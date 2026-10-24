#!/usr/bin/env python3
"""F-14C Tomcat front (pilot) and rear (RIO) cockpits, built by hand for the F-14C.mtb airframe: one standalone script.

WHAT IS ASSUMED. The F-14C was a projected variant of the initial F-14B (F401-PW-400 engines) with "advanced multi-mission
avionics" (Wikipedia, Grumman F-14 Tomcat); it was never built and no cockpit drawing exists. This is the Tomcat's own
layout with a digital-era fit, assumed, not documented:
  * the pilot's panel is the F-14 layout: the Vertical Display Indicator (VDI) in the middle under a single-combiner HUD, the
    Horizontal Situation Display (HSD) under the VDI, the engine instrument stack and fuel quantity on the right, the
    armament panel, the landing gear handle and the warning lights on the left, standby dials either side;
  * the RIO's panel is the F-14 layout: the large round Tactical Information Display (TID) in the middle, the Detail Data
    Display (DDD) beside it, the Computer Address Panel (CAP) under them, the radar control panel and the weapons panel on the
    wings, and the RIO's hand controller on the right console. UNVERIFIED: which side of the TID the DDD stands on (here left),
    and the hand controller's side (here right);
  * the displays are CRTs (green phosphor), as the real F-14D's; the F-14C's would have been digital but no source says what;
  * both seats are the airframe's own Martin-Baker Mk 7 groups (kept); the HUD is one combiner glass on one housing with its
    control strip low on the pilot's face; a F-14 pilot HUD has nothing standing beside its glass above its foot.

The airframe (commit 91141440) brought a cockpit of its own: two Martin-Baker Mk 7 seats, a dash with painted panels and
boxy consoles in each tub, loose switch boxes, an AoA/gunsight stand-in and a stick. This script cuts the dash, consoles,
switches, sight stand-in and the block's top boxes out of the two hull pieces (by face runs, each found by its bounding box; the
walls, floors, sill decks, the seats, the blocks between the crew and the bulkhead behind the RIO stay, so the exterior is as it
was) and puts a modelled cockpit in each place.

FRONT (pilot):
  * the main panel in three flat facets (the centre 10 degrees back, the wings turned 22 degrees toward the pilot): the VDI
    (live PFD page) with the HUD housing above, the HSD (live radar page) under it, standby airspeed, altimeter, angle of attack,
    vertical speed, accelerometer, attitude, clock, heading, radar altitude and fuel quantity either side; on the left wing the
    armament panel (master arm, A/A select: AIM-9, AIM-7, AIM-54, gun), the landing gear handle with its lights and the
    brake / hook / sweep / flap strip; on the right wing the caution/warning panel and the engine stack (RPM, FTIT live; fuel
    flow, nozzle painted);
  * the HUD: one unit on the glareshield (housing with its control strip low on the pilot's face) and ONE combiner glass in
    a slim frame centred on the neutral eye line; the AoA indexer on the left glareshield;
  * consoles (the two throttles on the left), liners, floor, kick panel, rudder pedals, the centre stick in its own bone.
REAR (RIO):
  * the panel in three flat facets: the round TID (live radar page, round mask over the square screen) and the DDD (live
    stores page), the CAP and an ECM strip under them, standby airspeed / altimeter / clock, the radar control panel and RWR
    scope on the left wing, the weapons panel and ICS panel on the right wing;
  * the hand controller on the right console, the left console's switch panels, rudder-pedal-less footwell (the RIO has no
    flight controls), a coaming over the block behind the pilot.

Everything the game draws live goes through the normal entries only: CockpitGauges (the dials, the engine stack, the caution
panel), FlightDisplays (VDI, HSD, TID, DDD) and HeadUpDisplay (the pilot's seat 0). All other faces are static paint.

The seats and eyes are the airframe's (pilot [0, 2.8875, 4.5], RIO [0, 3.125, 2.91875]); the vehicle data is not touched.
Paint lives in a free area of textures/entity/f_14c.png (x 2048.., rows 8..1296: no UV of the model or its native fallback
reaches it); the burnt copy goes to entity_dead.

Order: restore the airframe's files (work/elite_expansion_2/f14c/run_f14c_cockpit.ps1, from f14c/base), run canopy_f_14c.py, then
this script (it appends the HUD combiner glass to the canopy's CanopyGlass). Running it again rebuilds the same result.
Frames: everything below is eye-relative blocks of its station (x left, y up, z forward); the mesh is written in model pixels
(x, y, -z) * 16.

Usage: python tools/cockpit_models/f_14c.py [--dry-run]"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_14c.geo.json')
FALLBACK = os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback/f_14c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_14c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_14c.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_14c.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/f_14c.png')

BONE_F = 'wreck_fuselage_0__cockpit'          # the pilot's station breaks away with the forward fuselage
BONE_R = 'wreck_fuselage_1__cockpit'          # the RIO's stays with the middle one
THR = []                                 # the throttle levers (throttle_lib), one bone each
STICK_BONES = ('control_stick_roll', 'control_stick_pitch', 'wreck_fuselage_0__control_stick_pitch')
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
HULL0, HULL1 = 'wreck_fuselage_0__hull', 'wreck_fuselage_1__hull'
# free texture area (x, y, w, h): the model's UVs lie in rows 1301.. (v <= 0.6824), the native fallback's box UVs in the top
# left corner (x < 330, y < 160), and the old content of the top rows stops at x 2046
ATLAS = [(2048, 8, 2048, 1288)]
TW, TH = 4096, 4096

EYE_F = (0.0, 2.8875, 4.5)              # the pilot's eye (vehicle data)
EYE_R = (0.0, 3.125, 2.91875)           # the RIO's eye

FLOOR_ABS = 2.103                       # the hull's floor top is 2.100
CONSOLE_ABS = 2.42
SILL_ABS = 2.750                        # the liners' top: just under the sill decks' top (2.76)
CON_IN, CON_OUT, WALL_X = 0.25, 0.475, 0.420     # the sill decks' inner face stands at x 0.43..0.44 above y 2.49

# The airframe's own dash, consoles, switch boxes, sight stand-in and the block's top boxes, by the faces' index in the two hull
# bones' lists: (first face, last face, bounding box lo, hi in blocks). The box is checked before a run is cut, so a regenerated
# airframe whose lists moved is never cut blindly (the canopy script's cut shifts the indices; the runs are found near them),
# and a second run (the runs already gone) cuts nothing more. Kept: the seats (hull 0 faces 204-341, hull 1 624-965 and
# 971-1450), the dash's lower and front filler 360-389, the block between the crews' lower boxes 1498-1515, the bulkhead behind the RIO
# (1634-1644), all skin and sill decks.
OLD_INTERIOR = {
    HULL0: [(342, 359, (-0.525, 2.362, 5.062), (0.525, 2.975, 5.670)),    # the pilot's dash boxes (the lower blocks 360-377 stay)
            (390, 605, (-0.503, 2.100, 4.056), (0.481, 3.128, 5.346))],   # consoles, switch boxes, sight stand-in
    HULL1: [(1462, 1497, (-0.525, 2.494, 3.487), (0.525, 3.281, 4.056)),  # the block between the crews (top) and its gunsight bits
            (1516, 1633, (-0.481, 2.100, 2.481), (0.481, 2.822, 4.561)),  # the RIO's consoles, the pilot's rear switch boxes
            (1645, 1650, (-0.481, 2.669, 1.606), (0.481, 2.887, 2.218))],  # the shelf behind the RIO
}


def _font_files():
    out = [r'C:\Windows\Fonts\arialbd.ttf', r'C:\Windows\Fonts\arial.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
           '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf']
    try:
        import matplotlib
        d = os.path.join(os.path.dirname(matplotlib.__file__), 'mpl-data', 'fonts', 'ttf')
        out += [os.path.join(d, 'DejaVuSans-Bold.ttf'), os.path.join(d, 'DejaVuSans.ttf')]
    except ImportError:
        pass
    return out


FONT_FILES = _font_files()

# ---------------------------------------------------------------- colours (the Tomcat's dark grey-black panels)

PANEL = (94, 98, 100)  # FS 36231 grey (a touch lighter, as the game's light shows it); dial faces stay black
PANEL_D = (68, 71, 73)
BLACK = (18, 19, 20)
DGREY = (38, 40, 42)
MGREY = (84, 88, 90)
LGREY = (150, 154, 156)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (60, 62, 64)
CONSOLE = (110, 114, 116)
WHITE = (226, 228, 220)
GREEN_LAMP = (60, 150, 70)
AMBER = (214, 140, 30)
GREEN_TXT = (80, 220, 100)
AMBER_TXT = (255, 168, 40)


# ---------------------------------------------------------------- texture atlas

class Atlas:
    """Shelf-packed rectangles in the free texture area. paint(w, h, fn) -> rect; fn(draw, img) paints an RGBA tile
    at SS x resolution, downsampled for clean edges."""
    SS = 4

    def __init__(self):
        self.free = [tuple(a) for a in ATLAS]
        self.tiles = []

    def alloc(self, w, h):
        best = None
        for i, (x, y, fw, fh) in enumerate(self.free):
            if w <= fw and h <= fh:
                score = min(fw - w, fh - h)
                if best is None or score < best[0]:
                    best = (score, i)
        if best is None:
            raise SystemExit(f'atlas full: no room for {w}x{h}')
        x, y, fw, fh = self.free.pop(best[1])
        if fw - w < fh - h:
            self.free += [(x + w, y, fw - w, h), (x, y + h, fw, fh - h)]
        else:
            self.free += [(x + w, y, fw - w, fh), (x, y + h, w, fh - h)]
        self.free = [r for r in self.free if r[2] > 0 and r[3] > 0]
        return (x, y, w, h)

    def used(self):
        return sum(r[2] * r[3] for r, _ in self.tiles)

    def paint(self, w, h, fn, bg=(0, 0, 0, 255)):
        r = self.alloc(w, h)
        img = Image.new('RGBA', (w * self.SS, h * self.SS), bg)
        fn(ImageDraw.Draw(img), img)
        self.tiles.append((r, img.resize((w, h), Image.LANCZOS)))
        return r

    def swatch(self, colour):
        return self.paint(8, 8, lambda d, im: None, bg=tuple(colour) + (255,))

    def write(self, path, dead=False):
        base = Image.open(path).convert('RGBA')
        for x, y, w, h in ATLAS:
            base.paste((0, 0, 0, 0), (x, y, x + w, y + h))
        for (rx, ry, rw, rh), img in self.tiles:
            if dead:
                a = np.asarray(img).astype(np.float32)
                g = a[..., :3].mean(-1, keepdims=True)
                a[..., :3] = (a[..., :3] * 0.25 + g * 0.15) * 0.9          # soot: dark, mostly grey
                img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
            base.paste(img, (rx, ry))
        base.save(path, optimize=True)


def uv_of(rect, s, t):
    """normalized texture uv of (s, t) in a rect: s right, t up, both 0..1, kept half a texel inside"""
    x, y, w, h = rect
    s = 0.5 / w + s * (1 - 1.0 / w); t = 0.5 / h + t * (1 - 1.0 / h)
    return [(x + s * w) / TW, 1.0 - (y + (1 - t) * h) / TH]


def uv_flat(rect):
    return uv_of(rect, 0.5, 0.5)


# ---------------------------------------------------------------- mesh (eye-relative in, vehicle-local out)

class Mesh:
    def __init__(self, eye=(0, 0, 0)):
        self.faces = []                      # (points k x 3 vehicle-local, uvs k x 2, normal 3)
        self.eye = np.array(eye, float)      # the station being built: its eye, added to every point

    def face(self, pts, uvs, toward=None, normal=None):
        P = np.array(pts, float) + self.eye
        n = np.zeros(3)
        for i in range(len(P)):              # Newell normal
            a, b = P[i], P[(i + 1) % len(P)]
            n += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        want = None
        if normal is not None:
            want = np.array(normal, float)
        elif toward is not None:
            want = np.array(toward, float) + self.eye - P.mean(0)
        uvs = [list(u) for u in uvs]
        if want is not None and n @ want < 0:
            P = P[::-1]; uvs = uvs[::-1]; n = -n
        if len(P) > 4:                       # fan into triangles
            for k in range(1, len(P) - 1):
                self.faces.append((P[[0, k, k + 1]], [uvs[0], uvs[k], uvs[k + 1]], n))
        else:
            self.faces.append((P, uvs, n))

    def quad_tex(self, corners, rect, toward=None, normal=None):
        """corners: bottom-left, bottom-right, top-right, top-left as seen from the front"""
        self.face(corners, [uv_of(rect, 0, 0), uv_of(rect, 1, 0), uv_of(rect, 1, 1), uv_of(rect, 0, 1)],
                  toward=toward, normal=normal)

    def flat(self, pts, rect, toward=None, normal=None):
        u = uv_flat(rect)
        self.face(pts, [u] * len(pts), toward=toward, normal=normal)

    def box(self, centre, axes, half, rect, front=None):
        """oriented box; axes = 3 unit vectors, half sizes; front = (axis index, sign, rect, right, up): a textured face"""
        c = np.array(centre, float); A = [np.array(a, float) for a in axes]
        for k in range(3):
            for s in (-1, 1):
                i, j = [m for m in range(3) if m != k]
                fc = c + A[k] * half[k] * s
                pts = [fc - A[i] * half[i] - A[j] * half[j], fc + A[i] * half[i] - A[j] * half[j],
                       fc + A[i] * half[i] + A[j] * half[j], fc - A[i] * half[i] + A[j] * half[j]]
                if front and front[0] == k and front[1] == s:
                    _, _, r, right, up = front
                    R = np.array(right, float); U = np.array(up, float)
                    pts = [fc - R * half_along(A, half, R) - U * half_along(A, half, U),
                           fc + R * half_along(A, half, R) - U * half_along(A, half, U),
                           fc + R * half_along(A, half, R) + U * half_along(A, half, U),
                           fc - R * half_along(A, half, R) + U * half_along(A, half, U)]
                    self.quad_tex(pts, r, normal=A[k] * s)
                else:
                    self.flat(pts, rect, normal=A[k] * s)

    def ring(self, centre, normal, up, r0, r1, proud, rect, seg=20, back=0.0):
        c, n, u, rr = frame(centre, normal, up)
        for k in range(seg):
            a0 = 2 * math.pi * k / seg; a1 = 2 * math.pi * (k + 1) / seg
            d0 = rr * math.cos(a0) + u * math.sin(a0); d1 = rr * math.cos(a1) + u * math.sin(a1)
            f = c + n * proud
            self.flat([f + d0 * r0, f + d0 * r1, f + d1 * r1, f + d1 * r0], rect, normal=n)
            self.flat([f + d0 * r1, c + d0 * r1 - n * back, c + d1 * r1 - n * back, f + d1 * r1], rect,
                      normal=(d0 + d1) / 2)
            self.flat([f + d0 * r0, c + d0 * r0, c + d1 * r0, f + d1 * r0], rect, normal=-(d0 + d1) / 2)

    def rect_tex(self, centre, normal, up, w, h, rect):
        c, n, u, rr = frame(centre, normal, up)
        self.quad_tex([c - rr * w / 2 - u * h / 2, c + rr * w / 2 - u * h / 2, c + rr * w / 2 + u * h / 2,
                       c - rr * w / 2 + u * h / 2], rect, normal=n)

    def frame_rect(self, centre, normal, up, w, h, t, proud, rect):
        """rectangular bezel (four bars) around a w x h opening"""
        c, n, u, rr = frame(centre, normal, up)
        for (cx, cy, hw, hh) in ((0, h / 2 + t / 2, w / 2 + t, t / 2), (0, -h / 2 - t / 2, w / 2 + t, t / 2),
                                 (w / 2 + t / 2, 0, t / 2, h / 2), (-w / 2 - t / 2, 0, t / 2, h / 2)):
            self.box(c + rr * cx + u * cy + n * proud / 2, (rr, u, n), (hw, hh, proud / 2), rect)

    def poly_mesh(self):
        P, N, U, polys = [], [], [], []
        for pts, uvs, n in self.faces:
            px = [[float(q[0]) * 16, float(q[1]) * 16, -float(q[2]) * 16] for q in pts]
            npx = np.array([n[0], n[1], -n[2]])
            a, b, c = (np.array(q) for q in px[:3])
            # The mesh loader mirrors X and takes (p1 - p0) x (p2 - p0) of the mirrored points as the face normal, so
            # in file pixels a face winds clockwise seen from its front (the hull's own faces do the same); the stored
            # normal follows the file-pixel winding, as the hull's does.
            if np.cross(b - a, c - a) @ npx > 0:
                px = px[::-1]; uvs = uvs[::-1]
            ni = len(N); N.append([round(float(x), 5) + 0.0 for x in -npx])
            poly = []
            for p, uv in zip(px, uvs):
                poly.append([len(P), ni, len(U)])
                P.append([round(x, 4) + 0.0 for x in p]); U.append([round(uv[0], 6), round(uv[1], 6)])
            polys.append(poly)
        return {'normalized_uvs': True, 'positions': P, 'normals': N, 'uvs': U, 'polys': polys}


def half_along(A, half, d):
    return sum(abs(float(a @ d)) * h for a, h in zip(A, half))


def frame(centre, normal, up):
    """(c, n, u, r): r = the right-hand direction seen from in front of the face (looking along -n)"""
    n = np.array(normal, float); n /= np.linalg.norm(n)
    u = np.array(up, float); u = u - n * (u @ n); u /= np.linalg.norm(u)
    r = np.cross(u, n)
    return np.array(centre, float), n, u, r


def unit(v):
    v = np.array(v, float); return v / np.linalg.norm(v)


def r5(v):
    return [round(float(x), 5) + 0.0 for x in v]


X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


# ---------------------------------------------------------------- the panels

class Panel:
    """A three-facet instrument panel (centre leaning back, wings turned toward the crew), eye-relative metres."""

    def __init__(self, lean_deg, p0, hinge, wing_deg, wall_end, top_c, top_k, top_h, top_w, bot_c, bot_w):
        self.lean = math.radians(lean_deg)
        self.U_C = np.array([0, math.cos(self.lean), math.sin(self.lean)])
        self.N_C = np.array([0, math.sin(self.lean), -math.cos(self.lean)])
        self.R_C = np.array([-1.0, 0, 0])             # the crew's right
        self.P0 = np.array(p0, float)
        self.hinge = hinge
        self.wing = math.radians(wing_deg)
        self.wall_end = wall_end
        self.wing_len = (wall_end - hinge) / math.cos(self.wing)
        self.top_c, self.top_k, self.top_h, self.top_w = top_c, top_k, top_h, top_w
        self.bot_c, self.bot_w = bot_c, bot_w
        self.centre, self.right, self.left = Facet(self, 0), Facet(self, 1), Facet(self, -1)

    def front_z(self, x, y):
        """z of the panel's face (whichever facet stands there) at |x|, height y"""
        ax_ = abs(x)
        if ax_ <= self.hinge:
            return self.centre.at(0, y)[2]
        wing = self.left if x > 0 else self.right
        return wing.at((ax_ - self.hinge) / math.cos(self.wing), y)[2]


