#!/usr/bin/env python3
"""F-16A pylons, hand-built for the F-16A.mtb airframe (the same airframe coordinates as the F-16C).

The supplied F-16A model has no pylons. The F-16C model does, and War Thunder's F-16A Block 10 has the same set:
wingtip launchers (the model already has its tip rails), outer / middle / inner wing pylons and one centreline pylon.
Their sizes, shapes and places are measured from the F-16C airframe (assets/custom_geo/f_16c.geo.json, the faces of
wreck_wing_*__hull and wreck_fuselage_2__hull below the wing / belly skin; the station points are
tools/vehgen/specs/f_16c.json armamentOverrides):

  * the F-16C wing pylon is a thin blade 0.09 wide, 0.34 high (0.43 for the middle one, which hangs lower), 1.18 long,
    pointed at both ends in plan, its leading edge raked back 0.14 towards the top; the centreline pylon is the same
    blade 0.34 high under the belly. Here they are rebuilt as simple faceted solids of that size and place: a hexagonal
    blade (pointed leading and trailing edge) on a flat store-hook beam, the top buried in the wing / belly skin.
  * they are added to the wing hull bones (so they break away with the wing) and to the fuselage belly bone, in the
    hull's own face convention (outward normals, same winding as the hull).
  * they are painted with a grey picked from the F-16A texture itself (a uniform 3 x 3 texel block of the model's own
    grey, texture pixel (10, 5855)), so the texture file is not touched and no F-16C uv is used.

Bottom-centre points (vehicle data blocks = [geo x / 16, geo y / 16, -geo z / 16]) are printed for the armament file.
The native fallback's wing and belly boxes are widened to take the pylons' depth (as the F-16C's are).

Run on the airframe's committed files (git checkout the geo and the fallback first): a second run stops.
Usage: python tools/vehgen/f16a_pylons.py [--dry-run]
"""
import json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack')
GEO = os.path.join(GEN, 'custom_geo', 'f_16a.geo.json')
FALLBACK = os.path.join(GEN, 'geo', 'native_fallback', 'f_16a.geo.json')
TEX_W, TEX_H = 2048, 6144
GREY_PX = (10, 5855)                       # the model's own grey (98, 98, 102), a uniform block of the texture
STOCK_WING_POLYS = 72                      # a wing hull bone before the pylons

# (name, centre x of the left pylon, bottom y, centre z, top y, blade half width, blade length)
# measured on the F-16C: inner x 1.9929 y 1.4268 z -1.5018, middle 3.2159 / 1.3362 / -2.1359, outer 4.2123 / 1.4268 /
# -2.7247, centreline 0 / 0.6568 / -1.9548 (the pylon bottoms, = armamentOverrides stations); all 1.18 long, top 1.766
# on the wings and 0.996 under the belly.
PYLONS = [
    ('inner',      1.9929, 1.4268, -1.5018, 1.766, 0.045),
    ('middle',     3.2159, 1.3362, -2.1359, 1.766, 0.045),
    ('outer',      4.2123, 1.4268, -2.7247, 1.766, 0.045),
    ('centreline', 0.0,    0.6568, -1.9548, 0.996, 0.050),
]
LENGTH = 1.178                             # the F-16C blade (z -2.725 .. -1.547 for the middle one)
TIP = 0.136                                # how far the blade's flat sides stop short of the pointed ends
RAKE = 0.136                               # the leading edge's top sits this far aft of its foot
BEAM_H, BEAM_EXTRA = 0.050, 0.018          # the store-hook beam under the blade: height, and wider than the blade by


class Solid:
    """faces of one convex solid: each given as points (data frame: x left, y up, z forward) and oriented outward"""
    def __init__(self, centre):
        self.centre = np.array(centre, float)
        self.faces = []

    def face(self, pts):
        P = np.array(pts, float)
        n = np.zeros(3)
        for i in range(len(P)):                                   # Newell normal
            a, b = P[i], P[(i + 1) % len(P)]
            n += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        if n @ (P.mean(0) - self.centre) < 0:
            P = P[::-1]; n = -n
        self.faces.append((P, n))


def blade(cx, y0, y1, zc, hw):
    """hexagonal blade between heights y0 and y1: pointed at both ends, leading edge raked, trailing edge upright"""
    z_tr, z_ld = zc - LENGTH / 2, zc + LENGTH / 2

    def hexagon(y, rake):
        zl = z_ld - rake
        return [(cx, y, z_tr), (cx + hw, y, z_tr + TIP), (cx + hw, y, zl - TIP), (cx, y, zl), (cx - hw, y, zl - TIP),
                (cx - hw, y, z_tr + TIP)]
    lo, hi = hexagon(y0, 0.0), hexagon(y1, RAKE * (y1 - y0) / 0.34)
    s = Solid([cx, (y0 + y1) / 2, zc - RAKE / 4])
    for i in range(6):
        j = (i + 1) % 6
        s.face([lo[i], lo[j], hi[j], hi[i]])
    s.face(hi)                                                    # (inside the skin)
    return s


