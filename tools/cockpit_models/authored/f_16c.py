"""F-16C Block 50/52 front cockpit, authored from references (research/f_16c_cockpit.md):

  * main instrument panel: a flat centre board leaning back 15 degrees with two wings turned 25 degrees toward the
    pilot, and a lower centre pedestal leaning back 50 degrees for the EHSI;
  * the glareshield over it, the HUD on top with its combiner, and the up-front controls (ICP keypad with the DED
    beside it) hanging under the HUD; the eyebrow warning lights either side, the AOA and AR/NWS indexers beside the
    HUD;
  * left and right auxiliary consoles (45 degree panels by the knees): CMDS and the ALT GEAR handle on the left; the
    caution light panel, fuel quantity, hydraulic A/B, EPU fuel, clock and cabin pressure on the right;
  * side consoles with their control panels, the side-stick on a raised armrest on the right, the throttle on the
    left, rudder pedals, and an ACES II seat reclined 30 degrees.

Positions are metres from the design eye (r right, u up, f forward), after FlightGear's F-16C-50 cockpit offsets
(see the research brief), scaled to the model by the fitted tub and shrunk until the cockpit sits inside the glass and
the fuselage skin. Live instruments are CockpitGauges / FlightDisplays entries; captions of the lamp panels are drawn
by the game (Annunciator.kt); placards, keypads, the DED and the console panels are painted into the texture.
"""
import math
import numpy as np

from authored import lib as A
from authored.lib import L, unit, Plane, Painter, text, noisy, screws

# ---------------------------------------------------------------- colours (FS 36231 dark gull grey tub, black panels)
# colours from the reference photos (research/f_16c_cockpit.md, "Reference images"): black panel and console faces,
# a medium grey tub and sills, black floor, light grey seat structure with pale sage cushions
PANEL = (30, 31, 33)          # instrument panel and console panel faces: lusterless black
PANEL_D = (22, 23, 24)
GULL = (118, 122, 122)        # tub walls, sills, consoles' sides (dark gull grey)
GULL_D = (92, 96, 96)
COAMING = (20, 20, 21)
FLOOR = (26, 26, 27)
WHITE = (228, 230, 224)
LETTER = (222, 224, 216)      # panel lettering
SEAT_FRAME = (132, 138, 132)  # ACES II structure: light grey
CUSHION = (150, 156, 132)     # pale sage cushions
HEADBOX = (112, 120, 108)
YELLOW = (230, 190, 30)
RED = (196, 36, 30)
METAL = (130, 134, 136)
GRIP = (30, 30, 30)
GREEN_DED = (120, 230, 110)

LEAN = 15.0                   # main panel lean, degrees
WING_TURN = 25.0
CEN_A = 0.25                  # centre board half width
WING_W = 0.12
U_TOP, U_BOT = -0.28, -0.60   # main panel top / bottom (eye-relative)
F_PANEL = 0.80                # main panel reference distance ahead of the eye (at u = -0.45)
CONSOLE_U = -0.76
CONSOLE_R0, CONSOLE_R1 = 0.20, 0.40
FLOOR_U = -1.12
BACK_F = -0.45
FRONT_F = 1.15                # the footwell's forward bulkhead
UFC_S = 0.80                  # the up-front controls' plate: 0.24 m wide


def fit_scale(ac, tub):
    """the largest scale (tub.s down to 0.7 tub.s) at which the cockpit's outer corners sit inside the canopy glass
    and the fuselage skin"""
    probes = [(sg * 0.36, U_TOP + 0.02, 0.76, 'glass'), (sg * 0.15, -0.20, 0.70, 'glass'),
              (sg * 0.27, -0.25, 0.70, 'glass'), (sg * 0.40, CONSOLE_U, 0.0, 'skin'),
              (sg * 0.40, CONSOLE_U, 0.45, 'skin'), (sg * 0.37, -0.60, 0.74, 'skin'),
              (sg * 0.40, -0.50, -0.30, 'skin')] if False else None
    pts = []
    for sg in (1, -1):
        pts += [(sg * 0.36, U_TOP + 0.03, 0.76, 'glass'), (sg * 0.14, -0.18, 0.68, 'glass'),
                (sg * 0.27, -0.24, 0.70, 'glass'), (sg * 0.40, CONSOLE_U + 0.02, 0.0, 'skin'),
                (sg * 0.40, CONSOLE_U + 0.02, 0.45, 'skin'), (sg * 0.36, -0.60, 0.74, 'skin'),
                (sg * 0.36, -0.36, 0.74, 'skin')]
    best = 0.7 * tub.s
    for k in np.linspace(tub.s, 0.7 * tub.s, 16):
        e = A.Eye(tub, k)
        ok = True
        for r, u, f, kind in pts:
            p = e.p(r, u, f)
            if kind == 'glass':
                g = ac.glass_above(p[0], p[2], p[1] - 0.5)
                if g is None or g < p[1] + 0.02:
                    ok = False; break
            elif not tub.fits(ac, p[0], p[1], p[2]):
                ok = False; break
        if ok:
            best = k
            break
    return best


# ---------------------------------------------------------------- the instrument layout (plane, a, u, kind, size)
# a: metres right on the plane (centre board: from the centre line; wings: from the hinge outward, negative on the
# left), u: eye-relative height of the instrument centre. Kinds: a gauge kind, 'D:<page>', 'A:<lamps>' or 'paint'.

TWP = ('TWP', 1, [('HANDOFF', 'NONE', 'green'), ('LAUNCH', 'MISSILE_LAUNCH', 'red'), ('PRI|MODE', 'NONE', 'amber'),
                  ('UNK', 'NONE', 'amber'), ('TGT|SEP', 'NONE', 'green')])
GEAR = ('LG', 3, [('NOSE', 'GEAR_DOWN', 'green'), ('LEFT', 'GEAR_DOWN', 'green'), ('RIGHT', 'GEAR_DOWN', 'green')])
EPU = ('EPU', 3, [('EPU|RUN', 'NONE', 'green'), ('HYDRAZN', 'NONE', 'amber'), ('AIR', 'NONE', 'amber')])
EYEBROW_L = ('EYEBROW_L', 1, [('MASTER|CAUTION', 'MASTER_CAUTION', 'amber'), ('TF FAIL', 'NONE', 'amber')])
EYEBROW_R = ('EYEBROW_R', 2, [('ENG FIRE', 'FIRE', 'red'), ('ENGINE', 'ENGINE_OUT', 'red'),
                              ('HYD/OIL|PRESS', 'DAMAGE', 'red'), ('FLCS', 'NONE', 'red'),
                              ('DBU ON', 'NONE', 'amber'), ('TO/LDG|CONFIG', 'GEAR_TRANSIT', 'red'),
                              ('CANOPY', 'NONE', 'red'), ('OXY LOW', 'NONE', 'red')])
