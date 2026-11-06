#!/usr/bin/env python3
"""The App Store listing as files: check it, assemble it for fastlane, fetch its screenshots.

store-presence-and-support-pages.md §2 (D1, D7); runbook docs/how-to/store-listings.md.

  scripts/store-listing.py check [app …]              every limit and copy rule below; no network
  scripts/store-listing.py assemble <app> <out>       <out>/metadata in fastlane deliver's layout
  scripts/store-listing.py screenshots <app> <out>    <out>/screenshots/<locale>/, by manifest sha256
  scripts/store-listing.py pushable <app>             exit 0 when store-push.sh may take the app, else say what is missing
  scripts/store-listing.py digest <dir>               one sha256 over a tree (store-push.sh's preview stamp)
  scripts/store-listing.py play-check [app …]         the Google Play copy alone (`check` runs it too)

What lives where (the source; deliver never reads store/ directly — its loader refuses any folder that
is not a language, and store/<app>/ also holds review/, screenshots/ and this tooling's files):

  store/shared/copyright.txt, store/shared/<locale>/{privacy_url,marketing_url}.txt   every app
  store/<app>/<locale>/{name,subtitle,description,keywords,promotional_text,release_notes,support_url}.txt
  store/<app>/{primary_category,secondary_category}.txt, age_rating.json, review/notes.txt
  store/<app>/claims.md        the source of every privacy sentence in a description (§4.4)
  store/<app>/listing.json     bundle id, store locale → manifest language, the listed screens

Two kinds of app. PUSHED apps (Control, Drive, Fit, Calendar, Contacts, Photos, Timeline, Mia) have the whole listing and go
through store-push.sh. COPY-ONLY apps have the localized copy and claims.md, and not yet every pipeline
file (listing.json, age rating, primary category, review notes, captured App Store screenshots): `check`
holds their copy to every rule a pushed app's copy meets, checks whatever pipeline file they do have,
and `pushable`/`assemble`/`screenshots` refuse them, naming what is missing. An app moves to PUSHED_APPS
when its pipeline files exist — never by deleting a pushed app's file. The secondary category is
optional for every app (App Store Connect's is too).

Google Play (docs/how-to/store-listings.md §8): store/<app>/play/<locale>/{title,short_description,
full_description}.txt — fastlane supply's names — and store/<app>/play/listing.json, for all ten apps
(PLAY_APPS). Its own copy, not the App Store's: the platform facts differ (Face ID, the App Store,
Apple Health, the iOS version). `check` holds it to Play's limits and the same copy rules and claims.
Every locale folder is checked (en-US, de-DE); a non-English privacy sentence's claim says which English
claim it translates (`- translates: "…"`), and listing.json's `taglines` gives each non-English locale its
feature graphic line.

An app's own file wins over the shared one of the same name. ⚠ Limits are App Store Connect's as of
2026-10 (design §1.1); check them against the live console when one is refused.
"""
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
STORE = os.path.join(ROOT, "store")
CACHE = os.path.join(ROOT, "build", "store", "screenshot-cache")
# Pushed through store-push.sh: every pipeline file is required.
PUSHED_APPS = ("control", "drive", "fit", "calendar", "contacts", "photos", "timeline", "mia")
# The listing copy only (docs/how-to/store-listings.md §2): the pipeline files are checked when present.
# ⚠ Photos' listing.json takes its App Store set (ios/iphone69, ios/ipad13) — never its landing page's
# captures (ios/iphone69landing), which are not App Store screenshots.
COPY_ONLY_APPS = ("maps",)
# Pipeline files a pushed app must have; the secondary category is optional (App Store Connect's is).
PIPELINE_FILES = ("listing.json", "age_rating.json", "primary_category.txt", "review/notes.txt")
APPS = PUSHED_APPS + COPY_ONLY_APPS
# Google Play: every Android app has a listing (store/<app>/play/), Home and Mia included.
PLAY_APPS = APPS + ("home",)
# Play Console's limits (2026-10): characters, not bytes.
PLAY_LOCALIZED = {"title": 30, "short_description": 80, "full_description": 4000}
PLAY_PACKAGE = re.compile(r"^app\.tenshu\.[a-z]+\.android$")

