#!/usr/bin/env python3
"""MiG-29 (9.12) cockpit, modelled from the real panel layout.

Replaces the generic cockpit interior of custom_geo/mig_29.geo.json (seat, panel, consoles, HUD box) with a modelled
cockpit that fits the existing canopy and windscreen arch:

  * a three-facet main instrument panel (flat centre, left and right wings turned toward the pilot), 12 degrees back;
  * the black glareshield coaming lofted from the panel lip forward to the windscreen base;
  * the ILS-31 head-up display: body under the coaming, control strip on its aft face, combiner frame, and the
    combiner glass itself (added to CanopyGlass so it draws as glass);
  * the IT-23M head-down display (radar/TV) with its shading hood in the upper right corner;
  * side consoles, side walls, floor, rear bulkhead, rudder pedals, the throttle (RUD) on the left console and a
    K-36DM ejection seat;
  * every instrument at its place on the 9.12 panel (see README.md for the layout and its sources). Instruments the
    game draws (ATTITUDE, HEADING, ALTIMETER, THROTTLE and the filler dials) are written to CockpitGauges and the
    IT-23M to FlightDisplays; the others are painted dial faces with modelled bezels.

New faces go into one bone, wreck_fuselage_1__cockpit (parent hull), so they break away with the forward fuselage
section. The paint lives in a free area of textures/entity/mig_29.png (and a burnt copy in entity_dead/mig_29.png).
Running the script again rebuilds the same result: the removed old faces stay removed, the bone and the atlas area
are rewritten, and the combiner triangles in CanopyGlass are replaced.

If tools/canopy_glass/glass.py is ever re-run for the MiG-29, run this script again afterwards to put the HUD
combiner glass back.

Frames: vehicle-local blocks (x left, y up, z forward; 1 block = 1 m on this model) throughout; model pixels are
(x, y, -z) * 16.

Usage: python3 tools/cockpit_models/mig_29.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/mig_29.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/mig_29.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/mig_29.json')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/mig_29.png')
TEX_DEAD = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity_dead/mig_29.png')

BONE = 'wreck_fuselage_1__cockpit'
SOURCE_BONE = 'wreck_fuselage_1__hull'
ATLAS = [(1152, 0, 896, 640), (0, 0, 1152, 190)]   # free texture areas (x, y, w, h): no UVs of the model land here
TW, TH = 2048, 1024
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
FONT_B = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'

# ---------------------------------------------------------------- colours

TURQ = (74, 150, 154)          # Soviet cockpit turquoise
TURQ_D = (52, 112, 116)
TURQ_L = (96, 170, 172)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
LGREY = (150, 154, 156)
FRAME = (62, 68, 64)           # K-36 seat structure
CUSHION = (58, 62, 52)
HEADPAD = (92, 96, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)
WHITE = (226, 228, 220)
GREEN_LAMP = (60, 150, 70)
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
        """guillotine packing: the free rectangle with the best short-side fit, split along its shorter leftover"""
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


# ---------------------------------------------------------------- mesh

class Mesh:
    def __init__(self):
        self.faces = []                      # (points k x 3 vehicle-local, uvs k x 2, normal 3)

    def face(self, pts, uvs, toward=None, normal=None):
        P = np.array(pts, float)
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
            want = np.array(toward, float) - P.mean(0)
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
        """oriented box; axes = 3 unit vectors (a, b, c), half sizes; front = (axis index, sign, rect) textured face"""
        c = np.array(centre, float); A = [np.array(a, float) for a in axes]
        for k in range(3):
            for s in (-1, 1):
                i, j = [m for m in range(3) if m != k]
                fc = c + A[k] * half[k] * s
                pts = [fc - A[i] * half[i] - A[j] * half[j], fc + A[i] * half[i] - A[j] * half[j],
                       fc + A[i] * half[i] + A[j] * half[j], fc - A[i] * half[i] + A[j] * half[j]]
                if front and front[0] == k and front[1] == s:
                    # texture upright: s along axis i (or j), t along the other; caller passes (k, s, rect, right, up)
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
        """flat annulus (bezel) standing [proud] in front of the plane, with its outer wall back to the plane"""
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
        """textured round face: the tile's inscribed circle"""
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


# ---------------------------------------------------------------- the panel frame

EYE = None                                  # filled from the vehicle data
LEAN = math.radians(12)                     # panel top leans away from the pilot
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])
R_C = np.array([-1.0, 0, 0])                # pilot's right
P0 = np.array([0.0, 2.97, 3.85])            # centre facet reference point (0.82 m ahead of the eye)
HINGE = 0.22                                # centre facet half width
WING = math.radians(28)                     # wings turned toward the pilot
WING_LEN = (0.44 - HINGE) / math.cos(WING)  # wings reach the cockpit walls
TOP_C = 3.235                               # centre facet top (under the coaming lip)
TOP_W = 3.13                                # wing top at the wall (canopy sill)
BOT_C = 2.70
BOT_W = 2.80                                # wings end on the side consoles
CONSOLE_Y = 2.80
FLOOR_Y = 2.30
WALL_X = 0.44


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
            p = P0 + R_C * (self.side * HINGE) + U_C * v + (R_C * self.side * math.cos(WING) + N_C * math.sin(WING)) * a
        return p + self.N * lift

    def top(self, a):
        return TOP_C if self.side == 0 else TOP_C + (TOP_W - TOP_C) * a / WING_LEN

    def bottom(self):
        return BOT_C if self.side == 0 else BOT_W

    def extent(self):
        return (-HINGE, HINGE) if self.side == 0 else (0.0, WING_LEN)


CENTRE, RIGHT, LEFT = Facet(0), Facet(1), Facet(-1)

