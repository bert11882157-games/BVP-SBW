#!/usr/bin/env python3
"""F-4C Phantom II cockpits (pilot's and back-seater's), built by hand for the F-4C.mtb airframe: one standalone script.

The airframe (commit eb79c6de) brought two crew stations of its own: two Martin-Baker Mk 7 seats, and in the tubs two
photo-textured panels, a pair of boxy side consoles each and a loose handful of boxes (switch blocks, levers, pedals, a
gunsight stand-in). This script cuts the panels, consoles and loose boxes out of the two hull pieces (by the faces' place
in the airframe's own list; the walls, floors, bulkheads, the sill rails, the frames and the two seats stay, so the
exterior is as it was) and puts a modelled cockpit in each tub, laid out after the -1 (T.O. 1F-4C-1) panels, DCS F-4E
and photographs of the F-4C/D (1960s analogue, no head-up display):

  PILOT (front seat)
  * the main panel in three facets (centre 8 degrees back, wings turned 20 degrees toward the pilot): the radar scope
    repeater at the top centre, the attitude director over the HSI on the centre line between the airspeed / Mach
    indicator and the altimeter (the VVI, AoA, g-meter, clock, radar altimeter and turn-and-slip row below), the engine
    stack (two tachometers, two exhaust gas temperatures, two fuel flows, two nozzle positions, oil) and the fuel
    quantity on the right with the warning / caution panel above it, the armament panel (master arm, station select, gun),
    the landing gear handle with its lights and the utility panel on the left;
  * the LCOSS (lead computing optical sight set) on the glareshield: one unit (a squared head with its control face
    toward the pilot, a sun shutter, two struts) carrying the reflector glass (a plate in a thin frame, tilted 35 degrees
    back and centred on the neutral eye line; added to CanopyGlass) with the game's gunsight reticle (HeadUpDisplay,
    style GYRO: the F-4C had no head-up display);
  * the left console with the two throttles (quadrant, handles with their speed-brake and trim switches), switch panels
    on both consoles, the rudder pedals and the centre stick (the airframe's animated stick bone).
  BACK-SEATER (rear seat)
  * a tall panel: the flight instruments (airspeed, attitude, altimeter, g-meter, HSI, VVI) along the top, the large
    radar scope with its rubber hood on the centre line, the radar control panels on both wings, the clock and the RWR
    scope beside the hood;
  * consoles with the rear throttles and the ICS / radar panels, rudder pedals and a rear stick (fixed: the airframe
    animates only one stick).

Paint lives in a free area of textures/entity/f_4c.png (no UV of the model, the native fallback or the wreck lands
there; the burnt copy goes to entity_dead). The instruments the game draws are written to CockpitGauges and
FlightDisplays, the sight's reticle to HeadUpDisplay, the stick bones' pivots (geo, the native fallback and
AircraftRig) go to the new foot of the pilot's stick, and both seats and eyes are placed in the vehicle data.

Order: git checkout the airframe's files, run canopy_f_4c.py, then this script (it appends the sight's reflector glass
to the canopy's CanopyGlass). Running it again rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model), relative to the
cockpit's own eye; the mesh is written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/f_4c.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_4c.geo.json')
FALLBACK = os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback/f_4c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_4c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_4c.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_4c.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/f_4c.png')

BONE = 'wreck_fuselage_0__cockpit'
THR = []                                 # the throttle levers (throttle_lib), one bone each
STICK_BONES = ('control_stick_roll', 'control_stick_pitch', 'wreck_fuselage_0__control_stick_pitch')
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
# free texture area (x, y, w, h): the model's UVs all lie in the lower half (v <= 0.5); the native fallback's box UVs
# start at (0, 0) and reach about (376, 125)
ATLAS = [(0, 160, 2048, 1880)]
TW, TH = 2048, 4096

EYE_F = (0.0, 3.12, 5.66)              # the pilot's eye (vehicle data); the seat sits 1.62 below it
EYE_R = (0.0, 3.30, 4.19)              # the back-seater's eye (his seat stands 0.18 higher)

# The airframe's own panels, consoles and loose boxes, by the faces' index in the two hull bones' lists (each is a run of
# 6-face boxes or the 4-quad panels). The runs were read off the airframe (faces of the F-4C.mtb regeneration, 2977 and
# 1897 faces); the seats, the walls, sill bands, bulkheads, canopy frames and hull stay.
HULL0, HULL1 = 'wreck_fuselage_0__hull', 'wreck_fuselage_1__hull'
# (first face, last face, bounding box lo, hi in blocks): the box is checked before a run is cut, so a regenerated airframe
# whose lists moved is never cut blindly, and a second run (the runs already gone) cuts nothing more
OLD_INTERIOR = {
    HULL0: [(1447, 1452, (-0.31, 2.40, 6.13), (0.31, 2.98, 6.17)),      # pilot's panel, centre slab
            (1453, 1470, (-0.36, 2.58, 4.57), (0.36, 3.43, 4.79)),      # back-seater's panel (top and main block)
            (1471, 1593, (-0.49, 2.09, 5.10), (0.49, 2.98, 7.02)),      # pilot's consoles, panel wings, the boxes ahead
            (1658, 1669, (-0.49, 2.63, 4.44), (0.49, 2.85, 4.66)),      # back-seater's console fronts
            (1670, 1693, (-0.07, 2.27, 5.92), (0.07, 3.28, 6.08)),      # gunsight stand-in and pedal stems
            (1694, 1733, (0.22, 2.38, 5.63), (0.42, 2.72, 6.08)),       # throttle area (pilot's left console)
            (1752, 1937, (-0.40, 2.14, 5.75), (0.40, 3.16, 6.21)),      # pedals, glareshield blocks, HUD stand-in
            (1950, 1955, (-0.24, 3.10, 5.90), (-0.13, 3.21, 5.97)),
            (1956, 2086, (-0.51, 2.34, 5.10), (-0.24, 3.00, 5.92)),     # right console and its boxes
            (2099, 2170, (0.22, 2.29, 5.10), (0.47, 2.74, 5.86)),       # left console, small switch blocks
            (2171, 2194, (-0.18, 2.78, 5.97), (0.18, 3.22, 6.93)),      # thin dark plate and rail across the pilot's view
            (2355, 2402, (-0.04, 2.36, 5.95), (0.04, 2.80, 6.06))],     # the airframe's own stick
    HULL1: [(1468, 1479, (-0.49, 2.31, 3.64), (0.49, 2.63, 4.66)),      # back-seater's console boxes
            (1611, 1736, (-0.47, 2.51, 3.64), (0.47, 2.96, 4.30))],     # the white switch boxes and the left console
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

# ---------------------------------------------------------------- colours (the F-4's dark grey-black panels, grey consoles)

PANEL = (94, 98, 100)              # instrument panel face: FS 36231 grey (a touch lighter, as the game's light shows it); dial faces stay black
PANEL_D = (68, 71, 73)
PANEL_L = (74, 77, 78)
BLACK = (18, 19, 20)
DGREY = (38, 40, 42)
MGREY = (84, 88, 90)
LGREY = (150, 154, 156)
FRAME = (50, 54, 52)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (60, 62, 64)
CONSOLE = (110, 114, 116)            # the consoles are lighter grey than the panel
WHITE = (226, 228, 220)
GREEN_LAMP = (60, 150, 70)
AMBER = (214, 140, 30)
GREEN_TXT = (80, 220, 100)


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
    def __init__(self):
        self.faces = []                      # (points k x 3 vehicle-local, uvs k x 2, normal 3)
        self.eye = np.zeros(3)               # the cockpit being built: its eye, added to every point

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

    def disc(self, centre, normal, up, radius, rect, seg=20):
        c, n, u, rr = frame(centre, normal, up)
        pts, uvs = [], []
        for k in range(seg):
            a = 2 * math.pi * k / seg
            pts.append(c + (rr * math.cos(a) + u * math.sin(a)) * radius)
            uvs.append(uv_of(rect, 0.5 + 0.5 * math.cos(a), 0.5 + 0.5 * math.sin(a)))
        cuv = uv_of(rect, 0.5, 0.5)
        for k in range(seg):
            self.face([c, pts[k], pts[(k + 1) % seg]], [cuv, uvs[k], uvs[(k + 1) % seg]], normal=n)

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
            px = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pts]
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
    """A three-facet instrument panel (centre leaning back, wings turned toward the pilot), eye-relative metres.
    Absolute heights of the tubs: front floor 2.137, sill 2.94, walls at x 0.49; rear floor 2.315."""

    def __init__(self, lean_deg, p0, hinge, wing_deg, wall_end, top_c, top_k, top_h, top_w, bot_c, bot_w):
        self.lean = math.radians(lean_deg)
        self.U_C = np.array([0, math.cos(self.lean), math.sin(self.lean)])
        self.N_C = np.array([0, math.sin(self.lean), -math.cos(self.lean)])
        self.R_C = np.array([-1.0, 0, 0])             # pilot's right
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


# the pilot's panel: its face stands at z 6.07 (bottom) to 6.17 (top, abs) as the airframe's own panel did, topped by the
# deck at 2.98; wings stand on the consoles (top 2.56)
P_FRONT = Panel(8, (0.0, -0.40, 0.473), 0.24, 20, 0.445, -0.14, 0.12, -0.245, -0.43, -0.80, -0.64)
# the back-seater's: tall (the hood and the radar scope), its face at z 4.56 (bottom) to 4.62 (top, abs)
P_REAR = Panel(5, (0.0, -0.45, 0.405), 0.20, 18, 0.46, -0.12, 0.12, -0.16, -0.26, -0.88, -0.66)

GAUGE_LIFT = 0.004
DISPLAY_LIFT = 0.003
RIM = 0.16

# Instrument layouts (the -1's panels): name, facet, a, y (from the eye), spec, description.
# Specs: ('game', Kind, dia) drawn by the game; ('display', Kind, w, style); ('paint_disc', key, dia);
# ('paint_rect', w, h); ('annunciator', w, h)
F_C, F_R, F_L = P_FRONT.centre, P_FRONT.right, P_FRONT.left
LAYOUT_F = [
    ('radar',   F_C,  0.000, -0.280, ('display', 'RADAR', 0.115, 'CRT'), 'radar scope repeater (AN/APQ-100)'),
    ('ias',     F_C, -0.160, -0.430, ('game', 'AIRSPEED', 0.072), 'airspeed / Mach indicator'),
    ('adi',     F_C,  0.000, -0.430, ('game', 'ATTITUDE', 0.088), 'attitude director indicator'),
    ('alt',     F_C,  0.160, -0.430, ('game', 'ALTIMETER', 0.072), 'altimeter'),
    ('aoa',     F_C, -0.170, -0.545, ('game', 'AOA', 0.052), 'angle of attack indicator'),
    ('hsi',     F_C,  0.000, -0.545, ('game', 'HEADING', 0.078), 'horizontal situation indicator'),
    ('vvi',     F_C,  0.165, -0.545, ('game', 'VVI', 0.058), 'vertical velocity indicator'),
    ('gmeter',  F_C, -0.190, -0.645, ('game', 'G_METER', 0.046), 'accelerometer'),
    ('clock',   F_C, -0.095, -0.645, ('game', 'CLOCK', 0.046), 'clock'),
    ('ralt',    F_C,  0.000, -0.645, ('game', 'RADAR_ALT', 0.046), 'radar altimeter'),
    ('turn',    F_C,  0.095, -0.645, ('game', 'TURN_SLIP', 0.046), 'turn and slip'),
    ('fuelq',   F_C,  0.190, -0.645, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
    ('hyd',     F_C, -0.100, -0.745, ('paint_disc', 'HYD', 0.040), 'hydraulic pressure'),
    ('oil',     F_C,  0.100, -0.745, ('paint_disc', 'OIL', 0.040), 'oil pressure'),
    ('arm',     F_L,  0.095, -0.412, ('paint_rect', 0.120, 0.065), 'armament control panel'),
    ('gear',    F_L,  0.095, -0.505, ('paint_rect', 0.120, 0.090), 'landing gear handle and lights'),
    ('util',    F_L,  0.095, -0.587, ('paint_rect', 0.120, 0.036), 'brake / hook / canopy panel'),
    ('warning', F_R,  0.100, -0.418, ('annunciator', 0.130, 0.055), 'warning and caution lights'),
    ('rpm_l',   F_R,  0.160, -0.467, ('game', 'ENGINE_RPM', 0.032), 'left tachometer'),
    ('rpm_r',   F_R,  0.070, -0.467, ('game', 'ENGINE_RPM', 0.032), 'right tachometer'),
    ('egt_l',   F_R,  0.160, -0.505, ('game', 'EGT', 0.032), 'left exhaust gas temperature'),
    ('egt_r',   F_R,  0.070, -0.505, ('game', 'EGT', 0.032), 'right exhaust gas temperature'),
    ('ff_l',    F_R,  0.160, -0.543, ('paint_disc', 'FF', 0.032), 'left fuel flow'),
    ('ff_r',    F_R,  0.070, -0.543, ('paint_disc', 'FF', 0.032), 'right fuel flow'),
    ('noz_l',   F_R,  0.160, -0.581, ('paint_disc', 'NOZ', 0.032), 'left nozzle position'),
    ('noz_r',   F_R,  0.070, -0.581, ('paint_disc', 'NOZ', 0.032), 'right nozzle position'),
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


# dense round dials around the radar repeater (the real panel is packed with them): two columns either side of the scope, a
# row under the engine-stack-height gauges, and a third column between the engine dials on the right wing
LAYOUT_F += _dials('fa', F_C, [(s * 0.140, y) for s in (-1, 1) for y in (-0.215, -0.262, -0.309, -0.356)], 0.030) \
    + _dials('fb', F_C, [(s * 0.197, y) for s in (-1, 1) for y in (-0.240, -0.285, -0.330)], 0.030, 4) \
    + _dials('fc', F_C, [(0.0, -0.745), (-0.195, -0.745), (0.195, -0.745)], 0.036, 2) \
    + _dials('fd', F_R, [(0.115, -0.486), (0.115, -0.524), (0.115, -0.562)], 0.030, 6)

R_C, R_R, R_L = P_REAR.centre, P_REAR.right, P_REAR.left
LAYOUT_R = [
    ('ias',     R_C, -0.135, -0.240, ('game', 'AIRSPEED', 0.070), 'airspeed / Mach indicator'),
    ('adi',     R_C,  0.000, -0.240, ('game', 'ATTITUDE', 0.080), 'attitude indicator'),
    ('alt',     R_C,  0.135, -0.240, ('game', 'ALTIMETER', 0.070), 'altimeter'),
    ('gmeter',  R_C, -0.135, -0.350, ('game', 'G_METER', 0.050), 'accelerometer'),
    ('hsi',     R_C,  0.000, -0.350, ('game', 'HEADING', 0.070), 'horizontal situation indicator'),
    ('vvi',     R_C,  0.135, -0.350, ('game', 'VVI', 0.055), 'vertical velocity indicator'),
    ('radar',   R_C, -0.010, -0.560, ('display', 'RADAR', 0.135, 'CRT'), 'radar scope (AN/APQ-100 / -109)'),
    ('clock',   R_C, -0.150, -0.560, ('game', 'CLOCK', 0.050), 'clock'),
    ('rwr',     R_C,  0.150, -0.560, ('game', 'RWR', 0.062), 'radar homing and warning scope'),
    ('rctl',    R_C,  0.000, -0.770, ('paint_rect', 0.300, 0.070), 'radar mode / range strip'),
    ('rad_a',   R_L,  0.115, -0.320, ('paint_rect', 0.150, 0.130), 'radar control panel (gain, scale, AGC)'),
    ('rad_b',   R_L,  0.115, -0.470, ('paint_rect', 0.150, 0.110), 'radar control panel (acquisition, polarisation)'),
    ('wpn',     R_R,  0.115, -0.320, ('paint_rect', 0.150, 0.130), 'weapons selection and release panel'),
    ('nav',     R_R,  0.115, -0.470, ('paint_rect', 0.150, 0.110), 'navigation / ICS panel'),
]
# the back seat: the hooded scope in the centre with gauges all round it (flanks of the flight-instrument rows, a row under
# the scope's top, a column either side of the hood, a row beneath)
LAYOUT_R[:] = [e for e in LAYOUT_R if e[0] not in ('clock', 'rctl')]
LAYOUT_R += [('clock',   R_C, -0.170, -0.560, ('game', 'CLOCK', 0.044), 'clock'),
             ('rctl',    R_C,  0.000, -0.835, ('paint_rect', 0.300, 0.050), 'radar mode / range strip')]
LAYOUT_R += _dials('ra', R_C, [(s * 0.194, y) for s in (-1, 1) for y in (-0.215, -0.255, -0.295, -0.335)], 0.022) \
    + _dials('rb', R_C, [(a, -0.412) for a in (-0.17, -0.09, 0.0, 0.09, 0.17)], 0.030, 3) \
    + _dials('rc', R_C, [(-0.175, y) for y in (-0.455, -0.497, -0.625, -0.667)], 0.034, 5) \
    + _dials('rd', R_C, [(0.160, y) for y in (-0.455, -0.497, -0.635, -0.675)], 0.034, 1) \
    + _dials('re', R_C, [(a, -0.745) for a in (-0.16, -0.08, 0.0, 0.08, 0.16)], 0.036, 7)
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


def p_arm(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    d.rectangle([S * .05, H * .08, S * .33, H * .50], fill=RED + (255,), outline=(8, 8, 8, 255), width=3)
    text(d, (S * .19, H * .29), 'MASTER', H * .13, fill=(235, 235, 228), bold=True)
    text(d, (S * .19, H * .41), 'ARM', H * .13, fill=(235, 235, 228), bold=True)
    switches(d, S, H, [0.46, 0.60, 0.74, 0.88], 0.40, ['SPARROW', 'SIDEW', 'GUN', 'BOMB'], 'push', H * .10)
    switches(d, S, H, [0.12, 0.30, 0.48, 0.66, 0.84], 0.82, ['SAFE', 'STA', 'FUZE', 'JETT', 'NOSE'], 'tog', H * .09)
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
    switches(d, S, H, [0.16, 0.38, 0.60, 0.82], 0.52, ['BRAKE', 'HOOK', 'CANOPY', 'FLAPS'], 'tog', H * .22)


def p_rctl(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.10, 0.25, 0.40, 0.55, 0.70, 0.85], 0.40, ['STBY', 'SEARCH', 'RANGE', 'MAP', 'TRK', 'TEST'], 'push', H * .20)


def p_rad_a(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.22, 0.50, 0.78], 0.22, ['GAIN', 'AGC', 'BRT'], 'knob', H * .06)
    switches(d, S, H, [0.22, 0.50, 0.78], 0.58, ['SCALE', 'ELEV', 'TILT'], 'knob', H * .06)
    switches(d, S, H, [0.15, 0.38, 0.62, 0.85], 0.90, ['AFC', 'AUTO', 'MAN', 'CHAN'], 'tog', H * .06)


def p_rad_b(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.18, 0.40, 0.62, 0.84], 0.28, ['ACQ', 'POL', 'LIN', 'CIRC'], 'push', H * .09)
    switches(d, S, H, [0.18, 0.50, 0.82], 0.72, ['RECORD', 'ANT', 'PWR'], 'knob', H * .09)


def p_wpn(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.20, 0.50, 0.80], 0.22, [('AIM-7', AMBER), ('AIM-9', GREEN_LAMP), ('GUN', RED)], 'lamp', H * .07)
    switches(d, S, H, [0.18, 0.40, 0.62, 0.84], 0.62, ['SAFE', 'ARM', 'RIP', 'SGL'], 'tog', H * .07)
    text(d, (S * .5, H * .93), 'WEAPONS', H * .07)


def p_nav(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    switches(d, S, H, [0.18, 0.50, 0.82], 0.28, ['ICS', 'VOL', 'TACAN'], 'knob', H * .09)
    switches(d, S, H, [0.18, 0.40, 0.62, 0.84], 0.76, ['INS', 'IFF', 'DATA', 'LIGHT'], 'tog', H * .09)


def p_sight(d, im):
    """the LCOSS head's control face toward the pilot: mode knob, depression readout and knob, brightness, shutter"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .08, H * .10, S * .46, H * .46], fill=(6, 14, 8, 255), outline=(70, 72, 70, 255), width=2)
    text(d, (S * .27, H * .28), '0 2 5', H * .26, fill=GREEN_TXT + (255,), bold=True)
    for x, lab in ((0.62, 'MODE'), (0.84, 'BRT')):
        d.ellipse([S * x - H * .17, H * .28 - H * .17, S * x + H * .17, H * .28 + H * .17], fill=(26, 26, 26, 255),
                  outline=(150, 150, 146, 255), width=3)
        d.line([S * x, H * .28, S * x + H * .08, H * .15], fill=WHITE + (255,), width=3)
        text(d, (S * x, H * .56), lab, H * .09)
    d.rectangle([S * .10, H * .70, S * .46, H * .88], fill=(36, 36, 38, 255), outline=(150, 150, 146, 255), width=2)
    text(d, (S * .28, H * .79), 'DEPRESSION', H * .075)
    d.rectangle([S * .60, H * .72, S * .90, H * .86], fill=(90, 90, 90, 255))
    text(d, (S * .75, H * .79), 'CL   OPEN', H * .075, fill=(10, 10, 10))


