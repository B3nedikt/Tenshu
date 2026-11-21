# OVHcloud project resources

The SSH key the boxes boot with, the four buckets, and one S3 key per job, each limited by its
policy to the buckets that job needs. The move itself is
[`docs/how-to/move-to-ovh.md`](../../docs/how-to/move-to-ovh.md).

## Credentials

All OVH modules read the API credentials from the environment, never from tfvars:

```bash
export OVH_ENDPOINT=ovh-eu
export OVH_APPLICATION_KEY=...
export OVH_APPLICATION_SECRET=...
export OVH_CONSUMER_KEY=...
```

Create them at <https://eu.api.ovh.com/createToken/> with `GET`, `POST`, `PUT` and `DELETE` on
**both** `/cloud/project/<project id>/*` and `/publicCloud/project/<project id>/*` (eight
rights). ⚠ The second one is for the v2 API (SSH key, buckets, security groups, instances) and is
written **without** the `/v2` prefix — rules match the path after the branch prefix, just as
`/cloud/…` matches `/1.0/cloud/…`. A rule spelled `/v2/publicCloud/…` is accepted and matches
nothing: the S3 users get created, then everything else fails with 403 *"This call has not been
granted"*. Rights cannot be added to an existing token; make a new one. ⚠ Scope them to the
project, not `/*`: the same token type can order services and change the account.

⚠ **A new OVH project has outgoing SMTP (port 25) restricted, and while it is, the API refuses
every security-group change** (403 `SMTPPort25Restricted`). The hosts cannot be created until OVH
support lifts it. Our mail goes out on an off-list submission port, never 25 — the box also gets no answer on
587, so it is Mailgun's 2525 since 2026-11-19 (`docs/how-to/set-up-otp-email.md` §A.5); the
restriction matters only because it blocks the security groups.

## Use

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored
terraform init && terraform apply
terraform output buckets
terraform output -raw relay_env_overrides      # ⚠ secret keys; deploy/ovh-migrate reads these itself
```

## ⚠ What will bite

- **Bucket names are global** across OVH and are not re-creatable for a while after deletion. If
  `tenshu-ovh-…` is taken, change `bucket_prefix` before the first apply, not after.
- **`prevent_destroy` on every bucket.** The provider empties a bucket before deleting it, and the
  content bucket is the only copy of user content once Scaleway is gone.
- **The policy is what makes a key least-privilege.** An `objectstore_operator` user with no
  policy reaches every bucket in the project. Run the negative checks in the runbook once after
  the first apply.
- **S3 secrets live in the Terraform state** (gitignored here, like every module's state).
