#!/usr/bin/env python3
"""Per-aircraft cockpit structures: the parts kit the hand-built cockpits are made of, and the dispatch to each
aircraft's own description in structures/<vid>.py.

A structure is the furniture of one crew station, modelled to the real aircraft from its reference photos: the
instrument panel as a set of named plates (sub-panels, each with its own plane and size), the glareshield lofted
from the panel lip to the windscreen base, the head-up display or gunsight, the side consoles with their panels,
the seat, stick, throttle and pedals, the walls, floor and bulkheads. The plates are the stage the instruments
are placed on afterwards (stage 2): their names, planes and sizes come back from build() so a layout can refer to
'mfd_l' or 'centre' by name, and the review renders can label them.

Frames: vehicle-local blocks (x left, y up, z forward) everywhere. A plate's frame is (R, U, N): R toward the
pilot's right, U up its face, N out of the face toward the pilot. Sizes are metres at scale 1; the station's scale
s (cockpit_lib.Tub.s) is applied by the aircraft file where a size is real-world, so a model built to a different
scale than life gets a cockpit of its own size.

Usage: tools/cockpit_models/fleet.py calls build() for every aircraft that has a structures/<vid>.py; the aircraft
file's build(S) adds the parts to the Station S and returns nothing.
"""
import importlib, math, os, subprocess
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
import cockpit_lib as L  # noqa: E402

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
unit, frame, r5 = L.unit, L.frame, L.r5


def has(vid):
    return os.path.exists(os.path.join(HERE, 'structures', vid + '.py'))


def module(vid):
    return importlib.import_module('structures.' + vid)


def rot_x(deg):
    """(up, forward) unit vectors of a frame pitched [deg] degrees nose-up about x (positive: top goes aft)"""
    a = math.radians(deg)
    return np.array([0, math.cos(a), -math.sin(a)]), np.array([0, math.sin(a), math.cos(a)])


def is_png(path):
    try:
        with open(path, 'rb') as f:
            return f.read(8) == b'\x89PNG\r\n\x1a\n'
    except OSError:
        return False


