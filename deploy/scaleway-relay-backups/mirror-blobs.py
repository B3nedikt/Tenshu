#!/usr/bin/env python3
"""Mirror the relay's content-plane bucket into a copy that deletes only after a grace window.

The threat this guards against is the relay's own reclamation path (the content sweep, and before it
`dropRefs` → candidate → veto → reclaim) removing a blob some device still needs. A mirror that
propagated deletes at once would propagate exactly that failure. So a key the source no longer has is
kept for MIRROR_RETENTION_DAYS after this script first saw it missing, and only then pruned, and only
when pruning is switched on.

⚠⚠ **Pruning is OFF unless `MIRROR_PRUNE=on`.** Deleting from the mirror ends its protection for that
key, so turning it on is a deploy-time decision (`retention-quota-model.md`), not a default. With it
off the script still records when each key went missing, so the clock is already running when the
operator turns it on.

⚠⚠ **The copy always runs; only pruning fails closed.** A bad setting, an unreadable ledger, a clock
anomaly or a tripped guard stops the prune and makes the run exit non-zero, but never stops the
additive copy — that is the half of this script that protects anything.

The ledger lives in the mirror bucket itself, under `RESERVED_PREFIX`, so it survives a box rebuild.
That prefix is never copied from the source and never pruned. Besides each missing key's first-seen
time it records which source it was measured against, the source's size, and the last run's time —
the evidence the guards below compare against.

Every prune guard is in `assess()`, which the dry run and the real run share. Pruning needs ALL of:

- settings valid, `MIRROR_PRUNE=on`, ledger readable;
- **same source** as the ledger recorded (bucket, endpoint) — a different source makes every
  "missing" key meaningless;
- **sane clock**: not before the ledger's last run, not more than MAX_RUN_GAP_DAYS after it, and
  within MAX_CLOCK_SKEW of the S3 endpoint's `Date` header (no readable header → no prune);
- **no hold**: any tripped breaker sets a hold in the ledger, and only `--override-breaker` (a flag,
  one deliberate run) clears it — so a guard that tripped once cannot quietly un-trip next week;
- **breakers**, each against the PRE-copy mirror so a flood of copies cannot dilute it:
  newly-missing keys this run (count or bytes) ≤ MIRROR_PRUNE_MAX_NEW_MISSING_FRACTION; total missing
  ≤ MIRROR_PRUNE_MAX_TOTAL_MISSING_FRACTION (and ≤ MIRROR_PRUNE_MAX_MISSING_GB if set); copies this
  run ≤ MIRROR_PRUNE_MAX_COPY_FRACTION; source object count not down by more than SOURCE_DROP_FRACTION
  (25 %) since the last run, nor by more than CUMULATIVE_DROP_FRACTION (30 %) from the ledger's
  `peak_source_count` — the slow drain under the per-run limits. The peak is max(peak, current) every
  recorded run and is reset to the current count only by --override-breaker (or an accepted source
  change). Keys an override accepted (the backlog) no longer count towards the TOTAL-missing breaker,
  and only that one;
- a **complete copy**, then a fresh source listing and a HEAD per candidate immediately before the
  delete (any key the source turns out to have aborts the run).

⚠⚠ **Never loses the first copy.** A source object whose size differs from the mirror's copy is
either a corrupt write or a relation-slice re-seal (a deterministic `blob-` ref, indistinguishable by
key from a content blob). The mirror first saves its current copy to `_tenshu-mirror/prior/<key>` —
only if no prior exists, so the first, trustworthy version is never replaced — then overwrites the
main key and logs `SIZE CHANGED` (exit 0). If saving the prior fails, the main key is NOT overwritten
and the run exits SIZE_MISMATCH. Prior keys are reserved: outside every count, breaker and the
missing logic; deleted when their main key is pruned, never on their own.

A key is prunable only when it has been missing for ≥ MIRROR_RETENTION_DAYS by the clock AND has been
seen missing on ≥ ceil(window/7)+1 runs at least SIGHTING_SPACING_DAYS apart — so a clock that jumps
once adds one sighting, not a window.

Two targets, one code path (`--target`):

- **primary** (default) — the same-region mirror (fr-par). Server-side `CopyObject`: the bytes never
  transit this machine.
- **dr** — the second-region mirror (nl-ams). ⚠ `CopyObject` cannot cross regions, so each object is
  STREAMED through this box: a GET from the source piped into a (multipart, if large) upload to the
  target. ⚠⚠ Never read whole — residency is bounded by the transfer config (STREAM_CHUNK_BYTES ×
  STREAM_BUFFERED_CHUNKS), not by the object size. Its OWN ledger lives in its own bucket, its OWN prune
  switch is `MIRROR_DR_PRUNE`, and its OWN lock file — the box's key cannot delete there
  (docs/how-to/back-up-the-relay-database.md, "Keys"). Every guard above applies unchanged.

Every copy into a mirror (and every prior) is written in `MIRROR_STORAGE_CLASS` (default `ONEZONE_IA`):
the mirror is read only in a disaster, and its source is Multi-AZ already. `--migrate-storage-class`
moves what a mirror already holds onto that class (dry run unless `--apply`; see migrate_storage_class).

    python3 mirror-blobs.py [--target primary|dr] [--dry-run] [--prune-report] [--override-breaker]
                            [--accept-source-change] [--accept-clock]
    python3 mirror-blobs.py [--target primary|dr] --migrate-storage-class [--apply]

Exit codes: 0 ok · 1 copy incomplete · 2 prune refused (guard, newly set hold, setting, source
change) · 3 listing incomplete · 4 ledger unreadable or not stored · 5 clock anomaly or no server
date · 6 lock held or cannot be opened · 7 some prune deletes failed · 8 other S3/unexpected error,
missing boto3 or tfvars · 9 a size-changed object's prior copy could not be saved (not overwritten) ·
10 orphaned prior copies remain (their main key is gone from mirror, source and ledger).
"""
import argparse
import datetime as dt
import email.utils
import json
import math
import os
import re
import sys

# ⚠ Never a key the relay writes: the relay's keys are `<ownerId>/blob-<hex>` and `share/<id>/<idx>`,
# optionally under S3_KEY_PREFIX.
RESERVED_PREFIX = "_tenshu-mirror/"
LEDGER_KEY = RESERVED_PREFIX + "missing-since.json"
# The mirror's previous copy of a key whose size changed at the source. ⚠ Written ONCE and never
# replaced: a content blob never legitimately changes, so the first prior is the trustworthy one (for
# a slice-snapshot re-seal it is the oldest version, which is fine). Removed with its main key.
PRIOR_PREFIX = RESERVED_PREFIX + "prior/"


def prior_key(key):
    return PRIOR_PREFIX + key
LEDGER_VERSION = 2

