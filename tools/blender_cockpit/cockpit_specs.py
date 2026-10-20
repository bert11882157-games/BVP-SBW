"""Cockpit structure specs for the fleet generator (cockpit_build.py).

Every aircraft starts from its era's layout (the panel outline, the instruments on it, the sight, the seat), then its
own entry in AIRCRAFT overrides what differs. Outlines and positions are in metres at full size: r right of the centre
line, du down from the glareshield lip; the generator scales the whole layout uniformly so it fits the model's
cockpit (as the F-16C was fitted to its traced drawing).

Instrument items:
  ('mfd', name, r, du, size)                square display bezel with option keys
  ('display', name, r, du, w, h)             a plain rectangular display or scope bezel
  ('gauge', name, r, du, radius)             a round instrument
  ('scope', name, r, du, radius)             a round radar / sight scope with a hood
  ('plate', name, r, du, w, h)               a flush switch / lamp plate
  ('ufcp', name, r, du, w, h)                an up-front control panel standing proud under the HUD
"""

# -- panel outlines (closed, clockwise from the top left; du is down from the glareshield lip)
OUTLINES = {
    # twin shoulders falling to the sides, centre column running down between the knees (F-16-like glass panels)
    'modern': [(-0.250, 0.0), (0.250, 0.0), (0.355, -0.110), (0.360, -0.330), (0.115, -0.330), (0.110, -0.470),
               (-0.110, -0.470), (-0.115, -0.330), (-0.360, -0.330), (-0.355, -0.110)],
    # the 1980s-90s CRT panels: wider top, deeper centre
    'gen4': [(-0.270, 0.0), (0.270, 0.0), (0.350, -0.090), (0.355, -0.320), (0.130, -0.340), (0.125, -0.460),
             (-0.125, -0.460), (-0.130, -0.340), (-0.355, -0.320), (-0.350, -0.090)],
    # Cold War radar fighters: a trapezoid with the scope at the top centre and a short centre extension
    'radar': [(-0.270, 0.0), (0.270, 0.0), (0.330, -0.070), (0.330, -0.300), (0.120, -0.320), (0.110, -0.420),
              (-0.110, -0.420), (-0.120, -0.320), (-0.330, -0.300), (-0.330, -0.070)],
    # early jets: a broad panel with rounded upper corners, gauges in rows
    'steam': [(-0.250, 0.0), (0.250, 0.0), (0.300, -0.040), (0.310, -0.300), (0.100, -0.330), (0.090, -0.400),
              (-0.090, -0.400), (-0.100, -0.330), (-0.310, -0.300), (-0.300, -0.040)],
    # WWII: a flat, nearly upright board right across the cockpit
    'wwii': [(-0.230, 0.0), (0.230, 0.0), (0.270, -0.040), (0.270, -0.250), (0.080, -0.270), (0.070, -0.330),
             (-0.070, -0.330), (-0.080, -0.270), (-0.270, -0.250), (-0.270, -0.040)],
}

GAUGE_ROW = lambda name, du, rs, rad: [('gauge', f'{name}_{i}', r, du, rad) for i, r in enumerate(rs)]

