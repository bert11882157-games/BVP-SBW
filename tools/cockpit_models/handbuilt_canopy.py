"""Building blocks for the hand-made canopies (one file per aircraft, canopy_<vid>.py): the canopy's shape is given by
hand as cross-sections (the right half of each, from the sill up over the top, metres from the pilot's eye), and
its structure as frame members placed by hand: transverse bows (the windscreen arch, the canopy bows) at stations
along it, longitudinal members (windscreen posts, the sill rails, a spine) along a position across the sections.
From those this module makes the glass (CanopyGlass, as the game draws it) and the frames (a canopy bone), and
removes the model's old canopy frame faces (canopy_models/strip.py finds them against the old glass).

Nothing here measures or guesses a shape: glass_sections.py prints the old glass's sections for reference, and the
aircraft's file holds the shape and the frame layout it settles on.
"""
import json, math, os, subprocess, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, 'authored'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_models'))
import cockpit_lib as L      # noqa: E402
import handbuilt as H        # noqa: E402
import free_atlas as FA      # noqa: E402
import lib as A              # noqa: E402

N = 18                        # points per resampled half-section, sill to top
_FRAMES = []                  # every bow and member placed this run: the glass is cut round them (cut_glass)
_GLASS = []                   # every glass patch asked for this run
_EXTRA = []                   # free-standing glass (extra_glass): not cut by the frames, not used to clear old faces


def extra_glass(points):
    """glass triangles (flat list of vehicle-local points) that stand apart from the canopy's skin, e.g. an armoured
    plate inside the windscreen; they go into CanopyGlass as they are"""
    _EXTRA.extend(points)
BASE = 'be357476'             # the commit holding the models' original canopy glass


def original_glass(vid):
    """the model's original CanopyGlass triangles (k x 3 x 3), from git"""
    path = f'bvp/src/generated/resources/assets/berts_vehicle_pack/sbw/vehicles/{vid}.json'
    git = os.environ.get('BVP_GIT', r'C:\Users\udstu\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\git\cmd\git.exe')
    repo = os.path.normpath(os.path.join(HERE, '..', '..'))
    txt = subprocess.run([git, '-C', repo, 'show', f'{BASE}:{path}'], capture_output=True, check=True).stdout
    cg = json.loads(txt.decode('utf-8')).get('CanopyGlass') or {}
    return np.array(cg.get('Triangles', []), float).reshape(-1, 3, 3)


class Shape:
    """the canopy as cross-sections: [(z, [(x, y), ...]), ...] (eye-relative, right half from the sill up to the top
    centre at x = 0), resampled by arc length to N points; point(z, s) on the surface, s = 0 at the sill, 1 at the top"""

    def __init__(self, sections):
        self.z = np.array([z for z, _ in sections], float)
        self.P = np.array([self.resample(np.array(p, float)) for _, p in sections])

    @staticmethod
    def resample(p):
        d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
        t = np.linspace(0, d[-1], N)
        return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]

    def section(self, z):
        z = float(np.clip(z, self.z[0], self.z[-1]))
        i = int(np.clip(np.searchsorted(self.z, z) - 1, 0, len(self.z) - 2))
        f = (z - self.z[i]) / max(1e-9, self.z[i + 1] - self.z[i])
        return self.P[i] * (1 - f) + self.P[i + 1] * f

    def point(self, z, s, side=1):
        sec = self.section(z)
        k = s * (N - 1)
        i = int(min(N - 2, math.floor(k)))
        f = k - i
        x, y = sec[i] * (1 - f) + sec[i + 1] * f
        return np.array([x * side, y, z])

    def arclen(self, z):
        """the half-section's length at z, sill to top (metres per unit of s)"""
        sec = self.section(z)
        return float(np.linalg.norm(np.diff(sec, axis=0), axis=1).sum())

    def s_at_x(self, z, x):
        """the position s across the section at z where the surface stands x out from the centre line (s from the
        sill up, the first point at or inside x): for posts and members set by their width, not by arc length"""
        sec = self.section(z)
        for i in range(N - 1):
            a, b = sec[i][0], sec[i + 1][0]
            if (a - x) * (b - x) <= 0 and a != b:
                return (i + (a - x) / (a - b)) / (N - 1)
        return 1.0 if sec[0][0] > x else 0.0

    def s_at_y(self, z, y):
        """the position s across the section at z where the surface first rises to height y (from the sill up)"""
        sec = self.section(z)
        for i in range(N - 1):
            a, b = sec[i][1], sec[i + 1][1]
            if (a - y) * (b - y) <= 0 and a != b:
                return (i + (y - a) / (b - a)) / (N - 1)
        return 0.0 if sec[0][1] > y else 1.0

    def normal(self, z, s, side=1):
        """outward normal of the surface at (z, s): across the section, and along it"""
        e = 1e-3
        a = self.point(z, min(1, s + 0.02), side) - self.point(z, max(0, s - 0.02), side)
        b = self.point(min(self.z[-1], z + e), s, side) - self.point(max(self.z[0], z - e), s, side)
        n = np.cross(b, a) * side
        n = n / (np.linalg.norm(n) or 1)
        p = self.point(z, s, side)
        if n @ np.array([p[0], p[1] + 0.2, 0]) < 0:      # away from the inside (a point under the top, on the axis)
            n = -n
        return n


def glass_triangles(shape, E, z0, z1, s0=0.0, s1=1.0, nz=12, ns=10, sides=(1, -1), keep=None):
    """the glass over [z0, z1] x [s0, s1] of the shape, vehicle-local, both halves; flat list of points (run() then
    remakes it with the frames cut out of it: cut_glass). keep='aft' or 'fore': only the glass behind (ahead of) the
    shape's leaning bow (bow(lean=...)), so the canopy's and the windscreen's patches each end on its slanting edge"""
    _GLASS.append((shape, z0, z1, s0, s1, nz, ns, tuple(sides), keep))
    out = []
    zs = np.linspace(z0, z1, nz + 1)
    ss = np.linspace(s0, s1, ns + 1)
    for side in sides:
        for i in range(nz):
            for j in range(ns):
                a, b = shape.point(zs[i], ss[j], side), shape.point(zs[i + 1], ss[j], side)
                c, d = shape.point(zs[i + 1], ss[j + 1], side), shape.point(zs[i], ss[j + 1], side)
                out += [a + E, b + E, c + E, a + E, c + E, d + E]
    return out


