import { useEffect, useMemo, useRef, useState } from "react";
import { getClusterNodes, type ClusterNode } from "../lib/api";
import Sparkline from "./Sparkline";

interface Props {
  node: ClusterNode;
  onBack: () => void;
}

// Live telemetry poll cadence. At 10s, HISTORY_MAX samples ≈ 1 hour and the
// queue window (QUEUE_WINDOW samples) ≈ 5 minutes.
const POLL_MS = 10_000;
const HISTORY_MAX = 360; // ~1 hour at 10s
const QUEUE_WINDOW = 30; // ~5 minutes at 10s

interface Sample {
  battery: number;
  temp: number;
  queue: number;
}

// Palette hexes (mirror tailwind.config phonon.*), for canvas strokes.
const COLOR_BATTERY = "#38bdf8"; // accent
const COLOR_TEMP = "#eab308"; // warning
const COLOR_QUEUE = "#22c55e"; // success

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
  // Live node telemetry: seed from the passed snapshot, then refresh by
  // polling /api/v1/cluster/nodes and matching on device_id.
  const [live, setLive] = useState<ClusterNode>(node);
  const [history, setHistory] = useState<Sample[]>(() => [
    {
      battery: node.telemetry.battery_level,
      temp: node.telemetry.thermal_temp_c,
      queue: node.in_flight,
    },
  ]);
  const deviceId = node.device_id;
  // Keep the latest device_id available to the interval without re-subscribing.
  const deviceIdRef = useRef(deviceId);
  deviceIdRef.current = deviceId;

  useEffect(() => {
    let cancelled = false;

    const poll = async () => {
      try {
        const res = await getClusterNodes();
        if (cancelled) return;
        const fresh = res.data.find((n) => n.device_id === deviceIdRef.current);
        if (!fresh) return;
        setLive(fresh);
        setHistory((prev) => {
          const next = [
            ...prev,
            {
              battery: fresh.telemetry.battery_level,
              temp: fresh.telemetry.thermal_temp_c,
              queue: fresh.in_flight,
            },
          ];
          return next.length > HISTORY_MAX ? next.slice(next.length - HISTORY_MAX) : next;
        });
      } catch {
        /* transient — keep last known values */
      }
    };

    const timer = setInterval(poll, POLL_MS);
    poll();
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [deviceId]);

  const t = live.telemetry;

  const batterySeries = useMemo(() => history.map((s) => s.battery), [history]);
  const tempSeries = useMemo(() => history.map((s) => s.temp), [history]);
  const queueSeries = useMemo(
    () => history.slice(-QUEUE_WINDOW).map((s) => s.queue),
    [history]
  );

  const telemetryItems = useMemo(
    () => [
      {
        label: "Battery",
        value: `${Math.round(t.battery_level)}%`,
        detail: t.is_charging ? "Charging · last hour" : "Not charging · last hour",
        bar: t.battery_level / 100,
        barColor: batteryColor(t.battery_level),
        series: batterySeries,
        seriesColor: COLOR_BATTERY,
        rangeMin: 0,
        rangeMax: 100,
      },
      {
        label: "Temperature",
        value: `${Math.round(t.thermal_temp_c)}°C`,
        detail:
          (t.thermal_temp_c <= 35 ? "Normal" : t.thermal_temp_c <= 42 ? "Warm" : "Hot") +
          " · last hour",
        bar: Math.min(t.thermal_temp_c / 60, 1),
        barColor: tempBarColor(t.thermal_temp_c),
        series: tempSeries,
        seriesColor: COLOR_TEMP,
        rangeMin: 20,
        rangeMax: 60,
      },
      {
        label: "In-Flight",
        value: String(live.in_flight),
        detail: "Active requests (coordinator) · last 5 min",
        bar: 0, // no bar for queue
        barColor: "",
        series: queueSeries,
        seriesColor: COLOR_QUEUE,
        rangeMin: undefined,
        rangeMax: undefined,
      },
    ],
    [t, live.in_flight, batterySeries, tempSeries, queueSeries]
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
                live.state === "online"
                  ? "bg-phonon-success"
                  : live.state === "paired"
                  ? "bg-phonon-warning"
                  : "bg-phonon-muted"
              }`}
            />
            <span className="text-sm font-medium">{live.state}</span>
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
                <div className="mt-1">
                  <Sparkline
                    data={item.series}
                    color={item.seriesColor}
                    min={item.rangeMin}
                    max={item.rangeMax}
                    width={240}
                    height={32}
                    className="w-full"
                  />
                </div>
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
                {live.model_loaded || "none"}
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
