#!/usr/bin/env bash
#
# Hourly disk check for a Tenshu box, reporting to a dead man's switch (docs/how-to/box-health.md).
#
# Pings DISK_PING_URL when every watched filesystem is below DISK_LIMIT_PERCENT, and DISK_PING_URL/fail
# (with `df` as the body) when one is not — or when a watched path is not a mount point at all: a data
# volume that failed to attach leaves its directory on the ROOT disk, where the service then writes its
# state, and `df` of that directory would report the root disk's numbers as if all were well. ⚠ The alert that matters is the one the switch raises when
# NO ping arrives: a box whose disk is so full that this script cannot run, or whose timer died, says
# nothing at all, and that silence is what it reports.
#
# Why it exists: the commerce box's root disk sat at 100% from early October 2026, and its nightly
# database backup failed for 23 days with nothing noticing (it writes the dump to /var/tmp first).
set -uo pipefail

HC_ENV=${HC_ENV:-/opt/tenshu/healthchecks.env}
# shellcheck disable=SC1090
[ -f "$HC_ENV" ] && . "$HC_ENV"
: "${DISK_PING_URL:?DISK_PING_URL unset in $HC_ENV}"
LIMIT=${DISK_LIMIT_PERCENT:-85}
# Every path here must be a mount point. install.sh writes the box's own list (root + its data volume).
MOUNTS=${DISK_MOUNTS:-/}

# Exits non-zero after a /fail ping too, so the unit shows as failed on the box (`systemctl --failed`).
fail() { curl -fsS -m 10 --retry 3 -o /dev/null --data-raw "$1" "${DISK_PING_URL}/fail"; exit 1; }

unmounted=""
for m in $MOUNTS; do mountpoint -q "$m" || unmounted="$unmounted $m"; done
# ⚠ Only mount points and percentages ever leave the box: no sizes, no device names, and nothing at
# all on an OK ping (decided 2026-10-23). The alert still says which disk, which is what matters
# when the disk is too full for the box's own logs to say it.
[ -z "$unmounted" ] || fail "not mounted:${unmounted}"

rows=$(df --output=pcent,target $MOUNTS 2>/dev/null | tail -n +2)
# A df that printed nothing is a failure, not "all clear".
[ -n "$rows" ] || fail "df reported nothing for: ${MOUNTS}"
over=$(printf '%s\n' "$rows" \
  | awk -v limit="$LIMIT" 'NF { gsub("%", "", $1); if ($1 + 0 >= limit) print $2 " at " $1 "%" }')
[ -z "$over" ] || fail "over ${LIMIT}%: ${over}"

curl -fsS -m 10 --retry 3 -o /dev/null "$DISK_PING_URL"
