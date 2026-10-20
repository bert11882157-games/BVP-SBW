#!/usr/bin/env python3
"""Modelled helicopter cockpits (cockpit_lib.py does the panel, coaming, consoles, floor and seat).

For each helicopter in helicopters/<vid>.json, every crew station listed there is measured from its eye and the
canopy glass, the old interior baked into the hull (panels, instruments, glareshields, seats, sticks) is removed, and
a modelled station is put in its place:

  * the fixed parts (panel, coaming, consoles, walls, floor, seat, the cyclic's boot and the lever quadrants) go into
    the bone `cockpit` (parent hull);
  * the flight controls are bones of their own that the aircraft rig turns: `cyclic_roll_<seat>` with its child
    `cyclic_pitch_<seat>` (a centre cyclic, or a side-arm one on a console), `collective_<seat>` (the lever beside
    the seat, hinged at its aft end), `throttle_<seat>` (the power levers) and `pedal_left_<seat>` /
    `pedal_right_<seat>` (hung from a hinge above the pedal); AircraftRig gets their surfaces (cyclic and pedals
    follow the pilot's pitch, bank and yaw inputs) and flaps (PRESENTED_COLLECTIVE and PRESENTED_THROTTLE);
  * CockpitGauges and FlightDisplays of every listed seat are replaced by the new panel's instruments (helicopter
    eras below: rotor RPM and torque are live), HeadUpDisplay is written where the station has a HUD, and old glass
    inside a station is dropped from CanopyGlass.

Spec (helicopters/<vid>.json): {"palette": cockpit_lib.PALETTES key, "reference": where the layout comes from,
  "stations": [{"seat": n, "eye": [x,y,z] (default: the seat's eye attachment), "era": key of ERAS here,
                "cyclic": "centre" | "right" | "left" | "none", "collective": true, "throttle": 0..2 levers,
                "pedals": true, "hud": false, "gunsight": false, "tub": {cockpit_lib.Tub overrides}}],
  "remove": [[x0,x1,y0,y1,z0,z1], ...] hull faces wholly inside these boxes go as well,
  "keep": [[...]] hull faces with their centre in these boxes are never removed,
  "colours": {"panel": [r,g,b], ...} overrides of the palette's and accent colours (nearest uniform block of the
  texture), "glass": [[[x,y,z] x 4], ...] quads of canopy glass the model lacks, added to CanopyGlass (both windings)
  before the stations are measured}
A station's "eye" with "move_eye": true also moves the seat's eye attachment there (for an eye that sat inside the
airframe). helicopters/<vid>.py, when present, may define extra(ac, tubs, stations, mesh, c): details of that
helicopter's cockpit (sights, consoles) added to the cockpit bone.

Run it on the committed originals (`git checkout` the helicopter's geo and asset files first): the tub is measured
from the old interior, as fleet.py's are.

Usage: python3 tools/cockpit_models/helicopters.py [--dry-run] [--sheet DIR] [vid...]   (no ids: every spec)
"""
import glob, json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

SPECS = os.path.join(HERE, 'helicopters')
X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])

# Helicopters hold their whole interior in the one `hull` bone (no wreck pieces).
L.is_interior_bone = lambda name: name == 'hull'

