#!/usr/bin/env bash
# Wipe ALL server-side state on a deployed Tenshu test instance so the next
# onboarding starts against genuinely fresh data. This is DESTRUCTIVE and
# outward-facing: every paired device on the target relay is orphaned, and every
# commerce account/session/entitlement is deleted. Use it only on a test box you
# own.
#
#   ./deploy/reset-test-data.sh --relay <relay_host> [--commerce <commerce_host>]
#
# Examples (a TEST instance whose relay keeps blobs on its volume, i.e. no S3_*):
#   ./deploy/reset-test-data.sh --relay <test-relay-host> --commerce <test-commerce-host>
#   ./deploy/reset-test-data.sh --relay 203.0.113.10           # relay only
#
# ⚠⚠ An object-storage relay (S3_BUCKET set) is REFUSED unless you opt in with --empty-buckets and
# name the bucket with --bucket. Without that, this would wipe the index, leave every ciphertext in
# the bucket, and still verify a clean reset. Wiping commerce alone is a half reset that leaves every
# registered device 402ing (docs/how-to/reset-and-commerce-test.md §8); don't.
#
#   ./deploy/reset-test-data.sh --relay <relay_host> \
#       --empty-buckets --bucket <the S3_BUCKET value> [--empty-backups]
#
#   --empty-buckets  empty the relay's live blob bucket (S3_BUCKET) and, when set, the mirror
#                    (BLOB_MIRROR_BUCKET). ⚠⚠ Both or neither: emptying live while the mirror keeps
#                    the bytes is the same stranded-ciphertext half-state the refusal exists to
#                    prevent, and the next mirror run would carry them forward.
#   --bucket <name>  the interlock. Must equal the relay's rendered S3_BUCKET exactly or nothing
#                    runs — a destructive step against a named production bucket must not be
#                    reachable by a flag you can fat-finger.
#   --empty-backups  ALSO empty BACKUP_S3_BUCKET (the nightly relay-db snapshots). Separate on
#                    purpose: those are the only rollback for the reset itself, and they are not
#                    realm data — no client reaches them and they re-seed nothing. Pass it only when
#                    you mean "destroy the rollback too".
#
# ⚠ The emptying runs while the relay is STOPPED and after the blob-mirror timer is stopped, so no
# PUT and no mirror run can race the delete. Order is live → mirror, never the reverse.
#
# What it does, per host (the /opt/tenshu + /mnt/*_data layout of deploy/scaleway-{relay,commerce}/,
# same as the retired DO modules):
#   relay    — stop the `relay` container, delete /mnt/relay_data/relay.db(+wal/shm)
#              AND /mnt/relay_data/relay-blobs/* (index + bytes wiped together, or
#              you'd leave dangling refs), restart. Migrations rebuild an empty schema.
#   commerce — stop `commerce`+`db`, delete /mnt/commerce_data/pgdata/*, restart.
#              The postgres:16 entrypoint re-inits an empty DB; commerce migrations
#              re-run on boot.
#
# What it does NOT touch (by design):
#   - Caddy's caddy_data/caddy_config volumes → TLS certs survive (no ACME re-issue).
#   - The droplet, block volumes, DNS → this is a data wipe, not `terraform destroy`.
#
# Managed-DB commerce (DATABASE_URL points at an external Postgres, not the sidecar):
#   the pgdata rm does nothing there. Instead, against that DB, run:
#     DROP SCHEMA public CASCADE; CREATE SCHEMA public;
#   and restart commerce so migrations re-run. Pass --commerce-managed to skip the
#   sidecar wipe and print that reminder instead.
#
# Confirmation: you must type the relay host back to proceed. Set RESET_YES=1 to
# bypass (for automation only).
#
# Verification: after each wipe this asserts the data is actually GONE — relay
# table counts via sqlite3 on the host, commerce row counts via psql in the db
# container. A wipe that silently did not happen otherwise reads exactly like a
# successful one, which is the failure mode worth spending two ssh round-trips on.
#
# ── The production-reset flags (runbook: docs/how-to/reset-and-commerce-test.md) ─────────────
#
#   --relay-image-tag <tag|keep>     REQUIRED with --relay. The RELAY_IMAGE_TAG the relay restarts
#                                    onto, written into /opt/tenshu/deploy.env while it is DOWN.
#                                    ⚠⚠ Switch BEFORE the restart that follows the wipe, never after:
#                                    the old image on an empty volume writes its own schema_version
#                                    rows, and the new image's runner then skips every migration at or
#                                    below them — silently, with tables missing. `keep` restarts onto
#                                    the tag already deployed (for a test reset on the same build).
#   --commerce-image-tag <tag|keep>  The same for COMMERCE_IMAGE_TAG. REQUIRED with --commerce
#                                    (sidecar Postgres).
#   --expect-relay-schema <n>        The schema_version max() the restarted service must report.
#   --expect-commerce-schema <n>     Default: derived from the migration list at the tag's commit in
#                                    this checkout (tags are short shas — build-*-image.sh). A tag that
#                                    is not a commit here needs it explicitly. ⚠ A mismatch STOPS the
#                                    service and exits 1 rather than serving the wrong schema.
#   --single-plane                   Allow --relay or --commerce alone. Without it both are required:
#                                    one plane wiped alone is a half reset (runbook §8).
#   --statutory-exported <file>      The wipe REFUSES while commerce holds invoice/credit_note rows,
#                                    or its Stripe key is LIVE and Stripe reports invoices or credit
#                                    notes, unless this names the local export of them (a non-empty
#                                    file). Checked on every run, never assumed.
#   --backups-only                   Wipe NOTHING: empty only BACKUP_S3_BUCKET on the named host(s) —
#   --relay-backup-bucket <name>     the relay's nightly snapshots and/or the commerce backup bucket
#   --commerce-backup-bucket <name>  (deploy/scaleway-commerce-backups). Each name is an interlock that
#                                    must match the host's deploy.env. ⚠⚠ Run it only AFTER the
#                                    post-reset verification: those snapshots are the only rollback.
#                                    ⚠ RESET_YES does NOT skip its prompt; unattended, set
#                                    RESET_YES_BACKUPS=<bucket>[,<bucket>] naming every bucket.
#
# SSH: assumes `root@<host>` and the deploy/redeploy.sh conventions
# (repo at /opt/tenshu/repo, env at /opt/tenshu/deploy.env).
# ⚠ Pass extra ssh options via SSH_OPTS. The one that bites: a droplet reachable
# by IP but never by NAME has only the IP in known_hosts, so this dies with
# "Host key verification failed" before touching anything. Either add the hostname
# (verify the presented key matches the IP's first: `ssh-keyscan -t ed25519 <host>`
# against the IP's line in ~/.ssh/known_hosts) or run with
# SSH_OPTS="-o HostKeyAlias=<ip>".

set -euo pipefail

RELAY_HOST=""
COMMERCE_HOST=""
COMMERCE_MANAGED=0
EMPTY_BUCKETS=0
EMPTY_BACKUPS=0
BUCKET_NAMED=""
RELAY_IMAGE_TAG=""
COMMERCE_IMAGE_TAG=""
EXPECT_RELAY_SCHEMA=""
EXPECT_COMMERCE_SCHEMA=""
SINGLE_PLANE=0
STATUTORY_FILE=""
BACKUPS_ONLY=0
RELAY_BACKUP_BUCKET=""
COMMERCE_BACKUP_BUCKET=""

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELAY_IMAGE_REPO="ghcr.io/b3nedikt/tenshu-relay"
COMMERCE_IMAGE_REPO="ghcr.io/b3nedikt/tenshu-commerce"
RELAY_MIGRATIONS_KT="sync-relay-server/src/main/kotlin/org/tenshu/sync/relay/storage/RelayDatabase.kt"
COMMERCE_MIGRATIONS_KT="commerce-service/src/main/kotlin/org/tenshu/commerce/store/CommerceDatabase.kt"

# The header is every comment line from line 2 to the first non-comment line — no hard-coded range
# to drift when the header grows.
usage() {
  awk 'NR==1{next} /^#/{print; next} {exit}' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# shellcheck disable=SC2206  # deliberate word splitting: SSH_OPTS carries flags
SSH_CMD=(ssh ${SSH_OPTS:-})
# ⚠⚠ THE REMOTE-BODY TRUNCATION GUARD. Every remote body below is one quoted literal, and a stray
# quote of the enclosing kind ENDS it early: an apostrophe in a single-quoted body, a `"` in a
# double-quoted one. What is left over becomes extra words on this line, so the remote script arrives
# TRUNCATED and the leftovers run locally: a relay verification cut mid-`if` skips the only proof
# that the wipe happened and still prints "Server-side reset complete." exit 0.
#
# ⚠⚠ Argument count alone does NOT catch it. When
# the leftover begins with a shell operator (`|| true`, `&& …`) the call still gets exactly two
# arguments and the body is simply shorter; `bash -n` on THIS file is clean either way, and a
# truncated body is usually still valid bash, so syntax-checking the body does not catch it either.
# Measured: rewording one comment inside the quiesce body from '|| true' to "|| true" delivered 32 of
# its 48 lines — losing the whole unit-stop loop and the "still running" assertion — with argc=2 and
# no error anywhere.
#
# The invariant that actually holds is END-TO-END: every body ends with the sentinel line, so any
# truncation, at any point, drops it. Cheap, and it fires before the body is sent.
#
# ⚠ What this buys, precisely: a LOUD failure instead of a silent `exit 0`. It does NOT make the
# class harmless — the verification body that motivated it runs AFTER `rm relay.db` and after the
# bucket empty, so a truncation there still aborts a half-finished reset (the trap then restores the
# relay). An earlier revision of this comment claimed the guard "fires before the destructive step";
# it fires before each BODY is sent, which for the first few bodies is the same thing and for the
# verification body is not.
REMOTE_END='#REMOTE-END'
# ⚠⚠ 97, not 1. These are LOCAL bugs in this file, and every capturing call site (`$(run_ssh …)`:
# s3-detect, quiesce, bucket preflight, image preflight, statutory check) cannot be aborted from inside
# a subshell — it only sees an exit status. With `exit 1` the s3-detect
# site reported "could not render the relay config (status 1)" and the quiesce site reported "could
# not quiesce the mirror/backup units", sending an operator to diagnose a perfectly healthy box. No
# remote body here ever exits 97 (they use 0-4, and the Python 0-2), so callers can tell them apart.
# ⚠ The real constraint, easy to break silently: **curl exit 97 is CURLE_PROXY**. No body's status may
# be curl's: the health probes (REMOTE_HEALTH_FN) compare the HTTP code, and the one CAPTURED body that
# runs curl (REMOTE_STATUTORY) folds every curl failure into exit 3. Keep it that way, or pick another
# number.
REMOTE_BUG_RC=97
run_ssh() {
  if [[ $# -ne 2 ]]; then
    echo "internal error: run_ssh got $# arguments, expected 2 (host + command)." >&2
    echo "                A quote in one of the remote bodies is ending its literal early." >&2
    exit $REMOTE_BUG_RC
  fi
  # ⚠ Ends-WITH after trimming trailing whitespace, not an exact last-line match: the sentinels are
  # indented with their bodies, and a body may end with stray blank lines. An exact match made ONE
  # extra blank line before a heredoc's `REMOTE_EOF` abort the whole reset while blaming "a stray
  # quote" and printing an empty "last line delivered" — a wrong diagnosis for a trivial edit.
  local _body="$2"
  while [[ "$_body" == *$'\n' || "$_body" == *' ' || "$_body" == *$'\t' ]]; do _body="${_body%?}"; done
  if [[ "$_body" != *"$REMOTE_END" ]]; then
    echo "internal error: a remote body does not end with ${REMOTE_END}. NOTHING has been sent." >&2
    echo "                Either a stray quote truncated it, or its sentinel line is missing." >&2
    echo "                Last non-blank line: ${_body##*$'\n'}" >&2
    exit $REMOTE_BUG_RC
  fi
  # Second net: a truncation that happens to keep the sentinel would still usually break the syntax.
  if ! bash -n <<< "$2" 2>/dev/null; then
    echo "internal error: a remote body is not valid bash. Nothing was sent." >&2
    bash -n <<< "$2" 2>&1 | sed 's/^/                /' >&2
    exit $REMOTE_BUG_RC
  fi
  "${SSH_CMD[@]}" "$@"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --relay)             RELAY_HOST="${2:?--relay needs a host}"; shift 2 ;;
    --commerce)          COMMERCE_HOST="${2:?--commerce needs a host}"; shift 2 ;;
    --commerce-managed)  COMMERCE_MANAGED=1; shift ;;
    --empty-buckets)     EMPTY_BUCKETS=1; shift ;;
    --empty-backups)     EMPTY_BACKUPS=1; shift ;;
    --bucket)            BUCKET_NAMED="${2:?--bucket needs the bucket name}"; shift 2 ;;
    --relay-image-tag)   RELAY_IMAGE_TAG="${2:?--relay-image-tag needs a tag (or keep)}"; shift 2 ;;
    --commerce-image-tag) COMMERCE_IMAGE_TAG="${2:?--commerce-image-tag needs a tag (or keep)}"; shift 2 ;;
    --expect-relay-schema)    EXPECT_RELAY_SCHEMA="${2:?--expect-relay-schema needs a number}"; shift 2 ;;
    --expect-commerce-schema) EXPECT_COMMERCE_SCHEMA="${2:?--expect-commerce-schema needs a number}"; shift 2 ;;
    --single-plane)      SINGLE_PLANE=1; shift ;;
    --statutory-exported) STATUTORY_FILE="${2:?--statutory-exported needs the export file}"; shift 2 ;;
    --backups-only)      BACKUPS_ONLY=1; shift ;;
    --relay-backup-bucket)    RELAY_BACKUP_BUCKET="${2:?--relay-backup-bucket needs a name}"; shift 2 ;;
    --commerce-backup-bucket) COMMERCE_BACKUP_BUCKET="${2:?--commerce-backup-bucket needs a name}"; shift 2 ;;
    -h|--help)           usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done

if [[ -z "$RELAY_HOST" && -z "$COMMERCE_HOST" ]]; then
  echo "error: pass at least one of --relay / --commerce" >&2
  usage 1
fi

# ⚠ Both bucket flags act on the RELAY's object storage. Without --relay the S3 interlock block below
# is skipped entirely and the run reaches `ssh root@` with an empty host — it fails closed, but it
# fails saying "fix the relay's S3 settings", which sends you to a box you never named.
if [[ ( $EMPTY_BUCKETS -eq 1 || $EMPTY_BACKUPS -eq 1 ) && -z "$RELAY_HOST" ]]; then
  echo "error: --empty-buckets/--empty-backups empty the RELAY's object storage; pass --relay <host>." >&2
  exit 1
fi