DEFAULT_RETENTION_DAYS = 30
MIN_RETENTION_DAYS = 7          # below a week the mirror stops covering a bug noticed at the next look
MAX_RETENTION_DAYS = 3650
DEFAULTS = {
    "MIRROR_PRUNE_MAX_NEW_MISSING_FRACTION": 0.10,
    "MIRROR_PRUNE_MAX_TOTAL_MISSING_FRACTION": 0.50,
    "MIRROR_PRUNE_MAX_COPY_FRACTION": 0.50,
}
CYCLE_DAYS = 7                  # the timer's interval
SIGHTING_SPACING_DAYS = 6       # a sighting counts only this long after the previous one
MAX_RUN_GAP_DAYS = 3 * CYCLE_DAYS
# A source that lost this share of its objects since the last run is measured from the SOURCE side,
# independently of the mirror; looser than the per-run missing limit because the denominator shrinks.
SOURCE_DROP_FRACTION = 0.25
# …and this share since the first sighting of any prunable key — the slow drain the per-run limits miss.
CUMULATIVE_DROP_FRACTION = 0.30
EXIT_ORPHAN_PRIOR = 10
MAX_CLOCK_SKEW = dt.timedelta(minutes=15)
DELETE_BATCH = 1000

# ⚠ GLACIER is deliberately not accepted: a Glacier object has to be restored (per object, hours, billed)
# before it can be read or copied back, which turns a restore of the mirror into a project.
STORAGE_CLASSES = ("STANDARD", "ONEZONE_IA")
DEFAULT_STORAGE_CLASS = "ONEZONE_IA"

# The cross-region stream: what bounds this process's memory, whatever the object size. s3transfer
# buffers at most STREAM_BUFFERED_CHUNKS chunks of a non-seekable body (its default is 10), so the
# residency is ~ STREAM_CHUNK_BYTES × STREAM_BUFFERED_CHUNKS = 32 MiB. The DR unit's MemoryMax backs it.
STREAM_CHUNK_BYTES = 8 * 1024 * 1024
STREAM_CONCURRENCY = 2
STREAM_BUFFERED_CHUNKS = 4

EXIT_OK, EXIT_COPY_INCOMPLETE, EXIT_REFUSED, EXIT_LISTING = 0, 1, 2, 3
EXIT_LEDGER, EXIT_CLOCK, EXIT_LOCKED, EXIT_DELETE_FAILED, EXIT_ERROR, EXIT_SIZE_MISMATCH = 4, 5, 6, 7, 8, 9


class IncompleteListing(Exception):
    """A listing that cannot be shown to be complete. ⚠ Never read as 'these keys are missing'."""


class LedgerError(Exception):
    pass


class ConfigError(Exception):
    pass


def tfvar(text, key, default=None):
    m = re.search(rf'^\s*{key}\s*=\s*"([^"]+)"', text, re.M)
    if m:
        return m.group(1)
    if default is not None:
        return default
    print(f"  ⛔ {key} not found in terraform.tfvars")
    sys.exit(EXIT_ERROR)


def tfvar_number(text, key):
    m = re.search(rf'^\s*{key}\s*=\s*"?([0-9.]+)"?\s*$', text, re.M)
    return m.group(1) if m else None


def utc(ts):
    return ts.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_utc(s):
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


# ---------------------------------------------------------------- settings


class Settings:
    def __init__(self, prune=False, retention_days=DEFAULT_RETENTION_DAYS, max_new_missing=0.10,
                 max_total_missing=0.50, max_copy=0.50, max_missing_gb=None, error=None,
                 storage_class=DEFAULT_STORAGE_CLASS):
        self.storage_class = storage_class
        self.prune = prune
        self.retention_days = retention_days
        self.max_new_missing = max_new_missing
        self.max_total_missing = max_total_missing
        self.max_copy = max_copy
        self.max_missing_gb = max_missing_gb
        self.error = error   # set ⇒ pruning is refused, the copy still runs

    @property
    def sightings_required(self):
        return math.ceil(self.retention_days / CYCLE_DAYS) + 1

    @staticmethod
    def from_env(env, tf_retention=None, prune_var="MIRROR_PRUNE"):
        """Never raises: a bad value yields Settings(prune=False, error=…) — pruning fails closed, the
        copy is unaffected. `prune_var` is per target (MIRROR_PRUNE / MIRROR_DR_PRUNE); the window, the
        breakers and the storage class are shared. ⚠ A bad MIRROR_STORAGE_CLASS also refuses the prune
        (a mistyped setting is a misconfigured box), and the copy falls back to the default class."""
        sc = (env.get("MIRROR_STORAGE_CLASS") or DEFAULT_STORAGE_CLASS).strip().upper()
        sc_err = None
        if sc not in STORAGE_CLASSES:
            sc_err = f"MIRROR_STORAGE_CLASS must be one of {', '.join(STORAGE_CLASSES)}, got {sc!r}"
            sc = DEFAULT_STORAGE_CLASS
        try:
            s = Settings._parse(env, tf_retention, prune_var)
        except ConfigError as e:
            s = Settings(prune=False, error=str(e))
        s.storage_class = sc
        if sc_err and not s.error:
            s.prune, s.error = False, sc_err
        return s

    @staticmethod
    def _parse(env, tf_retention, prune_var="MIRROR_PRUNE"):
        raw = (env.get(prune_var) or "off").strip().lower()
        if raw not in ("on", "off"):
            # ⚠ A typo must not read as either setting — `MIRROR_PRUNE=yes` is neither.
            raise ConfigError(f"{prune_var} must be 'on' or 'off', got {raw!r}")
        days_raw = str(env.get("MIRROR_RETENTION_DAYS") or tf_retention or DEFAULT_RETENTION_DAYS).strip()
        if not re.fullmatch(r"[0-9]{1,5}", days_raw):
            raise ConfigError(f"MIRROR_RETENTION_DAYS must be a whole number of days, got {days_raw!r}")
        days = int(days_raw)
        if not MIN_RETENTION_DAYS <= days <= MAX_RETENTION_DAYS:
            raise ConfigError(f"MIRROR_RETENTION_DAYS={days} is outside [{MIN_RETENTION_DAYS}, {MAX_RETENTION_DAYS}]")
        fr = {}
        for name, default in DEFAULTS.items():
            v = env.get(name)
            try:
                f = float(v) if v not in (None, "") else default
            except ValueError:
                raise ConfigError(f"{name}={v!r} is not a number") from None
            if not (math.isfinite(f) and 0 < f < 1):
                raise ConfigError(f"{name}={v!r} must be strictly between 0 and 1")
            fr[name] = f
        gb = None
        v = env.get("MIRROR_PRUNE_MAX_MISSING_GB")
        if v not in (None, ""):
            try:
                gb = float(v)
            except ValueError:
                raise ConfigError(f"MIRROR_PRUNE_MAX_MISSING_GB={v!r} is not a number") from None
            if not (math.isfinite(gb) and gb > 0):
                raise ConfigError(f"MIRROR_PRUNE_MAX_MISSING_GB={v!r} must be finite and positive")
        return Settings(raw == "on", days, fr["MIRROR_PRUNE_MAX_NEW_MISSING_FRACTION"],
                        fr["MIRROR_PRUNE_MAX_TOTAL_MISSING_FRACTION"], fr["MIRROR_PRUNE_MAX_COPY_FRACTION"], gb)


# ---------------------------------------------------------------- S3 access


def response_date(resp):
    """The S3 endpoint's own clock, from the response's Date header, or None."""
    try:
        raw = resp["ResponseMetadata"]["HTTPHeaders"]["date"]
        d = email.utils.parsedate_to_datetime(raw)
    except (KeyError, TypeError, ValueError, IndexError):
        return None
    if d is None:
        return None
    if d.tzinfo is None:
        # `-0000` parses to a NAIVE datetime, which astimezone() would read as the box's local time.
        d = d.replace(tzinfo=dt.timezone.utc)
    return d.astimezone(dt.timezone.utc)


