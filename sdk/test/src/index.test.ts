import { expect, test } from "vitest";
import { TEST_HELPER_PACKAGE } from "./index.js";

test("test-helper package surface is reachable", () => {
  expect(TEST_HELPER_PACKAGE).toBe("@firemoot/test");
});
