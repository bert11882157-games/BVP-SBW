#!/usr/bin/env python3
"""A-10C (Suite 3 / PE) cockpit, built by hand for the A-10C.mtb airframe (as f_15c.py, f_16c.py and mig_29.py): one
standalone script, replacing the earlier A-10A-style pass of this name.

The airframe (commits 21a777fe, 6f44bcdc) brought its own interior in the old model's layout: box consoles, a seat, a flat
panel with instruments painted on the dash block's rear wall, a stick of yellow boxes, two stands for a HUD. This script cuts
exactly that loose interior out of the forward fuselage piece (the parts standing free in the tub, and the console boxes;
the walls, floor, rail bands, arch, bars, the dash block, the deck and the nose are kept, so the exterior stays as it was)
and puts a modelled cockpit in its place, laid out after the Suite 3 front panel of the A-10C (DCS A-10C flight manual,
cockpit figures; photographs of the PE cockpit):

  * the main panel, one flat facet leaning back 9 degrees under the dash block's glareshield (the cockpit is narrow:
    the panel stops at the sill rails): the UFC under the HUD, a colour 5x5 in MFCD each side of it (left: stores / SMS
    page, right: the moving map / TAD / TGP page), the standby instruments down the centre (airspeed, ADI, altimeter),
    with the standby compass hanging under the windscreen arch; the AOA gauge and vertical speed on the left, the fuel
    quantity indicator and the RWR on the right; the armament panel (stores jettison, master arm lights) and the landing
    gear handle with its flap indicator on the left; the caution panel and the twin engine gauges (fan speed, ITT, fuel flow,
    oil) on the right; the hydraulic / nose wheel steering panel under the ADI;
  * the HUD on its narrow centre pedestal on the glareshield (the UFC directly under it): the combiner glass (added to
    CanopyGlass) centred on the neutral eye line, slightly larger than life, and the AHCP tilted on the left of the
    glareshield, beside the windscreen's bar;
  * the left console: the two throttles with their HOTAS grips, the CMSP, trim / SAS, flap and lighting plates; the
    right console: the CDU (five-line display and keypad), the AAP and the radio, TACAN and environmental plates;
  * a centre-mounted stick in the animated stick bones (the airframe had none: the bones and the two rig surfaces for it
    are added), rudder pedals on the kick panel, an ACES II seat (reclined 10 degrees, headbox, the ejection handle
    between the knees, harness) at its real place, the rear bulkhead.

The pilot's eye moves back to (0, 3.20, 4.58) (the seat of the old model sat nearly under the arch), the seat position
follows; the airframe's data had them at (0, 3.139, 4.791).

Paint lives in a free area of textures/entity/a_10.png (no UV of the model, the native fallback or the wreck lands
there; the burnt copy goes to entity_dead). The instruments the game draws are written to CockpitGauges and
FlightDisplays, the HUD picture to HeadUpDisplay; only those client-data entries are live, everything else is a painted
face. The stick bones' pivots go to the new foot of the centre stick (geo, the native fallback and AircraftRig).

Order: git checkout the airframe's files, run canopy_a_10.py, then this script (it appends the HUD combiner glass to the
canopy's CanopyGlass). Running it again rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is
written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/a_10.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/a_10.geo.json')
FALLBACK = os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback/a_10.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/a_10.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/a_10.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/a_10.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/a_10.png')

BONE = 'wreck_fuselage_0__cockpit'
SOURCE_BONES = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
STICK_BONES = ('control_stick_roll', 'control_stick_pitch', 'wreck_fuselage_0__control_stick_pitch')
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
# free texture areas (x, y, w, h): no UV of the model, its fallback or the wreck lands there
ATLAS = [(1032, 360, 1008, 656), (1032, 2064, 1008, 656)]
TW, TH = 2048, 3072

EYE_NEW = (0.0, 3.20, 4.58)            # the pilot's eye (vehicle data)
EZ = EYE_NEW[2]
OLD_EYE = (0.0, 3.139398, 4.791139)
SEAT_BELOW, SEAT_AHEAD = 1.780573, 0.1  # the seat position relative to the eye, as the airframe's data had it


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

# ---------------------------------------------------------------- colours (the A-10's cockpit is dark grey and black)

PANEL = (50, 52, 54)
PANEL_D = (30, 31, 33)
BLACK = (20, 21, 23)
DGREY = (38, 40, 42)
MGREY = (84, 88, 90)
LGREY = (150, 154, 156)
FRAME = (46, 50, 48)           # ACES II structure
CUSHION = (64, 70, 60)         # the seat's olive drab
HEADPAD = (96, 100, 90)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (36, 38, 39)
CONSOLE = (58, 60, 62)
WHITE = (226, 228, 220)
GREEN_LAMP = (60, 150, 70)
AMBER = (214, 140, 30)
GREEN_TXT = (80, 220, 100)


# ---------------------------------------------------------------- texture atlas

class Atlas:
    """Shelf-packed rectangles in the free texture areas. paint(w, h, fn) -> rect; fn(draw, img) paints an RGBA tile
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
# Absolute heights of the airframe: floor top 2.25 (liner 2.258), the tub's rear bulkhead at z 4.09, the dash block's rear
# wall at z 5.235 (its top edge y 3.10, the glareshield sloping to 3.0 at z 5.54), the sill rails' band leaning in from
# x 0.62 at y 2.5 to 0.45 at 2.99, the arch's feet at x 0.4..0.5, y 2.9..3.15, z 4.94..5.19; the eye at (0, 3.20, 4.58).

FLOOR_Y = 2.258 - 3.20          # the floor liner, 8 mm over the old floor
CONSOLE_Y = 2.60 - 3.20         # console tops (abs 2.60)
CON_IN, CON_OUT = 0.30, 0.59    # the consoles' inner side and outer edge (the band leans in over the outer edge)
BACK_Z = 4.095 - EZ           # the rear bulkhead panel (5 mm ahead of the hull's own)

