import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";

import { count } from "./format";
import { h } from "./dom";

export interface Line {
  label: string;
  stroke: string;
  data: (number | null)[];
}

export interface Stack {
  label: string;
  stroke: string;
  fill: string;
  data: number[];
}

const HEIGHT = 240;

function width(target: HTMLElement): number {
  return Math.max(280, target.clientWidth);
}

function emptyState(target: HTMLElement): void {
  target.append(h("div", { class: "chart-empty" }, ["No data yet"]));
}

function render(
  target: HTMLElement,
  series: uPlot.Series[],
  data: uPlot.AlignedData,
  liveLegend: boolean,
  yFmt?: (value: number) => string,
): uPlot {
  const opts: uPlot.Options = {
    width: width(target),
    height: HEIGHT,
    series,
    scales: { x: { time: true } },
    axes: [{}, yFmt ? { values: (_u, splits) => splits.map((s) => yFmt(s)) } : {}],
    legend: { live: liveLegend },
  };
  const chart = new uPlot(opts, data, target);
  window.addEventListener("resize", () => chart.setSize({ width: width(target), height: HEIGHT }));
  return chart;
}

/** A multi-line time chart (DAU/WAU/MAU, CCU, storage). */
export function lineChart(
  target: HTMLElement,
  xs: number[],
  lines: Line[],
  yFmt?: (value: number) => string,
): void {
  if (xs.length === 0) return emptyState(target);
  const showPoints = xs.length < 30;
  const series: uPlot.Series[] = [
    {},
    ...lines.map((l) => ({
      label: l.label,
      stroke: l.stroke,
      width: 2,
      points: { show: showPoints },
    })),
  ];
  const data = [xs, ...lines.map((l) => l.data)] as uPlot.AlignedData;
  render(target, series, data, true, yFmt);
}

/**
 * A stacked-area chart (messages/day by channel type). Cumulative sums are drawn
 * tallest-first and filled to the baseline, so each subsequent (shorter) band
 * paints over the one below it - producing a correct visual stack. The legend is
 * static (non-live) since the plotted values are cumulative, not per-band.
 */
export function stackedAreaChart(target: HTMLElement, xs: number[], stacks: Stack[]): void {
  if (xs.length === 0 || stacks.length === 0) return emptyState(target);
  const n = xs.length;
  const cumulative: number[][] = [];
  for (let k = 0; k < stacks.length; k++) {
    const below = k > 0 ? cumulative[k - 1] : undefined;
    const row = new Array<number>(n);
    for (let i = 0; i < n; i++) row[i] = (below?.[i] ?? 0) + (stacks[k]?.data[i] ?? 0);
    cumulative.push(row);
  }
  const order = stacks.map((_, k) => k).reverse();
  const series: uPlot.Series[] = [
    {},
    ...order.map((k) => ({
      label: stacks[k]?.label ?? "",
      stroke: stacks[k]?.stroke ?? "#888",
      fill: stacks[k]?.fill ?? "rgba(136,136,136,0.4)",
      width: 1,
      points: { show: false },
    })),
  ];
  const data = [xs, ...order.map((k) => cumulative[k] ?? [])] as uPlot.AlignedData;
  render(target, series, data, false, count);
}
