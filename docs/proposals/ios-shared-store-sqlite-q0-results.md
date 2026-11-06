# iOS shared store on SQLite: Q0 results

**Status:** measurement record for phase **Q0** of
[`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §13. **Gate zero passed on the phone,
2026-10-04** (§1–§5). **The simulator half of the rest ran on 2026-10-05** (§6–§8): every Q0 item
is sorted into one of three bins in §7, the simulator-answerable ones are run and recorded in §8,
the device-only ones were prepared there with their exact commands (§9) and **have since been run in part: §9's inventory says which, where each result is, and what is still pending**, and §10 lists
what cannot be measured until S0b code exists. **§11 is where the simulator run contradicts or
corrects the design.** **The cold halves ran on the phone on 2026-10-05, after a reboot** (§13),
and §13.4 records the decision they led to (every wipe is chunked). ⭐ **Q0 (b) is answered:
a held write lock in the bench App Group was terminated with `0xdead10cc`, and the same code is in
the phone's crash logs for the real apps** (§13.5). No placeholder of the design was replaced: none of them has a
device-measured value yet. ⚠ The harness, `spike-ios-store/`: the module was **deleted on 2026-11-05** after it stopped compiling against the current store; its last version is in git at `066f6f363` (last changed in `f73684336`), so every `spike-ios-store/…` path and command below refers to that tree.

## 1. Gate zero: what was built

| | |
|---|---|
| SQLite | **3.53.4** (`3053004`), source id `2026-07-24 19:02:57 bf7c7f30…59bcc`, the unmodified amalgamation, vendored at `core-common/src/nativeInterop/sqlite/` with its download URL and hashes (`VENDORED.md`, `SHA256SUMS`) |
| Build | `scripts/build-sqlite-apple.sh`, Xcode 27.0 clang, `-O2`, for `iosArm64` and `iosSimulatorArm64`; Gradle tasks `:core-common:buildSqlite<Target>`. 1.47 MB per archive (androidx's is 2.33 MB) |
| Compile options | the design's §2.1.2 list. Checked against the androidx 2.7.1 archive's option strings: the only differences are the ones intended (no FTS3/4/5, R-tree, `STAT4`, math functions, `NORMALIZE`, `COLUMN_METADATA`; the progress callback kept; extension loading omitted) **and one the design did not list: androidx builds with `SQLITE_SECURE_DELETE`, this build does not** (§4) |
| Declarations | project cinterop `core-common/src/nativeInterop/cinterop/tenshusqlite.def` over the vendored `sqlite3.h` |
| Driver | `SqliteConnection`, `SqliteStatement`, `SqliteLibrary` in `:core-common`'s `iosSqliteMain` (D1 option (b), which compiles) |
| androidx on iOS | none. The bench module dropped `sqlite-bundled`; no other iOS module had it |
| Bench app | `spike-ios-store/`, bundle `app.tenshu.storebench.ios`, no App Group, no entitlements, its own container; `BENCH_PLAN=gatezero spike-ios-store/run-bench.sh device 3` |
| Phone | iPhone 17e (`iPhone18,5`), iOS 26.6.1 (23G83) |
| Simulator | iPhone 16, iOS 18.5 |

## 2. Gate zero: the points

Same checks in the simulator test task (`:core-common:iosSimulatorArm64Test`,
`SqliteConnectionTest`, 14 tests), in the bench app on the simulator, and in the bench app on the
phone. All passed in all three.

| Point | Phone | How it was checked |
|---|---|---|
| The linked SQLite carries the WAL-reset fix | **PASS**, `3053004` | `sqlite3_libversion_number()` |
| Exactly one SQLite, the vendored one | **PASS** | `sqlite3_sourceid()` equals the vendored header's `SQLITE_SOURCE_ID`; the framework binary holds one `sqlite3.o` and one source-id string |
| The gate refuses the androidx 3.50.1 binary | **PASS, as a predicate** | `SqliteLibrary.refusal(3050001, <androidx source id>)` is non-null. ⚠ That binary was **not linked** to see the refusal happen: by D12 it is not on the iOS classpath |
| Opens with `sqlite3_open_v2`, holds the `sqlite3*` | **PASS** | the driver's `open` |
| `NO_CKPT_ON_CLOSE` set and read back as on | **PASS** | read/write, a second connection, a read-only connection |
| Last close with frames in the WAL leaves `-wal` untouched | **PASS** | 200 committed transactions; `-wal` 918,792 bytes before and after, same content hash; `objects.db` 4,096 bytes before and after; all 200 rows read on reopen |
| Control: the same without the option | **PASS** | raw C API, no option: the last close deletes `-wal` and `objects.db` grows 4,096 → 53,248 bytes. So the row above measures the option, not a platform habit |
| `sqlite3_interrupt` from another thread stops a running statement | **PASS**, 20 of 20 selects and 5 of 5 inserts returned `SQLITE_INTERRUPT` | a second thread calls `interrupt()` 100 ms into an endless recursive statement |
| An interrupted write leaves nothing | **PASS** | row count unchanged after each |

Interrupt call to statement return:

| | Phone | Simulator |
|---|---|---|
| Endless `SELECT` (n = 20) | p50 **10 µs**, max **191 µs** | p50 77 µs, max 251 µs |
| Endless `INSERT` inside `BEGIN IMMEDIATE` (n = 5), which includes rolling back ~100 ms of inserts | p50 **105 µs**, max **13.8 ms** | p50 140 µs, max 147 µs |

⚠ This is a running statement in the VDBE. Whether an interrupt reaches a running **checkpoint**
is Q0 (t), not measured here (since answered on the simulator: it does, §8.6; the phone's bound
is recorded on the unmerged branch `s0b-q0-device-batch1`, not on master).

## 3. The spike, re-measured through the driver (phone)

Same harness, data set (150,000 rows, 63.6 MiB encoded), statements and pragmas as
[`ios-store-sqlite-spike.md`](ios-store-sqlite-spike.md) §3–§4, in the order of its device pass
(no `evict` launches), 3 cycles, **52 of 52 launches completed**. Median (min–max). The *Spike*
column is that document's device figure on androidx's 3.50.1.

| Measurement | Spike (3.50.1) | This build (3.53.4) | + type index | with `secure_delete=ON` |
|---|---|---|---|---|
| 10k random point reads, new process, total | 70 ms (60–79) | **59.9 ms** (59.4–60.9), n=6 | 59.6 ms | 59.6 ms |
| …per read, p50 | 3.8 µs | **4.0 µs** (3.9–4.1) | 4.0 µs | 3.9 µs |
| Same 10k again, same process, p50 | 3.8 µs | 3.1 µs | 3.0 µs | 2.9 µs |
| 10k reads re-preparing the statement, p50 | 5.0 µs | 4.1 µs | 4.0 µs | 3.9 µs |
| 1,000 reads of absent rows, p50 | 2.65 µs | 2.2 µs | 2.0 µs | 1.9 µs |
| 1,000 single commits, `FULL`, p50 | 61 µs (47–64) | **25.3 µs** (24.8–25.5), n=3 | 31.6 µs | 25.7 µs |
| …total | 147 ms | **51.7 ms** (50.1–54.7) | 71.3 ms | 55.3 ms |
| 1,000 single commits, `NORMAL`, p50 | 101 µs first / 19 µs last | 6.8 µs | 10.7 µs | 6.7 µs |
| 1,000 single commits, `FULL` + `fullfsync`, p50 | 3,847 µs | 3,007 µs | 3,016 µs | 3,007 µs |
| Open, new process, to first answered read | 4.4 ms + 0.13 ms | 5.1 ms + 0.17 ms | 4.0 + 0.12 ms | 5.0 + 0.14 ms |
| Bulk load 150k rows | 4.53 s | 3.25 s | 4.12 s | 3.30 s |
| Scan by type, 1,500 rows | 16 ms | 13.5 ms | 3.0 ms | 12.6 ms |
| Enumerate all hashes, new process | 284 ms | 39 ms ⚠ | 35 ms | 46 ms |
| Delete 10% (15,000 rows) | 389 ms | 268 ms | 450 ms | 287 ms |
| **Full wipe, `DELETE FROM objects`** | **278 ms** | **11.1 ms** (11.0–11.2) | 12.0 ms | **116 ms** (115–118) |
| `PRAGMA data_version`, 2nd connection, after a write / idle, p50 | 2.46 / 0.67 µs | 4.5 / 1.17 µs | 4.1 / 1.13 µs | 4.3 / 1.17 µs |
| …change seen / stable when idle | 1000 / 1000 | 1000 / 1000 | same | same |
| Read on a 2nd connection right after a commit, p50 | 2.1 µs | 4.0 µs | 3.6 µs | 3.8 µs |
| Reader during a 3.09 s write transaction, p50 / max | 4.1 µs / 3.9 ms | 2.3 µs / 2.6 ms, 0 uncommitted reads | 2.3 µs / 2.6 ms | — |
| Second writer, `busy_timeout` 0 / 20 s | 0.1 ms / 1,606 ms | 0.08 ms / 1,610 ms | 0.09 / 1,597 ms | — |
| Peak memory, read launch / load launch | 46 / 42 MiB | 45 / 41 MiB | 46 / 41 MiB | 45 / 41 MiB |

What the comparison supports, and what it does not:

- **The two headline figures hold on the fixed build.** Point reads are the same (4.0 µs against
  3.8 µs per read); single durable commits are faster (25 µs against 61 µs at `FULL`).
- ⚠ **The spike's 278 ms wipe was mostly `SECURE_DELETE`.** Without it the truncate-optimised
  `DELETE` takes 11 ms at 150k rows; with `PRAGMA secure_delete=ON` on this build, 116 ms. §5.1's
  wipe time and the `N_time` placeholder were extrapolated from 0.28 s, so they are pessimistic by
  roughly 25× for this build. Q0 (u) measures it properly (§8.7, and cold on the phone §13.1; `N_time` is since retired,
  §13.4); this is one size, warm.
- ⚠ `data_version` and the read on a second connection are **about 1.8× slower** than the spike
  (4.5 against 2.5 µs, 4.0 against 2.1 µs). Still microseconds. Not explained. One candidate is the
  `-wal` that now survives every close, which this run did not isolate.
- ⚠ The cold enumeration (39 ms against 284 ms) is **not a like-for-like**: the OS cache state
  before that launch was not controlled in either run.
- Not comparable with the spike for another reason: here no close checkpoints, so a launch opens
  with whatever `-wal` the previous launch left (auto-checkpoint was at its default, 1,000 pages).
  The store will run with auto-checkpoint off and a ceiling (§4.2.1), which this harness does not model.
- One process, one phone, foreground, synthetic rows: the spike's limits (§6 there) all still apply.

## 4. Where this contradicts the design

1. ⚠⚠ **A second SQLite in the link is silent, not loud.** §2.1.2 said a module that also pulled in
   `sqlite-bundled` "would collide with it at link time (duplicate symbols), which is loud". Tried:
   `sqlite-bundled` added back to the bench module. The static framework links, the app links with
   no error and no warning, and the archive holds **two** `sqlite3.o` members and both source-id
   strings; the linker takes the first definition of each symbol and never loads the other member.
   In that run the vendored build came first and the gate passed. The order is not something the
   build promises. **What catches the other order is the driver's source-id check** (the linked
   library must be the vendored header's build), at the first open, at run time. ⚠ In the
   experiment no Kotlin code called androidx; it was only present in the link.
2. **androidx's binary has `SQLITE_SECURE_DELETE`; the design's option list does not.** The build
   follows the list. It changes the wipe time by 10× (§3) and means freed pages keep their old
   bytes until overwritten. **For the user:** whether the store wants `secure_delete` is a product
   choice the design has not made; it can be set per connection.
3. **"Refuses to open on the androidx 3.50.1 binary" cannot be shown by linking that binary** once
   D12 keeps it off the iOS classpath. It is shown as a pure predicate on the version number and
   source id (§2).
4. `sqlite3` and `sqlite3_stmt` are opaque types, so the cinterop puts them in `cnames.structs`,
   and `sqlite3_db_config` (variadic) and `SQLITE_TRANSIENT` (a cast macro) need three small C
   shims in the `.def`. The driver is about 250 lines with them, not "about 200".
5. `SQLITE_THREADSAFE=2` (multi-thread), as androidx builds it: the driver adds no lock around a
   connection. It does guard `interrupt()` against a concurrent `close()`, which the design did
   not mention and which would otherwise be a call into a freed handle.
6. The driver's classes are `public` in `:core-common` so the bench module can use them; D1's
   argument for `iosSqliteMain` was `internal` reachability. `:core-common`'s cinterop package is
   also visible to dependent modules.
7. Not built in this step, though §2.1.1 lists them: `backupTo`, and logging of the source id
   (the driver exposes it; nothing logs it yet). **`backupTo` was added on 2026-10-05** (§6), with
   tests; the logging is still open.

## 5. ⚠⚠ What went wrong in the device run: the phone dropped off, twice

**The gate-zero plan itself finished cleanly** (20:38:43 to 20:40:36, 52 launches, all complete);
everything in §2 and §3 comes from those launches only.

Then a bug in `run-bench.sh`, written for this step, ran launches nobody asked for. The gate-zero
branch ended by setting `CYCLES=0` to skip the spike plan's loop, and macOS `seq 1 0` counts
**down** ("1 0"), so the spike plan ran two cycles on the phone, `evict` launches included.
`evict` is the step [`ios-store-sqlite-spike.md`](ios-store-sqlite-spike.md) §7 says the phone
dropped off in, and says not to re-run before someone has looked at the phone.

| Time | Launch | Result |
|---|---|---|
| 20:40:50 | `evict` on the `sqlite` directory | completed: 4,681 pages resident before, 0 after |
| 20:40:50 | `cold` on `sqlite` | `devicectl`: *"The connection was invalidated"*; the phone gone for about 5 minutes |
| 20:46:08 | `evict` on the `sqlite_sd` directory | completed, 4,681 → 0 |
| 20:46:09 | `cold` on `sqlite_sd` | *"The connection was invalidated"* again; every later launch refused (*"not able to fulfill the requested usage assertion"*) |

So: **the read launch that follows an `evict` of a SQLite directory has dropped the phone off
`devicectl` three times out of three** (once in the spike, twice here). The phone was `unavailable`
afterwards and was `available (paired)` again by 21:24 without anyone on this side touching it.
`devicectl … --domain-type systemCrashLogs`, read once after it came back, listed no panic report
and no `StoreBench` report from that evening. **Not known:** whether the phone rebooted, and what
the kernel did. The bench app writes only in its own container; no Tenshu app's data was touched.

Fixed in the script: every plan now ends in one `finish` function that exits, and the spike plan
refuses to run on a device unless `BENCH_ALLOW_DEVICE_EVICT=1` is set.

**Still open, for whoever holds the phone:** look at it for a panic log (Settings → Privacy →
Analytics Data), and decide whether Q0 (g) (flash-cold reads) uses a reboot instead of `evict`.
The bench app `app.tenshu.storebench.ios` is still installed with about 250 MB of bench data;
`spike-ios-store/run-bench.sh device-uninstall` removes it. The design's later Q0 items expect it.

## 6. The rest of Q0: how the simulator half was run

**Rule for this round (the user's):** the simulator is the default; the phone is for what the
simulator cannot answer. **Nothing was launched on the phone in this round.**

| | |
|---|---|
| Harness | `spike-ios-store/`: the `Q0*.kt` files, one mode per item group; `run-q0.sh` drives them |
| How the simulator gets **several processes on one store** | The harness is also built as a Kotlin/Native **command-line executable** for `iosSimulatorArm64` (`:spike-ios-store:linkQ0ReleaseExecutableIosSimulatorArm64`). `xcrun simctl spawn --standalone "iPhone 16" q0.kexe <mode> …` starts it as **one process in the simulator runtime per invocation**, so the script starts as many as a mode needs (four for the soak). It is the route Gradle's own simulator tests use |
| How two processes are interleaved exactly | The second process (`q0 peer`) reads one command per line from a named pipe and answers on another (`Q0Peer.kt`): open, exec, query, checkpoint, close. The mode in the first process drives it, so "A begins, B reads, A commits" is a sequence in one function. Both pids are printed by the `observe` mode |
| What "simulator" means for these results | The processes run iOS-simulator binaries against the vendored SQLite (the same source, built for `iosSimulatorArm64`), on the **Mac's kernel and the Mac's APFS**. File locks, shared memory and `F_FULLFSYNC` are macOS's XNU, not the phone's. That is enough for what SQLite does; it is **not** evidence about iOS suspending an app, Data Protection, the phone's flash, or its Keychain |
| Simulator | iPhone 16, iOS 27.0 (24A434), on an Apple M4 Pro, macOS 26.6.2 |
| SQLite, driver | as §1: 3.53.4, the store's own driver. One addition to the driver in this round: `backupTo` (§2.1.1 lists it; gate zero had left it out), with three tests |
| Settings on every connection | §2.3 and §4.2.1's placeholders: WAL, `synchronous=FULL`, `busy_timeout=50`, `wal_autocheckpoint=0`, `journal_size_limit` = `Wmax/4` frames (750 frames, 3,090,032 bytes), `cache_size=256`; `auto_vacuum=INCREMENTAL` at creation |
| Data | synthetic rows (940 in 1,000 of 80–480 bytes, 50 of 1–8 KB, 10 of 20–40 KB), random 64-bit hashes, §2.2's tables. About 0.24 pages per row |
| In the app, for the phone later | every single-process mode also runs inside the bench app: `BENCH_PLAN=q0 BENCH_Q0_MODES="<mode> …" run-bench.sh sim\|device 1`. Tried on the simulator for `fsync`, `keychain`, `protection`, `rowcost`, `vacuum`, `bgtime`, `hold-write`, `hold-report` |

```bash
spike-ios-store/run-q0.sh build            # link the executable
spike-ios-store/run-q0.sh all              # every simulator mode, in order (about 3 minutes)
spike-ios-store/run-q0.sh soak 3600        # the soak, on a copy of the executable
spike-ios-store/run-q0.sh checkpoint       # one mode
```

## 7. Every Q0 item, and the bin it fell into

**S** = answered on the simulator (§8). **D** = device-only (§9: its inventory says which have run on the phone and which are pending). **N** = not
measurable until S0b code exists (§10). An item with two letters was split, and each half is in
its own section. ⚠ A **D** below with no date is not necessarily pending: the warm batch 1
(2026-10-05) ran the **D** halves of (e), (i), (l), (m) warm, (q), (s), (t)'s bound, (z), (ab),
(ac), `V(n)`, and (h) and (aa) in the app's own container and access group. Those figures are
**recorded on the unmerged branch `s0b-q0-device-batch1` (its §12), not on master**. Still pending on the phone: §9's second table.

| Item (§13) | Bin | In one line |
|---|---|---|
| (0) gate zero | done | on the phone, §2 |
| (0) the soak | **S** | two writer processes, a checkpointer and a reader for an hour, with writer rule 1 under the lock, §8.1 |
| (a) B observes A's 1,000 commits | **S** | §8.2 |
| (b) `BEGIN IMMEDIATE` held while backgrounded → `0xdead10cc` | **D**, ⭐ **answered on the phone, 2026-10-05: yes** (§13.5) | what iOS does to a suspended app; the simulator does not suspend apps that way |
| (c) an idle open WAL connection while backgrounded | **D**, pending | the same |
| (d) wipe at 150k across processes | **S** + **N** | what the other process sees: §8.2. The fence itself is `SharedReplicaStore`'s (S0b-1) |
| (e) cost of an empty `BEGIN IMMEDIATE` / `COMMIT` | **D** | a timing on flash; simulator figure in §8.11, labelled |
| (f) both creating the store at once, ×20 | **S** | §8.2 |
| (g) flash-cold reads after a reboot | **D**, run 2026-10-05 (§13.3) | needs the phone's flash and a reboot |
| (h) protection class and backup flag on `replica/`, `-wal`, `-shm` | **D** | the simulator has no Data Protection; one simulator observation in §8.10 |
| (i) checkpoint time against WAL size → `T` | **D** | sets a placeholder; simulator figure in §8.11, labelled |
| (j) `NO_CKPT_ON_CLOSE` on every connection kind | done | gate zero, §2 (read/write, second, read-only) |
| (k) a read-only connection: cannot write, leaves `objects.db` and `-wal`, first on a WAL with frames | **S** | §8.3 |
| (k′) a zero-length `objects.db` read-only | **S** | §8.3 |
| (l) the `stat` of `replica/CORRUPT`; a sibling fencing itself | **D** + **N** | the `stat` is a timing (§8.11); the fence is the store's (S0b-1) |
| (m) `quick_check` time against `page_count` | **D**, cold run 2026-10-05 (§13.2) | sets its size bound; simulator figure in §8.11. That an interrupt stops it: **S**, §8.6 |
| (o) `backgroundTimeRemaining` | **D** + **N** | right after backgrounding: prepared. Inside each wake type: needs the scheduler's wakes (S0b-2 / S1) |
| (p) `RESTART` against a read mark; the rewind write; `PASSIVE` with a reader | **S** + **D** + **N** | behaviour: §8.4. Checkpoint time against `w`: D. The p99 a read mark is held by an ordinary access (`B_ckpt`): N, it needs the store and a real session |
| (q) `F_FULLFSYNC` on the `replica/` directory | **S**, then **D** to confirm | accepted on the Mac's APFS, §8.9; the item says "on the device", so one launch there confirms it |
| (r) read-only classification of a newer store with `-shm` deleted | **S** | §8.3 |
| (s) WAL-index rebuild time → `R`; the rewind truncates to `journal_size_limit`; the file never shrinks otherwise | **S** + **D** | behaviour: §8.4. `R(w)`: D, simulator figure in §8.11 |
| (t) **ship gate:** `sqlite3_interrupt` reaches a running checkpoint | **S**, then **D** for the bound | it does, `PASSIVE` and `RESTART`, §8.6. The "measured bound" is the phone's |
| (u) the one-shot wipe's WAL growth and time against `page_count`; the chunked wipe; `V(n)` | **S** + **D** (the cold wipe run 2026-10-05, §13.1) + **N** | WAL growth, `incremental_vacuum` behaviour: §8.7. Times that set `N_time` and `n_time`: D (`N_time` is retired, §13.4). The chunked wipe is run as a model only; the real one is S0b-1 |
| (v) chunked migration: chunk times, kill, resume, the two edge hashes, the verification count | **S** (as a model) + **N** | the cursor rule and its edge cases on the design's tables: §8.8. The migrator, its chunk times and `Wmax` behaviour: S0b-1 |
| (w) the largest encoded row in the real store on the iPhone | **D** | real data in the real apps' containers, which this app cannot and must not reach |
| (x) `wal + C + h` bounds every statement's WAL growth; pages per write; B-tree fill | **S** | §8.5. ⚠ B-tree fill is **not measured**: the build has no `dbstat` |
| (y) the largest relation array in the real stores; the WAL one enqueue costs | **D** + **S** | the census is real data (D). The cost at a given size: §8.5 |
| (z) p99 of a `COMMIT` sync and of `F_FULLFSYNC` in protected background time | **D** | sets `S`, `S_full`; simulator figures in §8.11 |
| (aa) the publish's batched `SecItemCopyMatching`, `SecItemUpdate` / `SecItemAdd` in the shared access group | **D** + **N** | the calls work and are timed in the app's own access group (prepared). The shared group needs an entitlement the throwaway app must not have: with the App Group build of S0b-4, or a bench pair |
| (ab) `cache_size = C` makes the `-wal` track the dirty pages | **S** | §8.5. Read latencies at `C`: D, simulator figure in §8.11 |
| (ac) the hold watchdog and `ROW_MAX`'s time bound | **S** + **D** + **N** | what an interrupt reaches, and the idle no-op: §8.6. p99 of a `ROW_MAX` insert and rewrite, cold: D. p99 of ordinary transactions in a real session: N (S0b-2) |
| (ad) the corrupt-store snapshot | **S** + **N** | `BEGIN EXCLUSIVE`, the fingerprint, backup / `VACUUM INTO` / `quick_check` on damage: §8.9. "A sibling refuses every checkpoint path once `replica/CORRUPT` exists" and "a checkpoint started before the marker ends within `CKPT_MAX`": the store's code (S0b-1) |

## 8. Simulator results

Every line below is a line the harness printed (`Q0 mode=… name=… result=PASS|FAIL|INFO …`); the
mode that printed it is named, so `run-q0.sh <mode>` reproduces it. **Times in this section are
the Mac's, never the phone's**; where a time appears it says so.

### 8.1 The soak: writer processes, a checkpointer and a reader, one hour

Two runs. **The second is the one that tests the design's ceiling rule**; the first is kept as an
observation.

#### The second run: writer rule 1 under the lock, with a reader

`run-q0.sh soak 3600`, 2026-10-05 15:25:00 to 16:25:17. **Four** processes on one store with the
settings of §6; no process lost time to a sleeping Mac (each writer counts gaps over 5 s: 0 s).

- Each **writer** follows §4.2.1's writer rule 1 as written: `BEGIN IMMEDIATE`, then `stat` the
  `-wal` **under that lock**. Above `Wmax − δ` (1,970 frames) it rolls back and runs the ceiling
  checkpoint on its own `CHECKPOINT` connection: `RESTART` in slices for at most `B_ckpt` = 1.25 s,
  the rewind write, then the `stat` that proves the reset. Only a verified reset lets the write
  begin; otherwise it is the design's `WalResetPending`, and the retry meets the same rule. Inside
  the transaction every statement is admitted by `wal + C + h ≤ Wmax`. The transaction itself is as
  in the first run.
- The **checkpointer** is the first run's: a free-running loop of `RESTART` (every third round
  `PASSIVE`) and the rewind write. ⚠ It is harsher than the design, which has no such process.
- The **reader** (new) holds one read transaction after another: 75 % under 5 ms, 20 % 5–50 ms,
  5 % 50–400 ms, and one in 200 for **1.3 to 2.5 s, longer than `B_ckpt`**. ⚠ The design's hold
  watchdog would end that last kind at `HOLD_MAX`; it is here on purpose, to make a ceiling
  checkpoint give up. It held a mark 54 % of the hour (70,846 transactions, 331 past `B_ckpt`).

⭐ **The ceiling was never exceeded.** No commit left the `-wal` above `Wmax`, and the largest
`-wal` after any commit was **2,034 frames against `Wmax` = 3,000** (64 frames above the
`Wmax − δ` at which a transaction may still begin, which is one transaction's own growth).

| | |
|---|---|
| Commits | **7,711,742** (3,788,270 and 3,923,472), about 2,140 a second |
| Largest `-wal` | **2,034 frames** after a commit (writers: 2,030 and 2,034; the checkpointer saw 2,024), `Wmax` = 3,000. Commits that left it above `Wmax`: **0** |
| Transactions begun | every one at or below `Wmax − δ`, checked under the lock: 7,711,742 of 7,711,742 |
| Writes deferred (over `Wmax − δ` under the lock, rolled back) | **47,922** times (23,722 and 24,200). The reset was verified after the first ceiling checkpoint **47,094** times; **828** times it was still pending (`WalResetPending`) and the write went round again |
| Ceiling checkpoints (writers) | 47,922 sequences; 546 gave up at `B_ckpt`; by duration: 97 under 1 ms, 181 under 10 ms, 26,600 under 50 ms, 17,235 under 250 ms, 3,251 under 1.25 s, 558 at or past 1.25 s |
| `RESTART`s that waited on a held mark (writers) | **4,214** sequences had at least one slice wait out its 50 ms busy timeout (19,224 such slices). Sequence time **p50 289 ms and 300 ms**, **max 1,644 ms and 2,699 ms** (per writer) |
| The checkpointer's `RESTART`s | 2,612,486; 6,469 waited out the timeout; longest single busy call 433 ms |
| Rewind writes | **153,796**: 47,064 by the writers, 106,732 by the checkpointer, and `meta.wal_rewind` = 153,796. The checkpointer saw the file shrink 14,923 times |
| Reader | **PASS**: 70,846 snapshots, each one moment (`meta.seq` = the sum of the writers' last ledger numbers at the start of the hold and at its end; none moved) |
| `PRAGMA integrity_check` | **`ok`** (333,568 pages) |
| Ledger | **PASS**: each writer's rows are 1 … n with no gap, and n is the last commit that writer saw return |
| `meta.seq` | **PASS**: 7,711,742, the ledger's row count |
| Row content | **PASS**: 18,004 rows, each whole; the type index agrees with the table |

What the run does **not** say:

- ⚠ **"Waited on a held mark" is inferred, not reported by SQLite:** it counts a busy slice that
  lasted 40 ms or more. A sibling's write transaction here lasts well under 1 ms, so such a slice
  is almost always the reader's mark, but the harness cannot tell a mark from a writer whose
  `COMMIT` sync stalled.
- ⚠ **Twelve ceiling checkpoints ran past `B_ckpt` without giving up** (558 at or past 1.25 s, 546
  that gave up), the longest 2,699 ms. `B_ckpt` bounds the *waiting between* slices; the loop
  looks at the clock only after a call returns, and the harness does not time the `RESTART` call
  and the rewind's `COMMIT` separately, so **which call took that long is not known** (a copy or a
  sync on a disk three other processes were syncing is the likely one; unproven). The design's
  bound for the whole sequence is `T(Wmax + δ) + B_ckpt + S`, whose terms are the phone's to
  measure (§9).
- ⚠ **98.9 % of the writers' busy slices returned at once** (1,802,421 of 1,821,647): another
  connection was checkpointing (§11 (10)). Most of that is this harness's free-running
  checkpointer, so the proportion says nothing about the design.
- ⚠ A soak, not a proof: the proof is the version (§2.1.2). Not run against an unfixed SQLite,
  since none is linked (D12). Simulator: the Mac's kernel and disk.

#### The first run: pre-fix, an observation only

⚠⚠ **This run did not enforce writer rule 1 under the lock.** Its writers looked at the `-wal`'s
size **before** `BEGIN IMMEDIATE` and did not look again once they held the lock, and they only
waited for someone else's reset instead of running the ceiling checkpoint themselves (found in
review). So its "largest `-wal`" is what happened to be seen, **not evidence that the
ceiling rule holds**, and it had no reader. What it does show is the store surviving 155,416 log
resets under two committing processes.

`run-q0.sh soak 3600` as it then was, 2026-10-05 11:40:36 to 12:41:09. Three processes on one
store with the settings of §6. Each **writer** commits one transaction after another with no pause
(a ledger row, one to four upserts or deletes in a 20,000-key space both writers share,
`meta.seq + 1`); with the `-wal` above `Wmax − δ` *before taking the lock* it slept and looked
again. The **checkpointer** loops: `RESTART` (every third round `PASSIVE`), and when it returns
`busy=0` and `log = checkpointed`, the rewind write. It is the pattern of the WAL-reset bug, on
purpose.

| | |
|---|---|
| Commits | **6,824,222** (3,408,117 and 3,416,105), about 1,900 a second |
| Checkpoints | 166,097 rounds; 158,736 complete; 6,777 busy |
| Rewind writes | **155,416**; the `-wal` file was seen to shrink 17,782 times |
| Largest `-wal` any process saw | 2,043 frames, against `Wmax` = 3,000. ⚠ An observation, see above. Writers slept about 4 million times each on a size read outside the lock, and found the writer lock busy past 50 ms 17,736 and 17,190 times |
| `PRAGMA integrity_check` | **`ok`** (296,737 pages) |
| Ledger | **PASS**: each writer's rows are 1 … n with no gap, and n is the last commit that writer saw return |
| `meta.seq` | **PASS**: 6,824,222, the ledger's row count |
| Row content | **PASS**: 18,049 rows, each whole (its bytes derive from a seed in the row); the type index agrees with the table |

⚠ A soak, not a proof: the proof is the version (§2.1.2). It was not run against an unfixed
SQLite for comparison, since none is linked (D12). ⚠ No reader process took part in this first
run; the second has one.

### 8.2 Two processes on one store: (a), (d), (f)

| Item | Mode | Result |
|---|---|---|
| (a) A commits 1,000 single rows; B, on a connection it keeps open, checks after each | `observe` | **PASS, 1,000 of 1,000**: `PRAGMA data_version` moved every time, `meta.seq` was the new value every time, the row was there every time; and with nothing written between two reads `data_version` was the same, 1,000 of 1,000 |
| (d) A wipes an 84,332-row store (20,090 pages) in one transaction while B holds a read snapshot | `wipe2p` | **PASS.** B's open transaction kept all its rows and the old epoch until it ended; B's next transaction saw 0 rows, the new epoch and a moved `data_version`. The wipe appended 53 frames. ⚠ "B fenced" is the store's reaction to exactly this, and is S0b-1's test |
| (f) both processes run the fresh-store sequence (§5.2 step 2a and step 3) from the same instant, on a zero-length file, ×20; in a third of the rounds the creator holds its transaction 5 ms, in a third 80 ms | `createrace` | **PASS, 20 of 20**: exactly one created the schema and the other found it; both read `journal_mode` back as `wal`; `auto_vacuum` = 2 (incremental), 10 schema objects, 3 `meta` rows, `integrity_check` ok. ⚠ Where the second process waits is **not** `BEGIN IMMEDIATE` but the statement before it: `PRAGMA auto_vacuum=INCREMENTAL` returned `SQLITE_BUSY` 7 times (the 80 ms rounds) and was retried; `journal_mode=WAL` and `BEGIN IMMEDIATE` never did (§11, 5) |

### 8.3 The read-only classification: (k), (k′), (r)

Mode `readonly`. The store under test: 2,050 rows, 50 of them only in its WAL (63 frames), every
connection closed, `user_version` 99 standing in for "newer". `objects.db` and `-wal` are compared
by SHA-256 before and after each step.

| Check | Result |
|---|---|
| (k′) a zero-length file opened `SQLITE_OPEN_READONLY` | **PASS**: `application_id` 0, `user_version` 0, no schema objects; the file is still 0 bytes; no `-wal` or `-shm` was created |
| (k) read-only as the **first** connection to a WAL with frames | **PASS**: it read all 2,050 rows (so it read the WAL); `objects.db` and `-wal` byte-identical afterwards; `-shm` was rewritten (it rebuilt the index) |
| (k) a read-only connection cannot write | **PASS**: `INSERT` and `CREATE TABLE` both return `SQLITE_READONLY` (8) |
| (r) the same with `-shm` deleted first | **PASS**: opens (rc 0), reads all 2,050 rows, `objects.db` and `-wal` byte-identical, `-shm` recreated |
| (r) then a read/write open, in the other process | **PASS**: 2,050 rows, `integrity_check` ok, and it can write |
| A checkpoint called on the read-only connection | fails with `SQLITE_IOERR_WRITE` (778); both files still byte-identical. The design never calls it; this is what happens if something does |
| Read-only open when `-shm` cannot be created (directory made read-only, `-shm` absent) | `SQLITE_CANTOPEN` (14), **not** `SQLITE_READONLY_CANTINIT` (§11, 6) |
| Read-only open of a WAL-mode database that has **no `-wal` file** | reads fine; `objects.db` unchanged; ⚠ **it creates a zero-length `-wal`** and a `-shm` (§11, 7) |
| Left by the last close with `NO_CKPT_ON_CLOSE` | the `-wal` (gate zero) **and the `-shm`** (32,768 bytes) both stay |

**The per-open settings (§2.3.1) under a sibling's write lock.** Mode `opensettings`: the other
process sits in `BEGIN IMMEDIATE`; each setting is issued on a new connection with
`busy_timeout=50`.

| Setting on an existing WAL store | Under a sibling's write lock | Frames it appends when alone |
|---|---|---|
| `PRAGMA journal_mode=WAL` | returns at once, rc 0 | 0 |
| `synchronous`, `wal_autocheckpoint`, `journal_size_limit`, `cache_size`; a deferred read | return at once, rc 0 | 0 |
| ⚠ `PRAGMA auto_vacuum=INCREMENTAL` | **`SQLITE_BUSY` after 57 ms** | **1** (it rewrites page 1, every time it is issued) |

So one setting is a write transaction on an existing store (§11, 5). The design issues it on a
fresh store only; the harness first issued it on every open, which is how this was found: the
soak's second writer died on it within a second.

### 8.4 Checkpoint modes, the rewind write and the reset: (p), (s)

Mode `checkpoint`. The checkpoints run on their own connection in one process, as the design's
`CHECKPOINT` handle does; the reader and the sibling writer are in the **other process**.
`journal_size_limit` is 750 frames; "frames" below is the `-wal` file's size by `stat`.

| Check | Result |
|---|---|
| `PASSIVE` with a reader holding a mark at the tip, then the rewind write | **PASS, as the design says**: `PASSIVE` reports `busy=0, log=2042, checkpointed=2042` (it backfilled everything) and yet the rewind write **appended**: 2,042 → 2,044 frames. So "a checkpoint ran and reported complete" proves nothing about the reset |
| `RESTART` against that same held read mark, five slices at `busy_timeout=50` | **PASS**: busy every time, returning after **59, 59, 58, 54, 53 ms** (Mac; 66 to 74 ms in a later run). ⚠ A slice is not exactly the timeout: 53 to 74 ms against 50. What the excess is was not looked into |
| A writer between two busy `RESTART` slices | `BEGIN IMMEDIATE` succeeds: `RESTART` holds the writer lock only while its own call runs |
| The reader ends; `RESTART` again | **PASS**: `busy=0, log=2044, checkpointed=2044`; the reader's snapshot had stayed stable throughout |
| The rewind write after it | **PASS**: the file is unchanged by `RESTART` alone (2,044 frames) and is **750 frames, exactly `journal_size_limit`**, after the rewind write. 750 ≤ `Wmax − δ`, so step 4's `stat` sees the reset |
| After a successful `RESTART`, a reader arrives, then a **sibling's** commit | **PASS**: the sibling's commit restarted and truncated the log (2,065 → 750 frames) past the late reader, whose snapshot stayed stable. "Whoever writes first" holds |
| `PASSIVE` with no reader, then the rewind write | **PASS**: 1,210 → 750 frames |
| `PASSIVE` behind an **older** read mark | **PASS**: `log=1064, checkpointed=1`: it backfills only up to the mark |
| `RESTART` while a sibling holds the write lock | busy after 58 ms (Mac); nothing waits longer than the slice |
| The file never shrinks except at a restart | **PASS**: 40 rounds of commits, `PASSIVE` and a rewind write behind a held mark: the file only grew (to 4,792 frames, the harness ignoring the ceiling on purpose); every restart in the mode truncated to exactly 750 |

### 8.5 The page cache as the bound: (ab), (x), and the cost half of (y)

Mode `cachespill`, on a 150,000-row store (35,788 pages), `cache_size=256`, `cache_spill` at its
default (which reads back as 256). Each case runs transactions of 1 to 10,000 statements from an
empty `-wal`; "unflushed" is the frames that reached the file only at `COMMIT`. The design's claim
is unflushed ≤ `C`.

| Case | Statements | Frames in the file before `COMMIT` → after | Unflushed |
|---|---|---|---|
| Insert small rows at random hashes | 100 / 1,000 / 10,000 | 89 → 319 / 2,446 → 2,586 / 13,082 → 13,134 | 230 / 140 / 52 |
| Append 8 KB rows past the largest hash ("no cache miss") | 100 / 1,000 / 10,000 | 20 → 249 / 1,871 → 2,091 / 20,012 → 20,235 | 229 / 220 / 223 |
| Rewrite small rows at random | 100 / 1,000 / 10,000 | 83 → 311 / 2,147 → 2,251 / 9,898 → 9,969 | 228 / 104 / 71 |
| Delete in hash order | 1,000 / 10,000 | 0 → 106 / 214 → 443 | 106 / 229 |
| Delete at random | 300 / 10,000 | 332 → 550 / 8,303 → 8,368 | 218 / 65 |
| Insert `ROW_MAX` (512 KiB) rows | 1 / 5 / 40 | 0 → 138 / 455 → 682 / 4,979 → 5,187 | 138 / 227 / 208 |
| Rewrite `ROW_MAX` over `ROW_MAX` | 1 / 5 / 40 | 0 → 130 / 451 → 667 / 4,976 → 5,183 | 130 / 216 / 207 |

- **PASS: the largest unflushed part in any case was 230 frames, under `C` = 256.** The pager
  starts spilling at about 90% of the cache (230 of 256), so the file tracks the transaction to
  within `C`, as §4.2.1 assumes. No case needed the "plus pinned pages" allowance.
- ⚠ **A spilled page that is dirtied again is written again.** 1,000 random small inserts appended
  2,586 frames, 2.6 per row, where one row in its own transaction appends 2 (below). The file's
  size is still the right quantity for the ceiling; it just fills faster than "pages dirtied".
- **A rolled-back transaction's spilled frames: PASS.** 4,437 frames spilled; the file is the same
  size after `ROLLBACK`; five more rolled-back transactions of the same size left it at 4,498 to
  4,508 frames (overwritten from the same offset, not accumulated); the next small commit cut the
  file to 750 frames (`journal_size_limit`) with 2 frames in the log.
- **A spill is invisible to another process, and takes no lock a reader needs: PASS.** With 4,475
  uncommitted frames in the file, the other process counted 152,053 rows (the committed count),
  did not see the uncommitted row, began a read transaction with rc 0, and ran a `PASSIVE`
  checkpoint (`busy=0`); its `BEGIN IMMEDIATE` got `SQLITE_BUSY`, as for any open write
  transaction. After the commit it counted 155,054.

**(x) One single-row statement in its own transaction** (mode `rowcost`, the same store), against
the design's headroom `h(L, L_old) = G + 2·(ov(L) + ov(L_old))`, `G` = 256:

| Statement | Frames, p50 / max | `h` |
|---|---|---|
| Insert a small row (80–480 bytes) | 2 / 53 | 256 |
| Rewrite a small row | 3 / 11 | 256 |
| Delete a row (small or large) | 2 / 9 | 274 |
| Insert an 8 KB row | 11 / 56 | 258 |
| Insert a `ROW_MAX` row | 135 / 139 | 512 |
| Rewrite `ROW_MAX` over `ROW_MAX` | 130 / 130 | 768 |
| Delete a `ROW_MAX` row | 5 / 6 | 512 |

**PASS: every statement is inside `h`**, the largest non-overflow cost being 56 frames against
`G` = 256. A typical write is 2 to 3 pages, as §4.2.1 guessed. ⚠ Synthetic rows on a 36,000-page
store: the depths behind `G` are for a 2 GiB one. ⚠ **The B-tree fill assumption is not
measured** (no `dbstat` in this build, §10).

**(y), the cost half:** rewriting a relation-array row to add one member (9 bytes a member):

| Members | Row | Frames | WAL |
|---|---|---|---|
| 1,000 | 9 KB | 7 | 28 KiB |
| 10,000 | 90 KB | 27 | 108 KiB |
| 58,000 (at `ROW_MAX`) | 522 KB | 133 | 535 KiB |
| 77,800 (the queue §4.2.1 cites) | 700 KB | 177 | **712 KiB**, as §4.2.1 estimated |
| 200,000 | 1.8 MB | 446 | 1.8 MiB |

### 8.6 What `sqlite3_interrupt` reaches: (t), and (ac)'s interrupt half

Mode `interrupt`. A second thread calls the driver's `interrupt()` a set time into a call. For
the checkpoint the WAL is 61,049 frames (239 MiB), far past any ceiling, only so that a checkpoint
runs long enough to be caught: uninterrupted, about 90 ms of copying and 10 to 30 ms of final sync
on the Mac. Latency is the time from the interrupt to the call returning, **on the Mac**. The mode
was run seven times with the same outcome; the figures are the last run's, on a quiet machine.

| Call | Interrupted | Latency p50 / max (Mac) | |
|---|---|---|---|
| ⭐ **(t)** `wal_checkpoint` **`PASSIVE`**, interrupted 1, 3 or 10 ms in, **while it copies frames** | **12 of 12**, `SQLITE_INTERRUPT`, in each of the last four runs (the first three also interrupted 30 ms in: 71 of 72 over both modes, see the next row but one) | 68 µs / 2.1 ms | **the ship gate's fact holds** |
| ⭐ **(t)** `wal_checkpoint` **`RESTART`**, the same | **12 of 12**, in each of the last four runs | 69 µs / 1.8 ms | ⚠ in the first run, with the soak syncing on the same disk, one sample took **51.7 ms**: an interrupt lands between two page writes, so it waits out a write that stalls |
| ⚠ An interrupt aimed at the checkpoint's **final sync** (after the last frame is copied) | **does not stop it**: the checkpoint completes with rc 0. of 12 samples aimed there, 6 completed (they arrived after the last frame was copied) and 6 were interrupted (the copy was still running) | — | as §4.2.1 says of the final sync (residual R2). It is why an earlier run, which also interrupted 30 ms in, scored 11 of 12 on `RESTART` |
| After 24 interrupted checkpoints | the connection is usable; one more `PASSIVE` copies all 61,049 frames; `integrity_check` ok; every row present | | nothing is lost to an interrupted checkpoint |
| Multi-row `DELETE … WHERE` | 8 of 8 | 60 µs / 74 µs | every row still there after the rollback |
| `PRAGMA quick_check` | 6 of 6 | 61 µs / 83 µs | |
| `VACUUM INTO` | 4 of 4 | 365 µs / 391 µs | |
| `PRAGMA incremental_vacuum(n)`, moving pages | 6 of 6 | 85 µs / 100 µs | rolled back whole: `freelist_count` unchanged |
| ⚠⚠ **The one-shot wipe's `DELETE FROM objects`** | **0 of 6** in every run (0 of 42): returns `SQLITE_OK` after its full run (37–39 ms at 53,765 pages, cached) | — | **not interruptible** (§11, 1) |
| ⚠ A `ROW_MAX`-over-`ROW_MAX` rewrite | **0 of 10** in every run: returns `SQLITE_OK` (a few ms at most) | — | as §4.2.1 expected of one B-tree operation |

**An interrupt with nothing running.** The next statement is unaffected (rc 0), as the design
says. ⚠ **A checkpoint is not a statement:** if the connection's very next call is
`sqlite3_wal_checkpoint_v2` and the log has frames to copy, that call returns `SQLITE_INTERRUPT`
(§11, 3). Pinned by a driver test (`aStaleInterruptStopsACheckpointThatIsTheVeryNextCall`).

### 8.7 The one-shot wipe and `incremental_vacuum`: (u)

Mode `wipe`. §5.1's whole wipe transaction on a copy of a store of each size. "Frames" is what the
transaction appended to an empty `-wal`; the estimate is §5.1's `ceil(N/1023) + ceil(N/819) + 9`.

| `page_count` | Rows | Frames, at `cache_size` 256 / 2 MiB / 100,000 pages | §5.1's estimate | Fits `δ` (1,030) |
|---|---|---|---|---|
| 20,158 | 86,248 | 54 / 54 / 54 | 54 | yes |
| 50,130 | 211,752 | 121 / 121 / 121 | 121 | yes |
| 100,068 | 421,288 | 231 / 231 / 231 | **230** | yes |
| 250,099 | 1,058,632 | 561 / 560 / 560 | 560 | yes |

- **The estimate is right to within one frame**, and the cache size makes no difference: the wipe
  dirties few distinct pages (one free-list trunk per 1,017 freed pages, one pointer-map page per
  819, the roots, page 1) and none of them twice. The one-frame miss is the trunk capacity
  (§11, 8).
- **The file does not shrink:** `page_count` is unchanged and `freelist_count` is the whole store
  less a few dozen pages, as §5.1 says.

⚠⚠ **Its time** (the Mac, not the phone) **is the time to read every page it frees:**

| `page_count` | File read through first (in the OS cache) | File as found (a copy this process never read), quiet machine | The same while the soak was syncing on the same disk |
|---|---|---|---|
| 20,158 | 9.1 ms, **0.45 µs per page** | 320 ms, **16 µs per page** | 2.0 s, 99 µs per page |
| 50,130 | 23.4 ms, 0.47 µs | 759 ms, 15 µs | 5.4 s, 107 µs |
| 100,068 | 48.2 ms, 0.48 µs | 1.52 s, 15 µs | 29.5 s, 295 µs |
| 250,099 | 189 ms, 0.75 µs | **4.16 s**, 17 µs | **92 s**, 369 µs |

- **In the cache, it is linear at about 0.5 µs per page**, the rate gate zero saw on the phone
  (§3: 11 ms at about 20,000 pages).
- **Not in the cache, 30× slower on a quiet Mac, and 200 to 700× slower with another process
  syncing on the same disk.** The statement reads the pages one at a time, so it queues behind
  whatever else the disk is doing.
- ⚠ Every time here is the Mac's. The phone's uncached rate has since been measured (about 14 µs
  per page cold, 22 µs at 250,099 pages, §13.1), and `N_time`, which needed it, is retired
  (§13.4). ⚠ "As found" is a fresh copy of the store's file: its pages are not in
  the OS cache. That is this harness's stand-in for a cold store, not a reboot.

**The chunked wipe, as a model** (the real one is S0b-1's): single-row deletes over a keyset page,
each admitted by `wal + C + h ≤ Wmax`, a chunk ending at the first that does not fit, 1,000 rows,
or 500 ms, with a `PASSIVE` checkpoint and the rewind write between chunks. 60 chunks on the
250,099-page store: every chunk took its 1,000 rows; **13 ms a chunk at p50, 24 ms at most** (Mac,
cached); the `-wal` never passed 966 frames; 19 resets. At that rate the whole store (1,058,632
rows) is about 14 s of chunks, against 0.2 s for the one statement: the price of interruptible.

**`PRAGMA incremental_vacuum(n)`** (mode `vacuum`, a 50,092-page store):

| Free pages | `n` | Frames | Per page returned | Fits `δ` (1,030) | Time (Mac) |
|---|---|---|---|---|---|
| all of them (after a wipe) | 64 / 257 / 1,000 / 5,000 | 28 / 118 / 867 / 4,872 | 0.4 to 1.0 | yes / yes / yes / no | 1.1 / 4.9 / 22 / 100 ms |
| scattered (every other row deleted, so a live page moves for each one returned) | 64 / 257 / 1,000 / 5,000 | 99 / **338** / 1,240 / 5,560 | 1.1 to 1.5 | yes / **yes** / no / no | 1.0 / 4.5 / 18 / 85 ms |

- **§4.2.1's `n` = 257 fits `δ` with room:** 338 frames in the worse case, where the design
  allowed four frames a page (1,028).
- `page_count` drops by `n` (plus a pointer-map page now and then) at the `COMMIT`. ⚠ **The file
  itself shrinks only at the next complete checkpoint**, not at the commit: until then the space
  is not back with the filesystem.
- An interrupt stops it and rolls the run back whole (§8.6).

### 8.8 The keyset pager and a killed, resumed chunked job: (v), as a model

⚠ **There is no migrator yet.** `run-q0.sh pager` exercises §5.3's cursor rule, its
`migration_done` record and its verification count on the design's tables, with a stand-in
migration: bump a row's first byte, drop one row in twenty. A row migrated twice or skipped shows
in the bytes. The store has 150,000 rows, 120,049 of them of the step's types, **one at
`Long.MIN_VALUE`** (the smallest hash there can be, so also the smallest present) and one at
`Long.MAX_VALUE`.

| Check | Result |
|---|---|
| First page with no lower bound, then `hash > ?`; page sizes 1,000 and 7 | **PASS**: 120,049 of 120,049 visited, the `Long.MIN_VALUE` row among them |
| The same pager started at a `Long.MIN_VALUE` sentinel | visits 120,048: **it skips that row**, the bug §5.3's "none" cursor avoids |
| The job killed **inside a chunk** (SIGKILL after the chunk's statements, before its `COMMIT`), twice, and resumed by a new process each time | **PASS**: verification count 0; `migration_done` 120,049 of 120,049; dropped 5,986 of 5,986; **every surviving row migrated exactly once** (114,063), none twice, none of another type touched; both edge rows done; `integrity_check` ok |
| A budget that cuts most pages short (the cursor is the last row **taken**, not the page's last) | covered by the above: every chunk took a random 1 to 700 rows of its 1,000-row page |
| The verification's counting statement at 120,049 rows | 17 ms (Mac) |
| WAL during the run | never above 874 frames. ⚠ Says little: this model's row cap ended every chunk before the budget did |

### 8.9 The corrupt-store snapshot and the sidecar rule: (ad), (q)

Mode `damage`. The store: 5,000 rows in `objects.db`, 200 more only in its WAL (1,142 frames),
1,362 pages. Each case is a fresh copy with one thing overwritten, then every path §6 would try,
each on a new connection. In every case **the originals were byte-identical afterwards**.

| Damage | First read | `quick_check` | `VACUUM INTO` | `sqlite3_backup` (the driver's `backupTo`) |
|---|---|---|---|---|
| none | ok, 5,200 rows | `ok` | ok | completes; the copy has 5,200 rows and checks `ok` |
| **16 B-tree pages overwritten** in `objects.db` | the schema reads; a scan fails, `SQLITE_CORRUPT` (11) | **reports it** (`Tree 3 page 690: btreeInitPage() …`) | **fails**, 11 | **completes** (1,362 pages); the copy carries the same damage, as a page copy must |
| **The 100-byte header overwritten, no WAL** | `SQLITE_NOTADB` (26) | 26 | 26 | **fails**, 26: the byte copy is what is left, as §6 says |
| The header overwritten, **a good page 1 in the WAL** | ok, 5,200 rows | `ok` | ok | completes. ⚠ The damage is not seen at all while the WAL holds page 1 |
| **`-shm` replaced by garbage** (nobody connected) | ok, 5,200 rows | `ok` | ok | completes: the index is rebuilt from the WAL |
| ⚠⚠ **The `-wal` header overwritten** (8 bytes of its salts) | ok, **5,000 rows** | **`ok`** | ok | completes, 5,000 rows |
| ⚠⚠ **One frame in the middle of the `-wal` overwritten**, or the file cut short there | ok, **5,102 rows** | **`ok`** | ok | completes, 5,102 rows |

⚠⚠ **A damaged `-wal` is silent** (§11, 4): SQLite reads the log up to the first frame whose
checksum fails and treats the rest as never written. No call returns an error and `quick_check`
says `ok`, while 200 (or 98) committed rows are gone.

| (ad) check, with the sibling in the other process | Result |
|---|---|
| `BEGIN EXCLUSIVE` here; the sibling runs a `PASSIVE` checkpoint | **PASS: it does not stop it.** The sibling's checkpoint reports `busy=0, log=1142, checkpointed=1142` and `objects.db` is rewritten under the "exclusive" transaction. The sibling can also read; only its `BEGIN IMMEDIATE` is busy. `BEGIN EXCLUSIVE` in WAL mode is `BEGIN IMMEDIATE`, as §6 says |
| The byte copy's fingerprint, under a writer hold | **PASS**: identical 300 ms apart with nothing running; identical across the byte copy; **changed** by a sibling's `PASSIVE` checkpoint during the hold. A sibling's **read** did not change it in this run |
| The byte copy of `objects.db` + `-wal` under the hold, opened | **PASS**: 5,500 of 5,500 rows |

| (q) | Result (mode `fsync`) |
|---|---|
| `fcntl(F_FULLFSYNC)` on a directory descriptor | **accepted**: rc 0 (and `fsync` rc 0), on the Mac's APFS. The launch on the phone that confirms it there ran in the warm batch 1 (recorded on the unmerged branch `s0b-q0-device-batch1`, not on master; §9) |
| The sidecar rule's sequence (temporary file, `F_FULLFSYNC`, `rename`, `F_FULLFSYNC` the directory), ×50 | every call rc 0 |

### 8.10 The device-only modes, dry-run in the simulator app

Run once each in the bench app on the simulator, **only to show the mode works**. None of these
is an answer.

| Mode | What the simulator showed | Why it is not the answer |
|---|---|---|
| `hold-write`, then `hold-report` (the App Group build) | the store was created under `…/Containers/Shared/AppGroup/…/bench/q0/hold/`, the process held `BEGIN IMMEDIATE` until it was terminated, and the next launch found the store clean with the uncommitted row gone | the simulator does not suspend an app or apply `0xdead10cc` |
| `protection` | every file reports no protection class. ⚠ One real observation: the backup-exclusion flag set on `replica/` reads `true` on the directory and **`false` on `objects.db`, `-wal` and `-shm`**: it is an attribute of the item it was set on, not inherited as a readable flag | no Data Protection in the simulator. On the phone, expect the same `false` on the files: what excludes them is the directory (*GK*, Apple's `isExcludedFromBackup` documentation), so (h)'s read-back of the flag on `-wal` cannot be the test |
| `bgtime` | about 29 s after `beginBackgroundTask`, the expiration handler at 4 s remaining | the allowance is the phone's and the OS version's |
| `keychain` | ten items added; one `SecItemCopyMatching` with `kSecMatchLimitAll` **and** `kSecReturnData` returns all ten with their data (status 0, about 0.5 ms); an update of a missing item is `errSecItemNotFound`, an add of a duplicate `errSecDuplicateItem` | the app's own access group on the Mac's simulator; the shared group and the phone's timings are open |

### 8.11 ⚠⚠ Simulator timings: the Mac's, NOT the device's

**None of these is a value for a placeholder.** They are here to show the `timing` and `fsync`
modes run, and for the shape (what grows with what). The Mac is an M4 Pro with an NVMe disk on
which `fsync` does not reach the medium; the phone's `T`, `R` and `S` are different numbers (measured warm in batch 1: recorded on the unmerged branch `s0b-q0-device-batch1`,
not on master; `quick_check` cold is §13.2).
All warm, foreground, one run, p50 (max).

| Item | Measurement | Mac |
|---|---|---|
| (e) | an empty `BEGIN IMMEDIATE` / `COMMIT`, n=1,000 | 1.5 µs (6 µs) |
| (z) | the `COMMIT` of a single-row transaction at `synchronous=FULL`, n=1,000 | 33 µs (229 µs); p99 78 µs |
| (z) | `F_FULLFSYNC` on a small file / on a directory, n=50 | 4.0 ms (5.9 ms) / 4.4 ms (6.9 ms) on a quiet disk; 0.33 s at worst while the soak was syncing |
| (l) | `stat` of the marker, present / absent, n=10,000 | 1.7 µs / 1.1 µs |
| (ac) | a `ROW_MAX` insert: the statement / its whole transaction, n=50 | 92 µs / 0.56 ms (3.4 ms) |
| (ac) | `ROW_MAX` over `ROW_MAX`: the statement / its whole transaction, n=50 | 169 µs / 0.64 ms (3.2 ms) |
| (ab) | a point read at `cache_size=256` / at SQLite's default, n=10,000 | 2.1 µs / 2.0 µs |
| (i) `T(w)` | `PASSIVE` checkpoint of 750 / 1,500 / 3,000 / 4,030 frames, n=5 | 9.5 / 15 / 26 / 61 ms (458 ms once at 4,030) |
| (i) `T(w)` | `RESTART`, no reader, the same sizes | 8.8 / 16 / 37 / 32 ms |
| (s) `R(w)` | the first open and read with nobody connected (it rebuilds the index), the same sizes | 0.65 / 1.1 / 2.0 / 2.8 ms; a second open beside it: 0.07 to 0.09 ms |
| (m) | `quick_check` at 2,125 / 8,274 / 20,097 / 50,115 pages | 1.9 / 10 / 26 / 70 ms: linear, about 1.3 µs per page cached |

Shape worth carrying to the phone: the index rebuild is small beside the checkpoint at the same
WAL size (2 ms against 26 ms at 3,000 frames here), and both grow about linearly with `w`.

## 9. Device-only: what has run, what is pending, and the commands

(Written on 2026-10-05 as "prepared, not run", before anything here had been launched on the
phone. The inventory below is the current state; the table after it is unchanged and is the
record of how each run is made.)

**Run on the device:**

| Item | Result is in |
|---|---|
| (g) flash-cold opens and reads, after a reboot | §13.3 |
| (u) the one-shot wipe against `page_count`, cold after a reboot; the chunked wipe as a model | §13.1 |
| (m) `quick_check` against `page_count`, cold after a reboot | §13.2 |
| (b) `hold-write`: `0xdead10cc` on a held write lock in the bench App Group | §13.5 (⚠ the delay after backgrounding is not known) |
| The warm batch 1 (2026-10-05): (e), (i), (s), (z), (ac), (ab), (l), (m) warm, (t)'s bound, (q), (h) and (aa) in the app's own container and access group, `V(n)`, the warm wipe | ⚠ **recorded on the unmerged branch `s0b-q0-device-batch1` (its §12), not on master**; nothing in this file holds those figures |

**Still pending:**

| Item | What is missing |
|---|---|
| (c) `hold-idle` and `hold-read` | an idle open WAL connection, and an open read transaction, held while backgrounded |
| (o) `bgtime` | `backgroundTimeRemaining` right after backgrounding |
| (h) `protection` **in the group container** | the protection class read back with `BENCH_GROUP=1` |
| (aa) the Keychain calls in the **shared access group** | needs an entitlement the throwaway app must not have: the App Group build of S0b-4, or a bench pair |
| (w), (y) the real-store census | the largest row and the largest relation array in the real apps' stores |
| (b) the delay | how long after backgrounding the kill comes: one more `hold-write` round with the moment of backgrounding recorded |

⚠⚠ **Do not repeat the large-store runs without a reason.** The modes that build 20,000 to
250,000-page stores and write at full rate (`wipe`, `coldcheck`, `timing`, `checkpoint`, the
commit-pace and `fsync` loops) **wrote on the order of 150 GB to the phone's flash in batch 1**.
Their results are recorded (§13 here, §12 on the unmerged branch `s0b-q0-device-batch1`); re-run one only to answer a question those
do not, and at the smallest size that answers it.

Each line of the table below says why the simulator cannot
answer it, the mode that is ready, the exact command, and what that command does on the phone.
None of them runs `evict`; `run-bench.sh` still refuses the plan that does (§5). The phone must be
unlocked. `TENSHU_TEAM_ID=752LWAZMKZ` is the team the gate-zero run used.

**Common to all of them:** the command builds the release framework and the bench shell, installs
**only** `app.tenshu.storebench.ios` (its own container, no Tenshu group), and launches it once
per mode. Without `BENCH_GROUP=1` the build has no entitlements file at all, as before.

```bash
# every command below is this line with the modes and options filled in
TENSHU_TEAM_ID=752LWAZMKZ BENCH_PLAN=q0 BENCH_Q0_MODES="<mode> …" [BENCH_Q0_OPTS="k=v;k=v"] \
  spike-ios-store/run-bench.sh device 1
```

| Item | Why the simulator cannot answer | Modes and options | What it does on the phone |
|---|---|---|---|
| **(b)** `0xdead10cc` on a held write lock | it is what iOS does to a **suspended** app holding a lock on a file in a **shared** container | `BENCH_GROUP=1`, `BENCH_Q0_MODES="hold-write"`; afterwards `run-bench.sh device-crashlogs`, then `BENCH_GROUP=1 BENCH_Q0_MODES="hold-report"` | ⚠ **First use of the bench-only App Group** (`group.app.tenshu.storebench`, in `ios/StoreBenchGroup.entitlements`): Xcode's automatic signing has to register it on the team, **not tried**. Creates a 2,000-row store in that group's container, opens `BEGIN IMMEDIATE`, and holds it, rewriting a heartbeat file every 100 ms. **Someone then presses Home.** Expected within seconds: iOS terminates the app, the attached console ends, and the script reports that launch as not finished, which here is the result. `device-crashlogs` lists the phone's crash reports (no launch). `hold-report` relaunches, prints how long the lock was held and checks the store opens clean |
| **(c)** an idle open WAL connection, and an open read transaction | the same | as (b) with `hold-idle`, then `hold-read` | the same, holding no transaction (only the lock every open WAL connection keeps), then a read mark. Informational: the design closes every connection at quiesce |
| **(e), (i), (s), (m), (z), (ac), (ab)**: `T(w)`, `R(w)`, `S`, the `ROW_MAX` insert and rewrite p99, the empty transaction, `quick_check` against size, reads at `cache_size=C` | they set placeholders (`Wmax`, `T`, `R`, `S`, `B_ckpt`'s parts, the `quick_check` bound), which are the phone's flash and CPU | `BENCH_Q0_MODES="timing"` (options: `rows=150000`, `wal=750,1500,3000,4030`, `reps=5`, `check_pages=2000,8192,20000,50000`) | builds a 150,000-row store (about 150 MiB) in the app's own container, then: 1,000 empty transactions; 1,000 single-row commits at `FULL`; 50 `ROW_MAX` inserts and 50 rewrites; 10,000 point reads at each cache size; for each WAL size 5 `PASSIVE` and 5 `RESTART` checkpoints and 5 first-opens (the index rebuild) against 5 second-opens; then four more stores (8 to 200 MiB) for `quick_check`. Several minutes, about 500 MiB of files. ⚠ Everything is warm; ⚠ foreground, not "protected background time", which (z) asks for and which needs S0b-2's tokens |
| **(u)** the one-shot wipe's time against `page_count` → `N_time` | the time is reading every page the wipe frees, from flash (§8.7) | warm: `BENCH_Q0_MODES="wipe" BENCH_Q0_OPTS="sizes=20000,50000,100000,250000"`. Cold: the same with `;build_only=1`, **reboot the phone by hand**, then with `;reuse=1` | builds four stores, 80 MiB to 1 GiB (about 1.7 GiB kept until the app is removed), and wipes a copy of each four times: as found, then read through first at three cache sizes. With `reuse=1` after a reboot the "as found" wipe is the flash-cold one. Then 60 chunks of the modelled chunked wipe |
| **(u)** `V(n)` → `n_time` | a time on flash | `BENCH_Q0_MODES="vacuum"` (`pages=50000`, `n=64,257,1000,5000`) | one 200 MiB store, copied twice; `incremental_vacuum(n)` after a wipe and after deleting every other row |
| **(t)** the interrupt's bound on a running checkpoint; **(ac)** interrupt latencies | the fact is §8.6; the bound is the phone's | `BENCH_Q0_MODES="interrupt"` (`wal_frames=60000`) | builds a 40,000-row store and a **240 MiB WAL** (deliberately far past the ceiling), interrupts 24 checkpoints and the statements of §8.6, then checkpoints it all. About 400 MiB |
| **(q)**, and (l), (z)'s `F_FULLFSYNC` | (q) says "on the device" | `BENCH_Q0_MODES="fsync"` | `F_FULLFSYNC` on a directory descriptor, the sidecar sequence ×50, 20,000 `stat`s. Under a minute, a few kilobytes |
| **(g)** flash-cold reads after a reboot | the phone's flash, and a reboot | `BENCH_PLAN=load spike-ios-store/run-bench.sh device 1`, **reboot by hand**, then `BENCH_PLAN=cold spike-ios-store/run-bench.sh device 1` (no `BENCH_Q0_MODES`) | `load`: the spike's 150,000-row load into the two SQLite stores. `cold`: two read launches per store; the first after the reboot is the cold sample. ⚠ **No `evict`**: this replaces the step that dropped the phone off three times (§5). The script cannot tell whether the reboot happened |
| **(h)** protection class and backup flag | the simulator has no Data Protection | `BENCH_Q0_MODES="protection"` (with `BENCH_GROUP=1` to read them in a group container, which is where the store will live) | creates `replica/` with `completeUntilFirstUserAuthentication` and the backup exclusion, lets SQLite create `objects.db`, `-wal` and `-shm` in it, and prints each file's protection class and flag. ⚠ See §8.10: the flag will read `false` on the files |
| **(o)** `backgroundTimeRemaining` right after backgrounding | the allowance is iOS's on the phone | `BENCH_Q0_MODES="bgtime"`, then **press Home** | prints the value at the notification, after `beginBackgroundTask`, once a second, and in the expiration handler; exits after 40 s. ⚠ "Inside each wake type" is not covered (§10) |
| **(aa)** the Keychain calls' time | the phone's Keychain | `BENCH_Q0_MODES="keychain"` | adds ten generic-password items in the app's **own** access group, reads them back in one call with their data ×50, updates one ×50, deletes them. ⚠ Not the shared access group (§10) |
| **(w), (y)** the largest row and the largest relation array in the real stores | real data, in the real apps' containers, which the bench app cannot reach and must not | **no mode.** A listing, no launch: `xcrun devicectl device info files --device <udid> --domain-type appDataContainer --domain-identifier app.tenshu.<app>.ios`, the store being `Documents/objects/` (one `.obj` per row today) | reads the file listing of one Tenshu app's container. ⚠ **Not tried**; whether the listing carries sizes is not known. If not, `devicectl device copy from … --source Documents/objects` copies the store to the Mac (the user's real data, so their call). "By type" needs a decoder that does not exist as a tool. The Android and desktop half of (y) is one query on their `objects.db`: `SELECT max(length(data)) FROM objects WHERE type = 'tenshu/core/list'` |

## 10. Not measurable yet

| What | Needs | Picked up by |
|---|---|---|
| (d), (l) a sibling **fencing itself** on the epoch change, and on `replica/CORRUPT` | `SharedReplicaStore`'s access path (§3.1 step 0) | **S0b-1** (`iosTestCheck`), then S0b-3 on the bench pair |
| (ad) every checkpoint path refusing once the marker exists; a checkpoint begun just before the marker ending within `CKPT_MAX` | the store's checkpoint step and its deadline | **S0b-1** |
| (v) the migrator: chunk times, WAL under `Wmax` across a real migration, a kill in every phase | the chunked migrator and `meta.migration` | **S0b-1** (the two-step chain test in its gate) |
| (u) the chunked wipe behind `meta.wipe`, resumed on a `REPLACEMENT` handle | the one wipe function | **S0b-1** |
| (x), S21: the store's own accounting (`fits`, `TransactionBudgetExceeded`, the after-commit `stat`) holding across real workloads | the store | **S0b-1** |
| (p) the p99 time a read mark is held by an ordinary access → `B_ckpt`; (ac) the p99 of ordinary transactions against `HOLD_MAX` | the store under a real session | **S0b-2** (its long-hold log), confirmed in S0b-4's device step (e) |
| (z) the sync times **in protected background time**; (o) `backgroundTimeRemaining` inside each wake type | the gate's tokens and the scheduler's wakes | **S0b-2**, and S1 for the wakes |
| (aa) the Keychain items in the **shared** access group; that no Keychain call runs under the SQLite lock | the watermark's publish path, and an entitlement the throwaway app must not have | **S0b-1** (the seam-fake tests), then the App Group build of **S0b-4** |
| (b), (c) with **two apps**, and every "bench pair" device step of §13 | a second bench app on the bench group. Not built in this round: one app with the group entitlement is enough for (b) and (c) | when S0b-1's device step needs it |
| (x) the B-tree fill assumption behind `G` | `dbstat`, which this build omits (`SQLITE_ENABLE_DBSTAT_VTAB` is not in §2.1.2's list) | open: a bench-only build option, or page walks. `G` held with a wide margin anyway (§8.5) |

## 11. Where the simulator run contradicts or corrects the design

Each is a fact about SQLite 3.53.4 as built here, with the mode that shows it and, where it was
read, the line in the vendored `sqlite3.c`. **The design's stated facts are corrected in place;
its mechanisms are not changed here.** The first two needed a decision, ⭐ **taken on 2026-10-06:
every wipe is chunked and the one-shot wipe is removed** (§13.4; design §5.1, D13). They are kept
below as measured, as the evidence for it.

1. ⚠⚠ **`sqlite3_interrupt` does not reach the one-shot wipe.** §4.2.1 and §5.1 said of the
   unconditional `DELETE FROM objects`: "one statement; interrupt rolls it back", and gave it to
   the hold watchdog ("on firing it is rolled back whole"). Measured (`interrupt`, seven runs): **0 of
   42** interrupts sent during the statement had any effect; it ran to its end and returned
   `SQLITE_OK`, and the interrupt was lost. *Read:* the truncate optimisation is one `OP_Clear`
   per B-tree, and `clearDatabasePage` (`sqlite3.c:83436`) walks the whole tree with no interrupt
   check. A multi-row `DELETE … WHERE` on the same table **is** interrupted (8 of 8), so this is
   the optimisation, not deletes. **What bounds a one-shot wipe is therefore its size alone**
   (`page_count ≤ N_time`): neither the hold watchdog nor a token's expiry can stop one in flight.
   (The phone agrees, 0 of 6, §13.1; the one-shot wipe and `N_time` are since removed, §13.4.)
2. ⚠⚠ **The one-shot wipe's time is the time to read every page it frees, so it depends on the
   cache and on the disk, not only on `page_count`.** `clearDatabasePage` loads each page of each
   tree. With the file in the OS cache it costs **0.5 µs per page** (Mac: 9 ms at 20,158 pages,
   48 ms at 100,068; the phone's gate-zero figure is the same rate, 11 ms at about 20,000 pages,
   §3). With the pages **not** in the cache it cost **15 to 17 µs per page on a quiet Mac** (0.32 s
   at 20,158 pages, 4.2 s at 250,099) and **100 to 370 µs per page while another process was
   syncing on the same disk** (2.0 s at 20,158 pages, 92 s at 250,099; §8.7). `N_time` = 35,000
   pages was extrapolated from one warm sample that also carried `secure_delete` (§3), at 14 µs
   per page. Cached, the real rate is 25× better than that. Uncached on a quiet Mac it is about
   that. Uncached with the disk busy, 35,000 pages at the measured rates is 3.5 to 13 s, far past
   the 1 s hold ceiling, and by (1) nothing can stop it. ~~The phone's uncached rate is not measured (§9, (u)); `N_time` has to
   come from it, and from what else may be writing at the time.~~ ⭐ **Historical since
   2026-10-06.** The phone's uncached rate **is measured**: about **14 µs per page** cold after a
   reboot, **22 µs at 250,099 pages** (§13.1). And the requirement is gone with what it served:
   every wipe is chunked, the one-shot wipe is removed and `N_time` is retired (§13.4, design D13).
   Nothing here asks for the large-store runs again (§9's warning).
3. ⚠ **An interrupt with nothing running is not a no-op for a checkpoint.** §4.2.1 said
   "`sqlite3_interrupt` with no statement running is a no-op and does not affect statements
   started later". True of statements. But `sqlite3_wal_checkpoint_v2` tests the flag per frame
   (`sqlite3.c:69826`) and clears it only on its way out (`:189985`), so a stale interrupt makes
   the connection's next checkpoint call return `SQLITE_INTERRUPT` if it has frames to copy
   (`interrupt`, and a driver test). Harmless in effect (§5.2.2: "skipped, retried"), and it means
   an interrupt that arrives a moment *before* a checkpoint starts still stops it.
4. ⚠⚠ **A damaged `-wal` produces no verdict at all.** Not a stated fact that was wrong; a case
   §5.2.2 and §6 do not have. Overwriting the `-wal`'s header, or one frame, or cutting the file
   short: every open succeeds, `quick_check` says `ok`, `VACUUM INTO` and the backup complete, and
   the rows committed after the damaged point are gone (200 of 200, or 98 of 200; `damage`). SQLite
   cannot tell a torn log tail from one that was never written. So "committed at
   `synchronous=FULL`" survives power loss but not damage to the log, and no `CORRUPT` marker is
   ever written for it. ⭐ **Accepted by the maintainer as a residual, 2026-10-06** (design
   §15.1 R5), with a lost-commit check at open built in S0b (design §5.4): detection and a log line only;
   recovery is an open design item there.

Smaller corrections, none of which changes a verdict:

5. **`PRAGMA auto_vacuum=INCREMENTAL` on an existing store is a write transaction.** It takes the
   write lock (`SQLITE_BUSY` after the 50 ms timeout under a sibling's transaction) and appends
   one frame rewriting page 1, each time (`opensettings`; *Read:* `sqlite3.c:145622`, the pragma
   opens a write transaction to set the incremental flag). §5.2 step 2a issues it on a fresh store
   only, but the loser of a create race issues it against the store the winner just made: in
   `createrace` it is where the loser waited, and it returned `SQLITE_BUSY` 7 times in the rounds
   where the creator held its transaction 80 ms. So it belongs under the same retry as
   `BEGIN IMMEDIATE`, which the harness gave it (20 of 20 races ended clean). No other per-open setting takes a lock on an existing WAL store,
   `journal_mode=WAL` included.
6. **The read-only open that cannot build `-shm` returned `SQLITE_CANTOPEN` (14), not
   `SQLITE_READONLY_CANTINIT`**, in the one way this run could produce it (a read-only directory
   with no `-shm`; `readonly`). §5.2.2 sends both codes to a retry, so the verdict stands; only
   the name `StoreUnclassifiable` is keyed to a code this case did not return.
7. **A read-only open creates a zero-length `-wal`** when the database is in WAL mode and has
   none (`readonly`). §5.2 said steps 1–2 "never change `objects.db` or `-wal`": they never change
   `objects.db` or an existing `-wal`. With `NO_CKPT_ON_CLOSE` the store never removes its `-wal`,
   so this needs an outside hand.
8. **The wipe's frame estimate is one trunk page short per 170 trunks.** §5.1 counted 1,022 leaf
   entries a trunk; SQLite fills a trunk only to `usableSize/4 − 8` = 1,016 (`sqlite3.c:80135`),
   so one trunk per **1,017** freed pages, not 1,023. Measured: 54 frames against an estimate of
   54 at 20,158 pages, 121 against 121 at 50,130, **231 against 230** at 100,068 (§8.7). Otherwise
   the estimate is exact, at every cache size.
9. **`RESTART` holds the writer lock only while its call runs.** §4.2.1 said "`RESTART` takes the
   writer lock itself, so no sibling commits while it is pending". Between two busy 50 ms slices a
   sibling's `BEGIN IMMEDIATE` succeeds (`checkpoint`). What keeps the log under the ceiling during
   the wait is writer rule 1 (each writer checks the size first), not `RESTART`'s lock.
10. **A `RESTART` slice is not always a 50 ms wait: while another connection is checkpointing it
    returns `SQLITE_BUSY` at once.** §4.2.1 has the ceiling checkpoint "repeated in 50 ms slices",
    with a busy slice simply retried. SQLite never waits for the checkpoint lock: a second
    checkpointer gets `SQLITE_BUSY` immediately and the busy handler is not called (*Read:*
    `sqlite3.c:71860`, and sqlite.org/c3ref/wal_checkpoint_v2.html). Seen while the corrected soak
    (§8.1) was being written: unslept, the slice loop spun about **ten million times in 30 s** (a
    trial run, not kept). In the hour's run, with the pause, 98.9 % of the writers' busy slices
    were of this kind. So the loop needs its own pause between slices, as §4.2's `BEGIN` loop
    has; the harness sleeps 1 ms after a slice that came back in under 5 ms.
11. **Q0 (h) as written cannot be checked on the files:** the backup-exclusion flag reads `false`
    on `objects.db`, `-wal` and `-shm` when it was set on `replica/` (§8.10, simulator). What can
    be read back per file is the protection class.

## 13. Device, the cold halves (2026-10-05, after a reboot)

(§12, the warm device batch, is on the unmerged branch `s0b-q0-device-batch1`; this section is numbered to follow it.)

| | |
|---|---|
| Phone | iPhone 17e, **iOS 26.6.1** |
| SQLite, driver | **3.53.4**, the vendored build and the store's own driver, as §1 |
| App | the bench app in **its own container** (no App Group) |
| State | **after a reboot**: the phone restarted by hand, unlocked once, then nothing opened before each run. The stores were built before the restart and not read since. Foreground, on the cable. One launch per mode, so **each figure is one sample** |
| Raw output | not in the repository: `spike-ios-store/build/results/device-cold/`, `device-cold-wipe/` and `device-cold-check/` on the Mac that ran it |

### 13.1 The one-shot wipe, cold: (u)

Mode `wipe` with `reuse=1`; "as found" is the flash-cold sample, "read first" the same store after
one read through. Frames match §8.7 and the design's estimate within one.

| `page_count` | Rows | Frames | **As found (cold)** | Read first (`cache_size` 256) |
|---|---|---|---|---|
| 20,158 (79 MiB) | 86,248 | 54 | **284 ms, 14.1 µs per page** | 8.7 ms (0.4 µs) |
| 50,130 (196 MiB) | 211,752 | 121 | **717 ms, 14.3 µs** | 21.8 ms (0.4 µs) |
| 100,068 (391 MiB) | 421,288 | 231 | ⚠ **1,437 ms, 14.4 µs** | 46.8 ms (0.5 µs) |
| 250,099 (977 MiB) | 1,058,632 | 561 | ⚠⚠ **5,526 ms, 22.1 µs** | 184 ms (0.7 µs) |

- **About 14 µs per page uncached (22 µs at the largest size), about 0.4 to 0.9 µs cached**, the same two rates as the warm
  batch's uncached clone and as the Mac (§8.7). The statement's time is the time to read every
  page it frees; a reboot does not make it worse than an uncached clone did, and nothing makes it
  better than reading the file.
- ⚠⚠ **So an uncached one-shot wipe holds the shared write lock for seconds, and nothing can stop
  it** (§11 (1): 0 of 42 interrupts on the simulator, 0 of 6 on the phone): past the 1 s hold
  ceiling from about 70,000 pages, 5.5 s at 250,099.
- **The chunked wipe, as a model, in the same launch** (60 chunks on the 250,099-page store):
  every chunk took its 1,000 rows; **38 ms a chunk at p50, 52 ms at most**, none over 200 ms; the
  `-wal` never passed 966 frames (within `Wmax`); 19 resets. Extrapolated to the whole store
  (1,058,632 rows): about **41 s**. The warm batch measured 45 ms at p50, 56 ms at most, about
  49 s. ⚠ A model: the real chunked wipe is S0b-1's.

### 13.2 `quick_check`, cold: (m)

Mode `coldcheck` with `reuse=1`; pass 1 is the cold sample, passes 2 and 3 are the same file again.

| `page_count` | **Pass 1 (cold)** | Passes 2 and 3 (cached) |
|---|---|---|
| 20,091 | **423 ms, 21.1 µs per page** | 25.1 / 24.6 ms (1.25 µs) |
| 50,116 | **1,028 ms, 20.5 µs** | 62.5 / 62.1 ms (1.24 µs) |
| 100,131 | ⚠ **2,155 ms, 21.5 µs** | 127.5 / 128.2 ms (1.27 µs) |

**About 21 µs per page cold against about 1.25 µs cached.** The design's size bound for
`quick_check` (finish within 1 s, §6) is therefore near 47,000 pages (~185 MiB) on this figure;
the 32 MiB placeholder is inside it. Not set here.

### 13.3 Cold opens and first reads: (g)

`BENCH_PLAN=cold`, the spike's 150,000-row stores (with and without the type index), two launches
each; the first launch per store is the cold one.

| Store | Launch | Open | First read | 10,000 point reads, p50 / p99 |
|---|---|---|---|---|
| `sqlite` | 1 (cold) | 8.5 ms | 0.59 ms | 3.2 / 231 µs |
| `sqlite` | 2 | 10.1 ms | 0.15 ms | 3.8 / 53 µs |
| `sqlite_idx` | 1 (cold) | 11.7 ms | 0.42 ms | 4.0 / 198 µs |
| `sqlite_idx` | 2 | 7.3 ms | 1.51 ms | 4.1 / 53 µs |

**A cold open is 7 to 12 ms and a first read 0.15 to 1.5 ms**; the cold launch shows in the read
tail (p99 about 200 µs against about 53 µs), not in the open.

### 13.4 What was decided from this (2026-10-06)

§11 (1) and (2) asked for a decision: an uninterruptible statement whose time is set by the cache
and the disk, guarded only by a size switch. **The maintainer decided on 2026-10-06 that every
wipe is chunked and the one-shot wipe is removed** (design §5.1, §14 D13). `N_time` is retired;
the wipe-time-against-`page_count` figures above stay as the evidence for that decision and are
no longer an input to any placeholder.

### 13.5 Q0 (b): a write lock held in a shared container is terminated with `0xdead10cc`

The bench app's `hold-write` round, **in the bench-only App Group** (`group.app.tenshu.storebench`,
so the store is in a shared container): a 2,000-row store, `BEGIN IMMEDIATE` opened and held, one
uncommitted row written, the app then sent to the background.

| | |
|---|---|
| Result | ⭐ **iOS terminated the process with `0xdead10cc`** (the crash report on the phone, 2026-10-05 19:13:44) |
| The store afterwards (`hold-report`, the next launch) | **clean**: it opens, **2,000 rows**, the uncommitted row gone, the `-wal` at 0 bytes (`-shm` 32,768 bytes). The kill released the lock and the WAL rolled the open transaction back, as the design's F4 says |
| How long it held the lock | about **27 minutes** in total (`held_for_s=1633`) |
| ⚠⚠ **Not known** | **how long after backgrounding the kill came.** The moment the app was backgrounded was not recorded, so the 27 minutes say nothing about the delay; no figure for it should be read from this run |

So Q0 (b) is answered: Apple's rule applies to a SQLite write lock on a file in an App Group
container on this phone and this iOS, and the store survives the kill intact. (c), the idle
connection and the open read transaction, is not run.

⚠⚠ **The same code was found in the phone's crash logs for the real apps**, which are App Group
builds sharing one replica under the current hand-built lock (not this design's store):
**`DriveIos` three times on 2026-10-06 (04:31, 04:48, 05:19) and `PhotosIos` once on 2026-10-05
(17:27)**, each terminated by RunningBoard with `0xdead10cc`; in the Photos report two threads
are inside `SharedReplicaDatabaseLock.withLock` and the main thread is in a query. So the hazard
the design's §4 is built against is observed with the shipping apps, with a plain `flock`.
⭐ **The maintainer accepts it until S0b ships** (2026-10-06).