LEAN = math.radians(9)          # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])    # pilot's right
P0 = np.array([0.0, -0.10, 5.23 - EZ])   # panel top edge on the centre line (abs y 3.10, z 5.23: the dash block's rear top)
PANEL_HALF = 0.585              # the panel's half width (the lowest steps reach the band)
BOT = -0.60                     # panel bottom (the console tops)


def panel_top(a):
    """the panel's top edge: the dash block's top over the middle, stepping down along the sill rail band that leans in"""
    a = abs(a)
    if a <= 0.44:
        return -0.10
    if a <= 0.48:
        return -0.34
    return -0.50


class Facet:
    N, U, R = N_C, U_C, R_C

    def at(self, a, y, lift=0.0):
        """panel point: a = metres right of the centre line, y = height from the eye"""
        v = (y - P0[1]) / math.cos(LEAN)
        return P0 + R_C * a + U_C * v + N_C * lift


PANEL_F = Facet()


def panel_front_z(y):
    return float(PANEL_F.at(0, y)[2])


# Instrument layout of the A-10C panel (a: metres right of the centre line, y: height from the eye). Sizes: dial diameter,
# or w x h. 'game': drawn by the game. 'display': a FlightDisplays page. Everything else is a painted face.
LAYOUT = [
    ('ufc',     0.000, -0.205, ('paint_rect', 0.145, 0.150), 'up-front controller (UFC) under the HUD'),
    ('lmfcd',  -0.205, -0.225, ('display', 'STORES', 0.150, 'LCD'), 'left MFCD, 5 x 5 in: stores / SMS page'),
    ('rmfcd',   0.205, -0.225, ('display', 'RADAR', 0.150, 'LCD'), 'right MFCD, 5 x 5 in: moving map / TAD / TGP page'),
    ('arm',    -0.375, -0.215, ('paint_rect', 0.115, 0.140), 'armament panel: stores jettison, master arm lights'),
    ('warning', 0.375, -0.215, ('annunciator', 0.115, 0.100), 'caution panel'),
    ('ias',    -0.125, -0.400, ('game', 'AIRSPEED', 0.090), 'standby airspeed indicator'),
    ('adi',     0.000, -0.400, ('game', 'ATTITUDE', 0.110), 'standby attitude indicator (ADI)'),
    ('alt',     0.125, -0.400, ('game', 'ALTIMETER', 0.090), 'standby altimeter'),
    ('vvi',    -0.245, -0.400, ('game', 'VVI', 0.070), 'vertical speed'),
    ('aoa',    -0.345, -0.400, ('game', 'AOA', 0.070), 'angle of attack'),
    ('fuelq',   0.245, -0.400, ('game', 'FUEL_QTY', 0.075), 'fuel quantity indicator'),
    ('rwr',     0.345, -0.400, ('game', 'RWR', 0.070), 'radar warning (ALR-69) scope'),
    ('gear',   -0.330, -0.515, ('paint_rect', 0.150, 0.095), 'landing gear handle and lights'),
    ('flaps',  -0.205, -0.520, ('paint_rect', 0.090, 0.070), 'flap position indicator'),
    ('hyd',     0.000, -0.525, ('paint_rect', 0.150, 0.080), 'hydraulic pressure / nose wheel steering panel'),
    ('rpm_l',   0.115, -0.500, ('game', 'ENGINE_RPM', 0.055), 'left fan speed'),
    ('rpm_r',   0.185, -0.500, ('game', 'ENGINE_RPM', 0.055), 'right fan speed'),
    ('egt_l',   0.115, -0.565, ('game', 'EGT', 0.050), 'left interstage turbine temperature'),
    ('egt_r',   0.185, -0.565, ('game', 'EGT', 0.050), 'right interstage turbine temperature'),
    ('ff_l',    0.265, -0.500, ('paint_disc', 'FF', 0.055), 'left fuel flow'),
    ('ff_r',    0.335, -0.500, ('paint_disc', 'FF', 0.055), 'right fuel flow'),
    ('oil_l',   0.265, -0.565, ('paint_disc', 'OIL', 0.050), 'left oil pressure'),
    ('oil_r',   0.335, -0.565, ('paint_disc', 'OIL', 0.050), 'right oil pressure'),
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
LAMP_COLUMNS = 4


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


def toggle(d, x, y, S, lit=False, big=False):
    """a toggle switch seen from the front: a round base and a short lever"""
    r = S * (0.030 if big else 0.022)
    d.ellipse([x - r, y - r, x + r, y + r], fill=(26, 26, 28, 255), outline=(150, 150, 146, 255), width=2)
    d.line([x, y, x + r * 0.5, y - r * 1.6], fill=(205, 205, 198, 255), width=max(3, int(r * 0.7)))


def knob(d, x, y, r, ang=0.7, label=None, H=None):
    d.ellipse([x - r, y - r, x + r, y + r], fill=(30, 30, 32, 255), outline=(170, 170, 164, 255), width=3)
    d.line([x, y, x + r * 0.8 * math.sin(ang), y - r * 0.8 * math.cos(ang)], fill=WHITE + (255,), width=4)
    if label and H:
        text(d, (x, y + r + H * 0.05), label, H * .045)


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


def panel_paint(rect_px):
    """the panel face: dark grey, seams, screws and the dark recesses behind every instrument"""
    a0, a1 = -PANEL_HALF, PANEL_HALF
    W, H = rect_px
    y_bot = BOT; y_top = panel_top(0)
    SS = Atlas.SS

    def to_px(a, y):
        s = (a - a0) / (a1 - a0)
        return (s * W * SS, (1 - (y - y_bot) / (y_top - y_bot)) * H * SS)

    def fn(d, im):
        noisy(d, im, PANEL, 3, seed=3)
        for (a, y) in [(a0 + 0.010, y_bot + 0.010), (a1 - 0.010, y_bot + 0.010), (a0 + 0.010, panel_top(a0) - 0.012),
                       (a1 - 0.010, panel_top(a1) - 0.012), (-0.30, y_top - 0.010), (0.30, y_top - 0.010)]:
            x, yy = to_px(a, y); r = SS * 1.8
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(20, 20, 20, 255))
        for name, a, y, spec, _ in LAYOUT:
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(14, 17, 17, 255))
            elif kind in ('paint_rect', 'display', 'annunciator'):
                if kind == 'display':
                    w = h = spec[2] + 0.060
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (y_top - y_bot) * H * SS * math.cos(LEAN)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(14, 17, 17, 255))
        # a seam under the top row and under the standby instruments
        for yy_ in (-0.318, -0.462):
            x0, yv = to_px(a0, yy_); x1, _ = to_px(a1, yy_)
            d.line([x0, yv, x1, yv], fill=PANEL_D + (255,), width=SS * 2)
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_ufc(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    d.rectangle([S * .06, H * .04, S * .94, H * .26], fill=(5, 12, 7, 255), outline=(60, 62, 60, 255), width=3)
    text(d, (S * .5, H * .09), 'UHF  251.000    TCN  33X', H * .062, fill=GREEN_TXT + (255,))
    text(d, (S * .5, H * .185), 'SCRATCHPAD  38 41 17.6N', H * .062, fill=GREEN_TXT + (255,))
    keys = [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['0', '.', '-']]
    for r_, row in enumerate(keys):
        for c_, k in enumerate(row):
            x0 = S * (0.07 + c_ * 0.20); y0 = H * (0.31 + r_ * 0.15)
            d.rectangle([x0, y0, x0 + S * .17, y0 + H * .12], fill=(170, 172, 168, 255), outline=(10, 10, 10, 255), width=2)
            text(d, (x0 + S * .085, y0 + H * .06), k, H * .075, fill=(20, 20, 20))
    for r_, lab in enumerate(['HACK', 'FUNC', 'CLR', 'ENT']):
        x0 = S * 0.69; y0 = H * (0.31 + r_ * 0.15)
        d.rectangle([x0, y0, x0 + S * .24, y0 + H * .12], fill=(70, 72, 74, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x0 + S * .12, y0 + H * .06), lab, H * .06)
    knob(d, S * .12, H * .93, H * .045); knob(d, S * .88, H * .93, H * .045)


def p_hud_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['BRT', 'DAY', 'CONT']):
        x = S * (0.20 + k * 0.30)
        d.ellipse([x - H * .22, H * .30 - H * .22, x + H * .22, H * .30 + H * .22], fill=(18, 18, 18, 255),
                  outline=(120, 120, 120, 255), width=3)
        text(d, (x, H * .74), lab, H * .2)


