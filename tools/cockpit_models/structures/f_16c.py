"""F-16C Block 50 cockpit structure and instruments.

From the reference photos (cockpit-refs/f_16c: NMUSAF F-16A, Twenthe F-16AM and the DCS Viper guide pages), fitted
to the model's measured space: the windscreen of this model is low and steep (at the panel plane the glass is only
4 cm above the eye on the centre line and below the eye 15 cm out), and the canopy is narrow at the panel (the
glass line falls below the glareshield top 30 cm out from the centre). So:

  * the glareshield is flat and the HUD short, its combiner leaning back under the windscreen at the pilot's end
    of the body; the eyebrow lamp blocks either side of the HUD;
  * the ICP (integrated control panel) under the HUD, tilted up toward the pilot, the DED window to its right;
  * the centre column: a panel 5 cm proud of the rest carrying the flight instruments in three rows (AOA / ADI /
    VVI, airspeed / HSI / altimeter, the fuel quantity indicator), down past the console level;
  * the two MFDs on panels turned 22 degrees toward the pilot either side of the column, the knee panels below
    them (ECM on the left, hydraulics / EPU fuel / clock on the right, where the model has no room for them on
    the right facet);
  * the auxiliary facets wrapping round at 45 degrees to the walls, their tops sloping down to the canopy line:
    the MISC panel over the landing gear panel on the left, the engine instrument column on the right;
  * the left console with the throttle on its track, the right console with the side stick at its front on a
    raised housing and the armrest behind it, the caution panel at its back;
  * the ACES II seat reclined 30 degrees, rudder pedals high in the footwell.
"""
import math
import numpy as np
import structure as K
import instruments as I

X, Y, Z = K.X, K.Y, K.Z

COLOURS = dict(panel=(56, 58, 60), shade=(26, 27, 28), wall=(84, 86, 88), console=(50, 52, 54), coaming=(18, 18, 19),
               floor=(50, 52, 54), frame=(118, 120, 116), cushion=(36, 38, 36))

LEAN = 10                      # the panel's top leans away from the pilot
MFD_TURN = 22                  # the MFD panels toward the pilot
AUX_TURN = 45                  # the outer facets toward the pilot
AUX_DROP = 0.17                # the outer facets' outer top corners sit this far (in s) under the glareshield top:
                               # the canopy line is there (measured: cover at E-0.27 at x 0.34, z Zp+0.1)
HUD_LEAN = 14                  # the combiner leans back under the low windscreen


