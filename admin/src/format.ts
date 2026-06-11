const UNITS = ["B", "KB", "MB", "GB", "TB", "PB"];

/** Human-readable bytes (binary, 1 decimal): 1536 -> "1.5 KB". */
export function bytes(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return "0 B";
  const i = Math.min(UNITS.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  const value = n / 1024 ** i;
  return `${i === 0 ? value : value.toFixed(1)} ${UNITS[i]}`;
}

/** Thousands-separated integer (UK locale). */
export function count(n: number): string {
  return Math.round(n).toLocaleString("en-GB");
}

const PAD = (n: number) => String(n).padStart(2, "0");

/** dd/mm/yyyy hh:mm from an ISO-8601 instant (UK ordering). */
export function dateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${PAD(d.getDate())}/${PAD(d.getMonth() + 1)}/${d.getFullYear()} ${PAD(d.getHours())}:${PAD(d.getMinutes())}`;
}

/** Unix seconds at UTC midnight for a "YYYY-MM-DD" day. */
export function unixDay(day: string): number {
  return Date.parse(`${day}T00:00:00Z`) / 1000;
}

/** Unix seconds for an ISO-8601 instant. */
export function unixInstant(iso: string): number {
  return Date.parse(iso) / 1000;
}
