#!/usr/bin/env python3
"""Mi-8AMTSh flight deck, built by hand for the Mi-8AMTSH.mtb airframe (commit e0494af5), after canopy_mi_8amtsh.py.

Real layout of the Mi-8 / Mi-17 family: the captain in the LEFT seat (x > 0), the co-pilot in the right (x < 0), the flight engineer on a folding jump
seat on the centre line behind them, in the passage through the bulkhead; the aircraft's blocks are 1.15 per metre of the real helicopter (x left,
y up, z forward). Measured off the model: floor top y 1.55 (1.50 behind the bulkhead), ceiling 3.10, the bulkhead's front face z 5.69 (its centre
door panel is cut by the canopy script so the engineer sees forward), the windscreen's lower edge y 2.39 (post) .. 2.27 (pillar) at z 7.47 .. 7.21,
the old interior (seats, instrument boxes, overhead panel, pedestal) cut. The captain's eye is moved from x 0.797 to x 0.55 (and the co-pilot's to
-0.55): the model's own seats and instrument boxes were centred on +-0.52 and its corner pillar stood in line with the old eye; the engineer's eye and
seat move onto the centre line. The edit is made to the data file (Attachments and the three seat positions) and is the same on every run.
Built:
  * the instrument panel across both pilots under a glareshield deck that runs forward to the glass's lower edge, in three groups: the captain's
    flight instruments (AGB attitude, speed, altitude, HSI, vertical speed, turn and slip, radio altimeter, clock, rounds), the TV3-117 engine and
    main-rotor group in the centre (Ng, torque, T4 for each engine, rotor rpm, fuel, oil, hydraulics), the co-pilot's flight instruments and the
    ATGM sight display (a live STORES page, FlightDisplays on his seat) with its hood; caution panels with Russian captions on the deck; placards in
    Russian throughout;
  * AMTSh additions: a weapons control panel on the centre pedestal (pylon selectors, rocket / gun pod / ATGM selector, arm and jettison covers),
    the Ataka guidance handle on the pedestal at the co-pilot's side;
  * the captain's PKV-class reflector sight on a stalk over the glareshield (HeadUpDisplay Style REFLECTOR on seat 0): the DCS Mi-8MTV2 manual and
    photographs show the PKV collimating sight on the captain's side of the Mi-8 / Mi-17 family; no reference seen shows it on the AMTSh
    specifically (a decision, see the report);
  * for each pilot: cyclic between the knees with its grip switches, collective on the outboard side with the twist throttle, rudder pedals, a seat
    with armour back and headrest and harness; the centre pedestal (radio, ICS), the overhead panel with the engine control quadrant, a floor mat;
  * the flight engineer's jump seat on the centre line and the engineer's panel on the back of the right bulkhead panel (fuel, engine, rotor,
    electrics: live on seat 2);
  * closing plates: the nose bay behind the dash is closed (double-sided plates) so nothing can be seen into it through the chin windows.
Imports only cockpit_lib and free_atlas (the building blocks below are inlined, as sa341f_gazelle.py's) and the canopy module for the glass lines.
Run on the airframe's originals (mi8/run_mi8_cockpit.ps1 restores them), after canopy_mi_8amtsh.py.
Usage: python3 tools/cockpit_models/mi_8amtsh.py [--dry-run]
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
import canopy_mi_8amtsh as CM   # noqa: E402
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

    def face(self, pts, uvs, toward=None, normal=None, double=False):
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
        for flip in ((False, True) if double else (False,)):
            Q, UQ, nq = (P[::-1], uvs[::-1], -n) if flip else (P, uvs, n)
            if len(Q) > 4:
                for k in range(1, len(Q) - 1):
                    self.faces.append((Q[[0, k, k + 1]], [UQ[0], UQ[k], UQ[k + 1]], nq))
            else:
                self.faces.append((Q, UQ, nq))

    def quad_tex(self, corners, rect, toward=None, normal=None):
        """corners: bottom-left, bottom-right, top-right, top-left as seen from the front"""
        self.face(corners, [self.uv(rect, 0, 0), self.uv(rect, 1, 0), self.uv(rect, 1, 1), self.uv(rect, 0, 1)],
                  toward=toward, normal=normal)

    def flat(self, pts, rect, toward=None, normal=None, double=False):
        u = self.uv(rect, 0.5, 0.5)
        self.face(pts, [u] * len(pts), toward=toward, normal=normal, double=double)

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
    SS = 3

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


VID = 'mi_8amtsh'
COCKPIT_BONE = 'hull__cockpit'
HULL_BONE = 'hull'

# colours: Soviet helicopter flight deck - black instrument panels, pale grey-green linings (the model's own), grey-green seat cloth, dark harness
PANEL = (30, 32, 32)
PANEL_D = (20, 22, 22)
BLACK = (16, 17, 18)
DGREY = (44, 47, 48)
MGREY = (90, 95, 96)
LGREY = (150, 154, 156)
MAT = (42, 46, 44)
LINING = (118, 130, 124)
FRAME = (58, 62, 58)
CUSHION = (92, 104, 88)         # grey-green seat cloth
HARNESS = (30, 32, 28)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
RUBBER = (14, 14, 15)
AMBER = (214, 140, 30)
SKIN = (40, 40, 40)

# ---------------------------------------------------------------- the measured flight deck
FLOOR = 1.556                  # floor mat's top (the model's floor tops out at 1.548)
FLOOR_PED = FLOOR - 0.012          # bases stand in the mat
FLOOR_E = 1.507                # behind the bulkhead (the model's floor 1.501)
PX = 0.55                      # the captain's seat line (+x, left); the co-pilot's is -PX
CEIL = 3.10
EYE_P = np.array([PX, 2.90741, 5.97065])
EYE_C = np.array([-PX, 2.90741, 5.97065])
EYE_E = np.array([0.0, 2.95431, 4.70452])

Y_B, Y_T = 1.90, 2.46                    # the dash face's lower and upper edge (y)
HALF = 0.82                              # its half width (x)
LEAN = math.radians(14.0)                # the face leans back (its top edge away from the crew)
TAN = math.tan(LEAN)
Z_PAN = 6.66                             # face z at y = Y_B
U_PAN = unit([0, math.cos(LEAN), math.sin(LEAN)])
N_PAN = unit([0, math.sin(LEAN), -math.cos(LEAN)])     # toward the crew


def z_face(y):
    return Z_PAN + TAN * (y - Y_B)


def pan_at(x, y, lift=0.0):
    return np.array([x, y, z_face(y)]) + N_PAN * lift


def deck_front(x):
    """(y, z) of the glass's lower edge at |x| (the deck stops 6 mm under it); the pane's lower edge runs from the post's foot to the pillar's foot"""
    q = CM.windscreen_pane()[0]
    b, d = q[3], q[2]
    f = np.clip(abs(x) / d[0], 0, 1)
    return float(b[1] + (d[1] - b[1]) * f) - 0.006, float(b[2] + (d[2] - b[2]) * f) - 0.004


# ---------------------------------------------------------------- instrument layout
# (name, seat, kind, x, y, diameter, caption) on the dash face; the game draws them live for their seat
GAUGES = [
    # the captain's flight instruments (the Russian "T": speed - AGB attitude - altitude / turn - HSI - vertical speed)
    ('agb', 0, 'ATTITUDE', 0.56, 2.340, 0.150, 'АГБ-3К'),
    ('us', 0, 'AIRSPEED', 0.74, 2.360, 0.108, 'СКОРОСТЬ'),
    ('vd', 0, 'ALTIMETER', 0.38, 2.360, 0.108, 'ВЫСОТА'),
    ('pnp', 0, 'HEADING', 0.56, 2.170, 0.118, 'ПНП'),
    ('var', 0, 'VVI', 0.40, 2.180, 0.092, 'ВАР-30'),
    ('eup', 0, 'TURN_SLIP', 0.72, 2.180, 0.092, 'ПОВОРОТ'),
    ('rv', 0, 'RADAR_ALT', 0.50, 2.028, 0.080, 'РВ-5'),
    ('ach', 0, 'CLOCK', 0.64, 2.028, 0.080, 'АЧС-1'),
    ('rnd', 0, 'AMMO', 0.77, 2.028, 0.066, 'БК'),
    # the engine and main-rotor group: engine 1 (x > 0) is the captain's, engine 2 (x < 0) the co-pilot's, the rotor between
    ('ng1', 0, 'ENGINE_RPM', 0.150, 2.385, 0.098, 'n ТК-1'),
    ('nr', 0, 'ROTOR_RPM', 0.000, 2.385, 0.112, 'НВ'),
    ('ng2', 1, 'ENGINE_RPM', -0.150, 2.385, 0.098, 'n ТК-2'),
    ('tq1', 0, 'TORQUE', 0.150, 2.255, 0.088, 'МОМЕНТ-1'),
    ('fuel', 0, 'FUEL_QTY', 0.000, 2.265, 0.092, 'ТОПЛИВО'),
    ('tq2', 1, 'TORQUE', -0.150, 2.255, 0.088, 'МОМЕНТ-2'),
    ('t41', 0, 'EGT', 0.150, 2.135, 0.084, 't Г-1'),
    ('oil', 0, 'FILLER_OIL', 0.000, 2.150, 0.080, 'МАСЛО'),
    ('t42', 1, 'EGT', -0.150, 2.135, 0.084, 't Г-2'),
    ('hyd', 1, 'FILLER_HYDRAULIC', -0.075, 2.025, 0.068, 'ГИДРО'),
    ('vlt', 0, 'FILLER_VOLTS', 0.075, 2.025, 0.068, 'НАПР'),
    # the co-pilot's flight instruments (his left is +x)
    ('c_us', 1, 'AIRSPEED', -0.300, 2.360, 0.096, 'СКОРОСТЬ'),
    ('c_agb', 1, 'ATTITUDE', -0.440, 2.340, 0.130, 'АГБ-3К'),
    ('c_vd', 1, 'ALTIMETER', -0.575, 2.360, 0.096, 'ВЫСОТА'),
    ('c_var', 1, 'VVI', -0.300, 2.205, 0.084, 'ВАР-30'),
    ('c_pnp', 1, 'HEADING', -0.440, 2.190, 0.104, 'ПНП'),
    ('c_nr', 1, 'ROTOR_RPM', -0.575, 2.205, 0.084, 'НВ'),
    ('c_ach', 1, 'CLOCK', -0.300, 2.060, 0.072, 'АЧС-1'),
    ('c_rv', 1, 'RADAR_ALT', -0.440, 2.050, 0.072, 'РВ-5'),
    ('c_rnd', 1, 'AMMO', -0.575, 2.060, 0.066, 'ПТУР'),
]
# the co-pilot's ATGM sight display: a live STORES page (FlightDisplays, seat 1), CRT
DISPLAYS = [('atgm', 1, 'STORES', -0.745, 2.250, 0.205, 'CRT', 'ПТУР «АТАКА»')]
# painted equipment faces on the dash: (name, x, y, width, height)
PAINT_RECTS = [('rwr', 0.105, 1.955, 0.17, 0.05), ('radio', -0.105, 1.955, 0.17, 0.05)]
# the caution panels on the glareshield deck: (name, seat, x, width, height, columns)
ANN = [('ann_p', 0, 0.40, 0.30, 0.050, 6), ('ann_c', 1, -0.40, 0.26, 0.050, 5)]

LAMPS_P = [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('ВНИМАНИЕ', 'MASTER_CAUTION', 'amber'), ('ПОЖАР', 'FIRE', 'red'), ('ОТКАЗ|ДВИГ', 'ENGINE_OUT', 'red'),
    ('НВ|МАЛО', 'ROTOR_RPM_LOW', 'red'), ('НВ|МНОГО', 'ROTOR_RPM_HIGH', 'amber'), ('ЗАПАС|ТОПЛ', 'LOW_FUEL', 'amber'),
    ('МОМЕНТ|МАКС', 'TORQUE_HIGH', 'amber'), ('ДВИГ|РАБОТ', 'ENGINE_RUN', 'green'), ('ПОВРЕЖД', 'DAMAGE', 'amber'),
    ('МАСЛО', 'NONE', 'amber'), ('ГЕНЕР', 'NONE', 'amber'), ('ГИДРО', 'NONE', 'amber')]]
LAMPS_C = [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('ВНИМАНИЕ', 'MASTER_CAUTION', 'amber'), ('ПОЖАР', 'FIRE', 'red'), ('ОБЛУЧЕН', 'RWR_LOCK', 'red'),
    ('ПУСК|ПО ВАМ', 'MISSILE_LAUNCH', 'red'), ('БК|МАЛО', 'AMMO_LOW', 'amber'), ('ЗАПАС|ТОПЛ', 'LOW_FUEL', 'amber')]]
# the flight engineer's panel (seat 2) on the back of the right bulkhead panel: (name, kind, column, row, diameter, caption)
ENG = [('e_fuel', 'FUEL_QTY', 0, 0, 0.090, 'ТОПЛИВО'), ('e_ng', 'ENGINE_RPM', 1, 0, 0.082, 'n ТК'), ('e_tq', 'TORQUE', 2, 0, 0.082, 'МОМЕНТ'),
       ('e_egt', 'EGT', 0, 1, 0.082, 't Г'), ('e_nr', 'ROTOR_RPM', 1, 1, 0.082, 'НВ'), ('e_clk', 'CLOCK', 2, 1, 0.082, 'АЧС-1'),
       ('e_oil', 'FILLER_OIL', 0, 2, 0.074, 'МАСЛО'), ('e_hyd', 'FILLER_HYDRAULIC', 1, 2, 0.074, 'ГИДРО'), ('e_vlt', 'FILLER_VOLTS', 2, 2, 0.074, 'НАПР')]
ENG_Z = 5.015                                   # the panel's plane (the model's plate behind the bulkhead stands at z 5.03)
ENG_X = (-0.72, -0.38)
ENG_Y = (2.40, 2.88)


def eng_xy(col, row):
    return -0.445 - 0.115 * col, 2.795 - 0.155 * row


# ---------------------------------------------------------------- painted faces
def dial_backing(d, cx, cy, r):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(12, 15, 15, 255))


def panel_face(W_, H_, x0, x1, yb, yt):
    """the dash face: dark, with the dials' backing discs, the Russian captions, the group outlines and screws"""
    def fn(d, im):
        SS = Atlas.SS
        noisy(d, im, PANEL, 3, seed=5)

        def to_px(x, y):
            return (x1 - x) / (x1 - x0) * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        px_m = W_ * SS / (x1 - x0)
        cos_f = math.cos(LEAN)
        groups = [(0.86 - 0.03, 2.455, 0.255, 1.965, 'ЛЕВЫЙ ЛЁТЧИК'), (0.225, 2.455, -0.225, 1.965, 'ДВИГАТЕЛИ ТВ3-117ВМ'),
                  (-0.255, 2.455, -0.86 + 0.03, 1.965, 'ПРАВЫЙ ЛЁТЧИК')]
        for xa, ya, xb, yb2, title in groups:
            ax, ay = to_px(xa, ya); bx, by = to_px(xb, yb2)
            d.rectangle([min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)], outline=(90, 94, 96, 255), width=SS * 2)
        for name, seat, kind, x, y, dia, cap in GAUGES:
            cx, cy = to_px(x, y)
            r = (dia / 2 * (1 + RIM) + 0.004) * px_m
            dial_backing(d, cx, cy, r)
            text(d, (cx, cy + r + SS * 3.6), cap, SS * 5.4, fill=(176, 178, 172, 255))
        for name, seat, kind, x, y, w, style, cap in DISPLAYS:
            cx, cy = to_px(x, y)
            r = (w / 2 * 1.30 + 0.004) * px_m
            d.rectangle([cx - r, cy - r, cx + r, cy + r], fill=(12, 15, 15, 255))
            text(d, (cx, cy + r + SS * 3.6), cap, SS * 5.4, fill=(176, 178, 172, 255))
        for name, x, y, w, h in PAINT_RECTS:
            cx, cy = to_px(x, y)
            sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m / cos_f
            d.rectangle([cx - sx, cy - sy, cx + sx, cy + sy], fill=(12, 15, 15, 255))
        for (a, y) in [(x0 + 0.012, yb + 0.010), (x1 - 0.012, yb + 0.010), (x0 + 0.012, yt - 0.010), (x1 - 0.012, yt - 0.010),
                       (-0.24, yt - 0.010), (0.24, yt - 0.010), (-0.24, yb + 0.010), (0.24, yb + 0.010)]:
            x_, y_ = to_px(a, y); r = SS * 1.6
            d.ellipse([x_ - r, y_ - r, x_ + r, y_ + r], fill=MGREY + (255,), outline=(20, 20, 20, 255))
        for xg, ttl in ((0.56, 'ЛЕВЫЙ ЛЁТЧИК (КВС)'), (0.0, 'ДВИГАТЕЛИ  ТВ3-117ВМ  /  НВ'), (-0.56, 'ПРАВЫЙ ЛЁТЧИК')):
            cx, cy = to_px(xg, 2.448)
            text(d, (cx, cy), ttl, SS * 4.6, fill=(150, 154, 150, 255), bold=True)
    return fn


