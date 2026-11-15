# Desktop tray liveness — the menu bar is Control's only visible organ

**Status:** v0.2 (2026-09-02). Phases TL-1…TL-5 built and shipped in one PR; **TL-V passed on a
real restart — see §6.1, which also records what it did *not* establish.** No gate covers the
login path, and none can.
Extends [`desktop-macos-integration.md`](desktop-macos-integration.md) §4 (Phase 1, the
`NSStatusItem` agent) and §6a (Phase 4 packaging), which introduced the tray but left its
supervision to a LaunchAgent that no longer runs.

**Touches:** `:control-desktop` (`Main.kt`'s `ensureTrayRunning`, the Swift `TrayAgent`
sources), `:control-shared` (a new pure decision function + its tests). No schema, no wire
format, no relay — House Rules #1/#2/#9 are not in play.

---

## 1. The observation

2026-08-31, reported as *"Control desktop seems to no longer start"*. It was running. What
had gone missing was the **menu-bar icon**, and for a daemon that boots hidden
(`TENSHU_CONTROL_START_HIDDEN=true`) that is the same sentence: the tray is the only thing
a user can see or click. There is no window, no Dock tile, and no log they can read.

The process table, taken before anything was touched:

```
tray-agent      pid 24912   started Wed Aug 26, 15:00
TenshuControl   pid 12929   started     Aug 30, 19:29
```

**The tray was four days older than the Control that was supposedly running it.** Control
had restarted at some point on Aug 30 — plausibly via the tray's own *Open Control*, which
cold-launches the bundle through LaunchServices — and on that start it deliberately did
not spawn a tray, because one was already alive.

⚠ What that agent's icon was doing at the time is **not** recorded and cannot now be
recovered: the process was killed during the reinstall that resolved the symptom. §3 keeps
the two candidate explanations apart rather than picking one, and §5 is designed so that
the next occurrence answers the question instead of re-opening it.

## 2. Why nothing replaced it

`ensureTrayRunning()` (`control-desktop/.../Main.kt`) guards on **presence**:

```kotlin
if (LaunchAgentManager.forTray().isInstalled()) return   // launchd owns it
if (isProcessRunning(tray.absolutePath)) return          // pgrep -f
runCatching { ProcessBuilder(tray.absolutePath).start() }
```

Each guard is individually right, and together they leave the tray unsupervised:

- It is **one-shot**, called once during Control's startup. There is no retry and no later
  re-check, so any tray death or degradation after that instant persists until Control
  itself is restarted.
- The **first** guard defers to a supervisor that is not there. `LaunchAgentManager.forTray()`
  is `KeepAlive=true` — real supervision, and the original design (§4 task 2). But that
  agent is *legacy*: the Settings *Start at login* toggle **uninstalls** it, only
  the daemon plist `app.tenshu.control.plist` is installed by default, and on this machine
  the label is additionally `disabled` in launchd's per-user database. So the branch that
  says "launchd has this handled" is never taken, and the supervision it defers to does not
  exist.
- The **second** guard treats *a process exists* as *the tray works*. Those came apart here.
  `pgrep` cannot see a menu bar.

The net effect: **one Control generation's tray outlives it and permanently suppresses the
next generation's**, and nothing in the system can tell that a tray process is doing its job.

## 3. Two candidate causes, and why one mechanism settles both

**C1 — the agent was alive but wedged or iconless.** Nothing in the tray's own code removes
its status item: it is created once in `applicationDidFinishLaunching` and never touched
again. The poll runs off-main and treats an unreachable daemon as a deliberate no-op, and
`BridgeClient` dials a fresh socket per call, so a Control restart cannot wedge it. That
argues *against* a self-inflicted disappearance and toward something external — a lost
menu-bar slot, or a hang somewhere not visible in the sources read.

**C2 — the icon was never placed, at login.** `RunAtLoad` fires Control very early in the
login session. The tray it spawns calls `NSStatusBar.system.statusItem(...)` immediately,
and a status item that cannot be placed — the menu bar not yet ready, or full — leaves the
app running with nothing on screen. `NSStatusItem.isVisible` reports the app's *intent*, not
the system's placement, so the agent has no idea. With `ensureTrayRunning` never retrying, a
slot lost at login is lost until the next manual Control restart.

