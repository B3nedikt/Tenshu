#!/usr/bin/env python3
"""Fail if a bundle or APK ships a 64-bit native library that is not 16 KB page-aligned.

    python3 scripts/lib/check_16kb_alignment.py app-release.aab [more.aab ...]

Play refuses such a bundle for an app targeting Android 15 or later. Only the 64-bit ABIs are
checked: 16 KB pages do not exist on 32-bit Android.

An APK is checked for one thing more. A library stored uncompressed is mapped straight out of the
APK, so its data must also start on a 16 KB boundary inside the zip (what `zipalign -P 16` does).
Play does that itself when it builds APKs from a bundle; an APK we hand out has no Play behind it.
"""
import struct
import sys
import zipfile

PT_LOAD = 1
PAGE = 16 * 1024
ABIS = ("arm64-v8a", "x86_64")


def min_load_alignment(elf: bytes) -> int:
    phoff = struct.unpack_from("<Q", elf, 0x20)[0]
    entry_size, count = struct.unpack_from("<HH", elf, 0x36)
    return min(
        struct.unpack_from("<Q", elf, phoff + i * entry_size + 48)[0]
        for i in range(count)
        if struct.unpack_from("<I", elf, phoff + i * entry_size)[0] == PT_LOAD
    )


def data_offset(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Where an entry's bytes start in the file: after its local header, name and extra field."""
    archive.fp.seek(info.header_offset)
    header = archive.fp.read(30)
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_length + extra_length


def main(paths: list[str]) -> int:
    bad = 0
    for path in paths:
        is_apk = path.endswith(".apk")
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                name = info.filename
                if not name.endswith(".so") or not any(f"/{abi}/" in name for abi in ABIS):
                    continue
                alignment = min_load_alignment(archive.read(name))
                if alignment < PAGE:
                    print(f"✗ {path}: {name} is {alignment}-byte aligned, needs {PAGE}", file=sys.stderr)
                    bad += 1
                if is_apk and info.compress_type == zipfile.ZIP_STORED:
                    offset = data_offset(archive, info)
                    if offset % PAGE:
                        print(f"✗ {path}: {name} is stored at zip offset {offset}, not on a {PAGE}-byte "
                              "boundary (zipalign -P 16)", file=sys.stderr)
                        bad += 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
