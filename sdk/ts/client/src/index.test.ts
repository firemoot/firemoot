import { expect, test } from "vitest";
import { CLIENT_PACKAGE } from "./index.js";

test("client package surface is reachable", () => {
  expect(CLIENT_PACKAGE).toBe("@firemoot/client");
});
