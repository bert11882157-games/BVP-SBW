#!/usr/bin/env python3
"""Vautour IIN canopies and windscreen, hand-built for the Sud Aviation Vautour.mtb airframe (commits 74ae912b, cad3dbdd): two
crew in tandem (pilot in front, navigator / radar operator behind), each under his own transparency, on the model's own
stepped fuselage.

The model's cockpit zone (all numbers eye-relative, from the pilot's eye (0, 2.46681, 3.9831), read off the untouched model,
checks/orig, and the source elements in vautour/cutlist.txt): the fuselage here is a stack of 5 to 7 unit steps; its shoulders
are flat ledges at y -0.294 (z -1.81 .. 0) and -0.340 (z 0 .. 1.09) from |x| 0.407 out to 0.68; the cockpit opening is
|x| < 0.407; the dorsal spine behind the navigator is a stepped turtleback (x 0.45 / 0.36 / 0.27 up to y +0.113, front face at
z -1.81); the solid nose starts at z +1.09 with its deck at y -0.294 (x < 0.36) and -0.340. The model's own frames (the rails,
the rear arch and the central arch, the old windscreen frames and sight), its tub interiors, dash and partition blocks, and both
seat groups are cut by element boxes (CUT_BOXES; faces that also lie in a kept element's box stay); the shoulders, ledges, floor
and nose block are the exterior and stay.

The real Vautour IIN (the SNCASO SO-4050 data sheets: "pilot and navigator in tandem, separate canopies"; type photographs; no
drawing with dimensions was found, so every number is the model's own line, UNVERIFIED against the real jet): two seats in
tandem under two transparencies on one long framed greenhouse, a framed windscreen with a flat armoured centre pane. Here:

  * navigator canopy: one blown shell (superellipse sections from the sill rail up to the crown) from the rear bow, which stands
    against the dorsal spine's front face, to the shared bow; the crown rises 0.36 above the pilot's eye line so the
    navigator's raised eye (+0.181) clears it, and falls to the spine's top at the rear bow;
  * pilot canopy: one blown shell from the shared bow (the navigator canopy's front bow and the pilot canopy's rear bow are
    ONE member) to the front bow;
  * windscreen: the flat armoured centre pane (one trapezoid, |x| <= 0.17 at the arch, 0.12 at the base bar) between two slim
    posts and a flat triangular quarter pane each side (arch corner, arch foot, base corner: planar by construction). The
    windscreen's arch IS the pilot canopy's front bow: one member where the shell and the windscreen glass meet, and the
    shell's section turns into the arch's flat-topped hexagon at the front so the arch's straight sides are the quarter
    panes' top edges;
  * frames (bone wreck_fuselage_1__canopy, closed solids, 4 to 6.5 cm, airframe grey): the two sill rails along both canopies
    (with the console ledge along their inner side), the rear bow, the shared bow, the front bow / windscreen arch, two posts, the
    base bar, the quarter panes' sill strips and, on the starboard rail, a hinge strip and lugs (both canopies hinge to the
    right, Decision taken: the real hinge side is unverified).

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read. Frames: vehicle-local blocks (x left, y up, z forward); meshes are written in model
pixels (x, y, -z) * 16.

Run first (on the airframe's files); the cockpit script vautour_iin.py then appends the sight glass to CanopyGlass.
Usage: python tools/cockpit_models/canopy_vautour_iin.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/vautour_iin.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/vautour_iin.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/vautour_iin.json')
TEXTURE = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity/vautour_iin.png')


def _eyes():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        return np.array(d['Attachments']['pilot_eye']['Position'], float), np.array(d['Attachments']['nav_eye']['Position'], float)
    except (OSError, KeyError):
        return np.array([0.0, 2.46681, 3.9831]), np.array([0.0, 2.64786, 2.76101])


EYE, NAV_EYE = _eyes()
NAV_DZ, NAV_DY = float(NAV_EYE[2] - EYE[2]), float(NAV_EYE[1] - EYE[1])      # the navigator's eye, eye-relative
BARS_BONE = 'wreck_fuselage_1__canopy'    # (an empty bone of the airframe: the frames go with the forward fuselage section)
H1 = 'wreck_fuselage_1__hull'
H0 = 'wreck_fuselage_0__hull'
FRAME_RGB = (170, 177, 179)               # the airframe's mid grey (the model's own texel); UV_FRAME is found in main()
UV_FRAME = None

# ---- the model's frames, interiors and seat groups, cut out: every face of the two hull pieces lying wholly inside one of
# CUT_BOXES and in none of KEEP_BOXES (absolute blocks, x0 y0 z0 x1 y1 z1; the source elements 93-109, 274-288, 372, 387,
# 449-516 and 519-557 except the old console boxes 521-523, 528-530, 537-542, which stay: my consoles cover them). EXPECT_CUT is the face count the first run must find (the airframe's face list must be the committed
# one); a second run finds none.
CUT_BOXES = [
    (0.362, 2.127, 2.580, 0.498, 2.263, 3.983), (0.385, 2.082, 3.983, 0.498, 2.207, 4.526), (0.317, 2.161, 2.173, 0.464, 2.308, 2.580),
    (0.226, 2.263, 2.173, 0.396, 2.444, 2.580), (0.045, 2.444, 2.173, 0.317, 2.580, 2.444), (0.000, 2.535, 2.173, 0.147, 2.580, 2.354),
    (0.362, 2.216, 3.485, 0.463, 2.399, 3.757), (0.362, 2.240, 2.987, 0.429, 2.399, 3.259), (0.317, 2.399, 3.440, 0.407, 2.535, 3.666),
    (0.317, 2.399, 3.078, 0.407, 2.535, 3.304), (0.226, 2.535, 3.123, 0.362, 2.625, 3.621), (0.000, 2.580, 3.168, 0.272, 2.625, 3.576),
    (0.317, 2.182, 4.255, 0.453, 2.354, 4.481), (0.091, 2.354, 4.345, 0.407, 2.535, 4.526), (0.000, 2.444, 4.390, 0.136, 2.535, 4.526),
    (0.000, 2.399, 4.526, 0.181, 2.489, 4.662), (0.091, 2.127, 4.662, 0.226, 2.444, 5.069), (-0.464, 2.161, 2.173, -0.317, 2.308, 2.580),
    (-0.396, 2.263, 2.173, -0.226, 2.444, 2.580), (-0.317, 2.444, 2.173, -0.045, 2.580, 2.444), (-0.147, 2.535, 2.173, 0.000, 2.580, 2.354),
    (-0.463, 2.216, 3.485, -0.362, 2.399, 3.757), (-0.429, 2.240, 2.987, -0.362, 2.399, 3.259), (-0.407, 2.399, 3.440, -0.317, 2.535, 3.666),
    (-0.407, 2.399, 3.078, -0.317, 2.535, 3.304), (-0.362, 2.535, 3.123, -0.226, 2.625, 3.621), (-0.272, 2.580, 3.168, 0.000, 2.625, 3.576),
    (-0.453, 2.182, 4.255, -0.317, 2.354, 4.481), (-0.407, 2.354, 4.345, -0.091, 2.535, 4.526), (-0.136, 2.444, 4.390, 0.000, 2.535, 4.526),
    (-0.181, 2.399, 4.526, 0.000, 2.489, 4.662), (-0.226, 2.127, 4.662, -0.091, 2.444, 5.069), (0.181, 2.082, 4.526, 0.453, 2.173, 5.069),
    (-0.453, 2.082, 4.526, -0.181, 2.173, 5.069), (-0.226, 1.358, 3.666, 0.226, 1.448, 4.255), (-0.272, 1.358, 3.440, 0.272, 2.444, 3.712),
    (0.226, 1.358, 3.621, 0.272, 1.629, 4.255), (-0.272, 1.358, 3.621, -0.226, 1.629, 4.255), (0.226, 1.358, 3.576, 0.272, 2.444, 3.802),
    (-0.272, 1.358, 3.576, -0.226, 2.444, 3.802), (0.045, 1.448, 3.666, 0.226, 1.494, 4.209), (-0.226, 1.448, 3.666, -0.045, 1.494, 4.209),
    (-0.045, 1.448, 3.666, 0.045, 1.494, 4.119), (-0.023, 1.448, 4.164, 0.023, 1.539, 4.209), (-0.068, 1.539, 4.164, 0.068, 1.584, 4.209),
    (-0.249, 1.426, 3.893, -0.068, 1.516, 3.983), (0.068, 1.426, 3.893, 0.249, 1.516, 3.983), (0.023, 1.426, 3.893, 0.068, 1.516, 3.983),
    (-0.068, 1.426, 3.893, -0.023, 1.516, 3.983), (-0.226, 1.494, 3.621, 0.226, 1.720, 3.712), (-0.226, 1.720, 3.598, 0.226, 1.946, 3.689),
    (-0.091, 2.399, 3.576, 0.091, 2.444, 3.666), (-0.091, 2.308, 3.576, 0.091, 2.399, 3.621), (-0.226, 2.173, 3.576, 0.226, 2.308, 3.666),
    (-0.226, 1.946, 3.621, 0.226, 2.173, 3.666), (-0.226, 2.173, 3.666, 0.226, 2.308, 3.712), (-0.136, 2.444, 3.440, 0.136, 2.580, 3.666),
    (-0.136, 2.444, 3.666, 0.136, 2.535, 3.757), (-0.136, 2.535, 3.666, 0.136, 2.580, 3.757), (0.113, 2.489, 3.530, 0.158, 2.535, 3.621),
    (-0.158, 2.489, 3.530, -0.113, 2.535, 3.621), (-0.045, 2.489, 3.802, 0.045, 2.535, 3.847), (0.045, 2.489, 3.757, 0.091, 2.535, 3.847),
    (-0.091, 2.489, 3.757, -0.045, 2.535, 3.847), (0.091, 2.037, 3.644, 0.181, 2.218, 3.689), (-0.181, 2.037, 3.644, -0.091, 2.218, 3.689),
    (-0.181, 1.992, 3.644, -0.091, 2.037, 3.689), (0.091, 1.992, 3.644, 0.181, 2.037, 3.689), (-0.226, 1.358, 2.444, 0.226, 1.448, 3.033),
    (-0.272, 1.358, 2.218, 0.272, 2.444, 2.489), (0.226, 1.358, 2.399, 0.272, 1.629, 3.033), (-0.272, 1.358, 2.399, -0.226, 1.629, 3.033),
    (0.226, 1.358, 2.354, 0.272, 2.444, 2.580), (-0.272, 1.358, 2.354, -0.226, 2.444, 2.580), (0.045, 1.448, 2.444, 0.226, 1.494, 2.987),
    (-0.226, 1.448, 2.444, -0.045, 1.494, 2.987), (-0.045, 1.448, 2.444, 0.045, 1.494, 2.897), (-0.023, 1.448, 2.942, 0.023, 1.539, 2.987),
    (-0.068, 1.539, 2.942, 0.068, 1.584, 2.987), (-0.249, 1.426, 2.670, -0.068, 1.516, 2.761), (0.068, 1.426, 2.670, 0.249, 1.516, 2.761),
    (0.023, 1.426, 2.670, 0.068, 1.516, 2.761), (-0.068, 1.426, 2.670, -0.023, 1.516, 2.761), (-0.226, 1.494, 2.399, 0.226, 1.720, 2.489),
    (-0.226, 1.720, 2.376, 0.226, 1.946, 2.467), (-0.091, 2.399, 2.354, 0.091, 2.444, 2.444), (-0.091, 2.308, 2.354, 0.091, 2.399, 2.399),
    (-0.226, 2.173, 2.354, 0.226, 2.308, 2.444), (-0.226, 1.946, 2.399, 0.226, 2.173, 2.444), (-0.226, 2.173, 2.444, 0.226, 2.308, 2.489),
    (-0.136, 2.444, 2.218, 0.136, 2.580, 2.444), (-0.136, 2.444, 2.444, 0.136, 2.535, 2.535), (-0.136, 2.535, 2.444, 0.136, 2.580, 2.535),
    (0.113, 2.489, 2.308, 0.158, 2.535, 2.399), (-0.158, 2.489, 2.308, -0.113, 2.535, 2.399), (-0.045, 2.489, 2.580, 0.045, 2.535, 2.625),
    (0.045, 2.489, 2.535, 0.091, 2.535, 2.625), (-0.091, 2.489, 2.535, -0.045, 2.535, 2.625), (0.091, 2.037, 2.422, 0.181, 2.218, 2.467),
    (-0.181, 2.037, 2.422, -0.091, 2.218, 2.467), (-0.181, 1.992, 2.422, -0.091, 2.037, 2.467), (0.091, 1.992, 2.422, 0.181, 2.037, 2.467),
    (-0.679, 1.358, 4.526, 0.679, 1.720, 5.069), (-0.679, 1.720, 4.526, 0.679, 2.263, 5.069), (-0.091, 1.358, 4.345, 0.091, 1.720, 4.436),
    (-0.091, 1.358, 4.436, 0.091, 1.720, 4.526), (-0.091, 1.358, 4.255, 0.091, 1.539, 4.345), (-0.679, 1.720, 4.436, 0.679, 2.263, 4.526),
    (0.136, 1.340, 4.367, 0.272, 1.604, 4.526), (-0.272, 1.340, 4.367, -0.136, 1.604, 4.526), (-0.027, 1.520, 4.090, 0.023, 1.949, 4.323),
    (-0.679, 1.358, 3.168, 0.679, 1.720, 3.485), (-0.679, 1.720, 3.123, 0.679, 2.263, 3.485), (-0.362, 2.263, 3.033, 0.362, 2.580, 3.485),
    (-0.091, 2.195, 4.368, 0.091, 2.286, 4.549), (0.045, 2.286, 4.368, 0.091, 2.467, 4.413), (0.045, 2.286, 4.504, 0.091, 2.376, 4.549),
    (0.045, 2.331, 4.413, 0.091, 2.467, 4.549), (0.045, 2.331, 4.413, 0.091, 2.376, 4.549), (-0.091, 2.286, 4.368, -0.045, 2.467, 4.413),
    (-0.091, 2.286, 4.504, -0.045, 2.376, 4.549), (-0.091, 2.331, 4.413, -0.045, 2.467, 4.549), (-0.091, 2.331, 4.413, -0.045, 2.376, 4.549),
    (0.362, 1.608, 4.059, 0.407, 1.748, 4.164), (0.317, 1.727, 4.019, 0.453, 1.788, 4.098), (0.317, 1.709, 4.019, 0.453, 1.771, 4.098),
    (0.157, 2.335, 4.328, 0.317, 2.493, 4.402), (-0.302, 2.346, 4.351, -0.128, 2.489, 4.410),
]
KEEP_BOXES = [
    (0.588, 1.086, 2.173, 0.815, 1.403, 3.983), (0.634, 1.403, 2.173, 0.815, 1.720, 3.983), (0.498, 1.720, 2.173, 0.769, 1.946, 3.983),
    (0.407, 1.946, 2.173, 0.679, 2.173, 3.983), (0.543, 1.086, 3.983, 0.815, 1.403, 4.526), (0.634, 1.403, 3.983, 0.815, 1.720, 4.526),
    (0.498, 1.720, 3.983, 0.769, 1.946, 4.526), (0.407, 1.946, 3.983, 0.634, 2.127, 4.526), (0.498, 1.086, 4.526, 0.815, 1.403, 5.069),
    (0.000, 1.086, 5.069, 0.769, 1.403, 5.658), (0.588, 1.403, 4.526, 0.815, 1.720, 5.069), (0.000, 1.403, 5.069, 0.769, 1.720, 5.658),
    (0.453, 1.720, 4.526, 0.769, 1.946, 5.069), (0.000, 1.675, 5.069, 0.724, 1.946, 5.658), (0.000, 1.901, 5.069, 0.588, 2.127, 5.658),
    (0.000, 2.037, 5.069, 0.362, 2.173, 5.658), (-0.769, 1.086, 5.069, 0.000, 1.403, 5.658), (-0.769, 1.403, 5.069, 0.000, 1.720, 5.658),
    (-0.724, 1.675, 5.069, 0.000, 1.946, 5.658), (-0.588, 1.901, 5.069, 0.000, 2.127, 5.658), (-0.362, 2.037, 5.069, 0.000, 2.173, 5.658),
    (0.226, 1.946, 4.526, 0.634, 2.127, 5.069), (-0.815, 1.086, 2.173, -0.588, 1.403, 3.983), (-0.815, 1.403, 2.173, -0.634, 1.720, 3.983),
    (-0.769, 1.720, 2.173, -0.498, 1.946, 3.983), (-0.679, 1.946, 2.173, -0.407, 2.173, 3.983), (-0.815, 1.086, 3.983, -0.543, 1.403, 4.526),
    (-0.815, 1.403, 3.983, -0.634, 1.720, 4.526), (-0.769, 1.720, 3.983, -0.498, 1.946, 4.526), (-0.634, 1.946, 3.983, -0.407, 2.127, 4.526),
    (-0.815, 1.086, 4.526, -0.498, 1.403, 5.069), (-0.815, 1.403, 4.526, -0.588, 1.720, 5.069), (-0.769, 1.720, 4.526, -0.453, 1.946, 5.069),
    (-0.498, 2.127, 2.580, -0.362, 2.263, 3.983), (-0.498, 2.082, 3.983, -0.385, 2.207, 4.526), (-0.634, 1.946, 4.526, -0.226, 2.127, 5.069),
    (0.000, 1.086, 2.173, 0.724, 1.358, 3.983), (0.000, 1.086, 3.983, 0.724, 1.358, 5.069), (-0.724, 1.086, 2.173, 0.000, 1.358, 3.983),
    (-0.724, 1.086, 3.983, 0.000, 1.358, 5.069), (0.317, 1.358, 4.436, 0.679, 1.720, 4.526), (0.317, 1.358, 4.255, 0.679, 1.720, 4.436),
    (0.317, 1.358, 3.485, 0.679, 1.629, 4.255), (-0.679, 1.358, 4.436, -0.317, 1.720, 4.526), (-0.679, 1.358, 4.255, -0.317, 1.720, 4.436),
    (-0.679, 1.358, 3.349, -0.317, 1.629, 4.255), (0.317, 1.358, 3.123, 0.679, 1.720, 3.214), (0.317, 1.358, 2.942, 0.679, 1.720, 3.123),
    (0.317, 1.358, 2.173, 0.679, 1.629, 2.942), (-0.679, 1.358, 3.123, -0.317, 1.720, 3.214), (-0.679, 1.358, 2.942, -0.317, 1.720, 3.123),
    (-0.679, 1.358, 2.173, -0.317, 1.629, 2.942),
]
EXPECT_CUT = 736
CUT_TOL = 0.004


def R(x, y, z):
    return np.array([x, y, z], float)


# ---- the canopies (eye-relative blocks: x to the left, y up, z forward)
Z_RB = (-1.806, -1.756)                   # rear bow (5 cm), its back face on the dorsal spine's front face
Z_SB = (-0.700, -0.640)                   # the shared bow (6 cm): the navigator canopy's front bow = the pilot canopy's rear bow
Z_ARCH = (0.400, 0.450)                   # front bow = windscreen arch
Z_END = Z_ARCH[0]
X_F = 0.425                               # the glass foot: the middle of the sill rail (the opening's edge stands at 0.407)
FOOT_Y = [(-1.806, -0.294), (-0.030, -0.294), (0.030, -0.340), (0.450, -0.340)]     # the ledges' tops (the step at the pilot's eye)
CROWN = [(-1.806, 0.135), (-1.65, 0.230), (-1.50, 0.310), (-1.30, 0.355), (-1.00, 0.360), (-0.70, 0.345), (-0.40, 0.305),
         (0.00, 0.240), (0.20, 0.205), (0.40, 0.170)]
YC0 = 0.170                               # the front pane's top edge: the arch's crown height
NEXP0, NEXP1 = 2.3, 1.8
W0, W1 = 0.0, Z_END                       # the section morphs from rounded to the arch's hexagon between these stations
HEX_X = 0.190                             # the arch's flat top ends here (the post's outer edge at the arch)


def y_foot(z):
    return float(np.interp(min(max(z, Z_RB[0]), Z_ARCH[1]), [p[0] for p in FOOT_Y], [p[1] for p in FOOT_Y]))


def x_foot(z):
    return X_F


def crown(z):
    return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))


def resample(pts, k):
    p = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    t = np.linspace(0, d[-1], k)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


def round_section(z, n=60):
    yt, yf, xf = crown(z), y_foot(z), x_foot(z)
    e = float(np.interp(z, [Z_RB[0], Z_END], [NEXP0, NEXP1]))
    us = np.linspace(0, 1, n)
    pts = [(xf * (1 - u ** e) ** (1 / e), yf + (yt - yf) * u) for u in us]
    pts[-1] = (0.0, yt)
    return pts


def hex_section(z):
    yt, yf, xf = crown(z), y_foot(z), x_foot(z)
    return [(xf, yf), (HEX_X, yt), (0.0, yt)]


def smooth(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3 - 2 * t)


def section(z, n=24):
    """right half-section at station z (eye-relative): from the sill foot up to the crown (x = 0)"""
    z = min(max(z, Z_RB[0]), Z_END)
    w = smooth((z - W0) / (W1 - W0))
    a = resample(round_section(z), n)
    b = resample(hex_section(z), n)
    pts = a * (1 - w) + b * w
    pts[0] = (x_foot(z), y_foot(z))
    pts[-1] = (0.0, crown(z))
    return [tuple(p) for p in pts]


half_section = section                    # (the name the cockpit script uses)


# ---- the windscreen: one flat front pane, two posts, two flat triangular quarter panes
ZF = Z_ARCH[1]                            # the arch's front face
Z_B0, Z_B1 = 1.040, 1.084                 # the base bar on the nose deck (the solid nose starts at z +1.0859)
YB = -0.285                               # the front pane's plane at the base bar (the deck stands at -0.294)
SLOPE = (YC0 - YB) / (Z_B0 - ZF)          # the plane drops this much per block forward
HW0, HWB = 0.170, 0.120                   # the post's centre line: half width at the arch and at the base bar
POST = 0.020                              # half width of a post
T_OUT, T_IN = 0.012, 0.030
Y_FOOT_ARCH = y_foot(Z_ARCH[1])           # the arch foot / the sill strip's start


def yc(z):                                # the front pane's plane (flat: independent of x)
    return YC0 - SLOPE * (z - ZF)


def hw(z):
    return HW0 + (HWB - HW0) * (z - ZF) / (Z_B0 - ZF)


T1 = R(HW0 + POST, YC0, ZF)               # the quarter pane: arch corner on the post's outer edge,
T2 = R(X_F, Y_FOOT_ARCH, ZF)              # the arch foot,
B1 = R(HWB + POST, YB, Z_B0)              # the post's outer edge at the base bar


def strip_xy(z):
    """the sill strip's inner top edge (the quarter pane's lower edge): T2 -> B1"""
    t = (z - ZF) / (Z_B0 - ZF)
    p = T2 + (B1 - T2) * t
    return p[0], p[1]


def x_out(z):                             # (the coaming's reach, read by the cockpit script)
    return strip_xy(min(max(z, ZF), Z_B0))[0]


def outline_rel():
    """the outline the checks read: from the sill line up the glass; the windscreen stations run from the sill strip's edge
    over the quarter pane and the post to the centre line"""
    stations = [-1.806, -1.78, -1.6, -1.4, -1.2, -1.0, -0.8, -0.7, -0.64, -0.4, -0.15, 0.1, 0.3, 0.40, 0.45, 0.60, 0.78, 0.95, 1.040]
    out = []
    for z in stations:
        if z <= Z_END + 1e-9:
            pts = section(z)
        else:
            xo, yo = strip_xy(z)
            pts = [(xo, yo), (hw(z) + POST, yc(z)), (0.0, yc(z))]
        out.append((z, [(x, y) for x, y in pts]))
    return out


CANOPY = outline_rel()                    # eye-relative, as handbuilt_canopy.Shape wants it (the checks read this)


# ---------------------------------------------------------------- glass (eye-relative, converted when written)

def shell_patch(z0, z1, nz, ns=16):
    """the blown shell between two stations: row j of the arc-length resampled section at every station, both sides"""
    out = []
    for side in (1, -1):
        grid = []
        for i in range(nz + 1):
            z = z0 + (z1 - z0) * i / nz
            r = resample(section(z), ns + 1)
            grid.append([np.array([side * x, y, z]) for x, y in r])
        for i in range(nz):
            for j in range(ns):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append(list(tri))
    return out


def windscreen_glass():
    out = []
    g0, gb = HW0 - POST, HWB - POST
    q = [R(-g0, yc(ZF), ZF), R(g0, yc(ZF), ZF), R(gb, yc(Z_B0), Z_B0), R(-gb, yc(Z_B0), Z_B0)]
    out += [[q[0], q[1], q[2]], [q[0], q[2], q[3]]]
    for side in (1, -1):                  # the quarter panes: one flat triangle each
        out.append([R(side * T1[0], T1[1], T1[2]), R(side * T2[0], T2[1], T2[2]), R(side * B1[0], B1[1], B1[2])])
    return out


# ---------------------------------------------------------------- frames (closed solids)

class Bars:
    """faces of the slim frame members as a poly mesh in model pixels; each face has one winding, outward from the member.
    Points come in eye-relative blocks"""
    def __init__(self, uv):
        self.P, self.N, self.U, self.polys, self.uv = [], [], [], [], uv

    def face(self, pts, n):
        px = [[float(q[0] + EYE[0]) * 16, float(q[1] + EYE[1]) * 16, -float(q[2] + EYE[2]) * 16] for q in pts]
        npx = np.array([n[0], n[1], -n[2]])
        a, b, c = (np.array(q) for q in px[:3])
        # the mesh loader mirrors X: in file pixels a face winds clockwise seen from its front (the hull's own faces do the same)
        if np.cross(b - a, c - a) @ npx > 0:
            px = px[::-1]
        ni = len(self.N)
        self.N.append([round(float(x), 5) + 0.0 for x in -npx])
        ui = len(self.U)
        self.U.append(list(self.uv))
        poly = []
        for p in px:
            poly.append([len(self.P), ni, ui])
            self.P.append([round(x, 4) + 0.0 for x in p])
        self.polys.append(poly)

    def mesh(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}

    def quad(self, q, inside):
        """q: 3 or 4 points; the normal points away from the point [inside]"""
        q = [np.array(p, float) for p in q]
        n = np.cross(q[1] - q[0], q[2] - q[0])
        if len(q) == 4:
            n = n + np.cross(q[2] - q[0], q[3] - q[0])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        if n @ (np.mean(q, 0) - np.array(inside, float)) < 0:
            n = -n
        self.face(q, n)

    def sweep(self, A, B, C, D):
        """a bar between four polylines A (top, near), B (top, far), C (bottom, far), D (bottom, near); the open ends are
        left to the neighbouring member (or are buried)"""
        for i in range(len(A) - 1):
            ctr = np.mean([L[k] for L in (A, B, C, D) for k in (i, i + 1)], 0)
            for p, q in ((A, B), (D, C), (A, D), (B, C)):
                self.quad([p[i], p[i + 1], q[i + 1], q[i]], ctr)

    def cap(self, pts, inside):
        self.quad(pts, inside)

    def box(self, lo, hi):
        lo, hi = np.array(lo, float), np.array(hi, float)
        c = (lo + hi) / 2
        for ax in range(3):
            for s in (lo, hi):
                i, j = [m for m in range(3) if m != ax]
                q = []
                for (a, b) in ((0, 0), (1, 0), (1, 1), (0, 1)):
                    p = np.zeros(3)
                    p[ax] = s[ax]
                    p[i] = (lo, hi)[a][i]
                    p[j] = (lo, hi)[b][j]
                    q.append(p)
                self.quad(q, c)


def out_normals(pts):
    """unit normal (x, y) of a half-section polyline at each point, pointing outward (up and out)"""
    n = []
    for k in range(len(pts)):
        a, c = np.array(pts[max(0, k - 1)], float), np.array(pts[min(len(pts) - 1, k + 1)], float)
        t = c - a
        t /= np.linalg.norm(t)
        nn = np.array([t[1], -t[0]])
        if nn[0] < -1e-9 or (abs(nn[0]) < 1e-9 and nn[1] < 0):
            nn = -nn
        n.append(nn)
    return n


def bow_bars(b, z0, z1):
    """a transverse bow over the shell between z0 and z1 (z1 - z0 deep, 3.8 cm radially, 1.2 cm proud of the glass)"""
    zc = 0.5 * (z0 + z1)
    sec = section(zc)
    nrm = out_normals(sec)
    for side in (1, -1):
        def line(z, rad):
            return [np.array([side * (p[0] + n[0] * rad), p[1] + n[1] * rad, z]) for p, n in zip(sec, nrm)]
        A, B, C, D = line(z0, T_OUT), line(z1, T_OUT), line(z1, -T_IN), line(z0, -T_IN)
        for Ln in (A, B, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.045, 0])        # the foot goes into the ledge
        b.sweep(A, B, C, D)


def arch_bars(b):
    """the windscreen arch = pilot canopy front bow: one member, from the shell's end edge on its rear face to the windscreen's
    top edge on its front face (the hexagon's own line, so the front pane's and the quarter panes' top edges lie on it)"""
    zr, zf = Z_ARCH
    sec = section(Z_END)
    for side in (1, -1):
        A = [np.array([side * p[0], p[1] + T_OUT, zr]) for p in sec]
        Bf = [np.array([side * p[0], p[1] + T_OUT, zf]) for p in sec]
        C = [p - np.array([0, T_IN * 1.15, 0]) for p in Bf]
        D = [p - np.array([0, T_IN * 1.15, 0]) for p in A]
        for Ln in (A, Bf, C, D):
            Ln[0] = Ln[0] + np.array([0, -0.045, 0])
        b.sweep(A, Bf, C, D)


def rail_bars(b):
    """the sill rails: 6.5 cm wide, top at the glass foot's line, buried 4.5 cm into the ledge"""
    zs = sorted(set(list(np.linspace(Z_RB[0], ZF, 30)) + [-0.045, -0.03, 0.0, 0.03, 0.045]))
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (x_foot(z) + dx), y_foot(z) + dy, z]) for z in zs]
        A, B = line(-0.035, 0.0), line(0.030, 0.0)
        C, D = line(0.030, -0.045), line(-0.035, -0.045)
        b.sweep(A, B, C, D)
        # the console ledge: from the cockpit's lining (x 0.393, 4 mm clear) out to the rail, 2 cm under the rail's top
        A, B = line(-0.058, -0.020), line(-0.035, -0.020)
        C, D = line(-0.035, -0.045), line(-0.058, -0.045)
        b.sweep(A, B, C, D)


