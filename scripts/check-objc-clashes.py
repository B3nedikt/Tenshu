#!/usr/bin/env python3
"""Find Obj-C name clashes among the modules an iOS app framework exports.

Why this exists (`ios-fit-timeline-maps-parity.md` D12, `photos-ios.md` PIO-D6): Kotlin/Native
names every exported public top-level class, interface and object by its SIMPLE name in the
generated Obj-C header. When two exported modules declare the same simple name, K/N resolves the
clash by appending `_` to whichever fully-qualified name sorts LATER — silently renaming a type a
shipped Swift shell already spells. Nothing fails until a Mac compiles that shell. The fix is for
the newcomer to carry `@ObjCName("<App><Type>")`, and this script finds every place that needs one
without a Mac.

It reads one app framework's export set — the base list every app framework exports (the root
`build.gradle.kts`, `iosAppFrameworkBaseExports`), the module's own `export(project(":x"))` lines,
and the module itself (`--framework`; `ios-per-app-frameworks.md` §5.2) — plus any `--add` modules
(the ones a PR is about to export), scans each module's `commonMain` / `iosMain` / `appleMain` /
`nativeMain` Kotlin for public top-level declarations and file facades (`<File>Kt`, which exist
whenever a file has a public top-level function or property), applies `@ObjCName`, and reports
every Obj-C name declared by more than one fully-qualified owner. Without `--framework` it checks
every app framework (each `tenshu-sdk-ios-<app>` directory but `-core` and `-region`), one at a
time: a clash exists only inside one framework's export set, never across apps.

⚠ A lexical scan, not a compiler: it does not see nested classes (whose Obj-C name is the
parent's plus their own) or declarations produced by other plugins, so a clean run is necessary,
not sufficient — the Mac pass (`ios-smoke.sh --build-only`, every app) is still the proof.

⚠ The app frameworks already carry clashes (3 or 4 each on 2026-10-15, all inside the base exports
or Drive's own: `CoreCommonModuleKt`, `Get`, `NativeReferenceDataDto`, `DrivePreferences`; the old
shared umbrella had 15). Those ship, and the Swift that compiles today spells their `_` names, so a
bare run LISTS them and exits 0: renaming one now would itself break a shell. The gate is for a
module being added or changed — pass it with `--add` or `--only-involving`.

Usage:
  scripts/check-objc-clashes.py                                   # list every app framework's clashes, exit 0
  scripts/check-objc-clashes.py --framework :tenshu-sdk-ios-control  # one app framework's clashes
  scripts/check-objc-clashes.py --add :photos-shared-logic        # before exporting a new module
  scripts/check-objc-clashes.py --only-involving :photos-shared-logic   # after: has it gained one?

Exit status: 1 when a clash involving an --add / --only-involving module is found, else 0.
"""

import argparse
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT_BUILD = os.path.join(ROOT, "build.gradle.kts")
BASE_EXPORTS_RE = re.compile(r"val iosAppFrameworkBaseExports = listOf\((.*?)\n\)", re.S)
SOURCE_SETS = ("commonMain", "iosMain", "appleMain", "nativeMain")

EXPORT_RE = re.compile(r'^\s*export\(project\("(:[^"]+)"\)\)', re.M)
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)
OBJC_NAME_RE = re.compile(r'@(?:kotlin\.native\.)?ObjCName\(\s*(?:name\s*=\s*)?"([^"]+)"')
# A top-level declaration starts at column 0. Modifiers before the keyword are free-form.
DECL_RE = re.compile(
    r"^((?:[a-z]+\s+)*)(class|interface|object|fun\s+interface)\s+`?([A-Za-z_][A-Za-z0-9_]*)"
)
TOP_LEVEL_MEMBER_RE = re.compile(r"^((?:[a-z]+\s+)*)(fun|val|var)\s")
HIDDEN = {"private", "internal"}


def exported_modules(framework):
    """The base exports every app framework carries, [framework]'s own, and [framework] itself."""
    with open(ROOT_BUILD, encoding="utf-8") as fh:
        base_block = BASE_EXPORTS_RE.search(fh.read())
    base = re.findall(r'"(:[^"]+)"', base_block.group(1)) if base_block else []
    with open(os.path.join(module_dir(framework), "build.gradle.kts"), encoding="utf-8") as fh:
        own = EXPORT_RE.findall(fh.read())
    return base + own + [framework]


def app_frameworks():
    """Every `:tenshu-sdk-ios-<app>` module: the ones that build a framework an app links."""
    libraries = {"tenshu-sdk-ios-core", "tenshu-sdk-ios-region"}
    return sorted(
        ":" + name for name in os.listdir(ROOT)
        if name.startswith("tenshu-sdk-ios-") and name not in libraries
        and os.path.isfile(os.path.join(ROOT, name, "build.gradle.kts"))
    )


