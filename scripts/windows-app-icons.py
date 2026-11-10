#!/usr/bin/env python3
"""The Windows app icons for every *-desktop module (ADR 0026 §6b).

    scripts/windows-app-icons.py           # regenerate <module>/icons/windows/
    scripts/windows-app-icons.py --check   # exit 1 if a committed file no longer matches what this renders

Needs Pillow and resvg (https://github.com/linebender/resvg; `resvg` on PATH, or TENSHU_RESVG=<path>).

Per module, under `<module>/icons/windows/`:

  <id>.ico                          the launcher .exe and an unpackaged window: 16 20 24 32 40 48 64 256
  msix/Square44x44Logo.*.png        Start, taskbar, Settings, search (targetsize-N and its _altform-unplated twin;
                                    scale-100/150/200/400)
  msix/Square150x150Logo.*.png      the medium Start tile (scale-100/150/200/400)
  msix/StoreLogo.*.png              the package logo (scale-100/150/200/400)
  window/tenshu-app-icon/<N>.png    the window's own icon — title bar, Alt+Tab — on the classpath (TenshuWindowIcon)

**The pictogram and colours are the macOS icon's (§6); the shape is Windows'.** Windows 11 does not mask app icons —
an app ships its own shape — and the Apple squircle in its wide transparent margin read as small and foreign next to
its taskbar neighbours. So the body is a rounded square filling the canvas but for a 40/1024 margin, corners 14% of
the body (chosen 2026-11-10 against a softer 22%), and the pictogram keeps its macOS share of the body (580/824).

**Rendered per size, never resampled from one master** (§6: "Let the renderer place it"). The inputs are all in
git, because the design source (docs/design/tenshu-icons/) is gitignored:
  * the pictogram — the app's Android launcher foreground VectorDrawable, the §5 port of foreground/<id>.svg,
    turned back into SVG by check-icons.py's own `vd_to_svg`;
  * the body's fill — the shipped `.icns` (sampled inside its body).
Before anything is written, the same pictogram is composed onto the `.icns`'s own body at 1024 and diffed against
the `.icns`, so a pictogram, colour or placement that drifts from the macOS icon fails here rather than shipping.

⚠ No ICO size is upscaled and no PNG is a resample of another: every file is its own resvg render, body included.
"""
from __future__ import annotations

import importlib.util
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parent.parent

# desktop module → (icon id, Android module carrying its launcher foreground). Control predates the -android split.
APPS = {
    "calendar-desktop": ("calendar", "calendar-android"),
    "contacts-desktop": ("contacts", "contacts-android"),
    "control-desktop": ("control", "control"),
    "drive-desktop": ("drive", "drive-android"),
    "fit-desktop": ("fit", "fit-android"),
    "home-desktop": ("home", "home-android"),
    "maps-desktop": ("maps", "maps-android"),
    "mia-desktop": ("mia", "mia-android"),
    "photos-desktop": ("photos", "photos-android"),
    "timeline-desktop": ("timeline", "timeline-android"),
}

ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 256]
WINDOW_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]
TARGET_SIZES = [16, 20, 24, 30, 32, 36, 40, 48, 60, 64, 72, 80, 96, 256]
SCALES = {100: 1.0, 150: 1.5, 200: 2.0, 400: 4.0}
LOGOS = {"Square44x44Logo": 44, "Square150x150Logo": 150, "StoreLogo": 50}

# The pictogram's 64-grid: 580 px on 1024, centred (§6). The Android foreground puts it at 84 + 4.125·u on 432,
# so on 1024 the 432 drawing is scaled by 580/64/4.125 and shifted to keep the grid centred.
GRID_SCALE = 580.0 / 64.0 / 4.125
GRID_SHIFT = (1024.0 - 580.0) / 2.0 - 84.0 * GRID_SCALE

# The Windows body (ADR 0026 §6b): a rounded square, 40/1024 margin, corner radius 14% of the body. The macOS body
# is 824 px, so the pictogram scales by BODY/824 about the centre to keep its share of the body.
MACOS_BODY = 824.0
WINDOWS_INSET = 40.0
WINDOWS_BODY = 1024.0 - 2 * WINDOWS_INSET
WINDOWS_RADIUS = 0.14 * WINDOWS_BODY
WINDOWS_PICTOGRAM_SCALE = WINDOWS_BODY / MACOS_BODY

# Mean per-channel difference (0–255) a correct 1024 render may show against the shipped .icns — antialiasing on
# stroke edges between two renderers. A wrong glyph, colour or placement is far above it.
TOLERANCE = 1.5


def resvg() -> str:
    exe = os.environ.get("TENSHU_RESVG") or shutil.which("resvg")
    if not exe:
        sys.exit("resvg not found: put it on PATH or set TENSHU_RESVG (https://github.com/linebender/resvg/releases)")
    return exe


