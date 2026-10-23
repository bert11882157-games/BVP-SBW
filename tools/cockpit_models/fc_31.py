#!/usr/bin/env python3
"""FC-31 Gyrfalcon (prototype 31001) cockpit, built by hand for the FC-31.mtb airframe: one standalone script.

The airframe (commit 0f9c3bb1) brought its own interior: a K-36 seat group (kept, it is the real seat of the type), bare tub
walls, an old dash with a chunky coaming and a gunsight box, a windscreen arch with side bows and a spine of centre bars. This
script cuts exactly the frame, coaming, sight and spine bars out of the hull piece (the CUT_POLYS table: the faces of those
elements of the source model; the tub walls, floors, sill shoulders, rear bulkhead, spine fairing and nose stay, so the exterior
stays as it was) and puts a modelled cockpit in their place:

  * the main instrument panel in three facets (centre 10 degrees back, the wings turned 28 degrees toward the pilot), after the
    published FC-31 cockpit images (a single display panel, a wide-angle HUD, an up-front control panel under it): one wide
    panoramic touchscreen made of two live display pages (flight page left, tactical page right) in ONE wide bezel, an
    integrated up-front control panel (UFCP) with a warning panel either side of it under the HUD, the standby flight group
    (airspeed, attitude, altitude) under the screen, the landing gear panel on the left wing and the emergency / jettison
    panel on the right wing. Placards are Chinese (the aircraft is Chinese; the lamps the game draws stay in English);
  * the black coaming running from the panel lip forward to the windscreen base, one smooth deck that meets the nose;
  * the wide-angle HUD: ONE combiner glass, wider at the top than at the foot, top corners cut, in a thin frame, on a low housing
    with its brightness strip; nothing stands beside the glass; the glass (added to CanopyGlass) is centred on the neutral eye
    line and HeadUpDisplay is the picture rectangle inside it;
  * side consoles: the throttle quadrant on the left, the fixed armrest and the SIDE STICK on the right (its grip goes into the
    stick bone, whose pivots, in the geometry and in AircraftRig, move to the stick's foot), liners, kick panel, rudder pedals.
    The K-36 seat is the model's own.

Paint lives in a free area of textures/entity/fc_31.png (no UV of the model or the wreck lands there; the burnt copy goes to
entity_dead). The instruments the game draws are written to CockpitGauges and FlightDisplays (pilot only, Seat 0), the warning
panels are annunciator panels, the HUD picture goes to HeadUpDisplay (the client keys the airframe pass removed).

Order: restore the airframe's files (run_fc31_cockpit.ps1 does), run canopy_fc_31.py, then this script (it appends the HUD
combiner glass to the canopy's CanopyGlass). Running it again on the restored files rebuilds the same result.

Frames: everything below is eye-relative blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is
written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/fc_31.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import canopy_fc_31 as CA        # noqa: E402  (the glass's foot line: where the sill and the nose deck are)
import cockpit_lib as CL         # noqa: E402  (the annunciator lamp sets)
import free_atlas as FA          # noqa: E402  (the texture area is checked free)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/fc_31.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/fc_31.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/fc_31.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/fc_31.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/fc_31.png')

BONE = 'wreck_fuselage_0__cockpit'
THR = []                                 # the throttle levers (throttle_lib), one bone each
SOURCE_BONES = ('wreck_fuselage_1__hull',)
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
ATLAS = [(0, 700, 2048, 2200)]        # free texture area (x, y, w, h): no UV of the model, its fallback or the wreck lands here
TW, TH = 4096, 5120

# The old frame, coaming, sight and spine faces of wreck_fuselage_1__hull (indices into the restored airframe's poly list; the
# faces of source elements 25-27, 161-163, 283-285 (windscreen arch and side bows), 302-303 (coaming), 321-326 (gunsight box),
# 333-337 (spine and centre bars) and the old centre stick and side instrument blocks standing where the new panel and consoles go\n# (286-294, 311, 313, 314-320) of part 0, found by matching each box's corners; shared faces with their neighbours included).
# The K-36 seat group, the tub walls, the sill shoulders (rails 29 / 165, 41-43, 177-179), the rear bulkhead and the nose stay.
KEEP_POLYS = (93, 441, 873, 885)       # shared faces that are exterior skin: the wall-top caps at the arch base, the side shell at the pedals
CUT_POLYS = {'wreck_fuselage_1__hull': (1331, [i for i in [
    *range(90, 108), *range(438, 456), *range(702, 774), 794, 806, *range(816, 829), *range(869, 875), *range(881, 956),
    *range(992, 1002)] if i not in KEEP_POLYS])}

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


# Chinese placards need a CJK face; without one the painters fall back to the pinyin-free English captions in CAPTION_EN
CJK_FILES = [r'C:\Windows\Fonts\msyhbd.ttc', r'C:\Windows\Fonts\msyh.ttc', r'C:\Windows\Fonts\simhei.ttf', r'C:\Windows\Fonts\simsun.ttc',
             '/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc', '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc']

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


X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# Absolute heights of the airframe (the model's own, in blocks): tub floor 1.50, the sill 2.49 along the pilot (the inner wall
# x 0.43..0.47, z 3.96..4.90), the nose deck beside the windscreen 2.25; the eye at y 2.639, z 4.6495; the K-36's pan front at
# z 5.12 (0.47 ahead of the eye), its back at z 4.22; the canopy crown 0.29 over the eye.

EYE_ABS = np.array([0.0, 2.639, 4.64951])
CONSOLE_Y = -0.640              # console tops (abs 2.00)
CON_IN, CON_OUT = 0.285, 0.424  # the consoles' inner side (beside the seat) and outer edge (at the wall)
BACK_Z = -0.250                 # the consoles' rear end (abs 4.40, behind the headrest face)
WALL = 0.424                    # the liner's half width (the model's own wall starts at 0.429)

LEAN = math.radians(10)         # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])    # pilot's right
P0 = np.array([0.0, -0.60, 0.50])
HINGE = 0.270                   # centre facet half width
WING = math.radians(28)         # wings turned toward the pilot
WALL_END = 0.400                # wings end here, short of the wall liner and of the glass's foot (0.44)
WING_LEN = (WALL_END - HINGE) / math.cos(WING)
TOP_C = -0.170                  # centre facet top: the coaming's lip
TOP_H, TOP_W = -0.176, -0.250   # wing top at the hinge and at the wall (sloping down under the glass)
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

# Instrument layout of the FC-31 panel. Positions: facet, a (see Facet.at), y (height from the eye). Sizes: dial diameter,
# or w x h. 'game': a dial drawn by the game; 'display': a colour LCD of the given width (the game draws the housing 12 %
# of the width round it); 'annunciator': a lamp panel drawn and lit by the game; 'frame': the wide bezel of the panoramic screen.
LAYOUT = [
    ('wl',      CENTRE, -0.168, -0.215, ('annunciator', 0.090, 0.066, 'eyebrow_l'), 'left warning panel: cautions'),
    ('ufcp',    CENTRE,  0.000, -0.215, ('paint_rect', 0.150, 0.066), 'up-front control panel (UFCP)'),
    ('wr',      CENTRE,  0.168, -0.215, ('annunciator', 0.090, 0.066, 'eyebrow_r'), 'right warning panel: advisories'),
    ('pano',    CENTRE,  0.000, -0.385, ('frame', 0.512, 0.272), 'the panoramic touchscreen: one wide bezel'),
    ('pano_l',  CENTRE, -0.125, -0.385, ('display', 'PFD', 0.200), 'panoramic screen, left half: flight page'),
    ('pano_r',  CENTRE,  0.125, -0.385, ('display', 'RADAR', 0.200), 'panoramic screen, right half: tactical page'),
    ('asi',     CENTRE, -0.200, -0.572, ('game', 'AIRSPEED', 0.050), 'standby airspeed'),
    ('adi',     CENTRE, -0.125, -0.575, ('game', 'ATTITUDE', 0.060), 'standby attitude'),
    ('alt',     CENTRE, -0.050, -0.572, ('game', 'ALTIMETER', 0.050), 'standby altimeter'),
    ('emerg',   CENTRE,  0.105, -0.575, ('paint_rect', 0.085, 0.056), 'emergency brake / jettison panel'),
    ('ecs',     CENTRE,  0.210, -0.575, ('paint_rect', 0.075, 0.056), 'fuel / engine panel'),
    ('gear',    LEFT,    0.070, -0.440, ('paint_rect', 0.075, 0.095), 'landing gear handle and lights'),
    ('cmds',    RIGHT,   0.070, -0.440, ('paint_rect', 0.075, 0.095), 'countermeasures panel'),
]
GAUGE_LIFT = 0.004
DISPLAY_LIFT = 0.003
RIM = 0.16


# ---------------------------------------------------------------- painting helpers

def font(px, bold=False, cjk=False):
    if cjk:
        for f in CJK_FILES:
            try:
                return ImageFont.truetype(f, max(4, int(px)))
            except OSError:
                continue
    for f in (FONT_FILES if bold else FONT_FILES[1:2] + FONT_FILES[0:1] + FONT_FILES[2:]):
        try:
            return ImageFont.truetype(f, max(4, int(px)))
        except OSError:
            continue
    return ImageFont.load_default()


def have_cjk():
    for f in CJK_FILES:
        try:
            ImageFont.truetype(f, 10)
            return True
        except OSError:
            continue
    return False


CJK = have_cjk()
# the English caption used when no CJK face is installed (the placards are Chinese by design)
CAPTION_EN = {'通信': 'COMM', '导航': 'NAV', '确认': 'ENTR', '清除': 'CLR', '亮度': 'BRT', '对比': 'CONT', '昼': 'DAY', '自动': 'AUTO',
              '刹车': 'BRAKE', '抛放': 'JETT', '应急': 'EMERG', '燃油': 'FUEL', '发动机': 'ENG', '干扰': 'CMDS', '箔条': 'CHAFF',
              '热焰': 'FLARE', '模式': 'MODE', '起落架': 'GEAR', '武器': 'WPN', '轮': 'WHL', '余油': 'FUEL', '左': 'L', '右': 'R'}

def noisy(d, im, colour, amount=6, seed=1):
    rng = np.random.default_rng(seed)
    a = np.zeros((im.height, im.width, 4), np.float32)
    a[..., :3] = colour; a[..., 3] = 255
    n = rng.normal(0, amount, (im.height // 8 + 1, im.width // 8 + 1))
    n = np.kron(n, np.ones((8, 8)))[:im.height, :im.width]
    a[..., :3] += n[..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)), (0, 0))


def text(d, xy, s, px, fill=WHITE, anchor='mm', bold=False):
    cjk = any(ord(c) > 127 for c in s)
    if cjk and not CJK:
        s, cjk = CAPTION_EN.get(s, s), False
    d.text(xy, s, font=font(px, bold, cjk), fill=fill, anchor=anchor)

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
            elif kind in ('paint_rect', 'display', 'annunciator', 'frame'):
                if kind == 'display':
                    w = h = spec[2] * 1.24 + 0.012
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

def p_ufcp(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .05, H * .06, S * .95, H * .36], fill=(6, 14, 8, 255), outline=(60, 62, 60, 255), width=3)
    text(d, (S * .26, H * .21), '通信', H * .17, fill=DED + (255,))
    text(d, (S * .62, H * .21), '251.000  ILS', H * .15, fill=DED + (255,))
    labs = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '确认', '0', '清除']
    kw = S * .90 / 6
    for k, lab in enumerate(labs):
        r, c = k // 6, k % 6
        x0 = S * .05 + c * kw + 3; y0 = H * (.42 + r * .30)
        d.rectangle([x0, y0, x0 + kw - 6, y0 + H * .24], fill=(70, 74, 80, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x0 + kw / 2 - 3, y0 + H * .12), lab, H * (.12 if len(lab) > 1 else .14), fill=(235, 235, 228))


def p_hud_ctrl(d, im):
    """the HUD's brightness strip (the face toward the pilot; its lower fifth is under the coaming)"""
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['昼', '自动', '亮度', '对比']):
        x = S * (0.14 + k * 0.24)
        if k in (2, 3):
            d.ellipse([x - H * .14, H * .26 - H * .14, x + H * .14, H * .26 + H * .14], fill=(18, 18, 18, 255),
                      outline=(120, 120, 120, 255), width=3)
        else:
            d.rectangle([x - H * .20, H * .10, x + H * .20, H * .42], fill=(150, 150, 150, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (x, H * .60), lab, H * .17)

def p_emerg(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .08, H * .12, S * .46, H * .66], fill=(226, 186, 30, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .27, H * .40), '刹车', H * .24, fill=(20, 20, 20), bold=True)
    d.rectangle([S * .54, H * .12, S * .92, H * .66], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .73, H * .40), '抛放', H * .24, fill=(240, 240, 232), bold=True)
    text(d, (S * .5, H * .85), '应急', H * .20)


