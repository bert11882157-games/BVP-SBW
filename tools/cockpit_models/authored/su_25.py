"""Su-25 front cockpit, authored from references (Wikimedia Commons):

  * "Приборная панель штурмовика Су-25" - the main instrument panel, face on: every instrument's place;
  * "CombatTask2017-03" - the ASP-17 sight head and its reflector over the panel, the warning-light column;
  * "Rostyslav Lazarenko" - the K-36L headbox, the armoured tub walls and the thick canopy frame;
  * "Будни авиагруппы ВКС РФ на аэродроме Хмеймим (5)" - the sight frame through the windscreen, the high sills.

The structure: a narrow armoured tub; one flat main panel leaning back 12 degrees with short wings turned toward
the pilot, its top cut down in the middle where the ASP-17 sight's control box stands on it; the sight head on top
with its reflector plate; a black coaming forward to the windscreen; side consoles with the twin throttles (RUD) on
the left; a centre stick with its large grey-blue grip; rudder pedals; and the K-36L seat.

Panel layout (left to right, from the photo): AoA/g and the warning-light buttons far left; the VD altimeter over
the KUS airspeed, the clock under them; the KPP attitude director in the centre with the PNP HSI under it; the
variometer and Mach meter right of it, the twin tachometer and the two EGT gauges under them; the SPO-15 panel top
right with the fuel quantity tape under it and the warning-light column on the right edge; the gear and flap
indicator bottom left. Positions are metres from the design eye (r right, u up, f forward), measured off the panel
photo with the ADI as the scale, and fitted to the model by the tub.
"""
import math
import numpy as np

from authored import lib as A
from authored.lib import L, unit, Plane, Painter, text, noisy, screws

# colours from the photos: blue-grey panels and tub, black instrument faces, a black coaming, green-grey K-36L
PANEL = (118, 142, 150)       # panel and console faces: Soviet cockpit blue-grey
PANEL_D = (92, 112, 120)
WALL = (108, 132, 138)        # tub walls
WALL_D = (84, 104, 110)
COAMING = (24, 25, 26)
FLOOR = (40, 44, 44)
LETTER = (238, 240, 236)      # panel lettering, white on blue-grey
BOX = (28, 29, 30)            # black sub-panels and control boxes
SEAT_FRAME = (86, 96, 86)     # K-36L structure
CUSHION = (70, 78, 62)
HEADBOX = (96, 106, 94)
GRIP_BLUE = (150, 170, 178)   # the stick grip's housing
YELLOW = (230, 190, 30)
RED = (196, 36, 30)
METAL = (130, 134, 136)

LEAN = 12.0
WING_TURN = 22.0
CEN_A = 0.21                  # centre board half width
WING_W = 0.10
U_TOP, U_BOT = -0.27, -0.62
F_PANEL = 0.78                # main panel reference distance ahead of the eye (at u = -0.45)
CUT_A, CUT_U = 0.13, -0.33    # the cut-out in the panel top for the sight's control box
CONSOLE_U = -0.66
CONSOLE_R0, CONSOLE_R1 = 0.20, 0.34
FLOOR_U = -1.10
BACK_F = -0.42
FRONT_F = 1.10

# the old panel, its shelf and posts in front of it: the model's glass leaves the windscreen open there, so the
# outside test counts them as outside; they go (only what the cockpit tool found as old interior; rebuild.py)
CUT_BOXES = [(-0.45, 0.45, -1.10, -0.12, 0.35, 1.30)]

FIRE = [('ПОЖАР', 'FIRE', 'red'), ('ОСТАТОК|ТОПЛ', 'LOW_FUEL', 'amber'), ('ОТКАЗ|ГИДРО', 'DAMAGE', 'amber'),
        ('ОТКАЗ|ГЕНЕР', 'NONE', 'amber'), ('ОПАСН|ВЫСОТА', 'LOW_ALT', 'red'), ('ОТКАЗ|ДВИГ', 'ENGINE_OUT', 'red'),
        ('ТОРМОЗ|ЩИТКИ', 'AIRBRAKE', 'green'), ('ВНИМАНИЕ', 'MASTER_CAUTION', 'amber')]
WARN_COL = ('WARN', 1, FIRE)
GEAR = ('GEAR', 3, [('ПЕР', 'GEAR_DOWN', 'green'), ('ЛЕВ', 'GEAR_DOWN', 'green'), ('ПРАВ', 'GEAR_DOWN', 'green')])
SPO15 = ('SPO', 4, [('ПЕР', 'RWR_LOCK', 'red'), ('ЗАД', 'RWR_LOCK', 'red'), ('ПУСК', 'MISSILE_LAUNCH', 'red'),
                    ('МОЩН', 'NONE', 'amber')])

