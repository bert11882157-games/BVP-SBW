#!/usr/bin/env python3
"""Xi'an H-6K flight deck and rear crew stations, built by hand for this model (as vulcan_b2.py, vautour_iin.py, mirage_4a.py), from the real layout.

Measured off the model (the faces of H-6K.mtb in checks/orig and the source elements, h6k/cutlist.txt; vehicle blocks, 1 block = 1 m; x to the
left, y up, z forward; source units x S / 16 with S = 0.8064, see canopy_h_6k.py):
  * the cabin: floor top y 2.570 (the model's floor slabs, source y 44), walls at |x| 1.008 (20 units) from the seat-back bulkhead (z 12.73) to the
    rear console wall (z 11.56) and narrowing to 0.86 at the windscreen, the roof at y 4.13 (75) over the centre, the shell sloping in above y 3.28;
  * the pilots sit side by side forward-facing (eyes (+-0.4032, 3.8052, 13.2552)), two crew sit behind them facing aft (eyes (+-0.4032, 3.8052,
    12.1968)); the dash face stands at z 13.83 (source -289.3), the glareshield ledge of the nose deck at y 3.68;
  * the old interior (both seat groups, the dash block, pedestal, side consoles, partitions and the rear console wall) is cut out by canopy_h_6k.py's
    boxes; the flight-deck windows are canopy_h_6k.py's.
Built:
  * captain (Seat 0, left) and co-pilot (Seat 1, right), side by side on ejection seats: a modernised glass dash, each pilot with two multi-function
    displays (the primary flight display, live, and a second page: stores on the captain's side, radar on the co-pilot's), three round standby
    instruments and the heading, the engine group between them (rpm, EGT and fuel for the two D-30KP-2, a clock, the caution panel), the throttle
    pedestal with the two throttles, side consoles with placarded switch panels, control wheels (yokes: the H-6 / Tu-16 family flies on wheels, one
    per pilot, each in its own stick bone pair), rudder pedals; no head-up display (the H-6K has none that is known);
  * the aft-facing crew behind them (Seats 2 and 3) on swivel seats at a rear console: the navigator / bombardier's radar display (FlightDisplays
    RADAR, Seat 2) with its control panels and keypad, the weapon-systems / EW officer's stores display (FlightDisplays STORES, Seat 3), the
    radar warning receiver (a live dial) and the countermeasure panels, painted; the seat-back bulkhead with a centre doorway; linings.
Placards are Chinese (the aircraft is Chinese; the lamps the game draws stay in English); without a CJK font installed the painters use the
English captions in CAPTION_EN.
Instruments the game draws (CockpitGauges, FlightDisplays) are live; the glass is canopy_h_6k.py's. No HeadUpDisplay.

Imports only cockpit_lib and free_atlas (the building blocks below are inlined, as vulcan_b2.py's).
Run on the airframe's originals (h6k/run_h6k_cockpit.ps1 restores them), after canopy_h_6k.py.
Usage: python3 tools/cockpit_models/h_6k.py [--dry-run]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import free_atlas as FA   # noqa: E402
import canopy_h_6k as CM   # noqa: E402
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


class Facet:
    """a flat panel facet: origin (the reference point), right and up directions in the panel plane, normal toward the crew.
    at(a, y): a metres along 'right' from the origin, at vehicle height y (the up axis leans, so z follows)"""

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
    """a console top seen from above: black sub-panels stacked fore to aft (tile top = forward) with toggles and knobs"""
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
                    d.ellipse([x - 14, yy - 14, x + 14, yy + 14], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
                else:
                    d.rectangle([x - 5, yy - 18, x + 5, yy + 6], fill=(180, 180, 180, 255))
                    d.ellipse([x - 9, yy - 26, x + 9, yy - 10], fill=(200, 200, 200, 255))
            y = y1
        if slot:
            d.rectangle([S * slot[0], H * slot[1], S * slot[2], H * slot[3]], fill=(10, 10, 10, 255))
    return fn


def rotated(fn):
    """a painter for a tile laid on its side: [fn] paints the upright tile (its height the tile's width), the result is
    turned a quarter left. A face's uv (s, t) on the upright tile is (1 - t, s) on the turned one"""
    def g(d, im):
        tmp = Image.new('RGBA', (im.height, im.width), (0, 0, 0, 255))
        fn(ImageDraw.Draw(tmp), tmp)
        im.paste(tmp.rotate(90, expand=True), (0, 0))
    return g


def p_cushion(colour, straps=False):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 6, seed=50)
        for k in range(1, 4):
            d.line([0, H * k / 4, S, H * k / 4], fill=tuple(max(0, c - 18) for c in colour) + (255,), width=4)
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



# ---------------------------------------------------------------- fonts: Chinese placards need a CJK face; without one the English captions are painted
CJK_FILES = [r'C:\Windows\Fonts\msyhbd.ttc', r'C:\Windows\Fonts\msyh.ttc', r'C:\Windows\Fonts\simhei.ttf', r'C:\Windows\Fonts\simsun.ttc',
             '/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc', '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc']
LATIN_FILES = (['arialbd.ttf', 'DejaVuSans-Bold.ttf'], ['arial.ttf', 'DejaVuSans.ttf'])


def have_cjk():
    for f in CJK_FILES:
        try:
            ImageFont.truetype(f, 10)
            return True
        except OSError:
            continue
    return False


CJK = have_cjk()


def ZH(zh, en):
    """a caption: the Chinese one when a CJK face is installed, else the English one"""
    return zh if CJK else en


def font(px, bold=False, cjk=False):          # noqa: F811 (replaces the block's Latin-only face)
    if cjk:
        for f in CJK_FILES:
            try:
                return ImageFont.truetype(f, max(6, int(px)))
            except OSError:
                continue
    for name in LATIN_FILES[0 if bold else 1]:
        try:
            return ImageFont.truetype(name, max(6, int(px)))
        except OSError:
            continue
    return ImageFont.load_default()


def text(d, xy, s, px, fill=WHITE, anchor='mm', bold=False):   # noqa: F811
    d.text(xy, s, font=font(px, bold, any(ord(c) > 127 for c in s)), fill=tuple(fill), anchor=anchor)


VID = 'h_6k'


def _eye(name):
    d = json.load(open(CM.DATA, encoding='utf-8'))
    return np.array(d['Attachments'][name]['Position'], float)


NEYE = _eye('nav_eye')                            # the aft-facing crew's eyes
WEYE = _eye('ewo_eye')
COCKPIT_BONE = 'wreck_fuselage_0__cockpit'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

# colours: a modernised PLAAF flight deck: dark grey panels, light grey-green linings, black seat cushions
PANEL = (50, 53, 57)
PANEL_D = (32, 34, 38)
BLACK = (18, 19, 21)
DGREY = (46, 48, 50)
MGREY = (92, 96, 98)
LINING = (118, 126, 120)       # pale grey-green side linings
LINING_D = (92, 100, 96)
CEIL = (170, 174, 166)
FLOORC = (52, 54, 54)
FRAME = (58, 62, 64)           # seat structure
CUSHION = (50, 56, 60)
HEADPAD = (86, 90, 88)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
DESK = (58, 62, 60)
DESKTOP = (76, 82, 78)
AMBER = (214, 140, 30)
GREEN = (60, 160, 70)