# A tag lands in a remote `sed s|…|…|` and a compose image reference: refuse anything that is not a
# plain tag rather than quote it correctly in three places.
for _t in "$RELAY_IMAGE_TAG" "$COMMERCE_IMAGE_TAG"; do
  [[ -z "$_t" || "$_t" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "error: '$_t' is not an image tag ([A-Za-z0-9._-]+)." >&2; exit 1; }
done
for _n in "$EXPECT_RELAY_SCHEMA" "$EXPECT_COMMERCE_SCHEMA"; do
  [[ -z "$_n" || "$_n" =~ ^[0-9]+$ ]] || { echo "error: --expect-*-schema takes a number, got '$_n'." >&2; exit 1; }
done

if [[ $BACKUPS_ONLY -eq 1 ]]; then
  # A purge of the rollback, never combined with a wipe: the two have opposite preconditions (the wipe
  # wants the snapshots kept; the purge wants the wipe already verified).
  if [[ $EMPTY_BUCKETS -eq 1 || $EMPTY_BACKUPS -eq 1 || -n "$BUCKET_NAMED" || -n "$RELAY_IMAGE_TAG" \
        || -n "$COMMERCE_IMAGE_TAG" || $COMMERCE_MANAGED -eq 1 || $SINGLE_PLANE -eq 1 \
        || -n "$EXPECT_RELAY_SCHEMA" || -n "$EXPECT_COMMERCE_SCHEMA" || -n "$STATUTORY_FILE" ]]; then
    echo "error: --backups-only wipes nothing and takes none of the wipe's flags." >&2
    exit 1
  fi
  if [[ -n "$RELAY_HOST" && -z "$RELAY_BACKUP_BUCKET" ]] || [[ -z "$RELAY_HOST" && -n "$RELAY_BACKUP_BUCKET" ]]; then
    echo "error: --backups-only needs --relay and --relay-backup-bucket together (or neither)." >&2
    exit 1
  fi
  if [[ -n "$COMMERCE_HOST" && -z "$COMMERCE_BACKUP_BUCKET" ]] || [[ -z "$COMMERCE_HOST" && -n "$COMMERCE_BACKUP_BUCKET" ]]; then
    echo "error: --backups-only needs --commerce and --commerce-backup-bucket together (or neither)." >&2
    exit 1
  fi
else
  if [[ -n "$RELAY_BACKUP_BUCKET" || -n "$COMMERCE_BACKUP_BUCKET" ]]; then
    echo "error: --relay-backup-bucket/--commerce-backup-bucket belong to --backups-only, which runs" >&2
    echo "       AFTER the reset has been verified — never inside the wipe." >&2
    exit 1
  fi
  # ⚠⚠ Never one plane alone (runbook §8): relay-only leaves accounts bound to an ownerId that no
  # longer exists; commerce-only leaves every registered device 402ing on every push.
  if [[ $SINGLE_PLANE -ne 1 && ( -z "$RELAY_HOST" || -z "$COMMERCE_HOST" ) ]]; then
    echo "error: pass BOTH --relay and --commerce — a one-plane wipe is a half reset, worse than none." >&2
    echo "       (--single-plane overrides, for a test instance that genuinely has only one.)" >&2
    exit 1
  fi
  if [[ -n "$RELAY_HOST" && -z "$RELAY_IMAGE_TAG" ]]; then
    echo "error: --relay needs --relay-image-tag <tag|keep>: the image the relay restarts onto after" >&2
    echo "       the wipe. Restarting the OLD image on the empty volume recreates its schema_version rows" >&2
    echo "       and the new image then skips its migrations silently." >&2
    exit 1
  fi
  if [[ -n "$COMMERCE_HOST" && $COMMERCE_MANAGED -ne 1 && -z "$COMMERCE_IMAGE_TAG" ]]; then
    echo "error: --commerce needs --commerce-image-tag <tag|keep> (same reason as the relay's)." >&2
    exit 1
  fi
  if [[ -n "$STATUTORY_FILE" && ! -s "$STATUTORY_FILE" ]]; then
    echo "error: --statutory-exported '$STATUTORY_FILE' is missing or empty." >&2
    exit 1
  fi
fi

# ⚠⚠ The project names are EXPLICIT and equal to the ones compose has always derived (the directory of
# the first -f file) — `sync-relay-server`, `commerce-service`. Implicit names change with how compose
# is invoked, and a different project name is a second, empty stack beside the live one
# — it has forked the production stack once. The image preflight
# asserts the running service belongs to this project before anything is deleted.
RELAY_COMPOSE="docker compose -p sync-relay-server -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env"
COMMERCE_COMPOSE="docker compose -p commerce-service -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env"

# The object-storage step, defined once and run twice: PREFLIGHT=1 (lists only, deletes nothing)
# before anything is destroyed, then for real once the relay is down. Defining it once is the point —
# a preflight that is not byte-identical to the run it vouches for vouches for nothing.
#
# boto3 + python3 are already on the box (deploy/scaleway-relay/backup-relay-db.sh uses exactly this
# pair), so this adds no dependency. Credentials are read from deploy.env ON THE HOST and never
# leave it; only bucket names are ever printed.
read -r -d '' BUCKET_PY <<'PYEOF' || true
import os, sys, boto3
from botocore.config import Config

def client(ep, region, ak, sk):
    return boto3.client('s3', endpoint_url=ep, region_name=region,
                        aws_access_key_id=ak, aws_secret_access_key=sk,
                        config=Config(signature_version='s3v4'))

def _err_code(e):
    # ⚠⚠ `.response` is only a dict for botocore's ClientError. Both callers catch bare `Exception`,
    # i.e. deliberately anything, and a requests-style exception carries a response OBJECT — whose
    # `.get` raises AttributeError out of the very `except` block meant to handle the failure. In
    # sweep_probe that lands inside the preflight's own handler, so sys.exit(2) never runs, the
    # "delete it by hand" line is never printed, the marker stays in the bucket, and the operator
    # gets a chained traceback instead of a refusal.
    resp = getattr(e, 'response', None)
    if not isinstance(resp, dict):
        return ''
    err = resp.get('Error')
    return (err.get('Code', '') or '') if isinstance(err, dict) else ''

class ListVersionsDenied(Exception):
    """The bucket may be versioned but this key cannot enumerate versions — never fall back."""

# ⚠⚠ The ONLY codes that mean "this store has no versioning API", i.e. the one case where a flat
# listing is the whole truth. Everything else — a denial, a throttle, a TLS failure, a 403 whose body
# botocore could not parse into Error.Code — must REFUSE. The polarity matters: an allowlist of
# denial codes defaults to the flat fallback, and `AccessDeniedException`, an `Error` with no `Code`,
# and an unparsed 403 all land there, delete current versions, re-list flat and print remaining=0
# over every noncurrent version still stored. Listing what is safe is the only safe direction.
NO_VERSIONING_API = ('NotImplemented', 'MethodNotAllowed', 'UnsupportedOperation')

def sweep_probe(c, bucket, key):
    # Best effort: take the preflight marker (and any versions of it) back out. The probe runs BEFORE
    # the prompts, so a preflight that fails *after* the PUT must not leave an object behind in a
    # production bucket for an operator who then aborts. Returns True if the key is provably gone.
    try:
        c.delete_object(Bucket=bucket, Key=key)
    except Exception:
        pass
    def versions():
        out = []
        for p in c.get_paginator('list_object_versions').paginate(Bucket=bucket, Prefix=key):
            out += [o['VersionId'] for o in (p.get('Versions', []) + p.get('DeleteMarkers', []))
                    if o['Key'] == key]
        return out
    # ⚠⚠ Confirm over the VERSION listing wherever there is one. The delete_object above writes a
    # delete MARKER on a versioned bucket, and a marker HIDES the key from list_objects_v2 — so a
    # flat confirmation prints "removed" while the object and the marker are both still stored and
    # billed. That false reassurance would land on exactly the path this message exists for: a key
    # that can PUT and can delete, but cannot delete a VERSION.
    # ⚠⚠ Fall back to the flat view ONLY for a store that positively has no versioning API — the same
    # polarity `versioned_pages` uses, and for the same reason. An earlier revision latched on "a
    # version listing already succeeded", which closed only half the window: a throttle or denial on
    # the FIRST listing left the latch unset, dropped into the flat branch, and the delete marker
    # written above hides the key there — so it returned "provably gone" over a surviving object and
    # its marker, suppressing the one warning this path exists to print. Measured: a fault on listing
    # #1 returned True with 2 objects left; on listing #2, False with 0 left. Judge the CODE, never
    # which call happened to fail.
    try:
        for vid in versions():
            try:
                c.delete_object(Bucket=bucket, Key=key, VersionId=vid)
            except Exception:
                pass
        return not versions()
    except Exception as e:
        code = _err_code(e)
        if code not in NO_VERSIONING_API:
            return False   # cannot see versions ⇒ cannot claim the probe is gone
    # No versioning API at all: the flat listing is then the whole truth for this key.
    try:
        for p in c.get_paginator('list_objects_v2').paginate(Bucket=bucket, Prefix=key):
            if any(o['Key'] == key for o in p.get('Contents', [])):
                return False
        return True
    except Exception:
        return False

def versioned_pages(c, bucket):
    # ⚠⚠ The version listing must be PROBED, not wrapped in a try. `paginate()` builds a lazy
    # PageIterator and issues no request, so a try/except around it catches nothing — the first
    # ListObjectVersions call happens inside the for-loop, outside the guard, and an S3-compatible
    # store answering NotImplemented (or a key without s3:ListBucketVersions) escapes as an uncaught
    # traceback with the database already deleted. Force the first page here instead.
    try:
        it = iter(c.get_paginator('list_object_versions').paginate(Bucket=bucket))
        first = next(it)
    except Exception as e:
        # ⚠⚠ "Cannot enumerate versions" is NOT "has no versions". A key with s3:ListBucket but not
        # s3:ListBucketVersions would otherwise fall back to the flat listing, delete current
        # versions (creating markers), re-list flat, and report remaining=0 over every noncurrent
        # version still stored and billed — the vacuous pass this whole path exists to close.
        # Refuse for everything except the codes that positively mean "no versioning API here".
        code = _err_code(e)
        if code not in NO_VERSIONING_API:
            raise ListVersionsDenied(
                '%s: could not enumerate versions (%s). A flat fallback would report an empty '
                'bucket over versions it cannot see.' % (bucket, code or type(e).__name__))
        return None
    def gen():
        yield first
        for p in it:
            yield p
    return gen()

def empty(label, c, bucket, prefix):
    # ⚠ Versioned buckets: deleting 'objects' leaves versions and delete markers behind, and a
    # plain list then reports zero while the bytes are still billed and restorable.
    kw = {'Bucket': bucket}
    if prefix:
        kw['Prefix'] = prefix
    pages = versioned_pages(c, bucket)
    versioned = pages is not None
    if versioned:
        keyed = lambda p: [{'Key': o['Key'], 'VersionId': o['VersionId']}
                           for o in (p.get('Versions', []) + p.get('DeleteMarkers', []))
                           if not prefix or o['Key'].startswith(prefix)]
    else:
        pages = c.get_paginator('list_objects_v2').paginate(**kw)
        keyed = lambda p: [{'Key': o['Key']} for o in p.get('Contents', [])]

    deleted = 0
    failed = []
    def flush(batch):
        # ⚠⚠ `Quiet=True` still returns an Errors array for per-key failures (AccessDenied, an
        # object-lock retention). Counting len(batch) as deleted turns a refused delete into a
        # success line. Read the response.
        nonlocal deleted
        resp = c.delete_objects(Bucket=bucket, Delete={'Objects': batch, 'Quiet': True})
        errs = resp.get('Errors', []) or []
        for e in errs:
            failed.append('%s (%s)' % (e.get('Key', '?'), e.get('Code', '?')))
        deleted += len(batch) - len(errs)

    batch = []
    # ⚠⚠ EVERYTHING here is outside the earlier guard. `versioned_pages` forces only page ONE inside
    # its try; pages 2..N are yielded by the generator unguarded, and `delete_objects` was never
    # guarded at all. A throttle (`SlowDown`) on version page 2, or on the first delete batch, then
    # escaped `empty()` as a raw ClientError traceback — after `rm relay.db`, mid-wipe, which is the
    # one moment a stack trace is worst. Fold any of it into the `failed` list so the run reports
    # `FAILED=… remaining=unverifiable` and exits 1 like every other refusal.
    try:
        for page in pages:
            for item in keyed(page):
                batch.append(item)
                if len(batch) == 1000:
                    flush(batch); batch = []
        if batch:
            flush(batch)
    except Exception as e:
        failed.append('listing/delete aborted after %d deletes: %s' % (deleted, e))

    # Re-list and assert zero. ⚠⚠ Over the SAME listing the delete used: re-listing a versioned
    # bucket with list_objects_v2 sees neither noncurrent versions nor delete markers — precisely
    # what the version path exists for — so it would print remaining=0 over the bytes still stored.
    left = 0
    if versioned:
        # ⚠ Catch the refusal HERE. The first listing succeeded (that is why `versioned` is true), so
        # a refusal on the re-list is a credential or policy change mid-run — and by now the deletes
        # have already happened. Letting ListVersionsDenied escape turns that into an uncaught
        # traceback *after* the database is gone; the `left = -1` branch below is exactly the
        # "deleted, cannot confirm" report the operator needs instead.
        try:
            rest = versioned_pages(c, bucket)
            if rest is None:
                left = -1  # cannot re-verify; treated as failure below
            else:
                for p in rest:
                    for o in (p.get('Versions', []) + p.get('DeleteMarkers', [])):
                        if not prefix or o['Key'].startswith(prefix):
                            left += 1
        except Exception:
            # Same reasoning as the delete loop: pages 2..N are unguarded, and "could not count"
            # must read as `unverifiable`, never as a traceback and never as zero.
            left = -1
    else:
        try:
            for p in c.get_paginator('list_objects_v2').paginate(**kw):
                left += len(p.get('Contents', []))
        except Exception:
            left = -1

    scope = ('prefix=%s ' % prefix) if prefix else ''
    print('   emptied   %-7s bucket=%-40s %sdeleted=%d remaining=%s%s'
          % (label, bucket, scope, deleted,
             ('unverifiable' if left < 0 else left),
             (' FAILED=%d' % len(failed)) if failed else ''))
    for f in failed[:5]:
        print('      ⚠ refused: %s' % f, file=sys.stderr)
    return (1 if (left != 0 or failed) else 0)

def need(name):
    # ⚠⚠ Name the missing variable and stop. A bare KeyError here is the worst possible failure:
    # by the time this runs the relay database is ALREADY deleted, so an unhandled one leaves a
    # wiped index beside a full bucket — the stranded-ciphertext half-state this whole flag exists
    # to prevent. The PREFLIGHT below is what keeps that from being reachable at all.
    v = os.environ.get(name, '').strip()
    if not v:
        print('   ⚠⚠ %s is not set in /opt/tenshu/deploy.env — cannot reach object storage.' % name,
              file=sys.stderr)
        sys.exit(2)
    return v

def endpoint(prefix=''):
    # Scaleway's relay config may carry an explicit endpoint or rely on the region. Accept either,
    # and derive the regional endpoint rather than guessing a bucket-style URL.
    ep = os.environ.get(prefix + 'S3_ENDPOINT', '').strip()
    if ep:
        return ep
    region = os.environ.get(prefix + 'S3_REGION', '').strip() or 'fr-par'
    return 'https://s3.%s.scw.cloud' % region

PREFLIGHT = os.environ.get('PREFLIGHT') == '1'

REGION = os.environ.get('S3_REGION', '').strip() or 'fr-par'

# ⚠⚠ The relay namespaces its objects when S3_KEY_PREFIX is set, and a bucket can legitimately be
# SHARED — a test relay pointed at the same bucket under `test/` is the cheap setup. Deleting the
# whole bucket there destroys the other tenant's content while the interlock reads as satisfied,
# because the bucket NAME matched. Scope every listing and delete to the relay's own prefix.
#
# ⚠⚠ AND KNOW THE LIMIT: this protects co-tenants only when THIS relay declares a prefix. Production
# does not — S3_KEY_PREFIX is absent from terraform.tfvars.example and cloud-init.yaml.tftpl
# (deploy/scaleway-relay/README.md:108 calls it optional) — so PREFIX is '' there and this empties
# the whole bucket, a co-tenant under `test/` included. A relay at the bucket ROOT is treated as
# owning the bucket; `--bucket` is the only guard in that direction. Give the test relay a prefix,
# or give it its own bucket.
#
# ⚠⚠ NORMALISE exactly as the relay does, and do NOT trim: `RelayConfig.kt:265` is
# `takeIf { it.isNotBlank() } ?: ""` with no `.trim()`, then `S3BlobStore.kt:52-56` appends '/'
# unless already present (the contract test pins the slash-less form `keyPrefix = "staging"` →
# `staging/alice/blob-1`). A `.strip()` here diverges for `S3_KEY_PREFIX="prod "`: the relay writes
# under `prod /`, this would scope to `prod/`, match nothing, and print `deleted=0 remaining=0` —
# exit 0 over an untouched bucket, after the database is already gone. And matching the RAW value
# unnormalised makes `prod` also match `prod-staging/…` and `production/…`, deleting a CO-TENANT
# relay's ciphertext. Both failures are one line apart; mirror the relay exactly.
_raw_prefix = os.environ.get('S3_KEY_PREFIX', '')
if not _raw_prefix.strip():        # Kotlin isNotBlank(): whitespace-only is no prefix at all
    PREFIX = ''
elif _raw_prefix.endswith('/'):
    PREFIX = _raw_prefix
else:
    PREFIX = _raw_prefix + '/'

# RESET_MODE picks the target set. `relay` (default) is the wipe: live → mirror (→ backups). The
# `backups-only` mode empties BACKUP_S3_BUCKET of whichever host this runs on and nothing else — the
# post-verification purge of the relay's nightly snapshots or of the commerce backup bucket
# (`--backups-only`), which must never ride the wipe itself: they are its only rollback.
MODE = os.environ.get('RESET_MODE', 'relay')
targets = []
if MODE == 'backups-only':
    _bb = need('BACKUP_S3_BUCKET')
    # ⚠⚠ The reverse of the distinctness check below: a BACKUP_S3_BUCKET mis-edited to the LIVE or the
    # MIRROR bucket would make this "backups only" purge empty realm content — after the reset was
    # verified, with nothing left to restore it from.
    for _k in ('S3_BUCKET', 'BLOB_MIRROR_BUCKET'):
        if os.environ.get(_k, '').strip() == _bb:
            print('   ⚠⚠ BACKUP_S3_BUCKET %r IS %s — refusing: this would empty content, not snapshots.'
                  % (_bb, _k), file=sys.stderr)
            sys.exit(2)
    # ⚠ No prefix: a snapshot bucket belongs to its backup job alone.
    targets.append(('backups',
                    client(os.environ.get('BACKUP_S3_ENDPOINT', '').strip() or endpoint('BACKUP_'),
                           os.environ.get('BACKUP_S3_REGION', '').strip() or REGION,
                           need('BACKUP_S3_ACCESS_KEY'), need('BACKUP_S3_SECRET_KEY')),
                    _bb, ''))
elif MODE != 'relay':
    print('   ⚠⚠ unknown RESET_MODE %r — refusing.' % MODE, file=sys.stderr)
    sys.exit(2)
else:
    targets.append(('live', client(endpoint(), REGION,
                                   need('S3_ACCESS_KEY'), need('S3_SECRET_KEY')),
                    need('S3_BUCKET'), PREFIX))

    mirror_bucket = os.environ.get('BLOB_MIRROR_BUCKET', '').strip()
    if mirror_bucket:
        # Same credentials unless the mirror declares its own.
        mirror_client = client(os.environ.get('BLOB_MIRROR_ENDPOINT', '').strip() or endpoint(),
                               os.environ.get('BLOB_MIRROR_REGION', '').strip() or REGION,
                               os.environ.get('BLOB_MIRROR_ACCESS_KEY', '').strip() or need('S3_ACCESS_KEY'),
                               os.environ.get('BLOB_MIRROR_SECRET_KEY', '').strip() or need('S3_SECRET_KEY'))
        targets.append(('mirror', mirror_client, mirror_bucket, PREFIX))
        # ⚠⚠ The mirror's LEDGER (`_tenshu-mirror/missing-since.json` + its `prior/` copies) sits at the
        # mirror bucket's ROOT whatever S3_KEY_PREFIX says (deploy/scaleway-relay-backups/mirror-blobs.py).
        # With no prefix — production — the whole-bucket empty above already takes it. With one, it
        # would survive, and the first post-reset mirror run would measure a ~100 % source drop against
        # it and trip the breaker into a hold. Empty it explicitly. (It also carries a co-tenant's prune
        # bookkeeping; an empty ledger only means pruning restarts its sightings, never a delete.)
        if PREFIX:
            targets.append(('ledger', mirror_client, mirror_bucket, '_tenshu-mirror/'))
    else:
        print('   mirror   (BLOB_MIRROR_BUCKET unset — nothing to empty)')

    if os.environ.get('WANT_BACKUPS') == '1':
        bb = os.environ.get('BACKUP_S3_BUCKET', '').strip()
        if bb:
            # ⚠ No prefix: the snapshot bucket is the backup job's alone (`relay-db/<year>/…`).
            targets.append(('backups',
                            client(os.environ.get('BACKUP_S3_ENDPOINT', '').strip() or endpoint('BACKUP_'),
                                   os.environ.get('BACKUP_S3_REGION', '').strip() or REGION,
                                   need('BACKUP_S3_ACCESS_KEY'), need('BACKUP_S3_SECRET_KEY')),
                            bb, ''))
        else:
            print('   backups  (BACKUP_S3_BUCKET unset — nothing to empty)')
    else:
        print('   backups  KEPT (no --empty-backups) — the rollback for this reset')

# ⚠⚠ Every bucket this run will empty, named on stdout so the SHELL can interlock on all of them.
# `--bucket` only ever matched the live one; the mirror and the snapshot bucket are read from the
# host's deploy.env and were emptied with no name check at all. A mis-edited BLOB_MIRROR_BUCKET
# pointing at the snapshot bucket would be emptied as "mirror" while the summary said backups were
# kept. Machine-readable so the caller can compare rather than the operator having to read.
# ⚠ PREFLIGHT only: these are protocol for the shell, which greps them out of the preflight output.
# The real pass has no such filter, so emitting them there just prints a raw tab-separated line into
# the middle of a wipe for the operator to puzzle over.
if PREFLIGHT:
    for label, _c, bucket, pfx in targets:
        print('TARGET\t%s\t%s\t%s' % (label, bucket, pfx), file=sys.stderr)

# ⚠⚠ And ASSERT they are distinct, which the shell cannot do: `--bucket` only ever cross-checks the
# LIVE bucket, so a mis-edited BLOB_MIRROR_BUCKET pointing at the snapshot bucket was emptied as
# "mirror" while the summary line said backups were KEPT — and under RESET_YES=1 there is no prompt
# in between. Naming the buckets on stdout is not the same as comparing them.
_backup_bucket = os.environ.get('BACKUP_S3_BUCKET', '').strip()
_seen = {}
for label, _c, bucket, _pfx in targets:
    if label == 'ledger':
        continue   # the mirror bucket again on purpose, scoped to the reserved prefix
    if bucket in _seen:
        print('   ⚠⚠ %s and %s both resolve to bucket %r — refusing: one of them is misconfigured '
              'in /opt/tenshu/deploy.env.' % (_seen[bucket], label, bucket), file=sys.stderr)
        sys.exit(2)
    _seen[bucket] = label
    if label != 'backups' and _backup_bucket and bucket == _backup_bucket:
        print('   ⚠⚠ the %s bucket %r IS BACKUP_S3_BUCKET — refusing. Emptying it as %r would '
              'destroy the rollback for this reset while the summary says backups are kept.'
              % (label, bucket, label), file=sys.stderr)
        sys.exit(2)

if PREFLIGHT:
    # ⚠⚠ Prove DELETE, not read. Listing proves only that the key can see the bucket, and a
    # read-only application key then sails through the preflight, the operator confirms, the
    # database is deleted, and every delete is refused — the "gutted index beside a full bucket"
    # this check exists to make unreachable. So: PUT a marker, DELETE it, and on a versioned bucket
    # clean up the version too, so the probe leaves nothing behind.
    for label, c, bucket, pfx in targets:
        # ⚠ The probe key must use THIS target's prefix, not the global one. The backups bucket is
        # deliberately unprefixed (pfx=''), so a probe built from PREFIX wrote it to
        # `prod/.reset-preflight` in a bucket whose keys all live at `relay-db/…` — proving delete
        # rights on a path the real run never touches, and leaving the marker somewhere unexpected.
        probe = pfx + '.reset-preflight'
        try:
            for page in c.get_paginator('list_objects_v2').paginate(Bucket=bucket, PaginationConfig={'MaxItems': 1}):
                pass
            # ⚠⚠ Probe the SAME listing the real run uses, or a key that can list objects but not
            # versions passes here and is refused after the database is gone.
            vpages = versioned_pages(c, bucket)
            put = c.put_object(Bucket=bucket, Key=probe, Body=b'reset-preflight')
            del_resp = c.delete_object(Bucket=bucket, Key=probe)
            # ⚠⚠ On a versioned bucket `delete_object(Key)` only writes a DELETE MARKER — it needs
            # s3:DeleteObject, while the real run deletes by VersionId and needs
            # s3:DeleteObjectVersion. Swallowing this exception meant the preflight passed on exactly
            # the permission the run requires. Let it propagate.
            # ⚠ And clean up BOTH the object version and the marker: the probe runs before the
            # prompts, so an operator who aborts must not leave a marker in a production bucket.
            for vid in (put.get('VersionId'), del_resp.get('VersionId')):
                if vid:
                    c.delete_object(Bucket=bucket, Key=probe, VersionId=vid)
            print('   preflight %-7s bucket=%-40s read+write OK%s%s'
                  % (label, bucket,
                     (' prefix=%s' % pfx) if pfx else '',
                     ' (versioned)' if vpages is not None else ''))
        except ListVersionsDenied as e:
            # Nothing was PUT on this path — the version probe runs before the marker.
            print('   ⚠⚠ preflight FAILED for %s: %s' % (label, e), file=sys.stderr)
            sys.exit(2)
        # ⚠ BaseException, not Exception: the preflight runs before the prompts and before the shell
        # arms its traps, so a Ctrl-C between the PUT and the version cleanup is a perfectly ordinary
        # way to leave `.reset-preflight` behind in a production bucket. KeyboardInterrupt and
        # SystemExit do not derive from Exception, so the sweep below would not have run for either.
        except BaseException as e:
            print('   ⚠⚠ preflight FAILED for %s bucket %r: %s'
                  % (label, bucket, e if str(e) else type(e).__name__), file=sys.stderr)
            # ⚠ Sweep the marker on the FAILURE path too. The delete probes above deliberately
            # propagate (that is how a key without s3:DeleteObjectVersion is caught), which means the
            # PUT can have succeeded and the cleanup not run — leaving `.reset-preflight` in a
            # production bucket for an operator who is about to abort at the prompt.
            if not sweep_probe(c, bucket, probe):
                print('      ⚠ could not confirm removal of s3://%s/%s — delete it by hand.'
                      % (bucket, probe), file=sys.stderr)
            sys.exit(2)
    print('   preflight OK — every bucket reachable AND deletable; nothing removed yet.')
    sys.exit(0)

bad = 0
for label, c, bucket, pfx in targets:
    try:
        bad += empty(label, c, bucket, pfx)
    except ListVersionsDenied as e:
        # ⚠ The byte-identical preflight should have caught this before anything was deleted, so
        # reaching it here means the credentials or the bucket policy changed mid-run — with the
        # database already gone. Say that in one line; a raw traceback is the worst thing to hand an
        # operator at this exact point, and it reads as a script bug rather than a refusal.
        print('   ⚠⚠ %s: %s' % (label, e), file=sys.stderr)
        bad += 1

if bad:
    print('   ⚠⚠ OBJECT STORAGE NOT EMPTY: %d bucket(s) still hold objects or refused a delete'
          % bad, file=sys.stderr)
    sys.exit(1)
PYEOF

# ⚠⚠ THESE THREE BODIES ARE QUOTED HEREDOCS, AND THAT IS THE POINT — not style. A hand-quoted remote
# body has two failure modes that no check on this file can see. One is TRUNCATION, which the
# REMOTE_END sentinel catches. The other is LOCAL EVALUATION, which it cannot: a *pair* of stray
# quotes truncates nothing and leaves the sentinel intact, but the region between them stops being
# quoted, so any `$(…)` there runs on the OPERATOR'S MACHINE while the string is being built and its
# output is baked into the body as a literal. Measured on the relay verification body: one line
# wrapped that way delivered `blobs=0` — this Mac's `find`, not the relay's — with argc=2, the
# sentinel present, `bash -n` clean, and the run exiting 0 on "verified: every relay table empty, 0
# blobs." over a directory it never counted. These bodies carry exactly the substitutions that
# matter (`sqlite3 .tables`, the blob `find`, the `psql` counts), so the class lands where the proof
# lives. `<<'"'"'REMOTE_EOF'"'"'` (quoted delimiter) has no closing-quote to stumble over and performs no
# expansion at all, which retires the class instead of detecting it. BUCKET_PY above already proves
# the pattern. ⚠ Keep the sentinel line inside each: run_ssh still requires it. Bodies that need a
# LOCAL value are heredocs too — it arrives as a `printf %q` assignment PREFIX (RELAY_COMPOSE_CMD,
# REARM_TIMERS), never interpolated into the body. Hand-quoting is NOT the sanctioned pattern for
# them; the few remaining inline one-liners use it only because they contain no `$(…)` at all.
# ⚠ `read -d ""` returns non-zero at EOF, hence the `|| true`.
# ⚠⚠ `IFS= read`, not bare `read`: with the default IFS, `read` strips leading whitespace from the
# value, so the first line of each body arrived DEDENTED — a silent mutation of a script you are
# about to run as root. It also strips the trailing newline, which is the only reason the REMOTE_END
# check passed at all; with `IFS=` that newline survives, so it is removed explicitly below rather
# than by accident.
IFS= read -r -d '' REMOTE_QUIESCE <<'REMOTE_EOF' || true
set -uo pipefail
      # ⚠⚠ `is-active` CANNOT see a running Type=oneshot without RemainAfterExit — which is what
      # all three of these units are. While ExecStart runs the state is 'activating', and is-active is
      # true only for 'active'/'reloading', so the old wait loop broke on its first iteration and the
      # assertion was empty whether or not a mirror was mid-run — then printed '(verified)'. Read
      # ActiveState directly and treat activating/deactivating as running.
      # ⚠⚠ BOTH helpers FAIL CLOSED, because an empty answer means two different things and only one
      # of them is safe: the unit does not exist, or systemctl could not answer at all (dbus
      # unreachable, systemctl not on PATH). Reading the second as 'not running' let the quiesce stop
      # nothing, print '(ActiveState verified)' and exit 0 with a mirror run genuinely in flight —
      # the same polarity mistake the Python side inverted for version listings. So: read the STATUS,
      # and treat an errored systemctl as 'yes, running' / 'yes, loaded'. The run then aborts at the
      # stop or at the assertion instead of wiping.
      running() { _v=$(systemctl show -p ActiveState --value "$1" 2>/dev/null) || return 0
        case "$_v" in active|activating|deactivating|reloading|'') return 0 ;; *) return 1 ;; esac; }
      # ⚠⚠ 'not loaded' is NOT a stop failure. The mirror pair and the backup pair are installed by
      # two SEPARATE manual procedures (deploy/scaleway-relay/README.md:150-154), not by cloud-init,
      # so a box with one and not the other is a normal state — and `systemctl stop` on a missing
      # unit exits 5, which the bare '|| exit 1' turned into 'could not quiesce … NOTHING has been
      # deleted' on a host with nothing wrong with it. Ask LoadState first, then let a real stop
      # failure abort. A not-found unit also reads ActiveState=inactive, so the assertion still holds.
      loaded() { _v=$(systemctl show -p LoadState --value "$1" 2>/dev/null) || return 0
        case "$_v" in loaded|'') return 0 ;; *) return 1 ;; esac; }
      # ⚠⚠ Name every timer that was ACTIVE before we touched it, so the restore re-arms exactly
      # those. Re-arming 'the two timers' unconditionally is wrong in the other direction: a timer an
      # operator had deliberately disabled comes back armed, and all three units are Persistent=true, so
      # `systemctl start` fires the missed elapse IMMEDIATELY rather than at the next schedule.
      for u in tenshu-blob-mirror tenshu-blob-mirror-dr tenshu-relay-backup; do
        if loaded $u.timer; then
          running $u.timer && echo "WASACTIVE $u.timer"
          systemctl stop $u.timer || exit 1
        fi
        # ⚠ No '|| true' on the unit stop: a stop that fails is exactly the case that must abort.
        loaded $u.service && { systemctl stop $u.service || exit 1; }
      done
      for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
        running tenshu-blob-mirror.service || running tenshu-blob-mirror-dr.service || running tenshu-relay-backup.service || break
        sleep 5
      done
      bad=''
      for u in tenshu-blob-mirror tenshu-blob-mirror-dr tenshu-relay-backup; do
        running $u.service && bad="$bad $u.service"
        running $u.timer   && bad="$bad $u.timer"
      done
      if [ -n "$bad" ]; then
        echo "   ⚠⚠ still running:$bad — refusing to wipe while a mirror can write" >&2
        exit 1
      fi
      echo '   blob-mirror, blob-mirror-dr + relay-backup timers AND units stopped (ActiveState verified).'
      #REMOTE-END
