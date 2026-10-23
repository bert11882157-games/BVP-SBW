#!/usr/bin/env python3
"""Cuts real gear wells into an aircraft's airframe and turns its landing gear into a retracting rig with doors.

For every deployed gear leg in the vehicle's AircraftRig.Gear:
  1. the leg's swing is chosen: a hinge H on the leg's axis a little inside the skin, and a 90-ish degree turn
     forward, aft or inboard (a nose leg swings fore/aft; a main leg prefers inboard, which lays its wheel flat).
     The first direction in the preference order whose stowed gear fits inside the airframe wins;
  2. the well is the axis-aligned box around the stowed gear (plus a margin), from below the skin up to a ceiling
     over the stowed gear. The box is subtracted from every airframe skin bone (wreck_<piece>__hull), so the skin
     is cut exactly along the box walls, and loose internal faces inside the box go;
  3. the walls rise from the exact cut line on the skin (the same points the cut produced, so the seam is closed)
     to the ceiling, and the ceiling closes the top: the well is a closed pocket with no view into the airframe;
  4. the skin cut out of the well's mouth becomes the gear doors: split in two along the well (a clamshell), each
     half in a bone gear_door_<n> hinged on its outer edge. Closed, the doors are exactly the original skin, so a
     retracted aircraft looks as before, with no seam to z-fight;
  5. the leg bone's pivot moves to the hinge, flat panels hanging off the leg that are not attached to the wheel
     (old doors modelled open) are dropped, and strut geometry above the hinge is trimmed;
  6. the vehicle asset gets Gear[i].Retract {Pivot, Axis, AngleDegrees} and GearDoors entries with
     OpenAngleDegrees (doors are modelled closed); AircraftRigAnimator swings the legs with the gear travel and
     opens the doors before the legs move.

Edges of untouched faces that pass through new cut points are split too (no T-junctions, no hairline cracks).
Rerunnable: an aircraft whose legs already carry Retract is skipped, so on a newer base, take that side's files
and run this again.

Usage: python3 tools/gear_wells/wells.py [--write] [--report] id...
"""
import itertools
import json, math, os, re, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', 'gear_wheels'))
import mesh as M  # noqa: E402

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
VEH = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity')
CONFIG = os.path.join(HERE, 'config.json')

MARGIN = 0.25         # px between the stowed gear and the well walls / ceiling
DOOR_CLEAR = 0.15     # px the stowed gear must sit above the closed doors
STEEP = 1.5           # px of rise per px across the skin under a well (about 56 degrees)
TOP_SKIN = 0.25       # px of airframe that must remain over a well ceiling
SKIN_LIFT = 0.25      # px of skin under a wall's foot before the wall counts as starting on the skin
OPEN_DEG = 90.0
DIRS = {'fwd': np.array([0, 0, -1.0]), 'aft': np.array([0, 0, 1.0])}
AIRFRAME = re.compile(r'^wreck_(.+)__hull$')


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def rot(axis, deg):
    a = unit(axis); t = math.radians(deg); c, s = math.cos(t), math.sin(t)
    x, y, z = a
    return np.array([[c + x * x * (1 - c), x * y * (1 - c) - z * s, x * z * (1 - c) + y * s],
                     [y * x * (1 - c) + z * s, c + y * y * (1 - c), y * z * (1 - c) - x * s],
                     [z * x * (1 - c) - y * s, z * y * (1 - c) + x * s, c + z * z * (1 - c)]])


class Model:
    def __init__(self, vid):
        self.vid = vid
        self.gpath = os.path.join(GEO, vid + '.geo.json')
        self.vpath = os.path.join(VEH, vid + '.json')
        self.geo = json.load(open(self.gpath))
        self.asset = json.load(open(self.vpath, encoding='utf-8'))
        self.bones_list = self.geo['minecraft:geometry'][0]['bones']
        self.bones = {b['name']: b for b in self.bones_list}
        self.rig = self.asset.get('AircraftRig') or {}
        kids = {}
        for b in self.bones_list:
            kids.setdefault(b.get('parent'), []).append(b['name'])
        self.kids = kids
        self.polys = {n: M.bone_polys(b['poly_mesh']) for n, b in self.bones.items()
                      if (b.get('poly_mesh') or {}).get('polys')}

    def subtree(self, root):
        out, stack = [], [root]
        while stack:
            n = stack.pop(); out.append(n); stack += self.kids.get(n, [])
        return out

    def airframe(self):
        return [n for n in self.polys if AIRFRAME.match(n)]

    def airframe_tris(self, polys=None):
        polys = polys or self.polys
        return M.tri_array([p for n in self.airframe() for p in polys[n]])


# ------------------------------------------------------------------------------------------------ gear analysis