class Facet:
    def __init__(self, panel, side):
        self.p = panel
        self.side = side                    # 0 centre, +1 right wing, -1 left wing
        if side == 0:
            self.R, self.N = panel.R_C, panel.N_C
        else:
            self.R = panel.R_C * side * math.cos(panel.wing) + panel.N_C * math.sin(panel.wing)
            self.N = panel.N_C * math.cos(panel.wing) - panel.R_C * side * math.sin(panel.wing)
        self.U = panel.U_C

    def at(self, a, y, lift=0.0):
        """panel point: a = metres right of the centre line (centre) or along the wing from the hinge (wings)"""
        p = self.p
        v = (y - p.P0[1]) / math.cos(p.lean)
        if self.side == 0:
            q = p.P0 + p.R_C * a + p.U_C * v
        else:
            q = p.P0 + p.R_C * (self.side * p.hinge) + p.U_C * v + self.R * a
        return q + self.N * lift

    def top(self, a):
        p = self.p
        if self.side == 0:
            return p.top_c + (p.top_h - p.top_c) * max(0.0, abs(a) - p.top_k) / (p.hinge - p.top_k)
        return p.top_h + (p.top_w - p.top_h) * a / p.wing_len

    def bottom(self):
        return self.p.bot_c if self.side == 0 else self.p.bot_w

    def extent(self):
        return (-self.p.hinge, self.p.hinge) if self.side == 0 else (0.0, self.p.wing_len)

    def knots(self):
        p = self.p
        if self.side == 0:
            return [-p.hinge, -p.top_k, p.top_k, p.hinge]
        return [0.0, p.wing_len]


