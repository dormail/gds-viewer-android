#!/usr/bin/env python3
"""Generate a small but realistic GDSII sample layout for testing the GDS viewer.

Usage:
  python3 tools/make_sample_gds.py [OUT]           # default: app/src/main/assets/sample.gds
  python3 tools/make_sample_gds.py --big N [OUT]   # stress file, TOP holds an N x N AREF of INV
                                                   # default output: ./sample_big.gds

Standard library only. All coordinates in the cells are in database units (1 DBU = 1 nm).
"""
import argparse
import math
import os
import struct
import sys

# GDSII record types
HEADER, BGNLIB, LIBNAME, UNITS, ENDLIB = 0x00, 0x01, 0x02, 0x03, 0x04
BGNSTR, STRNAME, ENDSTR = 0x05, 0x06, 0x07
BOUNDARY, PATH, SREF, AREF, TEXT = 0x08, 0x09, 0x0A, 0x0B, 0x0C
LAYER, DATATYPE, WIDTH, XY, ENDEL = 0x0D, 0x0E, 0x0F, 0x10, 0x11
SNAME, COLROW, TEXTTYPE, STRING = 0x12, 0x13, 0x16, 0x19
STRANS, MAG, ANGLE, PATHTYPE = 0x1A, 0x1B, 0x1C, 0x21
BOX, BOXTYPE, BGNEXTN, ENDEXTN = 0x2D, 0x2E, 0x30, 0x31

# GDSII data types
DT_NONE, DT_BIT, DT_I16, DT_I32, DT_REAL8, DT_ASCII = 0, 1, 2, 3, 5, 6

TIME = (2026, 10, 16, 0, 0, 0)  # fixed timestamp so output is reproducible


# ---------------------------------------------------------------- encoding

def real8(v):
    """GDSII 8-byte excess-64 base-16 real."""
    if v == 0:
        return bytes(8)
    sign = 0x80 if v < 0 else 0
    x = abs(v)
    exp = 64
    while x >= 1:
        x /= 16
        exp += 1
    while x < 1 / 16:
        x *= 16
        exp -= 1
    mant = round(x * 2 ** 56)
    if mant >= 2 ** 56:
        mant //= 16
        exp += 1
    return bytes([sign | exp]) + mant.to_bytes(7, "big")


def rec(rtype, dtype, payload=b""):
    """One GDSII record; ASCII payloads are padded with a NUL to an even length."""
    if len(payload) % 2:
        payload += b"\0"
    length = 4 + len(payload)
    if length > 0xFFFF:
        raise ValueError("record too long")
    return struct.pack(">HBB", length, rtype, dtype) + payload


def i16(*vals):
    return struct.pack(">%dh" % len(vals), *vals)


def i32(*vals):
    return struct.pack(">%di" % len(vals), *vals)


def ascii_(s):
    return s.encode("ascii")


def xy_rec(pts):
    flat = []
    for x, y in pts:
        flat += [x, y]
    return rec(XY, DT_I32, i32(*flat))


# ---------------------------------------------------------------- elements

def strans_block(mirror=False, mag=None, angle=None):
    """STRANS/MAG/ANGLE, emitted only when non-default (STRANS first)."""
    if not (mirror or mag is not None or angle is not None):
        return b""
    out = rec(STRANS, DT_BIT, struct.pack(">H", 0x8000 if mirror else 0))
    if mag is not None:
        out += rec(MAG, DT_REAL8, real8(mag))
    if angle is not None:
        out += rec(ANGLE, DT_REAL8, real8(angle))
    return out


def el_boundary(layer, dt, pts):
    pts = list(pts)
    if pts[0] != pts[-1]:
        pts.append(pts[0])  # closed: repeat first point
    return (rec(BOUNDARY, 0) + rec(LAYER, DT_I16, i16(layer)) +
            rec(DATATYPE, DT_I16, i16(dt)) + xy_rec(pts) + rec(ENDEL, 0))


def el_box(layer, boxtype, pts):
    pts = list(pts)
    if pts[0] != pts[-1]:
        pts.append(pts[0])
    return (rec(BOX, 0) + rec(LAYER, DT_I16, i16(layer)) +
            rec(BOXTYPE, DT_I16, i16(boxtype)) + xy_rec(pts) + rec(ENDEL, 0))


def el_path(layer, dt, pts, width, pathtype=0, ext=None):
    out = (rec(PATH, 0) + rec(LAYER, DT_I16, i16(layer)) +
           rec(DATATYPE, DT_I16, i16(dt)) + rec(PATHTYPE, DT_I16, i16(pathtype)) +
           rec(WIDTH, DT_I32, i32(width)))
    if pathtype == 4 and ext is not None:
        out += rec(BGNEXTN, DT_I32, i32(ext[0])) + rec(ENDEXTN, DT_I32, i32(ext[1]))
    return out + xy_rec(pts) + rec(ENDEL, 0)


