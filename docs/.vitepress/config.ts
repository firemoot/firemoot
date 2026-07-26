import { defineConfig } from "vitepress";

// GitHub Pages serves a project site under /<repo>/, so the deploy workflow sets
// DOCS_BASE=/firemoot/. Local dev and preview leave it unset and get "/".
const base = process.env["DOCS_BASE"] ?? "/";

export default defineConfig({
  title: "Firemoot",
  description: "Stream Chat's developer experience, your infrastructure.",
  base,
  cleanUrls: true,
  lastUpdated: true,
  srcExclude: ["decisions/**", "README.md"],
  head: [
    ["link", { rel: "icon", type: "image/svg+xml", href: `${base}logo.svg` }],
    ["meta", { property: "og:type", content: "website" }],
    ["meta", { property: "og:site_name", content: "Firemoot" }],
    ["meta", { property: "og:title", content: "Firemoot - self-hosted realtime chat backend" }],
    [
      "meta",
      {
        property: "og:description",
        content:
          "Stream Chat's developer experience, your infrastructure. One JVM service and Postgres, Apache-2.0.",
      },
    ],
  ],
  themeConfig: {
    logo: "/logo.svg",
    nav: [
      { text: "Guide", link: "/guide/quickstart", activeMatch: "/guide/" },
      { text: "GitHub", link: "https://github.com/firemoot/firemoot" },
    ],
    sidebar: [
      {
        text: "Getting started",
        items: [
          { text: "Quickstart", link: "/guide/quickstart" },
          { text: "Configuration", link: "/guide/configuration" },
          { text: "Testing", link: "/guide/testing" },
        ],
      },
      {
        text: "Concepts",
        items: [
          { text: "Authentication", link: "/guide/auth" },
          { text: "Realtime protocol", link: "/guide/protocol" },
          { text: "Webhooks", link: "/guide/webhooks" },
        ],
      },
      {
        text: "Operations",
        items: [
          { text: "Self-hosting", link: "/guide/hosting" },
          { text: "Admin dashboard", link: "/guide/admin" },
          { text: "Sizing & performance", link: "/guide/sizing" },
        ],
      },
      {
        text: "Migration",
        items: [
          { text: "Drop-in Stream compatibility", link: "/guide/stream-compat" },
          { text: "Migrating from Stream", link: "/guide/migration" },
        ],
      },
    ],
    socialLinks: [{ icon: "github", link: "https://github.com/firemoot/firemoot" }],
    search: { provider: "local" },
    editLink: {
      pattern: "https://github.com/firemoot/firemoot/edit/main/docs/:path",
      text: "Edit this page on GitHub",
    },
    footer: {
      message: "Released under the Apache-2.0 License.",
      copyright: "Firemoot",
    },
  },
});
