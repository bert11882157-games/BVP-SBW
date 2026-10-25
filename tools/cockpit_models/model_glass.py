#!/usr/bin/env python3
"""Glass for the big aircraft whose windows stay the model's own (no canopy is made: the frames are the exterior).

fit(ac): the model's original window glass (handbuilt_canopy.original_glass, the CanopyGlass the converted model came
with, fitted to its openings) back into the asset as CanopyGlass, without the panes that lie on a frame or skin face
(parallel within 8 degrees, within [gap], overlapping it: zfight_check's glass rule), which would z-fight and which no
crewman sees through anyway. The geometry is not touched.

view(ac, seats): from each crewman's eye, rays over the forward and side view (azimuth -100..100 degrees, elevation
-25..+35): how many first hit the new cockpit (its own parts in the view) and how many leave through the glass, and
the nearest blocked ray's direction.

Usage: model_glass.py VID [--write]   (prints both; --write puts the glass into the vehicle file)
"""
import math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L            # noqa: E402
import handbuilt_canopy as C       # noqa: E402


def _tris(ac, own, near, reach):
    """triangles (vehicle-local) of the new cockpit (own) or of the model, within [reach] of the point [near]"""
    out = []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        mine = b['name'].endswith('__cockpit') or b['name'].endswith('__canopy') or 'stick' in b['name']
        if mine != own:
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for poly in pm['polys']:
            if poly:
                V = P[[v[0] for v in poly]]
                if np.abs(V.mean(0) - near).max() > reach:
                    continue
                for k in range(1, len(V) - 1):
                    out.append(V[[0, k, k + 1]])
    return np.array(out).reshape(-1, 3, 3)


def _overlap2d(a, b):
    def shrink(t):
        c = t.mean(0)
        return c + (t - c) * 0.96
    a, b = shrink(a), shrink(b)
    for t in (a, b):
        for i in range(3):
            e = t[(i + 1) % 3] - t[i]
            n = np.array([-e[1], e[0]])
            pa, pb = a @ n, b @ n
            if pa.max() <= pb.min() or pb.max() <= pa.min():
                return False
    return True


def fit(ac, gap=0.012):
    """the original glass without the panes lying on the model's faces, into ac.asset; returns (kept, dropped)"""
    G = C.original_glass(ac.vid)
    if not len(G):
        return 0, 0
    E = np.array(ac.eye(0))
    B = _tris(ac, False, G.reshape(-1, 3).mean(0), 4.0)
    nb = np.cross(B[:, 1] - B[:, 0], B[:, 2] - B[:, 0])
    lb = np.linalg.norm(nb, axis=1)
    okb = lb > 1e-10
    nb[okb] /= lb[okb, None]
    lo_b, hi_b = B.min(1), B.max(1)
    keep = []
    for g in G:
        n = np.cross(g[1] - g[0], g[2] - g[0])
        ln = np.linalg.norm(n)
        if ln < 1e-10:
            continue
        n = n / ln
        lo, hi = g.min(0) - gap, g.max(0) + gap
        clash = False
        for j in np.where(okb & np.all(lo_b <= hi, axis=1) & np.all(hi_b >= lo, axis=1))[0]:
            if abs(n @ nb[j]) < math.cos(math.radians(8)) or np.abs((B[j] - g[0]) @ n).max() > gap:
                continue
            ax = np.argsort(np.abs(n))[:2]
            if _overlap2d(g[:, ax], B[j][:, ax]):
                clash = True
                break
        if not clash:
            keep.append(g)
    ac.asset.pop('CanopyGlass', None)
    seen, once = set(), []                  # (the original holds both windings; add_glass writes both again)
    for t in keep:
        key = frozenset(tuple(np.round(p, 4)) for p in t)
        if key not in seen:
            seen.add(key)
            once.append(t)
    keep = once
    if keep:
        L.add_glass(ac.asset, [list(map(float, p)) for t in keep for p in t])
    return len(keep), len(G) - len(keep)


