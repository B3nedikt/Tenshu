"""Builds Tenshu Fit's store-screenshot fixture from store/fixtures/fit/manifest.json.

    python3 store/fixtures/fit/generate_fit.py <out dir> [--preview <png>]

Writes, relative to today in this machine's time zone:

    <out>/health.json          the daily figures Fit's `-seedFixture` launch writes into Apple Health
    <out>/workouts/NN-*.gpx    one GPX per workout (route + elevation + heart rate), which the same
                               launch imports through Fit's own file import

Deterministic for a given manifest and day: each day's figures, vitals and night draw from their
own seeded generators, so the hour of the run changes only what is cut off at "now". `--preview` draws every route over the street network
(needs Pillow) — to check the routes by eye; never part of a run.

⚠ Nothing it writes is committed: store-screenshots.md §3 ("Nothing binary under store/").
"""

import datetime as dt
import json
import math
import os
import random
import sys

sys.dont_write_bytecode = True  # no __pycache__ under store/ (check-store-assets.py rejects binaries)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import streets  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))

# A bike keeps off footways and steps; a walk or run goes anywhere a person may.
BIKE = {"primary", "secondary", "tertiary", "tertiary_link", "residential", "unclassified",
        "living_street", "service", "cycleway", "track", "pedestrian"}


def local(day, hhmm):
    h, m = (int(v) for v in hhmm.split(":"))
    return dt.datetime.combine(day, dt.time(h, m)).astimezone()


def iso(t):
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def lerp(a, b, f):
    return a + (b - a) * f


# ── workouts ─────────────────────────────────────────────────────────────────

def elevation(p, manifest):
    e = manifest["riverEle"]
    for hill in manifest["hills"]:
        d = streets.haversine(p, hill["at"])
        e += hill["metres"] * math.exp(-(d / hill["radius"]) ** 2)
    return e


def resample(route, step_m):
    """Points every `step_m` metres along a polyline → [(lon, lat, metres from start)]."""
    out = [(route[0][0], route[0][1], 0.0)]
    travelled, carry = 0.0, 0.0
    for a, b in zip(route, route[1:]):
        seg = streets.haversine(a, b)
        if seg == 0:
            continue
        pos = step_m - carry
        while pos <= seg:
            f = pos / seg
            out.append((lerp(a[0], b[0], f), lerp(a[1], b[1], f), travelled + pos))
            pos += step_m
        carry = seg - (pos - step_m)
        travelled += seg
    out.append((route[-1][0], route[-1][1], travelled))
    return out


def gpx(workout, start, network, manifest, rng):
    places = manifest["places"]
    route = network.route([places[name] for name in workout["via"]])
    pts = resample(route, 12.0)
    if "kmh" in workout:
        base = workout["kmh"] / 3.6
    else:
        base = 1000.0 / (workout["paceMinKm"] * 60)
    t = start
    hr = workout["hr"] - 45
    trkpts = []
    prev_ele = elevation(pts[0][:2], manifest)
    for i, (lon, lat, metres) in enumerate(pts):
        ele = elevation((lon, lat), manifest)
        if i:
            step = metres - pts[i - 1][2]
            climb = (ele - prev_ele) / max(step, 1.0)
            speed = base * (1 - 4.0 * climb) * rng.uniform(0.94, 1.06)
            if "kmh" in workout and climb < 0:
                speed = base * min(1.5, 1 - 3.0 * climb)
            t += dt.timedelta(seconds=step / max(speed, 0.6))
            # Heart rate rises over the first minutes, then tracks the effort.
            target = workout["hr"] + 260 * max(climb, -0.02) + rng.uniform(-3, 3)
            hr += (target - hr) * 0.18
        prev_ele = ele
        trkpts.append(
            f'      <trkpt lat="{lat:.6f}" lon="{lon:.6f}"><ele>{ele:.1f}</ele><time>{iso(t)}</time>'
            f'<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>{round(hr)}</gpxtpx:hr>'
            f'</gpxtpx:TrackPointExtension></extensions></trkpt>')
    body = "\n".join(trkpts)
    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Tenshu store fixture" xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
  <trk>
    <name>{workout["name"]}</name>
    <type>{workout["type"]}</type>
    <trkseg>
{body}
    </trkseg>
  </trk>
