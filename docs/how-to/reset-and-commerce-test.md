# Production reset: wipe every plane and every client, then verify

The runbook for the **complete production reset** before the Play open test, and for any later wipe
of a test instance. Steps are numbered so the main session can run them in order and tick them off.
§8 onwards is reference material: why the order is what it is, and the traps the steps guard against.

> ⚠⚠ Every step here is **destructive and outward-facing**. Nothing in this document runs against
> production unless the maintainer is driving it.

**Why a reset at all.** The release cut (merged **2026-10-27**) restarted every data-format version and
`RELAY_PROTOCOL_VERSION` at 1 and squashed the relay's SQL migrations. That is only the code side. The
operational cut is this runbook: no pre-reset store, relay row, bucket object or client identity that a
relay or client reads may survive it. (⚠ Once the backup hardening is applied, pre-reset copies do survive
in the nl-ams backup buckets, ~31 days for snapshots and until emptied by hand for the mirror. Nothing
reads them, and they must never be restored: [back-up-the-relay-database.md](back-up-the-relay-database.md) D13.) The wire reshapes merged since (workstreams A–D) ride the same protocol-1 cut with **no
bump**, and that is sound only because nothing survives
([`core-schema-evolution.md`](../proposals/core-schema-evolution.md) Appendix A.2). **Play `versionCode`
6 is the first protocol-1 build to reach testers**; 5 is superseded and must never go live.

## 0. The moving parts

| Plane | Where | Reset by |
|---|---|---|
| Sync relay | `relay.tenshu.app` (Scaleway, [`deploy/scaleway-relay/`](../../deploy/scaleway-relay/)): SQLite on the block volume, blobs in object storage | [`deploy/reset-test-data.sh`](../../deploy/reset-test-data.sh) |
| Relay live + mirror buckets | `S3_BUCKET`, `BLOB_MIRROR_BUCKET` (mirror ledger under `_tenshu-mirror/`) | same script, `--empty-buckets` |
| Relay nightly snapshots | `BACKUP_S3_BUCKET` ([`deploy/scaleway-relay-backups/`](../../deploy/scaleway-relay-backups/)) | same script, `--backups-only`, **after** §6 |
| Second-region copies (once the backup hardening is applied) | nl-ams: relay + commerce snapshot buckets (Object-Locked, ~31 d), `BLOB_MIRROR_DR_BUCKET` | **not by the script.** The snapshots age out (no box key can delete them); the mirror is emptied by hand with the admin key ([back-up-the-relay-database.md](back-up-the-relay-database.md) D13) |
| Commerce issuer | `commerce.service.tenshu.app` (Scaleway, Postgres sidecar) | same script, `--commerce` |
| Commerce backups | `BACKUP_S3_BUCKET` on the commerce box ([`deploy/scaleway-commerce-backups/`](../../deploy/scaleway-commerce-backups/)) | same script, `--backups-only`, **after** §6 |
| Apex + web Drive | `tenshu.app` on Scaleway `212.47.241.176` (DO runs no compute since 2026-09-15). Static SPA, no data, but it embeds the JS SDK, so it must be a reset-era build | §2 step 5 ([`build-apex-image.sh`](../../deploy/build-apex-image.sh), [move-apex-to-scaleway](move-apex-to-scaleway.md)) |
| Dev lab | `tenshu.xyz`: relay, commerce and apex on one box ([`deploy/scaleway-dev/`](../../deploy/scaleway-dev/)) | §3a |
| Hub | the Pi: `/var/lib/tenshu-hub` (`objects.db`, `secrets.enc`, `wrap.key`, `content/`) | `scripts/reset-local-device-state.sh --hub`, §3b |
| Android | every device, all ten apps incl. Control | `scripts/reset-local-device-state.sh --android --uninstall` |
| iOS | every device, every Tenshu app (shared App Group) + the Keychain | `--ios`, then `reinstall-all-apps.sh --wipe-ios-identity` |
| macOS / Linux desktop | group container, `~/.tenshu`, Keychain wrap key, Touch ID item, LaunchAgents | `scripts/reset-local-device-state.sh --desktop --remove-apps` |
| Web clients | IndexedDB + localStorage for `tenshu.app` and `tenshu.xyz`, every browser profile | by hand, §4e |

## 1. Pre-flight checklist

Do all of these before anything is deleted. Each one has cost a reset before.

- [ ] **The reset commit is chosen and merged.** Workstreams A–D are in `master`, **including the server
      squash** (relay and commerce migrations squashed to one baseline), and so is this runbook's script. Note its short sha:
      `RESET_SHA=$(git rev-parse --short=8 origin/master)`. The script derives the schema version each
      image must produce from that commit, so a `RESET_SHA` without the server squash fails the post-check.
- [ ] **Images built from that commit, pushed and loaded.** For relay and commerce, from a clean
      checkout of `RESET_SHA`:
      `./deploy/build-relay-image.sh --push --ship relay.tenshu.app` and
      `./deploy/build-commerce-image.sh --push --ship commerce.service.tenshu.app`.
      ⚠⚠ **Do not run the cut-over the scripts print.** Starting the new image on the *old* database
      is the wrong order; `reset-test-data.sh` switches the tag itself, while the service is down.
      Apex is built in §2 step 5, because its build needs the live curtain code.
