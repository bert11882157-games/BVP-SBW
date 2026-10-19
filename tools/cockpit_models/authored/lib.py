"""Shared parts of the authored (per-type, reference-built) cockpits.

An authored cockpit is written in real metres relative to the pilot's design eye: r to the pilot's right, u up,
f forward. Eye(tub) maps that frame into vehicle-local blocks (x is the pilot's left), scaled by the fitted tub's
scale. Painted faces (panel placards, console panels, keypads) go into free areas of the vehicle's own texture
(free_atlas.py), packed by Atlas; the burnt copy goes into textures/entity_dead.
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
import cockpit_lib as L  # noqa: E402
import free_atlas as FA  # noqa: E402

FONTS = {False: ['C:/Windows/Fonts/arial.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'],
         True: ['C:/Windows/Fonts/arialbd.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf']}
MONO = ['C:/Windows/Fonts/consolab.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf']


def font(px, bold=False, mono=False):
    for p in (MONO if mono else FONTS[bold]):
        try:
            return ImageFont.truetype(p, max(6, int(px)))
        except OSError:
            continue
    return ImageFont.load_default()


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


class Eye:
    """eye-relative metres (r right, u up, f forward) -> vehicle-local blocks"""

    def __init__(self, tub, k=None):
        self.E = np.array(tub.E, float)
        self.k = tub.s if k is None else k
        self.R, self.U, self.F = np.array([-1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])

    def p(self, r, u, f):
        return self.E + (self.R * r + self.U * u + self.F * f) * self.k

    def d(self, r, u, f):
        """a direction in the eye frame, as a vehicle-local unit vector"""
        return unit(self.R * r + self.U * u + self.F * f)

    def m(self, x):
        """a length in metres -> blocks"""
        return x * self.k


class Plane:
    """a flat panel: origin o (vehicle), right and up unit vectors, normal toward the pilot. at(a, b) is the point a
    metres right and b metres up of the origin along the face (scaled), lifted off the face by `lift` blocks."""

    def __init__(self, eye, o, right, up):
        self.eye = eye
        self.o = np.array(o, float)
        self.R = unit(right)
        self.U = unit(np.array(up, float) - self.R * (self.R @ unit(up)))
        self.N = unit(np.cross(self.U, self.R))     # right x up order: points out of the face toward the viewer
        # make N face the pilot
        if self.N @ (eye.E - self.o) < 0:
            self.N = -self.N

    def at(self, a, b, lift=0.0):
        return self.o + (self.R * a + self.U * b) * self.eye.k + self.N * lift

    def axes(self):
        return (self.R, self.U, self.N)


def tilted(eye, centre_ruf, lean_deg, turn_deg=0.0):
    """a panel plane at an eye-frame point: its top leans away from the pilot by lean_deg, and it is turned about the
    vertical by turn_deg (positive turns its face toward the pilot's left)"""
    lean, turn = math.radians(lean_deg), math.radians(turn_deg)
    right = eye.d(math.cos(turn), 0, math.sin(turn))
    up = eye.d(0, math.cos(lean), math.sin(lean))
    return Plane(eye, eye.p(*centre_ruf), right, up)


# ---------------------------------------------------------------- texture atlas in the free areas

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


# ---------------------------------------------------------------- mesh helpers (cockpit_lib.Mesh)

def quad(m, atlas, corners, rect, normal, mirror=False):
    """a textured quad: corners bottom-left, bottom-right, top-right, top-left as seen from its front"""
    st = [(0, 0), (1, 0), (1, 1), (0, 1)]
    if mirror:
        st = [(1 - s, t) for s, t in st]
    m.face(corners, [atlas.uv(rect, s, t) for s, t in st], normal=normal)


def plate(m, atlas, plane, a, b, w, h, rect, proud=0.004, edge=None):
    """a painted plate on a plane: centre (a, b) metres, w x h metres, standing `proud` blocks off the face, with
    side walls in the edge colour (a swatch uv)"""
    k = plane.eye.k
    c = plane.at(a, b)
    R, U, N = plane.axes()
    hw, hh = w * k / 2, h * k / 2
    f = c + N * proud
    quad(m, atlas, [f - R * hw - U * hh, f + R * hw - U * hh, f + R * hw + U * hh, f - R * hw + U * hh], rect, N)
    if edge is not None and proud > 0:
        pts = [f - R * hw - U * hh, f + R * hw - U * hh, f + R * hw + U * hh, f - R * hw + U * hh]
        for i in range(4):
            p, q = pts[i], pts[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q - N * proud, p - N * proud], edge, toward=mid + (mid - f))


