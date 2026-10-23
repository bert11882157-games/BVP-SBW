#!/usr/bin/env python3
"""F-22A Raptor cockpit, built by hand for the F-22A.mtb airframe (as f_16c.py): one standalone script.

The airframe (commits 672afe3a + bf9dd381) brought its own interior: an ACES II seat, a plain dash wedge under the
windscreen, console blocks and a rear bulkhead, none of them the Raptor's layout. This script cuts exactly that
interior out of the hull pieces (the tub inside the sill line, from the rear bulkhead to the windscreen base; the walls,
floor, sill shoulders, rear shelf, spine and the nose stay, so the exterior stays as it was) and puts a modelled cockpit
in its place:

  * the main instrument panel in three facets (centre 8 degrees back, the wings turned 28 degrees toward the pilot),
    laid out after the F-22A's all-glass panel: the integrated control panel (ICP) under the HUD with an up-front
    display (UFD) either side of it, under a deep black glareshield hood that stands out over them; below them the 8 x 8 in
    primary multifunction display (PMFD) in the middle with a 6.25 in secondary MFD (SMFD) left and right of it and the
    third SMFD under it; ONE small square digital standby display (a live PFD page) left of the lower SMFD; the landing gear
    panel on the left wing;
  * the black coaming running from the panel lip forward to the windscreen base, one smooth deck that meets the nose;
  * the wide-angle HUD: ONE large combiner glass, roughly square with rounded corners, in a thin frame, on a big box
    housing with its brightness strip; nothing stands beside the glass; the glass (added to CanopyGlass) is centred on the
    neutral eye line and HeadUpDisplay is the picture rectangle inside it (its corners clear of the rounded corners);
  * side consoles: the throttle quadrant with two levers on the left, the fixed armrest and the SIDE STICK on the
    right (its grip goes into the stick bone, whose pivots, in the geometry and in AircraftRig, move to the stick's foot),
    liners, floor, kick panel, rudder pedals, the rear bulkhead;
  * an ACES II seat reclined 20 degrees, with rails, headbox and the ejection handles.

Paint lives in a free area of textures/entity/f_22a.png (no UV of the model, the native fallback or the wreck lands
there; the burnt copy goes to entity_dead). The instruments the game draws are written to CockpitGauges and
FlightDisplays (PMFD radar page, SMFDs PFD / stores / radar), the UFDs are annunciator panels, the HUD picture goes to
HeadUpDisplay (the client keys the airframe pass removed).

The cut also drops the small loose airframe pieces left in and beside the cabin (stub ribs, isolated faces: welded
components under 35 cm in the cockpit zone).

Order: restore the airframe's files, run canopy_f_22a.py, then this script (it appends the HUD combiner glass to the
canopy's CanopyGlass). Running it again rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is
written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/f_22a.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import canopy_f_22a as CA        # noqa: E402  (the glass's foot line: where the sill and the nose deck are)
import cockpit_lib as CL         # noqa: E402  (the annunciator lamp sets)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_22a.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_22a.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_22a.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_22a.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/f_22a.png')

BONE = 'wreck_fuselage_0__cockpit'
THR = []                                 # the throttle levers (throttle_lib), one bone each
SOURCE_BONES = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
ATLAS = [(0, 700, 2048, 2200)]        # free texture area (x, y, w, h): no UV of the model, its fallback or the wreck lands here
TW, TH = 4096, 6144


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

PANEL = (54, 57, 61)           # the Raptor's dark grey panel and consoles
PANEL_D = (38, 40, 44)
PANEL_L = (92, 96, 100)
BLACK = (22, 23, 25)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
LGREY = (150, 154, 156)
FRAME = (64, 68, 66)           # ACES II structure
CUSHION = (78, 84, 72)
HEADPAD = (118, 122, 110)
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


def rounded_outline(w, h, r, k=5):
    """outline of a w x h rectangle with corner radius r, as (right, up) points from the foot centre line, counter-clockwise from the
    bottom edge: the F-22A's combiner is one roughly square glass with rounded corners"""
    pts = []
    for cx, cy, a0 in ((w / 2 - r, r, -90), (w / 2 - r, h - r, 0), (-w / 2 + r, h - r, 90), (-w / 2 + r, r, 180)):
        for i in range(k + 1):
            a = math.radians(a0 + 90 * i / k)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def hud_combiner_round(m, rect, bot, up, n, w, h, r, bw, bt):
    """The F-22A's wide-angle HUD combiner: ONE large glass, roughly square with rounded corners (w x h, corner radius r),
    standing on its foot at `bot` (eye-relative) and leaning along `up` (n = the glass normal). The frame is a bar bt thick
    and bw wide all round the glass edge, always outside the glass, and a base plate under the foot; nothing stands beside
    or in front of the glass. Returns the glass triangles (eye-relative points, three per triangle) for CanopyGlass."""
    ol = rounded_outline(w, h, r)
    P = [bot + X * x + up * v for x, v in ol]
    c0 = bot + up * h / 2
    tris = []
    for i in range(len(P)):
        tris += [c0, P[i], P[(i + 1) % len(P)]]
    for i in range(len(P)):
        a, b = P[i].copy(), P[(i + 1) % len(P)].copy()
        d = unit(b - a)
        p = unit(np.cross(n, d))
        if (a + b) @ p / 2 - c0 @ p < 0:
            p = -p                               # outward in the glass plane
        ln = float(np.linalg.norm(b - a))
        m.box((a + b) / 2 + p * bw / 2, (d, p, n), (ln / 2 + bw / 2, bw / 2, bt / 2), rect)
    m.box(bot - up * 0.008, (X, up, n), (w / 2 - r + 0.012, 0.008, bt / 2), rect)
    return tris


