#!/usr/bin/env python3
"""Lockheed AH-56A Cheyenne cockpits, built by hand for the AH-56A.mtb airframe (one standalone script, as vulcan_b2.py and ah1w_super_cobra.py):
two crew in tandem, the PILOT in the rear on a raised floor, the GUNNER in the front, lower, as on the real Cheyenne.

Measured off the model (checks/orig/ah_56a.geo.json, the source elements in ah56/cutlist.py; vehicle blocks, 1 block = about 0.87 m, x to the
left, y up, z forward): the pilot's floor top y 2.714 (the model's own slab is cut out with the old interior; the tub's lower floor stands at
y 2.327), the gunner's y 2.262 (tub floor 2.197); the tub's inner walls at |x| 0.84 up to the sill (y 3.10, the sill slab's inner edge |x| 0.65
at y 3.23); the pilot's eye (0, 4.275, 5.566) and the gunner's (0, 3.936, 7.440); the nose block's deck top y 2.975 ahead of z 8.27.
canopy_ah_56a.py cuts the old frames, seat groups, panels, consoles and sight bits and builds the canopy; this script builds, into the bone
hull__cockpit:

GUNNER (front, Seat 1; the real station rotates with the turret, here the seat is fixed):
  * the XM112 periscopic sight head: a boxy head with a rubber eyepiece at his eye, a brow pad, the control strip on top, two handgrips on
    arms either side (the grips carry the triggers), standing on a bracket out of the panel's top; the sight turns with the nose turret
    (the game's gunner optic is on the turret barrel: the head here is physical, not a head-up display);
  * a small panel under the sight: weapons control panel (painted), his airspeed, altimeter and torque dials, the rounds counter, the stores
    display (FlightDisplays STORES) and caution lamps;
  * the armoured seat, the short side-stick cyclic on the right shelf and the collective on the left (the gunner could fly the aircraft),
    rudder pedals; the nose closed in front by a cowl.
PILOT (rear, Seat 0, eye 0.113 lower than the airframe data had it: the old roof rail stood at eye height):
  * the instrument panel on a bulkhead block between the crews (leaning back 6 degrees under a visor): ADI over the HSI, airspeed and
    altimeter, turn and slip, vertical speed, rotor rpm, torque, the T64-GE-16's gas generator N1 and turbine gas temperature, fuel, radar
    altimeter and clock (live), and painted transmission / engine oil, pusher propeller pitch, twin hydraulic gauges, a caution panel, the
    armament, engine start / fuel and fire panels;
  * a fixed reflector sight on the glareshield (HeadUpDisplay, Seat 0, Style REFLECTOR: the Cheyenne pilot sighted with a helmet sight, no
    head-up display; the game paints a reticle on the plate), centred on his eye line;
  * the armoured seat, the cyclic between the knees, the collective on the left console with its twist grip, the pusher propeller pitch lever
    and the engine condition lever in a quadrant beside it, two consoles, rudder pedals.
No HeadUpDisplay or HeadUpDisplays entry for the gunner.

Live gauges only through CockpitGauges / FlightDisplays / the pilot's HeadUpDisplay; everything else is a painted face. The sticks are fixed
(the airframe has no stick rig). Placards are English.

Imports only cockpit_lib and free_atlas (the building blocks are inlined). Run on the airframe's originals after canopy_ah_56a.py
(ah56/run_ah56_cockpit.ps1 restores them). Frames: absolute vehicle blocks; the mesh is written in model pixels (x, y, -z) * 16.
Usage: python tools/cockpit_models/ah_56a.py [--dry-run]"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
THR = []                                 # the throttle levers (throttle_lib), one bone each

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import free_atlas as FA   # noqa: E402
import canopy_ah_56a as CM   # noqa: E402
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

VID = 'ah_56a'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
DATA = os.path.join(GEN, f'data/berts_vehicle_pack/sbw/vehicles/{VID}.json')
BONE = 'hull__cockpit'
HULL_BONE = 'hull'

EYE_P = CM.EYE_P
EYE_G = CM.EYE_G
SEAT_P_Y = 2.60121                       # the pilot's seat position (was 2.71425, 0.11304 lower with the eye)

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
            self.box(c + rr * cx + u * cy + n * proud / 2, (rr, u, n), (hw, hh, proud / 2), rect)

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


# ---------------------------------------------------------------- colours (a dark olive-grey Army cockpit, black panels, olive seats)
PANEL = (44, 46, 48)
PANEL_D = (26, 27, 29)
BLACK = (18, 19, 21)
DGREY = (36, 38, 40)
MGREY = (84, 88, 90)
LGREY = (150, 154, 156)
FRAME = (44, 48, 44)
CUSHION = (62, 68, 50)
HARNESS = (82, 86, 64)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (36, 38, 38)
CONSOLE = (54, 56, 58)
AMBER = (214, 140, 30)
RUBBER = (14, 14, 15)


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

    def __init__(self, z0, y0):
        self.z0, self.y0 = z0, y0
        self.U = unit([0, 1, SLOPE])
        self.N = unit([0, SLOPE, -1])          # looking back at the crew, a little up

    def z(self, y):
        return self.z0 + SLOPE * (y - self.y0)

    def at(self, a, y, lift=0.0):
        return np.array([-a, y, self.z(y)]) + self.N * lift


PF = Facet(6.400, 3.150)                   # the pilot's instrument panel face
GF = Facet(7.980, 2.975)                   # the gunner's panel face
PANEL_TOP_P, PANEL_BOT_P = 3.890, 3.170
PANEL_TOP_G, PANEL_BOT_G = 3.400, 2.985
HALF_P, HALF_G = 0.40, 0.30

PILOT_LAYOUT = [
    ('adi',    0.000, 3.765, ('game', 'ATTITUDE', 0.105), 'attitude indicator (ADI)'),
    ('hsi',    0.000, 3.620, ('game', 'HEADING', 0.105), 'horizontal situation indicator (HSI)'),
    ('ias',   -0.135, 3.770, ('game', 'AIRSPEED', 0.076), 'airspeed indicator'),
    ('alt',    0.135, 3.770, ('game', 'ALTIMETER', 0.076), 'barometric altimeter'),
    ('turn',  -0.135, 3.655, ('game', 'TURN_SLIP', 0.062), 'turn and slip'),
    ('vsi',    0.135, 3.655, ('game', 'VVI', 0.062), 'vertical speed indicator'),
    ('nr',    -0.245, 3.770, ('game', 'ROTOR_RPM', 0.070), 'rotor rpm'),
    ('tq',     0.245, 3.770, ('game', 'TORQUE', 0.070), 'torque'),
    ('n1',    -0.350, 3.670, ('game', 'ENGINE_RPM', 0.056), 'T64 gas generator (N1)'),
    ('tgt',   -0.280, 3.660, ('game', 'EGT', 0.052), 'turbine gas temperature'),
    ('fuel',   0.250, 3.665, ('game', 'FUEL_QTY', 0.054), 'fuel quantity'),
    ('ralt',   0.320, 3.665, ('game', 'RADAR_ALT', 0.054), 'radar altimeter'),
    ('xmsn',  -0.350, 3.565, ('paint_disc', 'XMSN', 0.050), 'transmission oil'),
    ('oil',   -0.280, 3.565, ('paint_disc', 'OIL', 0.050), 'engine oil'),
    ('prop',  -0.205, 3.560, ('paint_disc', 'PROP', 0.064), 'pusher propeller pitch'),
    ('hyd1',   0.210, 3.565, ('paint_disc', 'HYD1', 0.050), 'hydraulic system 1'),
    ('hyd2',   0.275, 3.565, ('paint_disc', 'HYD2', 0.050), 'hydraulic system 2'),
    ('clock',  0.345, 3.565, ('game', 'CLOCK', 0.048), 'clock'),
    ('caution', 0.000, 3.470, ('annunciator', 0.300, 0.085), 'caution / warning panel'),
    ('arm',   -0.255, 3.325, ('paint_rect', 0.170, 0.095), 'armament panel'),
    ('eng',    0.255, 3.325, ('paint_rect', 0.170, 0.095), 'engine start / fuel panel'),
    ('fire',   0.000, 3.320, ('paint_rect', 0.150, 0.085), 'fire / extinguisher'),
]
GUNNER_LAYOUT = [
    ('g_arm', -0.185, 3.185, ('paint_rect', 0.200, 0.165), 'gunner weapons control panel'),
    ('g_sto',  0.150, 3.215, ('display', 'STORES', 0.130, 'CRT'), 'stores display'),
    ('g_ias', -0.075, 3.335, ('game', 'AIRSPEED', 0.056), 'airspeed indicator'),
    ('g_alt',  0.000, 3.335, ('game', 'ALTIMETER', 0.056), 'altimeter'),
    ('g_tq',   0.075, 3.335, ('game', 'TORQUE', 0.056), 'torque'),
    ('g_ammo', 0.245, 3.350, ('game', 'AMMO', 0.056), 'rounds remaining'),
    ('g_cau',  0.000, 3.040, ('annunciator', 0.280, 0.045), 'caution lamps'),
]

LAMPS = [{"Text": "MASTER|CAUTION", "Condition": "MASTER_CAUTION", "Colour": "amber"},
         {"Text": "ENG|OUT", "Condition": "ENGINE_OUT", "Colour": "red"},
         {"Text": "FIRE", "Condition": "FIRE", "Colour": "red"},
         {"Text": "LOW|FUEL", "Condition": "LOW_FUEL", "Colour": "amber"},
         {"Text": "ROTOR|LOW", "Condition": "ROTOR_RPM_LOW", "Colour": "red"},
         {"Text": "ROTOR|HIGH", "Condition": "ROTOR_RPM_HIGH", "Colour": "amber"},
         {"Text": "TORQUE|HIGH", "Condition": "TORQUE_HIGH", "Colour": "amber"},
         {"Text": "ENG|RUN", "Condition": "ENGINE_RUN", "Colour": "green"},
         {"Text": "XMSN|OIL", "Condition": "NONE", "Colour": "amber"},
         {"Text": "HYD|PRESS", "Condition": "NONE", "Colour": "amber"},
         {"Text": "PROP|PITCH", "Condition": "NONE", "Colour": "amber"},
         {"Text": "RWR|LOCK", "Condition": "RWR_LOCK", "Colour": "amber"},
         {"Text": "MSL|LAUNCH", "Condition": "MISSILE_LAUNCH", "Colour": "red"},
         {"Text": "LOW|AMMO", "Condition": "AMMO_LOW", "Colour": "amber"},
         {"Text": "DAMAGE", "Condition": "DAMAGE", "Colour": "red"},
         {"Text": "LOW|ALT", "Condition": "LOW_ALT", "Colour": "amber"}]
LAMP_COLUMNS = 8
LAMPS_G = [LAMPS[0], LAMPS[1], LAMPS[2], LAMPS[3], LAMPS[12], LAMPS[13]]
LAMP_COLUMNS_G = 6


def panel_paint(layout, facet, top, bot, half, rect_px, seed):
    """the panel face: dark grey, screws, and the dark recess behind every instrument"""
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
        for name, a, y, spec, _ in layout:
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(14, 17, 17, 255))
                if kind == 'game' and spec[1] in ('ROTOR_RPM', 'TORQUE', 'ENGINE_RPM', 'EGT', 'FUEL_QTY', 'RADAR_ALT', 'AMMO'):
                    cap = {'ROTOR_RPM': 'ROTOR', 'TORQUE': 'TORQUE', 'ENGINE_RPM': 'N1', 'EGT': 'TGT', 'FUEL_QTY': 'FUEL',
                           'RADAR_ALT': 'RAD ALT', 'AMMO': 'ROUNDS'}[spec[1]]
                    text(d, (x, yy - r - SS * 5), cap, SS * 6, fill=(170, 172, 168, 255))
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

def p_dial_static(kind):
    def fn(d, im):
        if kind == 'XMSN':
            dial_face(d, im, 'XMSN', 0, 120, 10, 20, 70.0, labels=['0', '20', '40', '60', '80', '100', '120'], red=(105, 120))
        elif kind == 'HYD1':
            dial_face(d, im, 'HYD 1', 0, 3000, 250, 500, 2100.0, labels=['0', '5', '10', '15', '20', '25', '30'], red=(0, 300))
        elif kind == 'HYD2':
            dial_face(d, im, 'HYD 2', 0, 3000, 250, 500, 2100.0, labels=['0', '5', '10', '15', '20', '25', '30'], red=(0, 300))
        elif kind == 'PROP':
            dial_face(d, im, 'PROP', -20, 100, 10, 20, 15.0, labels=['-20', '0', '20', '40', '60', '80', '100'], red=None)
        else:
            dial_face(d, im, 'OIL', 0, 100, 10, 20, 55.0, labels=['0', '20', '40', '60', '80', '100'], red=(0, 12))
    return fn


def p_arm(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .08), 'ARMAMENT', H * .11, bold=True)
    d.rectangle([S * .06, H * .18, S * .30, H * .42], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .18, H * .30), 'MASTER ARM', H * .07, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['GUN', 'RKT', 'TOW']):
        x = S * (0.40 + k * 0.20)
        d.rectangle([x - S * .08, H * .20, x + S * .08, H * .40], fill=(40, 130, 56, 255) if k == 1 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .30), lab, H * .09, fill=(10, 30, 10))
    for k, lab in enumerate(['1', '2', '3', '4']):
        x = S * (0.14 + 0.24 * k)
        d.rectangle([x - S * .08, H * .54, x + S * .08, H * .76], fill=(176, 176, 172, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x, H * .65), lab, H * .14, fill=(20, 20, 20))
    text(d, (S * .5, H * .88), 'STATION SELECT', H * .075)


def p_eng(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .08), 'T64 ENGINE  FUEL', H * .10, bold=True)
    for k, lab in enumerate(['START', 'IGN']):
        x = S * (0.28 + k * 0.44)
        d.rectangle([x - S * .17, H * .20, x + S * .17, H * .44], fill=(30, 32, 34, 255), outline=(150, 150, 146, 255), width=2)
        toggle(d, x, H * .36, S * 1.2, big=True)
        text(d, (x, H * .26), lab, H * .08)
    for k, lab in enumerate(['XFER', 'BOOST', 'EMER']):
        x = S * (0.22 + k * 0.28)
        toggle(d, x, H * .68, S * 1.2)
        text(d, (x, H * .86), lab, H * .075)


def p_fire(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    d.rectangle([S * .10, H * .10, S * .90, H * .58], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .5, H * .26), 'ENGINE FIRE', H * .14, fill=(240, 240, 232), bold=True)
    text(d, (S * .5, H * .44), 'PULL', H * .11, fill=(240, 240, 232))
    text(d, (S * .5, H * .80), 'EXTINGUISHER', H * .12)


def p_gun_arm(d, im):
    """the gunner's weapons control panel"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .07), 'WEAPONS CONTROL', H * .075, bold=True)
    for k, lab in enumerate(['30MM', '40MM', 'MG', 'TOW']):
        x = S * (0.15 + k * 0.23)
        d.rectangle([x - S * .09, H * .15, x + S * .09, H * .33], fill=(40, 130, 56, 255) if k == 0 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .24), lab, H * .08, fill=(10, 30, 10))
    d.rectangle([S * .06, H * .42, S * .30, H * .60], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .18, H * .51), 'MSTR ARM', H * .07, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['TURRET', 'STOW', 'LOCK']):
        x = S * (0.45 + k * 0.22)
        toggle(d, x, H * .50, S * 1.1)
        text(d, (x, H * .66), lab, H * .06)
    for k, lab in enumerate(['RNG', 'FLT', 'MODE']):
        x = S * (0.22 + 0.28 * k)
        knob(d, x, H * .82, H * .07, ang=0.3 + 0.7 * k)
        text(d, (x, H * .96), lab, H * .055)


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
            d.rectangle([S * .12, y0 + 5, S * .88, y0 + 5 + (y1 - y0) * .22], fill=(40, 42, 42, 255))
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
    """the XM112's eyepiece window: dark glass with the sight's reticle"""
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
    text(d, (S * .5, H * .5), 'XM112', H * .5, fill=(120, 124, 120), bold=True)


