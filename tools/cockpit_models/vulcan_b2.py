#!/usr/bin/env python3
"""Avro Vulcan B.2 flight deck and rear crew compartment, built by hand for this model (as vautour_iin.py, mirage_4a.py), from the
real layout.

Measured off the model (the faces of Avro Vulcan.mtb in checks/orig and the source elements, vulcan/cutlist.txt; vehicle blocks,
1 block = 1 m; x to the left, y up, z forward):
  * flight deck: the pilots' floor slab tops out at y 4.123 between the bulkhead (z 11.155) and the solid nose block (z 12.90, its
    rear face); the fuselage walls stand at |x| 0.82 .. 0.87 above y 4.41 (the shoulder caps top at y 4.947) and 1.07 .. 1.16 below;
    the captain's eye (0.4365, 5.335, 11.5915), the co-pilot's (-0.4365, ...); the canopy is canopy_vulcan_b2.py's;
  * rear crew compartment: floor y 3.492 between z 8.49 (its rear wall, the solid block behind) and the bulkhead at z 11.155, under
    the model's dorsal hump (exterior, stays); the crew face aft: radar navigator (0.485, 4.244, 9.676), air electronics officer
    (0.024, ...) and navigator plotter (-0.412, ...), all three eyes at the model's own attachments;
  * the old interior (dash and console blocks, both seat groups, partition) is cut out by canopy_vulcan_b2.py's boxes.
Built:
  * captain (Seat 0, left) and co-pilot (Seat 1, right), side by side on Martin-Baker Mk 4 ejection seats under the small canopy: a
    flat instrument panel with the basic six each (MRI attitude, ASI, altimeter, beam compass heading, Mach, vertical speed, g, turn
    and slip, clock / radio altimeter) and, between the pilots, the engine panel with the four Olympus groups (rpm and JPT per
    engine, fuel, caution lights) and the throttles on the centre console; fighter-style control sticks (not yokes), rudder pedals,
    undercarriage, brake, airbrake and radio panels, a small standby compass on the glareshield; no HUD and no bombsight (the B.2
    carried none);
  * rear crew (Seats 2, 3, 4), three plain swivel seats facing aft at one long desk on the rear bulkhead, windowless: the radar
    navigator's H2S scope (FlightDisplays RADAR, Seat 2) with its control panels, the navigator plotter's chart table, painted
    navigation computer and repeaters, the air electronics officer's ECM panels and RWR; a lined compartment with a doorway
    forward over the flight deck's step.
Seats: Martin-Baker Mk 4 (a face-blind handle above the headbox, a lower handle between the knees) for the pilots; the rear crew
had no ejection seats.
Instruments the game draws (CockpitGauges, FlightDisplays) are live; the canopy glass is canopy_vulcan_b2.py's. No HeadUpDisplay.

Imports only cockpit_lib and free_atlas (the building blocks below are inlined, as vautour_iin.py's).
Run on the airframe's originals (vulcan/run_vulcan_cockpit.ps1 restores them), after canopy_vulcan_b2.py.
Usage: python3 tools/cockpit_models/vulcan_b2.py [--dry-run]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import free_atlas as FA   # noqa: E402
import canopy_vulcan_b2 as CM   # noqa: E402
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


VID = 'vulcan_b2'
COCKPIT_BONE = 'wreck_fuselage_1__cockpit'      # (an empty bone of the airframe: the cockpit goes with the forward fuselage section)
STICK_BONE = 'wreck_fuselage_1__control_stick_pitch'

# colours: RAF V-force flight deck - black instrument panels, pale grey-green linings, black seat cushions
PANEL = (34, 36, 38)
PANEL_D = (22, 24, 26)
BLACK = (18, 19, 21)
DGREY = (46, 48, 50)
MGREY = (92, 96, 98)
LINING = (112, 122, 114)       # pale grey-green side linings
LINING_D = (92, 100, 94)
CEIL = (166, 170, 162)
FLOORC = (44, 46, 46)
FRAME = (58, 62, 64)           # seat structure
CUSHION = (46, 50, 56)
PARA = (74, 82, 66)            # parachute pack green
HEADPAD = (78, 82, 80)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
DESK = (58, 62, 60)
DESKTOP = (74, 80, 76)
CHART = (214, 210, 190)

# ---------------------------------------------------------------- the measured flight deck
Z_BK = 11.198                  # the bulkhead's front face (the canopy's rear bow stands against it at 11.20; the hump's front face is at 11.155)
Z_BK_B = 11.162                # its rear face (3.6 cm thick)
Z_FW = 12.885                  # the front wall (the nose block's rear face stands at 12.90)
F_Y = 4.130                    # the pilots' floor plate (the model's slab tops out at 4.123)
R_Y = 3.499                    # the rear floor plate (the model's floor top is 3.492)
XW_U, XW_L = 0.80, 1.04        # the lining above / below the ledge
Y_LEDGE = 4.41
Y_SILL = 4.890                 # the lining's top (under the nose-side shoulders' top, y 4.898; the canopy's rails start at 4.905)
LEAN = math.radians(17.8)      # the flight panel's lean
R_P = np.array([-1.0, 0, 0])                                   # the pilot's right
U_P = np.array([0, math.cos(LEAN), math.sin(LEAN)])
N_P = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward the pilot
PANEL_BOT, PANEL_TOP = (4.36, 12.40), (4.89, 12.40 + (4.89 - 4.36) * math.tan(LEAN))      # (y, z) of the panel's lower and upper edge
PANEL_X = 0.795
LEAN_R = math.radians(14.0)    # the rear desk's panel face leans back this much
R_R = np.array([1.0, 0, 0])                                    # the rear crew's right (they face aft)
U_R = np.array([0, math.cos(LEAN_R), -math.sin(LEAN_R)])
N_R = np.array([0, math.sin(LEAN_R), math.cos(LEAN_R)])        # toward the rear crew
DESK_TOP_Y = 3.98
DESK_Z0, DESK_Z1 = 8.50, 9.14  # the desk: from the rear wall to its front edge
RPANEL_BOT, RPANEL_TOP = (3.98, 8.50 + (4.86 - 3.98) * math.tan(LEAN_R)), (4.86, 8.50)    # (y, z) of the rear panel face's edges
REAR_WALL = 8.497              # lining plate in front of the solid block's face (8.488)
ROOF = None                    # set in main(): the hull's lowest face above the compartment, sampled


def roof_low(x, z, y0=4.6):
    """the lowest hull face above y0 at (x, z) (the hump's underside), or None"""
    a, b, c = ROOF
    d = (b[:, 2] - c[:, 2]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 2] - c[:, 2])
    ok = np.abs(d) > 1e-12
    ds = np.where(ok, d, 1)
    l1 = ((b[:, 2] - c[:, 2]) * (x - c[:, 0]) + (c[:, 0] - b[:, 0]) * (z - c[:, 2])) / ds
    l2 = ((c[:, 2] - a[:, 2]) * (x - c[:, 0]) + (a[:, 0] - c[:, 0]) * (z - c[:, 2])) / ds
    l3 = 1 - l1 - l2
    hit = ok & (l1 >= -1e-9) & (l2 >= -1e-9) & (l3 >= -1e-9)
    if not hit.any():
        return None
    y = l1[hit] * a[hit, 1] + l2[hit] * b[hit, 1] + l3[hit] * c[hit, 1]
    y = y[y > y0]
    return float(y.min()) if len(y) else None