X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# Absolute heights of the airframe: floor top 1.99, the sill shoulders 2.69 (rear) falling to 2.40 (windscreen side),
# the rear box top 2.57, the old rear bulkhead face at z 4.90; the eye at y 2.982, z 5.436 (canopy crown 0.28 over it).

EYE_ABS = np.array([0.0, 2.98238, 5.43609])
FLOOR_Y = -0.960                # the floor liner, 3 cm over the old floor
CONSOLE_Y = -0.600              # console tops (abs 2.38)
CON_IN, CON_OUT = 0.260, 0.493  # the consoles' inner side and outer edge
BACK_Z = -0.515                 # the rear bulkhead behind the seat (abs 4.92, 1.6 cm in front of the old face)
WALL = 0.497                    # the liner's half width

LEAN = math.radians(8)          # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])    # pilot's right
P0 = np.array([0.0, -0.42, 0.62])
HINGE = 0.340                   # centre facet half width
WING = math.radians(28)         # wings turned toward the pilot
WALL_END = 0.493                # wings end here, at the wall liner
WING_LEN = (WALL_END - HINGE) / math.cos(WING)
TOP_C = -0.165                  # centre facet top: the coaming's lip
TOP_H, TOP_W = -0.172, -0.520   # wing top at the hinge and at the wall (sloping down under the glass)
BOT_C = -0.750
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

