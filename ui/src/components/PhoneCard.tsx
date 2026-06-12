import type { ClusterNode } from "../lib/api";

interface Props {
  node: ClusterNode;
  onClick: () => void;
}

function tempColor(c: number): string {
  if (c <= 35) return "text-phonon-success";
  if (c <= 42) return "text-phonon-warning";
  return "text-phonon-danger";
}

function batteryIcon(level: number, charging: boolean): string {
  if (level <= 15) return "🔴";
  if (level <= 30) return "🟡";
  if (charging) return "⚡";
  return "🔋";
}

export default function PhoneCard({ node, onClick }: Props) {
  const { telemetry: t } = node;
  const isOnline = node.state === "online";

  return (
    <button
      onClick={onClick}
      className="group relative w-full rounded-lg border border-phonon-border bg-phonon-card p-4 text-left transition-all hover:border-phonon-accent/50 hover:shadow-lg hover:shadow-phonon-accent/5"
    >
      {/* State indicator */}
      <div className="absolute right-3 top-3 flex items-center gap-1.5">
        <span
          className={`h-2 w-2 rounded-full ${
            isOnline ? "bg-phonon-success" : node.state === "paired" ? "bg-phonon-warning" : "bg-phonon-muted"
          }`}
        />
        <span className="text-[10px] uppercase tracking-wider text-phonon-muted">
          {node.state}
        </span>
      </div>

      {/* Name + Model */}
      <div className="mb-3">
        <h3 className="font-semibold text-phonon-text">{node.name}</h3>
        <p className="text-xs text-phonon-muted">{node.device_model}</p>
      </div>

      {/* Group badge */}
      {node.group && (
        <div className="mb-3">
          <span className="inline-block rounded bg-phonon-accent/10 px-2 py-0.5 font-mono text-[11px] text-phonon-accent">
            {node.group}
          </span>
        </div>
      )}

      {/* Model loaded */}
      {node.model_loaded && (
        <div className="mb-3 flex items-center gap-2 text-xs text-phonon-text">
          <span>
            <span className="text-phonon-muted">Model: </span>
            <span className="font-mono">{node.model_loaded}</span>
          </span>
          {node.backend && <BackendBadge backend={node.backend} />}
        </div>
      )}

      {/* Telemetry row */}
      <div className="flex items-center justify-between border-t border-phonon-border pt-3 text-xs">
        {/* Battery */}
        <span className="flex items-center gap-1" title={`Battery: ${t.battery_level}%`}>
          <span>{batteryIcon(t.battery_level, t.is_charging)}</span>
          <span>{Math.round(t.battery_level)}%</span>
        </span>

        {/* Temperature */}
        <span className={`flex items-center gap-1 ${tempColor(t.thermal_temp_c)}`} title={`Temp: ${t.thermal_temp_c}°C`}>
          <span>🌡️</span>
          <span>{Math.round(t.thermal_temp_c)}°C</span>
        </span>

        {/* Queue depth */}
        {t.queue_depth > 0 && (
          <span className="text-phonon-muted" title="Pending requests">
            📨 {t.queue_depth}
          </span>
        )}
      </div>

      {/* Uptime */}
      {node.uptime && (
        <p className="mt-2 text-[10px] text-phonon-muted">{node.uptime}</p>
      )}
    </button>
  );
}

/**
 * Small chip showing which accelerator the phone's engine is running on.
 * NPU is the headline feature, so it gets the accent treatment; CPU is
 * rendered muted as a visual hint that the node is in fallback mode.
 */
function BackendBadge({ backend }: { backend: string }) {
  const styles: Record<string, string> = {
    npu: "bg-emerald-500/15 text-emerald-400",
    gpu: "bg-sky-500/15 text-sky-400",
    cpu: "bg-phonon-border/40 text-phonon-muted",
  };
  const label = backend.toUpperCase();
  return (
    <span
      className={`inline-block rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold ${styles[backend] ?? styles.cpu}`}
      title={`Inference accelerator: ${label}`}
    >
      {label}
    </span>
  );
}