def p_dial_static(kind):
    def fn(d, im):
        t, lo, hi, st, ls, val, labs = STATIC_DIALS[kind]
        dial_face(d, im, t, lo, hi, st, ls, val, labels=labs)
    return fn


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


def p_wall(seed):
    def fn(d, im):
        noisy(d, im, PANEL_D, 3, seed=seed)
    return fn


def p_floor(d, im):
    noisy(d, im, FLOOR, 4, seed=77)
    S, H = im.width, im.height
    for k in range(1, 8):
        d.line([0, H * k / 8, S, H * k / 8], fill=(26, 28, 30, 255), width=3)


def p_scope_hood(d, im):
    S, H = im.width, im.height
    noisy(d, im, (14, 14, 15), 3, seed=88)


def p_rear_wall(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 4, seed=70)
    for k in range(4):
        x = S * (0.08 + k * 0.23)
        d.rectangle([x, H * .15, x + S * .18, H * .55], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)
        for j in range(3):
            d.ellipse([x + S * .03 + j * S * .05, H * .3, x + S * .06 + j * S * .05, H * .4], fill=(150, 150, 150, 255))


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


# ---------------------------------------------------------------- build

def add_panel(m, atlas, sw, panel, layout, gauges, displays, seat, hood=False):
    """the three facets with their instruments; appends the game's gauges / displays (vehicle-local, seat index)"""
    ppm = 700
    painters = {'arm': p_arm, 'gear': p_gear, 'util': p_util, 'rctl': p_rctl, 'rad_a': p_rad_a, 'rad_b': p_rad_b,
                'wpn': p_wpn, 'nav': p_nav}
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
    cache = {}
    bar_h_r = atlas.paint(96, 16, bar_h); bar_v_r = atlas.paint(16, 96, bar_v)
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
            rect = cache.get(spec[1]) or atlas.paint(96, 96, p_dial_static(spec[1]))
            cache[spec[1]] = rect
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
            m.frame_rect(c, f.N, f.U, w, w, 0.014, pr, sw['bezel'])
            if hood:                                   # the rubber hood: a tapering tube out toward the back-seater
                d0, d1, depth = w / 2 + 0.016, w / 2 + 0.060, 0.115
                hc = lambda sx, sy, dd: c + r_ * sx + u_ * sy + n_ * dd
                # four slanted walls from the bezel's outer edge (d0) out to the rim (d1)
                corners0 = [(-d0, -d0), (d0, -d0), (d0, d0), (-d0, d0)]
                corners1 = [(-d1, -d1), (d1, -d1), (d1, d1), (-d1, d1)]
                for k in range(4):
                    a0_, a1_ = corners0[k], corners0[(k + 1) % 4]
                    b0_, b1_ = corners1[k], corners1[(k + 1) % 4]
                    quad = [hc(*a0_, pr), hc(*a1_, pr), hc(*b1_, depth), hc(*b0_, depth)]
                    ctr = (a0_[0] + a1_[0]) / 2, (a0_[1] + a1_[1]) / 2
                    m.flat(quad, sw['hood'], toward=hc(ctr[0] * 0.3, ctr[1] * 0.3, depth / 2))
                    inner = [hc(a0_[0] * .93, a0_[1] * .93, pr), hc(a1_[0] * .93, a1_[1] * .93, pr),
                             hc(b1_[0] * .93, b1_[1] * .93, depth), hc(b0_[0] * .93, b0_[1] * .93, depth)]
                    m.flat(inner, sw['hood'], toward=hc(ctr[0] * 3, ctr[1] * 3, depth / 2))
                    m.flat([hc(*b0_, depth), hc(*b1_, depth), hc(b1_[0] * .93, b1_[1] * .93, depth), hc(b0_[0] * .93, b0_[1] * .93, depth)],
                           sw['hood'], normal=n_)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 220 if w >= 0.15 else 160
            ph = max(24, int(round(pw * h / w)))
            rect = cache.get((name, id(m.eye))) or atlas.paint(pw, ph, painters[name])
            cache[(name, id(m.eye))] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])