def windscreen_bars(b):
    # the two posts: strips of the front pane's plane, 4 cm wide, 1.2 cm proud and 3 cm deep, arch to base bar
    zs = np.linspace(ZF, Z_B0, 6)
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (hw(z) + dx), yc(z) + dy, z]) for z in zs]
        A, B = line(-POST, T_OUT), line(POST, T_OUT)
        C, D = line(POST, -0.030), line(-POST, -0.030)
        b.sweep(A, B, C, D)
    # the base bar on the deck: across the posts and the quarter panes' feet, in the front pane's plane
    x0 = HWB + POST + 0.045
    A = [R(-x0, yc(Z_B0) + T_OUT, Z_B0), R(x0, yc(Z_B0) + T_OUT, Z_B0)]
    B = [R(-x0, yc(Z_B1) + T_OUT, Z_B1), R(x0, yc(Z_B1) + T_OUT, Z_B1)]
    C = [R(-x0, yc(Z_B1) - 0.090, Z_B1), R(x0, yc(Z_B1) - 0.090, Z_B1)]
    D = [R(-x0, yc(Z_B0) - 0.090, Z_B0), R(x0, yc(Z_B0) - 0.090, Z_B0)]
    b.sweep(A, B, C, D)
    ctr = R(0, yc(0.5 * (Z_B0 + Z_B1)) - 0.03, 0.5 * (Z_B0 + Z_B1))
    for i in (0, 1):
        b.cap([A[i], B[i], C[i], D[i]], ctr)
    # the sill strips along the quarter panes: from the arch foot to the base bar's end, outboard of the glass's lower edge,
    # top at that edge's height, 6.5 cm wide, down into the skin
    zs = np.linspace(ZF, Z_B0, 6)
    for side in (1, -1):
        def line(dx, dy):
            return [np.array([side * (strip_xy(z)[0] + dx), strip_xy(z)[1] + dy, z]) for z in zs]
        A_, B_ = line(0.0, 0.0), line(0.065, 0.0)
        C_ = [np.array([p[0], p[1] - 0.075, p[2]]) for p in B_]
        D_ = [np.array([p[0], p[1] - 0.075, p[2]]) for p in A_]
        b.sweep(A_, B_, C_, D_)


