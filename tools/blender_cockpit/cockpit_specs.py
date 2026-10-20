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
    'f_15c': dict(sight='hud'), 'f_14a': dict(sight='hud'), 'a_7d': dict(sight='hud'),
    'a_10': dict(sight='hud', outline='radar', layout='radar'),
    'su_27': dict(sight='hud'), 'su_25': dict(sight='hud'), 'su_39': dict(sight='hud'),
    'mig_23mld': dict(sight='hud'), 'mirage_f1': dict(sight='hud'),
    'panavia_tornado_ids_marineflieger': dict(sight='hud'), 'saab_37_viggen': dict(sight='hud'),
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