# Instrument sets of helicopter stations, rows from the top of each facet as in cockpit_lib.ERAS.
ERAS = {
    # Sizes are real instrument sizes (3 in = .083, 2 1/4 in = .060, 2 in = .052); layout shrinks a set to fit.
    # Western steam-gauge panel: flight instruments in the T with the dual tach, torque and engine dials beside them
    'HELO_STEAM': dict(
        centre=[[('AIRSPEED', .072), ('ATTITUDE', .083), ('ALTIMETER', .072)],
                [('FILLER_CLOCK', .052), ('HEADING', .083), ('VVI', .072)],
                [('G_METER', .052), ('FILLER_FUEL', .060), ('FILLER_HYDRAULIC', .052)]],
        left=[[('ROTOR_RPM', .066)], [('TORQUE', .060), ('THROTTLE', .052)],
              [('FILLER_TEMP', .048), ('FILLER_OIL', .048)], [('FILLER_VOLTS', .044), ('FILLER_TEMP', .044)]],
        right=[[('FILLER_RPM', .056), ('FILLER_TEMP', .056)], [('FILLER_OIL', .048), ('FILLER_OIL', .048)],
               [('FILLER_FUEL', .048), ('FILLER_HYDRAULIC', .048)], [('FILLER_VOLTS', .044), ('FILLER_CLOCK', .044)]],
        hud=False, displays='CRT', seat='bucket'),
    # the same with a radar warning receiver (AH-1F, Mi-24V, Mi-24D)
    'HELO_STEAM_RWR': dict(
        centre=[[('AIRSPEED', .072), ('ATTITUDE', .083), ('ALTIMETER', .072)],
                [('FILLER_CLOCK', .052), ('HEADING', .083), ('VVI', .072)],
                [('G_METER', .052), ('FILLER_FUEL', .060), ('FILLER_HYDRAULIC', .052)]],
        left=[[('ROTOR_RPM', .066)], [('TORQUE', .060), ('THROTTLE', .052)],
              [('FILLER_TEMP', .048), ('FILLER_OIL', .048)], [('FILLER_VOLTS', .044), ('FILLER_TEMP', .044)]],
        right=[[('RWR', .066)], [('FILLER_RPM', .052), ('FILLER_TEMP', .052)],
               [('FILLER_OIL', .046), ('FILLER_OIL', .046)], [('FILLER_FUEL', .044), ('FILLER_VOLTS', .044)]],
        hud=False, displays='CRT', seat='bucket'),
    # a gunner's station: the basic flight instruments around his sight
    'HELO_GUNNER': dict(
        centre=[[('ATTITUDE', .075)], [('AIRSPEED', .060), ('ALTIMETER', .060)],
                [('FILLER_CLOCK', .046), ('FILLER_FUEL', .046)]],
        left=[[('ROTOR_RPM', .056)], [('TORQUE', .052)], [('THROTTLE', .044), ('FILLER_VOLTS', .044)]],
        right=[[('HEADING', .060)], [('VVI', .052)], [('G_METER', .044), ('FILLER_HYDRAULIC', .044)]],
        hud=False, displays='CRT', seat='bucket'),
    # a gunner's station with a big sight on the centre line: the attitude dial moves to the left wing
    'HELO_GUNNER_SIGHT': dict(
        centre=[[('FILLER_CLOCK', .044)], [('AIRSPEED', .060), ('ALTIMETER', .060)],
                [('FILLER_FUEL', .046), ('FILLER_TEMP', .046)]],
        left=[[('ATTITUDE', .072)], [('ROTOR_RPM', .056)], [('TORQUE', .050), ('FILLER_VOLTS', .044)]],
        right=[[('HEADING', .066)], [('VVI', .056)], [('G_METER', .046), ('FILLER_HYDRAULIC', .044)]],
        hud=False, displays='CRT', seat='bucket'),
    # AH-64D pilot: two 6 in MPDs with the up-front display between them, standby dials and engine strip below
    'HELO_APACHE': dict(
        centre=[[('UFCP', .130)], [('AIRSPEED', .052), ('ATTITUDE', .060), ('ALTIMETER', .052)],
                [('FILLER_CLOCK', .040), ('FILLER_FUEL', .044)]],
        left=[[('D:STORES', .165)], [('ROTOR_RPM', .050), ('TORQUE', .050)], [('THROTTLE', .042), ('FILLER_OIL', .042)]],
        right=[[('D:RADAR', .165)], [('RWR', .056), ('FILLER_FUEL', .046)], [('FILLER_HYDRAULIC', .042), ('FILLER_VOLTS', .042)]],
        hud=False, displays='CRT', seat='bucket'),
    # glass stations of the 1990s: two or three multi-function displays, standby dials, rotor and torque
    'HELO_GLASS': dict(
        centre=[[('D:PFD', .165)], [('AIRSPEED', .052), ('ATTITUDE', .060), ('ALTIMETER', .052)]],
        left=[[('D:STORES', .160)], [('ROTOR_RPM', .050), ('TORQUE', .050)], [('THROTTLE', .042), ('FILLER_OIL', .042)]],
        right=[[('D:RADAR', .160)], [('RWR', .056), ('FILLER_FUEL', .046)], [('FILLER_HYDRAULIC', .042), ('FILLER_VOLTS', .042)]],
        hud=False, displays='CRT', seat='bucket'),
    # Tiger: two 6 x 8 in colour MFDs and the control/display unit, standby dials below
    'HELO_GLASS_LCD': dict(
        centre=[[('UFCP', .140)], [('AIRSPEED', .052), ('ATTITUDE', .060), ('ALTIMETER', .052)]],
        left=[[('D:STORES', .170)], [('ROTOR_RPM', .050), ('TORQUE', .050)], [('THROTTLE', .042), ('FILLER_OIL', .042)]],
        right=[[('D:PFD', .170)], [('RWR', .056), ('FILLER_FUEL', .046)], [('FILLER_HYDRAULIC', .042), ('FILLER_VOLTS', .042)]],
        hud=False, displays='LCD', seat='bucket'),
    # Mi-28N: three colour MFI displays with the radar page, standby dials below
    'HELO_GLASS_RADAR_LCD': dict(
        centre=[[('D:PFD', .165)], [('AIRSPEED', .052), ('ATTITUDE', .060), ('ALTIMETER', .052)]],
        left=[[('D:STORES', .160)], [('ROTOR_RPM', .050), ('TORQUE', .050)], [('THROTTLE', .042), ('FILLER_OIL', .042)]],
        right=[[('D:RADAR', .160)], [('RWR', .056), ('FILLER_FUEL', .046)], [('FILLER_HYDRAULIC', .042), ('FILLER_VOLTS', .042)]],
        hud=False, displays='LCD', seat='bucket'),
    # glass station of a gunner/co-pilot: two displays and the standby dials
    'HELO_GLASS_GUNNER': dict(
        centre=[[('D:PFD', .155)], [('AIRSPEED', .048), ('ATTITUDE', .054), ('ALTIMETER', .048)]],
        left=[[('ROTOR_RPM', .050)], [('TORQUE', .048)], [('FILLER_FUEL', .044)]],
        right=[[('D:STORES', .150)], [('HEADING', .048), ('FILLER_CLOCK', .040)]],
        hud=False, displays='CRT', seat='bucket'),
    # Ka-50: the steam panel with the IT-23 tactical screen and the ILS-31 head-up display
    'HELO_KA50': dict(
        centre=[[('ATTITUDE', .090), ('D:STORES', .140)],
                [('AIRSPEED', .064), ('HEADING', .083), ('ALTIMETER', .064)],
                [('VVI', .056), ('FILLER_CLOCK', .046), ('G_METER', .048)]],
        left=[[('ROTOR_RPM', .062), ('TORQUE', .056)], [('THROTTLE', .048), ('FILLER_TEMP', .048)],
              [('FILLER_FUEL', .052), ('FILLER_OIL', .044)], [('FILLER_HYDRAULIC', .044), ('FILLER_VOLTS', .044)]],
        right=[[('RWR', .062)], [('FILLER_RPM', .048), ('FILLER_OIL', .048)],
               [('FILLER_HYDRAULIC', .046), ('FILLER_VOLTS', .044)], [('FILLER_FUEL', .044), ('FILLER_CLOCK', .040)]],
        hud=True, displays='CRT', seat='ejection'),
}
L.ERAS.update(ERAS)