def _first_hit(o, d, T):
    """distance along the ray (o, d) to the first of the triangles T, or None"""
    e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]
    h = np.cross(d, e2)
    a = np.einsum('ij,ij->i', e1, h)
    ok = np.abs(a) > 1e-12
    f = np.zeros_like(a); f[ok] = 1 / a[ok]
    s = o - T[:, 0]
    u = f * np.einsum('ij,ij->i', s, h)
    q = np.cross(s, e1)
    v = f * (q @ d)
    t = f * np.einsum('ij,ij->i', e2, q)
    hit = ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (t > 0.02)
    return t[hit].min() if hit.any() else None


def panes(ac, seats, step=1.5, reach=4.0, inset=0.015, lip=0.02):
    """flat panes for the window openings the original glass leaves open, seen from each crewman's eye: rays over his
    view (azimuth -120..120, elevation -40..+60 degrees, every [step]) that leave without meeting the model or the
    glass mark an opening; each connected patch of them is one window. Its plane is fitted to where the neighbouring
    rays meet the frame round it; the pane is the outline of the patch's rays on that plane, grown by [lip] so its
    edges tuck behind the frame, set [inset] inside it (toward the eye). Returns triangles (k x 3 x 3) and a list of
    (seat, rays, centre) per pane."""
    G = np.array((ac.asset.get('CanopyGlass') or {}).get('Triangles', []), float).reshape(-1, 3, 3)
    tris, info = [], []
    for k in seats:
        E = np.array(ac.eye(k))
        model = _tris(ac, False, E, reach)
        azs = np.arange(-120, 120.01, step)
        els = np.arange(-40, 60.01, step)
        esc = np.zeros((len(azs), len(els)), bool)
        hit = {}
        for i, az in enumerate(azs):
            for j, el in enumerate(els):
                a, e = math.radians(az), math.radians(el)
                d = np.array([math.sin(a) * math.cos(e), math.sin(e), math.cos(a) * math.cos(e)])
                t_m = _first_hit(E, d, model) if len(model) else None
                t_g = _first_hit(E, d, G) if len(G) else None
                if t_g is not None and (t_m is None or t_g < t_m):
                    continue
                if t_m is None or t_m > reach:
                    esc[i, j] = True
                else:
                    hit[(i, j)] = E + d * t_m
        seen = np.zeros_like(esc)
        for i0 in range(len(azs)):
            for j0 in range(len(els)):
                if not esc[i0, j0] or seen[i0, j0]:
                    continue
                stack, comp, rim = [(i0, j0)], [], set()
                seen[i0, j0] = True
                while stack:
                    i, j = stack.pop()
                    comp.append((i, j))
                    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        a_, b_ = i + di, j + dj
                        if not (0 <= a_ < len(azs) and 0 <= b_ < len(els)):
                            continue
                        if esc[a_, b_]:
                            if not seen[a_, b_]:
                                seen[a_, b_] = True
                                stack.append((a_, b_))
                        elif (a_, b_) in hit:
                            rim.add((a_, b_))
                touches_edge = any(i in (0, len(azs) - 1) or j in (0, len(els) - 1) for i, j in comp)
                if len(comp) < 3 or len(rim) < 6 or touches_edge:
                    continue                      # (a stray gap, or a view out past the scanned field: not a window)
                R = np.array([hit[r] for r in rim])
                c = R.mean(0)
                _, _, vt = np.linalg.svd(R - c)
                nrm = vt[2]
                if nrm @ (E - c) < 0:
                    nrm = -nrm                       # toward the eye
                if np.abs((R - c) @ nrm).max() > 0.12:
                    continue                      # the frame round it is not near one plane: leave it open
                pts = []
                for i, j in comp:
                    a, e = math.radians(azs[i]), math.radians(els[j])
                    d = np.array([math.sin(a) * math.cos(e), math.sin(e), math.cos(a) * math.cos(e)])
                    den = d @ nrm
                    if abs(den) < 1e-6:
                        continue
                    t = ((c - E) @ nrm) / den
                    if t > 0:
                        pts.append(E + d * t)
                if len(pts) < 3:
                    continue
                u = vt[0]; v = vt[1]
                Q = np.array([[(p - c) @ u, (p - c) @ v] for p in pts])
                hull = _hull2d(Q)
                if len(hull) < 3:
                    continue
                q0 = hull.mean(0)
                grown = [q + (q - q0) / max(np.linalg.norm(q - q0), 1e-9) * lip for q in hull]
                P3 = [c + u * q[0] + v * q[1] + nrm * inset for q in grown]
                ctr = c + u * q0[0] + v * q0[1] + nrm * inset
                for a_, b_ in zip(P3, P3[1:] + P3[:1]):
                    tris.append(np.array([ctr, a_, b_]))
                info.append((k, len(comp), np.round(ctr, 2).tolist()))
    return np.array(tris).reshape(-1, 3, 3), info


