#!/usr/bin/env python3
"""Verify that every consumer of the app icon set matches its source.

The icon set has repeatedly grown consumers nobody remembered to update, and
none of them were caught by a gate — they were all found by accident:

  * calendar/photos shipped the Contacts glyph for months  (fidelity)
  * calendar-ios / contacts-ios had no asset catalogue      (coverage)
  * six web assets sat a full redesign round behind         (fidelity)
  * nine build files cited a gitignored doc                 (coverage)

Two classes, so two mechanisms:

  COVERAGE  Does every module that should have an icon actually declare and
            ship one? Needs no design source, so it always runs.
  FIDELITY  Does each consumer still render what the source says? Needs
            docs/design/tenshu-icons/, which is GITIGNORED — so on a clone or
            in CI this half SKIPS. It says so loudly; it never passes silently.

Contract: docs/adr/0026-app-icon-system.md.
Usage:  scripts/check-icons.py [--verbose]
"""
import json, os, re, shutil, struct, subprocess, sys, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC  = ROOT / "docs/design/tenshu-icons/icons"
ADR  = "docs/adr/0026-app-icon-system.md"
VERBOSE = "--verbose" in sys.argv
FAILURES, SKIPS, CHECKS = [], [], 0

def fail(where, msg):  FAILURES.append((where, msg))
def ok(where, msg=""):
    global CHECKS; CHECKS += 1
    if VERBOSE: print(f"    ok   {where} {msg}")

