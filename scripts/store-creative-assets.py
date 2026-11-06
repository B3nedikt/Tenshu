#!/usr/bin/env python3
"""The App Store "Header and Search Results" universal creative asset, per app (docs/how-to/store-listings.md §6a).

    scripts/store-creative-assets.sh [app …]       every app in store/creative/apps.json when none is named

For each app: fetches the two iPhone screens named in store/creative/apps.json (by their manifest URL, sha256-
verified, cached in build/store/screenshot-cache/ — the same cache store-listing.py uses), fills
scripts/store-creative-assets.html, renders it with headless Chrome at 5244 × 2950, and writes

    build/store/creative/<app>/header-16x9.png       the asset: 5244 × 2950 PNG, RGB, sRGB ICC profile
    build/store/creative/<app>/preview-crops.png     the whole asset with each crop and the art safe area drawn on
    build/store/creative/<app>/preview-header-21x9.png   the header crop, App Store overlay mocked translucently
    build/store/creative/<app>/preview-search-3x2.png    the search-results crop, likewise

then checks the asset (size, PNG, no alpha, sRGB profile, bytes) and fails loudly if any check does not hold.

The crops are OUR model, not Apple's published geometry: Apple publishes the art safe area (its template) and
the placements' aspect ratios, not where each device crops. 21:9 = full width, centred on the safe area
vertically; 3:2 = full height, centred horizontally. The overlay (icon, name, Get) is drawn where the App Store
puts it on a product page — over the bottom of the art — and is an approximation to judge legibility by.
"""
import importlib.util
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

try:
    from PIL import Image, ImageCms, ImageDraw, ImageFont, ImageStat, ImageChops
except ImportError:
    sys.exit("store-creative-assets: needs Pillow (python3 -m pip install pillow)")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
STORE = os.path.join(ROOT, "store")
OUT = os.path.join(ROOT, "build", "store", "creative")

W, H = 5244, 2950
# Apple's template, "16x9 Universal - 5244x2950" → "Art Safe Area": x 1921, y 660, 1402 × 962.
SAFE = (1921, 660, 1402, 962)
SAFE_CY = SAFE[1] + SAFE[3] / 2
HEADER_H = round(W * 9 / 21)                                   # 2247
HEADER = (0, round(min(max(SAFE_CY - HEADER_H / 2, 0), H - HEADER_H)), W, HEADER_H)
SEARCH_W = round(H * 3 / 2)                                    # 4425
SEARCH = (round((W - SEARCH_W) / 2), 0, SEARCH_W, H)
# Apple documents no byte limit for creative-asset images; this is a sanity bound, not Apple's.
MAX_BYTES = 20 * 1024 * 1024

# Tenshu palette (web/shared/tokens.css, ADR 0026 §2).
PRIMARY_SOFT = "#E6DEFF"
ACCENT = "#7B5265"

# Phone layout: two CSS-framed iPhones (1320 × 2868 screens), side by side, centred on the safe area, tops
# level. The height is the binding constraint, not the width: the top sits just above the safe area (status
# bar above it, the screen's title inside it) and the bottom must clear the 21:9 crop's lower edge with a
# margin, or the header cuts the phones a sliver short of their end — so the phones are as tall as that
# band allows, and no taller. (Taller means bleeding through the 21:9 edge AND, in 3:2, running under the
# overlaid app name — the bottom-left text reaches x ≈ 2240 for the longest subtitle.)
BEZEL = 24
PHONE_TOP = 450
PHONE_BOTTOM_MARGIN = 90                                       # to the 21:9 crop's bottom edge
PHONE_H = HEADER[1] + HEADER[3] - PHONE_BOTTOM_MARGIN - PHONE_TOP
SCREEN_H = PHONE_H - 2 * BEZEL
SCREEN_W = round(SCREEN_H * 1320 / 2868)
PHONE_W = SCREEN_W + 2 * BEZEL
GAP = 120
LEFT_X = round(W / 2 - GAP / 2 - PHONE_W)
RIGHT_X = round(W / 2 + GAP / 2)
LEFT_Y = RIGHT_Y = PHONE_TOP
# The render's screen area against the screenshot itself, both reduced to a thumbnail with the rounded corners
# trimmed off: a broken or missing <img> leaves the frame's own near-black and fails this by a wide margin.
MAX_SCREEN_DIFF = 12.0                                         # mean absolute difference, 0–255 per channel


