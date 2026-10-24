#!/usr/bin/env python3
"""SA 341F Gazelle cockpit, built by hand for the Aerospatiale Gazelle.mtb airframe (commits aeff8294, b9b6bfc7), from the real layout.

Side by side, as on the real Gazelle: the PILOT in the RIGHT seat (x < 0), the OBSERVER / gunner in the LEFT seat (x > 0), under the glazed
cage of canopy_sa341f_gazelle.py. Measured off the model (vehicle blocks, 1 block = 1 m on this model; x to the left, y up, z forward):
the cabin floor's top is y 0.569 (slabs from z 2.825 to 4.22), the sill rails' top y 0.724, the eyes (-0.465 / +0.465, 1.675, 3.455), the old
interior (both seat groups, the instrument block at z 4.0 .. 4.58, the floor controls) was cut by the canopy script; the roof's underside
stands at y 1.86 over the seats; the windscreen's belt bar runs from the A pillars (x 0.79, z 3.94) to the keel wedge (x 0.21, z 4.66) at
y 1.33, so the dash (a low central panel) stands at z 3.94 .. 4.02 with its glareshield at y 1.30 up to that line.
Built:
  * the instrument panel, one low central panel under a glareshield that reaches the windscreen's belt bar, in three groups: the PILOT's
    flight instruments right (the basic T: attitude, airspeed, altimeter / heading, vertical speed, turn and slip), the shared engine and
    rotor group in the centre (torque, rotor rpm, gas generator, turbine temperature, fuel, clock, oil, hydraulic, voltage), the OBSERVER's
    group left (his own repeaters, a stores display, rounds, the weapons panel); caution panels on the glareshield (French captions); French
    placards throughout;
  * pilot: the cyclic between the knees with trigger, hat and release (fixed: the airframe has no stick rig), the collective on his left
    with the fuel flow control, the rudder pedals, a bucket seat with headrest, shoulder harness and lap belt;
  * observer: a bucket seat the same, the periscope eyepiece of the roof-mounted sight (APX / SFIM) hanging in front of him from the sight
    housing over his seat, the sight's hand control on the centre pedestal, the gun / weapons switches and the stores display on his panel;
  * the centre pedestal (radio, fuel flow control, rotor brake), an overhead switch panel, side placards, a floor.
No HUD (the Gazelle had none) and no reflector sight (the roof sight is the observer's; no reference shows a pilot's reflector sight).
Instruments the game draws (CockpitGauges, FlightDisplays) are live for the seat that sees them (pilot: Seat 0, observer: Seat 1).

Imports only cockpit_lib and free_atlas (the building blocks below are inlined, as vulcan_b2.py's).
Run on the airframe's originals (gazelle/run_gazelle_cockpit.ps1 restores them), after canopy_sa341f_gazelle.py.
Usage: python3 tools/cockpit_models/sa341f_gazelle.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
THR = []                                 # the throttle levers (throttle_lib), one bone each

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import free_atlas as FA   # noqa: E402
import canopy_sa341f_gazelle as CM   # noqa: E402
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

# ---------------------------------------------------------------- building blocks (inlined: this script uses only cockpit_lib and free_atlas)
RIM = 0.16                     # game gauge bezel width (CockpitGauges.RIM)
GAUGE_LIFT = 0.004             # game dials stand this far proud of the panel face
DISPLAY_LIFT = 0.003
WHITE = (226, 228, 220)


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
                i, j = [m for m in range(3) if m != k]
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

    def cyl(self, p0, p1, r, rect, seg=8):
        p0, p1 = np.array(p0, float), np.array(p1, float)
        t = unit(p1 - p0)
        a = np.array([0, 1.0, 0]) if abs(t[1]) < 0.9 else np.array([1.0, 0, 0])
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

    def disc(self, centre, normal, up, radius, rect, seg=20):
        c, n, u, rr = frame(centre, normal, up)
        pts, uvs = [], []
        for k in range(seg):
            a = 2 * math.pi * k / seg
            pts.append(c + (rr * math.cos(a) + u * math.sin(a)) * radius)
            uvs.append(self.uv(rect, 0.5 + 0.5 * math.cos(a), 0.5 + 0.5 * math.sin(a)))
        cuv = self.uv(rect, 0.5, 0.5)
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


def font(px, bold=False):
    for name in (('arialbd.ttf', 'DejaVuSans-Bold.ttf') if bold else ('arial.ttf', 'DejaVuSans.ttf')):
        try:
            return ImageFont.truetype(name, max(6, int(px)))
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


def knob(d, x, y, r, ang=0.7):
    d.ellipse([x - r, y - r, x + r, y + r], fill=(30, 30, 32, 255), outline=(170, 170, 164, 255), width=3)
    d.line([x, y, x + r * 0.8 * math.sin(ang), y - r * 0.8 * math.cos(ang)], fill=WHITE + (255,), width=4)


def lamp(d, box, colour, label=None, lit=False, px=None):
    x0, y0, x1, y1 = box
    base = tuple(int(c * (1.0 if lit else 0.45)) for c in colour)
    d.rectangle(box, fill=base + (255,), outline=(10, 10, 10, 255), width=2)
    d.rectangle([x0 + 3, y0 + 3, x1 - 3, y0 + (y1 - y0) * 0.35], fill=tuple(min(255, c + 30) for c in base) + (255,))
    if label:
        text(d, ((x0 + x1) / 2, (y0 + y1) / 2), label, px or (y1 - y0) * .42, fill=(20, 20, 20))


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


def write(ac, atlas, asset, geo):                 # (this script's own: LF line ends, as the airframe's files)
    with open(ac.geo_path, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(geo, fh, separators=(',', ':'))
        fh.write('\n')
    glass_json = asset.pop('CanopyGlass', None)
    text_ = json.dumps(asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text_ = text_[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        asset['CanopyGlass'] = glass_json
    with open(ac.asset_path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text_ + '\n')
    tex = os.path.normpath(L.texture_path(ac.vid))
    atlas.write(tex)
    dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
    if os.path.isfile(dead):
        atlas.write(dead, dead=True)


VID = 'sa341f_gazelle'
COCKPIT_BONE = 'hull__cockpit'
HULL_BONE = 'hull'

# colours: French Army Gazelle cabin - black instrument panel, dark grey linings, olive-drab seats with black harness
PANEL = (34, 36, 37)
PANEL_D = (22, 24, 25)
BLACK = (18, 19, 21)
DGREY = (46, 48, 50)
MGREY = (92, 96, 98)
LGREY = (150, 154, 156)
FLOORC = (52, 54, 52)
LINING = (70, 74, 70)
FRAME = (58, 62, 58)
CUSHION = (66, 72, 54)          # olive drab seat cloth
HARNESS = (28, 30, 26)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
RUBBER = (14, 14, 15)
AMBER = (214, 140, 30)

# ---------------------------------------------------------------- the measured cabin
FLOOR = 0.575                  # cockpit floor plate top (the old slabs top out at 0.569)
SEAT_X = 0.465                 # pilot at -SEAT_X (right seat), observer at +SEAT_X
EYE_Z = 3.455
PAN_BOT, PAN_TOP = 0.955, 1.290          # the panel face's lower and upper edge (y)
PAN_HALF = 0.655                         # its half width (x)
LEAN = math.radians(14.0)                # the face leans back (its top edge away from the crew)
TAN = math.tan(LEAN)
Z_PAN = 3.900                            # face z at y = PAN_BOT
U_PAN = unit([0, math.cos(LEAN), math.sin(LEAN)])
N_PAN = unit([0, math.sin(LEAN), -math.cos(LEAN)])     # toward the crew
COWL_Y = 1.298                           # the glareshield's top
BELT_A = CM.A_at(1.330)                      # the belt bar's outer end (the A pillar), left side
BELT_W = CM.WEDGE                        # ... the keel wedge corner


def z_face(y):
    return Z_PAN + TAN * (y - PAN_BOT)


def _glass_line():
    """(x, z) of the windscreen glass where it crosses the glareshield's height, x >= 0, sorted by x"""
    pts = []
    for t in CM.nose_panes():
        for i in range(3):
            a, b = np.array(t[i]), np.array(t[(i + 1) % 3])
            if (a[1] - COWL_Y) * (b[1] - COWL_Y) < 0:
                f = (COWL_Y - a[1]) / (b[1] - a[1])
                q = a + (b - a) * f
                pts.append((q[0], q[2]))
    pts.sort()
    return np.array(pts)


