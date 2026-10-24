"""Mirage IIIE: the exterior skin faces the canopy pass's cut took out, as a restore list for checks/restore_exterior.py.

A face of the untouched model (checks/orig) that is gone from the current geo is skin when its outward normal's ray leaves
the untouched model without hitting any other face (so the old tub walls, the panel boxes and every face that looks into the
cockpit are not), and when it is not part of the old windscreen / canopy frame (it does not stand above the sill line).
Usage: make_restore.py OUT.json"""
import json, os, sys
import numpy as np

REPO = r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\repo'
ORIG = r'C:\Users\udstu\Documents\Superb warfare\work\elite_expansion_2\checks\orig\mirage_3e.geo.json'
CUR = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack', 'custom_geo', 'mirage_3e.geo.json')
sys.path.insert(0, os.path.join(REPO, 'tools', 'cockpit_models'))
import canopy_mirage_3e as CM   # noqa: E402

E = np.array([0.0, CM.EY, CM.EZ])
POST_BOX = ((0.12, -0.60, 0.56), (0.45, -0.10, 1.40))          # eye-relative (|x| lo/hi, y lo/hi, z lo/hi) of the old windscreen side posts
CAP_UNDER = 0.004                                 # the restored ridge stops this far under the rail's top
SK = np.array([1, 1, -1]) / 16.0


def bones(path):
    g = json.load(open(path, encoding='utf-8-sig'))
    return g['minecraft:geometry'][0]['bones']


def faces(bs, own=False):
    out = {}
    for b in bs:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        if not own and (b['name'].endswith('__cockpit') or b['name'].endswith('__canopy')):
            continue
        P = np.array(pm['positions'], float)
        N = np.array(pm.get('normals', [[0, 1, 0]]), float)
        U = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            if not poly:
                continue
            key = tuple(sorted(tuple(np.round(P[v[0]], 2)) for v in poly))
            out[key] = (b['name'], [P[v[0]].tolist() for v in poly], [N[v[1]].tolist() if v[1] < len(N) else [0, 1, 0] for v in poly],
                        [U[v[2]].tolist() for v in poly])
    return out