def p_gear(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    d.rectangle([S * .06, H * .10, S * .12, H * .80], fill=(150, 150, 146, 255))
    d.ellipse([S * .02, H * .02, S * .20, H * .32], fill=(210, 40, 34, 255), outline=(10, 10, 10, 255), width=3)  # the wheel-shaped red handle
    text(d, (S * .11, H * .92), 'LDG GEAR', H * .09)
    for k, (x, y, lab) in enumerate([(0.52, 0.22, 'NOSE'), (0.38, 0.62, 'L'), (0.66, 0.62, 'R')]):
        d.rectangle([S * x - S * .07, H * y - H * .13, S * x + S * .07, H * y + H * .13], fill=(40, 130, 56, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (S * x, H * y), lab, H * .14, fill=(10, 30, 10))
    d.rectangle([S * .78, H * .12, S * .96, H * .50], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .87, H * .31), 'GEAR', H * .12, fill=(235, 235, 228))
    d.rectangle([S * .78, H * .60, S * .96, H * .90], fill=(60, 60, 62, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .87, H * .75), 'DOWN LOCK', H * .08)


def p_flaps(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .14), 'FLAPS', H * .15)
    d.rectangle([S * .12, H * .30, S * .88, H * .62], fill=(34, 34, 36, 255), outline=(150, 150, 146, 255), width=3)
    for k, lab in enumerate(['UP', '7', '15', '20']):
        x = S * (0.20 + 0.20 * k)
        d.line([x, H * .34, x, H * .58], fill=WHITE + (255,), width=3)
        text(d, (x, H * .80), lab, H * .14)
    d.polygon([(S * .60, H * .30), (S * .55, H * .22), (S * .65, H * .22)], fill=AMBER + (255,))


def p_arm(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    d.rectangle([S * .08, H * .06, S * .92, H * .34], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .5, H * .20), 'EMER JETT', H * .13, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['A/A', 'A/G', 'GUN']):
        x = S * (0.20 + k * 0.30)
        d.rectangle([x - S * .11, H * .42, x + S * .11, H * .60], fill=(40, 130, 56, 255) if k == 1 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .51), lab, H * .09, fill=(10, 30, 10))
    for k, lab in enumerate(['L OUTBD', 'L INBD', 'FUS', 'R INBD', 'R OUTBD']):
        x = S * (0.12 + 0.19 * k)
        d.rectangle([x - S * .07, H * .68, x + S * .07, H * .88], fill=(176, 176, 172, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x, H * .78), lab.split()[0][:3], H * .055, fill=(20, 20, 20))
    text(d, (S * .5, H * .96), 'STATION SELECT', H * .06)


def p_hyd(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .24, H * .13), 'HYD PRESS', H * .13)
    for k, lab in enumerate(['L', 'R']):
        x = S * (0.12 + 0.24 * k)
        d.rectangle([x - S * .08, H * .24, x + S * .08, H * .62], fill=(30, 30, 32, 255), outline=(150, 150, 146, 255), width=3)
        d.rectangle([x - S * .08 + 4, H * .44, x + S * .08 - 4, H * .62 - 4], fill=(60, 150, 70, 255))
        text(d, (x, H * .75), lab, H * .14)
    text(d, (S * .76, H * .13), 'NWS', H * .13)
    d.ellipse([S * .66, H * .26, S * .86, H * .62], fill=(40, 130, 56, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .76, H * .44), 'ON', H * .15, fill=(10, 30, 10))
    text(d, (S * .76, H * .80), 'ENGAGED', H * .11)


