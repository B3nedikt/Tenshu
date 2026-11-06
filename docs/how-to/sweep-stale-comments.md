# Sweeping stale comments — how to run one, and what it will cost you

A stale comment is worse than no comment: it is read with the authority of the code and it does not
fail anything. This is the runbook for sweeping them at scale — the module split, how much runs in
parallel, and the hazards that are only obvious after they have bitten.

Written after the 2026-09-07 sweep of core + control (193 dangling doc references, ~90 comment
fixes) and the three cold lanes that followed
it. The numbers here are measured, not estimated.

**What a sweep proves.** That a comment and the code beneath it agree *today*. Nothing more — and
the half-life is short: six claims made by the 2026-09-07 sweep were falsified by master within
three days of being written.

**What it does not prove.** That the comment is *useful*, that the design it describes is right, or
that a doc it points at is true. Those are separate passes.

---

## 1. The two halves, and why the order matters

**The mechanical half** — dangling doc citations, orphaned KDoc, comments naming symbols that no
longer exist — is [`check-comment-claims.py`](../../scripts/check-comment-claims.py). Run it
repo-wide **first**. It costs minutes, and it stops every module lane independently re-discovering
the same three defect classes.

**The judgement half** is everything else, and it does not automate. A human or an agent has to
read the comment against the code and decide. Budget accordingly: in the first sweep, five agents
over ~600 files produced ~130 findings for roughly 1.35M tokens.

⚠ The gate is a **baseline** gate, not a clean-tree gate. The known set is banked and only *new*
findings fail. A gate that reports 600 problems on day one teaches everyone to skip it.

## 2. Splitting the work

**Split by coupling, not by size.** An agent that cannot see a whole subsystem cannot tell a stale
comment from a correct one — it has nothing to check the claim against. `timeline-*` is one lane
even though it is large; four unrelated 200-file modules are not one lane even though they are
small.

**Order by decay, not by convenience.** The repo moves ~10 commits/day and not evenly. Sweep the
cold areas first — they stay fixed. Leave whatever is actively churning until last, in small PRs
merged the same day. Doing it the other way round is how a week's work re-stales behind you.

⭐ Cold in this repo has meant: UI primitives, web/JS, the small services. Hot has meant: anything
touching reclamation, the content plane, thumbnails, photos, timeline.

## 3. Parallelism — what actually binds

The audit parallelises freely. **The apply is what binds, and only if you make it serial.**

The first sweep had agents report read-only and applied every fix by hand. That cost a full serial
day and was the bottleneck — not the agents. It does not scale past a handful of lanes, and it does
not need to: lanes are disjoint by module, so agents can apply their own fixes if the verification
is mechanised.

Run each lane in **its own git worktree** (`isolation: "worktree"`). Three agents sharing one
checkout collide on git state even when their files do not overlap.

⚠ The real ceiling is **review**, not compute. Nine PRs landing at once is unreviewable. Three or
four concurrent lanes, merged as they land, is the working number.

### Exit conditions — give every lane these four

1. **The diff is comment-only.** Assert it, do not eyeball it:
   ```bash
   git diff -U0 -- '*.kt' '*.kts' | grep -E "^[+-]" | grep -vE "^[+-]{3}" \
     | grep -vE "^[+-]\s*(\*|//|/\*)"
   ```
   Must print nothing — except declarations that *moved* because a KDoc block moved onto them, in
   which case check the declaration is byte-identical and say so.
2. **Every edited module compiles.** ⚠ Task names differ per module; `compileKotlinJvm` does not
   exist everywhere. Find each with
   `./gradlew :<module>:tasks --all | grep -oE "^compileKotlin[A-Za-z]+" | sort -u`.
3. **The doc gates pass** — see [`../README.md`](../README.md) §Doc gates.
4. **One branch and one PR per lane.**

## 4. ⚠⚠ Hazards, each one paid for

