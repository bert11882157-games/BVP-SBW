"""Su-25 cockpit structure.

From the reference photos (cockpit-refs/su_25: the head-on panel photo and the one over the right sill):

  * one flat blue-grey panel wall to wall, leaning back a little, under a shallow black glareshield: the flight
    instruments on the left (airspeed over the VSI and altimeter, the AoA / g meter at the far left, the radio
    altimeter and gear lamps low), the big KPP attitude indicator over the NPP HSI in the centre, the engine
    instruments on the right (twin-needle RPM, EGTs, the TOPLIVO fuel tape, pressures), the annunciator column
    down the far right;
  * the row of boxes along the panel top: the PUS weapon-control box left, the ASP-17 sight's control head in the
    centre under the sight, the gear / lights signal box and the fire lamps right;
  * the ASP-17 reflector sight on the coaming in front of the armoured windscreen;
  * the centre stick with the Soviet grip, twin throttles on the left console, the K-36L seat reclined 17
    degrees in the narrow armoured tub with its thick sills.
"""
import math
import numpy as np
import structure as K

X, Y, Z = K.X, K.Y, K.Z

COLOURS = dict(panel=(104, 132, 140), shade=(70, 92, 98), wall=(104, 132, 140), console=(92, 118, 126),
               coaming=(30, 32, 33), floor=(52, 56, 56), frame=(70, 76, 72), cushion=(64, 70, 54))

LEAN = 12                      # the panel leans away from the pilot
SIDE_W = 0.25                  # the left and right instrument groups (in s)
BOX_H = 0.10                   # the boxes along the panel top


