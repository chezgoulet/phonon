package health

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics holds all Prometheus metric collectors for the coordinator.
// Each monitor gets its own Metrics instance with its own registry.
type Metrics struct {
	NodesOnline      *prometheus.GaugeVec
	NodesOffline     *prometheus.GaugeVec
	NodesOverheating prometheus.Gauge
	RequestsTotal    *prometheus.CounterVec
	RequestDuration  *prometheus.HistogramVec
	QueueDepth       *prometheus.GaugeVec
	BatteryLevel     *prometheus.GaugeVec
	ThermalTempC     *prometheus.GaugeVec

	// Inference request observability (updated by the OpenAI handler).
	InferenceDuration        prometheus.Histogram
	InferenceTokensPerSecond prometheus.Histogram
	InferenceErrors          *prometheus.CounterVec
	InferenceRetries         prometheus.Counter
	RequestsActive           prometheus.Gauge

	registry     *prometheus.Registry
	registryOnce bool
}

// NewMetrics creates metric descriptors and a private registry.
func NewMetrics() *Metrics {
	return &Metrics{
		NodesOnline: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "phonon_nodes_online",
				Help: "Number of online nodes per group.",
			},
			[]string{"group"},
		),
		NodesOffline: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "phonon_nodes_offline",
				Help: "Number of offline nodes per group.",
			},
			[]string{"group"},
		),
		NodesOverheating: prometheus.NewGauge(
			prometheus.GaugeOpts{
				Name: "phonon_nodes_overheating",
				Help: "Total number of nodes currently overheating.",
			},
		),
		RequestsTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "phonon_requests_total",
				Help: "Total inference requests per group and status.",
			},
			[]string{"group", "status"},
		),
		RequestDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "phonon_request_duration_ms",
				Help:    "Inference request duration in milliseconds per group.",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"group"},
		),
		QueueDepth: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "phonon_queue_depth",
				Help: "Current inference queue depth per device.",
			},
			[]string{"device_id"},
		),
		BatteryLevel: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "phonon_battery_level",
				Help: "Current battery level percentage per device.",
			},
			[]string{"device_id"},
		),
		ThermalTempC: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "phonon_thermal_temp_c",
				Help: "Current SoC temperature in Celsius per device.",
			},
			[]string{"device_id"},
		),
		InferenceDuration: prometheus.NewHistogram(
			prometheus.HistogramOpts{
				Name:    "phonon_inference_duration_ms",
				Help:    "End-to-end inference duration in milliseconds.",
				Buckets: []float64{100, 500, 1000, 5000, 10000, 30000, 60000},
			},
		),
		InferenceTokensPerSecond: prometheus.NewHistogram(
			prometheus.HistogramOpts{
				Name:    "phonon_inference_tokens_per_second",
				Help:    "Completion tokens per second per inference request.",
				Buckets: []float64{1, 2, 5, 10, 20, 50, 100, 200},
			},
		),
		InferenceErrors: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "phonon_inference_errors_total",
				Help: "Inference request failures by error type.",
			},
			[]string{"error_type"}, // timeout | connection | phone_error | circuit_breaker
		),
		InferenceRetries: prometheus.NewCounter(
			prometheus.CounterOpts{
				Name: "phonon_inference_retries_total",
				Help: "Inference requests failed over to a fallback phone.",
			},
		),
		RequestsActive: prometheus.NewGauge(
			prometheus.GaugeOpts{
				Name: "phonon_requests_active",
				Help: "Currently active inference requests.",
			},
		),
	}
}

// Register all metrics into the private registry.
// Idempotent — subsequent calls are no-ops.
func (m *Metrics) Register() {
	if m.registryOnce {
		return
	}
	m.registryOnce = true
	m.registry = prometheus.NewRegistry()
	m.registry.MustRegister(m.NodesOnline)
	m.registry.MustRegister(m.NodesOffline)
	m.registry.MustRegister(m.NodesOverheating)
	m.registry.MustRegister(m.RequestsTotal)
	m.registry.MustRegister(m.RequestDuration)
	m.registry.MustRegister(m.QueueDepth)
	m.registry.MustRegister(m.BatteryLevel)
	m.registry.MustRegister(m.ThermalTempC)
	m.registry.MustRegister(m.InferenceDuration)
	m.registry.MustRegister(m.InferenceTokensPerSecond)
	m.registry.MustRegister(m.InferenceErrors)
	m.registry.MustRegister(m.InferenceRetries)
	m.registry.MustRegister(m.RequestsActive)
}

// Handler returns an HTTP handler that serves metrics from this instance's
// private registry. Returns the default promhttp handler if metrics haven't
// been registered yet.
func (m *Metrics) Handler() http.Handler {
	if m.registry == nil {
		return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("# phonon metrics not yet initialized\n"))
		})
	}
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}