def list_keys(s3, bucket, dates=None):
    """Every key in a bucket, with size. ⚠ Paginated — a bare list_objects_v2 stops at 1000 and would
    report everything past the first page as missing. Raises IncompleteListing rather than return a
    listing it cannot show is whole."""
    return {k: size for k, (size, _) in list_entries(s3, bucket, dates).items()}


def list_entries(s3, bucket, dates=None):
    """{key: (size, storage class)} — `list_keys` plus the class each object is stored in. An entry with
    no StorageClass reads as STANDARD, which is what S3 means by its absence."""
    out = {}
    token = None
    seen_tokens = set()
    while True:
        kw = {"Bucket": bucket, "MaxKeys": 1000}
        if token:
            kw["ContinuationToken"] = token
        page = s3.list_objects_v2(**kw)
        if dates is not None and not seen_tokens:
            # The FIRST page only: later pages arrive later, and a long listing is not skew.
            d = response_date(page)
            if d:
                dates.append(d)
        for o in page.get("Contents", []):
            if "Key" not in o or "Size" not in o:
                raise IncompleteListing(f"{bucket}: listing entry without Key/Size: {o!r}")
            out[o["Key"]] = (o["Size"], o.get("StorageClass") or "STANDARD")
        if not page.get("IsTruncated"):
            return out
        token = page.get("NextContinuationToken")
        if not token:
            raise IncompleteListing(f"{bucket}: page is truncated but carries no continuation token")
        if token in seen_tokens:
            raise IncompleteListing(f"{bucket}: continuation token repeated — listing would loop")
        seen_tokens.add(token)


def split_reserved(keys):
    """(user keys, reserved keys) — the ledger and anything else under RESERVED_PREFIX."""
    user = {k: n for k, n in keys.items() if not k.startswith(RESERVED_PREFIX)}
    return user, {k: n for k, n in keys.items() if k.startswith(RESERVED_PREFIX)}


def is_not_found(e):
    """True only for a definite 404. ⚠ 403, 5xx, a timeout, or an exception with no S3 response are NOT
    'not found' — reading them as absent would turn an outage into a delete."""
    resp = getattr(e, "response", None)
    if not isinstance(resp, dict):
        return False
    code = str(resp.get("Error", {}).get("Code", ""))
    status = resp.get("ResponseMetadata", {}).get("HTTPStatusCode")
    return code in ("404", "NoSuchKey", "NotFound") or status == 404


def empty_ledger():
    return {"version": LEDGER_VERSION, "updated": None, "source": None, "source_count": None,
            "source_bytes": None, "peak_source_count": None, "hold": None, "missing": {}}


def _is_ts(v):
    try:
        parse_utc(v)
        return True
    except (TypeError, ValueError):
        return False


def _is_count(v):
    return isinstance(v, int) and not isinstance(v, bool) and v >= 0


def validate_ledger(doc):
    """Raise ValueError unless every field has the shape the guards read. ⚠ A hand edit (to clear a
    hold, say) must fail HERE, as a ledger error, not later inside a guard."""
    if not isinstance(doc, dict):
        raise ValueError("not an object")
    if doc.get("updated") is not None and not _is_ts(doc["updated"]):
        raise ValueError(f"updated={doc['updated']!r}")
    src = doc.get("source")
    if src is not None and not (isinstance(src, dict) and set(src) == {"bucket", "endpoint"}
                                and all(isinstance(v, str) for v in src.values())):
        raise ValueError(f"source={src!r}")
    for k in ("source_count", "source_bytes", "peak_source_count"):
        if doc.get(k) is not None and not _is_count(doc[k]):
            raise ValueError(f"{k}={doc[k]!r}")
    hold = doc.get("hold")
    if hold is not None and not (isinstance(hold, dict) and _is_ts(hold.get("at"))
                                 and isinstance(hold.get("reason"), str)
                                 and isinstance(hold.get("events", []), list)
                                 and all(isinstance(x, str) for x in hold.get("events", []))):
        raise ValueError(f"hold={hold!r} (to clear a hold, run --override-breaker; do not edit it)")
    if not isinstance(doc.get("missing"), dict):
        raise ValueError("missing is not an object")
    for key, e in doc["missing"].items():
        ok = (isinstance(key, str) and isinstance(e, dict) and _is_ts(e.get("since")) and _is_ts(e.get("last"))
              and _is_count(e.get("sightings")) and e["sightings"] >= 1 and _is_count(e.get("size"))
              and (e.get("source_count_at") is None or _is_count(e["source_count_at"]))
              and isinstance(e.get("accepted", False), bool))
        if not ok:
            raise ValueError(f"entry {key!r}={e!r}")


def upgrade_v1(doc):
    """v1 (e2ddbf0d5: {since, size} per key, no source/hold) → v2. Keeps each `since` — so the AGE half
    of the window may already be met — but starts at one sighting, so an entry still needs
    ceil(window/7) more weekly runs before it is prunable. No source and no peak, so the first v2 run
    records both (the peak guard then protects these entries like any other)."""
    if not isinstance(doc.get("missing"), dict):
        raise ValueError("v1 ledger without a missing map")
    entries = {}
    for k, e in doc["missing"].items():
        if not (isinstance(e, dict) and _is_ts(e.get("since")) and _is_count(e.get("size"))):
            raise ValueError(f"v1 entry {k!r}={e!r}")
        entries[k] = {"since": e["since"], "last": e["since"], "sightings": 1, "size": e["size"]}
    out = empty_ledger()
    out["updated"] = doc.get("updated") if _is_ts(doc.get("updated")) else None
    out["missing"] = entries
    return out


def load_ledger(s3, bucket):
    """The ledger document. Absent → empty. ⚠ Unreadable or malformed → LedgerError: silently starting
    over would only delay pruning, but it would hide whatever damaged it."""
    try:
        body = s3.get_object(Bucket=bucket, Key=LEDGER_KEY)["Body"].read()
    except Exception as e:  # noqa: BLE001 - botocore's ClientError, or a fake's
        if is_not_found(e):
            return empty_ledger()
        raise LedgerError(f"cannot read {LEDGER_KEY}: {str(e)[:160]}") from None
    try:
        doc = json.loads(body)
        if isinstance(doc, dict) and doc.get("version") == 1:
            doc = upgrade_v1(doc)
        elif not isinstance(doc, dict) or doc.get("version") != LEDGER_VERSION:
            raise ValueError(f"unrecognised ledger version {doc.get('version') if isinstance(doc, dict) else doc!r}")
        if isinstance(doc.get("source"), dict):
            doc["source"].pop("prefix", None)   # 2fb554b23 recorded S3_KEY_PREFIX; the identity no longer has it
        validate_ledger(doc)
    except ValueError as e:
        raise LedgerError(f"{LEDGER_KEY} is malformed: {e}") from None
    base = empty_ledger()
    base.update(doc)
    return base


