# Public export: a private upstream, a public copy

**Status:** v0.1, 2026-11-05. Decided by the maintainer 2026-11-05: development stays in this
private repository, and the public one receives **summary commits** exported from it. Built:
`scripts/public-export.py` (dry run + `--publish`), its rules and tests, `LICENSE` (Apache-2.0, §6 Q2). Done: the real-places scrub
(§6 Q6), the rename to `TenshuInternal` with the pre-push hook armed (§5 steps 2–4). Not done: the first
cut (§5 step 5). Supersedes the "single cut, then develop in public" plan in an earlier pre-publication
cleanup brief, whose findings it absorbs.

## 1. The model

| | This repository | The public repository |
|---|---|---|
| Name | `B3nedikt/TenshuInternal` (after the rename) | `B3nedikt/Tenshu`, created **empty** |
| Visibility | private, forever | public |
| History | everything, as now | one commit per sync |
| Where work happens | here: PRs, CI, reviews, agents | outside contributions only |

A sync is `scripts/public-export.py --publish <public clone>`. It exports the committed tree,
cleans and checks it, and commits it as one commit on a `sync/<date>-<sha>` branch of a clone of
the public repository. The maintainer runs the gate there, pushes, and merges the PR. The first
sync goes straight onto the empty `master`. ⚠ The public branch is `master`, like this one's: the
site links to `blob/master/…` and the workflows trigger on `master` (`public_branch` in the rules).