def hinge_bars(b):
    """both canopies hinge on the right (starboard, x < 0) rail: a hinge strip along the outside of the rail and lugs"""
    side = -1
    for za, zb, zl in ((-1.70, -0.76, (-1.55, -1.05)), (-0.58, 0.33, (-0.45, -0.05, 0.30))):
        for z in zl:
            x_in, x_out_ = x_foot(z) + 0.030, x_foot(z) + 0.062
            yt = y_foot(z) + 0.010
            b.box(R(side * x_out_, yt - 0.060, z - 0.040), R(side * x_in, yt, z + 0.040))
        zz = np.linspace(za, zb, 12)
        A = [R(side * (x_foot(z) + 0.030), y_foot(z) + 0.004, z) for z in zz]
        B = [R(side * (x_foot(z) + 0.036), y_foot(z) + 0.004, z) for z in zz]
        C = [R(side * (x_foot(z) + 0.036), y_foot(z) - 0.040, z) for z in zz]
        D = [R(side * (x_foot(z) + 0.030), y_foot(z) - 0.040, z) for z in zz]
        b.sweep(A, B, C, D)


def frame_bars():
    b = Bars(UV_FRAME)
    rail_bars(b)
    bow_bars(b, *Z_RB)
    bow_bars(b, *Z_SB)
    arch_bars(b)
    windscreen_bars(b)
    hinge_bars(b)
    return b


