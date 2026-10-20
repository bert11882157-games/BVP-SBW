"""F-16C Block 50/52 side-stick, throttle, pedals and ACES II seat, for f16c_structure.py.

build(ctx) takes the structure script's helpers in a dict: P, R, U, F, E, k, M, box, cyl, obj, CON_U.
"""
import math
import bmesh
from mathutils import Vector


def ring(c, a, b, ra, rb, n=14, sq=2.6, shift=None):
    """a closed super-ellipse ring around c in the plane of unit axes a, b (half sizes ra, rb, in blocks)"""
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        ct, st = math.cos(t), math.sin(t)
        x = math.copysign(abs(ct) ** (2 / sq), ct) * ra
        y = math.copysign(abs(st) ** (2 / sq), st) * rb
        pts.append(c + a * x + b * y + (shift or Vector()))
    return pts


def loft(ctx, name, rings, material, bevel=0.0):
    """a closed tube through rings (each a closed loop, all the same length), capped at both ends"""
    bm = bmesh.new()
    V = [[bm.verts.new(p) for p in r] for r in rings]
    n = len(rings[0])
    for i in range(len(V) - 1):
        for j in range(n):
            bm.faces.new([V[i][j], V[i][(j + 1) % n], V[i + 1][(j + 1) % n], V[i + 1][j]])
    bm.faces.new(V[0][::-1]); bm.faces.new(V[-1])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return ctx['obj'](name, bm, material, bevel=bevel)