AOA_IDX = ('AOA', 1, [('▼', 'STALL', 'green'), ('○', 'NONE', 'amber'), ('▲', 'NONE', 'red')])
AR_IDX = ('AR', 1, [('RDY', 'NONE', 'green'), ('AR/NWS', 'NONE', 'green'), ('DISC', 'NONE', 'amber')])
CAUTION = ('CAUTION', 4, [
    ('FLCS FAULT', 'DAMAGE', 'amber'), ('ENGINE|FAULT', 'ENGINE_OUT', 'amber'), ('AVIONICS|FAULT', 'NONE', 'amber'),
    ('SEAT NOT|ARMED', 'NONE', 'amber'),
    ('ELEC SYS', 'NONE', 'amber'), ('SEC', 'NONE', 'amber'), ('EQUIP HOT', 'NONE', 'amber'), ('NWS FAIL', 'NONE', 'amber'),
    ('PROBE|HEAT', 'NONE', 'amber'), ('FUEL/OIL|HOT', 'NONE', 'amber'), ('RADAR ALT', 'LOW_ALT', 'amber'),
    ('ANTI SKID', 'NONE', 'amber'),
    ('CADC', 'NONE', 'amber'), ('INLET|ICING', 'NONE', 'amber'), ('IFF', 'NONE', 'amber'), ('HOOK', 'NONE', 'amber'),
    ('STORES|CONFIG', 'NONE', 'amber'), ('OVERHEAT', 'FIRE', 'amber'), ('NUCLEAR', 'NONE', 'amber'),
    ('OBOGS', 'NONE', 'amber'),
    ('ATF NOT|ENGAGED', 'NONE', 'amber'), ('EEC', 'NONE', 'amber'), ('CABIN|PRESS', 'NONE', 'amber'), ('', 'NONE', 'amber'),
    ('FWD FUEL|LOW', 'LOW_FUEL', 'amber'), ('BUC', 'NONE', 'amber'), ('', 'NONE', 'amber'), ('', 'NONE', 'amber'),
    ('AFT FUEL|LOW', 'LOW_FUEL', 'amber'), ('', 'NONE', 'amber'), ('', 'NONE', 'amber'), ('', 'NONE', 'amber')])

MAIN = [
    # Layout after the reference photos (Block 52 front panel; Falcon BMS Block 50 pilot's view): the ICP under the
    # HUD in the middle; RWR scope upper left; the DED on its own bezel upper right with the standby ADI under it;
    # the MFDs low either side; airspeed and altimeter under the ICP, the ADI under them with the AOA and VVI
    # beside it, the EHSI on the pedestal; the engine column outboard of the right MFD; the arming switches down the
    # left edge.
    ('centre', -0.165, -0.360, 'RWR', 0.068, 'RWR'),
    ('centre', 0.158, -0.336, 'paint:DED', (0.105, 0.046), None),
    ('centre', 0.168, -0.398, 'ATTITUDE', 0.046, 'SAI'),
    ('centre', -0.162, -0.500, 'D:RADAR', 0.104, None),
    ('centre', 0.162, -0.500, 'D:STORES', 0.104, None),
    ('centre', -0.044, -0.484, 'AIRSPEED', 0.048, 'AIRSPEED/MACH'),
    ('centre', 0.044, -0.484, 'ALTIMETER', 0.048, 'ALT'),
    ('centre', -0.064, -0.562, 'AOA', 0.030, 'AOA'),
    ('centre', 0.0, -0.562, 'ATTITUDE', 0.056, 'ADI'),
    ('centre', 0.064, -0.562, 'VVI', 0.030, 'VVI'),
    # pedestal
    ('pedestal', 0.0, -0.655, 'HEADING', 0.070, 'EHSI'),
    ('pedestal', -0.085, -0.655, 'paint:INSTR', (0.06, 0.05), None),
    ('pedestal', 0.085, -0.655, 'paint:FUELSEL', (0.06, 0.05), None),
    # right wing: fuel flow, then the engine column
    ('right', 0.062, -0.335, 'FILLER_FUEL', 0.044, 'FUEL FLOW'),
    ('right', 0.062, -0.395, 'FILLER_OIL', 0.044, 'OIL'),
    ('right', 0.062, -0.450, 'FILLER_RPM', 0.044, 'NOZ POS'),
    ('right', 0.062, -0.505, 'ENGINE_RPM', 0.046, 'RPM'),
    ('right', 0.062, -0.562, 'EGT', 0.046, 'FTIT'),
    # left wing: threat warning prime, the arming switches, the landing gear panel
    ('left', -0.060, -0.335, 'A:TWP', (0.036, 0.075), None),
    ('left', -0.060, -0.418, 'paint:MISC', (0.078, 0.046), None),
    ('left', -0.060, -0.470, 'paint:MISC2', (0.078, 0.046), None),
    ('left', -0.060, -0.530, 'A:LG', (0.075, 0.020), None),
]
RIGHT_AUX = [   # (a, b) on the right auxiliary console's plane
    (0.038, 0.030, 'A:CAUTION', (0.088, 0.112), None),
    (-0.050, 0.052, 'FUEL_QTY', 0.056, 'FUEL QTY'),
    (-0.070, -0.012, 'FILLER_HYDRAULIC', 0.034, 'HYD A'),
    (-0.030, -0.012, 'FILLER_HYDRAULIC', 0.034, 'HYD B'),
    (-0.066, -0.066, 'FILLER_FUEL', 0.034, 'EPU FUEL'),
    (-0.020, -0.066, 'CLOCK', 0.040, 'CLOCK'),
    (0.040, -0.066, 'FILLER_OXYGEN', 0.036, 'CABIN PRESS'),
]
LEFT_AUX = [
    (0.0, 0.045, 'paint:CMDS', (0.150, 0.075), None),
    (0.045, -0.050, 'paint:TEST', (0.065, 0.065), None),
]


