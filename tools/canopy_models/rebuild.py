#!/usr/bin/env python3
"""Cockpit-and-canopy rebuild, in three steps from the aircraft's original model (git ref, default origin/main):

1. Cut. A defined volume is cut out, and only faces lying wholly inside it go:
     * the cockpit well: between the rails (inside the canopy's width at the sill), from the rear bulkhead to the
       windscreen base, from below the floor up: the old interior (seat, panel, consoles, glareshield, sight);
     * the old canopy above the rails: parts standing wholly above the rail line inside the canopy's footprint (a
       hoop, frame bars, mirrors), and faces with a see-through texture there (the model's own glass);
     * all of the old CanopyGlass.
   Faces crossing the volume's boundary (fuselage skin, the rails, the deck under the windscreen) stay. The cut is
   checked: every removed face is reported with the rule that took it, and none may have a corner outside the volume.
2. Canopy (canopy.py): the new canopy is lofted from the old glass's footprint into the clean opening.
3. Cockpit (cockpit_models/cockpit_lib.py): a modelled cockpit is built under the new canopy.

Usage: python3 tools/canopy_models/rebuild.py [--ref=origin/main] [--dry-run] id...
"""
import json, os, subprocess, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import canopy as C  # noqa: E402
L, S = C.L, C.S
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
import fleet as F  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
COCKPITS_REF = os.environ.get('BVP_COCKPITS_REF', 'origin/claude/fleet-modeled-cockpits')
REL = 'bvp/src/generated/resources/assets/berts_vehicle_pack/'


def git_json(ref, path):
    git = os.environ.get('BVP_GIT', 'git')           # git need not be on PATH (BVP_GIT names it)
    out = subprocess.run([git, '-C', ROOT, 'show', f'{ref}:{path}'], capture_output=True, check=True).stdout
    return json.loads(out.decode('utf-8'))


def load(vid, ref):
    ac = L.Aircraft(vid)
    ac.geo = git_json(ref, f'{REL}custom_geo/{vid}.geo.json')
    ac.asset = git_json(ref, f'{REL}sbw/vehicles/{vid}.json')
    ac.bones = ac.geo['minecraft:geometry'][0]['bones']
    cg = ac.asset.get('CanopyGlass')
    ac.glass = np.array(cg['Triangles'], float).reshape(-1, 3, 3) if cg else np.zeros((0, 3, 3))
    return ac


class Volume:
    """the cut: the cockpit well and the old canopy's footprint above the rails"""

    def __init__(self, tub, glass, spec):
        E = tub.E
        self.x0 = E[0]
        self.half = spec.get('well_half_width', tub.Wg - 0.01)
        self.z0 = E[2] + spec.get('well_rear', tub.back_z - E[2] - 0.08)
        self.z1 = E[2] + spec.get('well_front', tub.ws_z - E[2] + 0.02)
        self.floor = tub.floor_y - 0.3
        self.rail = tub.sill + spec.get('rail_lift', 0.02)
        G = glass.reshape(-1, 3)
        self.glo, self.ghi = G.min(0) - 0.08, G.max(0) + 0.08
        self.shape, self.panel_z = None, tub.Z
        self.split = {}                       # {bone: set(poly)} faces to split along the canopy outline
        self.holes = np.zeros((0, 3, 2))      # plan (x, z) triangles of removed top surfaces ahead of the panel
        self.boxed = set()                    # faces the old-interior rules took (inside the box)

    def over_hole(self, x, z):
        """True when (x, z) lies under a removed upward-facing surface ahead of the panel (in plan)"""
        if not len(self.holes):
            return False
        T = self.holes
        q = np.array([x, z])
        a, b, c = T[:, 0], T[:, 1], T[:, 2]
        cr = lambda u, v: u[..., 0] * v[..., 1] - u[..., 1] * v[..., 0]
        d1, d2, d3 = cr(b - a, q - a), cr(c - b, q - b), cr(a - c, q - c)
        return bool((((d1 >= 0) & (d2 >= 0) & (d3 >= 0)) | ((d1 <= 0) & (d2 <= 0) & (d3 <= 0))).any())

    def in_box(self, V):
        return bool(np.all(np.abs(V[:, 0] - self.x0) < self.half) and np.all(V[:, 2] > self.z0) and
                    np.all(V[:, 2] < self.z1) and np.all(V[:, 1] > self.floor))

    def in_well(self, V):
        if not self.in_box(V):
            return False
        # ahead of the panel the well narrows with the new canopy: only what its outline covers goes (the deck
        # beside a pointed canopy nose stays)
        if self.shape is not None:
            for p in V:
                if p[2] > self.panel_z:
                    z = min(max(p[2], self.shape.rear), self.shape.front)
                    if abs(p[0] - self.shape.x0) > self.shape.at(z)[0] + 0.01:
                        return False
        return True

    def in_footprint(self, V):
        """per corner: inside the canopy's plan outline (any height)"""
        return ((V[:, 0] > self.glo[0]) & (V[:, 0] < self.ghi[0]) & (V[:, 2] > self.glo[2]) & (V[:, 2] < self.ghi[2]))

    def in_canopy(self, V):
        return bool(np.all(V[:, 1] > self.rail) and self.in_footprint(V).all())