def _hull2d(Q):
    """convex hull of 2-D points (monotone chain), counter-clockwise"""
    P = sorted(map(tuple, Q))
    if len(P) < 3:
        return np.array(P)

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lo, hi = [], []
    for p in P:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    for p in reversed(P):
        while len(hi) >= 2 and cross(hi[-2], hi[-1], p) <= 0:
            hi.pop()
        hi.append(p)
    return np.array(lo[:-1] + hi[:-1])


def view(ac, seats):
    """per seat: (rays, rays blocked by the new cockpit, rays out through the glass, nearest blocked direction)"""
    G = np.array((ac.asset.get('CanopyGlass') or {}).get('Triangles', []), float).reshape(-1, 3, 3)
    out = []
    for k in seats:
        E = np.array(ac.eye(k))
        own = _tris(ac, True, E, 2.5)
        model = _tris(ac, False, E, 3.0)
        n = blocked = glazed = 0
        worst = None
        for az in range(-100, 101, 10):
            for el in range(-25, 36, 10):
                a, e = math.radians(az), math.radians(el)
                d = np.array([math.sin(a) * math.cos(e), math.sin(e), math.cos(a) * math.cos(e)])
                t_own = _first_hit(E, d, own) if len(own) else None
                t_mod = _first_hit(E, d, model) if len(model) else None
                t_gl = _first_hit(E, d, G) if len(G) else None
                n += 1
                if t_own is not None and (t_mod is None or t_own < t_mod) and el >= -5 and abs(az) <= 40:
                    blocked += 1
                    if worst is None or el > worst[1]:
                        worst = (az, el, round(float(t_own), 2))
                if t_gl is not None and (t_mod is None or t_gl < t_mod) and (t_own is None or t_gl < t_own):
                    glazed += 1
        out.append((k, n, blocked, glazed, worst))
    return out


def glaze(ac):
    """the windows' glass for a cockpit script to call after its install: the original glass (fit) and panes in the
    openings it leaves (panes); returns a one-line summary"""
    kept, dropped = fit(ac)
    seats = [k for k, s in enumerate(ac.data['Seats'])          # (a seat without an eye of its own sees nothing)
             if (s.get('CameraPos') or {}).get('EyeAttachment') in ac.data.get('Attachments', {})]
    extra, info = panes(ac, seats)
    if len(extra):
        L.add_glass(ac.asset, [list(map(float, p)) for t in extra for p in t])
    return (f'{kept} original glass triangles kept, {dropped} dropped (on a frame or skin face), '
            f'{len(info)} panes fitted in open windows'), info


def main(argv):
    ac = L.Aircraft(argv[0])
    line, info = glaze(ac)
    print(f'{ac.vid}: {line}' + ''.join(f'\n      seat {k}: {n} rays, centre {c}' for k, n, c in info))
    for k, n, blocked, glazed, worst in view(ac, range(len(ac.data['Seats']))):
        print(f'   seat {k}: {n} rays, {glazed} out through the glass, {blocked} into the new cockpit above the panel '
              f'line (az +-40, el >= -5)' + (f', highest at az {worst[0]} el {worst[1]} ({worst[2]} m)' if worst else ''))
    if '--write' in argv:
        L.write_asset(ac.asset_path, ac.asset)


if __name__ == '__main__':
    main(sys.argv[1:])
