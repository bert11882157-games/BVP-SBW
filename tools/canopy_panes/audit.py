#!/usr/bin/env python3
"""Window audit for every airplane and helicopter: what each crew eye sees through the cockpit.

For every crew eye the alpha-tested model is rendered onto a depth/colour cube map (the canopy_frames renderer, so
the view is the one the game draws). Directions up from 35 degrees below the horizon are classed:
  open     nothing within SHELL of the eye: a window opening (or an open cockpit);
  glazed   an open direction that passes through the vehicle's CanopyGlass on the way out;
  dark     a surface within SHELL whose texel is near-black: a window painted on rather than cut out (candidate).
Prints one line per vehicle and writes JSON. Usage: python audit.py [--json=out.json] [ids...] (no ids: all aircraft)
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T

SHELL = 1.6          # blocks: anything nearer is cockpit (frames, panel, walls)
DARK = 0.10          # mean texel value of a painted window
N_DIRS = 6000
T.FACE = 240


def aircraft(kinds=('Airplane', 'Helicopter')):
    d = os.path.join(T.GEN, 'data/berts_vehicle_pack/sbw/vehicles')
    out = []
    for f in sorted(os.listdir(d)):
        try:
            doc = json.load(open(os.path.join(d, f), encoding='utf-8'))
        except Exception:
            continue
        if doc.get('Type') in kinds:
            out.append((f[:-5], doc['Type']))
    return out


def directions(n=N_DIRS, el_min=-35.0):
    i = np.arange(n) + 0.5
    y = 1 - 2 * i / n
    r = np.sqrt(1 - y * y)
    th = math.pi * (1 + 5 ** 0.5) * i
    d = np.stack([r * np.cos(th), y, r * np.sin(th)], -1)
    return d[y > math.sin(math.radians(el_min))]


def glass(vid):
    p = os.path.join(T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
    if not os.path.exists(p):
        return None
    c = json.load(open(p, encoding='utf-8')).get('CanopyGlass')
    if not c or c.get('Schema') != 1:
        return None
    return np.array(c['Triangles'], float).reshape(-1, 3, 3)


def ray_hits(tris, origin, dirs, tmax):
    """Nearest hit distance of each unit direction against triangles (inf: none before tmax)."""
    out = np.full(len(dirs), np.inf)
    if tris is None or not len(tris):
        return out
    v0 = tris[:, 0] - origin; e1 = tris[:, 1] - tris[:, 0]; e2 = tris[:, 2] - tris[:, 0]
    for k in range(0, len(dirs), 512):
        d = dirs[k:k + 512][:, None, :]
        p = np.cross(d, e2[None])
        det = (p * e1[None]).sum(-1)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
        s = -v0[None]
        a = (p * s).sum(-1) * inv
        q = np.cross(s, e1[None])
        b = (d * q).sum(-1) * inv
        t = (q * e2[None]).sum(-1) * inv
        hit = ok & (a >= 0) & (b >= 0) & (a + b <= 1) & (t > 0.02) & (t < tmax)
        out[k:k + 512] = np.where(hit, t, np.inf).min(1)
    return out


def crew(vid, kind):
    e = T.eyes(vid)
    if kind == 'Helicopter':
        return [x for x in e[:2] if np.linalg.norm(x - e[0]) < 4.0]
    return T.crew_eyes(vid)


def audit(vid, kind, dirs):
    _, geo = T.load_geo(vid)
    tris, uvs = T.triangles(geo)
    tex = T.load_texture(vid)
    g = glass(vid)
    res = []
    for eye in crew(vid, kind):
        col = np.zeros((6, T.FACE, T.FACE, 3), np.float32)
        depth = T.render_depth(tris, uvs, tex, eye, color=col)
        d = T.lookup(depth, dirs)
        c = T.lookup(col, dirs)
        open_ = d > SHELL
        gl = ray_hits(g, eye, dirs, SHELL * 2) < np.minimum(d, SHELL * 2)
        dark = (~open_) & (c.mean(-1) < DARK)
        # texture glass: translucent texels (cut by the alpha test above) the model itself draws in the opening
        tex2 = tex.copy(); tex2[..., 3] = (tex[..., 3] > 0.02).astype(np.float32)
        d2 = T.lookup(T.render_depth(tris, uvs, tex2, eye), dirs)
        tinted = open_ & (d2 < SHELL)
        res.append(dict(eye=[round(float(v), 3) for v in eye], open=float(open_.mean()),
                        holes=float((open_ & ~gl & ~tinted).mean()), glazed=float((open_ & gl).mean()),
                        texglass=float(tinted.mean()), dark=float(dark.mean())))
    return dict(kind=kind, glass=g is not None and len(g), eyes=res)


def main(argv):
    ids = [a for a in argv if not a.startswith('--')]
    kinds = dict(aircraft())
    ids = ids or list(kinds)
    dirs = directions()
    out = {}
    for vid in ids:
        try:
            r = audit(vid, kinds.get(vid, 'Airplane'), dirs)
        except Exception as e:
            print(f'{vid:34s} FAILED {e!r}', flush=True); continue
        out[vid] = r
        e = r['eyes'][0] if r['eyes'] else {}
        print(f"{vid:34s} {r['kind'][:5]} glass {str(bool(r['glass'])):5s} " + ' | '.join(
            f"open {x['open']:.2f} holes {x['holes']:.2f} tex {x['texglass']:.2f} dark {x['dark']:.2f}"
            for x in r['eyes']), flush=True)
    j = next((a[7:] for a in argv if a.startswith('--json=')), None)
    if j:
        json.dump(out, open(j, 'w'), indent=1)


if __name__ == '__main__':
    main(sys.argv[1:])