# Instrument layout of the F-22A panel. Positions: facet, a (see Facet.at), y (height from the eye). Sizes: dial diameter,
# or w x h. 'game': a dial drawn by the game; 'display': a colour LCD of the given width (the game draws the housing 12 %
# of the width round it); 'annunciator': a lamp panel drawn and lit by the game (the UFDs).
LAYOUT = [
    ('ufd_l',   CENTRE, -0.130, -0.215, ('annunciator', 0.100, 0.066, 'eyebrow_l'), 'left up-front display (UFD): cautions'),
    ('icp',     CENTRE,  0.000, -0.215, ('paint_rect', 0.140, 0.066), 'integrated control panel (ICP)'),
    ('ufd_r',   CENTRE,  0.130, -0.215, ('annunciator', 0.100, 0.066, 'eyebrow_r'), 'right up-front display (UFD): advisories'),
    ('smfd_l',  CENTRE, -0.233, -0.357, ('display', 'PFD', 0.160), 'left SMFD (flight)'),
    ('pmfd',    CENTRE,  0.000, -0.382, ('display', 'RADAR', 0.205), 'primary MFD (tactical)'),
    ('smfd_r',  CENTRE,  0.233, -0.357, ('display', 'STORES', 0.160), 'right SMFD (stores)'),
    ('smfd_c',  CENTRE,  0.000, -0.621, ('display', 'RADAR', 0.160), 'lower SMFD (defensive)'),
    ('sby',     CENTRE, -0.232, -0.600, ('display', 'PFD', 0.080, 'thin'), 'standby flight display (one small square digital unit: speed, attitude, altitude)'),
    ('emerg',   CENTRE,  0.175, -0.610, ('paint_rect', 0.085, 0.070), 'emergency brake / jettison panel'),
    ('ecs',     CENTRE,  0.268, -0.610, ('paint_rect', 0.075, 0.070), 'ECS / OBOGS panel'),
    ('gear',    LEFT,    0.075, -0.470, ('paint_rect', 0.075, 0.095), 'landing gear handle and lights'),
    ('cmds',    RIGHT,   0.075, -0.470, ('paint_rect', 0.075, 0.095), 'countermeasures (CMDS) panel'),
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
                    w = h = (spec[2] + 0.030) if len(spec) > 3 else spec[2] * 1.24 + 0.012
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (y_top - y_bot) * H * SS * math.cos(LEAN)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(18, 22, 22, 255))
        if facet.side == 0:
            yl = to_px(0, -0.250)[1]
            d.line([0, yl, W * SS, yl], fill=PANEL_D + (255,), width=SS * 2)
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_icp(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .05, H * .06, S * .95, H * .36], fill=(6, 14, 8, 255), outline=(60, 62, 60, 255), width=3)
    text(d, (S * .5, H * .21), 'COM1  251.000   NAV  ILS', H * .15, fill=DED + (255,))
    labs = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'ENTR', '0', 'CLR']
    kw = S * .90 / 6
    for k, lab in enumerate(labs):
        r, c = k // 6, k % 6
        x0 = S * .05 + c * kw + 3; y0 = H * (.42 + r * .30)
        d.rectangle([x0, y0, x0 + kw - 6, y0 + H * .24], fill=(70, 74, 80, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x0 + kw / 2 - 3, y0 + H * .12), lab, H * .14, fill=(235, 235, 228))


def p_hud_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['DAY', 'AUTO', 'BRT', 'CONT']):
        x = S * (0.14 + k * 0.24)
        if k in (2, 3):
            d.ellipse([x - H * .17, H * .30 - H * .17, x + H * .17, H * .30 + H * .17], fill=(18, 18, 18, 255),
                      outline=(120, 120, 120, 255), width=3)
        else:
            d.rectangle([x - H * .24, H * .10, x + H * .24, H * .46], fill=(150, 150, 150, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (x, H * .74), lab, H * .20)


def p_emerg(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .08, H * .14, S * .46, H * .70], fill=(226, 186, 30, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .27, H * .42), 'BRAKE', H * .16, fill=(20, 20, 20), bold=True)
    d.rectangle([S * .54, H * .14, S * .92, H * .70], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .73, H * .42), 'JETT', H * .16, fill=(240, 240, 232), bold=True)
    text(d, (S * .5, H * .88), 'EMERG', H * .12)


def p_ecs(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    for k, lab in enumerate(['OBOGS', 'ECS']):
        x = S * (0.28 + 0.44 * k)
        d.ellipse([x - S * .16, H * .16, x + S * .16, H * .16 + S * .32], fill=(34, 34, 36, 255), outline=(140, 140, 140, 255), width=3)
        d.line([x, H * .16 + S * .16, x + S * .09, H * .16 + S * .06], fill=WHITE + (255,), width=5)
        text(d, (x, H * .82), lab, H * .13)


def p_cmds(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .10), 'CMDS', H * .09)
    for k, lab in enumerate(['CHAFF', 'FLARE']):
        y = H * (0.30 + 0.27 * k)
        d.rectangle([S * .10, y - H * .07, S * .90, y + H * .07], fill=(8, 22, 10, 255), outline=(70, 72, 70, 255), width=2)
        text(d, (S * .30, y), lab, H * .08, fill=DED + (255,))
        text(d, (S * .76, y), '60', H * .09, fill=DED + (255,))
    d.rectangle([S * .22, H * .80, S * .78, H * .93], fill=(70, 74, 80, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .5, H * .865), 'MODE', H * .08, fill=(235, 235, 228))


