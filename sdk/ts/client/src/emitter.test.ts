import { describe, expect, test, vi } from "vitest";

import { TypedEmitter } from "./emitter.js";

interface Events {
  foo: { n: number };
  bar: string;
}

describe("TypedEmitter", () => {
  test("delivers typed payloads in order", () => {
    const emitter = new TypedEmitter<Events>();
    const seen: number[] = [];
    emitter.on("foo", (p) => seen.push(p.n));
    emitter.emit("foo", { n: 1 });
    emitter.emit("foo", { n: 2 });
    expect(seen).toEqual([1, 2]);
  });

  test("the returned unsubscribe stops delivery", () => {
    const emitter = new TypedEmitter<Events>();
    const handler = vi.fn();
    const off = emitter.on("bar", handler);
    emitter.emit("bar", "a");
    off();
    emitter.emit("bar", "b");
    expect(handler).toHaveBeenCalledTimes(1);
  });

  test("once fires exactly once", () => {
    const emitter = new TypedEmitter<Events>();
    const handler = vi.fn();
    emitter.once("bar", handler);
    emitter.emit("bar", "a");
    emitter.emit("bar", "b");
    expect(handler).toHaveBeenCalledTimes(1);
  });

  test("handlers added during emit do not fire in that round", () => {
    const emitter = new TypedEmitter<Events>();
    const order: string[] = [];
    emitter.on("bar", () => {
      order.push("first");
      emitter.on("bar", () => order.push("late"));
    });
    emitter.on("bar", () => order.push("second"));
    emitter.emit("bar", "x");
    expect(order).toEqual(["first", "second"]);
  });
});
