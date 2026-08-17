# Phonon Grafana Dashboard

## Import

```sh
# Via Grafana API (replace URL, key)
curl -X POST http://monitor.chezgoulet.org/api/dashboards/db \
  -H "Authorization: Bearer $GRAFANA_API_KEY" \
  -H "Content-Type: application/json" \
  -d @grafana/phonon-cluster.json
```

Or via the Grafana UI: **Import** → paste or upload `grafana/phonon-cluster.json`.

## Metrics Exposed

The coordinator exposes Prometheus metrics on `GET /metrics`. The dashboard consumes:

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `phonon_nodes_online` | GaugeVec | `group` | Nodes online per group |
| `phonon_nodes_offline` | GaugeVec | `group` | Nodes offline per group |
| `phonon_nodes_overheating` | Gauge | — | Total overheating nodes |
| `phonon_requests_active` | Gauge | — | Current in-flight requests |
| `phonon_requests_total` | CounterVec | `group`, `status` | Total requests by group |
| `phonon_request_duration_ms` | HistogramVec | `group` | Request latency |
| `phonon_queue_depth` | GaugeVec | `device_id` | Per-device in-flight count |
| `phonon_battery_level` | GaugeVec | `device_id` | Battery percentage |
| `phonon_thermal_temp_c` | GaugeVec | `device_id` | SoC temperature |
| `phonon_inference_duration_ms` | Histogram | — | End-to-end inference latency |
| `phonon_inference_tokens_per_second` | Histogram | — | Token throughput |
| `phonon_inference_errors_total` | CounterVec | `error_type` | Errors by type |
| `phonon_inference_retries_total` | Counter | — | Retry count |

## Recommended Alerts

Set these in your Grafana alerting or monitoring stack:

| Rule | Condition | Severity |
|------|-----------|----------|
| **All nodes offline** | `sum(phonon_nodes_online) == 0` for 5m | critical |
| **Node overheating** | `phonon_thermal_temp_c > 45` for 2m | warning |
| **High error rate** | `rate(phonon_inference_errors_total[5m]) > 0.5` for 5m | warning |
| **Inference stall** | `rate(phonon_inference_duration_ms_bucket{le="+Inf"}[5m]) == 0` and `phonon_requests_active > 0` for 2m | critical |
| **Coordinator down** | Absence of any `phonon_*` metric for 30s | critical |
| **Low battery** | `phonon_battery_level < 15` and `phonon_nodes_online > 0` for 1m | warning |
