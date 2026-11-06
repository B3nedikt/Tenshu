#!/usr/bin/env python3
"""Tests for mirror-blobs.py — the first-seen-missing ledger, the window, and the safety rails.

  python3 deploy/scaleway-relay-backups/test_mirror_blobs.py      (no network, no boto3 needed)

An in-memory S3 stands in for Scaleway: list_objects_v2 pages at MaxKeys like the real one, and a
test can make it lie (truncate without a token, omit a key, fail a HEAD) to exercise the rails. It
keeps a storage class per object, and two instances stand in for two regions (the second-region
target streams between them; nothing is copied server-side across instances).
"""
import datetime as dt
import email.utils
import importlib.util
import io
import json
import os
import sys
import unittest

sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "mirror_blobs", os.path.join(os.path.dirname(os.path.abspath(__file__)), "mirror-blobs.py"))
mb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mb)

SRC, DST, OTHER = "src", "mirror", "other"
T0 = dt.datetime(2026, 10, 4, 4, 40, tzinfo=dt.timezone.utc)  # a Sunday run
WEEK = dt.timedelta(days=7)
DAY = dt.timedelta(days=1)


class S3Error(Exception):
    def __init__(self, code=None, status=None):
        super().__init__(f"{code} {status}")
        self.response = {"Error": {"Code": str(code)} if code else {},
                         "ResponseMetadata": {"HTTPStatusCode": status} if status else {}}


def not_found():
    return S3Error("404", 404)


class FakeS3:
    def __init__(self, page_size=1000):
        self.buckets = {SRC: {}, DST: {}, OTHER: {}}
        self.page_size = page_size
        self.list_calls = {b: 0 for b in self.buckets}
        self.deleted = []
        self.puts = []
        self.server_now = None                # if set, list responses carry this Date header
        self.date_step = dt.timedelta(0)      # the server clock advances this much per list call
        self.date_header = None               # a raw Date header overriding server_now
        # Fault injection.
        self.truncate_without_token = set()   # buckets whose pages claim truncation, no token
        self.hide_from_list = {}              # bucket -> keys the listing omits (HEAD still sees them)
        self.hide_on_list_call = {}           # bucket -> (call number, keys) omitted on that call only
        self.head_error = {}                  # key -> exception HEAD raises
        self.delete_errors = set()            # keys DeleteObjects reports in Errors
        self.corrupt_puts = False             # PutObject stores something other than the body
        self.classes = {}                     # (bucket, key) -> storage class; absent = STANDARD
        self.ignore_storage_class = False     # an endpoint that accepts the parameter and drops it
        self.copies = []                      # (src bucket, src key, dst bucket, dst key, StorageClass)
        self.max_read = 0                     # largest single read() of a streamed GET body
        self.get_bodies = []

    def put(self, bucket, key, size):
        self.buckets[bucket][key] = b"x" * size
        self.classes.pop((bucket, key), None)

    def storage_class(self, bucket, key):
        return self.classes.get((bucket, key), "STANDARD")

    def _set_class(self, bucket, key, sc):
        if sc and not self.ignore_storage_class:
            self.classes[(bucket, key)] = sc
        else:
            self.classes.pop((bucket, key), None)

    def list_objects_v2(self, Bucket, MaxKeys=1000, ContinuationToken=None):
        if ContinuationToken is None:
            self.list_calls[Bucket] += 1
        hidden = set(self.hide_from_list.get(Bucket, ()))
        call, once = self.hide_on_list_call.get(Bucket, (None, ()))
        if call == self.list_calls[Bucket]:
            hidden |= set(once)
        keys = sorted(k for k in self.buckets[Bucket] if k not in hidden)
        start = int(ContinuationToken or 0)
        size = min(MaxKeys, self.page_size)
        page = keys[start:start + size]
        resp = {"Contents": [{"Key": k, "Size": len(self.buckets[Bucket][k]),
                              "StorageClass": self.storage_class(Bucket, k)} for k in page],
                "IsTruncated": start + size < len(keys)}
        if self.date_header is not None:
            resp["ResponseMetadata"] = {"HTTPHeaders": {"date": self.date_header}}
        elif self.server_now:
            resp["ResponseMetadata"] = {"HTTPHeaders": {"date": email.utils.format_datetime(self.server_now, usegmt=True)}}
            self.server_now += self.date_step
        if resp["IsTruncated"]:
            if Bucket in self.truncate_without_token:
                return resp
            resp["NextContinuationToken"] = str(start + size)
        return resp

    def copy_object(self, Bucket, Key, CopySource, StorageClass=None, MetadataDirective=None):
        if CopySource["Key"] not in self.buckets[CopySource["Bucket"]]:
            raise not_found()
        self.copies.append((CopySource["Bucket"], CopySource["Key"], Bucket, Key, StorageClass))
        self.buckets[Bucket][Key] = self.buckets[CopySource["Bucket"]][CopySource["Key"]]
        self._set_class(Bucket, Key, StorageClass)

    def head_object(self, Bucket, Key):
        if Key in self.head_error:
            raise self.head_error[Key]
        if Key not in self.buckets[Bucket]:
            raise not_found()
        resp = {"ContentLength": len(self.buckets[Bucket][Key])}
        if self.storage_class(Bucket, Key) != "STANDARD":   # S3 omits the header for STANDARD
            resp["StorageClass"] = self.storage_class(Bucket, Key)
        return resp

    def get_object(self, Bucket, Key):
        if Key not in self.buckets[Bucket]:
            raise S3Error("NoSuchKey", 404)
        body = StreamingBody(self.buckets[Bucket][Key], self)
        self.get_bodies.append(body)
        return {"Body": body}

    def put_object(self, Bucket, Key, Body, ContentType=None):
        self.puts.append(Key)
        self.buckets[Bucket][Key] = Body + (b" " if self.corrupt_puts else b"")
        self.classes.pop((Bucket, Key), None)

    def upload_fileobj(self, Fileobj, Bucket, Key, ExtraArgs=None, Config=None):
        # Like s3transfer on a non-seekable body: read it in chunks, never whole.
        chunks = []
        while True:
            c = Fileobj.read(UPLOAD_CHUNK)
            if not c:
                break
            chunks.append(c)
        self.puts.append(Key)
        self.buckets[Bucket][Key] = b"".join(chunks)
        self._set_class(Bucket, Key, (ExtraArgs or {}).get("StorageClass"))

    def delete_objects(self, Bucket, Delete):
        done, errors = [], []
        for o in Delete["Objects"]:
            if o["Key"] in self.delete_errors:
                errors.append({"Key": o["Key"], "Code": "InternalError", "Message": "boom"})
                continue
            self.buckets[Bucket].pop(o["Key"], None)
            self.deleted.append(o["Key"])
            done.append({"Key": o["Key"]})
        return {"Deleted": done, "Errors": errors}


UPLOAD_CHUNK = 64


class StreamingBody:
    """botocore's StreamingBody, minus the network: read(n) and close(); records the largest read so a
    test can tell a stream from a whole-object `.read()`."""

    def __init__(self, data, s3):
        self._buf = io.BytesIO(data)
        self._s3 = s3
        self.closed = False

    def read(self, n=-1):
        b = self._buf.read(n)
        self._s3.max_read = max(self._s3.max_read, len(b))
        return b

    def close(self):
        self.closed = True


def on(**kw):
    return mb.Settings(prune=True, **kw)


