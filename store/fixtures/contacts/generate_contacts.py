"""Builds Tenshu Contacts' store-screenshot fixture from store/fixtures/contacts/manifest.json.

    python3 store/fixtures/contacts/generate_contacts.py <out dir>
    python3 store/fixtures/contacts/generate_contacts.py --check      validate only, write nothing
    python3 store/fixtures/contacts/generate_contacts.py <out dir> --portraits DIR

With --portraits, a person whose `<Given> <Family>.jpg` (or .jpeg / .png / .heic) is in DIR gets it as
the card's embedded PHOTO, scaled to a 512 px long edge and stripped of metadata (`sips`, macOS) —
the path a real address-book export takes into Contacts' avatar. People without a file keep their
monogram, as a real address book has. The portraits are generated people
(docs/proposals/store-screenshot-stories.md §4.1), kept outside the tree, by default in
~/Movies/Tenshu promo/people/contacts; a file matching nobody fails the run, so a typo is not a
silently missing face.

Writes <out>/contacts.vcf — one vCard 3.0 file, the shape Apple's and Google's exports have — which
Contacts' `-seedFixture` launch imports through the shared repository's `importVcards`, the op every
Tenshu Contacts import calls (docs/how-to/store-screenshots.md §3c).

Refuses a manifest that could put a real person's details in a store listing: a phone number outside
the ranges reserved for fiction, an email address outside example.com / .org / .net, or an
accidental duplicate (the app's duplicate finder compares phones on their last nine digits and
emails case-insensitively — anything but the two deliberate pairs would change what it shows).

⚠ Nothing it writes is committed: store-screenshots.md §3 ("Nothing binary under store/").
"""

import base64
import json
import os
import re
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True  # no __pycache__ under store/ (check-store-assets.py rejects binaries)

HERE = os.path.dirname(os.path.abspath(__file__))

# Number ranges set aside for drama and fiction, as digits after the country code.
FICTION = [
    ("1", re.compile(r"^\d{3}55501\d\d$")),            # US/Canada: NXX 555-0100…0199
    ("44", re.compile(r"^7700900\d{3}$")),             # UK (Ofcom): 07700 900000…900999
    ("49", re.compile(r"^3023125\d{3}$")),             # Germany (BNetzA): 030 23125 000…999
    ("33", re.compile(r"^19900\d{4}$")),               # France (ARCEP): 01 99 00 xx xx
    ("61", re.compile(r"^491570(156|157|158|159)$")),  # Australia (ACMA): 0491 570 156…159
]
EMAIL = re.compile(r"^[A-Za-z0-9._-]+@([a-z0-9-]+\.)*example\.(com|org|net)$")


def fail(msg):
    sys.exit(f"generate_contacts: {msg}")


def check_phone(who, number):
    if not number.startswith("+"):
        fail(f"{who}: {number!r} must be international (+cc …)")
    digits = re.sub(r"\D", "", number)
    for cc, pattern in FICTION:
        if digits.startswith(cc) and pattern.match(digits[len(cc):]):
            return
    fail(f"{who}: {number!r} is not in a range reserved for fiction")


def escape(text):
    return text.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")


PORTRAIT_EXTENSIONS = (".jpg", ".jpeg", ".png", ".heic")
PORTRAIT_EDGE = 512


def load_portraits(directory, people):
    """Maps "Given Family" to JPEG bytes for every portrait in `directory`."""
    names = {" ".join(p["name"]) for p in people}
    portraits = {}
    for entry in sorted(os.listdir(directory)):
        stem, ext = os.path.splitext(entry)
        if entry.startswith(".") or ext.lower() not in PORTRAIT_EXTENSIONS:
            continue
        if stem not in names:
            fail(f"portrait {entry!r} matches nobody in the manifest (name it '<Given> <Family>{ext}')")
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "portrait.jpg")
            # sips re-encodes, which drops EXIF/GPS; -Z bounds the long edge.
            subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "82",
                            "-Z", str(PORTRAIT_EDGE), os.path.join(directory, entry), "--out", out],
                           check=True, capture_output=True)
            portraits[stem] = open(out, "rb").read()
    return portraits