# The pilot's panel: its face stands at z 4.954 (bottom, y 2.23) to 5.06 (top, y 2.83), the arch at 5.07 over it; the wings stand
# on the consoles (top 2.42). The RIO's: its face at z 3.44 (the block behind it has its face at 3.49), top 3.095 (the eye 3.125).
# (the wings end at x 0.385 / 0.415: the airframe's own skin beside the pilot's panel is only 0.40 out at the sill)
PANEL_F = Panel(10, (0.0, -0.40, 0.50), 0.245, 22, 0.385, -0.055, 0.10, -0.100, -0.170, -0.660, CONSOLE_ABS - EYE_F[1])
PANEL_R = Panel(6, (0.0, -0.40, 0.52), 0.285, 20, 0.415, -0.030, 0.12, -0.060, -0.120, -0.595, CONSOLE_ABS - EYE_R[1])

GAUGE_LIFT = 0.004
DISPLAY_LIFT = 0.003
RIM = 0.16

# Instrument layouts: name, facet, a, y (from the eye), spec, description.
# Specs: ('game', Kind, dia) drawn by the game; ('display', Kind, w, style[, 'round']); ('paint_disc', key, dia);
# ('paint_rect', w, h); ('annunciator', w, h)
F_C, F_R, F_L = PANEL_F.centre, PANEL_F.right, PANEL_F.left
LAYOUT_F = [
    ('vdi',     F_C,  0.000, -0.200, ('display', 'PFD', 0.150, 'CRT'), 'vertical display indicator (VDI)'),
    ('ias',     F_C, -0.175, -0.165, ('game', 'AIRSPEED', 0.062), 'airspeed / Mach indicator'),
    ('alt',     F_C,  0.175, -0.165, ('game', 'ALTIMETER', 0.062), 'altimeter'),
    ('aoa',     F_C, -0.175, -0.245, ('game', 'AOA', 0.050), 'angle of attack indicator'),
    ('vvi',     F_C,  0.175, -0.245, ('game', 'VVI', 0.050), 'vertical velocity indicator'),
    ('hsd',     F_C,  0.000, -0.395, ('display', 'RADAR', 0.135, 'CRT'), 'horizontal situation display (HSD)'),
    ('gmeter',  F_C, -0.175, -0.320, ('game', 'G_METER', 0.046), 'accelerometer'),
    ('adi',     F_C, -0.175, -0.395, ('game', 'ATTITUDE', 0.056), 'standby attitude indicator'),
    ('clock',   F_C, -0.175, -0.470, ('game', 'CLOCK', 0.046), 'clock'),
    ('hdg',     F_C,  0.175, -0.330, ('game', 'HEADING', 0.056), 'standby compass'),
    ('ralt',    F_C,  0.175, -0.405, ('game', 'RADAR_ALT', 0.046), 'radar altimeter'),
    ('fuelq',   F_C,  0.175, -0.480, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
    ('ecm',     F_C,  0.000, -0.575, ('paint_rect', 0.340, 0.070), 'ICS / DECM control strip'),
    ('arm',     F_L,  0.075, -0.190, ('paint_rect', 0.110, 0.070), 'armament panel (master arm, A/A select)'),
    ('gear',    F_L,  0.075, -0.290, ('paint_rect', 0.110, 0.100), 'landing gear handle and lights'),
    ('util',    F_L,  0.075, -0.380, ('paint_rect', 0.110, 0.050), 'brake / hook / sweep / flap strip'),
    ('warning', F_R,  0.075, -0.185, ('annunciator', 0.115, 0.065), 'warning and caution lights'),
    ('rpm_l',   F_R,  0.105, -0.255, ('game', 'ENGINE_RPM', 0.034), 'left engine rpm'),
    ('rpm_r',   F_R,  0.045, -0.255, ('game', 'ENGINE_RPM', 0.034), 'right engine rpm'),
    ('egt_l',   F_R,  0.105, -0.305, ('game', 'EGT', 0.034), 'left turbine temperature'),
    ('egt_r',   F_R,  0.045, -0.305, ('game', 'EGT', 0.034), 'right turbine temperature'),
    ('ff_l',    F_R,  0.105, -0.355, ('paint_disc', 'FF', 0.034), 'left fuel flow'),
    ('ff_r',    F_R,  0.045, -0.355, ('paint_disc', 'FF', 0.034), 'right fuel flow'),
    ('noz_l',   F_R,  0.105, -0.405, ('paint_disc', 'NOZ', 0.034), 'left nozzle position'),
    ('noz_r',   F_R,  0.045, -0.405, ('paint_disc', 'NOZ', 0.034), 'right nozzle position'),
]
# painted (not live) dials: key -> title, lo, hi, step, label step, needle value, labels
STATIC_DIALS = {
    'FF': ('FF', 0, 10, 1, 2, 4.0, ['0', '2', '4', '6', '8', '10']),
    'NOZ': ('NOZ', 0, 10, 1, 2, 3.0, ['0', '2', '4', '6', '8', '10']),
    'OIL': ('OIL', 0, 100, 10, 20, 62.0, ['0', '20', '40', '60', '80', '100']),
    'HYD': ('HYD', 0, 4000, 500, 1000, 3000.0, ['0', '1', '2', '3', '4']),
    'CABIN': ('CAB', 0, 50, 5, 10, 12.0, ['0', '10', '20', '30', '40', '50']),
    'FUELP': ('FUEL P', 0, 40, 5, 10, 22.0, ['0', '10', '20', '30', '40']),
    'LOXQ': ('LOX', 0, 10, 1, 2, 6.0, ['0', '2', '4', '6', '8', '10']),
    'OXYP': ('OXY', 0, 500, 50, 100, 380.0, ['0', '1', '2', '3', '4', '5']),
    'BRAKE': ('BRAKE', 0, 3000, 500, 1000, 2000.0, ['0', '1', '2', '3']),
    'VOLT': ('VOLT', 0, 30, 5, 10, 28.0, ['0', '10', '20', '30']),
    'AMP': ('AMP', 0, 300, 50, 100, 140.0, ['0', '1', '2', '3']),
}
DIAL_POOL = ['CABIN', 'FUELP', 'LOXQ', 'OXYP', 'BRAKE', 'VOLT', 'AMP', 'HYD', 'OIL', 'FF', 'NOZ']


def _dials(prefix, facet, pts, dia, k0=0):
    out = []
    for k, (a, y) in enumerate(pts):
        key = DIAL_POOL[(k0 + k) % len(DIAL_POOL)]
        out.append((f'{prefix}{k}', facet, a, y, ('paint_disc', key, dia), key.lower()))
    return out


# the pilot's panel: the VDI (a large CRT) top centre, the HSD under it, and the gauges packed round both
_v = [e for e in LAYOUT_F if e[0] in ('vdi', 'hsd')]
LAYOUT_F[:] = [e for e in LAYOUT_F if e[0] not in ('vdi', 'hsd')]
LAYOUT_F += [('vdi', F_C, 0.000, -0.190, ('display', 'PFD', 0.175, 'CRT'), 'vertical display indicator (VDI)'),
             ('hsd', F_C, 0.000, -0.405, ('display', 'RADAR', 0.135, 'CRT'), 'horizontal situation display (HSD)')]
LAYOUT_F += _dials('fa', F_C, [(s * 0.222, y) for s in (-1, 1) for y in (-0.125, -0.165, -0.205, -0.245, -0.285, -0.325, -0.365, -0.405, -0.445, -0.485, -0.525)], 0.026) \
    + _dials('fb', F_C, [(s * 0.123, y) for s in (-1, 1) for y in (-0.345, -0.385, -0.425, -0.465)], 0.024, 3)

R_C, R_R, R_L = PANEL_R.centre, PANEL_R.right, PANEL_R.left
LAYOUT_R = [
    ('tid',     R_C,  0.035, -0.200, ('display', 'RADAR', 0.200, 'CRT', 'round'), 'tactical information display (TID)'),
    ('ddd',     R_C, -0.210, -0.190, ('display', 'STORES', 0.100, 'CRT'), 'detail data display (DDD)'),
    ('r_ias',   R_C,  0.235, -0.110, ('game', 'AIRSPEED', 0.050), 'standby airspeed'),
    ('r_alt',   R_C,  0.235, -0.190, ('game', 'ALTIMETER', 0.050), 'standby altimeter'),
    ('r_clock', R_C,  0.235, -0.270, ('game', 'CLOCK', 0.050), 'clock'),
    ('cap',     R_C,  0.000, -0.445, ('paint_rect', 0.300, 0.090), 'computer address panel (CAP)'),
    ('r_ecm',   R_C,  0.000, -0.550, ('paint_rect', 0.400, 0.050), 'ECM / ICS strip'),
    ('rcp',     R_L,  0.070, -0.235, ('paint_rect', 0.110, 0.170), 'radar control panel'),
    ('rwr',     R_L,  0.070, -0.425, ('game', 'RWR', 0.070), 'radar warning scope'),
    ('r_arm',   R_R,  0.070, -0.235, ('paint_rect', 0.110, 0.170), 'weapons panel (Phoenix, Sparrow, Sidewinder, gun)'),
    ('r_ics',   R_R,  0.070, -0.425, ('paint_rect', 0.110, 0.080), 'ICS / TACAN panel'),
]
LAMPS = [{"Text": "MASTER|CAUTION", "Condition": "MASTER_CAUTION", "Colour": "amber"},
         {"Text": "GEAR|DOWN", "Condition": "GEAR_DOWN", "Colour": "green"},
         {"Text": "GEAR|UNSAFE", "Condition": "GEAR_TRANSIT", "Colour": "red"},
         {"Text": "FUEL|LOW", "Condition": "LOW_FUEL", "Colour": "amber"},
         {"Text": "FIRE", "Condition": "FIRE", "Colour": "red"},
         {"Text": "ENG|OUT", "Condition": "ENGINE_OUT", "Colour": "red"},
         {"Text": "HYD|PRESS", "Condition": "NONE", "Colour": "amber"},
         {"Text": "GEN", "Condition": "NONE", "Colour": "amber"},
         {"Text": "STALL", "Condition": "STALL", "Colour": "red"},
         {"Text": "OVER G", "Condition": "OVER_G", "Colour": "amber"},
         {"Text": "RWR|LOCK", "Condition": "RWR_LOCK", "Colour": "amber"},
         {"Text": "LAUNCH", "Condition": "MISSILE_LAUNCH", "Colour": "red"}]
LAMP_COLUMNS = 6


# ---------------------------------------------------------------- painting helpers

def font(px, bold=False):
    for f in (FONT_FILES if bold else FONT_FILES[1:2] + FONT_FILES[0:1] + FONT_FILES[2:]):
        try:
            return ImageFont.truetype(f, max(4, int(px)))
        except OSError:
            continue
    return ImageFont.load_default()


def noisy(d, im, colour, amount=6, seed=1):
    rng = np.random.default_rng(seed)
    a = np.zeros((im.height, im.width, 4), np.float32)
    a[..., :3] = colour; a[..., 3] = 255
    n = rng.normal(0, amount, (im.height // 8 + 1, im.width // 8 + 1))
    n = np.kron(n, np.ones((8, 8)))[:im.height, :im.width]
    a[..., :3] += n[..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)), (0, 0))


def text(d, xy, s, px, fill=WHITE, anchor='mm', bold=False):
    d.text(xy, s, font=font(px, bold), fill=fill, anchor=anchor)


def dial_face(d, im, title, lo, hi, step, label_step, value, start=-135, sweep=270, labels=None, red=None):
    S = im.width; c = S / 2; R = S / 2 - S * 0.02
    d.ellipse([c - R, c - R, c + R, c + R], fill=(14, 15, 16, 255))

    def ang(v):
        return math.radians(start + (v - lo) / (hi - lo) * sweep)
    if red:
        for v0 in np.arange(red[0], red[1], (hi - lo) / 200):
            a = ang(v0)
            d.line([c + (R * .80) * math.sin(a), c - (R * .80) * math.cos(a), c + R * .93 * math.sin(a),
                    c - R * .93 * math.cos(a)], fill=RED + (255,), width=int(S * .02))
    v = lo; k = 0
    while v <= hi + 1e-9:
        a = ang(v)
        major = abs((v - lo) / label_step - round((v - lo) / label_step)) < 1e-6
        r0 = R * (0.78 if major else 0.86)
        d.line([c + r0 * math.sin(a), c - r0 * math.cos(a), c + R * .95 * math.sin(a), c - R * .95 * math.cos(a)],
               fill=WHITE + (255,), width=int(S * (0.022 if major else 0.011)))
        if major:
            lab = labels[k] if labels else (str(int(v)) if float(v).is_integer() else f'{v:g}')
            k += 1
            text(d, (c + R * .60 * math.sin(a), c - R * .60 * math.cos(a)), lab, S * .11)
        v += step
    if title:
        text(d, (c, c + R * .36), title, S * .085)
    a = ang(value)
    d.line([c - .15 * R * math.sin(a), c + .15 * R * math.cos(a), c + .80 * R * math.sin(a), c - .80 * R * math.cos(a)],
           fill=(240, 240, 232, 255), width=int(S * .045))
    d.ellipse([c - S * .05, c - S * .05, c + S * .05, c + S * .05], fill=(60, 60, 60, 255))


def panel_paint(facet, rect_px, layout, labels=True):
    """the facet face: dark grey, seams, screws, the dark recesses behind every instrument and their small legends"""
    a0, a1 = facet.extent()
    W, H = rect_px
    y_bot = facet.bottom(); y_top = max(facet.top(a) for a in facet.knots())
    SS = Atlas.SS
    lean = facet.p.lean

    def to_px(a, y):
        s = (a - a0) / (a1 - a0)
        if facet.side == -1:
            s = 1 - s
        return (s * W * SS, (1 - (y - y_bot) / (y_top - y_bot)) * H * SS)

    def fn(d, im):
        noisy(d, im, PANEL, 3, seed=3 + facet.side)
        for (a, y) in [(a0 + 0.010, y_bot + 0.010), (a1 - 0.010, y_bot + 0.010), (a0 + 0.010, facet.top(a0 + 0.010) - 0.014),
                       (a1 - 0.010, facet.top(a1 - 0.010) - 0.014)]:
            x, yy = to_px(a, y); r = SS * 1.8
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(20, 20, 20, 255))
        for name, f, a, y, spec, label in layout:
            if f is not facet:
                continue
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(10, 12, 12, 255))
                if labels:
                    text(d, (x, yy + r + SS * 3.0), label.split(' ')[0].upper()[:9], SS * 4.2, fill=(190, 190, 184, 255))
            elif kind in ('paint_rect', 'display', 'annunciator'):
                if kind == 'display':
                    w = h = spec[2] + 0.060
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (y_top - y_bot) * H * SS * math.cos(lean)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(10, 12, 12, 255))
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces and panels