def screen_matches(asset, x, y, shot):
    inset_x, inset_y = round(SCREEN_W * 0.08), round(SCREEN_H * 0.05)
    rendered = asset.crop((x + BEZEL + inset_x, y + BEZEL + inset_y,
                           x + BEZEL + SCREEN_W - inset_x, y + BEZEL + SCREEN_H - inset_y))
    with Image.open(shot) as im:
        src = im.convert("RGB")
    sx, sy = round(src.width * 0.08), round(src.height * 0.05)
    source = src.crop((sx, sy, src.width - sx, src.height - sy))
    a = rendered.resize((60, 130), Image.BOX)
    b = source.resize((60, 130), Image.BOX)
    diff = ImageChops.difference(a, b)
    return sum(ImageStat.Stat(diff).mean) / 3


def load_store_listing():
    spec = importlib.util.spec_from_file_location("store_listing", os.path.join(ROOT, "scripts", "store-listing.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def hexcolour(rgb):
    return "#%02X%02X%02X" % tuple(rgb[:3])


def darken(rgb, k):
    return tuple(round(c * (1 - k)) for c in rgb[:3])


def find_chrome():
    env = os.environ.get("CHROME")
    if env:
        if not (os.path.isfile(env) and os.access(env, os.X_OK)):
            sys.exit(f"store-creative-assets: CHROME={env} is not an executable file")
        return env
    candidates = [
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        shutil.which("google-chrome") or "", shutil.which("chromium") or "", shutil.which("chromium-browser") or "",
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    sys.exit("store-creative-assets: no Chrome found — set CHROME=/path/to/chrome")


def render(chrome, html_path, png_path):
    with tempfile.TemporaryDirectory(prefix="tenshu-creative-") as profile:
        cmd = [chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
               "--no-default-browser-check", f"--user-data-dir={profile}",
               "--force-device-scale-factor=1", "--force-color-profile=srgb",
               "--virtual-time-budget=8000",
               f"--window-size={W},{H}", f"--screenshot={png_path}", "file://" + html_path]
        # run() kills the child when the timeout expires, before raising.
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        except subprocess.TimeoutExpired:
            sys.exit(f"store-creative-assets: Chrome did not finish rendering {html_path} within 180 s — killed it")
    if result.returncode != 0 or not os.path.exists(png_path):
        sys.exit(f"store-creative-assets: Chrome failed ({result.returncode}):\n{result.stderr[-2000:]}")


def srgb_icc():
    return ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()


def font(size, bold=False):
    for path, index in (("/System/Library/Fonts/HelveticaNeue.ttc", 1 if bold else 0),
                        ("/System/Library/Fonts/Helvetica.ttc", 1 if bold else 0),
                        ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else
                         "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 0)):
        if os.path.exists(path):
            return ImageFont.truetype(path, size, index=index)
    return ImageFont.load_default(size)


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img.convert("RGBA"), (0, 0), mask)
    return out


def scale_box(box, origin, k):
    x, y, w, h = box
    return ((x - origin[0]) * k, (y - origin[1]) * k, (x - origin[0] + w) * k, (y - origin[1] + h) * k)


def preview_crop(asset, crop, label, icon, name, subtitle, path, width=1800):
    """One crop, scaled to `width`, with a translucent mock of the App Store's overlay along its bottom."""
    x, y, w, h = crop
    k = width / w
    img = asset.crop((x, y, x + w, y + h)).resize((width, round(h * k)), Image.LANCZOS).convert("RGBA")
    pw, ph = img.size
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    # Scrim: the bottom 34 % darkens toward the edge, as the store's material does under its text.
    band = round(ph * 0.34)
    for i in range(band):
        a = round(150 * (i / band) ** 1.6)
        d.line([(0, ph - band + i), (pw, ph - band + i)], fill=(10, 8, 20, a))
    # Icon + name + subtitle bottom-left, Get bottom-right — translucent so the art under them shows.
    margin = round(pw * 0.04)
    size = round(ph * 0.13)
    top = ph - margin - size
    layer.alpha_composite(rounded(icon.resize((size, size), Image.LANCZOS), round(size * 0.225)), (margin, top))
    tx = margin + size + round(size * 0.28)
    d.text((tx, top + size * 0.10), name, font=font(round(size * 0.34), bold=True), fill=(255, 255, 255, 235))
    d.text((tx, top + size * 0.56), subtitle, font=font(round(size * 0.24)), fill=(255, 255, 255, 190))
    gw, gh = round(size * 1.05), round(size * 0.44)
    gx, gy = pw - margin - gw, top + (size - gh) // 2
    d.rounded_rectangle((gx, gy, gx + gw, gy + gh), gh // 2, fill=(255, 255, 255, 215))
    gf = font(round(gh * 0.5), bold=True)
    tw = d.textlength("Get", font=gf)
    d.text((gx + (gw - tw) / 2, gy + gh * 0.22), "Get", font=gf, fill=(40, 30, 80, 255))
    # The art safe area, as Apple's template draws it.
    d.rectangle(scale_box(SAFE, (x, y), k), outline=(0, 255, 0, 200), width=3)
    d.text((14, 10), label, font=font(30, bold=True), fill=(255, 255, 255, 230))
    img.alpha_composite(layer)
    img.convert("RGB").save(path, optimize=True)


def preview_all(asset, path, width=1800):
    k = width / W
    img = asset.resize((width, round(H * k)), Image.LANCZOS).convert("RGBA")
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    hx, hy, hw, hh = HEADER
    sx, sy, sw, sh = SEARCH
    inter = (max(hx, sx), max(hy, sy), min(hx + hw, sx + sw) - max(hx, sx), min(hy + hh, sy + sh) - max(hy, sy))
    f = font(26, bold=True)
    for box, colour, text, dy in ((HEADER, (0, 200, 255, 230), "header 21:9", 0),
                                  (SEARCH, (255, 160, 0, 230), "search 3:2", 34),
                                  (inter, (255, 255, 255, 160), "intersection", 68),
                                  (SAFE, (0, 255, 0, 230), "Apple art safe area", 0)):
        b = scale_box(box, (0, 0), k)
        d.rectangle(b, outline=colour, width=4)
        d.text((b[0] + 10, b[1] + 8 + dy), text, font=f, fill=colour)
    img.alpha_composite(layer)
    img.convert("RGB").save(path, optimize=True)


def check(path):
    problems = []
    with Image.open(path) as im:
        if im.format != "PNG":
            problems.append(f"format {im.format}, not PNG")
        if im.size != (W, H):
            problems.append(f"size {im.size[0]}×{im.size[1]}, not {W}×{H}")
        if im.mode != "RGB":
            problems.append(f"mode {im.mode}: Apple rejects alpha; must be RGB")
        icc = im.info.get("icc_profile")
        desc = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc))) if icc else ""
        if "sRGB" not in desc:
            problems.append(f"colour profile {desc.strip() or 'missing'!r}, not sRGB")
    size = os.path.getsize(path)
    if size > MAX_BYTES:
        problems.append(f"{size / 1e6:.1f} MB, over the {MAX_BYTES / 1e6:.0f} MB sanity bound")
    return problems, size, desc.strip()


def build(app, config, listing_mod, chrome):
    manifest = json.loads(read(os.path.join(STORE, app, "screenshots.manifest.json")))
    shots = []
    for screen in config["screens"]:
        key = f"ios/iphone69/en/{screen}"
        entry = manifest["screens"].get(key)
        if not entry:
            sys.exit(f"store-creative-assets: {app} — {key} is not in store/{app}/screenshots.manifest.json")
        shots.append(listing_mod.fetch_verified(manifest["base"] + entry["sha256"] + ".png", entry["sha256"]))
    icon_path = os.path.join(ROOT, f"{app}-ios", "Resources", "Assets.xcassets", "AppIcon.appiconset", "icon_1024.png")
    icon = Image.open(icon_path).convert("RGB")
    base = icon.getpixel((10, 10))  # the tile colour runs to every edge (ADR 0026 §4)
    out = os.path.join(OUT, app)
    os.makedirs(out, exist_ok=True)
    slots = {
        "base": hexcolour(base), "deep": hexcolour(darken(base, 0.55)), "glow": PRIMARY_SOFT, "accent": ACCENT,
        "phone_w": PHONE_W, "phone_h": PHONE_H, "bezel": BEZEL,
        "phone_r": round(PHONE_W * 0.135), "screen_r": round(PHONE_W * 0.135) - BEZEL,
        "left_x": LEFT_X, "left_y": LEFT_Y, "right_x": RIGHT_X, "right_y": RIGHT_Y,
        "left_src": "file://" + shots[0], "right_src": "file://" + shots[1],
    }
    html = read(os.path.join(ROOT, "scripts", "store-creative-assets.html"))
    for k, v in slots.items():
        html = html.replace("{{" + k + "}}", str(v))
    left = re.findall(r"\{\{[a-z_]+\}\}", html)
    if left:
        sys.exit(f"store-creative-assets: template slots left unfilled: {', '.join(sorted(set(left)))}")
    html_path = os.path.join(out, "header.html")
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(html)
    raw = os.path.join(out, "raw.png")
    render(chrome, html_path, raw)
    with Image.open(raw) as im:
        if im.size != (W, H):
            sys.exit(f"store-creative-assets: Chrome rendered {im.size}, not {W}×{H}")
        asset = im.convert("RGB")
    os.remove(raw)
    for side, x, y, shot in (("left", LEFT_X, LEFT_Y, shots[0]), ("right", RIGHT_X, RIGHT_Y, shots[1])):
        diff = screen_matches(asset, x, y, shot)
        if diff > MAX_SCREEN_DIFF:
            sys.exit(f"store-creative-assets: {app} — the {side} phone's screen does not show {shot} "
                     f"(mean difference {diff:.1f} > {MAX_SCREEN_DIFF}): it did not load into the render")
    final = os.path.join(out, "header-16x9.png")
    asset.save(final, format="PNG", optimize=True, icc_profile=srgb_icc())
    name = read(os.path.join(STORE, app, "en-US", "name.txt")).strip()
    subtitle = read(os.path.join(STORE, app, "en-US", "subtitle.txt")).strip()
    preview_all(asset, os.path.join(out, "preview-crops.png"))
    preview_crop(asset, HEADER, "header 21:9 — overlay mocked", icon, name, subtitle,
                 os.path.join(out, "preview-header-21x9.png"))
    preview_crop(asset, SEARCH, "search results 3:2 — overlay mocked", icon, name, subtitle,
                 os.path.join(out, "preview-search-3x2.png"))
    problems, size, desc = check(final)
    if problems:
        sys.exit(f"store-creative-assets: {app} — {final} FAILS: " + "; ".join(problems))
    print(f"store-creative-assets: {app} — {os.path.relpath(final, ROOT)} "
          f"{W}×{H} PNG RGB, {desc}, {size / 1e6:.1f} MB; previews beside it")


def main(argv):
    apps = json.loads(read(os.path.join(STORE, "creative", "apps.json")))["apps"]
    wanted = argv or list(apps)
    unknown = [a for a in wanted if a not in apps]
    if unknown:
        sys.exit(f"store-creative-assets: no entry in store/creative/apps.json for {', '.join(unknown)} "
                 f"(have: {', '.join(apps)})")
    listing_mod = load_store_listing()
    chrome = find_chrome()
    for app in wanted:
        build(app, apps[app], listing_mod, chrome)


if __name__ == "__main__":
    main(sys.argv[1:])
