package health

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

// setupProbeTest builds a monitor with probing enabled and a scriptable
// probe function.
func setupProbeTest(t *testing.T) (*Monitor, *registry.Registry, *probeScript) {
	t.Helper()
	reg := registry.New()
	cfg := DefaultMonitorConfig()
	cfg.CheckInterval = time.Hour // manual Check() only
	cfg.InferencePort = 9876
	m := NewMonitor(reg, cfg)

	script := &probeScript{results: map[string]error{}}
	m.probeFn = script.probe
	return m, reg, script
}

// probeScript maps probe URLs (by contained IP) to outcomes.
type probeScript struct {
	mu      sync.Mutex
	results map[string]error // key: substring of URL (device IP)
	urls    []string
}

func (s *probeScript) probe(_ context.Context, url string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.urls = append(s.urls, url)
	for key, err := range s.results {
		if strings.Contains(url, key) {
			return err
		}
	}
	return nil
}

func (s *probeScript) set(ip string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.results[ip] = err
}

func onlinePhone(t *testing.T, reg *registry.Registry, id, ip string) {
	t.Helper()
	if err := reg.Register(id, "phone", ip); err != nil {
		t.Fatal(err)
	}
	if err := reg.Pair(id); err != nil {
		t.Fatal(err)
	}
	if err := reg.UpdateHeartbeat(id, registry.HealthTelemetry{
		BatteryLevel: 80, ThermalTempC: 30,
	}); err != nil {
		t.Fatal(err)
	}
}

func excludeReason(t *testing.T, reg *registry.Registry, id string) string {
	t.Helper()
	n, ok := reg.Get(id)
	if !ok {
		t.Fatalf("node %s not found", id)
	}
	return n.ExcludeReason
}

func TestProbe_ThreeConsecutiveFailuresExclude(t *testing.T) {
	m, reg, script := setupProbeTest(t)
	onlinePhone(t, reg, "phone-01", "10.0.0.5")
	script.set("10.0.0.5", errors.New("connection refused"))

	// Two failures: still included.
	m.Check()
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "" {
		t.Fatalf("after 2 failures node must still be included, got reason %q", r)
	}

	// Third consecutive failure: excluded with the probe-owned reason.
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "inference_unreachable" {
		t.Fatalf("after 3 failures expected inference_unreachable, got %q", r)
	}
}

func TestProbe_SuccessResetsCounterAndClearsReason(t *testing.T) {
	m, reg, script := setupProbeTest(t)
	onlinePhone(t, reg, "phone-01", "10.0.0.5")

	// Fail twice, then succeed: counter must reset.
	script.set("10.0.0.5", errors.New("refused"))
	m.Check()
	m.Check()
	script.set("10.0.0.5", nil)
	m.Check()
	script.set("10.0.0.5", errors.New("refused"))
	m.Check()
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "" {
		t.Fatalf("interleaved success must reset the failure counter, got reason %q", r)
	}

	// Now trip it, then recover: reason clears on first success.
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "inference_unreachable" {
		t.Fatalf("expected exclusion after third straight failure, got %q", r)
	}
	script.set("10.0.0.5", nil)
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "" {
		t.Fatalf("successful probe must clear inference_unreachable, got %q", r)
	}
}

func TestProbe_TelemetryEvaluationDoesNotClearProbeReason(t *testing.T) {
	m, reg, script := setupProbeTest(t)
	onlinePhone(t, reg, "phone-01", "10.0.0.5")
	script.set("10.0.0.5", errors.New("refused"))
	m.Check()
	m.Check()
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "inference_unreachable" {
		t.Fatalf("setup: expected exclusion, got %q", r)
	}

	// A perfectly healthy heartbeat arrives (evaluateNode would return ""
	// for it). The probe-owned reason must survive telemetry evaluation.
	if err := reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{
		BatteryLevel: 95, ThermalTempC: 25, IsCharging: true,
	}); err != nil {
		t.Fatal(err)
	}
	m.evaluateNodes(context.Background())
	if r := excludeReason(t, reg, "phone-01"); r != "inference_unreachable" {
		t.Fatalf("telemetry evaluation clobbered the probe-owned reason: %q", r)
	}
}

