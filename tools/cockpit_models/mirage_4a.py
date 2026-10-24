#!/usr/bin/env python3
"""Mirage IVA cockpits (pilot and navigator / bomb aimer), built by hand for this model (as mig_25pd.py, mirage_3e.py,
f_4c.py), from the real panel layout.

Measured off the model (the faces of Dassault Mirage IV.mtb in checks/orig, sections.py, a ray scan; all heights and
distances from the pilot's eye at (0, 2.82852, 6.19348), the navigator's eye 1.675 m behind it; 1 block = 1 m on this model):
  * the fuselage is a pear-shaped shell with thin (5 cm) walls: inner wall x 0.40 .. 0.55 at the sill level (y -0.25 at the
    pilot, -0.18 at the rear crew's bay), widening 0.34 per metre of depth to about 0.58 at the elbow line and the floor at
    y -1.12; the nose deck under the old windscreen falls from y -0.34 (z +0.6) to -0.45 (z +1.3);
  * the old interior (consoles at y -0.75, panel boxes at z +0.4 .. +0.8, the seat groups with their headrest front faces at
    z -0.27 from each eye, the stick at z +0.44) is cut out by canopy_mirage_4a.py's boxes.
Built:
  * pilot (Seat 0): a three-facet main panel 0.55 m ahead, 12 degrees back, its wings turned 25 degrees toward the pilot: the
    bomb-aiming / navigation display (the game's stores page, square CRT) at the top centre, the attitude indicator and
    horizontal situation indicator, Machmeter and altimeter, variometer and clock (centre); airspeed, g-meter, the ARMEMENT
    and TRAIN panels on the left wing; the pair of Atar 9K-50 (two rev counters, two exhaust gas temperatures, fuel, the
    nozzle dial) on the right wing; French placards (VISU BOMBARDEMENT, MOTEUR G / MOTEUR D, ARMEMENT, TRAIN, alarm lamps);
    the black coaming forward to the windscreen's base; a reflector sight on the coaming (HeadUpDisplay Style REFLECTOR,
    seat 0; the Mirage IVA has no head-up display); low consoles with the two Atar throttle levers on the left, radio and
    oxygen boxes behind the seat, side linings, rudder pedals, a Martin-Baker BM4 ejection seat and the control stick (into
    the stick bone);
  * navigator / bomb aimer (Seat 1): a tall panel facing the partition behind the pilot: the DRAA 8A radar scope (round tube
    in its rubber hood, the game's radar page) over airspeed / heading / altitude repeaters, clock, Machmeter and
    variometer, the NAVIGATION and BOMBARDEMENT placards and warning lamps, navigation and radio panels on the left wing, the
    bombing and release panels on the right; low consoles with the bomb-aiming hand controller, a chart shelf, a BM4 seat.
    The ventral bomb-aiming periscope of the real aircraft is not modelled.
Seat: the Martin-Baker BM4 (Mk 4 class seat: a face-blind handle above the headbox and a lower handle at the front of the pan)
replaces the model's own seat groups; no photo reference was reachable, the proportions are the old groups'.
Instruments the game draws (CockpitGauges, FlightDisplays, HeadUpDisplay) are live. The canopy glass is canopy_mirage_4a.py's.

Imports only cockpit_lib and free_atlas (the building blocks below are inlined, as mig_25pd.py's).
Run on the airframe's originals (mirage4/run_mirage4_cockpit.ps1 restores them), after canopy_mirage_4a.py.
Usage: python3 tools/cockpit_models/mirage_4a.py [--dry-run]
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
import canopy_mirage_4a as CM   # noqa: E402
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



def write(ac, atlas):                          # (this script's own: LF line ends, as the airframe's files)
    with open(ac.geo_path, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(ac.geo, fh, separators=(',', ':'))
        fh.write('\n')
    glass_json = ac.asset.pop('CanopyGlass', None)
    text_ = json.dumps(ac.asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text_ = text_[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        ac.asset['CanopyGlass'] = glass_json
    with open(ac.asset_path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text_ + '\n')
    tex = os.path.normpath(L.texture_path(ac.vid))
    atlas.write(tex)
    dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
    if os.path.isfile(dead):
        atlas.write(dead, dead=True)


VID = 'mirage_4a'
SOURCE_BONE = 'wreck_fuselage_1__hull'
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'
EZ = CM.EZ

PANEL = (46, 50, 55)           # French cockpit: dark blue-grey panels (references: Mirage III / IV cockpit photos, War Thunder)
PANEL_D = (32, 35, 40)
BLACK = (22, 23, 25)
DGREY = (44, 46, 49)
MGREY = (88, 92, 96)
FRAME = (58, 62, 64)           # seat structure
CUSHION = (62, 66, 58)
HEADPAD = (88, 92, 86)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (40, 44, 46)

# ---------------------------------------------------------------- the measured cockpit (metres; z relative to the PILOT's eye)
# The pear-shaped fuselage's inner wall (read off the untouched model's section sheet): its x at the sill level and how it
# widens below; the lining stands 1.4 cm inside it.
# inner x of the wall at y = -0.2 / -0.4 / -0.7 / -1.0 for each station z (relative to the pilot's eye)
WALL_T = [(-2.4, (0.56, 0.60, 0.62, 0.60)), (-2.0, (0.52, 0.56, 0.60, 0.60)), (-1.7, (0.51, 0.56, 0.59, 0.59)),
          (-1.4, (0.48, 0.55, 0.59, 0.58)), (-1.0, (0.43, 0.51, 0.57, 0.57)), (-0.6, (0.40, 0.48, 0.59, 0.58)),
          (-0.2, (0.38, 0.47, 0.57, 0.57)), (0.2, (0.36, 0.45, 0.55, 0.55)), (0.5, (0.32, 0.41, 0.53, 0.53)),
          (0.8, (0.28, 0.36, 0.51, 0.51))]
WALL_Y = (-0.2, -0.4, -0.7, -1.0)
FLOOR_Y = -1.12
SILL_LIN_P = -0.28             # the pilot's lining tops out under the sill rails (y -0.25), on the old walls' top (-0.23 .. -0.29)
SILL_LIN_N = -0.19             # the rear crew's under the hood's base (y -0.18)
PART_Z = -1.10                 # the partition between the crews (the shared bow's plane); each side's face 5 mm off it
LEAN = math.radians(12)
WING = math.radians(25)
R_C = np.array([-1.0, 0, 0])                                   # the crew member's right
U_C = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_C = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward the crew member


def wall_x(y, zp):
    """the lining's x at height y (eye-relative) and z (relative to the pilot's eye): 1.4 cm inside the wall's inner face"""
    zs = [p[0] for p in WALL_T]
    col = [float(np.interp(zp, zs, [p[1][k] for p in WALL_T])) for k in range(4)]
    if y > WALL_Y[0]:                                           # above the first level: continue its slope (the wall leans in)
        v = col[0] + (col[0] - col[1]) / 0.2 * (y - WALL_Y[0]) * -1.0
    else:
        v = float(np.interp(-y, [-t for t in WALL_Y], col))
    return v - 0.014