REMOTE_EOF
REMOTE_QUIESCE="${REMOTE_QUIESCE%$'\n'}"

IFS= read -r -d '' REMOTE_VERIFY_RELAY <<'REMOTE_EOF' || true
    set -euo pipefail
    if ! command -v sqlite3 >/dev/null 2>&1; then
      echo "   ⚠ sqlite3 not on the host — cannot verify the relay wipe"; exit 0
    fi
    db=/mnt/relay_data/relay.db
    bad=""
    # ⚠⚠ An ABSENT database passes this check vacuously, and that is the dangerous reading.
    # `sqlite3 /missing.db ".tables"` CREATES a 0-byte file, prints nothing and exits 0, so the
    # loop body never runs, `bad` stays empty, and a relay that never came back up — a crash-looping
    # container, a half-applied migration — reports "every relay table empty". The liveness curl
    # above is `|| echo`, a note rather than a gate, so nothing else catches it. Require the schema
    # to exist, and give the migrations a moment the way the commerce half already does.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      [ -s "$db" ] && [ -n "$(sqlite3 "$db" ".tables" 2>/dev/null)" ] && break
      sleep 3
    done
    if [ ! -s "$db" ] || [ -z "$(sqlite3 "$db" ".tables" 2>/dev/null)" ]; then
      echo "   ⚠⚠ RELAY DID NOT COME BACK: no schema at $db after the restart."
      echo "      The wipe happened; the relay is not serving. Check:"
      # ⚠⚠ The apostrophes in the hint below are plain apostrophes, and they are safe ONLY because
      # this body is a quoted heredoc. In a hand-quoted literal they end the literal: the rest of
      # the script becomes extra words on the local run_ssh line, an `&&` in the leftovers makes it a
      # local AND-list (which set -e exempts), the remote body arrives truncated and dies of a syntax
      # error, and the run prints "Server-side reset complete." exit 0 having never verified the
      # wipe — with `bash -n` clean throughout. Never move this body back into a quoted string.
      echo "      ssh root@<host> 'cd /opt/tenshu/repo && docker compose … logs --tail=100 relay'"
      exit 1
    fi
    for t in $(sqlite3 "$db" ".tables"); do
      # Bookkeeping, non-empty on a FRESH database: the ledger, the sequence row, and the
      # `schema_baseline` marker (one row, `cut = '2026-11-reset'`). Skipping the marker is not
      # optional — a fresh relay has it, and the wipe would read as "NOT EMPTY" and exit after the rm. (Commerce's check names its tables explicitly, so its marker needs nothing.)
      case "$t" in schema_version|relay_seq|schema_baseline) continue ;; esac
      n=$(sqlite3 "$db" "select count(*) from $t;")
      [ "$n" = "0" ] || bad="$bad $t=$n"
    done
    blobs=$(find /mnt/relay_data/relay-blobs -type f 2>/dev/null | wc -l | tr -d " ")
    [ "$blobs" = "0" ] || bad="$bad relay-blobs=$blobs files"
    if [ -n "$bad" ]; then
      echo "   ⚠⚠ RELAY IS NOT EMPTY:$bad"; exit 1
    fi
    echo "   verified: every relay table empty, 0 blobs."
    #REMOTE-END
