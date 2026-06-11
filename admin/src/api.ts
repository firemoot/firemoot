// Typed client for the admin API (all paths under /admin, same-origin). The
// session lives in the httpOnly `firemoot_admin` cookie; mutations carry the
// readable `firemoot_csrf` cookie back as an X-CSRF-Token header (double-submit).

export interface LiveMetrics {
  dau: number;
  wau: number;
  mau: number;
  messagesByType: Record<string, number>;
  mediaBytes: number;
  dbSizeBytes: number;
  ccuNow: number;
}

export interface DailyPoint {
  day: string;
  labels: Record<string, unknown>;
  value: number;
}

export interface HourlyPoint {
  ts: string;
  labels: Record<string, unknown>;
  value: number;
}

export interface DeadLetter {
  id: string;
  endpointId: string;
  eventType: string;
  attempts: number;
  lastError: string;
  createdAt: string;
}

export interface ApiKeyInfo {
  id: string;
  createdAt: string;
  revoked: boolean;
}

export interface ApiKeyCreated {
  id: string;
  secret: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)firemoot_csrf=([^;]+)/);
  if (!match || match[1] === undefined) return "";
  return decodeURIComponent(match[1]);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { credentials: "same-origin", ...init });
  if (!res.ok) {
    throw new ApiError(res.status, `${init?.method ?? "GET"} ${path} failed (${res.status})`);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

function get<T>(path: string): Promise<T> {
  return request<T>(path);
}

function post<T>(path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "X-CSRF-Token": csrfToken() };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  return request<T>(path, {
    method: "POST",
    headers,
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
}

export async function login(password: string): Promise<boolean> {
  const res = await fetch("/admin/login", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ password }),
  });
  return res.ok;
}

export async function hasSession(): Promise<boolean> {
  const res = await fetch("/admin/session", { credentials: "same-origin" });
  return res.ok;
}

export const metrics = (): Promise<LiveMetrics> => get("/admin/metrics");

export async function dailySeries(metric: string, days = 90): Promise<DailyPoint[]> {
  const res = await get<{ series: DailyPoint[] }>(
    `/admin/metrics/daily?metric=${encodeURIComponent(metric)}&days=${days}`,
  );
  return res.series;
}

export async function hourlySeries(metric: string, hours = 168): Promise<HourlyPoint[]> {
  const res = await get<{ series: HourlyPoint[] }>(
    `/admin/metrics/hourly?metric=${encodeURIComponent(metric)}&hours=${hours}`,
  );
  return res.series;
}

export const deadLetters = (): Promise<DeadLetter[]> => get("/admin/webhooks/dead-letters");

export const replayDeadLetter = (id: string): Promise<unknown> =>
  post(`/admin/webhooks/dead-letters/${encodeURIComponent(id)}/replay`);

export const apiKeys = (): Promise<ApiKeyInfo[]> => get("/admin/api-keys");

export const createApiKey = (): Promise<ApiKeyCreated> => post("/admin/api-keys");

export const revokeApiKey = (id: string): Promise<unknown> =>
  post(`/admin/api-keys/${encodeURIComponent(id)}/revoke`);