- [ ] ⚠⚠ **The hub is rebuilt from `RESET_SHA` and redeployed before any device pairs.** The hub (the
      Pi) is deployed by hand, so nothing else updates it. It signs with its own A4 key, and the HLC
      tip it signs on every `/sync/changes` and `/sync/log` call is tagged and
      length-prefixed. A hub jar built before signed messages were length-prefixed still passes the version gates, then gets
      `401 Invalid tip signature` on every sync call and rejects every new peer's tip. It is **not**
      a 426 and not a skew banner, so it looks like an auth fault. Build it with
      `./gradlew :hub:distTar` from a checkout of `RESET_SHA` and unpack it as
      [hub-pi-deploy.md](hub-pi-deploy.md) §1–§2 describe. §3b does this after the wipe. Never
      re-pair the hub (§5) with an older build still in `/opt/tenshu-hub`.
- [ ] **Each box's compose files are at `RESET_SHA`.** Compose files come from `/opt/tenshu/repo` on
      the box, not from the image, and a newer image under an older compose file has 502'd before.
      ⚠ The relay box's checkout **cannot `git fetch`**: it has no GitHub credentials. Check commerce
      the same way (`ssh root@commerce.service.tenshu.app 'git -C /opt/tenshu/repo fetch --dry-run'`).
      Where fetch fails, copy the files from your `RESET_SHA` checkout — review the diff, then write:

      ```bash
      put() {  # put <ssh-target> <repo path>: show the diff, then overwrite the box's copy (keeps a .pre-reset)
        git show "$RESET_SHA:$2" | ssh "$1" "diff -u /opt/tenshu/repo/$2 - ; cp -p /opt/tenshu/repo/$2 /opt/tenshu/repo/$2.pre-reset"
        git show "$RESET_SHA:$2" | ssh "$1" "cat > /opt/tenshu/repo/$2"
      }
      put root@relay.tenshu.app           sync-relay-server/docker-compose.yml
      put root@relay.tenshu.app           sync-relay-server/Caddyfile
      put root@commerce.service.tenshu.app commerce-service/docker-compose.yml
      put root@commerce.service.tenshu.app commerce-service/Caddyfile
      put root@212.47.241.176             web/apex/docker-compose.yml
      ```

      Then prove each renders: `ssh root@relay.tenshu.app 'cd /opt/tenshu/repo && docker compose -p
      sync-relay-server -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env config
      >/dev/null && echo OK'` (commerce: `-p commerce-service -f commerce-service/docker-compose.yml -f
      /opt/tenshu/commerce.override.yml`).
- [ ] **The new backup scripts are installed** (they are copied by hand, not deployed). Their size
      floor is now relative and reset-aware (§6), so without them every nightly backup after the reset
      fails:

      ```bash
      git show "$RESET_SHA:deploy/scaleway-relay/backup-relay-db.sh" | ssh root@relay.tenshu.app 'cat > /opt/tenshu/backup-relay-db.sh && chmod 755 /opt/tenshu/backup-relay-db.sh'
      git show "$RESET_SHA:deploy/scaleway-commerce/backup-commerce-db.sh" | ssh root@commerce.service.tenshu.app 'cat > /opt/tenshu/backup-commerce-db.sh && chmod 755 /opt/tenshu/backup-commerce-db.sh'
      ```

      ⚠ The new floor **lists** the backup bucket to find the previous upload, so the backup key now
      needs `s3:ListBucket` as well as `PutObject`/`HeadObject`. It is the project-wide Scaleway key
      today (`deploy/scaleway-*-backups/outputs.tf`, no bucket policy), so it should. Prove it on both
      boxes; a refusal here means fix the key before the reset, or every night fails:

      ```bash
      for B in relay.tenshu.app commerce.service.tenshu.app; do
        ssh "root@$B" 'set -a; . /opt/tenshu/deploy.env; set +a; python3 -' <<'PY'
      import os, boto3
      c = boto3.client("s3", endpoint_url=os.environ["BACKUP_S3_ENDPOINT"], region_name=os.environ["BACKUP_S3_REGION"],
                       aws_access_key_id=os.environ["BACKUP_S3_ACCESS_KEY"], aws_secret_access_key=os.environ["BACKUP_S3_SECRET_KEY"])
      c.list_objects_v2(Bucket=os.environ["BACKUP_S3_BUCKET"], MaxKeys=1); print("ListBucket OK")
      PY
      done
      ```
- [ ] **Box health:** `df -h /` and the data volume on relay and commerce. A wipe followed by a
      `docker load` on a full root disk fails half-way.
- [ ] **Statutory records exported, or proven absent.** The script checks commerce's `invoice` and
      `credit_note` tables, and Stripe's own invoices and credit notes when the key is live. It
      refuses until an export is named with `--statutory-exported <file>`. Production has none today,
      but the script checks that rather than assuming it. If there are records, download
      `/admin/export/ledger.csv` and `/admin/export/vat-summary.csv`, plus the Stripe dashboard's
      invoice and credit-note exports, and keep them off the boxes.
