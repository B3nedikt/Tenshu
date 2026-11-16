# Moving production from Scaleway to OVHcloud

Moves relay, commerce and apex, plus the relay's buckets, from Scaleway to OVHcloud Public Cloud,
**by copying the running boxes**, then moves DNS on Cloudflare. A few minutes of downtime are
accepted, which keeps the data move simple: stop the old services, copy, start the new ones.

Code: `deploy/ovh-project` (SSH key, buckets, S3 keys), `deploy/ovh-{relay,commerce,apex}` (one idle
host each, via the child module `deploy/ovh-host`), `deploy/ovh-migrate` (the copy, numbered).

## Why, and the deadline

OVH's free trial is **$200 of credit, valid 90 days** from when it is applied. On the defaults here
(two `d2-2`, one `d2-4`, 30 GB of volumes, ~0.1 GB of objects) that runs about €30–35/month, so
the credit covers the 90 days. ⚠⚠ **After day 90 OVH costs more than Scaleway for this footprint**
(`hosting-provider-economics.md` D3: OVH sells unmetered egress and sovereignty, not low compute
prices). Decide before then whether to stay, move back, or move on. Moving back is this runbook
with the roles swapped; the scripts work in either direction once the old side is described in
`hosts.env`.

## What moves, and what stays

| | today | after |
|---|---|---|
| relay box + `/mnt/relay_data` (SQLite) | Scaleway `51.15.143.24` | OVH `tenshu-relay` |
| commerce box + Postgres (`/mnt/commerce_data`) | Scaleway `51.15.229.94` | OVH `tenshu-commerce` |
| apex box (static apps) | Scaleway `212.47.241.176` | OVH `tenshu-apex` |
| content bucket `tenshu-relay-blobs` | Scaleway fr-par | OVH `<prefix>-relay-blobs` (copied) |
| blob mirror, relay + commerce backup buckets | Scaleway fr-par | OVH, **new and empty** (the jobs refill them) |
| OTP mail | Scaleway TEM | **stays** (SMTP from the new box; 1-prepare checks it connects) |
| DNS, tiles, model catalog | Cloudflare | unchanged |
| dev lab (`deploy/scaleway-dev`) | Scaleway | not part of this move |

## What a copy carries, and why it is a copy

The boxes are hand-maintained: `deploy.env` and `apex.env` hold settings no Terraform module
writes (S3 keys, the issuer seed, review sign-in, Stripe, operator token), and the repo checkouts
carry local compose edits from past switch scripts. Rendering a fresh box from Terraform would
carry a guess. So the OVH hosts boot **idle** (Docker, volume mounted, nothing else), and
`deploy/ovh-migrate` copies onto them, from the running box:

- the **exact images** by `docker save | docker load` (same image id, and the boxes cannot pull
  the private commerce image from GHCR anyway);
- `/opt/tenshu` whole: repo checkout, env files, backup, mirror and switch scripts;
- the `tenshu-*` systemd units, **left disabled** until the end;
- Caddy's volumes, **certificates included**, so OVH serves valid TLS from the first request after
  the switch, and can be tested over HTTPS by IP before it;
- in the window: `relay.db` (checkpointed) and Postgres's `pgdata`.

Only the storage settings change: `deploy/ovh-project`'s `relay_env_overrides` /
`commerce_env_overrides` replace the `S3_*`, `BACKUP_S3_*`, `BLOB_MIRROR_BUCKET` and `MIRROR_S3_*`
lines. The untouched Scaleway copy stays beside it as `deploy.env.scaleway`.

---

## 0 — Credentials (once)

OVH API keys for Terraform, as environment variables (`deploy/ovh-project/README.md`):
`OVH_ENDPOINT=ovh-eu`, `OVH_APPLICATION_KEY`, `OVH_APPLICATION_SECRET`, `OVH_CONSUMER_KEY`.

## 1 — Stand the OVH side up (days before; production untouched)

```bash
cd deploy/ovh-project  && cp terraform.tfvars.example terraform.tfvars  # fill in
terraform init && terraform apply
for m in ovh-relay ovh-commerce ovh-apex; do
  (cd ../$m && cp terraform.tfvars.example terraform.tfvars && terraform init && terraform apply)
done
```

Then, per box, run what `terraform output verify_hint` prints. `cloud-init status` must say `done`,
`/opt/tenshu-host/READY` must exist, and for relay and commerce `findmnt` must show the data volume.
⚠ The bootstrap waits up to 5 minutes for the data volume and fails if it never appears, rather than
quietly writing production data to the root disk.

Check the S3 keys are really least-privilege before relying on them (the policy is what limits
them; without it an `objectstore_operator` user reaches every bucket):

