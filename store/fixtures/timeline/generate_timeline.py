"""Builds Tenshu Timeline's store-screenshot fixture from store/fixtures/timeline/manifest.json.

    python3 store/fixtures/timeline/generate_timeline.py <out dir> [--preview <png>]

Writes, relative to today in this machine's time zone (the simulator's, which is the Mac's):

    <out>/Semantic Location History/<YYYY>_<MONTH>.json   one legacy Google Timeline export per month

Timeline's `-seedFixture` launch hands every file to the shared Google Timeline importer
(`FileTimelineImporter.importFiles`, the call *Import from Google Takeout* makes on Android and
desktop). The legacy shape is used because it carries place names (`placeVisit.location.name`):
the 2024+ on-device export names nothing, and an iOS install looks nothing up.

Each visit ends at its manifest time; the leg after it starts then, lasts as long as its distance
takes at the mode's speed, and the next visit starts on arrival. Florence walks follow the bundled
basemap's streets (store/fixtures/fit/streets.py), each point timestamped by distance. Anything after
*now* is cut off and the stay in progress ends at *now*. Before the manifest's `notBefore` hour
today has too little in it, and the generator refuses.

`--preview` draws every Florence leg over the street network (needs Pillow) — to check by eye.

⚠ Nothing it writes is committed: store-screenshots.md §3 ("Nothing binary under store/").
"""

import datetime as dt
import json
import math
import os
import sys

sys.dont_write_bytecode = True  # no __pycache__ under store/ (check-store-assets.py rejects binaries)
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(HERE, "..", "fit"))
import streets  # noqa: E402

# km/h on the ground, and how much longer than the straight line a leg off the basemap runs.
SPEED = {"WALKING": 4.8, "RUNNING": 10.0, "CYCLING": 14.0, "IN_SUBWAY": 24.0}
DETOUR = 1.3
WEEKDAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]


def local(day, hhmm):
    h, m = (int(v) for v in hhmm.split(":"))
    return dt.datetime.combine(day, dt.time(h, m)).astimezone()


def iso(t):
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def e7(v):
    return int(round(v * 1e7))


def length(points):
    return sum(streets.haversine(a, b) for a, b in zip(points, points[1:]))


def timed(points, start, end):
    """Points timestamped by the distance along the line, from `start` to `end`."""
    total = length(points) or 1.0
    out, run = [], 0.0
    for i, p in enumerate(points):
        if i:
            run += streets.haversine(points[i - 1], p)
        out.append((p, start + (end - start) * (run / total)))
    return out


def straight(a, b):
    """A leg off the basemap: the straight line with a gentle bend, so it reads as a path."""
    mid = ((a[0] + b[0]) / 2 + (b[1] - a[1]) * 0.08, (a[1] + b[1]) / 2 - (b[0] - a[0]) * 0.08)
    return [a, mid, b]


