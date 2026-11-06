# Server logging: levels, what is never logged, retention

**Scope:** the sync relay (`:sync-relay-server`), commerce (`:commerce-service`), and the Caddy in
front of each of them and of the apex. Established 2026-10-23 (branch `fix/server-logging`), after
the privacy-policy review found both JVM servers logging at DEBUG and commerce logging email
addresses. `:hub` is out of scope: it runs on the user's own hardware and logs to that machine's
journal, not to ours.

## Levels

- **INFO by default.** Each server ships `src/main/resources/logback.xml`. Before it, logback's
  fallback logged everything at DEBUG, which is what rotated the relay's container log down to
  ~15 minutes (the AWS SDK's per-request DEBUG lines; `tech-debt.md`, 2026-10-01).
- **One knob, `LOG_LEVEL`**, for our own loggers (`org.tenshu.*`) and Ktor's
  `io.ktor.server.Application` logger. Set it from `deploy.env` as **`RELAY_LOG_LEVEL`** or
  **`COMMERCE_LOG_LEVEL`** (the compose files map those onto the container's `LOG_LEVEL`, so one
  dev-lab env file can tell them apart), then `up -d --force-recreate <service>`. Put it back
  afterwards: DEBUG on the in-memory mailbox names owner prefixes for every request.
  - ⚠ `LOG_LEVEL` also sets the **root** logger, so DEBUG raises every library that is *not* pinned
    below, too.
  - ⚠ **A misspelt value is DEBUG, not INFO.** logback reads an unknown level (`DEGUB`, `verbose`)
    as DEBUG. `LogbackConfigTest` pins that, so a logback upgrade that changes it is noticed.
- **Pinned libraries stay at WARN whatever `LOG_LEVEL` says**: Ktor's internals, Netty, the AWS SDK
  and SQLite (relay); HikariCP (commerce). To debug one, edit its `<logger>` line and rebuild.
  ⚠ The Postgres driver and Jakarta/Angus Mail log through `java.util.logging`, not SLF4J, so
  logback cannot pin them; JUL's default (INFO) keeps them quiet, and the SMTP dialogue appears only
  with `mail.debug=true`, which nothing sets.
- **Tests** use each module's `src/test/resources/logback-test.xml` (`org.tenshu` at DEBUG, libraries
  at WARN), which logback prefers over the production file. `:system-test-utils` has one too, because
  both production files can sit on its classpath.
- ⚠ **The operational lines are INFO and stay on.** `Content sweep: armed, T=…`,
  `Entitlement ENFORCED …`, the `[admin]` audit lines and the one-line-per-request call log go
  through `io.ktor.server.Application`, which is why the config raises that logger explicitly
  while quieting the rest of `io.ktor`. The boot lines `Relay blobs: …`, `Relay persistence: …` and
  `Commerce issuer public key …` are `println` and appear at any level. `LogbackConfigTest` (in both
  modules) pins all of this.

Line format: `2026-10-23T17:38:53.816+00:00 INFO  [thread] logger - message`, on stdout, read
with `docker logs <container>`.

**The relay's call log** is one line per request, `<status>: <METHOD> <route template> in <n>ms`:

```
200 OK: GET /share/{shareId} in 12ms
401 Unauthorized: DELETE /sync/invite/{inviteId} in 0ms
404 Not Found: GET <unmatched> in 1ms
```

The template is the route the call matched, as declared in `routing/*.kt`, read off Ktor's resolved
`RoutingNode` (`RouteTemplateLogging.kt`). A request that matched no route (a scanner, a typo) logs
the fixed text `<unmatched>`, never its path, because that path is attacker-chosen text. The
unhandled-exception line and the malformed-body debug lines name the same template
(`call.routeTemplate()`).
⚠ Never log `call.request.path()`, `request.uri` or a path parameter on the relay; log
`call.routeTemplate()`. `LogStatementPiiGuardTest` flags the first three and the capability
parameter names (as the routes bind them: `shareId`, `ref`, `address`, …) in a log statement, and
fails if main sources import `io.ktor.server.request.path` / `uri` at all; `RouteTemplateLoggingTest`
checks the real log output.