def p_rwr(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 22, 24, 255))
    text(d, (S * .5, H * .22), 'СПО  «ЛИПА»', H * .28, fill=(200, 204, 196), bold=True)
    for k in range(4):
        x = S * (0.15 + 0.23 * k)
        lamp(d, [x - S * .08, H * .46, x + S * .08, H * .86], (200, 40, 30) if k % 2 == 0 else (214, 140, 30), None)
    text(d, (S * .5, H * .96), 'КОНТРОЛЬ', H * .13)


def p_radio(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 22, 24, 255))
    text(d, (S * .5, H * .20), 'РАДИО  /  СПУ', H * .26, fill=(200, 204, 196), bold=True)
    for k, lab in enumerate(['Р-863', 'Р-828', 'РВ', 'СПУ']):
        x = S * (0.14 + 0.24 * k)
        knob(d, x, H * .62, H * .20, ang=0.4 + 0.5 * k)
        text(d, (x, H * .93), lab, H * .16)


def p_weapons(d, im):
    """the AMTSh weapons control panel (the pedestal's forward top): pylon selectors, the weapon-type selector, arm and jettison covers"""
    S, H = im.width, im.height
    noisy(d, im, PANEL, 3, seed=61)
    d.rectangle([4, 4, S - 5, H - 5], outline=(110, 114, 108, 255), width=4)
    text(d, (S * .5, H * .075), 'УПРАВЛЕНИЕ  ВООРУЖЕНИЕМ', H * .062, fill=(220, 222, 214), bold=True)
    # pylon selectors, outer - middle - inner for each side (the pack's three pylons a side)
    for side, x0 in (('ЛЕВ', 0.08), ('ПРАВ', 0.58)):
        text(d, (S * (x0 + 0.17), H * .17), side + '. БДЗ', H * .05, fill=(176, 178, 172))
        for k, lab in enumerate(['1', '2', '3']):
            x = S * (x0 + 0.06 + 0.11 * k)
            toggle(d, x, H * .27, S * 1.5)
            text(d, (x, H * .35), lab, H * .05)
            lamp(d, [x - S * .035, H * .39, x + S * .035, H * .43], (60, 200, 80) if k == 0 else (200, 140, 30), None)
    # weapon type selector
    for k, (lab, on) in enumerate([('НАР', True), ('ПУ-23', False), ('ГУВ', False), ('ПТУР', True), ('ОФАБ', False), ('ИГЛА', False)]):
        x = S * (0.10 + 0.15 * k)
        d.rectangle([x - S * .06, H * .50, x + S * .06, H * .60], fill=(40, 130, 56, 255) if on else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .55), lab, H * .045, fill=(10, 30, 10), bold=True)
    for k, lab in enumerate(['ЗАЛП', 'ОДИН', 'ПАРА']):
        x = S * (0.18 + 0.16 * k)
        toggle(d, x, H * .70, S * 1.5, big=True)
        text(d, (x, H * .79), lab, H * .045)
    d.rectangle([S * .70, H * .64, S * .94, H * .78], fill=RED + (255,), outline=(10, 10, 10, 255), width=4)
    text(d, (S * .82, H * .71), 'СБРОС', H * .06, fill=(235, 235, 228), bold=True)
    d.rectangle([S * .08, H * .86, S * .50, H * .95], fill=YELLOW + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .29, H * .905), 'ОГОНЬ  ОТКРЫТ ТОЛЬКО ПО ПРИКАЗУ', H * .035, fill=(20, 20, 20), bold=True)
    d.rectangle([S * .56, H * .86, S * .94, H * .95], fill=(30, 30, 32, 255), outline=(160, 160, 154, 255), width=3)
    text(d, (S * .75, H * .905), 'ПИТАНИЕ  ВООРУЖЕНИЯ', H * .04, fill=(220, 222, 214))


