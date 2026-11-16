# Shared helpers for the Scaleway -> OVH move. Sourced, not run.
#
# ⚠⚠ Every remote body goes through a QUOTED heredoc (`<<'EOF'`) with its arguments passed as
# `bash -s -- args`. A hand-quoted `ssh host "..."` string fails two invisible ways: a stray quote
# truncates it, or a pair of them makes the local shell evaluate part of it. `bash -n` sees neither.
#
# ⚠ Box-to-box copies stream THROUGH this machine (`ssh old tar | ssh new tar`). The payloads are
# small (tens of MB), and it means neither box ever needs a key for the other.

set -euo pipefail

MIGRATE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$MIGRATE_DIR/.." && pwd)"

[ -f "$MIGRATE_DIR/hosts.env" ] || { echo "missing $MIGRATE_DIR/hosts.env (copy hosts.env.example)"; exit 2; }
# shellcheck source=/dev/null
. "$MIGRATE_DIR/hosts.env"

SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 -o StrictHostKeyChecking=accept-new)

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   ✅ %s\n' "$*"; }
warn() { printf '   ⚠️  %s\n' "$*"; }
die()  { printf '   ⛔ %s\n' "$*" >&2; exit 1; }

# rsh HOST [args...] < script   — run a script read from stdin on HOST as root.
# ⚠⚠ ssh JOINS its arguments into one string that the remote shell splits again, so an argument
# with spaces ("--label a=b --label c=d", compose's flag string) would arrive as several. Each one is
# %q-quoted here, which the remote bash (root's login shell) undoes exactly. Without it, a volume
# was created as an anonymous one named by hash, and the copy into it failed.
rsh() {
  local h="$1" a q=""; shift
  for a in "$@"; do q="$q $(printf %q "$a")"; done
  ssh "${SSH_OPTS[@]}" "root@$h" "bash -s --$q"
}

confirm() {
  local answer
  read -r -p "   $1 Type 'yes' to continue: " answer
  [ "$answer" = "yes" ] || die "aborted"
}

# Per-role facts. The project names, compose files and env files are what PRODUCTION uses today
# (read off the running containers' labels), so `up` on the new box joins the same project instead
# of forking a second one (docs: compose-project-directory-forks-the-stack).
role_old() { case "$1" in relay) echo "$OLD_RELAY";; commerce) echo "$OLD_COMMERCE";; apex) echo "$OLD_APEX";; esac; }
role_new() { case "$1" in relay) echo "$NEW_RELAY";; commerce) echo "$NEW_COMMERCE";; apex) echo "$NEW_APEX";; esac; }
role_project() { case "$1" in relay) echo sync-relay-server;; commerce) echo commerce-service;; apex) echo apex;; esac; }
role_env() { case "$1" in apex) echo /opt/tenshu/apex.env;; *) echo /opt/tenshu/deploy.env;; esac; }
role_mount() { case "$1" in relay) echo /mnt/relay_data;; commerce) echo /mnt/commerce_data;; apex) echo "";; esac; }
role_compose() {
  case "$1" in
    relay)    echo "-p sync-relay-server -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env" ;;
    commerce) echo "-p commerce-service -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env" ;;
    apex)     echo "-p apex -f web/apex/docker-compose.yml --env-file /opt/tenshu/apex.env" ;;
  esac
}
role_domains() {
  case "$1" in
    relay)    echo "relay.tenshu.app" ;;
    commerce) echo "commerce.service.tenshu.app" ;;
    apex)     echo "tenshu.app calendar.tenshu.app contacts.tenshu.app drive.tenshu.app fit.tenshu.app home.tenshu.app maps.tenshu.app mia.tenshu.app photos.tenshu.app timeline.tenshu.app" ;;
  esac
}
role_timers() {
  case "$1" in
    relay)    echo "tenshu-relay-backup.timer tenshu-blob-mirror.timer tenshu-disk-check.timer" ;;
    commerce) echo "tenshu-commerce-backup.timer tenshu-disk-check.timer" ;;
    apex)     echo "tenshu-disk-check.timer" ;;
  esac
}

roles_from_args() {
  if [ "$#" -eq 0 ] || [ "$1" = all ]; then echo "relay commerce apex"; return; fi
  for r in "$@"; do case "$r" in relay|commerce|apex) ;; *) die "unknown role '$r' (relay|commerce|apex|all)";; esac; done
  echo "$@"
}

require_hosts() {
  local r
  for r in "$@"; do
    # ⚠ No ${r^^}: macOS ships bash 3.2.
    [ -n "$(role_old "$r")" ] || die "OLD_$(echo "$r" | tr a-z A-Z) is empty in hosts.env"
    [ -n "$(role_new "$r")" ] || die "NEW_$(echo "$r" | tr a-z A-Z) is empty in hosts.env (terraform output ipv4 in deploy/ovh-$r)"
  done
}