- [ ] **Commerce `deploy.env` still carries** `REVIEW_SIGNIN_ACCOUNTS`, `REVIEW_SIGNIN_HMAC_KEY`,
      `REDEMPTION_CODES` and `ISSUER_SEED`:
      `ssh root@commerce.service.tenshu.app "grep -c -E '^(REVIEW_SIGNIN_ACCOUNTS|REVIEW_SIGNIN_HMAC_KEY|REDEMPTION_CODES|ISSUER_SEED)=.+' /opt/tenshu/deploy.env"`
      should print `4`. They live in `deploy.env`, not in Postgres, so they survive the wipe. The
      review codes in the Keychain stay valid, and the review accounts re-register on their first
      sign-in.
- [ ] ⚠⚠ **From the moment the server squash merges until §2 runs: NO routine relay or commerce redeploy.** Not
      `deploy/redeploy.sh`, `redeploy-commerce.sh`, `ship-dev-stack.sh` against production, a
      `docker compose pull`/`up` or a `terraform apply` that moves an image tag. A post-squash image
      refuses any database without its `schema_baseline` marker (`refusePreSquashLadder` /
      `refusePreResetLadder`), which is every live one. It would **crash-loop production**. The only
      sanctioned start of a post-squash image on a production box is §2's, after the wipe.
- [ ] **Rollback exists.** The newest relay snapshot in `BACKUP_S3_BUCKET` and the newest commerce
      dump are from the last 24 h. **Do not** pass `--empty-backups` to the wipe; they are emptied
      only in §7.
      ⚠⚠ **A snapshot alone is not a rollback.** Pre-reset snapshots carry no `schema_baseline` marker,
      so the post-squash image refuses them. Restoring one also needs a **pre-squash image tag**. Record
      the tags running now, before anything changes:

      ```bash
      ssh root@relay.tenshu.app            "grep ^RELAY_IMAGE_TAG= /opt/tenshu/deploy.env"
      ssh root@commerce.service.tenshu.app "grep ^COMMERCE_IMAGE_TAG= /opt/tenshu/deploy.env"
      ```

      A rollback restores the snapshot **and** sets the tag back to these values. Both must still
      resolve in GHCR (`docker manifest inspect ghcr.io/b3nedikt/tenshu-relay:<tag>`).
- [ ] ⚠⚠ **No pre-cut build can still reach a tester.** A pre-cut build is incompatible with post-cut
      Control and apps **at the wire level**: the reshaped announce envelope fails to parse. So it
      shows up as a **connection failure, not the skew banner**, and nothing on the device names the
      cause. Close every route by which one could arrive:
      - **TestFlight:** in App Store Connect, **expire every existing build of every iOS app**, including
        the Mac App Store spike. An unexpired build stays installable from TestFlight.
      - **Play internal testing:** remove the releases older than the reset build, or supersede them by
        putting versionCode **6** on internal too.
      - **Play open test:** versionCode **5** must never go live. Managed publishing is on, so it waits
        for a manual publish. Never press it for 5.
      - **Sideloaded builds:** every test device must lose its debug/sideloaded APKs and desktop builds
        **older than `RESET_SHA`**. §4's `--uninstall` / `--all --uninstall` removes them. Do not skip it
        on a device that "only has a debug build". Desktop: also delete stray older `.app`/`.dmg`
        copies outside `/Applications`.
- [ ] **Every client is quiet.** Quit the desktop apps, stop the hub (`sudo systemctl stop tenshu-hub`),
      and put the phones in airplane mode or power them off. A running client between the server wipe
      and its own reset pushes into an empty relay and keeps a queue that outlives it (§8).
- [ ] **The relay's operator token is set, exactly once,** so the share-link takedown route exists from the
      first start (decided 2026-10-29: the token stays on permanently,
      [take-down-a-share-link.md](take-down-a-share-link.md) §3). The wipe keeps `deploy.env`, so this is
      a one-time edit before §2's restart; no separate restart is needed:
      `ssh root@relay.tenshu.app "grep -c '^RELAY_OPERATOR_TOKEN=' /opt/tenshu/deploy.env"` must print `1`.
      If `0`: `ssh root@relay.tenshu.app 'echo "RELAY_OPERATOR_TOKEN=$(openssl rand -hex 32)" >> /opt/tenshu/deploy.env'`
      (the token is generated on the box and never leaves it).
- [ ] **The four values for §2 are to hand:** `RESET_SHA`, the relay's `S3_BUCKET`
      (`ssh root@relay.tenshu.app "grep ^S3_BUCKET= /opt/tenshu/deploy.env"`), and both boxes' SSH
      host keys in `known_hosts`.

## 2. Server wipe: relay and commerce together

