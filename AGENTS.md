# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Gemini CLI, Cursor, Copilot, Warp and others) working in this repository.
`CLAUDE.md` includes this file.

## What This Is

An Enonic XP app (`com.enonic.app.nextxp`, display name "Next.XP") that integrates Content Studio with a Next.js frontend. It does two
things:

1. **Preview widget** — renders Next.js pages inside Content Studio by resolving content to an external URL and appending an encrypted
   `?xp=` payload. URL mappings are fetched from the Next.js server at `/api/mappings` (the predecessor
   [app-liveview-iframe](https://github.com/enonic/app-liveview-iframe) reads them from a `.cfg` file).
2. **Revalidation** — listens to XP content events and calls `<url>/api/revalidate` on the Next.js server when content is published, moved
   or renamed.

Requires Enonic XP 8.1+.

## Build, Test, Deploy

```bash
./gradlew build                                                        # produces build/libs/app-nextxp.jar
./gradlew test                                                         # all Java tests (JUnit 5 + Mockito + AssertJ)
./gradlew test --tests 'com.enonic.app.preview.nextjs.UrlMappingsResolverTest'   # one test class
./gradlew test --tests '*UrlMappingsResolverTest.testQueryParamsMatch'           # one test method
enonic project deploy                                                  # deploy to the Enonic CLI sandbox named in .enonic (site8)
```

`enonic project deploy` refuses sandboxes older than `xpVersion` (8.1.0-SNAPSHOT); against a source-built XP copy `build/libs/*.jar`
into `$XP_HOME/deploy/` instead. The `com.enonic.xp.admin.extension` import is optional, so the jar also resolves on XP 8.0.x with the
CSP processor inactive.

Gradle 9.4.1 via the wrapper. Enonic XP gradle plugin `com.enonic.xp.settings` 4.0.0-A3 (`settings.gradle`) provides the `xplibs.*`
catalog; third-party versions live in `gradle/libs.versions.toml`. Dependencies resolve from `xp.enonicRepo('dev')`, so SNAPSHOT XP libs
are expected. Only Java has tests; the JavaScript layer is untested.

## Architecture

Two layers: **Java** ScriptBeans for crypto, mapping resolution and debouncing; **JavaScript** (XP Nashorn-style, `require`/`exports`) for
orchestration. JS calls Java via `__.newBean('com.enonic.app.preview.nextjs.<Class>')`.

### Preview Request Flow

Widget descriptor `admin/extensions/preview-next/preview-next.yml` registers a `contentstudio.liveview` extension for all content types
under a site. Content Studio calls `preview-next.js` with `contentId`, `contentPath`, `repo`, `branch`, `mode`, `archive`, `type`.

1. `widget.js` validates params (missing `contentId`/`contentPath`/`repo` -> 400), then switches into the target repo/branch as
   `role:system.admin` and loads the nearest site.
2. `config.js` resolves `{url, secret}` for that site (see Configuration).
3. `PayloadEncoder.encode()` encrypts `{"xpProject": "<project>"}` with the secret. Project name is the repo id minus `com.enonic.cms.`.
4. `mappings.js` fetches `<url>/api/mappings?xp=<blob>` and caches the result per XP project (a project maps to one Next.js server)
   for 24h via `lib-cache`; failed fetches are not cached. The response may be project-specific (e.g. locale-prefixed targets). Each mapping
   is normalised to `{baseUrl, secret, sources, target, matchAny}` for Java.
5. `UrlMappingsResolver.resolve()` runs in an admin context, loads the content, computes the site-relative path and returns the first
   matching mapping's URL: target template expanded with Apache Commons `StringSubstitutor`, resolved against `baseUrl`, normalised, and
   stripped of a trailing slash (so the site root maps to `/no`, not `/no/`).
6. `widget.js#buildNextUrl()` appends `?xp=<blob>` (or `&xp=` if the URL already has a query).
7. Response: `mode=inline|edit` -> 200 JSON; otherwise a redirect. The URL is always also placed in the `enonic-widget-data` header.
   418 means "cannot render" (no mapping matched, `base:shortcut`, or archived content). A failed mappings fetch (network error, or a
   404 from a Next.js server whose `ENONIC_MAPPINGS` lacks the project) resolves to no mappings and therefore 418; 500 only for
   unexpected errors while switching context or building the URL.

### Revalidation Flow

`main.js` runs on app start and calls `lib/export/event.js#subscribe()`:

- Queries all projects for `portal:site` nodes that have this app in `siteConfig` and keeps their repo ids in `REPOS`. Refreshed on any
  `repository.*` event and when a site is pushed to `master`.
- Listens to `node.*` events. Only the cluster leader (`lib-cluster`) handles them. For nodes under `/content/` in a tracked repo:
  pushes to `master` trigger a debounced (500ms, Java `DebounceExecutor`) `GET <url>/api/revalidate?path=&xp=<blob>` with header
  `Content-Studio-Project: <project>`. Moves/renames stash the old path so it is revalidated on the next `master` push.

### Java Layer (`src/main/java/com/enonic/app/preview/nextjs/`)

- `PayloadEncoder` — AES-256-GCM, key = SHA-256(secret), output = base64url(IV[12] + ciphertext + tag), no padding. `decode()` mirrors it.
- `UrlMappingsResolver` — ScriptBean; parses `{configName: {mappings: [...]}}`, picks the mapping list by the site's or project's
  `configName`, falls back to `default`, then to a built-in `http://localhost:3000` + `${_path}` mapping.
- `UrlMapping` — sources + target + `MatchStrategy` (`ANY` = first match wins, `ALL` = every non-blank source must match; empty sources
  never match). `matchSource()` tries a content constraint first and falls back to a regex against the site-relative path.
- `ContentFieldAccessor` — `StringLookup` for `StringSubstitutor` and constraint evaluator. Fields: `_id`, `_name`, `_path`, `type`,
  `displayName`, `language`, `valid`, `data.<path>`, `x.<app>.<mixin>.<field>`, plus custom `siteRelativePath`. Missing values resolve to
  `""` in templates.
- `DebounceExecutor` — single daemon thread; each call cancels the previous pending task.
- `PreviewCspProcessor` — OSGi `AdminExtensionResponseProcessor` bound to `com.enonic.app.nextxp:preview-next` and reading the same
  `.cfg` as OSGi config (`configurationPid`). Adds every `nextjs.*.url` origin plus `'self'` to `frame-src`/`connect-src`/`style-src`. In
  `RunMode.DEV`
  with no config it allows `http://localhost:3000`; in prod, unconfigured means no CSP contribution. Package-private constructor takes a
  `RunMode` for tests.

### JavaScript Layer (`src/main/resources/`)

- `admin/extensions/preview-next/preview-next.js` — widget controller
- `lib/export/widget.js` — param validation, admin-context switching (`switchContext`), response builders, `buildNextUrl()`,
  `getProjectName()`
- `lib/export/config.js` — parses `nextjs.<name>.(url|secret)` from `app.config` (cached in module scope), site-aware lookup
- `lib/export/mappings.js` — fetch + cache + `toResolverConfig()`
- `lib/export/event.js` — revalidation (see above)
- `services/configurations/configurations.js` — CustomSelector service listing config names for the site form
- `cms/cms.yml` — site form with a single optional `configName` CustomSelector; `cms/site.yml` marks the app as a site app

## Configuration

`com.enonic.app.nextxp.cfg`:

```properties
nextjs.default.url=http://localhost:3000
nextjs.default.secret=mySecret
nextjs.production.url=https://my-nextjs-app.example.com
nextjs.production.secret=prodSecret
```

Resolution in `config.js`: site's `configName` -> named config -> `default` -> hardcoded
`{url: http://localhost:3000, secret: mySecretKey}`.

Non-obvious: `config.js` selects the config on the JS side and `preview-next.js` always hands the resolved mappings to Java under the
`default` key. `UrlMappingsResolver` also looks up `configName` from site or **project** site configs, but with a single `default` entry
that lookup always falls through to `default`. Effective config selection therefore happens in JS, and only from the site (not project).

## Mapping Source Format

`/api/mappings` returns `{mappings: [{sources: [...], target: "...", matchAny: bool}]}`. Sources mix content constraints and path regex:

- Content constraint: `<field>:<regex>`, e.g. `type:app:article`, `data.category:foo`, `_path:'/features/.*'` (single quotes stripped).
  Parsed by `ContentFieldAccessor.parse()`; anything without a `:` is treated as a path regex.
- Path regex: matched with `Pattern.matches()` against the **site-relative** path, e.g. `/articles/.*`, `/products/p1\\?category=foo`.

Target templates use `${field}` with the same field names; a leading `/` is stripped in `toResolverConfig()` before resolving against
`baseUrl`.

The reference Next.js side (`nextxp-demo`, adapter `localizeMappings()`) prefixes targets with the locale of the project carried in the
blob unless it is the default locale, e.g. `/no/${siteRelativePath}`, and answers 404 for a project missing from its `ENONIC_MAPPINGS`,
which this widget turns into

418.

## Testing Notes

Tests mock XP services (`ContentService`, `ProjectService`) through a mocked `BeanContext` and call `initialize()` on the bean directly;
mappings are passed as mocked `ScriptValue` trees. See `UrlMappingsResolverTest#createMappings()` for the canonical fixture covering
`ANY`/`ALL`, constraints, regex with query strings, x-data and `siteRelativePath`.

## Versioning & CI

`master` is `6.0.0-SNAPSHOT` against `xpVersion=8.1.0-SNAPSHOT` (`gradle.properties`). Release branches `3.x`–`6.x` exist; CI
(`.github/workflows/enonic-gradle.yml`) builds every push with `enonic/release-tools/build-and-publish` and creates a GitHub release when
the build marks one. Dependabot covers gradle, npm and github-actions.
