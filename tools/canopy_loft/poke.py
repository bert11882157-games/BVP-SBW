"""Diagnostic: where does model geometry stand above the lofted glass? For each aircraft, every model vertex within the
glass plan (|x| < W(z)) is compared with the glass surface height at that (x, z); prints the worst pokes.
Usage: python poke.py [--json=out.json] id..."""
import json, sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import loft as Lm
import evidence as E

def surface_height(L, x, z):
    k = int(np.clip(np.searchsorted(L.z, z), 1, len(L.z) - 1))
    f = (z - L.z[k - 1]) / max(L.z[k] - L.z[k - 1], 1e-9)
    ys = []
    for kk in (k - 1, k):
        W = L.W[kk]
        if W < 1e-3:
            ys.append(L.Yb[kk]); continue
        t = float(np.clip(abs(x) / W, 0, 1))
        ys.append(L.point(kk, t)[1])
    return ys[0] * (1 - f) + ys[1] * f

def pokes(vid, cfg):
    L = Lm.Loft(vid, cfg)
    tris = L.ev.tris if hasattr(L.ev, 'tris') else None
    if tris is None:
        import ortho as O
        tris, _, _ = O.model(vid)
    P = tris.reshape(-1, 3)
    m = (P[:, 2] > L.zr) & (P[:, 2] < L.zf)
    P = P[m]
    Wz = np.interp(P[:, 2], L.z, L.W)
    P = P[np.abs(P[:, 0]) < Wz * 0.75]
    out = []
    for p in P[np.argsort(-P[:, 1])][:4000]:
        h = surface_height(L, p[0], p[2])
        if p[1] > h + 0.01:
            out.append((float(p[1] - h), float(p[0]), float(p[1]), float(p[2])))
    out.sort(reverse=True)
    return L, out

if __name__ == '__main__':
    cfgs = json.load(open(Lm.CONFIG))
    res = {}
    for vid in [a for a in sys.argv[1:] if not a.startswith('--')]:
        try:
            L, o = pokes(vid, cfgs.get(vid, {}))
        except Exception as e:
            print(vid, 'FAILED', repr(e)); continue
        res[vid] = o[:10]
        print(f'{vid:30s} pokes {len(o):4d}  worst ' + ', '.join(f'{d:.3f}@(x{x:.2f},y{y:.2f},z{z:.2f})' for d, x, y, z in o[:3]))
    j = next((a[7:] for a in sys.argv if a.startswith('--json=')), None)
    if j: json.dump(res, open(j, 'w'))