_GL = _glass_line()


def z_chord(x):
    """z of the glass at the glareshield's height at |x| (the cowl's front edge stops short of it)"""
    return float(np.interp(abs(x), _GL[:, 0], _GL[:, 1]))


def pan_at(x, y, lift=0.0):
    return np.array([x, y, z_face(y)]) + N_PAN * lift


# ---------------------------------------------------------------- instrument layout (absolute x; y)
# (name, seat, kind, x, y, diameter): drawn by the game; the painted captions are in the panel's tile
XP, XO = -SEAT_X, SEAT_X
DX = 0.125                                # dial spacing in the pilot's and the observer's groups
GAUGES = [
    ('adi', 0, 'ATTITUDE', XP, 1.205, 0.120, 'HORIZON'),
    ('asi', 0, 'AIRSPEED', XP + DX, 1.212, 0.095, 'VITESSE'),
    ('alt', 0, 'ALTIMETER', XP - DX, 1.212, 0.095, 'ALTIMETRE'),
    ('hdg', 0, 'HEADING', XP, 1.083, 0.092, 'CAP'),
    ('vvi', 0, 'VVI', XP + DX, 1.083, 0.084, 'VARIO'),
    ('turn', 0, 'TURN_SLIP', XP - DX, 1.083, 0.084, 'BILLE'),
    ('tq', 0, 'TORQUE', -0.108, 1.215, 0.090, 'COUPLE'),
    ('nr', 0, 'ROTOR_RPM', 0.0, 1.215, 0.090, 'Nr  Ng'),
    ('ng', 0, 'ENGINE_RPM', 0.108, 1.215, 0.090, 'GG'),
    ('tot', 0, 'EGT', -0.108, 1.105, 0.085, 'T4'),
    ('fuel', 0, 'FUEL_QTY', 0.0, 1.105, 0.085, 'CARBURANT'),
    ('clk', 0, 'CLOCK', 0.108, 1.105, 0.085, 'HORLOGE'),
    ('oil', 0, 'FILLER_OIL', -0.108, 1.012, 0.064, 'HUILE'),
    ('hyd', 0, 'FILLER_HYDRAULIC', 0.0, 1.012, 0.064, 'HYDR'),
    ('vlt', 0, 'FILLER_VOLTS', 0.108, 1.012, 0.064, 'VOLTS'),
    ('ammo0', 0, 'AMMO', XP + DX, 0.997, 0.058, 'MUNITIONS'),
    ('o_alt', 1, 'ALTIMETER', XO - DX, 1.212, 0.090, 'ALTIMETRE'),
    ('o_hdg', 1, 'HEADING', XO, 1.212, 0.090, 'CAP'),
    ('o_asi', 1, 'AIRSPEED', XO + DX, 1.212, 0.090, 'VITESSE'),
    ('o_ammo', 1, 'AMMO', XO - DX, 1.092, 0.072, 'MUNITIONS'),
    ('o_tq', 1, 'TORQUE', XO + DX, 1.092, 0.080, 'COUPLE'),
]
DISPLAYS = [('o_sto', 1, 'STORES', XO, 1.085, 0.105, 'CRT', 'ARMEMENT')]
PAINT_RECTS = [('radio', XP - 0.060, 0.997, 0.250, 0.055), ('arm', XO, 0.997, 0.340, 0.058)]
ANN = [('ann_p', 0, XP, COWL_Y, 4.060, 0.300, 0.046, 6), ('ann_o', 1, XO, COWL_Y, 4.060, 0.200, 0.040, 5)]