def beam(cx, y0, zc, hw):
    h = hw + BEAM_EXTRA
    z0, z1 = zc - LENGTH / 2 + 0.11, zc + LENGTH / 2 - 0.03
    s = Solid([cx, y0 + BEAM_H / 2, (z0 + z1) / 2])
    for ya, yb in ((y0, y0 + BEAM_H),):
        c = [(cx - h, z0), (cx + h, z0), (cx + h, z1), (cx - h, z1)]
        s.face([(x, ya, z) for x, z in c][::-1])                   # bottom
        s.face([(x, yb, z) for x, z in c])                         # top
        for i in range(4):
            j = (i + 1) % 4
            s.face([(c[i][0], ya, c[i][1]), (c[j][0], ya, c[j][1]), (c[j][0], yb, c[j][1]), (c[i][0], yb, c[i][1])])
    return s


def pylon_solids(cx, ybot, zc, ytop, hw):
    return [beam(cx, ybot, zc, hw), blade(cx, ybot + BEAM_H, ytop, zc, hw)]


def add_to_bone(bone, solids, uv):
    pm = bone['poly_mesh']
    P, N, U = pm['positions'], pm['normals'], pm['uvs']
    ui = len(U); U.append(list(uv))
    for s in solids:
        for pts, n in s.faces:
            raw = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pts]    # file pixels
            nr = np.array([n[0], n[1], -n[2]])                                         # outward, file frame
            a, b, c = (np.array(q) for q in raw[:3])
            if np.cross(b - a, c - a) @ nr < 0:                    # the hull's own winding: cross = the stored normal
                raw = raw[::-1]
            ni = len(N); N.append([round(float(x), 5) + 0.0 for x in nr])
            poly = []
            for q in raw:
                poly.append([len(P), ni, ui])
                P.append([round(x, 4) + 0.0 for x in q])
            pm['polys'].append(poly)


def main(argv):
    dry = '--dry-run' in argv
    doc = json.load(open(GEO, encoding='utf-8'))
    bones = {b['name']: b for b in doc['minecraft:geometry'][0]['bones']}
    wl, wr, fz = bones['wreck_wing_left__hull'], bones['wreck_wing_right__hull'], bones['wreck_fuselage_2__hull']
    if len(wl['poly_mesh']['polys']) != STOCK_WING_POLYS:
        sys.exit('f_16a pylons: the wing bone is not the airframe\'s own (already done?): git checkout the geo first')
    uv = [(GREY_PX[0] + 0.5) / TEX_W, 1.0 - (GREY_PX[1] + 0.5) / TEX_H]
    print('f_16a pylons (bottom-centre, vehicle data blocks [x, y, z]; right wing x mirrored):')
    for name, cx, ybot, zc, ytop, hw in PYLONS:
        if name == 'centreline':
            add_to_bone(fz, pylon_solids(cx, ybot, zc, ytop, hw), uv)
            print(f'  {name:10s} [{cx:.4f}, {ybot:.4f}, {zc:.4f}]')
        else:
            add_to_bone(wl, pylon_solids(cx, ybot, zc, ytop, hw), uv)
            add_to_bone(wr, pylon_solids(-cx, ybot, zc, ytop, hw), uv)
            print(f'  {name:10s} left [{cx:.4f}, {ybot:.4f}, {zc:.4f}]  right [{-cx:.4f}, {ybot:.4f}, {zc:.4f}]')
    print('  wingtip     the model\'s own tip rails: [+-4.943, 1.721, -2.5] (the F-16C station)')
    fb = json.load(open(FALLBACK, encoding='utf-8'))
    fbb = {b['name']: b for b in fb['minecraft:geometry'][0]['bones']}
    for name, y0, size in (('wreck_wing_left__hull', 1.336 * 16, None), ('wreck_wing_right__hull', 1.336 * 16, None),
                           ('wreck_fuselage_2__hull', 0.6568 * 16, None)):
        cube = fbb[name]['cubes'][0]
        top = cube['origin'][1] + cube['size'][1]
        cube['origin'][1] = round(y0, 5)
        cube['size'][1] = round(top - y0, 5)
    if dry:
        return
    with open(GEO, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(doc, f, separators=(',', ':'))
    with open(FALLBACK, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(fb, f, separators=(',', ':'))


if __name__ == '__main__':
    main(sys.argv[1:])
