"""F-16C Block 50 cockpit structure.

From the reference photos (cockpit-refs/f_16c: NMUSAF F-16A, Twenthe F-16AM and the DCS Viper guide pages):

  * a flat black glareshield sloping down to the windscreen base, the wide-angle HUD standing on it in the centre
    (its two tall posts carry the combiner), the eyebrow lamp blocks either side of the HUD;
  * the ICP (integrated control panel) under the HUD, tilted up toward the pilot, the DED window to its right;
  * the centre column: a narrow vertical panel 5 cm proud of the rest, carrying the flight instruments, down to
    the console level, with the footwell open below it;
  * the two MFDs on panels turned 22 degrees toward the pilot either side of the column, their lower panels
    (knee recesses with a few switches) below them;
  * the auxiliary facets wrapping round at 45 degrees to the walls: the MISC panel over the landing gear panel on
    the left, the engine instrument column over the hydraulics / EPU / clock panel on the right;
  * the left console with the throttle on its track, the right console with the side stick at its front on a
    raised housing and the armrest behind it;
  * the ACES II seat reclined 30 degrees, rudder pedals high in the footwell.
"""
import math
import numpy as np
import structure as K

X, Y, Z = K.X, K.Y, K.Z

COLOURS = dict(panel=(38, 39, 40), shade=(26, 27, 28), wall=(84, 86, 88), console=(44, 46, 48), coaming=(18, 18, 19),
               floor=(50, 52, 54), frame=(118, 120, 116), cushion=(36, 38, 36))

LEAN = 10                      # the panel's top leans away from the pilot
MFD_TURN = 22                  # the MFD panels toward the pilot
AUX_TURN = 45                  # the outer facets toward the pilot