def save_ledger(s3, bucket, doc):
    """One PutObject — S3 replaces an object atomically, so a reader sees the old ledger or the new one,
    never a torn one. Read back, because an accepted PUT is not the same fact as the stored object."""
    body = json.dumps(doc, sort_keys=True, indent=1).encode()
    try:
        s3.put_object(Bucket=bucket, Key=LEDGER_KEY, Body=body, ContentType="application/json")
        got = s3.get_object(Bucket=bucket, Key=LEDGER_KEY)["Body"].read()
    except Exception as e:  # noqa: BLE001
        raise LedgerError(f"cannot store {LEDGER_KEY}: {str(e)[:160]}") from None
    if got != body:
        raise LedgerError(f"{LEDGER_KEY}: read-back differs from what was written")


# ---------------------------------------------------------------- the decision (pure)


class Plan:
    def __init__(self, missing, entries, new, cleared, prunable):
        self.missing = missing      # {key: size} — in the mirror, not in the source
        self.entries = entries      # the ledger's `missing` after this run's observations
        self.new = new              # keys first seen missing this run
        self.cleared = cleared      # keys back in the source, removed from the ledger
        self.prunable = prunable    # keys past the window by clock AND sightings, oldest first


def plan_prune(source, mirror, entries, now, settings):
    """Pure. `source`/`mirror` are user keys only, `mirror` the PRE-copy listing (copying only adds
    source keys, so the missing set is the same before and after)."""
    missing = {k: n for k, n in mirror.items() if k not in source}
    after, new = {}, []
    spacing = dt.timedelta(days=SIGHTING_SPACING_DAYS)
    for k, n in missing.items():
        e = entries.get(k)
        if e is None:
            after[k] = {"since": utc(now), "last": utc(now), "sightings": 1, "size": n}
            new.append(k)
        elif now - parse_utc(e["last"]) >= spacing:
            after[k] = {**e, "last": utc(now), "sightings": int(e["sightings"]) + 1}
        else:
            after[k] = dict(e)
    cleared = sorted(k for k in entries if k in source)
    window = dt.timedelta(days=settings.retention_days)
    prunable = sorted((k for k, v in after.items()
                       if now - parse_utc(v["since"]) >= window
                       and int(v["sightings"]) >= settings.sightings_required),
                      key=lambda k: (after[k]["since"], k))
    return Plan(missing, after, sorted(new), cleared, prunable)


def frac(part, whole):
    return part / whole if whole else (1.0 if part else 0.0)


def breakers(plan, source, mirror, todo, ledger, settings, baseline=False):
    """Mass-deletion signals, each measured against the PRE-copy mirror. Returns (reasons, per_run):
    every reason, and the subset caused by THIS run's change (newly missing, copies, a drop since the
    last run) — those fail the run even under an existing hold.

    `baseline` (no previous run to compare with: a new ledger, or an accepted source change) skips the
    per-run signals and the peak — everything would read as new — and keeps the total. Nothing is
    prunable on a baseline run anyway: every entry starts at one sighting."""
    out, per_run = [], []
    mn, mb_ = len(mirror), sum(mirror.values())
    new_n = len(plan.new)
    new_b = sum(plan.missing.get(k, 0) for k in plan.new)
    # Keys an --override-breaker run accepted (the backlog) were reviewed; they no longer count
    # towards the TOTAL. ⚠ Only there: the drain guards below ignore `accepted`.
    live = [k for k in plan.missing if not plan.entries.get(k, {}).get("accepted")]
    tot_n, tot_b = len(live), sum(plan.missing.get(k, 0) for k in live)
    cp_n = len(todo)
    cp_b = sum(source.get(k, 0) for k in todo)
    t = settings
    if not baseline and (frac(new_n, mn) > t.max_new_missing or frac(new_b, mb_) > t.max_new_missing):
        per_run.append(f"{new_n} objects / {new_b} bytes went missing from the source since the last run "
                       f"({frac(new_n, mn):.1%} / {frac(new_b, mb_):.1%} of the mirror; limit {t.max_new_missing:.0%})")
    if frac(tot_n, mn) > t.max_total_missing or frac(tot_b, mb_) > t.max_total_missing:
        out.append(f"{tot_n} objects / {tot_b} bytes are missing from the source in total "
                   f"({frac(tot_n, mn):.1%} / {frac(tot_b, mb_):.1%}; limit {t.max_total_missing:.0%})")
    if t.max_missing_gb is not None and tot_b > t.max_missing_gb * 1e9:
        out.append(f"{tot_b} missing bytes exceed MIRROR_PRUNE_MAX_MISSING_GB={t.max_missing_gb}")
    if not baseline and mn and (frac(cp_n, mn) > t.max_copy or frac(cp_b, mb_) > t.max_copy):
        per_run.append(f"this run copies {cp_n} objects / {cp_b} bytes into a mirror of {mn} / {mb_} "
                       f"(limit {t.max_copy:.0%}) — a different or flooded source?")
    prev = ledger.get("source_count")
    if not baseline and prev and len(source) < prev * (1 - SOURCE_DROP_FRACTION):
        per_run.append(f"the source fell from {prev} to {len(source)} objects since the last run")
    peak = ledger.get("peak_source_count")
    if not baseline and peak and len(source) < peak * (1 - CUMULATIVE_DROP_FRACTION):
        out.append(f"the source fell from its peak of {peak} to {len(source)} objects "
                   f"(limit {CUMULATIVE_DROP_FRACTION:.0%}) — a slow drain? --override-breaker resets the peak")
    return per_run + out, per_run


def assess(source, mirror, todo, ledger, identity, now, server_now, settings,
           override_breaker=False, accept_source_change=False, accept_clock=False):
    """Every prune guard, shared by the dry run and the real run.

    Returns (plan, refusals, write_ledger). `refusals` is a list of (exit code, message); empty means
    the prune may proceed (subject to the pre-delete re-checks). `write_ledger` is False when this
    run's observations must not be recorded (a different source, or a clock that cannot be trusted).
    """
    refusals = []
    write = True
    entries = ledger["missing"]

    if ledger.get("source") and ledger["source"] != identity and not accept_source_change:
        refusals.append((EXIT_REFUSED, f"source changed: ledger recorded {ledger['source']}, this run "
                                       f"is {identity}. Nothing recorded, nothing pruned; if the move is "
                                       f"deliberate, re-run once with --accept-source-change (restarts every clock)"))
        write = False
    elif ledger.get("source") and ledger["source"] != identity:
        entries = {}     # accepted: every clock restarts against the new source
    baseline = not ledger.get("updated") or (ledger.get("source") not in (None, identity))

    last = parse_utc(ledger["updated"]) if ledger.get("updated") else None
    clock_bad = []
    if last and now < last:
        clock_bad.append(f"clock is BEFORE the ledger's last run ({utc(now)} < {utc(last)})")
    if last and now - last > dt.timedelta(days=MAX_RUN_GAP_DAYS):
        clock_bad.append(f"{(now - last).days} days since the last run (more than {MAX_RUN_GAP_DAYS}) — "
                         f"clock jump, or the timer was down")
    if server_now is None:
        # ⚠ Fail closed: without the endpoint's clock the box's clock is unverified.
        clock_bad.append("no readable Date header from the S3 endpoint — the box's clock cannot be checked")
    elif abs(now - server_now) > MAX_CLOCK_SKEW:
        clock_bad.append(f"local clock {utc(now)} differs from the S3 endpoint's {utc(server_now)}")
    if clock_bad and not accept_clock:
        refusals.append((EXIT_CLOCK, "; ".join(clock_bad) + ". Nothing recorded, nothing pruned; once the "
                                     "clock is right, re-run once with --accept-clock"))
        write = False
    elif clock_bad:
        refusals.append((EXIT_OK, "--accept-clock: recording this run, but not pruning in it"))

    plan = plan_prune(source, mirror, entries, now, settings)

    if settings.error:
        refusals.append((EXIT_REFUSED, f"bad setting, pruning disabled: {settings.error}"))
    tripped, per_run = breakers(plan, source, mirror, todo, ledger, settings, baseline)
    hold = ledger.get("hold")   # ⚠ survives even an accepted source change; only an override clears it
    # With pruning off, a breaker under an EXISTING hold is a warning: nothing is at risk, and a known
    # backlog must not keep the unit red. ⚠ But the run that newly SETS a hold fails even with pruning
    # off — a mass deletion at the source must show in `systemctl --failed` while nobody prunes.
    # ⚠ And a per-run signal (something happened THIS run) fails it even under an existing hold, so a
    # second incident is never silenced by the first.
    newly_held = bool(tripped) and not hold and not override_breaker and write
    code = EXIT_REFUSED if settings.prune or newly_held or per_run else EXIT_OK
    if tripped and not override_breaker:
        refusals.append((code, "CIRCUIT BREAKER: " + "; ".join(tripped)))
    if hold and not override_breaker:
        later = hold.get("events", [])
        refusals.append((code, f"held since {hold['at']} ({hold['reason'][:200]})"
                               f"{f'; {len(later)} later incident(s), last: {later[-1][:160]}' if later else ''} — "
                               f"only --override-breaker clears it"))
    plan.tripped, plan.per_run = tripped, per_run
    return plan, refusals, write


