# iOS shared replica: SQLite vs `FileObjectStore`, measured

**Status:** a **measurement, not a design** (2026-10-02, device pass the same evening). It answers
the two questions the read-only investigation behind
[`ios-scheduler-lease.md`](ios-scheduler-lease.md) §15.2 said could kill a SQLite-backed store on
iOS. It recommends nothing beyond what the numbers support. Code: `spike-ios-store/` (throwaway,
opt-in, in no build or gate) — ⚠ the module was **deleted on 2026-11-05** after it stopped compiling against the current store; its last version is in git at `066f6f363` (last changed in `f73684336`), so every `spike-ios-store/…` path and command below refers to that tree.

## 1. The question

Should the shared on-device replica on iOS be backed by SQLite instead of the one-file-per-object
`FileObjectStore`? Two things could have killed it:

1. **The driver.** Does `androidx.sqlite:sqlite-bundled` 2.7.1, which the Android/JVM
   `SqliteObjectStore` already uses, resolve, compile, link and run for the iOS targets of this
   build (Kotlin 2.4.10)? Its metadata lists `iosArm64` and `iosSimulatorArm64` but not `iosX64`,
   which `:core-common` declares. Nothing here had resolved it for iOS before.
2. **Point reads.** Today a cache miss is one file read; with SQLite it is a query. The Android
   store's comments record a 15–18 s Timeline Home attributed to read cost
   (`SqliteObjectStore.kt`, the comment on its row cache).

## 2. Answer to (1): it builds and runs, unchanged

- `sqlite-bundled` 2.7.1 resolved, compiled, linked into a static release framework and ran on the
  simulator and on the phone with **no workaround and no version change**. The obvious alternatives
  (a newer `androidx.sqlite`, `libsqlite3` through a cinterop) were therefore not needed and not
  tried.
- **`iosX64` was not a blocker.** The spike module declares only `iosArm64` and
  `iosSimulatorArm64` and depends on `:core-common`, which declares three iOS targets; a consumer
  may declare a subset of its dependency's targets. `:core-common` was not touched. ⚠ This says
  nothing about adding the driver to `:core-common` itself: there, `iosX64` would need the
  dependency scoped to the two published targets (or `iosX64` dropped), which was not tried.
- The bundled build reports `page_size` 4096; WAL, `synchronous`, `busy_timeout`, `data_version`
  and `EXPLAIN QUERY PLAN` all behaved as on the JVM.

## 3. Method

| | |
|---|---|
| Device | iPhone 17e (`iPhone18,5`), iOS 26.6.1, 256 GB |
| Simulator | iPhone 16, iOS 18.5, on the Apple-silicon dev Mac (under unrelated build load) |
| Build | Kotlin 2.4.10, **release (optimised) K/N static framework** (`linkReleaseFramework…`), Xcode 27.0, `Release` configuration |
| App | `app.tenshu.storebench.ios`: own bundle id, **no App Group, no Keychain group, no entitlements file**, writes only under its own `Documents/bench`. The only app installed; still installed (§7) |
| Stores | The real `FileObjectStore` (`:core-common`, `migrations = null`), and a minimal SQLite store written for the spike: the Android table `objects(hash INTEGER PRIMARY KEY, type, data)`, WAL, `synchronous=NORMAL`, `busy_timeout` 5000, prepared statements reused, **no row cache and no resident hash set**, default page cache (2 MiB), no mmap. `sqlite_idx` adds `CREATE INDEX objects_type ON objects(type)`, which the Android store's comments describe and its schema does not have |
| Codec | Both stores encode/decode with `DbEntryCodec` (the SQLite side uses a verbatim copy, since the original is `internal`) |
| Process model | **One process per (phase, store)**, launched by `devicectl --console` / `simctl launch --console`. "New process" figures are a process that has just started; the OS buffer cache was **not** controlled (§6) |
| Repeats | 3 cycles of load → reads → concurrency → mutate per store; the read launch twice per cycle (n = 6 for reads, n = 3 otherwise); within a launch, latencies over 1,000–10,000 operations |

**Data set: 150,000 rows, deterministic (seed fixed, row `i` a pure function of `i`).**

