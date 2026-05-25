import { useEffect, useState } from "react";
import { getClusterNodes, type ClusterNode } from "../lib/api";

interface Props {
  onSelectNode: (node: ClusterNode) => void;
}

interface GroupInfo {
  name: string;
  nodes: ClusterNode[];
  // Infer mode from node flags in future — for now, groups are read-only
}

export default function GroupPanel({ onSelectNode }: Props) {
  const [nodes, setNodes] = useState<ClusterNode[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getClusterNodes()
      .then((d) => setNodes(d.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  // Group nodes by group assignment
  const groups = new Map<string, ClusterNode[]>();
  const ungrouped: ClusterNode[] = [];

  for (const node of nodes) {
    if (node.group) {
      const existing = groups.get(node.group) || [];
      existing.push(node);
      groups.set(node.group, existing);
    } else {
      ungrouped.push(node);
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="animate-pulse text-phonon-muted">Loading...</div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl p-6">
      <h2 className="mb-6 text-lg font-semibold">Groups</h2>

      {groups.size === 0 && ungrouped.length === 0 ? (
        <div className="py-12 text-center text-phonon-muted">
          No phones registered yet. Go to Dashboard to see available devices.
        </div>
      ) : (
        <div className="space-y-6">
          {/* Assigned groups */}
          {Array.from(groups.entries()).map(([name, members]) => (
            <div
              key={name}
              className="rounded-lg border border-phonon-border bg-phonon-card p-4"
            >
              <div className="mb-3 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="rounded bg-phonon-accent/10 px-3 py-1 font-mono text-sm text-phonon-accent">
                    {name}
                  </span>
                  <span className="text-xs text-phonon-muted">
                    {members.length} phone{members.length !== 1 ? "s" : ""}
                  </span>
                </div>
                <span className="rounded bg-phonon-bg px-2 py-0.5 text-xs text-phonon-muted">
                  {/* Mode is inferred from how the group is configured in YAML — not yet exposed via API */}
                  pool
                </span>
              </div>

              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {members.map((node) => (
                  <button
                    key={node.device_id}
                    onClick={() => onSelectNode(node)}
                    className="flex items-center gap-3 rounded border border-phonon-border bg-phonon-bg p-3 text-left transition-colors hover:border-phonon-accent/30"
                  >
                    <span
                      className={`h-2 w-2 shrink-0 rounded-full ${
                        node.state === "online"
                          ? "bg-phonon-success"
                          : "bg-phonon-muted"
                      }`}
                    />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium text-phonon-text">
                        {node.name}
                      </p>
                      <p className="text-xs text-phonon-muted">
                        {node.device_model}
                      </p>
                    </div>
                    <span className="text-xs text-phonon-muted">
                      {node.model_loaded || "—"}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          ))}

          {/* Ungrouped phones */}
          {ungrouped.length > 0 && (
            <div className="rounded-lg border border-dashed border-phonon-border p-4">
              <h3 className="mb-3 text-sm font-medium text-phonon-muted">
                Unassigned ({ungrouped.length})
              </h3>
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {ungrouped.map((node) => (
                  <button
                    key={node.device_id}
                    onClick={() => onSelectNode(node)}
                    className="flex items-center gap-3 rounded border border-phonon-border bg-phonon-bg p-3 text-left transition-colors hover:border-phonon-accent/30"
                  >
                    <span
                      className={`h-2 w-2 shrink-0 rounded-full ${
                        node.state === "online"
                          ? "bg-phonon-success"
                          : "bg-phonon-muted"
                      }`}
                    />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium text-phonon-text">
                        {node.name}
                      </p>
                      <p className="text-xs text-phonon-muted">
                        {node.device_model}
                      </p>
                    </div>
                    <span className="text-xs text-phonon-muted">
                      {node.state}
                    </span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
