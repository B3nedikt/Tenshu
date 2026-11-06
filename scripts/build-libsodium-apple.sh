#!/usr/bin/env bash
#
# Cross-compile a static libsodium for one Apple (Kotlin/Native) target.
#
# This is the build half of the iOS crypto backends (Phase 6a of
# docs/proposals/ios-proxy-architecture.md). The iOS `actual`s in
# core-common/src/iosMain/.../security/CryptoBackendsModule.ios.kt bind to
# libsodium through a cinterop def; this script produces the `libsodium.a`
# + headers that def consumes. It is invoked per-target by the Gradle
# `buildLibsodium<Target>` tasks in core-common/build.gradle.kts — you do
# not normally run it by hand.
#
# Why build from source rather than vendor a binary or pull a Maven klib:
#   - A checked-in `.a` is an opaque binary blob in git; a third-party
#     precompiled klib (e.g. the Ion Spin bindings) must match our exact
#     Kotlin compiler ABI, which is risky on a bleeding-edge Kotlin
#     (2.3.x). A cinterop klib we generate ourselves only depends on the
#     C library's headers + symbols, which are compiler-version-independent.
#   - The source + version + checksum are pinned here, so the build is
#     reproducible on any macOS host with Xcode (incl. the macos-latest
#     CI runner in .github/workflows/ios.yml).
#
# Requirements: Xcode command-line tools (xcrun, clang) + make. No
# autotools needed — the pinned release tarball ships a generated
# `configure`.
#
# Usage: build-libsodium-apple.sh <konan-target> <output-dir>
#   <konan-target>  one of: iosSimulatorArm64 | iosArm64 | iosX64
#   <output-dir>    install prefix; produces <output-dir>/lib/libsodium.a
#                   and <output-dir>/include/sodium.h (+ sodium/*.h)

set -euo pipefail

readonly LIBSODIUM_VERSION="1.0.20"
# Official libsodium-1.0.20.tar.gz from the 1.0.20-RELEASE GitHub release.
# Pinned so a tampered/swapped tarball is rejected before it is built into
# the crypto that protects user data.
readonly LIBSODIUM_SHA256="ebb65ef6ca439333c2bb41a0c1990587288da07f6c7fd07cb3a18cc18d30ce19"
readonly LIBSODIUM_URL="https://github.com/jedisct1/libsodium/releases/download/${LIBSODIUM_VERSION}-RELEASE/libsodium-${LIBSODIUM_VERSION}.tar.gz"
# Minimum deployment target — keep in step with the iOS app's deployment
# target when one is set in the Xcode project (Phase 6 app module).
readonly IOS_MIN_VERSION="14.0"

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <konan-target> <output-dir>" >&2
    exit 2
fi

readonly KONAN_TARGET="$1"
readonly OUTPUT_DIR="$2"

# Already built for this target? The lib is deterministic for a pinned
# source, so an existing artifact is a safe cache hit (Gradle treats
# OUTPUT_DIR as the task output and re-runs only when it is missing).
if [[ -f "${OUTPUT_DIR}/lib/libsodium.a" && -f "${OUTPUT_DIR}/include/sodium.h" ]]; then
    echo "libsodium already built for ${KONAN_TARGET} at ${OUTPUT_DIR}; skipping."
    exit 0
fi

# Map the Kotlin/Native target to an Apple SDK + clang -target triple.
# `configure --host` only flips libsodium into cross-compile mode (so it
# does not try to *run* its conftest binaries); the real architecture comes
# from CC's -target + -isysroot.
case "${KONAN_TARGET}" in
    iosSimulatorArm64)
        sdk="iphonesimulator"
        clang_target="arm64-apple-ios${IOS_MIN_VERSION}-simulator"
        config_host="arm-apple-darwin"
        ;;
    iosX64)
        sdk="iphonesimulator"
        clang_target="x86_64-apple-ios${IOS_MIN_VERSION}-simulator"
        config_host="x86_64-apple-darwin"
        ;;
    iosArm64)
        sdk="iphoneos"
        clang_target="arm64-apple-ios${IOS_MIN_VERSION}"
        config_host="arm-apple-darwin"
        ;;
    *)
        echo "unsupported konan target: ${KONAN_TARGET}" >&2
        echo "supported: iosSimulatorArm64 | iosX64 | iosArm64" >&2
        exit 2
        ;;
esac

sdk_path="$(xcrun --sdk "${sdk}" --show-sdk-path)"
clang_bin="$(xcrun -f clang)"

# Work + cache layout lives one level above the per-target output so the
# downloaded tarball is shared across targets.
work_root="$(dirname "${OUTPUT_DIR}")"
cache_dir="${work_root}/cache"
mkdir -p "${cache_dir}"
tarball="${cache_dir}/libsodium-${LIBSODIUM_VERSION}.tar.gz"

verify_checksum() {
    local actual
    actual="$(shasum -a 256 "${tarball}" | awk '{print $1}')"
    [[ "${actual}" == "${LIBSODIUM_SHA256}" ]]
}

if [[ ! -f "${tarball}" ]] || ! verify_checksum; then
    echo "Downloading libsodium ${LIBSODIUM_VERSION}…"
    curl -fL --retry 3 --max-time 180 -o "${tarball}" "${LIBSODIUM_URL}"
    if ! verify_checksum; then
        echo "checksum mismatch for ${tarball}" >&2
        echo "  expected ${LIBSODIUM_SHA256}" >&2
        echo "  actual   $(shasum -a 256 "${tarball}" | awk '{print $1}')" >&2
        rm -f "${tarball}"
        exit 1
    fi
fi

# configure mutates the source tree, so build each target in its own fresh
# extraction under the per-target work dir.
build_dir="${work_root}/src-${KONAN_TARGET}"
rm -rf "${build_dir}"
mkdir -p "${build_dir}"
tar xzf "${tarball}" -C "${build_dir}" --strip-components=1

echo "Building libsodium for ${KONAN_TARGET} (${clang_target})…"
(
    cd "${build_dir}"
    export CC="${clang_bin} -target ${clang_target} -isysroot ${sdk_path}"
    # --without-pthreads: iOS builds need no threads here. Argon2's output is
    # independent of the actual thread count (parallelism is an algorithm
    # parameter, not a runtime detail), so single-threaded fill produces the
    # same bytes as a multi-threaded one — interop with the JVM/JS backends
    # is preserved.
    ./configure \
        --host="${config_host}" \
        --prefix="${OUTPUT_DIR}" \
        --disable-shared \
        --enable-static \
        --without-pthreads \
        >"${build_dir}/configure.log" 2>&1
    make -j"$(sysctl -n hw.ncpu)" >"${build_dir}/make.log" 2>&1
    make install >"${build_dir}/install.log" 2>&1
)

if [[ ! -f "${OUTPUT_DIR}/lib/libsodium.a" ]]; then
    echo "build produced no libsodium.a — see logs in ${build_dir}" >&2
    exit 1
fi

echo "libsodium ready for ${KONAN_TARGET}: ${OUTPUT_DIR}/lib/libsodium.a"
