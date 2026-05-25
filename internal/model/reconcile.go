package model

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/chezgoulet/phonon/internal/config"
	"github.com/chezgoulet/phonon/internal/registry"
)

// CommandIssuer abstracts the ability to send model commands to a phone.
// The WSHandler in internal/api implements this.
type CommandIssuer interface {
	SendModelPush(deviceID, model, url, checksum string, sizeBytes int64) (string, error)
	SendModelLoad(deviceID, model string) (string, error)
	SendModelUnload(deviceID string) (string, error)
	HasConnection(deviceID string) bool
}

// Reconciler continuously reconciles desired model state (from config) with
// current state (from registry), issuing commands via the CommandIssuer.
type Reconciler struct {
	cache    *Cache
	reg      *registry.Registry
	issuer   CommandIssuer
	baseURL  string // coordinator base URL for model downloads
	log      *slog.Logger
	interval time.Duration

	mu      sync.Mutex
	cancel  context.CancelFunc
	running bool

	// Track which groups are in the middle of a rolling update.
	rollingGroups map[string]bool
}

// NewReconciler creates a reconciler. baseURL is the coordinator URL for
// model download links (e.g. "http://10.0.0.1:9876").
func NewReconciler(cache *Cache, reg *registry.Registry, issuer CommandIssuer, baseURL string) *Reconciler {
	return &Reconciler{
		cache:         cache,
		reg:           reg,
		issuer:        issuer,
		baseURL:       baseURL,
		log:           slog.With("component", "reconciler"),
		interval:      30 * time.Second,
		rollingGroups: make(map[string]bool),
	}
}

// Start begins the reconciliation loop.
func (r *Reconciler) Start(ctx context.Context, groups []config.GroupConfig) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.running {
		return fmt.Errorf("reconciler already running")
	}

	ctx, cancel := context.WithCancel(ctx)
	r.cancel = cancel
	r.running = true

	go r.loop(ctx, groups)
	r.log.Info("reconciler started", "interval", r.interval)
	return nil
}

// Stop terminates the reconciliation loop.
func (r *Reconciler) Stop() {
	r.mu.Lock()
	defer r.mu.Unlock()

	if !r.running {
		return
	}
	r.cancel()
	r.running = false
	r.log.Info("reconciler stopped")
}

// loop runs periodic reconciliation.
func (r *Reconciler) loop(ctx context.Context, groups []config.GroupConfig) {
	ticker := time.NewTicker(r.interval)
	defer ticker.Stop()

	// Run an initial reconciliation immediately
	r.reconcileAll(groups)

	for {
		select {
		case <-ticker.C:
			r.reconcileAll(groups)
		case <-ctx.Done():
			return
		}
	}
}

// reconcileAll runs reconciliation for all configured groups.
func (r *Reconciler) reconcileAll(groups []config.GroupConfig) {
	for i := range groups {
		steps := r.ReconcileGroup(&groups[i])
		for j := range steps {
			r.executeStep(&steps[j])
		}
	}
}

// ReconcileGroup compares desired vs current state for a group and returns
// the steps needed. Exported for testing.
func (r *Reconciler) ReconcileGroup(g *config.GroupConfig) []ReconcilerStep {
	var steps []ReconcilerStep

	if g.Model == "" {
		return steps
	}

	modelURL := r.cachedDownloadURL(g.Model)
	needDownload := modelURL == "" && !r.cache.Has(g.Model)

	for _, phoneID := range g.Phones {
		steps = append(steps, r.reconcilePhone(phoneID, g.Model, g.Checksum, modelURL, needDownload)...)
	}

	// Handle standby phones: just ensure they have the model cached
	for _, standbyID := range g.Standby {
		if r.cache.Has(g.Model) && r.issuer.HasConnection(standbyID) {
			node, ok := r.reg.Get(standbyID)
			if !ok || !node.ModelStatus.Loaded || node.ModelStatus.Name != g.Model {
				steps = append(steps, ReconcilerStep{
					DeviceID:  standbyID,
					Action:    ActionPush,
					ModelName: g.Model,
					SHA256:    g.Checksum,
				})
			}
		}
	}

	return steps
}