def switches(d, S, H, xs, y, labels, kind='tog', px=None):
    for x, lab in zip(xs, labels):
        if kind == 'tog':
            d.rectangle([S * x - 4, H * y - 18, S * x + 4, H * y + 6], fill=(176, 176, 176, 255))
            d.ellipse([S * x - 8, H * y - 24, S * x + 8, H * y - 10], fill=(210, 210, 206, 255))
        elif kind == 'knob':
            d.ellipse([S * x - 16, H * y - 16, S * x + 16, H * y + 16], fill=(20, 20, 20, 255), outline=(150, 150, 146, 255), width=3)
            d.line([S * x, H * y, S * x + 7, H * y - 12], fill=WHITE + (255,), width=4)
        elif kind == 'push':
            d.rectangle([S * x - 14, H * y - 10, S * x + 14, H * y + 10], fill=(40, 40, 42, 255), outline=(160, 160, 156, 255), width=2)
        elif kind == 'lamp':
            d.rectangle([S * x - 15, H * y - 9, S * x + 15, H * y + 9], fill=lab[1] + (255,), outline=(10, 10, 10, 255), width=2)
            lab = lab[0]
        text(d, (S * x, H * y + 30), lab, min(px or H * .075, S * 0.032))


def led_row(d, box, s, fill, px, anchor='lm'):
    """a recessed LED readout: dark window, the text in the LED colour"""
    d.rectangle(box, fill=(10, 8, 6, 255), outline=(66, 66, 62, 255), width=2)
    text(d, (box[0] + (box[2] - box[0]) * 0.06, (box[1] + box[3]) / 2), s, px, fill=fill, anchor=anchor, bold=True)


def p_arm(d, im):
    """the pilot's armament panel: guarded master arm, the A/A select (AIM-9, AIM-7, AIM-54, gun) and the jettison / stores keys"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    d.rectangle([S * .05, H * .08, S * .33, H * .50], fill=RED + (255,), outline=(8, 8, 8, 255), width=3)
    text(d, (S * .19, H * .29), 'MASTER', H * .13, fill=(235, 235, 228), bold=True)
    text(d, (S * .19, H * .41), 'ARM', H * .13, fill=(235, 235, 228), bold=True)
    switches(d, S, H, [0.46, 0.60, 0.74, 0.88], 0.40, ['SW', 'SP', 'PH', 'GUN'], 'push', H * .10)
    switches(d, S, H, [0.12, 0.30, 0.48, 0.66, 0.84], 0.82, ['SAFE', 'A/G', 'JETT', 'STA', 'AUX'], 'tog', H * .09)
    text(d, (S * .5, H * .96), 'ARMAMENT', H * .09)


def p_gear(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    d.ellipse([S * .06, H * .10, S * .46, H * .70], fill=(206, 206, 202, 255), outline=(8, 8, 8, 255), width=3)   # the wheel handle
    d.ellipse([S * .17, H * .25, S * .35, H * .55], fill=(30, 30, 30, 255))
    text(d, (S * .26, H * .86), 'LDG GEAR', H * .10)
    for k, (x, y, c) in enumerate([(0.62, 0.20, GREEN_LAMP), (0.60, 0.50, GREEN_LAMP), (0.84, 0.50, GREEN_LAMP)]):
        d.ellipse([S * x - 12, H * y - 12, S * x + 12, H * y + 12], fill=c + (255,), outline=(8, 8, 8, 255), width=3)
    d.rectangle([S * .56, H * .72, S * .90, H * .94], fill=RED + (255,), outline=(8, 8, 8, 255), width=3)
    text(d, (S * .73, H * .83), 'UNSAFE', H * .10, fill=(235, 235, 228))


def p_util(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.14, 0.38, 0.62, 0.86], 0.52, ['BRAKE', 'HOOK', 'SWEEP', 'FLAPS'], 'tog', H * .22)


def p_ecm(d, im):
    """the pilot's ICS / DECM strip under the HSD"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.08, 0.22, 0.36, 0.52, 0.68, 0.84], 0.40, ['ICS', 'VOL', 'CHAFF', 'FLARE', 'JAM', 'RWR'], 'push', H * .20)


