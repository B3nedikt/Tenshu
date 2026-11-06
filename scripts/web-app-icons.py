#!/usr/bin/env python3
"""The app icons the landing page's Apps carousel shows, as small WebPs under web/apex/public.

    scripts/web-app-icons.py           # regenerate web/apex/public/icons/apps/*.webp
    scripts/web-app-icons.py --check   # exit 1 if a committed WebP no longer matches its source

Source: each app's iOS asset catalogue, `<app>-ios/Resources/Assets.xcassets/AppIcon.appiconset/
icon_1024.png` (light) and `icon_1024_dark.png` (dark) — the finished, full-bleed tiles ADR 0026
§4/§7 produce, so this script resizes and never redraws. Output: `<app>.webp` and
`<app>-dark.webp`, SIZE px square, both opaque: the dark source is a glyph on transparency, which
iOS composites over a dark gradient of its own, so the dark WebP bakes that gradient in.

Why SIZE alone and no 2x file: the page draws the icon at 40 CSS px at most (the card) and 20 in
the name list, so 128 px already covers a 3x screen. A second size would be bytes nobody fetches.

⚠ An app whose source is missing is reported and skipped (exit 1), never replaced with a guess:
the page lists the apps it has icons for (web/apex/src/landing/carousel/landingApps.ts
APP_ICON_IDS) and falls back to a neutral tile for any other.

⚠ --check compares decoded pixels, not bytes: libwebp may encode the same pixels differently
across versions, and that is not drift.
"""
from __future__ import annotations

import io
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "web" / "apex" / "public" / "icons" / "apps"
SIZE = 128
QUALITY = 90
# The carousel's order (support-pages/hub.ts HUB_APPS); the iOS project is `<app>-ios`.
APPS = ["control", "drive", "photos", "maps", "timeline", "calendar", "contacts", "fit", "home", "mia"]
VARIANTS = {"": "icon_1024.png", "-dark": "icon_1024_dark.png"}
# Mean per-channel difference two encodings of the same pixels may show (0–255 scale).
TOLERANCE = 1.5


def source(app: str, name: str) -> Path:
    return ROOT / f"{app}-ios" / "Resources" / "Assets.xcassets" / "AppIcon.appiconset" / name


def dark_backdrop() -> Image.Image:
    """iOS draws a dark icon's glyph over its own dark gradient; the source is transparent (ADR 0026
    §7, App Store rules), so the web tile bakes an equivalent in: #4A4A50 at the top to #2C2C31.

    ⚠ Lighter than iOS's own (#333 → #141414): on the site's dark page (#141318, surface-3 #2A2832)
    iOS's backdrop all but vanished and the icons read as muted. This one sits a step above the
    page's raised surface, so the tile still reads as a tile."""
    top, bottom = (0x4A, 0x4A, 0x50), (0x2C, 0x2C, 0x31)
    column = Image.new("RGBA", (1, SIZE))
    for y in range(SIZE):
        t = y / (SIZE - 1)
        column.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,))
    return column.resize((SIZE, SIZE))


def render(path: Path, dark: bool) -> bytes:
    image = Image.open(path).convert("RGBA").resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    if dark:
        image = Image.alpha_composite(dark_backdrop(), image)
    buf = io.BytesIO()
    image.save(buf, "WEBP", quality=QUALITY, method=6)
    return buf.getvalue()


def differs(a: bytes, b: bytes) -> float:
    ia = Image.open(io.BytesIO(a)).convert("RGBA")
    ib = Image.open(io.BytesIO(b)).convert("RGBA")
    if ia.size != ib.size:
        return 255.0
    return max(ImageStat.Stat(ImageChops.difference(ia, ib)).mean)


def main() -> int:
    check = "--check" in sys.argv[1:]
    failed = False
    if not check:
        OUT.mkdir(parents=True, exist_ok=True)
    for app in APPS:
        for suffix, name in VARIANTS.items():
            src = source(app, name)
            target = OUT / f"{app}{suffix}.webp"
            rel = target.relative_to(ROOT)
            if not src.exists():
                print(f"MISSING  {src.relative_to(ROOT)} — no icon for {app}{suffix}", file=sys.stderr)
                failed = True
                continue
            data = render(src, dark=bool(suffix))
            if check:
                if not target.exists():
                    print(f"DRIFT    {rel} does not exist; run scripts/web-app-icons.py", file=sys.stderr)
                    failed = True
                elif (d := differs(data, target.read_bytes())) > TOLERANCE:
                    print(f"DRIFT    {rel} differs from {src.relative_to(ROOT)} (mean {d:.1f}); run scripts/web-app-icons.py", file=sys.stderr)
                    failed = True
                else:
                    print(f"ok       {rel}")
            else:
                target.write_bytes(data)
                print(f"wrote    {rel} ({len(data)} bytes)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
