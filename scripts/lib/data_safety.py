#!/usr/bin/env python3
"""Google Play Data safety CSVs, rendered from a short per-app answer file.

    python3 scripts/lib/data_safety.py render     # store/<app>/play/data-safety.json → data-safety.csv
    python3 scripts/lib/data_safety.py check      # fails if a committed CSV is stale or malformed

The CSV is the one the Console's App content → Data safety page exports and imports, and the body
`applications.dataSafety` takes (`play_publish.py data-safety`). Its rows — one per question and per
choice, 782 of them — come from `store/shared/play/data-safety-template.csv`, a blank export of the
current form; the answers come from `store/<app>/play/data-safety.json`, reviewed in
`docs/how-to/play-console-answers.md` §11. Rendering only fills the "Response value" column, so the
question set, its order and its labels stay exactly the template's.

⚠ The template is a blank of a real 2026 export (two independent repos agree on all 782 keys), not
an export of *our* form. Before the first upload, export one app's form from the Console and diff its
first two columns against the template (§12 of the how-to). If Google adds a question, replace the
template with a fresh blank export and re-render.

Answer file:

    {
      "collects": true,                       # PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA
      "encrypted_in_transit": true,           # only when collects
      "account_creation": ["PSL_ACM_NONE"],   # PSL_SUPPORTED_ACCOUNT_CREATION_METHODS choices
      "account_creation_specify": "",         # PSL_ACM_SPECIFY (free text, with PSL_ACM_OTHER)
      "account_deletion_url": "",             # PSL_ACCOUNT_DELETION_URL
      "data_deletion": "DATA_DELETION_YES",   # PSL_SUPPORT_DATA_DELETION_BY_USER choice, or ""
      "data_deletion_url": "",                # PSL_DATA_DELETION_URL
      "outside_app_accounts": false,          # PSL_HAS_OUTSIDE_APP_ACCOUNTS
      "data_types": {
        "PSL_EMAIL": {
          "collected": true, "shared": false, "ephemeral": false, "optional": true,
          "collection_purposes": ["PSL_APP_FUNCTIONALITY"], "sharing_purposes": []
        }
      }
    }

Any key other than `data_types` and the ones above is a note for reviewers and is ignored (`about`,
`why`). Standard library only.
"""

import csv
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
STORE = os.path.join(ROOT, "store")
TEMPLATE = os.path.join(STORE, "shared", "play", "data-safety-template.csv")
HEADER = ["Question ID (machine readable)", "Response ID (machine readable)", "Response value",
          "Answer requirement", "Human-friendly question label"]
PLACEHOLDER = "PLACEHOLDER"

# A Data safety answer that is only true once a deployment switch is thrown. Not a form answer — it
# never reaches the CSV — but `play_publish.py data-safety` reads it beside the CSV and refuses to
# upload until the operator says, with a flag, that the switch is thrown.
UPLOAD_GATE_KEY = "upload_gate"
# Control's "Yes, deletion" answers: the account and email are erased at once, but stored content is
# deleted only once BOTH account-closure flags are open (ACCOUNT_CLOSURE_RELAY_URL on commerce,
# ACCOUNT_CLOSURE_RECLAIM on the relay — retention-quota-model.md §3.3). Override: --closure-flags-open.
# ⚠ Both opened in production 2026-10-24, so Control's data-safety.json no longer carries it; the gate
# stays for the day a flag closes again (and test_data_safety.py pins its absence — edit both).
CLOSURE_FLAGS_GATE = "account-closure-flags"
USAGE = "PSL_DATA_USAGE_RESPONSES"
PURPOSES = {"PSL_APP_FUNCTIONALITY", "PSL_ANALYTICS", "PSL_DEVELOPER_COMMUNICATIONS",
            "PSL_FRAUD_PREVENTION_SECURITY", "PSL_ADVERTISING", "PSL_PERSONALIZATION",
            "PSL_ACCOUNT_MANAGEMENT"}
TOP_KEYS = {"collects", "encrypted_in_transit", "account_creation", "account_creation_specify",
            "account_deletion_url", "data_deletion", "data_deletion_url", "outside_app_accounts",
            "data_types"}
TYPE_KEYS = {"collected", "shared", "ephemeral", "optional", "collection_purposes", "sharing_purposes"}


class DataSafetyError(Exception):
    pass