def lfs_fetch(path):
    """fetches a Git LFS pointer file's content into the working tree; True when the file is a real file after"""
    if is_png(path):
        return True
    rel = os.path.relpath(path, L.ROOT) if hasattr(L, 'ROOT') else os.path.relpath(path, os.path.join(HERE, '..', '..'))
    try:
        subprocess.run(['git', 'lfs', 'pull', '--include=' + rel], cwd=os.path.join(HERE, '..', '..'), check=False,
                       capture_output=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        pass
    return is_png(path)


class Atlas:
    """Painted tiles in the free areas of the aircraft's texture (where no uv of the model lands). The free areas
    are found from the model's uvs, so a rerun paints the same places again. paint(w, h, fn) -> rect; fn(draw,
    img) paints an RGBA tile at SS x resolution, downsampled for clean edges. swatch(rgb) -> rect of one colour."""
    SS = 4
    MARGIN = 3

    def __init__(self, geo, tex_path, min_side=48, max_rects=24):
        self.base = Image.open(tex_path).convert('RGBA')
        self.W, self.H = self.base.size
        used = Image.new('L', (self.W, self.H), 0)
        d = ImageDraw.Draw(used)
        for b in geo['minecraft:geometry'][0]['bones']:
            pm = b.get('poly_mesh')
            if not pm or not pm.get('polys') or b['name'].endswith('__cockpit'):
                continue
            uv = np.array(pm['uvs'], float)
            for poly in pm['polys']:
                pts = [(uv[v[2]][0] * self.W, (1 - uv[v[2]][1]) * self.H) for v in poly]
                d.polygon(pts, fill=255)
                d.line(pts + [pts[0]], fill=255, width=2 * self.MARGIN + 1)
        g = 16
        cells = np.asarray(used).reshape(self.H // g, g, self.W // g, g).max(axis=(1, 3)) > 0
        self.areas = []                                       # (x, y, w, h) in texels, the free rectangles
        for _ in range(max_rects):
            r = largest_empty(cells)
            if r is None or r[2] * g < min_side or r[3] * g < min_side:
                break
            i, j, w, h = r
            cells[j:j + h, i:i + w] = True
            self.areas.append((i * g, j * g, w * g, h * g))
        self.free = list(self.areas)
        self.tiles = []
        self.swatches = {}

    def alloc(self, w, h):
        best = None
        for i, (x, y, fw, fh) in enumerate(self.free):
            if w <= fw and h <= fh:
                score = min(fw - w, fh - h)
                if best is None or score < best[0]:
                    best = (score, i)
        if best is None:
            raise RuntimeError(f'atlas full: no room for {w}x{h}')
        x, y, fw, fh = self.free.pop(best[1])
        if fw - w < fh - h:
            self.free += [(x + w, y, fw - w, h), (x, y + h, fw, fh - h)]
        else:
            self.free += [(x + w, y, fw - w, fh), (x, y + h, w, fh - h)]
        self.free = [r for r in self.free if r[2] > 0 and r[3] > 0]
        return (x, y, w, h)

    def paint(self, w, h, fn, bg=(0, 0, 0, 255)):
        r = self.alloc(w, h)
        img = Image.new('RGBA', (w * self.SS, h * self.SS), bg)
        fn(ImageDraw.Draw(img), img)
        self.tiles.append((r, img.resize((w, h), Image.LANCZOS)))
        return r

    def swatch(self, rgb):
        key = tuple(int(c) for c in rgb)
        if key not in self.swatches:
            self.swatches[key] = self.paint(8, 8, lambda d, im: None, bg=key + (255,))
        return self.swatches[key]

    def uv(self, rect, s, t):
        """normalized uv of (s, t) in a rect: s right, t up, both 0..1, kept half a texel inside"""
        x, y, w, h = rect
        s = 0.5 / w + s * (1 - 1.0 / w); t = 0.5 / h + t * (1 - 1.0 / h)
        return [(x + s * w) / self.W, 1.0 - (y + (1 - t) * h) / self.H]

    def uv_flat(self, rect):
        return self.uv(rect, 0.5, 0.5)

    def used(self):
        return sum(r[2] * r[3] for r, _ in self.tiles)

    def compose(self, base, dead=False):
        base = base.convert('RGBA')
        for x, y, w, h in self.areas:
            base.paste((0, 0, 0, 0), (x, y, x + w, y + h))
        for (rx, ry, rw, rh), img in self.tiles:
            if dead:
                a = np.asarray(img).astype(np.float32)
                g = a[..., :3].mean(-1, keepdims=True)
                a[..., :3] = (a[..., :3] * 0.25 + g * 0.15) * 0.9          # soot: dark, mostly grey
                img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
            base.paste(img, (rx, ry))
        return base

    def write(self, path, dead_path=None):
        """the painted texture to [path] (the base is the texture the atlas was made on) and the burnt copy to
        [dead_path] when that texture can be had; -> (True, dead written)"""
        self.compose(self.base).save(path, optimize=True)
        dead = False
        if dead_path and lfs_fetch(dead_path):
            self.compose(Image.open(dead_path), dead=True).save(dead_path, optimize=True)
            dead = True
        return True, dead


def largest_empty(cells):
    """(i, j, w, h) of the largest all-False rectangle in a boolean grid (histogram method), or None"""
    H, W = cells.shape
    best, hist = None, np.zeros(W, int)
    for j in range(H):
        hist = np.where(cells[j], 0, hist + 1)
        stack = []
        for i in range(W + 1):
            h = hist[i] if i < W else 0
            start = i
            while stack and stack[-1][1] >= h:
                s, sh = stack.pop()
                area = sh * (i - s)
                if sh > 0 and (best is None or area > best[0]):
                    best = (area, s, j - sh + 1, i - s, sh)
                start = s
            stack.append((start, h))
    return None if best is None else best[1:]


def noisy(d, im, colour, amount=6, seed=1):
    """speckle over a tile (fabric, painted metal)"""
    rng = np.random.default_rng(seed)
    a = np.asarray(im).astype(np.int16)
    n = rng.integers(-amount, amount + 1, size=a.shape[:2] + (1,))
    a[..., :3] = np.clip(a[..., :3] + n, 0, 255)
    im.paste(Image.fromarray(a.astype(np.uint8)))


class Plate:
    """a flat rectangular sub-panel: centre c, frame (R, U, N), size w x h (metres)"""

    def __init__(self, name, c, R, U, w, h, label=''):
        self.name, self.label = name, label
        self.c = np.array(c, float)
        self.R, self.U = unit(R), unit(U)
        self.N = unit(np.cross(self.R, self.U))
        self.w, self.h = float(w), float(h)

    def at(self, a, y, lift=0.0):
        """point on the plate: a metres right of its centre, y metres up, lift proud of the face"""
        return self.c + self.R * a + self.U * y + self.N * lift

    def corners(self, lift=0.0):
        hw, hh = self.w / 2, self.h / 2
        return [self.at(-hw, -hh, lift), self.at(hw, -hh, lift), self.at(hw, hh, lift), self.at(-hw, hh, lift)]

    def describe(self):
        return {'name': self.name, 'label': self.label, 'centre': r5(self.c), 'right': r5(self.R), 'up': r5(self.U),
                'normal': r5(self.N), 'width': round(self.w, 4), 'height': round(self.h, 4)}


class Station:
    """everything one aircraft file builds into: the aircraft and tub, the colours, the mesh and the registry of
    plates; the instrument lists stay empty at stage 1"""

    def __init__(self, ac, tub, sw, colours, atlas=None):
        self.ac, self.t, self.sw, self.atlas = ac, tub, sw, atlas
        self.s, self.E = tub.s, tub.E
        self.m, self.stick = L.Mesh(), L.Mesh()
        self.plates = {}
        self.gauges, self.displays, self.glass = [], [], []
        self.hud = None
        self.stick_foot = None
        self.palette = {}
        self.c = {}
        for k, v in list(L.ACCENT.items()) + list(colours.items()):
            self.colour(k, v)
        self.ctx = {'console': {}, 'wall_z': {}}

    def colour(self, name, rgb):
        """a named colour: an exact tile in the atlas, or the nearest texel block of the texture without one"""
        self.palette[name] = tuple(rgb)
        self.c[name] = self.atlas.uv_flat(self.atlas.swatch(rgb)) if self.atlas else self.sw.uv(rgb)

    def tile(self, w, h, fn, bg=(0, 0, 0, 255)):
        """a painted tile (atlas rect), or None without an atlas"""
        return self.atlas.paint(w, h, fn, bg) if self.atlas else None

    def uv(self, rect, s, t):
        return self.atlas.uv(rect, s, t)

    def plate(self, name, c, R, U, w, h, depth=0.02, colour='panel', edge=None, label='', lift=0.0, register=True):
        """a slab: the face in [colour], its edges and back in [edge]; registered under [name]"""
        p = Plate(name, c, R, U, w, h, label)
        if register:
            self.plates[name] = p
        self.slab(p, depth, colour, edge, lift)
        return p

    def slab(self, p, depth=0.02, colour='panel', edge=None, lift=0.0):
        m, c = self.m, self.c
        edge = edge or colour
        front = p.corners(lift)
        m.flat(front, c[colour], normal=p.N)
        back = [q - p.N * depth for q in front]
        m.flat(back, c[edge], normal=-p.N)
        for i in range(4):
            a, b = front[i], front[(i + 1) % 4]
            mid = (a + b) / 2
            m.flat([a, b, back[(i + 1) % 4], back[i]], c[edge], toward=mid + (mid - p.c) * 2)

    def box(self, centre, axes, half, colour, faces=None):
        self.m.box(centre, axes, half, self.c[colour],
                   faces={k: self.c[v] for k, v in faces.items()} if faces else None)

    def describe_plates(self):
        return [p.describe() for p in self.plates.values()]


# ---------------------------------------------------------------- the glareshield

def coaming(S, lip, ws_y=None, ws_z=None, front_hw=None, crown=0.04, lip_height=0.03, lip_depth=0.045, colour='coaming',
            drop=0.15, flat=False):
    """the glareshield lofted from the panel lip to the windscreen base. lip: [(point, N)] along the panel top from
    the pilot's left to right (point on the panel top edge, N the panel normal there). The lip edge stands
    [lip_height] above and [lip_depth] ahead of the panel top; the loft runs to a line under the windscreen base
    [ws_z] ahead at [ws_y], [front_hw] half wide, with a crown of [crown] between; flat: no crown, no dome."""
    t, E, s, m, c = S.t, S.E, S.s, S.m, S.c
    ac = S.ac
    ws_y = t.ws_y if ws_y is None else ws_y
    ws_z = t.ws_z if ws_z is None else ws_z
    front_hw = t.W * 0.9 if front_hw is None else front_hw
    pts = []
    for p, n in lip:
        p = np.array(p, float)
        if pts and np.linalg.norm(pts[-1][0] - p) < 1e-6:
            continue
        pts.append((p, unit(n)))
    lip_edge = [p + n * lip_depth * s + Y * lip_height * s for p, n in pts]
    panel_top = [p for p, n in pts]
    half = max(abs(lip_edge[0][0] - E[0]), abs(lip_edge[-1][0] - E[0]), 1e-6)
    rows, pinched = [], set()
    for k, tt in enumerate((0.0, 0.35, 0.7, 1.0)):
        row = []
        for le in lip_edge:
            sx = (le[0] - E[0]) / half
            xf = E[0] + sx * front_hw
            yf = ws_y - (0.0 if flat else 0.05 * s * sx * sx)
            z = le[2] + (ws_z - le[2]) * tt
            x = le[0] + (xf - le[0]) * tt
            cr = 0.0 if flat else crown * s * math.sin(math.pi * tt) * (1 - sx * sx)
            y = le[1] + (yf - le[1]) * tt + cr
            if k:
                wz = L.footprint_w(ac, E, z)
                if wz is not None and abs(x - E[0]) > wz - 0.02:
                    x = E[0] + np.sign(x - E[0]) * max(0.05, wz - 0.02)
                    pinched.add((k, len(row)))
                while abs(x - E[0]) > 0.05 and ac.glass_above(x, z, y - 0.3) is None:
                    x -= np.sign(x - E[0]) * 0.02
                    pinched.add((k, len(row)))
            g = ac.glass_above(x, z, y - 0.3) if k else None
            if g is not None and g < y + 0.02:
                y = g - 0.02
            if k and abs(x - E[0]) > 0.05:
                d = ac.skin_x(y, z, E[0])[0 if x > E[0] else 1]
                if abs(x - E[0]) > d - 0.01:
                    x = E[0] + np.sign(x - E[0]) * max(0.05, d - 0.01)
                    pinched.add((k, len(row)))
            row.append(np.array([x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], c[colour], normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):
        a, b = lip_edge[j], lip_edge[j + 1]
        da, db = a - Y * 0.012 * s, b - Y * 0.012 * s
        m.flat([a, b, db, da], c[colour], toward=E)
        m.flat([da, db, panel_top[j + 1], panel_top[j]], c[colour], normal=[0, -1, 0])
    for j in (0, len(lip_edge) - 1):
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            if (i, j) in pinched or (i + 1, j) in pinched:
                continue
            p, q = col[i], col[i + 1]
            lo = min(t.sill, p[1]) - drop * s
            while lo < p[1] - 0.01 and not (t.fits(ac, p[0], lo, p[2]) and t.fits(ac, q[0], lo, q[2])):
                lo += 0.02
            m.flat([p, q, [q[0], lo, q[2]], [p[0], lo, p[2]]], c[colour], toward=[E[0], lo, (p[2] + q[2]) / 2])
    S.ctx['coaming_rows'] = rows
    return rows


# ---------------------------------------------------------------- head-up displays and sights

def combiner(S, bottom, up, height, hw, frame_colour='dgrey', posts=True, post_half=(0.004, 0.005), clamp=True):
    """the combiner glass between two side posts; registers the HUD picture plane and the glass triangles"""
    g_up = unit(up)
    g_n = unit(np.cross(X, g_up))                         # away from the pilot
    g_bot = np.array(bottom, float)
    g_top = g_bot + g_up * height
    if clamp:
        S.box(g_bot - g_up * 0.006, (X, g_up, g_n), (hw + 0.008, 0.006, 0.012), frame_colour)
    if posts:
        for sg in (-1, 1):
            S.box((g_bot + g_top) / 2 + X * sg * (hw + post_half[0]), (X, g_up, g_n),
                  (post_half[0], height / 2, post_half[1]), frame_colour)
    gc = [g_bot - X * hw, g_bot + X * hw, g_top + X * hw, g_top - X * hw]
    S.glass += [gc[0], gc[1], gc[2], gc[0], gc[2], gc[3]]
    S.hud = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * hw, height=height)
    return g_bot, g_top


def hud_box(S, z0, z1, y0, y1, hw, lean=22, g_h=0.165, g_hw=0.074, g_z=None, body='dgrey', ctrl=True):
    """a conventional HUD: projector body on the glareshield, the combiner on a stem above its rear end, centred on
    the pilot's straight-ahead line"""
    E, s = S.E, S.s
    S.box([E[0], (y0 + y1) / 2, (z0 + z1) / 2], (X, Y, Z), (hw, (y1 - y0) / 2, (z1 - z0) / 2), body,
          faces={(2, -1): 'black'})
    if ctrl:
        for k in range(5):
            bx = E[0] + hw * (-0.7 + 0.35 * k)
            S.box([bx, (y0 + y1) / 2, z0 - 0.004], (X, Y, Z), (0.012 * s, 0.01 * s, 0.004), 'mgrey')
    g_up, _ = rot_x(lean)
    g_z = z0 + 0.02 * s if g_z is None else g_z
    g_bot = np.array([E[0], E[1] - g_up[1] * g_h / 2, g_z + g_up[2] * g_h / 2])
    stem_h = max(g_bot[1] - 0.004 - (y1 + 0.012), 0.0)
    if stem_h > 0.004:
        S.box([E[0], y1 + 0.012 + stem_h / 2, g_bot[2]], (X, Y, Z), (g_hw + 0.008, stem_h / 2, 0.012), body)
    S.m.cyl([E[0], y1 + 0.0005, g_bot[2] + 0.05 * s], Y, Z, 0.028 * s, 0.004, S.c['black'], cap=S.c['black'])
    return combiner(S, g_bot, g_up, g_h, g_hw, frame_colour=body)


def gunsight(S, z, y_base, lean=30, g_h=0.09, g_hw=0.035, body='black'):
    """a reflector gunsight: the sight head on the coaming, the reflector glass leaning back over it"""
    E, s = S.E, S.s
    S.box([E[0], y_base + 0.03 * s, z], (X, Y, Z), (0.045 * s, 0.03 * s, 0.07 * s), body)
    g_up, _ = rot_x(lean)
    g_bot = np.array([E[0], y_base + 0.06 * s, z + 0.03 * s])
    return combiner(S, g_bot, g_up, g_h * s, g_hw * s, frame_colour=body, post_half=(0.003, 0.003))


# ---------------------------------------------------------------- the tub: walls, consoles, floor, bulkheads

def shell(S, console_front, xin_frac=0.58, wall_colour='wall', console_colour='console', floor_colour='floor',
          shade='shade', sill_rail=True, back_z=None, front_z=None, wall_top=None, console_y=None):
    """side walls, console tops (registered as plates console_l / console_r), floor, rear bulkhead and the
    footwell. console_front(x) -> z where the console top meets the panel at that x."""
    t, E, s, m, c, ac = S.t, S.E, S.s, S.m, S.c, S.ac
    back_z = t.back_z if back_z is None else back_z
    front_z = t.front_z if front_z is None else front_z
    wall_top = t.sill if wall_top is None else wall_top
    cy = t.console_y if console_y is None else console_y
    xin = xin_frac * t.W
    xlow = {}
    for side in (1, -1):
        xw = E[0] + t.W * side
        xi = E[0] + xin * side
        zf_i, zf_o = console_front(xi), console_front(xw)
        zb = back_z + 0.1 * s
        zw = max(zf_o, zf_i) + 0.1
        while zw < t.Z + 0.3 * s and t.fits(ac, xw, wall_top - 0.02, zw + 0.03) and t.fits(ac, xw, cy, zw + 0.03):
            zw += 0.03
        m.flat([[xw, cy - 0.02, back_z], [xw, cy - 0.02, zw], [xw, wall_top, zw], [xw, wall_top, back_z]],
               c[wall_colour], normal=[-side, 0, 0])
        xl = xw
        for y in (t.floor_y + 0.02, (t.floor_y + cy) / 2):
            d = ac.skin_x(y, (back_z + zb) / 2, E[0])[0 if side > 0 else 1]
            xl = E[0] + side * min(abs(xl - E[0]), d - 0.01)
        xlow[side] = xl
        m.flat([[xl, t.floor_y, back_z], [xl, t.floor_y, zb], [xw, cy, zb], [xw, cy, back_z]], c[wall_colour],
               normal=[-side, 0, 0])
        top = [[xi, cy, zb], [xw, cy, zb], [xw, cy, zf_o], [xi, cy, zf_i]]
        m.flat(top, c[console_colour], normal=[0, 1, 0])
        m.flat([[xi, t.floor_y, zb], [xi, t.floor_y, zf_i], [xi, cy, zf_i], [xi, cy, zb]], c[shade],
               normal=[-side, 0, 0])
        m.flat([[xi, t.floor_y, zb], [xw, t.floor_y, zb], [xw, cy, zb], [xi, cy, zb]], c[shade], normal=[0, 0, -1])
        name = 'console_l' if side > 0 else 'console_r'
        zf = min(zf_i, zf_o)
        S.plates[name] = Plate(name, [(xi + xw) / 2, cy, (zb + zf) / 2], -X, Z, abs(xw - xi), zf - zb,
                               'left console' if side > 0 else 'right console')
        S.ctx['console'][side] = (xi, xw, zb, zf)
        S.ctx['wall_z'][side] = zw
        if sill_rail:
            S.box([xw - side * 0.035, wall_top + 0.008, (back_z + zf_o) / 2], (X, Y, Z),
                  (0.03, 0.010, (zf_o - back_z) / 2), 'dgrey')
    m.flat([[E[0] - xin, t.floor_y, back_z], [E[0] + xin, t.floor_y, back_z], [E[0] + xin, t.floor_y, front_z],
            [E[0] - xin, t.floor_y, front_z]], c[floor_colour], normal=[0, 1, 0])
    m.flat([[xlow[-1], t.floor_y, back_z], [xlow[1], t.floor_y, back_z], [E[0] + t.W, cy, back_z],
            [E[0] - t.W, cy, back_z]], c[wall_colour], normal=[0, 0, 1])
    m.flat([[E[0] - t.W, cy, back_z], [E[0] + t.W, cy, back_z], [E[0] + t.W, wall_top + 0.02, back_z],
            [E[0] - t.W, wall_top + 0.02, back_z]], c[wall_colour], normal=[0, 0, 1])
    S.ctx['xin'] = xin
    S.ctx['cy'] = cy
    return xin


def footwell(S, panel_bottom_y, z_face, xin=None, colour='floor', shade='shade'):
    """the forward bulkhead of the footwell under the panel and the panel's lower closing face"""
    t, E, s, m, c = S.t, S.E, S.s, S.m, S.c
    xin = S.ctx.get('xin', 0.58 * t.W) if xin is None else xin
    m.flat([[E[0] - xin, t.floor_y, t.front_z], [E[0] + xin, t.floor_y, t.front_z],
            [E[0] + xin, panel_bottom_y, t.front_z], [E[0] - xin, panel_bottom_y, t.front_z]], c[colour],
           normal=[0, 0, -1])
    m.flat([[E[0] - xin, panel_bottom_y, z_face], [E[0] + xin, panel_bottom_y, z_face],
            [E[0] + xin, panel_bottom_y, t.front_z], [E[0] - xin, panel_bottom_y, t.front_z]], c[shade],
           normal=[0, -1, 0])
    for sg in (1, -1):
        m.flat([[E[0] + sg * xin, t.floor_y, z_face - 0.2 * s], [E[0] + sg * xin, t.floor_y, t.front_z],
                [E[0] + sg * xin, panel_bottom_y, t.front_z], [E[0] + sg * xin, panel_bottom_y, z_face - 0.2 * s]],
               c[shade], normal=[-sg, 0, 0])


def pedals(S, z=None, spread=0.12, raise_=0.12, colour='mgrey'):
    t, E, s = S.t, S.E, S.s
    pz = (t.front_z - 0.08 * s) if z is None else z
    for sg in (1, -1):
        S.box([E[0] + sg * spread * s, t.floor_y + raise_ * s, pz], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])),
              (0.045 * s, 0.07 * s, 0.01), colour)
        S.box([E[0] + sg * spread * s, t.floor_y + raise_ * s - 0.06 * s, pz + 0.06 * s], (X, Y, Z),
              (0.012, 0.03 * s, 0.06 * s), 'dgrey')
        S.box([E[0] + sg * spread * s, t.floor_y + 0.002, pz - 0.16 * s], (X, Y, Z), (0.045 * s, 0.002, 0.07 * s), 'lgrey')
        up, fw = unit([0, 1, -0.35]), unit([0, 0.35, 1])
        S.box(np.array([E[0] + sg * spread * s, t.floor_y + raise_ * s, pz]) + up * 0.02 * s - fw * 0.014,
              (X, up, fw), (0.046 * s, 0.007 * s, 0.003), 'dgrey')


