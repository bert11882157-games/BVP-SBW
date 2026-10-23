"""Building blocks for the hand-built cockpits (one file per aircraft, as mig_29.py): the mesh with textured faces, the
painted dial and lamp artwork, the panel facets, and writing the result into the aircraft's geo, asset and textures.

Nothing here measures or places anything: every position, size and layout lives in the aircraft's own file, taken by
hand from its model (sections.py, eyeview.py) and its references. Frames: vehicle-local blocks (x left, y up,
z forward); model pixels are (x, y, -z) * 16.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, 'authored'))
import cockpit_lib as L  # noqa: E402
import lib as A          # noqa: E402

RIM = 0.16                     # game gauge bezel width (CockpitGauges.RIM)
GAUGE_LIFT = 0.004             # game dials stand this far proud of the panel face
DISPLAY_LIFT = 0.003
WHITE = (226, 228, 220)
RED = (196, 36, 30)
AMBER = (214, 140, 30)
GREEN_LAMP = (60, 150, 70)


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


# ---------------------------------------------------------------- mesh

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


# ---------------------------------------------------------------- panel facets

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


def facet_box(m, f, a, y, w, h, depth, rect, front=None, lift=0.0025):
    """a box standing on facet f at (a, y): w x h across the panel, [depth] toward the pilot, its back face [lift]
    off the panel face (so nothing lies on the panel); front: a rect for the face toward the pilot"""
    m.box(f.at(a, y, lift + depth / 2), (f.R, f.U, f.N), (w / 2, h / 2, depth / 2), rect,
          front=(2, 1, front, f.R, f.U) if front is not None else None)


def hood(m, f, a, y, w, h, depth, rect, t=0.004, lift=0.0025):
    """a hood over a display w x h at (a, y) on facet f: a top plate and two cheeks [depth] toward the pilot, just
    outside the display's bezel"""
    facet_box(m, f, a, y + h / 2 + 0.008 + t / 2, w + 0.016 + 2 * t, t, depth, rect, lift=lift)
    for s in (-1, 1):
        facet_box(m, f, a + s * (w / 2 + 0.008 + t / 2), y + h * 0.2, t, h * 0.6, depth, rect, lift=lift)


