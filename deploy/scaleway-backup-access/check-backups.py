#!/usr/bin/env python3
"""Did every backup job run? Read-only, from a workstation, with the ADMIN key in terraform.tfvars.

The boxes' own logs can only say what the box thinks happened. This asks the buckets:

- the four snapshot buckets: age of the newest object (nightly ⇒ stale past 26 h);
- the two mirrors: the ledger's `updated` stamp, which every non-dry run rewrites (weekly ⇒ stale past
  8 days). A mirror run that dies before writing the ledger leaves it stale, which is the point.

    python3 check-backups.py            # exit 0 all fresh · 1 something stale or unreadable

Bucket names and regions come from `terraform output -json buckets` in this directory.
"""
import datetime as dt
import json
import re
import subprocess
import sys

NIGHTLY = dt.timedelta(hours=26)
WEEKLY = dt.timedelta(days=8)
LEDGER_KEY = "_tenshu-mirror/missing-since.json"


def tfvar(text, key):
    m = re.search(rf'^\s*{key}\s*=\s*"([^"]+)"', text, re.M)
    if not m:
        sys.exit(f"{key} not found in terraform.tfvars")
    return m.group(1)


def newest(s3, bucket):
    best, token = None, None
    while True:
        kw = {"Bucket": bucket, "MaxKeys": 1000, **({"ContinuationToken": token} if token else {})}
        page = s3.list_objects_v2(**kw)
        for o in page.get("Contents", []):
            if not o["Key"].startswith("_probe/") and (best is None or o["LastModified"] > best["LastModified"]):
                best = o
        if not page.get("IsTruncated"):
            return best
        token = page["NextContinuationToken"]


def main():
    import boto3
    tf = open("terraform.tfvars").read()
    access, secret = tfvar(tf, "access_key"), tfvar(tf, "secret_key")
    buckets = json.loads(subprocess.run(["terraform", "output", "-json", "buckets"], check=True,
                                        capture_output=True, text=True).stdout)
    now = dt.datetime.now(dt.timezone.utc)
    stale = 0
    for bid, b in buckets.items():
        if bid == "content":
            continue
        s3 = boto3.client("s3", endpoint_url=f"https://s3.{b['region']}.scw.cloud", region_name=b["region"],
                          aws_access_key_id=access, aws_secret_access_key=secret)
        try:
            if "mirror" in bid:
                doc = json.loads(s3.get_object(Bucket=b["bucket"], Key=LEDGER_KEY)["Body"].read())
                when = dt.datetime.strptime(doc["updated"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
                limit, what = WEEKLY, "ledger updated"
            else:
                o = newest(s3, b["bucket"])
                if o is None:
                    raise RuntimeError("empty bucket")
                when, limit, what = o["LastModified"], NIGHTLY, o["Key"]
        except Exception as e:  # noqa: BLE001
            print(f"  ⛔ {b['bucket']:34} ({b['region']}): {str(e)[:100]}")
            stale += 1
            continue
        age = now - when
        ok = age <= limit
        stale += not ok
        print(f"  {'ok' if ok else '⛔ STALE'}  {b['bucket']:34} ({b['region']}) {what} — {age.total_seconds() / 3600:.1f} h ago")
    sys.exit(1 if stale else 0)


if __name__ == "__main__":
    main()
