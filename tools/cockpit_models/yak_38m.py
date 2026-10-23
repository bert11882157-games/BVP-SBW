#!/usr/bin/env python3
"""Yak-38M cockpit, built by hand for this model (as mig_25pd.py, su_15tm.py, mig_29.py), from the real panel layout.

Measured off the model (the faces of Yak-38M.mtb in checks/orig, work/elite_expansion_2/yak38/NOTES.md section 8; all heights and
distances from the pilot's eye at (0, 2.34618, 2.46611); 1 block = 1 m on this model):
  * the cockpit is open to the sky above the shoulders: the shoulders beside it slope down toward the nose (the canopy's
    sill rails follow y -0.16 - 0.33 (z + 0.7)), their inner faces stand at x 0.349; the floor at y -1.15; the dorsal
    fairing's front face at z -0.848; the model's seat group: back z -0.50, headrest face -0.30, head top +0.05;
  * the old panel and sight bars stood 0.30 .. 0.65 m ahead of the eye; the windscreen's base bar on the nose deck is
    1.27 m ahead (y -0.50, the nose deck there flat at |x| < 0.2).
Built:
  * a three-facet main panel 0.50 m ahead, 12 degrees back, its wings turned 25 degrees toward the pilot to the walls:
    the flight group on the centre facet (KPP attitude director and NPP horizontal situation left, the airspeed / Mach and the
    altimeter right, VVI and AoA between them, turn and slip, fuel, the clock), the VTOL mode panel and the nozzle-angle
    indicator over it, the automatic-ejection warning lamp top left; airspeed, accelerometer, the weapons panel (Kh-23, R-60,
    rockets, GSh-23) and the gear panel on the left wing; the R-28V-300 tachometer and exhaust gas temperature, the two RD-38
    lift-engine dials (rpm, gas temperature), hydraulics, oxygen, SPO-10 radar warning and the warning-light block on the
    right wing; Cyrillic placards;
  * the black coaming forward to the windscreen's base bar (1.27 m ahead of the eye), closed down to the nose bay's floor;
  * the ASP-17 class reflector sight on the coaming (HeadUpDisplay Style REFLECTOR, seat 0; the Yak-38 had no head-up display);
  * low side consoles with switch panels, side walls under the sloping sill, rudder pedals, the R-28V-300 throttle and the
    nozzle lever on the left console, a K-36VM ejection seat (Yak-38's automatic ejection: the red lamp on the panel) and the
    control stick (into the stick bone), a rear bulkhead closing the dorsal fairing's mouth behind the seat.
Seat: the Yak-38 flew on the K-36VM (a modified K-36 with the automatic ejection system; the kit reviews and the Yak-38 descriptions);
the airframe's model carries an unnamed seat group, which is replaced here.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live; the lift-engine dials are the game's static
filler dials (no lift-engine flight model exists). The canopy glass is canopy_yak_38m.py's.

Run on the airframe's originals (yak38/run_yak38_cockpit.ps1 restores them), after canopy_yak_38m.py.
Usage: python3 tools/cockpit_models/yak_38m.py [--dry-run]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import free_atlas as FA   # noqa: E402
import canopy_yak_38m as CM   # noqa: E402  (the glass the panel and coaming stay under)
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


VID = 'yak_38m'
SOURCE_BONE = 'wreck_fuselage_1__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'
H1, H0 = 'wreck_fuselage_1__hull', 'wreck_fuselage_0__hull'

GREYT = (92, 112, 112)         # the Navy Yak's grey-turquoise panel (the kit reviews: grey panel, black bezels)
GREYT_D = (60, 74, 74)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (62, 68, 64)           # seat structure
CUSHION = (58, 62, 52)
HEADPAD = (92, 96, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from the eye)
# (the shoulders beside the cockpit slope down toward the nose: the canopy's sill rails follow y -0.16 - 0.33 (z + 0.7);
# the model's shoulder inner faces stand at x 0.349, the dorsal fairing's front face at z -0.848, the floor at y -1.15)
WALL_X = 0.268                 # the side walls and consoles' outer edge: every restored skin vertex near the cockpit stands at x >= 0.275, so
                               # nothing of the cockpit bone is wider than this (the canopy's rail ledge closes the gap to the rail at 0.295)
FLOOR_Y = -1.15
BACK_Z = -0.815                # the rear bulkhead (the fairing's front face is at -0.848, the canopy's rear bow at -0.84 .. -0.80)
CONSOLE_Y = -0.78
CONSOLE_IN = 0.225
PANEL_Z = 0.50                 # centre facet reference plane
LEAN = math.radians(12)
HINGE = 0.13                   # centre facet half width
WING_X = 0.262                 # the panel wings end here
WING = math.radians(25)
WING_LEN = (WING_X - HINGE) / math.cos(WING)
TOP_C, TOP_W = -0.150, -0.425  # centre facet top, wing top at its end (under the glass, which stands at -0.37 there)
BOT_C, BOT_W = -0.74, CONSOLE_Y
FRONT_Z = CM.Z_B0 - 0.006      # the coaming runs forward to the windscreen's base bar (rear face 1.27 ahead of the eye)
FRONT_Y = CM.yc(CM.Z_B0) - 0.040   # ... meeting the base bar's underside
FRONT_X = 0.140                # the coaming's half width at the base bar (the nose narrows)
HUD_TOP = [0.060]              # how far above the eye the sight's glass may reach (the canopy's crown there: +0.125)
HEAD_Z = -0.300                # the seat's headrest face (the model's seat group: back z -0.50, headrest face -0.30, head top +0.05)
R_C = np.array([-1.0, 0, 0])                                   # pilot's right
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward the pilot


def facets(E):
    o = E + np.array([0, (TOP_C + BOT_C) / 2, PANEL_Z])
    centre = Facet(o, R_C, U_C, N_C)
    wings = {}
    for side in (1, -1):                                       # +1: the pilot's right wing
        R = R_C * side * math.cos(WING) + N_C * math.sin(WING)
        N = N_C * math.cos(WING) - R_C * side * math.sin(WING)
        hinge = o + R_C * side * HINGE
        wings[side] = Facet(hinge, R, U_C, N)                  # a: metres outward from the hinge
    return centre, wings[1], wings[-1]


def top_of(side, a):
    return TOP_C if side == 0 else TOP_C + (TOP_W - TOP_C) * a / WING_LEN


# Instrument layout of the Yak-38M front panel: the flight group (KPP attitude, NPP horizontal situation, speed, altimeter,
# Mach, VVI) on the centre facet with the VTOL mode panel and the nozzle-angle indicator over it, the automatic-ejection
# warning lamp top left; the weapons and gear panels on the left wing; the R-28V-300 dials (tachometer, EGT) and the two
# RD-38 lift-engine dials (rpm, temperature) with the warning-light block on the right wing.
# facet: 0 centre (a: metres to the pilot's right of the centre line), +1 right wing, -1 left wing (a: outward from the
# hinge). y: from the eye.
LAYOUT = [
    # centre facet
    ('eject',    0, -0.108, -0.205, ('paint_disc', 0.025), 'automatic-ejection warning lamp (red)'),
    ('p_eject',  0, -0.083, -0.243, ('plate', 0.072, 0.013, 'АВТ.КАТАП.'), 'its placard'),
    ('clock',    0,  0.104, -0.208, ('game', 'CLOCK', 0.033), 'AChS-1 clock'),
    ('vtol',     0,  0.013, -0.260, ('paint_rect', 0.096, 0.050), 'VTOL mode panel (take-off / landing, lift engines, doors)'),
    ('nozzle',   0,  0.000, -0.345, ('paint_rect', 0.061, 0.061), 'nozzle-angle indicator'),
    ('adi',      0, -0.077, -0.464, ('game', 'ATTITUDE', 0.079), 'KPP attitude director'),
    ('hsi',      0, -0.078, -0.556, ('game', 'HEADING', 0.077), 'NPP horizontal situation'),
    ('mach',     0,  0.077, -0.464, ('game', 'MACH', 0.079), 'Mach / true airspeed'),
    ('alt',      0,  0.078, -0.556, ('game', 'ALTIMETER', 0.077), 'VD-17 altimeter'),
    ('vvi',      0,  0.000, -0.445, ('game', 'VVI', 0.044), 'VAR-300 vertical speed'),
    ('aoa',      0,  0.000, -0.500, ('game', 'AOA', 0.042), 'UUA AoA'),
    ('turn',     0, -0.095, -0.640, ('game', 'TURN_SLIP', 0.048), 'EUP turn and slip'),
    ('fuel',     0,  0.095, -0.640, ('game', 'FUEL_QTY', 0.048), 'fuel quantity'),
    ('p_flight', 0,  0.000, -0.688, ('plate', 0.144, 0.013, 'ПИЛОТАЖНЫЕ ПРИБОРЫ'), 'flight group placard'),
    # left wing
    ('ias',     -1,  0.070, -0.394, ('game', 'AIRSPEED', 0.062), 'KUS airspeed'),
    ('g',       -1,  0.070, -0.460, ('game', 'G_METER', 0.042), 'accelerometer'),
    ('weapons', -1,  0.072, -0.545, ('paint_rect', 0.100, 0.052), 'weapons panel'),
    ('gear',    -1,  0.117, -0.640, ('paint_rect', 0.036, 0.085), 'gear control panel'),
    # right wing
    ('rpm',      1,  0.034, -0.365, ('game', 'ENGINE_RPM', 0.044), 'R-28V-300 tachometer'),
    ('egt',      1,  0.092, -0.425, ('game', 'EGT', 0.042), 'R-28V-300 exhaust gas temperature'),
    ('lift1',    1,  0.034, -0.432, ('game', 'FILLER_RPM', 0.032), 'RD-38 lift engine 1 rpm'),
    ('lift2',    1,  0.034, -0.480, ('game', 'FILLER_RPM', 0.032), 'RD-38 lift engine 2 rpm'),
    ('lifttemp', 1,  0.080, -0.480, ('game', 'FILLER_TEMP', 0.032), 'RD-38 gas temperature'),
    ('p_lift',   1,  0.073, -0.515, ('plate', 0.118, 0.013, 'РД-38 ПОДЪЁМНЫЕ'), 'lift-engine placard'),
    ('hyd',      1,  0.030, -0.556, ('game', 'FILLER_HYDRAULIC', 0.032), 'hydraulic pressures'),
    ('oxy',      1,  0.071, -0.556, ('game', 'FILLER_OXYGEN', 0.028), 'oxygen'),
    ('spo10',    1,  0.111, -0.556, ('game', 'RWR', 0.032), 'SPO-10 radar warning'),
    ('warning',  1,  0.073, -0.620, ('annunciator', 0.117, 0.062), 'warning lights'),
    ('p_eng',    1,  0.073, -0.672, ('plate', 0.117, 0.013, 'Р-28В-300'), 'engine placard'),
]


# ---------------------------------------------------------------- painted faces

def panel_paint(side, W_, H_, a0, a1, yb, yt):
    def fn(d, im):
        SS = Atlas.SS
        noisy(d, im, GREYT, 5, seed=3 + side)

        def to_px(a, y):
            s = (a - a0) / (a1 - a0)
            if side == -1:
                s = 1 - s
            return s * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        for y in ((-0.30, -0.40, -0.50) if side == 0 else (-0.35, -0.44, -0.52)):
            x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
            d.line([x0, yy, x1, yy], fill=GREYT_D + (255,), width=SS * 2)
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
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 26, 26, 255))
            elif spec[0] == 'plate':
                pass
            else:
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['Х-23', 'Р-60', 'НАР', 'ГШ-23']
    for k, lab in enumerate(labs):
        x = S * (0.14 + k * 0.24)
        lamp(d, [x - 18, Hh * .14, x + 18, Hh * .38], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .48, x + 4, Hh * .70], fill=(190, 190, 190, 255))
        text(d, (x, Hh * .86), lab, Hh * .15)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        y = Hh * (0.10 + 0.10 * k)
        lamp(d, [S * .2, y, S * .8, y + Hh * .07], (60, 150, 70), None, lit=True)
    text(d, (S / 2, Hh * .55), 'ШАССИ', S * .17)
    d.rectangle([S * .4, Hh * .62, S * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_vtol(d, im):
    """the VTOL mode panel: two rows of three lamp legends"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    rows = [[('ВЗЛЁТ', (60, 170, 80), True), ('ПОДЪЁМН', (60, 170, 80), True), ('ЛЮКИ', (230, 170, 40), False)],
            [('ПОСАДКА', (60, 170, 80), False), ('СОПЛА', (230, 170, 40), True), ('АВТОМАТ', (210, 60, 50), False)]]
    for r, row in enumerate(rows):
        for c, (lab, col, lit) in enumerate(row):
            x0 = S * (0.04 + c * 0.32); y0 = Hh * (0.08 + r * 0.46)
            lamp(d, [x0, y0, x0 + S * 0.28, y0 + Hh * 0.38], col, lab, lit=lit, px=Hh * .115)