def p_dial_static(kind):
    def fn(d, im):
        if kind == 'FF':
            dial_face(d, im, 'FF', 0, 10, 1, 2, 4.0, labels=['0', '2', '4', '6', '8', '10'])
        else:
            dial_face(d, im, 'OIL', 0, 100, 10, 20, 55.0, labels=['0', '20', '40', '60', '80', '100'], red=(0, 12))
    return fn


# the console plates: (kind, caption, z from the front edge (m), length (m)); kind picks the painter
LEFT_PLATES = [('cmsp', 'CMSP', 0.02, 0.15), ('quad', '', 0.20, 0.30), ('trim', 'TRIM / SAS', 0.54, 0.12),
               ('flap', 'EMER FLAP / BRK', 0.68, 0.11), ('lights', 'LIGHTING', 0.82, 0.12), ('mic', 'CMS CONTROL', 0.97, 0.09)]
RIGHT_PLATES = [('cdu', 'CDU', 0.02, 0.24), ('aap', 'AAP', 0.28, 0.12), ('radio', 'UHF / VHF / FM', 0.43, 0.14),
                ('tacan', 'TACAN / ILS', 0.60, 0.12), ('env', 'ENVIRONMENT', 0.75, 0.12), ('intl', 'INTERIOR LTS', 0.90, 0.12)]


def p_console(side, length):
    plates = LEFT_PLATES if side > 0 else RIGHT_PLATES

    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, CONSOLE, 3, seed=20 + side)
        for kind, cap, z0, ln in plates:
            y0 = H * z0 / length; y1 = H * (z0 + ln) / length
            d.rectangle([S * .05, y0 + 3, S * .95, y1 - 3], fill=(18, 20, 20, 255), outline=(10, 10, 10, 255), width=2)
            mid = (y0 + y1) / 2
            if kind == 'quad':
                d.rectangle([S * .22, y0 + 8, S * .78, y1 - 8], fill=(6, 6, 6, 255))      # the throttle slot (the levers are 3D)
                text(d, (S * .5, (y0 + y1) / 2), 'THROTTLES', S * .09, fill=(90, 90, 90))
                continue
            if kind == 'cmsp':
                d.rectangle([S * .12, y0 + 10, S * .88, y0 + (y1 - y0) * .38], fill=(5, 12, 7, 255), outline=(60, 62, 60, 255), width=2)
                text(d, (S * .5, y0 + (y1 - y0) * .2), 'CMSP  CHAFF 60  FLARE 30', S * .07, fill=GREEN_TXT + (255,))
                for k in range(4):
                    x = S * (0.2 + 0.2 * k)
                    d.rectangle([x - S * .07, y0 + (y1 - y0) * .5, x + S * .07, y0 + (y1 - y0) * .68], fill=(150, 152, 148, 255))
                for k in range(3):
                    toggle(d, S * (0.25 + 0.25 * k), y0 + (y1 - y0) * .84, S * 1.4)
                continue
            if kind == 'cdu':
                d.rectangle([S * .10, y0 + 8, S * .90, y0 + (y1 - y0) * .34], fill=(5, 12, 7, 255), outline=(60, 62, 60, 255), width=3)
                for k, s_ in enumerate(['NAV  WP 12  APT', 'HDG 087  DIST 34.2', 'ETE 08:12   GS 340', 'MSN  ARM  SAFE']):
                    text(d, (S * .5, y0 + 8 + (k + .6) * ((y1 - y0) * .34 - 8) / 4), s_, S * .05, fill=GREEN_TXT + (255,))
                for r_ in range(5):
                    for c_ in range(5):
                        x0 = S * (0.12 + c_ * 0.155); yy0 = y0 + (y1 - y0) * (0.40 + r_ * 0.113)
                        d.rectangle([x0, yy0, x0 + S * .125, yy0 + (y1 - y0) * .09], fill=(150, 152, 148, 255), outline=(10, 10, 10, 255), width=2)
                continue
            for k in range(3):
                x = S * (0.22 + 0.28 * k)
                if kind in ('radio', 'tacan', 'aap'):
                    knob(d, x, mid, S * .075, ang=0.5 + 0.6 * k)
                else:
                    toggle(d, x, mid + (y1 - y0) * .10, S * 1.4)
            d.rectangle([S * .12, y0 + 6, S * .88, y0 + 6 + (y1 - y0) * .18], fill=(40, 42, 42, 255))
            text(d, (S * .5, y0 + 6 + (y1 - y0) * .09), cap, S * .06)
    return fn


def p_ahcp(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .08), 'ARMAMENT HUD CONTROL', H * .08)
    d.rectangle([S * .06, H * .16, S * .30, H * .48], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .18, H * .32), 'MASTER ARM', H * .09, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['ARM', 'SAFE', 'TRAIN']):
        text(d, (S * .18, H * (0.55 + 0.12 * k)), lab, H * .075)
    for k, lab in enumerate(['LASER', 'TGP', 'GUN / PAC']):
        x = S * (0.44 + k * 0.22)
        toggle(d, x, H * .36, S * 1.2, big=True)
        text(d, (x, H * .60), lab, H * .075)
    for k, lab in enumerate(['HUD DAY', 'CICU', 'JTRS']):
        x = S * (0.44 + k * 0.22)
        toggle(d, x, H * .78, S * 1.2)
        text(d, (x, H * .92), lab, H * .065)


