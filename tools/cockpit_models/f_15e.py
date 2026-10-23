#!/usr/bin/env python3
"""F-15E Strike Eagle front (pilot) and rear (WSO) cockpits, built by hand for the F-15E.mtb airframe (as f_15c.py,
f_16c.py and fa_18e.py): one standalone script.

The airframe brought its own interior in the old model's layout: two ACES II seats, boxes for consoles, a dash with
painted instruments. This script cuts the loose interior of both stations out of the two hull pieces (the small parts
standing in the tubs and the console boxes; the walls, floor, both bulkheads, the block between the seats, the sill bands, the
spine, the nose and the dash blocks with their decks are kept, so the exterior stays as it was) and puts a modelled cockpit
in each place, laid out after the Strike Eagle's panels (the -1 manual's front and rear cockpit figures and photographs of
the E model).

FRONT (pilot):
  * the main panel in three flat facets (the centre 8 degrees back, the wings turned 22 degrees toward the pilot, the tops
    following the dash block's deck): the UFC top centre under the wide-angle HUD, the left MPD (radar) and the right MPD
    (stores) beside it, the colour MPCD under the UFC, the standby instruments (airspeed, attitude, altitude, climb, AoA,
    heading) on the lower left, the engine monitor display with its four engine dials and the fuel quantity lower right,
    the caution/warning lights and the ECS panel on the right wing, the master arm / AHCP strip and the landing gear handle
    panel on the left wing;
  * the HUD: one unit on the deck with its control strip low on the pilot's face, two posts, and the wide combiner glass
    (added to CanopyGlass) centred on the neutral eye line;
  * the consoles (the two throttles and an AHCP-like panel on the left, the switch panels on the right), liners, floor, kick
    panel, rudder pedals, the centre stick (its own bone, so it moves with the controls), an ACES II seat reclined 15 degrees,
    the bulkhead behind it with the breaker panel and a padded cover over the block between the seats.
REAR (WSO):
  * the rear panel in three facets: the two MPCDs in the centre, the two MPDs outboard (all four live), the caution light
    row on top, the UFC under the MPCDs between the TEWS and radar control panels, two standby instruments;
  * the two hand controllers on the side consoles (the WSO's hallmark), a left throttle quadrant, a centre stick, an ACES II
    seat, the rear bulkhead with its breaker panel.

Everything the game draws live goes through the normal entries only: CockpitGauges (the standby instruments, the engine
dials, the fuel quantity, the caution panel), FlightDisplays (front and rear screens) and HeadUpDisplay (front seat). All
other faces are static paint (UFCs, the engine monitor's face, the bezels, panels, the rear caution row).

The seats and eyes move into the vehicle data (seats and eye attachments only). The front seat's eye is 4.26 over the
old floor-level layout: the pilot eye (0, 3.39, 4.26), the WSO eye (0, 3.36, 2.59), the seats 1.62 below.
Paint lives in a free area of textures/entity/f_15e.png (rows above the UVs of the model, its native fallback and the wreck;
the burnt copy goes to entity_dead).

Order: git checkout the airframe's files, run canopy_f_15e.py, then this script (it appends the HUD combiner glass to the
canopy's CanopyGlass). Running it again rebuilds the same result.

Frames: everything below is eye-relative blocks of its station (x left, y up, z forward; 1 block = 1 m on this model); the
mesh is written in model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/f_15e.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/f_15e.geo.json')
FALLBACK = os.path.join(GEN, 'assets/berts_vehicle_pack/geo/native_fallback/f_15e.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/f_15e.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/f_15e.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/f_15e.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/f_15e.png')

BONE_F = 'wreck_fuselage_0__cockpit'          # the front station breaks away with the forward fuselage
BONE_R = 'wreck_fuselage_1__cockpit'          # the rear station stays with the middle one
SOURCE_BONES = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
STICK_BONES = ('control_stick_roll', 'control_stick_pitch', 'wreck_fuselage_0__control_stick_pitch')
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
ATLAS = [(16, 16, 4064, 4100)]        # free texture area (x, y, w, h): above every UV of the model, its fallback and the wreck
TW, TH = 4096, 6144

EYE_F = (0.0, 3.39, 4.26)             # the pilot's eye (vehicle data)
EYE_R = (0.0, 3.36, 2.59)             # the WSO's eye
SEAT_DROP = 1.62                      # the seat position hangs this far under its eye


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

PANEL = (112, 116, 118)           # the Strike Eagle's medium grey panel face
PANEL_D = (44, 46, 49)            # edges, wing backs, console sides
BLACK = (22, 23, 25)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
LGREY = (150, 154, 156)
FRAME = (50, 54, 52)              # ACES II structure
CUSHION = (62, 66, 58)
HEADPAD = (96, 100, 92)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (44, 47, 48)
CONSOLE = (58, 60, 62)            # the consoles are darker than the panel
WHITE = (226, 228, 220)
GREEN_TXT = (80, 220, 100)
AMBER_TXT = (255, 168, 40)
AMBER = (214, 140, 30)


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


# ---------------------------------------------------------------- mesh (station-relative in, vehicle-local out)

class Mesh:
    def __init__(self, origin):
        self.o = np.array(origin, float)     # the station's eye: the mesh is built eye-relative
        self.faces = []                      # (points k x 3 vehicle-local, uvs k x 2, normal 3)

    def face(self, pts, uvs, toward=None, normal=None):
        P = np.array(pts, float) + self.o
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
            want = np.array(toward, float) + self.o - P.mean(0)
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
R_C = np.array([-1.0, 0, 0])      # the crew's right

# ---------------------------------------------------------------- the measured airframe (absolute blocks)
# floor top 2.304 (liner 2.312), side walls at x 0.542 (liners at 0.532), the sill (the hull's wall top and the rail's foot)
# falling from 3.125 at z 2.4 to 2.895 at z 4.8, console tops 2.70, the rear station's bulkhead at z 2.273 and its panel wall at
# z 3.267 (the block between the seats up to 3.253, a ledge at 3.208 / 3.163 on its front), the front station's bulkhead at
# z 3.99 and its dash rear wall at 4.984 (the centre pod's front at 4.894, its deck 3.253).

FLOOR_ABS = 2.312
CONSOLE_ABS = 2.70
CON_IN, WALL_X = 0.30, 0.532      # the consoles' inner side; the liners stand just inside the wall (0.542)


def sill_abs(z):
    """the hull's sill height at absolute z (a hair under the real edge)"""
    return 3.125 - 0.0958 * (z - 2.4) - 0.004


WING = math.radians(22)           # the wings turn toward the crew


class Panel:
    """A three-facet instrument panel: the centre facet leaning back [lean] degrees, the wings turned 22 degrees toward the
    crew. Everything is eye-relative metres."""

    def __init__(self, p0, lean, hinge, wall_end, top_c, top_k, top_h, top_w, bot_c, bot_w):
        self.P0 = np.array(p0, float)
        self.LEAN = math.radians(lean)
        self.U_C = np.array([0, math.cos(self.LEAN), math.sin(self.LEAN)])
        self.N_C = np.array([0, math.sin(self.LEAN), -math.cos(self.LEAN)])
        self.HINGE = hinge
        self.WALL_END = wall_end
        self.WING_LEN = (wall_end - hinge) / math.cos(WING)
        self.TOP_C, self.TOP_K, self.TOP_H, self.TOP_W = top_c, top_k, top_h, top_w
        self.BOT_C, self.BOT_W = bot_c, bot_w
        self.CENTRE, self.RIGHT, self.LEFT = Facet(self, 0), Facet(self, 1), Facet(self, -1)

    def front_z(self, x, y):
        """z of the panel's face (whichever facet stands there) at |x|, height y"""
        ax_ = abs(x)
        if ax_ <= self.HINGE:
            return self.CENTRE.at(0, y)[2]
        wing = self.LEFT if x > 0 else self.RIGHT
        return wing.at((ax_ - self.HINGE) / math.cos(WING), y)[2]


class Facet:
    def __init__(self, panel, side):
        self.p = panel
        self.side = side                    # 0 centre, +1 right wing, -1 left wing
        if side == 0:
            self.R, self.N = R_C, panel.N_C
        else:
            self.R = R_C * side * math.cos(WING) + panel.N_C * math.sin(WING)
            self.N = panel.N_C * math.cos(WING) - R_C * side * math.sin(WING)
        self.U = panel.U_C

    def at(self, a, y, lift=0.0):
        """panel point: a = metres right of the centre line (centre) or along the wing from the hinge (wings)"""
        p = self.p
        v = (y - p.P0[1]) / math.cos(p.LEAN)
        if self.side == 0:
            q = p.P0 + R_C * a + p.U_C * v
        else:
            q = p.P0 + R_C * (self.side * p.HINGE) + p.U_C * v + self.R * a
        return q + self.N * lift

    def top(self, a):
        p = self.p
        if self.side == 0:
            return p.TOP_C + (p.TOP_H - p.TOP_C) * max(0.0, abs(a) - p.TOP_K) / (p.HINGE - p.TOP_K)
        return p.TOP_H + (p.TOP_W - p.TOP_H) * a / p.WING_LEN

    def bottom(self):
        return self.p.BOT_C if self.side == 0 else self.p.BOT_W

    def extent(self):
        return (-self.p.HINGE, self.p.HINGE) if self.side == 0 else (0.0, self.p.WING_LEN)

    def knots(self):
        """the breakpoints of the top edge"""
        p = self.p
        if self.side == 0:
            return [-p.HINGE, -p.TOP_K, p.TOP_K, p.HINGE]
        return [0.0, p.WING_LEN]


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


