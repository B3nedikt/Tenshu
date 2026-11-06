#!/usr/bin/env python3
"""Prove what each minted key can and cannot do — before a box depends on it, and after every switch.

Run from THIS directory after `terraform apply`. It reads the keys with `terraform output` (never from
a file, never from argv) and the bucket map from `terraform output -json buckets`.

    python3 probe-keys.py                 # every key
    python3 probe-keys.py relay_backup    # one key

⚠⚠ Non-destructive by construction:
- "can it delete?" is asked by deleting a key that does NOT exist (`_probe/absent-<uuid>`). S3 answers
  204 when the delete is allowed and 403 when it is not, and there is nothing to lose either way. (On
  the versioned nl-ams snapshot buckets an ALLOWED delete would leave a zero-byte delete marker — which
  is itself the failure being reported.)
- "can it write?" is asked only on the snapshot buckets, with a tiny `_probe/<stamp>` object that their
  30-day lifecycle removes. Never in a mirror: a stray key there would enter the prune ledger.

Expectations depend on the staged switches (`terraform output policed_buckets`): an unpoliced bucket is
governed by IAM alone, so a probe there is reported as "IAM only" rather than failed.

Exit 0: every probe matched · 1: at least one key can do something it must not, or cannot do its job.
"""
import base64
import datetime as dt
import hashlib
import json
import subprocess
import sys
import uuid

ALLOW, DENY = "allow", "deny"
PROBE_BODY = b"probe"
# Object-Locked buckets want an integrity header on every PUT (see backup-relay-db.sh).
PROBE_MD5 = base64.b64encode(hashlib.md5(PROBE_BODY).digest()).decode()

# key -> [(bucket id, operation, expected)]. Operations: put, head (of the put), list, delete-absent.
# ⚠ "list" on a backup key's OWN primary bucket must be ALLOW at every stage: the nightly size floor lists
# it, and a DENY there is every night failing "cannot list" (backup-*-db.sh). The second region is never
# listed, so its policy withholds ListBucket.
EXPECT = {
    "relay_backup": [
        ("relay_backup", "put", ALLOW), ("relay_backup", "list", ALLOW), ("relay_backup", "delete-absent", DENY),
        ("relay_backup_dr", "put", ALLOW), ("relay_backup_dr", "list", DENY), ("relay_backup_dr", "delete-absent", DENY),
        ("commerce_backup_dr", "put", DENY), ("blob_mirror_dr", "list", DENY),
        ("commerce_backup", "list", DENY), ("content", "list", DENY),   # BucketsRead: "IAM only" until policed
        ("content", "delete-absent", DENY),
    ],
    "commerce_backup": [
        ("commerce_backup", "put", ALLOW), ("commerce_backup", "list", ALLOW), ("commerce_backup", "delete-absent", DENY),
        ("commerce_backup_dr", "put", ALLOW), ("commerce_backup_dr", "list", DENY),
        ("commerce_backup_dr", "delete-absent", DENY),
        ("relay_backup_dr", "put", DENY), ("relay_backup", "list", DENY),   # BucketsRead: "IAM only" until policed
        ("blob_mirror", "list", DENY), ("blob_mirror_dr", "list", DENY),
        ("content", "list", DENY), ("content", "delete-absent", DENY),
    ],
    "blob_mirror": [
        ("content", "list", ALLOW), ("content", "delete-absent", DENY),
        ("blob_mirror", "list", ALLOW), ("blob_mirror", "delete-absent", ALLOW),
        ("blob_mirror_dr", "list", ALLOW), ("blob_mirror_dr", "delete-absent", DENY),
        ("relay_backup_dr", "delete-absent", DENY), ("commerce_backup_dr", "list", DENY),
    ],
    "relay_content": [
        ("content", "list", ALLOW), ("content", "delete-absent", ALLOW),
        ("relay_backup", "delete-absent", DENY), ("relay_backup_dr", "delete-absent", DENY),
        ("blob_mirror", "delete-absent", DENY), ("blob_mirror_dr", "delete-absent", DENY),
        ("commerce_backup_dr", "delete-absent", DENY),
    ],
}
ENV_OUTPUT = {"relay_backup": ("relay_box_env", "BACKUP_S3"), "blob_mirror": ("relay_box_env", "MIRROR_S3"),
              "commerce_backup": ("commerce_box_env", "BACKUP_S3"), "relay_content": ("relay_content_env", "S3")}


def tf_output(name, as_json=False):
    cmd = ["terraform", "output", "-json" if as_json else "-raw", name]
    out = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout
    return json.loads(out) if as_json else out


def parse_env(text, prefix):
    vals = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
    return vals.get(f"{prefix}_ACCESS_KEY"), vals.get(f"{prefix}_SECRET_KEY")


def classify(error_code):
    """S3 error code -> allow/deny. None (no error) is allow. ⚠ Anything that is neither is an ERROR, never
    read as a deny: a 'deny' that is really a typo'd bucket would make a too-broad key look tight."""
    if error_code is None:
        return ALLOW
    if error_code in ("AccessDenied", "403", "Forbidden"):
        return DENY
    return f"error:{error_code}"


def attempt(s3, bucket, op, stamp):
    try:
        if op == "put":
            s3.put_object(Bucket=bucket, Key=f"_probe/{stamp}", Body=PROBE_BODY, ContentMD5=PROBE_MD5)
        elif op == "list":
            s3.list_objects_v2(Bucket=bucket, MaxKeys=1)
        elif op == "delete-absent":
            s3.delete_object(Bucket=bucket, Key=f"_probe/absent-{uuid.uuid4()}")
        return classify(None)
    except Exception as e:  # noqa: BLE001 - botocore ClientError
        return classify(str(getattr(e, "response", {}).get("Error", {}).get("Code", type(e).__name__)))


def main():
    import boto3
    which = sys.argv[1:] or list(EXPECT)
    buckets = tf_output("buckets", as_json=True)
    policed = tf_output("policed_buckets", as_json=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    bad = 0
    for name in which:
        out_name, prefix = ENV_OUTPUT[name]
        access, secret = parse_env(tf_output(out_name), prefix)
        if not access:
            print(f"{name}: no key in `terraform output {out_name}` — skipped")
            continue
        print(f"{name} ({access}):")
        for bid, op, want in EXPECT[name]:
            b = buckets[bid]
            s3 = boto3.client("s3", endpoint_url=f"https://s3.{b['region']}.scw.cloud", region_name=b["region"],
                              aws_access_key_id=access, aws_secret_access_key=secret)
            got = attempt(s3, b["bucket"], op, stamp)
            is_policed = policed.get(b["bucket"]) == "policed"
            if got == want:
                verdict = "ok"
            elif not is_policed and want == DENY and got == ALLOW:
                verdict = "IAM only — not policed yet (expected until its switch is on)"
            else:
                verdict = "⛔ UNEXPECTED"
                bad += 1
            print(f"  {op:14} {b['bucket']:34} want {want:5} got {got:12} {verdict}")
    print("PASS" if not bad else f"FAIL: {bad} unexpected result(s)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