# the two crew stations (z in each is relative to its own eye)
ST_P = dict(name='pilot', seat=0, E=None, dz=0.0, hinge=0.150, wing_x=0.310, panel_z=0.55,
            top_c=-0.170, top_w=-0.300, bot_c=-0.800, bot_w=-0.720, console_y=-0.66, console_in=0.26,
            back=-1.095, front=0.67, sill_lin=SILL_LIN_P)
ST_N = dict(name='nav', seat=1, E=None, dz=CM.NAV_DZ, hinge=0.170, wing_x=0.405, panel_z=0.47,
            top_c=-0.100, top_w=-0.210, bot_c=-0.850, bot_w=-0.750, console_y=-0.66, console_in=0.26,
            back=-0.75, front=0.575, sill_lin=SILL_LIN_N)


def top_at(st, zp):
    """the lining's top at z: the sill under the canopy, falling with the quarter panes' lower edge ahead of its front bow"""
    if st['name'] != 'pilot':
        return st['sill_lin']
    if zp <= CM.Z_ARCH[0]:
        return min(st['sill_lin'], CM.foot_y(max(zp, CM.Z_RB[0] + 1e-6)) - 0.03)
    xw = min(wall_x(st['sill_lin'] - 0.1, zp), CM.x_out(min(max(zp, CM.Z_WS[0]), CM.Z_WS[1])))
    return min(st['sill_lin'], CM.ys(xw, zp) - 0.02)


def wing_len(st):
    return (st['wing_x'] - st['hinge']) / math.cos(WING)


def facets(st):
    E = st['E']
    o = E + np.array([0, (st['top_c'] + st['bot_c']) / 2, st['panel_z']])
    centre = Facet(o, R_C, U_C, N_C)
    wings = {}
    for side in (1, -1):                                       # +1: the crew member's right wing
        R = R_C * side * math.cos(WING) + N_C * math.sin(WING)
        N = N_C * math.cos(WING) - R_C * side * math.sin(WING)
        hinge = o + R_C * side * st['hinge']
        wings[side] = Facet(hinge, R, U_C, N)                  # a: metres outward from the hinge
    return centre, wings[1], wings[-1]


def top_of(st, side, a):
    return st['top_c'] if side == 0 else st['top_c'] + (st['top_w'] - st['top_c']) * a / wing_len(st)


