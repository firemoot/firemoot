import { expect, test } from "vitest";
import { postV1Users, postV1ChannelsTypeIdMessages } from "./index.js";

test("generated SDK operations are exported as functions", () => {
  expect(typeof postV1Users).toBe("function");
  expect(typeof postV1ChannelsTypeIdMessages).toBe("function");
});