1. **Wipe both planes in one run.** The script refuses one plane alone unless `--single-plane` is
   passed. That flag is for a test box only, never for production.

   ⚠⚠ **Run it inside `tmux` (or `screen`) on your own machine.** A dropped terminal, VPN or laptop
   lid SIGHUPs it. It now traps HUP and restores what is safe, but a session that survives is better
   than a good restore. Every line is also appended to a local log, whose path the script prints
   first (`$TMPDIR/tenshu-reset-<time>-<pid>.log`, or `RESET_LOG=<file>`). Read it after a dropped
   session.

   ```bash
   ./deploy/reset-test-data.sh \
       --relay relay.tenshu.app --commerce commerce.service.tenshu.app \
       --empty-buckets --bucket <S3_BUCKET> \
       --relay-image-tag "$RESET_SHA" --commerce-image-tag "$RESET_SHA"
       # + --statutory-exported <file> only if §1 found records
   ```

   What it does, in order:
   - **Preflights.** It reads the relay's bucket config, proves read **and delete** on the live
     bucket, the mirror bucket and the mirror ledger, and checks that both images are on their boxes.
     It also checks that compose renders the tag `deploy.env` names, derives the schema version each
     image must produce from its migration list at `RESET_SHA`, and runs the statutory check.
     Nothing has been deleted at this point.
   - **Asks you to type the relay host, then the bucket.**
   - **Relay:** stops the mirror and backup timers (and any running unit), stops the relay, and
     deletes `relay.db` (+wal/shm) and `relay-blobs/`. It then empties the **live bucket, then the
     mirror** (the ledger is part of the mirror). With the relay still down it switches
     `RELAY_IMAGE_TAG` and asserts that compose will start the new tag. Only then does it start the
     relay, require `/health` to answer **200 through Caddy**, verify every table is empty, and
     require `max(schema_version)` to equal the derived version.
   - **Commerce:** stops commerce and its database, deletes `pgdata/`, switches `COMMERCE_IMAGE_TAG`
     while it is down, starts it, verifies the tables are empty, and requires the expected
     `max(schema_version)`.
   - **The relay's mirror and backup timers are re-armed only once BOTH planes verified.**
   - ⚠⚠ **A schema mismatch, a non-empty table or no `/health` 200 STOPS the service and exits 1.** A
     mismatch means the old image started on the empty volume first and wrote its own ledger, so the
     new runner skipped its migrations. If the **stop itself fails**, the script says so in capitals
     and prints the exact `… stop relay` / `… stop commerce` command. Run it at once: that service
     may be serving.
   - ⚠⚠ **Any failure means re-running the full two-plane command.** The planes are not atomic. On
     the way out the script does only what is safe for how far each plane got: not yet wiped → back
     up on the old tag; wiped but not switched → **left down** (the old image must never start on a
     wiped volume); switched → started and schema-checked again. Never finish a plane by hand — the
     re-run wipes again, so nothing is lost. The re-run gets past the statutory check even when the
     failed run left commerce's database stopped or already wiped.
   - **The one exception:** if both planes verified and only re-arming the relay's timers failed, the
     script says *"Do NOT re-run the reset"* and prints the `systemctl start …` to run instead.
2. **Mirror the image tags into the gitignored tfvars**, as the script's last lines print:
   `relay_image_tag` in `deploy/scaleway-relay/terraform.tfvars` and `commerce_image_tag` in
   `deploy/scaleway-commerce/terraform.tfvars`. Otherwise the next `terraform apply` rolls them back.
3. **Check the boot lines.**
   - Commerce: `Commerce issuer public key …` must equal the relay's `ENTITLEMENT_ISSUER_PUBKEY`.
     `ISSUER_SEED` survived, so it should.
   - Commerce: `Commerce review sign-in: ENABLED for N account(s)`.
   - Relay: `Entitlement ENFORCED on writes …` if enforcement is on.

   ```bash
   ssh root@commerce.service.tenshu.app 'cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env logs commerce | grep -E "issuer public key|review sign-in"'
   ssh root@relay.tenshu.app 'cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env logs relay | grep -E "Entitlement|schema"'
   ```
4. **Redemption codes re-seeded:** `select count(*) from redemption_code` equals the number of entries
   in `REDEMPTION_CODES`. The script prints the count.
5. **Apex** (Scaleway `212.47.241.176`). Production apex is **curtained and signup-closed**, and both
   are compiled into the bundle, not read from `apex.env` ([move-apex-to-scaleway](move-apex-to-scaleway.md)).
   The new build must carry the **same curtain code** as the live one.

   ```bash
   APEX=root@212.47.241.176
   # a. Recover the live curtain code into a variable. ⚠ Never print it.
   CONSTRUCTION_CODE="$(ssh "$APEX" "docker exec apex-apex-1 sh -c 'cat /srv/assets/*.js'" \
     | grep -oE '\{code:`[^`]+`,children:' | head -1 | sed -E 's/^\{code:`([^`]+)`.*/\1/')"
   [ -n "$CONSTRUCTION_CODE" ] && echo "code recovered (${#CONSTRUCTION_CODE} chars)" || echo "⚠⚠ NO CODE — stop"

   # b. Build from RESET_SHA, closed like production; push + load. Note the printed tag as APEX_TAG.
   CONSTRUCTION_CODE="$CONSTRUCTION_CODE" SIGNUP_DISABLED=1 ./deploy/build-apex-image.sh --push --ship "$APEX"
   ```

   ⚠⚠ **Do not run the cut-over `build-apex-image.sh` prints.** It omits `-p apex -f
   web/apex/docker-compose.yml`, so it addresses a different compose project and forks the stack.

   ```bash
   # c. Validate the NEW image's Caddyfile against the box's compose env BEFORE switching
   #    (an image newer than the box's compose env has 502'd before).
   ssh "$APEX" "cd /opt/tenshu/repo && APEX_IMAGE_TAG=$APEX_TAG docker compose -p apex -f web/apex/docker-compose.yml \
     --env-file /opt/tenshu/apex.env run --rm --no-deps apex caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile"

   # d. Switch the tag and recreate — exactly this command.
   ssh "$APEX" "sed -i 's|^APEX_IMAGE_TAG=.*|APEX_IMAGE_TAG=$APEX_TAG|' /opt/tenshu/apex.env \
     && cd /opt/tenshu/repo && docker compose -p apex -f web/apex/docker-compose.yml --env-file /opt/tenshu/apex.env \
        up -d --pull never --force-recreate apex"

   # e. Assert the PUBLIC bundle carries the same code and is still signup-closed (compare, never print).
   js="$(curl -s https://tenshu.app/ | grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' | head -1)"
   live="$(curl -s "https://tenshu.app/$js" | grep -oE '\{code:`[^`]+`,children:' | head -1 | sed -E 's/^\{code:`([^`]+)`.*/\1/')"
   [ "$live" = "$CONSTRUCTION_CODE" ] && echo "curtain code unchanged" || echo "⚠⚠ curtain code CHANGED — roll back the tag"
   curl -s "https://tenshu.app/$js" | grep -c signupClosed   # must be > 0
   ```

   Mirror it into `deploy/scaleway-apex/terraform.tfvars` (`apex_image_tag`) too.

