#!/usr/bin/env python3
"""Every first-party app must register with Control at LAUNCH, not on first use.

An app becomes visible and manageable in Control when its client opens a session: the announce
is what `appRegistrationTrigger` fires on. But `wireSchemaAnnounce` only *installs*
`sessionPreflight`, which rides the session's FIRST CALL — so an app whose opening screen issues
no operation never announces, stays unregistered, and is invisible in Control while its transport
sits there connected, looking healthy from every outside angle.

Mia shipped exactly that (docs/tech-debt.md, 2026-08-14): installed, signed, resolved by the
catalogue, answering correctly — and absent from Control's Apps list until its first message.

No Gradle module can check this: the wiring lives in each app's `-shared-ui` and nothing links
them all. Hence a source gate, the same shape as this repo's other cross-cutting checks.

Compliance is either:
  * `launchScope = …` passed to `wireSchemaAnnounce` (the shared mechanism), or
  * an `announce()` call from something the app builds at launch — Mia's root view model.

⚠ PENDING lists apps that predate the rule. The gate fails when a NEW app is non-compliant, and
also when a PENDING app becomes compliant without being removed here, so the list prunes itself
instead of rotting.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Apps that announce only on first use. Shrink this; never grow it.
#
# ⭐ Empty since 2026-08-14: every first-party app announces at launch. An addition here is a
# regression, not a todo — the whole point of the rule is that an app nobody has opened is still
# registered, and therefore still visible and manageable in Control.
PENDING: set[str] = set()


def announces_at_launch(app: str) -> bool:
    for module in ROOT.glob(f"{app}-shared-ui/src/**/di/*ClientModule.kt"):
        text = module.read_text(encoding="utf-8")
        for call in re.findall(r"wireSchemaAnnounce\((.*?)\n\s*\)", text, re.S):
            if "launchScope" in call:
                return True
    # Mia's shape: the app root calls announce() itself.
    for source in ROOT.glob(f"{app}-shared-logic/src/**/*.kt"):
        if re.search(r"\.announce\(\)", source.read_text(encoding="utf-8")):
            return True
    return False


def main() -> int:
    apps = sorted(
        p.name.removesuffix("-shared-ui")
        for p in ROOT.glob("*-shared-ui")
        if list(p.glob("src/**/di/*ClientModule.kt"))
    )
    wiring = [a for a in apps if any(
        "wireSchemaAnnounce" in m.read_text(encoding="utf-8")
        for m in (ROOT / f"{a}-shared-ui").glob("src/**/di/*ClientModule.kt")
    )]

    compliant = {a for a in wiring if announces_at_launch(a)}
    missing = [a for a in wiring if a not in compliant and a not in PENDING]
    fixed = sorted(a for a in PENDING if a in compliant)

    print(f"first-party apps announcing at launch: {len(compliant)}/{len(wiring)}")
    for app in sorted(wiring):
        state = "launch" if app in compliant else ("pending" if app in PENDING else "MISSING")
        print(f"  {app:<10} {state}")

    failed = False
    if missing:
        failed = True
        print(
            "\nFAIL: these apps announce only on first use, so they are invisible in Control "
            "until someone uses them:\n  " + ", ".join(missing) +
            "\nPass `launchScope` to wireSchemaAnnounce, or announce from the app root."
        )
    if fixed:
        failed = True
        print(
            "\nFAIL: these now announce at launch but are still listed as PENDING — "
            "remove them from the list so it keeps meaning something:\n  " + ", ".join(fixed)
        )
    if not failed:
        print("\nOK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
