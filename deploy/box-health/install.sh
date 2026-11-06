#!/usr/bin/env bash
# Install (or refresh) box health on a RUNNING Tenshu box (docs/how-to/box-health.md).
#
#   deploy/box-health/install.sh [--dry-run] <ssh-host> <box>      # box: commerce | apex | relay
#   deploy/box-health/install.sh --dry-run root@51.15.229.94 commerce
#
# What it puts on the box:
#   - /opt/tenshu/tenshu-disk-check.sh + tenshu-disk-check.{service,timer} (hourly), enabled;
#   - /etc/systemd/journald.conf.d/tenshu-size.conf (journal capped at 200M), journald restarted;
#   - /opt/tenshu/healthchecks.env (mode 600): DISK_PING_URL, DISK_MOUNTS, and for commerce and relay
#     BACKUP_PING_URL;
#   - commerce / relay: the CURRENT backup script from this checkout (it carries the backup ping),
#     replacing the box's copy (the old one is kept as <name>.bak-<UTC stamp>);
#   - then runs one disk check, so the check on healthchecks.io turns green straight away.
#
# The ping URLs come from the macOS Keychain (account `tenshu`), stored once with a prompt so nothing
# lands in shell history:
#   security add-generic-password -U -a tenshu -s healthchecks-<box>-disk-url -w
#   security add-generic-password -U -a tenshu -s healthchecks-<box>-backup-url -w   # commerce, relay
# ⚠ An exported DISK_PING_URL / BACKUP_PING_URL wins over the Keychain, so this also runs off macOS.
#
# --dry-run touches nothing: it checks the local files and the Keychain items (never printing a URL),
# probes the box read-only (curl, systemd), and prints the plan and the remote script.
#
# ⚠ It does NOT touch swap, Docker or the backup timers. The backup's own install (packages, timer)
# is still the box's backup how-to; this only refreshes the script those timers run.
# ⚠⚠ Every remote body goes over stdin as ONE script (`ssh <host> bash -s`), with local values in a
# `printf %q` prelude — never a hand-quoted ssh argument, which has truncated scripts before.
set -euo pipefail

usage() { sed -n '4,5p' "$0" | sed 's/^# //' >&2; exit 2; }

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then DRY_RUN=1; shift; fi
[ $# -eq 2 ] || usage
HOST=$1
BOX=$2
SSH=${BOX_SSH:-ssh}   # a test seam: the transport, `ssh` unless overridden

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DEPLOY=$(dirname "$HERE")

case "$BOX" in
  commerce) BACKUP_SRC="$DEPLOY/scaleway-commerce/backup-commerce-db.sh"; MOUNTS="/ /mnt/commerce_data" ;;
  relay)    BACKUP_SRC="$DEPLOY/scaleway-relay/backup-relay-db.sh";       MOUNTS="/ /mnt/relay_data" ;;
  apex)     BACKUP_SRC="";                                                MOUNTS="/" ;;
  *) echo "install.sh: unknown box '$BOX' (commerce | apex | relay)" >&2; exit 2 ;;
esac

problems=0
problem() { echo "  ✗ $*" >&2; problems=$((problems + 1)); }

# --- local files ---
for f in tenshu-disk-check.sh tenshu-disk-check.service tenshu-disk-check.timer journald-size.conf; do
  [ -f "$HERE/$f" ] || problem "missing $HERE/$f"
done
bash -n "$HERE/tenshu-disk-check.sh" || problem "tenshu-disk-check.sh does not parse"
if [ -n "$BACKUP_SRC" ]; then
  [ -f "$BACKUP_SRC" ] || problem "missing $BACKUP_SRC"
  bash -n "$BACKUP_SRC" || problem "$(basename "$BACKUP_SRC") does not parse"
fi

