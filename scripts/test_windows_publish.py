#!/usr/bin/env python3
"""Tests for the Windows direct release's checks and manifest: scripts/lib/windows_publish.py.

  python3 scripts/test_windows_publish.py      (no network, no Gradle, no Windows)

Each test builds a fake build/windows-release with REAL bytes where it matters: a certificate minted
by openssl (DER, as Export-Certificate writes), and .msix zips whose AppxSignature.p7x is a PKCX-
prefixed PKCS#7 carrying that certificate — so the openssl parsing runs for real. Only the git HEAD
is faked.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import windows_publish as wp  # noqa: E402

BASE = "https://releases.tenshu.app/sha256/"
HEAD = "0123456789abcdef0123456789abcdef01234567"
PUBLISHER = "CN=Tenshu Beta"


def mint_cert(dir, subject="/CN=Tenshu Beta", days=1095):
    key, pem, der = (os.path.join(dir, n) for n in ("k.pem", "c.pem", "c.der"))
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", pem,
                    "-subj", subject, "-days", str(days)], check=True, capture_output=True)
    subprocess.run(["openssl", "x509", "-in", pem, "-outform", "DER", "-out", der], check=True, capture_output=True)
    p7 = subprocess.run(["openssl", "crl2pkcs7", "-nocrl", "-certfile", pem, "-outform", "DER"],
                        check=True, capture_output=True).stdout
    with open(der, "rb") as f:
        return f.read(), p7


def manifest_xml(name, publisher, version, arch="x64", min_version="10.0.17763.0"):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10">
  <Identity Name="{name}" Publisher="{publisher}" Version="{version}" ProcessorArchitecture="{arch}" />
  <Dependencies><TargetDeviceFamily Name="Windows.Desktop" MinVersion="{min_version}" MaxVersionTested="10.0.26100.0" /></Dependencies>
</Package>
"""


class Release:
    """A fake build/windows-release as windows-release.ps1 leaves it."""

    def __init__(self, root, version="1.0.1", publisher=PUBLISHER, subject="/CN=Tenshu Beta", **overrides):
        self.dir = os.path.join(root, "windows-release")
        os.makedirs(self.dir)
        self.version = version
        work = tempfile.mkdtemp(dir=root)
        cer, p7 = mint_cert(work, subject)
        self.p7x = b"PKCX" + p7
        self.cer_name = "TenshuBeta.cer"
        with open(os.path.join(self.dir, self.cer_name), "wb") as f:
            f.write(cer)
        self.thumbprint = wp.der_thumbprints(subprocess.run(
            ["openssl", "x509", "-inform", "DER", "-outform", "PEM"], input=cer, check=True, capture_output=True).stdout).pop()
        self.files = []
        for app, (pkg, identity) in wp.APPS.items():
            o = overrides.get(app, {})
            name = f"{pkg}-{version}.msix"
            with zipfile.ZipFile(os.path.join(self.dir, name), "w") as z:
                z.writestr("AppxManifest.xml", manifest_xml(o.get("name", identity), o.get("publisher", publisher),
                                                            o.get("version", version + ".0"), o.get("arch", "x64"),
                                                            o.get("min", "10.0.17763.0")))
                z.writestr(f"{pkg}.exe", f"{app} {version}")
                if not o.get("unsigned"):
                    z.writestr("AppxSignature.p7x", o.get("p7x", self.p7x))
            self.files.append(name)
        self.files.append(self.cer_name)
        self.info = {"schema": 1, "version": version, "packageVersion": version + ".0", "publisher": publisher,
                     "publisherId": wp.publisher_id(publisher), "thumbprint": self.thumbprint,
                     "certificate": self.cer_name, "commit": HEAD, "dirty": False, "built": "2026-11-17T10:00:00Z"}
        self.write()

    def write(self, sums=None):
        with open(os.path.join(self.dir, "release.json"), "w") as f:
            json.dump(self.info, f)
        lines = sums if sums is not None else [f"{wp.sha256(os.path.join(self.dir, n))}  {n}" for n in self.files]
        with open(os.path.join(self.dir, "SHA256SUMS"), "w") as f:
            f.write("\n".join(lines) + "\n")


class PlanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="windows-publish-test.")
        self.stage = os.path.join(self.tmp, "stage")
        self.published = os.path.join(self.tmp, "published.json")
        self._saved = (wp.git_head, wp.MANIFEST)
        wp.git_head = lambda: HEAD
        wp.MANIFEST = self.published  # absolute: os.path.join(ROOT, abs) is abs

    def tearDown(self):
        wp.git_head, wp.MANIFEST = self._saved
        shutil.rmtree(self.tmp)

    def release(self, **kw):
        return Release(tempfile.mkdtemp(dir=self.tmp), **kw)

    def plan(self, rel, published="2026-11-17"):
        wp.plan(rel.dir, BASE, self.stage, published)
        with open(os.path.join(self.stage, "windows-release.json")) as f:
            return json.load(f)

    def refused(self, rel, needle):
        # A stage of its own: an earlier plan in the same test (the "published" one) left its manifest behind.
        self.stage = tempfile.mkdtemp(dir=self.tmp)
        with self.assertRaises(wp.Refusal) as e:
            self.plan(rel)
        self.assertIn(needle, str(e.exception))
        self.assertFalse(os.path.exists(os.path.join(self.stage, "windows-release.json")), "a refusal must write no manifest")

    def publish(self, manifest):
        with open(self.published, "w") as f:
            json.dump(manifest, f)

    # ── the good path ──

    def test_a_clean_release_plans_a_manifest_and_every_object(self):
        rel = self.release()
        m = self.plan(rel)
        wp.check_manifest(m)
        self.assertEqual(list(m["apps"]), ["control", "drive"])
        self.assertEqual(m["version"], "1.0.1")
        self.assertEqual(m["packageVersion"], "1.0.1.0")
        self.assertEqual(m["publisherId"], "xs68hk207yka0")
        self.assertEqual(m["minimumWindows"], "10.0.17763.0")
        self.assertEqual(m["certificate"]["thumbprint"], rel.thumbprint)
        self.assertTrue(m["certificate"]["url"].endswith(".cer"))
        self.assertEqual(m["apps"]["drive"]["file"], "TenshuDrive-1.0.1.msix")
        with open(os.path.join(self.stage, "objects.tsv")) as f:
            rows = [line.rstrip("\n").split("\t") for line in f]
        self.assertEqual(len(rows), 3)
        self.assertEqual([r[5] for r in rows], [wp.MSIX_TYPE, wp.MSIX_TYPE, wp.CER_TYPE])
        for sha, path, key, name, size, _ in rows:
            self.assertEqual(wp.sha256(path), sha)
            self.assertEqual(key, f"sha256/{sha}{os.path.splitext(name)[1]}")
            self.assertEqual(int(size), os.path.getsize(path))

    def test_the_publisher_id_matches_the_gradle_and_local_test_known_answers(self):
        # xs68hk207yka0: what packageMsix printed for CN=Tenshu Beta; asmrhn1bb0j6y: desktop-windows-port.md §3.2.
        self.assertEqual(wp.publisher_id("CN=Tenshu Beta"), "xs68hk207yka0")
        self.assertEqual(wp.publisher_id("CN=Tenshu Local Test"), "asmrhn1bb0j6y")

    def test_republishing_the_same_bytes_keeps_the_published_date(self):
        rel = self.release()
        first = self.plan(rel, "2026-11-17")
        self.publish(first)
        again = self.plan(rel, "2026-11-20")
        self.assertEqual(again["published"], "2026-11-17")

    # ── refusals ──

    def test_refuses_a_dirty_build(self):
        rel = self.release()
        rel.info["dirty"] = True
        rel.write()
        self.refused(rel, "dirty tree")

    def test_refuses_another_commit(self):
        rel = self.release()
        rel.info["commit"] = "f" * 40
        rel.write()
        self.refused(rel, "publish from the commit that was built")

    def test_refuses_the_local_test_publisher(self):
        rel = self.release(publisher="CN=Tenshu Local Test", subject="/CN=Tenshu Local Test")
        self.refused(rel, "not a release publisher")

    def test_refuses_a_certificate_of_another_subject(self):
        rel = self.release(subject="/CN=Someone Else")
        self.refused(rel, "the packages' publisher is")

    def test_refuses_a_thumbprint_that_is_not_the_certificate(self):
        rel = self.release()
        rel.info["thumbprint"] = "A" * 40
        rel.write()
        self.refused(rel, "release.json says")

    def test_refuses_a_package_signed_by_another_key(self):
        work = tempfile.mkdtemp(dir=self.tmp)
        _, other = mint_cert(work)
        rel = self.release(drive={"p7x": b"PKCX" + other})
        self.refused(rel, "signed with another key")

    def test_refuses_an_unsigned_package(self):
        rel = self.release(control={"unsigned": True})
        self.refused(rel, "UNSIGNED")

    def test_refuses_a_package_of_another_identity(self):
        rel = self.release(drive={"name": "Tenshu.Drive2"})
        self.refused(rel, "the peer gate admits that name only")

    def test_refuses_a_package_of_another_publisher(self):
        rel = self.release(control={"publisher": "CN=Tenshu Store"})
        self.refused(rel, "Identity/@Publisher")

    def test_refuses_a_package_of_another_version(self):
        rel = self.release(drive={"version": "1.0.2.0"})
        self.refused(rel, "Identity/@Version")

    def test_refuses_packages_that_disagree_on_min_version(self):
        rel = self.release(drive={"min": "10.0.19041.0"})
        self.refused(rel, "MinVersion")

    def test_refuses_sums_that_miss_a_file(self):
        rel = self.release()
        rel.write(sums=[f"{wp.sha256(os.path.join(rel.dir, n))}  {n}" for n in rel.files[:-1]])
        self.refused(rel, "Missing")

    def test_refuses_bytes_that_differ_from_the_sums(self):
        rel = self.release()
        with open(os.path.join(rel.dir, rel.files[0]), "ab") as f:
            f.write(b"tampered")
        self.refused(rel, "SHA256SUMS says")

    def test_refuses_a_downgrade(self):
        self.publish(self.plan(self.release(version="1.0.2")))
        self.refused(self.release(version="1.0.1"), "lower than the published")

    def test_refuses_the_same_version_with_other_bytes(self):
        self.publish(self.plan(self.release()))
        self.refused(self.release(), "already published with other bytes")

    def test_refuses_a_publisher_switch(self):
        self.publish(self.plan(self.release()))
        self.refused(self.release(version="1.0.2", publisher="CN=Tenshu Release", subject="/CN=Tenshu Release"), "BESIDE the old one")

    def test_refuses_a_directory_that_is_not_windows_release(self):
        rel = self.release()
        other = os.path.join(self.tmp, "elsewhere")
        shutil.copytree(rel.dir, other)
        with self.assertRaises(wp.Refusal):
            wp.plan(other, BASE, self.stage, "2026-11-17")


class ManifestTest(unittest.TestCase):
    def test_check_rejects_a_hand_edited_url(self):
        tmp = tempfile.mkdtemp(prefix="windows-publish-test.")
        saved = (wp.git_head, wp.MANIFEST)
        try:
            wp.git_head = lambda: HEAD
            wp.MANIFEST = os.path.join(tmp, "none.json")
            rel = Release(tmp)
            wp.plan(rel.dir, BASE, os.path.join(tmp, "stage"), "2026-11-17")
            with open(os.path.join(tmp, "stage", "windows-release.json")) as f:
                m = json.load(f)
            m["apps"]["control"]["url"] = BASE + "0" * 64 + ".msix"
            with self.assertRaises(wp.Refusal):
                wp.check_manifest(m)
        finally:
            wp.git_head, wp.MANIFEST = saved
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main()