def p_gear(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .08, H * .08, S * .40, H * .92], fill=(226, 186, 30, 255), outline=(10, 10, 10, 255), width=3)
    d.ellipse([S * .13, H * .50, S * .35, H * .78], fill=(30, 30, 30, 255))
    text(d, (S * .24, H * .28), 'LG', H * .16, fill=(20, 20, 20), bold=True)
    for k, (x, y) in enumerate([(0.70, 0.25), (0.58, 0.50), (0.82, 0.50)]):
        d.ellipse([S * x - 10, H * y - 10, S * x + 10, H * y + 10], fill=(40, 130, 56, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .70, H * .80), 'WHEELS', H * .09)


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
        if side > 0:                      # the throttle quadrant's slot on the left console
            d.rectangle([S * .30, H * .30, S * .70, H * .60], fill=(8, 8, 8, 255))
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


# ---------------------------------------------------------------- the deck (the coaming from the panel lip to the nose)

# stations: (absolute z, centre height); the half width follows the canopy's foot line, the edges tuck under its frame
DECK = [(6.100, 2.817), (6.200, 2.795), (6.350, 2.720), (6.500, 2.630), (6.650, 2.550), (6.780, 2.500), (6.880, 2.465),
        (6.925, 2.447)]
NJ = 12


def build_deck(m, sw, nose_y):
    """one smooth black deck. nose_y(x, z abs) = the top of the nose skin that stays (None: none there)"""
    zs = [d[0] for d in DECK]; ys = [d[1] for d in DECK]
    ea = EYE_ABS

    def hw(z):
        return min(WALL, CA.foot_x(z) + 0.012)

    def deck_y(x, z):
        yc = float(np.interp(z, zs, ys))
        ax_ = abs(x)
        yp = ea[1] + (TOP_C if ax_ <= HINGE else TOP_H + (TOP_W - TOP_H) * ((ax_ - HINGE) / math.cos(WING)) / WING_LEN) + 0.007
        edge = CA.skin_y(z) - 0.020 + 1.2 * (hw(z) - ax_)
        y = min(yc, yp if z < 6.25 else 9.0, edge)
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


# ---------------------------------------------------------------- build