def add_tub(m, atlas, sw, panel, cfg):
    """consoles, side liners, floor and kick panel for one tub"""
    fy, console_y, sill_y = cfg['floor'], cfg['console'], cfg['sill']
    z_back, con_in, con_out, wall = cfg['back'], cfg['con_in'], cfg['con_out'], cfg['wall']
    wall_end = panel.wall_end
    for sgn in (1, -1):
        zf_w = panel.front_z(sgn * wall_end, console_y)
        liner = atlas.paint(180, 100, p_wall(30 + sgn))
        qs = [[sgn * wall, console_y, z_back], [sgn * wall, console_y, zf_w], [sgn * wall, sill_y, zf_w - 0.02], [sgn * wall, sill_y, z_back]]
        m.flat(qs, sw['liner'], normal=[-sgn, 0, 0])
        xi, xo = con_in * sgn, con_out * sgn
        zf_i, zf_o = panel.front_z(xi, console_y), panel.front_z(xo, console_y)
        con = atlas.paint(64, 340, p_console(sgn, cfg['seed'], shaft=cfg['shaft'] if sgn > 0 else None))
        top = [[xi, console_y, z_back + 0.01], [xo, console_y, z_back + 0.01], [xo, console_y, zf_o], [xi, console_y, zf_i]]
        m.face(top, [uv_of(con, 0, 0), uv_of(con, 1, 0), uv_of(con, 1, 1), uv_of(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, z_back + 0.01], [xi, fy, zf_i], [xi, console_y, zf_i], [xi, console_y, z_back + 0.01]], sw['panel_d'], normal=[-sgn, 0, 0])
        m.flat([[xi, fy, z_back + 0.01], [xo, fy, z_back + 0.01], [xo, console_y, z_back + 0.01], [xi, console_y, z_back + 0.01]], sw['panel_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf_i], [xo, fy, zf_o], [xo, console_y, zf_o], [xi, console_y, zf_i]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xo, fy, z_back + 0.01], [xo, fy, zf_o], [xo, console_y, zf_o], [xo, console_y, z_back + 0.01]], sw['panel_d'], normal=[-sgn, 0, 0])
    zk = panel.centre.at(0, panel.bot_c)[2]
    # floor liner between the consoles (the seat stands on it); the seat's bucket parts stay as they are
    m.flat([[-con_in, fy, cfg['floor_from']], [con_in, fy, cfg['floor_from']], [con_in, fy, zk], [-con_in, fy, zk]], sw['floor'], normal=[0, 1, 0])
    kick = atlas.paint(180, 60, p_wall(71))
    m.quad_tex([[con_in, fy, zk], [-con_in, fy, zk], [-con_in, panel.bot_c, zk], [con_in, panel.bot_c, zk]], kick, normal=[0, 0, -1])
    # rudder pedals: plates tilted toward the pilot, arms to the floor
    for s in (1, -1):
        m.box([s * 0.13, fy + 0.07, cfg['pedal_z']], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.060, 0.010), sw['mgrey'])
        m.box([s * 0.13, fy + 0.03, cfg['pedal_z'] + 0.03], (X, Y, Z), (0.014, 0.030, 0.035), sw['dgrey'])
    # the throttles (left console): quadrant, two levers with grips and thumb switches, speed brake switch
    tz = cfg['throttle_z']
    tx = 0.385

    def lever_mesh():
        lm = Mesh()
        lm.eye = m.eye
        return lm
    # The pilot's two levers are their own bones (throttle_lever_1 / _2) hinged under the console top, modelled at idle
    # (throttle_lib); the back seat's levers are the same shape as fixed mesh.
    levers = throttle_lib.jet_levers(m, lever_mesh, sw, 'f4', x=tx, top_y=console_y, z_idle=tz - 0.07, n=2, pitch=0.050)
    if np.allclose(m.eye, EYE_F):
        THR.clear()
        THR.extend(levers)
    else:
        throttle_lib.make_static(m, levers)
    m.box([tx + 0.075, console_y + 0.020, tz - 0.07], (X, Y, Z), (0.020, 0.012, 0.030), sw['metal'])      # speed brake switch
    # switch rows on both console tops
    for s in (1, -1):
        for zr in cfg['switch_z']:
            if s > 0 and tz - 0.17 < zr < tz + 0.17:
                continue
            for xc in (0.30, 0.33, 0.36, 0.39, 0.42, 0.45):
                m.box([s * xc, console_y + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, console_y + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])), (0.0025, 0.012, 0.0025), sw['black'])


