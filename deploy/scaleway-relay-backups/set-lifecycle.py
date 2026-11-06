#!/usr/bin/env python3
"""Apply (and verify) the backup buckets' expiration rules — the primary bucket and its second-region copy.

⚠ Terraform cannot express this: the Scaleway provider serialises
`expired_object_delete_marker` alongside `days` and Scaleway's S3 rejects the pair with
`MalformedXML`, failing AFTER the bucket is created and leaving it tainted. See main.tf.

Idempotent — run it after `terraform apply`, and re-run it any time to check what the
buckets actually report. Reads credentials from terraform.tfvars in this directory.

    python3 set-lifecycle.py [--days N] [--target primary|dr|all]

`primary` is `bucket_name` in `region`; `dr` is `dr_bucket_name` in `dr_region` (skipped, with a note,
if the tfvars has none). ⚠ A VERSIONED bucket (the Object-Locked second-region copy) gets a second
clause: expiring the current version only turns it into a noncurrent one, which is still stored and
billed, so `NoncurrentVersionExpiration` removes it a day later — or when its Object Lock lapses,
whichever is later. Whether Scaleway honours that clause is checked by the read-back below, not
assumed. ⚠ This file is identical in deploy/scaleway-relay-backups and deploy/scaleway-commerce-backups;
change both.
"""
import argparse
import re
import sys

try:
    import boto3
except ImportError:
    sys.exit("boto3 is required: pip3 install boto3 (or apt-get install python3-boto3)")

NONCURRENT_DAYS = 1


def tfvar(text: str, key: str, default: str | None = None) -> str:
    m = re.search(rf'^\s*{key}\s*=\s*"([^"]+)"', text, re.M)
    if m:
        return m.group(1)
    if default is not None:
        return default
    sys.exit(f"{key} not found in terraform.tfvars")


def rule_for(days: int, versioned: bool) -> dict:
    rule = {
        "ID": "expire-daily-snapshots",
        "Status": "Enabled",
        "Filter": {"Prefix": ""},
        "Expiration": {"Days": days},
    }
    if versioned:
        rule["NoncurrentVersionExpiration"] = {"NoncurrentDays": NONCURRENT_DAYS}
    return rule


def apply(s3, bucket: str, days: int) -> bool:
    versioned = s3.get_bucket_versioning(Bucket=bucket).get("Status") == "Enabled"
    s3.put_bucket_lifecycle_configuration(
        Bucket=bucket, LifecycleConfiguration={"Rules": [rule_for(days, versioned)]})

    # ⚠ Read it back. A PUT that the endpoint accepted is not the same fact as a rule the bucket
    # reports — the whole reason this file exists is a lifecycle request that looked fine and wasn't.
    got = s3.get_bucket_lifecycle_configuration(Bucket=bucket)["Rules"]
    for rule in got:
        exp = rule.get("Expiration", {})
        nc = rule.get("NoncurrentVersionExpiration", {})
        noncurrent = f" noncurrent_days={nc.get('NoncurrentDays')}" if nc else ""
        print(f"  {bucket}: rule={rule.get('ID')} status={rule.get('Status')} expiration_days={exp.get('Days')}"
              f"{noncurrent} versioned={versioned}")
    ok = any(r.get("Expiration", {}).get("Days") == days for r in got)
    if versioned:
        ok = ok and any(r.get("NoncurrentVersionExpiration", {}).get("NoncurrentDays") == NONCURRENT_DAYS
                        for r in got)
    if not ok:
        print(f"  FAILED: {bucket} does not report a {days}-day expiration"
              f"{' with noncurrent-version expiry' if versioned else ''}")
        return False
    print(f"  OK — {bucket} expires objects after {days} days"
          f"{f' (noncurrent versions {NONCURRENT_DAYS} day later, or when their lock lapses)' if versioned else ''}")
    return True


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=None, help="retention in days (default: retention_days from tfvars, else 30)")
    ap.add_argument("--target", choices=("primary", "dr", "all"), default="all")
    ap.add_argument("--tfvars", default="terraform.tfvars")
    args = ap.parse_args()

    tf = open(args.tfvars).read()
    days = args.days if args.days is not None else int(tfvar(tf, "retention_days", "30"))
    targets = []
    if args.target in ("primary", "all"):
        targets.append((tfvar(tf, "bucket_name"), tfvar(tf, "region", "fr-par")))
    if args.target in ("dr", "all"):
        dr_bucket = tfvar(tf, "dr_bucket_name", "")
        if dr_bucket:
            targets.append((dr_bucket, tfvar(tf, "dr_region", "nl-ams")))
        elif args.target == "dr":
            sys.exit("dr_bucket_name not found in terraform.tfvars")
        else:
            print("  (no dr_bucket_name in terraform.tfvars — second region skipped)")

    ok = True
    for bucket, region in targets:
        s3 = boto3.client(
            "s3",
            endpoint_url=f"https://s3.{region}.scw.cloud",
            region_name=region,
            aws_access_key_id=tfvar(tf, "access_key"),
            aws_secret_access_key=tfvar(tf, "secret_key"),
        )
        ok = apply(s3, bucket, days) and ok
    if not ok:
        sys.exit("FAILED: at least one bucket does not report the expected rule")


if __name__ == "__main__":
    main()