def build(atlas, lamp_sets, nose_y):
    m = Mesh()
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                lgrey=LGREY, frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED,
                                                metal=METAL, floor=FLOOR, bezel=(30, 31, 33),
                                                coaming=(20, 21, 22)).items()}
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
    painters = {'icp': p_icp, 'emerg': p_emerg, 'ecs': p_ecs, 'gear': p_gear, 'cmds': p_cmds}
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
            cols, lamps = CL.lamps_of(spec[3], 'nato')
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + EYE + f.N * 0.004), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 0, 'Center': r5(c + EYE + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'LCD'})
            if len(spec) > 3:                                 # the standby unit: a thin plain bezel, no key rows
                m.frame_rect(c, f.N, f.U, w, w, 0.009, 0.010, sw['bezel'])
                continue
            # the MFD: a flat bezel with a row of option keys along each edge
            _, n_, u_, r_ = frame(c, f.N, f.U)
            t = 0.022; pr = 0.010
            m.rect_tex(c + u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
            m.rect_tex(c - u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
            m.rect_tex(c + r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
            m.rect_tex(c - r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
            for sgn in (-1, 1):                               # the bezel's outer walls
                m.box(c + u_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (w / 2 + t, 0.0015, (pr - 0.002) / 2), sw['black'])
                m.box(c + r_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (0.0015, w / 2 + t, (pr - 0.002) / 2), sw['black'])
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

    # -- the glareshield hood: a deep black hood over the ICP and the two UFDs (it stands 10 cm out over them, its underside
    # just above their tops so the lamps stay in view from the eye), with the two side cheeks curving down to the panel.
    hood_y0, hood_y1, hood_z0, hood_z1, hood_hw = -0.150, -0.105, 0.545, 0.68, 0.215
    m.box([0, (hood_y0 + hood_y1) / 2, (hood_z0 + hood_z1) / 2], (X, Y, Z),
          (hood_hw, (hood_y1 - hood_y0) / 2, (hood_z1 - hood_z0) / 2), sw['coaming'])
    for s in (1, -1):
        m.box([s * (hood_hw - 0.006), hood_y0 - 0.026, 0.6125], (X, Y, Z), (0.006, 0.026, 0.0675), sw['coaming'])

    # -- the HUD: a big box housing on the coaming behind the hood (with its brightness strip), and ONE large combiner glass on
    # it, roughly square with rounded corners, centred on the neutral eye line and leaning back toward the pilot. Nothing
    # stands beside the glass above the housing.
    hz0, hz1, hy0, hy1, hhw = 0.665, 0.905, -0.215, -0.100, 0.160
    ctrl = atlas.paint(320, 54, p_hud_ctrl)
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['coaming'],
          front=(2, -1, ctrl, -X, Y))
    lean = math.radians(26)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_w, g_h, g_r = 0.226, 0.190, 0.038
    g_bot = np.array([0, hy1, 0.800])
    glass += [p + EYE for p in hud_combiner_round(m, sw['dgrey'], g_bot, g_up, g_n, g_w, g_h, g_r, 0.011, 0.014)]
    # the picture: the rectangle inside the glass, its corners clear of the rounded corners
    hud = dict(centre=g_bot + g_up * g_h / 2 + EYE, normal=-g_n, up=g_up, width=0.182, height=0.152)

    # -- side liners, consoles, floor, rear bulkhead, kick panel
    fy = FLOOR_Y
    z_back, z_front = BACK_Z, 0.63
    bulk = atlas.paint(180, 100, p_bulkhead)
    zl = [z_back, -0.30, 0.0, 0.25, 0.45, z_front]
    for sgn in (1, -1):
        # liner above the console, up to the sill (a cap strip bridges to the skin's inner edge, a hair under its top)
        for za, zb in zip(zl[:-1], zl[1:]):
            ya, yb = sill_rel(za) - 0.020, sill_rel(zb) - 0.020
            qs = [[sgn * WALL, CONSOLE_Y, za], [sgn * WALL, CONSOLE_Y, zb], [sgn * WALL, yb, zb], [sgn * WALL, ya, za]]
            m.flat(qs, sw['panel_d'], normal=[-sgn, 0, 0])
            cap = [[sgn * WALL, ya, za], [sgn * WALL, yb, zb], [sgn * (WALL + 0.022), yb, zb], [sgn * (WALL + 0.022), ya, za]]
            m.flat(cap, sw['panel_d'], normal=[0, 1, 0])
        # console: top, inner side, front, back
        wing = LEFT if sgn > 0 else RIGHT

        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(max(a, 0.0), BOT_W)[2] if abs(x) > HINGE else CENTRE.at(0, BOT_W)[2]
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
    # floor liner, rear bulkhead (up to the rear shelf) and the kick panel under the panel
    zk = CENTRE.at(0, BOT_C)[2]
    m.flat([[-CON_IN, fy, z_back], [CON_IN, fy, z_back], [CON_IN, fy, zk], [-CON_IN, fy, zk]], sw['floor'], normal=[0, 1, 0])
    shelf_y = 2.57 - EYE_ABS[1] - 0.010
    m.quad_tex([[WALL, fy, z_back], [-WALL, fy, z_back], [-WALL, shelf_y, z_back], [WALL, shelf_y, z_back]], bulk,
               normal=[0, 0, 1])
    m.flat([[-CON_IN, fy, zk], [CON_IN, fy, zk], [CON_IN, BOT_C, CENTRE.at(0, BOT_C)[2]], [-CON_IN, BOT_C, CENTRE.at(0, BOT_C)[2]]],
           sw['panel_d'], normal=[0, 0, -1])
    # rudder pedals
    for s in (1, -1):
        m.box([s * 0.13, -0.78, 0.50], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, -0.86, 0.54], (X, Y, Z), (0.014, 0.035, 0.07), sw['dgrey'])

    # -- throttles (left console): the quadrant with two levers and the grips with their thumb switches
    # The two levers are their own bones (throttle_lever_1 / _2) hinged under the console top, modelled at idle (throttle_lib).
    tz = 0.28
    THR.clear()
    THR.extend(throttle_lib.jet_levers(m, Mesh, sw, 'f22', x=0.378, top_y=CONSOLE_Y, z_idle=tz - 0.07, n=2, pitch=0.060))
    # switch rows on both console tops
    for s in (1, -1):
        for zr in (-0.30, -0.22, -0.14, -0.06, 0.50, 0.57):
            if s > 0 and 0.12 < zr < 0.46:
                continue
            for xc in (0.290, 0.320, 0.350, 0.380, 0.410, 0.440):
                m.box([s * xc, CONSOLE_Y + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, CONSOLE_Y + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])),
                      (0.0025, 0.012, 0.0025), sw['black'])

    # -- the fixed armrest and the SIDE STICK on the right console (the grip goes into the stick bone)
    SX, SZ = -0.340, 0.24
    m.box([SX, CONSOLE_Y + 0.012, SZ - 0.15], (X, Y, Z), (0.062, 0.012, 0.095), sw['cushion'])        # armrest pad
    m.box([SX, CONSOLE_Y + 0.006, SZ], (X, Y, Z), (0.040, 0.006, 0.040), sw['dgrey'])                 # base plate
    m.box([SX, CONSOLE_Y + 0.020, SZ], (X, Y, Z), (0.024, 0.014, 0.024), sw['black'])                 # rubber boot
    stick = Mesh()
    col_ax = (X, unit([0, 1, 0.05]), unit([0, -0.05, 1]))
    stick.box([SX, CONSOLE_Y + 0.070, SZ + 0.003], col_ax, (0.010, 0.056, 0.010), sw['metal'])
    g_ax = (X, unit([0, 1, 0.18]), unit([0, -0.18, 1]))
    gc = np.array([SX, CONSOLE_Y + 0.150, SZ + 0.016])
    stick.box(gc, g_ax, (0.020, 0.052, 0.027), sw['black'])                                           # the grip
    stick.box(gc + g_ax[1] * 0.052, g_ax, (0.015, 0.006, 0.020), sw['dgrey'])                         # top cap / hat
    stick.box(gc + g_ax[1] * 0.048 + g_ax[2] * 0.008, g_ax, (0.005, 0.004, 0.005), sw['red'])
    stick.box(gc + g_ax[2] * 0.028 + g_ax[1] * 0.010, g_ax, (0.007, 0.011, 0.005), sw['metal'])       # trigger
    stick.box(gc - g_ax[2] * 0.028 + g_ax[1] * 0.022, g_ax, (0.012, 0.018, 0.003), sw['dgrey'])       # thumb pad
    foot = np.array([SX, CONSOLE_Y + 0.008, SZ])

    # -- ACES II ejection seat, reclined 20 degrees, its back through the headrest behind the head
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    rec = math.radians(20)
    BU = np.array([0, math.cos(rec), -math.sin(rec)])            # up the seat back
    BN = np.array([0, math.sin(rec), math.cos(rec)])             # out of the back, toward the pilot's front
    pan_y = -0.78
    Q0 = np.array([0, pan_y, (-0.20) + (-0.05 - pan_y) * math.tan(rec)])    # back line at the pan: through the headrest face
    ax = (X, BU, BN)
    m.box([0, pan_y - 0.085, Q0[2] + 0.10], (X, Y, Z), (0.185, 0.050, 0.200), sw['frame'])           # survival kit / base
    m.box([0, pan_y - 0.020, Q0[2] + 0.17], (X, Y, Z), (0.200, 0.030, 0.175), sw['cushion'],
          front=(1, 1, cushion, -X, Z))                                                              # seat pan cushion
    for s in (1, -1):
        m.box([s * 0.215, pan_y + 0.03, Q0[2] + 0.17], (X, Y, Z), (0.012, 0.065, 0.185), sw['frame'])  # bucket sides
        m.box(Q0 + BU * 0.35 + X * s * 0.175 - BN * 0.055, ax, (0.018, 0.54, 0.024), sw['frame'])      # guide rails
    m.box(Q0 + BU * 0.38 - BN * 0.02, ax, (0.18, 0.38, 0.030), sw['cushion'],
          front=(2, 1, backt, X, BU))                                                                # back cushion
    m.box(Q0 + BU * 0.40 - BN * 0.085, ax, (0.205, 0.40, 0.030), sw['frame'])                        # back shell
    m.box(Q0 + BU * 0.86 - BN * 0.045, ax, (0.150, 0.100, 0.095), sw['frame'],
          front=(2, 1, headbox, X, BU))                                                              # headbox (parachute container)
    m.box(Q0 + BU * 0.74 + BN * 0.012, ax, (0.090, 0.060, 0.020), sw['cushion'])                     # headrest pad
    for s in (1, -1):                                        # the ejection handles on the pan's front corners
        m.box([s * 0.245, pan_y + 0.03, Q0[2] + 0.31], (X, Y, Z), (0.020, 0.020, 0.050), sw['yellow'],
              front=(1, 1, stripes, -X, Z))
        m.box([s * 0.245, pan_y, Q0[2] + 0.36], (X, Y, Z), (0.010, 0.010, 0.012), sw['black'])
    m.box(Q0 + BU * 0.52 - BN * 0.115, ax, (0.075, 0.090, 0.045), sw['dgrey'])                       # the seat's oxygen / kit pack
    return m, gauges, displays, glass, stick, hud, foot


