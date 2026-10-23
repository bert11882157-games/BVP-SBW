#!/usr/bin/env python3
"""Mil Mi-24P cockpits, built by hand for the Mi-24P.mtb airframe (one standalone script, as ah_56a.py and ah1w_super_cobra.py):
two crew in tandem, the OPERATOR in the nose (front, low), the PILOT behind him on a raised floor, as on the real Hind.

Measured off the model (checks/orig/mi_24p.geo.json; vehicle blocks, 1 block = 0.87 m, x to the left, y up, z forward): the operator's floor top
stands at y 1.58 and the pilot's at 1.90 (the model's own interior is cut out by canopy_mi_24p.py with the old frames); the cockpit's inner walls
are the skin's rim at |x| 0.54 .. 0.62; the cockpit hump between the crews stands at z 6.30 .. 6.64. The eyes: operator (0, 2.5004, 7.0532), pilot
(0, 3.2342, 5.2595) (the airframe's data, unchanged). The canopies are canopy_mi_24p.py's; this script builds, into the bone hull__cockpit:

OPERATOR (front, Seat 1; fires the 9M114 Shturm):
  * the KPS-53AV / Raduga-Sh sight unit on the coaming: a boxy body, a periscope eyepiece rising to a rubber cup at his eye line with the
    reticle window (a live reflector-style reticle for seat 1 only: HeadUpDisplays, Style REFLECTOR), the sight control strip on top;
  * the Shturm hand controller (a pistol grip with trigger and thumb switch) on the right console, the Raduga-Sh control box on the left one;
  * the panel under the sight: airspeed, altimeter, compass and vertical speed repeaters (live), the rounds counter, a STORES page, the
    weapons panel and the lamp strip (painted placards, Cyrillic), a short cyclic between the knees, the pedals, the armoured seat.
PILOT (rear, Seat 0; fires the GSh-30K, the rockets and the pods):
  * the instrument panel on the hump's rear face: KPP attitude and NPP horizontal situation (live), airspeed, altimeter, vertical speed,
    turn and slip, radio altimeter, clock, the rotor tachometer and the TV3-117 engine dials (N1 and gas temperature, left and right, torque),
    fuel, rounds, the caution strip; the weapons panel and the engine / rotor brake panel on the consoles (painted);
  * the ASP-17V gyro gunsight on the coaming: a head with its control strip, a combiner glass on struts centred on his eye line
    (lone HeadUpDisplay, Seat 0, Style GYRO: the pack's gunsight painter supports the gyro sight's fixed cross and pipper);
  * the armoured seat, the cyclic between the knees, the collective on the left console, rudder pedals.

Live gauges only through CockpitGauges / FlightDisplays / HeadUpDisplay(s); everything else is a painted face. The sticks are fixed. Placards
are Russian. Imports only cockpit_lib and free_atlas (the building blocks are inlined). Run after canopy_mi_24p.py (mi24p/run_mi24p_cockpit.ps1).
Usage: python tools/cockpit_models/mi_24p.py [--dry-run]"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
THR = []                                 # the throttle levers (throttle_lib), one bone each

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import free_atlas as FA   # noqa: E402
import canopy_mi_24p as CM   # noqa: E402
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

VID = 'mi_24p'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
BONE = 'hull__cockpit'
HULL_BONE = 'hull'

EYE_P = CM.EYE_P
EYE_G = CM.EYE_G

# ---------------------------------------------------------------- building blocks (inlined: this script uses only cockpit_lib and free_atlas)
RIM = 0.16                     # game gauge bezel width (CockpitGauges.RIM)
GAUGE_LIFT = 0.004             # game dials stand this far proud of the panel face
DISPLAY_LIFT = 0.003
PAINT_LIFT = 0.0055
WHITE = (226, 228, 220)
X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def frame(centre, normal, up):
    """(c, n, u, r): r = the right-hand direction seen from in front of the face (looking along -n)"""
    n = unit(normal)
    u = np.array(up, float); u = u - n * (u @ n); u /= np.linalg.norm(u)
    return np.array(centre, float), n, u, np.cross(u, n)


def half_along(A_, half, d):
    return sum(abs(float(a @ d)) * h for a, h in zip(A_, half))


def r5(v):
    return [round(float(x), 5) + 0.0 for x in v]


class Mesh:
    """faces (points k x 3 vehicle-local, uvs k x 2, normal); uvs come from the atlas"""

    def __init__(self, atlas):
        self.atlas = atlas
        self.faces = []

    def uv(self, rect, s, t):
        return self.atlas.uv(rect, s, t)

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
        want = np.array(normal, float) if normal is not None else \
            (np.array(toward, float) - P.mean(0) if toward is not None else None)
        uvs = [list(u) for u in uvs]
        if want is not None and n @ want < 0:
            P = P[::-1]; uvs = uvs[::-1]; n = -n
        if len(P) > 4:
            for k in range(1, len(P) - 1):
                self.faces.append((P[[0, k, k + 1]], [uvs[0], uvs[k], uvs[k + 1]], n))
        else:
            self.faces.append((P, uvs, n))

    def quad_tex(self, corners, rect, toward=None, normal=None):
        """corners: bottom-left, bottom-right, top-right, top-left as seen from the front"""
        self.face(corners, [self.uv(rect, 0, 0), self.uv(rect, 1, 0), self.uv(rect, 1, 1), self.uv(rect, 0, 1)],
                  toward=toward, normal=normal)

    def flat(self, pts, rect, toward=None, normal=None):
        u = self.uv(rect, 0.5, 0.5)
        self.face(pts, [u] * len(pts), toward=toward, normal=normal)

    def box(self, centre, axes, half, rect, front=None):
        """oriented box; front = (axis index, sign, rect, right, up): that face textured, upright"""
        c = np.array(centre, float); A_ = [unit(a) for a in axes]
        for k in range(3):
            for s in (-1, 1):
                i, j = [q for q in range(3) if q != k]
                fc = c + A_[k] * half[k] * s
                if front and front[0] == k and front[1] == s:
                    _, _, r, right, up = front
                    R = unit(right); U = unit(up)
                    hr, hu = half_along(A_, half, R), half_along(A_, half, U)
                    self.quad_tex([fc - R * hr - U * hu, fc + R * hr - U * hu, fc + R * hr + U * hu, fc - R * hr + U * hu],
                                  r, normal=A_[k] * s)
                else:
                    self.flat([fc - A_[i] * half[i] - A_[j] * half[j], fc + A_[i] * half[i] - A_[j] * half[j],
                               fc + A_[i] * half[i] + A_[j] * half[j], fc - A_[i] * half[i] + A_[j] * half[j]],
                              rect, normal=A_[k] * s)

    def solid(self, faces, rect):
        """a convex solid from planar face loops, every face turned to look away from the solid's centre"""
        allp = np.concatenate([np.array(f, float) for f in faces])
        c = allp.mean(0)
        for f in faces:
            self.flat(f, rect, toward=np.array(f, float).mean(0) * 2 - c)

    def yz_prism(self, prof, x0, x1, rect):
        """a convex prism: profile points (y, z) in order, extruded from x0 to x1"""
        L_ = [[x0, y, z] for y, z in prof]
        R_ = [[x1, y, z] for y, z in prof]
        faces = [L_, R_] + [[L_[i], L_[(i + 1) % len(L_)], R_[(i + 1) % len(L_)], R_[i]] for i in range(len(L_))]
        self.solid(faces, rect)

    def cyl(self, p0, p1, r, rect, seg=8):
        p0, p1 = np.array(p0, float), np.array(p1, float)
        t = unit(p1 - p0)
        a = Y if abs(t @ Y) < 0.9 else X
        u = unit(np.cross(t, a)); v = unit(np.cross(t, u))
        ring = lambda p: [p + r * (u * math.cos(2 * math.pi * (j + .5) / seg) + v * math.sin(2 * math.pi * (j + .5) / seg))
                          for j in range(seg)]
        A_, B_ = ring(p0), ring(p1)
        for j in range(seg):
            j2 = (j + 1) % seg
            q = [A_[j], A_[j2], B_[j2], B_[j]]
            self.flat(q, rect, toward=np.mean(q, 0) * 2 - (p0 + p1) / 2)
        self.flat(A_, rect, normal=-t)
        self.flat(B_, rect, normal=t)

    def ring(self, centre, normal, up, r0, r1, proud, rect, seg=20):
        """flat annulus (bezel) standing [proud] in front of the plane, with its walls back to the plane"""
        c, n, u, rr = frame(centre, normal, up)
        for k in range(seg):
            a0 = 2 * math.pi * k / seg; a1 = 2 * math.pi * (k + 1) / seg
            d0 = rr * math.cos(a0) + u * math.sin(a0); d1 = rr * math.cos(a1) + u * math.sin(a1)
            f = c + n * proud
            self.flat([f + d0 * r0, f + d0 * r1, f + d1 * r1, f + d1 * r0], rect, normal=n)
            self.flat([f + d0 * r1, c + d0 * r1, c + d1 * r1, f + d1 * r1], rect, normal=(d0 + d1) / 2)
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
            self.box(c + rr * cx + u * cy + n * (proud / 2 + 0.0012), (rr, u, n), (hw, hh, proud / 2), rect)     # (stands 1.2 mm off the panel: its back face would lie in the panel's plane)

    def poly_mesh(self):
        P, N, U, polys = [], [], [], []
        for pts, uvs, n in self.faces:
            px = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pts]
            npx = np.array([n[0], n[1], -n[2]])
            a, b, c = (np.array(q) for q in px[:3])
            # the mesh loader mirrors X: in file pixels a face winds clockwise seen from its front
            if np.cross(b - a, c - a) @ npx > 0:
                px = px[::-1]; uvs = uvs[::-1]
            ni = len(N); N.append([round(float(x), 5) + 0.0 for x in -npx])
            poly = []
            for p, uv in zip(px, uvs):
                poly.append([len(P), ni, len(U)])
                P.append([round(x, 4) + 0.0 for x in p]); U.append([round(uv[0], 6), round(uv[1], 6)])
            polys.append(poly)
        return {'normalized_uvs': True, 'positions': P, 'normals': N, 'uvs': U, 'polys': polys}


