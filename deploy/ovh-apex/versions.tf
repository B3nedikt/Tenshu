terraform {
  required_version = ">= 1.7.0"
  required_providers {
    ovh = {
      source  = "ovh/ovh"
      version = "~> 2.24"
    }
  }
}

# Credentials come from the environment (OVH_ENDPOINT, OVH_APPLICATION_KEY, OVH_APPLICATION_SECRET,
# OVH_CONSUMER_KEY); see deploy/ovh-project/README.md.
provider "ovh" {}
