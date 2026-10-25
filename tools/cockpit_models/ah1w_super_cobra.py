#!/usr/bin/env python3
"""AH-1W SuperCobra cockpits (the 1980s-90s analogue Cobra: no glass cockpit), built by hand for the AH-1W.mtb airframe: one standalone
script (as a_10a.py, f_15c.py and mig_29.py), with its own panels. Tandem seats under one canopy, the PILOT in the rear and
higher, the GUNNER in the front and lower, as on the real AH-1W.

The airframe (a skeleton hull of boxes, see canopy_ah1w_super_cobra.py) carries a rough old interior: two seat backs, two panel
slabs, two floor strips, a small sight stand. This script cuts out the five loose parts that the new cockpits replace (the pilot's
panel slab, the gunner's panel slab, the gunner's seat back and the two sight parts; the pilot's seat back and floor strip stay: the
new seat and floor lie on them) and builds:

PILOT (rear, eye (0, 3.062, 3.345)):
  * a bulkhead block that carries the instrument panel face (leaning back 6 degrees, as the old slab did) under a visor, the
    analogue panel: ADI over the HSI in the centre, airspeed and altimeter either side of them, vertical speed and turn / slip
    under those, rotor rpm and torque in the outer columns, twin engine N1 and gas temperature (left), fuel quantity,
    radar altimeter and clock (right), the caution / warning annunciator and the armament panel (painted) underneath;
  * the pilot's head-up display (owner: the AH-1W pilot has a HUD): one unit on the glareshield, a low control box with its strip toward
    the pilot under a narrow pedestal, and ONE combiner (15% over life size, slim bezel, nothing beside it) centred on the eye line;
    HeadUpDisplay style HUD, the game paints the symbology on it;
  * the armoured seat (back cushion on the old back plate, pan, bolsters, harness), the cyclic between the knees, the
    collective on the left console with its twist grip, two consoles, the rudder pedals, a rear liner.
GUNNER (front, eye (0, 2.62, 4.684): 0.44 m lower than the pilot's, over a floor 0.46 m lower, the real stepped tandem layout):
  * the TSU sight hood (the Telescopic Sight Unit's viewfinder: a boxy hood standing out of his panel with a rubber eyecup toward
    his face) over a small instrument panel: his airspeed and altimeter, torque, rounds counter, a stores display, the
    caution lamps and the gun / rocket / missile control panel (painted); the nose deck closed in front;
  * the armoured seat, his hand controllers on the side shelves: the short side-stick cyclic on the right, the collective
    on the left, rudder pedals.
The pilot's panel keeps the old slab's plane; the gunner's panel stands 0.25 m closer to the gunner than the old slab did (an eye
0.7 m behind the panel and a hood to reach him).

Live gauges only through CockpitGauges / FlightDisplays / HeadUpDisplay (rotorcraft kinds ROTOR_RPM and TORQUE are in the game's
GaugePainter); everything else is a painted face. The sticks are fixed (the airframe has no stick rig).

Order: the restore of the airframe's files, canopy_ah1w_super_cobra.py, then this script (it appends the sight glass to the
canopy's CanopyGlass). Running it on its own output rebuilds the same result (the old interior parts are gone, the bones
are replaced).

Frames: everything below is absolute vehicle blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is written in
model pixels (x, y, -z) * 16.

Usage: python tools/cockpit_models/ah1w_super_cobra.py [--dry-run]
"""
import json, math, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import throttle_lib
THR = []                                 # the throttle levers (throttle_lib), one bone each
from PIL import Image, ImageDraw, ImageFont
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

HERE = os.path.dirname(os.path.abspath(__file__))
VID = 'ah1w_super_cobra'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
DATA = os.path.join(GEN, f'data/berts_vehicle_pack/sbw/vehicles/{VID}.json')
TEX = os.path.join(GEN, f'assets/berts_vehicle_pack/textures/entity/{VID}.png')
TEX_DEAD = os.path.join(GEN, f'assets/berts_vehicle_pack/textures/entity_dead/{VID}.png')

BONE = 'hull__cockpit'
HULL_BONE = 'hull'
# free texture areas (x, y, w, h): no UV of the model, its fallback or the wreck lands there (the used area is x < 1024, y 1088..1700)
ATLAS = [(0, 0, 2048, 1060), (1032, 1080, 1016, 1096), (0, 1720, 1032, 456)]
TW, TH = 2048, 2176

EYE_P = (0.0, 3.06176, 3.345)           # the pilot's eye (vehicle data, unchanged)
EYE_G = (0.0, 2.62, 4.68375)            # the gunner's eye: was y 2.87051 (above the pilot's seat line, under the pilot's panel top)
SEAT_BELOW = 1.65112                    # the seat position relative to the eye, as the airframe's data has it (both seats)

# old interior parts replaced by the new cockpits (loose connected parts of the hull piece): bounding boxes (lo, hi)
OLD_INTERIOR = [((-0.414, 1.445, 4.059), (0.414, 2.803, 4.452)),      # the pilot's panel slab
                ((-0.414, 1.409, 5.625), (0.414, 2.513, 5.992)),      # the gunner's panel slab
                ((-0.383, 1.473, 4.493), (0.383, 2.749, 4.645)),      # the gunner's seat back
                ((-0.096, 2.026, 5.451), (0.096, 2.387, 5.812)),      # sight parts
                ((-0.159, 2.297, 5.361), (0.159, 2.477, 5.542))]


def _font_files():
    out = [r'C:\Windows\Fonts\arialbd.ttf', r'C:\Windows\Fonts\arial.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
           '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf']
    try:
        import matplotlib
        d = os.path.join(os.path.dirname(matplotlib.__file__), 'mpl-data', 'fonts', 'ttf')
        out += [os.path.join(d, 'DejaVuSans-Bold.ttf'), os.path.join(d, 'DejaVuSans.ttf')]
    except ImportError:
        pass
    return out


FONT_FILES = _font_files()

# ---------------------------------------------------------------- colours (the Cobra's cockpit is dark grey and black, olive seats)

PANEL = (66, 70, 72)           # panel fields, consoles and liners are the Cobra's grey (FS 36231 dark gull gray), the instrument faces black
PANEL_D = (50, 53, 55)
BLACK = (16, 17, 19)
DGREY = (36, 38, 40)
MGREY = (84, 88, 90)
LGREY = (150, 154, 156)
FRAME = (44, 48, 44)           # seat structure
CUSHION = (60, 66, 52)         # the seat's olive drab
HARNESS = (82, 86, 64)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (44, 46, 47)
CONSOLE = (84, 88, 90)
WHITE = (226, 228, 220)
AMBER = (214, 140, 30)
GREEN_TXT = (80, 220, 100)
RUBBER = (14, 14, 15)


# ---------------------------------------------------------------- texture atlas

class Atlas:
    """Shelf-packed rectangles in the free texture areas. paint(w, h, fn) -> rect; fn(draw, img) paints an RGBA tile
    at SS x resolution, downsampled for clean edges."""
    SS = 4

    def __init__(self):
        self.free = [tuple(a) for a in ATLAS]
        self.tiles = []

    def alloc(self, w, h):
        best = None
        for i, (x, y, fw, fh) in enumerate(self.free):
            if w <= fw and h <= fh:
                score = min(fw - w, fh - h)
                if best is None or score < best[0]:
                    best = (score, i)
        if best is None:
            raise SystemExit(f'atlas full: no room for {w}x{h}')
        x, y, fw, fh = self.free.pop(best[1])
        if fw - w < fh - h:
            self.free += [(x + w, y, fw - w, h), (x, y + h, fw, fh - h)]
        else:
            self.free += [(x + w, y, fw - w, fh), (x, y + h, w, fh - h)]
        self.free = [r for r in self.free if r[2] > 0 and r[3] > 0]
        return (x, y, w, h)

    def used(self):
        return sum(r[2] * r[3] for r, _ in self.tiles)

    def paint(self, w, h, fn, bg=(0, 0, 0, 255)):
        r = self.alloc(w, h)
        img = Image.new('RGBA', (w * self.SS, h * self.SS), bg)
        fn(ImageDraw.Draw(img), img)
        self.tiles.append((r, img.resize((w, h), Image.LANCZOS)))
        return r

    def swatch(self, colour):
        return self.paint(8, 8, lambda d, im: None, bg=tuple(colour) + (255,))

    def write(self, path, dead=False):
        base = Image.open(path).convert('RGBA')
        for x, y, w, h in ATLAS:
            base.paste((0, 0, 0, 0), (x, y, x + w, y + h))
        for (rx, ry, rw, rh), img in self.tiles:
            if dead:
                a = np.asarray(img).astype(np.float32)
                g = a[..., :3].mean(-1, keepdims=True)
                a[..., :3] = (a[..., :3] * 0.25 + g * 0.15) * 0.9
                img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
            base.paste(img, (rx, ry))
        base.save(path, optimize=True)


def uv_of(rect, s, t):
    """normalized texture uv of (s, t) in a rect: s right, t up, both 0..1, kept half a texel inside"""
    x, y, w, h = rect
    s = 0.5 / w + s * (1 - 1.0 / w); t = 0.5 / h + t * (1 - 1.0 / h)
    return [(x + s * w) / TW, 1.0 - (y + (1 - t) * h) / TH]


def uv_flat(rect):
    return uv_of(rect, 0.5, 0.5)


# ---------------------------------------------------------------- mesh (absolute blocks in, model pixels out)

def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def r5(v):
    return [round(float(x), 5) + 0.0 for x in v]


X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def half_along(A, half, d):
    return sum(abs(float(a @ d)) * h for a, h in zip(A, half))


def frame(centre, normal, up):
    """(c, n, u, r): r = the right-hand direction seen from in front of the face (looking along -n)"""
    n = unit(normal)
    u = np.array(up, float); u = u - n * (u @ n); u /= np.linalg.norm(u)
    r = np.cross(u, n)
    return np.array(centre, float), n, u, r