# Instrument layout of the MiG-29 9.12 front panel (README.md lists the sources). Positions: facet, a (see Facet.at),
# y (height in the vehicle frame). Sizes: dial diameter, or w x h. 'game' kinds are drawn by the game's gauge renderer;
# 'paint' ones are painted faces.
LAYOUT = [
    # centre facet, top row (beside the HUD body)
    ('aoa',      CENTRE, -0.190, 3.190, ('game', 'AOA', 0.040), 'UAP-14 angle of attack'),
    ('g',        CENTRE, -0.143, 3.190, ('game', 'G_METER', 0.040), 'UAP-14 g-meter'),
    ('caution',  CENTRE, -0.100, 3.196, ('paint_disc', 0.024), 'master caution lamp'),
    ('flares',   CENTRE,  0.150, 3.194, ('paint_rect', 0.060, 0.030), 'countermeasure counter'),
    # row 1: the T's top line
    ('adi',      CENTRE, -0.155, 3.075, ('game', 'ATTITUDE', 0.085), 'KPP attitude director (ADI)'),
    ('vvi',      CENTRE, -0.050, 3.075, ('game', 'VVI', 0.065), 'VAR vertical velocity'),
    ('radalt',   CENTRE,  0.055, 3.075, ('paint_dial', 0.065), 'radio altimeter'),
    ('tach',     CENTRE,  0.150, 3.075, ('game', 'THROTTLE', 0.065), 'ITE-2T twin tachometer (engine %)'),
    # row 2
    ('hsi',      CENTRE, -0.155, 2.963, ('game', 'HEADING', 0.085), 'PNP-72 horizontal situation (HSI)'),
    ('mach',     CENTRE, -0.050, 2.970, ('game', 'MACH', 0.065), 'KUS true airspeed / Mach'),
    ('egt_l',    CENTRE,  0.060, 2.975, ('paint_dial', 0.050), 'ITG gas temperature, left engine'),
    ('egt_r',    CENTRE,  0.130, 2.975, ('paint_dial', 0.050), 'ITG gas temperature, right engine'),
    # row 3
    ('afcs',     CENTRE, -0.155, 2.862, ('paint_rect', 0.100, 0.040), 'SAU autopilot push-lights'),
    ('clock',    CENTRE, -0.050, 2.862, ('game', 'FILLER_CLOCK', 0.058), 'AChS-1 clock'),
    ('ramps',    CENTRE,  0.095, 2.870, ('paint_rect', 0.075, 0.045), 'air intake ramp positions'),
    # left wing: airspeed / altimeter / mechanisation column, warning lights, gear handle
    ('weapons',  LEFT,    0.075, 3.160, ('paint_rect', 0.110, 0.034), 'weapon control panel'),
    ('ias',      LEFT,    0.070, 3.075, ('game', 'AIRSPEED', 0.068), 'KUS indicated airspeed'),
    ('alt',      LEFT,    0.070, 2.975, ('game', 'ALTIMETER', 0.068), 'VD-20 barometric altimeter'),
    ('gear_ind', LEFT,    0.070, 2.875, ('paint_dial', 0.058), 'IP-52 gear / flaps / airbrake indicator'),
    ('signals',  LEFT,    0.162, 2.990, ('paint_rect', 0.052, 0.230), 'signal lights panel'),
    # right wing: IT-23M up top, fuel and Ekran, SPO-15, hydraulics / oxygen / volts
    ('hdd',      RIGHT,   0.078, 3.112, ('display', 'RADAR', 0.105), 'IT-23M head-down display'),
    ('fuel',     RIGHT,   0.056, 2.995, ('game', 'FILLER_FUEL', 0.058), 'fuel quantity'),
    ('ekran',    RIGHT,   0.150, 2.995, ('paint_rect', 0.078, 0.046), 'Ekran warning display'),
    ('spo15',    RIGHT,   0.100, 2.904, ('game', 'RWR', 0.064), 'SPO-15 Beryoza radar warning'),
    ('hyd',      RIGHT,   0.045, 2.832, ('game', 'FILLER_HYDRAULIC', 0.042), 'IKG hydraulic pressures'),
    ('oxy',      RIGHT,   0.110, 2.832, ('game', 'FILLER_OXYGEN', 0.042), 'oxygen'),
    ('volts',    RIGHT,   0.175, 2.832, ('game', 'FILLER_VOLTS', 0.042), 'voltmeter'),
]

RIM = 0.16                     # game gauge bezel width (CockpitGauges.RIM)
GAUGE_LIFT = 0.004             # game dials stand this far proud of the panel face
DISPLAY_LIFT = 0.003


# ---------------------------------------------------------------- painting helpers

def font(px, bold=False):
    try:
        return ImageFont.truetype(FONT_B if bold else FONT, int(px))
    except OSError:
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


def dial_face(d, im, title, unit, lo, hi, step, label_step, value, start=-135, sweep=270, labels=None, red=None,
              second=None):
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
    if unit:
        text(d, (c, c - R * .32), unit, S * .075)
    for val, length, w in ([(value, .80, .045)] + ([second] if second else [])):
        a = ang(val)
        d.line([c - .15 * R * math.sin(a), c + .15 * R * math.cos(a), c + length * R * math.sin(a),
                c - length * R * math.cos(a)], fill=(240, 240, 232, 255), width=int(S * w))
    d.ellipse([c - S * .05, c - S * .05, c + S * .05, c + S * .05], fill=(60, 60, 60, 255))
    glare(d, S)


def glare(d, S):
    c = S / 2
    d.arc([S * .12, S * .12, S * .88, S * .88], 200, 250, fill=(255, 255, 255, 40), width=int(S * .05))
    del c


def lamp(d, box, colour, label=None, lit=False, px=None):
    x0, y0, x1, y1 = box
    base = tuple(int(c * (1.0 if lit else 0.45)) for c in colour)
    d.rectangle(box, fill=base + (255,), outline=(10, 10, 10, 255), width=2)
    d.rectangle([x0 + 3, y0 + 3, x1 - 3, y0 + (y1 - y0) * 0.35], fill=tuple(min(255, c + 30) for c in base) + (255,))
    if label:
        text(d, ((x0 + x1) / 2, (y0 + y1) / 2), label, px or (y1 - y0) * .42, fill=(20, 20, 20))