class Atlas:
    """Tiles painted at SS x and downsampled, shelf-packed into the vehicle texture's free rectangles."""
    SS = 4

    def __init__(self, vid, min_area=4096):
        (self.TW, self.TH), rects = FA.free_rects(vid, min_area)
        self.vid = vid
        self.areas = [tuple(int(v) for v in r) for r in rects]
        self.free = list(self.areas)
        self.tiles = []

    def alloc(self, w, h):
        best = None
        for i, (x, y, fw, fh) in enumerate(self.free):
            if w + 2 <= fw and h + 2 <= fh:
                score = min(fw - w, fh - h)
                if best is None or score < best[0]:
                    best = (score, i)
        if best is None:
            raise SystemExit(f'{self.vid}: texture atlas full ({w}x{h})')
        x, y, fw, fh = self.free.pop(best[1])
        w2, h2 = w + 2, h + 2                      # a texel of padding all round
        if fw - w2 < fh - h2:
            self.free += [(x + w2, y, fw - w2, h2), (x, y + h2, fw, fh - h2)]
        else:
            self.free += [(x + w2, y, fw - w2, fh), (x, y + h2, w2, fh - h2)]
        self.free = [r for r in self.free if r[2] > 0 and r[3] > 0]
        return (x + 1, y + 1, w, h)

    def paint(self, w, h, fn, bg=(0, 0, 0, 255)):
        w, h = int(max(4, w)), int(max(4, h))
        r = self.alloc(w, h)
        img = Image.new('RGBA', (w * self.SS, h * self.SS), bg)
        fn(ImageDraw.Draw(img), img)
        self.tiles.append((r, img.resize((w, h), Image.LANCZOS)))
        return r

    def swatch(self, colour):
        return self.paint(4, 4, lambda d, im: None, bg=tuple(colour) + (255,))

    def uv(self, rect, s, t):
        """normalized uv of (s, t) in a tile: s to the right, t up, 0..1"""
        x, y, w, h = rect
        s = 0.5 / w + s * (1 - 1.0 / w); t = 0.5 / h + t * (1 - 1.0 / h)
        return [(x + s * w) / self.TW, 1.0 - (y + (1 - t) * h) / self.TH]

    def used(self):
        return sum(r[2] * r[3] for r, _ in self.tiles)

    def write(self, path, dead=False):
        base = Image.open(path).convert('RGBA')
        if base.size != (self.TW, self.TH):
            raise SystemExit(f'{path}: texture size {base.size} is not {(self.TW, self.TH)}')
        for (rx, ry, rw, rh), img in self.tiles:
            if dead:
                a = np.asarray(img).astype(np.float32)
                g = a[..., :3].mean(-1, keepdims=True)
                a[..., :3] = (a[..., :3] * 0.25 + g * 0.15) * 0.9
                img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
            base.paste(img, (rx, ry))
        base.save(path, optimize=True)


# ---------------------------------------------------------------- colours (a Soviet blue-green cockpit: dark green-grey panels, black dials)
PANEL = (26, 124, 116)                     # the Hind's cockpit is painted turquoise (blue-green); the instrument faces are black
PANEL_D = (16, 86, 82)
BLACK = (18, 19, 21)
DGREY = (36, 40, 40)
MGREY = (84, 92, 90)
LGREY = (150, 158, 156)
FRAME = (40, 48, 44)
CUSHION = (70, 76, 58)
HARNESS = (96, 98, 76)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 134, 134)
FLOOR = (30, 38, 36)                       # (the deck is dark metal)
CONSOLE = (30, 134, 126)
AMBER = (214, 140, 30)
RUBBER = (14, 14, 15)
ARMOUR = (52, 60, 52)


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
    a[..., :3] += np.kron(n, np.ones((8, 8)))[:im.height, :im.width][..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)), (0, 0))


def text(d, xy, s, px, fill=WHITE, anchor='mm', bold=False):
    d.text(xy, s, font=font(px, bold), fill=tuple(fill), anchor=anchor)


def toggle(d, x, y, S, big=False):
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


# ---------------------------------------------------------------- panel facets and instrument layouts
SLOPE = 0.1049                             # the panel faces lean back 6 degrees


class Facet:
    """a panel face: the plane z = z0 + SLOPE (y - y0); a = metres to the crew member's right of the centre line, y = height"""

    def __init__(self, z0, y0, aft=True):
        self.z0, self.y0 = z0, y0
        self.U = unit([0, 1, SLOPE])
        self.N = unit([0, SLOPE, -1])          # looking back at the crew, a little up

    def z(self, y):
        return self.z0 + SLOPE * (y - self.y0)

    def at(self, a, y, lift=0.0):
        return np.array([-a, y, self.z(y)]) + self.N * lift


PF = Facet(6.280, 2.400)                   # the pilot's instrument panel face (the hump's rear face)
GF = Facet(7.440, 1.900)                   # the operator's panel face
PANEL_TOP_P, PANEL_BOT_P = 2.720, 2.120
PANEL_TOP_G, PANEL_BOT_G = 2.120, 1.640
HALF_P, HALF_G = 0.55, 0.27
# the pilot's panel follows the canopy's narrowing: a wide lower band (the breaker banks, radio, caution grid) under the upper band of
# flight and engine instruments: (bottom y, top y, half width)
PILOT_BANDS = [(2.120, 2.420, 0.55), (2.420, 2.720, 0.43)]

# (name, a, y, spec, caption) spec: ('game', KIND, diameter) | ('paint_disc', KIND, diameter) | ('annunciator', w, h) |
# ('paint_rect', w, h) | ('display', KIND, width, style)
PILOT_LAYOUT = [
    # upper band: the flight instruments, then two rows of engine and rotor dials
    ('ias',   -0.325, 2.640, ('game', 'AIRSPEED', 0.085), 'УС'),
    ('alt',   -0.215, 2.640, ('game', 'ALTIMETER', 0.085), 'ВД'),
    ('adi',   -0.085, 2.640, ('game', 'ATTITUDE', 0.115), 'КПП'),
    ('hsi',    0.085, 2.640, ('game', 'HEADING', 0.115), 'НПП'),
    ('vsi',    0.215, 2.640, ('game', 'VVI', 0.085), 'ВАР'),
    ('turn',   0.325, 2.640, ('game', 'TURN_SLIP', 0.075), 'ЭУП'),
    ('nr',    -0.350, 2.505, ('game', 'ROTOR_RPM', 0.070), 'НВ'),
    ('n1l',   -0.250, 2.505, ('game', 'ENGINE_RPM', 0.070), 'ЛЕВ'),
    ('n1r',   -0.150, 2.505, ('game', 'ENGINE_RPM', 0.070), 'ПРАВ'),
    ('tgl',   -0.050, 2.505, ('game', 'EGT', 0.070), 'T ЛЕВ'),
    ('tgr',    0.050, 2.505, ('game', 'EGT', 0.070), 'T ПРАВ'),
    ('tq',     0.150, 2.505, ('game', 'TORQUE', 0.070), 'МОМЕНТ'),
    ('fuel',   0.250, 2.505, ('game', 'FUEL_QTY', 0.070), 'ТОПЛ'),
    ('ralt',   0.350, 2.505, ('game', 'RADAR_ALT', 0.070), 'РВ'),
    ('d1',    -0.350, 2.447, ('paint_disc', 'OIL', 0.040), ''),
    ('d2',    -0.250, 2.447, ('paint_disc', 'XMSN', 0.040), ''),
    ('d3',    -0.150, 2.447, ('paint_disc', 'HYD', 0.040), ''),
    ('d4',    -0.050, 2.447, ('paint_disc', 'XMSNT', 0.040), ''),
    ('d5',     0.050, 2.447, ('paint_disc', 'OIL', 0.040), ''),
    ('d6',     0.150, 2.447, ('paint_disc', 'XMSN', 0.040), ''),
    ('d7',     0.250, 2.447, ('paint_disc', 'HYD', 0.040), ''),
    ('d8',     0.350, 2.447, ('paint_disc', 'XMSNT', 0.040), ''),
    # lower band: breaker banks either side, radio, the caution grid, clock and rounds, armament and engine panels
    ('cbl',   -0.410, 2.275, ('paint_rect', 0.260, 0.240), ''),
    ('rad',   -0.175, 2.325, ('paint_rect', 0.190, 0.115), ''),
    ('caution', -0.175, 2.195, ('annunciator', 0.200, 0.100), ''),
    ('clock',  0.000, 2.345, ('game', 'CLOCK', 0.060), ''),
    ('ammo',   0.000, 2.215, ('game', 'AMMO', 0.060), 'БК'),
    ('arm',    0.135, 2.305, ('paint_rect', 0.200, 0.120), ''),
    ('engp',   0.135, 2.185, ('paint_rect', 0.200, 0.075), ''),
    ('cbr',    0.410, 2.275, ('paint_rect', 0.260, 0.240), ''),
]
GUNNER_LAYOUT = [
    ('g_ias', -0.195, 2.030, ('game', 'AIRSPEED', 0.085), 'УС'),
    ('g_alt', -0.065, 2.030, ('game', 'ALTIMETER', 0.085), 'ВД'),
    ('g_hdg',  0.065, 2.030, ('game', 'HEADING', 0.085), 'ГМК'),
    ('g_vsi',  0.195, 2.030, ('game', 'VVI', 0.085), 'ВАР'),
    ('g_arm', -0.155, 1.835, ('paint_rect', 0.190, 0.155), ''),
    ('g_ammo', 0.000, 1.840, ('game', 'AMMO', 0.066), 'БК'),
    ('g_sto',  0.155, 1.840, ('display', 'STORES', 0.115, 'CRT'), ''),
    ('g_cau',  0.000, 1.690, ('annunciator', 0.340, 0.050), ''),
]