## 3. Dev lab and hub

### 3a. Dev lab (`tenshu.xyz`)

Relay, commerce and apex share one box, `/opt/tenshu/dev.env` and the `/opt/tenshu/compose` wrapper.
The production script is not wired to it. There is no bucket: blobs live on the volume. The order is
the same: wipe while stopped, switch the tags, then the first start.

```bash
ssh root@<dev-ip> '/opt/tenshu/compose stop relay commerce apex db'
ssh root@<dev-ip> 'rm -rf /mnt/tenshu/relay/* /mnt/tenshu/postgres/*'
./deploy/ship-dev-stack.sh root@<dev-ip>      # builds THIS checkout, flips all three *_IMAGE_TAG, runs up.sh
# post-checks: the expected version is the max `version = N` in each *Database.kt at RESET_SHA
ssh root@<dev-ip> "python3 -c \"import sqlite3; print(sqlite3.connect('file:/mnt/tenshu/relay/relay.db?mode=ro', uri=True).execute('select max(version) from schema_version').fetchone()[0])\""
ssh root@<dev-ip> "/opt/tenshu/compose exec -T db psql -tAqU commerce -d commerce -c 'select max(version) from schema_version'"
```

⚠ `ship-dev-stack.sh` is the first start after the wipe. Do not `up` the stack between the `rm` and the
ship, or the old images write the old ledger, exactly as in production. The dev issuer key comes from
`dev.env` and survives.

### 3b. Hub

```bash
./scripts/reset-local-device-state.sh --hub <user>@<hub-host>   # stops tenshu-hub, empties /var/lib/tenshu-hub incl. wrap.key
./gradlew :hub:distTar                                           # from RESET_SHA — the hub is a schema peer; an old jar is fenced
```

Then [hub-pi-deploy.md](hub-pi-deploy.md) §2 (unpack over `/opt/tenshu-hub`). Re-pair it (§3) **only
after §5**, then `sudo systemctl enable --now tenshu-hub` (§4). The wipe leaves the unit stopped **and
disabled**, so a reboot in between does not start an unpaired hub.

## 4. Clients — every device, every app

⚠⚠ **Never onboard a device between its client reset and the server wipe.** It mints an identity
against the old relay that the wipe then orphans (§8). The server wipe is done by now, so wipe the
clients and then do nothing else until §5.

**4a. Android.** Attach every device, or name each one with `--serial`, then:

```bash
./scripts/reset-local-device-state.sh --android --uninstall
```

`--uninstall` is not optional for this reset. ColorOS and some other OEM skins fake `pm clear`: it
returns success and clears nothing. The scope is the catalogue's ten apps, **Control included**.
Reinstall from Play (versionCode **≥ 6**) or with `scripts/reinstall-all-apps.sh` **from a checkout at or
after `RESET_SHA`**. ⚠ A build older than `RESET_SHA` fails as a connection error, not a skew banner (§1).

**4b. iOS.** On every device:

```bash
TENSHU_IOS_DEVICE=<udid> ./scripts/reset-local-device-state.sh --ios
./scripts/reinstall-all-apps.sh --ios --wipe-ios-identity
```

- Uninstall **every** Tenshu app: the App Group container goes only when the last app of the group
  does.
- The Keychain survives an uninstall, so Control's `-wipeDeviceState` (run by `--wipe-ios-identity`)
  is required. **Erase All Content & Settings** is the guaranteed floor.
- Confirm what is left: `xcrun devicectl device info apps --device <udid> | grep -i tenshu`. §9 has
  the traps.

**4c. macOS desktop.** On every Mac:

```bash
./scripts/reset-local-device-state.sh --desktop --remove-apps
```

This removes:
- the group container (`~/Library/Group Containers/*.app.tenshu.shared`) and `~/.tenshu`;
- the Keychain wrap key (`app.tenshu.control.desktop.master-key-store`);
- the Touch ID unlock item, through the bundled helper;
- the LaunchAgents (`~/Library/LaunchAgents/app.tenshu.*.plist`, booted out first);
- the Finder (File Provider) domain;
- the apps in `/Applications`.

