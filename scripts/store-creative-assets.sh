#!/usr/bin/env bash
#
# The App Store "Header and Search Results" creative asset — one 16:9 universal PNG (5244 × 2950) per app,
# from its published iPhone screenshots, its icon and scripts/store-creative-assets.html (docs/how-to/store-listings.md §6a).
#
#   scripts/store-creative-assets.sh [control] [drive] [calendar] [contacts] [photos] [fit]   (all when none named)
#
# Writes build/store/creative/<app>/header-16x9.png (gitignored) plus three preview-*.png crop previews, and
# fails unless the asset is 5244 × 2950, PNG, RGB without alpha and sRGB-tagged. Needs Google Chrome (or
# CHROME=/path/to/chromium) and Pillow. Uploading is manual: fastlane deliver 2.241 has no field for it.
set -euo pipefail
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
exec python3 -B "$ROOT/scripts/store-creative-assets.py" "$@"