_lib_layout = L.layout
BLOCKS = []                        # (facet, a, y, kind, w, h) of the last layout's panel furniture

# Caution and warning panels of helicopters (cockpit_lib.LAMP_SETS shape: caption, Annunciator condition, colour)
L.LAMP_SETS.update({
    'heli': dict(columns=8, lamps=[
        ('MASTER|WARNING', 'MASTER_WARNING', 'red'), ('ROTOR|RPM LOW', 'ROTOR_RPM_LOW', 'red'),
        ('ENG|OUT', 'ENGINE_OUT', 'red'), ('FIRE', 'FIRE', 'red'), ('LAUNCH', 'MISSILE_LAUNCH', 'red'),
        ('LOW|ALT', 'LOW_ALT', 'red'), ('XMSN|OIL', 'NONE', 'amber'), ('ENG|RUN', 'ENGINE_RUN', 'green'),
        ('MASTER|CAUTION', 'MASTER_CAUTION', 'amber'), ('ROTOR|RPM HI', 'ROTOR_RPM_HIGH', 'amber'),
        ('TORQUE|HIGH', 'TORQUE_HIGH', 'amber'), ('FUEL|LOW', 'LOW_FUEL', 'amber'), ('RWR|LOCK', 'RWR_LOCK', 'amber'),
        ('DAMAGE', 'DAMAGE', 'amber'), ('AMMO|LOW', 'AMMO_LOW', 'amber'), ('HYD|PRESS', 'NONE', 'amber')]),
    'heli_small': dict(columns=4, lamps=[
        ('MASTER|CAUTION', 'MASTER_CAUTION', 'amber'), ('FIRE', 'FIRE', 'red'), ('LAUNCH', 'MISSILE_LAUNCH', 'red'),
        ('RWR|LOCK', 'RWR_LOCK', 'amber'), ('ROTOR|RPM LOW', 'ROTOR_RPM_LOW', 'red'), ('ENG|OUT', 'ENGINE_OUT', 'red'),
        ('FUEL|LOW', 'LOW_FUEL', 'amber'), ('AMMO|LOW', 'AMMO_LOW', 'amber')]),
})
L.RUSSIAN.update({'MASTER|WARNING': 'ОПАСНО', 'ROTOR|RPM LOW': 'МАЛО|ОБОР НВ', 'ROTOR|RPM HI': 'ВЕЛИКИ|ОБОР НВ',
                  'TORQUE|HIGH': 'ПРЕВЫШ|РЕЖИМ', 'XMSN|OIL': 'МАСЛО|РЕДУКТ', 'ENG|RUN': 'ДВИГ|РАБОТ'})