```bash
# with the relay-backup key: a write to its own bucket works, a delete and another bucket do not
aws --endpoint-url https://s3.gra.io.cloud.ovh.net s3 cp /etc/hostname s3://<prefix>-relay-db-backups/probe   # ok
aws --endpoint-url https://s3.gra.io.cloud.ovh.net s3 rm s3://<prefix>-relay-db-backups/probe                 # AccessDenied
aws --endpoint-url https://s3.gra.io.cloud.ovh.net s3 ls s3://<prefix>-relay-blobs                            # AccessDenied
```

## 2 — Stage the copy (any time before the window; re-runnable)

```bash
cd deploy/ovh-migrate && cp hosts.env.example hosts.env    # NEW_* = each module's `terraform output ipv4`
./1-prepare.sh          # images, /opt/tenshu, units, Caddy volumes, env overrides; checks
./2-copy-blobs.sh       # content bucket, size-compared; the old relay keeps running
```

`1-prepare.sh` also runs the two checks only the new boxes can answer: the relay's **bucket
preflight** (a real 2 MiB write and read-back, which catches a store that returns `aws-chunked`
framing as data) and an SMTP connect from the new commerce box to Scaleway TEM.

## 3 — The window

```bash
./3-cutover.sh      # stop relay + commerce on Scaleway, copy both databases, start OVH, verify by IP
./4-switch-dns.sh   # rewrite deploy/dns/records.json, plan, confirm, apply (TTL 300 s)
./5-finish.sh       # wait for resolvers, public checks, move the timers, first OVH backups
```

What `3-cutover.sh` does, in order: last backups to Scaleway (the rollback point) · stop the relay
and commerce containers (Caddy stays up, so a client gets a 502, not a wrong answer) · checkpoint
and copy `relay.db`, then `quick_check` and row counts that must match · copy `pgdata`, start
Postgres on OVH, row counts that must match · refresh `/opt/tenshu` in case anything changed since
step 2 · the final bucket pass with `--verify-all` · start all three stacks on OVH · check
`running restarts=0` and HTTPS by IP (`curl --resolve`). It stops at the first failure. Before
`4-switch-dns.sh`, `./rollback.sh` loses nothing.

Expected downtime: the cutover (a few minutes at today's sizes: 14 MB database, 8 MB Postgres,
114 MB bucket) plus up to the 300 s TTL.

Then by hand: edit on one device and watch it arrive on another; upload a file and watch the chunk
`PUT`s and `POST /sync/content/declare` on the OVH relay; sign in once (an OTP through TEM from
the new box); commit `deploy/dns/records.json`; start the blob mirror once
(`systemctl start tenshu-blob-mirror.service`).

## Rolling back

`./rollback.sh` stops OVH, starts Scaleway, hands the timers back, and moves DNS back if it had
moved. ⚠⚠ After the DNS switch this **loses every write made on OVH since**: two relay logs cannot
be merged (sync blocks are ordered by a relay-assigned sequence). So the DNS switch is the one-way
point, and the checks in `3-cutover.sh` are there to make it a safe one.

## Teardown (after a quiet week on OVH)

Only once OVH has served normal traffic for several days and its nightly backups have landed:
`terraform destroy` in `deploy/scaleway-relay`, `-commerce`, `-apex`, then the buckets
(`scaleway-relay-blobs`, `-relay-backups`, `-commerce-backups`, `scaleway-backup-access`). ⚠ Keep
the Scaleway project and its **TEM** domain: OTP mail still goes through it. ⚠ Destroying the
relay box destroys its block volume; it is the last copy of the pre-move database.

---

## As run, 2026-11-12

Scaleway → OVH, GRA11. Relay `91.134.52.26`, commerce `141.94.175.130`, apex `51.255.22.186`.
Writers stopped 14:32:03Z, DNS applied 14:44:04Z, public resolvers on OVH by ~14:45Z: **about 13
minutes of relay/commerce downtime**, ten of them lost to the first trap below. Row counts matched
on both sides (relay 2768 sync blocks, commerce 3 accounts), `--verify-all` passed on 353 objects,
and a client push landed on OVH within a minute of the switch. Cross-device edit and an OTP sign-in
(mail via TEM from the OVH box) checked by hand the same afternoon.

Scaleway after the move (2026-11-12 ~15:15Z): **apex destroyed** (`deploy/scaleway-apex`, state empty;
it held no data). **Relay and commerce powered off** through the API `poweroff` action, not
destroyed: their block volumes keep the last pre-move `relay.db` and `pgdata`. `poweron` brings
them back; the Terraform modules have no `state` knob. The Scaleway buckets (content, backups) stay
for the quiet week, the project and TEM for good. Then: Teardown below.

What the run found, all fixed in the scripts since:

- **The env file's image tag is not what runs.** `redeploy.sh` exports the tag and never writes it,
  so the copied `deploy.env` named relay `36f1908d` while production ran `290b7b9e`. `up --pull
  never` found no such image and **started building the relay from source** on the new box, inside
  the window. `pin_running_tag` (lib.sh) now writes the tag read off the old container, in both
  `1-prepare.sh` and `3-cutover.sh`.
- **`rsh` lost argument boundaries.** ssh joins its arguments into one string, so a multi-word one
  (volume labels, compose flags) arrived split. It now `%q`-quotes each.
- **The blob mirror needs its own key on OVH.** The box's `mirror-blobs.py` predates `MIRROR_S3_*`
  and only reads `S3_ACCESS_KEY` — the relay's content key, which OVH's policy keeps out of the
  mirror bucket (AccessDenied). Fixed **on the box only**: `/opt/tenshu/mirror.env` maps the mirror
  key onto `S3_*`, loaded by the drop-in
  `/etc/systemd/system/tenshu-blob-mirror.service.d/ovh-mirror-key.conf`. Upgrading the box to the
  repo's script (which reads `MIRROR_S3_*` itself) makes both redundant.
- Terraform: apply the three hosts **one at a time** (parallel creates race on the SSH key pair,
  409), and an empty `volume_ids` must be `null` (provider 2.24 reads `[]` back as null and taints).

## ⚠ What will bite

- **On OVH, changing `user_data` or `image_id` reinstalls the instance and wipes its root disk**,
  where `/opt/tenshu` lives. The ids survive, so the plan shows an in-place update, not a
  replacement. `deploy/ovh-host` ignores both after creation; to really rebuild, use
  `terraform apply -replace=module.host.ovh_cloud_instance.this` and re-run `1-prepare.sh`.
- **The content bucket is not write-once.** File chunks are `blob-<sha256(ciphertext)>`, but
  relation snapshots are named after a hash of their *address* (`RelationSliceSnapshot.ref`) and
  overwritten in place, sometimes at the same size. A size-only delta misses those, which is why the
  in-window pass is `--verify-all` (byte comparison). Measured on 2026-11-10: 120 of 269 objects do
  not hash to their name.
- **`--numeric-owner` on every copy.** `pgdata` belongs to uid 999 (postgres in the container),
  which is `fwupd-refresh` on the Scaleway host and something else, or nothing, on OVH. By name,
  tar would remap it and Postgres would refuse its data directory.
- **The host firewall is the one that counts.** Every box runs nftables (`table inet tenshu_host`,
  from cloud-init): SSH from `ssh_allowed_cidrs`, 80/443 from anywhere, and a FORWARD rule that
  drops any Docker-published port other than 80/443, which a plain `ufw` would let through. Verified
  from outside on 2026-11-12 over IPv4 with a test container publishing 80, 8081 and 5432: only 80
  answered. ⚠ IPv6 is configured on the boxes but was **not** tested from outside (no IPv6 route
  here); the rules are `inet`, so they cover it, and DNS publishes no AAAA.
  ⚠⚠ The OVH security group is **off** (`security_group = false` in the three tfvars): a new
  project refuses every security-group call while outgoing port 25 is restricted
  (`SMTPPort25Restricted`, ticket opened 2026-11-12). Without it the instance sits in the project's
  `default` group, which lets everything in, so the host firewall is the only filter. Turning the
  group on later may reinstall the box (root disk wiped): do it before the cutover, then re-run
  `1-prepare.sh`.
- **No floating IP.** The OVH address survives reboots and reinstalls, but a *replaced* instance gets
  a new one, and `records.json` must follow. (OVH floating IPs need a private network and a gateway,
  which is more than three single boxes justify.)
- **Root login is enabled on purpose** (`disable_root: false`): every script and runbook reaches the
  boxes as `root@`, and OVH's Ubuntu images refuse it by default.
- **Timers are enabled on one side at a time.** Both boxes carry the same `healthchecks.env`, and two
  boxes pinging one check hides a failure. `5-finish.sh` disables Scaleway's before enabling OVH's.
- **`MIRROR_STORAGE_CLASS=STANDARD`.** The mirror script defaults to `ONEZONE_IA`, a Scaleway class.
- **Backup retention is Terraform here**, not `set-lifecycle.py` (Scaleway's provider could not
  express it; OVH's can): `backup_retention_days` (30) in `deploy/ovh-project`. ⚠ The privacy notice
  promises that 30, so the rule must exist; check with `terraform state list | grep lifecycle`.
  The content bucket and the mirror deliberately have none.

## Not covered

- A from-scratch deploy without a source box. The OVH hosts start idle by design; a fresh stack
  needs the env files written by hand, as the Scaleway modules' cloud-init used to do.
- Second-region copies (the Scaleway modules' `nl-ams` DR buckets). Production's boxes are not
  configured to write them today (`BACKUP_DR_*` unset), so nothing is lost by not recreating them.