def hull_top(x, z):
    """the highest hull face at (x, z) over the rear compartment (the hump's outer skin), or None"""
    a, b, c = ROOF
    d = (b[:, 2] - c[:, 2]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 2] - c[:, 2])
    ok = np.abs(d) > 1e-12
    ds = np.where(ok, d, 1)
    l1 = ((b[:, 2] - c[:, 2]) * (x - c[:, 0]) + (c[:, 0] - b[:, 0]) * (z - c[:, 2])) / ds
    l2 = ((c[:, 2] - a[:, 2]) * (x - c[:, 0]) + (a[:, 0] - c[:, 0]) * (z - c[:, 2])) / ds
    l3 = 1 - l1 - l2
    hit = ok & (l1 >= -1e-9) & (l2 >= -1e-9) & (l3 >= -1e-9)
    if not hit.any():
        return None
    return float((l1[hit] * a[hit, 1] + l2[hit] * b[hit, 1] + l3[hit] * c[hit, 1]).max())


# ---------------------------------------------------------------- painted faces

def p_wall(colour, line, seed):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 4, seed=seed)
        d.line([0, H * .5, S, H * .5], fill=tuple(line) + (255,), width=3)
    return fn


def p_ceiling(d, im):
    S, H = im.width, im.height
    noisy(d, im, CEIL, 3, seed=77)
    for k in range(1, 4):
        d.line([S * k / 4, 0, S * k / 4, H], fill=(150, 154, 146, 255), width=3)


def p_panel_face(layout_boxes, cols, W_, H_, x0, x1, yb, yt, label_lines):
    """the flight panel's face: black with the dials' backing discs, group outlines and the engine columns' captions"""
    def fn(d, im):
        SS = Atlas.SS
        noisy(d, im, PANEL, 4, seed=5)

        def to_px(x, y):
            return (x1 - x) / (x1 - x0) * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        px_m = W_ * SS / (x1 - x0)
        for (xa, ya, xb, yb2, title) in label_lines:             # group outlines
            ax, ay = to_px(xa, ya); bx, by = to_px(xb, yb2)
            d.rectangle([min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)], outline=(90, 94, 96, 255), width=SS * 2)
            if title:
                tx, ty = (ax + bx) / 2, min(ay, by) + SS * 5
                text(d, (tx, ty), title, SS * 7, fill=(170, 174, 168), bold=True)
        for kind, x, y, w, h in layout_boxes:
            cx, cy = to_px(x, y)
            if kind == 'disc':
                r = (w / 2 * (1 + RIM) + 0.004) * px_m
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(14, 16, 18, 255))
            elif kind == 'rect':
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([cx - sx, cy - sy, cx + sx, cy + sy], fill=(14, 16, 18, 255))
        for k in range(14):                                        # screws along the edges
            for yy in (yb + 0.012, yt - 0.014):
                sx_, sy_ = to_px(x0 + 0.02 + (x1 - x0 - 0.04) * k / 13, yy)
                r = SS * 2
                d.ellipse([sx_ - r, sy_ - r, sx_ + r, sy_ + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
    return fn


def p_cols(nums, small=''):
    """a plate with a number over each of four columns"""
    def fn(d, im):
        d.rectangle([0, 0, im.width, im.height], fill=(16, 18, 20, 255))
        for k, s in enumerate(nums):
            text(d, (im.width * (k + 0.5) / len(nums), im.height * 0.5), s, im.height * 0.7)
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


def p_undercarriage(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.08), 'UNDERCARRIAGE', Hh * 0.075)
    for k, lab in enumerate(['NOSE', 'PORT', 'STBD']):
        x = S * (0.2 + 0.3 * k)
        lamp(d, [x - 20, Hh * .20, x + 20, Hh * .38], (60, 160, 70), None, lit=True)
        text(d, (x, Hh * .46), lab, Hh * .06)
    d.rectangle([S * .46, Hh * .56, S * .54, Hh * .80], fill=(190, 190, 190, 255))
    text(d, (S / 2, Hh * .92), 'UP      DOWN', Hh * .06)


def p_brakes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.12), 'BRAKES', Hh * 0.14)
    d.ellipse([S * .12, Hh * .30, S * .40, Hh * .85], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
    text(d, (S * .26, Hh * .58), 'PARK', Hh * .13)
    d.rectangle([S * .55, Hh * .35, S * .90, Hh * .75], fill=(190, 40, 30, 255))
    text(d, (S * .725, Hh * .55), 'DRAG', Hh * .13, fill=(240, 240, 230))
    text(d, (S * .725, Hh * .72), 'CHUTE', Hh * .12, fill=(240, 240, 230))


def p_airbrake(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.08), 'FLYING CONTROLS', Hh * 0.07)
    for k, lab in enumerate(['AIR BRAKE', 'ARTIFICIAL FEEL', 'MACH TRIM']):
        y = Hh * (0.28 + 0.24 * k)
        d.rectangle([S * .12, y - 14, S * .34, y + 14], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=2)
        d.rectangle([S * .20, y - 12, S * .26, y + 2], fill=(190, 190, 190, 255))
        text(d, (S * .66, y), lab, Hh * .06)


def p_radio(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.14), 'RADIO / IFF', Hh * 0.14)
    for k, lab in enumerate(['VHF', 'UHF', 'IFF']):
        x = S * (0.2 + 0.3 * k)
        d.ellipse([x - 22, Hh * .38, x + 22, Hh * .38 + 44], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
        text(d, (x, Hh * .86), lab, Hh * .13)


def p_pedestal(d, im):
    """the centre console's top seen from above (tile top = forward): the throttle quadrant, with four slots"""
    S, Hh = im.width, im.height
    noisy(d, im, PANEL, 4, seed=21)
    d.rectangle([S * .06, Hh * .06, S * .94, Hh * .66], fill=(14, 16, 18, 255))
    for k in range(4):
        x = S * (0.19 + 0.206 * k)
        d.rectangle([x - 6, Hh * .10, x + 6, Hh * .62], fill=(4, 4, 4, 255))
        text(d, (x, Hh * .75), str(k + 1), Hh * .12)
    text(d, (S * .5, Hh * .91), 'THROTTLES', Hh * .09)


def p_h2s(d, im):
    """radar controls beside the H2S scope"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.08), 'H2S  MK 9', Hh * 0.08)
    for k, lab in enumerate(['RANGE', 'GAIN', 'TILT', 'BRILL']):
        y = Hh * (0.24 + 0.17 * k)
        d.ellipse([S * .12, y - 20, S * .12 + 40, y + 20], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
        d.line([S * .12 + 20, y, S * .12 + 20, y - 18], fill=(230, 230, 230, 255), width=3)
        text(d, (S * .66, y), lab, Hh * .07)
    lamp(d, [S * .15, Hh * .92, S * .30, Hh * .98], (60, 160, 70), None, lit=True)
    lamp(d, [S * .40, Hh * .92, S * .55, Hh * .98], (200, 160, 40), None, lit=False)


def p_navcomp(d, im):
    """the navigator plotter's navigation / bombing computer display, painted (a moving-map style plan with a track line)"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(16, 18, 18, 255))
    d.rectangle([S * .04, Hh * .12, S * .96, Hh * .80], fill=(10, 36, 22, 255), outline=(70, 80, 74, 255), width=4)
    for k in range(1, 6):
        d.line([S * (.04 + .92 * k / 6), Hh * .12, S * (.04 + .92 * k / 6), Hh * .80], fill=(30, 100, 60, 255), width=2)
    for k in range(1, 4):
        d.line([S * .04, Hh * (.12 + .68 * k / 4), S * .96, Hh * (.12 + .68 * k / 4)], fill=(30, 100, 60, 255), width=2)
    d.line([S * .12, Hh * .72, S * .40, Hh * .50, S * .66, Hh * .40, S * .88, Hh * .20], fill=(120, 240, 150, 255), width=5)
    d.polygon([(S * .62, Hh * .46), (S * .70, Hh * .46), (S * .66, Hh * .34)], fill=(230, 240, 230, 255))
    text(d, (S * .5, Hh * .05), 'NAVIGATION / BOMBING COMPUTER', Hh * .075)
    for k, lab in enumerate(['TRACK', 'GS', 'D/R', 'FIX']):
        text(d, (S * (0.14 + 0.24 * k), Hh * .90), lab, Hh * .07)


def p_ecm(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.10), 'ECM CONTROL', Hh * 0.10)
    labs = ['JAM 1', 'JAM 2', 'JAM 3', 'JAM 4', 'RCVR', 'STBY']
    for k, lab in enumerate(labs):
        x = S * (0.11 + 0.15 * k)
        lamp(d, [x - 18, Hh * .24, x + 18, Hh * .46], (200, 120, 30) if k < 4 else (60, 160, 70), None, lit=(k in (4,)))
        d.rectangle([x - 5, Hh * .54, x + 5, Hh * .74], fill=(190, 190, 190, 255))
        text(d, (x, Hh * .88), lab, Hh * .09)


