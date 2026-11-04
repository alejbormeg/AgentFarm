#!/usr/bin/env python3
"""The panel's farm: one scene, drawn at low resolution, in a day and a dusk palette, and the sprites that
carry live state over it. Run it to regenerate src/main/resources/static/art; no dependencies."""
import math, random, struct, sys, zlib

DAY = {
    "sky1": "#a9cdd6", "sky2": "#bfd9d9", "sky3": "#d6e4d6", "cloud": "#f3f1e4", "cloudD": "#dfe3d8",
    "hill": "#8fae8c", "hill2": "#7a9c78", "tree": "#4c6f4a", "treeD": "#3a5a40", "treeL": "#64875a",
    "grass": "#7d9c58", "grassD": "#6a8a4c", "grassL": "#93ad66", "path": "#c9b288", "pathD": "#b39b72", "pathL": "#d9c49c",
    "soil": "#7d5a3f", "soilD": "#654731", "soilL": "#92704f", "crop": "#6f9a48", "cropD": "#4f7a3a",
    "barn": "#8f3f31", "barnD": "#763227", "barnL": "#a44f3c", "roof": "#4a3d3a", "roofD": "#3a2f2d", "roofL": "#5d4e49",
    "tar": "#1f1c1c", "paint": "#efe9d8", "wood": "#8a6a48", "woodD": "#6c5037", "woodL": "#a3835c",
    "plaster": "#e2d6ba", "plasterD": "#c9bb9c", "glass": "#7f9fae", "glassL": "#a8c2c9", "lamp": "#e9b94f", "lampL": "#f6d98a",
    "stone": "#b8ad98", "stoneD": "#9a8f7b", "stoneL": "#cdc3ae", "cap": "#6c3a2f", "sail": "#efe8d6", "sailD": "#c7bfa9",
    "line": "#2a2522", "sack": "#d3ad6a", "sackD": "#b08c50", "sackL": "#e4c588", "crate": "#c58a43", "crateD": "#8e5f2b", "crateL": "#dfa862",
    "clover": "#4f8f4d", "cloverD": "#3e7740", "cloverL": "#69a860", "shade": "#5a7444",
    "pig": "#e9a9ae", "pigD": "#cd878e", "hat": "#1d2c47", "gold": "#e0ab3e", "horse": "#8c5c35", "horseD": "#6b4427", "mane": "#39261a",
    "white": "#f4f0e4", "donkey": "#9aa0a8", "donkeyD": "#7b8189", "ear": "#6f747b", "muzzle": "#e4dfd2", "eye": "#141414",
}

W, H = 420, 150


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


