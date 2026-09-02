# Next.js Preview for Enonic XP

Content Studio preview widget that renders Next.js content using encrypted redirect URLs.
Unlike [app-liveview-iframe](https://github.com/enonic/app-liveview-iframe) which reads URL mappings from a config file, this app fetches
them dynamically from the Next.js server at `/api/mappings`.

## Installation

Deploy the application JAR to your Enonic XP instance, then add it to your site via Content Studio.

## Configuration

### XP side

Create `com.enonic.app.nextxp.cfg` in your XP config directory:

```properties
nextjs.default.url=http://localhost:3000
nextjs.default.secret=mySharedSecret
nextjs.production.url=https://my-nextjs-app.example.com
nextjs.production.secret=prodSecret
```

| Key pattern                  | Description                              |
|------------------------------|------------------------------------------|
| `nextjs.<configName>.url`    | URL of the Next.js server                |
| `nextjs.<configName>.secret` | Shared secret for AES-256-GCM encryption |

When multiple configurations are defined, each site can select which one to use via the site configuration form. If omitted, the `default`
configuration is used.

NOTE: If no configuration is provided, the app defaults to `url=http://127.0.0.1:3000` with `mySecretKey` secret.

### Next.js side

The Next.js server must expose a `GET /api/mappings` endpoint that returns URL mapping rules:

```json
{
  "mappings": [
    {
      "sources": [
        "type:app:article",
        "/articles/.*"
      ],
      "target": "/blog/${_name}",
      "matchAny": true
    },
    {
      "sources": [
        "/.*"
      ],
      "target": "${_path}"
    }
  ]
}
```

#### Mapping fields

| Field      | Description                                                                    |
|------------|--------------------------------------------------------------------------------|
| `sources`  | Array of source patterns — content field constraints or path regex (see below) |
| `target`   | URL path template with `${field}` variable substitution                        |
| `matchAny` | `true` to require any source match, `false` (default) to require all           |

#### Source patterns

Sources can be content field constraints or path regex patterns, mixed in a single list:

- **Content constraints:** `type:app:article`, `data.category:foo`, `_path:'/features/.*'`
- **Path regex:** `/articles/.*`, `/products/.*`

TIP: Learn more about [content field constraints](https://developer.enonic.com/docs/code/stable/web/sites/mappings#match_mappings)
and [path regex](https://developer.enonic.com/docs/code/stable/web/sites/mappings#pattern_mappings) in the Enonic XP docs.

#### Target template variables

- `${_id}`, `${_name}`, `${_path}` — Content identifiers
- `${siteRelativePath}` — Content path relative to the nearest site (`/` for the site itself, full `_path` outside a site)
- `${type}`, `${displayName}`, `${language}` — Content metadata
- `${data.<field>}` — Content data fields (supports nested paths like `${data.product.category}`)
- `${x.<app>.<mixin>.<field>}` — Extra data (x-data/mixin) fields

## How it works

1. Content Studio calls the preview widget with content details
2. The widget reads `url` + `secret` for the site's configured Next.js server
3. URL mappings are fetched from `<url>/api/mappings` (cached for 24 hours, stop/start the app to clear it)
4. Content is matched against mapping rules to resolve the target URL
5. `{xpProject}` is encrypted with AES-256-GCM using the shared secret
6. The resolved URL is returned with `?xp=<encrypted-blob>` appended
7. Content Studio loads the URL in its preview pane

## Compatibility

Requires Enonic XP 8.1+.