**Why not develop in public** (the brief's plan): the tree here carries personal data, real
location traces and operations detail that are useful to the maintainer and must not be public.
With a private upstream they stay where they are, and the export removes them. The cost is that
every sync is a publication, so its checks must be automatic (§3).

**Why not a fork, in either direction:** a fork of a public repository is public, and every
repository in a fork network shares one object store. A commit of the private member can then be
read through the public one by its SHA. The public repository is therefore created empty, and it
only ever receives commits the script made.

## 2. What the export changes

The rules are in `scripts/public-export-rules.json`. That file names the strings it removes, so
the script never exports it, whatever the rules say.

- **Excluded paths:** the rules file itself, plus anything listed under `exclude`.
- **Personal data, replaced:** the private e-mail address and two personal Apple identity ids
  (`desktop-ipc-client-identity.md`, `tech-debt.md`), and the full legal name
  (`desktop-macos-distribution.md`). The maintainer's public name stays where the law asks for
  it: the imprint, the copyright line, the whitepaper's author.
- **Location traces, coarsened to 2 decimals (~1 km):** `ios-apple-maps-option.md`'s route.
  (`timeline-movement-capture.md` stays in the rule but has held no coordinates since §6 Q6.) The internal docs keep full precision,
  because the reasoning in them needs it. The places that recur in that data (the office, real
  Wi-Fi BSSIDs, place ids that encode coordinates) are **not** rewritten: they are forbidden
  (§3), so they block a sync until they are removed at the source (§6 Q6).
- **PR references:** a bare `#NNNN` means a PR *of the repository the text is in*, and the public
  numbering restarts at 1. The export qualifies each one as `B3nedikt/TenshuInternal#1089`, which
  still links for the maintainer and is a dead link for everyone else. Colours (`color:#666`,
  `'#555'`, `#333`) are left alone; ⚠ CSS, HTML, SVG and JSON are not rewritten at all. A forbid
  rule catches a colour rewritten anyway. See §6 Q1 for removing the references instead.

## 3. The gates: every finding is fatal

A sync publishes, so nothing is a warning to read later. On the cleaned tree:

1. **gitleaks** over the files (`gitleaks dir`: there is no history to scan, which is the
   point). Known false positives are allowed by their **exact matched text**, not by their
   file, so a real secret pasted into an allowed test file later still fails. Three paths are
   allowed whole: the vendored `sqlite3.c` and two hash manifests.
2. **Forbidden strings**: the personal data above, the home-directory path, the office's name,
   real Wi-Fi BSSIDs (a standalone run of exactly six pairs, so a certificate or SSH fingerprint
   does not match; synthetic `aa:bb:cc…`, `02:00:00…`, `a4:2b:8c…` pass), place ids encoding
   real coordinates (`cap:48…`), an unrewritten old-name PR link, a colour rewritten as a PR
   reference. A binary or non-UTF-8 file is searched too, decoded four ways. The commit message
   goes through every text gate, review patterns and gitleaks included, and the commit's author
   and committer identity through the forbidden strings.
3. **Forbidden paths**: `terraform.tfvars*` (except `*.example`), `*.tfstate`, `*.p8`, `*.p12`,
   keystores, provisioning profiles, `local.properties`, `.tenshu-gate/`, `*.env`.
4. **File size**: 10 MB. The 23 MB sample photo is deleted in the same change that adds this doc.
5. **Required files**: `LICENSE`, checked by `--publish` (a dry run only reports it missing).

**Review findings** are the one non-fatal class: things a script cannot decide. Today these are
coordinates at 4+ decimals in tests (§6 Q3) and the `repo_url` defaults (§6 Q4).

## 4. The publish step's refusals

`--publish` refuses:

- a clone whose `origin` is not the public repository, or names `Internal`;
- a clone that shares a root commit with this repository. ⚠⚠ This is the leak the whole design
  exists to prevent: pushing any branch from a clone of *this* repository publishes its history;
- a clone that borrows objects (`alternates`), has uncommitted changes, or is **shallow** (on
  either side: roots cannot be compared then). Every check fails closed: an error is a refusal;
- running at all while **this repository's own remotes still name the public one**. After the
  rename, a plain `git push` from here would publish the whole history;
- a sync when **public commits landed after the last sync** (drift). Each sync commit carries an
  `Internal-Source: <sha>` trailer, and only commits with the script's own subjects count, so a
  contributor's commit cannot forge one. Merge commits are not drift, so a sync PR may land as a
  merge. An outside contribution must be brought in here first (`git format-patch` there,
  `git am` here), or the next sync would revert it. `--allow-drift` is for when it already has
  been.

It never pushes. The push and the PR are the maintainer's.

## 5. Runbook

**Every sync:**

```bash
scripts/public-export.py                          # dry run: must end "Dry run clean"
scripts/public-export.py --publish ~/src/tenshu-public
(cd ~/src/tenshu-public && scripts/run-gate.sh)   # the export must build on its own
git -C ~/src/tenshu-public push -u origin sync/<date>-<sha>
```

**The first cut**, once. The maintainer does it, in this order. ⚠⚠ Renaming leaves a redirect, so
until a new repository takes the name, `B3nedikt/Tenshu.git` still reaches the private one. From
the moment the public one exists, any remote still set to the old URL pushes **to the public
repository**.

1. Add the `LICENSE` (§6 Q2) and get a clean dry run.
2. Rename `B3nedikt/Tenshu` → `B3nedikt/TenshuInternal`.
3. Re-point every remote: the main checkout (it covers its worktrees), every other machine, cloud
   environments, scheduled routines. Check each with `git remote -v`.
4. Set `ACTIVE=1` in `.githooks/pre-push` and commit it. The hook refuses a push to the public
   repository's URL from any clone that uses the repo's hooks (`core.hooksPath`). It catches a
   clone missed in step 3. Before the rename it must stay off: the name is still this repository.
5. Create the empty public `B3nedikt/Tenshu`, clone it into a **fresh directory**, run
   `--publish` against it, check `git rev-list --count HEAD` is `1`, push.
6. The deploy modules' `repo_url` and the image workflows: §6 Q4.

## 6. Open questions (the maintainer's)

- **Q1. Decided 2026-11-05, see §8:** remove them at the source. Measured 2026-11-05: 4,319 in the
  exported file types, of which 853 are plain asides (`(#NNNN)`, `(PR #NNNN, #NNNN)`) that can be
  dropped mechanically. The other 3,466 are part of a sentence ("Since #NNNN every app announces",
  "Done in #NNNN") and need rewording by someone who reads them. If they are reworded here, at the
  source, the export needs no transform, and a check stops new ones.
- **Q2. ⚖️ The licence: decided 2026-11-05, Apache-2.0** for the code, open to commercial use.
  The brand and the images are carved out in `TRADEMARKS.md`: the Tenshu name, the app names as
  product names, the logos, and every image/icon file are not licensed. Section 6 of the licence
  already withholds trademarks. **The copyright holder is the maintainer personally** (confirmed 2026-11-05), matching
  the site's copyright line and the imprint's trademark statement.
- **Q3. Coordinates in tests.** Most are landmarks (Marienplatz, Ostbahnhof), and those are fine.
  `RouteCameraTest` held the office's point at 7 decimals; it now uses Marienplatz. The rest is
  Q6.
- **Q4. Decided 2026-11-06: the `git_repo_url` defaults** in `deploy/*/variables.tf` name the
  public `Tenshu.git`. The servers build from what is published, not from the development repo.
  ⚠ Until the first cut the public repository is empty, so a box rebuilt before it cannot clone the
  default: override `git_repo_url` with `TenshuInternal.git` (and a token) in its `terraform.tfvars`
  until then. `git_token_required` stays `true` by default; a token also clones a public repository.
- **Q5. Infrastructure detail.** Server IPs in `deploy/dns/records.json`, `deploy/box-health`,
  `deploy/build-apex-image.sh` and a README are exported as they are. **Decided 2026-11-06: they
  stay.** Anyone can resolve them from DNS, so hiding them protects nothing.

- **Q6. The maintainer's real places, at the source. Done 2026-11-05.** Measured that day (309
  findings): the office (its name, its street, the building beside it, and its grid-cell place id),
  real Wi-Fi BSSIDs, and place ids encoding real coordinates (`cap:48…`), across about 25 test
  files (Wi-Fi fingerprinting, place clustering and merging, the geocoder) and
  `timeline-recognition-and-place-naming.md` / `timeline-movement-capture.md`. As built:
  - **Names**: ⚠ **no invented proper names** for places, streets, businesses or people — a made-up
    name can belong to a real company. Fixtures use plain nouns ("Office", "Street", "Building",
    "Clinic", "Bistro", "Town hall"); docs and comments say "the office", "home", "a station".
    Public landmarks (main stations, Marienplatz) may stay.
  - **BSSIDs** map one-to-one onto synthetic ones (`a4:2b:8c:…` for universally administered,
    `02:00:00:…` for the one locally administered), keeping which addresses share a prefix.
  - **Coordinates**: every fixture holding real places moved by one offset of **whole `0.001°`
    grid cells**, north to just past 49° (out of the forbidden 47–48° band). Whole cells keep every
    `cap:` id, snap and adjacency exactly (both grids, `10_000` and `5_000` E7); north-south metres
    are exact and east-west ones shrink 1.8 % (one longitude step is 73 m, not 74 m). Ids and E7
    values were recomputed, not edited. ⚠ Keep latitude and longitude **positive with the same digit
    count**: a merge cluster's root is the string-smallest id (`PlaceClustering`), and a move into the
    southern hemisphere reversed that order and failed `aLatitudeStepMergesToo`. Fixtures that were
    already synthetic round anchors (`TimelineProjectionTest`, `PhotonGeocoderTest`) and the
    Marienplatz landmark keep theirs.
  - **Docs**: the timeline docs no longer tell how the algorithms came to be on the maintainer's
    real days — venue narratives, per-day tables, BSSID and place-id dumps are gone (git history
    keeps them). Rules, decisions, ⚠ constraints, defect lists and thresholds stay, worded
    generically, under their original section numbers.
  The company's legal address in `web/apex/support-pages/` is public by law and stays.