def cut(ac, vol, tex, tub, glass, canopy=True):
    """{bone: set(poly)} removed, and {rule: count}. canopy=False: the cockpit interior only (the old canopy's
    frames, posts and glass faces all stay)"""
    out, why = {}, {'well': 0, 'canopy part': 0, 'old glass face': 0}
    canopy_parts = set()
    for b in ac.bones:
        if not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        polys = pm['polys']
        verts = [P[[v[0] for v in p]] for p in polys]
        # connected parts (faces sharing corners): ahead of the panel only parts lying wholly in the cut box go (an
        # old glareshield, a sight block); a part reaching out of it is fuselage skin (the deck under a windscreen)
        parent = {}
        def root(x):
            while parent.setdefault(x, x) != x:
                parent[x] = parent[parent[x]]; x = parent[x]
            return x
        key = lambda i: tuple(np.round(P[i], 4))
        for p in polys:
            ks = [key(v[0]) for v in p]
            for k in ks[1:]:
                parent[root(ks[0])] = root(k)
        parts = {}
        for i, p in enumerate(polys):
            parts.setdefault(root(key(p[0][0])), []).append(i)
        part_of = {i: r for r, faces in parts.items() for i in faces}
        boxed = {r: all(vol.in_box(verts[i]) for i in faces) for r, faces in parts.items()}
        sel = set()
        for i, V in enumerate(verts):
            ahead = V[:, 2].max() > vol.panel_z
            if ahead and not boxed[part_of[i]]:
                continue                                  # fuselage skin ahead of the panel: never cut
            if vol.in_well(V):
                sel.add(i); why['well'] += 1
            elif ahead and vol.in_box(V) and vol.shape is not None:
                # ahead of the panel, straddling the new canopy's outline: split there (outside part stays)
                vol.split.setdefault(b['name'], set()).add(i)
            elif canopy and vol.in_canopy(V) and S._see_through(tex, U[[v[2] for v in polys[i]]]):
                sel.add(i); why['old glass face'] += 1
        # connected parts standing wholly above the rails in the canopy's footprint: hoops, frame bars, mirrors
        for faces in (parts.values() if canopy else ()):
            if len(faces) > 400:
                continue
            V = np.concatenate([verts[i] for i in faces])
            above = vol.in_footprint(V) & (V[:, 1] > vol.rail)
            # a hoop or frame whose legs reach down into the fuselage still stands mostly above the rails
            if above.mean() >= 0.7 and V[:, 1].max() > vol.rail + 0.1 and vol.in_footprint(V).all():
                new = set(faces) - sel
                sel |= new; why['canopy part'] += len(new)
                canopy_parts.update((b['name'], i) for i in new)
        if sel:
            out[b['name']] = sel
    # old canopy frame members welded into the hull mesh: faces along the old glass (strip.py's test) that stand
    # wholly above the rails in the footprint; the rails themselves and the deck under the glass stay
    along, _ = S.find(ac, tub, canopy=glass) if canopy else ({}, None)
    for name, faces in along.items():
        pm = next(b for b in ac.bones if b['name'] == name)['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for i in faces:
            V = P[[v[0] for v in pm['polys'][i]]]
            # wholly above the rails, or a tall member (a windscreen post) reaching down past them; never the rails
            ax = np.linalg.svd(V - V.mean(0))[2] if len(V) >= 3 else np.eye(3)
            ext = np.ptp((V - V.mean(0)) @ ax.T, axis=0)
            thin = ext[0] > 0.12 and ext[1] < 0.07           # a bar: long one way, narrow the other
            tall = vol.in_footprint(V).all() and V[:, 1].max() > vol.rail + 0.10 and \
                V[:, 1].min() > vol.rail - (0.35 if thin else 0.15)
            if (vol.in_canopy(V) or tall) and i not in out.get(name, set()):
                out.setdefault(name, set()).add(i); why['canopy part'] += 1
                if not vol.in_canopy(V):
                    canopy_parts.add((name, i))
    # old windscreen posts and sight struts: thin bars ahead of the panel, above its top, in the canopy's footprint
    # (where the old glass never covered the windscreen, they do not lie along it)
    for b in (ac.bones if canopy else ()):
        if not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for i, poly in enumerate(pm['polys']):
            if i in out.get(b['name'], set()):
                continue
            V = P[[v[0] for v in poly]]
            if len(V) < 3 or V[:, 2].min() < vol.panel_z - 0.15 or V[:, 1].max() < tub.top_c + 0.10 or \
                    not vol.in_footprint(V).all():
                continue                                  # (a post rises well above the panel top from near it)
            ax = np.linalg.svd(V - V.mean(0))[2]
            ext = np.ptp((V - V.mean(0)) @ ax.T, axis=0)
            if ext[0] > 0.10 and ext[1] < 0.06:
                out.setdefault(b['name'], set()).add(i); why['canopy part'] += 1
                canopy_parts.add((b['name'], i))
    # the glareshield zone ahead of the panel: the cockpit tool's old-interior rules (old decks, HUD housings and
    # sight mounts under the glass, windscreen frames and thin members along the glass kept), inside the cut box
    old_rm, old_clips = L.old_interior(ac, [tub])
    why['old interior rule'] = 0
    for b in ac.bones:
        sel = old_rm.get(b['name'])
        if not sel:
            continue
        P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
        for i in sel:
            V = P[[v[0] for v in b['poly_mesh']['polys'][i]]]
            # the cockpit tool's rules keep themselves to the tub and the glareshield zone (its own bounds), so all
            # they select goes: an old panel shroud may reach past the cut box
            if i not in out.get(b['name'], set()):
                out.setdefault(b['name'], set()).add(i); why['old interior rule'] += 1
                vol.boxed.add((b['name'], i))
    # and its cuts: a deck running on under the windscreen keeps only its part ahead of the windscreen base
    why['clipped'] = 0
    for b in ac.bones:
        for i, (axis, c) in old_clips.get(b['name'], {}).items():
            if i not in out.get(b['name'], set()):
                L.clip_keep(b['poly_mesh'], i, axis, c); why['clipped'] += 1
    if not canopy:
        # keeping the old canopy: its frames (faces along the old glass above the rails, windscreen posts) are
        # protected from every rule above, the well's included (the well has no ceiling)
        guard, _ = S.find(ac, tub, canopy=glass)
        why['canopy kept'] = 0
        for b in ac.bones:
            pm = b.get('poly_mesh')
            if not pm or b['name'] not in out:
                continue
            P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
            for i in list(out[b['name']]):
                V = P[[v[0] for v in pm['polys'][i]]]
                frame = i in guard.get(b['name'], ()) and V[:, 1].max() > vol.rail
                post = False
                if len(V) >= 3 and V[:, 1].max() > tub.top_c + 0.10 and vol.in_footprint(V).all() and \
                        V[:, 2].min() > vol.panel_z - 0.15:
                    ax = np.linalg.svd(V - V.mean(0))[2]
                    ext = np.ptp((V - V.mean(0)) @ ax.T, axis=0)
                    post = ext[0] > 0.10 and ext[1] < 0.06
                if frame or post:
                    out[b['name']].discard(i); why['canopy kept'] += 1
    vol.parts = canopy_parts
    return out, why


def check(ac, rm, vol):
    """every removed face lies wholly in the volume (the well, or above the rails in the canopy's footprint) or
    belongs to an old canopy part (inside the footprint): -> list of offending faces"""
    bad = []
    for b in ac.bones:
        for i in rm.get(b['name'], ()):
            P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
            V = P[[v[0] for v in b['poly_mesh']['polys'][i]]]
            part = (b['name'], i) in vol.parts and vol.in_footprint(V).all()
            boxed = (b['name'], i) in vol.boxed                  # the cockpit tool's own bounded rules
            if not (vol.in_well(V) or vol.in_canopy(V) or part or boxed):
                bad.append((b['name'], i))
    return bad


class Skin:
    """the fuselage's outer surface after the cut (hull bones, no new cockpit or canopy): its height over a point and
    its side distance, for keeping new geometry inside it"""

    def __init__(self, ac):
        T = []
        for b in ac.bones:
            pm = b.get('poly_mesh')
            if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']) or \
                    b['name'].endswith(('__cockpit', '__canopy')):
                continue
            P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
            for poly in pm['polys']:
                V = P[[v[0] for v in poly]]
                for k in range(1, len(V) - 1):
                    T.append(V[[0, k, k + 1]])
        self.T = np.array(T).reshape(-1, 3, 3)

    def _hits(self, q, ia, ib, ic):
        """values of coordinate ic where the line through q (coordinates ia, ib) meets the triangles"""
        T = self.T
        a, b, c = T[:, 0][:, [ia, ib]], T[:, 1][:, [ia, ib]], T[:, 2][:, [ia, ib]]
        cr = lambda u, v: u[..., 0] * v[..., 1] - u[..., 1] * v[..., 0]
        d1, d2, d3 = cr(b - a, q - a), cr(c - b, q - b), cr(a - c, q - c)
        area = cr(b - a, c - a)
        m = (((d1 >= 0) & (d2 >= 0) & (d3 >= 0)) | ((d1 <= 0) & (d2 <= 0) & (d3 <= 0))) & (np.abs(area) > 1e-9)
        w0, w2 = d2[m] / area[m], d3[m] / area[m]
        return w0 * T[m, 0, ic] + (1 - w0 - w2) * T[m, 1, ic] + w2 * T[m, 2, ic]

    def top(self, x, z, below=np.inf):
        """the highest skin height over (x, z) under [below], or -inf"""
        y = self._hits(np.array([x, z]), 0, 2, 1)
        y = y[y < below]
        return float(y.max()) if len(y) else -np.inf


def keep_inside(mesh, skin, ac, E, margin=0.008):
    """pulls new cockpit geometry back inside the fuselage: a corner outside the skin sideways moves in to it, one
    above the top skin where no canopy glass covers it moves down under it. -> number of corners moved"""
    moved = 0
    new = []
    for pts, uvs, n in mesh.faces:
        P = np.array(pts, float)
        for k, p in enumerate(P):
            if abs(p[0] - E[0]) > 0.05:
                d = skin._hits(np.array([p[1], p[2]]), 1, 2, 0) - E[0]
                side = np.sign(p[0] - E[0])
                walls = d[d * side > 0.02] * side
                if len(walls):
                    lim = walls.max()                 # the outermost skin that way
                    if abs(p[0] - E[0]) > lim - margin:
                        P[k, 0] = E[0] + side * (lim - margin); moved += 1
            if ac.glass_above(p[0], p[2], p[1] - 0.05) is None:
                top = skin.top(p[0], p[2])
                if np.isfinite(top) and p[1] > top - margin and p[1] < top + 0.3:
                    P[k, 1] = top - margin; moved += 1
        new.append((P, uvs, n))
    mesh.faces = new
    return moved


def _clip(poly, inside):
    """Sutherland-Hodgman: poly = [(point3, uv2)], inside(point) -> signed value (>= 0 keeps); linear crossing"""
    out = []
    for k in range(len(poly)):
        (p, u), (q, w) = poly[k], poly[(k + 1) % len(poly)]
        a, b = inside(p), inside(q)
        if a >= 0:
            out.append((p, u))
        if (a >= 0) != (b >= 0):
            t = a / (a - b)
            out.append((p + (q - p) * t, u + (w - u) * t))
    return out


def footprints(glass):
    """the canopy glass's plan outline: a convex (x, z) polygon, counter-clockwise, per canopy (split where the glass
    has a z gap over 0.3)"""
    import vis as VS
    G = glass.reshape(-1, 3)
    order = np.argsort(G[:, 2]); cut = [0]
    for i in range(1, len(order)):
        if G[order[i], 2] - G[order[i - 1], 2] > 0.3:
            cut.append(i)
    cut.append(len(order))
    out = []
    for a, b in zip(cut, cut[1:]):
        h = np.array(VS._hull2(G[order[a:b]][:, [0, 2]]), float)
        if len(h) >= 3:
            area = 0.5 * np.sum(h[:, 0] * np.roll(h[:, 1], -1) - np.roll(h[:, 0], -1) * h[:, 1])
            out.append(h if area > 0 else h[::-1])
    return out


def split_footprint(pm, i, hulls):
    """splits poly i at the canopy's plan outline: the pieces outside it join the bone as new polys (the same surface,
    so the outside looks the same) and the piece under the canopy is left out. -> pieces added (0: nothing outside,
    the poly is not split)"""
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    U = np.array(pm['uvs'], float)
    poly = pm['polys'][i]
    rem = [(P[v[0]], U[v[2]]) for v in poly]
    ni = poly[0][1]
    def inside(h, p):
        return all((h[(k + 1) % len(h)][0] - h[k][0]) * (p[2] - h[k][1]) -
                   (h[(k + 1) % len(h)][1] - h[k][1]) * (p[0] - h[k][0]) > 0 for k in range(len(h)))
    h = next((h for h in hulls if any(inside(h, p) for p, _ in rem)), None)
    if h is None:
        return 0
    pieces = []
    for k in range(len(h)):
        a, b = h[k], h[(k + 1) % len(h)]
        side = lambda p, a=a, b=b: (b[0] - a[0]) * (p[2] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
        out = _clip(rem, lambda p, s=side: -s(p))
        if len(out) >= 3:
            pieces.append(out)
        rem = _clip(rem, side)
        if len(rem) < 3:
            break
    def area(pc):
        Q = np.array([p for p, _ in pc])
        return 0.5 * np.linalg.norm(sum(np.cross(Q[k], Q[(k + 1) % len(Q)]) for k in range(len(Q))))
    pieces = [pc for pc in pieces if area(pc) > 1e-6]
    if not pieces or len(rem) < 3 or area(rem) < 1e-6:
        return 0
    for piece in pieces:
        new = []
        for p, u in piece:
            pm['positions'].append([round(float(p[0]) * 16, 4) + 0.0, round(float(p[1]) * 16, 4) + 0.0,
                                    round(-float(p[2]) * 16, 4) + 0.0])
            pm['uvs'].append([round(float(u[0]), 6), round(float(u[1]), 6)])
            new.append([len(pm['positions']) - 1, ni, len(pm['uvs']) - 1])
        pm['polys'].append(new)
    return len(pieces)


def split_outline(pm, faces, shape, vol):
    """replaces each face by its parts outside the new canopy's plan outline (|x - x0| > w(z), each side); the
    pieces join the bone as new polys (the originals are removed by the caller) -> pieces added"""
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    U = np.array(pm['uvs'], float)
    added = 0
    for i in faces:
        poly = pm['polys'][i]
        pts = [(P[v[0]], U[v[2]]) for v in poly]
        ni = poly[0][1]
        def width(z):
            return shape.at(min(max(z, shape.rear), shape.front))[0] + 0.01
        for side in (1, -1):
            # the outside of one boundary, and (for the other side) nothing of the canopy's inside
            piece = _clip(pts, lambda p, s=side: s * (p[0] - shape.x0) - width(p[2]))
            # and only below the canopy's sill line: the old glareshield's walls do not stand up beside the new glass
            # (6 mm under the deck panels that close the opening there, so the two never fight for the same pixels)
            piece = _clip(piece, lambda p: float(shape.ys(min(max(p[2], shape.rear), shape.front))) - 0.006 - p[1]) \
                if len(piece) >= 3 else piece
            if len(piece) < 3:
                continue
            new = []
            for p, u in piece:
                pm['positions'].append([round(float(p[0]) * 16, 4) + 0.0, round(float(p[1]) * 16, 4) + 0.0,
                                        round(-float(p[2]) * 16, 4) + 0.0])
                pm['uvs'].append([round(float(u[0]), 6), round(float(u[1]), 6)])
                new.append([len(pm['positions']) - 1, ni, len(pm['uvs']) - 1])
            pm['polys'].append(new)
            added += 1
    return added


def deck(m, vol, ac, shape, tub, uv, front, skin, step=0.01):
    """closes the cut opening wherever the new canopy does not cover it (a pointed canopy nose narrower than the old
    opening): flat panels at the canopy's sill line, in the aircraft's paint, only over holes (where the fuselage
    has no skin near that height) -> number of panels"""
    E = tub.E
    n = 0
    reach = vol.half + 0.15                      # the old opening may be wider than the cut (the old glass covered it)
    xs = np.arange(-reach, reach - step + 1e-6, step)
    for z in np.arange(max(vol.z0, vol.panel_z), front, step):
        cz = z + step / 2
        y = float(shape.ys(min(max(cz, shape.rear), shape.front)))
        for x in xs:
            cx = E[0] + x + step / 2
            if abs(cx - shape.x0) <= shape.at(min(max(cz, shape.rear), shape.front))[0] - 0.03:
                continue                                   # under the canopy (the panels tuck in under its sill frame)
            if not vol.over_hole(cx, cz):
                continue                                   # only where a removed top surface was: nowhere else
            q = [[cx - step / 2, y, cz - step / 2], [cx + step / 2, y, cz - step / 2],
                 [cx + step / 2, y, cz + step / 2], [cx - step / 2, y, cz + step / 2]]
            m.flat(q, uv, normal=[0, 1, 0]); n += 1
    return n


def rebuild(vid, ref='origin/main', dry=False):
    ac = load(vid, ref)
    tex = np.asarray(Image.open(L.texture_path(vid)).convert('RGBA')).astype(np.int16)
    sw = L.Swatches(L.texture_path(vid))
    fspec = F.FLEET['aircraft'].get(vid, {})
    era = C.era_of(vid)
    seat = fspec.get('seat', 0)
    E0 = ac.eye(seat)
    ac.drop_sight_glass(E0, fspec.get('tub', {}).get('s', 1.0))     # the old gunsight / HUD plates: not canopy
    tub_kw = dict(fspec.get('tub', {}))
    tub_kw.setdefault('hinge_frac', L.ERAS[era].get('hinge_frac', 0.5))
    tub = L.Tub(ac, seat, **tub_kw)
    ac.drop_glass_inside(tub)
    old = ac.glass.copy()
    spec = C.spec_for(vid, ac, tub.E, [tub.E], None)
    vol = Volume(tub, old, spec)
    shape = C.Shape(old, spec, tub.E, sill_ref=(tub.back_z, tub.Z + 0.1, tub.sill))
    vol.shape = shape

    # 1. cut
    rm, why = cut(ac, vol, tex, tub, old)
    bad = check(ac, rm, vol)
    if bad:
        raise SystemExit(f'{vid}: cut check failed, {len(bad)} faces outside the volume: {bad[:5]}')
    paint = tuple(spec.get('paint_rgb') or C.frame_rgb(ac, rm, tub, tex) or C.paint_rgb(ac, tub, tex))
    well_front = tub.ws_z                      # the front edge of the cut cockpit well
    for b in ac.bones:
        if b['name'] in rm:
            P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
            for i in rm[b['name']]:
                if (b['name'], i) not in vol.parts:
                    well_front = max(well_front, float(P[[v[0] for v in b['poly_mesh']['polys'][i]], 2].max()))
    holes = []                                 # what the deck panels may close: removed top surfaces ahead of the panel
    for b in ac.bones:
        for i in rm.get(b['name'], set()) | vol.split.get(b['name'], set()):
            P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
            V = P[[v[0] for v in b['poly_mesh']['polys'][i]]]
            n = np.cross(V[1] - V[0], V[-1] - V[0]); ln = np.linalg.norm(n)
            if ln < 1e-9 or abs(n[1]) / ln < 0.6 or V[:, 2].max() < vol.panel_z or (b['name'], i) in vol.parts:
                continue
            for k in range(1, len(V) - 1):
                holes.append(V[[0, k, k + 1]][:, [0, 2]])
    vol.holes = np.array(holes).reshape(-1, 3, 2)
    n_split = 0
    for b in ac.bones:
        if b['name'] in vol.split:
            n_split += split_outline(b['poly_mesh'], vol.split[b['name']] - rm.get(b['name'], set()), shape, vol)
            rm.setdefault(b['name'], set()).update(vol.split[b['name']])
    for b in ac.bones:
        if b['name'] in rm:
            b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if i not in rm[b['name']] and p]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith(('__cockpit', '__canopy'))]
    skin = Skin(ac)

    # 2. canopy; its front ends where its crown meets the nose skin (no frame tip jutting out ahead of it)
    for z in np.arange(shape.front, tub.E[2], -0.01):
        w, ys, c = shape.at(z)
        if c > skin.top(shape.x0, z, below=c + 0.5) + 0.01:
            shape.front = float(z)
            break
    cmesh, cglass = C.build(ac, tub, shape, spec, sw, paint)
    ac.glass = np.array(cglass, float).reshape(-1, 3, 3)
    ac.__dict__.pop('_skin', None); ac.__dict__.pop('_all', None)

    # 3. cockpit, under the new canopy. The coaming reaches forward to the front of the cut (the opening's front
    # edge), so no hole is left between it and the nose skin
    if well_front > tub.ws_z:
        g = ac.glass_above(tub.E[0], well_front - 0.02, tub.sill - 0.4)
        tub.ws_z = float(well_front)
        if g is not None:
            tub.ws_y = float(min(tub.ws_y, g - 0.015))
    mesh, stick, gauges, displays, hud_glass, hud = cockpit(ac, tub, era, fspec, sw)
    moved = keep_inside(mesh, skin, ac, tub.E)
    skin = Skin(ac)                              # with the split pieces
    decked = deck(cmesh, vol, ac, shape, tub, sw.uv(paint), well_front, skin)
    source = max(rm, key=lambda k: len(rm[k])) if rm else 'wreck_fuselage_1__hull'
    piece = source.split('__', 1)[0] if source.startswith('wreck_') else 'wreck_fuselage_1'
    hull = ac.bone('hull')
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
    ac.bones.insert(at, {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']),
                         'poly_mesh': cmesh.poly_mesh()})
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']),
                         'poly_mesh': mesh.poly_mesh()})
    write_instruments(ac, seat, gauges, displays, hud, tub)
    tri = list(ac.glass.reshape(-1, 3)) + [np.array(p, float) for p in hud_glass]
    ac.asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
                               'Triangles': [round(float(v), 4) + 0.0 for p in tri for v in p]}
    rep = (f'{vid} ({era}): cut {sum(len(v) for v in rm.values())} faces {why} from {sorted(rm)}; canopy z '
           f'{shape.rear:.2f}..{shape.front:.2f} paint {paint}; cockpit {len(mesh.faces)} faces, {len(gauges)} gauges, '
           f'{len(displays)} displays, HUD {"yes" if hud else "no"}; {moved} corners pulled inside the skin, {n_split} split pieces kept, {decked} deck panels')
    if not dry:
        with open(ac.geo_path, 'w', newline='\n', encoding='utf-8') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        C.write_asset_lf(ac.asset_path, ac.asset)
    return ac, rm, vol, rep