def bow(m, shape, E, z, width, depth, rect_out, rect_in, s0=0.0, s1=1.0, proud=0.004, lean=0.0):
    """a transverse frame at station z over the shape from s0 to s1 on both sides (one arch over the top), [width]
    along the aircraft, [depth] deep inward, standing [proud] outside the glass. With [lean] the frame slants: its
    foot on the sill (s = 0) lean/2 ahead of z, its top (s = 1) lean/2 behind (the Flanker's front bow); then the glass
    patches either side of it are glass_triangles(keep='aft' / 'fore')"""
    if lean:
        _FRAMES.append((shape, 'lbow', z, width, lean))
    else:
        _FRAMES.append((shape, 'bow', z, width, s0, s1))
    ss = np.linspace(s0, s1, 24 if lean else 12)
    for side in (1, -1):
        for _ in (0,):
            for j in range(len(ss) - 1):
                q = []
                for s in (ss[j], ss[j + 1]):
                    zs = z + lean * (0.5 - s)
                    for zc in (zs - width / 2, zs + width / 2):
                        p = shape.point(zc, s, side); n = shape.normal(zc, s, side)
                        q.append((p + n * proud + E, p - n * depth + E))
                (a0, a1), (b0, b1), (c0, c1), (d0, d1) = q          # (s_j, z-), (s_j, z+), (s_j+1, z-), (s_j+1, z+)
                m.flat([a0, b0, d0, c0], rect_out, toward=(a0 + d0) / 2 + (a0 - a1))
                m.flat([a1, b1, d1, c1], rect_in, toward=(a1 + d1) / 2 + (a1 - a0))
                m.flat([a0, c0, c1, a1], rect_in, toward=(a0 + c1) / 2 + (a0 - b0))
                m.flat([b0, d0, d1, b1], rect_in, toward=(b0 + d1) / 2 + (b0 - a0))


def member(m, shape, E, s, z0, z1, width, depth, rect_out, rect_in, sides=(1, -1), proud=0.004, steps=10):
    """a longitudinal frame along the shape at position s from z0 to z1, [width] across the surface (s may be a
    function of z, e.g. lambda z: shape.s_at_x(z, 0.12) for a post standing a set width out)"""
    _FRAMES.append((shape, 'member', s, z0, z1, width))
    zs = np.linspace(z0, z1, steps + 1)
    for side in sides:
        for i in range(steps):
            q = []
            for zc in (zs[i], zs[i + 1]):
                sc = s(zc) if callable(s) else s
                p = shape.point(zc, sc, side); n = shape.normal(zc, sc, side)
                t = shape.point(zc, min(1, sc + 0.02), side) - shape.point(zc, max(0, sc - 0.02), side)
                t = t / (np.linalg.norm(t) or 1)
                for o in (-1, 1):
                    q.append((p + t * o * width / 2 + n * proud + E, p + t * o * width / 2 - n * depth + E))
            (a0, a1), (b0, b1), (c0, c1), (d0, d1) = q      # (z_i, -), (z_i, +), (z_i+1, -), (z_i+1, +)
            m.flat([a0, b0, d0, c0], rect_out, toward=(a0 + d0) / 2 + (a0 - a1))
            m.flat([a1, b1, d1, c1], rect_in, toward=(a1 + d1) / 2 + (a1 - a0))
            m.flat([a0, c0, c1, a1], rect_in, toward=(a0 + c1) / 2 + (a0 - b0))
            m.flat([b0, d0, d1, b1], rect_in, toward=(b0 + d1) / 2 + (b0 - a0))


def pane_x(z_top, half_w, radius, slope):
    """the half width at station z of a pane with straight sides [half_w] and top corners of [radius], its apex at
    z_top on a windscreen whose top falls at [slope] (radians)"""
    def x_at(z):
        d = max(0.0, (z - z_top) / math.cos(slope))
        if d >= radius:
            return half_w
        return half_w - radius + math.sqrt(max(0.0, radius ** 2 - (radius - d) ** 2))
    return x_at