def build(ac, tub, sw, atlas):
    k = fit_scale(ac, tub)
    e = A.Eye(tub, k)
    E = e.E
    m, stick = L.Mesh(), L.Mesh()
    gauges, displays, glass = [], [], []
    sid = tub.seat
    col = {name: atlas.flat(atlas.swatch(rgb)) for name, rgb in dict(
        panel=PANEL, panel_d=PANEL_D, gull=GULL, gull_d=GULL_D, coaming=COAMING, floor=FLOOR, frame=SEAT_FRAME,
        cushion=CUSHION, headbox=HEADBOX, yellow=YELLOW, red=RED, metal=METAL, grip=GRIP, white=WHITE,
        mgrey=(88, 92, 94), lgrey=(150, 154, 156), dgrey=(44, 46, 48), bezel=(28, 29, 30)).items()}
    lean = math.radians(LEAN)
    up15 = e.d(0, math.cos(lean), math.sin(lean))

    # -- planes
    def cb(u, plane_u0):
        return (u - plane_u0) / math.cos(lean)
    cen = Plane(e, e.p(0, -0.45, F_PANEL), e.d(1, 0, 0), up15)
    hinge_r, hinge_l = cen.at(CEN_A, 0), cen.at(-CEN_A, 0)
    t = math.radians(WING_TURN)
    right = Plane(e, hinge_r, e.d(math.cos(t), 0, -math.sin(t)), up15)
    left = Plane(e, hinge_l, e.d(math.cos(t), 0, math.sin(t)), up15)
    ped_lean = math.radians(50)
    ped = Plane(e, e.p(0, -0.66, 0.70), e.d(1, 0, 0), e.d(0, math.cos(ped_lean), math.sin(ped_lean)))
    aux_lean, aux_turn = math.radians(45), math.radians(10)
    aux = {sg: Plane(e, e.p(sg * 0.30, -0.70, 0.55), e.d(math.cos(aux_turn), 0, sg * math.sin(aux_turn)),
                     e.d(0, math.cos(aux_lean), math.sin(aux_lean))) for sg in (1, -1)}
    planes = dict(centre=cen, right=right, left=left, pedestal=ped)
    b_top, b_bot = cb(U_TOP, -0.45), cb(U_BOT, -0.45)
    extent = dict(centre=(-CEN_A, CEN_A, b_bot, b_top), right=(0.0, WING_W, b_bot, b_top),
                  left=(-WING_W, 0.0, b_bot, b_top), pedestal=(-0.13, 0.13, -0.07, 0.07))

    def plane_b(name, u):
        if name == 'pedestal':
            return (u + 0.66) / math.cos(ped_lean)
        return cb(u, -0.45)

    # -- painted faces of the main panel parts: recesses behind every instrument, and the placards
    def face_painter(name, items):
        a0, a1, b0, b1 = extent[name]
        P = Painter(a0, a1, b0, b1, 1100)
        def fn(d, im):
            noisy(im, PANEL, 3, seed=hash(name) % 97)
            # panel seams and screws: Dzus-fastened sub-panels
            screws(d, P.W * A.Atlas.SS, P.H * A.Atlas.SS, P.mm(8), P.mm(2.6))
            for it in items:
                _, a, u, kind, size, label = it
                b = plane_b(name, u)
                x, y = P.to(a, b)
                if kind.startswith('D:'):
                    w = size * 1.3
                    d.rectangle(P.box(a, b, w + 0.008, w + 0.008), fill=(16, 16, 17, 255))
                elif kind.startswith('A:') or kind.startswith('paint:'):
                    w, h = size
                    d.rectangle(P.box(a, b, w + 0.006, h + 0.006), fill=(18, 18, 19, 255))
                else:
                    r = (size / 2 * 1.18 + 0.003) * P.S
                    d.ellipse([x - r, y - r, x + r, y + r], fill=(16, 16, 17, 255))
                if label:
                    off = (size / 2 * 1.18 + 0.008) if not isinstance(size, tuple) else size[1] / 2 + 0.008
                    text(d, (x, y + off * P.S), label, P.mm(6.5), fill=LETTER)
            if name == 'centre':
                # the MFD labels and the instrument panel's own markings
                for a in (-0.158, 0.158):
                    text(d, P.to(a, plane_b(name, -0.468 - 0.112 * 0.65 - 0.006)), '', P.mm(6))
            if name == 'left':
                # landing gear handle plate, hook and DN LOCK REL
                x, y = P.to(-0.060, plane_b(name, -0.585))
                d.rectangle([x - P.mm(36), y - P.mm(16), x + P.mm(36), y + P.mm(16)], fill=(18, 18, 19, 255))
                text(d, (x - P.mm(20), y + P.mm(11)), 'LG', P.mm(6), fill=LETTER)
                text(d, (x + P.mm(20), y - P.mm(10)), 'UP', P.mm(5), fill=LETTER)
                text(d, (x + P.mm(20), y + P.mm(10)), 'DN', P.mm(5), fill=LETTER)
                text(d, (x, y + P.mm(24)), 'DN LOCK REL', P.mm(5), fill=LETTER)
        return P, fn

    for name, plane in planes.items():
        items = [it for it in MAIN if it[0] == name]
        P, fn = face_painter(name, items)
        rect = atlas.paint(P.W, P.H, fn)
        a0, a1, b0, b1 = extent[name]
        A.slab(m, plane, a0, a1, b0, b1, None, col['panel_d'], depth=e.m(0.025), face_rect=rect, atlas=atlas)

    # -- instruments on the main panel parts
    def put(plane, a, b, kind, size, label=None):
        c = plane.at(a, b)
        if kind.startswith('D:'):
            displays.append({'Seat': sid, 'Center': L.r5(c + plane.N * L.DISPLAY_LIFT), 'Normal': L.r5(plane.N),
                             'Up': L.r5(plane.U), 'Width': round(e.m(size), 4), 'Depth': 0.035, 'Kind': kind[2:],
                             'Style': 'LCD'})
            # its own black hood under the glareshield, as in the photos: a top plate reaching toward the pilot,
            # sides tapering down to the housing's lower corners
            R, U, N = plane.axes()
            hw = e.m(size * (1 + 2 * L.DISPLAY_BEZEL) / 2 + 0.006)
            d_top, d_bot = e.m(0.045), e.m(0.008)
            m.box(c + U * (hw + e.m(0.003)) + N * d_top / 2, (R, U, N), (hw + e.m(0.004), e.m(0.003), d_top / 2),
                  col['coaming'])
            for sg_ in (-1, 1):
                p0 = c + R * sg_ * (hw + e.m(0.002)) - U * hw; p1 = c + R * sg_ * (hw + e.m(0.002)) + U * hw
                q1 = p1 + N * d_top; q0 = p0 + N * d_bot
                m.flat([p0, p1, q1, q0], col['coaming'], normal=R * sg_)
                m.flat([p0, p1, q1, q0], col['coaming'], normal=-R * sg_)
        elif kind.startswith('A:'):
            w, h = size
            spec = {'TWP': TWP, 'LG': GEAR, 'EPU': EPU, 'CAUTION': CAUTION}[kind[2:]]
            annunciator(c + plane.N * 0.002, plane, w, h, spec)
        elif kind.startswith('paint:'):
            w, h = size
            rect = atlas.paint(int(w * 1100), int(h * 1100), PAINTERS[kind[6:]](w, h))
            A.plate(m, atlas, plane, a, b, w, h, rect, proud=0.004, edge=col['panel_d'])
            for (sa, sb, kind2) in SWITCHES.get(kind[6:], []):
                switch(plane, a + sa * w, b + sb * h, kind2)
        else:
            gauges.append({'Seat': sid, 'Kind': kind, 'Center': L.r5(c + plane.N * L.GAUGE_LIFT),
                           'Normal': L.r5(plane.N), 'Up': L.r5(plane.U), 'Diameter': round(e.m(size), 4),
                           'Depth': 0.03})

    def annunciator(c, plane, w, h, spec, depth=0.02):
        _, cols, lamps = spec
        gauges.append({'Seat': sid, 'Kind': 'ANNUNCIATOR', 'Center': L.r5(c), 'Normal': L.r5(plane.N),
                       'Up': L.r5(plane.U), 'Diameter': round(e.m(w), 4), 'Width': round(e.m(w), 4),
                       'Height': round(e.m(h), 4), 'Depth': depth, 'Columns': cols,
                       'Lamps': [{'Text': tx, 'Condition': cd, 'Colour': cl} for tx, cd, cl in lamps]})

    def switch(plane, a, b, kind):
        """a modelled control on a plane: 'toggle', 'knob', 'button', 'guard' (red guarded toggle)"""
        R, U, N = plane.axes()
        c = plane.at(a, b)
        if kind in ('toggle', 'guard'):
            m.box(c + N * e.m(0.003), (R, U, N), (e.m(0.005), e.m(0.005), e.m(0.003)), col['mgrey'])
            lever = unit(U * 0.5 + N)
            m.box(c + N * e.m(0.006) + lever * e.m(0.009), (R, unit(np.cross(N, R) * 0 + lever), unit(np.cross(R, lever))),
                  (e.m(0.0022), e.m(0.009), e.m(0.0022)), col['lgrey'])
            if kind == 'guard':
                m.box(c + N * e.m(0.008) + U * e.m(0.002), (R, U, N), (e.m(0.007), e.m(0.009), e.m(0.0015)), col['red'])
        elif kind == 'knob':
            m.box(c + N * e.m(0.006), (R, U, N), (e.m(0.007), e.m(0.007), e.m(0.006)), col['grip'])
            m.box(c + N * e.m(0.0125) + U * e.m(0.003), (R, U, N), (e.m(0.0012), e.m(0.004), e.m(0.0006)), col['white'])
        elif kind == 'button':
            m.box(c + N * e.m(0.003), (R, U, N), (e.m(0.005), e.m(0.004), e.m(0.003)), col['dgrey'])

    for name, a, u, kind, size, label in MAIN:
        put(planes[name], a, plane_b(name, u), kind, size, label)

    # -- landing gear handle: a lever with a wheel-shaped knob (red light in it), below the gear lamps
    g0 = left.at(-0.060, plane_b('left', -0.585))
    R, U, N = left.axes()
    m.box(g0 + N * e.m(0.008), (R, U, N), (e.m(0.006), e.m(0.020), e.m(0.008)), col['metal'])
    m.box(g0 + N * e.m(0.030) + U * e.m(0.010), (R, U, N), (e.m(0.018), e.m(0.009), e.m(0.012)), col['white'])
    m.box(g0 + N * e.m(0.043) + U * e.m(0.010), (R, U, N), (e.m(0.006), e.m(0.004), e.m(0.002)), col['red'])

    # -- auxiliary consoles
    for sg, items in ((1, RIGHT_AUX), (-1, LEFT_AUX)):
        p = aux[sg]
        P = Painter(-0.09, 0.09, -0.10, 0.10, 1100)
        def fn(d, im, items=items, P=P, sg=sg):
            noisy(im, PANEL, 3, seed=11 + sg)
            screws(d, P.W * A.Atlas.SS, P.H * A.Atlas.SS, P.mm(7), P.mm(2.4))
            for a, b, kind, size, label in items:
                x, y = P.to(a, b)
                if isinstance(size, tuple):
                    d.rectangle(P.box(a, b, size[0] + 0.006, size[1] + 0.006), fill=(18, 18, 19, 255))
                else:
                    r = (size / 2 * 1.18 + 0.003) * P.S
                    d.ellipse([x - r, y - r, x + r, y + r], fill=(16, 16, 17, 255))
                    text(d, (x, y + (size / 2 * 1.18 + 0.007) * P.S), label, P.mm(5.5), fill=LETTER)
            if sg < 0:
                x, y = P.to(-0.050, -0.050)
                d.rectangle([x - P.mm(22), y - P.mm(26), x + P.mm(22), y + P.mm(26)], fill=(18, 18, 19, 255))
                text(d, (x, y + P.mm(30)), 'ALT GEAR', P.mm(6), fill=LETTER)
        rect = atlas.paint(P.W, P.H, fn)
        A.slab(m, p, -0.09, 0.09, -0.10, 0.10, None, col['panel_d'], depth=e.m(0.03), face_rect=rect, atlas=atlas)
        for a, b, kind, size, label in items:
            put(p, a, b, kind, size, label)
    # ALT GEAR handle: yellow and black T on the left aux console
    p = aux[-1]; R, U, N = p.axes(); c = p.at(-0.050, -0.050)
    m.box(c + N * e.m(0.012), (R, U, N), (e.m(0.004), e.m(0.004), e.m(0.012)), col['metal'])
    m.box(c + N * e.m(0.026), (R, U, N), (e.m(0.020), e.m(0.008), e.m(0.006)), col['yellow'])

    # -- glareshield: from the panel lip forward to the windscreen base, under the glass
    lip = []
    for name, aa in (('left', np.linspace(-WING_W, 0, 3)), ('centre', np.linspace(-CEN_A, CEN_A, 7)),
                     ('right', np.linspace(0, WING_W, 3))):
        for a in aa:
            q = planes[name].at(a, b_top)
            if lip and np.linalg.norm(lip[-1] - q) < 1e-6:
                continue
            lip.append(q)
    lip_edge = [q + e.F * e.m(0.05) + e.U * e.m(0.02) for q in lip]
    ws_z = max(tub.ws_z, lip_edge[0][2] + 0.1)
    rows = []
    for kk, tt in enumerate((0.0, 0.35, 0.7, 1.0)):
        row = []
        for le in lip_edge:
            sx = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            z = le[2] + (ws_z - le[2]) * tt
            x = le[0] + (E[0] + sx * tub.W * 0.85 - le[0]) * tt
            y = le[1] + e.m(0.03) * math.sin(math.pi * tt) * (1 - sx * sx) - e.m(0.04) * tt * sx * sx
            if kk:
                while abs(x - E[0]) > 0.05 and ac.glass_above(x, z, y - 0.3) is None:
                    x -= np.sign(x - E[0]) * 0.02
                d_ = ac.skin_x(y, z, E[0])[0 if x > E[0] else 1]
                if abs(x - E[0]) > d_ - 0.01:
                    x = E[0] + np.sign(x - E[0]) * max(0.05, d_ - 0.01)
            g = ac.glass_above(x, z, y - 0.3)
            if g is not None and g < y + 0.02:
                y = g - 0.02
            row.append(np.array([x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], col['coaming'], normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):
        a_, b_ = lip_edge[j], lip_edge[j + 1]
        da, db = a_ - e.U * e.m(0.015), b_ - e.U * e.m(0.015)
        m.flat([a_, b_, db, da], col['coaming'], toward=E)
        m.flat([da, db, lip[j + 1], lip[j]], col['coaming'], normal=[0, -1, 0])
    for j in (0, len(lip_edge) - 1):
        for i in range(len(rows) - 1):
            p_, q_ = rows[i][j], rows[i + 1][j]
            lo = min(p_[1], q_[1]) - e.m(0.12)
            m.flat([p_, q_, [q_[0], lo, q_[2]], [p_[0], lo, p_[2]]], col['coaming'], toward=[E[0], lo, (p_[2] + q_[2]) / 2])

    # -- HUD body on the glareshield, the combiner over it, and the up-front controls hanging under it
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    hud_f0, hud_f1 = 0.64, 0.86
    lip_y = lip_edge[len(lip_edge) // 2][1]
    hy0, hy1 = lip_y - e.m(0.04), lip_y + e.m(0.05)
    hz0, hz1 = E[2] + e.m(hud_f0), E[2] + e.m(hud_f1)
    m.box([E[0], (hy0 + hy1) / 2, (hz0 + hz1) / 2], (X, Y, Z), (e.m(0.085), (hy1 - hy0) / 2, (hz1 - hz0) / 2),
          col['dgrey'], faces={(2, -1): col['coaming']})
    g_lean = math.radians(20)
    g_up = np.array([0, math.cos(g_lean), -math.sin(g_lean)])
    g_n = unit(np.cross(X, g_up))
    g_h, g_z = 0.0, hz0
    for gz in np.arange(hz0 + e.m(0.06), hz0 - e.m(0.10), -0.01):
        h = e.m(0.135)
        above = ac.glass_above(E[0], gz - g_up[2] * h / 2, E[1])
        if above is not None:
            h = min(h, 2 * (above - 0.02 - E[1]) / g_up[1])
        if h > g_h + 1e-6:
            g_h, g_z = h, gz
        if g_h >= e.m(0.11):
            break
    hud = None
    if g_h > e.m(0.05):
        g_bot = np.array([E[0], E[1] - g_up[1] * g_h / 2, g_z + g_up[2] * g_h / 2])
        g_top = g_bot + g_up * g_h
        g_hw = e.m(0.072)
        stem = g_bot[1] - 0.004 - hy1
        if stem > 0.004:
            m.box([E[0], hy1 + stem / 2, g_bot[2]], (X, Y, Z), (g_hw * 0.5, stem / 2, e.m(0.012)), col['dgrey'])
        m.box(g_bot - g_up * 0.006, (X, g_up, g_n), (g_hw + 0.008, 0.006, 0.012), col['dgrey'])
        for s_ in (-1, 1):
            m.box((g_bot + g_top) / 2 + X * s_ * (g_hw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), col['dgrey'])
        gc = [g_bot - X * g_hw, g_bot + X * g_hw, g_top + X * g_hw, g_top - X * g_hw]
        glass += [gc[0], gc[1], gc[2], gc[0], gc[2], gc[3]]
        hud = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)
    # the integrated control panel: a box standing out of the panel's top centre under the HUD, its face toward the
    # pilot, 12 degrees back (the DED is on its own bezel, upper right of the main panel)
    ufc_lean = math.radians(12)
    ufc = Plane(e, e.p(0, -0.322, 0.625), e.d(1, 0, 0), e.d(0, math.cos(ufc_lean), math.sin(ufc_lean)))
    R, U, N = ufc.axes()
    back_to_panel = max(e.m(0.04), float((ufc.o - cen.at(0, cb(-0.322, -0.45))) @ -N) + e.m(0.01))
    m.box(ufc.at(0, 0) - N * back_to_panel / 2, (R, U, N),
          (e.m(ICP_W / 2 + 0.006), e.m(ICP_H / 2 + 0.006), back_to_panel / 2), col['dgrey'])
    icp = atlas.paint(int(ICP_W * 1500), int(ICP_H * 1500), p_icp)
    A.plate(m, atlas, ufc, 0, 0, ICP_W, ICP_H, icp, proud=0.003, edge=col['dgrey'])
    for a in (-0.083, 0.083):               # the rocker wheels' housings
        for b in (0.016, -0.022):
            m.box(ufc.at(a, b, 0.003) + N * e.m(0.004), (R, U, N), (e.m(0.006), e.m(0.014), e.m(0.004)), col['grip'])

    # -- eyebrow lights and the indexers on the glareshield either side of the HUD
    def brow(r, u, f, w, h, spec):
        pl = Plane(e, e.p(r, u, f), e.d(1, 0, 0), e.d(0, math.cos(math.radians(10)), math.sin(math.radians(10))))
        R, U, N = pl.axes()
        m.box(pl.at(0, 0) - N * e.m(0.03), (R, U, N), (e.m(w / 2 + 0.008), e.m(h / 2 + 0.008), e.m(0.03)), col['coaming'])
        annunciator(pl.at(0, 0, 0.002), pl, w, h, spec)
    brow(-0.236, -0.262, 0.74, 0.046, 0.050, EYEBROW_L)
    brow(0.240, -0.262, 0.74, 0.074, 0.060, EYEBROW_R)
    brow(-0.120, -0.240, 0.72, 0.020, 0.045, AOA_IDX)
    brow(0.120, -0.240, 0.72, 0.024, 0.045, AR_IDX)

    # -- side consoles, walls, floor, bulkheads, footwell
    for sg in (1, -1):                      # +1 = the pilot's right
        x_in, x_out = sg * CONSOLE_R0, sg * CONSOLE_R1
        f0, f1 = BACK_F + 0.05, 0.47
        top = [e.p(x_in, CONSOLE_U, f0), e.p(x_out, CONSOLE_U, f0), e.p(x_out, CONSOLE_U, f1), e.p(x_in, CONSOLE_U, f1)]
        P = Painter(0, CONSOLE_R1 - CONSOLE_R0, f0, f1, 900)
        rect = atlas.paint(P.W, P.H, console_painter(sg, P))
        # tile: s across from inboard to outboard, t from aft to forward
        uvs = [atlas.uv(rect, 0, 0), atlas.uv(rect, 1, 0), atlas.uv(rect, 1, 1), atlas.uv(rect, 0, 1)]
        m.face(top, uvs, normal=[0, 1, 0])
        for k_, (fa, fb) in enumerate(CONSOLE_CONTROLS[sg]):
            # each labelled control: a knob or a toggle at its painted marker, and on most panels a second, smaller
            # toggle or push-button beside it, so the consoles read as densely packed as in the photos
            pl = Plane(e, e.p(sg * (CONSOLE_R0 + fa * (CONSOLE_R1 - CONSOLE_R0)), CONSOLE_U, fb), e.d(sg, 0, 0),
                       e.d(0, 0, 1))
            switch(pl, 0, 0, 'knob' if k_ % 3 else 'toggle')
            if k_ % 4 != 3:
                switch(pl, 0.0, -0.016, 'button' if k_ % 2 else 'toggle')
        m.flat([e.p(x_in, FLOOR_U, f0), e.p(x_in, FLOOR_U, f1), e.p(x_in, CONSOLE_U, f1), e.p(x_in, CONSOLE_U, f0)],
               col['gull_d'], normal=e.d(-sg, 0, 0))
        m.flat([e.p(x_in, FLOOR_U, f1), e.p(x_out, FLOOR_U, f1), e.p(x_out, CONSOLE_U, f1), e.p(x_in, CONSOLE_U, f1)],
               col['gull_d'], normal=e.d(0, 0, -1))
        # outer wall from the console up to the sill
        sill_u = (tub.sill - E[1]) / k
        m.flat([e.p(x_out, CONSOLE_U, BACK_F), e.p(x_out, CONSOLE_U, 0.62), e.p(x_out, sill_u, 0.62),
                e.p(x_out, sill_u, BACK_F)], col['gull'], normal=e.d(-sg, 0, 0))
        # the sill: a broad grey ledge from the wall out to the canopy rail, closing the gap above the wall
        m.box(e.p(sg * (CONSOLE_R1 + 0.03), sill_u + 0.01, (BACK_F + 0.62) / 2), (X, Y, Z),
              (e.m(0.05), e.m(0.012), e.m((0.62 - BACK_F) / 2)), col['gull'])
        # footwell side from the console's front to the bulkhead
        m.flat([e.p(sg * 0.20, FLOOR_U, f1), e.p(sg * 0.20, FLOOR_U, 1.12), e.p(sg * 0.20, -0.70, 1.12),
                e.p(sg * 0.20, -0.70, f1)], col['gull_d'], normal=e.d(-sg, 0, 0))
    m.flat([e.p(-0.20, FLOOR_U, BACK_F), e.p(0.20, FLOOR_U, BACK_F), e.p(0.20, FLOOR_U, 1.12),
            e.p(-0.20, FLOOR_U, 1.12)], col['floor'], normal=[0, 1, 0])
    m.flat([e.p(-0.20, FLOOR_U, 1.12), e.p(0.20, FLOOR_U, 1.12), e.p(0.20, -0.70, 1.12), e.p(-0.20, -0.70, 1.12)],
           col['gull_d'], normal=e.d(0, 0, -1))
    m.flat([e.p(-0.20, -0.70, 0.66), e.p(0.20, -0.70, 0.66), e.p(0.20, -0.70, 1.12), e.p(-0.20, -0.70, 1.12)],
           col['panel_d'], normal=[0, -1, 0])
    sill_u = (tub.sill - E[1]) / k
    m.flat([e.p(-CONSOLE_R1, FLOOR_U, BACK_F), e.p(CONSOLE_R1, FLOOR_U, BACK_F), e.p(CONSOLE_R1, sill_u, BACK_F),
            e.p(-CONSOLE_R1, sill_u, BACK_F)], col['gull'], normal=e.d(0, 0, 1))
    # the tub liner: full-width floor, the side walls on to the footwell bulkhead and the bulkhead itself up behind
    # the panel, so no gap shows the fuselage's inside; the strips between each aux console's top and the panel
    # wing's bottom are closed too
    m.flat([e.p(-CONSOLE_R1, FLOOR_U - 0.005, BACK_F), e.p(CONSOLE_R1, FLOOR_U - 0.005, BACK_F),
            e.p(CONSOLE_R1, FLOOR_U - 0.005, FRONT_F), e.p(-CONSOLE_R1, FLOOR_U - 0.005, FRONT_F)], col['gull_d'],
           normal=[0, 1, 0])
    for sg in (1, -1):
        m.flat([e.p(sg * CONSOLE_R1, FLOOR_U, 0.62), e.p(sg * CONSOLE_R1, FLOOR_U, FRONT_F),
                e.p(sg * CONSOLE_R1, sill_u, FRONT_F), e.p(sg * CONSOLE_R1, sill_u, 0.62)], col['gull_d'],
               normal=e.d(-sg, 0, 0))
        m.flat([e.p(sg * CONSOLE_R1, FLOOR_U, BACK_F), e.p(sg * CONSOLE_R1, FLOOR_U, 0.62),
                e.p(sg * CONSOLE_R1, CONSOLE_U, 0.62), e.p(sg * CONSOLE_R1, CONSOLE_U, BACK_F)], col['gull_d'],
               normal=e.d(-sg, 0, 0))
        p = aux[sg]
        wing = right if sg > 0 else left
        wa0, wa1 = (0.0, WING_W) if sg > 0 else (-WING_W, 0.0)
        a_top = [p.at(-0.09, 0.10), p.at(0.09, 0.10)]
        w_bot = [wing.at(wa0, b_bot), wing.at(wa1, b_bot)]
        if sg < 0:
            w_bot = w_bot[::-1]
        m.flat([a_top[0], a_top[1], w_bot[1], w_bot[0]], col['panel_d'], toward=E)
    m.flat([e.p(-CONSOLE_R1, FLOOR_U, FRONT_F), e.p(CONSOLE_R1, FLOOR_U, FRONT_F), e.p(CONSOLE_R1, -0.30, FRONT_F),
            e.p(-CONSOLE_R1, -0.30, FRONT_F)], col['gull_d'], normal=e.d(0, 0, -1))
    for sg in (1, -1):                      # rudder pedals
        m.box(e.p(sg * 0.11, -0.98, 1.02), (X, unit([0, 1, -0.45]), unit([0, 0.45, 1])),
              (e.m(0.045), e.m(0.075), e.m(0.012)), col['mgrey'])
        m.box(e.p(sg * 0.11, -1.05, 1.07), (X, Y, Z), (e.m(0.012), e.m(0.03), e.m(0.06)), col['dgrey'])

    # -- side-stick on its armrest (right console), throttle (left console)
    m.box(e.p(0.29, CONSOLE_U + 0.045, 0.28), (X, Y, Z), (e.m(0.045), e.m(0.045), e.m(0.15)), col['gull_d'])
    m.box(e.p(0.29, CONSOLE_U + 0.093, 0.26), (X, Y, Z), (e.m(0.040), e.m(0.005), e.m(0.12)), col['cushion'])
    foot = e.p(0.29, CONSOLE_U + 0.06, 0.46)
    m.box(foot - Y * e.m(0.01), (X, Y, Z), (e.m(0.03), e.m(0.012), e.m(0.035)), col['grip'])
    ax = (X, unit([0, 1, 0.22]), unit([0, -0.22, 1]))
    gc = foot + ax[1] * e.m(0.075)
    stick.box(gc, ax, (e.m(0.019), e.m(0.058), e.m(0.026)), col['grip'])
    stick.box(gc + ax[1] * e.m(0.062), ax, (e.m(0.016), e.m(0.006), e.m(0.02)), col['dgrey'])
    stick.box(gc + ax[2] * e.m(0.028) + ax[1] * e.m(0.02), ax, (e.m(0.006), e.m(0.01), e.m(0.004)), col['metal'])
    stick.box(gc + ax[1] * e.m(0.068) + ax[2] * e.m(0.008), ax, (e.m(0.005), e.m(0.004), e.m(0.005)), col['red'])
    stick.box(gc + ax[1] * e.m(0.05) - X * e.m(0.019), ax, (e.m(0.004), e.m(0.006), e.m(0.006)), col['mgrey'])
    tq = e.p(-0.30, CONSOLE_U, 0.26)
    m.box(tq + Y * e.m(0.012), (X, Y, Z), (e.m(0.03), e.m(0.012), e.m(0.11)), col['grip'])
    m.box(tq + Y * e.m(0.05) + Z * e.m(0.02), (X, unit([0, 1, 0.3]), unit([0, -0.3, 1])),
          (e.m(0.01), e.m(0.04), e.m(0.012)), col['metal'])
    tg = tq + Y * e.m(0.10) + Z * e.m(0.035)
    t_ax = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
    m.box(tg, t_ax, (e.m(0.032), e.m(0.035), e.m(0.055)), col['grip'])
    m.box(tg + t_ax[1] * e.m(0.036), t_ax, (e.m(0.026), e.m(0.004), e.m(0.045)), col['dgrey'])
    for j in range(3):
        m.box(tg + t_ax[1] * e.m(0.04) + t_ax[2] * e.m(-0.025 + j * 0.022), t_ax, (e.m(0.005), e.m(0.004), e.m(0.005)),
              col['mgrey'] if j else col['red'])

    aces_ii(m, e, col)
    return m, stick, gauges, displays, glass, hud, foot


def aces_ii(m, e, col):
    """ACES II: reclined 30 degrees, sage-green cushions on a dark frame, a headbox with the canopy breakers, the
    yellow and black ejection handle at the front of the seat pan, and the survival kit under it"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    rec = math.radians(30)
    up = unit(e.U * math.cos(rec) - e.F * math.sin(rec))       # up the seat back, leaning aft
    fw = unit(np.cross(X, up)) if np.cross(X, up) @ e.F > 0 else -unit(np.cross(X, up))
    hip = e.p(0, -0.72, 0.20)
    pan_ax = (X, unit([0, 1, -0.12]), unit([0, 0.12, 1]))
    m.box(hip + Z * e.m(0.20) - Y * e.m(0.08), pan_ax, (e.m(0.20), e.m(0.07), e.m(0.22)), col['frame'])    # kit
    m.box(hip + Z * e.m(0.20) + Y * e.m(0.0), pan_ax, (e.m(0.19), e.m(0.03), e.m(0.21)), col['cushion'])
    for sg in (1, -1):
        m.box(hip + X * sg * e.m(0.215) + Z * e.m(0.16) + Y * e.m(0.04), (X, Y, Z),
              (e.m(0.012), e.m(0.12), e.m(0.20)), col['frame'])
    back_c = hip + up * e.m(0.36) - fw * e.m(0.06)
    m.box(back_c, (X, up, fw), (e.m(0.19), e.m(0.34), e.m(0.035)), col['cushion'])
    m.box(back_c - fw * e.m(0.06), (X, up, fw), (e.m(0.21), e.m(0.38), e.m(0.03)), col['frame'])
    for sg in (1, -1):
        m.box(back_c - fw * e.m(0.09) + X * sg * e.m(0.16) + up * e.m(0.12), (X, up, fw),
              (e.m(0.02), e.m(0.56), e.m(0.025)), col['metal'])                       # catapult rails
        m.box(back_c + fw * e.m(0.04) + X * sg * e.m(0.08) + up * e.m(0.05), (X, up, fw),
              (e.m(0.022), e.m(0.30), e.m(0.004)), col['dgrey'])                      # shoulder harness straps
    head = hip + up * e.m(0.82) - fw * e.m(0.09)
    m.box(head, (X, up, fw), (e.m(0.15), e.m(0.13), e.m(0.09)), col['headbox'])
    m.box(head + fw * e.m(0.095), (X, up, fw), (e.m(0.10), e.m(0.09), e.m(0.012)), col['cushion'])
    for sg in (1, -1):                                                           # canopy breakers on the headbox
        m.box(head + up * e.m(0.14) + X * sg * e.m(0.09), (X, up, fw), (e.m(0.01), e.m(0.02), e.m(0.01)), col['metal'])
    hz = hip + Z * e.m(0.43) + Y * e.m(0.03)
    for sg in (1, -1):
        m.box(hz + X * sg * e.m(0.05), (X, Y, Z), (e.m(0.008), e.m(0.03), e.m(0.008)), col['yellow'])
    m.box(hz + Y * e.m(0.03), (X, Y, Z), (e.m(0.058), e.m(0.008), e.m(0.008)), col['yellow'])


# ---------------------------------------------------------------- painted parts

ICP_W, ICP_H = 0.200, 0.110   # the integrated control panel's face, metres
# ICP layout (plate metres from its centre): the override row on top, the 3 x 4 keypad, RCL / ENTR on the right, the
# SYM and BRT wheels down the left edge, DRIFT C/O and WARN RESET on the right edge, the DCS rocker below the keypad
ICP_TOP = [('COM1', -0.060), ('COM2', -0.036), ('IFF', -0.012), ('LIST', 0.012), ('A-A', 0.036), ('A-G', 0.060)]
ICP_KEYS = [['1|T-ILS', '2|ALOW', '3'], ['4|STPT', '5|CRUS', '6|TIME'], ['7|MARK', '8|FIX', '9|A-CAL'],
            ['', '0|M-SEL', '']]
ICP_KX, ICP_KY = (-0.030, -0.004, 0.022), (0.022, 0.002, -0.018, -0.038)


def p_icp(d, im):
    S = im.width / ICP_W                             # SS pixels per metre
    def px(a, b):                                    # plate metres (centre origin) -> pixels
        return (im.width / 2 + a * S, im.height / 2 - b * S)
    noisy(im, (38, 40, 42), 3, seed=5)
    for lab, a in ICP_TOP:
        x, y = px(a, 0.046)
        d.rounded_rectangle([x - 0.0105 * S, y - 0.0075 * S, x + 0.0105 * S, y + 0.0075 * S], 0.002 * S,
                            fill=(66, 68, 70, 255))
        text(d, (x, y), lab, 0.0040 * S, fill=WHITE)
    for i, row in enumerate(ICP_KEYS):
        for j, k_ in enumerate(row):
            if not k_:
                continue
            x, y = px(ICP_KX[j], ICP_KY[i])
            d.rounded_rectangle([x - 0.0105 * S, y - 0.0085 * S, x + 0.0105 * S, y + 0.0085 * S], 0.002 * S,
                                fill=(70, 72, 74, 255))
            parts = k_.split('|')
            text(d, (x, y - (0.002 * S if len(parts) > 1 else 0)), parts[0], 0.0062 * S, fill=WHITE)
            if len(parts) > 1:
                text(d, (x, y + 0.0045 * S), parts[1], 0.0030 * S, fill=WHITE)
    for lab, b in (('RCL', 0.012), ('ENTR', -0.010)):
        x, y = px(0.052, b)
        d.rounded_rectangle([x - 0.0105 * S, y - 0.0085 * S, x + 0.0105 * S, y + 0.0085 * S], 0.002 * S,
                            fill=(70, 72, 74, 255))
        text(d, (x, y), lab, 0.0045 * S, fill=WHITE)
    for a, labs in ((-0.083, ('SYM', 'BRT')), (0.083, ('DRIFT', 'WARN'))):
        for k, (lab, b) in enumerate(zip(labs, (0.016, -0.022))):
            x, y = px(a, b)
            d.rectangle([x - 0.006 * S, y - 0.014 * S, x + 0.006 * S, y + 0.014 * S], fill=(24, 24, 25, 255),
                        outline=(120, 120, 120, 255), width=max(1, int(0.001 * S)))
            for t in range(5):
                d.line([x - 0.005 * S, y + (-0.011 + t * 0.0055) * S, x + 0.005 * S, y + (-0.011 + t * 0.0055) * S],
                       fill=(90, 90, 90, 255), width=max(1, int(0.0008 * S)))
            text(d, (x, y + 0.020 * S), lab, 0.0034 * S, fill=WHITE)
    x, y = px(0.0, -0.052)                           # the DCS (data control switch) rocker
    d.rectangle([x - 0.016 * S, y - 0.005 * S, x + 0.016 * S, y + 0.005 * S], fill=(24, 24, 25, 255))
    d.ellipse([x - 0.004 * S, y - 0.004 * S, x + 0.004 * S, y + 0.004 * S], fill=(110, 110, 110, 255))


def p_ded(w, h):
    """the data entry display on its own bezel: five lines of green text"""
    def fn(d, im):
        W, H = im.width, im.height
        d.rectangle([0, 0, W, H], fill=(36, 38, 40, 255))
        d.rectangle([W * .04, H * .10, W * .96, H * .90], fill=(6, 14, 8, 255), outline=(80, 80, 80, 255),
                    width=max(1, int(H * .02)))
        lines = ['UHF 242.00  STPT  4', '', 'VHF  1     08:12:31', '      M1 3  C  4', '12:00 MAN  T  75X']
        for i, ln in enumerate(lines):
            text(d, (W * .07, H * (.20 + i * .145)), ln, H * .13, fill=GREEN_DED, anchor='lm', mono=True)
        screws(d, W, H, H * .08, H * .03)
    return fn


def p_label_panel(title, labels, knobs=0, toggles=0, title_top=True):
    def make(w, h):
        def fn(d, im):
            noisy(im, PANEL, 3, seed=len(title))
            W, H = im.width, im.height
            screws(d, W, H, H * 0.08, H * 0.03)
            text(d, (W / 2, H * (0.12 if title_top else 0.9)), title, H * 0.13, fill=LETTER)
            n = max(1, len(labels))
            for j, lab in enumerate(labels):
                x = W * (0.5 + j) / n
                text(d, (x, H * 0.82), lab, H * 0.09, fill=LETTER)
                d.ellipse([x - H * 0.06, H * 0.48 - H * 0.06, x + H * 0.06, H * 0.48 + H * 0.06],
                          fill=(18, 18, 18, 255))
        return fn
    return make


PAINTERS = {
    'MISC': p_label_panel('MASTER ARM', ['ARM', 'LASER ARM']),
    'MISC2': p_label_panel('RF / ALT REL', ['NORM QUIET', 'ALT REL']),
    'DED': p_ded,
    'INSTR': p_label_panel('INSTR MODE', ['TCN', 'NAV', 'ILS']),
    'FUELSEL': p_label_panel('FUEL QTY SEL', ['NORM', 'RSVR', 'INT', 'EXT']),
    'TWA': p_label_panel('TWA', ['SEARCH', 'ACT/PWR', 'LOW', 'SYS']),
    'CMDS': p_label_panel('CMDS', ['RWR', 'JMR', 'MWS', 'O1', 'O2', 'CH', 'FL', 'PRGM', 'MODE']),
    'TEST': p_label_panel('TEST', ['FIRE/OHEAT', 'OXY QTY', 'MAL IND']),
}
# controls modelled on the painted panels: (s, t) in the panel (-0.5..0.5 across, -0.5..0.5 up), kind
SWITCHES = {
    'MISC': [(-0.25, -0.02, 'guard'), (0.25, -0.02, 'toggle')],
    'MISC2': [(-0.25, -0.02, 'toggle'), (0.25, -0.02, 'button')],
    'DED': [],
    'INSTR': [(0.0, -0.02, 'knob')],
    'FUELSEL': [(0.0, -0.02, 'knob')],
    'TWA': [(-0.375, -0.02, 'button'), (-0.125, -0.02, 'button'), (0.125, -0.02, 'button'), (0.375, -0.02, 'button')],
    'CMDS': [(-0.5 + (j + 0.5) / 9, -0.02, 'toggle' if j < 6 else 'knob') for j in range(9)],
    'TEST': [(-0.33, -0.02, 'button'), (0.0, -0.02, 'button'), (0.33, -0.02, 'button')],
}
CONSOLE_PANELS = {   # fore to aft along each side console: (title, controls labels, depth share)
    1: [('SNSR PWR', ['LEFT HDPT', 'RIGHT HDPT', 'FCR', 'RDR ALT'], 1.0),
        ('HUD', ['SCALES', 'FPM', 'DED DATA', 'DEPR RET'], 1.0),
        ('AVIONICS POWER', ['MMC', 'ST STA', 'MFD', 'UFC', 'EGI'], 1.2),
        ('LIGHTING', ['PRIMARY', 'FLOOD', 'INST PNL', 'CONSOLE'], 1.0),
        ('OXYGEN REGULATOR', ['SUPPLY', 'DILUTER', 'EMER'], 1.0),
        ('AIR COND', ['TEMP', 'AIR SOURCE'], 0.8),
        ('ANTI-ICE', ['ENGINE', 'UHF'], 0.6)],
    -1: [('TEST', ['FLCS PWR', 'PROBE HEAT', 'EPU/GEN'], 0.8),
         ('FLCS', ['DIGITAL BACKUP', 'ALT FLAPS', 'MANUAL TF FLYUP'], 1.0),
         ('FUEL', ['MASTER', 'TANK INERTING', 'ENG FEED', 'AIR REFUEL'], 1.1),
         ('AUDIO 1', ['COMM 1', 'COMM 2', 'MSL', 'THREAT'], 1.0),
         ('UHF', ['CHAN', 'FREQ', 'FUNCTION'], 1.2),
         ('EXT LIGHTING', ['ANTI-COLL', 'POSITION', 'FORM', 'MASTER'], 1.0),
         ('EPU', ['EPU', 'GEN'], 0.7)],
}
CONSOLE_CONTROLS = {}


def console_painter(sg, P):
    panels = CONSOLE_PANELS[sg]
    total = sum(p[2] for p in panels)
    span = P.b1 - P.b0
    controls = []
    f = P.b1 - 0.02
    layout = []
    for title, labels, share in panels:
        depth = (span - 0.03) * share / total
        layout.append((title, labels, f - depth, f))
        n = len(labels)
        for j in range(n):
            controls.append(((j + 0.5) / n, f - depth * 0.45))
        f -= depth
    CONSOLE_CONTROLS[sg] = controls

    def fn(d, im):
        noisy(im, GULL_D, 3, seed=40 + sg)
        W = im.width
        for title, labels, fa, fb in layout:
            x0, ya = 0.04 * W, (P.b1 - fb) * P.S + P.mm(3)
            x1, yb = 0.96 * W, (P.b1 - fa) * P.S - P.mm(3)
            d.rectangle([x0, ya, x1, yb], fill=PANEL + (255,))
            screws(d, 0, 0, 0, 0) if False else None
            for (x, y) in ((x0 + P.mm(4), ya + P.mm(4)), (x1 - P.mm(4), ya + P.mm(4)), (x0 + P.mm(4), yb - P.mm(4)),
                           (x1 - P.mm(4), yb - P.mm(4))):
                d.ellipse([x - P.mm(1.6), y - P.mm(1.6), x + P.mm(1.6), y + P.mm(1.6)], fill=(70, 72, 74, 255))
            # the console's tile runs aft (bottom) to forward (top); the panels read from the pilot's seat, so the
            # text is upright when seen from behind: top of the text toward the nose
            text(d, ((x0 + x1) / 2, ya + P.mm(7)), title, P.mm(6), fill=LETTER)
            n = len(labels)
            for j, lab in enumerate(labels):
                s_ = (j + 0.5) / n
                x = x0 + (x1 - x0) * (s_ if sg > 0 else s_)
                yy = (P.b1 - (fa + (fb - fa) * 0.55)) * P.S
                d.ellipse([x - P.mm(6), yy - P.mm(6), x + P.mm(6), yy + P.mm(6)], fill=(18, 18, 18, 255))
                text(d, (x, yy + P.mm(10)), lab, P.mm(3.6), fill=LETTER)
    return fn
