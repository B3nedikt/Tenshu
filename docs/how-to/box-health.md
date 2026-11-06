# Box health — disk, backups, and a dead man's switch

What watches the three production boxes (commerce, apex, relay), and how to set it up on a running
one. The scripts are in [`deploy/box-health/`](../../deploy/box-health); the backups themselves are
[`back-up-the-commerce-database.md`](back-up-the-commerce-database.md) and
[`back-up-the-relay-database.md`](back-up-the-relay-database.md).

## Why: 23 days of silent backup failure (October 2026)

Found 2026-10-23: the commerce box's **8.9 GB root disk had been 100% full since early October**.
cloud-init had created a fixed **4 GB `/swapfile`** on it — sized for the old on-box Gradle image
build, which no longer happens because images are prebuilt. The nightly database backup writes its dump
to `/var/tmp` first, so it failed every night, **for 23 days, and nothing said so**: its failures went
to a log file on the full disk that nobody reads.

The live boxes were fixed by hand the same day (swap → 1 GB on commerce and apex, a fresh backup taken
and verified; the relay's 18 GB disk was at 68% and keeps its 4 GB). This page is the part that stops it
from happening silently again, in three layers:

1. **Box setup (Terraform / cloud-init).** The swapfile is `swapfile_size_gb` — 1 GB with a prebuilt
   image, 4 GB only when the image is built on the box (an explicit value overrides both). The journal
   is capped at 200 MB, and container logs rotate (3 × 20 MB per container).
2. **App logging.** `:commerce-service` had no logback configuration, so it logged at DEBUG — HikariCP's
   pool statistics twice every 30 s. It is INFO now (`commerce-service/src/main/resources/logback.xml`).
3. **Monitoring, with [healthchecks.io](https://healthchecks.io) as a dead man's switch.** Each job pings
   a URL when it succeeds; the service alerts when a ping is **late**. ⚠⚠ That is the property that
   matters: it was chosen over sending our own e-mails, Scaleway Cockpit or a self-hosted monitor
   because only an *external* switch alerts when **nothing arrives** — and nothing arriving is exactly
   how this failed. A full disk stops a script before it can report anything, and a box that sends its
   own alerts cannot send one about itself being broken.

## What is monitored

| check | box | sends | schedule | alert when |
|---|---|---|---|---|
| `commerce-backup` | commerce | `backup-commerce-db.sh` | daily 03:42 UTC | `/fail`, or no ping by ~05:42 |
| `relay-backup` | relay | `backup-relay-db.sh` | daily 03:17 UTC | `/fail`, or no ping by ~05:17 |
| `commerce-disk` | commerce | `tenshu-disk-check.sh` | hourly | `/fail`, or no ping for 3 h |
| `apex-disk` | apex | `tenshu-disk-check.sh` | hourly | `/fail`, or no ping for 3 h |
| `relay-disk` | relay | `tenshu-disk-check.sh` | hourly | `/fail`, or no ping for 3 h |

- **The backups** ping `BACKUP_PING_URL` from their `EXIT` trap: the plain URL on success,
  `…/fail` on any failure — including a missing or incomplete `deploy.env`, because the trap is set
  before that file is read. A box without `/opt/tenshu/healthchecks.env` still backs up; it just
  reports nothing.
- **The disk check** (`tenshu-disk-check.timer`, hourly with up to 5 min jitter) pings `/fail` when a watched filesystem is at or above **85%**
  (`DISK_LIMIT_PERCENT`), when `df` reports nothing, or when a watched path is **not a mount point**
  — a data volume that failed to attach leaves its directory on the root disk, and `df` of it would
  report the root disk's numbers as if all were well. Each box watches `/` plus its data volume
  (`DISK_MOUNTS`, written by `install.sh`): commerce `/mnt/commerce_data`, relay `/mnt/relay_data`,
  apex none.

### `/fail` versus a missing ping

| you see | it means | look at |
|---|---|---|
| a **`/fail`** ping | the job ran, and said what went wrong | the ping body on healthchecks.io (disk: only the mount point and its percentage, e.g. `/ at 91%`; nothing else leaves the box, and an OK ping carries no body), or `/var/log/tenshu-{commerce,relay}-backup.log` on the box (backup) |
| **no ping** (check goes *down* after the grace period) | the job did not run, or could not reach the internet: disk too full to start it, timer disabled or lost on a rebuild, the box down, `healthchecks.env` gone | `systemctl list-timers 'tenshu-*'`, `df -h`, `systemctl status tenshu-disk-check.service` |

⚠ A missing ping is the more serious one — it is the October failure mode. Do not "fix" it by widening
the grace period.

### Not covered

- **Is the service up?** healthchecks.io receives pings; it does not probe `/health`. An HTTP uptime
  check is a separate thing and does not exist yet.
- The relay's weekly **blob mirror** (`tenshu-blob-mirror.timer`) sends no ping.
- Memory, CPU, certificate expiry.

## Setting it up

### 1. Create the checks on healthchecks.io (maintainer)

One account (the free plan has room for these five checks), one project, e-mail as the integration.
Create the checks from the table above:

- **Backups:** type **Cron**, schedule `42 3 * * *` (commerce) / `17 3 * * *` (relay), time zone
  **UTC**, grace **2 hours**. ⚠ Keep these in step with the `OnCalendar=` of
  `tenshu-{commerce,relay}-backup.timer`; moving a timer without moving its check is a false alarm every
  night.
- **Disk:** type **Simple**, period **1 hour**, grace **2 hours**.

Each check has a ping URL, `https://hc-ping.com/<uuid>`. ⚠ The URL is the whole credential — anyone
holding it can mark the check green, which would hide a real failure. Treat it like a secret: it goes
into the Keychain, and on the box into a mode-600 file, nowhere else.

### 2. Store the ping URLs in the Keychain

`-w` with no value prompts, so nothing lands in shell history:

```bash
security add-generic-password -U -a tenshu -s healthchecks-commerce-disk-url   -w
security add-generic-password -U -a tenshu -s healthchecks-commerce-backup-url -w
security add-generic-password -U -a tenshu -s healthchecks-apex-disk-url       -w
security add-generic-password -U -a tenshu -s healthchecks-relay-disk-url      -w
security add-generic-password -U -a tenshu -s healthchecks-relay-backup-url    -w
```

### 3. Install on each box

```bash
deploy/box-health/install.sh --dry-run root@51.15.229.94  commerce   # checks + read-only probe; changes nothing
deploy/box-health/install.sh           root@51.15.229.94  commerce
deploy/box-health/install.sh           root@212.47.241.176 apex
deploy/box-health/install.sh           root@51.15.143.24  relay
```

It writes `/opt/tenshu/healthchecks.env` (mode 600), installs the disk check and enables its timer, caps
the journal (and restarts journald), copies **this checkout's** backup script over the box's copy for
commerce and relay (the old one is kept as `<name>.bak-<stamp>` if it differed), and runs one disk check
— which must pass, so the disk check should be green on healthchecks.io straight away. It refuses to
touch the box if a Keychain item is missing or not a plain `https://` URL, and never prints a URL. An
exported `DISK_PING_URL` / `BACKUP_PING_URL` wins over the Keychain.

⚠ It does **not** install the backup itself (packages, the backup timer) — that is still each backup's
how-to; `install.sh` only warns when the backup timer is not enabled. It does not touch swap or Docker
either.

⚠ Run it from a checkout of `master`: the backup script it ships is the one in your tree.

### 4. Verify

```bash
# the backup ping, without waiting for 03:xx (commerce shown; relay: tenshu-relay-backup.service)
ssh root@51.15.229.94 'systemctl start tenshu-commerce-backup.service; tail -3 /var/log/tenshu-commerce-backup.log'
# → the commerce-backup check shows a fresh ping

# the alert path, once: force a /fail, see the e-mail arrive, then clear it with a normal run
ssh root@51.15.229.94 'DISK_LIMIT_PERCENT=1 /opt/tenshu/tenshu-disk-check.sh; systemctl start tenshu-disk-check.service'
```

Until an alert e-mail has actually arrived once, the alerting is untested.

## ⚠⚠ The cloud-init change is for the next rebuild — never `apply` or `-replace` for it

The swap, journald and container-log settings live in each module's `cloud-init.yaml.tftpl`, and
cloud-init runs **once per instance**, at first boot. So the template change reaches a box only when
that box is rebuilt — and for these three modules a rebuild is not routine: for the relay it is an
outage procedure ([`deploy/scaleway-relay/README.md`](../../deploy/scaleway-relay/README.md), *"Rebuilding this instance is an outage procedure"*),
for commerce a billing outage. The data volumes survive a rebuild; the instance does not.

What a `plan` shows now: a `user_data` change on the server. ⚠ Whether that is an in-place update or a
replacement depends on the provider, and the repo has been wrong about it before — the DigitalOcean
provider **forced replacement**; the Scaleway provider was measured as an **in-place** update on the dev
lab (provider 2.83.1, [`deploy-dev-lab-scaleway.md`](deploy-dev-lab-scaleway.md)), which stores the new
`user_data` and changes nothing on the box. The production modules lock **2.82.0**, where it has not been
measured. Read the plan's `forces replacement` marker before any apply on these modules, and **do not
apply just to pick this up**: the live boxes were fixed by hand on 2026-10-23, and an in-place apply
would not change them anyway. After an intended rebuild, re-run `install.sh` for that box —
cloud-init does not install the health checks, because their URLs are not Terraform variables.

⚠ Container-log rotation (`/etc/docker/daemon.json`) applies only to boxes built from the new template,
and only to containers created after it. The live boxes still have Docker's unrotated default; at
commerce's INFO volume that grows by a few MB a month, so it was left for the next rebuild rather than
restarting Docker on production.
