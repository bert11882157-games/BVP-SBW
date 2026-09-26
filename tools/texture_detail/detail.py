#!/usr/bin/env python3
"""Adds a faint metal finish and structural seams to an aircraft skin.

Every exterior triangle of the model is rasterised in texture space with its 3D position per texel, so the detail
follows the airframe, not the texture layout:
  * seams only where the real aircraft has them (panels.json, per aircraft, with the source): fuselage section joints
    and the radome joint at stations along the length, and on the wing and fin the spar/hinge lines (leading-edge flap,
    flaperon or aileron) and the span break between inboard and outboard surfaces. Lines are thin and only a few
    percent darker than the paint;
  * metal: a very faint grain and low-frequency mottle.
Lines are functions of |x|, so left/right faces that share mirrored texels get the same seams. Cockpit interior
faces, stores and landing gear are left alone.

The skin is always rebuilt from the untouched texture (git BASE_COMMIT), keeping texels later tools painted into
free texture space (tools/cockpit_cleanup), so re-running replaces the previous detail instead of stacking on it.

Usage: python3 tools/texture_detail/detail.py [--write] [--out DIR] <id>...   (no ids: every aircraft in panels.json)
"""
import io, json, math, os, subprocess, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

BASE_COMMIT = '14a1f05'          # skins before any detail was applied
LINE_DARK = 0.91                 # seam centre: 9% darker than the paint
COARSE = 0.09                    # blocks per texel above which no seams are drawn
LINE_HALF = 0.011                # seam half width in blocks (at least half a texel)
GRAIN = 0.006
MOTTLE = 0.012
PANELS = os.path.join(HERE, 'panels.json')
APPLIED = os.path.join(HERE, 'applied.json')


def exterior_triangles(geo, eye):
    out = []
    for bone in geo['minecraft:geometry'][0]['bones']:
        name = bone['name']
        if 'suspended' in name or 'landing_gear' in name or name.startswith('pilot_view_occluder'):
            continue
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        pos = pm['positions']; uv = pm['uvs']
        for poly in pm['polys']:
            P = np.array([T.to_local(pos[v[0]]) for v in poly])
            UV = np.array([uv[v[2]] for v in poly], float)
            c = P.mean(axis=0)
            rel = c - eye
            if np.linalg.norm(rel) < 1.4 and rel[1] < 0.4:
                continue    # cockpit tub, panel, seat
            for k in range(1, len(poly) - 1):
                out.append((P[[0, k, k + 1]], UV[[0, k, k + 1]]))
    return out


def normal(P):
    n = np.cross(P[1] - P[0], P[2] - P[0])
    return n / max(np.linalg.norm(n), 1e-12)


def samples(P, k=12):
    a, b = np.meshgrid(np.linspace(0, 1, k), np.linspace(0, 1, k))
    m = a + b <= 1
    a, b = a[m], b[m]
    return P[0] + a[:, None] * (P[1] - P[0]) + b[:, None] * (P[2] - P[0])