func TestProbe_DoesNotOverwriteTelemetryReason(t *testing.T) {
	m, reg, script := setupProbeTest(t)
	onlinePhone(t, reg, "phone-01", "10.0.0.5")

	// Overheat the phone: telemetry owns the exclusion.
	if err := reg.UpdateHeartbeat("phone-01", registry.HealthTelemetry{
		BatteryLevel: 80, ThermalTempC: 55,
	}); err != nil {
		t.Fatal(err)
	}
	script.set("10.0.0.5", errors.New("refused"))
	m.Check()
	m.Check()
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "overheating" {
		t.Fatalf("probe must not overwrite a telemetry-managed reason, got %q", r)
	}

	// And a probe success must NOT clear a reason the probe doesn't own.
	script.set("10.0.0.5", nil)
	m.Check()
	if r := excludeReason(t, reg, "phone-01"); r != "overheating" {
		t.Fatalf("probe success cleared a telemetry-managed reason: %q", r)
	}
}

func TestProbe_DisabledWhenPortUnset(t *testing.T) {
	reg := registry.New()
	cfg := DefaultMonitorConfig()
	cfg.CheckInterval = time.Hour
	cfg.InferencePort = 0 // disabled
	m := NewMonitor(reg, cfg)
	called := false
	m.probeFn = func(context.Context, string) error {
		called = true
		return errors.New("refused")
	}
	onlinePhone(t, reg, "phone-01", "10.0.0.5")
	m.Check()
	m.Check()
	m.Check()
	if called {
		t.Error("probe must not run when InferencePort <= 0")
	}
	if r := excludeReason(t, reg, "phone-01"); r != "" {
		t.Errorf("no exclusion expected with probing disabled, got %q", r)
	}
}

func TestProbe_SkipsOfflineNodes(t *testing.T) {
	m, reg, script := setupProbeTest(t)
	onlinePhone(t, reg, "phone-01", "10.0.0.5")
	if err := reg.SetOffline("phone-01"); err != nil {
		t.Fatal(err)
	}
	m.Check()
	script.mu.Lock()
	probed := len(script.urls)
	script.mu.Unlock()
	if probed != 0 {
		t.Errorf("offline nodes must not be probed, got %d probes", probed)
	}
}

func TestProbe_URLUsesConfiguredPort(t *testing.T) {
	m, reg, script := setupProbeTest(t)
	m.cfg.InferencePort = 12345
	onlinePhone(t, reg, "phone-01", "10.0.0.5")
	m.Check()
	script.mu.Lock()
	defer script.mu.Unlock()
	if len(script.urls) != 1 || script.urls[0] != "http://10.0.0.5:12345/health" {
		t.Errorf("unexpected probe URLs: %v", script.urls)
	}
}

func TestDefaultSidecarProbe_AgainstHTTPServer(t *testing.T) {
	okSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(w, `{"status":"ok","model_loaded":true}`)
	}))
	defer okSrv.Close()
	if err := defaultSidecarProbe(context.Background(), okSrv.URL+"/health"); err != nil {
		t.Errorf("200 response should probe healthy: %v", err)
	}

	failSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer failSrv.Close()
	if err := defaultSidecarProbe(context.Background(), failSrv.URL+"/health"); err == nil {
		t.Error("non-200 response must be a probe failure")
	}

	// Connection refused (closed server).
	deadSrv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	deadURL := deadSrv.URL
	deadSrv.Close()
	if err := defaultSidecarProbe(context.Background(), deadURL+"/health"); err == nil {
		t.Error("connection refused must be a probe failure")
	}

	// Deadline enforcement against a stalling server.
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(2 * time.Second)
	}))
	defer slow.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	start := time.Now()
	if err := defaultSidecarProbe(ctx, slow.URL+"/health"); err == nil {
		t.Error("stalled server must time out")
	} else if time.Since(start) > time.Second {
		t.Errorf("probe ignored the context deadline (took %v)", time.Since(start))
	}
}