LAYOUTS = {
    'modern': [('mfd', 'mfd_l', -0.200, -0.180, 0.160), ('mfd', 'mfd_r', 0.200, -0.180, 0.160),
               ('ufcp', 'ufcp', 0.0, -0.065, 0.150, 0.100), ('display', 'ded', 0.0, -0.250, 0.110, 0.050),
               ('gauge', 'standby_adi', -0.045, -0.320, 0.032), ('gauge', 'standby_alt', 0.045, -0.320, 0.032),
               ('plate', 'left_plate', -0.315, -0.200, 0.040, 0.160), ('plate', 'right_plate', 0.315, -0.200, 0.040, 0.160)],
    'modern3': [('mfd', 'mfd_l', -0.215, -0.160, 0.150), ('mfd', 'mfd_r', 0.215, -0.160, 0.150),
                ('mfd', 'mfd_c', 0.0, -0.300, 0.150), ('ufcp', 'ufcp', 0.0, -0.075, 0.150, 0.110),
                ('plate', 'left_plate', -0.320, -0.200, 0.030, 0.160), ('plate', 'right_plate', 0.320, -0.200, 0.030, 0.160)],
    'wide': [('display', 'wide_l', -0.170, -0.190, 0.230, 0.180), ('display', 'wide_r', 0.170, -0.190, 0.230, 0.180),
             ('ufcp', 'ufcp', 0.0, -0.060, 0.160, 0.070), ('display', 'lower', 0.0, -0.360, 0.120, 0.080)],
    'gen4': [('mfd', 'mfd_l', -0.205, -0.140, 0.140), ('mfd', 'mfd_r', 0.205, -0.140, 0.140),
             ('mfd', 'mfd_c', 0.0, -0.300, 0.150), ('ufcp', 'ufcp', 0.0, -0.065, 0.150, 0.090)]
            + GAUGE_ROW('eng', -0.270, (0.255, 0.315), 0.028) + GAUGE_ROW('fl', -0.270, (-0.255, -0.315), 0.028),
    'radar': [('scope', 'radar', 0.0, -0.095, 0.075), ('gauge', 'adi', 0.0, -0.250, 0.050),
              ('gauge', 'asi', -0.120, -0.205, 0.038), ('gauge', 'alt', 0.120, -0.205, 0.038),
              ('gauge', 'hsi', 0.0, -0.370, 0.045), ('gauge', 'vvi', 0.120, -0.300, 0.032),
              ('gauge', 'aoa', -0.120, -0.300, 0.032)]
             + GAUGE_ROW('eng', -0.110, (0.205, 0.275), 0.030) + GAUGE_ROW('eng2', -0.190, (0.205, 0.275), 0.030)
             + [('plate', 'arm', -0.245, -0.150, 0.110, 0.140), ('plate', 'warn', 0.240, -0.265, 0.110, 0.060)],
    'steam': GAUGE_ROW('row1', -0.085, (-0.140, -0.050, 0.050, 0.140), 0.038)
             + GAUGE_ROW('row2', -0.185, (-0.140, -0.050, 0.050, 0.140), 0.038)
             + GAUGE_ROW('eng', -0.100, (0.230,), 0.030) + GAUGE_ROW('eng2', -0.180, (0.230,), 0.030)
             + [('gauge', 'compass', 0.0, -0.290, 0.035), ('plate', 'arm', -0.235, -0.150, 0.090, 0.160),
                ('plate', 'fuel', 0.230, -0.255, 0.070, 0.050)],
    'wwii': GAUGE_ROW('row1', -0.070, (-0.150, -0.060, 0.060, 0.150), 0.040)
            + GAUGE_ROW('row2', -0.165, (-0.150, -0.060, 0.060, 0.150), 0.040)
            + [('gauge', 'compass', 0.0, -0.250, 0.035), ('gauge', 'eng1', 0.215, -0.120, 0.030),
               ('gauge', 'eng2', -0.215, -0.120, 0.030), ('plate', 'mag', -0.200, -0.215, 0.060, 0.050),
               ('plate', 'fuel', 0.200, -0.215, 0.060, 0.050)],
}

ERAS = {
    'MODERN_GLASS': dict(outline='modern', layout='modern3', sight='hud', seat='ejection', recline=17, lean=15,
                         column=True),
    'GEN4_CRT': dict(outline='gen4', layout='gen4', sight='hud', seat='ejection', recline=15, lean=15, column=True),
    'COLD_WAR_RADAR': dict(outline='radar', layout='radar', sight='gunsight', seat='ejection', recline=14, lean=12,
                           column=True),
    'COLD_WAR_STEAM': dict(outline='steam', layout='steam', sight='gunsight', seat='ejection_early', recline=12,
                           lean=10, column=True),
    'WWII_STEAM': dict(outline='wwii', layout='wwii', sight='reflector', seat='bucket', recline=8, lean=4,
                       column=False),
}

