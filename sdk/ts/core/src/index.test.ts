import { expect, test } from "vitest";
import { CORE_PACKAGE } from "./index.js";

test("core package surface is reachable", () => {
  expect(CORE_PACKAGE).toBe("@firemoot/core");
});