def p_riser(d, im):
    """the pedestal's riser (faces the crew): intercom, ATGM channel and sight controls"""
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=62)
    text(d, (S * .5, H * .17), 'СПУ  /  ПТУР «АТАКА»', H * .22, fill=(200, 204, 196), bold=True)
    for k, lab in enumerate(['1', '2', '3', '4']):
        x = S * (0.12 + 0.12 * k)
        toggle(d, x, H * .58, S * 1.1)
        text(d, (x, H * .86), lab, H * .16)
    for k, lab in enumerate(['РЕЖИМ', 'КАНАЛ']):
        x = S * (0.64 + 0.2 * k)
        knob(d, x, H * .58, H * .22, ang=0.5 + 0.6 * k)
        text(d, (x, H * .90), lab, H * .14)


def p_pedestal_aft(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL, 4, seed=63)
    d.rectangle([S * .06, H * .06, S * .94, H * .46], fill=(20, 22, 24, 255), outline=(70, 72, 74, 255), width=3)
    text(d, (S * .5, H * .13), 'РАДИОСТАНЦИИ', H * .07, fill=(200, 204, 196), bold=True)
    for k in range(3):
        knob(d, S * (0.22 + 0.28 * k), H * .32, S * .08, ang=0.3 + 0.7 * k)
    text(d, (S * .5, H * .60), 'ТОПЛИВНЫЙ  КРАН', H * .06, fill=(200, 204, 196))
    d.rectangle([S * .42, H * .64, S * .58, H * .90], fill=(4, 4, 4, 255), outline=(90, 94, 96, 255), width=3)
    d.rectangle([S * .47, H * .66, S * .53, H * .88], fill=(26, 26, 28, 255))
    text(d, (S * .26, H * .78), 'ОТКР', H * .05); text(d, (S * .74, H * .78), 'ЗАКР', H * .05)