# ---------------------------------------------------------------- traced from same-variant references
# (positions measured on a 5 cm grid laid over each figure; the references stay in work\cockpit_diagrams, off the repo)

# A-10A: DCS A-10A Flight Manual, figure 40 "A-10A Cockpit" (front-on). Fire handles across the top, RWR scope upper
# left of centre with its control panel beside it, ADI over HSI on the centre line, airspeed / AoA / clock / UHF
# readout to the left, VVI over the altimeter to the right, the TV monitor upper right, the 4 x 3 engine gauge block
# under it, fuel quantity far right, the armament control panel lower left, the gear handle outboard left.
OUTLINES['a10'] = [(-0.385, 0.0), (0.385, 0.0), (0.385, -0.325), (0.125, -0.330), (0.110, -0.420), (-0.110, -0.420),
                   (-0.125, -0.330), (-0.385, -0.325)]
LAYOUTS['a10'] = (
    [('plate', 'fire_handles', -0.015, -0.022, 0.640, 0.040), ('scope', 'rwr', -0.142, -0.073, 0.040),
     ('plate', 'rwr_control', 0.0, -0.068, 0.120, 0.050), ('gauge', 'vvi', 0.110, -0.100, 0.040),
     ('gauge', 'adi', 0.0, -0.165, 0.065), ('gauge', 'alt', 0.110, -0.175, 0.037),
     ('gauge', 'asi', -0.115, -0.175, 0.037), ('gauge', 'aoa', -0.185, -0.175, 0.027),
     ('gauge', 'clock', -0.255, -0.175, 0.023), ('display', 'uhf', -0.315, -0.175, 0.060, 0.040),
     ('display', 'tv_monitor', 0.240, -0.150, 0.150, 0.120), ('gauge', 'hsi', 0.0, -0.275, 0.050),
     ('gauge', 'fuel_qty', 0.315, -0.270, 0.040), ('plate', 'acp', -0.170, -0.265, 0.200, 0.100),
     ('plate', 'gear_panel', -0.300, -0.300, 0.060, 0.050)]
    + [('gauge', f'eng_{a}_{b}', r, du, 0.021) for a, du in enumerate((-0.232, -0.272, -0.307))
       for b, r in enumerate((0.095, 0.140, 0.195, 0.245))])

# Su-25 (base): DCS Su-25 Flight Manual, figure 7 "Cockpit Controls". AoA/G and IAS upper left, ADI left of centre with
# the HSI under it, the VVI / clock pair on the centre line, Machmeter and tachometer right of centre with the two ITT
# gauges under them, the vertical fuel-quantity strip, the warning-light block upper right, the configuration
# indicator and the radar altimeter lower left; the ASP-17 sight on the coaming (sight='gunsight').
OUTLINES['su25'] = [(-0.300, 0.0), (0.300, 0.0), (0.360, -0.050), (0.360, -0.200), (0.150, -0.205), (0.120, -0.300),
                    (-0.120, -0.300), (-0.150, -0.205), (-0.360, -0.200), (-0.360, -0.050)]
LAYOUTS['su25'] = [
    ('gauge', 'aoa_g', -0.235, -0.040, 0.032), ('gauge', 'ias', -0.160, -0.040, 0.028),
    ('gauge', 'adi', -0.080, -0.053, 0.038), ('gauge', 'vvi', 0.010, -0.058, 0.030),
    ('gauge', 'mach', 0.073, -0.048, 0.030), ('display', 'fuel_strip', 0.122, -0.070, 0.035, 0.095),
    ('plate', 'warning_lights', 0.225, -0.020, 0.120, 0.080), ('gauge', 'clock', 0.008, -0.115, 0.026),
    ('gauge', 'radalt', -0.157, -0.095, 0.028), ('gauge', 'rpm', 0.070, -0.100, 0.032),
    ('display', 'config_ind', -0.210, -0.135, 0.050, 0.040), ('display', 'dist_ctr', -0.155, -0.145, 0.040, 0.020),
    ('gauge', 'hsi', -0.080, -0.135, 0.038), ('gauge', 'itt_l', 0.065, -0.150, 0.022),
    ('gauge', 'itt_r', 0.115, -0.150, 0.022), ('plate', 'gear_lever_panel', -0.265, -0.125, 0.050, 0.070)]