LAMPS = [{"Text": "ВНИМ|ОТКАЗ", "Condition": "MASTER_CAUTION", "Colour": "amber"},
         {"Text": "ОТКАЗ|ДВ", "Condition": "ENGINE_OUT", "Colour": "red"},
         {"Text": "ПОЖАР", "Condition": "FIRE", "Colour": "red"},
         {"Text": "ОСТ|ТОПЛ", "Condition": "LOW_FUEL", "Colour": "amber"},
         {"Text": "ОБ НВ|МАЛ", "Condition": "ROTOR_RPM_LOW", "Colour": "red"},
         {"Text": "ОБ НВ|ВЕЛ", "Condition": "ROTOR_RPM_HIGH", "Colour": "amber"},
         {"Text": "МОМ|ВЕЛ", "Condition": "TORQUE_HIGH", "Colour": "amber"},
         {"Text": "ДВ|РАБ", "Condition": "ENGINE_RUN", "Colour": "green"},
         {"Text": "МАСЛО|РЕД", "Condition": "NONE", "Colour": "amber"},
         {"Text": "ГИДР|ДАВЛ", "Condition": "NONE", "Colour": "amber"},
         {"Text": "ГЕН|ОТКАЗ", "Condition": "NONE", "Colour": "amber"},
         {"Text": "СПО|ОБЛУЧ", "Condition": "RWR_LOCK", "Colour": "amber"},
         {"Text": "ПУСК|ПТУР", "Condition": "MISSILE_LAUNCH", "Colour": "red"},
         {"Text": "МАЛО|БК", "Condition": "AMMO_LOW", "Colour": "amber"},
         {"Text": "РАЗРУШ", "Condition": "DAMAGE", "Colour": "red"},
         {"Text": "ОПАСН|ВЫС", "Condition": "LOW_ALT", "Colour": "amber"}]
LAMP_COLUMNS = 4
LAMPS_G = [LAMPS[0], LAMPS[2], LAMPS[3], LAMPS[12], LAMPS[13], LAMPS[14]]
LAMP_COLUMNS_G = 6


def panel_paint(layout, facet, top, bot, half, rect_px, seed):
    """the panel face: dark green-grey, screws, and the dark recess behind every instrument, with its Russian caption"""
    a0, a1 = -half, half
    W, H = rect_px
    SS = Atlas.SS
    cos_f = math.cos(math.atan(SLOPE))

    def to_px(a, y):
        s = (a - a0) / (a1 - a0)
        return (s * W * SS, (1 - (y - bot) / (top - bot)) * H * SS)

    def fn(d, im):
        noisy(d, im, PANEL, 3, seed=seed)
        for (a, y) in [(a0 + 0.012, bot + 0.012), (a1 - 0.012, bot + 0.012), (a0 + 0.012, top - 0.012), (a1 - 0.012, top - 0.012)]:
            x, yy = to_px(a, y); r = SS * 1.8
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(20, 20, 20, 255))
        for name, a, y, spec, cap in layout:
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(14, 17, 17, 255))
                if cap:
                    text(d, (x, yy - r - SS * 4.5), cap, SS * 5.2, fill=(190, 194, 188, 255))
            elif kind in ('paint_rect', 'display', 'annunciator'):
                if kind == 'display':
                    w = h = spec[2] + 0.060
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (top - bot) * H * SS * cos_f
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(14, 17, 17, 255))
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces and panels
def p_arm_pilot(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .08), 'ВООРУЖЕНИЕ', H * .11, bold=True)
    d.rectangle([S * .06, H * .18, S * .34, H * .42], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .20, H * .30), 'ПИТАНИЕ', H * .075, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['ПУШКА', 'НУР', 'ПТУР']):
        x = S * (0.50 + k * 0.20)
        d.rectangle([x - S * .085, H * .20, x + S * .085, H * .40], fill=(40, 130, 56, 255) if k == 1 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .30), lab, H * .075, fill=(10, 30, 10))
    for k, lab in enumerate(['1', '2', '3', '4']):
        x = S * (0.14 + 0.24 * k)
        d.rectangle([x - S * .08, H * .54, x + S * .08, H * .76], fill=(176, 176, 172, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x, H * .65), lab, H * .14, fill=(20, 20, 20))
    text(d, (S * .5, H * .88), 'ВЫБОР УЗЛА ПОДВЕСКИ', H * .07)


