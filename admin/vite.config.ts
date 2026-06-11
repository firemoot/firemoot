import { defineConfig } from "vite";

// The admin SPA is baked into the server jar as classpath resources and served
// at /admin (see AdminSpaRoutes). Stable, unhashed filenames and `minify: false`
// keep the committed build output deterministic so CI can drift-gate it the same
// way it gates the generated SDK.
export default defineConfig({
  base: "/admin/",
  build: {
    outDir: "../server/src/main/resources/admin",
    emptyOutDir: true,
    assetsInlineLimit: 0,
    minify: false,
    target: "es2022",
    rollupOptions: {
      output: {
        entryFileNames: "assets/app.js",
        chunkFileNames: "assets/[name].js",
        assetFileNames: "assets/app.[ext]",
      },
    },
  },
  server: {
    // Dev convenience only (the user runs this, never CI): proxy the admin API
    // to a locally running server so the SPA can talk to /admin/* during `dev`.
    port: 6669,
    proxy: {
      "/admin/login": "http://localhost:6668",
      "/admin/session": "http://localhost:6668",
      "/admin/metrics": "http://localhost:6668",
      "/admin/webhooks": "http://localhost:6668",
      "/admin/api-keys": "http://localhost:6668",
    },
  },
});