Then install all the reset-era desktop apps **together**: a pinned Control and an unpinned client look
for the socket in different directories. A packaged build now keeps everything — app data and the
Fit/Maps/Timeline `map-regions` staging dirs — under the group container and never reads `~/.tenshu`,
so leftovers there would be ignored rather than imported; the script deletes them anyway.

**4d. Linux desktop.** Run the same command. The root is `~/.tenshu` and there is no Keychain.

**4e. Web.** In **every** browser profile that ever opened them, clear site data for `tenshu.app`,
`tenshu.xyz` and their subdomains: IndexedDB, localStorage, service workers and cookies.
- Chrome: DevTools → Application → Storage → *Clear site data*.
- Safari: Settings → Privacy → *Manage Website Data*.
- Firefox: Settings → Privacy → *Manage Data*.

## 5. Bring-up: entitlement first, then one pairing

1. **Sign up for the entitlement first, in Control on desktop (or Android).** Production apex is built
   signup-closed, so the web is not a sign-up route, and Control-on-iOS has no sign-up surface.
   Pairing works without an entitlement, but nothing can push, so the devices never exchange their
   `Device` rows (§8).
2. **Pair once, on a quiet system:** one approver, one joiner, nothing else running.
3. **Verify on the relay**, not in a device list:

   ```bash
   ssh root@relay.tenshu.app "python3 -c 'import sqlite3
   c=sqlite3.connect(\"file:/mnt/relay_data/relay.db?mode=ro\",uri=True)
   print(\"realms\",c.execute(\"select count(*) from realm\").fetchone()[0],
         \"devices\",c.execute(\"select count(*) from device\").fetchone()[0])'"
   ```

   Expect `realms 1 devices 2`. An asymmetric device list in the apps is normal until sync flows.
4. Add the remaining devices one at a time and re-check the count after each. The hub (§3b) comes
   last.

## 6. Post-reset verification checklist

- [ ] The script ended `Server-side reset complete.`: relay tables empty, 0 blobs, both buckets
      `remaining=0`, commerce tables empty, and both `max(schema_version)` lines as expected.
- [ ] Tags mirrored into both tfvars (§2 step 2).
- [ ] The mirror and backup timers are active:
      `ssh root@relay.tenshu.app 'systemctl is-active tenshu-blob-mirror.timer tenshu-relay-backup.timer'`.
- [ ] **The first mirror run after the reset exits 0 with no breaker hold.** Force one, then read
      `/var/log/tenshu-blob-mirror.log`: `ssh root@relay.tenshu.app 'systemctl start tenshu-blob-mirror.service'`.
      A hold means a ledger survived, which is the `--empty-buckets` mirror step's job to prevent.
