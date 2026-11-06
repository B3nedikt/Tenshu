#!/usr/bin/env python3
"""Tests for the direct (outside-Play) release path: scripts/lib/direct_release.py, the APK half of
scripts/lib/check_16kb_alignment.py, and android-release.sh's refusals before it builds anything.

  python3 scripts/test_direct_release.py      (no network, no Gradle; the script tests need keytool)
"""
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import check_16kb_alignment  # noqa: E402
import direct_release  # noqa: E402

ROOT = os.path.dirname(HERE)
DIGEST = ":".join(f"{0xA0 + i:02X}" for i in range(32))
PAGE = 16 * 1024


class DigestTest(unittest.TestCase):
    def test_apksigner_hex_becomes_the_keytool_form(self):
        self.assertEqual(DIGEST, direct_release.colon_hex(DIGEST.replace(":", "").lower()))

    def test_a_short_digest_is_refused(self):
        with self.assertRaises(ValueError):
            direct_release.colon_hex("abcd")

    def test_every_signer_is_read(self):
        out = ("Signer #1 certificate DN: CN=Tenshu\n"
               f"Signer #1 certificate SHA-256 digest: {DIGEST.replace(':', '').lower()}\n"
               "Signer #1 certificate SHA-1 digest: 00\n"
               f"Signer #2 certificate SHA-256 digest: {'11' * 32}\n")
        self.assertEqual([DIGEST, ":".join(["11"] * 32)], direct_release.signers(out))


class PinnedDigestTest(unittest.TestCase):
    def test_the_real_slot_parses(self):
        """⚠ The script reads Kotlin source: a reshaped declaration must fail here, not at release."""
        with open(os.path.join(ROOT, direct_release.FIRST_PARTY_SIGNING)) as f:
            pinned = direct_release.pinned_digest(f.read())
        self.assertTrue(pinned is None or direct_release.DIGEST.match(pinned))

    def test_an_empty_slot_is_none(self):
        self.assertIsNone(direct_release.pinned_digest("val SIDE_CHANNEL_SIGNING_CERT_SHA256: String? = null"))

    def test_a_pinned_slot_is_its_digest_even_on_the_next_line(self):
        src = f'val SIDE_CHANNEL_SIGNING_CERT_SHA256: String? =\n        "{DIGEST}"'
        self.assertEqual(DIGEST, direct_release.pinned_digest(src))

    def test_a_malformed_pin_is_refused(self):
        with self.assertRaises(ValueError):
            direct_release.pinned_digest('val SIDE_CHANNEL_SIGNING_CERT_SHA256: String? = "AB:CD"')

    def test_a_missing_declaration_is_refused(self):
        with self.assertRaises(ValueError):
            direct_release.pinned_digest("object FirstPartySigning {}")


class EndpointTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)
        self.write(direct_release.RELAY_CONFIG,
                   'const val RELAY_URL: String = "https://relay.tenshu.app"\n'
                   'const val COMMERCE_URL: String = "https://commerce.service.tenshu.app"\n')
        self.write(direct_release.ISSUER_CONFIG, 'const val PUBLISHER_ID: String = ""\n')
        self.write(direct_release.RAIL_CONFIG,
                   "internal val ANDROID_BILLING_RAIL: BillingRail = BillingRail.STRIPE\n")

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)

    def test_a_production_build_passes(self):
        self.assertEqual([], direct_release.endpoint_problems(self.root))

    def test_a_dev_lab_relay_is_refused(self):
        self.write(direct_release.RELAY_CONFIG,
                   'const val RELAY_URL: String = "https://relay.tenshu.xyz"\n'
                   'const val COMMERCE_URL: String = "https://commerce.service.tenshu.app"\n')
        [problem] = direct_release.endpoint_problems(self.root)
        self.assertIn("relay.tenshu.xyz", problem)

    def test_a_local_commerce_is_refused(self):
        self.write(direct_release.RELAY_CONFIG,
                   'const val RELAY_URL: String = "https://relay.tenshu.app"\n'
                   'const val COMMERCE_URL: String = "http://10.0.2.2:8081"\n')
        [problem] = direct_release.endpoint_problems(self.root)
        self.assertIn("10.0.2.2", problem)

    def test_an_extra_issuer_is_refused(self):
        self.write(direct_release.ISSUER_CONFIG, 'const val PUBLISHER_ID: String = "tenshu.commerce.dev"\n')
        [problem] = direct_release.endpoint_problems(self.root)
        self.assertIn("tenshu.commerce.dev", problem)

    def test_the_play_rail_is_refused(self):
        """commerce-store-link-out.md D3: an APK for outside Play must sell through Stripe."""
        self.write(direct_release.RAIL_CONFIG,
                   "internal val ANDROID_BILLING_RAIL: BillingRail = BillingRail.GOOGLE_PLAY\n")
        [problem] = direct_release.endpoint_problems(self.root)
        self.assertIn("GOOGLE_PLAY", problem)

    def test_a_missing_file_is_refused_not_skipped(self):
        os.remove(os.path.join(self.root, direct_release.ISSUER_CONFIG))
        [problem] = direct_release.endpoint_problems(self.root)
        self.assertIn("missing", problem)

    def test_the_paths_are_where_the_generators_write(self):
        """⚠ A moved generator would leave the check reading nothing: pin each path to its build script."""
        for rel, script, marker in [
            (direct_release.RELAY_CONFIG, "control-shared-proxy/build.gradle.kts", "generated/relayConfig/proxy"),
            (direct_release.ISSUER_CONFIG, "identity-service/build.gradle.kts", "generated/buildConfiguredIssuer"),
            (direct_release.RAIL_CONFIG, "control-shared/build.gradle.kts", "generated/billingRailConfig"),
        ]:
            with open(os.path.join(ROOT, script)) as f:
                text = f.read()
            self.assertIn(marker, text, script)
            self.assertIn(marker, rel)
            self.assertIn(os.path.basename(rel), text, script)