class Well:
    """the strict cut for --interior: the cockpit under the original canopy. A face goes only when every corner lies
    between the rails (inside the canopy's width at the sill), from behind the rear bulkhead to the windscreen base,
    and under the original canopy glass, clear of it (frames and rails along the glass stay), and it is not outer
    skin (cut_well's exterior test). The nose ahead of the windscreen, the deck behind the seat, the canopy and all
    skin stay, so the aircraft's outside is unchanged."""

    def __init__(self, tub, ac):
        E = tub.E
        self.ac = ac
        self.x0, self.half = E[0], tub.Wg - 0.01
        self.z0, self.z1 = tub.back_z - 0.08, tub.ws_z + 0.05
        self.floor = tub.floor_y - 0.3
        self.clear = 0.03                     # a face this close under the glass is a canopy frame or rail: kept

    def _plan(self, V):
        return bool(np.all(np.abs(V[:, 0] - self.x0) < self.half) and np.all(V[:, 2] > self.z0) and
                    np.all(V[:, 2] < self.z1) and np.all(V[:, 1] > self.floor))

    def under_glass(self, V):
        """every corner lies under the original canopy glass, clear of it"""
        for p in V:
            g = self.ac.glass_above(p[0], p[2], p[1])
            if g is None or g - p[1] < self.clear:
                return False
        return True

    def face_in(self, V):
        return self._plan(V) and self.under_glass(V)

    part_in = face_in