def p_cap(d, im):
    """the RIO's computer address panel: the LED readouts over the 4 x 5 key block"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    led_row(d, [S * .04, H * .06, S * .46, H * .30], 'A/A  AIM-54  STA 4', GREEN_TXT + (255,), H * .17)
    led_row(d, [S * .52, H * .06, S * .96, H * .30], 'TCN 073X  BRG 092', AMBER_TXT + (255,), H * .17)
    labels = [['1', '2', '3', 'CLR', 'ENT'], ['4', '5', '6', 'N', 'E'], ['7', '8', '9', 'S', 'W'], ['0', 'FPT', 'TGT', 'IP', 'HOME']]
    for r in range(4):
        for c in range(5):
            x0 = S * (0.04 + c * 0.188); y0 = H * (0.36 + r * 0.155)
            d.rectangle([x0, y0, x0 + S * .165, y0 + H * .13], fill=(70, 72, 72, 255), outline=(10, 10, 10, 255), width=2)
            text(d, (x0 + S * .082, y0 + H * .065), labels[r][c], H * .09, fill=WHITE + (255,), bold=True)


def p_rcp(d, im):
    """the RIO's radar control panel: range / gain knobs, mode toggles (PD, pulse, TWS, RWS)"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.28, 0.72], 0.20, ['RANGE', 'GAIN'], 'knob', H * .06)
    switches(d, S, H, [0.18, 0.40, 0.62, 0.84], 0.52, ['PD', 'PULSE', 'TWS', 'RWS'], 'push', H * .06)
    switches(d, S, H, [0.18, 0.50, 0.82], 0.84, ['STBY', 'OPR', 'TEST'], 'tog', H * .06)


def p_rio_arm(d, im):
    """the RIO's weapons panel: the missile status lamps and the select / jettison keys"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.18, 0.50, 0.82], 0.16, [('PHX', AMBER), ('SPW', GREEN_LAMP), ('SW', GREEN_LAMP)], 'lamp', H * .06)
    switches(d, S, H, [0.18, 0.50, 0.82], 0.42, ['SEL', 'ARM', 'JETT'], 'push', H * .06)
    switches(d, S, H, [0.14, 0.38, 0.62, 0.86], 0.70, ['SAFE', 'RIP', 'SGL', 'GUN'], 'tog', H * .06)
    text(d, (S * .5, H * .95), 'WEAPONS', H * .06)


def p_r_ics(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.24, 0.76], 0.30, ['ICS', 'TCN'], 'knob', H * .13)
    switches(d, S, H, [0.16, 0.38, 0.62, 0.84], 0.76, ['INS', 'IFF', 'LNK', 'LGT'], 'tog', H * .12)


def p_r_ecm(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.07, 0.19, 0.31, 0.43, 0.55, 0.67, 0.79, 0.91], 0.40,
             ['ECM', 'RWR', 'CHF', 'FLR', 'JAM', 'DCM', 'XPDR', 'STBY'], 'push', H * .20)


def p_hud_ctrl(d, im):
    """the HUD's control strip: brightness and contrast knobs, the declutter and mode keys, the day / night switch"""
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    items = [('DCLT', 'sq'), ('MODE', 'sq'), ('BRT', 'kn'), ('CONT', 'kn'), ('D/N', 'sw'), ('TEST', 'sq')]
    for k, (lab, kind) in enumerate(items):
        x = S * (0.09 + k * 0.164)
        if kind == 'kn':
            d.ellipse([x - H * .20, H * .30 - H * .20, x + H * .20, H * .30 + H * .20], fill=(18, 18, 18, 255),
                      outline=(120, 120, 120, 255), width=3)
            d.line([x, H * .30, x + H * .10, H * .14], fill=WHITE + (255,), width=4)
        elif kind == 'sw':
            d.rectangle([x - 5, H * .10, x + 5, H * .44], fill=(176, 176, 176, 255))
            d.ellipse([x - 10, H * .04, x + 10, H * .22], fill=(206, 206, 206, 255))
        else:
            d.rectangle([x - H * .24, H * .10, x + H * .24, H * .46], fill=(150, 150, 150, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (x, H * .72), lab, H * .20)


def p_aoa(d, im):
    """the angle-of-attack indexer's face: the amber chevron (slow), the green donut (on speed) and the red chevron (fast)"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    amber, green, red = (170, 112, 24, 255), (40, 120, 56, 255), (150, 34, 28, 255)
    d.polygon([(S * .5, H * .10), (S * .86, H * .30), (S * .70, H * .30), (S * .5, H * .20), (S * .30, H * .30), (S * .14, H * .30)], fill=amber)
    d.ellipse([S * .22, H * .38, S * .78, H * .62], outline=green, width=int(S * .09))
    d.polygon([(S * .5, H * .90), (S * .86, H * .70), (S * .70, H * .70), (S * .5, H * .80), (S * .30, H * .70), (S * .14, H * .70)], fill=red)


def p_dial_static(kind):
    def fn(d, im):
        t_, lo, hi, st, ls, val, labs = STATIC_DIALS[kind]
        dial_face(d, im, t_, lo, hi, st, ls, val, labels=labs)
    return fn


def p_handctl(d, im):
    """the top of the RIO's hand controller: the thumb pad with its cross, the two buttons"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(24, 24, 26, 255))
    d.ellipse([S * .22, H * .12, S * .78, H * .68], fill=(40, 40, 42, 255), outline=(150, 150, 146, 255), width=3)
    d.line([S * .5, H * .20, S * .5, H * .60], fill=(190, 190, 184, 255), width=3)
    d.line([S * .30, H * .40, S * .70, H * .40], fill=(190, 190, 184, 255), width=3)
    for x in (0.30, 0.70):
        d.ellipse([S * x - 7, H * .80 - 7, S * x + 7, H * .80 + 7], fill=(180, 40, 36, 255) if x < .5 else (60, 130, 60, 255),
                  outline=(10, 10, 10, 255), width=2)


def p_console(side, seed=0, shaft=None, switch_rows=8):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, CONSOLE, 3, seed=20 + side + seed)
        rng = np.random.default_rng(40 + side + seed)
        y = H * 0.02
        heights = [0.12, 0.10, 0.14, 0.09, 0.15, 0.10, 0.12, 0.09, 0.11][:switch_rows]
        for k, hgt in enumerate(heights):
            y1 = y + hgt * H
            d.rectangle([S * .06, y + 4, S * .94, y1 - 4], fill=(20, 22, 22, 255))
            n = int(rng.integers(2, 5))
            for j in range(n):
                x = S * (0.15 + 0.7 * (j + 0.5) / n); yy = (y + y1) / 2
                if rng.random() < 0.5:
                    d.ellipse([x - 9, yy - 9, x + 9, yy + 9], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
                else:
                    d.rectangle([x - 4, yy - 11, x + 4, yy + 5], fill=(180, 180, 180, 255))
            y = y1
        if shaft:
            d.rectangle([S * shaft[0], H * shaft[1], S * shaft[2], H * shaft[3]], fill=(6, 6, 6, 255))
    return fn


def p_throttle_plate(d, im):
    S, H = im.width, im.height
    noisy(d, im, (30, 32, 33), 3, seed=31)
    d.rectangle([S * .08, H * .08, S * .92, H * .92], outline=(100, 102, 100, 255), width=3)
    for k, lab in enumerate(['OFF', 'IDLE', 'MIL', 'A/B']):
        y = H * (0.88 - k * 0.24)
        d.line([S * .35, y, S * .65, y], fill=(150, 150, 146, 255), width=3)
        text(d, (S * .18, y), lab, H * .06, fill=(200, 200, 190, 255))


def p_wall(seed):
    def fn(d, im):
        noisy(d, im, PANEL_D, 3, seed=seed)
    return fn


def p_floor(d, im):
    noisy(d, im, FLOOR, 4, seed=77)
    S, H = im.width, im.height
    for k in range(1, 8):
        d.line([0, H * k / 8, S, H * k / 8], fill=(26, 28, 30, 255), width=3)


def p_bulkhead(d, im):
    """the bulkhead the RIO's panel stands against: circuit breaker rows, a placard strip"""
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 4, seed=70)
    rng = np.random.default_rng(70)
    for r in range(3):
        y0 = H * (0.08 + r * 0.30)
        d.rectangle([S * .06, y0, S * .94, y0 + H * 0.26], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)
        for c in range(12):
            x = S * (0.10 + c * 0.071)
            d.ellipse([x - 5, y0 + H * .06, x + 5, y0 + H * .06 + 10], fill=(rng.integers(150, 235),) * 3 + (255,))