# AJS-37 Viggen: DCS AJS-37 manual, figure 4 "Front panel" (the flight manual's line drawing). The tall CI radar
# display at the top centre under the HUD, the big central course indicator under it, the attitude ball and the
# main altimeter on the left, airspeed and the radio panel outboard left, two columns of system gauges and the two
# large engine gauges on the right, the master-warning lamps by the attitude ball.
OUTLINES['viggen'] = [(-0.100, 0.0), (0.100, 0.0), (0.100, -0.100), (0.300, -0.140), (0.310, -0.350),
                      (0.100, -0.370), (0.090, -0.420), (-0.090, -0.420), (-0.100, -0.370), (-0.310, -0.350),
                      (-0.300, -0.140), (-0.100, -0.100)]
LAYOUTS['viggen'] = (
    [('display', 'ci_radar', 0.0, -0.080, 0.135, 0.130), ('gauge', 'central_ind', 0.0, -0.260, 0.080),
     ('gauge', 'adi', -0.160, -0.158, 0.045), ('gauge', 'alt', -0.155, -0.283, 0.050),
     ('gauge', 'asi', -0.262, -0.203, 0.035), ('plate', 'radio', -0.245, -0.295, 0.060, 0.090),
     ('gauge', 'accel', -0.115, -0.070, 0.022), ('plate', 'master_warning', -0.163, -0.093, 0.050, 0.020),
     ('gauge', 'clock', 0.110, -0.090, 0.027), ('gauge', 'eng_big_1', 0.246, -0.218, 0.035),
     ('gauge', 'eng_big_2', 0.246, -0.291, 0.035)]
    + [('gauge', f'sys_a_{i}', 0.107, du, 0.026) for i, du in enumerate((-0.186, -0.252, -0.311))]
    + [('gauge', f'sys_b_{i}', 0.169, du, 0.026) for i, du in enumerate((-0.135, -0.189, -0.252, -0.311))])

# F-15C: DCS Flaming Cliffs 3 manual, figure 4-1 "F-15C instrument panel". The UFC / HUD control panel at the top
# centre, the VSD radar display upper left, the TEWS scope upper right, the MPCD lower left with the armament panel
# under it, IAS / AoA / accelerometer down the left of the centre column, ADI over HSI on it, altimeter / VVI / clock
# down its right, the paired engine gauges (RPM, FTIT, fuel flow, nozzle) and fuel quantity to the right, the engine
# fire lights upper left.
OUTLINES['f15c'] = [(-0.120, 0.0), (0.120, 0.0), (0.300, -0.060), (0.385, -0.140), (0.385, -0.420), (0.120, -0.440),
                    (0.110, -0.530), (-0.110, -0.530), (-0.120, -0.440), (-0.385, -0.420), (-0.385, -0.140),
                    (-0.300, -0.060)]
