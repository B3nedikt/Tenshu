#!/usr/bin/env python3
"""The Google Play listing of one app, assembled from the repo into fastlane supply's folder layout.

    scripts/play-listing-assets.py drive [maps …] [--out build/store/play]

Writes, per app (gitignored, rebuilt on every run):

  <out>/<app>/<locale>/title.txt, short_description.txt, full_description.txt   copied from store/<app>/play/<locale>/
  <out>/<app>/<locale>/images/icon.png                512 × 512, from the app's iOS 1024 tile (ADR 0026)
  <out>/<app>/<locale>/images/featureGraphic.png      1024 × 500, drawn here: the tagline beside one or two of the app's
                                                      screens in a phone (`featureScreens`, else the first two)
  <out>/<app>/<locale>/images/phoneScreenshots/NN.png 9:16: a framed render (store-frame, already 1242 × 2208) as is;
                                                       else each capture centred on the app's tile colour —
                                                      the locale's own set, or en-US's where listing.json
                                                      maps the locale to it (`localeScreenshots`)

`scripts/lib/play_publish.py listing <package> <out>/<app>` uploads that folder; the layout is supply's,
so a later `supply` lane (store-presence-and-support-pages.md D7) reads it unchanged.

Inputs (docs/how-to/store-listings.md §8):
  store/<app>/play/listing.json   package, tagline (`taglines`: a non-English locale's own line), and
                                  the screenshots: `manifest` keys (published
                                  captures in store/<app>/screenshots.manifest.json, fetched from R2 by
                                  sha256) or a `local` folder of PNGs (captures not yet published) for
                                  en-US, and `localeScreenshots` for every other locale: its own set in
                                  the same shape, or "en-US" for the English one; `featureScreens`, the
                                  one or two captures the feature graphic shows (default: the first two).
  <app>-ios/.../AppIcon.appiconset/icon_1024.png   the full-bleed opaque tile every platform's icon comes from

Why the screenshots are padded and not cropped: the captures are 1080 × 2400 (9:20), and Play refuses a
screenshot whose long side is more than twice its short side. Cropping would cut the app's own chrome;
padding to 9:16 on the tile colour keeps every pixel the app drew.

Needs Pillow and the macOS system font Helvetica Neue (the feature graphic's text).
"""
import argparse
import importlib.util
import json
import os
import shutil
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
STORE = os.path.join(ROOT, "store")
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"
# The Play listing's default language: `screenshots` is its set; every other locale names its own in
# `localeScreenshots`, or "en-US" for this one (see assemble).
DEFAULT_LOCALE = "en-US"
FONT_BOLD, FONT_MEDIUM = 1, 10
FEATURE = (1024, 500)
ICON = 512
SHOT_CANVAS = (1242, 2208)   # 9:16
SHOT_HEIGHT = 1980           # the capture's height on that canvas


