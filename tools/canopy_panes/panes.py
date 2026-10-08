#!/usr/bin/env python3
"""Flat-pane cockpit glazing: glass in every window opening the crew looks through, flat and flush in its frame.

Helicopter cockpits, transport and bomber flight decks, glazed noses, gunner positions and flat windscreen plates
are built from flat panes held in frames. For every crew eye the alpha-tested model is rendered onto a depth cube map
(the canopy_frames renderer: the view the game draws) and sampled on an azimuth/elevation grid:
  * a cell is OPEN when nothing lies within SHELL of the eye, it does not look back into the vehicle's own cabin,
    and no glass already covers it (the lofted canopy, panes made for an earlier eye);
  * each connected opening is a window; its frame is the ring of solid cells around it, split into planes by
    sequential RANSAC. A fitted plane counts only where the cockpit shell near it lies behind it (so a plane
    slicing through the cockpit or along the panel top is refused); a window with no such plane (a greenhouse of
    thin bars) takes the facets of the convex hull of the whole shell, which seats and consoles cannot shape;
  * along every ray through the window the glass lies on the nearest of its planes, OFFSET beyond the frame's inner
    face so it never z-fights the bars; the window grows by one cell so each pane edge tucks behind its frame;
  * each plane's share of a window becomes one convex pane (a few triangles, straight edges), and a pane standing
    in front of the cockpit's own surfaces, or mostly outside the skin, from every seat is dropped.
Panes are flat by construction. The result is appended to the base glass named in panes.json.

Usage: python panes.py [--write] [--out=file.json] <id>...
"""
import json, math, os, sys
import numpy as np
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_loft'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_glass'))
import thin as T
from audit import ray_hits

CONFIG = os.path.join(HERE, 'panes.json')
SHELL = 1.6           # blocks: a window is a direction with nothing nearer than this
STEP = 1.0            # degrees: grid cell
FAR = 40.0            # blocks: what a window looks out on
TUBE = 1.3            # blocks: half-width of the fuselage tube (cabin walls lie inside it)
EL_MIN = -40.0        # degrees below the horizon (helicopters: HELI_EL_MIN, chin windows)
HELI_EL_MIN = -75.0
INLIER = 0.03         # blocks: frame points this close to a plane belong to it
MIN_INLIERS = 10
MAX_PLANES = 6
OFFSET = 0.008        # blocks beyond the frame's inner face
MIN_CELLS = 6         # smaller openings are gaps between parts, not windows
PANE_MIN_AREA = 0.002  # square blocks
SUPPORT_R = 0.6       # blocks round a fitted pane in which the shell must lie behind it
SUPPORT_TOL = 0.03
SUPPORT_OUT = 0.03    # share of those shell points allowed beyond it
CORNER_GAP = 0.25    # blocks: farthest a pane corner may lie from the cockpit shell
BURIED_MAX = 0.5     # share of a pane allowed behind the cockpit walls from every seat
GRAZE = 0.2           # cosine: rays meeting a pane flatter than this do not shape it
FLOAT_MAX = 0.15     # share of a pane allowed in front of the cockpit's own surfaces
T.FACE = 360


def grid(el_min):
    az = np.arange(-180.0, 180.0, STEP)
    el = np.arange(el_min, 89.0, STEP)
    return az, el


def dirs_of(az, el):
    a, e = np.radians(az), np.radians(el)
    # vehicle-local: +z forward, +x left, +y up; azimuth positive to the left
    return np.stack([np.cos(e) * np.sin(a), np.sin(e), np.cos(e) * np.cos(a)], -1)


def label_wrapped(mask):
    lab, n = ndimage.label(mask, structure=np.ones((3, 3)))
    # the azimuth wraps: join labels meeting across -180/180
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
    lab = roots[lab]
    return lab, sorted(set(lab[lab > 0].tolist()))