# Per locale: file → (limit, unit). Unit "chars" counts characters, "bytes" UTF-8 bytes (keywords).
LOCALIZED = {
    "name": (30, "chars"),
    "subtitle": (30, "chars"),
    "description": (4000, "chars"),
    "keywords": (100, "bytes"),
    "promotional_text": (170, "chars"),
    "release_notes": (4000, "chars"),
    "support_url": (None, None),
    "marketing_url": (None, None),
    "privacy_url": (None, None),
}
REQUIRED_LOCALIZED = {"name", "description", "keywords", "support_url", "privacy_url"}
NON_LOCALIZED = ("copyright", "primary_category", "secondary_category")
OPTIONAL_NON_LOCALIZED = {"secondary_category"}
# Spaceship::ConnectAPI::AppCategory, fastlane 2.241.1 — the ones a non-game app could take.
CATEGORIES = {
    "BOOKS", "BUSINESS", "DEVELOPER_TOOLS", "EDUCATION", "ENTERTAINMENT", "FINANCE", "FOOD_AND_DRINK",
    "GRAPHICS_AND_DESIGN", "HEALTH_AND_FITNESS", "LIFESTYLE", "MAGAZINES_AND_NEWSPAPERS", "MEDICAL", "MUSIC",
    "NAVIGATION", "NEWS", "PHOTO_AND_VIDEO", "PRODUCTIVITY", "REFERENCE", "SHOPPING", "SOCIAL_NETWORKING",
    "SPORTS", "TRAVEL", "UTILITIES", "WEATHER",
}
# Spaceship::ConnectAPI::AgeRatingDeclaration: the frequency questions and the yes/no ones.
AGE_FREQUENCY = {
    "alcoholTobaccoOrDrugUseOrReferences", "contests", "gamblingSimulated", "gunsOrOtherWeapons",
    "horrorOrFearThemes", "matureOrSuggestiveThemes", "medicalOrTreatmentInformation", "profanityOrCrudeHumor",
    "sexualContentGraphicAndNudity", "sexualContentOrNudity", "violenceCartoonOrFantasy", "violenceRealistic",
    "violenceRealisticProlongedGraphicOrSadistic",
}
# The API takes both pairs (AgeRatingDeclarationUpdateRequest, 2026-10): the older *_OR_MILD/_OR_INTENSE, and
# INFREQUENT/FREQUENT, the labels of the 2025 questionnaire (e.g. Medical or Treatment Information).
AGE_FREQUENCY_VALUES = {"NONE", "INFREQUENT_OR_MILD", "FREQUENT_OR_INTENSE", "INFREQUENT", "FREQUENT"}
AGE_BOOLEAN = {
    "advertising", "ageAssurance", "gambling", "healthOrWellnessTopics", "lootBox", "messagingAndChat",
    "parentalControls", "socialMedia", "unrestrictedWebAccess", "userGeneratedContent",
}
# Asked only when socialMedia is true (the API: "whether the app's social media features are age
# restricted"): required then, and left out otherwise — deliver sends the file's keys as they are.
AGE_CONDITIONAL = {"socialMediaAgeRestricted": "socialMedia"}
# The copy rules (D6, and §4.4's "no claim stronger than the threat model"). A word list cannot judge
# tone; it catches the claims that are wrong on their face. Matched case-insensitively, whole words.
SUPERLATIVES = [
    "best", "fastest", "safest", "most secure", "most private", "ultimate", "unbreakable", "unhackable",
    "military-grade", "military grade", "bank-grade", "bank grade", "100%", "perfect", "perfectly",
    "number one", "#1", "world's", "leading", "revolutionary", "bulletproof", "guaranteed",
    "completely secure", "completely private", "totally secure", "zero-knowledge", "zero knowledge",
    # German (de-DE): regex STEMS (re.Pattern), not word lists — German inflects and compounds, so a list
    # of forms always misses one (Allerbeste, Unknackbare, Branchenführende). Still whole words at both
    # ends. ⚠ Never a bare `best\w*` (bestätigen, bestehen, Bestellung) or `\w*führend\w*` (anführende,
    # durchführende, irreführend): the compounds that make a claim are named instead.
    re.compile(r"(aller)?beste[nrs]?"), re.compile(r"bestmöglich\w*"), re.compile(r"\w*sicherste[nrs]?"),
    re.compile(r"\w*schnellste[nrs]?"), re.compile(r"privateste[nrs]?"), re.compile(r"höchste[nrs]?"),
    re.compile(r"unknackbar\w*"), re.compile(r"unhackbar\w*"), re.compile(r"garantier\w*"),
    re.compile(r"militär\w*"), re.compile(r"bankenniveau"), re.compile(r"perfekt\w*"),
    re.compile(r"revolutionär\w*"), re.compile(r"hundertprozentig\w*"), re.compile(r"nummer\s+eins"),
    re.compile(r"nr\.\s*1"), re.compile(r"100\s*(%|prozent)"),
    re.compile(r"(markt|branchen|welt|technologie|innovations|qualitäts)?führend\w*"),
    re.compile(r"weltweit\s+führend\w*"),
    re.compile(r"(absolut|völlig|komplett|vollkommen|vollständig|total)\s+(sicher|privat)\w*"),
]
COMPETITORS = [
    "dropbox", "google drive", "google photos", "google one", "icloud", "onedrive", "sharepoint", "pcloud",
    "proton", "tresorit", "mega.nz", "mega.io", "sync.com", "nextcloud", "owncloud", "spideroak", "box.com",
    "amazon photos", "idrive", "backblaze", "1password", "bitwarden",
]
# The privacy section's heading, per language: "HOW DRIVE KEEPS YOUR FILES PRIVATE",
# "WIE DRIVE DEINE DATEIEN PRIVAT HÄLT" (store/<app>/play/de-DE/). ⚠ Never an ß in a heading: "ß".upper()
# is "SS", so the line would not read as a heading.
PRIVACY_HEADINGS = {"en": re.compile(r"^HOW .* PRIVATE$"), "de": re.compile(r"^WIE .* PRIVAT( [A-ZÄÖÜ]+)?$")}
# The language whose claims are the originals: a claim in any other language is a translation of one,
# and says which (`- translates: "<the English claim>"`); the set and order must match the English
# listing's claims. That is mechanics: whether the translation means the same is review's job.
SOURCE_LANGUAGE = "en"
LOCALE = re.compile(r"^[a-z]{2}(-[A-Z][A-Za-z]+)?$")
CAPTION_LIMIT = 40
MAX_SCREENSHOTS = 10
# What App Store Connect accepts per device folder (store-screenshots.sh's accepted_sizes).
DEVICE_SIZES = {"iphone69": {(1320, 2868), (1290, 2796)}, "ipad13": {(2064, 2752), (2048, 2732)}}
# The slot deliver files each device's sizes under (Deliver::AppScreenshot.calculate_display_type,
# fastlane 2.241.1): it decides by pixel size alone, and both sizes above land in these.
DEVICE_DISPLAY_TYPES = {"iphone69": "APP_IPHONE_67 (6.9\" iPhone)", "ipad13": "APP_IPAD_PRO_3GEN_129 (13\" iPad)"}
# Every app ships with TARGETED_DEVICE_FAMILY "1,2", so App Store Connect wants both slots filled.
REQUIRED_DEVICES = ("iphone69", "ipad13")