# ---------------------------------------------------------------- the airframe

def cut_frame(bones, already):
    """removes the model's own frames, interiors and seats (CUT_BOXES) from the two hull pieces: every face wholly inside a cut
    box and inside no kept box. The first run must find EXPECT_CUT faces; returns the faces removed"""
    gone = {}
    box = np.array(CUT_BOXES, float)
    lo, hi = box[:, :3] - CUT_TOL, box[:, 3:] + CUT_TOL
    kb = np.array(KEEP_BOXES, float)
    klo, khi = kb[:, :3] - CUT_TOL, kb[:, 3:] + CUT_TOL
    for name in (H1, H0):
        pm = next(b for b in bones if b['name'] == name)['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        idx = set()
        for k, poly in enumerate(pm['polys']):
            if not poly:
                continue
            V = P[[v[0] for v in poly]]
            vl, vh = V.min(0), V.max(0)
            if np.any(np.all(vl >= lo, axis=1) & np.all(vh <= hi, axis=1)) and \
                    not np.any(np.all(vl >= klo, axis=1) & np.all(vh <= khi, axis=1)):
                idx.add(k)
        gone[name] = idx
    n = sum(len(v) for v in gone.values())
    if n != EXPECT_CUT and not already and n:
        raise SystemExit(f'the frame cut found {n} faces, expected {EXPECT_CUT}: the airframe changed; re-read CUT_BOXES (nothing was written)')
    for name, idx in gone.items():
        pm = next(b for b in bones if b['name'] == name)['poly_mesh']
        pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in idx]
    return n


