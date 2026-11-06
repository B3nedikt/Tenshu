# DigitalOcean relay deploy (Terraform)

> ⚠⚠ **RETIRED — do not `terraform apply` this module.** Production runs on
> [`deploy/scaleway-relay/`](../scaleway-relay/). This module's state still owns the `relay` A record at
> the old reserved IP, so an apply "corrects" production DNS back to the dead droplet. The zone is also
> shared with `deploy/tiles/` and is the rollback for the Cloudflare DNS move. Teardown only, in this
> order: `terraform state rm 'digitalocean_record.relay_a[0]' 'digitalocean_domain.zone[0]'`, then
> `terraform plan -destroy` must list exactly the droplet, firewall, reserved IP, its assignment and
> the volume, and only then `destroy`.

One-command, single-droplet deployment of the Tenshu sync relay
(`:sync-relay-server`). Provisions a droplet + block-storage volume + firewall +
reserved IP (+ optional DNS), and boots the relay behind Caddy (automatic
HTTPS) via cloud-init. SQLite + filesystem blobs on the volume - the single-node
tier from [`docs/proposals/sync-relay-persistence.md`](../../docs/proposals/sync-relay-persistence.md).

```bash
cp terraform.tfvars.example terraform.tfvars   # fill in token, ssh key, domain, email
terraform init
terraform apply
```

First boot clones the repo, builds the fat jar (a few minutes), and gets a TLS
cert. Then point clients at the `relay_url` output.

Full walkthrough, the prebuilt-image alternative, and the DDoS/abuse notes:
[`docs/how-to/deploy-relay-digitalocean.md`](../../docs/how-to/deploy-relay-digitalocean.md).

| File | Purpose |
|------|---------|
| `versions.tf` | Terraform + DO provider version pins. |
| `variables.tf` | Inputs (token, sizing, domain, DNS toggle, app config). |
| `main.tf` | Droplet, volume, firewall, reserved IP, DNS. |
| `cloud-init.yaml.tftpl` | First-boot bootstrap (Docker, volume mount, clone, compose up). |
| `outputs.tf` | Public IP + relay URL. |

The relay run definition (compose + Caddy) lives next to the module it serves:
[`sync-relay-server/docker-compose.yml`](../../sync-relay-server/docker-compose.yml).
House Rule #1 is intact throughout - the relay stores ciphertext only.
