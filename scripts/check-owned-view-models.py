#!/usr/bin/env python3
"""Every view model a swept test builds must go through `OwnedViewModels`.

⚠⚠ Why this exists, and why the compiler is not enough. The migration
(`docs/proposals/testing-leaked-scope-guard.md` §4.8) rewrites `= SomeViewModel(…)` into
`vms.own(SomeViewModel(…))` mechanically. The shapes that break that rewrite mostly FAIL TO
COMPILE, which makes the compiler look like a sufficient oracle. It is not: a construction used
*inline* — `assertIs<…>(SomeViewModel(repo).uiState.value)`, `Screen(SomeViewModel(repo))`,
`return SomeViewModel(…)` — is simply never wrapped, compiles perfectly, and leaves exactly the leak
the sweep exists to remove.

That is the same blindness `OwnedViewModels.assertNothingActive()` has by construction (§4.8.1,
row 3: the guard only sees what it was handed). A guard that cannot see an unregistered
construction needs a *static* counterpart, which is this.

⚠ Two constructions are legitimately not wrapped:
  - a Koin / Compose `viewModel { SomeViewModel(…) }` factory, whose instances are resolved into a
    `ViewModelStore` the test hands to `ownStore(…)` — enumerated at close time, so covered;
  - a construction already inside an `own(` call, including across a line break.

Exit 1 on any other construction in a module listed in SWEPT.
"""
import glob
import os
import re
import sys

# ⚠⚠ Anchor to the repository root, not the working directory. A relative glob run from anywhere
# else matches nothing and the scan reports success having read no files — a gate that exists to
# catch "a rewrite that never happened" silently passing because IT never happened. The
# scanned-file assertion below is the belt to this braces: `run-gate.sh` learned the same lesson
# from UP-TO-DATE test tasks reporting green having executed nothing.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Modules whose tests have been migrated. ⚠ Add a module here the moment it is swept — a module
# absent from this list is not checked, which is how the sweep would quietly rot.
SWEPT = [
    'photos-shared-ui', 'photos-shared-logic', 'drive-shared-ui', 'maps-shared-logic',
    'control-shared', 'control-shared-logic', 'fit-shared-ui', 'contacts-shared-logic',
    'timeline-shared-logic', 'mia-shared-logic', 'home-shared-logic', 'fit-shared-logic',
    'calendar-shared-logic', 'timeline-shared-ui', 'mia-shared-ui', 'home-shared-ui',
    'contacts-shared-ui', 'calendar-shared-ui',
]
# The iOS SDK modules, swept 2026-11-11 after an unowned model kept a Contacts test binary from
# exiting (`docs/tech-debt.md`, "Contacts iOS test binary never exits").
SWEPT += sorted(os.path.basename(p) for p in glob.glob(os.path.join(ROOT, 'tenshu-sdk-ios-*'))
                if os.path.isdir(os.path.join(p, 'src', 'iosTest')))

CTOR = re.compile(r'\b([A-Z]\w*ViewModel)\s*\(')

# ⚠⚠ The iOS SDK tests reach most view models through a surface factory, not a constructor —
# `DriveMutationSurface.rename(…)`, `ContactsSurface.contactListViewModel()` — and CTOR cannot see
# those. The factory names are read from the modules' own `iosMain` (a `fun` whose declared return
# type is a `…ViewModel`), so a new factory is covered the day it exists. A factory returning a
# holder (`MoveBound`, `PhotosModelsBinding`) is not matched: its model is owned as
# `.also { vms.own(it.viewModel) }`, which this scan cannot tell from a missing one.
IOS_SDK_GLOB = 'tenshu-sdk-ios-*'
IOS_FACTORY_DECL = re.compile(r'\bfun\s+([a-z]\w*)\s*\([^)]*\)\s*:\s*[A-Z]\w*ViewModel\b')


def ios_surface_factory_call():
    names = set()
    for path in glob.glob(os.path.join(ROOT, IOS_SDK_GLOB, 'src', 'iosMain', '**', '*.kt'), recursive=True):
        names.update(IOS_FACTORY_DECL.findall(open(path, encoding='utf-8').read()))
    if not names:
        return None
    return re.compile(r'\b\w+Surface\.(' + '|'.join(sorted(names)) + r')\s*\(')
OWN_END = re.compile(r'\bown\(\s*$')
KOIN_VM = re.compile(r'\bviewModel\s*(<[^>]*>)?\s*\{[^{}]*$')


def unwrapped():
    out = []
    scanned = 0
    factory = ios_surface_factory_call()
    patterns = [CTOR] + ([factory] if factory else [])
    for module in SWEPT:
        for path in sorted(glob.glob(os.path.join(ROOT, module, 'src', '*Test', '**', '*.kt'),
                                     recursive=True)):
            if path.endswith('ComposeVmTest.kt'):
                continue
            scanned += 1
            text = open(path, encoding='utf-8').read()
            lines = text.split('\n')
            for m in (m for pattern in patterns for m in pattern.finditer(text)):
                before = text[:m.start()]
                n = before.count('\n') + 1
                stripped = lines[n - 1].strip()
                if stripped.startswith(('import ', '*', '//', '/*')):
                    continue
                trimmed = before.rstrip()
                if OWN_END.search(trimmed):
                    continue
                if KOIN_VM.search(trimmed.split('\n')[-1]):
                    continue
                out.append((os.path.relpath(path, ROOT), n, stripped[:100]))
    return out, scanned


def main():
    found, scanned = unwrapped()
    # ⚠ A scan that read nothing is not a pass. Every swept module has test files by definition, so
    # zero means the glob is broken or a module was renamed — either way the answer is worthless.
    if scanned == 0:
        print(f'FAIL — scanned 0 files under {ROOT}. The glob or the SWEPT list is wrong;')
        print('a green from a scan that read nothing is exactly what this gate exists to prevent.')
        return 1
    for path, line, src in found:
        print(f'{path}:{line}  {src}')
    if found:
        print(f'\nFAIL — {len(found)} view model(s) built outside `own(...)` in a swept module.')
        print('Wrap the construction, or hand the composition\'s store to `ownStore(...)`.')
        print('See docs/testing.md §11 and testing-leaked-scope-guard.md §4.8.8a.')
        return 1
    print(f'OK — every view model is owned across {scanned} test files in {len(SWEPT)} swept modules.')
    print('⚠ Presence only: being registered says nothing about whether the test asserts anything.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
