#!/usr/bin/env python3
"""Pins `store-relation-audit.py`'s owner walk: a dangling list is attributed to the composite
that holds its row hash, in every shape `DbEntryMapper.serialize` can store that hash in.

    python3 scripts/test_store_relation_audit.py

Each case builds a store copy by hand, encoded as `DbEntryCodec.encode` writes it. ⚠ That makes
the encoder below a second copy of the codec, so this checks the walk, never the decoder: a codec
drift would move both copies together and stay green here.
"""
import contextlib
import importlib.util
import io
import os
import sqlite3
import struct
import sys
import tempfile
import unittest

# Loading the script by path would otherwise drop an untracked `scripts/__pycache__/` — nothing
# ignores one.
sys.dont_write_bytecode = True
_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("store_relation_audit", os.path.join(_HERE, "store-relation-audit.py"))
audit_script = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(audit_script)

LIST = audit_script.LIST_TYPE
FOLDER = audit_script.FOLDER_TYPE
FILE = audit_script.FILE_TYPE
REALM_SETTING = "tenshu/preference/realmSetting"  # [string preferenceId, genericData value]

LIST_HASH = 100
PRESENT = 200
MISSING = 1  # no row — the dangling member every case plants


# ---- DbEntryCodec.encode ------------------------------------------------------------------------


def _str(s):
    b = s.encode("utf-8")
    return struct.pack(">i", len(b)) + b


def string(v):
    return b"\x01" + _str(v)


def long(v):
    return b"\x02" + struct.pack(">q", v)


def generic(type_id, payload):
    return b"\x06" + _str(type_id) + payload


def entry(type_id, h, fields=(), props=()):
    out = _str(type_id) + struct.pack(">q", h) + struct.pack(">i", len(fields)) + b"".join(fields)
    out += struct.pack(">i", len(props))
    for key, value in props:
        out += _str(key) + value
    return (h, type_id, out)


def dangling_list(h=LIST_HASH):
    """A list row with one live member and one missing one, plus the live member's row."""
    return [entry(LIST, h, [long(PRESENT), long(MISSING)]), entry(FILE, PRESENT, [string("/present.jpg")])]


class OwnerAttributionTest(unittest.TestCase):
    def audit(self, rows):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "objects.db")
            conn = sqlite3.connect(path)
            conn.execute("CREATE TABLE objects (hash INTEGER PRIMARY KEY NOT NULL, type TEXT NOT NULL, data BLOB NOT NULL)")
            conn.executemany("INSERT INTO objects VALUES (?, ?, ?)", rows)
            conn.commit()
            conn.close()
            out = io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
                rc = audit_script.audit(path, verbose=False)
        self.assertEqual(rc, 1, "every case plants a dangling member")
        self.assertNotIn("!!", out.getvalue(), "a fixture row failed to decode")
        return out.getvalue()

    def owner_of(self, out, list_hash=LIST_HASH):
        lines = [line for line in out.splitlines() if line.startswith(f"list {list_hash}:")]
        self.assertEqual(len(lines), 1, out)
        return lines[0].split(" — owned by ", 1)[1]

    # ---- shapes that were already attributed ----------------------------------------------------

    def test_a_list_nobody_references_is_reported_as_unowned(self):
        # The negative the other cases lean on: without it, a walk that names *something* as the
        # owner of every list would pass them all.
        out = self.audit(dangling_list() + [entry(FOLDER, 300, [string("/Backup/A"), long(999)])])
        self.assertTrue(self.owner_of(out).startswith("(no composite references this list"), out)

    def test_a_field_slot_names_its_owner(self):
        out = self.audit(dangling_list() + [entry(FOLDER, 300, [string("/Backup/A"), long(LIST_HASH)])])
        self.assertEqual(self.owner_of(out), f"{FOLDER} '/Backup/A' slot 1")

    def test_a_property_names_its_owner(self):
        out = self.audit(
            dangling_list() + [entry(FILE, 300, [string("/Backup/a.jpg")], props=[("tenshu/file/history", long(LIST_HASH))])]
        )
        self.assertEqual(self.owner_of(out), f"{FILE} '/Backup/a.jpg' property 'tenshu/file/history'")

    # ---- a genericData slot ----------------------------------------------------------------------

    def test_a_generic_field_holding_a_list_names_its_owner(self):
        # The shape serialize writes for a genericData field whose runtime value is a TList. A
        # primitive-valued setting sits beside it: a generic with no hash in it must yield nothing.
        out = self.audit(
            dangling_list()
            + [
                entry(REALM_SETTING, 300, [string("drive/theme"), generic("tenshu/core/string", string("dark"))]),
                entry(REALM_SETTING, 301, [string("drive/pinned"), generic(LIST, long(LIST_HASH))]),
            ]
        )
        self.assertEqual(self.owner_of(out), f"{REALM_SETTING} 'drive/pinned' slot 1")

    def test_a_generic_nested_in_a_generic_names_its_owner(self):
        # serialize never nests one today, but the codec does not forbid it, so the walk must not
        # assume a depth.
        out = self.audit(
            dangling_list()
            + [entry(REALM_SETTING, 300, [string("drive/pinned"), generic("x/outer", generic("x/inner", generic(LIST, long(LIST_HASH))))])]
        )
        self.assertEqual(self.owner_of(out), f"{REALM_SETTING} 'drive/pinned' slot 1")

    def test_a_list_behind_a_composite_behind_a_generic_names_the_composite(self):
        # A composite is never inline in a slot: the generic holds its row hash, and the composite's
        # own row holds the list. The list's owner is that composite, found on its own row.
        composite = "x/pinnedSet"
        out = self.audit(
            dangling_list()
            + [
                entry(REALM_SETTING, 300, [string("drive/pinned"), generic(composite, long(301))]),
                entry(composite, 301, [long(7), generic(LIST, long(LIST_HASH))]),
            ]
        )
        self.assertEqual(self.owner_of(out), f"{composite} slot 1")

    def test_a_number_in_a_generic_slot_is_not_a_reference(self):
        # A value type writes its number into the same LongValue a list's row hash goes in, so a
        # setting whose value happens to equal the list's hash must not be named its owner. Instant
        # is here because the value types are open-ended: TInteger is not the only one.
        out = self.audit(
            dangling_list()
            + [
                entry(REALM_SETTING, 300, [string("drive/count"), generic("tenshu/core/number/integer", long(LIST_HASH))]),
                entry(REALM_SETTING, 301, [string("drive/seenAt"), generic("tenshu/time/instant/unix", long(LIST_HASH))]),
            ]
        )
        self.assertTrue(self.owner_of(out).startswith("(no composite references this list"), out)


if __name__ == "__main__":
    unittest.main()
