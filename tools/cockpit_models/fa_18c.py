#!/usr/bin/env python3
"""F/A-18C Hornet (Lot 20, late) cockpit, built by hand for the FA-18C.mtb airframe (as mig_29.py, f_16c.py and f_15c.py):
one standalone script.

The airframe (commit 54e9fb02) is an open tub: a Martin-Baker Mk 14 (NACES, SJU-17) seat, two consoles, a dash with the old HUD
posts and a textured panel, all in a rough low-poly layout, no canopy and no glass. This script cuts exactly that loose interior
out of the two hull pieces (the small parts standing in the tub between the rear wall and the dash wall: the seat, the pedestal
and footwell pieces, the HUD posts, the box-like consoles; the walls, floor, the rear wall, the sill lips, the nose and the dash
wall with its glareshield deck are kept, so the exterior stays as it was) and puts a modelled cockpit in its place, laid out
after the Hornet's panel (DCS F/A-18C Lot 20 manual, the C's photographs):

  * the main instrument panel in three flat facets (the centre 7 degrees back, the wings turned 22 degrees toward the pilot,
    the tops following the dash's glareshield): the UFC (up-front control, scratchpad and option windows) under the HUD, the
    left and right DDIs (monochrome green CRTs) either side below it with the standby attitude indicator between them, the
    standby airspeed and altimeter, the centre MPCD (colour) under them, the engine fuel display (IFEI) and the engine dials
    under the right DDI, accelerometer and clock under the left one, the master arm panel and the landing gear handle on the
    left wing, the caution panel and threat scope on the right;
  * the HUD on the glareshield: one unit with its control strip low on the pilot's face, two side posts, and the single combiner
    glass (added to CanopyGlass) centred on the neutral eye line, slightly larger than life; the three-light AoA indexer
    stands on the glareshield left of it;
  * side consoles (the two throttles on the left, the utility panels on the right), liners, floor, kick panel with the
    centre stick's boot, rudder pedals;
  * a NACES seat reclined 18 degrees at its real place: pan, survival kit, guide rails, back, the wide headbox with its side
    cheeks, the single yellow-and-black lower ejection handle between the knees, harness, a rear bulkhead with the breaker
    panel behind it.

The seat and eye are moved to match (the old model's sill was level with the pilot's eye): the eye to (0, 3.00, 4.22), 0.27
above the sill's crest as in a real Hornet.

Paint lives in a free area of textures/entity/fa_18c.png (the top part: no UV of the model, the native fallback or the wreck
lands there; the burnt copy goes to entity_dead). The instruments the game draws are written to CockpitGauges and
FlightDisplays, the HUD picture to HeadUpDisplay; the stick bones' pivots (geo, the native fallback and AircraftRig) go to
the new foot of the centre stick, and the seat and eye attachments are written into the vehicle data.

Order: git checkout the airframe's files, run canopy_fa_18c.py, then this script (it appends the HUD combiner glass to the
canopy's CanopyGlass). Running it again on the airframe's files rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is
written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/fa_18c.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
from PIL import Image, ImageDraw, ImageFont
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/fa_18c.geo.json')
FALLBACK = os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback/fa_18c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/fa_18c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/fa_18c.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/fa_18c.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/fa_18c.png')

BONE = 'wreck_fuselage_1__cockpit'
THR = []                                 # the throttle levers (throttle_lib), one bone each
SOURCE_BONES = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
STICK_BONES = ('control_stick_roll', 'control_stick_pitch', 'wreck_fuselage_1__control_stick_pitch')
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'
ATLAS = [(16, 160, 4064, 1880)]        # free texture area (x, y, w, h): no UV of the model, its fallback or the wreck lands here
TW, TH = 4096, 6144

EYE_NEW = (0.0, 3.00, 4.22)            # the pilot's eye (vehicle data); the seat's reference point sits 1.788 below it


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

# ---------------------------------------------------------------- colours

PANEL = (70, 73, 77)              # the Hornet's dark grey panel face
PANEL_D = (36, 38, 41)            # edges, wing backs, console sides
BLACK = (18, 19, 21)
DGREY = (40, 42, 45)
MGREY = (88, 92, 94)
LGREY = (150, 154, 156)
FRAME = (62, 66, 68)              # NACES structure
CUSHION = (58, 62, 54)
HEADPAD = (92, 96, 90)
SEATGREY = (96, 100, 102)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (40, 43, 45)
CONSOLE = (52, 54, 57)            # the consoles are a shade darker than the panel
WHITE = (226, 228, 220)
GREEN_LAMP = (60, 150, 70)
AMBER = (214, 140, 30)
GREEN_TXT = (80, 220, 100)
STRAP = (52, 58, 48)


# ---------------------------------------------------------------- texture atlas

class Atlas:
    """Shelf-packed rectangles in the free texture area. paint(w, h, fn) -> rect; fn(draw, img) paints an RGBA tile
    at SS x resolution, downsampled for clean edges."""
    SS = 4

    def __init__(self):
        self.free = [tuple(a) for a in ATLAS]
        self.tiles = []                      # (rect, image)

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

EYE = np.zeros(3)                           # set in main(): the mesh is built eye-relative


class Mesh:
    def __init__(self):
        self.faces = []                      # (points k x 3 vehicle-local, uvs k x 2, normal 3)

    def face(self, pts, uvs, toward=None, normal=None):
        P = np.array(pts, float) + EYE
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
            want = np.array(toward, float) + EYE - P.mean(0)
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

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# Absolute heights of the airframe: floor top 2.060 (liner 2.068), the sill lips' crest 2.62 to 2.77 (falling toward the front),
# the tub walls at x 0.55 (the lips lean in to 0.45 at the crest), the rear wall at z 4.03 (the old seat stood against it), the
# dash wall at z 4.88 (top 2.86 over the middle, falling to 2.59 at the walls, the glareshield deck ahead of it); the eye at
# y 3.00, z 4.22 (the crown 0.31 over it, the windscreen arch's rear face 0.66 ahead).

FLOOR_Y = -0.932                # the floor liner, 8 mm over the old floor (abs 2.068)
SILL_Y = -0.420                 # the liners' top (abs 2.58, under the lips' crest)
CONSOLE_Y = -0.580              # console tops (abs 2.42)
CON_IN, CON_OUT = 0.230, 0.500  # the consoles' inner side and outer edge
BACK_Z = -0.185                 # the rear bulkhead panel (abs 4.035, 5 mm ahead of the hull's own wall)

LEAN = math.radians(7)          # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])    # pilot's right
P0 = np.array([0.0, -0.69, 0.570])
HINGE = 0.320                   # centre facet half width
WING = math.radians(22)         # wings turned toward the pilot
WALL_END = 0.450                # wings end here (the liner)
WING_LEN = (WALL_END - HINGE) / math.cos(WING)
TOP_C = -0.140                  # centre facet top over the middle (abs 2.86, the dash wall's top)
TOP_K = 0.180                   # ... out to here, then falling to the hinge
TOP_H, TOP_W = -0.170, -0.400   # wing top at the hinge and at the wall (abs 2.83 and 2.60: the dash wall's own edge)
BOT_C = -0.700
BOT_W = CONSOLE_Y               # wings stand on the consoles


class Facet:
    def __init__(self, side):
        self.side = side                    # 0 centre, +1 right wing, -1 left wing
        if side == 0:
            self.R, self.N = R_C, N_C
        else:
            self.R = R_C * side * math.cos(WING) + N_C * math.sin(WING)
            self.N = N_C * math.cos(WING) - R_C * side * math.sin(WING)
        self.U = U_C

    def at(self, a, y, lift=0.0):
        """panel point: a = metres right of the centre line (centre) or along the wing from the hinge (wings)"""
        v = (y - P0[1]) / math.cos(LEAN)
        if self.side == 0:
            p = P0 + R_C * a + U_C * v
        else:
            p = P0 + R_C * (self.side * HINGE) + U_C * v + self.R * a
        return p + self.N * lift

    def top(self, a):
        if self.side == 0:
            return TOP_C + (TOP_H - TOP_C) * max(0.0, abs(a) - TOP_K) / (HINGE - TOP_K)
        return TOP_H + (TOP_W - TOP_H) * a / WING_LEN

    def bottom(self):
        return BOT_C if self.side == 0 else BOT_W

    def extent(self):
        return (-HINGE, HINGE) if self.side == 0 else (0.0, WING_LEN)

    def knots(self):
        """the breakpoints of the top edge"""
        if self.side == 0:
            return [-HINGE, -TOP_K, TOP_K, HINGE]
        return [0.0, WING_LEN]


CENTRE, RIGHT, LEFT = Facet(0), Facet(1), Facet(-1)

# Instrument layout of the Hornet's panel (DCS F/A-18C manual, the C's photographs, in metres: x right of the centre line,
# y from the eye). Positions: facet, a (see Facet.at), y (height from the eye). Sizes: dial diameter, or w x h. 'game': drawn
# by the game.
LAYOUT = [
    ('ufc',     CENTRE,  0.000, -0.215, ('paint_rect', 0.150, 0.075), 'up-front control (UFC) under the HUD'),
    ('ddi_l',   CENTRE, -0.190, -0.330, ('display', 'RADAR', 0.130, 'CRT'), 'left digital display indicator (DDI, green CRT)'),
    ('ddi_r',   CENTRE,  0.190, -0.330, ('display', 'STORES', 0.130, 'CRT'), 'right DDI (green CRT)'),
    ('sadi',    CENTRE,  0.000, -0.318, ('game', 'ATTITUDE', 0.066), 'standby attitude indicator'),
    ('sias',    CENTRE, -0.050, -0.405, ('game', 'AIRSPEED', 0.046), 'standby airspeed'),
    ('salt',    CENTRE,  0.050, -0.405, ('game', 'ALTIMETER', 0.046), 'standby altimeter'),
    ('mpcd',    CENTRE,  0.000, -0.545, ('display', 'PFD', 0.140, 'LCD'), 'multipurpose colour display (MPCD)'),
    ('gmeter',  CENTRE, -0.240, -0.510, ('game', 'G_METER', 0.058), 'accelerometer'),
    ('clock',   CENTRE, -0.158, -0.510, ('game', 'CLOCK', 0.050), 'clock'),
    ('aoa',     CENTRE, -0.200, -0.625, ('game', 'AOA', 0.050), 'angle of attack'),
    ('ifei',    CENTRE,  0.190, -0.520, ('paint_rect', 0.090, 0.140), 'engine fuel display (IFEI)'),
    ('rpm',     CENTRE,  0.150, -0.645, ('game', 'ENGINE_RPM', 0.044), 'engine rpm'),
    ('ftit',    CENTRE,  0.195, -0.645, ('game', 'EGT', 0.044), 'FTIT'),
    ('fuelq',   CENTRE,  0.240, -0.645, ('game', 'FUEL_QTY', 0.044), 'fuel quantity'),
    ('arm',     LEFT,    0.070, -0.440, ('paint_rect', 0.084, 0.062), 'master arm and jettison'),
    ('gear',    LEFT,    0.070, -0.535, ('paint_rect', 0.084, 0.066), 'landing gear handle and lights'),
    ('warning', RIGHT,   0.070, -0.440, ('annunciator', 0.084, 0.062), 'caution panel'),
    ('tews',    RIGHT,   0.070, -0.535, ('game', 'RWR', 0.056), 'threat warning scope'),
]

GAUGE_LIFT = 0.004
DISPLAY_LIFT = 0.003
RIM = 0.16

# the caution lamps (the same set the F-16C's and F-15C's panels carry; the conditions are the game's)
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


def panel_paint(facet, rect_px):
    """the facet face: grey, seams, screws and the dark recesses behind every instrument"""
    a0, a1 = facet.extent()
    W, H = rect_px
    y_bot = facet.bottom(); y_top = max(facet.top(a) for a in facet.knots())
    SS = Atlas.SS

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
        for name, f, a, y, spec, _ in LAYOUT:
            if f is not facet:
                continue
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(14, 17, 18, 255))
            elif kind in ('paint_rect', 'display', 'annunciator'):
                if kind == 'display':
                    w = h = spec[2] + 0.060
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (y_top - y_bot) * H * SS * math.cos(LEAN)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(14, 17, 18, 255))
        if facet.side == 0:
            yl = to_px(0, -0.700 + 0.018)[1]
            d.line([0, yl, W * SS, yl], fill=PANEL_D + (255,), width=SS * 2)
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_ufcd(d, im):
    """the UFCD: a screen with its scratchpad and COMM lines, an option-key column each side, the knobs below"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .17, H * .07, S * .83, H * .72], fill=(6, 14, 8, 255), outline=(70, 72, 70, 255), width=3)
    text(d, (S * .27, H * .20), 'COMM 1', H * .11, fill=GREEN_TXT + (255,))
    text(d, (S * .68, H * .20), 'COMM 2', H * .11, fill=GREEN_TXT + (255,))
    text(d, (S * .27, H * .38), '127.500', H * .15, fill=GREEN_TXT + (255,), bold=True)
    text(d, (S * .70, H * .38), '251.000', H * .15, fill=GREEN_TXT + (255,), bold=True)
    text(d, (S * .5, H * .58), 'MAN   GRCV   G   CHAN', H * .09, fill=GREEN_TXT + (255,))
    for k in range(5):
        y = H * (0.09 + k * 0.13)
        d.rectangle([S * .03, y, S * .13, y + H * .09], fill=(140, 142, 140, 255), outline=(10, 10, 10, 255), width=2)
        d.rectangle([S * .87, y, S * .97, y + H * .09], fill=(140, 142, 140, 255), outline=(10, 10, 10, 255), width=2)
    for k, lab in enumerate(['BRT', 'COM1', 'COM2', 'SYM', 'IFF']):
        x = S * (0.10 + k * 0.20)
        d.ellipse([x - H * .10, H * .78, x + H * .10, H * .98], fill=(40, 40, 42, 255), outline=(150, 150, 146, 255), width=3)
        d.line([x, H * .88, x + H * .035, H * .81], fill=WHITE + (255,), width=4)