def panel_paint(panel, facet, rect_px, layout, seed):
    """the facet face: grey, seams, screws and the dark recesses behind every instrument"""
    a0, a1 = facet.extent()
    W, H = rect_px
    y_bot = facet.bottom(); y_top = max(facet.top(a) for a in facet.knots())
    SS = Atlas.SS
    lean = panel.LEAN

    def to_px(a, y):
        s = (a - a0) / (a1 - a0)
        if facet.side == -1:
            s = 1 - s
        return (s * W * SS, (1 - (y - y_bot) / (y_top - y_bot)) * H * SS)

    def fn(d, im):
        noisy(d, im, PANEL, 3, seed=seed + facet.side)
        for (a, y) in [(a0 + 0.010, y_bot + 0.010), (a1 - 0.010, y_bot + 0.010), (a0 + 0.010, facet.top(a0 + 0.010) - 0.014),
                       (a1 - 0.010, facet.top(a1 - 0.010) - 0.014)]:
            x, yy = to_px(a, y); r = SS * 1.8
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        # the panel's seams: a thin dark line under the top row of instruments and between the wings and the centre
        for name, f, a, y, spec, _ in layout:
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
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (y_top - y_bot) * H * SS * math.cos(lean)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(18, 22, 22, 255))
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces (all static)

def led_row(d, box, s, fill, px, anchor='lm'):
    """a recessed LED readout: dark window, the text in the LED colour"""
    d.rectangle(box, fill=(10, 8, 6, 255), outline=(66, 66, 62, 255), width=2)
    text(d, (box[0] + (box[2] - box[0]) * 0.06, (box[1] + box[3]) / 2), s, px, fill=fill, anchor=anchor, bold=True)


def p_ufc_front(d, im):
    """the F-15E up-front controller: two scratchpad rows (amber), the COMM 1 / COMM 2 channel windows, the 3 x 4 keypad with
    its mode legends, the two volume knobs at the sides and the lamp row of the IFF/ILS/EMIS keys"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(24, 25, 26, 255))
    led_row(d, [S * .20, H * .04, S * .80, H * .14], 'A/A  AIM-9  02', AMBER_TXT + (255,), H * .062)
    led_row(d, [S * .20, H * .165, S * .80, H * .265], 'COMM1 305.500', AMBER_TXT + (255,), H * .062)
    led_row(d, [S * .20, H * .29, S * .80, H * .39], 'COMM2 251.300', AMBER_TXT + (255,), H * .062)
    for k in range(2):                                              # the volume knobs on the sides
        cx = S * (0.10 if k == 0 else 0.90)
        d.ellipse([cx - S * .065, H * .06, cx + S * .065, H * .06 + S * .13], fill=(34, 34, 36, 255), outline=(170, 170, 166, 255), width=3)
        d.line([cx, H * .06 + S * .065, cx + S * .035 * (1 if k else -1), H * .075], fill=WHITE + (255,), width=4)
        text(d, (cx, H * .27), 'VOL', H * .045)
    keys = [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['CLR', '0', 'ENT']]
    legends = [['A/P', 'IFF', 'TCN'], ['ILS', 'D/L', 'BCN'], ['VIDS', 'B/I', 'MARK'], ['', '', '']]
    for r in range(4):
        for c in range(3):
            x0 = S * (0.20 + c * 0.205); y0 = H * (0.435 + r * 0.115)
            d.rectangle([x0, y0, x0 + S * .18, y0 + H * .095], fill=(70, 72, 72, 255), outline=(10, 10, 10, 255), width=2)
            text(d, (x0 + S * .09, y0 + H * .036), keys[r][c], H * .05, fill=WHITE + (255,), bold=True)
            if legends[r][c]:
                text(d, (x0 + S * .09, y0 + H * .075), legends[r][c], H * .028, fill=(200, 200, 190, 255))
    for k, lab in enumerate(['A/P', 'IFF', 'L/S', 'A/G']):          # the function keys down the sides
        y0 = H * (0.435 + k * 0.115)
        for sx in (0.04, 0.855):
            d.rectangle([S * sx, y0, S * (sx + .11), y0 + H * .095], fill=(54, 56, 56, 255), outline=(10, 10, 10, 255), width=2)
            text(d, (S * (sx + .055), y0 + H * .048), lab, H * .036, fill=(214, 214, 206, 255))
    d.rectangle([S * .10, H * .905, S * .90, H * .975], fill=(20, 20, 20, 255), outline=(66, 66, 62, 255), width=2)
    for k, lab in enumerate(['BRT', 'SYM', 'DAY', 'N/W']):
        text(d, (S * (0.20 + 0.20 * k), H * .94), lab, H * .042)


def p_ufc_rear(d, im):
    """the WSO's UFC: the same unit, its own state (radar and sensor pages in the scratchpad, the data keys lit)"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(24, 25, 26, 255))
    led_row(d, [S * .20, H * .04, S * .80, H * .14], 'SCRATCH 0123456', GREEN_TXT + (255,), H * .056)
    led_row(d, [S * .20, H * .165, S * .80, H * .265], 'T/D  A/G  TGT 02', AMBER_TXT + (255,), H * .056)
    led_row(d, [S * .20, H * .29, S * .80, H * .39], 'TACAN 073X  CHAN', AMBER_TXT + (255,), H * .056)
    for k in range(2):
        cx = S * (0.10 if k == 0 else 0.90)
        d.rectangle([cx - S * .06, H * .07, cx + S * .06, H * .33], fill=(32, 32, 34, 255), outline=(150, 150, 146, 255), width=3)
        text(d, (cx, H * .20), ['BRT', 'CONT'][k], H * .05)
    legends = [['7', '8', '9'], ['4', '5', '6'], ['1', '2', '3'], ['0', 'CLR', 'ENT']]
    for r in range(4):
        for c in range(3):
            x0 = S * (0.20 + c * 0.205); y0 = H * (0.435 + r * 0.115)
            d.rectangle([x0, y0, x0 + S * .18, y0 + H * .095], fill=(70, 72, 72, 255), outline=(10, 10, 10, 255), width=2)
            text(d, (x0 + S * .09, y0 + H * .048), legends[r][c], H * .06, fill=WHITE + (255,), bold=True)
    for k, lab in enumerate(['A/G', 'A/A', 'NAV', 'DATA']):
        y0 = H * (0.435 + k * 0.115)
        for sx in (0.04, 0.855):
            d.rectangle([S * sx, y0, S * (sx + .11), y0 + H * .095], fill=(54, 100, 54, 255) if (k == 0 and sx < .5) else (54, 56, 56, 255),
                        outline=(10, 10, 10, 255), width=2)
            text(d, (S * (sx + .055), y0 + H * .048), lab, H * .036, fill=(214, 214, 206, 255))
    d.rectangle([S * .10, H * .905, S * .90, H * .975], fill=(20, 20, 20, 255), outline=(66, 66, 62, 255), width=2)
    text(d, (S * .5, H * .94), 'UP FRONT CONTROL', H * .042)


def p_hud_ctrl(d, im):
    """the HUD's control strip: DECLUTTER, SYMBOL, BRT and CONT knobs, the test button, the day/night switch"""
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    items = [('DCLT', 'sq'), ('SYM', 'sq'), ('BRT', 'kn'), ('CONT', 'kn'), ('D/N', 'sw'), ('TEST', 'sq')]
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


def p_ahcp(d, im):
    """the armament control panel (AHCP style) strip on the left wing: master arm, the A/A and A/G mode keys, the
    jettison and the laser/ICP lights"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 19, 20, 255))
    d.rectangle([S * .05, H * .10, S * .30, H * .50], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .175, H * .30), 'ARM', H * .16, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['SAFE', 'COMBAT', 'JETT']):
        x = S * (0.45 + k * 0.23)
        d.rectangle([x - 6, H * .16, x + 6, H * .46], fill=(176, 176, 176, 255))
        d.ellipse([x - 12, H * .08, x + 12, H * .26], fill=(206, 206, 206, 255))
        text(d, (x, H * .62), lab, H * .09)
    for k, (lab, col) in enumerate([('A/A', (40, 130, 56)), ('A/G', (30, 60, 34)), ('NAV', (30, 60, 34))]):
        x0 = S * (0.07 + k * 0.30)
        d.rectangle([x0, H * .72, x0 + S * .26, H * .94], fill=col + (255,), outline=(10, 10, 10, 255), width=2)
        text(d, (x0 + S * .13, H * .83), lab, H * .12, fill=(20, 20, 20, 255) if k == 0 else (200, 200, 190, 255), bold=True)


def p_gear(d, im):
    """the landing gear panel: the wheel handle (white), its three green lights, the red UNSAFE lamp, the emergency brake"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 19, 20, 255))
    d.ellipse([S * .06, H * .08, S * .44, H * .50], fill=(206, 206, 202, 255), outline=(10, 10, 10, 255), width=3)
    d.ellipse([S * .19, H * .20, S * .31, H * .38], fill=(30, 30, 30, 255))
    d.line([S * .25, H * .50, S * .25, H * .64], fill=(206, 206, 202, 255), width=8)
    text(d, (S * .25, H * .76), 'LDG GEAR', H * .09)
    for k, (x, y) in enumerate([(0.68, 0.18), (0.56, 0.42), (0.80, 0.42)]):
        d.ellipse([S * x - 11, H * y - 11, S * x + 11, H * y + 11], fill=(40, 130, 56, 255), outline=(10, 10, 10, 255), width=3)
    d.rectangle([S * .52, H * .62, S * .90, H * .84], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .71, H * .73), 'UNSAFE', H * .09, fill=(235, 235, 228))
    d.rectangle([S * .08, H * .84, S * .44, H * .95], fill=(222, 170, 30, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .26, H * .895), 'EMER BRK', H * .075, fill=(20, 20, 20, 255), bold=True)