def p_arm_op(d, im):
    """the operator's weapons panel"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .07), 'ПУЛЬТ ОПЕРАТОРА', H * .075, bold=True)
    for k, lab in enumerate(['ПТУР', 'ПУСК', 'ГОТОВ', 'АВТО']):
        x = S * (0.15 + k * 0.23)
        d.rectangle([x - S * .09, H * .15, x + S * .09, H * .33], fill=(40, 130, 56, 255) if k == 0 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .24), lab, H * .075, fill=(10, 30, 10))
    d.rectangle([S * .06, H * .42, S * .34, H * .60], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .20, H * .51), 'ПИТАНИЕ', H * .07, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['АРРЕТИР', 'РАДУГА', 'ВЫКЛ']):
        x = S * (0.50 + k * 0.20)
        toggle(d, x, H * .50, S * 1.1)
        text(d, (x, H * .66), lab, H * .055)
    for k, lab in enumerate(['ДАЛЬН', 'ЯРК', 'ПЕРЕКЛ']):
        x = S * (0.22 + 0.28 * k)
        knob(d, x, H * .82, H * .07, ang=0.3 + 0.7 * k)
        text(d, (x, H * .96), lab, H * .055)


def p_eng_console(d, im):
    S, H = im.width, im.height
    noisy(d, im, CONSOLE, 3, seed=61)
    text(d, (S * .5, H * .06), 'ДВИГАТЕЛИ ТВ3-117', S * .085, bold=True)
    for k, lab in enumerate(['ЛЕВ', 'ПРАВ']):
        x = S * (0.28 + k * 0.44)
        d.rectangle([x - S * .17, H * .12, x + S * .17, H * .27], fill=(18, 20, 20, 255), outline=(150, 150, 146, 255), width=2)
        toggle(d, x, H * .24, S * 1.2, big=True)
        text(d, (x, H * .15), lab, S * .09)
    for k, lab in enumerate(['ЗАПУСК', 'ХОЛ.ПР.', 'ПОЖАР']):
        x = S * (0.20 + k * 0.30)
        toggle(d, x, H * .42, S * 1.2)
        text(d, (x, H * .53), lab, S * .075)
    d.rectangle([S * .10, H * .62, S * .90, H * .76], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .5, H * .69), 'РОТОРНЫЙ ТОРМОЗ', S * .08, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['КРАН', 'ПЕРЕКР']):
        x = S * (0.30 + k * 0.40)
        knob(d, x, H * .88, S * .08, ang=0.5 + 0.6 * k)
        text(d, (x, H * .97), lab, S * .07)


def p_radug(d, im):
    """the Raduga-Sh control box"""
    S, H = im.width, im.height
    noisy(d, im, CONSOLE, 3, seed=62)
    text(d, (S * .5, H * .10), 'РАДУГА-Ш', S * .13, bold=True)
    for k, lab in enumerate(['ПИТ', 'РЕЖИМ', 'КАНАЛ']):
        x = S * (0.20 + k * 0.30)
        knob(d, x, H * .42, S * .09, ang=0.4 + 0.6 * k)
        text(d, (x, H * .62), lab, S * .075)
    for k in range(3):
        x = S * (0.22 + k * 0.28)
        d.ellipse([x - S * .05, H * .80 - S * .05, x + S * .05, H * .80 + S * .05], fill=[(40, 150, 56), (180, 150, 30), (24, 60, 28)][k] + (255,))


def p_console(side, caps):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, CONSOLE, 3, seed=20 + side)
        n = len(caps)
        for k, cap in enumerate(caps):
            y0 = H * (k / n) + H * 0.01; y1 = H * ((k + 1) / n) - H * 0.01
            d.rectangle([S * .05, y0, S * .95, y1], fill=(18, 20, 20, 255), outline=(10, 10, 10, 255), width=2)
            mid = (y0 + y1) / 2
            for j in range(3):
                x = S * (0.22 + 0.28 * j)
                if k % 2:
                    knob(d, x, mid + (y1 - y0) * .08, S * .075, ang=0.5 + 0.6 * j)
                else:
                    toggle(d, x, mid + (y1 - y0) * .10, S * 1.4)
            d.rectangle([S * .12, y0 + 5, S * .88, y0 + 5 + (y1 - y0) * .22], fill=(40, 44, 44, 255))
            text(d, (S * .5, y0 + 5 + (y1 - y0) * .11), cap, S * .075)
    return fn


def p_cushion(colour, straps=False):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 5, seed=50)
        for k in range(1, 4):
            d.line([0, H * k / 4, S, H * k / 4], fill=tuple(c - 16 for c in colour) + (255,), width=4)
        if straps:
            for x in (S * .28, S * .72):
                d.rectangle([x - S * .05, 0, x + S * .05, H], fill=HARNESS + (255,))
            d.rectangle([S * .38, H * .55, S * .62, H * .70], fill=(150, 150, 150, 255))
    return fn


def p_placard(lines, bg=YELLOW, fg=(20, 20, 20)):
    def fn(d, im):
        S, H = im.width, im.height
        d.rectangle([0, 0, S, H], fill=bg + (255,))
        d.rectangle([3, 3, S - 4, H - 4], outline=fg + (255,), width=4)
        for k, s in enumerate(lines):
            text(d, (S * .5, H * (k + 1) / (len(lines) + 1)), s, H * 0.62 / len(lines), fill=fg, bold=True)
    return fn


def p_eyepiece(d, im):
    """the KPS-53AV's eyepiece window: dark glass with the sight's reticle"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(6, 12, 8, 255))
    c = (S / 2, H / 2)
    g = (60, 190, 90, 255)
    d.ellipse([c[0] - S * .30, c[1] - S * .30, c[0] + S * .30, c[1] + S * .30], outline=g, width=3)
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        d.line([c[0] + dx * S * .08, c[1] + dy * S * .08, c[0] + dx * S * .42, c[1] + dy * S * .42], fill=g, width=3)
    d.ellipse([c[0] - 3, c[1] - 3, c[0] + 3, c[1] + 3], fill=g)


def p_head_side(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=33)
    text(d, (S * .5, H * .5), 'КПС-53АВ', H * .46, fill=(120, 128, 122), bold=True)


def p_sight_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['ЯРК', 'СЕТКА', 'УВЕЛ', 'КОНТР']):
        x = S * (0.14 + k * 0.24)
        d.ellipse([x - H * .20, H * .34 - H * .20, x + H * .20, H * .34 + H * .20], fill=(18, 18, 18, 255), outline=(120, 120, 120, 255), width=3)
        text(d, (x, H * .80), lab, H * .18)


def p_asp(d, im):
    """the ASP-17V's control strip toward the pilot"""
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=11)
    text(d, (S * .5, H * .16), 'АСП-17В', H * .26, fill=(150, 156, 150), bold=True)
    for k, lab in enumerate(['ЯРК', 'БАЗА', 'ВИЗИР']):
        x = S * (0.20 + k * 0.30)
        d.ellipse([x - H * .15, H * .52 - H * .15, x + H * .15, H * .52 + H * .15], fill=(18, 18, 18, 255), outline=(120, 120, 120, 255), width=3)
        text(d, (x, H * .88), lab, H * .17)


def p_kick(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=71)
    for k in range(3):
        d.rectangle([S * (.10 + k * .30), H * .30, S * (.34 + k * .30), H * .70], fill=(20, 24, 24, 255), outline=(70, 76, 76, 255), width=3)


# ---------------------------------------------------------------- the dense panels (painted: black boxes, white Russian legends, toggles, knobs)
def p_dial_static(kind):
    def fn(d, im):
        if kind == 'XMSN':
            dial_face(d, im, '', 0, 8, 1, 2, 4.0, labels=['0', '2', '4', '6', '8'], red=(7, 8))
        elif kind == 'HYD':
            dial_face(d, im, '', 0, 100, 10, 20, 60.0, labels=['0', '20', '40', '60', '80', '100'], red=(0, 10))
        elif kind == 'XMSNT':
            dial_face(d, im, '', 0, 150, 10, 30, 80.0, labels=['0', '30', '60', '90', '120', '150'], red=(120, 150))
        else:
            dial_face(d, im, '', 0, 10, 1, 2, 5.0, labels=['0', '2', '4', '6', '8', '10'], red=(0, 1))
    return fn


def _bg(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(18, 20, 21, 255), outline=(60, 66, 66, 255), width=3)


def p_cb(seed, labels):
    """a breaker / switch bank: rows of small toggles with Russian legends"""
    def fn(d, im):
        S, H = im.width, im.height
        _bg(d, im)
        rng = np.random.default_rng(seed)
        cols, rows = 6, 6
        for r in range(rows):
            for c in range(cols):
                x = S * (0.09 + 0.82 * c / (cols - 1)); y = H * (0.08 + 0.84 * r / (rows - 1))
                k = (r * cols + c) % len(labels)
                if rng.random() < 0.7:
                    toggle(d, x, y - H * 0.02, H * 0.38)
                else:
                    d.rectangle([x - S * .04, y - H * .04, x + S * .04, y + H * .03], fill=(150, 40, 30, 255) if rng.random() < 0.3 else (36, 40, 40, 255),
                                outline=(150, 150, 146, 255), width=2)
                text(d, (x, y + H * 0.065), labels[k], H * 0.045)
    return fn


def p_sw(seed, labels, rows=2):
    def fn(d, im):
        S, H = im.width, im.height
        _bg(d, im)
        rng = np.random.default_rng(seed)
        n = len(labels)
        per = (n + rows - 1) // rows
        for k, lab in enumerate(labels):
            r, c = divmod(k, per)
            x = S * (0.10 + 0.80 * (c + 0.5) / per)
            y = H * (0.30 + 0.42 * r) if rows > 1 else H * 0.40
            if rng.random() < 0.6:
                toggle(d, x, y, H * 0.8)
            else:
                knob(d, x, y, H * 0.10, ang=float(rng.random() * 2))
            text(d, (x, y + H * 0.24), lab, H * 0.10)
    return fn