# (plane, a, u, kind, size, label); a: metres right on the plane (centre board from the centre line; wings from the
# hinge outward, negative on the left); u: eye-relative height of the centre
MAIN = [
    ('left', -0.050, -0.330, 'G_METER', 0.058, 'ПЕРЕГРУЗКА'),
    ('left', -0.050, -0.420, 'AOA', 0.056, 'УГОЛ АТАКИ'),
    ('left', -0.050, -0.500, 'paint:BTN', (0.070, 0.050), None),
    ('left', -0.050, -0.575, 'A:GEAR', (0.072, 0.024), None),
    ('centre', -0.150, -0.360, 'ALTIMETER', 0.066, 'ВЫСОТА'),
    ('centre', -0.150, -0.455, 'AIRSPEED', 0.066, 'СКОРОСТЬ'),
    ('centre', -0.150, -0.548, 'CLOCK', 0.052, 'ЧАСЫ'),
    ('centre', -0.050, -0.420, 'ATTITUDE', 0.090, 'КПП'),
    ('centre', -0.050, -0.540, 'HEADING', 0.080, 'ПНП'),
    ('centre', 0.060, -0.432, 'VVI', 0.068, 'ВАР'),
    ('centre', 0.152, -0.420, 'MACH', 0.068, 'М'),
    ('centre', 0.135, -0.510, 'ENGINE_RPM', 0.070, 'ОБОРОТЫ'),
    ('centre', 0.110, -0.588, 'EGT', 0.044, 'Т ГАЗОВ Л'),
    ('centre', 0.170, -0.588, 'FILLER_TEMP', 0.044, 'Т ГАЗОВ П'),
    ('centre', 0.045, -0.598, 'paint:PK', (0.060, 0.030), None),
    ('right', 0.045, -0.330, 'RWR', 0.060, 'СПО-15'),
    ('right', 0.045, -0.395, 'A:SPO', (0.070, 0.018), None),
    ('right', 0.040, -0.480, 'FUEL_QTY', 0.056, 'ТОПЛИВО'),
    ('right', 0.090, -0.440, 'A:WARN', (0.024, 0.150), None),
    ('right', 0.040, -0.565, 'RADAR_ALT', 0.050, 'РВ'),
]


def fit_scale(ac, tub):
    """the largest scale (tub.s down to 0.65 tub.s) at which the cockpit's outer corners sit inside the canopy glass
    and the fuselage skin"""
    pts = []
    for sg in (1, -1):
        pts += [(sg * 0.30, U_TOP + 0.03, 0.74, 'glass'), (sg * 0.10, -0.12, 0.74, 'glass'),
                (sg * CONSOLE_R1, CONSOLE_U + 0.02, 0.0, 'skin'), (sg * CONSOLE_R1, CONSOLE_U + 0.02, 0.40, 'skin'),
                (sg * 0.30, -0.60, 0.72, 'skin'), (sg * 0.30, -0.36, 0.74, 'skin')]
    for k in np.linspace(tub.s, 0.65 * tub.s, 15):
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
            return k
    return 0.65 * tub.s


