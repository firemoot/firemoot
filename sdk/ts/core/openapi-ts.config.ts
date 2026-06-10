import { defineConfig } from "@hey-api/openapi-ts";

// Generates the @firemoot/core transport from the server's OpenAPI document.
// The spec is regenerated from the tapir endpoints via
// `sbt "server/runMain com.firemoot.OpenApiExport openapi.json"`; CI fails on drift.
export default defineConfig({
  input: "../../../openapi.json",
  output: "src/generated",
  plugins: ["@hey-api/client-fetch", "@hey-api/typescript", "@hey-api/sdk"],
});