def fit_planes(P, eye, rng):
    """Sequential RANSAC: planes (n, d) with n.x + d = 0 and n pointing away from the eye."""
    planes = []
    rest = P.copy()
    for _ in range(MAX_PLANES):
        if len(rest) < MIN_INLIERS:
            break
        best, best_in = None, None
        for _ in range(400):
            a, b, c = rest[rng.choice(len(rest), 3, replace=False)]
            n = np.cross(b - a, c - a)
            ln = np.linalg.norm(n)
            if ln < 1e-6:
                continue
            n /= ln
            inl = np.abs((rest - a) @ n) < INLIER
            if best_in is None or inl.sum() > best_in.sum():
                best, best_in = (n, a), inl
        if best_in is None or best_in.sum() < MIN_INLIERS:
            break
        Q = rest[best_in]
        c = Q.mean(0)
        n = np.linalg.svd(Q - c)[2][-1]
        if n @ (c - eye) < 0:
            n = -n
        dist = n @ (c - eye)
        if 0.12 < dist < SHELL:
            planes.append((n, -n @ c, Q))
        rest = rest[~(np.abs((rest - c) @ n) < INLIER)]
    return planes


def crew(vid, kind, cfg):
    e = T.eyes(vid)
    if 'eyes' in cfg:
        return [e[i] for i in cfg['eyes'] if i < len(e)]
    reach = 4.0 if kind == 'Helicopter' else 2.5
    out = [e[0]]
    for x in e[1:2]:
        # a second crew eye at about the pilot's height (not a cabin or door-gun seat down in the hull)
        if np.linalg.norm(x - e[0]) < reach and abs(x[1] - e[0][1]) < 1.2 and \
                all(np.linalg.norm(x - y) > 0.2 for y in out):
            out.append(x)
    out += [np.array(p, float) for p in cfg.get('extra_eyes', [])]
    return out