class Oval:
    """the outline of an oval flat pane on a windscreen whose top line falls at [slope] (radians): in the pane's plane
    a superellipse (n = 2: an ellipse; larger n: straighter sides, as a rounded window) [half_w] wide, its frame's
    outer edge touching z_top at the apex and z_end at the foot, the frame [width] wide round it. env(z, r): how far
    out from the centre line the outline offset outward by r (r < 0: inward) reaches at station z, 0 off its ends"""

    def __init__(self, z_top, z_end, half_w, width, slope, n=2.0, k=2400, n_bot=None, outline=None, scale=1.0):
        self.c, self.width = math.cos(slope), width
        self.z_top = z_top
        if outline is not None:
            # the glass's own edge traced from photographs (mm in the pane's plane, origin the middle of its flat
            # foot, Y up the pane): its apex [width] + 7 mm down from z_top, the frame's centre line round it
            G = np.array(outline, float) / 1000.0 * scale
            G = np.vstack([G, G[:1]])
            s = np.r_[0, np.cumsum(np.linalg.norm(np.diff(G, axis=0), axis=1))]
            u = np.linspace(0, s[-1], k, endpoint=False)
            G = np.c_[np.interp(u, s, G[:, 0]), np.interp(u, s, G[:, 1])]
            apex = width + 0.007
            gd = np.c_[G[:, 0], apex + (G[:, 1].max() - G[:, 1])]
            dc = gd[:, 1].mean()
            tg = np.roll(gd, -1, 0) - np.roll(gd, 1, 0)
            nrm = np.c_[-tg[:, 1], tg[:, 0]]
            nrm /= np.linalg.norm(nrm, axis=1)[:, None]
            if (nrm * (gd - [0, dc])).sum(1).mean() < 0:
                nrm = -nrm
            self.N = nrm
            self.P = gd + nrm * (width / 2 + 0.006)
            self._env = {}
            return
        d0, d1 = width / 2 + 0.001, (z_end - z_top) / self.c - width / 2 - 0.001   # the frame's centre line's ends
        dc, b = (d0 + d1) / 2, (d1 - d0) / 2
        t = np.linspace(0, 2 * math.pi, k, endpoint=False)
        ct, st = np.cos(t), np.sin(t)
        # (n_bot: the lower half's own exponent, e.g. 8 for an arch with a flat foot and small corners)
        nn = np.where(st < 0, n_bot or n, n)
        self.P = np.c_[half_w * np.sign(ct) * np.abs(ct) ** (2 / nn), dc - b * np.sign(st) * np.abs(st) ** (2 / nn)]
        tg = np.roll(self.P, -1, 0) - np.roll(self.P, 1, 0)
        nrm = np.c_[-tg[:, 1], tg[:, 0]]
        nrm /= np.linalg.norm(nrm, axis=1)[:, None]
        if (nrm * (self.P - [0, dc])).sum(1).mean() < 0:
            nrm = -nrm
        self.N = nrm
        self._env = {}

    def foot_d(self):
        """how far down the plane from z_top the frame's outer edge reaches at the foot"""
        return float((self.P + self.N * self.width / 2)[:, 1].max())

    def d(self, z):
        return (z - self.z_top) / self.c

    def env(self, z, r=0.0):
        if r not in self._env:
            Q = self.P + self.N * r
            grid = np.arange(Q[:, 1].min() - 0.002, Q[:, 1].max() + 0.002, 0.001)
            xs = np.zeros(len(grid))
            for a, b in zip(Q, np.roll(Q, -1, 0)):                 # every segment, at every grid line it spans
                lo, hi = sorted((a[1], b[1]))
                i0, i1 = int(math.ceil((lo - grid[0]) / 0.001)), int(math.floor((hi - grid[0]) / 0.001))
                for i in range(max(0, i0), min(len(grid) - 1, i1) + 1):
                    f = 0.5 if hi - lo < 1e-12 else (grid[i] - a[1]) / (b[1] - a[1])
                    xs[i] = max(xs[i], a[0] + (b[0] - a[0]) * f, 0.0)
            self._env[r] = (grid, xs)
        grid, xs = self._env[r]
        return float(np.interp(self.d(z), grid, xs, left=0.0, right=0.0))


def flat_pane(sections, z_top, z_base, half_w, radius, width, z_end=None, n=2.0, n_bot=None, step=0.025, outline=None,
              scale=1.0):
    """the windscreen sections remade so the armoured front pane's region is one flat plane (a flat slab, as the real
    one): across x level, along z straight from the windscreen's top at z_top to its top at z_base; up to the outer
    edge of the pane's frame the sections lie on that plane, outboard the side panes are bent down to meet it
    (the change fading out to nothing at the sill). Stations every [step] over the pane; the others unchanged.
    z_end given: the pane an oval (Oval, its frame's foot at z_end), else round-topped with straight sides."""
    sh = Shape(sections)
    y0, y1 = sh.point(z_top, 1.0)[1], sh.point(z_base, 1.0)[1]
    slope = math.atan2(y0 - y1, z_base - z_top)
    if z_end is None:
        x_at = pane_x(z_top, half_w, radius, slope)
        reach = lambda z: x_at(z) + width / 2 + 0.025                       # noqa: E731
    else:
        ov = Oval(z_top, z_end, half_w, width, slope, n, n_bot=n_bot, outline=outline, scale=scale)
        z_mid = (z_top + z_end) / 2                 # below its widest the plane stays full width down to the base
        reach = lambda z: max(0.03, ov.env(min(z, z_mid), width / 2 + 0.025))   # noqa: E731
    out = [(z, p) for z, p in sections if z < z_top - 1e-6 or z > z_base + 1e-6]
    for z in list(np.arange(z_top, z_base - 1e-6, step)) + [z_base]:
        sec = sh.section(z)
        yp = y0 + (y1 - y0) * (z - z_top) / (z_base - z_top)
        xf = min(reach(z), sec[0][0] - 0.03)   # (flat a little past the frame's outer edge)
        xs, ys = sec[::-1, 0], sec[::-1, 1]
        dy = yp - float(np.interp(xf, xs, ys))
        x_sill = sec[0][0]
        outer = [(float(x), float(y + dy * ((x - x_sill) / (xf - x_sill)) ** 2)) for x, y in sec if x > xf + 0.005]
        out.append((float(z), outer + [(xf, yp), (xf * 0.66, yp), (xf * 0.33, yp), (0.0, yp)]))
    out.sort(key=lambda t: t[0])
    return out


def arched_pane(m, shape, E, z_top, z_base, half_w, radius, width, depth, rect_out, rect_in, steps=28, flat_to=None):
    """the frame round a windscreen's flat front pane whose top is rounded (the Soviet armoured front panes: the
    MiG-21's round arch, the Su-7 family's tall round-topped pane, the MiG-15/17's rounded corners): straight sides
    [half_w] out from the centre line, the top corners rounded with [radius] (radius == half_w: one round arch), the
    top at z_top, down to z_base. A short bow across the top joins the two sides."""
    p0, p1 = shape.point(z_top, 1.0, 1), shape.point(z_top + 0.1, 1.0, 1)
    slope = math.atan2(p0[1] - p1[1], 0.1)

    def x_at(z):
        d = max(0.0, (z - z_top) / math.cos(slope))
        if d >= radius:
            return half_w
        return half_w - radius + math.sqrt(max(0.0, radius ** 2 - (radius - d) ** 2))
    z_cap = z_top + width * 0.35
    bow(m, shape, E, z_cap, width * 0.7, depth, rect_out, rect_in, s0=shape.s_at_x(z_cap, max(x_at(z_cap), 0.03)))   # (its ends inside the sides' frame)
    member(m, shape, E, lambda z: shape.s_at_x(z, max(x_at(z), 0.02)), z_cap, z_base, width, depth, rect_out, rect_in,
           steps=steps)
    if flat_to is not None:
        # the pane itself: one flat sheet of glass (the windscreen's top line here is the pane's plane, flat_pane),
        # its edge just inside the frame; the windscreen's own glass is cut away inside the outline (cut_glass)
        _FRAMES.append((shape, 'pane', x_at, z_cap, flat_to))
        zs = np.arange(z_cap + width * 0.35 + 0.006, flat_to, 0.01).tolist() + [flat_to]
        rows = []
        for z in zs:
            xin = x_at(z) - width / 2 - 0.006
            if xin > 0.004:
                yp = shape.point(z, 1.0)[1]
                rows.append((np.array([xin, yp, z]), np.array([-xin, yp, z])))
        for (a, b), (c, d) in zip(rows[:-1], rows[1:]):
            _EXTRA.extend([a + E, c + E, d + E, a + E, d + E, b + E])
    return x_at


