"""Canopy and window glass for one aircraft: the hood over an open cockpit (fit.py, laid over its frames by hull.py)
and a pane in every other framed opening the crew look out through (panes.py), each pane only where no glass is yet.

python3 glass.py VID... [--ref REV] [--out DIR] [--write]   (--write puts it in the vehicle's asset file)"""
import sys, os, json, time
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import model as M
import fit as FT
import panes as PN
import hull as H

FLAT = 0.015        # a window whose frame lies within this (rms) of a plane is glazed flat
SKY = 0.35          # the hood is built when the crew see out over this share of the view above 30 degrees


def build(vid, ref=None, log=print):
    t0 = time.time()
    m = M.Model(vid, ref)
    eyes = [np.array(e) for e in m.eyes()]
    if not eyes:
        raise RuntimeError('no crew seats')
    # the pilot (front-most) first
    eyes.sort(key=lambda e: -e[2])
    maps = []
    hood = None; parts = []; sheets = []
    sky = 0.0
    for e in eyes:
        em = PN.EyeMap(m, e)
        up = em.el > 30
        sky = max(sky, float(em.out[up].mean()))      # through the open hood, between its frames and bars
        maps.append(em)
    if sky >= SKY:
        try:
            f = FT.Fit(vid, ref, log=log, model=m)
            hood = f.build()
            parts.append(hood); sheets.append(f.sheet + (True,))
            log(f'  hood: {len(hood)} triangles')
        except RuntimeError as ex:
            log(f'  no hood: {ex}')
    else:
        log(f'  no open sky over the crew ({sky:.0%}): windows only')
    for em in maps:
        for k, P, R in PN.window_panes(em, log=log):
            cover = _covered(sheets, em, k)
            if cover == 'covered':
                continue
            V, F = PN.membrane_mesh(P, R)
            if len(F) == 0:
                continue
            # one clean pane on the window's plane or gentle curve, out to its whole frame; else the membrane, thinned
            clean = PN.flush_pane(m, em, k, V, log=log)
            if clean is None:
                clean = PN.pane(m, em, k, V, log=log, flat=FLAT)
            if clean is not None and np.ptp(clean[0], axis=0).max() > 1.5 * np.ptp(V, axis=0).max() + 0.1:
                log(f'    window {k}: clean pane ran far past its opening, the membrane kept'); clean = None
            if clean is not None:
                V, F = clean
            elif cover == 'behind':
                log(f'    window {k}: behind the hood, no clean pane fits its frame: left to the hood')
                continue
            else:
                log(f'    window {k}: no smooth surface fits its frame, the membrane kept')
                V, F = H.decimate(V, F, max(24, len(F) // 25))
            if len(F) == 0:
                continue
            sheets.append((V, F, False))
            parts.append(PN.solidify(V, F, FT.THICK, np.tile(em.eye, (len(V), 1)) - V))
            log(f'  window {k} from the seat at z {em.eye[2]:.2f}: {len(F)} faces')
    T = np.concatenate(parts) if parts else np.zeros((0, 3, 3))
    log(f'  {len(T)} triangles, {time.time() - t0:.0f}s')
    return T, eyes


def _under(m, p):
    h = m.cast((p[0], p[1] + 3.0, p[2]), (0, -1, 0), maxd=3.0)
    return h is not None and h[1][1] > p[1] + 0.01


def _covered(sheets, em, k, share=0.5, inside=0.05):
    """'covered' when most of the window's opening is already seen through glass built before it: a pane, or the hood
    at or outside the window's frame; 'behind' when the hood lies well inside the frame, leaving the window open to
    the air (it is glazed when a clean pane fits it); else 'open'"""
    if not sheets:
        return 'open'
    from mathutils import Vector
    from mathutils.bvhtree import BVHTree
    C = em.cells(k)
    ring = em.ring(C) & ~np.isnan(em.D)
    frame = float(np.median(em.D[ring])) if ring.any() else 0.0
    ds = em.d[C][::max(1, int(C.sum()) // 200)]
    hit = np.zeros(len(ds), bool); outside = np.zeros(len(ds), bool)
    for V, F, hood in sheets:
        tree = BVHTree.FromPolygons([Vector(v) for v in V], [tuple(int(i) for i in f) for f in F], all_triangles=True)
        for i, d in enumerate(ds):
            h = tree.ray_cast(Vector(em.eye), Vector(d), 2 * em.near)
            if h[0] is not None:
                hit[i] = True
                outside[i] |= not hood or h[3] >= frame - inside
    if outside.sum() >= share * len(ds):
        return 'covered'
    return 'behind' if hit.sum() >= share * len(ds) else 'open'


def to_json(T):
    return {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
            'Triangles': [round(float(v), 4) for v in np.asarray(T).reshape(-1)]}


def write(vid, T):
    """the glass into the vehicle's asset file in the working tree (on one line, the rest of the file as it was)"""
    path = os.path.join(M.REPO, M.ASSET.format(vid))
    raw = open(path, encoding='utf-8').read()
    doc = json.loads(raw)
    doc.pop('CanopyGlass', None)
    marker = '"__CANOPY_GLASS__"'
    if len(T):
        doc['CanopyGlass'] = marker[1:-1]
    text = json.dumps(doc, indent=2 if raw.startswith('{\n') else None, ensure_ascii=False)
    if len(T):
        text = text.replace(marker, json.dumps(to_json(T), separators=(',', ':')))
    open(path, 'w', encoding='utf-8').write(text + ('\n' if raw.endswith('\n') else ''))


if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument('vids', nargs='+'); ap.add_argument('--ref', default=None); ap.add_argument('--out', default=None)
    ap.add_argument('--write', action='store_true')
    a = ap.parse_args()
    for vid in a.vids:
        print(vid)
        T, eyes = build(vid, a.ref)
        if a.out:
            os.makedirs(a.out, exist_ok=True)
            np.savez(os.path.join(a.out, vid + '.npz'), outer=T, E=np.array(eyes))
        if a.write:
            write(vid, T)
