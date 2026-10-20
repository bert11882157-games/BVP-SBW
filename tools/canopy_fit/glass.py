"""Canopy and window glass for one aircraft: the lofted hood over an open cockpit (tools/canopy_loft's, read from the
glass the aircraft had at LOFT_REF; fit.py's hood where it had none) and a pane in every other framed opening the crew
look out through (panes.py), each pane only where no glass is yet; then, where the glass was pieced together, a pane
in every sliver the crew still see out through between the glass and the frames.

python3 glass.py VID... [--ref REV] [--out DIR] [--write]   (--write puts it in the vehicle's asset file)"""
import sys, os, json, time, subprocess, copy
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import model as M
import fit as FT
import panes as PN
import hull as H

FLAT = 0.015        # a window whose frame lies within this (rms) of a plane is glazed flat
SKY = 0.35          # the hood is built when the crew see out over this share of the view above 30 degrees
LOFT_REF = '33fae4f'    # the lofted hoods tools/canopy_loft built, as the aircraft carried them before this tool
WHOLE_LOFT = {'ju_87_b2'}   # lofts kept in all their pieces (a greenhouse cut between its frames), not only the largest
NO_SEAL = {'b_1b'}      # slivers left unsealed: the B-1B's, under its windscreen, only take ragged panes
FULL = 0.9          # a window is left to the loft when the loft is seen through this share of it
PART = 0.15         # a broken loft seen through more than this of a window: the window gets its own pane instead
SEAL_EDGE = 0.15    # a sliver is sealed when at least this share of its edge is glass ...
SEAL_EL = -30.0     # ... and it lies above this elevation (lower, it is a hole in the floor, not a gap in the glass)


def build(vid, ref=None, log=print, loft_ref=LOFT_REF):
    t0 = time.time()
    m = M.Model(vid, ref)
    eyes = [np.array(e) for e in m.eyes()]
    if not eyes:
        raise RuntimeError('no crew seats')
    # the pilot (front-most) first
    eyes.sort(key=lambda e: -e[2])
    maps = [PN.EyeMap(m, e) for e in eyes]
    sky = max(float(em.out[em.el > 30].mean()) for em in maps)      # through the open hood, between its frames and bars
    parts = []; sheets = []; hood = None; whole = True
    T0 = loft(vid, loft_ref) if loft_ref else np.zeros((0, 3, 3))
    if sky >= SKY and len(T0):
        # the loft is cut where it meets frames and skin: its largest piece is the hood, the rest are shards of
        # windscreen, which get panes of their own
        lab = pieces(T0)
        a = np.linalg.norm(np.cross(T0[:, 1] - T0[:, 0], T0[:, 2] - T0[:, 0]), axis=1) / 2
        L = T0 if vid in WHOLE_LOFT else T0[lab == np.argmax(np.bincount(lab, weights=a))]
        whole = len(L) == len(T0)
        V, F = weld(L)
        hood = [V, orient(V, F, eyes)]
        log(f'  hood: the loft, {len(L)} of {len(T0)} triangles')
    elif sky >= SKY:
        try:
            f = FT.Fit(vid, ref, log=log, model=m)
            T = f.build()
            parts.append(T); sheets.append(f.sheet + (True,))
            log(f'  hood: {len(T)} triangles')
        except RuntimeError as ex:
            log(f'  no hood: {ex}')
    else:
        log(f'  no open sky over the crew ({sky:.0%}): windows only')
    panes = []
    for em in maps:
        for k, P, R in PN.window_panes(em, log=log):
            part = False
            if hood is not None:
                hit, outside = _loft_share(hood, em, k)
                if max(hit, outside) >= FULL or (whole and max(hit, outside) >= 0.5):
                    continue
                part = hit > PART and not whole
            cover = _covered(sheets + [(Vp, Fp, False) for Vp, Fp in panes], em, k)
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
            if part:
                # the loft's shard of this window goes; so does any of the loft in front of the window's frame
                ring = em.ring(em.cells(k)) & ~np.isnan(em.D)
                frame = float(np.percentile(em.D[ring], 10)) if ring.any() else 0.0
                cut = _in_window(em, k, hood[0], frame - 0.03)[hood[1]].all(1)
                hood[1] = hood[1][~cut]
                log(f'    window {k}: the loft reaches {hit:.0%} of it: its own pane, {int(cut.sum())} loft faces in front taken off')
            panes.append((V, F))
            parts.append(PN.solidify(V, F, FT.THICK, np.tile(em.eye, (len(V), 1)) - V))
            log(f'  window {k} from the seat at z {em.eye[2]:.2f}: {len(F)} faces')
    if hood is not None:
        # one sheet, as the loft was: walls along its rim would show from inside as bright streaks round the rails
        V, F = hood
        parts.insert(0, V[F])
        log(f'  hood: {len(F)} faces')
    T = np.concatenate(parts) if parts else np.zeros((0, 3, 3))
    # a whole loft already closes on its frames and sills: the slivers are sealed where the glass was pieced together
    for em in maps if (hood is None or not whole) and vid not in NO_SEAL else []:
        extra = seal(m, em, T, log)
        if extra:
            T = np.concatenate([T] + extra)
    log(f'  {len(T)} triangles, {time.time() - t0:.0f}s')
    return T, eyes