def p_sight_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['BRT', 'RET', 'MAG', 'TEST']):
        x = S * (0.14 + k * 0.24)
        d.ellipse([x - H * .20, H * .34 - H * .20, x + H * .20, H * .34 + H * .20], fill=(18, 18, 18, 255), outline=(120, 120, 120, 255), width=3)
        text(d, (x, H * .80), lab, H * .18)


def p_reflector(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['BRT', 'RET', 'TEST']):
        x = S * (0.20 + k * 0.30)
        d.ellipse([x - H * .22, H * .30 - H * .22, x + H * .22, H * .30 + H * .22], fill=(18, 18, 18, 255), outline=(120, 120, 120, 255), width=3)
        text(d, (x, H * .74), lab, H * .2)


def p_kick(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=71)
    for k in range(3):
        d.rectangle([S * (.10 + k * .30), H * .30, S * (.34 + k * .30), H * .70], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)


def p_quadrant(d, im):
    """the pusher propeller pitch and engine condition quadrant: two slots with scale marks"""
    S, H = im.width, im.height
    noisy(d, im, CONSOLE, 3, seed=61)
    for k, (lab, lo, hi) in enumerate((('PROP PITCH', 'FEATHER', 'MAX'), ('ENG COND', 'OFF', 'FLY'))):
        x = S * (0.30 + 0.40 * k)
        d.rectangle([x - S * .05, H * .14, x + S * .05, H * .82], fill=(8, 8, 9, 255))
        text(d, (x, H * .07), lab, S * .13)
        text(d, (x + S * .20, H * .20), hi, S * .11)
        text(d, (x + S * .20, H * .78), lo, S * .11)
        for j in range(6):
            yy = H * (0.18 + 0.12 * j)
            d.line([x - S * .10, yy, x - S * .06, yy], fill=WHITE + (255,), width=3)