class Problems(list):
    def add(self, where, message):
        self.append(f"{os.path.relpath(where, ROOT) if os.path.isabs(where) else where}: {message}")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def value(path):
    """A file's value as deliver sends it: deliver strips every value before upload."""
    return read(path).strip()


def measure(text, unit):
    return len(text.encode("utf-8")) if unit == "bytes" else len(text)


def word_hits(text, words):
    lowered = text.lower()
    hits = []
    for w in words:
        # Whole words: `best` must not hit `bestow`, and `#1`/`100%` have no word boundary of their own.
        # Any letter counts, umlauts too. A string is literal; an re.Pattern (the German stems) a regex,
        # reported by the text it matched. `\s` covers the no-break spaces of "100 %" (U+00A0, U+202F).
        body = w.pattern if isinstance(w, re.Pattern) else re.escape(w)
        m = re.search(r"(?<![^\W_])(?:" + body + r")(?![^\W_])", lowered)
        if m:
            hits.append(m.group(0) if isinstance(w, re.Pattern) else w)
    return hits


def locales(app):
    base = os.path.join(STORE, app)
    return sorted(d for d in os.listdir(base) if LOCALE.match(d) and os.path.isdir(os.path.join(base, d)))


def source_for(app, locale, key):
    """The file that supplies `key` for (app, locale): the app's own, else the shared one, else None."""
    for path in (os.path.join(STORE, app, locale, f"{key}.txt"), os.path.join(STORE, "shared", locale, f"{key}.txt")):
        if os.path.exists(path):
            return path
    return None


def non_localized_source(app, key):
    for path in (os.path.join(STORE, app, f"{key}.txt"), os.path.join(STORE, "shared", f"{key}.txt")):
        if os.path.exists(path):
            return path
    return None


def load_json(path, problems):
    try:
        return json.loads(read(path))
    except (OSError, ValueError) as e:
        problems.add(path, f"not readable JSON ({e})")
        return None


def privacy_sentences(description, lang=None):
    """The bullets under the description's privacy heading (`HOW … PRIVATE`, `WIE … PRIVAT …`), each
    split into sentences. `lang` takes that language's heading only; None any language's."""
    headings = [PRIVACY_HEADINGS[lang]] if lang in PRIVACY_HEADINGS else list(PRIVACY_HEADINGS.values())
    sentences, inside = [], False
    for line in description.splitlines():
        if line.strip() and line == line.upper() and re.search(r"[A-ZÄÖÜ]", line):
            inside = any(h.match(line.strip()) for h in headings)
            continue
        if inside and line.startswith("- "):
            for s in re.split(r"(?<=[.!?])\s+", line[2:].strip()):
                if s:
                    sentences.append(s)
    return sentences


def all_descriptions(app):
    """Every description of the app, App Store and Play: a claim is live while any of them quotes it."""
    texts = []
    if app in APPS:
        for locale in locales(app):
            path = os.path.join(STORE, app, locale, "description.txt")
            if os.path.exists(path):
                texts.append(read(path))
    for locale in play_locales(app):
        path = os.path.join(STORE, app, "play", locale, "full_description.txt")
        if os.path.exists(path):
            texts.append(read(path))
    return texts


def play_locales(app):
    base = os.path.join(STORE, app, "play")
    if not os.path.isdir(base):
        return []
    return sorted(d for d in os.listdir(base) if LOCALE.match(d) and os.path.isdir(os.path.join(base, d)))


def check_claims(app, locale, description, problems, where=None, english=None):
    """Every privacy sentence in the description is quoted in claims.md with at least one source that exists;
    outside English, each is also a `- translates:` of an English claim in the same file, and — given the
    same listing's English description (`english`) — the translated claims are exactly its English ones,
    in order, so a dropped caveat fails. Whether each translation MEANS its claim is review's job."""
    lang = locale.split("-")[0]
    sentences = privacy_sentences(description, lang)
    path = os.path.join(STORE, app, "claims.md")
    where = where or os.path.join(STORE, app, locale, "description.txt")
    if lang not in PRIVACY_HEADINGS:
        problems.add(where, f"no privacy heading is known for '{lang}' — add it to PRIVACY_HEADINGS")
        return
    if not sentences:
        problems.add(where, "no privacy section (`HOW … PRIVATE`, `WIE … PRIVAT …`) with bullets — the listing's "
                            "privacy claims have to be findable")
        return
    if not os.path.exists(path):
        problems.add(path, "missing — every privacy sentence of the description needs a cited source")
        return
    claims, translates, current = {}, {}, None
    for line in read(path).splitlines():
        if line.startswith("> "):
            current = line[2:].strip()
            claims[current] = []
        elif current is not None and line.strip().startswith("- source:"):
            claims[current].append(line.split("source:", 1)[1].strip())
        elif current is not None and line.strip().startswith("- translates:"):
            if current in translates:
                problems.add(path, f"a second `- translates:` under one claim — a translation renders one English claim: \"{current}\"")
            translates[current] = line.split("translates:", 1)[1].strip().strip('"')
    for s in sentences:
        if s not in claims:
            problems.add(where, f"privacy sentence has no entry in claims.md: \"{s}\"")
            continue
        if lang != SOURCE_LANGUAGE:
            original = translates.get(s)
            if original is None:
                problems.add(path, f"a {locale} claim names the English claim it translates (`- translates: \"…\"`): \"{s}\"")
            elif original not in claims or original in translates:
                problems.add(path, f"translates \"{original}\", which is not an English claim in claims.md "
                                   f"(changed or removed? translate the new wording): \"{s}\"")
        if not claims[s]:
            problems.add(path, f"claim has no `- source:` line: \"{s}\"")
        for src in claims[s]:
            target = src.split(" ", 1)[0].split("#", 1)[0]
            if not os.path.exists(os.path.join(ROOT, target)):
                problems.add(path, f"source '{target}' does not exist (claim: \"{s}\")")
    if lang != SOURCE_LANGUAGE and english is not None:
        # Completeness: the translation renders every English claim, in order — none dropped, none added.
        got = [translates.get(s) for s in sentences]
        want = privacy_sentences(english, SOURCE_LANGUAGE)
        if got != want:
            missing = [c for c in want if c not in got]
            extra = [c for c in got if c not in want]
            detail = (f"missing {missing}" if missing else "") + ("; " if missing and extra else "") + \
                     (f"not in the English listing {extra}" if extra else "")
            problems.add(where, f"the {locale} privacy section does not translate the English one's claims "
                                f"one for one, in order ({detail or 'order differs'})")
    used = set()
    for d in all_descriptions(app):
        used.update(privacy_sentences(d))
    for c in claims:
        if c not in used:
            problems.add(path, f"claim is in no description any more (App Store or Play) — remove it or restore it: \"{c}\"")