</gpx>
"""
    return xml, route, pts[-1][2], t


# ── daily figures ────────────────────────────────────────────────────────────

def quantity(kind, unit, value, start, end):
    return {"type": kind, "unit": unit, "value": round(value, 2), "start": iso(start), "end": iso(end)}


def daily(manifest, today, now, workout_km, morning_starts):
    p = manifest["person"]
    days = manifest["days"]
    out = {"quantities": [], "sleep": []}
    # Height once, a few weeks back.
    t = local(today - dt.timedelta(days=days - 1), "08:00")
    out["quantities"].append(quantity("height", "m", p["heightM"], t, t))
    for d in range(days - 1, -1, -1):
        day = today - dt.timedelta(days=d)
        rng = random.Random(f"{manifest['seed']}-day-{d}")
        progress = 1 - d / (days - 1)
        weekend = day.weekday() >= 5
        lo, hi = p["steps"]["weekend" if weekend else "weekday"]
        steps = rng.uniform(lo, hi) + workout_km.get(d, 0) * 1300
        # The day's steps in blocks, so a chart of the day has a shape; today stops at now.
        blocks = [("07:30", 0.12), ("09:00", 0.18), ("12:30", 0.2), ("15:00", 0.15), ("18:00", 0.22), ("20:30", 0.13)]
        for start, share in blocks:
            a = local(day, start)
            b = a + dt.timedelta(minutes=55)
            if b > now:
                continue
            n = steps * share
            out["quantities"].append(quantity("stepCount", "count", round(n), a, b))
            out["quantities"].append(quantity("distanceWalkingRunning", "m", n * 0.74, a, b))
            out["quantities"].append(quantity("activeEnergyBurned", "kcal", n * 0.042 + rng.uniform(5, 20), a, b))
        if d > 0 or now.hour >= 20:
            a = local(day, "19:30")
            out["quantities"].append(quantity("dietaryEnergyConsumed", "kcal", rng.uniform(*p["dietaryKcal"]), a, a))
            out["quantities"].append(quantity("dietaryWater", "mL", rng.uniform(*p["waterMl"]), a, a))
        # Resting heart rate every morning, weight about twice a week — their own generator, since
        # how many step blocks drew before them depends on the hour of the run.
        morning = local(day, "07:00")
        rng = random.Random(f"{manifest['seed']}-vitals-{d}")
        if morning <= now:
            rhr = lerp(p["restingHr"]["start"], p["restingHr"]["end"], progress) + rng.uniform(-1.5, 1.5)
            out["quantities"].append(quantity("restingHeartRate", "count/min", round(rhr), morning, morning))
            if day.weekday() in (0, 3):
                kg = lerp(p["weightKg"]["start"], p["weightKg"]["end"], progress) + rng.uniform(-0.25, 0.25)
                out["quantities"].append(quantity("bodyMass", "kg", kg, morning, morning))
        if d == 0:
            out["quantities"].append(quantity("oxygenSaturation", "%", 0.98, morning, morning))
            out["quantities"].append(quantity("respiratoryRate", "count/min", 14.5, morning, morning))
        # The night that ENDS this morning — up at least 20 minutes before a morning workout.
        rng = random.Random(f"{manifest['seed']}-night-{d}")
        bed = local(day - dt.timedelta(days=1), p["sleep"]["bedtime"]) + dt.timedelta(minutes=rng.uniform(-35, 45))
        total = rng.uniform(*p["sleep"]["minutes"])
        t = bed + dt.timedelta(minutes=rng.uniform(8, 20))  # falling asleep
        if d in morning_starts:
            latest = morning_starts[d] - dt.timedelta(minutes=26)  # wake, then 6 min more in bed
            total = min(total, (latest - t).total_seconds() / 60)
        out["sleep"].append({"stage": "inBed", "start": iso(bed), "end": iso(t + dt.timedelta(minutes=total + 6))})
        end = t + dt.timedelta(minutes=total)
        cycle = 0
        while t < end:
            # ~90-minute cycles: deep early in the night, REM later.
            deep = max(5.0, 35 - cycle * 8) * rng.uniform(0.8, 1.2)
            rem = (12 + cycle * 7) * rng.uniform(0.8, 1.2)
            core = 90 - deep - rem
            for stage, minutes in (("core", core * 0.5), ("deep", deep), ("core", core * 0.5), ("rem", rem)):
                nxt = min(end, t + dt.timedelta(minutes=minutes))
                if nxt > t:
                    out["sleep"].append({"stage": stage, "start": iso(t), "end": iso(nxt)})
                t = nxt
            if t < end and rng.random() < 0.35:
                nxt = min(end, t + dt.timedelta(minutes=rng.uniform(2, 7)))
                out["sleep"].append({"stage": "awake", "start": iso(t), "end": iso(nxt)})
                t = nxt
            cycle += 1
    return out


def main():
    args = sys.argv[1:]
    preview = None
    if "--preview" in args:
        i = args.index("--preview")
        preview = args[i + 1]
        del args[i:i + 2]
    if len(args) != 1:
        sys.exit(__doc__)
    out = args[0]
    manifest = json.load(open(os.path.join(HERE, "manifest.json")))
    rng = random.Random(manifest["seed"])
    now = dt.datetime.now().astimezone()
    today = now.date()

    walk_net = streets.Network(os.path.join(ROOT, streets.ARCHIVE))
    bike_net = streets.Network(os.path.join(ROOT, streets.ARCHIVE), classes=BIKE)
    os.makedirs(os.path.join(out, "workouts"), exist_ok=True)
    routes, workout_km, morning_starts = [], {}, {}
    for n, w in enumerate(manifest["workouts"], 1):
        start = local(today - dt.timedelta(days=w["daysAgo"]), w["start"])
        if start > now:
            sys.exit(f"generate_fit: {w['name']} starts after now ({w['start']}) — run later in the day, "
                     "or move its start")
        net = bike_net if w["type"] == "Cycling" else walk_net
        xml, route, metres, end = gpx(w, start, net, manifest, rng)
        if end > now:
            sys.exit(f"generate_fit: {w['name']} ends after now ({end:%H:%M}) — run later in the day, "
                     "or move its start")
        if start.hour < 12:
            d = w["daysAgo"]
            morning_starts[d] = min(start, morning_starts.get(d, start))
        slug = "".join(c if c.isalnum() else "-" for c in w["name"].lower()).strip("-")
        with open(os.path.join(out, "workouts", f"{n:02d}-{slug}.gpx"), "w") as f:
            f.write(xml)
        if w["type"] != "Cycling":
            workout_km[w["daysAgo"]] = workout_km.get(w["daysAgo"], 0) + metres / 1000
        routes.append((w, route))
        print(f"  {n:02d} {w['type']:<8} {metres / 1000:5.1f} km  {start:%a %d %b %H:%M}  {w['name']}")

    health = daily(manifest, today, now, workout_km, morning_starts)
    with open(os.path.join(out, "health.json"), "w") as f:
        json.dump(health, f, indent=1)
    print(f"generate_fit: {len(routes)} workouts, {len(health['quantities'])} quantities, "
          f"{len(health['sleep'])} sleep stages → {out}")

    if preview:
        draw_preview(walk_net, routes, preview)


def draw_preview(network, routes, path):
    from PIL import Image, ImageDraw
    minlon, minlat, maxlon, maxlat = network.bounds
    w = 1600
    h = int(w * (maxlat - minlat) / ((maxlon - minlon) * math.cos(math.radians(minlat))))
    px = lambda p: ((p[0] - minlon) / (maxlon - minlon) * w, (maxlat - p[1]) / (maxlat - minlat) * h)
    img = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(img)
    for a, nbrs in network.adj.items():
        for b in nbrs:
            d.line([px(a), px(b)], fill=(215, 215, 215), width=1)
    colours = {"Running": (220, 60, 40), "Cycling": (40, 90, 220), "Walking": (30, 150, 70)}
    for w_, route in routes:
        d.line([px(p) for p in route], fill=colours.get(w_["type"], (0, 0, 0)), width=3)
    img.save(path)
    print(f"generate_fit: preview → {path}")


if __name__ == "__main__":
    main()
