#!/usr/bin/env python3
"""Independent GDSII structure checker (standard library only).

Usage:
  python3 tools/check_gds.py FILE[.gds|.gds.gz] [--klayout] [--top NAME]

Walks every record and checks: record length (even, >= 4, inside the file), data type per record
type, library/structure/element framing, STRANS-before-MAG-before-ANGLE order, XY sizes (<= 8191
points, closed BOUNDARY/BOX, AREF has 3 points, SREF/TEXT 1, PATH >= 2), valid BGNLIB/BGNSTR
timestamps, SNAME references resolve, and AREF steps divide evenly. Prints element counts and the
flattened instance/shape counts. With --klayout also loads the file with the klayout binary
(klayout -b) and prints its cell/instance/shape statistics.
"""
import gzip
import os
import struct
import subprocess
import sys
import tempfile

EXPECT_DT = {0x00: 2, 0x01: 2, 0x02: 6, 0x03: 5, 0x04: 0, 0x05: 2, 0x06: 6, 0x07: 0, 0x08: 0,
             0x09: 0, 0x0A: 0, 0x0B: 0, 0x0C: 0, 0x0D: 2, 0x0E: 2, 0x0F: 3, 0x10: 3, 0x11: 0,
             0x12: 6, 0x13: 2, 0x16: 2, 0x19: 6, 0x1A: 1, 0x1B: 5, 0x1C: 5, 0x21: 2, 0x2D: 0,
             0x2E: 2, 0x30: 3, 0x31: 3}
ELEMS = {0x08: "BOUNDARY", 0x09: "PATH", 0x0A: "SREF", 0x0B: "AREF", 0x0C: "TEXT", 0x2D: "BOX"}
REQUIRED = {0x08: {0x0D, 0x0E, 0x10}, 0x09: {0x0D, 0x0E, 0x0F, 0x10}, 0x0A: {0x12, 0x10},
            0x0B: {0x12, 0x13, 0x10}, 0x0C: {0x0D, 0x16, 0x10, 0x19}, 0x2D: {0x0D, 0x2E, 0x10}}