def p_nozzle(d, im):
    """the nozzle-angle indicator: a 0 .. 120 degree scale, the needle at the aft-pointing (cruise) end"""
    S = im.width
    d.rectangle([0, 0, S, im.height], fill=(20, 22, 22, 255))
    c = S / 2
    d.ellipse([S * .06, S * .06, S * .94, S * .94], fill=(8, 10, 12, 255), outline=(150, 150, 150, 255), width=3)
    for k in range(0, 13):
        a = math.radians(-120 + k * 20)
        r0, r1 = S * .40, S * (.31 if k % 3 == 0 else .35)
        d.line([c + r0 * math.sin(a), c - r0 * math.cos(a), c + r1 * math.sin(a), c - r1 * math.cos(a)], fill=WHITE + (255,), width=3 if k % 3 == 0 else 2)
    for k, lab in ((0, '0'), (3, '60'), (6, '120')):
        a = math.radians(-120 + k * 40)
        text(d, (c + S * .24 * math.sin(a), c - S * .24 * math.cos(a)), lab, S * .09)
    text(d, (c, c + S * .20), 'СОПЛО', S * .10)
    a = math.radians(-112)
    d.line([c, c, c + S * .34 * math.sin(a), c - S * .34 * math.cos(a)], fill=(255, 138, 28, 255), width=5)
    d.ellipse([c - 5, c - 5, c + 5, c + 5], fill=(60, 60, 60, 255))


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(150, 20, 16, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(240, 110, 100, 255))