def wall_frames(S, pitch=0.24, stringer=True, colour='console'):
    """frames and a stringer on the side walls between the console and the sill"""
    t, E, s = S.t, S.E, S.s
    cy = S.ctx.get('cy', t.console_y)
    for side in (1, -1):
        xi, xw, zb, zf_i = S.ctx['console'][side]
        zw = S.ctx['wall_z'][side]
        n = max(2, int((zw - t.back_z) / (pitch * s)) + 1)
        ym = (t.sill + cy) / 2
        hy = (t.sill - cy) / 2 - 0.012
        for k in range(n):
            z = t.back_z + (k + 0.5) * (zw - t.back_z) / n
            S.box([xw - side * 0.007, ym, z], (X, Y, Z), (0.007, hy, 0.010 * s), colour)
        if stringer:
            S.box([xw - side * 0.005, ym, (t.back_z + zw) / 2], (X, Y, Z),
                  (0.005, 0.006 * s, (zw - t.back_z) / 2 - 0.01), colour)


def console_panels(S, side, kinds, slot_len=0.11, pal_name='nato', skip=()):
    """panels along a side console from front to back: kinds from cockpit_lib.console_slot ('throttle',
    'toggles', 'rotary', 'fuel', 'radio', 'breakers', 'caution', 'oxygen', 'lever', 'trim'); 'bare' leaves a plain
    plate; skip: (z0, z1) ranges left to the throttle / stick"""
    t, E, s = S.t, S.E, S.s
    xi, xw, zb, zf = S.ctx['console'][side]
    cy = S.ctx.get('cy', t.console_y)
    Lc = zf - zb
    n = max(2, int(round(Lc / (slot_len * s))))
    hl = Lc / n / 2 - 0.006 * s
    hw = (abs(xw - xi) - 0.02 * s) / 2
    xc = (xi + xw) / 2
    kinds = list(kinds)
    for k in range(n):
        zc = zf - (k + 0.5) * Lc / n
        if any(z0 - 0.01 < zc < z1 + 0.01 for z0, z1 in skip):
            kind = 'bare'
        else:
            kind = kinds.pop(0) if kinds else 'toggles'
        if kind == 'bare':
            S.box([xc, cy + 0.002, zc], (X, Y, Z), (hw, 0.002, hl), 'black')
            continue
        L.console_slot(S.m, kind, side, xc, zc, hw, hl, cy, s, S.c, t, S.gauges, pal_name)
    S.box([xi + side * 0.004, cy + 0.004, (zb + zf) / 2], (X, Y, Z), (0.005, 0.005, Lc / 2), 'dgrey')