def check_app(app, problems):
    base = os.path.join(STORE, app)
    pushed = app in PUSHED_APPS
    screens_path = os.path.join(base, "screens.json")
    screens_doc = (load_json(screens_path, problems) if pushed or os.path.exists(screens_path) else None) or {}
    known = set((screens_doc.get("captured") or {})) | set((screens_doc.get("planned") or {}))
    captured = set(screens_doc.get("captured") or {})

    found = locales(app)
    if not found:
        problems.add(base, "no <locale>/ folder (e.g. en-US) with listing copy")
    for locale in found:
        texts = {}
        for key, (limit, unit) in LOCALIZED.items():
            path = source_for(app, locale, key)
            if path is None:
                if key in REQUIRED_LOCALIZED:
                    problems.add(os.path.join(base, locale), f"{key}.txt is required (here or in store/shared/{locale}/)")
                continue
            text = value(path)
            texts[key] = (path, text)
            if not text:
                problems.add(path, "empty")
            if limit is not None and measure(text, unit) > limit:
                problems.add(path, f"{measure(text, unit)} {unit}, over App Store Connect's {limit}")
            if "\r" in read(path):
                problems.add(path, "carriage return — write plain \\n line ends")
        # Shapes deliver would accept but a reviewer or user would trip over.
        if "name" in texts and not texts["name"][1].startswith("Tenshu "):
            problems.add(texts["name"][0], "store names are \"Tenshu <App>\" (design §6.1)")
        if "keywords" in texts:
            path, kw = texts["keywords"]
            words = [w.strip() for w in kw.split(",")]
            if any(not w for w in words):
                problems.add(path, "an empty keyword (doubled or trailing comma)")
            if len({w.lower() for w in words}) != len(words):
                problems.add(path, "a keyword appears twice")
            if any(w.lower() == "tenshu" or w.lower() in texts.get("name", ("", ""))[1].lower().split() for w in words):
                problems.add(path, "a keyword repeats the app name — the name is indexed already")
            if "\n" in kw:
                problems.add(path, "keywords are one comma-separated line")
        for key in ("support_url", "marketing_url", "privacy_url"):
            if key in texts and not re.match(r"^https://[^\s]+$", texts[key][1]):
                problems.add(texts[key][0], "must be one https:// URL")
        lang = locale.split("-")[0]
        expected = f"https://tenshu.app/apps/{app}/support/ios/{lang}"
        if "support_url" in texts and texts["support_url"][1] != expected:
            problems.add(texts["support_url"][0], f"the support URL is {expected} (D2, §4.1)")
        for key, (path, text) in texts.items():
            for hit in word_hits(text, SUPERLATIVES):
                problems.add(path, f"\"{hit}\" — no superlatives in store copy (D6)")
            for hit in word_hits(text, COMPETITORS):
                problems.add(path, f"\"{hit}\" — name no competitor (D6)")
        if "description" in texts:
            # A non-English privacy section translates the English one's claims, one for one and in order.
            english_path = source_for(app, "en-US", "description") if locale != "en-US" else None
            check_claims(app, locale, texts["description"][1], problems,
                         english=read(english_path) if english_path else None)
        captions = os.path.join(base, locale, "captions.txt")
        if os.path.exists(captions) and not os.path.exists(screens_path):
            problems.add(captions, "captions need screens.json — a caption names a captured screen")
        elif os.path.exists(captions):
            for n, line in enumerate(read(captions).splitlines(), 1):
                if not line.strip() or line.startswith("#"):
                    continue
                m = re.match(r"^(\d\d-[a-z0-9-]+):\s+(\S.*)$", line)
                if not m:
                    problems.add(captions, f"line {n}: not `NN-screen: caption`")
                    continue
                screen, caption = m.groups()
                if screen not in known:
                    problems.add(captions, f"line {n}: '{screen}' is not in screens.json")
                if len(caption) > CAPTION_LIMIT:
                    problems.add(captions, f"line {n}: {len(caption)} chars, over the {CAPTION_LIMIT} a frame's caption line holds")
                for hit in word_hits(caption, SUPERLATIVES):
                    problems.add(captions, f"line {n}: \"{hit}\" — no superlatives (D6)")
                for hit in word_hits(caption, COMPETITORS):
                    problems.add(captions, f"line {n}: \"{hit}\" — name no competitor (D6)")

    for key in NON_LOCALIZED:
        path = non_localized_source(app, key)
        if path is None:
            if pushed and key not in OPTIONAL_NON_LOCALIZED:
                problems.add(base, f"{key}.txt is required (here or in store/shared/)")
            continue
        text = value(path)
        if key == "copyright" and not re.match(r"^\d{4} \S", text):
            problems.add(path, "copyright is \"<year> <owner>\"")
        if key.endswith("category") and text not in CATEGORIES:
            problems.add(path, f"'{text}' is not an App Store category id (e.g. PRODUCTIVITY, UTILITIES)")
    primary, secondary = non_localized_source(app, "primary_category"), non_localized_source(app, "secondary_category")
    if primary and secondary and value(primary) == value(secondary):
        problems.add(secondary, "the secondary category repeats the primary")

    notes = os.path.join(base, "review", "notes.txt")
    if not os.path.exists(notes):
        if pushed:
            problems.add(notes, "missing — App Review needs to know how to reach the app's screens")
    elif len(value(notes)) > 4000:
        problems.add(notes, f"{len(value(notes))} chars, over App Store Connect's 4000")
    for name in os.listdir(os.path.join(base, "review")) if os.path.isdir(os.path.join(base, "review")) else []:
        if name != "notes.txt":
            problems.add(os.path.join(base, "review", name),
                         "only notes.txt is committed — the review contact is personal data; enter it in App Store Connect")

    rating_path = os.path.join(base, "age_rating.json")
    rating = load_json(rating_path, problems) if os.path.exists(rating_path) else None
    if rating is None and not os.path.exists(rating_path):
        if pushed:
            problems.add(rating_path, "missing — the age rating questionnaire is answered from this file")
    elif isinstance(rating, dict):
        for k in sorted(AGE_FREQUENCY | AGE_BOOLEAN):
            if k not in rating:
                problems.add(rating_path, f"'{k}' is unanswered")
        for k, v in rating.items():
            if k in AGE_FREQUENCY:
                if v not in AGE_FREQUENCY_VALUES:
                    problems.add(rating_path, f"'{k}' must be one of {sorted(AGE_FREQUENCY_VALUES)}")
            elif k in AGE_BOOLEAN or k in AGE_CONDITIONAL:
                if not isinstance(v, bool):
                    problems.add(rating_path, f"'{k}' must be true or false")
            else:
                problems.add(rating_path, f"'{k}' is not an age rating question deliver sends")
        for k, parent in AGE_CONDITIONAL.items():
            if rating.get(parent) is True and k not in rating:
                problems.add(rating_path, f"'{k}' is unanswered — it is asked because '{parent}' is true")
            elif rating.get(parent) is not True and k in rating:
                problems.add(rating_path, f"'{k}' is asked only when '{parent}' is true — leave it out")

    listing_path = os.path.join(base, "listing.json")
    listing = load_json(listing_path, problems) if os.path.exists(listing_path) else None
    if listing is None:
        if not os.path.exists(listing_path) and pushed:
            problems.add(listing_path, "missing")
        return
    ios = listing.get("ios") or {}
    if not re.match(r"^app\.tenshu\.[a-z]+\.ios$", str(ios.get("bundleId", ""))):
        problems.add(listing_path, "ios.bundleId is the App Store bundle id (app.tenshu.<app>.ios)")
    langs = ios.get("languages") or {}
    for locale in found:
        if locale not in langs:
            problems.add(listing_path, f"ios.languages has no entry for {locale}")
    shots = ios.get("screenshots") or {}
    screens = shots.get("screens") or []
    if not screens or len(screens) > MAX_SCREENSHOTS:
        problems.add(listing_path, f"ios.screenshots.screens lists 1–{MAX_SCREENSHOTS} screens, has {len(screens)}")
    if len(set(screens)) != len(screens):
        problems.add(listing_path, "a screen is listed twice")
    for s in screens:
        if s not in captured:
            problems.add(listing_path, f"'{s}' is not a captured screen in screens.json")
    devices = shots.get("devices") or []
    if "iphone69" not in devices:
        problems.add(listing_path, "ios.screenshots.devices must include iphone69 — the App Store's required iPhone slot")
    for d in devices:
        if d not in DEVICE_SIZES:
            problems.add(listing_path, f"device '{d}' — known: {sorted(DEVICE_SIZES)}")
    if not isinstance(shots.get("framed", False), bool):
        problems.add(listing_path, "ios.screenshots.framed is true or false")


