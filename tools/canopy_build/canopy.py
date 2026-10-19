#!/usr/bin/env python3
"""The canopy itself, built new: the model's old canopy (frame faces and glass panes modelled into the fuselage
pieces) is cut out, and a canopy with real frame members, sills and thick glass is put in its place, painted in the
aircraft's own colours from its texture.

Runs after tools/canopy_panels/panels.py (the glass panels and their frame stations, from spec.json) and
tools/cockpit_models/fleet.py (the modelled cockpit); the MiG-29 keeps its own glass but gets the same treatment.

  * Old canopy: every face of the hull wreck pieces that stands above the canopy sill inside the hood's plan, or
    that stands clearly off the nose skin along the windscreen (posts, bows, armour-glass plates, painted panes),
    is removed. The outer skin along the sills and the nose deck under the windscreen stay.
  * Frame members: solid boxes with a real cross-section along every frame station the panels define (windscreen
    posts, the arch, the hood bows, the hood's end, the base of the windscreen, the sills along the glass's lower
    edge and the spine of a glazed tail), straddling the glass (outer face proud of it, inner face under it).
    Outer faces and edges take the airframe paint beside the cockpit (sampled from the model's texture on the skin
    next to the sills, snapped to a uniform texel block of that texture), inner faces the cockpit's dark grey, and
    the sill tops the black of the canopy seal.
  * Glass: the panel glass gets an inner sheet a few millimetres under the outer one (CanopyGlass), so the glass
    reads as a thick transparency at its edges and from the seat.

New faces go into the bone wreck_<piece>__canopy (parent hull) of the piece that holds the cockpit, so the canopy
breaks away with the forward fuselage. Running the script again on its own output removes the earlier canopy
bone and inner sheet first.

Usage: python3 tools/canopy_build/canopy.py [--dry-run] [--orig=DIR] id...   (no ids: every aircraft in spec.json)
       python3 tools/canopy_build/run.py [--rev=REV] id...   runs the whole chain from the committed originals
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_panels'))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_loft'))
import panels as P          # noqa: E402
import cockpit_lib as L     # noqa: E402
import loft as LF           # noqa: E402
import ortho as O           # noqa: E402

FRAME_W = 0.045       # frame member width (across the member)
FRAME_OUT = 0.014     # stands this proud of the glass
FRAME_IN = 0.024      # and reaches this far under it
POST_W = 0.055        # windscreen posts and the arch are heavier
SILL_W = 0.070        # the sill rail: width across
SILL_H = 0.036        # and height
GLASS_T = 0.012       # inner glass sheet this far under the outer
SEAL = (18, 18, 20)
INNER = L.ACCENT['dgrey']


def unit(v):
    v = np.asarray(v, float); return v / max(np.linalg.norm(v), 1e-9)


# ---------------------------------------------------------------- members

class Members:
    """solid members along polylines, straddling a surface whose outward direction is given per point"""
    def __init__(self, mesh, outward, uv_paint, uv_inner, uv_seal):
        self.m, self.outward = mesh, outward
        self.paint, self.inner, self.seal = uv_paint, uv_inner, uv_seal

    def chain(self, pts, width, proud, under, closed=False, top_seal=False):
        P = np.asarray(pts, float)
        if closed and np.linalg.norm(P[0] - P[-1]) > 1e-6:
            P = np.vstack([P, P[:1]])
        for a, b in zip(P[:-1], P[1:]):
            d = b - a; ln = np.linalg.norm(d)
            if ln < 1e-4:
                continue
            d /= ln
            out = self.outward((a + b) / 2)
            out = out - d * (out @ d)
            if np.linalg.norm(out) < 1e-6:
                out = np.array([0.0, 1.0, 0.0])
            out = unit(out)
            side = np.cross(d, out)
            c = (a + b) / 2 + out * (proud - under) / 2
            faces = {(2, -1): self.inner}
            if top_seal:
                faces[(2, 1)] = self.seal
            self.m.box(c, (d, side, out), (ln / 2 + width * 0.35, width / 2, (proud + under) / 2), self.paint,
                       faces=faces)


# ---------------------------------------------------------------- the old canopy

def glass_colours(ac, B, env, skip_bones):
    """the texel colours the model paints its glass in: saturated cyan / blue colours whose faces lie almost all
    around the cockpit (a blue camouflage covers the whole airframe; the glass colour nothing else)"""
    import colorsys
    from PIL import Image
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGB'))
    H, W = tex.shape[:2]
    near, total = {}, {}
    for b in ac.bones:
        if b['name'] in skip_bones or not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        Pp = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        UV = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            uv = UV[[v[2] for v in poly]].mean(0)
            c = tuple(int(x) for x in tex[min(H - 1, int((1 - uv[1]) * H)), min(W - 1, int(uv[0] * W))])
            h, s, v = colorsys.rgb_to_hsv(*(np.array(c) / 255.0))
            if not (s > 0.45 and v > 0.35 and 0.42 < h < 0.65):
                continue
            V = Pp[[v[0] for v in poly]]
            total[c] = total.get(c, 0) + 1
            if B.z_hood - 0.6 < V[:, 2].mean() < B.z_base + 0.6 and np.abs(V[:, 0]).max() < env.W(env.zc) + 0.7:
                near[c] = near.get(c, 0) + 1
    return {c for c, n in total.items() if n >= 4 and near.get(c, 0) >= 0.75 * n}


def old_canopy(ac, B, env, skip_bones):
    """{bone name: set(poly index)}: faces of the model's own canopy"""
    from PIL import Image
    ev = env.ev
    z_front = B.z_base + 0.12
    z_back = min(env.zr, B.z_hood) - 0.06
    Wa = env.W(B.z_arch)
    glassy = glass_colours(ac, B, env, skip_bones)
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGB'))
    H, W = tex.shape[:2]
    out = {}
    for b in ac.bones:
        if b['name'] in skip_bones or not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        Pp = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        UV = np.array(pm['uvs'], float)
        sel = set()
        for i, poly in enumerate(pm['polys']):
            V = Pp[[v[0] for v in poly]]
            z0, z1 = V[:, 2].min(), V[:, 2].max()
            if z1 < z_back - 0.10 or z0 > z_front + 0.10:
                continue
            if np.abs(V[:, 0]).max() > max(env.W(z) for z in np.clip(V[:, 2], env.zr, env.zf)) + 0.30:
                continue
            # C. painted in the model's glass colour, up at the canopy: an old pane, wherever its frame stands
            if glassy:
                uv = UV[[v[2] for v in poly]].mean(0)
                c = tuple(int(x) for x in tex[min(H - 1, int((1 - uv[1]) * H)), min(W - 1, int(uv[0] * W))])
                if c in glassy and V[:, 1].max() > env.lip(float(np.clip(V[:, 2].mean(), env.zr, env.zf))) - 0.60:
                    sel.add(i); continue
            if z1 < z_back or z0 > z_front:
                continue
            # E. a face whose middle lies inside the canopy's volume (well inside the plan, above the sill or the
            # nose skin, under the roof): an old post or divider standing in the glass, however far it reaches down
            cx, cy, cz = V.mean(0)
            czc = float(np.clip(cz, env.zr, env.zf))
            if z_back <= cz <= z_front and abs(cx) < env.W(czc) - 0.05 and cy < env.spine(czc) + 0.05:
                floor = env.lip(czc) + 0.02 if cz <= B.z_arch + 0.06 else ev.skin_at(float(cx), float(cz)) + 0.015
                if cy > floor or V[:, 1].max() > floor + 0.08:
                    sel.add(i); continue
            # A. inside the hood's plan, above the sill: old glass panes, bows, the headrest fairing's glazing
            lips = np.array([env.lip(float(z)) for z in V[:, 2]])
            if z1 <= B.z_arch + 0.06 and z0 >= z_back and np.all(V[:, 1] >= lips - 0.03):
                sel.add(i); continue
            # a modelled pane or frame reaching down to (or a little under) the sill: lies along the new glass
            if z1 <= B.z_arch + 0.06 and z0 >= env.zr - 0.30 and np.all(V[:, 1] >= lips - 0.14) and \
                    V[:, 1].max() >= lips.max() + 0.02 and ac.hugs_glass(V, 0.15):
                sel.add(i); continue
            # B. along the windscreen: anything standing clearly off the nose skin (posts, a bow, an armour-glass
            # plate), or lying along the new glass without being the skin under it
            if z1 > B.z_arch - 0.06 and z1 <= z_front and np.abs(V[:, 0]).max() <= Wa + 0.30:
                skin = np.array([ev.skin_at(float(x), float(z)) for x, z in zip(V[:, 0], V[:, 2])])
                if np.all(V[:, 1] > skin + 0.015):
                    sel.add(i); continue
                if np.all(V[:, 1] > skin - 0.02) and V[:, 1].max() > skin.max() + 0.006 and ac.hugs_glass(V, 0.08):
                    sel.add(i); continue
        # D. the frame around removed panes: dark faces sharing an edge with a removed face, standing off the skin
        if glassy and sel:
            import colorsys
            key = lambda j: tuple(np.round(Pp[j], 3))
            edges_of = lambda poly: {frozenset((key(poly[k][0]), key(poly[(k + 1) % len(poly)][0])))
                                     for k in range(len(poly))}
            E = [edges_of(poly) for poly in pm['polys']]
            grown = True
            while grown:
                grown = False
                front = set().union(*(E[i] for i in sel))
                for i, poly in enumerate(pm['polys']):
                    if i in sel or not (E[i] & front):
                        continue
                    V = Pp[[v[0] for v in poly]]
                    z0, z1 = V[:, 2].min(), V[:, 2].max()
                    if z1 < z_back - 0.10 or z0 > z_front + 0.10 or \
                            np.abs(V[:, 0]).max() > max(env.W(z) for z in np.clip(V[:, 2], env.zr, env.zf)) + 0.30:
                        continue
                    uv = UV[[v[2] for v in poly]].mean(0)
                    c = tex[min(H - 1, int((1 - uv[1]) * H)), min(W - 1, int(uv[0] * W))]
                    if colorsys.rgb_to_hsv(*(c / 255.0))[2] > 0.16:
                        continue
                    skin = np.array([ev.skin_at(float(x), float(z)) for x, z in zip(V[:, 0], V[:, 2])])
                    if V[:, 1].max() > skin.max() + 0.03 and \
                            V[:, 1].max() > env.lip(float(np.clip(V[:, 2].mean(), env.zr, env.zf))) - 0.60:
                        sel.add(i); grown = True
        # F. hand-listed leftovers: boxes relative to the eye (x left, y up, z forward) in spec.json
        for lo, hi in B.spec.get('remove_boxes', []):
            lo, hi = np.array(lo, float) + ev.eye, np.array(hi, float) + ev.eye
            for i, poly in enumerate(pm['polys']):
                cc = Pp[[v[0] for v in poly]].mean(0)
                if np.all(cc >= lo) and np.all(cc <= hi):
                    sel.add(i)
        if sel:
            out[b['name']] = sel
    return out


