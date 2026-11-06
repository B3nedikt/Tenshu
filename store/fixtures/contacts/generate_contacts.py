"""Builds Tenshu Contacts' store-screenshot fixture from store/fixtures/contacts/manifest.json.

    python3 store/fixtures/contacts/generate_contacts.py <out dir>
    python3 store/fixtures/contacts/generate_contacts.py --check      validate only, write nothing

Writes <out>/contacts.vcf — one vCard 3.0 file, the shape Apple's and Google's exports have — which
Contacts' `-seedFixture` launch imports through the shared repository's `importVcards`, the op every
Tenshu Contacts import calls (docs/how-to/store-screenshots.md §3c).

Refuses a manifest that could put a real person's details in a store listing: a phone number outside
the ranges reserved for fiction, an email address outside example.com / .org / .net, or an
accidental duplicate (the app's duplicate finder compares phones on their last nine digits and
emails case-insensitively — anything but the two deliberate pairs would change what it shows).

⚠ Nothing it writes is committed: store-screenshots.md §3 ("Nothing binary under store/").
"""

import json
import os
import re
import sys

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


def vcard(person):
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
    if len(argv) != 2:
        fail("usage: generate_contacts.py <out dir> | --check")
    manifest = json.load(open(os.path.join(HERE, "manifest.json"), encoding="utf-8"))
    people = manifest["people"]
    validate(people)
    if argv[1] == "--check":
        print(f"generate_contacts: ok ({len(people)} people)")
        return
    os.makedirs(argv[1], exist_ok=True)
    out = os.path.join(argv[1], "contacts.vcf")
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("".join(vcard(p) for p in people))
    print(f"generate_contacts: {len(people)} people → {out}")


if __name__ == "__main__":
    main(sys.argv)