def draw_blocks(mesh, c):
    """switch panels, keyboards and blank plates of an explicit panel, centred at (a, y) on their facet"""
    for f, a, y, kind, w, h in BLOCKS:
        p = f.at(a, y)
        ax = (f.R, f.U, f.N)
        mesh.box(p + f.N * 0.006, ax, (w / 2, h / 2, 0.006), c['dgrey'], faces={(2, 1): c['black']})
        if kind == 'SWITCHES':                     # rows of toggle switches with their guards
            nx, ny = max(1, int(w / 0.026)), max(1, int(h / 0.032))
            for i in range(nx):
                for j in range(ny):
                    q = p + f.R * (-w / 2 + (i + 0.5) * w / nx) + f.U * (h / 2 - (j + 0.5) * h / ny) + f.N * 0.012
                    mesh.box(q + f.N * 0.006 - f.U * 0.003, ax, (0.0025, 0.002, 0.008), c['lgrey'])
                    mesh.box(q, ax, (0.006, 0.0025, 0.001), c['white'])
        elif kind == 'KEYPAD':                     # key grid
            nx, ny = max(1, int(w / 0.018)), max(1, int(h / 0.018))
            for i in range(nx):
                for j in range(ny):
                    q = p + f.R * (-w / 2 + (i + 0.5) * w / nx) + f.U * (h / 2 - (j + 0.5) * h / ny) + f.N * 0.014
                    mesh.box(q, ax, (w / nx * 0.36, h / ny * 0.36, 0.002), c['mgrey'])
        elif kind == 'KNOBS':                      # rotary selectors
            n = max(1, int(w / 0.03))
            for i in range(n):
                q = p + f.R * (-w / 2 + (i + 0.5) * w / n) + f.N * 0.016
                mesh.box(q, ax, (0.007, 0.007, 0.006), c['black'])
                mesh.box(q + f.N * 0.0065 + f.U * 0.004, ax, (0.0012, 0.003, 0.0005), c['white'])
        # 'BLANK': the plate only
    BLOCKS.clear()


def on_eye_line(hud, E):
    """The HUD picture centred where the pilot's neutral line of sight (straight ahead along +Z from the eye) meets
    the combiner, and cut down to the part of the glass that stays symmetric about that point, so the boresight
    sits in the middle of the picture and every symbol is drawn on the glass."""
    c, n, up = (np.asarray(hud[k], float) for k in ('centre', 'normal', 'up'))
    t = float(np.dot(n, c - E)) / n[2]
    p = E + np.array([0.0, 0.0, t])
    dv, du = float(np.dot(p - c, up)), float(p[0] - c[0])
    h, w = hud['height'] - 2 * abs(dv), hud['width'] - 2 * abs(du)
    if h <= 0 or w <= 0:
        raise SystemExit(f'the eye line misses the HUD combiner by {dv:+.3f} m up, {du:+.3f} m across')
    return dict(hud, centre=p, width=w, height=h)


def layout(tub, era, facets, hud_body=None):
    """Helicopter stations with an explicit `panel` (real positions from the aircraft's panel drawings); every other
    era goes to cockpit_lib, which packs its rows tight at real size and fills the band below with switch panels."""
    if 'panel' not in era:
        return _lib_layout(tub, era, facets, hud_body)
    s, out = tub.s, []
    for key, f in (('centre', facets[0]), ('left', facets[-1]), ('right', facets[1])):
        # [kind, a, v, size] / [kind, a, v, w, h]: a metres right of the centre line (centre) or out from the hinge
        # (wings), v metres below the facet's top edge at a; all at real size
        for item in era['panel'].get(key, []):
            kind, a, v = item[0], item[1] * s, item[2] * s
            y = f.top(a) - v * math.cos(tub.lean)
            if kind.startswith('B:'):                  # panel furniture, drawn by the driver after the build
                BLOCKS.append((f, a, y, kind[2:], item[3] * s, item[4] * s))
            elif kind.startswith('A:'):                # annunciator: the game draws its housing and lamps
                out.append((f, a, y, kind, (item[3] * s, item[4] * s)))
            else:
                out.append((f, a, y, kind, item[3] * s))
    return out, []


L.layout = layout
for _e in ERAS.values():
    _e['helicopter'] = True


def load_spec(vid):
    return json.load(open(os.path.join(SPECS, vid + '.json')))


def all_specs():
    return sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(SPECS, '*.json')))


def in_box(V, b):
    return np.all((V[:, 0] >= b[0]) & (V[:, 0] <= b[1]) & (V[:, 1] >= b[2]) & (V[:, 1] <= b[3]) &
                  (V[:, 2] >= b[4]) & (V[:, 2] <= b[5]))


def drop_faces(mesh, boxes):
    """removes the faces of [mesh] lying wholly inside any of [boxes] (x0,x1,y0,y1,z0,z1)"""
    mesh.faces = [f for f in mesh.faces if not any(in_box(np.asarray(f[0]), b) for b in boxes)]


def px(p):
    return L.r5(np.asarray(p, float) * np.array([16.0, 16.0, -16.0]))


def bone(name, pivot, mesh):
    return {'name': name, 'parent': 'hull', 'pivot': px(pivot), 'poly_mesh': mesh.poly_mesh()}