# ---------------------------------------------------------------- the measured cabin (vehicle blocks; B converts source units)
B = CM.B
S_U = CM.S / 16                                   # blocks per source unit
F_Y = float(B(0, 44, 0)[1])                       # the floor's top (the model's slabs)
Z_REAR = float(B(0, 0, -244.4)[2])                # the rear console wall's face
Z_BK = float(B(0, 0, -267.5)[2])                  # the seat-back bulkhead between the stations
Z_PANEL = float(B(0, 0, -289.3)[2])               # the dash panel's bottom edge
Y_PB = float(B(0, 50, 0)[1])                      # the dash panel's bottom and top edges
Y_PT = float(B(0, 62.4, 0)[1])
LEAN = math.radians(12.0)                         # the dash panel's face leans back (its top is further forward)
R_P = np.array([-1.0, 0, 0])                      # the pilot's right
U_P = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_P = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward the pilot
PX = 0.57                                         # the dash panel's half width
LEAN_R = math.radians(8.0)                        # the rear console's panel face leans back this much
R_R = np.array([1.0, 0, 0])                       # the rear crew's right (they face aft)
U_R = np.array([0, math.cos(LEAN_R), -math.sin(LEAN_R)])
N_R = np.array([0, math.sin(LEAN_R), math.cos(LEAN_R)])        # toward the rear crew
RPB = float(B(0, 47, 0)[1])                       # the rear panel's bottom edge (y) and top edge
RPT = float(B(0, 62.0, 0)[1])
RPX = 0.88

# measured inner wall half widths (source units) at y 54 / 58 / 62 / 66 against z, from h6k/measure.py on the cut model
WALL_Z = [-291, -290, -285, -280, -275, -270, -266, -244]
WALL = {54: [16.8, 16.8, 17.9, 18.5, 19.1, 19.8, 20.0, 20.0],
        58: [15.0, 15.4, 17.2, 18.1, 18.9, 19.7, 20.0, 20.0],
        62: [11.0, 12.9, 15.4, 16.3, 16.9, 17.7, 18.0, 18.0],
        66: [6.0, 6.5, 14.0, 14.9, 15.6, 16.2, 16.5, 16.5]}


def wall_x(y_src, z_src):
    """the cabin's inner half width (blocks) at a source height and z (y 54 to 66)"""
    ys = sorted(WALL)
    vals = [float(np.interp(z_src, WALL_Z, WALL[y])) for y in ys]
    return float(np.interp(y_src, ys, vals)) * S_U


def src_z(zb):
    return -zb / S_U - 15.0


def src_y(yb):
    return yb / S_U - 7.0


def Zb(zs):
    return float(B(0, 0, zs)[2])


def Yb(ys):
    return float(B(0, ys, 0)[1])


