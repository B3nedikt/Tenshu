# Moving apex to Scaleway

The apex box is stateless, so this is a cutover, not a data migration — which makes it tempting to
treat as a DNS flip. It is not, and the reason is the whole point of this file.

⚠⚠ **Apex's behaviour is compiled into the image, and the deploy config on the box does not
describe it.** `VITE_TENSHU_RELAY_URL`, `VITE_CONSTRUCTION_CODE` and `VITE_SIGNUP_DISABLED` are
inlined by Vite at build time. They are read from `apex.env` **only on the on-box build path**; when
`APEX_IMAGE_TAG` is set — which is how both boxes run — a prebuilt image is pulled and those lines do
nothing at all.

Measured 2026-09-14, both boxes running prebuilt images:

| | `apex.env` says | what the served bundle contains |
|---|---|---|
| production (DO, `46f632d2-closed`) | `VITE_CONSTRUCTION_CODE=191919` | curtain code **`171716`** |
| staged (Scaleway, `1d21f3a2`) | `VITE_CONSTRUCTION_CODE=` *(empty)* | curtain code **`191919`** |
| production | *(no signup var)* | `signupClosed` ×5 — **signup closed** |
| staged | *(no signup var)* | `signupClosed` ×0 — **signup open** |

⚠⚠ **Production's `apex.env` names a code its image does not carry.** Both boxes are curtained; the
staged one is curtained with an *older* code. So cutting `tenshu.app` over as it stands would revert
the curtain code — locking out everyone holding the current one — **and** re-open public signup.
Neither is visible in any config file, and neither is caught by `/health`, an HTTP status or a page
diff (both shells are byte-identical at 2581 bytes).

⚠ This inverted once already during this investigation: searching the bundles for the value in
`apex.env` (`191919`) found it absent from production and concluded the site was open. It was not —
production carries a different code. **Extract the code from the bundle; never grep for the value you
expect.**

---

## The check that actually works

Compare the **served bundle**, not the box, not `apex.env`, not the image tag alone.

```bash
# 1. which bundle is each site serving?
for H in tenshu.app apex-scw.service.tenshu.app; do
  curl -s "https://$H/" | grep -oE 'assets/index-[A-Za-z0-9_-]+\.js'
done

# 2. pull both and compare the gates that have no other tell
curl -s -o prod.js "https://tenshu.app/assets/index-<hash>.js"
curl -s -o scw.js  "https://apex-scw.service.tenshu.app/assets/index-<hash>.js"
# BOTH bundles, every time. Checking only prod.js reconfirms production and says nothing
# about the box you are about to cut over to.
for f in prod.js scw.js; do
  echo "$f: signupClosed x$(grep -o 'signupClosed' "$f" | wc -l)  code=$(grep -oE 'code:`[0-9]{6}`' "$f" | head -1)"
done
```

The two lines must match before you proceed. `signupClosed ×0` on the staged bundle means signup is
**open** there; a differing `code:` means the curtain code changes under your users.

⚠⚠ Extract the code, do not search for one. Vite emits it as a **backtick template literal**
(``code:`171716` ``), so `grep '"[0-9]{6}"'` matches nothing at all even when a code is present — and
a bare `[0-9]{6}` is swamped by noise (`214748` from `2147483647`, and so on). The `code:` anchor is
what makes it reliable.

⚠ Do not check for the component (`ConstructionGate`) — minification renames it, and it is compiled
into **both** builds regardless. What differs is whether a code literal is inlined. String literals
survive minification; identifiers do not.

## Doing the cutover

Rehearsed against the staged box on 2026-09-14, up to — not including — the DNS change. Everything
below that step has been run; the flip itself has not.

⭐ **The approach that removes the TLS gap entirely:** serve *both* names from the new box, with the
production certificate pre-seeded, before DNS moves. Then the flip changes where traffic goes and
nothing else — no ACME order races the propagation, and the staging hostname keeps working for
verification throughout. It supersedes an earlier version of this runbook that changed the domain as
a separate later step and expected ACME to fail in between.