⚠ C2 matches the reported shape (*present when started by hand, missing after a login*) but
so does C1 plus an unlucky restart. **They are not distinguishable from the evidence that
survives**, which is the actual problem to fix first.

The design below does not pick between them. Both reduce to one question the system cannot
currently answer — *is there a tray process, and does it have an icon?* — so both are served
by making the tray **publish that answer** and by making Control **act on it**.

## 4. Design

### 4.1 The tray publishes a heartbeat carrying its visibility

A 10-second timer in the tray's `AppDelegate` writes one line to
`<socketDir>/tray-agent.state`:

```
1756800000000 visible
```

— epoch millis, then `visible` or `hidden`, where visibility is
`statusItem.button?.window != nil`: whether AppKit actually placed the item in the menu bar,
not whether the app asked it to. Written atomically, so a torn read is not possible.

The file answers both halves at once. Its **freshness** proves the process's run loop is
turning (C1); its **verdict** proves the icon exists (C2).

⚠⚠ **A level destroys its own history, and that is the wrong shape for the question this doc
exists to answer.** The file above is rewritten every tick, so it says *is the icon there now*.
If placement fails at login and §4.3 recovers it ten seconds later, the level reads `visible` by
the time anyone looks, and the login race erases itself — the manual pass in §6 would come back
"fine" having observed nothing. So the tray also keeps an **append-only placement journal**,
`<socketDir>/tray-agent.placement.log`, with one line per *change* of verdict:

```
1788352944806 hidden  unplacedTicks=1
1788352964806 visible unplacedTicks=0
```

One line means it placed immediately and stayed. Two mean C2 happened and healed — which is the
finding, and it survives being looked at late. Tray-owned and single-writer, deliberately not
§4.4's log, which Control read-modify-writes at startup.

⚠ `<socketDir>` is the directory Control's bridge socket already lives in, and the tray is
now told it explicitly: Control passes `TENSHU_IPC_DIR=<resolved dir>` in the spawned tray's
environment. Both sides resolve the socket independently today (`SocketPathResolver` from
system properties + env on the JVM side, env only in Swift) and agree only because neither
is configured. Deriving the heartbeat from a *second* independent resolution would add a
drift class for free; passing the directory down removes it for the socket too.

### 4.2 Control's guard becomes liveness, not presence

`ensureTrayRunning` keeps its shape and gains a verdict, computed by a **pure function**
(`TrayLivenessDecision`, in `:control-shared` so it is unit-testable away from a menu bar):

| Observed | Decision |
|----------|----------|
| no tray binary / tray LaunchAgent installed | `Skip` — unchanged |
| no tray process | `Spawn` |
| process, heartbeat fresh, `visible` | `Skip` — the healthy path |
| process, heartbeat stale or absent | `Replace` — wedged, or a pre-heartbeat build |
| process, heartbeat fresh, `hidden` | `Replace` — alive but no icon |
| process younger than the grace period | `Skip` — hasn't had time to write one |

`Replace` kills **by pid** (from `pgrep -f`, never by name) and spawns a fresh agent.

⚠ The grace period exists so a tray spawned seconds ago is never killed for not having
written yet — without it, an old tray build plus a fast Control restart is a kill loop.
Staleness is `3 ×` the heartbeat interval; the grace period is the same, read from
`ps -o etimes=`.

⚠ A tray from **before** this change writes no heartbeat and so reads as `Replace` exactly
once, on the first Control start after the upgrade. That is correct and self-clearing.

### 4.3 The tray re-places an icon it lost

The same 10-second tick that writes the heartbeat checks placement, and when the item has no
window it removes and recreates it. Attempts back off (1, 2, 4, 8 … capped at 30 ticks) so a
genuinely full menu bar costs one attempt every five minutes rather than one every ten
seconds, and never gives up — a slot that frees later is then taken.

This is the only part of the design that is **speculative**: it is a fix for C2, which is
unconfirmed. It is cheap, it cannot fire when the icon is present (the check is
`window == nil`), and if C2 is wrong it will simply never run. Its real value is that it
records what it saw in the heartbeat either way.

### 4.4 The decision is written down where launchd cannot swallow it

