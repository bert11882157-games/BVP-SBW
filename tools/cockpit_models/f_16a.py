#!/usr/bin/env python3
"""F-16A Block 10 (USAF Thunderbirds) cockpit, built by hand for the F-16A.mtb airframe: one standalone script.

The airframe brought its own interior (an ACES II seat, a pilot figure, a panel, a coaming, a sight ring), all a little
rough and none of them in the layout of the type. This script cuts exactly that interior out of the hull pieces (a
tub-shaped region inside the walls, floor and sill, from behind the seat to the windscreen base; the walls, floor,
sill bands, spine and the nose are kept, so the exterior stays as it was) and puts a modelled cockpit in its place.
The early Block 1 to 15 cockpit is not the Block 50 one: no up-front controller (ICP / DED) and no MFDs, a small
monochrome radar scope in the middle of the panel under the HUD, round dials for everything else, the radar / FCC and
the EW / stores control panels on the panel's wings and consoles, a narrow-field-of-view HUD with its control panel on
its own face:

  * the main instrument panel in three flat facets (centre 8 degrees back, the wings turned 28 degrees toward the
    pilot, tops sloping down under the glass): the radar scope (a green CRT, game display RADAR) top centre with the
    angle-of-attack and G dials either side, airspeed / attitude (ADI) / altimeter in a row under it, the vertical
    speed, HSI and radar altimeter below, the FCC radar control and fuel selector panels at the foot; the RWR scope,
    the EW / master-arm panel and the landing gear panel on the left wing; round engine dials (rpm, FTIT, fuel
    quantity) and the caution panel on the right wing;
  * the black coaming running from the panel lip forward to the windscreen base, one smooth deck that meets the nose;
  * the narrow-field HUD (GEC / Marconi, pre wide-angle) on it: one unit, a low wide housing whose HUD control panel
    (BRT, CONT, DAY / NITE, ALT source) faces the pilot 6 cm above the coaming, and ONE wide, low combiner glass in a
    thick rounded frame whose side members run down as the two posts; the glass (added to CanopyGlass) is centred on
    the neutral eye line, slightly larger than life, and HeadUpDisplay is the picture rectangle inside it;
  * side consoles (throttle on the left, the fixed-stick armrest and side stick on the right), liners, floor, kick
    panel, rudder pedals;
  * an ACES II seat reclined 30 degrees at its real place, with the rails, the headbox, the ejection handles, and a
    rear bulkhead behind it.

Paint lives in a free area of textures/entity/f_16a.png (no UV of the model, the native fallback or the wreck lands
there; the burnt copy goes to entity_dead). The instruments the game draws are written to CockpitGauges and
FlightDisplays, the HUD picture to HeadUpDisplay; the stick bones' pivots (geo and AircraftRig) go to the new foot of
the side stick. The seat and eye in the vehicle data are the airframe's (same coordinates as the F-16C); this script
does not write the data file.

Order: git checkout the airframe's files, run tools/vehgen/f16a_pylons.py, canopy_f_16a.py, then this script (it
appends the HUD combiner glass to the canopy's CanopyGlass). Running it again rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is
written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/f_16a.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_16a.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_16a.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_16a.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_16a.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/f_16a.png')

BONE = 'wreck_fuselage_1__cockpit'
THR = []                                 # the throttle levers (throttle_lib), one bone each
SOURCE_BONES = ('wreck_fuselage_1__hull', 'wreck_fuselage_0__hull')
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'
ATLAS = [(0, 600, 2048, 1400)]        # free texture area (x, y, w, h): no UV of the model, its fallback or the wreck lands here
TW, TH = 2048, 6144


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

PANEL = (56, 58, 62)           # the F-16's dark grey panel and consoles
PANEL_D = (40, 42, 45)
PANEL_L = (92, 96, 100)
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
WHITE = (226, 228, 220)
GREEN_LAMP = (60, 150, 70)
AMBER = (214, 140, 30)
DED = (80, 220, 100)


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

EYE = np.zeros(3)                           # filled from the vehicle data: the mesh is built eye-relative


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


def hud_combiner(m, rect, bot, up, n, H, hb, ht, ch, bw, bw_top, bt):
    """A HUD combiner standing on its foot at `bot` (eye-relative), leaning along `up` (n = the glass normal): the glass
    outline is H high, half hb wide at the foot and ht at the top, the top corners cut off by ch. The frame is a bar
    bt thick round the sides and top (bw wide, bw_top on the top edge), always outside the glass edge, with the two side
    bars running 16 mm further down as the posts, and a base plate under the foot. Returns the glass triangles
    (eye-relative points, three per triangle) for CanopyGlass."""
    ol = [(-hb, 0.0), (hb, 0.0), (ht, H - ch), (ht - ch, H), (-(ht - ch), H), (-ht, H - ch)]
    P = [bot + X * r + up * v for r, v in ol]
    tris = [P[0], P[1], P[2], P[0], P[2], P[3], P[0], P[3], P[4], P[0], P[4], P[5]]
    centre = bot + up * H / 2
    for i in range(1, 6):
        a, b = P[i].copy(), P[(i + 1) % 6].copy()
        d = unit(b - a)
        if i == 1:
            a = a - d * 0.016                    # the right post
        if i == 5:
            b = b + d * 0.016                    # the left post
        w = bw_top if i == 3 else bw
        p = unit(np.cross(n, d))
        if (a + b) @ p / 2 - centre @ p < 0:
            p = -p                               # outward in the glass plane
        ln = float(np.linalg.norm(b - a))
        m.box((a + b) / 2 + p * w / 2, (d, p, n), (ln / 2 + w / 2, w / 2, bt / 2), rect)
    m.box(bot - up * 0.008, (X, up, n), (hb + bw + 0.002, 0.008, bt / 2), rect)
    return tris


X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# Absolute heights of the airframe: floor top 1.68, hull sill 2.22, side walls at x 0.50 below 2.08 leaning in to 0.41
# at 2.22, the canopy rails 2.29 high; the eye at y 2.603, z 2.869 (canopy top 0.34 over it, the rear arch 0.62 behind).

FLOOR_Y = -0.900                # the floor liner, 2 cm over the old floor
SILL_Y = -0.383                 # the hull's sill
CONSOLE_Y = -0.503              # console tops (abs 2.10)
CON_IN, CON_OUT = 0.255, 0.470  # the consoles' inner side and outer edge
BACK_Z = -0.45                  # the rear bulkhead behind the seat (abs 2.42)

LEAN = math.radians(8)          # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])    # pilot's right
P0 = np.array([0.0, -0.40, 0.70])
HINGE = 0.250                   # centre facet half width
WING = math.radians(28)         # wings turned toward the pilot
WALL_END = 0.395                # wings end here (under the canopy rail)
WING_LEN = (WALL_END - HINGE) / math.cos(WING)
TOP_C = -0.152                  # centre facet top: the coaming's lip
TOP_H, TOP_W = -0.157, -0.343   # wing top at the hinge and at the wall (sloping down under the glass)
BOT_C = -0.680
BOT_W = CONSOLE_Y               # wings stand on the consoles


def wall_x(y_abs):
    """inner face of the hull's side wall at absolute height y (the old tub's)"""
    return 0.495 if y_abs <= 2.08 else max(0.41, 0.5 - (y_abs - 2.08) * 0.643)


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
        return TOP_C if self.side == 0 else TOP_H + (TOP_W - TOP_H) * a / WING_LEN

    def bottom(self):
        return BOT_C if self.side == 0 else BOT_W

    def extent(self):
        return (-HINGE, HINGE) if self.side == 0 else (0.0, WING_LEN)


CENTRE, RIGHT, LEFT = Facet(0), Facet(1), Facet(-1)

# Instrument layout of the F-16A Block 10 panel (no ICP / MFDs: a small radar scope under the HUD, round dials, the
# FCC / radar control panels on the wing facets). Positions: facet, a (see Facet.at), y (height from the eye). Sizes:
# dial diameter, or w x h. 'game': drawn by the game. Everything stands inside its facet (the wings' bottom is the
# console top at -0.503).
LAYOUT = [
    ('radar',   CENTRE, 0.000, -0.256, ('display', 'RADAR', 0.110), 'radar scope (monochrome CRT)'),
    ('aoa',     CENTRE, -0.172, -0.256, ('game', 'AOA', 0.046), 'angle of attack'),
    ('gmeter',  CENTRE, 0.172, -0.256, ('game', 'G_METER', 0.046), 'accelerometer'),
    ('ias',     CENTRE, -0.145, -0.386, ('game', 'AIRSPEED', 0.064), 'airspeed / Mach indicator'),
    ('adi',     CENTRE, 0.000, -0.392, ('game', 'ATTITUDE', 0.082), 'attitude director indicator'),
    ('alt',     CENTRE, 0.145, -0.386, ('game', 'ALTIMETER', 0.064), 'altimeter'),
    ('vvi',     CENTRE, -0.130, -0.488, ('game', 'VVI', 0.050), 'vertical velocity'),
    ('hsi',     CENTRE, 0.000, -0.500, ('game', 'HEADING', 0.084), 'horizontal situation indicator'),
    ('ralt',    CENTRE, 0.130, -0.488, ('game', 'RADAR_ALT', 0.050), 'radar altimeter'),
    ('fcc',     CENTRE, -0.105, -0.598, ('paint_rect', 0.085, 0.058), 'FCC / radar control panel'),
    ('clock',   CENTRE, 0.000, -0.598, ('game', 'FILLER_CLOCK', 0.040), 'clock'),
    ('fuelsel', CENTRE, 0.105, -0.598, ('paint_rect', 0.085, 0.058), 'fuel quantity selector'),
    ('pedal',   CENTRE, 0.000, -0.658, ('paint_rect', 0.100, 0.022), 'pedal adjust plate'),
    ('rwr',     LEFT,   0.082, -0.318, ('game', 'RWR', 0.060), 'threat warning (RWR) scope'),
    ('misc',    LEFT,   0.082, -0.404, ('paint_rect', 0.110, 0.050), 'EW / master arm panel'),
    ('gear',    LEFT,   0.082, -0.462, ('paint_rect', 0.110, 0.046), 'landing gear handle and lights'),
    ('rpm',     RIGHT,  0.082, -0.296, ('game', 'ENGINE_RPM', 0.042), 'engine rpm'),
    ('ftit',    RIGHT,  0.082, -0.342, ('game', 'EGT', 0.042), 'FTIT'),
    ('fuel',    RIGHT,  0.082, -0.388, ('game', 'FUEL_QTY', 0.042), 'fuel quantity'),
    ('warning', RIGHT,  0.082, -0.458, ('annunciator', 0.125, 0.060), 'caution panel'),
]
# the caution panel's lamps (the game lights each from its condition; NONE never lights)
LAMPS = [
    {'Text': 'ENG|FIRE', 'Condition': 'FIRE', 'Colour': 'red'},
    {'Text': 'ENGINE|FAULT', 'Condition': 'ENGINE_OUT', 'Colour': 'red'},
    {'Text': 'FUEL|LOW', 'Condition': 'LOW_FUEL', 'Colour': 'amber'},
    {'Text': 'MASTER|CAUTION', 'Condition': 'MASTER_CAUTION', 'Colour': 'amber'},
    {'Text': 'GEAR|DOWN', 'Condition': 'GEAR_DOWN', 'Colour': 'green'},
    {'Text': 'GEAR|UNSAFE', 'Condition': 'GEAR_TRANSIT', 'Colour': 'red'},
    {'Text': 'STALL', 'Condition': 'STALL', 'Colour': 'red'},
    {'Text': 'OVER G', 'Condition': 'OVER_G', 'Colour': 'amber'},
    {'Text': 'RWR|LOCK', 'Condition': 'RWR_LOCK', 'Colour': 'amber'},
    {'Text': 'LAUNCH', 'Condition': 'MISSILE_LAUNCH', 'Colour': 'red'},
    {'Text': 'HYD|PRESS', 'Condition': 'NONE', 'Colour': 'amber'},
    {'Text': 'ELEC|SYS', 'Condition': 'NONE', 'Colour': 'amber'},
]

GAUGE_LIFT = 0.004
DISPLAY_LIFT = 0.003
RIM = 0.16


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
    """the facet face: dark grey, seams, screws and the dark recesses behind every instrument"""
    a0, a1 = facet.extent()
    W, H = rect_px
    y_bot = facet.bottom(); y_top = max(facet.top(a0), facet.top(a1))
    SS = Atlas.SS

    def to_px(a, y):
        s = (a - a0) / (a1 - a0)
        if facet.side == -1:
            s = 1 - s
        return (s * W * SS, (1 - (y - y_bot) / (y_top - y_bot)) * H * SS)

    def fn(d, im):
        noisy(d, im, PANEL, 4, seed=3 + facet.side)
        for (a, y) in [(a0 + 0.010, y_bot + 0.010), (a1 - 0.010, y_bot + 0.010), (a0 + 0.010, y_top - 0.016),
                       (a1 - 0.010, y_top - 0.016)]:
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
            d.line([0, to_px(0, -0.640)[1], W * SS, to_px(0, -0.640)[1]], fill=PANEL_D + (255,), width=SS * 2)
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_fcc(d, im):
    """the fire control computer / radar control panel: range and mode selectors, a gain knob, the SMS-style push-lights"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .10), 'FCC  RADAR', H * .11)
    for k, (lab, x) in enumerate((('RNG', .24), ('MODE', .76))):
        d.ellipse([S * x - H * .17, H * .20, S * x + H * .17, H * .54], fill=(34, 34, 36, 255), outline=(140, 140, 140, 255), width=3)
        a = math.radians(-50 + 70 * k)
        d.line([S * x, H * .37, S * x + H * .15 * math.sin(a), H * .37 - H * .15 * math.cos(a)], fill=WHITE + (255,), width=5)
        text(d, (S * x, H * .64), lab, H * .10)
    for k, lab in enumerate(['CRM', 'STBY', 'TWS']):
        x0 = S * (.07 + .30 * k)
        d.rectangle([x0, H * .74, x0 + S * .26, H * .94], fill=(70, 74, 80, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x0 + S * .13, H * .84), lab, H * .10, fill=(235, 235, 228))


