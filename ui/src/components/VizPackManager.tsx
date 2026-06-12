import { useEffect, useState } from "react";
import {
  getVizPacks,
  setActivePack,
  getClusterNodes,
  type VizPack,
  type ClusterNode,
} from "../lib/api";

export default function VizPackManager() {
  const [packs, setPacks] = useState<VizPack[]>([]);
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [activePack, setActivePackState] = useState<string | null>(null);
  const [targetDevice, setTargetDevice] = useState<string>("all");
  const [activating, setActivating] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState<{ packId: string; msg: string } | null>(null);

  // Load packs and devices on mount
  useEffect(() => {
    (async () => {
      try {
        const [packData, nodeData] = await Promise.all([
          getVizPacks(),
          getClusterNodes(),
        ]);
        setPacks(packData.data);
        setNodes(nodeData.data);

        // Default to Neon Ring as active
        if (packData.data.length > 0) {
          setActivePackState(packData.data[0].id);
        }
      } catch {
        /* offline */
      }
    })();
  }, []);

  const handleSetActive = async (packId: string) => {
    setActivating(packId);
    setStatusMsg(null);
    try {
      const deviceId = targetDevice === "all" ? undefined : targetDevice;
      await setActivePack(packId, deviceId);
      setActivePackState(packId);
      setStatusMsg({
        packId,
        msg: deviceId ? `Activated on ${deviceId.slice(0, 8)}...` : "Activated on all devices",
      });
      setTimeout(() => setStatusMsg(null), 3000);
    } catch {
      setStatusMsg({
        packId,
        msg: "Failed to activate",
      });
    } finally {
      setActivating(null);
    }
  };

  // Color swatch mapping per pack
  const packSwatch = (pack: VizPack): string => {
    switch (pack.id) {
      case "neon-ring":
        return "bg-gradient-to-br from-cyan-400 via-purple-500 to-pink-500";
      case "matrix-rain":
        return "bg-gradient-to-br from-green-500 to-green-800";
      case "cyber-hud":
        return "bg-gradient-to-br from-cyan-300 to-orange-500";
      default:
        return "bg-gradient-to-br from-phonon-accent to-phonon-muted";
    }
  };

  return (
    <div className="flex h-full flex-col gap-4">
      {/* Device selector */}
      <div className="flex items-center gap-3 rounded-lg border border-phonon-border bg-phonon-card px-4 py-2">
        <label className="text-sm font-medium text-phonon-muted">
          Apply to:
        </label>
        <select
          value={targetDevice}
          onChange={(e) => setTargetDevice(e.target.value)}
          className="rounded-md border border-phonon-border bg-phonon-bg px-3 py-1.5 text-sm text-phonon-text focus:border-phonon-accent focus:outline-none"
        >
          <option value="all">All Devices</option>
          {nodes.map((node) => (
            <option key={node.device_id} value={node.device_id}>
              {node.name} ({node.device_model})
            </option>
          ))}
        </select>
      </div>

      {/* Pack grid */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {packs.map((pack) => {
          const isActive = activePack === pack.id;
          const isActivating = activating === pack.id;
          const successMsg =
            statusMsg?.packId === pack.id ? statusMsg.msg : null;

          return (
            <div
              key={pack.id}
              className={`group relative overflow-hidden rounded-lg border-2 transition-all ${
                isActive
                  ? "border-phonon-accent bg-phonon-accent/5 shadow-md shadow-phonon-accent/20"
                  : "border-phonon-border bg-phonon-card hover:border-phonon-muted"
              }`}
            >
              {/* Color swatch bar */}
              <div className={`h-16 ${packSwatch(pack)}`} />

              {/* Pack info */}
              <div className="space-y-2 p-4">
                <div className="flex items-start justify-between">
                  <div>
                    <h3 className="font-semibold text-phonon-text">
                      {pack.name}
                      {isActive && (
                        <span className="ml-2 rounded bg-phonon-accent/20 px-1.5 py-0.5 text-[10px] font-medium text-phonon-accent">
                          ACTIVE
                        </span>
                      )}
                    </h3>
                    <p className="mt-1 text-xs leading-relaxed text-phonon-muted">
                      {pack.description}
                    </p>
                  </div>
                </div>

                {/* Meta */}
                <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-phonon-muted">
                  <span>
                    by <strong className="text-phonon-text">{pack.author}</strong>
                  </span>
                  <span>v{pack.version}</span>
                </div>

                {/* Config keys preview */}
                <div className="flex flex-wrap gap-1">
                  {Object.entries(pack.default_config).slice(0, 4).map(([key, val]) => (
                    <span
                      key={key}
                      className="rounded bg-phonon-bg px-1.5 py-0.5 font-mono text-[10px] text-phonon-muted"
                      title={`${key}: ${val}`}
                    >
                      {key}
                    </span>
                  ))}
                  {Object.keys(pack.default_config).length > 4 && (
                    <span className="rounded bg-phonon-bg px-1.5 py-0.5 font-mono text-[10px] text-phonon-muted">
                      +{Object.keys(pack.default_config).length - 4}
                    </span>
                  )}
                </div>

                {/* Action */}
                <div className="pt-1">
                  <button
                    onClick={() => handleSetActive(pack.id)}
                    disabled={isActive || isActivating}
                    className={`w-full rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                      isActive
                        ? "bg-phonon-accent/10 text-phonon-accent cursor-default"
                        : "bg-phonon-accent text-white hover:bg-phonon-accent/80 disabled:opacity-40"
                    }`}
                  >
                    {isActivating
                      ? "Activating..."
                      : isActive
                      ? "Currently Active"
                      : "Set Active"}
                  </button>

                  {successMsg && (
                    <p className="mt-1 text-center text-[11px] text-phonon-success">
                      {successMsg}
                    </p>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Upload zone placeholder */}
      <div className="mt-2 rounded-lg border-2 border-dashed border-phonon-border p-8 text-center transition-colors hover:border-phonon-muted">
        <div className="flex flex-col items-center gap-2">
          <span className="text-2xl text-phonon-muted">⬇</span>
          <p className="text-sm font-medium text-phonon-muted">
            Upload Theme Pack
          </p>
          <p className="text-xs text-phonon-muted/60">
            Coming in a future update — custom packs will be installable
            via .zip upload
          </p>
        </div>
      </div>
    </div>
  );
}
