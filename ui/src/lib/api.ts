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