def p_hud_ctrl(d, im):
    """the narrow-field HUD's control panel, facing the pilot: brightness and contrast knobs, DAY / NITE and the altitude source"""
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['BRT', 'CONT', 'D/N', 'ALT', 'TEST']):
        x = S * (0.10 + k * 0.20)
        if k in (0, 1):
            d.ellipse([x - H * .17, H * .26 - H * .17, x + H * .17, H * .26 + H * .17], fill=(18, 18, 18, 255),
                      outline=(120, 120, 120, 255), width=3)
            d.line([x, H * .26, x + H * .08, H * .26 - H * .12], fill=WHITE + (255,), width=4)
        else:
            d.rectangle([x - H * .30, H * .08, x + H * .30, H * .44], fill=(150, 150, 150, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (x, H * .70), lab, H * .20)

def p_instr(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .12), 'INSTR MODE', H * .1)
    d.ellipse([S * .22, H * .24, S * .78, H * .70], fill=(34, 34, 36, 255), outline=(140, 140, 140, 255), width=3)
    d.line([S * .5, H * .47, S * .64, H * .32], fill=WHITE + (255,), width=6)
    for k, lab in enumerate(['TCN', 'NAV', 'ILS']):
        text(d, (S * (0.18 + 0.32 * k), H * .84), lab, H * .09)