REMOTE_EOF
REMOTE_VERIFY_RELAY="${REMOTE_VERIFY_RELAY%$'\n'}"

IFS= read -r -d '' REMOTE_VERIFY_COMMERCE <<'REMOTE_EOF' || true
      set -euo pipefail
      cd /opt/tenshu/repo || exit 1
      psql() { $COMMERCE_COMPOSE_CMD exec -T db psql -tAqU commerce -d commerce "$@"; }
      for _ in 1 2 3 4 5 6 7 8 9 10; do
        if psql -c "select 1 from account limit 1;" >/dev/null 2>&1; then break; fi
        sleep 3
      done
      bad=""
      for t in account account_owner session entitlement_history acceptance invoice credit_note \
               stripe_event email_verification marketing_consent; do
        q="select count(*) from $t;"
        # Commerce writes the review sign-in fingerprints row at every start (ReviewSignIn.FINGERPRINTS_KEY,
        # a config record, not user data), so a freshly started empty commerce always holds it.
        [ "$t" = email_verification ] && \
          q="select count(*) from email_verification where email_normalised <> 'review-signin-fingerprints:';"
        n=$(psql -c "$q" 2>/dev/null || echo missing)
        case "$n" in
          0) ;;
          missing) bad="$bad $t=absent" ;;
          *) bad="$bad $t=$n" ;;
        esac
      done
      if [ -n "$bad" ]; then
        echo "   ⚠⚠ COMMERCE IS NOT EMPTY (or migrations have not finished):$bad"; exit 1
      fi
      echo "   verified: every commerce account/session/entitlement table empty."
      echo "   (redemption_code=$(psql -c "select count(*) from redemption_code;") is re-seeded from deploy.env, not a survivor.)"
      #REMOTE-END
REMOTE_EOF
REMOTE_VERIFY_COMMERCE="${REMOTE_VERIFY_COMMERCE%$'\n'}"

# ⚠⚠ The last three bodies that mixed a LOCAL ${…} with an escaped REMOTE \$(…) on the same line —
# the exact mixture the conversion above retired, and these were the worst two places to leave it:
# the gate that decides whether the object-storage refusal fires, and the restore that runs after
# something has already gone wrong. Local values now arrive as a one-line assignment PREFIX built
# with `printf %q`, so the body itself never needs an escape and can stay a quoted heredoc.
IFS= read -r -d '' REMOTE_S3_DETECT <<'REMOTE_EOF' || true
cd /opt/tenshu/repo || exit 3
cfg="$($RELAY_COMPOSE_CMD config 2>/dev/null)" || exit 3
[ -n "$cfg" ] || exit 3
# ⚠⚠ ONLY three answers are allowed: 2 = a bucket named on stdout, 0 = the key is present and its
# value is explicitly empty, 4 = anything else. "Anything else" must never collapse into 0, because 0
# means "no object storage" and the caller then wipes the volume and reports a clean reset with every
# ciphertext still in the bucket.
#
# ⚠⚠ An earlier revision asserted `grep -q 'S3_BUCKET'` — a SUBSTRING — while the verdict came from an
# ANCHORED sed. Those two disagree, so the canary could be satisfied by a line the parser cannot read,
# and by a DIFFERENT key entirely: `BACKUP_S3_BUCKET` is a real key in deploy.env
# (deploy/scaleway-relay/backup-relay-db.sh). Measured against fake renders, it still answered
# "no bucket" and proceeded for a list-form `- S3_BUCKET=…`, a value on the following line, a quoted
# value containing a space, a column-0 key, and a render carrying only BACKUP_S3_BUCKET — two of which
# the commit adding it claimed to have closed. Assert the EXTRACTION, not the substring.
#
# Today sync-relay-server/docker-compose.yml emits `S3_BUCKET: "${S3_BUCKET:-}"` under `environment:`,
# so the parsed shape is the real one and a failure here is genuine drift worth stopping for.

# The list form is a shape this parser cannot read at all — refuse rather than miss the key.
printf '%s\n' "$cfg" | grep -qE '^[[:space:]]*-[[:space:]]*S3_BUCKET=' && exit 4
# The key, in the mapping shape, at any indent (including column 0).
kline="$(printf '%s\n' "$cfg" | grep -E '^[[:space:]]*S3_BUCKET:' | head -1)" || true
[ -n "$kline" ] || exit 4
# Everything after the colon, trimmed. ⚠ A BARE `S3_BUCKET:` is not "empty" — it is YAML for "the
# value is on a following line", which this parser does not read. Refuse it; compose does not emit it.
v="$(printf '%s\n' "$kline" | sed -E 's/^[[:space:]]*S3_BUCKET:[[:space:]]*//; s/[[:space:]]*$//')"
case "$v" in
  '')            exit 4 ;;            # value elsewhere, or a shape we do not parse
  '""'|"''")     exit 0 ;;            # explicitly empty: genuinely no object storage
  '>'*|'|'*|'&'*|'*'*) exit 4 ;;      # block scalar / anchor / alias — do not guess
esac
# Unquote a simple scalar.
case "$v" in
  '"'*'"') b="${v#\"}"; b="${b%\"}" ;;
  "'"*"'") b="${v#\'}"; b="${b%\'}" ;;
  *)       b="$v" ;;
esac
# A bucket name cannot contain whitespace; if we produced one, we misparsed.
case "$b" in *[[:space:]]*) exit 4 ;; esac
[ -n "$b" ] || exit 4
printf '%s' "$b"
exit 2
#REMOTE-END
REMOTE_EOF
REMOTE_S3_DETECT="${REMOTE_S3_DETECT%$'\n'}"

# ⚠⚠ Liveness is the app's /health THROUGH Caddy, and only a 200 counts. `curl http://localhost/` proved
# nothing: Caddy serves only {$RELAY_DOMAIN}/{$COMMERCE_DOMAIN}, so a request for `localhost` never
# reached the app, and the relay's `/` is a static string that cannot fail anyway (Application.kt).
# `--resolve` pins the public name to this box, so the probe uses the real site block and certificate.
# A function prelude, not a body: it is prepended to the bodies that need it and has no sentinel.
# $1 = the deploy.env key naming the public domain (RELAY_DOMAIN / COMMERCE_DOMAIN).
IFS= read -r -d '' REMOTE_HEALTH_FN <<'REMOTE_EOF' || true
health_200() {
  local d code
  d="$( . /opt/tenshu/deploy.env >/dev/null 2>&1; printf %s "${!1:-}" )"
  [ -n "$d" ] || { echo "   ⚠ $1 is not set in /opt/tenshu/deploy.env — cannot probe /health" >&2; return 1; }
  code="$(curl -sS -o /dev/null -m 10 -w '%{http_code}' --resolve "$d:443:127.0.0.1" "https://$d/health" 2>/dev/null)"
  [ "$code" = 200 ]
}
wait_health_200() {
  local i
  for i in $(seq 1 "${2:-30}"); do health_200 "$1" && return 0; sleep 2; done
  return 1
}
REMOTE_EOF

IFS= read -r -d '' REMOTE_RESTORE_RELAY <<'REMOTE_EOF' || true
set -uo pipefail
cd /opt/tenshu/repo || exit 1
$RELAY_COMPOSE_CMD up -d relay || exit 1
wait_health_200 RELAY_DOMAIN 30 || { echo '   relay /health is not answering 200' >&2; exit 1; }
rearm_rc=0
for t in $REARM_TIMERS; do
  systemctl start "$t" || rearm_rc=1
  [ "$(systemctl is-active "$t" 2>/dev/null)" = active ] || rearm_rc=1
done
for t in tenshu-blob-mirror tenshu-blob-mirror-dr tenshu-relay-backup; do
  printf '   %s.timer: %s\n' "$t" "$(systemctl is-active "$t.timer" 2>/dev/null || echo unknown)"
done
echo '   relay is answering (/health 200).'
[ "$rearm_rc" = 0 ] || { echo '   ⚠⚠ a timer did not start — see above' >&2; exit 1; }
#REMOTE-END
REMOTE_EOF
REMOTE_RESTORE_RELAY="${REMOTE_HEALTH_FN}${REMOTE_RESTORE_RELAY%$'\n'}"

# The trap's commerce restore, for a plane that was stopped but not wiped, or switched to the new tag.
IFS= read -r -d '' REMOTE_RESTORE_COMMERCE <<'REMOTE_EOF' || true
set -uo pipefail
cd /opt/tenshu/repo || exit 1
$COMMERCE_COMPOSE_CMD up -d || exit 1
wait_health_200 COMMERCE_DOMAIN 30 || { echo '   commerce /health is not answering 200' >&2; exit 1; }
echo '   commerce is answering (/health 200).'
#REMOTE-END
REMOTE_EOF
REMOTE_RESTORE_COMMERCE="${REMOTE_HEALTH_FN}${REMOTE_RESTORE_COMMERCE%$'\n'}"

# The post-start liveness check of the wipe itself. Prefix: DOMAIN_VAR, LABEL.
IFS= read -r -d '' REMOTE_LIVENESS <<'REMOTE_EOF' || true
set -uo pipefail
if wait_health_200 "$DOMAIN_VAR" 30; then
  echo "   $LABEL is up (empty): /health 200."
else
  echo "   ⚠⚠ $LABEL: /health did not answer 200 within 60 s." >&2
  exit 1
fi
#REMOTE-END
REMOTE_EOF
REMOTE_LIVENESS="${REMOTE_HEALTH_FN}${REMOTE_LIVENESS%$'\n'}"

# ⚠ Fails when any timer did not start: a reset that reports success with the nightly backup and the
# mirror still disarmed is a silent loss of both safety nets.
IFS= read -r -d '' REMOTE_REARM <<'REMOTE_EOF' || true
rc=0
for t in $REARM_TIMERS; do
  systemctl start "$t" || rc=1
  st="$(systemctl is-active "$t" 2>/dev/null || true)"
  printf '   %s: %s\n' "$t" "${st:-unknown}"
  [ "$st" = active ] || rc=1
done
exit "$rc"
#REMOTE-END
REMOTE_EOF
REMOTE_REARM="${REMOTE_REARM%$'\n'}"

# ── Image switch + schema post-check (the production-reset order) ─────────────────────────────────
# ⚠⚠ Why the switch exists at all: each runner applies only migrations ABOVE `max(schema_version)`.
# Restart the OLD image on a wiped volume and it writes its own ledger (pre-squash: up to 25); the new
# image then finds every one of its versions "already applied" and creates nothing — the service
# starts, answers /health, and fails on the first query against a table that was never made. So the
# tag is switched while the service is down, and the result is checked against the version the image
# is supposed to produce.
#
# Prefix variables (printf %q): ENV_FILE, TAG_VAR, IMAGE_REPO, WANT_TAG (a tag, or `keep`), COMPOSE_CMD,
# SERVICE.
# Answers on stdout: `CURRENT\t<tag>` and `TARGET\t<tag>`. Exit 3 = refuse.
IFS= read -r -d '' REMOTE_IMAGE_PREFLIGHT <<'REMOTE_EOF' || true
set -uo pipefail
[ -f "$ENV_FILE" ] || { echo "   ⚠⚠ no $ENV_FILE on this host." >&2; exit 3; }
n="$(grep -c "^${TAG_VAR}=" "$ENV_FILE" || true)"
if [ "$n" != "1" ]; then
  echo "   ⚠⚠ $ENV_FILE has $n ${TAG_VAR}= lines, not exactly one — the switch cannot be made safely." >&2
  exit 3