# ---------------------------------------------------------------- painted faces
def p_panel_face(boxes, W_, H_, halfw, yb, yt, groups, seed=5, rear=False):
    """a facet's face: dark with the instruments' backing discs / rectangles, group outlines with captions and screws along the edges.
    x is the vehicle x: the front panel's tile runs from +x (the viewer's left, the pilots face forward) to -x; the rear panel's from -x to +x"""
    def fn(d, im):
        SS = Atlas.SS
        noisy(d, im, PANEL, 4, seed=seed)

        def to_px(a, y):
            u = (a + halfw) if rear else (halfw - a)
            return u / (2 * halfw) * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        px_m = W_ * SS / (2 * halfw)
        for (xa, ya, xb, yb2, title) in groups:
            ax, ay = to_px(xa, ya); bx, by = to_px(xb, yb2)
            d.rectangle([min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)], outline=(90, 94, 96, 255), width=SS * 2)
            if title:
                text(d, ((ax + bx) / 2, min(ay, by) + SS * 6), title, SS * 6.5, fill=(170, 174, 168), bold=True)
        for kind, x, y, w, h in boxes:
            cx, cy = to_px(x, y)
            if kind == 'disc':
                r = (w / 2 * (1 + RIM) + 0.004) * px_m
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(14, 16, 18, 255))
            else:
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([cx - sx, cy - sy, cx + sx, cy + sy], fill=(14, 16, 18, 255))
        for k in range(16):
            for yy in (yb + 0.012, yt - 0.014):
                sx_, sy_ = to_px(-halfw + 0.02 + (2 * halfw - 0.04) * k / 15, yy)
                r = SS * 2
                d.ellipse([sx_ - r, sy_ - r, sx_ + r, sy_ + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
    return fn

def p_wall(colour, seed):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 4, seed=seed)
        d.line([0, H * .5, S, H * .5], fill=tuple(max(0, c - 22) for c in colour) + (255,), width=3)
    return fn


def p_ceiling(d, im):
    S, H = im.width, im.height
    noisy(d, im, CEIL, 3, seed=77)
    for k in range(1, 4):
        d.line([S * k / 4, 0, S * k / 4, H], fill=(150, 154, 146, 255), width=3)


def p_switchbank(title, rows, lamps=()):
    """a placarded switch panel: the title, rows of toggles with tiny labels, a few lamps"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
        text(d, (S / 2, Hh * 0.09), title, min(Hh * .11, S * .12))
        rng = np.random.default_rng(len(rows) * 13 + sum(ord(c) for c in title) % 97)
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


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.08), ZH('\u8d77\u843d\u67b6', 'GEAR'), Hh * 0.075)
    for k, lab in enumerate([ZH('\u524d', 'NOSE'), ZH('\u5de6', 'LEFT'), ZH('\u53f3', 'RIGHT')]):
        x = S * (0.2 + 0.3 * k)
        lamp(d, [x - 20, Hh * .20, x + 20, Hh * .38], GREEN, None, lit=True)
        text(d, (x, Hh * .46), lab, Hh * .06)
    d.rectangle([S * .46, Hh * .56, S * .54, Hh * .80], fill=(190, 190, 190, 255))
    text(d, (S / 2, Hh * .92), ZH('\u6536    \u653e', 'UP   DOWN'), Hh * .06)


def p_flaps(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.08), ZH('\u895c\u7ffc', 'FLAPS'), Hh * 0.075)
    for k, lab in enumerate(['0', '20', '35']):
        y = Hh * (0.28 + 0.24 * k)
        d.rectangle([S * .12, y - 12, S * .30, y + 12], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=2)
        text(d, (S * .62, y), lab + '\u00b0', Hh * .07)
    d.rectangle([S * .17, Hh * .30, S * .23, Hh * .46], fill=(190, 190, 190, 255))


def p_brakes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.12), ZH('\u5239\u8f66', 'BRAKES'), Hh * 0.14)
    d.ellipse([S * .12, Hh * .30, S * .40, Hh * .85], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
    text(d, (S * .26, Hh * .58), ZH('\u505c\u653e', 'PARK'), Hh * .13)
    d.rectangle([S * .55, Hh * .35, S * .90, Hh * .75], fill=(190, 40, 30, 255))
    text(d, (S * .725, Hh * .55), ZH('\u963b\u529b', 'DRAG'), Hh * .13, fill=(240, 240, 230))
    text(d, (S * .725, Hh * .72), ZH('\u4f1e', 'CHUTE'), Hh * .12, fill=(240, 240, 230))


def p_radio(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.14), ZH('\u901a\u4fe1', 'COMM'), Hh * 0.14)
    for k, lab in enumerate(['VHF', 'HF', ZH('\u654c\u6211', 'IFF')]):
        x = S * (0.2 + 0.3 * k)
        d.ellipse([x - 22, Hh * .38, x + 22, Hh * .38 + 44], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
        text(d, (x, Hh * .86), lab, Hh * .13)


def p_fuel(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.10), ZH('\u71c3\u6cb9', 'FUEL'), Hh * 0.10)
    for k, lab in enumerate([ZH('\u524d', 'FWD'), ZH('\u4e2d', 'MID'), ZH('\u540e', 'AFT'), ZH('\u7ffc', 'WING')]):
        x = S * (0.14 + 0.24 * k)
        d.rectangle([x - 5, Hh * .30, x + 5, Hh * .52], fill=(190, 190, 190, 255))
        d.ellipse([x - 9, Hh * .26, x + 9, Hh * .34], fill=(205, 205, 205, 255))
        lamp(d, [x - 14, Hh * .62, x + 14, Hh * .74], GREEN, None, lit=(k != 3))
        text(d, (x, Hh * .88), lab, Hh * .09)


def p_pedestal(d, im):
    """the centre pedestal's top seen from above (tile top = forward): the throttle quadrant, two slots"""
    S, Hh = im.width, im.height
    noisy(d, im, PANEL, 4, seed=21)
    d.rectangle([S * .08, Hh * .05, S * .92, Hh * .62], fill=(14, 16, 18, 255))
    for k in range(2):
        x = S * (0.30 + 0.40 * k)
        d.rectangle([x - 7, Hh * .09, x + 7, Hh * .58], fill=(4, 4, 4, 255))
        text(d, (x, Hh * .72), str(k + 1), Hh * .11)
    text(d, (S * .5, Hh * .90), ZH('\u6cb9\u95e8', 'THROTTLE'), Hh * .10)


def p_trim(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=22)
    text(d, (S / 2, Hh * .12), ZH('\u914d\u5e73', 'TRIM'), Hh * .12)
    for k in range(3):
        x = S * (0.2 + 0.3 * k)
        d.ellipse([x - 16, Hh * .35, x + 16, Hh * .35 + 32], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
        d.line([x, Hh * .35 + 16, x, Hh * .35 + 2], fill=(230, 230, 230, 255), width=3)
    text(d, (S / 2, Hh * .85), ZH('\u5347\u964d  \u6a2a\u6eda  \u822a\u5411', 'PITCH ROLL YAW'), Hh * .09)


def p_keypad(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    for r in range(4):
        for c in range(5):
            x0 = S * (0.07 + 0.18 * c); y0 = Hh * (0.30 + 0.16 * r)
            d.rectangle([x0, y0, x0 + S * .14, y0 + Hh * .12], fill=(54, 58, 60, 255), outline=(120, 124, 126, 255), width=2)
    d.rectangle([S * .07, Hh * .08, S * .93, Hh * .22], fill=(8, 30, 20, 255), outline=(90, 96, 92, 255), width=2)
    text(d, (S / 2, Hh * .15), ZH('\u5bfc\u822a', 'NAV') + '  12.34  56.78', Hh * .10, fill=(120, 240, 150))


def p_radar_ctl(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.06), ZH('\u96f7\u8fbe', 'RADAR'), S * 0.2)
    for k, lab in enumerate([ZH('\u626b\u63cf', 'SCAN'), ZH('\u6a21\u5f0f', 'MODE'), ZH('\u8ddd\u79bb', 'RANGE'), ZH('\u589e\u76ca', 'GAIN')]):
        y = Hh * (0.20 + 0.20 * k)
        d.ellipse([S * .12, y - 14, S * .12 + 28, y + 14], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
        d.line([S * .12 + 14, y, S * .12 + 22, y - 12], fill=(230, 230, 230, 255), width=3)
        text(d, (S * .72, y), lab, S * .16)


def p_ecm(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.10), ZH('\u7535\u5b50\u5bf9\u6297', 'ECM CONTROL'), Hh * 0.10)
    labs = [ZH('\u5e72\u6270', 'JAM') + '1', ZH('\u5e72\u6270', 'JAM') + '2', ZH('\u5e72\u6270', 'JAM') + '3', ZH('\u8bf1\u9a71', 'DECOY'),
            ZH('\u63a5\u6536', 'RCVR'), ZH('\u5f85\u673a', 'STBY')]
    for k, lab in enumerate(labs):
        x = S * (0.11 + 0.15 * k)
        lamp(d, [x - 18, Hh * .24, x + 18, Hh * .46], AMBER if k < 4 else GREEN, None, lit=(k == 4))
        d.rectangle([x - 5, Hh * .54, x + 5, Hh * .74], fill=(190, 190, 190, 255))
        text(d, (x, Hh * .88), lab, Hh * .09)


def p_dispenser(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.12), ZH('\u6295\u653e', 'DISPENSER'), Hh * 0.12)
    d.rectangle([S * .10, Hh * .30, S * .55, Hh * .62], fill=(10, 10, 10, 255), outline=(150, 150, 150, 255), width=3)
    text(d, (S * .325, Hh * .46), '24  24', Hh * .2, fill=(240, 200, 60))
    for k in range(3):
        x = S * (0.68 + 0.12 * k)
        d.ellipse([x - 12, Hh * .34, x + 12, Hh * .34 + 24], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
    text(d, (S * .5, Hh * .84), ZH('\u7bb4\u6761    \u70ed\u7130', 'CHAFF    FLARE'), Hh * .1)


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.10), ZH('\u6b66\u5668\u63a7\u5236', 'WEAPON CONTROL'), Hh * 0.10)
    labs = [ZH('\u5bfc\u5f39', 'MSL') + '1', ZH('\u5bfc\u5f39', 'MSL') + '2', ZH('\u5bfc\u5f39', 'MSL') + '3', ZH('\u8f70\u70b8', 'BOMB'), ZH('\u6295\u5f39', 'REL'),
            ZH('\u4fdd\u9669', 'SAFE')]
    for k, lab in enumerate(labs):
        x = S * (0.11 + 0.15 * k)
        lamp(d, [x - 18, Hh * .24, x + 18, Hh * .46], RED if k == 4 else AMBER, None, lit=(k == 5))
        d.rectangle([x - 5, Hh * .54, x + 5, Hh * .74], fill=(190, 190, 190, 255))
        text(d, (x, Hh * .88), lab, Hh * .09)