class BadgingTest(unittest.TestCase):
    LINE = "package: name='app.tenshu.drive.android' versionCode='6' versionName='1.0' platformBuildVersionName='17'"

    def test_the_right_package_and_code_pass(self):
        self.assertEqual([], direct_release.badging_problems(self.LINE, "app.tenshu.drive.android", "6"))

    def test_a_stale_version_code_fails(self):
        [problem] = direct_release.badging_problems(self.LINE, "app.tenshu.drive.android", "7")
        self.assertIn("stale", problem)

    def test_another_package_fails(self):
        self.assertTrue(direct_release.badging_problems(self.LINE, "app.tenshu.maps.android", "6"))


class AbiTest(unittest.TestCase):
    def test_arm64_only_passes(self):
        self.assertEqual([], direct_release.abi_problems(
            ["AndroidManifest.xml", "lib/arm64-v8a/libsqlite.so", "lib/arm64-v8a/libsodium.so"]))

    def test_every_other_abi_is_named(self):
        problems = direct_release.abi_problems(
            ["lib/arm64-v8a/a.so", "lib/x86_64/a.so", "lib/armeabi-v7a/a.so"])
        self.assertEqual(2, len(problems))
        self.assertTrue(any("x86_64" in p for p in problems) and any("armeabi-v7a" in p for p in problems))

    def test_no_native_libraries_is_refused_not_passed(self):
        [problem] = direct_release.abi_problems(["AndroidManifest.xml", "classes.dex", "lib/README"])
        self.assertIn("no native libraries", problem)

    def test_the_gradle_filter_names_the_same_abi(self):
        with open(os.path.join(ROOT, "build.gradle.kts")) as f:
            self.assertIn(f'val DIRECT_RELEASE_ABI = "{direct_release.ABI}"', f.read())


class ManifestTest(unittest.TestCase):
    def test_checksums_and_manifest_describe_the_files(self):
        out = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, out)
        with open(os.path.join(out, "drive-6.apk"), "wb") as f:
            f.write(b"drive")
        release = direct_release.write_manifest(out, 6, "1.0", DIGEST, {"drive": "drive-6.apk"})
        actual = direct_release.sha256_file(os.path.join(out, "drive-6.apk"))
        with open(os.path.join(out, "SHA256SUMS")) as f:
            self.assertEqual(f"{actual}  drive-6.apk\n", f.read())
        with open(os.path.join(out, "release.json")) as f:
            self.assertEqual(release, json.load(f))
        self.assertEqual(DIGEST, release["signingCertSha256"])
        self.assertEqual("arm64-v8a", release["abi"])
        self.assertEqual({"package": "app.tenshu.drive.android", "file": "drive-6.apk", "sha256": actual, "bytes": 5},
                         release["apps"]["drive"])
        self.assertEqual(64, len(actual))
        if shutil.which("shasum"):
            subprocess.run(["shasum", "-a", "256", "-c", "SHA256SUMS"], cwd=out, check=True, capture_output=True)

    def test_a_malformed_certificate_is_refused(self):
        with self.assertRaises(ValueError):
            direct_release.write_manifest(tempfile.gettempdir(), 6, "1.0", "debug", {})


def elf(alignment: int) -> bytes:
    """A 64-bit ELF header and one PT_LOAD program header, which is all the check reads."""
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    struct.pack_into("<Q", header, 0x20, 64)  # e_phoff
    struct.pack_into("<HH", header, 0x36, 56, 1)  # e_phentsize, e_phnum
    program = bytearray(56)
    struct.pack_into("<I", program, 0, 1)  # PT_LOAD
    struct.pack_into("<Q", program, 48, alignment)
    return bytes(header + program)


def apk(path: str, name: str, data: bytes, pad_to: int | None, stored=True):
    """A zip with one entry whose data starts at a multiple of `pad_to` (or right after its header)."""
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    if pad_to:
        # Android's alignment extra field (0xD935), sized so the data lands on the boundary.
        size = pad_to - 30 - len(name)
        info.extra = struct.pack("<HH", 0xD935, size - 4) + bytes(size - 4)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(info, data)


