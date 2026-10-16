#!/usr/bin/env python3
"""Generate large, realistic GDSII stress-test layouts (100-200 MB) for benchmarking the viewer.

Usage:
  python3 tools/make_stress_gds.py KIND [--size-mb N] [--seed S] [--gzip] [-o OUT]

KIND: flat | stdcell | photonic | mixed   (see tools/README.md)

Standard library only, deterministic for a given --seed. Reuses the record writer helpers of
make_sample_gds.py (real8, rec, strans_block, el_aref, ...). Units: 1 DBU = 1 nm
(UNITS 1e-3 user / 1e-9 m).
"""
import argparse
import gzip
import math
import os
import random
import struct
import sys
import time

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_sample_gds as g  # noqa: E402

DEFAULT_MB = {"flat": 150, "stdcell": 150, "photonic": 100, "mixed": 120}
ENDEL = struct.pack(">HBB", 4, g.ENDEL, 0)
FLUSH_AT = 1 << 24
MAXPTS = 8191
UM = 1000


# ================================================================ writer with statistics

class Cell:
    __slots__ = ("b", "p", "x", "t", "s", "a", "refs")

    def __init__(self):
        self.b = self.p = self.x = self.t = self.s = self.a = 0
        self.refs = {}


class Lib:
    def __init__(self, fobj, name):
        self.f = fobj
        self.buf = bytearray()
        self.written = 0
        self.cells = {}
        self.cur = None
        self._rh, self._ph, self._sp = {}, {}, {}
        self._pk5 = struct.Struct(">20s10i4s")
        self._pkp = struct.Struct(">34s4i4s")
        self._pkxy = struct.Struct(">ii4s")
        self._pkn = {}
        self.buf += (g.rec(g.HEADER, g.DT_I16, g.i16(600)) +
                     g.rec(g.BGNLIB, g.DT_I16, g.i16(*g.TIME, *g.TIME)) +
                     g.rec(g.LIBNAME, g.DT_ASCII, g.ascii_(name)) +
                     g.rec(g.UNITS, g.DT_REAL8, g.real8(0.001) + g.real8(1e-9)))

    @property
    def size(self):
        return self.written + len(self.buf)

    def flush(self):
        if self.buf:
            self.f.write(self.buf)
            self.written += len(self.buf)
            self.buf.clear()

    def _chk(self):
        if len(self.buf) > FLUSH_AT:
            self.f.write(self.buf)
            self.written += len(self.buf)
            self.buf.clear()

    def finish(self):
        self.buf += g.rec(g.ENDLIB, 0)
        self.flush()
        # note: self.written is now the full uncompressed size (flush adds the tail)

    def begin(self, name):
        assert self.cur is None and name not in self.cells, name
        self.cur = self.cells[name] = Cell()
        self.buf += (g.rec(g.BGNSTR, g.DT_I16, g.i16(*g.TIME, *g.TIME)) +
                     g.rec(g.STRNAME, g.DT_ASCII, g.ascii_(name)))
        self._chk()

    def end(self):
        self.buf += g.rec(g.ENDSTR, 0)
        self.cur = None
        self._chk()

    # -- shapes
    def rect(self, l, dt, x0, y0, x1, y1):
        h = self._rh.get((l, dt))
        if h is None:
            h = self._rh[(l, dt)] = (g.rec(g.BOUNDARY, 0) + g.rec(g.LAYER, g.DT_I16, g.i16(l)) +
                                     g.rec(g.DATATYPE, g.DT_I16, g.i16(dt)) +
                                     struct.pack(">HBB", 44, g.XY, g.DT_I32))
        self.buf += self._pk5.pack(h, x0, y0, x1, y0, x1, y1, x0, y1, x0, y0, ENDEL)
        self.cur.b += 1
        if len(self.buf) > FLUSH_AT:
            self._chk()

    def box(self, l, bt, x0, y0, x1, y1):
        hdr = (g.rec(g.BOX, 0) + g.rec(g.LAYER, g.DT_I16, g.i16(l)) +
               g.rec(g.BOXTYPE, g.DT_I16, g.i16(bt)) + struct.pack(">HBB", 44, g.XY, g.DT_I32))
        self.buf += self._pk5.pack(hdr, x0, y0, x1, y0, x1, y1, x0, y1, x0, y0, ENDEL)
        self.cur.x += 1

    def _xyhdr(self, nvals):
        return struct.pack(">HBB", 4 + 4 * nvals, g.XY, g.DT_I32)

    def _packn(self, n):
        s = self._pkn.get(n)
        if s is None:
            s = self._pkn[n] = struct.Struct(">%di" % n)
        return s

    def poly(self, l, dt, flat):
        """BOUNDARY from a flat [x0,y0,x1,y1,...] list; closes it if needed."""
        if flat[0] != flat[-2] or flat[1] != flat[-1]:
            flat = flat + flat[:2]
        n = len(flat)
        assert 8 <= n <= 2 * MAXPTS, n
        h = self._rh.get((l, dt, 0))
        if h is None:
            h = self._rh[(l, dt, 0)] = (g.rec(g.BOUNDARY, 0) + g.rec(g.LAYER, g.DT_I16, g.i16(l)) +
                                        g.rec(g.DATATYPE, g.DT_I16, g.i16(dt)))
        self.buf += h
        self.buf += self._xyhdr(n)
        self.buf += self._packn(n).pack(*flat)
        self.buf += ENDEL
        self.cur.b += 1
        if len(self.buf) > FLUSH_AT:
            self._chk()

    def _pathpre(self, l, dt, pt, w):
        key = (l, dt, pt, w)
        h = self._ph.get(key)
        if h is None:
            h = self._ph[key] = (g.rec(g.PATH, 0) + g.rec(g.LAYER, g.DT_I16, g.i16(l)) +
                                 g.rec(g.DATATYPE, g.DT_I16, g.i16(dt)) +
                                 g.rec(g.PATHTYPE, g.DT_I16, g.i16(pt)) +
                                 g.rec(g.WIDTH, g.DT_I32, g.i32(w)))
        return h

    def path2(self, l, dt, pt, w, x0, y0, x1, y1):
        key = (l, dt, pt, w, 2)
        h = self._ph.get(key)
        if h is None:
            h = self._ph[key] = self._pathpre(l, dt, pt, w) + struct.pack(">HBB", 20, g.XY, g.DT_I32)
        self.buf += self._pkp.pack(h, x0, y0, x1, y1, ENDEL)
        self.cur.p += 1
        if len(self.buf) > FLUSH_AT:
            self._chk()

    def path(self, l, dt, pt, w, flat):
        n = len(flat)
        assert 4 <= n <= 2 * MAXPTS
        self.buf += self._pathpre(l, dt, pt, w)
        self.buf += self._xyhdr(n)
        self.buf += self._packn(n).pack(*flat)
        self.buf += ENDEL
        self.cur.p += 1
        if len(self.buf) > FLUSH_AT:
            self._chk()

    def text(self, l, tt, x, y, s):
        self.buf += g.el_text(l, tt, x, y, s)
        self.cur.t += 1

    # -- references
    def sref(self, name, x, y, mirror=False, mag=None, angle=None):
        key = (name, mirror, mag, angle)
        pre = self._sp.get(key)
        if pre is None:
            pre = self._sp[key] = (g.rec(g.SREF, 0) + g.rec(g.SNAME, g.DT_ASCII, g.ascii_(name)) +
                                   g.strans_block(mirror, mag, angle) +
                                   struct.pack(">HBB", 12, g.XY, g.DT_I32))
        self.buf += pre
        self.buf += self._pkxy.pack(x, y, ENDEL)
        c = self.cur
        c.s += 1
        c.refs[name] = c.refs.get(name, 0) + 1
        if len(self.buf) > FLUSH_AT:
            self._chk()

    def aref(self, name, cols, rows, origin, col_step, row_step, mirror=False, mag=None, angle=None):
        assert 1 <= cols < 32768 and 1 <= rows < 32768
        self.buf += g.el_aref(name, cols, rows, origin, col_step, row_step, mirror, mag, angle)
        c = self.cur
        c.a += 1
        c.refs[name] = c.refs.get(name, 0) + cols * rows
        self._chk()

    # -- statistics
    def totals(self):
        t = dict(cells=len(self.cells), boundaries=0, paths=0, boxes=0, srefs=0, arefs=0, texts=0)
        for c in self.cells.values():
            t["boundaries"] += c.b
            t["paths"] += c.p
            t["boxes"] += c.x
            t["srefs"] += c.s
            t["arefs"] += c.a
            t["texts"] += c.t
        return t

    def flatten(self, top):
        memo = {}

        def f(name):
            r = memo.get(name)
            if r is None:
                c = self.cells[name]
                inst, sh, tx = 0, c.b + c.p + c.x, c.t
                for ch, m in c.refs.items():
                    i, s, t = f(ch)
                    inst += m * (1 + i)
                    sh += m * s
                    tx += m * t
                r = memo[name] = (inst, sh, tx)
            return r
        return f(top)