def p_radio(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    text(d, (S * .5, H * .09), 'Р-828   РАДИОСТАНЦИЯ', H * .10, bold=True)
    d.rectangle([S * .08, H * .20, S * .50, H * .40], fill=(64, 82, 64, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .29, H * .30), '45,50', H * .13, fill=(20, 40, 20))
    for k, lab in enumerate(['ГРОМК', 'ШУМ', 'КАНАЛ']):
        knob(d, S * (0.62 + 0.14 * k), H * .30, H * .09, ang=0.5 + 0.5 * k)
        text(d, (S * (0.62 + 0.14 * k), H * .48), lab, H * .08)
    for k, lab in enumerate(['ПРД', 'ПРМ', 'ТЛГ', 'ТЛФ']):
        x = S * (0.15 + 0.23 * k)
        toggle(d, x, H * .70, H * .9)
        text(d, (x, H * .90), lab, H * .09)


PAINTERS = {'cbl': p_cb(21, ['АЗС', 'ПУ', 'НВ', 'ЗАП', 'ОСВ', 'ГИДР', 'ТОПЛ', 'ГЕН', 'ПТУ']),
            'cbr': p_cb(22, ['РЕЗ', 'КОНТ', 'ПИТ', 'СВЯЗЬ', 'ЛЕВ', 'ПРАВ', 'АВТ', 'ТЛВ', 'ОБОГР']),
            'rad': p_radio, 'arm': p_arm_pilot,
            'engp': p_sw(23, ['ЗАПУСК', 'ХОЛ.ПР.', 'ЛЕВ', 'ПРАВ', 'ПОЖАР', 'КРАН', 'РТ'])}


# ---------------------------------------------------------------- geometry
FLOOR_G, FLOOR_P = 1.58, 1.90
SLAB_BOT = 1.375                           # the floors reach down here (the cut region's lowest faces)
LIFT = 0.003


def build(atlas):
    m = Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lgrey=LGREY, frame=FRAME,
                                                cushion=CUSHION, harness=HARNESS, yellow=YELLOW, red=RED, metal=METAL, floor=FLOOR,
                                                bezel=(26, 27, 29), console=CONSOLE, rubber=RUBBER, armour=ARMOUR).items()}
    gauges, displays, glass = [], [], []
    cache = {}
    cos_s = math.cos(math.atan(SLOPE))

    # ===================================================================== floors, the hump between the crews, the nose closure
    WALL = 0.585
    for xh, za, zb in ((WALL, 6.62, 7.36), (0.50, 7.36, 7.50), (0.40, 7.50, 7.62)):                       # the nose narrows under the floor
        m.box([0, (SLAB_BOT + FLOOR_G) / 2, (za + zb) / 2], (X, Y, Z), (xh, (FLOOR_G - SLAB_BOT) / 2, (zb - za) / 2), sw['floor'])
    m.box([0, (SLAB_BOT + FLOOR_P) / 2, (4.74 + PF.z(FLOOR_P)) / 2], (X, Y, Z), (WALL + 0.015, (FLOOR_P - SLAB_BOT) / 2, (PF.z(FLOOR_P) - 4.74) / 2), sw['floor'])
    HUMP_TOP = 2.60
    m.yz_prism([(FLOOR_P, PF.z(FLOOR_P)), (2.30, PF.z(2.30)), (2.30, 6.66), (FLOOR_G, 6.66), (FLOOR_G, PF.z(FLOOR_P) + 0.01)], -0.60, 0.60, sw['panel_d'])
    m.yz_prism([(2.28, PF.z(2.28) + 0.004), (2.34, PF.z(2.34) + 0.004), (2.34, 6.66), (2.28, 6.66)], -0.60, 0.60, sw['panel_d'])
    m.yz_prism([(2.34, PF.z(2.34) + 0.004), (2.42, PF.z(2.42) + 0.004), (2.42, 6.66), (2.34, 6.66)], -0.574, 0.574, sw['panel_d'])             # (the canopy narrows over the sill)
    m.yz_prism([(2.42, PF.z(2.42)), (PANEL_TOP_P, PF.z(PANEL_TOP_P)), (2.66, 6.50), (2.42, 6.66)], -0.42, 0.42, sw['panel_d'])           # (convex: a fan over a dented profile would overlap itself)   # the hump's body (the operator's seat back stands against it)
    # ---- pilot's panel: two bands (PILOT_BANDS), each its own painted face, the wide one under the narrow one
    ppm = 760
    kick = atlas.paint(220, 70, p_kick)
    xp = lambda y: min(0.60, 0.62 - 0.379 * (y - 2.30) - 0.01)           # follows the A pillar (the canopy's outline at the panel)
    for bi, (bot, top, half) in enumerate(PILOT_BANDS):
        lay = [e for e in PILOT_LAYOUT if bot <= e[2] < top]
        W_ = int(round(2 * half * ppm)); H_ = int(round((top - bot) / cos_s * ppm))
        rect = atlas.paint(W_, H_, panel_paint(lay, PF, top, bot, half, (W_, H_), 3 + bi))
        m.quad_tex([PF.at(-half, bot, LIFT), PF.at(half, bot, LIFT), PF.at(half, top, LIFT), PF.at(-half, top, LIFT)], rect, normal=PF.N)
        for s_ in (1, -1):
            m.flat([PF.at(s_ * half, bot, LIFT), PF.at(s_ * xp(bot), bot, LIFT), PF.at(s_ * xp(top), top, LIFT), PF.at(s_ * half, top, LIFT)],
                   sw['panel_d'], normal=PF.N)
    hw0 = PILOT_BANDS[0][2]
    m.quad_tex([PF.at(-hw0, FLOOR_P + 0.004, LIFT), PF.at(hw0, FLOOR_P + 0.004, LIFT), PF.at(hw0, PANEL_BOT_P, LIFT), PF.at(-hw0, PANEL_BOT_P, LIFT)], kick, normal=PF.N)
    for s_ in (1, -1):
        m.flat([PF.at(s_ * hw0, FLOOR_P + 0.004, LIFT), PF.at(s_ * xp(FLOOR_P + 0.004), FLOOR_P + 0.004, LIFT), PF.at(s_ * xp(PANEL_BOT_P), PANEL_BOT_P, LIFT),
                PF.at(s_ * hw0, PANEL_BOT_P, LIFT)], sw['panel_d'], normal=PF.N)
    zt = PF.z(PANEL_TOP_P)
    y_visor = 2.82
    m.box([0, (PANEL_TOP_P + y_visor) / 2 - 0.0, (zt - 0.12 + 6.38) / 2], (X, Y, Z), (0.40, (y_visor - PANEL_TOP_P) / 2, (6.38 - zt + 0.12) / 2), sw['black'])   # the coaming / visor
    # the canopy jettison handle on the pilot's kick panel
    pl = atlas.paint(150, 40, p_placard(['АВАР. СБРОС', 'ФОНАРЯ'], bg=RED, fg=(240, 240, 232)))
    m.rect_tex(PF.at(0.0, 2.04, LIFT + 0.002), PF.N, PF.U, 0.200, 0.040, pl)

    # ===================================================================== PILOT
    for name, a, y, spec, cap in PILOT_LAYOUT:
        kind = spec[0]
        c = PF.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + PF.N * GAUGE_LIFT), 'Normal': r5(PF.N),
                           'Up': r5(PF.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c + PF.N * LIFT, PF.N, PF.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006 - LIFT, sw['bezel'])
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + PF.N * 0.0045), 'Normal': r5(PF.N), 'Up': r5(PF.U),
                           'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS, 'Lamps': LAMPS})
            m.frame_rect(c + PF.N * LIFT, PF.N, PF.U, w, h, 0.005, 0.008 - LIFT, sw['bezel'])
        elif kind == 'paint_disc':
            dia = spec[2]
            r_ = cache.get(spec[1]) or atlas.paint(96, 96, p_dial_static(spec[1]))
            cache[spec[1]] = r_
            m.rect_tex(c + PF.N * PAINT_LIFT, PF.N, PF.U, dia, dia, r_)
            m.ring(c + PF.N * LIFT, PF.N, PF.U, dia / 2, dia / 2 * (1 + RIM), 0.010 - LIFT, sw['bezel'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = int(round(w * 1400))
            r_ = atlas.paint(pw, int(round(pw * h / w)), PAINTERS[name])
            m.rect_tex(c + PF.N * PAINT_LIFT, PF.N, PF.U, w, h, r_)
            m.frame_rect(c + PF.N * LIFT, PF.N, PF.U, w, h, 0.004, 0.007 - LIFT, sw['bezel'])

    # -- the ASP-17V gyro gunsight on the coaming: a head with its control strip toward the pilot, and the combiner glass on two struts,
    # centred on the eye line (a little larger than the real unit), a chamfered plate in a thin frame
    sc = atlas.paint(150, 40, p_asp)
    head_top = y_visor + 0.14
    zh = 6.13
    m.box([0, (y_visor + head_top) / 2, zh], (X, Y, Z), (0.085, (head_top - y_visor) / 2, 0.065), sw['dgrey'], front=(2, -1, sc, -X, Y))
    m.box([0, head_top + 0.008, zh + 0.012], (X, Y, Z), (0.060, 0.008, 0.040), sw['dgrey'])
    lean = math.radians(30)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h, g_hw = 0.140, 0.085
    g_c = np.array([0.0, EYE_P[1], 5.905])
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    for s in (-1, 1):
        m.cyl([s * (g_hw - 0.01), head_top + 0.012, zh], [s * (g_hw - 0.01), g_bot[1], g_bot[2]], 0.005, sw['dgrey'], seg=6)
    # the plate: chamfered top corners (the ASP-17V's glass is a rounded rectangle), frame bars along every edge
    ch = 0.030
    outline = [(-g_hw, -g_h / 2), (g_hw, -g_h / 2), (g_hw, g_h / 2 - ch), (g_hw - ch, g_h / 2), (-g_hw + ch, g_h / 2), (-g_hw, g_h / 2 - ch)]
    P3 = [g_c + X * ox + g_up * oy for ox, oy in outline]
    glass += [P3[0], P3[1], P3[2], P3[0], P3[2], P3[3], P3[0], P3[3], P3[4], P3[0], P3[4], P3[5]]
    for i in range(len(P3)):
        a_, b_ = P3[i], P3[(i + 1) % len(P3)]
        mid = (a_ + b_) / 2
        ln = np.linalg.norm(b_ - a_)
        e = unit(b_ - a_)
        ax = (e, g_n, unit(np.cross(e, g_n)))
        m.box(mid, ax, (ln / 2 + 0.004, 0.004, 0.005), sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)

    # -- the pilot's seat: armoured base, pan, back with its armour plate, headrest, harness
    ez = EYE_P[2]
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    PAN_P = 2.30
    zbp = lambda y: ez - 0.30 - 0.0696 * (y - 2.9)
    ax_b = (X, unit([0, 1, -0.0696]), unit([0, 0.0696, 1]))
    m.box([0, (FLOOR_P + PAN_P - 0.02) / 2, ez - 0.02], (X, Y, Z), (0.215, (PAN_P - 0.02 - FLOOR_P) / 2, 0.245), sw['frame'])
    m.box([0, PAN_P - 0.01, ez - 0.01], (X, Y, Z), (0.200, 0.030, 0.255), sw['cushion'], front=(1, 1, cushion, -X, Z))
    yc = 2.86
    m.box([0, yc, zbp(yc) + 0.035], ax_b, (0.185, 0.36, 0.035), sw['cushion'], front=(2, 1, backt, -X, ax_b[1]))
    m.box([0, yc, zbp(yc) - 0.015], ax_b, (0.200, 0.37, 0.020), sw['frame'])
    m.box([0, yc + 0.03, zbp(yc) - 0.045], ax_b, (0.190, 0.34, 0.012), sw['armour'])                     # the armour plate behind the back
    yh = 3.26
    m.box([0, yh, zbp(yh) + 0.040], ax_b, (0.095, 0.095, 0.040), sw['cushion'])
    for s in (1, -1):
        m.box([s * 0.213, PAN_P + 0.11, ez - 0.14], (X, Y, Z), (0.012, 0.135, 0.100), sw['frame'])
        m.box([s * 0.213, PAN_P + 0.01, ez + 0.06], (X, Y, Z), (0.012, 0.040, 0.100), sw['frame'])
        m.box([s * 0.09, 2.90, zbp(2.90) + 0.077], (X, unit([0, 1, 0.3]), unit([0, -0.3, 1])), (0.025, 0.250, 0.003), sw['harness'])
        m.box([s * 0.12, PAN_P + 0.022, ez + 0.07], (X, Y, Z), (0.045, 0.004, 0.010), sw['harness'])
    m.box([0, PAN_P + 0.028, ez + 0.09], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])

    # -- the cyclic: boot on the floor between the knees, the column leaning back, the grip with trigger and hat
    FOOT = np.array([0.0, FLOOR_P + 0.005, 5.78])
    m.box(FOOT + [0, 0.014, 0], (X, Y, Z), (0.060, 0.014, 0.060), sw['dgrey'])
    m.box(FOOT + [0, 0.055, 0], (X, Y, Z), (0.034, 0.040, 0.034), sw['black'])
    col_ax = (X, unit([0, 1, -0.17]), unit([0, 0.17, 1]))
    m.box(FOOT + col_ax[1] * 0.37, col_ax, (0.011, 0.33, 0.011), sw['metal'])
    gc = FOOT + col_ax[1] * 0.74 + col_ax[2] * 0.01
    g_ax = (X, unit([0, 1, -0.30]), unit([0, 0.30, 1]))
    m.box(gc, g_ax, (0.022, 0.085, 0.030), sw['black'])
    m.box(gc + g_ax[1] * 0.087, g_ax, (0.019, 0.006, 0.025), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.097 + g_ax[2] * 0.006, g_ax, (0.005, 0.004, 0.005), sw['red'])
    m.box(gc + g_ax[2] * 0.032 + g_ax[1] * 0.025, g_ax, (0.007, 0.018, 0.005), sw['metal'])
    m.box(gc - g_ax[2] * 0.031 + g_ax[1] * 0.040, g_ax, (0.012, 0.018, 0.003), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.045 + g_ax[2] * 0.027, g_ax, (0.012, 0.006, 0.006), sw['mgrey'])

    # -- the pilot's side consoles: the left carries the collective and the engine / rotor brake panel, the right the weapons panel
    cons_p = atlas.paint(220, 300, p_console(1, ['СВЯЗЬ', 'НАВИГАЦИЯ', 'ТОПЛИВО', 'ОСВЕЩЕНИЕ']))
    eng = atlas.paint(150, 210, p_eng_console)
    armp = atlas.paint(200, 124, p_arm_pilot)
    TOPC = 2.18
    for s in (1, -1):
        s_i, s_o = (1, 0) if s > 0 else (0, 1)
        xi, xo = 0.30 * s, 0.585 * s
        z0, z1 = 4.98, 5.92
        t = [[xi, TOPC, z0], [xo, TOPC, z0], [xo, TOPC, z1], [xi, TOPC, z1]]
        m.face(t, [m.uv(cons_p, s_i, 0), m.uv(cons_p, s_o, 0), m.uv(cons_p, s_o, 1), m.uv(cons_p, s_i, 1)], normal=[0, 1, 0])
        m.flat([[xi, FLOOR_P, z0], [xi, FLOOR_P, z1], [xi, TOPC, z1], [xi, TOPC, z0]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[xi, FLOOR_P, z1], [xo, FLOOR_P, z1], [xo, TOPC, z1], [xi, TOPC, z1]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xi, FLOOR_P, z0], [xo, FLOOR_P, z0], [xo, TOPC, z0], [xi, TOPC, z0]], sw['panel_d'], normal=[0, 0, -1])
    # the engine panel on the left console's top (rear half), the weapons panel on the right console's top (front half)
    m.rect_tex([0.44, TOPC + 0.0015, 5.30], Y, Z, 0.20, 0.28, eng)
    m.rect_tex([-0.44, TOPC + 0.0015, 5.55], Y, -Z, 0.24, 0.15, armp)
    # the collective: base on the console, the lever running forward and up, the twist grip
    P0c = np.array([0.40, TOPC + 0.016, 5.00])
    G0 = np.array([0.385, TOPC + 0.160, 5.36])
    m.box(P0c - [0, 0.013, 0], (X, Y, Z), (0.030, 0.012, 0.030), sw['dgrey'])                       # the pivot block
    # The collective (the "throttle" lever) is its own bone (throttle_lever), hinged on the pivot block, modelled full down
    # (throttle_lib.collective); the twist grip is the throttle.
    arm = G0 - P0c
    THR.clear()
    THR.append(throttle_lib.collective(lambda: Mesh(atlas), sw, 'throttle_lever', P0c, float(math.hypot(arm[1], arm[2])), 8.0,
                                       math.degrees(math.atan2(0.20, 1.0)), grip_len=0.245, travel=24.0, lateral=float(arm[0])))
    # rudder pedals
    for s in (1, -1):
        zk = PF.z(FLOOR_P + 0.12) - 0.075
        m.box([s * 0.13, FLOOR_P + 0.15, zk], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, FLOOR_P + 0.09, zk + 0.025], (X, Y, Z), (0.014, 0.055, 0.025), sw['dgrey'])

    # ===================================================================== OPERATOR
    eg = EYE_G
    zg = GF.z
    # the dash block and the wedge under the windscreen (closed, so the nose bay cannot be seen into)
    pane_z = lambda y: CM.OB_BL[2] - (y - CM.OB_BL[1]) * (CM.OB_BL[2] - CM.OB_TL[2]) / (CM.OB_TL[1] - CM.OB_BL[1])
    m.yz_prism([(FLOOR_G, zg(FLOOR_G)), (PANEL_TOP_G, zg(PANEL_TOP_G)), (PANEL_TOP_G, pane_z(PANEL_TOP_G) - 0.004), (CM.OB_BL[1] - 0.004, CM.OB_BL[2] - 0.004),
                (FLOOR_G, CM.OB_BL[2] - 0.004)], -0.44, 0.44, sw['panel_d'])
    m.box([0, PANEL_TOP_G + 0.012, (zg(PANEL_TOP_G) - 0.10 + pane_z(PANEL_TOP_G)) / 2], (X, Y, Z), (0.44, 0.012, (pane_z(PANEL_TOP_G) - zg(PANEL_TOP_G) + 0.10) / 2 - 0.002), sw['black'])   # coaming
    a0, a1 = -HALF_G, HALF_G
    Wg = int(round((a1 - a0) * ppm)); Hg = int(round((PANEL_TOP_G - PANEL_BOT_G) / cos_s * ppm))
    rect_g = atlas.paint(Wg, Hg, panel_paint(GUNNER_LAYOUT, GF, PANEL_TOP_G, PANEL_BOT_G, HALF_G, (Wg, Hg), 5))
    m.quad_tex([GF.at(a0, PANEL_BOT_G, LIFT), GF.at(a1, PANEL_BOT_G, LIFT), GF.at(a1, PANEL_TOP_G, LIFT), GF.at(a0, PANEL_TOP_G, LIFT)],
               rect_g, normal=GF.N)
    m.quad_tex([GF.at(a0, FLOOR_G + 0.004, LIFT), GF.at(a1, FLOOR_G + 0.004, LIFT), GF.at(a1, PANEL_BOT_G, LIFT), GF.at(a0, PANEL_BOT_G, LIFT)], kick, normal=GF.N)
    for xa, xb in ((a1, 0.44), (-0.44, a0)):
        m.flat([GF.at(xa, FLOOR_G + 0.004, LIFT), GF.at(xb, FLOOR_G + 0.004, LIFT), GF.at(xb, PANEL_TOP_G, LIFT), GF.at(xa, PANEL_TOP_G, LIFT)], sw['panel_d'], normal=GF.N)
    arm_op = atlas.paint(220, 180, p_arm_op)
    for name, a, y, spec, cap in GUNNER_LAYOUT:
        kind = spec[0]
        c = GF.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 1, 'Kind': spec[1], 'Center': r5(c + GF.N * GAUGE_LIFT), 'Normal': r5(GF.N),
                           'Up': r5(GF.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c + GF.N * LIFT, GF.N, GF.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006 - LIFT, sw['bezel'])
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 1, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + GF.N * 0.0045), 'Normal': r5(GF.N), 'Up': r5(GF.U),
                           'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS_G, 'Lamps': LAMPS_G})
            m.frame_rect(c + GF.N * LIFT, GF.N, GF.U, w, h, 0.005, 0.008 - LIFT, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 1, 'Center': r5(c + GF.N * DISPLAY_LIFT), 'Normal': r5(GF.N), 'Up': r5(GF.U), 'Width': w,
                             'Depth': 0.035, 'Kind': spec[1], 'Style': spec[3]})
            _, n_, u_, r_ = frame(c, GF.N, GF.U)
            pr, t = 0.010, 0.022
            m.rect_tex(c + u_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, w + 2 * t, t, sw['bezel'])
            m.rect_tex(c - u_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, w + 2 * t, t, sw['bezel'])
            m.rect_tex(c + r_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, t, w, sw['bezel'])
            m.rect_tex(c - r_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, t, w, sw['bezel'])
            for sgn in (-1, 1):
                m.box(c + u_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (w / 2 + t, 0.0015, (pr - 0.002) / 2), sw['black'])
                m.box(c + r_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (0.0015, w / 2 + t, (pr - 0.002) / 2), sw['black'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            m.rect_tex(c + GF.N * PAINT_LIFT, GF.N, GF.U, w, h, arm_op)
            m.frame_rect(c + GF.N * LIFT, GF.N, GF.U, w, h, 0.004, 0.007 - LIFT, sw['bezel'])

    # -- the KPS-53AV / Raduga-Sh sight unit on the coaming: a boxy body, a periscope eyepiece rising to a rubber cup at his eye line,
    # the reticle window in the cup (HeadUpDisplays, seat 1), the control strip on top
    cy0 = PANEL_TOP_G + 0.024
    hz0, hz1 = 7.24, 7.41
    hy0, hy1 = cy0, cy0 + 0.20
    hw_ = 0.080
    m.yz_prism([(hy0, hz0), (hy1, hz0 + 0.02), (hy1, hz1), (hy0, hz1)], -hw_, hw_, sw['dgrey'])
    side = atlas.paint(120, 50, p_head_side)
    for s in (1, -1):
        m.rect_tex([s * (hw_ + 0.0015), (hy0 + hy1) / 2, (hz0 + hz1) / 2 + 0.01], [s, 0, 0], Y, 0.15, 0.045, side)
    # the periscope tube: from the body's rear top up and back to the cup
    cup_c = np.array([0.0, eg[1] - 0.045, eg[2] + 0.18])
    tube0 = np.array([0.0, hy1 - 0.06, hz0 + 0.02])
    m.cyl(tube0, cup_c + np.array([0, 0, 0.012]), 0.020, sw['dgrey'], seg=8)
    vf = atlas.paint(96, 96, p_eyepiece)
    for k in range(8):
        a0_, a1_ = 2 * math.pi * k / 8, 2 * math.pi * (k + 1) / 8
        d0 = X * math.cos(a0_) + Y * math.sin(a0_); d1 = X * math.cos(a1_) + Y * math.sin(a1_)
        r_in, r_out, out = 0.026, 0.044, 0.030
        p0, p1, p2, p3 = cup_c + d0 * r_in, cup_c + d1 * r_in, cup_c + d1 * r_out, cup_c + d0 * r_out
        m.flat([p0 - Z * out, p1 - Z * out, p2 - Z * out, p3 - Z * out], sw['rubber'], normal=-Z)
        m.flat([p3 - Z * out, p2 - Z * out, p2, p3], sw['rubber'], normal=(d0 + d1) / 2)
        m.flat([p0 - Z * out, p0, p1, p1 - Z * out], sw['rubber'], normal=-(d0 + d1) / 2)
    m.rect_tex(cup_c - Z * 0.003, -Z, Y, 0.062, 0.062, vf)
    tsu = dict(centre=cup_c - Z * 0.003, normal=-Z, up=Y, width=0.075, height=0.075)          # the operator's live sight (seat 1)
    sc2 = atlas.paint(150, 30, p_sight_ctrl)
    m.rect_tex([0, hy1 + 0.0015, (hz0 + hz1) / 2], Y, -Z, 0.15, 0.05, sc2)
    m.box([0, hy0 - 0.012, hz1 - 0.04], (X, Y, Z), (0.050, 0.012, 0.045), sw['black'])                      # the base on the coaming

    # -- the operator's seat: base, pan, back on its frame against the hump, headrest
    gcus = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    ezg = eg[2]
    PAN_G = 1.78
    zbg = lambda y: ezg - 0.30 - 0.0696 * (y - 2.20)
    m.box([0, (FLOOR_G + PAN_G - 0.02) / 2, ezg - 0.02], (X, Y, Z), (0.215, (PAN_G - 0.02 - FLOOR_G) / 2, 0.245), sw['frame'])
    m.box([0, PAN_G - 0.01, ezg - 0.01], (X, Y, Z), (0.200, 0.030, 0.255), sw['cushion'], front=(1, 1, cushion, -X, Z))
    yc = 2.16
    m.box([0, yc, zbg(yc) + 0.035], ax_b, (0.185, 0.36, 0.035), sw['cushion'], front=(2, 1, gcus, -X, ax_b[1]))
    m.box([0, yc, zbg(yc) - 0.015], ax_b, (0.200, 0.37, 0.020), sw['frame'])
    m.box([0, yc + 0.03, zbg(yc) - 0.045], ax_b, (0.190, 0.34, 0.012), sw['armour'])
    yh = 2.56
    m.box([0, yh, zbg(yh) + 0.040], ax_b, (0.095, 0.080, 0.040), sw['cushion'])
    for s in (1, -1):
        m.box([s * 0.213, PAN_G + 0.11, ezg - 0.14], (X, Y, Z), (0.012, 0.135, 0.100), sw['frame'])
        m.box([s * 0.213, PAN_G + 0.01, ezg + 0.06], (X, Y, Z), (0.012, 0.040, 0.100), sw['frame'])
        m.box([s * 0.09, 2.20, zbg(2.20) + 0.077], (X, unit([0, 1, 0.1]), unit([0, -0.1, 1])), (0.025, 0.250, 0.003), sw['harness'])
        m.box([s * 0.12, PAN_G + 0.022, ezg + 0.07], (X, Y, Z), (0.045, 0.004, 0.010), sw['harness'])
    m.box([0, PAN_G + 0.028, ezg + 0.09], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])

    # -- the operator's side consoles: the Raduga-Sh control box on the left, the Shturm hand controller on the right
    cons_g = atlas.paint(160, 240, p_console(1, ['СВЯЗЬ', 'ПРИБОРЫ', 'ОСВЕЩЕНИЕ']))
    radug = atlas.paint(120, 120, p_radug)
    SH = 1.93
    for s in (1, -1):
        xi, xo = 0.27 * s, 0.585 * s
        s_i, s_o = (1, 0) if s > 0 else (0, 1)
        z0, z1 = 6.86, 7.40
        top = [[xi, SH, z0], [xo, SH, z0], [xo, SH, z1], [xi, SH, z1]]
        m.face(top, [m.uv(cons_g, s_i, 0), m.uv(cons_g, s_o, 0), m.uv(cons_g, s_o, 1), m.uv(cons_g, s_i, 1)], normal=[0, 1, 0])
        m.flat([[xi, FLOOR_G, z0], [xi, FLOOR_G, z1], [xi, SH, z1], [xi, SH, z0]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[xi, FLOOR_G, z1], [xo, FLOOR_G, z1], [xo, SH, z1], [xi, SH, z1]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xi, FLOOR_G, z0], [xo, FLOOR_G, z0], [xo, SH, z0], [xi, SH, z0]], sw['panel_d'], normal=[0, 0, -1])
    # the Raduga-Sh box (left console)
    m.box([0.43, SH + 0.045, 7.17], (X, Y, Z), (0.075, 0.045, 0.100), sw['dgrey'])
    m.rect_tex([0.43, SH + 0.0905, 7.17], Y, -Z, 0.14, 0.18, radug)
    # the Shturm hand controller (right console): a base, a column, the pistol grip with trigger and thumb switch
    HC = np.array([-0.43, SH + 0.006, 7.14])
    m.box(HC + [0, 0.012, 0], (X, Y, Z), (0.040, 0.012, 0.040), sw['dgrey'])
    st_ax = (X, unit([0, 1, 0.20]), unit([0, -0.20, 1]))
    m.box(HC + st_ax[1] * 0.085, st_ax, (0.010, 0.075, 0.010), sw['metal'])
    sg = HC + st_ax[1] * 0.190
    m.box(sg, st_ax, (0.020, 0.045, 0.026), sw['black'])
    m.box(sg + st_ax[1] * 0.047, st_ax, (0.016, 0.004, 0.022), sw['dgrey'])
    m.box(sg + st_ax[2] * 0.028 + st_ax[1] * 0.012, st_ax, (0.006, 0.015, 0.004), sw['metal'])             # trigger on the grip's front
    m.box(sg + st_ax[1] * 0.056 - st_ax[2] * 0.004, st_ax, (0.008, 0.004, 0.008), sw['red'])               # thumb switch
    m.box(sg + st_ax[1] * 0.030 - st_ax[2] * 0.021, st_ax, (0.010, 0.012, 0.004), sw['mgrey'])
    # the operator's short cyclic between the knees and the pedals
    FOOTG = np.array([0.0, FLOOR_G + 0.005, 7.22])
    m.box(FOOTG + [0, 0.014, 0], (X, Y, Z), (0.050, 0.014, 0.050), sw['dgrey'])
    m.box(FOOTG + [0, 0.050, 0], (X, Y, Z), (0.030, 0.036, 0.030), sw['black'])
    col2 = (X, unit([0, 1, -0.17]), unit([0, 0.17, 1]))
    m.box(FOOTG + col2[1] * 0.17, col2, (0.010, 0.15, 0.010), sw['metal'])
    gc2 = FOOTG + col2[1] * 0.34
    m.box(gc2, g_ax, (0.020, 0.060, 0.026), sw['black'])
    m.box(gc2 + g_ax[1] * 0.062, g_ax, (0.016, 0.005, 0.022), sw['dgrey'])
    for s in (1, -1):
        zk = GF.z(FLOOR_G + 0.12) - 0.075
        m.box([s * 0.13, FLOOR_G + 0.15, zk], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, FLOOR_G + 0.09, zk + 0.025], (X, Y, Z), (0.014, 0.055, 0.025), sw['dgrey'])
    # the operator's emergency exit placard on his left console's side
    pl2 = atlas.paint(120, 36, p_placard(['АВАР. ВЫХОД', 'ПОТЯНИ'], bg=RED, fg=(240, 240, 232)))
    m.rect_tex([0.40, SH + 0.0015, 6.98], Y, -Z, 0.16, 0.05, pl2)
    return m, gauges, displays, glass, hud, tsu