def p_fuelsel(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .12), 'FUEL QTY SEL', H * .09)
    d.ellipse([S * .22, H * .24, S * .78, H * .70], fill=(34, 34, 36, 255), outline=(140, 140, 140, 255), width=3)
    d.line([S * .5, H * .47, S * .36, H * .34], fill=WHITE + (255,), width=6)
    for k, lab in enumerate(['TEST', 'NORM', 'RSVR']):
        text(d, (S * (0.18 + 0.32 * k), H * .84), lab, H * .09)


def p_pedal(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(180, 184, 188, 255), outline=(60, 60, 60, 255), width=3)
    text(d, (S * .5, H * .5), 'PEDAL ADJ', H * .62, fill=(30, 30, 30), bold=True)


def p_misc(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    for k, lab in enumerate(['ECM', 'ARM', 'CHAFF']):
        x = S * (0.18 + 0.32 * k)
        d.rectangle([x - 8, H * .22, x + 8, H * .52], fill=(170, 170, 170, 255))
        d.ellipse([x - 14, H * .12, x + 14, H * .28], fill=(200, 200, 200, 255))
        text(d, (x, H * .72), lab, H * .12)
    d.rectangle([S * .74, H * .15, S * .94, H * .55], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .5, H * .92), 'EW  MASTER ARM', H * .1)