def p_overhead(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=33)
    d.rectangle([4, 4, S - 5, H - 5], outline=(100, 104, 98, 255), width=3)
    labs = ['АККУМ', 'ГЕНЕР 1', 'ГЕНЕР 2', 'ПО-500', 'ПИТОТ', 'АНО', 'ФАРА', 'ЗАПУСК']
    for k, lab in enumerate(labs):
        x = S * (0.09 + 0.116 * k)
        toggle(d, x, H * .30, S * 1.0)
        text(d, (x, H * .55), lab, H * .09)
    for k, lab in enumerate(['ЛЕВ  ДВ', 'ПРАВ  ДВ', 'КОРРЕКЦИЯ']):
        x = S * (0.20 + 0.30 * k)
        d.rectangle([x - S * .09, H * .66, x + S * .09, H * .92], fill=(10, 10, 10, 255), outline=(90, 94, 96, 255), width=3)
        d.rectangle([x - S * .008, H * .69, x + S * .008, H * .89], fill=(4, 4, 4, 255))
        text(d, (x, H * .96), lab, H * .07)


def p_eng_panel(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL, 3, seed=71)
    d.rectangle([4, 4, S - 5, H - 5], outline=(100, 104, 98, 255), width=4)
    text(d, (S * .5, H * .05), 'БОРТМЕХАНИК', H * .045, fill=(176, 178, 172), bold=True)
    x0, x1 = ENG_X
    y0, y1 = ENG_Y
    for name, kind, col, row, dia, cap in ENG:
        x, y = eng_xy(col, row)
        cx = (x1 - x) / (x1 - x0) * S
        cy = (y1 - y) / (y1 - y0) * H
        r = (dia / 2 * (1 + RIM) + 0.004) / (y1 - y0) * H
        dial_backing(d, cx, cy, r)
        text(d, (cx, cy + r + H * .018), cap, H * .028, fill=(176, 178, 172, 255))


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


def p_sight_body(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=34)
    text(d, (S * .5, H * .30), 'ПКВ', H * .34, fill=(176, 178, 172), bold=True)
    for k in range(3):
        knob(d, S * (0.22 + 0.28 * k), H * .72, H * .13, ang=0.4 + 0.5 * k)


def p_grip(label):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, DGREY, 3, seed=35)
        text(d, (S * .5, H * .5), label, H * .5, fill=(176, 178, 172), bold=True)
    return fn


def p_floor(d, im):
    S, H = im.width, im.height
    noisy(d, im, MAT, 4, seed=80)
    for k in range(0, S, 22):
        d.line([k, 0, k, H], fill=(36, 40, 38, 255), width=2)
    for k in range(0, H, 22):
        d.line([0, k, S, k], fill=(36, 40, 38, 255), width=2)


def p_hood(d, im):
    S, H = im.width, im.height
    noisy(d, im, BLACK, 2, seed=90)