# --- ping URLs: checked here, read again only inside the command that ships them ---
# ping_url <env var> <keychain service> — prints the URL on stdout; the caller never echoes it.
ping_url() {
  local v="${!1:-}"
  [ -n "$v" ] || v=$(security find-generic-password -a tenshu -s "$2" -w 2>/dev/null || true)
  printf '%s' "$v"
}
# Strict on purpose: the value is written into a file that bash SOURCES, so nothing that needs quoting
# may get through. healthchecks.io ping URLs are https://hc-ping.com/<uuid> or .../<ping key>/<slug>.
url_ok() { [[ "$1" =~ ^https://[A-Za-z0-9._~:/-]+$ ]]; }
check_url() { # check_url <env var> <keychain service>
  if [ -z "$(ping_url "$1" "$2")" ]; then
    problem "no '$2' in the Keychain (account tenshu) and \$$1 is unset"
  elif ! url_ok "$(ping_url "$1" "$2")"; then
    problem "'$2' is not an https:// URL of plain characters (value not shown)"
  else
    echo "  ✓ $2 present"
  fi
}
DISK_ITEM="healthchecks-$BOX-disk-url"
BACKUP_ITEM="healthchecks-$BOX-backup-url"
echo "Ping URLs:"
check_url DISK_PING_URL "$DISK_ITEM"
[ -z "$BACKUP_SRC" ] || check_url BACKUP_PING_URL "$BACKUP_ITEM"

# healthchecks.env as it will land on the box. Reads the Keychain itself, so it runs only where its
# output goes straight into the payload.
healthchecks_env() {
  echo "# Written by deploy/box-health/install.sh — docs/how-to/box-health.md. Mode 600: the URLs are"
  echo "# write capabilities for this box's checks."
  echo "DISK_PING_URL=$(ping_url DISK_PING_URL "$DISK_ITEM")"
  echo "DISK_MOUNTS=\"$MOUNTS\""
  [ -z "$BACKUP_SRC" ] || echo "BACKUP_PING_URL=$(ping_url BACKUP_PING_URL "$BACKUP_ITEM")"
}

b64() { base64 < "$1" | tr -d '\n'; }

# The remote script. Quoted heredoc: nothing in it is expanded locally.
remote_body() {
  cat <<'EOF'
set -euo pipefail
put() { # put <base64> <path> <mode> — written beside the target, then renamed over it
  printf '%s' "$1" | base64 -d > "$2.new"
  chmod "$3" "$2.new"
  mv -f "$2.new" "$2"
}
echo "== box health on $(hostname) ($BOX)"
command -v curl >/dev/null || { echo "curl is missing on this box — install it first"; exit 1; }
install -d -m 755 /opt/tenshu /etc/systemd/journald.conf.d

(umask 077; put "$HC_ENV_B64" /opt/tenshu/healthchecks.env 600)
put "$DISK_CHECK_B64" /opt/tenshu/tenshu-disk-check.sh 755
put "$DISK_SERVICE_B64" /etc/systemd/system/tenshu-disk-check.service 644
put "$DISK_TIMER_B64" /etc/systemd/system/tenshu-disk-check.timer 644
put "$JOURNALD_B64" /etc/systemd/journald.conf.d/tenshu-size.conf 644

if [ -n "$BACKUP_NAME" ]; then
  target=/opt/tenshu/$BACKUP_NAME
  printf '%s' "$BACKUP_B64" | base64 -d > "$target.new"
  bash -n "$target.new" || { rm -f "$target.new"; echo "the new $BACKUP_NAME does not parse — box copy left as it was"; exit 1; }
  rm -f "$target.new"
  if [ -f "$target" ] && printf '%s' "$BACKUP_B64" | base64 -d | cmp -s - "$target"; then
    echo "  $BACKUP_NAME already current"
  else
    [ ! -f "$target" ] || cp -p "$target" "$target.bak-$(date -u +%Y%m%dT%H%M%SZ)"
    put "$BACKUP_B64" "$target" 755
    echo "  $BACKUP_NAME replaced (previous copy kept as $BACKUP_NAME.bak-<stamp>)"
  fi
  systemctl is-enabled --quiet "$BACKUP_TIMER" \
    || echo "  ⚠ $BACKUP_TIMER is NOT enabled — the backup itself is not installed on this box (see its how-to)"
fi

systemctl daemon-reload
systemctl enable --now tenshu-disk-check.timer
systemctl restart systemd-journald
echo "  journal: $(journalctl --disk-usage 2>&1)"

echo "  running one disk check"
systemctl start tenshu-disk-check.service \
  || { echo "  ✗ the disk check reported a problem (a /fail ping) or could not ping at all:"
       journalctl -u tenshu-disk-check.service -n 20 --no-pager; exit 1; }
echo "  ✓ disk check passed and pinged (its check on healthchecks.io should now be up)"
systemctl list-timers tenshu-disk-check.timer --no-pager | head -2
EOF
}

case "$BOX" in commerce) BACKUP_TIMER=tenshu-commerce-backup.timer ;; relay) BACKUP_TIMER=tenshu-relay-backup.timer ;; *) BACKUP_TIMER="" ;; esac
BACKUP_NAME=""
[ -z "$BACKUP_SRC" ] || BACKUP_NAME=$(basename "$BACKUP_SRC")

