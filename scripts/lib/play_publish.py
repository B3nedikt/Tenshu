#!/usr/bin/env python3
"""Upload an Android App Bundle to a Google Play track through the Play Developer API.

    play_publish.py check  app.tenshu.control.android
    play_publish.py upload --track internal app.tenshu.control.android build/play/control-3.aab
    play_publish.py promote --from internal --to production --version-code 9 app.tenshu.control.android
    play_publish.py listing build/store/play/drive [--dry-run]      # the store listing, from files
    play_publish.py show-listing app.tenshu.drive.android            # read the live listing back
    play_publish.py data-safety app.tenshu.drive.android store/drive/play/data-safety.csv [--dry-run]

`check` opens an edit, prints every track's current releases and discards the edit, so it proves
the credentials and the app grant without changing anything. `upload` sends one bundle and makes it
the track's only release, rolled out to everyone on that track. `promote` copies the completed
release on one track to another (the bundle is already on Play, so nothing is uploaded), and only if
that release is exactly `--version-code`. `listing` uploads one app's store
listing from a folder `scripts/play-listing-assets.py` assembled (fastlane supply's layout: `package`,
`<locale>/{title,short_description,full_description}.txt`, `<locale>/images/{icon.png,
featureGraphic.png,phoneScreenshots/*.png}`): the text, then each image type it has, replaced and not
appended (deleteall, then upload), all in one edit committed at the end. It never touches tracks,
releases or bundles. `show-listing` reads the committed listing back (a discarded edit).
`data-safety` replaces the app's Data safety answers with a CSV in the Console's export format
(`scripts/lib/data_safety.py` renders them; `docs/how-to/play-console-answers.md` §12). It is not
part of an edit: Play applies it on the call. `--dry-run` validates and prints what would be sent,
without credentials or network.

Credentials: the service-account JSON in the macOS Keychain (account `tenshu`, item
`play-service-account`), or a file named by $TENSHU_PLAY_SERVICE_ACCOUNT_FILE.

Standard library only. The JWT is signed by `openssl` because Python has no RSA built in; the
private key is written to a 0600 file in a private temporary directory for that one call.

⚠ Play refuses the first upload of an app: the app has to be created in the Console, and its app
signing choice made, by hand. See docs/how-to/android-release.md.
"""

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

sys.dont_write_bytecode = True  # importing data_safety must not leave a __pycache__ in scripts/lib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def fail(message):
    print(f"play_publish: {message}", file=sys.stderr)
    sys.exit(1)


def load_service_account():
    path = os.environ.get("TENSHU_PLAY_SERVICE_ACCOUNT_FILE")
    if path:
        with open(path) as f:
            return json.load(f)
    result = subprocess.run(
        ["security", "find-generic-password", "-a", "tenshu", "-s", "play-service-account", "-w"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        fail("no 'play-service-account' in the Keychain (account tenshu) and "
             "TENSHU_PLAY_SERVICE_ACCOUNT_FILE is unset")
    raw = result.stdout.strip()
    # ⚠ `security -w` prints the value as hex when it contains a newline, which a pretty-printed
    # JSON file always does.
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return json.loads(bytes.fromhex(raw).decode())


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token(account):
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": account["client_email"], "scope": SCOPE, "aud": account["token_uri"],
        "iat": now, "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claims}".encode()
    tmp = tempfile.mkdtemp()
    try:
        key_path = os.path.join(tmp, "key.pem")
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(account["private_key"])
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    assertion = f"{header}.{claims}.{b64url(signature)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion,
    }).encode()
    return request("POST", account["token_uri"], body=body,
                   headers={"Content-Type": "application/x-www-form-urlencoded"})["access_token"]