def build(ctx):
    P, R, U, F, E, k, M = (ctx[x] for x in ('P', 'R', 'U', 'F', 'E', 'k', 'M'))
    box, cyl, CON_U = ctx['box'], ctx['cyl'], ctx['CON_U']

    # ---------------------------------------------------------------- side-stick (right console, forward end)
    # the contoured armrest: a forearm cradle rising toward the wrist, behind the stick
    rings = []
    for f, h, w in ((0.08, 0.050, 0.040), (0.16, 0.060, 0.046), (0.26, 0.072, 0.048), (0.34, 0.082, 0.046),
                    (0.39, 0.080, 0.040)):
        c = P(0.300, CON_U + h / 2, f)
        rings.append(ring(c, R, U, w * k, h / 2 * k, n=16, sq=3.5))
    loft(ctx, 'stick_armrest', rings, M['tub_d'], bevel=0.003)
    rings = []
    for f, h, w in ((0.09, 0.050, 0.034), (0.20, 0.062, 0.040), (0.30, 0.076, 0.040), (0.37, 0.083, 0.035)):
        c = P(0.300, CON_U + h + 0.004, f)
        rings.append(ring(c, R, U, w * k, 0.006 * k, n=16, sq=3.0))
    loft(ctx, 'stick_armpad', rings, M['cushion'])
    # the short base (a boot on the console) and the grip: a hand-shaped body leaning 13 degrees forward, its head
    # overhanging forward and inboard, the trigger under the head's front
    base = P(0.300, CON_U, 0.455)
    cyl('stick_boot', base + U * 0.012 * k, U, 0.032, 0.012, M['grip'])
    cyl('stick_stem', base + U * 0.032 * k, U, 0.016, 0.010, M['metal'])
    s_ax = (U * math.cos(math.radians(13)) + F * math.sin(math.radians(13))).normalized()
    s_fw = s_ax.cross(R).normalized()
    if s_fw.dot(F) < 0:
        s_fw = -s_fw
    rings = []
    for t, rr, rf, df, dr in ((0.00, 0.018, 0.022, 0.000, 0.000), (0.03, 0.019, 0.025, 0.002, 0.000),
                              (0.06, 0.017, 0.023, 0.003, -0.001), (0.09, 0.019, 0.026, 0.006, -0.003),
                              (0.115, 0.023, 0.031, 0.011, -0.005), (0.135, 0.022, 0.030, 0.012, -0.006),
                              (0.148, 0.016, 0.022, 0.010, -0.005)):
        c = base + U * 0.042 * k + s_ax * t * k + s_fw * df * k + R * dr * k
        rings.append(ring(c, R, s_fw, rr * k, rf * k, n=16, sq=2.4))
    loft(ctx, 'stick_grip', rings, M['grip'], bevel=0.002)
    trig = base + U * 0.042 * k + s_ax * 0.095 * k + s_fw * 0.032 * k
    box('stick_trigger', trig, (R, s_ax, s_fw), (0.006, 0.014, 0.005), M['metal'], bevel=0.002)
    box('stick_pinky_shelf', base + U * 0.045 * k - s_fw * 0.004 * k, (R, U, s_fw), (0.024, 0.004, 0.030), M['grip'],
        bevel=0.002)

    # ---------------------------------------------------------------- throttle (left console, forward end)
    box('throttle_quadrant', P(-0.300, CON_U + 0.010, 0.30), (R, U, F), (0.030, 0.010, 0.12), M['grip'], bevel=0.003)
    box('throttle_slot', P(-0.300, CON_U + 0.0205, 0.30), (R, U, F), (0.006, 0.0006, 0.10), M['black'], bevel=0.0)
    lever = P(-0.300, CON_U + 0.02, 0.33)
    t_ax = (U * math.cos(math.radians(20)) + F * math.sin(math.radians(20))).normalized()
    cyl('throttle_lever', lever + t_ax * 0.025 * k, t_ax, 0.009, 0.025, M['metal'])
    # the grip: a fist-sized block, its inboard (thumb) side bulging up, the head rolled over toward the pilot
    rings = []
    for t, rr, rf, dr, df in ((0.045, 0.026, 0.040, 0.000, 0.000), (0.065, 0.031, 0.046, 0.004, 0.002),
                              (0.090, 0.034, 0.048, 0.007, 0.000), (0.112, 0.032, 0.044, 0.009, -0.004),
                              (0.126, 0.024, 0.034, 0.008, -0.006)):
        c = lever + t_ax * t * k + R * dr * k + F * df * k
        rings.append(ring(c, R, F, rr * k, rf * k, n=16, sq=2.6))
    loft(ctx, 'throttle_grip', rings, M['grip'], bevel=0.002)

    # ---------------------------------------------------------------- rudder pedals
    for sg in (-1, 1):
        p_ax = (U * math.cos(math.radians(-25)) + F * math.sin(math.radians(-25))).normalized()
        box(f'pedal_{sg}', P(sg * 0.10, -0.92, 0.96), (R, p_ax, R.cross(p_ax)), (0.040, 0.065, 0.012), M['metal'])

    # ---------------------------------------------------------------- ACES II, reclined 30 degrees
    rec = math.radians(30)
    s_up = (U * math.cos(rec) - F * math.sin(rec)).normalized()      # up the seat back
    s_fw = s_up.cross(R).normalized()
    if s_fw.dot(F) < 0:
        s_fw = -s_fw                                                 # out of the seat back, toward the panel
    # where the seat sits in this airframe: the model's own seat back stands in the hull behind the cockpit (kept,
    # since it shows from outside; its front face at 0.43 m behind the eye, top 0.24 m above it), so the ACES II is
    # set back on its rails until its headrest meets that block, which reads as the seat's parachute container
    hip = P(0, -0.72, -0.035)
    ax_back = (R, s_up, s_fw)
    # bucket: two side frames (deep at the front, tapering up the back) and the survival-kit pan under the cushion
    for sg in (-1, 1):
        x = sg * 0.200
        prof = [hip + R * x * k + F * 0.42 * k - U * 0.12 * k, hip + R * x * k + F * 0.42 * k + U * 0.02 * k,
                hip + R * x * k + F * 0.10 * k + U * 0.08 * k, hip + R * x * k + s_up * 0.50 * k - s_fw * 0.02 * k,
                hip + R * x * k + s_up * 0.50 * k - s_fw * 0.12 * k, hip + R * x * k - s_fw * 0.12 * k - U * 0.12 * k]
        bm = bmesh.new()
        vs = [bm.verts.new(p) for p in prof]
        bm.faces.new(vs)
        o = ctx['obj'](f'seat_side_frame_{sg}', bm, M['seat'], bevel=0.004, solid=0.014)
        box(f'seat_rail_{sg}', hip + R * sg * 0.150 * k + s_up * 0.30 * k - s_fw * 0.11 * k, ax_back,
            (0.018, 0.42, 0.020), M['metal'], bevel=0.004)
    box('seat_kit', hip + F * 0.20 * k - U * 0.065 * k, (R, U, F), (0.188, 0.055, 0.21), M['chute'], bevel=0.01)
    box('seat_pan', hip + F * 0.20 * k + U * 0.012 * k, (R, U, F), (0.180, 0.024, 0.20), M['cushion'], bevel=0.014)
    # the back: a narrower cushion on the back shell; the parachute container across the top, behind the headrest
    back = hip + s_up * 0.30 * k - s_fw * 0.045 * k
    box('seat_back_cushion', back, ax_back, (0.150, 0.27, 0.028), M['cushion'], bevel=0.014)
    box('seat_back_shell', back - s_fw * 0.055 * k + s_up * 0.04 * k, ax_back, (0.175, 0.33, 0.025), M['seat'],
        bevel=0.008)
    head = hip + s_up * 0.76 * k + s_fw * 0.020 * k
    box('seat_headrest', head, ax_back, (0.075, 0.085, 0.018), M['cushion'], bevel=0.012)
    for sg in (-1, 1):                   # the two side pads, turned in toward the helmet
        a_ = (R * math.cos(math.radians(30)) - s_fw * sg * math.sin(math.radians(30))).normalized()
        n_ = s_up.cross(a_).normalized()
        if n_.dot(s_fw) < 0:
            n_ = -n_
        box(f'seat_head_pad_{sg}', head + R * sg * 0.105 * k + s_fw * 0.012 * k, (a_, s_up, n_), (0.030, 0.075, 0.018),
            M['cushion'], bevel=0.010)
    # the ejection loop: a yellow and black striped D-ring on the seat's front edge, between the thighs
    lp = hip + F * 0.415 * k + U * 0.02 * k
    pts = [lp + R * (0.045 * math.cos(a)) * k + U * (0.035 * math.sin(a) + 0.0) * k + F * 0.012 * math.sin(a) * k
           for a in [math.pi * i / 8 for i in range(9)]]
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        cyl(f'seat_loop_{i}', (a + b) / 2, b - a, 0.0075, (b - a).length / k / 2 + 0.001,
            M['yellow'] if i % 2 == 0 else M['loop_black'], segs=10)
    box('seat_loop_mount', lp, (R, U, F), (0.055, 0.008, 0.010), M['black'], bevel=0.002)