def p_rwr_ctl(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.10), ZH('\u96f7\u8fbe\u544a\u8b66', 'RWR'), Hh * 0.12)
    for k, lab in enumerate([ZH('\u4eae\u5ea6', 'BRT'), ZH('\u97f3\u91cf', 'VOL'), ZH('\u6a21\u5f0f', 'MODE')]):
        x = S * (0.2 + 0.3 * k)
        d.ellipse([x - 18, Hh * .32, x + 18, Hh * .32 + 36], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
        d.line([x, Hh * .32 + 18, x + 9, Hh * .32 + 4], fill=(230, 230, 230, 255), width=3)
        text(d, (x, Hh * .82), lab, Hh * .11)


def p_chart(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, (214, 210, 190), 4, seed=31)
    for k in range(1, 8):
        d.line([S * k / 8, 0, S * k / 8, Hh], fill=(170, 176, 190, 255), width=2)
    for k in range(1, 6):
        d.line([0, Hh * k / 6, S, Hh * k / 6], fill=(170, 176, 190, 255), width=2)
    d.line([S * .1, Hh * .85, S * .45, Hh * .45, S * .9, Hh * .2], fill=(180, 40, 40, 255), width=5)
    d.ellipse([S * .43, Hh * .42, S * .47, Hh * .48], outline=(30, 30, 160, 255), width=4)


def p_headbox(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)


def p_back(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, (70, 76, 64), 5, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(56, 62, 50, 255), width=3)
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


LAMPS = (3, [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in [
    ('MASTER|WARNING', 'MASTER_CAUTION', 'amber'), ('GEAR|DOWN', 'GEAR_DOWN', 'green'),
    ('GEAR|UNSAFE', 'GEAR_TRANSIT', 'red'), ('FUEL|LOW', 'LOW_FUEL', 'amber'),
    ('FIRE', 'FIRE', 'red'), ('ENGINE|FAILURE', 'ENGINE_OUT', 'red')]])



# ---------------------------------------------------------------- the flight deck's shell: linings, floor, bulkhead
INSET = 0.012                                       # linings stand this far inside the shell (the checks want a margin from the outline)


def inward(p, d=0.006):
    """a lining point moved d toward the centre line (x) and down (y)"""
    p = np.array(p, float)
    p[0] -= np.sign(p[0]) * d
    return p


def lining(m, atlas, sw):
    cabin = np.array([0.0, Yb(60), Zb(-270)])
    zs = [-285, -280, -275, -270, -266, -244.4]
    bands = [(44, 54), (54, 58), (58, 62), (62, 66)]
    for s in (1, -1):
        def P(y, z, s=s):
            yy = min(max(y, 54), 66)
            return np.array([s * (wall_x(yy, z) - INSET), Yb(y), Zb(z)])
        # the front stretch up to y 58 (the windscreen's lower part), then all bands from z -285
        for za, zb_ in [(-291, -285)] + list(zip(zs[:-1], zs[1:])):
            for (ya, yb_) in (bands[:2] if za < -285 else bands):
                q = [P(ya, za), P(ya, zb_), P(yb_, zb_), P(yb_, za)]
                m.flat(q, sw['lining'] if (ya + yb_) % 2 == 0 else sw['lining_d'], toward=cabin)
        # the upper wall (the slope between y 66 and the roof) and the roof, aft of the side windows (z -268)
        for za, zb_ in zip([-266], [-244.4]):
            sa, sb = CM.STATIONS[-266], CM.STATIONS[-244]
            for k in range(2, 8):
                qa = [np.array([s * (sa[k][0] * S_U - INSET), Yb(sa[k][1]) - INSET, Zb(za)]),
                      np.array([s * (sb[k][0] * S_U - INSET), Yb(sb[k][1]) - INSET, Zb(zb_)]),
                      np.array([s * (sb[k + 1][0] * S_U - INSET), Yb(sb[k + 1][1]) - INSET, Zb(zb_)]),
                      np.array([s * (sa[k + 1][0] * S_U - INSET), Yb(sa[k + 1][1]) - INSET, Zb(za)])]
                m.flat(qa, sw['ceil'], toward=cabin)
        # the roof between the windscreen and the side-window bulkhead (the model's ceiling: x <= 12)
        order = [-285, -280, -275, -270, -266]
        for za, zb_ in zip(order[:-1], order[1:]):
            sa, sb = CM.STATIONS[za], CM.STATIONS[zb_]
            for k in range(4, 8):
                ka = k
                qa = [np.array([s * (sa[ka][0] * S_U - INSET), Yb(sa[ka][1]) - INSET, Zb(za)]),
                      np.array([s * (sb[k][0] * S_U - INSET), Yb(sb[k][1]) - INSET, Zb(zb_)]),
                      np.array([s * (sb[k + 1][0] * S_U - INSET), Yb(sb[k + 1][1]) - INSET, Zb(zb_)]),
                      np.array([s * (sa[ka + 1][0] * S_U - INSET), Yb(sa[ka + 1][1]) - INSET, Zb(za)])]
                m.flat(qa, sw['ceil'], toward=cabin)
    # the floor mat (4 mm over the model's slabs)
    xa, xf = 20 * S_U - INSET, 17.1 * S_U - INSET
    fy = F_Y + 0.004
    pts = [[-xa, fy, Zb(-244.4)], [xa, fy, Zb(-244.4)], [xa, fy, Zb(-270)], [xf, fy, Zb(-290)], [-xf, fy, Zb(-290)], [-xa, fy, Zb(-270)]]
    m.flat(pts, sw['floor'], normal=[0, 1, 0])


def bulkhead_poly(side_x0):
    """the seat-back bulkhead / rear wall outline of one half (x >= side_x0 for the right half of the section): polygon points (x, y), x >= 0"""
    out = CM.STATIONS[-266]
    pts = [(side_x0, F_Y), (20 * S_U - INSET, F_Y)]
    pts += [(x * S_U - INSET, Yb(y) - INSET) for x, y in out[:-1] if x * S_U > side_x0]
    pts += [(side_x0, Yb(75) - INSET)]
    # the roof points are lifted off the lining by their own inset
    return pts


def bulkhead(m, atlas, sw):
    z = Z_BK
    aisle = 0.25
    for s in (1, -1):
        pts = bulkhead_poly(aisle)
        pts = [(s * x, y) for x, y in pts]
        m.flat([[x, y, z + 0.012] for x, y in pts], sw['lining'], normal=[0, 0, 1])           # toward the pilots
        m.flat([[x, y, z - 0.012] for x, y in pts], sw['lining'], normal=[0, 0, -1])          # toward the aft crew
        m.flat([[s * aisle, F_Y, z + 0.012], [s * aisle, Yb(75) - INSET, z + 0.012], [s * aisle, Yb(75) - INSET, z - 0.012],
                [s * aisle, F_Y, z - 0.012]], sw['panel_d'], normal=[-s, 0, 0])               # the doorway's jamb
        for (x0, y0), (x1, y1) in zip(pts[1:-1], pts[2:-1]):                                  # the thickness along the shell
            m.flat([[x0, y0, z + 0.012], [x1, y1, z + 0.012], [x1, y1, z - 0.012], [x0, y0, z - 0.012]], sw['panel_d'],
                   toward=[0, Yb(60), z])


# ---------------------------------------------------------------- the dash
def basic_gauges():
    """(name, kind, x, y, dia, seat): the standby dials and the engine group; vehicle x, vehicle height"""
    out = []
    ry = Yb(52.4)
    for seat, sg in ((0, 1), (1, -1)):
        xs = {'ATTITUDE': 0.44, 'AIRSPEED': 0.34, 'ALTIMETER': 0.24, ('HEADING' if seat == 0 else 'VVI'): 0.14}
        for kind, ax in xs.items():
            out.append((f'{kind.lower()}{seat}', kind, sg * ax, ry, 0.085, seat))
    for k, (x, kind) in enumerate([(0.04, 'ENGINE_RPM'), (-0.04, 'ENGINE_RPM')]):
        out.append((f'rpm{k}', kind, x, Yb(58.2), 0.062, 0))
    for k, x in enumerate((0.04, -0.04)):
        out.append((f'egt{k}', 'EGT', x, Yb(55.9), 0.062, 0))
    for k, x in enumerate((0.04, -0.04)):
        out.append((f'fuel{k}', 'FUEL_QTY', x, Yb(53.6), 0.062, 0))
    out.append(('clock', 'CLOCK', 0.0, Yb(50.9), 0.056, 0))
    return out


def wing_strip(m, fp, sw, s):
    """the dash's side wing (the panel plane out to the wall) on one side"""
    ys = [Y_PB, Yb(54), Yb(58), Yb(62), Y_PT]
    def outer(y):
        return wall_x(src_y(y), -290.5) - 0.006
    for ya, yb_ in zip(ys[:-1], ys[1:]):
        q = [fp.at(-s * PX, ya), fp.at(-s * outer(ya), ya), fp.at(-s * outer(yb_), yb_), fp.at(-s * PX, yb_)]
        m.flat(q, sw['panel_d'], normal=fp.N)


def dash(m, atlas, sw, gauges, displays):
    cen = np.array([0.0, Y_PB, Z_PANEL])
    fp = Facet(cen, R_P, U_P, N_P)
    a = lambda x, y: fp.at(-x, y)          # noqa: E731  (vehicle x -> the facet's right = -x)
    lay = basic_gauges()
    boxes = [('disc', x, y, dia, dia) for _n, _k, x, y, dia, _s in lay]
    # displays: two multi-function displays per pilot (PFD live; stores / radar second page)
    disp = [(0, 'PFD', 0.41, Yb(57.6), 0.17), (0, 'STORES', 0.195, Yb(57.6), 0.17),
            (1, 'PFD', -0.41, Yb(57.6), 0.17), (1, 'RADAR', -0.195, Yb(57.6), 0.17)]
    for seat, kind, x, y, w in disp:
        boxes.append(('rect', x, y, w * 1.24, w * 1.24))
    boxes.append(('rect', 0.0, Yb(61.3), 0.15, 0.05))                 # the caution panel's seat
    groups = [(0.60 - 0.04, Y_PT - 0.012, 0.115, Y_PB + 0.008, ZH('机长', 'CAPTAIN')),
              (-0.115, Y_PT - 0.012, -0.60 + 0.04, Y_PB + 0.008, ZH('副驾驶', 'CO-PILOT')),
              (0.085, Y_PT - 0.012, -0.085, Y_PB + 0.008, ZH('发动机', 'ENGINES'))]
    W_ = int(round(2 * PX * 600)); Hp = int(round((Y_PT - Y_PB) / math.cos(LEAN) * 600))
    ptile = atlas.paint(W_, Hp, p_panel_face(boxes, W_, Hp, PX, Y_PB, Y_PT, groups))
    pb = [a(PX, Y_PB), a(-PX, Y_PB), a(-PX, Y_PT), a(PX, Y_PT)]
    m.quad_tex(pb, ptile, normal=fp.N)
    for s in (1, -1):
        wing_strip(m, fp, sw, s)
    # the knee wall under the panel, the dash's top: a ramp up to the nose deck's underside (the glareshield), side flaps
    xk = wall_x(46, -289.3) - 0.006
    m.flat([[-xk, F_Y + 0.002, Z_PANEL], [xk, F_Y + 0.002, Z_PANEL], [xk, Y_PB, Z_PANEL], [-xk, Y_PB, Z_PANEL]], sw['panel_d'], normal=[0, 0, -1])
    zt = float(fp.at(0, Y_PT)[2])
    yr, zr = Yb(63.5), Zb(-293.6)
    m.flat([[PX, Y_PT, zt], [0.36, yr, zr], [-0.36, yr, zr], [-PX, Y_PT, zt]], sw['black'], normal=[0, 1, -0.3])
    for s in (1, -1):
        xw = wall_x(62.4, -291.9) - 0.006
        m.flat([[s * PX, Y_PT, zt], [s * xw, Y_PT, zt], [s * 0.36, yr, zr]], sw['black'], normal=[-s * 0.3, 1, -0.3])
    # the panel's gauges and displays
    for name, kind, x, y, dia, seat in lay:
        c = a(x, y)
        gauges.append({'Seat': seat, 'Kind': kind, 'Center': r5(c + fp.N * GAUGE_LIFT), 'Normal': r5(fp.N), 'Up': r5(fp.U),
                       'Diameter': dia, 'Depth': 0.03})
    for seat, kind, x, y, w in disp:
        c = a(x, y)
        displays.append({'Seat': seat, 'Center': r5(c + fp.N * DISPLAY_LIFT), 'Normal': r5(fp.N), 'Up': r5(fp.U), 'Width': w,
                         'Depth': 0.035, 'Kind': kind, 'Style': 'LCD'})
    cols, lamps = LAMPS
    c = a(0.0, Yb(61.3))
    gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + fp.N * 0.004), 'Normal': r5(fp.N), 'Up': r5(fp.U), 'Diameter': 0.15,
                   'Width': 0.15, 'Height': 0.05, 'Depth': 0.02, 'Columns': cols, 'Lamps': lamps})
    m.frame_rect(c, fp.N, fp.U, 0.15, 0.05, 0.005, 0.008, sw['bezel'])
    # painted placards on the panel: the standby row's caption plates and the engine group's
    for lab, x in ((ZH('备用仪表', 'STANDBY'), 0.29), (ZH('备用仪表', 'STANDBY'), -0.29)):
        c2 = a(x, Y_PB + 0.016)
        plate = atlas.paint(160, 14, p_label_plate(lab))
        m.rect_tex(c2 + fp.N * 0.003, fp.N, fp.U, 0.30, 0.020, plate)
    return fp


