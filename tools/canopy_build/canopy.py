#!/usr/bin/env python3
"""The canopy itself, built new: the model's old canopy (frame faces and glass panes modelled into the fuselage
pieces) is cut out, and a canopy with real frame members, sills and thick glass is put in its place, painted in the
aircraft's own colours from its texture.

Runs after tools/canopy_panels/panels.py (the glass panels and their frame stations, from spec.json) and
tools/cockpit_models/fleet.py (the modelled cockpit); the MiG-29 keeps its own glass but gets the same treatment.

  * Old canopy: only faces lying wholly inside an explicit cut region go (old_canopy): the hood's volume above
    the sill line, the windscreen's volume off the nose skin, faces painted in the model's glass colour inside the
    plan, and boxes listed per aircraft in spec.json "cut". Nothing is taken for touching a removed face or for
    where its middle sits, so the skin and the deck under the windscreen stay. The report counts each rule's take;
    tools/canopy_build/review.py draws the region and the removed faces for checking.
  * Frame members: one continuous rail swept round the glass's whole edge (windscreen base, sills, rear edge) and
    rings over the glass at every frame station (windscreen posts, the arch, the bows, the hinge frame where rear
    glazing runs on behind the hood), each a solid with a real cross-section straddling the glass, mitred at the
    corners. Outer and side faces take the airframe paint beside the cockpit (sampled from the model's texture
    next to the sills, snapped to a uniform texel block), the face under the glass the cockpit's dark grey.
    Widths follow a real fighter's frame (spec.json "frame" overrides per aircraft).
  * Glass: the panel glass is a solid: outer sheet, inner sheet GLASS_T under it and the edge between them
    (CanopyGlass), so the glass reads as a thick transparency at its edges and from the seat.

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

# member cross-sections, in blocks (about a metre a block on these models): a real fighter's canopy frame is
# 8-12 cm wide at the sill and the bow, 15-25 cm along a heavy hinge frame; spec.json "frame" overrides per aircraft
FRAME_W = 0.080       # bows and the arch: width across the member
FRAME_OUT = 0.020     # stands this proud of the glass
FRAME_IN = 0.040      # and reaches this far under it
POST_W = 0.070        # windscreen posts
RAIL_W = 0.090        # the rail round the glass's edge (sills, windscreen base, rear edge)
RAIL_OUT = 0.022
RAIL_IN = 0.045
RAIL_SHIFT = 0.025    # the rail sits this much outboard of the glass's edge, on the coaming's top
GLASS_T = 0.020       # inner glass sheet this far under the outer: the glass's thickness
INNER = L.ACCENT['dgrey']


def unit(v):
    v = np.asarray(v, float); return v / max(np.linalg.norm(v), 1e-9)


# ---------------------------------------------------------------- members

class Members:
    """solid members swept along polylines, straddling a surface whose outward direction is given per point: a
    rectangular section (width across, proud + under deep) is set at every point of the line, turned to the line's
    direction there (mitred at the corners), and the sections are joined into one continuous member. Outer and side
    faces take the paint, the face under the glass the cockpit's dark grey."""
    def __init__(self, mesh, outward, uv_paint, uv_inner):
        self.m, self.outward = mesh, outward
        self.paint, self.inner = uv_paint, uv_inner

    def sweep(self, pts, width, proud, under, closed=False, shift=0.0):
        """shift: the section is moved this far sideways, away from the middle of the line (a closed loop's
        rail then lies outboard of the glass's edge, on the skin, instead of straddling it)"""
        P = np.asarray(pts, float)
        keep = [P[0]]
        for q in P[1:]:
            if np.linalg.norm(q - keep[-1]) > 1e-4:
                keep.append(q)
        P = np.array(keep)
        if closed and np.linalg.norm(P[0] - P[-1]) < 1e-4:
            P = P[:-1]
        n = len(P)
        if n < 2:
            return
        secs = []
        for i in range(n):
            if closed:
                t = unit(P[(i + 1) % n] - P[i]) + unit(P[i] - P[(i - 1) % n])
            elif i == 0:
                t = P[1] - P[0]
            elif i == n - 1:
                t = P[-1] - P[-2]
            else:
                t = unit(P[i + 1] - P[i]) + unit(P[i] - P[i - 1])
            if np.linalg.norm(t) < 1e-6:                  # a hairpin: use one side
                t = P[(i + 1) % n] - P[i]
            t = unit(t)
            out = self.outward(P[i])
            out = out - t * (out @ t)
            if np.linalg.norm(out) < 1e-6:
                out = np.array([0.0, 1.0, 0.0])
            out = unit(out)
            side = np.cross(t, out)
            c = P[i] + out * (proud - under) / 2
            if shift:
                mid = P.mean(0)
                away = unit(P[i] - mid - out * ((P[i] - mid) @ out))
                c = c + side * (shift if side @ away > 0 else -shift)
            h = (proud + under) / 2
            secs.append([c + side * width / 2 + out * h, c - side * width / 2 + out * h,
                         c - side * width / 2 - out * h, c + side * width / 2 - out * h])
        pairs = list(zip(range(n - 1), range(1, n))) + ([(n - 1, 0)] if closed else [])
        for i, j in pairs:
            A, B = secs[i], secs[j]
            self.m.flat([A[0], B[0], B[1], A[1]], self.paint, normal=(A[0] + A[1] - A[2] - A[3]))     # outer
            self.m.flat([A[1], B[1], B[2], A[2]], self.paint, normal=(A[1] + A[2] - A[0] - A[3]))     # side
            self.m.flat([A[2], B[2], B[3], A[3]], self.inner, normal=(A[2] + A[3] - A[0] - A[1]))     # under
            self.m.flat([A[3], B[3], B[0], A[0]], self.paint, normal=(A[0] + A[3] - A[1] - A[2]))     # side
        if not closed:
            A = secs[0]; self.m.flat(A, self.paint, normal=P[0] - P[1])
            B = secs[-1]; self.m.flat(B, self.paint, normal=P[-1] - P[-2])


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