# ---------------------------------------------------------------- seats

def seat_aces2(S, recline=30, hip=None, pan_tilt=12, headbox_top=None, frame='frame', cushion='cushion'):
    """ACES II (F-16 at 30 degrees, A-10 / F-15 at about 17). hip: the seat reference point (the pan's rear centre,
    where the back meets it); default from the eye and the recline angle. The parachute container tops the back,
    the headrest pad on its front; the ejection handle is the loop between the knees."""
    E, s = S.E, S.s
    r = math.radians(recline)
    up_b, fwd_b = rot_x(recline)                                  # along the back (top goes aft)
    # the seat reference point sits 0.70 m under the eye; the more the seat reclines, the further ahead of the eye
    hip = np.array(hip, float) if hip is not None else E - Y * 0.70 * s + Z * (0.70 * s * math.tan(r) - 0.22 * s)
    up_p, fwd_p = rot_x(-pan_tilt)                                # the pan: front raised
    pan_c = hip + fwd_p * 0.22 * s
    # the bucket: survival-kit base, pan cushion, sides
    S.box(pan_c - up_p * 0.10 * s, (X, up_p, fwd_p), (0.21 * s, 0.07 * s, 0.22 * s), frame)
    S.box(pan_c + up_p * 0.0 * s, (X, up_p, fwd_p), (0.20 * s, 0.035 * s, 0.21 * s), cushion)
    for sg in (1, -1):
        S.box(pan_c + X * sg * 0.222 * s + up_p * 0.02 * s - fwd_p * 0.02 * s, (X, up_p, fwd_p),
              (0.012 * s, 0.12 * s, 0.24 * s), frame)
    # the back: cushion on a shell, with side bolsters; the rails behind it from the floor up
    back_c = hip + up_b * 0.34 * s - fwd_b * 0.02 * s
    S.box(back_c, (X, up_b, fwd_b), (0.20 * s, 0.33 * s, 0.03 * s), cushion)
    S.box(back_c - fwd_b * 0.05 * s, (X, up_b, fwd_b), (0.215 * s, 0.36 * s, 0.035 * s), frame)
    for sg in (1, -1):
        S.box(back_c + X * sg * 0.19 * s + fwd_b * 0.03 * s, (X, up_b, fwd_b), (0.025 * s, 0.28 * s, 0.045 * s), frame)
    rail_h = 0.85 * s
    for sg in (1, -1):
        S.box(hip - up_b * 0.15 * s + up_b * rail_h / 2 - fwd_b * 0.095 * s + X * sg * 0.12 * s, (X, up_b, fwd_b),
              (0.02 * s, rail_h / 2, 0.02 * s), frame)
    # parachute container (head box) on the back, headrest pad on its front face
    hb_c = hip + up_b * 0.86 * s - fwd_b * 0.045 * s
    hb_half = np.array([0.17, 0.15, 0.085]) * s
    if headbox_top is not None:
        top = (hb_c + up_b * hb_half[1])[1]
        if top > headbox_top:
            hb_c = hb_c - Y * (top - headbox_top)
    S.box(hb_c, (X, up_b, fwd_b), hb_half, frame, faces={(1, 1): 'dgrey'})
    S.box(hb_c + fwd_b * (hb_half[2] + 0.012 * s) - up_b * 0.04 * s, (X, up_b, fwd_b),
          (0.11 * s, 0.09 * s, 0.012 * s), cushion)
    # harness: shoulder straps down the back, lap belt and buckle on the pan
    for sg in (1, -1):
        S.box(back_c + fwd_b * 0.034 * s + X * sg * 0.055 * s + up_b * 0.06 * s, (X, up_b, fwd_b),
              (0.018 * s, 0.26 * s, 0.004), 'mgrey')
    S.box(pan_c + up_p * 0.04 * s + fwd_p * 0.05 * s, (X, up_p, fwd_p), (0.18 * s, 0.006, 0.014 * s), 'mgrey')
    S.box(pan_c + up_p * 0.044 * s + fwd_p * 0.05 * s, (X, up_p, fwd_p), (0.03 * s, 0.009, 0.018 * s), 'metal')
    # the ejection handle: a yellow loop standing at the front edge of the pan, between the knees
    hp = pan_c + fwd_p * 0.21 * s + up_p * 0.05 * s
    for sg in (1, -1):
        S.box(hp + X * sg * 0.045 * s, (X, up_p, fwd_p), (0.008 * s, 0.035 * s, 0.008 * s), 'yellow')
    S.box(hp + up_p * 0.035 * s, (X, up_p, fwd_p), (0.053 * s, 0.008 * s, 0.008 * s), 'yellow')
    S.box(hp - up_p * 0.035 * s, (X, up_p, fwd_p), (0.053 * s, 0.008 * s, 0.008 * s), 'yellow')
    S.ctx['seat'] = dict(hip=hip, pan=pan_c, back=back_c, up=up_b, fwd=fwd_b)
    return hip