def glass_triangles():
    tris = shell_patch(Z_RB[1], Z_SB[0], 22)                   # the navigator's canopy: rear bow -> shared bow
    tris += shell_patch(Z_SB[1], Z_ARCH[0], 22)                # the pilot's canopy: shared bow -> front bow
    tris += windscreen_glass()                                 # the front pane and the two quarter panes
    return [np.array(t) + EYE for t in tris]


def main(argv):
    global UV_FRAME
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    hull = next(b for b in bones if b['name'] == H1)
    # a texel of the model's own airframe grey (170, 177, 179), the frame's colour: the first such swatch in the hull's uvs
    from PIL import Image
    tex = Image.open(TEXTURE).convert('RGBA')
    W_, H_ = tex.size
    pmh = hull['poly_mesh']
    best = None
    for i in range(len(pmh['uvs'])):
        u, v = pmh['uvs'][i]
        px = tex.getpixel((min(W_ - 1, int(u * W_)), min(H_ - 1, int((1 - v) * H_))))
        if px == FRAME_RGB + (255,):
            best = [u, v]
            break
    if best is None:
        raise SystemExit(f'no opaque texel {FRAME_RGB} under the hull uvs: the frame colour needs another source')
    UV_FRAME = best
    bars_bone = next((b for b in bones if b['name'] == BARS_BONE), None)
    if bars_bone is None:
        at = next(i for i, b in enumerate(bones) if b['name'] == H1) + 1
        bars_bone = {'name': BARS_BONE, 'parent': 'hull', 'pivot': list(next(b for b in bones if b['name'] == 'hull')['pivot'])}
        bones.insert(at, bars_bone)
    already = bool(bars_bone.get('poly_mesh', {}).get('polys'))
    cut = cut_frame(bones, already)
    bars = frame_bars()
    bars_bone['poly_mesh'] = bars.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f"vautour_iin canopy: the model's frames, interiors and seats cut out ({cut} faces), {len(bars.polys)} faces of slim frames; "
          f'{len(tris)} glass triangles (x2 windings): two blown canopies, one flat front pane, two flat quarter panes')
    if dry:
        return
    with open(GEO, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(geo, f, separators=(',', ':'))
        f.write('\n')
    glass_json = asset.pop('CanopyGlass')
    text = json.dumps(asset, indent=2, ensure_ascii=False)
    text = text[:-2] + ',\n  "CanopyGlass": ' + json.dumps(glass_json, separators=(',', ':')) + '\n}'
    with open(ASSET, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text + '\n')


if __name__ == '__main__':
    main(sys.argv[1:])
