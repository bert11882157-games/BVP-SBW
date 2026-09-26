#!/usr/bin/env python3
"""Thins the canopy frames of fighter aircraft for a clearer view from the cockpit.

The MTB aircraft model their canopy frames as opaque geometry, either as exported boxes or as strips of quads (often
welded into the fuselage mesh), with the glass left open or cut out of the texture. The tool renders what each crew
eye sees onto a cube map (the poly_mesh triangles the game draws, texture alpha with the mesh loader's flipped v,
pilot_view_occluder bones hidden as in first person); anything nearer than FRAME_RANGE is solid. A frame bar is a
long, thin, opaque piece of the canopy shell (nothing solid just beyond it along the view ray, so headrests, seats and
consoles do not count; the HUD/gunsight box straight ahead is left alone) with an open pane on both sides of it:
  * box bars (runs of 6 quads closing a cuboid; boxes stacked side by side along one bar are grouped) shrink their
    cross-section to KEEP about the bar's axis and grow at both ends so they still reach what they met;
  * quad-strip bars (quads, or triangle pairs forming quads, plus the side/back faces of 3D bars) pull every short
    edge toward its midpoint; a vertex on several edges takes the least-squares move that satisfies them all, so
    joints stay closed. Moves are keyed by vertex position, so coincident vertices of other polys and bones follow
    and no cracks open.
Bars already thinner than MIN_WIDTH, sills, the cockpit tub and the panel (no pane on both sides) are untouched.

Usage: python3 tools/canopy_frames/thin.py [--write] [--sheet DIR] <id>...   (no ids: every aircraft in FIGHTERS)
"""
import json, math, os, sys
import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
GEN = os.path.join(ROOT, 'bvp', 'src', 'generated', 'resources')

# Fighters, fighter-bombers and attack aircraft with a pilot canopy (bombers and transports are not included).
FIGHTERS = """a_10 a_7d eurofighter_typhoon f2h_2 f3h f8f_1 f9f_2 f_100c f_104g f_111f f_14a f_14d f_15c f_16c f_4c f_5a
f_84f f_86k f_8h fa_18e fiat_g_91 ho_229 il_10 j_11a j_2 j_26 j_5 ju_87_b2 m_50a md_450_ouragan meteor_f_8
mig_15bis mig_19s mig_21bis mig_23mld mig_29 mig_9 mirage_5 mirage_f1 p_51d panavia_tornado_ids_marineflieger
rafale saab_29_tunnan saab_32_lansen saab_35_draken saab_37_viggen saab_j_21a_1 saab_jas_39_gripen sabre_mk_6
su_17 su_25 su_27 su_35 su_39 su_57 su_9 super_mystere supermarine_spitfire_griffon yak_15p yak_3 yak_9u""".split()

FACE = 480                  # cube-map face resolution (90 degrees)
NEAR_TRIS = 3.0             # triangles farther than this from the eye are ignored (blocks)
FRAME_RANGE = 2.0           # anything nearer than this along a view ray is solid (blocks)
BEHIND = (0.12, 0.8)     # solid within this range beyond a vertex marks it as interior, not canopy (blocks)


def in_region(az, el):
    if el < math.radians(-35):
        return False
    # HUD combiner / reflector gunsight straight ahead over the panel
    if abs(az) < math.radians(14) and el < math.radians(14):
        return False
    return True


def load_geo(vid):
    path = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo', vid + '.geo.json')
    return path, json.load(open(path))


