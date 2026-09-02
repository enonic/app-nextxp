# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Gemini CLI, Cursor, Copilot, Warp and others) working in this repository.

## What This Is

An Enonic XP Content Studio preview widget (`com.enonic.app.nextxp`) that enables editors to preview Next.js-rendered content. It fetches
URL mappings dynamically from the Next.js server at `/api/mappings` (unlike its predecessor `app-liveview-next` which reads them from a
`.cfg` config file).

## Build & Test

```bash
./gradlew build          # Build the app (produces build/libs/app-nextxp.jar)
./gradlew test           # Run Java tests (JUnit 5 + Mockito)
./gradlew clean build    # Full rebuild
```

Requires: Java 17+, Gradle 9.4.1 (wrapper included). Uses Enonic XP plugin 4.0.0-A3 via `com.enonic.xp.settings` in `settings.gradle`.
Dependencies are managed through `gradle/libs.versions.toml` (version catalog) and `xplibs.*` for XP platform libs.

## Architecture

Two-layer design: **Java** for crypto and content-to-URL resolution, **JavaScript** for orchestration.

### Request Flow

1. `preview-next.js` (widget controller) receives request from Content Studio
2. `config.js` reads `url` + `secret` from `.cfg` file, resolved via site's CustomSelector config name
3. `mappings.js` fetches mappings from `<url>/api/mappings` (cached 24h via `lib-cache`)
4. Java `UrlMappingsResolver` resolves content to an external URL using source matching + template substitution
5. Java `PayloadEncoder` encrypts `{xpProject}` with AES-256-GCM
6. Widget returns response with `?xp=<encrypted-blob>` appended to the resolved URL

### Java Layer (`src/main/java/com/enonic/app/preview/nextjs/`)

- `PayloadEncoder` — AES-256-GCM encryption/decryption, SHA-256 key derivation
- `UrlMappingsResolver` — resolves content ID to external URL via mapping rules (ScriptBean, called from JS)
- `UrlMapping` — mapping rule: sources (match patterns) + target (URL template with `${field}` placeholders)
- `ContentFieldAccessor` — implements `StringLookup` for Apache Commons `StringSubstitutor`; resolves content fields (`_id`, `_name`,
  `_path`, `type`, `data.*`, `x.*`) and evaluates constraint expressions
- `MatchStrategy` — `ANY` (first match wins) vs `ALL` (all must match)
- `PreviewCspProcessor` — `AdminExtensionResponseProcessor` (OSGi component, requires XP ≥ 8.1) adding configured Next.js origins to the
  Content Studio tool page's `frame-src`/`connect-src` CSP so the preview iframe can render them; falls back to `http://localhost:3000`
  only when XP runs in dev mode — in prod, unconfigured means no CSP contribution

### JavaScript Layer (`src/main/resources/`)

- `admin/extensions/preview-next/preview-next.js` — widget entry point
- `lib/export/config.js` — parses `nextjs.<name>.(url|secret)` from app config, site-aware config resolution
- `lib/export/mappings.js` — fetches from `/api/mappings`, caches with `lib-cache` (24h TTL), bridges API response to `UrlMappingsResolver`
  format via `toResolverConfig()`
- `lib/export/widget.js` — shared utilities (param validation, context switching, response builders, `buildNextUrl()`)
- `services/configurations/configurations.js` — CustomSelector service listing available configs

## Configuration

Config file: `com.enonic.app.nextxp.cfg`

```properties
nextjs.default.url=http://localhost:3000
nextjs.default.secret=mySecret
nextjs.production.url=https://my-nextjs-app.example.com
nextjs.production.secret=prodSecret
```

Sites select their config via a CustomSelector form field (`cms.yml` -> `configurations` service). Resolution: site config name -> named
config -> `default` -> hardcoded `http://127.0.0.1:3000`.

## Mapping Source Format

Sources mix content field constraints and path regex in a single list (parsed by `ContentFieldAccessor` and `UrlMapping.matchSource()`):

- Content constraints: `type:app:article`, `data.category:foo`, `_path:'/features/.*'`
- Path regex: `/articles/.*`, `/products/p1\\?category=foo`

## Design Docs

- `docs/superpowers/specs/2026-04-09-preview-nextjs-design.md` — full spec
- `docs/superpowers/plans/2026-04-09-preview-nextjs.md` — implementation plan