# ---------------------------------------------------------------- pedestal, consoles, pedals, wheels, seats
def pedestal(m, atlas, sw):
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    ped = atlas.paint(96, 196, p_pedestal)
    px0, px1, pz0, pz1, py = -0.095, 0.095, Zb(-273), Zb(-288), Yb(53)
    # (z runs forward: -273 is the rear end, -288 the front end; pz0 < pz1)
    m.face([[px0, py, pz1], [px1, py, pz1], [px1, py, pz0], [px0, py, pz0]],
           [m.uv(ped, 0, 1), m.uv(ped, 1, 1), m.uv(ped, 1, 0), m.uv(ped, 0, 0)], normal=[0, 1, 0])
    for s in (1, -1):
        m.flat([[s * px1, F_Y + 0.002, pz0], [s * px1, F_Y + 0.002, pz1], [s * px1, py, pz1], [s * px1, py, pz0]], sw['panel_d'], normal=[s, 0, 0])
    m.flat([[px0, F_Y + 0.002, pz0], [px1, F_Y + 0.002, pz0], [px1, py, pz0], [px0, py, pz0]], sw['panel_d'], normal=[0, 0, -1])
    m.flat([[px0, F_Y + 0.002, pz1], [px1, F_Y + 0.002, pz1], [px1, py, pz1], [px0, py, pz1]], sw['panel_d'], normal=[0, 0, 1])
    # the two throttle levers (one per D-30KP-2), stalks and black grips leaning back (toward the pilots = -z)
    for k in range(2):
        tx = px1 - (px1 - px0) * (0.30 + 0.40 * k)
        tz = pz1 - (pz1 - pz0) * 0.38
        ax = (X, unit([0, 1, -0.25]), unit([0, 0.25, 1]))
        m.box([tx, py + 0.045, tz], ax, (0.004, 0.050, 0.004), sw['metal'])
        m.box([tx, py + 0.108, tz - 0.012], ax, (0.013, 0.030, 0.021), sw['black'])
        m.box([tx, py + 0.141, tz - 0.016], ax, (0.011, 0.005, 0.018), sw['dgrey'])
    # the speed brake / reverser lever's box and the gear and flap levers on the pedestal's sides
    m.box([0.0, py + 0.012, pz0 + 0.06], (X, Y, Z), (0.050, 0.012, 0.030), sw['dgrey'])
    m.box([0.0, py + 0.034, pz0 + 0.06], (X, Y, Z), (0.006, 0.014, 0.006), sw['red'])
    for s, col in ((1, 'metal'), (-1, 'metal')):
        m.box([s * 0.118, py + 0.020, pz0 + 0.30], (X, Y, Z), (0.022, 0.020, 0.050), sw['dgrey'])
        ax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
        m.box([s * 0.118, py + 0.075, pz0 + 0.31], ax, (0.006, 0.048, 0.006), sw[col])
        m.box([s * 0.118, py + 0.130, pz0 + 0.325], ax, (0.011, 0.012, 0.018), sw['red'] if s > 0 else sw['yellow'])