def build(ac, tub, sw, atlas):
    k = fit_scale(ac, tub)
    e = A.Eye(tub, k)
    E = e.E
    m, stick = L.Mesh(), L.Mesh()
    gauges, displays, glass = [], [], []
    sid = tub.seat
    col = {n: atlas.flat(atlas.swatch(rgb)) for n, rgb in dict(
        panel=PANEL, panel_d=PANEL_D, wall=WALL, wall_d=WALL_D, coaming=COAMING, floor=FLOOR, box=BOX,
        frame=SEAT_FRAME, cushion=CUSHION, headbox=HEADBOX, grip=GRIP_BLUE, yellow=YELLOW, red=RED, metal=METAL,
        black=(20, 20, 21), mgrey=(88, 92, 94), lgrey=(170, 174, 176), dgrey=(44, 46, 48)).items()}
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    lean = math.radians(LEAN)
    up = e.d(0, math.cos(lean), math.sin(lean))
    # the tall console tiles go into the texture first, while its free areas are whole
    console_tiles = {}
    for sg in (1, -1):
        P = Painter(0, CONSOLE_R1 - CONSOLE_R0, BACK_F + 0.05, 0.45, 500)
        console_tiles[sg] = atlas.paint(P.W, P.H, console_painter(sg, P))

    def cb(u):
        return (u + 0.45) / math.cos(lean)
    cen = Plane(e, e.p(0, -0.45, F_PANEL), e.d(1, 0, 0), up)
    t = math.radians(WING_TURN)
    right = Plane(e, cen.at(CEN_A, 0), e.d(math.cos(t), 0, -math.sin(t)), up)
    left = Plane(e, cen.at(-CEN_A, 0), e.d(math.cos(t), 0, math.sin(t)), up)
    planes = dict(centre=cen, right=right, left=left)
    b_top, b_bot, b_cut = cb(U_TOP), cb(U_BOT), cb(CUT_U)
    extent = dict(centre=(-CEN_A, CEN_A, b_bot, b_top), right=(0.0, WING_W, b_bot, b_top),
                  left=(-WING_W, 0.0, b_bot, b_top))
    # the centre board's outline: the top cut down between +-CUT_A for the sight's control box
    cen_outline = [(-CEN_A, b_bot), (CEN_A, b_bot), (CEN_A, b_top), (CUT_A, b_top), (CUT_A, b_cut), (-CUT_A, b_cut),
                   (-CUT_A, b_top), (-CEN_A, b_top)]

    def annunciator(c, plane, w, h, spec, depth=0.02):
        _, cols, lamps = spec
        gauges.append({'Seat': sid, 'Kind': 'ANNUNCIATOR', 'Center': L.r5(c), 'Normal': L.r5(plane.N),
                       'Up': L.r5(plane.U), 'Diameter': round(e.m(w), 4), 'Width': round(e.m(w), 4),
                       'Height': round(e.m(h), 4), 'Depth': depth, 'Columns': cols,
                       'Lamps': [{'Text': tx, 'Condition': cd, 'Colour': cl} for tx, cd, cl in lamps]})

    def switch(plane, a, b, kind):
        R, U, N = plane.axes()
        c = plane.at(a, b)
        if kind in ('toggle', 'guard'):
            m.box(c + N * e.m(0.003), (R, U, N), (e.m(0.005), e.m(0.005), e.m(0.003)), col['mgrey'])
            lever = unit(U * 0.5 + N)
            m.box(c + N * e.m(0.006) + lever * e.m(0.009), (R, lever, unit(np.cross(R, lever))),
                  (e.m(0.0022), e.m(0.009), e.m(0.0022)), col['lgrey'])
            if kind == 'guard':
                m.box(c + N * e.m(0.008) + U * e.m(0.002), (R, U, N), (e.m(0.007), e.m(0.009), e.m(0.0015)),
                      col['red'])
        elif kind == 'knob':
            m.box(c + N * e.m(0.006), (R, U, N), (e.m(0.008), e.m(0.008), e.m(0.006)), col['black'])
            m.box(c + N * e.m(0.0125) + U * e.m(0.003), (R, U, N), (e.m(0.0012), e.m(0.004), e.m(0.0006)),
                  col['lgrey'])
        elif kind == 'button':
            m.box(c + N * e.m(0.003), (R, U, N), (e.m(0.005), e.m(0.005), e.m(0.003)), col['red'])

    # -- the main panel: painted faces (recesses behind instruments, Russian placards) and slabs
    def face_fn(name, items, P):
        def fn(d, im):
            noisy(im, PANEL, 3, seed=7 + len(name))
            screws(d, P.W * A.Atlas.SS, P.H * A.Atlas.SS, P.mm(8), P.mm(2.6), colour=(150, 160, 164))
            for _, a, u, kind, size, label in items:
                b = cb(u)
                x, y = P.to(a, b)
                if kind.startswith('A:') or kind.startswith('paint:'):
                    w, h = size
                    d.rectangle(P.box(a, b, w + 0.008, h + 0.008), fill=BOX + (255,))
                else:
                    r = (size / 2 * 1.18 + 0.004) * P.S
                    d.ellipse([x - r, y - r, x + r, y + r], fill=(20, 22, 22, 255))
                if label:
                    off = (size / 2 * 1.18 + 0.010) if not isinstance(size, tuple) else size[1] / 2 + 0.010
                    text(d, (x, y + off * P.S), label, P.mm(6), fill=LETTER)
            if name == 'centre':
                # the cut-out's edges and the sight's mounting plate below the control box
                x0, y0 = P.to(-CUT_A, b_cut); x1, _ = P.to(CUT_A, b_cut)
                d.line([x0, y0, x1, y0], fill=PANEL_D + (255,), width=int(P.mm(3)))
        return fn

    for name, plane in planes.items():
        items = [it for it in MAIN if it[0] == name]
        a0, a1, b0, b1 = extent[name]
        P = Painter(a0, a1, b0, b1, 800)
        rect = atlas.paint(P.W, P.H, face_fn(name, items, P))
        pieces = [None] if name != 'centre' else [
            [(-CEN_A, b_bot), (CEN_A, b_bot), (CEN_A, b_cut), (-CEN_A, b_cut)],
            [(-CEN_A, b_cut), (-CUT_A, b_cut), (-CUT_A, b_top), (-CEN_A, b_top)],
            [(CUT_A, b_cut), (CEN_A, b_cut), (CEN_A, b_top), (CUT_A, b_top)]]   # convex pieces round the cut-out
        for outline in pieces:
            A.slab(m, plane, a0, a1, b0, b1, None, col['panel_d'], depth=e.m(0.025), face_rect=rect, atlas=atlas,
                   outline=outline)

    def put(plane, a, b, kind, size):
        c = plane.at(a, b)
        if kind.startswith('A:'):
            w, h = size
            annunciator(c + plane.N * 0.002, plane, w, h, {'GEAR': GEAR, 'SPO': SPO15, 'WARN': WARN_COL}[kind[2:]])
        elif kind.startswith('paint:'):
            w, h = size
            rect = atlas.paint(int(w * 1200), int(h * 1200), PAINTERS[kind[6:]])
            A.plate(m, atlas, plane, a, b, w, h, rect, proud=0.004, edge=col['box'])
            for sa, sb, kind2 in SWITCHES.get(kind[6:], []):
                switch(plane, a + sa * w, b + sb * h, kind2)
        else:
            gauges.append({'Seat': sid, 'Kind': kind, 'Center': L.r5(c + plane.N * L.GAUGE_LIFT),
                           'Normal': L.r5(plane.N), 'Up': L.r5(plane.U), 'Diameter': round(e.m(size), 4),
                           'Depth': 0.03})
            # the black bezel ring the photo shows round every dial
            m.ring(c, plane.N, plane.U, e.m(size / 2 * 1.16), e.m(size / 2 * 1.26), e.m(0.006), col['black'])

    for name, a, u, kind, size, label in MAIN:
        put(planes[name], a, cb(u), kind, size)

    # -- the ASP-17 sight: its control box standing in the panel's cut-out (knobs and placards on its face), the
    # sight head on top and the reflector plate leaning back over it
    box_f0 = F_PANEL - 0.02
    cbx = Plane(e, e.p(0, (U_TOP + CUT_U) / 2 + 0.01, box_f0 - 0.05), e.d(1, 0, 0), e.d(0, 1, 0.1))
    R, U, N = cbx.axes()
    m.box(cbx.at(0, 0) - N * e.m(0.07), (R, U, N), (e.m(CUT_A - 0.004), e.m(0.055), e.m(0.07)), col['box'])
    face = atlas.paint(int(2 * CUT_A * 1200), int(0.11 * 1200), p_asp_box)
    A.plate(m, atlas, cbx, 0, 0, 2 * CUT_A - 0.01, 0.105, face, proud=0.003, edge=col['box'])
    for a in (-0.085, -0.035, 0.050, 0.095):
        switch(cbx, a, 0.012, 'knob')
    head_c = e.p(0, U_TOP + 0.04, box_f0 - 0.02)
    m.box(head_c, (X, Y, Z), (e.m(0.055), e.m(0.035), e.m(0.07)), col['box'])
    m.box(head_c + Y * e.m(0.036) + Z * e.m(0.02), (X, Y, Z), (e.m(0.035), e.m(0.004), e.m(0.035)), col['dgrey'])
    # reflector: a frame leaning back 35 degrees from the head's top, up to the eye's height
    g_lean = math.radians(35)
    g_up = np.array([0, math.cos(g_lean), -math.sin(g_lean)])
    g_n = unit(np.cross(X, g_up))
    g_bot = head_c + Y * e.m(0.04)
    g_h = max(e.m(0.04), min(e.m(0.12), (E[1] + e.m(0.02) - g_bot[1]) / g_up[1]))
    top = ac.glass_above(g_bot[0], g_bot[2] + g_up[2] * g_h, g_bot[1])
    if top is not None:
        g_h = min(g_h, (top - 0.03 - g_bot[1]) / g_up[1])
    g_hw = e.m(0.045)
    if g_h > e.m(0.03):
        g_top = g_bot + g_up * g_h
        for s_ in (-1, 1):
            m.box((g_bot + g_top) / 2 + X * s_ * g_hw, (X, g_up, g_n), (e.m(0.004), g_h / 2, e.m(0.005)), col['box'])
        m.box(g_top + g_up * e.m(0.003), (X, g_up, g_n), (g_hw + e.m(0.004), e.m(0.003), e.m(0.005)), col['box'])

    # -- coaming: black, from the panel top forward to the windscreen base
    lip = []
    for name, aa in (('left', np.linspace(-WING_W, 0, 3)), ('centre', np.linspace(-CEN_A, CEN_A, 7)),
                     ('right', np.linspace(0, WING_W, 3))):
        for a in aa:
            q = planes[name].at(a, b_top)
            if lip and np.linalg.norm(lip[-1] - q) < 1e-6:
                continue
            lip.append(q)
    lip_edge = [q + e.F * e.m(0.04) + e.U * e.m(0.015) for q in lip]
    ws_z = max(tub.ws_z, lip_edge[0][2] + 0.1)
    rows = []
    for kk, tt in enumerate((0.0, 0.4, 0.8, 1.0)):
        row = []
        for le in lip_edge:
            sx = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            z = le[2] + (ws_z - le[2]) * tt
            x = le[0] + (E[0] + sx * tub.W * 0.8 - le[0]) * tt
            y = le[1] + e.m(0.02) * math.sin(math.pi * tt) * (1 - sx * sx)
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
        da, db = a_ - e.U * e.m(0.012), b_ - e.U * e.m(0.012)
        m.flat([a_, b_, db, da], col['coaming'], toward=E)
        m.flat([da, db, lip[j + 1], lip[j]], col['coaming'], normal=[0, -1, 0])

    # -- tub: armoured walls, side consoles, floor, bulkheads
    sill_u = (tub.sill - E[1]) / k
    armour_tile = atlas.paint(300, 120, p_armour)       # one tile for both walls (the texture is 1024 square)
    for sg in (1, -1):
        x_in, x_out = sg * CONSOLE_R0, sg * CONSOLE_R1
        f0, f1 = BACK_F + 0.05, 0.45
        top = [e.p(x_in, CONSOLE_U, f0), e.p(x_out, CONSOLE_U, f0), e.p(x_out, CONSOLE_U, f1), e.p(x_in, CONSOLE_U, f1)]
        rect = console_tiles[sg]
        m.face(top, [atlas.uv(rect, 0, 0), atlas.uv(rect, 1, 0), atlas.uv(rect, 1, 1), atlas.uv(rect, 0, 1)],
               normal=[0, 1, 0])
        for k_, (fa, fb) in enumerate(CONSOLE_CONTROLS[sg]):
            pl = Plane(e, e.p(sg * (CONSOLE_R0 + fa * (CONSOLE_R1 - CONSOLE_R0)), CONSOLE_U, fb), e.d(sg, 0, 0),
                       e.d(0, 0, 1))
            switch(pl, 0, 0, 'knob' if k_ % 3 else 'toggle')
            if k_ % 4 != 3:
                switch(pl, 0.0, -0.016, 'toggle')
        m.flat([e.p(x_in, FLOOR_U, f0), e.p(x_in, FLOOR_U, f1), e.p(x_in, CONSOLE_U, f1), e.p(x_in, CONSOLE_U, f0)],
               col['wall_d'], normal=e.d(-sg, 0, 0))
        m.flat([e.p(x_in, FLOOR_U, f1), e.p(x_out, FLOOR_U, f1), e.p(x_out, CONSOLE_U, f1), e.p(x_in, CONSOLE_U, f1)],
               col['wall_d'], normal=e.d(0, 0, -1))
        # the armoured wall from the floor to the sill, all the way to the footwell bulkhead
        wall = armour_tile
        q = [e.p(x_out, CONSOLE_U, BACK_F), e.p(x_out, CONSOLE_U, FRONT_F), e.p(x_out, sill_u, FRONT_F),
             e.p(x_out, sill_u, BACK_F)]
        st = [(0, 0), (1, 0), (1, 1), (0, 1)] if sg < 0 else [(1, 0), (0, 0), (0, 1), (1, 1)]
        m.face(q, [atlas.uv(wall, s, t_) for s, t_ in st], normal=e.d(-sg, 0, 0))
        m.flat([e.p(x_out, FLOOR_U, BACK_F), e.p(x_out, FLOOR_U, FRONT_F), e.p(x_out, CONSOLE_U, FRONT_F),
                e.p(x_out, CONSOLE_U, BACK_F)], col['wall_d'], normal=e.d(-sg, 0, 0))
        m.box(e.p(sg * (CONSOLE_R1 + 0.03), sill_u + 0.01, (BACK_F + 0.70) / 2), (X, Y, Z),
              (e.m(0.05), e.m(0.012), e.m((0.70 - BACK_F) / 2)), col['wall'])
        m.flat([e.p(sg * 0.20, FLOOR_U, f1), e.p(sg * 0.20, FLOOR_U, FRONT_F), e.p(sg * 0.20, U_BOT, FRONT_F),
                e.p(sg * 0.20, U_BOT, f1)], col['wall_d'], normal=e.d(-sg, 0, 0))
    m.flat([e.p(-CONSOLE_R1, FLOOR_U, BACK_F), e.p(CONSOLE_R1, FLOOR_U, BACK_F), e.p(CONSOLE_R1, FLOOR_U, FRONT_F),
            e.p(-CONSOLE_R1, FLOOR_U, FRONT_F)], col['floor'], normal=[0, 1, 0])
    m.flat([e.p(-CONSOLE_R1, FLOOR_U, FRONT_F), e.p(CONSOLE_R1, FLOOR_U, FRONT_F), e.p(CONSOLE_R1, -0.30, FRONT_F),
            e.p(-CONSOLE_R1, -0.30, FRONT_F)], col['wall_d'], normal=e.d(0, 0, -1))
    m.flat([e.p(-CEN_A, U_BOT, 0.70), e.p(CEN_A, U_BOT, 0.70), e.p(CEN_A, U_BOT, FRONT_F), e.p(-CEN_A, U_BOT, FRONT_F)],
           col['panel_d'], normal=[0, -1, 0])
    m.flat([e.p(-CONSOLE_R1, FLOOR_U, BACK_F), e.p(CONSOLE_R1, FLOOR_U, BACK_F), e.p(CONSOLE_R1, sill_u, BACK_F),
            e.p(-CONSOLE_R1, sill_u, BACK_F)], col['wall'], normal=e.d(0, 0, 1))
    for sg in (1, -1):                      # rudder pedals
        m.box(e.p(sg * 0.12, -0.96, 0.98), (X, unit([0, 1, -0.45]), unit([0, 0.45, 1])),
              (e.m(0.05), e.m(0.08), e.m(0.012)), col['mgrey'])
        m.box(e.p(sg * 0.12, -1.03, 1.03), (X, Y, Z), (e.m(0.012), e.m(0.03), e.m(0.06)), col['dgrey'])

    # -- twin throttles (RUD) on the left console: a quadrant box with two levers and their grips
    tq = e.p(-0.27, CONSOLE_U, 0.18)
    m.box(tq + Y * e.m(0.02), (X, Y, Z), (e.m(0.045), e.m(0.02), e.m(0.13)), col['box'])
    for j, dx in enumerate((-0.017, 0.017)):
        lev = tq + X * -e.m(dx) + Y * e.m(0.06) + Z * e.m(0.02)
        ax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
        m.box(lev, ax, (e.m(0.006), e.m(0.045), e.m(0.008)), col['metal'])
        m.box(lev + ax[1] * e.m(0.05), ax, (e.m(0.015), e.m(0.022), e.m(0.03)), col['black'])
    m.box(tq + Y * e.m(0.13) + Z * e.m(0.04), (X, Y, Z), (e.m(0.004), e.m(0.004), e.m(0.004)), col['red'])

    # -- centre stick: a rubber boot, the column, and the big grey-blue grip with its trigger, buttons and the
    # wheel-brake lever (the grip in the panel photo)
    foot = e.p(0, -0.98, 0.42)
    m.box(foot + Y * e.m(0.03), (X, Y, Z), (e.m(0.05), e.m(0.04), e.m(0.05)), col['dgrey'])
    top_u = -0.50
    Lc = (top_u - (-0.98)) * k - e.m(0.06)
    col_ax = (X, unit([0, 1, -0.08]), unit([0, 0.08, 1]))
    stick.box(foot + col_ax[1] * Lc / 2, col_ax, (e.m(0.012), Lc / 2, e.m(0.012)), col['metal'])
    g_ax = (X, unit([0, 1, 0.2]), unit([0, -0.2, 1]))
    gc = foot + col_ax[1] * Lc + g_ax[1] * e.m(0.06)
    stick.box(gc, g_ax, (e.m(0.028), e.m(0.065), e.m(0.034)), col['grip'])
    stick.box(gc + g_ax[1] * e.m(0.07), g_ax, (e.m(0.03), e.m(0.012), e.m(0.036)), col['grip'])
    stick.box(gc + g_ax[1] * e.m(0.08) + g_ax[2] * e.m(0.012), g_ax, (e.m(0.008), e.m(0.004), e.m(0.008)), col['black'])
    stick.box(gc + g_ax[1] * e.m(0.08) - X * e.m(0.018), g_ax, (e.m(0.007), e.m(0.004), e.m(0.007)), col['black'])
    stick.box(gc + g_ax[2] * e.m(0.036) + g_ax[1] * e.m(0.02), g_ax, (e.m(0.01), e.m(0.014), e.m(0.005)), col['metal'])
    stick.box(gc - g_ax[2] * e.m(0.04) + g_ax[1] * e.m(0.01), g_ax, (e.m(0.014), e.m(0.04), e.m(0.004)), col['metal'])

    k36(m, e, col)
    return m, stick, gauges, displays, glass, None, foot