def p_brake(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 19, 20, 255))
    for k, lab in enumerate(['ANTI SKID', 'NWS', 'ARR HOOK']):
        x = S * (0.20 + 0.30 * k)
        d.rectangle([x - 5, H * .20, x + 5, H * .56], fill=(176, 176, 176, 255))
        d.ellipse([x - 10, H * .10, x + 10, H * .30], fill=(206, 206, 206, 255))
        text(d, (x, H * .78), lab, H * .13)


def p_ecs(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 19, 20, 255))
    d.ellipse([S * .06, H * .08, S * .46, H * .62], fill=(30, 30, 32, 255), outline=(150, 150, 150, 255), width=3)
    for k in range(8):
        a = math.radians(-120 + k * 34)
        cx, cy = S * .26, H * .35
        d.line([cx + S * .15 * math.sin(a), cy - H * .20 * math.cos(a), cx + S * .18 * math.sin(a), cy - H * .24 * math.cos(a)],
               fill=WHITE + (255,), width=3)
    d.line([S * .26, H * .35, S * .36, H * .20], fill=WHITE + (255,), width=5)
    text(d, (S * .26, H * .76), 'CABIN ALT', H * .10)
    for k, lab in enumerate(['AIR', 'DEFOG', 'TEMP']):
        y = H * (0.18 + 0.30 * k)
        d.rectangle([S * .62 - 5, y - H * .09, S * .62 + 5, y + H * .09], fill=(176, 176, 176, 255))
        d.ellipse([S * .62 - 9, y - H * .14, S * .62 + 9, y - H * .04], fill=(206, 206, 206, 255))
        text(d, (S * .84, y), lab, H * .12)


def p_emd(d, im):
    """the engine monitor display's face behind its four live dials: L and R columns, the dial captions and the strip of
    static readouts (fuel flow, nozzle, oil, hydraulics) under them"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(8, 10, 10, 255))
    d.rectangle([0, 0, S - 1, H - 1], outline=(70, 72, 72, 255), width=4)
    g = (150, 220, 160, 255)
    text(d, (S * .5, H * .035), 'ENGINE MONITOR', H * .036, fill=g)
    text(d, (S * .27, H * .09), 'L', H * .04, fill=g); text(d, (S * .73, H * .09), 'R', H * .04, fill=g)
    text(d, (S * .5, H * .36), 'RPM', H * .04, fill=g); text(d, (S * .5, H * .64), 'FTIT', H * .04, fill=g)
    d.line([S * .06, H * .685, S * .94, H * .685], fill=(70, 72, 72, 255), width=3)
    for k, (lab, l, r) in enumerate([('FF', '4.2', '4.1'), ('NOZ', '45', '46'), ('OIL', '38', '39'), ('HYD', '3000', '3000')]):
        y = H * (0.735 + k * 0.07)
        text(d, (S * .5, y), lab, H * .036, fill=g)
        text(d, (S * .20, y), l, H * .042, fill=GREEN_TXT + (255,), bold=True); text(d, (S * .80, y), r, H * .042, fill=GREEN_TXT + (255,), bold=True)

def p_ew(d, im):
    """the EW / IFF panel under the MPCD: the RWR volume knob, dispenser keys and the ECM mode switch"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 19, 20, 255))
    d.ellipse([S * .06, H * .16, S * .28, H * .84], fill=(34, 34, 36, 255), outline=(150, 150, 146, 255), width=3)
    d.line([S * .17, H * .50, S * .22, H * .26], fill=WHITE + (255,), width=4)
    text(d, (S * .17, H * .93), 'RWR VOL', H * .12)
    for k, (lab, col) in enumerate([('CHAFF', (200, 150, 30)), ('FLARE', (200, 150, 30)), ('JMR', (60, 120, 60)), ('STBY', (50, 50, 52))]):
        x0 = S * (0.34 + k * 0.16)
        d.rectangle([x0, H * .16, x0 + S * .14, H * .52], fill=col + (255,), outline=(10, 10, 10, 255), width=2)
        text(d, (x0 + S * .07, H * .34), lab, H * .14, fill=(20, 20, 20, 255), bold=True)
    for k in range(4):
        x = S * (0.40 + 0.16 * k)
        d.rectangle([x - 4, H * .62, x + 4, H * .86], fill=(176, 176, 176, 255))
        d.ellipse([x - 8, H * .56, x + 8, H * .70], fill=(206, 206, 206, 255))


def p_clock(d, im):
    dial_face(d, im, '', 0, 12, 1, 1, 10.1, start=0, sweep=360, labels=['12'] + [str(k) for k in range(1, 12)] + ['12'])
    S = im.width; c = S / 2
    d.line([c, c, c + S * .26 * math.sin(math.radians(60)), c - S * .26 * math.cos(math.radians(60))], fill=(240, 240, 232, 255), width=int(S * .05))


def p_dial_static(kind):
    def fn(d, im):
        if kind == 'COMPASS':
            dial_face(d, im, 'MAG', 0, 360, 10, 30, 80, labels=['N', '3', '6', 'E', '12', '15', 'S', '21', '24', 'W', '30', '33'],
                      start=0, sweep=360)
        else:
            dial_face(d, im, kind, 0, 10, 1, 2, 4.0, labels=['0', '2', '4', '6', '8', '10'])
    return fn