def consoles(m, atlas, sw):
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    cy = Yb(52.5)
    zf, zb_ = Zb(-289.0), Zb(-271.0)           # front (larger z), back
    specs = {1: ('气气', None), -1: ('', None)}
    for s in (1, -1):
        xi = 0.64
        xo = min(wall_x(52, -289) - 0.012, 0.84)
        tiles = ([p_switchbank(ZH('通信导航', 'COMM / NAV'), ['VHF', 'HF', ZH('导航', 'NAV'), ZH('敌我', 'IFF')], ((GREEN, True), (AMBER, False)))]
                 if s > 0 else [p_switchbank(ZH('燃油电源', 'FUEL / ELEC'), [ZH('燃油', 'FUEL'), ZH('电源', 'POWER'), ZH('液压', 'HYD'),
                                                                                              ZH('灯光', 'LIGHTS')], ((GREEN, True), (AMBER, False)))])
        t = atlas.paint(96, 220, rotated(tiles[0]))
        # the console's top: tile laid on its side (rotated), forward = the panel's top
        m.face([[s * xi, cy, zf], [s * xo, cy, zf], [s * xo, cy, zb_], [s * xi, cy, zb_]],
               [m.uv(t, 1, 0), m.uv(t, 1, 1), m.uv(t, 0, 1), m.uv(t, 0, 0)], normal=[0, 1, 0])
        m.flat([[s * xi, F_Y + 0.002, zb_], [s * xi, F_Y + 0.002, zf], [s * xi, cy, zf], [s * xi, cy, zb_]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[s * xi, F_Y + 0.002, zb_], [s * xo, F_Y + 0.002, zb_], [s * xo, cy, zb_], [s * xi, cy, zb_]], sw['panel_d'], normal=[0, 0, -1])
        m.flat([[s * xi, F_Y + 0.002, zf], [s * xo, F_Y + 0.002, zf], [s * xo, cy, zf], [s * xi, cy, zf]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[s * xo, F_Y + 0.002, zb_], [s * xo, F_Y + 0.002, zf], [s * xo, cy, zf], [s * xo, cy, zb_]], sw['panel_d'], normal=[-s, 0, 0])


def pedals(m, sw, x0):
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    for s2 in (1, -1):
        zc = Zb(-286.0)
        m.box([x0 + s2 * 0.10, F_Y + 0.13, zc], (X, unit([0, 1, 0.35]), unit([0, -0.35, 1])), (0.045, 0.07, 0.010), sw['mgrey'])
        m.box([x0 + s2 * 0.10, F_Y + 0.048, zc - 0.03], (X, Y, Z), (0.012, 0.045, 0.05), sw['dgrey'])


def seat_mesh(m, atlas, sw, P, X, Y, Z, rails=(-0.46, 0.55)):
    """ejection seat: bucket and pan, a harness back, headbox, the face-curtain handle above, the lower handle between the knees;
    eye-relative (headbox top y +0.105)"""
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    back = atlas.paint(48, 64, p_back)
    headbox = atlas.paint(64, 64, p_headbox)
    stripes = atlas.paint(64, 16, p_stripes)
    ax_b = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
    m.box(P(0, -1.075, -0.10), (X, Y, Z), (0.18, 0.025, 0.28), sw['frame'])                       # the seat base
    m.box(P(0, -0.925, -0.12), (X, Y, Z), (0.17, 0.030, 0.27), sw['cushion'], front=(1, 1, cushion, -X, Z))   # the pan
    for s in (1, -1):
        m.box(P(s * 0.195, -0.88, -0.14), (X, Y, Z), (0.012, 0.12, 0.25), sw['frame'])           # bucket sides
        m.box(P(s * 0.15, rails[0], -0.415), ax_b, (0.018, rails[1], 0.020), sw['frame'])         # guide rails up to the headbox
        m.box(P(s * 0.215, -0.82, 0.0), (X, Y, Z), (0.012, 0.012, 0.15), sw['dgrey'])            # arm rest
    m.box(P(0, -0.58, -0.375), ax_b, (0.19, 0.30, 0.020), sw['para'], front=(2, 1, back, X, ax_b[1]))   # the harness back
    m.box(P(0, -0.095, -0.335), (X, Y, Z), (0.15, 0.20, 0.070), sw['frame'])                    # headbox (top y +0.105)
    m.box(P(0, -0.07, -0.2655), (X, Y, Z), (0.11, 0.10, 0.0045), sw['cushion'], front=(2, 1, headbox, X, Y))
    m.box(P(0, 0.082, -0.335), (X, Y, Z), (0.080, 0.022, 0.050), sw['frame'])                    # the face-curtain housing
    for s in (1, -1):
        m.box(P(s * 0.062, 0.085, -0.262), (X, Y, Z), (0.006, 0.012, 0.030), sw['metal'])
    m.box(P(0, 0.093, -0.235), (X, Y, Z), (0.076, 0.009, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))
    for s in (1, -1):                                                                             # the lower handle on two posts
        m.box(P(s * 0.035, -0.855, 0.15), (X, Y, Z), (0.006, 0.05, 0.006), sw['metal'])
    m.box(P(0, -0.795, 0.15), (X, Y, Z), (0.045, 0.011, 0.009), sw['yellow'], front=(2, 1, stripes, X, Y))


