#!/usr/bin/env python3
"""ST-3a — census of the relay's content blobs. READ ONLY.

Answers the question ST-3 is gated on: what are the blobs on that volume? Live content,
orphans from re-emits, or both.

House Rule #1 holds throughout: this counts and sizes ciphertext, never opens it.
"""
import base64
import collections
import os
import shutil
import sqlite3
import sys

DATA = "/mnt/relay_data"
DB = os.path.join(DATA, "relay.db")
BLOBS = os.path.join(DATA, "relay-blobs")
SNAP = "/tmp/relay-census.db"


def snapshot():
    # WAL is live; copy db + wal + shm together so the read sees a consistent picture
    # rather than racing the writer.
    for suffix in ("", "-wal", "-shm"):
        src = DB + suffix
        if os.path.exists(src):
            shutil.copy2(src, SNAP + suffix)


def human(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} PB"


def main():
    snapshot()
    conn = sqlite3.connect(f"file:{SNAP}?mode=ro", uri=True)

    # --- what the index believes ---
    rows = conn.execute("SELECT owner_id, ref, last_referenced_at FROM content_blob").fetchall()
    print(f"content_blob rows: {len(rows)}")

    by_owner = collections.Counter(r[0] for r in rows)
    print(f"distinct owners: {len(by_owner)}")
    for owner, count in by_owner.most_common(10):
        print(f"  owner {owner[:12]}… : {count} refs")

    if rows:
        stamps = sorted(r[2] for r in rows)
        print(f"last_referenced_at: min={stamps[0]} max={stamps[-1]}")

    shares = conn.execute("SELECT share_id, chunk_count FROM share_record").fetchall()
    share_chunks = sum(c for _, c in shares)
    print(f"share_record rows: {len(shares)} ({share_chunks} chunks)")

    # --- what is actually on disk ---
    disk = {}
    for name in os.listdir(BLOBS):
        path = os.path.join(BLOBS, name)
        try:
            disk[name] = os.path.getsize(path)
        except OSError:
            pass
    print(f"\nfiles in relay-blobs: {len(disk)}  total {human(sum(disk.values()))}")

    tmp = {n: s for n, s in disk.items() if n.endswith(".tmp")}
    print(f"  stale .tmp files: {len(tmp)}  ({human(sum(tmp.values()))})  <- ST-1 leak, pre-fix")

    # --- the join: which disk files does the index still claim? ---
    def key_for(owner, ref):
        raw = f"{owner}/{ref}".encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    claimed = {key_for(o, r) for o, r, _ in rows}
    for share_id, chunk_count in shares:
        for i in range(chunk_count):
            claimed.add(key_for("share", f"{share_id}/{i}"))

    on_disk = {n for n in disk if not n.endswith(".tmp")}
    orphans = on_disk - claimed
    missing = claimed - on_disk

    orphan_bytes = sum(disk[n] for n in orphans)
    print(f"\n=== THE ANSWER ===")
    print(f"claimed by an index row : {len(on_disk & claimed)}  ({human(sum(disk[n] for n in on_disk & claimed))})")
    print(f"ORPHANS (no index row)  : {len(orphans)}  ({human(orphan_bytes)})")
    print(f"index rows with no file : {len(missing)}")
    if sum(disk.values()):
        print(f"orphan share of bytes   : {100.0 * orphan_bytes / sum(disk.values()):.1f}%")

    sizes = sorted(disk[n] for n in on_disk)
    if sizes:
        def pct(p):
            return sizes[min(len(sizes) - 1, int(len(sizes) * p))]
        print(f"\nblob sizes: p50={human(pct(0.5))} p90={human(pct(0.9))} max={human(sizes[-1])}")
        print(f"  2 MB chunks (the content plane's unit): {sum(1 for s in sizes if s == 2*1024*1024)}")


if __name__ == "__main__":
    main()
