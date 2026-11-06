#!/usr/bin/env python3
"""Tests for scripts/store-listing.py — the limits, the copy rules, the claims guard and the screenshot fetch.

  python3 scripts/test_store_listing.py      (no network: screenshots are served from file:// URLs)
"""
import hashlib
import importlib.util
import io
import json
import os
import shutil
import struct
import sys
import tempfile
import unittest
import zlib
from contextlib import redirect_stderr, redirect_stdout

sys.dont_write_bytecode = True  # importing store-listing.py must not leave a __pycache__ behind
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("store_listing", os.path.join(HERE, "store-listing.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
REAL_STORE = sl.STORE


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def read_json(path):
    return json.loads(read_text(path))


def write_file(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def png(width, height):
    raw = b"".join(b"\0" + b"\x10\x20\x30" * width for _ in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class StoreCopy(unittest.TestCase):
    """Each test works on a throwaway copy of store/, so it can break one file at a time."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.store = os.path.join(self.tmp, "store")
        shutil.copytree(REAL_STORE, self.store, ignore=shutil.ignore_patterns("screenshots", "fixtures"))
        sl.STORE = self.store
        sl.CACHE = os.path.join(self.tmp, "cache")

    def tearDown(self):
        sl.STORE = REAL_STORE
        shutil.rmtree(self.tmp)

    def write(self, rel, text):
        with open(os.path.join(self.store, rel), "w", encoding="utf-8") as f:
            f.write(text)

    def problems(self, app="drive"):
        p = sl.Problems()
        sl.check_app(app, p)
        return p

    def assertProblem(self, needle, app="drive"):
        found = self.problems(app)
        self.assertTrue(any(needle in p for p in found), f"no problem mentioning {needle!r} in {list(found)}")


class TheCommittedListings(StoreCopy):
    def test_every_app_passes(self):
        for app in sl.APPS:
            self.assertEqual(list(self.problems(app)), [], app)

    def test_every_app_with_copy_is_checked(self):
        # A store/<app>/<locale>/ folder the validator does not know would be pushed unchecked one day.
        with_copy = {a for a in os.listdir(self.store) if os.path.isdir(os.path.join(self.store, a)) and sl.locales(a)}
        self.assertEqual(with_copy - {"shared"}, set(sl.APPS))


class CopyOnlyApps(StoreCopy):
    """The apps with copy and claims.md but not every pipeline file yet: the copy rules hold, the pipeline files wait."""

    def test_pipeline_files_are_not_required(self):
        for f in ("listing.json", "age_rating.json", "primary_category.txt"):
            self.assertFalse(os.path.exists(os.path.join(self.store, "maps", f)), f)
        self.assertEqual(list(self.problems("maps")), [])

    def test_but_a_pushed_app_still_needs_them(self):
        os.remove(os.path.join(self.store, "drive/age_rating.json"))
        self.assertProblem("age rating questionnaire")
        os.remove(os.path.join(self.store, "drive/listing.json"))
        self.assertProblem("listing.json: missing")

    def test_a_pipeline_file_that_exists_is_still_checked(self):
        self.write("maps/primary_category.txt", "MAPS\n")
        self.assertProblem("not an App Store category id", app="maps")

    def test_copy_rules_apply(self):
        self.write("fit/en-US/promotional_text.txt", "The best way to track workouts.\n")
        self.assertProblem("no superlatives", app="fit")
        self.write("timeline/en-US/support_url.txt", "https://tenshu.app/apps/drive/support/ios/en\n")
        self.assertProblem("the support URL is https://tenshu.app/apps/timeline/support/ios/en", app="timeline")

    def test_claims_apply(self):
        path = os.path.join(self.store, "contacts/en-US/description.txt")
        text = read_text(path)
        marker = next(l for l in text.splitlines() if l.startswith("- ") and "PRIVATE" not in l
                      and l in text.split("PRIVATE", 1)[1])
        self.write("contacts/en-US/description.txt", text.replace(marker, "- Nobody can ever see anything. " + marker[2:]))
        self.assertProblem("privacy sentence has no entry in claims.md: \"Nobody can ever see anything.\"", app="contacts")

    def test_captions_need_screens(self):
        # Every app has a screens.json now, so take Maps' away in this copy of the store.
        os.remove(os.path.join(self.store, "maps", "screens.json"))
        self.write("maps/en-US/captions.txt", "01-map: Your map\n")
        self.assertProblem("captions need screens.json", app="maps")

    def test_assemble_refuses(self):
        with redirect_stderr(io.StringIO()) as err:
            self.assertEqual(sl.cmd_assemble("maps", os.path.join(self.tmp, "out")), 1)
        self.assertIn("listing copy only", err.getvalue())
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "out")))

    def test_pushable_names_what_is_missing(self):
        # store-push.sh asks this first.
        with redirect_stderr(io.StringIO()) as err:
            self.assertEqual(sl.main(["store-listing.py", "pushable", "maps"]), 1)
        self.assertIn("no listing.json, age_rating.json, primary_category.txt yet", err.getvalue())
        for app in sl.PUSHED_APPS:
            self.assertEqual(sl.main(["store-listing.py", "pushable", app]), 0, app)


class PushedApps(StoreCopy):
    def test_every_pushed_app_has_its_pipeline_files(self):
        for app in sl.PUSHED_APPS:
            for f in sl.PIPELINE_FILES:
                self.assertTrue(os.path.exists(os.path.join(self.store, app, f)), f"{app}/{f}")

    def test_secondary_category_is_optional(self):
        self.assertFalse(os.path.exists(os.path.join(self.store, "fit/secondary_category.txt")))
        self.assertEqual(list(self.problems("fit")), [])
        os.remove(os.path.join(self.store, "drive/secondary_category.txt"))
        self.assertEqual(list(self.problems("drive")), [])

    def test_primary_category_is_not(self):
        os.remove(os.path.join(self.store, "fit/primary_category.txt"))
        self.assertProblem("primary_category.txt is required", app="fit")

    def test_listing_must_offer_the_iphone_slot(self):
        listing = read_json(os.path.join(self.store, "calendar/listing.json"))
        listing["ios"]["screenshots"]["devices"] = ["ipad13"]
        self.write("calendar/listing.json", json.dumps(listing))
        self.assertProblem("must include iphone69", app="calendar")

    def test_bundle_ids_are_the_apps_own(self):
        for app in sl.PUSHED_APPS:
            listing = read_json(os.path.join(self.store, app, "listing.json"))
            self.assertEqual(listing["ios"]["bundleId"], f"app.tenshu.{app}.ios")


class Limits(StoreCopy):
    def test_name_over_30_chars(self):
        self.write("drive/en-US/name.txt", "Tenshu Drive — encrypted files for you\n")
        self.assertProblem("over App Store Connect's 30")

    def test_trailing_newline_does_not_count(self):
        # deliver strips every value, so a file of exactly 30 characters plus "\n" is fine.
        self.write("drive/en-US/subtitle.txt", "x" * 30 + "\n")
        self.assertEqual(list(self.problems()), [])

    def test_keywords_are_counted_in_bytes(self):
        # 50 "ü" are 50 characters but 100 bytes, plus the comma → over the limit.
        self.write("drive/en-US/keywords.txt", "files," + "ü" * 50 + "\n")
        self.assertProblem("bytes, over App Store Connect's 100")

    def test_promotional_text_over_170(self):
        self.write("drive/en-US/promotional_text.txt", "a" * 171)
        self.assertProblem("over App Store Connect's 170")

    def test_description_over_4000(self):
        with open(os.path.join(self.store, "drive/en-US/description.txt"), "a", encoding="utf-8") as f:
            f.write("x" * 4000)
        self.assertProblem("over App Store Connect's 4000")

    def test_missing_required_file(self):
        os.remove(os.path.join(self.store, "drive/en-US/keywords.txt"))
        self.assertProblem("keywords.txt is required")

    def test_shared_privacy_url_counts_for_every_app(self):
        os.remove(os.path.join(self.store, "shared/en-US/privacy_url.txt"))
        self.assertProblem("privacy_url.txt is required")
        self.assertProblem("privacy_url.txt is required", app="control")


class CopyRules(StoreCopy):
    def test_superlative(self):
        self.write("drive/en-US/promotional_text.txt", "The most secure place for your files.\n")
        self.assertProblem("no superlatives")

    def test_superlative_is_a_whole_word(self):
        self.write("drive/en-US/promotional_text.txt", "Files you bestow on Drive stay encrypted.\n")
        self.assertEqual(list(self.problems()), [])

    def test_competitor(self):
        self.write("drive/en-US/promotional_text.txt", "Like iCloud, but encrypted.\n")
        self.assertProblem("name no competitor")

    def test_keyword_repeating_the_name(self):
        self.write("drive/en-US/keywords.txt", "tenshu,files\n")
        self.assertProblem("repeats the app name")

    def test_support_url_shape(self):
        self.write("drive/en-US/support_url.txt", "https://tenshu.app/support\n")
        self.assertProblem("the support URL is https://tenshu.app/apps/drive/support/ios/en")

    def test_unknown_category(self):
        self.write("drive/primary_category.txt", "FILES\n")
        self.assertProblem("not an App Store category id")

    def test_age_rating_wrong_type(self):
        rating = read_json(os.path.join(self.store, "drive/age_rating.json"))
        rating["userGeneratedContent"] = "NONE"
        self.write("drive/age_rating.json", json.dumps(rating))
        self.assertProblem("'userGeneratedContent' must be true or false")

    def test_social_media_must_be_answered(self):
        rating = read_json(os.path.join(self.store, "drive/age_rating.json"))
        del rating["socialMedia"]
        self.write("drive/age_rating.json", json.dumps(rating))
        self.assertProblem("'socialMedia' is unanswered")

    def test_social_media_age_restriction_only_when_social_media(self):
        rating = read_json(os.path.join(self.store, "drive/age_rating.json"))
        rating["socialMediaAgeRestricted"] = False
        self.write("drive/age_rating.json", json.dumps(rating))
        self.assertProblem("asked only when 'socialMedia' is true")
        rating["socialMedia"] = True
        del rating["socialMediaAgeRestricted"]
        self.write("drive/age_rating.json", json.dumps(rating))
        self.assertProblem("'socialMediaAgeRestricted' is unanswered")
        rating["socialMediaAgeRestricted"] = True
        self.write("drive/age_rating.json", json.dumps(rating))
        self.assertEqual(list(self.problems()), [])

    def test_both_frequency_value_sets_are_accepted(self):
        rating = read_json(os.path.join(self.store, "fit/age_rating.json"))
        for v in ("INFREQUENT", "INFREQUENT_OR_MILD", "FREQUENT", "FREQUENT_OR_INTENSE"):
            rating["medicalOrTreatmentInformation"] = v
            self.write("fit/age_rating.json", json.dumps(rating))
            self.assertEqual(list(self.problems("fit")), [], v)
        rating["medicalOrTreatmentInformation"] = "SOMETIMES"
        self.write("fit/age_rating.json", json.dumps(rating))
        self.assertProblem("'medicalOrTreatmentInformation' must be one of", app="fit")

    def test_review_contact_is_not_committed(self):
        self.write("drive/review/phone_number.txt", "+49 …\n")
        self.assertProblem("personal data")

    def test_caption_for_an_unknown_screen(self):
        self.write("drive/en-US/captions.txt", "99-nope: A caption\n")
        self.assertProblem("'99-nope' is not in screens.json")

    def test_listing_screen_must_be_captured(self):
        listing = read_json(os.path.join(self.store, "drive/listing.json"))
        listing["ios"]["screenshots"]["screens"].append("99-nope")
        self.write("drive/listing.json", json.dumps(listing))
        self.assertProblem("'99-nope' is not a captured screen")


class Claims(StoreCopy):
    def test_unsourced_privacy_sentence(self):
        path = os.path.join(self.store, "drive/en-US/description.txt")
        text = read_text(path).replace(
            "- Without a storage plan,", "- Nobody can ever see anything. Without a storage plan,")
        self.write("drive/en-US/description.txt", text)
        self.assertProblem("privacy sentence has no entry in claims.md: \"Nobody can ever see anything.\"")

    def test_source_must_exist(self):
        path = os.path.join(self.store, "drive/claims.md")
        self.write("drive/claims.md", read_text(path).replace(
            "docs/adr/0018-file-sharing-mode-a.md", "docs/adr/9999-gone.md"))
        self.assertProblem("source 'docs/adr/9999-gone.md' does not exist")

    def test_stale_claim(self):
        with open(os.path.join(self.store, "drive/claims.md"), "a", encoding="utf-8") as f:
            f.write("\n> A sentence the description no longer has.\n- source: AGENTS.md\n")
        self.assertProblem("is in no description any more")

    def test_sentences_come_from_the_privacy_section_only(self):
        sentences = sl.privacy_sentences("INTRO\n- Not this.\n\nHOW IT KEEPS IT PRIVATE\n- One. Two!\n\nOTHER\n- Not this.\n")
        self.assertEqual(sentences, ["One.", "Two!"])


class PlayListings(StoreCopy):
    """store/<app>/play/: Play's own limits, no iOS facts, Control named, the claims shared with the App Store."""

    def play_problems(self, app="drive"):
        p = sl.Problems()
        sl.check_play_app(app, p)
        return p

    def assertPlayProblem(self, needle, app="drive"):
        found = self.play_problems(app)
        self.assertTrue(any(needle in p for p in found), f"no problem mentioning {needle!r} in {list(found)}")

    def test_every_android_app_has_a_passing_listing(self):
        for app in sl.PLAY_APPS:
            self.assertEqual(list(self.play_problems(app)), [], app)

    def test_short_description_limit(self):
        self.write("drive/play/en-US/short_description.txt", "x" * 81 + "\n")
        self.assertPlayProblem("over Play's 80")
        self.write("drive/play/en-US/short_description.txt", "x" * 80 + "\n")
        self.assertEqual(list(self.play_problems()), [])

    def test_title_and_full_description_limits(self):
        self.write("drive/play/en-US/title.txt", "Tenshu " + "x" * 24 + "\n")
        self.assertPlayProblem("over Play's 30")
        full = read_text(os.path.join(self.store, "drive/play/en-US/full_description.txt"))
        self.write("drive/play/en-US/full_description.txt", full + "x" * 4000)
        self.assertPlayProblem("over Play's 4000")

    def test_no_ios_fact(self):
        self.write("drive/play/en-US/short_description.txt", "Your files on iPhone.\n")
        self.assertPlayProblem("an iOS fact")

    def test_a_client_names_control(self):
        full = read_text(os.path.join(self.store, "maps/play/en-US/full_description.txt"))
        self.write("maps/play/en-US/full_description.txt", full.replace("Tenshu Control", "the hub app"))
        self.assertPlayProblem("needs Tenshu Control", app="maps")

    def test_a_play_privacy_sentence_needs_a_claim(self):
        full = read_text(os.path.join(self.store, "fit/play/en-US/full_description.txt"))
        self.write("fit/play/en-US/full_description.txt",
                   full.replace("Without a storage plan, your data stays on this device.", "Nothing ever leaves.", 1))
        self.assertPlayProblem("no entry in claims.md", app="fit")

    def test_a_play_only_claim_is_not_stale(self):
        # Quoted by the Play description only: the App Store check must not call it stale.
        self.assertEqual(list(self.problems("fit")), [])

    def test_package_must_match_the_app(self):
        path = os.path.join(self.store, "drive/play/listing.json")
        listing = read_json(path)
        listing["package"] = "app.tenshu.maps.android"
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("package is app.tenshu.drive.android")

    def test_screenshots_must_be_published_android_captures(self):
        path = os.path.join(self.store, "drive/play/listing.json")
        listing = read_json(path)
        listing["screenshots"] = {"manifest": ["android/phone/en/01-files", "ios/iphone69/en/01-files"]}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("not an Android capture")
        listing["screenshots"] = {"local": "store/drive/shots"}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("lives under build/")


class GermanPlayListings(StoreCopy):
    """store/<app>/play/de-DE/: the same rules, German superlatives, and claims that only translate English ones."""

    def play_problems(self, app="drive"):
        p = sl.Problems()
        sl.check_play_app(app, p)
        return p

    def assertPlayProblem(self, needle, app="drive"):
        found = self.play_problems(app)
        self.assertTrue(any(needle in p for p in found), f"no problem mentioning {needle!r} in {list(found)}")

    def full(self):
        return read_text(os.path.join(self.store, "drive/play/de-DE/full_description.txt"))

    def test_the_german_listing_is_checked(self):
        self.assertEqual(sl.play_locales("drive"), ["de-DE", "en-US"])
        self.write("drive/play/de-DE/short_description.txt", "x" * 81 + "\n")
        self.assertPlayProblem("de-DE/short_description.txt: 81 chars, over Play's 80")

    def test_german_superlatives_are_caught_by_stem(self):
        for text, hit in [
            ("Der sicherste Speicher.", "sicherste"), ("Absolut sichere Verschlüsselung.", "absolut sichere"),
            ("Die Allerbeste.", "allerbeste"), ("Die bestmögliche App.", "bestmögliche"),
            ("Unknackbare Schlüssel.", "unknackbare"), ("Garantierte Sicherheit.", "garantierte"),
            ("Militär-Verschlüsselung.", "militär"), ("Branchenführende Technik.", "branchenführende"),
            ("Höchste Sicherheit.", "höchste"), ("Hundertprozentig sicher.", "hundertprozentig"),
            ("Vollständig sicher.", "vollständig sicher"), ("Die Nr. 1 für Dateien.", "nr. 1"),
            ("100 Prozent privat.", "100 prozent"), ("100\u00a0% privat.", "100\u00a0%"),
            ("100\u202f% privat.", "100\u202f%"),
        ]:
            with self.subTest(text=text):
                self.write("drive/play/de-DE/short_description.txt", text + "\n")
                self.assertPlayProblem(f"\"{hit}\" — no superlatives")

    def test_german_superlative_stems_stay_whole_words(self):
        for text in ("Deine Bestellung.", "Geräte bestätigen.", "Anführende Zeichen.", "Was bestehen bleibt."):
            with self.subTest(text=text):
                self.write("drive/play/de-DE/short_description.txt", text + "\n")
                self.assertEqual(list(self.play_problems()), [])

    def test_the_german_privacy_heading_is_found(self):
        self.write("drive/play/de-DE/full_description.txt",
                   self.full().replace("WIE DRIVE DEINE DATEIEN PRIVAT HÄLT", "DATENSCHUTZ"))
        self.assertPlayProblem("no privacy section")

    def test_a_german_sentence_needs_its_own_claim(self):
        self.write("drive/play/de-DE/full_description.txt",
                   self.full().replace("Ohne Speicherplan verlässt nichts dein Gerät.", "Nichts verlässt je dein Gerät.", 1))
        self.assertPlayProblem("no entry in claims.md")

    def test_a_german_claim_must_translate_an_english_one(self):
        claims = read_text(os.path.join(self.store, "drive/claims.md"))
        self.write("drive/claims.md", claims.replace(
            '- translates: "Without a storage plan, nothing leaves your device."\n', ""))
        self.assertPlayProblem("names the English claim it translates")

    def test_changing_the_english_claim_flags_its_translation(self):
        claims = read_text(os.path.join(self.store, "drive/claims.md"))
        self.write("drive/claims.md", claims.replace(
            '- translates: "Without a storage plan, nothing leaves your device."',
            '- translates: "Without a plan, nothing ever leaves your device."'))
        self.assertPlayProblem("which is not an English claim")

    def test_a_dropped_caveat_fails(self):
        # Every sentence keeps its claim, so only the completeness check can see the caveat is gone.
        caveat = " Einige Fakten, die der Server zum Synchronisieren braucht, sieht er trotzdem: wie viel du " \
                 "speicherst, wie viele Geräte du hast und wann sie synchronisieren."
        self.assertIn(caveat, self.full())
        self.write("drive/play/de-DE/full_description.txt", self.full().replace(caveat, ""))
        self.assertPlayProblem("does not translate the English one's claims one for one")

    def test_claims_out_of_order_fail(self):
        a = "- Ohne Speicherplan verlässt nichts dein Gerät."
        full = self.full()
        line = next(l for l in full.splitlines() if l.startswith(a))
        moved = full.replace(line + "\n", "").replace("WIE DRIVE DEINE DATEIEN PRIVAT HÄLT\n",
                                                      "WIE DRIVE DEINE DATEIEN PRIVAT HÄLT\n" + line + "\n")
        self.write("drive/play/de-DE/full_description.txt", moved)
        self.assertPlayProblem("order differs")

    def test_a_second_translates_line_fails(self):
        claims = read_text(os.path.join(self.store, "drive/claims.md"))
        line = '- translates: "Without a storage plan, nothing leaves your device."\n'
        self.write("drive/claims.md", claims.replace(line, line + '- translates: "Your files are encrypted on your device before they are uploaded."\n'))
        self.assertPlayProblem("a second `- translates:`")

    def test_help_links_must_exist_in_the_listings_language(self):
        full = self.full()
        self.write("drive/play/de-DE/full_description.txt", full.replace("support/android/de", "support/android/en"))
        self.assertPlayProblem("is in 'en', the listing in 'de'")
        self.write("drive/play/de-DE/full_description.txt", full.replace("support/android/de", "support/android/xx"))
        self.assertPlayProblem("no support page")
        self.write("drive/play/de-DE/full_description.txt", full.replace("apps/drive/support/android/de", "apps/maps/support/android/de"))
        self.assertPlayProblem("no support page")
        self.write("drive/play/de-DE/full_description.txt", full.replace("apps/drive/support/android/de", "apps/support/de"))
        self.assertPlayProblem("drive has its own support page")

    def test_the_feature_graphic_needs_a_german_tagline(self):
        path = os.path.join(self.store, "drive/play/listing.json")
        listing = read_json(path)
        del listing["taglines"]
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("taglines has no 'de-DE'")
        listing["taglines"] = {"de-DE": "Der beste Speicher"}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("taglines.de-DE: \"beste\"")
        listing["taglines"] = {"de-DE": "Speicher", "fr-FR": "Stockage"}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("taglines has 'fr-FR', which has no play/fr-FR/ folder")


    def test_every_other_locale_names_its_screenshots(self):
        path = os.path.join(self.store, "drive/play/listing.json")
        listing = json.loads(read_text(path))
        listing.pop("localeScreenshots", None)
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("localeScreenshots has no 'de-DE'")
        listing["localeScreenshots"] = {"de-DE": "en-US"}
        write_file(path, json.dumps(listing))
        self.assertEqual(list(self.play_problems()), [])
        listing["localeScreenshots"] = {"de-DE": "en-US", "fr-FR": "en-US"}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("localeScreenshots has 'fr-FR'")

    def test_a_locales_own_set_is_in_its_language(self):
        path = os.path.join(self.store, "drive/play/listing.json")
        listing = json.loads(read_text(path))
        english = listing["screenshots"]["manifest"]
        listing["localeScreenshots"] = {"de-DE": {"manifest": english}}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("is not a 'de' capture")
        listing["localeScreenshots"] = {"de-DE": {"manifest": ["android/phone/de/99-nothing", "android/phone/de/98-none"]}}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("localeScreenshots.de-DE: 'android/phone/de/99-nothing' is not in screenshots.manifest.json")
        listing["localeScreenshots"] = {"de-DE": {"local": "store/x"}}
        write_file(path, json.dumps(listing))
        self.assertPlayProblem("a local screenshot folder lives under build/")

class PlayPublishLeavesUnsentImagesAlone(unittest.TestCase):
    """A locale assembled without screenshots (no captures at all) must never send a DELETE for them."""

    def test_no_screenshot_folder_no_delete(self):
        spec = importlib.util.spec_from_file_location("play_publish", os.path.join(HERE, "lib", "play_publish.py"))
        pp = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pp)
        tmp = tempfile.mkdtemp()
        try:
            write_file(os.path.join(tmp, "package"), "app.tenshu.drive.android\n")
            for locale in ("en-US", "de-DE"):
                os.makedirs(os.path.join(tmp, locale, "images"))
                for key in ("title", "short_description", "full_description"):
                    write_file(os.path.join(tmp, locale, f"{key}.txt"), "Tenshu Drive\n")
                with open(os.path.join(tmp, locale, "images", "featureGraphic.png"), "wb") as f:
                    f.write(png(4, 2))
            os.makedirs(os.path.join(tmp, "en-US", "images", "phoneScreenshots"))
            with open(os.path.join(tmp, "en-US", "images", "phoneScreenshots", "01.png"), "wb") as f:
                f.write(png(9, 16))
            calls = []

            class FakeEdit:
                def __init__(self, token, package):
                    pass

                def call(self, method, path, body=None):
                    calls.append((method, path))
                    return {}

                def upload_image(self, locale, image_type, path):
                    calls.append(("UPLOAD", f"/listings/{locale}/{image_type}"))

                def commit(self):
                    calls.append(("COMMIT", ""))

                def discard(self):
                    calls.append(("DISCARD", ""))

            pp.Edit = FakeEdit
            with redirect_stdout(io.StringIO()):
                pp.push_listing("token", tmp, dry_run=False)
            self.assertIn(("DELETE", "/listings/en-US/phoneScreenshots"), calls)
            self.assertNotIn(("DELETE", "/listings/de-DE/phoneScreenshots"), calls)
            self.assertFalse([c for c in calls if c[1] == "/listings/de-DE/phoneScreenshots"])
            self.assertIn(("UPLOAD", "/listings/de-DE/featureGraphic"), calls)
        finally:
            shutil.rmtree(tmp)


class Assemble(StoreCopy):
    def test_deliver_layout(self):
        out = os.path.join(self.tmp, "out")
        with redirect_stdout(io.StringIO()):
            self.assertEqual(sl.cmd_assemble("control", out), 0)
        meta = os.path.join(out, "metadata")
        self.assertEqual(sorted(os.listdir(meta)), ["copyright.txt", "de-DE", "en-US", "primary_category.txt",
                                                    "review_information", "secondary_category.txt"])
        self.assertEqual(sorted(os.listdir(os.path.join(meta, "en-US"))), sorted(f"{k}.txt" for k in sl.LOCALIZED))
        # captions.txt is ours, not deliver's: a file deliver does not know must not ride along.
        self.assertNotIn("captions.txt", os.listdir(os.path.join(meta, "en-US")))
        self.assertEqual(os.listdir(os.path.join(meta, "review_information")), ["notes.txt"])
        self.assertEqual(read_text(os.path.join(out, "bundle_id")).strip(), "app.tenshu.control.ios")

    def test_refuses_a_failing_listing(self):
        self.write("control/en-US/name.txt", "Control\n")
        with redirect_stderr(io.StringIO()):
            self.assertEqual(sl.cmd_assemble("control", os.path.join(self.tmp, "out")), 1)


class Screenshots(StoreCopy):
    def setUp(self):
        super().setUp()
        # These tests are about one locale's order, sizes and gaps: list English only, so the real
        # listing's other languages (de-DE) do not stop the run for want of their own captures.
        path = os.path.join(self.store, "drive", "listing.json")
        listing = json.loads(read_text(path))
        listing["ios"]["languages"] = {"en-US": "en"}
        self.write("drive/listing.json", json.dumps(listing))

    def publish(self, screens):
        """Fake R2: write PNGs under a file:// base and a manifest that names them."""
        r2 = os.path.join(self.tmp, "r2")
        os.makedirs(r2)
        entries = {}
        for key, (w, h, data) in screens.items():
            data = data if data is not None else png(w, h)
            sha = hashlib.sha256(data).hexdigest()
            with open(os.path.join(r2, f"{sha}.png"), "wb") as f:
                f.write(data)
            entries[key] = {"sha256": sha, "width": w, "height": h, "bytes": len(data)}
        self.write("drive/screenshots.manifest.json",
                   json.dumps({"version": 1, "base": f"file://{r2}/", "screens": entries}))
        return r2

    def run_fetch(self):
        out = os.path.join(self.tmp, "out")
        err = io.StringIO()
        with redirect_stdout(io.StringIO()), redirect_stderr(err):
            sl.cmd_screenshots("drive", out)
        return os.path.join(out, "screenshots", "en-US"), err.getvalue()

    def test_listing_order_and_names(self):
        self.publish({
            "ios/iphone69/en/03-preview": (1320, 2868, None),
            "ios/iphone69/en/01-files": (1320, 2868, None),
            "ios/iphone69/en/dark/01-files": (1320, 2868, png(1320, 2868)[:-1] + b"\1"),
        })
        folder, err = self.run_fetch()
        # listing.json puts 01-files first and 03-preview third; the NN is that position.
        self.assertEqual(sorted(os.listdir(folder)), ["iphone69-01-01-files.png", "iphone69-03-03-preview.png"])
        self.assertIn("ios/iphone69/en/02-photos is not published", err)
        # A device with no screen at all is one clear line, not one per screen.
        self.assertIn("NO ipad13 screenshot for en-US", err)
        self.assertNotIn("ios/ipad13/en/01-files is not published", err)

    def test_ipad_captures_are_taken_when_published(self):
        self.publish({
            "ios/iphone69/en/01-files": (1320, 2868, None),
            "ios/ipad13/en/01-files": (2064, 2752, None),
            "ios/ipad13/en/03-preview": (2048, 2732, None),
        })
        folder, err = self.run_fetch()
        self.assertEqual(sorted(os.listdir(folder)),
                         ["ipad13-01-01-files.png", "ipad13-03-03-preview.png", "iphone69-01-01-files.png"])
        self.assertNotIn("NO ipad13", err)
        self.assertIn("ios/ipad13/en/02-photos is not published", err)

    def test_an_ipad_capture_at_an_iphone_size_fails(self):
        self.publish({"ios/iphone69/en/01-files": (1320, 2868, None), "ios/ipad13/en/01-files": (1320, 2868, None)})
        with self.assertRaises(SystemExit) as caught:
            self.run_fetch()
        self.assertIn("not a size App Store Connect accepts for ipad13", str(caught.exception))

    def test_no_iphone_set_stops_and_never_falls_back_to_landing(self):
        # Only the landing page's captures exist (Photos' case): they are not App Store screenshots.
        self.publish({
            "ios/iphone69landing/en/01-files": (1320, 2868, None),
            "ios/ipad13/en/01-files": (2064, 2752, None),
        })
        with self.assertRaises(SystemExit) as caught:
            self.run_fetch()
        self.assertIn("NO iphone69 screenshot for en-US", str(caught.exception))

    def test_hash_mismatch_fails(self):
        r2 = self.publish({"ios/iphone69/en/01-files": (1320, 2868, None)})
        name = os.listdir(r2)[0]
        with open(os.path.join(r2, name), "ab") as f:
            f.write(b"tampered")
        with self.assertRaises(SystemExit) as caught:
            self.run_fetch()
        self.assertIn("does NOT hash to its manifest entry", str(caught.exception))

    def test_wrong_size_fails(self):
        self.publish({"ios/iphone69/en/01-files": (1290, 2000, None)})
        with self.assertRaises(SystemExit) as caught:
            self.run_fetch()
        self.assertIn("not a size App Store Connect accepts", str(caught.exception))

    def test_framed_set_reads_the_framed_device(self):
        listing = read_json(os.path.join(self.store, "drive/listing.json"))
        listing["ios"]["screenshots"]["framed"] = True
        self.write("drive/listing.json", json.dumps(listing))
        self.publish({
            "ios/iphone69/en/01-files": (1320, 2868, None),
            "ios/iphone69framed/en/01-files": (1320, 2868, png(1320, 2868)[:-1] + b"\2"),
        })
        folder, _ = self.run_fetch()
        self.assertEqual(os.listdir(folder), ["iphone69-01-01-files.png"])
        manifest = read_json(os.path.join(self.store, "drive/screenshots.manifest.json"))
        framed_sha = manifest["screens"]["ios/iphone69framed/en/01-files"]["sha256"]
        self.assertEqual(sl.sha256_of(os.path.join(folder, "iphone69-01-01-files.png")), framed_sha)


class Digest(unittest.TestCase):
    def test_changes_with_content_not_preview(self):
        with tempfile.TemporaryDirectory() as d:
            def digest():
                buf = io.StringIO()
                with redirect_stdout(buf):
                    sl.cmd_digest(d)
                return buf.getvalue().strip()
            write_file(os.path.join(d, "a.txt"), "one")
            first = digest()
            write_file(os.path.join(d, "Preview.html"), "<html>")
            self.assertEqual(digest(), first)
            write_file(os.path.join(d, "a.txt"), "two")
            self.assertNotEqual(digest(), first)


class GermanAppStoreClaims(StoreCopy):
    """store/<app>/de-DE/description.txt: its privacy section translates en-US's, one for one and in order."""

    def description(self):
        return read_text(os.path.join(self.store, "drive/de-DE/description.txt"))

    def test_the_committed_pair_passes(self):
        self.assertEqual([p for p in self.problems() if "de-DE" in p], [])

    def test_a_dropped_claim_fails(self):
        line = next(l for l in self.description().splitlines() if l.startswith("- Ohne Speicherplan"))
        self.write("drive/de-DE/description.txt", self.description().replace(line + "\n", ""))
        self.assertProblem("does not translate the English one's claims one for one")


@unittest.skipUnless(importlib.util.find_spec("PIL"), "play-listing-assets.py needs Pillow; CI's python3 has none")
class FeatureGraphicWrap(unittest.TestCase):
    """The banner's tagline wraps into as few lines as a greedy fill, then evens them out."""

    def setUp(self):
        spec = importlib.util.spec_from_file_location("play_listing_assets", os.path.join(HERE, "play-listing-assets.py"))
        self.pla = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.pla)

        class Monospace:  # 10 px a character, so widths are countable
            def textlength(self, text, font=None):
                return 10 * len(text)
        self.draw = Monospace()

    def test_no_word_left_alone(self):
        text = "Synchronization for your Tenshu apps"  # 36 characters; 310 px fits all but "apps"
        self.assertEqual(self.pla.wrap_greedy(self.draw, text, None, 310)[-1], "apps")
        lines = self.pla.wrap(self.draw, text, None, 310)
        self.assertEqual(lines, ["Synchronization for", "your Tenshu apps"])

    def test_never_more_lines_and_one_line_untouched(self):
        for width in (80, 150, 220, 310, 400):
            text = "Keys devices and sync for all of your Tenshu apps here"
            self.assertEqual(len(self.pla.wrap(self.draw, text, None, width)), len(self.pla.wrap_greedy(self.draw, text, None, width)))
            self.assertTrue(all(10 * len(l) <= width or " " not in l for l in self.pla.wrap(self.draw, text, None, width)))
        self.assertEqual(self.pla.wrap(self.draw, "Short", None, 400), ["Short"])


@unittest.skipUnless(importlib.util.find_spec("PIL"), "play-listing-assets.py needs Pillow; CI's python3 has none")
class FeatureGraphic(unittest.TestCase):
    """The feature graphic shows the app's own screens, never a framed render, and is Play's exact size."""

    def setUp(self):
        spec = importlib.util.spec_from_file_location("play_listing_assets", os.path.join(HERE, "play-listing-assets.py"))
        self.pla = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.pla)
        from PIL import Image
        self.Image = Image
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)

    def capture(self, name, colour):
        path = os.path.join(self.tmp, name)
        self.Image.new("RGB", (1080, 2400), colour).save(path)
        return path

    def test_default_screens_skip_framed_renders_and_stop_at_two(self):
        framed, a, b, c = (self.capture(n, (i * 60, 0, 0)) for i, n in enumerate(["f.png", "a.png", "b.png", "c.png"]))
        screens = self.pla.feature_screens("x", {}, "en-US", "en-US", [(framed, True), (a, False), (b, False), (c, False)], None)
        self.assertEqual([s.getpixel((0, 0)) for s in screens], [(60, 0, 0), (120, 0, 0)])

    def test_no_screens_means_none(self):
        self.assertEqual(self.pla.feature_screens("x", {}, "en-US", "en-US", [], None), [])

    def test_renders_at_plays_size_with_one_or_two_screens(self):
        tile = self.Image.new("RGB", (1024, 1024), (96, 80, 160))
        for n in (1, 2):
            out = os.path.join(self.tmp, f"feature{n}.png")
            screens = [self.Image.open(self.capture(f"s{i}.png", (250, 250, 250))) for i in range(n)]
            self.pla.render_feature(tile, "Tenshu Drive", "Synchronization for your Tenshu apps", screens, out)
            self.assertEqual(self.Image.open(out).size, (1024, 500))


if __name__ == "__main__":
    unittest.main()