**A comment-only diff is not automatically a safe diff.** A path containing `/*` inside a KDoc —
`/admin/export/*.csv` — opens a *nested* block comment, so the file never closes. That cost lane C
a build and eight cascading unresolved references, from a diff that passed the assertion in §3.1.
The assertion proves *scope*, not correctness. Compile what you edit.

**Do not let parallel lanes edit shared state.** `ALLOW_SYMBOLS` in the gate is one dict, and two
lanes both had entries for it. One added its 20 and conflicted on rebase; the other deliberately
held them back and reported them instead. **The second is the right call.** Have lanes *report*
allowlist additions and baseline re-banks; merge them centrally, once.

**Never re-bank the baseline from inside a lane.** Three lanes running `--update` produce three
conflicting JSON files. A lane's gate run reporting *"N baseline entries no longer reproduce"* is
the expected outcome and the evidence the lane worked.

**Agent worktrees live under `.claude/` — inside the repo.** Any repo-wide scan must exclude them
or it walks N+1 copies of the tree. This inflated one run from 160 orphaned KDoc blocks to 618.

**Past tense is usually correct.** *"They **were** the local `ContactRelationMaintainer`"* is
history, and history is worth keeping. Only fix a comment that names a dead symbol **as if it were
live**. This is the single most common false positive, and a regex will destroy the distinction.

**The symbol check runs roughly 1-in-4 signal.** In the cold lanes, 33 of 44 and 8 of 8 findings
were false positives — platform APIs, prose vocabulary, correct history. It still earns its keep,
but do not hand a lane the list and expect it to be a worklist of bugs.

## 5. What counts as stale

Only what you can back with the specific contradicting code, cited by `file:line` on both sides.

1. A comment stating behaviour the code no longer has; a TODO beside a complete implementation.
2. A comment calling shipped work "planned" / "upcoming" / "will be".
3. A workaround described for a constraint that is gone — a lifted pin, a removed platform limit,
   a fixed upstream bug.
4. KDoc contradicting the signature; two comments in one file contradicting each other.
5. Commented-out code; references to tests that do not exist.
6. ⚠⚠ **Comments that *understate* shipped work.** As common as overstating and more damaging:
   an interface KDoc calling something a stub when production implements it fully will send the
   next reader to build what already exists, or to distrust a security property that holds.

**What does not count**, and must not be "fixed": terse comments, missing KDoc, style preferences,
prose you would have phrased differently. A sweep that rewrites prose is a sweep nobody will run
twice.

## 6. Docs are the other direction

Every lane should also report, without necessarily fixing:

- **(a)** code comments carrying long narrative that belongs in a doc — name the doc and section;
- **(b)** doc statements the code now contradicts;
- **(c)** doc sections superseded or duplicated, naming the source of truth.

⚠ Expect **(b)** in both directions. The 2026-09-07 sweep found a canonical doc still presenting a
disabled, data-loss-causing design as live — and, separately, a doc blaming a blocker that the code
had already closed. Neither direction is rarer than the other.

⚠⚠ Fix a doc's `**Status:**` line when it is provably wrong, but nothing more. Status is
[delegated to the docs on purpose](../../AGENTS.md) — `AGENTS.md` rows no longer carry it — so
those lines now matter more than they did, and
[`check-proposal-status.py`](../../scripts/check-proposal-status.py) asserts they exist. It cannot
assert they are *true*; that is this sweep's job.

## 7. Closing a lane

1. Lane pushes its branch and reports: counts by category, the most interesting findings with
   `file:line`, docs findings, and **what it deliberately left alone, with reasons**.
2. Verify independently — scope, the comment-only assertion, and at least one headline claim per
   lane. Take nothing on trust; agents do overclaim.
3. Merge the lanes, then re-bank the baseline **once**:
   `scripts/check-comment-claims.py --update`.
4. Fold in the allowlist additions the lanes reported.

⭐ The honest closing line for any sweep is what it *did not* reach. "Tiers 1–2 thoroughly, tier 3
not at all" is a better report than a thin pass over everything, and it is what makes the next
sweep cheap to scope.