def cut_well(ac, well, glass):
    """{bone: set(poly)} of the cockpit interior inside the well, and counts"""
    out, why = {}, {'well faces': 0, 'loose interior parts': 0}
    for b in ac.bones:
        if not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        polys = pm['polys']
        verts = [P[[v[0] for v in p]] for p in polys]
        def exterior(V):
            # the aircraft's outer skin: a ray from the face, either way along its normal but not up through the
            # cockpit opening, gets out of the airframe (the old glass ignored)
            n = np.cross(V[1] - V[0], V[-1] - V[0]); ln = np.linalg.norm(n)
            if ln < 1e-9:
                return False
            n = n / ln
            c = V.mean(0)
            for d in (n, -n):
                if d[1] < 0.5 and ac.escapes(c + d * 0.004, d):
                    return True
            return False
        sel = {i for i, V in enumerate(verts) if well.face_in(V) and not exterior(V)}
        skin_kept = sum(1 for i, V in enumerate(verts) if well.face_in(V)) - len(sel)
        why['skin inside the box kept'] = why.get('skin inside the box kept', 0) + skin_kept
        why['well faces'] += len(sel)
        parent = {}
        def root(x):
            while parent.setdefault(x, x) != x:
                parent[x] = parent[parent[x]]; x = parent[x]
            return x
        key = lambda i: tuple(np.round(P[i], 4))
        for p in polys:
            ks = [key(v[0]) for v in p]
            for k in ks[1:]:
                parent[root(ks[0])] = root(k)
        parts = {}
        for i, p in enumerate(polys):
            parts.setdefault(root(key(p[0][0])), []).append(i)
        for faces in parts.values():
            if len(faces) > 400 or set(faces) <= sel:
                continue
            V = np.concatenate([verts[i] for i in faces])
            if not well.part_in(V):
                continue                                  # it reaches out of the well: skin or canopy
            if len(glass) and ac.touches_glass(np.unique(np.round(V, 4), axis=0), gap=0.03):
                continue                                  # it meets the old glass: a canopy member
            if any(exterior(verts[i]) for i in faces if i not in sel):
                continue                                  # part of the outer skin
            new = set(faces) - sel
            sel |= new; why['loose interior parts'] += len(new)
        if sel:
            out[b['name']] = sel
    return out, why