def next_ledger(ledger, plan, source, identity, now, override_breaker):
    hold = None if override_breaker else ledger.get("hold")
    if plan.tripped and not override_breaker:
        if hold is None:
            hold = {"at": utc(now), "reason": "; ".join(plan.tripped), "events": []}
        elif plan.per_run:
            # A new incident under an existing hold is appended, not dropped (kept to the last 50).
            hold = {**hold, "events": (hold.get("events", []) + [f"{utc(now)}: " + "; ".join(plan.per_run)])[-50:]}
    same_source = ledger.get("source") in (None, identity)
    peak = ledger.get("peak_source_count") if same_source else None
    # ⚠ The peak only grows; an override (a reviewed state) or a new source resets it to now.
    peak = len(source) if (override_breaker or peak is None) else max(peak, len(source))
    if override_breaker:
        # The operator reviewed what is missing now (the backlog): accept it, so those same keys do not
        # re-trip the breakers — and re-set the hold — at every run until they are pruned.
        plan.entries = {k: {**v, "accepted": True} for k, v in plan.entries.items()}
    return {"version": LEDGER_VERSION, "updated": utc(now), "source": identity,
            "source_count": len(source), "source_bytes": sum(source.values()), "peak_source_count": peak,
            "hold": hold,
            "missing": plan.entries}


# ---------------------------------------------------------------- the run


def confirm_absent(s3, src, keys):
    """HEAD each candidate in the source. ⚠ Only a definite 404 counts; anything else aborts."""
    for k in keys:
        try:
            s3.head_object(Bucket=src, Key=k)
        except Exception as e:  # noqa: BLE001
            if is_not_found(e):
                continue
            raise IncompleteListing(f"HEAD {src}/{k} failed: {str(e)[:160]}") from None
        raise IncompleteListing(f"{src}/{k} exists although the listing omitted it — not pruning anything")


def save_prior(s3, dst, key, size, out, storage_class=DEFAULT_STORAGE_CLASS):
    """Copy the mirror's current object to prior/<key> and confirm it by size. False = do not overwrite.
    Within the mirror bucket, so server-side in either target."""
    pk = prior_key(key)
    try:
        s3.copy_object(Bucket=dst, Key=pk, CopySource={"Bucket": dst, "Key": key}, StorageClass=storage_class)
        got = s3.head_object(Bucket=dst, Key=pk)
        if got.get("ContentLength") != size:
            raise RuntimeError(f"prior is {got.get('ContentLength')} bytes, expected {size}")
    except Exception as e:  # noqa: BLE001
        out(f"  ⛔ could not save {pk}: {str(e)[:160]} — {key} NOT overwritten")
        return False
    return True


def delete_keys(s3, dst, keys, out):
    deleted = set()
    for i in range(0, len(keys), DELETE_BATCH):
        batch = keys[i:i + DELETE_BATCH]
        resp = s3.delete_objects(Bucket=dst, Delete={"Objects": [{"Key": k} for k in batch], "Quiet": False})
        for d in resp.get("Deleted", []):
            deleted.add(d["Key"])
        for err in resp.get("Errors", []):
            out(f"    FAILED delete {err.get('Key')}: {err.get('Code')} {str(err.get('Message', ''))[:120]}")
    return deleted


def print_plan(p, settings, now, out, limit=20):
    out(f"  in mirror but NOT in source: {len(p.missing)} object(s), {sum(p.missing.values())} bytes")
    out(f"    newly missing this run: {len(p.new)} · back in source: {len(p.cleared)}")
    pb = sum(p.missing.get(k, 0) for k in p.prunable)
    out(f"    missing >= {settings.retention_days} days on >= {settings.sightings_required} runs (prunable): "
        f"{len(p.prunable)} object(s), {pb} bytes")
    for k in p.prunable[:limit]:
        e = p.entries[k]
        out(f"      {k}  missing since {e['since']} ({(now - parse_utc(e['since'])).days} d, {e['sightings']} runs)")
    if len(p.prunable) > limit:
        out(f"      … and {len(p.prunable) - limit} more")


def stream_copy(src_s3, src, key, dst_s3, dst, storage_class, transfer_config=None):
    """Copy one object across regions through this box. ⚠ The GET body is handed to upload_fileobj as a
    stream — never `.read()` whole — so memory is bounded by the transfer config, not the object."""
    body = src_s3.get_object(Bucket=src, Key=key)["Body"]
    kw = {"ExtraArgs": {"StorageClass": storage_class}}
    if transfer_config is not None:
        kw["Config"] = transfer_config
    try:
        dst_s3.upload_fileobj(body, dst, key, **kw)
    finally:
        body.close()