class Base(unittest.TestCase):
    def setUp(self):
        self.s3 = FakeS3()
        self.log = []
        self.pin_server = False               # True: the test sets s3.server_now itself
        # 100 live blobs of 1000 bytes, all mirrored.
        for i in range(100):
            self.s3.put(SRC, f"blob-{i:03d}", 1000)
        self.assertEqual(self.run_at(T0, mb.Settings()), 0)

    def run_at(self, now, settings, src=SRC, **kw):
        self.log = []
        if not self.pin_server:
            self.s3.server_now = now
        return mb.run(self.s3, src, DST, settings, now, out=self.log.append, **kw)

    def weekly(self, first, last, settings, **kw):
        """Run every week from week `first` to `last` inclusive; return the codes."""
        return [self.run_at(T0 + w * WEEK, settings, **kw) for w in range(first, last + 1)]

    def doc(self):
        raw = self.s3.buckets[DST].get(mb.LEDGER_KEY)
        return json.loads(raw) if raw else None

    def ledger(self):
        return self.doc()["missing"]

    def delete_source(self, *keys):
        for k in keys:
            del self.s3.buckets[SRC][k]

    def logged(self, needle):
        return any(needle in line for line in self.log)


class LedgerTest(Base):
    def test_first_seen_recorded_once_and_kept(self):
        self.delete_source("blob-001")
        self.assertEqual(self.run_at(T0 + WEEK, mb.Settings()), 0)
        e = self.ledger()["blob-001"]
        self.assertEqual((e["since"], e["sightings"], e["size"]), ("2026-10-11T04:40:00Z", 1, 1000))
        self.run_at(T0 + 2 * WEEK, mb.Settings())
        e = self.ledger()["blob-001"]
        self.assertEqual((e["since"], e["sightings"]), ("2026-10-11T04:40:00Z", 2))

    def test_ledger_records_the_source_and_its_size(self):
        d = self.doc()
        self.assertEqual(d["source"]["bucket"], SRC)
        self.assertEqual((d["source_count"], d["source_bytes"]), (100, 100_000))
        self.assertEqual(d["updated"], "2026-10-04T04:40:00Z")

    def test_ledger_is_written_with_pruning_off_but_nothing_is_deleted(self):
        self.delete_source("blob-001")
        self.assertEqual(set(self.weekly(1, 9, mb.Settings())), {0})
        self.assertIn("blob-001", self.s3.buckets[DST])
        self.assertEqual(self.s3.deleted, [])
        self.assertEqual(self.ledger()["blob-001"]["sightings"], 9)

    def test_reappearance_clears_the_entry(self):
        self.delete_source("blob-001")
        self.run_at(T0 + WEEK, on())
        self.s3.put(SRC, "blob-001", 1000)
        self.run_at(T0 + 2 * WEEK, on())
        self.assertEqual(self.ledger(), {})
        # …and the clock restarts from scratch if it goes missing again.
        self.delete_source("blob-001")
        self.weekly(3, 6, on())
        self.assertEqual(self.ledger()["blob-001"]["since"], "2026-10-25T04:40:00Z")
        self.assertIn("blob-001", self.s3.buckets[DST])

    def test_ledger_key_is_never_mirrored_pruned_or_counted(self):
        self.s3.put(SRC, mb.LEDGER_KEY, 5)   # a hostile/accidental source key under the prefix
        self.delete_source("blob-001")
        self.weekly(1, 11, on())
        self.assertNotIn(mb.LEDGER_KEY, self.ledger())
        self.assertTrue(self.logged("reserved prefix"))
        self.assertIn(mb.LEDGER_KEY, self.s3.buckets[DST])
        self.assertNotEqual(self.s3.buckets[DST][mb.LEDGER_KEY], b"xxxxx")
        self.assertNotIn(mb.LEDGER_KEY, self.s3.deleted)

    def test_corrupt_ledger_stops_the_prune_not_the_copy(self):
        self.s3.buckets[DST][mb.LEDGER_KEY] = b'{"version": 99, "missing": {}}'
        self.s3.put(SRC, "blob-new", 10)
        self.assertEqual(self.run_at(T0 + WEEK, on()), mb.EXIT_LEDGER)
        self.assertIn("blob-new", self.s3.buckets[DST])
        self.assertEqual(self.s3.buckets[DST][mb.LEDGER_KEY], b'{"version": 99, "missing": {}}')

    def test_ledger_read_back_mismatch_is_exit_4(self):
        self.s3.corrupt_puts = True
        self.s3.put(SRC, "blob-new", 10)
        self.assertEqual(self.run_at(T0 + WEEK, on()), mb.EXIT_LEDGER)
        self.assertIn("blob-new", self.s3.buckets[DST])
        self.assertTrue(self.logged("read-back differs"))


class WindowTest(Base):
    def test_pruned_at_the_first_weekly_run_past_the_window_and_not_before(self):
        self.delete_source("blob-001")
        codes = self.weekly(1, 5, on())                 # first seen week 1; weeks 2–5 = 7–28 days
        self.assertEqual(set(codes), {0})
        self.assertIn("blob-001", self.s3.buckets[DST])
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on()), 0)   # 35 days, 6 sightings
        self.assertEqual(self.s3.deleted, ["blob-001"])
        self.assertEqual(self.ledger(), {})
        self.assertIn("  pruned: 1 objects, 1000 bytes (missing from the source for >= 30 days)", self.log)

    def test_retention_is_configurable(self):
        s = on(retention_days=10)                          # ceil(10/7)+1 = 3 sightings, 14 days
        self.assertEqual(s.sightings_required, 3)
        self.delete_source("blob-001")
        self.weekly(1, 2, s)
        self.assertTrue("blob-001" in self.s3.buckets[DST])   # 7 days, 2 sightings
        self.run_at(T0 + 3 * WEEK, s)
        self.assertEqual(self.s3.deleted, ["blob-001"])

    def test_dry_run_and_prune_report_write_nothing(self):
        self.delete_source("blob-001")
        self.weekly(1, 5, on())
        puts, before = list(self.s3.puts), dict(self.s3.buckets[DST])
        self.s3.put(SRC, "blob-new", 10)
        for kw in ({"dry_run": True}, {"prune_report": True}):
            self.assertEqual(self.run_at(T0 + 6 * WEEK, on(), **kw), 0)
            self.assertTrue(self.logged("(prunable): 1 object(s), 1000 bytes"), self.log)
            self.assertTrue(self.logged("would prune 1 object(s)"), self.log)
        self.assertEqual(self.s3.puts, puts)
        self.assertEqual(self.s3.buckets[DST], before)


class RunCounterTest(Base):
    def test_extra_runs_within_a_week_add_no_sighting(self):
        self.delete_source("blob-001")
        self.run_at(T0 + WEEK, on())
        for h in (1, 30, 100):
            self.run_at(T0 + WEEK + dt.timedelta(hours=h), on())
        self.assertEqual(self.ledger()["blob-001"]["sightings"], 1)

    def test_age_alone_is_not_enough(self):
        # Two runs 30 days apart (the gap guard accepted by hand): old enough, too few sightings.
        self.delete_source("blob-001")
        self.run_at(T0 + WEEK, on())
        self.run_at(T0 + WEEK + 30 * DAY, on(), accept_clock=True)
        self.run_at(T0 + WEEK + 31 * DAY, on())
        self.assertIn("blob-001", self.s3.buckets[DST])
        self.assertEqual(self.s3.deleted, [])