def bar_h(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 20, 22, 255))
    for k in range(5):
        x = S * (0.1 + k * 0.2)
        d.rectangle([x - S * .06, H * .22, x + S * .06, H * .78], fill=(84, 90, 96, 255))


def bar_v(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 20, 22, 255))
    for k in range(5):
        y = H * (0.1 + k * 0.2)
        d.rectangle([S * .22, y - H * .06, S * .78, y + H * .06], fill=(84, 90, 96, 255))


PAINTERS = {'arm': p_arm, 'gear': p_gear, 'util': p_util, 'ecm': p_ecm, 'cap': p_cap, 'rcp': p_rcp, 'r_arm': p_rio_arm,
            'r_ics': p_r_ics, 'r_ecm': p_r_ecm}


# ---------------------------------------------------------------- build helpers

def add_panel(m, atlas, sw, panel, layout, gauges, displays, seat, cache):
    """the three facets with their instruments; appends the game's gauges / displays (vehicle-local, seat index)"""
    ppm = 700
    for f in (panel.centre, panel.left, panel.right):
        a0, a1 = f.extent()
        y_top = max(f.top(a) for a in f.knots())
        W = int(round((a1 - a0) * ppm)); Hh = int(round((y_top - f.bottom()) / math.cos(panel.lean) * ppm))
        rect = atlas.paint(W, Hh, panel_paint(f, (W, Hh), layout))
        yb = f.bottom()
        ks = f.knots()
        cuts = sorted(set([a0 + (a1 - a0) * k / 8 for k in range(9)] + ks))
        for aa0, aa1 in zip(cuts[:-1], cuts[1:]):
            s0, s1 = (aa0 - a0) / (a1 - a0), (aa1 - a0) / (a1 - a0)
            t0 = (f.top(aa0) - yb) / (y_top - yb); t1 = (f.top(aa1) - yb) / (y_top - yb)
            pts = [f.at(aa0, yb), f.at(aa1, yb), f.at(aa1, f.top(aa1)), f.at(aa0, f.top(aa0))]
            if f.side == -1:
                uvs = [uv_of(rect, 1 - s0, 0), uv_of(rect, 1 - s1, 0), uv_of(rect, 1 - s1, t1), uv_of(rect, 1 - s0, t0)]
            else:
                uvs = [uv_of(rect, s0, 0), uv_of(rect, s1, 0), uv_of(rect, s1, t1), uv_of(rect, s0, t0)]
            m.face(pts, uvs, normal=f.N)
        back = -f.N * 0.02
        loop = [f.at(cuts[0], yb)] + [f.at(a, yb) for a in cuts[1:]] + [f.at(a, f.top(a)) for a in cuts[::-1]]
        m.flat([c + back for c in loop], sw['panel_d'], normal=-f.N)
        mid0 = np.mean(loop, 0)
        for i in range(len(loop)):
            p, q = loop[i], loop[(i + 1) % len(loop)]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['panel_d'], toward=mid + (mid - mid0))
    bar_h_r = cache['bar_h']; bar_v_r = cache['bar_v']
    for name, f, a, y, spec, label in layout:
        kind = spec[0]
        c = f.at(a, y)
        E = m.eye
        if kind == 'game':
            gauges.append({'Seat': seat, 'Kind': spec[1], 'Center': r5(c + E + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c, f.N, f.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'], back=0.0)
        elif kind == 'paint_disc':
            dia = spec[2]
            key = ('disc', spec[1])
            rect = cache.get(key)
            if rect is None:
                rect = atlas.paint(96, 96, p_dial_static(spec[1]))
                cache[key] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, dia, dia, rect)
            m.ring(c, f.N, f.U, dia / 2, dia / 2 * (1 + RIM), 0.010, sw['bezel'], back=0.0)
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': seat, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + E + f.N * 0.004), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS,
                           'Lamps': LAMPS})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': seat, 'Center': r5(c + E + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': spec[3]})
            _, n_, u_, r_ = frame(c, f.N, f.U)
            pr = 0.010
            t = 0.022
            if len(spec) > 4 and spec[4] == 'round':
                # the TID: a round screen. The game draws a square picture; a black mask over its four corners and a round
                # bezel ring leave the circle
                seg = 40
                r_in = w / 2
                r_corner = w / 2 * 1.4142 + 0.004
                m.ring(c, f.N, f.U, r_in, r_corner, pr - 0.002, sw['black'], seg=seg, back=0.0)
                m.ring(c, f.N, f.U, r_corner - 0.002, r_corner + 0.014, pr + 0.004, sw['bezel'], seg=seg, back=0.0)
            else:
                # a CRT display: a flat bezel with a row of push keys along each edge and a black outer wall
                m.rect_tex(c + u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
                m.rect_tex(c - u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
                m.rect_tex(c + r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
                m.rect_tex(c - r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
                for sgn in (-1, 1):
                    m.box(c + u_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (w / 2 + t, 0.0015, (pr - 0.002) / 2), sw['black'])
                    m.box(c + r_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (0.0015, w / 2 + t, (pr - 0.002) / 2), sw['black'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 240 if w >= 0.15 else 180
            ph = max(24, int(round(pw * h / w)))
            key = ('rect', name, id(m))
            rect = cache.get(key)
            if rect is None:
                rect = atlas.paint(pw, ph, PAINTERS[name])
                cache[key] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])


def panel_top_pts(panel):
    """the panel's top edge from the left wall to the right wall (eye-relative), duplicates removed"""
    pts = []
    for f, aa in ((panel.left, [panel.wing_len, 0.0]), (panel.centre, [-panel.hinge, -panel.top_k, panel.top_k, panel.hinge]),
                  (panel.right, [0.0, panel.wing_len])):
        for a in aa:
            pts.append(f.at(a, f.top(a)))
    uniq = [pts[0]]
    for p in pts[1:]:
        if np.linalg.norm(p - uniq[-1]) > 1e-4:
            uniq.append(p)
    return uniq


def coaming(m, sw, panel, z_wall, y_wall, x_in=1.0, x_max=None):
    """a flat black strip from the panel's top edge forward to the deck, so the two meet with no gap (seals the tub under the glass);
    its front edge is the panel's top edge scaled by [x_in] or clipped to [x_max]"""
    uniq = panel_top_pts(panel)
    lift = np.array([0, 0.004, 0])
    fx = (lambda x: float(np.clip(x, -x_max, x_max))) if x_max else (lambda x: x * x_in)
    for i in range(len(uniq) - 1):
        a, b = uniq[i], uniq[i + 1]
        m.flat([a + lift, b + lift, np.array([fx(b[0]), y_wall, z_wall]), np.array([fx(a[0]), y_wall, z_wall])],
               sw['coaming'], normal=[0, 1, 0])


def tub(m, atlas, sw, panel, eye, z_back_abs, seed, shaft=None, throttle=None, hand=None, switch_z=(), pedals=None):
    """liners above the consoles up to the sill, the two consoles (top, inner side, front, back), the floor liner and the kick wall"""
    fy = FLOOR_ABS - eye[1]
    cy = CONSOLE_ABS - eye[1]
    sy = SILL_ABS - eye[1]
    z_back = z_back_abs - eye[2]
    for sgn in (1, -1):
        wx = sgn * panel.wall_end                          # (the liner runs on to the wing's end)
        zf_b = panel.front_z(wx, cy)
        zf_t = panel.front_z(wx, sy)
        m.flat([[sgn * WALL_X, cy, z_back], [wx, cy, zf_b], [wx, sy, zf_t - 0.02], [sgn * WALL_X, sy, z_back]],
               sw['liner'], normal=[-sgn, 0, 0])
        xi, xo = CON_IN * sgn, CON_OUT * sgn
        zf_i, zf_o = panel.front_z(xi, cy), panel.front_z(xo, cy)
        con = atlas.paint(64, 340, p_console(sgn, seed, shaft=shaft if sgn > 0 else None))
        top = [[xi, cy, z_back + 0.01], [xo, cy, z_back + 0.01], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [uv_of(con, 0, 0), uv_of(con, 1, 0), uv_of(con, 1, 1), uv_of(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, z_back + 0.01], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, z_back + 0.01]], sw['panel_d'], normal=[-sgn, 0, 0])
        m.flat([[xi, fy, z_back + 0.01], [xo, fy, z_back + 0.01], [xo, cy, z_back + 0.01], [xi, cy, z_back + 0.01]], sw['panel_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf_i], [xo, fy, zf_o], [xo, cy, zf_o], [xi, cy, zf_i]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xo, fy, z_back + 0.01], [xo, fy, zf_o], [xo, cy, zf_o], [xo, cy, z_back + 0.01]], sw['panel_d'], normal=[-sgn, 0, 0])
    zk = panel.centre.at(0, panel.bot_c)[2]
    m.flat([[-CON_IN, fy, z_back], [CON_IN, fy, z_back], [CON_IN, fy, zk], [-CON_IN, fy, zk]], sw['floor'], normal=[0, 1, 0])
    kick = atlas.paint(180, 60, p_wall(71))
    m.quad_tex([[CON_IN, fy, zk], [-CON_IN, fy, zk], [-CON_IN, panel.bot_c, zk], [CON_IN, panel.bot_c, zk]], kick, normal=[0, 0, -1])
    if pedals is not None:                                    # rudder pedals: plates tilted toward the pilot, arms to the floor
        for s in (1, -1):
            m.box([s * 0.13, fy + 0.07, pedals], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.060, 0.010), sw['mgrey'])
            m.box([s * 0.13, fy + 0.03, pedals + 0.03], (X, Y, Z), (0.014, 0.030, 0.035), sw['dgrey'])
    if throttle is not None:                                  # the two throttles (left console): quadrant, levers, grips, thumb switches
        tz = throttle
        tx = 0.335
        plate = atlas.paint(60, 80, p_throttle_plate)
        m.box([tx, cy + 0.011, tz], (X, Y, Z), (0.085, 0.011, 0.140), sw['dgrey'])
        m.rect_tex([tx, cy + 0.0225, tz], Y, Z, 0.17, 0.28, plate)
        # The two levers are their own bones (throttle_lever_1 / _2) hinged under the console top, modelled at idle
        # (throttle_lib); the plate and its gate decal stay.
        THR.clear()
        THR.extend(throttle_lib.jet_levers(m, lambda: Mesh(eye), sw, 'f14', x=tx - 0.012, top_y=cy, z_idle=tz - 0.04, n=2, pitch=0.060,
                                           depth=0.12, plate=False))
        m.box([tx + 0.080, cy + 0.025, tz - 0.07], (X, Y, Z), (0.020, 0.012, 0.030), sw['metal'])
    if hand is not None:                                      # the RIO's hand controller on the right console
        zc, top_tex = hand
        x = -0.345
        m.box([x, cy + 0.010, zc], (X, Y, Z), (0.050, 0.010, 0.060), sw['dgrey'])
        g = (X, unit([0, 1, 0.30]), unit([0, -0.30, 1]))
        c0 = np.array([x, cy + 0.060, zc + 0.014])
        m.box(c0, g, (0.016, 0.045, 0.022), sw['black'])
        top_c = c0 + g[1] * 0.052 + g[2] * 0.004
        m.box(top_c, g, (0.022, 0.006, 0.027), sw['dgrey'], front=(1, 1, top_tex, -X, g[2]))
        m.box(c0 - g[2] * 0.026 + g[1] * 0.012, g, (0.008, 0.016, 0.005), sw['metal'])
        m.box(c0 + g[2] * 0.026 + g[1] * 0.030, g, (0.005, 0.005, 0.004), sw['red'])
    for s in (1, -1):                                         # switch rows on the console tops
        for zr in switch_z:
            if s > 0 and throttle is not None and throttle - 0.17 < zr < throttle + 0.17:
                continue
            if s < 0 and hand is not None and hand[0] - 0.09 < zr < hand[0] + 0.09:
                continue
            for xc in (0.285, 0.31, 0.335, 0.36):
                m.box([s * xc, cy + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, cy + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])), (0.0025, 0.012, 0.0025), sw['black'])
    return zk


def stick_mesh(m, sw, foot, tall=0.26, lean_back=0.08):
    """a centre stick standing on [foot]: column and grip (hat, trigger, trim thumb switch)"""
    col_ax = (X, unit([0, 1, -lean_back]), unit([0, lean_back, 1]))
    m.box(foot + col_ax[1] * (tall / 2), col_ax, (0.011, tall / 2, 0.011), sw['metal'])
    g_ax = (X, unit([0, 1, -0.20]), unit([0, 0.20, 1]))
    gc = foot + col_ax[1] * tall + g_ax[2] * 0.012 + g_ax[1] * 0.045
    m.box(gc, g_ax, (0.021, 0.060, 0.028), sw['black'])
    m.box(gc + g_ax[1] * 0.062, g_ax, (0.015, 0.006, 0.021), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.0675 + g_ax[2] * 0.009, g_ax, (0.005, 0.004, 0.005), sw['red'])
    m.box(gc + g_ax[2] * 0.031 - g_ax[1] * 0.002, g_ax, (0.007, 0.014, 0.005), sw['metal'])
    m.box(gc + g_ax[1] * 0.020, g_ax, (0.023, 0.010, 0.030), sw['dgrey'])


def edge_bar(m, p0, p1, n, thick, depth, rect):
    """a thin bar along the segment p0-p1 in the plane with normal n: [thick] in the plane, [depth] along n"""
    p0 = np.array(p0, float); p1 = np.array(p1, float)
    d = unit(p1 - p0); L = np.linalg.norm(p1 - p0)
    perp = unit(np.cross(n, d))
    m.box((p0 + p1) / 2, (d, perp, unit(n)), (L / 2 + thick / 2, thick / 2, depth / 2), rect)


HUD_LEAN = math.radians(22)


def hud_unit(m, atlas, sw):
    """The pilot's HUD: one housing on the glareshield (its control strip low on the pilot's face, 4.5 cm) and ONE combiner glass in
    a slim frame, centred on the neutral eye line; nothing stands beside the glass above its foot. Returns the glass triangles
    (vehicle-local) and the HUD entry's numbers."""
    ctrl = atlas.paint(200, 44, p_hud_ctrl)
    hz0, hz1, hy0, hy1, hhw = 0.450, 0.600, -0.058, -0.012, 0.125
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    g_up = np.array([0, math.cos(HUD_LEAN), -math.sin(HUD_LEAN)])
    g_n = unit(np.cross(X, g_up))
    hw, h = 0.095, 0.130
    g_bot = np.array([0, -0.0605, 0.500])
    outline = [(-hw, 0.0), (hw, 0.0), (hw, h), (-hw, h)]               # (x, up the glass)
    P = [g_bot + X * u + g_up * t for u, t in outline]
    th, dp = 0.006, 0.006
    cf = sw['combiner_frame']
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        if i == 0:
            continue                                                    # (the foot sits in the housing's slot)
        edge_bar(m, a, b, g_n, th, dp, cf)
    tris = []
    for k in range(1, len(P) - 1):
        tris += [P[0] + m.eye, P[k] + m.eye, P[k + 1] + m.eye]
    centre = g_bot + g_up * (h / 2)
    return tris, dict(centre=centre + m.eye, normal=-g_n, up=g_up, width=2 * hw, height=h)


# ---------------------------------------------------------------- seats: face-curtain loops

# The seat tops' bright handle assembly (a red bar, two chunky yellow rings and a striped post per seat) is cut from the hull piece:
# boxes around each headbox top; faces whose texel is yellow or red go
HANDLE_BOXES = [(HULL1, (-0.26, 2.86, 4.05), (0.26, 3.35, 4.60)), (HULL1, (-0.26, 2.86, 2.50), (0.26, 3.42, 2.90))]


def drop_orphans(pm, lo, hi):
    """small welded pieces of the seat (a strap buckle or two) left floating by the cut: components of under 25 faces and 30 cm
    whose centre lies in the handle box go too"""
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    polys = pm['polys']
    parent = {}

    def find(a):
        while parent.setdefault(a, a) != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    key = lambda i: tuple(np.round(P[i], 4))
    for p in polys:
        ks = [key(q[0]) for q in p]
        for k in ks[1:]:
            parent[find(k)] = find(ks[0])
    comp = {}
    for n, p in enumerate(polys):
        comp.setdefault(find(key(p[0][0])), []).append(n)
    drop = set()
    for ids in comp.values():
        if len(ids) > 24:
            continue
        V = np.vstack([P[[q[0] for q in polys[n]]] for n in ids])
        if np.linalg.norm(V.max(0) - V.min(0)) < 0.30 and np.all(V.mean(0) >= lo) and np.all(V.mean(0) <= hi):
            drop.update(ids)
    pm['polys'] = [p for n, p in enumerate(polys) if n not in drop]
    return len(drop)


def cut_handles(geo):
    tex = np.asarray(Image.open(TEX).convert('RGB'))
    th, tw = tex.shape[:2]
    cut = 0
    for b in geo['minecraft:geometry'][0]['bones']:
        boxes = [(np.array(lo), np.array(hi)) for n, lo, hi in HANDLE_BOXES if n == b['name']]
        if not boxes:
            continue
        pm = b['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        keep = []
        for p in pm['polys']:
            c = P[[q[0] for q in p]].mean(0)
            if any(np.all(c >= lo) and np.all(c <= hi) for lo, hi in boxes):
                uv = U[[q[2] for q in p]].mean(0)
                r, g, bl = (int(v) for v in tex[min(th - 1, int((1 - uv[1]) * th)), min(tw - 1, int(uv[0] * tw))])
                if r > 200 and bl < 90 and (g > 180 or g < 90):
                    cut += 1
                    continue
            keep.append(p)
        pm['polys'] = keep
        for lo, hi in boxes:
            cut += drop_orphans(pm, lo, hi)
    print(f'seat handles: {cut} bright block faces cut')
    return cut


def face_curtain(m, sw, top_y, z_abs):
    """Martin-Baker GRU-7A face-curtain handles: a pair of thin striped (yellow and black) webbing loops lying just ahead of the
    headbox cap with the red fitting between them, at real size (a loop about 10 x 9 cm, webbing 2 cm)"""
    Zt = np.array([0.0, math.sin(math.radians(10)), math.cos(math.radians(10))])
    Nt = np.cross(Zt, X)
    c0 = np.array([0.0, top_y, z_abs]) - m.eye
    m.box(c0 - Z * 0.040, (X, Y, Z), (0.022, 0.012, 0.020), sw['red'])
    for sx in (-1, 1):
        cen = c0 + X * sx * 0.095
        n = 14
        pts = [cen + X * (0.052 * math.cos(2 * math.pi * k / n)) + Zt * (0.046 * math.sin(2 * math.pi * k / n)) for k in range(n + 1)]
        for k in range(n):
            p0, p1 = pts[k], pts[k + 1]
            t = unit(p1 - p0)
            r = unit(np.cross(Nt, t))
            m.box((p0 + p1) / 2, (t, r, Nt), (np.linalg.norm(p1 - p0) / 2, 0.012, 0.005 if k % 2 == 0 else 0.003),
                  sw['yellow'] if k % 2 == 0 else sw['black'])
        m.box(c0 + X * sx * 0.0325 - Z * 0.020, (X, Y, Z), (0.0105, 0.004, 0.010), sw['black'])


def build_pilot(atlas, sw):
    m = Mesh(EYE_F)
    stick = Mesh(EYE_F)
    gauges, displays = [], []
    cache = {'bar_h': atlas.paint(96, 16, bar_h), 'bar_v': atlas.paint(16, 96, bar_v)}
    add_panel(m, atlas, sw, PANEL_F, LAYOUT_F, gauges, displays, 0, cache)
    tub(m, atlas, sw, PANEL_F, EYE_F, 4.12, 0, shaft=(0.30, 0.38, 0.70, 0.68), throttle=0.12, switch_z=(-0.28, -0.20, -0.12, 0.30, 0.36),
        pedals=0.40)
    # the glareshield: a flat black strip from the panel's top edge forward to the deck block's rear edge (z 5.67, y 2.765)
    coaming(m, sw, PANEL_F, 5.675 - EYE_F[2], 2.740 - EYE_F[1], x_max=0.225)
    # the angle-of-attack indexer on the glareshield's left, its lamps toward the pilot
    aoa = atlas.paint(48, 96, p_aoa)
    m.box([0.215, -0.062, 0.585], (X, Y, Z), (0.017, 0.028, 0.014), sw['dgrey'], front=(2, -1, aoa, -X, Y))
    glass, hud = hud_unit(m, atlas, sw)
    # the centre stick (the animated bone's): its foot ahead of the seat between the knees
    foot = np.array([0.0, 2.20 - EYE_F[1], 4.90 - EYE_F[2]])
    stick.eye = m.eye
    stick_mesh(stick, sw, foot)
    m.box(foot + [0, 0.010, 0], (X, Y, Z), (0.045, 0.010, 0.045), sw['dgrey'])                   # base plate
    m.box(foot + [0, 0.035, 0], (X, Y, Z), (0.024, 0.026, 0.024), sw['black'])                   # rubber boot
    face_curtain(m, sw, 3.200, 4.337)
    return m, stick, gauges, displays, glass, hud, foot + m.eye


def build_rear(atlas, sw):
    m = Mesh(EYE_R)
    gauges, displays = [], []
    cache = {'bar_h': atlas.paint(96, 16, bar_h), 'bar_v': atlas.paint(16, 96, bar_v)}
    add_panel(m, atlas, sw, PANEL_R, LAYOUT_R, gauges, displays, 1, cache)
    hand_tex = atlas.paint(60, 60, p_handctl)
    tub(m, atlas, sw, PANEL_R, EYE_R, 2.47, 5, shaft=(0.30, 0.20, 0.70, 0.55), hand=(0.14, hand_tex),
        switch_z=(-0.33, -0.25, -0.17, 0.26))
    # the block between the crews: its top (2.76 .. 3.11) was cut with the airframe's gunsight bits and is rebuilt here, inside the
    # canopy's section (x 0.436 up to the sill, 0.40 above it), its rear face behind the panel's wings
    z0, z1 = 3.49, 4.06
    blk =lambda y0, y1, hx: m.box([0, (y0 + y1) / 2 - EYE_R[1], (z0 + z1) / 2 - EYE_R[2]], (X, Y, Z),
                                    (hx, (y1 - y0) / 2, (z1 - z0) / 2), sw['coaming'])
    blk(2.480, SILL_ABS, 0.420)
    blk(SILL_ABS, 3.108, 0.400)
    # coaming strip over the panel's top edge, back to the block's rear top edge
    coaming(m, sw, PANEL_R, z0 - EYE_R[2] - 0.001, 3.108 - EYE_R[1], x_in=0.92)
    face_curtain(m, sw, 3.243, 2.762)
    return m, gauges, displays


def swatches(atlas):
    cols = dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lgrey=LGREY, yellow=YELLOW, red=RED,
                metal=METAL, floor=FLOOR, bezel=(30, 31, 33), coaming=(20, 21, 22), console=CONSOLE, liner=(74, 78, 80),
                combiner_frame=(34, 36, 38))
    return {k: atlas.swatch(c) for k, c in cols.items()}


# ---------------------------------------------------------------- main

def write_json(path, obj, indent, newline=True):
    text_ = json.dumps(obj, indent=indent, ensure_ascii=False) if indent else json.dumps(obj, separators=(',', ':'))
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text_ + ('\n' if newline else ''))


def write_asset(path, asset):
    """the asset keeps its layout: indented, with the canopy glass triangle list on one line at the end"""
    glass_json = asset.pop('CanopyGlass', None)
    text_ = json.dumps(asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text_ = text_[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        asset['CanopyGlass'] = glass_json
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text_ + '\n')


def find_run(mins, maxs, a, c, lo, hi, tol=0.012):
    """the first face of the run of c - a + 1 faces near index a whose bounding box is (lo, hi), or None (the list may
    have shifted: an earlier cut, a regeneration)"""
    n = c - a + 1
    for s in sorted(range(max(0, a - 700), min(len(mins) - n, a + 700) + 1), key=lambda s: abs(s - a)):
        if np.all(np.abs(mins[s:s + n].min(0) - lo) < tol) and np.all(np.abs(maxs[s:s + n].max(0) - hi) < tol):
            return s
    return None


def remove_old(geo):
    """cut the airframe's own dash, consoles, switch boxes and sight stand-in (runs of the hull bones' face lists, each found by its
    bounding box). A run that is not found stops the script, unless this geo already carries the new cockpits (a second run: the
    runs are gone)."""
    removed = 0
    bones = geo['minecraft:geometry'][0]['bones']
    already = any(b['name'] == BONE_F and b.get('poly_mesh', {}).get('polys') for b in bones)
    for b in bones:
        if b['name'] not in OLD_INTERIOR:
            continue
        pm = b['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        mins = np.array([P[[q[0] for q in p]].min(0) if p else np.full(3, 9e9) for p in pm['polys']])
        maxs = np.array([P[[q[0] for q in p]].max(0) if p else np.full(3, -9e9) for p in pm['polys']])
        gone = set()
        if not already:
            for a, c, lo, hi in OLD_INTERIOR[b['name']]:
                s = find_run(mins, maxs, a, c, lo, hi)
                if s is None:
                    raise SystemExit(f'{b["name"]}: old interior run {a}-{c} (box {lo}, {hi}) is not in the airframe: the face '
                                     'list changed; re-read OLD_INTERIOR (nothing was written)')
                gone.update(range(s, s + c - a + 1))
        removed += len(gone)
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone]
    return removed


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    g = geo['minecraft:geometry'][0]
    removed = remove_old(geo)
    cut_handles(geo)
    atlas = Atlas()
    sw = swatches(atlas)
    mf, stick, gf, df, glass, hud, foot = build_pilot(atlas, sw)
    mr, gr, dr = build_rear(atlas, sw)
    pms = {BONE_F: mf.poly_mesh(), BONE_R: mr.poly_mesh()}
    for name, anchor in ((BONE_F, HULL0), (BONE_R, HULL1)):
        bone = next((b for b in g['bones'] if b['name'] == name), None)
        if bone is None:
            hull = next(b for b in g['bones'] if b['name'] == 'hull')
            at = next(i for i, b in enumerate(g['bones']) if b['name'] == anchor) + 1
            bone = {'name': name, 'parent': 'hull', 'pivot': list(hull['pivot'])}
            g['bones'].insert(at, bone)
        bone['poly_mesh'] = pms[name]
    # the pilot's stick: its three bones' pivots to the stick's foot (model pixels)
    foot_px = [round(float(foot[0]) * 16, 5), round(float(foot[1]) * 16, 5), round(-float(foot[2]) * 16, 5)]
    for b in g['bones']:
        if b['name'] in STICK_BONES:
            b['pivot'] = list(foot_px)
        if b['name'] == STICK_BONE:
            b['poly_mesh'] = stick.poly_mesh()
    thr = throttle_lib.add_bones(g['bones'], THR, BONE_F, EYE_F)
    for s in asset['AircraftRig']['Surfaces']:
        if s['Bone'] in ('control_stick_roll', 'control_stick_pitch'):
            s['Pivot'] = list(foot_px)
    asset['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gf + gr}
    old_fd = asset.get('FlightDisplays') or {}
    fd = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': df + dr}
    if 'Planform' in old_fd:
        fd['Planform'] = old_fd['Planform']
    asset['FlightDisplays'] = fd
    asset['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(EYE_F)}
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        c = t.mean(1) - np.array(EYE_F)                      # (an earlier combiner of this script goes)
        keep = ~((np.abs(c[:, 0]) < 0.14) & (c[:, 1] > -0.12) & (c[:, 1] < 0.12) & (c[:, 2] > 0.38) & (c[:, 2] < 0.56))
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    print(f'f_14c: {removed} old interior faces removed; front {len(pms[BONE_F]["polys"])} faces, rear {len(pms[BONE_R]["polys"])} faces; '
          f'{len(gf) + len(gr)} gauges ({len(gr)} rear), {len(df) + len(dr)} displays ({len(dr)} rear), HUD combiner {len(glass) // 3} triangles; '
          f'stick foot {foot_px} px; throttle bones {[(n, p) for n, p, _ in thr]}; atlas {atlas.used()} of {sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    fb = json.load(open(FALLBACK, encoding='utf-8'))
    fbones = fb['minecraft:geometry'][0]['bones']
    for b in fbones:
        if b['name'] in STICK_BONES:
            b['pivot'] = list(foot_px)
    for name, anchor in ((BONE_F, HULL0), (BONE_R, HULL1)):
        if not any(b['name'] == name for b in fbones):
            at = next(i for i, b in enumerate(fbones) if b['name'] == anchor) + 1
            hull = next(b for b in fbones if b['name'] == 'hull')
            fbones.insert(at, {'name': name, 'parent': 'hull', 'pivot': list(hull['pivot'])})
    write_json(FALLBACK, fb, None, newline=False)
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])