LAMPS_P = [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('ALARME', 'MASTER_CAUTION', 'amber'), ('PANNE|MOTEUR', 'ENGINE_OUT', 'red'), ('FEU', 'FIRE', 'red'),
    ('NIVEAU|CARB', 'LOW_FUEL', 'amber'), ('Nr|BAS', 'ROTOR_RPM_LOW', 'red'), ('Nr|HAUT', 'ROTOR_RPM_HIGH', 'amber'),
    ('COUPLE|MAX', 'TORQUE_HIGH', 'amber'), ('MOTEUR|MARCHE', 'ENGINE_RUN', 'green'), ('PRESS|HUILE', 'NONE', 'amber'),
    ('PRESS|HYDR', 'NONE', 'amber'), ('GENE', 'NONE', 'amber'), ('MUNITIONS|BAS', 'AMMO_LOW', 'amber')]]
LAMPS_O = [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('ALARME', 'MASTER_CAUTION', 'amber'), ('FEU', 'FIRE', 'red'), ('NIVEAU|CARB', 'LOW_FUEL', 'amber'),
    ('ALERTE|MISSILE', 'MISSILE_LAUNCH', 'red'), ('MUNITIONS|BAS', 'AMMO_LOW', 'amber')]]


# ---------------------------------------------------------------- painted faces
def dial_face(d, im, title, lo, hi, step, label_step, value, labels=None, red=None, start=-135, sweep=270):
    S = im.width; c = S / 2; R = S / 2 - S * 0.02
    d.ellipse([c - R, c - R, c + R, c + R], fill=(14, 15, 16, 255))

    def ang(v):
        return math.radians(start + (v - lo) / (hi - lo) * sweep)
    if red:
        for v0 in np.arange(red[0], red[1], (hi - lo) / 200):
            a = ang(v0)
            d.line([c + R * .80 * math.sin(a), c - R * .80 * math.cos(a), c + R * .93 * math.sin(a), c - R * .93 * math.cos(a)],
                   fill=RED + (255,), width=int(S * .02))
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


def panel_face(W_, H_, x0, x1, yb, yt):
    """the panel's face: dark with the dials' backing discs, the captions in French, the group outlines and screws"""
    def fn(d, im):
        SS = Atlas.SS
        noisy(d, im, PANEL, 3, seed=5)

        def to_px(x, y):
            return (x1 - x) / (x1 - x0) * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        px_m = W_ * SS / (x1 - x0)
        cos_f = math.cos(LEAN)
        groups = [(-0.645, 1.282, -0.255, 0.962, 'PILOTE'), (-0.165, 1.282, 0.165, 0.962, 'MOTEUR  ROTOR'),
                  (0.255, 1.282, 0.645, 0.962, 'OBSERVATEUR')]
        for xa, ya, xb, yb2, title in groups:
            ax, ay = to_px(xa, ya); bx, by = to_px(xb, yb2)
            d.rectangle([min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)], outline=(90, 94, 96, 255), width=SS * 2)
        for name, seat, kind, x, y, dia, cap in GAUGES:
            cx, cy = to_px(x, y)
            r = (dia / 2 * (1 + RIM) + 0.004) * px_m
            d.ellipse([cx - r, cy - r * 1.0, cx + r, cy + r * 1.0], fill=(14, 17, 17, 255))
            text(d, (cx, cy + r + SS * 4.5), cap, SS * 6.2, fill=(176, 178, 172, 255))
        for name, seat, kind, x, y, w, style, cap in DISPLAYS:
            cx, cy = to_px(x, y)
            r = (w / 2 * 1.30 + 0.004) * px_m
            d.rectangle([cx - r, cy - r, cx + r, cy + r], fill=(14, 17, 17, 255))
            text(d, (cx, cy + r + SS * 4.5), cap, SS * 6.2, fill=(176, 178, 172, 255))
        for name, x, y, w, h in PAINT_RECTS:
            cx, cy = to_px(x, y)
            sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m / cos_f
            d.rectangle([cx - sx, cy - sy, cx + sx, cy + sy], fill=(14, 17, 17, 255))
        for (a, y) in [(x0 + 0.012, yb + 0.010), (x1 - 0.012, yb + 0.010), (x0 + 0.012, yt - 0.010), (x1 - 0.012, yt - 0.010),
                       (-0.21, yt - 0.010), (0.21, yt - 0.010), (-0.21, yb + 0.010), (0.21, yb + 0.010)]:
            x_, y_ = to_px(a, y); r = SS * 1.8
            d.ellipse([x_ - r, y_ - r, x_ + r, y_ + r], fill=MGREY + (255,), outline=(20, 20, 20, 255))
        for xg, ttl in ((-0.46, 'PILOTE'), (0.0, 'MOTEUR  ROTOR'), (0.46, 'OBSERVATEUR')):
            cx, cy = to_px(xg, 1.2765)
            text(d, (cx, cy), ttl, SS * 6.0, fill=(150, 154, 150, 255), bold=True)
    return fn