OLD_TINT = (54, 66, 62)                    # the interior swatch the earlier pass pointed the old teal faces at
NEW_TINT = (22, 104, 98)                   # the Hind's turquoise, shaded for the tub's lining


def find_tint_texels(geo):
    """texels (x, y) the hull faces re-coloured by an earlier run point at (every vertex on ONE uv whose texel is OLD_TINT)"""
    hull = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    tex = np.asarray(Image.open(L.texture_path(VID)).convert('RGB')).astype(int)
    H, W = tex.shape[:2]
    seen = {}
    for poly in pm['polys']:
        ids = {v[2] for v in poly}
        if len(ids) != 1:
            continue
        u, vv = pm['uvs'][next(iter(ids))]
        x, y = min(W - 1, max(0, int(u * W))), min(H - 1, max(0, int((1 - vv) * H)))
        if np.abs(tex[y, x] - np.array(OLD_TINT)).max() <= 3:
            seen[(x, y)] = 1
    return sorted(seen)


def repaint_texels(path, texels, colour, dead=False):
    img = Image.open(path).convert('RGBA')
    c = np.array(colour, np.float32)
    if dead:
        g = c.mean()
        c = (c * 0.25 + g * 0.15) * 0.9
    c = tuple(int(round(v)) for v in c) + (255,)
    px = img.load()
    for (x, y) in texels:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                px[x + dx, y + dy] = c
    img.save(path, optimize=True)


