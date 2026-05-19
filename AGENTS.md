# AGENTS.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview

**app-nextxp** is an Enonic XP application that enables integration between Enonic XP CMS and Next.js frontend applications. It acts as a
bridge: when content is previewed in Enonic Content Studio, this app proxies rendering requests to an external Next.js server and returns
the result as a preview.

The app itself is a thin shell — nearly all logic lives in the companion library **lib-nextxp** (`com.enonic.lib:lib-nextxp`), which is
included as a Gradle dependency.

## Build Commands

```
./gradlew build          # Build the application JAR
./gradlew deploy         # Deploy to a local XP instance (XP_HOME must be set)
./gradlew clean build    # Clean and rebuild
```

There are no tests or linting configured in this repository.

## Architecture

### This App (app-nextxp)

The app has three source files under `src/main/resources/`:

- **main.js** — Application entry point. On the master cluster node, subscribes to XP node/repo events via `lib-nextxp/event` to trigger
  Next.js revalidation when content changes.
- **site/site.xml** — Declares a site-level config field (`nextjs-config` CustomSelector) and two controller mappings that route all
  requests through `lib-nextxp/proxy.js`.
- **services/configurations/configurations.js** — HTTP service backing the CustomSelector; returns the list of named Next.js configurations
  from `lib-nextxp/config`.

### Companion Library (lib-nextxp)

All core logic lives here. The key modules (ES6, transpiled by Gradle plugin):

- **proxy.es6** — Main proxy controller. Receives XP requests, forwards them to the configured Next.js server via `lib-http-client`, manages
  Next.js preview cookies (cached per site), handles redirects/retries, and post-processes HTML/JS/CSS responses to rewrite URLs for Content
  Studio embedding.
- **config.es6** — Reads `app.config` properties matching `nextjs.<name>.url` / `nextjs.<name>.secret` to support multiple named Next.js
  configurations. Falls back to `http://127.0.0.1:3000` with secret `mySecretKey`. Resolves which config applies to a site via its
  `siteConfig`.
- **event.es6** — Subscribes to XP `node.*` and `repository.*` events. On content publish (master branch), sends revalidation requests (
  `/api/revalidate`) to the Next.js server. Uses a Java-based debounce executor for bulk revalidation.
- **parsing.es6** — Parses XP request paths into frontend-relative paths, handling edit mode (ID-based URLs), component sub-paths (
  `/_/component/...`), and query parameter serialization.
- **postprocessing.es6** — Rewrites URLs in proxied HTML/JS/CSS responses so that `/_next/...` and `/api/...` references point through the
  XP proxy rather than directly to the Next.js server. Also extracts single-component HTML via
  `<details data-single-component-output="true">` markers and injects a `<base>` tag for correct asset resolution.

### Configuration

Next.js server URLs and secrets are configured in `<xp-home>/config/com.enonic.app.nextxp.cfg`:

```properties
nextjs.default.url=http://localhost:3000
nextjs.default.secret=mySecret
# Multiple named configs supported:
nextjs.production.url=https://my-nextjs-app.example.com
nextjs.production.secret=prodSecret
```

Sites select a configuration via the `nextjs-config` field in the site config (Content Studio).

### Key Concepts

- The proxy only works on the **draft** branch and is blocked in **live** mode — it's designed for Content Studio preview, not production
  serving.
- Next.js preview cookies (`__prerender_bypass`) are cached in-memory per site with a 1-hour TTL and 900-entry limit.
- Revalidation events only fire on the **master** cluster node to avoid duplicate requests.
- Move/rename events track old paths to revalidate both old and new content URLs.

## Gradle & Dependencies

- **XP SDK**: version defined in `gradle.properties` as `xpVersion` (currently 7.12.0)
- **Gradle plugins**: `com.enonic.xp.app` (XP app packaging), `com.enonic.defaults` (Enonic defaults)
- **Runtime deps**: `lib-cluster` (from XP SDK), `lib-nextxp` (companion library)
- Java source compatibility: 11