class Planform:
    """Leading/trailing edge per span station of a lifting surface; span measured along axis (0 = |x|, 1 = y)."""

    def __init__(self, tris, axis, step=0.02):
        self.ok = len(tris) > 0
        if not self.ok:
            return
        coord = (lambda q: np.abs(q[..., 0])) if axis == 0 else (lambda q: q[..., 1])
        allp = np.vstack(tris)
        s_all = coord(allp)
        self.axis, self.step = axis, step
        self.lo, self.hi = float(np.percentile(s_all, 1)), float(s_all.max())
        n = int((self.hi - self.lo) / step) + 1
        le = np.full(n, np.nan); te = np.full(n, np.nan)
        for P in tris:
            sv = coord(P)
            for (a, b) in ((0, 1), (1, 2), (2, 0)):
                s0, s1 = sv[a], sv[b]
                z0, z1 = P[a, 2], P[b, 2]
                lo_i = int(np.ceil((min(s0, s1) - self.lo) / step)); hi_i = int(np.floor((max(s0, s1) - self.lo) / step))
                for i in range(max(lo_i, 0), min(hi_i, n - 1) + 1):
                    si = self.lo + i * step
                    t = 0.5 if abs(s1 - s0) < 1e-9 else (si - s0) / (s1 - s0)
                    z = z0 + t * (z1 - z0)
                    le[i] = z if np.isnan(le[i]) else max(le[i], z)
                    te[i] = z if np.isnan(te[i]) else min(te[i], z)
        idx = np.arange(n); good = ~np.isnan(le)
        self.ok = good.sum() >= 3
        if not self.ok:
            return
        self.le = np.interp(idx, idx[good], le[good]); self.te = np.interp(idx, idx[good], te[good])
        # Strakes and LERX widen the root chord; the flap and spar lines follow the wing's own leading edge, the
        # straight edge of the outer span carried inboard.
        frac = idx / max(n - 1, 1)
        fit = (frac > 0.3) & (frac < 0.9) & good
        if fit.sum() >= 3:
            k, c = np.polyfit(idx[fit], self.le[fit], 1)
            self.le = np.minimum(self.le, k * idx + c + 0.05)

    def at(self, p):
        s = np.abs(p[..., 0]) if self.axis == 0 else p[..., 1]
        f = np.clip((s - self.lo) / self.step, 0, len(self.le) - 1)
        i = np.minimum(f.astype(int), len(self.le) - 2) if len(self.le) > 1 else np.zeros_like(f, int)
        w = f - i if len(self.le) > 1 else 0.0
        j = np.minimum(i + 1, len(self.le) - 1)
        le = self.le[i] * (1 - w) + self.le[j] * w
        te = self.te[i] * (1 - w) + self.te[j] * w
        chord = np.maximum(le - te, 1e-3)
        return (s - self.lo) / max(self.hi - self.lo, 1e-3), (le - p[..., 2]) / chord, chord

    def seam_distance(self, p, spec):
        span, c, chord = self.at(p)
        dist = np.full(p.shape[:-1], np.inf)
        outer = spec.get('outer', {})
        for f in spec.get('chord', []):
            a, b = outer.get(str(f), [0.0, 1.0])
            d = np.abs(c - f) * chord
            dist = np.where((span >= a) & (span <= b), np.minimum(dist, d), dist)
        for g in spec.get('span', []):
            d = np.abs(span - g) * (self.hi - self.lo)
            dist = np.where((c > 0.03) & (c < 0.97), np.minimum(dist, d), dist)
        return dist


def classify(tris):
    """Split exterior triangles into fuselage, wing, horizontal tail and fin; returns landmarks too."""
    allp = np.vstack([P for P, _ in tris])
    nose, tail = allp[:, 2].max(), allp[:, 2].min()
    length = nose - tail
    frac = lambda z: (nose - z) / length   # noqa: E731
    sides = [P for P, _ in tris if abs(normal(P)[0]) > 0.7 and 0.35 < frac(P[:, 2].mean()) < 0.7]
    root = float(np.median(np.abs(np.vstack(sides)[:, 0]))) if sides else 0.6
    wing, htail, fin, fuse = [], [], [], []
    for P, UV in tris:
        n = normal(P); c = P.mean(axis=0); f = frac(c[2])
        if abs(n[1]) > 0.6 and abs(c[0]) > root * 1.05:
            (htail if f > 0.8 else wing).append((P, UV))
        elif abs(n[0]) > 0.8 and f > 0.6 and c[1] > allp[:, 1].min() + 0.55 * (allp[:, 1].max() - allp[:, 1].min()):
            fin.append((P, UV))
        else:
            fuse.append((P, UV))
    return dict(nose=nose, tail=tail, length=length, root=root, wing=wing, htail=htail, fin=fin, fuse=fuse)