payload() {
  printf 'BOX=%q\n' "$BOX"
  printf 'BACKUP_NAME=%q\n' "$BACKUP_NAME"
  printf 'BACKUP_TIMER=%q\n' "$BACKUP_TIMER"
  printf 'DISK_CHECK_B64=%q\n' "$(b64 "$HERE/tenshu-disk-check.sh")"
  printf 'DISK_SERVICE_B64=%q\n' "$(b64 "$HERE/tenshu-disk-check.service")"
  printf 'DISK_TIMER_B64=%q\n' "$(b64 "$HERE/tenshu-disk-check.timer")"
  printf 'JOURNALD_B64=%q\n' "$(b64 "$HERE/journald-size.conf")"
  if [ -n "$BACKUP_SRC" ]; then printf 'BACKUP_B64=%q\n' "$(b64 "$BACKUP_SRC")"; else echo "BACKUP_B64="; fi
  printf 'HC_ENV_B64=%q\n' "$(healthchecks_env | base64 | tr -d '\n')"
  remote_body
}

if [ "$DRY_RUN" = 1 ]; then
  echo
  echo "Box $HOST — read-only probe:"
  # shellcheck disable=SC2016
  "$SSH" "$HOST" bash -s <<'EOF' || problem "could not reach $HOST"
echo "  host:     $(hostname)"
echo "  curl:     $(command -v curl || echo MISSING)"
echo "  systemd:  $(systemctl --version | head -1)"
echo "  disks:"; df -h / /mnt/commerce_data /mnt/relay_data 2>/dev/null | sed 's/^/    /'
echo "  installed: $(ls /opt/tenshu/healthchecks.env /opt/tenshu/tenshu-disk-check.sh 2>/dev/null | tr '\n' ' ')"
EOF
  echo
  echo "Would install on $HOST ($BOX):"
  echo "  /opt/tenshu/healthchecks.env (600): DISK_PING_URL=<$DISK_ITEM>, DISK_MOUNTS=\"$MOUNTS\"${BACKUP_SRC:+, BACKUP_PING_URL=<$BACKUP_ITEM>}"
  echo "  /opt/tenshu/tenshu-disk-check.sh, /etc/systemd/system/tenshu-disk-check.{service,timer}"
  echo "  /etc/systemd/journald.conf.d/tenshu-size.conf"
  [ -z "$BACKUP_SRC" ] || echo "  /opt/tenshu/$BACKUP_NAME (from ${BACKUP_SRC#"$DEPLOY"/})"
  echo
  echo "Remote script (the values above it are passed as base64):"
  remote_body | sed 's/^/  | /'
  echo
  if [ "$problems" -gt 0 ]; then echo "DRY RUN: $problems problem(s) above — a real run would stop before touching the box." >&2; exit 1; fi
  echo "DRY RUN: nothing was changed."
  exit 0
fi

if [ "$problems" -gt 0 ]; then echo "install.sh: $problems problem(s) — nothing was changed on $HOST." >&2; exit 1; fi
payload | "$SSH" "$HOST" bash -s
