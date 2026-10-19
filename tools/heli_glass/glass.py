#!/usr/bin/env python3
"""Helicopter cockpit glass: every window opening glazed by filling in the depth map seen from each crew eye.

Helicopter models are framed greenhouses: thin bars with nothing between them. For each crew eye the alpha-tested
model is rendered onto a depth cube map (canopy_frames.thin) and sampled on an azimuth/elevation grid:
  * a cell is a WINDOW where nothing lies within OPEN of the eye (sky, or the rotor and tail far beyond); anything
    nearer (bars, the panel, the walls, the cabin behind) is SOLID and keeps its depth;
  * each window's depth is filled in from the solid cells round it (Laplace's equation on the grid: the smooth
    surface spanning the frame ring, a dome between the bars of a bubble, nearly flat in a small flat window);
  * the window cells, one cell into the frame so the pane's edge tucks under the bars (that ring is filled in
    too, so the edge continues the pane instead of spiking in to the wall), are triangulated at that depth less
    OFFSET, so the glass lies just inside the bars' outer face and never z-fights them;
  * a later eye skips cells that glass from an earlier eye already covers, so a window seen from two seats gets
    one pane; windows below EL_MIN (the floor) and openings smaller than MIN_CELLS (gaps between parts) are left.
The whole CanopyGlass of the vehicle is replaced.

Usage: python3 tools/heli_glass/glass.py [--write] [--out=DIR] [--eyes=0,1] id...
"""
import json, math, os, sys
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
ASSET_DIR = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
CONFIG = os.path.join(HERE, 'glass.json')
STEP = 4.0          # degrees per cell
OPEN = 2.6          # blocks: a cell with nothing nearer is a window
EL_MIN = -70.0      # degrees: the chin windows reach down to here
MIN_CELLS = 12      # smaller openings are gaps between parts
OFFSET = 0.010      # blocks inside the bars' outer face
ITER = 3000         # Laplace relaxation sweeps
T.FACE = 360


def grid():
    az = np.arange(-180.0, 180.0, STEP)
    el = np.arange(EL_MIN, 90.0, STEP)
    return az, el


def dirs_of(az, el):
    a, e = np.radians(az)[None, :], np.radians(el)[:, None]
    return np.stack([np.cos(e) * np.sin(a), np.sin(e) * np.ones_like(a), np.cos(e) * np.cos(a)], -1)


def label_wrapped(mask):
    lab, n = ndimage.label(mask, structure=np.ones((3, 3)))
    parent = list(range(n + 1))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    for j in range(mask.shape[0]):
        for dj in (-1, 0, 1):
            jj = j + dj
            if 0 <= jj < mask.shape[0]:
                a, b = lab[j, 0], lab[jj, -1]
                if a and b:
                    parent[find(a)] = find(b)
    roots = np.array([find(i) for i in range(n + 1)])
    return roots[lab]


def fill(depth, unknown):
    """Laplace fill of the unknown cells from the known ones (azimuth wraps); the known cells stay."""
    d = depth.copy()
    known = ~unknown
    # start the unknown cells at the mean of the known ones round each window, then relax
    d[unknown] = np.nanmean(np.where(known, d, np.nan))
    for _ in range(ITER):
        up = np.roll(d, 1, 0); up[0] = d[0]
        dn = np.roll(d, -1, 0); dn[-1] = d[-1]
        lf = np.roll(d, 1, 1); rt = np.roll(d, -1, 1)
        new = (up + dn + lf + rt) / 4
        d = np.where(unknown, new, d)
    return d


def covered_by(glass, eye, dirs):
    """cells whose ray from the eye meets the glass already made"""
    if not len(glass):
        return np.zeros(dirs.shape[:2], bool)
    G = np.array(glass)
    T_ = G - eye
    e1, e2 = T_[:, 1] - T_[:, 0], T_[:, 2] - T_[:, 0]
    D = dirs.reshape(-1, 3)
    out = np.zeros(len(D), bool)
    for k in range(0, len(D), 4000):
        d = D[k:k + 4000]
        h = np.cross(d[:, None, :], e2[None])                 # (m, n, 3)
        a = (h * e1[None]).sum(-1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1.0 / np.where(ok, a, 1.0), 0.0)
        s = -T_[:, 0][None]
        u = f * (s * h).sum(-1)
        q = np.cross(s, e1[None])
        v = f * (d[:, None, :] * q).sum(-1)
        t = f * (q * e2[None]).sum(-1)
        out[k:k + 4000] = (ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > 0.01)).any(1)
    return out.reshape(dirs.shape[:2])