def p_gear(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .06, H * .1, S * .30, H * .9], fill=(226, 186, 30, 255), outline=(10, 10, 10, 255), width=3)
    d.ellipse([S * .10, H * .5, S * .26, H * .78], fill=(30, 30, 30, 255))
    text(d, (S * .18, H * .30), 'LG', H * .2, fill=(20, 20, 20), bold=True)
    for k, (x, y) in enumerate([(0.62, 0.30), (0.46, 0.62), (0.78, 0.62)]):
        d.ellipse([S * x - 10, H * y - 10, S * x + 10, H * y + 10], fill=(40, 130, 56, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .62, H * .86), 'WHEELS', H * .12)


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(130, 20, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_eyebrow(label, colour):
    def fn(d, im):
        S, H = im.width, im.height
        d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
        n = 3
        for k in range(n):
            lamp(d, [k * S / n + 3, H * .14, (k + 1) * S / n - 3, H * .86], colour, None, lit=False)
        text(d, (S * .5, H * .5), label, H * .34, fill=(160, 150, 130))
    return fn


def p_console(side):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, PANEL, 4, seed=20 + side)
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
        if side > 0:                      # the throttle's slot on the left console
            d.rectangle([S * .40, H * .36, S * .60, H * .66], fill=(8, 8, 8, 255))
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