def wheel_mesh(atlas, sw, x0):
    """a control wheel on its column (the stick bone's mesh); absolute vehicle blocks, foot at (x0, F_Y + 0.18, zf)"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    st = Mesh(atlas)
    zf = Zb(-284.0)
    foot = np.array([x0, F_Y + 0.18, zf])
    ax = unit([0, 1.0, -0.22])                           # the column leans back toward the pilot
    up = unit([0, 0.22, 1.0])
    centre = foot + ax * 0.50
    st.box(foot + ax * 0.25, (X, ax, up), (0.020, 0.25, 0.020), sw['metal'])                      # the column
    st.box(foot + ax * 0.02, (X, Y, Z), (0.055, 0.030, 0.055), sw['dgrey'])                      # the boot on the floor
    nrm = unit(np.cross(X, ax))                          # the wheel's face normal: toward the pilot (-z, up a little)
    if nrm[2] > 0:
        nrm = -nrm
    # the wheel: an octagonal rim of eight bars (flat to the pilot), two spokes, a hub
    R_ = 0.145
    n = 8
    for k in range(n):
        a0, a1 = 2 * math.pi * k / n, 2 * math.pi * (k + 1) / n
        p0 = centre + (X * math.cos(a0) + ax * math.sin(a0)) * R_
        p1 = centre + (X * math.cos(a1) + ax * math.sin(a1)) * R_
        mid = (p0 + p1) / 2
        d = unit(p1 - p0)
        st.box(mid, (d, nrm, unit(np.cross(d, nrm))), (np.linalg.norm(p1 - p0) / 2 + 0.006, 0.014, 0.011), sw['black'])
    for dxy in (X, ax):
        st.box(centre + dxy * R_ / 2, (dxy, nrm, unit(np.cross(dxy, nrm))), (R_ / 2, 0.009, 0.007), sw['dgrey'])
    st.box(centre, (X, ax, nrm), (0.034, 0.034, 0.024), sw['dgrey'])
    st.box(centre + nrm * 0.026, (X, ax, nrm), (0.012, 0.012, 0.004), sw['red'])                  # the autopilot-disengage button
    return st, foot


# ---------------------------------------------------------------- the flight deck
def flight_deck(m, atlas, sw, gauges, displays):
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    lining(m, atlas, sw)
    bulkhead(m, atlas, sw)
    dash(m, atlas, sw, gauges, displays)
    pedestal(m, atlas, sw)
    consoles(m, atlas, sw)
    sticks = []
    for x0, E_ in ((0.4032, CM.EYE), (-0.4032, CM.EYE_C)):
        m.box([x0, F_Y + 0.083, E_[2] - 0.10], (X, Y, Z), (0.17, 0.08, 0.27), sw['dgrey'])           # the seat's mounting box
        seat_mesh(m, atlas, sw, lambda x, y, z, E_=E_: E_ + np.array([x, y, z], float), X, Y, Z)
        pedals(m, sw, x0)
        st, foot = wheel_mesh(atlas, sw, x0)
        sticks.append((st, foot))
    return sticks


# ---------------------------------------------------------------- the rear crew stations
def swivel_seat(m, atlas, sw, xe, ze):
    """a plain swivel seat facing aft: pedestal on a star base, pan, back (toward the bulkhead, +z), arms, head pad"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    pan_y = F_Y + 0.25
    for k in range(3):
        a = math.radians(60 * k)
        ax = (np.array([math.cos(a), 0, math.sin(a)]), Y, np.array([-math.sin(a), 0, math.cos(a)]))
        m.box([xe, F_Y + 0.016, ze], ax, (0.19, 0.012, 0.022), sw['frame'])
    m.box([xe, F_Y + 0.12, ze], (X, Y, Z), (0.032, 0.095, 0.032), sw['metal'])                      # the column
    m.box([xe, pan_y - 0.022, ze], (X, Y, Z), (0.20, 0.022, 0.20), sw['cushion'], front=(1, 1, cushion, X, Z))      # the pan (top = pan_y)
    m.box([xe, pan_y + 0.30, ze + 0.205], (X, unit([0, 1, 0.12]), unit([0, -0.12, 1])), (0.19, 0.30, 0.040), sw['cushion'])   # the back
    m.box([xe, pan_y + 0.665, ze + 0.225], (X, Y, Z), (0.11, 0.075, 0.030), sw['cushion'])        # head pad
    for s in (1, -1):
        m.box([xe + s * 0.215, pan_y + 0.12, ze + 0.04], (X, Y, Z), (0.012, 0.012, 0.15), sw['dgrey'])
        m.box([xe + s * 0.215, pan_y + 0.05, ze + 0.04], (X, Y, Z), (0.010, 0.075, 0.010), sw['frame'])


def rear_crew(m, atlas, sw, gauges, displays):
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    z_w = Z_REAR + 0.004
    cabin = np.array([0.0, Yb(60), Zb(-255)])
    # -- the rear wall: the whole section behind the panel (floor to the roof), as the lining
    sec = CM.STATIONS[-244]
    R_ = [(20 * S_U - INSET, F_Y + 0.001)] + [(x * S_U - INSET, Yb(y) - INSET) for x, y in sec[:-1]] + [(0.0, Yb(75) - INSET)]
    pts = R_ + [(-x, y) for x, y in reversed(R_[:-1])]
    m.flat([[x, y, z_w] for x, y in pts], sw['lining'], normal=[0, 0, 1])
    # -- the console's panel face: one facet across the two stations
    h = RPT - RPB
    cen = np.array([0.0, RPB, Z_REAR + 0.02 + math.tan(LEAN_R) * h])
    fr = Facet(cen, R_R, U_R, N_R)
    AX = lambda x, y: fr.at(x, y)           # noqa: E731
    items = []
    yc = Yb(55.0)
    # navigator / bombardier (Seat 2, x +0.4032): the radar display, keypad, radar controls, clock
    items += [('disp', 2, 0.4032, yc, ('RADAR', 0.22)), ('paint', 2, 0.70, Yb(53.5), (p_keypad, 0.15, 0.20, 'kp')),
              ('paint', 2, 0.12, Yb(53.5), (p_radar_ctl, 0.09, 0.20, 'rc')), ('gauge', 2, 0.80, Yb(59.4), ('CLOCK', 0.06)),
              ('paint', 2, 0.4032, Yb(49.6), (p_weapons, 0.56, 0.09, 'wp'))]
    # weapon-systems / EW officer (Seat 3, x -0.4032): the stores display, the RWR dial, the ECM and dispenser panels
    items += [('disp', 3, -0.4032, yc, ('STORES', 0.22)), ('gauge', 3, -0.74, Yb(56.8), ('RWR', 0.13)),
              ('paint', 3, -0.74, Yb(50.6), (p_rwr_ctl, 0.14, 0.08, 'rw')), ('paint', 3, -0.12, Yb(55.5), (p_ecm, 0.20, 0.14, 'ecm')),
              ('paint', 3, -0.4032, Yb(49.6), (p_dispenser, 0.40, 0.09, 'dsp'))]
    # centre: the comm panel and the fuel panel (shared)
    items += [('paint', 2, 0.0, Yb(59.6), (p_radio, 0.14, 0.10, 'rad')), ('paint', 3, 0.0, Yb(49.9), (p_fuel, 0.14, 0.10, 'fu'))]
    boxes = []
    for kind, seat, x, y, spec in items:
        if kind == 'gauge':
            boxes.append(('disc', x, y, spec[1], spec[1]))
        elif kind == 'disp':
            boxes.append(('rect', x, y, spec[1] * 1.24, spec[1] * 1.24))
        else:
            boxes.append(('rect', x, y, spec[1], spec[2]))
    W_ = int(round(2 * RPX * 520)); Hp = int(round(h / math.cos(LEAN_R) * 520))
    groups = [(0.025, RPT - 0.012, 0.88 - 0.02, RPB + 0.008, ZH('导航员', 'NAVIGATOR')),
              (-0.88 + 0.02, RPT - 0.012, -0.025, RPB + 0.008, ZH('武器系统员', 'WEAPONS / EW'))]
    ptile = atlas.paint(W_, Hp, p_panel_face(boxes, W_, Hp, RPX, RPB, RPT, groups, seed=8, rear=True))
    pb = [AX(-RPX, RPB), AX(RPX, RPB), AX(RPX, RPT), AX(-RPX, RPT)]
    m.quad_tex(pb, ptile, normal=fr.N)
    # the panel's sides and top close it against the wall; the ledge under it
    xs = RPX
    for s in (1, -1):
        m.flat([[s * xs, RPB, float(fr.at(0, RPB)[2])], [s * xs, RPT, float(fr.at(0, RPT)[2])], [s * xs, RPT, z_w], [s * xs, RPB, z_w]],
               sw['desk'], normal=[-s, 0, 0])
    m.flat([[-xs, RPT, float(fr.at(0, RPT)[2])], [xs, RPT, float(fr.at(0, RPT)[2])], [xs, RPT, z_w], [-xs, RPT, z_w]], sw['desk'], normal=[0, 1, 0])
    zb_ = float(fr.at(0, RPB)[2])
    m.box([0.0, RPB - 0.03, zb_ + 0.07], (X, Y, Z), (xs, 0.03, 0.14), sw['desktop'])               # the console's ledge
    m.flat([[-xs, F_Y + 0.002, z_w], [xs, F_Y + 0.002, z_w], [xs, RPB - 0.06, z_w], [-xs, RPB - 0.06, z_w]], sw['desk'], normal=[0, 0, 1])
    # the dials, displays and painted panels
    for kind, seat, x, y, spec in items:
        c = AX(x, y)
        if kind == 'gauge':
            gauges.append({'Seat': seat, 'Kind': spec[0], 'Center': r5(c + fr.N * GAUGE_LIFT), 'Normal': r5(fr.N), 'Up': r5(fr.U),
                           'Diameter': spec[1], 'Depth': 0.03})
        elif kind == 'disp':
            displays.append({'Seat': seat, 'Center': r5(c + fr.N * DISPLAY_LIFT), 'Normal': r5(fr.N), 'Up': r5(fr.U), 'Width': spec[1],
                             'Depth': 0.035, 'Kind': spec[0], 'Style': 'LCD'})
        else:
            fn, w, hh, _ = spec
            pw, ph = (112, max(16, int(round(112 * hh / w)))) if hh >= w else (160, max(16, int(round(160 * hh / w))))
            rect = atlas.paint(pw, ph, fn)
            m.rect_tex(c + fr.N * 0.004, fr.N, fr.U, w, hh, rect)
            m.frame_rect(c, fr.N, fr.U, w, hh, 0.004, 0.006, sw['bezel'])
    # -- two swivel seats at their eyes, a hand controller for the navigator and a map case on the right wall
    for eye in (NEYE, WEYE):
        swivel_seat(m, atlas, sw, float(eye[0]), float(eye[2]))
    # equipment racks on the rear wall above the console: painted units, two rows (the crew's shelves of radios, navigation and recorder units)
    labs = [(ZH('\u901a\u4fe1\u673a', 'RADIO'), -0.55), (ZH('\u5bfc\u822a', 'NAV'), -0.19), (ZH('\u7535\u6e90', 'POWER'), 0.19), (ZH('\u8bb0\u5f55', 'REC'), 0.55)]
    for lab, xr in labs:
        tile = atlas.paint(96, 54, p_rack(lab))
        m.box([xr, Yb(65.5), z_w + 0.004 + 0.035], (X, Y, Z), (0.16, 0.085, 0.035), sw['dgrey'], front=(2, 1, tile, X, Y))
    for lab, xr in ((ZH('\u5e72\u6270', 'JAM'), -0.25), (ZH('\u96f7\u8fbe', 'RADAR'), 0.25)):
        tile = atlas.paint(96, 54, p_rack(lab))
        m.box([xr, Yb(70.0), z_w + 0.004 + 0.035], (X, Y, Z), (0.20, 0.075, 0.035), sw['dgrey'], front=(2, 1, tile, X, Y))
    gax = (X, unit([0, 1, -0.3]), unit([0, 0.3, 1]))
    m.box([0.62, RPB + 0.0, zb_ + 0.10], (X, Y, Z), (0.045, 0.012, 0.045), sw['dgrey'])
    m.box([0.62, RPB + 0.050, zb_ + 0.10], gax, (0.007, 0.040, 0.007), sw['metal'])
    m.box([0.62, RPB + 0.108, zb_ + 0.088], gax, (0.020, 0.036, 0.028), sw['black'])
    rk = atlas.paint(64, 48, p_rack(ZH('电子', 'ECM')))
    m.box([-0.945, Yb(52.5), Zb(-252.0)], (X, Y, Z), (0.045, 0.10, 0.18), sw['dgrey'], front=(0, 1, rk, -Z, Y))
    chart = atlas.paint(160, 100, p_chart)
    m.box([0.945, Yb(52.5), Zb(-252.0)], (X, Y, Z), (0.045, 0.09, 0.20), sw['dgrey'], front=(0, -1, chart, Z, Y))