class ClockTest(Base):
    def test_clock_jumped_forward_prunes_nothing_and_records_nothing(self):
        self.delete_source("blob-001")
        self.weekly(1, 3, on())
        before = self.doc()
        code = self.run_at(T0 + 3 * WEEK + 365 * DAY, on())
        self.assertEqual(code, mb.EXIT_CLOCK)
        self.assertEqual(self.s3.deleted, [])
        self.assertEqual(self.doc(), before)
        # Clock fixed: the next punctual run carries on as if nothing happened.
        self.assertEqual(self.run_at(T0 + 4 * WEEK, on()), 0)
        self.assertIn("blob-001", self.s3.buckets[DST])

    def test_clock_backward_prunes_nothing(self):
        self.delete_source("blob-001")
        self.weekly(1, 6, mb.Settings())
        self.assertEqual(self.run_at(T0 + 2 * WEEK, on()), mb.EXIT_CLOCK)
        self.assertEqual(self.s3.deleted, [])

    def test_forward_jump_within_the_gap_adds_one_sighting_only(self):
        self.delete_source("blob-001")
        self.run_at(T0 + WEEK, on())
        self.run_at(T0 + WEEK + 20 * DAY, on())   # within the gap limit, but one run
        self.assertEqual(self.ledger()["blob-001"]["sightings"], 2)
        self.assertIn("blob-001", self.s3.buckets[DST])

    def test_local_clock_disagreeing_with_the_server_refuses(self):
        self.delete_source("blob-001")
        self.weekly(1, 5, on())
        self.pin_server = True
        self.s3.server_now = T0 + 5 * WEEK + 2 * DAY
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on()), mb.EXIT_CLOCK)
        self.assertEqual(self.s3.deleted, [])
        self.s3.server_now = T0 + 6 * WEEK + dt.timedelta(minutes=3)
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on()), 0)
        self.assertEqual(self.s3.deleted, ["blob-001"])

    def test_accept_clock_records_but_does_not_prune(self):
        self.delete_source("blob-001")
        self.weekly(1, 5, on())
        self.assertEqual(self.run_at(T0 + 10 * WEEK, on(), accept_clock=True), 0)
        self.assertEqual(self.s3.deleted, [])
        self.assertEqual(self.doc()["updated"], mb.utc(T0 + 10 * WEEK))
        self.run_at(T0 + 11 * WEEK, on())
        self.assertEqual(self.s3.deleted, ["blob-001"])


class WrongSourceTest(Base):
    def setUp(self):
        super().setUp()
        for i in range(1000):                    # a different, LARGER bucket
            self.s3.put(OTHER, f"blob-other-{i:04d}", 1000)

    def test_wrong_larger_source_deletes_nothing_ever(self):
        codes = [self.run_at(T0 + w * WEEK, on(), src=OTHER) for w in range(1, 12)]
        self.assertTrue(all(c == mb.EXIT_REFUSED for c in codes), codes)
        self.assertEqual(self.s3.deleted, [])
        self.assertTrue(all(f"blob-{i:03d}" in self.s3.buckets[DST] for i in range(100)))
        self.assertTrue(self.logged("source changed"))

    def test_without_the_identity_guard_the_breakers_still_hold(self):
        # Same identity (the guard cannot see it), so only the size-based breakers stand.
        ident = {"bucket": SRC, "endpoint": ""}
        codes = [self.run_at(T0 + w * WEEK, on(), src=OTHER, identity=ident) for w in range(1, 12)]
        self.assertEqual(codes[0], mb.EXIT_REFUSED)
        self.assertTrue(self.logged("held since"))
        self.assertTrue(all(c == mb.EXIT_REFUSED for c in codes), codes)
        self.assertEqual(self.s3.deleted, [])

    def test_copy_flood_breaker_alone(self):
        ident = {"bucket": SRC, "endpoint": ""}
        self.s3.buckets[OTHER].update(self.s3.buckets[SRC])   # superset: nothing goes missing
        self.assertEqual(self.run_at(T0 + WEEK, on(), src=OTHER, identity=ident), mb.EXIT_REFUSED)
        self.assertTrue(self.logged("this run copies 1000 objects"))

    def test_accept_source_change_restarts_every_clock(self):
        self.delete_source("blob-001")
        self.weekly(1, 5, on())
        new = {"bucket": SRC, "endpoint": "https://elsewhere"}
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on(), identity=new), mb.EXIT_REFUSED)
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on(), identity=new, accept_source_change=True), 0)
        self.assertEqual(self.s3.deleted, [])
        self.assertEqual(self.ledger()["blob-001"]["sightings"], 1)
        self.assertEqual(self.doc()["source"], new)


class BreakerTest(Base):
    def test_mass_deletion_trips_holds_and_prunes_nothing(self):
        self.delete_source(*[f"blob-{i:03d}" for i in range(20)])   # 20% in one week
        codes = self.weekly(1, 8, on())
        self.assertTrue(all(c == mb.EXIT_REFUSED for c in codes), codes)
        self.assertEqual(self.s3.deleted, [])
        self.assertTrue(self.logged("held since"))
        self.assertEqual(len(self.ledger()), 20)   # facts still recorded

    def test_override_prunes_once_and_clears_the_hold(self):
        self.delete_source(*[f"blob-{i:03d}" for i in range(20)])
        self.weekly(1, 5, on())
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on(), override_breaker=True), 0)
        self.assertEqual(len(self.s3.deleted), 20)
        self.assertIsNone(self.doc()["hold"])
        self.assertEqual(self.run_at(T0 + 7 * WEEK, on()), 0)

    def test_ordinary_churn_does_not_trip(self):
        # Steady state: 5 deleted and 5 uploaded every week. The total missing reaches ~30 %, the
        # per-run signal stays at 5 %, the source does not shrink, and pruning keeps up without a hold.
        n = 0
        for w in range(1, 13):
            self.delete_source(*[f"blob-{i:03d}" for i in range(n, n + 5)])
            for i in range(5):
                self.s3.put(SRC, f"blob-new-{w:02d}-{i}", 1000)
            n += 5
            self.assertEqual(self.run_at(T0 + w * WEEK, on()), 0, self.log)
        self.assertEqual(len(self.s3.deleted), 35)   # weeks 1–7's deletes, each 35 days on
        self.assertIsNone(self.doc()["hold"])

    def test_byte_signal(self):
        self.s3.put(SRC, "blob-huge", 50_000)          # 1/3 of the bytes, 1/101 of the objects
        self.run_at(T0 + WEEK, mb.Settings())
        self.delete_source("blob-huge")
        self.assertEqual(self.run_at(T0 + 2 * WEEK, on()), mb.EXIT_REFUSED)
        self.assertTrue(self.logged("went missing from the source since the last run"))

    def test_absolute_cap(self):
        self.delete_source("blob-001")
        self.assertEqual(self.run_at(T0 + WEEK, on(max_missing_gb=0.0000005)), mb.EXIT_REFUSED)

    def test_empty_source_listing_trips_at_100_percent(self):
        self.s3.buckets[SRC] = {}
        self.assertEqual(self.run_at(T0 + WEEK, on()), mb.EXIT_REFUSED)
        self.assertTrue(self.logged("(100.0% / 100.0% of the mirror"))
        self.assertTrue(self.logged("the source fell from 100 to 0 objects"))
        self.weekly(2, 8, on())
        self.assertEqual(self.s3.deleted, [])

    def test_source_count_drop(self):
        s = on(max_new_missing=0.5, max_total_missing=0.9)
        self.delete_source(*[f"blob-{i:03d}" for i in range(30)])
        self.assertEqual(self.run_at(T0 + WEEK, s), mb.EXIT_REFUSED)
        self.assertTrue(self.logged("the source fell from 100 to 70 objects"))

    def test_with_pruning_off_the_run_that_sets_a_hold_fails_later_ones_warn(self):
        self.delete_source(*[f"blob-{i:03d}" for i in range(20)])
        self.assertEqual(self.run_at(T0 + WEEK, mb.Settings()), mb.EXIT_REFUSED)
        self.assertTrue(self.logged("CIRCUIT BREAKER"))
        self.assertIsNotNone(self.doc()["hold"])
        self.assertEqual(self.run_at(T0 + 2 * WEEK, mb.Settings()), 0)
        self.assertTrue(self.logged("held since"))
        self.assertEqual(self.s3.deleted, [])

    def test_backlog_on_a_new_ledger_is_a_baseline_not_a_spike(self):
        s3 = FakeS3()
        for i in range(100):
            s3.put(DST, f"blob-{i:03d}", 1000)
        for i in range(70, 100):
            s3.put(SRC, f"blob-{i:03d}", 1000)
        log = []
        s3.server_now = T0
        # 70 % of the mirror is a pre-ledger backlog: no per-run signal, but the total guard holds it.
        self.assertEqual(mb.run(s3, SRC, DST, on(), T0, out=log.append), mb.EXIT_REFUSED)
        self.assertFalse(any("since the last run" in line for line in log))
        self.assertTrue(any("in total" in line for line in log))