def request(method, url, token=None, body=None, headers=None):
    headers = dict(headers or {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=600) as response:
            payload = response.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        try:
            detail = json.loads(detail)["error"]["message"]
        except (ValueError, KeyError, TypeError):
            pass
        fail(f"{method} {url.split('?')[0]} → HTTP {e.code}: {detail}")
    return json.loads(payload) if payload else {}


class Edit:
    """One Play edit: every change is staged in it and nothing is visible until commit()."""

    def __init__(self, token, package):
        self.token, self.package = token, package
        self.base = f"{API}/{package}/edits"
        self.id = request("POST", self.base, token, body={})["id"]

    def call(self, method, path, body=None):
        return request(method, f"{self.base}/{self.id}{path}", self.token, body=body)

    def upload_bundle(self, aab):
        url = f"{UPLOAD_API}/{self.package}/edits/{self.id}/bundles?uploadType=media"
        with open(aab, "rb") as f:
            data = f.read()
        return request("POST", url, self.token, body=data,
                       headers={"Content-Type": "application/octet-stream"})["versionCode"]

    def upload_image(self, locale, image_type, path):
        url = f"{UPLOAD_API}/{self.package}/edits/{self.id}/listings/{locale}/{image_type}?uploadType=media"
        with open(path, "rb") as f:
            data = f.read()
        return request("POST", url, self.token, body=data, headers={"Content-Type": "image/png"})

    def commit(self, not_sent_for_review=False):
        # ⚠ While an app has changes in review (or under managed publishing), Play refuses a plain
        # commit with HTTP 400 "Changes cannot be sent for review automatically". The edit then
        # commits only with this flag, and the maintainer sends it for review in the Console.
        return self.call("POST", ":commit?changesNotSentForReview=true" if not_sent_for_review else ":commit")

    def discard(self):
        request("DELETE", f"{self.base}/{self.id}", self.token)


IMAGE_TYPES = {"icon": "icon.png", "featureGraphic": "featureGraphic.png", "phoneScreenshots": "phoneScreenshots"}


def listing_files(folder):
    """(package, {locale: {text fields, images}}) from an assembled folder; fails on anything missing."""
    with open(os.path.join(folder, "package")) as f:
        package = f.read().strip()
    locales = {}
    for locale in sorted(os.listdir(folder)):
        path = os.path.join(folder, locale)
        if not os.path.isdir(path):
            continue
        entry = {}
        for key, field in (("title", "title"), ("short_description", "shortDescription"),
                           ("full_description", "fullDescription")):
            with open(os.path.join(path, f"{key}.txt"), encoding="utf-8") as f:
                entry[field] = f.read().strip()
        images = {}
        for image_type, name in IMAGE_TYPES.items():
            target = os.path.join(path, "images", name)
            if os.path.isdir(target):
                files = [os.path.join(target, n) for n in sorted(os.listdir(target)) if n.endswith(".png")]
                if files:
                    images[image_type] = files
            elif os.path.isfile(target):
                images[image_type] = [target]
        entry["images"] = images
        locales[locale] = entry
    if not locales:
        fail(f"no <locale>/ folder in {folder}")
    return package, locales


def push_listing(token, folder, dry_run):
    package, locales = listing_files(folder)
    if dry_run:
        for locale, entry in locales.items():
            counts = ", ".join(f"{t} ×{len(f)}" for t, f in entry["images"].items()) or "no images"
            # A type with no files is never sent, not even its DELETE: Play keeps what it has (for a
            # non-English locale with no screenshots, that is nothing, so it shows the default language's).
            kept = [t for t in IMAGE_TYPES if t not in entry["images"]]
            print(f"{package} [{locale}] (dry run) {entry['title']!r} — {counts}"
                  + (f"; left as they are: {', '.join(kept)}" if kept else ""))
        return
    edit = Edit(token, package)
    committed = False
    try:
        for locale, entry in locales.items():
            edit.call("PUT", f"/listings/{locale}", body={
                "language": locale, "title": entry["title"],
                "shortDescription": entry["shortDescription"], "fullDescription": entry["fullDescription"],
            })
            for image_type, files in entry["images"].items():
                # Replace, never append: an upload adds to the set, so a re-run would double it.
                edit.call("DELETE", f"/listings/{locale}/{image_type}")
                for path in files:
                    edit.upload_image(locale, image_type, path)
            counts = ", ".join(f"{t} ×{len(f)}" for t, f in entry["images"].items()) or "no images"
            print(f"{package} [{locale}]: text + {counts} staged")
        edit.commit()
        committed = True
        print(f"{package}: listing committed")
    finally:
        if not committed:
            edit.discard()


def show_listing(token, package):
    edit = Edit(token, package)
    try:
        for listing in edit.call("GET", "/listings").get("listings", []):
            locale = listing["language"]
            images = {t: len(edit.call("GET", f"/listings/{locale}/{t}").get("images", [])) for t in IMAGE_TYPES}
            print(f"{package} [{locale}] title={listing.get('title')!r} short={listing.get('shortDescription')!r} "
                  f"full={len(listing.get('fullDescription', ''))} chars images={images}")
    finally:
        edit.discard()


def data_safety_gate(csv_path):
    """The `upload_gate` the answer file beside [csv_path] declares, or None."""
    import data_safety
    spec_path = os.path.join(os.path.dirname(os.path.abspath(csv_path)), "data-safety.json")
    if not os.path.isfile(spec_path):
        return None
    with open(spec_path, encoding="utf-8") as f:
        return json.load(f).get(data_safety.UPLOAD_GATE_KEY)


def data_safety_body(package, csv_path, dry_run, closure_flags_open=False):
    """The request body for `applications.dataSafety`, after the checks an upload must pass."""
    import data_safety  # scripts/lib, beside this file
    with open(csv_path, encoding="utf-8", newline="") as f:
        text = f.read()
    gate = data_safety_gate(csv_path)
    if gate is not None and gate != data_safety.CLOSURE_FLAGS_GATE:
        fail(f"{csv_path}: unknown upload_gate {gate!r} beside it")
    # ⚠⚠ Control answers "Yes, we delete data on request", which is only wholly true once content
    # reclamation runs. Refuse until the operator states the closure flags are open — a machine
    # check, because a doc note is exactly what an upload in a hurry skips.
    if gate == data_safety.CLOSURE_FLAGS_GATE and not dry_run and not closure_flags_open:
        fail(
            f"{csv_path}: its deletion answers hold only once BOTH account-closure flags are open "
            "(ACCOUNT_CLOSURE_RELAY_URL on commerce, ACCOUNT_CLOSURE_RECLAIM on the relay — "
            "retention-quota-model.md §3.3). Open them, then pass --closure-flags-open."
        )
    listing = os.path.join(os.path.dirname(os.path.abspath(csv_path)), "listing.json")
    if os.path.isfile(listing):
        with open(listing, encoding="utf-8") as f:
            expected = json.load(f).get("package")
        if expected and expected != package:
            fail(f"{csv_path} sits beside {expected}'s listing, not {package}'s")
    try:
        summary = data_safety.validate(text, data_safety.load_template(), allow_placeholder=dry_run)
    except data_safety.DataSafetyError as e:
        fail(f"{csv_path}: {e}")
    return {"safetyLabels": text}, summary


def push_data_safety(token, package, csv_path, dry_run, closure_flags_open=False):
    body, summary = data_safety_body(package, csv_path, dry_run, closure_flags_open)
    if dry_run:
        warn = " ⚠ contains PLACEHOLDER: an upload would refuse" if data_safety_placeholder(body) else ""
        if data_safety_gate(csv_path) and not closure_flags_open:
            warn += " ⚠ gated on the account-closure flags: an upload needs --closure-flags-open"
        print(f"{package} (dry run) {summary}; {len(body['safetyLabels'])} bytes{warn}")
        return
    request("POST", f"{API}/{package}/dataSafety", token, body=body)
    print(f"{package}: Data safety answers replaced ({summary})")


def data_safety_placeholder(body):
    import data_safety
    return data_safety.PLACEHOLDER in body["safetyLabels"]


def describe(release):
    codes = ",".join(release.get("versionCodes", [])) or "-"
    return f"{release.get('status')} {release.get('name', '')!r} versionCodes={codes}"


def check(token, package):
    edit = Edit(token, package)
    try:
        tracks = edit.call("GET", "/tracks").get("tracks", [])
        print(f"{package}: credentials and app access OK")
        for track in tracks:
            releases = track.get("releases", [])
            print(f"  {track['track']}: " + ("; ".join(map(describe, releases)) or "no release"))
    finally:
        edit.discard()


def upload(token, package, track, aab, status, not_sent_for_review=False):
    edit = Edit(token, package)
    committed = False
    try:
        version_code = edit.upload_bundle(aab)
        print(f"{package}: uploaded {os.path.basename(aab)} as versionCode {version_code}")
        edit.call("PUT", f"/tracks/{track}", body={
            "track": track,
            "releases": [{"versionCodes": [str(version_code)], "status": status}],
        })
        edit.commit(not_sent_for_review)
        committed = True
        print(f"{package}: versionCode {version_code} is the {status} release on '{track}'")
        if not_sent_for_review:
            print(f"{package}: ⚠ committed WITHOUT sending for review — send it from the Play Console")
    finally:
        if not committed:
            edit.discard()


def promoted_release(track, version_code):
    """The release to put on the target track: the source track's one completed release, refused
    unless it is exactly `version_code`. Name and release notes travel with it."""
    completed = [r for r in track.get("releases", []) if r.get("status") == "completed"]
    if len(completed) != 1:
        raise ValueError(f"'{track.get('track')}' has {len(completed)} completed releases, expected one")
    release = completed[0]
    codes = release.get("versionCodes", [])
    if codes != [str(version_code)]:
        raise ValueError(f"'{track.get('track')}' serves versionCodes {codes}, not [{version_code}]")
    promoted = {"versionCodes": codes, "status": "completed"}
    for key in ("name", "releaseNotes"):
        if key in release:
            promoted[key] = release[key]
    return promoted


def promote(token, package, source, target, version_code):
    edit = Edit(token, package)
    committed = False
    try:
        try:
            release = promoted_release(edit.call("GET", f"/tracks/{source}"), version_code)
        except ValueError as e:
            fail(f"{package}: {e}")
        edit.call("PUT", f"/tracks/{target}", body={"track": target, "releases": [release]})
        edit.commit()
        committed = True
        print(f"{package}: {describe(release)} promoted '{source}' → '{target}'")
    finally:
        if not committed:
            edit.discard()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p_check = sub.add_parser("check")
    p_check.add_argument("package")
    p_upload = sub.add_parser("upload")
    p_upload.add_argument("--track", required=True)
    # ⚠ An app Play still lists as a draft (never released anywhere) accepts only `draft`.
    p_upload.add_argument("--status", default="completed", choices=["completed", "draft"])
    p_upload.add_argument("--changes-not-sent-for-review", action="store_true",
                          help="commit without sending for review (Play's answer while other changes are in review)")
    p_upload.add_argument("package")
    p_upload.add_argument("aab")
    p_promote = sub.add_parser("promote")
    p_promote.add_argument("--from", dest="source", required=True)
    p_promote.add_argument("--to", dest="target", required=True)
    p_promote.add_argument("--version-code", type=int, required=True)
    p_promote.add_argument("package")
    p_listing = sub.add_parser("listing")
    p_listing.add_argument("folder")
    p_listing.add_argument("--dry-run", action="store_true")
    p_show = sub.add_parser("show-listing")
    p_show.add_argument("package")
    p_safety = sub.add_parser("data-safety")
    p_safety.add_argument("package")
    p_safety.add_argument("csv")
    p_safety.add_argument("--dry-run", action="store_true")
    p_safety.add_argument(
        "--closure-flags-open", action="store_true",
        help="assert that both account-closure flags are open (needed for an answer file with upload_gate)",
    )
    args = parser.parse_args(argv)

    if args.command == "data-safety" and not os.path.isfile(args.csv):
        fail(f"no CSV at {args.csv}")
    if args.command == "listing" and args.dry_run:
        push_listing(None, args.folder, True)
        return
    if args.command == "data-safety" and args.dry_run:
        push_data_safety(None, args.package, args.csv, True, args.closure_flags_open)
        return
    if args.command == "data-safety":
        data_safety_body(args.package, args.csv, False, args.closure_flags_open)  # refuse before credentials
    token = access_token(load_service_account())
    if args.command == "listing":
        push_listing(token, args.folder, False)
    elif args.command == "data-safety":
        push_data_safety(token, args.package, args.csv, False, args.closure_flags_open)
    elif args.command == "show-listing":
        show_listing(token, args.package)
    elif args.command == "check":
        check(token, args.package)
    elif args.command == "promote":
        promote(token, args.package, args.source, args.target, args.version_code)
    else:
        if not os.path.isfile(args.aab):
            fail(f"no bundle at {args.aab}")
        upload(token, args.package, args.track, args.aab, args.status, args.changes_not_sent_for_review)


if __name__ == "__main__":
    main()