Control appends one line per startup decision to `<socketDir>/tray-agent.log` (capped at 200
lines):

```
2026-09-02T09:14:03Z spawn        no tray process
2026-09-02T09:31:55Z skip         heartbeat 4s old, visible
2026-09-02T10:02:11Z replace      heartbeat 41s old (stale), pid 24912
```

⚠⚠ This is the point of the phase, not a nicety. The daemon's stdout under launchd goes
**nowhere** — `log show --predicate 'process == "TenshuControl"'` returns nothing — which is
precisely why §1 has a process table and a guess instead of a cause. Every other route
(`:diagnostics`, the `core-logging-facade` two-stream design) is a live track of its own;
this is deliberately a dumb capped file that ships now and can be deleted when that lands.

### 4.5 The tray starts before the proxy, not after it

`ensureTrayRunning()` was called late in `main()` — after `startKoin` built the whole proxy
graph, after the store opened, after both socket servers. It needs none of that: the tray dials
the bridge when it wants something and already treats an unreachable daemon as a no-op.

⚠⚠ Measured while verifying this PR: one Control start reached that line **43 minutes** after
launch (a cold store open following an unclean shutdown; two other starts on the same build
took 15 s and ~30 s). At login, 43 minutes of no icon is not "slow" — it is indistinguishable
from the tray never coming up, and it is a fourth, entirely mundane way to produce the §1
report that neither C1 nor C2 covers.

Moved to the first statement of `main()`. The icon is then up in milliseconds, and a slow or
wedged proxy costs the user *visibility of* Control rather than Control itself.

⚠ This also changes what a missing icon means, which is the point: after this change, no tray
at login is a tray problem, because nothing else can delay it.

### 4.6 A tray that dies after start-up — Quit, reopen, relaunch (2026-11-08)

§4.2 runs once, at start-up. A tray that dies *later* left a Control that was running, hidden,
holding the instance lock, with nothing on screen — and every attempt to open it went nowhere.
Reported as "after quitting Control once I cannot start it again". Three defects, all fixed:

1. **The tray's Quit lost its own message.** It sent `quitApp` on a background queue and called
   `NSApp.terminate` at once; the tray usually exited first. Quit now waits (bounded, 2 s) for the
   bridge call before terminating.
2. ⚠⚠ **Opening a running app starts no second process.** Finder, Spotlight and `open -a` send
   the running Control a *reopen* event, so the instance guard never sees them, and AWT ignores
   the event by default. Control now handles it: show the window and re-run `ensureTrayRunning`.
   A launch that *does* start a process (another install channel, `open -n`) defers and sends
   `openControlWindow`, which revives the tray in the same way. ⚠⚠ **Only the lock holder
   ever spawns a tray**: a deferring copy that spawned its own raced the holder's tray when two
   channels started together (the first heartbeat lands ~2 s after spawn). Revival is also
   gated on the shared heartbeat (`TrayLiveness.isHealthy`), since another channel's tray is
   invisible to the path-based `pgrep`, and serialized within the process.
3. ⚠⚠ **The shutdown after `application { }` never ran.** Compose's default
   `exitProcessOnExit = true` calls `exitProcess(0)` the moment the block returns, so
   `shutdownAndJoin`, socket removal and the lock release were dead code; Cmd+Q / Dock Quit /
   logout went through AWT's default quit handler (`System.exit`) and skipped them too. Now
   `exitProcessOnExit = false`, a quit handler routes macOS quits through `requestQuit` and
   answers `performQuit()` after the shutdown, and the shutdown is bounded (10 s) — an unbounded
   join that hangs would recreate the hidden-Control-holding-the-lock state.

⚠ The tray spawn discards stdout/stderr: nobody reads the default pipes, and a tray that
outlives its parent would die of SIGPIPE on its next `NSLog`.

## 5. Phases

- **TL-1** — Swift: heartbeat writer + visibility probe (§4.1).
- **TL-2** — Swift: placement re-check and recreate with backoff (§4.3).
- **TL-3** — Kotlin: `TrayLivenessDecision` + `ensureTrayRunning` acting on it, `TENSHU_IPC_DIR`
  passed to the child (§4.2).