def landmarks(vid, parts, eye, wingform, finform):
    data = json.load(open(os.path.join(T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')))
    glass = (data.get('CanopyGlass') or {}).get('Triangles')
    if glass:
        z = np.array(glass).reshape(-1, 3)[:, 2]
        cf, cr = z.max(), z.min()
    else:
        cf, cr = eye[2] + 0.8, eye[2] - 0.6
    marks = dict(canopy_front=cf, canopy_rear=cr)
    if wingform.ok:
        marks.update(wing_root_le=wingform.le[0], wing_root_te=wingform.te[0])
    if finform.ok:
        marks['fin_root_le'] = finform.le[0]
    return marks


def stations(spec, parts, marks):
    out = []
    for s in spec.get('fuselage', []):
        if 'frac' in s:
            out.append(parts['nose'] - s['frac'] * parts['length'])
        elif s.get('at') in marks:
            out.append(marks[s['at']] + s.get('offset', 0.0))
    return out


def detail(vid, tex, spec):
    H, W = tex.shape[:2]
    eye = T.eyes(vid)[0]
    _, geo = T.load_geo(vid)
    tris = exterior_triangles(geo, eye)
    parts = classify(tris)
    wingform = Planform([P for P, _ in parts['wing']], 0)
    tailform = Planform([P for P, _ in parts['htail']], 0)
    finform = Planform([P for P, _ in parts['fin']], 1)
    marks = landmarks(vid, parts, eye, wingform, finform)
    zs = stations(spec, parts, marks)
    kind = {}
    for name in ('wing', 'htail', 'fin', 'fuse'):
        for P, _ in parts[name]:
            kind[id(P)] = name
    shade = np.ones((H, W), np.float32)
    covered = np.zeros((H, W), bool)
    # Texels several faces share (tiled or reused texture) cannot carry a seam that is right for all of them.
    first = np.full((H, W, 3), np.nan, np.float32)
    shared = np.zeros((H, W), bool)
    for P, UV in tris:
        px = np.stack([UV[:, 0] * W, (1 - UV[:, 1]) * H], -1)
        x0, y0 = np.floor(px.min(axis=0)).astype(int)
        x1, y1 = np.ceil(px.max(axis=0)).astype(int)
        x0 = max(x0, 0); y0 = max(y0, 0); x1 = min(x1, W - 1); y1 = min(y1, H - 1)
        if x1 < x0 or y1 < y0:
            continue
        a, b, c = px
        d = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        if abs(d) < 1e-9:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        l1 = ((gx - a[0]) * (c[1] - a[1]) - (gy - a[1]) * (c[0] - a[0])) / d
        l2 = ((b[0] - a[0]) * (gy - a[1]) - (b[1] - a[1]) * (gx - a[0])) / d
        inside = (l1 >= -0.02) & (l2 >= -0.02) & (l1 + l2 <= 1.02)
        if not inside.any():
            continue
        p3 = P[0][None, None] + l1[..., None] * (P[1] - P[0]) + l2[..., None] * (P[2] - P[0])
        key = np.stack([np.abs(p3[..., 0]), p3[..., 1], p3[..., 2]], -1).astype(np.float32)
        fsub = first[y0:y1 + 1, x0:x1 + 1]
        seen = inside & ~np.isnan(fsub[..., 0])
        clash = seen & (np.abs(fsub - key).max(-1) > 0.08)
        shared[y0:y1 + 1, x0:x1 + 1] |= clash
        fresh = inside & ~seen
        fsub[fresh] = key[fresh]
        area3 = np.linalg.norm(np.cross(P[1] - P[0], P[2] - P[0])) / 2
        per_texel = math.sqrt(area3 / max(abs(d) / 2, 1e-9))           # blocks per texel
        half = max(LINE_HALF, 0.55 * per_texel)
        k = kind.get(id(P), 'fuse')
        if k == 'wing' and wingform.ok:
            dist = wingform.seam_distance(p3, spec.get('wing', {}))
        elif k == 'htail' and tailform.ok:
            dist = tailform.seam_distance(p3, {'chord': [0.25]})
        elif k == 'fin' and finform.ok:
            dist = finform.seam_distance(p3, spec.get('fin', {}))
        else:
            dist = np.full(p3.shape[:-1], np.inf)
            for z in zs:
                dist = np.minimum(dist, np.abs(p3[..., 2] - z))
        line = np.clip(1 - dist / half, 0, 1) ** 0.7
        if per_texel > COARSE:
            line[:] = 0.0                                             # texels too big for a thin seam
        f = 1 - (1 - LINE_DARK) * line
        sub = shade[y0:y1 + 1, x0:x1 + 1]
        sub[inside] = np.minimum(sub[inside], f[inside])
        covered[y0:y1 + 1, x0:x1 + 1] |= inside
    rng = np.random.default_rng(sum(map(ord, vid)))
    grain = 1 + rng.normal(0, GRAIN, (H, W)).astype(np.float32)
    coarse = rng.normal(0, 1, (max(2, H // 96), max(2, W // 96))).astype(np.float32)
    mottle = np.asarray(Image.fromarray(coarse).resize((W, H), Image.BICUBIC), np.float32)
    mottle = 1 + MOTTLE * mottle / max(np.abs(mottle).max(), 1e-6)
    shade = np.where(shared, 1.0, shade)
    factor = np.where(covered, shade * grain * mottle, 1.0)
    out = tex.copy()
    rgb = out[..., :3].astype(np.float32) * factor[..., None]
    out[..., :3] = np.clip(np.round(rgb), 0, 255).astype(np.uint8)
    info = dict(stations=[round(float(z), 2) for z in zs], marks={k: round(float(v), 2) for k, v in marks.items()},
                wing=len(parts['wing']), fin=len(parts['fin']), htail=len(parts['htail']), root=round(parts['root'], 2))
    return out, int(covered.sum()), info


def base_texture(vid, path):
    """The skin before any detail, with texels painted since into free (transparent) texture space kept."""
    repo = subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True,
                          cwd=HERE).stdout.strip()
    rel = os.path.relpath(path, repo)
    blob = subprocess.run(['git', 'show', f'{BASE_COMMIT}:{rel}'], capture_output=True, cwd=repo).stdout
    if blob.startswith(b'version https://git-lfs'):
        blob = subprocess.run(['git', 'lfs', 'smudge'], input=blob, capture_output=True, cwd=repo).stdout
    current = np.asarray(Image.open(path).convert('RGBA')).copy()
    if not blob:
        return current
    orig = np.asarray(Image.open(io.BytesIO(blob)).convert('RGBA')).copy()
    if orig.shape != current.shape:
        return current
    keep_new = orig[..., 3] == 0
    orig[keep_new] = current[keep_new]
    return orig


def main(argv):
    write = '--write' in argv
    out_dir = argv[argv.index('--out') + 1] if '--out' in argv else None
    specs = json.load(open(PANELS))
    ids = [a for a in argv if not a.startswith('--') and a != out_dir] or [k for k in specs if not k.startswith('_')]
    applied = json.load(open(APPLIED)) if os.path.exists(APPLIED) else {}
    for vid in ids:
        path = os.path.join(T.GEN, 'assets/berts_vehicle_pack/textures/entity', vid + '.png')
        mode = Image.open(path).mode
        tex = base_texture(vid, path)
        out, n, info = detail(vid, tex, specs.get(vid, {}))
        print(f'{vid}: {n} texels detailed {info}', flush=True)
        img = Image.fromarray(out, 'RGBA')
        if mode != 'RGBA':
            img = img.convert(mode)
        if out_dir:
            os.makedirs(out_dir, exist_ok=True)
            img.save(os.path.join(out_dir, vid + '.png'))
        if write:
            img.save(path, optimize=True)
            applied[vid] = 2
            with open(APPLIED, 'w') as fh:
                json.dump(dict(sorted(applied.items())), fh, indent=1)
                fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