def p_cushion(colour, straps=False):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 5, seed=50)
        for k in range(1, 4):
            d.line([0, H * k / 4, S, H * k / 4], fill=tuple(c - 16 for c in colour) + (255,), width=4)
        if straps:
            for x in (S * .28, S * .72):
                d.rectangle([x - S * .05, 0, x + S * .05, H], fill=(70, 76, 64, 255))
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
                                                metal=METAL, floor=FLOOR, bezel=(26, 27, 29),
                                                console=CONSOLE, pale=(120, 124, 120)).items()}
    gauges, displays, glass = [], [], []
    f = PANEL_F

    # -- the main panel (one flat facet, front face painted, slab 2 cm deep): strips between the top edge's steps
    ppm = 700
    a0, a1 = -PANEL_HALF, PANEL_HALF
    y_top = panel_top(0)
    W = int(round((a1 - a0) * ppm)); Hh = int(round((y_top - BOT) / math.cos(LEAN) * ppm))
    rect = atlas.paint(W, Hh, panel_paint((W, Hh)))
    cuts = sorted(set([a0 + (a1 - a0) * k / 12 for k in range(13)] + [-0.48, -0.44, 0.44, 0.48]))
    loop_top, loop_bot = [], []
    for aa0, aa1 in zip(cuts[:-1], cuts[1:]):
        s0, s1 = (aa0 - a0) / (a1 - a0), (aa1 - a0) / (a1 - a0)
        top = panel_top((aa0 + aa1) / 2)
        t = (top - BOT) / (y_top - BOT)
        pts = [f.at(aa0, BOT), f.at(aa1, BOT), f.at(aa1, top), f.at(aa0, top)]
        m.face(pts, [uv_of(rect, s0, 0), uv_of(rect, s1, 0), uv_of(rect, s1, t), uv_of(rect, s0, t)], normal=f.N)
        loop_bot.append((aa0, aa1))
    back = -f.N * 0.02
    # slab back and edges: the outline (bottom left to right, up the right edge, along the stepped top, down the left edge)
    top_pts = []
    for aa0, aa1 in zip(cuts[:-1], cuts[1:]):
        top = panel_top((aa0 + aa1) / 2)
        top_pts += [(aa0, top), (aa1, top)]
    top_pts = [p for i, p in enumerate(top_pts) if i == 0 or abs(p[0] - top_pts[i - 1][0]) > 1e-9 or abs(p[1] - top_pts[i - 1][1]) > 1e-9]
    outline = [f.at(a0, BOT), f.at(a1, BOT)] + [f.at(a, y) for a, y in reversed(top_pts)]
    m.flat([c + back for c in outline], sw['panel_d'], normal=-f.N)
    mid0 = np.mean(outline, 0)
    for i in range(len(outline)):
        p, q = outline[i], outline[(i + 1) % len(outline)]
        mid = (p + q) / 2
        m.flat([p, q, q + back, p + back], sw['panel_d'], toward=mid + (mid - mid0))

    # -- instruments
    cache = {}
    painters = {'ufc': p_ufc, 'flaps': p_flaps, 'arm': p_arm, 'gear': p_gear, 'hyd': p_hyd}
    bar_h_r = atlas.paint(96, 16, bar_h); bar_v_r = atlas.paint(16, 96, bar_v)
    for name, a, y, spec, label in LAYOUT:
        kind = spec[0]
        c = f.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + EYE + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c, f.N, f.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'], back=0.0)
        elif kind == 'paint_disc':
            dia = spec[2]
            rect_ = cache.get(spec[1]) or atlas.paint(96, 96, p_dial_static(spec[1]))
            cache[spec[1]] = rect_
            m.rect_tex(c + f.N * 0.004, f.N, f.U, dia, dia, rect_)
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
            # the MFCD: a flat bezel with a row of twelve option keys along each edge
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
            pw = 200 if w >= 0.14 else 140
            ph = max(16, int(round(pw * h / w)))
            rect_ = cache.get(name) or atlas.paint(pw, ph, painters[name])
            cache[name] = rect_
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect_)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])

    # -- the standby compass, hung from the windscreen arch's crown (abs y 3.40, z 5.11): a small body with a face
    # toward the pilot, and the game's heading dial on it
    ch = np.array([0.0, 3.395 - 3.20, 5.098 - EZ])
    m.box(ch + [0, 0, 0.012], (X, Y, Z), (0.034, 0.034, 0.018), sw['black'])
    cn = unit([0, 0.10, -1])
    gauges.append({'Seat': 0, 'Kind': 'HEADING', 'Center': r5(ch + [0, 0, -0.008] + EYE + cn * 0.004), 'Normal': r5(cn),
                   'Up': r5(unit(np.cross(cn, X) * -1)), 'Diameter': 0.056, 'Depth': 0.02})
    m.ring(ch + [0, 0, -0.008], cn, unit(np.cross(cn, X) * -1), 0.028, 0.032, 0.008, sw['bezel'], back=0.0)

    # -- the HUD: the unit on its narrow centre pedestal on the glareshield (the UFC right under it) with a low control
    # strip (4.5 cm) toward the pilot, and ONE combiner glass (the A-10C Suite 3 HUD: a single flat-topped trapezoid, a
    # little wider at its foot than at its top, from photographs of the A-10C and the A-10's HUD pedestal; DCS's A-10C
    # model carries no HUD to measure) centred on the neutral eye line, 15% over life size, leaning back toward the pilot,
    # in a slim bezel with two side posts standing on the pedestal
    hz0, hz1, hy0, hy1, hhw = 5.330 - EZ, 5.430 - EZ, 3.020 - 3.20, 3.065 - 3.20, 0.075
    ctrl = atlas.paint(150, 30, p_hud_ctrl)
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    lean = math.radians(28)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))                              # (away from the pilot)
    g_h = 0.270                                                # slant length of the glass
    g_bot = np.array([0, 3.062 - 3.20, 5.385 - EZ])
    # outline (half width, height) from the bottom edge: a trapezoid with its corners eased
    OUTLINE = [(0.100, 0.0), (0.105, 0.020), (0.090, 0.245), (0.080, 0.270)]
    poly2 = [(-OUTLINE[0][0], 0.0), (OUTLINE[0][0], 0.0)] + [(u, v) for u, v in OUTLINE[1:]] + [(-u, v) for u, v in OUTLINE[:0:-1]]
    q3 = lambda u, v, d=0.0: g_bot + X * u + g_up * v + g_n * d
    glass_pts = [q3(u, v) for u, v in poly2]
    for k in range(1, len(glass_pts) - 1):
        glass += [glass_pts[0] + EYE, glass_pts[k] + EYE, glass_pts[k + 1] + EYE]
    # the bezel: a mitred ring round the glass (1.4 cm wide, 1.2 cm deep, centred on the glass plane); the bottom edge sits
    # in the pedestal and is left open
    rim, dep = 0.014, 0.006
    nP = len(poly2)
    outn = []
    for i in range(nP):
        a, b = np.array(poly2[i]), np.array(poly2[(i + 1) % nP])
        e = b - a; e /= np.linalg.norm(e)
        outn.append(np.array([e[1], -e[0]]))
    outer2 = []
    for i in range(nP):
        n1, n2 = outn[i - 1], outn[i]
        outer2.append(np.array(poly2[i]) + (n1 + n2) / (1 + n1 @ n2) * rim)
    for i in range(1, nP):
        j = (i + 1) % nP
        a, b = q3(*poly2[i]), q3(*poly2[j])
        a2, b2 = q3(*outer2[i]), q3(*outer2[j])
        on = X * outn[i][0] + g_up * outn[i][1]
        m.flat([a - g_n * dep, b - g_n * dep, b2 - g_n * dep, a2 - g_n * dep], sw['dgrey'], normal=-g_n)
        m.flat([a + g_n * dep, b + g_n * dep, b2 + g_n * dep, a2 + g_n * dep], sw['dgrey'], normal=g_n)
        m.flat([a2 - g_n * dep, b2 - g_n * dep, b2 + g_n * dep, a2 + g_n * dep], sw['dgrey'], normal=on)
        m.flat([a - g_n * dep, b - g_n * dep, b + g_n * dep, a + g_n * dep], sw['dgrey'], normal=-on)
    for s in (-1, 1):                                          # the two side posts, up the glass's sides to the shoulders
        base = q3(s * (0.105 + rim + 0.012), 0.0)
        m.box(base + g_up * 0.110, (X, g_up, g_n), (0.006, 0.110, 0.007), sw['dgrey'])
    hud = dict(centre=q3(0, 0.135) + EYE, normal=-g_n, up=g_up, width=0.170, height=0.190)

    # -- the AHCP: tilted on the left of the glareshield, beside the windscreen bar (a painted face)
    ahcp = atlas.paint(220, 130, p_ahcp)
    ax_ = (X, unit([0, 1, -0.55]), unit([0, 0.55, 1]))
    ac_ = np.array([0.325, 3.083 - 3.20, 5.40 - EZ])
    m.box(ac_, ax_, (0.095, 0.010, 0.060), sw['black'], front=(1, 1, ahcp, -X, ax_[2]))

    # -- the sill liners' feet and the console tops
    fy = FLOOR_Y
    z_back = BACK_Z
    bulk = atlas.paint(180, 70, p_bulkhead)
    kick = atlas.paint(180, 60, p_kick)
    zk = panel_front_z(BOT)
    # consoles: top (painted plates), inner side, front, back; the outer side stays against the wall
    for sgn in (1, -1):
        xi, xo = CON_IN * sgn, CON_OUT * sgn
        zf_ = panel_front_z(CONSOLE_Y)
        length = zf_ - (z_back + 0.01)
        con = atlas.paint(int(round((CON_OUT - CON_IN) * 600)), int(round(length * 600)), p_console(sgn, length))
        top = [[xi, CONSOLE_Y, z_back + 0.01], [xo, CONSOLE_Y, z_back + 0.01], [xo, CONSOLE_Y, zf_], [xi, CONSOLE_Y, zf_]]
        # (the image's right is the pilot's right: for the left console that is toward the inner side)
        s_i, s_o = (1, 0) if sgn > 0 else (0, 1)
        m.face(top, [uv_of(con, s_i, 0), uv_of(con, s_o, 0), uv_of(con, s_o, 1), uv_of(con, s_i, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, z_back + 0.01], [xi, fy, zf_], [xi, CONSOLE_Y, zf_], [xi, CONSOLE_Y, z_back + 0.01]], sw['panel_d'],
               normal=[-sgn, 0, 0])
        m.flat([[xi, fy, z_back + 0.01], [xo, fy, z_back + 0.01], [xo, CONSOLE_Y, z_back + 0.01], [xi, CONSOLE_Y, z_back + 0.01]],
               sw['panel_d'], normal=[0, 0, -1])
    # floor liner, rear bulkhead (lower part painted, the upper part to the rail band flat black), kick panel
    m.flat([[-CON_IN, fy, z_back], [CON_IN, fy, z_back], [CON_IN, fy, zk], [-CON_IN, fy, zk]], sw['floor'], normal=[0, 1, 0])
    yb = 2.70 - 3.20
    m.quad_tex([[0.58, fy, z_back], [-0.58, fy, z_back], [-0.58, yb, z_back], [0.58, yb, z_back]], bulk, normal=[0, 0, 1])
    m.flat([[0.58, yb, z_back], [-0.58, yb, z_back], [-0.47, 2.86 - 3.20, z_back], [0.47, 2.86 - 3.20, z_back]], sw['panel_d'],
           normal=[0, 0, 1])
    m.quad_tex([[CON_IN, fy, zk], [-CON_IN, fy, zk], [-CON_IN, BOT, zk], [CON_IN, BOT, zk]], kick, normal=[0, 0, -1])
    # rudder pedals (arms to the floor, the plates tilted toward the pilot)
    for s in (1, -1):
        m.box([s * 0.13, -0.80, zk - 0.060], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, -0.86, zk - 0.040], (X, Y, Z), (0.014, 0.060, 0.030), sw['dgrey'])

    # -- the two throttles (left console): the quadrant, two levers with their HOTAS grips
    tz = 4.76 - EZ
    m.box([0.440, CONSOLE_Y + 0.011, tz], (X, Y, Z), (0.085, 0.011, 0.140), sw['dgrey'])
    for xl in (0.405, 0.475):
        lev = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
        m.box([xl, CONSOLE_Y + 0.075, tz + 0.04], lev, (0.008, 0.062, 0.010), sw['metal'])
        gax = (X, unit([0, 1, 0.45]), unit([0, -0.45, 1]))
        m.box([xl, CONSOLE_Y + 0.150, tz + 0.080], gax, (0.019, 0.050, 0.032), sw['black'])
        m.box([xl, CONSOLE_Y + 0.204, tz + 0.096], gax, (0.015, 0.008, 0.028), sw['dgrey'])             # top: the slew / mic hat
        m.box(np.array([xl, CONSOLE_Y + 0.160, tz + 0.080]) - gax[2] * 0.037 + gax[1] * 0.012, gax, (0.007, 0.007, 0.004),
              sw['red'] if xl < 0.44 else sw['metal'])                                                   # the boat / speed brake switch
    m.box([0.440, CONSOLE_Y + 0.022, tz - 0.10], (X, Y, Z), (0.055, 0.012, 0.014), sw['metal'])           # the throttle friction lever
    # switch blocks on both console tops, between and aft of the plates
    for s in (1, -1):
        for zr in ((-0.45, -0.40), (-0.34, -0.29))[:2]:
            for xc in (0.34, 0.39, 0.44, 0.49, 0.54):
                m.box([s * xc, CONSOLE_Y + 0.005, zr[0] + 0.0], (X, Y, Z), (0.011, 0.005, 0.011), sw['metal'])
                m.box([s * xc, CONSOLE_Y + 0.020, zr[0] - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])), (0.0025, 0.012, 0.0025),
                      sw['black'])

    # -- the centre stick: the boot (fixed) and the stick itself (into the stick bone), pivot at the foot between the knees
    FOOT = np.array([0.0, fy + 0.007, 5.000 - EZ])
    m.box(FOOT + [0, 0.012, 0], (X, Y, Z), (0.060, 0.012, 0.060), sw['dgrey'])                         # base plate
    m.box(FOOT + [0, 0.050, 0], (X, Y, Z), (0.032, 0.038, 0.032), sw['black'])                         # rubber boot
    stick = Mesh()
    col_ax = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
    stick.box(FOOT + col_ax[1] * 0.250, col_ax, (0.012, 0.215, 0.012), sw['metal'])     # the column
    g_ax = (X, unit([0, 1, -0.22]), unit([0, 0.22, 1]))
    gc = FOOT + col_ax[1] * 0.470 + g_ax[2] * 0.012
    stick.box(gc, g_ax, (0.023, 0.075, 0.031), sw['black'])                                           # the grip
    stick.box(gc + g_ax[1] * 0.075, g_ax, (0.017, 0.006, 0.023), sw['dgrey'])                         # top cap with the trim hat
    stick.box(gc + g_ax[1] * 0.068 + g_ax[2] * 0.010, g_ax, (0.005, 0.004, 0.005), sw['red'])
    stick.box(gc + g_ax[2] * 0.032 + g_ax[1] * 0.022, g_ax, (0.008, 0.017, 0.005), sw['metal'])       # trigger
    stick.box(gc - g_ax[2] * 0.032 + g_ax[1] * 0.035, g_ax, (0.014, 0.020, 0.003), sw['dgrey'])       # pinky: nose wheel steering
    stick.box(gc + g_ax[1] * 0.040 + g_ax[2] * 0.026, g_ax, (0.014, 0.005, 0.006), sw['mgrey'])       # weapon release
    stick.box(gc + g_ax[1] * 0.034, g_ax, (0.025, 0.010, 0.033), sw['dgrey'])                         # mid-grip band

    # -- ACES II ejection seat, reclined 10 degrees, its back through the headrest behind the head
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    rec = math.radians(10)
    BU = np.array([0, math.cos(rec), -math.sin(rec)])            # up the seat back
    BN = np.array([0, math.sin(rec), math.cos(rec)])             # out of the back, toward the pilot's front
    pan_y = -0.76
    Q0 = np.array([0, pan_y, (4.30 - EZ) + (0.0 - pan_y) * math.tan(rec)])    # back line at the pan: through z 4.30 at the eye's height
    ax = (X, BU, BN)
    m.box([0, pan_y - 0.105, Q0[2] + 0.18], (X, Y, Z), (0.185, 0.050, 0.200), sw['frame'])           # survival kit / base
    m.box([0, pan_y - 0.030, Q0[2] + 0.19], (X, Y, Z), (0.200, 0.030, 0.185), sw['cushion'],
          front=(1, 1, cushion, -X, Z))                                                              # seat pan cushion
    for s in (1, -1):
        m.box([s * 0.215, pan_y + 0.02, Q0[2] + 0.19], (X, Y, Z), (0.012, 0.065, 0.195), sw['frame'])  # bucket sides
        m.box(Q0 + BU * 0.37 + X * s * 0.175 - BN * 0.055, ax, (0.018, 0.57, 0.024), sw['frame'])      # guide rails
    m.box(Q0 + BU * 0.40 - BN * 0.02, ax, (0.18, 0.40, 0.030), sw['cushion'],
          front=(2, 1, backt, X, BU))                                                                # back cushion
    m.box(Q0 + BU * 0.42 - BN * 0.085, ax, (0.205, 0.42, 0.030), sw['frame'])                        # back shell
    m.box(Q0 + BU * 0.93 - BN * 0.045, ax, (0.150, 0.110, 0.095), sw['frame'],
          front=(2, 1, headbox, X, BU))                                                              # headbox (parachute container)
    m.box(Q0 + BU * 0.80 + BN * 0.012, ax, (0.090, 0.065, 0.020), sw['cushion'])                     # headrest pad
    # the ejection handle: one, on the front of the pan between the knees, yellow and black striped
    m.box([0, pan_y + 0.012, Q0[2] + 0.405], (X, Y, Z), (0.055, 0.016, 0.022), sw['yellow'], front=(1, 1, stripes, -X, Z))
    m.box([0, pan_y - 0.012, Q0[2] + 0.405], (X, Y, Z), (0.012, 0.016, 0.012), sw['black'])
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


