# Vendored SQLite amalgamation (iOS shared store, D12)

`sqlite3.c` and `sqlite3.h` in this directory are the **unmodified** SQLite amalgamation. They are
the only SQLite the iOS store links: `docs/proposals/ios-shared-store-sqlite.md` §2.1.2 (D12) says
why the store builds its own and takes no `androidx.sqlite` artifact on iOS.

| | |
|---|---|
| Version | **3.53.4** (`SQLITE_VERSION_NUMBER` 3053004) |
| Source id | `2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc` |
| Downloaded | 2026-10-04, from `https://sqlite.org/2026/sqlite-amalgamation-3530400.zip` |
| Archive SHA3-256 | `628a44cfe82c66aed1ccbbe85a562d2e33ebe64b3288981ed76285612227934e` (matches the hash sqlite.org/download.html publishes for it) |
| Archive SHA-256 | `1e71ddf93849c6a6ecf58b827c0692073d2dd7ee40196158068f7b29f422e87d` |
| `sqlite3.c` SHA-256 | `b1dd5d74ec7f29055a6684fa06fb3c2f6821c87dd38f9a458dfd2e8a1db28189` |
| `sqlite3.h` SHA-256 | `919e7f2e8ed1d8f56ac17b412b8971c76aa5d1a879752cc6058f75e7d5910e1d` |
| Licence | public domain (sqlite.org/copyright.html) |

The two file hashes are also in `SHA256SUMS`, which `scripts/build-sqlite-apple.sh` checks before
every compile: an edited or swapped file fails the build.

## Why this version

The WAL-reset bug (sqlite.org/wal.html §11) is fixed in 3.51.3, with backports 3.50.7 and 3.44.6.
The shared store's design is exactly the pattern that triggers it, so a fixed version is a ship
requirement and the driver refuses to open on anything else (`SqliteLibrary.requireFixedBuild`).
3.53.4 was the current release on the day it was vendored.

## Updating

1. Download the new `sqlite-amalgamation-<n>.zip` and check its SHA3-256 against
   sqlite.org/download.html (`openssl dgst -sha3-256`).
2. Replace `sqlite3.c` and `sqlite3.h`, regenerate `SHA256SUMS` (`shasum -a 256 sqlite3.c sqlite3.h`)
   and update the table above.
3. The version gate reads the header's `SQLITE_VERSION_NUMBER` and `SQLITE_SOURCE_ID` through the
   cinterop, so no Kotlin constant needs changing unless the new version is below the minimum.
4. Run `./gradlew :core-common:iosSimulatorArm64Test --rerun` (it carries the `iosSqliteTest`
   suites — open, WAL reset, checkpoint, corruption, migration — over this build; `iosTestCheck`
   runs the same suite with every other module's). A new SQLite version is a new binary, so the performance numbers in
   `docs/proposals/ios-store-sqlite-spike.md` and `ios-shared-store-sqlite-q0-results.md` are
   indicative until re-measured. The bench that measured them, `spike-ios-store/`, was deleted
   after it stopped compiling; its last version is in git at `066f6f363` (last changed in
   `f73684336`). Re-measuring means restoring it from there and porting it to the current store.

## How it is built

`scripts/build-sqlite-apple.sh <konan-target> <output-dir>`, run by the
`buildSqlite<Target>` tasks of `core-common/build.gradle.kts` for `iosArm64` and
`iosSimulatorArm64`. The compile options are in that script, with the reason for each.
The Kotlin declarations come from `../cinterop/tenshusqlite.def` over the same `sqlite3.h`.