def old_interior(geo):
    """{bone: set(poly index)} of the airframe's own interior: faces of the hull pieces whose centre lies inside the tub
    (inside the glass's foot line, above the floor) from the rear bulkhead to the windscreen base: the old seat,
    console blocks and the dash wedge under the windscreen, plus the old headrest standing over the rear shelf. The
    walls, floor, sill shoulders, rear shelf, spine and the nose ahead of the windscreen stay."""
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
            if not (2.0 < c[1] < 3.3):
                continue
            xw = min(0.50, CA.foot_x(c[2]) - 0.015)
            V = P[[v[0] for v in poly]]
            nv = np.cross(V[1] - V[0], V[2] - V[0]) if len(V) >= 3 else np.zeros(3)
            ln = np.linalg.norm(nv)
            inward = ln > 1e-12 and abs(nv[0]) > 0.85 * ln and c[0] * nv[0] < 0      # an old tub wall, facing the centre line
            if 4.93 <= c[2] <= 6.90 and abs(c[0]) < xw:
                sel.add(i)
            elif 5.94 <= c[2] <= 6.90 and inward and abs(c[0]) < 0.56 and 1.9 < c[1] < 2.75:
                sel.add(i)
            elif 4.45 <= c[2] < 4.93 and c[1] > 2.60 and abs(c[0]) < 0.45:      # the old headrest above the rear shelf
                sel.add(i)
        if sel:
            out[b['name']] = sel
    return out