def check_play_app(app, problems):
    """store/<app>/play/: Play's limits, the copy rules, the claims, listing.json (docs/how-to/store-listings.md §8)."""
    base = os.path.join(STORE, app, "play")
    found = play_locales(app)
    if not found:
        problems.add(base, "no <locale>/ folder (e.g. en-US) with the Play copy")
    for locale in found:
        texts = {}
        for key, limit in PLAY_LOCALIZED.items():
            path = os.path.join(base, locale, f"{key}.txt")
            if not os.path.exists(path):
                problems.add(os.path.join(base, locale), f"{key}.txt is required")
                continue
            text = value(path)
            texts[key] = (path, text)
            if not text:
                problems.add(path, "empty")
            if len(text) > limit:
                problems.add(path, f"{len(text)} chars, over Play's {limit}")
            if "\r" in read(path):
                problems.add(path, "carriage return — write plain \\n line ends")
            for hit in word_hits(text, SUPERLATIVES):
                problems.add(path, f"\"{hit}\" — no superlatives in store copy (D6)")
            for hit in word_hits(text, COMPETITORS):
                problems.add(path, f"\"{hit}\" — name no competitor (D6)")
            for hit in word_hits(text, ["app store", "face id", "touch id", "iphone", "ipad", "apple health"]):
                problems.add(path, f"\"{hit}\" — an iOS fact in the Play copy")
        if "title" in texts and not texts["title"][1].startswith("Tenshu "):
            problems.add(texts["title"][0], "store names are \"Tenshu <App>\" (design §6.1)")
        if "short_description" in texts and "\n" in texts["short_description"][1]:
            problems.add(texts["short_description"][0], "the short description is one line")
        if "full_description" in texts:
            path, text = texts["full_description"]
            if app != "control" and "Tenshu Control" not in text:
                problems.add(path, "every app but Control says it needs Tenshu Control")
            english_path = os.path.join(base, "en-US", "full_description.txt")
            english = read(english_path) if locale != "en-US" and os.path.exists(english_path) else None
            check_claims(app, locale, text, problems, where=path, english=english)
            check_help_link(app, locale, text, path, problems)
    listing_path = os.path.join(base, "listing.json")
    listing = load_json(listing_path, problems) if os.path.exists(listing_path) else None
    if listing is None:
        if not os.path.exists(listing_path):
            problems.add(listing_path, "missing — the package, tagline and screenshots come from it")
        return
    if not PLAY_PACKAGE.match(str(listing.get("package", ""))) or listing["package"].split(".")[2] != app:
        problems.add(listing_path, f"package is app.tenshu.{app}.android")
    if not str(listing.get("tagline", "")).strip() or len(listing.get("tagline", "")) > 40:
        problems.add(listing_path, "tagline (the feature graphic's second line) is 1–40 characters")
    # The feature graphic is drawn per locale: `taglines` gives a locale its own line, else it gets `tagline`.
    taglines = listing.get("taglines") or {}
    if not isinstance(taglines, dict):
        problems.add(listing_path, "taglines is {\"<locale>\": \"line\"}")
        taglines = {}
    for locale, line in taglines.items():
        if locale not in found:
            problems.add(listing_path, f"taglines has '{locale}', which has no play/{locale}/ folder")
        if not isinstance(line, str) or not line.strip() or len(line) > 40:
            problems.add(listing_path, f"taglines.{locale} is 1–40 characters")
            continue
        for hit in word_hits(line, SUPERLATIVES):
            problems.add(listing_path, f"taglines.{locale}: \"{hit}\" — no superlatives in store copy (D6)")
        for hit in word_hits(line, COMPETITORS):
            problems.add(listing_path, f"taglines.{locale}: \"{hit}\" — name no competitor (D6)")
    for locale in found:
        if locale != "en-US" and locale not in taglines:
            problems.add(listing_path, f"taglines has no '{locale}' — its feature graphic would carry the English tagline")
    check_play_shots(app, listing.get("screenshots") or {}, "en", "screenshots", listing_path, problems)
    # The feature graphic's screens: one or two raw English Android captures (a framed render has its own
    # caption and canvas; a German locale with captures of its own takes each key's /de/ twin).
    if "featureScreens" in listing:
        keys = listing["featureScreens"]
        manifest_path = os.path.join(STORE, app, "screenshots.manifest.json")
        screens = ((load_json(manifest_path, problems) or {}).get("screens") or {}) if os.path.exists(manifest_path) else {}
        if not isinstance(keys, list) or not 1 <= len(keys) <= 2:
            problems.add(listing_path, "featureScreens is a list of one or two manifest keys")
            keys = []
        for k in keys:
            parts = str(k).split("/")
            if k not in screens:
                problems.add(listing_path, f"featureScreens: '{k}' is not in screenshots.manifest.json")
            elif parts[0] != "android" or parts[1].endswith("framed") or parts[2] != "en":
                problems.add(listing_path, f"featureScreens: '{k}' is not an unframed English Android capture")
    # Every other locale names its screenshots: its own set, or "en-US" for the English one (an app whose
    # screens are English either way, or whose language has no captures). Never silently English.
    by_locale = listing.get("localeScreenshots") or {}
    if not isinstance(by_locale, dict):
        problems.add(listing_path, "localeScreenshots is {\"<locale>\": {\"manifest\": [keys…]} | \"en-US\"}")
        by_locale = {}
    for locale, shots in by_locale.items():
        if locale not in found or locale == "en-US":
            problems.add(listing_path, f"localeScreenshots has '{locale}', which is not a non-English play/<locale>/ folder")
        elif shots != "en-US":
            check_play_shots(app, shots if isinstance(shots, dict) else {}, locale.split("-")[0],
                             f"localeScreenshots.{locale}", listing_path, problems)
    for locale in found:
        if locale != "en-US" and locale not in by_locale:
            problems.add(listing_path, f"localeScreenshots has no '{locale}' — name its own captures, or \"en-US\" "
                                       "for the English set")