LAYOUTS['f15c'] = (
    [('ufcp', 'ufc', 0.0, -0.094, 0.180, 0.170), ('display', 'vsd', -0.165, -0.107, 0.130, 0.150),
     ('scope', 'tews', 0.193, -0.130, 0.055), ('mfd', 'mpcd', -0.248, -0.280, 0.170),
     ('gauge', 'ias', -0.132, -0.250, 0.034), ('gauge', 'aoa', -0.121, -0.315, 0.026),
     ('gauge', 'accel', -0.118, -0.370, 0.024), ('gauge', 'adi', 0.0, -0.265, 0.050),
     ('gauge', 'hsi', 0.0, -0.364, 0.050), ('gauge', 'alt', 0.123, -0.250, 0.034),
     ('gauge', 'vvi', 0.113, -0.315, 0.028), ('gauge', 'clock', 0.110, -0.372, 0.024),
     ('gauge', 'fuel_qty', 0.270, -0.354, 0.035), ('plate', 'armament', -0.248, -0.375, 0.170, 0.060),
     ('gauge', 'fire_l', -0.340, -0.180, 0.020), ('gauge', 'fire_r', -0.280, -0.180, 0.020)]
    + [('gauge', f'eng_{n}_{s}', r, du, rad) for n, du, rs, rad in
       (('rpm', -0.240, (0.188, 0.240), 0.024), ('ftit', -0.289, (0.186, 0.238), 0.024),
        ('ff', -0.340, (0.170, 0.222), 0.022), ('noz', -0.385, (0.163, 0.212), 0.020))
       for s, r in enumerate(rs)]
    + [('gauge', f'hyd_{i}', r, -0.230, 0.017) for i, r in enumerate((0.285, 0.315, 0.345))])

# Su-27: DCS Su-27 Flight Manual, figure 24 "Su-27 instrument panel". Top row AoA/G, IAS/Mach, ADI, VVI, tachometer;
# second row altimeter, clock, HSI, the two ITT gauges; the mechanical devices indicator and the radar altimeter lower
# left; the vertical fuel strip, the Ekran panel, the warning-light block and the SPO-15 RWR on the right; the head-down
# tactical display at the upper right.
OUTLINES['su27'] = [(-0.340, 0.0), (0.360, 0.0), (0.380, -0.050), (0.380, -0.250), (0.200, -0.250), (0.120, -0.320),
                    (-0.120, -0.320), (-0.200, -0.250), (-0.370, -0.250), (-0.370, -0.050)]
LAYOUTS['su27'] = [
    ('gauge', 'aoa_g', -0.255, -0.083, 0.036), ('gauge', 'ias', -0.175, -0.080, 0.034),
    ('gauge', 'adi', -0.079, -0.088, 0.040), ('gauge', 'vvi', 0.014, -0.088, 0.034),
    ('gauge', 'rpm', 0.092, -0.104, 0.036), ('gauge', 'alt', -0.239, -0.149, 0.034),
    ('gauge', 'clock', -0.164, -0.152, 0.033), ('gauge', 'hsi', -0.074, -0.176, 0.044),
    ('display', 'mech_ind', -0.268, -0.200, 0.045, 0.035), ('gauge', 'radalt', -0.215, -0.203, 0.025),
    ('gauge', 'itt_l', 0.057, -0.163, 0.026), ('gauge', 'itt_r', 0.113, -0.163, 0.026),
    ('display', 'fuel_strip', 0.182, -0.165, 0.040, 0.100), ('display', 'ekran', 0.240, -0.160, 0.050, 0.070),
    ('plate', 'warning_lights', 0.294, -0.165, 0.060, 0.070), ('scope', 'spo15', 0.300, -0.230, 0.030),
    ('display', 'hdd', 0.290, -0.030, 0.130, 0.110)]

# MiG-15bis: DCS MiG-15bis Flight Manual, figure 4.5 "MiG-15bis instrument panel". Left column KUS airspeed, VD-17
# altimeter, high altimeter; centre-left AGK-47B horizon, clock, Machmeter; centre-right VAR-75 climb, fuel gauge;
# right ARK radio compass, GIK compass, oil/fuel pressure; far right tachometer, EGT, volt/amp, cabin altitude;
# the oxygen indicator and the gear indicator with its lever on the low left wing of the panel.
OUTLINES['mig15'] = [(-0.180, 0.0), (0.250, 0.0), (0.280, -0.080), (0.340, -0.130), (0.340, -0.300), (0.100, -0.310),
                     (0.090, -0.350), (-0.090, -0.350), (-0.100, -0.310), (-0.350, -0.300), (-0.350, -0.160),
                     (-0.240, -0.060)]
