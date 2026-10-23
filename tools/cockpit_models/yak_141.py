#!/usr/bin/env python3
"""Yak-141 (Yak-41M prototype, call sign 77) cockpit, built by hand for the Yak-141.mtb airframe: one standalone script.

The airframe (commits dcfb17d8 / 250ac801, nose gear raised) brought its own interior: a generic toolbox seat group in a narrow
tub, an old dash with a coaming and a gunsight stub, a windscreen arch with a centre rib and rear arch ribs. This script cuts
exactly those elements out of the hull pieces (the CUT_POLYS table: the faces of source elements 147-154 / 389-396 (windscreen
arch and rib), 751-755 (sight), 731-747 / 759-760 (dash, coaming, side consoles), 607-660 (the seat group) and 765-767 / 770-772 (rear
arch ribs); the tub walls, floor, sill shoulders, rear bulkhead, dorsal spine and nose stay, so the exterior stays as it was) and puts
a modelled cockpit in their place. The late-Soviet single-seat layout (MiG-29 / Su-27 generation, the Yak-141 being the first
Soviet VTOL fighter with a head-up display and CRT displays planned):

  * the main instrument panel in three facets (centre 10 degrees back, the wings turned 28 degrees toward the pilot): two live
    CRT displays side by side (the MFI-type tactical / stores display left, the radar display right), a caution panel and a
    warning panel above them, the analogue flight group below (airspeed, KPP attitude director, HSI, altimeter), the angle of
    attack and g dials, the nozzle-angle indicator and gear panel on the left wing, the radar warning scope, tachometer, fuel
    quantity and weapons panel on the right wing. Placards are Russian;
  * the black coaming running from the panel lip forward to the windscreen base, one smooth deck that meets the nose;
  * the wide-angle HUD: ONE combiner glass, wider at the top than at the foot, top corners cut, in a thin frame, on a low housing
    with its ЯРК / КОНТР / ФИЛЬТР strip; nothing stands beside the glass; the glass (added to CanopyGlass) is centred on the neutral
    eye line and HeadUpDisplay is the picture rectangle inside it;
  * side consoles: the throttle (РУД) with the nozzle-angle lever on the left, the VTOL mode panel, switch rows; the right
    console with the circuit-breaker rows; a centre stick (its grip goes into the stick bone, whose pivots, in the geometry and
    in AircraftRig, move to the stick's foot), kick panel, rudder pedals;
  * the Zvezda K-36LV ejection seat: pan, back, parachute container, headbox with its cheeks and drogue box, striped handles,
    harness.

Paint lives in a free area of textures/entity/yak_141.png (no UV of the model or the wreck lands there; the burnt copy goes to
entity_dead). The instruments the game draws are written to CockpitGauges and FlightDisplays (pilot only, Seat 0), the warning
panels are annunciator panels, the HUD picture goes to HeadUpDisplay (the client keys the airframe pass removed).

Order: restore the airframe's files (run_yak141_cockpit.ps1 does), run canopy_yak_141.py, then this script (it appends the HUD
combiner glass to the canopy's CanopyGlass). Running it again on the restored files rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is
written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/yak_141.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import canopy_yak_141 as CA          # noqa: E402  (the glass's foot line: where the sill and the nose deck are)
import cockpit_lib as CL             # noqa: E402  (the annunciator lamp sets)
import free_atlas as FA              # noqa: E402  (the texture area is checked free)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/yak_141.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/yak_141.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/yak_141.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/yak_141.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/yak_141.png')

BONE = 'wreck_fuselage_0__cockpit'
SOURCE_BONES = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
ATLAS = [(1100, 0, 2900, 2900)]        # free texture area (x, y, w, h): no UV of the model, its fallback or the wreck lands here
TW, TH = 4096, 3072

# The old interior faces of the restored airframe's hull pieces (poly index ranges, found by matching each face of the source
# elements 147-154 / 389-396 windscreen arch and centre rib, 751-755 gunsight, 731-732 / 736 / 738 / 740-747 / 759-760 instrument
# panel, coaming and side consoles, 607-660 the seat group and 765-767 / 770-772 rear arch ribs of part 0 to its geometry; a face
# shared with a kept neighbour loses one copy only). The tub walls, floor, rear bulkhead (757 / 758), dorsal spine and nose stay.
CUT_POLYS = {
    'wreck_fuselage_0__hull': (614, [(93, 105), (234, 246), (288, 300), (312, 313), (318, 319), (320, 325), (330, 331), (332, 336),
                                     (342, 402)]),
    'wreck_fuselage_1__hull': (1449, [(306, 307), (309, 312), (313, 345), (645, 646), (648, 651), (652, 1037),
                                      (1063, 1064), (1066, 1069), (1070, 1084), (1092, 1093), (1095, 1098), (1099, 1113)]),
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

# ---------------------------------------------------------------- colours

PANEL = (44, 47, 49)           # the dark grey instrument panel
PANEL_D = (30, 32, 34)
BLACK = (22, 23, 25)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
LGREY = (150, 154, 156)
TEAL = (62, 130, 134)          # the console and liner turquoise of the Soviet cockpits
TEAL_D = (46, 100, 104)
FRAME = (62, 68, 64)           # seat structure
CUSHION = (84, 88, 70)
HEADPAD = (118, 122, 104)
HARNESS = (52, 56, 48)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)
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
    bt thick round the sides and top (bw wide, bw_top on the top edge), always outside the glass edge, and a base plate
    under the foot; no post stands below or beside the glass. Returns the glass triangles (eye-relative points, three
    per triangle) for CanopyGlass."""
    ol = [(-hb, 0.0), (hb, 0.0), (ht, H - ch), (ht - ch, H), (-(ht - ch), H), (-ht, H - ch)]
    P = [bot + X * r + up * v for r, v in ol]
    tris = [P[0], P[1], P[2], P[0], P[2], P[3], P[0], P[3], P[4], P[0], P[4], P[5]]
    centre = bot + up * H / 2
    for i in range(1, 6):
        a, b = P[i].copy(), P[(i + 1) % 6].copy()
        d = unit(b - a)
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
# Absolute heights of the airframe (the model's own, in blocks): tub floor 1.50, the wall tops 2.45 (z 4.25) falling to 2.28 at the
# windscreen base, the inner wall at x 0.45 (below y 2.0) / 0.40 (2.0 .. 2.2) / 0.35 above it ahead of z 5.05; the eye at y 2.70, z 4.90;
# the dorsal spine's top 2.90 behind the canopy (z < 4.25), the rear bulkhead's face at z 4.40.

