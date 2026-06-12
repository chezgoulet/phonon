import { useState } from "react";
import Dashboard from "./components/Dashboard";
import GroupPanel from "./components/GroupPanel";
import PairingFlow from "./components/PairingFlow";
import HealthDetail from "./components/HealthDetail";
import ArrangementWidget from "./components/ArrangementWidget";
import VizPackManager from "./components/VizPackManager";
import type { ClusterNode } from "./lib/api";

type View = "dashboard" | "groups" | "pairing" | "visualizations";

function App() {
  const [view, setView] = useState<View>("dashboard");
  const [selectedNode, setSelectedNode] = useState<ClusterNode | null>(null);
  const [vizSubView, setVizSubView] = useState<"arrange" | "packs">("arrange");

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
              ["visualizations", "Visualizations"],
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
        ) : view === "pairing" ? (
          <PairingFlow />
        ) : view === "visualizations" ? (
          <div className="flex h-full flex-col">
            {/* Sub-navigation */}
            <div className="flex gap-1 border-b border-phonon-border bg-phonon-card px-6 py-2">
              {(
                [
                  ["arrange", "Arrangement"] as const,
                  ["packs", "Visualization Packs"] as const,
                ]
              ).map(([id, label]) => (
                <button
                  key={id}
                  onClick={() => setVizSubView(id)}
                  className={`rounded-md px-3 py-1 text-xs font-medium transition-colors ${
                    vizSubView === id
                      ? "bg-phonon-accent text-white"
                      : "text-phonon-muted hover:text-phonon-text"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>

            <div className="flex-1 overflow-auto">
              {vizSubView === "arrange" ? (
                <ArrangementWidget />
              ) : (
                <div className="mx-auto max-w-5xl p-6">
                  <VizPackManager />
                </div>
              )}
            </div>
          </div>
        ) : null}
      </main>
    </div>
  );
}

export default App;