def panes(vid, kind, cfg, base):
    _, geo = T.load_geo(vid)
    tris, uvs = T.triangles(geo)
    tex = T.load_texture(vid)
    rng = np.random.default_rng(7)
    glass = [t for t in base]
    out = []
    el_min = cfg.get('el_min', HELI_EL_MIN if kind == 'Helicopter' else EL_MIN)
    az, el = grid(el_min)
    AZ, EL = np.meshgrid(az, el)
    D = dirs_of(AZ, EL)
    # cell corners
    AZc, ELc = np.meshgrid(np.append(az, 180.0) - STEP / 2, np.append(el, el[-1] + STEP) - STEP / 2)
    Dc = dirs_of(AZc, ELc)
    skip = cfg.get('skip_az', [])   # [[az0, az1, el0, el1], ...] openings meant to stay open (doors)
    eyes = crew(vid, kind, cfg)
    cubes = [T.render_depth(tris, uvs, tex, eye) for eye in eyes]
    deps = [T.lookup(c, D.reshape(-1, 3)).reshape(D.shape[:2]) for c in cubes]
    # the cockpit shell: everything the crew sees within SHELL. Its convex hull runs over the frames and the skin
    # round every window (seats, consoles and the other crew stand inside it and cannot shape it); its facets, merged
    # where coplanar, are the panes' planes
    shell = np.vstack([(eye + D * dp[..., None])[dp <= SHELL] for eye, dp in zip(eyes, deps)])
    from scipy.spatial import ConvexHull
    hull = ConvexHull(shell)
    planes = merge_facets(hull.equations)
    for eye, dep in zip(eyes, deps):
        covered = np.zeros(dep.shape, bool)
        if glass:
            g = np.array(glass)
            covered = (ray_hits(g, eye, D.reshape(-1, 3), SHELL * 2) < np.minimum(dep.reshape(-1), SHELL * 2)).reshape(dep.shape)
        opening = (dep > SHELL) & ~covered
        # a window leads outside: looking through it you see sky or parts standing off the fuselage (wings, engines,
        # rotors); an opening into the cabin behind a flight deck ends on the fuselage's own walls inside the tube
        opening &= ~cabin(tris, uvs, tex, eye, D.reshape(-1, 3), cfg).reshape(dep.shape)
        for a0, a1, e0, e1 in skip:
            opening &= ~((AZ >= a0) & (AZ <= a1) & (EL >= e0) & (EL <= e1))
        lab, ids = label_wrapped(opening)
        win = np.isin(lab, [k for k in ids if (lab == k).sum() >= MIN_CELLS])
        # window planes: the hull's facets, plus planes fitted to each window's frame ring that support the shell
        # locally (no shell point near the window stands out beyond them, so a plane slicing through the cockpit
        # or along the panel top is refused); on a curved nose the fitted frame planes are the flat panes
        solid = dep <= SHELL
        P = eye + D * np.where(np.isfinite(dep), dep, 0)[..., None]
        groups = []
        Dflat = D.reshape(-1, 3)
        for k in ids:
            w = lab == k
            if w.sum() < MIN_CELLS:
                continue
            ring = ndimage.binary_dilation(w, np.ones((3, 3)), iterations=2) & solid
            pts = P[ring]
            local = []
            if len(pts) >= MIN_INLIERS:
                for n, o, Q in fit_planes(pts, eye, rng):
                    c = Q.mean(0)
                    near = shell[np.linalg.norm(shell - c, axis=1) < SUPPORT_R]
                    if len(near) and ((near @ n + o) > SUPPORT_TOL).mean() < SUPPORT_OUT:
                        local.append((n, o, None))
            # the window's own frame planes; a window with none (a greenhouse of thin bars) takes the hull's facets
            wp = local or planes
            # a pane reaches no farther from the eye than the window's frame does (grazing rays run off along the plane)
            reach = 1.15 * np.linalg.norm(pts - eye, axis=1).max() if len(pts) else SHELL
            N = np.array([p[0] for p in wp]); off = np.array([p[1] for p in wp])
            cells = ndimage.binary_dilation(w, np.ones((3, 3))) & ~covered & (dep > 0.3)
            idx = np.nonzero(cells.reshape(-1))[0]
            nd = Dflat[idx] @ N.T
            t = np.where(nd > 1e-3, -(eye @ N.T + off) / np.where(nd > 1e-3, nd, 1), np.inf)
            t[t <= 0] = np.inf
            ex = np.full(cells.size, -1)
            good = t.min(1) < SHELL * 1.3
            ex[idx[good]] = t[good].argmin(1)
            ex = ex.reshape(cells.shape)
            for pi in np.unique(ex[ex >= 0]):
                sub, ns = ndimage.label(ex == pi, np.ones((3, 3)))
                groups += [(wp[pi], sub == s, reach) for s in range(1, ns + 1)]
        for (n, o, _), grp, reach in groups:
            for _once in (0,):
                cj, ci = np.nonzero(grp)
                corners = {(j + dj, i + di) for j, i in zip(cj, ci) for dj in (0, 1) for di in (0, 1)}
                pts_on = []
                for j, i in corners:
                    d = Dc[j, i % len(az)]
                    ndd = n @ d
                    if ndd <= GRAZE:
                        continue
                    tt = (-(n @ eye + o) + OFFSET) / ndd
                    if 0 < tt < min(reach, SHELL * 1.5):
                        pts_on.append(eye + d * tt)
                # a pane is the convex outline of its cells on the plane: flat, straight-edged, a few triangles
                pane = convex_pane(np.array(pts_on), n)
                if pane:
                    out.append(pane); glass.extend(pane)
    # a pane standing in front of the cockpit's own surfaces from any crew seat runs through the interior: drop it
    keep = []
    from scipy.spatial import cKDTree
    shell_tree = cKDTree(surface_points(tris, eyes))
    for pane in out:
        # a pane's corners sit on its frame: one reaching off into empty space is not a window
        corners = np.unique(np.round(np.vstack(pane), 4), axis=0)
        gap = shell_tree.query(corners)[0].max()
        if gap > CORNER_GAP:
            if os.environ.get('PANES_DEBUG'):
                print(f'   drop pane at {np.round(corners.mean(0), 2)}: corner {gap:.2f} off the frame')
            continue
        S = sample(pane)
        bad, buried = 0.0, 1.0
        for eye, dp in zip(eyes, cubes):
            r = S - eye; s = np.linalg.norm(r, axis=1)
            d = T.lookup(dp, r / s[:, None])
            bad = max(bad, float(((d <= SHELL) & (s < d - 0.06)).mean()))
            # mostly hidden behind walls from every seat: it stands outside the skin, not in a window
            buried = min(buried, float((s > d + 0.06).mean()))
        if bad < FLOAT_MAX and buried < BURIED_MAX:
            keep.extend(pane)
        elif os.environ.get('PANES_DEBUG'):
            c = np.mean([t.mean(0) for t in pane], 0)
            print(f'   drop pane at {np.round(c, 2)} area {sum(np.linalg.norm(np.cross(t[1]-t[0], t[2]-t[0]))/2 for t in pane):.3f} floating {bad:.2f} buried {buried:.2f}')
    return keep


