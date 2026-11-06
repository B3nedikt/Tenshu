#!/usr/bin/env bash
#
# Nightly snapshot of the relay's SQLite database to object storage.
#
# ⚠⚠ `VACUUM INTO` and not `cp`. A `cp` of a live WAL database can capture the main file and the WAL
# from different instants — a torn snapshot that opens cleanly and is missing rows, which is the same
# class of failure as copying `relay.db` without its `-wal` (that one yields a valid, EMPTY database
# with no error anywhere; see docs/how-to/move-the-relay-to-another-provider.md). `VACUUM INTO` is one
# of SQLite's two documented online-safe routes and needs no downtime: it writes a fully checkpointed,
# self-contained file with no WAL beside it, so a restore is a single file drop.
#
# ⚠ Runs against the LIVE relay. It takes no write lock for the duration — but see the note on
# SQLITE_BUSY below; this is why it retries rather than failing the night.
#
# ⚠ Two copies: BACKUP_S3_* (same region as the box) and, when BACKUP_DR_S3_BUCKET is set, the same file
# again to the second region (nl-ams, Object-Locked). The second upload runs only after the first is
# verified; if it fails the run exits 2 (unit failed) with the first copy untouched. Credentials are the
# job's own least-privilege key (deploy/scaleway-backup-access): it can write, it cannot delete.
set -euo pipefail

ENV_FILE=${ENV_FILE:-/opt/tenshu/deploy.env}
DB=${DB:-/mnt/relay_data/relay.db}
# ⚠ The temp snapshot goes on the DATA volume, never the root one. Root is 20 GB and holds every
# docker image layer; the data volume has the room. Writing a ~110 MB snapshot plus its gzip to a
# root volume that a docker pull is also filling is how a backup job takes the service down with it.
WORK=${WORK:-/mnt/relay_data/.backup-work}
LOG=${LOG:-/var/log/tenshu-relay-backup.log}

exec >>"$LOG" 2>&1

# Dead man's switch (docs/how-to/box-health.md): a finished run pings BACKUP_PING_URL, a failed one
# BACKUP_PING_URL/fail. ⚠ The alert that matters is the one raised when NO ping arrives — a full disk
# that stops this script before it can say anything is exactly how the commerce backups failed
# silently for 23 days in October 2026. Optional, so a box without the file still backs up.
HC_ENV=${HC_ENV:-/opt/tenshu/healthchecks.env}
# shellcheck disable=SC1090
[ -f "$HC_ENV" ] && . "$HC_ENV"
ping_health() {
  [ -n "${BACKUP_PING_URL:-}" ] || return 0
  curl -fsS -m 10 --retry 3 -o /dev/null "${BACKUP_PING_URL}$1" || echo "  (health ping failed)"
}
# The work dir is emptied on every run, not just on success: a snapshot left behind by a failed
# upload is ~110 MB of data-volume space that nothing else will ever reclaim. Set before the env file is
# read, so a missing or incomplete deploy.env reports /fail too.
trap 'status=$?; rm -rf "$WORK"; if [ "$status" -eq 0 ]; then ping_health ""; else ping_health /fail; fi' EXIT
echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) starting ==="

set -a
# shellcheck source=/dev/null
. "$ENV_FILE"
set +a
: "${BACKUP_S3_ENDPOINT:?BACKUP_S3_ENDPOINT unset in $ENV_FILE}"
: "${BACKUP_S3_BUCKET:?BACKUP_S3_BUCKET unset in $ENV_FILE}"
: "${BACKUP_S3_REGION:?BACKUP_S3_REGION unset in $ENV_FILE}"
: "${BACKUP_S3_ACCESS_KEY:?BACKUP_S3_ACCESS_KEY unset in $ENV_FILE}"
: "${BACKUP_S3_SECRET_KEY:?BACKUP_S3_SECRET_KEY unset in $ENV_FILE}"