def line_teal(geo, atlas):
    """old teal tub faces still standing in the cockpit (rim lining, side blocks) are re-coloured in the cockpit's interior green-grey: their
    uvs point at a swatch of the interior colour (positions untouched)"""
    hull = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    tex = np.asarray(Image.open(L.texture_path(VID)).convert('RGB')).astype(int)
    H, W = tex.shape[:2]
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    U = pm['uvs']
    rect = atlas.swatch(NEW_TINT)
    hits = []
    for poly in pm['polys']:
        c = P[[v[0] for v in poly]].mean(0)
        if abs(c[0]) > 0.64 or not 4.7 < c[2] < 7.66 or c[1] < 1.45 or c[1] > 3.7:
            continue
        cs = []
        for v in poly:
            u, vv = U[v[2]]
            cs.append(tex[min(H - 1, max(0, int((1 - vv) * H))), min(W - 1, max(0, int(u * W)))])
        r, g, b = np.mean(cs, 0)
        if min(abs(np.array([r, g, b]) - np.array(NEW_TINT)).max(), abs(np.array([r, g, b]) - np.array(OLD_TINT)).max()) <= 6:
            continue                                      # (already re-coloured by an earlier run: the swatch texels are repainted in place)
        if b > r + 25 and g > r + 25:
            hits.append(poly)
    if hits:                                              # (no uv is added when nothing is re-coloured: a rerun leaves the hull piece as it is)
        ui = len(U)
        U.append([round(v, 6) for v in atlas.uv(rect, 0.5, 0.5)])
        for poly in hits:
            for v in poly:
                v[2] = ui
    return len(hits)