def place_oriented(lib, name, w, h, x, y, angle=0, mirror=False):
    """SREF with a 90-degree-multiple orientation so the transformed bbox lower-left is (x, y).
    Returns the transformed (width, height)."""
    c, s = {0: (1, 0), 90: (0, 1), 180: (-1, 0), 270: (0, -1)}[angle]
    xs, ys = [], []
    for px, py in ((0, 0), (w, 0), (w, h), (0, h)):
        if mirror:
            py = -py
        xs.append(c * px - s * py)
        ys.append(s * px + c * py)
    ox, oy = x - min(xs), y - min(ys)
    lib.sref(name, ox, oy, mirror, None, float(angle) if angle else None)
    return max(xs) - min(xs), max(ys) - min(ys)


def ev(x):
    """round to an even integer"""
    return int(x) & ~1


# ================================================================ KIND flat

METALS = [(68, 20), (69, 20), (70, 20), (71, 20), (72, 20)]      # met1..met5 (sky130-like)
VIAS = [(68, 44), (69, 44), (70, 44), (71, 44)]                  # via1..via4
M_PITCH = [340, 460, 680, 920, 3400]
M_WIDTH = [140, 140, 300, 300, 1600]


def build_flat(lib, rng, budget, name, extent=5000 * UM):
    stop = lib.size + budget
    lib.begin(name)
    rr, ri, lg, gauss = rng.random, rng.randrange, rng.lognormvariate, rng.gauss
    E = extent

    # clusters of shapes (denser regions) and holes (empty areas)
    nc = 90
    centers = [(rr() * E, rr() * E, min(500 * UM, max(15 * UM, lg(math.log(60 * UM), 0.8))))
               for _ in range(nc)]
    cw, acc = [], 0.0
    for _ in range(nc):
        acc += lg(0, 1.0)
        cw.append(acc)
    holes = []
    for _ in range(7):
        w, h = rng.uniform(300, 900) * UM, rng.uniform(300, 900) * UM
        x, y = rr() * (E - w), rr() * (E - h)
        holes.append((x, y, x + w, y + h))

    def in_hole(x, y):
        for a, b, c, d in holes:
            if a <= x <= c and b <= y <= d:
                return True
        return False

    # power straps: met4 vertical / met5 horizontal, 3..50 um wide, cut into segments
    for lay, vertical in ((METALS[3], True), (METALS[4], False)):
        pos = 50 * UM
        while pos < E - 50 * UM:
            w = ev(rng.choice([3, 5, 10, 20, 50]) * UM)
            a = rng.randrange(0, 300 * UM)
            while a < E:
                ln = rng.randrange(500 * UM, 1500 * UM)
                b = min(a + ln, E)
                if vertical:
                    lib.rect(lay[0], lay[1], pos, a, pos + w, b)
                else:
                    lib.rect(lay[0], lay[1], a, pos, b, pos + w)
                a = b + rng.randrange(5 * UM, 60 * UM)
            pos += rng.choice([100, 150, 200]) * UM

    ntext = 0
    lw = (35, 25, 20, 12, 8)
    lcum = [sum(lw[:i + 1]) for i in range(5)]
    wide = (140, 170, 200, 300, 500, 1000, 2000)
    visz = (50, 60, 100, 150, 170, 200)
    B = 2000
    while lib.size < stop:
        cidx = rng.choices(range(nc), cum_weights=cw, k=B)
        for ci in cidx:
            if rr() < 0.08:
                x, y = rr() * E, rr() * E
            else:
                cx, cy, s = centers[ci]
                x, y = gauss(cx, s), gauss(cy, s)
            if not (0 <= x < E - 100 * UM and 0 <= y < E - 100 * UM) or in_hole(x, y):
                continue
            x, y = int(x), int(y)
            r = rr()
            if r < 0.40:      # metal rectangle
                k = rng.choices(range(5), weights=lw)[0]
                horiz = (k % 2 == 0) == (rr() < 0.85)
                ln = ev(min(60 * UM, max(200, lg(7.3, 1.0))))
                w = ev(M_WIDTH[k] * (1 if rr() < 0.7 else rng.choice((1.5, 2, 3, 6))))
                lay = METALS[k]
                if horiz:
                    y -= y % M_PITCH[k]
                    lib.rect(lay[0], lay[1], x, y, x + ln, y + w)
                else:
                    x -= x % M_PITCH[k]
                    lib.rect(lay[0], lay[1], x, y, x + w, y + ln)
            elif r < 0.60:    # via
                v = VIAS[ri(4)]
                s = ev(rng.choice(visz))
                lib.rect(v[0], v[1], x, y, x + s, y + s)
            elif r < 0.80:    # 2-point path
                k = rng.choices(range(5), weights=lw)[0]
                horiz = (k % 2 == 0) == (rr() < 0.85)
                ln = ev(min(200 * UM, max(500, lg(8.5, 1.1))))
                w = ev(rng.choice(wide))
                pt = 0 if rr() < 0.5 else 2
                lay = METALS[k]
                if horiz:
                    y -= y % M_PITCH[k]
                    lib.path2(lay[0], lay[1], pt, w, x, y, x + ln, y)
                else:
                    x -= x % M_PITCH[k]
                    lib.path2(lay[0], lay[1], pt, w, x, y, x, y + ln)
            elif r < 0.835:   # non-rectangular polygon
                k = rng.choices(range(5), weights=lw)[0]
                lay = METALS[k]
                w, h = ev(rng.randrange(500, 10000)), ev(rng.randrange(500, 10000))
                if rr() < 0.5:
                    w2, h1 = ev(w * rng.uniform(0.3, 0.7)), ev(h * rng.uniform(0.3, 0.7))
                    lib.poly(lay[0], lay[1], [x, y, x + w, y, x + w, y + h1, x + w2, y + h1,
                                              x + w2, y + h, x, y + h])
                else:
                    c = ev(min(w, h) / 3)
                    x1, y1 = x + w, y + h
                    lib.poly(lay[0], lay[1], [x + c, y, x1 - c, y, x1, y + c, x1, y1 - c,
                                              x1 - c, y1, x + c, y1, x, y1 - c, x, y + c])
            elif r < 0.845:   # BOX record
                k = ri(5)
                w, h = ev(rng.randrange(200, 5000)), ev(rng.randrange(200, 5000))
                lib.box(METALS[k][0], 0, x, y, x + w, y + h)
            else:             # remaining ~15%: small contact/pad squares on metal
                k = rng.choices(range(5), weights=lw)[0]
                s = ev(rng.choice((140, 170, 290, 500, 1000)))
                lib.rect(METALS[k][0], METALS[k][1], x, y, x + s, y + s)
            if ntext < 300 and rr() < 0.0002:
                lib.text(68, 5, x, y, "N%d" % ntext)
                ntext += 1
        if lib.size >= stop:
            break
    lib.end()
    return (0, 0, E, E)