def build(atlas, lamps, cols):
    m = Mesh()
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                lgrey=LGREY, frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED,
                                                metal=METAL, floor=FLOOR, bezel=(30, 31, 33),
                                                coaming=(20, 21, 22)).items()}
    gauges, displays, glass = [], [], []
    TOWARD = np.array([0, 0.3, -1.0])

    # -- main panel facets (front face painted, slab 2 cm deep)
    ppm = 700
    for f in (CENTRE, LEFT, RIGHT):
        a0, a1 = f.extent()
        y_top = max(f.top(a0), f.top(a1))
        W = int(round((a1 - a0) * ppm)); Hh = int(round((y_top - f.bottom()) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W, Hh, panel_paint(f, (W, Hh)))
        n = 6
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            yb = f.bottom()
            t0 = (f.top(aa0) - yb) / (y_top - yb); t1 = (f.top(aa1) - yb) / (y_top - yb)
            pts = [f.at(aa0, yb), f.at(aa1, yb), f.at(aa1, f.top(aa1)), f.at(aa0, f.top(aa0))]
            if f.side == -1:
                uvs = [uv_of(rect, 1 - s0, 0), uv_of(rect, 1 - s1, 0), uv_of(rect, 1 - s1, t1), uv_of(rect, 1 - s0, t0)]
            else:
                uvs = [uv_of(rect, s0, 0), uv_of(rect, s1, 0), uv_of(rect, s1, t1), uv_of(rect, s0, t0)]
            m.face(pts, uvs, normal=f.N)
        back = -f.N * 0.02
        corners = [f.at(a0, f.bottom()), f.at(a1, f.bottom()), f.at(a1, f.top(a1)), f.at(a0, f.top(a0))]
        m.flat([c + back for c in corners], sw['panel_d'], normal=-f.N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['panel_d'], toward=mid + (mid - mid0))

    # -- instruments
    cache = {}
    painters = {'fcc': p_fcc, 'fuelsel': p_fuelsel, 'pedal': p_pedal, 'misc': p_misc, 'gear': p_gear}
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
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 0, 'Center': r5(c + EYE + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
            # the radar scope: a square dark hood round the tube with the range / gain knobs along the foot
            _, n_, u_, r_ = frame(c, f.N, f.U)
            t = 0.020; pr = 0.012
            for cc, ww, hh in ((u_ * (w / 2 + t / 2), w + 2 * t, t), (-u_ * (w / 2 + t / 2), w + 2 * t, t),
                               (r_ * (w / 2 + t / 2), t, w), (-r_ * (w / 2 + t / 2), t, w)):
                m.box(c + cc + n_ * pr / 2, (r_, u_, n_), (ww / 2, hh / 2, pr / 2), sw['black'])
            for k in (-2, -1, 0, 1, 2):                       # knobs and push-lights on the lower bezel
                m.box(c - u_ * (w / 2 + t / 2) + r_ * k * 0.021 + n_ * (pr + 0.003), (r_, u_, n_), (0.007, 0.006, 0.003),
                      sw['mgrey'] if k % 2 else sw['lgrey'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 160 if w >= h else 80
            ph = max(16, int(round(pw * h / w)))
            rect = cache.get(name) or atlas.paint(pw, ph, painters[name])
            cache[name] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])

    # -- the coaming: the visor over the panel, then one smooth deck down to the windscreen base. Heights in absolute
    # blocks: the lip 2.458, the deck meeting the nose (4.30) at 2.285; the edges tuck under the canopy's rails.
    Ea = EYE
    stations = [  # (z abs, half width of the deck there, centre height)
        (3.545, 0.395, 2.458), (3.60, 0.385, 2.458), (3.70, 0.372, 2.447), (3.80, 0.356, 2.428), (3.93, 0.338, 2.398),
        (4.05, 0.312, 2.360), (4.18, 0.275, 2.322), (4.30, 0.240, 2.288)]
    EDGE_Y = 2.265
    SZ_, SHW, SYC = [s[0] for s in stations], [s[1] for s in stations], [s[2] for s in stations]
    cs = sw['coaming']

    def deck_y(x, z):
        """the coaming's height: the panel's top across the middle and down its wings, falling away to the edge that
        tucks under the canopy's rail"""
        hw = float(np.interp(z, SZ_, SHW)); yc = float(np.interp(z, SZ_, SYC))
        ax_ = abs(x)
        yp = 2.603 + TOP_C if ax_ <= HINGE else 2.603 + TOP_H + (TOP_W - TOP_H) * ((ax_ - HINGE) / math.cos(WING)) / WING_LEN
        return min(yc, yp + 0.007, EDGE_Y + 1.5 * (hw - ax_))

    def deck_row(z):
        hw = float(np.interp(z, SZ_, SHW))
        xs = np.linspace(-hw, hw, 13)
        return [np.array([x, deck_y(x, z), z]) - Ea for x in xs]
    rows = [deck_row(z) for z in SZ_]
    NJ = 12
    for i in range(len(rows) - 1):
        for j in range(NJ):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], cs, normal=[0, 1, 0])
    # the visor's lip over the panel: a front strip and the underside back to the panel top
    lip = rows[0]
    for j in range(NJ):
        a, b = lip[j], lip[j + 1]
        da, db = a - [0, 0.014, 0], b - [0, 0.014, 0]
        m.flat([a, b, db, da], cs, normal=[0, 0, -1])
        m.flat([da, db, db + [0, 0, 0.05], da + [0, 0, 0.05]], cs, normal=[0, -1, 0])
    # the deck's closed ends at the sides (down to the sill), so nothing is open under the glass
    for j in (0, NJ):
        for i in range(len(rows) - 1):
            p, q = rows[i][j], rows[i + 1][j]
            m.flat([p, q, [q[0], 2.222 - Ea[1], q[2]], [p[0], 2.222 - Ea[1], p[2]]], cs, toward=[0, 0.3, 0])
    # -- the HUD: one unit (the housing on the coaming with its control strip low on the pilot's face, two side posts)
    # and the combiner glass centred on the neutral eye line, leaning back toward the pilot
    # The GEC / Marconi narrow-field HUD of the Block 1-15 F-16A: ONE wide, low combiner glass in a thick rounded frame, its
    # two side members running on down as the posts into the unit's housing, the HUD control panel on the housing's face
    # toward the pilot. The housing is a low wide block, its strip 6 cm over the coaming; nothing of it stands in front of
    # the glass, whose middle sits on the neutral eye line.
    hz0, hz1, hy0, hy1, hhw = 0.700, 0.870, -0.158, -0.085, 0.150
    ctrl = atlas.paint(320, 80, p_hud_ctrl)
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    lean = math.radians(22)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h = 0.170
    g_bot = np.array([0, hy1, 0.800])
    glass += [p + Ea for p in hud_combiner(m, sw['dgrey'], g_bot, g_up, g_n, g_h, 0.117, 0.110, 0.026, 0.016, 0.016, 0.016)]
    # the picture: the rectangle inside the glass (the glass is 0.22 wide, 0.17 high)
    hud = dict(centre=g_bot + g_up * g_h / 2 + Ea, normal=-g_n, up=g_up, width=0.190, height=0.130)
    # eyebrow caution lamps on the coaming either side of the HUD
    for sgn, lab, col in ((1, 'MASTER', AMBER), (-1, 'FIRE', RED)):
        rect = atlas.paint(96, 28, p_eyebrow(lab, col))
        yd = deck_y(sgn * 0.215, 2.869 + 0.80) - Ea[1]
        m.box([sgn * 0.215, yd + 0.0035, 0.80], (X, Y, Z), (0.045, 0.0035, 0.014), sw['black'])
        m.rect_tex([sgn * 0.215, yd + 0.0075, 0.80], [0, 1, 0], [0, 0, 1], 0.088, 0.026, rect)

    # -- side walls' liners, consoles, floor, rear bulkhead, kick panel
    fy = FLOOR_Y
    wall_t = lambda ya: wall_x(ya) - 0.010           # (liner just inside the hull's wall)
    z_back, z_front = BACK_Z, 0.63
    bulk = atlas.paint(180, 100, p_bulkhead)
    for sgn in (1, -1):
        # liner above the console, along the leaning wall to the sill
        ya0, ya1 = 2.603 + CONSOLE_Y, 2.215
        qs = [[sgn * wall_t(ya0), CONSOLE_Y, z_back], [sgn * wall_t(ya0), CONSOLE_Y, z_front],
              [sgn * wall_t(ya1), ya1 - 2.603, z_front], [sgn * wall_t(ya1), ya1 - 2.603, z_back]]
        m.flat(qs, sw['panel_d'], normal=[-sgn, 0.3, 0])
        # console: top, inner side, front, back
        wing = LEFT if sgn > 0 else RIGHT

        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(a, BOT_W)[2]
        xi, xo = CON_IN * sgn, CON_OUT * sgn
        zf_i, zf_o = wing_bottom_z(CON_IN), wing_bottom_z(CON_OUT)
        con = atlas.paint(64, 340, p_console(sgn))
        top = [[xi, CONSOLE_Y, z_back + 0.05], [xo, CONSOLE_Y, z_back + 0.05], [xo, CONSOLE_Y, zf_o], [xi, CONSOLE_Y, zf_i]]
        m.face(top, [uv_of(con, 0, 0), uv_of(con, 1, 0), uv_of(con, 1, 1), uv_of(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, z_back + 0.05], [xi, fy, zf_i], [xi, CONSOLE_Y, zf_i], [xi, CONSOLE_Y, z_back + 0.05]], sw['panel_d'],
               normal=[-sgn, 0, 0])
        m.flat([[xi, fy, z_back + 0.05], [xo, fy, z_back + 0.05], [xo, CONSOLE_Y, z_back + 0.05], [xi, CONSOLE_Y, z_back + 0.05]],
               sw['panel_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf_i], [xo, fy, zf_o], [xo, CONSOLE_Y, zf_o], [xi, CONSOLE_Y, zf_i]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xo, fy, z_back + 0.05], [xo, fy, zf_o], [xo, CONSOLE_Y, zf_o], [xo, CONSOLE_Y, z_back + 0.05]], sw['panel_d'],
               normal=[-sgn, 0, 0])
    # floor liner, rear bulkhead and the kick panel under the panel
    zk = CENTRE.at(0, BOT_C)[2]
    m.flat([[-CON_IN, fy, z_back], [CON_IN, fy, z_back], [CON_IN, fy, zk], [-CON_IN, fy, zk]], sw['floor'], normal=[0, 1, 0])
    m.quad_tex([[CON_OUT, fy, z_back], [-CON_OUT, fy, z_back], [-CON_OUT, SILL_Y, z_back], [CON_OUT, SILL_Y, z_back]], bulk,
               normal=[0, 0, 1])
    m.flat([[-CON_IN, fy, zk], [CON_IN, fy, zk], [CON_IN, BOT_C, CENTRE.at(0, BOT_C)[2]], [-CON_IN, BOT_C, CENTRE.at(0, BOT_C)[2]]],
           sw['panel_d'], normal=[0, 0, -1])
    # rudder pedals
    for s in (1, -1):
        m.box([s * 0.13, -0.66, 0.54], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, -0.72, 0.58], (X, Y, Z), (0.014, 0.035, 0.07), sw['dgrey'])

    # -- throttle (left console): quadrant, lever and the grip with its thumb switches
    # The lever is its own bone (throttle_lever), hinged under the console top, modelled at idle (throttle_lib).
    tz = 0.34
    THR.clear()
    THR.extend(throttle_lib.jet_levers(m, Mesh, sw, 'f16', x=0.345, top_y=CONSOLE_Y, z_idle=tz - 0.07,
                                       lean0=-20.0, ab=22.0))
    # switch rows on both console tops
    for s in (1, -1):
        for zr in (-0.20, -0.13, -0.06, 0.40, 0.47, 0.54):
            if s > 0 and 0.15 < zr < 0.55:
                continue
            for xc in (0.285, 0.315, 0.345, 0.375, 0.405):
                m.box([s * xc, CONSOLE_Y + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, CONSOLE_Y + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])),
                      (0.0025, 0.012, 0.0025), sw['black'])

    # -- the fixed armrest and the side stick on the right console (the grip goes into the stick bone)
    SX, SZ = -0.285, 0.22
    m.box([SX, CONSOLE_Y + 0.012, SZ - 0.14], (X, Y, Z), (0.062, 0.012, 0.095), sw['cushion'])        # armrest pad
    m.box([SX, CONSOLE_Y + 0.006, SZ], (X, Y, Z), (0.045, 0.006, 0.045), sw['dgrey'])                 # base plate
    m.box([SX, CONSOLE_Y + 0.020, SZ], (X, Y, Z), (0.026, 0.014, 0.026), sw['black'])                 # rubber boot
    stick = Mesh()
    col_ax = (X, unit([0, 1, 0.08]), unit([0, -0.08, 1]))
    stick.box([SX, CONSOLE_Y + 0.075, SZ + 0.006], col_ax, (0.010, 0.062, 0.010), sw['metal'])
    g_ax = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
    gc = np.array([SX, CONSOLE_Y + 0.165, SZ + 0.026])
    stick.box(gc, g_ax, (0.022, 0.058, 0.030), sw['black'])                                           # the grip
    stick.box(gc + g_ax[1] * 0.056, g_ax, (0.016, 0.006, 0.022), sw['dgrey'])                         # top cap / hat
    stick.box(gc + g_ax[1] * 0.052 + g_ax[2] * 0.008, g_ax, (0.005, 0.004, 0.005), sw['red'])
    stick.box(gc + g_ax[2] * 0.030 + g_ax[1] * 0.012, g_ax, (0.008, 0.012, 0.005), sw['metal'])       # trigger
    stick.box(gc - g_ax[2] * 0.031 + g_ax[1] * 0.025, g_ax, (0.014, 0.020, 0.003), sw['dgrey'])       # pinky switch
    foot = np.array([SX, CONSOLE_Y + 0.008, SZ])

    # -- ACES II ejection seat, reclined 30 degrees, its back through the headrest behind the head
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    rec = math.radians(30)
    BU = np.array([0, math.cos(rec), -math.sin(rec)])            # up the seat back
    BN = np.array([0, math.sin(rec), math.cos(rec)])             # out of the back, toward the pilot's front
    pan_y = -0.74
    Q0 = np.array([0, pan_y, (-0.17) + (-0.05 - pan_y) * math.tan(rec)])    # back line at the pan: through the headrest face
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
    return m, gauges, displays, glass, stick, hud, foot