def small_leftovers(geo, removed, diag=0.35):
    """The airframe also leaves small loose pieces inside and beside the cabin: welded components of the hull pieces
    under 35 cm across that lie in the cockpit zone (under the glass, between the rear bulkhead and the windscreen base,
    inside the sill band) and touch nothing else (stub ribs, an isolated pair of faces). They go. Returns {bone: set(poly
    index)}; they are added to `removed` as well."""
    gone = {}
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] not in SOURCE_BONES:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        key = {}
        for i, q in enumerate(P):
            key.setdefault(tuple(np.round(q, 3)), []).append(i)
        parent = list(range(len(P)))

        def find(a):
            while parent[a] != a:
                parent[a] = parent[parent[a]]; a = parent[a]
            return a
        for ids in key.values():
            for j in ids[1:]:
                parent[find(j)] = find(ids[0])
        polys = pm['polys']
        comp = {}
        for i, poly in enumerate(polys):
            if not poly or i in removed.get(b['name'], ()):
                continue
            for v in poly:
                parent_ = find(v[0])
                comp.setdefault(find(poly[0][0]), set()).add(i)
                break
        # merge the components that share a vertex (find() already unified by position)
        for r, idxs in comp.items():
            V = np.array([P[v[0]] for i in idxs for v in polys[i]])
            lo, hi = V.min(0), V.max(0)
            c = (lo + hi) / 2
            if np.linalg.norm(hi - lo) >= diag:
                continue
            if 4.4 < c[2] < 6.9 and np.abs(V[:, 0]).max() < 0.52 and abs(c[0]) < 0.505 and 2.30 < c[1] < 3.05:
                gone.setdefault(b['name'], set()).update(idxs)
    for bn, s in gone.items():
        removed.setdefault(bn, set()).update(s)
    return gone


