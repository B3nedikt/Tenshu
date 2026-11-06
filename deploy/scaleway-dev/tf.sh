#!/usr/bin/env bash
# terraform, with this module's secrets read from the macOS Keychain for the one command.
#
#   ./tf.sh plan
#   ./tf.sh apply
#
# The secrets never sit in terraform.tfvars; that file keeps only what is not secret
# (project_id, dev_domain, the CA's PUBLIC key, ...). Store each once — `-w` with no value
# prompts, so nothing lands in shell history:
#
#   security add-generic-password -U -a tenshu -s scw-dev-access-key -w
#   security add-generic-password -U -a tenshu -s scw-dev-secret-key -w
#   security add-generic-password -U -a tenshu -s dev-git-token -w
#   security add-generic-password -U -a tenshu -s dev-issuer-seed -w        # head -c 32 /dev/urandom | base64
#   security add-generic-password -U -a tenshu -s dev-postgres-password -w  # head -c 24 /dev/urandom | base64
#
# ⚠ An exported TF_VAR_* wins over the Keychain, so this also runs off macOS and in CI.
# ⚠⚠ This is hygiene, not a boundary: terraform.tfstate still holds every value in plaintext
# (docs/proposals/ops-secret-custody.md D3). Keep the state where you would keep the secrets.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

secret() { # secret <terraform variable> <keychain service>
  local var="TF_VAR_$1" v
  v="${!var:-}"
  [[ -n "$v" ]] || v="$(security find-generic-password -a tenshu -s "$2" -w 2>/dev/null || true)"
  [[ -n "$v" ]] || { echo "tf.sh: no '$2' in the Keychain (account tenshu) and $var is unset" >&2; exit 1; }
  export "$var=$v"
}

secret access_key        scw-dev-access-key
secret secret_key        scw-dev-secret-key
secret git_token         dev-git-token
secret issuer_seed       dev-issuer-seed
secret postgres_password dev-postgres-password

# Optional: absent => the fake rail. Store both or neither (main.tf refuses half a pair):
#   security add-generic-password -U -a tenshu -s dev-stripe-secret-key -w      # sk_test_…
#   security add-generic-password -U -a tenshu -s dev-stripe-webhook-secret -w  # whsec_…
optional_secret() { # optional_secret <terraform variable> <keychain service>
  local var="TF_VAR_$1" v
  v="${!var:-}"
  [[ -n "$v" ]] || v="$(security find-generic-password -a tenshu -s "$2" -w 2>/dev/null || true)"
  [[ -z "$v" ]] || export "$var=$v"
}

# Optional: absent => no https://mail.<dev_domain>. Generated straight into the Keychain, unseen:
#   security add-generic-password -U -a tenshu -s dev-mailpit-token -w "$(openssl rand -hex 24)"
optional_secret mailpit_token         dev-mailpit-token
optional_secret stripe_secret_key     dev-stripe-secret-key
optional_secret stripe_webhook_secret dev-stripe-webhook-secret

exec terraform "$@"