# ---------------------------------------------------------------- sticks and throttles

def stick_side(S, foot, cant=10, grip_tilt=12, armrest=True):
    """a side stick on a housing at the console's front edge, canted [cant] degrees inboard; the housing stays
    with the cockpit, the grip goes to the stick bone"""
    E, s = S.E, S.s
    foot = np.array(foot, float)
    sd = 1 if foot[0] > E[0] else -1                                 # which wall it is on
    S.stick_foot = foot
    S.box(foot - Y * 0.015 * s, (X, Y, Z), (0.055 * s, 0.015 * s, 0.075 * s), 'dgrey')          # housing
    S.box(foot - Y * 0.002 * s, (X, Y, Z), (0.04 * s, 0.004 * s, 0.045 * s), 'mgrey')           # boot plate
    c_a = math.radians(cant)
    up = unit([-sd * math.sin(c_a), math.cos(c_a), 0.0])
    g_a = math.radians(grip_tilt)
    g_up = unit(up * math.cos(g_a) + Z * math.sin(g_a))
    g_fw = unit(np.cross(X, g_up))
    g_rt = unit(np.cross(g_up, g_fw))
    st = S.stick
    st.box(foot + up * 0.03 * s, (g_rt, g_up, g_fw), (0.016 * s, 0.03 * s, 0.016 * s), S.c['dgrey'])  # boot
    gc = foot + up * 0.045 * s + g_up * 0.06 * s
    st.box(gc, (g_rt, g_up, g_fw), (0.02 * s, 0.062 * s, 0.028 * s), S.c['black'])
    st.box(gc + g_up * 0.066 * s, (g_rt, g_up, g_fw), (0.018 * s, 0.006 * s, 0.024 * s), S.c['dgrey'])
    st.box(gc + g_up * 0.07 * s + g_fw * 0.012 * s, (g_rt, g_up, g_fw), (0.006 * s, 0.004 * s, 0.006 * s), S.c['red'])
    st.box(gc + g_up * 0.03 * s + g_fw * 0.03 * s, (g_rt, g_up, g_fw), (0.007 * s, 0.012 * s, 0.004 * s), S.c['metal'])
    st.box(gc + g_up * 0.02 * s - g_rt * sd * 0.022 * s, (g_rt, g_up, g_fw), (0.004 * s, 0.01 * s, 0.01 * s), S.c['mgrey'])
    if armrest:
        S.box(foot - Z * 0.17 * s + Y * 0.025 * s, (X, Y, Z), (0.045 * s, 0.012 * s, 0.10 * s), 'lgrey')
        S.box(foot - Z * 0.17 * s + Y * 0.006 * s, (X, Y, Z), (0.012 * s, 0.008 * s, 0.09 * s), 'dgrey')
    return foot