class SecondReviewTest(Base):
    # MEDIUM 1 — a malformed ledger field stops the prune, never the copy.
    def test_malformed_ledger_fields_still_copy(self):
        for field, value in (("hold", {"at": "yesterday"}), ("hold", "cleared"), ("source", "src"),
                             ("source_count", "100"), ("source_count", -1), ("updated", 5)):
            self.setUp()
            doc = self.doc()
            doc[field] = value
            self.s3.buckets[DST][mb.LEDGER_KEY] = json.dumps(doc).encode()
            self.s3.put(SRC, "blob-new", 10)
            self.assertEqual(self.run_at(T0 + WEEK, on()), mb.EXIT_LEDGER, (field, value))
            self.assertTrue("blob-new" in self.s3.buckets[DST], (field, value))

    def test_a_guard_that_raises_refuses_but_the_copy_runs(self):
        real = mb.breakers
        mb.breakers = lambda *a, **k: (_ for _ in ()).throw(TypeError("boom"))
        try:
            self.s3.put(SRC, "blob-new", 10)
            self.assertEqual(self.run_at(T0 + WEEK, on()), mb.EXIT_ERROR)
        finally:
            mb.breakers = real
        self.assertTrue("blob-new" in self.s3.buckets[DST])
        self.assertTrue(self.logged("prune guards could not be evaluated"))

    # MEDIUM 2 — a slow drain under every per-run limit.
    def test_slow_drain_under_the_per_run_limits_is_caught(self):
        self.s3 = FakeS3()
        for i in range(1000):
            self.s3.put(SRC, f"blob-{i:04d}", 1000)
        self.run_at(T0, mb.Settings())
        for w in range(1, 13):
            live = sorted(self.s3.buckets[SRC])
            self.delete_source(*live[:int(len(live) * 0.09)])
            self.run_at(T0 + w * WEEK, on())
        self.assertIn("a slow drain?", self.doc()["hold"]["reason"])
        self.assertEqual(self.s3.deleted, [])
        self.assertLess(len(self.s3.buckets[SRC]), 400)

    # MEDIUM 4, option 3 — keep the FIRST prior, then overwrite.
    def test_first_mismatch_keeps_the_prior_and_overwrites(self):
        good = self.s3.buckets[DST]["blob-007"]
        self.s3.put(SRC, "blob-007", 3)          # a truncated re-PUT, or a re-seal
        self.assertEqual(self.run_at(T0 + WEEK, on()), 0)
        self.assertEqual(self.s3.buckets[DST][mb.prior_key("blob-007")], good)
        self.assertEqual(len(self.s3.buckets[DST]["blob-007"]), 3)
        self.assertTrue(self.logged("SIZE CHANGED blob-007"))
        self.assertTrue(self.logged("prior kept at _tenshu-mirror/prior/blob-007"))

    def test_second_mismatch_keeps_the_original_prior(self):
        good = self.s3.buckets[DST]["blob-007"]
        self.s3.put(SRC, "blob-007", 3)
        self.run_at(T0 + WEEK, on())
        self.s3.put(SRC, "blob-007", 5)
        self.assertEqual(self.run_at(T0 + 2 * WEEK, on()), 0)
        self.assertEqual(self.s3.buckets[DST][mb.prior_key("blob-007")], good)
        self.assertEqual(len(self.s3.buckets[DST]["blob-007"]), 5)

    def test_failed_prior_copy_blocks_the_overwrite(self):
        good = self.s3.buckets[DST]["blob-007"]
        real = self.s3.copy_object

        def copy(Bucket, Key, CopySource, **kw):
            if Key.startswith(mb.PRIOR_PREFIX):
                raise RuntimeError("503 SlowDown")
            return real(Bucket=Bucket, Key=Key, CopySource=CopySource, **kw)
        self.s3.copy_object = copy
        self.s3.put(SRC, "blob-007", 3)
        self.s3.put(SRC, "blob-new", 10)
        self.assertEqual(self.run_at(T0 + WEEK, on()), mb.EXIT_SIZE_MISMATCH)
        self.assertEqual(self.s3.buckets[DST]["blob-007"], good)
        self.assertNotIn(mb.prior_key("blob-007"), self.s3.buckets[DST])
        self.assertTrue("blob-new" in self.s3.buckets[DST])

    def test_prior_with_the_wrong_size_blocks_the_overwrite(self):
        good = self.s3.buckets[DST]["blob-007"]
        real = self.s3.head_object
        self.s3.head_object = lambda Bucket, Key: (
            {"ContentLength": 1} if Key.startswith(mb.PRIOR_PREFIX) else real(Bucket=Bucket, Key=Key))
        self.s3.put(SRC, "blob-007", 3)
        self.assertEqual(self.run_at(T0 + WEEK, on()), mb.EXIT_SIZE_MISMATCH)
        self.assertEqual(self.s3.buckets[DST]["blob-007"], good)

    def test_prune_removes_main_and_prior(self):
        self.s3.put(SRC, "blob-007", 3)
        self.run_at(T0 + WEEK, on())
        self.delete_source("blob-007")
        self.weekly(2, 7, on())
        self.assertNotIn("blob-007", self.s3.buckets[DST])
        self.assertNotIn(mb.prior_key("blob-007"), self.s3.buckets[DST])
        self.assertEqual(sorted(self.s3.deleted), ["_tenshu-mirror/prior/blob-007", "blob-007"])
        self.assertNotIn(mb.prior_key("blob-007"), self.ledger())

    def test_priors_are_not_counted_missing_or_pruned_alone(self):
        # 20 priors (20 % of the mirror) whose main keys are all live: no breaker, nothing missing,
        # nothing pruned — even after many weeks.
        for i in range(20):
            self.s3.put(SRC, f"blob-{i:03d}", 3)
        self.run_at(T0 + WEEK, on())
        self.assertEqual(sum(k.startswith(mb.PRIOR_PREFIX) for k in self.s3.buckets[DST]), 20)
        self.assertEqual(set(self.weekly(2, 9, on())), {0}, self.log)
        self.assertEqual(self.ledger(), {})
        self.assertEqual(self.s3.deleted, [])
        self.assertTrue(self.logged("mirror mirror: 100 objects"))
        self.assertIsNone(self.doc()["hold"])

    def test_dry_run_reports_a_size_change_and_writes_nothing(self):
        before = dict(self.s3.buckets[DST])
        self.s3.put(SRC, "blob-007", 3)
        self.assertEqual(self.run_at(T0 + WEEK, on(), dry_run=True), 0)
        self.assertTrue(self.logged("would save prior to _tenshu-mirror/prior/blob-007"))
        self.assertEqual(self.s3.buckets[DST], before)

    # LOW 5 — the backlog: one override accepts it, later runs neither re-trip nor re-hold.
    def test_backlog_override_is_accepted_once(self):
        s3 = self.s3 = FakeS3()
        for i in range(100):
            s3.put(DST, f"blob-{i:03d}", 1000)
        for i in range(70, 100):
            s3.put(SRC, f"blob-{i:03d}", 1000)
        self.assertEqual(self.run_at(T0, mb.Settings()), mb.EXIT_REFUSED)    # baseline sets the hold
        self.assertEqual(self.run_at(T0 + WEEK, mb.Settings(), override_breaker=True), 0)
        self.assertIsNone(self.doc()["hold"])
        codes = self.weekly(2, 5, on())
        self.assertEqual(set(codes), {0}, self.log)
        self.assertIsNone(self.doc()["hold"])
        self.assertEqual(len(s3.deleted), 70)                               # at the 6th sighting

    # LOW 6 — no readable Date header fails closed; -0000 is UTC.
    def test_no_date_header_prunes_nothing_but_copies(self):
        self.delete_source("blob-001")
        self.weekly(1, 5, on())
        self.pin_server = True
        self.s3.server_now = None
        self.s3.put(SRC, "blob-new", 10)
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on()), mb.EXIT_CLOCK)
        self.assertEqual(self.s3.deleted, [])
        self.assertTrue("blob-new" in self.s3.buckets[DST])
        self.s3.date_header = "not a date"
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on()), mb.EXIT_CLOCK)

    def test_minus_0000_date_is_utc(self):
        d = mb.response_date({"ResponseMetadata": {"HTTPHeaders": {"date": "Sun, 04 Oct 2026 04:40:00 -0000"}}})
        self.assertEqual(d, T0)

    # LOW 7 — a long listing is not skew.
    def test_long_listing_is_not_read_as_skew(self):
        self.s3.page_size = 5                    # ~20 list calls per bucket per run
        self.s3.date_step = dt.timedelta(minutes=10)
        self.delete_source("blob-001")
        self.weekly(1, 6, on())
        self.assertEqual(self.s3.deleted, ["blob-001"])

    # LOW 8 — a v1 ledger upgrades.
    def test_v1_ledger_upgrades_keeping_since(self):
        self.delete_source("blob-001")
        v1 = {"version": 1, "updated": "2026-10-04T04:40:00Z",
              "missing": {"blob-001": {"since": "2026-09-01T04:40:00Z", "size": 1000}}}
        self.s3.buckets[DST][mb.LEDGER_KEY] = json.dumps(v1).encode()
        self.assertEqual(self.run_at(T0 + WEEK, on()), 0)
        e = self.ledger()["blob-001"]
        self.assertEqual((e["since"], e["sightings"]), ("2026-09-01T04:40:00Z", 2))
        self.assertEqual(self.doc()["version"], 2)
        self.assertTrue("blob-001" in self.s3.buckets[DST])   # old since, but 2 of 6 sightings
        self.weekly(2, 5, on())
        self.assertEqual(self.s3.deleted, ["blob-001"])

    # LOW 10 — no fallback lock.
    def test_unopenable_lock_raises(self):
        with self.assertRaises(OSError):
            mb.take_lock("/nonexistent-dir-for-test/l.lock")