# ---------------------------------------------------------------- paint

def paint_colour(ac, env, B):
    """the airframe colour beside the cockpit: the median texel colour of the skin just outside the sills, seen
    from above on the model as it is (before the old canopy goes)"""
    tris, uvs, tex = O.model(ac.vid)
    zc = (B.z_arch + B.z_hood) / 2
    hw, hl = 1.8, max(1.2, (B.z_arch - B.z_hood) / 2 + 0.6)
    centre = np.array([0.0, tris[..., 1].max() + 1.0, zc])
    step = 0.02
    colour, depth, _ = O.render(tris, uvs, tex, 'top', centre, hw, hl, step)
    ny, nx = depth.shape
    samples = []
    for z in np.arange(B.z_hood - 0.3, B.z_arch, 0.04):
        j = int(np.clip(round((zc + hl - z) / step), 0, ny - 1))
        for sgn in (1, -1):
            for dx in (0.18, 0.28, 0.40):
                x = sgn * (env.W(z) + dx)
                i = int(np.clip(round((hw - x) / step), 0, nx - 1))
                if np.isfinite(depth[j, i]):
                    samples.append(colour[j, i])
    if not samples:
        return (120, 124, 120)
    S = np.array(samples)
    if S.max() <= 1.0:
        S = S * 255.0                     # ortho loads the texture as floats 0..1
    med = np.median(S, 0)
    # the most common colour near the median (pixel-art skins: a few flat colours)
    near = S[np.abs(S - med).max(1) < 24]
    if len(near):
        vals, counts = np.unique(near.astype(int), axis=0, return_counts=True)
        return tuple(int(v) for v in vals[np.argmax(counts)])
    return tuple(int(v) for v in med)