def cap(m, shape, E, z0, z1, bottom, rect_out, rect_in, steps=24, proud=0.003, depth=0.015):
    """fill's twin from the top down: a solid metal panel over [z0, z1] from bottom(z) (an s) up to the top (s = 1),
    on both sides; e.g. a canopy pane's rounded top corners, bottom(z) dipping toward the bows"""
    fill(m, shape, E, z0, z1, bottom, rect_out, rect_in, steps, proud, depth, upper=True)


def fill(m, shape, E, z0, z1, top, rect_out, rect_in, steps=24, proud=0.003, depth=0.015, upper=False):
    """a solid metal panel in the windscreen's surface where the real one has no glass (the MiG-21's filled lower
    corners and the band under its front pane): over [z0, z1], from the sill (s = 0) up to top(z) (an s), on both
    sides, standing [proud] out of the surface and [depth] in, its top edge closed; the glass is cut away under it.
    upper: from top(z) up to s = 1 instead (cap)"""
    _FRAMES.append((shape, 'cap' if upper else 'fill', top, z0, z1))
    zs = np.linspace(z0, z1, steps + 1)
    for side in (1, -1):
        for i in range(steps):
            za, zb = zs[i], zs[i + 1]
            ta, tb = top(za), top(zb)
            if upper:
                ss_a, ss_b = np.linspace(ta, 1, 7), np.linspace(tb, 1, 7)
            else:
                ss_a, ss_b = np.linspace(0, ta, 7), np.linspace(0, tb, 7)
            for j in range(6):
                q = []
                for z, s in ((za, ss_a[j]), (zb, ss_b[j]), (zb, ss_b[j + 1]), (za, ss_a[j + 1])):
                    p = shape.point(z, s, side); nrm = shape.normal(z, s, side)
                    q.append((p + nrm * proud + E, p - nrm * depth + E))
                o = [a for a, b in q]; i_ = [b for a, b in q]
                c_o = np.mean(o, 0); c_i = np.mean(i_, 0)
                m.flat(o, rect_out, toward=c_o + (c_o - c_i))
                m.flat(i_, rect_in, toward=c_i + (c_i - c_o))
            # the top edge (a cap's lower edge), where the glass begins
            pa, pb = shape.point(za, ta, side), shape.point(zb, tb, side)
            na, nb = shape.normal(za, ta, side), shape.normal(zb, tb, side)
            e = [pa + na * proud + E, pb + nb * proud + E, pb - nb * depth + E, pa - na * depth + E]
            sm = (ta + tb) / 2
            up = shape.point((za + zb) / 2, max(0, sm - 0.05) if upper else min(1, sm + 0.05), side) + E
            m.flat(e, rect_in, toward=up)