def components(polys):
    key = lambda p: tuple(np.round(p, 3))
    parent = list(range(len(polys)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]; i = parent[i]
        return i
    owner = {}
    for i, poly in enumerate(polys):
        for c in poly:
            k = key(c[0])
            if k in owner:
                a, b = find(i), find(owner[k])
                if a != b:
                    parent[a] = b
            else:
                owner[k] = i
    groups = {}
    for i in range(len(polys)):
        groups.setdefault(find(i), []).append(i)
    return list(groups.values())


def extents(pts):
    c = pts - pts.mean(0)
    if len(pts) < 3:
        return np.zeros(3)
    w = np.linalg.svd(c, compute_uv=False)
    _, _, vt = np.linalg.svd(c, full_matrices=False)
    proj = c @ vt.T
    return np.sort(proj.max(0) - proj.min(0))


def wheels_of(m, leg):
    sub = set(m.subtree(leg))
    out = []
    for r in m.rig.get('Rotors') or []:
        if r.get('SpeedChannel') != 'groundRoll' or r['Bone'] not in sub:
            continue
        pts = [c[0] for n in m.subtree(r['Bone']) for p in m.polys.get(n, []) for c in p]
        if not pts:
            continue
        pts = np.array(pts)
        out.append({'bone': r['Bone'], 'pivot': np.array(r['Pivot'], float),
                    'lo': pts.min(0), 'hi': pts.max(0),
                    'radius': (pts[:, 1].max() - pts[:, 1].min()) / 2})
    return out


class Gear:
    """One leg: its meshes, wheel(s) and axis.

    `mesh` maps each mesh bone of the leg to the polygons it keeps: the leg's own strut pieces plus strut pieces
    an earlier tool left in the old (open-modelled) door bones, all attached to the wheel. Pieces that hang
    beside the gear unattached (doors and fairings modelled open) are left out: the skin doors replace them."""

    ATTACH = 0.8

    def __init__(self, m, entry, loose, claimed):
        self.m = m
        self.leg = entry['Bone']
        self.entry = entry
        self.sub = m.subtree(self.leg)
        # stores hung on a leg bone (a pylon parented to the gear) are not gear: they move to the hull
        self.stores = [n for n in m.kids.get(self.leg, []) if 'suspended' in n]
        skip = {k for n in self.stores for k in m.subtree(n)}
        self.mesh_bones = [n for n in self.sub if n in m.polys and n not in skip]
        wrecks = [k for k in m.kids.get(self.leg, []) if k.startswith('wreck_')]
        self.leg_mesh = max(wrecks, key=lambda k: len(m.polys.get(k, []))) if wrecks else None
        self.wheels = wheels_of(m, self.leg)
        self.wheel_bones = {n for w in self.wheels for n in m.subtree(w['bone'])}
        self.dropped = 0
        self.taken = 0
        self._pts = {}
        self.strut = None
        self._attach(loose, claimed)
        pts = self.points()
        self.lo, self.hi = pts.min(0), pts.max(0)
        if self.wheels:
            self.axle = np.mean([w['pivot'] for w in self.wheels], axis=0)
            self.radius = max(w['radius'] for w in self.wheels)
        else:   # stale wheel rig: the lowest part of the leg mesh stands in for the wheel
            low = pts[pts[:, 1] < self.lo[1] + 3]
            self.radius = max(1.0, (np.ptp(low[:, 2]) if len(low) else 2) / 2)
            self.axle = np.array([low[:, 0].mean(), self.lo[1] + self.radius, low[:, 2].mean()])
        top = pts[pts[:, 1] > self.hi[1] - 1.5]
        self.top = np.array([top[:, 0].mean(), self.hi[1], top[:, 2].mean()])
        self.nose = abs(self.axle[0]) < 3.0 and len([1 for w in self.wheels if abs(w['pivot'][0]) > 3]) == 0
        # the leg telescopes: everything below split_y (wheel, fork, steering, lower strut) can slide up the leg
        self.split_y = self.axle[1] + self.radius + 0.3
        self.direct = [n for n in self.mesh_bones if self.m.bones[n].get('parent') == self.leg]
        self._parts = {}
        # a strut stand-in from the top of the gear up to the hinge is added when the model's strut stops short
        legp = [c for p in self.mesh.get(self.leg_mesh, []) for c in p]
        self.strut_uv = max(self.mesh.get(self.leg_mesh, []) or [[(0, 0, np.array([0.5, 0.5]))]],
                            key=lambda p: M.area(p) if len(p) > 2 else 0)[0][2] if legp else np.array([0.5, 0.5])

    def _attach(self, loose, claimed):
        comps = []                     # (source bone, polygons, lo, hi)
        self.mesh = {}
        for n in self.mesh_bones:
            if n in self.wheel_bones:
                self.mesh[n] = list(self.m.polys[n])
                continue
            polys = self.m.polys[n]
            self.mesh[n] = []
            for comp in components(polys):
                pp = [polys[i] for i in comp]
                pts = np.array([c[0] for p in pp for c in p])
                comps.append((n, pp, pts.min(0), pts.max(0), None))
        for i, (n, pp, lo, hi) in enumerate(loose):
            if i not in claimed:
                comps.append((n, pp, lo, hi, i))
        if self.wheels:
            boxes = [(w['lo'], w['hi']) for w in self.wheels]
        else:   # no wheel bone: start from the lowest piece of the leg itself
            own = [c for c in comps if c[4] is None]
            low = min(own, key=lambda c: c[2][1])
            boxes = [(low[2], low[3])]
        attached = [False] * len(comps)
        grew = True
        while grew:
            grew = False
            for i, (_, _, lo, hi, _) in enumerate(comps):
                if attached[i]:
                    continue
                if any(np.all(lo <= bh + self.ATTACH) and np.all(hi >= bl - self.ATTACH) for bl, bh in boxes):
                    attached[i] = True; boxes.append((lo, hi)); grew = True
        for i, (n, pp, _, _, li) in enumerate(comps):
            if attached[i]:
                if li is None:
                    self.mesh[n] += pp
                else:          # a strut piece from an old door bone joins the leg
                    claimed.add(li)
                    self.mesh.setdefault(self.leg_mesh, []).extend(pp)
                    self.taken += len(pp)
            elif li is None:
                self.dropped += len(pp)

    def strut_polys(self, H, half=0.6):
        """A square strut from the top of the gear's attached pieces up to the hinge, if they stop short."""
        legp = [c[0] for n, ps in self.mesh.items() if n not in self.wheel_bones for p in ps for c in p]
        if not legp:
            return []
        legp = np.array(legp)
        topy = legp[:, 1].max()
        if H[1] - topy < 1.0:
            return []
        near = legp[legp[:, 1] > topy - 1.0]
        cx, cz = near[:, 0].mean(), near[:, 2].mean()
        y0, y1 = topy - 1.0, H[1] + 0.5
        uv = self.strut_uv
        P = lambda x, y, z: np.array([x, y, z], float)
        corners = [(cx - half, cz - half), (cx + half, cz - half), (cx + half, cz + half), (cx - half, cz + half)]
        out = []
        for i in range(4):
            (xa, za), (xb, zb) = corners[i], corners[(i + 1) % 4]
            n = unit([(xa + xb) / 2 - cx, 0, (za + zb) / 2 - cz])
            out.append([(P(xa, y0, za), n, uv), (P(xb, y0, zb), n, uv), (P(xb, y1, zb), n, uv),
                        (P(xa, y1, za), n, uv)])
        return out

    def parts(self, H):
        """(upper, lower): {bone: polygons} of the leg's fixed upper part (with the strut stand-in for hinge H, in
        the leg mesh) and of the sliding lower part (pieces of the leg meshes below split_y, then every mesh
        under the steering / wheel bones). Narrow pieces of the lower part (strut pistons) are slimmed by 15 %
        about the axle's vertical line, so they never share a face with the upper strut they slide into."""
        key = tuple(np.round(H, 4))
        if key in self._parts:
            return self._parts[key]
        upper, lower = {}, {}
        for n in self.mesh_bones:
            polys = list(self.mesh.get(n, []))
            if n == self.leg_mesh:
                polys += self.strut_polys(H)
            if n in self.direct:
                up, lo = [], []
                for p in polys:
                    a, b = M.split(p, 1, self.split_y, True)
                    if a is not None:
                        up.append(a)
                    if b is not None:
                        lo.append(b)
                upper[n] = up
                lower[n] = self._slim(lo)
            else:
                lower[n] = polys if n in self.wheel_bones else self._slim(polys)
        self._parts[key] = (upper, lower)
        return upper, lower

    def _slim(self, polys, k=0.85):
        out = [None] * len(polys)
        for comp in components(polys):
            pts = np.array([c[0] for i in comp for c in polys[i]])
            ext = pts.max(0) - pts.min(0)
            narrow = ext[0] < 3.0 and ext[2] < 3.0 and ext[1] > 1.5 * max(ext[0], ext[2])
            for i in comp:
                if narrow:
                    out[i] = [(np.array([self.axle[0] + (c[0][0] - self.axle[0]) * k, c[0][1],
                                         self.axle[2] + (c[0][2] - self.axle[2]) * k]), c[1], c[2])
                              for c in polys[i]]
                else:
                    out[i] = polys[i]
        return out

    def rest_points(self, H, D, tw=0.0, u=(0.0, 1.0, 0.0)):
        """Sample points of the gear with the lower part twisted tw degrees about the leg's vertical axis through
        the axle and slid D px up the leg (before the swing), the upper part trimmed at the hinge."""
        key = ('rest', tuple(np.round(H, 4)), round(float(D), 3), round(float(tw), 3), tuple(np.round(u, 4)))
        if key in self._pts:
            return self._pts[key]
        upper, lower = self.parts(H)
        up = [q for ps in upper.values() for p in ps for q in [M.split(p, 1, H[1] + 0.8, False)[0]] if q is not None]
        lo = [p for ps in lower.values() for p in ps]
        pts = sample(up)
        lp = sample(lo)
        if len(lp):
            if tw:
                c = np.array([self.axle[0], 0, self.axle[2]])
                lp = (lp - c) @ rot([0, 1.0, 0], tw).T + c
            lp = lp + np.asarray(u, float) * D
        pts = np.concatenate([x for x in (pts, lp) if len(x)])
        self._pts[key] = pts
        return pts

    def points(self, trim_y=None, H=None):
        """Corner points of the gear's polygons (and its strut stand-in for hinge H); with trim_y, of the polygons
        clipped to y <= trim_y (a strut is a long prism with corners only at its ends, so clipping, not
        filtering, keeps its length)."""
        key = (trim_y, None if H is None else tuple(np.round(H, 4)))
        if key in self._pts:
            return self._pts[key]
        pts = []
        polys = [p for ps in self.mesh.values() for p in ps]
        if H is not None:
            polys += self.strut_polys(H)
        for p in polys:
            if trim_y is not None:
                p, _ = M.split(p, 1, trim_y, False)
                if p is None:
                    continue
            pts += [c[0] for c in p]
            # and points along long edges, so a box around them follows the strut
            for i in range(len(p)):
                a, b = p[i][0], p[(i + 1) % len(p)][0]
                L = np.linalg.norm(b - a)
                for t in np.arange(2.0, L, 2.0):
                    pts.append(a + (b - a) * (t / L))
        self._pts[key] = np.array(pts)
        return self._pts[key]


def loose_pieces(m):
    """Connected pieces of the old door bones (an earlier tool's split; they often hold strut pieces)."""
    out = []
    for d in m.rig.get('GearDoors') or []:
        for n in m.subtree(d['Bone']):
            polys = m.polys.get(n)
            if not polys:
                continue
            for comp in components(polys):
                pp = [polys[i] for i in comp]
                pts = np.array([c[0] for p in pp for c in p])
                out.append((n, pp, pts.min(0), pts.max(0)))
    return out


def sample(polys):
    pts = []
    for p in polys:
        pts += [c[0] for c in p]
        for i in range(len(p)):          # points along long edges, so a box around them follows a strut
            a, b = p[i][0], p[(i + 1) % len(p)][0]
            L = np.linalg.norm(b - a)
            for t in np.arange(2.0, L, 2.0):
                pts.append(a + (b - a) * (t / L))
    return np.array(pts).reshape(-1, 3)


# ------------------------------------------------------------------------------------------------ the airframe

class Skin:
    """Ray queries against the airframe. `tris` is the cuttable skin (wreck_<piece>__hull); `fixed` is every
    other airframe part a well must not reach into (cockpit tub, canopy, parts split into bones of their own)."""

    def __init__(self, m):
        self.m = m
        self.tris = m.airframe_tris()
        moving = set()
        for key in ('Surfaces', 'Flaps', 'Sweeps', 'Rotors', 'NoseSteering', 'GearDoors', 'Gear'):
            for p in m.rig.get(key) or []:
                moving |= set(m.subtree(p['Bone']))
        fixed = [p for n, polys in m.polys.items() if n.startswith('wreck_') and not AIRFRAME.match(n)
                 and n not in moving and 'suspended' not in n and 'occluder' not in n for p in polys]
        self.fixed = M.tri_array(fixed)
        self.all = np.concatenate([self.tris, self.fixed]) if len(self.fixed) else self.tris
        self._low = {}

    @staticmethod
    def _dedupe(h):
        if len(h) > 1:                 # a ray through a shared edge hits both faces
            h = h[np.concatenate([[True], np.diff(h) > 0.05])]
        return h

    def column(self, x, z, y0=-200.0, which='tris'):
        """Heights at which a vertical line at (x, z) crosses the skin (or the `fixed` parts), bottom first."""
        return self._dedupe(y0 + M.ray_hits(getattr(self, which), [x, y0, z], [0, 1.0, 0]))

    def inside(self, p, reach=60.0):
        """A point is inside the airframe when the airframe is found above it and to both sides."""
        for d in ([0, 1.0, 0], [1.0, 0, 0], [-1.0, 0, 0]):
            h = M.ray_hits(self.all, p, d)
            if not len(h) or h[0] > reach:
                return False
        return True

    def skin_y(self, x, z, q=0.25):
        """The lowest skin over (x, z), sampled on a q-px lattice (cached: only for checks, never geometry)."""
        k = (round(x / q), round(z / q))
        if k not in self._low:
            h = self.column(k[0] * q, k[1] * q)
            self._low[k] = h[0] if len(h) else None
        return self._low[k]

    def exterior(self, poly):
        """True for a skin piece that faces the open air below or beside the aircraft (some downward ray from it
        escapes), false for an internal face (a duct floor, a bulkhead) that has the skin under it."""
        c = np.mean([q[0] for q in poly], axis=0)
        for d in ([0, -1.0, 0], [1, -1.0, 0], [-1, -1.0, 0], [0, -1.0, 1], [0, -1.0, -1], [1.0, -0.2, 0],
                  [-1.0, -0.2, 0]):
            d = unit(d)
            h = M.ray_hits(self.all, c + d * 0.02, d)
            if not len(h[h > 0.02]):
                return True
        return False


# ------------------------------------------------------------------------------------------------ planning

def swing_axis(v, s, ideal):
    """Unit axis (+-ideal) and signed angle that turn leg vector v onto direction s."""
    a = np.array(ideal, float)
    vp = v - a * (v @ a)
    ang = math.degrees(math.atan2(float(np.cross(vp, s) @ a), float(vp @ s)))
    return a, ang


def make_slabs(st, s_axis, s_lo, s_hi, step=2.0, jump=2.0, most=4):
    """Splits the well along its length into at most `most` sections, each with a flat ceiling just over the
    stowed gear beneath it: a deep pocket for the wheel, a shallow one for the strut in a thin wing."""
    edges = list(np.arange(s_lo, s_hi - 0.5, step)) + [s_hi]
    bins = []
    for i in range(len(edges) - 1):
        a, b = edges[i], edges[i + 1]
        sel = st[(st[:, s_axis] >= a - MARGIN) & (st[:, s_axis] <= b + MARGIN)]
        top = (sel[:, 1].max() if len(sel) else st[:, 1].min()) + MARGIN
        bins.append([a, b, top])
    slabs = [bins[0]]
    for a, b, t in bins[1:]:
        if abs(t - slabs[-1][2]) <= jump:
            slabs[-1][1] = b; slabs[-1][2] = max(slabs[-1][2], t)
        else:
            slabs.append([a, b, t])
    while len(slabs) > most:
        k = min(range(len(slabs) - 1), key=lambda i: abs(slabs[i][2] - slabs[i + 1][2]))
        slabs[k] = [slabs[k][0], slabs[k + 1][1], max(slabs[k][2], slabs[k + 1][2])]
        del slabs[k + 1]
    return [tuple(float(v) for v in x) for x in slabs]


def slab_top(slabs, s_axis, p):
    for s0, s1, t in slabs:
        if s0 - 1e-6 <= p[s_axis] <= s1 + 1e-6:
            return t
    return -1e9


def in_well(plan, p):
    lo, hi = plan['lo'], plan['hi']
    if not (lo[0] - 1e-6 <= p[0] <= hi[0] + 1e-6 and lo[2] - 1e-6 <= p[2] <= hi[2] + 1e-6 and p[1] >= lo[1]):
        return False
    return p[1] <= slab_top(plan['slabs'], plan['s_axis'], p) + 1e-6


def plan_leg(g, skin, direction, others, cfg, accept=None):
    """The cheapest well for leg g folding toward `direction`, or (None, why). `accept`, if given, must also
    approve the plan (the mirror leg of a pair has to fit the same way)."""
    side = 1.0 if g.axle[0] >= 0 else -1.0
    if direction in DIRS:
        s = DIRS[direction]; ideal = [1.0, 0, 0]
    elif direction == 'in':
        s = np.array([-side, 0, 0]); ideal = [0, 0, 1.0]
    elif direction == 'out':
        s = np.array([side, 0, 0]); ideal = [0, 0, 1.0]
    elif direction in ('xpos', 'xneg'):        # a nose leg folding sideways, its wheel laid flat
        s = np.array([1.0 if direction == 'xpos' else -1.0, 0, 0]); ideal = [0, 0, 1.0]
    else:
        raise ValueError(direction)
    # the hinge sits straight above the axle, or (a strut raked toward the fuselage) at the strut's top: a raked
    # leg's top is sometimes only a drag brace, so both are tried, the vertical one first
    ups = [np.array([0, 1.0, 0])]
    lean = g.top - g.axle
    if cfg.get('raked', True) and np.hypot(lean[0], lean[2]) > 2.0:
        ups.append(unit(lean))
    modes = cfg.get('hinge_modes', range(len(ups)))
    cands, worst, hits_any = [], None, False
    # fore-and-aft folds may twist the wheel a quarter turn so it lies flat (mirrored left and right)
    twists = [t * (1.0 if g.nose else side) for t in cfg.get('twists', [0.0, 90.0] if direction in DIRS else [0.0])]
    for ui, u in enumerate(ups):
        if ui not in modes:
            continue
        hits = M.ray_hits(skin.tris, g.axle + u * (g.radius * 0.5), u)
        if not len(hits):
            continue
        hits_any = True
        S = g.axle + u * (g.radius * 0.5 + hits[0])
        for D, tw, extra in itertools.product(cfg.get('slides', [0, 2, 4, 6, 8, 10, 13, 16, 20, 24]), twists,
                                              cfg.get('extra_degs', [0, 10, 20, 30])):
            for d in np.arange(0.5, 2 * g.radius + 4.01, 0.25):
                H = S + u * d
                if D * u[1] > H[1] - 0.5 - g.split_y:
                    continue
                a, ang = swing_axis(g.axle + u * D - H, s, ideal)
                ang += extra * (1 if ang >= 0 else -1)
                st = (g.rest_points(H, D, tw, u) - H) @ rot(a, ang).T + H
                ok = True       # every stowed point must sit above the skin over it, so the doors close
                for p in st[:: max(1, len(st) // 160)]:
                    y = skin.skin_y(p[0], p[2])
                    if y is None or p[1] < y + DOOR_CLEAR:
                        ok = False; worst = worst or (p, y); break
                if ok:
                    cands.append((d + extra / 10.0 + D / 3.0 + abs(tw) / 90.0 + ui, H, a, ang, st, d, extra, D, tw, u, ui))
                    break
    if not hits_any:
        return None, 'leg axis never meets the skin'
    if not cands:
        return None, 'stowed gear never clears the skin' + (
            f' (e.g. {np.round(worst[0], 1).tolist()} over skin {worst[1] if worst[1] is None else round(worst[1], 1)})'
            if worst else '')
    first = None
    for _, H, a, ang, st, d, extra, D, tw, u, ui in sorted(cands, key=lambda c: c[0])[:40]:
        plan, why = check_plan(g, skin, direction, s, H, a, ang, st, d, others, D, tw, u)
        if plan is not None:
            plan['extra'] = extra
            plan['mode'] = ui
            if accept is None:
                return plan, None
            why = accept(plan)
            if why is None:
                return plan, None
        first = first or why
    return None, first


def check_plan(g, skin, direction, s, H, a, ang, st, d, others, D=0.0, tw=0.0, u=(0.0, 1.0, 0.0)):
    m = g.m
    s_axis = 2 if abs(s[2]) > 0.5 else 0           # the well runs along the swing
    c_axis = 2 - s_axis
    lo = st.min(0) - MARGIN; hi = st.max(0) + MARGIN
    slabs = make_slabs(st, s_axis, lo[s_axis], hi[s_axis])
    skin_min = 1e9
    inner = 0
    cs = np.linspace(lo[c_axis], hi[c_axis], max(3, int((hi[c_axis] - lo[c_axis]) / 1.0) + 1))
    for si, (s0, s1, top) in enumerate(slabs):
        ss = np.linspace(s0, s1, max(2, int((s1 - s0) / 1.0) + 1))
        for sv in ss:
            for cv in cs:
                x, z = (cv, sv) if s_axis == 2 else (sv, cv)
                col = skin.column(x, z)
                if not len(col):
                    return None, f'no skin under the well at ({x:.1f},{z:.1f})'
                allc = skin.column(x, z, which='all')
                if allc[-1] < top + TOP_SKIN:
                    return None, f'the well breaks through the top of the airframe at ({x:.1f},{z:.1f})'
                if col[0] > top - 0.5:
                    return None, f'skin above the ceiling at ({x:.1f},{z:.1f})'
                fx = skin.column(x, z, which='fixed')
                fx = fx[(fx > col[0] - 0.1) & (fx < top + 0.3)]
                if len(fx):
                    return None, f'the well reaches into a fixed part (cockpit, canopy) at ({x:.1f},{fx[0]:.1f},{z:.1f})'
                inner += int(np.sum((col[1:] < top + 0.3)))
                skin_min = min(skin_min, col[0])
                edge = cv in (cs[0], cs[-1]) or (si == 0 and sv == ss[0]) or (si == len(slabs) - 1 and sv == ss[-1])
                if edge and not skin.inside(np.array([x, top - 0.05, z])):
                    return None, f'the well wall at ({x:.1f},{z:.1f}) would stand outside the airframe'
    # the doors are cut from the skin under the well: it must be one gently curved surface, not a nacelle's side
    # or a step, or a door would carry a wall with it
    grid = {}
    for s0, s1, top in slabs:
        for sv in np.arange(s0, s1 + 1e-6, 1.0):
            for cv in np.arange(lo[c_axis], hi[c_axis] + 1e-6, 1.0):
                x, z = (cv, sv) if s_axis == 2 else (sv, cv)
                col = skin.column(x, z)
                grid[(round(sv, 3), round(cv, 3))] = col[0] if len(col) else None
    for (sv, cv), y in grid.items():
        for nb in ((round(sv + 1.0, 3), cv), (sv, round(cv + 1.0, 3))):
            y2 = grid.get(nb)
            if y is not None and y2 is not None and abs(y2 - y) > STEEP:
                x, z = (cv, sv) if s_axis == 2 else (sv, cv)
                return None, f'the skin under the well is too steep at ({x:.1f},{z:.1f}) for a door'
    lo[1] = skin_min - 1.0
    hi[1] = max(t for _, _, t in slabs)
    plan = {'leg': g.leg, 'dir': direction, 'H': H, 'axis': a, 'angle': ang, 'lo': lo, 'hi': hi,
            'depth': d, 's': s, 'stowed': st, 'inner': inner, 'slabs': slabs, 's_axis': s_axis,
            'c_axis': c_axis, 'slide': D, 'twist': tw, 'up': np.asarray(u, float)}
    # stores, control surfaces and other wells must stay clear
    for n, polys in m.polys.items():
        if AIRFRAME.match(n) or n in g.mesh_bones:
            continue
        if 'suspended' in n or any(n in m.subtree(p['Bone']) for p in (m.rig.get('Surfaces') or [])
                                    + (m.rig.get('Flaps') or [])):
            for p in polys:
                if M.overlaps_box(p, lo, hi, pad=0.1):
                    return None, f'{n} intrudes into the well'
    for o in others:
        if np.all(o['hi'] >= lo) and np.all(o['lo'] <= hi):
            return None, f'overlaps the well of {o["leg"]}'
    if not g.nose:          # a main gear's well stays on its own side of the centreline
        if (g.axle[0] >= 0 and lo[0] < 0.3) or (g.axle[0] < 0 and hi[0] > -0.3):
            return None, 'the well crosses the centreline'
    # the swing: intermediate poses may hang below the skin or sit in the well, nowhere else
    bad = 0
    for k in range(1, 8):
        Rk = rot(a, ang * k / 8)
        for p in ((g.rest_points(H, D * k / 8, tw * k / 8, u)[::3]) - H) @ Rk.T + H:
            if in_well(plan, p):
                continue
            y = skin.skin_y(p[0], p[2])
            if y is not None and p[1] > y + 0.2:
                bad += 1
    plan['swing_clip'] = bad
    return plan, None


def preferences(g, cfg):
    over = (cfg.get('legs') or {}).get(g.leg) or {}
    if 'dirs' in over:
        return over['dirs']
    return cfg.get('nose_dirs', ['fwd', 'aft', 'xpos', 'xneg']) if g.nose else cfg.get('main_dirs', ['in', 'fwd', 'aft'])


# ------------------------------------------------------------------------------------------------ building

def well_uv(vid):
    """A texel in a flat, dark-grey patch of the skin texture, for the well walls and ceiling."""
    im = np.asarray(Image.open(os.path.join(TEX, vid + '.png')).convert('RGBA')).astype(np.float32) / 255
    H, W = im.shape[:2]
    b = 16
    best, at = 1e9, (0.5, 0.5)
    for y in range(0, H - b, b):
        for x in range(0, W - b, b):
            blk = im[y:y + b, x:x + b]
            if blk[..., 3].min() < 1:
                continue
            rgb = blk[..., :3]
            sd = float(rgb.std(axis=(0, 1)).max())
            if sd > 0.02:
                continue
            mean = rgb.mean(axis=(0, 1))
            sat = float(mean.max() - mean.min())
            lum = float(mean @ [0.3, 0.59, 0.11])
            score = abs(lum - 0.22) + sat * 2 + sd * 4
            if score < best:
                best, at = score, ((x + b / 2) / W, 1 - (y + b / 2) / H)
    return np.array(at)


def build(m, plans, gears, skin):
    skin_bones = m.airframe()
    new_polys = {n: list(m.polys[n]) for n in skin_bones}
    cut_points = []
    report = []
    uv = well_uv(m.vid)
    door_entries, retracts = [], {}
    door_index = 0
    new_bones = []
    for plan in plans:
        g = gears[plan['leg']]
        lo, hi = plan['lo'], plan['hi']
        sa, ca = plan['s_axis'], plan['c_axis']
        slabs = plan['slabs']
        boxes = []
        for s0, s1, top in slabs:
            blo, bhi = lo.copy(), hi.copy()
            blo[sa], bhi[sa], bhi[1] = s0, s1, top
            boxes.append((blo, bhi))
        inside = []              # (bone, polygon) pieces of skin inside the well
        for n in skin_bones:
            for blo, bhi in boxes:
                kept = []
                for poly in new_polys[n]:
                    if not M.overlaps_box(poly, blo, bhi):
                        kept.append(poly); continue
                    outs, ins = M.subtract_box(poly, blo, bhi)
                    kept += outs
                    for piece in outs:
                        cut_points += [c[0] for c in piece]
                    if ins is not None:
                        inside.append((n, ins))
                        cut_points += [c[0] for c in ins]
                new_polys[n] = kept
        # pieces facing the open air become the doors; internal faces inside the well go
        doors = [(n, p) for n, p in inside if skin.exterior(p)]
        dropped_inner = len(inside) - len(doors)
        piece = max(((AIRFRAME.match(n).group(1), M.area(p)) for n, p in doors), key=lambda t: t[1])[0] \
            if doors else AIRFRAME.match(skin_bones[0]).group(1)
        # walls: from each door edge lying on the well's outline up to the ceiling over it
        well = []
        centre = (lo + hi) / 2
        outline = [(ca, lo[ca]), (ca, hi[ca]), (sa, slabs[0][0]), (sa, slabs[-1][1])]
        for n, p in doors:
            k = len(p)
            for j in range(k):
                a, b = p[j][0], p[(j + 1) % k][0]
                for axis, val in outline:
                    if abs(a[axis] - val) < 1e-6 and abs(b[axis] - val) < 1e-6:
                        top = slab_top(slabs, sa, (a + b) / 2)
                        if top - max(a[1], b[1]) < 1e-4:
                            continue
                        nrm = np.zeros(3); nrm[axis] = 1.0 if centre[axis] > val else -1.0
                        at, bt = a.copy(), b.copy(); at[1] = top; bt[1] = top
                        well.append([(b, nrm, uv), (a, nrm, uv), (at, nrm, uv), (bt, nrm, uv)])
        dn = np.array([0, -1.0, 0])
        for i, ((s0, s1, top), (blo, bhi)) in enumerate(zip(slabs, boxes)):
            q = [np.array([blo[0], top, blo[2]]), np.array([bhi[0], top, blo[2]]),
                 np.array([bhi[0], top, bhi[2]]), np.array([blo[0], top, bhi[2]])]
            well.append([(v, dn, uv) for v in q])
            if i + 1 < len(slabs):           # the step between this ceiling and the next one
                t2 = slabs[i + 1][2]
                if abs(t2 - top) > 1e-4:
                    y0, y1 = min(top, t2), max(top, t2)
                    nrm = np.zeros(3); nrm[sa] = 1.0 if t2 > top else -1.0
                    c0, c1 = lo[ca], hi[ca]
                    r = []
                    for cv, yv in ((c0, y0), (c1, y0), (c1, y1), (c0, y1)):
                        v = np.zeros(3); v[sa] = s1; v[ca] = cv; v[1] = yv
                        r.append((v, nrm, uv))
                    well.append(r)
        well = [w for w in (M.clean(w) for w in well) if w]
        new_polys['wreck_%s__hull' % piece] = new_polys.get('wreck_%s__hull' % piece, []) + well
        cut_points += [c[0] for w in well for c in w]
        # doors: a clamshell split along the well, each half hinged on its outer edge
        mid = centre[ca]
        halves = {0: [], 1: []}
        for n, p in doors:
            low, high = M.split(p, ca, mid, False)         # low: coord <= mid
            for k, q in ((0, low), (1, high)):
                if q is not None:
                    halves[k].append(q)
                    cut_points += [c[0] for c in q]
        for k, polys in halves.items():
            if not polys:
                continue
            edge = lo[ca] if k == 0 else hi[ca]
            on = [c[0] for p in polys for c in p if abs(c[0][ca] - edge) < 1e-6]
            pts = np.array([c[0] for p in polys for c in p])
            pivot = np.zeros(3)
            pivot[ca] = edge
            pivot[1] = float(np.mean([q[1] for q in on])) if on else float(pts[:, 1].max())
            pivot[sa] = float((lo[sa] + hi[sa]) / 2)
            axis = np.zeros(3); axis[sa] = 1.0
            cen = pts.mean(0)
            best = None                      # open outward and down: the sign that lowers the door
            for sgn in (1, -1):
                c2 = (cen - pivot) @ rot(axis, sgn * OPEN_DEG).T + pivot
                if best is None or c2[1] < best[1]:
                    best = (sgn, c2[1])
            name = 'gear_door_%d' % door_index
            door_index += 1
            pv = [round(float(v), 5) for v in pivot]
            new_bones.append({'name': name, 'parent': 'hull', 'pivot': pv})
            new_bones.append({'name': 'wreck_%s__%s' % (piece, name), 'parent': name, 'pivot': pv, 'polys': polys})
            door_entries.append({'Bone': name, 'Parent': 'hull', 'Pivot': pv,
                                 'Axis': [float(v) for v in axis], 'OpenAngleDegrees': best[0] * OPEN_DEG})
        retracts[g.leg] = plan
        report.append(f"{g.leg}: {plan['dir']} {plan['angle']:+.0f} deg, shortens {plan['slide']:g} px, wheel twist {plan['twist']:+.0f}, hinge {np.round(plan['H'], 1).tolist()}, "
                      f"well {np.round(lo, 1).tolist()}..{np.round(hi, 1).tolist()} in {len(slabs)} section(s); "
                      f"{len(doors)} skin pieces -> 2 doors, {dropped_inner} inner faces removed, "
                      f"{g.dropped} open-door faces dropped, {g.taken} strut faces taken from old door bones, "
                      f"{len(well)} well faces, swing clips {plan['swing_clip']}")
    # close every T-junction the cuts made (skin bones and the new door pieces)
    for n in skin_bones:
        new_polys[n] = M.repair_t_junctions(new_polys[n], cut_points)
    for b in new_bones:
        if 'polys' in b:
            b['polys'] = M.repair_t_junctions(b['polys'], cut_points)
    return new_polys, new_bones, door_entries, retracts, report


def apply(m, new_polys, new_bones, door_entries, retracts, gears):
    # old door bones (modelled open) and their entries go: the skin doors replace them
    old_doors = [d['Bone'] for d in m.rig.get('GearDoors') or []]
    drop = set()
    for d in old_doors:
        drop |= set(m.subtree(d))
    for n, polys in new_polys.items():
        M.write_polys(m.bones[n]['poly_mesh'], [q for p in polys for q in M.triangulate(p)])
    slide_index = 0
    for leg, plan in retracts.items():
        g = gears[leg]
        H = [round(float(v), 5) for v in plan['H']]
        trim = plan['H'][1] + 0.8
        D = float(plan.get('slide', 0.0))
        tw = float(plan.get('twist', 0.0))
        upper, lower = g.parts(plan['H'])
        slide = None
        if D > 0.01 or tw:
            slide = 'gear_slide_%d' % slide_index
            slide_index += 1
            piece = g.leg_mesh.split('__')[0] if g.leg_mesh else 'wreck_' + AIRFRAME.match(m.airframe()[0]).group(1)
            pv = [round(float(v), 5) for v in g.axle]
            lower_leg = [p for n in g.direct for p in lower.get(n, [])]
            new_bones.append({'name': slide, 'parent': leg, 'pivot': pv})
            new_bones.append({'name': '%s__%s' % (piece, slide), 'parent': slide, 'pivot': pv, 'polys': lower_leg})
            # the steering and wheel bones ride on the sliding part
            for k in m.kids.get(leg, []):
                if not k.startswith('wreck_') and k not in g.stores:
                    m.bones[k]['parent'] = slide
            for key in ('Rotors', 'NoseSteering'):
                for e in m.rig.get(key) or []:
                    if e.get('Parent') == leg:
                        e['Parent'] = slide
        for n in g.mesh_bones:
            if n in g.wheel_bones:
                continue                         # the wheels themselves stay exactly as they are
            if n in g.direct:
                polys = upper.get(n, []) if slide else upper.get(n, []) + lower.get(n, [])
                clipped = []
                for p in polys:
                    keep, _ = M.split(p, 1, trim, False)
                    if keep is not None:
                        clipped += M.triangulate(keep)
                polys = clipped
            else:
                polys = lower.get(n, []) if slide else list(g.mesh.get(n, []))
            M.write_polys(m.bones[n]['poly_mesh'], [q for p in polys for q in M.triangulate(p)])
        for n in [leg] + [k for k in m.kids.get(leg, []) if k.startswith('wreck_')]:
            m.bones[n]['pivot'] = H
        for e in m.rig['Gear']:
            if e['Bone'] == leg:
                e['Retract'] = {'Pivot': H, 'Axis': [round(float(v), 6) for v in plan['axis']],
                                'AngleDegrees': round(float(plan['angle']), 3)}
                if slide:
                    e['Retract']['Slide'] = {'Bone': slide, 'Pivot': [round(float(v), 5) for v in g.axle],
                                             'Direction': [round(float(v), 6) for v in plan['up']],
                                             'DistancePx': round(D, 3)}
                    if tw:
                        e['Retract']['Slide']['TwistDegrees'] = round(tw, 3)
    for g in gears.values():
        for n in g.stores:
            if g.leg in retracts:
                m.bones[n]['parent'] = 'hull'
    bl = [b for b in m.bones_list if b['name'] not in drop]
    for b in new_bones:
        nb = {'name': b['name'], 'parent': b['parent'], 'pivot': b['pivot']}
        if 'polys' in b:
            pm = {'normalized_uvs': True}
            M.write_polys(pm, [q for p in b['polys'] for q in M.triangulate(p)])
            nb['poly_mesh'] = pm
        bl.append(nb)
    # parents before children (the slide bones are new parents of existing steering / wheel bones)
    byname = {b['name']: b for b in bl}
    done, ordered = set(), []

    def emit(b):
        if b['name'] in done:
            return
        p = b.get('parent')
        if p in byname and p not in done:
            emit(byname[p])
        done.add(b['name']); ordered.append(b)
    for b in bl:
        emit(b)
    m.geo['minecraft:geometry'][0]['bones'] = ordered
    m.rig['GearDoors'] = door_entries


def write(m, out_dir=None):
    raw = open(m.gpath).read()
    if out_dir:
        m.gpath = os.path.join(out_dir, m.vid + '.geo.json')
        m.vpath = os.path.join(out_dir, m.vid + '.json')
        open(m.vpath, 'w').write(open(os.path.join(VEH, m.vid + '.json'), encoding='utf-8').read())
    with open(m.gpath, 'w') as fh:
        json.dump(m.geo, fh, separators=(',', ':'))
        if raw.endswith('\n'):
            fh.write('\n')
    import rig as R  # tools/gear_wheels: keeps the vehicle file's layout (CanopyGlass on one line)
    R.write_vehicle(m.vpath, m.asset)


def run(vid, do_write=False, cfg_all=None, out_dir=None):
    cfg_all = cfg_all if cfg_all is not None else (json.load(open(CONFIG)) if os.path.exists(CONFIG) else {})
    cfg = dict(cfg_all.get('default', {}))
    cfg.update(cfg_all.get(vid, {}))
    m = Model(vid)
    legs = [e for e in m.rig.get('Gear') or [] if e.get('VisibleWhen') != 'RETRACTED']
    if not legs:
        return None, ['no gear']
    if any('Retract' in e for e in legs):
        return None, ['already has retracting gear']
    skin = Skin(m)
    loose, claimed = loose_pieces(m), set()
    gears = {e['Bone']: Gear(m, e, loose, claimed) for e in legs}
    plans, notes = [], []

    def mirror_of(g):
        return next((o for o in gears.values() if o is not g and not o.nose and not g.nose
                     and abs(o.axle[0] + g.axle[0]) < 0.6 and abs(o.axle[2] - g.axle[2]) < 0.6), None)

    def same_as(p):
        return dict(cfg, slides=[p['slide']], extra_degs=[p['extra']], twists=[abs(p['twist'])],
                    hinge_modes=[p['mode']])

    # nose legs first: they are the most constrained; a main gear and its mirror are planned together, folding
    # the same way, so the pair looks alike
    done = set()
    for leg in sorted(gears, key=lambda n: (not gears[n].nose, n)):
        if leg in done:
            continue
        g = gears[leg]
        twin = mirror_of(g)
        reasons, chosen, pair = [], None, None
        for d in preferences(g, cfg):
            if twin is None:
                plan, why = plan_leg(g, skin, d, plans, cfg)
            else:
                found = {}

                def accept(p, d=d, found=found):
                    q, why = plan_leg(twin, skin, d, plans + [p], same_as(p))
                    if q is None:
                        return f'its mirror {twin.leg} does not fit the same way ({why})'
                    found['q'] = q
                    return None
                plan, why = plan_leg(g, skin, d, plans, cfg, accept)
                pair = found.get('q')
            if plan is not None:
                chosen = plan; break
            reasons.append(f'{d}: {why}')
        if chosen is None:
            notes.append(f'{leg}: no well fits ({"; ".join(reasons)})')
            return None, notes
        if reasons:
            notes.append(f'{leg}: ' + '; '.join(reasons))
        plans.append(chosen)
        done.add(leg)
        if twin is not None:
            plans.append(pair)
            done.add(twin.leg)
    new_polys, new_bones, doors, retracts, report = build(m, plans, gears, skin)
    apply(m, new_polys, new_bones, doors, retracts, gears)
    if do_write or out_dir:
        write(m, out_dir)
    return m, notes + report


def main(argv):
    do_write = '--write' in argv
    out_dir = argv[argv.index('--out') + 1] if '--out' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != out_dir]
    for vid in ids:
        m, notes = run(vid, do_write, out_dir=out_dir)
        print(vid, 'OK' if m else 'SKIPPED')
        for n in notes:
            print('   ', n)


if __name__ == '__main__':
    main(sys.argv[1:])