def build(S):
    t, E, s = S.t, S.E, S.s
    R = -X
    U, N = K.rot_x(LEAN)[0], None
    N = np.array([0, math.sin(math.radians(LEAN)), -math.cos(math.radians(LEAN))])
    top = t.top_c
    col_bot = t.bot_w - 0.05 * s                        # the centre column's pedestal reaches below the console level
    Zp = t.Z
    cosl = math.cos(math.radians(LEAN))

    def P(a, y, proud=0.0):
        """point on the panel's base plane: a metres to the pilot's right of the centre line, y height"""
        return np.array([E[0], top, Zp]) + R * a + U * ((y - top) / cosl) + N * proud

    # -- centre column (proud) and the ICP housing over it, the DED to the right
    col_w = 0.25 * s                                    # three dials side by side: the ADI between the AOA and VVI
    col_top = top - 0.14 * s                            # under the ICP housing
    S.plate('centre', P(0, (col_top + col_bot) / 2, 0.05), R, U, col_w, (col_top - col_bot) / cosl,
            depth=0.08, label='centre column: flight instruments')
    # the ICP housing: a block tilted up 32 degrees so the keypad faces the pilot's eyes, under the HUD body
    icp_up = K.unit(np.array([0, math.cos(math.radians(32 + LEAN)), math.sin(math.radians(32 + LEAN))]))
    icp_n = K.unit(np.cross(R, icp_up))
    icp_c = P(0, top - 0.075 * s, 0.03)
    S.box(icp_c - icp_n * 0.035, (R, icp_up, icp_n), (0.095 * s, 0.058 * s, 0.035), 'panel')
    S.plate('icp', icp_c + icp_n * 0.001, R, icp_up, 0.16 * s, 0.10 * s, depth=0.004, colour='black',
            edge='panel', label='ICP keypad')
    ded_c = P(0.225 * s, top - 0.035 * s, 0.028)       # proud of the turned strip beside it
    S.box(ded_c - N * 0.030, (R, U, N), (0.06 * s, 0.03 * s, 0.035), 'panel')
    S.plate('ded', ded_c + N * 0.006, R, U, 0.105 * s, 0.045 * s, depth=0.012, colour='black', edge='panel',
            label='DED window')

    # -- the MFD panels and the knee panels under them
    w_m = math.radians(MFD_TURN)
    x_in = col_w / 2
    mfd_w, mfd_h = 0.18 * s, 0.18 * s
    mfd_top = top - 0.115 * s                            # under the RWR / DED strips
    low_bot = t.bot_w
    x_out = x_in + mfd_w * math.cos(w_m)
    mfd_proud_out = -0.03 + mfd_w * math.sin(w_m)
    aux_w = 0.11 * s
    w_a = math.radians(AUX_TURN)
    aux_out = x_out + aux_w * math.cos(w_a)
    aux_proud_out = mfd_proud_out + aux_w * math.sin(w_a)
    aux_top = top - AUX_DROP * s                         # the facets' outer top corners, on the canopy line
    for side, tag in ((1, 'r'), (-1, 'l')):
        Rm = K.unit(R * math.cos(w_m) + side * N * math.sin(w_m))
        inner = P(side * x_in, mfd_top - mfd_h / 2, -0.03)
        S.plate('mfd_' + tag, inner + side * Rm * mfd_w / 2, Rm, U, mfd_w, mfd_h, depth=0.03, colour='shade',
                edge='panel', label=('right' if side > 0 else 'left') + ' MFD')
        lo_h = (mfd_top - mfd_h - low_bot) / cosl
        inner_lo = P(side * x_in, low_bot + (mfd_top - mfd_h - low_bot) / 2, -0.06)
        S.plate('under_' + tag, inner_lo + side * Rm * mfd_w / 2, Rm, U, mfd_w, lo_h, depth=0.02, colour='shade',
                label=('hydraulics, EPU fuel, clock' if side > 0 else 'ECM panel'))
        # the strip above the MFD, under the glareshield: the RWR scope and threat-warning prime block on the
        # left, the fuel flow indicator beside the DED on the right
        up_c = P(side * x_in, (top - 0.012 + mfd_top + 0.012 * s) / 2, -0.03)
        S.plate('above_' + tag, up_c + side * Rm * mfd_w / 2, Rm, U, mfd_w,
                (top - 0.012 - mfd_top - 0.012 * s) / cosl, depth=0.03,
                label='fuel flow, DED switches' if side > 0 else 'RWR scope, threat warning prime')
        # the auxiliary facet at 45 degrees: a rectangle under the sloping top, the wedge above it plain panel
        Ra = K.unit(R * math.cos(w_a) + side * N * math.sin(w_a))
        if side < 0:
            tops = [('misc_l', 'MISC panel (master arm, laser arm, alt release)', aux_top, aux_top - 0.085 * s),
                    ('gear', 'landing gear panel', aux_top - 0.085 * s, low_bot)]
        else:
            tops = [('eng', 'engine instruments column', aux_top, low_bot)]
        for name, label, y1, y0 in tops:
            yc = (y0 + y1) / 2
            c = P(side * x_out, yc, mfd_proud_out) + side * Ra * aux_w / 2
            S.plate(name, c, Ra, U, aux_w, (y1 - y0) / cosl, depth=0.03, label=label)
        wedge = [P(side * x_out, aux_top, mfd_proud_out), P(side * aux_out, aux_top, aux_proud_out),
                 P(side * x_out, top, mfd_proud_out)]
        S.m.flat(wedge, S.c['panel'], normal=K.unit(np.cross(Ra, U)))
        # closing the gap between the facet's outer edge and the wall, down to the sill
        po = P(side * aux_out, aux_top, aux_proud_out)
        pb = P(side * aux_out, low_bot, aux_proud_out)
        S.m.flat([po, pb, [E[0] - side * t.W, pb[1], pb[2]], [E[0] - side * t.W, t.sill - 0.01, po[2]]],
                 S.c['panel'], normal=N)

    # -- the glareshield: the lip follows the panel top, wrapping forward with the facets and falling with them
    lip = []
    for a, y, proud in ((-aux_out, aux_top, aux_proud_out), (-x_out, top, mfd_proud_out), (-x_in, top, -0.03),
                        (-x_in, top, 0.0), (x_in, top, 0.0), (x_in, top, -0.03), (x_out, top, mfd_proud_out),
                        (aux_out, aux_top, aux_proud_out)):
        lip.append((P(a, y, proud), N))
    for a_end in (-1, 1):                                # on to the wall at the sill
        pt = (P(a_end * t.W, t.sill - 0.012, aux_proud_out), N)
        lip = [pt] + lip if a_end < 0 else lip + [pt]
    K.coaming(S, lip, crown=0.0, flat=True, front_hw=t.W * 0.92, lip_height=0.02, lip_depth=0.03)

    # -- HUD: a short body on the glareshield pitched down toward the windscreen base, the combiner leaning back
    # on posts at its pilot's end, under the low windscreen; eyebrow lamp blocks either side
    body_z0, body_z1 = Zp - 0.15, Zp + 0.20 * s
    body_y0, body_y1 = top + 0.022, top + 0.067 * s      # its lower edge clears the sight line to the ICP's top row
    b_up, b_fwd = K.rot_x(-8)                            # top toward the pilot: the far end lower
    b_c = np.array([E[0], (body_y0 + body_y1) / 2, body_z0]) + b_fwd * (body_z1 - body_z0) / 2
    S.box(b_c, (X, b_up, b_fwd), (0.10 * s, (body_y1 - body_y0) / 2, (body_z1 - body_z0) / 2), 'coaming')
    lens = b_c + b_fwd * 0.12 * s + b_up * (body_y1 - body_y0) / 2
    S.m.cyl(lens + b_up * 0.0005, b_up, b_fwd, 0.03 * s, 0.004, S.c['black'], cap=S.c['black'])
    g_up, _ = K.rot_x(HUD_LEAN)
    g_h, g_hw = 0.15 * s, 0.072 * s
    g_bot = np.array([E[0], body_y1 + 0.010, body_z0 + 0.03])
    post_half = (0.012 * s, 0.016 * s)
    K.combiner(S, g_bot, g_up, g_h, g_hw, frame_colour='dgrey', post_half=post_half)
    S.ctx['hud'] = dict(g_bot=g_bot, g_up=g_up, g_n=K.unit(np.cross(X, g_up)), g_h=g_h, g_hw=g_hw, post_half=post_half)
    for sg in (1, -1):                                                        # the posts' feet on the body
        S.box([E[0] + sg * (g_hw + 0.012 * s), body_y1 + 0.005, g_bot[2]], (X, Y, Z), (0.016 * s, 0.005, 0.03 * s),
              'dgrey')
    eb_up = K.unit([0, math.cos(math.radians(20)), math.sin(math.radians(20))])
    for sg, name, label, w, h in ((1, 'eyebrow_l', 'left eyebrow lamps (TF fail, master caution)', 0.075, 0.032),
                                  (-1, 'eyebrow_r', 'right eyebrow warning lamps (eng fire, engine, hyd/oil, FLCS...)', 0.11, 0.040)):
        c = np.array([E[0] + sg * (0.13 * s + w * s / 2), top + 0.012 + h * s / 2 + 0.002, Zp - 0.005])
        K.lamp_block(S, name, c, R, eb_up, w * s, h * s, depth=0.035, label=label)

    # -- the tub: consoles meet the panels along the knee panels' bottom edge
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

    # -- left console: throttle; right console: the side stick on its housing, armrest behind, caution panel aft
    tx, tz = E[0] + 0.76 * t.W, E[2] + 0.18 * s
    K.throttle_f16(S, tx, tz)
    K.console_panels(S, 1, ['trim', 'toggles', 'toggles', 'radio', 'rotary', 'toggles', 'breakers'],
                     skip=[(tz - 0.16 * s, tz + 0.16 * s)], pal_name='nato')
    foot = np.array([E[0] - 0.72 * t.W, t.console_y + 0.03 * s, E[2] + 0.30 * s])
    K.stick_side(S, foot, cant=10, grip_tilt=10)
    K.console_panels(S, -1, ['toggles', 'rotary', 'toggles', 'rotary', 'oxygen', 'caution'],
                     skip=[(foot[2] - 0.10 * s, foot[2] + 0.10 * s), (foot[2] - 0.30 * s, foot[2] - 0.10 * s)],
                     pal_name='nato')

    # -- seat and pedals
    g = S.ac.glass_above(E[0], E[2] - 0.35 * s, E[1])
    K.seat_aces2(S, recline=30, headbox_top=(g - 0.03) if g is not None else None)
    K.pedals(S, raise_=0.16)


