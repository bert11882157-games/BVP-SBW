#!/usr/bin/env python3
"""Finds the old canopy of an aircraft: the frame faces of the model's own canopy and the fitted canopy glass.

The MTB models weld their canopy frames into the fuselage mesh (the hull bones of the wreck pieces). A face belongs
to the old canopy when, in the canopy's footprint (inside the glass's width, between its front and rear ends):
  * it stands above the cockpit sill, and
  * it lies along the glass (a frame bar, bow, arch or sill member just under or over the glass), or stands inside the
    canopy space clear of the fuselage skin (the arch behind the seat, mirrors, handles, loose frame pieces).
Fuselage skin under the glass (the spine under an aft transparency, the deck under a windscreen) lies well below the
glass and faces out of the airframe: it is kept, so removing the canopy leaves the fuselage closed. The new cockpit
(__cockpit bones) is never touched. The HUD combiner glass the cockpit tool added is kept; all other glass goes.

Used by canopy.py; run alone it reports what it would remove: python3 strip.py <id>...
"""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
import cockpit_lib as L  # noqa: E402

ALONG = 0.075      # a face this close to the glass at every corner lies along it (a frame member)
SKIN_GAP = 0.10    # a face whose top is this far under the glass above it is fuselage skin under the glass
MARGIN = 0.12      # beyond the glass's width and ends


def hud_glass_mask(asset, glass):
    """True for glass triangles of the HUD combiner (inside the combiner's rectangle, on its plane)"""
    hud = asset.get('HeadUpDisplay')
    if not hud or not len(glass):
        return np.zeros(len(glass), bool)
    c, n, u = (np.array(hud[k], float) for k in ('Center', 'Normal', 'Up'))
    n /= np.linalg.norm(n); u /= np.linalg.norm(u)
    r = np.cross(u, n)
    d = glass - c
    on_plane = np.all(np.abs(d @ n) < 0.03, axis=1)
    inside = np.all((np.abs(d @ r) < hud['Width'] / 2 + 0.06) & (np.abs(d @ u) < hud['Height'] / 2 + 0.08), axis=1)
    return on_plane & inside


def find(ac, tub):
    """{bone name: set(poly index)} of the old canopy frame faces, and the glass triangles to keep (the HUD's)"""
    g = ac.glass
    keep_glass = hud_glass_mask(ac.asset, g)
    canopy = g[~keep_glass]
    if not len(canopy):
        return {}, keep_glass
    lo, hi = canopy.reshape(-1, 3).min(0), canopy.reshape(-1, 3).max(0)
    saved = ac.glass
    ac.glass = canopy                         # glass_above / hugs_glass against the canopy glass only
    E = tub.E
    out = {}
    for b in ac.bones:
        if not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        sel = set()
        for i, poly in enumerate(pm['polys']):
            V = P[[v[0] for v in poly]]
            cc = V.mean(0)
            if not (lo[0] - MARGIN < cc[0] < hi[0] + MARGIN and lo[2] - MARGIN < cc[2] < hi[2] + MARGIN):
                continue
            if V[:, 1].max() < tub.sill - 0.02 or cc[1] < lo[1] - 0.05:
                continue
            if ac.hugs_glass(V, ALONG):
                sel.add(i)
                continue
            top = ac.glass_above(cc[0], cc[2], V[:, 1].max() - 0.01)
            if top is None:
                # beside the glass: a frame stub standing above the glass's lower edge, within reach of its side
                edge = _edge_at(canopy, cc[2])
                if edge is not None and V[:, 1].max() > edge[0] + 0.03 and np.ptp(V, 0).max() < 0.3 and \
                        abs(cc[0] - E[0]) < edge[1] + 0.08:
                    sel.add(i)
                continue                          # not under the canopy: skin beside or beyond it
            if top - V[:, 1].max() > SKIN_GAP and _outward(ac, V, cc, E):
                continue                          # fuselage skin under the glass
            if cc[1] > tub.sill + 0.02:
                sel.add(i)                        # standing in the canopy space: arch, mirror, frame piece
        sel |= _rest_of_parts(P, pm['polys'], sel, tub.sill - 0.06)
        if sel:
            out[b['name']] = sel
    ac.glass = saved
    return out, keep_glass


def _edge_at(glass, z, dz=0.08):
    """(lowest glass y, glass half-width about its middle) at station z, or None"""
    G = glass.reshape(-1, 3)
    s = G[np.abs(G[:, 2] - z) < dz]
    if not len(s):
        return None
    return float(s[:, 1].min()), float(np.ptp(s[:, 0]) / 2)


def _rest_of_parts(P, polys, sel, floor, max_faces=400):
    """the other faces of small connected parts (sharing corners) already partly selected, above [floor]: the legs
    of an arch or the ends of a frame bar that reach outside the glass's footprint"""
    parent = {}
    def root(x):
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    key = lambda i: tuple(np.round(P[i], 4))
    for p in polys:
        ks = [key(v[0]) for v in p]
        for k in ks[1:]:
            parent[root(ks[0])] = root(k)
    parts = {}
    for i, p in enumerate(polys):
        parts.setdefault(root(key(p[0][0])), []).append(i)
    extra = set()
    for faces in parts.values():
        if len(faces) > max_faces or not sel.intersection(faces):
            continue
        low = min(P[[v[0] for v in polys[i]]][:, 1].min() for i in faces)
        if low > floor:
            extra.update(faces)
    return extra - sel


def _outward(ac, V, cc, E):
    n = np.cross(V[1] - V[0], V[-1] - V[0]); ln = np.linalg.norm(n)
    if ln < 1e-9:
        return True
    n = n / ln
    if n[1] < 0:
        n = -n                                    # skin under the glass faces up
    return n[1] > 0.35


def main(argv):
    for vid in argv:
        ac = L.Aircraft(vid)
        tub = L.Tub(ac, 0)
        rm, keep = find(ac, tub)
        print(vid, {k: len(v) for k, v in rm.items()}, 'glass kept', int(keep.sum()), 'of', len(ac.glass))


if __name__ == '__main__':
    main(sys.argv[1:])