def p_sight(d, im):
    """the ASP-17's aft face: the reticle unit's window, the brightness / base / cage knobs"""
    S, Hh = im.width, im.height
    noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['ЯРК', 'БАЗА', 'АСП-17']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        text(d, (x, Hh * .93), lab, Hh * .09)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    text(d, (S * .5, Hh * .5), 'К-36ВМ', Hh * .22, fill=(40, 40, 40), bold=True)


def p_armour(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, (74, 78, 74), 4, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(56, 60, 56, 255), width=3)
    for x in (S * .12, S * .88):
        for y in (Hh * .1, Hh * .9):
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(120, 124, 120, 255))


# ---------------------------------------------------------------- build

def glass_top(x, z):
    """the canopy glass's height over (|x|, z) in eye-relative blocks (what the coaming and panel must stay under)"""
    x = abs(x)
    if z <= CM.Z_END:
        p = np.array(CM.section(z, 90))[::-1]
        return float(np.interp(x, p[:, 0], p[:, 1]))
    if x <= CM.hw(z) + CM.POST:
        return CM.yc(z)
    n = np.cross(CM.T2 - CM.T1, CM.B1 - CM.T1)                  # the quarter pane's plane
    y = CM.T1[1] - (n[0] * (x - CM.T1[0]) + n[2] * (z - CM.T1[2])) / n[1]
    return float(max(y, CM.strip_xy(z)[1]))