class Mesh:
    def __init__(self):
        self.faces = []

    def face(self, pts, uvs, toward=None, normal=None):
        P = np.array(pts, float)
        n = np.zeros(3)
        for i in range(len(P)):
            a, b = P[i], P[(i + 1) % len(P)]
            n += np.array([(a[1] - b[1]) * (a[2] + b[2]), (a[2] - b[2]) * (a[0] + b[0]), (a[0] - b[0]) * (a[1] + b[1])])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        want = None
        if normal is not None:
            want = np.array(normal, float)
        elif toward is not None:
            want = np.array(toward, float) - P.mean(0)
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
        self.face(corners, [uv_of(rect, 0, 0), uv_of(rect, 1, 0), uv_of(rect, 1, 1), uv_of(rect, 0, 1)],
                  toward=toward, normal=normal)

    def flat(self, pts, rect, toward=None, normal=None):
        u = uv_flat(rect)
        self.face(pts, [u] * len(pts), toward=toward, normal=normal)

    def box(self, centre, axes, half, rect, front=None, skip=()):
        """oriented box; axes = 3 unit vectors, half sizes; front = (axis index, sign, rect, right, up): a textured face"""
        c = np.array(centre, float); A = [np.array(a, float) for a in axes]
        for k in range(3):
            for s in (-1, 1):
                if (k, s) in skip:
                    continue
                i, j = [m for m in range(3) if m != k]
                fc = c + A[k] * half[k] * s
                pts = [fc - A[i] * half[i] - A[j] * half[j], fc + A[i] * half[i] - A[j] * half[j],
                       fc + A[i] * half[i] + A[j] * half[j], fc - A[i] * half[i] + A[j] * half[j]]
                if front and front[0] == k and front[1] == s:
                    _, _, r, right, up = front
                    R = np.array(right, float); U = np.array(up, float)
                    pts = [fc - R * half_along(A, half, R) - U * half_along(A, half, U),
                           fc + R * half_along(A, half, R) - U * half_along(A, half, U),
                           fc + R * half_along(A, half, R) + U * half_along(A, half, U),
                           fc - R * half_along(A, half, R) + U * half_along(A, half, U)]
                    self.quad_tex(pts, r, normal=A[k] * s)
                else:
                    self.flat(pts, rect, normal=A[k] * s)

    def solid(self, faces, rect):
        """a convex solid from planar face loops; every face is turned to look away from the solid's centre"""
        allp = np.concatenate([np.array(f, float) for f in faces])
        c = allp.mean(0)
        for f in faces:
            self.flat(f, rect, toward=np.array(f, float).mean(0) * 2 - c)

    def yz_prism(self, prof, x0, x1, rect):
        """a convex prism: profile points (y, z) in order, extruded from x0 to x1"""
        L = [[x0, y, z] for y, z in prof]
        R = [[x1, y, z] for y, z in prof]
        faces = [L, R] + [[L[i], L[(i + 1) % len(L)], R[(i + 1) % len(L)], R[i]] for i in range(len(L))]
        self.solid(faces, rect)

    def xz_prism(self, prof, y0, y1, rect):
        """a convex prism: profile points (x, z) in order, extruded from y0 to y1"""
        B = [[x, y0, z] for x, z in prof]
        T = [[x, y1, z] for x, z in prof]
        faces = [B, T] + [[B[i], B[(i + 1) % len(B)], T[(i + 1) % len(B)], T[i]] for i in range(len(B))]
        self.solid(faces, rect)

    def sloped(self, plan0, plan1, y0, y1, rect):
        """a convex prism between two plans (x, z loops with the same vertex order) at heights y0 and y1"""
        B = [[x, y0, z] for x, z in plan0]
        T = [[x, y1, z] for x, z in plan1]
        n = len(B)
        faces = [B, T] + [[B[i], B[(i + 1) % n], T[(i + 1) % n], T[i]] for i in range(n)]
        self.solid(faces, rect)

    def cyl(self, p0, p1, r, rect, seg=8):
        p0, p1 = np.array(p0, float), np.array(p1, float)
        t = unit(p1 - p0)
        a = np.array([0, 1.0, 0]) if abs(t @ Y) < 0.9 else X
        u = unit(np.cross(t, a)); v = unit(np.cross(t, u))
        ring = lambda p: [p + r * (u * math.cos(2 * math.pi * (j + .5) / seg) + v * math.sin(2 * math.pi * (j + .5) / seg))
                          for j in range(seg)]
        A, B = ring(p0), ring(p1)
        for j in range(seg):
            j2 = (j + 1) % seg
            q = [A[j], A[j2], B[j2], B[j]]
            self.flat(q, rect, toward=np.mean(q, 0) * 2 - (p0 + p1) / 2)
        self.flat(A, rect, normal=-t)
        self.flat(B, rect, normal=t)

    def ring(self, centre, normal, up, r0, r1, proud, rect, seg=20, back=0.0):
        c, n, u, rr = frame(centre, normal, up)
        for k in range(seg):
            a0 = 2 * math.pi * k / seg; a1 = 2 * math.pi * (k + 1) / seg
            d0 = rr * math.cos(a0) + u * math.sin(a0); d1 = rr * math.cos(a1) + u * math.sin(a1)
            f = c + n * proud
            self.flat([f + d0 * r0, f + d0 * r1, f + d1 * r1, f + d1 * r0], rect, normal=n)
            self.flat([f + d0 * r1, c + d0 * r1 - n * back, c + d1 * r1 - n * back, f + d1 * r1], rect, normal=(d0 + d1) / 2)
            self.flat([f + d0 * r0, c + d0 * r0, c + d1 * r0, f + d1 * r0], rect, normal=-(d0 + d1) / 2)

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
            # The mesh loader mirrors X and takes (p1 - p0) x (p2 - p0) of the mirrored points as the face normal, so in file
            # pixels a face winds clockwise seen from its front (the hull's own faces do the same).
            if np.cross(b - a, c - a) @ npx > 0:
                px = px[::-1]; uvs = uvs[::-1]
            ni = len(N); N.append([round(float(x), 5) + 0.0 for x in -npx])
            poly = []
            for p, uv in zip(px, uvs):
                poly.append([len(P), ni, len(U)])
                P.append([round(x, 4) + 0.0 for x in p]); U.append([round(uv[0], 6), round(uv[1], 6)])
            polys.append(poly)
        return {'normalized_uvs': True, 'positions': P, 'normals': N, 'uvs': U, 'polys': polys}


# ---------------------------------------------------------------- geometry of the stepped tandem

FLOOR_P, FLOOR_G = 1.930, 1.470
WALL = 0.505                               # the tub's half width (the sill rails' inner faces stand at 0.51)
UPPER = 0.440                              # the bulkhead block's upper half width (inside the pilot's side plates)
SLOPE = 0.1049                             # the panel faces lean back 6 degrees (the old slabs' plane)


class Facet:
    """a panel face: the plane z = z0 + SLOPE (y - y0); a = metres to the crew member's right of the centre line, y = height"""

    def __init__(self, z0, y0):
        self.z0, self.y0 = z0, y0
        self.U = unit([0, 1, SLOPE])
        self.N = unit([0, SLOPE, -1])          # looking back at the crew, a little up

    def z(self, y):
        return self.z0 + SLOPE * (y - self.y0)

    def at(self, a, y, lift=0.0):
        return np.array([-a, y, self.z(y)]) + self.N * lift


PF = Facet(4.060, 1.472)                   # the pilot's panel face (the old slab's rear face)
GF = Facet(5.380, 1.800)                   # the gunner's panel face
PANEL_TOP_P, PANEL_BOT_P = 2.780, 2.050    # the painted part of the pilot's face
PANEL_TOP_G, PANEL_BOT_G = 2.450, 1.800
HALF_P, HALF_G = 0.40, 0.40

# ---------------------------------------------------------------- instrument layouts (a: right of the centre line; y: height)
# ('game', Kind, diameter) is drawn by the game; ('paint_disc', caption, d) and ('paint_rect', w, h) are painted faces;
# ('annunciator', w, h) is lit by the game; ('display', Kind, width, style) is a FlightDisplays page
PILOT_LAYOUT = [
    # the three main columns: torque / airspeed / rotor rpm, the standby compass over the ADI over the HSI, radar altimeter / altimeter / vertical speed
    ('comp',    0.000, 2.710, ('paint_disc', 'COMP', 0.066), 'standby compass'),
    ('adi',     0.000, 2.580, ('game', 'ATTITUDE', 0.108), 'attitude indicator (ADI)'),
    ('hsi',     0.000, 2.450, ('game', 'HEADING', 0.108), 'horizontal situation indicator (HSI)'),
    ('tq',     -0.150, 2.705, ('game', 'TORQUE', 0.096), 'torque'),
    ('ias',    -0.150, 2.575, ('game', 'AIRSPEED', 0.108), 'airspeed indicator'),
    ('nr',     -0.150, 2.445, ('game', 'ROTOR_RPM', 0.108), 'rotor rpm (NR / NP)'),
    ('ralt',    0.150, 2.705, ('game', 'RADAR_ALT', 0.096), 'radar altimeter'),
    ('alt',     0.150, 2.575, ('game', 'ALTIMETER', 0.108), 'barometric altimeter'),
    ('vsi',     0.150, 2.445, ('game', 'VVI', 0.108), 'vertical speed indicator'),
    # the engine and systems cluster, two columns of small round gauges on the left
    ('n1l',    -0.355, 2.700, ('game', 'ENGINE_RPM', 0.056), 'engine 1 gas generator (N1)'),
    ('n1r',    -0.285, 2.700, ('game', 'ENGINE_RPM', 0.056), 'engine 2 gas generator (N1)'),
    ('tgl',    -0.355, 2.630, ('game', 'EGT', 0.056), 'engine 1 turbine gas temperature'),
    ('tgr',    -0.285, 2.630, ('game', 'EGT', 0.056), 'engine 2 turbine gas temperature'),
    ('fuel',   -0.355, 2.560, ('game', 'FUEL_QTY', 0.056), 'fuel quantity'),
    ('oil1',   -0.285, 2.560, ('paint_disc', 'XMSN', 0.056), 'transmission oil pressure'),
    ('xmt',    -0.355, 2.490, ('paint_disc', 'XMSNT', 0.056), 'transmission oil temperature'),
    ('hyd',    -0.285, 2.490, ('paint_disc', 'HYD', 0.056), 'hydraulic pressure'),
    ('oil2',   -0.355, 2.420, ('paint_disc', 'OIL', 0.056), 'engine 1 oil pressure'),
    ('oil3',   -0.285, 2.420, ('paint_disc', 'OIL', 0.056), 'engine 2 oil pressure'),
    # far right: turn and slip, accelerometer, the red RWR scope, the clock
    ('turn',    0.280, 2.705, ('game', 'TURN_SLIP', 0.078), 'turn and slip'),
    ('accel',   0.360, 2.705, ('paint_disc', 'ACCEL', 0.050), 'accelerometer'),
    ('rwr',     0.300, 2.575, ('paint_disc', 'RWR', 0.100), 'radar warning scope'),
    ('clock',   0.300, 2.445, ('game', 'CLOCK', 0.070), 'clock'),
    # the lower panels: radio, navigation controls, armament; the caution light grid at the lower right
    ('uhf',    -0.275, 2.300, ('paint_rect', 0.170, 0.105), 'UHF / FM radio'),
    ('nav',    -0.095, 2.300, ('paint_rect', 0.150, 0.105), 'navigation control panel'),
    ('arm',     0.085, 2.300, ('paint_rect', 0.150, 0.105), 'armament panel'),
    ('ccl',     0.285, 2.357, ('paint_rect', 0.170, 0.030), 'caution reset / test / brightness'),
    ('caution', 0.285, 2.200, ('annunciator', 0.170, 0.250), 'caution / warning panel'),
    ('ics',    -0.275, 2.170, ('paint_rect', 0.170, 0.070), 'ICS / comm select'),
    ('jett',   -0.095, 2.170, ('paint_rect', 0.150, 0.070), 'jettison select'),
    ('fire',    0.085, 2.170, ('paint_rect', 0.150, 0.070), 'fire / extinguisher'),
    ('eng',    -0.275, 2.088, ('paint_rect', 0.170, 0.058), 'engine controls'),
    ('fuelp',  -0.095, 2.088, ('paint_rect', 0.150, 0.058), 'fuel controls'),
    ('elec',    0.085, 2.088, ('paint_rect', 0.150, 0.058), 'electrical'),
]
GUNNER_LAYOUT = [
    ('g_icp',  -0.265, 2.355, ('paint_rect', 0.200, 0.095), 'integrated control panel (keypad)'),
    ('g_uhf',  -0.265, 2.215, ('paint_rect', 0.200, 0.125), 'UHF radio'),
    ('g_ics',  -0.265, 2.100, ('paint_rect', 0.200, 0.070), 'ICS / comm select'),
    ('g_jtsn', -0.265, 1.998, ('paint_rect', 0.200, 0.062), 'jettison select / emergency hydraulic pump'),
    ('g_sw3',  -0.265, 1.905, ('paint_rect', 0.200, 0.070), 'switch bank'),
    ('g_arm',  -0.040, 2.335, ('paint_rect', 0.185, 0.120), 'gunner weapons control panel'),
    ('g_tsu',  -0.040, 2.200, ('paint_rect', 0.185, 0.115), 'TSU control panel'),
    ('g_cau',  -0.040, 2.105, ('annunciator', 0.185, 0.045), 'caution lamps'),
    ('g_sw1',  -0.040, 1.995, ('paint_rect', 0.185, 0.085), 'switch bank'),
    ('g_sw2',  -0.040, 1.890, ('paint_rect', 0.185, 0.080), 'switch bank'),
    ('g_ias',   0.165, 2.340, ('game', 'AIRSPEED', 0.090), 'airspeed indicator'),
    ('g_tq',    0.280, 2.340, ('game', 'TORQUE', 0.080), 'torque'),
    ('g_alt',   0.165, 2.215, ('game', 'ALTIMETER', 0.080), 'altimeter'),
    ('g_ammo',  0.280, 2.215, ('game', 'AMMO', 0.070), 'rounds remaining (gun)'),
    ('g_sto',   0.225, 2.035, ('display', 'STORES', 0.095, 'CRT'), 'stores display'),
    ('g_sw4',   0.225, 1.895, ('paint_rect', 0.160, 0.070), 'switch bank'),
]
GAUGE_LIFT = 0.004
PAINT_LIFT = 0.0055                         # painted faces stand 2.5 mm over the panel's painted quad (the z-fight gap is 1.5 mm)
DISPLAY_LIFT = 0.003
RIM = 0.16