def k36_handle(m, at, red, black):
    """the K-36 ejection handle on the seat front: a red double loop over a black root block; at(x, y) gives the
    vehicle point x metres across and y metres up from the handle's root, on the seat front"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    for s in (1, -1):
        for xl in (0.018, 0.088):
            m.box(at(s * xl, 0.033), (X, Y, Z), (0.007, 0.032, 0.006), red)
        m.box(at(s * 0.053, 0.07), (X, Y, Z), (0.042, 0.007, 0.0065), red)
    m.box(at(0, 0.0), (X, Y, Z), (0.03, 0.01, 0.009), black)


# ---------------------------------------------------------------- painting

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


def glare(d, S):
    d.arc([S * .12, S * .12, S * .88, S * .88], 200, 250, fill=(255, 255, 255, 40), width=int(S * .05))


def dial_face(d, im, title, unit_, lo, hi, step, label_step, value, start=-135, sweep=270, labels=None, red=None):
    S = im.width; c = S / 2; R = S / 2 - S * 0.02
    d.ellipse([c - R, c - R, c + R, c + R], fill=(14, 15, 16, 255))

    def ang(v):
        return math.radians(start + (v - lo) / (hi - lo) * sweep)
    if red:
        for v0 in np.arange(red[0], red[1], (hi - lo) / 200):
            a = ang(v0)
            d.line([c + R * .80 * math.sin(a), c - R * .80 * math.cos(a), c + R * .93 * math.sin(a),
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
    if unit_:
        text(d, (c, c - R * .32), unit_, S * .075)
    a = ang(value)
    d.line([c - .15 * R * math.sin(a), c + .15 * R * math.cos(a), c + .80 * R * math.sin(a), c - .80 * R * math.cos(a)],
           fill=(240, 240, 232, 255), width=int(S * .045))
    d.ellipse([c - S * .05, c - S * .05, c + S * .05, c + S * .05], fill=(60, 60, 60, 255))
    glare(d, S)


def lamp(d, box, colour, label=None, lit=False, px=None):
    x0, y0, x1, y1 = box
    base = tuple(int(c * (1.0 if lit else 0.45)) for c in colour)
    d.rectangle(box, fill=base + (255,), outline=(10, 10, 10, 255), width=2)
    d.rectangle([x0 + 3, y0 + 3, x1 - 3, y0 + (y1 - y0) * 0.35], fill=tuple(min(255, c + 30) for c in base) + (255,))
    if label:
        text(d, ((x0 + x1) / 2, (y0 + y1) / 2), label, px or (y1 - y0) * .42, fill=(20, 20, 20))


def p_lamp_block(rows, cols_n=1):
    """a block of caption lamps: rows = [(label, colour)], laid out in cols_n columns"""
    def fn(d, im):
        S, H = im.width, im.height
        d.rectangle([0, 0, S, H], fill=(20, 22, 22, 255))
        per = (len(rows) + cols_n - 1) // cols_n
        w = S / cols_n; h = H / per
        for k, (lab, col) in enumerate(rows):
            cx, cy = k // per, k % per
            lamp(d, [cx * w + 6, cy * h + 4, (cx + 1) * w - 6, (cy + 1) * h - 4], col, lab, px=h * .3)
    return fn


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


def remove_sight_clutter(ac, E, reach=1.2):
    """the model's own old HUD or gunsight left in the pilot's line of sight: interior-bone faces wholly in the box
    ahead of the eye (|x| < 0.30, 0.40 below to 0.22 above the eye, inside the canopy, 0.25 m to [reach] ahead) that do not lie along
    the canopy glass (its frame stays); returns how many went"""
    e = np.array(E, float)
    n = 0
    G = ac.glass

    def behind_glass(c):                       # outside the canopy: the glass lies between the eye and it
        if not len(G):
            return False
        d = c - e; L_ = np.linalg.norm(d); d = d / L_
        e1, e2 = G[:, 1] - G[:, 0], G[:, 2] - G[:, 0]
        h = np.cross(d, e2); a = (e1 * h).sum(1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1.0 / np.where(ok, a, 1.0), 0.0)
        s = e - G[:, 0]; u = f * (s * h).sum(1)
        q = np.cross(s, e1); v = f * (q @ d); tt = f * (q * e2).sum(1)
        return bool((ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (tt > 0) & (tt < L_ - 0.01)).any())

    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']) or b['name'].endswith('__cockpit'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        out = []
        for poly in pm['polys']:
            if poly:
                V = P[[v[0] for v in poly]]
                d = V - e
                if (np.all(np.abs(d[:, 0]) < 0.30) and np.all(d[:, 1] > -0.40) and np.all(d[:, 1] < 0.22)
                        and np.all(d[:, 2] > 0.25) and np.all(d[:, 2] < reach) and not ac.hugs_glass(V, 0.04)
                        and not behind_glass(V.mean(0))):
                    n += 1
                    continue
            out.append(poly)
        pm['polys'] = out
    return n


def view_ceiling(ac, E, dz, half_w=0.05, reach=4.0):
    """how far above the eye (metres, at dz ahead of it) the pilot sees forward over the nose before the model's
    own structure (the windscreen bow, the canopy frame) cuts the view: the lowest blocked elevation, scanned up from
    level at the glass's centre and both edges, as a height at dz. A HUD or sight glass taller than twice this would
    have its top hidden behind the bow"""
    T = []
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or not L.is_interior_bone(b['name']):
            continue                             # the airframe only (not the propeller disc, not the weapons)
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for poly in pm['polys']:
            if poly:
                V = P[[v[0] for v in poly]]
                for k in range(1, len(V) - 1):
                    T.append(V[[0, k, k + 1]])
    T = np.array(T)
    c = T.mean(1)
    near = np.all(np.abs(c - E) < reach + 1.0, axis=1)
    T = T[near]
    e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]

    def hits(p, d):
        h = np.cross(d, e2); a = (e1 * h).sum(1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1.0 / np.where(ok, a, 1.0), 0.0)
        s = p - T[:, 0]; u = f * (s * h).sum(1)
        q = np.cross(s, e1); v = f * (q @ d); t = f * (q * e2).sum(1)
        return bool((ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (t > dz * 0.5) & (t < reach)).any())
    best = 1.0
    for x in (-half_w, 0.0, half_w):
        clear = False                            # the first block above a clear view (the nose below is not it)
        for k in range(-10, 61):
            el = math.radians(k * 0.5)
            d = np.array([x / dz, math.tan(el), 1.0]); d /= np.linalg.norm(d)
            blocked = hits(np.array(E, float), d)
            if blocked and clear and k > 0:
                best = min(best, dz * math.tan(el))
                break
            clear = clear or not blocked
    return best


# ---------------------------------------------------------------- writing into the aircraft

def remove_faces(ac, keep_out):
    """drops the faces of interior bones whose centre lies in any box (lo, hi) of keep_out (vehicle-local);
    returns {bone: count}"""
    count = {}
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']) or b['name'].endswith('__cockpit'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        out = []
        n = 0
        for poly in pm['polys']:
            if poly:
                cc = P[[v[0] for v in poly]].mean(0)
                if any(np.all(cc >= lo) and np.all(cc <= hi) for lo, hi in keep_out):
                    n += 1
                    continue
            out.append(poly)
        if n:
            pm['polys'] = out
            count[b['name']] = n
    return count


def install(ac, mesh, stick, source, stick_bone, gauges, displays, hud, eye, seat=0):
    """the cockpit bone after [source], the stick into [stick_bone] (pivot at the stick's foot, given by the
    caller in stick['foot']), the instruments and the HUD into the asset; no glass"""
    pm = mesh.poly_mesh()
    piece = source.split('__', 1)[0]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
    hull = ac.bone('hull')
    bone = {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == source) + 1
    ac.bones.insert(at, bone)
    rig = ac.asset.get('AircraftRig')
    if stick is not None:
        mesh_s, foot = stick
        px = r5(np.array(foot) * np.array([16.0, 16.0, -16.0]))
        for b in ac.bones:
            if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
                b['pivot'] = px
                if b['name'].endswith('__control_stick_pitch'):
                    b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        ac.bone(stick_bone)['poly_mesh'] = mesh_s.poly_mesh()
        for sf in (rig or {}).get('Surfaces', []):
            if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
                sf['Pivot'] = px
    a = ac.asset
    old_g = [g for g in (a.get('CockpitGauges') or {}).get('Gauges', []) if g.get('Seat', 0) != seat]
    old_d = [x for x in (a.get('FlightDisplays') or {}).get('Displays', []) if x.get('Seat', 0) != seat]
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': old_g + gauges}
    fd = a.get('FlightDisplays') or {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS'}
    fd['Displays'] = old_d + displays
    fd.setdefault('Schema', 1); fd.setdefault('Frame', 'VEHICLE_LOCAL_BLOCKS')
    a['FlightDisplays'] = fd
    if not fd['Displays']:
        a.pop('FlightDisplays')
    if hud:
        a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(eye)}
        if hud.get('style'):                    # a gunsight's reticle instead of HUD symbology (GYRO, REFLECTOR)
            a['HeadUpDisplay']['Style'] = hud['style']
    else:
        a.pop('HeadUpDisplay', None)
    a.pop('CanopyGlass', None)                  # no glass for now (the owner's call, 2026-10-20)
    return pm


def write(ac, atlas):
    with open(ac.geo_path, 'w') as fh:
        json.dump(ac.geo, fh, separators=(',', ':'))
    L.write_asset(ac.asset_path, ac.asset)
    tex = os.path.normpath(L.texture_path(ac.vid))
    atlas.write(tex)
    dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
    if os.path.isfile(dead):
        atlas.write(dead, dead=True)
