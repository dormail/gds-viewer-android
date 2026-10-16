# tools

## make_sample_gds.py
Small hand-built sample layout (`app/src/main/assets/sample.gds`); `--big N` makes an N x N AREF stress file.

## make_stress_gds.py - large GDSII benchmark files
Generates realistic 100-200 MB GDSII files to benchmark opening and rendering. Standard library only,
deterministic per `--seed`, ~3-6 s per 150 MB. Reuses the record writer of `make_sample_gds.py`.

    python3 tools/make_stress_gds.py KIND [--size-mb N] [--seed S] [--gzip] [-o OUT]

| KIND | default size | content |
|------|-------------:|---------|
| `flat` | 150 MB | one cell, no hierarchy, ~2.5 M shapes over 5 x 5 mm: metal/via rectangles, ~20% 2-point PATHs (type 0/2), L-shapes and 45-degree octagons, BOX records, clustered density with empty areas, 50 nm vias to 50 um power straps |
| `stdcell` | 150 MB | ~150 sky130-like std-cell types (20-120 shapes), ~1.1 M placements in rows (alternate rows mirrored, FN/FS/S orientations so rows abut), fillers/taps, met1-met5 routing with via cells, SRAM macro (one 512x512 AREF bitcell array + periphery) placed at 0/90/180/270, 4 hard blocks placed several times (top -> block -> std cell -> shapes) |
| `photonic` | 100 MB | ~3400 device cells (rings with 200-2000 vertex polygons, grating couplers, spirals, MZIs), SREFs at 15/30/45/60/... degree angles, mirrors and MAG != 1, AREFs (also rotated), routed waveguides (paths with flat/round ends plus bend polygons), TEXT labels |
| `mixed` | 120 MB | top cell with a 2 x 2 mm flat region, a std-cell chip and a photonic block as subcells |

`--size-mb` is the approximate uncompressed size (within ~1%; the std-cell library needs a few MB at minimum).
`--gzip` writes OUT gzip-compressed (default name `stress_KIND.gds` / `.gds.gz`). A summary (size, cells,
boundaries, paths, boxes, srefs, arefs, texts, flattened instance and shape counts) is printed at the end.

## check_gds.py - independent validator
    python3 tools/check_gds.py FILE[.gds|.gds.gz] [--klayout]

Walks every record (lengths, data types, element framing, XY sizes, closed polygons, AREF/SNAME
consistency, timestamps), prints element counts and flattened totals; `--klayout` also loads the
file with the `klayout` binary (`klayout -b`) and prints its cell/instance/shape counts.
