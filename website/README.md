# Website

Separate desktop app and library sites, built together for GitHub Pages.
Requires Node.js 22 or newer.
Fetch release tags before building (`git fetch origin --tags`). The build queries
GitHub's latest stable release to select app documentation.

```sh
cd website
npm ci --ignore-scripts
npm test
npm run build
npm run check:links
npm run preview
```

Open `http://localhost:4173`. Output is written to `build/website` at the repository
root. Run `./gradlew dokkaGenerate` from the root first to include local API docs.

## Content

- `/`: desktop app, downloads, screenshots, user guide, settings, and app changelog.
- `/library/`: library installation, module guides, API versions, library changelog,
  and contributor material.

Each site has its own navigation and search index. Their footers link to the
other product. Technical guides may link to relevant product references.

- `src/pages.mjs`: landing pages, downloads, screenshots, and API archive index.
- `src/app-docs.mjs`: publishes the app's user guide, settings, and changelog.
- `released-app/v{version}/`: corrections for releases predating the app guides.
  These pages must describe that exact release; newer releases use their own
  tagged Markdown. Source links identify the correction's revision.
- `src/docs.mjs`: publishes library and contributor Markdown, module READMEs,
  `Module.md`, and module contracts. App/plugin module docs and agent instructions
  are excluded. The library index is generated from its navigation.
- `src/markdown.mjs`: shared Markdown rendering and link resolution.
- `assets/`: styles and browser behavior; release asset names match
  `publish-binaries.yml`.

The build reuses the app's `icons/logo.svg` for the header, footer, and favicon.
The palette uses its green and charcoal, with darker green text on light surfaces
for readable contrast.

Edit documentation in its owning Markdown file. Relative links between published
guides stay within their version; source links point to the recorded Git revision.
Library search stays within the selected version; app search covers app help.
App help, settings, and changelog come from the latest published stable release,
matching the download selector. Development, prerelease, and older-tag builds
cannot substitute their app docs. Missing stable guides stop the build.
Mermaid is loaded locally only on pages with diagrams. Downloads use the public GitHub release API and
fall back to the releases page when it is unavailable.

Use `npm run format` after website edits. CI checks formatting, archive behavior,
download selection, and generated links. It does not require a JVM build for
website-only pull requests.

## Publication and history

`deploy-docs.yml` builds development docs on `master` and release docs for `v*`
tags. Manual runs use the selected ref: a tag publishes its version, a branch
updates development. Configure GitHub Pages to use **GitHub Actions**.
Published releases and successful `Publish Binaries` runs also trigger a rebuild, including
releases created by GitHub Actions. This updates stable app docs after downloads
become available. Prerelease tags remain excluded from app docs and downloads.

The `gh-pages` branch stores the complete published archive. A deployment reads
that branch, adds the new version, saves it back, and uploads the same site to
Pages. Archive read failures stop publication. Runs queue to avoid discarding
pending release builds.

- `/library/guides/dev/` and `/library/api/dev/`: replaceable development content.
- `/library/guides/v{version}/` and `/library/api/v{version}/`: retained releases.
- `/library/api/latest/`: highest stable API version, never development or a prerelease.
- `/library/versions.html`: available guides, module documentation, changelogs, and APIs.

Release reruns preserve existing version content. Each deployment backfills any
missing tagged APIs before publication: it exports each tag, checks `VERSION`,
and runs that release's Gradle wrapper with `RELEASE=true`. The first run builds
the full history; subsequent runs retain completed versions. Historical releases
without archived Markdown guides appear as API-only versions.

Run `npm run backfill:api` locally to generate `build/website-history`; set
`WEBSITE_ARCHIVE` to an existing archive first when one is available. The source
checkouts and build outputs stay under `build/website-sources`, leaving the working
checkout unchanged. Completed API archives include `source.json` with the tag and
commit. Failed builds stop publication and can be resumed; incomplete copies are
never advertised as versions. Gradle needs the release's toolchains and network
access to resolve dependencies (currently Java 21 and 25).

Existing `docs/v{version}/`
archives are migrated automatically. Old API and guide URLs redirect to the
new locations, retaining URL fragments.

## Separate library hosting

`build/website/library` is independently deployable: its assets, search indexes,
guides, and API files use relative URLs within that directory. Cross-product
references point to the app's `WEBSITE_URL`. Publish that directory as a site
root to move the library to another hostname; no route rewrite is required.

GitHub Pages provides `<account>.github.io` and repository subpaths. An additional
hostname such as `library.ketraterm.github.io` is not an assignable Pages domain.
A custom library hostname requires an owned domain and DNS configuration.

For local archive checks, set `WEBSITE_ARCHIVE` to an existing site directory
outside `build/website`. `WEBSITE_RELEASE` selects a version matching `VERSION`;
without it the build updates development. CI also supplies `WEBSITE_SOURCE_REF`,
`WEBSITE_URL`, and `WEBSITE_REQUIRE_API=true`. `WEBSITE_APP_RELEASE=v{version}`
can select a specific stable tag for an offline app-doc preview; production leaves
it unset and queries GitHub. `GITHUB_TOKEN` authenticates that request when set.
