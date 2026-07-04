import { useEffect, useMemo, useState } from "react";
import { getEvents, type ClusterEvent, type EventSeverity } from "../lib/api";

const REFRESH_MS = 30_000;
const FETCH_LIMIT = 200;

type SeverityFilter = "all" | EventSeverity;

// Quick time-range presets → RFC3339 `since` value (undefined = all time).
const TIME_RANGES: { id: string; label: string; ms: number | null }[] = [
  { id: "15m", label: "15 min", ms: 15 * 60_000 },
  { id: "1h", label: "1 hour", ms: 60 * 60_000 },
  { id: "24h", label: "24 hours", ms: 24 * 60 * 60_000 },
  { id: "all", label: "All", ms: null },
];

// Normalizes the server's severity strings (info/warning/error) to a bucket.
function severityBucket(sev: string): EventSeverity {
  if (sev === "error") return "error";
  if (sev === "warning") return "warning";
  return "info";
}

const SEVERITY_STYLE: Record<EventSeverity, { dot: string; text: string; row: string }> = {
  info: { dot: "bg-phonon-success", text: "text-phonon-success", row: "" },
  warning: { dot: "bg-phonon-warning", text: "text-phonon-warning", row: "bg-phonon-warning/5" },
  error: { dot: "bg-phonon-danger", text: "text-phonon-danger", row: "bg-phonon-danger/5" },
};

function formatTime(ts: string): string {
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return ts;
  return d.toLocaleString(undefined, {
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export default function EventLogViewer() {
  const [events, setEvents] = useState<ClusterEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [severity, setSeverity] = useState<SeverityFilter>("all");
  const [device, setDevice] = useState<string>("all");
  const [range, setRange] = useState<string>("1h");

  // Refetch on mount, when the time range changes, and every REFRESH_MS.
  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      const preset = TIME_RANGES.find((r) => r.id === range);
      const since =
        preset && preset.ms != null
          ? new Date(Date.now() - preset.ms).toISOString()
          : undefined;
      try {
        const res = await getEvents({ limit: FETCH_LIMIT, since });
        if (cancelled) return;
        setEvents(res.data ?? []);
        setError(null);
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : "failed to load events");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [range]);

  // Device options derived from the events currently loaded.
  const devices = useMemo(() => {
    const set = new Set<string>();
    for (const e of events) if (e.device_id) set.add(e.device_id);
    return Array.from(set).sort();
  }, [events]);

  const filtered = useMemo(
    () =>
      events.filter((e) => {
        if (severity !== "all" && severityBucket(e.severity) !== severity) return false;
        if (device !== "all" && e.device_id !== device) return false;
        return true;
      }),
    [events, severity, device]
  );

  return (
    <div className="mx-auto max-w-6xl p-6">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold tracking-tight">Event Log</h2>
          <p className="text-xs text-phonon-muted">
            Node lifecycle, model, and pairing events · auto-refreshes every 30s
          </p>
        </div>
        <span className="text-xs text-phonon-muted">
          {filtered.length} of {events.length} shown
        </span>
      </div>

      {/* Filter bar */}
      <div className="mb-4 flex flex-wrap items-center gap-4">
        {/* Severity */}
        <div className="flex gap-1 rounded-lg bg-phonon-bg p-1">
          {(["all", "info", "warning", "error"] as SeverityFilter[]).map((s) => (
            <button
              key={s}
              onClick={() => setSeverity(s)}
              className={`rounded-md px-3 py-1 text-xs font-medium capitalize transition-colors ${
                severity === s
                  ? "bg-phonon-accent text-white"
                  : "text-phonon-muted hover:text-phonon-text"
              }`}
            >
              {s}
            </button>
          ))}
        </div>

        {/* Device */}
        <select
          value={device}
          onChange={(e) => setDevice(e.target.value)}
          className="rounded-md border border-phonon-border bg-phonon-bg px-3 py-1 text-xs text-phonon-text"
        >
          <option value="all">All devices</option>
          {devices.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>

        {/* Time range */}
        <div className="flex gap-1 rounded-lg bg-phonon-bg p-1">
          {TIME_RANGES.map((r) => (
            <button
              key={r.id}
              onClick={() => setRange(r.id)}
              className={`rounded-md px-3 py-1 text-xs font-medium transition-colors ${
                range === r.id
                  ? "bg-phonon-accent text-white"
                  : "text-phonon-muted hover:text-phonon-text"
              }`}
            >
              {r.label}
            </button>
          ))}
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-md border border-phonon-danger/40 bg-phonon-danger/10 px-4 py-2 text-sm text-phonon-danger">
          {error}
        </div>
      )}

      {/* Table */}
      <div className="overflow-x-auto rounded-lg border border-phonon-border">
        <table className="w-full text-left text-sm">
          <thead className="bg-phonon-card text-xs uppercase tracking-wide text-phonon-muted">
            <tr>
              <th className="px-4 py-2 font-medium">Time</th>
              <th className="px-4 py-2 font-medium">Severity</th>
              <th className="px-4 py-2 font-medium">Event</th>
              <th className="px-4 py-2 font-medium">Device</th>
              <th className="px-4 py-2 font-medium">Details</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-phonon-border">
            {loading ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-phonon-muted">
                  Loading…
                </td>
              </tr>
            ) : filtered.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-phonon-muted">
                  No events match the current filters.
                </td>
              </tr>
            ) : (
              filtered.map((e) => {
                const style = SEVERITY_STYLE[severityBucket(e.severity)];
                return (
                  <tr key={e.id} className={style.row}>
                    <td className="whitespace-nowrap px-4 py-2 font-mono text-xs text-phonon-muted">
                      {formatTime(e.timestamp)}
                    </td>
                    <td className="px-4 py-2">
                      <span className="flex items-center gap-2">
                        <span className={`h-2 w-2 rounded-full ${style.dot}`} />
                        <span className={`text-xs capitalize ${style.text}`}>
                          {severityBucket(e.severity)}
                        </span>
                      </span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 font-mono text-xs">
                      {e.event_type}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 font-mono text-xs text-phonon-muted">
                      {e.device_id ? e.device_id.slice(0, 12) : "—"}
                    </td>
                    <td className="px-4 py-2 text-xs text-phonon-muted">{e.details || "—"}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