# ---------------------------------------------------------------- flight controls

def cyclic(t, c, kind):
    """(boot Mesh, moving Mesh, pivot): a centre cyclic between the knees, or a side-arm one on a console"""
    s, E = t.s, t.E
    boot, m = L.Mesh(), L.Mesh()
    if kind == 'centre':
        foot = np.array([E[0], t.floor_y + 0.03 * s, E[2] + 0.36 * s])
        boot.box(foot + [0, 0.03 * s, 0], (X, Y, Z), (0.05 * s, 0.03 * s, 0.05 * s), c['black'])
        top = np.array([E[0], E[1] - 0.50 * s, E[2] + 0.30 * s])        # the grip at the knees, leaning aft
        ax = L.unit(top - foot)
        side = L.unit(np.cross(ax, X))
        L_ = np.linalg.norm(top - foot)
        m.box(foot + ax * L_ / 2, (X, ax, side), (0.012 * s, L_ / 2, 0.012 * s), c['metal'])
        g_ax = (X, L.unit(ax + [0, 0, 0.15]), L.unit(np.cross(X, L.unit(ax + [0, 0, 0.15]))))
        gc = top + g_ax[1] * 0.05 * s
        m.box(gc, g_ax, (0.022 * s, 0.06 * s, 0.026 * s), c['black'])
        m.box(gc + g_ax[1] * 0.064 * s, g_ax, (0.018 * s, 0.006 * s, 0.022 * s), c['dgrey'])
        m.box(gc + g_ax[1] * 0.068 * s + g_ax[2] * 0.012 * s, g_ax, (0.006 * s, 0.004 * s, 0.006 * s), c['red'])
        m.box(gc + g_ax[2] * 0.030 * s + g_ax[1] * 0.02 * s, g_ax, (0.008 * s, 0.012 * s, 0.005 * s), c['metal'])
        return boot, m, foot
    sd = 1 if kind == 'left' else -1                                     # x is the pilot's left
    foot = np.array([E[0] + sd * 0.80 * t.W, t.console_y + 0.01 * s, E[2] + 0.18 * s])
    boot.box(foot + [0, 0.012 * s, 0], (X, Y, Z), (0.035 * s, 0.012 * s, 0.05 * s), c['black'])
    ax = (X, L.unit([-sd * 0.1, 1, 0.2]), L.unit([0, -0.2, 1]))
    gc = foot + ax[1] * 0.08 * s
    m.box(gc, ax, (0.02 * s, 0.06 * s, 0.026 * s), c['black'])
    m.box(gc + ax[1] * 0.064 * s, ax, (0.016 * s, 0.006 * s, 0.02 * s), c['dgrey'])
    m.box(gc + ax[1] * 0.07 * s + ax[2] * 0.01 * s, ax, (0.005 * s, 0.004 * s, 0.005 * s), c['red'])
    return boot, m, foot


def collective(t, c):
    """(quadrant Mesh, lever Mesh, hinge): the lever runs forward beside the seat on the left, hinged at its aft end,
    modelled fully down"""
    s, E = t.s, t.E
    # in the gap between the seat's side (0.21 s) and the left console's inner edge (0.58 W), just above the
    # console top, so it stays in the open
    x = E[0] + 0.5 * (0.21 * s + 0.58 * t.W)
    hinge = np.array([x, E[1] - 0.62 * s, E[2] - 0.18 * s])
    q = L.Mesh()
    q.box(hinge + [0, -0.03 * s, 0.02 * s], (X, Y, Z), (0.016 * s, 0.03 * s, 0.05 * s), c['black'])
    m = L.Mesh()
    ax = L.unit([0, -0.18, 1])                                         # the lever droops slightly when down
    up = L.unit(np.cross(ax, X))
    Ln = 0.50 * s
    m.box(hinge + ax * Ln / 2, (X, up, ax), (0.013 * s, 0.013 * s, Ln / 2), c['metal'])
    head = hinge + ax * (Ln + 0.05 * s)
    m.box(head, (X, up, ax), (0.024 * s, 0.03 * s, 0.06 * s), c['black'])   # switch box
    m.box(head + up * 0.034 * s, (X, up, ax), (0.012 * s, 0.006 * s, 0.02 * s), c['mgrey'])
    m.box(head + up * 0.03 * s + ax * 0.04 * s, (X, up, ax), (0.006 * s, 0.006 * s, 0.006 * s), c['red'])
    m.box(head + X * 0.028 * s, (X, up, ax), (0.004 * s, 0.012 * s, 0.012 * s), c['yellow'])
    return q, m, hinge


