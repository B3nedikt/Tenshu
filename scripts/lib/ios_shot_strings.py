#!/usr/bin/env python3
"""The iOS screenshot walks' label table for one app and language (docs/how-to/store-screenshots.md §4).

    ios_shot_strings.py <app-ios dir> <lang> <out.json>

Writes {English label: [labels in <lang>]} — the app's own translations from
`<app-ios>/Resources/Localizable.xcstrings`, then SYSTEM below: the words of Apple's own sheets and
alerts the walks tap, which no catalogue of ours holds. `ShotStrings` (ios-shell/UITestSupport) reads
it through TEST_RUNNER_SHOTS_STRINGS and matches the English label OR any of these, so a wrong or
missing entry here costs a lookup that fails loudly, never a wrong capture.
"""
import json
import re
import sys

# Apple's wording on the iOS 27 simulator, per language. ⚠ Not every entry has been seen on screen:
# a run that fails at one prints the labels it found — correct the entry from that.
SYSTEM = {
    "de": {
        "Allow": ["Erlauben"],
        "Don't Allow": ["Nicht erlauben"],
        "OK": ["OK"],
        "Cancel": ["Abbrechen"],
        "Close": ["Schließen"],
        "Continue": ["Fortfahren", "Weiter"],
        "Not Now": ["Nicht jetzt"],
        "Turn On All": ["Alle aktivieren"],
        "Select All": ["Alle auswählen"],
        # Apple Health's iOS 27 sheet; a LIKE pattern (ShotStrings.labelLike), the count mid-sentence.
        "Select All * Topics": ["Alle * Themen auswählen"],  # seen 2026-10-29
        "All Recorded Data": ["Alle aufgezeichneten Daten"],
        "How much data": ["Wie viele Daten"],
        "Health Access": ["Health-Zugriff"],
        "iCloud Health Data Sync is Off": ["iCloud-Synchronisierung für Health-Daten ist deaktiviert"],
        "Return": ["Return", "Zeilenschalter", "Eingabe"],
        "return": ["return", "Zeilenschalter", "Eingabe"],
        "search": ["suchen", "Suchen"],
    },
}


def main(app_dir, lang, out):
    with open(f"{app_dir}/Resources/Localizable.xcstrings", encoding="utf-8") as f:
        catalogue = json.load(f)["strings"]
    table = {}

    def add(key, value):
        if value and value != key and value not in table.setdefault(key, []):
            table[key].append(value)

    for key, entry in catalogue.items():
        local = (entry.get("localizations") or {}).get(lang, {})
        values = [local["stringUnit"].get("value")] if "stringUnit" in local else [
            v["stringUnit"].get("value") for v in (local.get("variations") or {}).get("plural", {}).values()
            if "stringUnit" in v]
        for value in values:
            add(key, value)
            # "%d possible duplicates" → also "possible duplicates": a walk matches a counted label by
            # its words (`labelEnds`), never by a number it cannot know.
            lead = re.match(r"^%(?:\d+\$)?l{0,2}[dui@] ", key)
            if lead and value and re.match(r"^%(?:\d+\$)?l{0,2}[dui@] ", value):
                add(key[lead.end():], re.sub(r"^%(?:\d+\$)?l{0,2}[dui@] ", "", value))
    table = {k: v for k, v in table.items() if v}
    for key, labels in SYSTEM.get(lang, {}).items():
        table[key] = table.get(key, []) + [l for l in labels if l not in table.get(key, [])]
    with open(out, "w", encoding="utf-8") as f:
        json.dump(table, f, ensure_ascii=False, indent=1, sort_keys=True)
    print(f"ios_shot_strings: {len(table)} labels for {lang} → {out}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