def old_interior(geo):
    """{bone: set(poly index)} of the airframe's own interior: faces of the hull pieces lying wholly inside the tub
    (inside the walls and the sill line, above the floor) from behind the seat to the windscreen base: the old seat,
    panel, coaming, HUD and the wedge under the windscreen. The walls, floor, sill bands, spine and nose stay."""
    out = {}
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] not in SOURCE_BONES:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        sel = set()
        for i, poly in enumerate(pm['polys']):
            if not poly:
                continue
            c = P[[v[0] for v in poly]].mean(0)
            if not (2.46 <= c[2] <= 4.30 and 1.70 < c[1] < 3.05):
                continue
            xw = wall_x(c[1]) - 0.005 if c[1] <= 2.22 else (0.405 if c[1] <= 2.35 else 0.55)
            if abs(c[0]) < xw:
                sel.add(i)
        if sel:
            out[b['name']] = sel
    return out


# ---------------------------------------------------------------- main

def write_json(path, obj, indent):
    text_ = json.dumps(obj, indent=indent, ensure_ascii=False) if indent else json.dumps(obj, separators=(',', ':'))
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text_ + ('\n' if indent else ''))


def write_asset(path, asset):
    """the asset keeps its layout: indented, with the canopy glass triangle list on one line at the end"""
    glass_json = asset.pop('CanopyGlass', None)
    text_ = json.dumps(asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text_ = text_[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        asset['CanopyGlass'] = glass_json
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text_ + '\n')


def main(argv):
    global EYE
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    data = json.load(open(DATA, encoding='utf-8'))
    seat = data['Seats'][0]
    EYE = np.array(data['Attachments'][seat['CameraPos']['EyeAttachment']]['Position'], float)
    g = geo['minecraft:geometry'][0]
    rm = old_interior(geo)
    removed = 0
    for b in g['bones']:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            removed += len(rm[b['name']])
    lamps, cols = LAMPS, 4
    atlas = Atlas()
    mesh, gauges, displays, glass, stick, hud, foot = build(atlas, lamps, cols)
    pm = mesh.poly_mesh()
    bones = [b for b in g['bones'] if b['name'] != BONE]
    hull = next(b for b in bones if b['name'] == 'hull')
    at = next(i for i, b in enumerate(bones) if b['name'] == SOURCE_BONES[0]) + 1
    bones.insert(at, {'name': BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    g['bones'] = bones
    thr = throttle_lib.add_bones(g['bones'], THR, BONE, EYE)
    # the stick: its three bones' pivots to the side stick's foot (model pixels)
    foot_px = [round(float((foot + EYE)[0]) * 16, 5), round(float((foot + EYE)[1]) * 16, 5),
               round(-float((foot + EYE)[2]) * 16, 5)]
    for b in g['bones']:
        if b['name'] in ('control_stick_roll', 'control_stick_pitch', STICK_BONE):
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
        keep = ~((np.abs(c[:, 0]) < 0.16) & (c[:, 1] > -0.14) & (c[:, 1] < 0.14) & (c[:, 2] > 0.55) & (c[:, 2] < 0.90))
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    # the pilot's seat and eye (vehicle data: x left, y up, z forward, blocks): the eye above the seat as built
    print(f'f_16a: {removed} old interior faces removed; new cockpit {len(pm["polys"])} faces, {len(gauges)} game gauges, '
          f'{len(displays)} display(s), HUD combiner {len(glass) // 3} triangles; stick foot {foot_px} px; throttle bones {[(n, p) for n, p, _ in thr]}; '
          f'atlas {atlas.used()} of {sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])
