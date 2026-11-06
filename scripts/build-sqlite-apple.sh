#!/usr/bin/env bash
#
# Compile the vendored SQLite amalgamation into a static library for one Apple (Kotlin/Native)
# target, with Xcode's clang.
#
# This is the SQLite the iOS shared store links (docs/proposals/ios-shared-store-sqlite.md §2.1.2,
# D12): the store builds its own, pinned at a version with the WAL-reset fix, and takes no
# `androidx.sqlite` artifact on iOS. The source is checked in at
# core-common/src/nativeInterop/sqlite/ (version, download URL and hashes in VENDORED.md there).
# Invoked per target by the `buildSqlite<Target>` tasks of core-common/build.gradle.kts.
#
# Usage: build-sqlite-apple.sh <konan-target> <output-dir>
#   <konan-target>  iosArm64 | iosSimulatorArm64
#   <output-dir>    produces <output-dir>/lib/libtenshusqlite3.a
#
# ⚠ The library is deliberately NOT named libsqlite3.a: `-lsqlite3` must keep meaning the system
# library, so nothing links this build by accident or the system one in its place.

set -euo pipefail

# Keep in step with scripts/build-libsodium-apple.sh and the apps' deployment target.
readonly IOS_MIN_VERSION="14.0"

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <konan-target> <output-dir>" >&2
    exit 2
fi

readonly KONAN_TARGET="$1"
readonly OUTPUT_DIR="$2"
readonly SRC_DIR="$(cd "$(dirname "$0")/../core-common/src/nativeInterop/sqlite" && pwd)"

case "${KONAN_TARGET}" in
    iosSimulatorArm64)
        sdk="iphonesimulator"
        clang_target="arm64-apple-ios${IOS_MIN_VERSION}-simulator"
        ;;
    iosArm64)
        sdk="iphoneos"
        clang_target="arm64-apple-ios${IOS_MIN_VERSION}"
        ;;
    *)
        # No iosX64: the store's source set (`iosSqliteMain`) covers the two targets that run.
        echo "unsupported konan target: ${KONAN_TARGET} (supported: iosSimulatorArm64 | iosArm64)" >&2
        exit 2
        ;;
esac

# The checked-in source must be the recorded one. A mismatch is an edited or swapped file.
(cd "${SRC_DIR}" && shasum -a 256 -c SHA256SUMS >/dev/null) || {
    echo "vendored SQLite does not match ${SRC_DIR}/SHA256SUMS" >&2
    exit 1
}

sdk_path="$(xcrun --sdk "${sdk}" --show-sdk-path)"
clang_bin="$(xcrun -f clang)"
libtool_bin="$(xcrun -f libtool)"

# Compile options (§2.1.2). The first block is matched to the binary the spike measured
# (androidx.sqlite:sqlite-bundled 2.7.1, read from its compile-option strings); every one the store
# depends on is also set per connection, so a difference would be caught there.
#
# Left out because the store uses none of it: FTS3/4/5, R-tree, STAT4, the math functions,
# NORMALIZE, COLUMN_METADATA. Extension loading is omitted outright.
#
# ⚠ Left IN, unlike androidx: the progress callback (no SQLITE_OMIT_PROGRESS_CALLBACK), a second
# route to the hold watchdog beside sqlite3_interrupt.
#
# ⚠ Not set, unlike androidx: SQLITE_SECURE_DELETE. The design's list does not carry it; a
# connection that wants the androidx behaviour sets `PRAGMA secure_delete=ON`.
#
# SQLITE_ENABLE_LOCKING_STYLE is left at its Apple default (1), as androidx leaves it.
#
# ⚠⚠ SQLITE_DEFAULT_AUTOVACUUM=2 (incremental) is NOT in androidx's list and is load-bearing: the
# shared store never issues `PRAGMA auto_vacuum`. That pragma must precede the first table, so it
# could only run before the write lock is held, and on a store that already exists it is a write
# transaction: an opener that lost a create race appended a frame to its sibling's store, a newer
# build's included. With the default compiled in, whichever connection first writes page 1 makes
# an incremental-vacuum database. The store reads `PRAGMA auto_vacuum` back as 2 on every open.
# docs/proposals/ios-shared-store-sqlite.md Appendix B.3 (8).
readonly SQLITE_DEFINES=(
    -DSQLITE_THREADSAFE=2
    -DSQLITE_DEFAULT_PAGE_SIZE=4096
    -DSQLITE_DEFAULT_AUTOVACUUM=2
    -DSQLITE_DEFAULT_SYNCHRONOUS=2
    -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1
    -DSQLITE_DEFAULT_MMAP_SIZE=0
    -DSQLITE_TEMP_STORE=3
    -DSQLITE_SYSTEM_MALLOC
    -DSQLITE_DEFAULT_MEMSTATUS=0
    -DSQLITE_OMIT_SHARED_CACHE
    -DSQLITE_OMIT_DEPRECATED
    -DSQLITE_OMIT_LOAD_EXTENSION
    -DHAVE_ISNAN=1
    -DHAVE_USLEEP=1
)

obj_dir="${OUTPUT_DIR}/obj"
lib_dir="${OUTPUT_DIR}/lib"
rm -rf "${obj_dir}" "${lib_dir}"
mkdir -p "${obj_dir}" "${lib_dir}"

"${clang_bin}" -c "${SRC_DIR}/sqlite3.c" \
    -o "${obj_dir}/sqlite3.o" \
    -target "${clang_target}" \
    -isysroot "${sdk_path}" \
    -O2 -fPIC -fno-common -DNDEBUG \
    "${SQLITE_DEFINES[@]}"

"${libtool_bin}" -static -o "${lib_dir}/libtenshusqlite3.a" "${obj_dir}/sqlite3.o" 2>/dev/null

echo "built ${lib_dir}/libtenshusqlite3.a for ${KONAN_TARGET} ($(wc -c < "${lib_dir}/libtenshusqlite3.a" | tr -d ' ') bytes)"