fi
cur="$(grep "^${TAG_VAR}=" "$ENV_FILE" | cut -d= -f2- | tr -d "\"' ")"
[ -n "$cur" ] || cur=latest   # the compose default for an empty value
# The post-start check probes https://<domain>/health. Find out NOW, not after the wipe, if it cannot.
case "$SERVICE" in relay) dvar=RELAY_DOMAIN ;; *) dvar=COMMERCE_DOMAIN ;; esac
grep -q "^${dvar}=." "$ENV_FILE" \
  || { echo "   ⚠⚠ $ENV_FILE has no ${dvar}= — the post-start /health check could not run." >&2; exit 3; }
command -v curl >/dev/null 2>&1 || { echo "   ⚠⚠ no curl on this host — the /health check could not run." >&2; exit 3; }
tag="$WANT_TAG"
[ "$tag" = keep ] && tag="$cur"
# ⚠ Prove now, while nothing is deleted, that deploy.env is what decides the image — the switch below
# runs after the wipe, and finding out THEN that compose cannot list images or that something else
# overrides the tag would leave a wiped service with no safe image to start.
cd /opt/tenshu/repo || exit 3
# ⚠⚠ The live container must belong to THIS compose project, or `-p` names a second, empty stack and
# every stop/up below acts on nothing while the real service keeps running on the old volume.
if [ -z "$($COMPOSE_CMD ps -aq "$SERVICE" 2>/dev/null)" ]; then
  echo "   ⚠⚠ no '$SERVICE' container in this compose project — the project name does not match the" >&2
  echo "      running stack (docker ps --format '{{.Names}}'). Refusing: acting on it would fork the stack." >&2
  exit 3
fi
rendered="$($COMPOSE_CMD config --images 2>/dev/null | grep -F "$IMAGE_REPO:" | sort -u)"
if [ "$rendered" != "$IMAGE_REPO:$cur" ]; then
  echo "   ⚠⚠ compose renders '${rendered:-nothing}' but $ENV_FILE says ${TAG_VAR}=$cur — the tag switch" >&2
  echo "      would not take (an override, or a compose without 'config --images')." >&2
  exit 3
fi
if ! docker image inspect "$IMAGE_REPO:$tag" >/dev/null 2>&1; then
  if ! docker pull -q "$IMAGE_REPO:$tag" >/dev/null 2>&1; then
    echo "   ⚠⚠ $IMAGE_REPO:$tag is neither on this box nor pullable. Ship it first" >&2
    echo "      (deploy/build-relay-image.sh / build-commerce-image.sh --ship <host>)." >&2
    exit 3
  fi
fi
printf 'CURRENT\t%s\n' "$cur"
printf 'TARGET\t%s\n' "$tag"
#REMOTE-END
REMOTE_EOF
REMOTE_IMAGE_PREFLIGHT="${REMOTE_IMAGE_PREFLIGHT%$'\n'}"

# Prefix variables: ENV_FILE, TAG_VAR, IMAGE_REPO, TAG, COMPOSE_CMD. Run while the service is DOWN.
# ⚠ Asserts what compose will START, not what the file says: a shell-exported tag or a second env
# file would win over deploy.env, and that is exactly the "old image recreates the old ledger" trap.
IFS= read -r -d '' REMOTE_IMAGE_SWITCH <<'REMOTE_EOF' || true
set -euo pipefail
cd /opt/tenshu/repo
cur="$(grep "^${TAG_VAR}=" "$ENV_FILE" | cut -d= -f2-)"
if [ "$cur" != "$TAG" ]; then
  cp -p "$ENV_FILE" "$ENV_FILE.pre-reset.$(date -u +%Y%m%dT%H%M%SZ)"
  sed -i "s|^${TAG_VAR}=.*|${TAG_VAR}=${TAG}|" "$ENV_FILE"
fi
got="$(grep "^${TAG_VAR}=" "$ENV_FILE" | cut -d= -f2-)"
if [ "$got" != "$TAG" ]; then
  echo "   ⚠⚠ ${TAG_VAR} reads '$got' after the switch, not '$TAG'. Not restarting." >&2
  exit 1
fi
img="$($COMPOSE_CMD config --images 2>/dev/null | grep -F "$IMAGE_REPO:" | sort -u)"
if [ "$img" != "$IMAGE_REPO:$TAG" ]; then
  echo "   ⚠⚠ compose would start '$img', not '$IMAGE_REPO:$TAG' — something overrides deploy.env." >&2
  echo "      Not restarting onto it." >&2
  exit 1
fi
echo "   ${TAG_VAR}=${TAG} — compose renders $img"
#REMOTE-END
REMOTE_EOF
REMOTE_IMAGE_SWITCH="${REMOTE_IMAGE_SWITCH%$'\n'}"

# Prefix variable: EXPECT. Reads the ledger read-only through python3's sqlite3 (always on the box;
# the sqlite3 CLI is not, and this check must not degrade to a note the way the table count does).
IFS= read -r -d '' REMOTE_SCHEMA_RELAY <<'REMOTE_EOF' || true
set -uo pipefail
# Retried: the runner applies one migration per transaction, so an early read can see a ledger that is
# still climbing.
for _ in 1 2 3 4 5 6; do
  got="$(python3 -c "import sqlite3; c = sqlite3.connect('file:/mnt/relay_data/relay.db?mode=ro', uri=True); print(c.execute('select coalesce(max(version), 0) from schema_version').fetchone()[0])" 2>/dev/null)" || got=""
  [ "$got" = "$EXPECT" ] && break
  sleep 3
done
if [ "$got" != "$EXPECT" ]; then
  echo "   ⚠⚠ RELAY SCHEMA MISMATCH: max(schema_version) = ${got:-unreadable}, expected $EXPECT."
  exit 1
fi
echo "   verified: relay max(schema_version) = $got, as the image's migration list says."
#REMOTE-END
REMOTE_EOF
REMOTE_SCHEMA_RELAY="${REMOTE_SCHEMA_RELAY%$'\n'}"

IFS= read -r -d '' REMOTE_SCHEMA_COMMERCE <<'REMOTE_EOF' || true
set -uo pipefail
cd /opt/tenshu/repo || exit 1
for _ in 1 2 3 4 5 6; do
  got="$($COMMERCE_COMPOSE_CMD exec -T db psql -tAqU commerce -d commerce -c 'select coalesce(max(version), 0) from schema_version;' 2>/dev/null | tr -d '[:space:]')" || got=""
  [ "$got" = "$EXPECT" ] && break
  sleep 3
done
if [ "$got" != "$EXPECT" ]; then
  echo "   ⚠⚠ COMMERCE SCHEMA MISMATCH: max(schema_version) = ${got:-unreadable}, expected $EXPECT."
  exit 1
fi
echo "   verified: commerce max(schema_version) = $got, as the image's migration list says."
#REMOTE-END
REMOTE_EOF
REMOTE_SCHEMA_COMMERCE="${REMOTE_SCHEMA_COMMERCE%$'\n'}"

# ⚠⚠ Statutory records, checked before anything is destroyed. Commerce is its own merchant of record:
# `invoice` and `credit_note` are the statutory ledger, and a LIVE Stripe account holds its own
# invoices and credit notes besides. A wipe that loses them is not a test-data question. Production
# has none today — this asserts that instead of assuming it. The Stripe key never leaves the box: it
# reaches curl as a header on stdin, not on a command line.
# Answers: `LOCAL\t<invoices>\t<credit notes>` and `STRIPE\t<live|test|none>\t<invoices>\t<credit notes>`.
# ⚠ Every curl failure is folded into exit 3, so curl's own status (97 is CURLE_PROXY) never becomes
# this capturing site's — see REMOTE_BUG_RC.
IFS= read -r -d '' REMOTE_STATUTORY <<'REMOTE_EOF' || true
set -uo pipefail
# The paths are overridable only for CommerceStatutoryProbeTest; on a box they are the defaults.
ENV_FILE="${ENV_FILE:-/opt/tenshu/deploy.env}"
REPO_DIR="${REPO_DIR:-/opt/tenshu/repo}"
PGDATA_DIR="${PGDATA_DIR:-/mnt/commerce_data/pgdata}"
set -a; . "$ENV_FILE"; set +a
cd "$REPO_DIR" || exit 3
q() { $COMMERCE_COMPOSE_CMD exec -T db psql -tAqU commerce -d commerce -c "$1" 2>/dev/null | tr -d '[:space:]'; }
# ⚠⚠ A re-run after an interrupted reset finds commerce in a state the first run never saw, and the
# runbook's answer to every failure is "re-run". So all three must count, not refuse:
#   pgdata empty (already wiped)  → there is no cluster, so no rows. Nothing is started.
#   pgdata present, `db` stopped  → start `db` ALONE (Postgres on its own data; the commerce app stays
#                                   down) and count. The wipe stops it again right after.
#   no invoice/credit_note table  → a fresh cluster whose migrations never ran: no table, no rows.
# Anything unreadable still refuses.
count_if() {
  case "$(q "select to_regclass('public.$1') is not null;")" in
    t) q "select count(*) from $1;" ;;
    f) echo 0 ;;
    *) echo "" ;;
  esac
}
if [ ! -s "$PGDATA_DIR/PG_VERSION" ]; then
  echo "   (commerce pgdata is empty — already wiped; no statutory rows can exist)" >&2
  inv=0; cn=0
else
  if [ -z "$($COMMERCE_COMPOSE_CMD ps -q --status running db 2>/dev/null)" ]; then
    echo "   (commerce db is stopped — starting it alone to count statutory rows)" >&2
    $COMMERCE_COMPOSE_CMD up -d db >/dev/null 2>&1 \
      || { echo "   ⚠⚠ could not start commerce db to count its statutory rows — refusing." >&2; exit 3; }
  fi
  for _ in $(seq 1 30); do
    $COMMERCE_COMPOSE_CMD exec -T db pg_isready -U commerce -d commerce >/dev/null 2>&1 && break
    sleep 2
  done
  inv="$(count_if invoice)"
  cn="$(count_if credit_note)"
fi
for v in "$inv" "$cn"; do
  case "$v" in
    ''|*[!0-9]*) echo "   ⚠⚠ cannot count commerce invoice/credit_note rows — refusing to assume there are none." >&2; exit 3 ;;
  esac
done
printf 'LOCAL\t%s\t%s\n' "$inv" "$cn"
key="${STRIPE_SECRET_KEY:-}"
stripe_count() {
  printf 'Authorization: Bearer %s\n' "$key" \
    | curl -fsS -m 20 -H @- "https://api.stripe.com/v1/$1?limit=1" \
    | python3 -c 'import json, sys; print(len(json.load(sys.stdin)["data"]))'
}
case "$key" in
  sk_live_*|rk_live_*)
    si="$(stripe_count invoices)" || { echo "   ⚠⚠ live Stripe key, but /v1/invoices could not be read — refusing to assume none." >&2; exit 3; }
    sc="$(stripe_count credit_notes)" || { echo "   ⚠⚠ live Stripe key, but /v1/credit_notes could not be read — refusing to assume none." >&2; exit 3; }
    printf 'STRIPE\tlive\t%s\t%s\n' "$si" "$sc" ;;
  '') printf 'STRIPE\tnone\t0\t0\n' ;;
  *)  printf 'STRIPE\ttest\t0\t0\n' ;;
esac
#REMOTE-END
REMOTE_EOF
REMOTE_STATUTORY="${REMOTE_STATUTORY%$'\n'}"

# The expected max(schema_version) of an image, from its migration list at the tag's commit.
# $1 = repo path of the *Database.kt, $2 = tag. Prints nothing (status 1) when it cannot tell.
# The parse lives in deploy/lib/migration-max-version.sh so a JVM test can pin it against the real
# runners (Relay/CommerceMigrationVersionDerivationTest) — an anchored regex here once read the relay as
# 3 while the image wrote 4.
derive_schema() {
  local sha
  sha="$(git -C "$REPO_ROOT" rev-parse --verify --quiet "$2^{commit}" 2>/dev/null)" || return 1
  git -C "$REPO_ROOT" show "$sha:$1" 2>/dev/null | bash "$REPO_ROOT/deploy/lib/migration-max-version.sh"
}

# $1 = host, $2 = TAG_VAR, $3 = image repo, $4 = wanted tag (or keep), $5 = compose command,
# $6 = compose service. Sets PF_CURRENT / PF_TARGET.
image_preflight() {
  local out rc=0
  out="$(run_ssh "root@$1" "ENV_FILE=/opt/tenshu/deploy.env TAG_VAR=$(printf %q "$2") IMAGE_REPO=$(printf %q "$3") WANT_TAG=$(printf %q "$4") COMPOSE_CMD=$(printf %q "$5") SERVICE=$(printf %q "$6")
${REMOTE_IMAGE_PREFLIGHT}")" || rc=$?
  if [[ $rc -eq $REMOTE_BUG_RC ]]; then
    echo "error: refusing — see the internal error above. $1 was not contacted; NOTHING deleted." >&2
    exit 1
  fi
  if [[ $rc -ne 0 ]]; then
    echo "error: image preflight on $1 failed (status $rc) — NOTHING has been deleted." >&2
    exit 1
  fi
  PF_CURRENT="$(printf '%s\n' "$out" | tr -d '\r' | awk -F'\t' '$1=="CURRENT"{print $2}')"
  PF_TARGET="$(printf '%s\n' "$out" | tr -d '\r' | awk -F'\t' '$1=="TARGET"{print $2}')"
  [[ -n "$PF_TARGET" ]] || { echo "error: image preflight on $1 named no target tag — refusing." >&2; exit 1; }
}

# $1 = host, $2 = 1 for preflight (no deletes), 0 for the real run, $3 = RESET_MODE (default relay).
run_bucket_python() {
  local host="$1" preflight="$2" mode="${3:-relay}"
  # shellcheck disable=SC2087  # the body is a literal we control, not interpolated remote input
  printf '%s\n' "$BUCKET_PY" | run_ssh "root@${host}" "set -euo pipefail
    set -a; . /opt/tenshu/deploy.env; set +a
    export WANT_BACKUPS='${EMPTY_BACKUPS}' PREFLIGHT='${preflight}' RESET_MODE='${mode}'
    python3 -
    ${REMOTE_END}"
}