def drop_twins(geo, removed):
    """The airframe builds several faces of its rear shelf and tub twice or on top of each other (faces in one plane, one
    way round, a smaller one lying on a bigger one): they z-fight where they show, under the glass behind the seat. A face
    that lies wholly inside the union of bigger (or earlier, if equal) faces in its plane goes. Returns {bone: set(poly
    index)} of those, added to `removed` as well."""
    from matplotlib.path import Path
    items = []                                  # (bone, index, vertices abs, unit normal, area)
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] not in SOURCE_BONES:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for i, poly in enumerate(pm['polys']):
            if not poly or i in removed.get(b['name'], ()):
                continue
            V = P[[v[0] for v in poly]]
            c = V.mean(0)
            if len(V) < 3 or not (3.5 < c[2] < 7.0 and abs(c[0]) < 0.65 and 2.3 < c[1] < 3.3):
                continue
            nv = np.cross(V[1] - V[0], V[2] - V[0])
            if len(V) == 4:
                nv = nv + np.cross(V[2] - V[0], V[3] - V[0])
            ln = np.linalg.norm(nv)
            if ln < 1e-10:
                continue
            items.append((b['name'], i, V, nv / ln, ln / 2))
    gone = {}
    lo = np.array([it[2].min(0) for it in items]); hi = np.array([it[2].max(0) for it in items])
    for c, (bj, ij, Vj, nj, aj) in enumerate(items):
        ax = np.argsort(np.abs(nj))[:2]
        cand = []
        for a in np.where(np.all(lo <= hi[c] + 1e-3, axis=1) & np.all(hi >= lo[c] - 1e-3, axis=1))[0]:
            if a == c:
                continue
            bi, ii, Vi, ni, ai = items[a]
            if ni @ nj < np.cos(np.radians(2.5)) or abs((Vj.mean(0) - Vi.mean(0)) @ ni) > 0.002:
                continue
            if ai > aj * 1.001 or (abs(ai - aj) <= aj * 0.001 and (bi, ii) < (bj, ij)):
                cand.append(Path(Vi[:, ax]))
        if not cand:
            continue
        pj = Path(Vj[:, ax])
        mn, mx = Vj[:, ax].min(0), Vj[:, ax].max(0)
        gx, gy = np.meshgrid(np.linspace(mn[0], mx[0], 9), np.linspace(mn[1], mx[1], 9))
        pts = np.c_[gx.ravel(), gy.ravel()]
        pts = np.r_[pts[pj.contains_points(pts, radius=-1e-3)], Vj[:, ax].mean(0)[None]]
        if len(pts) < 2:
            continue
        inside = np.zeros(len(pts), bool)
        for p in cand:
            inside |= p.contains_points(pts, radius=1e-3) | p.contains_points(pts, radius=-1e-3)
        if inside.all():
            gone.setdefault(bj, set()).add(ij)
    for bn, s in gone.items():
        removed.setdefault(bn, set()).update(s)
    return gone


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
            if V[:, 2].max() < 6.3 or V[:, 2].min() > 7.3 or np.abs(V[:, 0]).min() > 0.8:
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
        y = y[(y < 2.95) & (y > 2.40)]      # (the nose skin's top: not the floors and ribs inside the old shell)
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
    twins = drop_twins(geo, rm)
    loose = small_leftovers(geo, rm)
    print('f_22a: loose airframe pieces dropped:', {k: len(v) for k, v in loose.items()})
    nose_y = make_nose_y(geo, rm)
    removed = 0
    for b in g['bones']:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            removed += len(rm[b['name']])
    atlas = Atlas()
    mesh, gauges, displays, glass, stick, hud, foot = build(atlas, CL.LAMP_SETS, nose_y)
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
    print(f'f_22a: {removed} old interior faces removed (of them {sum(len(v) for v in twins.values())} doubled faces); new cockpit {len(pm["polys"])} faces, {len(gauges)} game gauges, '
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