def panel_paint(facet, rect_px, ppm):
    """the facet face: turquoise, panel seams, screws, dark recesses behind every instrument, placards"""
    a0, a1 = facet.extent()
    W, H = rect_px
    y_bot = facet.bottom(); y_top = max(facet.top(a0), facet.top(a1))
    SS = Atlas.SS
    def to_px(a, y):                      # a, y -> tile pixel (SS scale)
        s = (a - a0) / (a1 - a0)
        if facet.side == -1:              # the left wing's tile is mirrored on its face (it runs leftward)
            s = 1 - s
        return (s * W * SS, (1 - (y - y_bot) / (y_top - y_bot)) * H * SS)
    def fn(d, im):
        noisy(d, im, TURQ, 5, seed=3 + facet.side)
        # sub-panel seams: Soviet panels are made of screwed plates
        seams_y = [2.92, 3.02, 3.13] if facet.side == 0 else [2.93, 3.04]
        for y in seams_y:
            x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
            d.line([x0, yy, x1, yy], fill=TURQ_D + (255,), width=SS * 2)
        for (a, y) in [(a0 + 0.012, y_bot + 0.012), (a1 - 0.012, y_bot + 0.012), (a0 + 0.012, y_top - 0.02),
                       (a1 - 0.012, y_top - 0.02)]:
            x, yy = to_px(a, y); r = SS * 2.2
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        for name, f, a, y, spec, _ in LAYOUT:
            if f is not facet:
                continue
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_dial', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 26, 26, 255))
                # small placard under each round instrument
                if dia > 0.04:
                    pw = dia * 0.55 / (a1 - a0) * W * SS; ph = 0.007 / (a1 - a0) * W * SS
                    py = yy + r + ph * 0.9
                    if py + ph < H * SS:
                        d.rectangle([x - pw / 2, py - ph / 2, x + pw / 2, py + ph / 2], fill=(18, 18, 18, 255))
            elif kind in ('paint_rect', 'display'):
                if kind == 'display':
                    w = h = spec[2]
                else:
                    w, h = spec[1], spec[2]
                sx = (w / 2 + 0.006) / (a1 - a0) * W * SS; sy = (h / 2 + 0.006) / (y_top - y_bot) * H * SS * math.cos(LEAN)
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
        if facet.side == 0:
            # lower centre: placards and the two emergency handles' plates
            for k, lab in enumerate(['ТОРМОЗ', 'АВАР. ВЫП.']):
                x, yy = to_px(-0.10 + k * 0.20, 2.765)
                d.rectangle([x - 70, yy - 14, x + 70, yy + 14], fill=(18, 18, 18, 255))
                text(d, (x, yy), lab, 18)
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_radalt(d, im):
    dial_face(d, im, 'H', 'м', 0, 600, 20, 100, 0, start=-150, sweep=300, labels=['0', '1', '2', '3', '4', '5', '6'])
    S = im.width
    d.rectangle([S * .58, S * .55, S * .72, S * .66], fill=(200, 60, 40, 255))


def p_egt(d, im):
    dial_face(d, im, 't° газа', '×100 °C', 2, 10, 0.5, 2, 6.4, red=(9.0, 10))


def p_gear(d, im):
    S = im.width; c = S / 2
    d.ellipse([S * .02, S * .02, S * .98, S * .98], fill=(14, 15, 16, 255))
    # planform with the three gear lamps and flap / airbrake segments (IP-52)
    pl = [(c, S * .16), (c + S * .07, S * .42), (c + S * .36, S * .58), (c + S * .36, S * .64), (c + S * .08, S * .62),
          (c + S * .10, S * .78), (c + S * .2, S * .84), (c - S * .2, S * .84), (c - S * .10, S * .78),
          (c - S * .08, S * .62), (c - S * .36, S * .64), (c - S * .36, S * .58), (c - S * .07, S * .42)]
    d.polygon(pl, outline=WHITE + (255,), fill=(40, 42, 44, 255))
    for (x, y) in [(c, S * .33), (c - S * .17, S * .57), (c + S * .17, S * .57)]:
        d.ellipse([x - S * .055, y - S * .055, x + S * .055, y + S * .055], fill=(40, 120, 50, 255),
                  outline=(10, 10, 10, 255), width=3)
    d.ellipse([c - S * .045, S * .66, c + S * .045, S * .75], fill=(140, 30, 25, 255))
    glare(d, S)


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_flares(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 18, 18, 255))
    for k in range(2):
        x = S * (0.08 + 0.46 * k)
        d.rectangle([x, H * .2, x + S * .38, H * .8], fill=(6, 6, 6, 255), outline=(80, 80, 80, 255), width=3)
        text(d, (x + S * .19, H * .5), '30', H * .5, fill=(230, 230, 220))


def p_afcs(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 22, 22, 255))
    labs = ['ДЕМПФ', 'СТАБ', 'ВЫСОТ', 'ПРИВ', 'ОТКЛ']
    for k, lab in enumerate(labs):
        w = S / len(labs)
        lamp(d, [k * w + 6, H * .14, (k + 1) * w - 6, H * .86], (210, 210, 200) if k < 4 else (200, 60, 50),
             lab, lit=(k == 0), px=H * .2)