OUTBOARD = 0.6                            # of the canopy half-width at the sill
FORMED = False                            # --formed: the cockpit shaped to the measured space, canopy glass kept
DETAILED = False                          # --detailed: the detailed generator cockpit (0.11.129's), canopy glass kept,
                                          # leftovers in front of the pilot cleared
PART_RULE = os.environ.get('BVP_PART_RULE') == '1'   # off: it kept old shelves joined to the frames
HIGH = 0.15                               # blocks above the eye
DEBUG = {}                                # interior_strict's kept faces, for the review renders
FRAME = float(os.environ.get('BVP_FRAME', 0.0))  # off: the outside test and the cockpit tool's set hold frames                            # blocks: a face this close to the canopy glass is part of the canopy
SPIN = ('rotor', 'propeller', 'blade', 'turbine_fan', 'spinner')


def face_tris(ac, tex, bones=None):
    """(tris, uvs, owner [(bone name, poly index)]) of every poly of the given bones (default all but spinning parts
    and the pilot-view occluder), triangulated"""
    tris, uvs, own = [], [], []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        if bones is not None and b['name'] not in bones:
            continue
        leaf = b['name'].split('__')[-1]
        if bones is None and (b['name'].startswith('pilot_view_occluder') or any(s in leaf for s in SPIN)):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float) if pm.get('uvs') else np.zeros((1, 2))
        for i, p in enumerate(pm['polys']):
            idx = [v[0] for v in p]; tid = [v[2] if len(v) > 2 else 0 for v in p]
            for k in range(1, len(idx) - 1):
                tris.append(P[[idx[0], idx[k], idx[k + 1]]]); uvs.append(U[[tid[0], tid[k], tid[k + 1]]])
                own.append((b['name'], i))
    return np.array(tris).reshape(-1, 3, 3), np.array(uvs).reshape(-1, 3, 2), own