class FinalReviewTest(Base):
    def big(self, n=1000):
        self.s3 = FakeS3()
        for i in range(n):
            self.s3.put(SRC, f"blob-{i:04d}", 1000)
        self.run_at(T0, mb.Settings())

    def drain(self, weeks, rate, settings, start=1):
        codes = []
        for w in range(start, start + weeks):
            live = sorted(self.s3.buckets[SRC])
            self.delete_source(*live[:int(len(live) * rate)])
            codes.append(self.run_at(T0 + w * WEEK, settings))
        return codes

    # 1 — a steady drain under every per-run limit is caught against the PEAK.
    def test_steady_drain_below_seven_percent_is_caught(self):
        self.big()
        codes = self.drain(15, 0.065, on())
        self.assertEqual(self.s3.deleted, [])
        self.assertIn("its peak of 1000", self.doc()["hold"]["reason"])
        self.assertIn(mb.EXIT_REFUSED, codes)
        self.assertEqual(self.doc()["peak_source_count"], 1000)

    # 2 — an override resets the peak instead of exempting the keys in flight.
    def test_override_resets_the_peak_and_exempts_nothing_from_it(self):
        self.big()
        self.drain(8, 0.065, on())                       # trips against the peak of 1000
        n = len(self.s3.buckets[SRC])
        self.assertEqual(self.run_at(T0 + 9 * WEEK, on(), override_breaker=True), 0)
        self.assertEqual(self.doc()["peak_source_count"], n)
        self.assertTrue(all(e.get("accepted") for e in self.ledger().values()))
        before = len(self.s3.deleted)
        codes = self.drain(12, 0.065, on(), start=10)    # the drain continues
        self.assertIn(mb.EXIT_REFUSED, codes)
        self.assertIn(f"its peak of {n}", self.doc()["hold"]["reason"])
        self.assertLess(len(self.s3.buckets[SRC]), n * 0.7 + 1)
        # Pruning stopped once the source fell 30 % below the reset peak; it did not drain on.
        self.assertLess(len(self.s3.deleted) - before, n)

    def test_peak_follows_uploads(self):
        for i in range(50):
            self.s3.put(SRC, f"blob-up-{i}", 1000)
        self.run_at(T0 + WEEK, mb.Settings())
        self.assertEqual(self.doc()["peak_source_count"], 150)

    # 3 — a new incident under an existing hold fails the run and is appended.
    def test_new_incident_under_a_hold_fails_and_is_recorded(self):
        self.big()
        self.delete_source(*sorted(self.s3.buckets[SRC])[:150])
        self.assertEqual(self.run_at(T0 + WEEK, mb.Settings()), mb.EXIT_REFUSED)
        self.assertEqual(self.run_at(T0 + 2 * WEEK, mb.Settings()), 0)       # nothing new
        self.delete_source(*sorted(self.s3.buckets[SRC])[:400])
        self.assertEqual(self.run_at(T0 + 3 * WEEK, mb.Settings()), mb.EXIT_REFUSED)
        hold = self.doc()["hold"]
        self.assertTrue(hold["reason"].startswith("150 objects"))
        self.assertEqual(len(hold["events"]), 1)
        self.assertIn("400 objects", hold["events"][0])
        self.run_at(T0 + 4 * WEEK, mb.Settings())
        self.assertTrue(self.logged("1 later incident(s)"))

    # 4 — orphaned priors are reported, and removed only on a run allowed to prune.
    def test_orphaned_prior_is_reported_then_removed(self):
        self.s3.put(DST, mb.prior_key("blob-gone"), 7)
        self.assertEqual(self.run_at(T0 + WEEK, mb.Settings()), mb.EXIT_ORPHAN_PRIOR)
        self.assertTrue(self.logged("ORPHANED PRIOR _tenshu-mirror/prior/blob-gone"))
        self.assertTrue(mb.prior_key("blob-gone") in self.s3.buckets[DST])
        self.assertEqual(self.run_at(T0 + 2 * WEEK, on()), 0)
        self.assertFalse(mb.prior_key("blob-gone") in self.s3.buckets[DST])

    def test_prior_whose_delete_failed_becomes_an_orphan_and_is_cleaned_up(self):
        self.s3.put(SRC, "blob-007", 3)
        self.run_at(T0 + WEEK, on())
        self.delete_source("blob-007")
        self.s3.delete_errors.add(mb.prior_key("blob-007"))
        codes = self.weekly(2, 8, on())
        self.assertIn(mb.EXIT_DELETE_FAILED, codes)
        self.assertEqual(codes[-1], mb.EXIT_ORPHAN_PRIOR)
        self.s3.delete_errors.clear()
        self.assertEqual(self.run_at(T0 + 9 * WEEK, on()), 0)
        self.assertFalse(any(k.startswith(mb.PRIOR_PREFIX) for k in self.s3.buckets[DST]))

    def test_prior_of_a_live_or_tracked_key_is_not_an_orphan(self):
        self.s3.put(SRC, "blob-007", 3)
        self.run_at(T0 + WEEK, on())
        self.delete_source("blob-007")                   # tracked in the ledger, main still mirrored
        self.assertEqual(self.run_at(T0 + 2 * WEEK, on()), 0)
        self.assertFalse(self.logged("ORPHANED PRIOR"))

    # 5 — v1 entries (no per-entry baseline) are guarded by the peak like any other.
    def test_v1_entries_do_not_bypass_the_drain_guard(self):
        self.big()
        live = sorted(self.s3.buckets[SRC])
        v1 = {"version": 1, "updated": mb.utc(T0),
              "missing": {}}
        self.s3.buckets[DST][mb.LEDGER_KEY] = json.dumps(v1).encode()
        codes = self.drain(10, 0.08, on())
        self.assertIn(mb.EXIT_REFUSED, codes)
        self.assertLess(len(self.s3.deleted), 300)
        self.assertIn("peak", self.doc()["hold"]["reason"])