def oval_pane(m, shape, E, z_top, z_end, half_w, width, depth, rect_out, rect_in, n=2.0, n_bot=None, outline=None,
              scale=1.0, lip=0.0):
    """the Soviet armoured front pane as it is: one flat sheet whose outline, seen from ahead, is an oval (rounded
    at its foot as well as its top: Oval), in a frame of even [width] all round it; the frame's apex touching the
    windscreen arch at z_top, its foot the base frame at z_end. The shape must be flat_pane'd with the same oval.
    The windscreen's own glass is cut away under the frame and inside it (cut_glass); the pane's glass fills the
    frame's inside, on the plane"""
    p0, p1 = shape.point(z_top, 1.0, 1), shape.point(z_top + 0.1, 1.0, 1)
    slope = math.atan2(p0[1] - p1[1], 0.1)
    ov = Oval(z_top, z_end, half_w, width, slope, n, n_bot=n_bot, outline=outline, scale=scale)
    T = np.array([0, -math.sin(slope), math.cos(slope)])            # down the plane
    Nn = np.array([0, math.cos(slope), math.sin(slope)])            # out of it
    top = p0 + E

    def at(x, d):                                                   # a point on the plane
        return top + np.array([x, 0, 0]) + T * d
    _FRAMES.append((shape, 'pane', lambda z: ov.env(z, width / 2 + 0.006), z_top - 0.002, z_end + 0.002))
    P, Nv = ov.P, ov.N
    k = len(P)
    step = max(1, k // 160)
    idx = list(range(0, k, step)) + [0]
    for i, j in zip(idx[:-1], idx[1:]):
        q = []
        for t in (i, j):
            for o in (-1, 1):
                x, d = P[t] + Nv[t] * o * width / 2
                b = at(x, d)
                q.append((b + Nn * 0.004, b - Nn * depth))
        (a0, a1), (b0, b1), (c0, c1), (d0, d1) = q      # (i, in), (i, out), (j, in), (j, out)
        m.flat([a0, b0, d0, c0], rect_out, toward=(a0 + d0) / 2 + (a0 - a1))
        m.flat([a1, b1, d1, c1], rect_in, toward=(a1 + d1) / 2 + (a1 - a0))
        m.flat([a0, c0, c1, a1], rect_in, toward=(a0 + c1) / 2 + (a0 - b0))
        m.flat([b0, d0, d1, b1], rect_in, toward=(b0 + d1) / 2 + (b0 - a0))
        if lip:                                         # a flange [lip] past the frame's outer edge, over the slit
            q = []                                      # the windscreen's glass stops short of it at (cut_glass)
            for t in (i, j):
                for o in (0.0, lip):
                    x, d = P[t] + Nv[t] * (width / 2 + o)
                    b = at(x, d)
                    q.append((b + Nn * 0.004, b - Nn * 0.01))
            (a0, a1), (b0, b1), (c0, c1), (d0, d1) = q
            m.flat([a0, b0, d0, c0], rect_out, toward=(a0 + d0) / 2 + (a0 - a1))
            m.flat([a1, b1, d1, c1], rect_in, toward=(a1 + d1) / 2 + (a1 - a0))
            m.flat([b0, d0, d1, b1], rect_in, toward=(b0 + d1) / 2 + (b0 - a0))
    # the pane: the glass inside the frame, on the plane, rows across it
    r_in = -(width / 2 + 0.006)
    Q = P + Nv * r_in
    dmin, dmax = Q[:, 1].min(), Q[:, 1].max()
    zs = [z_top + d * ov.c for d in np.linspace(dmin + 1e-4, dmax - 1e-4, 60)]
    rows = []
    for z in zs:
        xin = ov.env(z, r_in)
        d = ov.d(z)
        rows.append((at(xin, d), at(-xin, d)))
    for (a, b), (c, d) in zip(rows[:-1], rows[1:]):
        _EXTRA.extend([a, c, d, a, d, b])
    return ov


def cut_glass(E, pad=0.006):
    """the glass patches asked for, remade with every frame cut out of them: the glass stops at each bow's and
    member's edge (less [pad], as the frames' faces lean out over their footprint where the glass curves) instead of
    running on under it, so no frame face lies a few millimetres over the glass (they flicker through each other)"""
    out = []
    for sh, z0, z1, s0, s1, nz, ns, sides, keep in _GLASS:
        frames = [f for f in _FRAMES if f[0] is sh]
        bows = [(f[2] - f[3] / 2 - pad, f[2] + f[3] / 2 + pad, f[4], f[5]) for f in frames if f[1] == 'bow']
        mems = [f for f in frames if f[1] == 'member']
        panes = [f for f in frames if f[1] == 'pane']
        fills = [f for f in frames if f[1] == 'fill']
        caps = [f for f in frames if f[1] == 'cap']
        lbows = [f for f in frames if f[1] == 'lbow']
        zb = set(np.linspace(z0, z1, nz + 1).tolist())
        for f in lbows:                                # a leaning bow: the glass's slanting edge every 5 mm
            r = abs(f[4]) / 2 + f[3] / 2 + pad
            zb |= set(np.arange(f[2] - r - 0.005, f[2] + r + 0.005, 0.005).tolist())
        for a, b, _, _ in bows:
            zb |= {a, b}
        for f in mems:
            zb |= {f[3] - pad, f[4] + pad}
            if callable(f[2]):                         # a curving frame: the glass's edge follows it every 5 mm
                zb |= set(np.arange(f[3], f[4], 0.005).tolist())
        for f in panes + fills + caps:                 # an oval pane's frame and a metal fill likewise
            zb |= set(np.arange(f[3], f[4], 0.005).tolist()) | {f[3] - pad, f[4] + pad}
        zb = sorted(z for z in zb if z0 - 1e-9 <= z <= z1 + 1e-9)
        for i in range(len(zb) - 1):
            za, zc = zb[i], zb[i + 1]
            if zc - za < 1e-6:
                continue
            zm = (za + zc) / 2
            here = [(bs0, bs1) for a, b, bs0, bs1 in bows if a - 1e-6 <= zm <= b + 1e-6]
            if any(bs0 <= 1e-6 and bs1 >= 1 - 1e-6 for bs0, bs1 in here):
                continue                                   # under a bow: no glass
            live = [f for f in mems if f[3] - pad - 1e-6 <= zm <= f[4] + pad + 1e-6]
            def mid(f, z):
                return f[2](z) if callable(f[2]) else f[2]
            # what the glass stops short of: the members here, and the bows that cross only part of the section
            cuts = [('m', f) for f in live] + [('b', sb) for sb in here]
            cuts += [('p', f) for f in panes if f[3] - 1e-6 <= zm <= f[4] + pad + 1e-6]   # a flat pane's inside
            cuts += [('f', f) for f in fills if f[3] - pad - 1e-6 <= zm <= f[4] + pad + 1e-6]   # a metal fill from the sill up
            cuts += [('c', f) for f in caps if f[3] - pad - 1e-6 <= zm <= f[4] + pad + 1e-6]    # and one from the top down

            def lspan(f, z):                           # the s over which a leaning bow covers station z (and its pad)
                zc_, w_, ln = f[2], f[3], f[4]
                h = w_ / 2 + pad
                s_of = lambda zv: (zc_ + ln / 2 - zv) / ln      # the s at which the bow's centre line is at zv
                a_, b_ = sorted((s_of(z + h), s_of(z - h)))
                return a_, b_
            for f in lbows:
                if abs(zm - f[2]) <= abs(f[4]) / 2 + f[3] / 2 + pad + 0.005:
                    if keep == 'aft':                  # the glass behind the bow: from it up to the top cut away
                        cuts.append(('la', f))
                    elif keep == 'fore':               # the glass ahead of it: from the sill up to it cut away
                        cuts.append(('lf', f))
                    else:
                        cuts.append(('l', f))

            def key(c):
                if c[0] == 'm':
                    return mid(c[1], zm)
                if c[0] == 'b':
                    return (c[1][0] + c[1][1]) / 2
                if c[0] in ('f', 'lf'):
                    return 0.0
                if c[0] == 'c':
                    return c[1][2](min(max(zm, c[1][3]), c[1][4]))
                if c[0] in ('l', 'la'):
                    return min(1.0, max(0.0, sum(lspan(c[1], zm)) / 2))
                return 1.0
            cuts.sort(key=key)
            def bounds(z):
                L = sh.arclen(z) or 1.0
                b, prev = [], s0
                for kind, f in cuts:
                    if kind == 'm':
                        h = (f[5] / 2 + (2 * pad if callable(f[2]) else pad)) / L   # (a curving frame leans further over)
                        lo, hi = mid(f, z) - h, mid(f, z) + h
                    elif kind == 'b':
                        lo, hi = f[0] - pad / L, f[1] + pad / L
                    elif kind == 'f':
                        lo, hi = s0, min(1.0, f[2](min(max(z, f[3]), f[4])) + pad / L)
                    elif kind == 'c':
                        lo, hi = max(0.0, f[2](min(max(z, f[3]), f[4])) - pad / L), 1.0 + pad / L
                    elif kind in ('l', 'la', 'lf'):
                        lo, hi = lspan(f, z)
                        if kind == 'la':
                            hi = s1 + 1.0
                        elif kind == 'lf':
                            lo = s0
                    else:
                        lo, hi = sh.s_at_x(z, max(f[2](z), 0.0)), 1.0 + pad / L
                    b.append((prev, max(prev, min(lo, s1))))
                    prev = min(s1, max(prev, hi))
                b.append((prev, s1))
                return b
            ba, bc = bounds(za), bounds(zc)
            for (la, ha), (lc, hc) in zip(ba, bc):
                w = max(ha - la, hc - lc)
                if w <= 1e-4:
                    continue
                k = max(1, int(round(ns * w / max(1e-6, s1 - s0))))
                for j in range(k):
                    sa0, sa1 = la + (ha - la) * j / k, la + (ha - la) * (j + 1) / k
                    sc0, sc1 = lc + (hc - lc) * j / k, lc + (hc - lc) * (j + 1) / k
                    for side in sides:
                        a, b = sh.point(za, sa0, side), sh.point(zc, sc0, side)
                        c, d = sh.point(zc, sc1, side), sh.point(za, sa1, side)
                        out += [a + E, b + E, c + E, a + E, c + E, d + E]
    return out


def clear_under_glass(ac, glass_pts, E, gap=0.015):
    """the model's own faces lying within [gap] of the new glass, parallel to it and under it (old canopy panes and
    plates): seen through the glass they flicker against it, so they go"""
    G = np.array(glass_pts, float).reshape(-1, 3, 3)
    if not len(G):
        return 0
    n = np.cross(G[:, 1] - G[:, 0], G[:, 2] - G[:, 0])
    ln = np.linalg.norm(n, axis=1)
    G, n = G[ln > 1e-10], n[ln > 1e-10] / ln[ln > 1e-10, None]
    glo, ghi = G.min(1) - gap, G.max(1) + gap
    def overlap2d(a, b):
        def shrink(t):
            c = t.mean(0)
            return c + (t - c) * 0.96
        a, b = shrink(a), shrink(b)
        for tt in (a, b):
            for i in range(len(tt)):
                e = tt[(i + 1) % len(tt)] - tt[i]
                nn = np.array([-e[1], e[0]])
                pa, pb = a @ nn, b @ nn
                if pa.max() <= pb.min() or pb.max() <= pa.min():
                    return False
        return True
    total = 0
    for bone in ac.bones:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].endswith('__cockpit') or bone['name'].endswith('__canopy'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        keep = []
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]] if poly else None
            hit = False
            if V is not None and len(V) >= 3:
                fn = np.cross(V[1] - V[0], V[2] - V[0])
                if np.linalg.norm(fn) > 1e-10:
                    fn = fn / np.linalg.norm(fn)
                    lo, hi = V.min(0), V.max(0)
                    for i in np.where(np.all(glo <= hi, axis=1) & np.all(ghi >= lo, axis=1))[0]:
                        if abs(n[i] @ fn) < math.cos(math.radians(8)) or np.abs((V - G[i][0]) @ n[i]).max() > gap:
                            continue
                        ax = np.argsort(np.abs(n[i]))[:2]
                        if overlap2d(G[i][:, ax], V[:, ax]):
                            hit = True
                            break
            if hit:
                total += 1
            else:
                keep.append(poly)
        pm['polys'] = keep
    return total


