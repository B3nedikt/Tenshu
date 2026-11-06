#!/usr/bin/env python3
"""Tests for scripts/public-export.py: the transforms, the gates, and --publish's refusals.

    python3 scripts/test_public_export.py

gitleaks is not exercised here (`--no-gitleaks`); the dry run over the real tree is what covers it.
"""
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("public_export", os.path.join(HERE, "public-export.py"))
pe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pe)

RULES = {
    "public_remote": r"github\.com[:/]B3nedikt/Tenshu(\.git)?$",
    "public_branch": "main",
    "internal_repo": "B3nedikt/TenshuInternal",
    "rewrite_refs": {"min": 100, "max": 99999, "skip": ["*.css"]},
    "replace": [{"pattern": "<identity id>", "with": "<identity id>"}],
    "coarsen_coords": [{"paths": ["docs/trace.md"], "pattern": r"(?<![\d.])(4[78]|1[01])\.\d{4,}", "decimals": 2}],
    "forbid": [{"pattern": "SECRET-NAME", "why": "personal"}],
    "review": [{"pattern": r"48\.\d{4,}", "why": "coords", "allow": ["landmarks/*"]}],
    "forbidden_paths": ["*terraform.tfvars", "*terraform.tfvars.*", "*.p8"],
    "allowed_paths": ["*.example"],
    "max_file_bytes": 1000,
}


def rw(text):
    return pe.rewrite_refs(text, "B3nedikt/TenshuInternal", 100, 99999)


class RewriteRefs(unittest.TestCase):
    def test_bare_reference_is_qualified(self):
        self.assertEqual(rw("fixed in B3nedikt/TenshuInternal#1089."), "fixed in B3nedikt/TenshuInternal#1089.")
        self.assertEqual(rw("(B3nedikt/TenshuInternal#2531)"), "(B3nedikt/TenshuInternal#2531)")
        self.assertEqual(rw("PR B3nedikt/TenshuInternal#816 and B3nedikt/TenshuInternal#2960/#2994"),
                         "PR B3nedikt/TenshuInternal#816 and B3nedikt/TenshuInternal#2960/#2994")

    def test_old_repo_name_links_are_rewritten(self):
        self.assertEqual(rw("https://github.com/B3nedikt/TenshuInternal/pull/14"),
                         "https://github.com/B3nedikt/TenshuInternal/pull/14")
        self.assertEqual(rw("B3nedikt/TenshuInternal#200"), "B3nedikt/TenshuInternal#200")

    def test_colours_are_left_alone(self):
        for text in ["color:#666;", "'#555'", '"#000"', "#141318", "#2A2832", "(#333 → #141414)",
                     "#111 and #fafafa"]:
            self.assertEqual(rw(text), text, text)

    def test_anchors_entities_and_qualified_refs_are_left_alone(self):
        for text in ["see file.md#123", "&#1234;", "owner/repo#1234", "B3nedikt/Tenshu#12",
                     "B3nedikt/TenshuInternal#1089", "##1234", "#1234-foo"]:
            self.assertEqual(rw(text), text, text)

    def test_below_min_is_left_alone(self):
        self.assertEqual(rw("step #12"), "step #12")

    def test_idempotent(self):
        once = rw("in B3nedikt/TenshuInternal#1089 and Drive/pull/3")
        self.assertEqual(rw(once), once)


class Transforms(unittest.TestCase):
    def test_skip_globs_disable_reference_rewriting(self):
        self.assertEqual(pe.transform_text("a/site.css", "#1234", RULES), "#1234")
        self.assertEqual(pe.transform_text("a/x.md", "#1234", RULES), "B3nedikt/TenshuInternal#1234")

    def test_replace_applies_everywhere_without_paths(self):
        self.assertEqual(pe.transform_text("x.md", "id <identity id>", RULES), "id <identity id>")

    def test_coarsen_only_in_listed_files(self):
        text = "@48.1453,11.5763 ratio 0.12345"
        self.assertEqual(pe.transform_text("docs/trace.md", text, RULES), "@48.15,11.58 ratio 0.12345")
        self.assertEqual(pe.transform_text("docs/other.md", text, RULES), text)

    def test_re2_literal_escapes_only_metacharacters(self):
        self.assertEqual(pe.re2_literal('a b.c\n"x"(y)'), 'a b\\.c\\n"x"\\(y\\)')