def instruments(S):
    """Stage 2: the instruments on the plates. Sizes are real ones at the tub's scale (3-inch dials .076, 2-inch
    .057, the 4-inch MFD screens drawn a little over life size so they read in game), packed to each plate."""
    s, E, pl = S.s, S.E, S.plates
    RIM = I.L.RIM
    P = pl['centre']
    # -- the centre column: AOA / ADI / VVI, airspeed / HSI / altimeter, the fuel quantity indicator and its
    # selector knob at the foot
    hh = P.h / 2
    y1 = hh - 0.010 - 0.085 * s * (1 + RIM) / 2
    I.dial_row(S, P, y1, [('AOA', 0.046 * s), ('ATTITUDE', 0.085 * s), ('VVI', 0.046 * s)])
    y2 = y1 - 0.085 * s * (1 + RIM) / 2 - 0.009 - 0.078 * s * (1 + RIM) / 2
    I.dial_row(S, P, y2, [('AIRSPEED', 0.058 * s), ('HEADING', 0.078 * s), ('ALTIMETER', 0.058 * s)])
    y3 = y2 - 0.078 * s * (1 + RIM) / 2 - 0.009 - 0.066 * s * (1 + RIM) / 2
    I.dial(S, P, 0.022 * s, y3, 'FUEL_QTY', 0.066 * s)
    I.knob(S, P, -0.062 * s, y3 + 0.008 * s, r=0.011, h=0.013)
    I.placard(S, P, -0.062 * s, y3 - 0.024 * s, 0.050 * s, 0.016 * s, 'FUEL QTY|SEL')
    I.placard(S, P, -0.062 * s, y3 + 0.030 * s, 0.050 * s, 0.009 * s, 'NORM  TEST', px=0.0055)
    y4 = y3 - 0.066 * s * (1 + RIM) / 2 - 0.016 * s
    if y4 - 0.012 * s > -hh:
        I.switch_row(S, P, -0.09 * s, 0.09 * s, y4 - 0.004 * s, ['TANK|INERT', 'ENG|FEED', 'AIR|REFUEL'],
                     tilts=[-1, 1, -1])

    # -- the ICP keypad under the HUD, the DED window to its right
    icp = pl['icp']
    keys = [(0, 0, 'COM|1', None), (0, 1, 'COM|2', None), (0, 2, 'IFF', None), (0, 3, 'LIST', None),
            (1, 0, '1|T-ILS', None), (2, 0, '2|ALOW', None), (3, 0, '3', None),
            (1, 1, '4|STPT', None), (2, 1, '5|CRUS', None), (3, 1, '6|TIME', None),
            (1, 2, '7|MARK', None), (2, 2, '8|FIX', None), (3, 2, '9|A-CAL', None),
            (1, 3, 'A-A', (40, 70, 44)), (2, 3, '0|M-SEL', None), (3, 3, 'A-G', (40, 70, 44)),
            (4, 0, 'RCL', None), (4, 1, 'ENTR', None), (4, 2, 'F-ACK', None), (4, 3, 'WX', None)]

    def icp_extra(d, im, W, H):
        # the DCS rocker and the knob legends painted on the back plate
        cx, cy, r = W * (5.5 / 6), H * 0.50, min(W / 6, H / 4) * 0.42
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(60, 62, 64, 255), outline=(20, 20, 20, 255), width=3)
        for lab, dx, dy in (('RTN', -1, 0), ('SEQ', 1, 0), ('UP', 0, -1), ('DN', 0, 1)):
            I.text(d, (cx + dx * r * 0.62, cy + dy * r * 0.62), lab, r * 0.33, bold=True)
        I.text(d, (W * (5.5 / 6), H * 0.27), 'SYM', H * 0.07)
        I.text(d, (W * (5.5 / 6), H * 0.67), 'BRT', H * 0.07)
    I.keypad(S, icp, 0, 0, icp.w, icp.h, keys, 6, 4, icp.w / 6 * 0.86, icp.h / 4 * 0.80, extra=icp_extra)
    I.knob(S, icp, icp.w * (5.5 / 6 - 0.5), icp.h * (0.5 - 0.13), r=0.006, h=0.010)             # SYM
    I.knob(S, icp, icp.w * (5.5 / 6 - 0.5), icp.h * (0.5 - 0.82), r=0.006, h=0.010)             # BRT
    ded = pl['ded']
    I.ded(S, ded, 0, 0, ded.w, ded.h, ['UHF  305.00      STPT  1', 'VHF  127.50      09:42:17',
                                        'M1 3  M3 1234         T 0', 'C  6324    M4 A'])

    # -- the MFDs, each with its twenty option select keys and the corner rockers
    for tag, kind in (('l', 'RADAR'), ('r', 'STORES')):
        p = pl['mfd_' + tag]
        I.display(S, p, 0, 0, kind, 0.118 * s, 'LCD', osb=5)
        hw = 0.118 * s * (1 + 2 * I.L.DISPLAY_BEZEL) / 2 + 0.006 * s
        for sa in (-1, 1):
            for sy in (-1, 1):
                I.button(S, p, sa * hw, sy * hw, w=0.009, h=0.009, colour='dgrey', proud=0.004)
    # the knee panels: ECM controls on the left; hydraulics, EPU fuel and the clock on the right
    u = pl['under_l']
    I.placard(S, u, 0, u.h / 2 - 0.010 * s, 0.040 * s, 0.010 * s, 'ECM')
    I.switch_row(S, u, -0.07 * s, 0.07 * s, u.h / 2 - 0.045 * s, ['PWR', 'XMIT|STBY', 'CHAFF|FLARE'], tilts=[1, -1, 1])
    I.knob(S, u, -0.035 * s, -u.h / 2 + 0.030 * s, r=0.010, h=0.012)
    I.knob(S, u, 0.035 * s, -u.h / 2 + 0.030 * s, r=0.010, h=0.012)
    I.placard(S, u, -0.035 * s, -u.h / 2 + 0.010 * s, 0.030 * s, 0.008 * s, 'MODE')
    I.placard(S, u, 0.035 * s, -u.h / 2 + 0.010 * s, 0.030 * s, 0.008 * s, 'BRT')
    u = pl['under_r']
    d4 = 0.040 * s
    ya = u.h / 2 - 0.009 * s - d4 * (1 + RIM) / 2
    I.dial(S, u, -0.055 * s, ya, 'FILLER_HYDRAULIC', d4)
    I.dial(S, u, -0.006 * s, ya, 'FILLER_HYDRAULIC', d4)
    I.dial(S, u, 0.045 * s, ya, 'FILLER_FUEL', d4)
    yb = ya - d4 * (1 + RIM) / 2 - 0.004 * s
    I.placard(S, u, -0.055 * s, yb, 0.030 * s, 0.007 * s, 'HYD A', px=0.0045)
    I.placard(S, u, -0.006 * s, yb, 0.030 * s, 0.007 * s, 'HYD B', px=0.0045)
    I.placard(S, u, 0.045 * s, yb, 0.030 * s, 0.007 * s, 'EPU FUEL', px=0.0045)
    yc = -u.h / 2 + 0.008 * s + 0.046 * s * (1 + RIM) / 2
    I.dial(S, u, 0.040 * s, yc, 'CLOCK', 0.046 * s)
    I.switch_row(S, u, -0.075 * s, -0.005 * s, yc - 0.006 * s, ['EPU', 'CABIN|PRESS'], tilts=[-1, 1])

    # -- over the MFDs: the RWR azimuth scope and the threat warning prime block on the left; the fuel flow
    # indicator beside the DED housing and its switches under it on the right
    p = pl['above_l']
    I.dial(S, p, -p.w / 2 + 0.040 * s, -0.004 * s, 'RWR', 0.064 * s)
    I.annunciator(S, p, 0.040 * s, -0.002 * s, 0.080 * s, 0.060 * s,
                  [('HAND|OFF', 'NONE', 'green'), ('LAUNCH', 'MISSILE_LAUNCH', 'red'), ('PRI', 'RWR_LOCK', 'amber'),
                   ('NAVAL', 'NONE', 'green'), ('UNK', 'NONE', 'amber'), ('TGT|SEP', 'NONE', 'green')], 3)
    I.placard(S, p, 0.040 * s, p.h / 2 - 0.007 * s, 0.040 * s, 0.008 * s, 'THREAT')
    p = pl['above_r']
    I.dial(S, p, -p.w / 2 + 0.028 * s, -0.012 * s, 'FILLER_FUEL', 0.044 * s)
    I.switch_row(S, p, -0.034 * s, 0.040 * s, -0.030 * s, ['', '', ''], tilts=[1, 1, -1])
    I.knob(S, p, 0.060 * s, -0.026 * s, r=0.008, h=0.010)
    I.placard(S, p, 0.060 * s, -0.041 * s, 0.026 * s, 0.007 * s, 'DED BRT', px=0.0045)

    # -- the auxiliary facets: MISC and landing gear panels on the left, the engine instrument column on the right
    p = pl['misc_l']
    I.placard(S, p, 0, p.h / 2 - 0.008 * s, 0.040 * s, 0.009 * s, 'MISC')
    I.toggle(S, p, -0.034 * s, -0.004 * s, tilt=1)
    I.guard(S, p, -0.034 * s, -0.004 * s)
    I.placard(S, p, -0.034 * s, 0.020 * s, 0.034 * s, 0.015 * s, 'MASTER|ARM')
    I.toggle(S, p, 0.006 * s, -0.004 * s, tilt=-1)
    I.placard(S, p, 0.006 * s, 0.020 * s, 0.032 * s, 0.015 * s, 'LASER|ARM')
    I.button(S, p, 0.040 * s, -0.004 * s, w=0.014, h=0.014, colour='dgrey', proud=0.006, cap='red')
    I.placard(S, p, 0.040 * s, 0.020 * s, 0.028 * s, 0.015 * s, 'ALT|REL')
    I.placard(S, p, 0.006 * s, -0.030 * s, 0.070 * s, 0.009 * s, 'ARM  OFF  SIM', px=0.005)
    p = pl['gear']
    I.placard(S, p, 0, p.h / 2 - 0.008 * s, 0.070 * s, 0.009 * s, 'LANDING GEAR')
    I.gear_handle(S, p, -0.036 * s, p.h / 2 - 0.058 * s, size=0.85)
    I.annunciator(S, p, 0.028 * s, p.h / 2 - 0.030 * s, 0.050 * s, 0.024 * s,
                  [('NOSE', 'GEAR_DOWN', 'green'), ('LEFT', 'GEAR_DOWN', 'green'), ('RIGHT', 'GEAR_DOWN', 'green')], 3)
    I.annunciator(S, p, 0.028 * s, p.h / 2 - 0.056 * s, 0.024 * s, 0.015 * s, [('WARN', 'GEAR_TRANSIT', 'red')], 1)
    I.switch_row(S, p, -0.054 * s, 0.054 * s, p.h / 2 - 0.105 * s, ['HOOK', 'LDG|LIGHTS', 'HORN|SIL'],
                 tilts=[-1, 1, -1])
    I.switch_row(S, p, -0.054 * s, 0.054 * s, p.h / 2 - 0.150 * s, ['PARK|BRAKE', 'STORES|CONFIG', 'ANTI|SKID'],
                 tilts=[1, -1, 1])
    p = pl['eng']
    y = p.h / 2 - 0.010
    for kind, d, lab in (('FILLER_OIL', 0.040, 'OIL'), ('FILLER_HYDRAULIC', 0.040, 'NOZ|POS'),
                         ('ENGINE_RPM', 0.050, 'RPM'), ('EGT', 0.050, 'FTIT')):
        fp = d * s * (1 + RIM)
        y -= fp / 2
        I.dial(S, p, -0.014 * s, y, kind, d * s)
        I.placard(S, p, 0.040 * s, y, 0.024 * s, (0.015 if '|' in lab else 0.008) * s, lab)
        y -= fp / 2 + 0.008
    if y - 0.012 * s > -p.h / 2:
        I.placard(S, p, 0, y - 0.006 * s, 0.060 * s, 0.009 * s, 'ENGINE', px=0.0055)

    # -- the eyebrow blocks: TF FAIL and MASTER CAUTION on the left, the warning lights on the right
    p = pl['eyebrow_l']
    I.annunciator(S, p, 0, 0, p.w - 0.004, p.h - 0.004,
                  [('TF|FAIL', 'NONE', 'red'), ('MASTER|CAUTION', 'MASTER_CAUTION', 'amber')], 2)
    p = pl['eyebrow_r']
    I.annunciator(S, p, 0, 0, p.w - 0.004, p.h - 0.004,
                  [('ENG|FIRE', 'FIRE', 'red'), ('ENGINE', 'ENGINE_OUT', 'red'), ('HYD/OIL|PRESS', 'NONE', 'amber'),
                   ('FLCS', 'DAMAGE', 'amber'), ('DBU|ON', 'NONE', 'amber'), ('TO/LDG|CONFIG', 'NONE', 'amber'),
                   ('CANOPY', 'NONE', 'amber'), ('OXY|LOW', 'NONE', 'amber')], 4)

    # -- the AOA indexer on the left HUD post, facing the pilot
    h = S.ctx['hud']
    c = h['g_bot'] + h['g_up'] * h['g_h'] / 2 + X * (h['g_hw'] + h['post_half'][0]) - h['g_n'] * h['post_half'][1]
    post = K.Plate('aoa_indexer', c, -X, h['g_up'], 2 * h['post_half'][0], h['g_h'], 'AOA indexer on the HUD post')
    S.plates['aoa_indexer'] = post
    I.annunciator(S, post, 0, h['g_h'] * 0.12, 0.018 * s, 0.054 * s,
                  [('^', 'NONE', 'amber'), ('O', 'NONE', 'green'), ('v', 'NONE', 'red')], 1, depth=0.008)


# Review views matching the reference photos (cockpit-refs/f_16c): (photo, caption, eye offset from the pilot's
# eye, yaw, pitch, fov). yaw positive to the pilot's left.
REF_VIEWS = [
    ('f_16c_03.jpg', "pilot's view of the panel (NMUSAF F-16A)", (0.0, 0.0, -0.06), 0.0, -24.0, 74.0),
    ('f_16c_01.jpg', 'from the left shoulder (Asian Aerospace F-16)', (0.45, 0.42, -0.25), -22.0, -30.0, 66.0),
    ('f_16c_06.jpg', 'from above and behind (Twenthe F-16AM)', (0.0, 1.25, -0.95), 0.0, -56.0, 58.0),
    ('f_16c_05.jpg', 'right console and side stick (F-16D)', (0.55, 0.55, -0.35), -48.0, -36.0, 62.0),
    ('f_16c_07.jpg', 'over the left sill (Twenthe F-16AM)', (1.05, 0.55, -0.5), -62.0, -30.0, 70.0),
]