def build(S):
    t, E, s = S.t, S.E, S.s
    R = -X
    U, N = K.rot_x(LEAN)[0], None
    N = np.array([0, math.sin(math.radians(LEAN)), -math.cos(math.radians(LEAN))])
    top = t.top_c
    col_bot = t.bot_w                                   # the centre column ends at the console level
    Zp = t.Z

    def P(a, y, proud=0.0):
        """point on the panel's base plane: a metres to the pilot's right of the centre line, y height"""
        return np.array([E[0], top, Zp]) + R * a + U * ((y - top) / math.cos(math.radians(LEAN))) + N * proud

    # -- centre column (proud) and the ICP housing over it, the DED to the right
    col_w = 0.19 * s
    col_top = top - 0.11 * s
    col = S.plate('centre', P(0, (col_top + col_bot) / 2, 0.05), R, U, col_w, (col_top - col_bot) / math.cos(math.radians(LEAN)),
                  depth=0.08, label='centre column: flight instruments')
    # the ICP housing: a block tilted up 32 degrees so the keypad faces the pilot's eyes, under the HUD body
    icp_up = K.unit(np.array([0, math.cos(math.radians(32 + LEAN)), math.sin(math.radians(32 + LEAN))]))
    icp_n = K.unit(np.cross(R, icp_up))
    icp_c = P(0, top - 0.055 * s, 0.03)
    S.box(icp_c - icp_n * 0.035, (R, icp_up, icp_n), (0.095 * s, 0.05 * s, 0.035), 'panel')
    S.plate('icp', icp_c + icp_n * 0.001, R, icp_up, 0.16 * s, 0.085 * s, depth=0.004, colour='black',
            edge='panel', label='ICP keypad')
    ded_c = P(0.20 * s, top - 0.035 * s, -0.01)
    S.box(ded_c - N * 0.025, (R, U, N), (0.06 * s, 0.03 * s, 0.03), 'panel')
    S.plate('ded', ded_c + N * 0.006, R, icp_up, 0.105 * s, 0.045 * s, depth=0.012, colour='black', edge='panel',
            label='DED window')

    # -- the MFD panels and the lower panels under them
    w_m = math.radians(MFD_TURN)
    x_in = col_w / 2
    mfd_w, mfd_h = 0.19 * s, 0.19 * s
    mfd_top = top - 0.06 * s
    low_bot = t.bot_w
    x_out = x_in + mfd_w * math.cos(w_m)
    mfd_proud_out = -0.03 + mfd_w * math.sin(w_m)
    aux_w = 0.13 * s
    w_a = math.radians(AUX_TURN)
    aux_out = x_out + aux_w * math.cos(w_a)
    aux_proud_out = mfd_proud_out + aux_w * math.sin(w_a)
    for side, tag in ((1, 'r'), (-1, 'l')):
        Rm = K.unit(R * math.cos(w_m) + side * N * math.sin(w_m))
        inner = P(side * x_in, mfd_top - mfd_h / 2, -0.03)
        S.plate('mfd_' + tag, inner + side * Rm * mfd_w / 2, Rm, U, mfd_w, mfd_h, depth=0.03,
                label=('right' if side > 0 else 'left') + ' MFD')
        lo_h = (mfd_top - mfd_h - low_bot) / math.cos(math.radians(LEAN))
        inner_lo = P(side * x_in, low_bot + (mfd_top - mfd_h - low_bot) / 2, -0.06)
        S.plate('under_' + tag, inner_lo + side * Rm * mfd_w / 2, Rm, U, mfd_w, lo_h, depth=0.02, colour='shade',
                label=('right' if side > 0 else 'left') + ' lower panel')
        # the strip above the MFD, under the glareshield
        up_c = P(side * x_in, top - 0.03 * s, -0.03)
        S.plate('above_' + tag, up_c + side * Rm * mfd_w / 2, Rm, U, mfd_w, 0.05 * s, depth=0.03,
                label=('right' if side > 0 else 'left') + ' upper strip')
        # the auxiliary facet at 45 degrees, in two panels
        Ra = K.unit(R * math.cos(w_a) + side * N * math.sin(w_a))
        aux_in = P(side * x_out, 0.0, mfd_proud_out)
        if side < 0:
            tops = [('misc_l', 'MISC panel (master arm, laser arm, alt release)', top - 0.01, top - 0.17 * s),
                    ('gear', 'landing gear panel', top - 0.17 * s, low_bot)]
        else:
            tops = [('eng', 'engine instruments column', top - 0.01, top - 0.27 * s),
                    ('aux_r', 'hydraulics, EPU fuel, clock, cabin pressure', top - 0.27 * s, low_bot)]
        for name, label, y1, y0 in tops:
            yc = (y0 + y1) / 2
            c = P(side * x_out, yc, mfd_proud_out) + side * Ra * aux_w / 2
            S.plate(name, c, Ra, U, aux_w, (y1 - y0) / math.cos(math.radians(LEAN)), depth=0.03, label=label)
        # closing the gap between the aux facet's outer edge and the wall
        xo = E[0] - side * aux_out
        po = P(side * aux_out, top, aux_proud_out)
        pb = P(side * aux_out, low_bot, aux_proud_out)
        S.m.flat([po, pb, [E[0] - side * t.W, pb[1], pb[2]], [E[0] - side * t.W, po[1], po[2]]], S.c['panel'],
                 normal=N)

    # -- the glareshield: the lip follows the panel top, wrapping forward with the facets
    lip = []
    for a, proud in ((-aux_out, aux_proud_out), (-x_out, mfd_proud_out), (-x_in, -0.03), (-x_in, 0.0), (x_in, 0.0),
                     (x_in, -0.03), (x_out, mfd_proud_out), (aux_out, aux_proud_out)):
        lip.append((P(a, top, proud), N))
    lip = [(P(-t.W / 1.0 + 0.0, top, aux_proud_out) if False else lip[0][0], N)] + lip[1:]
    K.coaming(S, lip, crown=0.0, flat=True, front_hw=t.W * 0.92, lip_height=0.02, lip_depth=0.03)

    # -- HUD: body on the glareshield, the posts and combiner; eyebrow lamp blocks either side
    body_z0, body_z1 = Zp - 0.05, Zp + 0.26 * s
    body_y0, body_y1 = top - 0.12, top + 0.07 * s
    S.box([E[0], (body_y0 + body_y1) / 2, (body_z0 + body_z1) / 2], (X, Y, Z),
          (0.10 * s, (body_y1 - body_y0) / 2, (body_z1 - body_z0) / 2), 'coaming')
    S.m.cyl([E[0], body_y1 + 0.0005, Zp + 0.11 * s], Y, Z, 0.03 * s, 0.004, S.c['black'], cap=S.c['black'])
    g_up, _ = K.rot_x(8)
    g_h, g_hw = 0.21 * s, 0.072 * s
    g_bot = np.array([E[0], body_y1 + 0.012, Zp + 0.03 * s])
    K.combiner(S, g_bot, g_up, g_h, g_hw, frame_colour='dgrey', post_half=(0.012 * s, 0.016 * s))
    for sg in (1, -1):                                                        # the posts' feet on the body
        S.box([E[0] + sg * (g_hw + 0.012 * s), body_y1 + 0.006, Zp + 0.03 * s], (X, Y, Z),
              (0.016 * s, 0.006, 0.03 * s), 'dgrey')
    eb_up = K.unit([0, math.cos(math.radians(20)), math.sin(math.radians(20))])
    for sg, name, label in ((1, 'eyebrow_l', 'left eyebrow lamps (TF fail, master caution)'),
                            (-1, 'eyebrow_r', 'right eyebrow lamps (threat warning prime)')):
        c = np.array([E[0] + sg * 0.165 * s, top + 0.012 + 0.018 * s, Zp - 0.005])
        K.lamp_block(S, name, c, R, eb_up, 0.075 * s, 0.032 * s, depth=0.035, label=label)

    # -- the tub: consoles meet the panels along the lower panels' bottom edge
    z_low = P(0, low_bot, -0.06)[2]

    def console_front(x):
        a = abs(x - E[0])
        if a <= x_out:
            return z_low
        return z_low - min(1.0, (a - x_out) / max(aux_out - x_out, 1e-6)) * aux_w * math.sin(w_a)
    K.shell(S, console_front, xin_frac=0.58)
    K.footwell(S, low_bot, z_low, shade='shade')
    K.wall_frames(S)
    # the chin lip under the centre column, the pedal-adjust knob at its foot
    foot_c = P(0, col_bot + 0.004, 0.05)
    S.box(foot_c + N * 0.006, (R, U, N), (col_w / 2, 0.006 * s, 0.006), 'dgrey')
    K.knob(S, P(0, col_bot + 0.03 * s, 0.05), N, U, r=0.012, h=0.012)

    # -- left console: throttle; right console: the side stick on its housing, armrest behind
    tx, tz = E[0] + 0.76 * t.W, E[2] + 0.18 * s
    K.throttle_f16(S, tx, tz)
    K.console_panels(S, 1, ['trim', 'toggles', 'toggles', 'radio', 'rotary', 'toggles', 'breakers'],
                     skip=[(tz - 0.16 * s, tz + 0.16 * s)], pal_name='nato')
    foot = np.array([E[0] - 0.72 * t.W, t.console_y + 0.03 * s, E[2] + 0.30 * s])
    K.stick_side(S, foot, cant=10, grip_tilt=10)
    K.console_panels(S, -1, ['toggles', 'rotary', 'toggles', 'rotary', 'oxygen', 'toggles'],
                     skip=[(foot[2] - 0.10 * s, foot[2] + 0.10 * s), (foot[2] - 0.30 * s, foot[2] - 0.10 * s)],
                     pal_name='nato')

    # -- seat and pedals
    g = S.ac.glass_above(E[0], E[2] - 0.35 * s, E[1])
    K.seat_aces2(S, recline=30, headbox_top=(g - 0.03) if g is not None else None)
    K.pedals(S, raise_=0.16)