# compose_on HOST ROLE ARGS...  — run `docker compose <production flags> ARGS` in /opt/tenshu/repo.
compose_on() {
  local h="$1" r="$2"; shift 2
  rsh "$h" "$(role_compose "$r")" "$@" <<'EOF'
set -euo pipefail
flags="$1"; shift
cd /opt/tenshu/repo
# shellcheck disable=SC2086
docker compose $flags "$@"
EOF
}

# The deploy.env lines that point a box at OVH storage (from deploy/ovh-project's outputs).
env_overrides() {
  case "$1" in
    relay)    (cd "$DEPLOY_DIR/ovh-project" && terraform output -raw relay_env_overrides) ;;
    commerce) (cd "$DEPLOY_DIR/ovh-project" && terraform output -raw commerce_env_overrides) ;;
    apex)     : ;;
  esac
}

# ---- The copy steps (shared by 1-prepare.sh and 3-cutover.sh, all re-runnable) -------------------

check_new_host() {
  local r="$1" new; new="$(role_new "$r")"
  rsh "$new" "$(role_mount "$r")" <<'EOF'
set -euo pipefail
mount="$1"
[ -f /opt/tenshu-host/READY ] || { echo "cloud-init has not finished (no /opt/tenshu-host/READY); see /var/log/tenshu-bootstrap.log"; exit 1; }
command -v docker >/dev/null || { echo "docker missing"; exit 1; }
if [ -n "$mount" ]; then
  # ⚠⚠ The one check that matters most: data on the root disk is lost on a rebuild, silently.
  mountpoint -q "$mount" || { echo "$mount is NOT a mount point; data would land on the root disk"; exit 1; }
  [ "$(findmnt -no SOURCE "$mount")" != "$(findmnt -no SOURCE /)" ] || { echo "$mount is on the root disk"; exit 1; }
fi
echo "host ready since $(cat /opt/tenshu-host/READY); root $(df -h / | awk 'NR==2{print $4" free"}')"
EOF
}

# Every image a container of the project runs on the old box, copied by `docker save | docker load`.
# ⚠ Not pulled: the commerce image is private and the boxes cannot pull from GHCR. Copying also
# guarantees the new box runs the SAME image id as production, not merely the same tag.
copy_images() {
  local r="$1" old new project img id
  old="$(role_old "$r")"; new="$(role_new "$r")"; project="$(role_project "$r")"
  for img in $(rsh "$old" "$project" <<'EOF'
docker ps -a --filter "label=com.docker.compose.project=$1" --format '{{.Image}}' | sort -u
EOF
  ); do
    id="$(rsh "$old" "$img" <<'EOF'
docker image inspect --format '{{.Id}}' "$1"
EOF
    )"
    if [ "$(rsh "$new" "$img" <<'EOF'
docker image inspect --format '{{.Id}}' "$1" 2>/dev/null || true
EOF
    )" = "$id" ]; then ok "image $img already there"; continue; fi
    echo "   copying image $img ..."
    ssh "${SSH_OPTS[@]}" "root@$old" "docker save $(printf %q "$img") | gzip -1" \
      | ssh "${SSH_OPTS[@]}" "root@$new" "gunzip | docker load" >/dev/null
    ok "image $img ($id)"
  done
}

# /opt/tenshu: the repo checkout (with the box's local compose edits), env files, backup scripts,
# switch/rollback scripts. Then the tenshu-* systemd units, copied but left DISABLED: they ping the
# same healthchecks URLs as the old box, and two boxes pinging one check hides a failure.
sync_opt() {
  local r="$1" old new
  old="$(role_old "$r")"; new="$(role_new "$r")"
  ssh "${SSH_OPTS[@]}" "root@$old" "tar -C /opt -czf - tenshu" | ssh "${SSH_OPTS[@]}" "root@$new" "tar --numeric-owner -C /opt -xzf -"
  ssh "${SSH_OPTS[@]}" "root@$old" "cd /etc/systemd/system && tar -czf - tenshu-*.service tenshu-*.timer" \
    | ssh "${SSH_OPTS[@]}" "root@$new" "tar --numeric-owner -C /etc/systemd/system -xzf - && systemctl daemon-reload"
  # Keep an untouched copy of the Scaleway env: the bucket copy reads its source credentials from it.
  rsh "$new" "$(role_env "$r")" <<'EOF'
set -euo pipefail
cp -p "$1" "$1.scaleway"
chmod 600 "$1.scaleway"
EOF
  ok "/opt/tenshu and $(rsh "$new" <<'EOF'
ls /etc/systemd/system | grep -c '^tenshu-'
EOF
  ) systemd units copied (timers not enabled)"
}