## 7. Later: an organisation

Transferring the public repository to an organisation keeps issues, PRs and redirects, but not:
the `ghcr.io/b3nedikt/*` images (the image workflows fail until they are renamed, and production
keeps pulling the old path until its box environment changes, which is a production deploy step),
the GitHub Apps, cloud environments, or fine-grained tokens. The licence's copyright holder does
not change with the repository either.

## 8. The comment sweep: present tense, on the API, no PR numbers

Decided 2026-11-05: PR references are not qualified forever, they are removed at the source, and
the comments around them are rewritten to say what the code does. This replaces §6 Q1's
question; until the sweep is done, §2's qualification stays as the export's safety net.

### 8.1 The rules

Code comments (Kotlin, Swift, TypeScript, scripts):

- **C1. Present tense: what it does, and the constraint that is not obvious.** No history: no
  PR numbers, review rounds ("Codex P1", "round 4"), "used to", "previously", "since v3", "was
  renamed", dates or phase names. The exception is history that is still a fact about data
  ("rows written before schema 5 carry no `etag`"): say it as that fact.
- **C2. Keep every trap.** A `⚠`/`⚠⚠` comment guards something that breaks silently. Keep the
  constraint and drop the story of how it was found. If a test pins it, name the test.
- **C3. Contracts live on the API.** What an operation or type promises goes on its declaration:
  the `Operation`s, schemas and `Service` in `<name>-service` / `-common`, public interfaces,
  `ui-core`'s public composables (`ui-agent.md` §5). Implementations (`-proxy`), clients
  (`-client`, `*-shared-logic`, ViewModels, repositories, screens) keep only constraints local
  to them: lock order, threading, why this algorithm. A contract explained in an implementation
  **moves** to the interface, and a contract explained in three places is kept once.