def p_ecs(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    for k, lab in enumerate(['燃油', '发动机']):
        x = S * (0.28 + 0.44 * k)
        d.ellipse([x - S * .16, H * .10, x + S * .16, H * .10 + S * .32], fill=(34, 34, 36, 255), outline=(140, 140, 140, 255), width=3)
        d.line([x, H * .10 + S * .16, x + S * .09, H * .10 + S * .06], fill=WHITE + (255,), width=5)
        text(d, (x, H * .82), lab, H * .20)


def p_cmds(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    text(d, (S * .5, H * .10), '干扰', H * .10)
    for k, lab in enumerate(['箔条', '热焰']):
        y = H * (0.30 + 0.27 * k)
        d.rectangle([S * .10, y - H * .07, S * .90, y + H * .07], fill=(8, 22, 10, 255), outline=(70, 72, 70, 255), width=2)
        text(d, (S * .30, y), lab, H * .09, fill=DED + (255,))
        text(d, (S * .76, y), '60', H * .09, fill=DED + (255,))
    d.rectangle([S * .22, H * .80, S * .78, H * .93], fill=(70, 74, 80, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .5, H * .865), '模式', H * .09, fill=(235, 235, 228))


def p_gear(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 17, 18, 255))
    d.rectangle([S * .08, H * .08, S * .40, H * .92], fill=(226, 186, 30, 255), outline=(10, 10, 10, 255), width=3)
    d.ellipse([S * .13, H * .50, S * .35, H * .78], fill=(30, 30, 30, 255))
    text(d, (S * .24, H * .28), '轮', H * .20, fill=(20, 20, 20), bold=True)
    for k, (x, y) in enumerate([(0.70, 0.25), (0.58, 0.50), (0.82, 0.50)]):
        d.ellipse([S * x - 10, H * y - 10, S * x + 10, H * y + 10], fill=(40, 130, 56, 255), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .70, H * .80), '起落架', H * .11)

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