# Instrument layouts. facet: 0 centre (a: metres to the crew member's right of the centre line), +1 right wing, -1 left wing
# (a: outward from the hinge). y: from the eye.
LAYOUT_P = [
    # centre facet
    ('bomb',    0,  0.000, -0.275, ('display', 'STORES', 0.100), 'bomb-aiming / navigation display (stores page)'),
    ('p_bomb',  0,  0.000, -0.352, ('plate', 0.150, 0.014, 'VISU BOMBARDEMENT'), 'display placard'),
    ('adi',     0, -0.075, -0.435, ('game', 'ATTITUDE', 0.080), 'attitude indicator'),
    ('hsi',     0,  0.075, -0.435, ('game', 'HEADING', 0.080), 'horizontal situation indicator'),
    ('mach',    0, -0.075, -0.530, ('game', 'MACH', 0.070), 'Machmeter'),
    ('alt',     0,  0.075, -0.530, ('game', 'ALTIMETER', 0.070), 'altimeter'),
    ('vvi',     0, -0.075, -0.615, ('game', 'VVI', 0.060), 'variometer'),
    ('clock',   0,  0.075, -0.615, ('game', 'CLOCK', 0.060), 'clock'),
    ('warning', 0,  0.000, -0.715, ('annunciator', 0.260, 0.056), 'warning lights'),
    # left wing
    ('ias',    -1,  0.094, -0.420, ('game', 'AIRSPEED', 0.075), 'airspeed indicator'),
    ('g',      -1,  0.094, -0.510, ('game', 'G_METER', 0.055), 'accelerometer'),
    ('weapons', -1, 0.094, -0.600, ('paint_rect', 0.120, 0.055), 'ARMEMENT panel'),
    ('gear',   -1,  0.094, -0.680, ('paint_rect', 0.100, 0.045), 'TRAIN panel'),
    # right wing: the Atar 9K-50 pair
    ('rpm_l',   1,  0.050, -0.420, ('game', 'ENGINE_RPM', 0.058), 'left Atar rev counter'),
    ('rpm_r',   1,  0.136, -0.420, ('game', 'ENGINE_RPM', 0.058), 'right Atar rev counter'),
    ('egt_l',   1,  0.050, -0.500, ('game', 'EGT', 0.052), 'left exhaust gas temperature'),
    ('egt_r',   1,  0.136, -0.500, ('game', 'EGT', 0.052), 'right exhaust gas temperature'),
    ('fuel',    1,  0.050, -0.575, ('game', 'FUEL_QTY', 0.052), 'fuel quantity'),
    ('noz',     1,  0.136, -0.575, ('paint_disc', 'TUY', 0.048), 'nozzle position'),
    ('p_mot',   1,  0.094, -0.645, ('plate', 0.150, 0.014, 'MOTEUR G  MOTEUR D'), 'engine placard'),
]
LAYOUT_N = [
    ('scope',   0,  0.000, -0.235, ('display', 'RADAR', 0.110, 'round'), 'DRAA 8A radar scope (round tube, hooded)'),
    ('p_radar', 0,  0.000, -0.372, ('plate', 0.170, 0.014, 'RADAR DRAA 8A'), 'scope placard'),
    ('ias',     0, -0.105, -0.455, ('game', 'AIRSPEED', 0.070), 'airspeed repeater'),
    ('hdg',     0,  0.000, -0.455, ('game', 'HEADING', 0.070), 'heading repeater'),
    ('alt',     0,  0.105, -0.455, ('game', 'ALTIMETER', 0.070), 'altimeter'),
    ('clock',   0, -0.105, -0.545, ('game', 'CLOCK', 0.055), 'clock'),
    ('mach',    0,  0.000, -0.545, ('game', 'MACH', 0.060), 'Machmeter'),
    ('vvi',     0,  0.105, -0.545, ('game', 'VVI', 0.055), 'variometer'),
    ('p_nav',   0,  0.000, -0.640, ('plate', 0.300, 0.016, 'NAVIGATION    BOMBARDEMENT'), 'panel placard'),
    ('warning', 0,  0.000, -0.725, ('annunciator', 0.280, 0.056), 'warning lights'),
    ('nav1',   -1,  0.120, -0.300, ('paint_rect', 0.140, 0.150), 'NAVIGATION panel'),
    ('nav2',   -1,  0.120, -0.500, ('paint_rect', 0.140, 0.120), 'RADIO / TACAN panel'),
    ('bomb1',   1,  0.120, -0.300, ('paint_rect', 0.140, 0.150), 'BOMBARDEMENT panel'),
    ('bomb2',   1,  0.120, -0.500, ('paint_rect', 0.140, 0.120), 'CHRONO / LARGAGE panel'),
    ('fuel',    1,  0.120, -0.620, ('game', 'FUEL_QTY', 0.050), 'fuel quantity'),
]

LAMPS = (3, [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('ALARME|GENERALE', 'MASTER_CAUTION', 'amber'), ('TRAIN|SORTI', 'GEAR_DOWN', 'green'),
    ('TRAIN|INSTABLE', 'GEAR_TRANSIT', 'red'), ('CARBURANT|BAS', 'LOW_FUEL', 'amber'),
    ('FEU', 'FIRE', 'red'), ('PANNE|MOTEUR', 'ENGINE_OUT', 'red')]])


# ---------------------------------------------------------------- painted faces

def panel_paint(st, layout, side, W_, H_, a0, a1, yb, yt, ylines):
    def fn(d, im):
        SS = Atlas.SS
        noisy(d, im, PANEL, 5, seed=3 + side + 7 * st['seat'])

        def to_px(a, y):
            s = (a - a0) / (a1 - a0)
            if side == -1:
                s = 1 - s
            return s * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        for y in ylines:
            if yb < y < yt:
                x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
                d.line([x0, yy, x1, yy], fill=PANEL_D + (255,), width=SS * 2)
        for a, y in ((a0 + 0.012, yb + 0.012), (a1 - 0.012, yb + 0.012), (a0 + 0.012, yt - 0.02), (a1 - 0.012, yt - 0.02)):
            x, yy = to_px(a, y); r = SS * 2.2
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        for name, f, a, y, spec, _ in layout:
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
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 24, 26, 255))
    return fn