def check_play_shots(app, shots, lang, where, listing_path, problems):
    """One locale's Play screenshots: published Android `manifest` keys in that locale's language, or a
    `local` folder under build/."""
    if set(shots) - {"manifest", "local"} or len(shots) != 1:
        problems.add(listing_path, f"{where} is {{\"manifest\": [keys…]}} or {{\"local\": \"build/…\"}}")
    keys = shots.get("manifest") or []
    if "manifest" in shots:
        manifest_path = os.path.join(STORE, app, "screenshots.manifest.json")
        manifest = load_json(manifest_path, problems) if os.path.exists(manifest_path) else None
        if not 2 <= len(keys) <= 8:
            problems.add(listing_path, f"{where}: Play takes 2–8 phone screenshots, listed {len(keys)}")
        for k in keys:
            if manifest is None or k not in (manifest.get("screens") or {}):
                problems.add(listing_path, f"{where}: '{k}' is not in screenshots.manifest.json")
            elif not k.startswith("android/"):
                problems.add(listing_path, f"{where}: '{k}' is not an Android capture")
            elif k.split("/")[2] != lang:
                problems.add(listing_path, f"{where}: '{k}' is not a '{lang}' capture — map the locale to \"en-US\" "
                                           "to show the English set")
    if "local" in shots and not str(shots["local"]).startswith("build/"):
        problems.add(listing_path, f"{where}: a local screenshot folder lives under build/ — no binary under store/")