def loft(vid, ref):
    """the CanopyGlass the vehicle carried at ref, as triangles"""
    r = subprocess.run(['git', '-C', M.REPO, 'show', f'{ref}:{M.ASSET.format(vid)}'], capture_output=True, text=True)
    g = json.loads(r.stdout).get('CanopyGlass') if r.returncode == 0 else None
    t = g.get('Triangles') if g and g.get('Schema') == 1 else None
    return np.array(t, float).reshape(-1, 3, 3) if t else np.zeros((0, 3, 3))


def pieces(T, tol=1e-4):
    """a label per triangle: triangles sharing a corner are one piece"""
    from scipy.sparse import coo_matrix
    from scipy.sparse.csgraph import connected_components
    _, inv = np.unique(np.round(T.reshape(-1, 3) / tol).astype(np.int64), axis=0, return_inverse=True)
    n = len(T); nv = inv.max() + 1
    A = coo_matrix((np.ones(3 * n), (np.repeat(np.arange(n), 3), inv.ravel() + n)), shape=(n + nv, n + nv))
    return connected_components(A + A.T, directed=False)[1][:n]


def weld(T, tol=1e-4):
    """a triangle soup as an indexed sheet (corners within tol are one vertex)"""
    P = T.reshape(-1, 3)
    _, idx, inv = np.unique(np.round(P / tol).astype(np.int64), axis=0, return_index=True, return_inverse=True)
    F = inv.reshape(-1, 3)
    return P[idx], F[(F[:, 0] != F[:, 1]) & (F[:, 1] != F[:, 2]) & (F[:, 0] != F[:, 2])]


def orient(V, F, eyes):
    """every face turned to face away from its nearest eye"""
    C = V[F].mean(1)
    E = np.asarray(eyes)
    e = E[np.argmin(np.linalg.norm(C[:, None] - E[None], axis=2), axis=1)]
    n = np.cross(V[F[:, 1]] - V[F[:, 0]], V[F[:, 2]] - V[F[:, 0]])
    F = F.copy()
    flip = np.sum(n * (C - e), axis=1) < 0
    F[flip] = F[flip][:, ::-1]
    return F


def _tree(V, F):
    from mathutils import Vector
    from mathutils.bvhtree import BVHTree
    return BVHTree.FromPolygons([Vector(v) for v in V], [tuple(int(i) for i in f) for f in F], all_triangles=True)