def p_ifei(d, im):
    """the engine fuel display (IFEI): a green-on-black block, RPM / TEMP / FF per engine, fuel and bingo, clock and time"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(12, 13, 14, 255))
    d.rectangle([S * .05, H * .04, S * .95, H * .96], fill=(4, 12, 6, 255), outline=(70, 72, 70, 255), width=3)
    g = GREEN_TXT + (255,)
    text(d, (S * .50, H * .10), 'RPM', H * .06, fill=g)
    text(d, (S * .22, H * .10), '86', H * .09, fill=g, bold=True); text(d, (S * .78, H * .10), '86', H * .09, fill=g, bold=True)
    text(d, (S * .50, H * .24), 'TEMP', H * .06, fill=g)
    text(d, (S * .22, H * .24), '640', H * .09, fill=g, bold=True); text(d, (S * .78, H * .24), '640', H * .09, fill=g, bold=True)
    text(d, (S * .50, H * .38), 'FF', H * .06, fill=g)
    text(d, (S * .22, H * .38), '2400', H * .09, fill=g, bold=True); text(d, (S * .78, H * .38), '2450', H * .09, fill=g, bold=True)
    text(d, (S * .50, H * .52), 'OIL', H * .06, fill=g)
    text(d, (S * .22, H * .52), '75', H * .09, fill=g, bold=True); text(d, (S * .78, H * .52), '75', H * .09, fill=g, bold=True)
    text(d, (S * .50, H * .66), 'FUEL', H * .06, fill=g)
    text(d, (S * .50, H * .76), '13600', H * .11, fill=g, bold=True)
    text(d, (S * .26, H * .88), 'BINGO 3000', H * .06, fill=g)
    text(d, (S * .74, H * .88), '14:32:08', H * .06, fill=g)


def p_hud_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['BRT', 'CONT', 'AUTO', 'DAY', 'NITE']):
        x = S * (0.11 + k * 0.195)
        if k in (0, 1):
            d.ellipse([x - H * .20, H * .30 - H * .20, x + H * .20, H * .30 + H * .20], fill=(18, 18, 18, 255),
                      outline=(120, 120, 120, 255), width=3)
        else:
            d.rectangle([x - H * .26, H * .10, x + H * .26, H * .46], fill=(150, 150, 150, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (x, H * .70), lab, H * .2)


def p_aoa_indexer(d, im):
    """the AoA indexer: a donut, a chevron and a bar in a column (the carrier approach lights)"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 14, 15, 255))
    d.ellipse([S * .16, H * .06, S * .84, H * .06 + S * .68], fill=(40, 140, 60, 255), outline=(8, 8, 8, 255), width=3)
    d.ellipse([S * .34, H * .06 + S * .18, S * .66, H * .06 + S * .50], fill=(14, 14, 15, 255))
    d.polygon([(S * .5, H * .43), (S * .84, H * .43 + S * .30), (S * .16, H * .43 + S * .30)], fill=(210, 160, 30, 255))
    d.rectangle([S * .22, H * .78, S * .78, H * .90], fill=(200, 40, 34, 255))