def load_texture(vid):
    return np.asarray(Image.open(os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity', vid + '.png'))
                      .convert('RGBA')).astype(np.float32) / 255.0


def to_local(p):
    """model pixels (x left, y up, z aft) -> vehicle-local blocks (x left, y up, z forward)"""
    return np.asarray(p, float) * np.array([1.0, 1.0, -1.0]) / 16.0


def from_local(p):
    return np.asarray(p, float) * np.array([1.0, 1.0, -1.0]) * 16.0


def triangles(geo):
    tris, uvs = [], []
    for bone in geo['minecraft:geometry'][0]['bones']:
        if bone['name'].startswith('pilot_view_occluder'):
            continue
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        pos = np.array(pm['positions'], float)
        uv = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            idx = [v[0] for v in poly]
            tidx = [v[2] if len(v) > 2 else 0 for v in poly]
            for k in range(1, len(idx) - 1):
                tris.append(pos[[idx[0], idx[k], idx[k + 1]]])
                uvs.append(uv[[tidx[0], tidx[k], tidx[k + 1]]])
    return to_local(np.array(tris)), np.array(uvs)


def eyes(vid):
    data = json.load(open(os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles', vid + '.json')))
    att = data.get('Attachments', {})
    out = []
    for seat in data['Seats']:
        name = seat.get('CameraPos', {}).get('EyeAttachment')
        out.append(np.array(att[name]['Position'], float) if name and name in att
                   else np.array(seat['Position'], float) + np.array([0, 1.5, 0]))
    return out


# cube faces: (forward, right, up) with right = up x forward in this left-handed-looking frame; only consistency with
# direction_to_face matters
FACES = []
for axis in range(3):
    for sign in (1.0, -1.0):
        f = np.zeros(3); f[axis] = sign
        helper = np.array([0.0, 1.0, 0.0]) if axis != 1 else np.array([0.0, 0.0, 1.0])
        r = np.cross(helper, f); r /= np.linalg.norm(r)
        u = np.cross(f, r)
        FACES.append((f, r, u))


def clip_front(poly, eps=1e-3):
    """Clip a face-space polygon (x, y, z) to z >= eps (Sutherland-Hodgman)."""
    out = []
    n = len(poly)
    for k in range(n):
        a, b = poly[k], poly[(k + 1) % n]
        ina, inb = a[2] >= eps, b[2] >= eps
        if ina:
            out.append(a)
        if ina != inb:
            t = (eps - a[2]) / (b[2] - a[2])
            out.append(a + (b - a) * t)
    return np.array(out)


def render_depth(tris, uvs, tex, eye, color=None, ids=None, tri_ids=None):
    """Depth cube map (FACES x FACE x FACE) of the alpha-tested triangles seen from eye; inf = nothing near.
    When color is an array (6, FACE, FACE, 3) it receives the texel colour of each visible surface."""
    rel = tris - eye
    near = np.linalg.norm(rel, axis=2).min(axis=1) < NEAR_TRIS
    rel, uvs = rel[near], uvs[near]
    if tri_ids is not None:
        tri_ids = np.asarray(tri_ids)[near]
    H, W = tex.shape[:2]
    depth = np.full((6, FACE, FACE), np.inf, np.float32)
    coords = (np.arange(FACE) + 0.5) / FACE * 2 - 1
    for fi, (f, r, u) in enumerate(FACES):
        z = rel @ f
        x = rel @ r
        y = rel @ u
        for ti in range(len(rel)):
            zt = z[ti]
            if (zt <= 1e-4).all():
                continue
            poly = clip_front(np.stack([x[ti], y[ti], zt], -1))
            if len(poly) < 3:
                continue
            px, py = poly[:, 0] / poly[:, 2], poly[:, 1] / poly[:, 2]
            if px.max() < -1 or px.min() > 1 or py.max() < -1 or py.min() > 1:
                continue
            i0 = max(0, int((max(px.min(), -1.0) + 1) / 2 * FACE) - 1)
            i1 = min(FACE, int((min(px.max(), 1.0) + 1) / 2 * FACE) + 2)
            j0 = max(0, int((max(py.min(), -1.0) + 1) / 2 * FACE) - 1)
            j1 = min(FACE, int((min(py.max(), 1.0) + 1) / 2 * FACE) + 2)
            if i1 <= i0 or j1 <= j0:
                continue
            gx, gy = np.meshgrid(coords[i0:i1], coords[j0:j1])
            d = f[None, None] + gx[..., None] * r + gy[..., None] * u
            v0 = rel[ti, 0]; e1 = rel[ti, 1] - v0; e2 = rel[ti, 2] - v0
            p = np.cross(d, e2)
            det = p @ e1
            ok = np.abs(det) > 1e-12
            inv = np.where(ok, 1.0 / np.where(ok, det, 1.0), 0.0)
            s = -v0
            a = (p @ s) * inv
            q = np.cross(s, e1)
            b = (d * q).sum(-1) * inv
            t = (q @ e2) * inv * np.linalg.norm(d, axis=-1)  # distance along the unit ray
            hit = ok & (a >= 0) & (b >= 0) & (a + b <= 1) & (t > 0.02)
            if not hit.any():
                continue
            cur = depth[fi, j0:j1, i0:i1]
            hit &= t < cur
            if not hit.any():
                continue
            uv = uvs[ti, 0] * (1 - a - b)[..., None] + uvs[ti, 1] * a[..., None] + uvs[ti, 2] * b[..., None]
            tx = np.clip((uv[..., 0] * W).astype(int), 0, W - 1)
            ty = np.clip(((1 - uv[..., 1]) * H).astype(int), 0, H - 1)
            hit &= tex[ty, tx, 3] > 0.5
            cur[hit] = t[hit]
            if color is not None:
                color[fi, j0:j1, i0:i1][hit] = tex[ty, tx, :3][hit]
            if ids is not None:
                ids[fi, j0:j1, i0:i1][hit] = tri_ids[ti]
    return depth


def lookup(depth, dirs):
    """Depth (or colour, for a colour cube map) for unit directions (..., 3)."""
    ax = np.abs(dirs)
    axis = ax.argmax(-1)
    comp = np.take_along_axis(dirs, axis[..., None], -1)[..., 0]
    face = axis * 2 + (comp < 0)
    out = np.empty(dirs.shape[:-1] + depth.shape[3:], np.float32)
    for fi, (f, r, u) in enumerate(FACES):
        m = face == fi
        if not m.any():
            continue
        dd = dirs[m]
        z = dd @ f
        i = np.clip(((dd @ r / z + 1) / 2 * FACE).astype(int), 0, FACE - 1)
        j = np.clip(((dd @ u / z + 1) / 2 * FACE).astype(int), 0, FACE - 1)
        out[m] = depth[fi, j, i]
    return out


class Caster:
    """Alpha-tested ray casts against the triangles near the eye."""

    def __init__(self, tris, uvs, tex, eye):
        near = np.linalg.norm(tris - eye, axis=2).min(axis=1) < NEAR_TRIS + FRAME_RANGE
        self.v0 = tris[near, 0]; self.e1 = tris[near, 1] - self.v0; self.e2 = tris[near, 2] - self.v0
        self.uvs = uvs[near]; self.tex = tex; self.eye = eye

    def hits(self, d, tmin, tmax):
        p = np.cross(d, self.e2)
        det = np.einsum('ij,ij->i', self.e1, p)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1.0 / np.where(ok, det, 1.0), 0.0)
        s = self.eye - self.v0
        a = np.einsum('ij,ij->i', s, p) * inv
        q = np.cross(s, self.e1)
        b = (q @ d) * inv
        t = np.einsum('ij,ij->i', self.e2, q) * inv
        idx = np.where(ok & (a >= 0) & (b >= 0) & (a + b <= 1) & (t > tmin) & (t < tmax))[0]
        H, W = self.tex.shape[:2]
        for i in idx:
            uv = self.uvs[i, 0] * (1 - a[i] - b[i]) + self.uvs[i, 1] * a[i] + self.uvs[i, 2] * b[i]
            if self.tex[int(np.clip((1 - uv[1]) * H, 0, H - 1)), int(np.clip(uv[0] * W, 0, W - 1)), 3] > 0.5:
                return True
        return False


def crew_eyes(vid):
    all_eyes = eyes(vid)
    out = [all_eyes[0]]
    for e in all_eyes[1:2]:
        if np.linalg.norm(e - all_eyes[0]) < 2.5 and np.linalg.norm(e - all_eyes[0]) > 0.2:
            out.append(e)
    return out


ASPECT = 1.8        # a frame-bar quad is at least this much longer than wide
KEEP = 0.35         # fraction of its width (and depth) a frame bar keeps
MIN_WIDTH = 0.015   # blocks
SIDE_MARGIN = 0.03  # blocks beyond the bar edge where the pane must be open
OPAQUE = 0.7        # canopy glass strips (mostly see-through texels) are not frames


def opacity(pm, poly_ids, tex):
    """Fraction of opaque texels over the given polys (the mesh loader flips v)."""
    H, W = tex.shape[:2]
    uv = pm['uvs']
    n = o = 0
    for pi in poly_ids:
        poly = pm['polys'][pi]
        t = [np.array(uv[v[2]], float) for v in poly]
        for k in range(1, len(t) - 1):
            for a in (0.1, 0.33, 0.6):
                for b in (0.1, 0.33, 0.6):
                    if a + b > 0.95:
                        continue
                    q = t[0] * (1 - a - b) + t[k] * a + t[k + 1] * b
                    o += tex[int(np.clip((1 - q[1]) * H, 0, H - 1)), int(np.clip(q[0] * W, 0, W - 1)), 3] > 0.5
                    n += 1
    return o / max(n, 1)


def faces(pm):
    """(poly ids, 4 corner points in vehicle-local blocks, in order) for every quad of a poly_mesh; triangle pairs
    that split a quad along its longest edge count as that quad. ([id], None) for anything else."""
    pos = pm['positions']
    polys = pm['polys']
    key = lambda p: tuple(np.round(p, 4))
    tri_pts = {}
    edge_owner = {}
    for pi, poly in enumerate(polys):
        if len(poly) == 3:
            P = [to_local(pos[v[0]]) for v in poly]
            tri_pts[pi] = P
            L = [np.linalg.norm(P[(k + 1) % 3] - P[k]) for k in range(3)]
            k = int(np.argmax(L))
            ek = tuple(sorted((key(P[k]), key(P[(k + 1) % 3]))))
            edge_owner.setdefault(ek, []).append((pi, k))
    paired = {}
    for ek, owners in edge_owner.items():
        if len(owners) != 2:
            continue
        (p1, k1), (p2, k2) = owners
        A, B = tri_pts[p1], tri_pts[p2]
        n1 = np.cross(A[1] - A[0], A[2] - A[0]); n2 = np.cross(B[1] - B[0], B[2] - B[0])
        if np.linalg.norm(n1) < 1e-12 or np.linalg.norm(n2) < 1e-12:
            continue
        if abs(n1 @ n2) / np.linalg.norm(n1) / np.linalg.norm(n2) < math.cos(math.radians(35)):
            continue
        a, b, c = A[k1], A[(k1 + 1) % 3], A[(k1 + 2) % 3]
        d = B[(k2 + 2) % 3]
        paired[p1] = ([p1, p2], np.array([a, d, b, c]))
        paired[p2] = None  # consumed by its partner
    for pi, poly in enumerate(polys):
        if len(poly) == 4:
            yield [pi], np.array([to_local(pos[v[0]]) for v in poly])
        elif pi in paired:
            if paired[pi] is not None:
                yield paired[pi]
        else:
            yield [pi], None


def quad_axes(P):
    """(short edges [(a, b), (b', a')], short length, long length, unit across direction, centre-line ends)."""
    e = [np.linalg.norm(P[(k + 1) % 4] - P[k]) for k in range(4)]
    short, long_ = ((0, 2), (1, 3)) if e[0] + e[2] <= e[1] + e[3] else ((1, 3), (0, 2))
    ws = (e[short[0]] + e[short[1]]) / 2
    ls = (e[long_[0]] + e[long_[1]]) / 2
    a0, b0 = P[short[0]], P[(short[0] + 1) % 4]
    a1, b1 = P[short[1]], P[(short[1] + 1) % 4]
    u = ((b0 - a0) + (a1 - b1)) / 2
    nu = np.linalg.norm(u)
    u = u / nu if nu > 1e-9 else u
    return [(a0, b0), (b1, a1)], ws, ls, u, ((a0 + b0) / 2, (a1 + b1) / 2)


def cuboids(pm):
    """Boxes of a poly_mesh: runs of 6 consecutive quads (or 12 triangles) that close a box with 8 corners, as
    exported cubes are. List of (poly ids, position ids, 8 corner points in vehicle-local blocks)."""
    pos = pm['positions']
    polys = pm['polys']
    key = lambda i: tuple(np.round(pos[i], 4))
    out = []
    i = 0
    while i < len(polys):
        found = False
        for n, sides in ((6, 4), (12, 3)):
            run = polys[i:i + n]
            if len(run) < n or any(len(p) != sides for p in run):
                continue
            count = {}
            for p in run:
                for v in p:
                    count[key(v[0])] = count.get(key(v[0]), 0) + 1
            if len(count) != 8:
                continue
            if sides == 4 and any(c != 3 for c in count.values()):
                continue
            P = np.array([to_local(k) for k in count])
            c, axes, ext = box_frame(P)
            # the 8 corners must be the corners of a box (all at +-ext along the axes)
            if (np.abs(np.abs((P - c) @ axes.T) - ext) > 1e-3 + 0.02 * ext.max()).any():
                continue
            ids = sorted({v[0] for p in run for v in p})
            out.append((list(range(i, i + n)), ids, P))
            i += n
            found = True
            break
        if not found:
            i += 1
    return out


def box_frame(P):
    """Centre, axes (long first) and half extents of a box given its 8 corners."""
    c = P.mean(axis=0)
    w, V = np.linalg.eigh(np.cov((P - c).T))
    axes = V[:, ::-1].T
    ext = np.abs((P - c) @ axes.T).max(axis=0)
    return c, axes, ext


def bar_quads(geo, tex, depth, eye, caster, reasons=None, skip=None):
    """(bone, poly ids, [(a, b) short edges as vehicle-local points]) for every quad of a frame bar seen from eye.

    A bar quad is a thin, opaque quad of the canopy shell with an open pane on both sides of it (as the crew sees
    it). The side and back faces of a 3D bar (thin quads sharing a long edge with a bar quad at an angle) join it so
    the bar loses depth as well as width; otherwise a deep bar seen at a slant would look as wide as before."""
    out = []
    memo = {}
    key = lambda p: tuple(np.round(p, 4))

    def shell(pl):
        k = key(pl)
        if k not in memo:
            rel = pl - eye
            r = float(np.linalg.norm(rel))
            ok = 0.05 < r < FRAME_RANGE
            if ok:
                d0 = rel / r
                ok = in_region(math.atan2(d0[0], d0[2]), math.asin(np.clip(d0[1], -1, 1))) \
                    and not caster.hits(d0, r + BEHIND[0], r + BEHIND[1])
            memo[k] = ok
        return memo[k]

    def reject(bone, pis, why):
        if reasons is not None:
            for pi in pis:
                reasons[(bone['name'], pi)] = why

    def is_open(pl):
        rel = pl - eye
        r = np.linalg.norm(rel)
        return lookup(depth, (rel / r)[None])[0] >= FRAME_RANGE

    pending = []  # thin opaque shell quads without panes on both sides: side/back faces if they touch a bar
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].startswith('pilot_view_occluder'):
            continue
        for pis, P in faces(pm):
            if skip is not None and (bone['name'], pis[0]) in skip:
                continue
            if P is None:
                reject(bone, pis, 'not quad')
                continue
            if not shell(P.mean(axis=0)):
                reject(bone, pis, 'not shell')
                continue
            edges, ws, ls, u, (m0, m1) = quad_axes(P)
            if ws < 1e-4 or ls / ws < ASPECT or ws * KEEP < MIN_WIDTH * 0.5 or np.linalg.norm(u) < 0.5:
                reject(bone, pis, 'aspect')
                continue
            if opacity(pm, pis, tex) < OPAQUE:
                reject(bone, pis, 'glass')
                continue
            off = ws / 2 + max(SIDE_MARGIN, 0.3 * ws)
            sides = [(is_open(c + u * off), is_open(c - u * off))
                     for c in (m0 + (m1 - m0) * t for t in (0.25, 0.5, 0.75))]
            n = np.cross(P[1] - P[0], P[2] - P[0])
            n = n / max(np.linalg.norm(n), 1e-12)
            if sum(a for a, _ in sides) < 2 or sum(b for _, b in sides) < 2:
                pending.append((bone, pis, edges, P, n, ws))
                reject(bone, pis, 'sides')
                continue
            out.append((bone, pis, edges, P, n, ws))
    # side and back faces: share a long edge with a bar quad, at an angle to it, no wider than it
    for _ in range(2):
        long_edges = {}
        for q in out:
            P = q[3]
            for k in range(4):
                long_edges.setdefault(tuple(sorted((key(P[k]), key(P[(k + 1) % 4])))), []).append(q)
        still = []
        for q in pending:
            bone, pis, edges, P, n, ws = q
            joined = False
            for k in range(4):
                ek = tuple(sorted((key(P[k]), key(P[(k + 1) % 4]))))
                for b in long_edges.get(ek, []):
                    edge_len = np.linalg.norm(P[(k + 1) % 4] - P[k])
                    if edge_len < ws * ASPECT * 0.9:
                        continue  # shares a short edge (bar end), not a long one
                    if abs(n @ b[4]) < math.cos(math.radians(40)) and ws <= b[5] * 1.6:
                        joined = True
                        break
                if joined:
                    break
            if joined:
                out.append(q)
                if reasons is not None:
                    for pi in pis:
                        reasons.pop((bone['name'], pi), None)
            else:
                still.append(q)
        pending = still
    return [(q[0], q[1], q[2]) for q in out]


def bar_boxes(geo, tex, depth, eye, caster):
    """Frame bars built from separate boxes: [(bone, position ids, centre, axes, half extents)] grouped so that
    boxes stacked side by side along the same bar thin together about one axis (and stay touching)."""
    cands = []
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].startswith('pilot_view_occluder'):
            continue
        for pis, ids, P in cuboids(pm):
            c, axes, ext = box_frame(P)
            if ext[1] < 1e-4 or ext[0] / ext[1] < ASPECT or ext[1] * 2 * KEEP < MIN_WIDTH * 0.5:
                continue
            rel = c - eye
            r = float(np.linalg.norm(rel))
            if not 0.05 < r < FRAME_RANGE:
                continue
            d0 = rel / r
            if not in_region(math.atan2(d0[0], d0[2]), math.asin(np.clip(d0[1], -1, 1))):
                continue
            if caster.hits(d0, r + ext[1] + BEHIND[0], r + BEHIND[1] + ext[1]):
                continue
            if opacity(pm, pis, tex) < OPAQUE:
                continue
            cands.append([bone, pis, ids, P, c, axes, ext])
    # group parallel boxes that touch side by side
    n = len(cands)
    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for i in range(n):
        for j in range(i + 1, n):
            a, b = cands[i], cands[j]
            if abs(a[5][0] @ b[5][0]) < 0.985:
                continue
            d = b[4] - a[4]
            along = abs(d @ a[5][0])
            if along > a[6][0] + b[6][0]:
                continue
            across = np.linalg.norm(d - (d @ a[5][0]) * a[5][0])
            if across <= a[6][1] + b[6][1] + 0.004:
                parent[find(i)] = find(j)
    groups = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(cands[i])
    out = []
    for members in groups.values():
        big = max(members, key=lambda m: m[6][0])
        axis = big[5][0]
        pts = np.concatenate([m[3] for m in members])
        c, _, _ = big[4], None, None
        # cross-section frame of the group
        u1 = big[5][1] - (big[5][1] @ axis) * axis; u1 /= np.linalg.norm(u1)
        u2 = np.cross(axis, u1)
        rel = pts - c
        lo = np.array([(rel @ u1).min(), (rel @ u2).min()]); hi = np.array([(rel @ u1).max(), (rel @ u2).max()])
        centre = c + u1 * (lo[0] + hi[0]) / 2 + u2 * (lo[1] + hi[1]) / 2
        half = (hi - lo) / 2
        t = rel @ axis
        # panes open on both sides of the bar as seen from the eye
        view = centre - eye
        across = np.cross(view / np.linalg.norm(view), axis)
        if np.linalg.norm(across) < 1e-6:
            continue
        across /= np.linalg.norm(across)
        span = abs(across @ u1) * half[0] + abs(across @ u2) * half[1]
        off = span + max(SIDE_MARGIN, 0.6 * span)
        ok = [0, 0]
        for f in (0.25, 0.5, 0.75):
            q = centre + axis * (t.min() + (t.max() - t.min()) * f)
            for k, sgn in enumerate((1, -1)):
                pt = q + across * off * sgn
                rr = pt - eye
                ok[k] += lookup(depth, (rr / np.linalg.norm(rr))[None])[0] >= FRAME_RANGE
        if ok[0] < 2 or ok[1] < 2:
            continue
        out.append((members, centre, axis, u1, u2, half))
    return out