# the tub's interior: from just ahead of the rear bulkhead (z 4.09) over the dash block (z 5.24 to 5.54) to the HUD stands
# (z 5.71): the small parts standing in it (seat, stick, HUD stands, switch boxes, rail cleats) go whole, except the arch
R_LO, R_HI = np.array([-0.58, 2.20, 4.08]), np.array([0.58, 3.60, 6.00])
CON_Z0, CON_Z1, CON_X0, CON_X1, CON_Y1 = 4.095, 5.245, 0.25, 0.60, 2.72


def old_interior(geo):
    """{bone: set(poly index)} of the airframe's own loose interior: (1) every vertex-connected part lying wholly inside the
    box R (the seat, the stick, the HUD stands, boxes, cleats), except the arch's crown pieces (they reach above y 3.3 ahead
    of z 5.0); (2) the faces of the console boxes still attached to the shell (centre above the floor, between the bulkhead
    and the dash block, 0.25 to 0.60 out, below y 2.72), except the dash block's own rear-wall faces and the wall pieces
    at the band. The walls, floor, rear bulkhead, rail bands, arch, bars, the dash block and the nose stay."""
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
            if np.all(V.min(0) >= R_LO) and np.all(V.max(0) <= R_HI) and not (V.min(0)[2] >= 5.0 and V.max(0)[1] >= 3.3):
                sel.update(fl)
        if b['name'] == SOURCE_BONES[0]:
            N = np.array(pm['normals'], float) * np.array([1, 1, -1])
            for i, poly in enumerate(pm['polys']):
                if i in sel or not poly:
                    continue
                V = P[[v[0] for v in poly]]
                c = V.mean(0)
                if not (CON_Z0 <= c[2] <= CON_Z1 and CON_X0 <= abs(c[0]) < CON_X1 and 2.2505 < c[1] <= CON_Y1):
                    continue
                if V[:, 2].min() < 4.085 or (V[:, 0].min() * np.sign(c[0]) >= 0.545 and c[1] < 2.45):
                    continue                                      # (parts poking out of the bulkhead; the floor strip along the wall)
                if c[2] >= 5.22 and N[poly[0][1]][2] < -0.9:
                    continue                                      # the dash block's rear wall
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
    """the seat and eye of the new cockpit into the vehicle data (x left, y up, z forward, blocks)"""
    ex, ey, ez = EYE_NEW
    seat = data['Seats'][0]
    seat['Position'] = [0, round(ey - SEAT_BELOW, 9), round(ez + SEAT_AHEAD, 9)]
    data['Attachments'][seat['CameraPos']['EyeAttachment']]['Position'] = [ex, ey, ez]