def read_rows(text):
    rows = list(csv.reader(io.StringIO(text)))
    if not rows or rows[0] != HEADER:
        raise DataSafetyError(f"header is not the Console's: {rows[0] if rows else '(empty)'}")
    for i, row in enumerate(rows[1:], start=2):
        if len(row) != 5:
            raise DataSafetyError(f"row {i} has {len(row)} columns, not 5")
    return rows[1:]


def load_template(path=TEMPLATE):
    with open(path, encoding="utf-8", newline="") as f:
        return read_rows(f.read())


def data_type_ids(template):
    """{data type id: its PSL_DATA_TYPES_* category question}, from the template."""
    return {r[1]: r[0] for r in template if r[0].startswith("PSL_DATA_TYPES_")}


def answers_for(spec, template):
    """{(question id, response id): value} for every cell the answer file fills."""
    unknown = set(spec) - TOP_KEYS - {"about", "why", UPLOAD_GATE_KEY}
    if unknown:
        raise DataSafetyError(f"unknown keys {sorted(unknown)}")
    types = data_type_ids(template)
    out = {}

    def put(question, response, value):
        out[(question, response)] = value

    collects = spec["collects"]
    if not isinstance(collects, bool):
        raise DataSafetyError("collects must be true or false")
    put("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA", "", bool_cell(collects))
    if collects:
        put("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT", "", bool_cell(spec["encrypted_in_transit"]))
    # ⚠ Play's API enforces three rules the form only implies (measured on upload, 2026-10-24), and
    # a spec that breaks one is REFUSED here rather than silently trimmed, so the JSON is exactly
    # what gets uploaded:
    #  1. with collects=false the account and deletion sections are hidden, so any answer to them is
    #     refused ("You cannot answer PSL_SUPPORTED_ACCOUNT_CREATION_METHODS");
    #  2. "Can users log in with accounts created outside the app?" is asked only when the app itself
    #     creates no accounts (PSL_ACM_NONE);
    #  3. DATA_DELETION_YES requires a data_deletion_url ("Response missing for PSL_DATA_DELETION_URL").
    hidden_when_not_collecting = ("account_creation", "account_creation_specify", "account_deletion_url",
                                  "data_deletion", "data_deletion_url", "outside_app_accounts")
    if not collects:
        present = [k for k in hidden_when_not_collecting if k in spec]
        if present:
            raise DataSafetyError(f"collects is false, so Play refuses any answer to: {', '.join(present)}")
    methods = spec.get("account_creation", [])
    for method in methods:
        put("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", method, "true")
    for key, question in (("account_creation_specify", "PSL_ACM_SPECIFY"),
                          ("account_deletion_url", "PSL_ACCOUNT_DELETION_URL"),
                          ("data_deletion_url", "PSL_DATA_DELETION_URL")):
        if spec.get(key):
            put(question, "", spec[key])
    if spec.get("data_deletion"):
        if spec["data_deletion"] == "DATA_DELETION_YES" and not spec.get("data_deletion_url"):
            raise DataSafetyError("data_deletion is DATA_DELETION_YES but no data_deletion_url is set")
        put("PSL_SUPPORT_DATA_DELETION_BY_USER", spec["data_deletion"], "true")
    if "outside_app_accounts" in spec:
        if methods != ["PSL_ACM_NONE"]:
            raise DataSafetyError("outside_app_accounts is only asked when account_creation is [PSL_ACM_NONE]")
        put("PSL_HAS_OUTSIDE_APP_ACCOUNTS", "", bool_cell(spec["outside_app_accounts"]))

    data_types = spec.get("data_types", {})
    if data_types and not collects:
        raise DataSafetyError("data_types listed but collects is false")
    if collects and not data_types:
        raise DataSafetyError("collects is true but no data_types are listed")
    for type_id, t in data_types.items():
        if type_id not in types:
            raise DataSafetyError(f"{type_id} is not a data type in the template")
        if set(t) - TYPE_KEYS or TYPE_KEYS - set(t):
            raise DataSafetyError(f"{type_id}: keys must be exactly {sorted(TYPE_KEYS)}")
        if not (t["collected"] or t["shared"]):
            raise DataSafetyError(f"{type_id}: neither collected nor shared — leave it out instead")
        for kind in ("collection_purposes", "sharing_purposes"):
            bad = set(t[kind]) - PURPOSES
            if bad:
                raise DataSafetyError(f"{type_id}: unknown purposes {sorted(bad)}")
        if t["collected"] != bool(t["collection_purposes"]):
            raise DataSafetyError(f"{type_id}: collection_purposes must be given exactly when collected")
        if t["shared"] != bool(t["sharing_purposes"]):
            raise DataSafetyError(f"{type_id}: sharing_purposes must be given exactly when shared")
        put(types[type_id], type_id, "true")
        prefix = f"{USAGE}:{type_id}:"
        if t["collected"]:
            put(prefix + "PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_COLLECTED", "true")
        if t["shared"]:
            put(prefix + "PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_SHARED", "true")
        put(prefix + "PSL_DATA_USAGE_EPHEMERAL", "", bool_cell(t["ephemeral"]))
        control = "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL" if t["optional"] else "PSL_DATA_USAGE_USER_CONTROL_REQUIRED"
        put(prefix + "DATA_USAGE_USER_CONTROL", control, "true")
        for purpose in t["collection_purposes"]:
            put(prefix + "DATA_USAGE_COLLECTION_PURPOSE", purpose, "true")
        for purpose in t["sharing_purposes"]:
            put(prefix + "DATA_USAGE_SHARING_PURPOSE", purpose, "true")
    return out


