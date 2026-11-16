#!/usr/bin/env python3
"""Copy the relay's content-plane bucket from Scaleway to OVH. Runs ON the new relay box.

    python3 copy-bucket.py SOURCE_ENV DEST_ENV [--verify-all]

SOURCE_ENV and DEST_ENV are deploy.env files; each supplies S3_ENDPOINT / S3_BUCKET / S3_REGION /
S3_ACCESS_KEY / S3_SECRET_KEY. On the box they are /opt/tenshu/deploy.env.scaleway (the untouched
copy) and /opt/tenshu/deploy.env (after the OVH overrides).

Safe to run while the old relay is live, and safe to run again; the source is never written.

⚠⚠ The bucket is NOT write-once, so a size-only delta is not enough. File chunks are named
`blob-<sha256(ciphertext)>` and never change, but relation snapshots are named after a hash of
their ADDRESS (`RelationSliceSnapshot.ref`) and are overwritten in place, possibly at the same
size. So:

  - without flags: copies keys that are missing or differ in size (the cheap pass, relay live);
  - `--verify-all`: also reads every key on BOTH sides and re-copies any whose bytes differ. Run it
    once the old relay is stopped; it is the pass that proves the copy.

Each copied object is read back from the destination and compared by sha256. The run ends with a
count/size comparison and exits non-zero unless every source key is present (and, with
`--verify-all`, byte-identical). Keys only at the destination are reported, not deleted.

Exit codes: 0 match, 1 mismatch or hash failure, 2 bad configuration.
"""
import hashlib
import sys

import boto3
from botocore.config import Config

def read_env(path):
    env = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def client(env, label):
    missing = [k for k in ("S3_ENDPOINT", "S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY") if not env.get(k)]
    if missing:
        print(f"⛔ {label}: {', '.join(missing)} unset")
        sys.exit(2)
    # ⚠ Plain checksums, no aws-chunked uploads: some S3-compatible stores store the chunk framing as
    # payload (the relay's S3BlobStore turns chunked encoding off for the same reason).
    # botocore < 1.36 (Ubuntu 22.04 ships 1.23) has neither option and never sends aws-chunked.
    try:
        cfg = Config(request_checksum_calculation="when_required", response_checksum_validation="when_required",
                     retries={"max_attempts": 8, "mode": "standard"})
    except TypeError:
        cfg = Config(retries={"max_attempts": 8, "mode": "standard"})
    s3 = boto3.client("s3", endpoint_url=env["S3_ENDPOINT"], region_name=env.get("S3_REGION") or None,
                      aws_access_key_id=env["S3_ACCESS_KEY"], aws_secret_access_key=env["S3_SECRET_KEY"],
                      config=cfg)
    return s3, env["S3_BUCKET"], env.get("S3_KEY_PREFIX", "")


def listing(s3, bucket, prefix):
    out = {}
    for page in s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix):
        for o in page.get("Contents", []):
            out[o["Key"]] = o["Size"]
    return out


def get(s3, bucket, key):
    return s3.get_object(Bucket=bucket, Key=key)["Body"].read()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    verify_all = "--verify-all" in sys.argv
    src, sbucket, sprefix = client(read_env(sys.argv[1]), "source")
    dst, dbucket, dprefix = client(read_env(sys.argv[2]), "destination")
    if sprefix != dprefix:
        print(f"⛔ S3_KEY_PREFIX differs (source {sprefix!r}, destination {dprefix!r}); keys would not line up")
        sys.exit(2)
    if (src.meta.endpoint_url, sbucket) == (dst.meta.endpoint_url, dbucket):
        print("note: source and destination are the same bucket; nothing will be written")

    print(f"source      : {sbucket} at {src.meta.endpoint_url}")
    print(f"destination : {dbucket} at {dst.meta.endpoint_url}")
    s_objs = listing(src, sbucket, sprefix)
    d_objs = listing(dst, dbucket, dprefix)
    todo = {k for k, size in s_objs.items() if d_objs.get(k) != size}
    print(f"source has {len(s_objs)} objects ({sum(s_objs.values())} bytes); {len(todo)} missing or a different size")

    if verify_all:
        same = sorted(set(s_objs) - todo)
        print(f"comparing the bytes of {len(same)} objects present on both sides ...")
        changed = [k for k in same if sha(get(src, sbucket, k)) != sha(get(dst, dbucket, k))]
        print(f"  {len(changed)} differ in content (overwritten since the last pass)")
        todo |= set(changed)

    bad = []
    same_bucket = (src.meta.endpoint_url, sbucket) == (dst.meta.endpoint_url, dbucket)
    for i, key in enumerate(sorted(todo), 1):
        if same_bucket:
            bad.append(f"{key}: differs from itself?")
            continue
        data = get(src, sbucket, key)
        dst.put_object(Bucket=dbucket, Key=key, Body=data)
        if sha(get(dst, dbucket, key)) != sha(data):
            bad.append(f"{key}: destination bytes differ from what was written")
        if i % 100 == 0 or i == len(todo):
            print(f"  copied {i}/{len(todo)}")

    d_objs = listing(dst, dbucket, dprefix)
    missing = [k for k, size in s_objs.items() if d_objs.get(k) != size]
    extra = [k for k in d_objs if k not in s_objs]
    print(f"source      : {len(s_objs)} objects, {sum(s_objs.values())} bytes")
    print(f"destination : {len(s_objs) - len(missing)} of them present at the same size")
    if extra:
        print(f"  ⚠ {len(extra)} keys exist only at the destination (left alone), e.g. {extra[:3]}")
    for b in bad[:20]:
        print(f"  ⛔ {b}")
    if missing or bad:
        print(f"⛔ NOT a match: {len(missing)} missing/different, {len(bad)} failed")
        sys.exit(1)
    print("✅ every source object is at the destination" + (", byte-identical" if verify_all else " at the same size (sizes only; run --verify-all with the relay stopped)"))


if __name__ == "__main__":
    main()