# ── --backups-only: purge the rollback, wipe nothing ──────────────────────────────────────────────
# Same Python, same preflight-then-run shape, scoped by RESET_MODE=backups-only to BACKUP_S3_BUCKET of
# the host it runs on. ⚠ A backup key may be deliberately put-only; the preflight proves delete before
# the prompt, and on refusal the bucket has to be emptied with the module's own credentials (runbook).
if [[ $BACKUPS_ONLY -eq 1 ]]; then
  BO_HOSTS=()
  BO_NAMES=()
  [[ -n "$RELAY_HOST" ]] && { BO_HOSTS+=("$RELAY_HOST"); BO_NAMES+=("$RELAY_BACKUP_BUCKET"); }
  [[ -n "$COMMERCE_HOST" ]] && { BO_HOSTS+=("$COMMERCE_HOST"); BO_NAMES+=("$COMMERCE_BACKUP_BUCKET"); }
  for i in "${!BO_HOSTS[@]}"; do
    h="${BO_HOSTS[$i]}"; want="${BO_NAMES[$i]}"
    echo "Preflighting BACKUP_S3_BUCKET on ${h} (nothing is deleted by this step) …"
    bo_rc=0
    BO_OUT="$(run_bucket_python "$h" 1 backups-only 2>&1)" || bo_rc=$?
    printf '%s\n' "$BO_OUT" | grep -v '^TARGET	' || true
    if [[ $bo_rc -eq $REMOTE_BUG_RC ]]; then
      echo "error: refusing — see the internal error above. NOTHING has been deleted." >&2
      exit 1
    fi
    if [[ $bo_rc -ne 0 ]]; then
      echo "error: backup-bucket preflight on ${h} failed — NOTHING has been deleted." >&2
      exit 1
    fi
    got="$(printf '%s\n' "$BO_OUT" | tr -d '\r' | awk -F'\t' '$1=="TARGET" && $2=="backups"{print $3}' | head -1)"
    if [[ "$got" != "$want" ]]; then
      echo "error: ${h}'s BACKUP_S3_BUCKET is '${got}', not '${want}' — the interlock does not match." >&2
      exit 1
    fi
  done
  echo
  echo "About to EMPTY these backup buckets (and nothing else):"
  for i in "${!BO_HOSTS[@]}"; do echo "  ${BO_HOSTS[$i]} → ${BO_NAMES[$i]}"; done
  echo "⚠⚠ This destroys the only rollback for the reset. Do it only after the post-reset verification."
  # ⚠⚠ RESET_YES does NOT apply here: a blanket yes is how the rollback gets purged by an automation
  # that meant the wipe. The non-interactive form must NAME every bucket: RESET_YES_BACKUPS=<a>,<b>.
  for i in "${!BO_HOSTS[@]}"; do
    if [[ -n "${RESET_YES_BACKUPS:-}" ]]; then
      case ",${RESET_YES_BACKUPS}," in
        *",${BO_NAMES[$i]},"*) ;;
        *) echo "Aborted — RESET_YES_BACKUPS does not name '${BO_NAMES[$i]}'." >&2; exit 1 ;;
      esac
    else
      typed_b=""
      read -r -p "Type the bucket '${BO_NAMES[$i]}' to confirm: " typed_b || true
      [[ "$typed_b" == "${BO_NAMES[$i]}" ]] || { echo "Aborted — bucket name did not match (RESET_YES does not apply to --backups-only)." >&2; exit 1; }
    fi
  done
  for i in "${!BO_HOSTS[@]}"; do
    echo "==> emptying ${BO_NAMES[$i]} on ${BO_HOSTS[$i]} …"
    run_bucket_python "${BO_HOSTS[$i]}" 0 backups-only
  done
  echo
  echo "Backup buckets emptied. The next nightly run starts the post-reset series."
  exit 0
fi

# ⚠⚠ Refuse an object-storage relay. With S3_* set, content blobs live in a bucket, not in
# /mnt/relay_data/relay-blobs. This script would wipe the index, leave every ciphertext in the bucket
# and still report "0 blobs". Production runs that way. Empty the bucket
# deliberately and separately (and never the production one), or reset a test relay without S3_*.
# ⚠ Fail CLOSED: only a config that rendered AND has no S3_BUCKET may proceed. An ssh or compose
#   failure must never read as "no bucket". The remote side answers with a distinct status (0 = no
#   bucket, 2 = bucket, 3 = config did not render) and keeps the rendered secrets on the box.
RELAY_S3_BUCKET=""
if [[ -n "$RELAY_HOST" ]]; then
  s3_rc=0
  # ⚠ The bucket NAME comes back on stdout (names are not secrets; keys stay on the box and are
  #   never rendered here). Status still carries the verdict so a name we fail to parse cannot read
  #   as "no bucket".
  RELAY_S3_BUCKET="$(run_ssh "root@${RELAY_HOST}" \
    "RELAY_COMPOSE_CMD=$(printf %q "$RELAY_COMPOSE")
${REMOTE_S3_DETECT}")" || s3_rc=$?

  if [[ $s3_rc -eq 2 ]]; then
    # An object-storage relay. Proceed ONLY when both the opt-in and the interlock are present.
    if [[ $EMPTY_BUCKETS -ne 1 ]]; then
      echo "error: ${RELAY_HOST}'s relay stores blobs in object storage (S3_BUCKET is set)." >&2
      echo "       Wiping only the volume would strand every bucket object and still report a clean" >&2
      echo "       reset. Re-run with --empty-buckets --bucket <name> to empty it deliberately," >&2
      echo "       or reset a test relay without S3_*." >&2
      exit 1
    fi
    if [[ -z "$BUCKET_NAMED" ]]; then
      echo "error: --empty-buckets needs --bucket <name> naming the bucket it will empty." >&2
      echo "       The relay reports S3_BUCKET='${RELAY_S3_BUCKET}'." >&2
      exit 1
    fi
    if [[ "$BUCKET_NAMED" != "$RELAY_S3_BUCKET" ]]; then
      echo "error: --bucket '${BUCKET_NAMED}' does not match the relay's S3_BUCKET" >&2
      echo "       ('${RELAY_S3_BUCKET}'). Refusing — the interlock exists so a destructive step" >&2
      echo "       cannot land on a bucket you did not mean to name." >&2
      exit 1
    fi
  elif [[ $s3_rc -eq $REMOTE_BUG_RC ]]; then
    # ⚠ A LOCAL bug in this file, not a sick relay. `exit` inside `$(…)` only leaves the subshell, so
    # without this the operator was told "could not render the relay config (status 1)" and sent to
    # diagnose a box with nothing wrong with it. run_ssh has already printed the real cause.
    echo "error: refusing — see the internal error above. The relay was not contacted." >&2
    exit 1
  elif [[ $s3_rc -eq 4 ]]; then
    echo "error: ${RELAY_HOST}'s rendered relay config does not mention S3_BUCKET at all, so this" >&2
    echo "       script cannot tell whether the relay uses object storage. Refusing rather than" >&2
    echo "       guessing 'no bucket' — that guess wipes the index and strands every ciphertext." >&2
    echo "       Check: ssh root@${RELAY_HOST} 'cd /opt/tenshu/repo && ${RELAY_COMPOSE} config | grep -i s3'" >&2
    exit 1
  elif [[ $s3_rc -ne 0 ]]; then
    echo "error: could not render ${RELAY_HOST}'s relay config (status ${s3_rc}), so an object-storage" >&2
    echo "       relay cannot be ruled out. Refusing rather than guessing." >&2
    exit 1
  else
    # No bucket. Opting in is then a mistake worth catching rather than silently ignoring.
    if [[ $EMPTY_BUCKETS -eq 1 || $EMPTY_BACKUPS -eq 1 ]]; then
      echo "error: ${RELAY_HOST}'s relay has no S3_BUCKET — there is no bucket to empty." >&2
      echo "       Drop --empty-buckets/--empty-backups; the volume wipe covers this topology." >&2
      exit 1
    fi
  fi
fi

# ⚠⚠ PREFLIGHT object storage BEFORE the summary and the prompts — nothing is deleted yet, the relay
# is still up, and the database still exists. A missing S3_ACCESS_KEY or a wrong region discovered
# *after* the volume wipe leaves a gutted index beside a full bucket: the stranded-ciphertext
# half-state --empty-buckets exists to prevent. ⚠ It also belongs before the confirmations so you are
# not asked to type a bucket name for a bucket this host cannot actually reach.
if [[ $EMPTY_BUCKETS -eq 1 ]]; then
  echo "Preflighting object storage on ${RELAY_HOST} (nothing is deleted by this step) …"
  # ⚠ THE THIRD capturing site. The REMOTE_BUG_RC arm was added at the other two and missed here, so a
  # local quoting bug in run_bucket_python's body still reported "Fix the relay's S3 settings" — the
  # send-the-operator-to-a-healthy-box failure that status exists to retire.
  preflight_rc=0
  PREFLIGHT_OUT="$(run_bucket_python "$RELAY_HOST" 1 2>&1)" || preflight_rc=$?
  if [[ $preflight_rc -eq $REMOTE_BUG_RC ]]; then
    printf '%s\n' "$PREFLIGHT_OUT" >&2
    echo "error: refusing — see the internal error above. NOTHING has been deleted, and the relay's" >&2
    echo "       S3 settings are not implicated." >&2
    exit 1
  fi
  if [[ $preflight_rc -ne 0 ]]; then
    printf '%s\n' "$PREFLIGHT_OUT" >&2
    echo "error: bucket preflight failed — NOTHING has been deleted. Fix the relay's S3 settings" >&2
    echo "       in /opt/tenshu/deploy.env (or drop --empty-buckets) and re-run." >&2
    exit 1
  fi
  printf '%s\n' "$PREFLIGHT_OUT" | grep -v '^TARGET	' || true
  # ⚠⚠ Every bucket the run will empty, from the run's OWN target list — not just the one `--bucket`
  # named. The interlock covered the live bucket only; the mirror and the snapshot bucket came from
  # the host's deploy.env and were emptied with no name check, so a mis-edited BLOB_MIRROR_BUCKET
  # pointing at the snapshot bucket would be emptied as "mirror" while the summary said backups were
  # kept. Now they are all named before the prompt, and the live one is cross-checked against the
  # value `--bucket` matched — two independent reads of the same fact (compose config vs deploy.env),
  # which is what catches a shell-environment override rendering one name and deleting another.
  BUCKET_TARGETS="$(printf '%s\n' "$PREFLIGHT_OUT" | grep '^TARGET	' || true)"
  if [[ -z "$BUCKET_TARGETS" ]]; then
    echo "error: the preflight named no buckets — refusing to guess what it would empty." >&2
    exit 1
  fi
  live_from_py="$(printf '%s\n' "$BUCKET_TARGETS" | awk -F'\t' '$2=="live"{print $3}' | head -1)"
  if [[ "$live_from_py" != "$RELAY_S3_BUCKET" ]]; then
    echo "error: the relay's compose config names S3_BUCKET='${RELAY_S3_BUCKET}' but deploy.env" >&2
    echo "       resolves '${live_from_py}'. Two different buckets — refusing." >&2
    exit 1
  fi
  echo
fi

# ⚠⚠ IMAGE + SCHEMA PREFLIGHT, before the summary and the prompts. The image each service restarts
# onto must already be on its box (or pullable), and the schema version it must produce must be
# known — otherwise the post-check has nothing to compare against.
if [[ -n "$RELAY_HOST" ]]; then
  image_preflight "$RELAY_HOST" RELAY_IMAGE_TAG "$RELAY_IMAGE_REPO" "$RELAY_IMAGE_TAG" "$RELAY_COMPOSE" relay
  RELAY_TAG_FROM="$PF_CURRENT"; RELAY_TAG_TO="$PF_TARGET"
  if [[ -z "$EXPECT_RELAY_SCHEMA" ]]; then
    EXPECT_RELAY_SCHEMA="$(derive_schema "$RELAY_MIGRATIONS_KT" "$RELAY_TAG_TO")" || {
      echo "error: cannot derive the relay schema version for tag '${RELAY_TAG_TO}' (not a commit in this" >&2
      echo "       checkout, or its migration list moved). Pass --expect-relay-schema <n>." >&2
      exit 1; }
  fi
fi
if [[ -n "$COMMERCE_HOST" && $COMMERCE_MANAGED -ne 1 ]]; then
  image_preflight "$COMMERCE_HOST" COMMERCE_IMAGE_TAG "$COMMERCE_IMAGE_REPO" "$COMMERCE_IMAGE_TAG" "$COMMERCE_COMPOSE" commerce
  COMMERCE_TAG_FROM="$PF_CURRENT"; COMMERCE_TAG_TO="$PF_TARGET"
  if [[ -z "$EXPECT_COMMERCE_SCHEMA" ]]; then
    EXPECT_COMMERCE_SCHEMA="$(derive_schema "$COMMERCE_MIGRATIONS_KT" "$COMMERCE_TAG_TO")" || {
      echo "error: cannot derive the commerce schema version for tag '${COMMERCE_TAG_TO}'." >&2
      echo "       Pass --expect-commerce-schema <n>." >&2
      exit 1; }
  fi

  # Statutory records: a check, never an assumption.
  st_rc=0
  ST_OUT="$(run_ssh "root@${COMMERCE_HOST}" "COMMERCE_COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_STATUTORY}")" || st_rc=$?
  if [[ $st_rc -eq $REMOTE_BUG_RC ]]; then
    echo "error: refusing — see the internal error above. Commerce was not contacted; NOTHING deleted." >&2
    exit 1
  fi
  if [[ $st_rc -ne 0 ]]; then
    echo "error: the statutory-records check on ${COMMERCE_HOST} failed (status ${st_rc}) — NOTHING deleted." >&2
    exit 1
  fi
  ST_OUT="$(printf '%s\n' "$ST_OUT" | tr -d '\r')"
  st_local="$(printf '%s\n' "$ST_OUT" | awk -F'\t' '$1=="LOCAL"{print $2"+"$3}')"
  st_stripe="$(printf '%s\n' "$ST_OUT" | awk -F'\t' '$1=="STRIPE"{print $2" "$3"+"$4}')"
  echo "Statutory records on ${COMMERCE_HOST}: commerce invoice+credit_note = ${st_local:-?}; Stripe ${st_stripe:-?}"
  if [[ -z "$st_local" || -z "$st_stripe" ]]; then
    echo "error: the statutory check answered nothing parseable — refusing." >&2
    exit 1
  fi
  if [[ "$st_local" != "0+0" || "${st_stripe#* }" != "0+0" ]]; then
    if [[ -z "$STATUTORY_FILE" ]]; then
      echo "error: there ARE statutory records. Export them first — commerce /admin/export/ledger.csv and" >&2
      echo "       /admin/export/vat-summary.csv, plus the Stripe dashboard's invoices and credit notes —" >&2
      echo "       then re-run with --statutory-exported <the export file>." >&2
      exit 1
    fi
    echo "   statutory records exist; export acknowledged: ${STATUTORY_FILE}"
  fi
  echo
elif [[ -n "$COMMERCE_HOST" ]]; then
  echo "⚠ Managed commerce DB: the statutory-records check did not run — count invoice/credit_note by hand"
  echo "  and check the Stripe dashboard before you DROP SCHEMA."
fi

echo "About to WIPE ALL DATA on:"
[[ -n "$RELAY_HOST"    ]] && echo "  relay    → root@${RELAY_HOST}    (/mnt/relay_data/relay.db + relay-blobs/)"
if [[ $EMPTY_BUCKETS -eq 1 ]]; then
  echo "  buckets  → object storage, EVERY bucket below is emptied:"
  printf '%s\n' "$BUCKET_TARGETS" | awk -F'\t' '{printf "               %-8s %s%s\n", $2, $3, ($4=="" ? "" : "  (prefix " $4 ")")}'
  [[ $EMPTY_BACKUPS -eq 1 ]] \
    && echo "  backups  → BACKUP_S3_BUCKET TOO — ⚠⚠ this destroys the only rollback for this reset" \
    || echo "  backups  → BACKUP_S3_BUCKET kept (pass --empty-backups to delete those too)"
fi
[[ -n "$COMMERCE_HOST" ]] && echo "  commerce → root@${COMMERCE_HOST} ($([[ $COMMERCE_MANAGED -eq 1 ]] && echo 'MANAGED DB — manual DROP SCHEMA' || echo '/mnt/commerce_data/pgdata/'))"
[[ -n "$RELAY_HOST" ]] && \
  echo "  restart  → relay    ${RELAY_IMAGE_REPO}:${RELAY_TAG_TO} (was ${RELAY_TAG_FROM}); must come up at schema ${EXPECT_RELAY_SCHEMA}"
[[ -n "$COMMERCE_HOST" && $COMMERCE_MANAGED -ne 1 ]] && \
  echo "  restart  → commerce ${COMMERCE_IMAGE_REPO}:${COMMERCE_TAG_TO} (was ${COMMERCE_TAG_FROM}); must come up at schema ${EXPECT_COMMERCE_SCHEMA}"
echo "This orphans every paired device and deletes every commerce account. It cannot be undone."

if [[ "${RESET_YES:-0}" != "1" ]]; then
  CONFIRM_TARGET="${RELAY_HOST:-$COMMERCE_HOST}"
  read -r -p "Type the host '${CONFIRM_TARGET}' to confirm: " typed
  if [[ "$typed" != "$CONFIRM_TARGET" ]]; then
    echo "Aborted — input did not match." >&2
    exit 1
  fi
  # ⚠⚠ A SECOND confirmation for object storage, and it asks for the bucket, not the host. Typing a
  # hostname you have typed a hundred times is muscle memory; the bucket is the thing being emptied.
  if [[ $EMPTY_BUCKETS -eq 1 ]]; then
    read -r -p "Type the bucket '${RELAY_S3_BUCKET}' to confirm emptying object storage: " typed_b
    if [[ "$typed_b" != "$RELAY_S3_BUCKET" ]]; then
      echo "Aborted — bucket name did not match." >&2
      exit 1
    fi
  fi