| Share | Type (from the replica census, `ios-route1-replica-scope.md` §1) | Encoded size |
|---|---|---|
| 25.0% / 25.0% | `dataId/string` / `resolvedDataId` | 71–206 B, uniform (`tech-debt.md`, the iOS store entry) |
| 13.0% / 13.0% | `hlc` / `sync/metadata` | 71–206 B |
| 9.5% / 9.5% / 3.0% | `health/workout` / `…RollupAttribution` / `health/dailyActivity` | 71–206 B |
| 1.0% | `bench/scanTarget` (the type the scan asks for) | 71–206 B |
| 1.0% | `file/thumbnail` | 25–35 KiB of random bytes (`bounded-store-cache.md`: ~99% small, thumbnails eager at 25–35 KiB) |

63.6 MiB of encoded payload; 1,500 large rows. Bulk load: `FileObjectStore.storeObject` per row
(it has no batching); SQLite one transaction per 1,000 rows at `synchronous=NORMAL`, then
`wal_checkpoint(TRUNCATE)`. Row generation is outside every timed region.

## 4. Results

Median (min–max). **Device numbers are the answer; simulator numbers are not** (shown for the
record; the simulator ran on a loaded Mac and its file system is the host's). Device and simulator
are the same harness version (pass 2, §7).

| Measurement | Device: File | Device: SQLite | Device: SQLite+idx | Sim: File | Sim: SQLite | Sim: SQLite+idx |
|---|---|---|---|---|---|---|
| Bulk load 150k rows | **111.6 s** (99.4–114.6) | **4.53 s** (4.14–4.70) | 5.92 s (5.41–6.35) | 63.1 s | 11.1 s | 14.3 s |
| On-disk, allocated (logical) | **627 MiB** (63.6), 150,001 files | **80.9 MiB** (73.2) | 80.3 MiB (77.7) | 627 MiB | 80.6 MiB | 80.7 MiB |
| Open, new process, to first answered read | 205 ms (135–276) + 1.7 ms | 4.4 ms + 0.13 ms | 6.1 ms + 0.40 ms | 122 ms | 0.9 ms | 1.0 ms |
| Open, first launch after other work | 349 ms (344–383) | 5.9 ms | 6.5 ms | 617 ms | 1.5 ms | 2.0 ms |
| Enumerate all 150k hashes, new process | 131 ms (126–134)¹ | 284 ms (276–293) | 113 ms (108–125)² | 132 ms | 626 ms | 169 ms |
| Enumerate all hashes, warm | —³ | 27 ms (25–33) | 19 ms (18–32) | — | 34 ms | 17 ms |
| 10k random point reads, new process, rows **not read since the load** | **4,524 ms** (4,399–4,551), p50 460 µs, n=3 | not isolated⁴ | **233 ms** (231–293), mean 21–26 µs, n=3 | 2,770 ms | — | 365 ms |
| 10k random point reads, new process, rows **read by an earlier process** | **2,252 ms** (2,171–2,307), p50 228 µs, n=3 | **70 ms** (60–79), p50 3.8 µs, n=6 | 61 ms (61–111), n=3 | 502 ms (474–7,416) | 83 ms | 58 ms |
| Same 10k again, same process | 13.9 ms, p50 0.04 µs (its row cache) | 48 ms, p50 3.8 µs | 48 ms, p50 3.8 µs | 8.1 ms | 48 ms | 48 ms |
| Same 10k again, file store's row cache dropped | 2,184 ms (2,084–2,265), p50 212 µs | (no row cache) | (no row cache) | 408 ms | — | — |
| …of which the 97 large rows, mean | 187 µs | 66 µs | 66 µs | 52 µs | 66 µs | 63 µs |
| 10k reads re-**preparing** the statement each time | — | 60 ms, p50 5.0 µs | 59 ms, p50 5.0 µs | — | 63 ms | 58 ms |
| 1,000 reads of absent rows | 0.45 ms, p50 0.33 µs (index set) | 2.7 ms, p50 2.65 µs | 8.4 ms (2.6–19), p50 2.7 µs | 0.22 ms | 2.3 ms | 10.0 ms |
| Scan by type, 1,500 rows (1%) | **66.2 s** (64.6–66.9) | 16 ms (15–18), full scan | **6.6 ms** (3.9–10), index | 28.0 s | 15 ms | 6.5 ms |
| Foreground rehydrate with ~9,900 resident rows | 4,971 ms (4,907–5,045) | — | — | 3,582 ms | — | — |
| 1,000 single writes, `NORMAL` (no flush per commit) | — | p50 **101 µs** first 1,000⁵, **19 µs** last 1,000 | 94 µs / 26 µs | — | 146 / 66 µs | 211 / 125 µs |
| 1,000 single writes, `FULL` (fsync per commit) | — | p50 **61 µs** (47–64), total 147 ms | 43 µs, 128 ms | — | 107 µs | 111 µs |
| 1,000 single writes, `FULL` + `fullfsync` (F_FULLFSYNC per commit) | — | p50 3,847 µs, total 3.68 s | 3,858 µs | — | 4,817 µs | 5,028 µs |
| 1,000 single writes, `storeObject` (synchronous, atomic) | p50 **926 µs** (925–1,015), total 1,012 ms | — | — | 320 µs | — | — |
| Calibration, 140-B file: `NSData.writeToFile(atomically:)` / temp+rename / +`fsync` / +`F_FULLFSYNC` | 732 / 419 / 431 / 4,943 µs p50 | | | 264 / 179 / 202 / 5,249 µs | | |
| Delete 10% (15,000 rows) | **4,053 ms** (4,019–4,063) | **389 ms** (385–389), txn per 1,000 | 556 ms | 5,375 ms | 898 ms | 1,448 ms |
| Full wipe | **40.3 s** (39.1–41.6), `wipeAllObjects` | **278 ms** `DELETE FROM`; 0.27 ms closing + unlinking | 286 ms | 43.9 s | 818 ms | 910 ms |
| `PRAGMA data_version`, 2nd connection, after a write / idle | — | 2.46 / 0.67 µs p50; change seen 1000/1000, stable when idle 1000/1000 | 2.50 / 0.67 µs | — | 2.83 / 0.71 µs | 2.92 / 0.71 µs |
| Read on a 2nd connection right after a commit | — | p50 2.1 µs; saw it on the first try 1000/1000 | same | — | 2.7 µs | 2.8 µs |
| Reader (2nd connection) during a 3.09 s write transaction | — | p50 4.1 µs, max 3.9 ms; **never blocked**, read the pre-transaction snapshot (0 uncommitted reads of ~107k), saw the commit after | p50 3.4 µs, max 3.3 ms | — | p50 2.8 µs | p50 2.9 µs |
| Second writer during it, `busy_timeout=0` / 20 s | — | `SQLITE_BUSY` in 0.1 ms / **blocked 1,606 ms**, i.e. until the commit | 0.08 ms / 1,601 ms | — | 0.25 ms / 1,585 ms | 0.10 / 1,593 ms |
| Peak memory (`ru_maxrss`), read launch / load launch | 271 / 271 MiB⁶ | 46 / 42 MiB | 46 / 42 MiB | 190 / 272 | 139 / 135 | 138 / 135 |

¹ The file store's `open` *is* its enumeration; this is `rehydrateFromDisk` straight after it, so
a second listing of the directory. ² `SELECT hash` uses the type index as a covering index.
³ The warm file figure is the rehydrate row, which also re-reads resident rows.
⁴ The read launch for plain `sqlite` always followed a launch that had scanned the whole table,
so its pages had just been read by another process; `sqlite_idx`'s preceding scan used the index,
so its first read launch per cycle is the "not read since the load" case.
⁵ The first 1,000 writes after the load's `wal_checkpoint(TRUNCATE)` extend a WAL that starts
empty; once the log has reached its auto-checkpoint size and been reset, the same writes cost a
fifth. This is an inference from the ordering, not separately measured; `FULL` was measured in
between and is therefore a mid-growth figure.
⁶ Inflated by the harness: `NSData` results autorelease, and the benchmark thread drained no
autorelease pool in this pass. The file store also keeps every small row it writes or reads in its
`HashMap`, which is its design. Pass 3 drains a pool per chunk (simulator only, §7).

### 4.1 Pass 3, simulator only: the OS cache dropped before each read launch

Not the answer either (the simulator's file system is the Mac's SSD and APFS, not the phone's), but
it is the only flash-cold measurement there is, and it **narrows the read gap a lot**. n = 6 read
launches, 3 cycles. The eviction launch found 0–100% of each store's pages resident and left **0**
resident every time (`mincore`); it cannot evict the directory or vnode cache, which favours the
file store.

| Measurement (simulator, pass 3) | File | SQLite | SQLite+idx |
|---|---|---|---|
| 10k random point reads, new process, after eviction | **1,399 ms** (1,255–4,605), p50 133 µs | **418 ms** (341–2,924), p50 3.9 µs, mean 38 µs | 424 ms (355–2,659) |
| Type scan 1% after those reads | 21.1 s | 49 ms | 9.2 ms |
| Single writes p50, after 3,000 warm-up writes: `NORMAL` / `FULL` / `NORMAL` again / `FULL` again | — | 21 / 49 / 15 / 42 µs | 44 / 73 / 37 / 83 µs |
| Footprint (`phys_footprint`) after open → after the 10k reads | 45 → 66 MiB | 15 → 27 MiB | 14 → 26 MiB |
| Footprint at the end of the bulk load | 193 MiB | 28 MiB | 28 MiB |

So with a flash-cold cache the simulator shows SQLite **~3×** ahead on 10k point reads, against
20–30× on the device with the OS cache in whatever state the previous launch left it. A SQLite
point read from a cold cache pulls a whole OS page; the file store's rows are one page each, so
the two converge as the cache goes cold. With warm-up done first, `FULL` costs about 2.5–3× `NORMAL`
and the first-versus-last ordering effect of pass 2 (footnote 5) is gone, which supports that
footnote's explanation.

## 5. The numbers that matter for the decision

1. **Point reads are the opposite of the fear.** From a process that has just started, 10,000
   random reads cost **2.2–4.5 s** on the file store (p50 228–460 µs per read: an open, a read and a
   close of one file each) and **60–290 ms** on SQLite (p50 3.8–4.3 µs). Even with the file store's
   row cache dropped and its files in the OS cache, a file read is 212 µs; a SQLite read with the
   statement re-prepared every time is 5 µs. Only a hit in the file store's resident `HashMap`
   (0.04 µs) or its resident hash set for an absent row (0.33 µs vs 2.65 µs) is faster, and a SQLite
   store can keep the same two structures if it wants them. The Android 15–18 s Timeline Home was
   ~10k thin reads; on this phone 10k SQLite reads including decode take 60–290 ms, so **store-level
   read cost cannot by itself account for that figure on iOS** (inferred; Timeline was not run).
   ⚠ The one flash-cold measurement (simulator, §4.1) shrinks the gap to ~3×: still SQLite's way,
   but not by an order of magnitude. The device equivalent was not measured.
2. **The file store's bulk operations are where the time is.** Wiping 150k rows takes 40 s, a full
   type scan 66 s, a 10% delete 4 s, a bulk load 112 s; SQLite does each in 0.3 s, 0.016 s, 0.4 s
   and 4.5 s. The 40 s wipe is the number §15.1 finding 2 of `ios-scheduler-lease.md` is about (a
   wipe under one lock hold, below a one-second ceiling): measured, it is not close.
3. **The engine already provides the cross-connection behaviour §5 of the lease doc was building.**
   A reader is never blocked by a writer and reads a consistent snapshot; a commit is visible to the
   next read on another connection, every time; `data_version` detects every foreign commit and
   costs 0.7–2.5 µs. Durable single writes at fsync-per-commit (`FULL`) are 43–61 µs against the
   file store's 926 µs, and the calibration places the file store's write in the same durability
   class as `FULL`, not `F_FULLFSYNC` (`NSData` atomic 732 µs ≈ temp+rename 419–431 µs ≪
   `F_FULLFSYNC` 4,943 µs; inferred from timing, Apple does not document it).

The type index costs +31% bulk-load time and +6% file size, makes a 1% type scan 2.4× faster and a
cold hash enumeration 2.5× faster, and leaves point reads and writes unchanged.

## 6. What could not be measured

- **Reads from flash with the OS cache dropped, on the device.** The "not read since the load" row
  is the coldest state the device pass reached; the bulk write may have left those pages cached.
  Pass 3 adds an eviction launch (`msync(MS_INVALIDATE)` per file, checked with `mincore`, which
  dropped 100% of resident pages on the simulator) before every read launch. **It did not run on the
  phone** (§7). A reboot would be the clean way and needs the maintainer to unlock the phone.
- **A second process.** Cross-connection results use a second connection on another thread in the
  same process. Cross-process locking, visibility across processes, and the suspended-lock
  termination (`0xdead10cc`) that `ios-scheduler-lease.md` §15.2 says SQLite does not remove were
  not measured.
- **The App Group container.** The benchmark wrote to its own container; the group container's file
  protection class and location were not exercised (deliberately: no entitlement on the bench app).
- **Real data, a real app, a real working set.** The rows are synthetic (mix in §3); no Tenshu
  service, schema migration, listener or `DatabaseImpl` ran over either store.
- **A production-shaped SQLite store.** No resident hash set, no row cache, no writer thread, no
  migration seam. The numbers bound the engine, not a finished `ObjectStore`.
- **Thermal state and background execution.** Every launch ran in the foreground with the screen
  kept on.

⚠ **Two harness gaps, found in review, not fixed** (the spike was merged as measured):
- `run-bench.sh` ignores a failed `devicectl`/`simctl` launch, and a Kotlin phase that fails prints
  `BENCH_FAILED` and exits 0, so `summarise.py` can average an incomplete sample and the script
  still succeeds. The device numbers in §4 were checked against the console log by hand; a re-run
  should make a failed phase fail the run.
- The eviction step does not check the return codes of `mincore` and `msync(MS_INVALIDATE)`. A
  failed `mincore` leaves a zeroed vector that reads as "0 resident pages". The simulator's
  "dropped 100%" above therefore holds only if those calls succeeded, which was not verified.

## 7. Runs, and an open item on the phone

- **Pass 1** (device): found a harness bug — `scanRowsOfType` with `matchLimit = Int.MAX_VALUE`
  makes `FileObjectStore` pre-size an `ArrayList` to it, killing the process. Its numbers are
  discarded. (A real caller passes a bounded limit; no product change is implied.)
- **Pass 2** (device + simulator, 51 launches each, all completed): the table above.
- **Pass 3** (eviction before each read launch, `NORMAL`/`FULL` interleaved after 3,000 warm-up
  writes, `task_info` footprint, autorelease pools): completed on the simulator only. ⚠ On the
  device, the phone dropped off (`devicectl`: *"The connection was invalidated"*, then *"not able to
  fulfill the requested usage assertion"*) **during the first eviction launch against the SQLite
  directory**, after three eviction launches against the file store had succeeded, and was still
  `unavailable` ~17 h later. Whether the eviction caused it (a crash or a reboot) or the phone simply
  disconnected is **not known**. The maintainer should look at the phone, and when it is back, at
  `xcrun devicectl device info files --device <udid> --domain-type systemCrashLogs` for a panic or a
  `StoreBench` report, before anyone re-runs pass 3 there.
  ⚠⚠ **It happened again, twice, on 2026-10-04**, by a script bug, not on purpose: each time an
  `evict` of a SQLite directory completed and the read launch after it dropped the phone
  ([`ios-shared-store-sqlite-q0-results.md`](ios-shared-store-sqlite-q0-results.md) §5). Three of
  three, so the eviction is the cause in every way but the mechanism. `run-bench.sh` now refuses
  the `evict` plan on a device.
- ⚠ Since 2026-10-04 the SQLite stores in `spike-ios-store/` run on the store's own driver and the
  vendored SQLite 3.53.4, not on `androidx.sqlite`; the numbers above cannot be reproduced from the
  current code. The re-measurement is in the results doc §3.
- The benchmark app `app.tenshu.storebench.ios` is **still installed** on the phone (it could not be
  removed while the phone is unavailable). Remove it with `spike-ios-store/run-bench.sh
  device-uninstall`; its data lives only in its own container.

## 8. Verdict

**Measured:** on an iPhone 17e with a 150k-row synthetic replica, the Android/JVM SQLite driver
builds and runs for both iOS targets as it is, and the SQLite store was faster than
`FileObjectStore` at everything measured except reads the file store answers from memory: point
reads from a new process 20–30× in total time (60–100× per-read median), durable single writes
15–20×, bulk load ~25×, delete ~10×, wipe
~140×, with an eighth of the disk. Its concurrency primitives (snapshot reads under a writer,
immediate cross-connection visibility, `data_version`) work on the phone at microsecond cost.
**Inferred, not measured:** how large the point-read advantage is for flash-cold reads on the
phone (the coldest device state may have been partly cached, and the simulator's flash-cold pass
showed ~3× rather than 20–30×), and anything about two *processes* in the App Group
container, which is where §5's remaining hazards — the suspended-lock termination and schema skew
between apps — live.
