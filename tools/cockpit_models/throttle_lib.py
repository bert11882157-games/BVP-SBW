#!/usr/bin/env python3
"""Shaped throttle levers (and helicopter collectives) for the hand-built cockpit scripts of Elite Expansion 2.

Each lever is its own bone (throttle_lever, throttle_lever_1, throttle_lever_2 ... ; helicopters: the same names, the
collective is the "throttle" lever) so a later patch can move it with the throttle. The lever is modelled at IDLE, its
pivot at the real hinge (below the console top, where the lever arm turns). The mesh is built in the calling script's
own frame (x left, y up, z forward, blocks; the script's Mesh adds its eye offset), so a script calls

    THR += throttle_lib.jet_levers(m, Mesh, sw, 'f16', x=0.345, top_y=CONSOLE_Y, z_idle=tz - 0.07)

and, where it writes the bones, throttle_lib.add_bones(g['bones'], THR, BONE, offset=EYE).

The static parts (quadrant plate, slots, detent gate, friction knob) go into the script's own mesh `m`; the moving
parts (arm and grip) into one Mesh per lever. Travel is a rotation of the bone about the vehicle X axis through the
hinge: positive turns the grip tip forward (toward +z). Idle lean is negative (arm leaning back toward the pilot).
"""
import math
import numpy as np

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
PREFIX = 'throttle_lever'