class ListingTest(unittest.TestCase):
    def test_paginates_every_page(self):
        s3 = FakeS3(page_size=7)
        for i in range(50):
            s3.put(SRC, f"blob-{i:03d}", i)
        self.assertEqual(len(mb.list_keys(s3, SRC)), 50)

    def test_multi_page_source_is_not_read_as_missing(self):
        s3 = FakeS3(page_size=7)
        for i in range(50):
            s3.put(SRC, f"blob-{i:03d}", 10)
        for w in range(0, 10):
            s3.server_now = T0 + w * WEEK
            self.assertEqual(mb.run(s3, SRC, DST, on(), T0 + w * WEEK, out=lambda _: None), 0)
        self.assertEqual(s3.deleted, [])
        self.assertEqual(json.loads(s3.buckets[DST][mb.LEDGER_KEY])["missing"], {})

    def test_truncated_page_without_token_raises(self):
        s3 = FakeS3(page_size=7)
        for i in range(10):
            s3.put(SRC, f"blob-{i:03d}", 1)
        s3.truncate_without_token.add(SRC)
        with self.assertRaises(mb.IncompleteListing):
            mb.list_keys(s3, SRC)


class IncompleteSourceTest(Base):
    def test_truncated_source_listing_aborts_before_any_write(self):
        self.delete_source("blob-001")
        self.weekly(1, 5, on())
        puts = list(self.s3.puts)
        self.s3.page_size = 10
        self.s3.truncate_without_token.add(SRC)
        with self.assertRaises(mb.IncompleteListing):
            self.run_at(T0 + 6 * WEEK, on())
        self.assertEqual(self.s3.deleted, [])
        self.assertEqual(self.s3.puts, puts)

    def test_key_omitted_by_listing_but_present_aborts_the_prune(self):
        self.s3.hide_from_list[SRC] = {"blob-005"}
        self.weekly(1, 5, on())
        with self.assertRaises(mb.IncompleteListing):
            self.run_at(T0 + 6 * WEEK, on())
        self.assertEqual(self.s3.deleted, [])
        self.assertIn("blob-005", self.s3.buckets[DST])

    def _hidden_first_listing_only(self):
        self.s3.hide_from_list[SRC] = {"blob-005"}
        self.weekly(1, 5, on())
        del self.s3.hide_from_list[SRC]
        # Hidden in the run's first listing, visible in the pre-delete re-listing.
        self.s3.hide_on_list_call[SRC] = (self.s3.list_calls[SRC] + 1, {"blob-005"})

    def test_key_omitted_by_first_listing_only_aborts_the_prune(self):
        self._hidden_first_listing_only()
        with self.assertRaises(mb.IncompleteListing):
            self.run_at(T0 + 6 * WEEK, on())
        self.assertEqual(self.s3.deleted, [])

    def test_fresh_listing_alone_catches_it_when_head_also_misses(self):
        self._hidden_first_listing_only()
        self.s3.head_error["blob-005"] = not_found()
        with self.assertRaises(mb.IncompleteListing):
            self.run_at(T0 + 6 * WEEK, on())
        self.assertEqual(self.s3.deleted, [])

    def test_head_errors_other_than_404_abort(self):
        for err in (S3Error("403", 403), S3Error("InternalError", 503), RuntimeError("timeout")):
            self.setUp()
            self.delete_source("blob-001")
            self.weekly(1, 5, on())
            self.s3.head_error["blob-001"] = err
            with self.assertRaises(mb.IncompleteListing, msg=repr(err)):
                self.run_at(T0 + 6 * WEEK, on())
            self.assertEqual(self.s3.deleted, [])

    def test_incomplete_copy_records_ledger_but_prunes_nothing(self):
        self.delete_source("blob-001")
        self.weekly(1, 5, on())
        self.s3.put(SRC, "blob-new", 10)
        real = self.s3.copy_object

        def failing(**kw):
            raise RuntimeError("503")
        self.s3.copy_object = failing
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on()), mb.EXIT_COPY_INCOMPLETE)
        self.s3.copy_object = real
        self.assertEqual(self.s3.deleted, [])
        self.assertEqual(self.ledger()["blob-001"]["sightings"], 6)

    def test_partial_delete_failure_is_exit_7_and_stays_in_the_ledger(self):
        self.delete_source("blob-001", "blob-002")
        self.weekly(1, 5, on())
        self.s3.delete_errors.add("blob-002")
        self.assertEqual(self.run_at(T0 + 6 * WEEK, on()), mb.EXIT_DELETE_FAILED)
        self.assertEqual(self.s3.deleted, ["blob-001"])
        self.assertEqual(list(self.ledger()), ["blob-002"])
        self.assertTrue(self.logged("FAILED delete blob-002: InternalError"))


class NotFoundTest(unittest.TestCase):
    def test_only_a_definite_404_is_not_found(self):
        self.assertTrue(mb.is_not_found(S3Error("404")))
        self.assertTrue(mb.is_not_found(S3Error("NoSuchKey")))
        self.assertTrue(mb.is_not_found(S3Error(None, 404)))   # code missing, status 404
        self.assertFalse(mb.is_not_found(S3Error("403", 403)))
        self.assertFalse(mb.is_not_found(S3Error("SlowDown", 503)))
        self.assertFalse(mb.is_not_found(RuntimeError("no response attribute")))
        e = RuntimeError("odd")
        e.response = None
        self.assertFalse(mb.is_not_found(e))


class SettingsTest(unittest.TestCase):
    def test_off_by_default(self):
        s = mb.Settings.from_env({})
        self.assertFalse(s.prune)
        self.assertIsNone(s.error)
        self.assertEqual((s.retention_days, s.sightings_required), (30, 6))
        self.assertIsNone(s.max_missing_gb)

    def test_env_and_tfvars(self):
        s = mb.Settings.from_env({"MIRROR_PRUNE": "on", "MIRROR_PRUNE_MAX_MISSING_GB": "2"}, tf_retention="45")
        self.assertTrue(s.prune)
        self.assertEqual((s.retention_days, s.max_missing_gb), (45, 2.0))
        self.assertEqual(mb.Settings.from_env({"MIRROR_RETENTION_DAYS": "60"}, "45").retention_days, 60)

    def test_bad_values_disable_pruning_without_raising(self):
        for env in ({"MIRROR_PRUNE": "yes"}, {"MIRROR_RETENTION_DAYS": "3"}, {"MIRROR_RETENTION_DAYS": "x"},
                    {"MIRROR_RETENTION_DAYS": "30.5"}, {"MIRROR_RETENTION_DAYS": "99999999999999999999"},
                    {"MIRROR_RETENTION_DAYS": "4000"},
                    {"MIRROR_PRUNE": "on", "MIRROR_PRUNE_MAX_NEW_MISSING_FRACTION": "0"},
                    {"MIRROR_PRUNE": "on", "MIRROR_PRUNE_MAX_NEW_MISSING_FRACTION": "1"},
                    {"MIRROR_PRUNE": "on", "MIRROR_PRUNE_MAX_TOTAL_MISSING_FRACTION": "nan"},
                    {"MIRROR_PRUNE": "on", "MIRROR_PRUNE_MAX_MISSING_GB": "inf"},
                    {"MIRROR_PRUNE": "on", "MIRROR_PRUNE_MAX_MISSING_GB": "-1"}):
            s = mb.Settings.from_env(env)
            self.assertFalse(s.prune, env)
            self.assertTrue(s.error, env)

    def test_bad_setting_still_copies_and_exits_2(self):
        s3 = FakeS3()
        s3.put(SRC, "blob-a", 10)
        bad = mb.Settings.from_env({"MIRROR_PRUNE": "yes"})
        s3.server_now = T0
        self.assertEqual(mb.run(s3, SRC, DST, bad, T0, out=lambda _: None), mb.EXIT_REFUSED)
        self.assertIn("blob-a", s3.buckets[DST])

    def test_tfvar_number(self):
        self.assertEqual(mb.tfvar_number("blob_mirror_retention_days = 45\n", "blob_mirror_retention_days"), "45")
        self.assertIsNone(mb.tfvar_number("region = \"fr-par\"\n", "blob_mirror_retention_days"))


