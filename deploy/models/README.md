# Model catalog pipeline

The **manifest** and the local **build** directory for the signed model catalog
(`inference-service.md` IN-D10). The runbook is
**[`docs/how-to/models-pipeline.md`](../../docs/how-to/models-pipeline.md)**; the bucket it publishes to
is **[`deploy/cloudflare-models/`](../cloudflare-models/)** (R2, `models.service.tenshu.app`).

| | |
|---|---|
| `models.json` | tracked — identity and policy per checkpoint, written by hand |
| `build/artifacts/<path>` | git-ignored — the checkpoint files, laid out exactly as served |
| `build/catalog.json` + `.sig` | git-ignored — derived by `:model-signer`, never hand-edited |

**The Core ML entries** are made by an export script each and published by the maintainer with a
block of their own: SigLIP (`coreml`) from `scripts/inference/export-coreml-towers.py`, runbook
§6.1; AuraFace (`coreml+vision`) from `scripts/inference/export-coreml-face-embedder.py`, runbook
§6.2. Each such entry pins its archives' `sha256` and `byteSize`, and copies `source` from the
export's `export-manifest.json`.

⚠ This directory held a Scaleway Terraform module until 2026-09-17. It was never applied and nothing
was published through it; the hosting moved to R2 so the URL compiled into Control names our own
domain rather than a provider's region (`deploy/cloudflare-models/README.md`).
