# Changesets

This folder drives the `@firemoot/*` SDK release flow (PLAN.md M4.7).

To record a change for the next release, run `pnpm changeset`, pick the
affected packages (`@firemoot/core`, `@firemoot/client`, `@firemoot/test`) and a
bump type, and commit the generated markdown file alongside your change.

On merge to `main`, the gated `Release` workflow (`.github/workflows/release.yml`)
opens a "Version Packages" PR that consumes the pending changesets, bumps
versions and updates changelogs; merging that PR publishes to npm.

The publish is **inert until launch**: it only runs when the repository variable
`RELEASE_ENABLED` is `true` and an `NPM_TOKEN` secret is present (see §12 of
`PLAN.md` - creating the npm `@firemoot` org is a deliberate user step).

See the [changesets docs](https://github.com/changesets/changesets) for detail.