def panes(vid, eyes_idx=None, log=print):
    _, geo = T.load_geo(vid)
    tex = T.load_texture(vid)
    tris, uvs = T.triangles(geo)
    all_eyes = T.eyes(vid)
    cfg = json.load(open(CONFIG)).get(vid, {}) if os.path.exists(CONFIG) else {}
    idx = eyes_idx if eyes_idx is not None else cfg.get('eyes', [0, 1][:len(all_eyes)])
    eyes = [all_eyes[i] + np.array(cfg.get('eye_shift', [0, 0, 0]), float) for i in idx if i < len(all_eyes)]
    az, el = grid()
    dirs = dirs_of(az, el)
    glass = []
    for n, eye in enumerate(eyes):
        depth = T.render_depth(tris, uvs, tex, eye)
        d = T.lookup(depth, dirs)
        window = ~(d < OPEN)
        for a0, a1, e0, e1 in cfg.get('keep_open', []):      # openings meant to stay open (a door, the cabin)
            window &= ~((az[None, :] >= a0) & (az[None, :] <= a1) & (el[:, None] >= e0) & (el[:, None] <= e1))
        window &= ~covered_by(glass, eye, dirs)
        lab = label_wrapped(window)
        sizes = np.bincount(lab.ravel())
        small = np.isin(lab, np.nonzero(sizes < MIN_CELLS)[0])
        window &= ~small
        if not window.any():
            log(f'{vid} eye {idx[n]}: no windows'); continue
        # the frame ring: solid cells touching a window, one cell in; the pane spans window + ring
        ring = ndimage.binary_dilation(window, structure=np.ones((3, 3))) & ~window
        # the ring's own depth is the bar's (or the wall's) inner face, so the ring is filled in too: the pane
        # continues smoothly one cell under the frame instead of spiking in to the wall
        unknown = window | ring | ~(d < OPEN)
        known_depth = np.where(unknown, np.nan, d)
        filled = fill(np.where(np.isnan(known_depth), np.nanmean(known_depth), known_depth), unknown)
        r = filled - OFFSET
        P = eye + dirs * r[..., None]
        pane = window | ring
        count = 0
        for j in range(len(el) - 1):
            for i in range(len(az)):
                i2 = (i + 1) % len(az)
                if not (pane[j, i] and pane[j, i2] and pane[j + 1, i] and pane[j + 1, i2]):
                    continue
                if not (window[j, i] or window[j, i2] or window[j + 1, i] or window[j + 1, i2]):
                    continue
                a, b, c_, e = P[j, i], P[j, i2], P[j + 1, i2], P[j + 1, i]
                glass.append(np.array([a, b, c_])); glass.append(np.array([a, c_, e])); count += 2
        log(f'{vid} eye {idx[n]}: {int(window.sum())} window cells in {len(np.unique(lab[window]))} windows, {count} triangles')
    return glass


def block(tris):
    flat = [round(float(v), 4) for t in tris for p in t for v in p]
    return {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': flat}


def main(argv):
    out = next((a[6:] for a in argv if a.startswith('--out=')), None)
    eyes = next((a[7:] for a in argv if a.startswith('--eyes=')), None)
    eyes = [int(x) for x in eyes.split(',')] if eyes else None
    ids = [a for a in argv if not a.startswith('--')]
    for vid in ids:
        glass = panes(vid, eyes)
        b = block(glass)
        if out:
            os.makedirs(out, exist_ok=True)
            json.dump(b, open(os.path.join(out, vid + '.json'), 'w'))
        if '--write' in argv:
            path = os.path.join(ASSET_DIR, vid + '.json')
            asset = json.load(open(path))
            asset['CanopyGlass'] = b
            sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
            import cockpit_lib as L
            L.write_asset(path, asset)
        print(f'{vid}: {len(glass)} glass triangles')


if __name__ == '__main__':
    main(sys.argv[1:])
