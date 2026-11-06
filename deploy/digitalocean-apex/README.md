# DigitalOcean apex deploy (Terraform)

One-command, single-droplet deployment of the apex web frontend (`web/apex` —
TypeScript + React, served as a static SPA). Provisions a droplet + firewall +
reserved IP (+ optional DNS), and boots the site behind Caddy (automatic HTTPS)
via cloud-init. Stateless — no data volume; the only persisted state is Caddy's
ACME cert store.

```bash
cp terraform.tfvars.example terraform.tfvars   # fill in token, ssh key, domain, email
terraform init
terraform apply
```

First boot clones the repo and either builds the image (a few minutes — the
Kotlin→JS SDK bundle + Vite build) or pulls a prebuilt one, then gets a TLS cert.

Full walkthrough, the prebuilt-image path, and the shared-DNS-zone note:
[`docs/how-to/deploy-apex-digitalocean.md`](../../docs/how-to/deploy-apex-digitalocean.md).

| File | Purpose |
|------|---------|
| `versions.tf` | Terraform + DO provider version pins. |
| `variables.tf` | Inputs (token, sizing, domain, DNS toggle, image/relay config). |
| `main.tf` | Droplet, firewall, reserved IP, DNS. |
| `cloud-init.yaml.tftpl` | First-boot bootstrap (Docker, clone, compose up). |
| `outputs.tf` | Public IP + apex URL. |

The apex run definition (compose + Caddy) lives next to the app it serves:
[`web/apex/docker-compose.yml`](../../web/apex/docker-compose.yml). This module is
a deliberate sibling of [`deploy/digitalocean/`](../digitalocean/) (the relay):
separate hosts, separate DNS records, separate Terraform state. The site serves
only the public open-source build; the encrypted sync traffic goes to the relay's
own origin (baked into the SPA at build time), so House Rule #1 never enters the
picture here.