# ---------------------------------------------------------------- the deck (the coaming from the panel lip to the nose)

# stations: (absolute z, centre height); the half width follows the canopy's foot line, the edges tuck under its frame
DECK = [(5.230, 2.480), (5.300, 2.470), (5.400, 2.440), (5.500, 2.410), (5.600, 2.380), (5.720, 2.350), (5.850, 2.320),
        (5.930, 2.300), (5.960, 2.290)]
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
    painters = {'ufcp': p_ufcp, 'emerg': p_emerg, 'ecs': p_ecs, 'gear': p_gear, 'cmds': p_cmds}
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
            # a touchscreen page: no option keys; the wide bezel round both pages is the 'frame' entry
            displays.append({'Seat': 0, 'Center': r5(c + EYE + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': spec[2], 'Depth': 0.035, 'Kind': spec[1], 'Style': 'LCD'})
        elif kind == 'frame':
            m.frame_rect(c, f.N, f.U, spec[1], spec[2], 0.012, 0.009, sw['bezel'])
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

    # -- the HUD: a low housing on the coaming with its brightness strip, and ONE combiner glass on it, centred on the neutral
    # eye line and leaning back toward the pilot. The wide-angle HUD glass is wider at the top than at the foot, top corners cut,
    # in a thin frame; nothing stands beside it above the housing. It stands under the windscreen with 4 cm to spare.
    hz0, hz1, hy0, hy1, hhw = 0.640, 0.800, -0.200, -0.105, 0.135
    ctrl = atlas.paint(270, 95, p_hud_ctrl)
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    lean = math.radians(22)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h = 0.170
    g_bot = np.array([0, hy1, 0.700])
    glass += [p + EYE for p in hud_combiner(m, sw['dgrey'], g_bot, g_up, g_n, g_h, 0.105, 0.150, 0.026, 0.010, 0.009, 0.013)]
    hud = dict(centre=g_bot + g_up * g_h / 2 + EYE, normal=-g_n, up=g_up, width=0.200, height=0.140)

    # -- side liners, consoles, the soffit under the panel, kick wall, pedals
    fy = -1.100
    z_back = BACK_Z
    zl = [z_back, -0.05, 0.12, 0.24]
    for sgn in (1, -1):
        # liner above the console, up to the sill (a cap strip bridges to the skin's inner edge, a hair under its top)
        for za, zb in zip(zl[:-1], zl[1:]):
            ya, yb = sill_rel(za) - 0.020, sill_rel(zb) - 0.020
            qs = [[sgn * WALL, CONSOLE_Y, za], [sgn * WALL, CONSOLE_Y, zb], [sgn * WALL, yb, zb], [sgn * WALL, ya, za]]
            m.flat(qs, sw['panel_d'], normal=[-sgn, 0, 0])
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
    # the soffit under the panel and the kick wall ahead of the pedals (the footwell is closed: no view into the nose)
    zk = CENTRE.at(0, BOT_C)[2]
    zw = 0.800
    m.flat([[-CON_IN, BOT_C - 0.002, zk], [CON_IN, BOT_C - 0.002, zk], [CON_IN, BOT_C - 0.002, zw], [-CON_IN, BOT_C - 0.002, zw]],
           sw['panel_d'], normal=[0, -1, 0])
    m.flat([[-CON_IN, BOT_C - 0.002, zw], [CON_IN, BOT_C - 0.002, zw], [CON_IN, fy, zw], [-CON_IN, fy, zw]],
           sw['panel_d'], normal=[0, 0, -1])
    # rudder pedals
    for s in (1, -1):
        m.box([s * 0.13, -0.88, 0.66], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, -0.97, 0.70], (X, Y, Z), (0.014, 0.035, 0.07), sw['dgrey'])

    # -- throttles (left console): the quadrant with two levers and the grips with their thumb switches
    # Each lever is its own bone (throttle_lever_1 / _2) hinged under the console top, modelled at idle (throttle_lib).
    tz = 0.00
    THR.clear()
    THR.extend(throttle_lib.jet_levers(m, Mesh, sw, 'f22', x=0.355, top_y=CONSOLE_Y, z_idle=tz - 0.07, n=2, pitch=0.052))
    # switch rows on both console tops
    for s, rows in ((1, (0.30, 0.37)), (-1, (-0.20, -0.13, 0.34, 0.40))):
        for zr in rows:
            for xc in (0.315, 0.345, 0.375, 0.405):
                m.box([s * xc, CONSOLE_Y + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, CONSOLE_Y + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])),
                      (0.0025, 0.012, 0.0025), sw['black'])

    # -- the fixed armrest and the SIDE STICK on the right console (the grip goes into the stick bone)
    SX, SZ = -0.350, 0.20
    m.box([SX, CONSOLE_Y + 0.012, SZ - 0.15], (X, Y, Z), (0.055, 0.012, 0.095), sw['cushion'])        # armrest pad
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
    return m, gauges, displays, glass, stick, hud, foot

