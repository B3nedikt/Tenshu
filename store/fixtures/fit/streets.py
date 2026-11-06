"""The street network of the basemap every Tenshu app bundles, for routing fixture workouts.

The iOS apps draw only the bundled extract (`ui-map/src/androidMain/assets/tenshu_basemap.pmtiles`,
central Florence) unless a region is downloaded, so a fixture route outside it would sit on a blank
map. Reading the roads out of that same archive keeps every route on streets the map actually draws.

Pure standard library: a PMTiles v3 directory reader and a minimal Mapbox Vector Tile decoder —
just the parts this needs (the Protomaps `roads` layer's line strings at the archive's max zoom).
"""

import gzip
import math
import struct

ARCHIVE = "ui-map/src/androidMain/assets/tenshu_basemap.pmtiles"


# ── protobuf ─────────────────────────────────────────────────────────────────

def _varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def _fields(buf):
    """Yields (field number, wire type, value) over one message."""
    i = 0
    while i < len(buf):
        key, i = _varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, i = _varint(buf, i)
        elif wire == 2:
            n, i = _varint(buf, i)
            value, i = buf[i:i + n], i + n
        elif wire == 1:
            value, i = buf[i:i + 8], i + 8
        elif wire == 5:
            value, i = buf[i:i + 4], i + 4
        else:
            raise ValueError(f"wire type {wire}")
        yield field, wire, value


def _packed(buf):
    out, i = [], 0
    while i < len(buf):
        v, i = _varint(buf, i)
        out.append(v)
    return out


def _zigzag(n):
    return (n >> 1) ^ -(n & 1)


# ── PMTiles v3 ───────────────────────────────────────────────────────────────

def _tile_id(z, x, y):
    """The Hilbert-curve tile id PMTiles v3 indexes by."""
    acc = sum(4 ** i for i in range(z))
    n = 1 << z
    d, rx_x, rx_y = 0, x, y
    s = n // 2
    while s > 0:
        rx = 1 if rx_x & s else 0
        ry = 1 if rx_y & s else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                rx_x, rx_y = s - 1 - rx_x, s - 1 - rx_y
            rx_x, rx_y = rx_y, rx_x
        s //= 2
    return acc + d


def _decompress(data, kind):
    return gzip.decompress(data) if kind == 2 else data


def _directory(data):
    i = 0
    n, i = _varint(data, i)
    ids, last = [], 0
    for _ in range(n):
        v, i = _varint(data, i)
        last += v
        ids.append(last)
    runs = []
    for _ in range(n):
        v, i = _varint(data, i)
        runs.append(v)
    lengths = []
    for _ in range(n):
        v, i = _varint(data, i)
        lengths.append(v)
    offsets = []
    for k in range(n):
        v, i = _varint(data, i)
        offsets.append(offsets[k - 1] + lengths[k - 1] if v == 0 and k > 0 else v - 1)
    return list(zip(ids, runs, lengths, offsets))


class Archive:
    def __init__(self, path):
        self.data = open(path, "rb").read()
        h = self.data[:127]
        if h[:7] != b"PMTiles" or h[7] != 3:
            raise ValueError("not a PMTiles v3 archive")
        q = lambda o: struct.unpack("<Q", h[o:o + 8])[0]
        self.root = (q(8), q(16))
        self.leaves = q(40)
        self.tile_data = q(56)
        self.internal_compression = h[97]
        self.tile_compression = h[98]
        self.min_zoom, self.max_zoom = h[100], h[101]
        self.bounds = [struct.unpack("<i", h[o:o + 4])[0] / 1e7 for o in (102, 106, 110, 114)]

    def _dir(self, offset, length):
        return _directory(_decompress(self.data[offset:offset + length], self.internal_compression))

    def tile(self, z, x, y):
        tid = _tile_id(z, x, y)
        entries = self._dir(*self.root)
        for _ in range(4):
            hit = None
            for e in entries:
                if e[0] <= tid:
                    hit = e
                else:
                    break
            if hit is None:
                return None
            eid, run, length, offset = hit
            if run == 0:  # a leaf directory
                entries = self._dir(self.leaves + offset, length)
                continue
            if tid < eid + run:
                raw = self.data[self.tile_data + offset:self.tile_data + offset + length]
                return _decompress(raw, self.tile_compression)
            return None
        return None


# ── vector tiles ─────────────────────────────────────────────────────────────