class Scan(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.d)

    def put(self, rel, data):
        full = os.path.join(self.d, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as f:
            f.write(data if isinstance(data, bytes) else data.encode())
        return rel

    def test_findings(self):
        paths = [
            self.put("deploy/a/terraform.tfvars", "x"),
            self.put("deploy/a/terraform.tfvars.bak-1", "x"),
            self.put("deploy/a/terraform.tfvars.example", "x"),
            self.put("AuthKey.p8", "x"),
            self.put("big.bin", b"\0" * 2000),
            self.put("doc.md", "line\nby SECRET-NAME\n"),
            self.put("t/Test.kt", "val p = 48.14539"),
            self.put("landmarks/Test.kt", "val p = 48.1374"),
        ]
        fatal, review = pe.scan(self.d, paths, RULES)
        joined = "\n".join(fatal)
        self.assertIn("deploy/a/terraform.tfvars: forbidden path", joined)
        self.assertIn("deploy/a/terraform.tfvars.bak-1: forbidden path", joined)
        self.assertNotIn("example", joined)
        self.assertIn("AuthKey.p8: forbidden path", joined)
        self.assertIn("big.bin: 2000 bytes", joined)
        self.assertIn("doc.md:2: forbidden string (personal)", joined)
        self.assertEqual(review, ["t/Test.kt:1: coords"])


def git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def make_internal(d):
    """A throwaway stand-in for this repository: two commits, no remote naming the public repo.
    Tests never use the real history (CI clones it shallow; the public copy has one commit)."""
    repo = os.path.join(d, "internal")
    os.mkdir(repo)
    git(repo, "init", "-q", "-b", "master")
    git(repo, "config", "user.email", "t@example.com")
    git(repo, "config", "user.name", "t")
    git(repo, "remote", "add", "origin", "https://github.com/B3nedikt/TenshuInternal.git")
    for i in (1, 2):
        with open(os.path.join(repo, "f.txt"), "w") as f:
            f.write(f"{i}\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-qm", f"internal change {i}")
    return repo


class WithInternal(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.saved = (pe.REPO, pe.gitleaks)
        pe.REPO = make_internal(self.d)
        pe.gitleaks = lambda *a: []  # the real scan runs in the dry run, not here

    def tearDown(self):
        pe.REPO, pe.gitleaks = self.saved
        shutil.rmtree(self.d)

    def fresh(self, name, url="https://github.com/B3nedikt/Tenshu.git"):
        p = os.path.join(self.d, name)
        os.mkdir(p)
        git(p, "init", "-q", "-b", "main")
        git(p, "config", "user.email", "t@example.com")
        git(p, "config", "user.name", "t")
        git(p, "remote", "add", "origin", url)
        return p


class PublishRefusals(WithInternal):
    """--publish must refuse every clone it could leak history from, and fail closed."""

    def refused(self, clone, needle):
        with self.assertRaises(SystemExit) as e:
            pe.check_public_clone(clone, RULES)
        self.assertIn(needle, str(e.exception))

    def test_internal_remote_is_refused(self):
        self.refused(self.fresh("a", "git@github.com:B3nedikt/TenshuInternal.git"), "not the public repository")

    def test_other_remote_is_refused(self):
        self.refused(self.fresh("b", "https://github.com/someone/else.git"), "not the public repository")

    def test_clone_sharing_history_with_the_internal_repo_is_refused(self):
        clone = self.fresh("c")
        git(clone, "fetch", "-q", pe.REPO, "master")
        git(clone, "reset", "-q", "--hard", "FETCH_HEAD")
        self.refused(clone, "shares history")

    def test_shallow_clone_is_refused(self):
        clone = os.path.join(self.d, "shallow")
        git(self.d, "clone", "-q", "--depth", "1", f"file://{pe.REPO}", clone)
        git(clone, "remote", "set-url", "origin", "https://github.com/B3nedikt/Tenshu.git")
        self.refused(clone, "shallow")

    def test_empty_public_clone_is_accepted(self):
        self.assertEqual(pe.check_public_clone(self.fresh("d"), RULES), "main")

    def test_internal_remote_still_named_public_blocks_publish(self):
        git(pe.REPO, "remote", "set-url", "origin", "https://github.com/B3nedikt/Tenshu.git")
        with self.assertRaises(SystemExit) as e:
            pe.publish(self.d, self.fresh("e"), "0" * 40, RULES, False)
        self.assertIn("remotes still name the public repository", str(e.exception))


class PublishFlow(WithInternal):
    """The initial cut, syncs, and drift, against a throwaway 'public' clone."""

    def setUp(self):
        super().setUp()
        self.clone = self.fresh("public")
        self.tree = os.path.join(self.d, "tree")
        os.makedirs(os.path.join(self.tree, "docs"))
        self.write("hello\n")
        self.first = git(pe.REPO, "rev-parse", "HEAD~1")
        self.head = git(pe.REPO, "rev-parse", "HEAD")

    def write(self, text):
        with open(os.path.join(self.tree, "docs", "a.md"), "w") as f:
            f.write(text)

    def publish(self, sha, allow_drift=False):
        with redirect_stdout(io.StringIO()):
            pe.publish(self.tree, self.clone, sha, RULES, allow_drift)

    def commit_on_main(self, name, message, merge_from=None):
        git(self.clone, "switch", "-q", "main")
        if merge_from:
            git(self.clone, "merge", "-q", "--no-ff", "-m", message, merge_from)
            return
        with open(os.path.join(self.clone, name), "w") as f:
            f.write("x\n")
        git(self.clone, "add", "-A")
        git(self.clone, "commit", "-qm", message)

    def test_initial_cut_then_sync_then_drift(self):
        self.publish(self.first)
        self.assertEqual(git(self.clone, "rev-list", "--count", "main"), "1")
        self.assertIn(f"{pe.TRAILER}: {self.first}", git(self.clone, "log", "-1", "--format=%B"))

        self.write("hello again\n")
        git(self.clone, "switch", "-q", "main")
        self.publish(self.head)
        branch = git(self.clone, "branch", "--show-current")
        self.assertTrue(branch.startswith("sync/"), branch)
        body = git(self.clone, "log", "-1", "--format=%B")
        self.assertIn(f"{pe.TRAILER}: {self.head}", body)
        self.assertIn("- internal change 2", body)

        # The sync PR lands as a merge commit: that is not drift.
        self.commit_on_main(None, "Merge sync", merge_from=branch)
        git(self.clone, "branch", "-q", "-D", branch)
        self.assertEqual(pe.last_sync(self.clone, "main")[1], self.head)

        # An outside contribution lands, one that even forges a trailer: the next sync stops.
        self.commit_on_main("CONTRIBUTED.md", f"outside contribution\n\n{pe.TRAILER}: {'f' * 40}")
        self.assertEqual(pe.last_sync(self.clone, "main")[1], self.head)
        with self.assertRaises(SystemExit) as e:
            self.publish(self.head)
        self.assertIn("outside contribution", str(e.exception))

    def test_ignored_paths_are_still_committed(self):
        with open(os.path.join(self.tree, ".gitignore"), "w") as f:
            f.write("*.md\n")
        self.publish(self.first)
        self.assertIn("docs/a.md", git(self.clone, "ls-files"))

    def test_forbidden_author_identity_blocks_the_commit(self):
        git(self.clone, "config", "user.name", "SECRET-NAME")
        with self.assertRaises(SystemExit) as e:
            self.publish(self.first)
        self.assertIn("GIT_AUTHOR_IDENT", str(e.exception))


class BuildGuards(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.d)

    def test_forbidden_string_in_binary_and_utf16_content(self):
        for name, data in [("a.bin", b"\0\0meta SECRET-NAME\0"), ("b.txt", "SECRET-NAME".encode("utf-16-le"))]:
            with open(os.path.join(self.d, name), "wb") as f:
                f.write(data)
        fatal, _ = pe.scan(self.d, ["a.bin", "b.txt"], RULES)
        self.assertEqual(len(fatal), 2, fatal)

    def test_commit_message_runs_every_text_gate(self):
        saved = pe.gitleaks
        pe.gitleaks = lambda *a: []
        try:
            found = pe.message_findings("x SECRET-NAME\nat 48.14539", RULES, self.d)
        finally:
            pe.gitleaks = saved
        self.assertTrue(any("forbidden" in x for x in found), found)
        self.assertTrue(any("coords" in x for x in found), found)


if __name__ == "__main__":
    unittest.main(verbosity=1)