def stick_centre(S, foot, top_y, grip='b8', lean=4):
    """a centre stick: boot on the floor, column leaning [lean] degrees forward, the grip on top"""
    E, s = S.E, S.s
    foot = np.array(foot, float)
    S.stick_foot = foot
    S.box(foot + Y * 0.03 * s, (X, Y, Z), (0.045 * s, 0.04 * s, 0.045 * s), 'dgrey')
    col_up, col_fw = rot_x(-lean)
    Lc = max(top_y - 0.05 * s - foot[1], 0.15 * s)
    st = S.stick
    st.box(foot + col_up * Lc / 2, (X, col_up, col_fw), (0.011 * s, Lc / 2, 0.011 * s), S.c['metal'])
    g_up, g_fw = rot_x(-12)
    gc = foot + col_up * Lc + g_up * 0.055 * s
    if grip == 'spade':                                           # WWII British spade grip
        st.box(gc + g_up * 0.03 * s, (X, g_up, g_fw), (0.07 * s, 0.05 * s, 0.012 * s), S.c['black'])
        st.box(gc + g_up * 0.03 * s, (X, g_up, g_fw), (0.045 * s, 0.03 * s, 0.013 * s), S.c['dgrey'])
    else:
        st.box(gc, (X, g_up, g_fw), (0.021 * s, 0.062 * s, 0.027 * s), S.c['black'])
        st.box(gc + g_up * 0.066 * s, (X, g_up, g_fw), (0.017 * s, 0.006 * s, 0.022 * s), S.c['dgrey'])
        st.box(gc + g_up * 0.07 * s + g_fw * 0.010 * s, (X, g_up, g_fw), (0.006 * s, 0.004 * s, 0.006 * s), S.c['red'])
        st.box(gc + g_fw * 0.031 * s + g_up * 0.015 * s, (X, g_up, g_fw), (0.008 * s, 0.012 * s, 0.005 * s), S.c['metal'])
    return foot