def p_console(side, rows, seed):
    """a console top painted as strips of switch groups: dark recesses with toggles, rotary knobs and a placard line"""
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, CONSOLE, 3, seed=seed)
        rng = np.random.default_rng(seed + 11)
        y = H * 0.02
        for k, hgt in enumerate(rows):
            y1 = y + hgt * H
            d.rectangle([S * .06, y + 4, S * .94, y1 - 4], fill=(20, 22, 22, 255), outline=(52, 54, 54, 255), width=2)
            n = int(rng.integers(2, 5))
            for j in range(n):
                x = S * (0.15 + 0.7 * (j + 0.5) / n); yy = (y + y1) / 2
                if rng.random() < 0.45:
                    d.ellipse([x - 12, yy - 12, x + 12, yy + 12], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
                    d.line([x, yy, x + 8, yy - 9], fill=WHITE + (255,), width=3)
                else:
                    d.rectangle([x - 5, yy - 16, x + 5, yy + 6], fill=(180, 180, 180, 255))
            text(d, (S * .5, y1 - 10), ['FUEL', 'LTG', 'ECS', 'COMM', 'NAV', 'EW', 'ENG', 'INS', 'MISC', 'OXY'][(k + seed) % 10], H * .014)
            y = y1
    return fn


def p_ahcp_console(d, im):
    """the AHCP-like panel on the left console: a row of toggles over a block of mode keys and a placard"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 22, 255))
    for k in range(5):
        x = S * (0.12 + k * 0.19)
        d.rectangle([x - 5, H * .12, x + 5, H * .36], fill=(176, 176, 176, 255))
        d.ellipse([x - 9, H * .06, x + 9, H * .20], fill=(206, 206, 206, 255))
        text(d, (x, H * .47), ['MSTR', 'LASER', 'TGP', 'GUN', 'FLIR'][k], H * .075)
    for r in range(2):
        for c in range(5):
            x0 = S * (0.07 + c * 0.18); y0 = H * (0.56 + r * 0.22)
            d.rectangle([x0, y0, x0 + S * .15, y0 + H * .17], fill=(60, 64, 64, 255) if (r, c) != (0, 1) else (50, 140, 60, 255),
                        outline=(10, 10, 10, 255), width=2)
            text(d, (x0 + S * .075, y0 + H * .085), [['A/A', 'S-J', 'JETT', 'STEP', 'RSET'], ['PRE', 'RIP', 'SAFE', 'HUD', 'MAN']][r][c],
                 H * .08, fill=(220, 220, 212, 255))


def p_throttle_plate(d, im):
    S, H = im.width, im.height
    noisy(d, im, (30, 32, 33), 3, seed=31)
    d.rectangle([S * .08, H * .08, S * .92, H * .92], outline=(100, 102, 100, 255), width=3)
    for k, lab in enumerate(['OFF', 'IDLE', 'MIL', 'A/B']):
        y = H * (0.88 - k * 0.24)
        d.line([S * .35, y, S * .65, y], fill=(150, 150, 146, 255), width=3)
        text(d, (S * .18, y), lab, H * .06, fill=(200, 200, 190, 255))


def p_handctl(d, im):
    """the top of a WSO hand controller: the thumb pad with its four-way cross, the two buttons"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(24, 24, 26, 255))
    d.ellipse([S * .22, H * .12, S * .78, H * .68], fill=(40, 40, 42, 255), outline=(150, 150, 146, 255), width=3)
    d.line([S * .5, H * .20, S * .5, H * .60], fill=(190, 190, 184, 255), width=3)
    d.line([S * .30, H * .40, S * .70, H * .40], fill=(190, 190, 184, 255), width=3)
    for x in (0.30, 0.70):
        d.ellipse([S * x - 7, H * .80 - 7, S * x + 7, H * .80 + 7], fill=(180, 40, 36, 255) if x < .5 else (60, 130, 60, 255),
                  outline=(10, 10, 10, 255), width=2)


def p_tews(d, im):
    """the WSO's TEWS / ECM control panel: the RWR pattern select keys and the dispenser counters"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 19, 20, 255))
    text(d, (S * .5, H * .07), 'TEWS', H * .08, bold=True)
    for r in range(3):
        for c in range(3):
            x0 = S * (0.08 + c * 0.30); y0 = H * (0.16 + r * 0.17)
            d.rectangle([x0, y0, x0 + S * .26, y0 + H * .13], fill=(70, 72, 72, 255), outline=(10, 10, 10, 255), width=2)
            text(d, (x0 + S * .13, y0 + H * .065), [['PWR', 'SRCH', 'ALT'], ['SEL', 'UNK', 'NAV'], ['CHF', 'FLR', 'MODE']][r][c],
                 H * .065, fill=(220, 220, 212, 255))
    led_row(d, [S * .08, H * .70, S * .92, H * .82], 'CHF 60  FLR 30', AMBER_TXT + (255,), H * .07)
    text(d, (S * .5, H * .93), 'EW CONTROL', H * .06)


def p_radar_ctl(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 19, 20, 255))
    text(d, (S * .5, H * .07), 'RADAR / SENSOR', H * .07, bold=True)
    for k, lab in enumerate(['STBY', 'OPR', 'EMCON']):
        x = S * (0.20 + k * 0.30)
        d.rectangle([x - 5, H * .20, x + 5, H * .46], fill=(176, 176, 176, 255))
        d.ellipse([x - 9, H * .14, x + 9, H * .28], fill=(206, 206, 206, 255))
        text(d, (x, H * .58), lab, H * .075)
    for k in range(2):
        cx = S * (0.30 + 0.40 * k)
        d.ellipse([cx - S * .10, H * .66, cx + S * .10, H * .66 + S * .20], fill=(34, 34, 36, 255), outline=(150, 150, 146, 255), width=3)
        d.line([cx, H * .66 + S * .10, cx + 8, H * .70], fill=WHITE + (255,), width=3)
        text(d, (cx, H * .94), ['RNG', 'GAIN'][k], H * .06)


def p_caution_row(d, im):
    """the rear caution lights row: twenty legend windows, none lit (amber, red and green caps)"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 14, 15, 255))
    legends = ['MSTR|CAUT', 'ENG|FIRE', 'FUEL|LOW', 'OXY|LOW', 'HYD|PRESS', 'GEN', 'BUS|TIE', 'CADC', 'STALL', 'GEAR',
               'FLAP', 'ENG|OUT', 'OVER|HEAT', 'BIT', 'IFF', 'RADAR', 'DATA|LINK', 'TGT|POD', 'CMD|DISP', 'MSL|LNCH']
    cols = [AMBER, RED, AMBER, AMBER, AMBER, AMBER, AMBER, AMBER, RED, (60, 150, 70), AMBER, RED, AMBER, AMBER, AMBER, AMBER,
            AMBER, AMBER, AMBER, RED]
    n = len(legends); per = 10
    for k in range(n):
        r, c = divmod(k, per)
        x0 = S * (0.01 + c * 0.099); y0 = H * (0.06 + r * 0.47)
        base = tuple(int(v * 0.40) for v in cols[k])
        d.rectangle([x0, y0, x0 + S * .092, y0 + H * .42], fill=base + (255,), outline=(8, 8, 8, 255), width=2)
        for j, line in enumerate(legends[k].split('|')):
            text(d, (x0 + S * .046, y0 + H * (0.21 + (j - (len(legends[k].split('|')) - 1) / 2) * 0.17)), line, H * .13,
                 fill=(24, 24, 24, 255), bold=True)


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
    text(d, (S * .5, H * .38), 'ACES II', H * .2, fill=(40, 40, 40), bold=True)
    d.rectangle([S * .30, H * .62, S * .70, H * .78], fill=YELLOW + (255,))
    text(d, (S * .5, H * .70), 'EJECT', H * .10, fill=(20, 20, 20, 255), bold=True)


def p_bulkhead(rows, seed):
    """the bulkhead behind a seat: circuit breaker rows (white caps in dark panels), a placard strip and a map pocket"""
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, PANEL_D, 4, seed=seed)
        rng = np.random.default_rng(seed)
        for r in range(rows):
            y0 = H * (0.08 + r * (0.52 / rows))
            d.rectangle([S * .06, y0, S * .94, y0 + H * (0.46 / rows)], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)
            for c in range(12):
                x = S * (0.10 + c * 0.071)
                d.ellipse([x - 5, y0 + H * .12 / rows, x + 5, y0 + H * .12 / rows + 10], fill=(rng.integers(150, 235),) * 3 + (255,))
        d.rectangle([S * .08, H * .66, S * .92, H * .80], fill=(30, 32, 33, 255), outline=(70, 72, 72, 255), width=3)
        d.rectangle([S * .12, H * .85, S * .50, H * .96], fill=(52, 56, 48, 255), outline=(20, 20, 20, 255), width=2)
        text(d, (S * .31, H * .905), 'MAP / CHECKLIST', H * .05, fill=(190, 190, 180, 255))
    return fn


def p_kick(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=71)
    for k in range(3):
        d.rectangle([S * (.10 + k * .30), H * .30, S * (.34 + k * .30), H * .70], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)


def p_cover(d, im):
    """the padded cover over the block between the seats: grey leather with stitching and a hand grip recess"""
    S, H = im.width, im.height
    noisy(d, im, (74, 78, 78), 3, seed=73)
    for k in range(1, 6):
        d.line([S * k / 6, 0, S * k / 6, H], fill=(52, 56, 56, 255), width=3)
    d.rectangle([S * .38, H * .35, S * .62, H * .65], fill=(24, 26, 26, 255), outline=(100, 102, 100, 255), width=3)


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

# ---------------------------------------------------------------- the two panels (eye-relative)

# FRONT: the centre facet from y -0.77 to the deck (-0.14), the wings falling with the dash's sloping deck to the wall
PANEL_F = Panel(p0=(0.0, -0.40, 0.585), lean=8, hinge=0.30, wall_end=WALL_X, top_c=-0.140, top_k=0.17, top_h=-0.175,
                top_w=-0.44, bot_c=-0.77, bot_w=CONSOLE_ABS - EYE_F[1])
# REAR: under the ledge on the block's front face (centre 3.208, sides 3.163), the MPDs on the wings
PANEL_R = Panel(p0=(0.0, -0.45, 0.625), lean=8, hinge=0.225, wall_end=WALL_X, top_c=-0.162, top_k=0.12, top_h=-0.205,
                top_w=-0.215, bot_c=-0.74, bot_w=CONSOLE_ABS - EYE_R[1])