def seen_polys(tris, uvs, own, occ, centre, tex, through_opening=True):
    """{(bone, poly)} of the polys seen from outside (vis.visible, alpha-tested against the texture)"""
    import vis as VS
    H, W = tex.shape[:2]
    def alpha(i, w):
        u = w @ uvs[i][:, 0]; v = w @ uvs[i][:, 1]
        tx = np.clip((u * W).astype(int), 0, W - 1); ty = np.clip(((1 - v) * H).astype(int), 0, H - 1)
        return tex[ty, tx, 3] > 127
    radius = 4.0
    idx = np.arange(len(tris))         # all of it: geometry far along a view's depth still occludes
    s = VS.visible(tris[idx], occ, centre, radius, alpha=lambda i, w: alpha(idx[i], w), through_opening=through_opening)
    return {own[idx[i]] for i in s}


def interior_strict(vid, ref='origin/main', dry=False):
    """the cockpit interior rebuilt inside the original model with its outside untouched: in the cockpit well (Well's
    plan box) only faces that cannot be seen from outside go (vis.py: 26 orthographic views, the original canopy
    glass opaque), the detailed cockpit is built in their place, and any cockpit face that would show from outside is
    dropped. No CanopyGlass is written."""
    import vis as VS
    ac = load(vid, ref)
    tex = np.asarray(Image.open(L.texture_path(vid)).convert('RGBA'))
    data = json.load(open(os.path.join(ROOT, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles',
                                       vid + '.json'), encoding='utf-8'))
    eyes = [data['Attachments'][s['CameraPos']['EyeAttachment']]['Position'] for s in data.get('Seats', [])
            if (s.get('CameraPos') or {}).get('EyeAttachment') in data.get('Attachments', {})]
    sw = L.Swatches(L.texture_path(vid))
    fspec = F.FLEET['aircraft'].get(vid, {})
    era = C.era_of(vid)
    seat = fspec.get('seat', 0)
    all_glass = ac.glass.copy()          # every pane, for the outside test (the cockpit is fitted to the canopy's)
    ac.drop_sight_glass(ac.eye(seat), fspec.get('tub', {}).get('s', 1.0))
    tub_kw = dict(fspec.get('tub', {}))
    tub_kw.setdefault('hinge_frac', L.ERAS[era].get('hinge_frac', 0.5))
    tub = L.Tub(ac, seat, **tub_kw)
    ac.drop_glass_inside(tub)
    if not len(ac.glass):
        raise SystemExit(f'{vid}: no canopy glass to fit the cockpit under')
    well = Well(tub, ac)
    well.floor = tub.floor_y - 0.1
    well.half = 3.0 * tub.Wg           # the old panel runs into the fuselage sides; skin there is seen, so it stays
    centre = np.array(tub.E, float) + [0, -0.3, 0]
    # the occluder: the canopy glass with a skirt over the slits at its edges, pulled in toward the crew
    occ_glass = VS.pull_in(np.concatenate([all_glass, VS.skirt(all_glass, eyes)]), eyes)
    tris, uvs, own = face_tris(ac, tex)
    seen = seen_polys(tris, uvs, own, occ_glass, centre, tex)
    # the old interior, as the modelled-cockpit tool (cockpit_models/fleet.py) found it: the faces of the original
    # that its model no longer has. Only those may go, and of them only what is hidden and not canopy structure.
    pkey = lambda P, p: tuple(sorted(tuple(np.round(P[v[0]], 3)) for v in p))
    kept_by_tool = set()
    for b in load(vid, COCKPITS_REF).bones:
        pm = b.get('poly_mesh')
        if pm and pm.get('polys') and not b['name'].endswith('__cockpit'):
            P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
            kept_by_tool.update(pkey(P, p) for p in pm['polys'])
    import authored
    _mod = authored.module(vid)
    cut_boxes = list(getattr(_mod, 'CUT_BOXES', [])) if _mod is not None else []
    under_canopy = None
    if (FORMED or DETAILED) and not cut_boxes:
        # the formed cockpit clears the old interior in front of the pilot even where gaps in the windscreen glass
        # let an outside view see it, as long as every corner is under the canopy glass (the nose deck ahead of
        # the windscreen and the sills stay)
        ws_f = (tub.ws_z - tub.E[2]) / tub.s - 0.03          # behind the windscreen base: never the nose deck
        cut_boxes = [(-0.55, 0.55, -1.15, 0.05, 0.20, ws_f)]       # up to the eye's height: old glareshields
        under_canopy = lambda V: True
    rm, kept_frame, n_split = {}, 0, 0
    hulls = footprints(ac.glass)
    for b in ac.bones:                    # the cut: polys in the well's plan box that no outside view can see
        if not L.is_interior_bone(b['name']) and not b['name'].endswith('__cockpit'):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        verts = [P[[v[0] for v in p]] for p in pm['polys']]
        sel = {i for i, V in enumerate(verts) if (b['name'], i) not in seen and well._plan(V)
               and pkey(P, pm['polys'][i]) not in kept_by_tool}
        # an authored cockpit may name boxes (metres from the eye, r right, u up, f forward) where the old interior
        # the cockpit tool found goes even though an outside view sees it: the model's glass leaves the windscreen
        # open there, so the old panel counts as outside
        for r0, r1, u0, u1, f0, f1 in cut_boxes:
            for i, V in enumerate(verts):
                if pkey(P, pm['polys'][i]) in kept_by_tool:
                    continue
                rr, uu, ff = -(V[:, 0] - tub.E[0]) / tub.s, (V[:, 1] - tub.E[1]) / tub.s, (V[:, 2] - tub.E[2]) / tub.s
                if (rr.min() >= r0 and rr.max() <= r1 and uu.min() >= u0 and uu.max() <= u1 and ff.min() >= f0
                        and ff.max() <= f1) and (under_canopy is None or under_canopy(V)):
                    sel.add(i)
        # canopy frames, rails and the windscreen frame lie along the glass: a face whose centre is within FRAME of it
        # stays, and so does a loose part (up to 400 faces) with half of its faces within 1.5 FRAME
        near = {i for i in sel if FRAME > 0 and ac.touches_glass([verts[i].mean(0)], gap=FRAME)}
        # and nothing of the old cockpit stands wholly above the eye (HIGH): what does is canopy structure
        near |= {i for i in sel if verts[i][:, 1].min() > tub.E[1] + HIGH}
        # canopy posts and arches stand above the sill at the canopy's sides, where the cockpit has nothing
        near |= {i for i in sel if verts[i][:, 1].min() > tub.sill + 0.03 and
                 np.abs(verts[i][:, 0] - tub.E[0]).min() > OUTBOARD * tub.Wg and
                 abs(np.sign(verts[i][:, 0] - tub.E[0]).sum()) == len(verts[i])}
        close = {i for i in sel if i in near or FRAME > 0 and ac.touches_glass([verts[i].mean(0)], gap=1.5 * FRAME)}
        parent = {}
        def root(x):
            while parent.setdefault(x, x) != x:
                parent[x] = parent[parent[x]]; x = parent[x]
            return x
        key = lambda i: tuple(np.round(P[i], 4))
        for p in pm['polys']:
            ks = [key(v[0]) for v in p]
            for k in ks[1:]:
                parent[root(ks[0])] = root(k)
        parts = {}
        for i, p in enumerate(pm['polys']):
            parts.setdefault(root(key(p[0][0])), []).append(i)
        for faces in parts.values():                # a frame member: most of a loose part lies along the glass
            if PART_RULE and len(faces) <= 400 and len(close & set(faces)) >= 0.5 * len(faces):
                near |= set(faces) & sel
        kept_frame += len(near)
        sel -= near
        DEBUG.setdefault('frame', {})[b['name']] = near
        DEBUG.setdefault('seen', {})[b['name']] = {i for i, V in enumerate(verts)
                                                   if (b['name'], i) in seen and well._plan(V)}
        # skin the cockpit tool took that reaches in under the canopy (a deck face running from outside the
        # windscreen over the panel): split at the canopy's plan outline, the outside part kept as it is
        n0 = len(pm['polys'])
        for i in range(n0):
            if (b['name'], i) in seen and well._plan(verts[i]) and pkey(P, pm['polys'][i]) not in kept_by_tool \
                    and verts[i][:, 1].min() < tub.E[1] + HIGH and os.environ.get('BVP_SPLIT', '0') == '1':
                k = split_footprint(pm, i, hulls)
                if k:
                    sel.add(i); n_split += 1
        if sel:
            rm[b['name']] = sel
    for b in ac.bones:
        if b['name'] in rm:
            b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if i not in rm[b['name']]]
    if any(b['name'].endswith('__cockpit') for b in ac.bones):
        raise SystemExit(f'{vid}: the original model already has a cockpit bone')
    skin = Skin(ac)
    ac.__dict__.pop('_skin', None); ac.__dict__.pop('_all', None)
    mesh, stick, gauges, displays, hud_glass, hud = cockpit(ac, tub, era, fspec, sw)
    moved = keep_inside(mesh, skin, ac, tub.E)
    source = max(rm, key=lambda k: len(rm[k])) if rm else 'wreck_fuselage_1__hull'
    piece = source.split('__', 1)[0] if source.startswith('wreck_') else 'wreck_fuselage_1'
    hull = ac.bone('hull')
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']),
                         'poly_mesh': mesh.poly_mesh()})
    # the new cockpit stays hidden from outside: any of its polys (and the stick's) that an outside view sees goes
    new = {piece + '__cockpit'} | {b['name'] for b in F.stick_bones(ac)}
    tris, uvs, own = face_tris(ac, tex)
    seen = seen_polys(tris, uvs, own, occ_glass, centre, tex)
    dropped = 0
    kept_glass, ac.glass = ac.glass, all_glass        # the whole canopy for the poke-through test

    def above_glass(V):
        """a corner standing out above the canopy glass: glass under it in its column, none over it"""
        for p in V:
            if ac.glass_above(p[0], p[2], p[1]) is None and ac.glass_above(p[0], p[2], -1e9) is not None:
                return True
        return False
    for b in ac.bones:
        if b['name'] in new and b.get('poly_mesh'):
            bad = {i for (n, i) in seen if n == b['name']}
            P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
            bad |= {i for i, p in enumerate(b['poly_mesh']['polys']) if above_glass(P[[v[0] for v in p]])}
            dropped += len(bad)
            b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if i not in bad]
    ac.glass = kept_glass
    write_instruments(ac, seat, gauges, displays, hud, tub)
    if (FORMED or DETAILED) and ac.asset.get('CanopyGlass'):
        # the canopy's own glass stays; the old sight and display glass inside the cockpit goes with the old
        # interior, and the new combiner glass is added
        cg = ac.asset['CanopyGlass']
        cg['Triangles'] = [round(float(v), 4) + 0.0 for v in np.asarray(ac.glass, float).reshape(-1)]
        if hud_glass:
            L.add_glass(ac.asset, hud_glass)
    else:
        ac.asset.pop('CanopyGlass', None)
    rep = (f'{vid} ({era}): cut {sum(len(v) for v in rm.values())} hidden faces '
           f'{ {k: len(v) for k, v in rm.items()} }, {kept_frame} canopy-frame faces kept, {n_split} skin faces split at the canopy outline, no glass; '
           f'cockpit {len(mesh.faces)} faces ({dropped} seen from outside dropped), {len(gauges)} gauges, '
           f'{len(displays)} displays, HUD {"yes" if hud else "no"}; {moved} corners pulled inside the skin')
    if not dry:
        with open(ac.geo_path, 'w', newline='\n', encoding='utf-8') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        C.write_asset_lf(ac.asset_path, ac.asset)
        if getattr(ac, 'atlas', None) is not None:
            tex_dir = os.path.join(ROOT, REL, 'textures')
            ac.atlas.write(os.path.join(tex_dir, 'entity', vid + '.png'))
            dead = os.path.join(tex_dir, 'entity_dead', vid + '.png')
            if os.path.exists(dead):
                ac.atlas.write(dead, dead=True)
    return ac, rm, well, rep


