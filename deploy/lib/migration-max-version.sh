#!/usr/bin/env bash
# Prints the highest migration version declared in a server *Database.kt read on stdin, i.e. the
# max(schema_version) an image built from that source writes on an empty database. Exit 1 when the
# source declares none (the list moved, or this is not a migration file) — never a guess.
#
#   git show <sha>:sync-relay-server/src/main/kotlin/org/tenshu/sync/relay/storage/RelayDatabase.kt \
#     | deploy/lib/migration-max-version.sh
#
# Used by deploy/reset-test-data.sh to know what the restarted relay/commerce must report.
# ⚠⚠ Unanchored on purpose: migrations are written both as `version = 1,` on its own line and as
# `Migration(version = 4, …)` on one line. An anchored match misses the second form and reads a
# lower version than the image writes, which stops a healthy relay mid-reset. Pinned against the real runners by
# RelayMigrationVersionDerivationTest and CommerceMigrationVersionDerivationTest.
set -euo pipefail
v="$(grep -oE '(^|[^A-Za-z0-9_])version[[:space:]]*=[[:space:]]*[0-9]+' | grep -oE '[0-9]+$' | sort -n | tail -1 || true)"
[[ -n "$v" ]] || exit 1
printf '%s\n' "$v"