def p_radio(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 22, 24, 255))
    text(d, (S * .5, H * .20), 'RADIO / INTERPHONE', H * .24, fill=(200, 204, 196))
    for k, lab in enumerate(['VHF', 'FM', 'UHF', 'ICS']):
        x = S * (0.14 + 0.24 * k)
        knob(d, x, H * .62, H * .20, ang=0.4 + 0.5 * k)
        text(d, (x, H * .93), lab, H * .16)


def p_arm(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 22, 24, 255))
    text(d, (S * .5, H * .15), 'ARMEMENT  -  TIR', H * .22, fill=(200, 204, 196), bold=True)
    for k, lab in enumerate(['CANON', 'ROQUET.', 'HOT', 'MISTRAL']):
        x = S * (0.10 + 0.17 * k)
        d.rectangle([x - S * .07, H * .30, x + S * .07, H * .66], fill=(40, 130, 56, 255) if k == 2 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .48), lab, H * .20, fill=(10, 30, 10))
    d.rectangle([S * .78, H * .30, S * .93, H * .66], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .855, H * .48), 'ARME', H * .22, fill=(235, 235, 228), bold=True)
    text(d, (S * .5, H * .86), 'SELECTION          SECURITE', H * .15)


def p_pedestal(d, im):
    """the centre pedestal's top seen from above (tile top = forward)"""
    S, H = im.width, im.height
    noisy(d, im, PANEL, 4, seed=21)
    d.rectangle([S * .08, H * .06, S * .92, H * .34], fill=(20, 22, 24, 255), outline=(70, 72, 74, 255), width=3)
    text(d, (S * .5, H * .12), 'RADIO', H * .06, fill=(200, 204, 196))
    for k in range(3):
        knob(d, S * (0.24 + 0.26 * k), H * .24, S * .08, ang=0.3 + 0.7 * k)
    d.rectangle([S * .26, H * .46, S * .74, H * .86], fill=(10, 10, 10, 255), outline=(90, 94, 96, 255), width=3)
    d.rectangle([S * .46, H * .50, S * .54, H * .84], fill=(4, 4, 4, 255))
    text(d, (S * .5, H * .43), 'DEBIT CARBURANT', H * .045, fill=(200, 204, 196))
    text(d, (S * .30, H * .93), 'COUPE', H * .045)
    text(d, (S * .70, H * .93), 'VOL', H * .045)
    text(d, (S * .88, H * .62), 'FREIN|ROTOR'.replace('|', ' '), H * .035, fill=(200, 204, 196))


def p_overhead(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=33)
    for k, lab in enumerate(['PLAFOND', 'ALIM', 'ANTICOL', 'PITOT', 'FEUX']):
        x = S * (0.12 + 0.19 * k)
        toggle(d, x, H * .42, S * 1.3)
        text(d, (x, H * .80), lab, H * .16)


def p_placard(lines, bg=YELLOW, fg=(20, 20, 20)):
    def fn(d, im):
        S, H = im.width, im.height
        d.rectangle([0, 0, S, H], fill=bg + (255,))
        d.rectangle([3, 3, S - 4, H - 4], outline=fg + (255,), width=4)
        for k, s in enumerate(lines):
            text(d, (S * .5, H * (k + 1) / (len(lines) + 1)), s, H * 0.62 / len(lines), fill=fg, bold=True)
    return fn


def p_cushion(colour, straps=False, seed=50):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 5, seed=seed)
        for k in range(1, 4):
            d.line([0, H * k / 4, S, H * k / 4], fill=tuple(max(0, c - 16) for c in colour) + (255,), width=4)
        if straps:
            for x in (S * .28, S * .72):
                d.rectangle([x - S * .05, 0, x + S * .05, H], fill=HARNESS + (255,))
            d.rectangle([S * .38, H * .55, S * .62, H * .70], fill=(150, 150, 150, 255))
    return fn