class LockTest(unittest.TestCase):
    def test_second_lock_is_refused(self):
        import tempfile
        path = os.path.join(tempfile.mkdtemp(), "l")
        a = mb.take_lock(path)
        self.assertIsNotNone(a)
        self.assertIsNone(mb.take_lock(path))
        a.close()
        b = mb.take_lock(path)
        self.assertIsNotNone(b)
        b.close()

class StorageClassTest(Base):
    def test_every_copy_into_the_mirror_is_one_zone(self):
        user = [k for k in self.s3.buckets[DST] if not k.startswith(mb.RESERVED_PREFIX)]
        self.assertEqual(len(user), 100)
        self.assertEqual({self.s3.storage_class(DST, k) for k in user}, {"ONEZONE_IA"})
        self.assertTrue(all(c[4] == "ONEZONE_IA" for c in self.s3.copies))
        # The ledger is the one object read every run; it stays STANDARD.
        self.assertEqual(self.s3.storage_class(DST, mb.LEDGER_KEY), "STANDARD")

    def test_a_prior_is_one_zone_too(self):
        self.s3.put(SRC, "blob-001", 1200)            # a size change: the mirror keeps its copy as prior
        self.assertEqual(self.run_at(T0 + WEEK, mb.Settings()), 0)
        self.assertEqual(self.s3.storage_class(DST, mb.prior_key("blob-001")), "ONEZONE_IA")
        self.assertEqual(self.s3.storage_class(DST, "blob-001"), "ONEZONE_IA")

    def test_class_comes_from_env_and_a_bad_one_refuses_the_prune_not_the_copy(self):
        self.assertEqual(mb.Settings.from_env({}).storage_class, "ONEZONE_IA")
        self.assertEqual(mb.Settings.from_env({"MIRROR_STORAGE_CLASS": "standard"}).storage_class, "STANDARD")
        for bad in ("GLACIER", "ONEZONE", "one zone"):
            s = mb.Settings.from_env({"MIRROR_STORAGE_CLASS": bad, "MIRROR_PRUNE": "on"})
            self.assertFalse(s.prune, bad)
            self.assertIn("MIRROR_STORAGE_CLASS", s.error)
            self.assertEqual(s.storage_class, "ONEZONE_IA")
        # A bad prune setting does not lose a good class.
        s = mb.Settings.from_env({"MIRROR_STORAGE_CLASS": "STANDARD", "MIRROR_PRUNE": "yes"})
        self.assertEqual((s.storage_class, s.prune), ("STANDARD", False))

    def test_an_ignored_class_warns_but_does_not_fail_the_run(self):
        self.s3.ignore_storage_class = True
        self.s3.put(SRC, "blob-new", 10)
        self.assertEqual(self.run_at(T0 + WEEK, mb.Settings()), 0)
        self.assertTrue(self.logged("NOT stored as ONEZONE_IA"), self.log)


class MigrationTest(Base):
    """--migrate-storage-class: a mirror filled before the class existed (STANDARD), moved in place."""

    def setUp(self):
        self.s3 = FakeS3()
        self.log = []
        self.pin_server = False
        for i in range(20):
            self.s3.put(SRC, f"blob-{i:03d}", 100)
        legacy = mb.Settings(storage_class="STANDARD")
        self.assertEqual(self.run_at(T0, legacy), 0)
        del self.s3.buckets[SRC]["blob-000"]          # so the ledger has an entry worth protecting
        self.s3.put(SRC, "blob-001", 150)             # and a prior exists
        self.run_at(T0 + WEEK, legacy)
        self.ledger_bytes = self.s3.buckets[DST][mb.LEDGER_KEY]
        self.assertIn("blob-000", json.loads(self.ledger_bytes)["missing"])
        self.assertIn(mb.prior_key("blob-001"), self.s3.buckets[DST])
        self.s3.copies.clear()

    def migrate(self, apply):
        self.log = []
        return mb.migrate_storage_class(self.s3, DST, "ONEZONE_IA", apply=apply, out=self.log.append)

    def classes(self):
        return {k: self.s3.storage_class(DST, k) for k in self.s3.buckets[DST]}

    def test_dry_run_is_the_default_and_changes_nothing(self):
        before, classes = dict(self.s3.buckets[DST]), self.classes()
        self.assertEqual(mb.migrate_storage_class(self.s3, DST, "ONEZONE_IA", out=self.log.append), 0)
        self.assertEqual((self.s3.buckets[DST], self.classes()), (before, classes))
        self.assertEqual(self.s3.copies, [])
        self.assertTrue(self.logged("to move to ONEZONE_IA: 20 object(s)"), self.log)

    def test_apply_moves_every_object_and_never_the_reserved_prefix(self):
        self.assertEqual(self.migrate(True), 0)
        for k, c in self.classes().items():
            self.assertEqual(c, "STANDARD" if k.startswith(mb.RESERVED_PREFIX) else "ONEZONE_IA", k)
        self.assertFalse(any(c[3].startswith(mb.RESERVED_PREFIX) for c in self.s3.copies))
        self.assertEqual(self.s3.buckets[DST][mb.LEDGER_KEY], self.ledger_bytes)
        # Self-copies only: nothing read from or written to the source.
        self.assertTrue(all(c[0] == DST and c[1] == c[3] for c in self.s3.copies))
        # The mirror-only key (missing from the source) is moved too, not dropped.
        self.assertEqual(self.s3.storage_class(DST, "blob-000"), "ONEZONE_IA")

    def test_idempotent(self):
        self.migrate(True)
        n = len(self.s3.copies)
        self.assertEqual(self.migrate(True), 0)
        self.assertEqual(len(self.s3.copies), n)
        self.assertTrue(self.logged("to move to ONEZONE_IA: 0 object(s)"))

    def test_a_class_the_endpoint_drops_is_a_failure(self):
        self.s3.ignore_storage_class = True
        self.assertEqual(self.migrate(True), mb.EXIT_COPY_INCOMPLETE)
        self.assertTrue(self.logged("accepted the copy but not the class"))

    def test_a_failed_copy_is_reported_and_retried_by_the_next_run(self):
        real = self.s3.copy_object

        def flaky(**kw):
            if kw["Key"] == "blob-007":
                raise RuntimeError("503 SlowDown")
            return real(**kw)
        self.s3.copy_object = flaky
        self.assertEqual(self.migrate(True), mb.EXIT_COPY_INCOMPLETE)
        self.assertEqual(self.s3.storage_class(DST, "blob-007"), "STANDARD")
        self.assertIn("blob-007", self.s3.buckets[DST])       # the original survives a failed self-copy
        self.s3.copy_object = real
        self.s3.copies.clear()
        self.assertEqual(self.migrate(True), 0)
        self.assertEqual([c[3] for c in self.s3.copies], ["blob-007"])

    def test_a_ledger_changed_underneath_is_reported(self):
        real = self.s3.copy_object

        def racing(**kw):
            self.s3.buckets[DST][mb.LEDGER_KEY] = b'{"changed": true}'
            return real(**kw)
        self.s3.copy_object = racing
        self.assertEqual(self.migrate(True), mb.EXIT_LEDGER)
        self.assertTrue(self.logged("changed during the migration"))

    def test_the_weekly_run_after_a_migration_copies_nothing(self):
        self.migrate(True)
        self.s3.copies.clear()
        self.assertEqual(self.run_at(T0 + 2 * WEEK, mb.Settings()), 0)
        self.assertEqual(self.s3.copies, [])
        self.assertIn("  to copy: 0", self.log)


