#!/usr/bin/env python3
"""Audit a copied `objects.db` for relation rows that reference missing member rows. READ ONLY.

The proxy's object store keys every row by a 64-bit FNV-1a hash of its `DataId`, and a relation
(`tenshu/core/list`) row is a list of those hashes. A member hash with no row behind it is the
"dangling member" a `WARN DatabaseImpl: relation … skipped N member(s)` line reports — but that
line fires only when a relation is *read*, so a log sweep undercounts. This walks the whole store
copy instead, and says which relations dangle, how many members each is missing, and which
composite owns each relation (for `Folder.FILES`, the folder's path), so one isolated case and a
cluster under the Synced-Folders backup targets can be told apart.

    scripts/store-relation-audit.py <copy-of-objects.db>

⚠ Run it on a COPY, with the `-wal` file copied beside it (`objects.db-wal` → the copy's name
+ `-wal`); the live store is written by Control through a write-behind mailbox, and SQLite in WAL
mode holds recent commits in the `-wal` until a checkpoint. A copy without it reads a stale
prefix and can report a row as missing that is on the device.

⚠ **Not for an iOS shared SQLite store (`replica/objects.db`) as it is.** There a list of more than
8,000 members is a chain: its row holds the first segment and a `\0next` property, and the rest are
`tenshu/store/list-segment` rows (`ios-shared-store-sqlite.md` Appendix B.19). This audit reads only
each list row's own members, so it would undercount such a list and report its segments as
composites of an unknown type. Android, desktop and the iOS file stores keep every list as one row.

The hash helpers answer the identity questions the audit raises:

    scripts/store-relation-audit.py --file-hash "/Backup/Phone/Camera/IMG_0001.jpg"
    scripts/store-relation-audit.py --files-array-hash "/Backup/Phone/Camera"
    scripts/store-relation-audit.py --match <hash> --paths-from <other-objects.db>
    scripts/store-relation-audit.py --match <hash> --paths-file names.txt --under "/Backup/Phone/Camera"

`--match` names a member hash by hashing candidate paths — every `tenshu/file` row's path in
another device's store copy (`--paths-from`), or one path per line from a file (`--paths-file`,
optionally prefixed with `--under`). For a WhatsApp-named member, `scripts/DataIdHashSearch.java`
enumerates the naming pattern directly and needs no candidate list at all.

`--self-test` pins the derivation against four hashes taken from the 2026-09-13 desktop store
(`docs/proposals/drive-folder-files-dangling-member.md`); `DataIdHashVectorsTest` in `:core` pins
the same four from the Kotlin side, so this file and the platform cannot drift apart silently.
`scripts/test_store_relation_audit.py` pins the owner walk, one hand-built store per shape a
composite can hold a list's row hash in (field slot, property, `genericData` slot).
"""
import argparse
import collections
import os
import sqlite3
import struct
import sys

MASK = (1 << 64) - 1
FNV_OFFSET_BASIS = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3

LIST_TYPE = "tenshu/core/list"
FILE_TYPE = "tenshu/file"
FOLDER_TYPE = "tenshu/file/folder"
FOLDER_FILES_FIELD = "tenshu/file/folder/files"

# ---- DataId hashing (core/src/commonMain/kotlin/org/tenshu/core/entity/DataId.kt) -------------


def _feed(h, s):
    for b in s.encode("utf-8"):
        h = ((h ^ b) * FNV_PRIME) & MASK
    return h


def _signed(h):
    return h - (1 << 64) if h >= (1 << 63) else h


def file_hash(path):
    """`StringDataId(type = "tenshu/file", keyValue = path)` — a File OR Folder row's key.

    Folders key under `tenshu/file` too (`Folder`'s parent schema is `File`, so it inherits
    `FileDataIdProvider`); hashing a folder path under `tenshu/file/folder` finds nothing.
    """
    h = _feed(FNV_OFFSET_BASIS, FILE_TYPE)
    h = _feed(h, "_")
    h = _feed(h, path)
    return _signed(h)