⚠ **Ktor's engine writes one line templating cannot reach.** A failure that escapes the pipeline
past StatusPages is logged by `DefaultEnginePipeline.logFailure` as `<status>: <METHOD> - <path>`,
with the concrete path. StatusPages rethrows `CancellationException`, so **every mid-request client
disconnect** produces one, at DEBUG (on disk under `RELAY_LOG_LEVEL=DEBUG`); anything else that
escapes logs at ERROR. The production `logback.xml` therefore has a second `%replace` that swaps the
path in that shape for the literal `<path>` (`Unhandled: GET - <path>. Exception …`).
`RouteTemplateLoggingTest` drives a real disconnect through the shipped config and checks the line.

## Never logged

| Data | What the log carries instead |
|---|---|
| E-mail address | `***@domain` (`emailDomain`). A logback `%replace` also masks any address in any message or stack trace, as a backstop for library messages. The dev-only sender prints `***@domain#1a2b3c4d` (`emailTag`: first 8 hex of SHA-256 over the lower-cased address) so two sign-ups on one domain can be told apart; recompute with `printf %s 'jane@example.com' \| shasum -a 256 \| cut -c1-8`. |
| OTP code | Nothing. The one exception is the dev-only `LoggingEmailSender`, which `CommerceConfig.validate()` refuses when `DEV_MODE=false`. |
| Owner / account / device id | Its first 8 characters and `…` (`logId` in the relay, `shortId` in commerce). Enough to find the row and to tie one incident's lines together. ⚠ The `…` is part of the log text, not the id: query with the 8 characters only, `WHERE owner_id LIKE '3f2a9c1e%'`. Pasting `3f2a9c1e…%` matches nothing. |
| Secrets: pairing secrets, tokens, entitlement headers, OTPs | Nothing, not even a prefix. |
| Query strings | Not logged anywhere: the relay's call and error lines print the route template only, and Caddy drops the query. `?token=` (Google RTDN) and `?email=` (admin) travel in queries. (`?pairingSecret=` did until F7, 2026-10-28: the joiner's poll is now a POST carrying only the secret's hash. `?hash=` on the hub-offer fetch went the same day: the hub's long-lived rendezvous key now rides a POST body, `/sync/pairing/hub-offer/fetch`.) |
| Capabilities in path segments: share id, grant-mailbox address, EPC session id, invite id, content ref (relay); the share-viewer id and device id (apex) | The route template: `/share/{shareId}`, `/sync/content/{ref}`. Ktor logs the template it matched, and the engine's own failure line is masked to `<path>` (above). Caddy rewrites the same segments in its access **and** error logs with a `multi_regexp` filter on `request>uri` (table below). Either one alone would still write the id, which is why both do it. |
| Request / response headers, User-Agent | Not logged (Caddy deletes them; Ktor never logged them). |
| Client IP address | Not in the relay's or commerce's application log. The per-IP rate limiters key on it in memory and do not log it. Caddy logs it **truncated to /24 (IPv4) or /48 (IPv6)**. Exception: commerce's `[admin]` audit lines keep the **operator's** address, because that is what an access audit is for, and the admin console is off public DNS. |
| Exception messages from mail delivery | SMTP failures (anything with a `jakarta.mail.MessagingException` in its cause chain) are logged as `describeForLog(e)`: each class and its message with addresses redacted, no stack trace, because Jakarta Mail names the rejected recipient. Other failures on the same path (a store error) keep their stack trace. |

⚠ **This table covers what our own log statements write. Exception messages and stack traces are not
filtered beyond the email backstop**: a library's message can quote a request path (owner ids and
content refs travel in paths), a SQL statement or a snippet of the JSON it failed to parse. Those
appear only on ERROR/WARN lines for failures, but they are not guaranteed to be free of identifiers.