### 1. Match the image — and do not assume the registry will give it to you

Set `APEX_IMAGE_TAG` to the **tag** production runs — `46f632d2-closed` on 2026-09-14:

```bash
ssh root@<do-apex> "docker inspect apex-apex-1 --format '{{.Config.Image}}'" | sed 's/.*://'
```

⚠ `.Config.Image` is the full reference (`ghcr.io/b3nedikt/tenshu-apex:46f632d2-closed`), and compose
prepends the repository itself (`ghcr.io/b3nedikt/tenshu-apex:${APEX_IMAGE_TAG}`) — pasting it whole
yields a doubly prefixed reference that cannot start. The `sed` keeps only what follows the last `:`.

⚠⚠ **Edit `/opt/tenshu/apex.env`, not `/opt/tenshu/repo/web/apex/apex.env`.** The repo path is a
**symlink** to the former, and `sed -i` on a symlink replaces the link with a regular file — after
which compose reads a copy nobody else edits.

⚠⚠ **A `docker pull` of that tag from the staged box is `denied`, even though the box has a
`ghcr.io` credential stored.** So re-running `docker login` is not the fix; the stored token is expired
or lacks access to the package. The `-closed` image *is* in GHCR — this box just cannot fetch it. The
route that works is copying it from the box that already has it:

```bash
ssh root@<do-apex> 'docker save ghcr.io/b3nedikt/tenshu-apex:<tag> | gzip' \
  | ssh root@<scw-apex> 'gunzip | docker load'
# confirm the image ID matches on both boxes, then recreate WITHOUT a pull
ssh root@<scw-apex> 'cd /opt/tenshu/repo/web/apex && docker compose --env-file apex.env up -d --pull never'
```

### 2. Pre-seed the production certificate

⚠ Confirm first that `/data` is a **named volume** (`apex_caddy_data`) — a seed written into a
container layer is lost on the next recreate.

Copy the certificate **box to box through a pipe**, so the private key never lands on a workstation.
⚠⚠ Caddy only finds it at its **exact storage path**. Copied "into `/data`" as a directory, it lands at
`/data/tenshu.app/`, Caddy never sees it, and it orders a new certificate it cannot get. `tar -C /data`
with the relative path keeps the layout, the `0600` modes and root ownership (the image has `tar`):

```bash
C=caddy/certificates/acme-v02.api.letsencrypt.org-directory/tenshu.app
ssh root@<do-apex> "docker exec apex-apex-1 tar -C /data -cf - $C" \
  | ssh root@<scw-apex> 'docker exec -i apex-apex-1 tar -C /data -xf -'
# identical on both ends (hashes only — nothing secret is printed)
for h in <do-apex> <scw-apex>; do
  ssh root@$h "docker exec apex-apex-1 sh -c 'cd /data/$C && sha256sum tenshu.app.crt tenshu.app.json tenshu.app.key'"
done
```

⚠ **The deadline is the certificate's expiry, not its renewal date.** On 2026-09-14 the cert runs to
**2026-11-25**. Caddy starts renewing about 30 days ahead, at **2026-10-25 22:16 UTC**, over HTTP-01,
and that **cannot succeed on the new box while DNS points at DigitalOcean**. A failed renewal is not an
outage, though: Caddy keeps serving the current certificate and retries with backoff, so the first
retry after the flip succeeds. From 2026-10-25, renewal errors in the new box's log are expected noise,
not a gate.

⚠⚠ What *is* a gate is expiry. Flip with **days to spare** — say by 2026-11-15 — so renewal has
retries left before 2026-11-25. After that date, a box still holding the seeded certificate serves an
expired one to a `.app` name, and HSTS preload makes that a hard failure.

### 3. Serve both names

```
APEX_DOMAIN="tenshu.app, apex-scw.service.tenshu.app"
```