def files_array_hash(folder_path):
    """`EntityFieldDataId(type = TList, parentEntity = StringDataId(tenshu/file, folder), files)`.

    The row a folder's `Folder.FILES` list lives at — the same row whether it was written inline
    by `saveData(Folder(files = […]))` or maintained by `addToArray`/`removeFromArray`.
    """
    h = _feed(FNV_OFFSET_BASIS, LIST_TYPE)
    h = _feed(h, "_")
    h = _feed(h, FILE_TYPE)
    h = _feed(h, "_")
    h = _feed(h, folder_path)
    h = _feed(h, "_")
    h = _feed(h, FOLDER_FILES_FIELD)
    return _signed(h)


# Hashes read off the desktop Control store on 2026-09-13, and the names they resolved to: the
# folder row, its list row, and the list's two dangling members (slots 708 and 710).
VECTORS = [
    ("file", "/Backup/OnePlus CPH2581/WhatsApp Images", 6939062656711727838),
    ("files-array", "/Backup/OnePlus CPH2581/WhatsApp Images", -4299254704025414121),
    ("file", "/Backup/OnePlus CPH2581/WhatsApp Images/IMG-20240410-WA0004.jpg", 2329932176197719662),
    ("file", "/Backup/OnePlus CPH2581/WhatsApp Images/IMG-20240830-WA0004.jpg", -5762757953325457904),
]


def self_test():
    ok = True
    for kind, path, expected in VECTORS:
        got = file_hash(path) if kind == "file" else files_array_hash(path)
        status = "ok " if got == expected else "BAD"
        if got != expected:
            ok = False
        print(f"{status} {kind:11} {path!r} -> {got} (expected {expected})")
    return ok


# ---- DbEntryCodec (core-common/.../db/DbEntryCodec.kt) — big-endian, length-prefixed ----------

TAG_STRING, TAG_LONG, TAG_BYTES, TAG_BOOL, TAG_NULL, TAG_GENERIC = 1, 2, 3, 4, 5, 6


class Reader:
    def __init__(self, blob):
        self.b = blob
        self.i = 0

    def i8(self):
        v = self.b[self.i]
        self.i += 1
        return v

    def i32(self):
        (v,) = struct.unpack_from(">i", self.b, self.i)
        self.i += 4
        return v

    def i64(self):
        (v,) = struct.unpack_from(">q", self.b, self.i)
        self.i += 8
        return v

    def string(self):
        n = self.i32()
        s = self.b[self.i : self.i + n].decode("utf-8", errors="replace")
        self.i += n
        return s

    def value(self):
        tag = self.i8()
        if tag == TAG_STRING:
            return ("str", self.string())
        if tag == TAG_LONG:
            return ("long", self.i64())
        if tag == TAG_BYTES:
            n = self.i32()
            self.i += n
            return ("bytes", n)
        if tag == TAG_BOOL:
            return ("bool", self.i8() != 0)
        if tag == TAG_NULL:
            return ("null", None)
        if tag == TAG_GENERIC:
            type_id = self.string()
            payload = self.value()
            return ("generic", (type_id, payload))
        raise ValueError(f"unknown DbValue tag {tag} at offset {self.i - 1}")


def _where(slot):
    """A field index prints as a slot; a property reference names itself."""
    return slot if isinstance(slot, str) else f"slot {slot}"


def _ref(value):
    """The list-row hash a decoded value can hold, or None.

    ⚠ A `genericData` slot wraps it: a list value there is stored as
    `GenericValue("tenshu/core/list", LongValue(hash))` (DbEntryMapper.serialize), never as a bare
    long, so a walk that takes only top-level longs calls that list unowned. The codec lets a
    payload be generic again, so this unwraps to any depth rather than exactly one.

    ⚠ Only a list-typed generic counts. A value type (TInteger, Instant, any `value(...)` over
    them) writes its number into the same LongValue, and the reader tells the two apart by type id
    alone. With no schema here, this matches the one id a list slot is written under — both list
    shapes serialize accepts (TListDto, ArrayDatabaseReference) carry `TList.Schema`. A bare long
    stays ambiguous (a TInteger field looks just like a reference) and still counts.
    """
    kind, v = value
    type_id = None
    while kind == "generic":
        type_id, (kind, v) = v
    if kind != "long" or type_id not in (None, LIST_TYPE):
        return None
    return v