def throttle_f16(S, x, z, y=None):
    """the F-16 throttle: a long grip with the radar cursor and antenna knobs, on a track along the console"""
    E, s = S.E, S.s
    y = S.ctx.get('cy', S.t.console_y) if y is None else y
    S.box([x, y + 0.012 * s, z], (X, Y, Z), (0.045 * s, 0.012 * s, 0.14 * s), 'black')                 # track housing
    S.box([x, y + 0.026 * s, z], (X, Y, Z), (0.008 * s, 0.004 * s, 0.12 * s), 'dgrey')                  # the slot
    g_up, g_fw = rot_x(-25)
    base = np.array([x, y + 0.03 * s, z])
    S.box(base + g_up * 0.03 * s, (X, g_up, g_fw), (0.012 * s, 0.03 * s, 0.014 * s), 'metal')           # the stem
    gc = base + g_up * 0.085 * s
    S.box(gc, (X, g_up, g_fw), (0.028 * s, 0.045 * s, 0.05 * s), 'black')                               # grip
    S.box(gc + g_up * 0.03 * s - g_fw * 0.04 * s, (X, g_up, g_fw), (0.02 * s, 0.02 * s, 0.02 * s), 'black')  # hump
    S.box(gc + X * 0.03 * s + g_fw * 0.02 * s, (X, g_up, g_fw), (0.006 * s, 0.01 * s, 0.01 * s), 'mgrey')  # cursor
    S.box(gc + g_up * 0.05 * s + g_fw * 0.012 * s, (X, g_up, g_fw), (0.008 * s, 0.006 * s, 0.008 * s), 'red')
    S.box(gc - X * 0.03 * s - g_fw * 0.015 * s, (X, g_up, g_fw), (0.004 * s, 0.012 * s, 0.012 * s), 'mgrey')
    S.ctx['throttle'] = (x, z)


