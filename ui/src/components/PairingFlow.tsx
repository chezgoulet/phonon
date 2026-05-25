import { useEffect, useState } from "react";
import { getClusterNodes, type ClusterNode } from "../lib/api";

// Pairing flow shows unpaired phones and their status.
// Future: QR code generation, pairing code display, live status transitions.
// For now, it shows the list of unpaired phones and provides the pairing
// endpoint info so the operator can trigger pairing from the API.

export default function PairingFlow() {
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchNodes = () =>
      getClusterNodes()
        .then((d) => setNodes(d.data))
        .catch(() => {})
        .finally(() => setLoading(false));

    fetchNodes();
    const interval = setInterval(fetchNodes, 5_000);
    return () => clearInterval(interval);
  }, []);

  const unpaired = nodes.filter((n) => n.state === "unpaired");
  const paired = nodes.filter((n) => n.state === "paired" || n.state === "online");

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="animate-pulse text-phonon-muted">Loading...</div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      <h2 className="mb-2 text-lg font-semibold">Pairing</h2>
      <p className="mb-6 text-sm text-phonon-muted">
        Pair new phones by registering them with the coordinator. Phones register
        automatically when the sidecar APK is installed and the app is running on
        the same network.
      </p>

      {/* How to pair */}
      <div className="mb-8 rounded-lg border border-phonon-accent/20 bg-phonon-accent/5 p-4">
        <h3 className="mb-2 text-sm font-medium text-phonon-accent">How to pair a phone</h3>
        <ol className="space-y-1 text-sm text-phonon-muted">
          <li>1. Install the sidecar APK: <code className="rounded bg-phonon-bg px-1.5 py-0.5 font-mono text-phonon-text">./gradlew assembleRelease</code></li>
          <li>2. Install on phone: <code className="rounded bg-phonon-bg px-1.5 py-0.5 font-mono text-phonon-text">adb install -r app-release.apk</code></li>
          <li>3. The phone appears here as <strong className="text-phonon-text">unpaired</strong></li>
          <li>4. Call the API: <code className="rounded bg-phonon-bg px-1.5 py-0.5 font-mono text-phonon-text">POST /api/v1/sidecar/pair</code> with its <code className="rounded bg-phonon-bg px-1.5 py-0.5 font-mono text-phonon-text">device_id</code></li>
        </ol>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        {/* Unpaired phones */}
        <div className="rounded-lg border border-phonon-border bg-phonon-card p-4">
          <h3 className="mb-3 flex items-center gap-2 text-sm font-medium">
            <span className="h-2 w-2 rounded-full bg-phonon-warning" />
            Unpaired ({unpaired.length})
          </h3>

          {unpaired.length === 0 ? (
            <p className="py-8 text-center text-xs text-phonon-muted">
              No unpaired phones. All devices are paired.
            </p>
          ) : (
            <div className="space-y-2">
              {unpaired.map((node) => (
                <div
                  key={node.device_id}
                  className="rounded border border-phonon-border bg-phonon-bg p-3"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-sm font-medium text-phonon-text">
                        {node.name}
                      </p>
                      <p className="text-xs text-phonon-muted">
                        {node.device_model}
                      </p>
                    </div>
                    <span className="text-xs font-mono text-phonon-muted">
                      {node.ip_address || "—"}
                    </span>
                  </div>
                  <p className="mt-1 truncate text-[10px] text-phonon-muted">
                    {node.device_id}
                  </p>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Paired (active) phones */}
        <div className="rounded-lg border border-phonon-border bg-phonon-card p-4">
          <h3 className="mb-3 flex items-center gap-2 text-sm font-medium">
            <span className="h-2 w-2 rounded-full bg-phonon-success" />
            Active ({paired.length})
          </h3>

          {paired.length === 0 ? (
            <p className="py-8 text-center text-xs text-phonon-muted">
              No active phones yet.
            </p>
          ) : (
            <div className="space-y-2">
              {paired.map((node) => (
                <div
                  key={node.device_id}
                  className="rounded border border-phonon-border bg-phonon-bg p-3"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-sm font-medium text-phonon-text">
                        {node.name}
                      </p>
                      <p className="text-xs text-phonon-muted">
                        {node.group ? `${node.group} · ` : ""}
                        {node.model_loaded || "no model"}
                      </p>
                    </div>
                    <span
                      className={`text-xs ${
                        node.state === "online"
                          ? "text-phonon-success"
                          : "text-phonon-muted"
                      }`}
                    >
                      {node.state}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* API endpoint info */}
      <div className="mt-8 rounded-lg border border-phonon-border bg-phonon-card p-4">
        <h3 className="mb-2 text-sm font-medium">Unified Endpoint</h3>
        <div className="rounded bg-phonon-bg p-3 font-mono text-xs">
          <div className="text-phonon-accent">http://{window.location.hostname}:8080/v1</div>
          <div className="mt-1 text-phonon-muted">
            Model names appear in the{" "}
            <span className="text-phonon-text">GET /v1/models</span> response
          </div>
        </div>
        <p className="mt-2 text-xs text-phonon-muted">
          Use this endpoint in LiteLLM, Open WebUI, or any OpenAI-compatible client.
        </p>
      </div>
    </div>
  );
}