# Upload one file to one target and read its size back. `primary` is BACKUP_S3_*; `dr` is BACKUP_DR_S3_*
# (the second region), on the same key unless BACKUP_DR_S3_ACCESS_KEY overrides it.
#
# ⚠⚠ The plausibility floor is RELATIVE, not absolute, and it runs on the PRIMARY upload only. It used to
# be "under 1 MB ⇒ refuse", which caught a truncated snapshot but also refused every snapshot of a freshly
# reset relay — an empty relay.db is ~272 KiB (3.6 KiB gzipped), so a reset meant weeks of no backups and
# nightly /fail pings. Now: refuse a snapshot under BACKUP_MIN_FRACTION (default 0.5) of the previous
# primary upload — unless /opt/tenshu/reset-epoch (written by deploy/reset-test-data.sh) is newer than that
# upload, i.e. this is the first snapshot after a deliberate reset. integrity_check and the ledger check
# below still apply (they run before upload() is called). A refused primary exits before the second region is tried, so the floor guards both.
# ⚠ The floor LISTS the primary bucket: the backup key needs s3:ListBucket there
# (deploy/scaleway-backup-access grants it on the primary bucket only — the second region is never listed).
RESET_EPOCH_FILE=${RESET_EPOCH_FILE:-/opt/tenshu/reset-epoch}
upload() {
python3 - "$1" "$2" "$3" "relay-db/" "$RESET_EPOCH_FILE" "tenshu-relay-backup.service" <<'PY'
import base64, hashlib, os, sys, boto3
from botocore.config import Config
src, key, target, prefix, epoch_file, unit = sys.argv[1:7]
p = "BACKUP_S3_" if target == "primary" else "BACKUP_DR_S3_"
env = os.environ
s3 = boto3.client(
    "s3",
    endpoint_url=env[p + "ENDPOINT"],
    region_name=env[p + "REGION"],
    aws_access_key_id=env.get(p + "ACCESS_KEY") or env["BACKUP_S3_ACCESS_KEY"],
    aws_secret_access_key=env.get(p + "SECRET_KEY") or env["BACKUP_S3_SECRET_KEY"],
    config=Config(signature_version="s3v4"),
)
bucket = env[p + "BUCKET"]
if target == "primary":
    size = os.path.getsize(src)
    prev = None
    try:
        for page in s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix):
            for o in page.get("Contents", []):
                if prev is None or o["LastModified"] > prev["LastModified"]:
                    prev = o
    except Exception as e:  # noqa: BLE001 — any listing failure is the same answer
        raise SystemExit(f"FAILED: cannot list {bucket}/{prefix} to find the previous upload ({e}). "
                         "The size floor needs s3:ListBucket on the backup key.")
    if prev is not None:
        fraction = float(env.get("BACKUP_MIN_FRACTION", "0.5"))
        try:
            epoch = float(open(epoch_file).read().strip())
        except (OSError, ValueError):
            epoch = None
        after_reset = epoch is not None and epoch > prev["LastModified"].timestamp()
        if after_reset:
            print(f"  first snapshot after the reset at {int(epoch)} — the size floor does not apply")
        elif size < prev["Size"] * fraction:
            raise SystemExit(f"FAILED: {size} bytes is under {fraction:.0%} of the previous upload "
                             f"({prev['Key']}, {prev['Size']} bytes) and no reset is recorded since — "
                             "refusing a suspect backup. If the shrink is real: `date -u +%s > "
                             f"{epoch_file}` and `systemctl start {unit}` (one run), or set "
                             "BACKUP_MIN_FRACTION in /opt/tenshu/deploy.env (standing).")
    s3.upload_file(src, bucket, key)
else:
    # ⚠ One PUT carrying Content-MD5. The second-region bucket is Object-Locked, and S3 requires an
    # integrity header on uploads to a locked bucket; the box's boto3 (jammy's 1.20) adds none by
    # itself. The MD5 is computed streaming and the body is a file handle, so nothing is read whole.
    # (A single PUT tops out at 5 GB — far above a gzipped snapshot.)
    md5 = hashlib.md5()
    with open(src, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            md5.update(chunk)
    with open(src, "rb") as f:
        s3.put_object(Bucket=bucket, Key=key, Body=f,
                      ContentMD5=base64.b64encode(md5.digest()).decode(),
                      StorageClass=env.get("BACKUP_DR_S3_STORAGE_CLASS") or "ONEZONE_IA")
# Read the object's size back rather than trusting a silent PUT. The relay's own content plane was
# migrated on the strength of exactly this check (index byte_len vs what S3 returns).
got = s3.head_object(Bucket=bucket, Key=key)["ContentLength"]
want = os.path.getsize(src)
if got != want:
    raise SystemExit(f"FAILED: uploaded {want} bytes to {target} but the object is {got}")
print(f"  uploaded {key} to {target} {bucket} ({got} bytes)")
PY
}

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
SNAP="$WORK/relay-$STAMP.db"
mkdir -p "$WORK"