def p_arm(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .06, H * .10, S * .30, H * .52], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .18, H * .31), 'ARM', H * .18, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['SAFE', 'AUX', 'JETT']):
        x = S * (0.50 + k * 0.22)
        d.rectangle([x - 6, H * .16, x + 6, H * .48], fill=(176, 176, 176, 255))
        d.ellipse([x - 12, H * .08, x + 12, H * .26], fill=(206, 206, 206, 255))
        text(d, (x, H * .62), lab, H * .09)
    text(d, (S * .5, H * .90), 'MASTER ARM', H * .12)


def p_gear(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.ellipse([S * .08, H * .12, S * .44, H * .62], fill=(206, 206, 202, 255), outline=(10, 10, 10, 255), width=3)
    d.ellipse([S * .20, H * .27, S * .32, H * .47], fill=(30, 30, 30, 255))
    text(d, (S * .24, H * .80), 'LDG GEAR', H * .09)
    for k, (x, y) in enumerate([(0.70, 0.22), (0.58, 0.52), (0.82, 0.52)]):
        d.ellipse([S * x - 10, H * y - 10, S * x + 10, H * y + 10], fill=(40, 130, 56, 255), outline=(10, 10, 10, 255), width=3)
    d.rectangle([S * .56, H * .70, S * .84, H * .92], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .70, H * .81), 'HOOK', H * .09, fill=(235, 235, 228))


