#!/usr/bin/env python3
"""Tu-95MS flight deck, built by hand for this model (as tu_95ms.py): the commander left and the co-pilot right at one
wide panel under the windscreen, the navigator's, flight engineer's, radio operator's and weapons operator's seats
behind them.

Measured off the untouched model with sections.py (metres from each pilot's eye at x +-0.751, 5.104 up, 19.516 aft;
1 block = 1 m on this model; the co-pilot's eye sat 0.10 m higher and 0.10 m further back than the commander's and is
set to his):
  * the flight deck's walls 0.68 m outboard of each pilot's eye up to y -0.48 where the glazing begins; the old panel
    0.50 m ahead, its top at y -0.25 under the glareshield, the floor at y -1.24.
Built (the Tu-95MS's flight deck as photographed and described):
  * each pilot's section: the attitude director over the course indicator, airspeed, the radio altimeter and the
    vario beside them, the altimeter, accelerometer and the flap indicator; the gear and weapons (tail turret) panels
    on the outer wing; the four engines' tachometers and gas temperatures, fuel and the warning lights on the inner
    wing; the co-pilot's section mirrored; one coaming, a centre console (the throttles) between them;
  * outer consoles, side walls, seats, rudder pedals and both control columns (the commander's into the stick bone,
    the co-pilot's fixed); the navigator's, flight engineer's, radio operator's and weapons operator's seats.
Instruments the game draws (CockpitGauges) are live, each to its own seat; the warning lights are ANNUNCIATOR panels.
No canopy is made: the model keeps its own windows and frames; they get glass (model_glass.glaze: the model's original
panes, and flat panes in the openings they leave).

Run on the committed originals (git checkout be357476 -- the tu_95ms geo, asset and both textures first).
Usage: python3 tools/cockpit_models/tu_95ms.py [--dry-run]
"""
import math, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L   # noqa: E402
import handbuilt as H     # noqa: E402

VID = 'tu_95ms'
SOURCE_BONE = 'wreck_fuselage_0__hull'
STICK_BONE = 'wreck_fuselage_0__control_stick_pitch'

TURQ = (74, 150, 154)          # Soviet cockpit turquoise
TURQ_D = (52, 112, 116)
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
FRAME = (60, 66, 60)           # K-36D seat structure
CUSHION = (78, 80, 64)
YELLOW = (226, 186, 30)
RED = (196, 36, 30)
METAL = (128, 132, 134)
FLOOR = (46, 50, 50)

# ---------------------------------------------------------------- the measured cockpit (metres from each eye)
LEAN = math.radians(12)
HINGE = 0.16                   # centre facet half width
WING = math.radians(30)
CENTRE_X = 0.7506              # the centre line, inboard of each pilot's eye
FRONT = dict(SILL_Y=-0.48, FLOOR_Y=-1.24, BACK_Z=-0.45, CONSOLE_Y=-0.82, PANEL_Z=0.56, TOP_C=-0.300, TOP_IN=-0.270,
             TOP_OUT=-0.360, BOT_C=-0.980, FRONT_Z=0.95, FRONT_Y_C=-0.250, FRONT_Y_W=-0.320, WALL_X=0.680, LAYOUT_DY=-0.16)

# one entry per crewman: his seat, which way his outer wall lies (+1: +x, the aircraft's left)
STATIONS = {
    'pilot': dict(FRONT, SEAT=0, OUT=1),
    'nav': dict(FRONT, SEAT=1, OUT=-1),          # the co-pilot (right seat; the name keeps the template's)
}


def use(name):
    g = globals()
    g.update(STATIONS[name])
    g['STATION'] = name
    g['LAYOUT'] = LAYOUT_PILOT if name == 'pilot' else mirror(LAYOUT_PILOT)
    g['R_C'] = np.array([-1.0, 0, 0])                                   # the crewman's right
    g['U_C'] = np.array([0, math.cos(LEAN), math.sin(LEAN)])
    g['N_C'] = np.array([0, math.sin(LEAN), -math.cos(LEAN)])           # toward him
    # facet sides: +1 his right wing, -1 his left; the outer wall is on his left for the pilot (OUT +1), right for the
    # navigator, the inner wing reaching the centre line
    outer = -1 if OUT == 1 else 1
    g['OUTER'] = outer
    g['WING_LEN'] = {outer: (WALL_X - HINGE) / math.cos(WING), -outer: (CENTRE_X - HINGE) / math.cos(WING)}


def facets(E):
    o = E + np.array([0, (TOP_C + BOT_C) / 2, PANEL_Z])
    centre = H.Facet(o, R_C, U_C, N_C)
    wings = {}
    for side in (1, -1):                                       # +1: the crewman's right wing
        R = R_C * side * math.cos(WING) + N_C * math.sin(WING)
        N = N_C * math.cos(WING) - R_C * side * math.sin(WING)
        hinge = o + R_C * side * HINGE
        wings[side] = H.Facet(hinge, R, U_C, N)                # a: metres outward from the hinge
    return centre, wings[1], wings[-1]


def top_of(side, a):
    if side == 0:
        return TOP_C
    end = TOP_OUT if side == OUTER else TOP_IN
    return TOP_C + (end - TOP_C) * a / WING_LEN[side]


def bot_of(side):
    if side == OUTER:
        return CONSOLE_Y
    # (where the two inner wings meet on the centre line their slabs cross: the navigator's 3 mm lower, so their
    # bottom faces do not lie in one plane)
    return BOT_C - (0.003 if side != 0 and STATION in ('nav', 'rnav_r') else 0.0)


def mirror(layout):
    """a layout for the crewman on the other side: the centre facet flipped about his centre line, the wings swapped"""
    return [(n, -f, (-a if f == 0 else a), y, spec, d) for n, f, a, y, spec, d in layout]