# Pin <ROLE>_IMAGE_TAG in the NEW env to the tag the old container really runs.
# ⚠⚠ The env file's tag is not the truth: deploy/redeploy.sh EXPORTS the tag and never writes it
# to deploy.env. On 2026-11-12 production ran relay 290b7b9e while deploy.env said 36f1908d, so
# `up --pull never` found no such image and started BUILDING the relay from source on the 1-vCPU
# box, mid-window. `docker inspect` works on a stopped container, so this is safe in the window too.
pin_running_tag() {
  local r="$1" old new container var tag
  old="$(role_old "$r")"; new="$(role_new "$r")"
  case "$r" in
    relay)    container=sync-relay-server-relay-1;   var=RELAY_IMAGE_TAG ;;
    commerce) container=commerce-service-commerce-1; var=COMMERCE_IMAGE_TAG ;;
    apex)     container=apex-apex-1;                 var=APEX_IMAGE_TAG ;;
  esac
  tag="$(rsh "$old" "$container" <<'EOF'
docker inspect "$1" --format '{{.Config.Image}}' | sed 's/.*://'
EOF
  )"
  [ -n "$tag" ] || die "$r: cannot read the running image of $container on $old"
  rsh "$new" "$(role_env "$r")" "$var" "$tag" <<'EOF'
set -euo pipefail
was="$(grep "^$2=" "$1" | cut -d= -f2- || true)"
if grep -q "^$2=" "$1"; then sed -i "s|^$2=.*|$2=$3|" "$1"; else echo "$2=$3" >> "$1"; fi
[ "$was" = "$3" ] && echo "   $2=$3 (env already matched)" || echo "   ⚠ $2: env said '$was', container runs '$3' -> pinned $3"
EOF
}

# Point the env file at OVH storage: replace each KEY= line the overrides name, append the rest.
apply_overrides() {
  local r="$1" new overrides
  new="$(role_new "$r")"
  overrides="$(env_overrides "$r")"
  [ -n "$overrides" ] || return 0
  printf '%s\n' "$overrides" | ssh "${SSH_OPTS[@]}" "root@$new" "umask 077; cat > /opt/tenshu/ovh-overrides.env"
  rsh "$new" "$(role_env "$r")" <<'EOF'
set -euo pipefail
python3 - "$1" /opt/tenshu/ovh-overrides.env <<'PY'
import sys
env_path, ov_path = sys.argv[1], sys.argv[2]
ov = {}
for line in open(ov_path):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1); ov[k] = v
out, seen = [], set()
for line in open(env_path):
    k = line.split("=", 1)[0].strip()
    if k in ov:
        out.append(f"{k}={ov[k]}\n"); seen.add(k)
    else:
        out.append(line if line.endswith("\n") else line + "\n")
out += [f"{k}={v}\n" for k, v in ov.items() if k not in seen]
open(env_path, "w").writelines(out)
print(f"{len(seen)} replaced, {len(ov) - len(seen)} added: {', '.join(sorted(ov))}")
PY
EOF
}

# Docker named volumes of the project, except bind-backed ones (relay_data IS /mnt/relay_data) and
# logs. Recreated with the SAME labels: Compose compares its config-hash label, and a volume that
# exists without matching labels gets a warning or a refusal instead of being reused.
# ⭐ caddy_data carries the Let's Encrypt certificates, so the new Caddy serves valid TLS from the
# first request after the DNS switch, and the box can be tested over HTTPS before it.
copy_volumes() {
  local r="$1" old new project vol
  old="$(role_old "$r")"; new="$(role_new "$r")"; project="$(role_project "$r")"
  for vol in $(rsh "$old" "$project" <<'EOF'
for v in $(docker volume ls -q --filter "label=com.docker.compose.project=$1"); do
  [ "$(docker volume inspect "$v" --format '{{index .Options "device"}}')" = "" ] || continue
  # (no `case` here: bash 3.2 mis-parses a case pattern inside $(...))
  [ "${v%_logs}" = "$v" ] || continue
  echo "$v"
done
EOF
  ); do
    labels="$(rsh "$old" "$vol" <<'EOF'
docker volume inspect "$1" --format '{{range $k, $v := .Labels}}--label {{$k}}={{$v}} {{end}}'
EOF
    )"
    rsh "$new" "$vol" "$labels" <<'EOF'
set -euo pipefail
docker volume inspect "$1" >/dev/null 2>&1 && exit 0
# shellcheck disable=SC2086
docker volume create $2 "$1" >/dev/null
EOF
    ssh "${SSH_OPTS[@]}" "root@$old" "tar -C /var/lib/docker/volumes/$vol/_data -czf - ." \
      | ssh "${SSH_OPTS[@]}" "root@$new" "tar --numeric-owner -C /var/lib/docker/volumes/$vol/_data -xzf -"
    ok "volume $vol"
  done
}

# Is anything of the project running on HOST? Prints the container lines.
running_on() {
  rsh "$1" "$(role_project "$2")" <<'EOF'
docker ps --filter "label=com.docker.compose.project=$1" --format '{{.Names}} {{.Status}}'
EOF
}
