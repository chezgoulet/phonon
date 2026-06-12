// Types matching the coordinator API responses

export interface NodeTelemetry {
  battery_level: number;
  thermal_temp_c: number;
  is_charging: boolean;
  queue_depth: number;
}

export interface ClusterNode {
  device_id: string;
  name: string;
  device_model: string;
  group: string;
  state: "online" | "offline" | "paired" | "unpaired";
  ip_address: string;
  telemetry: NodeTelemetry;
  model_loaded: string;
  backend: string;
  uptime: string;
  registered_at: string;
}

export interface ClusterHealth {
  status: "healthy" | "degraded" | "offline";
  total_nodes: number;
  online_nodes: number;
  offline_nodes: number;
  paired_nodes: number;
  groups: Record<string, number>;
  stale_nodes: number;
  timestamp: string;
}

export interface AuthStatus {
  mode: string;
  authenticated: boolean;
}

// API base — the UI is served from /ui/, the API lives at /api/
const API = "/api/v1";

async function fetchJSON<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`API ${res.status}: ${res.statusText}`);
  }
  return res.json();
}

export async function getClusterHealth(): Promise<ClusterHealth> {
  return fetchJSON<ClusterHealth>(`${API}/cluster/health`);
}

export async function getClusterNodes(
  group?: string
): Promise<{ object: string; data: ClusterNode[] }> {
  const params = group ? `?group=${encodeURIComponent(group)}` : "";
  return fetchJSON(`${API}/cluster/nodes${params}`);
}

export async function getAuthStatus(): Promise<AuthStatus> {
  return fetchJSON<AuthStatus>("/api/v1/auth/status");
}

// ── Visualization types ──

export interface VizPack {
  id: string;
  name: string;
  description: string;
  author: string;
  version: string;
  default_config: Record<string, string>;
}

export interface VizArrangementEntry {
  device_id: string;
  display_number: number;
  position_x: number;
  position_y: number;
}

export async function getVizPacks(): Promise<{ object: string; data: VizPack[] }> {
  return fetchJSON(`${API}/viz/packs`);
}

export async function getVizArrangement(): Promise<{ object: string; data: VizArrangementEntry[] }> {
  return fetchJSON(`${API}/viz/arrangement`);
}

export async function setVizArrangement(
  entries: VizArrangementEntry[]
): Promise<{ status: string; count: number }> {
  const res = await fetch(`${API}/viz/arrangement`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ entries }),
  });
  if (!res.ok) throw new Error(`API ${res.status}: ${res.statusText}`);
  return res.json();
}

export async function setVizShowNumbers(visible: boolean): Promise<{ status: string }> {
  const res = await fetch(`${API}/viz/show-numbers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ visible }),
  });
  if (!res.ok) throw new Error(`API ${res.status}: ${res.statusText}`);
  return res.json();
}

export async function setActivePack(
  packId: string,
  deviceId?: string
): Promise<{ status: string }> {
  const url = deviceId
    ? `${API}/viz/device/${encodeURIComponent(deviceId)}/switch`
    : `${API}/viz/switch`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pack_id: packId }),
  });
  if (!res.ok) throw new Error(`API ${res.status}: ${res.statusText}`);
  return res.json();
}