def thin(vid, log=print):
    path, geo = load_geo(vid)
    tex = load_texture(vid)
    moved_total = 0
    quads_total = 0
    for eye in crew_eyes(vid):
        tris, uvs = triangles(geo)
        depth = render_depth(tris, uvs, tex, eye)
        caster = Caster(tris, uvs, tex, eye)
        boxes = bar_boxes(geo, tex, depth, eye, caster)
        skip = set()
        for members, centre, axis, u1, u2, half in boxes:
            ext = (1 - KEEP) * max(half)   # grow the ends so the thinner bar still reaches what it met
            for bone, pis, ids, P, c, axes, e in members:
                skip.update((bone['name'], pi) for pi in pis)
                pm = bone['poly_mesh']
                lo_t = min(((to_local(pm['positions'][i]) - centre) @ axis) for i in ids)
                hi_t = max(((to_local(pm['positions'][i]) - centre) @ axis) for i in ids)
                for i in ids:
                    p = to_local(pm['positions'][i])
                    r = p - centre
                    t = r @ axis
                    t += ext if abs(t - hi_t) < 1e-4 else -ext if abs(t - lo_t) < 1e-4 else 0.0
                    q = centre + axis * t + u1 * (r @ u1) * KEEP + u2 * (r @ u2) * KEEP
                    pm['positions'][i] = [round(float(x), 5) for x in from_local(q)]
                    moved_total += 1
        quads = bar_quads(geo, tex, depth, eye, caster, skip=skip)
        quads_total += len(quads) + sum(len(b[0]) for b in boxes)
        # Every short edge pulls its two ends toward its midpoint. A vertex on several edges satisfies them all in
        # the least-squares sense: the same edge seen from two faces agrees, while the width and depth edges of a
        # bar corner (or the two bars of a joint) combine.
        cons = {}
        for bone, _, edges in quads:
            for a, b in edges:
                m = (a + b) / 2
                w = np.linalg.norm(b - a)
                if w < 1e-9:
                    continue
                keep = max(KEEP, min(1.0, MIN_WIDTH / w))
                for q in (a, b):
                    d = (m - q) * (1 - keep)
                    L = np.linalg.norm(d)
                    if L > 1e-9:
                        cons.setdefault(tuple(np.round(q, 4)), {})[tuple(np.round(d / L, 3))] = (d / L, L)
        disp = {}
        for k, cs in cons.items():
            N = np.array([c[0] for c in cs.values()])
            dl = np.array([c[1] for c in cs.values()])
            sol = np.linalg.lstsq(N, dl, rcond=1e-3)[0]
            cap = dl.max() * 1.5
            if np.linalg.norm(sol) > cap:
                sol *= cap / np.linalg.norm(sol)
            disp[k] = sol
        for bone in geo['minecraft:geometry'][0]['bones']:
            pm = bone.get('poly_mesh')
            if not pm or not pm.get('polys'):
                continue
            for i, p in enumerate(pm['positions']):
                k = tuple(np.round(to_local(p), 4))
                if k in disp:
                    pm['positions'][i] = [round(float(x), 5) for x in from_local(np.array(k) + disp[k])]
                    moved_total += 1
    log(f'{vid}: {quads_total} frame-bar quads, moved {moved_total} vertex slots')
    return path, geo, moved_total