HELP_LINK = re.compile(r"tenshu\.app/apps/(?:([a-z]+)/support/([a-z]+)/([a-z]{2})|support/([a-z]{2}))(?![\w/])")
APEX_CONTENT = os.path.join(ROOT, "web", "apex", "content")


def check_help_link(app, locale, text, where, problems, platform="android"):
    """Every tenshu.app support link is a page that exists, for this platform, in this listing's language:
    an app's page needs web/apex/content/apps/<app>/support/<lang>.md listing the platform; the hub
    (/apps/support/<lang>) needs the Getting started page in that language, and is only for an app with
    no page of its own."""
    lang = locale.split("-")[0]
    for m in HELP_LINK.finditer(text):
        page_app, page_platform, page_lang, hub_lang = m.groups()
        link_lang = page_lang or hub_lang
        if link_lang != lang:
            problems.add(where, f"{m.group(0)} is in '{link_lang}', the listing in '{lang}'")
        if hub_lang:
            if not os.path.exists(os.path.join(APEX_CONTENT, "support", "getting-started", f"{hub_lang}.md")):
                problems.add(where, f"{m.group(0)}: the support hub has no '{hub_lang}' page")
            own = os.path.join(APEX_CONTENT, "apps", app, "support", f"{hub_lang}.md")
            if os.path.exists(own):
                problems.add(where, f"{m.group(0)}: {app} has its own support page — link "
                                    f"tenshu.app/apps/{app}/support/{platform}/{hub_lang}")
            continue
        page = os.path.join(APEX_CONTENT, "apps", page_app, "support", f"{page_lang}.md")
        if not os.path.exists(page):
            problems.add(where, f"{m.group(0)}: no support page {os.path.relpath(page, ROOT)}")
            continue
        declared = re.search(r"^platforms:\s*(.*)$", read(page), re.M)
        if page_platform != platform or not declared or page_platform not in [p.strip() for p in declared.group(1).split(",")]:
            problems.add(where, f"{m.group(0)}: the page has no '{page_platform}' platform, or this is not the {platform} listing")


def cmd_play_check(apps):
    problems = Problems()
    for app in apps:
        if app not in PLAY_APPS:
            problems.add("store", f"unknown app {app} (known: {', '.join(PLAY_APPS)})")
            continue
        check_play_app(app, problems)
    if problems:
        print("store-listing: the Play listing files have problems (docs/how-to/store-listings.md §8):", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1
    print(f"store-listing: ok (Play: {', '.join(apps)})")
    return 0


def cmd_check(apps):
    problems = Problems()
    for app in apps:
        check_app(app, problems)
    if problems:
        print("store-listing: the listing files have problems (docs/how-to/store-listings.md §2):", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1
    pushed = [a for a in apps if a in PUSHED_APPS]
    copy_only = [a for a in apps if a not in PUSHED_APPS]
    parts = [f"{', '.join(pushed)}: limits, copy rules, claims, categories, age rating, listing.json"] if pushed else []
    parts += [f"{', '.join(copy_only)}: limits, copy rules, claims (copy only)"] if copy_only else []
    print(f"store-listing: ok ({'; '.join(parts)})")
    return 0


def refuse_copy_only(app):
    """True (and says why) for an app that is not pushed: deliver would lack a bundle id, rating, notes or screenshots."""
    if app in PUSHED_APPS:
        return False
    missing = [f for f in PIPELINE_FILES if not os.path.exists(os.path.join(STORE, app, f))]
    why = f"no {', '.join(missing)} yet" if missing else "not in PUSHED_APPS yet"
    print(f"store-listing: {app} has listing copy only — {why}, so there is nothing to push "
          f"(pushed apps: {', '.join(PUSHED_APPS)})", file=sys.stderr)
    return True


def cmd_assemble(app, out):
    """<out>/metadata in deliver's layout, from store/shared + store/<app>. Refuses a listing that fails check."""
    if refuse_copy_only(app) or cmd_check([app]) != 0:
        return 1
    meta = os.path.join(out, "metadata")
    shutil.rmtree(meta, ignore_errors=True)
    os.makedirs(meta)
    for locale in locales(app):
        os.makedirs(os.path.join(meta, locale))
        for key in LOCALIZED:
            src = source_for(app, locale, key)
            if src:
                shutil.copyfile(src, os.path.join(meta, locale, f"{key}.txt"))
    for key in NON_LOCALIZED:
        src = non_localized_source(app, key)
        if src:
            shutil.copyfile(src, os.path.join(meta, f"{key}.txt"))
    os.makedirs(os.path.join(meta, "review_information"))
    shutil.copyfile(os.path.join(STORE, app, "review", "notes.txt"), os.path.join(meta, "review_information", "notes.txt"))
    shutil.copyfile(os.path.join(STORE, app, "age_rating.json"), os.path.join(out, "age_rating.json"))
    listing = json.loads(read(os.path.join(STORE, app, "listing.json")))
    with open(os.path.join(out, "bundle_id"), "w") as f:
        f.write(listing["ios"]["bundleId"] + "\n")
    print(f"store-listing: {app} — metadata for {', '.join(locales(app))} in {os.path.relpath(meta, ROOT)}")
    return 0


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_verified(url, sha):
    """The cached file for `sha`, downloading `url` first if needed. A hash mismatch fails, never re-pins."""
    cached = os.path.join(CACHE, f"{sha}.png")
    if os.path.exists(cached) and sha256_of(cached) == sha:
        return cached
    os.makedirs(CACHE, exist_ok=True)
    partial = cached + ".part"
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "tenshu-store-listing"})
        with urllib.request.urlopen(request, timeout=60) as response, open(partial, "wb") as out:
            shutil.copyfileobj(response, out)
        got = sha256_of(partial)
        if got != sha:
            raise SystemExit(f"store-listing: {url} does NOT hash to its manifest entry\n"
                             f"    manifest: {sha}\n    got:      {got}\n"
                             "  An object under sha256/ never changes; something between here and R2 is wrong. Nothing was assembled.")
        os.replace(partial, cached)
    finally:
        if os.path.exists(partial):
            os.remove(partial)
    return cached