def _lines(geometry):
    """A LINESTRING feature's command stream → lists of (x, y) in tile units."""
    out, cur, x, y, i = [], None, 0, 0, 0
    while i < len(geometry):
        cmd = geometry[i]
        op, count = cmd & 7, cmd >> 3
        i += 1
        for _ in range(count):
            if op in (1, 2):
                x += _zigzag(geometry[i])
                y += _zigzag(geometry[i + 1])
                i += 2
                if op == 1:
                    cur = [(x, y)]
                    out.append(cur)
                else:
                    cur.append((x, y))
    return out


def roads(tile_bytes):
    """[(kind_detail, [(x, y), …], extent, level)] for every line in the tile's `roads` layer."""
    result = []
    for field, _, layer in _fields(tile_bytes):
        if field != 3:
            continue
        name, keys, values, features, extent = "", [], [], [], 4096
        for f, _, v in _fields(layer):
            if f == 1:
                name = v.decode()
            elif f == 2:
                features.append(v)
            elif f == 3:
                keys.append(v.decode())
            elif f == 4:
                val = None
                for vf, vw, vv in _fields(v):
                    val = vv.decode() if vf == 1 else vv
                values.append(val)
            elif f == 5:
                extent = v
        if name != "roads":
            continue
        for feat in features:
            tags, gtype, geom = [], 0, []
            for f, _, v in _fields(feat):
                if f == 2:
                    tags = _packed(v)
                elif f == 3:
                    gtype = v
                elif f == 4:
                    geom = _packed(v)
            if gtype != 2:
                continue
            props = {keys[tags[k]]: values[tags[k + 1]] for k in range(0, len(tags), 2)}
            for line in _lines(geom):
                # ⚠ A bridge and the road beneath it cross without meeting: the level keeps them apart.
                level = props.get("pmap:level") or props.get("layer") or 0
                result.append((props.get("pmap:kind_detail") or props.get("pmap:kind", ""), line, extent, level))
    return result


# ── the network ──────────────────────────────────────────────────────────────

def lonlat_to_tile(lon, lat, z):
    n = 1 << z
    x = (lon + 180) / 360 * n
    y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
    return x, y


def tile_to_lonlat(x, y, z):
    n = 1 << z
    lon = x / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    return lon, lat


def haversine(a, b):
    (lon1, lat1), (lon2, lat2) = a, b
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def _cross(a, b, c, d, snap):
    """Where segments ab and cd meet, as (t along ab, u along cd), or None. An end within `snap`
    metres of the other segment counts as meeting it."""
    rx, ry = b[0] - a[0], b[1] - a[1]
    sx, sy = d[0] - c[0], d[1] - c[1]
    den = rx * sy - ry * sx
    if den != 0:
        t = ((c[0] - a[0]) * sy - (c[1] - a[1]) * sx) / den
        u = ((c[0] - a[0]) * ry - (c[1] - a[1]) * rx) / den
        if 0 <= t <= 1 and 0 <= u <= 1:
            return t, u
    # T-junctions: an endpoint of one lying (almost) on the other.
    def project(p, o, v):
        l2 = v[0] ** 2 + v[1] ** 2
        if l2 == 0:
            return None
        f = ((p[0] - o[0]) * v[0] + (p[1] - o[1]) * v[1]) / l2
        if not 0 <= f <= 1:
            return None
        qx, qy = o[0] + v[0] * f, o[1] + v[1] * f
        return f if math.hypot(p[0] - qx, p[1] - qy) <= snap else None
    for p, end in ((c, 0.0), (d, 1.0)):
        f = project(p, a, (rx, ry))
        if f is not None:
            return f, end
    for p, end in ((a, 0.0), (b, 1.0)):
        f = project(p, c, (sx, sy))
        if f is not None:
            return end, f
    return None