# Instrument layouts (y as on the Su-24's panel: each station adds its LAYOUT_DY). facet: 0 centre (a: metres to the crewman's right of his centre line), +1 his right wing, -1
# his left wing (a: metres outward from the hinge). y: from his eye. The pilot's outer wing is his left (-1), the
# navigator's his right (+1).
LAYOUT_PILOT = [
    ('adi',      0,  0.000, -0.245, ('game', 'ATTITUDE', 0.094), 'KPP-1273K attitude director'),
    ('hsi',      0,  0.000, -0.362, ('game', 'HEADING', 0.088), 'PNP-72 horizontal situation'),
    ('radalt',   0,  0.000, -0.470, ('game', 'RADAR_ALT', 0.054), 'RV-21 radio altimeter'),
    ('ias',      0, -0.102, -0.232, ('game', 'AIRSPEED', 0.060), 'KUS-2500 airspeed'),
    ('mach',     0, -0.102, -0.312, ('game', 'MACH', 0.052), 'M-2.5 Machmeter'),
    ('aoa',      0, -0.102, -0.392, ('game', 'AOA', 0.050), 'UAP-14 angle of attack'),
    ('spo15',    0, -0.102, -0.475, ('game', 'RWR', 0.046), 'SPO-15 Beryoza'),
    ('alt',      0,  0.102, -0.232, ('game', 'ALTIMETER', 0.060), 'VDI-30 altimeter'),
    ('vvi',      0,  0.102, -0.312, ('game', 'VVI', 0.052), 'VAR-300 vertical speed'),
    ('g',        0,  0.102, -0.392, ('game', 'G_METER', 0.046), 'accelerometer'),
    ('sweep',    0,  0.102, -0.475, ('paint_rect', 0.056, 0.046), 'wing sweep indicator'),
    ('caution',  0, -0.128, -0.165, ('paint_disc', 0.024), 'master caution lamp'),
    ('clock',   -1,  0.060, -0.222, ('game', 'CLOCK', 0.042), 'AChS-1 clock'),
    ('weapons', -1,  0.120, -0.335, ('paint_rect', 0.150, 0.070), 'weapons control panel'),
    ('gear',    -1,  0.200, -0.440, ('paint_rect', 0.048, 0.100), 'gear control panel'),
    ('rpm',      1,  0.055, -0.225, ('game', 'ENGINE_RPM', 0.056), 'ITA-2T twin tachometer'),
    ('fuel',     1,  0.150, -0.230, ('game', 'FUEL_QTY', 0.044), 'fuel quantity'),
    ('egt_l',    1,  0.050, -0.305, ('game', 'EGT', 0.042), 'gas temperature, left engine'),
    ('egt_r',    1,  0.120, -0.305, ('game', 'EGT', 0.042), 'gas temperature, right engine'),
    ('warning',  1,  0.095, -0.405, ('annunciator', 0.140, 0.070), 'warning lights'),
    ('hyd',      1,  0.055, -0.505, ('game', 'FILLER_HYDRAULIC', 0.034), 'hydraulic pressures'),
    ('oxy',      1,  0.130, -0.505, ('game', 'FILLER_OXYGEN', 0.034), 'oxygen'),
]
LAYOUT_NAV = [
    ('radar',    0,  0.000, -0.272, ('display', 'RADAR', 0.170), 'Orion-A radar scope'),
    ('radctl',   0,  0.000, -0.445, ('paint_rect', 0.220, 0.062), 'radar control panel'),
    ('warning',  0,  0.000, -0.540, ('annunciator', 0.200, 0.040), 'navigator warning panel'),
    ('caution',  0, -0.128, -0.165, ('paint_disc', 0.022), 'master warning lamp'),
    ('adi_n',   -1,  0.055, -0.235, ('game', 'ATTITUDE', 0.062), 'AGR-81 standby attitude'),
    ('alt_n',   -1,  0.140, -0.240, ('game', 'ALTIMETER', 0.048), 'altimeter'),
    ('ias_n',   -1,  0.055, -0.330, ('game', 'AIRSPEED', 0.048), 'airspeed'),
    ('vvi_n',   -1,  0.140, -0.335, ('game', 'VVI', 0.044), 'vertical speed'),
    ('hsi_n',    1,  0.065, -0.235, ('game', 'HEADING', 0.066), 'PNP-72 horizontal situation'),
    ('clock_n',  1,  0.175, -0.230, ('game', 'CLOCK', 0.040), 'clock'),
    ('pns',      1,  0.125, -0.370, ('paint_rect', 0.170, 0.090), 'PNS-24 navigation / attack panel'),
    ('relief',   1,  0.125, -0.470, ('paint_rect', 0.170, 0.050), 'Relief terrain-following panel'),
]


# ---------------------------------------------------------------- painted faces

def panel_paint(side, W_, H_, a0, a1, yb, yt):
    def fn(d, im):
        SS = H.A.Atlas.SS
        H.noisy(d, im, TURQ, 5, seed=3 + side + 7 * SEAT)

        def to_px(a, y):
            s = (a - a0) / (a1 - a0)
            if side == -1:
                s = 1 - s
            return s * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        for y in ((y_ + LAYOUT_DY for y_ in (-0.30, -0.42, -0.52)) if side == 0 else
                  (y_ + LAYOUT_DY for y_ in (-0.28, -0.38, -0.48))):
            if yb < y < yt:
                x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
                d.line([x0, yy, x1, yy], fill=TURQ_D + (255,), width=SS * 2)
        for a, y in ((a0 + 0.012, yb + 0.012), (a1 - 0.012, yb + 0.012), (a0 + 0.012, yt - 0.02), (a1 - 0.012, yt - 0.02)):
            x, yy = to_px(a, y); r = SS * 2.2
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        for name, f, a, y, spec, _ in LAYOUT:
            if f != side:
                continue
            x, yy = to_px(a, y + LAYOUT_DY)
            px_m = W_ * SS / (a1 - a0)
            if spec[0] in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + H.RIM) + 0.004) * px_m
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 26, 26, 255))
                if dia > 0.04:
                    pw, ph = dia * 0.55 * px_m, 0.007 * px_m
                    py = yy + r + ph * 0.9
                    d.rectangle([x - pw / 2, py - ph / 2, x + pw / 2, py + ph / 2], fill=(18, 18, 18, 255))
            else:
                w, h = (spec[2], spec[2]) if spec[0] == 'display' else (spec[1], spec[2])
                sx, sy = (w / 2 + 0.006) * px_m, (h / 2 + 0.006) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 26, 26, 255))
        if side == 0 and STATION == 'pilot':
            for k, lab in enumerate(['ТОРМОЗ', 'САУ ОТКЛ']):
                x, yy = to_px(-0.09 + k * 0.18, -0.560 + LAYOUT_DY)
                d.rectangle([x - 70, yy - 14, x + 70, yy + 14], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 18)
    return fn


