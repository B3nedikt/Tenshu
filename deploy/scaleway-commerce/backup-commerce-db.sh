#!/usr/bin/env bash
#
# Nightly snapshot of the commerce PostgreSQL database to object storage.
#
# ⚠ `pg_dump` runs INSIDE the database container. Postgres is an internal-only Compose service and
# the host has no postgres client packages — a bare `pg_dump` on the host has neither a reachable
# server nor the binary.
#
# ⚠ `-Fc` (custom format): compressed, and restorable selectively with `pg_restore`. It is also
# self-describing, which is what lets this script validate the archive before uploading it.
#
# ⚠⚠ Runs against a LIVE commerce. `pg_dump` takes a consistent MVCC snapshot, so the dump is
# internally consistent without stopping anything — but it is consistent AS OF ITS START. For a
# migration you need the source quiesced first; see docs/how-to/move-commerce-to-scaleway.md. This
# nightly job is a backup, not a cutover.
#
# ⚠ Two copies: BACKUP_S3_* (same region as the box) and, when BACKUP_DR_S3_BUCKET is set, the same file
# again to the second region (nl-ams, Object-Locked). The second upload runs only after the first is
# verified; if it fails the run exits 2 (unit failed) with the first copy untouched. Credentials are the
# job's own least-privilege key (deploy/scaleway-backup-access): it can write, it cannot delete.
set -euo pipefail

ENV_FILE=${ENV_FILE:-/opt/tenshu/deploy.env}
DB_CONTAINER=${DB_CONTAINER:-commerce-service-db-1}
DB_NAME=${DB_NAME:-commerce}
DB_USER=${DB_USER:-commerce}
WORK=${WORK:-/var/tmp/commerce-backup}
LOG=${LOG:-/var/log/tenshu-commerce-backup.log}

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
# Set before the env file is read, so a missing or incomplete deploy.env reports /fail too.
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
# ⚠⚠ The plausibility floor is RELATIVE and runs on the PRIMARY upload only (see
# deploy/scaleway-relay/backup-relay-db.sh for the full reasoning). The old absolute 10 kB floor refused
# the dump of a freshly reset, near-empty database. Now: refuse under BACKUP_MIN_FRACTION (default 0.5) of
# the previous primary upload, unless /opt/tenshu/reset-epoch is newer than that upload. The TOC checks
# below still apply (they run before upload() is called). A refused primary exits before the second region is tried.
# ⚠ The floor LISTS the primary bucket: the backup key needs s3:ListBucket there
# (deploy/scaleway-backup-access grants it on the primary bucket only — the second region is never listed).
RESET_EPOCH_FILE=${RESET_EPOCH_FILE:-/opt/tenshu/reset-epoch}
upload() {
python3 - "$1" "$2" "$3" "commerce-db/" "$RESET_EPOCH_FILE" <<'PY'
import base64, hashlib, os, sys, boto3
from botocore.config import Config
src, key, target, prefix, epoch_file = sys.argv[1:6]
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
        if epoch is not None and epoch > prev["LastModified"].timestamp():
            print(f"  first dump after the reset at {int(epoch)} — the size floor does not apply")
        elif size < prev["Size"] * fraction:
            raise SystemExit(f"FAILED: {size} bytes is under {fraction:.0%} of the previous upload "
                             f"({prev['Key']}, {prev['Size']} bytes) and no reset is recorded since — "
                             "refusing a suspect backup. If the shrink is real: `date -u +%s > "
                             f"{epoch_file}` and `systemctl start tenshu-commerce-backup.service` (one run), or set "
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
DUMP="$WORK/commerce-$STAMP.dump"
mkdir -p "$WORK"

docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$DUMP" || {
  echo "  FAILED: pg_dump did not complete"; exit 1; }

BYTES=$(stat -c %s "$DUMP")

# ⚠ Validate the ARCHIVE, not just the file. `pg_restore --list` parses the table of contents, so a
# truncated or corrupt dump fails here rather than on the day it is needed. Cheap: it reads the TOC,
# not the data.
TOC=$(docker exec -i "$DB_CONTAINER" pg_restore --list < "$DUMP" | grep -c "TABLE DATA" || true)
if [ "${TOC:-0}" -lt 1 ]; then
  echo "  FAILED: pg_restore --list found no TABLE DATA entries — archive is not usable"; exit 1
fi
# Sanity: the dump carries the migration ledger and the account table, and the live ledger reads.
for t in schema_version account; do
  docker exec -i "$DB_CONTAINER" pg_restore --list < "$DUMP" | grep -q "TABLE DATA public $t " || {
    echo "  FAILED: the archive has no TABLE DATA for $t — not a commerce database"; exit 1; }
done
LIVE_SCHEMA=$(docker exec "$DB_CONTAINER" psql -tAqU "$DB_USER" -d "$DB_NAME" -c "SELECT COALESCE(MAX(version), 0) FROM schema_version;" 2>/dev/null | tr -d '[:space:]')
case "$LIVE_SCHEMA" in
  ''|0|*[!0-9]*) echo "  FAILED: the live schema_version reads '${LIVE_SCHEMA}' — refusing to upload"; exit 1 ;;
esac

gzip -6 "$DUMP"
GZ="$DUMP.gz"
GZ_BYTES=$(stat -c %s "$GZ")
KEY="commerce-db/$(date -u +%Y)/commerce-$STAMP.dump.gz"

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

echo "  OK  dump ${BYTES}b -> ${GZ_BYTES}b gz, ${TOC} tables with data, key=${KEY}"
if [ "$DR_FAILED" = 1 ]; then
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) done — PRIMARY OK, SECOND REGION FAILED (exit 2) ==="
  exit 2
fi
echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) done ==="