# ---------------------------------------------------------------- build
def build(atlas):
    m = Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lining=LINING,
                                                lining_d=LINING_D, ceil=CEIL, floor=FLOORC, frame=FRAME, cushion=CUSHION, para=(70, 76, 64),
                                                yellow=YELLOW, red=RED, metal=METAL, desk=DESK, desktop=DESKTOP,
                                                bezel=(30, 31, 32)).items()}
    gauges, displays = [], []
    sticks = flight_deck(m, atlas, sw, gauges, displays)
    rear_crew(m, atlas, sw, gauges, displays)
    return m, sticks, gauges, displays


# ---------------------------------------------------------------- the airframe
def r5v(v):
    return [round(float(x), 5) + 0.0 for x in v]


def install(ac, mesh, sticks, gauges, displays):
    pm = mesh.poly_mesh()
    cb = next((b for b in ac.bones if b['name'] == COCKPIT_BONE), None)
    if cb is None:
        hull = ac.bone('hull')
        at = next(i for i, b in enumerate(ac.bones) if b['name'] == 'wreck_fuselage_0__hull') + 1
        cb = {'name': COCKPIT_BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        ac.bones.insert(at, cb)
    cb['poly_mesh'] = pm
    rig = ac.asset.get('AircraftRig')
    (stick_c, foot_c), (stick_p, foot_p) = sticks
    px_c = r5v(np.array(foot_c) * np.array([16.0, 16.0, -16.0]))
    px_p = r5v(np.array(foot_p) * np.array([16.0, 16.0, -16.0]))
    for b in ac.bones:
        if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
            b['pivot'] = px_c
            if b['name'].endswith('__control_stick_pitch'):
                b['poly_mesh'] = stick_c.poly_mesh()
    for sf in (rig or {}).get('Surfaces', []):
        if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
            sf['Pivot'] = px_c
    # the co-pilot's own wheel: a second roll / pitch pair on the same controls (the bones and their rig surfaces)
    names = {b['name'] for b in ac.bones}
    if 'control_stick_roll_2' not in names:
        i0 = next(i for i, b in enumerate(ac.bones) if b['name'].endswith('__control_stick_pitch'))
        roll = {'name': 'control_stick_roll_2', 'parent': 'hull', 'pivot': px_p}
        pitch = {'name': 'control_stick_pitch_2', 'parent': 'control_stick_roll_2', 'pivot': px_p}
        mesh_b = {'name': STICK_BONE + '_2', 'parent': 'control_stick_pitch_2', 'pivot': px_p}
        ac.bones[i0 + 1:i0 + 1] = [roll, pitch, mesh_b]
        if rig:
            extra = []
            for sf in rig['Surfaces']:
                if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
                    c = json.loads(json.dumps(sf))
                    c['Bone'] += '_2'
                    if c.get('Parent') == 'control_stick_roll':
                        c['Parent'] = 'control_stick_roll_2'
                    c['Pivot'] = px_p
                    extra.append(c)
            rig['Surfaces'] += extra
    for b in ac.bones:
        if b['name'] in ('control_stick_roll_2', 'control_stick_pitch_2'):
            b['pivot'] = px_p
        if b['name'] == STICK_BONE + '_2':
            b['pivot'] = px_p
            b['poly_mesh'] = stick_p.poly_mesh()
    a = ac.asset
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    a['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    a.pop('HeadUpDisplay', None)               # the H-6K has no head-up display
    return pm


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    atlas = Atlas(VID)
    mesh, sticks, gauges, displays = build(atlas)
    pm = install(ac, mesh, sticks, gauges, displays)
    print(f'{VID}: new cockpits {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} displays')
    if not dry:
        write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