def surface_points(tris, eyes, spacing=0.03, reach=3.0):
    """The model's surfaces round the crew, sampled every `spacing` blocks (frame bars included, however thin)."""
    near = np.zeros(len(tris), bool)
    for e in eyes:
        near |= np.linalg.norm(tris - e, axis=2).min(1) < reach
    out = []
    for a, b, c in tris[near]:
        n = int(min(40, max(1, math.ceil(max(np.linalg.norm(b - a), np.linalg.norm(c - a), np.linalg.norm(c - b)) / spacing))))
        for i in range(n + 1):
            for j in range(n + 1 - i):
                out.append(a + (b - a) * (i / n) + (c - a) * (j / n))
    return np.array(out) if out else np.zeros((1, 3))


def sample(pane, n=6):
    pts = []
    for a, b, c in pane:
        for i in range(n + 1):
            for j in range(n + 1 - i):
                u, v = i / n, j / n
                pts.append(a + (b - a) * u + (c - a) * v)
    return np.array(pts)


def merge_facets(eq, ang=1.5, gap=0.006):
    """Coplanar hull facets (Qhull triangulates) as one plane each: [(n, offset, None)] with n pointing out."""
    planes = []
    for e in eq:
        n, o = e[:3], e[3]
        for p in planes:
            if p[0] @ n > math.cos(math.radians(ang)) and abs(p[1] - o) < gap:
                break
        else:
            planes.append((n.copy(), float(o), None))
    return planes


def cabin(tris, uvs, tex, eye, dirs, cfg):
    """Directions looking back into the vehicle's own cabin (ending on its walls inside the fuselage tube, behind
    the crew) rather than out of a window."""
    near0 = T.NEAR_TRIS
    T.NEAR_TRIS = FAR
    try:
        far = T.lookup(T.render_depth(tris, uvs, tex, eye), dirs)
    finally:
        T.NEAR_TRIS = near0
    p = eye + dirs * np.where(np.isfinite(far), far, 0)[:, None]
    tube = cfg.get('tube', TUBE)
    return np.isfinite(far) & (np.abs(p[:, 0]) < tube) & (np.abs(p[:, 1] - eye[1]) < tube * 1.2) & \
        (p[:, 2] < eye[2] - 0.5)


def holes(vid, kind, cfg, tris_all):
    """Share of each crew eye's view (same elevation range) that is open and not glazed."""
    import audit as A
    _, geo = T.load_geo(vid)
    tris, uvs = T.triangles(geo)
    tex = T.load_texture(vid)
    el_min = cfg.get('el_min', HELI_EL_MIN if kind == 'Helicopter' else EL_MIN)
    dirs = A.directions(8000, el_min)
    g = np.array(tris_all) if len(tris_all) else None
    out = []
    for eye in crew(vid, kind, cfg):
        d = T.lookup(T.render_depth(tris, uvs, tex, eye), dirs)
        open_ = (d > SHELL) & ~cabin(tris, uvs, tex, eye, dirs, cfg)
        tg = A.ray_hits(g, eye, dirs, SHELL * 2)
        gl = tg < np.minimum(d, SHELL * 2)
        # glass standing in front of the cockpit's own surfaces (a pane through the interior)
        floating = (d <= SHELL) & (tg < d - 0.06)
        out.append((float((open_ & ~gl).mean()), float(floating.mean())))
    return out