C_, R_, L_ = 'C', 'R', 'L'
# (name, facet, a, y, spec, label). Positions: a along the facet (see Facet.at), y from the eye. Specs: ('game', kind, dia) a live
# gauge; ('paint_disc', kind, dia); ('paint_rect', w, h); ('display', kind, w, style) a live screen; ('annunciator', w, h)
LAYOUT_F = [
    ('ufc',    C_, 0.000, -0.228, ('paint_rect', 0.150, 0.150), 'up-front controller under the HUD'),
    ('mpd_l',  C_, -0.215, -0.262, ('display', 'RADAR', 0.115, 'CRT'), 'left MPD (radar)'),
    ('mpd_r',  C_, 0.215, -0.262, ('display', 'STORES', 0.115, 'CRT'), 'right MPD (stores)'),
    ('mpcd',   C_, 0.000, -0.455, ('display', 'PFD', 0.140, 'LCD'), 'colour MPCD'),
    ('asi',    C_, -0.258, -0.430, ('game', 'AIRSPEED', 0.064), 'standby airspeed'),
    ('adi',    C_, -0.168, -0.435, ('game', 'ATTITUDE', 0.070), 'standby attitude indicator'),
    ('alt',    C_, -0.258, -0.525, ('game', 'ALTIMETER', 0.064), 'standby altimeter'),
    ('vvi',    C_, -0.168, -0.535, ('game', 'VVI', 0.056), 'standby vertical velocity'),
    ('aoa',    C_, -0.258, -0.612, ('game', 'AOA', 0.050), 'angle of attack'),
    ('hdg',    C_, -0.172, -0.615, ('game', 'HEADING', 0.056), 'standby compass / HSI'),
    ('emd',    C_, 0.212, -0.490, ('paint_rect', 0.165, 0.240), 'engine monitor display'),
    ('rpm_l',  C_, 0.174, -0.4228, ('game', 'ENGINE_RPM', 0.040), 'left engine rpm'),
    ('rpm_r',  C_, 0.250, -0.4228, ('game', 'ENGINE_RPM', 0.040), 'right engine rpm'),
    ('egt_l',  C_, 0.174, -0.490, ('game', 'EGT', 0.040), 'left FTIT'),
    ('egt_r',  C_, 0.250, -0.490, ('game', 'EGT', 0.040), 'right FTIT'),
    ('fuelq',  C_, 0.262, -0.700, ('game', 'FUEL_QTY', 0.062), 'fuel quantity'),
    ('clock',  C_, 0.158, -0.700, ('paint_disc', 'CLOCK', 0.055), 'clock'),
    ('ew',     C_, 0.000, -0.655, ('paint_rect', 0.190, 0.085), 'EW / IFF panel'),
    ('ahcp',   L_, 0.105, -0.405, ('paint_rect', 0.100, 0.070), 'armament control strip'),
    ('gear',   L_, 0.105, -0.500, ('paint_rect', 0.100, 0.100), 'landing gear handle and lights'),
    ('brake',  L_, 0.105, -0.600, ('paint_rect', 0.100, 0.070), 'anti-skid / NWS / hook'),
    ('warning', R_, 0.105, -0.405, ('annunciator', 0.100, 0.070), 'caution / warning lights'),
    ('ecs',    R_, 0.105, -0.505, ('paint_rect', 0.100, 0.090), 'ECS panel'),
    ('oxy',    R_, 0.105, -0.610, ('game', 'FILLER_OXYGEN', 0.040), 'oxygen quantity'),
]
LAYOUT_R = [
    ('caut',    C_, 0.000, -0.188, ('paint_rect', 0.400, 0.034), 'caution light row'),
    ('mpcd_l',  C_, -0.106, -0.335, ('display', 'PFD', 0.135, 'LCD'), 'left MPCD'),
    ('mpcd_r',  C_, 0.106, -0.335, ('display', 'RADAR', 0.135, 'LCD'), 'right MPCD'),
    ('ufc',     C_, 0.000, -0.575, ('paint_rect', 0.140, 0.150), 'up-front controller'),
    ('r_alt',   C_, -0.155, -0.520, ('game', 'ALTIMETER', 0.058), 'standby altimeter'),
    ('r_asi',   C_, 0.155, -0.520, ('game', 'AIRSPEED', 0.058), 'standby airspeed'),
    ('mpd_l',   L_, 0.165, -0.340, ('display', 'RADAR', 0.125, 'CRT'), 'left MPD'),
    ('tews',    L_, 0.165, -0.545, ('paint_rect', 0.150, 0.150), 'TEWS control'),
    ('mpd_r',   R_, 0.165, -0.340, ('display', 'STORES', 0.125, 'CRT'), 'right MPD'),
    ('radar',   R_, 0.165, -0.545, ('paint_rect', 0.150, 0.150), 'radar / sensor control'),
]

# the caution lamps the game lights (the conditions are the game's)
LAMPS = [{"Text": "MASTER|CAUTION", "Condition": "MASTER_CAUTION", "Colour": "amber"},
         {"Text": "FIRE", "Condition": "FIRE", "Colour": "red"},
         {"Text": "ENG|OUT", "Condition": "ENGINE_OUT", "Colour": "red"},
         {"Text": "FUEL|LOW", "Condition": "LOW_FUEL", "Colour": "amber"},
         {"Text": "STALL", "Condition": "STALL", "Colour": "red"},
         {"Text": "OVER G", "Condition": "OVER_G", "Colour": "amber"},
         {"Text": "GEAR|DOWN", "Condition": "GEAR_DOWN", "Colour": "green"},
         {"Text": "GEAR|UNSAFE", "Condition": "GEAR_TRANSIT", "Colour": "red"},
         {"Text": "RWR|LOCK", "Condition": "RWR_LOCK", "Colour": "amber"},
         {"Text": "LAUNCH", "Condition": "MISSILE_LAUNCH", "Colour": "red"},
         {"Text": "HYD|PRESS", "Condition": "NONE", "Colour": "amber"},
         {"Text": "GEN", "Condition": "NONE", "Colour": "amber"}]
LAMP_COLUMNS = 6

FACET_OF = {C_: lambda p: p.CENTRE, R_: lambda p: p.RIGHT, L_: lambda p: p.LEFT}
PAINTERS = {'ufc_f': p_ufc_front, 'ufc_r': p_ufc_rear, 'emd': p_emd, 'ew': p_ew, 'ahcp': p_ahcp, 'gear': p_gear, 'brake': p_brake,
            'ecs': p_ecs, 'caut': p_caution_row, 'tews': p_tews, 'radar': p_radar_ctl}


def resolve(layout, panel):
    return [(n, FACET_OF[f](panel), a, y, s, l) for n, f, a, y, s, l in layout]


# ---------------------------------------------------------------- build helpers

def edge_bar(m, p0, p1, n, thick, depth, rect):
    """a thin bar along the segment p0-p1 in the plane with normal n: [thick] in the plane, [depth] along n"""
    p0 = np.array(p0, float); p1 = np.array(p1, float)
    d = unit(p1 - p0); L = np.linalg.norm(p1 - p0)
    perp = unit(np.cross(n, d))
    m.box((p0 + p1) / 2, (d, perp, unit(n)), (L / 2 + thick / 2, thick / 2, depth / 2), rect)


