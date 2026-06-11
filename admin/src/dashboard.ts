import * as api from "./api";
import { lineChart, stackedAreaChart } from "./charts";
import type { Line, Stack } from "./charts";
import { card, h, toast } from "./dom";
import { bytes, count, dateTime, unixDay, unixInstant } from "./format";

const PALETTE = ["#e8590c", "#1c7ed6", "#2f9e44", "#ae3ec9", "#f08c00", "#0c8599", "#e64980"];

function withAlpha(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

interface NamedDaily {
  label: string;
  stroke: string;
  points: api.DailyPoint[];
}

interface NamedHourly {
  label: string;
  stroke: string;
  points: api.HourlyPoint[];
}

function mergeDaily(named: NamedDaily[]): { xs: number[]; lines: Line[] } {
  const days = new Set<string>();
  for (const n of named) for (const p of n.points) days.add(p.day);
  const sorted = [...days].sort();
  const lines = named.map((n) => {
    const byDay = new Map<string, number>(n.points.map((p): [string, number] => [p.day, p.value]));
    return { label: n.label, stroke: n.stroke, data: sorted.map((d) => byDay.get(d) ?? null) };
  });
  return { xs: sorted.map(unixDay), lines };
}

function mergeHourly(named: NamedHourly[]): { xs: number[]; lines: Line[] } {
  const stamps = new Set<string>();
  for (const n of named) for (const p of n.points) stamps.add(p.ts);
  const sorted = [...stamps].sort();
  const lines = named.map((n) => {
    const byTs = new Map<string, number>(n.points.map((p): [string, number] => [p.ts, p.value]));
    return { label: n.label, stroke: n.stroke, data: sorted.map((t) => byTs.get(t) ?? null) };
  });
  return { xs: sorted.map(unixInstant), lines };
}

function stackMessages(points: api.DailyPoint[]): { xs: number[]; stacks: Stack[] } {
  const days = new Set<string>();
  const byType = new Map<string, Map<string, number>>();
  for (const p of points) {
    const type = typeof p.labels["channelType"] === "string" ? p.labels["channelType"] : "other";
    days.add(p.day);
    const series = byType.get(type) ?? new Map<string, number>();
    series.set(p.day, p.value);
    byType.set(type, series);
  }
  const sorted = [...days].sort();
  const stacks = [...byType.keys()].sort().map((type, i) => {
    const series = byType.get(type) ?? new Map<string, number>();
    const colour = PALETTE[i % PALETTE.length] ?? "#888888";
    return {
      label: type,
      stroke: colour,
      fill: withAlpha(colour, 0.45),
      data: sorted.map((d) => series.get(d) ?? 0),
    };
  });
  return { xs: sorted.map(unixDay), stacks };
}

function tile(label: string, value: string, sub?: string): HTMLElement {
  return h("div", { class: "tile" }, [
    h("div", { class: "tile-value" }, [value]),
    h("div", { class: "tile-label" }, [label]),
    ...(sub ? [h("div", { class: "tile-sub" }, [sub])] : []),
  ]);
}

async function act(button: HTMLButtonElement, run: () => Promise<void>): Promise<void> {
  button.disabled = true;
  try {
    await run();
  } catch (error) {
    if (error instanceof api.ApiError && error.status === 401) {
      location.reload();
      return;
    }
    button.disabled = false;
    toast(`Failed: ${(error as Error).message}`, "err");
  }
}

function deadLetterTable(letters: api.DeadLetter[]): HTMLElement {
  if (letters.length === 0) return h("p", { class: "muted" }, ["No dead-lettered deliveries."]);
  const body = h("tbody", {}, letters.map(deadLetterRow));
  return h("table", { class: "grid" }, [
    h("thead", {}, [
      h("tr", {}, [
        h("th", {}, ["Event"]),
        h("th", {}, ["Endpoint"]),
        h("th", {}, ["Attempts"]),
        h("th", {}, ["Last error"]),
        h("th", {}, ["Failed at"]),
        h("th", {}, [""]),
      ]),
    ]),
    body,
  ]);
}

function deadLetterRow(letter: api.DeadLetter): HTMLElement {
  const replay = h("button", { class: "btn small" }, ["Replay"]) as HTMLButtonElement;
  const row = h("tr", {}, [
    h("td", {}, [letter.eventType]),
    h("td", { class: "mono" }, [letter.endpointId]),
    h("td", {}, [count(letter.attempts)]),
    h("td", { class: "error-cell" }, [letter.lastError || "-"]),
    h("td", {}, [dateTime(letter.createdAt)]),
    h("td", {}, [replay]),
  ]);
  replay.addEventListener("click", () =>
    act(replay, async () => {
      await api.replayDeadLetter(letter.id);
      row.remove();
      toast("Delivery requeued");
    }),
  );
  return row;
}

function apiKeyRow(key: api.ApiKeyInfo): HTMLElement {
  const status = h("td", { class: key.revoked ? "muted" : "ok" }, [
    key.revoked ? "revoked" : "active",
  ]);
  const action = h("td", {});
  const row = h("tr", {}, [
    h("td", { class: "mono" }, [key.id]),
    h("td", {}, [dateTime(key.createdAt)]),
    status,
    action,
  ]);
  if (!key.revoked) {
    const revoke = h("button", { class: "btn small danger" }, ["Revoke"]) as HTMLButtonElement;
    revoke.addEventListener("click", () =>
      act(revoke, async () => {
        await api.revokeApiKey(key.id);
        status.className = "muted";
        status.textContent = "revoked";
        revoke.remove();
        toast("Key revoked");
      }),
    );
    action.append(revoke);
  }
  return row;
}

function apiKeysSection(keys: api.ApiKeyInfo[]): HTMLElement {
  const tbody = h("tbody", {}, keys.map(apiKeyRow));
  const table = h("table", { class: "grid" }, [
    h("thead", {}, [
      h("tr", {}, [
        h("th", {}, ["Key id"]),
        h("th", {}, ["Created"]),
        h("th", {}, ["Status"]),
        h("th", {}, [""]),
      ]),
    ]),
    tbody,
  ]);
  const secretSlot = h("div", {});
  const create = h("button", { class: "btn" }, ["Create key"]) as HTMLButtonElement;
  create.addEventListener("click", () =>
    act(create, async () => {
      const created = await api.createApiKey();
      create.disabled = false;
      secretSlot.replaceChildren(
        h("div", { class: "secret" }, [
          h("strong", {}, ["New key secret (shown once - copy it now):"]),
          h("code", { class: "mono" }, [`${created.id} : ${created.secret}`]),
        ]),
      );
      tbody.prepend(
        apiKeyRow({ id: created.id, createdAt: new Date().toISOString(), revoked: false }),
      );
      toast("API key created");
    }),
  );
  return h("div", {}, [h("div", { class: "row" }, [create]), secretSlot, table]);
}

function chartCard(title: string): { card: HTMLElement; canvas: HTMLElement } {
  const canvas = h("div", { class: "chart" });
  return { card: card(title, canvas), canvas };
}

/** Fetches everything and renders the dashboard into `app`. */
export async function renderDashboard(app: HTMLElement): Promise<void> {
  const [live, dau, wau, mau, messages, ccuMax, ccuP95, media, dbSize, letters, keys] =
    await Promise.all([
      api.metrics(),
      api.dailySeries("dau"),
      api.dailySeries("wau"),
      api.dailySeries("mau"),
      api.dailySeries("messages"),
      api.hourlySeries("ccu_max"),
      api.hourlySeries("ccu_p95"),
      api.dailySeries("media_bytes"),
      api.dailySeries("db_size_bytes"),
      api.deadLetters(),
      api.apiKeys(),
    ]);

  const messagesToday = Object.values(live.messagesByType).reduce((a, b) => a + b, 0);
  const breakdown = Object.entries(live.messagesByType)
    .map(([type, n]) => `${type} ${count(n)}`)
    .join("  ·  ");

  const tiles = h("div", { class: "tiles" }, [
    tile("Online now", count(live.ccuNow)),
    tile("DAU", count(live.dau)),
    tile("WAU", count(live.wau)),
    tile("MAU", count(live.mau)),
    tile("Messages today", count(messagesToday), breakdown || undefined),
    tile("Media stored", bytes(live.mediaBytes)),
    tile("Database", bytes(live.dbSizeBytes)),
  ]);

  const activeUsers = chartCard("Active users (DAU / WAU / MAU)");
  const messagesChart = chartCard("Messages per day, by channel type");
  const ccuChart = chartCard("Concurrent connections (hourly p95 / max)");
  const storageChart = chartCard("Storage (media / database)");

  const header = h("header", { class: "topbar" }, [
    h("div", { class: "brand" }, [h("span", { class: "spark" }, ["▲"]), "Firemoot admin"]),
    refreshButton(app),
  ]);

  app.replaceChildren(
    header,
    h("main", { class: "dashboard" }, [
      card("At a glance", tiles),
      h("div", { class: "chart-grid" }, [
        activeUsers.card,
        messagesChart.card,
        ccuChart.card,
        storageChart.card,
      ]),
      card("Webhook dead-letters", deadLetterTable(letters)),
      card("API keys", apiKeysSection(keys)),
    ]),
  );

  // Charts initialise only once attached, so they can read their container width.
  const activeMerged = mergeDaily([
    { label: "DAU", stroke: PALETTE[2] ?? "#2f9e44", points: dau },
    { label: "WAU", stroke: PALETTE[1] ?? "#1c7ed6", points: wau },
    { label: "MAU", stroke: PALETTE[0] ?? "#e8590c", points: mau },
  ]);
  lineChart(activeUsers.canvas, activeMerged.xs, activeMerged.lines, count);

  const stacked = stackMessages(messages);
  stackedAreaChart(messagesChart.canvas, stacked.xs, stacked.stacks);

  const ccuMerged = mergeHourly([
    { label: "p95", stroke: PALETTE[1] ?? "#1c7ed6", points: ccuP95 },
    { label: "max", stroke: PALETTE[0] ?? "#e8590c", points: ccuMax },
  ]);
  lineChart(ccuChart.canvas, ccuMerged.xs, ccuMerged.lines, count);

  const storageMerged = mergeDaily([
    { label: "Media", stroke: PALETTE[0] ?? "#e8590c", points: media },
    { label: "Database", stroke: PALETTE[1] ?? "#1c7ed6", points: dbSize },
  ]);
  lineChart(storageChart.canvas, storageMerged.xs, storageMerged.lines, bytes);
}

function refreshButton(app: HTMLElement): HTMLElement {
  const button = h("button", { class: "btn small" }, ["Refresh"]) as HTMLButtonElement;
  button.addEventListener("click", () =>
    act(button, async () => {
      await renderDashboard(app);
    }),
  );
  return button;
}