LAMPS16 = [{"Text": "MASTER|CAUTION", "Condition": "MASTER_CAUTION", "Colour": "amber"},
           {"Text": "ENG|OUT", "Condition": "ENGINE_OUT", "Colour": "red"},
           {"Text": "FIRE", "Condition": "FIRE", "Colour": "red"},
           {"Text": "LOW|FUEL", "Condition": "LOW_FUEL", "Colour": "amber"},
           {"Text": "ROTOR|LOW", "Condition": "ROTOR_RPM_LOW", "Colour": "red"},
           {"Text": "ROTOR|HIGH", "Condition": "ROTOR_RPM_HIGH", "Colour": "amber"},
           {"Text": "TORQUE|HIGH", "Condition": "TORQUE_HIGH", "Colour": "amber"},
           {"Text": "ENG|RUN", "Condition": "ENGINE_RUN", "Colour": "green"},
           {"Text": "XMSN|OIL", "Condition": "NONE", "Colour": "amber"},
           {"Text": "HYD|PRESS", "Condition": "NONE", "Colour": "amber"},
           {"Text": "GEN", "Condition": "NONE", "Colour": "amber"},
           {"Text": "RWR|LOCK", "Condition": "RWR_LOCK", "Colour": "amber"},
           {"Text": "MSL|LAUNCH", "Condition": "MISSILE_LAUNCH", "Colour": "red"},
           {"Text": "LOW|AMMO", "Condition": "AMMO_LOW", "Colour": "amber"},
           {"Text": "DAMAGE", "Condition": "DAMAGE", "Colour": "red"},
           {"Text": "LOW|ALT", "Condition": "LOW_ALT", "Colour": "amber"}]
# the pilot's caution grid: 4 columns x 6 rows (the real AH-1 grid is the lower right block of the panel); the last two rows are legends only
LAMPS = LAMPS16[:8] + [LAMPS16[11], LAMPS16[12], LAMPS16[13], LAMPS16[14], LAMPS16[15], LAMPS16[8], LAMPS16[9], LAMPS16[10]] + \
    [{"Text": t, "Condition": "NONE", "Colour": c} for t, c in
     (("ENG|CHIP", "amber"), ("XMSN|CHIP", "amber"), ("FUEL|FILTER", "amber"), ("FUEL|PUMP", "amber"),
      ("ENG|ICING", "amber"), ("BATT|HOT", "amber"), ("OIL|BYPASS", "amber"), ("AUX|FUEL", "green"))]
LAMP_COLUMNS = 4
LAMPS_G = [LAMPS16[0], LAMPS16[1], LAMPS16[2], LAMPS16[3], LAMPS16[12], LAMPS16[13]]
LAMP_COLUMNS_G = 6


# ---------------------------------------------------------------- painting helpers

def font(px, bold=False):
    for f in (FONT_FILES if bold else FONT_FILES[1:2] + FONT_FILES[0:1] + FONT_FILES[2:]):
        try:
            return ImageFont.truetype(f, max(4, int(px)))
        except OSError:
            continue
    return ImageFont.load_default()


def noisy(d, im, colour, amount=6, seed=1):
    rng = np.random.default_rng(seed)
    a = np.zeros((im.height, im.width, 4), np.float32)
    a[..., :3] = colour; a[..., 3] = 255
    n = rng.normal(0, amount, (im.height // 8 + 1, im.width // 8 + 1))
    n = np.kron(n, np.ones((8, 8)))[:im.height, :im.width]
    a[..., :3] += n[..., None]
    im.paste(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)), (0, 0))


def text(d, xy, s, px, fill=WHITE, anchor='mm', bold=False):
    d.text(xy, s, font=font(px, bold), fill=fill, anchor=anchor)


def toggle(d, x, y, S, lit=False, big=False):
    r = S * (0.030 if big else 0.022)
    d.ellipse([x - r, y - r, x + r, y + r], fill=(26, 26, 28, 255), outline=(150, 150, 146, 255), width=2)
    d.line([x, y, x + r * 0.5, y - r * 1.6], fill=(205, 205, 198, 255), width=max(3, int(r * 0.7)))


def knob(d, x, y, r, ang=0.7, label=None, H=None):
    d.ellipse([x - r, y - r, x + r, y + r], fill=(30, 30, 32, 255), outline=(170, 170, 164, 255), width=3)
    d.line([x, y, x + r * 0.8 * math.sin(ang), y - r * 0.8 * math.cos(ang)], fill=WHITE + (255,), width=4)
    if label and H:
        text(d, (x, y + r + H * 0.05), label, H * .045)


def dial_face(d, im, title, lo, hi, step, label_step, value, start=-135, sweep=270, labels=None, red=None):
    S = im.width; c = S / 2; R = S / 2 - S * 0.02
    d.ellipse([c - R, c - R, c + R, c + R], fill=(14, 15, 16, 255))

    def ang(v):
        return math.radians(start + (v - lo) / (hi - lo) * sweep)
    if red:
        for v0 in np.arange(red[0], red[1], (hi - lo) / 200):
            a = ang(v0)
            d.line([c + (R * .80) * math.sin(a), c - (R * .80) * math.cos(a), c + R * .93 * math.sin(a),
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
    a = ang(value)
    d.line([c - .15 * R * math.sin(a), c + .15 * R * math.cos(a), c + .80 * R * math.sin(a), c - .80 * R * math.cos(a)],
           fill=(240, 240, 232, 255), width=int(S * .045))
    d.ellipse([c - S * .05, c - S * .05, c + S * .05, c + S * .05], fill=(60, 60, 60, 255))


def panel_paint(layout, facet, top, bot, half, rect_px, seed):
    """the panel face: dark grey, screws, seams and the dark recess behind every instrument"""
    a0, a1 = -half, half
    W, H = rect_px
    SS = Atlas.SS
    cos_f = math.cos(math.atan(SLOPE))

    def to_px(a, y):
        s = (a - a0) / (a1 - a0)
        return (s * W * SS, (1 - (y - bot) / (top - bot)) * H * SS)

    def fn(d, im):
        noisy(d, im, PANEL, 3, seed=seed)
        for (a, y) in [(a0 + 0.012, bot + 0.012), (a1 - 0.012, bot + 0.012), (a0 + 0.012, top - 0.012), (a1 - 0.012, top - 0.012),
                       (0.0, top - 0.012), (0.0, bot + 0.012)]:
            x, yy = to_px(a, y); r = SS * 1.8
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(20, 20, 20, 255))
        for name, a, y, spec, _ in layout:
            kind = spec[0]
            x, yy = to_px(a, y)
            if kind in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + RIM) + 0.004) / (a1 - a0) * W * SS
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(14, 17, 17, 255))
                if kind == 'game' and spec[1] in ('ROTOR_RPM', 'TORQUE', 'ENGINE_RPM', 'EGT', 'FUEL_QTY', 'RADAR_ALT'):
                    cap = {'ROTOR_RPM': 'NR / NP', 'TORQUE': 'TORQUE', 'ENGINE_RPM': 'N1', 'EGT': 'TGT', 'FUEL_QTY': 'FUEL',
                           'RADAR_ALT': 'RAD ALT'}[spec[1]]
                    text(d, (x, yy - r - SS * 6), cap, SS * 7, fill=(170, 172, 168, 255))
            elif kind in ('paint_rect', 'display', 'annunciator'):
                if kind == 'display':
                    w = h = spec[2] + 0.060
                else:
                    w, h = spec[1] + 0.012, spec[2] + 0.012
                sx = (w / 2) / (a1 - a0) * W * SS; sy = (h / 2) / (top - bot) * H * SS * cos_f
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(14, 17, 17, 255))
        return None
    return fn


# ---------------------------------------------------------------- painted instrument faces