- [ ] **The first nightly backup after the reset uploads.** Both scripts used to refuse anything under
      an absolute floor (relay 1 MB, commerce 10 kB). Measured locally at the pre-squash schemas, an
      empty relay database is **278,528 B** (3,678 B gzipped, schema 4) and an empty commerce dump is
      **16,434 B** (2,999 B gzipped, schema 8). So the relay would have refused every night for weeks.
      The floor is now **relative**: refuse under 50 % (`BACKUP_MIN_FRACTION`) of the previous upload,
      unless `/opt/tenshu/reset-epoch` — written by the wipe — is newer than that upload.
      `integrity_check`, the `pg_restore` TOC check, a `schema_version`/`realm` sanity check (relay)
      and a `schema_version`/`account` TABLE DATA check (commerce) still apply. Force a run to see it:
      `ssh root@relay.tenshu.app 'systemctl start tenshu-relay-backup.service; tail -5 /var/log/tenshu-relay-backup.log'`
      — expect `first snapshot after the reset … the size floor does not apply`. Same for
      `tenshu-commerce-backup.service`. Requires the scripts from §1. If a later night refuses a
      shrink that is real, the two ways out are in
      [back-up-the-relay-database.md](back-up-the-relay-database.md#how-it-works) and the `/fail`
      message itself.
- [ ] Commerce: the issuer key matches the relay's, review sign-in is `ENABLED`, and the redemption
      codes are re-seeded (§2 steps 3–4).
- [ ] After §5: commerce has exactly the accounts you created
      (`select count(*) from account`), and the relay shows `realms 1` and the expected device count.
- [ ] Every app on every device opens with **no update-required / schema-skew banner**, and Control
      shows the full device list on each device.
- [ ] **Content round trip:** a file uploaded on one device opens on another, and the live bucket's
      object count is now > 0.
- [ ] A store-review account signs in with its Keychain code and re-registers.
- [ ] Hub: `journalctl -u tenshu-hub -b | grep tenshu-hub:` says `paired (owner …)`, and its
      `HubStatus` appears in Home.
- [ ] Web: `tenshu.app` signs in and shows the new realm. The dev lab does the same on `tenshu.xyz`.
- [ ] Play: the tester track is on `versionCode` ≥ 6, and 5 is not rolled out anywhere.
- [ ] The takedown route is live: `deploy/share-takedown.sh relay.tenshu.app "$(openssl rand -hex 11)" other reset-check`
      answers `not_found` (one audit row, nothing else touched).

## 7. After verification: purge the rollback, record the date

1. **Only once every box in §6 is ticked**, empty the pre-reset snapshots. They are the only rollback,
   so this step goes last.

   ```bash
   ./deploy/reset-test-data.sh --backups-only \
       --relay relay.tenshu.app --relay-backup-bucket <relay BACKUP_S3_BUCKET> \
       --commerce commerce.service.tenshu.app --commerce-backup-bucket <commerce BACKUP_S3_BUCKET>
   ```

   It wipes nothing else. Each name is an interlock against that box's `deploy.env`; it refuses a
   backup bucket that is also the live or mirror bucket; and the preflight proves delete permission
   before the prompt. ⚠ `RESET_YES=1` does **not** skip the prompt here — unattended, set
   `RESET_YES_BACKUPS=<relay bucket>,<commerce bucket>`. ⚠ A backup key may be write-only by design.
   If the preflight is refused, empty the bucket with the credentials of its own Terraform module
   (`deploy/scaleway-*-backups/`) rather than widening the box's key.
2. **Record the date.** Write it into
   [`core-schema-evolution.md`](../proposals/core-schema-evolution.md) Appendix A.2, which says "date
   TBD", and into the banner of [`pre-release-checklist.md`](../proposals/pre-release-checklist.md) §4.
   From that date the "reshapes ride protocol 1 with no bump" exemption is over, and HR #9 applies in
   full.

---

## 8. Why this order — a half reset is worse than none (2026-08-17)

⚠⚠ **A client you do not wipe carries instructions that outlive the server you did.** The pending
queues are client-side, so a server wipe does not empty them.

This was learned the expensive way. A *"retire this device"* tombstone was written during a
half-finished morning reset. It sat unsendable in a queue (relay wiped → `401`, commerce wiped →
`402`) and survived a re-pairing that evening. It then executed the moment an entitlement made pushing
possible, silently un-pairing the device that had just been paired. The iOS **Keychain identity
survived** the morning's reset, and that is what let a stale instruction name a live device. See
`docs/tech-debt.md` §"A queued Device tombstone can revoke a device that has since been re-admitted".

- **Both servers, together.**
  - Relay-only ⇒ every device `401`s, while its entitlements still exist.
  - Commerce-only ⇒ registered devices `402` on every push, and accounts stay bound to an ownerId that
    no longer exists.
- **Every client, including the iOS Keychain.** A device that keeps its identity keeps its **queues**.
- **Entitlement before pairing.** The mailbox routes are not entitlement-gated, so pairing *looks*
  fine. But nothing can push, so the two devices never exchange `Device` rows, and each one shows a
  device list missing the other.
- **The relay's `device` table is the authority**, not a device list. The approving device *authors*
  the new device's row locally, while the reverse direction has to sync.
- **Switch the image before the restart that follows a wipe.** Each server's runner applies only
  migrations above `max(schema_version)`. The old image on an empty volume writes its own ledger, and
  the new image then creates nothing. The service starts and answers `/health`, then fails on the
  first query against a table that was never made. Hence `--*-image-tag` and the post-check.
- Device-level gotchas that cost time on every run:
  - The iPhone must be **unlocked** for install and launch.
  - Control-on-iOS comes up **locked** after a reinstall. A sealed realm can neither run a pairing
    session nor repair its own registration, so unlock it before concluding anything is broken.

## 9. Client gotchas

- ⚠ **`pm clear` is blocked on ColorOS** (and some other OEM skins): it returns success and clears
  nothing. The script **verifies** each clear through `run-as` and tells you to rerun with
  `--uninstall` (`timeline-movement-capture.md` §11).
- ⚠ **iOS, more than one Apple device attached:** the reset refuses to guess and skips the iOS plane,
  but the run still exits 0. Name the device with `TENSHU_IOS_DEVICE=<udid>`.

### iOS is two halves, and uninstalling is only one of them

⚠⚠ On iOS an uninstall is **not** a wipe. Deleting every Tenshu app drops the App Group container,
and with it the shared SQLite replica (deleting only some keeps it), but the Keychain items survive. Control then comes back bound to the **old device key** and gets 401s from the
wiped relay.

`reinstall-all-apps.sh --ios --wipe-ios-identity` handles this:
- It runs Control's `-wipeDeviceState` after reinstalling. The route needs the app installed, which
  is why the reset script cannot do it.
- It then relaunches without the flag, because a launch with it parks on "Device state wiped" for the
  life of the process.

⚠⚠ **It prints `identity wiped and Control relaunched clean` unconditionally.** A failed launch shows
on its own `FAIL:` line above that message. Confirm it, or redo it by hand (⚠ the `--` is required):

```bash
xcrun devicectl device process launch --terminate-existing --device <udid> app.tenshu.control.ios -- -wipeDeviceState
xcrun devicectl device process launch --terminate-existing --device <udid> app.tenshu.control.ios
```

**Trust record.** Removing every app signed by your developer identity **can** drop the device's trust
record. The apps then refuse to launch (*"…profile has not been explicitly trusted"*).
- Fix: re-Trust under Settings → General → VPN & Device Management.
- It is not certain: on 2026-08-25 a nine-bundle removal dropped nothing.

**`ios-smoke.sh`'s "developer profile not trusted / expired"** is a guess across three causes, and it
has been wrong both ways. Re-run the launch yourself:
`xcrun devicectl device process launch --terminate-existing --device <udid> <bundle>`.

**The three-app limit is PROVISIONING, not device capacity.** With no Apple ID signed into Xcode,
`-allowProvisioningUpdates` cannot mint a profile for a bundle id that has none. Signing into Xcode
unblocks the rest.

**`devicectl device uninstall app` succeeds for a bundle that was never installed.** Ask the device:
`xcrun devicectl device info apps --device <udid> | grep -i tenshu`.

## 10. Script reference

**`deploy/reset-test-data.sh`**
- `--help` prints the whole contract.
- Refusals worth knowing:
  - **Object storage without `--empty-buckets --bucket <name>`.** Wiping only the volume would strand
    every ciphertext and still report a clean reset.
  - **One plane without `--single-plane`.**
  - **No `--*-image-tag`.**
  - **A tag that is not a commit in this checkout** without `--expect-*-schema <n>`.
  - **Statutory records** without `--statutory-exported`.
- Every remote body ends in a sentinel and is `bash -n`-checked before it is sent, so a quoting slip
  fails loudly instead of truncating a wipe.
- Compose projects are explicit (`-p sync-relay-server`, `-p commerce-service`), and the preflight
  refuses when the running service is not in that project — a different project is a second stack.
- The expected schema comes from `deploy/lib/migration-max-version.sh`, pinned against the real
  runners by `RelayMigrationVersionDerivationTest` / `CommerceMigrationVersionDerivationTest`.
- Each wipe is **verified**: a wipe that silently did not happen reads exactly like one that did.
- What looks like a failed commerce wipe but is not:
  - `schema_version` is the migration ledger.
  - `redemption_code` is re-seeded from `REDEMPTION_CODES`.
- `ISSUER_SEED` lives in `deploy.env`, so the issuer keypair survives, and the relay's pinned
  `ENTITLEMENT_ISSUER_PUBKEY` stays valid.
- Managed-DB commerce (`--commerce-managed`): run `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`
  by hand. The statutory check does not run, so do it by hand too.
- ⚠ SSH: a box known only by IP dies with `Host key verification failed`. Verify the key and add the
  hostname, or pass `SSH_OPTS="-o HostKeyAlias=<ip>"`.

**`scripts/reset-local-device-state.sh`.** Planes `--desktop`, `--android`, `--ios`, `--all` and
`--hub <ssh-target>`. Both client scripts read [`scripts/lib/app-catalogue.sh`](../../scripts/lib/app-catalogue.sh):
add a first-party app there and both pick it up.
- An app without an iOS module carries `-` in the iOS columns, and callers ask `app_has_ios` first.
- ⚠ `-` is not inert: `grep -qF -` matches every line of a `devicectl` listing.

**Redeploying a plane.** Each plane has a `build-*-image.sh` that builds natively and assembles an
amd64 image from COPY steps alone.
- ⚠⚠ On an arm Mac this is the only path.
- ⚠ Loading an image is inert. The cut-over is a `*_IMAGE_TAG` flip plus `--force-recreate`, which is
  what the reset script now does for you.

---

## Appendix — commerce issuer and entitlement-enforcement test

The as-run issuer procedure is [move-commerce-to-scaleway.md](move-commerce-to-scaleway.md).
⚠⚠ Never `apply` `deploy/digitalocean-commerce`: it is retired.

For a closed test, use `dev_mode = "true"` and a `redemption_codes` seed.

Gotchas:
- **cloud-init is ASCII-only.** A non-ASCII character makes cloud-init drop *all* user-data,
  silently.
- **A private repo needs `git_token`** in the tfvars.
- **`manage_dns = true` collides** with the existing zone.

**Wire the relay to the issuer**:

```dotenv
ENTITLEMENT_ISSUER_PUBKEY=<base64 from the commerce boot log>
ENTITLEMENT_ENFORCE=false   # keep OFF until a client holds an entitlement (staged rollout)
```

**Mint an entitlement.** The relay checks a session-gated bearer token (`X-Entitlement`), so the
client needs a commerce **account**.
- Desktop dev: [`scripts/run-control-desktop-commerce.sh`](../../scripts/run-control-desktop-commerce.sh).
- Installed app: run
  `launchctl setenv TENSHU_BILLING_MOCK false; launchctl setenv TENSHU_COMMERCE_URL https://commerce.service.tenshu.app`.

On a dev deploy the OTP is only logged, as `***@<domain>#<8 hex>`. Pick yours with
`printf %s '<address>' | shasum -a 256 | cut -c1-8`.

**Flip enforcement and verify.** Set `ENTITLEMENT_ENFORCE=true` and recreate the relay. Watch the
right endpoint:
- `POST /sync/push` is the gated write: `200` with a token, `402 entitlement_required` without.
- `POST /sync/changes` is ungated, so it stays `200` either way.

An un-accounted device is held to **Local-only** by the client-side gate. The `402` is the relay-side
backstop. See `docs/tech-debt.md` → *"Entitlement Enforcement — Client Sends Doomed Writes on
Record-vs-Token Divergence"*.