LAYOUTS['mig15'] = [
    ('gauge', 'kus', -0.134, -0.089, 0.034), ('gauge', 'vd17', -0.134, -0.171, 0.034),
    ('gauge', 'alt_hi', -0.134, -0.252, 0.034), ('gauge', 'agk47', -0.042, -0.094, 0.040),
    ('gauge', 'clock', -0.050, -0.188, 0.034), ('gauge', 'mach', -0.042, -0.267, 0.034),
    ('gauge', 'var75', 0.054, -0.111, 0.034), ('gauge', 'fuel', 0.054, -0.208, 0.034),
    ('gauge', 'ark', 0.141, -0.089, 0.034), ('gauge', 'gik', 0.141, -0.161, 0.034),
    ('gauge', 'press', 0.141, -0.248, 0.032), ('gauge', 'rpm', 0.220, -0.094, 0.034),
    ('gauge', 'egt', 0.228, -0.173, 0.034), ('gauge', 'eng_small', 0.228, -0.245, 0.026),
    ('gauge', 'voltamp', 0.304, -0.178, 0.024), ('gauge', 'cabin_alt', 0.304, -0.248, 0.030),
    ('gauge', 'oxygen', -0.213, -0.094, 0.026), ('gauge', 'oxy_press', -0.317, -0.200, 0.025),
    ('display', 'gear_ind', -0.230, -0.257, 0.070, 0.050)]

# what differs per aircraft (same-variant notes; the layouts are first-pass archetypes, refined type by type)
AIRCRAFT = {
    'f_16b': dict(stick='side', recline=30, layout='radar'),          # F-16B Block 15: steam panel, side stick
    'rafale': dict(stick='side', layout='wide'),                        # Rafale: side stick, wide head-level displays
    'su_57': dict(layout='wide'),                                       # two large MFDs
    'su_35': dict(layout='wide'),                                       # two large MFI-35 displays
    'eurofighter_typhoon': dict(layout='modern3'),                      # three MHDDs
    'saab_jas_39_gripen': dict(layout='modern3'),                       # three displays
    'fa_18e': dict(layout='modern3'),                                   # three DDIs plus the UFCD
    'j_10a': dict(layout='modern3'),
    'j_15d': dict(layout='wide'),
    'f_15c': dict(sight='hud', outline='f15c', layout='f15c'),       # traced: DCS FC3 manual fig. 4-1
    'mig_15bis': dict(outline='mig15', layout='mig15'),             # traced: DCS MiG-15bis manual fig. 4.5
    'f_14a': dict(sight='hud'), 'a_7d': dict(sight='hud'),
    'a_10': dict(sight='hud', outline='a10', layout='a10'),            # traced: DCS A-10A manual fig. 40
    'su_27': dict(sight='hud', outline='su27', layout='su27'),        # traced: DCS Su-27 manual fig. 24
    'su_25': dict(sight='gunsight', outline='su25', layout='su25'), 'su_39': dict(sight='hud'),   # Su-25: ASP-17 sight
    'mig_23mld': dict(sight='hud'), 'mirage_f1': dict(sight='hud'),
    'panavia_tornado_ids_marineflieger': dict(sight='hud'), 'saab_37_viggen': dict(sight='hud', outline='viggen', layout='viggen'),
}


def spec_for(vid, era, fleet):
    s = dict(ERAS.get(era, ERAS['COLD_WAR_STEAM']))
    s.update(stick='centre')
    if fleet.get('side_stick'):
        s['stick'] = 'side'
    if fleet.get('hud') is True:
        s['sight'] = 'hud'
    s.update(AIRCRAFT.get(vid, {}))
    s['trace'] = OUTLINES[s['outline']]
    s['items'] = LAYOUTS[s['layout']]
    return s