def k36(m, e, col):
    """K-36L: an upright seat (about 18 degrees back), green-grey cushions on a darker frame, the tall headbox with
    its padded front, guide beams behind, the yellow and black ejection handles between the knees"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    rec = math.radians(18)
    up = unit(e.U * math.cos(rec) - e.F * math.sin(rec))
    fw = unit(np.cross(X, up)) if np.cross(X, up) @ e.F > 0 else -unit(np.cross(X, up))
    hip = e.p(0, -0.80, 0.02)
    m.box(hip + Z * e.m(0.18) - Y * e.m(0.11), (X, Y, Z), (e.m(0.21), e.m(0.09), e.m(0.22)), col['frame'])
    m.box(hip + Z * e.m(0.18) + Y * e.m(0.0), (X, Y, Z), (e.m(0.19), e.m(0.035), e.m(0.21)), col['cushion'])
    for sg in (1, -1):
        m.box(hip + X * sg * e.m(0.215) + Z * e.m(0.15) + Y * e.m(0.04), (X, Y, Z), (e.m(0.012), e.m(0.14), e.m(0.22)),
              col['frame'])
    back_c = hip + up * e.m(0.36) - fw * e.m(0.07)
    m.box(back_c, (X, up, fw), (e.m(0.19), e.m(0.33), e.m(0.03)), col['cushion'])
    m.box(back_c - fw * e.m(0.06), (X, up, fw), (e.m(0.21), e.m(0.37), e.m(0.03)), col['frame'])
    for sg in (1, -1):
        m.box(back_c - fw * e.m(0.10) + X * sg * e.m(0.17) + up * e.m(0.14), (X, up, fw),
              (e.m(0.02), e.m(0.6), e.m(0.025)), col['frame'])
        m.box(back_c + fw * e.m(0.035) + X * sg * e.m(0.08) + up * e.m(0.05), (X, up, fw),
              (e.m(0.022), e.m(0.3), e.m(0.004)), col['dgrey'])
    head = hip + up * e.m(0.86) - fw * e.m(0.10)
    m.box(head, (X, up, fw), (e.m(0.16), e.m(0.19), e.m(0.10)), col['headbox'])
    m.box(head + fw * e.m(0.105), (X, up, fw), (e.m(0.11), e.m(0.11), e.m(0.012)), col['cushion'])
    hz = hip + Z * e.m(0.42) + Y * e.m(0.05)
    for sg in (1, -1):
        m.box(hz + X * sg * e.m(0.05), (X, Y, Z), (e.m(0.008), e.m(0.035), e.m(0.008)), col['yellow'])
    m.box(hz + Y * e.m(0.035), (X, Y, Z), (e.m(0.058), e.m(0.008), e.m(0.008)), col['yellow'])


# ---------------------------------------------------------------- painted parts

def p_asp_box(d, im):
    """the ASP-17 sight's control box face, after the photo: knobs with НИЗ / ВЕРХ / ГОДЕН / КОНТРОЛЬ on the left,
    the warning plate ПОД НАПРЯЖЕНИЕМ НЕ ОТКРЫВАТЬ over the grille in the middle, ЛАМПА / РЕЗЕРВ and ЛЕВО / ПРАВО on
    the right"""
    W, H = im.width, im.height
    noisy(im, BOX, 3, seed=3)
    S = W / (2 * CUT_A)
    def px(a, b):
        return (W / 2 + a * S, H / 2 - b * S)
    for a, lab in ((-0.085, 'НИЗ'), (-0.035, 'ГОДЕН'), (0.050, 'ЛАМПА'), (0.095, 'ЛЕВО  ПРАВО')):
        x, y = px(a, 0.040)
        text(d, (x, y), lab, 0.008 * S, fill=LETTER)
    for a, lab in ((-0.085, 'ВЕРХ'), (-0.035, 'КОНТРОЛЬ'), (0.050, 'РЕЗЕРВ')):
        x, y = px(a, -0.030)
        text(d, (x, y), lab, 0.007 * S, fill=LETTER)
    x0, y0 = px(-0.012, 0.045); x1, y1 = px(0.022, -0.045)
    d.rectangle([x0, y0, x1, y1], fill=(14, 14, 15, 255))
    for i in range(9):
        yy = y0 + (y1 - y0) * (0.25 + i * 0.08)
        d.line([x0 + 0.003 * S, yy, x1 - 0.003 * S, yy], fill=(70, 70, 70, 255), width=max(1, int(0.002 * S)))
    d.rectangle([x0, y0, x1, y0 + 0.018 * S], fill=(40, 40, 40, 255))
    text(d, ((x0 + x1) / 2, y0 + 0.006 * S), 'ПОД НАПРЯЖЕНИЕМ', 0.0042 * S, fill=LETTER)
    text(d, ((x0 + x1) / 2, y0 + 0.013 * S), 'НЕ ОТКРЫВАТЬ', 0.0042 * S, fill=LETTER)
    screws(d, W, H, H * 0.07, H * 0.025)


def p_label_panel(title, labels, bg=BOX):
    def fn(d, im):
        noisy(im, bg, 3, seed=len(title))
        W, H = im.width, im.height
        screws(d, W, H, H * 0.08, H * 0.03)
        text(d, (W / 2, H * 0.14), title, H * 0.15, fill=LETTER)
        n = max(1, len(labels))
        for j, lab in enumerate(labels):
            x = W * (0.5 + j) / n
            text(d, (x, H * 0.84), lab, H * 0.11, fill=LETTER)
            d.ellipse([x - H * 0.07, H * 0.5 - H * 0.07, x + H * 0.07, H * 0.5 + H * 0.07], fill=(12, 12, 12, 255))
    return fn


def p_armour(d, im):
    """the armoured tub wall: blue-grey plates with rivet rows and a few equipment boxes"""
    W, H = im.width, im.height
    noisy(im, WALL, 4, seed=21)
    for x in np.linspace(0, W, 6)[1:-1]:
        d.line([x, 0, x, H], fill=WALL_D + (255,), width=6)
        for y in np.linspace(H * 0.06, H * 0.94, 9):
            d.ellipse([x - 7 - 4, y - 4, x - 7 + 4, y + 4], fill=(150, 168, 174, 255))
    for k_, (x0, w) in enumerate(((0.12, 0.14), (0.45, 0.10), (0.70, 0.18))):
        d.rectangle([W * x0, H * 0.25, W * (x0 + w), H * 0.70], fill=BOX + (255,))
        text(d, (W * (x0 + w / 2), H * 0.78), ['РАДИО', 'КИСЛОРОД', 'РСБН'][k_], H * 0.07, fill=LETTER)


PAINTERS = {
    'BTN': p_label_panel('АВТ', ['ДАЛЬН', 'БЛИЖН', 'ПОВТОР']),
    'PK': p_label_panel('', ['ПК', 'ПК'], bg=(40, 30, 30)),
}
SWITCHES = {
    'BTN': [(-0.33, -0.02, 'button'), (0.0, -0.02, 'button'), (0.33, -0.02, 'button')],
    'PK': [(-0.25, -0.02, 'guard'), (0.25, -0.02, 'guard')],
}
CONSOLE_PANELS = {   # fore to aft along each side console: (title, controls labels, depth share)
    1: [('РАДИО', ['КАНАЛ', 'ГРОМК', 'ПОДАВ'], 1.2), ('НАВИГАЦИЯ', ['РСБН', 'АРК', 'КУРС'], 1.0),
        ('ЭЛЕКТРО', ['АККУМ', 'ГЕНЕР Л', 'ГЕНЕР П'], 1.0), ('КОНДИЦ', ['ТЕМП', 'ПОДАЧА'], 0.8),
        ('СВЕТ', ['КАБИНА', 'ПРИБОРЫ', 'АНО'], 0.8)],
    -1: [('ЗАПУСК', ['ЛЕВ', 'ПРАВ', 'СТОП'], 1.0), ('ТОПЛИВО', ['НАСОС', 'ПЕРЕКАЧ', 'ПТБ'], 1.0),
         ('ВООРУЖЕНИЕ', ['ГЛАВН', 'БОМБЫ', 'НАР', 'ПУШКА'], 1.3), ('АВАР', ['СБРОС', 'ФОНАРЬ'], 0.8)],
}
CONSOLE_CONTROLS = {}


def console_painter(sg, P):
    panels = CONSOLE_PANELS[sg]
    total = sum(p[2] for p in panels)
    span = P.b1 - P.b0
    controls, layout = [], []
    f = P.b1 - 0.02
    for title, labels, share in panels:
        depth = (span - 0.03) * share / total
        layout.append((title, labels, f - depth, f))
        for j in range(len(labels)):
            controls.append(((j + 0.5) / len(labels), f - depth * 0.45))
        f -= depth
    CONSOLE_CONTROLS[sg] = controls

    def fn(d, im):
        noisy(im, PANEL_D, 3, seed=40 + sg)
        W = im.width
        for title, labels, fa, fb in layout:
            x0, ya = 0.04 * W, (P.b1 - fb) * P.S + P.mm(3)
            x1, yb = 0.96 * W, (P.b1 - fa) * P.S - P.mm(3)
            d.rectangle([x0, ya, x1, yb], fill=PANEL + (255,))
            for (x, y) in ((x0 + P.mm(4), ya + P.mm(4)), (x1 - P.mm(4), ya + P.mm(4)), (x0 + P.mm(4), yb - P.mm(4)),
                           (x1 - P.mm(4), yb - P.mm(4))):
                d.ellipse([x - P.mm(1.6), y - P.mm(1.6), x + P.mm(1.6), y + P.mm(1.6)], fill=(150, 160, 164, 255))
            text(d, ((x0 + x1) / 2, ya + P.mm(7)), title, P.mm(6), fill=LETTER)
            n = len(labels)
            for j, lab in enumerate(labels):
                x = x0 + (x1 - x0) * (j + 0.5) / n
                yy = (P.b1 - (fa + (fb - fa) * 0.55)) * P.S
                d.ellipse([x - P.mm(6), yy - P.mm(6), x + P.mm(6), yy + P.mm(6)], fill=(18, 18, 18, 255))
                text(d, (x, yy + P.mm(10)), lab, P.mm(4), fill=LETTER)
    return fn