def decode(blob):
    r = Reader(blob)
    type_id = r.string()
    data_id_hash = r.i64()
    fields = [r.value() for _ in range(r.i32())]
    props = {}
    for _ in range(r.i32()):
        key = r.string()
        props[key] = r.value()
    return type_id, data_id_hash, fields, props


# ---- the audit --------------------------------------------------------------------------------


def open_copy(path):
    if not os.path.exists(path):
        sys.exit(f"no such file: {path}")
    if not os.path.exists(path + "-wal"):
        print(
            f"note: no {os.path.basename(path)}-wal beside the copy — if the source store had "
            "one, this copy is a stale prefix of it (see the module docstring)",
            file=sys.stderr,
        )
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def audit(path, verbose):
    conn = open_copy(path)
    rows = conn.execute("SELECT hash, type, data FROM objects").fetchall()
    present = {h for h, _, _ in rows}
    print(f"{len(rows)} rows")

    # Control decoded-and-present before anything is called missing: a codec drift would read as
    # "everything is missing", and that is the trap the incident's own store queries hit.
    decoded = 0
    lists = []
    owners = collections.defaultdict(list)  # referenced row hash → (owner type, owner path, slot or property)
    paths = {}  # row hash → first string field (the path, for File/Folder)
    for h, type_id, data in rows:
        try:
            t, data_hash, fields, props = decode(data)
        except Exception as e:  # noqa: BLE001 — report, keep walking
            print(f"!! cannot decode row {h} ({type_id}): {e}")
            continue
        decoded += 1
        if data_hash != h:
            print(f"!! row {h} carries dataIdHash {data_hash} inside its blob")
        if fields and fields[0][0] == "str":
            paths[h] = fields[0][1]
        if t == LIST_TYPE:
            lists.append((h, [v for k, v in fields if k == "long"]))
        else:
            owner_path = fields[0][1] if fields and fields[0][0] == "str" else None
            for slot, value in enumerate(fields):
                ref = _ref(value)
                if ref is not None:
                    owners[ref].append((t, owner_path, slot))
            # A list held as an entity PROPERTY — File.FileHistory, SharedFile.Children —
            # keeps its row hash in `properties`, never in `fields` (DbEntryMapper.serialize).
            # Reading only fields found the dangling list and then reported it as having no
            # composite owner, which is the one thing this tool exists to answer.
            for key, value in props.items():
                ref = _ref(value)
                if ref is not None:
                    owners[ref].append((t, owner_path, f"property {key!r}"))
    print(f"{decoded} decoded, {len(lists)} list rows")

    dangling_lists = []
    for h, refs in lists:
        missing = [r for r in refs if r not in present]
        if missing:
            dangling_lists.append((h, refs, missing))

    if not dangling_lists:
        print("no list row references a missing member — every relation is intact")
        return 0

    print(f"\n{len(dangling_lists)} list row(s) with dangling member(s):\n")
    by_owner_prefix = collections.Counter()
    for h, refs, missing in dangling_lists:
        owner_desc = "; ".join(
            f"{t} {p!r} {_where(s)}" if p else f"{t} {_where(s)}" for t, p, s in owners.get(h, [])
        ) or "(no composite references this list — a service relation, or an orphan)"
        print(f"list {h}: {len(refs)} member(s), {len(missing)} missing — owned by {owner_desc}")
        for m in missing:
            print(f"    missing member hash {m}")
        for t, p, _s in owners.get(h, []):
            if p and p.startswith("/"):
                parts = p.split("/")
                by_owner_prefix["/".join(parts[:4]) if len(parts) > 3 else p] += len(missing)
        if verbose:
            print(f"    members: {refs}")
    if by_owner_prefix:
        print("\nmissing members by owner path prefix (first three segments):")
        for prefix, n in by_owner_prefix.most_common():
            print(f"    {n:6}  {prefix}")
    print(
        "\nName a missing member by hashing candidate paths: --match <hash> --paths-from "
        "<another device's objects.db copy>, or scripts/DataIdHashSearch.java for WhatsApp names."
    )
    return 1


