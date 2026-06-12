import { useEffect, useRef, useState, useCallback } from "react";
import {
  getClusterNodes,
  getVizArrangement,
  setVizArrangement,
  setVizShowNumbers,
  type ClusterNode,
  type VizArrangementEntry,
} from "../lib/api";

const GRID_SIZE = 20; // px for snap
const PHONE_W = 120; // px card width on canvas
const PHONE_H = 70; // px card height on canvas

// Map node state to border color
function stateColor(state: string): string {
  switch (state) {
    case "online":
      return "border-phonon-success";
    case "offline":
      return "border-phonon-muted";
    default:
      return "border-phonon-warning";
  }
}

// Map node state to background tint
function stateBg(state: string): string {
  switch (state) {
    case "online":
      return "bg-phonon-success/10";
    case "offline":
      return "bg-phonon-muted/5";
    default:
      return "bg-phonon-warning/10";
  }
}

export default function ArrangementWidget() {
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [entries, setEntries] = useState<VizArrangementEntry[]>([]);
  const [showNumbers, setShowNumbers] = useState(true);
  const [dragging, setDragging] = useState<string | null>(null);
  const [shiftHeld, setShiftHeld] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Track Shift key state
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => setShiftHeld(e.shiftKey);
    window.addEventListener("keydown", onKey);
    window.addEventListener("keyup", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("keyup", onKey);
    };
  }, []);

  // Fetch devices and persisted arrangement on mount
  useEffect(() => {
    (async () => {
      try {
        const [nodeData, arrData] = await Promise.all([
          getClusterNodes(),
          getVizArrangement(),
        ]);
        setNodes(nodeData.data);
        if (arrData.data.length > 0) {
          setEntries(arrData.data);
        }
      } catch {
        /* widget still usable offline */
      }
    })();
  }, []);

  // Place a node on the canvas
  const placeDevice = useCallback(
    (deviceId: string) => {
      const existing = entries.find((e) => e.device_id === deviceId);
      if (existing) return; // already placed

      const takenPositions = new Set(
        entries.map((e) => `${e.position_x.toFixed(2)},${e.position_y.toFixed(2)}`)
      );

      // Find first free spot in a grid pattern
      const cols = Math.ceil(Math.sqrt(entries.length + 1));
      for (let row = 0; row < cols + 2; row++) {
        for (let col = 0; col < cols; col++) {
          const px = (col + 0.5) / (cols + 1);
          const py = (row + 0.5) / (cols + 1);
          const key = `${px.toFixed(2)},${py.toFixed(2)}`;
          if (!takenPositions.has(key)) {
            setEntries((prev) => [
              ...prev,
              {
                device_id: deviceId,
                display_number: prev.length + 1,
                position_x: px,
                position_y: py,
              },
            ]);
            return;
          }
        }
      }
    },
    [entries]
  );

  // Remove a device from canvas (back to sidebar)
  const removeDevice = useCallback((deviceId: string) => {
    setEntries((prev) => prev.filter((e) => e.device_id !== deviceId));
  }, []);

  // ── Drag handling ──

  const handlePointerDown = useCallback(
    (e: React.PointerEvent, deviceId: string) => {
      e.preventDefault();
      (e.target as HTMLElement).setPointerCapture(e.pointerId);
      setDragging(deviceId);
    },
    []
  );

  const handlePointerMove = useCallback(
    (e: React.PointerEvent) => {
      if (!dragging || !containerRef.current) return;

      const rect = containerRef.current.getBoundingClientRect();
      const padding = 8; // canvas padding

      // Calculate position in pixels relative to canvas (accounting for card size)
      let px = e.clientX - rect.left - padding;
      let py = e.clientY - rect.top - padding;

      // Clamp to canvas bounds
      px = Math.max(0, Math.min(px, rect.width - PHONE_W - padding * 2));
      py = Math.max(0, Math.min(py, rect.height - PHONE_H - padding * 2));

      // Grid snapping when Shift is held
      if (shiftHeld) {
        px = Math.round(px / GRID_SIZE) * GRID_SIZE;
        py = Math.round(py / GRID_SIZE) * GRID_SIZE;
      }

      // Convert to 0-1 normalized coordinates
      const normX = (px + PHONE_W / 2) / (rect.width - padding * 2);
      const normY = (py + PHONE_H / 2) / (rect.height - padding * 2);

      setEntries((prev) =>
        prev.map((e) =>
          e.device_id === dragging
            ? { ...e, position_x: Math.round(normX * 1000) / 1000, position_y: Math.round(normY * 1000) / 1000 }
            : e
        )
      );
    },
    [dragging, shiftHeld]
  );

  const handlePointerUp = useCallback(() => {
    setDragging(null);
  }, []);

  // ── Toolbar actions ──

  const handleSave = useCallback(async () => {
    setSaving(true);
    try {
      await setVizArrangement(entries);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch {
      /* error handling */
    } finally {
      setSaving(false);
    }
  }, [entries]);

  const handleReset = useCallback(() => {
    setEntries([]);
  }, []);

  const handleAutoArrange = useCallback(() => {
    const cols = Math.ceil(Math.sqrt(entries.length));
    const arranged = entries.map((e, i) => {
      const col = i % cols;
      const row = Math.floor(i / cols);
      return {
        ...e,
        position_x: ((col + 0.5) / (cols + 1)),
        position_y: ((row + 0.5) / (Math.ceil(entries.length / cols) + 1)),
      };
    });
    setEntries(arranged);
  }, [entries]);

  const handleToggleNumbers = useCallback(async () => {
    const next = !showNumbers;
    setShowNumbers(next);
    try {
      await setVizShowNumbers(next);
    } catch {
      /* silent */
    }
  }, [showNumbers]);

  // placed vs unplaced device IDs
  const placedIds = new Set(entries.map((e) => e.device_id));
  const unplaced = nodes.filter((n) => !placedIds.has(n.device_id));

  return (
    <div className="flex h-full gap-4 p-4">
      {/* Main arrangement canvas */}
      <div className="flex flex-1 flex-col gap-3">
        {/* Toolbar */}
        <div className="flex items-center gap-3 rounded-lg border border-phonon-border bg-phonon-card px-4 py-2">
          <button
            onClick={handleSave}
            disabled={saving}
            className="rounded-md bg-phonon-accent px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-phonon-accent/80 disabled:opacity-50"
          >
            {saving ? "Saving..." : saved ? "Saved ✓" : "Save Arrangement"}
          </button>

          <button
            onClick={handleReset}
            className="rounded-md border border-phonon-border px-3 py-1.5 text-sm font-medium text-phonon-muted transition-colors hover:text-phonon-text"
          >
            Reset
          </button>

          <button
            onClick={handleAutoArrange}
            disabled={entries.length === 0}
            className="rounded-md border border-phonon-border px-3 py-1.5 text-sm font-medium text-phonon-muted transition-colors hover:text-phonon-text disabled:opacity-30"
          >
            Auto-Arrange
          </button>

          <span className="h-5 w-px bg-phonon-border" />

          <label className="flex items-center gap-2 text-sm text-phonon-muted">
            <input
              type="checkbox"
              checked={showNumbers}
              onChange={handleToggleNumbers}
              className="h-4 w-4 rounded border-phonon-border bg-phonon-bg accent-phonon-accent"
            />
            Show Numbers
          </label>

          <span className="ml-auto text-xs text-phonon-muted">
            <strong className="text-phonon-text">{entries.length}</strong> placed ·{" "}
            <strong className="text-phonon-text">{unplaced.length}</strong> unplaced
          </span>

          {shiftHeld && (
            <span className="rounded bg-phonon-accent/10 px-2 py-0.5 text-xs text-phonon-accent">
              Snap {GRID_SIZE}px
            </span>
          )}
        </div>

        {/* Canvas */}
        <div
          ref={containerRef}
          className="relative w-full overflow-hidden rounded-lg border border-phonon-border bg-phonon-bg"
          style={{ aspectRatio: "16 / 9", minHeight: 320 }}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          onPointerCancel={handlePointerUp}
        >
          {/* Grid dots pattern */}
          <svg
            className="pointer-events-none absolute inset-0 h-full w-full"
            width="100%"
            height="100%"
          >
            <defs>
              <pattern
                id="grid-dots"
                width={GRID_SIZE}
                height={GRID_SIZE}
                patternUnits="userSpaceOnUse"
              >
                <circle
                  cx={GRID_SIZE / 2}
                  cy={GRID_SIZE / 2}
                  r={0.5}
                  fill="currentColor"
                  className="text-phonon-border opacity-30"
                />
              </pattern>
            </defs>
            <rect width="100%" height="100%" fill="url(#grid-dots)" />
          </svg>

          {/* Draggable phone cards */}
          {entries.map((entry) => {
            const node = nodes.find((n) => n.device_id === entry.device_id);
            const isDragging = dragging === entry.device_id;

            return (
              <div
                key={entry.device_id}
                className={`absolute flex cursor-grab flex-col items-center transition-opacity ${
                  isDragging ? "z-50 opacity-90" : "z-10"
                }`}
                style={{
                  left: `calc(${entry.position_x * 100}% - ${PHONE_W / 2}px)`,
                  top: `calc(${entry.position_y * 100}% - ${PHONE_H / 2}px)`,
                  width: PHONE_W,
                }}
                onPointerDown={(e) => handlePointerDown(e, entry.device_id)}
                title={
                  node
                    ? `${node.name} — ${node.device_model}\n${node.ip_address}`
                    : entry.device_id
                }
              >
                {/* Phone card */}
                <div
                  className={`relative flex w-full items-center justify-center rounded-lg border-2 ${
                    stateColor(node?.state ?? "unknown")
                  } ${stateBg(node?.state ?? "unknown")} ${
                    isDragging ? "shadow-lg shadow-phonon-accent/30 scale-105" : "shadow-sm"
                  } transition-shadow`}
                  style={{ height: PHONE_H }}
                >
                  {/* Display number badge */}
                  {showNumbers && (
                    <span className="absolute -left-2 -top-2 flex h-6 w-6 items-center justify-center rounded-full bg-phonon-accent text-xs font-bold text-white shadow-sm">
                      {entry.display_number}
                    </span>
                  )}

                  {/* Device name */}
                  <span className="truncate px-2 text-xs font-medium text-phonon-text">
                    {node?.name ?? entry.device_id.slice(0, 8)}
                  </span>

                  {/* Remove button on hover */}
                  <button
                    className="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-phonon-danger/20 text-[10px] text-phonon-danger opacity-0 transition-opacity hover:opacity-100"
                    onClick={(e) => {
                      e.stopPropagation();
                      removeDevice(entry.device_id);
                    }}
                    title="Remove from canvas"
                  >
                    ✕
                  </button>
                </div>

                {/* Processing indicator */}
                {node?.state === "online" && (
                  <span className="mt-0.5 text-[10px] text-phonon-muted">
                    {node.model_loaded ? "⚡" : "○"}
                  </span>
                )}
              </div>
            );
          })}

          {/* Empty state */}
          {entries.length === 0 && (
            <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
              <p className="text-sm text-phonon-muted">
                Drag devices from the sidebar onto the canvas
              </p>
            </div>
          )}
        </div>
      </div>

      {/* Sidebar — unplaced devices */}
      <div className="w-56 shrink-0 rounded-lg border border-phonon-border bg-phonon-card">
        <div className="border-b border-phonon-border px-3 py-2">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-phonon-muted">
            Unplaced Devices
          </h3>
        </div>
        <div className="space-y-1 p-2">
          {unplaced.length === 0 && (
            <p className="px-1 py-4 text-center text-xs text-phonon-muted">
              {nodes.length === 0
                ? "No devices registered"
                : "All devices placed"}
            </p>
          )}
          {unplaced.map((node) => (
            <button
              key={node.device_id}
              onClick={() => placeDevice(node.device_id)}
              className={`flex w-full items-center gap-2 rounded-md px-2 py-2 text-left text-sm transition-colors hover:bg-phonon-bg ${stateBg(
                node.state
              )}`}
              title={`${node.device_id} — ${node.ip_address}`}
            >
              <span
                className={`h-2 w-2 shrink-0 rounded-full ${
                  node.state === "online"
                    ? "bg-phonon-success"
                    : node.state === "offline"
                    ? "bg-phonon-muted"
                    : "bg-phonon-warning"
                }`}
              />
              <span className="truncate text-phonon-text">{node.name}</span>
              {node.model_loaded && (
                <span className="ml-auto text-[10px] text-phonon-muted">⚡</span>
              )}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