def build_panel(m, atlas, sw, panel, layout, ufc_key, seat, gauges, displays, seed, cache, lip, ctrl_ok=True):
    """facets (front face painted, slab 2 cm deep), instruments, bezels and the visor lip along the top edge.
    lip = (z over the centre, z at the wing ends): the strip runs from the panel's top edge forward to the dash's deck"""
    L = resolve(layout, panel)
    ppm = 700
    for f in (panel.CENTRE, panel.LEFT, panel.RIGHT):
        a0, a1 = f.extent()
        y_top = max(f.top(a) for a in f.knots())
        W = int(round((a1 - a0) * ppm)); Hh = int(round((y_top - f.bottom()) / math.cos(panel.LEAN) * ppm))
        rect = atlas.paint(W, Hh, panel_paint(panel, f, (W, Hh), L, seed))
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
    for name, f, a, y, spec, label in L:
        kind = spec[0]
        c = f.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': seat, 'Kind': spec[1], 'Center': r5(c + m.o + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c, f.N, f.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'], back=0.0)
        elif kind == 'paint_disc':
            dia = spec[2]
            key = ('disc', spec[1])
            rect = cache.get(key)
            if rect is None:
                rect = atlas.paint(96, 96, p_clock if spec[1] == 'CLOCK' else p_dial_static(spec[1]))
                cache[key] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, dia, dia, rect)
            m.ring(c, f.N, f.U, dia / 2, dia / 2 * (1 + RIM), 0.010, sw['bezel'], back=0.0)
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': seat, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + m.o + f.N * 0.004), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS,
                           'Lamps': LAMPS})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': seat, 'Center': r5(c + m.o + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': spec[3]})
            _, n_, u_, r_ = frame(c, f.N, f.U)
            pr = 0.010
            t = 0.022
            # an MPD / MPCD: a flat bezel with a row of push keys along each edge and a black outer wall
            m.rect_tex(c + u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
            m.rect_tex(c - u_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, w + 2 * t, t, bar_h_r)
            m.rect_tex(c + r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
            m.rect_tex(c - r_ * (w / 2 + t / 2) + n_ * pr, f.N, f.U, t, w, bar_v_r)
            for sgn in (-1, 1):
                m.box(c + u_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (w / 2 + t, 0.0015, (pr - 0.002) / 2), sw['black'])
                m.box(c + r_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (0.0015, w / 2 + t, (pr - 0.002) / 2), sw['black'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 200 if w >= 0.15 else 160 if w >= h else 80
            ph = max(16, int(round(pw * h / w)))
            key = ufc_key if name == 'ufc' else name
            rect = cache.get(('rect', key))
            if rect is None:
                rect = atlas.paint(pw, ph, PAINTERS[key])
                cache[('rect', key)] = rect
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])

    # the visor lip: a flat black strip from the panel's top edge forward to the dash's deck, so the two meet with no gap
    pts = []
    for f, aa in ((panel.LEFT, [panel.WING_LEN, 0.0]), (panel.CENTRE, [-panel.HINGE, -panel.TOP_K, panel.TOP_K, panel.HINGE]),
                  (panel.RIGHT, [0.0, panel.WING_LEN])):
        for a in aa:
            pts.append(f.at(a, f.top(a)))
    uniq = [pts[0]]
    for p in pts[1:]:
        if np.linalg.norm(p - uniq[-1]) > 1e-4:
            uniq.append(p)
    zl = lambda x: lip[0] if abs(x) <= 0.18 else lip[1]
    fwd = [np.array([p[0], p[1], zl(p[0])]) for p in uniq]
    up = np.array([0, 0.006, 0])
    for i in range(len(uniq) - 1):
        a, b, c2, d2 = uniq[i] + up, uniq[i + 1] + up, fwd[i + 1] + up, fwd[i] + up
        m.flat([a, b, c2], sw['coaming'], normal=[0, 1, 0])
        m.flat([a, c2, d2], sw['coaming'], normal=[0, 1, 0])


def liners_and_consoles(m, atlas, sw, panel, eye, z_back, tiles_l, tiles_r, kick_tex):
    """wall liners above the consoles up to the sill, the two consoles (top, inner side, front, back), floor liner,
    the panel's kick wall and the bulkhead's floor edge"""
    fy = FLOOR_ABS - eye[1]
    cy = CONSOLE_ABS - eye[1]
    zq = lambda zrel: zrel + eye[2]
    for sgn in (1, -1):
        zf_b = panel.front_z(sgn * WALL_X, cy)
        zf_t = panel.front_z(sgn * WALL_X, panel.TOP_W)
        top0 = sill_abs(eye[2] + z_back) - eye[1]
        top1 = sill_abs(eye[2] + zf_t - 0.02) - eye[1]
        m.flat([[sgn * WALL_X, cy, z_back], [sgn * WALL_X, cy, zf_b], [sgn * WALL_X, top1, zf_t - 0.02], [sgn * WALL_X, top0, z_back]],
               sw['panel_d'], normal=[-sgn, 0, 0])
        xi, xo = CON_IN * sgn, WALL_X * sgn
        zf_i, zf_o = panel.front_z(xi, cy), panel.front_z(xo, cy)
        tiles = tiles_l if sgn > 0 else tiles_r
        top = [[xi, cy, z_back + 0.01], [xo, cy, z_back + 0.01], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [uv_of(tiles, 0, 0), uv_of(tiles, 1, 0), uv_of(tiles, 1, 1), uv_of(tiles, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, z_back + 0.01], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, z_back + 0.01]], sw['panel_d'], normal=[-sgn, 0, 0])
        m.flat([[xi, fy, z_back + 0.01], [xo, fy, z_back + 0.01], [xo, cy, z_back + 0.01], [xi, cy, z_back + 0.01]],
               sw['panel_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf_i], [xo, fy, zf_o], [xo, cy, zf_o], [xi, cy, zf_i]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xo, fy, z_back + 0.01], [xo, fy, zf_o], [xo, cy, zf_o], [xo, cy, z_back + 0.01]], sw['panel_d'], normal=[-sgn, 0, 0])
    zk = panel.CENTRE.at(0, panel.BOT_C)[2]
    m.flat([[-CON_IN, fy, z_back], [CON_IN, fy, z_back], [CON_IN, fy, zk], [-CON_IN, fy, zk]], sw['floor'], normal=[0, 1, 0])
    m.quad_tex([[CON_IN, fy, zk], [-CON_IN, fy, zk], [-CON_IN, panel.BOT_C, zk], [CON_IN, panel.BOT_C, zk]], kick_tex, normal=[0, 0, -1])
    return zk


def pedals(m, sw, eye, zk):
    fy = FLOOR_ABS - eye[1]
    for s in (1, -1):
        m.box([s * 0.13, fy + 0.22, zk - 0.10], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, fy + 0.16, zk - 0.06], (X, Y, Z), (0.014, 0.060, 0.045), sw['dgrey'])


def throttles(m, sw, eye, tz, scale=1.0, hat=True):
    """the throttle quadrant on the left console and its two levers with the F-15 grips (speed brake switch, mic switch, the
    target management hat, the weapon release and the cage button on the back)"""
    cy = CONSOLE_ABS - eye[1]
    s = scale
    m.box([0.410, cy + 0.011, tz], (X, Y, Z), (0.085, 0.011, 0.140 * s), sw['dgrey'])
    for xl in (0.385, 0.435):
        lev = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
        m.box([xl, cy + 0.07 * s, tz + 0.04 * s], lev, (0.008, 0.060 * s, 0.010), sw['metal'])
        gax = (X, unit([0, 1, 0.45]), unit([0, -0.45, 1]))
        m.box([xl, cy + 0.140 * s, tz + 0.075 * s], gax, (0.017, 0.044 * s, 0.030), sw['black'])
        if hat:
            m.box([xl, cy + 0.188 * s, tz + 0.090 * s], gax, (0.014, 0.008, 0.026), sw['dgrey'])
            m.box(np.array([xl, cy + 0.152 * s, tz + 0.075 * s]) - gax[2] * 0.034 + gax[1] * 0.010, gax, (0.006, 0.006, 0.004),
                  sw['red'] if xl < 0.4 else sw['metal'])
    m.box([0.465, cy + 0.020, tz - 0.07], (X, Y, Z), (0.020, 0.012, 0.030), sw['metal'])      # speed brake switch


def switch_rows(m, sw, eye, sides, zs, skip=None, xs=(0.305, 0.335, 0.365, 0.395, 0.425, 0.455, 0.485, 0.515)):
    cy = CONSOLE_ABS - eye[1]
    for s in sides:
        for zr in zs:
            if skip and skip(s, zr):
                continue
            for xc in xs:
                m.box([s * xc, cy + 0.005, zr], (X, Y, Z), (0.009, 0.005, 0.009), sw['metal'])
                m.box([s * xc, cy + 0.020, zr - 0.003], (X, unit([0, 1, -0.3]), unit([0, 0.3, 1])), (0.0025, 0.012, 0.0025), sw['black'])


def hand_controller(m, sw, eye, sgn, zc, top_tex):
    """the WSO's hand controller: a base plate on the console, an angled grip and the thumb pad on top"""
    cy = CONSOLE_ABS - eye[1]
    x = 0.415 * sgn
    m.box([x, cy + 0.010, zc], (X, Y, Z), (0.050, 0.010, 0.060), sw['dgrey'])
    g = (X, unit([0, 1, 0.30]), unit([0, -0.30, 1]))
    c0 = np.array([x, cy + 0.060, zc + 0.014])
    m.box(c0, g, (0.016, 0.045, 0.022), sw['black'])                                    # the grip
    top = c0 + g[1] * 0.052 + g[2] * 0.004
    m.box(top, g, (0.022, 0.006, 0.027), sw['dgrey'], front=(1, 1, top_tex, -X, g[2]))   # thumb pad
    m.box(c0 - g[2] * 0.026 + g[1] * 0.012, g, (0.008, 0.016, 0.005), sw['metal'])      # trigger
    m.box(c0 + g[2] * 0.026 + g[1] * 0.030, g, (0.005, 0.005, 0.004), sw['red'])


def centre_stick(m, stick, sw, eye, foot, boot=True):
    """the boot (fixed) and the stick itself (into [stick], the bone that moves; or [m] for a fixed one), pivot at the foot"""
    m.box(foot + [0, 0.012, 0], (X, Y, Z), (0.060, 0.012, 0.060), sw['dgrey'])
    m.box(foot + [0, 0.045, 0], (X, Y, Z), (0.030, 0.033, 0.030), sw['black'])
    col_ax = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
    stick.box(foot + col_ax[1] * 0.190, col_ax, (0.011, 0.175, 0.011), sw['metal'])
    g_ax = (X, unit([0, 1, -0.20]), unit([0, 0.20, 1]))
    gc = foot + col_ax[1] * 0.360 + g_ax[2] * 0.012
    stick.box(gc, g_ax, (0.022, 0.070, 0.030), sw['black'])                                      # the grip
    stick.box(gc + g_ax[1] * 0.070, g_ax, (0.016, 0.006, 0.022), sw['dgrey'])                    # the four-way trim hat
    stick.box(gc + g_ax[1] * 0.063 + g_ax[2] * 0.010, g_ax, (0.005, 0.004, 0.005), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.020, g_ax, (0.008, 0.016, 0.005), sw['metal'])  # trigger
    stick.box(gc - g_ax[2] * 0.031 + g_ax[1] * 0.030, g_ax, (0.014, 0.020, 0.003), sw['dgrey'])  # pinky switch
    stick.box(gc + g_ax[1] * 0.030, g_ax, (0.024, 0.010, 0.032), sw['dgrey'])                    # mid-grip band


def ejection_seat(m, sw, tiles, pan_y, floor_y):
    """ACES II ejection seat reclined 15 degrees, its back through the headrest behind the head"""
    cushion, backt, headbox, stripes = tiles
    rec = math.radians(15)
    BU = np.array([0, math.cos(rec), -math.sin(rec)])            # up the seat back
    BN = np.array([0, math.sin(rec), math.cos(rec)])             # out of the back, toward the crew's front
    Q0 = np.array([0, pan_y, (-0.20) + (0.0 - pan_y) * math.tan(rec)])    # back line at the pan: through the headrest face
    ax = (X, BU, BN)
    top_b, bot_b = pan_y - 0.062, floor_y + 0.003
    m.box([0, (top_b + bot_b) / 2, Q0[2] + 0.08], (X, Y, Z), (0.185, (top_b - bot_b) / 2, 0.200), sw['frame'])      # base and kit
    m.box([0, pan_y - 0.030, Q0[2] + 0.17], (X, Y, Z), (0.200, 0.030, 0.175), sw['cushion'], front=(1, 1, cushion, -X, Z))
    for s in (1, -1):
        m.box([s * 0.215, pan_y + 0.02, Q0[2] + 0.17], (X, Y, Z), (0.012, 0.065, 0.185), sw['frame'])  # bucket sides
        m.box(Q0 + BU * 0.37 + X * s * 0.175 - BN * 0.055, ax, (0.018, 0.57, 0.024), sw['frame'])      # guide rails
    m.box(Q0 + BU * 0.40 - BN * 0.02, ax, (0.18, 0.40, 0.030), sw['cushion'], front=(2, 1, backt, X, BU))      # back cushion
    m.box(Q0 + BU * 0.42 - BN * 0.085, ax, (0.205, 0.42, 0.030), sw['frame'])                         # back shell
    m.box(Q0 + BU * 0.90 - BN * 0.045, ax, (0.150, 0.100, 0.095), sw['frame'], front=(2, 1, headbox, X, BU))  # headbox
    m.box(Q0 + BU * 0.80 + BN * 0.012, ax, (0.090, 0.065, 0.020), sw['cushion'])                      # headrest pad
    for s in (1, -1):                                        # the ejection handles on the pan's front corners
        m.box([s * 0.245, pan_y + 0.01, Q0[2] + 0.31], (X, Y, Z), (0.020, 0.020, 0.050), sw['yellow'], front=(1, 1, stripes, -X, Z))
        m.box([s * 0.245, pan_y - 0.02, Q0[2] + 0.36], (X, Y, Z), (0.010, 0.010, 0.012), sw['black'])
    m.box(Q0 + BU * 0.55 - BN * 0.115, ax, (0.075, 0.095, 0.045), sw['dgrey'])                        # the oxygen / kit pack
    return Q0

# ---------------------------------------------------------------- the stations

def swatches(atlas):
    cols = dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lgrey=LGREY, frame=FRAME, cushion=CUSHION,
                yellow=YELLOW, red=RED, metal=METAL, floor=FLOOR, bezel=(30, 31, 33), coaming=(20, 21, 22), console=CONSOLE,
                combiner_frame=(34, 36, 38))
    return {k: atlas.swatch(c) for k, c in cols.items()}


def seat_tiles(atlas):
    return (atlas.paint(64, 64, p_cushion(CUSHION)), atlas.paint(64, 96, p_cushion(CUSHION, straps=True)),
            atlas.paint(64, 64, p_headbox), atlas.paint(64, 16, p_stripes))


BACK_F = 3.995 - EYE_F[2]           # the bulkhead panel behind the pilot (5 mm ahead of the hull's own face at 3.99)
BACK_R = 2.278 - EYE_R[2]           # the rear bulkhead behind the WSO (hull face at 2.273)
HUD_LEAN = math.radians(20)


def hud_unit(m, atlas, sw):
    """The wide-angle HUD: one unit on the deck (its housing with the control strip low on the pilot's face) and ONE combiner glass
    (the Kaiser unit has a single large combiner), its outline wider than tall with the top corners cut, held by a slim frame
    whose two side bars run down into the housing. Returns the glass triangles (vehicle-local) and the HUD entry's numbers."""
    ctrl = atlas.paint(200, 44, p_hud_ctrl)
    hz0, hz1, hy0, hy1, hhw = 0.700, 0.850, -0.133, -0.077, 0.125
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hhw, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    g_up = np.array([0, math.cos(HUD_LEAN), -math.sin(HUD_LEAN)])
    g_n = unit(np.cross(X, g_up))
    hw, h = 0.115, 0.160
    g_bot = np.array([0, -0.085, 0.760])
    outline = [(-hw, 0.0), (hw, 0.0), (hw, 0.118), (0.085, h), (-0.085, h), (-hw, 0.118)]       # (x, up the glass)
    P = [g_bot + X * u + g_up * t for u, t in outline]
    th, dp = 0.008, 0.008
    cf = sw['combiner_frame']
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        edge_bar(m, a, b, g_n, th, dp, cf)
    edge_bar(m, g_bot + X * (-hw) - g_up * 0.018, g_bot + X * hw - g_up * 0.018, g_n, 0.012, 0.012, cf)     # the lower rail in the slot
    for s in (-1, 1):                                                                                # the side bars continue into the housing
        edge_bar(m, g_bot + X * s * hw - g_up * 0.030, g_bot + X * s * hw, g_n, th, dp, cf)
    tris = []
    for k in range(1, len(P) - 1):
        tris += [P[0] + m.o, P[k] + m.o, P[k + 1] + m.o]
    centre = g_bot + g_up * (h / 2)
    return tris, dict(centre=centre + m.o, normal=-g_n, up=g_up, width=2 * hw, height=h)



def deck_cover(geo, eye, z_rng, y_rng, x_rng, need_up=None, off=0.004):
    """black covers 4 mm over the airframe's own deck faces that still carry the old model's painted panels (the dash's sloping
    outer decks, the rear ledge): the faces of the two hull bones in the window, each offset toward the eye. Eye-relative points."""
    out = []
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] not in SOURCE_BONES:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for poly in pm['polys']:
            if not poly or len(poly) > 4:
                continue
            V = P[[v[0] for v in poly]]
            c = V.mean(0)
            if not (z_rng[0] <= c[2] <= z_rng[1] and y_rng[0] <= c[1] <= y_rng[1] and x_rng[0] <= abs(c[0]) <= x_rng[1]):
                continue
            if np.all(np.abs(V[:, 2] - 3.267) < 0.002):
                continue                                   # (the wall the rear panel stands against)
            n = np.cross(V[1] - V[0], V[2] - V[0]); ln = np.linalg.norm(n)
            if ln < 1e-9:
                continue
            n /= ln
            if need_up is not None and abs(n[1]) < need_up:
                continue
            if n @ (np.array(eye) - c) < 0:
                n = -n
            out.append([list(p + n * off - np.array(eye)) for p in V])
    return out