fi

# ⚠⚠ ARM THE TRAPS ONCE, HERE — before either host block, before anything is touched.
# Two earlier revisions got the placement wrong in opposite directions: inside the `--empty-buckets`
# branch (so the script's own headline usage, `--relay <host>` with no flags, ran the `rm` step
# uncovered), then inside `if [[ -n "$RELAY_HOST" ]]` (so a `--commerce`-only run had no trap at all,
# and a Ctrl-C between `stop commerce db` and `up -d` left commerce down with no restore and no
# message). One arming point outside both blocks is the only shape with no such hole; each half's
# flag decides whether its restore has anything to do.
# ⚠⚠ What the trap may do depends on how far each plane got — a PHASE, not a flag. A flag that stayed
# armed from before the `rm` until after the schema check brought the PRE-RESET image up on the wiped
# volume on any failure in between (a bucket error, a refused tag switch, Ctrl-C, an ssh drop), wrote
# the old ledger, and reported "restored": the exact state the image switch exists to prevent.
#   ""        untouched                      → nothing to do
#   stopped   quiesced/stopped, not wiped    → start it again on the OLD tag (nothing was destroyed)
#   wiped     data deleted, tag not switched → NEVER start: leave it down, say "re-run the full reset"
#   switched  new tag in place               → start it, then re-run the schema check
#   verified  up on the new image, checked   → nothing to undo (relay: only its timers are pending)
#   runaway   came up wrong, and the STOP failed → say so loudly, with the exact manual stop command
#   rearming  (relay) both planes verified; only the timer re-arm is left → `systemctl start`, NOT a re-run
RELAY_PHASE=""
COMMERCE_PHASE=""
# Space-separated list of the timers that were ACTIVE before the quiesce stopped them. Empty means
# the restore must arm nothing.
TIMERS_TO_REARM=""
# Set by the signal handlers only. `$?` at handler entry is the status of whatever command had just
# finished, which for `kill -TERM <pid>` (a CI cancel, `timeout` without --foreground) is 0 — so the
# restore announced "(exit 0)" on an aborted run, on the most alarming path there is.
SIG_RC=""

# Every message the trap prints goes through `say`: when the operator's session has dropped, a write to
# the dead terminal fails with EIO, and under `set -e` (or just an unchecked `echo`) that aborted the
# restore half-way. `say` cannot fail. The local log (RESET_LOG, below) keeps a readable copy.
say() { printf '%s\n' "$*" >&2 2>/dev/null || true; }

relay_stop_cmd()    { printf "ssh root@%s 'cd /opt/tenshu/repo && %s stop relay'" "$RELAY_HOST" "$RELAY_COMPOSE"; }
commerce_stop_cmd() { printf "ssh root@%s 'cd /opt/tenshu/repo && %s stop commerce'" "$COMMERCE_HOST" "$COMMERCE_COMPOSE"; }

# $1 = relay|commerce. Status 0 ONLY when the service really stopped.
stop_plane() {
  if [[ "$1" == relay ]]; then
    run_ssh "root@${RELAY_HOST}" "cd /opt/tenshu/repo && ${RELAY_COMPOSE} stop relay
${REMOTE_END}"
  else
    run_ssh "root@${COMMERCE_HOST}" "cd /opt/tenshu/repo && ${COMMERCE_COMPOSE} stop commerce
${REMOTE_END}"
  fi
}

# ⚠⚠ A plane that came up wrong (wrong ledger, not empty, no /health) must not serve. Stop it — and
# NEVER say "stopped" unless it is: a swallowed `stop … || true` once left the relay serving the wrong
# ledger while the run reported it stopped. A failed stop is phase `runaway`, which the trap reports
# with the exact manual command. $1 = relay|commerce, $2 = what went wrong, $3 = what to re-run with.
# Always exits 1.
halt_plane() {
  local plane="$1" reason="$2" hint="${3:-}"
  if stop_plane "$plane"; then
    [[ "$plane" == relay ]] && RELAY_PHASE=wiped || COMMERCE_PHASE=wiped
    say "error: ${plane} ${reason} — it is STOPPED.${hint:+ Re-run the full reset ${hint}.}"
  else
    [[ "$plane" == relay ]] && RELAY_PHASE=runaway || COMMERCE_PHASE=runaway
    say "error: ${plane} ${reason} — and the STOP FAILED."
  fi
  exit 1
}

restore_relay() {
  set +e
  local rc="$1" phase="$RELAY_PHASE"
  RELAY_PHASE=""
  case "$phase" in
    "") return 0 ;;
    stopped)
      # ⚠⚠ Arm back exactly the timers the quiesce found active — never "the two timers". A timer an
      # operator had deliberately disabled must stay disabled, and both units are `Persistent=true`, so
      # `systemctl start` on one whose window has passed fires the missed elapse IMMEDIATELY.
      say ""
      say "==> Restoring ${RELAY_HOST} (exit ${rc}) — nothing was deleted; relay up on its old tag$([[ -n "${TIMERS_TO_REARM}" ]] && echo ", re-arming:${TIMERS_TO_REARM}") …"
      # ⚠⚠ VERIFY, do not announce: the remote body fails unless /health answers 200 and every timer started.
      if run_ssh "root@${RELAY_HOST}" \
          "RELAY_COMPOSE_CMD=$(printf %q "$RELAY_COMPOSE") REARM_TIMERS=$(printf %q "$TIMERS_TO_REARM")
${REMOTE_RESTORE_RELAY}" >&2; then
        say "   restored."
      else
        say "   ⚠⚠ RESTORE FAILED — the relay may still be DOWN$([[ -n "${TIMERS_TO_REARM}" ]] && echo ' and its timers disarmed')."
        say "      ssh root@${RELAY_HOST} 'cd /opt/tenshu/repo && ${RELAY_COMPOSE} up -d relay'"
        [[ -n "${TIMERS_TO_REARM}" ]] && say "      ssh root@${RELAY_HOST} systemctl start${TIMERS_TO_REARM}"
      fi
      ;;
    wiped)
      say ""
      say "==> ⚠⚠ ${RELAY_HOST}: the relay is DOWN on a WIPED volume (exit ${rc}) and was NOT restarted."
      say "      Starting it now could run the pre-reset image, which writes its own schema_version rows"
      say "      and makes the new image skip its migrations. Do not start it by hand."
      say "      → Re-run the FULL two-plane command; it wipes again and switches the tag first."
      [[ -n "${TIMERS_TO_REARM}" ]] && say "      Timers left quiesced:${TIMERS_TO_REARM}"
      ;;
    runaway)
      say ""
      say "==> ⚠⚠⚠ ${RELAY_HOST}: the relay failed its post-start check AND could not be stopped (exit ${rc})."
      say "      It may be SERVING a wrong or non-empty ledger RIGHT NOW. Stop it by hand, now:"
      say "        $(relay_stop_cmd)"
      say "      → then re-run the FULL two-plane command."
      [[ -n "${TIMERS_TO_REARM}" ]] && say "      Timers left quiesced:${TIMERS_TO_REARM}"
      ;;
    switched)
      say ""
      say "==> Restoring ${RELAY_HOST} (exit ${rc}) — the new tag is in place; starting it and re-checking schema + emptiness …"
      if run_ssh "root@${RELAY_HOST}" \
          "RELAY_COMPOSE_CMD=$(printf %q "$RELAY_COMPOSE") REARM_TIMERS=''
${REMOTE_RESTORE_RELAY}" >&2 \
        && run_ssh "root@${RELAY_HOST}" "EXPECT=$(printf %q "$EXPECT_RELAY_SCHEMA")
${REMOTE_SCHEMA_RELAY}" >&2 \
        && run_ssh "root@${RELAY_HOST}" "$REMOTE_VERIFY_RELAY" >&2; then
        say "   relay is up on the new image, empty, at the expected schema — but the run failed: re-run the full reset."
      elif stop_plane relay >&2; then
        say "   ⚠⚠ the relay did not come up empty at the expected schema — it is STOPPED. Re-run the full reset."
      else
        say "   ⚠⚠⚠ the relay did not come up empty at the expected schema, and the STOP FAILED — it may be"
        say "       SERVING. Stop it by hand, now:  $(relay_stop_cmd)"
      fi
      [[ -n "${TIMERS_TO_REARM}" ]] && say "      Timers left quiesced:${TIMERS_TO_REARM}"
      ;;
    verified)
      # The relay itself is done; its timers wait until BOTH planes verified, and commerce did not.
      if [[ -n "${TIMERS_TO_REARM}" ]]; then
        say ""
        say "==> ${RELAY_HOST}: relay verified, but the run failed later — timers left quiesced:${TIMERS_TO_REARM}"
        say "      Re-run the full two-plane reset; only if you deliberately will not: ssh root@${RELAY_HOST} systemctl start${TIMERS_TO_REARM}"
      fi
      ;;
    rearming)
      # Both planes are reset and verified; only the timers did not come back. A re-run would wipe a
      # good reset for nothing.
      say ""
      say "==> ⚠ Both planes are reset and VERIFIED; only re-arming the relay's timers failed (exit ${rc})."
      say "      Do NOT re-run the reset. Start them by hand and check they are active:"
      say "        ssh root@${RELAY_HOST} 'systemctl start${TIMERS_TO_REARM} && systemctl is-active${TIMERS_TO_REARM}'"
      ;;
  esac
  return 0
}

restore_commerce() {
  set +e
  local rc="$1" phase="$COMMERCE_PHASE"
  COMMERCE_PHASE=""
  case "$phase" in
    ""|verified) return 0 ;;
    stopped)
      say ""
      say "==> Restoring ${COMMERCE_HOST} (exit ${rc}) — nothing was deleted; commerce + db up on the old tag …"
      if run_ssh "root@${COMMERCE_HOST}" "COMMERCE_COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_RESTORE_COMMERCE}" >&2; then
        say "   restored."
      else
        say "   ⚠⚠ RESTORE FAILED — commerce may still be DOWN."
        say "      ssh root@${COMMERCE_HOST} 'cd /opt/tenshu/repo && ${COMMERCE_COMPOSE} up -d'"
      fi
      ;;
    switched)
      say ""
      say "==> Restoring ${COMMERCE_HOST} (exit ${rc}) — the new tag is in place; starting it and re-checking schema + emptiness …"
      if run_ssh "root@${COMMERCE_HOST}" "COMMERCE_COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_RESTORE_COMMERCE}" >&2 \
        && run_ssh "root@${COMMERCE_HOST}" "EXPECT=$(printf %q "$EXPECT_COMMERCE_SCHEMA") COMMERCE_COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_SCHEMA_COMMERCE}" >&2 \
        && run_ssh "root@${COMMERCE_HOST}" "COMMERCE_COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_VERIFY_COMMERCE}" >&2; then
        say "   commerce is up on the new image, empty, at the expected schema — but the run failed: re-run the full reset."
      elif stop_plane commerce >&2; then
        say "   ⚠⚠ commerce did not come up empty at the expected schema — it is STOPPED. Re-run the full reset."
      else
        say "   ⚠⚠⚠ commerce did not come up empty at the expected schema, and the STOP FAILED — it may be"
        say "       SERVING. Stop it by hand, now:  $(commerce_stop_cmd)"
      fi
      ;;
    wiped)
      say ""
      say "==> ⚠⚠ ${COMMERCE_HOST}: commerce is DOWN on a WIPED database (exit ${rc}) and was NOT restarted —"
      say "      the pre-reset image would write its own schema_version. Do not start it by hand."
      say "      → Re-run the FULL two-plane command, unchanged. Its statutory check counts an already-wiped"
      say "        or stopped database as it finds it, so it gets through."
      ;;
    runaway)
      say ""
      say "==> ⚠⚠⚠ ${COMMERCE_HOST}: commerce failed its post-start check AND could not be stopped (exit ${rc})."
      say "      It may be SERVING RIGHT NOW. Stop it by hand, now:"
      say "        $(commerce_stop_cmd)"
      say "      → then re-run the FULL two-plane command."
      ;;
  esac
  return 0
}

LOG_HINT_SHOWN=0
on_exit() {
  local rc=$?
  # ⚠⚠ The restore must run to the end whatever happens around it: no `set -e` abort on one failed
  # command, and no second Ctrl-C / SIGHUP / SIGPIPE cutting it short half-way.
  set +e
  trap '' INT TERM HUP PIPE
  rc="${SIG_RC:-$rc}"
  local half=0
  [[ "$RELAY_PHASE" =~ ^(wiped|switched|verified|runaway)$ || "$COMMERCE_PHASE" =~ ^(wiped|switched|verified|runaway)$ ]] && half=1
  restore_relay "$rc"
  restore_commerce "$rc"
  if [[ $rc -ne 0 && $half -eq 1 ]]; then
    say ""
    say "⚠⚠ The two planes are NOT atomic and this run failed after a wipe: relay and commerce may now"
    say "   describe different worlds. Re-run the full two-plane command — never finish one plane by hand."
  fi
  if [[ $rc -ne 0 && -n "${RESET_LOG_ON:-}" && $LOG_HINT_SHOWN -eq 0 ]]; then
    LOG_HINT_SHOWN=1
    say "   (the full output of this run is in ${RESET_LOG})"
  fi
  return $rc
}

# Close our end of the log pipe and let the logger drain, so nothing is lost and nothing prints after
# the shell prompt returns.
drain_log() {
  [[ -n "${LOGGER_PID:-}" ]] || return 0
  exec 1>&- 2>&-
  wait "$LOGGER_PID" 2>/dev/null || true
}

# ⚠⚠ THREE traps, not one, and the signal handlers EXIT. A handler that ends in `return` returns from
# the *function* — and a bash INT/TERM handler that returns resumes the script where it was
# interrupted. So a single `trap restore EXIT INT TERM` turned a Ctrl-C (or a CI cancel, or
# `timeout`) during the `rm relay.db` step into: relay brought back UP, mirror timer RE-ARMED,
# the restore flag cleared — and then the script carried on into the bucket wipe with the relay
# serving and the mirror armed, exiting 0 with "Server-side reset complete." That is exactly the race
# the quiesce below exists to prevent, armed by the safety net itself. (Bash aborts on its own only
# when the signal also killed the foreground child; reaching bash alone, it does not.) Exit with the
# conventional 128+signal so a caller can still tell what happened.
# ⚠ The EXIT trap re-enters `on_exit` after a signal handler's `exit`; both restores clear their phase
# first, so the second pass no-ops and does not override the exit code.
# ⚠⚠ HUP and PIPE too: a dropped ssh/VPN session to the operator's machine SIGHUPs this script, and
# without a HUP trap bash simply died — mid-wipe, with no restore and no message. Run it under tmux.
#
# The local log: every line from here on is also appended to RESET_LOG, through a logger that ignores
# HUP/PIPE and write errors, so the operator can read what happened after a dropped session. Set up
# here, AFTER the confirmation prompts, which need the raw terminal.
_tmpdir="${TMPDIR:-/tmp}"
RESET_LOG="${RESET_LOG:-${_tmpdir%/}/tenshu-reset-$(date -u +%Y%m%dT%H%M%SZ)-$$.log}"
RESET_LOG_ON=""
LOGGER_PID=""
_fifo="${RESET_LOG}.fifo"
if : >>"$RESET_LOG" 2>/dev/null && rm -f "$_fifo" && mkfifo "$_fifo" 2>/dev/null; then
  # ⚠ `set +e`: the subshell inherits this script's `set -e`, and the first write to a dead terminal
  # killed the logger — after which every write of the script's own hit a pipe with no reader.
  # The terminal copy stops at its first failed write: bash keeps an unflushed buffer after a failed
  # write, and the next printf (the log's) flushed it again, duplicating every line in the log.
  ( set +e; trap '' HUP INT TERM PIPE
    _tty=1
    while IFS= read -r _l || [[ -n "$_l" ]]; do
      printf '%s\n' "$_l" >>"$RESET_LOG" 2>/dev/null || true
      [[ $_tty -eq 1 ]] && { printf '%s\n' "$_l" 2>/dev/null || _tty=0; }
    done ) <"$_fifo" &
  LOGGER_PID=$!
  exec >"$_fifo" 2>&1
  rm -f "$_fifo"
  RESET_LOG_ON=1
  echo "   (this run is also logged to ${RESET_LOG})"
