#!/usr/bin/env python3
"""LCAC cargo-deck heightfield (data/berts_vehicle_pack/sbw/decks/lcac.json) rasterized from the committed LCAC geometry.

    python3 tools/vehgen/lcac_deck.py [--write]

The pack's aircraft carriers (essex, kiev) are SBW "deck surface" owners: a heightfield in the entity's data frame (+X left,
+Y up, +Z forward, blocks, origin at the entity position) whose every cell is a solid column from its bottom to its top. SBW turns
the columns into world collision (DeckCollisions through the DeckCollisionMixin), supports vehicles, probes and placement rays on
them, and carries whatever stands on the deck when the owner moves or turns (DeckCarry). The LCAC owner class
(FittedDeckBoatEntity) implements the same interface, so the LCAC's cargo well, its two ramps and its side structures are terrain
like a carrier's flight deck.

The columns come from the hull mesh itself: every triangle of the hull bone is projected on the 0.25-block grid, the highest
and the lowest surface under a cell centre are its column's top and bottom (1/16 block). The well floor is flat, the bow and
stern ramps slope down exactly as the model's ramp slabs do, the side structures are walls.
"""
import base64
import json
import os
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import vehgen  # noqa: E402

VID = 'lcac'
CELL = 0.25
EMPTY = -32768
GEO = os.path.join(vehgen.ASSETS, 'custom_geo', VID + '.geo.json')
OUT = os.path.join(vehgen.DATA, 'sbw', 'decks', VID + '.json')


def triangles(geo_path=GEO):
    g = json.load(open(geo_path, encoding='utf-8'))
    bone = next(b for b in g['minecraft:geometry'][0]['bones'] if b['name'] == 'hull')['poly_mesh']
    P = np.array(bone['positions'], float)
    D = np.column_stack([P[:, 0] / 16, P[:, 1] / 16, -P[:, 2] / 16])        # geo px -> data blocks (+X left, +Z forward)
    tris = []
    for poly in bone['polys']:
        idx = [v[0] for v in poly]
        for k in range(1, len(idx) - 1):
            tris.append((idx[0], idx[k], idx[k + 1]))
    return D[np.array(tris)]


def rasterize(T, cell=CELL):
    lo, hi = T.reshape(-1, 3).min(0), T.reshape(-1, 3).max(0)
    min_x = np.floor(lo[0] / cell) * cell
    min_z = np.floor(lo[2] / cell) * cell
    size_x = int(np.ceil((hi[0] - min_x) / cell))
    size_z = int(np.ceil((hi[2] - min_z) / cell))
    top = np.full((size_z, size_x), -np.inf)
    bottom = np.full((size_z, size_x), np.inf)
    for (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) in T:
        d = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2)
        if abs(d) < 1e-9:
            continue                                            # a vertical face: no footprint
        i0 = max(0, int(np.floor((min(x0, x1, x2) - min_x) / cell)))
        i1 = min(size_x - 1, int(np.floor((max(x0, x1, x2) - min_x) / cell)))
        j0 = max(0, int(np.floor((min(z0, z1, z2) - min_z) / cell)))
        j1 = min(size_z - 1, int(np.floor((max(z0, z1, z2) - min_z) / cell)))
        if i1 < i0 or j1 < j0:
            continue
        xs = min_x + (np.arange(i0, i1 + 1) + 0.5) * cell
        zs = min_z + (np.arange(j0, j1 + 1) + 0.5) * cell
        X, Z = np.meshgrid(xs, zs)
        a = ((z1 - z2) * (X - x2) + (x2 - x1) * (Z - z2)) / d
        b = ((z2 - z0) * (X - x2) + (x0 - x2) * (Z - z2)) / d
        c = 1 - a - b
        inside = (a >= -1e-9) & (b >= -1e-9) & (c >= -1e-9)
        if not inside.any():
            continue
        y = a * y0 + b * y1 + c * y2
        sub_t = top[j0:j1 + 1, i0:i1 + 1]
        sub_b = bottom[j0:j1 + 1, i0:i1 + 1]
        np.maximum(sub_t, np.where(inside, y, -np.inf), out=sub_t)
        np.minimum(sub_b, np.where(inside, y, np.inf), out=sub_b)
    covered = np.isfinite(top)
    top = opening(top, covered)
    q_top = np.where(covered, np.round(np.where(covered, top, 0) * 16), EMPTY).astype(np.int32)
    q_bot = np.where(covered, np.round(np.where(covered, bottom, 0) * 16), EMPTY).astype(np.int32)
    return min_x, min_z, size_x, size_z, q_top, q_bot


def _window(a, fn, fill):
    """3 x 3 sliding min / max that ignores empty cells (filled with `fill`) and the grid edge."""
    p = np.pad(a, 1, constant_values=fill)
    stack = [p[dj:dj + a.shape[0], di:di + a.shape[1]] for dj in range(3) for di in range(3)]
    return fn(np.stack(stack), axis=0)


def opening(top, covered):
    """Morphological opening of the column tops (erode, then dilate, 3 x 3): masts, rails, aerials and the thin stripes across
    the ramps (features under 0.75 block wide) stop being columns; walls, fan housings and the ramps are wider and stay. The
    slopes are unchanged: a slope is lowered by one cell of fall by the erosion and raised by it again by the dilation."""
    low = np.where(covered, top, np.inf)
    eroded = _window(low, np.min, np.inf)
    eroded = np.where(covered, eroded, np.inf)
    high = np.where(np.isfinite(eroded), eroded, -np.inf)
    opened = _window(high, np.max, -np.inf)
    out = np.where(covered, np.minimum(top, opened), -np.inf)
    return out


def rle(values):
    out = bytearray()
    flat = values.reshape(-1)
    i = 0
    while i < len(flat):
        v = flat[i]
        j = i
        while j < len(flat) and flat[j] == v and j - i < 32767:
            j += 1
        out += struct.pack('<hh', int(v), j - i)
        i = j
    return base64.b64encode(bytes(out)).decode()


def build(geo_path=GEO):
    T = triangles(geo_path)
    min_x, min_z, size_x, size_z, top, bot = rasterize(T)
    well = top[size_z // 2, size_x // 2] / 16.0
    return {
        'Version': 1, 'CellSize': CELL, 'MinX': float(min_x), 'MinZ': float(min_z), 'SizeX': size_x, 'SizeZ': size_z,
        'DeckHeight': float(well),
        'Encoding': 'rle-int16-px: base64 of little-endian (value, count) int16 pairs, rows of SizeX along +X, rows ordered '
                    'along +Z; value in 1/16 block above the entity origin, -32768 = no column',
        'Top': rle(top), 'Bottom': rle(bot)}, top, bot


def main(argv):
    deck, top, bot = build()
    sx, sz = deck['SizeX'], deck['SizeZ']
    print(f'deck grid {sx} x {sz} cells of {CELL}: x {deck["MinX"]} .. {deck["MinX"] + sx * CELL}, '
          f'z {deck["MinZ"]} .. {deck["MinZ"] + sz * CELL}, well floor {deck["DeckHeight"]} blocks')
    col = top[:, sx // 2]
    print('centre line tops (z, top):', [(round(deck['MinZ'] + (j + 0.5) * CELL, 2), round(col[j] / 16, 3))
                                           for j in range(0, sz, 8) if col[j] != EMPTY])
    if '--write' in argv:
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        with open(OUT, 'w', newline='\n') as fh:
            json.dump(deck, fh, indent=2)
            fh.write('\n')
        print('wrote', OUT)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