def throttle_lever(S, x, z, y=None, lean=25, colour='black'):
    """a simple throttle lever in a quadrant on the console (jets of the 1950s to 70s, Soviet RUD)"""
    E, s = S.E, S.s
    y = S.ctx.get('cy', S.t.console_y) if y is None else y
    S.box([x, y + 0.02 * s, z], (X, Y, Z), (0.035 * s, 0.02 * s, 0.09 * s), 'black')
    g_up, g_fw = rot_x(-lean)
    S.box(np.array([x, y + 0.04 * s, z]) + g_up * 0.04 * s, (X, g_up, g_fw), (0.009 * s, 0.045 * s, 0.009 * s), 'metal')
    gc = np.array([x, y + 0.04 * s, z]) + g_up * 0.12 * s
    S.box(gc, (X, g_up, g_fw), (0.03 * s, 0.045 * s, 0.04 * s), colour)
    S.box(gc + g_up * 0.05 * s, (X, g_up, g_fw), (0.026 * s, 0.01 * s, 0.032 * s), 'dgrey')
    S.box(gc - X * 0.023 * s + g_up * 0.015 * s + g_fw * 0.025 * s, (X, g_up, g_fw), (0.004, 0.008 * s, 0.008 * s), 'red')
    S.ctx['throttle'] = (x, z)


# ---------------------------------------------------------------- small fittings

def lamp_block(S, name, c, R, U, w, h, depth=0.04, label=''):
    """an eyebrow / warning lamp block standing on the glareshield: a housing with its lamp face toward the pilot,
    registered as a plate for the lamps"""
    p = Plate(name, c, R, U, w, h, label)
    S.plates[name] = p
    S.box(p.c - p.N * depth / 2, (p.R, p.U, p.N), (w / 2 + 0.008, h / 2 + 0.008, depth / 2), 'dgrey',
          faces={(2, 1): 'black'})
    return p


def knob(S, p, N, U, r=0.012, h=0.014, pointer=True):
    L.knob(S.m, p, N, U, S.s, S.c, r=r, h=h, pointer=pointer)


def toggle(S, p, R, U, N, tilt=1, col=None):
    L.toggle(S.m, p, R, U, N, S.s, S.c, tilt=tilt, col=S.c[col] if col else None)


def gear_handle(S, p, R, U, N, size=1.0):
    """a landing gear lever: plate, stem and the red wheel knob"""
    s = S.s * size
    S.box(p + N * 0.006, (R, U, N), (0.016 * s, 0.03 * s, 0.006), 'black')
    S.box(p + N * 0.030 + U * 0.012 * s, (R, U, N), (0.004 * s, 0.022 * s, 0.024), 'metal')
    S.m.cyl(p + N * 0.05 + U * 0.03 * s, N, U, 0.012 * s, 0.012 * s, S.c['red'], cap=S.c['red'])


# ---------------------------------------------------------------- finishing

def finish(S, roof=True):
    """deck plates over what nothing covers, the clip to the skin; -> (mesh, stick, gauges, displays, glass, hud)"""
    t, m = S.t, S.m
    m.roof = []
    m.roofed = L.roof(m, S.ac, t, S.c) if roof else 0
    m.clipped = L.clip_to_skin(m, S.ac, t)
    return m, S.stick, S.gauges, S.displays, S.glass, S.hud


def build(vid, ac, tub, sw, spec, write_textures=True):
    """-> (mesh, stick, gauges, displays, glass, hud, stick_foot, plates, report)"""
    mod = module(vid)
    colours = dict(L.PALETTES[spec.get('palette', 'nato')])
    colours.update(getattr(mod, 'COLOURS', {}))
    atlas = None
    try:
        atlas = Atlas(ac.geo, L.texture_path(vid))
        if not atlas.areas:
            atlas = None
    except Exception as failure:              # no free texture space: the texture's own texel blocks then
        print(f'{vid}: no atlas ({failure!r})')
    S = Station(ac, tub, sw, colours, atlas)
    mod.build(S)
    m, stick, gauges, displays, glass, hud = finish(S)
    rep = 'colours from the texture'
    if atlas:
        rep = f'{len(atlas.tiles)} atlas tiles in {len(atlas.areas)} free areas ({atlas.used()} texels)'
        if write_textures:
            tex = os.path.join(L.TEX_DIR, vid + '.png')
            dead = os.path.join(L.TEX_DIR, '..', 'entity_dead', vid + '.png')
            _, dead_ok = atlas.write(tex, dead)
            rep += ', textures written' + ('' if dead_ok else ' (entity_dead not had: left as it is)')
    return m, stick, gauges, displays, glass, hud, S.stick_foot, S.describe_plates(), rep