else
  echo "warning: cannot create ${RESET_LOG} — running without a local log." >&2
fi
trap 'on_exit; _rc=$?; drain_log; exit $_rc' EXIT
trap 'SIG_RC=130; on_exit; exit 130' INT
trap 'SIG_RC=143; on_exit; exit 143' TERM
trap 'SIG_RC=129; on_exit; exit 129' HUP
trap 'SIG_RC=141; on_exit; exit 141' PIPE

if [[ -n "$RELAY_HOST" ]]; then
  echo
  echo "==> Resetting relay on ${RELAY_HOST} …"
  RELAY_PHASE=stopped

  # shellcheck disable=SC2087
  # ⚠⚠ Stop the blob-mirror timer BEFORE the relay, and before anything is deleted. A mirror run
  # that fires mid-wipe copies the live bucket into the mirror — so the bytes you just deleted come
  # back in the bucket you are about to check, and the verification passes on both.
  # ⚠⚠ The .timer is the SCHEDULE; an already-running .service keeps going. A oneshot mirror that
  # started a minute before the reset finishes its server-side copy *after* the mirror was re-listed
  # as empty, writing its pre-wipe key list back in — both checks green, ciphertext stranded. Stop
  # the units too, and assert they are actually down rather than trusting `|| true`.
  # ⚠ A plain `--relay` run (no --empty-buckets) does NOT quiesce, so tenshu-relay-backup.timer stays
  # armed and its nightly snapshot can fire during the `rm relay.db` → `up -d` window. Not data loss:
  # with no database it fails (`VACUUM INTO` / `integrity_check`), and a snapshot of the fresh empty
  # one is uploaded under a NEW key — the wipe writes /opt/tenshu/reset-epoch, so backup-relay-db.sh's
  # relative size floor lets it through — beside, never over, the pre-reset snapshots. Left as-is:
  # quiescing on every run would stop the timers on the topology that has no bucket and no reason to
  # touch them.
  if [[ $EMPTY_BUCKETS -eq 1 ]]; then
    # ⚠ Capture, do not stream: the remote names the timers it found active on `WASACTIVE` lines and
    # the restore re-arms exactly those. `QUIESCE_OUT` holds whatever was printed before a failure
    # too, so a quiesce that dies half-way still tells the trap which timers it had already stopped.
    QUIESCE_RC=0
    QUIESCE_OUT="$(run_ssh "root@${RELAY_HOST}" "$REMOTE_QUIESCE")" \
      || QUIESCE_RC=$?
    # ⚠ Set the re-arm list BEFORE acting on the failure: the timers are already stopped by then, so
    # an `exit 1` here owes the trap an accurate list.
    # ⚠⚠ VALIDATE, do not just parse. These words are word-split and interpolated UNQUOTED into the
    # next remote command, so the remote is trusted to name only its own units — and stdout carries
    # human output on the same channel, separated from protocol by a prefix and nothing else. Accept
    # only the unit names this script itself stops; anything else is dropped loudly rather than
    # executed. ⚠ `tr -d '\r'` — NOT `tr -d '\r\n'`, which would delete the line separators the
    # `while read` loop below depends on. With SSH_OPTS="-t" (the documented escape hatch) the
    # remote's lines arrive CRLF, and a trailing CR turns `systemctl start x.timer^M` into an unknown
    # unit that `|| true` swallows — leaving the nightly backup and the mirror disarmed while the run
    # prints "restored."
    TIMERS_TO_REARM=""
    while IFS= read -r _u; do
      [[ -n "$_u" ]] || continue
      case "$_u" in
        tenshu-blob-mirror.timer|tenshu-blob-mirror-dr.timer|tenshu-relay-backup.timer) TIMERS_TO_REARM="${TIMERS_TO_REARM} ${_u}" ;;
        *) echo "warning: ignoring unexpected WASACTIVE unit from ${RELAY_HOST}: '${_u}'" >&2 ;;
      esac
    done < <(printf '%s\n' "$QUIESCE_OUT" | tr -d '\r' | sed -n 's/^WASACTIVE //p')
    printf '%s\n' "$QUIESCE_OUT" | grep -v '^WASACTIVE ' || true
    if [[ $QUIESCE_RC -eq $REMOTE_BUG_RC ]]; then
      # As at the s3-detect site: a local bug, not a host that refused to quiesce. run_ssh printed
      # the real cause; do not send the operator to `systemctl status` on a healthy box.
      echo "error: refusing — see the internal error above. NOTHING has been deleted." >&2
      exit 1
    fi
    if [[ $QUIESCE_RC -ne 0 ]]; then
      echo "error: could not quiesce the mirror/backup units — NOTHING has been deleted." >&2
      echo "       (the trap above will re-arm${TIMERS_TO_REARM:- nothing} on the way out)" >&2
      exit 1
    fi
  fi

  # ⚠ Split around the bucket step: the relay must stay DOWN while object storage is emptied, or a
  # live PUT lands after the delete and the re-list finds it.
  run_ssh "root@${RELAY_HOST}" "set -euo pipefail
    cd /opt/tenshu/repo
    ${RELAY_COMPOSE} stop relay
    ${REMOTE_END}"
  # From here on the trap must never start the relay on the old tag. Set BEFORE the rm: a failure
  # half-way through it is still "wiped".
  RELAY_PHASE=wiped
  run_ssh "root@${RELAY_HOST}" "set -euo pipefail
    rm -f  /mnt/relay_data/relay.db /mnt/relay_data/relay.db-wal /mnt/relay_data/relay.db-shm
    rm -rf /mnt/relay_data/relay-blobs
    mkdir -p /mnt/relay_data/relay-blobs
    # Read by backup-relay-db.sh: the first snapshot after this is exempt from the relative size floor.
    date -u +%s > /opt/tenshu/reset-epoch
    ${REMOTE_END}"

  if [[ $EMPTY_BUCKETS -eq 1 ]]; then
    echo "   emptying object storage (relay is down) …"
    run_bucket_python "$RELAY_HOST" 0
  fi

  # ⚠⚠ The tag switch happens HERE — relay down, volume empty, before the first start. Never after:
  # one start of the old image on this volume writes the old ledger, and the new image skips its
  # migrations on top of it.
  echo "   switching the relay image (relay is down) …"
  run_ssh "root@${RELAY_HOST}" "ENV_FILE=/opt/tenshu/deploy.env TAG_VAR=RELAY_IMAGE_TAG IMAGE_REPO=$(printf %q "$RELAY_IMAGE_REPO") TAG=$(printf %q "$RELAY_TAG_TO") COMPOSE_CMD=$(printf %q "$RELAY_COMPOSE")
${REMOTE_IMAGE_SWITCH}"
  # Only once the switch has asserted what compose will start.
  RELAY_PHASE=switched
  run_ssh "root@${RELAY_HOST}" "set -euo pipefail
    cd /opt/tenshu/repo
    ${RELAY_COMPOSE} up -d --force-recreate relay
    ${RELAY_COMPOSE} ps
    ${REMOTE_END}"
  run_ssh "root@${RELAY_HOST}" "DOMAIN_VAR=RELAY_DOMAIN LABEL=relay
${REMOTE_LIVENESS}" \
    || { say "   check: ssh root@${RELAY_HOST} 'cd /opt/tenshu/repo && ${RELAY_COMPOSE} logs --tail=100 relay'"
         halt_plane relay "did not answer /health 200 after the start"; }

  # Prove it. Every table but the three bookkeeping ones (schema_version, relay_seq, schema_baseline)
  # must be at zero, and the blob directory must be empty — the index and the
  # bytes are wiped together or you are left with dangling refs.
  # ⚠⚠ A relay that is not empty, or on the wrong ledger, must not serve, and the trap must not start
  # it again: halt_plane stops it (and says so only if the stop worked). A re-run with the right
  # --relay-image-tag wipes the volume again, so nothing here is permanent.
  echo "   verifying …"
  run_ssh "root@${RELAY_HOST}" "$REMOTE_VERIFY_RELAY" \
    || halt_plane relay "failed the post-wipe emptiness check (see above)"
  run_ssh "root@${RELAY_HOST}" "EXPECT=$(printf %q "$EXPECT_RELAY_SCHEMA")
${REMOTE_SCHEMA_RELAY}" \
    || halt_plane relay "came up on the wrong schema" "with the right --relay-image-tag"
  RELAY_PHASE=verified
  # ⚠ The timers are NOT re-armed here. The two planes are not atomic: a relay whose mirror and nightly
  # backup resumed while commerce then failed would carry on as if the reset had happened. They come
  # back once BOTH planes verified (rearm_relay_timers, below); on a failure the trap names them.
fi

# ⚠⚠ Re-arm what we stopped — and ONLY what we stopped. A reset that leaves the nightly backup and the
# mirror disabled is a silent loss of both safety nets; arming a timer this run found already off is
# the mirror-image mistake, and with `Persistent=true` it fires the missed elapse immediately.
# Called only once BOTH planes verified. Phase `rearming`: a failure here owes the operator
# `systemctl start`, not a re-run of a reset that already succeeded.
rearm_relay_timers() {
  if [[ -n "$RELAY_HOST" && -n "$TIMERS_TO_REARM" ]]; then
    RELAY_PHASE=rearming
    COMMERCE_PHASE=""
    run_ssh "root@${RELAY_HOST}" \
      "REARM_TIMERS=$(printf %q "$TIMERS_TO_REARM")
${REMOTE_REARM}"
  fi
  TIMERS_TO_REARM=""
}

if [[ -n "$COMMERCE_HOST" ]]; then
  echo
  if [[ $COMMERCE_MANAGED -eq 1 ]]; then
    echo "==> Commerce is on a MANAGED DB — this script won't touch it."
    echo "    Run against that Postgres, then restart commerce so migrations re-run:"
    echo "      DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
    echo "      ssh root@${COMMERCE_HOST} 'cd /opt/tenshu/repo && ${COMMERCE_COMPOSE} restart commerce'"
  else
    echo "==> Resetting commerce (sidecar Postgres) on ${COMMERCE_HOST} …"
    COMMERCE_PHASE=stopped
    run_ssh "root@${COMMERCE_HOST}" "set -euo pipefail
      cd /opt/tenshu/repo
      ${COMMERCE_COMPOSE} stop commerce db
      ${REMOTE_END}"
    # From here the trap must never start commerce on the old tag; set BEFORE the rm.
    COMMERCE_PHASE=wiped
    run_ssh "root@${COMMERCE_HOST}" "set -euo pipefail
      rm -rf /mnt/commerce_data/pgdata
      mkdir -p /mnt/commerce_data/pgdata
      # Read by backup-commerce-db.sh: the first dump after this is exempt from the relative size floor.
      date -u +%s > /opt/tenshu/reset-epoch
      ${REMOTE_END}"
    # ⚠⚠ Same rule as the relay: switch while it is down, before the first start on the empty cluster.
    echo "   switching the commerce image (commerce is down) …"
    run_ssh "root@${COMMERCE_HOST}" "ENV_FILE=/opt/tenshu/deploy.env TAG_VAR=COMMERCE_IMAGE_TAG IMAGE_REPO=$(printf %q "$COMMERCE_IMAGE_REPO") TAG=$(printf %q "$COMMERCE_TAG_TO") COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_IMAGE_SWITCH}"
    COMMERCE_PHASE=switched
    run_ssh "root@${COMMERCE_HOST}" "set -euo pipefail
      cd /opt/tenshu/repo
      ${COMMERCE_COMPOSE} up -d --force-recreate
      ${COMMERCE_COMPOSE} ps
      ${REMOTE_END}"
    run_ssh "root@${COMMERCE_HOST}" "DOMAIN_VAR=COMMERCE_DOMAIN LABEL=commerce
${REMOTE_LIVENESS}" \
      || { say "   check: ssh root@${COMMERCE_HOST} 'cd /opt/tenshu/repo && ${COMMERCE_COMPOSE} logs --tail=100 commerce'"
           halt_plane commerce "did not answer /health 200 after the start"; }

    # Prove it — and wait for the re-init, because postgres:16 re-creating the
    # cluster plus commerce's migrations take longer than the container's "up".
    #
    # ⚠ `redemption_code` and `schema_version` are EXPECTED to be non-zero after a
    # wipe: the codes are re-seeded from REDEMPTION_CODES in deploy.env on boot and
    # schema_version is the migration ledger. Reading those as "the wipe failed"
    # sends you chasing a bug that is a working feature.
    echo "   verifying …"
    run_ssh "root@${COMMERCE_HOST}" "COMMERCE_COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_VERIFY_COMMERCE}" \
      || halt_plane commerce "failed the post-wipe emptiness check (see above)"
    run_ssh "root@${COMMERCE_HOST}" "EXPECT=$(printf %q "$EXPECT_COMMERCE_SCHEMA") COMMERCE_COMPOSE_CMD=$(printf %q "$COMMERCE_COMPOSE")
${REMOTE_SCHEMA_COMMERCE}" \
      || halt_plane commerce "came up on the wrong schema" "with the right --commerce-image-tag"
    COMMERCE_PHASE=verified
  fi
fi

# Both planes verified: only now do the relay's mirror and nightly backup resume.
# ⚠ Not with --commerce-managed: that commerce is reset by hand AFTER this script (the DROP SCHEMA
# above), so "both verified" has not happened yet. Hand the operator the command instead.
if [[ -n "$COMMERCE_HOST" && $COMMERCE_MANAGED -eq 1 && -n "$RELAY_HOST" && -n "$TIMERS_TO_REARM" ]]; then
  echo
  echo "⚠ The relay's timers stay quiesced until the managed commerce DB is reset. After the DROP SCHEMA"
  echo "  and the commerce restart, re-arm them:"
  echo "    ssh root@${RELAY_HOST} 'systemctl start${TIMERS_TO_REARM} && systemctl is-active${TIMERS_TO_REARM}'"
  TIMERS_TO_REARM=""
else
  rearm_relay_timers
fi
RELAY_PHASE=""
COMMERCE_PHASE=""

echo
echo "Server-side reset complete."
if [[ -n "$RELAY_HOST" || ( -n "$COMMERCE_HOST" && $COMMERCE_MANAGED -ne 1 ) ]]; then
  echo "⚠ Mirror the image tags into the gitignored tfvars, or the next \`terraform apply\` rolls them back:"
  [[ -n "$RELAY_HOST" ]] && echo "    deploy/scaleway-relay/terraform.tfvars       relay_image_tag    = \"${RELAY_TAG_TO}\""
  [[ -n "$COMMERCE_HOST" && $COMMERCE_MANAGED -ne 1 ]] && \
    echo "    deploy/scaleway-commerce/terraform.tfvars    commerce_image_tag = \"${COMMERCE_TAG_TO}\""
fi
echo "Now reset the client devices, then put the apps back:"
echo "  ./scripts/reset-local-device-state.sh --all --uninstall   # desktop + every Android + iOS"
echo "  ./scripts/reinstall-all-apps.sh --wipe-ios-identity       # rebuild + install from this checkout"