class Canvas:
    def __init__(self, w, h, fill=None):
        self.w, self.h = w, h
        self.px = [[fill] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < self.w and 0 <= y < self.h else None

    def rect(self, x, y, w, h, c):
        for j in range(y, y + h):
            for i in range(x, x + w):
                self.set(i, j, c)

    def line(self, x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for t in range(n + 1):
            self.set(round(x0 + (x1 - x0) * t / n), round(y0 + (y1 - y0) * t / n), c)

    def poly(self, pts, c):
        ys = [p[1] for p in pts]
        for y in range(min(ys), max(ys) + 1):
            xs = []
            for (a, b), (d, e) in zip(pts, pts[1:] + pts[:1]):
                if (b <= y < e) or (e <= y < b):
                    xs.append(a + (y - b) * (d - a) / (e - b))
            xs.sort()
            for l, r in zip(xs[::2], xs[1::2]):
                for x in range(round(l), round(r) + 1):
                    self.set(x, y, c)

    def sprite(self, x, y, rows, keys, outline="line", shadow=True):
        h, w = len(rows), max(map(len, rows))
        if shadow:
            cx, cy = x + w // 2, y + h
            for j in range(-1, 2):
                span = w // 2 + 1 - abs(j) * 2
                for i in range(-span, span + 1):
                    if (i + j) % 2 == 0 or abs(j) == 0:
                        self.set(cx + i, cy + j, "shade")
        solid = {(i, j) for j, r in enumerate(rows) for i, ch in enumerate(r) if ch != "."}
        if outline:
            for i, j in solid:
                for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    if (i + a, j + b) not in solid:
                        self.set(x + i + a, y + j + b, outline)
        for i, j in solid:
            self.set(x + i, y + j, keys[rows[j][i]])


HORSE = [
    "...............MM.",
    "..............MHHH",
    "..............MHeH",
    "..............MHHw",
    "..MMHHHHHHHHHHHHHw",
    ".MHHHHHHHHHHHHHD..",
    ".MHHHHgggHHHHHH...",
    ".MHHHHHHHHHHHHD...",
    "..HHHHHHHHHHHHD...",
    "..HD.HD....HD.HD..",
    "..HD.HD....HD.HD..",
    "..HD.HD....HD.HD..",
    "..WW.WW....WW.WW..",
]
HORSE_K = {"M": "mane", "H": "horse", "D": "horseD", "e": "eye", "w": "white", "g": "gold", "W": "white"}
PIG = [
    "....kkkkk.....",
    "..kkkkgkkkkk..",
    ".kkkkkkkkkkkk.",
    "..ggggggggg...",
    "..PPPPPPPPP...",
    ".PPPPPPPPeP...",
    ".PPPPPPPPPpp..",
    ".PPPPPPPPPpp..",
    ".PPPPPPPPPP...",
    ".pPPPPPPPP....",
    "..Pp.Pp.Pp....",
    "..pp.pp.pp....",
]
PIG_K = {"k": "hat", "g": "gold", "P": "pig", "p": "pigD", "e": "eye"}
DONKEY = [
    ".........E.E..",
    ".........E.E..",
    ".........E.E..",
    ".........EEE..",
    "........DDDDD.",
    "........oeDoe.",
    "........DDDDD.",
    ".........DDmm.",
    ".DDDDDDDDDDmm.",
    "DDDDDDDDDDDD..",
    ".DDDDDDDDDd...",
    ".DDDDDDDDd....",
    ".Dd.Dd.Dd.Dd..",
    ".Dd.Dd.Dd.Dd..",
    ".dd.dd.dd.dd..",
]
DONKEY_K = {"E": "ear", "D": "donkey", "d": "donkeyD", "o": "hat", "e": "white", "m": "muzzle"}


def planks(c, x, y, w, h, base, dark, light, step=4):
    c.rect(x, y, w, h, base)
    for i in range(x, x + w, step):
        c.rect(i, y, 1, h, dark)
        c.rect(i + 1, y, 1, h, light if (i // step) % 3 == 0 else base)


def dither_band(c, x, y, w, h, a, b):
    """Top a, bottom b, an ordered mix between them."""
    bayer = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]
    for j in range(h):
        t = (j / max(1, h - 1)) * 16
        for i in range(w):
            c.set(x + i, y + j, b if t > bayer[(y + j) % 4][(x + i) % 4] else a)


def tree(c, x, y, r):
    c.rect(x - 1, y, 3, r + 3, "woodD")
    for j in range(-r, r + 1):
        for i in range(-r, r + 1):
            if i * i + j * j * 1.2 <= r * r + random.randint(-2, 2):
                tone = "treeD" if (i + j) > r // 2 else ("treeL" if (i + j) < -r // 2 and random.random() < .6 else "tree")
                c.set(x + i, y - r // 2 + j, tone)


def crate(c, x, y):
    c.rect(x, y, 11, 10, "crate")
    c.rect(x, y, 11, 1, "crateL")
    c.rect(x, y + 9, 11, 1, "crateD")
    c.rect(x, y, 1, 10, "crateD")
    c.rect(x + 10, y, 1, 10, "crateD")
    c.rect(x + 1, y + 4, 9, 1, "crateD")
    c.line(x + 1, y + 8, x + 9, y + 1, "crateL")
    for j in (-1, 10):
        c.rect(x - 1, y + j, 13, 1, "line")
    c.rect(x - 1, y - 1, 1, 12, "line")
    c.rect(x + 11, y - 1, 1, 12, "line")


def sack(c, x, y, new=False):
    c.rect(x + 1, y, 7, 6, "sackL" if new else "sack")
    c.rect(x, y + 1, 9, 4, "sackL" if new else "sack")
    c.rect(x + 1, y + 5, 7, 1, "sackD")
    c.rect(x + 3, y - 1, 3, 1, "sackD")
    c.set(x + 2, y + 1, "sackL")


def scene(lit=False):
    random.seed(1945)
    c = Canvas(W, H, "grass")
    # Sky, clouds, far hills.
    dither_band(c, 0, 0, W, 18, "sky1", "sky2")
    dither_band(c, 0, 18, W, 18, "sky2", "sky3")
    for x, y, l in ((46, 8, 34), (196, 5, 26), (312, 11, 40)):
        c.rect(x, y, l, 3, "cloud")
        c.rect(x + 6, y - 2, l - 14, 2, "cloud")
        c.rect(x + 3, y + 3, l - 8, 1, "cloudD")
    for x in range(W):
        h1 = 30 + round(4 * math.sin(x / 37) + 2 * math.sin(x / 11))
        h2 = 35 + round(2 * math.sin(x / 23 + 1))
        for y in range(h1, 40):
            c.set(x, y, "hill")
        for y in range(h2, 42):
            c.set(x, y, "hill2")
    # Ground texture.
    for y in range(40, H):
        for x in range(W):
            n = random.random()
            c.set(x, y, "grassD" if n < .10 else "grassL" if n < .16 else "grass")
    for _ in range(150):
        x, y = random.randint(0, W), random.randint(42, H)
        c.set(x, y, "grassL")
        c.set(x, y - 1, "grassL")
    for x, y, r in ((6, 36, 8), (128, 34, 6), (236, 36, 7), (304, 38, 6), (346, 34, 8), (412, 40, 8)):
        tree(c, x, y, r)
    # Road.
    for x in range(120, W):
        top = 96 + round(1.5 * math.sin(x / 19))
        for y in range(top, top + 9):
            n = random.random()
            c.set(x, y, "pathD" if n < .18 else "pathL" if n < .28 else "path")
        c.set(x, top - 1, "grassD")
    # Field, with furrows.
    c.rect(10, 48, 108, 92, "soil")
    for y in range(48, 140, 8):
        c.rect(10, y, 108, 2, "soilD")
        c.rect(10, y + 2, 108, 1, "soilL")
    done = 58
    for y in range(52, 140, 8):
        for x in range(14, 116, 6):
            if x < done or y < 80 or y > 100:
                c.rect(x, y - 3, 2, 3, "crop")
                c.set(x - 1, y - 2, "cropD")
                c.set(x + 2, y - 3, "cropD")
    c.rect(9, 47, 110, 1, "woodD")
    c.rect(9, 140, 110, 1, "woodD")
    c.rect(9, 47, 1, 94, "woodD")
    c.rect(118, 47, 1, 94, "woodD")
    # Barn.
    bx, by, bw, bh = 136, 50, 92, 44
    c.rect(bx + bw, by + 4, 5, bh - 2, "shade")
    planks(c, bx, by, bw, bh, "barn", "barnD", "barnL")
    c.poly([(bx - 4, by + 1), (bx + bw // 2, by - 30), (bx + bw + 4, by + 1)], "roof")
    for j in range(by - 26, by, 4):
        for i in range(bx - 6, bx + bw + 6):
            if c.get(i, j) == "roof":
                c.set(i, j, "roofD")
    for i in range(0, bw // 2 + 4):
        c.set(bx - 4 + i, by + 1 - round(i * 31 / (bw / 2 + 4)), "roofL")
    c.rect(bx + bw // 2 - 6, by - 14, 12, 12, "woodD")
    c.rect(bx + bw // 2 - 5, by - 13, 10, 10, "tar")
    c.rect(bx + 52, by + 14, 26, 30, "barnD")
    c.rect(bx + 53, by + 15, 24, 29, "woodD")
    c.rect(bx + 64, by + 15, 2, 29, "tar")
    c.line(bx + 53, by + 15, bx + 64, by + 43, "wood")
    c.line(bx + 77, by + 15, bx + 66, by + 43, "wood")
    # The seven commandments, on a tarred wall.
    c.rect(bx + 6, by + 8, 38, 30, "woodD")
    c.rect(bx + 7, by + 9, 36, 28, "tar")
    for n, l in enumerate((30, 26, 24, 20, 28, 25, 23)):
        c.rect(bx + 10, by + 11 + n * 3 + (n > 0), 2, 2, "gold")
        c.rect(bx + 14, by + 12 + n * 3 + (n > 0), l - 4, 1, "paint")
    # House.
    hx, hy, hw, hh = 254, 58, 52, 36
    c.rect(hx + hw, hy + 3, 4, hh - 1, "shade")
    c.rect(hx, hy, hw, hh, "plaster")
    for j in range(hy + 2, hy + hh, 5):
        c.rect(hx, j, hw, 1, "plasterD")
    c.poly([(hx - 3, hy + 1), (hx + hw // 2, hy - 20), (hx + hw + 3, hy + 1)], "cap")
    for j in range(hy - 18, hy, 3):
        for i in range(hx - 4, hx + hw + 4):
            if c.get(i, j) == "cap":
                c.set(i, j, "barnD")
    c.rect(hx + 38, hy - 22, 6, 14, "stoneD")
    c.rect(hx + 37, hy - 23, 8, 2, "stone")
    c.rect(hx + 22, hy + 16, 9, 20, "woodD")
    c.rect(hx + 23, hy + 17, 7, 19, "wood")
    for wx in (hx + 6, hx + 37):
        c.rect(wx - 1, hy + 9, 11, 11, "woodD")
        c.rect(wx, hy + 10, 9, 9, "lamp" if lit else "glass")
        c.rect(wx, hy + 10, 4, 4, "lampL" if lit else "glassL")
        c.rect(wx + 4, hy + 10, 1, 9, "woodD")
        c.rect(wx, hy + 14, 9, 1, "woodD")
    # Windmill, with the sails stopped at an angle.
    mx, my = 382, 38
    c.poly([(mx - 9, my + 4), (mx + 9, my + 4), (mx + 15, 96), (mx - 15, 96)], "stone")
    for j in range(my + 8, 96, 6):
        for i in range(mx - 16, mx + 17):
            if c.get(i, j) == "stone":
                c.set(i, j, "stoneD")
    for i in range(0, 6):
        c.line(mx + 9 + i // 2, my + 4, mx + 15 - 5 + i, 95, "stoneD")
    c.poly([(mx - 11, my + 5), (mx, my - 9), (mx + 11, my + 5)], "cap")
    c.rect(mx - 4, 80, 8, 16, "woodD")
    c.rect(mx - 3, 81, 6, 15, "wood")
    c.rect(mx - 3, my + 16, 6, 7, "glass")
    for a in (28, 118, 208, 298):
        s, co = math.sin(math.radians(a)), math.cos(math.radians(a))
        ex, ey = round(mx + co * 33), round(my + s * 33)
        c.line(mx, my, ex, ey, "woodD")
        for t in range(8, 33):
            bx2, by2 = mx + co * t, my + s * t
            for k in range(1, 6):
                px, py = round(bx2 - s * k), round(by2 + co * k)
                c.set(px, py, "sailD" if (t % 5 == 0 or k == 5) else "sail")
    c.rect(mx - 1, my - 1, 3, 3, "line")
    # Fence by the road and the crates waiting at the gate.
    for x in range(124, 204, 10):
        c.rect(x, 104, 2, 9, "woodD")
    c.rect(124, 106, 80, 1, "wood")
    c.rect(124, 110, 80, 1, "woodD")
    # Clover's pasture: the shaded part is what has been used.
    px0, py0, pw, ph = 210, 110, 76, 30
    c.rect(px0, py0, pw, ph, "clover")
    for j in range(py0, py0 + ph):
        for i in range(px0, px0 + pw):
            n = random.random()
            used = False
            c.set(i, j, "soil" if used and n < .5 else "soilL" if used else "cloverL" if n < .14 else "cloverD" if n < .3 else "clover")
    for x in range(px0 - 1, px0 + pw + 1, 8):
        c.rect(x, py0 - 3, 2, 6, "woodD")
    c.rect(px0 - 1, py0 - 2, pw + 2, 1, "wood")
    c.rect(px0 - 1, py0 + ph, pw + 2, 1, "woodD")
    # The granary, with one sack per integrated change.
    gx, gy, gw, gh = 300, 110, 60, 30
    c.rect(gx, gy - 8, gw, 3, "woodD")
    c.poly([(gx - 3, gy - 3), (gx + gw // 2, gy - 12), (gx + gw + 3, gy - 3)], "roof")
    c.rect(gx, gy - 3, gw, gh + 3, "woodD")
    c.rect(gx + 2, gy, gw - 4, gh, "soilD")
    return c


def bayer(x, y):
    return [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]][y % 4][x % 4] / 16


def lum(h):
    r, g, b = rgb(h)
    return (.299 * r + .587 * g + .114 * b) / 255


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def write_png(path, rows):
    h, w = len(rows), len(rows[0])
    raw = b"".join(b"\0" + bytes(v for p in r for v in p) for r in rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def day(c):
    return [[rgb(DAY[p]) for p in r] for r in c.px]


def dusk(c):
    """A cold shadow over everything, a warm low sky, and only the lamps keep their colour."""
    sky = {"sky1": "#2e3358", "sky2": "#5b4a6e", "sky3": "#c0786a", "cloud": "#d99a7c", "cloudD": "#a8706f", "hill": "#3a3f5e", "hill2": "#2e3350"}
    out = []
    for y, r in enumerate(c.px):
        row = []
        for x, p in enumerate(r):
            if p in sky:
                row.append(rgb(sky[p]))
            elif p in ("lamp", "lampL", "gold"):
                row.append(rgb(DAY[p]))
            else:
                row.append(mix(rgb(DAY[p]), (40, 42, 78), .46))
        out.append(row)
    # Light from the lit windows falls on the ground, in dithered steps.
    for cx, cy, rad in ((265, 96, 26), (296, 96, 26)):
        for y in range(cy - 6, cy + 26):
            for x in range(cx - rad, cx + rad):
                d = math.hypot(x - cx, (y - cy) * 1.5) / rad
                if d < 1 and 0 <= x < W and y < H and (1 - d) > bayer(x, y) * .9:
                    out[y][x] = mix(out[y][x], (224, 170, 80), .30)
    return out


def rgba_png(path, c):
    rows = c.px
    h, w = len(rows), len(rows[0])
    raw = b""
    for r in rows:
        raw += b"\0"
        for p in r:
            raw += bytes((0, 0, 0, 0)) if p is None else bytes(rgb(DAY[p]) + (255,))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def single(path, draw, w, h):
    c = Canvas(w, h)
    draw(c)
    rgba_png(path, c)


if __name__ == "__main__":
    out = sys.argv[1]
    write_png(out + "/granja-dia.png", day(scene()))
    write_png(out + "/granja-atardecer.png", dusk(scene(lit=True)))
    single(out + "/boxer.png", lambda c: c.sprite(1, 1, HORSE, HORSE_K, shadow=False), 20, 15)
    single(out + "/benjamin.png", lambda c: c.sprite(1, 1, DONKEY, DONKEY_K, shadow=False), 16, 17)
    single(out + "/napoleon.png", lambda c: c.sprite(1, 1, PIG, PIG_K, shadow=False), 16, 14)
    single(out + "/caja.png", lambda c: crate(c, 1, 1), 13, 12)
    single(out + "/saco.png", lambda c: sack(c, 0, 1), 9, 7)