def interior_only(vid, ref='origin/main', dry=False):
    """the cockpit rebuilt in its original canopy: only the cockpit interior is cut (the old canopy's frames, posts
    and glass faces all stay), the detailed cockpit goes in its place, and the vehicle carries no CanopyGlass (the
    old glass is used only to fit the cockpit under it)"""
    # the base is the modelled-cockpit model (cockpit_models/fleet.py): its old interior is already gone by the
    # cockpit tool's rules, and every frame of the original canopy is untouched. Its cockpit bone is replaced.
    ac = load(vid, COCKPITS_REF)
    orig = load(vid, ref)                     # the original model: the cockpit is measured on it, as fleet.py did
    sw = L.Swatches(L.texture_path(vid))
    fspec = F.FLEET['aircraft'].get(vid, {})
    era = C.era_of(vid)
    seat = fspec.get('seat', 0)
    orig.drop_sight_glass(orig.eye(seat), fspec.get('tub', {}).get('s', 1.0))
    tub_kw = dict(fspec.get('tub', {}))
    tub_kw.setdefault('hinge_frac', L.ERAS[era].get('hinge_frac', 0.5))
    tub = L.Tub(orig, seat, **tub_kw)
    orig.drop_glass_inside(tub)
    if not len(orig.glass):
        raise SystemExit(f'{vid}: no canopy glass to fit the cockpit under')
    ac.glass = orig.glass.copy()              # fitting only: the old glass is not written
    rm, why = {}, {'old cockpit bone replaced': 1}
    old_piece = next((b['name'].split('__', 1)[0] for b in ac.bones if b['name'].endswith('__cockpit')),
                     'wreck_fuselage_1')       # the new cockpit breaks away with the same wreck piece
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith(('__cockpit', '__canopy'))]
    skin = Skin(ac)
    ac.__dict__.pop('_skin', None); ac.__dict__.pop('_all', None)
    mesh, stick, gauges, displays, hud_glass, hud = cockpit(ac, tub, era, fspec, sw)
    moved = keep_inside(mesh, skin, ac, tub.E)
    piece = old_piece
    hull = ac.bone('hull')
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == piece + '__hull'), len(ac.bones) - 1) + 1
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']),
                         'poly_mesh': mesh.poly_mesh()})
    write_instruments(ac, seat, gauges, displays, hud, tub)
    ac.asset.pop('CanopyGlass', None)
    rep = (f'{vid} ({era}): {why}; old canopy kept, no glass; '
           f'cockpit {len(mesh.faces)} faces, {len(gauges)} gauges, {len(displays)} displays, '
           f'HUD {"yes" if hud else "no"}; {moved} corners pulled inside the skin')
    if not dry:
        with open(ac.geo_path, 'w', newline='\n', encoding='utf-8') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        C.write_asset_lf(ac.asset_path, ac.asset)
    return ac, rm, None, rep


