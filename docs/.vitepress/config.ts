import { defineConfig } from "vitepress";

// The Firemoot docs site (PLAN.md M4.9). The internal decision records and the
// downstream compatibility audit live under docs/ too, but are not published.
export default defineConfig({
  title: "Firemoot",
  description: "Self-hosted, single-binary realtime chat backend on Postgres.",
  cleanUrls: true,
  lastUpdated: true,
  srcExclude: ["frented-compat-audit.md", "decisions/**", "README.md"],
  themeConfig: {
    nav: [
      { text: "Guide", link: "/guide/quickstart" },
      { text: "Protocol", link: "/guide/protocol" },
    ],
    sidebar: [
      {
        text: "Guide",
        items: [
          { text: "Quickstart", link: "/guide/quickstart" },
          { text: "Auth model", link: "/guide/auth" },
          { text: "Protocol reference", link: "/guide/protocol" },
          { text: "Migrating from Stream", link: "/guide/migration" },
          { text: "Sizing & performance", link: "/guide/sizing" },
          { text: "Hosting", link: "/guide/hosting" },
        ],
      },
    ],
    socialLinks: [{ icon: "github", link: "https://github.com/firemoot/firemoot" }],
    search: { provider: "local" },
    footer: {
      message: "Released under the Apache-2.0 License.",
      copyright: "Firemoot",
    },
  },
});