def bool_cell(value):
    if not isinstance(value, bool):
        raise DataSafetyError(f"expected true or false, got {value!r}")
    return "true" if value else "false"


def render(spec, template):
    answers = answers_for(spec, template)
    keys = {(r[0], r[1]) for r in template}
    missing = [k for k in answers if k not in keys]
    if missing:
        raise DataSafetyError(f"answers with no template row: {missing}")
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\n")
    writer.writerow(HEADER)
    for q, r, _, req, label in template:
        writer.writerow([q, r, answers.get((q, r), ""), req, label])
    text = buf.getvalue()
    validate(text, template)
    return text


def validate(text, template=None, allow_placeholder=True):
    """Structural checks on a CSV about to be uploaded. Returns a one-line summary."""
    rows = read_rows(text)
    if template is not None and [(r[0], r[1]) for r in rows] != [(r[0], r[1]) for r in template]:
        raise DataSafetyError("question/response rows differ from the template (order or set)")
    by_q = {}
    for q, r, value, req, _ in rows:
        by_q.setdefault(q, []).append((r, value, req))
    for q, choices in by_q.items():
        req = choices[0][2]
        chosen = [r for r, v, _ in choices if v.lower() == "true"]
        if req == "SINGLE_CHOICE" and len(chosen) > 1:
            raise DataSafetyError(f"{q}: single choice has {len(chosen)} answers")
        for r, v, _ in choices:
            if req in ("MULTIPLE_CHOICE", "SINGLE_CHOICE") and v not in ("", "true", "TRUE"):
                raise DataSafetyError(f"{q}/{r}: a choice cell must be true or blank, got {v!r}")
    collects = [v for r, v, _ in by_q.get("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA", [])]
    if collects not in (["true"], ["false"], ["TRUE"], ["FALSE"]):
        raise DataSafetyError("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA must be answered")
    if not allow_placeholder and PLACEHOLDER in text:
        raise DataSafetyError(f"contains {PLACEHOLDER} — replace it (a URL still to be published) first")
    types = sorted(r for q, r, v, _, _ in rows if q.startswith("PSL_DATA_TYPES_") and v.lower() == "true")
    return f"collects={collects[0].lower()} data types: {', '.join(types) or 'none'}"


def apps():
    for app in sorted(os.listdir(STORE)):
        spec = os.path.join(STORE, app, "play", "data-safety.json")
        if os.path.isfile(spec):
            yield app, spec, os.path.join(STORE, app, "play", "data-safety.csv")


def main(argv):
    if len(argv) != 2 or argv[1] not in ("render", "check"):
        print(__doc__.splitlines()[2], file=sys.stderr)
        print(__doc__.splitlines()[3], file=sys.stderr)
        return 2
    template = load_template()
    stale = []
    for app, spec_path, csv_path in apps():
        with open(spec_path, encoding="utf-8") as f:
            spec = json.load(f)
        try:
            text = render(spec, template)
        except DataSafetyError as e:
            print(f"data-safety {app}: {e}", file=sys.stderr)
            return 1
        if argv[1] == "render":
            with open(csv_path, "w", encoding="utf-8", newline="") as f:
                f.write(text)
            print(f"{app}: {validate(text, template)}")
        else:
            current = open(csv_path, encoding="utf-8", newline="").read() if os.path.isfile(csv_path) else None
            if current != text:
                stale.append(app)
    if stale:
        print(f"data-safety: stale CSV for {', '.join(stale)} — run scripts/lib/data_safety.py render",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