# ⚠ Retry on SQLITE_BUSY. VACUUM INTO reads the whole database; a concurrent writer can still make it
# fail, and losing a night's backup to one transient lock is not acceptable when the retry is free.
for attempt in 1 2 3; do
  if sqlite3 "$DB" "VACUUM INTO '$SNAP';" ; then break; fi
  echo "  VACUUM INTO failed (attempt $attempt) — retrying in 30s"
  rm -f "$SNAP"
  [ "$attempt" = 3 ] && { echo "  FAILED: could not snapshot after 3 attempts"; exit 1; }
  sleep 30
done

SNAP_BYTES=$(stat -c %s "$SNAP")
sqlite3 "$SNAP" "PRAGMA integrity_check;" | head -1 | grep -qx "ok" || {
  echo "  FAILED: snapshot fails integrity_check — refusing to upload"; exit 1; }
# Sanity: the snapshot is a whole relay database — its migration ledger matches the live one and the
# identity table reads. A torn or foreign file fails here rather than on the day it is restored.
SNAP_SCHEMA=$(sqlite3 "$SNAP" "SELECT COALESCE(MAX(version), 0) FROM schema_version;" 2>/dev/null || echo "")
LIVE_SCHEMA=$(sqlite3 "$DB" "SELECT COALESCE(MAX(version), 0) FROM schema_version;" 2>/dev/null || echo "")
if [ -z "$SNAP_SCHEMA" ] || [ "$SNAP_SCHEMA" -lt 1 ] || [ "$SNAP_SCHEMA" != "$LIVE_SCHEMA" ]; then
  echo "  FAILED: snapshot schema_version '${SNAP_SCHEMA}' does not match the live database's '${LIVE_SCHEMA}'"; exit 1
fi
sqlite3 "$SNAP" "SELECT COUNT(*) FROM realm;" >/dev/null 2>&1 || {
  echo "  FAILED: the snapshot's realm table is unreadable — refusing to upload"; exit 1; }
BLOCKS=$(sqlite3 "$SNAP" "SELECT COUNT(*) FROM sync_block;")

gzip -6 "$SNAP"
GZ="$SNAP.gz"
GZ_BYTES=$(stat -c %s "$GZ")
KEY="relay-db/$(date -u +%Y)/relay-$STAMP.db.gz"

upload "$GZ" "$KEY" primary

# ⚠⚠ The second region (nl-ams), AFTER the primary is in place and verified. A failure here must be
# loud — the run exits 2 and systemd marks the unit failed — but it cannot touch the first copy: this
# step only reads the local file, and nothing in this script deletes from either bucket.
DR_FAILED=0
if [ -n "${BACKUP_DR_S3_BUCKET:-}" ]; then
  if [ -z "${BACKUP_DR_S3_ENDPOINT:-}" ] || [ -z "${BACKUP_DR_S3_REGION:-}" ]; then
    echo "  FAILED: DR: BACKUP_DR_S3_BUCKET is set but BACKUP_DR_S3_ENDPOINT/REGION are not"
    DR_FAILED=1
  elif upload "$GZ" "$KEY" dr; then
    echo "  DR: OK  ${BACKUP_DR_S3_BUCKET} ($BACKUP_DR_S3_REGION)"
  else
    echo "  FAILED: DR: second-region upload to ${BACKUP_DR_S3_BUCKET} failed — the primary copy ${KEY} is in place and untouched"
    DR_FAILED=1
  fi
else
  # Not a failure while the second region is being rolled out; the how-to's monitoring greps for it.
  echo "  ⚠ DR: BACKUP_DR_S3_BUCKET unset — no second-region copy tonight"
fi

echo "  OK  snapshot ${SNAP_BYTES}b -> ${GZ_BYTES}b gz, sync_block=${BLOCKS}, key=${KEY}"
if [ "$DR_FAILED" = 1 ]; then
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) done — PRIMARY OK, SECOND REGION FAILED (exit 2) ==="
  exit 2
fi
echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) done ==="
