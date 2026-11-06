# Least-privilege keys for the backup jobs

One IAM application + API key per backup job (relay snapshots, commerce dumps, blob mirror) and one for
the relay's content plane, plus a bucket policy on every backup bucket naming only the admins and the
job that writes it. Replaces the single project-wide admin key every job used to run on
(`docs/tech-debt.md`, *"Two backup buckets, one key"*).

⚠⚠ **Prepared, staged, and not self-applying.** Read `main.tf`'s header for what Scaleway can and
cannot express, then follow *Applying the backup hardening* in
[`docs/how-to/back-up-the-relay-database.md`](../../docs/how-to/back-up-the-relay-database.md) — the
order matters, because a bucket policy that omits the principal a box is *currently* using stops that
box's job the same night.

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored; the ADMIN key, admin_principals
terraform init
terraform test                                 # offline: the rendered policies (Terraform >= 1.7)
terraform plan                                 # read every policy document before applying
```

After an apply, and after every switch:

```bash
python3 probe-keys.py      # each key against each bucket: allowed where it must be, denied where it must not.
                           # Non-destructive: "can it delete?" deletes a key that does not exist.
python3 check-backups.py   # did every job run? newest snapshot < 26 h, each mirror ledger < 8 days
```

⚠ **The state holds every key this module mints.** Keep it where the admin key lives, never on a box.
Read secrets with `terraform output -raw relay_box_env | ssh root@<box> …` (the runbook does this) —
never into a file in the checkout.

| switch | default | flips at runbook step |
|---|---|---|
| `enforce_on_existing_buckets` | `false` | 11 — after both boxes ran on the new keys, and the relay's content key is rotated |
| `create_relay_content_key` | `true` | minted at 3, installed on the relay at 10 |
| `enforce_on_content_bucket` | `false` | 12 — last; production content plane |