# ---------------------------------------------------------------- build
def build(atlas):
    m = Mesh(atlas)
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lgrey=LGREY,
                                                mat=MAT, lining=LINING, frame=FRAME, cushion=CUSHION, harness=HARNESS,
                                                yellow=YELLOW, red=RED, metal=METAL, rubber=RUBBER, bezel=(26, 27, 29),
                                                skin=SKIN, amber=AMBER).items()}
    gauges, displays = [], []

    # ===================================================================== floor mats
    mat_tile = atlas.paint(96, 96, p_floor)
    m.flat([[-0.80, FLOOR, 5.70], [0.80, FLOOR, 5.70], [0.80, FLOOR, 6.64], [-0.80, FLOOR, 6.64]], mat_tile, normal=[0, 1, 0])
    for s in (1, -1):                                                # outboard of the dash's side, under the collective
        m.flat([[s * 0.80, FLOOR, 5.70], [s * 1.12, FLOOR, 5.70], [s * 1.12, FLOOR, 6.30], [s * 0.80, FLOOR, 6.30]], mat_tile, normal=[0, 1, 0])
    m.flat([[-0.30, FLOOR_E, 4.20], [0.30, FLOOR_E, 4.20], [0.30, FLOOR_E, 5.70], [-0.30, FLOOR_E, 5.70]], mat_tile, normal=[0, 1, 0])

    # ===================================================================== the dash: one panel across both pilots under the glareshield deck
    ppm = 700
    W_ = int(round(2 * HALF * ppm))
    H_ = int(round((Y_T - Y_B) / math.cos(LEAN) * ppm))
    ptile = atlas.paint(W_, H_, panel_face(W_, H_, -HALF, HALF, Y_B, Y_T))
    lift = 0.003
    pb = [pan_at(-HALF, Y_B, lift), pan_at(HALF, Y_B, lift), pan_at(HALF, Y_T, lift), pan_at(-HALF, Y_T, lift)]
    m.quad_tex([pb[1], pb[0], pb[3], pb[2]], ptile, normal=N_PAN)
    DEPTH = 0.12
    zp = z_face
    for s in (1, -1):                                                # the panel box's sides and its closed back
        m.flat([[s * HALF, Y_B, zp(Y_B)], [s * HALF, Y_B, zp(Y_B) + DEPTH], [s * HALF, Y_T, zp(Y_T) + DEPTH], [s * HALF, Y_T, zp(Y_T)]],
               sw['panel_d'], normal=[s, 0, 0])
        m.flat([[s * HALF, FLOOR, zp(Y_B)], [s * HALF, FLOOR, zp(Y_B) + 0.26], [s * HALF, Y_B, zp(Y_B) + 0.26], [s * HALF, Y_B, zp(Y_B)]],
               sw['panel_d'], normal=[s, 0, 0], double=True)
    m.flat([[-HALF, Y_B, zp(Y_B)], [HALF, Y_B, zp(Y_B)], [HALF, Y_B, zp(Y_B) + DEPTH], [-HALF, Y_B, zp(Y_B) + DEPTH]], sw['panel_d'],
           normal=[0, -1, 0])
    m.flat([[-HALF, FLOOR, zp(Y_B)], [HALF, FLOOR, zp(Y_B)], [HALF, Y_B, zp(Y_B)], [-HALF, Y_B, zp(Y_B)]], sw['panel_d'], normal=[0, 0, -1])  # kick plate
    # the glareshield deck: from the panel's top edge forward to 6 mm under the glass's lower edge, one plane per half, black
    yb0 = Y_T
    zb0 = zp(Y_T)
    for s in (1, -1):
        yf0, zf0 = deck_front(0.0)
        yf1, zf1 = deck_front(HALF)
        pts = [[0.0, yb0, zb0], [s * HALF, yb0, zb0], [s * HALF, yf1, zf1], [0.0, yf0, zf0]]
        m.flat(pts, sw['black'], normal=[0, 1, 0.15])
        # the deck's underside and its front lip (a shade over the nose bay)
        m.flat([[p[0], p[1] - 0.04, p[2]] for p in pts], sw['panel_d'], normal=[0, -1, 0])
        m.flat([[0.0, yf0, zf0], [s * HALF, yf1, zf1], [s * HALF, yf1 - 0.04, zf1], [0.0, yf0 - 0.04, zf0]], sw['black'], normal=[0, 0.1, 1])
        m.flat([[s * HALF, yb0, zb0], [s * HALF, yf1, zf1], [s * HALF, yf1 - 0.04, zf1], [s * HALF, yb0 - 0.04, zb0]], sw['panel_d'], normal=[s, 0, 0])
    # ---- the dials (game) and the display; bezels
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
        pr, t = 0.012, 0.024
        for dy in (1, -1):
            m.box(c + u_ * dy * (w / 2 + t / 2) + n_ * pr / 2, (r_, u_, n_), (w / 2 + t, t / 2, pr / 2), sw['bezel'])
        for dx in (1, -1):
            m.box(c + r_ * dx * (w / 2 + t / 2) + n_ * pr / 2, (r_, u_, n_), (t / 2, w / 2, pr / 2), sw['bezel'])
        # the sight's hood: a black visor over the screen reaching toward the co-pilot (clear of the bezel by 6 mm)
        hood_len = 0.085
        m.box(c + u_ * (w / 2 + t + 0.014) + n_ * (hood_len / 2), (r_, u_, n_), (w / 2 + t + 0.018, 0.0035, hood_len / 2), sw['black'])
        for dx in (1, -1):
            m.box(c + r_ * dx * (w / 2 + t + 0.0125) + n_ * (hood_len / 2) + u_ * (w / 4 + 0.006), (r_, u_, n_), (0.0035, w / 4 + 0.008, hood_len / 2 - 0.002), sw['black'])
    painters = {'rwr': p_rwr, 'radio': p_radio}
    for name, x, y, w, h in PAINT_RECTS:
        c = pan_at(x, y)
        pw = 220
        rect = atlas.paint(pw, max(16, int(round(pw * h / w))), painters[name])
        m.rect_tex(c + N_PAN * 0.0055, N_PAN, U_PAN, w, h, rect)
        m.frame_rect(c, N_PAN, U_PAN, w, h, 0.004, 0.007, sw['bezel'])
    # ---- the caution panels on the deck (lit by the game), each on a sloped wedge facing its crew member
    PHI = math.radians(38.0)
    for name, seat, x, w, h, cols in ANN:
        yd, zd = deck_front(0.0)
        zc = zb0 + 0.12
        yc = yb0 - (yb0 - yd) * (zc - zb0) / max(1e-6, zd - zb0) + 0.002
        u_ = unit([0, math.cos(PHI), math.sin(PHI)])
        n_ = unit([0, math.sin(PHI), -math.cos(PHI)])
        c = np.array([x, yc + 0.5 * h * u_[1] + 0.004, zc])
        gauges.append({'Seat': seat, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + n_ * 0.0045), 'Normal': r5(n_), 'Up': r5(u_),
                       'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                       'Lamps': LAMPS_P if seat == 0 else LAMPS_C})
        m.box(c - n_ * 0.014, (X, u_, n_), (w / 2 + 0.01, h / 2 + 0.008, 0.014), sw['black'])
        m.frame_rect(c, n_, u_, w, h, 0.005, 0.008, sw['bezel'])
    # ---- the captain's PKV-class reflector sight on a stalk over the deck (HeadUpDisplay REFLECTOR, seat 0)
    sight_body = atlas.paint(110, 64, p_sight_body)
    ex, ey, ez = EYE_P
    g_hw, g_h = 0.058, 0.090
    lean = math.radians(32)
    g_up = unit([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))                   # toward +z (away from the pilot)
    g_c = np.array([ex, ey - 0.020, ez + 0.755])
    base_c = np.array([ex, yb0 + 0.034, zb0 + 0.060])
    m.box(base_c, (X, Y, Z), (0.062, 0.034, 0.060), sw['dgrey'], front=(2, -1, sight_body, -X, Y))   # the unit (its face toward the pilot)
    stalk_top = g_c - g_up * (g_h / 2 + 0.004) - g_n * 0.014
    m.cyl(base_c + [0, 0.030, 0.0], stalk_top, 0.006, sw['dgrey'], seg=6)              # one slim post behind the combiner's frame
    m.box(g_c - g_up * (g_h / 2 + 0.004), (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.009), sw['dgrey'])
    m.box(g_c + g_up * (g_h / 2 + 0.003), (X, g_up, g_n), (g_hw + 0.008, 0.003, 0.005), sw['dgrey'])
    for s in (-1, 1):
        m.box(g_c + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='REFLECTOR')
    gl = (g_hw - 0.001, g_h / 2 - 0.001)
    glass = [g_c - X * gl[0] - g_up * gl[1], g_c + X * gl[0] - g_up * gl[1], g_c + X * gl[0] + g_up * gl[1], g_c - X * gl[0] + g_up * gl[1]]
    hud['glass'] = [glass[0], glass[1], glass[2], glass[0], glass[2], glass[3]]
    # ===================================================================== the centre pedestal: radio / ICS, fuel cock, the AMTSh weapons panel
    ped_aft = atlas.paint(180, 160, p_pedestal_aft)
    ped_wpn = atlas.paint(380, 270, p_weapons)
    ped_riser = atlas.paint(260, 58, p_riser)
    pxh = 0.27
    za, zr, zf_ = 5.76, 6.18, 6.62
    ya, yf = 1.80, 1.98
    # back block (radio / fuel cock), top y 1.80
    m.face([[pxh, ya, za], [-pxh, ya, za], [-pxh, ya, zr], [pxh, ya, zr]],
           [atlas.uv(ped_aft, 0, 0), atlas.uv(ped_aft, 1, 0), atlas.uv(ped_aft, 1, 1), atlas.uv(ped_aft, 0, 1)], normal=[0, 1, 0])
    for s in (1, -1):
        m.flat([[s * pxh, FLOOR_PED, za], [s * pxh, FLOOR_PED, zf_], [s * pxh, yf, zf_], [s * pxh, yf, zr], [s * pxh, ya, zr], [s * pxh, ya, za]],
               sw['panel_d'], normal=[s, 0, 0])
    m.flat([[-pxh, FLOOR_PED, za], [pxh, FLOOR_PED, za], [pxh, ya, za], [-pxh, ya, za]], sw['panel_d'], normal=[0, 0, -1])
    # the riser (faces the crew) and the forward top (the weapons panel, leaning toward the crew)
    m.quad_tex([[pxh, ya, zr], [-pxh, ya, zr], [-pxh, yf, zr], [pxh, yf, zr]], ped_riser, normal=[0, 0, -1])
    wl = math.radians(18)
    wz0, wz1 = zr, zf_
    y0w, y1w = yf, yf - (wz1 - wz0) * math.tan(wl)
    wn = unit([0, math.cos(wl), -math.sin(wl)]) if False else unit([0, 1.0, math.tan(wl)])
    m.quad_tex([[pxh, y0w, wz0], [-pxh, y0w, wz0], [-pxh, y1w, wz1], [pxh, y1w, wz1]], ped_wpn, normal=wn)
    m.flat([[-pxh, y1w, wz1], [pxh, y1w, wz1], [pxh, FLOOR_PED, wz1], [-pxh, FLOOR_PED, wz1]], sw['panel_d'], normal=[0, 0, 1], double=True)
    # the Ataka guidance handle at the co-pilot's side: a post and a pistol grip with its trigger and thumb switch
    hx, hz = -0.165, 6.40
    hy = y0w - (hz - wz0) * math.tan(wl)
    m.box([hx, hy + 0.012, hz], (X, Y, Z), (0.040, 0.012, 0.040), sw['dgrey'])
    m.cyl([hx, hy + 0.02, hz], [hx, hy + 0.115, hz - 0.012], 0.009, sw['metal'], seg=6)
    gax = (X, unit([0, 1, -0.30]), unit([0, 0.30, 1]))
    gc = np.array([hx, hy + 0.165, hz - 0.020])
    m.box(gc, gax, (0.020, 0.052, 0.028), sw['black'])
    m.box(gc + gax[2] * 0.030 + gax[1] * 0.008, gax, (0.006, 0.020, 0.005), sw['metal'])
    m.box(gc + gax[1] * 0.056 + gax[2] * 0.004, gax, (0.014, 0.005, 0.018), sw['mgrey'])
    m.box(gc + gax[1] * 0.061 + gax[2] * 0.010, gax, (0.005, 0.004, 0.005), sw['red'])
    # ===================================================================== seats and flight controls (each pilot)
    cush = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    grip_tiles = {k: atlas.paint(80, 28, p_grip(k)) for k in ('ПТТ', 'ОГОНЬ')}
    for xc, tag in ((PX, 'p'), (-PX, 'c')):
        m.box([xc, FLOOR + 0.07, 6.03], (X, Y, Z), (0.17, 0.07, 0.22), sw['frame'])                           # the seat base
        m.box([xc, 1.835, 6.03], (X, Y, Z), (0.20, 0.030, 0.27), sw['cushion'], front=(1, 1, cush, -X, Z))    # the pan, top y 1.865
        m.box([xc, 2.40, 5.748], (X, Y, Z), (0.205, 0.50, 0.026), sw['cushion'], front=(2, 1, backt, -X, Y))  # the armoured back, z 5.72 .. 5.77
        m.box([xc, 3.00, 5.745], (X, Y, Z), (0.11, 0.095, 0.030), sw['cushion'])                              # the headrest (top 3.095)
        for s in (1, -1):
            m.box([xc + s * 0.215, 1.97, 6.00], (X, Y, Z), (0.014, 0.095, 0.25), sw['frame'])                 # side bolsters
            m.box([xc + s * 0.095, 2.40, 5.775], (X, Y, Z), (0.024, 0.42, 0.004), sw['harness'])              # shoulder straps
            m.box([xc + s * 0.115, 1.885, 6.13], (X, Y, Z), (0.050, 0.004, 0.012), sw['harness'])             # lap belt halves
        m.box([xc, 1.895, 6.13], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])                               # the buckle
        # the cyclic between the knees: base, stick leaning back, grip with trigger, trim hat and radio switch
        base = np.array([xc, FLOOR + 0.004, 6.46])
        m.box(base + [0, 0.020, 0], (X, Y, Z), (0.050, 0.020, 0.050), sw['dgrey'])
        ax = unit([0, 0.97, -0.17])
        top = base + ax * 0.78
        m.cyl(base + [0, 0.03, 0], top, 0.0125, sw['metal'], seg=8)
        gc = top + ax * 0.045
        g_ax = (X, unit([0, 1, -0.28]), unit([0, 0.28, 1]))
        m.box(gc, g_ax, (0.024, 0.078, 0.030), sw['black'])
        m.box(gc + g_ax[1] * 0.083, g_ax, (0.020, 0.006, 0.026), sw['dgrey'])
        m.box(gc + g_ax[2] * 0.034 + g_ax[1] * 0.025, g_ax, (0.008, 0.020, 0.005), sw['metal'])             # trigger
        m.box(gc + g_ax[1] * 0.045 - g_ax[2] * 0.030, g_ax, (0.012, 0.014, 0.004), sw['mgrey'])             # trim hat
        m.box(gc + g_ax[1] * 0.012 - g_ax[2] * 0.032, g_ax, (0.007, 0.007, 0.004), sw['red'])               # PTT / weapon release
        m.rect_tex(gc + g_ax[2] * 0.0335 + g_ax[1] * 0.052, g_ax[2], g_ax[1], 0.034, 0.014, grip_tiles['ПТТ'])
        # the collective on the outboard side: a base on the floor beside the seat, the lever forward, the twist throttle grip
        s_out = 1 if xc > 0 else -1
        cb = np.array([xc + s_out * 0.405, FLOOR + 0.20, 5.90])
        cg = np.array([xc + s_out * 0.405, FLOOR + 0.44, 6.38])
        m.box(cb - [0, 0.15, 0], (X, Y, Z), (0.035, 0.15, 0.04), sw['dgrey'])
        # The captain's collective (the "throttle" lever) is its own bone (throttle_lever), hinged on the base, modelled full down
        # (throttle_lib.collective); the co-pilot's is the same lever, fixed.
        gd = unit([0, 0.10, 1.0])
        arm = cg - cb
        lv = throttle_lib.collective(lambda: Mesh(atlas), sw, 'throttle_lever', cb, float(math.hypot(arm[1], arm[2])), 12.0,
                                     math.degrees(math.atan2(0.10, 1.0)), grip_len=0.215, travel=25.0)
        if tag == 'p':
            THR.clear()
            THR.append(lv)
        else:
            throttle_lib.make_static(m, [lv])
        m.box(cg - gd * 0.10 + [0.0, 0.0, 0.0] + [s_out * 0.04, 0.0, 0.0], (X, Y, Z), (0.012, 0.016, 0.012), sw['mgrey'])    # friction lock
        # rudder pedals: a plate each (heel on the floor, the top leaning to the dash), a rod to the kick plate
        for s in (1, -1):
            px_ = xc + s * 0.135
            axp = (X, unit([0, 1, 0.50]), unit([0, -0.50, 1]))
            m.box([px_, FLOOR + 0.13, 6.50], axp, (0.058, 0.080, 0.010), sw['mgrey'])
            m.box([px_, FLOOR + 0.04, 6.42], (X, Y, Z), (0.050, 0.014, 0.060), sw['dgrey'])
            m.cyl([px_, FLOOR + 0.20, 6.55], [px_, FLOOR + 0.30, 6.655], 0.010, sw['metal'], seg=6)
    # ===================================================================== overhead panel and the engine control quadrant (on the ceiling between the pilots)
    ov = atlas.paint(300, 100, p_overhead)
    oz0, oz1, oy0 = 5.80, 6.40, CEIL - 0.003
    oa = math.radians(10)
    oy1 = oy0 - 0.060 - (oz1 - oz0) * math.tan(oa) * 0.5
    ow = 0.33
    face_pts = [[ow, oy0 - 0.045, oz0], [-ow, oy0 - 0.045, oz0], [-ow, oy1, oz1], [ow, oy1, oz1]]
    on_ = unit(np.cross(np.array(face_pts[1]) - np.array(face_pts[0]), np.array(face_pts[3]) - np.array(face_pts[0])))
    if on_[1] > 0:
        on_ = -on_
    m.face(face_pts, [atlas.uv(ov, 0, 1), atlas.uv(ov, 1, 1), atlas.uv(ov, 1, 0), atlas.uv(ov, 0, 0)], normal=on_)
    m.box([0, (oy0 + (oy0 - 0.045)) / 2, (oz0 + oz1) / 2], (X, Y, Z), (ow, 0.0225, (oz1 - oz0) / 2 - 0.002), sw['panel_d'])   # the panel's housing up to the ceiling
    for xl, kn in ((-0.20, 'red'), (0.0, 'red'), (0.20, 'mgrey')):                                                # the three control levers hanging from its forward edge
        m.cyl([xl, oy1 + 0.004, oz1 - 0.02], [xl, oy1 - 0.070, oz1 + 0.012], 0.007, sw['metal'], seg=6)
        m.box([xl, oy1 - 0.090, oz1 + 0.018], (X, Y, Z), (0.014, 0.014, 0.014), sw[kn])
    # ===================================================================== the nose bay behind the dash: closed (double-sided plates) so it cannot be seen into
    BX, BZ0, BZ1, BY = 0.66, 6.84, 7.28, 2.28                 # the bay's closing plates: inside the nose block's skin
    for s in (1, -1):
        m.flat([[s * BX, FLOOR - 0.04, BZ0], [s * BX, FLOOR - 0.04, BZ1], [s * BX, BY, BZ1], [s * BX, BY, BZ0]], sw['panel_d'], normal=[s, 0, 0], double=True)
    m.flat([[-BX, BY, BZ0], [BX, BY, BZ0], [BX, BY, BZ1], [-BX, BY, BZ1]], sw['panel_d'], normal=[0, 1, 0], double=True)
    m.flat([[-BX, FLOOR - 0.04, BZ1], [BX, FLOOR - 0.04, BZ1], [BX, BY, BZ1], [-BX, BY, BZ1]], sw['panel_d'], normal=[0, 0, 1], double=True)
    m.flat([[-BX, FLOOR - 0.04, BZ0], [BX, FLOOR - 0.04, BZ0], [BX, FLOOR - 0.04, BZ1], [-BX, FLOOR - 0.04, BZ1]], sw['panel_d'], normal=[0, 1, 0], double=True)
    # ===================================================================== the flight engineer: jump seat on the centre line, his panel on the bulkhead's back
    ez_ = 4.72
    m.box([0, FLOOR_E + 0.30, ez_ - 0.03], (X, Y, Z), (0.17, 0.020, 0.20), sw['frame'])                             # folded frame under the pan
    m.box([0, 1.865, ez_], (X, Y, Z), (0.18, 0.028, 0.21), sw['cushion'], front=(1, 1, cush, -X, Z))               # the pan, top 1.893
    m.box([0, 2.37, ez_ - 0.205], (X, Y, Z), (0.185, 0.46, 0.022), sw['cushion'], front=(2, 1, backt, -X, Y))       # the back, z 4.49 .. 4.53
    for s in (1, -1):
        m.cyl([s * 0.15, FLOOR_E, ez_ - 0.18], [s * 0.15, 1.85, ez_ - 0.12], 0.012, sw['metal'], seg=6)            # tubular legs
        m.cyl([s * 0.15, FLOOR_E, ez_ + 0.17], [s * 0.15, 1.85, ez_ + 0.15], 0.012, sw['metal'], seg=6)
        m.box([s * 0.095, 2.40, ez_ - 0.178], (X, Y, Z), (0.022, 0.40, 0.004), sw['harness'])
    eng_tile = atlas.paint(int((ENG_X[1] - ENG_X[0]) * 700), int((ENG_Y[1] - ENG_Y[0]) * 700), p_eng_panel)
    xc_e = (ENG_X[0] + ENG_X[1]) / 2
    yc_e = (ENG_Y[0] + ENG_Y[1]) / 2
    # (the panel faces -z; seen from the engineer, +x is on his left, so the tile's left edge is x = ENG_X[1]'s side ... painted to match)
    m.quad_tex([[ENG_X[1], ENG_Y[0], ENG_Z], [ENG_X[0], ENG_Y[0], ENG_Z], [ENG_X[0], ENG_Y[1], ENG_Z], [ENG_X[1], ENG_Y[1], ENG_Z]], eng_tile, normal=[0, 0, -1])
    for name, kind, col, row, dia, cap in ENG:
        x, y = eng_xy(col, row)
        c = np.array([x, y, ENG_Z])
        gauges.append({'Seat': 2, 'Kind': kind, 'Center': r5(c + [0, 0, -GAUGE_LIFT]), 'Normal': [0.0, 0.0, -1.0], 'Up': [0.0, 1.0, 0.0],
                       'Diameter': dia, 'Depth': 0.03})
        m.ring(c, [0, 0, -1], Y, dia / 2, dia / 2 * (1 + RIM), GAUGE_LIFT + 0.006, sw['bezel'])
    # ===================================================================== Russian placards
    pl_exit = atlas.paint(120, 36, p_placard(['АВАРИЙНЫЙ ВЫХОД'], bg=RED, fg=(240, 240, 232)))
    pl_ext = atlas.paint(120, 36, p_placard(['ОГНЕТУШИТЕЛЬ'], bg=RED, fg=(240, 240, 232)))
    pl_step = atlas.paint(120, 36, p_placard(['НЕ НАСТУПАТЬ'], bg=YELLOW, fg=(20, 20, 20)))
    pl_arm = atlas.paint(120, 36, p_placard(['ВООРУЖЕНИЕ', 'ОПАСНО'], bg=YELLOW, fg=(20, 20, 20)))
    for s_ in (1, -1):
        m.rect_tex([s_ * 1.199, 2.02, 6.10], [-s_, 0, 0], Y, 0.20, 0.060, pl_exit)
        m.rect_tex([s_ * 0.88, 1.567, 6.00], [0, 1, 0], Z, 0.14, 0.045, pl_step)
    m.rect_tex([0.272, 1.88, 6.40], [1, 0, 0], Y, 0.18, 0.05, pl_ext)
    m.rect_tex([-0.272, 1.88, 6.40], [-1, 0, 0], Y, 0.18, 0.05, pl_arm)
    return m, gauges, displays, hud


