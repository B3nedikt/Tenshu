# fastlane, for the store listings only: `deliver` pushes store/<app>/ to App Store Connect
# (scripts/store-push.sh, docs/how-to/store-listings.md). The binary does NOT go through fastlane —
# scripts/ios-archive.sh owns that (store-presence-and-support-pages.md §2.2).
#
# Ruby ≥ 3.2 (fastlane 2.241's floor). macOS's system Ruby is 2.6: use Homebrew's (`brew install ruby`).
# Install once with `bundle install`; the lock file pins every gem. Bump: `bundle update fastlane`.
source "https://rubygems.org"

ruby ">= 3.2"

gem "fastlane", "2.241.1"