def build(atlas, E):
    m = Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(turq=GREYT, turq_d=GREYT_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED, metal=METAL,
                                                floor=FLOOR, bezel=(30, 31, 32)).items()}
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
        m.flat([c + back for c in corners], sw['turq_d'], normal=-fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['turq_d'], toward=mid + (mid - mid0))

    # -- instruments
    cols, lamps = L.lamps_of('cw', 'soviet')
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
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = {'weapons': p_weapons, 'gear': p_gear, 'vtol': p_vtol, 'nozzle': p_nozzle}[name]
            ph = 96 if h >= w else max(16, int(round(160 * h / w)))
            pw = 160 if w >= h else max(16, int(round(96 * w / h)))
            if name == 'nozzle':
                pw = ph = 96
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.04 - f.U * 0.02, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.02, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])
        elif kind == 'plate':                                      # a Cyrillic placard: black plate, white letters
            w, h, label = spec[1], spec[2], spec[3]
            pw = max(24, int(w * 1000)); ph = max(12, int(h * 1000))
            rect = atlas.paint(pw, ph, p_label_plate(label))
            m.rect_tex(c + f.N * 0.003, f.N, f.U, w, h, rect)

    # -- coaming: lofted from the panel lip forward to the windscreen's base bar, always under the glass
    lip = []
    for side, aa in ((-1, np.linspace(WING_LEN, 0, 4)), (0, np.linspace(HINGE, -HINGE, 7)), (1, np.linspace(0, WING_LEN, 4))):
        for a in aa:
            p = at(side, a, top_of(side, a))
            if lip and np.linalg.norm(lip[-1][0] - p) < 1e-6:
                continue
            lip.append((p, fac[side]))
    lip.sort(key=lambda q: -q[0][0])
    lip_edge = [p + f.N * 0.045 + np.array([0, 0.025, 0]) for p, f in lip]
    panel_top = [p for p, f in lip]
    rows = []
    for t in (0.0, 0.2, 0.4, 0.6, 0.8, 1.0):
        row = []
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + s * FRONT_X
            yf = E[1] + FRONT_Y
            z = le[2] + (E[2] + FRONT_Z - le[2]) * t
            x = le[0] + (xf - le[0]) * min(1.0, t * 1.5)          # (narrowing fast: the nose skin is narrow under the quarter panes)
            y = le[1] + (yf - le[1]) * t + 0.02 * math.sin(math.pi * t) * (1 - s * s)
            cap = E[1] + glass_top(x - E[0], z - E[2]) - 0.045      # under the glass
            if abs(x - E[0]) > 0.12:
                y = min(y, cap)
            x = E[0] + float(np.clip(x - E[0], -WALL_X, WALL_X))
            row.append(np.array([x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], sw['black'], normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):
        a, b = lip_edge[j], lip_edge[j + 1]
        da, db = a - [0, 0.012, 0], b - [0, 0.012, 0]
        m.flat([a, b, db, da], sw['black'], toward=E)
        m.flat([da, db, panel_top[j + 1], panel_top[j]], sw['black'], normal=[0, -1, 0])
    yb_ = E[1] + FLOOR_Y + 0.25                                   # the coaming's side ends and front end down into the nose bay's floor: closed tub
    for j in (0, len(lip_edge) - 1):
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=[E[0], yb_, (p[2] + q[2]) / 2])
    last = rows[-1]
    for j in range(len(last) - 1):
        p, q = last[j], last[j + 1]
        m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=E)

    # -- ASP-17 class reflector sight on the coaming: the body, its aft face, the reflector frame and its glass
    sight = atlas.paint(96, 64, p_sight)
    g_h, g_hw = 0.085, 0.060
    g_h = min(g_h, 2 * HUD_TOP[0] - 0.012)
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    base_y = E[1] + TOP_C + 0.045                                  # the coaming's top at the lip
    g_c = np.array([E[0], E[1] - 0.012, E[2] + PANEL_Z - 0.045])
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    body_top = base_y + 0.055
    body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + PANEL_Z - 0.03])
    m.box(body_c, (X, Y, Z), (0.060, (body_top - base_y) / 2, 0.085), sw['dgrey'], front=(2, -1, sight, -X, Y))
    if g_bot[1] - 0.006 > body_top + 0.003:
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.004), (body_top + g_bot[1] - 0.006) / 2, g_bot[2]], (X, Y, Z),
                  (0.004, (g_bot[1] - 0.006 - body_top) / 2, 0.005), sw['dgrey'])
    m.box(g_bot - g_up * 0.005, (X, g_up, g_n), (g_hw + 0.008, 0.005, 0.010), sw['dgrey'])
    for s in (-1, 1):
        m.box(g_c + X * s * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), sw['dgrey'])
    m.box(g_top + g_up * 0.004, (X, g_up, g_n), (g_hw + 0.008, 0.004, 0.005), sw['dgrey'])
    hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, style='REFLECTOR')
    gl = (g_hw - 0.001, g_h / 2 - 0.001)
    glass = [g_c - X * gl[0] - g_up * gl[1], g_c + X * gl[0] - g_up * gl[1], g_c + X * gl[0] + g_up * gl[1],
             g_c - X * gl[0] + g_up * gl[1]]
    hud['glass'] = [glass[0], glass[1], glass[2], glass[0], glass[2], glass[3]]

    # -- side walls, consoles, the closing faces under the panel (the walls follow the sloping sill, 3 cm under the rails' top)
    def wall_top(z):
        return CM.y_foot(z) - 0.030

    for side in (1, -1):                                            # +1 = left (x > 0)
        xw = E[0] + WALL_X * side
        wall = atlas.paint(120, 60, p_wall(GREYT_D, GREYT, 30 + side))
        z0, z1 = E[2] + BACK_Z + 0.01, E[2] + PANEL_Z - 0.10
        qa = [xw, E[1] + CONSOLE_Y - 0.02, z0]; qb = [xw, E[1] + CONSOLE_Y - 0.02, z1]
        qc = [xw, E[1] + wall_top(z1 - E[2]), z1]; qd = [xw, E[1] + wall_top(z0 - E[2]), z0]
        ua, ub, uc, ud = m.uv(wall, 0, 0), m.uv(wall, 1, 0), m.uv(wall, 1, 1), m.uv(wall, 0, 1)
        if side < 0:
            ua, ub, uc, ud = ub, ua, ud, uc
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
        con = atlas.paint(240, 36, rotated(p_console(GREYT, 20 + side, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                               else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                               slot=(0.44, 0.30, 0.56, 0.62) if side > 0 else None)))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
        m.face(top, [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['turq_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
    # the rear bulkhead behind the seat, closing the dorsal fairing's mouth (what shows through the glass behind the pilot):
    # the canopy's rear section (just inside the rear bow), from the floor up over the top
    sec = CM.section(CM.Z_RB[0] + 0.005)
    out_r = [(WALL_X, FLOOR_Y + 0.03)] + [(min(x * 0.985, WALL_X), y - 0.004 if y < 0 else y - 0.012) for x, y in sec]
    ring = [P(x, y, BACK_Z) for x, y in out_r] + [P(-x, y, BACK_Z) for x, y in out_r[::-1]]
    wallp = atlas.paint(120, 90, p_wall(GREYT_D, GREYT, 77))
    cb = np.mean(ring, 0)
    for i in range(len(ring) - 1):
        m.face([cb, ring[i], ring[i + 1]], [m.uv(wallp, 0.5, 0.5), m.uv(wallp, 0.1 + 0.8 * i / len(ring), 0.2),
                                            m.uv(wallp, 0.1 + 0.8 * (i + 1) / len(ring), 0.2)], normal=[0, 0, 1])
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    # the floor: a dark plate over the model's own floor, from behind the seat to the pedals
    fw = WALL_X - 0.005
    m.flat([P(-fw, FLOOR_Y + 0.03, BACK_Z), P(fw, FLOOR_Y + 0.03, BACK_Z),
            P(fw, FLOOR_Y + 0.03, PANEL_Z + 0.30), P(-fw, FLOOR_Y + 0.03, PANEL_Z + 0.30)], sw['floor'],
           normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.985, 0.46), (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.045, 0.50), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- the R-28V-300 throttle and the nozzle lever on the left console (the VTOL handles of the Yak-38)
    tx = 0.246                                                      # (the console is 4 cm wide: the two levers stand one behind the other)
    for tz, th, col in ((-0.03, 0.060, 'black'), (0.105, 0.045, 'yellow')):
        m.box(P(tx, CONSOLE_Y + 0.5 * th, tz), (X, unit([0, 1, 0.25]), unit([0, -0.25, 1])), (0.007, th, 0.007), sw['metal'])
        grip_ax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
        m.box(P(tx, CONSOLE_Y + th + 0.045, tz + 0.02), grip_ax, (0.016, 0.040, 0.030), sw[col])
        m.box(P(tx, CONSOLE_Y + th + 0.090, tz + 0.03), grip_ax, (0.014, 0.008, 0.026), sw['dgrey'])
    m.box(P(tx, CONSOLE_Y + 0.115, -0.03 + 0.05), grip_ax, (0.003, 0.008, 0.008), sw['red'])

    # -- K-36VM ejection seat (the Yak-38's seat, with its automatic ejection): an armoured back shield, a shaped headbox with
    # side wings and the face-blind handle on top, arm rests with the ejection handles, the pan handle at the front centre;
    # at the model's seat group's place (back z -0.50, headrest face z -0.30, head top y +0.05)
    SH = HEAD_Z + 0.2455                                            # (the KM-1 seat's z offsets of mig_25pd.py, moved so the headrest face stands at HEAD_Z)
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    back = atlas.paint(48, 64, p_cushion(CUSHION, straps=True))
    armour = atlas.paint(48, 64, p_armour)
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    ax_b = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
    m.box(P(0, -1.095, -0.10 + SH), (X, Y, Z), (0.18, 0.04, 0.28), sw['frame'])                       # seat base on the floor
    m.box(P(0, -0.99, -0.04 + SH), (X, Y, Z), (0.17, 0.03, 0.29), sw['cushion'], front=(1, 1, cushion, -X, Z))   # the pan
    for s in (1, -1):
        m.box(P(s * 0.205, -0.95, -0.10 + SH), (X, Y, Z), (0.012, 0.13, 0.27), sw['frame'])           # side plates
        m.box(P(s * 0.15, -0.58, -0.395 + SH), ax_b, (0.018, 0.42, 0.022), sw['frame'])               # guide rails up to the headbox
        m.box(P(s * 0.215, -0.915, 0.09 + SH), (X, Y, Z), (0.012, 0.012, 0.16), sw['dgrey'])          # arm rest
        m.box(P(s * 0.215, -0.895, 0.215 + SH), (X, Y, Z), (0.014, 0.014, 0.030), sw['yellow'], front=(0, s, stripes, Z, Y))   # handle
    m.box(P(0, -0.60, -0.375 + SH), ax_b, (0.19, 0.40, 0.018), sw['metal'], front=(2, 1, armour, X, ax_b[1]))   # the armoured shield
    m.box(P(0, -0.59, -0.345 + SH), ax_b, (0.16, 0.37, 0.016), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.075, -0.335 + SH), (X, Y, Z), (0.14, 0.115, 0.09), sw['frame'])                    # headbox (top +0.04, 11 cm under the glass)
    m.box(P(0, -0.060, -0.2455 + SH), (X, Y, Z), (0.11, 0.085, 0.0045), sw['cushion'], front=(2, 1, headbox, X, Y))
    for s in (1, -1):                                                                                # the K-36's head wings
        m.box(P(s * 0.125, -0.075, -0.255 + SH), (X, Y, Z), (0.012, 0.095, 0.060), sw['frame'])
    m.box(P(0, 0.062, -0.335 + SH), (X, Y, Z), (0.10, 0.014, 0.06), sw['dgrey'])                     # the face-blind's housing
    m.box(P(0, 0.062, -0.2745 + SH), (X, Y, Z), (0.045, 0.011, 0.006), sw['yellow'], front=(2, 1, stripes, X, Y))   # its handle
    for s in (1, -1):
        m.box(P(s * 0.035, -0.915, 0.355 + SH), (X, Y, Z), (0.006, 0.05, 0.006), sw['metal'])
    m.box(P(0, -0.855, 0.355 + SH), (X, Y, Z), (0.045, 0.011, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))      # the pan handle

    # -- control stick (RUS): the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.30
    m.box(P(0, -1.07, sz), (X, Y, Z), (0.045, 0.04, 0.045), sw['dgrey'])
    stick = Mesh(atlas)
    col_ax = (X, unit([0, 1, -0.07]), unit([0, 0.07, 1]))
    stick.box(P(0, -0.84, sz - 0.002), col_ax, (0.011, 0.19, 0.011), sw['metal'])
    g_ax = (X, unit([0, 1, 0.21]), unit([0, -0.21, 1]))
    gc = P(0, -0.64, sz - 0.006)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    stick.box(gc - g_ax[2] * 0.034 + g_ax[1] * 0.01, g_ax, (0.012, 0.035, 0.004), sw['metal'])
    foot = P(0, -1.04, sz)
    return m, (stick, foot), gauges, displays, hud


# ---------------------------------------------------------------- the airframe

# the old interior, cut out by element boxes: every face of the two hull pieces lying wholly inside one of these boxes (absolute
# blocks, x0 y0 z0 x1 y1 z1; source elements: the seat group 443..496, the tub boxes, the old panel, coaming, console and sight
# boxes and their mirrors). The old frame (hoop, bulkhead, windscreen ribs) is canopy_yak_38m.py's.
CUT_BOXES = [
    (0.399, 1.348, 2.666, 0.449, 1.747, 3.115), (0.399, 1.398, 2.017, 0.449, 1.947, 2.666), (0.300, 1.647, 2.666, 0.449, 1.847, 3.115),
    (0.300, 1.747, 2.017, 0.449, 2.047, 2.666), (0.300, 1.747, 2.666, 0.349, 1.897, 3.115), (0.300, 1.847, 2.017, 0.349, 2.147, 2.666),
    (0.349, 1.847, 1.617, 0.499, 2.246, 2.416), (-0.449, 1.348, 2.666, -0.399, 1.747, 3.115), (-0.449, 1.398, 2.017, -0.399, 1.947, 2.666),
    (-0.449, 1.647, 2.666, -0.300, 1.847, 3.115), (-0.449, 1.747, 2.017, -0.300, 2.047, 2.666), (-0.349, 1.747, 2.666, -0.300, 1.897, 3.115),
    (-0.349, 1.847, 2.017, -0.300, 2.147, 2.666), (-0.499, 1.847, 1.617, -0.349, 2.246, 2.416), (-0.200, 1.048, 2.117, 0.200, 1.198, 2.965),
    (0.200, 1.048, 1.967, 0.250, 1.298, 3.015), (-0.250, 1.048, 1.967, -0.200, 1.298, 3.015), (-0.200, 1.048, 1.967, 0.200, 2.097, 2.117),
    (0.200, 1.298, 1.967, 0.250, 2.097, 2.316), (-0.250, 1.298, 1.967, -0.200, 2.097, 2.316), (0.100, 1.498, 2.117, 0.200, 1.997, 2.167),
    (-0.200, 1.198, 2.117, 0.200, 1.398, 2.167), (-0.200, 1.498, 2.117, -0.100, 1.997, 2.167), (0.100, 1.398, 2.117, 0.200, 1.547, 2.167),
    (-0.200, 1.398, 2.117, -0.100, 1.547, 2.167), (-0.100, 1.398, 2.117, 0.100, 1.997, 2.167), (0.075, 1.547, 2.092, 0.125, 1.747, 2.192),
    (-0.125, 1.547, 2.092, -0.075, 1.747, 2.192), (-0.125, 1.747, 2.092, -0.075, 1.797, 2.192), (0.075, 1.747, 2.092, 0.125, 1.797, 2.192),
    (0.075, 1.797, 2.092, 0.125, 2.047, 2.192), (-0.125, 1.797, 2.092, -0.075, 2.047, 2.192), (0.025, 1.997, 2.092, 0.075, 2.097, 2.142),
    (-0.075, 1.997, 2.092, -0.025, 2.097, 2.142), (0.075, 1.198, 2.092, 0.250, 1.547, 2.192), (-0.250, 1.198, 2.092, -0.075, 1.547, 2.192),
    (0.125, 1.947, 2.092, 0.175, 2.097, 2.142), (-0.175, 1.947, 2.092, -0.125, 2.097, 2.142), (0.150, 2.097, 1.967, 0.250, 2.147, 2.167),
    (0.150, 2.147, 1.967, 0.250, 2.196, 2.167), (-0.250, 2.097, 1.967, -0.150, 2.147, 2.167), (-0.250, 2.147, 1.967, -0.150, 2.196, 2.167),
    (0.100, 2.097, 1.967, 0.150, 2.396, 2.017), (-0.150, 2.097, 1.967, -0.100, 2.396, 2.017), (-0.100, 2.097, 1.967, 0.100, 2.346, 2.017),
    (-0.075, 2.346, 1.967, -0.025, 2.396, 2.017), (0.025, 2.346, 1.967, 0.075, 2.396, 2.017), (-0.175, 2.296, 2.017, 0.175, 2.396, 2.167),
    (-0.175, 2.196, 2.017, 0.175, 2.296, 2.167), (-0.175, 2.097, 2.017, 0.175, 2.196, 2.167), (-0.200, 1.198, 2.167, 0.200, 1.248, 2.865),
    (0.100, 1.198, 2.865, 0.200, 1.248, 2.965), (-0.200, 1.198, 2.865, -0.100, 1.248, 2.965), (-0.075, 1.198, 2.915, 0.075, 1.298, 2.965),
    (0.025, 1.198, 2.865, 0.175, 1.398, 2.915), (-0.175, 1.198, 2.865, -0.025, 1.398, 2.915), (-0.300, 1.048, 2.316, -0.250, 1.298, 3.015),
    (0.250, 1.048, 2.316, 0.300, 1.298, 3.015), (-0.250, 1.298, 2.865, -0.200, 1.398, 2.965), (0.200, 1.298, 2.865, 0.250, 1.398, 2.965),
    (0.250, 1.298, 2.666, 0.300, 1.398, 2.716), (0.250, 1.298, 2.566, 0.300, 1.448, 2.616), (0.250, 1.298, 2.466, 0.300, 1.398, 2.566),
    (-0.300, 1.298, 2.566, -0.250, 1.448, 2.616), (0.150, 1.048, 2.965, 0.200, 1.198, 3.015), (0.025, 1.048, 2.965, 0.075, 1.198, 3.015),
    (-0.075, 1.048, 2.965, -0.025, 1.198, 3.015), (-0.200, 1.048, 2.965, -0.150, 1.198, 3.015), (0.000, 1.498, 2.965, 0.150, 1.947, 3.215),
    (0.150, 1.498, 2.965, 0.300, 1.947, 3.215), (0.324, 1.532, 2.655, 0.374, 1.630, 2.766), (0.275, 1.589, 2.614, 0.374, 1.658, 2.684),
    (-0.150, 1.498, 2.965, 0.000, 1.947, 3.215), (-0.300, 1.498, 2.965, -0.150, 1.947, 3.215), (0.000, 1.947, 2.965, 0.150, 1.997, 3.215),
    (-0.150, 1.947, 2.965, 0.000, 1.997, 3.215), (-0.100, 1.997, 3.015, 0.100, 2.047, 3.215), (0.050, 2.047, 3.065, 0.100, 2.196, 3.115),
    (0.050, 1.998, 3.071, 0.100, 2.196, 3.212), (-0.100, 2.047, 3.065, -0.050, 2.196, 3.115), (-0.100, 1.998, 3.071, -0.050, 2.196, 3.212),
]
EXPECT_CUT = 495
CUT_TOL = 0.004


def cut_old(ac):
    """removes the old interior (CUT_BOXES); the first run, after canopy_yak_38m.py has cut the frame, must find EXPECT_CUT faces\n    (523 before that cut: 28 faces are shared with the frame boxes), a later one finds none"""
    already = any(b['name'].endswith('__cockpit') and b.get('poly_mesh', {}).get('polys') for b in ac.bones)
    box = np.array(CUT_BOXES, float)
    lo, hi = box[:, :3] - CUT_TOL, box[:, 3:] + CUT_TOL
    gone = {}
    for name in (H1, H0):
        pm = ac.bone(name)['poly_mesh']
        Pp = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        idx = set()
        for k, poly in enumerate(pm['polys']):
            if not poly:
                continue
            V = Pp[[v[0] for v in poly]]
            if np.any(np.all(V.min(0) >= lo, axis=1) & np.all(V.max(0) <= hi, axis=1)):
                idx.add(k)
        gone[name] = idx
    n = sum(len(v) for v in gone.values())
    if n != EXPECT_CUT and not already and n:
        raise SystemExit(f'the interior cut found {n} faces, expected {EXPECT_CUT}: the airframe changed; re-read CUT_BOXES '
                         '(nothing was written)')
    for name, idx in gone.items():
        pm = ac.bone(name)['poly_mesh']
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in idx]
    return n


def install(ac, mesh, stick, gauges, displays, hud, eye, seat=0):
    """as handbuilt.install, but the canopy's CanopyGlass stays (the sight's glass is added to it)"""
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
    if displays:                               # (no radar scope on the Yak-38: no FlightDisplays entry, as the other aircraft without one)
        a['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    else:
        a.pop('FlightDisplays', None)
    a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat, 'Center': r5(hud['centre']),
                          'Normal': r5(hud['normal']), 'Up': r5(hud['up']), 'Width': round(float(hud['width']), 5),
                          'Height': round(float(hud['height']), 5), 'Eye': r5(eye), 'Style': hud['style']}
    if hud.get('glass'):                       # the reflector's glass, on top of the canopy's
        cg = a.get('CanopyGlass')
        if cg:                                 # (drop the sight glass of an earlier run: it lies inside the sight's box)
            t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
            c = t.mean(1)
            keep = ~((np.abs(c[:, 0] - eye[0]) < 0.08) & (np.abs(c[:, 1] - eye[1]) < 0.09) & (c[:, 2] > eye[2] + 0.30) &
                     (c[:, 2] < eye[2] + 0.55))
            cg['Triangles'] = [round(float(v), 4) + 0.0 for v in t[keep].reshape(-1)]
        L.add_glass(a, hud['glass'])
    return pm


def write_all(ac, atlas):
    """as write(), with LF line endings"""
    with open(ac.geo_path, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(ac.geo, fh, separators=(',', ':'))
        fh.write('\n')
    glass_json = ac.asset.pop('CanopyGlass', None)
    text = json.dumps(ac.asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        ac.asset['CanopyGlass'] = glass_json
    with open(ac.asset_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text + '\n')
    tex = os.path.normpath(L.texture_path(ac.vid))
    atlas.write(tex)
    dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
    if os.path.isfile(dead):
        atlas.write(dead, dead=True)


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E = ac.eye(0)
    removed = cut_old(ac)
    atlas = Atlas(VID)
    mesh, stick, gauges, displays, hud = build(atlas, E)
    pm = install(ac, mesh, stick, gauges, displays, hud, E)
    print(f'{VID}: removed {removed} old interior faces; new cockpit {len(pm["polys"])} faces, {len(gauges)} gauges')
    if not dry:
        write_all(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