def slab(m, plane, a0, a1, b0, b1, face_uv, edge_uv, depth=0.02, face_rect=None, atlas=None, outline=None):
    """the panel board itself: a slab from (a0, b0) to (a1, b1) on the plane, its face painted (face_rect) or flat.
    outline: optional [(a, b)] polygon replacing the rectangle (convex, counter-clockwise seen from the front)"""
    pts2 = outline or [(a0, b0), (a1, b0), (a1, b1), (a0, b1)]
    P = [plane.at(a, b) for a, b in pts2]
    if face_rect is not None:
        uvs = [atlas.uv(face_rect, (a - a0) / (a1 - a0), (b - b0) / (b1 - b0)) for a, b in pts2]
        m.face(P, uvs, normal=plane.N)
    else:
        m.flat(P, face_uv, normal=plane.N)
    back = -plane.N * depth
    m.flat([p + back for p in P], edge_uv, normal=-plane.N)
    c = np.mean(P, 0)
    for i in range(len(P)):
        p, q = P[i], P[(i + 1) % len(P)]
        mid = (p + q) / 2
        m.flat([p, q, q + back, p + back], edge_uv, toward=mid + (mid - c))


# ---------------------------------------------------------------- painting

def noisy(im, colour, amount=4, seed=1):
    rng = np.random.default_rng(seed)
    a = np.zeros((im.height, im.width, 4), np.float32)
    a[..., :3] = colour; a[..., 3] = 255
    n = rng.normal(0, amount, (im.height // 8 + 1, im.width // 8 + 1))
    n = np.kron(n, np.ones((8, 8)))[:im.height, :im.width]
    a[..., :3] += n[..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)), (0, 0))


def text(d, xy, s, px, fill=(226, 228, 220), anchor='mm', bold=True, mono=False):
    d.text(xy, s, font=font(px, bold, mono), fill=tuple(fill) + (255,) if len(fill) == 3 else fill, anchor=anchor)


def screws(d, W, H, inset, r, colour=(70, 72, 74)):
    for x, y in ((inset, inset), (W - inset, inset), (inset, H - inset), (W - inset, H - inset)):
        d.ellipse([x - r, y - r, x + r, y + r], fill=tuple(colour) + (255,), outline=(20, 20, 20, 255))
        d.line([x - r * 0.7, y, x + r * 0.7, y], fill=(30, 30, 30, 255), width=max(1, int(r * 0.3)))


class Painter:
    """paints a panel face tile in metres: px_per_m pixels per metre at the final (downsampled) size; to(a, b) maps
    plane metres (a right, b up, from the tile's bottom-left corner (a0, b0)) to SS pixels"""

    def __init__(self, a0, a1, b0, b1, px_per_m=900):
        self.a0, self.a1, self.b0, self.b1 = a0, a1, b0, b1
        self.ppm = px_per_m
        self.W = int(round((a1 - a0) * px_per_m)); self.H = int(round((b1 - b0) * px_per_m))
        self.S = Atlas.SS * px_per_m                 # SS pixels per metre

    def to(self, a, b):
        return ((a - self.a0) * self.S, (self.b1 - b) * self.S)

    def box(self, a, b, w, h):
        x, y = self.to(a, b)
        return [x - w / 2 * self.S, y - h / 2 * self.S, x + w / 2 * self.S, y + h / 2 * self.S]

    def mm(self, x):
        """x millimetres in SS pixels"""
        return x / 1000 * self.S