class Network:
    """An undirected graph of the archive's streets: nodes are rounded (lon, lat) points.

    ⚠⚠ The tiles' lines are simplified, so two streets that meet rarely share a vertex: taken as
    drawn, most crossings are not junctions and a 600 m walk routes 3.5 km round. Every pair of
    segments on the same level is therefore intersected and split where they cross, and a line
    ending within a couple of metres of another is joined to it (a T-junction).
    """

    def __init__(self, path=ARCHIVE, classes=None):
        archive = Archive(path)
        self.bounds = archive.bounds
        z = archive.max_zoom
        minlon, minlat, maxlon, maxlat = self.bounds
        x0, y1 = (int(v) for v in lonlat_to_tile(minlon, minlat, z))
        x1, y0 = (int(v) for v in lonlat_to_tile(maxlon, maxlat, z))
        # A local plane in metres, good to well under a metre over a few kilometres.
        self._lat0 = (minlat + maxlat) / 2
        self._k = 111320 * math.cos(math.radians(self._lat0))
        segments = set()
        for tx in range(x0, x1 + 1):
            for ty in range(y0, y1 + 1):
                data = archive.tile(z, tx, ty)
                if not data:
                    continue
                for cls, line, extent, level in roads(data):
                    if classes is not None and cls not in classes:
                        continue
                    pts = [self._xy(tile_to_lonlat(tx + px / extent, ty + py / extent, z)) for px, py in line]
                    for a, b in zip(pts, pts[1:]):
                        if a != b:
                            segments.add((min(a, b), max(a, b), level))
        self.adj = {}
        for a, b in self._split(sorted(segments)):
            na, nb = self._node(self._lonlat(a)), self._node(self._lonlat(b))
            if na != nb:
                w = haversine(na, nb)
                self.adj.setdefault(na, {})[nb] = w
                self.adj.setdefault(nb, {})[na] = w
        self._largest_component()

    def _xy(self, p):
        return round(p[0] * self._k, 2), round(p[1] * 110574, 2)

    def _lonlat(self, xy):
        return xy[0] / self._k, xy[1] / 110574

    @staticmethod
    def _split(segments, cell=60.0, snap=2.5):
        """Each segment cut at every point where another one on its level crosses or touches it."""
        grid = {}
        for i, (a, b, _) in enumerate(segments):
            for gx in range(int(min(a[0], b[0]) // cell), int(max(a[0], b[0]) // cell) + 1):
                for gy in range(int(min(a[1], b[1]) // cell), int(max(a[1], b[1]) // cell) + 1):
                    grid.setdefault((gx, gy), []).append(i)
        cuts = [[0.0, 1.0] for _ in segments]
        for members in grid.values():
            for x in range(len(members)):
                i = members[x]
                a, b, la = segments[i]
                for y in range(x + 1, len(members)):
                    j = members[y]
                    c, d, lc = segments[j]
                    if la != lc:
                        continue
                    hit = _cross(a, b, c, d, snap)
                    if hit:
                        cuts[i].append(hit[0])
                        cuts[j].append(hit[1])
        for (a, b, _), ts in zip(segments, cuts):
            ts = sorted(set(round(t, 4) for t in ts))
            pts = [(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t) for t in ts]
            for p, q in zip(pts, pts[1:]):
                yield (round(p[0], 1), round(p[1], 1)), (round(q[0], 1), round(q[1], 1))

    @staticmethod
    def _node(p):
        # ~1 m: joins the same vertex as cut at tile edges and at the crossings above.
        return round(p[0], 5), round(p[1], 5)

    def _largest_component(self):
        seen, best = set(), set()
        for start in self.adj:
            if start in seen:
                continue
            comp, stack = {start}, [start]
            while stack:
                for nxt in self.adj[stack.pop()]:
                    if nxt not in comp:
                        comp.add(nxt)
                        stack.append(nxt)
            seen |= comp
            if len(comp) > len(best):
                best = comp
        self.adj = {n: {m: w for m, w in e.items() if m in best} for n, e in self.adj.items() if n in best}

    def nearest(self, lonlat):
        """The nearest junction: snapping to any vertex can land on a dead end, and the route then
        runs out along it and back — a spur no one would run."""
        junctions = [n for n, e in self.adj.items() if len(e) >= 3] or list(self.adj)
        return min(junctions, key=lambda n: haversine(n, lonlat))

    def path(self, a, b):
        """Dijkstra between two nodes → [nodes]."""
        import heapq
        dist, prev, heap = {a: 0.0}, {}, [(0.0, a)]
        while heap:
            d, n = heapq.heappop(heap)
            if n == b:
                break
            if d > dist.get(n, math.inf):
                continue
            for m, w in self.adj[n].items():
                nd = d + w
                if nd < dist.get(m, math.inf):
                    dist[m], prev[m] = nd, n
                    heapq.heappush(heap, (nd, m))
        if b not in dist:
            raise ValueError("no path")
        out = [b]
        while out[-1] != a:
            out.append(prev[out[-1]])
        return out[::-1]

    def route(self, waypoints):
        """A route through (lon, lat) waypoints along the streets, as [(lon, lat)]."""
        nodes = [self.nearest(w) for w in waypoints]
        out = [nodes[0]]
        for a, b in zip(nodes, nodes[1:]):
            out += self.path(a, b)[1:]
        return out