def fold(line):
    """vCard 3.0 folding: no line over 75 octets, continuations start with one space."""
    if len(line) <= 75:
        return line
    parts = [line[:75]] + [line[i:i + 74] for i in range(75, len(line), 74)]
    return "\r\n ".join(parts)


def vcard(person, portrait=None):
    given, family = person["name"]
    lines = ["BEGIN:VCARD", "VERSION:3.0",
             f"N:{escape(family)};{escape(given)};;;", f"FN:{escape(given + ' ' + family)}"]
    if person.get("org"):
        lines.append(f"ORG:{escape(person['org'])};")
    if person.get("title"):
        lines.append(f"TITLE:{escape(person['title'])}")
    for label, number in person.get("phones", []):
        lines.append(f"TEL;TYPE={label.upper()};TYPE=VOICE:{number}")
    for label, address in person.get("emails", []):
        lines.append(f"EMAIL;TYPE=INTERNET;TYPE={label.upper()}:{address}")
    for label, parts in person.get("addresses", []):
        lines.append(f"ADR;TYPE={label.upper()}:" + ";".join(escape(p) for p in parts))
    if person.get("note"):
        lines.append(f"NOTE:{escape(person['note'])}")
    if person.get("birthday"):
        lines.append(f"BDAY:{person['birthday']}")
    if person.get("categories"):
        lines.append("CATEGORIES:" + ",".join(escape(c) for c in person["categories"]))
    if portrait:
        lines.append(fold("PHOTO;ENCODING=b;TYPE=JPEG:" + base64.b64encode(portrait).decode("ascii")))
    lines.append("END:VCARD")
    return "\r\n".join(lines) + "\r\n"


def validate(people):
    phones, emails = {}, {}
    for p in people:
        who = " ".join(p["name"])
        for _, number in p.get("phones", []):
            check_phone(who, number)
            phones.setdefault(re.sub(r"\D", "", number)[-9:], set()).add(who)
        for _, address in p.get("emails", []):
            if not EMAIL.match(address):
                fail(f"{who}: {address!r} is not at example.com / example.org / example.net")
            emails.setdefault(address.lower(), set()).add(who)
    shared = [sorted(w) for w in list(phones.values()) + list(emails.values()) if len(w) > 1]
    expected = sorted(sorted(w) for w in ({"Priya Raman", "Priya R."}, {"Tomás Oliveira", "Tom Oliveira"}))
    if sorted(shared) != expected:
        fail(f"the duplicate pairs are {sorted(shared)}, expected exactly {expected}")
    names = [" ".join(p["name"]).lower() for p in people]
    if len(set(names)) != len(names):
        fail("two people share a name — the duplicate finder would pair them")


def main(argv):
    portraits_dir = None
    if "--portraits" in argv:
        i = argv.index("--portraits")
        if i + 1 >= len(argv):
            fail("--portraits needs a directory")
        portraits_dir = os.path.expanduser(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
        if not os.path.isdir(portraits_dir):
            fail(f"--portraits: {portraits_dir} is not a directory")
    if len(argv) != 2:
        fail("usage: generate_contacts.py <out dir> [--portraits DIR] | --check")
    manifest = json.load(open(os.path.join(HERE, "manifest.json"), encoding="utf-8"))
    people = manifest["people"]
    validate(people)
    if argv[1] == "--check":
        print(f"generate_contacts: ok ({len(people)} people)")
        return
    portraits = load_portraits(portraits_dir, people) if portraits_dir else {}
    os.makedirs(argv[1], exist_ok=True)
    out = os.path.join(argv[1], "contacts.vcf")
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("".join(vcard(p, portraits.get(" ".join(p["name"]))) for p in people))
    with_photo = f", {len(portraits)} with a portrait" if portraits_dir else ""
    print(f"generate_contacts: {len(people)} people{with_photo} → {out}")


if __name__ == "__main__":
    main(sys.argv)