def view(vid, geo, eye, out, size=420, radius=115.0):
    """Fisheye (azimuthal equidistant, +-radius degrees) pilot view centred 40 degrees above the nose."""
    tex = load_texture(vid)
    tris, uvs = triangles(geo)
    color = np.zeros((6, FACE, FACE, 3), np.float32)
    depth = render_depth(tris, uvs, tex, eye, color)
    c = np.array([0.0, math.sin(math.radians(40)), math.cos(math.radians(40))])
    right = np.cross(np.array([0.0, 1.0, 0.0]), c); right /= np.linalg.norm(right)  # toward +x (left of the pilot)
    right = -right                                                                    # image right = pilot's right
    up = np.cross(c, -right); up /= np.linalg.norm(up)
    if up[1] < 0:
        up = -up
    g = (np.arange(size) + 0.5) / size * 2 - 1
    X, Y = np.meshgrid(g, -g)
    rho = np.hypot(X, Y) * math.radians(radius)
    phi = np.arctan2(Y, X)
    dirs = (np.cos(rho)[..., None] * c + np.sin(rho)[..., None] * (np.cos(phi)[..., None] * right
                                                                     + np.sin(phi)[..., None] * up))
    dd = lookup(depth, dirs)
    cc = lookup(color, dirs) * 255
    img = np.where((dd < np.inf)[..., None], cc, np.array([140, 190, 255]))
    img[np.hypot(X, Y) > 1] = 0
    Image.fromarray(img.astype(np.uint8)).save(out)