# ---------------------------------------------------------------- png + render
def read_png(path):
    d = Path(path).read_bytes(); assert d[:8] == b"\x89PNG\r\n\x1a\n", path
    i, idat, w = 8, b"", None
    while i < len(d):
        ln = struct.unpack(">I", d[i:i+4])[0]; typ = d[i+4:i+8]; data = d[i+8:i+8+ln]; i += 12+ln
        if typ == b"IHDR": w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", data); assert il == 0
        elif typ == b"IDAT": idat += data
        elif typ == b"IEND": break
    raw = zlib.decompress(idat); nc = {0:1,2:3,3:1,4:2,6:4}[ct]; bpp = nc*bd//8; stride = w*bpp
    out, prev, p = bytearray(), bytearray(stride), 0
    for _ in range(h):
        f = raw[p]; p += 1; line = bytearray(raw[p:p+stride]); p += stride
        for x in range(stride):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if   f == 1: line[x] = (line[x]+a) & 255
            elif f == 2: line[x] = (line[x]+b) & 255
            elif f == 3: line[x] = (line[x]+(a+b)//2) & 255
            elif f == 4:
                pp = a+b-c; pa, pb, pc = abs(pp-a), abs(pp-b), abs(pp-c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        out += line; prev = line
    return w, h, nc, bytes(out)

_rendered = {}
def render_svg(text, tag, size):
    tag = re.sub(r"[^A-Za-z0-9_-]", "-", tag)      # tags become filenames
    key = (tag, size)
    if key in _rendered: return _rendered[key]
    stem = ROOT / f".icons-check-{tag}-{size}"
    svg, png = Path(f"{stem}.svg"), Path(f"{stem}.png")
    svg.write_text(text)
    subprocess.run(["rsvg-convert","-w",str(size),"-h",str(size),str(svg),"-o",str(png)], check=True)
    r = read_png(png)
    svg.unlink(); png.unlink()
    _rendered[key] = r
    return r

# ⚠ Channel counts differ across the set by design — the default iOS icon is RGB
# (no alpha, App Store Connect requires it) while every other asset is RGBA. These
# must never index past the end when handed the wrong one: a check that CRASHES
# instead of reporting is worse than no check, because the traceback buries which
# consumer was wrong.
def _alpha(px, nc, i): return px[i*nc+3] if nc == 4 else 255
def alpha_diff(a, b, tol=8):
    (w,h,nc,pa), (_,_,nc2,pb) = a, b
    if (w,h) != (b[0],b[1]): return w*h
    return sum(1 for i in range(w*h) if abs(_alpha(pa,nc,i)-_alpha(pb,nc2,i)) > tol)
def rgb_diff(a, b, tol=8):
    (w,h,nc,pa), (_,_,nc2,pb) = a, b
    if (w,h) != (b[0],b[1]): return w*h*3
    return sum(1 for i in range(w*h) for c in range(3) if abs(pa[i*nc+c]-pb[i*nc2+c]) > tol)

# ------------------------------------------------------------------ discovery
def android_modules():
    out = []
    for p in sorted(ROOT.glob("*-android")) + [ROOT/"control"]:
        if (p/"src/main/res").is_dir(): out.append((p.name, p.name.replace("-android","")))
    return out
def desktop_modules():
    return [(p.name, p.name.replace("-desktop","")) for p in sorted(ROOT.glob("*-desktop"))]
def ios_projects():
    return [(p.name, p.name.replace("-ios","")) for p in sorted(ROOT.glob("*-ios"))
            if (p/"project.yml").exists() and "sdk" not in p.name]

# ------------------------------------------------------- vector drawable -> svg
def vd_to_svg(path):
    s = re.sub(r"<!--.*?-->", "", Path(path).read_text(encoding="utf-8"), flags=re.S)   # comments can contain <group>
    xf = []
    for a in re.findall(r"<group\b(.*?)>", s, re.S):
        g = lambda k, d: float(m.group(1)) if (m := re.search(k+r'="([-\d.]+)"', a)) else d
        xf.append((g("translateX",0.0), g("translateY",0.0), g("scaleX",1.0)))
    if not xf or xf[0][2] == 1.0:
        raise ValueError(f"{path}: outer <group> transform parsed as identity")
    body = []
    for m in re.finditer(r"<path\b(.*?)/>", s, re.S):
        at = m.group(1)
        d  = re.search(r'android:pathData="(.*?)"', at, re.S).group(1)
        st = re.search(r'android:strokeColor="(#[0-9A-Fa-f]+)"', at)
        fc = re.search(r'android:fillColor="(#[0-9A-Fa-f]+)"', at)
        sw = re.search(r'android:strokeWidth="([\d.]+)"', at)
        lc = re.search(r'android:strokeLineCap="(\w+)"', at)
        ft = re.search(r'android:fillType="(\w+)"', at)                 # dropping this silently
        x  = f'fill="{fc.group(1) if fc else "none"}"'                  # renders the wrong winding
        if ft and ft.group(1).lower() == "evenodd": x += ' fill-rule="evenodd"'
        if st:
            x += f' stroke="{st.group(1)}" stroke-width="{sw.group(1)}" stroke-linejoin="round"'
            x += f' stroke-linecap="{lc.group(1)}"' if lc else ' stroke-linecap="round"'
        body.append(f'<path d="{d}" {x}/>')
    inner = "".join(body)
    for tx, ty, sc in reversed(xf):
        inner = f'<g transform="translate({tx} {ty}) scale({sc})">{inner}</g>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 432 432">{inner}</svg>'

def strip_meta(text):
    return re.sub(r'\s+xmlns:c2pa="[^"]*"', "", re.sub(r"<metadata>.*?</metadata>", "", text, flags=re.S))

# ================================================================== COVERAGE
# No design source needed. This is the half that catches a consumer which does
# not exist at all — the class that left calendar-ios and contacts-ios with no
# icon for as long as they had existed.

def check_coverage():
    print("COVERAGE — every module that ships an app declares and ships an icon")

    for mod, ident in android_modules():
        res = ROOT/mod/"src/main/res"
        for f in ("drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml",
                  "values/ic_launcher_background.xml", "mipmap-anydpi-v26/ic_launcher.xml"):
            if not (res/f).exists(): fail(f"android/{mod}", f"missing {f}")
            else: ok(f"android/{mod}", f)
        adaptive = res/"mipmap-anydpi-v26/ic_launcher.xml"
        if adaptive.exists():
            t = adaptive.read_text()
            for layer in ("background", "foreground", "monochrome"):
                if f"<{layer}" not in t: fail(f"android/{mod}", f"<adaptive-icon> has no <{layer}>")
                else: ok(f"android/{mod}", f"<{layer}>")

    # two apps ship byte-identical drawables => one of them carries another app's glyph
    seen = {}
    for mod, ident in android_modules():
        p = ROOT/mod/"src/main/res/drawable/ic_launcher_foreground.xml"
        if not p.exists(): continue
        import hashlib
        h = hashlib.sha256(re.sub(rb"<!--.*?-->", b"", p.read_bytes(), flags=re.S)).hexdigest()
        if h in seen: fail("android", f"{mod} and {seen[h]} ship IDENTICAL launcher glyphs")
        else: seen[h] = mod; ok("android", f"{mod} glyph is unique")

    for mod, ident in desktop_modules():
        gradle = (ROOT/mod/"build.gradle.kts").read_text(encoding="utf-8")
        # ⚠ Anchored on `.icns`: the `windows { iconFile.set(… .ico) }` line sits above the macOS block, and an
        # unanchored search would match it and pass a module whose macOS icon is gone.
        m = re.search(r'iconFile\.set\(project\.file\("([^"]+\.icns)"\)', gradle)
        if not m: fail(f"desktop/{mod}", "no iconFile in the macOS block — jpackage falls back to the Java icon")
        elif not (ROOT/mod/m.group(1)).exists(): fail(f"desktop/{mod}", f"iconFile points at missing {m.group(1)}")
        else: ok(f"desktop/{mod}", m.group(1))
        # Windows (ADR §6b): the .exe's .ico, the window's own icon on the classpath, and the MSIX logos. Without
        # them a Windows build shows the JVM's icon.
        win = ROOT/mod/"icons/windows"
        mw = re.search(r'windows \{ iconFile\.set\(project\.file\("([^"]+)"\)\) \}', gradle)
        if not mw: fail(f"desktop/{mod}", "no iconFile in a windows { } block — the .exe gets the JVM's icon")
        elif not (ROOT/mod/mw.group(1)).exists(): fail(f"desktop/{mod}", f"windows iconFile points at missing {mw.group(1)}")
        else: ok(f"desktop/{mod}", mw.group(1))
        if 'resources.srcDir("icons/windows/window")' not in gradle:
            fail(f"desktop/{mod}", "icons/windows/window is not on the jvmMain classpath — TenshuWindowIcon finds nothing")
        elif not (win/"window/tenshu-app-icon/32.png").exists():
            fail(f"desktop/{mod}", "no window icon PNGs — run scripts/windows-app-icons.py")
        else: ok(f"desktop/{mod}", "window icon")
        main_kt = next((ROOT/mod).glob("src/*/kotlin/**/Main.kt"), None)
        if main_kt is None:
            fail(f"desktop/{mod}", "no Main.kt found — cannot check that the window calls TenshuWindowIcon()")
        elif "TenshuWindowIcon()" not in main_kt.read_text(encoding="utf-8"):
            fail(f"desktop/{mod}", f"{main_kt.relative_to(ROOT)} never calls TenshuWindowIcon() — the window keeps the JVM's icon")
        else: ok(f"desktop/{mod}", "TenshuWindowIcon()")
        for logo in ("Square44x44Logo.targetsize-24_altform-unplated.png", "Square150x150Logo.scale-100.png", "StoreLogo.scale-100.png"):
            if not (win/"msix"/logo).exists(): fail(f"desktop/{mod}", f"missing icons/windows/msix/{logo} — run scripts/windows-app-icons.py")
            else: ok(f"desktop/{mod}", logo)

    for proj, ident in ios_projects():
        yml = (ROOT/proj/"project.yml").read_text()
        if "ASSETCATALOG_COMPILER_APPICON_NAME" not in yml:
            fail(f"ios/{proj}", "no ASSETCATALOG_COMPILER_APPICON_NAME — the catalogue is never compiled in")
        else: ok(f"ios/{proj}", "APPICON declared")
        if not re.search(r"^\s*- path: Resources\s*$", yml, re.M):
            fail(f"ios/{proj}", "no '- path: Resources' source — the catalogue is not in the target")
        else: ok(f"ios/{proj}", "Resources sourced")
        # iOS 18 has three appearances and they have OPPOSITE alpha rules:
        # the default must be opaque (App Store Connect rejects alpha), while dark
        # and tinted must be transparent — iOS supplies the ground behind them.
        base = ROOT/proj/"Resources/Assets.xcassets/AppIcon.appiconset"
        for fname, needs_alpha, why in (
                ("icon_1024.png",        False, "App Store Connect rejects alpha on the default icon"),
                ("icon_1024_dark.png",   True,  "the dark variant must be transparent — iOS supplies the ground"),
                ("icon_1024_tinted.png", True,  "the tinted variant must be transparent — iOS supplies the ground")):
            f = base/fname
            if not f.exists(): fail(f"ios/{proj}", f"missing {fname}"); continue
            w, h, nc, px = read_png(f)
            has_alpha = nc == 4
            if has_alpha != needs_alpha:
                fail(f"ios/{proj}", f"{fname}: {'has' if has_alpha else 'has no'} alpha — {why}")
            elif (w, h) != (1024, 1024):
                fail(f"ios/{proj}", f"{fname} is {w}x{h}, expected 1024x1024")
            else: ok(f"ios/{proj}", f"{fname} 1024x1024")
            if fname.endswith("_tinted.png") and has_alpha:
                # Apple maps LUMINANCE to the user's tint, so the artwork must be grey
                bad = next((i for i in range(w*h)
                            if px[i*4+3] > 8 and not (px[i*4] == px[i*4+1] == px[i*4+2])), None)
                if bad is not None: fail(f"ios/{proj}", f"{fname} is not greyscale — the tint will be wrong")
                else: ok(f"ios/{proj}", "tinted is greyscale")
            if fname.endswith("_dark.png") and has_alpha and all(px[i*4+3] == 255 for i in range(0, w*h, 997)):
                fail(f"ios/{proj}", f"{fname} is fully opaque — the background was not dropped")
        cj = base/"Contents.json"
        if cj.exists():
            imgs = json.loads(cj.read_text()).get("images", [])
            if not any(i.get("filename") for i in imgs):
                fail(f"ios/{proj}", "Contents.json declares a slot with no filename")
            have = {tuple(a["value"] for a in i.get("appearances", [])) for i in imgs}
            for want in ((), ("dark",), ("tinted",)):
                if want not in have:
                    fail(f"ios/{proj}", f"Contents.json declares no {want or ('default',)} appearance")
                else: ok(f"ios/{proj}", f"appearance {want or ('default',)}")

    # Web assets are DISCOVERED from what the pages actually reference, not from a
    # list here — a hardcoded list cannot notice an icon somebody newly wired in.
    referenced = set()
    for page in list(ROOT.glob("web/*/index.html")) + list(ROOT.glob("web/*/public/manifest.webmanifest")):
        app_public = ROOT/page.relative_to(ROOT).parts[0]/page.relative_to(ROOT).parts[1]/"public"
        for href in re.findall(r'["\'](/icons/[A-Za-z0-9_.-]+)["\']', page.read_text()):
            referenced.add((str(page.relative_to(ROOT)), app_public/href.lstrip("/")))
    if not referenced: fail("web", "no icon references found in any web page — did the markup move?")
    for who, path in sorted(referenced, key=lambda t: str(t[1])):
        if not path.exists():
            fail("web", f"{who} references {path.relative_to(ROOT)}, which does not exist")
        else: ok("web", str(path.relative_to(ROOT)))

    # ⚠ An SVG under a served public/ directory goes to the browser BYTE FOR BYTE.
    # The design source's SVGs carry an inline C2PA provenance manifest that is
    # ~90% of the file — 8.6KB where 886 bytes of geometry would do — so a copy
    # made without stripping it ships that on every page load, silently. This is
    # the guard; the strip in the copy step is not, because nothing checks it ran.
    for svg in sorted(ROOT.glob("web/*/public/**/*.svg")):
        t = svg.read_text()
        if "<metadata>" in t:
            stripped = len(re.sub(r"<metadata>.*?</metadata>", "", t, flags=re.S).encode())
            fail("web", f"{svg.relative_to(ROOT)} still carries <metadata> — "
                        f"{len(t.encode())} B where {stripped} B is the artwork. Strip it (ADR §8).")
        else: ok("web", f"{svg.relative_to(ROOT)} has no metadata")

    # a build comment or drawable that cites a repo path which does not exist is
    # how nine build files ended up pointing at a gitignored doc
    cited = set()
    for p in list(ROOT.glob("*/build.gradle.kts")) + list(ROOT.glob("*/src/main/res/drawable/ic_launcher_*.xml")):
        for m in re.finditer(r"docs/[A-Za-z0-9_./-]+\.md", p.read_text()):
            cited.add((str(p.relative_to(ROOT)), m.group(0)))
    for who, path in sorted(cited):
        if not (ROOT/path).exists(): fail("references", f"{who} cites {path}, which does not exist")
        else: ok("references", f"{who} -> {path}")

# ================================================================== FIDELITY
# Needs the gitignored design source. Re-derives what each consumer *should*
# be and diffs. Skips loudly when the source is absent — never passes silently.

GRID_SPAN = 580.0                      # macOS: the pictogram's 64-unit grid, in px on 1024
PICTO     = 1024 * GRID_SPAN / 655.36  # the foreground render size that produces it

def source_for(kind, ident):
    p = SRC/kind/f"{ident}.svg"
    return strip_meta(p.read_text()) if p.exists() else None

def check_fidelity():
    if not SRC.is_dir():
        SKIPS.append(f"design source not present at {SRC.relative_to(ROOT)} (it is gitignored) "
                     "— fidelity NOT checked; only coverage ran")
        return
    if not shutil.which("rsvg-convert"):
        SKIPS.append("rsvg-convert not on PATH (brew install librsvg) — fidelity NOT checked; "
                     "only coverage ran")
        return
    print("\nFIDELITY — every consumer still renders what the source says")
    manifest = json.loads((SRC/"manifest.json").read_text())
    bg = {a["id"]: a["android"]["background"] for a in manifest["apps"]}
    tilebg = {a["id"]: a["tile"]["background"] for a in manifest["apps"]}

    # ---- sources themselves still obey the construction rules (ADR §3)
    for a in manifest["apps"]:
        ident = a["id"]
        src = source_for("android/foreground", ident)
        if not src: fail("source", f"{ident} has no android/foreground"); continue
        w, h, nc, px = render_svg(src, f"src-{ident}", 432)
        best = max(((x+.5-216)**2 + (y+.5-216)**2)
                   for y in range(h) for x in range(w) if px[(y*w+x)*nc+3] > 16)
        r = best ** 0.5 / 4.125
        if r > 30.0: fail("source", f"{ident} radius {r:.1f} exceeds 30 (ADR §3)")
        else: ok("source", f"{ident} radius {r:.1f}")

    # ---- Android: the port must render exactly what the source does
    for mod, ident in android_modules():
        for kind, fname in (("android/foreground","ic_launcher_foreground"),
                            ("android/themed","ic_launcher_monochrome")):
            src = source_for(kind, ident)
            vd  = ROOT/mod/"src/main/res/drawable"/f"{fname}.xml"
            if src is None or not vd.exists(): continue
            try: mine = render_svg(vd_to_svg(vd), f"vd-{mod}-{fname}", 432)
            except ValueError as e: fail(f"android/{mod}", str(e)); continue
            n = alpha_diff(mine, render_svg(src, f"srcref-{ident}-{kind}", 432))
            if n: fail(f"android/{mod}", f"{fname} differs from source by {n} px")
            else: ok(f"android/{mod}", fname)
        bgf = ROOT/mod/"src/main/res/values/ic_launcher_background.xml"
        if bgf.exists() and ident in bg:
            got = re.search(r"#[0-9A-Fa-f]{6}", bgf.read_text()).group(0)
            if got.upper() != bg[ident].upper():
                fail(f"android/{mod}", f"background {got}, manifest says {bg[ident]}")
            else: ok(f"android/{mod}", f"background {got}")

    # ---- iOS: full-bleed square, no alpha, same artwork as the tile (ADR §7)
    for proj, ident in ios_projects():
        src = source_for("foreground", ident)
        if src is None or ident not in tilebg: continue
        if not (ROOT/proj/"Resources/Assets.xcassets/AppIcon.appiconset/icon_1024.png").exists(): continue
        inner = re.sub(r"^.*?<svg[^>]*>", "", src, flags=re.S).replace("</svg>", "")
        want = render_svg(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">'
                          f'<rect width="1024" height="1024" fill="{tilebg[ident]}"/>{inner}</svg>',
                          f"ios-{ident}", 1024)
        base = ROOT/proj/"Resources/Assets.xcassets/AppIcon.appiconset"
        n = rgb_diff(read_png(base/"icon_1024.png"), want)
        if n: fail(f"ios/{proj}", f"icon_1024.png differs from source by {n} channels")
        else: ok(f"ios/{proj}", "default matches source")
        # dark and tinted derive from android/themed — foreground paints interior
        # detail in the BACKGROUND colour, a fake hole that fills in on transparency
        themed = source_for("android/themed", ident)
        if themed:
            t_inner = re.search(r'<g transform="translate\(84 84\) scale\(4\.125\)">(.*)</g>\s*</svg>',
                                themed, re.S)
            if t_inner:
                wrapped = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">'
                           f'<g transform="translate(184.32 184.32) scale(10.24)">{t_inner.group(1)}</g></svg>')
                fg = {a["id"]: a["tile"]["foreground"] for a in manifest["apps"]}.get(ident, "#FFFFFF")
                for fname, text in (("icon_1024_dark.png",   wrapped.replace("#FFFFFF", fg)),
                                    ("icon_1024_tinted.png", wrapped)):
                    f = base/fname
                    if not f.exists(): continue
                    d = alpha_diff(read_png(f), render_svg(text, f"i18-{ident}-{fname}", 1024))
                    if d: fail(f"ios/{proj}", f"{fname} differs from themed source by {d} px")
                    else: ok(f"ios/{proj}", fname)

    # ---- web: six hand-copied assets, all from favicon/tenshu.svg (ADR §8)
    fav = source_for("favicon", "tenshu")
    if fav:
        for p in ("web/apex/public/icons/tenshu.svg", "web/drive/public/icons/tenshu.svg"):
            if (ROOT/p).exists() and (ROOT/p).read_text() != fav:
                fail("web", f"{p} differs from favicon/tenshu.svg")
            else: ok("web", p)
        for p, size in (("web/apex/public/icons/favicon-32.png",32),
                        ("web/apex/public/icons/apple-touch-icon.png",180),
                        ("web/apex/public/icons/pwa-192.png",192),
                        ("web/apex/public/icons/pwa-512.png",512)):
            if not (ROOT/p).exists(): continue
            n = rgb_diff(read_png(ROOT/p), render_svg(fav, "web-fav", size))
            if n: fail("web", f"{p} differs from source by {n} channels")
            else: ok("web", p)

# ====================================================================== main
def main():
    print(f"Checking the icon set against {ADR}\n")
    check_coverage()
    check_fidelity()
    print()
    for s in SKIPS: print(f"  SKIPPED  {s}")
    if FAILURES:
        print(f"\n{len(FAILURES)} problem(s):")
        for where, msg in FAILURES: print(f"  FAIL  {where}: {msg}")
        print(f"\n{CHECKS} checks passed, {len(FAILURES)} failed.")
        return 1
    print(f"{CHECKS} checks passed" + (", fidelity skipped." if SKIPS else ", nothing adrift."))
    return 0

if __name__ == "__main__":
    sys.exit(main())