def p_lens(d, im):
    """the sight's eyepiece window: dark glass with the reticle (a painted face)"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(6, 12, 8, 255))
    c = (S / 2, H / 2)
    g = (60, 190, 90, 255)
    d.ellipse([c[0] - S * .30, c[1] - S * .30, c[0] + S * .30, c[1] + S * .30], outline=g, width=3)
    d.line([c[0] - S * .42, c[1], c[0] - S * .08, c[1]], fill=g, width=3)
    d.line([c[0] + S * .08, c[1], c[0] + S * .42, c[1]], fill=g, width=3)
    d.line([c[0], c[1] - S * .42, c[0], c[1] - S * .08], fill=g, width=3)
    d.line([c[0], c[1] + S * .08, c[0], c[1] + S * .42], fill=g, width=3)
    d.ellipse([c[0] - 3, c[1] - 3, c[0] + 3, c[1] + 3], fill=g)


def p_sight_side(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=34)
    text(d, (S * .5, H * .3), 'APX  M397', H * .30, fill=(150, 154, 150), bold=True)
    text(d, (S * .5, H * .72), 'VISEE  OBSERVATEUR', H * .20, fill=(150, 154, 150))


def p_grip_ctl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=35)
    text(d, (S * .5, H * .5), 'VISEE', H * .5, fill=(176, 178, 172), bold=True)


def p_side_panel(lines, w_h):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, LINING, 3, seed=40)
        d.rectangle([4, 4, S - 5, H - 5], outline=(110, 114, 108, 255), width=4)
        for k, s in enumerate(lines):
            text(d, (S * .5, H * (k + 1) / (len(lines) + 1)), s, H * .60 / len(lines), fill=(220, 222, 214), bold=True)
    return fn


def p_wall(colour, seed):
    def fn(d, im):
        noisy(d, im, colour, 4, seed=seed)
    return fn


def p_floor(d, im):
    S, H = im.width, im.height
    noisy(d, im, FLOORC, 4, seed=80)
    for k in range(0, S, 24):
        d.line([k, 0, k, H], fill=(44, 46, 44, 255), width=2)
    for k in range(0, H, 24):
        d.line([0, k, S, k], fill=(44, 46, 44, 255), width=2)


# ---------------------------------------------------------------- build
def build(atlas):
    m = Mesh(atlas)
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lgrey=LGREY,
                                                floor=FLOORC, lining=LINING, frame=FRAME, cushion=CUSHION, harness=HARNESS,
                                                yellow=YELLOW, red=RED, metal=METAL, rubber=RUBBER, bezel=(26, 27, 29)).items()}
    gauges, displays = [], []
    # ===================================================================== floor
    floor_tile = atlas.paint(96, 96, p_floor)

    def fl_poly(side):
        pts = [(0.0, 3.10), (0.82, 3.10), (0.82, 3.34), (0.715, 3.34), (0.715, 3.858), (0.62, 3.858), (0.62, 4.20),
               (0.0, 4.20)]
        return [[side * x, FLOOR, z] for x, z in pts]
    for s in (1, -1):
        m.flat(fl_poly(s), floor_tile, normal=[0, 1, 0])

    # ===================================================================== the dash: a low central panel under the glareshield
    ppm = 740
    W_ = int(round(2 * PAN_HALF * ppm))
    H_ = int(round((PAN_TOP - PAN_BOT) / math.cos(LEAN) * ppm))
    ptile = atlas.paint(W_, H_, panel_face(W_, H_, -PAN_HALF, PAN_HALF, PAN_BOT, PAN_TOP))
    lift = 0.003
    pb = [pan_at(-PAN_HALF, PAN_BOT, lift), pan_at(PAN_HALF, PAN_BOT, lift), pan_at(PAN_HALF, PAN_TOP, lift), pan_at(-PAN_HALF, PAN_TOP, lift)]
    # (the quad's left is the pilot's right: x runs from -PAN_HALF at the tile's left to +PAN_HALF at its right as seen from the front;
    # the crew look along +z, so the tile reads left to right as x decreases: the corners are laid in that order)
    m.quad_tex([pb[1], pb[0], pb[3], pb[2]], ptile, normal=N_PAN)
    # the panel's box: sides, underside, front wall, and the glareshield over it reaching the belt bar's line
    DEPTH = 0.10
    zp = lambda y: z_face(y)
    for s in (1, -1):
        m.flat([[s * PAN_HALF, PAN_BOT, zp(PAN_BOT)], [s * PAN_HALF, PAN_BOT, zp(PAN_BOT) + DEPTH],
                [s * PAN_HALF, COWL_Y, zp(COWL_Y) + DEPTH], [s * PAN_HALF, COWL_Y, zp(COWL_Y)]], sw['panel_d'], normal=[s, 0, 0])
    m.flat([[-PAN_HALF, PAN_BOT, zp(PAN_BOT)], [PAN_HALF, PAN_BOT, zp(PAN_BOT)], [PAN_HALF, PAN_BOT, zp(PAN_BOT) + DEPTH],
            [-PAN_HALF, PAN_BOT, zp(PAN_BOT) + DEPTH]], sw['panel_d'], normal=[0, -1, 0])
    m.flat([[-PAN_HALF, PAN_BOT, zp(PAN_BOT) + DEPTH], [PAN_HALF, PAN_BOT, zp(PAN_BOT) + DEPTH],
            [PAN_HALF, COWL_Y - 0.04, zp(COWL_Y - 0.04) + DEPTH], [-PAN_HALF, COWL_Y - 0.04, zp(COWL_Y - 0.04) + DEPTH]],
           sw['panel_d'], normal=[0, 0, 1])
    # the glareshield: a black plate over the panel (its top at COWL_Y) from the panel's top edge out to the belt line, with a lip
    xs = [PAN_HALF, 0.60, 0.50, 0.40, 0.30, BELT_W[0] + 0.03, 0.0]
    top = [(x, z_chord(x) - 0.045) for x in xs]
    zt = z_face(COWL_Y)
    cowl = [[-PAN_HALF, COWL_Y, zt]] + [[-x, COWL_Y, z] for x, z in top[:-1]] + [[0.0, COWL_Y, z_chord(0.0) - 0.045]] \
        + [[x, COWL_Y, z] for x, z in top[:-1][::-1]] + [[PAN_HALF, COWL_Y, zt]]
    # cowl polygon: from the left panel corner along the front edge to the right panel corner (convex enough for a fan)
    m.flat(cowl, sw['black'], normal=[0, 1, 0])
    cow_dn = [[p[0], COWL_Y - 0.040, p[2]] for p in cowl]
    m.flat(cow_dn, sw['panel_d'], normal=[0, -1, 0])
    for a, b in zip(cowl[1:-2], cowl[2:-1]):                  # the cowl's front lip, between its top and its underside
        m.flat([[a[0], COWL_Y, a[2]], [b[0], COWL_Y, b[2]], [b[0], COWL_Y - 0.04, b[2]], [a[0], COWL_Y - 0.04, a[2]]], sw['black'],
               toward=[(a[0] + b[0]) / 2, COWL_Y - 0.02, (a[2] + b[2]) / 2 + 1.0])
    # the visor lip over the panel (a shade 4 cm over its top edge)
    m.flat([[-PAN_HALF, COWL_Y, zt], [PAN_HALF, COWL_Y, zt], [PAN_HALF, COWL_Y - 0.040, zt - 0.025], [-PAN_HALF, COWL_Y - 0.040, zt - 0.025]],
           sw['black'], normal=[0, 0.3, -1])
    # kick plate under the panel down to the floor at the sides (a half-height cheek each side of the footwell)
    for s in (1, -1):
        m.flat([[s * PAN_HALF, FLOOR, zp(PAN_BOT) - 0.02], [s * PAN_HALF, FLOOR, zp(PAN_BOT) + 0.14], [s * PAN_HALF, PAN_BOT, zp(PAN_BOT) + 0.14],
                [s * PAN_HALF, PAN_BOT, zp(PAN_BOT) - 0.02]], sw['panel_d'], normal=[-s, 0, 0])
    # ---- the dials (game) and the display, the painted panels
    for name, seat, kind, x, y, dia, cap in GAUGES:
        c = pan_at(x, y)
        gauges.append({'Seat': seat, 'Kind': kind, 'Center': r5(c + N_PAN * GAUGE_LIFT), 'Normal': r5(N_PAN), 'Up': r5(U_PAN),
                       'Diameter': dia, 'Depth': 0.03})
        m.ring(c + N_PAN * lift, N_PAN, U_PAN, dia / 2, dia / 2 * (1 + RIM), GAUGE_LIFT + 0.006 - lift, sw['bezel'])
    for name, seat, kind, x, y, w, style, cap in DISPLAYS:
        c = pan_at(x, y)
        displays.append({'Seat': seat, 'Center': r5(c + N_PAN * DISPLAY_LIFT), 'Normal': r5(N_PAN), 'Up': r5(U_PAN), 'Width': w,
                         'Depth': 0.035, 'Kind': kind, 'Style': style})
        _, n_, u_, r_ = frame(c, N_PAN, U_PAN)
        pr, t = 0.010, 0.022
        for dy in (1, -1):
            m.box(c + u_ * dy * (w / 2 + t / 2) + n_ * pr / 2, (r_, u_, n_), (w / 2 + t, t / 2, pr / 2), sw['bezel'])
        for dx in (1, -1):
            m.box(c + r_ * dx * (w / 2 + t / 2) + n_ * pr / 2, (r_, u_, n_), (t / 2, w / 2, pr / 2), sw['bezel'])
    painters = {'radio': p_radio, 'arm': p_arm}
    for name, x, y, w, h in PAINT_RECTS:
        c = pan_at(x, y)
        pw = 200
        rect = atlas.paint(pw, max(16, int(round(pw * h / w))), painters[name])
        # (the panel's x runs right to left as seen from the crew: a tile's right is -x)
        m.rect_tex(c + N_PAN * 0.0055, N_PAN, U_PAN, w, h, rect)
        m.frame_rect(c, N_PAN, U_PAN, w, h, 0.004, 0.007, sw['bezel'])
    # ---- the caution panels on the glareshield (lit by the game), each on a sloped wedge facing its crew member
    PHI = math.radians(40.0)
    for name, seat, x, y, z, w, h, cols in ANN:
        u_ = unit([0, math.cos(PHI), math.sin(PHI)])
        n_ = unit([0, math.sin(PHI), -math.cos(PHI)])
        c = np.array([x, y + 0.5 * h * u_[1] + 0.004, z])
        gauges.append({'Seat': seat, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + n_ * 0.0045), 'Normal': r5(n_), 'Up': r5(u_),
                       'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                       'Lamps': LAMPS_P if seat == 0 else LAMPS_O})
        m.box(c - n_ * 0.014 - u_ * 0.0, (X, u_, n_), (w / 2 + 0.01, h / 2 + 0.008, 0.014), sw['black'])
        m.frame_rect(c, n_, u_, w, h, 0.005, 0.008, sw['bezel'])
    # ---- the legend strips along the glareshield's front edge (below the caution panels)
    # ===================================================================== the centre pedestal
    ped_tile = atlas.paint(150, 230, p_pedestal)
    px0, px1, pz0, pz1, py = -0.14, 0.14, 3.30, 3.92, 0.86
    m.face([[px0, py, pz1], [px1, py, pz1], [px1, py, pz0], [px0, py, pz0]],
           [atlas.uv(ped_tile, 1, 1), atlas.uv(ped_tile, 0, 1), atlas.uv(ped_tile, 0, 0), atlas.uv(ped_tile, 1, 0)], normal=[0, 1, 0])
    for s in (1, -1):
        m.flat([[s * px1, FLOOR, pz0], [s * px1, FLOOR, pz1], [s * px1, py, pz1], [s * px1, py, pz0]], sw['panel_d'], normal=[s, 0, 0])
    m.flat([[px0, FLOOR, pz0], [px1, FLOOR, pz0], [px1, py, pz0], [px0, py, pz0]], sw['panel_d'], normal=[0, 0, -1])
    m.flat([[px0, FLOOR, pz1], [px1, FLOOR, pz1], [px1, py, pz1], [px0, py, pz1]], sw['panel_d'], normal=[0, 0, 1])
    # the fuel flow control: a lever in its slot (tile slot x .26-.74, y .46-.86), its red knob; the rotor brake lever at the side
    tz = pz1 - (pz1 - pz0) * 0.62
    lev = (X, unit([0, 1, -0.25]), unit([0, 0.25, 1]))
    m.box([0.0, py + 0.045, tz], lev, (0.005, 0.05, 0.005), sw['metal'])
    m.box([0.0, py + 0.108, tz - 0.012], lev, (0.014, 0.014, 0.014), sw['red'])
    m.box([0.115, py + 0.030, pz0 + 0.20], (X, unit([0, 1, 0.3]), unit([0, -0.3, 1])), (0.007, 0.045, 0.007), sw['metal'])
    m.box([0.115, py + 0.080, pz0 + 0.215], (X, Y, Z), (0.014, 0.014, 0.018), sw['black'])
    # ===================================================================== seats
    cush = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    for xc, tag in ((-SEAT_X, 'p'), (SEAT_X, 'o')):
        m.box([xc, FLOOR + 0.07, 3.40], (X, Y, Z), (0.19, 0.07, 0.21), sw['frame'])                    # the base (y .575 - .715)
        m.box([xc, 0.745, 3.42], (X, Y, Z), (0.205, 0.030, 0.24), sw['cushion'], front=(1, 1, cush, -X, Z))   # the pan (top .775)
        ub = unit([0, math.cos(math.radians(10)), -math.sin(math.radians(10))])
        nb = unit([0, math.sin(math.radians(10)), math.cos(math.radians(10))])
        m.box([xc, 1.180, 3.135], (X, ub, nb), (0.215, 0.40, 0.035), sw['cushion'], front=(2, 1, backt, -X, ub))   # the back
        m.box([xc, 1.625, 3.075], (X, ub, nb), (0.105, 0.115, 0.040), sw['cushion'])                  # the headrest (top ~ y 1.74)
        for s in (1, -1):
            m.box([xc + s * 0.225, 0.92, 3.36], (X, Y, Z), (0.014, 0.13, 0.20), sw['frame'])           # side bolsters / arm supports
            m.box([xc + s * 0.095, 1.20, 3.168], (X, ub, nb), (0.026, 0.32, 0.004), sw['harness'])    # shoulder straps
            m.box([xc + s * 0.12, 0.792, 3.52], (X, Y, Z), (0.050, 0.004, 0.012), sw['harness'])      # lap belt halves
        m.box([xc, 0.796, 3.52], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])                        # the buckle
    # ===================================================================== pilot: cyclic, collective, pedals
    xc = -SEAT_X
    FOOT = np.array([xc, FLOOR + 0.004, 3.88])
    m.box(FOOT + [0, 0.014, 0], (X, Y, Z), (0.055, 0.014, 0.055), sw['dgrey'])                         # the boot
    m.box(FOOT + [0, 0.05, 0], (X, Y, Z), (0.032, 0.035, 0.032), sw['black'])
    col_ax = (X, unit([0, 1, -0.17]), unit([0, 0.17, 1]))
    m.box(FOOT + col_ax[1] * 0.225, col_ax, (0.011, 0.185, 0.011), sw['metal'])                        # the stick
    gc = FOOT + col_ax[1] * 0.43 + col_ax[2] * 0.01
    g_ax = (X, unit([0, 1, -0.30]), unit([0, 0.30, 1]))
    m.box(gc, g_ax, (0.022, 0.078, 0.030), sw['black'])                                                # the grip
    m.box(gc + g_ax[1] * 0.080, g_ax, (0.019, 0.006, 0.025), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.090 + g_ax[2] * 0.006, g_ax, (0.005, 0.004, 0.005), sw['red'])             # release
    m.box(gc + g_ax[2] * 0.032 + g_ax[1] * 0.020, g_ax, (0.007, 0.018, 0.005), sw['metal'])           # trigger
    m.box(gc + g_ax[1] * 0.040 + g_ax[2] * 0.027, g_ax, (0.012, 0.006, 0.006), sw['mgrey'])           # weapon switch
    m.box(gc - g_ax[2] * 0.031 + g_ax[1] * 0.034, g_ax, (0.012, 0.016, 0.003), sw['dgrey'])           # trim hat
    # the collective: a lever on his left (the centre side) from a base on the floor, the grip with the twist throttle
    CB = np.array([xc + 0.265, FLOOR + 0.030, 3.30])
    CG = np.array([xc + 0.265, FLOOR + 0.375, 3.62])
    m.box(CB - [0, 0.012, 0], (X, Y, Z), (0.030, 0.026, 0.030), sw['dgrey'])
    # The collective (the "throttle" lever) is its own bone (throttle_lever), hinged on its floor base, modelled full down
    # (throttle_lib.collective); the twist grip is the throttle.
    arm = CG - CB
    THR.clear()
    THR.append(throttle_lib.collective(lambda: Mesh(atlas), sw, 'throttle_lever', CB, float(math.hypot(arm[1], arm[2])), 30.0,
                                       math.degrees(math.atan2(0.20, 1.0)), grip_len=0.215, travel=30.0, lateral=float(arm[0])))
    # rudder pedals (both seats get the dual set, the pilot's the working one)
    for xs_ in (xc, SEAT_X):
        for s in (1, -1):
            zk = 3.86
            m.box([xs_ + s * 0.13, FLOOR + 0.15, zk], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
            m.box([xs_ + s * 0.13, FLOOR + 0.09, zk + 0.025], (X, Y, Z), (0.014, 0.055, 0.025), sw['dgrey'])
    # ===================================================================== observer: the sight eyepiece, hand control, switches
    lens = atlas.paint(96, 96, p_lens)
    side = atlas.paint(120, 50, p_sight_side)
    ex, ey, ez = 0.36, 1.745, 3.620
    # the tube from the roof (the sight housing's underside) down to the eyepiece
    m.box([ex, 1.805, ez - 0.01], (X, Y, Z), (0.032, 0.054, 0.032), sw['dgrey'])
    m.box([ex, ey, ez], (X, Y, Z), (0.050, 0.042, 0.058), sw['dgrey'])                                  # the eyepiece body
    for s in (1, -1):
        m.rect_tex([ex + s * 0.0505, ey, ez + 0.0], [s, 0, 0], Y, 0.095, 0.050, side)                   # the legend, both sides
    ecen = np.array([ex, ey, ez - 0.058 - 0.002])
    seg = 8
    for k in range(seg):
        a0, a1 = 2 * math.pi * k / seg, 2 * math.pi * (k + 1) / seg
        d0 = X * math.cos(a0) + Y * math.sin(a0); d1 = X * math.cos(a1) + Y * math.sin(a1)
        r_in, r_out, out = 0.022, 0.036, 0.030
        p0, p1, p2, p3 = ecen + d0 * r_in, ecen + d1 * r_in, ecen + d1 * r_out, ecen + d0 * r_out
        m.flat([p0 - Z * out, p1 - Z * out, p2 - Z * out, p3 - Z * out], sw['rubber'], normal=-Z)       # the rubber eyecup toward his face
        m.flat([p3 - Z * out, p2 - Z * out, p2, p3], sw['rubber'], normal=(d0 + d1) / 2)
        m.flat([p0 - Z * out, p0, p1, p1 - Z * out], sw['rubber'], normal=-(d0 + d1) / 2)
    m.rect_tex(ecen - Z * 0.002, -Z, Y, 0.044, 0.044, lens)
    # the hand control on the pedestal's left edge: a stalk and a mushroom grip with its thumb switch
    gtile = atlas.paint(80, 28, p_grip_ctl)
    m.box([0.185, py + 0.05, 3.60], (X, Y, Z), (0.035, 0.050, 0.040), sw['dgrey'])
    m.box([0.205, py + 0.140, 3.605], (X, unit([0, 1, 0.15]), unit([0, -0.15, 1])), (0.010, 0.050, 0.010), sw['metal'])
    m.box([0.205, py + 0.215, 3.615], (X, unit([0, 1, 0.15]), unit([0, -0.15, 1])), (0.026, 0.032, 0.030), sw['black'])
    m.box([0.205, py + 0.252, 3.620], (X, Y, Z), (0.012, 0.004, 0.014), sw['red'])
    m.rect_tex([0.205, py + 0.215, 3.646], [0, 0.1, 1], Y, 0.04, 0.02, gtile)
    # ===================================================================== overhead switch panel, side placards
    ov = atlas.paint(180, 40, p_overhead)
    m.face([[-0.115, 1.857, 3.16], [0.115, 1.857, 3.16], [0.115, 1.857, 3.62], [-0.115, 1.857, 3.62]],
           [atlas.uv(ov, 0, 0), atlas.uv(ov, 1, 0), atlas.uv(ov, 1, 1), atlas.uv(ov, 0, 1)], normal=[0, -1, 0])
    # French placards: the emergency exits (red, on the rear sills' inner faces), no-step plates on the front sills' tops, the dash's title
    pl_exit = atlas.paint(120, 36, p_placard(['SORTIE DE SECOURS'], bg=RED, fg=(240, 240, 232)))
    pl_step = atlas.paint(120, 36, p_placard(['NE PAS MARCHER'], bg=YELLOW, fg=(20, 20, 20)))
    pl_ext = atlas.paint(120, 36, p_placard(['EXTINCTEUR'], bg=RED, fg=(240, 240, 232)))
    for s_ in (1, -1):
        m.rect_tex([s_ * 0.8285, 0.650, 3.10], [-s_, 0, 0], Y, 0.20, 0.060, pl_exit)
        m.rect_tex([s_ * 0.80, 0.7275, 3.60], [0, 1, 0], Z, 0.12, 0.040, pl_step)
    m.rect_tex([-0.1405, 0.74, 3.62], [-1, 0, 0], Y, 0.18, 0.05, pl_ext)
    return m, gauges, displays


# ---------------------------------------------------------------- the airframe
def planform(geo):
    """a top-down outline of the airframe for the stores page: the hull's widest |x| per z, as one closed polyline"""
    hull = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == HULL_BONE)
    P = np.array(hull['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
    z0, z1 = float(P[:, 2].min()), float(P[:, 2].max())
    zs = np.arange(z0 + 0.1, z1 - 0.05, (z1 - z0) / 40.0)
    left, right = [], []
    for z in zs:
        sel = P[np.abs(P[:, 2] - z) < 0.18]
        if len(sel) == 0:
            continue
        left.append((float(sel[:, 0].max()), float(z)))
        right.append((float(sel[:, 0].min()), float(z)))
    loop = left + right[::-1] + [left[0]]
    return [[round(v, 3) for p in loop for v in p]]


def install(ac, mesh, gauges, displays):
    pm = mesh.poly_mesh()
    cb = next((b for b in ac.bones if b['name'] == COCKPIT_BONE), None)
    if cb is None:
        hull = ac.bone(HULL_BONE)
        has = ac.bone('hull__canopy')
        at = ac.bones.index(has if has else hull) + 1
        cb = {'name': COCKPIT_BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        ac.bones.insert(at, cb)
    cb['poly_mesh'] = pm
    thr = throttle_lib.add_bones(ac.bones, THR, COCKPIT_BONE)
    print(f'sa341f_gazelle throttle bones {[(n, p) for n, p, _ in thr]}')
    a = ac.asset
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    a['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays, 'Planform': planform(ac.geo)}
    a.pop('HeadUpDisplay', None)               # the Gazelle has no head-up display
    return pm


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    atlas = Atlas(VID)
    mesh, gauges, displays = build(atlas)
    pm = install(ac, mesh, gauges, displays)
    print(f'{VID}: new cockpits {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} display(s); atlas '
          f'{sum(r[2] * r[3] for r, _ in atlas.tiles)} of {sum(a[2] * a[3] for a in atlas.areas)} texels')
    if not dry:
        write(ac, atlas, ac.asset, ac.geo)


if __name__ == '__main__':
    main(sys.argv[1:])