APPLIED = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'applied.json')


def main(argv):
    """--write edits the custom_geo files in place and records each id in applied.json; an id already recorded is
    skipped (thinning twice would thin again). --force re-applies anyway."""
    write = '--write' in argv
    force = '--force' in argv
    sheet = None
    if '--sheet' in argv:
        sheet = argv[argv.index('--sheet') + 1]
        os.makedirs(sheet, exist_ok=True)
    ids = [a for a in argv if not a.startswith('--') and a != sheet] or FIGHTERS
    applied = json.load(open(APPLIED)) if os.path.exists(APPLIED) else {}
    for vid in ids:
        if write and vid in applied and not force:
            print(f'{vid}: already thinned ({applied[vid]}), skipped')
            continue
        if sheet:
            _, geo0 = load_geo(vid)
            view(vid, geo0, eyes(vid)[0], os.path.join(sheet, vid + '_before.png'))
        path, geo, moved = thin(vid)
        if sheet:
            view(vid, geo, eyes(vid)[0], os.path.join(sheet, vid + '_after.png'))
        if write:
            if moved:
                with open(path, 'w') as fh:
                    json.dump(geo, fh, separators=(',', ':'))
                    fh.write('\n')
            applied[vid] = f'{moved} vertex slots moved'
            with open(APPLIED, 'w') as fh:
                json.dump(dict(sorted(applied.items())), fh, indent=1)
                fh.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