STICK_SURFACES = [
    {'Bone': 'control_stick_roll', 'Parent': 'hull', 'Axis': [0, 0, 1], 'MaxDeflectionDegrees': 11.0,
     'ControlWeights': {'ElevatorUp': 0, 'RightRoll': 1, 'RudderRight': 0}},
    {'Bone': 'control_stick_pitch', 'Parent': 'control_stick_roll', 'Axis': [1, 0, 0], 'MaxDeflectionDegrees': 14.0,
     'ControlWeights': {'ElevatorUp': 1, 'RightRoll': 0, 'RudderRight': 0}},
]


def add_stick_bones(g, foot_px, with_mesh=None):
    """the airframe has no stick: the roll bone, the pitch bone and the wreck piece carrying the stick mesh (and the
    cockpit's wreck bone) are added where a bone of the same pattern sits in the F-15C / F-16C airframes"""
    bones = g['bones']
    names = [b['name'] for b in bones]
    hull = next(b for b in bones if b['name'] == 'hull')
    parent_hull = hull['name']
    fallback = any(b['name'] == 'bvpNativeFallbackFrame' for b in bones)

    def put(b, after):
        if b['name'] in names:
            bones[names.index(b['name'])].update(b)
            return
        at = names.index(after) + 1 if after in names else len(bones)
        bones.insert(at, b); names.insert(at, b['name'])
    put({'name': 'control_stick_roll', 'parent': parent_hull, 'pivot': list(foot_px)}, 'rudder_right')
    put({'name': 'control_stick_pitch', 'parent': 'control_stick_roll', 'pivot': list(foot_px)}, 'control_stick_roll')
    put({'name': STICK_BONE, 'parent': 'control_stick_pitch', 'pivot': list(foot_px)}, SOURCE_BONES[0])
    put({'name': BONE, 'parent': parent_hull, 'pivot': list(hull['pivot'])}, STICK_BONE)


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
    foot_px = [round(float((foot + EYE)[0]) * 16, 5), round(float((foot + EYE)[1]) * 16, 5),
               round(-float((foot + EYE)[2]) * 16, 5)]
    add_stick_bones(g, foot_px)
    for b in g['bones']:
        if b['name'] == BONE:
            b['poly_mesh'] = pm
        if b['name'] == STICK_BONE:
            b['poly_mesh'] = stick.poly_mesh()
    surfaces = asset['AircraftRig']['Surfaces']
    names = [s['Bone'] for s in surfaces]
    for s in STICK_SURFACES:
        s = dict(s); s['Pivot'] = list(foot_px)
        if s['Bone'] in names:
            surfaces[names.index(s['Bone'])] = s
        else:
            surfaces.append(s)
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
        keep = ~((np.abs(c[:, 0]) < 0.14) & (c[:, 1] > -0.12) & (c[:, 1] < 0.12) & (c[:, 2] > 0.62) & (c[:, 2] < 0.85))
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    move_eye(data)
    print(f'a_10: {removed} old interior faces removed; new cockpit {len(pm["polys"])} faces, {len(gauges)} game gauges, '
          f'{len(displays)} display(s), HUD combiner {len(glass) // 3} triangles; stick foot {foot_px} px; '
          f'atlas {atlas.used()} of {sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    write_json(DATA, data, 2)
    fb = json.load(open(FALLBACK, encoding='utf-8'))
    add_stick_bones(fb['minecraft:geometry'][0], foot_px)
    write_json(FALLBACK, fb, None, newline=False)
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])