// cachedDownloadURL returns the download URL if the model is cached, or empty.
func (r *Reconciler) cachedDownloadURL(modelName string) string {
	if r.cache.Has(modelName) && r.baseURL != "" {
		return URL(r.baseURL, modelName)
	}
	return ""
}

// reconcilePhone computes steps needed for one phone.
func (r *Reconciler) reconcilePhone(deviceID, desiredModel, checksum, modelURL string, needDownload bool) []ReconcilerStep {
	node, ok := r.reg.Get(deviceID)
	if !ok {
		r.log.Debug("phone not registered", "device_id", deviceID)
		return nil
	}

	if node.State != registry.NodeStateOnline {
		return nil
	}

	// If model not cached and need download, skip for now
	if needDownload {
		r.log.Info("model not cached and no URL for download",
			"model", desiredModel, "device_id", deviceID)
		return nil
	}

	currentName := node.ModelStatus.Name
	currentLoaded := node.ModelStatus.Loaded

	if currentLoaded && currentName == desiredModel {
		return nil
	}

	// Model needs to change
	if modelURL == "" && !r.cache.Has(desiredModel) {
		r.log.Info("model not cached, cannot push", "model", desiredModel, "device_id", deviceID)
		return nil
	}

	entry := r.cachedEntry(desiredModel)

	if !r.cache.Has(desiredModel) {
		// Can't push without cache
		return nil
	}

	pushStep := ReconcilerStep{
		DeviceID:  deviceID,
		ModelName: desiredModel,
		URL:       URL(r.baseURL, desiredModel),
		SHA256:    checksum,
	}

	if entry != nil {
		pushStep.SizeBytes = entry.SizeBytes
	}

	if currentName != desiredModel || !currentLoaded {
		if currentLoaded {
			// Emit unload + push in one shot so the model change converges in
			// a single reconciliation cycle instead of requiring two.
			unloadStep := ReconcilerStep{
				DeviceID: deviceID,
				Action:   ActionUnload,
			}
			pushStep.Action = ActionPush
			return []ReconcilerStep{unloadStep, pushStep}
		}
		pushStep.Action = ActionPush
		return []ReconcilerStep{pushStep}
	}

	return nil
}

func (r *Reconciler) cachedEntry(modelName string) *CacheEntry {
	for _, e := range r.cache.List() {
		if e.Name == modelName {
			return &e
		}
	}
	return nil
}

// executeStep sends the command for a single reconciliation step.
func (r *Reconciler) executeStep(step *ReconcilerStep) {
	if !r.issuer.HasConnection(step.DeviceID) {
		r.log.Debug("device not connected, deferring", "device_id", step.DeviceID)
		return
	}

	switch step.Action {
	case ActionPush:
		r.log.Info("pushing model", "device_id", step.DeviceID, "model", step.ModelName)
		if _, err := r.issuer.SendModelPush(step.DeviceID, step.ModelName, step.URL, step.SHA256, step.SizeBytes); err != nil {
			r.log.Error("push command failed", "device_id", step.DeviceID, "error", err)
		}

	case ActionLoad:
		r.log.Info("loading model", "device_id", step.DeviceID, "model", step.ModelName)
		if _, err := r.issuer.SendModelLoad(step.DeviceID, step.ModelName); err != nil {
			r.log.Error("load command failed", "device_id", step.DeviceID, "error", err)
		}

	case ActionUnload:
		r.log.Info("unloading model", "device_id", step.DeviceID)
		if _, err := r.issuer.SendModelUnload(step.DeviceID); err != nil {
			r.log.Error("unload command failed", "device_id", step.DeviceID, "error", err)
		}
	}
}

// SetInterval changes the reconciliation interval. Only takes effect on the
// next Start call.
func (r *Reconciler) SetInterval(d time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if d > 0 {
		r.interval = d
	}
}