def el_sref(name, x, y, mirror=False, mag=None, angle=None):
    return (rec(SREF, 0) + rec(SNAME, DT_ASCII, ascii_(name)) +
            strans_block(mirror, mag, angle) + xy_rec([(x, y)]) + rec(ENDEL, 0))


def el_aref(name, cols, rows, origin, col_step, row_step, mirror=False, mag=None, angle=None):
    ox, oy = origin
    pts = [origin,
           (ox + cols * col_step[0], oy + cols * col_step[1]),
           (ox + rows * row_step[0], oy + rows * row_step[1])]
    return (rec(AREF, 0) + rec(SNAME, DT_ASCII, ascii_(name)) +
            strans_block(mirror, mag, angle) +
            rec(COLROW, DT_I16, i16(cols, rows)) + xy_rec(pts) + rec(ENDEL, 0))


def el_text(layer, texttype, x, y, s):
    return (rec(TEXT, 0) + rec(LAYER, DT_I16, i16(layer)) +
            rec(TEXTTYPE, DT_I16, i16(texttype)) + xy_rec([(x, y)]) +
            rec(STRING, DT_ASCII, ascii_(s)) + rec(ENDEL, 0))


def el_cell(name, elements):
    return (rec(BGNSTR, DT_I16, i16(*TIME, *TIME)) + rec(STRNAME, DT_ASCII, ascii_(name)) +
            b"".join(elements) + rec(ENDSTR, 0))


# ---------------------------------------------------------------- geometry helpers

def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def octagon(x0, y0, x1, y1, c):
    return [(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c),
            (x1 - c, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0 + c)]


def um_pts(pts):
    """Convert micrometre coordinates to nm database units."""
    return [(round(x * 1000), round(y * 1000)) for x, y in pts]


# ---------------------------------------------------------------- cells

def inv_cell():
    e = [
        el_boundary(10, 0, rect(0, 4000, 4000, 8000)),       # nwell, upper half
        el_boundary(1, 0, rect(500, 500, 3500, 3000)),       # n-diff
        el_boundary(1, 0, rect(500, 5000, 3500, 7500)),      # p-diff
        el_path(2, 0, [(2000, 700), (2000, 6900), (2900, 6900)], 180, 0),  # poly gate, L jog
        el_boundary(2, 0, rect(2600, 6600, 3200, 7200)),     # poly pad
    ]
    for x, y in [(900, 1000), (2900, 1000), (900, 5500), (2900, 5500),
                 (1200, 2200), (2840, 6840)]:
        e.append(el_boundary(3, 0, rect(x, y, x + 120, y + 120)))  # contacts
    e += [
        el_boundary(4, 0, rect(0, 7300, 4000, 8000)),        # VDD rail
        el_boundary(4, 0, rect(0, 0, 4000, 700)),            # VSS rail
        el_boundary(4, 0, [(600, 2000), (1400, 2000), (1400, 2600),
                           (1000, 2600), (1000, 3400), (600, 3400)]),  # L output strap
        el_text(4, 0, 3000, 3800, "A"),
    ]
    return el_cell("INV", e)


def via_cell():
    return el_cell("VIA", [
        el_boundary(5, 0, rect(-100, -100, 100, 100)),       # 200 x 200 via
        el_boundary(4, 0, rect(-200, -200, 200, 200)),       # metal1 enclosure
        el_boundary(6, 0, rect(-200, -200, 200, 200)),       # metal2 enclosure
    ])


def pad_cell():
    return el_cell("PAD", [
        el_boundary(6, 0, octagon(0, 0, 60000, 60000, 15000)),
        el_boundary(4, 0, octagon(2000, 2000, 58000, 58000, 13000)),
        el_boundary(9, 0, rect(5000, 5000, 55000, 55000)),   # 50 um pad opening
        el_aref("VIA", 10, 10, (21000, 21000), (2000, 0), (0, 2000)),  # 2 um pitch, centered
    ])


def logo_cell():
    big_r, small_r = 10000, 4000
    star = []
    for k in range(10):
        ang = math.pi / 2 + k * math.pi / 5
        rad = big_r if k % 2 == 0 else small_r
        star.append((round(rad * math.cos(ang)), round(rad * math.sin(ang))))
    n = 64
    circle = [(round(16000 + 3500 * math.cos(2 * math.pi * i / n)),
               round(3500 * math.sin(2 * math.pi * i / n))) for i in range(n)]
    return el_cell("LOGO", [
        el_boundary(8, 0, star),       # concave 5-point star, 10 vertices
        el_boundary(8, 0, circle),     # 64-vertex circle
    ])