def p_window(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.12), 'WINDOW  DISPENSER', Hh * 0.12)
    d.rectangle([S * .10, Hh * .30, S * .55, Hh * .62], fill=(10, 10, 10, 255), outline=(150, 150, 150, 255), width=3)
    text(d, (S * .325, Hh * .46), '0 4 8', Hh * .2, fill=(240, 200, 60))
    for k in range(3):
        x = S * (0.68 + 0.12 * k)
        d.ellipse([x - 12, Hh * .34, x + 12, Hh * .34 + 24], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
    text(d, (S * .5, Hh * .84), 'CHAFF     FLARE     RWR', Hh * .1)


def p_radar_ctl(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 24, 255))
    text(d, (S / 2, Hh * 0.06), 'RADAR', S * 0.2)
    for k, lab in enumerate(['SCAN', 'MODE', 'SECTOR', 'FIX']):
        y = Hh * (0.20 + 0.20 * k)
        d.ellipse([S * .12, y - 14, S * .12 + 28, y + 14], fill=(30, 30, 30, 255), outline=(150, 150, 150, 255), width=3)
        d.line([S * .12 + 14, y, S * .12 + 22, y - 12], fill=(230, 230, 230, 255), width=3)
        text(d, (S * .72, y), lab, S * .16)


def p_chart(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, CHART, 4, seed=31)
    for k in range(1, 8):
        d.line([S * k / 8, 0, S * k / 8, Hh], fill=(170, 176, 190, 255), width=2)
    for k in range(1, 6):
        d.line([0, Hh * k / 6, S, Hh * k / 6], fill=(170, 176, 190, 255), width=2)
    d.line([S * .1, Hh * .85, S * .45, Hh * .45, S * .9, Hh * .2], fill=(180, 40, 40, 255), width=5)
    d.ellipse([S * .43, Hh * .42, S * .47, Hh * .48], outline=(30, 30, 160, 255), width=4)
    text(d, (S * .5, Hh * .08), 'CHART', Hh * .1, fill=(80, 80, 90))