def p_ramps(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 18, 18, 255))
    for k in range(2):
        cx = S * (0.25 + 0.5 * k); cy = H * .55; R = H * .38
        d.ellipse([cx - R, cy - R, cx + R, cy + R], fill=(10, 10, 10, 255), outline=(90, 90, 90, 255), width=4)
        for v in range(9):
            a = math.radians(-90 + v * 22.5)
            d.line([cx + R * .7 * math.sin(a), cy - R * .7 * math.cos(a), cx + R * .92 * math.sin(a),
                    cy - R * .92 * math.cos(a)], fill=WHITE + (255,), width=4)
        d.line([cx, cy, cx + R * .8 * math.sin(math.radians(-70)), cy - R * .8 * math.cos(math.radians(-70))],
               fill=WHITE + (255,), width=8)
        text(d, (cx, H * .1), 'Л' if k == 0 else 'П', H * .16)


def p_weapons(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 22, 22, 255))
    for k in range(6):
        x = S * (0.09 + k * 0.165)
        d.ellipse([x - 12, H * .58 - 12, x + 12, H * .58 + 12], fill=(140, 140, 140, 255))
        d.line([x, H * .58, x, H * .28], fill=(200, 200, 200, 255), width=8)
        text(d, (x, H * .86), ['ПУСК', 'ОГОНЬ', 'АВТ', 'РЕЖ', 'БОМБ', 'НРС'][k], H * .16)


def p_signals(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 22, 22, 255))
    rows = [('ФОРСАЖ', AMBER), ('ФОРСАЖ', AMBER), ('ОСТАТОК', RED), ('ПОЖАР', RED), ('ГЕНЕР', AMBER),
            ('ГИДРО', AMBER), ('ШАССИ', RED), ('ТОРМОЗ', AMBER), ('КИСЛОР', AMBER), ('ЗАКРЫЛ', GREEN_LAMP),
            ('ЩИТКИ', GREEN_LAMP), ('ЗАПУСК', GREEN_LAMP)]
    h = H / len(rows)
    for k, (lab, col) in enumerate(rows):
        lamp(d, [8, k * h + 5, S - 8, (k + 1) * h - 5], col, lab, lit=False, px=h * .3)


def p_ekran(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(24, 26, 26, 255))
    d.rectangle([S * .08, H * .18, S * .92, H * .62], fill=(10, 30, 16, 255), outline=(70, 70, 70, 255), width=4)
    text(d, (S * .5, H * .40), 'ПРОВЕРЬ ТОПЛИВО', H * .14, fill=(110, 220, 120))
    text(d, (S * .5, H * .82), 'ЭКРАН', H * .15)


def p_hud_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 4, seed=9)
    for k, lab in enumerate(['ФИЛЬТР', 'ЯРК', 'ДН/НЧ', 'КОНТР']):
        x = S * (0.13 + k * 0.245)
        if k == 1:
            d.ellipse([x - 22, H * .5 - 22, x + 22, H * .5 + 22], fill=(20, 20, 20, 255), outline=(110, 110, 110, 255),
                      width=3)
        elif k == 3:
            lamp(d, [x - 22, H * .3, x + 22, H * .7], (60, 170, 70), None)
        else:
            d.rectangle([x - 6, H * .25, x + 6, H * .65], fill=(170, 170, 170, 255))
        text(d, (x, H * .86), lab, H * .085)


def p_hud_lens(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(16, 22, 26, 255))
    d.ellipse([S * .1, H * .1, S * .9, H * .9], fill=(30, 46, 56, 255))
    d.ellipse([S * .25, H * .2, S * .5, H * .4], fill=(90, 120, 140, 255))


def p_console(side):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, TURQ, 5, seed=20 + side)
        rng = np.random.default_rng(40 + side)
        y = H * 0.02
        # black sub-panels stacked fore to aft (top of the tile = forward), each with switches and knobs
        heights = [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0 else \
                  [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08]
        for k, hgt in enumerate(heights):
            y1 = y + hgt * H
            d.rectangle([S * .06, y + 4, S * .94, y1 - 4], fill=(22, 24, 24, 255))
            n = int(rng.integers(2, 5))
            for j in range(n):
                x = S * (0.15 + 0.7 * (j + 0.5) / n); yy = (y + y1) / 2
                if rng.random() < 0.5:
                    d.ellipse([x - 14, yy - 14, x + 14, yy + 14], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255),
                              width=3)
                else:
                    d.rectangle([x - 5, yy - 18, x + 5, yy + 6], fill=(180, 180, 180, 255))
                    d.ellipse([x - 9, yy - 26, x + 9, yy - 10], fill=(200, 200, 200, 255))
            y = y1
        if side > 0:                      # throttle slot on the left console
            d.rectangle([S * .44, H * .30, S * .56, H * .62], fill=(10, 10, 10, 255))
    return fn


def p_wall(side):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, TURQ_D, 5, seed=30 + side)
        for k in range(3):
            x = S * (0.15 + k * 0.28)
            d.rectangle([x, H * .2, x + S * .18, H * .55], fill=(22, 24, 24, 255))
            for j in range(3):
                d.ellipse([x + S * .03 + j * S * .05, H * .3, x + S * .06 + j * S * .05, H * .38],
                          fill=(170, 170, 170, 255))
        d.line([0, H * .62, S, H * .62], fill=TURQ + (255,), width=6)
    return fn


def p_cushion(colour, straps=False):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 6, seed=50)
        for k in range(1, 4):
            d.line([0, H * k / 4, S, H * k / 4], fill=tuple(c - 18 for c in colour) + (255,), width=4)
        if straps:
            for x in (S * .3, S * .7):
                d.rectangle([x - S * .05, 0, x + S * .05, H], fill=(70, 74, 62, 255))
            d.rectangle([S * .38, H * .55, S * .62, H * .7], fill=(150, 150, 150, 255))
    return fn


def p_stripes(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=YELLOW + (255,))
    for k in range(-H, S, 24):
        d.polygon([(k, H), (k + 12, H), (k + 12 + H, 0), (k + H, 0)], fill=(20, 20, 20, 255))