def vd_to_svg():
    """check-icons.py's VectorDrawable → SVG converter, reused so the two can never disagree about a port."""
    sys.dont_write_bytecode = True  # no scripts/__pycache__ left behind in the tree
    spec = importlib.util.spec_from_file_location("check_icons", ROOT / "scripts" / "check-icons.py")
    module = importlib.util.module_from_spec(spec)
    saved = sys.argv
    sys.argv = [saved[0]]  # check-icons reads its flags at import
    try:
        spec.loader.exec_module(module)
    finally:
        sys.argv = saved
    return module.vd_to_svg


def icns_1024(path: Path) -> Image.Image:
    """The `ic10` entry (1024 px, PNG) of an .icns."""
    data = path.read_bytes()
    if data[:4] != b"icns":
        raise ValueError(f"{path}: not an .icns")
    i = 8
    while i + 8 <= len(data):
        kind, length = data[i:i + 4], struct.unpack(">I", data[i + 4:i + 8])[0]
        if length < 8:
            raise ValueError(f"{path}: corrupt chunk {kind!r} at {i}")  # would otherwise loop forever
        if kind == b"ic10":
            return Image.open(io.BytesIO(data[i + 8:i + length])).convert("RGBA")
        i += length
    raise ValueError(f"{path}: no 1024 px (ic10) entry")


def body_fill(master: Image.Image) -> tuple[int, int, int]:
    """The squircle's colour, read just inside its top edge — above the pictogram's grid, inside the body."""
    r, g, b, a = master.getpixel((512, 150))
    if a != 255:
        raise ValueError("the .icns has no opaque body at (512, 150) — not a §6 icon?")
    return r, g, b


class Renderer:
    def __init__(self, module: str, ident: str, android: str, tool: str, to_svg):
        self.ident = ident
        icns = ROOT / module / "icons" / f"{ident}.icns"
        vd = ROOT / android / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml"
        for p in (icns, vd):
            if not p.exists():
                raise FileNotFoundError(p.relative_to(ROOT))
        self.master = icns_1024(icns)
        self.fill = body_fill(self.master)
        self.mask = self.master.getchannel("A")
        svg432 = to_svg(vd)
        inner = svg432[svg432.index(">") + 1: svg432.rindex("</svg>")]
        # The pictogram on the macOS grid, then (Windows) scaled about the centre to the larger body.
        self.macos_pictogram = f'<g transform="translate({GRID_SHIFT:.4f} {GRID_SHIFT:.4f}) scale({GRID_SCALE:.6f})">{inner}</g>'
        k = WINDOWS_PICTOGRAM_SCALE
        shift = 512.0 * (1 - k)
        fill = "#%02X%02X%02X" % self.fill
        self.windows_svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
            f'<rect x="{WINDOWS_INSET}" y="{WINDOWS_INSET}" width="{WINDOWS_BODY}" height="{WINDOWS_BODY}" '
            f'rx="{WINDOWS_RADIUS:.2f}" fill="{fill}"/>'
            f'<g transform="translate({shift:.4f} {shift:.4f}) scale({k:.6f})">{self.macos_pictogram}</g></svg>'
        )
        self.tool = tool
        self._cache: dict[int, Image.Image] = {}

    def render(self, svg_text: str, size: int) -> Image.Image:
        with tempfile.TemporaryDirectory() as tmp:
            svg, png = Path(tmp, "p.svg"), Path(tmp, "p.png")
            svg.write_text(svg_text, encoding="utf-8")
            subprocess.run([self.tool, "-w", str(size), "-h", str(size), str(svg), str(png)], check=True)
            return Image.open(png).convert("RGBA")

    def icon(self, size: int) -> Image.Image:
        """The Windows icon at [size]: body and pictogram in one render."""
        if size not in self._cache:
            self._cache[size] = self.render(self.windows_svg, size)
        return self._cache[size]

    def macos_icon(self) -> Image.Image:
        """Our pictogram on the `.icns`'s own body at 1024 — what the macOS icon must equal (the fidelity check)."""
        body = Image.new("RGBA", (1024, 1024), self.fill + (255,))
        body.putalpha(self.mask)
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
               f'{self.macos_pictogram}</svg>')
        return Image.alpha_composite(body, self.render(svg, 1024))

    def drift_from_macos(self) -> float:
        """
        Mean channel difference of our 1024 render against the .icns's — the pipeline's own fidelity check.

        ⚠ Compared as *seen*: both composited over black and over white, plus the alpha channel on its own. A raw
        RGBA diff counts the colour of fully transparent pixels, which no renderer agrees on and nobody can see.
        """
        mine, theirs = self.macos_icon(), self.master
        worst = max(ImageStat.Stat(ImageChops.difference(mine.getchannel("A"), theirs.getchannel("A"))).mean)
        for backdrop in ((0, 0, 0, 255), (255, 255, 255, 255)):
            ground = Image.new("RGBA", mine.size, backdrop)
            a = Image.alpha_composite(ground, mine).convert("RGB")
            b = Image.alpha_composite(ground, theirs).convert("RGB")
            worst = max(worst, max(ImageStat.Stat(ImageChops.difference(a, b)).mean))
        return worst