def cockpit(ac, tub, era, fspec, sw):
    """the modelled cockpit (fleet.py's build, without its own old-interior pass: the cut did that)"""
    E, s = tub.E, tub.s
    sticks = F.stick_bones(ac)
    rig = ac.asset.get('AircraftRig')
    rigged = bool(sticks) and rig and rig.get('Schema') == 2 and \
        any(sf.get('Bone') == 'control_stick_pitch' for sf in rig.get('Surfaces', []))
    side = fspec.get('side_stick')
    import authored
    mod = authored.module(ac.vid)
    if FORMED:
        # the formed cockpit (cockpit_models/formed.py): shaped to the measured space inside the model
        import formed as FM
        from authored import lib as AL
        ac.atlas = AL.Atlas(ac.vid)
        mesh, stick, gauges, displays, glass, hud, foot = FM.build(ac, tub, sw, ac.atlas, era,
                                                                   fspec.get('palette', 'nato'), hud=fspec.get('hud'),
                                                                   side_stick=bool(side))
    elif mod is not None and not DETAILED:
        # an authored cockpit (tools/cockpit_models/authored/<vid>.py): built from the type's references, its
        # painted faces in a free area of the texture (written with the model)
        from authored import lib as AL
        ac.atlas = AL.Atlas(ac.vid)
        mesh, stick, gauges, displays, glass, hud, foot = mod.build(ac, tub, sw, ac.atlas)
    else:
        foot = (np.array([E[0] - side * 0.30 * s, tub.console_y + 0.02 * s, E[2] + 0.28 * s]) if side
                else np.array([E[0], E[1] - 0.92 * s, E[2] + 0.44 * s]))
        mesh, stick, gauges, displays, glass, hud = L.build(ac, tub, era, fspec.get('palette', 'nato'), sw,
                                                            hud=fspec.get('hud'), gunsight=fspec.get('gunsight', True),
                                                            stick_foot=foot, side_stick=bool(side))
        import detail as D
        D.add(mesh, tub, D.colours(sw, fspec.get('palette', 'nato')), era, displays)
    if rigged:
        px = L.r5(foot * np.array([16.0, 16.0, -16.0]))
        for b in ac.bones:
            if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
                b['pivot'] = px
        for b in sticks:
            b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        sticks[0]['poly_mesh'] = stick.poly_mesh()
        for sf in rig['Surfaces']:
            if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
                sf['Pivot'] = px
                if side:
                    sf['MaxDeflectionDegrees'] = 6.0
    else:
        for b in sticks:
            b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        mesh.extend(stick)
    return mesh, stick, gauges, displays, glass, hud


def write_instruments(ac, seat, gauges, displays, hud, tub):
    a = ac.asset
    old_g = [g for g in (a.get('CockpitGauges') or {}).get('Gauges', []) if g.get('Seat', 0) != seat]
    old_d = [d for d in (a.get('FlightDisplays') or {}).get('Displays', []) if d.get('Seat', 0) != seat]
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': old_g + gauges}
    fd = a.get('FlightDisplays') or {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS'}
    fd['Displays'] = old_d + displays
    fd.setdefault('Schema', 1); fd.setdefault('Frame', 'VEHICLE_LOCAL_BLOCKS')
    a['FlightDisplays'] = fd
    if any(d.get('Kind') == 'STORES' for d in fd['Displays']) and 'Planform' not in fd:
        import importlib.util
        sp = importlib.util.spec_from_file_location('cockpit_layout', os.path.join(HERE, '..', 'cockpit_layout',
                                                                                    'layout.py'))
        lay = importlib.util.module_from_spec(sp); sp.loader.exec_module(lay)
        fd['Planform'] = lay.planform(ac.vid)
    if not fd['Displays']:
        a.pop('FlightDisplays')
    if hud:
        a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat,
                              'Center': L.r5(hud['centre']), 'Normal': L.r5(hud['normal']), 'Up': L.r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': L.r5(tub.E)}
    else:
        a.pop('HeadUpDisplay', None)


def main(argv):
    ref = next((a.split('=', 1)[1] for a in argv if a.startswith('--ref=')), 'origin/main')
    dry = '--dry-run' in argv
    global FORMED, DETAILED
    FORMED = '--formed' in argv
    DETAILED = '--detailed' in argv
    run = interior_strict if ('--interior' in argv or FORMED or DETAILED) else interior_only if '--keep-canopy' in argv else rebuild
    failed = 0
    for vid in [a for a in argv if not a.startswith('--')]:
        try:
            print(run(vid, ref, dry)[3], flush=True)
        except (SystemExit, Exception) as e:          # one aircraft's failure leaves the others to run
            print(f'FAILED {vid}: {type(e).__name__} {e}', flush=True); failed += 1
    if failed:
        sys.exit(1)


if __name__ == '__main__':
    main(sys.argv[1:])