def old_interior(geo):
    """{bone: set(poly index)} of the airframe's old frame, coaming, sight and spine faces (CUT_POLYS). The restored airframe is
    required: the table indexes its poly lists."""
    out = {}
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] in CUT_POLYS:
            n, idx = CUT_POLYS[b['name']]
            if len(b['poly_mesh']['polys']) != n:
                raise SystemExit(f'{b["name"]}: {len(b["poly_mesh"]["polys"])} polys, expected {n}: restore the airframe first '
                                 '(run_fc31_cockpit.ps1)')
            out[b['name']] = set(idx)
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
        y = y[(y < 2.45) & (y > 2.17)]      # (the nose skin's top: not the floors and ribs inside the old shell)
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
    mesh, gauges, displays, glass, stick, hud, foot = build(atlas, CL.LAMP_SETS, nose_y)
    pm = mesh.poly_mesh()
    bones = [b for b in g['bones'] if b['name'] != BONE]
    hull = next(b for b in bones if b['name'] == 'hull')
    at = next(i for i, b in enumerate(bones) if b['name'] == SOURCE_BONES[0]) + 1
    bones.insert(at, {'name': BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    g['bones'] = bones
    # the stick: its three bones' pivots to the side stick's foot (model pixels)
    foot_px = [round(float((foot + EYE)[0]) * 16, 5), round(float((foot + EYE)[1]) * 16, 5),
               round(-float((foot + EYE)[2]) * 16, 5)]
    for b in g['bones']:
        if b['name'] in ('control_stick_roll', 'control_stick_pitch', STICK_BONE):
            b['pivot'] = list(foot_px)
        if b['name'] == STICK_BONE:
            b['poly_mesh'] = stick.poly_mesh()
    thr = throttle_lib.add_bones(g['bones'], THR, BONE, EYE)
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
    print(f'fc_31: {removed} old frame / coaming / sight / spine faces removed; new cockpit {len(pm["polys"])} faces, {len(gauges)} game gauges, '
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