def main():
    out_path = sys.argv[1]
    org = faces(bones(ORIG))
    cur = faces(bones(CUR))
    # triangles of the untouched model for the escape test
    T = []
    for k, (bn, P, N, U) in org.items():
        Q = np.array(P) * SK
        for i in range(1, len(Q) - 1):
            T.append(Q[[0, i, i + 1]])
    T = np.array(T)
    e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]

    def hits(p, d, tmax=4.0, skip=0.003):
        h = np.cross(d, e2); a = (e1 * h).sum(1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1.0 / np.where(ok, a, 1.0), 0.0)
        s = p - T[:, 0]; u = f * (s * h).sum(1)
        q = np.cross(s, e1); v = f * (q @ d); t = f * (q * e2).sum(1)
        return bool((ok & (u >= -1e-6) & (u <= 1 + 1e-6) & (v >= -1e-6) & (u + v <= 1 + 1e-6) & (t > skip) & (t < tmax)).any())

    keep, gone = [], 0
    for k, (bn, P, N, U) in org.items():
        if k in cur:
            continue
        Q = np.array(P) * SK
        c = Q.mean(0)
        r = c - E
        if abs(r[0]) > 1.0 or not -1.2 < r[2] < 1.6 or r[1] < -1.5:
            continue
        gone += 1
        # the face's own outward normal (stored in model pixels: z mirrored)
        n = np.array(N[0]) * np.array([1, 1, -1])
        n /= np.linalg.norm(n) + 1e-12
        fn = np.cross(Q[1] - Q[0], Q[2] - Q[0]); fn /= np.linalg.norm(fn) + 1e-12
        if fn @ n < 0:
            fn = -fn
        if abs(fn @ n) < 0.9:
            n = fn
        samples = [c] + [c + (q - c) * 0.6 for q in Q] + [(Q[i] + Q[(i + 1) % len(Q)]) / 2 * 0.7 + c * 0.3 for i in range(len(Q))]
        buried = abs(r[0]) >= 0.365                               # in the wall layer outside the new lining (x 0.345): skin shell
        if not buried and all(hits(s + n * 0.004, n) for s in samples):
            continue                                              # looks into something: tub, panel, wall inner face
        z = r[2]
        sill = CM.foot_y(max(min(z, CM.Z_ARCH[0]), CM.Z_FAIR)) if z <= CM.Z_ARCH[0] else CM.sill_y(z)
        if Q[:, 1].max() - E[1] > max(sill + 0.02, -0.285) and abs(r[0]) < CM.foot_x(max(min(z, CM.Z_ARCH[0]), CM.Z_FAIR)) + 0.12:
            continue                                              # the old canopy / windscreen frame and what stood above the sill
        keep.append({'bone': bn, 'P': P, 'N': N, 'U': U})
    # the sill ridge: faces of the original fuselage that the escape test above cannot see (the old frame shadows them) and the
    # ridge's outer wall above the sill line. Outward walls and up-facing tops of the ridge zone go back as in the original,
    # cut off CAP_UNDER under the sill rail's top (the glass foot line), so the skin runs up to the rail with no slit under it
    have = {tuple(sorted(tuple(np.round(np.array(p) * SK, 3)) for p in k_['P'])) for k_ in keep}
    ridge = 0
    for k, (bn, P, N, U) in org.items():
        if k in cur:
            continue
        Q = np.array(P) * SK
        if tuple(sorted(tuple(np.round(q, 3)) for q in Q)) in have:
            continue
        rr = Q - E
        side = 1.0 if rr[:, 0].mean() > 0 else -1.0
        ax = np.abs(rr[:, 0])
        if ax.min() < 0.26 or ax.max() > 0.54 or rr[:, 2].min() < -0.9 or rr[:, 2].max() > 1.4 or rr[:, 1].max() > 0.0 or rr[:, 1].min() < -0.56:
            continue
        fn = np.cross(Q[1] - Q[0], Q[2] - Q[0]); fn /= np.linalg.norm(fn) + 1e-12
        n = np.array(N[0]) * np.array([1, 1, -1]); n /= np.linalg.norm(n) + 1e-12
        if fn @ n < 0:
            fn = -fn
        if abs(fn @ n) < 0.9:
            n = fn
        if not (n[0] * side > 0.9 or n[1] > 0.9) or os.environ.get('MR_RIDGE') == '0':
            continue
        za, zb = rr[:, 2].min(), rr[:, 2].max()
        ca = CM.foot_y(max(min(za, CM.Z_ARCH[0]), CM.Z_FAIR)) - CAP_UNDER
        cb = CM.foot_y(max(min(zb, CM.Z_ARCH[0]), CM.Z_FAIR)) - CAP_UNDER

        def cap(z):
            return ca if zb - za < 1e-4 else ca + (cb - ca) * (z - za) / (zb - za)
        poly = [(rr[i], np.array(U[i], float), np.array(N[i], float)) for i in range(len(rr))]
        out = []
        for i in range(len(poly)):
            a, b = poly[i], poly[(i + 1) % len(poly)]
            da, db = a[0][1] - cap(a[0][2]), b[0][1] - cap(b[0][2])
            if da <= 0:
                out.append(a)
            if (da < 0 < db) or (db < 0 < da):
                t = da / (da - db)
                out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2]))
        if len(out) < 3:
            continue
        pts = np.array([o[0] for o in out])
        if sum(np.linalg.norm(np.cross(pts[j] - pts[0], pts[j + 1] - pts[0])) for j in range(1, len(pts) - 1)) < 1e-7:
            continue
        keep.append({'bone': bn, 'P': [((o[0] + E) / SK).tolist() for o in out], 'N': [o[2].tolist() for o in out],
                     'U': [o[1].tolist() for o in out]})
        ridge += 1
    # the nose piece's rear end: the original has a vertical cap face at the nose piece's rear plane (z 5.102) that closes its
    # shell between the rear fuselage's deck and its own top skin (a 4 cm step); the cut takes it, and the base bar alone does
    # not cover the step
    cap_n = 0
    for k, (bn, P, N, U) in org.items():
        if k in cur or bn != 'wreck_fuselage_0__hull' or os.environ.get('MR_CAP') == '0':
            continue
        Q = np.array(P) * SK
        if np.all(np.abs(Q[:, 2] - (E[2] + 1.3675)) < 0.003) and np.abs(Q[:, 0]).max() < 0.5 and Q[:, 1].min() > E[1] - 0.43:
            fn = np.cross(Q[1] - Q[0], Q[2] - Q[0])
            if np.linalg.norm(fn) < 1e-9:
                continue
            keep.append({'bone': bn, 'P': P, 'N': N, 'U': U})
            cap_n += 1
    print(f'  + {cap_n} faces of the nose piece\'s rear cap go back')
    # the original windscreen side posts: the outward faces of the old frame's side member beside the quarter panes (the modeller's
    # own post section), so the strip's skirt, the bar's end and the arch's foot meet the original post and not a hollow
    post_n = 0
    box_lo = np.array([POST_BOX[0][0], POST_BOX[0][1], POST_BOX[0][2]]); box_hi = np.array(POST_BOX[1])
    have2 = {tuple(sorted(tuple(np.round(np.array(p) * SK, 3)) for p in k_['P'])) for k_ in keep}
    for k, (bn, P, N, U) in org.items():
        if k in cur or os.environ.get('MR_POST') != '1':
            continue
        Q = np.array(P) * SK
        if tuple(sorted(tuple(np.round(q, 3)) for q in Q)) in have2:
            continue
        rr = Q - E
        side = 1.0 if rr[:, 0].mean() > 0 else -1.0
        ax = np.abs(rr[:, 0])
        # the long side walls of the old nose shell under the quarter panes: they run from the old rear of the shell (z 3.955) to the
        # nose piece's rear plane (z 5.102), at least 0.8 m long, below the pane level, |x| 0.17 .. 0.45
        if not (rr[:, 2].max() - rr[:, 2].min() > 0.8 and rr[:, 2].max() <= 1.40 and ax.min() >= 0.17 and ax.max() <= 0.45
                and rr[:, 1].max() <= -0.33 and rr[:, 1].min() >= -0.60):
            continue
        fn = np.cross(Q[1] - Q[0], Q[2] - Q[0]); fn /= np.linalg.norm(fn) + 1e-12
        n = np.array(N[0]) * np.array([1, 1, -1]); n /= np.linalg.norm(n) + 1e-12
        if fn @ n < 0:
            fn = -fn
        if abs(fn @ n) < 0.9:
            n = fn
        keep.append({'bone': bn, 'P': P, 'N': N, 'U': U})
        post_n += 1
    print(f'  + {post_n} outward faces of the original windscreen side posts go back')
    print(f'mirage_3e: {gone} faces gone near the cockpit, {len(keep)} are exterior skin and go back ({ridge} of them sill ridge faces cut off under the rail)')
    json.dump(keep, open(out_path, 'w'))


if __name__ == '__main__':
    main()