def _u(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def _col(sw, name):
    return sw[name] if name in sw else sw['black']


class Lever:
    def __init__(self, name, mesh, hinge, info):
        self.name, self.mesh, self.hinge, self.info = name, mesh, np.array(hinge, float), info

    def pivot_px(self, offset=(0, 0, 0)):
        h = self.hinge + np.array(offset, float)
        return [round(float(h[0]) * 16, 5), round(float(h[1]) * 16, 5), round(-float(h[2]) * 16, 5)]


def add_bones(bones, levers, parent, offset=(0, 0, 0), mesh=True):
    """put one bone per lever into the geo bone list (after its parent); a rerun replaces them"""
    bones[:] = [b for b in bones if not b['name'].startswith(PREFIX)]
    at = next(i for i, b in enumerate(bones) if b['name'] == parent) + 1
    for k, lv in enumerate(levers):
        b = {'name': lv.name, 'parent': parent, 'pivot': lv.pivot_px(offset)}
        if mesh:
            b['poly_mesh'] = lv.mesh.poly_mesh()
        bones.insert(at + k, b)
    return [(lv.name, lv.pivot_px(offset), lv.info) for lv in levers]


# ---------------------------------------------------------------- solids

def _basis(t, ref=X):
    t = _u(t)
    a = np.array(ref, float) - t * (np.array(ref, float) @ t)
    if np.linalg.norm(a) < 1e-6:
        a = Y - t * (Y @ t)
    a = _u(a)
    return a, np.cross(t, a)


def _ring(c, a, b, ra, rb, seg, n):
    pts = []
    for k in range(seg):
        th = 2 * math.pi * k / seg
        ct, st = math.cos(th), math.sin(th)
        x = math.copysign(abs(ct) ** (2.0 / n), ct)
        y = math.copysign(abs(st) ** (2.0 / n), st)
        pts.append(np.array(c, float) + a * ra * x + b * rb * y)
    return np.array(pts)


def loft(m, col, rings, caps=(True, True)):
    cs = [r.mean(0) for r in rings]
    for i in range(len(rings) - 1):
        r0, r1 = rings[i], rings[i + 1]
        seg = len(r0)
        for j in range(seg):
            k = (j + 1) % seg
            q = [r0[j], r0[k], r1[k], r1[j]]
            m.flat(q, col, normal=np.mean(q, 0) - (cs[i] + cs[i + 1]) / 2)
    if caps[0]:
        m.flat(list(rings[0]), col, normal=cs[0] - cs[1])
    if caps[1]:
        m.flat(list(rings[-1]), col, normal=cs[-1] - cs[-2])


def tube(m, col, p0, p1, r0, r1=None, seg=8, ref=X, caps=(True, True)):
    r1 = r0 if r1 is None else r1
    t = np.array(p1, float) - np.array(p0, float)
    a, b = _basis(t, ref)
    loft(m, col, [_ring(p0, a, b, r0, r0, seg, 2.0), _ring(p1, a, b, r1, r1, seg, 2.0)], caps)


class Fr:
    """a grip frame: origin o, forward axis g (in the y-z plane), b = up across it, X = left"""

    def __init__(self, o, g):
        self.o, self.g = np.array(o, float), _u(g)
        self.b = _u(np.cross(self.g, X))

    def p(self, a=0.0, u=0.0, l=0.0):
        return self.o + self.g * a + self.b * u + X * l


def body(m, col, F, prof, seg=10, n=2.4):
    """a lofted grip along F.g: prof = [(a, half width, half height, lift)]"""
    rings = [_ring(F.p(p[0], p[3] if len(p) > 3 else 0.0), X, F.b, p[1], p[2], seg, n) for p in prof]
    loft(m, col, rings)


def fbox(m, col, F, a, u, l, ha, hu, hl):
    m.box(F.p(a, u, l), (X, F.b, F.g), (hl, hu, ha), col)


def fcyl(m, col, F, a, u, l, r, h, axis='b', seg=8):
    """a short cylinder of radius r and half length h at (a, u, l) along the frame's b (default), X or g axis"""
    ax = {'b': F.b, 'x': X, 'g': F.g}[axis]
    c = F.p(a, u, l)
    tube(m, col, c - ax * h, c + ax * h, r, r, seg)


# ---------------------------------------------------------------- grips (all at rest, from the arm's tip)

def _thumb(m, sw, F, a, u, l, hat=True, n=3, red=False):
    """a thumb cluster: pad, a hat on it and a row of little switches"""
    fbox(m, _col(sw, 'dgrey'), F, a, u + 0.003, l, 0.020, 0.0035, 0.015)
    if hat:
        fcyl(m, _col(sw, 'black'), F, a - 0.006, u + 0.0095, l, 0.0085, 0.0035)
        fcyl(m, _col(sw, 'metal'), F, a - 0.006, u + 0.0135, l, 0.004, 0.0015)
    for k in range(n):
        fbox(m, _col(sw, 'red' if (red and k == 0) else 'metal'), F, a + 0.012 + 0.006 * k, u + 0.0085, l + (k - 1) * 0.0045,
             0.0022, 0.0035, 0.0022)


def grip_f16(m, sw, tip, side=0):
    """F-16 throttle handle: a tubular palm grip with the thumb cluster on top, the finger lift under the front and
    the antenna wheel on the left face"""
    F = Fr(tip + np.array([0, 0.010, 0.0]), [0, math.sin(math.radians(8)), math.cos(math.radians(8))])
    fbox(m, _col(sw, 'metal'), F, 0.004, -0.004, 0, 0.012, 0.010, 0.010)                 # the neck
    body(m, _col(sw, 'black'), F, [(0.0, .014, .017), (.022, .020, .025), (.060, .0225, .0275), (.100, .0215, .026),
                                   (.132, .0155, .019)], n=2.4)
    _thumb(m, sw, F, 0.044, 0.0275, 0.0, True, 3, red=False)
    fcyl(m, _col(sw, 'metal'), F, 0.055, 0.004, 0.0245, 0.011, 0.0025, axis='x')            # the antenna elevation wheel
    fbox(m, _col(sw, 'metal'), F, 0.118, -0.0285, 0, 0.011, 0.0035, 0.016)                  # the finger lift
    fbox(m, _col(sw, 'dgrey'), F, 0.086, 0.0, -0.0225, 0.010, 0.012, 0.0022)                # the speed-brake slide plate


def _pair_grip(m, sw, tip, side, elev, L, wa, wb, n, extras):
    F = Fr(tip + np.array([0, 0.010, 0.0]), [0, math.sin(math.radians(elev)), math.cos(math.radians(elev))])
    fbox(m, _col(sw, 'metal'), F, 0.004, -0.004, 0, 0.011, 0.010, 0.009)
    body(m, _col(sw, 'black'), F, [(0.0, wa * .62, wb * .62), (L * .17, wa * .95, wb * .96), (L * .5, wa, wb),
                                   (L * .8, wa * .93, wb * .94), (L, wa * .66, wb * .68)], n=n)
    fbox(m, _col(sw, 'metal'), F, L * .88, -wb - 0.0012, 0, 0.011, 0.0035, wa * .62)       # the idle finger lift
    extras(m, sw, F, side, L, wa, wb)
    return F


def _x_f15(m, sw, F, side, L, wa, wb):
    if side >= 0:                                       # left: speed brake rocker, chaff / flare, mic
        fbox(m, _col(sw, 'dgrey'), F, L * .30, wb + 0.0025, 0.0, 0.020, 0.0030, 0.012)
        fbox(m, _col(sw, 'metal'), F, L * .62, wb + 0.0045, 0.0, 0.0085, 0.0035, 0.0075)
        fbox(m, _col(sw, 'red'), F, L * .30, wb + 0.0075, -0.004, 0.0035, 0.0035, 0.0035)
    else:                                               # right: the pinky switch and the target management switch
        fbox(m, _col(sw, 'dgrey'), F, L * .30, wb + 0.0025, 0.0, 0.020, 0.0030, 0.012)
        fcyl(m, _col(sw, 'metal'), F, L * .56, wb + 0.0075, 0.0, 0.0065, 0.004)
    fbox(m, _col(sw, 'dgrey'), F, L * .78, -wb * .3, wa + 0.0020, 0.012, 0.010, 0.0020)       # the outboard ridge


def _x_f18(m, sw, F, side, L, wa, wb):
    fbox(m, _col(sw, 'dgrey'), F, L * .40, wb + 0.0020, 0.0, 0.034, 0.0022, 0.0050)           # the raised top ridge
    fbox(m, _col(sw, 'dgrey'), F, L * .28, wb + 0.0045, 0.0, 0.016, 0.0030, 0.013)
    fcyl(m, _col(sw, 'black'), F, L * .30, wb + 0.0090, 0.0, 0.0075, 0.003)                   # cursor / speed brake hat
    fbox(m, _col(sw, 'metal'), F, L * .70, wb + 0.0045, 0.0, 0.0050, 0.0030, 0.0050)
    if side >= 0:
        fbox(m, _col(sw, 'metal'), F, L * .55, wb * .2, wa + 0.0030, 0.011, 0.0070, 0.0030)    # the speed-brake boat


def _x_f22(m, sw, F, side, L, wa, wb):
    _thumb(m, sw, F, L * .34, wb, 0.0, True, 2, red=(side >= 0))
    fbox(m, _col(sw, 'metal'), F, L * .72, wb + 0.0035, -0.005, 0.004, 0.003, 0.004)
    fbox(m, _col(sw, 'dgrey'), F, L * .62, wb * .3, wa + 0.0030, 0.012, 0.009, 0.0030)


def _x_a10(m, sw, F, side, L, wa, wb):
    _thumb(m, sw, F, L * .30, wb, 0.0, True, 3, red=(side >= 0))
    fbox(m, _col(sw, 'metal'), F, L * .58, wb * .3, wa + 0.0035, 0.012, 0.008, 0.0035)       # the boat switch
    fbox(m, _col(sw, 'red'), F, L * .86, wb * .1, -wa - 0.0030, 0.0045, 0.0045, 0.0030)


def _x_f4(m, sw, F, side, L, wa, wb):
    fcyl(m, _col(sw, 'dgrey'), F, L * 1.0, 0.0, 0.0, wa * .8, 0.004, axis='g')                 # the cap on top
    fcyl(m, _col(sw, 'red'), F, L * .30, wb + 0.0020, 0.0, 0.0055, 0.0030)
    fbox(m, _col(sw, 'metal'), F, L * .66, wb + 0.0030, 0.0, 0.0050, 0.0030, 0.0070)
    if side >= 0:
        fbox(m, _col(sw, 'metal'), F, L * .50, wb * .2, wa + 0.0030, 0.010, 0.0060, 0.0030)    # the speed-brake switch


def _x_f14(m, sw, F, side, L, wa, wb):
    _thumb(m, sw, F, L * .32, wb, 0.0, False, 3, red=(side >= 0))
    fbox(m, _col(sw, 'metal'), F, L * .62, wb + 0.0045, 0.0, 0.0065, 0.0035, 0.0065)
    if side >= 0:
        fbox(m, _col(sw, 'dgrey'), F, L * .50, wb * .2, wa + 0.0030, 0.012, 0.0080, 0.0030)    # the wing-sweep override


GRIPS = {                                                  # style: (elevation deg, length, half width, half height, n, extras)
    'f15': (22, 0.125, 0.0205, 0.0295, 2.8, _x_f15),
    'f18': (15, 0.118, 0.0205, 0.0300, 2.6, _x_f18),
    'f22': (18, 0.122, 0.0200, 0.0290, 3.2, _x_f22),
    'a10': (26, 0.128, 0.0225, 0.0320, 2.7, _x_a10),
    'f4': (38, 0.100, 0.0215, 0.0235, 2.2, _x_f4),
    'f14': (30, 0.108, 0.0210, 0.0260, 2.5, _x_f14),
}


def grip_pair(style):
    def fn(m, sw, tip, side=0):
        e, L, wa, wb, n, ex = GRIPS[style]
        _pair_grip(m, sw, tip, side, e, L, wa, wb, n, ex)
    return fn


def grip_rud(m, sw, tip, side=0):
    """the Soviet RUD: an upright bulb handle leaning back, the afterburner finger loop in front and the red cap on top"""
    F = Fr(tip + np.array([0, 0.006, 0.0]), [0, math.sin(math.radians(62)), math.cos(math.radians(62))])
    fcyl(m, _col(sw, 'metal'), F, 0.004, 0.0, 0, 0.0105, 0.010, axis='g')
    body(m, _col(sw, 'black'), F, [(0.010, .0135, .0135), (.030, .0225, .0215), (.062, .0265, .0250), (.092, .0235, .0225),
                                   (.112, .0155, .0150)], n=2.0)
    fbox(m, _col(sw, 'red'), F, 0.113, 0.0, 0, 0.0030, 0.0090, 0.0090)                      # the cap
    fbox(m, _col(sw, 'metal'), F, 0.060, -0.0265, 0, 0.0075, 0.0035, 0.0150)                 # the finger loop (front)
    fbox(m, _col(sw, 'metal'), F, 0.080, 0.0265, 0.0, 0.0045, 0.0025, 0.0090)                # the radio button


def grip_ball(m, sw, tip, side=0):
    """the French lever: a slim stalk ending in a ball grip with a finger lift under its front"""
    F = Fr(tip + np.array([0, 0.012, 0.0]), [0, math.sin(math.radians(70)), math.cos(math.radians(70))])
    body(m, _col(sw, 'black'), F, [(-0.010, .0090, .0090), (0.006, .0200, .0200), (.030, .0285, .0285), (.058, .0260, .0260),
                                   (.075, .0150, .0150)], n=2.0, seg=12)
    fbox(m, _col(sw, 'metal'), F, 0.040, -0.0285, 0, 0.0100, 0.0030, 0.0120)                 # the cut-off finger lift
    fbox(m, _col(sw, 'red'), F, 0.064, 0.0200, 0, 0.0050, 0.0040, 0.0050)                    # the button on top


def grip_pistol(m, sw, tip, side=0):
    """a plain pistol grip (F-102): the black handle with the finger lift and a thumb button"""
    F = Fr(tip + np.array([0, 0.008, 0.0]), [0, math.sin(math.radians(54)), math.cos(math.radians(54))])
    fcyl(m, _col(sw, 'metal'), F, 0.004, 0.0, 0, 0.0100, 0.010, axis='g')
    body(m, _col(sw, 'black'), F, [(0.008, .0140, .0150), (.030, .0215, .0235), (.062, .0235, .0255), (.095, .0215, .0235),
                                   (.112, .0150, .0160)], n=2.3)
    fbox(m, _col(sw, 'metal'), F, 0.074, -0.0265, 0, 0.0090, 0.0035, 0.0140)
    fbox(m, _col(sw, 'red'), F, 0.108, 0.0, 0, 0.0030, 0.0060, 0.0060)
    fbox(m, _col(sw, 'dgrey'), F, 0.040, 0.0255, 0, 0.0090, 0.0025, 0.0110)


def grip_bomber(m, sw, tip, side=0):
    """a heavy-aircraft engine lever: a rounded black head on a metal collar, leaning back, with a small button"""
    F = Fr(tip + np.array([0, 0.004, 0.0]), [0, math.sin(math.radians(76)), math.cos(math.radians(76))])
    fcyl(m, _col(sw, 'metal'), F, 0.006, 0.0, 0, 0.0115, 0.008, axis='g')
    body(m, _col(sw, 'black'), F, [(0.014, .0150, .0150), (.030, .0215, .0220), (.060, .0235, .0245), (.085, .0200, .0210),
                                   (.098, .0110, .0115)], n=2.0)
    fbox(m, _col(sw, 'dgrey'), F, 0.048, 0.0245, 0, 0.0070, 0.0025, 0.0090)


GRIP_FN = {'f16': grip_f16, 'f15': grip_pair('f15'), 'f18': grip_pair('f18'), 'f22': grip_pair('f22'),
           'a10': grip_pair('a10'), 'f4': grip_pair('f4'), 'f14': grip_pair('f14'), 'rud': grip_rud, 'ball': grip_ball,
           'pistol': grip_pistol, 'bomber': grip_bomber}


# ---------------------------------------------------------------- levers

def quadrant(m, sw, x, top_y, z, hx, hz, slots=(0.0,), slot_hz=None, gate_dz=None, height=0.008, friction=True):
    """the static quadrant: a low plate on the console top with a black slot per lever, the afterburner gate and the friction knob"""
    slot_hz = hz * .86 if slot_hz is None else slot_hz
    m.box([x, top_y + height / 2, z], (X, Y, Z), (hx, height / 2, hz), _col(sw, 'dgrey'))
    for dx in slots:
        m.box([x + dx, top_y + height + 0.0006, z], (X, Y, Z), (0.0065, 0.0006, slot_hz), _col(sw, 'black'))
        if gate_dz is not None:
            m.box([x + dx + 0.011, top_y + height + 0.0020, z + gate_dz], (X, Y, Z), (0.0035, 0.0020, 0.006), _col(sw, 'metal'))
    if friction:
        m.box([x + hx - 0.012, top_y + height + 0.0045, z - hz * .55], (X, Y, Z), (0.010, 0.0045, 0.0055), _col(sw, 'metal'))


def lever(m_unused, Mesh_, sw, name, x, top_y, z_idle, grip, lean0=-28.0, rise=0.115, depth=0.10, arm_r=0.0085, side=0,
          mil=22.0, ab=30.0):
    """one jet throttle: arm hinged `depth` under the console top, leaning back `lean0` degrees at idle (crossing the top
    plane at z_idle), rising `rise` above the plane to the grip"""
    L0 = math.radians(lean0)
    a0 = np.array([0, math.cos(L0), math.sin(L0)])
    La = (depth + rise) / math.cos(L0)
    hinge = np.array([x, top_y - depth, z_idle - depth * math.tan(L0)])
    tip = hinge + a0 * La
    mesh = Mesh_()
    tube(mesh, _col(sw, 'metal'), hinge + a0 * (depth - 0.012), tip, arm_r, arm_r * .85, seg=8)
    GRIP_FN[grip](mesh, sw, tip, side)
    info = dict(arm=round(La, 3), lean_idle=lean0, lean_mil=mil, lean_ab=ab, hinge=[round(float(v), 4) for v in hinge],
                grip=grip, travel_z=round(float(depth * (math.tan(math.radians(ab)) - math.tan(L0))), 3))
    return Lever(name, mesh, hinge, info)


def names(n):
    return ['throttle_lever'] if n == 1 else ['throttle_lever_%d' % k for k in range(1, n + 1)]


def jet_levers(m, Mesh_, sw, style, x, top_y, z_idle, n=1, pitch=0.050, hx=None, hz=None, z_plate=None, sides=None,
               lean0=-28.0, rise=0.115, depth=0.10, mil=22.0, ab=30.0, gate=True):
    """a jet throttle quadrant at (x, top_y): `n` levers `pitch` apart centred on x (lever 1 on the pilot's left,
    +x), the plate and slots into `m`. style: f16 f15 f18 f22 a10 f4 f14 rud ball pistol bomber"""
    grip = {'f15': 'f15', 'f18': 'f18', 'f22': 'f22', 'a10': 'a10', 'f4': 'f4', 'f14': 'f14'}.get(style, style)
    xs = [x + (n - 1) * pitch / 2 - k * pitch for k in range(n)]
    hx = hx if hx is not None else (n - 1) * pitch / 2 + 0.035
    trav = depth * (math.tan(math.radians(ab)) - math.tan(math.radians(lean0)))
    zp = z_idle + trav / 2 if z_plate is None else z_plate
    hz = trav / 2 + 0.035 if hz is None else hz
    quadrant(m, sw, x, top_y, zp, hx, hz, slots=[xx - x for xx in xs], gate_dz=(hz * .55 if gate else None))
    out = []
    for k, (nm, xx) in enumerate(zip(names(n), xs)):
        sd = 0 if n == 1 else (1 if k == 0 else -1)
        out.append(lever(m, Mesh_, sw, nm, xx, top_y, z_idle, grip, lean0, rise, depth, 0.0085, sd, mil, ab))
    return out


def collective(m, Mesh_, sw, name, x, base_y, z_hinge, length=0.40, rest_deg=4.0, up_deg=28.0, base_h=0.03, rise=0.03,
               twist_len=0.135, twist_r=0.0215, height=0.0):
    """a helicopter collective: the lever hinged on a base block at the rear, running forward, at rest (full down) at
    `rest_deg` above the horizontal; the grip with its twist throttle at the front end"""
    hinge = np.array([x, base_y + base_h + rise, z_hinge])
    m.box([x, base_y + base_h / 2, z_hinge + 0.02], (X, Y, Z), (0.045, base_h / 2, 0.075), _col(sw, 'dgrey'))
    m.box([x + 0.052, base_y + 0.012, z_hinge + 0.02], (X, Y, Z), (0.008, 0.012, 0.018), _col(sw, 'metal'))   # friction knob
    r = math.radians(rest_deg)
    a0 = np.array([0, math.sin(r), math.cos(r)])
    tip = hinge + a0 * length
    mesh = Mesh_()
    boss0 = hinge - a0 * 0.02
    tube(mesh, _col(sw, 'metal'), boss0, hinge + a0 * (length - twist_len - 0.01), 0.0125, 0.0105, seg=8)
    tube(mesh, _col(sw, 'dgrey'), hinge - X * 0.014, hinge + X * 0.014, 0.0165, 0.0165, seg=8, ref=Y)         # the hinge boss
    F = Fr(hinge + a0 * (length - twist_len), a0)
    # the grip: a head with the switch block, then the twist throttle sleeve
    body(mesh, _col(sw, 'black'), F, [(-0.012, .0160, .0170), (.012, .0230, .0250), (.045, .0240, .0260), (.062, .0200, .0215)], n=2.4)
    fbox(mesh, _col(sw, 'dgrey'), F, 0.030, 0.0275, 0, 0.026, 0.0030, 0.0150)                                  # the switch plate
    for k in range(3):
        fbox(mesh, _col(sw, 'red' if k == 0 else 'metal'), F, 0.014 + 0.014 * k, 0.0315, (k - 1) * 0.006, 0.0035, 0.0030, 0.0035)
    fbox(mesh, _col(sw, 'metal'), F, 0.050, -0.0262, 0, 0.012, 0.0030, 0.012)                                  # the trigger
    body(mesh, _col(sw, 'black'), F, [(.062, twist_r * .92, twist_r * .92), (.080, twist_r, twist_r), (twist_len - 0.010, twist_r, twist_r),
                                      (twist_len, twist_r * .72, twist_r * .72)], n=2.0, seg=12)
    for s in (0.070, 0.092, 0.114):
        body(mesh, _col(sw, 'dgrey'), F, [(s, twist_r * 1.03, twist_r * 1.03), (s + 0.006, twist_r * 1.03, twist_r * 1.03)], n=2.0, seg=12)
    info = dict(arm=round(length, 3), lean_idle=rest_deg, lean_mil=rest_deg + up_deg * .6, lean_ab=rest_deg + up_deg,
                hinge=[round(float(v), 4) for v in hinge], grip='twist', travel_z=0.0)
    return Lever(name, mesh, hinge, info)