def p_switchbank(title, rows, lamps=()):
    """a placarded switch panel: the title, rows of toggles with tiny labels, a few lamps"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
        text(d, (S / 2, Hh * 0.09), title, min(Hh * .11, S * .12))
        rng = np.random.default_rng(len(title) * 7 + len(rows))
        n = len(rows)
        for k, lab in enumerate(rows):
            y = Hh * (0.24 + 0.58 * (k + 0.5) / n)
            for j in range(3):
                x = S * (0.18 + 0.32 * j)
                if rng.random() < 0.6:
                    d.rectangle([x - 4, y - 9, x + 4, y + 4], fill=(190, 190, 190, 255))
                    d.ellipse([x - 7, y - 15, x + 7, y - 3], fill=(205, 205, 205, 255))
                else:
                    d.ellipse([x - 9, y - 9, x + 9, y + 9], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=2)
            text(d, (S * 0.5, y + Hh * 0.04), lab, Hh * .055, fill=(180, 180, 170))
        for k, (colour, lit) in enumerate(lamps):
            x = S * (0.15 + 0.7 * (k + 0.5) / max(1, len(lamps)))
            lamp(d, [x - 14, Hh * .90, x + 14, Hh * .97], colour, None, lit=lit)
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    labs = ['BOMB', 'RESV', 'PHOT', 'SEC']
    for k, lab in enumerate(labs):
        x = S * (0.14 + k * 0.24)
        lamp(d, [x - 18, Hh * .14, x + 18, Hh * .38], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .48, x + 4, Hh * .70], fill=(190, 190, 190, 255))
        text(d, (x, Hh * .86), lab, Hh * .15)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    for k in range(3):
        x = S * (0.2 + 0.3 * k)
        lamp(d, [x - 16, Hh * .12, x + 16, Hh * .52], (60, 150, 70), None, lit=True)
    text(d, (S / 2, Hh * .80), 'TRAIN', Hh * .26)


def p_dial(label):
    def fn(d, im):
        S = im.width
        d.rectangle([0, 0, S, im.height], fill=(20, 22, 24, 255))
        d.ellipse([S * .06, S * .06, S * .94, S * .94], fill=(236, 236, 228, 255), outline=(60, 60, 60, 255), width=3)
        for k in range(11):
            a = math.radians(225 - 270 * k / 10)
            r0, r1 = S * .36, S * .43
            d.line([S / 2 + r0 * math.cos(a), S / 2 - r0 * math.sin(a), S / 2 + r1 * math.cos(a), S / 2 - r1 * math.sin(a)],
                   fill=(20, 20, 20, 255), width=2)
        a = math.radians(225 - 270 * 0.45)
        d.line([S / 2, S / 2, S / 2 + S * .33 * math.cos(a), S / 2 - S * .33 * math.sin(a)], fill=(200, 40, 30, 255), width=3)
        text(d, (S / 2, S * .68), label, S * .13, fill=(20, 20, 20))
    return fn


def p_sight(d, im):
    """the sight's aft face: the reticle unit's window and its knobs"""
    S, Hh = im.width, im.height
    noisy(d, im, DGREY, 4, seed=9)
    d.rectangle([S * .22, Hh * .12, S * .78, Hh * .62], fill=(16, 22, 26, 255), outline=(90, 90, 90, 255), width=4)
    d.ellipse([S * .38, Hh * .2, S * .62, Hh * .54], fill=(34, 48, 58, 255))
    for k, lab in enumerate(['LUM', 'TIR', 'VISEUR']):
        x = S * (0.2 + k * 0.3)
        d.ellipse([x - 14, Hh * .74 - 14, x + 14, Hh * .74 + 14], fill=(22, 22, 22, 255), outline=(140, 140, 140, 255),
                  width=3)
        text(d, (x, Hh * .93), lab, Hh * .085)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    text(d, (S * .5, Hh * .5), 'MARTIN-BAKER', Hh * .13, fill=(40, 40, 40), bold=True)


def p_back(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, CUSHION, 5, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(48, 52, 44, 255), width=3)
    text(d, (S * .5, Hh * .3), 'BM4', Hh * .16, fill=(30, 30, 30), bold=True)
    for x in (S * .12, S * .88):
        for y in (Hh * .1, Hh * .9):
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(120, 124, 120, 255))


def p_rack(label):
    def fn(d, im):
        S, Hh = im.width, im.height
        noisy(d, im, DGREY, 4, seed=len(label))
        d.rectangle([S * .08, Hh * .12, S * .92, Hh * .88], outline=(70, 72, 74, 255), width=4)
        for k in range(3):
            x = S * (0.25 + 0.25 * k)
            d.ellipse([x - 9, Hh * .62 - 9, x + 9, Hh * .62 + 9], fill=(26, 26, 26, 255), outline=(150, 150, 150, 255), width=2)
        text(d, (S * .5, Hh * .3), label, Hh * .2)
    return fn


# ---------------------------------------------------------------- build

def ring_pts(zp, sill):
    """right half of the closed cross-section at z (relative to the pilot's eye) just inside the lining and the glass, from
    the floor up to the centre line at the crown"""
    below = [(wall_x(y, zp), y) for y in (FLOOR_Y + 0.01, -0.70, -0.40)] + [(wall_x(sill, zp), sill)]
    sec = CM.half_section(zp)
    y0, yt = sec[0][1], sec[-1][1]
    above = [(x * 0.93, y - 0.03 * (y - y0) / max(yt - y0, 1e-6)) for x, y in sec[1:]]
    above[-1] = (0.0, above[-1][1])
    return below + above


def fan(m, rect, pts_right, zp_abs, E, normal, uvs=None):
    """fills a closed section (right half given, mirrored) with a triangle fan at the vehicle z; centroid fan"""
    ring = [np.array([E[0] + x, E[1] + y, zp_abs]) for x, y in pts_right] + \
        [np.array([E[0] - x, E[1] + y, zp_abs]) for x, y in pts_right[::-1] if x > 1e-6]
    cb = np.mean(ring, 0)
    for i in range(len(ring)):
        a_, b_ = ring[i], ring[(i + 1) % len(ring)]
        m.flat([cb, a_, b_], rect, normal=normal)


def build_station(m, atlas, sw, st, layout, ylines, gauges, displays):
    E = st['E']
    dz = st['dz']
    P = lambda x, y, z: E + np.array([x, y, z], float)         # noqa: E731  eye-relative point
    zg = lambda z: z + dz                                       # station z -> relative to the pilot's eye   # noqa: E731
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    seat = st['seat']
    centre, right, left = facets(st)
    fac = {0: centre, 1: right, -1: left}
    hinge, WL = st['hinge'], wing_len(st)
    ext = {0: (-hinge, hinge), 1: (0.0, WL), -1: (0.0, WL)}
    bot = {0: st['bot_c'], 1: st['bot_w'], -1: st['bot_w']}

    def at(side, a, y):
        return fac[side].at(a, E[1] + y)

    # -- the main panel: three facets, each a 2 cm slab with its painted face
    ppm = 560
    for side in (0, -1, 1):
        a0, a1 = ext[side]
        yt = max(top_of(st, side, a0), top_of(st, side, a1)); yb = bot[side]
        W_ = int(round((a1 - a0) * ppm)); H_ = int(round((yt - yb) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W_, H_, panel_paint(st, layout, side, W_, H_, a0, a1, yb, yt, ylines))
        n = 6
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            t0, t1 = (top_of(st, side, aa0) - yb) / (yt - yb), (top_of(st, side, aa1) - yb) / (yt - yb)
            pts = [at(side, aa0, yb), at(side, aa1, yb), at(side, aa1, top_of(st, side, aa1)), at(side, aa0, top_of(st, side, aa0))]
            su = (lambda s: 1 - s) if side == -1 else (lambda s: s)
            m.face(pts, [m.uv(rect, su(s0), 0), m.uv(rect, su(s1), 0), m.uv(rect, su(s1), t1), m.uv(rect, su(s0), t0)],
                   normal=fac[side].N)
        back = -fac[side].N * 0.02
        corners = [at(side, a0, yb), at(side, a1, yb), at(side, a1, top_of(st, side, a1)), at(side, a0, top_of(st, side, a0))]
        m.flat([c + back for c in corners], sw['panel_d'], normal=-fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['panel_d'], toward=mid + (mid - mid0))

    # -- instruments
    cols, lamps = LAMPS
    for name, side, a, y, spec, _ in layout:
        f = fac[side]
        c = at(side, a, y)
        kind = spec[0]
        if kind == 'game':
            gauges.append({'Seat': seat, 'Kind': spec[1], 'Center': r5(c + f.N * GAUGE_LIFT), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': seat, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + f.N * 0.004), 'Normal': r5(f.N),
                           'Up': r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': seat, 'Center': r5(c + f.N * DISPLAY_LIFT), 'Normal': r5(f.N), 'Up': r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
            if len(spec) > 3 and spec[3] == 'round':
                # the DRAA scope is a round tube: the game's square raster stands in a round bezel and a round rubber
                # hood (deep on top), sized round the game's housing (BEZEL 0.12 of the width each side) corners
                _, n_, u_, r_ = frame(c, f.N, f.U)
                r_in = (w * (1 + 2 * 0.12) / 2) * math.sqrt(2) + 0.003
                r_out = r_in + 0.016
                m.disc(c + n_ * 0.0028, n_, u_, r_in, sw['black'], seg=28)
                m.ring(c, n_, u_, r_in, r_out, 0.009, sw['black'], seg=28)
                seg = 28
                for k in range(seg):
                    a0_, a1_ = -0.35 + math.pi * 1.7 * k / seg, -0.35 + math.pi * 1.7 * (k + 1) / seg
                    d0, d1 = r_ * math.cos(a0_) + u_ * math.sin(a0_), r_ * math.cos(a1_) + u_ * math.sin(a1_)
                    dep0, dep1 = 0.012 + 0.058 * max(0.0, math.sin(a0_)), 0.012 + 0.058 * max(0.0, math.sin(a1_))
                    q = [c + d0 * r_out + n_ * 0.009, c + d1 * r_out + n_ * 0.009, c + d1 * r_out + n_ * (0.009 + dep1),
                         c + d0 * r_out + n_ * (0.009 + dep0)]
                    m.flat(q, sw['black'], toward=c + (d0 + d1) * 5 + n_ * 0.03)          # outer skin of the hood
                    q2 = [c + d0 * r_in + n_ * 0.009, c + d1 * r_in + n_ * 0.009, c + d1 * r_in + n_ * (0.009 + dep1),
                          c + d0 * r_in + n_ * (0.009 + dep0)]
                    m.flat(q2, sw['black'], toward=c + n_ * 0.03)                          # its inner skin, lit dark
                    m.flat([q[3], q[2], q2[2], q2[3]], sw['black'], toward=c + n_ * 0.2)   # the lip, toward the crew member
            else:
                m.frame_rect(c, f.N, f.U, w * (1 + 2 * 0.12), w * (1 + 2 * 0.12), 0.006, 0.010, sw['bezel'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            painter = PAINTERS[name]
            if h >= w:
                pw, ph = 112, max(16, int(round(112 * h / w)))
            else:
                pw, ph = 160, max(16, int(round(160 * h / w)))
            rect = atlas.paint(pw, ph, painter)
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.03, (f.R, f.U, f.N), (0.005, 0.005, 0.028), sw['metal'])
                m.box(c + f.N * 0.058, (f.R, f.U, f.N), (0.011, 0.011, 0.009), sw['red'])
        elif kind == 'paint_disc':
            dia = spec[2]
            rect = atlas.paint(64, 64, p_dial(spec[1]))
            _, n_, u_, r_ = frame(c, f.N, f.U)
            m.disc(c + n_ * 0.004, n_, u_, dia / 2, rect, seg=24)
            m.ring(c, n_, u_, dia / 2, dia / 2 * (1 + RIM) + 0.002, 0.006, sw['bezel'], seg=24)
        elif kind == 'plate':                                      # a French placard: black plate, white letters
            w, h, label = spec[1], spec[2], spec[3]
            pw = max(24, int(w * 1000)); ph = max(12, int(h * 1000))
            rect = atlas.paint(pw, ph, p_label_plate(label))
            m.rect_tex(c + f.N * 0.003, f.N, f.U, w, h, rect)

    # -- side linings, sill caps, consoles, the floor, the bulkheads
    z_stat = [zg(st['back'])] + [z for z in (-2.0, -1.6, -0.6, -0.2, 0.2, 0.4, 0.55) if zg(st['back']) + 0.15 < z < zg(st['front']) - 0.15] \
        + [zg(st['front'])]
    z_stat = sorted(set(round(z, 4) for z in z_stat))
    top = st['sill_lin']
    ylev = [top, -0.40, -0.70, FLOOR_Y + 0.01]
    for side in (1, -1):                                            # +1 = left (x > 0)
        wall = atlas.paint(120, 60, p_wall(PANEL_D, PANEL, 30 + side + 5 * seat))
        ua, ub, uc, ud = m.uv(wall, 0, 0), m.uv(wall, 1, 0), m.uv(wall, 1, 1), m.uv(wall, 0, 1)
        if side < 0:
            ua, ub, uc, ud = ub, ua, ud, uc
        for z0, z1 in zip(z_stat[:-1], z_stat[1:]):
            for y0, y1 in zip(ylev[1:], ylev[:-1]):
                qa = np.array([E[0] + side * wall_x(y0, z0), E[1] + y0, E[2] + z0 - dz])
                qb = np.array([E[0] + side * wall_x(y0, z1), E[1] + y0, E[2] + z1 - dz])
                y1a = top_at(st, z0) if y1 == top else y1
                y1b = top_at(st, z1) if y1 == top else y1
                qc = np.array([E[0] + side * wall_x(y1b, z1), E[1] + y1b, E[2] + z1 - dz])
                qd = np.array([E[0] + side * wall_x(y1a, z0), E[1] + y1a, E[2] + z0 - dz])
                m.face([qa, qb, qc], [ua, ub, uc], normal=[-side, 0, 0])
                m.face([qa, qc, qd], [ua, uc, ud], normal=[-side, 0, 0])
        if st['name'] == 'pilot':                                   # the sill cap between the lining and the rails
            for z0, z1 in zip(z_stat[:-1], z_stat[1:]):
                if z1 > CM.Z_ARCH[0] + 1e-6:
                    continue
                xr0 = CM.foot_x(min(max(z0, CM.Z_RB[0] + 1e-6), CM.Z_ARCH[0])) - 0.035
                xr1 = CM.foot_x(min(max(z1, CM.Z_RB[0] + 1e-6), CM.Z_ARCH[0])) - 0.035
                ya, yb2 = top_at(st, z0) + 0.004, top_at(st, z1) + 0.004
                m.flat([[E[0] + side * wall_x(ya, z0), E[1] + ya, E[2] + z0 - dz], [E[0] + side * wall_x(yb2, z1), E[1] + yb2, E[2] + z1 - dz],
                        [E[0] + side * xr1, E[1] + yb2, E[2] + z1 - dz], [E[0] + side * xr0, E[1] + ya, E[2] + z0 - dz]],
                       sw['panel_d'], normal=[0, 1, 0])
        # the console: top at console_y from behind the seat to the panel, its side faces down to the floor
        xi = E[0] + st['console_in'] * side
        cy, fy = E[1] + st['console_y'], E[1] + FLOOR_Y + 0.02
        zb, zf = zg(st['back']) + 0.05, zg(st['front']) - 0.06
        xo = lambda z: E[0] + side * (wall_x(st['console_y'], z) - 0.004)           # noqa: E731
        con = atlas.paint(240, 36, rotated(p_console(PANEL, 20 + side + 3 * seat, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if side > 0
                                                     else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08],
                                                     slot=(0.44, 0.30, 0.56, 0.62) if (side > 0 and st['name'] == 'pilot') else None)))
        zbv, zfv = E[2] + zb - dz, E[2] + zf - dz
        top_q = [[xi, cy, zbv], [xo(zb), cy, zbv], [xo(zf), cy, zfv], [xi, cy, zfv]]
        m.face(top_q, [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, fy, zbv], [xi, fy, zfv], [xi, cy, zfv], [xi, cy, zbv]], sw['panel_d'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zbv], [xo(zb), fy, zbv], [xo(zb), cy, zbv], [xi, cy, zbv]], sw['panel_d'], normal=[0, 0, -1])
        m.flat([[xi, fy, zfv], [xo(zf), fy, zfv], [xo(zf), cy, zfv], [xi, cy, zfv]], sw['panel_d'], normal=[0, 0, 1])
        if st['name'] == 'pilot':                                   # equipment boxes behind the seat (radio, oxygen, test sockets)
            for k, (zc, lab) in enumerate(((-0.84, 'RADIO'), (-0.64, 'OXYG'))):
                rack = atlas.paint(64, 48, p_rack(lab))
                xc = (st['console_in'] + wall_x(st['console_y'], zc) - 0.004) / 2
                hw = (wall_x(st['console_y'], zc) - 0.004 - st['console_in']) / 2 - 0.01
                m.box(P(side * xc, st['console_y'] + 0.06, zc - dz), (X, Y, Z), (hw, 0.06, 0.075), sw['dgrey'],
                      front=(1, 1, rack, -X, Z))
    # the floor: a dark plate over the model's own floor, from the rear bulkhead to the panel
    fl = []
    for z in (zg(st['back']), zg(st['front'])):
        w = wall_x(FLOOR_Y + 0.01, z)
        fl.append((z, w))
    m.flat([[E[0] - fl[0][1], E[1] + FLOOR_Y + 0.01, E[2] + fl[0][0] - dz], [E[0] + fl[0][1], E[1] + FLOOR_Y + 0.01, E[2] + fl[0][0] - dz],
            [E[0] + fl[1][1], E[1] + FLOOR_Y + 0.01, E[2] + fl[1][0] - dz], [E[0] - fl[1][1], E[1] + FLOOR_Y + 0.01, E[2] + fl[1][0] - dz]],
           sw['floor'], normal=[0, 1, 0])
    # the bulkheads: a closed section at the rear of the compartment and at its front
    wallp = sw['panel_d']
    sill_b = SILL_LIN_P if st['name'] == 'pilot' else SILL_LIN_P
    if st['name'] == 'pilot':
        zr_ = zg(st['back'])                                        # (the partition's pilot face, facing forward)
        fan(m, wallp, ring_pts(zr_, SILL_LIN_P), E[2] + zr_ - dz, E, [0, 0, 1])
    else:
        zr_ = zg(st['back'])                                        # the rear crew's rear bulkhead inside the old hood
        sec = CM.half_section(zr_)
        y0, yt = sec[0][1], sec[-1][1]
        right = [(wall_x(y, zr_), y) for y in (FLOOR_Y + 0.01, -0.70, -0.40, SILL_LIN_N)] + \
                [(x * 0.90, y - 0.05 * (y - y0) / (yt - y0)) for x, y in sec[1:]]
        right[-1] = (0.0, right[-1][1])
        fan(m, wallp, right, E[2] + zr_ - dz, E, [0, 0, 1])
        zf_ = zg(st['front'])                                       # the partition's face toward the rear crew (facing back)
        fan(m, wallp, ring_pts(zf_, SILL_LIN_P), E[2] + zf_ - dz, E, [0, 0, -1])
    return P, X, Y, Z, fac, centre


def seat_mesh(m, atlas, sw, P, X, Y, Z):
    """Martin-Baker BM4 ejection seat: bucket and pan, a parachute back, headbox, the face-blind handle above, the lower
    handle between the knees; at the model's old seat group (headrest front face z -0.265, headbox top y +0.10)"""
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    back = atlas.paint(48, 64, p_back)
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    ax_b = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
    m.box(P(0, -1.075, -0.10), (X, Y, Z), (0.18, 0.025, 0.28), sw['frame'])                       # the seat base
    m.box(P(0, -0.925, -0.12), (X, Y, Z), (0.17, 0.030, 0.27), sw['cushion'], front=(1, 1, cushion, -X, Z))   # the pan
    for s in (1, -1):
        m.box(P(s * 0.195, -0.88, -0.14), (X, Y, Z), (0.012, 0.12, 0.25), sw['frame'])           # bucket sides
        m.box(P(s * 0.15, -0.46, -0.415), ax_b, (0.018, 0.55, 0.020), sw['frame'])               # guide rails up to the headbox
        m.box(P(s * 0.215, -0.82, 0.0), (X, Y, Z), (0.012, 0.012, 0.15), sw['dgrey'])            # arm rest
    m.box(P(0, -0.58, -0.375), ax_b, (0.19, 0.30, 0.020), sw['cushion'], front=(2, 1, back, X, ax_b[1]))   # the parachute back
    m.box(P(0, -0.095, -0.335), (X, Y, Z), (0.15, 0.20, 0.070), sw['frame'])                    # headbox (top y +0.105)
    m.box(P(0, -0.07, -0.2655), (X, Y, Z), (0.11, 0.10, 0.0045), sw['cushion'], front=(2, 1, headbox, X, Y))
    # the face-blind: a housing on the headbox top with the yellow-and-black striped handle at its front
    m.box(P(0, 0.082, -0.335), (X, Y, Z), (0.080, 0.022, 0.050), sw['frame'])
    for s in (1, -1):
        m.box(P(s * 0.062, 0.085, -0.262), (X, Y, Z), (0.006, 0.012, 0.030), sw['metal'])
    m.box(P(0, 0.093, -0.235), (X, Y, Z), (0.076, 0.009, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))
    # the lower handle: a striped D ring on two posts at the front centre of the pan
    for s in (1, -1):
        m.box(P(s * 0.035, -0.855, 0.15), (X, Y, Z), (0.006, 0.05, 0.006), sw['metal'])
    m.box(P(0, -0.795, 0.15), (X, Y, Z), (0.045, 0.011, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))


def build(atlas, E0, E1):
    m = Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED, metal=METAL,
                                                floor=FLOOR, bezel=(30, 31, 32)).items()}
    gauges, displays = [], []
    ST_P['E'], ST_N['E'] = E0, E1
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])

    # ---------------- the pilot
    P, X, Y, Z, fac, centre = build_station(m, atlas, sw, ST_P, LAYOUT_P, (-0.40, -0.50, -0.59, -0.69), gauges, displays)
    E = E0
    st = ST_P
    HINGE, WING_LEN = st['hinge'], wing_len(st)
    # the black coaming: lofted from the panel lip forward to the windscreen's base bar, closed against the nose skin
    lip = []
    for side, aa in ((-1, np.linspace(WING_LEN, 0, 4)), (0, np.linspace(HINGE, -HINGE, 7)), (1, np.linspace(0, WING_LEN, 4))):
        for a in aa:
            p = fac[side].at(a, E[1] + top_of(st, side, a))
            if lip and np.linalg.norm(lip[-1][0] - p) < 1e-6:
                continue
            lip.append((p, fac[side]))
    lip.sort(key=lambda q: -q[0][0])
    lip_edge = [p + f.N * 0.045 + np.array([0, 0.025, 0]) for p, f in lip]
    panel_top = [p for p, f in lip]
    FRONT_Z, FRONT_Y_C, FRONT_Y_W = 1.30, -0.455, -0.500
    rows = []
    for t in (0.0, 0.25, 0.5, 0.75, 1.0):
        row = []
        zrow = lip_edge[0][2] + (E[2] + FRONT_Z - lip_edge[0][2]) * t - E[2]
        xmax = max(CM.x_out(min(max(zrow, CM.Z_WS[0]), CM.Z_WS[1])) - 0.035, 0.15) if t > 0.2 else st['wing_x'] + 0.01
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + s * xmax
            yf = E[1] + FRONT_Y_C + (FRONT_Y_W - FRONT_Y_C) * s * s
            z = le[2] + (E[2] + FRONT_Z - le[2]) * t
            x = le[0] + (xf - le[0]) * t if t > 0 else le[0]
            y = le[1] + (yf - le[1]) * t
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
    for j in (0, len(lip_edge) - 1):                                # the coaming's side ends down to the sill
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            yb_ = E[1] + SILL_LIN_P - 0.20
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], sw['black'], toward=[E[0], yb_, (p[2] + q[2]) / 2])
    # the front bulkhead: closes the tub behind the panel and under the coaming up to the lip
    wallp = sw['panel_d']
    zf = st['front']
    right = [(wall_x(y, zf), y) for y in (FLOOR_Y + 0.01, -0.70, -0.40)] + [(wall_x(-0.30, zf), -0.30), (st['wing_x'] - 0.02, -0.30), (st['hinge'] - 0.01, -0.17), (0.0, -0.17)]
    fan(m, wallp, right, E[2] + zf, E, [0, 0, -1])

    # -- reflector sight on the coaming (HeadUpDisplay Style REFLECTOR, seat 0; the IVA has no head-up display)
    sight = atlas.paint(96, 64, p_sight)
    g_h, g_hw = 0.080, 0.050
    lean = math.radians(35)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))
    base_y = E[1] + st['top_c'] + 0.045                              # the coaming's top at the lip
    g_c = np.array([E[0], E[1] - 0.045, E[2] + 0.50])
    g_bot, g_top = g_c - g_up * g_h / 2, g_c + g_up * g_h / 2
    body_top = base_y + 0.050
    body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + 0.535])
    m.box(body_c, (X, Y, Z), (0.050, (body_top - base_y) / 2, 0.075), sw['dgrey'], front=(2, -1, sight, -X, Y))
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

    # rudder pedals under the panel, the two Atar throttle levers on the left console
    for s in (1, -1):
        m.box(P(s * 0.13, -0.955, 0.45), (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.13, -1.030, 0.49), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])
    cy = st['console_y']
    # The two Atar levers are their own bones (throttle_lever_1 / _2), hinged under the console top, at idle.
    THR.clear()
    THR.extend(throttle_lib.jet_levers(m, lambda: Mesh(atlas), sw, 'ball', x=E[0] + 0.3425, top_y=E[1] + cy,
                                       z_idle=E[2] - 0.08 - 0.06, n=2, pitch=0.065, hx=0.065, friction=False))
    seat_mesh(m, atlas, sw, P, X, Y, Z)

    # control stick: the boot stays with the cockpit, the rest goes into the stick bone
    sz = 0.27
    m.box(P(0, -1.055, sz), (X, Y, Z), (0.045, 0.04, 0.045), sw['dgrey'])
    stick = Mesh(atlas)
    col_ax = (X, unit([0, 1, -0.07]), unit([0, 0.07, 1]))
    stick.box(P(0, -0.80, sz - 0.002), col_ax, (0.011, 0.21, 0.011), sw['metal'])
    g_ax = (X, unit([0, 1, 0.21]), unit([0, -0.21, 1]))
    gc = P(0, -0.57, sz - 0.006)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    foot = P(0, -1.013, sz)

    # ---------------- the navigator / bomb aimer
    st = ST_N
    En = E1
    Pn, Xn, Yn, Zn, facn, centre_n = build_station(m, atlas, sw, st, LAYOUT_N, (-0.40, -0.50, -0.585, -0.685), gauges, displays)
    seat_mesh(m, atlas, sw, Pn, Xn, Yn, Zn)
    cy = st['console_y']
    # the bomb-aiming hand controller on the right console: a stalk, a pistol grip with a thumb button, a slew switch
    gx = -0.33
    m.box(Pn(gx, cy + 0.04, -0.03), (Xn, Yn, Zn), (0.007, 0.04, 0.007), sw['metal'])
    gax = (Xn, unit([0, 1, 0.35]), unit([0, -0.35, 1]))
    m.box(Pn(gx, cy + 0.10, -0.01), gax, (0.020, 0.040, 0.030), sw['black'])
    m.box(Pn(gx, cy + 0.147, 0.005), gax, (0.003, 0.008, 0.008), sw['red'])
    m.box(Pn(gx + 0.075, cy + 0.015, -0.10), (Xn, Yn, Zn), (0.030, 0.012, 0.030), sw['dgrey'])
    m.box(Pn(gx + 0.075, cy + 0.035, -0.10), (Xn, Yn, Zn), (0.004, 0.012, 0.004), sw['metal'])
    # a map case / chart table shelf on the left console
    m.box(Pn(0.375, cy + 0.012, -0.12), (Xn, Yn, Zn), (0.085, 0.006, 0.12), sw['metal'])
    return m, (stick, foot), gauges, displays, hud