def throttle(t, c, levers):
    """(quadrant Mesh, levers Mesh, pivot): power levers on the left console, modelled at idle (leaning aft)"""
    s, E = t.s, t.E
    pivot = np.array([E[0] + 0.78 * t.W, t.console_y + 0.025 * s, E[2] + 0.22 * s])
    q, m = L.Mesh(), L.Mesh()
    q.box(pivot + [0, -0.012 * s, 0], (X, Y, Z), (0.04 * s, 0.014 * s, 0.08 * s), c['black'])
    ax = L.unit([0, 1, -0.45])                                          # idle: tipped back
    fw = L.unit(np.cross(X, ax))
    for k in range(levers):
        dx = (k - (levers - 1) / 2) * 0.03 * s
        base = pivot + X * dx
        m.box(base + ax * 0.045 * s, (X, ax, fw), (0.006 * s, 0.045 * s, 0.006 * s), c['metal'])
        m.box(base + ax * 0.095 * s, (X, ax, fw), (0.011 * s, 0.012 * s, 0.014 * s), c['black'])
    return q, m, pivot


def pedal(t, c, sd):
    """(Mesh, hinge): one pedal hung from a hinge above it; sd = +1 left, -1 right"""
    s, E = t.s, t.E
    pz = t.front_z - 0.10 * s
    hinge = np.array([E[0] + sd * 0.12 * s, t.floor_y + 0.32 * s, pz])
    m = L.Mesh()
    m.box(hinge + [0, -0.10 * s, 0], (X, Y, Z), (0.008 * s, 0.10 * s, 0.008 * s), c['dgrey'])
    pa = (X, L.unit([0, 1, -0.4]), L.unit([0, 0.4, 1]))
    m.box(hinge + [0, -0.22 * s, -0.01 * s], pa, (0.05 * s, 0.07 * s, 0.01 * s), c['mgrey'])
    m.box(hinge + [0, -0.22 * s, -0.022 * s], pa, (0.042 * s, 0.006 * s, 0.003 * s), c['black'])
    return m, hinge


def station_parts(ac, t, st, c):
    """the moving controls of one station: [(bone name, Mesh, pivot, rig entry or None)], fixed parts Mesh"""
    seat = st['seat']
    fixed = L.Mesh()
    parts = []
    kind = st.get('cyclic', 'centre')
    if kind != 'none':
        boot, stick, foot = cyclic(t, c, kind)
        fixed.extend(boot)
        travel = (8.0, 8.0) if kind == 'centre' else (4.0, 4.0)        # degrees of roll, pitch at full input
        parts.append((f'cyclic_roll_{seat}', L.Mesh(), foot,
                      {'Axis': [0, 0, 1], 'ControlWeights': {'ElevatorUp': 0, 'RightRoll': 1, 'RudderRight': 0},
                       'MaxDeflectionDegrees': travel[0]}))
        parts.append((f'cyclic_pitch_{seat}', stick, foot,
                      {'Axis': [1, 0, 0], 'ControlWeights': {'ElevatorUp': 1, 'RightRoll': 0, 'RudderRight': 0},
                       'MaxDeflectionDegrees': travel[1], 'Parent': f'cyclic_roll_{seat}'}))
    if st.get('pedals', True):
        for sd, name in ((1, 'left'), (-1, 'right')):
            m, hinge = pedal(t, c, sd)
            # rudder right: the right pedal goes forward, the left one back
            parts.append((f'pedal_{name}_{seat}', m, hinge,
                          {'Axis': [1, 0, 0], 'ControlWeights': {'ElevatorUp': 0, 'RightRoll': 0,
                                                                 'RudderRight': -1 if sd > 0 else 1},
                           'MaxDeflectionDegrees': 12.0}))
    if st.get('collective', True):
        q, lever, hinge = collective(t, c)
        fixed.extend(q)
        parts.append((f'collective_{seat}', lever, hinge,
                      {'Axis': [1, 0, 0], 'Input': 'PRESENTED_COLLECTIVE', 'AngleSign': 1,
                       'MaxDeflectionDegrees': 16.0}))
    n = st.get('throttle', 2)
    if n:
        q, levers, pivot = throttle(t, c, n)
        fixed.extend(q)
        parts.append((f'throttle_{seat}', levers, pivot,
                      {'Axis': [1, 0, 0], 'Input': 'PRESENTED_THROTTLE', 'AngleSign': -1,
                       'MaxDeflectionDegrees': 40.0}))
    return parts, fixed


# ---------------------------------------------------------------- one helicopter

CONTROL_BONES = ('cockpit', 'cyclic_roll_', 'cyclic_pitch_', 'collective_', 'throttle_', 'pedal_left_', 'pedal_right_')


def ours(name):
    return name == 'cockpit' or name.startswith(CONTROL_BONES[1:])