def build(S):
    t, E, s = S.t, S.E, S.s
    R = -X
    U = K.rot_x(LEAN)[0]
    N = np.array([0, math.sin(math.radians(LEAN)), -math.cos(math.radians(LEAN))])
    top = t.top_c
    bot = t.bot_w
    Zp = t.Z
    W = t.W

    def P(a, y, proud=0.0):
        """point on the panel plane: a metres to the pilot's right of the centre line, y height"""
        return np.array([E[0], top, Zp]) + R * a + U * ((y - top) / math.cos(math.radians(LEAN))) + N * proud

    h_all = (top - bot) / math.cos(math.radians(LEAN))
    side_w = SIDE_W * s
    cen_w = 2 * (W - 0.02) - 2 * side_w
    # -- the three instrument groups, one plane wall to wall
    S.plate('left', P(-(W - 0.02) + side_w / 2, (top + bot) / 2), R, U, side_w, h_all, depth=0.03,
            label='flight instruments: airspeed, VSI, altimeter, AoA / g, radio altimeter, gear lamps')
    S.plate('centre', P(0, (top + bot) / 2), R, U, cen_w, h_all, depth=0.03,
            label='KPP attitude indicator over the NPP HSI, clock, sight base')
    S.plate('right', P((W - 0.02) - side_w / 2, (top + bot) / 2), R, U, side_w, h_all, depth=0.03,
            label='engine instruments: RPM, EGTs, fuel tape, pressures; annunciator column at the far right')
    # the strips closing the 2 cm to each wall
    for sg in (1, -1):
        a = sg * (W - 0.02)
        S.m.flat([P(a, top), P(a, bot), [E[0] - sg * W, P(a, bot)[1], P(a, bot)[2]],
                  [E[0] - sg * W, P(a, top)[1], P(a, top)[2]]], S.c['panel'], normal=N)
    # -- the boxes along the panel top, standing proud of it
    box_h = BOX_H * s
    boxes = [('pus', -0.30 * s, 0.13 * s, 'PUS weapon control box', 0.03),
             ('sight_ctrl', 0.0, 0.24 * s, "ASP-17 sight control head", 0.06),
             ('signal', 0.31 * s, 0.11 * s, 'gear and lights signal box', 0.03)]
    for name, a, w, label, depth in boxes:
        c = P(a, top - box_h / 2 - 0.005 * s, depth)
        S.box(c - N * depth / 2, (R, U, N), (w / 2, box_h / 2, depth / 2), 'black')
        S.plate(name, c + N * 0.001, R, U, w, box_h, depth=0.002, colour='black', edge='black', label=label)
    # the fire lamp row between the sight head and the signal box
    S.plate('fire', P(0.19 * s, top - 0.03 * s, 0.004), R, U, 0.10 * s, 0.03 * s, depth=0.006, colour='black',
            edge='black', label='fire warning lamps')

    # -- the glareshield: a shallow black lip wall to wall, the loft to the windscreen base
    lip = [(P(-W, top, 0.0), N), (P(-0.15 * s, top, 0.0), N), (P(0.15 * s, top, 0.0), N), (P(W, top, 0.0), N)]
    K.coaming(S, lip, crown=0.02, lip_height=0.02, lip_depth=0.035, front_hw=W * 0.9)
    # -- the ASP-17 sight: the head on the coaming behind the windscreen, the reflector leaning back over it
    sight_z = Zp + 0.16 * s
    head_c = np.array([E[0], top + 0.02 * s + 0.055 * s, sight_z])
    S.box(head_c, (X, Y, Z), (0.065 * s, 0.055 * s, 0.09 * s), 'black')
    S.box(head_c + Y * 0.055 * s + Z * 0.05 * s, (X, Y, Z), (0.05 * s, 0.012 * s, 0.03 * s), 'dgrey')
    g_up, _ = K.rot_x(15)
    g_bot = head_c + Y * 0.055 * s - Z * 0.03 * s
    K.combiner(S, g_bot, g_up, 0.12 * s, 0.062 * s, frame_colour='black', post_half=(0.005, 0.006))

    # -- the tub: thick armoured sills, consoles meeting the panel along its bottom edge
    z_low = P(0, bot, 0.0)[2]
    K.shell(S, lambda x: z_low, xin_frac=0.56)
    K.footwell(S, bot, z_low, shade='shade')
    K.wall_frames(S, pitch=0.30)
    for sg in (1, -1):                                                   # the sill armour: a 7 cm ledge inside
        S.box([E[0] + sg * (W - 0.035 * s), t.sill - 0.012 * s, (t.back_z + z_low) / 2], (X, Y, Z),
              (0.035 * s, 0.012 * s, (z_low - t.back_z) / 2), 'console')

    # -- controls: the centre stick, twin throttles on the left console
    foot = np.array([E[0], t.floor_y + 0.02 * s, E[2] + 0.34 * s])
    K.stick_centre(S, foot, E[1] - 0.42 * s, grip='b8', lean=6)
    tz = E[2] + 0.12 * s
    for k, x in enumerate((E[0] + 0.58 * W, E[0] + 0.80 * W)):
        K.throttle_lever(S, x, tz, lean=22)
    S.ctx['throttle'] = (E[0] + 0.69 * W, tz)
    K.console_panels(S, 1, ['toggles', 'rotary', 'radio', 'toggles', 'breakers', 'toggles'],
                     skip=[(tz - 0.13 * s, tz + 0.13 * s)], pal_name='soviet')
    K.console_panels(S, -1, ['toggles', 'toggles', 'rotary', 'radio', 'toggles', 'breakers'], pal_name='soviet')

    # -- the seat (K-36L: the ACES II kit shape at 17 degrees stands in for it at this stage) and pedals
    g = S.ac.glass_above(E[0], E[2] - 0.35 * s, E[1])
    K.seat_aces2(S, recline=17, pan_tilt=10, headbox_top=(g - 0.03) if g is not None else None)
    K.pedals(S, raise_=0.08)


# Review views matching the reference photos (cockpit-refs/su_25): (photo, caption, eye offset from the pilot's
# eye, yaw, pitch, fov). yaw positive to the pilot's left.
REF_VIEWS = [
    ('su_25_01.jpg', 'the panel head-on', (0.0, -0.05, 0.10), 0.0, -12.0, 62.0),
    ('su_25_02.jpg', 'over the right sill, behind the pilot', (-0.85, 0.55, -0.55), 52.0, -24.0, 64.0),
]
