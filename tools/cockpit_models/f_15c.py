#!/usr/bin/env python3
"""F-15C (MSIP) cockpit, built by hand for the F-15C.mtb airframe (as mig_29.py and f_16c.py): one standalone script.

The airframe (commit 496cbf2e) brought its own interior: an ACES II seat, two consoles, a dash and a HUD housing
in the old model's layout, all a little rough. This script cuts exactly that loose interior out of the two hull pieces
(the small parts standing in the tub between the seat's bulkhead and the dash, and the box-like consoles; the walls,
floor, the rear bulkhead, the sill bands, the spine, the nose and the black dash block with its glareshield deck are
kept, so the exterior stays as it was) and puts a modelled cockpit in its place, laid out after the traced F-15C panel
of the DCS Flaming Cliffs 3 manual (work/cockpit_diagrams/f_15c) and photographs of the MSIP cockpit:

  * the main instrument panel in three flat facets (the centre 8 degrees back, the wings turned 22 degrees toward the
    pilot, the tops following the dash block's glareshield): the up-front controller (UFC) at the top centre under the
    HUD, the VSD (radar) display upper left and the TEWS scope upper right, the ADI over the HSI on the centre line
    with airspeed / AoA / g to their left and altimeter / VVI / clock to their right, the MPCD on the lower left, the
    paired engine gauges (rpm, FTIT, fuel flow, nozzle position) and the fuel quantity on the right, the armament and
    gear panels on the left wing, the caution panel and the ECS panel on the right;
  * the HUD on the glareshield: one unit with its control strip low on the pilot's face, two side posts, and the
    combiner glass (added to CanopyGlass) centred on the neutral eye line, slightly larger than life;
  * side consoles (the two throttles on the left, the utility panels on the right), liners, floor, kick panel with the
    centre stick's boot, rudder pedals;
  * an ACES II seat reclined 15 degrees at its real place, with the rails, the headbox, the ejection handles and
    harness, a rear bulkhead with the breaker panel behind it.

The seat and eye are moved back to match (the old model's pilot sat over the dash): the eye to (0, 3.265, 4.40), the
seat position 1.62 below it, as the airframe's data had them.

Paint lives in a free area of textures/entity/f_15c.png (no UV of the model, the native fallback or the wreck lands
there; the burnt copy goes to entity_dead). The instruments the game draws are written to CockpitGauges and
FlightDisplays, the HUD picture to HeadUpDisplay; the stick bones' pivots (geo, the native fallback and AircraftRig) go
to the new foot of the centre stick, and the seat and eye attachments are written into the vehicle data.

Order: git checkout the airframe's files, run canopy_f_15c.py, then this script (it appends the HUD combiner glass to the
canopy's CanopyGlass). Running it again rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is
written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/f_15c.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_15c.geo.json')
FALLBACK = os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback/f_15c.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_15c.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_15c.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_15c.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/f_15c.png')

BONE = 'wreck_fuselage_0__cockpit'
SOURCE_BONES = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
STICK_BONES = ('control_stick_roll', 'control_stick_pitch', 'wreck_fuselage_0__control_stick_pitch')
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
ATLAS = [(1500, 16, 2560, 3000)]       # free texture area (x, y, w, h): no UV of the model, its fallback or the wreck lands here
TW, TH = 4096, 4096

EYE_NEW = (0.0, 3.265, 4.40)           # the pilot's eye (vehicle data); the seat sits 1.62 below it


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

PANEL = (122, 126, 128)           # the F-15's medium grey panel face
PANEL_D = (46, 48, 51)         # edges, wing backs, console sides
PANEL_L = (128, 132, 134)
BLACK = (22, 23, 25)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
LGREY = (150, 154, 156)
FRAME = (50, 54, 52)           # ACES II structure
CUSHION = (62, 66, 58)
HEADPAD = (96, 100, 92)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (44, 47, 48)
CONSOLE = (62, 64, 66)         # the consoles are darker than the panel
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

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# Absolute heights of the airframe: floor top 2.304 (liner 2.312), hull sill 2.937, side walls at x 0.542, the rear
# bulkhead at z 4.171 (under the deck 2.98), the dash block's rear wall at z 5.12 (hump, 3.16) and 5.21 (sides); the
# eye at y 3.265, z 4.40 (the crown 0.39 over it, the front arch 0.85 ahead).

FLOOR_Y = -0.953                # the floor liner, 8 mm over the old floor
SILL_Y = -0.328                 # the hull's sill (abs 2.937)
CONSOLE_Y = -0.580              # console tops (abs 2.685)
CON_IN, CON_OUT = 0.285, 0.532  # the consoles' inner side and outer edge (the liners stand just inside the wall)
BACK_Z = -0.224                 # the rear bulkhead panel (abs 4.176, 5 mm ahead of the hull's own)

LEAN = math.radians(8)          # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])    # pilot's right
P0 = np.array([0.0, -0.40, 0.64])
HINGE = 0.340                   # centre facet half width
WING = math.radians(22)         # wings turned toward the pilot
WALL_END = 0.532                # wings end here (the liner)
WING_LEN = (WALL_END - HINGE) / math.cos(WING)
TOP_C = -0.145                  # centre facet top over the middle (abs 3.12, the dash block's glareshield)
TOP_K = 0.180                   # ... out to here, then falling to the hinge
TOP_H, TOP_W = -0.190, -0.300   # wing top at the hinge and at the wall (abs 3.075 and 2.965)
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

# Instrument layout of the F-15C panel (the traced DCS FC3 figure, work/cockpit_diagrams/f_15c, in metres: x right of
# the centre line, y below the glareshield, here y from the eye). Positions: facet, a (see Facet.at), y (height from
# the eye). Sizes: dial diameter, or w x h. 'game': drawn by the game.
LAYOUT = [
    ('ufc',     CENTRE, 0.000, -0.260, ('paint_rect', 0.165, 0.165), 'up-front controller (UFC) under the HUD'),
    ('vsd',     CENTRE, -0.180, -0.250, ('display', 'RADAR', 0.130, 'CRT'), 'VSD (vertical situation display, radar)'),
    ('tews',    CENTRE, 0.200, -0.295, ('game', 'RWR', 0.120), 'TEWS scope (threat warning)'),
    ('mpcd',    CENTRE, -0.262, -0.460, ('display', 'STORES', 0.110, 'LCD'), 'multi-purpose colour display (MPCD)'),
    ('ias',     CENTRE, -0.132, -0.426, ('game', 'AIRSPEED', 0.066), 'airspeed / Mach indicator'),
    ('aoa',     CENTRE, -0.121, -0.491, ('game', 'AOA', 0.058), 'angle of attack'),
    ('gmeter',  CENTRE, -0.119, -0.549, ('game', 'G_METER', 0.052), 'accelerometer'),
    ('adi',     CENTRE, 0.000, -0.428, ('game', 'ATTITUDE', 0.082), 'attitude director indicator (ADI)'),
    ('hsi',     CENTRE, 0.000, -0.540, ('game', 'HEADING', 0.082), 'horizontal situation indicator (HSI)'),
    ('alt',     CENTRE, 0.123, -0.426, ('game', 'ALTIMETER', 0.066), 'altimeter'),
    ('vvi',     CENTRE, 0.110, -0.491, ('game', 'VVI', 0.058), 'vertical velocity'),
    ('clock',   CENTRE, 0.108, -0.549, ('game', 'CLOCK', 0.052), 'clock'),
    ('rpm_l',   CENTRE, 0.241, -0.410, ('game', 'ENGINE_RPM', 0.040), 'left engine rpm'),
    ('rpm_r',   CENTRE, 0.191, -0.410, ('game', 'ENGINE_RPM', 0.040), 'right engine rpm'),
    ('egt_l',   CENTRE, 0.238, -0.462, ('game', 'EGT', 0.040), 'left FTIT'),
    ('egt_r',   CENTRE, 0.186, -0.462, ('game', 'EGT', 0.040), 'right FTIT'),
    ('ff_l',    CENTRE, 0.208, -0.514, ('paint_disc', 'FF', 0.040), 'left fuel flow'),
    ('ff_r',    CENTRE, 0.160, -0.514, ('paint_disc', 'FF', 0.040), 'right fuel flow'),
    ('noz_l',   CENTRE, 0.206, -0.566, ('paint_disc', 'NOZ', 0.040), 'left nozzle position'),
    ('noz_r',   CENTRE, 0.158, -0.566, ('paint_disc', 'NOZ', 0.040), 'right nozzle position'),
    ('fuelq',   CENTRE, 0.288, -0.530, ('game', 'FUEL_QTY', 0.066), 'fuel quantity'),
    ('afcs',    CENTRE, -0.100, -0.640, ('paint_rect', 0.064, 0.050), 'AFCS panel'),
    ('flaps',   CENTRE, 0.100, -0.640, ('paint_rect', 0.064, 0.050), 'flap position / lights'),
    ('arm',     LEFT,   0.100, -0.340, ('paint_rect', 0.112, 0.078), 'armament panel: master arm, combat, jettison'),
    ('gear',    LEFT,   0.100, -0.462, ('paint_rect', 0.105, 0.095), 'landing gear handle and lights'),
    ('warning', RIGHT,  0.100, -0.365, ('annunciator', 0.115, 0.070), 'caution panel'),
    ('ecs',     RIGHT,  0.100, -0.460, ('paint_rect', 0.098, 0.082), 'ECS panel'),
    ('oxy',     RIGHT,  0.100, -0.540, ('game', 'FILLER_OXYGEN', 0.040), 'oxygen quantity'),
]

GAUGE_LIFT = 0.004
DISPLAY_LIFT = 0.003
RIM = 0.16

# the caution lamps (the same set the F-16C's panel carries; the conditions are the game's)
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


def lamp(d, box, colour, label=None, lit=False, px=None):
    x0, y0, x1, y1 = box
    base = tuple(int(c * (1.0 if lit else 0.45)) for c in colour)
    d.rectangle(box, fill=base + (255,), outline=(10, 10, 10, 255), width=2)
    if label:
        text(d, ((x0 + x1) / 2, (y0 + y1) / 2), label, px or (y1 - y0) * .42, fill=(20, 20, 20))


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
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        for name, f, a, y, spec, _ in LAYOUT:
            if f is not facet:
                continue
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(18, 22, 22, 255))
            elif kind in ('paint_rect', 'display', 'annunciator'):
                if kind == 'display':
                    w = h = spec[2] + 0.060
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (y_top - y_bot) * H * SS * math.cos(LEAN)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(18, 22, 22, 255))
        if facet.side == 0:
            d.line([0, to_px(0, -0.640)[1] - 0.034 * H * SS, W * SS, to_px(0, -0.640)[1] - 0.034 * H * SS],
                   fill=PANEL_D + (255,), width=SS * 2)
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_ufc(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .05, H * .04, S * .95, H * .17], fill=(6, 14, 8, 255), outline=(60, 62, 60, 255), width=3)
    text(d, (S * .5, H * .105), 'MODE 3/A    1632', H * .075, fill=GREEN_TXT + (255,))
    d.rectangle([S * .05, H * .21, S * .95, H * .45], fill=(6, 14, 8, 255), outline=(60, 62, 60, 255), width=3)
    text(d, (S * .22, H * .29), 'CHAN 06', H * .07, fill=GREEN_TXT + (255,))
    text(d, (S * .72, H * .29), 'MAN FREQ', H * .055, fill=GREEN_TXT + (255,))
    text(d, (S * .5, H * .385), '276.375', H * .12, fill=GREEN_TXT + (255,), bold=True)
    for k, lab in enumerate(['BRT', 'SYM', 'RTCL']):
        x = S * (0.20 + k * 0.30)
        d.line([x, H * .60, x + S * .035, H * .52], fill=(190, 190, 184, 255), width=6)
        d.ellipse([x - 10, H * .58, x + 10, H * .66], fill=(150, 150, 146, 255))
        text(d, (x, H * .72), lab, H * .07)
    for k, lab in enumerate(['SYMBOL', 'DAY', 'STBY']):
        x = S * (0.20 + k * 0.30)
        d.ellipse([x - S * .07, H * .78, x + S * .07, H * .96], fill=(44, 44, 46, 255), outline=(150, 150, 146, 255), width=3)
        d.line([x, H * .87, x + S * .04, H * .81], fill=WHITE + (255,), width=4)


def p_hud_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['SYM', 'DAY', 'BRT', 'CONT', 'TEST']):
        x = S * (0.11 + k * 0.195)
        if k in (2, 3):
            d.ellipse([x - H * .20, H * .30 - H * .20, x + H * .20, H * .30 + H * .20], fill=(18, 18, 18, 255),
                      outline=(120, 120, 120, 255), width=3)
        else:
            d.rectangle([x - H * .26, H * .10, x + H * .26, H * .46], fill=(150, 150, 150, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (x, H * .70), lab, H * .2)


def p_afcs(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    for k, lab in enumerate(['ROLL', 'PITCH', 'YAW']):
        x = S * (0.2 + 0.3 * k)
        d.rectangle([x - 5, H * .20, x + 5, H * .52], fill=(170, 170, 170, 255))
        d.ellipse([x - 9, H * .12, x + 9, H * .30], fill=(200, 200, 200, 255))
        text(d, (x, H * .72), lab, H * .16)
    text(d, (S * .5, H * .93), 'AFCS', H * .13)


def p_flaps(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .12), 'FLAPS', H * .15)
    for k, lab in enumerate(['UP', 'HALF', 'FULL']):
        d.ellipse([S * (.14 + .3 * k) - 10, H * .34, S * (.14 + .3 * k) + 10, H * .54], fill=(40, 130, 56, 255) if k == 0 else (30, 60, 34, 255),
                  outline=(10, 10, 10, 255), width=2)
        text(d, (S * (.14 + .3 * k), H * .74), lab, H * .14)


def p_arm(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .06, H * .10, S * .30, H * .52], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .18, H * .31), 'ARM', H * .18, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['SAFE', 'COMBAT', 'JETT']):
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
    text(d, (S * .70, H * .81), 'UNSAFE', H * .09, fill=(235, 235, 228))


def p_ecs(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.ellipse([S * .08, H * .10, S * .48, H * .60], fill=(30, 30, 32, 255), outline=(150, 150, 150, 255), width=3)
    d.line([S * .28, H * .35, S * .40, H * .20], fill=WHITE + (255,), width=5)
    text(d, (S * .28, H * .72), 'CABIN ALT', H * .10)
    for k, lab in enumerate(['AIR', 'DEFOG', 'TEMP']):
        x = S * (0.64 + 0.0 * k); y = H * (0.20 + 0.28 * k)
        d.rectangle([x - 5, y - H * .09, x + 5, y + H * .09], fill=(176, 176, 176, 255))
        d.ellipse([x - 9, y - H * .14, x + 9, y - H * .04], fill=(206, 206, 206, 255))
        text(d, (S * .84, y), lab, H * .12)


def p_dial_static(kind):
    def fn(d, im):
        if kind == 'FF':
            dial_face(d, im, 'FF', 0, 10, 1, 2, 4.0, labels=['0', '2', '4', '6', '8', '10'])
        else:
            dial_face(d, im, 'NOZ', 0, 10, 1, 2, 3.0, labels=['0', '2', '4', '6', '8', '10'])
    return fn


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
                d.rectangle([x - S * .05, 0, x + S * .05, H], fill=(66, 70, 60, 255))
            d.rectangle([S * .38, H * .55, S * .62, H * .70], fill=(150, 150, 150, 255))
    return fn


def p_stripes(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=YELLOW + (255,))
    for k in range(-H, S, 24):
        d.polygon([(k, H), (k + 12, H), (k + 12 + H, 0), (k + H, 0)], fill=(20, 20, 20, 255))


def p_headbox(d, im):
    S, H = im.width, im.height
    noisy(d, im, HEADPAD, 4, seed=60)
    d.rectangle([S * .1, H * .1, S * .9, H * .9], outline=(60, 62, 58, 255), width=6)
    text(d, (S * .5, H * .5), 'ACES II', H * .2, fill=(40, 40, 40), bold=True)


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
                                                metal=METAL, floor=FLOOR, bezel=(30, 31, 33),
                                                coaming=(20, 21, 22), console=CONSOLE).items()}
    gauges, displays, glass = [], [], []

    # -- main panel facets (front face painted, slab 2 cm deep)
    ppm = 700
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
    painters = {'ufc': p_ufc, 'afcs': p_afcs, 'flaps': p_flaps, 'arm': p_arm, 'gear': p_gear, 'ecs': p_ecs}
    bar_h_r = atlas.paint(96, 16, bar_h); bar_v_r = atlas.paint(16, 96, bar_v)
    for name, f, a, y, spec, label in LAYOUT:
        kind = spec[0]
        c = f.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + EYE + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c, f.N, f.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'], back=0.0)
        elif kind == 'paint_disc':
            dia = spec[2]
            rect = cache.get(spec[1]) or atlas.paint(96, 96, p_dial_static(spec[1]))
            cache[spec[1]] = rect
            _, n_, u_, r_ = frame(c, f.N, f.U)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, dia, dia, rect)
            m.ring(c, f.N, f.U, dia / 2, dia / 2 * (1 + RIM), 0.010, sw['bezel'], back=0.0)
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
            if spec[3] == 'CRT':
                m.frame_rect(c, f.N, f.U, w, w, 0.014, pr, sw['bezel'])      # the VSD: a plain black bezel round the tube
            else:
                # the MPCD: a flat bezel with a row of option keys along each edge
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
            pw = 200 if w >= 0.15 else 160 if w >= h else 80
            ph = max(16, int(round(pw * h / w)))
            rect = cache.get(name) or atlas.paint(pw, ph, painters[name])
            cache[name] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])

    # -- the visor lip: from the panel's top edge back to the dash block (its rear wall at z 5.12 over the middle, 5.21
    # beside it), one flat black strip, so the panel meets the old glareshield with no gap
    cs = sw['coaming']
    pts = []
    for f, aa in ((LEFT, [WING_LEN, 0.0]), (CENTRE, [-HINGE, -TOP_K, TOP_K, HINGE]), (RIGHT, [0.0, WING_LEN])):
        for a in aa:
            pts.append(f.at(a, f.top(a)))
    # (the centre facet's end knots coincide with the wings' inner knots)
    uniq = [pts[0]]
    for p in pts[1:]:
        if np.linalg.norm(p - uniq[-1]) > 1e-4:
            uniq.append(p)
    pts = uniq
    z_wall = lambda x: 0.72 if abs(x) <= 0.181 else 0.81
    fwd = []
    for p in pts:
        fwd.append(np.array([p[0], p[1], z_wall(p[0])]))
    for i in range(len(pts) - 1):
        # break the strip where the wall steps from 0.72 to 0.81 so the quad stays planar
        lift = np.array([0, 0.004, 0])             # (a visor: 4 mm over the panel's top edge, so the two never share a plane)
        m.flat([pts[i] + lift, pts[i + 1] + lift, fwd[i + 1] + lift, fwd[i] + lift], cs, normal=[0, 1, 0])
    # -- the HUD: one unit (the housing on the glareshield's hump with its control strip low on the pilot's face, two
    # side posts) and the combiner glass centred on the neutral eye line, leaning back toward the pilot
    hz0, hz1, hy0, hy1, hhw = 0.730, 0.880, -0.118, -0.062, 0.118
    ctrl = atlas.paint(200, 44, p_hud_ctrl)
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    lean = math.radians(20)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h, g_hw = 0.190, 0.105
    g_bot = np.array([0, -0.0925, 0.800])
    g_top = g_bot + g_up * g_h
    m.box(g_bot - g_up * 0.004 + [0, 0.002, 0], (X, g_up, g_n), (g_hw + 0.010, 0.005, 0.010), sw['dgrey'])
    for s in (-1, 1):
        m.box((g_bot + g_top) / 2 + X * s * (g_hw + 0.007), (X, g_up, g_n), (0.005, g_h / 2 + 0.004, 0.006), sw['dgrey'])
    corners = [g_bot - X * g_hw, g_bot + X * g_hw, g_top + X * g_hw, g_top - X * g_hw]
    glass += [corners[0] + EYE, corners[1] + EYE, corners[2] + EYE, corners[0] + EYE, corners[2] + EYE, corners[3] + EYE]
    hud = dict(centre=(g_bot + g_top) / 2 + EYE, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)

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
    m.quad_tex([[CON_OUT, fy, z_back], [-CON_OUT, fy, z_back], [-CON_OUT, -0.288, z_back], [CON_OUT, -0.288, z_back]], bulk,
               normal=[0, 0, 1])
    m.quad_tex([[CON_IN, fy, zk], [-CON_IN, fy, zk], [-CON_IN, BOT_C, zk], [CON_IN, BOT_C, zk]], kick, normal=[0, 0, -1])
    # rudder pedals (arms to the floor, the plates tilted toward the pilot)
    for s in (1, -1):
        m.box([s * 0.13, -0.80, 0.50], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, -0.86, 0.54], (X, Y, Z), (0.014, 0.060, 0.045), sw['dgrey'])

    # -- the two throttles (left console): the quadrant, two levers with their grips and thumb switches
    tz = 0.30
    m.box([0.410, CONSOLE_Y + 0.011, tz], (X, Y, Z), (0.085, 0.011, 0.150), sw['dgrey'])
    for xl in (0.385, 0.435):
        lev = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
        m.box([xl, CONSOLE_Y + 0.07, tz + 0.04], lev, (0.008, 0.060, 0.010), sw['metal'])
        gax = (X, unit([0, 1, 0.45]), unit([0, -0.45, 1]))
        m.box([xl, CONSOLE_Y + 0.140, tz + 0.075], gax, (0.017, 0.044, 0.030), sw['black'])
        m.box([xl, CONSOLE_Y + 0.188, tz + 0.090], gax, (0.014, 0.008, 0.026), sw['dgrey'])
        m.box(np.array([xl, CONSOLE_Y + 0.152, tz + 0.075]) - gax[2] * 0.034 + gax[1] * 0.010, gax, (0.006, 0.006, 0.004),
              sw['red'] if xl < 0.4 else sw['metal'])
    m.box([0.465, CONSOLE_Y + 0.020, tz - 0.07], (X, Y, Z), (0.020, 0.012, 0.030), sw['metal'])      # speed brake switch
    # switch rows on both console tops
    for s in (1, -1):
        for zr in (-0.17, -0.10, -0.03, 0.52, 0.58):
            if s > 0 and 0.12 < zr < 0.48:
                continue
            for xc in (0.305, 0.335, 0.365, 0.395, 0.425, 0.455, 0.485, 0.515)[:7 if zr > 0.4 else 8]:
                if s > 0 and zr > 0.4 and xc > 0.5:
                    continue
                m.box([s * xc, CONSOLE_Y + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, CONSOLE_Y + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])),
                      (0.0025, 0.012, 0.0025), sw['black'])

    # -- the centre stick: the boot (fixed) and the stick itself (into the stick bone), pivot at the foot between the knees
    FOOT = np.array([0.0, fy + 0.007, 0.52])
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

    # -- ACES II ejection seat, reclined 15 degrees, its back through the headrest behind the head
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    rec = math.radians(15)
    BU = np.array([0, math.cos(rec), -math.sin(rec)])            # up the seat back
    BN = np.array([0, math.sin(rec), math.cos(rec)])             # out of the back, toward the pilot's front
    pan_y = -0.745
    Q0 = np.array([0, pan_y, (-0.20) + (0.0 - pan_y) * math.tan(rec)])    # back line at the pan: through the headrest face
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
    m.box(Q0 + BU * 0.92 - BN * 0.045, ax, (0.150, 0.110, 0.095), sw['frame'],
          front=(2, 1, headbox, X, BU))                                                              # headbox (parachute container)
    m.box(Q0 + BU * 0.80 + BN * 0.012, ax, (0.090, 0.065, 0.020), sw['cushion'])                     # headrest pad
    for s in (1, -1):                                        # the ejection handles on the pan's front corners
        m.box([s * 0.245, pan_y + 0.01, Q0[2] + 0.31], (X, Y, Z), (0.020, 0.020, 0.050), sw['yellow'],
              front=(1, 1, stripes, -X, Z))
        m.box([s * 0.245, pan_y - 0.02, Q0[2] + 0.36], (X, Y, Z), (0.010, 0.010, 0.012), sw['black'])
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


# the tub's interior: from just ahead of the rear bulkhead (z 4.171) to the dash block's rear wall (z 5.12 over the
# middle); the small parts standing in it (seat, handles, the old HUD housing, switch boxes) go whole
R_LO, R_HI = np.array([-0.56, 2.25, 4.10]), np.array([0.56, 3.55, 5.325])
TUB_Z0, TUB_Z1, TUB_X = 4.18, 5.119, 0.537


def old_interior(geo):
    """{bone: set(poly index)} of the airframe's own loose interior: (1) every vertex-connected part lying wholly inside the
    box R (the seat, the HUD housing, boxes); (2) the faces of what is still attached to the shell between the bulkhead and
    the dash block (the consoles, the pieces on the walls), except the big wall and floor panels. The walls, floor, the
    rear bulkhead, the sill bands, the spine, the nose and the dash block (rear wall, glareshield deck) stay."""
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
            if np.all(V.min(0) >= R_LO) and np.all(V.max(0) <= R_HI):
                sel.update(fl)
        for i, poly in enumerate(pm['polys']):
            if i in sel or not poly:
                continue
            V = P[[v[0] for v in poly]]
            c = V.mean(0)
            if TUB_Z0 <= c[2] <= TUB_Z1 and 2.31 < c[1] < 3.6 and abs(c[0]) < TUB_X and np.ptp(V, axis=0).max() < 0.6:
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
    """the seat and eye of the new cockpit into the vehicle data (x left, y up, z forward, blocks); the seat position stays
    1.62 below the eye and the dismount point stays level with it"""
    ex, ey, ez = EYE_NEW
    seat = data['Seats'][0]
    seat['Position'] = [0, round(ey - 1.62, 9), ez]
    data['Attachments'][seat['CameraPos']['EyeAttachment']]['Position'] = [ex, ey, ez]
    seat['DismountInfo']['Position'][2] = ez


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
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        c = t.mean(1) - EYE                                  # (an earlier combiner of this script goes)
        keep = ~((np.abs(c[:, 0]) < 0.14) & (c[:, 1] > -0.12) & (c[:, 1] < 0.12) & (c[:, 2] > 0.65) & (c[:, 2] < 0.85))
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    move_eye(data)
    print(f'f_15c: {removed} old interior faces removed; new cockpit {len(pm["polys"])} faces, {len(gauges)} game gauges, '
          f'{len(displays)} display(s), HUD combiner {len(glass) // 3} triangles; stick foot {foot_px} px; '
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