def p_console(side):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, CONSOLE, 3, seed=20 + side)
        rng = np.random.default_rng(40 + side)
        y = H * 0.02
        heights = [0.12, 0.10, 0.14, 0.09, 0.15, 0.10, 0.12, 0.09] if side > 0 else \
                  [0.10, 0.14, 0.09, 0.13, 0.11, 0.12, 0.13, 0.09]
        for k, hgt in enumerate(heights):
            y1 = y + hgt * H
            d.rectangle([S * .06, y + 4, S * .94, y1 - 4], fill=(20, 22, 22, 255))
            n = int(rng.integers(2, 5))
            for j in range(n):
                x = S * (0.15 + 0.7 * (j + 0.5) / n); yy = (y + y1) / 2
                if rng.random() < 0.5:
                    d.ellipse([x - 12, yy - 12, x + 12, yy + 12], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
                else:
                    d.rectangle([x - 5, yy - 16, x + 5, yy + 6], fill=(180, 180, 180, 255))
            y = y1
        if side > 0:                      # the throttles' slot on the left console
            d.rectangle([S * .30, H * .36, S * .70, H * .66], fill=(8, 8, 8, 255))
    return fn


def p_cushion(colour, straps=False):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 5, seed=50)
        for k in range(1, 4):
            d.line([0, H * k / 4, S, H * k / 4], fill=tuple(c - 16 for c in colour) + (255,), width=4)
        if straps:
            for x in (S * .28, S * .72):
                d.rectangle([x - S * .05, 0, x + S * .05, H], fill=STRAP + (255,))
            d.rectangle([S * .38, H * .55, S * .62, H * .70], fill=(150, 150, 150, 255))
    return fn


def p_stripes(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=YELLOW + (255,))
    for k in range(-H, S, 24):
        d.polygon([(k, H), (k + 12, H), (k + 12 + H, 0), (k + H, 0)], fill=(20, 20, 20, 255))


def p_headbox(d, im):
    S, H = im.width, im.height
    noisy(d, im, SEATGREY, 4, seed=60)
    d.rectangle([S * .1, H * .1, S * .9, H * .9], outline=(50, 52, 50, 255), width=6)
    text(d, (S * .5, H * .38), 'MARTIN-BAKER', H * .13, fill=(30, 30, 30), bold=True)
    text(d, (S * .5, H * .62), 'NACES', H * .22, fill=(30, 30, 30), bold=True)


def p_bulkhead(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 4, seed=70)
    for k in range(4):
        x = S * (0.08 + k * 0.23)
        d.rectangle([x, H * .15, x + S * .18, H * .55], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)
        for j in range(3):
            d.ellipse([x + S * .03 + j * S * .05, H * .3, x + S * .06 + j * S * .05, H * .4], fill=(150, 150, 150, 255))
    d.rectangle([S * .08, H * .66, S * .92, H * .8], fill=(30, 32, 33, 255))


def p_kick(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=71)
    for k in range(3):
        d.rectangle([S * (.10 + k * .30), H * .30, S * (.34 + k * .30), H * .70], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)


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