def top_cell(big_n):
    cols, rows = (12, 6) if big_n is None else (big_n, big_n)
    e = [
        # main core array of INV, 5 um column pitch, 9 um row pitch
        el_aref("INV", cols, rows, (128000, 130000), (5000, 0), (0, 9000)),
        # rotated 90 degree array, 4 x 3
        el_aref("INV", 4, 3, (208000, 190000), (10000, 0), (0, 6000), angle=90),
        el_sref("INV", 130000, 200000, mirror=True),
        el_sref("INV", 210000, 230000, mag=2.0),
    ]

    # 12 pads, 3 per side, ANGLE 0/90/180/270 for bottom/left/top/right
    pads = []
    for x0 in (90000, 170000, 250000):
        pads.append((x0, 10000, 0))
    for y0 in (90000, 170000, 250000):
        pads.append((10000, y0, 90))
    for x0 in (90000, 170000, 250000):
        pads.append((x0, 330000, 180))
    for y0 in (90000, 170000, 250000):
        pads.append((330000, y0, 270))
    origin_for = {0: lambda x0, y0: (x0, y0),
                  90: lambda x0, y0: (x0 + 60000, y0),
                  180: lambda x0, y0: (x0 + 60000, y0 + 60000),
                  270: lambda x0, y0: (x0, y0 + 60000)}
    for x0, y0, ang in pads:
        ox, oy = origin_for[ang](x0, y0)
        e.append(el_sref("PAD", ox, oy, angle=ang or None))

    # metal2 routes (width 2 um) from core to pad centres, in um
    routes = [
        ([(175, 165), (175, 100), (120, 100), (120, 40)], 2),
        ([(210, 165), (210, 100), (200, 100), (200, 40)], 2),
        ([(225, 165), (225, 120), (280, 120), (280, 40)], 0),
        ([(165, 175), (100, 175), (100, 120), (40, 120)], 2),
        ([(165, 205), (110, 205), (110, 200), (40, 200)], 2),
        ([(165, 225), (120, 225), (105, 240), (105, 280), (40, 280)], 2),  # 45 deg segment
        ([(180, 237), (180, 300), (120, 300), (120, 360)], 2),
        ([(205, 237), (205, 300), (200, 300), (200, 360)], 1),             # round ends
        ([(230, 237), (230, 310), (280, 310), (280, 360)], 2),
        ([(235, 175), (300, 175), (300, 120), (360, 120)], 2),
        ([(235, 205), (300, 205), (300, 200), (360, 200)], 4),             # extended ends
        ([(235, 225), (310, 225), (310, 280), (360, 280)], 2),
    ]
    for pts, pt in routes:
        e.append(el_path(6, 0, um_pts(pts), 2000, pt, ext=(500, 1500) if pt == 4 else None))

    # power ring: outer square 80..320 um with inner square hole 120..280 um,
    # joined by a zero-width cut at x = 200 um
    a, b = (200, 80), (200, 120)
    keyhole = [a, (320, 80), (320, 320), (80, 320), (80, 80), a,
               b, (120, 120), (120, 280), (280, 280), (280, 120), b, a]
    e.append(el_boundary(4, 0, um_pts(keyhole)))

    # seal ring at the die border (2 BOX, 2 BOUNDARY)
    e.append(el_box(7, 0, um_pts(rect(2, 2, 398, 5))))
    e.append(el_box(7, 0, um_pts(rect(2, 395, 398, 398))))
    e.append(el_boundary(7, 0, um_pts(rect(2, 5, 5, 395))))
    e.append(el_boundary(7, 0, um_pts(rect(395, 5, 398, 395))))

    # logo in a free corner: 30 degrees, magnification 1.5
    e.append(el_sref("LOGO", 255000, 255000, mag=1.5, angle=30))

    e.append(el_text(4, 0, 150000, 250000, "VDD"))
    e.append(el_text(4, 0, 240000, 100000, "VSS"))
    return el_cell("TOP", e)


def build_library(big_n=None):
    cells = [inv_cell(), via_cell(), pad_cell(), logo_cell(), top_cell(big_n)]
    return (rec(HEADER, DT_I16, i16(600)) +
            rec(BGNLIB, DT_I16, i16(*TIME, *TIME)) +
            rec(LIBNAME, DT_ASCII, ascii_("SAMPLELIB")) +
            rec(UNITS, DT_REAL8, real8(0.001) + real8(1e-9)) +
            b"".join(cells) +
            rec(ENDLIB, 0))


def main():
    ap = argparse.ArgumentParser(description="Generate a sample GDSII layout.")
    ap.add_argument("out", nargs="?", help="output file")
    ap.add_argument("--big", type=int, metavar="N",
                    help="stress test: TOP contains an N x N AREF of INV")
    args = ap.parse_args()

    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if args.big is not None:
        if args.big < 1:
            sys.exit("--big must be >= 1")
        out = args.out or os.path.abspath("sample_big.gds")
        big_n = args.big
    else:
        out = args.out or os.path.join(repo, "app", "src", "main", "assets", "sample.gds")
        big_n = None

    data = build_library(big_n)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (out, len(data)))


if __name__ == "__main__":
    main()
