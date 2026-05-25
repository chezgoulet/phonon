import { useMemo } from "react";
import { getClusterNodes, type ClusterNode } from "../lib/api";

interface Props {
  node: ClusterNode;
  onBack: () => void;
}

function tempBarColor(c: number): string {
  if (c <= 35) return "bg-phonon-success";
  if (c <= 42) return "bg-phonon-warning";
  return "bg-phonon-danger";
}

function batteryColor(l: number): string {
  if (l > 60) return "bg-phonon-success";
  if (l > 25) return "bg-phonon-warning";
  return "bg-phonon-danger";
}

export default function HealthDetail({ node, onBack }: Props) {
  const { telemetry: t } = node;

  const telemetryItems = useMemo(
    () => [
      {
        label: "Battery",
        value: `${Math.round(t.battery_level)}%`,
        detail: t.is_charging ? "Charging" : "Not charging",
        bar: t.battery_level / 100,
        barColor: batteryColor(t.battery_level),
      },
      {
        label: "Temperature",
        value: `${Math.round(t.thermal_temp_c)}°C`,
        detail: t.thermal_temp_c <= 35 ? "Normal" : t.thermal_temp_c <= 42 ? "Warm" : "Hot",
        bar: Math.min(t.thermal_temp_c / 60, 1),
        barColor: tempBarColor(t.thermal_temp_c),
      },
      {
        label: "Queue Depth",
        value: String(t.queue_depth),
        detail: "Pending requests",
        bar: 0, // no bar for queue
        barColor: "",
      },
    ],
    [t]
  );

  return (
    <div className="mx-auto max-w-3xl p-6">
      {/* Back button */}
      <button
        onClick={onBack}
        className="mb-4 flex items-center gap-1 text-sm text-phonon-muted hover:text-phonon-text"
      >
        <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
        </svg>
        Back to Dashboard
      </button>

      {/* Header */}
      <div className="mb-6">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-xl font-bold text-phonon-text">{node.name}</h2>
            <p className="text-sm text-phonon-muted">{node.device_model}</p>
          </div>
          <div className="flex items-center gap-2">
            <span
              className={`h-3 w-3 rounded-full ${
                node.state === "online"
                  ? "bg-phonon-success"
                  : node.state === "paired"
                  ? "bg-phonon-warning"
                  : "bg-phonon-muted"
              }`}
            />
            <span className="text-sm font-medium">{node.state}</span>
          </div>
        </div>
      </div>

      {/* Detail grid */}
      <div className="grid gap-4 md:grid-cols-2">
        {/* Telemetry */}
        <div className="rounded-lg border border-phonon-border bg-phonon-card p-4">
          <h3 className="mb-3 text-sm font-medium text-phonon-muted">
            Telemetry
          </h3>
          <div className="space-y-3">
            {telemetryItems.map((item) => (
              <div key={item.label}>
                <div className="flex items-center justify-between text-sm">
                  <span className="text-phonon-muted">{item.label}</span>
                  <span className="font-medium text-phonon-text">
                    {item.value}
                  </span>
                </div>
                {item.bar > 0 && (
                  <div className="mt-1 h-1.5 w-full rounded-full bg-phonon-bg">
                    <div
                      className={`h-full rounded-full transition-all ${item.barColor}`}
                      style={{ width: `${item.bar * 100}%` }}
                    />
                  </div>
                )}
                <p className="text-[10px] text-phonon-muted">{item.detail}</p>
              </div>
            ))}
          </div>
        </div>

        {/* Device info */}
        <div className="rounded-lg border border-phonon-border bg-phonon-card p-4">
          <h3 className="mb-3 text-sm font-medium text-phonon-muted">
            Device Info
          </h3>
          <dl className="space-y-2 text-sm">
            <div className="flex justify-between">
              <dt className="text-phonon-muted">Device ID</dt>
              <dd className="font-mono text-phonon-text">{node.device_id}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-phonon-muted">IP Address</dt>
              <dd className="font-mono text-phonon-text">
                {node.ip_address || "—"}
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-phonon-muted">Group</dt>
              <dd className="text-phonon-text">
                {node.group ? (
                  <span className="rounded bg-phonon-accent/10 px-2 py-0.5 font-mono text-xs text-phonon-accent">
                    {node.group}
                  </span>
                ) : (
                  "—"
                )}
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-phonon-muted">Model</dt>
              <dd className="font-mono text-phonon-text">
                {node.model_loaded || "none"}
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-phonon-muted">Uptime</dt>
              <dd className="text-phonon-text">{node.uptime || "—"}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-phonon-muted">Registered</dt>
              <dd className="text-phonon-text">
                {node.registered_at
                  ? new Date(node.registered_at).toLocaleDateString()
                  : "—"}
              </dd>
            </div>
          </dl>
        </div>
      </div>
    </div>
  );
}