def run(s3, src, dst, settings, now, identity=None, dry_run=False, prune_report=False,
        override_breaker=False, accept_source_change=False, accept_clock=False, out=print,
        dst_s3=None, transfer_config=None):
    """`s3` reaches the source; `dst_s3` the mirror. They differ only for the second-region target, and
    that is what switches the copy from server-side CopyObject to a stream through the box."""
    cross_region = dst_s3 is not None and dst_s3 is not s3
    dst_s3 = dst_s3 or s3
    identity = identity or {"bucket": src, "endpoint": ""}
    dates = []
    source_all = list_keys(s3, src, dates)
    mirror_all = list_keys(dst_s3, dst, dates)
    # ⚠ The FIRST response: `now` was taken before listing, and a long listing must not read as skew.
    server_now = dates[0] if dates else None
    source, source_reserved = split_reserved(source_all)
    mirror, mirror_reserved = split_reserved(mirror_all)
    priors = {k[len(PRIOR_PREFIX):] for k in mirror_reserved if k.startswith(PRIOR_PREFIX)}
    for k in sorted(source_reserved):
        # ⚠ Copying it would overwrite the ledger with whatever the source holds under that name.
        out(f"  ⚠ source holds a key under the reserved prefix, NOT mirrored: {k}")
    out(f"  source {src}: {len(source)} objects, {sum(source.values())} bytes")
    out(f"  mirror {dst}: {len(mirror)} objects, {sum(mirror.values())} bytes")
    out(f"  pruning: {'ON' if settings.prune else 'off'} · window {settings.retention_days} d / "
        f"{settings.sightings_required} runs")
    out(f"  copy: {'streamed through this box (cross-region)' if cross_region else 'server-side CopyObject'}"
        f" · storage class {settings.storage_class}")

    def copy_in(k):
        if cross_region:
            stream_copy(s3, src, k, dst_s3, dst, settings.storage_class, transfer_config)
        else:
            s3.copy_object(Bucket=dst, Key=k, CopySource={"Bucket": src, "Key": k},
                           StorageClass=settings.storage_class)

    # ⚠ A ledger problem stops the PRUNE, never the copy below.
    ledger_err = None
    try:
        ledger = load_ledger(dst_s3, dst)
    except LedgerError as e:
        ledger_err, ledger = str(e), empty_ledger()

    # A size that differs from the mirror's copy is a corrupt write or a slice-snapshot re-seal; the
    # two cannot be told apart by key. ⚠⚠ So the mirror's copy is saved to prior/ (once) BEFORE the
    # overwrite — see save_prior. `todo` is new keys only; the breakers count those.
    todo = [k for k in source if k not in mirror]
    mismatched = sorted(k for k, n in source.items() if k in mirror and mirror[k] != n)
    try:
        plan, refusals, write = assess(source, mirror, todo, ledger, identity, now, server_now, settings,
                                       override_breaker, accept_source_change, accept_clock)
    except Exception as e:  # noqa: BLE001 - a guard that cannot be evaluated refuses; the copy still runs
        plan = plan_prune(source, mirror, {}, now, settings)
        plan.prunable, plan.tripped, plan.per_run = [], [], []
        refusals, write = [(EXIT_ERROR, f"prune guards could not be evaluated ({type(e).__name__}: "
                                        f"{str(e)[:160]}) — not pruning, not rewriting the ledger")], False
    if ledger_err:
        refusals.insert(0, (EXIT_LEDGER, f"{ledger_err} — not pruning, not rewriting it"))
        write = False
    if not settings.prune:
        refusals.append((EXIT_OK, "pruning is off (MIRROR_PRUNE != on)"))

    def report_refusals(prefix):
        for code, msg in refusals:
            out(f"  {'⛔' if code else '·'} {prefix}{msg}")

    if prune_report or dry_run:
        if dry_run:
            out(f"  to copy: {len(todo)}")
            for k in todo[:10]:
                out(f"    would copy {k}")
            for k in mismatched[:20]:
                keep = "prior already kept" if k in priors else f"would save prior to {prior_key(k)}"
                out(f"    SIZE CHANGED {k}: source {source[k]} bytes, mirror {mirror[k]} — {keep}, would overwrite")
        print_plan(plan, settings, now, out)
        report_refusals("would refuse to prune: ")
        if not refusals:
            out(f"  would prune {len(plan.prunable)} object(s)")
        out("  dry run — nothing copied, ledger not written, nothing pruned")
        return EXIT_OK

    out(f"  to copy: {len(todo)}")
    copied = failed = 0
    done = []
    for i, k in enumerate(todo, 1):
        try:
            copy_in(k)
            copied += 1
            done.append(k)
        except Exception as e:  # noqa: BLE001 - report and continue; one bad key must not stop the run
            failed += 1
            out(f"    FAILED {k}: {str(e)[:120]}")
        if i % 500 == 0:
            out(f"    … {i}/{len(todo)}")

    blocked = []
    for k in mismatched:
        if k not in priors and not save_prior(dst_s3, dst, k, mirror[k], out, settings.storage_class):
            blocked.append(k)
            continue
        try:
            copy_in(k)
            done.append(k)
        except Exception as e:  # noqa: BLE001
            out(f"    FAILED {k}: {str(e)[:120]}")
            continue
        out(f"  ⚠ SIZE CHANGED {k}: source {source[k]} bytes, mirror had {mirror[k]} "
            f"(prior kept at {prior_key(k)}) — a corrupt write or a slice-snapshot re-seal")
    if blocked:
        refusals.insert(0, (EXIT_SIZE_MISMATCH,
                            f"{len(blocked)} size-changed object(s) NOT overwritten because their prior copy "
                            f"could not be saved (first: {blocked[0]}) — the mirror kept its copies"))

    entries, _ = split_reserved(list_entries(dst_s3, dst))
    after = {k: size for k, (size, _) in entries.items()}
    absent = [k for k, n in source.items() if k not in blocked and after.get(k) != n]
    out(f"  copied: {copied}  failed: {failed}")
    out(f"  mirror now: {len(after)} objects, {sum(after.values())} bytes")
    # ⚠ An accepted StorageClass parameter is not the same fact as a stored class — read it back. A
    # mismatch costs money, not data, so it warns rather than failing the run (and blocking the prune).
    wrong = [k for k in done if k in entries and entries[k][1] != settings.storage_class]
    if wrong:
        out(f"  ⚠ {len(wrong)} object(s) copied this run are NOT stored as {settings.storage_class} "
            f"(first: {wrong[0]} is {entries[wrong[0]][1]}) — the endpoint ignored the class")
    legacy = sum(1 for _, c in entries.values() if c != settings.storage_class)
    if legacy:
        out(f"  note: {legacy} mirror object(s) are not {settings.storage_class} — "
            f"`--migrate-storage-class` moves them")
    if absent:
        refusals.insert(0, (EXIT_COPY_INCOMPLETE,
                            f"{len(absent)} source object(s) still absent from the mirror"))
    elif not blocked:
        out("  OK — every source object is present in the mirror at the same size")

    print_plan(plan, settings, now, out)
    # The ledger is written whether or not pruning is on: recording when a key went missing deletes
    # nothing, and it means the window has already run when the operator turns pruning on.
    doc = None
    if write:
        doc = next_ledger(ledger, plan, source, identity, now, override_breaker)
        try:
            save_ledger(dst_s3, dst, doc)
        except LedgerError as e:
            refusals.insert(0, (EXIT_LEDGER, str(e)))
            doc = None
        else:
            out(f"  ledger: {len(doc['missing'])} key(s) tracked ({len(plan.new)} new, "
                f"{len(plan.cleared)} back in source){' · HELD' if doc['hold'] else ''}")
    if override_breaker and plan.tripped:
        out(f"  ⚠ circuit breaker overridden by --override-breaker: {'; '.join(plan.tripped)}")

    # Priors whose main key is gone from the mirror, the source AND the ledger (a manual delete, or a
    # prior whose delete failed when its main key was pruned). Reported every run; deleted only on a
    # run that is allowed to prune.
    orphans = sorted(k for k in priors if k not in mirror and k not in source and k not in ledger["missing"])
    for k in orphans[:20]:
        out(f"  ⚠ ORPHANED PRIOR {prior_key(k)}: its main key is in neither the mirror, the source nor the ledger")

    if refusals:
        report_refusals("not pruning: ")
        if any(m.startswith("CIRCUIT") for _, m in refusals):
            out("     That looks like a mass deletion or a wrong source, not ordinary deletes. Investigate")
            out("     (docs/how-to/back-up-the-relay-database.md); the hold stays until --override-breaker.")
        if orphans:
            refusals.append((EXIT_ORPHAN_PRIOR, f"{len(orphans)} orphaned prior copy/copies kept (pruning not allowed this run)"))
        return next((c for c, _ in refusals if c), EXIT_OK)
    if not plan.prunable and not orphans:
        out("  pruned: 0 objects, 0 bytes")
        return EXIT_OK

    # ⚠ Re-check immediately before deleting: a fresh listing, then a HEAD per key. A listing that
    # was short the first time must not be the only evidence for a delete — for an orphaned prior,
    # that its main key is really gone from the source.
    checked = plan.prunable + orphans
    fresh, _ = split_reserved(list_keys(s3, src))
    back = [k for k in checked if k in fresh]
    if back:
        raise IncompleteListing(f"{len(back)} prune candidate(s) appear in a fresh source listing "
                                f"(first: {back[0]}) — the earlier listing was incomplete")
    confirm_absent(s3, src, checked)

    with_prior = [k for k in plan.prunable if k in priors]
    deleted_all = delete_keys(dst_s3, dst, plan.prunable + [prior_key(k) for k in with_prior + orphans], out)
    deleted = {k for k in deleted_all if not k.startswith(RESERVED_PREFIX)}
    stray = [prior_key(k) for k in with_prior if k in deleted and prior_key(k) not in deleted_all]
    if with_prior:
        out(f"  pruned {len(with_prior) - len(stray)} prior copy/copies with their main keys")
    for k in stray:
        out(f"  ⚠ prior {k} not deleted although its main key was; reported as orphaned next run")
    orphans_left = [k for k in orphans if prior_key(k) not in deleted_all]
    if orphans:
        out(f"  removed {len(orphans) - len(orphans_left)} orphaned prior copy/copies")
    pruned_bytes = sum(plan.missing.get(k, 0) for k in deleted)
    doc["missing"] = {k: v for k, v in doc["missing"].items() if k not in deleted}
    save_ledger(dst_s3, dst, doc)
    out(f"  pruned: {len(deleted)} objects, {pruned_bytes} bytes "
        f"(missing from the source for >= {settings.retention_days} days)")
    if len(deleted) != len(plan.prunable) or stray:
        out(f"  ⚠ {len(plan.prunable) - len(deleted)} prune delete(s) failed; they stay in the ledger")
        return EXIT_DELETE_FAILED
    if orphans_left:
        return EXIT_ORPHAN_PRIOR
    return EXIT_OK