# ---------------------------------------------------------------- the airframe
def planform(geo):
    """a top-down outline of the airframe for the stores page: the hull's widest |x| per z, as one closed polyline"""
    hull = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == HULL_BONE)
    P = np.array(hull['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
    P = P[(np.abs(P[:, 0]) < 1.6) & (P[:, 1] > 1.0)]            # the fuselage only (not the outriggers, wheels and tail rotor)
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


def set_seats(ac, dry):
    """the captain's and co-pilot's seats and eyes onto +-PX, the engineer's onto the centre line (the data file's Attachments and Seats)"""
    d = ac.data
    want = {'pilot_eye': PX, 'copilot_eye': -PX, 'engineer_eye': 0.0}
    for k, x in want.items():
        d['Attachments'][k]['Position'][0] = x
    for i, x in ((0, PX), (1, -PX), (2, 0.0)):
        d['Seats'][i]['Position'][0] = x
    if not dry:
        with open(os.path.join(L.DATA_DIR, VID + '.json'), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(json.dumps(d, indent=2, ensure_ascii=False) + '\n')


def install(ac, mesh, gauges, displays, hud):
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
    print(f'mi_8amtsh throttle bones {[(n, p) for n, p, _ in thr]}')
    a = ac.asset
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    a['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays, 'Planform': planform(ac.geo)}
    a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0, 'Center': r5(hud['centre']),
                          'Normal': r5(hud['normal']), 'Up': r5(hud['up']), 'Width': round(float(hud['width']), 5),
                          'Height': round(float(hud['height']), 5), 'Eye': r5(EYE_P), 'Style': hud['style']}
    L.add_glass(a, hud['glass'])                  # the reflector's glass, on top of the canopy's
    return pm


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    set_seats(ac, dry)
    atlas = Atlas(VID)
    mesh, gauges, displays, hud = build(atlas)
    pm = install(ac, mesh, gauges, displays, hud)
    print(f'{VID}: new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} display(s), reflector sight; atlas '
          f'{sum(r[2] * r[3] for r, _ in atlas.tiles)} of {sum(a[2] * a[3] for a in atlas.areas)} texels')
    if not dry:
        write(ac, atlas, ac.asset, ac.geo)


if __name__ == '__main__':
    main(sys.argv[1:])
