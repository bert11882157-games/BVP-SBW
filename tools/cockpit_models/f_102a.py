#!/usr/bin/env python3
"""F-102A Delta Dagger cockpit, built by hand for this model (as mig_25pd.py, mirage_3e.py, mig_29.py), from the real panel layout.

Measured off the model (the faces of F-102A.mtb in checks/orig, sections.py, eyeview.py; all heights and distances from the
pilot's eye at (0, 2.84744, 4.49618); 1 block = 1 m on this model):
  * the fuselage's top skin steps down from the sill ridge (y -0.17 behind the seat to -0.37 at the panel) outward, the floor's
    top is at y -0.95, the belly at -1.5; the cockpit is open under the canopy (the model's own tub boxes are cut by the canopy pass);
  * the old panel and hood stood 0.60 m ahead of the eye (the tall radar-scope hood to y +0.20 above the eye); the nose deck
    under the long windscreen falls from y -0.18 (z +0.6) to -0.43 at the base (z +1.7), 0.2 m wide on its crown;
  * the model's seat group: headrest front face z -0.30, the pan from z -0.40 to +0.35; the stick's foot (0, -0.80, +0.35).
Built:
  * a three-facet main panel 0.62 m ahead, 12 degrees back, its wings turned 25 degrees toward the pilot: the Hughes MG-10 fire
    control radar scope at the top centre under a deep rectangular rubber hood (its top at -0.05 at the eyepiece lip, so the
    forward view stays open), the attitude indicator and radio magnetic indicator, Mach / airspeed and altimeter, rate of climb,
    angle of attack and clock (centre); airspeed, g-meter, the armament panel (FALCON / ROCKET select) and the landing-gear panel
    on the left wing; the J57's tachometer, exhaust temperature and fuel quantity and the warning lights on the right wing;
    English placards (MG-10 FIRE CONTROL, J57 ENGINE, FALCON, ROCKET, LANDING GEAR);
  * the black glareshield over the model's own nose deck, forward to the windscreen's base bar;
  * the reflector sight on the hood's top (HeadUpDisplay Style REFLECTOR, seat 0; the F-102A has no head-up display);
  * low side consoles with switch panels, side walls, rudder pedals, the J57's throttle lever on the left console, a radar hand
    controller (a small pistol grip with a thumb button) on the right console, a Weber ejection seat (pan and bucket, parachute
    back, headbox with its pad, rails, the armrest ejection handles) and the control stick with a radar thumb switch (into the
    stick bone), the rear bulkhead closing the dorsal hump's front, and a floor.
Seat: the F-102A's Weber ejection seat (armrest ejection handles) replaces the model's own seat group; no photo reference was
reachable, the proportions are the old group's.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. The canopy glass is canopy_f_102a.py's.

Run on the airframe's originals (f102a/run_f102a_cockpit.ps1 restores them), after canopy_f_102a.py.
Usage: python3 tools/cockpit_models/f_102a.py [--dry-run]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import free_atlas as FA   # noqa: E402
import canopy_f_102a as CM   # noqa: E402
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
    """faces (points k x 3 vehicle-local, uvs k x 2, normal); uvs come from the atlas (authored.lib.Atlas)"""

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

    def slab(self, pts, depth, front_rect, side_rect, toward):
        """a flat plate: the outline pts (one plane) as the front face toward [toward], extruded [depth] back"""
        P = np.array(pts, float)
        self.flat(P, front_rect, toward=toward)
        n = unit(np.array(toward, float) - P.mean(0))
        nn = np.cross(P[1] - P[0], P[2] - P[0]); nn = unit(nn) if nn @ n > 0 else -unit(nn)
        B = P - nn * depth
        self.flat(B, side_rect, normal=-nn)
        c = P.mean(0)
        for i in range(len(P)):
            a, b = P[i], P[(i + 1) % len(P)]
            mid = (a + b) / 2
            self.flat([a, b, B[(i + 1) % len(P)], B[i]], side_rect, toward=mid + (mid - c))

    def poly_mesh(self):
        P, N, U, polys = [], [], [], []
        for pts, uvs, n in self.faces:
            px = [[float(p[0]) * 16, float(p[1]) * 16, -float(p[2]) * 16] for p in pts]
            npx = np.array([n[0], n[1], -n[2]])
            a, b, c = (np.array(q) for q in px[:3])
            # the mesh loader mirrors X: in file pixels a face winds clockwise seen from its front (as mig_29.py)
            if np.cross(b - a, c - a) @ npx > 0:
                px = px[::-1]; uvs = uvs[::-1]
            ni = len(N); N.append([round(float(x), 5) + 0.0 for x in -npx])
            poly = []
            for p, uv in zip(px, uvs):
                poly.append([len(P), ni, len(U)])
                P.append([round(x, 4) + 0.0 for x in p]); U.append([round(uv[0], 6), round(uv[1], 6)])
            polys.append(poly)
        return {'normalized_uvs': True, 'positions': P, 'normals': N, 'uvs': U, 'polys': polys}


class Facet:
    """a flat panel facet: origin (the reference point, centre of its left-to-right span at height y0), right
    direction and up direction in the panel plane, normal toward the pilot. at(a, y): a metres along 'right' from
    the origin, at vehicle height y (the up axis leans, so z follows)"""

    def __init__(self, origin, right, up, normal):
        self.o = np.array(origin, float)
        self.R, self.U, self.N = unit(right), unit(up), unit(normal)

    def at(self, a, y, lift=0.0):
        v = (y - self.o[1]) / self.U[1]
        return self.o + self.R * a + self.U * v + self.N * lift


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


def lamp(d, box, colour, label=None, lit=False, px=None):
    x0, y0, x1, y1 = box
    base = tuple(int(c * (1.0 if lit else 0.45)) for c in colour)
    d.rectangle(box, fill=base + (255,), outline=(10, 10, 10, 255), width=2)
    d.rectangle([x0 + 3, y0 + 3, x1 - 3, y0 + (y1 - y0) * 0.35], fill=tuple(min(255, c + 30) for c in base) + (255,))
    if label:
        text(d, ((x0 + x1) / 2, (y0 + y1) / 2), label, px or (y1 - y0) * .42, fill=(20, 20, 20))


def p_console(colour, seed, heights, slot=None):
    """a console top seen from above: black sub-panels stacked fore to aft (tile top = forward) with toggles and
    knobs; slot = (x0, y0, x1, y1) fractions for a throttle slot"""
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 5, seed=seed)
        rng = np.random.default_rng(seed + 20)
        y = H * 0.02
        for hgt in heights:
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
        if slot:
            d.rectangle([S * slot[0], H * slot[1], S * slot[2], H * slot[3]], fill=(10, 10, 10, 255))
    return fn


def rotated(fn):
    """a painter for a tile laid on its side: [fn] paints the upright tile (its height the tile's width), the
    result is turned a quarter left (the upright tile's top ends at the left). A face's uv (s, t) on the upright
    tile is (1 - t, s) on the turned one"""
    def g(d, im):
        tmp = Image.new('RGBA', (im.height, im.width), (0, 0, 0, 255))
        fn(ImageDraw.Draw(tmp), tmp)
        im.paste(tmp.rotate(90, expand=True), (0, 0))
    return g


def p_wall(colour, line, seed):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 5, seed=seed)
        for k in range(3):
            x = S * (0.15 + k * 0.28)
            d.rectangle([x, H * .2, x + S * .18, H * .55], fill=(22, 24, 24, 255))
            for j in range(3):
                d.ellipse([x + S * .03 + j * S * .05, H * .3, x + S * .06 + j * S * .05, H * .38], fill=(170, 170, 170, 255))
        d.line([0, H * .62, S, H * .62], fill=tuple(line) + (255,), width=6)
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
    d.rectangle([0, 0, S, H], fill=(226, 186, 30, 255))
    for k in range(-H, S, 24):
        d.polygon([(k, H), (k + 12, H), (k + 12 + H, 0), (k + H, 0)], fill=(20, 20, 20, 255))


def p_label_plate(label, bg=(20, 22, 22)):
    def fn(d, im):
        d.rectangle([0, 0, im.width, im.height], fill=tuple(bg) + (255,))
        lines = label.split('|')
        px = min(im.height * .5 / len(lines), im.width * 1.6 / max(len(s) for s in lines))
        for k, s in enumerate(lines):
            text(d, (im.width / 2, im.height * (k + .5) / len(lines)), s, px)
    return fn


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

    def flat(self, rect):
        return self.uv(rect, 0.5, 0.5)

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


def write(ac, atlas):
    with open(ac.geo_path, 'w') as fh:
        json.dump(ac.geo, fh, separators=(',', ':'))
    L.write_asset(ac.asset_path, ac.asset)
    tex = os.path.normpath(L.texture_path(ac.vid))
    atlas.write(tex)
    dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
    if os.path.isfile(dead):
        atlas.write(dead, dead=True)




VID = 'f_102a'
SOURCE_BONE = 'wreck_fuselage_1__hull'
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'

PANEL = (52, 55, 58)           # US 1950s fighter: dark grey-black panels (references: F-102A cockpit photos, War Thunder)
PANEL_D = (36, 38, 41)
BLACK = (22, 23, 25)
DGREY = (46, 48, 51)
MGREY = (90, 94, 98)
WALLC = (84, 90, 88)           # the cockpit's grey-green side walls
FRAME = (70, 74, 76)           # seat structure
CUSHION = (64, 70, 60)
HEADPAD = (96, 98, 90)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (44, 47, 48)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
WALL_X = 0.395                 # side walls and consoles end here (the glass foot stands at x 0.44, the rails at 0.405..0.47)
FLOOR_Y = -0.95                # the model's floor top
BACK_Z = -0.535                # the rear bulkhead, just inside the canopy's rear bow (z -0.54), against the model's rear wall (z -0.55)
CONSOLE_Y = -0.62
CONSOLE_IN = 0.215
PANEL_Z = 0.62                 # centre facet reference plane
LEAN = math.radians(12)
HINGE = 0.19                   # centre facet half width
WING = math.radians(25)
WING_X = 0.34                  # the panel wings end here
WING_LEN = (WING_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.150, -0.310  # centre facet top, wing top at its end (under the sill)
BOT_C, BOT_W = -0.80, CONSOLE_Y

HUD_TOP = [0.10]
R_C = np.array([-1.0, 0, 0])                                   # pilot's right
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward the pilot

# the nose deck under the windscreen (the model's own skin, measured: eye-relative top y at |x| 0, 0.2, 0.3, 0.4)
DECK_X = [0.0, 0.2, 0.3, 0.4, 0.5]
DECK_Z = [0.6, 0.8, 1.0, 1.3, 1.6, 1.7]
DECK_Y = [[-0.18, -0.18, -0.27, -0.41, -0.43],
          [-0.22, -0.22, -0.32, -0.45, -0.48],
          [-0.26, -0.28, -0.37, -0.50, -0.55],
          [-0.33, -0.37, -0.45, -0.54, -0.60],
          [-0.40, -0.45, -0.51, -0.57, -0.63],
          [-0.43, -0.47, -0.53, -0.59, -0.65]]


def deck_y(x, z):
    rows = [np.interp(abs(x), DECK_X, r) for r in DECK_Y]
    return float(np.interp(z, DECK_Z, rows))


def sill_top(z):
    """the walls' top: just under the glass foot's rails"""
    return CM.foot_y(z) - 0.012


def facets(E):
    o = E + np.array([0, (TOP_C + BOT_C) / 2, PANEL_Z])
    centre = Facet(o, R_C, U_C, N_C)
    wings = {}
    for side in (1, -1):                                       # +1: the pilot's right wing
        R = R_C * side * math.cos(WING) + N_C * math.sin(WING)
        N = N_C * math.cos(WING) - R_C * side * math.sin(WING)
        hinge = o + R_C * side * HINGE
        wings[side] = Facet(hinge, R, U_C, N)                # a: metres outward from the hinge
    return centre, wings[1], wings[-1]


def top_of(side, a):
    return TOP_C if side == 0 else TOP_C + (TOP_W - TOP_C) * a / WING_LEN


# Instrument layout of the F-102A front panel: the MG-10 radar scope under its deep hood at the top centre, the flight
# instruments under it, airspeed / g and the armament and landing-gear panels on the left, the J57 engine group on the right.
# facet: 0 centre (a: metres to the pilot's right of the centre line), +1 right wing, -1 left wing (a: outward from the
# hinge). y: from the eye.
LAYOUT = [
    # centre facet
    ('scope',    0,   0.000, -0.215, ('display', 'RADAR', 0.170), 'MG-10 radar scope (under its hood)'),
    ('p_radar',  0,   0.000, -0.378, ('plate', 0.200, 0.016, 'MG-10 FIRE CONTROL'), 'scope placard'),
    ('adi',      0, -0.085, -0.520, ('game', 'ATTITUDE', 0.086), 'attitude indicator'),
    ('hsi',      0,  0.085, -0.520, ('game', 'HEADING', 0.086), 'radio magnetic indicator'),
    ('mach',     0, -0.085, -0.610, ('game', 'MACH', 0.080), 'Machmeter / airspeed'),
    ('alt',      0,  0.085, -0.610, ('game', 'ALTIMETER', 0.080), 'altimeter'),
    ('vvi',      0, -0.085, -0.700, ('game', 'VVI', 0.062), 'rate of climb'),
    ('clock',    0,  0.085, -0.700, ('game', 'CLOCK', 0.058), 'clock'),
    ('aoa',      0,  0.000, -0.700, ('game', 'AOA', 0.050), 'angle of attack'),
    # left wing
    ('ias',     -1,   0.085, -0.390, ('game', 'AIRSPEED', 0.074), 'airspeed indicator'),
    ('g',       -1,   0.085, -0.475, ('game', 'G_METER', 0.052), 'accelerometer'),
    ('weapons', -1,   0.095, -0.560, ('paint_rect', 0.125, 0.062), 'armament panel (FALCON / ROCKET select)'),
    ('gear',    -1,   0.095, -0.640, ('paint_rect', 0.100, 0.052), 'landing gear panel'),
    # right wing
    ('rpm',      1,   0.085, -0.385, ('game', 'ENGINE_RPM', 0.070), 'J57 tachometer'),
    ('egt',      1,   0.085, -0.470, ('game', 'EGT', 0.054), 'exhaust gas temperature'),
    ('fuel',     1,   0.085, -0.545, ('game', 'FUEL_QTY', 0.052), 'fuel quantity'),
    ('warning',  1,   0.095, -0.610, ('annunciator', 0.130, 0.056), 'warning lights'),
    ('p_eng',    1,   0.095, -0.665, ('plate', 0.130, 0.016, 'J57 ENGINE'), 'engine placard'),
]

LAMPS = (3, [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('MASTER|CAUTION', 'MASTER_CAUTION', 'amber'), ('GEAR|DOWN', 'GEAR_DOWN', 'green'),
    ('GEAR|UNSAFE', 'GEAR_TRANSIT', 'red'), ('FUEL|LOW', 'LOW_FUEL', 'amber'),
    ('FIRE', 'FIRE', 'red'), ('ENG|OUT', 'ENGINE_OUT', 'red')]])


# ---------------------------------------------------------------- painted faces

def panel_paint(side, W_, H_, a0, a1, yb, yt):
    def fn(d, im):
        SS = Atlas.SS
        noisy(d, im, PANEL, 5, seed=3 + side)

        def to_px(a, y):
            s = (a - a0) / (a1 - a0)
            if side == -1:
                s = 1 - s
            return s * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        for y in ((-0.565, -0.655) if side == 0 else (-0.35, -0.435, -0.51, -0.585)):
            x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
            d.line([x0, yy, x1, yy], fill=PANEL_D + (255,), width=SS * 2)
        for a, y in ((a0 + 0.012, yb + 0.012), (a1 - 0.012, yb + 0.012), (a0 + 0.012, yt - 0.02), (a1 - 0.012, yt - 0.02)):
            x, yy = to_px(a, y); r = SS * 2.2
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        for name, f, a, y, spec, _ in LAYOUT:
            if f != side:
                continue
            x, yy = to_px(a, y)
            px_m = W_ * SS / (a1 - a0)
            if spec[0] in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) * px_m
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 24, 26, 255))
            elif spec[0] == 'plate':
                pass
            else:
                w, h = (spec[2] * (1 + 2 * L.DISPLAY_BEZEL), spec[2] * (1 + 2 * L.DISPLAY_BEZEL)) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 24, 26, 255))
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['FALCON', 'ROCKET', 'READY', 'SALVO']
    for k, lab in enumerate(labs):
        x = S * (0.14 + k * 0.24)
        lamp(d, [x - 18, Hh * .14, x + 18, Hh * .38], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .48, x + 4, Hh * .70], fill=(190, 190, 190, 255))
        text(d, (x, Hh * .86), lab, Hh * .12)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        x = S * (0.2 + 0.3 * k)
        lamp(d, [x - 16, Hh * .12, x + 16, Hh * .52], (60, 150, 70), None, lit=True)
    text(d, (S / 2, Hh * .80), 'LANDING GEAR', Hh * .17)


def p_sight(d, im):
    """the reflector sight's aft face: the reticle unit's window, the brightness / range / depression knobs"""
    S, Hh = im.width, im.height
    noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['BRT', 'RANGE', 'SIGHT']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        text(d, (x, Hh * .93), lab, Hh * .09)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    text(d, (S * .5, Hh * .5), 'WEBER', Hh * .2, fill=(40, 40, 40), bold=True)


def p_back(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, CUSHION, 5, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(48, 54, 44, 255), width=3)
    text(d, (S * .5, Hh * .3), 'EJECT', Hh * .14, fill=(30, 30, 30), bold=True)
    for x in (S * .12, S * .88):
        for y in (Hh * .1, Hh * .9):
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(120, 124, 120, 255))


# ---------------------------------------------------------------- build

def build(atlas, E):
    m = Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                wall=WALLC, frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED,
                                                metal=METAL, floor=FLOOR, bezel=(30, 31, 32)).items()}
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    P = lambda x, y, z: E + np.array([x, y, z], float)          # noqa: E731  eye-relative point
    gauges, displays = [], []
    centre, right, left = facets(E)
    fac = {0: centre, 1: right, -1: left}
    ext = {0: (-HINGE, HINGE), 1: (0.0, WING_LEN), -1: (0.0, WING_LEN)}
    bot = {0: BOT_C, 1: BOT_W, -1: BOT_W}

    def at(side, a, y):
        return fac[side].at(a, E[1] + y)

    # -- the main panel: three facets, each a 2 cm slab with its painted face
    ppm = 600
    for side in (0, -1, 1):
        a0, a1 = ext[side]
        yt = max(top_of(side, a0), top_of(side, a1)); yb = bot[side]
        W_ = int(round((a1 - a0) * ppm)); H_ = int(round((yt - yb) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W_, H_, panel_paint(side, W_, H_, a0, a1, yb, yt))
        n = 6
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            t0, t1 = (top_of(side, aa0) - yb) / (yt - yb), (top_of(side, aa1) - yb) / (yt - yb)
            pts = [at(side, aa0, yb), at(side, aa1, yb), at(side, aa1, top_of(side, aa1)), at(side, aa0, top_of(side, aa0))]
            su = (lambda s: 1 - s) if side == -1 else (lambda s: s)
            m.face(pts, [m.uv(rect, su(s0), 0), m.uv(rect, su(s1), 0), m.uv(rect, su(s1), t1), m.uv(rect, su(s0), t0)],
                   normal=fac[side].N)
        back = -fac[side].N * 0.02
        corners = [at(side, a0, yb), at(side, a1, yb), at(side, a1, top_of(side, a1)), at(side, a0, top_of(side, a0))]
        m.flat([c + back for c in corners], sw['panel_d'], normal=-fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['panel_d'], toward=mid + (mid - mid0))

    # -- instruments
    cols, lamps = LAMPS
    hood_c = None
    for name, side, a, y, spec, _ in LAYOUT:
        f = fac[side]
        c = at(side, a, y)
        kind = spec[0]
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + f.N * 0.004), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 0, 'Center': r5(c + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
            hood_c = c
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'weapons': p_weapons, 'gear': p_gear}[name]
            ph = 96 if h >= w else max(16, int(round(160 * h / w)))
            pw = 160 if w >= h else max(16, int(round(96 * w / h)))
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.04 - f.U * 0.0, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.0, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])
        elif kind == 'plate':                                      # an English placard: black plate, white letters
            w, h, label = spec[1], spec[2], spec[3]
            pw = max(24, int(w * 1000)); ph = max(12, int(h * 1000))
            rect = atlas.paint(pw, ph, p_label_plate(label))
            m.rect_tex(c + f.N * 0.003, f.N, f.U, w, h, rect)

    # -- the MG-10 scope hood: a deep rectangular rubber visor standing out of the panel toward the pilot, open at the back;
    # its top stays under the eye line (-0.05 at the lip) so the view ahead is open
    HW, HH, D, T = 0.150, 0.125, 0.22, 0.012
    c0 = hood_c
    ax = (R_C, U_C, N_C)
    for s in (1, -1):
        m.box(c0 + U_C * s * (HH - T / 2) + N_C * D / 2, ax, (HW, T / 2, D / 2), sw['black'])
        m.box(c0 + R_C * s * (HW - T / 2) + N_C * D / 2, ax, (T / 2, HH, D / 2), sw['black'])
    m.box(c0 + U_C * (HH - T) + N_C * (D + 0.012), ax, (HW + 0.008, 0.014, 0.012), sw['black'])       # the eyepiece lip, top
    m.box(c0 - U_C * (HH - T) + N_C * (D + 0.012), ax, (HW + 0.008, 0.014, 0.012), sw['black'])       # bottom
    for s in (1, -1):
        m.box(c0 + R_C * s * (HW - T) + N_C * (D + 0.012), ax, (0.014, HH, 0.012), sw['black'])       # sides

    # -- coaming: the black glareshield over the model's own nose deck, from the panel's lip forward to the windscreen's base
    lip = []
    for side, aa in ((-1, np.linspace(WING_LEN, 0, 4)), (0, np.linspace(HINGE, -HINGE, 7)), (1, np.linspace(0, WING_LEN, 4))):
        for a in aa:
            p = at(side, a, top_of(side, a))
            if lip and np.linalg.norm(lip[-1][0] - p) < 1e-6:
                continue
            lip.append((p, fac[side]))
    lip.sort(key=lambda q: -q[0][0])
    lip_edge = [p + f.N * 0.045 + np.array([0, 0.015, 0]) for p, f in lip]
    panel_top = [p for p, f in lip]
    ZS = [lip_edge[0][2] - E[2], 0.80, 1.00, 1.30, 1.60, 1.70]
    rows = []
    for k, zrow in enumerate(ZS):
        row = []
        xw = 0.330 - 0.080 * max(0.0, zrow - 0.66)
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            x = s * xw
            z = E[2] + zrow
            y = E[1] + deck_y(x, zrow) + 0.006
            if k == 0:
                x, y, z = le[0], le[1], le[2]
            row.append(np.array([E[0] + x if k else x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], sw['black'], normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):
        a, b = lip_edge[j], lip_edge[j + 1]
        da, db = a - [0, 0.012, 0], b - [0, 0.012, 0]
        m.flat([a, b, db, da], sw['black'], toward=E)
        m.flat([da, db, panel_top[j + 1], panel_top[j]], sw['black'], normal=[0, -1, 0])
    for j in (0, len(lip_edge) - 1):                                # the coaming's side ends down to the deck
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            yb_ = min(p[1], q[1]) - 0.06
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=[E[0], yb_, (p[2] + q[2]) / 2])

    # -- the reflector sight on the hood's top: the body, its aft face, the reflector frame and its glass
    sight = atlas.paint(96, 64, p_sight)
    g_h, g_hw = 0.100, 0.060
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    g_c = np.array([E[0], E[1] - 0.005, E[2] + 0.560])
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    body_c = np.array([E[0], E[1] - 0.055, E[2] + 0.525])
    m.box(body_c, (X, Y, Z), (0.060, 0.030, 0.080), sw['dgrey'], front=(2, -1, sight, -X, Y))
    for s in (-1, 1):
        m.box(g_c + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    m.box(g_bot - g_up * 0.005, (X, g_up, g_n), (g_hw + 0.008, 0.005, 0.010), sw['dgrey'])
    m.box(g_top + g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='REFLECTOR')
    gl = (g_hw - 0.001, g_h / 2 - 0.001)
    glass = [g_c - X * gl[0] - g_up * gl[1], g_c + X * gl[0] - g_up * gl[1], g_c + X * gl[0] + g_up * gl[1],
             g_c - X * gl[0] + g_up * gl[1]]
    hud['glass'] = [glass[0], glass[1], glass[2], glass[0], glass[2], glass[3]]

    # -- side walls, consoles, the closing faces under the panel
    zw = [BACK_Z, -0.3, -0.1, 0.1, 0.3, 0.5, 0.6]
    for side in (1, -1):                                            # +1 = left (x > 0)
        xb = E[0] + WALL_X * side
        wall = atlas.paint(120, 60, p_wall(PANEL_D, WALLC, 30 + side))
        y0 = E[1] + FLOOR_Y
        ua, ub, uc, ud = m.uv(wall, 0, 0), m.uv(wall, 1, 0), m.uv(wall, 1, 1), m.uv(wall, 0, 1)
        if side < 0:
            ua, ub, uc, ud = ub, ua, ud, uc
        for k in range(len(zw) - 1):                                # the lining, its top following the glass foot
            za, zb_ = E[2] + zw[k], E[2] + zw[k + 1]
            qa = [xb, y0, za]; qb = [xb, y0, zb_]
            qc = [xb, E[1] + sill_top(zw[k + 1]), zb_]; qd = [xb, E[1] + sill_top(zw[k]), za]
            m.face([qa, qb, qc], [ua, ub, uc], normal=[-side, 0, 0])
            m.face([qa, qc, qd], [ua, uc, ud], normal=[-side, 0, 0])
        wing = left if side > 0 else right

        def wing_bottom_z(x):
            a = (abs(x) - HINGE) / math.cos(WING)
            return wing.at(a, E[1] + BOT_W)[2]
        xi, xo = E[0] + CONSOLE_IN * side, E[0] + WALL_X * side
        zf_i, zf_o = wing_bottom_z(CONSOLE_IN), wing_bottom_z(WALL_X)
        cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
        zb = E[2] + BACK_Z + 0.06
        con = atlas.paint(240, 36, rotated(p_console(PANEL, 20 + side, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                               else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                               slot=(0.44, 0.30, 0.56, 0.62) if side < 0 else None)))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['panel_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['panel_d'], normal=[0, 0, -1])
    # the rear bulkhead behind the seat, closing the dorsal hump's front (what shows through the glass behind the pilot):
    # the canopy's own rear section from the floor up over the top
    sec = CM.section(CM.Z_RB[1] - 0.01)
    out_r = [(WALL_X, FLOOR_Y + 0.01), (WALL_X, CM.foot_y(BACK_Z) - 0.012)] + [(max(x - 0.012, 0.0), y - 0.006) for x, y in sec[1:-1]] + [(0.0, sec[-1][1] - 0.01)]
    ring = [P(x, y, BACK_Z) for x, y in out_r] + [P(-x, y, BACK_Z) for x, y in out_r[::-1] if x > 1e-6]
    wallp = atlas.paint(120, 90, p_wall(PANEL_D, WALLC, 77))
    cb = np.mean(ring, 0)
    for i in range(len(ring)):
        a_, b_ = ring[i], ring[(i + 1) % len(ring)]
        m.face([cb, a_, b_], [m.uv(wallp, 0.5, 0.5), m.uv(wallp, 0.1 + 0.8 * i / len(ring), 0.2),
                              m.uv(wallp, 0.1 + 0.8 * (i + 1) / len(ring), 0.2)], normal=[0, 0, 1])
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['panel_d'], normal=[0, -1, 0])
    # the floor: a dark plate over the model's own floor, from the bulkhead to the pedals
    fw = WALL_X
    m.flat([P(-fw, FLOOR_Y + 0.012, BACK_Z), P(fw, FLOOR_Y + 0.012, BACK_Z),
            P(fw, FLOOR_Y + 0.012, PANEL_Z + 0.20), P(-fw, FLOOR_Y + 0.012, PANEL_Z + 0.20)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.845, 0.46), (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -0.915, 0.50), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- the J57's single throttle lever on the left console, and the radar hand controller on the right console
    tz, tx = -0.12, 0.30
    m.box(P(tx, CONSOLE_Y + 0.055, tz), (X, unit([0, 1, 0.25]), unit([0, -0.25, 1])), (0.007, 0.06, 0.007), sw['metal'])
    grip_ax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
    m.box(P(tx, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.019, 0.042, 0.034), sw['black'])
    m.box(P(tx, CONSOLE_Y + 0.183, tz + 0.03), grip_ax, (0.016, 0.009, 0.028), sw['dgrey'])
    m.box(P(tx, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.003, 0.008, 0.008), sw['red'])
    rx, rz = -0.29, 0.02                                           # the radar hand controller: a small pistol grip
    m.box(P(rx, CONSOLE_Y + 0.030, rz), (X, Y, Z), (0.030, 0.030, 0.040), sw['dgrey'])
    m.box(P(rx, CONSOLE_Y + 0.085, rz - 0.004), (X, unit([0, 1, 0.2]), unit([0, -0.2, 1])), (0.014, 0.040, 0.016), sw['black'])
    m.box(P(rx, CONSOLE_Y + 0.130, rz + 0.004), (X, unit([0, 1, 0.2]), unit([0, -0.2, 1])), (0.011, 0.007, 0.014), sw['metal'])
    m.box(P(rx, CONSOLE_Y + 0.108, rz + 0.016), (X, unit([0, 1, 0.2]), unit([0, -0.2, 1])), (0.004, 0.006, 0.004), sw['red'])

    # -- the Weber ejection seat: bucket and pan, a parachute back, headbox with a pad, two rails, the armrests with the
    # raised yellow-and-black ejection handles; at the model's old seat group (headrest front face z -0.31)
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    back = atlas.paint(48, 64, p_back)
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    ax_b = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
    m.box(P(0, -0.915, -0.14), (X, Y, Z), (0.19, 0.035, 0.29), sw['frame'])                       # the seat base (floor -0.95)
    m.box(P(0, -0.775, -0.14), (X, Y, Z), (0.17, 0.030, 0.29), sw['cushion'], front=(1, 1, cushion, -X, Z))   # the pan
    for s in (1, -1):
        m.box(P(s * 0.195, -0.72, -0.15), (X, Y, Z), (0.012, 0.12, 0.27), sw['frame'])           # bucket sides
        m.box(P(s * 0.15, -0.36, -0.44), ax_b, (0.018, 0.40, 0.020), sw['frame'])               # guide rails up to the headbox
        m.box(P(s * 0.215, -0.60, -0.10), (X, Y, Z), (0.014, 0.012, 0.19), sw['dgrey'])          # armrest
        m.box(P(s * 0.215, -0.575, 0.075), (X, Y, Z), (0.012, 0.030, 0.012), sw['metal'])        # the handle's post
        m.box(P(s * 0.215, -0.538, 0.085), (X, Y, Z), (0.012, 0.012, 0.032), sw['yellow'], front=(2, 1, stripes, X, Y))
    m.box(P(0, -0.43, -0.445), ax_b, (0.19, 0.32, 0.020), sw['cushion'], front=(2, 1, back, X, ax_b[1]))   # the parachute back
    m.box(P(0, -0.04, -0.385), (X, Y, Z), (0.15, 0.11, 0.070), sw['frame'])                      # headbox (top y +0.07)
    m.box(P(0, -0.03, -0.3145), (X, Y, Z), (0.11, 0.09, 0.0045), sw['cushion'], front=(2, 1, headbox, X, Y))

    # -- control stick: the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.33
    m.box(P(0, -0.935, sz), (X, Y, Z), (0.045, 0.035, 0.045), sw['dgrey'])
    stick = Mesh(atlas)
    col_ax = (X, unit([0, 1, -0.07]), unit([0, 0.07, 1]))
    stick.box(P(0, -0.73, sz - 0.002), col_ax, (0.011, 0.20, 0.011), sw['metal'])
    g_ax = (X, unit([0, 1, 0.21]), unit([0, -0.21, 1]))
    gc = P(0, -0.52, sz - 0.006)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])       # trigger
    stick.box(gc + g_ax[1] * 0.040 + g_ax[2] * -0.027, g_ax, (0.010, 0.012, 0.004), sw['yellow'])     # the radar thumb switch
    foot = P(0, -0.93, sz)
    return m, (stick, foot), gauges, displays, hud


# ---------------------------------------------------------------- the airframe

def install(ac, mesh, stick, gauges, displays, hud, eye, seat=0):
    """the cockpit bone, the stick, the live entries; the canopy's CanopyGlass stays (the sight's glass is added to it)"""
    pm = mesh.poly_mesh()
    piece = SOURCE_BONE.split('__', 1)[0]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
    hull = ac.bone('hull')
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == SOURCE_BONE) + 1
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    rig = ac.asset.get('AircraftRig')
    mesh_s, foot = stick
    px = r5(np.array(foot) * np.array([16.0, 16.0, -16.0]))
    for b in ac.bones:
        if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
            b['pivot'] = px
            if b['name'].endswith('__control_stick_pitch'):
                b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    ac.bone(STICK_BONE)['poly_mesh'] = mesh_s.poly_mesh()
    for sf in (rig or {}).get('Surfaces', []):
        if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
            sf['Pivot'] = px
    a = ac.asset
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    a['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat, 'Center': r5(hud['centre']),
                          'Normal': r5(hud['normal']), 'Up': r5(hud['up']), 'Width': round(float(hud['width']), 5),
                          'Height': round(float(hud['height']), 5), 'Eye': r5(eye), 'Style': hud['style']}
    if hud.get('glass'):                       # the reflector's glass, on top of the canopy's
        cg = a.get('CanopyGlass')
        if cg:                                 # (drop the sight glass of an earlier run: it lies inside the sight's box)
            t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
            c = t.mean(1)
            keep = ~((np.abs(c[:, 0] - eye[0]) < 0.08) & (np.abs(c[:, 1] - eye[1]) < 0.09) & (c[:, 2] > eye[2] + 0.45) &
                     (c[:, 2] < eye[2] + 0.62))
            cg['Triangles'] = [round(float(v), 4) + 0.0 for v in t[keep].reshape(-1)]
        L.add_glass(a, hud['glass'])
    return pm


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    atlas = Atlas(VID)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = install(ac, mesh, stick, gauges, displays, hud, E)
    print(f'{VID}: new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} display')
    if not dry:
        write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