# ---------------------------------------------------------------- the airframe

PAINTERS = {
    'weapons': p_weapons, 'gear': p_gear,
    'nav1': p_switchbank('NAVIGATION', ['CAP', 'DERIVE', 'VENT', 'POINT'], [((60, 150, 70), True), ((200, 160, 40), False)]),
    'nav2': p_switchbank('RADIO / TACAN', ['VHF', 'UHF', 'TACAN'], [((60, 150, 70), True), ((200, 160, 40), False)]),
    'bomb1': p_switchbank('BOMBARDEMENT', ['SELECT', 'ARMEM', 'FUSEE', 'INTERV'], [((200, 40, 30), False), ((60, 150, 70), True)]),
    'bomb2': p_switchbank('CHRONO LARGAGE', ['MINUT', 'SALVE', 'URG'], [((200, 160, 40), False), ((200, 40, 30), False)]),
}


def install(ac, mesh, stick, gauges, displays, hud, eye, seat=0):
    """as the single-seat scripts' install, but the canopy's CanopyGlass stays (the sight's glass is added to it)"""
    pm = mesh.poly_mesh()
    piece = SOURCE_BONE.split('__', 1)[0]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
    hull = ac.bone('hull')
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == SOURCE_BONE) + 1
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    thr = throttle_lib.add_bones(ac.bones, THR, piece + '__cockpit')
    print(f'{piece} throttle bones {[(n, p) for n, p, _ in thr]}')
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
            keep = ~((np.abs(c[:, 0] - eye[0]) < 0.08) & (np.abs(c[:, 1] - (eye[1] - 0.045)) < 0.04) &
                     (c[:, 2] > eye[2] + 0.40) & (c[:, 2] < eye[2] + 0.53))
            cg['Triangles'] = [round(float(v), 4) + 0.0 for v in t[keep].reshape(-1)]
        L.add_glass(a, hud['glass'])
    return pm


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    atlas = Atlas(VID)
    mesh, stick, gauges, displays, hud = build(atlas, E0, E1)
    pm = install(ac, mesh, stick, gauges, displays, hud, E0)
    print(f'{VID}: new cockpits {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} displays')
    if not dry:
        write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
