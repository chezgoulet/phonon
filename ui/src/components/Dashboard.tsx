import { useEffect, useState } from "react";
import PhoneCard from "./PhoneCard";
import { getClusterHealth, getClusterNodes, type ClusterNode, type ClusterHealth } from "../lib/api";

interface Props {
  onSelectNode: (node: ClusterNode) => void;
}

export default function Dashboard({ onSelectNode }: Props) {
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [health, setHealth] = useState<ClusterHealth | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [nodeData, healthData] = await Promise.all([
          getClusterNodes(),
          getClusterHealth(),
        ]);
        setNodes(nodeData.data);
        setHealth(healthData);
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e.message : "Failed to load");
      } finally {
        setLoading(false);
      }
    };

    fetchData();
    // Poll every 10 seconds for live state
    const interval = setInterval(fetchData, 10_000);
    return () => clearInterval(interval);
  }, []);

  const statusColor = health?.status === "healthy"
    ? "text-phonon-success"
    : health?.status === "degraded"
    ? "text-phonon-warning"
    : "text-phonon-danger";

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-phonon-muted animate-pulse">Connecting to coordinator...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="rounded-lg border border-phonon-danger/30 bg-phonon-danger/10 px-6 py-4 text-center">
          <p className="font-medium text-phonon-danger">Connection Failed</p>
          <p className="mt-1 text-sm text-phonon-muted">{error}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl p-6">
      {/* Summary bar */}
      {health && (
        <div className="mb-6 flex flex-wrap items-center gap-4 rounded-lg border border-phonon-border bg-phonon-card px-5 py-3">
          <div className="flex items-center gap-2">
            <span className={`h-2.5 w-2.5 rounded-full ${statusColor} bg-current`} />
            <span className={`text-sm font-medium ${statusColor}`}>
              {health.status.charAt(0).toUpperCase() + health.status.slice(1)}
            </span>
          </div>
          <span className="h-4 w-px bg-phonon-border" />
          <span className="text-sm text-phonon-muted">
            <strong className="text-phonon-text">{health.online_nodes}</strong> online
          </span>
          <span className="text-sm text-phonon-muted">
            <strong className="text-phonon-text">{health.total_nodes}</strong> total
          </span>
          {Object.entries(health.groups).length > 0 && (
            <>
              <span className="h-4 w-px bg-phonon-border" />
              {Object.entries(health.groups).map(([group, count]) => (
                <span key={group} className="text-xs text-phonon-muted">
                  <span className="inline-block rounded bg-phonon-accent/10 px-2 py-0.5 font-mono text-phonon-accent">
                    {group}
                  </span>{" "}
                  {count}
                </span>
              ))}
            </>
          )}
          {health.stale_nodes > 0 && (
            <>
              <span className="h-4 w-px bg-phonon-border" />
              <span className="text-xs text-phonon-warning">
                {health.stale_nodes} stale
              </span>
            </>
          )}
        </div>
      )}

      {/* Phone grid */}
      {nodes.length === 0 ? (
        <div className="flex items-center justify-center py-20">
          <div className="text-center">
            <p className="text-lg font-medium text-phonon-muted">No phones registered</p>
            <p className="mt-2 text-sm text-phonon-muted">
              Install the Phonon sidecar APK on an Android device to get started
            </p>
          </div>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {nodes.map((node) => (
            <PhoneCard
              key={node.device_id}
              node={node}
              onClick={() => onSelectNode(node)}
            />
          ))}
        </div>
      )}
    </div>
  );
}