`web/apex/Caddyfile` has a single site block, `{$APEX_DOMAIN} {`, and Caddy substitutes the variable
before parsing, so a comma list becomes two site addresses. ⚠ **Prove that before applying it** rather
than trusting this sentence: run `caddy adapt` inside the container with the new value and check both
hosts appear. (It did on 2026-09-14: `['tenshu.app', 'apex-scw.service.tenshu.app']`.) Recreate.

### 4. Prove it from outside — without touching DNS

```bash
# valid TLS for the PRODUCTION name, from the NEW box — no -k
curl -sS -o /dev/null -w 'http=%{http_code} verify=%{ssl_verify_result}\n' \
  --resolve tenshu.app:443:<scw-apex-ip> https://tenshu.app/            # expect http=200 verify=0

# and it is the seeded cert, not a fresh order: serials must match
for h in <do-apex-ip> <scw-apex-ip>; do
  echo | openssl s_client -connect $h:443 -servername tenshu.app 2>/dev/null | openssl x509 -noout -serial
done
```

Then run the bundle check above against the bundle fetched through `--resolve`.

⭐ **The serial match is the proof the seed was picked up.** Caddy's log can corroborate it, but only by
message, because certmagic names the two cases differently:

```bash
ssh root@<scw-apex> "docker logs apex-apex-1 2>&1 | grep -E '\"(obtaining|renewing) certificate\"' | grep tenshu.app"
```

| message for `tenshu.app` | means |
|---|---|
| `obtaining certificate` | ⚠⚠ the seed was **not** found, on any date. It will fail while DNS is at DO — stop and re-seed |
| `renewing certificate` | the seed **was** loaded and is due for renewal. Expected from 2026-10-25, fails until the flip, **not a blocker** |

Port 80 must reach Caddy too (a `308` to https), or the HTTP-01 renewal cannot work after the flip.

### 5. The flip

A few hours ahead, lower the TTL (same target, so nothing moves yet):

```bash
doctl compute domain records list tenshu.app --format ID,Type,Name,Data,TTL   # find the @ A record
doctl compute domain records update tenshu.app --record-id <id> --record-type A \
  --record-name @ --record-data <do-apex-ip> --record-ttl 60
```

Immediately before, **re-check parity** — production may have redeployed apex since the rehearsal:
image tags equal on both boxes, certificate serials equal, bundle gates equal. Then:

```bash
doctl compute domain records update tenshu.app --record-id <id> --record-type A \
  --record-name @ --record-data <scw-apex-ip> --record-ttl 60
dig +short @ns1.digitalocean.com tenshu.app                    # authoritative: the new IP
```

⚠ `deploy/scaleway-apex` declares a `scaleway_domain_record` gated behind `manage_dns` (default
`false`), and it must stay off: Scaleway does not serve this zone, so enabling it creates a record
nothing resolves.

### 6. After

- From outside: `https://tenshu.app/` returns 200 with `verify=0`, bundle gate passes, a real sign-in
  works.
- The DO box drains: `ss -Htn state established '( sport = :443 )' | wc -l` falls toward 0. Keep it
  serving **at least 24 h** — JVM and Android DNS caches outlive the TTL.
- Then restore the TTL to 300, drop `apex-scw.service.tenshu.app` from `APEX_DOMAIN`, and retire the
  DO box. Its own renewals start failing once DNS has moved. That is harmless on a box with no traffic,
  but it limits how long rollback stays possible (below).

**Rollback** is the DNS record alone, back to the DO IP: the old box was never modified and serves the
same certificate. ⚠ That certificate cannot renew there after the flip, so rollback only works until
**2026-11-25**.

## What has no equivalent here

The relay move needed a window because it carried a database. Apex carries no **application** data:
nothing on the box has to be copied for correctness, so there is no window and no snapshot.

⚠ It does carry one piece of state: Caddy's certificate in `apex_caddy_data`. That is what step 2
seeds, and it is what puts a date on the move. The flip should land before **2026-11-25**, and rollback
to the DO box stops working on that date too, because the DO box's copy cannot renew once DNS has
moved. Beyond that date, the only irreversible thing is user-visible behaviour if you flip with the
wrong bundle, which is what the check above exists to prevent.