def apply(vid, dry=False):
    spec = load_spec(vid)
    ac = L.Aircraft(vid)
    sw = L.Swatches(L.texture_path(vid))
    ac.bones[:] = [b for b in ac.bones if not ours(b['name'])]
    stations = spec['stations']
    if spec.get('glass'):                             # canopy glass the model lacks, so the stations can be measured
        extra_glass = np.array([[q[0], q[1], q[2], q[0], q[2], q[3]] for q in spec['glass']], float).reshape(-1, 3, 3)
        ac.glass = np.concatenate([ac.glass, extra_glass, extra_glass[:, ::-1]]) if len(ac.glass) else extra_glass
    tubs = []
    dropped = 0
    for st in stations:
        seat = st['seat']
        if 'eye' in st:                               # the attachment is off for some seats: override it
            ac.eye = (lambda f, s0, e: (lambda s: np.array(e, float) if s == s0 else f(s)))(ac.eye, seat, st['eye'])
        dropped += ac.drop_sight_glass(ac.eye(seat), st.get('tub', {}).get('s', 1.0))
        t = L.Tub(ac, seat, **st.get('tub', {}))
        dropped += ac.drop_glass_inside(t)
        tubs.append(t)

    # the old interior
    rm, clips = L.old_interior(ac, tubs)
    hull = ac.bone('hull')
    pm = hull['poly_mesh']
    for i, (axis, cc) in clips.get('hull', {}).items():
        L.clip_keep(pm, i, axis, cc)
    gone = set(rm.get('hull', set()))
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    for i, poly in enumerate(pm['polys']):
        V = P[[v[0] for v in poly]] if poly else None
        if V is None:
            continue
        if any(in_box(V, b) for b in spec.get('remove', [])):
            gone.add(i)
        if any(in_box(V.mean(0, keepdims=True), b) for b in spec.get('keep', [])):
            gone.discard(i)
    pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone and p]

    # the new stations
    pal = dict(L.PALETTES[spec.get('palette', 'nato')])
    pal.update({k: tuple(v) for k, v in spec.get('colours', {}).items()})
    pal_name = f"{spec.get('palette', 'nato')}_{vid}"     # cockpit_lib.lamps_of reads the palette family
    L.PALETTES[pal_name] = pal
    c = {k: sw.uv(v) for k, v in pal.items()}
    c.update({k: sw.uv(v) for k, v in L.ACCENT.items()})
    c.update({k: sw.uv(tuple(v)) for k, v in spec.get('colours', {}).items()})
    cockpit = L.Mesh()
    new_bones, surfaces, flaps = [], [], []
    gauges, displays, glass, hud_out = [], [], [], None
    for st, t in zip(stations, tubs):
        era = st['era']
        if st.get('panel'):                            # this station's own panel layout
            era = f"{vid}#{st['seat']}"
            L.ERAS[era] = dict(L.ERAS[st['era']], panel=st['panel'])
        mesh, _stick, g, d, gl, hud = L.build(ac, t, era, pal_name, sw, hud=st.get('hud'),
                                              gunsight=st.get('gunsight', False))
        draw_blocks(mesh, c)
        s, E = t.s, t.E
        # cockpit_lib's fixed-wing throttle and rudder pedals: helicopters get a collective, power levers and
        # pedals that move instead
        tx, tz = E[0] + 0.78 * t.W, E[2] + 0.24 * s
        pz = t.front_z - 0.08 * s
        drop_faces(mesh, [[tx - 0.06 * s, tx + 0.06 * s, t.console_y - 0.01, t.console_y + 0.25 * s,
                           tz - 0.13 * s, tz + 0.13 * s]] +
                   [[E[0] + sg * 0.12 * s - 0.07 * s, E[0] + sg * 0.12 * s + 0.07 * s, t.floor_y - 0.01,
                     t.floor_y + 0.22 * s, pz - 0.06 * s, pz + 0.14 * s] for sg in (1, -1)])
        cockpit.extend(mesh)
        parts, fixed = station_parts(ac, t, st, c)
        cockpit.extend(fixed)
        for name, m, pivot, rig in parts:
            b = bone(name, pivot, m)
            parent = rig.pop('Parent', 'hull')
            b['parent'] = parent
            new_bones.append(b)
            entry = {'Bone': name, 'Parent': parent, 'Pivot': px(pivot), **rig}
            (flaps if 'Input' in entry else surfaces).append(entry)
        gauges += g
        displays += d
        glass += gl
        if hud:
            hud_out = (st['seat'], hud, E)

    hook = os.path.join(SPECS, vid + '.py')
    if os.path.exists(hook):
        import importlib.util
        sp = importlib.util.spec_from_file_location(f'heli_{vid}', hook)
        mod = importlib.util.module_from_spec(sp); sp.loader.exec_module(mod)
        mod.extra(ac, tubs, stations, cockpit, c)
    cpm = cockpit.poly_mesh()
    at = next(i for i, b in enumerate(ac.bones) if b['name'] == 'hull') + 1
    ac.bones[at:at] = [{'name': 'cockpit', 'parent': 'hull', 'pivot': list(hull.get('pivot', [0, 0, 0])),
                        'poly_mesh': cpm}] + new_bones

    # the rig: schema 2, with the existing rotors
    a = ac.asset
    rig = a.get('AircraftRig') or {'Schema': 2, 'Frame': 'GENERATED_MODEL_PIXELS', 'Surfaces': [], 'Rotors': []}
    rig['Schema'] = 2
    rig['Surfaces'] = [sf for sf in rig.get('Surfaces', []) if not ours(sf['Bone'])] + surfaces
    rig.setdefault('Rotors', [])
    rig.setdefault('Sweeps', [])
    rig['Flaps'] = [f for f in (rig.get('Flaps') or []) if not ours(f['Bone'])] + flaps
    a['AircraftRig'] = rig

    # instruments of the listed seats are replaced
    seats = {st['seat'] for st in stations}
    old_g = [g for g in (a.get('CockpitGauges') or {}).get('Gauges', []) if g.get('Seat', 0) not in seats]
    old_d = [d for d in (a.get('FlightDisplays') or {}).get('Displays', []) if d.get('Seat', 0) not in seats]
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': old_g + gauges}
    fd = a.get('FlightDisplays') or {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS'}
    fd['Displays'] = old_d + displays
    fd.setdefault('Schema', 1); fd.setdefault('Frame', 'VEHICLE_LOCAL_BLOCKS')
    a['FlightDisplays'] = fd
    if any(d.get('Kind') == 'STORES' for d in fd['Displays']) and 'Planform' not in fd:
        import importlib.util
        sp = importlib.util.spec_from_file_location('cockpit_layout',
                                                    os.path.join(HERE, '..', 'cockpit_layout', 'layout.py'))
        lay = importlib.util.module_from_spec(sp); sp.loader.exec_module(lay)
        fd['Planform'] = lay.planform(vid)
    if not fd['Displays']:
        a.pop('FlightDisplays')
    if hud_out:
        seat, hud, E = hud_out
        hud = on_eye_line(hud, E)
        a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat,
                              'Center': L.r5(hud['centre']), 'Normal': L.r5(hud['normal']), 'Up': L.r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': L.r5(E)}
    else:
        a.pop('HeadUpDisplay', None)
    # CanopyGlass belongs to the canopy-glass rebuild (fitted in Blender): it is read above to measure the stations
    # and left exactly as it is. The HUD picture draws on the combiner frame without a pane of its own.

    vols = [L.loader_volume(b['poly_mesh']) for b in [ac.bone('cockpit')] + new_bones if b['poly_mesh']['polys']]
    report = (f'{vid}: ' + '; '.join(f"seat {st['seat']} {st['era']} {t.describe()}" for st, t in zip(stations, tubs)) +
              f'; removed {len(gone)} old faces, cut {len(clips.get("hull", {}))}; {len(cpm["polys"])} cockpit faces, '
              f'{len(new_bones)} control bones, min volume {min(vols):+.0f}; {len(gauges)} gauges, {len(displays)} '
              f'displays, {dropped} old glass triangles dropped; colour error up to {sw.worst:.0f}')
    moved = [st for st in stations if st.get('move_eye') and 'eye' in st]
    for st in moved:
        name = ac.data['Seats'][st['seat']]['CameraPos']['EyeAttachment']
        ac.data['Attachments'][name]['Position'] = [round(float(v), 5) for v in st['eye']]
    if not dry:
        with open(ac.geo_path, 'w') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        L.write_asset(ac.asset_path, ac.asset)
        if moved:
            path = os.path.join(L.DATA_DIR, vid + '.json')
            with open(path, 'w', encoding='utf-8', newline='\n') as f:
                f.write(json.dumps(ac.data, indent=2, ensure_ascii=False) + '\n')
    return ac, tubs, report


def views(vid, out_dir, size=(700, 480)):
    """the pilot's view from each station, and one from outside looking in through the side"""
    import eyeview as EV
    os.makedirs(out_dir, exist_ok=True)
    spec = load_spec(vid)
    ac = L.Aircraft(vid)
    paths = []
    for st in spec['stations']:
        E = np.array(st['eye'], float) if 'eye' in st else ac.eye(st['seat'])
        p = os.path.join(out_dir, f"{vid}_seat{st['seat']}.png")
        EV.render(vid, p, eye=E, pitch=-22, fov=85, size=size)
        paths.append(p)
        p = os.path.join(out_dir, f"{vid}_seat{st['seat']}_down.png")
        EV.render(vid, p, eye=E + [0, 0.05, -0.05], pitch=-55, fov=95, size=size)
        paths.append(p)
    return paths


def main(argv):
    dry = '--dry-run' in argv
    out = argv[argv.index('--sheet') + 1] if '--sheet' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != out] or all_specs()
    for vid in ids:
        _, _, rep = apply(vid, dry)
        print(rep)
        if out and not dry:
            views(vid, out)


if __name__ == '__main__':
    main(sys.argv[1:])