def migrate_storage_class(s3, bucket, storage_class, apply=False, out=print):
    """Move every object a mirror already holds onto `storage_class`. One-off; idempotent; DRY RUN unless
    `apply`.

    Copies each object onto ITSELF with the new class (server-side, so nothing transits the box) and
    verifies it with a HEAD — the class AND the size — before counting it done. Chosen over a lifecycle
    transition rule because it is synchronous and checked per object: a lifecycle rule runs whenever the
    provider gets to it, reports nothing, and lives on a bucket whose whole value is that nothing on it
    expires — one wrong field (Expiration for Transition) there deletes the mirror.

    ⚠⚠ Nothing under `_tenshu-mirror/` is touched — not the ledger, not the priors: they are left out of
    the work list, and the ledger's bytes are compared before and after.
    """
    entries = list_entries(s3, bucket)
    user, reserved = split_reserved(entries)
    todo = sorted(k for k, (_, c) in user.items() if c != storage_class)
    by_class = {}
    for _, (n, c) in user.items():
        by_class.setdefault(c, [0, 0])
        by_class[c][0] += 1
        by_class[c][1] += n
    out(f"  {bucket}: {len(user)} objects · " +
        " · ".join(f"{c}: {v[0]} objects, {v[1]} bytes" for c, v in sorted(by_class.items())))
    out(f"  to move to {storage_class}: {len(todo)} object(s), {sum(user[k][0] for k in todo)} bytes"
        f" · reserved prefix left alone: {len(reserved)} key(s)")
    if not apply:
        for k in todo[:10]:
            out(f"    would move {k} ({user[k][1]} -> {storage_class})")
        out("  dry run — nothing changed. Re-run with --apply to move them.")
        return EXIT_OK

    def ledger_bytes():
        try:
            return s3.get_object(Bucket=bucket, Key=LEDGER_KEY)["Body"].read()
        except Exception as e:  # noqa: BLE001
            if is_not_found(e):
                return None
            raise

    ledger_before = ledger_bytes()
    moved = failed = 0
    for i, k in enumerate(todo, 1):
        size = user[k][0]
        try:
            s3.copy_object(Bucket=bucket, Key=k, CopySource={"Bucket": bucket, "Key": k},
                           StorageClass=storage_class, MetadataDirective="COPY")
            head = s3.head_object(Bucket=bucket, Key=k)
            got_class = head.get("StorageClass") or "STANDARD"
            if head.get("ContentLength") != size:
                failed += 1
                out(f"    FAILED {k}: size {head.get('ContentLength')} after the move, was {size}")
            elif got_class != storage_class:
                failed += 1
                out(f"    FAILED {k}: still {got_class} — the endpoint accepted the copy but not the class")
            else:
                moved += 1
        except Exception as e:  # noqa: BLE001 - report and continue; each self-copy is atomic
            failed += 1
            out(f"    FAILED {k}: {str(e)[:120]}")
        if i % 500 == 0:
            out(f"    … {i}/{len(todo)}")

    if ledger_bytes() != ledger_before:
        out(f"  ⛔ {LEDGER_KEY} changed during the migration — was another mirror run active?")
        out(f"     before: {(ledger_before or b'')[:2000]!r}")
        return EXIT_LEDGER
    out(f"  moved: {moved}  failed: {failed}  (re-run to retry; objects already moved are skipped)")
    return EXIT_OK if not failed else EXIT_COPY_INCOMPLETE


def endpoint_for(region):
    return f"https://s3.{region}.scw.cloud"