def stick_mesh(m, sw, foot, tall=0.21, lean_back=0.08):
    """a centre stick standing on [foot]: column and grip (hat, trigger, trim thumb switch); returns nothing, adds to [m]"""
    col_ax = (X, unit([0, 1, -lean_back]), unit([0, lean_back, 1]))
    m.box(foot + col_ax[1] * (tall / 2), col_ax, (0.011, tall / 2, 0.011), sw['metal'])
    g_ax = (X, unit([0, 1, -0.20]), unit([0, 0.20, 1]))
    gc = foot + col_ax[1] * tall + g_ax[2] * 0.012 + g_ax[1] * 0.045
    m.box(gc, g_ax, (0.021, 0.060, 0.028), sw['black'])
    m.box(gc + g_ax[1] * 0.062, g_ax, (0.015, 0.006, 0.021), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.0675 + g_ax[2] * 0.009, g_ax, (0.005, 0.004, 0.005), sw['red'])
    m.box(gc + g_ax[2] * 0.031 - g_ax[1] * 0.002, g_ax, (0.007, 0.014, 0.005), sw['metal'])
    m.box(gc + g_ax[1] * 0.020, g_ax, (0.023, 0.010, 0.030), sw['dgrey'])


def build_pilot(m, atlas, sw, gauges, displays, glass):
    m.eye = np.array(EYE_F, float)
    cfg = dict(floor=-0.975, console=-0.60, sill=-0.18, back=-0.32, con_in=0.225, con_out=0.485, wall=0.485,
               seed=0, shaft=(0.30, 0.38, 0.70, 0.68), floor_from=0.30, pedal_z=0.40, throttle_z=0.12,
               switch_z=(-0.28, -0.20, -0.12, 0.30, 0.36))
    add_panel(m, atlas, sw, P_FRONT, LAYOUT_F, gauges, displays, 0)
    add_tub(m, atlas, sw, P_FRONT, cfg)
    # the visor: from the panel's top edge forward to the deck (its rear edge at z 6.2): one flat black strip
    pts = []
    for f, aa in ((F_L, [P_FRONT.wing_len, 0.0]), (F_C, [-P_FRONT.hinge, -P_FRONT.top_k, P_FRONT.top_k, P_FRONT.hinge]), (F_R, [0.0, P_FRONT.wing_len])):
        for a in aa:
            pts.append(f.at(a, f.top(a)))
    uniq = [pts[0]]
    for p in pts[1:]:
        if np.linalg.norm(p - uniq[-1]) > 1e-4:
            uniq.append(p)
    z_wall = 0.600
    lift = np.array([0, 0.004, 0])
    for i in range(len(uniq) - 1):
        a, b = uniq[i], uniq[i + 1]
        m.flat([a + lift, b + lift, np.array([b[0] * 0.86, b[1], z_wall]) + lift, np.array([a[0] * 0.86, a[1], z_wall]) + lift], sw['coaming'], normal=[0, 1, 0])
    # -- the LCOSS: head on the glareshield (control face toward the pilot), two struts to the reflector glass
    sight = atlas.paint(160, 100, p_sight)
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h, g_hw = 0.110, 0.070                      # the reflector plate: a little larger than life
    g_c = np.array([0.0, 0.0, 0.440])             # centred on the neutral eye line
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    base_y = -0.138
    head_top = -0.062
    head_c = np.array([0.0, (base_y + head_top) / 2, 0.462])
    m.box(head_c, (X, Y, Z), (0.056, (head_top - base_y) / 2, 0.052), sw['dgrey'], front=(2, -1, sight, -X, Y))
    m.box([0.0, head_top + 0.009, 0.490], (X, Y, Z), (0.038, 0.009, 0.024), sw['dgrey'])           # the sun shutter housing
    for s in (-1, 1):                              # struts from the head up to the glass frame's lower corners
        m.box([s * (g_hw + 0.004), (head_top + g_bot[1]) / 2 - 0.004, g_bot[2] + 0.004], (X, Y, Z),
              (0.004, max(0.004, (g_bot[1] - head_top) / 2 + 0.006), 0.005), sw['dgrey'])
    m.box(g_bot - g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.005, 0.010), sw['dgrey'])           # lower clamp
    for s in (-1, 1):
        m.box(g_c + X * s * (g_hw + 0.005), (X, g_up, g_n), (0.004, g_h / 2 + 0.002, 0.005), sw['dgrey'])
    m.box(g_top + g_up * 0.003, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    E = m.eye
    corners = [g_bot - X * g_hw, g_bot + X * g_hw, g_top + X * g_hw, g_top - X * g_hw]
    glass += [corners[0] + E, corners[1] + E, corners[2] + E, corners[0] + E, corners[2] + E, corners[3] + E]
    hud = dict(centre=g_c + E, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, eye=E)
    # -- the centre stick (the animated bone's): its foot on the seat front, between the knees
    foot = np.array([0.0, 2.33 - E[1], 5.97 - E[2]])
    stick = Mesh(); stick.eye = m.eye
    stick_mesh(stick, sw, foot)
    m.box(foot + [0, 0.010, 0], (X, Y, Z), (0.045, 0.010, 0.045), sw['dgrey'])                   # base plate
    m.box(foot + [0, 0.035, 0], (X, Y, Z), (0.024, 0.026, 0.024), sw['black'])                   # rubber boot
    return stick, hud, foot + E


def build_rear(m, atlas, sw, gauges, displays):
    m.eye = np.array(EYE_R, float)
    cfg = dict(floor=-0.980, console=-0.66, sill=-0.28, back=-0.50, con_in=0.225, con_out=0.485, wall=0.485,
               seed=5, shaft=(0.30, 0.20, 0.70, 0.55), floor_from=0.20, pedal_z=0.36, throttle_z=0.04,
               switch_z=(-0.35, -0.27, -0.19, 0.20, 0.26))
    add_panel(m, atlas, sw, P_REAR, LAYOUT_R, gauges, displays, 1, hood=True)
    add_tub(m, atlas, sw, P_REAR, cfg)
    # coaming strip over the panel's top edge, back to the bulkhead block between the cockpits (its face at z 4.66)
    pts = []
    for f, aa in ((R_L, [P_REAR.wing_len, 0.0]), (R_C, [-P_REAR.hinge, -P_REAR.top_k, P_REAR.top_k, P_REAR.hinge]), (R_R, [0.0, P_REAR.wing_len])):
        for a in aa:
            pts.append(f.at(a, f.top(a)))
    uniq = [pts[0]]
    for p in pts[1:]:
        if np.linalg.norm(p - uniq[-1]) > 1e-4:
            uniq.append(p)
    z_wall = 4.655 - m.eye[2]
    lift = np.array([0, 0.004, 0])
    for i in range(len(uniq) - 1):
        a, b = uniq[i], uniq[i + 1]
        m.flat([a + lift, b + lift, np.array([b[0], b[1], z_wall]) + lift, np.array([a[0], a[1], z_wall]) + lift], sw['coaming'], normal=[0, 1, 0])
    # rear stick: fixed (the airframe animates the pilot's only)
    foot = np.array([0.0, 2.49 - m.eye[1], 4.52 - m.eye[2]])
    stick_mesh(m, sw, foot, tall=0.16)
    m.box(foot + [0, 0.010, 0], (X, Y, Z), (0.045, 0.010, 0.045), sw['dgrey'])
    m.box(foot + [0, 0.032, 0], (X, Y, Z), (0.024, 0.022, 0.024), sw['black'])


# ---------------------------------------------------------------- seats: face-curtain loops, the bow's side window

BOW_BONE = 'wreck_fuselage_0__bow'      # the bow window's panels: airframe repair, its own bone (not the cockpit's)
BOW_MESH = None
SKIN_UV = [0.34, 0.02]          # the airframe's own grey texel (set from the hull by cut_handles)
# the seat tops' bright handle assembly (a red bar, two chunky yellow rings and a striped post per seat) is cut from the hull
# pieces: boxes on the F-4C's scale around each headbox top; faces whose texel is yellow or red go
HANDLE_BOXES = {'wreck_fuselage_0__hull': ((-0.26, 2.96, 5.10), (0.26, 3.40, 5.50)),
                'wreck_fuselage_1__hull': ((-0.26, 3.13, 3.62), (0.26, 3.58, 4.04))}
# loop centre height (y) and z per seat (just ahead of the headbox cap, under the canopy's crown), F-4C scale
CURTAIN_TOPS = ((EYE_F, 3.300, 5.390), (EYE_R, 3.478, 3.922))
# the window the airframe leaves between the legs of the bow between the two cockpits, rows of (y, z lo, z hi, |x| of the
# bow's outer skin there less 6 mm); closed with a skin-coloured panel on each side
BOW_ROWS = [(3.06, 4.56, 4.82, 0.372), (3.10, 4.56, 4.82, 0.368), (3.20, 4.57, 4.80, 0.356), (3.30, 4.60, 4.77, 0.335),
            (3.40, 4.62, 4.74, 0.303), (3.44, 4.62, 4.72, 0.260)]


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
    """cut the airframe's oversized seat-top handle blocks; remember a grey skin texel for the bow window's panels"""
    global SKIN_UV
    Tf = globals().get('T') or (lambda p: np.asarray(p, float))
    tex = np.asarray(Image.open(TEX).convert('RGB'))
    th, tw = tex.shape[:2]
    cut = 0
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] not in HANDLE_BOXES:
            continue
        lo, hi = (np.array(Tf(p), float) for p in HANDLE_BOXES[b['name']])
        pm = b['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        keep = []
        for p in pm['polys']:
            c = P[[q[0] for q in p]].mean(0)
            if np.all(c >= lo) and np.all(c <= hi):
                uv = U[[q[2] for q in p]].mean(0)
                r, g, bl = (int(v) for v in tex[min(th - 1, int((1 - uv[1]) * th)), min(tw - 1, int(uv[0] * tw))])
                if r > 200 and bl < 90 and (g > 180 or g < 90):
                    cut += 1
                    continue
            keep.append(p)
        pm['polys'] = keep
        cut += drop_orphans(pm, lo, hi)
        if b['name'] == 'wreck_fuselage_0__hull':
            tgt = np.array(Tf((0.40, 3.25, 4.70)), float)
            best = None
            for p in pm['polys']:
                c = P[[q[0] for q in p]].mean(0)
                d = np.linalg.norm(c - tgt)
                uvm = U[[q[2] for q in p]].mean(0)
                px = tex[min(th - 1, int((1 - uvm[1]) * th)), min(tw - 1, int(uvm[0] * tw))]
                if int(px.sum()) > 300 and (best is None or d < best[0]):         # a grey skin texel, not a dark interior one
                    best = (d, uvm)
            SKIN_UV = [float(best[1][0]), float(best[1][1])]
    print(f'seat handles: {cut} bright block faces cut; skin texel {SKIN_UV}')
    return cut


def face_curtains(m, sw):
    """Martin-Baker Mk 7 face-curtain handles: a pair of thin striped (yellow and black) webbing loops lying just ahead of the cap of
    each headbox with the red fitting between them, at real size (a loop about 10 x 9 cm, webbing 2 cm)"""
    tilt = math.radians(10)
    Zt = np.array([0.0, math.sin(tilt), math.cos(tilt)])
    Nt = np.cross(Zt, X)
    for eye, top, zc in CURTAIN_TOPS:
        m.eye = np.array(eye, float)
        c0 = np.array([0.0, top, zc]) - m.eye
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


def bow_infill(m, sw):
    """close the A-shaped window through the bow between the cockpits (it showed the sky from outside and from the back seat)
    with a panel on each side, lying 6 mm inside the bow's sloping skin and wearing the airframe's own grey"""
    m.eye = np.zeros(3)
    for s in (1, -1):
        for r0, r1 in zip(BOW_ROWS[:-1], BOW_ROWS[1:]):
            q = [[s * r0[3], r0[0], r0[1]], [s * r0[3], r0[0], r0[2]], [s * r1[3], r1[0], r1[2]], [s * r1[3], r1[0], r1[1]]]
            m.face(q, [SKIN_UV] * 4, normal=[s, 0.3, 0])
            qi = [[p[0] - s * 0.004, p[1], p[2]] for p in q]
            m.flat(qi, sw['panel_d'], normal=[-s, 0, 0])


def build(atlas):
    m = Mesh()
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                lgrey=LGREY, frame=FRAME, yellow=YELLOW, red=RED, metal=METAL, floor=FLOOR,
                                                bezel=(30, 31, 33), coaming=(20, 21, 22), console=CONSOLE,
                                                hood=(14, 14, 15), liner=(74, 78, 80)).items()}
    gauges, displays, glass = [], [], []
    stick, hud, foot = build_pilot(m, atlas, sw, gauges, displays, glass)
    build_rear(m, atlas, sw, gauges, displays)
    face_curtains(m, sw)
    global BOW_MESH
    BOW_MESH = Mesh()
    bow_infill(BOW_MESH, sw)
    return m, gauges, displays, glass, stick, hud, foot


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