def cut_boxes(B, env):
    """the cut region as plain boxes, vehicle-local (x left, y up, z forward), for drawing on a review render:
    one box per hood station slice and per windscreen slice, plus the hand-listed boxes"""
    out = []
    ev = env.ev
    z_back = min(env.zr, B.z_hood) - 0.05
    for z0 in np.arange(z_back, B.z_arch + 0.06, 0.25):
        z1 = min(z0 + 0.25, B.z_arch + 0.06)
        zc = float(np.clip((z0 + z1) / 2, env.zr, env.zf))
        w = env.W(zc) + 0.15
        out.append(('hood', [-w, env.lip(zc) - 0.03, z0], [w, env.spine(zc) + 0.5, z1]))
    for z0 in np.arange(B.z_arch - 0.06, B.z_base + 0.10, 0.2):
        z1 = min(z0 + 0.2, B.z_base + 0.10)
        zc = (z0 + z1) / 2
        w = env.W(B.z_arch) + 0.15
        out.append(('windscreen', [-w, ev.skin_at(0.0, zc) + 0.015, z0], [w, env.spine(float(np.clip(zc, env.zr, env.zf))) + 0.5, z1]))
    for lo, hi in B.spec.get('cut', []):
        out.append(('box', list(np.array(lo, float) + ev.eye), list(np.array(hi, float) + ev.eye)))
    return out