- **C4. Delete** comments that restate the code, claims the code no longer bears out (a removed
  symbol, a removed branch, a "TODO" that is done), and commented-out code.
- **C5. Valid KDoc.** `/** */` directly above the declaration it documents, never two in a row
  (`check-comment-claims.py` finds orphans). `[Symbol]` links resolve from that file. `@param` /
  `@return` / `@throws` name what exists. Inside a function body, `//`. Swift uses `///`, TS uses
  TSDoc `/** */`.
- **C6. Point, don't copy.** Long rationale stays in its design doc. The comment names the section
  (`timeline-movement-capture.md` §32).
- **C7. Tests** say which behaviour they pin, not the history of the bug: "pins the
  delete-during-upload race", not "the #NNNN regression". Assertion messages follow the same rule.
- **C8. Behaviour never changes.** Only comments, assertion-message strings and whitespace move.
  A diff line outside those is a defect in the sweep.

Docs: design docs keep their decisions and as-built notes, but cite decision ids, sections and
tests instead of PR numbers. `tech-debt.md` entries describe the current state, and resolved
entries are deleted.

**PR references by kind** (measured 2026-11-05, 4,329 in 987 files):

| Kind | Count | Becomes |
|---|---|---|
| review provenance ("Codex P1 on #NNNN", "round 4 of #NNNN") | 1,319 | deleted; the finding stays as a fact |
| plain aside "(#NNNN)" | 911 | deleted |
| "landed / fixed / done in #N" | 90 | present-tense fact, or deleted if it was status |
| "since / before / after #N" | 169 | the current behaviour, without the "since" |
| "the #N bug / regression" | 86 | the bug named, or the test that pins it |
| inline prose and tables | 1,979 | read and reworded; PR columns dropped from tables |

Each agent gets a table of every PR's number and title (`gh pr list --state all --json
number,title`), so it can understand a reference before removing it.

### 8.2 Order

1. **Guard**: shipped with this doc. `scripts/check-pr-refs.py` fails any commit whose staged
   files gain a reference (pre-commit hook, `pr-refs-baseline.json`), and `AGENTS.md` states the
   rule. New code stops adding references from today.
2. **Pilot: `contact-service*` + `contacts-shared-logic`** (~16k lines, 25 % comments, including
   review-round provenance in `ContactPhotoStorage`). One PR. The maintainer reviews the tone,
   and the rules above are corrected before anything else is touched.
3. **Rollout, phase A: the 987 files that hold references**, because the export needs them. One
   PR per service family or app, smallest first. `sync-service*` (232 references),
   `inference-service*` (198) and `file-service*` (84) are split by module. The docs go last:
   `tech-debt.md` (322) alone, then the 26 proposals with 20+, then the rest.
4. **Phase B, optional: every other file** (~235k comment lines across 6,900 Kotlin, Swift and
   TS files). Expensive, and nothing waits on it. It can also happen as each area is next worked
   on.

**Each PR:** compile and run the touched modules' tests (comment-only, so a failure means C8 was
broken), `check-comment-claims.py`, `check-doc-claims.py` for docs (⚠ markers must survive),
then `check-pr-refs.py --update` to bank the progress. Keep PRs small and merge them quickly:
the sweep touches files other work is editing.

**Done** when `pr-refs-baseline.json` is empty. Then the check becomes a clean-tree gate, and the
export's `rewrite_refs` stays only as a net.