def move_eyes(data):
    """both seats' positions and eyes into the vehicle data (x left, y up, z forward, blocks); each seat position stays
    1.62 below its eye and the dismount point stays level with it; only the seats and eye attachments are touched"""
    for seat, eye in zip(data['Seats'][:2], (EYE_F, EYE_R)):
        ex, ey, ez = eye
        seat['Position'] = [0, round(ey - 1.62, 9), ez]
        data['Attachments'][seat['CameraPos']['EyeAttachment']]['Position'] = [ex, ey, ez]
        seat['DismountInfo']['Position'][2] = ez


# The airframe's regeneration left four fin boxes of the baked front Sparrows under the forward fuselage (two per hull piece,
# |x| 0.556 to 0.869, y 1.2 to 1.51; source units |x| 12.5..19.5, y 17..24, z -79..-68 and -43..-32): cut by box
FIN_BOXES = [((0.55, 1.15, 4.30), (0.88, 1.55, 4.88)), ((0.55, 1.15, 2.65), (0.88, 1.55, 3.28))]       # |x| lo, y lo, z lo / hi


def find_run(mins, maxs, a, c, lo, hi, tol=0.012):
    """the first face of the run of c - a + 1 faces near index a whose bounding box is (lo, hi), or None (the list may
    have shifted: an earlier cut, a regeneration)"""
    n = c - a + 1
    for s in sorted(range(max(0, a - 600), min(len(mins) - n, a + 600) + 1), key=lambda s: abs(s - a)):
        if np.all(np.abs(mins[s:s + n].min(0) - lo) < tol) and np.all(np.abs(maxs[s:s + n].max(0) - hi) < tol):
            return s
    return None