def store_listing():
    """scripts/store-listing.py as a module, for its R2 fetch (verified by sha256) and the copy check."""
    sys.dont_write_bytecode = True  # importing it must not leave a scripts/__pycache__ behind
    spec = importlib.util.spec_from_file_location("store_listing", os.path.join(ROOT, "scripts", "store-listing.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def tile_path(app):
    return os.path.join(ROOT, f"{app}-ios", "Resources", "Assets.xcassets", "AppIcon.appiconset", "icon_1024.png")


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def shade(rgb, factor):
    return tuple(max(0, min(255, round(c * factor))) for c in rgb)


def gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        draw.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return img


def render_icon(tile, out):
    tile.resize((ICON, ICON), Image.LANCZOS).convert("RGB").save(out, optimize=True)


SS = 2  # the feature graphic is drawn at twice its size, then downsampled: smooth phone edges and text
PHONE_BODY, PHONE_RAIL, PHONE_LENS = (22, 23, 27), (58, 60, 68), (8, 8, 10)


def phone(screen, width):
    """A capture in an Android phone — the website's frame (web/store-frame/src/android.ts) in its
    proportions: bezel 2.6 % of the width, outer radius 7 %, screen radius 4.4 %, a punch-hole lens."""
    margin = round(width * 0.026)
    inner = width - 2 * margin
    shot = screen.resize((inner, round(screen.height * inner / screen.width)), Image.LANCZOS)
    body = Image.new("RGBA", (width, shot.height + 2 * margin), (0, 0, 0, 0))
    draw = ImageDraw.Draw(body)
    radius, rail = round(width * 0.07), max(1, round(width * 0.007))
    draw.rounded_rectangle((0, 0, body.width - 1, body.height - 1), radius=radius, fill=PHONE_RAIL)
    draw.rounded_rectangle((rail, rail, body.width - 1 - rail, body.height - 1 - rail), radius=radius - rail, fill=PHONE_BODY)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), radius=round(width * 0.044), fill=255)
    body.paste(shot, (margin, margin), mask)
    lens, cx = round(width * 0.03), width // 2
    cy = round(width * 0.037) + lens // 2
    draw.ellipse((cx - lens // 2, cy - lens // 2, cx + lens // 2, cy + lens // 2), fill=PHONE_LENS)
    return body


def composite_with_shadow(canvas, item, xy, blur, offset, opacity):
    shadow = Image.new("RGBA", (item.width + 4 * blur, item.height + 4 * blur), (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 255), (2 * blur, 2 * blur), item.getchannel("A").point(lambda v: v * opacity // 255))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(blur)), (xy[0] - 2 * blur, xy[1] - 2 * blur + offset))
    canvas.alpha_composite(item, xy)


def diagonal(size, start, end):
    """A top-left → bottom-right gradient (drawn small and scaled: smooth, and fast)."""
    small = Image.new("RGB", (64, 64))
    px = small.load()
    for y in range(64):
        for x in range(64):
            t = x / 63 * 0.55 + y / 63 * 0.45
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(start, end))
    return small.resize(size, Image.BILINEAR)


def render_feature(tile, name, tagline, screens, out):
    """The tagline, large, beside the app's own screens in a phone (the second behind and lower), on a
    gradient of the tile's colour. The name is a small line above the tagline: the Play card under the
    graphic already shows the icon and the name, so the graphic's job is to show the app."""
    W, H = FEATURE[0] * SS, FEATURE[1] * SS
    bg = tile.getpixel((4, 4))[:3]
    canvas = diagonal((W, H), shade(bg, 1.18), shade(bg, 0.72)).convert("RGBA")
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))  # a soft light behind the phones
    ImageDraw.Draw(glow).ellipse((W * 0.50, -H * 0.25, W * 1.15, H * 1.15), fill=(255, 255, 255, 46))
    canvas.alpha_composite(glow.filter(ImageFilter.GaussianBlur(70 * SS)))

    # Both phones run off the bottom edge: the top of a screen is where an app says what it is.
    width = 236 * SS
    if len(screens) > 1:
        back = phone(screens[1], round(width * 0.92))
        composite_with_shadow(canvas, back, (W - 52 * SS - back.width, 128 * SS), 14 * SS, 10 * SS, 110)
        front_x = W - 52 * SS - back.width - round(width * 0.62)
    else:
        front_x = W - 110 * SS - width
    composite_with_shadow(canvas, phone(screens[0], width), (front_x, 56 * SS), 18 * SS, 14 * SS, 140)

    draw = ImageDraw.Draw(canvas)
    left = 64 * SS
    max_w = front_x - left - 40 * SS
    size = 58  # down to 44 to stay within two lines
    while True:
        tag_font = ImageFont.truetype(FONT, size * SS, index=FONT_BOLD)
        if len(wrap_greedy(draw, tagline, tag_font, max_w)) <= 2 or size <= 44:
            break
        size -= 2
    lines = wrap(draw, tagline, tag_font, max_w)
    icon = 44 * SS
    small = rounded(tile.resize((icon, icon), Image.LANCZOS), radius=round(icon * 0.24))
    line_h = round(size * 1.12) * SS
    y = (H - (icon + 30 * SS + line_h * len(lines))) // 2
    composite_with_shadow(canvas, small, (left, y), 4 * SS, 2 * SS, 60)
    name_font = ImageFont.truetype(FONT, 24 * SS, index=FONT_MEDIUM)
    draw.text((left + icon + 16 * SS, y + icon // 2), name, font=name_font, fill=(255, 255, 255, 225), anchor="lm")
    y += icon + 30 * SS
    for line in lines:
        draw.text((left, y), line, font=tag_font, fill=(255, 255, 255))
        y += line_h
    canvas.convert("RGB").resize(FEATURE, Image.LANCZOS).save(out, optimize=True)


def wrap_greedy(draw, text, font, width):
    lines, current = [], ""
    for word in text.split():
        trial = f"{current} {word}".strip()
        if draw.textlength(trial, font=font) <= width or not current:
            current = trial
        else:
            lines.append(current)
            current = word
    return lines + ([current] if current else [])


def wrap(draw, text, font, width):
    """As few lines as a greedy fill needs, then as even as they can be: "Synchronization for /
    your Tenshu apps", never "Synchronization for your Tenshu / apps" (one word left alone)."""
    lines = wrap_greedy(draw, text, font, width)
    if len(lines) < 2:
        return lines
    lo, hi = 1, width  # the narrowest width that still needs no more lines
    while lo < hi:
        mid = (lo + hi) // 2
        if len(wrap_greedy(draw, text, font, mid)) <= len(lines):
            hi = mid
        else:
            lo = mid + 1
    return wrap_greedy(draw, text, font, lo)


def render_shot(src, bg, out, framed=False):
    shot = Image.open(src).convert("RGB")
    # A framed render (scripts/store-frame render <app> --device phone, `android/phoneframed/…` keys)
    # is already the Play canvas, with its own background and caption: upload it as drawn. Decided by
    # the key, not the size — 1242 × 2208 is also an iPhone 5.5" capture.
    if framed:
        if shot.size != SHOT_CANVAS:
            raise SystemExit(f"play-listing-assets: {src}: a framed render is {SHOT_CANVAS[0]} × {SHOT_CANVAS[1]}, not {shot.width} × {shot.height}")
        shot.save(out, optimize=True)
        return
    w = round(shot.width * SHOT_HEIGHT / shot.height)
    shot = rounded(shot.resize((w, SHOT_HEIGHT), Image.LANCZOS), radius=56)
    canvas = gradient(SHOT_CANVAS, shade(bg, 1.12), shade(bg, 0.82))
    canvas.paste(shot, ((SHOT_CANVAS[0] - w) // 2, (SHOT_CANVAS[1] - SHOT_HEIGHT) // 2), shot)
    canvas.save(out, optimize=True)


def screenshot_sources(app, shots, sl):
    shots = shots or {}
    if "manifest" in shots:
        manifest = json.loads(sl.read(os.path.join(STORE, app, "screenshots.manifest.json")))
        sources = []
        for key in shots["manifest"]:
            entry = manifest["screens"].get(key)
            if entry is None:
                raise SystemExit(f"play-listing-assets: {app}: {key} is not in screenshots.manifest.json")
            src = sl.fetch_verified(f"{manifest['base']}{entry['sha256']}.png", entry["sha256"])
            sources.append((src, key.split("/")[1].endswith("framed")))
        return sources
    if "local" in shots:
        folder = os.path.join(ROOT, shots["local"])
        if not os.path.isdir(folder):
            print(f"play-listing-assets: WARNING {app}: no captures in {shots['local']} — the listing gets no screenshots",
                  file=sys.stderr)
            return []
        return [(os.path.join(folder, f), False) for f in sorted(os.listdir(folder)) if f.endswith(".png")]
    return []


def feature_screens(app, listing, locale, own, sources, sl):
    """The one or two captures the feature graphic shows: `featureScreens`' manifest keys (English; a
    locale with captures of its own gets its language's twin where the manifest has one), else the first
    two unframed captures of the locale's set — a framed render already has its own caption and canvas."""
    keys = listing.get("featureScreens")
    if keys:
        manifest = json.loads(sl.read(os.path.join(STORE, app, "screenshots.manifest.json")))
        lang = locale.split("-")[0]
        paths = []
        for key in keys:
            local_key = key.replace("/en/", f"/{lang}/", 1)
            if own != DEFAULT_LOCALE and local_key in manifest["screens"]:
                key = local_key
            entry = manifest["screens"].get(key)
            if entry is None:
                raise SystemExit(f"play-listing-assets: {app}: featureScreens' {key} is not in screenshots.manifest.json")
            paths.append(sl.fetch_verified(f"{manifest['base']}{entry['sha256']}.png", entry["sha256"]))
    else:
        paths = [src for src, framed in sources if not framed][:2]
    return [Image.open(p).convert("RGB") for p in paths]


def assemble(app, out_root, sl):
    base = os.path.join(STORE, app, "play")
    listing = json.loads(sl.read(os.path.join(base, "listing.json")))
    tile = Image.open(tile_path(app)).convert("RGB")
    bg = tile.getpixel((4, 4))
    out = os.path.join(out_root, app)
    shutil.rmtree(out, ignore_errors=True)
    default = screenshot_sources(app, listing.get("screenshots"), sl)
    counts, drawn = {}, []
    for locale in sl.play_locales(app):
        folder = os.path.join(out, locale)
        images = os.path.join(folder, "images")
        os.makedirs(images)
        for key in sl.PLAY_LOCALIZED:
            shutil.copyfile(os.path.join(base, locale, f"{key}.txt"), os.path.join(folder, f"{key}.txt"))
        title = sl.value(os.path.join(base, locale, "title.txt"))
        render_icon(tile, os.path.join(images, "icon.png"))
        # Every locale gets a set: its own captures, or en-US's where listing.json maps it there
        # ("de-DE": "en-US" — an app whose screens are English either way). Uploading the English set
        # under the locale, rather than none, replaces any set uploaded there by hand. ⚠ No captures at
        # all (a `local` folder a fresh clone lacks) means no folder, and play_publish.py then neither
        # uploads nor deletes that locale's set (it replaces only the image types it has files for).
        own = (listing.get("localeScreenshots") or {}).get(locale, DEFAULT_LOCALE)
        sources = default if locale == DEFAULT_LOCALE or own == DEFAULT_LOCALE else screenshot_sources(app, own, sl)
        counts[locale] = (len(sources), "en-US's" if locale != DEFAULT_LOCALE and own == DEFAULT_LOCALE else "own")
        # The feature graphic shows the app: no screens, no graphic, and the push leaves the live one alone.
        screens = feature_screens(app, listing, locale, own, sources, sl)
        if screens:
            tagline = (listing.get("taglines") or {}).get(locale, listing["tagline"])
            render_feature(tile, title, tagline, screens, os.path.join(images, "featureGraphic.png"))
            drawn.append(locale)
        else:
            print(f"play-listing-assets: WARNING {app} {locale}: no screen for the feature graphic — none drawn",
                  file=sys.stderr)
        if not sources:
            continue
        os.makedirs(os.path.join(images, "phoneScreenshots"))
        for n, (src, framed) in enumerate(sources, 1):
            render_shot(src, bg, os.path.join(images, "phoneScreenshots", f"{n:02d}.png"), framed)
    with open(os.path.join(out, "package"), "w") as f:
        f.write(listing["package"] + "\n")
    shots = ", ".join(f"{loc} {n} ({kind})" if kind != "own" else f"{loc} {n}" for loc, (n, kind) in counts.items())
    print(f"play-listing-assets: {app} — {', '.join(sl.play_locales(app))}, icon, "
          f"feature graphic: {', '.join(drawn) or 'none'}, "
          f"screenshots: {shots} in {os.path.relpath(out, ROOT)}")
    return sum(n for n, _ in counts.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apps", nargs="+")
    parser.add_argument("--out", default=os.path.join(ROOT, "build", "store", "play"))
    args = parser.parse_args()
    sl = store_listing()
    if sl.cmd_play_check(args.apps) != 0:
        return 1
    for app in args.apps:
        assemble(app, os.path.abspath(args.out), sl)
    return 0


if __name__ == "__main__":
    sys.exit(main())