EYE_ABS = np.array([0.0, 2.70, 4.90])
CONSOLE_Y = -0.640              # console tops (abs 2.06)
CON_IN = 0.255                  # the consoles' inner side (beside the seat)
CON_OUT_REAR, CON_OUT_FRONT = 0.440, 0.385      # their outer edges at the wall (the wall steps in ahead of z 5.05)
Z_STEP = 0.150                  # where the console narrows
BACK_Z = -0.250                 # the consoles' rear end (abs 4.65)
FLOOR_Y = -1.200                # the tub floor (abs 1.50)

LEAN = math.radians(10)         # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])    # pilot's right
P0 = np.array([0.0, -0.64, 0.44])
HINGE = 0.200                   # centre facet half width
WING = math.radians(28)         # wings turned toward the pilot
WALL_END = 0.345                # wings end here, at the wall above the console
WING_LEN = (WALL_END - HINGE) / math.cos(WING)
TOP_C = -0.215                  # centre facet top: the coaming's lip
TOP_H, TOP_W = -0.222, -0.275   # wing top at the hinge and at the wall (sloping down under the glass)
BOT_C = -0.640
BOT_W = CONSOLE_Y               # wings stand on the consoles


def sill_rel(z_rel):
    """the skin's top under the glass foot (eye-relative y) at station z_rel"""
    return CA.skin_y(z_rel + EYE_ABS[2]) - EYE_ABS[1]


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