def p_headbox(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, HEADPAD, 5, seed=60)
    d.rectangle([S * .1, Hh * .1, S * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
    text(d, (S * .5, Hh * .38), 'MARTIN-BAKER', Hh * .13, fill=(30, 30, 30), bold=True)
    text(d, (S * .5, Hh * .62), 'Mk 4', Hh * .2, fill=(30, 30, 30), bold=True)


def p_back(d, im):
    S, Hh = im.width, im.height
    noisy(d, im, PARA, 5, seed=61)
    for k in range(1, 4):
        d.line([0, Hh * k / 4, S, Hh * k / 4], fill=(56, 62, 50, 255), width=3)
    text(d, (S * .5, Hh * .3), 'EJECT', Hh * .16, fill=(220, 210, 160), bold=True)
    for x in (S * .12, S * .88):
        for y in (Hh * .1, Hh * .9):
            d.ellipse([x - 5, y - 5, x + 5, y + 5], fill=(120, 124, 120, 255))


def p_compass(d, im):
    S = im.width
    d.rectangle([0, 0, S, im.height], fill=(20, 22, 24, 255))
    d.ellipse([S * .06, S * .06, S * .94, S * .94], fill=(226, 224, 210, 255), outline=(60, 60, 60, 255), width=4)
    for k in range(36):
        a = math.radians(k * 10)
        r0, r1 = S * (.36 if k % 9 else .32), S * .43
        d.line([S / 2 + r0 * math.sin(a), S / 2 - r0 * math.cos(a), S / 2 + r1 * math.sin(a), S / 2 - r1 * math.cos(a)], fill=(20, 20, 20, 255), width=2)
    d.line([S / 2, S * .22, S / 2, S * .78], fill=(200, 40, 30, 255), width=4)
    text(d, (S / 2, S * .17), 'N', S * .12, fill=(20, 20, 20), bold=True)


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

X_CAPT, X_COPI = 0.4365, -0.4365


def basic_six(x0, seat, copilot):
    """the basic flight instruments of one pilot (his eye's x): (name, kind, x, y, dia)"""
    out = [('adi', 'ATTITUDE', x0, 4.80, 0.115), ('asi', 'AIRSPEED', x0 + 0.185, 4.80, 0.095), ('alt', 'ALTIMETER', x0 - 0.185, 4.80, 0.095),
           ('hdg', 'HEADING', x0, 4.665, 0.100), ('mach', 'MACH', x0 + 0.185, 4.665, 0.075), ('vvi', 'VVI', x0 - 0.185, 4.665, 0.075),
           ('g', 'G_METER', x0 + 0.185, 4.545, 0.060), ('turn', 'TURN_SLIP', x0, 4.545, 0.070),
           ('aux', 'RADAR_ALT' if copilot else 'CLOCK', x0 - 0.185, 4.545, 0.060)]
    return [(f'{n}{seat}', k, x, y, dd, seat) for n, k, x, y, dd in out]


ENG_X = [0.115, 0.038, -0.038, -0.115]            # engines 1 .. 4, from the left


def engine_group():
    out = []
    for k, x in enumerate(ENG_X):
        out.append((f'rpm{k + 1}', 'ENGINE_RPM', x, 4.775, 0.060, 0))
        out.append((f'jpt{k + 1}', 'EGT', x, 4.685, 0.060, 0))
        out.append((f'fuel{k + 1}', 'FUEL_QTY', x, 4.595, 0.050, 0))
    return out


# ---------------------------------------------------------------- the flight deck

def wall_quads(m, sw, side, y_lo, y_hi, x, z0, z1, normal_x, rect):
    m.flat([[side * x, y_lo, z0], [side * x, y_lo, z1], [side * x, y_hi, z1], [side * x, y_hi, z0]], rect, normal=[normal_x * side, 0, 0])


def flight_deck(m, atlas, sw, gauges, displays):
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    lin = sw['lining']; lin_d = sw['lining_d']
    # -- floor, side linings, ledges, sill caps
    m.flat([[-XW_L, F_Y + 0.002, Z_BK], [XW_L, F_Y + 0.002, Z_BK], [XW_L, F_Y + 0.002, Z_FW], [-XW_L, F_Y + 0.002, Z_FW]], sw['floor'], normal=[0, 1, 0])
    for s in (1, -1):
        wall_quads(m, sw, s, F_Y, Y_LEDGE, XW_L, Z_BK, Z_FW, -1, lin_d)
        m.flat([[s * XW_U, Y_LEDGE, Z_BK], [s * XW_L, Y_LEDGE, Z_BK], [s * XW_L, Y_LEDGE, Z_FW], [s * XW_U, Y_LEDGE, Z_FW]], lin_d, normal=[0, 1, 0])
        wall_quads(m, sw, s, Y_LEDGE, Y_SILL, XW_U, Z_BK, Z_FW, -1, lin)
        m.flat([[s * XW_U, Y_SILL, Z_BK], [s * 0.85, Y_SILL, Z_BK], [s * 0.85, Y_SILL, Z_FW], [s * XW_U, Y_SILL, Z_FW]], sw['panel_d'], normal=[0, 1, 0])
    # -- the bulkhead's front face (toward the pilots) with the doorway; the rear face is the rear crew's. Its top follows the canopy's
    # inner outline at the rear bow and stays under the hump's front face
    xs_b = [0.85, 0.80, 0.75, 0.69, 0.60, 0.50, 0.40, 0.30, 0.20, 0.10, 0.0]
    cin = lambda x: CM.section_y(Z_BK + 0.002, min(x, 0.85)) - 0.02      # noqa: E731  (the canopy skin's inner face at the rear bow)
    topb = [min(cin(x), (hull_top(x, Z_BK - 0.02) or 5.2) - 0.012) for x in xs_b]
    pieces = [[(0.20, F_Y), (XW_U, F_Y), (XW_U, 4.80), (0.20, 4.80)], [(XW_U, F_Y), (XW_L, F_Y), (XW_L, Y_LEDGE), (XW_U, Y_LEDGE)],
              [(0.0, 4.80), (XW_U, 4.80), (XW_U, Y_SILL), (0.0, Y_SILL)]]
    pieces += [[(xa, Y_SILL), (xb, Y_SILL), (xb, tb), (xa, ta)] for xa, xb, ta, tb in zip(xs_b[:-1], xs_b[1:], topb[:-1], topb[1:])]
    pieces += [[(0.85, Y_SILL), (0.80, Y_SILL), (0.80, 4.80 + 0.0), (0.85, 4.80)]] if False else []
    for s in (1, -1):
        for pc in pieces:
            m.flat([[s * x, y, Z_BK] for x, y in pc], sw['lining'], normal=[0, 0, 1])
            m.flat([[s * x, y, Z_BK_B] for x, y in pc], sw['lining'], normal=[0, 0, -1])
        for (xa, ta), (xb, tb) in zip(zip(xs_b[:-1], topb[:-1]), zip(xs_b[1:], topb[1:])):     # the slab's top edge
            m.flat([[s * xa, ta, Z_BK], [s * xb, tb, Z_BK], [s * xb, tb, Z_BK_B], [s * xa, ta, Z_BK_B]], sw['panel_d'], normal=[0, 1, 0])
    for s in (1, -1):                                   # the doorway's jambs (3.6 cm deep) and its sill
        m.flat([[s * 0.20, F_Y, Z_BK], [s * 0.20, 4.80, Z_BK], [s * 0.20, 4.80, Z_BK_B], [s * 0.20, F_Y, Z_BK_B]], sw['panel_d'], normal=[-s, 0, 0])
    m.flat([[-0.20, 4.80, Z_BK], [0.20, 4.80, Z_BK], [0.20, 4.80, Z_BK_B], [-0.20, 4.80, Z_BK_B]], sw['panel_d'], normal=[0, -1, 0])
    m.flat([[-0.20, F_Y + 0.002, Z_BK], [0.20, F_Y + 0.002, Z_BK], [0.20, F_Y + 0.002, Z_BK_B], [-0.20, F_Y + 0.002, Z_BK_B]], sw['floor'], normal=[0, 1, 0])
    # -- the dash: the flat panel, the black glareshield above it, the under-plate and the front wall
    cen = np.array([0.0, 0.5 * (PANEL_BOT[0] + PANEL_TOP[0]), 0.5 * (PANEL_BOT[1] + PANEL_TOP[1])])
    fp = Facet(cen, R_P, U_P, N_P)
    boxes, items = [], []
    lay = basic_six(X_CAPT, 0, False) + basic_six(X_COPI, 1, True) + engine_group()
    for name, kind, x, y, dia, seat in lay:
        boxes.append(('disc', x, y, dia, dia))
    paint_rects = [('uc', p_undercarriage, 0.735, 4.775, 0.125, 0.150), ('brk', p_brakes, 0.735, 4.600, 0.125, 0.090),
                   ('fc', p_airbrake, -0.735, 4.775, 0.125, 0.150), ('rad', p_radio, -0.735, 4.600, 0.125, 0.090)]
    for name, fn, x, y, w, h in paint_rects:
        boxes.append(('rect', x, y, w, h))
    boxes.append(('rect', 0.0, 4.480, 0.31, 0.06))                    # the caution panel's seat
    groups = [(0.17, 4.885, 0.80, 4.37, 'CAPTAIN'), (-0.80, 4.885, -0.17, 4.37, 'CO-PILOT'), (-0.162, 4.885, 0.162, 4.37, 'ENGINES')]
    W_ = int(round(2 * PANEL_X * 560)); Hp = int(round((PANEL_TOP[0] - PANEL_BOT[0]) / math.cos(LEAN) * 560))
    ptile = atlas.paint(W_, Hp, p_panel_face(boxes, None, W_, Hp, -PANEL_X, PANEL_X, PANEL_BOT[0], PANEL_TOP[0], groups))
    # the panel face: one quad, uv across x (the pilot's right = -x is the tile's right) and up the lean
    a = lambda x, y: fp.at(-x, y)          # noqa: E731  (right = -x)
    pb = [a(PANEL_X, PANEL_BOT[0]), a(-PANEL_X, PANEL_BOT[0]), a(-PANEL_X, PANEL_TOP[0]), a(PANEL_X, PANEL_TOP[0])]
    m.quad_tex(pb, ptile, normal=fp.N)
    # the glareshield: a black plate from the panel's lip forward to just short of the windscreen's base bar, and the under-plate
    yt, zt = PANEL_TOP[0], PANEL_TOP[1]
    m.flat([[-PANEL_X, yt, zt], [PANEL_X, yt, zt], [PANEL_X, yt, Z_FW], [-PANEL_X, yt, Z_FW]], sw['black'], normal=[0, 1, 0])
    m.flat([[-PANEL_X, PANEL_BOT[0], PANEL_BOT[1]], [PANEL_X, PANEL_BOT[0], PANEL_BOT[1]], [PANEL_X, PANEL_BOT[0], Z_FW], [-PANEL_X, PANEL_BOT[0], Z_FW]], sw['panel_d'], normal=[0, -1, 0])
    m.flat([[-PANEL_X, F_Y + 0.002, Z_FW], [PANEL_X, F_Y + 0.002, Z_FW], [PANEL_X, PANEL_BOT[0], Z_FW], [-PANEL_X, PANEL_BOT[0], Z_FW]], sw['panel_d'], normal=[0, 0, -1])
    for s in (1, -1):                                    # the dash's ends
        m.flat([[s * PANEL_X, PANEL_BOT[0], PANEL_BOT[1]], [s * PANEL_X, PANEL_BOT[0], Z_FW], [s * PANEL_X, yt, Z_FW], [s * PANEL_X, yt, zt]], sw['panel_d'], normal=[s, 0, 0])
    # the lip between the panel's top edge and the glareshield (closed above the panel)
    m.flat([[-PANEL_X, yt, zt], [PANEL_X, yt, zt], [PANEL_X, yt - 0.0001, zt], [-PANEL_X, yt - 0.0001, zt]], sw['black'], normal=[0, 1, 0]) if False else None
    # -- the dials (game) and the painted panels on the face
    cols, lamps = LAMPS
    for name, kind, x, y, dia, seat in lay:
        c = fp.at(-x, y)
        gauges.append({'Seat': seat, 'Kind': kind, 'Center': r5(c + fp.N * GAUGE_LIFT), 'Normal': r5(fp.N), 'Up': r5(fp.U),
                       'Diameter': dia, 'Depth': 0.03})
    painters = {n: f for n, f, *_ in paint_rects}
    for name, fn, x, y, w, h in paint_rects:
        c = fp.at(-x, y)
        pw, ph = (112, max(16, int(round(112 * h / w)))) if h >= w else (160, max(16, int(round(160 * h / w))))
        rect = atlas.paint(pw, ph, fn)
        m.rect_tex(c + fp.N * 0.004, fp.N, fp.U, w, h, rect)
        m.frame_rect(c, fp.N, fp.U, w, h, 0.004, 0.006, sw['bezel'])
    c = fp.at(0.0, 4.480)
    gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + fp.N * 0.004), 'Normal': r5(fp.N), 'Up': r5(fp.U), 'Diameter': 0.31,
                   'Width': 0.31, 'Height': 0.06, 'Depth': 0.02, 'Columns': cols, 'Lamps': lamps})
    m.frame_rect(c, fp.N, fp.U, 0.31, 0.06, 0.005, 0.008, sw['bezel'])
    c = fp.at(0.0, 4.865)                                   # the engine numbers over the columns
    plate = atlas.paint(312, 28, p_cols(['1', '2', '3', '4']))
    m.rect_tex(c + fp.N * 0.003, fp.N, fp.U, 0.312, 0.028, plate)
    for x, lab in ((X_CAPT, 'CAPTAIN  FLIGHT  INSTRUMENTS'), (X_COPI, 'CO-PILOT  FLIGHT  INSTRUMENTS')):
        c = fp.at(-x, 4.425)
        plate = atlas.paint(380, 16, p_label_plate(lab))
        m.rect_tex(c + fp.N * 0.003, fp.N, fp.U, 0.38, 0.016, plate)
    c = fp.at(0.0, 4.412)
    plate = atlas.paint(220, 14, p_label_plate('OLYMPUS  ENGINES'))
    m.rect_tex(c + fp.N * 0.003, fp.N, fp.U, 0.22, 0.014, plate)
    # -- the standby compass on the glareshield's centre (the Vulcan carried a compass here instead of a sight)
    comp = atlas.paint(64, 64, p_compass)
    cx_, cz_ = 0.0, 12.70
    m.box([cx_, yt + 0.022, cz_], (X, Y, Z), (0.045, 0.022, 0.045), sw['dgrey'])
    m.disc([cx_, yt + 0.0445, cz_], [0, 1, 0], [0, 0, 1], 0.040, comp, seg=24)
    m.ring([cx_, yt + 0.044, cz_], [0, 1, 0], [0, 0, 1], 0.040, 0.047, 0.005, sw['dgrey'], seg=24)
    # -- side consoles beside the pilots (switch panels), the centre pedestal with the four throttles
    cy = 4.52
    for s in (1, -1):
        con = atlas.paint(180, 40, rotated(p_console(PANEL, 20 + s, [0.11, 0.08, 0.13, 0.07, 0.12, 0.09, 0.10, 0.08] if s > 0
                                                       else [0.09, 0.12, 0.08, 0.11, 0.10, 0.09, 0.13, 0.08])))
        xi, xo = s * 0.63, s * (XW_U - 0.004)
        zb, zf = 11.64, 12.36
        m.face([[xi, cy, zb], [xo, cy, zb], [xo, cy, zf], [xi, cy, zf]], [m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1), m.uv(con, 0, 0)], normal=[0, 1, 0])
        m.flat([[xi, F_Y + 0.002, zb], [xi, F_Y + 0.002, zf], [xi, cy, zf], [xi, cy, zb]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[xi, F_Y + 0.002, zb], [xo, F_Y + 0.002, zb], [xo, cy, zb], [xi, cy, zb]], sw['panel_d'], normal=[0, 0, -1])
        m.flat([[xi, F_Y + 0.002, zf], [xo, F_Y + 0.002, zf], [xo, cy, zf], [xi, cy, zf]], sw['panel_d'], normal=[0, 0, 1])
    ped = atlas.paint(96, 196, p_pedestal)
    px0, px1, pz0, pz1, py = -0.125, 0.125, 11.64, PANEL_BOT[1] + 0.02, 4.60
    m.face([[px0, py, pz1], [px1, py, pz1], [px1, py, pz0], [px0, py, pz0]],
           [m.uv(ped, 0, 1), m.uv(ped, 1, 1), m.uv(ped, 1, 0), m.uv(ped, 0, 0)], normal=[0, 1, 0])
    for s in (1, -1):
        m.flat([[s * px1, F_Y + 0.002, pz0], [s * px1, F_Y + 0.002, pz1], [s * px1, py, pz1], [s * px1, py, pz0]], sw['panel_d'], normal=[s, 0, 0])
    m.flat([[px0, F_Y + 0.002, pz0], [px1, F_Y + 0.002, pz0], [px1, py, pz0], [px0, py, pz0]], sw['panel_d'], normal=[0, 0, -1])
    m.flat([[px0, F_Y + 0.002, pz1], [px1, F_Y + 0.002, pz1], [px1, py, pz1], [px0, py, pz1]], sw['panel_d'], normal=[0, 0, 1])
    # the four throttle levers: stalks and black grips leaning back, one per slot (the tile's slots stand at 0.19 + 0.206 k of its width)
    for k in range(4):
        tx = px1 - (px1 - px0) * (0.19 + 0.206 * k)
        tz = pz1 - (pz1 - pz0) * 0.36
        ax = (X, unit([0, 1, -0.25]), unit([0, 0.25, 1]))
        m.box([tx, py + 0.045, tz], ax, (0.004, 0.050, 0.004), sw['metal'])
        m.box([tx, py + 0.108, tz + 0.012], ax, (0.013, 0.030, 0.021), sw['black'])
        m.box([tx, py + 0.141, tz + 0.016], ax, (0.011, 0.005, 0.018), sw['dgrey'])
    m.box([0.0, py + 0.012, pz0 + 0.08], (X, Y, Z), (0.050, 0.012, 0.030), sw['dgrey'])          # the throttles' friction / HP cock box
    m.box([0.0, py + 0.034, pz0 + 0.08], (X, Y, Z), (0.006, 0.014, 0.006), sw['red'])
    for s in (1, -1):                                    # the airbrake lever on the left of the pedestal
        pass
    m.box([0.158, py + 0.020, pz0 + 0.24], (X, Y, Z), (0.032, 0.020, 0.060), sw['dgrey'])      # pedestal side (airbrake) lever base
    m.box([0.158, py + 0.075, pz0 + 0.24], (X, unit([0, 1, 0.3]), unit([0, -0.3, 1])), (0.007, 0.050, 0.007), sw['metal'])
    m.box([0.158, py + 0.135, pz0 + 0.255], (X, unit([0, 1, 0.3]), unit([0, -0.3, 1])), (0.012, 0.014, 0.020), sw['red'])
    # -- the pilots: Mk 4 seat on a mounting box, rudder pedals, stick boot
    sticks = []
    for x0, E_ in ((X_CAPT, CM.EYE), (X_COPI, CM.EYE_C)):
        P = lambda x, y, z, E_=E_: E_ + np.array([x, y, z], float)       # noqa: E731  eye-relative point
        m.box([x0, F_Y + 0.054, E_[2] - 0.10], (X, Y, Z), (0.17, 0.054, 0.27), sw['dgrey'])           # the seat's mounting box
        seat_mesh(m, atlas, sw, P, X, Y, Z)
        for s2 in (1, -1):                                                # rudder pedals
            m.box([x0 + s2 * 0.12, 4.30, PANEL_BOT[1] + 0.07], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
            m.box([x0 + s2 * 0.12, 4.215, PANEL_BOT[1] + 0.11], (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])
        # the stick's boot stays with the cockpit, the stick itself goes into the stick bone
        sz = 12.10
        m.box([x0, 4.17, sz], (X, Y, Z), (0.050, 0.038, 0.050), sw['dgrey'])
        stick = Mesh(atlas)
        col_ax = (X, unit([0, 1, -0.10]), unit([0, 0.10, 1]))
        stick.box([x0, 4.38, sz - 0.012], col_ax, (0.011, 0.205, 0.011), sw['metal'])
        g_ax = (X, unit([0, 1, 0.22]), unit([0, -0.22, 1]))
        gc = np.array([x0, 4.64, sz - 0.036])
        stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
        stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
        stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
        stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
        sticks.append((stick, np.array([x0, 4.20, sz])))
    return sticks


def seat_mesh(m, atlas, sw, P, X, Y, Z, rails=(-0.46, 0.55)):
    """Martin-Baker Mk 4 ejection seat: bucket and pan, a parachute back, headbox, the face-blind handle above, the lower handle
    between the knees; eye-relative (headbox top y +0.105)"""
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
    m.box(P(0, -0.58, -0.375), ax_b, (0.19, 0.30, 0.020), sw['para'], front=(2, 1, back, X, ax_b[1]))   # the parachute back
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


# ---------------------------------------------------------------- the rear crew compartment

def swivel_seat(m, atlas, sw, xe):
    """a plain swivel seat facing aft (the rear crew had no ejection seats): pedestal on a star base, pan, back, arms, head pad"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    zc = 9.70
    for k in range(3):                                            # the star base: three legs
        a = math.radians(60 * k)
        ax = (np.array([math.cos(a), 0, math.sin(a)]), Y, np.array([-math.sin(a), 0, math.cos(a)]))
        m.box([xe, R_Y + 0.016, zc], ax, (0.19, 0.012, 0.022), sw['frame'])
    m.box([xe, R_Y + 0.062, zc], (X, Y, Z), (0.032, 0.062, 0.032), sw['metal'])                  # the column
    m.box([xe, 3.608, zc], (X, Y, Z), (0.20, 0.022, 0.20), sw['cushion'], front=(1, 1, cushion, X, Z))    # the pan (top y 3.63)
    m.box([xe, 3.935, zc + 0.205], (X, unit([0, 1, 0.12]), unit([0, -0.12, 1])), (0.19, 0.30, 0.040), sw['cushion'])   # the back (faces the desk)
    m.box([xe, 4.28, zc + 0.225], (X, Y, Z), (0.11, 0.075, 0.030), sw['cushion'])                # head pad (top 4.355)
    for s in (1, -1):
        m.box([xe + s * 0.215, 3.80, zc + 0.04], (X, Y, Z), (0.012, 0.012, 0.15), sw['dgrey'])
        m.box([xe + s * 0.215, 3.725, zc + 0.04], (X, Y, Z), (0.010, 0.075, 0.010), sw['frame'])


def rear_crew(m, atlas, sw, gauges, displays):
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    lin, lin_d, ceil = sw['lining'], sw['lining_d'], sw['ceil']
    zr0, zr1 = DESK_Z0, Z_BK_B - 0.001
    # -- floor, riser under the bulkhead, side linings, the ceiling following the hump's underside
    m.flat([[-XW_L, R_Y + 0.002, zr0], [XW_L, R_Y + 0.002, zr0], [XW_L, R_Y + 0.002, 11.15], [-XW_L, R_Y + 0.002, 11.15]], sw['floor'], normal=[0, 1, 0])
    m.flat([[-XW_L, R_Y + 0.002, 11.150], [XW_L, R_Y + 0.002, 11.150], [XW_L, F_Y, 11.150], [-XW_L, F_Y, 11.150]], sw['lining_d'], normal=[0, 0, -1])
    zs = [DESK_Z0 + 0.06 + k * (11.14 - DESK_Z0 - 0.06) / 9 for k in range(10)]
    xs = [0.80, 0.64, 0.48, 0.32, 0.16, 0.0]
    cy_of = lambda x, z: (roof_low(x, z) or 5.2) - 0.014            # noqa: E731
    for s in (1, -1):
        wall_quads(m, sw, s, R_Y, Y_LEDGE, XW_L, zr0, 11.15, -1, lin_d)
        m.flat([[s * XW_U, Y_LEDGE, zr0], [s * XW_L, Y_LEDGE, zr0], [s * XW_L, Y_LEDGE, 11.15], [s * XW_U, Y_LEDGE, 11.15]], lin_d, normal=[0, 1, 0])
        for z0, z1 in zip(zs[:-1], zs[1:]):
            ya, yb = cy_of(XW_U, z0), cy_of(XW_U, z1)
            m.flat([[s * XW_U, Y_LEDGE, z0], [s * XW_U, Y_LEDGE, z1], [s * XW_U, yb, z1], [s * XW_U, ya, z0]], lin, normal=[-s, 0, 0])
            for xa, xb in zip(xs[:-1], xs[1:]):
                q = [[s * xa, cy_of(xa, z0), z0], [s * xa, cy_of(xa, z1), z1], [s * xb, cy_of(xb, z1), z1], [s * xb, cy_of(xb, z0), z0]]
                m.flat(q, ceil, normal=[0, -1, 0])
    # the rear wall's lining above the desk panels (the model's block face is painted with a console)
    for s in (1, -1):
        for xa, xb in zip(xs[:-1], xs[1:]):
            ya, yb = 4.84, 0.5 * (cy_of(xa, REAR_WALL + 0.06) + cy_of(xb, REAR_WALL + 0.06))
            m.flat([[s * xa, 4.84, REAR_WALL], [s * xb, 4.84, REAR_WALL], [s * xb, cy_of(xb, REAR_WALL + 0.06), REAR_WALL], [s * xa, cy_of(xa, REAR_WALL + 0.06), REAR_WALL]], lin, normal=[0, 0, 1])
        m.flat([[s * XW_U, R_Y + 0.002, REAR_WALL], [s * XW_L, R_Y + 0.002, REAR_WALL], [s * XW_L, Y_LEDGE, REAR_WALL], [s * XW_U, Y_LEDGE, REAR_WALL]], lin_d, normal=[0, 0, 1])
        m.flat([[s * XW_U, Y_LEDGE, REAR_WALL], [s * XW_U, cy_of(XW_U, REAR_WALL + 0.06), REAR_WALL], [s * 0.80, cy_of(0.80, REAR_WALL + 0.06), REAR_WALL]], lin, normal=[0, 0, 1]) if False else None
    # -- the desk: a long top on the rear bulkhead, its fascia, side cheeks and the modesty panel; the sloped panel face above it
    m.box([0.0, DESK_TOP_Y - 0.025, 0.5 * (RPANEL_BOT[1] + DESK_Z1)], (X, Y, Z), (0.80, 0.025, 0.5 * (DESK_Z1 - RPANEL_BOT[1])), sw['desktop'])
    for s in (1, -1):
        m.box([s * 0.795, 3.74, 0.5 * (DESK_Z0 + DESK_Z1) + 0.04], (X, Y, Z), (0.012, 0.24, 0.5 * (DESK_Z1 - DESK_Z0) - 0.04), sw['desk'])   # cheeks
    m.flat([[-0.78, R_Y + 0.002, 8.78], [0.78, R_Y + 0.002, 8.78], [0.78, DESK_TOP_Y - 0.05, 8.78], [-0.78, DESK_TOP_Y - 0.05, 8.78]], sw['desk'], normal=[0, 0, 1])
    # the sloped face: from the desk's back edge up to the lining, one facet across the three stations
    cen = np.array([0.0, 0.5 * (RPANEL_BOT[0] + RPANEL_TOP[0]), 0.5 * (RPANEL_BOT[1] + RPANEL_TOP[1])])
    fr = Facet(cen, R_R, U_R, N_R)
    AX = lambda x, y: fr.at(x, y)           # noqa: E731
    stations = {2: 0.485, 4: 0.02425, 3: -0.41225}
    items = []
    # radar navigator (Seat 2): the H2S scope, his clock and radio altimeter, the control panels
    items += [('disp', 2, 0.485, 4.45, ('RADAR', 0.140)), ('gauge', 2, 0.678, 4.72, ('CLOCK', 0.060)), ('gauge', 2, 0.292, 4.72, ('RADAR_ALT', 0.060)),
              ('paint', 2, 0.725, 4.40, (p_h2s, 0.12, 0.20, 'h2s')), ('paint', 2, 0.290, 4.40, (p_radar_ctl, 0.085, 0.20, 'rctl'))]
    # air electronics officer (Seat 4): the RWR, the ECM control and window dispenser panels
    items += [('gauge', 4, 0.02425, 4.67, ('RWR', 0.130)), ('paint', 4, 0.02425, 4.42, (p_ecm, 0.37, 0.14, 'ecm')),
              ('paint', 4, 0.02425, 4.22, (p_window, 0.26, 0.12, 'win'))]
    # navigator plotter (Seat 3): four repeaters over the painted navigation computer
    items += [('gauge', 3, -0.27, 4.74, ('AIRSPEED', 0.070)), ('gauge', 3, -0.38, 4.74, ('MACH', 0.062)), ('gauge', 3, -0.49, 4.74, ('ALTIMETER', 0.070)),
              ('gauge', 3, -0.60, 4.74, ('HEADING', 0.070)), ('paint', 3, -0.435, 4.44, (p_navcomp, 0.40, 0.25, 'nav'))]
    # the panel's face: black, with the stations' backing discs and the three station captions
    boxes = []
    for kind, seat, x, y, spec in items:
        if kind == 'gauge':
            boxes.append(('disc', x, y, spec[1], spec[1]))
        elif kind == 'disp':
            boxes.append(('disc', x, y, spec[1] * 1.9, spec[1] * 1.9))
        elif kind == 'paint':
            boxes.append(('rect', x, y, spec[1], spec[2]))
    W_ = int(round(1.6 * 520)); Hp = int(round((RPANEL_TOP[0] - RPANEL_BOT[0]) / math.cos(LEAN_R) * 520))
    groups = [(0.24, 4.855, 0.80, 3.99, 'RADAR NAVIGATOR'), (-0.20, 4.855, 0.235, 3.99, 'AIR ELECTRONICS OFFICER'), (-0.80, 4.855, -0.205, 3.99, 'NAVIGATOR PLOTTER')]

    def face_fn(d, im):
        SS = Atlas.SS
        noisy(d, im, PANEL, 4, seed=8)

        def to_px(x, y):
            return (x + 0.8) / 1.6 * W_ * SS, (1 - (y - RPANEL_BOT[0]) / (RPANEL_TOP[0] - RPANEL_BOT[0])) * Hp * SS
        px_m = W_ * SS / 1.6
        for (xa, ya, xb, yb2, title) in groups:
            ax_, ay_ = to_px(xa, ya); bx_, by_ = to_px(xb, yb2)
            d.rectangle([min(ax_, bx_), min(ay_, by_), max(ax_, bx_), max(ay_, by_)], outline=(90, 94, 96, 255), width=SS * 2)
            text(d, ((ax_ + bx_) / 2, min(ay_, by_) + SS * 7), title, SS * 7, fill=(170, 174, 168), bold=True)
        for kind, x, y, w, h in boxes:
            cx, cy = to_px(x, y)
            if kind == 'disc':
                r = (w / 2 * (1 + RIM) + 0.004) * px_m
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(14, 16, 18, 255))
            else:
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([cx - sx, cy - sy, cx + sx, cy + sy], fill=(14, 16, 18, 255))
    ptile = atlas.paint(W_, Hp, face_fn)
    pb = [AX(-0.80, RPANEL_BOT[0]), AX(0.80, RPANEL_BOT[0]), AX(0.80, RPANEL_TOP[0]), AX(-0.80, RPANEL_TOP[0])]
    m.quad_tex(pb, ptile, normal=fr.N)
    # the panel face's top lip and sides close it against the rear wall
    for s in (1, -1):
        m.flat([[s * 0.80, RPANEL_BOT[0], RPANEL_BOT[1]], [s * 0.80, RPANEL_TOP[0], RPANEL_TOP[1]], [s * 0.80, RPANEL_TOP[0], REAR_WALL], [s * 0.80, RPANEL_BOT[0], REAR_WALL]], sw['desk'], normal=[-s, 0, 0])
    m.flat([[-0.80, RPANEL_TOP[0], RPANEL_TOP[1]], [0.80, RPANEL_TOP[0], RPANEL_TOP[1]], [0.80, RPANEL_TOP[0], REAR_WALL], [-0.80, RPANEL_TOP[0], REAR_WALL]], sw['desk'], normal=[0, 1, 0])
    # the dials, displays and painted panels
    for kind, seat, x, y, spec in items:
        c = AX(x, y)
        if kind == 'gauge':
            gauges.append({'Seat': seat, 'Kind': spec[0], 'Center': r5(c + fr.N * GAUGE_LIFT), 'Normal': r5(fr.N), 'Up': r5(fr.U),
                           'Diameter': spec[1], 'Depth': 0.03})
        elif kind == 'disp':
            w = spec[1]
            displays.append({'Seat': seat, 'Center': r5(c + fr.N * DISPLAY_LIFT), 'Normal': r5(fr.N), 'Up': r5(fr.U), 'Width': w,
                             'Depth': 0.035, 'Kind': spec[0], 'Style': 'CRT'})
            # the round tube in its hood (as the Vautour's DRAC scope): a round bezel and a round rubber hood, deep on top
            _, n_, u_, r_ = frame(c, fr.N, fr.U)
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
                m.flat(q, sw['black'], toward=c + (d0 + d1) * 5 + n_ * 0.03)
                q2 = [c + d0 * r_in + n_ * 0.009, c + d1 * r_in + n_ * 0.009, c + d1 * r_in + n_ * (0.009 + dep1),
                      c + d0 * r_in + n_ * (0.009 + dep0)]
                m.flat(q2, sw['black'], toward=c + n_ * 0.03)
                m.flat([q[3], q[2], q2[2], q2[3]], sw['black'], toward=c + n_ * 0.2)
        else:
            fn, w, h, _ = spec
            pw, ph = (112, max(16, int(round(112 * h / w)))) if h >= w else (160, max(16, int(round(160 * h / w))))
            rect = atlas.paint(pw, ph, fn)
            m.rect_tex(c + fr.N * 0.004, fr.N, fr.U, w, h, rect)
            m.frame_rect(c, fr.N, fr.U, w, h, 0.004, 0.006, sw['bezel'])
    # -- on the desk: the radar navigator's hand controller and chart-table items, the plotter's chart and dividers, the AEO's boxes
    chart = atlas.paint(200, 130, p_chart)
    ctop = DESK_TOP_Y + 0.003
    zc0, zc1 = 8.80, 9.12
    m.face([[-0.65, ctop, zc1], [-0.22, ctop, zc1], [-0.22, ctop, zc0], [-0.65, ctop, zc0]],
           [m.uv(chart, 0, 0), m.uv(chart, 1, 0), m.uv(chart, 1, 1), m.uv(chart, 0, 1)], normal=[0, 1, 0])
    m.box([-0.435, ctop + 0.010, zc0 - 0.012], (X, Y, Z), (0.215, 0.010, 0.010), sw['metal'])    # the plotter's parallel rule's bar
    m.box([-0.50, ctop + 0.013, 8.97], (unit([1, 0, 0.1]), Y, unit([-0.1, 0, 1])), (0.17, 0.003, 0.006), sw['dgrey'])
    # the radar navigator's hand controller (a stalk and a grip) and sector switch
    m.box([0.60, ctop + 0.040, 8.96], (X, Y, Z), (0.007, 0.040, 0.007), sw['metal'])
    gax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
    m.box([0.60, ctop + 0.100, 8.975], gax, (0.020, 0.040, 0.030), sw['black'])
    m.box([0.60, ctop + 0.147, 8.99], gax, (0.003, 0.008, 0.008), sw['red'])
    m.box([0.34, ctop + 0.012, 8.98], (X, Y, Z), (0.050, 0.012, 0.050), sw['dgrey'])
    m.box([0.34, ctop + 0.034, 8.98], (X, Y, Z), (0.005, 0.014, 0.005), sw['metal'])
    # the AEO's boxes: a comms control unit and a log board
    rack = atlas.paint(64, 48, p_rack('COMMS'))
    m.box([0.02425, ctop + 0.030, 8.95], (X, Y, Z), (0.120, 0.030, 0.050), sw['dgrey'], front=(2, 1, rack, X, Y))
    m.box([0.02425 + 0.20, ctop + 0.006, 9.00], (X, Y, Z), (0.045, 0.006, 0.060), sw['metal'])
    # -- three swivel seats at their eyes
    for xe in (0.485, 0.02425, -0.41225):
        swivel_seat(m, atlas, sw, xe)
    # -- a small rack on the left side wall, a map case on the right
    rk = atlas.paint(64, 48, p_rack('RADIO'))
    m.box([-0.97, 4.12, 10.30], (X, Y, Z), (0.05, 0.10, 0.18), sw['dgrey'], front=(0, 1, rk, -Z, Y))
    m.box([0.97, 4.10, 10.30], (X, Y, Z), (0.05, 0.09, 0.20), sw['dgrey'], front=(0, -1, rk, Z, Y))


# ---------------------------------------------------------------- build

def build(atlas):
    m = Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY, lining=LINING,
                                                lining_d=LINING_D, ceil=CEIL, floor=FLOORC, frame=FRAME, cushion=CUSHION, para=PARA,
                                                yellow=YELLOW, red=RED, metal=METAL, desk=DESK, desktop=DESKTOP,
                                                bezel=(30, 31, 32)).items()}
    gauges, displays = [], []
    sticks = flight_deck(m, atlas, sw, gauges, displays)
    rear_crew(m, atlas, sw, gauges, displays)
    return m, sticks, gauges, displays


# ---------------------------------------------------------------- the airframe

def install(ac, mesh, sticks, gauges, displays):
    pm = mesh.poly_mesh()
    cb = next((b for b in ac.bones if b['name'] == COCKPIT_BONE), None)
    if cb is None:
        hull = ac.bone('hull')
        at = next(i for i, b in enumerate(ac.bones) if b['name'] == 'wreck_fuselage_1__hull') + 1
        cb = {'name': COCKPIT_BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        ac.bones.insert(at, cb)
    cb['poly_mesh'] = pm
    rig = ac.asset.get('AircraftRig')
    (stick_c, foot_c), (stick_p, foot_p) = sticks
    px_c = r5(np.array(foot_c) * np.array([16.0, 16.0, -16.0]))
    px_p = r5(np.array(foot_p) * np.array([16.0, 16.0, -16.0]))
    for b in ac.bones:
        if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
            b['pivot'] = px_c
            if b['name'].endswith('__control_stick_pitch'):
                b['poly_mesh'] = stick_c.poly_mesh()
    for sf in (rig or {}).get('Surfaces', []):
        if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
            sf['Pivot'] = px_c
    # the co-pilot's own stick: a second roll / pitch pair on the same controls (the bones and their rig surfaces)
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
    a.pop('HeadUpDisplay', None)               # the Vulcan has no head-up display
    return pm


def main(argv):
    global ROOF
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    # the hull's faces over the rear compartment (for the ceiling lining)
    T = []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not b['name'].startswith('wreck_fuselage') or b['name'].endswith(('__cockpit', '__canopy')) \
                or 'gear' in b['name'] or 'stick' in b['name']:
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for poly in pm['polys']:
            V = P[[v[0] for v in poly]]
            if V[:, 2].max() < 8.3 or V[:, 2].min() > 11.4 or np.abs(V[:, 0]).min() > 1.1:
                continue
            for k in range(1, len(V) - 1):
                T.append(V[[0, k, k + 1]])
    T = np.array(T)
    ROOF = (T[:, 0], T[:, 1], T[:, 2])
    atlas = Atlas(VID)
    mesh, sticks, gauges, displays = build(atlas)
    pm = install(ac, mesh, sticks, gauges, displays)
    print(f'{VID}: new cockpits {len(pm["polys"])} faces, {len(gauges)} gauges, {len(displays)} displays')
    if not dry:
        write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