def build_front(atlas, sw, geo):
    m = Mesh(EYE_F); stick = Mesh(EYE_F)
    gauges, displays = [], []
    for q in deck_cover(geo, EYE_F, (4.98, 5.22), (2.93, 3.26), (0.18, 0.545), need_up=0.3):
        m.flat(q, sw['coaming'], toward=[0, 0, 0])
    cache = {'bar_h': atlas.paint(96, 16, bar_h), 'bar_v': atlas.paint(16, 96, bar_v)}
    P = PANEL_F
    build_panel(m, atlas, sw, P, LAYOUT_F, 'ufc_f', 0, gauges, displays, 3, cache, lip=(0.642, 0.724))
    con_l = atlas.paint(64, 340, p_console(1, [0.10, 0.14, 0.09, 0.13, 0.11, 0.12, 0.13, 0.09], 21))
    con_r = atlas.paint(64, 340, p_console(-1, [0.12, 0.10, 0.14, 0.09, 0.15, 0.10, 0.12, 0.09], 22))
    kick = atlas.paint(180, 60, p_kick)
    zk = liners_and_consoles(m, atlas, sw, P, EYE_F, BACK_F, con_l, con_r, kick)
    pedals(m, sw, EYE_F, zk)
    cy = CONSOLE_ABS - EYE_F[1]
    # the left console: the throttles on their quadrant plate, the AHCP-like panel ahead of them, switch rows behind
    plate = atlas.paint(60, 80, p_throttle_plate)
    m.rect_tex([0.410, cy + 0.0225, 0.14], Y, Z, 0.17, 0.28, plate)
    throttles(m, sw, EYE_F, 0.14)
    ahcp_c = atlas.paint(200, 150, p_ahcp_console)
    tilt = (X, unit([0, 1, -0.35]), unit([0, 0.35, 1]))
    m.box([0.415, cy + 0.030, 0.385], tilt, (0.095, 0.022, 0.072), sw['dgrey'], front=(1, 1, ahcp_c, -X, tilt[2]))
    switch_rows(m, sw, EYE_F, (1,), (-0.20, -0.13))
    switch_rows(m, sw, EYE_F, (-1,), (-0.20, -0.13, -0.06, 0.30, 0.37, 0.44), xs=(0.305, 0.335, 0.365, 0.395, 0.425, 0.455, 0.485))
    # the bulkhead behind the pilot with its breaker panel, and the padded cover over the block between the seats
    bulk = atlas.paint(180, 100, p_bulkhead(3, 70))
    fy = FLOOR_ABS - EYE_F[1]
    top = 3.245 - EYE_F[1]
    m.quad_tex([[WALL_X, fy, BACK_F], [-WALL_X, fy, BACK_F], [-WALL_X, top, BACK_F], [WALL_X, top, BACK_F]], bulk, normal=[0, 0, 1])
    cover = atlas.paint(120, 100, p_cover)
    yc = 3.257 - EYE_F[1]
    m.quad_tex([[0.48, yc, 3.455 - EYE_F[2]], [-0.48, yc, 3.455 - EYE_F[2]], [-0.48, yc, BACK_F + 0.012], [0.48, yc, BACK_F + 0.012]], cover,
               normal=[0, 1, 0])
    # the centre stick: pivot at the foot between the knees
    foot = np.array([0.0, fy + 0.007, zk - 0.09])
    centre_stick(m, stick, sw, EYE_F, foot)
    seat = ejection_seat(m, sw, seat_tiles(atlas), -0.775, fy)
    glass, hud = hud_unit(m, atlas, sw)
    return m, gauges, displays, glass, stick, hud, foot


