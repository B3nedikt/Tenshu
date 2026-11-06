# Dev lab on Scaleway (Terraform)

One Scaleway instance running an **isolated copy** of the Tenshu server plane — relay + commerce
(+ Postgres) + apex behind one Caddy, with a mail sink — against a throwaway domain. This is
DL-1/DL-2 of [`docs/proposals/ops-dev-lab.md`](../../docs/proposals/ops-dev-lab.md).

**Do not start here.** An SSH key, a Project, an API key, a DNS delegation and a PAT all have to
exist first, and one of them is unrecoverable if you get the order wrong. The walkthrough is
[`docs/how-to/deploy-dev-lab-scaleway.md`](../../docs/how-to/deploy-dev-lab-scaleway.md).

```bash
cp terraform.tfvars.example terraform.tfvars   # gitignored; the runbook names every screen
terraform init
./tf.sh plan                                   # terraform, with the five secrets from the Keychain
./tf.sh apply
terraform output verify_hint
```

## How this differs from the three production Scaleway modules

`scaleway-relay`, `scaleway-commerce` and `scaleway-apex` each run one service on its own box,
because that is what production needs. This module runs **all three on one box** against a
throwaway domain, because that is what a dev plane needs: a third of the cost, one IP, and a reset
that is one command rather than three. It is a sibling of those modules, not a variant — it shares
no state and no resources with them.

⚠⚠ **Use a separate Scaleway Project.** Projects are free, and it is the cheapest isolation in the
design: a `terraform destroy` here then cannot reach the production relay even if aimed at the
wrong state file.

| File | Purpose |
|------|---------|
| `versions.tf` | Terraform + `scaleway/scaleway` pins, provider auth. |
| `variables.tf` | Inputs. Read `project_id` and the SSH-key comment before applying. |
| `main.tf` | Plan-time guards, volume, IP, security group, instance, three A records. |
| `cloud-init.yaml.tftpl` | First boot: Docker, volume mount, clone, stack up. |
| `outputs.tf` | URLs, ssh, the mailpit tunnel, and `verify_hint`. |
| `stack/dev-overlay.yml` | ⚠⚠ An **overlay** over `sync-relay-server/` + `commerce-service/`'s own composes, not a stack — it adds Postgres, mailpit, apex and the three-site Caddyfile and nothing else. Alone it starts a Caddy with nothing behind it. |
| `stack/Caddyfile` | TLS for the three hostnames. |

## The Scaleway traps

Every one of these also applies to `deploy/scaleway-relay` and its runbook. They are repeated in this module's comments because each fails **silently** —
a clean apply and a box serving nothing.

1. **`user_data` is a map, not a string.** Scaleway takes cloud-init under a `cloud-init` key.
   A bare string does not error; the box boots clean with nothing installed.
2. **Security groups default to ACCEPT.** `inbound_default_policy = "drop"` is set explicitly.
   Omit it and every port is open, with no error anywhere.
3. ⚠⚠ **SSH keys come from the PROJECT, at first boot only.** There is no SSH-key argument on
   `scaleway_instance_server`. Register the key in the console **before** `terraform apply` —
   adding it afterwards does not reach a running instance, and you get `Permission denied
   (publickey)` on a perfectly healthy box. Recovery is a rebuild or the console's VNC.
4. **`iops` is required on the volume**, and accepted values are tier-dependent — a rejected
   number fails at *create*, not at plan.
5. **Instance and volume must share a zone.** A cross-zone id fails at create.
6. **The root volume fills.** 30 GB here rather than the relay module's 20: this box extracts six
   images to the relay's one. A 9 GB relay root has hit 100 % and failed a pull with
   `no space left on device` while its 46 GB data volume sat at 1 %.
7. ⚠ The *preferred Project* trap from `deploy/models/` and `deploy/scaleway-tiles/` does **not**
   apply here — that one is S3-only, and this module creates no bucket.

## ⚠⚠ Verify in this order — a green health check is not enough

```bash
ssh root@<ip> 'cloud-init status --long'   # 1. bootstrap finished
ssh root@<ip> 'findmnt /mnt/tenshu'        # 2. the DURABLE volume is mounted
curl -fsS https://relay.<domain>/health    # 3. the relay answers
```

Step 2 is the one that matters and the one that is easy to skip. The cloud-init **warns and carries
on** if it cannot find the data volume, putting state on the root disk — where a rebuild destroys
it. The live relay answered `{"status":"ok","durable":true}` for five days holding zero rows;
`durable:true` means *a database is configured and its volume is writable*, not that your data is
there.

## ⚠ What `terraform plan` does not tell you

It checks schema and this module's own guards. It does **not** check that `ubuntu_jammy` is the
right image label, that `DEV1-M` exists in your zone, that the volume attaches, that your SSH key
was on the Project in time, or that cloud-init succeeded.