class SecondRegionTest(unittest.TestCase):
    """--target dr: two fakes stand in for fr-par and nl-ams. The source and the same-region mirror are
    in `self.par`, the second-region mirror in `self.ams`."""

    def setUp(self):
        self.par, self.ams = FakeS3(), FakeS3()
        for i in range(100):
            self.par.put(SRC, f"blob-{i:03d}", 1000)
        self.log = []

    def dr(self, now, settings, **kw):
        self.log = []
        self.par.server_now = now
        return mb.run(self.par, SRC, DST, settings, now, out=self.log.append, dst_s3=self.ams, **kw)

    def primary(self, now, settings):
        self.par.server_now = now
        return mb.run(self.par, SRC, DST, settings, now, out=lambda _: None)

    def test_streams_every_object_without_reading_one_whole(self):
        self.assertEqual(self.dr(T0, mb.Settings()), 0)
        user = {k for k in self.ams.buckets[DST] if not k.startswith(mb.RESERVED_PREFIX)}
        self.assertEqual(user, set(self.par.buckets[SRC]))
        self.assertTrue(all(self.ams.buckets[DST][k] == self.par.buckets[SRC][k] for k in user))
        self.assertEqual({self.ams.storage_class(DST, k) for k in user}, {"ONEZONE_IA"})
        # No server-side copy anywhere (it cannot cross regions), and no whole-object read.
        self.assertEqual((self.par.copies, self.ams.copies), ([], []))
        self.assertLessEqual(self.par.max_read, UPLOAD_CHUNK)
        self.assertTrue(all(b.closed for b in self.par.get_bodies))
        # Nothing landed in the source region's mirror.
        self.assertEqual(self.par.buckets[DST], {})
        self.assertTrue(any("streamed through this box" in line for line in self.log))

    def test_transfer_config_is_passed_through(self):
        seen = []
        real = self.ams.upload_fileobj

        def spy(Fileobj, Bucket, Key, ExtraArgs=None, Config=None):
            seen.append(Config)
            return real(Fileobj, Bucket, Key, ExtraArgs=ExtraArgs, Config=Config)
        self.ams.upload_fileobj = spy
        marker = object()
        self.dr(T0, mb.Settings(), transfer_config=marker)
        self.assertEqual(set(map(id, seen)), {id(marker)})

    def test_a_size_change_saves_the_prior_in_the_second_region_and_streams_the_new_bytes(self):
        self.dr(T0, mb.Settings())
        self.par.put(SRC, "blob-001", 1200)
        self.assertEqual(self.dr(T0 + WEEK, mb.Settings()), 0)
        self.assertEqual(len(self.ams.buckets[DST][mb.prior_key("blob-001")]), 1000)
        self.assertEqual(len(self.ams.buckets[DST]["blob-001"]), 1200)
        # The prior is a server-side copy inside nl-ams; nothing in fr-par was written.
        self.assertTrue(all(c[0] == DST and c[2] == DST for c in self.ams.copies))
        self.assertEqual(self.par.copies, [])

    def test_each_mirror_keeps_its_own_ledger(self):
        self.primary(T0, mb.Settings())
        self.dr(T0, mb.Settings())
        del self.par.buckets[SRC]["blob-001"]
        self.primary(T0 + WEEK, mb.Settings())
        self.dr(T0 + 2 * WEEK, mb.Settings())
        par = json.loads(self.par.buckets[DST][mb.LEDGER_KEY])["missing"]
        ams = json.loads(self.ams.buckets[DST][mb.LEDGER_KEY])["missing"]
        self.assertEqual(par["blob-001"]["since"], "2026-10-11T04:40:00Z")
        self.assertEqual(ams["blob-001"]["since"], "2026-10-18T04:40:00Z")

    def test_same_pruning_semantics_window_and_sightings_then_delete_only_in_the_second_region(self):
        self.primary(T0, mb.Settings())
        self.dr(T0, mb.Settings())
        del self.par.buckets[SRC]["blob-001"]
        pruned_at = None
        for w in range(1, 9):
            self.assertEqual(self.dr(T0 + w * WEEK, on()), 0, self.log)
            if pruned_at is None and "blob-001" not in self.ams.buckets[DST]:
                pruned_at = w
        self.assertEqual(pruned_at, 6)                  # first seen week 1; window 30 d AND 6 sightings
        self.assertEqual(self.ams.deleted, ["blob-001"])
        self.assertEqual(self.par.deleted, [])
        self.assertIn("blob-001", self.par.buckets[DST])   # the same-region mirror is untouched

    def test_breaker_guards_the_second_region_too(self):
        self.dr(T0, mb.Settings())
        self.par.buckets[SRC] = {}
        self.assertEqual(self.dr(T0 + WEEK, on()), mb.EXIT_REFUSED)
        self.assertEqual(self.ams.deleted, [])
        self.assertTrue(json.loads(self.ams.buckets[DST][mb.LEDGER_KEY])["hold"])

    def test_a_failed_stream_is_copy_incomplete_and_prunes_nothing(self):
        self.dr(T0, mb.Settings())
        del self.par.buckets[SRC]["blob-001"]
        for w in range(1, 6):
            self.dr(T0 + w * WEEK, on())
        self.par.put(SRC, "blob-new", 10)

        def fail(*a, **kw):
            raise RuntimeError("connection reset")
        self.ams.upload_fileobj = fail
        self.assertEqual(self.dr(T0 + 6 * WEEK, on()), mb.EXIT_COPY_INCOMPLETE)
        self.assertEqual(self.ams.deleted, [])
        self.assertIn("blob-001", self.ams.buckets[DST])
        self.assertTrue(all(b.closed for b in self.par.get_bodies))   # closed even when the upload fails

    def test_a_reserved_key_in_the_source_never_reaches_the_second_region_ledger(self):
        self.par.put(SRC, mb.LEDGER_KEY, 5)
        self.dr(T0, mb.Settings())
        self.assertEqual(json.loads(self.ams.buckets[DST][mb.LEDGER_KEY])["version"], mb.LEDGER_VERSION)

    def test_prune_switch_is_per_target(self):
        both = {"MIRROR_PRUNE": "on"}
        self.assertTrue(mb.Settings.from_env(both).prune)
        self.assertFalse(mb.Settings.from_env(both, prune_var="MIRROR_DR_PRUNE").prune)
        self.assertTrue(mb.Settings.from_env({"MIRROR_DR_PRUNE": "on"}, prune_var="MIRROR_DR_PRUNE").prune)
        bad = mb.Settings.from_env({"MIRROR_DR_PRUNE": "yes"}, prune_var="MIRROR_DR_PRUNE")
        self.assertFalse(bad.prune)
        self.assertIn("MIRROR_DR_PRUNE", bad.error)

if __name__ == "__main__":
    unittest.main()