def p_dial_static(kind):
    def fn(d, im):
        if kind == 'XMSN':
            dial_face(d, im, 'XMSN', 0, 120, 10, 20, 70.0, labels=['0', '20', '40', '60', '80', '100', '120'], red=(105, 120))
        elif kind == 'HYD':
            dial_face(d, im, 'HYD', 0, 3000, 250, 500, 1700.0, labels=['0', '5', '10', '15', '20', '25', '30'], red=(0, 300))
        elif kind == 'XMSNT':
            dial_face(d, im, 'TEMP', 0, 150, 10, 30, 80.0, labels=['0', '30', '60', '90', '120', '150'], red=(120, 150))
        elif kind == 'ACCEL':
            dial_face(d, im, 'G', -3, 4, 0.5, 1, 1.0, labels=['-3', '-2', '-1', '0', '1', '2', '3', '4'])
        elif kind == 'COMP':
            d.ellipse([0, 0, im.width, im.height], fill=(206, 206, 198, 255), outline=(20, 20, 20, 255), width=int(im.width * .06))
            for k, lab in enumerate(['N', 'E', 'S', 'W']):
                a = math.radians(90 * k)
                text(d, (im.width / 2 + im.width * .34 * math.sin(a), im.height / 2 - im.height * .34 * math.cos(a)), lab, im.width * .16, fill=(20, 20, 20), bold=True)
            d.line([im.width / 2, im.height * .2, im.width / 2, im.height * .8], fill=(160, 40, 30, 255), width=int(im.width * .03))
        elif kind == 'RWR':
            c = im.width / 2
            d.ellipse([0, 0, im.width, im.height], fill=(150, 42, 34, 255), outline=(20, 20, 20, 255), width=int(im.width * .05))
            for rr in (0.2, 0.4, 0.6, 0.8):
                d.ellipse([c - c * rr, c - c * rr, c + c * rr, c + c * rr], outline=(100, 24, 20, 255), width=int(im.width * .015))
            d.line([c, im.height * .08, c, im.height * .92], fill=(100, 24, 20, 255), width=int(im.width * .015))
            d.line([im.width * .08, c, im.width * .92, c], fill=(100, 24, 20, 255), width=int(im.width * .015))
        else:
            dial_face(d, im, 'OIL', 0, 100, 10, 20, 55.0, labels=['0', '20', '40', '60', '80', '100'], red=(0, 12))
    return fn


def p_arm(d, im):
    """the pilot's armament panel"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .08), 'ARMAMENT', H * .11, bold=True)
    d.rectangle([S * .06, H * .18, S * .30, H * .42], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .18, H * .30), 'MASTER|ARM'.replace('|', ' '), H * .075, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['GUN', 'RKT', 'MSL']):
        x = S * (0.40 + k * 0.20)
        d.rectangle([x - S * .08, H * .20, x + S * .08, H * .40], fill=(40, 130, 56, 255) if k == 1 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .30), lab, H * .09, fill=(10, 30, 10))
    for k, lab in enumerate(['1', '2', '3', '4']):
        x = S * (0.14 + 0.24 * k)
        d.rectangle([x - S * .08, H * .54, x + S * .08, H * .76], fill=(176, 176, 172, 255), outline=(10, 10, 10, 255), width=2)
        text(d, (x, H * .65), lab, H * .14, fill=(20, 20, 20))
    text(d, (S * .5, H * .88), 'STATION SELECT', H * .075)


def p_eng(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .08), 'ENGINE  FUEL', H * .10, bold=True)
    for k, lab in enumerate(['ENG 1', 'ENG 2']):
        x = S * (0.28 + k * 0.44)
        d.rectangle([x - S * .17, H * .20, x + S * .17, H * .44], fill=(30, 32, 34, 255), outline=(150, 150, 146, 255), width=2)
        toggle(d, x, H * .36, S * 1.2, big=True)
        text(d, (x, H * .26), lab, H * .08)
    for k, lab in enumerate(['XFER', 'BOOST', 'EMER']):
        x = S * (0.22 + k * 0.28)
        toggle(d, x, H * .68, S * 1.2)
        text(d, (x, H * .86), lab, H * .075)


def p_fire(d, im):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    for k, lab in enumerate(['ENG 1', 'ENG 2']):
        x = S * (0.26 + k * 0.48)
        d.rectangle([x - S * .20, H * .10, x + S * .20, H * .58], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .26), 'FIRE', H * .14, fill=(240, 240, 232), bold=True)
        text(d, (x, H * .44), lab, H * .11, fill=(240, 240, 232))
    text(d, (S * .5, H * .80), 'EXTINGUISHER', H * .12)


def p_gun_arm(d, im):
    """the gunner's weapons control panel"""
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(14, 15, 16, 255))
    text(d, (S * .5, H * .07), 'WEAPONS CONTROL', H * .075, bold=True)
    for k, lab in enumerate(['GUN', 'RKT', 'TOW', 'HF']):
        x = S * (0.15 + k * 0.23)
        d.rectangle([x - S * .09, H * .15, x + S * .09, H * .33], fill=(40, 130, 56, 255) if k == 0 else (24, 52, 28, 255),
                    outline=(10, 10, 10, 255), width=3)
        text(d, (x, H * .24), lab, H * .08, fill=(10, 30, 10))
    d.rectangle([S * .06, H * .42, S * .30, H * .60], fill=RED + (255,), outline=(10, 10, 10, 255), width=3)
    text(d, (S * .18, H * .51), 'MSTR ARM', H * .07, fill=(235, 235, 228), bold=True)
    for k, lab in enumerate(['TURRET', 'STOW', 'LOCK']):
        x = S * (0.45 + k * 0.22)
        toggle(d, x, H * .50, S * 1.1)
        text(d, (x, H * .66), lab, H * .06)
    for k, lab in enumerate(['RNG', 'FLT', 'MODE']):
        x = S * (0.22 + 0.28 * k)
        knob(d, x, H * .82, H * .07, ang=0.3 + 0.7 * k)
        text(d, (x, H * .96), lab, H * .055)


def p_console(side, length, caps):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, CONSOLE, 3, seed=20 + side)
        n = len(caps)
        for k, cap in enumerate(caps):
            y0 = H * (k / n) + H * 0.01; y1 = H * ((k + 1) / n) - H * 0.01
            d.rectangle([S * .05, y0, S * .95, y1], fill=(18, 20, 20, 255), outline=(10, 10, 10, 255), width=2)
            mid = (y0 + y1) / 2
            for j in range(3):
                x = S * (0.22 + 0.28 * j)
                if k % 2:
                    knob(d, x, mid + (y1 - y0) * .08, S * .075, ang=0.5 + 0.6 * j)
                else:
                    toggle(d, x, mid + (y1 - y0) * .10, S * 1.4)
            d.rectangle([S * .12, y0 + 5, S * .88, y0 + 5 + (y1 - y0) * .22], fill=(40, 42, 42, 255))
            text(d, (S * .5, y0 + 5 + (y1 - y0) * .11), cap, S * .075)
    return fn


def p_cushion(colour, straps=False):
    def fn(d, im):
        S, H = im.width, im.height
        noisy(d, im, colour, 5, seed=50)
        for k in range(1, 4):
            d.line([0, H * k / 4, S, H * k / 4], fill=tuple(c - 16 for c in colour) + (255,), width=4)
        if straps:
            for x in (S * .28, S * .72):
                d.rectangle([x - S * .05, 0, x + S * .05, H], fill=HARNESS + (255,))
            d.rectangle([S * .38, H * .55, S * .62, H * .70], fill=(150, 150, 150, 255))
    return fn


def p_placard(lines, bg=YELLOW, fg=(20, 20, 20)):
    def fn(d, im):
        S, H = im.width, im.height
        d.rectangle([0, 0, S, H], fill=bg + (255,))
        d.rectangle([3, 3, S - 4, H - 4], outline=fg + (255,), width=4)
        for k, s in enumerate(lines):
            text(d, (S * .5, H * (k + 1) / (len(lines) + 1)), s, H * 0.62 / len(lines), fill=fg, bold=True)
    return fn


def p_viewfinder(d, im):
    """the TSU's viewfinder window: a dark glass with the sight's reticle (a painted face)"""
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


def p_hood_side(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=33)
    text(d, (S * .5, H * .5), 'TSU', H * .5, fill=(120, 124, 120), bold=True)


def p_hud_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['BRT', 'CONT', 'MODE', 'TEST']):
        x = S * (0.14 + k * 0.24)
        d.ellipse([x - H * .20, H * .34 - H * .20, x + H * .20, H * .34 + H * .20], fill=(18, 18, 18, 255), outline=(120, 120, 120, 255), width=3)
        text(d, (x, H * .80), lab, H * .18)


def p_sight_ctrl(d, im):
    S, H = im.width, im.height
    noisy(d, im, DGREY, 3, seed=9)
    for k, lab in enumerate(['BRT', 'RET', 'TEST']):
        x = S * (0.20 + k * 0.30)
        d.ellipse([x - H * .22, H * .30 - H * .22, x + H * .22, H * .30 + H * .22], fill=(18, 18, 18, 255),
                  outline=(120, 120, 120, 255), width=3)
        text(d, (x, H * .74), lab, H * .2)


def p_bulk(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 4, seed=70)
    for k in range(3):
        x = S * (0.08 + k * 0.31)
        d.rectangle([x, H * .20, x + S * .24, H * .62], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)
        for j in range(3):
            d.ellipse([x + S * .03 + j * S * .06, H * .36, x + S * .06 + j * S * .06, H * .46], fill=(150, 150, 150, 255))


def p_kick(d, im):
    S, H = im.width, im.height
    noisy(d, im, PANEL_D, 3, seed=71)
    for k in range(3):
        d.rectangle([S * (.10 + k * .30), H * .30, S * (.34 + k * .30), H * .70], fill=(20, 22, 22, 255), outline=(70, 72, 72, 255), width=3)


# ---------------------------------------------------------------- the dense panels (painted: black boxes, white legends, toggles, knobs, push buttons)

def _bg(d, im, seed=0):
    S, H = im.width, im.height
    d.rectangle([0, 0, S, H], fill=(20, 21, 23, 255), outline=(70, 72, 74, 255), width=3)
    for (x, y) in ((S * .03, H * .08), (S * .97, H * .08), (S * .03, H * .92), (S * .97, H * .92)):
        d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(120, 122, 120, 255))


def _toggles(d, im, y, labels, x0=0.10, x1=0.90, size=1.0, lab_dy=0.20):
    S, H = im.width, im.height
    n = len(labels)
    for k, lab in enumerate(labels):
        x = S * (x0 + (x1 - x0) * (k + 0.5) / n)
        toggle(d, x, H * y, H * 0.9 * size)
        text(d, (x, H * (y + lab_dy)), lab, H * 0.11)