# ---------------------------------------------------------------- geometry
WALL = 0.82                                # the tub's inner half width used here (the walls stand at 0.84)
FLOOR_P, FLOOR_G = 2.714, 2.262
TUB_P, TUB_G = 2.327, 2.197                # the model's own lower floors
BLOCK_FRONT = 7.05                         # the bulkhead block's front face (the gunner's seat back stands just ahead of it)
Z_NOSE = 8.27                              # the nose block's rear face (deck top y 2.975 from here on)
LIFT = 0.003


def build(atlas):
    m = Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lgrey=LGREY, frame=FRAME,
                                                cushion=CUSHION, harness=HARNESS, yellow=YELLOW, red=RED, metal=METAL, floor=FLOOR,
                                                bezel=(26, 27, 29), console=CONSOLE, rubber=RUBBER, hull=(63, 66, 33)).items()}
    gauges, displays, glass = [], [], []
    cache = {}
    ex, ey, ez = EYE_P

    # ===================================================================== floors and the bulkhead block between the crews
    zr = PF.z
    LIFT_TUB = 0.006                           # the floors stand 6 mm above the model's own tub floors (their bottoms lay on them: z-fights)
    m.box([0, (TUB_P + LIFT_TUB + FLOOR_P) / 2, (4.78 + 6.40) / 2], (X, Y, Z), (WALL - 0.005, (FLOOR_P - TUB_P - LIFT_TUB) / 2, (6.40 - 4.78) / 2), sw['floor'])    # pilot's raised floor
    m.box([0, (TUB_G + LIFT_TUB + FLOOR_G) / 2, (7.00 + 8.20) / 2], (X, Y, Z), (WALL - 0.005, (FLOOR_G - TUB_G - LIFT_TUB) / 2, (8.20 - 7.00) / 2), sw['floor'])    # gunner's floor
    yb = TUB_G + 0.012
    for ya, yc, half in ((yb, 3.12, WALL), (3.12, 3.45, 0.64), (3.45, 3.93, 0.62)):
        m.yz_prism([(ya, zr(ya)), (yc, zr(yc)), (yc, BLOCK_FRONT), (ya, BLOCK_FRONT)], -half, half, sw['panel_d'])
    ppm = 760
    a0, a1 = -HALF_P, HALF_P
    W_ = int(round((a1 - a0) * ppm)); H_ = int(round((PANEL_TOP_P - PANEL_BOT_P) / math.cos(math.atan(SLOPE)) * ppm))
    rect = atlas.paint(W_, H_, panel_paint(PILOT_LAYOUT, PF, PANEL_TOP_P, PANEL_BOT_P, HALF_P, (W_, H_), 3))
    m.quad_tex([PF.at(a0, PANEL_BOT_P, LIFT), PF.at(a1, PANEL_BOT_P, LIFT), PF.at(a1, PANEL_TOP_P, LIFT), PF.at(a0, PANEL_TOP_P, LIFT)],
               rect, normal=PF.N)
    kick = atlas.paint(220, 70, p_kick)
    m.quad_tex([PF.at(a0, FLOOR_P, LIFT), PF.at(a1, FLOOR_P, LIFT), PF.at(a1, PANEL_BOT_P, LIFT), PF.at(a0, PANEL_BOT_P, LIFT)], kick, normal=PF.N)
    for xa, xb in ((a1, WALL), (-WALL, a0)):
        m.flat([PF.at(xa, FLOOR_P, LIFT), PF.at(xb, FLOOR_P, LIFT), PF.at(xb, 3.12, LIFT), PF.at(xa, 3.12, LIFT)], sw['panel_d'], normal=PF.N)
    for xa, xb in ((a1, 0.64), (-0.64, a0)):
        m.flat([PF.at(xa, 3.12, LIFT), PF.at(xb, 3.12, LIFT), PF.at(xb, 3.45, LIFT), PF.at(xa, 3.45, LIFT)], sw['panel_d'], normal=PF.N)
    for xa, xb in ((a1, 0.62), (-0.62, a0)):
        m.flat([PF.at(xa, 3.45, LIFT), PF.at(xb, 3.45, LIFT), PF.at(xb, PANEL_TOP_P, LIFT), PF.at(xa, PANEL_TOP_P, LIFT)], sw['panel_d'], normal=PF.N)
    zt = zr(PANEL_TOP_P)
    m.yz_prism([(PANEL_TOP_P, zt - 0.10), (PANEL_TOP_P + 0.030, zt - 0.10), (PANEL_TOP_P + 0.030, BLOCK_FRONT), (PANEL_TOP_P, BLOCK_FRONT)],
               -0.61, 0.61, sw['black'])                                        # the visor over the panel, a 10 cm lip toward the pilot
    y_glare = PANEL_TOP_P + 0.030
    # the canopy jettison handle and its placard on the pilot's kick panel
    pl = atlas.paint(150, 40, p_placard(['CANOPY JETTISON', 'PULL'], bg=RED, fg=(240, 240, 232)))
    m.rect_tex(PF.at(0.0, 3.205, LIFT + 0.002), PF.N, PF.U, 0.200, 0.040, pl)

    # ===================================================================== PILOT
    for name, a, y, spec, label in PILOT_LAYOUT:
        kind = spec[0]
        c = PF.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + PF.N * GAUGE_LIFT), 'Normal': r5(PF.N),
                           'Up': r5(PF.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c + PF.N * LIFT, PF.N, PF.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006 - LIFT, sw['bezel'])
        elif kind == 'paint_disc':
            dia = spec[2]
            r_ = cache.get(spec[1]) or atlas.paint(96, 96, p_dial_static(spec[1]))
            cache[spec[1]] = r_
            m.rect_tex(c + PF.N * PAINT_LIFT, PF.N, PF.U, dia, dia, r_)
            m.ring(c + PF.N * LIFT, PF.N, PF.U, dia / 2, dia / 2 * (1 + RIM), 0.010 - LIFT, sw['bezel'])
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + PF.N * 0.0045), 'Normal': r5(PF.N), 'Up': r5(PF.U),
                           'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS, 'Lamps': LAMPS})
            m.frame_rect(c + PF.N * LIFT, PF.N, PF.U, w, h, 0.005, 0.008 - LIFT, sw['bezel'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 200
            ph = max(16, int(round(pw * h / w)))
            r_ = atlas.paint(pw, ph, {'arm': p_arm, 'eng': p_eng, 'fire': p_fire}[name])
            m.rect_tex(c + PF.N * PAINT_LIFT, PF.N, PF.U, w, h, r_)
            m.frame_rect(c + PF.N * LIFT, PF.N, PF.U, w, h, 0.004, 0.007 - LIFT, sw['bezel'])

    # -- the pilot's fixed reflector sight (HeadUpDisplay Style REFLECTOR, Seat 0): a head on the glareshield (its control strip toward
    # the pilot) and one plate on two struts, a little larger than life, centred on the eye line
    sc = atlas.paint(150, 30, p_reflector)
    head_top = y_glare + 0.150
    zh = zt - 0.02
    m.box([0, (y_glare + head_top) / 2, zh], (X, Y, Z), (0.085, (head_top - y_glare) / 2, 0.065), sw['dgrey'], front=(2, -1, sc, -X, Y))
    m.box([0, head_top + 0.010, zh + 0.02], (X, Y, Z), (0.060, 0.010, 0.035), sw['dgrey'])
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_h, g_hw = 0.130, 0.080
    g_c = np.array([0.0, ey, zh - 0.12])
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    for s in (-1, 1):
        m.box([s * (g_hw + 0.004), (head_top + 0.020 + g_bot[1]) / 2, g_bot[2] + 0.010], (X, Y, Z),
              (0.004, max(0.004, (g_bot[1] - head_top - 0.020) / 2 + 0.004), 0.005), sw['dgrey'])
    m.box(g_bot - g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.005, 0.010), sw['dgrey'])
    for s in (-1, 1):
        m.box(g_c + X * s * (g_hw + 0.005), (X, g_up, g_n), (0.004, g_h / 2 + 0.002, 0.005), sw['dgrey'])
    m.box(g_top + g_up * 0.003, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    corners = [g_bot - X * g_hw, g_bot + X * g_hw, g_top + X * g_hw, g_top - X * g_hw]
    glass += [corners[0], corners[1], corners[2], corners[0], corners[2], corners[3]]
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)

    # -- the pilot's seat: armoured base, pan, back cushion on its frame, headrest, bolsters, harness
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    zbp = lambda y: ez - 0.30 - 0.0696 * (y - 3.45)
    ax_b = (X, unit([0, 1, -0.0696]), unit([0, 0.0696, 1]))
    PAN_P = 3.42
    m.box([0, (FLOOR_P + PAN_P - 0.02) / 2, ez - 0.02], (X, Y, Z), (0.215, (PAN_P - 0.02 - FLOOR_P) / 2, 0.245), sw['frame'])         # base
    m.box([0, PAN_P - 0.01, ez - 0.01], (X, Y, Z), (0.200, 0.030, 0.255), sw['cushion'], front=(1, 1, cushion, -X, Z))                # pan cushion
    yc = 3.80
    m.box([0, yc, zbp(yc) + 0.035], ax_b, (0.185, 0.36, 0.035), sw['cushion'], front=(2, 1, backt, -X, ax_b[1]))
    m.box([0, yc, zbp(yc) - 0.015], ax_b, (0.200, 0.37, 0.020), sw['frame'])                                                           # back frame
    yh = 4.10
    m.box([0, yh, zbp(yh) + 0.040], ax_b, (0.095, 0.095, 0.040), sw['cushion'])                                                         # headrest pad
    for s in (1, -1):
        m.box([s * 0.213, PAN_P + 0.11, ez - 0.14], (X, Y, Z), (0.012, 0.135, 0.100), sw['frame'])
        m.box([s * 0.213, PAN_P + 0.01, ez + 0.06], (X, Y, Z), (0.012, 0.040, 0.100), sw['frame'])
        m.box([s * 0.09, 3.85, zbp(3.85) + 0.077], (X, unit([0, 1, 0.3]), unit([0, -0.3, 1])), (0.025, 0.250, 0.003), sw['harness'])
        m.box([s * 0.12, PAN_P + 0.022, ez + 0.07], (X, Y, Z), (0.045, 0.004, 0.010), sw['harness'])
    m.box([0, PAN_P + 0.028, ez + 0.09], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])

    # -- the cyclic: boot on the floor between the knees, the column leaning back, the grip with trigger and hat
    FOOT = np.array([0.0, FLOOR_P + 0.005, 6.08])
    m.box(FOOT + [0, 0.014, 0], (X, Y, Z), (0.060, 0.014, 0.060), sw['dgrey'])
    m.box(FOOT + [0, 0.055, 0], (X, Y, Z), (0.034, 0.040, 0.034), sw['black'])
    col_ax = (X, unit([0, 1, -0.17]), unit([0, 0.17, 1]))
    m.box(FOOT + col_ax[1] * 0.45, col_ax, (0.011, 0.40, 0.011), sw['metal'])
    gc = FOOT + col_ax[1] * 0.90 + col_ax[2] * 0.01
    g_ax = (X, unit([0, 1, -0.30]), unit([0, 0.30, 1]))
    m.box(gc, g_ax, (0.022, 0.085, 0.030), sw['black'])
    m.box(gc + g_ax[1] * 0.087, g_ax, (0.019, 0.006, 0.025), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.097 + g_ax[2] * 0.006, g_ax, (0.005, 0.004, 0.005), sw['red'])
    m.box(gc + g_ax[2] * 0.032 + g_ax[1] * 0.025, g_ax, (0.007, 0.018, 0.005), sw['metal'])
    m.box(gc - g_ax[2] * 0.031 + g_ax[1] * 0.040, g_ax, (0.012, 0.018, 0.003), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.045 + g_ax[2] * 0.027, g_ax, (0.012, 0.006, 0.006), sw['mgrey'])

    # -- the pilot's side consoles (the left one carries the quadrant and the collective)
    cons_p = atlas.paint(220, 300, p_console(1, ['ICS / COMM', 'NAV', 'FUEL XFR', 'EXT LTS', 'ENG START']))
    quad = atlas.paint(110, 160, p_quadrant)
    for s in (1, -1):
        s_i, s_o = (1, 0) if s > 0 else (0, 1)
        for xi, xo, top in ((0.34 * s, 0.60 * s, 3.08), (0.60 * s, 0.76 * s, 3.00)):
            z0, z1 = 5.15, 5.90
            t = [[xi, top, z0], [xo, top, z0], [xo, top, z1], [xi, top, z1]]
            if abs(xi) < 0.5:
                m.face(t, [m.uv(cons_p, s_i, 0), m.uv(cons_p, s_o, 0), m.uv(cons_p, s_o, 1), m.uv(cons_p, s_i, 1)], normal=[0, 1, 0])
            else:
                m.flat(t, sw['console'], normal=[0, 1, 0])
            m.flat([[xi, FLOOR_P, z0], [xi, FLOOR_P, z1], [xi, top, z1], [xi, top, z0]], sw['panel_d'], normal=[-s, 0, 0])
            m.flat([[xi, FLOOR_P, z1], [xo, FLOOR_P, z1], [xo, top, z1], [xi, top, z1]], sw['panel_d'], normal=[0, 0, 1])
            m.flat([[xi, FLOOR_P, z0], [xo, FLOOR_P, z0], [xo, top, z0], [xi, top, z0]], sw['panel_d'], normal=[0, 0, -1])
    # the quadrant: a slotted plate on the left console's front with the propeller pitch and engine condition levers
    qc = np.array([0.47, 3.07, 5.70])
    m.box(qc, (X, Y, Z), (0.10, 0.012, 0.13), sw['dgrey'])
    m.rect_tex(qc + np.array([0, 0.0135, 0]), Y, -Z, 0.20, 0.26, quad)
    for k, (xl, col) in enumerate(((0.515, 'black'), (0.425, 'red'))):
        zl = 5.64 + 0.05 * k
        m.cyl([xl, 3.09, zl], [xl, 3.20, zl - 0.02], 0.007, sw['metal'], seg=6)
        m.box([xl, 3.22, zl - 0.025], (X, Y, Z), (0.014, 0.020, 0.014), sw[col])
    # the collective: base on the console, the lever running forward and up, the twist grip
    P0c = np.array([0.40, 3.115, 5.20])
    G0 = np.array([0.385, 3.30, 5.62])
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

    # ===================================================================== GUNNER
    eg = EYE_G
    zg = GF.z
    # -- the front block: the panel face leaning back, the block up to the panel's top; a cowl closes the nose in front
    for ya, yc2, half in ((yb, 3.10, 0.74), (3.10, 3.42, 0.62)):
        m.yz_prism([(ya, zg(ya)), (yc2, zg(yc2)), (yc2, Z_NOSE), (ya, Z_NOSE)], -half, half, sw['panel_d'])
    m.yz_prism([(2.975, Z_NOSE - 0.01), (3.42, Z_NOSE - 0.01), (3.00, 8.78), (2.975, 8.78)], -0.60, 0.60, sw['hull'])     # cowl (deck to panel top), in the nose's own olive
    ppm = 760
    a0, a1 = -HALF_G, HALF_G
    Wg = int(round((a1 - a0) * ppm)); Hg = int(round((PANEL_TOP_G - PANEL_BOT_G) / math.cos(math.atan(SLOPE)) * ppm))
    rect_g = atlas.paint(Wg, Hg, panel_paint(GUNNER_LAYOUT, GF, PANEL_TOP_G, PANEL_BOT_G, HALF_G, (Wg, Hg), 5))
    m.quad_tex([GF.at(a0, PANEL_BOT_G, LIFT), GF.at(a1, PANEL_BOT_G, LIFT), GF.at(a1, PANEL_TOP_G, LIFT), GF.at(a0, PANEL_TOP_G, LIFT)],
               rect_g, normal=GF.N)
    m.quad_tex([GF.at(a0, yb + 0.02, LIFT), GF.at(a1, yb + 0.02, LIFT), GF.at(a1, PANEL_BOT_G, LIFT), GF.at(a0, PANEL_BOT_G, LIFT)], kick, normal=GF.N)
    for xa, xb in ((a1, 0.62), (-0.62, a0)):
        m.flat([GF.at(xa, 3.10, LIFT), GF.at(xb, 3.10, LIFT), GF.at(xb, PANEL_TOP_G, LIFT), GF.at(xa, PANEL_TOP_G, LIFT)], sw['panel_d'], normal=GF.N)
    for xa, xb in ((a1, 0.74), (-0.74, a0)):
        m.flat([GF.at(xa, yb + 0.02, LIFT), GF.at(xb, yb + 0.02, LIFT), GF.at(xb, 3.10, LIFT), GF.at(xa, 3.10, LIFT)], sw['panel_d'], normal=GF.N)
    for name, a, y, spec, label in GUNNER_LAYOUT:
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
            r_ = atlas.paint(220, int(round(220 * h / w)), p_gun_arm)
            m.rect_tex(c + GF.N * PAINT_LIFT, GF.N, GF.U, w, h, r_)
            m.frame_rect(c + GF.N * LIFT, GF.N, GF.U, w, h, 0.004, 0.007 - LIFT, sw['bezel'])

    # -- the XM112 periscopic sight head: a boxy head with a rubber eyepiece at the gunner's eye line, a brow pad, a control strip on top
    # (its top follows the overhead plane, 5 cm under it), a bracket out of the panel and a handgrip on an arm either side
    hz0, hz1 = 7.74, 8.05
    hy0 = 3.64
    hw_ = 0.100
    top_y = lambda z: min(CM.Y_B2 - CM.S_G2 * (z - CM.ZB2) - 0.055, 3.86)       # (and under the gunner's eye line: he looks over it)
    ytop0, ytop1 = top_y(hz0), top_y(hz1)
    prof = [(hy0, hz0), (ytop0, hz0), (ytop1, hz1), (hy0, hz1)]
    m.yz_prism(prof, -hw_, hw_, sw['dgrey'])
    side = atlas.paint(120, 50, p_head_side)
    for s in (1, -1):
        m.rect_tex([s * (hw_ + 0.0015), (hy0 + ytop1) / 2, (hz0 + hz1) / 2 + 0.03], [s, 0, 0], Y, 0.20, 0.05, side)
    vf = atlas.paint(96, 96, p_eyepiece)
    ecen = np.array([0.0, (hy0 + ytop0) / 2, hz0])
    for k in range(8):
        a0_, a1_ = 2 * math.pi * k / 8, 2 * math.pi * (k + 1) / 8
        d0 = X * math.cos(a0_) + Y * math.sin(a0_); d1 = X * math.cos(a1_) + Y * math.sin(a1_)
        r_in, r_out, out = 0.034, 0.052, 0.040
        p0, p1, p2, p3 = ecen + d0 * r_in, ecen + d1 * r_in, ecen + d1 * r_out, ecen + d0 * r_out
        m.flat([p0 - Z * out, p1 - Z * out, p2 - Z * out, p3 - Z * out], sw['rubber'], normal=-Z)
        m.flat([p3 - Z * out, p2 - Z * out, p2, p3], sw['rubber'], normal=(d0 + d1) / 2)
        m.flat([p0 - Z * out, p0, p1, p1 - Z * out], sw['rubber'], normal=-(d0 + d1) / 2)
    m.rect_tex(ecen - Z * 0.003, -Z, Y, 0.075, 0.075, vf)
    m.box([0, ytop0 - 0.020, hz0 - 0.012], (X, Y, Z), (0.075, 0.020, 0.012), sw['rubber'])                  # the brow pad
    sc2 = atlas.paint(150, 30, p_sight_ctrl)
    zc = (hz0 + hz1) / 2
    ytc = (ytop0 + ytop1) / 2 + 0.002
    m.rect_tex([0, ytc, zc - 0.03], unit([0, 1, CM.S_G2]), -Z, 0.20, 0.06, sc2)
    m.box([0, hy0 - 0.060, hz1 - 0.075], (X, Y, Z), (0.050, 0.060, 0.065), sw['black'])                      # the bracket out of the panel's top
    for s in (1, -1):                                                                                         # arms and handgrips
        m.box([s * 0.150, hy0 + 0.04, 7.92], (X, Y, Z), (0.045, 0.012, 0.012), sw['black'])
        m.cyl([s * 0.190, hy0 - 0.12, 7.90], [s * 0.190, hy0 + 0.07, 7.90], 0.020, sw['black'], seg=8)
        m.cyl([s * 0.190, hy0 + 0.07, 7.90], [s * 0.190, hy0 + 0.085, 7.90], 0.016, sw['dgrey'], seg=8)
        m.box([s * 0.190, hy0 - 0.02, 7.878], (X, Y, Z), (0.006, 0.020, 0.006), sw['metal'])                   # trigger on the grip's face
        m.box([s * 0.190, hy0 + 0.060, 7.914], (X, Y, Z), (0.010, 0.005, 0.006), sw['red'])                   # thumb switch

    # -- the gunner's seat: base, pan, back on its frame against the block, headrest
    gcus = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    ezg = eg[2]
    zbg = lambda y: ezg - 0.30 - 0.0696 * (y - 3.04)
    PAN_G = 3.02
    m.box([0, (FLOOR_G + PAN_G - 0.02) / 2, ezg - 0.02], (X, Y, Z), (0.215, (PAN_G - 0.02 - FLOOR_G) / 2, 0.245), sw['frame'])
    m.box([0, PAN_G - 0.01, ezg - 0.01], (X, Y, Z), (0.200, 0.030, 0.255), sw['cushion'], front=(1, 1, cushion, -X, Z))
    yc = 3.42
    m.box([0, yc, zbg(yc) + 0.035], ax_b, (0.185, 0.36, 0.035), sw['cushion'], front=(2, 1, gcus, -X, ax_b[1]))
    m.box([0, yc, zbg(yc) - 0.015], ax_b, (0.200, 0.37, 0.020), sw['frame'])
    yh = 3.76
    m.box([0, yh, zbg(yh) + 0.040], ax_b, (0.095, 0.095, 0.040), sw['cushion'])
    for s in (1, -1):
        m.box([s * 0.213, PAN_G + 0.11, ezg - 0.14], (X, Y, Z), (0.012, 0.135, 0.100), sw['frame'])
        m.box([s * 0.213, PAN_G + 0.01, ezg + 0.06], (X, Y, Z), (0.012, 0.040, 0.100), sw['frame'])
        m.box([s * 0.09, 3.47, zbg(3.47) + 0.077], (X, unit([0, 1, 0.1]), unit([0, -0.1, 1])), (0.025, 0.250, 0.003), sw['harness'])
        m.box([s * 0.12, PAN_G + 0.022, ezg + 0.07], (X, Y, Z), (0.045, 0.004, 0.010), sw['harness'])
    m.box([0, PAN_G + 0.028, ezg + 0.09], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])

    # -- the gunner's side shelves: the side-stick cyclic on the right, the collective on the left
    cons_g = atlas.paint(160, 240, p_console(1, ['ICS / COMM', 'IFF', 'LIGHTS']))
    SH = 3.08
    for s in (1, -1):
        xi, xo = 0.340 * s, 0.80 * s
        s_i, s_o = (1, 0) if s > 0 else (0, 1)
        z0, z1 = 7.10, 7.95
        top = [[xi, SH, z0], [xo, SH, z0], [xo, SH, z1], [xi, SH, z1]]
        m.face(top, [m.uv(cons_g, s_i, 0), m.uv(cons_g, s_o, 0), m.uv(cons_g, s_o, 1), m.uv(cons_g, s_i, 1)], normal=[0, 1, 0])
        m.flat([[xi, FLOOR_G, z0], [xi, FLOOR_G, z1], [xi, SH, z1], [xi, SH, z0]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[xi, FLOOR_G, z1], [xo, FLOOR_G, z1], [xo, SH, z1], [xi, SH, z1]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xi, FLOOR_G, z0], [xo, FLOOR_G, z0], [xo, SH, z0], [xi, SH, z0]], sw['panel_d'], normal=[0, 0, -1])
    SF = np.array([-0.415, SH - 0.001, 7.55])
    m.box(SF + [0, 0.012, 0], (X, Y, Z), (0.045, 0.012, 0.045), sw['dgrey'])
    st_ax = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
    m.box(SF + st_ax[1] * 0.100, st_ax, (0.010, 0.085, 0.010), sw['metal'])
    sg = SF + st_ax[1] * 0.215
    m.box(sg, st_ax, (0.020, 0.040, 0.026), sw['black'])
    m.box(sg + st_ax[1] * 0.042, st_ax, (0.016, 0.004, 0.022), sw['dgrey'])
    m.box(sg + st_ax[2] * 0.028 + st_ax[1] * 0.010, st_ax, (0.006, 0.014, 0.004), sw['metal'])
    m.box(sg + st_ax[1] * 0.034 - st_ax[2] * 0.004, st_ax, (0.006, 0.004, 0.006), sw['red'])
    GP0 = np.array([0.430, SH + 0.017, 7.25])
    GG0 = np.array([0.408, SH + 0.105, 7.57])
    m.box(GP0, (X, Y, Z), (0.028, 0.012, 0.028), sw['dgrey'])
    # the gunner's collective: the same shaped lever, fixed (only the pilot's is the throttle bone)
    garm = GG0 - GP0
    throttle_lib.make_static(m, [throttle_lib.collective(lambda: Mesh(atlas), sw, 'collective_g', GP0, float(math.hypot(garm[1], garm[2])),
                                                         math.degrees(math.atan2(garm[1], garm[2])), math.degrees(math.atan2(0.12, 1.0)),
                                                         grip_len=0.173, lateral=float(garm[0]))])
    for s in (1, -1):                                                                                        # pedals
        zk = GF.z(FLOOR_G + 0.12) - 0.21
        m.box([s * 0.13, FLOOR_G + 0.15, zk], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, FLOOR_G + 0.09, zk + 0.025], (X, Y, Z), (0.014, 0.055, 0.025), sw['dgrey'])
    # the gunner's emergency exit placard on his seat's back side shelf
    pl2 = atlas.paint(120, 36, p_placard(['EMERGENCY EXIT', 'PULL HANDLE'], bg=RED, fg=(240, 240, 232)))
    m.rect_tex([0.60, SH + 0.0015, 7.50], Y, -Z, 0.20, 0.06, pl2)
    return m, gauges, displays, glass, hud