# ---------------------------------------------------------------- glass

def inner_sheet(tris, centre, t):
    """the panel glass offset inward by t along smoothed vertex normals (normals point away from the canopy centre)"""
    T = np.asarray(tris, float).reshape(-1, 3, 3)
    n = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    c = T.mean(1)
    flip = ((c - centre) * n).sum(1) < 0
    n[flip] *= -1
    key = lambda p: tuple(np.round(p, 4))
    acc = {}
    for k in range(len(T)):
        for q in range(3):
            acc.setdefault(key(T[k, q]), []).append(n[k])
    nv = {k: unit(np.sum(v, 0)) for k, v in acc.items()}
    out = T.copy()
    for k in range(len(T)):
        for q in range(3):
            out[k, q] = T[k, q] - nv[key(T[k, q])] * t
    return out


# ---------------------------------------------------------------- build

def apply(vid, dry=False, orig=None):
    """orig: a folder holding the committed original <vid>.geo.json, the model panels.py measured its glass on;
    the envelope and the paint are measured there, so the frames land exactly on the glass (the modelled cockpit
    has changed the model on disk by now). Without it the model on disk is measured, which fits only when it is
    still the original."""
    specs = json.load(open(P.SPEC))
    spec = specs.get(vid)
    if spec is None:
        return None, f'{vid}: no spec'
    loft_cfgs = json.load(open(LF.CONFIG)) if os.path.exists(LF.CONFIG) else {}
    ac = L.Aircraft(vid)
    # drop what an earlier run added
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__canopy')]
    was = os.environ.get('BVP_GEO_DIR')
    if orig:
        os.environ['BVP_GEO_DIR'] = orig
    try:
        B = P.Builder(vid, spec, loft_cfgs.get(vid, {}))
        tris = B.build()
        env = B.env
        sw = L.Swatches(L.texture_path(vid))
        paint = paint_colour(ac, env, B)
    finally:
        if orig:
            if was is None:
                os.environ.pop('BVP_GEO_DIR', None)
            else:
                os.environ['BVP_GEO_DIR'] = was
    uv_paint, uv_inner, uv_seal = sw.uv(paint), sw.uv(INNER), sw.uv(SEAL)
    got = sw.got(paint)

    # the old canopy
    cockpit = next((b['name'] for b in ac.bones if b['name'].endswith('__cockpit')), None)
    rm = old_canopy(ac, B, env, {cockpit} if cockpit else set())
    removed = 0
    for b in ac.bones:
        if b['name'] in rm:
            pm = b['poly_mesh']
            gone = rm[b['name']]
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone]
            removed += len(gone)

    # members
    centre = np.array([0.0, env.lip(env.zc) - 0.35, env.zc])
    mesh = L.Mesh()
    M = Members(mesh, lambda p: unit(np.asarray(p) - centre), uv_paint, uv_inner, uv_seal)
    for kind, pts in B.frames:
        w = POST_W if kind in ('post', 'arch') else FRAME_W
        M.chain(pts, w, FRAME_OUT, FRAME_IN, closed=False)
    for kind, pts in B.edges:
        if kind == 'base':
            M.chain(pts, FRAME_W, FRAME_OUT, FRAME_IN)
        elif kind == 'spine':
            M.chain(pts, FRAME_W, FRAME_OUT, FRAME_IN)
        elif kind == 'rear':
            M.chain(pts, FRAME_W, FRAME_OUT, FRAME_IN)
    for pts in B.sills():
        if len(pts) > 1:
            # the sill rail sits on the lip, its top the seal; pushed a little inboard so its outer face lies in
            # the skin
            M.chain(pts, SILL_W, SILL_H * 0.5, SILL_H * 0.5, top_seal=True)
    pm = mesh.poly_mesh()
    hull = ac.bone('hull')
    piece = cockpit.split('__', 1)[0] if cockpit else 'wreck_fuselage_1'
    bone = {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == cockpit), len(ac.bones) - 1) + 1
    ac.bones.insert(at, bone)

    # thick glass: the panel glass plus its inner sheet; an earlier inner sheet is dropped first (it lies under
    # the panels by GLASS_T along the normal: anything that close under the fresh panels and not on them)
    a = ac.asset
    cg = a.get('CanopyGlass')
    if cg and not spec.get('skip'):
        cur = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        panel = np.asarray(tris, float).reshape(-1, 3, 3)
        inner = inner_sheet(panel, centre, GLASS_T)
        old_inner = inner_sheet(panel, centre, GLASS_T)    # same geometry: match by rounded vertices
        keyset = {tuple(np.round(p, 3)) for t in old_inner for p in t}
        keep = [t for t in cur if not all(tuple(np.round(p, 3)) in keyset for p in t)]
        cg['Triangles'] = [round(float(v), 4) + 0.0 for t in list(keep) + list(inner) for p in t for v in p]
    vol = L.loader_volume(pm)
    report = (f'{vid}: removed {removed} old canopy faces from {sorted(rm)}; {len(pm["polys"])} canopy faces in '
              f'{bone["name"]} (volume {vol:+.0f}); paint {paint} -> {got}; glass {len(cg["Triangles"]) // 9 if cg else 0} tris')
    if not dry:
        with open(ac.geo_path, 'w') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        L.write_asset(ac.asset_path, ac.asset)
    return ac, report


def main(argv):
    dry = '--dry-run' in argv
    orig = next((a[7:] for a in argv if a.startswith('--orig=')), None)
    ids = [a for a in argv if not a.startswith('--')]
    if not ids:
        ids = [k for k in json.load(open(P.SPEC)) if not k.startswith('_')]
    for vid in ids:
        try:
            _, report = apply(vid, dry, orig)
        except Exception as failure:
            import traceback; traceback.print_exc()
            report = f'{vid}: FAILED {failure!r}'
        print(report, flush=True)


if __name__ == '__main__':
    main(sys.argv[1:])