def remove_old(geo):
    """cut the airframe's own panels, consoles and loose boxes (runs of the hull bones' face lists, each found by its bounding
    box) and the four leftover Sparrow fin boxes. A run that is not found stops the script, unless this geo already carries
    the new cockpits (a second run: the runs are gone)."""
    removed = fins = 0
    bones = geo['minecraft:geometry'][0]['bones']
    already = any(b['name'] == BONE and b.get('poly_mesh', {}).get('polys') for b in bones)
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
        for i in sorted(gone):
            V = P[[q[0] for q in pm['polys'][i]]]
            # the boxes' outer skins stay: the faces against the tub walls (|x| 0.49), the rear console boxes' backs
            # (z 3.64, against the bulkhead) and the closing faces ahead of the pilot's panel (z 6.75 and 7.02, under the
            # deck: they keep the tub shut against the nose bay)
            if (np.abs(V[:, 0]) > 0.489).all() or (b['name'] == HULL1 and (V[:, 2] < 3.645).all()) or \
                    (b['name'] == HULL0 and V[:, 2].max() > 6.74 and np.ptp(V[:, 2]) < 0.3):
                gone.discard(i)
        removed += len(gone)
        fin = set()
        for (xlo, ylo, zlo), (xhi, yhi, zhi) in FIN_BOXES:
            ax = np.abs(np.stack([mins[:, 0], maxs[:, 0]]))
            inside = (ax.min(0) >= xlo) & (ax.max(0) <= xhi) & (mins[:, 1] >= ylo) & (maxs[:, 1] <= yhi) & \
                     (mins[:, 2] >= zlo) & (maxs[:, 2] <= zhi) & (np.sign(mins[:, 0]) * np.sign(maxs[:, 0]) >= 0)
            fin.update(int(i) for i in np.where(inside)[0])
        fins += len(fin)
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone and i not in fin]
    return removed, fins

