#!/usr/bin/env python3
"""Finds the pilot's control stick in an aircraft model.

MTB cockpits mark the stick grip with a red top face. The finder takes the red face in front of the seat nearest to
the centre line and the pilot's hands, and collects every polygon that lies wholly inside a cylinder running from that
face down the stick (along the face's inward normal): the grip, the shaft and small parts on it. Large parts that
merely touch the column (the floor, the seat, the panel) are left alone. The pivot is the bottom of that column.
"""
import math, os, sys
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'canopy_frames'))
import thin as T  # noqa: E402

MAX_LENGTH = 0.8        # blocks down the column
MIN_LENGTH = 0.1


def red(c):
    return c[0] > 0.45 and c[1] < 0.3 and c[2] < 0.3


def poly_color(pm, poly, tex):
    H, W = tex.shape[:2]
    t = [np.array(pm['uvs'][v[2]], float) for v in poly]
    q = sum(t) / len(t)
    return tex[int(np.clip((1 - q[1]) * H, 0, H - 1)), int(np.clip(q[0] * W, 0, W - 1)), :3]


def all_polys(geo):
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].startswith('pilot_view_occluder'):
            continue
        for pi, poly in enumerate(pm['polys']):
            yield bone, pi, poly, np.array([T.to_local(pm['positions'][v[0]]) for v in poly])


def column(geo, top, axis, radius):
    """Polys wholly inside the cylinder from `top` along `axis` (unit, pointing down the stick)."""
    parts, depth = [], 0.0
    for bone, pi, poly, P in all_polys(geo):
        rel = P - top
        along = rel @ axis
        perp = np.linalg.norm(rel - np.outer(along, axis), axis=1)
        if along.min() < -0.03 or along.max() > MAX_LENGTH or perp.max() > radius:
            continue
        parts.append((bone, pi))
        depth = max(depth, float(along.max()))
    return parts, depth


def candidates(geo, tex, eye, need_red):
    out = []
    for bone, pi, poly, P in all_polys(geo):
        c = P.mean(axis=0)
        rel = c - eye
        if not (-0.15 < rel[0] < 0.15 and -1.2 < rel[1] < -0.2 and -0.1 < rel[2] < 0.8):
            continue
        size = np.linalg.norm(P.max(axis=0) - P.min(axis=0))
        if size > (0.15 if need_red else 0.09) or len(poly) < 3:
            continue
        if need_red and not red(poly_color(bone['poly_mesh'], poly, tex)):
            continue
        n = np.cross(P[1] - P[0], P[2] - P[0])
        if np.linalg.norm(n) < 1e-9:
            continue
        n /= np.linalg.norm(n)
        if n[1] < 0:
            n = -n
        if n[1] < 0.35:
            continue    # a grip top faces up (a tilted stick tilts it, but not onto its side)
        out.append((abs(rel[0]) * 2 + abs(rel[1] + 0.55) + abs(rel[2] - 0.3), c, n, size))
    out.sort(key=lambda f: f[0])
    return out


def best_column(geo, faces, radii, limit):
    best = None
    for _, c, n, size in faces[:limit]:
        for radius in radii(size):
            parts, depth = column(geo, c, -n, radius)
            if depth < MIN_LENGTH or len(parts) < 4 or len(parts) > 40:
                continue
            if best is None or depth > best['length'] + 0.02:
                best = dict(top=c, axis=-n, pivot=c - n * depth, parts=parts, length=depth, radius=radius)
    return best


def find(vid):
    _, geo = T.load_geo(vid)
    tex = T.load_texture(vid)
    eye = T.eyes(vid)[0]
    red_faces = candidates(geo, tex, eye, True)
    best = best_column(geo, red_faces, lambda size: (max(0.035, 0.75 * size), 0.06, 0.08), 4)
    if best is None or best['length'] < 0.2:
        # No red grip, or only the grip: any small up-facing top with a column under it.
        other = best_column(geo, candidates(geo, tex, eye, False), lambda size: (0.035, 0.05), 40)
        if other is not None and other['length'] >= 0.25 and (best is None or other['length'] > best['length']):
            best = other
    if best is None:
        return None
    best['eye'] = eye
    return best


if __name__ == '__main__':
    for vid in sys.argv[1:]:
        r = find(vid)
        if not r:
            print(f'{vid:30s} no stick found')
            continue
        print(f"{vid:30s} polys {len(r['parts'])} length {r['length']:.2f} r {r['radius']:.3f} "
              f"pivot rel {np.round(r['pivot'] - r['eye'], 2)} axis {np.round(r['axis'], 2)}")
