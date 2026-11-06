# Deploying the sync relay to Fly.io

The sync relay (`:sync-relay-server`) is the only server-side component today (the
Publishing Service will be a separate Fly app later). It's a single Ktor/Netty service
plus a file-backed database, so the deployment is deliberately small: **one Dockerfile,
one `fly.toml`, one volume.** House Rule #1 is unchanged throughout — the relay stores
ciphertext only; nothing here decrypts user data.

## What's in the repo

| File | Purpose |
|------|---------|
| [`sync-relay-server/Dockerfile`](../../sync-relay-server/Dockerfile) | Multi-stage build: Gradle assembles the Ktor fat jar, a slim JRE runs it. |
| [`sync-relay-server/fly.toml`](../../sync-relay-server/fly.toml) | Fly app config: port, env defaults, the volume mount. |
| [`.dockerignore`](../../.dockerignore) | Prunes build outputs / `.git` / `node_modules` so the build context stays small. |

The Docker build context is the **repo root** (the Gradle build is multi-module), so all
`fly` commands below run from the repo root and point `--config` / `--dockerfile` into the
module.

## Configuration contract

All runtime config comes from the environment via `RelayConfig.fromEnv()`. With nothing
set, it falls back to the defaults below — ⚠ **with dev mode OFF**: the relay fails closed.
`./gradlew :sync-relay-server:run` is still a dev-mode relay because that Gradle task exports
`DEV_MODE=true` itself (`DEV_MODE=false ./gradlew :sync-relay-server:run` overrides it).

| Var | Default | Deploy value | Notes |
|-----|---------|--------------|-------|
| `PORT` | `8080` | `8080` | Listen port; Fly routes `internal_port` here. |
| `BIND_HOST` | `0.0.0.0` | `0.0.0.0` | Bind address. |
| `DEV_MODE` | `false` | **`false`** | The `X-Dev-Owner` auth bypass. Unset or unparseable = `false` (fail closed); keep the explicit `false` anyway. **Never `true` on any public bind** — otherwise anyone can act as any owner. The relay logs a loud warning (but still starts) if `DEV_MODE=true` on a non-loopback bind. |
| `DATABASE_URL` | _(unset → in-memory)_ | `jdbc:sqlite:/data/relay.db` | Durable backend. Unset = in-memory (registrations wiped on restart). |
| `BLOB_DIR` | _(derived next to the DB)_ | `/data/relay-blobs` | Content-plane + share-link chunk storage. |
| `CORS_ALLOWED_ORIGINS` | _(apex default)_ | _(see below)_ | Comma-separated web origins allowed to call the relay cross-origin. |

The defaults for prod live in `fly.toml` `[env]`; put any genuinely secret values behind
`fly secrets set` instead.

## First deploy

```bash
# From the repo root.

# 1. Create the app (writes/updates the app name in fly.toml). Skip if it exists.
fly launch --no-deploy --copy-config --config sync-relay-server/fly.toml

# 2. Create the persistent volume for the SQLite db + blobs (same region as the app).
fly volumes create relay_data --size 3 --region fra

# 3. Deploy (build context = repo root).
fly deploy --config sync-relay-server/fly.toml --dockerfile sync-relay-server/Dockerfile
```

## Scaling caveat (read before scaling out)

Persistence is **SQLite on a single volume** (`docs/proposals/sync-relay-persistence.md`,
Phase 1) — a single writer. **Do not run more than one machine** until the relay grows a
Postgres backend (deferred). `fly.toml` pins `min_machines_running = 1` with auto-stop
off; keep it that way. Scaling horizontally would split state across volumes and corrupt
the registry.

## After deploy — point the clients at it

The native clients default to the **production relay** (`https://relay.tenshu.app`), so
pointing them at *that* needs no change; the overrides below are for a custom relay (your
Fly app) or for local-relay dev:

- **Web** (`web/apex`): set `VITE_TENSHU_RELAY_URL=https://<app>.fly.dev` at build time.
- **Desktop**: the `syncRelayUrl` binding reads `TENSHU_RELAY_URL` (env) or
  `-Dtenshu.relayUrl` (system property) at runtime — e.g.
  `TENSHU_RELAY_URL=https://<app>.fly.dev ./gradlew :control-desktop:run`.
- **Android**: no runtime knob, so the value is baked at build time into `RelayConfig`
  (see `control-shared-proxy/build.gradle.kts`). Set `tenshu.relayUrl=https://<app>.fly.dev`
  in the gitignored `local.properties` (or pass `-Ptenshu.relayUrl=…` / `TENSHU_RELAY_URL`
  at build) and rebuild.
- **Share-link host**: the recipient viewer host (`ShareLinkUrl.DEFAULT_HOST`) is the
  **web** origin, not the relay — point it at wherever the apex is deployed.

## CORS (web share viewer)

The web share viewer (`/s/...`, served from the web origin) fetches `GET /share/...` from
the relay origin — a cross-origin request the browser blocks unless the relay returns an
`Access-Control-Allow-Origin` for that origin. (Native desktop/Android clients aren't
subject to same-origin, so this only affects the web.)

- **Default**: the relay allows `https://tenshu.app` + the `drive`/`control` subdomains. If
  your web app is served from the apex, **nothing to configure** — the share viewer works.
- **Other origins**: if the web app is served from a different origin (a `*.fly.dev`, a
  staging/preview URL, a custom domain), set `CORS_ALLOWED_ORIGINS` to a comma-separated
  list of full origins:

  ```bash
  fly secrets set CORS_ALLOWED_ORIGINS="https://app.example.com,https://staging.example.com"
  ```

  A non-empty `CORS_ALLOWED_ORIGINS` **replaces** the apex default (it is not merged), so
  include every web origin you need — including the apex if you still serve from it.

`DEV_MODE=true` opens CORS to any host (local apex iteration only); the interlock keeps
that off any public bind.