def p_headbox(d, im):
    S, H = im.width, im.height
    noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, H * .1, S * .9, H * .9], outline=(60, 62, 58, 255), width=6)
    text(d, (S * .5, H * .5), 'К-36', H * .2, fill=(40, 40, 40), bold=True)


def p_hdd_bezel(d, im):
    S = im.width
    d.rectangle([0, 0, S, S], fill=(22, 22, 22, 255))
    for k in range(5):
        x = S * (0.15 + k * 0.175)
        d.rectangle([x - 10, S * .03, x + 10, S * .08], fill=(70, 70, 70, 255))
        d.rectangle([x - 10, S * .92, x + 10, S * .97], fill=(70, 70, 70, 255))


# ---------------------------------------------------------------- build

def build(atlas):
    m = Mesh()
    sw = {k: atlas.swatch(c) for k, c in dict(turq=TURQ, turq_d=TURQ_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                lgrey=LGREY, frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED,
                                                metal=METAL, floor=FLOOR, bezel=(30, 31, 32)).items()}
    eye = EYE
    gauges, displays, glass = [], [], []

    # -- main panel facets (front face painted, slab 2 cm deep)
    ppm = 600
    for f in (CENTRE, LEFT, RIGHT):
        a0, a1 = f.extent()
        y_top = max(f.top(a0), f.top(a1))
        W = int(round((a1 - a0) * ppm)); H = int(round((y_top - f.bottom()) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W, H, panel_paint(f, (W, H), ppm))
        # the face: a strip of quads following the (sloping) top edge, uv in the facet's own tile
        n = 6
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            yb = f.bottom()
            t_top0 = (f.top(aa0) - yb) / (y_top - yb); t_top1 = (f.top(aa1) - yb) / (y_top - yb)
            pts = [f.at(aa0, yb), f.at(aa1, yb), f.at(aa1, f.top(aa1)), f.at(aa0, f.top(aa0))]
            uvs = [uv_of(rect, s0, 0), uv_of(rect, s1, 0), uv_of(rect, s1, t_top1), uv_of(rect, s0, t_top0)]
            if f.side == -1:              # the left wing runs leftward: mirror its tile so it reads correctly
                uvs = [uv_of(rect, 1 - s0, 0), uv_of(rect, 1 - s1, 0), uv_of(rect, 1 - s1, t_top1),
                       uv_of(rect, 1 - s0, t_top0)]
            m.face(pts, uvs, normal=f.N)
        # back and edges
        back = -f.N * 0.02
        corners = [f.at(a0, f.bottom()), f.at(a1, f.bottom()), f.at(a1, f.top(a1)), f.at(a0, f.top(a0))]
        m.flat([c + back for c in corners], sw['turq_d'], normal=-f.N)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['turq_d'], toward=mid + (mid - np.mean(corners, 0)) + f.N * 0.0)
    # -- instruments
    for name, f, a, y, spec, label in LAYOUT:
        kind = spec[0]
        c = f.at(a, y)
        if kind == 'game':
            dia = spec[2]
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': dia, 'Depth': 0.03})
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 0, 'Center': r5(c + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
            m.frame_rect(c, f.N, f.U, w, w, 0.012, 0.006, sw['black'])
            # shading hood: top plate deep, sides tapering, a short lip below
            hw = w / 2 + 0.012
            _, n_, u_, r_ = frame(c, f.N, f.U)
            depth_top, depth_bot = 0.05, 0.015
            for s in (-1, 1):
                p0 = c + r_ * s * hw - u_ * hw; p1 = c + r_ * s * hw + u_ * hw
                q1 = p1 + n_ * depth_top; q0 = p0 + n_ * depth_bot
                for off in (0.0, 0.006):
                    sh = r_ * s * off
                    m.flat([p0 + sh, p1 + sh, q1 + sh, q0 + sh], sw['black'], normal=r_ * s * (1 if off else -1))
                m.flat([q0, q1, q1 + r_ * s * 0.006, q0 + r_ * s * 0.006], sw['black'], normal=n_)
            top0 = c - r_ * hw + u_ * hw; top1 = c + r_ * hw + u_ * hw
            m.box((top0 + top1) / 2 + n_ * depth_top / 2 + u_ * 0.003, (r_, u_, n_), (hw + 0.006, 0.003, depth_top / 2),
                  sw['black'])
            bot0 = c - r_ * hw - u_ * hw; bot1 = c + r_ * hw - u_ * hw
            m.box((bot0 + bot1) / 2 + n_ * depth_bot / 2 - u_ * 0.003, (r_, u_, n_), (hw + 0.006, 0.003, depth_bot / 2),
                  sw['black'])
        elif kind == 'paint_dial':
            dia = spec[1]
            painter = {'radalt': p_radalt, 'egt_l': p_egt, 'egt_r': p_egt, 'gear_ind': p_gear}[name]
            rect = TILE_CACHE.get(painter) or atlas.paint(128, 128, painter)
            TILE_CACHE[painter] = rect
            m.disc(c + f.N * GAUGE_LIFT, f.N, f.U, dia / 2, rect)
            m.ring(c, f.N, f.U, dia / 2, dia / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'], back=0.0)
        elif kind == 'paint_disc':
            dia = spec[1]
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, dia / 2, rect, seg=12)
            m.ring(c, f.N, f.U, dia / 2, dia / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'flares': p_flares, 'afcs': p_afcs, 'ramps': p_ramps, 'weapons': p_weapons,
                       'signals': p_signals, 'ekran': p_ekran}[name]
            pw = 128 if w >= h else 48
            ph = max(16, int(round(pw * h / w)))
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])

    # -- coaming (glareshield): lofted from the panel lip forward to the windscreen base
    lip = []
    for f, aa in ((LEFT, np.linspace(WING_LEN, 0, 4)), (CENTRE, np.linspace(-HINGE, HINGE, 7)),
                  (RIGHT, np.linspace(0, WING_LEN, 4))):
        a_seq = aa if f is not CENTRE else (aa if True else aa)
        for a in a_seq:
            p = f.at(a, f.top(a))
            lip.append((p, f))
    # order the lip from the pilot's left (+x) to right (-x) and drop duplicates at the hinges
    lip.sort(key=lambda q: -q[0][0])
    pts = []
    for p, f in lip:
        if pts and np.linalg.norm(pts[-1][0] - p) < 1e-6:
            continue
        pts.append((p, f))
    lip_edge = [p + f.N * 0.045 + np.array([0, 0.03, 0]) for p, f in pts]
    panel_top = [p for p, f in pts]
    # front line under the windscreen base, and a crown line between
    front_z, front_y, front_x = 4.40, 3.045, 0.40
    rows = []
    for t in (0.0, 0.35, 0.7, 1.0):
        row = []
        for le in lip_edge:
            s = le[0] / max(abs(lip_edge[0][0]), 1e-6)                 # -1..1 across
            xf = s * front_x
            yf = front_y - 0.05 * s * s
            z = le[2] + (front_z - le[2]) * t
            x = le[0] + (xf - le[0]) * t
            crown = 0.04 * math.sin(math.pi * t) * (1 - s * s)          # domed between lip and front
            y = le[1] + (yf - le[1]) * t + crown
            row.append(np.array([x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], sw['black'],
                   normal=[0, 1, 0])
    # lip: front strip and underside back to the panel top
    for j in range(len(lip_edge) - 1):
        a, b = lip_edge[j], lip_edge[j + 1]
        da, db = a - [0, 0.012, 0], b - [0, 0.012, 0]
        m.flat([a, b, db, da], sw['black'], toward=eye)
        m.flat([da, db, panel_top[j + 1], panel_top[j]], sw['black'], normal=[0, -1, 0])
    # the side ends of the coaming down to the sill
    for j in (0, len(lip_edge) - 1):
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            m.flat([p, q, [q[0], 2.95, q[2]], [p[0], 2.95, p[2]]], sw['black'], toward=[0, 2.95, (p[2] + q[2]) / 2])

    # -- ILS-31 HUD: body under the coaming, aft control strip, lens, combiner frame and glass
    hud_z0, hud_z1, hud_y0, hud_y1, hud_hw = 3.83, 4.12, 3.20, 3.30, 0.08
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    ctrl = atlas.paint(128, 80, p_hud_ctrl)
    lens = atlas.paint(64, 64, p_hud_lens)
    m.box([0, (hud_y0 + hud_y1) / 2, (hud_z0 + hud_z1) / 2], (X, Y, Z),
          (hud_hw, (hud_y1 - hud_y0) / 2, (hud_z1 - hud_z0) / 2), sw['dgrey'],
          front=(2, -1, ctrl, -X, Y))
    m.rect_tex([0, hud_y1 + 0.001, 3.97], [0, 1, 0], [0, 0, 1], 0.10, 0.10, lens)
    lean = math.radians(22)                     # combiner top leans toward the pilot
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h = 0.165; g_hw = 0.074
    # the glass is centred on the pilot's straight-ahead line (the eye's height), so the projected picture's
    # boresight sits in the middle of it
    g_bot = np.array([0, EYE[1] - g_up[1] * g_h / 2, 3.85])
    g_top = g_bot + g_up * g_h
    # stem from the body top, bottom clamp bar and the two side posts
    stem = g_bot[1] - 0.006 - hud_y1
    if stem > 0.004:
        m.box([0, hud_y1 + stem / 2, g_bot[2]], (X, Y, Z), (g_hw + 0.008, stem / 2, 0.012), sw['dgrey'])
    m.box(g_bot - g_up * 0.006, (X, g_up, g_n), (g_hw + 0.008, 0.006, 0.012), sw['dgrey'])
    for s in (-1, 1):
        m.box((g_bot + g_top) / 2 + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    corners = [g_bot - X * g_hw, g_bot + X * g_hw, g_top + X * g_hw, g_top - X * g_hw]
    glass += [corners[0], corners[1], corners[2], corners[0], corners[2], corners[3]]
    # the picture the game projects onto the combiner (HeadUpDisplay): the glass face, normal toward the pilot
    hud = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)

    # -- side walls, consoles, floor, bulkheads, footwell
    for side in (1, -1):                         # +1 = left (x > 0)
        xw = WALL_X * side
        wall = atlas.paint(180, 100, p_wall(side))
        m.quad_tex([[xw, CONSOLE_Y - 0.02, 2.45], [xw, CONSOLE_Y - 0.02, 3.95], [xw, 3.13, 3.95], [xw, 3.13, 2.45]]
                   if side > 0 else
                   [[xw, CONSOLE_Y - 0.02, 3.95], [xw, CONSOLE_Y - 0.02, 2.45], [xw, 3.13, 2.45], [xw, 3.13, 3.95]],
                   wall, normal=[-side, 0, 0])
        # console: top follows the wing bottom edge at its front end
        wing = LEFT if side > 0 else RIGHT
        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(a, BOT_W)[2]
        xi, xo = 0.25 * side, WALL_X * side
        zf_i, zf_o = wing_bottom_z(0.25), wing_bottom_z(WALL_X)
        top = [[xi, CONSOLE_Y, 2.55], [xo, CONSOLE_Y, 2.55], [xo, CONSOLE_Y, zf_o], [xi, CONSOLE_Y, zf_i]]
        con = atlas.paint(56, 300, p_console(side))
        # tile: s across (inner -> outer), t from aft (0) to forward (1)
        m.face(top, [uv_of(con, 0, 0), uv_of(con, 1, 0), uv_of(con, 1, 1), uv_of(con, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, FLOOR_Y, 2.55], [xi, FLOOR_Y, zf_i], [xi, CONSOLE_Y, zf_i], [xi, CONSOLE_Y, 2.55]], sw['turq_d'],
               normal=[-side, 0, 0])
        m.flat([[xi, FLOOR_Y, 2.55], [xo, FLOOR_Y, 2.55], [xo, CONSOLE_Y, 2.55], [xi, CONSOLE_Y, 2.55]], sw['turq_d'],
               normal=[0, 0, -1])
        m.flat([[xi, FLOOR_Y, zf_i], [xo, FLOOR_Y, zf_o], [xo, CONSOLE_Y, zf_o], [xi, CONSOLE_Y, zf_i]], sw['turq_d'],
               normal=[0, 0, 1])
        # wall strip beside the seat, behind the console, down to the floor
        m.flat([[xw, FLOOR_Y, 2.45], [xw, FLOOR_Y, 2.55], [xw, CONSOLE_Y, 2.55], [xw, CONSOLE_Y, 2.45]], sw['turq_d'],
               normal=[-side, 0, 0])
        # sill cap (canopy rail ledge) along the wall top
        m.box([side * (WALL_X - 0.015), 3.14, 3.20], (X, Y, Z), (0.03, 0.012, 0.76), sw['dgrey'])
    m.flat([[-0.25, FLOOR_Y, 2.45], [0.25, FLOOR_Y, 2.45], [0.25, FLOOR_Y, 4.02], [-0.25, FLOOR_Y, 4.02]], sw['floor'],
           normal=[0, 1, 0])
    m.flat([[-WALL_X, FLOOR_Y, 2.45], [WALL_X, FLOOR_Y, 2.45], [WALL_X, 3.16, 2.45], [-WALL_X, 3.16, 2.45]],
           sw['turq_d'], normal=[0, 0, 1])
    # under the panel: the forward bulkhead of the footwell and the panel's lower closing face
    m.flat([[-0.25, FLOOR_Y, 4.02], [0.25, FLOOR_Y, 4.02], [0.25, BOT_C, 4.02], [-0.25, BOT_C, 4.02]], sw['floor'],
           normal=[0, 0, -1])
    bl, br = CENTRE.at(HINGE, BOT_C), CENTRE.at(-HINGE, BOT_C)
    m.flat([bl, br, [br[0], BOT_C, 4.02], [bl[0], BOT_C, 4.02]], sw['turq_d'], normal=[0, -1, 0])
    for s in (1, -1):                            # footwell sides from the centre facet's edge to the console
        e = CENTRE.at(-HINGE * s, BOT_C)
        m.flat([[s * 0.25, FLOOR_Y, 3.70], [s * 0.25, FLOOR_Y, 4.02], [s * 0.25, BOT_C, 4.02], [s * 0.25, BOT_C, 3.70]],
               sw['turq_d'], normal=[-s, 0, 0])
        m.flat([e, CENTRE.at(-HINGE * s, BOT_W), [s * 0.25, BOT_W, e[2]], [s * 0.25, BOT_C, e[2]]], sw['turq_d'],
               normal=[0, 0, -1])
    # rudder pedals
    for s in (1, -1):
        m.box([s * 0.12, 2.42, 3.76], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box([s * 0.12, 2.36, 3.82], (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- throttle (RUD) on the left console
    m.box([0.345, CONSOLE_Y + 0.055, 3.27], (X, unit([0, 1, 0.25]), unit([0, -0.25, 1])), (0.009, 0.06, 0.009),
          sw['metal'])
    grip_ax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
    m.box([0.345, CONSOLE_Y + 0.135, 3.29], grip_ax, (0.03, 0.045, 0.04), sw['black'])
    m.box([0.345, CONSOLE_Y + 0.185, 3.30], grip_ax, (0.026, 0.01, 0.032), sw['dgrey'])
    m.box([0.322, CONSOLE_Y + 0.15, 3.315], grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- K-36DM ejection seat
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    back = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    seat_back_ax = (X, unit([0, 1, -0.14]), unit([0, 0.14, 1]))          # 8 degrees reclined
    m.box([0, 2.39, 3.03], (X, Y, Z), (0.20, 0.09, 0.22), sw['frame'])                       # survival kit / base
    m.box([0, 2.515, 3.06], (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'],
          front=(1, 1, cushion, -X, Z))                                                      # seat pan cushion
    for s in (1, -1):
        m.box([s * 0.212, 2.52, 3.02], (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])          # bucket sides
        m.box([s * 0.17, 3.0, 2.63], seat_back_ax, (0.02, 0.62, 0.025), sw['frame'])         # guide beams
    m.box([0, 2.90, 2.80], seat_back_ax, (0.19, 0.33, 0.03), sw['cushion'],
          front=(2, 1, back, X, seat_back_ax[1]))                                           # back cushion
    m.box([0, 2.90, 2.74], seat_back_ax, (0.205, 0.36, 0.035), sw['frame'])                  # back shell
    m.box([0, 3.41, 2.72], (X, Y, Z), (0.16, 0.20, 0.10), sw['frame'])                       # headbox
    m.box([0, 3.42, 2.835], (X, Y, Z), (0.11, 0.11, 0.015), sw['cushion'],
          front=(2, 1, headbox, X, Y))                                                       # seen from ahead                                                      # headrest pad
    # ejection handles between the knees (yellow and black)
    for s in (1, -1):
        m.box([s * 0.05, 2.585, 3.29], (X, Y, Z), (0.008, 0.035, 0.008), sw['yellow'])
    m.box([0, 2.62, 3.29], (X, Y, Z), (0.058, 0.008, 0.008), sw['yellow'], front=(2, -1, stripes, -X, Y))

    # -- control stick (RUS): column, grip leaning forward, trigger, red button, wheel-brake lever; the rubber boot
    # stays with the cockpit, the rest goes into the animated stick bone
    m.box([0, 2.40, 3.47], (X, Y, Z), (0.045, 0.10, 0.045), sw['dgrey'])
    stick = Mesh()
    col_ax = (X, unit([0, 1, -0.07]), unit([0, 0.07, 1]))
    stick.box([0, 2.69, 3.468], col_ax, (0.011, 0.215, 0.011), sw['metal'])
    grip_ax = (X, unit([0, 1, 0.21]), unit([0, -0.21, 1]))
    gc = np.array([0, 2.955, 3.462])
    stick.box(gc, grip_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + grip_ax[1] * 0.066, grip_ax, (0.017, 0.006, 0.022), sw['dgrey'])                 # top cap
    stick.box(gc + grip_ax[1] * 0.07 + grip_ax[2] * 0.010, grip_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + grip_ax[2] * 0.031 + grip_ax[1] * 0.015, grip_ax, (0.008, 0.012, 0.005), sw['metal'])  # trigger
    stick.box(gc - grip_ax[2] * 0.034 + grip_ax[1] * 0.01, grip_ax, (0.012, 0.035, 0.004), sw['metal'])  # brake
    return m, gauges, displays, glass, stick, hud


TILE_CACHE = {}
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'


def r5(v):
    return [round(float(x), 5) + 0.0 for x in v]


# ---------------------------------------------------------------- removing the old interior

def old_interior(geo):
    """{bone: set(poly index)} of the old cockpit interior: faces inside the cockpit tub below the sill, the old HUD
    shelf, and the small loose parts (seat, panel, HUD box) of the cockpit section."""
    out = {}
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] != SOURCE_BONE:
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        polys = pm['polys']
        cent = np.array([P[[v[0] for v in p]].mean(0) for p in polys])
        N = np.array(pm['normals'], float) * np.array([1, 1, -1])
        skin = {i for i, (c, p) in enumerate(zip(cent, polys))      # outer skin along the sill: keep
                if abs(c[0]) > 0.40 and N[p[0][1]][0] * np.sign(c[0]) > 0.3
                or c[2] > 4.25 and c[1] > 2.85}                  # and the old deck under the windscreen base
        sel = set()
        for i, c in enumerate(cent):
            if i in skin:
                continue
            if abs(c[0]) < 0.445 and c[1] < 3.13 and 2.15 < c[2] < 4.45:
                sel.add(i)
            elif abs(c[0]) < 0.2 and 3.13 <= c[1] < 3.30 and 3.55 < c[2] < 4.05:
                sel.add(i)                   # old HUD shelf
            elif 0.09 < abs(c[0]) < 0.385 and 3.13 <= c[1] < 3.27 and 3.55 < c[2] < 4.0:
                sel.add(i)                   # old glareshield wings (the windscreen arch legs sit further out)
        # small connected parts inside the canopy: the old seat, panel and HUD
        parent = {}
        def find(x):
            while parent.setdefault(x, x) != x:
                parent[x] = parent[parent[x]]; x = parent[x]
            return x
        key = lambda i: tuple(np.round(P[i], 4))
        box = [i for i, c in enumerate(cent) if abs(c[0]) < 1.0 and 1.75 < c[1] < 4.3 and 1.7 < c[2] < 5.5]
        for i in box:
            ks = [key(v[0]) for v in polys[i]]
            for k in ks[1:]:
                parent[find(ks[0])] = find(k)
        comps = {}
        for i in box:
            comps.setdefault(find(key(polys[i][0][0])), []).append(i)
        for ids in comps.values():
            V = np.array([P[v[0]] for i in ids for v in polys[i]])
            lo, hi = V.min(0), V.max(0)
            if len(ids) <= 120 and np.all(np.abs(V[:, 0]) < 0.30) and lo[2] > 2.25 and hi[2] < 4.05 and \
                    hi[1] < 3.70 and hi[2] - lo[2] < 1.0:
                sel.update(ids)
        if sel:
            out[b['name']] = sel
    return out


# ---------------------------------------------------------------- main

def main(argv):
    global EYE
    dry = '--dry-run' in argv
    geo = json.load(open(GEO))
    asset = json.load(open(ASSET))
    data = json.load(open(DATA))
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
    atlas = Atlas()
    mesh, gauges, displays, glass, stick, hud = build(atlas)
    pm = mesh.poly_mesh()
    stick_bone = next(b for b in g['bones'] if b['name'] == STICK_BONE)
    stick_bone['poly_mesh'] = stick.poly_mesh()
    hull = next(b for b in g['bones'] if b['name'] == 'hull')
    bone = {'name': BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    bones = [b for b in g['bones'] if b['name'] != BONE]
    at = next(i for i, b in enumerate(bones) if b['name'] == SOURCE_BONE) + 1
    bones.insert(at, bone)
    g['bones'] = bones
    asset['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    asset['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    asset['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(EYE)}
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        c = t.mean(1)
        keep = ~((np.abs(c[:, 0]) < 0.1) & (c[:, 1] > 3.25) & (c[:, 1] < 3.48) & (c[:, 2] > 3.7) & (c[:, 2] < 3.9))
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]      # both windings
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    print(f'mig_29: {removed} old cockpit faces removed; new cockpit {len(pm["polys"])} faces, '
          f'{len(gauges)} game gauges, {len(displays)} display(s), HUD combiner glass {len(glass) // 3} triangles; '
          f'atlas {atlas.used()} of {sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return geo, asset, atlas
    with open(GEO, 'w') as f:
        json.dump(geo, f, separators=(',', ':'))
    # the asset keeps its layout: indented, with the canopy glass triangle list on one line at the end
    glass_json = asset.pop('CanopyGlass', None)
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        asset['CanopyGlass'] = glass_json
    with open(ASSET, 'w') as f:
        f.write(text + '\n')
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)
    return geo, asset, atlas


if __name__ == '__main__':
    main(sys.argv[1:])
