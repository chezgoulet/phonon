package api

import (
	"log/slog"
	"net/http"
	"slices"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

const statusDegraded = "degraded"

// ClusterHandler exposes aggregate cluster health data from the registry.
type ClusterHandler struct {
	reg *registry.Registry
	log *slog.Logger
}

// NewClusterHandler creates a cluster health handler.
func NewClusterHandler(reg *registry.Registry) *ClusterHandler {
	return &ClusterHandler{
		reg: reg,
		log: slog.With("component", "cluster"),
	}
}

// RegisterRoutes adds cluster health endpoints to the given mux.
func (h *ClusterHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/cluster/health", h.handleClusterHealth)
	mux.HandleFunc("GET /api/v1/cluster/nodes", h.handleListNodes)
}

// ClusterHealthResponse is the aggregate cluster health report.
type ClusterHealthResponse struct {
	Status      string           `json:"status"`       // healthy, degraded, offline
	TotalNodes  int              `json:"total_nodes"`
	OnlineNodes int              `json:"online_nodes"`
	OfflineNodes int             `json:"offline_nodes"`
	PairedNodes int              `json:"paired_nodes"`
	Groups      map[string]int   `json:"groups"`        // group name → node count
	StaleCount  int              `json:"stale_nodes"`   // nodes with no recent heartbeat
	Timestamp   time.Time        `json:"timestamp"`
}

func (h *ClusterHandler) handleClusterHealth(w http.ResponseWriter, _ *http.Request) {
	nodes := h.reg.List()

	online := 0
	offline := 0
	paired := 0
	unpaired := 0
	groups := make(map[string]int)
	for i := range nodes {
		node := &nodes[i]
		switch node.State {
		case registry.NodeStateOnline:
			online++
		case registry.NodeStateOffline:
			offline++
		case registry.NodeStatePaired:
			paired++
		default:
			unpaired++
		}

		if node.Group != "" {
			groups[node.Group]++
		}
	}

	// Determine aggregate status
	totalNodes := len(nodes)
	status := "healthy"
	if totalNodes == 0 {
		status = "offline"
	} else if online == 0 {
		status = statusDegraded
	} else if offline > 0 || paired > 0 || unpaired > 0 {
		status = statusDegraded
	}

	resp := ClusterHealthResponse{
		Status:       status,
		TotalNodes:   len(nodes),
		OnlineNodes:  online,
		OfflineNodes: offline + paired + unpaired,
		PairedNodes:  paired,
		Groups:       groups,
		StaleCount:   0,
		Timestamp:    time.Now(),
	}

	writeJSON(w, http.StatusOK, resp)
}

// ListNodeResponse contains a node's summary for the cluster list.
type ListNodeResponse struct {
	DeviceID     string                   `json:"device_id"`
	Name         string                   `json:"name"`
	DeviceModel  string                   `json:"device_model"`
	Group        string                   `json:"group"`
	State        string                   `json:"state"`
	IPAddress    string                   `json:"ip_address"`
	Telemetry    registry.HealthTelemetry `json:"telemetry"`
	ModelLoaded  string                   `json:"model_loaded,omitempty"`
	Backend      string                   `json:"backend,omitempty"` // active accelerator: npu/gpu/cpu
	Uptime       string                   `json:"uptime,omitempty"`
	RegisteredAt time.Time                `json:"registered_at"`
}

func (h *ClusterHandler) handleListNodes(w http.ResponseWriter, r *http.Request) {
	nodes := h.reg.List()

	// Support group filter
	groupFilter := r.URL.Query().Get("group")

	slices.SortFunc(nodes, func(a, b registry.Node) int {
		if a.State != b.State {
			if a.State == registry.NodeStateOnline {
				return -1
			}
			if b.State == registry.NodeStateOnline {
				return 1
			}
		}
		return 0
	})

	items := make([]ListNodeResponse, 0, len(nodes))
	for i := range nodes {
		node := &nodes[i]
		if groupFilter != "" && node.Group != groupFilter {
			continue
		}

		modelLoaded := ""
		if node.ModelStatus.Loaded {
			modelLoaded = node.ModelStatus.Name
		}

		uptime := ""
		if !node.RegisteredAt.IsZero() {
			uptime = time.Since(node.RegisteredAt).Truncate(time.Second).String()
		}

		items = append(items, ListNodeResponse{
			DeviceID:     node.DeviceID,
			Name:         node.Name,
			DeviceModel:  node.DeviceModel,
			Group:        node.Group,
			State:        string(node.State),
			IPAddress:    node.IPAddress,
			Telemetry:    node.Telemetry,
			ModelLoaded:  modelLoaded,
			Backend:      node.ModelStatus.Backend,
			Uptime:       uptime,
			RegisteredAt: node.RegisteredAt,
		})
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"object": "list",
		"data":   items,
	})
}