def cmd_screenshots(app, out):
    """The listed screens into <out>/screenshots/<locale>/ as `<device>-NN-<screen>.png`: deliver sorts each
    device's set by file name, so the NN is listing.json's order, not the screen's own number."""
    listing = json.loads(read(os.path.join(STORE, app, "listing.json")))
    manifest = json.loads(read(os.path.join(STORE, app, "screenshots.manifest.json")))
    shots = listing["ios"]["screenshots"]
    suffix = "framed" if shots.get("framed") else ""
    target = os.path.join(out, "screenshots")
    shutil.rmtree(target, ignore_errors=True)
    total, missing, per_device = 0, [], {}
    for locale, lang in sorted(listing["ios"]["languages"].items()):
        folder = os.path.join(target, locale)
        os.makedirs(folder)
        for device in shots["devices"]:
            got = per_device.setdefault((locale, device), [])
            for n, screen in enumerate(shots["screens"], 1):
                key = f"ios/{device}{suffix}/{lang}/{screen}"
                entry = manifest["screens"].get(key)
                if entry is None:
                    missing.append((locale, device, key))
                    continue
                if (entry["width"], entry["height"]) not in DEVICE_SIZES[device]:
                    raise SystemExit(f"store-listing: {key} is {entry['width']}×{entry['height']}, not a size App Store "
                                     f"Connect accepts for {device} ({sorted(DEVICE_SIZES[device])})")
                src = fetch_verified(f"{manifest['base']}{entry['sha256']}.png", entry["sha256"])
                shutil.copyfile(src, os.path.join(folder, f"{device}-{n:02d}-{screen}.png"))
                got.append(screen)
                total += 1
    # A device with none at all gets one line below; a gap inside a set is named screen by screen.
    for locale, device, key in missing:
        if per_device.get((locale, device)):
            print(f"store-listing: WARNING {app}: {key} is not published — the listing will have no such screenshot", file=sys.stderr)
    listed = len(shots["screens"])
    for (locale, device), got in sorted(per_device.items()):
        print(f"store-listing: {app} {locale} {device} → {DEVICE_DISPLAY_TYPES[device]}: {len(got)} of {listed} screen(s)")
    for locale in sorted(listing["ios"]["languages"]):
        for device in REQUIRED_DEVICES:
            if per_device.get((locale, device)):
                continue
            if device == "iphone69":
                # Never a fallback to another set (the landing captures are not App Store screenshots): stop.
                raise SystemExit(f"store-listing: {app} has NO {device} screenshot for {locale} — App Store Connect "
                                 f"requires the 6.9\" iPhone slot. Capture and publish ios/{device}{suffix}/… first "
                                 "(docs/how-to/store-screenshots.md), or push with --no-screenshots.")
            print(f"store-listing: WARNING {app}: NO {device} screenshot for {locale} — the app runs on iPad "
                  f"(TARGETED_DEVICE_FAMILY \"1,2\"), so App Store Connect will not accept a submission without "
                  f"the 13\" iPad slot, and a push (overwrite_screenshots) removes any iPad set uploaded by hand. "
                  f"Capturing it: docs/how-to/store-screenshots.md (§3a–§3c for Fit, Calendar and Contacts, whose "
                  f"iPad walk may not run yet), then publish it; listing.json's devices must name {device}", file=sys.stderr)
    print(f"store-listing: {app} — {total} screenshot(s) in {os.path.relpath(target, ROOT)}, each verified against its sha256")
    return 0


def cmd_digest(directory):
    """sha256 over every file's relative path and bytes, in sorted order (Preview.html excluded)."""
    digest = hashlib.sha256()
    for dirpath, dirs, files in os.walk(directory):
        dirs.sort()
        for name in sorted(files):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, directory)
            if rel in ("Preview.html", "preview.stamp"):
                continue
            digest.update(rel.encode() + b"\0" + sha256_of(full).encode() + b"\n")
    print(digest.hexdigest())
    return 0


def main(argv):
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0 if len(argv) >= 2 else 2
    cmd, args = argv[1], argv[2:]
    if cmd == "check":
        bad = [a for a in args if a not in APPS]
        if bad:
            print(f"store-listing: unknown app {bad[0]} (known: {', '.join(APPS)})", file=sys.stderr)
            return 2
        status = cmd_check(args or list(APPS))
        play = [a for a in (args or PLAY_APPS) if os.path.isdir(os.path.join(STORE, a, "play"))]
        return (cmd_play_check(play) if play else 0) or status
    if cmd == "play-check":
        return cmd_play_check(args or list(PLAY_APPS))
    if cmd in ("assemble", "screenshots") and len(args) == 2 and args[0] in APPS:
        if refuse_copy_only(args[0]):
            return 2
        return (cmd_assemble if cmd == "assemble" else cmd_screenshots)(args[0], os.path.abspath(args[1]))
    if cmd == "pushable" and len(args) == 1:
        if args[0] not in APPS:
            print(f"store-listing: unknown app {args[0]} (known: {', '.join(APPS)})", file=sys.stderr)
            return 2
        return 1 if refuse_copy_only(args[0]) else 0
    if cmd == "digest" and len(args) == 1:
        return cmd_digest(args[0])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