class ApkAlignmentTest(unittest.TestCase):
    NAME = "lib/arm64-v8a/libx.so"

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)

    def check(self, *args, **kwargs) -> int:
        path = os.path.join(self.dir, "app.apk")
        apk(path, *args, **kwargs)
        with open(os.devnull, "w") as quiet:
            stderr, sys.stderr = sys.stderr, quiet
            try:
                return check_16kb_alignment.main([path])
            finally:
                sys.stderr = stderr

    def test_a_stored_library_on_a_16k_boundary_passes(self):
        self.assertEqual(0, self.check(self.NAME, elf(PAGE), PAGE))

    def test_a_stored_library_off_the_boundary_fails(self):
        """⚠ The case Play hides for a bundle and nothing hides for an APK we hand out."""
        self.assertEqual(1, self.check(self.NAME, elf(PAGE), None))

    def test_a_compressed_library_needs_no_zip_alignment(self):
        self.assertEqual(0, self.check(self.NAME, elf(PAGE), None, stored=False))

    def test_a_4k_aligned_library_still_fails(self):
        self.assertEqual(1, self.check(self.NAME, elf(4096), PAGE))

    def test_a_32_bit_library_is_not_checked(self):
        self.assertEqual(0, self.check("lib/armeabi-v7a/libx.so", elf(4096), None))


@unittest.skipUnless(shutil.which("keytool"), "needs keytool")
class ReleaseScriptRefusalTest(unittest.TestCase):
    """android-release.sh refuses these before Gradle runs, so the tests take seconds."""

    @classmethod
    def setUpClass(cls):
        # A throwaway key for the refusal paths only: it never signs anything.
        cls.dir = tempfile.mkdtemp()
        cls.store = os.path.join(cls.dir, "throwaway.p12")
        subprocess.run(["keytool", "-genkeypair", "-keystore", cls.store, "-storetype", "PKCS12",
                        "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1",
                        "-dname", "CN=throwaway", "-storepass", "throwaway"], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir)

    def run_script(self, *args, root=ROOT):
        env = dict(os.environ, TENSHU_RELEASE_STORE_FILE=self.store, TENSHU_RELEASE_STORE_PASSWORD="throwaway",
                   TENSHU_RELEASE_KEY_ALIAS="t", TENSHU_RELEASE_KEY_PASSWORD="throwaway")
        return subprocess.run([os.path.join(root, "scripts", "android-release.sh"), *args], env=env,
                              capture_output=True, text=True, timeout=60)

    def tree_with_slot(self, value: str) -> str:
        """A copy of the script, its helpers and FirstPartySigning.kt with the slot set to `value`.

        The script reads the pin from the tree it lives in, so the empty-slot refusal needs a tree
        whose slot is empty whatever the real one holds."""
        root = tempfile.mkdtemp(dir=self.dir)
        shutil.copytree(os.path.join(ROOT, "scripts"), os.path.join(root, "scripts"),
                        ignore=shutil.ignore_patterns("__pycache__"))
        with open(os.path.join(ROOT, direct_release.FIRST_PARTY_SIGNING)) as f:
            source = f.read()
        source, n = re.subn(r'(val SIDE_CHANNEL_SIGNING_CERT_SHA256: String\? =)\s*(null|"[^"]*")',
                            lambda m: f"{m.group(1)} {value}", source)
        self.assertEqual(1, n, "FirstPartySigning.kt no longer declares the slot")
        target = os.path.join(root, direct_release.FIRST_PARTY_SIGNING)
        os.makedirs(os.path.dirname(target))
        with open(target, "w") as f:
            f.write(source)
        return root

    def test_an_unpinned_key_is_refused_before_the_build(self):
        out = self.run_script("--direct", "--version-code", "1", "drive", root=self.tree_with_slot("null"))
        self.assertNotEqual(0, out.returncode)
        self.assertIn("no side-channel key is pinned", out.stderr)
        self.assertNotIn("gradlew", out.stdout + out.stderr)

    def test_a_key_other_than_the_pin_is_refused_before_the_build(self):
        other = '"' + ":".join(["AB"] * 32) + '"'
        out = self.run_script("--direct", "--version-code", "1", "drive", root=self.tree_with_slot(other))
        self.assertNotEqual(0, out.returncode)
        self.assertIn("is not the pinned side-channel key", out.stderr)
        self.assertNotIn("gradlew", out.stdout + out.stderr)

    def test_the_real_pin_refuses_the_throwaway_key(self):
        """The repository's own pin: a throwaway key must never pass as the side-channel key."""
        out = self.run_script("--direct", "--version-code", "1", "drive")
        self.assertNotEqual(0, out.returncode)
        self.assertIn("is not the pinned side-channel key", out.stderr)

    def test_upload_is_refused_for_a_direct_build(self):
        out = self.run_script("--direct", "--upload", "internal", "--version-code", "1", "drive")
        self.assertNotEqual(0, out.returncode)
        self.assertIn("--upload is for Play", out.stderr)

    def test_allow_unpinned_is_refused_for_a_play_build(self):
        out = self.run_script("--allow-unpinned", "--version-code", "1", "drive")
        self.assertNotEqual(0, out.returncode)
        self.assertIn("only applies to --direct", out.stderr)


if __name__ == "__main__":
    unittest.main()