def take_lock(path):
    """Exclusive, non-blocking. Returns the open file (keep it referenced) or None if held. Raises
    OSError if the lock file cannot be opened."""
    import fcntl
    f = open(path, "a")   # ⚠ No fallback path: a different file would not exclude anything.
    try:
        fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        f.close()
        return None
    return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true",
                    help="report what would be copied and pruned, and which guard refuses; write nothing")
    ap.add_argument("--prune-report", action="store_true",
                    help="the prune half of --dry-run only")
    ap.add_argument("--override-breaker", action="store_true",
                    help="prune although a breaker trips, and clear the hold (one deliberate run; never in the unit)")
    ap.add_argument("--accept-source-change", action="store_true",
                    help="the source bucket/endpoint moved on purpose: adopt it, restarting every clock")
    ap.add_argument("--accept-clock", action="store_true",
                    help="record a run after a clock anomaly or a long gap (still prunes nothing in that run)")
    ap.add_argument("--target", choices=("primary", "dr"), default="primary",
                    help="primary: the same-region mirror (server-side copy) · dr: the second-region mirror "
                         "(streamed through this box; own ledger, lock and prune switch MIRROR_DR_PRUNE)")
    ap.add_argument("--migrate-storage-class", action="store_true",
                    help="move objects the target mirror already holds onto MIRROR_STORAGE_CLASS (dry run "
                         "unless --apply); copies nothing from the source and prunes nothing")
    ap.add_argument("--apply", action="store_true", help="with --migrate-storage-class: actually move them")
    ap.add_argument("--tfvars", default="terraform.tfvars")
    args = ap.parse_args()
    if args.apply and not args.migrate_storage_class:
        ap.error("--apply only goes with --migrate-storage-class")
    dr = args.target == "dr"

    try:
        import boto3
    except ImportError:
        print("  ⛔ boto3 is required: pip3 install boto3 (or apt-get install python3-boto3)")
        sys.exit(EXIT_ERROR)

    # Two ways in, so the same script serves the maintainer's checkout and a timer on the box:
    # the relay's own deploy.env when it is there, else terraform.tfvars beside this file.
    # ⚠ MIRROR_S3_* is the mirror job's own least-privilege key (deploy/scaleway-backup-access). The
    # fallback to the relay's content-plane S3_* key is the pre-hardening setup, kept so an upgrade of
    # this script alone changes nothing; it is announced, because that key can delete everything.
    env = os.environ
    tf_retention = None
    access = env.get("MIRROR_S3_ACCESS_KEY") or env.get("S3_ACCESS_KEY")
    if access and (env.get("BLOB_MIRROR_BUCKET") or env.get("BLOB_MIRROR_DR_BUCKET")):
        if env.get("MIRROR_S3_ACCESS_KEY"):
            secret = env.get("MIRROR_S3_SECRET_KEY", "")
        else:
            secret = env.get("S3_SECRET_KEY", "")
            print("  ⚠ MIRROR_S3_ACCESS_KEY unset — using the relay's content-plane key (S3_ACCESS_KEY)")
        region = env.get("S3_REGION", "fr-par")
        src = env.get("S3_BUCKET", "tenshu-relay-blobs")
        endpoint = env.get("S3_ENDPOINT", endpoint_for(region))
        if dr:
            dst = env.get("BLOB_MIRROR_DR_BUCKET")
            dst_region = env.get("BLOB_MIRROR_DR_REGION", "nl-ams")
            dst_endpoint = env.get("BLOB_MIRROR_DR_ENDPOINT", endpoint_for(dst_region))
        else:
            dst, dst_region, dst_endpoint = env.get("BLOB_MIRROR_BUCKET"), region, endpoint
    else:
        try:
            tf = open(args.tfvars).read()
        except OSError as e:
            print(f"  ⛔ no deploy.env settings in the environment and cannot read {args.tfvars}: {e}")
            sys.exit(EXIT_ERROR)
        region = tfvar(tf, "region", "fr-par")
        src = tfvar(tf, "source_bucket_name", "tenshu-relay-blobs")
        endpoint = endpoint_for(region)
        access, secret = tfvar(tf, "access_key"), tfvar(tf, "secret_key")
        tf_retention = tfvar_number(tf, "blob_mirror_retention_days")
        if dr:
            dst = tfvar(tf, "dr_blob_mirror_bucket_name")
            dst_region = tfvar(tf, "dr_region", "nl-ams")
        else:
            dst, dst_region = tfvar(tf, "blob_mirror_bucket_name"), region
        dst_endpoint = endpoint_for(dst_region)
    if not dst:
        print(f"  ⛔ no mirror bucket for --target {args.target} "
              f"({'BLOB_MIRROR_DR_BUCKET' if dr else 'BLOB_MIRROR_BUCKET'} unset)")
        sys.exit(EXIT_ERROR)
    if dr and dst_region == region:
        # ⚠ A second-region copy in the first region is a same-region copy that LOOKS like geo-redundancy.
        print(f"  ⛔ --target dr points at {dst_region}, the source's own region")
        sys.exit(EXIT_ERROR)
    # ⚠ No S3_KEY_PREFIX here: the script mirrors the WHOLE bucket whatever prefix the relay writes under.
    identity = {"bucket": src, "endpoint": endpoint.rstrip("/")}

    settings = Settings.from_env(env, tf_retention, prune_var="MIRROR_DR_PRUNE" if dr else "MIRROR_PRUNE")
    if settings.error:
        print(f"  ⛔ {settings.error} — pruning disabled for this run; the copy still runs")

    lock = None
    if not (args.dry_run or args.prune_report) and (args.apply or not args.migrate_storage_class):
        # Per target: the lock guards one mirror's ledger, and the two targets must not block each other.
        lock_path = env.get("MIRROR_LOCK_FILE") or \
            f"/run/lock/tenshu-blob-mirror{'-dr' if dr else ''}.lock"
        try:
            lock = take_lock(lock_path)
        except OSError as e:
            print(f"  ⛔ cannot open the lock file {lock_path}: {e} — not running unlocked "
                  f"(set MIRROR_LOCK_FILE to a writable path on a non-systemd host)")
            sys.exit(EXIT_LOCKED)
        if lock is None:
            print("  ⛔ another mirror-blobs.py run holds the lock — not starting a second one")
            sys.exit(EXIT_LOCKED)

    def client(url, reg):
        return boto3.client("s3", endpoint_url=url, region_name=reg,
                            aws_access_key_id=access, aws_secret_access_key=secret)

    s3 = client(endpoint, region)
    dst_s3 = client(dst_endpoint, dst_region) if dr else s3
    print(f"  target {args.target}: {src} ({region}) -> {dst} ({dst_region})")

    transfer_config = None
    if dr and not args.migrate_storage_class:
        from boto3.s3.transfer import TransferConfig
        transfer_config = TransferConfig(multipart_threshold=STREAM_CHUNK_BYTES,
                                         multipart_chunksize=STREAM_CHUNK_BYTES,
                                         max_concurrency=STREAM_CONCURRENCY)
        # s3transfer's own knob for how many chunks of a non-seekable body it holds; not a
        # constructor argument of boto3's subclass on older versions, so set it directly.
        transfer_config.max_in_memory_upload_chunks = STREAM_BUFFERED_CHUNKS
    try:
        if args.migrate_storage_class:
            code = migrate_storage_class(dst_s3, dst, settings.storage_class, apply=args.apply)
        else:
            code = run(s3, src, dst, settings, dt.datetime.now(dt.timezone.utc), identity=identity,
                       dry_run=args.dry_run, prune_report=args.prune_report,
                       override_breaker=args.override_breaker, accept_source_change=args.accept_source_change,
                       accept_clock=args.accept_clock, dst_s3=dst_s3, transfer_config=transfer_config)
    except IncompleteListing as e:
        print(f"  ⛔ INCOMPLETE LISTING: {e}. Nothing pruned; a listing that cannot be shown whole is "
              f"never read as 'missing'.")
        code = EXIT_LISTING
    except LedgerError as e:
        print(f"  ⛔ LEDGER: {e}")
        code = EXIT_LEDGER
    except Exception as e:  # noqa: BLE001 - one line in the log, not a traceback
        print(f"  ⛔ {type(e).__name__}: {str(e)[:300]}")
        code = EXIT_ERROR
    sys.exit(code)


if __name__ == "__main__":
    main()