def _knobs(d, im, y, labels, x0=0.15, x1=0.85, r=0.14):
    S, H = im.width, im.height
    n = len(labels)
    for k, lab in enumerate(labels):
        x = S * (x0 + (x1 - x0) * (k + 0.5) / n)
        knob(d, x, H * y, H * r, ang=0.4 + 0.7 * k)
        text(d, (x, H * (y + r + 0.12)), lab, H * 0.11)


def _buttons(d, im, y0, rows, cols, x0=0.08, x1=0.92, h=0.14, lit=()):
    S, H = im.width, im.height
    w = (x1 - x0) / cols
    for r in range(rows):
        for c in range(cols):
            x = S * (x0 + w * c + 0.1 * w)
            y = H * (y0 + (h + 0.04) * r)
            d.rectangle([x, y, x + S * w * 0.8, y + H * h], fill=(200, 196, 150, 255) if (r, c) in lit else (36, 38, 40, 255),
                        outline=(150, 150, 146, 255), width=2)


def p_radio(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    text(d, (S * .5, H * .08), 'UHF   FREQ (MHz)', H * .10, bold=True)
    d.rectangle([S * .10, H * .17, S * .52, H * .36], fill=(66, 84, 66, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .31, H * .27), '251.40', H * .13, fill=(20, 40, 20))
    for k, lab in enumerate(['VOL', 'SQ', 'TONE']):
        knob(d, S * (0.66 + 0.14 * k), H * .27, H * .09, ang=0.5 + 0.5 * k)
        text(d, (S * (0.66 + 0.14 * k), H * .43), lab, H * .08)
    _knobs(d, im, 0.64, ['MAN', 'PRST', 'CHAN', 'T/R+G', 'ADF'], x0=0.10, x1=0.90, r=0.12)


def p_nav(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    text(d, (S * .5, H * .08), 'NAV CONT   HSI', H * .10, bold=True)
    _knobs(d, im, 0.42, ['BRG PTR 1', 'BRG PTR 2'], x0=0.20, x1=0.80, r=0.20)
    _knobs(d, im, 0.80, ['FM HOME'], x0=0.35, x1=0.65, r=0.13)
    text(d, (S * .14, H * .20), 'ADF', H * .08); text(d, (S * .86, H * .20), 'VOR', H * .08)


def p_icp(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    d.rectangle([S * .06, H * .10, S * .46, H * .34], fill=(58, 76, 58, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .26, H * .22), 'DISPLAY', H * .12, fill=(20, 40, 20))
    for r in range(4):
        for c in range(3):
            x = S * (0.52 + 0.14 * c); y = H * (0.10 + 0.21 * r)
            d.rectangle([x, y, x + S * .12, y + H * .17], fill=(40, 42, 44, 255), outline=(160, 160, 156, 255), width=2)
            text(d, (x + S * .06, y + H * .085), '123456789*0#'[r * 3 + c], H * .12)
    knob(d, S * .16, H * .62, H * .15, ang=0.9)
    text(d, (S * .16, H * .90), 'MODE', H * .09)
    _toggles(d, im, 0.62, ['CLR', 'ENT'], x0=0.28, x1=0.44, size=0.6)


def p_ics(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    text(d, (S * .5, H * .12), 'ICS   HOT MIC  FM VHF UHF HF', H * .13, bold=True)
    _toggles(d, im, 0.50, ['HOT MIC', 'FM', 'VHF', 'UHF', 'HF'], x0=0.34, x1=0.96, size=0.7)
    _toggles(d, im, 0.78, ['ADF', 'VOR', 'AUX'], x0=0.46, x1=0.96, size=0.7, lab_dy=0.0)
    knob(d, S * .13, H * .58, H * .22, ang=0.6); knob(d, S * .26, H * .58, H * .20, ang=1.4)


def p_jtsn(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    d.rectangle([S * .06, H * .14, S * .24, H * .62], fill=(190, 60, 40, 255), outline=(10, 10, 10, 255), width=2)
    text(d, (S * .5, H * .16), 'JTSN SEL   OUTBD / BOTH / INBD', H * .15, bold=True)
    _toggles(d, im, 0.60, ['OUTBD', 'BOTH', 'INBD'], x0=0.34, x1=0.72, size=0.8, lab_dy=0.26)
    toggle(d, S * .88, H * .52, H * 1.0); text(d, (S * .88, H * .84), 'EMER HYD', H * .10)


def p_tsu(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    text(d, (S * .5, H * .08), 'TSU CONTROL', H * .10, bold=True)
    _knobs(d, im, 0.34, ['BF / LRD', 'CAMERA', 'LASER ARM', 'MODE SEL'], x0=0.10, x1=0.92, r=0.13)
    _toggles(d, im, 0.74, ['LASER', 'RANGE', 'DISPLAY', 'TEST'], x0=0.10, x1=0.92, size=0.7)
    for k, c in enumerate([(210, 140, 30), (210, 140, 30), (210, 140, 30), (60, 170, 70)]):
        x = S * (0.15 + 0.2 * k)
        d.ellipse([x - 5, H * .93 - 5, x + 5, H * .93 + 5], fill=c + (255,))


def p_ccl(d, im):
    S, H = im.width, im.height
    _bg(d, im)
    text(d, (S * .30, H * .50), 'CAUTION  RESET', H * .46, bold=True)
    toggle(d, S * .62, H * .50, H * 1.2); toggle(d, S * .80, H * .50, H * 1.2)
    text(d, (S * .70, H * .90), 'TEST  BRIGHT', H * .28)


def p_sw(seed, labels, rows=2):
    def fn(d, im):
        S, H = im.width, im.height
        _bg(d, im)
        rng = np.random.default_rng(seed)
        n = len(labels)
        per = (n + rows - 1) // rows
        for k, lab in enumerate(labels):
            r, c = divmod(k, per)
            x = S * (0.10 + 0.80 * (c + 0.5) / per)
            y = H * (0.28 + 0.42 * r) if rows > 1 else H * 0.38
            if rng.random() < 0.6:
                toggle(d, x, y, H * 0.8)
            else:
                knob(d, x, y, H * 0.10, ang=float(rng.random() * 2))
            text(d, (x, y + H * 0.22), lab, H * 0.09)
        text(d, (S * .5, H * .07), labels[0] + ' ...', H * .09)
    return fn


PAINTERS = {'uhf': p_radio, 'nav': p_nav, 'arm': p_arm, 'ccl': p_ccl, 'ics': p_ics, 'jett': p_jtsn, 'fire': p_fire,
            'eng': p_sw(31, ['FUEL', 'ENG 1', 'ENG 2', 'ENG ST', 'GOV', 'BOOST']),
            'fuelp': p_sw(32, ['XFER', 'BOOST', 'EMER', 'FLT', 'DUMP', 'PRIME']),
            'elec': p_sw(33, ['BAT', 'GEN', 'INV', 'BUS', 'NVG', 'HTR']),
            'g_icp': p_icp, 'g_uhf': p_radio, 'g_ics': p_ics, 'g_jtsn': p_jtsn, 'g_arm': p_gun_arm, 'g_tsu': p_tsu,
            'g_sw1': p_sw(41, ['MASTER', 'STBY', 'ARM', 'TURRET', 'LOCK', 'STOW']),
            'g_sw2': p_sw(42, ['LIGHTS', 'PITOT', 'HTR', 'DEFOG', 'VENT', 'HYD']),
            'g_sw3': p_sw(43, ['COMM 1', 'COMM 2', 'IFF', 'NAV', 'ILL', 'TEST']),
            'g_sw4': p_sw(44, ['RNG', 'FLT', 'MODE', 'BIT'], rows=1)}

# ---------------------------------------------------------------- build

def build(atlas):
    m = Mesh()
    sw = {k: atlas.swatch(c) for k, c in dict(panel=PANEL, panel_d=PANEL_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                lgrey=LGREY, frame=FRAME, cushion=CUSHION, harness=HARNESS, yellow=YELLOW,
                                                red=RED, metal=METAL, floor=FLOOR, bezel=(14, 15, 17), console=CONSOLE,
                                                rubber=RUBBER, pale=(120, 124, 120)).items()}
    gauges, displays, glass = [], [], []
    cache = {}

    # ===================================================================== tub: floors, liners, the bulkhead block
    yb = FLOOR_G
    # floors (the pilot's lies on the old strip's level + 9 mm; the gunner's is the tub's bottom)
    zfloor_p = PF.z(FLOOR_P)
    m.flat([[-WALL, FLOOR_P, 3.115], [WALL, FLOOR_P, 3.115], [WALL, FLOOR_P, zfloor_p], [-WALL, FLOOR_P, zfloor_p]], sw['floor'], normal=[0, 1, 0])
    m.flat([[-WALL, yb, 4.52], [WALL, yb, 4.52], [WALL, yb, GF.z(yb)], [-WALL, yb, GF.z(yb)]], sw['floor'], normal=[0, 1, 0])
    # side liners up to the rails' base (y 1.86); the rails' inner faces (x 0.51) are the walls above
    for s in (1, -1):
        m.flat([[s * WALL, yb, 4.52], [s * WALL, yb, GF.z(yb)], [s * WALL, 1.86, GF.z(yb)], [s * WALL, 1.86, 4.52]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[s * WALL, FLOOR_P, 3.115], [s * WALL, FLOOR_P, zfloor_p], [s * WALL, 1.86, zfloor_p], [s * WALL, 1.86, 3.115]], sw['panel_d'], normal=[-s, 0, 0])
    # the bulkhead block (pilot's panel face to the gunner's seat back), solid
    zf = PF.z
    SHOULDER = 2.45                            # above this the block narrows to UPPER (the glass leans in over the pilot's panel)
    m.yz_prism([(yb, zf(yb)), (SHOULDER, zf(SHOULDER)), (SHOULDER, 4.52), (yb, 4.52)], -WALL, WALL, sw['panel_d'])
    m.yz_prism([(SHOULDER, zf(SHOULDER)), (PANEL_TOP_P + 0.020, zf(PANEL_TOP_P + 0.020)), (PANEL_TOP_P + 0.020, 4.52), (SHOULDER, 4.52)],
               -UPPER, UPPER, sw['panel_d'])
    # the panel face (painted) and the knee panel below it
    ppm = 760
    a0, a1 = -HALF_P, HALF_P
    W = int(round((a1 - a0) * ppm)); Hh = int(round((PANEL_TOP_P - PANEL_BOT_P) / math.cos(math.atan(SLOPE)) * ppm))
    rect = atlas.paint(W, Hh, panel_paint(PILOT_LAYOUT, PF, PANEL_TOP_P, PANEL_BOT_P, HALF_P, (W, Hh), 3))
    lift = 0.003
    m.quad_tex([PF.at(a0, PANEL_BOT_P, lift), PF.at(a1, PANEL_BOT_P, lift), PF.at(a1, PANEL_TOP_P, lift), PF.at(a0, PANEL_TOP_P, lift)],
               rect, normal=PF.N)
    # the face's border: the block's front face is the same plane (3 mm behind the painted quad); strips at the sides and under it
    kick = atlas.paint(220, 70, p_kick)
    m.quad_tex([PF.at(a0, FLOOR_P, lift), PF.at(a1, FLOOR_P, lift), PF.at(a1, PANEL_BOT_P, lift), PF.at(a0, PANEL_BOT_P, lift)],
               kick, normal=PF.N)
    for xa, xb in ((a1, WALL), (-WALL, a0)):               # (a: to the pilot's right, x = -a)
        m.flat([PF.at(xa, FLOOR_P, lift), PF.at(xb, FLOOR_P, lift), PF.at(xb, SHOULDER, lift), PF.at(xa, SHOULDER, lift)],
               sw['panel_d'], normal=PF.N)
    for xa, xb in ((a1, UPPER), (-UPPER, a0)):
        m.flat([PF.at(xa, SHOULDER, lift), PF.at(xb, SHOULDER, lift), PF.at(xb, PANEL_TOP_P, lift), PF.at(xa, PANEL_TOP_P, lift)],
               sw['panel_d'], normal=PF.N)
    # the visor over the panel: a plate leaning over it with a 10 cm lip toward the pilot
    zt = zf(PANEL_TOP_P)
    m.yz_prism([(PANEL_TOP_P, zt - 0.10), (PANEL_TOP_P + 0.028, zt - 0.10), (PANEL_TOP_P + 0.028, 4.52), (PANEL_TOP_P, 4.52)],
               -UPPER + 0.005, UPPER - 0.005, sw['black'])
    # the gunner's rear: the back plate of the seat on the block's back face is part of his seat (below)

    # ===================================================================== PILOT
    # -- instruments
    for name, a, y, spec, label in PILOT_LAYOUT:
        kind = spec[0]
        c = PF.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 0, 'Kind': spec[1], 'Center': r5(c + PF.N * GAUGE_LIFT), 'Normal': r5(PF.N),
                           'Up': r5(PF.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c + PF.N * lift, PF.N, PF.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006 - lift, sw['bezel'])
        elif kind == 'paint_disc':
            dia = spec[2]
            rect_ = cache.get(spec[1]) or atlas.paint(96, 96, p_dial_static(spec[1]))
            cache[spec[1]] = rect_
            m.rect_tex(c + PF.N * PAINT_LIFT, PF.N, PF.U, dia, dia, rect_)
            m.ring(c + PF.N * lift, PF.N, PF.U, dia / 2, dia / 2 * (1 + RIM), 0.010 - lift, sw['bezel'])
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 0, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + PF.N * 0.0045), 'Normal': r5(PF.N), 'Up': r5(PF.U),
                           'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS, 'Lamps': LAMPS})
            m.frame_rect(c + PF.N * lift, PF.N, PF.U, w, h, 0.005, 0.008 - lift, sw['bezel'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = int(round(w * 1400))
            ph = max(16, int(round(pw * h / w)))
            rect_ = atlas.paint(pw, ph, PAINTERS[name])
            m.rect_tex(c + PF.N * PAINT_LIFT, PF.N, PF.U, w, h, rect_)
            m.frame_rect(c + PF.N * lift, PF.N, PF.U, w, h, 0.004, 0.007 - lift, sw['bezel'])

    # -- the pilot's HUD: one unit on the glareshield (the visor's top): a low control box (2.5 cm strip toward the pilot) and a narrow
    # pedestal carrying ONE combiner, 15% over life size, centred on the eye line; no posts or wings beside the glass
    ex, ey, ez = EYE_P
    sy0 = PANEL_TOP_P + 0.028
    sc = atlas.paint(150, 30, p_hud_ctrl)
    m.box([0, sy0 + 0.0275, 4.30], (X, Y, Z), (0.085, 0.0275, 0.055), sw['dgrey'], front=(2, -1, sc, -X, Y))   # control box, strip 5.5 cm tall
    lean = math.radians(25)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = unit(np.cross(X, g_up))                              # (away from the pilot)
    g_h = 0.125                                                # slant length of the glass
    g_bot = np.array([0, ey - g_h / 2 * math.cos(lean), 4.325 + g_h / 2 * math.sin(lean)])
    m.box([0, g_bot[1], g_bot[2]], (X, Y, Z), (0.095, 0.014, 0.030), sw['dgrey'])                  # the combiner's housing: the frame bars stand in it
    m.box([0, (sy0 + 0.055 + g_bot[1] + 0.004) / 2, 4.33], (X, Y, Z), (0.040, (g_bot[1] + 0.004 - sy0 - 0.055) / 2, 0.035), sw['dgrey'])   # pedestal
    # the glass: a rectangle, 0.17 wide, with the lower corners eased, centred on the eye line (y at the eye's height)
    q3 = lambda u, v, d=0.0: g_bot + X * u + g_up * v + g_n * d
    hw = 0.075
    poly2 = [(-hw, 0.0), (hw, 0.0), (hw, g_h), (-hw, g_h)]
    gp = [q3(u, v) for u, v in poly2]
    for k in range(1, len(gp) - 1):
        glass += [gp[0], gp[k], gp[k + 1]]
    rim, dep = 0.010, 0.005
    outn = []
    nP = len(poly2)
    for i in range(nP):
        a, b = np.array(poly2[i]), np.array(poly2[(i + 1) % nP])
        e = (b - a) / np.linalg.norm(b - a)
        outn.append(np.array([e[1], -e[0]]))
    outer2 = []
    for i in range(nP):
        n1, n2 = outn[i - 1], outn[i]
        outer2.append(np.array(poly2[i]) + (n1 + n2) / (1 + n1 @ n2) * rim)
    for i in range(1, nP):                                     # (the bottom edge stands in the unit)
        j = (i + 1) % nP
        a, b = q3(*poly2[i]), q3(*poly2[j])
        a2, b2 = q3(*outer2[i]), q3(*outer2[j])
        on = X * outn[i][0] + g_up * outn[i][1]
        m.flat([a - g_n * dep, b - g_n * dep, b2 - g_n * dep, a2 - g_n * dep], sw['dgrey'], normal=-g_n)
        m.flat([a + g_n * dep, b + g_n * dep, b2 + g_n * dep, a2 + g_n * dep], sw['dgrey'], normal=g_n)
        m.flat([a2 - g_n * dep, b2 - g_n * dep, b2 + g_n * dep, a2 + g_n * dep], sw['dgrey'], normal=on)
        m.flat([a - g_n * dep, b - g_n * dep, b + g_n * dep, a + g_n * dep], sw['dgrey'], normal=-on)
    hud = dict(centre=q3(0, g_h / 2), normal=-g_n, up=g_up, width=2 * hw, height=g_h)

    # -- the pilot's seat: back cushion on the old back plate (its face z = 3.115 - 0.0696 (y - 1.86)), pan, base, bolsters, harness
    cushion = atlas.paint(64, 64, p_cushion(CUSHION))
    backt = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    zb = lambda y: 3.115 - 0.0696 * (y - 1.86)
    ax_b = (X, unit([0, 1, -0.0696]), unit([0, 0.0696, 1]))
    yc = 2.75
    m.box([0, yc, zb(yc) + 0.035 - 0.002], ax_b, (0.185, 0.36, 0.035), sw['cushion'], front=(2, 1, backt, -X, ax_b[1]),
          skip=((2, -1),))                                   # (the rear face lay 2 mm off the old back plate: omitted)
    yh = 2.975
    m.box([0, yh, zb(yh) + 0.038], ax_b, (0.095, 0.115, 0.040), sw['cushion'], skip=((2, -1),))                       # headrest pad
    m.box([0, 2.14, 3.34], (X, Y, Z), (0.215, 0.205, 0.225), sw['frame'])                           # the armoured base (y 1.935 - 2.345)
    m.box([0, 2.362, 3.35], (X, Y, Z), (0.200, 0.018, 0.215), sw['cushion'], front=(1, 1, cushion, -X, Z))   # seat pan cushion
    for s in (1, -1):
        m.box([s * 0.213, 2.50, 3.22], (X, Y, Z), (0.012, 0.135, 0.100), sw['frame'])                # bolsters
        m.box([s * 0.213, 2.40, 3.40], (X, Y, Z), (0.012, 0.040, 0.100), sw['frame'])
        m.box([s * 0.09, 2.70, zb(2.70) + 0.077], (X, unit([0, 1, 0.3]), unit([0, -0.3, 1])), (0.025, 0.200, 0.003), sw['harness'])   # shoulder straps
        m.box([s * 0.12, 2.383, 3.43], (X, Y, Z), (0.045, 0.004, 0.010), sw['harness'])              # lap belt halves
    m.box([0, 2.384, 3.45], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])                           # the buckle

    # -- the cyclic: boot on the floor between the knees, the column leaning back, the grip with trigger and hat
    FOOT = np.array([0.0, FLOOR_P + 0.005, 3.90])
    m.box(FOOT + [0, 0.014, 0], (X, Y, Z), (0.060, 0.014, 0.060), sw['dgrey'])
    m.box(FOOT + [0, 0.055, 0], (X, Y, Z), (0.034, 0.040, 0.034), sw['black'])
    col_ax = (X, unit([0, 1, -0.17]), unit([0, 0.17, 1]))
    m.box(FOOT + col_ax[1] * 0.290, col_ax, (0.011, 0.235, 0.011), sw['metal'])
    gc = FOOT + col_ax[1] * 0.545 + col_ax[2] * 0.01
    g_ax = (X, unit([0, 1, -0.30]), unit([0, 0.30, 1]))
    m.box(gc, g_ax, (0.022, 0.085, 0.030), sw['black'])
    m.box(gc + g_ax[1] * 0.087, g_ax, (0.019, 0.006, 0.025), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.097 + g_ax[2] * 0.006, g_ax, (0.005, 0.004, 0.005), sw['red'])
    m.box(gc + g_ax[2] * 0.032 + g_ax[1] * 0.025, g_ax, (0.007, 0.018, 0.005), sw['metal'])         # trigger
    m.box(gc - g_ax[2] * 0.031 + g_ax[1] * 0.040, g_ax, (0.012, 0.018, 0.003), sw['dgrey'])
    m.box(gc + g_ax[1] * 0.045 + g_ax[2] * 0.027, g_ax, (0.012, 0.006, 0.006), sw['mgrey'])         # weapon switch
    # the pilot's cyclic is the pilot's control; the gunner's stalk is below

    # -- the collective on the left console: base on the console, the lever running forward and up, the twist grip
    cons_p = atlas.paint(220, 300, p_console(1, 0.62, ['ICS / COMM', 'NAV', 'FUEL XFR', 'EXT LTS', 'ENG START']))
    for s in (1, -1):
        xi, xo = 0.365 * s, WALL * s
        top = [[xi, 2.215, 3.17], [xo, 2.215, 3.17], [xo, 2.215, 3.79], [xi, 2.215, 3.79]]
        s_i, s_o = (1, 0) if s > 0 else (0, 1)
        m.face(top, [uv_of(cons_p, s_i, 0), uv_of(cons_p, s_o, 0), uv_of(cons_p, s_o, 1), uv_of(cons_p, s_i, 1)], normal=[0, 1, 0])
        m.flat([[xi, FLOOR_P, 3.17], [xi, FLOOR_P, 3.79], [xi, 2.215, 3.79], [xi, 2.215, 3.17]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[xi, FLOOR_P, 3.79], [xo, FLOOR_P, 3.79], [xo, 2.215, 3.79], [xi, 2.215, 3.79]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xi, FLOOR_P, 3.17], [xo, FLOOR_P, 3.17], [xo, 2.215, 3.17], [xi, 2.215, 3.17]], sw['panel_d'], normal=[0, 0, -1])
    P0c = np.array([0.435, 2.240, 3.215])
    G0 = np.array([0.412, 2.405, 3.64])
    m.box(P0c - [0, 0.013, 0], (X, Y, Z), (0.030, 0.012, 0.030), sw['dgrey'])                       # the pivot block
    # The collective (the "throttle" lever) is its own bone (throttle_lever), hinged on the pivot block, modelled full down
    # (throttle_lib.collective); the twist grip is the throttle.
    arm = G0 - P0c
    THR.clear()
    THR.append(throttle_lib.collective(lambda: Mesh(), sw, 'throttle_lever', P0c, float(math.hypot(arm[1], arm[2])), 8.0,
                                       math.degrees(math.atan2(0.20, 1.0)), grip_len=0.245, travel=24.0, lateral=float(arm[0])))
    # -- rudder pedals (arms to the floor, the plates leaning toward the pilot)
    for s in (1, -1):
        zk = PF.z(FLOOR_P + 0.12) - 0.075
        m.box([s * 0.13, FLOOR_P + 0.15, zk], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, FLOOR_P + 0.09, zk + 0.025], (X, Y, Z), (0.014, 0.055, 0.025), sw['dgrey'])

    # ===================================================================== GUNNER
    eg = EYE_G
    # -- the nose body ahead of the panel: two tiers narrowing inside the quarter plates (plan trapezoids), closing the nose bay
    import canopy_ah1w_super_cobra as CN                       # the canopy's planes: the body stays inside the glass
    def half_at(y, z, margin):
        return min(0.49, CN.x_on(CN.plane_gq, y, z) - margin)
    zg = GF.z
    z_front = 5.995

    def plan(y_side, margin, z_rear):
        zs_ = [z_rear, 5.60, 5.80, z_front]
        t = [(half_at(y_side, z, margin), z) for z in zs_]
        return [(-w, z) for w, z in t] + [(w, z) for w, z in t[::-1]]
    # tier 1 (floor to y 2.10) and tier 2 (to y 2.455) narrower under the quarter plates; the rear face leans with the panel's
    m.sloped(plan(2.05, 0.02, zg(yb)), plan(2.05, 0.02, zg(2.05)), yb, 2.05, sw['panel_d'])
    m.sloped(plan(2.46, 0.03, zg(2.05)), plan(2.46, 0.03, zg(2.455)), 2.05, 2.455, sw['panel_d'])
    # the panel face (painted), leaning back
    ppm = 760
    a0, a1 = -HALF_G, HALF_G
    Wg = int(round((a1 - a0) * ppm)); Hg = int(round((PANEL_TOP_G - PANEL_BOT_G) / math.cos(math.atan(SLOPE)) * ppm))
    rect_g = atlas.paint(Wg, Hg, panel_paint(GUNNER_LAYOUT, GF, PANEL_TOP_G, PANEL_BOT_G, HALF_G, (Wg, Hg), 5))
    m.quad_tex([GF.at(a0, PANEL_BOT_G, lift), GF.at(a1, PANEL_BOT_G, lift), GF.at(a1, PANEL_TOP_G, lift), GF.at(a0, PANEL_TOP_G, lift)],
               rect_g, normal=GF.N)
    m.quad_tex([GF.at(a0, yb + 0.02, lift), GF.at(a1, yb + 0.02, lift), GF.at(a1, PANEL_BOT_G, lift), GF.at(a0, PANEL_BOT_G, lift)],
               kick, normal=GF.N)
    for name, a, y, spec, label in GUNNER_LAYOUT:
        kind = spec[0]
        c = GF.at(a, y)
        if kind == 'game':
            gauges.append({'Seat': 1, 'Kind': spec[1], 'Center': r5(c + GF.N * GAUGE_LIFT), 'Normal': r5(GF.N),
                           'Up': r5(GF.U), 'Diameter': spec[2], 'Depth': 0.03})
            m.ring(c + GF.N * lift, GF.N, GF.U, spec[2] / 2, spec[2] / 2 * (1 + RIM), GAUGE_LIFT + 0.006 - lift, sw['bezel'])
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': 1, 'Kind': 'ANNUNCIATOR', 'Center': r5(c + GF.N * 0.0045), 'Normal': r5(GF.N), 'Up': r5(GF.U),
                           'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': LAMP_COLUMNS_G, 'Lamps': LAMPS_G})
            m.frame_rect(c + GF.N * lift, GF.N, GF.U, w, h, 0.005, 0.008 - lift, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': 1, 'Center': r5(c + GF.N * DISPLAY_LIFT), 'Normal': r5(GF.N), 'Up': r5(GF.U), 'Width': w,
                             'Depth': 0.035, 'Kind': spec[1], 'Style': spec[3]})
            _, n_, u_, r_ = frame(c, GF.N, GF.U)
            pr, t = 0.010, 0.022
            m.rect_tex(c + u_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, w + 2 * t, t, sw['bezel'])
            m.rect_tex(c - u_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, w + 2 * t, t, sw['bezel'])
            m.rect_tex(c + r_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, t, w, sw['bezel'])
            m.rect_tex(c - r_ * (w / 2 + t / 2) + n_ * pr, GF.N, GF.U, t, w, sw['bezel'])
            for sgn in (-1, 1):
                m.box(c + u_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (w / 2 + t, 0.0015, (pr - 0.002) / 2), sw['black'])
                m.box(c + r_ * sgn * (w / 2 + t) + n_ * (pr - 0.002) / 2, (r_, u_, n_), (0.0015, w / 2 + t, (pr - 0.002) / 2), sw['black'])
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = int(round(w * 1400))
            rect_ = atlas.paint(pw, int(round(pw * h / w)), PAINTERS[name])
            m.rect_tex(c + GF.N * PAINT_LIFT, GF.N, GF.U, w, h, rect_)
            m.frame_rect(c + GF.N * lift, GF.N, GF.U, w, h, 0.004, 0.007 - lift, sw['bezel'])

    # -- the TSU sight hood: a boxy hood standing out of the panel's top toward the gunner, a rubber eyecup at the back at his eye line
    hz0, hz1 = 5.04, GF.z(PANEL_TOP_G) + 0.01        # (eyecup 0.35 in front of the eye; rear face of the hood at hz0)
    hy0, hy1 = 2.500, 2.680
    hw_ = 0.095
    m.box([0, (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (hw_, (hy1 - hy0) / 2, (hz1 - hz0) / 2), sw['dgrey'])
    side = atlas.paint(120, 50, p_hood_side)
    for s in (1, -1):                                       # the TSU legend on both sides
        m.rect_tex([s * (hw_ + 0.0015), (hy0 + hy1) / 2, (hz0 + hz1) / 2 + 0.06], [s, 0, 0], Y, 0.12, 0.05, side)
    vf = atlas.paint(96, 96, p_viewfinder)
    # the viewfinder window recessed in the rear face, its eyecup ring (rubber) standing 4 cm out toward the gunner
    ecen = np.array([0.0, 2.590, hz0 - 0.0])
    for k in range(8):
        a0_, a1_ = 2 * math.pi * k / 8, 2 * math.pi * (k + 1) / 8
        d0 = X * math.cos(a0_) + Y * math.sin(a0_); d1 = X * math.cos(a1_) + Y * math.sin(a1_)
        r_in, r_out, out = 0.034, 0.052, 0.040
        p0, p1, p2, p3 = ecen + d0 * r_in, ecen + d1 * r_in, ecen + d1 * r_out, ecen + d0 * r_out
        m.flat([p0 - Z * out, p1 - Z * out, p2 - Z * out, p3 - Z * out], sw['rubber'], normal=-Z)
        m.flat([p3 - Z * out, p2 - Z * out, p2, p3], sw['rubber'], normal=(d0 + d1) / 2)
        m.flat([p0 - Z * out, p0, p1, p1 - Z * out], sw['rubber'], normal=-(d0 + d1) / 2)
    m.rect_tex(ecen - Z * 0.003, -Z, Y, 0.075, 0.075, vf)
    tsu = dict(centre=ecen - Z * 0.003, normal=-Z, up=Y, width=0.075, height=0.075)          # the gunner's live sight (seat 1)
    # the hood's control strip, on top toward the gunner
    sc2 = atlas.paint(150, 30, p_sight_ctrl)
    m.rect_tex([0, hy1 + 0.0015, (hz0 + hz1) / 2 - 0.05], Y, -Z, 0.18, 0.05, sc2)
    # a bracket from the hood to the panel face
    m.box([0, (hy0 + 0.02) - 0.03, hz1 - 0.04], (X, Y, Z), (0.050, 0.030, 0.045), sw['black'])
    # the nose deck's top is the upper tier (flat black)

    # -- the gunner's seat: base, pan, back plate against the block (its face z 4.52)
    m.box([0, 1.690, 4.80], (X, Y, Z), (0.215, 0.220, 0.215), sw['frame'])                           # base y 1.47 - 1.91
    m.box([0, 1.925, 4.80], (X, Y, Z), (0.200, 0.018, 0.205), sw['cushion'], front=(1, 1, cushion, -X, Z))
    gcus = atlas.paint(64, 96, p_cushion(CUSHION, straps=True))
    m.box([0, 2.34, 4.545], (X, Y, Z), (0.185, 0.36, 0.025), sw['cushion'], front=(2, 1, gcus, -X, Y))
    m.box([0, 2.78, 4.56], (X, Y, Z), (0.095, 0.110, 0.040), sw['cushion'])
    for s in (1, -1):
        m.box([s * 0.213, 2.08, 4.72], (X, Y, Z), (0.012, 0.135, 0.100), sw['frame'])
        m.box([s * 0.213, 1.99, 4.92], (X, Y, Z), (0.012, 0.040, 0.100), sw['frame'])
        m.box([s * 0.09, 2.40, 4.572], (X, unit([0, 1, 0.1]), unit([0, -0.1, 1])), (0.025, 0.200, 0.003), sw['harness'])
        m.box([s * 0.12, 1.945, 4.93], (X, Y, Z), (0.045, 0.004, 0.010), sw['harness'])
    m.box([0, 1.946, 4.95], (X, Y, Z), (0.030, 0.006, 0.014), sw['metal'])

    # -- the gunner's hand controllers on his side shelves: the short side-stick cyclic on the right, the collective on the left
    cons_g = atlas.paint(160, 240, p_console(1, 0.6, ['ICS / COMM', 'IFF', 'LIGHTS']))
    for s in (1, -1):
        xi, xo = 0.340 * s, WALL * s
        top = [[xi, 2.040, 4.66], [xo, 2.040, 4.66], [xo, 2.040, 5.30], [xi, 2.040, 5.30]]
        s_i, s_o = (1, 0) if s > 0 else (0, 1)
        m.face(top, [uv_of(cons_g, s_i, 0), uv_of(cons_g, s_o, 0), uv_of(cons_g, s_o, 1), uv_of(cons_g, s_i, 1)], normal=[0, 1, 0])
        m.flat([[xi, yb, 4.66], [xi, yb, 5.30], [xi, 2.040, 5.30], [xi, 2.040, 4.66]], sw['panel_d'], normal=[-s, 0, 0])
        m.flat([[xi, yb, 5.30], [xo, yb, 5.30], [xo, 2.040, 5.30], [xi, 2.040, 5.30]], sw['panel_d'], normal=[0, 0, 1])
        m.flat([[xi, yb, 4.66], [xo, yb, 4.66], [xo, 2.040, 4.66], [xi, 2.040, 4.66]], sw['panel_d'], normal=[0, 0, -1])
    # right: the side-stick (a 15 cm stalk on a boot, leaning forward) with its small grip
    SF = np.array([-0.415, 2.045, 5.00])
    m.box(SF + [0, 0.012, 0], (X, Y, Z), (0.045, 0.012, 0.045), sw['dgrey'])
    st_ax = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
    m.box(SF + st_ax[1] * 0.100, st_ax, (0.010, 0.085, 0.010), sw['metal'])
    sg = SF + st_ax[1] * 0.215
    m.box(sg, st_ax, (0.020, 0.040, 0.026), sw['black'])
    m.box(sg + st_ax[1] * 0.042, st_ax, (0.016, 0.004, 0.022), sw['dgrey'])
    m.box(sg + st_ax[2] * 0.028 + st_ax[1] * 0.010, st_ax, (0.006, 0.014, 0.004), sw['metal'])       # trigger
    m.box(sg + st_ax[1] * 0.034 - st_ax[2] * 0.004, st_ax, (0.006, 0.004, 0.006), sw['red'])
    # left: the collective lever with its twist grip
    GP0 = np.array([0.430, 2.062, 4.78])
    GG0 = np.array([0.408, 2.150, 5.10])
    m.box(GP0, (X, Y, Z), (0.028, 0.012, 0.028), sw['dgrey'])
    # the gunner's collective: the same shaped lever, fixed (only the pilot's is the throttle bone)
    garm = GG0 - GP0
    throttle_lib.make_static(m, [throttle_lib.collective(lambda: Mesh(), sw, 'collective_g', GP0, float(math.hypot(garm[1], garm[2])),
                                                         math.degrees(math.atan2(garm[1], garm[2])), math.degrees(math.atan2(0.12, 1.0)),
                                                         grip_len=0.173, lateral=float(garm[0]))])
    # pedals
    for s in (1, -1):
        zk = GF.z(yb + 0.12) - 0.090
        m.box([s * 0.13, yb + 0.15, zk], (X, unit([0, 1, -0.35]), unit([0, 0.35, 1])), (0.050, 0.075, 0.010), sw['mgrey'])
        m.box([s * 0.13, yb + 0.09, zk + 0.025], (X, Y, Z), (0.014, 0.055, 0.025), sw['dgrey'])

    # -- placards: the canopy jettison (yellow, on both sills' rail tops), a no-step on the rails' inside
    pl = atlas.paint(120, 36, p_placard(['EMERGENCY EXIT', 'PULL HANDLE'], bg=RED, fg=(240, 240, 232)))
    for s in (1, -1):
        m.rect_tex([s * 0.508, 2.15, 4.85], [-s, 0, 0], Y, 0.20, 0.06, pl)
    return m, gauges, displays, glass, hud, tsu


def _components(pm):
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    key, ids = {}, []
    for p in P:
        ids.append(key.setdefault(tuple(np.round(p, 3)), len(key)))
    ids = np.array(ids)
    rows, cols = [], []
    for poly in pm['polys']:
        vs = [ids[v[0]] for v in poly]
        for a in vs[1:]:
            rows.append(vs[0]); cols.append(a)
    n = len(key)
    _, lab = connected_components(coo_matrix((np.ones(len(rows)), (rows, cols)), shape=(n, n)), directed=False)
    return P, ids, lab


def cut_old_interior(bones):
    """removes the old interior parts (loose connected parts whose bounding box is one of OLD_INTERIOR); returns the count"""
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    pm = hull['poly_mesh']
    P, ids, lab = _components(pm)
    comp = {}
    for i, poly in enumerate(pm['polys']):
        if poly:
            comp.setdefault(lab[ids[poly[0][0]]], []).append(i)
    drop = set()
    for fl in comp.values():
        V = np.concatenate([P[[v[0] for v in pm['polys'][i]]] for i in fl])
        lo, hi = V.min(0), V.max(0)
        for blo, bhi in OLD_INTERIOR:
            if np.all(np.abs(lo - blo) < 0.003) and np.all(np.abs(hi - bhi) < 0.003):
                drop.update(fl)
    pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in drop]
    return len(drop)


def write_json(path, obj, indent, newline=True):
    text_ = json.dumps(obj, indent=indent, ensure_ascii=False) if indent else json.dumps(obj, separators=(',', ':'))
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text_ + ('\n' if newline else ''))


def write_asset(path, asset):
    """the asset keeps its layout: indented, with the canopy glass triangle list on one line at the end"""
    glass_json = asset.pop('CanopyGlass', None)
    text_ = json.dumps(asset, indent=2, ensure_ascii=False)
    if glass_json is not None:
        text_ = text_[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
        asset['CanopyGlass'] = glass_json
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text_ + '\n')


def planform(geo):
    """a top-down outline of the airframe for the stores page: the hull's widest |x| per z, as one closed polyline"""
    hull = next(b for b in geo['minecraft:geometry'][0]['bones'] if b['name'] == HULL_BONE)
    P = np.array(hull['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
    zs = np.arange(-8.4, 7.2, 0.4)
    left, right = [], []
    for z in zs:
        sel = P[np.abs(P[:, 2] - z) < 0.3]
        if len(sel) == 0:
            continue
        left.append((float(sel[:, 0].max()), float(z)))
        right.append((float(sel[:, 0].min()), float(z)))
    loop = left + right[::-1] + [left[0]]
    return [[round(v, 3) for p in loop for v in p]]


def move_eyes(data):
    """the gunner's seat and eye lowered (the step); the pilot's stay"""
    ex, ey, ez = EYE_G
    gs = data['Seats'][1]
    gs['Position'] = [0.0, round(ey - SEAT_BELOW, 5), gs['Position'][2]]
    data['Attachments'][gs['CameraPos']['EyeAttachment']]['Position'] = [ex, ey, ez]


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    data = json.load(open(DATA, encoding='utf-8'))
    g = geo['minecraft:geometry'][0]
    bones = g['bones']
    bones[:] = [b for b in bones if b['name'] != BONE]
    removed = cut_old_interior(bones)
    atlas = Atlas()
    mesh, gauges, displays, glass, hud, tsu = build(atlas)
    pm = mesh.poly_mesh()
    hull = next(b for b in bones if b['name'] == HULL_BONE)
    at = [b['name'] for b in bones].index('hull__canopy') + 1 if any(b['name'] == 'hull__canopy' for b in bones) else bones.index(hull) + 1
    bones.insert(at, {'name': BONE, 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm})
    thr = throttle_lib.add_bones(bones, THR, BONE)
    print(f'ah1w_super_cobra throttle bones {[(n, p) for n, p, _ in thr]}')
    asset['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    asset['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays, 'Planform': planform(geo)}
    asset['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 0,
                              'Center': r5(hud['centre']), 'Normal': r5(hud['normal']), 'Up': r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': r5(EYE_P), 'Style': 'HUD'}
    # the gunner's TSU viewfinder: a live reflector-style reticle for seat 1 only (HeadUpDisplays, one sight per seat;
    # the lone HeadUpDisplay above stays the pilot's for code that does not read the list yet)
    asset['HeadUpDisplays'] = [{'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': 1,
                                'Center': r5(tsu['centre']), 'Normal': r5(tsu['normal']), 'Up': r5(tsu['up']),
                                'Width': tsu['width'], 'Height': tsu['height'],
                                'Eye': r5(EYE_G), 'Style': 'REFLECTOR'}]
    cg = asset.get('CanopyGlass')
    if cg and cg.get('Schema') == 1:
        t = np.array(cg['Triangles'], float).reshape(-1, 3, 3)
        c = t.mean(1)
        gl = np.array(hud['centre'])
        keep = ~((np.abs(c[:, 0]) < 0.14) & (np.abs(c[:, 1] - gl[1]) < 0.12) & (np.abs(c[:, 2] - gl[2]) < 0.10))   # (an earlier combiner goes)
        tri = [list(map(float, p)) for p in t[keep].reshape(-1, 3)]
        G = [list(map(float, p)) for p in glass]
        both = G + [G[i] for k in range(0, len(G), 3) for i in (k, k + 2, k + 1)]
        cg['Triangles'] = [round(v, 4) + 0.0 for p in tri + both for v in p]
    data_before = json.dumps(data, sort_keys=True)
    move_eyes(data)
    print(f'{VID}: {removed} old interior faces removed; new cockpits {len(pm["polys"])} faces, {len(gauges)} gauges, '
          f'{len(displays)} display(s), sight glass {len(glass) // 3} triangles; atlas {atlas.used()} of '
          f'{sum(a[2] * a[3] for a in ATLAS)} texels')
    if dry:
        return
    write_json(GEO, geo, None)
    write_asset(ASSET, asset)
    if json.dumps(data, sort_keys=True) != data_before:
        write_json(DATA, data, 2)
    atlas.write(TEX)
    atlas.write(TEX_DEAD, dead=True)


if __name__ == '__main__':
    sys.path.insert(0, HERE)
    main(sys.argv[1:])