def planform(geo):
    """a top-down outline of the airframe for the stores page: the hull's widest |x| per z, as one closed polyline"""
    hull = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == HULL_BONE)
    P = np.array(hull['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
    zs = np.arange(-12.0, 8.4, 0.4)
    left, right = [], []
    for z in zs:
        sel = P[np.abs(P[:, 2] - z) < 0.3]
        if len(sel) == 0:
            continue
        left.append((float(sel[:, 0].max()), float(z)))
        right.append((float(sel[:, 0].min()), float(z)))
    loop = left + right[::-1] + [left[0]]
    return [[round(v, 3) for p in loop for v in p]]


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


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bones[:] = [b for b in bones if b['name'] != BONE]
    atlas = Atlas(VID)
    mesh, gauges, displays, glass, hud, tsu = build(atlas)
    lined = line_teal(geo, atlas)
    pm = mesh.poly_mesh()
    tint_texels = find_tint_texels(geo)
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    at = [b['name'] for b in bones].index('hull__canopy') + 1 if any(b['name'] == 'hull__canopy' for b in bones) else bones.index(hull) + 1
    bones.insert(at, {'name': BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    thr = throttle_lib.add_bones(bones, THR, BONE)
    print(f'{VID} throttle bones {[(n, p) for n, p, _ in thr]}')
    asset['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    asset['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays, 'Planform': planform(geo)}
    asset['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(EYE_P), 'Style': 'GYRO'}
    # the operator's KPS-53AV reticle: a live reflector-style sight for seat 1 only (HeadUpDisplays, one sight per seat; the lone
    # HeadUpDisplay above stays the pilot's for code that does not read the list yet)
    asset['HeadUpDisplays'] = [{'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 1,
                                'Center': r5(tsu['centre']), 'Normal': r5(tsu['normal']), 'Up': r5(tsu['up']),
                                'Width': tsu['width'], 'Height': tsu['height'],
                                'Eye': r5(EYE_G), 'Style': 'REFLECTOR'}]
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        c = t.mean(1)
        gc = np.array(hud['centre'])
        keep = ~((np.abs(c[:, 0]) < 0.12) & (np.abs(c[:, 1] - gc[1]) < 0.08) & (c[:, 2] > 5.84) & (c[:, 2] < 5.97))   # (an earlier run's gunsight glass goes)
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    print(f'{VID}: {lined} old teal faces re-coloured, new cockpits {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} display(s), gunsight glass '
          f'{len(glass) // 3} triangles; atlas {atlas.used()} of {sum(a[2] * a[3] for a in atlas.areas)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    tex = os.path.normpath(L.texture_path(VID))
    atlas.write(tex)
    repaint_texels(tex, tint_texels, NEW_TINT)
    dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
    if os.path.isfile(dead):
        atlas.write(dead, dead=True)
        repaint_texels(dead, tint_texels, NEW_TINT, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])