def module_dir(module):
    return os.path.join(ROOT, *module.strip(":").split(":"))


def strip_block_comments(text):
    return re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)


def declarations(module):
    """Yield (objc_name, fq_owner, path:line) for every exported top-level name in [module]."""
    base = module_dir(module)
    for source_set in SOURCE_SETS:
        src = os.path.join(base, "src", source_set, "kotlin")
        for dirpath, _, files in os.walk(src):
            for name in files:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                with open(path, encoding="utf-8") as fh:
                    text = strip_block_comments(fh.read())
                pkg_match = PACKAGE_RE.search(text)
                pkg = pkg_match.group(1) if pkg_match else ""
                lines = text.splitlines()
                has_facade = False
                pending_objc = None
                for i, line in enumerate(lines):
                    annotation = OBJC_NAME_RE.search(line)
                    if annotation and line.lstrip().startswith("@") and not line.startswith(" "):
                        pending_objc = annotation.group(1)
                    if not line or line[0].isspace() or line.startswith(("//", "@", "import", "package")):
                        continue
                    decl = DECL_RE.match(line)
                    if decl:
                        mods = set(decl.group(1).split())
                        objc = pending_objc
                        pending_objc = None
                        if mods & HIDDEN or "expect" in mods and source_set != "commonMain":
                            continue
                        # `actual` in iosMain restates the commonMain `expect`: same symbol.
                        if "actual" in mods:
                            continue
                        simple = decl.group(3)
                        rel = os.path.relpath(path, ROOT)
                        yield objc or simple, f"{pkg}.{simple}", f"{rel}:{i + 1}"
                        continue
                    member = TOP_LEVEL_MEMBER_RE.match(line)
                    if member:
                        pending_objc = None
                        mods = set(member.group(1).split())
                        if not mods & HIDDEN and "actual" not in mods:
                            has_facade = True
                if has_facade:
                    facade = name[:-3] + "Kt"
                    rel = os.path.relpath(path, ROOT)
                    yield facade, f"{pkg}.{facade}", f"{rel} (file facade)"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--framework", action="append", default=None,
        help="the app framework module to check, e.g. :tenshu-sdk-ios-control (repeatable; default: all of them)",
    )
    parser.add_argument("--add", nargs="*", default=[], help="modules about to be exported, e.g. :photos-shared-logic")
    parser.add_argument(
        "--only-involving", nargs="*", default=None,
        help="report only clashes that involve one of these modules (default: the --add modules, or all)",
    )
    args = parser.parse_args()
    frameworks = args.framework or app_frameworks()
    status = 0
    for framework in frameworks:
        if len(frameworks) > 1:
            print(f"== {framework}")
        status = max(status, check(framework, args))
        if len(frameworks) > 1:
            print()
    return status


def check(framework, args):
    if not os.path.isfile(os.path.join(module_dir(framework), "build.gradle.kts")):
        print(f"unknown framework module: {framework}", file=sys.stderr)
        return 2
    modules = list(dict.fromkeys(exported_modules(framework) + args.add))
    missing = [m for m in modules if not os.path.isdir(module_dir(m))]
    if missing:
        print(f"unknown module(s): {', '.join(missing)}", file=sys.stderr)
        return 2
    gating = bool(args.add) or args.only_involving is not None
    focus = set(args.only_involving if args.only_involving is not None else (args.add or modules))

    owners = defaultdict(lambda: defaultdict(list))  # objc name -> fq owner -> [(module, where)]
    for module in modules:
        for objc, fq, where in declarations(module):
            owners[objc][fq].append((module, where))

    clashes = []
    for objc, by_fq in sorted(owners.items()):
        if len(by_fq) < 2:
            continue
        involved = {module for sites in by_fq.values() for module, _ in sites}
        if involved & focus:
            clashes.append((objc, by_fq))

    if not clashes:
        print(f"no Obj-C name clashes across {len(modules)} exported module(s)"
              + (f" involving {', '.join(sorted(focus))}" if args.add else ""))
        return 0

    print(f"{len(clashes)} Obj-C name clash(es) — K/N renames the later-sorting owner to `<Name>_`:\n")
    for objc, by_fq in clashes:
        ordered = sorted(by_fq)
        print(f"  {objc}")
        for rank, fq in enumerate(ordered):
            fate = "keeps the name" if rank == 0 else "renamed " + objc + "_" * rank
            for module, where in by_fq[fq]:
                print(f"    {fq:70s} {module:28s} {fate:22s} {where}")
        print()
    if not gating:
        print("Listed, not failed: these already ship, and the Swift that compiles today spells the `_`")
        print("names. Gate a new or changed module with --add / --only-involving.")
        return 0
    print("Fix: the NEWCOMER carries @ObjCName(\"<App><Type>\") (parity doc D12) — never the shipped owner.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