def planform(geo):
    """a top-down outline of the airframe for the stores page: the hull's widest |x| per z, as one closed polyline"""
    hull = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == HULL_BONE)
    P = np.array(hull['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
    zs = np.arange(-10.8, 9.8, 0.4)
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


def move_eyes(data):
    """the pilot's eye and seat lowered 0.113 (the old roof rail stood at eye height); the gunner's stay"""
    ex, ey, ez = EYE_P
    data['Attachments']['pilot_eye']['Position'] = [ex, ey, ez]
    ps = data['Seats'][0]
    ps['Position'] = [ps['Position'][0], SEAT_P_Y, ps['Position'][2]]


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    data = json.load(open(DATA, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    bones[:] = [b for b in bones if b['name'] != BONE]
    atlas = Atlas(VID)
    mesh, gauges, displays, glass, hud = build(atlas)
    pm = mesh.poly_mesh()
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
                              'Eye': r5(EYE_P), 'Style': 'REFLECTOR'}
    asset.pop('HeadUpDisplays', None)                  # the gunner's sight head is physical: no hull-fixed combiner for him
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        c = t.mean(1)
        gl = np.array(hud['centre'])
        keep = ~((np.abs(c[:, 0]) < 0.14) & (np.abs(c[:, 1] - gl[1]) < 0.12) & (np.abs(c[:, 2] - gl[2]) < 0.10))   # (an earlier plate goes)
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    data_before = json.dumps(data, sort_keys=True)
    move_eyes(data)
    print(f'{VID}: new cockpits {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} display(s), reflector glass '
          f'{len(glass) // 3} triangles; atlas {atlas.used()} of {sum(a[2] * a[3] for a in atlas.areas)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    if json.dumps(data, sort_keys=True) != data_before:
        # (the data file keeps its own layout: 2-space indent; only the pilot's eye and seat height change)
        write_json(DATA, data, 2)
    tex = os.path.normpath(L.texture_path(VID))
    atlas.write(tex)
    dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
    if os.path.isfile(dead):
        atlas.write(dead, dead=True)


if __name__ == '__main__':
    main(sys.argv[1:])