class Builder:
    def __init__(self, manifest, network, now):
        self.places = manifest["places"]
        self.train = manifest["train"]
        self.network = network
        self.now = now
        self.objects = []   # (start, json object)
        self.legs = []      # Florence legs, for --preview
        self.here = None    # [place key, start, end or None]

    def step(self, day, key, until, mode):
        end = local(day, until) if until else None
        if self.here and self.here[0] == key and self.here[2] is None:
            self.here[2] = end
            return
        if self.here is None:
            self.here = [key, local(day, "00:00"), end]
            return
        self.leg(key, mode)
        if end is not None and end <= self.here[1]:
            sys.exit(f"generate_timeline: {self.places[key]['name']} on {day} ends at {until}, before the "
                     f"leg there arrives ({self.here[1]:%H:%M}) — move its time")
        self.here[2] = end

    def leg(self, key, mode):
        """Closes the current stay, then travels to `key`; the new stay starts on arrival."""
        frm, start = self.here[0], self.here[2]
        if start is None:
            sys.exit(f"generate_timeline: the stay at {self.places[frm]['name']} has no end before the leg on")
        self.close()
        a, b = self.places[frm], self.places[key]
        if mode == "IN_TRAIN":
            points = self.train["via"]
            end = start + dt.timedelta(minutes=self.train["minutes"])
            metres = length(points) * 1.15
        else:
            if a.get("florence") and b.get("florence"):
                points = [tuple(a["at"])] + self.network.route([a["at"], b["at"]]) + [tuple(b["at"])]
                self.legs.append(points)
                metres = length(points)
            else:
                points = straight(tuple(a["at"]), tuple(b["at"]))
                metres = length(points) * DETOUR
            minutes = metres / 1000 / SPEED[mode] * 60 + (6 if mode == "IN_SUBWAY" else 0)
            end = start + dt.timedelta(minutes=max(3, round(minutes)))
        self.objects.append((start, {"activitySegment": {
            "startLocation": {"latitudeE7": e7(a["at"][1]), "longitudeE7": e7(a["at"][0])},
            "endLocation": {"latitudeE7": e7(b["at"][1]), "longitudeE7": e7(b["at"][0])},
            "duration": {"startTimestamp": iso(start), "endTimestamp": iso(end)},
            "distance": int(round(metres)),
            "activityType": mode,
            "confidence": "HIGH",
            "simplifiedRawPath": {"points": [
                {"latE7": e7(p[1]), "lngE7": e7(p[0]), "timestamp": iso(t), "accuracyMeters": 8}
                for p, t in timed(points, start, end)]},
        }}))
        self.here = [key, end, None]

    def close(self):
        key, start, end = self.here
        p = self.places[key]
        self.objects.append((start, {"placeVisit": {
            "location": {"latitudeE7": e7(p["at"][1]), "longitudeE7": e7(p["at"][0]), "name": p["name"]},
            "duration": {"startTimestamp": iso(start), "endTimestamp": iso(end)},
        }}))

    def finish(self):
        """The stay in progress ends at now; everything that starts after it is dropped."""
        self.here[2] = self.now if self.here[2] is None or self.here[2] > self.now else self.here[2]
        self.close()
        kept = []
        for start, obj in sorted(self.objects, key=lambda o: o[0]):
            if start >= self.now:
                continue
            body = obj.get("placeVisit") or obj["activitySegment"]
            if dt.datetime.fromisoformat(body["duration"]["endTimestamp"].replace("Z", "+00:00")) > self.now:
                if "activitySegment" in obj:
                    continue  # a leg still under way: cut, the stay before it is the last
                body["duration"]["endTimestamp"] = iso(self.now)
            kept.append((start, obj))
        return kept


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
    now = dt.datetime.now().astimezone().replace(second=0, microsecond=0)
    today = now.date()
    if now < local(today, manifest["notBefore"]):
        sys.exit(f"generate_timeline: it is {now:%H:%M} — today's chapter needs the morning in Florence; "
                 f"run after {manifest['notBefore']}")

    network = streets.Network(os.path.join(ROOT, streets.ARCHIVE))
    b = Builder(manifest, network, now)
    home = manifest["homeDays"]
    for ago in range(home["from"], home["to"] - 1, -1):
        day = today - dt.timedelta(days=ago)
        for step in manifest["homeDays"]["weekday"][WEEKDAYS[day.weekday()]]:
            b.step(day, step[0], step[1], step[2] if len(step) > 2 else None)
    for d in manifest["trip"]:
        day = today - dt.timedelta(days=d["daysAgo"])
        for step in d["steps"]:
            b.step(day, step[0], step[1], step[2] if len(step) > 2 else None)
    kept = b.finish()

    folder = os.path.join(out, "Semantic Location History")
    os.makedirs(folder, exist_ok=True)
    months = {}
    for start, obj in kept:
        months.setdefault((start.year, start.month), []).append(obj)
    for (year, month), objs in sorted(months.items()):
        name = f"{year}_{dt.date(year, month, 1).strftime('%B').upper()}.json"
        with open(os.path.join(folder, name), "w") as f:
            json.dump({"timelineObjects": objs}, f, indent=1, ensure_ascii=False)
    visits = sum("placeVisit" in o for _, o in kept)
    print(f"generate_timeline: {visits} visits, {len(kept) - visits} legs over {len(months)} month file(s), "
          f"{today - dt.timedelta(days=home['from'])} – {now:%Y-%m-%d %H:%M} → {folder}")
    for start, obj in kept:
        if start.date() == today:
            body = obj.get("placeVisit")
            what = body["location"]["name"] if body else obj["activitySegment"]["activityType"].lower()
            print(f"  today {start:%H:%M}  {what}")

    if preview:
        draw_preview(network, b.legs, manifest, preview)


def draw_preview(network, legs, manifest, path):
    from PIL import Image, ImageDraw
    minlon, minlat, maxlon, maxlat = network.bounds
    w = 1600
    h = int(w * (maxlat - minlat) / ((maxlon - minlon) * math.cos(math.radians(minlat))))
    px = lambda p: ((p[0] - minlon) / (maxlon - minlon) * w, (maxlat - p[1]) / (maxlat - minlat) * h)
    img = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(img)
    for a, nbrs in network.adj.items():
        for c in nbrs:
            d.line([px(a), px(c)], fill=(215, 215, 215), width=1)
    for leg in legs:
        d.line([px(p) for p in leg], fill=(30, 150, 70), width=3)
    for p in manifest["places"].values():
        if p.get("florence"):
            x, y = px(p["at"])
            d.ellipse([x - 6, y - 6, x + 6, y + 6], fill=(220, 60, 40))
            d.text((x + 8, y - 6), p["name"], fill=(0, 0, 0))
    img.save(path)
    print(f"generate_timeline: preview → {path}")


if __name__ == "__main__":
    main()
