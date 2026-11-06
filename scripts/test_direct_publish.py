#!/usr/bin/env python3
"""Tests for publishing a direct (outside-Play) release: scripts/lib/direct_publish.py and
scripts/android-direct-publish.sh.

  python3 scripts/test_direct_publish.py      (no network, no Gradle, no Android SDK)

The script tests run the real shell script in a throwaway git repo, with `aws`, `curl` and
`apksigner` replaced by fakes that keep the "bucket" in a directory, so the order of uploads and the
manifest write, the headers and every refusal are checked without touching R2.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import direct_publish  # noqa: E402
import direct_release  # noqa: E402

ROOT = os.path.dirname(HERE)
PIN = ":".join(f"{0xA0 + i:02X}" for i in range(32))
OTHER = ":".join(f"{0x10 + i:02X}" for i in range(32))
BASE = "https://releases.tenshu.app/sha256/"


def read_json(path):
    with open(path) as f:
        return json.load(f)


def write_json(obj, path):
    with open(path, "w") as f:
        json.dump(obj, f)


def make_release(directory: str, version_code: int = 7, apps=("control", "drive"), cert: str = PIN,
                 salt: str = "", abi: str = "arm64-v8a") -> dict:
    """A directory shaped like android-release.sh --direct output, through the real write_manifest."""
    os.makedirs(directory, exist_ok=True)
    pairs = {}
    for app in apps:
        name = f"{app}-{version_code}.apk"
        with zipfile.ZipFile(os.path.join(directory, name), "w") as z:
            z.writestr("AndroidManifest.xml", f"fake apk {app} {version_code} {salt}" * 50)
            z.writestr(f"lib/{abi}/libfake.so", b"\x7fELF")
        pairs[app] = name
    release = direct_release.write_manifest(directory, version_code, "1.0", cert, pairs)
    with open(os.path.join(directory, "signing-cert-sha256.txt"), "w") as f:
        f.write(cert + "\n")
    return release


class ReleaseDirTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.dir = os.path.join(self.tmp, "direct")
        make_release(self.dir)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def check(self, directory=None, pinned=PIN, signer=PIN):
        return direct_publish.check_release_dir(directory or self.dir, pinned, lambda _p: signer)

    def refused(self, pattern, **kw):
        with self.assertRaisesRegex(direct_publish.Refusal, pattern):
            self.check(**kw)

    def test_a_pinned_release_passes(self):
        self.assertEqual(7, self.check()["versionCode"])

    def test_the_unpinned_directory_is_refused_whatever_it_contains(self):
        unpinned = os.path.join(self.tmp, "direct-unpinned")
        make_release(unpinned)
        self.refused("allow-unpinned", directory=unpinned)

    def test_a_symlink_to_the_unpinned_directory_is_refused(self):
        unpinned = os.path.join(self.tmp, "direct-unpinned")
        make_release(unpinned)
        link = os.path.join(self.tmp, "looks-fine")
        os.symlink(unpinned, link)
        self.refused("allow-unpinned", directory=link)

    def test_the_staging_directory_is_refused(self):
        staging = os.path.join(self.tmp, "direct.staging")
        make_release(staging)
        self.refused("staging", directory=staging)

    def test_an_empty_pin_is_refused(self):
        self.refused("no side-channel key is pinned", pinned=None)

    def test_a_release_signed_by_another_key_is_refused(self):
        self.refused("release.json names signer .*, not the pinned side-channel key", pinned=OTHER, signer=OTHER)

    def test_the_apk_itself_must_be_signed_by_the_pin(self):
        # release.json and the txt both claim the pin; the APK says otherwise.
        self.refused(r"control: .* signed by " + OTHER, signer=OTHER)

    def test_cert_txt_and_release_json_must_agree(self):
        with open(os.path.join(self.dir, "signing-cert-sha256.txt"), "w") as f:
            f.write(OTHER)
        self.refused("signing-cert-sha256.txt")

    def test_an_apk_changed_after_the_build_is_refused(self):
        with open(os.path.join(self.dir, "drive-7.apk"), "ab") as f:
            f.write(b"tampered")
        self.refused("drive: drive-7.apk hashes to")

    def test_a_missing_apk_is_refused(self):
        os.remove(os.path.join(self.dir, "control-7.apk"))
        self.refused("control-7.apk is missing")

    def test_a_missing_sidecar_is_refused(self):
        os.remove(os.path.join(self.dir, "SHA256SUMS"))
        self.refused("SHA256SUMS is missing")

    def test_a_universal_release_is_refused(self):
        path = os.path.join(self.dir, "release.json")
        release = read_json(path)
        release["abi"] = "universal"
        write_json(release, path)
        self.refused("arm64-v8a only")

    def test_a_version_name_the_website_refuses_is_refused(self):
        path = os.path.join(self.dir, "release.json")
        release = read_json(path)
        release["versionName"] = "x" * 65
        write_json(release, path)
        self.refused("versionName")

    def test_an_apk_carrying_another_abi_is_refused_whatever_release_json_says(self):
        make_release(self.dir, abi="x86_64")
        self.refused("carries lib/x86_64/")

    def test_an_unknown_app_is_refused(self):
        make_release(self.dir, apps=("control", "evil"))
        self.refused("evil: not a first-party app")


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def release(self, version_code=7, apps=("control", "drive"), cert=PIN, salt=""):
        return make_release(os.path.join(self.tmp, f"r{version_code}{salt}{len(apps)}"), version_code,
                            apps, cert, salt)

    def test_every_field_the_download_page_needs(self):
        rel = self.release()
        m = direct_publish.merge_manifest(None, rel, BASE, "2026-11-04")
        e = m["apps"]["control"]
        sha = rel["apps"]["control"]["sha256"]
        self.assertEqual({
            "package": "app.tenshu.control.android", "versionCode": 7, "versionName": "1.0",
            "abi": "arm64-v8a", "signingCertSha256": PIN, "sha256": sha,
            "bytes": rel["apps"]["control"]["bytes"], "filename": "tenshu-control-7.apk",
            "url": f"{BASE}{sha}.apk", "published": "2026-11-04",
        }, e)
        self.assertEqual((1, BASE, "arm64-v8a", PIN), (m["schema"], m["base"], m["abi"], m["signingCertSha256"]))

    def test_apps_follow_the_catalogue_order(self):
        m = direct_publish.merge_manifest(None, self.release(apps=("mia", "drive", "control")), BASE, "2026-11-04")
        self.assertEqual(["control", "drive", "mia"], list(m["apps"]))

    def test_other_apps_keep_their_entries(self):
        old = direct_publish.merge_manifest(None, self.release(7, ("control", "drive")), BASE, "2026-11-01")
        new = direct_publish.merge_manifest(old, self.release(8, ("drive",)), BASE, "2026-11-04")
        self.assertEqual(old["apps"]["control"], new["apps"]["control"])
        self.assertEqual((8, "2026-11-04"), (new["apps"]["drive"]["versionCode"], new["apps"]["drive"]["published"]))

    def test_a_republish_of_the_same_bytes_keeps_the_date(self):
        rel = self.release()
        old = direct_publish.merge_manifest(None, rel, BASE, "2026-11-01")
        self.assertEqual(old, direct_publish.merge_manifest(old, rel, BASE, "2026-11-04"))

    def test_a_lower_version_code_is_refused(self):
        old = direct_publish.merge_manifest(None, self.release(8), BASE, "2026-11-01")
        with self.assertRaisesRegex(direct_publish.Refusal, "never go backwards"):
            direct_publish.merge_manifest(old, self.release(7), BASE, "2026-11-04")

    def test_the_same_version_code_with_other_bytes_is_refused(self):
        old = direct_publish.merge_manifest(None, self.release(7), BASE, "2026-11-01")
        with self.assertRaisesRegex(direct_publish.Refusal, "already published with other bytes"):
            direct_publish.merge_manifest(old, self.release(7, salt="rebuilt"), BASE, "2026-11-04")

    def test_a_change_of_signing_key_is_refused(self):
        old = direct_publish.merge_manifest(None, self.release(7), BASE, "2026-11-01")
        with self.assertRaisesRegex(direct_publish.Refusal, "uninstalled"):
            direct_publish.merge_manifest(old, self.release(8, cert=OTHER), BASE, "2026-11-04")

    def test_a_change_of_host_is_refused(self):
        old = direct_publish.merge_manifest(None, self.release(7), BASE, "2026-11-01")
        with self.assertRaisesRegex(direct_publish.Refusal, "move the manifest deliberately"):
            direct_publish.merge_manifest(old, self.release(8), "https://assets.tenshu.app/sha256/", "2026-11-04")

    def test_check_catches_a_hand_edited_url(self):
        m = direct_publish.merge_manifest(None, self.release(), BASE, "2026-11-04")
        m["apps"]["drive"]["url"] = "https://evil.example/drive.apk"
        with self.assertRaisesRegex(direct_publish.Refusal, "drive: url"):
            direct_publish.check_manifest(m)

    def test_check_catches_a_second_signing_key(self):
        m = direct_publish.merge_manifest(None, self.release(), BASE, "2026-11-04")
        m["apps"]["drive"]["signingCertSha256"] = OTHER
        with self.assertRaisesRegex(direct_publish.Refusal, "one key signs every app"):
            direct_publish.check_manifest(m)

    def test_check_catches_a_missing_field(self):
        m = direct_publish.merge_manifest(None, self.release(), BASE, "2026-11-04")
        del m["apps"]["control"]["abi"]
        with self.assertRaisesRegex(direct_publish.Refusal, "control: fields"):
            direct_publish.check_manifest(m)

    def test_content_disposition_names_app_and_version(self):
        self.assertEqual('attachment; filename="tenshu-photos-12.apk"', direct_publish.content_disposition("photos", 12))


class CatalogueTest(unittest.TestCase):
    def test_the_app_list_is_android_release_sh_s(self):
        with open(os.path.join(ROOT, "scripts", "android-release.sh")) as f:
            m = re.search(r'^ALL_APPS="([^"]*)"', f.read(), re.M)
        self.assertEqual(tuple(m.group(1).split()), direct_publish.APPS)

    def test_the_committed_manifest_checks_if_there_is_one(self):
        path = os.path.join(ROOT, direct_publish.MANIFEST)
        if os.path.exists(path):
            direct_publish.check_manifest(read_json(path))


FAKE_AWS = r'''#!/usr/bin/env python3
# Fake `aws s3api` over a directory: $FAKE_R2/<key> holds the bytes, <key>.meta the headers.
import json, os, shutil, sys
store = os.environ["FAKE_R2"]
args = sys.argv[1:]
def opt(name):
    return args[args.index(name) + 1] if name in args else None
cmd = args[args.index("s3api") + 1]
with open(os.path.join(store, "calls.log"), "a") as log:
    log.write(json.dumps({"cmd": cmd, "key": opt("--key"),
                          "manifest_exists": os.path.exists("store/android-release.json"),
                          "creds": os.environ.get("AWS_ACCESS_KEY_ID")}) + "\n")
if cmd == "get-object":
    if not os.path.exists(path := os.path.join(store, "objects", opt("--key"))):
        sys.exit(255)
    shutil.copyfile(path, args[-1])
    print("{}")
    sys.exit(0)
if cmd == "head-bucket":
    sys.exit(1 if os.environ.get("FAKE_R2_NO_BUCKET") else 0)
path = os.path.join(store, "objects", opt("--key") or "")
if cmd == "head-object":
    if not os.path.exists(path):
        sys.exit(255)
    meta = json.load(open(path + ".meta"))
    print("\t".join([str(os.path.getsize(path)), meta["type"], meta["cache"], meta["disposition"] or "None"]))
elif cmd == "put-object":
    if os.environ.get("FAKE_R2_FAIL_PUT") == opt("--key"):
        sys.exit(1)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    shutil.copyfile(opt("--body"), path)
    json.dump({"type": opt("--content-type"), "cache": opt("--cache-control"),
               "disposition": opt("--content-disposition")}, open(path + ".meta", "w"))
    print("{}")
else:
    sys.exit(f"fake aws: unexpected {cmd}")
'''

FAKE_CURL = r'''#!/usr/bin/env python3
# Fake `curl -D <headers> -o <body> -w %{http_code} <url>` serving the fake bucket.
import json, os, shutil, sys
args = sys.argv[1:]
url = args[-1]
key = url.split("/", 3)[3]
path = os.path.join(os.environ["FAKE_R2"], "objects", key)
if not os.path.exists(path):
    open(args[args.index("-D") + 1], "w").write("HTTP/2 404\r\n\r\n")
    open(args[args.index("-o") + 1], "w").close()
    print("404", end="")
    sys.exit(0)
meta = json.load(open(path + ".meta"))
open(args[args.index("-D") + 1], "w").write(
    "HTTP/2 200\r\ncontent-type: %s\r\ncache-control: %s\r\ncontent-disposition: %s\r\n\r\n"
    % (meta["type"], meta["cache"], meta["disposition"]))
shutil.copyfile(path, args[args.index("-o") + 1])
print("200", end="")
'''

FAKE_APKSIGNER = '''#!/bin/sh
# Fake `apksigner verify --print-certs`: every APK is signed by $FAKE_SIGNER (hex, no colons).
echo "Signer #1 certificate DN: CN=Tenshu"
echo "Signer #1 certificate SHA-256 digest: $FAKE_SIGNER"
'''


class ScriptTest(unittest.TestCase):
    """The real android-direct-publish.sh, in a throwaway repo, against fake aws / curl / apksigner."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "scripts", "lib"))
        for rel in ("scripts/android-direct-publish.sh", "scripts/lib/direct_publish.py",
                    "scripts/lib/direct_release.py"):
            shutil.copy(os.path.join(ROOT, rel), os.path.join(self.repo, rel))
        self.pin(PIN)
        subprocess.run(["git", "init", "-q", self.repo], check=True)
        self.r2 = os.path.join(self.tmp, "r2")
        os.makedirs(self.r2)
        bin_dir = os.path.join(self.tmp, "bin")
        tools = os.path.join(self.tmp, "sdk", "build-tools", "99.0.0")
        os.makedirs(bin_dir)
        os.makedirs(tools)
        for path, body in ((os.path.join(bin_dir, "aws"), FAKE_AWS), (os.path.join(bin_dir, "curl"), FAKE_CURL),
                           (os.path.join(tools, "apksigner"), FAKE_APKSIGNER)):
            with open(path, "w") as f:
                f.write(body)
            os.chmod(path, 0o755)
        self.env = {
            "PATH": bin_dir + os.pathsep + os.environ["PATH"], "HOME": self.tmp, "TMPDIR": self.tmp,
            "FAKE_R2": self.r2, "FAKE_SIGNER": PIN.replace(":", "").lower(),
            "ANDROID_HOME": os.path.join(self.tmp, "sdk"),
            "RELEASES_ENDPOINT": "https://0123.r2.cloudflarestorage.com",
            "RELEASES_R2_ACCESS_KEY_ID": "releases-id", "RELEASES_R2_SECRET_ACCESS_KEY": "releases-secret",
        }
        self.direct = os.path.join(self.repo, "build", "direct")
        self.release = make_release(self.direct)
        self.manifest = os.path.join(self.repo, "store", "android-release.json")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def pin(self, digest):
        path = os.path.join(self.repo, direct_release.FIRST_PARTY_SIGNING)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        value = f'"{digest}"' if digest else "null"
        with open(path, "w") as f:
            f.write(textwrap.dedent(f"""\
                object FirstPartySigning {{
                    val SIDE_CHANNEL_SIGNING_CERT_SHA256: String? = {value}
                }}
                """))

    def run_script(self, *args, **env):
        return subprocess.run(["bash", os.path.join(self.repo, "scripts", "android-direct-publish.sh"), *args],
                              cwd=self.repo, env={**self.env, **env}, capture_output=True, text=True)

    def calls(self):
        path = os.path.join(self.r2, "calls.log")
        if not os.path.exists(path):
            return []
        with open(path) as f:
            return [json.loads(line) for line in f]

    def assertRefused(self, result, pattern):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertRegex(result.stderr, pattern)
        self.assertEqual([], self.calls(), "a refusal must come before any network call")
        self.assertFalse(os.path.exists(self.manifest))

    def test_publish_uploads_every_apk_with_its_headers_then_writes_the_manifest(self):
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        puts = [c for c in self.calls() if c["cmd"] == "put-object"]
        self.assertEqual(2, len(puts))
        self.assertFalse(any(c["manifest_exists"] for c in self.calls()), "objects first, manifest last")
        self.assertTrue(all(c["creds"] == "releases-id" for c in self.calls()))
        for app in ("control", "drive"):
            sha = self.release["apps"][app]["sha256"]
            meta = read_json(os.path.join(self.r2, "objects", "sha256", f"{sha}.apk.meta"))
            self.assertEqual({"type": "application/vnd.android.package-archive",
                              "cache": "public, max-age=31536000, immutable",
                              "disposition": f'attachment; filename="tenshu-{app}-7.apk"'}, meta)
        m = read_json(self.manifest)
        direct_publish.check_manifest(m)
        self.assertEqual(f"{BASE}{self.release['apps']['drive']['sha256']}.apk", m["apps"]["drive"]["url"])

    def test_a_second_run_uploads_nothing(self):
        self.assertEqual(0, self.run_script().returncode)
        os.remove(os.path.join(self.r2, "calls.log"))
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual([], [c for c in self.calls() if c["cmd"] == "put-object"])

    def test_an_object_with_a_wrong_header_is_repaired(self):
        self.assertEqual(0, self.run_script().returncode)
        sha = self.release["apps"]["control"]["sha256"]
        meta_path = os.path.join(self.r2, "objects", "sha256", f"{sha}.apk.meta")
        meta = read_json(meta_path)
        meta["disposition"] = None
        write_json(meta, meta_path)
        r = self.run_script()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("REPAIR", r.stdout)
        self.assertEqual('attachment; filename="tenshu-control-7.apk"', read_json(meta_path)["disposition"])

    def test_a_failed_upload_leaves_the_manifest_untouched(self):
        key = f"sha256/{self.release['apps']['drive']['sha256']}.apk"
        r = self.run_script(FAKE_R2_FAIL_PUT=key)
        self.assertNotEqual(0, r.returncode)
        self.assertFalse(os.path.exists(self.manifest))

    def test_a_failed_upload_leaves_an_existing_manifest_byte_identical(self):
        self.assertEqual(0, self.run_script().returncode)
        with open(self.manifest, "rb") as f:
            before = f.read()
        make_release(self.direct, version_code=8)
        key = f"sha256/{direct_release.sha256_file(os.path.join(self.direct, 'drive-8.apk'))}.apk"
        r = self.run_script(FAKE_R2_FAIL_PUT=key)
        self.assertNotEqual(0, r.returncode)
        with open(self.manifest, "rb") as f:
            self.assertEqual(before, f.read())

    def test_a_same_size_replacement_already_in_the_bucket_is_caught_before_the_manifest(self):
        # T-DA-01: an object with the right size and headers is already there, but not our bytes.
        sha = self.release["apps"]["drive"]["sha256"]
        path = os.path.join(self.r2, "objects", "sha256", f"{sha}.apk")
        os.makedirs(os.path.dirname(path))
        with open(path, "wb") as f:
            f.write(b"x" * self.release["apps"]["drive"]["bytes"])
        write_json({"type": "application/vnd.android.package-archive",
                    "cache": "public, max-age=31536000, immutable",
                    "disposition": 'attachment; filename="tenshu-drive-7.apk"'}, path + ".meta")
        r = self.run_script()
        self.assertNotEqual(0, r.returncode)
        self.assertIn("was replaced or truncated", r.stderr)
        self.assertIn("have   sha256/" + sha, r.stdout, "the size+header shortcut is what this guards")
        self.assertFalse(os.path.exists(self.manifest))

    def test_a_missing_bucket_uploads_nothing(self):
        r = self.run_script(FAKE_R2_NO_BUCKET="1")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("does not exist", r.stderr)
        self.assertEqual([], [c for c in self.calls() if c["cmd"] == "put-object"])
        self.assertFalse(os.path.exists(self.manifest))

    def test_dry_run_touches_nothing(self):
        r = self.run_script("--dry-run")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("tenshu-control-7.apk", r.stdout)
        self.assertEqual([], self.calls())
        self.assertFalse(os.path.exists(self.manifest))

    def test_the_unpinned_directory_is_refused(self):
        unpinned = os.path.join(self.repo, "build", "direct-unpinned")
        make_release(unpinned)
        self.assertRefused(self.run_script(unpinned), "allow-unpinned")

    def test_an_empty_pin_is_refused(self):
        self.pin(None)
        self.assertRefused(self.run_script(), "no side-channel key is pinned")

    def test_a_pin_other_than_the_signer_is_refused(self):
        self.pin(OTHER)
        self.assertRefused(self.run_script(), "not the pinned side-channel key")

    def test_an_apk_signed_by_another_key_is_refused(self):
        self.assertRefused(self.run_script(FAKE_SIGNER=OTHER.replace(":", "").lower()), "signed by " + OTHER)

    def test_verify_passes_on_what_was_published(self):
        self.assertEqual(0, self.run_script().returncode)
        r = self.run_script("--verify")
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("verified 2 APKs", r.stdout)

    def test_verify_catches_a_replaced_object(self):
        # The reason the bucket is its own (D3): a write key can swap the bytes under a hash key.
        self.assertEqual(0, self.run_script().returncode)
        sha = self.release["apps"]["drive"]["sha256"]
        path = os.path.join(self.r2, "objects", "sha256", f"{sha}.apk")
        size = os.path.getsize(path)
        with open(path, "wb") as f:
            f.write(b"x" * size)  # same size: only the hash can tell
        r = self.run_script("--verify")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("FAIL drive", r.stdout)
        self.assertIn("sha256", r.stdout.split("FAIL drive")[1].splitlines()[0])


if __name__ == "__main__":
    unittest.main()