def _loft_share(hood, em, k, inside=0.05):
    """(share of window k seen through the loft, share seen through it at or past the window's frame)"""
    from mathutils import Vector
    C = em.cells(k)
    ring = em.ring(C) & ~np.isnan(em.D)
    frame = float(np.median(em.D[ring])) if ring.any() else 0.0
    ds = em.d[C][::max(1, int(C.sum()) // 300)]
    tree = _tree(*hood)
    hit = out = 0
    for d in ds:
        h = tree.ray_cast(Vector(em.eye), Vector(d), 2 * em.near)
        if h[0] is not None:
            hit += 1; out += h[3] >= frame - inside
    return hit / len(ds), out / len(ds)


def _in_window(em, k, P, beyond):
    """points P the eye sees through window k (its cells grown by one), further away than beyond"""
    from scipy import ndimage
    G = ndimage.binary_dilation(np.pad(em.cells(k), ((0, 0), (2, 2)), mode='wrap'), structure=np.ones((3, 3), bool))[:, 2:-2]
    d = P - em.eye
    az, el = PN.to_azel(d)
    i = np.clip(np.round((el - em.el[0]) / PN.STEP).astype(int), 0, len(em.el) - 1)
    j = np.round((az - em.az[0]) / PN.STEP).astype(int) % len(em.az)
    return G[i, j] & (np.linalg.norm(d, axis=1) > beyond)


def seal(m, em, T, log=print):
    """panes for what the eye still sees out through past the glass: each such sliver is a window framed partly by
    the airframe and partly by the edge of the glass already built"""
    from mathutils import Vector
    if len(T) == 0:
        return []
    tree = _tree(T.reshape(-1, 3), np.arange(3 * len(T)).reshape(-1, 3))
    e2 = copy.copy(em)
    e2.out = em.out.copy(); e2.D = em.D.copy(); e2.P = em.P.copy()
    for i, j in zip(*np.nonzero(em.out)):
        h = tree.ray_cast(Vector(em.eye), Vector(em.d[i, j]), 8.0)
        if h[0] is not None:
            e2.out[i, j] = False; e2.D[i, j] = h[3]; e2.P[i, j] = tuple(h[0])
    e2.lab, e2.n = em._label(e2.out)
    top = e2.lab[-1][e2.out[-1]]
    e2.outer = int(np.bincount(top).argmax()) if len(top) else 0
    glass = em.out & ~e2.out
    _, E = np.meshgrid(em.az, em.el)
    parts = []
    for k, P, R in PN.window_panes(e2, log=lambda *a: None):
        C = e2.cells(k)
        ring = e2.ring(C)
        edge = (ring & glass).sum() / max(1, ring.sum())
        el = float(np.median(E[C]))
        if edge < SEAL_EDGE or el < SEAL_EL:
            continue
        V, F = PN.membrane_mesh(P, R)
        if len(F) == 0:
            continue
        # only a clean pane: a membrane stretched across a sliver comes out ragged
        clean = PN.pane(m, e2, k, V, log=lambda *a: None, flat=FLAT)
        if clean is None or np.ptp(clean[0], axis=0).max() > 1.5 * np.ptp(V, axis=0).max() + 0.1:
            continue
        V, F = clean
        if len(F) == 0:
            continue
        parts.append(PN.solidify(V, F, FT.THICK, np.tile(em.eye, (len(V), 1)) - V))
        log(f'  sliver from the seat at z {em.eye[2]:.2f} sealed: {int(C.sum())} cells at {el:.0f} degrees, {len(F)} faces')
    return parts


def _covered(sheets, em, k, share=0.5, inside=0.05):
    """'covered' when most of the window's opening is already seen through glass built before it: a pane, or the hood
    at or outside the window's frame; 'behind' when the hood lies well inside the frame, leaving the window open to
    the air (it is glazed when a clean pane fits it); else 'open'"""
    if not sheets:
        return 'open'
    from mathutils import Vector
    C = em.cells(k)
    ring = em.ring(C) & ~np.isnan(em.D)
    frame = float(np.median(em.D[ring])) if ring.any() else 0.0
    ds = em.d[C][::max(1, int(C.sum()) // 200)]
    hit = np.zeros(len(ds), bool); outside = np.zeros(len(ds), bool)
    for V, F, hood in sheets:
        tree = _tree(V, F)
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
    ap.add_argument('--loft-ref', default=LOFT_REF, help="where the lofted hoods are read from ('' for fitted hoods)")
    ap.add_argument('--write', action='store_true')
    a = ap.parse_args()
    for vid in a.vids:
        print(vid)
        T, eyes = build(vid, a.ref, loft_ref=a.loft_ref)
        if a.out:
            os.makedirs(a.out, exist_ok=True)
            np.savez(os.path.join(a.out, vid + '.npz'), outer=T, E=np.array(eyes))
        if a.write:
            write(vid, T)