def atlas_for(vid):
    """an atlas in the texture's free area, the cockpit's tiles counted as used"""
    at = A.Atlas.__new__(A.Atlas)
    (at.TW, at.TH), rects = FA.free_rects(vid, 256, skip=())
    at.vid = vid
    at.areas = [tuple(int(v) for v in r) for r in rects]
    at.free = list(at.areas)
    at.tiles = []
    return at


def at_canopy(ac, glass_pts, E, near=0.07, below=0.05, old_glass=None):
    """keep(bone, poly) -> True for a face the canopy step may take: within [near] of the new glass, or inside the
    new canopy's volume (under its surface, no more than [below] under the sill), or a piece of the old canopy: near
    the old glass, standing over the new canopy's sill line within its length. Anything else is the model's exterior
    skin (the nose deck ahead of the windscreen, the spine behind the canopy, the fuselage sides) and stays."""
    from matplotlib.path import Path
    G = np.array(glass_pts, float).reshape(-1, 3, 3) - E if len(glass_pts) else np.zeros((0, 3, 3))
    pts = np.concatenate([G.reshape(-1, 3), G.mean(1)]) if len(G) else np.zeros((0, 3))
    shapes = [(sh, z0, z1) for sh, z0, z1, *_ in _GLASS]
    O = (np.asarray(old_glass, float).reshape(-1, 3) - E) if old_glass is not None and len(old_glass) else np.zeros((0, 3))
    def ok(c):
        if len(pts) and np.min(np.linalg.norm(pts - c, axis=1)) < near:
            return True
        if len(O) and np.min(np.linalg.norm(O - c, axis=1)) < 0.10:
            for sh, z0, z1 in shapes:
                if z0 - 0.05 <= c[2] <= z1 + 0.05 and c[1] > sh.section(c[2])[0][1] + 0.03:
                    return True
        for sh, z0, z1 in shapes:
            if z0 <= c[2] <= z1:
                sec = sh.section(c[2])
                if c[1] >= sec[0][1] - below and Path(np.r_[sec, sec[::-1] * np.array([-1, 1])]).contains_point((c[0], c[1])):
                    return True
        return False
    return ok