NPTS = {0x0A: 1, 0x0B: 3, 0x0C: 1}


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    path = args[0]
    top = None
    if "--top" in sys.argv:
        top = sys.argv[sys.argv.index("--top") + 1]
        args = [a for a in args if a != top]
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        data = f.read()
    n = len(data)
    unpack = struct.unpack_from
    errors = []

    def err(msg):
        if len(errors) < 20:
            errors.append(msg)

    pos = 0
    nrec = 0
    state = "start"            # start, lib, str, el, end
    counts = dict.fromkeys(ELEMS.values(), 0)
    cells = {}                 # name -> [own_shapes, texts, {child: mult}]
    cur = None
    elt = None
    seen = []
    npts = 0
    sname = None
    colrow = None
    ptsv = None
    maxpts = 0
    units = None
    while pos < n:
        if pos + 4 > n:
            err("truncated header at %d" % pos)
            break
        ln, rt, dt = unpack(">HBB", data, pos)
        nrec += 1
        if ln < 4 or ln % 2 or pos + ln > n:
            err("bad record length %d at %d (type %02x)" % (ln, pos, rt))
            break
        exp = EXPECT_DT.get(rt)
        if exp is None:
            err("unknown record type %02x at %d" % (rt, pos))
        elif exp != dt:
            err("record %02x has data type %d, expected %d at %d" % (rt, dt, exp, pos))
        body = pos + 4
        plen = ln - 4
        if state == "el":
            if rt == 0x11:                                   # ENDEL
                miss = REQUIRED[elt] - set(seen)
                if miss:
                    err("%s in %s missing records %s" % (ELEMS[elt], cur, sorted(miss)))
                counts[ELEMS[elt]] += 1
                c = cells[cur]
                if elt == 0x0A or elt == 0x0B:
                    mult = 1 if elt == 0x0A else colrow[0] * colrow[1]
                    c[2][sname] = c[2].get(sname, 0) + mult
                elif elt == 0x0C:
                    c[1] += 1
                else:
                    c[0] += 1
                state = "str"
            else:
                seen.append(rt)
                if rt == 0x10:                               # XY
                    if plen % 8:
                        err("XY size %d not multiple of 8 in %s" % (plen, cur))
                    npts = plen // 8
                    maxpts = max(maxpts, npts)
                    if npts > 8191:
                        err("XY has %d points" % npts)
                    need = NPTS.get(elt)
                    if need is not None and npts != need:
                        err("%s XY has %d points" % (ELEMS[elt], npts))
                    if elt in (0x08, 0x2D):
                        if npts < 4 or unpack(">ii", data, body) != unpack(">ii", data, body + plen - 8):
                            err("%s not closed / too short in %s" % (ELEMS[elt], cur))
                        if elt == 0x2D and npts != 5:
                            err("BOX with %d points" % npts)
                    if elt == 0x09 and npts < 2:
                        err("PATH with %d points" % npts)
                    if elt == 0x0B:
                        ptsv = unpack(">6i", data, body)
                elif rt == 0x12:
                    sname = data[body:body + plen].rstrip(b"\0").decode("ascii")
                elif rt == 0x13:
                    colrow = unpack(">2h", data, body)
                elif rt in (0x1A, 0x1B, 0x1C):
                    order = [r for r in seen if r in (0x1A, 0x1B, 0x1C)]
                    if order != sorted(order):
                        err("STRANS/MAG/ANGLE out of order in %s" % cur)
                    if rt != 0x1A and 0x1A not in seen:
                        err("MAG/ANGLE without STRANS in %s" % cur)
                    if rt in (0x1B, 0x1C) and plen != 8:
                        err("bad real8 size")
                elif rt in (0x19, 0x06) and plen == 0:
                    err("empty string")
                if rt == 0x10 and elt == 0x0B and colrow is not None:
                    cols, rows = colrow
                    ox, oy, cx, cy, rx, ry = ptsv
                    if cols < 1 or rows < 1 or (cx - ox) % cols or (cy - oy) % cols \
                            or (rx - ox) % rows or (ry - oy) % rows:
                        err("AREF steps not integral in %s" % cur)
        elif rt in ELEMS:
            if state != "str":
                err("element outside structure at %d" % pos)
            state, elt, seen, colrow, sname = "el", rt, [], None, None
        elif rt == 0x00:
            if state != "start":
                err("duplicate HEADER")
            state = "hdr"
        elif rt == 0x01:                                     # BGNLIB
            if state != "hdr":
                err("BGNLIB out of place")
            state = "lib"
            ts = unpack(">12h", data, body)
            if not (1 <= ts[1] <= 12 and 1 <= ts[2] <= 31 and 0 <= ts[3] <= 24):
                err("bad BGNLIB timestamp %s" % (ts,))
        elif rt == 0x03:
            units = (struct.unpack_from(">Q", data, body)[0], struct.unpack_from(">Q", data, body + 8)[0])
        elif rt == 0x05:                                     # BGNSTR
            if state != "lib":
                err("BGNSTR out of place at %d" % pos)
            ts = unpack(">12h", data, body)
            if not (1 <= ts[1] <= 12 and 1 <= ts[2] <= 31 and 0 <= ts[3] <= 24):
                err("bad BGNSTR timestamp %s" % (ts,))
            state = "strname"
        elif rt == 0x06:
            if state != "strname":
                err("STRNAME out of place")
            cur = data[body:body + plen].rstrip(b"\0").decode("ascii")
            if cur in cells:
                err("duplicate cell %s" % cur)
            cells[cur] = [0, 0, {}]
            state = "str"
        elif rt == 0x07:
            if state != "str":
                err("ENDSTR out of place")
            state = "lib"
        elif rt == 0x04:
            if state != "lib":
                err("ENDLIB out of place")
            state = "end"
            pos += ln
            break
        elif rt != 0x02:
            err("unexpected record %02x in state %s" % (rt, state))
        pos += ln
    if state != "end":
        err("no ENDLIB (state %s)" % state)
    if pos != n:
        err("%d trailing bytes after ENDLIB" % (n - pos))

    # hierarchy
    called = set()
    for nm, c in cells.items():
        for ch in c[2]:
            called.add(ch)
            if ch not in cells:
                err("%s references undefined cell %s" % (nm, ch))
    tops = [c for c in cells if c not in called]
    if top is None and tops:
        top = tops[-1]
    memo = {}

    def flat(nm, depth=0):
        if depth > 50:
            raise RuntimeError("hierarchy too deep / cyclic at " + nm)
        r = memo.get(nm)
        if r is None:
            c = cells[nm]
            inst, sh, tx = 0, c[0], c[1]
            for ch, m in c[2].items():
                if ch in cells:
                    i, s, t = flat(ch, depth + 1)
                    inst += m * (1 + i)
                    sh += m * s
                    tx += m * t
            r = memo[nm] = (inst, sh, tx)
        return r

    print("file: %s (%d bytes uncompressed), %d records" % (path, n, nrec))
    if units:
        print("units raw real8: %016x %016x" % units)
    print("cells: %d, top cells: %s, max XY points in one record: %d" % (len(cells), tops, maxpts))
    print("elements: " + ", ".join("%s=%d" % kv for kv in counts.items()),
          "texts(in cells)=%d" % sum(c[1] for c in cells.values()))
    if top and not any("cyclic" in e for e in errors):
        try:
            inst, sh, tx = flat(top)
            print("flattened from %s: instances=%d shapes=%d texts=%d" % (top, inst, sh, tx))
        except RuntimeError as e:
            err(str(e))
    if errors:
        print("ERRORS:")
        for e in errors:
            print("  " + e)
    else:
        print("STRUCTURE OK")

    if "--klayout" in sys.argv:
        script = '''
import pya
ly = pya.Layout()
ly.read(gds)
ly.dbu
tops = [c.name for c in ly.top_cells()]
nshape = 0
ninst = 0
memo = {}
for c in ly.each_cell():
    nshape += c.shapes_count() if hasattr(c, "shapes_count") else sum(c.shapes(l).size() for l in ly.layer_indexes())
    ninst += sum(1 for _ in c.each_inst())
def fl(c):
    r = memo.get(c.cell_index())
    if r is None:
        sh = sum(c.shapes(l).size() for l in ly.layer_indexes()); ins = 0
        for i in c.each_inst():
            n = i.size()
            s2, i2 = fl(i.cell)
            sh += n * s2; ins += n * (1 + i2)
        r = memo[c.cell_index()] = (sh, ins)
    return r
print("KLAYOUT cells=%d top=%s dbu=%g own_shapes=%d inst_records=%d" % (ly.cells(), tops, ly.dbu, nshape, ninst))
for t in ly.top_cells():
    sh, ins = fl(t)
    print("KLAYOUT flattened %s: shapes=%d instances=%d bbox=%s" % (t.name, sh, ins, t.bbox()))
'''
        with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as tf:
            tf.write(script)
        try:
            r = subprocess.run(["klayout", "-b", "-r", tf.name, "-rd", "gds=" + os.path.abspath(path)],
                               capture_output=True, text=True)
            print(r.stdout.strip())
            if r.returncode or r.stderr.strip():
                print("klayout rc=%d stderr: %s" % (r.returncode, r.stderr.strip()[:2000]))
        finally:
            os.unlink(tf.name)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