def p_weapons(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['ПУШКА', 'НАР', 'УР', 'БОМБ', 'КАБ']
    for k, lab in enumerate(labs):
        x = S * (0.11 + k * 0.195)
        H.lamp(d, [x - 16, Hh * .14, x + 16, Hh * .38], (210, 210, 200), None, lit=(k == 0))
        d.rectangle([x - 4, Hh * .48, x + 4, Hh * .70], fill=(190, 190, 190, 255))
        H.text(d, (x, Hh * .86), lab, Hh * .085)


def p_gear(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        y = Hh * (0.12 + 0.11 * k)
        H.lamp(d, [S * .2, y, S * .8, y + Hh * .08], (60, 150, 70), None, lit=True)
    H.text(d, (S / 2, Hh * .55), 'ШАССИ', S * .16)
    d.rectangle([S * .4, Hh * .62, S * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_sweep(d, im):
    """the wing sweep indicator (УКС): a quarter scale 20 to 65 degrees, the needle on the wings' setting"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(14, 15, 15, 255))
    cx, cy, r = S * .2, Hh * .82, Hh * .66
    for k, deg in enumerate((0, 15, 25, 35)):
        t = math.radians(90 - deg / 35 * 90)
        d.line([cx + r * .78 * math.cos(t), cy - r * .78 * math.sin(t), cx + r * math.cos(t), cy - r * math.sin(t)],
               fill=(230, 230, 220, 255), width=3)
        H.text(d, (cx + r * 1.12 * math.cos(t), cy - r * 1.12 * math.sin(t)), str(deg), Hh * .12)
    t = math.radians(90)
    d.line([cx, cy, cx + r * .9 * math.cos(t), cy - r * .9 * math.sin(t)], fill=(240, 240, 230, 255), width=4)
    H.text(d, (S * .72, Hh * .86), 'ЗАКРЫЛ.', Hh * .11)


def p_radctl(d, im):
    """the Orion-A's control panel under the scope: mode push-lights, range and gain rotaries"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    labs = ['ОБЗОР', 'КАРТА', 'СЛЕЖ', 'ТВ']
    for k, lab in enumerate(labs):
        x0 = S * (0.04 + 0.13 * k)
        H.lamp(d, [x0, Hh * .18, x0 + S * .11, Hh * .82], (210, 210, 200), lab, lit=(k == 0), px=Hh * .2)
    for k, lab in enumerate(['ДАЛЬН', 'УСИЛ', 'ЯРК']):
        x, r = S * (0.62 + 0.13 * k), Hh * .28
        d.ellipse([x - r, Hh * .42 - r, x + r, Hh * .42 + r], fill=(10, 10, 11, 255), outline=(120, 120, 120, 255), width=2)
        d.line([x, Hh * .42, x + r * .7, Hh * .42 - r * .7], fill=(235, 235, 225, 255), width=3)
        H.text(d, (x, Hh * .86), lab, Hh * .16)


def p_pns(d, im):
    """the PNS-24 navigation / attack system panel: the waypoint and mode selectors, a digit window and push-lights"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    H.text(d, (S * .5, Hh * .1), 'ПНС-24', Hh * .1, bold=True)
    d.rectangle([S * .06, Hh * .22, S * .52, Hh * .44], fill=(6, 6, 6, 255), outline=(90, 90, 90, 255))
    H.text(d, (S * .29, Hh * .33), '03  147.5', Hh * .14, fill=(230, 200, 120))
    for k, lab in enumerate(['ППМ', 'РЕЖИМ', 'ЦЕЛЬ']):
        x, r = S * (0.64 + 0.13 * k), Hh * .13
        d.ellipse([x - r, Hh * .33 - r, x + r, Hh * .33 + r], fill=(10, 10, 11, 255), outline=(120, 120, 120, 255), width=2)
        d.line([x, Hh * .33, x, Hh * .33 - r * .8], fill=(235, 235, 225, 255), width=3)
        H.text(d, (x, Hh * .52), lab, Hh * .08)
    for k in range(6):
        x0 = S * (0.05 + 0.155 * k)
        H.lamp(d, [x0, Hh * .62, x0 + S * .13, Hh * .9], (210, 190, 120) if k % 2 else (90, 170, 90), None, lit=(k == 1))


def p_relief(d, im):
    """the Relief terrain-following radar's panel: the clearance height rotary and its mode push-lights"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(20, 22, 22, 255))
    H.text(d, (S * .12, Hh * .5), 'РЕЛЬЕФ', Hh * .2, bold=True)
    for k, lab in enumerate(['ВКЛ', 'ОГИБ', 'ОБХОД']):
        x0 = S * (0.27 + 0.15 * k)
        H.lamp(d, [x0, Hh * .18, x0 + S * .13, Hh * .82], (210, 210, 200), lab, lit=(k == 1), px=Hh * .22)
    x, r = S * .87, Hh * .32
    d.ellipse([x - r, Hh * .5 - r, x + r, Hh * .5 + r], fill=(10, 10, 11, 255), outline=(120, 120, 120, 255), width=2)
    d.line([x, Hh * .5, x - r * .7, Hh * .5 - r * .7], fill=(235, 235, 225, 255), width=3)


def p_disc_red(d, im):
    S = im.width
    d.ellipse([0, 0, S - 1, S - 1], fill=(120, 18, 14, 255))
    d.ellipse([S * .18, S * .14, S * .55, S * .4], fill=(220, 90, 80, 255))


def p_hud_body(d, im):
    """the PPV sight's aft face: the projector housing, its brightness rotary and the reticle selector"""
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(40, 42, 44, 255))
    d.rectangle([2, 2, S - 3, Hh - 3], outline=(64, 66, 68, 255), width=3)
    for k, lab in enumerate(['ЯРК', 'РЕЖ']):
        x, r = S * (0.18 + 0.64 * k), Hh * .2
        d.ellipse([x - r, Hh * .45 - r, x + r, Hh * .45 + r], fill=(14, 14, 15, 255), outline=(130, 130, 130, 255), width=3)
        d.line([x, Hh * .45, x + r * .7, Hh * .45 - r * .7], fill=(230, 230, 220, 255), width=3)
        H.text(d, (x, Hh * .8), lab, Hh * .13)
    H.text(d, (S * .5, Hh * .45), 'ППВ', Hh * .2, bold=True)


# the side consoles, front to back: (share, title, kind)
CONSOLE_PILOT = [(0.10, 'ЗАПУСК', 'guarded'), (0.26, 'РУД', 'throttle'), (0.12, 'ТОПЛИВО', 'toggles'),
                 (0.12, 'КРЫЛО', 'rotary'), (0.12, 'Р-862', 'freq'), (0.14, 'СПС', 'lamps'), (0.14, 'КИСЛОРОД', 'rotary')]
CONSOLE_NAV = [(0.14, 'РСБН', 'rotary'), (0.12, 'АРК-15', 'freq'), (0.16, 'ЭЛЕКТРОПИТАНИЕ', 'toggles'),
               (0.14, 'АЗС', 'breakers'), (0.14, 'СУО', 'guarded'), (0.12, 'КОНДИЦ', 'rotary'), (0.18, 'ФОНАРЬ', 'guarded')]
CONSOLE_CENTRE = [(0.25, 'ПРИЦЕЛ', 'toggles'), (0.25, 'ВЗРЫВ', 'guarded'), (0.25, 'СВЯЗЬ', 'freq'),
                  (0.25, 'ОСВЕЩ', 'rotary')]


def p_console(rows, mirror):
    """a console top from above (tile top = forward): black sub-panels with Cyrillic titles, each its own kind of
    controls; mirror for a console on the +x side (its tile runs inner to outer the other way)"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ + (255,))
        y = Hh * 0.01
        for share, title, kind in rows:
            y1 = y + share * Hh
            if kind == 'throttle':                                  # the twin throttles' quadrant and its scale
                d.rectangle([S * .08, y + 3, S * .92, y1 - 3], fill=(36, 56, 58, 255))
                for x0 in (.36, .52):
                    d.rectangle([S * x0, y + Hh * .02, S * (x0 + .09), y1 - Hh * .02], fill=(8, 8, 8, 255))
                for j in range(6):
                    yy = y + Hh * .025 + (y1 - y - Hh * .05) * j / 5
                    d.line([S * .66, yy, S * .8, yy], fill=(220, 220, 210, 255), width=2)
                for j, lab in enumerate(['ФОРС', 'МАКС', 'МГ', 'СТОП']):
                    H.text(d, (S * .2, y + Hh * .03 + (y1 - y - Hh * .06) * j / 3), lab, S * .09)
                y = y1
                continue
            d.rectangle([S * .07, y + 3, S * .93, y1 - 3], fill=(22, 24, 24, 255), outline=(54, 96, 98, 255), width=2)
            for sx in (.12, .88):
                for sy in (y + 9, y1 - 9):
                    d.ellipse([S * sx - 3, sy - 3, S * sx + 3, sy + 3], fill=(84, 86, 86, 255))
            H.text(d, (S * .5, y + Hh * .012), title, min(S * .1, Hh * .011))
            cy, top = (y + y1) / 2 + Hh * .006, y + Hh * .024
            if kind == 'guarded':
                for j in range(3):
                    x = S * (0.24 + 0.26 * j)
                    d.rectangle([x - S * .07, cy - Hh * .016, x + S * .07, cy + Hh * .016], fill=(170, 30, 26, 255),
                                outline=(60, 10, 8, 255), width=2)
                    d.line([x - S * .05, cy, x + S * .05, cy], fill=(110, 14, 12, 255), width=3)
            elif kind == 'rotary':
                for j in range(2):
                    x, r = S * (0.3 + 0.4 * j), S * .11
                    for a in range(-120, 121, 40):
                        t = math.radians(a)
                        d.line([x + r * 1.3 * math.sin(t), cy - r * 1.3 * math.cos(t), x + r * 1.6 * math.sin(t),
                                cy - r * 1.6 * math.cos(t)], fill=(210, 210, 200, 255), width=2)
                    d.ellipse([x - r, cy - r, x + r, cy + r], fill=(10, 10, 11, 255), outline=(110, 110, 110, 255), width=2)
                    d.line([x, cy, x - r * .8 * math.sin(.5 * (j + 1)), cy - r * .8 * math.cos(.5 * (j + 1))],
                           fill=(235, 235, 225, 255), width=3)
            elif kind == 'freq':
                d.rectangle([S * .18, top + Hh * .004, S * .82, top + Hh * .022], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                H.text(d, (S * .5, top + Hh * .013), '124.50', Hh * .014, fill=(230, 200, 120))
                for x in (.3, .7):
                    d.ellipse([S * x - S * .09, cy + Hh * .012 - S * .09, S * x + S * .09, cy + Hh * .012 + S * .09],
                              fill=(14, 14, 15, 255), outline=(120, 120, 120, 255), width=2)
            elif kind == 'toggles':
                nrow = max(1, int((y1 - top) / (Hh * .03)))
                for r_ in range(nrow):
                    yy = top + (y1 - top) * (r_ + .5) / nrow
                    for j in range(4):
                        x = S * (0.2 + 0.2 * j)
                        d.ellipse([x - 5, yy - 5, x + 5, yy + 5], fill=(120, 122, 118, 255))
                        d.rectangle([x - 2, yy - Hh * .011, x + 2, yy], fill=(205, 205, 200, 255))
            elif kind == 'breakers':
                for r_ in range(3):
                    yy = top + (y1 - top) * (r_ + .5) / 3
                    for j in range(5):
                        x = S * (0.17 + 0.165 * j)
                        d.ellipse([x - 6, yy - 6, x + 6, yy + 6], fill=(6, 6, 6, 255), outline=(150, 150, 140, 255), width=2)
            elif kind == 'lamps':
                for r_ in range(2):
                    for j in range(3):
                        x0 = S * (0.14 + 0.25 * j); yy = top + (y1 - top) * (r_ * .45 + .1)
                        H.lamp(d, [x0, yy, x0 + S * .2, yy + (y1 - top) * .33], (210, 190, 120) if (j + r_) % 2 else (90, 170, 90), None)
            y = y1
        if mirror:
            im.paste(im.transpose(Image.FLIP_LEFT_RIGHT))
    return paint


def p_wall(out):
    """a cockpit side wall: seams and rivets; the pilot's with the emergency gear and canopy handles, the
    navigator's with a breaker panel"""
    def paint(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=TURQ_D + (255,))
        for x in (S * .3, S * .62):
            d.line([x, 0, x, Hh], fill=(40, 88, 90, 255), width=3)
        for y in (Hh * .1, Hh * .9):
            for x in range(6, S, 14):
                d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(86, 140, 142, 255))
        d.rectangle([S * .38, Hh * .3, S * .54, Hh * .66], fill=(22, 24, 24, 255), outline=(70, 110, 112, 255), width=2)
        if out > 0:
            d.rectangle([S * .405, Hh * .4, S * .445, Hh * .6], fill=(190, 160, 30, 255))
            d.rectangle([S * .475, Hh * .4, S * .515, Hh * .6], fill=(170, 30, 26, 255))
            H.text(d, (S * .46, Hh * .35), 'АВАР. ВЫПУСК', Hh * .045)
        else:
            for r_ in range(3):
                for j in range(5):
                    x, y = S * (0.405 + 0.026 * j), Hh * (0.4 + 0.09 * r_)
                    d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(6, 6, 6, 255), outline=(140, 140, 130, 255))
            H.text(d, (S * .46, Hh * .35), 'АЗС', Hh * .05)
    return paint


def p_cushion(is_back):
    """the K-36D's grey-olive cushions: stitched panels; the back with the harness and stencil"""
    def fn(d, im):
        S, Hh = im.width, im.height
        d.rectangle([0, 0, S, Hh], fill=CUSHION + (255,))
        dark = tuple(c - 18 for c in CUSHION) + (255,)
        for k in range(1, 3):
            d.line([S * k / 3, 0, S * k / 3, Hh], fill=dark, width=3)
        for k in range(1, 5 if is_back else 3):
            y = Hh * k / (5 if is_back else 3)
            d.line([0, y, S, y], fill=tuple(c - 9 for c in CUSHION) + (255,), width=2)
        d.rectangle([2, 2, S - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            for x in (S * .3, S * .7):
                d.rectangle([x - S * .05, 0, x + S * .05, Hh * .7], fill=(92, 96, 80, 255))
            d.rectangle([S * .42, Hh * .6, S * .58, Hh * .72], fill=(150, 150, 146, 255))
            H.text(d, (S * .5, Hh * .9), 'ТУ-95', Hh * .07, fill=(30, 30, 26), bold=True)
    return fn


def p_headpad(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(32, 33, 32, 255))
    d.rounded_rectangle([S * .1, Hh * .12, S * .9, Hh * .88], radius=S * .12, fill=(48, 49, 46, 255),
                        outline=(18, 18, 18, 255), width=3)


def p_stripes(d, im):
    S, Hh = im.width, im.height
    d.rectangle([0, 0, S, Hh], fill=(220, 180, 28, 255))
    for k in range(-Hh, S, 18):
        d.polygon([(k, Hh), (k + 9, Hh), (k + 9 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


# ---------------------------------------------------------------- build (one crewman's station)

def build(atlas, E):
    """the station use() selected, about its crewman's eye E: its panel section, instruments, outer wall and console,
    seat, pedals and stick. Returns (mesh, stick mesh or None, stick foot, gauges, displays, the panel's lip)"""
    m = H.Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(turq=TURQ, turq_d=TURQ_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED, metal=METAL,
                                                floor=FLOOR, bezel=(30, 31, 32)).items()}
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    P = lambda x, y, z: E + np.array([x, y, z], float)          # noqa: E731  eye-relative point
    gauges, displays = [], []
    centre, right, left = facets(E)
    fac = {0: centre, 1: right, -1: left}
    ext = {0: (-HINGE, HINGE), 1: (0.0, WING_LEN[1]), -1: (0.0, WING_LEN[-1])}

    def at(side, a, y):
        return fac[side].at(a, E[1] + y)

    # -- the main panel: three facets, each a 2 cm slab with its painted face
    ppm = 200                    # (a smaller texture than the fighters: the panels painted at half density)
    for side in (0, -1, 1):
        a0, a1 = ext[side]
        yt = max(top_of(side, a0), top_of(side, a1)); yb = bot_of(side)
        W_ = int(round((a1 - a0) * ppm)); H_ = int(round((yt - yb) / math.cos(LEAN) * ppm))
        rect = atlas.paint(W_, H_, panel_paint(side, W_, H_, a0, a1, yb, yt))
        n = 6
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            t0, t1 = (top_of(side, aa0) - yb) / (yt - yb), (top_of(side, aa1) - yb) / (yt - yb)
            pts = [at(side, aa0, yb), at(side, aa1, yb), at(side, aa1, top_of(side, aa1)), at(side, aa0, top_of(side, aa0))]
            su = (lambda s: 1 - s) if side == -1 else (lambda s: s)
            m.face(pts, [m.uv(rect, su(s0), 0), m.uv(rect, su(s1), 0), m.uv(rect, su(s1), t1), m.uv(rect, su(s0), t0)],
                   normal=fac[side].N)
        back = -fac[side].N * 0.02
        corners = [at(side, a0, yb), at(side, a1, yb), at(side, a1, top_of(side, a1)), at(side, a0, top_of(side, a0))]
        m.flat([c + back for c in corners], sw['turq_d'], normal=-fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['turq_d'], toward=mid + (mid - mid0))

    # -- instruments and panels
    cols, lamps = L.lamps_of('cw', 'soviet')
    painters = {'weapons': p_weapons, 'gear': p_gear, 'sweep': p_sweep, 'radctl': p_radctl, 'pns': p_pns,
                'relief': p_relief}
    for name, side, a, y, spec, _ in LAYOUT:
        y += LAYOUT_DY
        f = fac[side]
        c = at(side, a, y)
        kind = spec[0]
        if kind == 'game':
            gauges.append({'Seat': SEAT, 'Kind': spec[1], 'Center': H.r5(c + f.N * H.GAUGE_LIFT), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': SEAT, 'Kind': 'ANNUNCIATOR', 'Center': H.r5(c + f.N * 0.004), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': cols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': SEAT, 'Center': H.r5(c + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': 'CRT'})
            m.frame_rect(c, f.N, f.U, w, w, 0.014, 0.008, sw['black'])
            # the Orion scope's deep rubber hood, open toward the navigator's eyes
            H.hood(m, f, a, E[1] + y, w + 0.02, w + 0.02, 0.11, sw['black'])
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 160 if w >= h else 56
            ph = max(16, int(round(pw * h / w)))
            rect = atlas.paint(pw, ph, painters[name])
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':                                     # the gear lever stands out of its panel
                m.box(c + f.N * 0.04 - f.U * 0.02, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.02, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])

    # -- the panel's top edge for the coaming (main() lofts one coaming over both sections)
    lip = []
    for side, aa in ((-1, np.linspace(WING_LEN[-1], 0, 4)), (0, np.linspace(HINGE, -HINGE, 7)),
                     (1, np.linspace(0, WING_LEN[1], 4))):
        for a in aa:
            p = at(side, a, top_of(side, a))
            if lip and np.linalg.norm(lip[-1][0] - p) < 1e-6:
                continue
            lip.append((p, fac[side]))

    # -- the outer wall and console, the closing faces under the panel
    o = OUT
    xw = E[0] + WALL_X * o
    wall = atlas.paint(180, 100, p_wall(o))
    z0, z1 = E[2] + BACK_Z, E[2] + PANEL_Z - 0.10
    q = [[xw, E[1] + CONSOLE_Y - 0.02, z0], [xw, E[1] + CONSOLE_Y - 0.02, z1], [xw, E[1] + SILL_Y - 0.01, z1],
         [xw, E[1] + SILL_Y - 0.01, z0]]
    m.quad_tex(q if o > 0 else [q[1], q[0], q[3], q[2]], wall, normal=[-o, 0, 0])
    wing = fac[OUTER]

    def wing_bottom_z(x):
        a = (abs(x) - HINGE) / math.cos(WING)
        return wing.at(a, E[1] + CONSOLE_Y)[2]
    con_in = 0.25
    xi, xo = E[0] + con_in * o, E[0] + WALL_X * o
    zf_i, zf_o = wing_bottom_z(con_in), wing_bottom_z(WALL_X)
    cy, fy = E[1] + CONSOLE_Y, E[1] + FLOOR_Y + 0.02
    zb = E[2] + BACK_Z + 0.06
    con = atlas.paint(40, 210, p_console(CONSOLE_PILOT if STATION == 'pilot' else CONSOLE_NAV, o > 0))
    top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf_o], [xi, cy, zf_i]]
    if o < 0:
        top = [top[1], top[0], top[3], top[2]]
    m.face(top, [m.uv(con, 0, 0), m.uv(con, 1, 0), m.uv(con, 1, 1), m.uv(con, 0, 1)], normal=[0, 1, 0])
    m.flat([[xi, fy, zb], [xi, fy, zf_i], [xi, cy, zf_i], [xi, cy, zb]], sw['turq_d'], normal=[-o, 0, 0])
    m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['turq_d'], normal=[0, 0, -1])
    m.box([xw - o * 0.015, E[1] + SILL_Y - 0.005, E[2] + (BACK_Z + PANEL_Z - 0.1) / 2], (X, Y, Z),
          (0.03, 0.01, (PANEL_Z - 0.1 - BACK_Z) / 2), sw['dgrey'])                     # canopy rail ledge
    # under the centre facet: its lower closing face back to the footwell
    bl, br = centre.at(HINGE, E[1] + BOT_C), centre.at(-HINGE, E[1] + BOT_C)
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['turq_d'], normal=[0, -1, 0])
    # the floor: a dark plate over the model's own, from behind the seat to the pedals, wall to centre line
    xa, xb = E[0] + WALL_X * o, E[0] - CENTRE_X * o
    m.flat([[xa, E[1] + FLOOR_Y + 0.012, E[2] + BACK_Z], [xb, E[1] + FLOOR_Y + 0.012, E[2] + BACK_Z],
            [xb, E[1] + FLOOR_Y + 0.012, E[2] + PANEL_Z + 0.30], [xa, E[1] + FLOOR_Y + 0.012, E[2] + PANEL_Z + 0.30]],
           sw['floor'], normal=[0, 1, 0])
    # rudder pedals under the panel
    for s in (1, -1):
        m.box(P(s * 0.12, -0.95, 0.54), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.045, 0.07, 0.01), sw['mgrey'])
        m.box(P(s * 0.12, -1.01, 0.58), (X, Y, Z), (0.012, 0.03, 0.06), sw['dgrey'])

    # -- the pilot's twin throttles (RUD) on his outer console
    if STATION == 'pilot':
        tz = 0.18
        for dx in (0.0, 0.035):
            xt = o * (0.33 - dx)
            m.box(P(xt, CONSOLE_Y + 0.055, tz), (X, H.unit([0, 1, 0.25]), H.unit([0, -0.25, 1])), (0.007, 0.06, 0.007),
                  sw['metal'])
        grip_ax = (X, H.unit([0, 1, 0.3]), H.unit([0, -0.3, 1]))
        m.box(P(o * 0.3125, CONSOLE_Y + 0.135, tz + 0.02), grip_ax, (0.035, 0.04, 0.04), sw['black'])
        m.box(P(o * 0.3125, CONSOLE_Y + 0.18, tz + 0.03), grip_ax, (0.03, 0.01, 0.032), sw['dgrey'])
        m.box(P(o * 0.29, CONSOLE_Y + 0.15, tz + 0.045), grip_ax, (0.004, 0.008, 0.008), sw['red'])

    # -- K-36D ejection seat
    pan = atlas.paint(64, 64, p_cushion(False))
    back = atlas.paint(64, 96, p_cushion(True))
    headpad = atlas.paint(64, 56, p_headpad)
    stripes = atlas.paint(64, 12, p_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    m.box(P(0, -0.962, -0.004), (X, Y, Z), (0.196, 0.08, 0.22), sw['frame'])
    m.box(P(0, -0.847, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, pan, -X, Z))
    for s in (1, -1):
        m.box(P(s * 0.212, -0.842, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(P(s * 0.17, -0.40, -0.404), ax_b, (0.02, 0.54, 0.025), sw['frame'])
        m.box(P(s * 0.192, -0.78, 0.20), (X, Y, Z), (0.006, 0.06, 0.04), sw['black'])
    m.box(P(0, -0.462, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.462, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(P(0, 0.01, -0.314), (X, Y, Z), (0.16, 0.15, 0.10), sw['frame'])
    m.box(P(0, 0.02, -0.199), (X, Y, Z), (0.11, 0.09, 0.015), sw['black'], front=(2, 1, headpad, X, Y))
    m.box(P(0, 0.162, -0.300), (X, Y, Z), (0.15, 0.004, 0.075), sw['black'], front=(1, 1, stripes, X, -Z))
    m.box(P(0, -0.795, 0.10), (X, Y, Z), (0.17, 0.006, 0.02), sw['mgrey'])
    m.box(P(0, -0.788, 0.10), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])
    H.k36_handle(m, lambda x, y: P(x, -0.80 + y, 0.256), atlas.swatch((178, 34, 30)), sw['black'])

    # -- control stick (RUS): the boot stays with the cockpit; the pilot's into the stick bone, the navigator's fixed
    sz = 0.38
    if STATION in ('pilot', 'nav'):
        m.box(P(0, -0.97, sz), (X, Y, Z), (0.045, 0.10, 0.045), sw['dgrey'])
    stick = H.Mesh(atlas) if STATION == 'pilot' else (m if STATION == 'nav' else H.Mesh(atlas))
    col_ax = (X, H.unit([0, 1, -0.07]), H.unit([0, 0.07, 1]))
    stick.box(P(0, -0.68, sz - 0.002), col_ax, (0.011, 0.215, 0.011), sw['metal'])
    g_ax = (X, H.unit([0, 1, 0.21]), H.unit([0, -0.21, 1]))
    gc = P(0, -0.417, sz - 0.008)
    stick.box(gc, g_ax, (0.021, 0.062, 0.027), sw['black'])
    stick.box(gc + g_ax[1] * 0.066, g_ax, (0.017, 0.006, 0.022), sw['dgrey'])
    stick.box(gc + g_ax[1] * 0.07 + g_ax[2] * 0.010, g_ax, (0.006, 0.004, 0.006), sw['red'])
    stick.box(gc + g_ax[2] * 0.031 + g_ax[1] * 0.015, g_ax, (0.008, 0.012, 0.005), sw['metal'])
    stick.box(gc - g_ax[2] * 0.034 + g_ax[1] * 0.01, g_ax, (0.012, 0.035, 0.004), sw['metal'])
    foot = P(0, -0.90, sz)
    return m, (stick if STATION == 'pilot' else None), foot, gauges, displays, lip


def coaming(m, atlas, lips, E0, E1):
    """one black coaming over both sections: lofted from the panels' top edges forward to the windscreen's foot,
    wall to wall, its sides down to the sills"""
    black = atlas.swatch(BLACK)
    pts = sorted([p for lip in lips for p, f in lip], key=lambda p: -p[0])
    lip_edge = []
    for p in pts:
        if lip_edge and abs(lip_edge[-1][0] - p[0]) < 0.004:
            continue
        lip_edge.append(p + np.array([0, 0.025, 0.03]))
    xc = (E0[0] + E1[0]) / 2                                       # the centre line
    half = abs(E0[0] - xc) + STATIONS['pilot']['WALL_X'] - 0.04
    rows = []
    for t in (0.0, 0.35, 0.7, 1.0):
        row = []
        for le in lip_edge:
            s = (le[0] - xc) / half
            yf = E0[1] + FRONT_Y_C + (FRONT_Y_W - FRONT_Y_C) * s * s
            z = le[2] + (E0[2] + FRONT_Z - le[2]) * t
            x = le[0] + (xc + s * (half - 0.08) - le[0]) * t
            y = le[1] + (yf - le[1]) * t + 0.025 * math.sin(math.pi * t) * (1 - s * s)
            row.append(np.array([x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], black, normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):                             # the lip's face toward the crew
        a, b = lip_edge[j], lip_edge[j + 1]
        m.flat([a, b, b - [0, 0.04, 0.03], a - [0, 0.04, 0.03]], black, normal=[0, 0, -1])
    for j in (0, len(lip_edge) - 1):                                # the coaming's side ends down to the sill
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            yb_ = E0[1] + SILL_Y - 0.06
            m.flat([p, q, [q[0], yb_, q[2]], [p[0], yb_, p[2]]], black, toward=[xc, yb_, (p[2] + q[2]) / 2])


def hud(m, atlas, E0, ac):
    """the pilot's PPV head-up sight on the coaming: its projector body, two posts and the combiner glass"""
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    dgrey = atlas.swatch(DGREY)
    body = atlas.paint(128, 64, p_hud_body)
    g_h, g_hw = 0.130, 0.075
    lean = math.radians(30)
    g_up = np.array([0, math.cos(lean), -math.sin(lean)])
    g_n = H.unit(np.cross(X, g_up))
    base_y = E0[1] + TOP_C + 0.045
    g_c = np.array([E0[0], E0[1], E0[2] + PANEL_Z + 0.13])
    # (no canopy is made: the model keeps its own glazing and frames, under which the sight stands clear)
    g_bot = g_c - g_up * g_h / 2
    body_top = base_y + 0.06
    body_c = np.array([E0[0], (base_y + body_top) / 2, E0[2] + PANEL_Z + 0.10])
    m.box(body_c, (X, Y, Z), (0.075, (body_top - base_y) / 2, 0.10), dgrey, front=(2, -1, body, -X, Y))
    if g_bot[1] - 0.006 > body_top + 0.003:
        for s in (-1, 1):
            m.box([E0[0] + s * (g_hw + 0.004), (body_top + g_bot[1] - 0.006) / 2, g_bot[2]], (X, Y, Z),
                  (0.004, (g_bot[1] - 0.006 - body_top) / 2, 0.005), dgrey)
    H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'rect', 0.0, ('bottom', 'side'), 0.007, dgrey)
    return dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h)


def centre_console(m, atlas, E0, E1):
    """the narrow console between the seats, from behind them to under the panel"""
    xc = (E0[0] + E1[0]) / 2
    hw = 0.085
    y0, y1 = E0[1] + FLOOR_Y + 0.02, E0[1] + CONSOLE_Y - 0.06
    z0, z1 = E0[2] + BACK_Z + 0.10, E0[2] + PANEL_Z - 0.17           # (its front short of the inner wings)
    con = atlas.paint(40, 200, p_console(CONSOLE_CENTRE, False))
    top = [[xc - hw, y1, z0], [xc + hw, y1, z0], [xc + hw, y1, z1], [xc - hw, y1, z1]]
    m.face(top, [m.uv(con, 1, 0), m.uv(con, 0, 0), m.uv(con, 0, 1), m.uv(con, 1, 1)], normal=[0, 1, 0])
    td = atlas.swatch(TURQ_D)
    for s in (1, -1):
        x = xc + s * hw
        m.flat([[x, y0, z0], [x, y0, z1], [x, y1, z1], [x, y1, z0]], td, normal=[s, 0, 0])
    m.flat([[xc - hw, y0, z0], [xc + hw, y0, z0], [xc + hw, y1, z0], [xc - hw, y1, z0]], td, normal=[0, 0, -1])


def seat_only(atlas, E):
    """a crew seat about the eye E, as the pilots' (the engineer's, the navigator's, the radio operator's)"""
    m = H.Mesh(atlas)
    sw = {k: atlas.swatch(c) for k, c in dict(turq=TURQ, turq_d=TURQ_D, black=BLACK, dgrey=DGREY, mgrey=MGREY,
                                                frame=FRAME, cushion=CUSHION, yellow=YELLOW, red=RED, metal=METAL,
                                                floor=FLOOR, bezel=(30, 31, 32)).items()}
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    P = lambda x, y, z: E + np.array([x, y, z], float)          # noqa: E731  eye-relative point
    # -- K-36D ejection seat
    pan = atlas.paint(64, 64, p_cushion(False))
    back = atlas.paint(64, 96, p_cushion(True))
    headpad = atlas.paint(64, 56, p_headpad)
    stripes = atlas.paint(64, 12, p_stripes)
    ax_b = (X, H.unit([0, 1, -0.14]), H.unit([0, 0.14, 1]))
    m.box(P(0, -0.962, -0.004), (X, Y, Z), (0.196, 0.08, 0.22), sw['frame'])
    m.box(P(0, -0.847, 0.026), (X, Y, Z), (0.19, 0.035, 0.21), sw['cushion'], front=(1, 1, pan, -X, Z))
    for s in (1, -1):
        m.box(P(s * 0.212, -0.842, -0.014), (X, Y, Z), (0.012, 0.15, 0.24), sw['frame'])
        m.box(P(s * 0.17, -0.40, -0.404), ax_b, (0.02, 0.54, 0.025), sw['frame'])
        m.box(P(s * 0.192, -0.78, 0.20), (X, Y, Z), (0.006, 0.06, 0.04), sw['black'])
    m.box(P(0, -0.462, -0.234), ax_b, (0.19, 0.33, 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(P(0, -0.462, -0.294), ax_b, (0.205, 0.36, 0.035), sw['frame'])
    m.box(P(0, 0.01, -0.314), (X, Y, Z), (0.16, 0.15, 0.10), sw['frame'])
    m.box(P(0, 0.02, -0.199), (X, Y, Z), (0.11, 0.09, 0.015), sw['black'], front=(2, 1, headpad, X, Y))
    m.box(P(0, 0.162, -0.300), (X, Y, Z), (0.15, 0.004, 0.075), sw['black'], front=(1, 1, stripes, X, -Z))
    m.box(P(0, -0.795, 0.10), (X, Y, Z), (0.17, 0.006, 0.02), sw['mgrey'])
    m.box(P(0, -0.788, 0.10), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])
    H.k36_handle(m, lambda x, y: P(x, -0.80 + y, 0.256), atlas.swatch((178, 34, 30)), sw['black'])

    return m


def seat_eye(ac, k):
    """where a seated crewman's eye is over his seat, at the pilots' height above it (the model's eye attachments
    for the seats behind the pilots stand 0.30 m higher)"""
    s_ = ac.data['Seats'][k]
    return np.array(s_['Position'], float) + [0, ac.eye(0)[1] - ac.data['Seats'][0]['Position'][1], 0]


def old_interior(E0, E1, rear):
    """boxes (vehicle-local lo, hi) of the old flight deck: the tub inside the walls above the floor from behind the
    pilots' seats to the old panel, the old panel and coaming block under the windscreen (the glazed nose below and
    ahead stays), the old rear crew seats"""
    e = np.array([(E0[0] + E1[0]) / 2, E0[1], E0[2]])
    out = [(e + [-1.45, -1.23, -0.55], e + [1.45, FRONT['SILL_Y'] - 0.005, 0.50]),
           (e + [-1.40, -1.23, 0.45], e + [1.40, -0.20, 1.05])]
    for r in rear:
        out.append((r + [-0.35, -1.45, -0.60], r + [0.35, 0.25, 0.40]))
    return out


def main(argv):
    dry = '--dry-run' in argv
    ac = L.Aircraft(VID)
    E0, E1 = ac.eye(0), ac.eye(1)
    rear = [seat_eye(ac, k) for k in (2, 3, 4, 5)]                      # the crew behind the pilots
    removed = H.remove_faces(ac, old_interior(E0, E1, rear))
    removed['old sight in the view'] = H.remove_sight_clutter(ac, E0)
    atlas = H.A.Atlas(VID)
    use('pilot')
    mesh, stick, foot, gauges, displays, lip0 = build(atlas, E0)
    use('nav')
    mesh_n, _, _, gauges_n, displays_n, lip1 = build(atlas, E1)
    mesh.faces += mesh_n.faces
    for r in rear:
        mesh.faces += seat_only(atlas, r).faces
    use('pilot')
    coaming(mesh, atlas, (lip0, lip1), E0, E1)
    centre_console(mesh, atlas, E0, E1)
    ac.asset['CockpitGauges'] = {'Gauges': []}                        # both pilots' instruments are written anew
    ac.asset['FlightDisplays'] = {'Displays': []}
    pm = H.install(ac, mesh, (stick, foot), SOURCE_BONE, STICK_BONE, gauges + gauges_n, displays + displays_n, None, E0)
    print(f'{VID}: removed {removed}; new cockpit {len(pm["polys"])} faces, {len(gauges)} + {len(gauges_n)} gauges')
    import model_glass as MG                                          # the windows' glass (the frames stay the model's)
    line, _ = MG.glaze(ac)
    print(f'{VID}: {line}')
    if not dry:
        H.write(ac, atlas)


if __name__ == '__main__':
    main(sys.argv[1:])