def build_rear(atlas, sw, geo):
    m = Mesh(EYE_R)
    gauges, displays = [], []
    for q in deck_cover(geo, EYE_R, (3.26, 3.45), (3.15, 3.26), (0.0, 0.545)):
        m.flat(q, sw['coaming'], toward=[0, 0, 0])
    cache = {'bar_h': atlas.paint(96, 16, bar_h), 'bar_v': atlas.paint(16, 96, bar_v)}
    P = PANEL_R
    build_panel(m, atlas, sw, P, LAYOUT_R, 'ufc_r', 1, gauges, displays, 5, cache, lip=(0.677, 0.677))
    con_l = atlas.paint(64, 340, p_console(1, [0.11, 0.09, 0.13, 0.12, 0.10, 0.14, 0.09, 0.12], 23))
    con_r = atlas.paint(64, 340, p_console(-1, [0.09, 0.13, 0.11, 0.10, 0.14, 0.12, 0.09, 0.12], 24))
    kick = atlas.paint(180, 60, p_kick)
    zk = liners_and_consoles(m, atlas, sw, P, EYE_R, BACK_R, con_l, con_r, kick)
    pedals(m, sw, EYE_R, zk)
    cy = CONSOLE_ABS - EYE_R[1]
    plate = atlas.paint(60, 80, p_throttle_plate)
    m.rect_tex([0.410, cy + 0.0225, 0.06], Y, Z, 0.17, 0.24, plate)
    throttles(m, sw, EYE_R, 0.06, scale=0.85, hat=False)                                   # the rear seat's small throttle quadrant
    top_tex = atlas.paint(64, 64, p_handctl)
    hand_controller(m, sw, EYE_R, 1, 0.30, top_tex)                                         # the two hand controllers
    hand_controller(m, sw, EYE_R, -1, 0.30, top_tex)
    switch_rows(m, sw, EYE_R, (1,), (-0.24, -0.17, -0.10))
    switch_rows(m, sw, EYE_R, (-1,), (-0.24, -0.17, -0.10, -0.03), xs=(0.305, 0.335, 0.365, 0.395, 0.425, 0.455, 0.485))
    bulk = atlas.paint(180, 100, p_bulkhead(4, 80))
    fy = FLOOR_ABS - EYE_R[1]
    top = 3.13 - EYE_R[1]
    m.quad_tex([[WALL_X, fy, BACK_R], [-WALL_X, fy, BACK_R], [-WALL_X, top, BACK_R], [WALL_X, top, BACK_R]], bulk, normal=[0, 0, 1])
    foot = np.array([0.0, fy + 0.007, zk - 0.09])
    centre_stick(m, m, sw, EYE_R, foot)                                                     # fixed: the rig has one pair of stick bones
    ejection_seat(m, sw, seat_tiles(atlas), -0.745, fy)
    return m, gauges, displays


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


# the two tubs' interiors (absolute): the front from its bulkhead (z 3.99) to the dash's rear wall (4.984), the rear from its
# bulkhead (2.273) to the block's front face (3.267); every vertex-connected part lying wholly inside a box goes (seats, handles,
# the old HUD housings, switch boxes), and so do the small faces and the console boxes still attached to the shell
R_BOXES = [(np.array([-0.56, 2.25, 3.90]), np.array([0.56, 3.56, 5.325])), (np.array([-0.56, 2.25, 2.15]), np.array([0.56, 3.56, 3.36]))]
TUBS = [(3.995, 4.983), (2.275, 3.266)]
TUB_X = 0.537


def old_interior(geo):
    """{bone: set(poly index)} of the airframe's own loose interior. The walls, floor, the two bulkheads, the block between the
    seats, the sill bands, the spine, the nose and the dash blocks with their decks stay."""
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
            for lo, hi in R_BOXES:
                if np.all(V.min(0) >= lo) and np.all(V.max(0) <= hi):
                    sel.update(fl)
                    break
        for i, poly in enumerate(pm['polys']):
            if i in sel or not poly:
                continue
            V = P[[v[0] for v in poly]]
            c = V.mean(0)
            for z0, z1 in TUBS:
                if not (z0 <= c[2] <= z1 and 2.31 < c[1] < 3.6 and abs(c[0]) < TUB_X + 0.05):
                    continue
                if np.ptp(V, axis=0).max() < 0.6:
                    sel.add(i)
                    break
                # the console boxes: wholly within x 0.27 .. 0.545, y 2.31 .. 2.76, and not the wall or floor planes themselves
                nn = np.cross(V[1] - V[0], V[2] - V[0]); nn = nn / max(np.linalg.norm(nn), 1e-12)
                if abs(nn[0]) > 0.7 and np.abs(V[:, 0]).mean() > 0.49:
                    continue                                  # (the tub's own wall faces, sloped or flat, stay)
                if (np.abs(V[:, 0]).min() >= 0.27 and np.abs(V[:, 0]).max() <= 0.545 and V[:, 1].max() <= 2.76
                        and not np.all(np.abs(np.abs(V[:, 0]) - 0.542) < 0.003) and not np.all(np.abs(V[:, 1] - 2.304) < 0.003)):
                    sel.add(i)
                    break
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


def move_eyes(data):
    """the two seats and eyes into the vehicle data (x left, y up, z forward, blocks); each seat position stays 1.62 below its
    eye and its dismount point stays level with it. Only these keys are touched."""
    for idx, eye in ((0, EYE_F), (1, EYE_R)):
        ex, ey, ez = eye
        seat = data['Seats'][idx]
        seat['Position'] = [0, round(ey - SEAT_DROP, 9), ez]
        data['Attachments'][seat['CameraPos']['EyeAttachment']]['Position'] = [ex, ey, ez]
        seat['DismountInfo']['Position'][2] = ez


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    g = geo['minecraft:geometry'][0]
    rm = old_interior(geo)
    removed = 0
    for b in g['bones']:
        if b['name'] in rm:
            pm = b['poly_mesh']
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in rm[b['name']]]
            removed += len(rm[b['name']])
    atlas = Atlas()
    sw = swatches(atlas)
    mf, gf, df, glass, stick, hud, foot = build_front(atlas, sw, geo)
    mr, gr, dr = build_rear(atlas, sw, geo)
    pms = {BONE_F: mf.poly_mesh(), BONE_R: mr.poly_mesh()}
    for name, anchor in ((BONE_F, SOURCE_BONES[0]), (BONE_R, SOURCE_BONES[1])):
        bone = next((b for b in g['bones'] if b['name'] == name), None)
        if bone is None:
            hull = next(b for b in g['bones'] if b['name'] == 'hull')
            at = next(i for i, b in enumerate(g['bones']) if b['name'] == anchor) + 1
            bone = {'name': name, 'parent': 'hull', 'pivot': list(hull['pivot'])}
            g['bones'].insert(at, bone)
        bone['poly_mesh'] = pms[name]
    # the stick: its three bones' pivots to the centre stick's foot (model pixels)
    fv = foot + np.array(EYE_F)
    foot_px = [round(float(fv[0]) * 16, 5), round(float(fv[1]) * 16, 5), round(-float(fv[2]) * 16, 5)]
    for b in g['bones']:
        if b['name'] in STICK_BONES:
            b['pivot'] = list(foot_px)
        if b['name'] == STICK_BONE:
            b['poly_mesh'] = stick.poly_mesh()
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
        keep = ~((np.abs(c[:, 0]) < 0.14) & (c[:, 1] > -0.12) & (c[:, 1] < 0.12) & (c[:, 2] > 0.62) & (c[:, 2] < 0.80))
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    print(f'f_15e: {removed} old interior faces removed; front {len(pms[BONE_F]["polys"])} faces, rear {len(pms[BONE_R]["polys"])} faces; '
          f'{len(gf) + len(gr)} gauges ({len(gr)} rear), {len(df) + len(dr)} displays ({len(dr)} rear), HUD combiner {len(glass) // 3} triangles; '
          f'stick foot {foot_px} px; atlas {atlas.used()} of {sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    data = json.load(open(DATA, encoding='utf-8'))              # (read fresh: the file also holds other work)
    move_eyes(data)
    write_json(DATA, data, 2)
    fb = json.load(open(FALLBACK, encoding='utf-8'))
    fbones = fb['minecraft:geometry'][0]['bones']
    for b in fbones:
        if b['name'] in STICK_BONES:
            b['pivot'] = list(foot_px)
    if not any(b['name'] == BONE_R for b in fbones):
        at = next(i for i, b in enumerate(fbones) if b['name'] == SOURCE_BONES[1]) + 1
        hull = next(b for b in fbones if b['name'] == 'hull')
        fbones.insert(at, {'name': BONE_R, 'parent': 'hull', 'pivot': list(hull['pivot'])})
    write_json(FALLBACK, fb, None, newline=False)
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])