def install(ac, mesh, source, glass_pts, old_glass):
    """the old canopy frame faces go (strip.find against the old glass), the frames into <piece>__canopy (parent
    hull), the new glass into CanopyGlass"""
    import strip as S
    from PIL import Image
    ac.glass = old_glass
    tub = L.Tub(ac, 0)
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGBA'))
    rm = S.find(ac, tub, canopy=old_glass, tex=tex)[0] if len(old_glass) else {}   # no old glass: clear_skin does it
    E = ac.eye(0)
    ok = at_canopy(ac, glass_pts, E, old_glass=old_glass)
    for b in ac.bones:                                  # only what lies at the new canopy goes; the skin stays
        if b['name'] in rm:
            P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
            polys = b['poly_mesh']['polys']
            rm[b['name']] = {i for i in rm[b['name']] if polys[i] and ok(P[[v[0] for v in polys[i]]].mean(0))}
    n = 0
    for b in ac.bones:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            n += len(rm[b['name']])
    pm = mesh.poly_mesh()
    piece = source.split('__', 1)[0]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__canopy')]
    hull = ac.bone('hull')
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == source) + 1
    ac.bones.insert(at, {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    ac.asset.pop('CanopyGlass', None)
    if glass_pts:
        L.add_glass(ac.asset, [list(map(float, p)) for p in glass_pts])
    return n, len(pm['polys'])


def periscope(m, shape, E, z, out, dark):
    """the rear-view periscope housing on the canopy's top at station z, sitting on the glass"""
    top = shape.point(z, 1.0) + E
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    m.box(top + np.array([0, 0.0, 0]), (X, Y, Z), (0.028, 0.022, 0.06), out)          # its foot sunk 22 mm through the glass
    m.box(top + np.array([0, 0.006, -0.0615]), (X, Y, Z), (0.02, 0.014, 0.002), dark)


def skin(m, shape, E, z0, z1, rect, nz=6, ns=10):
    """a solid (metal) part of the canopy over [z0, z1], both halves, facing out: e.g. a faired rear section"""
    zs, ss = np.linspace(z0, z1, nz + 1), np.linspace(0, 1, ns + 1)
    for side in (1, -1):
        for i in range(nz):
            for j in range(ns):
                q = [shape.point(zs[a], ss[b], side) + E for a, b in ((i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1))]
                c = sum(q) / 4
                m.flat(q, rect, toward=c + shape.normal((zs[i] + zs[i + 1]) / 2, (ss[j] + ss[j + 1]) / 2, side))


def deck(m, shape, E, z0, z1, rect, drop=0.008, reach=0.015, skirt=0.15, front=0.06, x_in=0.0, nz=8):
    """a closed tub under the windscreen over [z0, z1]: a floor just under the sill line reaching [reach] past the sill
    (under the fuselage skin), skirts down from its outer edges and a front wall [front] past z1, all facing in and up:
    what shows through the glass ahead of the panel's coaming is an enclosed deck, not the open nose bay under it.
    With x_in > 0 only the outer bands (|x| > x_in) are floored, beside a panel that stands in the middle."""
    zs = np.linspace(z0, z1 + front, nz + 1)
    edge = []
    for z in zs:
        sx, sy = shape.section(min(z, z1))[0]
        edge.append((z, sx + reach, sy - drop))
    for i in range(nz):
        (za, xa, ya), (zb, xb, yb) = edge[i], edge[i + 1]
        if x_in > 0:
            for s in (1, -1):
                m.flat([E + [s * x_in, ya, za], E + [s * xa, ya, za], E + [s * xb, yb, zb], E + [s * x_in, yb, zb]], rect,
                       normal=[0, 1, 0])
        else:
            m.flat([E + [-xa, ya, za], E + [xa, ya, za], E + [xb, yb, zb], E + [-xb, yb, zb]], rect, normal=[0, 1, 0])
        for s in (1, -1):                                   # the skirts, down from the floor's outer edges
            m.flat([E + [s * xa, ya, za], E + [s * xb, yb, zb], E + [s * xb, yb - skirt, zb], E + [s * xa, ya - skirt, za]],
                   rect, normal=[-s, 0, 0])
    if front <= 0:
        return
    (z, x, y) = edge[-1]                                    # the front wall
    m.flat([E + [-x, y, z], E + [x, y, z], E + [x, y - skirt, z], E + [-x, y - skirt, z]], rect, normal=[0, 0, -1])


def shell(m, shape, E, z0, z1, rect, sight, inset=0.03, nz=10):
    """one smooth surface under a windscreen over [z0, z1]: the glass's own sections set [inset] inside it, from sill to
    sill, its top held under the pilot's sight line over the coaming (y = sight(z)), facing up and out: under the glass
    the nose reads as one homogeneous deck instead of the parts and old skin below it"""
    zs = np.linspace(z0, z1, nz + 1)
    rows = []
    for z in zs:
        row = []
        for x, y in shape.section(z):
            row.append((max(x - inset, 0.0), min(y - inset * 0.5, sight(z))))
        row[0] = (row[0][0], shape.section(z)[0][1] - 0.004)     # (its edge just under the sill line, on the skin)
        rows.append((z, row))
    for (za, ra), (zb, rb) in zip(rows[:-1], rows[1:]):
        for j in range(len(ra) - 1):
            for s in (1, -1):
                q = [E + [s * ra[j][0], ra[j][1], za], E + [s * ra[j + 1][0], ra[j + 1][1], za],
                     E + [s * rb[j + 1][0], rb[j + 1][1], zb], E + [s * rb[j][0], rb[j][1], zb]]
                c = sum(q) / 4
                m.flat(q, rect, toward=c + np.array([s * 0.3, 1.0, 0.0]))


def clear_box(ac, E, lo, hi):
    """the model's own faces (not the new cockpit or canopy) whose centres lie in the eye-relative box lo..hi"""
    n = 0
    lo, hi = np.array(lo, float), np.array(hi, float)
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or b['name'].endswith('__canopy'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
        keep = [p for p in pm['polys'] if not p or not np.all((lo <= P[[v[0] for v in p]].mean(0)) & (P[[v[0] for v in p]].mean(0) <= hi))]
        n += len(pm['polys']) - len(keep)
        pm['polys'] = keep
    return n


def clear_skin(ac, shape, E, z0, z1, depth=0.07, above=0.03, outside=0.0):
    """the model's own faces left against the new canopy (old frame slabs and side plates strip.find missed): every
    face of a model bone whose centre lies within [depth] of the canopy's surface, inside or out, between z0 and z1 and
    more than [above] over the sill (and, with [outside], anything up to that far outside it: an old canopy standing clear of the new one); returns how many went"""
    from matplotlib.path import Path
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or b['name'].endswith('__canopy'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0 - E
        keep = []
        for poly in pm['polys']:
            c = P[[v[0] for v in poly]].mean(0) if poly else None
            if c is None or not z0 <= c[2] <= z1:
                keep.append(poly)
                continue
            sec = shape.section(c[2])
            if c[1] < sec[0][1] + above:
                keep.append(poly)
                continue
            d = np.min(np.hypot(sec[:, 0] - abs(c[0]), sec[:, 1] - c[1]))
            outline = np.r_[sec, sec[::-1] * np.array([-1, 1])]
            inside = Path(outline).contains_point((c[0], c[1]))
            if (inside and d < depth * 1.6) or (not inside and d < max(0.025, outside)):   # outside: only what hugs it
                n += 1
            else:
                keep.append(poly)
        pm['polys'] = keep
    return n


def mirrors(m, shape, E, z, depth, rect_back, rect_glass, at=(1.0, 0.62), size=(0.11, 0.035), lean=0.0):
    """rear-view mirrors on the inside of a bow at station z: one at the top (s = 1) and a pair at s = at[1] on the
    sides, standing a little inside the bow, their glass toward the pilot (on a leaning bow: the bow's [lean])"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    spots = [(at[0], 1)] + [(at[1], s) for s in (1, -1)]
    z0 = z
    for s_, side in spots:
        z = z0 + lean * (0.5 - s_)
        p = shape.point(z, s_, side); n = shape.normal(z, s_, side)
        c = p - n * (depth + 0.016) + E
        t = shape.point(z, min(1, s_ + 0.02), side) - shape.point(z, max(0, s_ - 0.02), side)
        t = t / (np.linalg.norm(t) or 1)
        w = np.cross(n, Z); w = w / (np.linalg.norm(w) or 1)
        m.box(c, (w, np.cross(Z, w), Z), (size[0] / 2, size[1] / 2, 0.006), rect_back)
        m.box(c - Z * 0.0065, (w, np.cross(Z, w), Z), (size[0] / 2 - 0.006, size[1] / 2 - 0.005, 0.0008), rect_glass)


def run(vid, source, build, argv, colour=None, clear=None, boxes=(), centre_line=False):
    """the aircraft file's canopy put in: build(atlas, E, skin) -> (mesh, glass points); the frames in [colour], or
    the spine's own paint; [clear] = (shape, z0, z1[, outside]) also clears the model's leftover faces against that canopy.
    centre_line: E on the aircraft's centre line at the pilot's eye height and station (a side-by-side cockpit, whose
    canopy is symmetric about the centre line, not about either crewman)"""
    ac = L.Aircraft(vid)
    E = ac.eye(0)
    if centre_line:
        E = np.array([0.0, E[1], E[2]])
    old = original_glass(vid)
    atlas = atlas_for(vid)
    _FRAMES.clear(); _GLASS.clear(); _EXTRA.clear()
    comb = ac.asset.pop('CombinerGlass', None) or []   # the cockpit step's HUD / sight glass (handbuilt.combiner)
    _EXTRA.extend(np.array(p, float) for p in comb)
    m, glass = build(atlas, E, colour or frame_colour(ac))
    if _GLASS:
        glass = cut_glass(E)                             # the frames cut out of the glass
    n_old, n_new = install(ac, m, source, glass + _EXTRA, old)
    n_old += clear_under_glass(ac, glass, E)
    if clear:
        n_old += clear_skin(ac, clear[0], E, clear[1], clear[2], outside=clear[3] if len(clear) > 3 else 0.0)
    for lo, hi in boxes:                                 # old frame pieces standing clear of the glass
        n_old += clear_box(ac, E, lo, hi)
    print(f'{vid} canopy: {n_old} old frame faces removed; {n_new} frame faces, {len(glass) // 3} glass triangles')
    if '--dry-run' not in argv:
        H.write(ac, atlas)


def frame_colour(ac, rm_faces=None):
    """the skin colour round the canopy: the commonest texel colour of the spine behind it (outside paint, not the
    cockpit interior)"""
    from PIL import Image
    from collections import Counter
    tex = np.array(Image.open(L.texture_path(ac.vid)).convert('RGB'))
    E = ac.eye(0)
    c = Counter()
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        for poly in pm['polys']:
            if not poly:
                continue
            V = P[[v[0] for v in poly]] - E
            cc = V.mean(0)
            if abs(cc[0]) < 0.25 and -0.1 < cc[1] < 0.5 and -1.8 < cc[2] < -0.6:
                u = U[[v[2] for v in poly]].mean(0)
                px = tex[int(np.clip((1 - u[1]) * tex.shape[0], 0, tex.shape[0] - 1)), int(np.clip(u[0] * tex.shape[1], 0, tex.shape[1] - 1))]
                c[tuple(int(v) // 8 * 8 for v in px)] += 1
    return c.most_common(1)[0][0] if c else (110, 116, 118)
