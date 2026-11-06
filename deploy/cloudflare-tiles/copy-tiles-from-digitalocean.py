#!/usr/bin/env python3
"""Copy the published tile bundles from DigitalOcean Spaces to Cloudflare R2.

⚠⚠ Cross-provider, so there is no server-side `CopyObject` — every byte is streamed through this
machine and buffered to /var/tmp one bundle at a time (the largest is 2.0 GB). ~5.5 GB today, and
`--verify-existing` downloads it all back again. ⚠ Run it where the UPLINK is fast, not where it is
convenient: a home line measured at ~5 Mbit/s up would take hours, while an idle host in the source's
region did the whole verified copy in ~5 minutes (2026-09-14).

⭐ The catalog carries a **sha256 per region**, so unlike a blob copy this is verifiable
cryptographically rather than by byte count. Every object is hashed as it streams and checked against
the catalog; a mismatch fails the run rather than being reported at the end.

⚠ PMTiles is range-native: the client reads tiles by byte range instead of downloading a bundle.
Anything in front of these objects that drops `Range` does not merely break resume, it breaks
rendering. Verify with `--check-ranges` after publishing, against the PUBLIC url, unauthenticated —
that is the path a device uses.

    export R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=...   # R2 → Manage API tokens; never in tfvars
    python3 copy-tiles-from-digitalocean.py --dry-run
    python3 copy-tiles-from-digitalocean.py --verify-existing
    python3 copy-tiles-from-digitalocean.py --check-ranges --public-base https://<the route under test>
    python3 copy-tiles-from-digitalocean.py --check-ranges --public-base … --user-agent 'SomeClient/1'
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request

CHUNK = 8 * 1024 * 1024

# ⚠⚠ Every HTTP request this script makes names the APPS' User-Agent, never urllib's default. The
# default (`Python-urllib/3.x`) is not neutral: measured 2026-09-14, r2.dev answered it 403 on every
# path while `ktor-client`, curl and no User-Agent at all got 200/206. The gate then reported "the
# catalog itself is not reachable" against a bucket serving ranges perfectly. A proxied Cloudflare zone
# is no better: Browser Integrity Check, on by default, "challenges visitors without a user agent or
# with a non-standard user agent". So the only answer that means anything is the one the apps get.
#
# ⚠⚠ What the apps actually send is Ktor's DEFAULT, on every platform: `ktor-client`. Not `okhttp/…` and
# not a per-app CFNetwork string. Ktor core's `mergeHeaders` adds `User-Agent: KTOR_DEFAULT_USER_AGENT`
# whenever none is set on a non-browser platform, and both engines route through it: OkHttp (Android,
# desktop) calls `mergeHeaders`, Darwin (iOS) calls `forEachHeader`, which wraps it. Nothing in
# `control-shared-proxy`'s PlatformProxyModule sets one. Checked against ktor 3.5.2 (gradle/libs.versions.toml).
# ⚠ Two things invalidate this list silently: a Ktor upgrade that changes the constant (older Ktor sent
# "Ktor client"), and any app that installs a UserAgent plugin or default header. Re-check on either.
CLIENT_USER_AGENTS = (
    "ktor-client",
)


def _open(url: str, user_agent: str, timeout: int, headers=None):
    """urlopen with an explicit User-Agent. The only way this script touches HTTP."""
    h = {"User-Agent": user_agent}
    h.update(headers or {})
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout)

# ⚠ Object metadata must match what `scripts/tiles-publish.sh` sets (bundles L107-109 / L126-128, catalog
# L165-172), or the copy is not the same publication: without it every object goes up with no
# Cache-Control and a default Content-Type. Taken from the publisher's contract, NOT from the source
# response — the DigitalOcean CDN serves catalog.json with `max-age=3600` (its endpoint-wide TTL)
# while the origin object says `max-age=60`, so copying what the source URL returns would publish the
# edge's value instead of the origin's.
BUNDLE_METADATA = {"ContentType": "application/octet-stream",
                   "CacheControl": "public, max-age=31536000, immutable"}
CATALOG_METADATA = {
    "catalog.json": {"ContentType": "application/json",
                     "CacheControl": "public, max-age=60, must-revalidate"},
    "catalog.json.sig": {"ContentType": "application/octet-stream",
                         "CacheControl": "public, max-age=60, must-revalidate"},
}

# The Ed25519 key the CLIENT pins for the region catalog — `TILES_MANIFEST_PUBLIC_KEY_HEX` in
# `control-shared-proxy`'s ProxyModule. Kept here so this script can verify the exact pair it is
# about to publish. ⚠ Rotating the signing key means changing it in both places.
TILES_MANIFEST_PUBLIC_KEY_HEX = "e6d7adf0f403c33905e514e404e6578f4f2b990e272ba6339f0f9c7a977f43e1"


def verify_catalog(catalog_bytes: bytes, sig: bytes) -> str:
    """Check the detached signature over the catalog. Returns a description of the check performed.

    ⚠⚠ Why this and not "re-fetch the catalog and compare". `scripts/tiles-publish.sh` uploads
    `catalog.json` BEFORE `catalog.json.sig`, so a publish overlapping these two fetches hands back a
    NEW catalog with the PREVIOUS signature. Re-reading only the catalog cannot see that — by then it
    has stabilised — and the mismatched pair gets published, after which every client rejects it.
    Verifying the pair catches it whatever the timing.
    """
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError:
        # ⚠⚠ FATAL, not skipped. A "SKIPPED" that lets the run continue means the default command
        # silently does not provide the guarantee this function exists to give — the mismatched pair
        # still reaches the bucket, and the operator has a green run to point at. Failing to verify
        # must stop the publish, not annotate it.
        sys.exit("    FAILED: cannot verify catalog.json against its signature — python3-cryptography\n"
                 "    is not installed. Install it (apt-get install -y python3-cryptography, or\n"
                 "    pip3 install cryptography) and re-run. Refusing to publish an unverified pair.")
    pk = Ed25519PublicKey.from_public_bytes(bytes.fromhex(TILES_MANIFEST_PUBLIC_KEY_HEX))
    try:
        pk.verify(sig, catalog_bytes)
    except InvalidSignature:
        sys.exit("    FAILED: catalog.json does not match catalog.json.sig under the pinned key.\n"
                 "    Most likely a publish landed between the two fetches (the catalog is uploaded\n"
                 "    first). Nothing was published. Re-run.")
    return "verified against the pinned Ed25519 key"


def remote_sha256(s3, bucket: str, key: str) -> str:
    """Hash an object AS STORED, by downloading it back. The only check that sees what the destination
    kept rather than what this machine sent."""
    h = hashlib.sha256()
    body = s3.get_object(Bucket=bucket, Key=key)["Body"]
    while True:
        b = body.read(CHUNK)
        if not b:
            break
        h.update(b)
    return h.hexdigest()


def tfvar(text, key, default=None):
    m = re.search(rf'^\s*{key}\s*=\s*"([^"]+)"', text, re.M)
    if m:
        return m.group(1)
    if default is not None:
        return default
    sys.exit(f"{key} not found in terraform.tfvars")


def _probe_range(url: str, first: int, last: int, user_agent: str):
    """One ranged GET. Returns (status, bytes_read, content_range) and never reads past the range."""
    resp = _open(url, user_agent, 60, {"Range": f"bytes={first}-{last}"})
    # ⚠ Status FIRST, and never read more than the range asked for. An endpoint that ignores Range
    # answers 200 with the whole bundle — gigabytes — and reading before checking turns the failure
    # this gate exists to detect into an apparent hang.
    code = resp.status
    got = len(resp.read(last - first + 2)) if code == 206 else -1
    return code, got, resp.headers.get("Content-Range", "")


def check_ranges(public_base: str, user_agents) -> int:
    """The gate: does the URL a device uses serve byte ranges, to every client? Returns an exit code.

    ⚠ Once per User-Agent (see CLIENT_USER_AGENTS), and it fails if ANY of them fails. Pass more with
    --user-agent when the apps stop sending one value everywhere; a route that serves one client and
    challenges another is broken for that client, and one run cannot see that.

    ⚠⚠ Reads EVERYTHING through `public_base` — the catalog included — and needs nothing but the
    standard library. It is the check most likely to be run from somewhere other than the migration
    host (a laptop, CI, after a CDN change), so it must not require boto3, cryptography or tfvars,
    and it must not depend on DigitalOcean still answering: this gate outlives that bucket.

    ⚠ Unauthenticated, on purpose. The S3 API path proves nothing about what a client sees, and
    neither does the bucket origin once a custom hostname or a CDN is in front — which is exactly
    where Range gets dropped.

    ⚠⚠ Every range is requested TWICE. An edge cache can answer the first request (a miss, passed to
    the origin, which honours Range) correctly and the second (a hit) from a cached whole object with
    a 200, or with the wrong slice. A single request only ever tests the miss. And one of the two
    ranges sits mid-file, because a cache that stored only a prefix still gets bytes 0-1023 right.
    """
    print(f"  checking ranges through: {public_base}")
    failed = [ua for ua in user_agents if not _check_ranges_as(public_base, ua)]
    if failed:
        print(f"  ⚠⚠ FAILED for User-Agent: {', '.join(repr(ua) for ua in failed)}")
        if len(failed) < len(user_agents):
            # Same route, same bytes, different verdict by client: that is filtering, not Range.
            print("     Other User-Agents passed, so this is the route refusing a client (on a Cloudflare zone:"
                  " Browser Integrity Check or Bot Fight Mode), not Range. See the runbook's stage 5.")
        return 1
    print(f"  OK — every range served twice, exact bytes, to all {len(user_agents)} User-Agent(s)")
    return 0


def _check_ranges_as(public_base: str, user_agent: str) -> bool:
    """One User-Agent's pass of the gate. True when every region's ranges came back exact, twice."""
    print(f"  as User-Agent: {user_agent}")
    try:
        with _open(f"{public_base}/catalog.json", user_agent, 60) as resp:
            catalog = json.load(resp)
    except Exception as e:  # noqa: BLE001
        print(f"  ⚠⚠ FAILED — the catalog itself is not reachable there: {str(e)[:100]}")
        return False
    regions = catalog.get("regions", [])
    if not regions:
        print("  ⚠⚠ FAILED — the catalog there names no regions, so nothing was range-checked")
        return False
    ok = True
    for r in regions:
        url = f"{public_base}/{r['path']}"
        size = r.get("byteSize") or 0
        ranges = [(0, 1023)]
        if size > 4096:
            mid = size // 2
            ranges.append((mid, mid + 1023))
        for first, last in ranges:
            want = last - first + 1
            for attempt in (1, 2):
                try:
                    code, got, content_range = _probe_range(url, first, last, user_agent)
                except Exception as e:  # noqa: BLE001
                    print(f"    {r['id']} bytes={first}-{last} #{attempt}: FAILED {str(e)[:80]}")
                    ok = False
                    continue
                # ⚠ Content-Range must name the slice asked for: a 206 of the right LENGTH from the
                # wrong OFFSET is exactly what a confused cache returns, and PMTiles would decode it
                # as garbage rather than fail.
                # ⚠ And its total must be the catalog's byteSize. A truncated or stale object still
                # longer than the midpoint passes both probes by slice alone, and then fails only when
                # a client reads near the end it was promised.
                exact = content_range.startswith(f"bytes {first}-{last}/")
                if exact and size:
                    exact = content_range == f"bytes {first}-{last}/{size}"
                if code == 206 and got == want and exact:
                    print(f"    {r['id']} bytes={first}-{last} #{attempt}: HTTP 206, {got} bytes")
                else:
                    shown = f"{got} bytes" if got >= 0 else "whole body not read"
                    print(f"    {r['id']} bytes={first}-{last} #{attempt}: HTTP {code}, {shown}, "
                          f"Content-Range={content_range or '(none)'}   ⚠⚠ NOT THIS RANGE")
                    ok = False
    print("    ok — every range served twice, exact bytes" if ok else
          "    ⚠⚠ failed — a 200 means the whole bundle came back and PMTiles' premise is gone")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--verify-existing", action="store_true",
                    help="hash every object IN THE DESTINATION against the catalog: the ones skipped as "
                         "already present, and each new upload right after it lands. Off by default "
                         "because it downloads everything back; on for any run whose result you intend "
                         "to trust.")
    ap.add_argument("--check-ranges", action="store_true",
                    help="verify a byte range returns 206 through --public-base, and copy nothing")
    ap.add_argument("--public-base",
                    help="⚠⚠ REQUIRED for --check-ranges: the public route under test, exactly as a "
                         "client would reach it — the r2.dev URL, the sibling test hostname, or "
                         "https://tiles.service.tenshu.app itself. Never the S3 API endpoint: a gate "
                         "that passes there says nothing about the hostname or edge clients go through.")
    # ⚠ Relative to THIS FILE, not the cwd: the how-to invokes the script by its repo-root path,
    # so a cwd-relative default looks for a terraform.tfvars that is not there.
    ap.add_argument("--tfvars", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "terraform.tfvars"))
    # ⚠⚠ The DigitalOcean BUCKET ORIGIN, not tiles.service.tenshu.app. That hostname is the thing
    # being moved: after the cutover it IS R2, and a source read through it would verify R2 against
    # itself and call the pair in lockstep. The origin is also always current — DigitalOcean's CDN
    # holds catalog.json for up to an hour unless flushed, so a copy that read through it right after
    # a publish would pin and republish the PREVIOUS catalog.
    ap.add_argument("--source-base", default="https://tenshu-tiles.fra1.digitaloceanspaces.com",
                    help="where the catalog and bundles are read FROM (default: the DigitalOcean bucket origin)")
    ap.add_argument("--user-agent", action="append", dest="user_agents", metavar="UA",
                    help="a client User-Agent to test as; repeat for several. Default: "
                         + " and ".join(repr(ua) for ua in CLIENT_USER_AGENTS)
                         + ". ⚠ Never urllib's own, which r2.dev refuses outright.")
    args = ap.parse_args()
    user_agents = args.user_agents or list(CLIENT_USER_AGENTS)

    # ⚠ Validate before touching the filesystem or the network, so the refusal is the first thing a
    # caller sees rather than a traceback about something incidental.
    if args.check_ranges and not args.public_base:
        sys.exit("--check-ranges needs --public-base: the public route under test (r2.dev URL, the\n"
                 "  sibling hostname, or https://tiles.service.tenshu.app). Defaulting to anything else\n"
                 "  would pass while the route clients use drops Range, which is what this gate catches.")
    if args.check_ranges:
        sys.exit(check_ranges(args.public_base.rstrip("/"), user_agents))

    # Everything below is the COPY path. Its dependencies are imported here, not at module level, so
    # the range gate above runs on any machine with a Python — no boto3, no cryptography, no tfvars.
    try:
        import boto3
    except ImportError:
        sys.exit("boto3 is required to copy: pip3 install boto3 (or apt-get install python3-boto3)")
    try:
        tf = open(args.tfvars).read()
    except FileNotFoundError:
        sys.exit(f"no terraform.tfvars at {args.tfvars} — copy terraform.tfvars.example and fill it in, "
                 f"or pass --tfvars")
    # ⚠ Defaults must match deploy/cloudflare-tiles/variables.tf. A tfvars that omits an optional
    # variable is a valid Terraform configuration, and the bucket it created has the default name.
    account_id = tfvar(tf, "account_id")
    bucket = tfvar(tf, "bucket_name", "tenshu-tiles")
    # ⚠⚠ The S3 key pair comes from the ENVIRONMENT, under R2-specific names, and never from tfvars.
    # tfvars holds the Terraform API token's world (account and zone IDs); the R2 key pair is a
    # separate credential (R2 → Manage API tokens). The names are R2_* rather than AWS_* because a
    # shell set up for publishing to DigitalOcean usually has AWS_* exported already, and this client
    # must never quietly pick those up.
    access_key = os.environ.get("R2_ACCESS_KEY_ID")
    secret_key = os.environ.get("R2_SECRET_ACCESS_KEY")
    if not (access_key and secret_key):
        sys.exit("export R2_ACCESS_KEY_ID and R2_SECRET_ACCESS_KEY — an R2 API token's S3 key pair\n"
                 "  (Cloudflare dashboard → R2 → Manage API tokens, Object Read & Write on this bucket).")
    # ⚠⚠ Pin ONE snapshot. Fetching the catalog again at the end would publish a catalog that can
    # reference bundles this run never copied — and can pair a catalog with a signature taken at a
    # different instant. Keep the BYTES (the signature covers bytes, not the parsed object).
    # ⚠ The source is read over public HTTP too, so it sends a client User-Agent for the same reason
    # the gate does: a --source-base on a filtered route would otherwise fail as "unreachable".
    ua = user_agents[0]
    with _open(f"{args.source_base}/catalog.json", ua, 60) as resp:
        catalog_bytes = resp.read()
    with _open(f"{args.source_base}/catalog.json.sig", ua, 60) as resp:
        catalog_sig = resp.read()
    catalog = json.loads(catalog_bytes)
    regions = catalog.get("regions", [])
    print(f"  catalog signature: {verify_catalog(catalog_bytes, catalog_sig)}")
    print(f"  catalog: schemaVersion={catalog.get('schemaVersion')} buildDate={catalog.get('buildDate')} "
          f"regions={len(regions)}")

    # ⚠ Region "auto" is R2's only region.
    # ⚠ The SDK's default request checksums are left ON. Cloudflare removed its boto3 incompatibility
    # note in 2025-07, and checksums are what checks the bytes in flight. ⚠⚠ The opposite failure is
    # the one that matters: a store that doesn't decode `aws-chunked` framing STORES it and reports
    # success (hosting-provider-economics.md D10, which lists R2 among such stores). The size check
    # after every upload catches that — stored framing makes the object larger. If a checksum header
    # is refused instead ("x-amz-checksum-… not implemented"), set
    # AWS_REQUEST_CHECKSUM_CALCULATION=when_required, and --verify-existing stops being optional.
    s3 = boto3.client(
        "s3",
        endpoint_url=f"https://{account_id}.r2.cloudflarestorage.com",
        region_name="auto",
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
    )

    existing = {}
    token = None
    while True:
        kw = {"Bucket": bucket, "MaxKeys": 1000}
        if token:
            kw["ContinuationToken"] = token
        page = s3.list_objects_v2(**kw)
        for o in page.get("Contents", []):
            existing[o["Key"]] = o["Size"]
        if not page.get("IsTruncated"):
            break
        token = page.get("NextContinuationToken")

    wanted = []
    for r in regions:
        for key, size, sha in ((r["path"], r.get("byteSize"), r.get("sha256")),
                               (r.get("overlayPath"), r.get("overlayByteSize"), r.get("overlaySha256"))):
            if key and size:
                wanted.append((key, size, sha))
    todo = [w for w in wanted if existing.get(w[0]) != w[1]]
    # ⚠⚠ Present-at-the-right-size is NOT verified. A corrupt object with the expected byte count is
    # skipped here and would then be covered by a summary claiming every bundle's hash matched — and
    # the client, which checks the catalog's sha256, would reject it. Size agreeing while content does
    # not is the same trap the relay migration hit from the other side (count vs bytes).
    skipped = [w for w in wanted if w not in todo]
    total = sum(s for _, s, _ in todo)
    print(f"  already present: {len(existing)} objects;  to copy: {len(todo)} ({total/1e9:.3f} GB)"
          f";  skipped as same-size: {len(skipped)}")

    # ⚠ Same size is not same publication. An object copied by an earlier version of this script — or
    # by any tool that omitted metadata — would otherwise be skipped for ever with no Cache-Control and
    # a default Content-Type, and neither a re-run nor --verify-existing would notice. HEAD each skipped
    # object and rewrite its metadata in place when it differs (server-side CopyObject onto itself,
    # which is within R2's 5 GiB single-request limit for every bundle published today; the largest
    # is 2.0 GB). --dry-run reports without changing anything.
    stale_meta = []
    for key, _size, _sha in skipped:
        h = s3.head_object(Bucket=bucket, Key=key)
        if (h.get("CacheControl"), h.get("ContentType")) != (BUNDLE_METADATA["CacheControl"],
                                                             BUNDLE_METADATA["ContentType"]):
            stale_meta.append(key)
    if stale_meta:
        print(f"  skipped objects whose metadata differs from the publisher's: {len(stale_meta)}")
        for key in stale_meta:
            if args.dry_run:
                print(f"    would rewrite metadata on {key}")
                continue
            s3.copy_object(Bucket=bucket, Key=key, CopySource={"Bucket": bucket, "Key": key},
                           MetadataDirective="REPLACE", **BUNDLE_METADATA)
            print(f"    rewrote metadata on {key}")

    # Objects whose bytes were hashed AS STORED in the destination — the number the summary reports.
    # ⚠ Not the same as "copied": a new upload's SOURCE stream is always hashed, but that proves what
    # left this machine, not what the destination kept.
    in_dest_verified = 0
    if skipped and args.verify_existing:
        print("  verifying skipped objects against the catalog (downloads them back)…")
        bad = []
        for key, size, want in skipped:
            if not want:
                continue
            state = "ok" if remote_sha256(s3, bucket, key) == want else "MISMATCH"
            print(f"    {key}: {state}")
            if state == "ok":
                in_dest_verified += 1
            else:
                bad.append((key, size, want))
        if bad and args.dry_run:
            # The gate (Lockstep) reports; it never repairs.
            sys.exit(f"    FAILED: {len(bad)} existing object(s) do not match the catalog: {[k for k, _, _ in bad]}")
        if bad:
            # ⚠ Re-copy rather than stop. A same-size mismatch is classified as "skipped" on EVERY run —
            # including one left by an upload the read-back check below refused — so exiting here would
            # make the copy unable to converge without a manual delete. The upload overwrites the key.
            print(f"    re-copying {len(bad)} mismatched object(s) — they will be hashed again after upload")
            skipped = [w for w in skipped if w not in bad]
            todo.extend(bad)
    elif skipped:
        print("  ⚠ those were matched on SIZE ONLY and not hashed — pass --verify-existing to check them")
    if args.dry_run:
        for k, s, _ in todo:
            print(f"    would copy {k} ({s} bytes)")
        # ⚠⚠ `--dry-run --verify-existing` is a GATE (the runbook's Lockstep), not a plan. As a gate, an
        # object the catalog names that is missing or the wrong size is a failure: exiting 0 after
        # "would copy" would let a cutover finish with a region that cannot be downloaded.
        if args.verify_existing and todo:
            sys.exit(f"    FAILED: {len(todo)} object(s) the catalog names are missing from the destination "
                     f"or the wrong size — the destination does not serve this catalog")
        return

    for i, (key, size, want_sha) in enumerate(todo, 1):
        url = f"{args.source_base}/{key}"
        h = hashlib.sha256()
        print(f"  [{i}/{len(todo)}] {key} ({size/1e9:.3f} GB)…", flush=True)
        # ⚠ Buffer to disk rather than holding in memory: these are gigabyte objects, and a
        # multipart upload needs a seekable body anyway.
        tmp = f"/var/tmp/tiles-{os.path.basename(key)}"
        with _open(url, ua, 120) as resp, open(tmp, "wb") as out:
            while True:
                b = resp.read(CHUNK)
                if not b:
                    break
                h.update(b)
                out.write(b)
        got_sha = h.hexdigest()
        if want_sha and got_sha != want_sha:
            os.unlink(tmp)
            sys.exit(f"    FAILED: {key} sha256 {got_sha} != catalog {want_sha} — source is corrupt, nothing uploaded")
        s3.upload_file(tmp, bucket, key, ExtraArgs=BUNDLE_METADATA)
        after = s3.head_object(Bucket=bucket, Key=key)["ContentLength"]
        os.unlink(tmp)
        if after != size:
            sys.exit(f"    FAILED: uploaded {size} bytes but the object is {after}")
        # ⚠ A size match does not see a same-length corruption, and with the SDK's request checksums
        # turned off (the runbook's fallback) nothing else would. --verify-existing is the promise
        # that the destination's bytes were read back, so it covers new uploads too, before the
        # catalog that points at them is published.
        if args.verify_existing and want_sha:
            if remote_sha256(s3, bucket, key) != want_sha:
                sys.exit(f"    FAILED: {key} reads back from the destination with a different sha256 — "
                         f"nothing published")
            in_dest_verified += 1
            print(f"    ok, source sha256 matched the catalog, and the stored object hashes the same")
        else:
            print(f"    ok, source sha256 matched the catalog; stored object size-checked only")

    # ⚠ Re-read the source catalog and refuse to publish if it moved under us: a new build during a
    # multi-gigabyte transfer means the bundles copied no longer match the catalog about to be
    # published. Restart rather than publish a catalog whose objects are not all here.
    with _open(f"{args.source_base}/catalog.json", ua, 60) as resp:
        now_bytes = resp.read()
    if now_bytes != catalog_bytes:
        sys.exit("    FAILED: the source catalog changed during the copy — a new build was published.\n"
                 "    Nothing was published here. Re-run; the bundles already copied will be skipped.")
    # ⚠ And re-verify the pair. The check above only proves the catalog is the one whose bundles were
    # copied; this proves the signature about to be published belongs to it.
    print(f"  re-checked before publishing: {verify_catalog(catalog_bytes, catalog_sig)}")
    for name, data in (("catalog.json", catalog_bytes), ("catalog.json.sig", catalog_sig)):
        s3.put_object(Bucket=bucket, Key=name, Body=data, **CATALOG_METADATA[name])
        # ⚠ Read back, byte for byte. The bundles get a size check after upload; these two are the
        # objects a client verifies FIRST, and a store that kept a request's framing (see the client
        # above) would hand every client a catalog that fails its signature.
        back = s3.get_object(Bucket=bucket, Key=name)["Body"].read()
        if back != data:
            sys.exit(f"    FAILED: {name} reads back as {len(back)} bytes, not the {len(data)} written")
        print(f"  {name}: {len(data)} bytes, read back identical (the snapshot this run verified against)")

    print(f"  OK — {len(todo)} copied; {in_dest_verified} of {len(wanted)} object(s) hash-verified "
          f"AS STORED in the destination")
    if not args.verify_existing:
        print(f"  ⚠ the destination's copies were size-checked, not hashed — pass --verify-existing to read them back")
    print(f"  now, through the public route under test (never the S3 endpoint):")
    # sys.argv[0], not basename(__file__): it is the path the operator actually typed, so the
    # printed command runs from wherever this one was run.
    print(f"    python3 {sys.argv[0]} --check-ranges --public-base https://<r2.dev URL | sibling | tiles.service.tenshu.app>")


if __name__ == "__main__":
    main()