# ================================================================ KIND stdcell

U = 460
ROWH = 2720
FUNCS = [("inv", 2), ("buf", 3), ("nand2", 3), ("nand3", 4), ("nand4", 5), ("nor2", 3), ("nor3", 4),
         ("nor4", 5), ("and2", 4), ("and3", 5), ("and4", 6), ("or2", 4), ("or3", 5), ("or4", 6),
         ("xor2", 7), ("xnor2", 7), ("mux2", 7), ("mux4", 12), ("a21o", 5), ("a22o", 6), ("a31o", 6),
         ("o21a", 5), ("o22a", 6), ("o31a", 6), ("a21oi", 4), ("o21ai", 4), ("maj3", 8), ("ha", 9),
         ("fa", 12), ("dfxtp", 14), ("dfrtp", 16), ("dlxtp", 11), ("clkbuf", 3), ("clkinv", 3),
         ("dlygate", 6), ("ebufn", 7)]
DRIVE_W = {1: 0, 2: 1, 4: 2, 8: 4, 16: 6}


class StdLib:
    pass


def std_body(lib, rng, w, with_tap=False):
    """Shapes of one std cell of w sites (width w*0.46 um, height 2.72 um)."""
    W = w * U
    rr = rng.random
    lib.rect(64, 20, 0, 1480, W, ROWH)                      # nwell (upper half)
    lib.rect(68, 20, 0, -240, W, 240)                       # VSS rail met1
    lib.rect(68, 20, 0, ROWH - 240, W, ROWH + 240)          # VDD rail met1
    lib.rect(65, 20, 130, 260, W - 130, 1140)               # n diff
    lib.rect(65, 20, 130, 1740, W - 130, 2460)              # p diff
    for k in range(1, w):
        xc = k * U
        lib.rect(66, 20, xc - 75, 120, xc + 75, ROWH - 120)  # poly gate
    for j in range(w):
        xc = j * U + U // 2
        lib.rect(66, 44, xc - 85, 615, xc + 85, 785)         # licon n
        lib.rect(66, 44, xc - 85, 1935, xc + 85, 2105)       # licon p
        lib.rect(67, 20, xc - 85, 530, xc + 85, 1100)        # li1 n
        lib.rect(67, 20, xc - 85, 1780, xc + 85, 2190)       # li1 p
        r = rr()
        if r < 0.55:
            lib.rect(67, 44, xc - 85, 1275, xc + 85, 1445)   # mcon
        if r < 0.3:
            lib.rect(68, 20, xc - 145, 1235, xc + 145, 1485)  # met1 pin stub
        if with_tap and j == 0:
            lib.rect(65, 44, xc - 150, 400, xc + 150, 700)   # tap diff
    lib.text(68, 5, W // 2, ROWH // 2, "X")
    lib.rect(235, 4, 0, 0, W, ROWH)                          # prBoundary


def make_stdlib(lib, rng, prefix):
    std = StdLib()
    # via cells joining met(k) and met(k+1)
    std.vias = []
    for k, (sz, pad) in enumerate(((150, 290), (200, 340), (200, 340), (800, 1000))):
        nm = "%svia%d" % (prefix, k + 1)
        lib.begin(nm)
        lib.rect(68 + k, 44, -sz // 2, -sz // 2, sz // 2, sz // 2)
        lib.rect(68 + k, 20, -pad // 2, -pad // 2, pad // 2, pad // 2)
        lib.rect(69 + k, 20, -pad // 2, -pad // 2, pad // 2, pad // 2)
        lib.end()
        std.vias.append(nm)
    names, widths, nshapes = [], [], []
    combos = [(f, bw, d) for d in (1, 2, 4, 8) for f, bw in FUNCS]
    combos += [(f, bw, 16) for f, bw in FUNCS[:6]]
    rng.shuffle(combos)
    for f, bw, d in combos:
        w = max(3, min(16, bw + DRIVE_W[d]))
        nm = "%ssc_%s_%d" % (prefix, f, d)
        lib.begin(nm)
        std_body(lib, rng, w)
        nshapes.append(lib.cur.b)
        lib.end()
        names.append(nm)
        widths.append(w * U)
    std.names, std.widths, std.nshapes = names, widths, nshapes
    # small cells weigh more (like real netlists): weight ~ 1/w^1.3
    std.weights = [1.0 / (w // U) ** 1.3 for w in widths]
    wsum = sum(std.weights)
    std.avgw = sum(w * wt for w, wt in zip(widths, std.weights)) / wsum
    std.fillers = []
    for w in (8, 4, 2, 1):
        nm = "%sfill_%d" % (prefix, w)
        lib.begin(nm)
        lib.rect(64, 20, 0, 1480, w * U, ROWH)
        lib.rect(68, 20, 0, -240, w * U, 240)
        lib.rect(68, 20, 0, ROWH - 240, w * U, ROWH + 240)
        lib.rect(235, 4, 0, 0, w * U, ROWH)
        lib.end()
        std.fillers.append((nm, w * U))
    std.tap = "%stap_1" % prefix
    lib.begin(std.tap)
    std_body(lib, rng, 1, with_tap=True)
    lib.end()
    # sram pieces
    std.bit = "%ssram_bitcell" % prefix
    lib.begin(std.bit)
    lib.rect(64, 20, 0, 800, 1200, 1600)
    for xl in (150, 700):
        lib.rect(65, 20, xl, 150, xl + 350, 700)
        lib.rect(65, 20, xl, 900, xl + 350, 1450)
        lib.rect(66, 20, xl + 150, 80, xl + 230, 1520)
    for xl in (80, 540, 990):
        for yl in (300, 1100):
            lib.rect(66, 44, xl, yl, xl + 170, yl + 170)
            lib.rect(67, 20, xl - 20, yl - 60, xl + 190, yl + 230)
    lib.rect(69, 20, 100, 0, 240, 1600)
    lib.rect(69, 20, 960, 0, 1100, 1600)
    lib.rect(68, 20, 0, 700, 1200, 900)
    lib.end()
    std.wl = "%ssram_wldrv" % prefix
    lib.begin(std.wl)
    for j in range(6):
        lib.rect(66, 20, 200 + j * 400, 100, 280 + j * 400, 1500)
        lib.rect(67, 20, 100 + j * 400, 300, 380 + j * 400, 600)
    lib.rect(68, 20, 0, 700, 3000, 900)
    lib.end()
    std.sa = "%ssram_sa" % prefix
    lib.begin(std.sa)
    for j in range(20):
        lib.rect(65, 20, 100 + j * 220, 200 + (j % 3) * 300, 250 + j * 220, 900 + (j % 4) * 500)
        lib.rect(66, 20, 150 + j * 200, 100, 230 + j * 200, 5800)
    lib.rect(68, 20, 0, 3000, 4800, 3300)
    lib.end()
    return std


def place_rows(lib, rng, std, x0, y0, W, max_bytes):
    """Fill rows [x0, x0+W] starting at y0 with std cells until max_bytes were written.
    Even rows: N or FN (mirror + 180 deg); odd rows: FS (mirror) or S (180 deg), so rows abut.
    Returns (placements, rows)."""
    start = lib.size
    n = len(std.names)
    rr = rng.random
    names, widths = std.names, std.widths
    cnt = r = 0
    sref = lib.sref
    while lib.size - start < max_bytes:
        y = y0 + r * ROWH
        odd = r & 1
        x = 0
        since_tap = rng.randrange(40 * U)
        draws = rng.choices(range(n), weights=std.weights, k=W // U // 2 + 20)
        di = 0

        def put(nm, w, x):
            if odd:
                if rr() < 0.2:
                    sref(nm, x0 + x + w, y + ROWH, False, None, 180.0)   # S
                else:
                    sref(nm, x0 + x, y + ROWH, True)                      # FS
            else:
                if rr() < 0.2:
                    sref(nm, x0 + x + w, y, True, None, 180.0)           # FN
                else:
                    sref(nm, x0 + x, y)                                   # N
        while True:
            if since_tap >= 40 * U and x + U <= W:
                put(std.tap, U, x)
                x += U
                since_tap = 0
                cnt += 1
            idx = draws[di]
            di += 1
            w = widths[idx]
            if x + w > W:
                break
            put(names[idx], w, x)
            x += w
            since_tap += w
            cnt += 1
        for nm, fw in std.fillers:
            while x + fw <= W:
                put(nm, fw, x)
                x += fw
                cnt += 1
        r += 1
    return cnt, r


def route_fill(lib, rng, std, bbox, budget):
    """Random met1..met5 routing (2-point paths and rects, joined by via SREFs) until budget bytes."""
    stop = lib.size + budget
    x0, y0, x1, y1 = bbox
    W, H = x1 - x0, y1 - y0
    rr, ri, lg = rng.random, rng.randrange, rng.lognormvariate
    scale = (1, 1, 2, 4, 8)
    vias = std.vias
    kw = (4, 4, 3, 1)
    while lib.size < stop:
        for _ in range(500):
            k = rng.choices(range(4), weights=kw)[0]
            # layers k (lower) and k+1; even index = horizontal
            hk, vk = (k, k + 1) if k % 2 == 0 else (k + 1, k)
            jx = x0 + ri(W)
            jx -= jx % M_PITCH[vk]
            jy = y0 + ri(H)
            jy -= jy % M_PITCH[hk]
            for layer, horiz in ((hk, True), (vk, False)):
                lay = METALS[layer]
                a = ev(min(150 * UM, max(300, lg(8.0, 0.9) * scale[layer])))
                b = ev(min(150 * UM, max(300, lg(8.0, 0.9) * scale[layer])))
                w = M_WIDTH[layer]
                if rr() < 0.65:
                    pt = 0 if rr() < 0.5 else 2
                    if horiz:
                        lib.path2(lay[0], lay[1], pt, w, jx - a, jy, jx + b, jy)
                    else:
                        lib.path2(lay[0], lay[1], pt, w, jx, jy - a, jx, jy + b)
                else:
                    if horiz:
                        lib.rect(lay[0], lay[1], jx - a, jy - w // 2, jx + b, jy + w // 2)
                    else:
                        lib.rect(lay[0], lay[1], jx - w // 2, jy - a, jx + w // 2, jy + b)
            lib.sref(vias[k], jx, jy)
        if lib.size >= stop:
            break


def build_stdcell(lib, rng, budget, prefix, topname):
    start = lib.size
    std = make_stdlib(lib, rng, prefix)
    avg_place = 31.0                                    # bytes per placement (approx.)
    H = ROWH

    def region_width(nbytes):
        n = nbytes / avg_place
        w = math.sqrt(n * (std.avgw / 1000.0) * (H / 1000.0)) * 1000.0
        return max(40 * U, int(w // U) * U)

    # ---- SRAM macro: top -> sram -> (bitcell AREF 512x512, periphery, std cells)
    sram = "%ssram_512x512" % prefix
    BW, BH = 1200, 1600
    ax, ay = 4000, 8000
    arr_w, arr_h = 512 * BW, 512 * BH
    lib.begin(sram)
    lib.aref(std.bit, 512, 512, (ax, ay), (BW, 0), (0, BH))
    for r in range(512):
        lib.sref(std.wl, 500, ay + r * BH + (BH if r & 1 else 0), bool(r & 1))
    for c in range(128):
        lib.sref(std.sa, ax + c * 4 * BW, 500)
    ctl_w = 60000
    cx0 = ax + arr_w + 2000
    place_rows(lib, rng, std, cx0, ay, ctl_w, max(20000, budget // 800))
    mw, mh = cx0 + ctl_w + 2000, ay + arr_h + 20000
    lib.rect(235, 4, 0, 0, mw, mh)
    for lay in (71, 72):                                 # power ring
        lib.rect(lay, 20, 1000, 1000, mw - 1000, 3000)
        lib.rect(lay, 20, 1000, mh - 3000, mw - 1000, mh - 1000)
        lib.rect(lay, 20, 1000, 1000, 3000, mh - 1000)
        lib.rect(lay, 20, mw - 3000, 1000, mw - 1000, mh - 1000)
    for i in range(32):
        lib.text(68, 5, 100, ay + i * 16 * BH, "A%d" % i)
        lib.text(69, 5, ax + i * 16 * BW, 100, "D%d" % i)
    lib.end()

    # ---- hard blocks: top -> blk -> std cells
    blocks = []
    nblk = 4
    for b in range(nblk):
        nm = "%sblk_%d" % (prefix, b)
        pb, rb = budget * 0.02, budget * 0.025
        Wb = region_width(pb)
        lib.begin(nm)
        cnt, rows = place_rows(lib, rng, std, 0, 0, Wb, pb)
        Hb = rows * H
        route_fill(lib, rng, std, (0, 0, Wb, Hb), rb)
        lib.rect(235, 4, 0, 0, Wb, Hb)
        lib.text(68, 5, Wb // 2, Hb // 2, "BLK%d" % b)
        lib.end()
        blocks.append((nm, Wb, Hb))

    # ---- top
    lib.begin(topname)
    Wc = region_width(budget * 0.22)
    cnt, rows = place_rows(lib, rng, std, 0, 0, Wc, budget * 0.22)
    Hc = rows * H
    margin = 50 * UM
    # macros to the right of the core, rotated 0/90/180/270 (mirrored for two of them)
    my = 0
    right = Wc
    for angle, mir in ((0, False), (90, False), (180, True), (270, False)):
        w, h = place_oriented(lib, sram, mw, mh, Wc + margin, my, angle, mir)
        right = max(right, Wc + margin + w)
        my += h + margin
    # blocks above the core, several instances each with various orientations
    bx, by, rowh = 0, Hc + margin, 0
    orients = [(0, False), (90, False), (180, False), (270, True), (0, True), (180, True)]
    k = 0
    for rep in range(3):
        for nm, Wb, Hb in blocks:
            angle, mir = orients[k % len(orients)]
            k += 1
            wdt = Wb if angle in (0, 180) else Hb
            if bx + wdt > right:
                bx, by, rowh = 0, by + rowh + margin, 0
            w, h = place_oriented(lib, nm, Wb, Hb, bx, by, angle, mir)
            bx += w + margin
            rowh = max(rowh, h)
    top_h = max(Hc, my, by + rowh)
    for i in range(100):
        lib.text(68, 5, rng.randrange(Wc), rng.randrange(max(1, Hc)), "pin%d" % i)
    route_fill(lib, rng, std, (0, 0, Wc, Hc), max(0, start + budget - lib.size))
    lib.end()
    return (0, 0, right, top_h)


# ================================================================ KIND photonic

WG = (1, 0)
ETCH = (2, 0)
HEAT = (10, 0)
MET = (11, 0)
LABEL = (100, 0)
DEVREC = (99, 0)


def interleave(xs, ys):
    flat = [0] * (2 * len(xs))
    flat[0::2] = xs
    flat[1::2] = ys
    return flat


def arc_xy(cx, cy, r, a0, a1, n):
    """n+1 points on an arc; returns (xs, ys) int lists"""
    da = (a1 - a0) / n
    cs = [math.cos(a0 + i * da) for i in range(n + 1)]
    sn = [math.sin(a0 + i * da) for i in range(n + 1)]
    return [int(cx + r * c + 0.5) for c in cs], [int(cy + r * s + 0.5) for s in sn]


def arc_poly(cx, cy, r_in, r_out, a0, a1, n):
    ox, oy = arc_xy(cx, cy, r_out, a0, a1, n)
    ix, iy = arc_xy(cx, cy, r_in, a0, a1, n)
    ix.reverse()
    iy.reverse()
    return interleave(ox + ix, oy + iy)


def curve_poly(xs, ys, w):
    """polygon of a centerline polyline with constant width w"""
    n = len(xs)
    lx, ly, rx, ry = [], [], [], []
    h = w / 2.0
    for i in range(n):
        a, b = max(0, i - 1), min(n - 1, i + 1)
        dx, dy = xs[b] - xs[a], ys[b] - ys[a]
        d = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / d * h, dx / d * h
        lx.append(int(xs[i] + nx + 0.5))
        ly.append(int(ys[i] + ny + 0.5))
        rx.append(int(xs[i] - nx + 0.5))
        ry.append(int(ys[i] - ny + 0.5))
    rx.reverse()
    ry.reverse()
    return interleave(lx + rx, ly + ry)


def dev_ring(lib, rng, name, idx):
    R = int(rng.uniform(5, 40) * UM)
    ww = ev(rng.choice((400, 450, 500)))
    gap = ev(rng.choice((150, 200, 250, 300)))
    n = rng.randint(100, 1000)          # polygon has 2(n+1)+1 = 205..2005 vertices
    lib.begin(name)
    lib.poly(WG[0], WG[1], arc_poly(0, 0, R - ww // 2, R + ww // 2, 0, 2 * math.pi, n))
    yb = -(R + ww + gap)
    lib.path2(WG[0], WG[1], 0, ww, -(R + 10 * UM), yb, R + 10 * UM, yb)       # bus, flat ends
    lib.path2(WG[0], WG[1], 1, ww, -(R + 10 * UM), -yb, R + 10 * UM, -yb)     # drop bus, round ends
    lib.poly(HEAT[0], HEAT[1], arc_poly(0, 0, R + ww // 2 + 500, R + ww // 2 + 2500,
                                        math.radians(20), math.radians(340), max(20, n // 2)))
    lib.rect(MET[0], MET[1], R + 3000, -2000, R + 13000, 2000)
    lib.rect(MET[0], MET[1], -(R + 13000), -2000, -(R + 3000), 2000)
    lib.text(LABEL[0], LABEL[1], 0, 0, "RING_%d_R%d" % (idx, R // 100))
    e = R + 14 * UM
    lib.rect(DEVREC[0], DEVREC[1], -e, -e, e, e)
    lib.end()


def dev_grating(lib, rng, name, idx):
    m = rng.randint(20, 60)
    pitch = ev(rng.randint(600, 700))
    r0 = 20 * UM
    half = math.radians(17)
    na = rng.randint(24, 48)
    lib.begin(name)
    for k in range(m):
        ri_ = r0 + k * pitch
        lib.poly(ETCH[0], ETCH[1], arc_poly(0, 0, ri_, ri_ + pitch // 2, -half, half, na))
    ox, oy = arc_xy(0, 0, r0, -half, half, na)
    lib.poly(WG[0], WG[1], [0, 0] + interleave(ox, oy))
    lib.path2(WG[0], WG[1], 0, 500, -30 * UM, 0, 0, 0)
    lib.text(LABEL[0], LABEL[1], 0, 3000, "GC_%d" % idx)
    lib.rect(DEVREC[0], DEVREC[1], -1000, -(r0 + m * pitch) // 2, r0 + m * pitch, (r0 + m * pitch) // 2)
    lib.end()


def dev_spiral(lib, rng, name, idx):
    turns = rng.randint(3, 8)
    pitch = rng.randint(3, 6) * UM
    r0 = 10 * UM
    n = rng.randint(600, 1800)
    ww = 500
    th = [turns * 2 * math.pi * i / n for i in range(n + 1)]
    b = pitch / (2 * math.pi)
    xs = [int((r0 + b * t) * math.cos(t)) for t in th]
    ys = [int((r0 + b * t) * math.sin(t)) for t in th]
    lib.begin(name)
    lib.poly(WG[0], WG[1], curve_poly(xs, ys, ww))
    lib.path(WG[0], WG[1], 1, ww, [xs[0], ys[0], xs[0] + 8 * UM, ys[0], xs[0] + 8 * UM, ys[0] + 3 * UM])
    lib.text(LABEL[0], LABEL[1], 0, 0, "SPIRAL_%d_T%d" % (idx, turns))
    e = r0 + turns * pitch + 3 * UM
    lib.rect(DEVREC[0], DEVREC[1], -e, -e, e, e)
    lib.end()


def dev_mzi(lib, rng, name, idx):
    L = rng.randint(60, 120) * UM
    dh = rng.randint(10, 25) * UM
    sl = rng.randint(20, 40) * UM
    n = rng.randint(100, 400)
    ww = 500
    lib.begin(name)
    t = [i / n for i in range(n + 1)]
    for sgn in (1, -1):
        for x0, flip in ((-L // 2, 1), (L // 2, -1)):
            xs = [int(x0 + flip * sl * ti) for ti in t]
            ys = [int(sgn * dh * (1 - math.cos(math.pi * ti)) / 2) for ti in t]
            lib.poly(WG[0], WG[1], curve_poly(xs, ys, ww))
        lib.path2(WG[0], WG[1], 0, ww, -L // 2 + sl, sgn * dh, L // 2 - sl, sgn * dh)
    lib.path2(WG[0], WG[1], 1, ww, -L // 2 - 15 * UM, 0, -L // 2, 0)
    lib.path2(WG[0], WG[1], 1, ww, L // 2, 0, L // 2 + 15 * UM, 0)
    lib.rect(HEAT[0], HEAT[1], -L // 4, dh - 1000, L // 4, dh + 1000)
    lib.text(LABEL[0], LABEL[1], 0, dh + 4000, "MZI_%d" % idx)
    lib.rect(DEVREC[0], DEVREC[1], -L // 2 - 15 * UM, -dh - 3000, L // 2 + 15 * UM, dh + 3000)
    lib.end()


def build_photonic(lib, rng, budget, prefix, topname):
    start = lib.size
    stop = start + budget
    rr = rng.random
    kinds = [dev_ring, dev_ring, dev_grating, dev_spiral, dev_mzi, dev_ring]
    devs = []
    i = 0
    while lib.size < start + budget * 0.55:
        k = kinds[i % len(kinds)]
        nm = "%s%s_%d" % (prefix, k.__name__[4:], i)
        k(lib, rng, nm, i)
        devs.append(nm)
        i += 1
    rings = [d for d in devs if "_ring_" in d or d.startswith(prefix + "ring_")]

    lib.begin(topname)
    pitch = 240 * UM
    gx = max(32, int(math.ceil(math.sqrt(len(devs)))))
    gy = max(26, -(-len(devs) // gx))
    order = list(range(len(devs)))
    rng.shuffle(order)
    cnt = 0
    angles = [0] * 6 + [90, 180, 270] * 2 + [30, 45, 60, 15, 120, 135, 210, 300, 330]
    mags = (0.5, 0.75, 1.25, 1.5, 2.0)
    for ix in range(gx):
        for iy in range(gy):
            nm = devs[order[cnt]] if cnt < len(order) else devs[rng.randrange(len(devs))]
            cnt += 1
            ang = rng.choice(angles)
            mag = rng.choice(mags) if rr() < 0.03 else None
            x = ix * pitch + int(rng.uniform(-10, 10) * UM)
            y = iy * pitch + int(rng.uniform(-10, 10) * UM)
            lib.sref(nm, x, y, rr() < 0.1, mag, float(ang) if ang else None)
    # arrays of rings, some at 0/90 degrees, one at 45 and one at 30 degrees
    by = -3 * pitch
    for j, ang in enumerate((0, 0, 90, 0, 45, 30)):
        nm = rings[rng.randrange(len(rings))]
        c, s = math.cos(math.radians(ang)), math.sin(math.radians(ang))
        cs = (int(pitch * c), int(pitch * s))
        rs = (int(-pitch * s), int(pitch * c))
        lib.aref(nm, 8, 6, (j * 9 * pitch if ang in (0, 90) else j * 9 * pitch + 2 * pitch, by),
                 cs, rs, False, None, float(ang) if ang else None)
    # routed waveguides: straights (paths, flat and round ends) + bends (polygons)
    E = (gx * pitch, gy * pitch)
    wi = 0
    ww = 500
    while lib.size < stop:
        x, y = rr() * E[0], rr() * E[1]
        h = rng.randrange(4)
        x, y = int(x), int(y)
        lib.text(LABEL[0], LABEL[1], x, y, "WG%d" % wi)
        for seg in range(rng.randint(3, 8)):
            ln = int(rng.uniform(20, 400) * UM)
            dx, dy = ((1, 0), (0, 1), (-1, 0), (0, -1))[h]
            lib.path2(WG[0], WG[1], rng.choice((0, 1)), ww, x, y, x + dx * ln, y + dy * ln)
            x, y = x + dx * ln, y + dy * ln
            R = int(rng.uniform(5, 60) * UM)
            n = rng.randint(100, 1000)
            left = rr() < 0.5
            ang_h = h * math.pi / 2
            if left:
                cx, cy = x - dy * R, y + dx * R
                a0 = ang_h - math.pi / 2
                a1 = a0 + math.pi / 2
                h2 = (h + 1) % 4
            else:
                cx, cy = x + dy * R, y - dx * R
                a0 = ang_h + math.pi / 2
                a1 = a0 - math.pi / 2
                h2 = (h - 1) % 4
            lib.poly(WG[0], WG[1], arc_poly(cx, cy, R - ww // 2, R + ww // 2, a0, a1, n))
            ex, ey = arc_xy(cx, cy, R, a1, a1, 1)
            x, y = ex[0], ey[0]
            h = h2
        wi += 1
        if lib.size >= stop:
            break
    lib.end()
    return (-3 * pitch, by, gx * pitch, gy * pitch)


# ================================================================ KIND mixed

def build_mixed(lib, rng, budget):
    third = budget / 3.0
    bf = build_flat(lib, rng, third, "FLAT_REGION", extent=2000 * UM)
    bs = build_stdcell(lib, rng, third, "s_", "STD_BLOCK")
    bp = build_photonic(lib, rng, third, "p_", "PHOT_BLOCK")
    lib.begin("TOP")
    gap = 100 * UM
    x = 0
    for nm, bb in (("FLAT_REGION", bf), ("STD_BLOCK", bs), ("PHOT_BLOCK", bp)):
        lib.sref(nm, x - bb[0], -bb[1])
        lib.text(100, 0, x, -50 * UM, nm)
        x += bb[2] - bb[0] + gap
    lib.end()
    return "TOP"


# ================================================================ main

def main():
    ap = argparse.ArgumentParser(description="Generate large GDSII stress-test layouts.")
    ap.add_argument("kind", choices=["flat", "stdcell", "photonic", "mixed"])
    ap.add_argument("--size-mb", type=float, help="approx. uncompressed size (default per kind)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--gzip", action="store_true", help="write OUT gzip-compressed")
    ap.add_argument("-o", "--out", help="output file (default stress_KIND.gds[.gz])")
    a = ap.parse_args()

    mb = a.size_mb if a.size_mb else DEFAULT_MB[a.kind]
    budget = int(mb * 1024 * 1024)
    out = a.out or ("stress_%s.gds%s" % (a.kind, ".gz" if a.gzip else ""))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    rng = random.Random(a.seed)

    t0 = time.time()
    raw = open(out, "wb")
    fobj = (gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=6)
            if a.gzip else raw)
    lib = Lib(fobj, "STRESS_" + a.kind.upper())
    if a.kind == "flat":
        build_flat(lib, rng, budget, "TOP")
        top = "TOP"
    elif a.kind == "stdcell":
        build_stdcell(lib, rng, budget, "", "TOP")
        top = "TOP"
    elif a.kind == "photonic":
        build_photonic(lib, rng, budget, "", "TOP")
        top = "TOP"
    else:
        top = build_mixed(lib, rng, budget)
    lib.finish()
    fobj.close()
    if fobj is not raw:
        raw.close()
    dt = time.time() - t0

    tot = lib.totals()
    inst, shapes, texts = lib.flatten(top)
    print("wrote %s" % out)
    print("  uncompressed size : %.1f MB (%d bytes; target %.0f MB)" % (lib.written / 1048576.0, lib.written, mb))
    if a.gzip:
        print("  file size (gzip)  : %.1f MB" % (os.path.getsize(out) / 1048576.0))
    print("  cells             : %d" % tot["cells"])
    print("  boundaries        : %d" % tot["boundaries"])
    print("  paths             : %d" % tot["paths"])
    print("  boxes             : %d" % tot["boxes"])
    print("  srefs / arefs     : %d / %d" % (tot["srefs"], tot["arefs"]))
    print("  texts             : %d" % tot["texts"])
    print("  flattened instances (AREF = cols*rows, nested): %d" % inst)
    print("  flattened shapes (boundary+path+box)          : %d" % shapes)
    print("  flattened texts                               : %d" % texts)
    print("  generation time   : %.1f s" % dt)


if __name__ == "__main__":
    main()