def png_bytes(image: Image.Image) -> bytes:
    buf = io.BytesIO()
    image.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def ico_bytes(images: list[Image.Image]) -> bytes:
    """An ICO of PNG-compressed entries, one per size, each its own render (Vista+ reads PNG entries)."""
    entries = [png_bytes(img) for img in images]
    out = bytearray(struct.pack("<HHH", 0, 1, len(entries)))
    offset = 6 + 16 * len(entries)
    for img, data in zip(images, entries):
        w = img.width if img.width < 256 else 0  # 0 means 256
        out += struct.pack("<BBBBHHII", w, w, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    for data in entries:
        out += data
    return bytes(out)


def outputs(r: Renderer) -> dict[str, bytes]:
    files: dict[str, bytes] = {f"{r.ident}.ico": ico_bytes([r.icon(s) for s in ICO_SIZES])}
    for size in WINDOW_SIZES:
        files[f"window/tenshu-app-icon/{size}.png"] = png_bytes(r.icon(size))
    for size in TARGET_SIZES:
        data = png_bytes(r.icon(size))
        # ⚠ Both names: Windows draws the plain one on a coloured plate in a few places and the unplated one on the
        # taskbar and Start. Our icon carries its own body, so the two are the same pixels.
        files[f"msix/Square44x44Logo.targetsize-{size}.png"] = data
        files[f"msix/Square44x44Logo.targetsize-{size}_altform-unplated.png"] = data
    for name, base in LOGOS.items():
        for scale, factor in SCALES.items():
            files[f"msix/{name}.scale-{scale}.png"] = png_bytes(r.icon(round(base * factor)))
    return files


def ico_entries(data: bytes) -> list[bytes]:
    """The embedded images of an ICO, in directory order."""
    count = struct.unpack("<H", data[4:6])[0]
    entries = []
    for k in range(count):
        size, offset = struct.unpack("<II", data[6 + 16 * k + 8: 6 + 16 * k + 16])
        entries.append(data[offset: offset + size])
    return entries


def same_pixels(a: bytes, b: bytes) -> bool:
    """
    Pixel equality, not byte equality: another Pillow or zlib re-encodes the same pixels differently, and that is
    not drift. An ICO is compared entry by entry.
    """
    if a[:4] == b"\x00\x00\x01\x00" and b[:4] == b"\x00\x00\x01\x00":
        ea, eb = ico_entries(a), ico_entries(b)
        return len(ea) == len(eb) and all(same_pixels(x, y) for x, y in zip(ea, eb))
    if a[:4] != b"\x89PNG" or b[:4] != b"\x89PNG":
        return a == b
    ia, ib = Image.open(io.BytesIO(a)).convert("RGBA"), Image.open(io.BytesIO(b)).convert("RGBA")
    return ia.size == ib.size and ImageChops.difference(ia, ib).getbbox() is None


def main() -> int:
    check = "--check" in sys.argv[1:]
    tool, to_svg = resvg(), vd_to_svg()
    failed = False
    for module, (ident, android) in APPS.items():
        try:
            r = Renderer(module, ident, android, tool, to_svg)
        except FileNotFoundError as e:
            print(f"MISSING  {e} — no Windows icon for {module}", file=sys.stderr)
            failed = True
            continue
        drift = r.drift_from_macos()
        if drift > TOLERANCE:
            print(f"DRIFT    {module}: the 1024 render is {drift:.2f} off the shipped .icns — not writing", file=sys.stderr)
            failed = True
            continue
        out_dir = ROOT / module / "icons" / "windows"
        wanted = outputs(r)
        if check:
            stale = [n for n, d in wanted.items() if not (out_dir / n).exists() or not same_pixels(d, (out_dir / n).read_bytes())]
            extra = [p for p in out_dir.rglob("*") if p.is_file() and p.relative_to(out_dir).as_posix() not in wanted] if out_dir.exists() else []
            if stale or extra:
                print(f"DRIFT    {module}: {len(stale)} stale, {len(extra)} unexpected; run scripts/windows-app-icons.py", file=sys.stderr)
                failed = True
            else:
                print(f"ok       {module} ({len(wanted)} files, {drift:.2f} off macOS)")
            continue
        if out_dir.exists():
            shutil.rmtree(out_dir)
        for name, data in wanted.items():
            path = out_dir / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        print(f"wrote    {module}/icons/windows ({len(wanted)} files, {drift:.2f} off macOS)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