def old_canopy(ac, B, env, skip_bones):
    """{bone name: set(poly index)}: faces of the model's own canopy, taken only from an explicit region: a face
    goes when ALL its corners lie inside one of these (nothing is grown from neighbours, and no face is taken for
    where its middle sits):
      hood        between the hood's rear end and the arch, inside the glass's plan (+0.15), on or above the sill
                  line (lip - 0.03): the old panes, bows and headrest-fairing glazing;
      windscreen  between the arch and the base, inside the arch's width (+0.15), standing off the nose skin
                  (skin + 0.015): posts, bows and armour-glass plates; the deck itself stays;
      painted     a face painted in the model's glass colour (glass_colours) inside the plan, down to 0.6 under
                  the sill: a pane painted onto the fuselage side;
      cut         boxes listed in spec.json "cut" (eye-relative), for leftovers the rules miss.
    Returns the selection and a per-rule count, so the report says what each rule took."""
    from PIL import Image
    ev = env.ev
    z_front = B.z_base + 0.10
    z_back = min(env.zr, B.z_hood) - 0.05
    Wa = env.W(B.z_arch)
    glassy = glass_colours(ac, B, env, skip_bones)
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGB'))
    H, W = tex.shape[:2]
    boxes = [(np.array(lo, float) + ev.eye, np.array(hi, float) + ev.eye) for lo, hi in B.spec.get('cut', [])]
    out, counts = {}, {'hood': 0, 'windscreen': 0, 'painted': 0, 'cut': 0}
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
            rule = None
            for lo, hi in boxes:
                if np.all(V >= lo) and np.all(V <= hi):
                    rule = 'cut'; break
            if rule is None and z1 >= z_back and z0 <= z_front:
                zc = np.clip(V[:, 2], env.zr, env.zf)
                Ws = np.array([env.W(float(z)) for z in zc])
                lips = np.array([env.lip(float(z)) for z in zc])
                if np.all(np.abs(V[:, 0]) <= Ws + 0.15):
                    if z0 >= z_back and z1 <= B.z_arch + 0.06 and np.all(V[:, 1] >= lips - 0.03):
                        rule = 'hood'
                    elif glassy and np.all(V[:, 1] >= lips - 0.60):
                        uv = UV[[v[2] for v in poly]].mean(0)
                        c = tuple(int(x) for x in tex[min(H - 1, int((1 - uv[1]) * H)), min(W - 1, int(uv[0] * W))])
                        if c in glassy:
                            rule = 'painted'
                if rule is None and z0 >= B.z_arch - 0.06 and z1 <= z_front and np.all(np.abs(V[:, 0]) <= Wa + 0.15):
                    skin = np.array([ev.skin_at(float(x), float(z)) for x, z in zip(V[:, 0], V[:, 2])])
                    if np.all(V[:, 1] > skin + 0.015):
                        rule = 'windscreen'
            if rule:
                sel.add(i); counts[rule] += 1
        if sel:
            out[b['name']] = sel
    return out, counts


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
    """(the panel glass offset inward by t along smoothed vertex normals, normal lookup for any point): normals
    point away from the canopy centre"""
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
    keys = np.array(list(nv.keys()), float); vals = np.array(list(nv.values()), float)

    def normal_at(p):
        k = key(p)
        if k in nv:
            return nv[k]
        return vals[int(np.argmin(((keys - np.asarray(p, float)) ** 2).sum(1)))]
    return out, normal_at


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
    uv_paint, uv_inner = sw.uv(paint), sw.uv(INNER)
    got = sw.got(paint)

    # the old canopy
    cockpit = next((b['name'] for b in ac.bones if b['name'].endswith('__cockpit')), None)
    rm, counts = old_canopy(ac, B, env, {cockpit} if cockpit else set())
    removed = 0
    for b in ac.bones:
        if b['name'] in rm:
            pm = b['poly_mesh']
            gone = rm[b['name']]
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone]
            removed += len(gone)

    # members: the rail round the glass's edge, the rings (arch, bows, the hinge frame at the hood's end when
    # glazing runs on behind it) and the windscreen posts
    fr = spec.get('frame', {})
    rail_w, bow_w, arch_w, post_w = fr.get('rail_w', RAIL_W), fr.get('bow_w', FRAME_W), fr.get('arch_w', FRAME_W), fr.get('post_w', POST_W)
    centre = np.array([0.0, env.lip(env.zc) - 0.35, env.zc])
    mesh = L.Mesh()
    M = Members(mesh, lambda p: unit(np.asarray(p) - centre), uv_paint, uv_inner)
    loop = B.perimeter()
    M.sweep(loop, rail_w, RAIL_OUT, RAIL_IN, closed=True, shift=fr.get('rail_shift', RAIL_SHIFT))
    rear_kind = spec.get('rear', {}).get('type', 'fairing')
    bows = [(k, pts) for k, pts in B.frames if k == 'bow']
    for k, pts in B.frames:
        if k == 'bow' and rear_kind == 'fairing' and pts is bows[-1][1]:
            continue                                   # the hood's end: the rail closes there
        w = {'post': post_w, 'arch': arch_w}.get(k, bow_w)
        M.sweep(pts, w, FRAME_OUT, FRAME_IN)
    pm = mesh.poly_mesh()
    hull = ac.bone('hull')
    piece = cockpit.split('__', 1)[0] if cockpit else 'wreck_fuselage_1'
    bone = {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == cockpit), len(ac.bones) - 1) + 1
    ac.bones.insert(at, bone)

    # thick glass: the panel glass as a solid: outer sheet, inner sheet GLASS_T under it, and the edge between
    # them round the perimeter. Other glass in the asset (the HUD combiner, a gunsight) stays; the panel sheets
    # of an earlier run are dropped by their vertices.
    a = ac.asset
    cg = a.get('CanopyGlass')
    if cg and not spec.get('skip'):
        cur = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        panel = np.asarray(tris, float).reshape(-1, 3, 3)
        inner, nv = inner_sheet(panel, centre, GLASS_T)
        keyset = {tuple(np.round(p, 3)) for t in list(panel) + list(inner) for p in t}
        keep = [t for t in cur if not all(tuple(np.round(p, 3)) in keyset for p in t)]
        band = []
        Lp = np.array(loop, float)
        for q0, q1 in zip(Lp, np.vstack([Lp[1:], Lp[:1]])):
            i0, i1 = q0 - nv(q0) * GLASS_T, q1 - nv(q1) * GLASS_T
            band += [[q0, q1, i1], [q0, i1, i0]]
        cg['Triangles'] = [round(float(v), 4) + 0.0 for t in list(keep) + list(panel) + list(inner) + band for p in t for v in p]
    vol = L.loader_volume(pm)
    report = (f'{vid}: removed {removed} old canopy faces from {sorted(rm)} ({", ".join(f"{k} {n}" for k, n in counts.items())}); '
              f'{len(pm["polys"])} canopy faces in {bone["name"]} (volume {vol:+.0f}); paint {paint} -> {got}; '
              f'glass {len(cg["Triangles"]) // 9 if cg else 0} tris')
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