def build(atlas):
    m = Mesh()
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                lgrey=LGREY, frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED,
                                                metal=METAL, floor=FLOOR, bezel=(26, 27, 29), strap=STRAP,
                                                seatgrey=SEATGREY, coaming=(16, 17, 18), console=CONSOLE).items()}
    gauges, displays, glass = [], [], []

    # -- main panel facets (front face painted, slab 2 cm deep)
    ppm = 900
    for f in (CENTRE, LEFT, RIGHT):
        a0, a1 = f.extent()
        y_top = max(f.top(a) for a in f.knots())
        W = int(round((a1 - a0) * ppm)); Hh = int(round((y_top - f.bottom()) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W, Hh, panel_paint(f, (W, Hh)))
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

    # -- instruments
    cache = {}
    painters = {'ufc': p_ufcd, 'arm': p_arm, 'gear': p_gear, 'ifei': p_ifei}
    bar_h_r = atlas.paint(96, 16, bar_h); bar_v_r = atlas.paint(16, 96, bar_v)
    for name, f, a, y, spec, label in LAYOUT:
        kind = spec[0]
        c = f.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + EYE + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c, f.N, f.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'], back=0.0)
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + EYE + f.N * 0.004), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS,
                           'Lamps': LAMPS})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 0, 'Center': r5(c + EYE + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': spec[3]})
            _, n_, u_, r_ = frame(c, f.N, f.U)
            pr = 0.010
            # a flat bezel with a row of option keys along each edge (the DDIs and the large display alike)
            t = 0.022
            m.rect_tex(c + u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
            m.rect_tex(c - u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
            m.rect_tex(c + r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
            m.rect_tex(c - r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
            for sgn in (-1, 1):                               # the bezel's outer walls
                m.box(c + u_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (w / 2 + t, 0.0015, (pr - 0.002) / 2), sw['black'])
                m.box(c + r_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (0.0015, w / 2 + t, (pr - 0.002) / 2), sw['black'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 240 if w >= 0.14 else 160 if w >= h else 120 if name == 'ifei' else 80
            ph = max(16, int(round(pw * h / w)))
            rect = cache.get(name) or atlas.paint(pw, ph, painters[name])
            cache[name] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])

    # -- the visor lip: from the panel's top edge forward to the HUD housing / the dash wall (z 4.88 abs), one flat black strip,
    # so the panel meets the old glareshield with no gap
    cs = sw['coaming']
    pts = []
    for f, aa in ((LEFT, [WING_LEN, 0.0]), (CENTRE, [-HINGE, -TOP_K, TOP_K, HINGE]), (RIGHT, [0.0, WING_LEN])):
        for a in aa:
            pts.append(f.at(a, f.top(a)))
    uniq = [pts[0]]
    for p in pts[1:]:
        if np.linalg.norm(p - uniq[-1]) > 1e-4:
            uniq.append(p)
    pts = uniq
    fwd = [np.array([p[0], p[1], 0.70 if abs(p[0]) <= HINGE + 0.01 else 0.665]) for p in pts]      # (shorter over the wings: the deck falls away there)
    lift = np.array([0, 0.004, 0])             # (a visor: 4 mm over the panel's top edge, so the two never share a plane)
    for i in range(len(pts) - 1):
        m.flat([pts[i] + lift, pts[i + 1] + lift, fwd[i + 1] + lift, fwd[i] + lift], cs, normal=[0, 1, 0])

    # -- the HUD: one unit. The Hornet's HUD (Kaiser wide-angle unit, DCS F/A-18C Lot 20 and the C's photographs): ONE combiner
    # glass leaning far back toward the pilot, a rectangle that does not taper (unlike the F-16's), straight sides, the top
    # corners cut off, its bottom edge in the housing; two uprights stand beside it on the housing. Here ~15% larger than life,
    # centred on the neutral eye line (the glass centre at the eye's height, 0.70 ahead), in a slim mitred bezel; the housing
    # has its control strip low on the pilot's face (5 cm, below the glass, never covering it)
    hz0, hz1, hy0, hy1, hhw = 0.700, 0.850, -0.160, -0.108, 0.140
    ctrl = atlas.paint(200, 46, p_hud_ctrl)
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    lean = math.radians(45)                                    # the real combiner leans ~50 degrees from the vertical
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))                              # (away from the pilot)
    g_h = 0.310                                                # slant length of the glass
    g_bot = np.array([0, -0.112, 0.805])
    g_top = g_bot + g_up * g_h
    # outline (half width, height) from the bottom edge: straight sides, top corners cut off
    OUTLINE = [(0.115, 0.0), (0.115, 0.272), (0.080, 0.310)]
    poly2 = [(u, v) for u, v in ([(-OUTLINE[0][0], 0.0), (OUTLINE[0][0], 0.0)] + [(u, v) for u, v in OUTLINE[1:]]
                                 + [(-u, v) for u, v in OUTLINE[:0:-1]])]
    q3 = lambda u, v, d=0.0: g_bot + X * u + g_up * v + g_n * d
    glass_pts = [q3(u, v) for u, v in poly2]
    for k in range(1, len(glass_pts) - 1):
        glass += [glass_pts[0] + EYE, glass_pts[k] + EYE, glass_pts[k + 1] + EYE]
    # the bezel: a mitred ring round the glass (1.4 cm wide, 1.2 cm deep, centred on the glass plane); the bottom edge sits
    # in the housing and is left open
    rim, dep = 0.014, 0.006
    nP = len(poly2)
    outn = []
    for i in range(nP):
        a, b = np.array(poly2[i]), np.array(poly2[(i + 1) % nP])
        e = b - a; e /= np.linalg.norm(e)
        outn.append(np.array([e[1], -e[0]]))                  # (the polygon runs counter-clockwise: right of the edge is out)
    outer2 = []
    for i in range(nP):
        n1, n2 = outn[i - 1], outn[i]
        outer2.append(np.array(poly2[i]) + (n1 + n2) / (1 + n1 @ n2) * rim)
    for i in range(1, nP):                                     # (edge 0 is the bottom edge)
        j = (i + 1) % nP
        a, b = q3(*poly2[i]), q3(*poly2[j])
        a2, b2 = q3(*outer2[i]), q3(*outer2[j])
        on = X * outn[i][0] + g_up * outn[i][1]
        m.flat([a - g_n * dep, b - g_n * dep, b2 - g_n * dep, a2 - g_n * dep], sw['dgrey'], normal=-g_n)
        m.flat([a + g_n * dep, b + g_n * dep, b2 + g_n * dep, a2 + g_n * dep], sw['dgrey'], normal=g_n)
        m.flat([a2 - g_n * dep, b2 - g_n * dep, b2 + g_n * dep, a2 + g_n * dep], sw['dgrey'], normal=on)
        m.flat([a - g_n * dep, b - g_n * dep, b + g_n * dep, a + g_n * dep], sw['dgrey'], normal=-on)
    # the two side posts: short slim brackets standing on the housing beside the bezel's foot, leaning with the glass, 9 cm
    # (owner 2026-10-23: shortened; nothing stands beside the glass above them)
    for s in (-1, 1):
        base = q3(s * (0.115 + rim + 0.012), 0.0)
        m.box(base + g_up * 0.045, (X, g_up, g_n), (0.006, 0.045, 0.007), sw['dgrey'])
    hud = dict(centre=q3(0, 0.155) + EYE, normal=-g_n, up=g_up, width=0.200, height=0.200)
    # the AoA indexer: a small lit column on the glareshield, left of the HUD
    aoa_r = atlas.paint(40, 100, p_aoa_indexer)
    ax_, ay_, az_ = 0.215, -0.105, 0.765
    m.box([ax_, ay_ + 0.0, az_], (X, Y, Z), (0.017, 0.044, 0.012), sw['dgrey'], front=(2, -1, aoa_r, -X, Y))

    # -- side walls' liners, consoles, floor, rear bulkhead, kick panel
    fy = FLOOR_Y
    z_back = BACK_Z
    bulk = atlas.paint(180, 100, p_bulkhead)
    kick = atlas.paint(180, 60, p_kick)

    def panel_front_z(x, y):
        """z of the panel's face (whichever facet stands there) at |x|, height y"""
        ax_ = abs(x)
        if ax_ <= HINGE:
            return CENTRE.at(0, y)[2]
        wing = LEFT if x > 0 else RIGHT
        return wing.at((ax_ - HINGE) / math.cos(WING), y)[2]

    for sgn in (1, -1):
        # liner above the console, up the wall to the sill
        zf_w = panel_front_z(sgn * WALL_END, CONSOLE_Y)
        qs = [[sgn * WALL_END, CONSOLE_Y, z_back], [sgn * WALL_END, CONSOLE_Y, zf_w],
              [sgn * WALL_END, SILL_Y, zf_w - 0.02], [sgn * WALL_END, SILL_Y, z_back]]
        m.flat(qs, sw['panel_d'], normal=[-sgn, 0, 0])
        # console: top, inner side, front, back
        xi, xo = CON_IN * sgn, CON_OUT * sgn
        zf_i, zf_o = panel_front_z(xi, CONSOLE_Y), panel_front_z(xo, CONSOLE_Y)
        con = atlas.paint(64, 340, p_console(sgn))
        top = [[xi, CONSOLE_Y, z_back + 0.01], [xo, CONSOLE_Y, z_back + 0.01], [xo, CONSOLE_Y, zf_o], [xi, CONSOLE_Y, zf_i]]
        m.face(top, [uv_of(con, 0, 0), uv_of(con, 1, 0), uv_of(con, 1, 1), uv_of(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, z_back + 0.01], [xi, fy, zf_i], [xi, CONSOLE_Y, zf_i], [xi, CONSOLE_Y, z_back + 0.01]], sw['panel_d'],
               normal=[-sgn, 0, 0])
        m.flat([[xi, fy, z_back + 0.01], [xo, fy, z_back + 0.01], [xo, CONSOLE_Y, z_back + 0.01], [xi, CONSOLE_Y, z_back + 0.01]],
               sw['panel_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf_i], [xo, fy, zf_o], [xo, CONSOLE_Y, zf_o], [xi, CONSOLE_Y, zf_i]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xo, fy, z_back + 0.01], [xo, fy, zf_o], [xo, CONSOLE_Y, zf_o], [xo, CONSOLE_Y, z_back + 0.01]], sw['panel_d'],
               normal=[-sgn, 0, 0])
    # floor liner, rear bulkhead and the kick panel under the panel
    zk = CENTRE.at(0, BOT_C)[2]
    m.flat([[-CON_IN, fy, z_back], [CON_IN, fy, z_back], [CON_IN, fy, zk], [-CON_IN, fy, zk]], sw['floor'], normal=[0, 1, 0])
    m.quad_tex([[CON_OUT, fy, z_back], [-CON_OUT, fy, z_back], [-CON_OUT, -0.240, z_back], [CON_OUT, -0.240, z_back]], bulk,
               normal=[0, 0, 1])
    m.quad_tex([[CON_IN, fy, zk], [-CON_IN, fy, zk], [-CON_IN, BOT_C, zk], [CON_IN, BOT_C, zk]], kick, normal=[0, 0, -1])
    # rudder pedals (arms to the floor, the plates tilted toward the pilot)
    for s in (1, -1):
        m.box([s * 0.13, -0.80, 0.50], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, -0.86, 0.54], (X, Y, Z), (0.014, 0.060, 0.045), sw['dgrey'])

    # -- the two throttles (left console): the quadrant, two levers with their grips and thumb switches
    # Each lever is its own bone (throttle_lever_1 / _2) hinged under the console top, modelled at idle (throttle_lib).
    tz = 0.22
    THR.clear()
    THR.extend(throttle_lib.jet_levers(m, Mesh, sw, 'f18', x=0.375, top_y=CONSOLE_Y, z_idle=tz - 0.05, n=2, pitch=0.055))
    m.box([0.440, CONSOLE_Y + 0.020, tz - 0.07], (X, Y, Z), (0.020, 0.012, 0.030), sw['metal'])      # flap switch
    # switch rows on both console tops
    for s in (1, -1):
        for zr in (-0.12, -0.05, 0.02, 0.50, 0.56):
            if s > 0 and 0.06 < zr < 0.42:
                continue
            for xc in (0.305, 0.335, 0.365, 0.395, 0.425, 0.455)[:6 if zr > 0.4 else 6]:
                m.box([s * xc, CONSOLE_Y + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, CONSOLE_Y + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])),
                      (0.0025, 0.012, 0.0025), sw['black'])

    # -- the centre stick: the boot (fixed) and the stick itself (into the stick bone), pivot at the foot between the knees
    FOOT = np.array([0.0, fy + 0.007, 0.56])
    m.box(FOOT + [0, 0.012, 0], (X, Y, Z), (0.060, 0.012, 0.060), sw['dgrey'])                         # base plate
    m.box(FOOT + [0, 0.045, 0], (X, Y, Z), (0.030, 0.033, 0.030), sw['black'])                         # rubber boot
    stick = Mesh()
    col_ax = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
    stick.box(FOOT + col_ax[1] * 0.190, col_ax, (0.011, 0.175, 0.011), sw['metal'])     # the column
    g_ax = (X, unit([0, 1, -0.20]), unit([0, 0.20, 1]))
    gc = FOOT + col_ax[1] * 0.360 + g_ax[2] * 0.012
    stick.box(gc, g_ax, (0.022, 0.070, 0.030), sw['black'])                                           # the grip
    stick.box(gc + g_ax[1] * 0.070, g_ax, (0.016, 0.006, 0.022), sw['dgrey'])                         # top cap / hat
    stick.box(gc + g_ax[1] * 0.063 + g_ax[2] * 0.010, g_ax, (0.005, 0.004, 0.005), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.020, g_ax, (0.008, 0.016, 0.005), sw['metal'])       # trigger
    stick.box(gc - g_ax[2] * 0.031 + g_ax[1] * 0.030, g_ax, (0.014, 0.020, 0.003), sw['dgrey'])       # pinky switch
    stick.box(gc + g_ax[1] * 0.030 - g_ax[2] * 0.0, g_ax, (0.024, 0.010, 0.032), sw['dgrey'])         # mid-grip band

    # -- Martin-Baker NACES seat, reclined 18 degrees, its back through the headrest behind the head
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(96, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    rec = math.radians(18)
    BU = np.array([0, math.cos(rec), -math.sin(rec)])            # up the seat back
    BN = np.array([0, math.sin(rec), math.cos(rec)])             # out of the back, toward the pilot's front
    pan_y = -0.745
    Q0 = np.array([0, pan_y, 0.095])                             # back line at the pan: 0.095 ahead of the eye's z
    ax = (X, BU, BN)
    m.box([0, pan_y - 0.105, Q0[2] + 0.08], (X, Y, Z), (0.185, 0.050, 0.200), sw['frame'])           # survival kit / base
    m.box([0, pan_y - 0.030, Q0[2] + 0.17], (X, Y, Z), (0.200, 0.030, 0.175), sw['cushion'],
          front=(1, 1, cushion, -X, Z))                                                              # seat pan cushion
    for s in (1, -1):
        m.box([s * 0.215, pan_y + 0.02, Q0[2] + 0.17], (X, Y, Z), (0.012, 0.065, 0.185), sw['frame'])  # bucket sides
        m.box(Q0 + BU * 0.37 + X * s * 0.175 - BN * 0.055, ax, (0.018, 0.57, 0.024), sw['frame'])      # guide rails
    m.box(Q0 + BU * 0.40 - BN * 0.02, ax, (0.18, 0.40, 0.030), sw['cushion'],
          front=(2, 1, backt, X, BU))                                                                # back cushion
    m.box(Q0 + BU * 0.42 - BN * 0.085, ax, (0.205, 0.42, 0.030), sw['frame'])                        # back shell
    m.box(Q0 + BU * 0.92 - BN * 0.045, ax, (0.150, 0.110, 0.095), sw['seatgrey'],
          front=(2, 1, headbox, X, BU))                                                              # headbox (parachute container)
    for s in (1, -1):                                                                                 # its cheeks (the NACES "ears")
        m.box(Q0 + BU * 0.90 - BN * 0.040 + X * s * 0.180, ax, (0.030, 0.085, 0.085), sw['seatgrey'])
    m.box(Q0 + BU * 0.80 + BN * 0.012, ax, (0.090, 0.065, 0.020), sw['cushion'])                     # headrest pad
    for s in (1, -1):                                                                                 # the shoulder straps up the back
        m.box(Q0 + BU * 0.58 + BN * 0.012 + X * s * 0.095, ax, (0.024, 0.20, 0.004), sw['strap'])
    for s in (1, -1):                                                                                 # lap belts over the pan
        m.box([s * 0.150, pan_y + 0.002, Q0[2] + 0.19], (X, Y, Z), (0.030, 0.003, 0.120), sw['strap'])
    # the single lower ejection handle between the knees: a yellow-and-black bar on two posts
    hz = Q0[2] + 0.385
    m.box([0, pan_y + 0.040, hz], (X, Y, Z), (0.075, 0.012, 0.024), sw['yellow'], front=(1, 1, stripes, -X, Z))
    for s in (1, -1):
        m.box([s * 0.066, pan_y + 0.005, hz], (X, Y, Z), (0.010, 0.030, 0.012), sw['black'])
    m.box(Q0 + BU * 0.55 - BN * 0.115, ax, (0.075, 0.095, 0.045), sw['dgrey'])                       # the seat's oxygen / kit pack
    return m, gauges, displays, glass, stick, hud, FOOT


def _components(pm):
    """vertex-merged connected components of a poly mesh: (positions in blocks, vertex ids, component label per vertex id)"""
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    key, ids = {}, []
    for p in P:
        ids.append(key.setdefault(tuple(np.round(p, 3)), len(key)))
    ids = np.array(ids)
    rows, cols = [], []
    for poly in pm['polys']:
        vs = [ids[v[0]] for v in poly]
        for a in vs[1:]:
            rows.append(vs[0]); cols.append(a)
    n = len(key)
    _, lab = connected_components(coo_matrix((np.ones(len(rows)), (rows, cols)), shape=(n, n)), directed=False)
    return P, ids, lab


# the tub's interior: from just ahead of the rear bulkhead (z 3.966) to the dash wall (z 4.91); the small parts standing in
# it (seat, handles, the old HUD housing, switch boxes) go whole
R_LO, R_HI = np.array([-0.56, 1.95, 3.95]), np.array([0.56, 3.40, 5.40])
S_LO, S_HI = np.array([-0.30, 2.60, 2.70]), np.array([0.30, 3.00, 4.00])      # (the loose deck slab behind the seat)
TUB_Z0, TUB_Z1, TUB_X = 4.02, 4.87, 0.25


def old_interior(geo):
    """{bone: set(poly index)} of the airframe's own loose interior: (1) every vertex-connected part lying wholly inside the
    box R (the seat, the HUD housing, boxes); (2) the faces of what is still attached to the shell between the bulkhead and
    the dash wall (the consoles, the pieces on the walls), except the big wall and floor panels. The walls, floor, the
    rear bulkhead, the sill bands, the spine, the nose and the dash wall (and its glareshield deck) stay."""
    out = {}
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] not in SOURCE_BONES:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P, ids, lab = _components(pm)
        comp = {}
        for i, poly in enumerate(pm['polys']):
            comp.setdefault(lab[ids[poly[0][0]]], []).append(i)
        sel = set()
        for fl in comp.values():
            V = np.concatenate([P[[v[0] for v in pm['polys'][i]]] for i in fl])
            if (np.all(V.min(0) >= R_LO) and np.all(V.max(0) <= R_HI)) or \
                    (np.all(V.min(0) >= S_LO) and np.all(V.max(0) <= S_HI)):
                sel.update(fl)
        for i, poly in enumerate(pm['polys']):
            if i in sel or not poly:
                continue
            V = P[[v[0] for v in poly]]
            c = V.mean(0)
            if TUB_Z0 <= c[2] <= TUB_Z1 and 2.09 < c[1] < 2.95 and abs(c[0]) < TUB_X and np.ptp(V, axis=0).max() < 0.9:
                sel.add(i)
        if sel:
            out[b['name']] = sel
    return out


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


def move_eye(data):
    """the seat and eye of the new cockpit into the vehicle data (x left, y up, z forward, blocks); the seat position keeps its
    offset from the eye (z) and sits 1.788 below it (the dismount point is left alone)"""
    ex, ey, ez = EYE_NEW
    seat = data['Seats'][0]
    att = data['Attachments'][seat['CameraPos']['EyeAttachment']]
    dz = ez - att['Position'][2]
    seat['Position'] = [0, round(ey - 1.788, 9), round(seat['Position'][2] + dz, 9)]
    att['Position'] = [0 if ex == 0 else ex, ey, ez]


def main(argv):
    global EYE
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    data = json.load(open(DATA, encoding='utf-8'))
    EYE = np.array(EYE_NEW, float)
    g = geo['minecraft:geometry'][0]
    rm = old_interior(geo)
    removed = 0
    for b in g['bones']:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            removed += len(rm[b['name']])
    atlas = Atlas()
    mesh, gauges, displays, glass, stick, hud, foot = build(atlas)
    pm = mesh.poly_mesh()
    bone = next((b for b in g['bones'] if b['name'] == BONE), None)
    if bone is None:
        hull = next(b for b in g['bones'] if b['name'] == 'hull')
        at = next(i for i, b in enumerate(g['bones']) if b['name'] == SOURCE_BONES[0]) + 1
        bone = {'name': BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        g['bones'].insert(at, bone)
    bone['poly_mesh'] = pm
    # the stick: its three bones' pivots to the centre stick's foot (model pixels)
    foot_px = [round(float((foot + EYE)[0]) * 16, 5), round(float((foot + EYE)[1]) * 16, 5),
               round(-float((foot + EYE)[2]) * 16, 5)]
    for b in g['bones']:
        if b['name'] in STICK_BONES:
            b['pivot'] = list(foot_px)
        if b['name'] == STICK_BONE:
            b['poly_mesh'] = stick.poly_mesh()
    thr = throttle_lib.add_bones(g['bones'], THR, BONE, EYE)
    for s in asset['AircraftRig']['Surfaces']:
        if s['Bone'] in ('control_stick_roll', 'control_stick_pitch'):
            s['Pivot'] = list(foot_px)
    asset['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    old_fd = asset.get('FlightDisplays') or {}
    fd = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    if 'Planform' in old_fd:
        fd['Planform'] = old_fd['Planform']
    asset['FlightDisplays'] = fd
    asset['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(EYE)}
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        # (canopy_fa_18c.py writes the glass fresh, so there is no earlier combiner to drop)
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for v in cg['Triangles']] + [round(v, 4) + 0.0 for p in both for v in p]
    move_eye(data)
    print(f'fa_18c: {removed} old interior faces removed; new cockpit {len(pm["polys"])} faces, {len(gauges)} game gauges, '
          f'{len(displays)} display(s), HUD combiner {len(glass) // 3} triangles; stick foot {foot_px} px; throttle bones {[(n, p) for n, p, _ in thr]}; '
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