def convex_pane(pts, n):
    if len(pts) < 3:
        return []
    from scipy.spatial import ConvexHull
    u = np.cross(n, [0.0, 1.0, 0.0] if abs(n[1]) < 0.9 else [1.0, 0.0, 0.0]); u /= np.linalg.norm(u)
    v = np.cross(n, u)
    c = pts.mean(0)
    q = np.stack([(pts - c) @ u, (pts - c) @ v], -1)
    try:
        h = ConvexHull(q)
    except Exception:
        return []
    if h.volume < PANE_MIN_AREA:
        return []
    ring = pts[h.vertices]
    # drop corners that barely turn the outline (the grid makes many): fewer, cleaner triangles
    changed = True
    while changed and len(ring) > 3:
        changed = False
        for k in range(len(ring)):
            a, b, c2 = ring[k - 1], ring[k], ring[(k + 1) % len(ring)]
            ab, bc = b - a, c2 - b
            la, lb = np.linalg.norm(ab), np.linalg.norm(bc)
            if la < 1e-6 or lb < 1e-6 or np.linalg.norm(np.cross(ab, bc)) / (la * lb) < math.sin(math.radians(4)) \
                    or min(la, lb) < 0.012:
                # removing a convex hull corner only shrinks the pane by a sliver under the frame
                ring = np.delete(ring, k, 0); changed = True
                break
    # project onto the exact plane (the points already lie on it up to rounding)
    ring = ring - np.outer((ring - c) @ n, n)
    return [np.array([ring[0], ring[k], ring[k + 1]]) for k in range(1, len(ring) - 1)]


def base_glass(vid, cfg):
    mode = cfg.get('base', 'none')
    if mode in ('loft', 'windscreen'):
        import loft as Lm
        lcfg = json.load(open(Lm.CONFIG)).get(vid, {})
        lcfg = dict(lcfg, **cfg.get('loft', {}))
        lcfg.pop('skip', None)
        if mode == 'windscreen' and 'front' not in cfg.get('loft', {}):
            # flat windscreen: the dome ends at the windscreen bow (the foremost frame crossing the centre line
            # ahead of the crew) and the flat panes glaze the windscreen in front of it
            L = Lm.Loft(vid, lcfg)
            c = np.array([p for f in L.ev.frames for p in f if abs(p[0]) < 0.06 and L.zc < p[2] < L.zf - 0.1])
            if len(c):
                # the bow arch crosses the centre line high up; the windscreen's lower frame runs down to the nose
                top = c[c[:, 1] >= c[:, 1].max() - 0.08]
                lcfg['front'] = float(top[:, 2].max()) + 0.02
                lcfg['windscreen_base'] = 0.9
            L = Lm.Loft(vid, lcfg)
            # the dome stays open at the bow (its closing strip would wall off the windscreen)
            zcut = L.z[-2] + 1e-6
            return [np.array(t) for t in L.mesh() if np.max(np.array(t)[:, 2]) < zcut]
        return [np.array(t) for t in Lm.Loft(vid, lcfg).mesh()]
    if mode == 'glass':
        import glass as G
        _, block = G.glass(vid)
        return [] if not block else list(np.array(block['Triangles'], float).reshape(-1, 3, 3))
    return []


def block(tris):
    flat = [round(float(v), 4) for t in tris for p in t for v in p]
    return {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': flat}


def main(argv):
    cfgs = json.load(open(CONFIG))
    from audit import aircraft
    kinds = dict(aircraft())
    ids = [a for a in argv if not a.startswith('--')] or [k for k in cfgs if not k.startswith('_')]
    out = next((a[6:] for a in argv if a.startswith('--out=')), None)
    results = {}
    for vid in ids:
        cfg = cfgs.get(vid, {})
        try:
            base = base_glass(vid, cfg)
            p = panes(vid, kinds.get(vid, 'Airplane'), cfg, base)
        except Exception as e:
            import traceback; traceback.print_exc()
            print(f'{vid:34s} FAILED {e!r}', flush=True); continue
        results[vid] = block(list(base) + p)
        left = holes(vid, kinds.get(vid, 'Airplane'), cfg, list(base) + p)
        print(f'{vid:34s} base {len(base):5d}  panes {len(p):5d} triangles  holes/floating ' +
              ' | '.join(f'{h:.3f}/{f:.3f}' for h, f in left), flush=True)
        if '--write' in argv:
            import glass as G
            G.write_block(vid, results[vid])
    if out:
        json.dump(results, open(out, 'w'))


if __name__ == '__main__':
    main(sys.argv[1:])