- **TL-4** — Kotlin: the capped decision log (§4.4).
- **TL-5** — Kotlin: the tray starts first, ahead of the proxy boot (§4.5).
- **TL-V** — the on-device pass: log out or restart, then read the two files (§6).

## 6. Verification

**What the gate proves:** `TrayLivenessDecision` is a pure function over
(process present, process age, heartbeat age, heartbeat verdict) and is unit-tested for every
row of §4.2's table, including the grace period and the absent-heartbeat upgrade case.
`scripts/run-gate.sh` covers it.

**What only a build proves:** the Swift half compiles solely through
`:control-desktop:packageControlApp` (nothing else in the repo compiles Swift), which also
asserts the helpers are embedded and the bundle is Team-signed.

**⚠⚠ What nothing here proves:** that the icon survives a login. The tray agent has no test
target, `NSStatusItem` placement cannot be exercised headlessly, and C2 is a hypothesis about
a race in the login session specifically. **TL-V is a manual pass and the PR is not verified
without it.** After a restart:

```bash
cat ~/.tenshu/tray-agent.placement.log  # ⭐ read this FIRST — it is the only one that keeps history
                                        #   one `visible` line   → placed at once, nothing to see
                                        #   `hidden` then `visible` → C2 CONFIRMED and self-healed
                                        #   `hidden` alone       → C2, still unrecovered
cat ~/.tenshu/tray-agent.state          # the level *right now*; a healed race already reads visible
cat ~/.tenshu/tray-agent.log            # what Control decided at startup, and why
```

⚠ The icon itself is the fourth instrument and the bluntest: present immediately is a pass,
*appearing a few seconds late* is §4.3 catching a failure, and that lateness is exactly what the
journal turns from an impression into a record.

### 6.1 TL-V result — passed, and it did **not** settle C1 vs C2

Restarted 2026-09-02 14:49. The icon was there.

```
798  TenshuControl  started 14:49:32     ← launchd RunAtLoad
920  tray-agent     started 14:49:40

tray-agent.placement.log:  1788353382726 visible unplacedTicks=0
tray-agent.log:            2026-09-02T12:49:39.949Z spawn   no tray process
```

⚠⚠ **`unplacedTicks=0` means the icon placed on the first attempt, so §4.3 never fired and C2
did not occur on this boot.** The login path is verified end to end — launchd → Control → tray →
placed — and the journal proved it records that durably. It is *not* evidence that C2 was the
original cause, and a single clean boot cannot be: C2 is a race, and races are not disproved by
one non-occurrence. **§3 stays open.** What is closed is the failure actually reproduced — a
stale tray suppressing its own replacement (§1) — which §4.2 fixes and the SIGSTOP test
demonstrates. If the icon vanishes again, the journal now names which of the two it was.

⚠ **The tray was up 8 s after Control, not "milliseconds"** as §4.5 predicts. That prediction was
measured from a warm `launchctl kickstart`; a cold login adds JVM startup *before* `main()` runs
at all, and the login-time load average was 30. §4.5's claim is still right about what it
removed — the store's cold-open latency, the 43-minute case — but the floor is JVM boot, not
zero. **8 s at login is the expected shape; treat a future 8 s as normal, not a regression.**

That is the discriminator §1 could not make. Whatever it says is worth folding back into this
section, because it closes a question that has now cost two sessions.

## 7. Open questions

- **Q1 — should the tray get its supervisor back?** A `KeepAlive=true` LaunchAgent is the
  idiomatic answer to "this process must always be up" and was the original design. It was
  abandoned for good reasons (the default-on *Start at login* change: the Settings toggle owned the same label, and a live
  `load` of a `RunAtLoad` agent boots a duplicate daemon), but the replacement — a one-shot
  spawn from a process that itself may be dead — is strictly weaker. Worth revisiting under a
  *separate* label that Settings does not touch.
- **Q2 — is the 10-second tick right?** It is inherited from the existing pending-request
  poll rather than reasoned from first principles. Nothing here needs 10 s; 30 s would cost a
  third as many wakeups and still detect a dead tray well inside a Control restart cycle.
- **Q3 — should `Replace` also fire outside startup?** Today Control only asks at boot, so a
  tray that dies at 11:00 stays dead until the next login. A cheap periodic re-check would
  close that, at the cost of Control policing a process it does not own.
