import { useState } from "react";
import Dashboard from "./components/Dashboard";
import GroupPanel from "./components/GroupPanel";
import PairingFlow from "./components/PairingFlow";
import HealthDetail from "./components/HealthDetail";
import type { ClusterNode } from "./lib/api";

type View = "dashboard" | "groups" | "pairing";

function App() {
  const [view, setView] = useState<View>("dashboard");
  const [selectedNode, setSelectedNode] = useState<ClusterNode | null>(null);

  return (
    <div className="flex h-screen flex-col">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-phonon-border bg-phonon-card px-6 py-3">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-bold tracking-tight">Phonon Cluster</h1>
          <span className="rounded bg-phonon-accent/20 px-2 py-0.5 text-xs font-medium text-phonon-accent">
            alpha
          </span>
        </div>

        <nav className="flex gap-1 rounded-lg bg-phonon-bg p-1">
          {(
            [
              ["dashboard", "Dashboard"],
              ["groups", "Groups"],
              ["pairing", "Pairing"],
            ] as [View, string][]
          ).map(([id, label]) => (
            <button
              key={id}
              onClick={() => {
                setView(id);
                setSelectedNode(null);
              }}
              className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                view === id
                  ? "bg-phonon-accent text-white"
                  : "text-phonon-muted hover:text-phonon-text"
              }`}
            >
              {label}
            </button>
          ))}
        </nav>

        {/* Endpoint display */}
        <a
          href="/api/v1/cluster/health"
          target="_blank"
          className="rounded border border-phonon-border bg-phonon-bg px-3 py-1.5 font-mono text-xs text-phonon-muted hover:text-phonon-accent"
        >
          {window.location.hostname}:8080/v1
        </a>
      </header>

      {/* Main content */}
      <main className="flex-1 overflow-auto">
        {selectedNode ? (
          <HealthDetail
            node={selectedNode}
            onBack={() => setSelectedNode(null)}
          />
        ) : view === "dashboard" ? (
          <Dashboard onSelectNode={setSelectedNode} />
        ) : view === "groups" ? (
          <GroupPanel onSelectNode={setSelectedNode} />
        ) : (
          <PairingFlow />
        )}
      </main>
    </div>
  );
}

export default App;