def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    data = json.load(open(DATA, encoding='utf-8'))
    g = geo['minecraft:geometry'][0]
    removed, fins = remove_old(geo)
    cut_handles(geo)
    atlas = Atlas()
    mesh, gauges, displays, glass, stick, hud, foot = build(atlas)
    pm = mesh.poly_mesh()
    bone = next((b for b in g['bones'] if b['name'] == BONE), None)
    if bone is None:
        hull = next(b for b in g['bones'] if b['name'] == 'hull')
        at = next(i for i, b in enumerate(g['bones']) if b['name'] == HULL0) + 1
        bone = {'name': BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        g['bones'].insert(at, bone)
    bone['poly_mesh'] = pm
    bow_b = next((b for b in g['bones'] if b['name'] == BOW_BONE), None)
    if bow_b is None:
        hull_ = next(b for b in g['bones'] if b['name'] == 'hull')
        bow_b = {'name': BOW_BONE, 'parent': 'hull', 'pivot': list(hull_['pivot'])}
        g['bones'].insert(next(i for i, b in enumerate(g['bones']) if b['name'] == BONE) + 1, bow_b)
    bow_b['poly_mesh'] = BOW_MESH.poly_mesh()
    # the pilot's stick: its three bones' pivots to the stick's foot (model pixels)
    foot_px = [round(float(foot[0]) * 16, 5), round(float(foot[1]) * 16, 5), round(-float(foot[2]) * 16, 5)]
    for b in g['bones']:
        if b['name'] in STICK_BONES:
            b['pivot'] = list(foot_px)
        if b['name'] == STICK_BONE:
            b['poly_mesh'] = stick.poly_mesh()
    thr = throttle_lib.add_bones(g['bones'], THR, BONE, EYE_F)
    for s in asset['AircraftRig']['Surfaces']:
        if s['Bone'] in ('control_stick_roll', 'control_stick_pitch'):
            s['Pivot'] = list(foot_px)
    asset['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    old_fd = asset.get('FlightDisplays') or {}
    fd = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    if 'Planform' in old_fd:
        fd['Planform'] = old_fd['Planform']
    asset['FlightDisplays'] = fd
    # the LCOSS: the game's gunsight reticle (style GYRO) on its reflector glass; the F-4C has no head-up display
    asset['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(hud['eye']), 'Style': 'GYRO'}
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        e = np.array(EYE_F)
        c = t.mean(1) - e                                  # (an earlier combiner of this script goes)
        keep = ~((np.abs(c[:, 0]) < 0.12) & (c[:, 1] > -0.09) & (c[:, 1] < 0.09) & (c[:, 2] > 0.38) & (c[:, 2] < 0.50))
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    move_eyes(data)
    print(f'f_4c: {removed} old interior faces and {fins} leftover Sparrow fin faces removed; new cockpits {len(pm["polys"])} faces, {len(gauges)} game gauges, '
          f'{len(displays)} display(s), LCOSS glass {len(glass) // 3} triangles; stick foot {foot_px} px; throttle bones {[(n, p) for n, p, _ in thr]}; '
          f'atlas {atlas.used()} of {sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    write_json(DATA, data, 2)
    fb = json.load(open(FALLBACK, encoding='utf-8'))
    for b in fb['minecraft:geometry'][0]['bones']:
        if b['name'] in STICK_BONES:
            b['pivot'] = list(foot_px)
    write_json(FALLBACK, fb, None, newline=False)
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])