def string_id_hash(type_id, key_value):
    """`StringDataId(type, keyValue)` for any type — e.g. a sync sidecar keyed by a wire id:
    `--string-id tenshu/sync/metadata <wireIdHex>`."""
    h = _feed(FNV_OFFSET_BASIS, type_id)
    h = _feed(h, "_")
    h = _feed(h, key_value)
    return _signed(h)


def has_rows(path, hashes):
    """Presence of specific row hashes in a store copy — the check §7 of the doc runs for a file's
    sidecar rows (`SyncMetadata`, `ResolvedDataId`, `LocalWriteMetadata`) once its wire id is known."""
    conn = open_copy(path)
    found = 0
    for h in hashes:
        row = conn.execute("SELECT type, length(data) FROM objects WHERE hash = ?", (h,)).fetchone()
        if row:
            found += 1
            print(f"present  {h}  {row[0]}  {row[1]} bytes")
        else:
            print(f"MISSING  {h}")
    return 0 if found == len(hashes) else 1


def match(target, paths_from, paths_file, under):
    candidates = []
    if paths_from:
        conn = open_copy(paths_from)
        for (data,) in conn.execute("SELECT data FROM objects WHERE type IN (?, ?)", (FILE_TYPE, FOLDER_TYPE)):
            t, _h, fields, _p = decode(data)
            if fields and fields[0][0] == "str":
                candidates.append(fields[0][1])
    if paths_file:
        with open(paths_file, encoding="utf-8") as f:
            for line in f:
                name = line.rstrip("\n")
                if not name:
                    continue
                candidates.append(f"{under.rstrip('/')}/{name}" if under else name)
    hits = [p for p in candidates if file_hash(p) == target]
    print(f"{len(candidates)} candidate path(s), {len(hits)} match(es) for {target}")
    for p in hits:
        print(f"MATCH: {p}")
    return 0 if hits else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("store", nargs="?", help="a COPY of objects.db (with its -wal beside it)")
    ap.add_argument("--verbose", action="store_true", help="print every member hash of a dangling list")
    ap.add_argument("--file-hash", metavar="PATH", help="hash of the File/Folder row at PATH")
    ap.add_argument("--files-array-hash", metavar="FOLDER", help="hash of FOLDER's Folder.FILES list row")
    ap.add_argument("--string-id", nargs=2, metavar=("TYPE", "KEY"), help="hash of StringDataId(TYPE, KEY)")
    ap.add_argument("--has", metavar="HASH", type=int, nargs="+", help="report whether these row hashes exist in the store copy")
    ap.add_argument("--match", metavar="HASH", type=int, help="name this member hash from candidate paths")
    ap.add_argument("--paths-from", metavar="DB", help="candidate paths: every File/Folder row in this store copy")
    ap.add_argument("--paths-file", metavar="FILE", help="candidate paths: one per line")
    ap.add_argument("--under", metavar="FOLDER", help="prefix for --paths-file names")
    ap.add_argument("--self-test", action="store_true", help="check the hash derivation against pinned vectors")
    args = ap.parse_args()

    if args.self_test:
        sys.exit(0 if self_test() else 1)
    if args.file_hash is not None:
        print(file_hash(args.file_hash))
        return
    if args.files_array_hash is not None:
        print(files_array_hash(args.files_array_hash))
        return
    if args.string_id is not None:
        print(string_id_hash(args.string_id[0], args.string_id[1]))
        return
    if args.has is not None:
        if not args.store:
            ap.error("--has needs a store copy")
        sys.exit(has_rows(args.store, args.has))
    if args.match is not None:
        if not (args.paths_from or args.paths_file):
            ap.error("--match needs --paths-from or --paths-file")
        sys.exit(match(args.match, args.paths_from, args.paths_file, args.under))
    if not args.store:
        ap.error("a store copy is required (or one of the --hash/--match/--self-test modes)")
    sys.exit(audit(args.store, args.verbose))


if __name__ == "__main__":
    main()