# Instrument layout. Positions: facet, a (see Facet.at), y (height from the eye). Sizes: dial diameter, or w x h. 'game': a dial
# drawn by the game; 'display': a CRT of the given width (the game draws the housing 12 % of the width round it); 'annunciator':
# a lamp panel drawn and lit by the game; 'paint_rect': a placard painted into the texture.
LAYOUT = [
    ('caut',    CENTRE, -0.121, -0.256, ('annunciator', 0.138, 0.058, 'eyebrow_l'), 'caution panel'),
    ('modep',   CENTRE,  0.000, -0.256, ('paint_rect', 0.058, 0.058), 'take-off / landing mode lights'),
    ('warn',    CENTRE,  0.121, -0.256, ('annunciator', 0.138, 0.058, 'eyebrow_r'), 'warning panel'),
    ('mfi',     CENTRE, -0.099, -0.384, ('display', 'STORES', 0.145), 'MFI-type tactical display (CRT)'),
    ('radar',   CENTRE,  0.099, -0.384, ('display', 'RADAR', 0.145), 'radar display (CRT)'),
    ('asi',     CENTRE, -0.146, -0.557, ('game', 'AIRSPEED', 0.054), 'airspeed'),
    ('adi',     CENTRE, -0.048, -0.557, ('game', 'ATTITUDE', 0.070), 'KPP attitude director'),
    ('hsi',     CENTRE,  0.045, -0.557, ('game', 'HEADING', 0.060), 'HSI'),
    ('alt',     CENTRE,  0.140, -0.557, ('game', 'ALTIMETER', 0.054), 'altimeter'),
    ('aoa',     LEFT,    0.082, -0.305, ('game', 'AOA', 0.050), 'angle of attack'),
    ('gm',      LEFT,    0.082, -0.382, ('game', 'G_METER', 0.050), 'g meter'),
    ('noz',     LEFT,    0.082, -0.470, ('paint_rect', 0.118, 0.080), 'nozzle angle indicator and VTOL mode lights'),
    ('gear',    LEFT,    0.082, -0.575, ('paint_rect', 0.118, 0.052), 'landing gear lever and lights'),
    ('rwr',     RIGHT,   0.082, -0.308, ('game', 'RWR', 0.066), 'SPO radar warning'),
    ('rpm',     RIGHT,   0.082, -0.392, ('game', 'ENGINE_RPM', 0.050), 'engine tachometer'),
    ('fuel',    RIGHT,   0.082, -0.466, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
    ('weap',    RIGHT,   0.082, -0.562, ('paint_rect', 0.118, 0.066), 'weapons selector panel'),
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
                    w = h = spec[2] * 1.24 + 0.012
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (y_top - y_bot) * H * SS * math.cos(LEAN)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(18, 22, 22, 255))
        if facet.side == 0:
            yl = to_px(0, -0.306)[1]
            d.line([0, yl, W * SS, yl], fill=PANEL_D + (255,), width=SS * 2)
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_modep(d, im):
    """take-off / landing mode lights over the centre"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    for k, (lab, col) in enumerate([('ВЗЛЁТ', (60, 150, 70)), ('ПОСАД', (214, 140, 30))]):
        y0 = H * (0.08 + 0.46 * k)
        d.rectangle([S * .08, y0, S * .92, y0 + H * .38], fill=(8, 22, 10, 255) if k == 0 else (26, 16, 6, 255),
                    outline=(70, 72, 70, 255), width=2)
        text(d, (S * .5, y0 + H * .19), lab, H * .24, fill=col + (255,), bold=True)


def p_hud_ctrl(d, im):
    """the HUD's control strip (the face toward the pilot; its lower fifth is under the coaming)"""
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['ЯРК', 'КОНТР', 'ФИЛЬТР']):
        x = S * (0.19 + k * 0.31)
        if k < 2:
            d.ellipse([x - H * .14, H * .26 - H * .14, x + H * .14, H * .26 + H * .14], fill=(18, 18, 18, 255),
                      outline=(120, 120, 120, 255), width=3)
        else:
            d.rectangle([x - H * .22, H * .10, x + H * .22, H * .42], fill=(150, 150, 150, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (x, H * .66), lab, H * .17)


def p_nozzle(d, im):
    """nozzle angle indicator (0 .. 95 degrees) and the VTOL mode lights"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .10), 'СОПЛО', H * .14, fill=WHITE + (255,), bold=True)
    d.rectangle([S * .06, H * .22, S * .94, H * .52], fill=(10, 12, 12, 255), outline=(110, 112, 110, 255), width=2)
    for k in range(10):                                   # the scale, 0 to 95 degrees
        x = S * (0.10 + 0.80 * k / 9)
        d.line([x, H * .26, x, H * .36 if k % 3 else H * .42], fill=WHITE + (255,), width=2)
    text(d, (S * .10, H * .47), '0', H * .08, fill=WHITE + (255,))
    text(d, (S * .90, H * .47), '95', H * .08, fill=WHITE + (255,))
    d.polygon([(S * .42, H * .24), (S * .48, H * .24), (S * .45, H * .34)], fill=(255, 138, 28, 255))
    for k, (lab, col) in enumerate([('ВЗЛ', (60, 150, 70)), ('ВИС', (214, 140, 30)), ('ПЕР', (60, 150, 70))]):
        x0 = S * (0.06 + 0.30 * k)
        d.rectangle([x0, H * .62, x0 + S * .26, H * .90], fill=(14, 20, 14, 255), outline=(70, 72, 70, 255), width=2)
        text(d, (x0 + S * .13, H * .76), lab, H * .15, fill=col + (255,), bold=True)


def p_gear(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .06, H * .10, S * .34, H * .90], fill=(226, 186, 30, 255), outline=(10, 10, 10, 255), width=3)
    d.ellipse([S * .10, H * .50, S * .30, H * .84], fill=(30, 30, 30, 255))
    text(d, (S * .20, H * .30), 'ШАССИ', H * .14, fill=(20, 20, 20), bold=True)
    for k, (x, y) in enumerate([(0.62, 0.30), (0.50, 0.66), (0.74, 0.66)]):
        d.ellipse([S * x - 12, H * y - 12, S * x + 12, H * y + 12], fill=(40, 130, 56, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .62, H * .92), 'ВЫП', H * .13)


def p_weap(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .09), 'ВООРУЖЕНИЕ', H * .12, fill=WHITE + (255,), bold=True)
    labs = ['ПУШКА', 'Р-73', 'Р-27', 'НАР', 'БОМБЫ', 'СБРОС']
    for k, lab in enumerate(labs):
        c, r = k % 3, k // 3
        x0 = S * (0.05 + c * 0.31); y0 = H * (0.24 + r * 0.38)
        d.rectangle([x0, y0, x0 + S * .27, y0 + H * .30], fill=(70, 74, 80, 255), outline=(10, 10, 10, 255), width=2)
        d.ellipse([x0 + S * .10, y0 + H * .03, x0 + S * .17, y0 + H * .10], fill=(60, 150, 70, 255) if k != 5 else RED + (255,))
        text(d, (x0 + S * .135, y0 + H * .20), lab, H * .09)


def p_console(side):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, TEAL, 4, seed=20 + side)
        rng = np.random.default_rng(40 + side)
        y = H * 0.02
        heights = [0.10, 0.14, 0.09, 0.13, 0.11, 0.12, 0.13, 0.09] if side > 0 else \
                  [0.12, 0.10, 0.14, 0.09, 0.15, 0.10, 0.12, 0.09]
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
        if side > 0:                      # the throttle quadrant's slot on the left console
            d.rectangle([S * .22, H * .22, S * .78, H * .56], fill=(8, 8, 8, 255))
    return fn


def p_vtol(d, im):
    """the VTOL mode panel on the left console: lift-engine and nozzle switches"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .09), 'РЕЖИМ ВЗЛЁТА', H * .10, fill=WHITE + (255,), bold=True)
    labs = [('ПОДЪЁМН ДВ', (60, 150, 70)), ('СОПЛО', (214, 140, 30)), ('КРЕЙС', (60, 150, 70)), ('ВЕРТИК', (214, 140, 30))]
    for k, (lab, col) in enumerate(labs):
        c, r = k % 2, k // 2
        x0 = S * (0.05 + c * 0.48); y0 = H * (0.22 + r * 0.34)
        d.rectangle([x0, y0, x0 + S * .43, y0 + H * .26], fill=(14, 20, 14, 255), outline=(70, 72, 70, 255), width=2)
        text(d, (x0 + S * .215, y0 + H * .13), lab, H * .085, fill=col + (255,), bold=True)
    for k in range(3):
        x = S * (0.22 + 0.28 * k)
        d.rectangle([x - 9, H * .80, x + 9, H * .96], fill=(180, 180, 180, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .5, H * .775), 'ЗАЩИТА  АВТОМАТ  РУЧН', H * .06)


def p_pwr(d, im):
    """the right console's power / lighting block"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .08), 'ПИТАНИЕ', H * .10, fill=WHITE + (255,), bold=True)
    for k, lab in enumerate(['АККУМ', 'ГЕНЕР 1', 'ГЕНЕР 2', 'ПО', 'ОСВЕЩ', 'КОНДИЦ']):
        c, r = k % 3, k // 3
        x = S * (0.18 + 0.32 * c); y = H * (0.30 + 0.40 * r)
        d.rectangle([x - 8, y - 4, x + 8, y + 24], fill=(180, 180, 180, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x, y - 18), lab, H * .075)


# ---------------------------------------------------------------- the deck (the coaming from the panel lip to the nose)

# stations: (absolute z, centre height); the half width follows the canopy's foot line, the edges tuck under its frame
DECK = [(5.300, 2.487), (5.400, 2.482), (5.600, 2.475), (5.800, 2.455), (5.950, 2.400), (6.050, 2.340), (6.120, 2.295)]
NJ = 12


def build_deck(m, sw, nose_y):
    """one smooth black deck. nose_y(x, z abs) = the top of the nose skin that stays (None: none there)"""
    zs = [d[0] for d in DECK]; ys = [d[1] for d in DECK]
    ea = EYE_ABS

    def hw(z):
        return CA.foot_x(z) + 0.012 - (0.0 if z < 5.95 else 0.4 * (z - 5.95))

    def deck_y(x, z):
        yc = float(np.interp(z, zs, ys))
        ax_ = abs(x)
        yp = ea[1] + (TOP_C if ax_ <= HINGE else TOP_H + (TOP_W - TOP_H) * ((ax_ - HINGE) / math.cos(WING)) / WING_LEN) + 0.007
        edge = CA.skin_y(z) - 0.020 + 1.2 * (hw(z) - ax_)
        y = min(yc, yp if z < 5.45 else 9.0, edge)
        ny = nose_y(x, z)
        if ny is not None:
            y = min(y, ny - 0.012)                 # (meets the nose skin and goes under it)
        return y

    def row(z):
        h = hw(z)
        xs = np.linspace(-h, h, NJ + 1)
        return [np.array([x, deck_y(x, z), z]) - ea for x in xs]
    rows = [row(z) for z in zs]
    cs = sw['coaming']
    for i in range(len(rows) - 1):
        for j in range(NJ):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], cs, normal=[0, 1, 0])
    # the visor's lip over the panel: a front strip and its underside back to the panel top
    lip = rows[0]
    for j in range(NJ):
        a, b = lip[j], lip[j + 1]
        da, db = a - [0, 0.014, 0], b - [0, 0.014, 0]
        m.flat([a, b, db, da], cs, normal=[0, 0, -1])
        m.flat([da, db, db + [0, 0, 0.05], da + [0, 0, 0.05]], cs, normal=[0, -1, 0])
    # the deck's closed ends at the sides (down under the frame's band), so nothing is open under the glass
    for j in (0, NJ):
        for i in range(len(rows) - 1):
            p, q = rows[i][j], rows[i + 1][j]
            m.flat([p, q, q - [0, 0.07, 0], p - [0, 0.07, 0]], cs, toward=[0, 0.3, 0])
    return deck_y


# ---------------------------------------------------------------- the K-36LV seat

def build_seat(m, sw):
    lean = math.radians(18)
    up = np.array([0, math.cos(lean), -math.sin(lean)])
    fw = np.array([0, math.sin(lean), math.cos(lean)])
    S0 = np.array([0.0, CONSOLE_Y, -0.105])
    ax = (X, up, fw)
    # pedestal and rails down to the floor, the pan, its side boards
    m.box([0, -0.93, -0.02], (X, Y, Z), (0.17, 0.25, 0.17), sw['frame'])
    m.box([0, FLOOR_Y + 0.016, -0.05], (X, Y, Z), (0.22, 0.012, 0.24), sw['frame'])
    m.box([0, CONSOLE_Y - 0.022, 0.03], (X, Y, Z), (0.215, 0.022, 0.215), sw['cushion'])
    for s in (1, -1):
        m.box([s * 0.232, CONSOLE_Y - 0.002, 0.02], (X, Y, Z), (0.012, 0.040, 0.20), sw['frame'])
    # back: armour plate, parachute container, cushion
    m.box(S0 + up * 0.35 - fw * 0.030, ax, (0.22, 0.35, 0.025), sw['frame'])
    m.box(S0 + up * 0.43 - fw * 0.105, ax, (0.17, 0.20, 0.070), sw['black'])
    m.box(S0 + up * 0.29 + fw * 0.035, ax, (0.19, 0.29, 0.030), sw['cushion'])
    # headbox: the box, the pad on its face, the cheeks, the drogue box on top, the face-curtain handle
    m.box(S0 + up * 0.60 + fw * 0.015, ax, (0.115, 0.125, 0.045), sw['frame'])
    m.box(S0 + up * 0.60 + fw * 0.066, ax, (0.085, 0.100, 0.012), sw['headpad'])
    for s in (1, -1):
        m.box(S0 + up * 0.585 + fw * 0.060 + X * s * 0.127, ax, (0.012, 0.112, 0.060), sw['frame'])
    m.box(S0 + up * 0.745 - fw * 0.030, ax, (0.090, 0.028, 0.050), sw['black'])
    for k in range(3):
        m.box(S0 + up * 0.775 - fw * 0.005 + X * (k - 1) * 0.036, ax, (0.016, 0.008, 0.012),
              sw['yellow'] if k != 1 else sw['black'])
    # the two ejection handles, yellow and black striped, on the pan's front corners
    for s in (1, -1):
        for k in range(3):
            m.box([s * 0.238, CONSOLE_Y + 0.020, 0.115 + k * 0.034], (X, Y, Z), (0.011, 0.014, 0.017),
                  sw['yellow'] if k != 1 else sw['black'])
        m.box([s * 0.238, CONSOLE_Y + 0.010, 0.090], (X, Y, Z), (0.008, 0.012, 0.010), sw['metal'])
    # leg restraint garters and the harness: shoulder straps, lap belts and the buckle
    for s in (1, -1):
        m.box([s * 0.10, CONSOLE_Y + 0.004, 0.215], (X, Y, Z), (0.014, 0.004, 0.012), sw['metal'])
        m.box(S0 + up * 0.28 + fw * 0.072 + X * s * 0.075, ax, (0.024, 0.20, 0.004), sw['harness'])
        m.box([s * 0.125, CONSOLE_Y + 0.005, -0.060], (X, Y, Z), (0.085, 0.004, 0.026), sw['harness'])
    m.box(S0 + up * 0.075 + fw * 0.080, ax, (0.032, 0.016, 0.010), sw['metal'])


# ---------------------------------------------------------------- build

def build(atlas, nose_y):
    m = Mesh()
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                lgrey=LGREY, frame=FRAME, cushion=CUSHION, headpad=HEADPAD, harness=HARNESS,
                                                yellow=YELLOW, red=RED, metal=METAL, floor=FLOOR, bezel=(30, 31, 33),
                                                coaming=(20, 21, 22), teal=TEAL, teal_d=TEAL_D).items()}
    gauges, displays, glass = [], [], []

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
    painters = {'modep': p_modep, 'noz': p_nozzle, 'gear': p_gear, 'weap': p_weap}
    for name, f, a, y, spec, label in LAYOUT:
        kind = spec[0]
        c = f.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + EYE + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c, f.N, f.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'], back=0.0)
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            cols, lamps = CL.lamps_of(spec[3], 'soviet')
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + EYE + f.N * 0.004), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            displays.append({'Seat': 0, 'Center': r5(c + EYE + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': spec[2], 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 160 if w >= h else 80
            ph = max(16, int(round(pw * h / w)))
            rect = cache.get(name) or atlas.paint(pw, ph, painters[name])
            cache[name] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])

    # -- the coaming: the visor over the panel, then one smooth deck down to the windscreen base
    build_deck(m, sw, nose_y)

    # -- the HUD: a low housing on the coaming with its control strip, and ONE combiner glass on it, centred on the neutral eye
    # line and leaning back toward the pilot. The wide-angle HUD glass is wider at the top than at the foot, top corners cut, in a
    # thin frame; nothing stands beside it above the housing. It stands 0.13 m under the windscreen.
    hz0, hz1, hy0, hy1, hhw = 0.560, 0.720, -0.220, -0.110, 0.130
    ctrl = atlas.paint(270, 95, p_hud_ctrl)
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    lean = math.radians(22)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h = 0.170
    g_bot = np.array([0, hy1, 0.640])
    glass += [p + EYE for p in hud_combiner(m, sw['dgrey'], g_bot, g_up, g_n, g_h, 0.100, 0.145, 0.026, 0.010, 0.009, 0.013)]
    hud = dict(centre=g_bot + g_up * g_h / 2 + EYE, normal=-g_n, up=g_up, width=0.190, height=0.135)

    # -- the side consoles (two boxes each: the wall steps in ahead of z 5.05), the soffit under the panel, kick wall, pedals
    z_back = BACK_Z
    for sgn in (1, -1):
        wing = LEFT if sgn > 0 else RIGHT

        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(max(a, 0.0), BOT_W)[2] if abs(x) > HINGE else CENTRE.at(0, BOT_W)[2]
        xi = CON_IN
        zf_i, zf_o = wing_bottom_z(CON_IN), wing_bottom_z(CON_OUT_FRONT)
        con = atlas.paint(64, 340, p_console(sgn))
        for (xo, za, zb_i, zb_o) in ((CON_OUT_REAR, z_back, Z_STEP, Z_STEP), (CON_OUT_FRONT, Z_STEP, zf_i, zf_o)):
            top = [[sgn * xi, CONSOLE_Y, za], [sgn * xo, CONSOLE_Y, za], [sgn * xo, CONSOLE_Y, zb_o], [sgn * xi, CONSOLE_Y, zb_i]]
            m.face(top, [uv_of(con, 0, 0), uv_of(con, 1, 0), uv_of(con, 1, 1), uv_of(con, 0, 1)], normal=[0, 1, 0])
            m.flat([[sgn * xi, FLOOR_Y, za], [sgn * xi, FLOOR_Y, zb_i], [sgn * xi, CONSOLE_Y, zb_i], [sgn * xi, CONSOLE_Y, za]],
                   sw['teal_d'], normal=[-sgn, 0, 0])
            m.flat([[sgn * xi, FLOOR_Y, zb_i], [sgn * xo, FLOOR_Y, zb_o], [sgn * xo, CONSOLE_Y, zb_o], [sgn * xi, CONSOLE_Y, zb_i]],
                   sw['teal_d'], normal=[0, 0, 1])
        m.flat([[sgn * xi, FLOOR_Y, z_back], [sgn * CON_OUT_REAR, FLOOR_Y, z_back], [sgn * CON_OUT_REAR, CONSOLE_Y, z_back],
                [sgn * xi, CONSOLE_Y, z_back]], sw['teal_d'], normal=[0, 0, -1])
        # the step between the two boxes (facing forward on the wide one)
        m.flat([[sgn * CON_OUT_FRONT, FLOOR_Y, Z_STEP], [sgn * CON_OUT_REAR, FLOOR_Y, Z_STEP], [sgn * CON_OUT_REAR, CONSOLE_Y, Z_STEP],
                [sgn * CON_OUT_FRONT, CONSOLE_Y, Z_STEP]], sw['teal_d'], normal=[0, 0, 1])
    # the soffit under the panel and the kick wall ahead of the pedals (the footwell is closed: no view into the nose)
    zk = CENTRE.at(0, BOT_C)[2]
    zw = 0.800
    m.flat([[-CON_IN, BOT_C - 0.002, zk], [CON_IN, BOT_C - 0.002, zk], [CON_IN, BOT_C - 0.002, zw], [-CON_IN, BOT_C - 0.002, zw]],
           sw['panel_d'], normal=[0, -1, 0])
    m.flat([[-CON_IN, BOT_C - 0.002, zw], [CON_IN, BOT_C - 0.002, zw], [CON_IN, FLOOR_Y, zw], [-CON_IN, FLOOR_Y, zw]],
           sw['panel_d'], normal=[0, 0, -1])
    # rudder pedals
    for s in (1, -1):
        m.box([s * 0.13, -0.98, 0.66], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, -1.07, 0.70], (X, Y, Z), (0.014, 0.035, 0.07), sw['dgrey'])

    # -- throttle (left console): the quadrant with the throttle lever (РУД) and the shorter nozzle-angle lever, the VTOL panel
    tz = -0.06
    m.box([0.345, CONSOLE_Y + 0.011, tz], (X, Y, Z), (0.068, 0.011, 0.130), sw['dgrey'])
    lev = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
    gax = (X, unit([0, 1, 0.45]), unit([0, -0.45, 1]))
    xl = 0.322
    m.box([xl, CONSOLE_Y + 0.07, tz + 0.04], lev, (0.008, 0.060, 0.010), sw['metal'])            # throttle lever
    m.box([xl, CONSOLE_Y + 0.140, tz + 0.075], gax, (0.017, 0.044, 0.034), sw['black'])          # grip
    m.box([xl, CONSOLE_Y + 0.188, tz + 0.090], gax, (0.014, 0.008, 0.028), sw['dgrey'])
    m.box(np.array([xl, CONSOLE_Y + 0.152, tz + 0.075]) - gax[2] * 0.036 + gax[1] * 0.012, gax, (0.006, 0.006, 0.004), sw['red'])
    xn = 0.380
    m.box([xn, CONSOLE_Y + 0.045, tz + 0.01], lev, (0.007, 0.035, 0.009), sw['metal'])          # nozzle lever
    m.box([xn, CONSOLE_Y + 0.092, tz + 0.022], gax, (0.012, 0.016, 0.016), sw['red'])            # its knob
    vt = atlas.paint(80, 120, p_vtol)
    cvt = np.array([0.345, CONSOLE_Y + 0.003, 0.215])
    m.rect_tex(cvt, [0, 1, 0], [0, 0, 1], 0.110, 0.115, vt)
    m.frame_rect(cvt, [0, 1, 0], [0, 0, 1], 0.110, 0.115, 0.004, 0.005, sw['bezel'])
    pw = atlas.paint(80, 90, p_pwr)
    cpw = np.array([-0.345, CONSOLE_Y + 0.003, 0.215])
    m.rect_tex(cpw, [0, 1, 0], [0, 0, 1], 0.100, 0.100, pw)
    m.frame_rect(cpw, [0, 1, 0], [0, 0, 1], 0.100, 0.100, 0.004, 0.005, sw['bezel'])
    # switch rows on both console tops
    for s, rows in ((1, (-0.20, 0.10)), (-1, (-0.20, -0.12, -0.04, 0.04))):
        for zr in rows:
            for xc in ((0.285, 0.320, 0.355, 0.390) if s > 0 else (0.285, 0.320, 0.355, 0.390)):
                if s > 0 and zr == 0.10 and xc < 0.30:
                    continue
                m.box([s * xc, CONSOLE_Y + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, CONSOLE_Y + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])),
                      (0.0025, 0.012, 0.0025), sw['black'])

    # -- the K-36LV ejection seat
    build_seat(m, sw)

    # -- the centre stick: the boot stays; the stick and its grip go into the stick bone
    SXF, SZF = 0.0, 0.290
    foot = np.array([SXF, FLOOR_Y + 0.030, SZF])
    m.box(foot - Y * 0.012, (X, Y, Z), (0.050, 0.020, 0.050), sw['dgrey'])                         # base plate and boot
    m.box(foot + Y * 0.020, (X, Y, Z), (0.032, 0.020, 0.032), sw['black'])
    stick = Mesh()
    col_ax = (X, unit([0, 1, 0.07]), unit([0, -0.07, 1]))
    stick.box(foot + col_ax[1] * 0.30, col_ax, (0.011, 0.30, 0.011), sw['metal'])
    top = foot + col_ax[1] * 0.60
    g_ax = (X, unit([0, 1, 0.14]), unit([0, -0.14, 1]))
    gc = top + g_ax[1] * 0.045 + g_ax[2] * 0.004
    stick.box(gc, g_ax, (0.021, 0.056, 0.026), sw['black'])                                        # the grip
    stick.box(gc + g_ax[1] * 0.056, g_ax, (0.017, 0.007, 0.021), sw['dgrey'])                      # top cap / trim hat
    stick.box(gc + g_ax[1] * 0.052 + g_ax[2] * 0.008, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.027 + g_ax[1] * 0.012, g_ax, (0.007, 0.012, 0.005), sw['metal'])    # trigger
    stick.box(gc - g_ax[2] * 0.027 + g_ax[1] * 0.025, g_ax, (0.013, 0.017, 0.004), sw['dgrey'])    # thumb pad
    return m, gauges, displays, glass, stick, hud, foot


def old_interior(geo):
    """{bone: set(poly index)} of the airframe's old frame, dash, sight and seat faces (CUT_POLYS). The restored airframe is
    required: the table indexes its poly lists."""
    out = {}
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] in CUT_POLYS:
            n, rngs = CUT_POLYS[b['name']]
            if len(b['poly_mesh']['polys']) < n:       # (restored skin faces are appended behind the airframe's n polys)
                raise SystemExit(f'{b["name"]}: {len(b["poly_mesh"]["polys"])} polys, expected {n}: restore the airframe first '
                                 '(run_yak141_cockpit.ps1)')
            out[b['name']] = {i for a, z in rngs for i in range(a, z)}
    return out


def make_nose_y(geo, removed):
    """a function (x, z abs) -> the top of the airframe's skin that stays (nose deck ahead of the new deck), or None"""
    T = []
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] not in SOURCE_BONES:
            continue
        pm = b.get('poly_mesh')
        if not pm:
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for i, poly in enumerate(pm['polys']):
            if i in removed.get(b['name'], ()) or not poly:
                continue
            V = P[[v[0] for v in poly]]
            if V[:, 2].max() < 5.55 or V[:, 2].min() > 6.7 or np.abs(V[:, 0]).min() > 0.8:
                continue
            for k in range(1, len(V) - 1):
                T.append(V[[0, k, k + 1]])
    T = np.array(T).reshape(-1, 3, 3)
    A, B, C = T[:, 0], T[:, 1], T[:, 2]
    d = (B[:, 2] - C[:, 2]) * (A[:, 0] - C[:, 0]) + (C[:, 0] - B[:, 0]) * (A[:, 2] - C[:, 2])
    ok = np.abs(d) > 1e-9
    ds = np.where(ok, d, 1)

    def top(x, z):
        l1 = ((B[:, 2] - C[:, 2]) * (x - C[:, 0]) + (C[:, 0] - B[:, 0]) * (z - C[:, 2])) / ds
        l2 = ((C[:, 2] - A[:, 2]) * (x - C[:, 0]) + (A[:, 0] - C[:, 0]) * (z - C[:, 2])) / ds
        l3 = 1 - l1 - l2
        hit = ok & (l1 >= -1e-6) & (l2 >= -1e-6) & (l3 >= -1e-6)
        if not hit.any():
            return None
        y = l1[hit] * A[hit, 1] + l2[hit] * B[hit, 1] + l3[hit] * C[hit, 1]
        y = y[(y < 2.55) & (y > 2.0)]      # (the nose skin's top: not the floors and ribs inside the old shell)
        return float(y.max()) if len(y) else None
    return top


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
    nose_y = make_nose_y(geo, rm)
    removed = 0
    for b in g['bones']:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            removed += len(rm[b['name']])
    used = FA.used_mask(geo, TW, TH, skip=('__cockpit',))          # the paint area must be texels no UV of the model samples
    for x, y, w, h in ATLAS:
        if used[y:y + h, x:x + w].any():
            raise SystemExit(f'texture area {(x, y, w, h)} is used by a UV of the model')
    atlas = Atlas()
    mesh, gauges, displays, glass, stick, hud, foot = build(atlas, nose_y)
    pm = mesh.poly_mesh()
    cb = next(b for b in g['bones'] if b['name'] == BONE)
    cb['poly_mesh'] = pm
    # the stick: its three bones' pivots to the stick's foot (model pixels)
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
    asset['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    asset['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(EYE)}
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        mine = {tuple(np.round(tri, 4).ravel()) for tri in np.array(both).reshape(-1, 3, 3)}   # (an earlier run's combiner goes)
        keep = [i for i in range(len(t)) if tuple(np.round(t[i], 4).ravel()) not in mine]
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    print(f'yak_141: {removed} old frame / dash / sight / seat faces removed; new cockpit {len(pm["polys"])} faces, {len(gauges)} game gauges, '
          f'{len(displays)} display(s), HUD combiner {len(glass) // 3} triangles; stick foot {foot_px} px; '
          f'atlas {atlas.used()} of {sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])