**Guard:** `LogStatementPiiGuardTest` in each module scans `src/main/kotlin` and fails when a log
statement interpolates an email, a raw owner/account/device id or a secret that has not been through
a redactor. It reads identifiers rather than values, so it is a tripwire, not a proof. A line that
genuinely needs one takes a trailing `// log-pii-ok: <reason>`; none does today.

## Where logs go and how long they last

| Log | Store | Bound |
|---|---|---|
| Relay, commerce, Postgres, mailpit (application lines + Caddy's *runtime* lines: certificates, config) | Docker `json-file`, the `x-logging` anchor in each compose file | **Size only:** 50 MB × 5 files per container. How much time that covers depends on traffic (days at normal load, hours in a storm). |
| Caddy **access** log, per site (`relay`, `commerce`, `apex`, `drive`) | `/var/log/caddy/<site>.log` in the `caddy_logs` volume (the dev lab's inner apex container: `apex_caddy_logs`). Read it with `docker exec <caddy> tail -f /var/log/caddy/relay.log` — JSON lines, pipe to `jq`. | **Time and size**, see below. |
| Caddy **error** log (`http.log.error`: one entry per failed proxied request, every 502/504) | `/var/log/caddy/errors.log`, same volume, same filter as the access log, same roll. | Same. |

⚠⚠ **The error log is why the global options block routes `http.log.error` away from the default
logger.** It carries the same request object as the access log — full IP, the full URI with its
query (`?pairingSecret=`), every header (`X-Entitlement`) — and without `log default { exclude
http.log.error }` it went to `docker logs` unfiltered on every upstream failure.

**How long the files last:** rolled daily (`roll_interval 24h`) or at 50 MiB, at most 5 rolled files
kept (`roll_keep 5`, gzipped), and a rolled file older than 7 days removed (`roll_keep_for 168h`).
⚠ Caddy rolls and cleans up **only when it writes**, and `roll_keep 5` usually binds before the 7
days does. So the honest statement is **at most about a week, normally 5–6 days, on a site that keeps
receiving traffic**; an idle site keeps its last lines until its next request. Docker's `json-file`
driver cannot bound by time at all, which is why these two logs no longer go to stdout.

⚠ `roll_interval` needs Caddy ≥ 2.11 (2.10 refuses to start), so the compose files and
`web/apex/Dockerfile` pin `caddy:2.11` rather than `caddy:2`: a box with a cached old `caddy:2` would
otherwise keep it, since `redeploy*.sh` pull only the app image.

**A time bound for the container logs**, if one is wanted: Docker has none for `json-file` or
`local`. The operator's route is the `journald` driver (`logging: driver: journald` in the
`x-logging` anchor) plus `MaxRetentionSec=7day` in the box's `/etc/systemd/journald.conf`, which
`docker logs` still reads. That is a host change on every box and is not in the repo. Since the
application logs now carry no addresses, IPs or full ids, the size bound is what applies.

### The Caddy `request>uri` filter

The filter applies its rules in order: first the query string goes, then each route family whose
path carries an identifier is rewritten to the template Ktor logs, and last a **backstop** replaces
any path segment of 20 or more id characters (`[A-Za-z0-9_%-]`) with `{id}`. It is in the
`access_log` snippet and the global `log errors` block of each Caddyfile. Coverage:

| Caddyfile | Logged as |
|---|---|
| `sync-relay-server/Caddyfile` | `/share/{shareId}` (and `/share/{shareId}/chunk/<n>`), `/sync/grant/{address}`, `/sync/epc/{sessionId}` (and `/sync/epc/{sessionId}/<direction>`), `/sync/content/{ref}`, `/sync/invite/{inviteId}` |
| `web/apex/Caddyfile` | `/s/{shareId}` (the Mode-A share viewer), `/control/data/devices/{deviceId}` |
| `deploy/scaleway-dev/stack/Caddyfile` | Both sets: one snippet serves every site on the dev box |

The relay list is every route with a `{param}` path segment in `sync-relay-server/.../routing/*.kt`.
A chunk index and an EPC direction (`up`/`down`) stay as they are, because neither is a capability.

- **The backstop** is what catches the spellings an anchored `^/share/` rule cannot see but Ktor
  still routes — `//share/<id>` (a relay URL configured with a trailing `/`; the clients now trim
  it) and `/%73hare/<id>` — and a capability route nobody wrote a rule for. It relies on every
  capability id being at least 20 characters (share id 22, content ref 69, invite id / grant address
  / EPC session id 32) and on no constant path segment being that long. It is listed twice because
  a match consumes the `/` after it, so an id directly after another id needs a second pass. Static
  asset names (`ShareViewerRoute-Bx9k2Q.js`) keep their name: the `.` ends the match.
- **`CaddyLogFilterTest`** (`:sync-relay-server`) turns this list into a failing test. It walks the
  relay's real Ktor route tree and the apex's `RuntimeApp.tsx` routes, fills every path parameter
  with a realistic value, runs each path through every `format filter` block of the three
  Caddyfiles — plain, `//`-prefixed, percent-encoded and with a query — and fails if an id survives
  or a constant route is rewritten. A new path parameter fails it until the test is given an example
  for it, which is the moment to decide whether it is a capability.
- ⚠ `/sync/content/` and `/sync/invite/` have constant siblings (`declare`, `inventory`, `reap`;
  `response`, `responses`, `ack`, `done`), and Go's RE2 has no lookahead. So the filter parks
  those siblings behind a space, which an HTTP/1 request line cannot contain. The generic rule then
  skips any segment that starts with a space, and the last rule removes the space again.
- `multi_regexp` needs Caddy ≥ 2.10. The pinned `caddy:2.11` has it.
- Verified 2026-10-27 against `caddy:2.11`. `caddy validate` passed on all three files, and the
  production relay and apex files were served real requests. No id reached either log file.
  (The backstop rules were added after that pass; `CaddyLogFilterTest` checks them with Java's
  regex engine, not with Caddy.)

**Deploying a filter change.** ⚠ The relay's Caddyfile is **bind-mounted** into the `caddy`
container (`sync-relay-server/docker-compose.yml`). `deploy/redeploy.sh` does `git pull`, which
updates the file on disk, but then pulls only the relay image, and its `up -d` leaves an unchanged
`caddy` container alone — so the running Caddy keeps the **old** filter. Apply it on the box with
`$COMPOSE exec caddy caddy reload --config /etc/caddy/Caddyfile` (or `$COMPOSE up -d
--force-recreate caddy`; plain `up -d caddy` is a no-op), `$COMPOSE` being the line at the top of
`redeploy.sh`. The dev lab's outer Caddyfile (`deploy/scaleway-dev/stack/`) is mounted the same
way. The apex's Caddyfile is **baked into the apex image** (`web/apex/Dockerfile`), so it ships
with the next apex image.

**Lines written before the fix stay.** The Caddy log files age out within about a week (above), but
the relay's own lines live in Docker's `json-file` log, which is bounded by **size only** — at low
traffic, ids logged by an earlier relay build can sit there for a long time. Recreating the relay
container (`$COMPOSE up -d --force-recreate relay`) starts a fresh log and drops them.

## Known gaps

- **A capability shorter than 20 characters** in a path segment would pass the backstop. None
  exists today, and `CaddyLogFilterTest` fails if a route gains one without a specific rule.
- **Exception messages and stack traces** can still quote a path. See the ⚠ warning under the
  *Never logged* table.
- **Caddy's other runtime lines** (stderr; everything except `http.log.error`) may name a client
  address on a TLS-handshake error. Not verified, and not filtered.
- Commerce **stores** the acceptance IP with each ToS acceptance (`Acceptance.ip`). That is database
  retention, not logging, and is out of this page's scope.
