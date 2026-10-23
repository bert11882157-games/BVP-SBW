#!/usr/bin/env python3
"""Xi'an H-6K flight-deck windows and frames, hand-built for the H-6K.mtb airframe (commits 7ec0ae81 + 353df86d): the window band round the
solid-nose flight deck, on the model's own fuselage. The model's mould line is kept.

The model's flight-deck zone (source units of H-6K.mtb, listed by work/elite_expansion_2/h6k/cutlist.py; vehicle blocks = (x * S / 16,
(y + 7) * S / 16, -(z + 15) * S / 16), S = 0.8064, 1 block = 1 m): the nose deck rises to a glareshield ledge at y 66 (z -301 to -289); behind
it the cabin walls stand at |x| 20 (17 at the windscreen) up to y 58, then slope in; the roof shell (y 72 to 78) starts at z -287 at the
centre and z -284 at the sides and is closed. The windows are the band between the deck's top edge (y 66) and the roof's front edge (y 72): a front
row of two panes either side of a centre post (z -291 to -287), a corner post, and two side panes (z -288 to -275 and -275 to -268) in the
sloping upper side wall, ending at the seat-back bulkhead (z -268). The model's own frame bars (elements 168-175 and their mirrors 882-889),
the whole interior (both seat groups: toolbox group 1790586884, the dash, the pedestal, the side consoles, the partitions and the rear console
wall) are cut out by element boxes (CUT_BOXES; faces that also lie in a kept element's box stay). The shell, the floor, the nose block and the
roof are exterior and stay.

The real H-6K (type descriptions and photographs; no drawing with dimensions was found, so every number is the model's own line, UNVERIFIED
against the real jet): a solid radome nose and a flight deck set well back under a stepped, raised windscreen of flat panes: a centre post
between two front panes either side, and side windows behind them. Here:
  * eight flat panes: front pane and front quarter pane either side of the centre post, two side panes (before and behind the side post) on the
    sloping upper side wall; each pane is one plane (the best fit through its frame corners, so the frame and the glass line up);
  * frames (bone wreck_fuselage_0__canopy, closed solids, 4 to 7 cm, airframe grey): the centre post, the front posts, the corner posts, the side
    posts and the bulkhead-end posts, the roof rails along the roof's front edge and the deck rails along its lower edge; glass stops at every
    frame edge (the outline runs on the frame centre lines, the glass is inset by half the frame's width).

CANOPY (eye-relative sections, the right half from the sill up over the top) is what the checks in work/elite_expansion_2/checks read: the whole
cabin (walls above the sill at y 58, the sloping upper wall, the roof) is one outline, as the Vulcan's. The checks measure from the pilot's eye:
here the captain's eye stands 0.4032 off the centre line, so they are run through h6k/check_h6k.py, which sets the origin on the centre line.

Run first (on the airframe's files); the cockpit script h_6k.py follows.
Usage: python tools/cockpit_models/canopy_h_6k.py [--dry-run] [--flat]   (--flat writes flat_panes/h_6k.json)"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/h_6k.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/h_6k.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/h_6k.json')
TEXTURE = L.texture_path('h_6k')
FLAT = os.path.join(HERE, 'flat_panes', 'h_6k.json')
S = 0.8064


def _eyes():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        A = d['Attachments']
        return np.array(A['pilot_eye']['Position'], float), np.array(A['copilot_eye']['Position'], float)
    except (OSError, KeyError):
        return np.array([0.4032, 3.8052, 13.2552]), np.array([-0.4032, 3.8052, 13.2552])


EYE, EYE_C = _eyes()                       # the captain's and the co-pilot's eyes (vehicle blocks)
CENTRE = np.array([0.0, EYE[1], EYE[2]])   # the centre line at the eyes' height: the origin of CANOPY and of the checks
BARS_BONE = 'wreck_fuselage_0__canopy'     # (an empty bone of the airframe: the frames go with the forward fuselage section)
H0 = 'wreck_fuselage_0__hull'
HULLS = ('wreck_fuselage_0__hull', 'wreck_fuselage_1__hull')
GREY_FRAME = (74, 78, 80)                  # frames: a darker airframe grey
UV_FRAME = None

# ---- the model's frames, interiors and seat groups, cut out: every face of the hull pieces lying wholly inside one of CUT_BOXES and in none of
# KEEP_BOXES (absolute blocks, x0 y0 z0 x1 y1 z1; the source elements listed by h6k/gen_cutboxes.py).
# EXPECT_CUT is the face count the first run must find (the airframe's face list must be the committed one); a second run finds none.
CUT_BOXES = [
    (0.252, 3.276, 12.701, 1.008, 3.679, 12.751), (0.554, 3.679, 13.507, 0.605, 3.982, 13.810),
    (0.554, 3.931, 13.104, 0.655, 3.982, 13.558), (0.605, 3.931, 12.751, 0.655, 3.982, 13.104),
    (0.605, 3.679, 13.054, 0.806, 3.982, 13.104), (0.202, 3.931, 13.507, 0.605, 3.982, 13.709),
    (0.000, 3.931, 13.658, 0.202, 3.982, 13.709), (0.151, 3.604, 13.709, 0.302, 3.982, 13.910),
    (0.000, 3.629, 13.709, 0.050, 3.982, 13.910), (0.202, 2.570, 12.902, 0.605, 2.722, 13.759),
    (0.605, 2.570, 12.751, 0.655, 2.822, 13.810), (0.151, 2.570, 12.751, 0.202, 2.822, 13.810),
    (0.202, 2.570, 12.751, 0.605, 3.629, 12.902), (0.605, 2.822, 12.751, 0.655, 3.629, 13.104),
    (0.151, 2.822, 12.751, 0.202, 3.629, 13.104), (0.504, 3.024, 12.902, 0.605, 3.528, 12.953),
    (0.202, 2.722, 12.902, 0.605, 2.923, 12.953), (0.202, 3.024, 12.902, 0.302, 3.528, 12.953),
    (0.504, 2.923, 12.902, 0.605, 3.074, 12.953), (0.202, 2.923, 12.902, 0.302, 3.074, 12.953),
    (0.302, 2.923, 12.902, 0.504, 3.528, 12.953), (0.479, 3.074, 12.877, 0.529, 3.276, 12.978),
    (0.277, 3.074, 12.877, 0.328, 3.276, 12.978), (0.277, 3.276, 12.877, 0.328, 3.326, 12.978),
    (0.479, 3.276, 12.877, 0.529, 3.326, 12.978), (0.479, 3.326, 12.877, 0.529, 3.578, 12.978),
    (0.277, 3.326, 12.877, 0.328, 3.578, 12.978), (0.428, 3.528, 12.877, 0.479, 3.629, 12.928),
    (0.328, 3.528, 12.877, 0.378, 3.629, 12.928), (0.479, 2.722, 12.877, 0.655, 3.074, 12.978),
    (0.151, 2.722, 12.877, 0.328, 3.074, 12.978), (0.529, 3.478, 12.877, 0.580, 3.629, 12.928),
    (0.227, 3.478, 12.877, 0.277, 3.629, 12.928), (0.554, 3.629, 12.751, 0.655, 3.679, 12.953),
    (0.554, 3.679, 12.751, 0.655, 3.730, 12.953), (0.151, 3.629, 12.751, 0.252, 3.679, 12.953),
    (0.151, 3.679, 12.751, 0.252, 3.730, 12.953), (0.504, 3.629, 12.751, 0.554, 3.931, 12.802),
    (0.252, 3.629, 12.751, 0.302, 3.931, 12.802), (0.302, 3.629, 12.751, 0.504, 3.881, 12.802),
    (0.328, 3.881, 12.751, 0.378, 3.931, 12.802), (0.428, 3.881, 12.751, 0.479, 3.931, 12.802),
    (0.227, 3.830, 12.802, 0.580, 3.931, 12.953), (0.227, 3.730, 12.802, 0.580, 3.830, 12.953),
    (0.227, 3.629, 12.802, 0.580, 3.730, 12.953), (0.202, 2.722, 12.953, 0.605, 2.772, 13.658),
    (0.504, 2.722, 13.658, 0.605, 2.772, 13.759), (0.202, 2.722, 13.658, 0.302, 2.772, 13.759),
    (0.328, 2.722, 13.709, 0.479, 2.822, 13.759), (0.428, 2.722, 13.658, 0.580, 2.923, 13.709),
    (0.227, 2.722, 13.658, 0.378, 2.923, 13.709), (0.101, 2.570, 13.104, 0.151, 2.822, 13.810),
    (0.655, 2.570, 13.104, 0.706, 2.822, 13.810), (0.151, 2.822, 13.658, 0.202, 2.923, 13.759),
    (0.605, 2.822, 13.658, 0.655, 2.923, 13.759), (0.655, 2.822, 13.457, 0.706, 2.923, 13.507),
    (0.655, 2.822, 13.356, 0.706, 2.974, 13.406), (0.655, 2.822, 13.255, 0.706, 2.923, 13.356),
    (0.101, 2.822, 13.356, 0.151, 2.974, 13.406), (0.554, 2.570, 13.759, 0.605, 2.722, 13.810),
    (0.428, 2.570, 13.759, 0.479, 2.722, 13.810), (0.328, 2.570, 13.759, 0.378, 2.722, 13.810),
    (0.202, 2.570, 13.759, 0.252, 2.722, 13.810), (0.202, 2.570, 11.693, 0.605, 2.722, 12.550),
    (0.151, 2.570, 11.642, 0.202, 2.822, 12.701), (0.605, 2.570, 11.642, 0.655, 2.822, 12.701),
    (0.202, 2.570, 12.550, 0.605, 3.629, 12.701), (0.151, 2.822, 12.348, 0.202, 3.629, 12.701),
    (0.605, 2.822, 12.348, 0.655, 3.629, 12.701), (0.202, 3.024, 12.499, 0.302, 3.528, 12.550),
    (0.202, 2.722, 12.499, 0.605, 2.923, 12.550), (0.504, 3.024, 12.499, 0.605, 3.528, 12.550),
    (0.202, 2.923, 12.499, 0.302, 3.074, 12.550), (0.504, 2.923, 12.499, 0.605, 3.074, 12.550),
    (0.302, 2.923, 12.499, 0.504, 3.528, 12.550), (0.277, 3.074, 12.474, 0.328, 3.276, 12.575),
    (0.479, 3.074, 12.474, 0.529, 3.276, 12.575), (0.479, 3.276, 12.474, 0.529, 3.326, 12.575),
    (0.277, 3.276, 12.474, 0.328, 3.326, 12.575), (0.277, 3.326, 12.474, 0.328, 3.578, 12.575),
    (0.479, 3.326, 12.474, 0.529, 3.578, 12.575), (0.328, 3.528, 12.524, 0.378, 3.629, 12.575),
    (0.428, 3.528, 12.524, 0.479, 3.629, 12.575), (0.151, 2.722, 12.474, 0.328, 3.074, 12.575),
    (0.479, 2.722, 12.474, 0.655, 3.074, 12.575), (0.227, 3.478, 12.524, 0.277, 3.629, 12.575),
    (0.529, 3.478, 12.524, 0.580, 3.629, 12.575), (0.151, 3.629, 12.499, 0.252, 3.679, 12.701),
    (0.151, 3.679, 12.499, 0.252, 3.730, 12.701), (0.554, 3.629, 12.499, 0.655, 3.679, 12.701),
    (0.554, 3.679, 12.499, 0.655, 3.730, 12.701), (0.252, 3.629, 12.650, 0.302, 3.931, 12.701),
    (0.504, 3.629, 12.650, 0.554, 3.931, 12.701), (0.302, 3.629, 12.650, 0.504, 3.881, 12.701),
    (0.428, 3.881, 12.650, 0.479, 3.931, 12.701), (0.328, 3.881, 12.650, 0.378, 3.931, 12.701),
    (0.227, 3.830, 12.499, 0.580, 3.931, 12.650), (0.227, 3.730, 12.499, 0.580, 3.830, 12.650),
    (0.227, 3.629, 12.499, 0.580, 3.730, 12.650), (0.202, 2.722, 11.794, 0.605, 2.772, 12.499),
    (0.202, 2.722, 11.693, 0.302, 2.772, 11.794), (0.504, 2.722, 11.693, 0.605, 2.772, 11.794),
    (0.328, 2.722, 11.693, 0.479, 2.822, 11.743), (0.227, 2.722, 11.743, 0.378, 2.923, 11.794),
    (0.428, 2.722, 11.743, 0.580, 2.923, 11.794), (0.655, 2.570, 11.642, 0.706, 2.822, 12.348),
    (0.101, 2.570, 11.642, 0.151, 2.822, 12.348), (0.605, 2.822, 11.693, 0.655, 2.923, 11.794),
    (0.151, 2.822, 11.693, 0.202, 2.923, 11.794), (0.101, 2.822, 11.945, 0.151, 2.923, 11.995),
    (0.101, 2.822, 12.046, 0.151, 2.974, 12.096), (0.101, 2.822, 12.096, 0.151, 2.923, 12.197),
    (0.655, 2.822, 12.046, 0.706, 2.974, 12.096), (0.202, 2.570, 11.642, 0.252, 2.722, 11.693),
    (0.328, 2.570, 11.642, 0.378, 2.722, 11.693), (0.428, 2.570, 11.642, 0.479, 2.722, 11.693),
    (0.554, 2.570, 11.642, 0.605, 2.722, 11.693), (0.000, 2.570, 11.189, 1.008, 3.276, 11.542),
    (0.806, 2.570, 11.542, 1.008, 2.974, 12.398), (-1.008, 3.276, 12.701, -0.252, 3.679, 12.751),
    (-0.605, 2.570, 12.902, -0.202, 2.722, 13.759), (-0.655, 2.570, 12.751, -0.605, 2.822, 13.810),
    (-0.202, 2.570, 12.751, -0.151, 2.822, 13.810), (-0.605, 2.570, 12.751, -0.202, 3.629, 12.902),
    (-0.655, 2.822, 12.751, -0.605, 3.629, 13.104), (-0.202, 2.822, 12.751, -0.151, 3.629, 13.104),
    (-0.605, 3.024, 12.902, -0.504, 3.528, 12.953), (-0.605, 2.722, 12.902, -0.202, 2.923, 12.953),
    (-0.302, 3.024, 12.902, -0.202, 3.528, 12.953), (-0.605, 2.923, 12.902, -0.504, 3.074, 12.953),
    (-0.302, 2.923, 12.902, -0.202, 3.074, 12.953), (-0.504, 2.923, 12.902, -0.302, 3.528, 12.953),
    (-0.529, 3.074, 12.877, -0.479, 3.276, 12.978), (-0.328, 3.074, 12.877, -0.277, 3.276, 12.978),
    (-0.328, 3.276, 12.877, -0.277, 3.326, 12.978), (-0.529, 3.276, 12.877, -0.479, 3.326, 12.978),
    (-0.529, 3.326, 12.877, -0.479, 3.578, 12.978), (-0.328, 3.326, 12.877, -0.277, 3.578, 12.978),
    (-0.479, 3.528, 12.877, -0.428, 3.629, 12.928), (-0.378, 3.528, 12.877, -0.328, 3.629, 12.928),
    (-0.655, 2.722, 12.877, -0.479, 3.074, 12.978), (-0.328, 2.722, 12.877, -0.151, 3.074, 12.978),
    (-0.580, 3.478, 12.877, -0.529, 3.629, 12.928), (-0.277, 3.478, 12.877, -0.227, 3.629, 12.928),
    (-0.655, 3.629, 12.751, -0.554, 3.679, 12.953), (-0.655, 3.679, 12.751, -0.554, 3.730, 12.953),
    (-0.252, 3.629, 12.751, -0.151, 3.679, 12.953), (-0.252, 3.679, 12.751, -0.151, 3.730, 12.953),
    (-0.554, 3.629, 12.751, -0.504, 3.931, 12.802), (-0.302, 3.629, 12.751, -0.252, 3.931, 12.802),
    (-0.504, 3.629, 12.751, -0.302, 3.881, 12.802), (-0.378, 3.881, 12.751, -0.328, 3.931, 12.802),
    (-0.479, 3.881, 12.751, -0.428, 3.931, 12.802), (-0.580, 3.830, 12.802, -0.227, 3.931, 12.953),
    (-0.580, 3.730, 12.802, -0.227, 3.830, 12.953), (-0.580, 3.629, 12.802, -0.227, 3.730, 12.953),
    (-0.605, 2.722, 12.953, -0.202, 2.772, 13.658), (-0.605, 2.722, 13.658, -0.504, 2.772, 13.759),
    (-0.302, 2.722, 13.658, -0.202, 2.772, 13.759), (-0.479, 2.722, 13.709, -0.328, 2.822, 13.759),
    (-0.580, 2.722, 13.658, -0.428, 2.923, 13.709), (-0.378, 2.722, 13.658, -0.227, 2.923, 13.709),
    (-0.151, 2.570, 13.104, -0.101, 2.822, 13.810), (-0.706, 2.570, 13.104, -0.655, 2.822, 13.810),
    (-0.202, 2.822, 13.658, -0.151, 2.923, 13.759), (-0.655, 2.822, 13.658, -0.605, 2.923, 13.759),
    (-0.706, 2.822, 13.457, -0.655, 2.923, 13.507), (-0.706, 2.822, 13.356, -0.655, 2.974, 13.406),
    (-0.706, 2.822, 13.255, -0.655, 2.923, 13.356), (-0.151, 2.822, 13.356, -0.101, 2.974, 13.406),
    (-0.605, 2.570, 13.759, -0.554, 2.722, 13.810), (-0.479, 2.570, 13.759, -0.428, 2.722, 13.810),
    (-0.378, 2.570, 13.759, -0.328, 2.722, 13.810), (-0.252, 2.570, 13.759, -0.202, 2.722, 13.810),
    (-0.605, 2.570, 11.693, -0.202, 2.722, 12.550), (-0.202, 2.570, 11.642, -0.151, 2.822, 12.701),
    (-0.655, 2.570, 11.642, -0.605, 2.822, 12.701), (-0.605, 2.570, 12.550, -0.202, 3.629, 12.701),
    (-0.202, 2.822, 12.348, -0.151, 3.629, 12.701), (-0.655, 2.822, 12.348, -0.605, 3.629, 12.701),
    (-0.302, 3.024, 12.499, -0.202, 3.528, 12.550), (-0.605, 2.722, 12.499, -0.202, 2.923, 12.550),
    (-0.605, 3.024, 12.499, -0.504, 3.528, 12.550), (-0.302, 2.923, 12.499, -0.202, 3.074, 12.550),
    (-0.605, 2.923, 12.499, -0.504, 3.074, 12.550), (-0.504, 2.923, 12.499, -0.302, 3.528, 12.550),
    (-0.328, 3.074, 12.474, -0.277, 3.276, 12.575), (-0.529, 3.074, 12.474, -0.479, 3.276, 12.575),
    (-0.529, 3.276, 12.474, -0.479, 3.326, 12.575), (-0.328, 3.276, 12.474, -0.277, 3.326, 12.575),
    (-0.328, 3.326, 12.474, -0.277, 3.578, 12.575), (-0.529, 3.326, 12.474, -0.479, 3.578, 12.575),
    (-0.378, 3.528, 12.524, -0.328, 3.629, 12.575), (-0.479, 3.528, 12.524, -0.428, 3.629, 12.575),
    (-0.328, 2.722, 12.474, -0.151, 3.074, 12.575), (-0.655, 2.722, 12.474, -0.479, 3.074, 12.575),
    (-0.277, 3.478, 12.524, -0.227, 3.629, 12.575), (-0.580, 3.478, 12.524, -0.529, 3.629, 12.575),
    (-0.252, 3.629, 12.499, -0.151, 3.679, 12.701), (-0.252, 3.679, 12.499, -0.151, 3.730, 12.701),
    (-0.655, 3.629, 12.499, -0.554, 3.679, 12.701), (-0.655, 3.679, 12.499, -0.554, 3.730, 12.701),
    (-0.302, 3.629, 12.650, -0.252, 3.931, 12.701), (-0.554, 3.629, 12.650, -0.504, 3.931, 12.701),
    (-0.504, 3.629, 12.650, -0.302, 3.881, 12.701), (-0.479, 3.881, 12.650, -0.428, 3.931, 12.701),
    (-0.378, 3.881, 12.650, -0.328, 3.931, 12.701), (-0.580, 3.830, 12.499, -0.227, 3.931, 12.650),
    (-0.580, 3.730, 12.499, -0.227, 3.830, 12.650), (-0.580, 3.629, 12.499, -0.227, 3.730, 12.650),
    (-0.605, 2.722, 11.794, -0.202, 2.772, 12.499), (-0.302, 2.722, 11.693, -0.202, 2.772, 11.794),
    (-0.605, 2.722, 11.693, -0.504, 2.772, 11.794), (-0.479, 2.722, 11.693, -0.328, 2.822, 11.743),
    (-0.378, 2.722, 11.743, -0.227, 2.923, 11.794), (-0.580, 2.722, 11.743, -0.428, 2.923, 11.794),
    (-0.706, 2.570, 11.642, -0.655, 2.822, 12.348), (-0.151, 2.570, 11.642, -0.101, 2.822, 12.348),
    (-0.655, 2.822, 11.693, -0.605, 2.923, 11.794), (-0.202, 2.822, 11.693, -0.151, 2.923, 11.794),
    (-0.151, 2.822, 11.945, -0.101, 2.923, 11.995), (-0.151, 2.822, 12.046, -0.101, 2.974, 12.096),
    (-0.151, 2.822, 12.096, -0.101, 2.923, 12.197), (-0.706, 2.822, 12.046, -0.655, 2.974, 12.096),
    (-0.252, 2.570, 11.642, -0.202, 2.722, 11.693), (-0.378, 2.570, 11.642, -0.328, 2.722, 11.693),
    (-0.479, 2.570, 11.642, -0.428, 2.722, 11.693), (-0.605, 2.570, 11.642, -0.554, 2.722, 11.693),
    (-1.008, 2.570, 11.189, 0.000, 3.276, 11.542), (-1.008, 2.570, 11.542, -0.806, 2.974, 12.398),
    (0.000, 2.570, 14.364, 0.806, 2.923, 14.414), (0.000, 2.923, 14.364, 0.857, 3.125, 14.414),
    (0.000, 3.125, 14.364, 0.706, 3.528, 14.414), (-0.806, 2.570, 14.364, 0.000, 2.923, 14.414),
    (-0.857, 2.923, 14.364, 0.000, 3.125, 14.414), (-0.706, 3.125, 14.364, 0.000, 3.528, 14.414),
    (0.706, 2.570, 13.910, 0.857, 2.923, 14.364), (-0.907, 2.923, 13.810, 0.907, 3.679, 14.364),
    (0.706, 2.570, 13.910, 0.857, 2.923, 14.364), (0.706, 2.570, 12.751, 1.008, 2.923, 13.910),
    (-1.008, 2.570, 12.751, -0.706, 2.923, 13.910), (-0.101, 2.570, 13.810, 0.101, 2.923, 14.414),
    (-0.101, 2.570, 13.406, 0.101, 3.226, 13.810), (-0.101, 2.570, 13.003, 0.101, 3.024, 13.406),
    (0.302, 2.570, 13.810, 0.504, 2.772, 14.364), (0.302, 2.772, 13.961, 0.504, 2.923, 14.364),
    (0.378, 2.740, 13.548, 0.428, 3.158, 13.910), (-0.504, 2.570, 13.810, -0.302, 2.772, 14.364),
    (-0.504, 2.772, 13.961, -0.302, 2.923, 14.364), (-0.428, 2.740, 13.548, -0.378, 3.158, 13.910),
    (0.025, 3.139, 13.593, 0.076, 3.350, 13.810), (-0.076, 3.139, 13.593, -0.025, 3.350, 13.810),
    (-0.076, 3.038, 13.543, -0.025, 3.249, 13.759), (0.025, 3.038, 13.543, 0.076, 3.249, 13.759),
    (-0.605, 3.679, 13.507, -0.554, 3.982, 13.810), (-0.655, 3.931, 13.104, -0.554, 3.982, 13.558),
    (-0.655, 3.931, 12.751, -0.605, 3.982, 13.104), (-0.806, 3.679, 13.054, -0.605, 3.982, 13.104),
    (-0.605, 3.931, 13.507, -0.202, 3.982, 13.709), (-0.202, 3.931, 13.658, 0.000, 3.982, 13.709),
    (-0.302, 3.604, 13.709, -0.151, 3.982, 13.910), (-0.050, 3.629, 13.709, 0.000, 3.982, 13.910),
]
KEEP_BOXES = [
    (0.806, 3.276, 10.937, 1.058, 3.679, 12.751), (0.605, 3.679, 10.937, 0.857, 3.982, 12.751),
    (0.302, 3.982, 10.937, 0.655, 4.133, 12.751), (0.000, 4.133, 10.937, 0.353, 4.284, 12.751),
    (1.008, 2.470, 10.937, 1.058, 3.276, 12.751), (0.000, 1.764, 10.937, 1.058, 2.470, 12.751),
    (0.554, 1.764, 12.751, 1.058, 2.470, 13.558), (0.554, 1.814, 13.558, 0.958, 2.470, 13.910),
    (0.504, 1.814, 13.910, 0.907, 2.470, 14.414), (0.806, 2.470, 13.910, 0.907, 2.974, 14.414),
    (0.857, 2.470, 13.558, 0.958, 3.024, 13.910), (0.907, 2.470, 12.751, 1.058, 3.276, 13.558),
    (0.806, 2.974, 13.558, 0.958, 3.226, 13.910), (0.706, 2.923, 13.910, 0.907, 3.175, 14.414),
    (0.554, 2.822, 14.414, 0.857, 3.125, 15.574), (0.706, 3.226, 12.751, 1.058, 3.679, 13.558),
    (0.554, 3.175, 13.558, 0.958, 3.679, 13.910), (0.403, 3.125, 13.810, 0.857, 3.679, 14.414),
    (0.403, 3.024, 14.414, 0.756, 3.528, 15.574), (0.050, 3.478, 13.810, 0.605, 3.679, 14.414),
    (0.050, 3.175, 14.414, 0.454, 3.578, 15.574), (0.000, 3.528, 13.910, 0.050, 3.679, 14.414),
    (0.000, 3.276, 14.414, 0.050, 3.578, 15.574), (0.000, 2.016, 12.751, 1.008, 2.470, 14.414),
    (0.252, 2.470, 12.701, 1.008, 3.276, 12.751), (0.252, 3.679, 12.701, 0.806, 3.982, 12.751),
    (0.000, 3.982, 12.701, 0.605, 4.133, 12.751), (0.000, 2.470, 10.937, 1.008, 2.570, 12.751),
    (0.000, 2.470, 12.751, 1.008, 2.570, 14.414), (-1.058, 3.276, 10.937, -0.806, 3.679, 12.751),
    (-0.857, 3.679, 10.937, -0.605, 3.982, 12.751), (-0.655, 3.982, 10.937, -0.302, 4.133, 12.751),
    (-0.353, 4.133, 10.937, 0.000, 4.284, 12.751), (-1.058, 2.470, 10.937, -1.008, 3.276, 12.751),
    (-1.058, 1.764, 10.937, 0.000, 2.470, 12.751), (-1.058, 1.764, 12.751, -0.554, 2.470, 13.558),
    (-0.958, 1.814, 13.558, -0.554, 2.470, 13.910), (-0.907, 1.814, 13.910, -0.504, 2.470, 14.414),
    (-0.857, 1.814, 14.414, -0.454, 2.470, 15.574), (-0.857, 2.369, 14.414, -0.655, 2.923, 15.574),
    (-0.907, 2.470, 13.910, -0.806, 2.974, 14.414), (-0.958, 2.470, 13.558, -0.857, 3.024, 13.910),
    (-1.058, 2.470, 12.751, -0.907, 3.276, 13.558), (-0.958, 2.974, 13.558, -0.806, 3.226, 13.910),
    (-0.907, 2.923, 13.910, -0.706, 3.175, 14.414), (-0.857, 2.822, 14.414, -0.554, 3.125, 15.574),
    (-1.058, 3.226, 12.751, -0.706, 3.679, 13.558), (-0.958, 3.175, 13.558, -0.554, 3.679, 13.910),
    (-0.857, 3.125, 13.810, -0.403, 3.679, 14.414), (-0.756, 3.024, 14.414, -0.403, 3.528, 15.574),
    (-0.605, 3.478, 13.810, -0.050, 3.679, 14.414), (-0.454, 3.175, 14.414, -0.050, 3.578, 15.574),
    (-0.050, 3.528, 13.910, 0.000, 3.679, 14.414), (-0.050, 3.276, 14.414, 0.000, 3.578, 15.574),
    (-1.008, 2.016, 12.751, 0.000, 2.470, 14.414), (-1.008, 2.470, 12.701, -0.252, 3.276, 12.751),
    (-0.806, 3.679, 12.701, -0.252, 3.982, 12.751), (-0.605, 3.982, 12.701, 0.000, 4.133, 12.751),
    (-1.008, 2.470, 10.937, 0.000, 2.570, 12.751), (-1.008, 2.470, 12.751, 0.000, 2.570, 14.414),
    (0.454, 1.814, 14.414, 0.857, 2.470, 15.574), (0.655, 2.369, 14.414, 0.857, 2.923, 15.574),
    (-0.202, 1.966, 14.364, 0.202, 3.024, 14.515), (0.202, 2.218, 14.364, 0.252, 3.024, 14.717),
    (-0.252, 2.218, 14.364, -0.202, 3.024, 14.717), (0.101, 2.419, 14.515, 0.202, 2.923, 14.566),
    (-0.202, 2.419, 14.515, -0.101, 2.923, 14.566), (0.101, 2.318, 14.515, 0.202, 2.470, 14.566),
    (-0.202, 2.318, 14.515, -0.101, 2.470, 14.566), (-0.101, 2.318, 14.515, 0.101, 2.923, 14.566),
    (0.076, 2.470, 14.490, 0.126, 2.671, 14.591), (-0.126, 2.470, 14.490, -0.076, 2.671, 14.591),
    (-0.126, 2.671, 14.490, -0.076, 2.722, 14.591), (0.076, 2.671, 14.490, 0.126, 2.722, 14.591),
    (0.076, 2.722, 14.490, 0.126, 2.974, 14.591), (-0.126, 2.722, 14.490, -0.076, 2.974, 14.591),
    (0.025, 2.923, 14.490, 0.076, 3.024, 14.540), (-0.076, 2.923, 14.490, -0.025, 3.024, 14.540),
    (0.076, 2.117, 14.490, 0.252, 2.470, 14.591), (-0.252, 2.117, 14.490, -0.076, 2.470, 14.591),
    (0.126, 2.873, 14.490, 0.176, 3.024, 14.540), (-0.176, 2.873, 14.490, -0.126, 3.024, 14.540),
    (0.151, 3.024, 14.364, 0.252, 3.074, 14.566), (0.151, 3.074, 14.364, 0.252, 3.125, 14.566),
    (-0.252, 3.024, 14.364, -0.151, 3.074, 14.566), (-0.252, 3.074, 14.364, -0.151, 3.125, 14.566),
    (0.101, 3.024, 14.364, 0.151, 3.326, 14.414), (-0.151, 3.024, 14.364, -0.101, 3.326, 14.414),
    (-0.101, 3.024, 14.364, 0.101, 3.276, 14.414), (-0.076, 3.276, 14.364, -0.025, 3.326, 14.414),
    (0.025, 3.276, 14.364, 0.076, 3.326, 14.414), (-0.176, 3.226, 14.414, 0.176, 3.326, 14.566),
    (-0.176, 3.125, 14.414, 0.176, 3.226, 14.566), (-0.176, 3.024, 14.414, 0.176, 3.125, 14.566),
    (0.302, 2.520, 14.414, 0.806, 2.570, 15.523), (0.353, 2.570, 14.414, 0.706, 3.024, 14.868),
    (-0.806, 2.520, 14.414, -0.302, 2.570, 15.523), (-0.706, 2.570, 14.414, -0.353, 3.024, 14.868),
    (0.302, 3.982, 12.751, 0.655, 4.133, 13.104), (0.302, 3.982, 13.104, 0.655, 4.133, 13.558),
    (0.050, 3.982, 13.356, 0.605, 4.133, 13.709), (0.000, 3.982, 13.406, 0.202, 4.133, 13.709),
    (0.000, 4.133, 12.751, 0.353, 4.284, 13.104), (0.050, 4.133, 13.104, 0.353, 4.284, 13.457),
    (0.000, 4.133, 13.104, 0.050, 4.284, 13.457), (-0.655, 3.982, 12.751, -0.302, 4.133, 13.104),
    (-0.655, 3.982, 13.104, -0.302, 4.133, 13.558), (-0.605, 3.982, 13.356, -0.050, 4.133, 13.709),
    (-0.202, 3.982, 13.406, 0.000, 4.133, 13.709), (-0.353, 4.133, 12.751, 0.000, 4.284, 13.104),
    (-0.353, 4.133, 13.104, -0.050, 4.284, 13.457), (-0.050, 4.133, 13.104, 0.000, 4.284, 13.457),
]
EXPECT_CUT = 1453
CUT_TOL = 0.004


def B(x, y, z):
    """source units (toolbox list frame, nose -z) -> vehicle blocks"""
    return np.array([x * S / 16, (y + 7) * S / 16, -(z + 15) * S / 16], float)


def mir(p):
    return np.array([-p[0], p[1], p[2]], float)


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


# ---------------------------------------------------------------- the panes (left side, x > 0; the right side is the mirror)
# corners on the frame centre lines, as (bottom-inner, bottom-outer, top-outer, top-inner) in source units; per edge inset = half the frame's width
# (blocks): bottom, outer (right), top, inner (left)
PANES = {
    'front': ([(0, 66, -291), (5.5, 66, -290.5), (3.5, 71.5, -287), (0, 71.5, -287)], [0.025, 0.025, 0.0225, 0.035]),
    'quarter': ([(5.5, 66, -290.5), (11.5, 66, -288.5), (11.5, 71.5, -284), (3.5, 71.5, -287)], [0.025, 0.025, 0.0225, 0.025]),
    'side_a': ([(11.5, 66, -288.5), (16, 66, -275), (12.5, 71.5, -275), (11.5, 71.5, -284)], [0.025, 0.025, 0.0225, 0.025]),
    'side_b': ([(16, 66, -275), (17, 66, -268), (13, 71.5, -268), (12.5, 71.5, -275)], [0.025, 0.025, 0.0225, 0.025]),
}
INSIDE = B(0, 60, -275)                    # a point inside the cabin: the panes' normals point away from it


def plane_of(P):
    """best-fit plane (point, outward unit normal) of the corners P"""
    c = P.mean(0)
    _, _, vt = np.linalg.svd(P - c)
    n = vt[-1]
    if n @ (c - INSIDE) < 0:
        n = -n
    return c, n


def pane_corners(name, side=1):
    """the pane's four corners projected onto its best-fit plane (so the pane is one exact plane), blocks"""
    pts = np.array([B(*p) for p in PANES[name][0]])
    c, n = plane_of(pts)
    pts = pts - np.outer((pts - c) @ n, n)
    if side < 0:
        pts = np.array([mir(p) for p in pts])
    return pts, n * np.array([side, 1, 1])


def inset_poly(P, d):
    """a convex planar polygon with edge i (P[i] -> P[i+1]) moved in by d[i]; returns the new corners"""
    P = [np.array(p, float) for p in P]
    n = unit(np.cross(P[1] - P[0], P[2] - P[0]))
    lines = []
    for i in range(len(P)):
        a, b = P[i], P[(i + 1) % len(P)]
        u = unit(b - a)
        inward = unit(np.cross(n, u))
        if inward @ (np.mean(P, 0) - a) < 0:
            inward = -inward
        lines.append((a + inward * d[i], u))
    out = []
    for i in range(len(P)):
        (p0, u0), (p1, u1) = lines[i - 1], lines[i]
        A_ = np.array([u0, -u1]).T
        sol = np.linalg.lstsq(A_, p1 - p0, rcond=None)[0]
        out.append(p0 + sol[0] * u0)
    return out


def glass_panes():
    """name, side, glass corners (inset), normal"""
    out = []
    for name, (_c, ins) in PANES.items():
        for side in (1, -1):
            P, n = pane_corners(name, side)
            out.append((name, side, inset_poly(P, ins), n))
    return out


def glass_triangles():
    tris = []
    for name, side, g, n in glass_panes():
        tris.append([g[0], g[1], g[2]])
        tris.append([g[0], g[2], g[3]])
    return tris


# ---------------------------------------------------------------- the sections the checks read (eye-relative to CENTRE)
# right half-sections of the cabin, source units (x, y): from the sill (wall at y 58) up the wall, over the sloping upper wall and the roof to the
# centre line; measured by h6k/measure.py on the cut model (wall x at y 58 / 62 / 66, roof y at x 12 / 8 / 6 / 0)
STATIONS = {
    -244: [(20, 58), (18, 62), (16.5, 66), (14, 69), (12, 72), (10, 73), (8, 74), (6, 75), (0, 75)],
    -266: [(20, 58), (18, 62), (16.5, 66), (14, 69), (12, 72), (10, 73), (8, 74), (6, 75), (0, 75)],
    -270: [(19.7, 58), (17.7, 62), (16.2, 66), (14, 69), (12, 72), (10, 73), (8, 74), (6, 75), (0, 75)],
    -275: [(18.9, 58), (16.9, 62), (15.6, 66), (14, 69), (12, 72), (10, 73), (8, 74), (6, 75), (0, 75)],
    -280: [(18.1, 58), (16.3, 62), (14.9, 66), (13.5, 69), (12, 72), (10, 72.7), (8, 73.8), (6, 75), (0, 75)],
    -285: [(17.2, 58), (15.4, 62), (14, 66), (13, 68.5), (12, 71), (10, 71.8), (8, 72), (6, 72.2), (0, 72.6)],
    -289: [(15.4, 58), (12.9, 62), (8, 64.9), (4, 65.8), (0, 66.5)],
    -291: [(15.0, 58), (11.0, 62), (6, 65.4), (0, 66.0)],
}


def section_abs(zs):
    return [tuple(B(x, y, zs)[:2]) for x, y in STATIONS[zs]]


def outline_rel():
    out = []
    for zs in sorted(STATIONS, key=lambda v: B(0, 0, v)[2]):
        z = B(0, 0, zs)[2]
        out.append((z - CENTRE[2], [(x - CENTRE[0], y - CENTRE[1]) for x, y in section_abs(zs)]))
    return out


CANOPY = outline_rel()                     # as handbuilt_canopy.Shape wants it (the checks read this)


def half_section(z_rel):
    """the cabin's right half-section at a z relative to CENTRE, eye-relative (the cockpit script reads this)"""
    import handbuilt_canopy as C
    sh = C.Shape(CANOPY)
    return [tuple(p) for p in sh.section(z_rel)]


# ---------------------------------------------------------------- mesher (closed solids)

class Solids:
    """faces of the frame members as a poly mesh in model pixels; each face has one winding, outward from the solid. Points are vehicle blocks"""

    def __init__(self, uv):
        self.P, self.N, self.U, self.polys, self.uv = [], [], [], [], uv

    def face(self, pts, n, uv=None):
        px = [[float(q[0]) * 16, float(q[1]) * 16, -float(q[2]) * 16] for q in pts]
        npx = np.array([n[0], n[1], -n[2]])
        a, b, c = (np.array(q) for q in px[:3])
        # the mesh loader mirrors X: in file pixels a face winds clockwise seen from its front (the hull's own faces do the same)
        if np.cross(b - a, c - a) @ npx > 0:
            px = px[::-1]
        ni = len(self.N)
        self.N.append([round(float(x), 5) + 0.0 for x in -npx])
        ui = len(self.U)
        self.U.append(list(uv or self.uv))
        poly = []
        for p in px:
            poly.append([len(self.P), ni, ui])
            self.P.append([round(x, 4) + 0.0 for x in p])
        self.polys.append(poly)

    def quad(self, q, inside, uv=None):
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
        self.face(q, n, uv)

    def obox(self, c, axes, half, uv=None):
        c = np.array(c, float)
        A = [unit(a) for a in axes]
        for k in range(3):
            for s in (-1, 1):
                i, j = [m for m in range(3) if m != k]
                fc = c + A[k] * half[k] * s
                q = [fc + A[i] * half[i] * a + A[j] * half[j] * b for a, b in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
                self.quad(q, c, uv)

    def bar(self, a, b, w, n, t=0.05, po=0.012, ext=None, uv=None):
        """a straight member from a to b: w wide across the surface, t thick along the outward normal n, standing po proud of the
        surface line a-b; its ends run on by [ext] (default half the width) so neighbouring members overlap at the joints"""
        a, b = np.array(a, float), np.array(b, float)
        u = unit(b - a)
        nn = np.array(n, float)
        nn = unit(nn - (nn @ u) * u)
        s = np.cross(u, nn)
        L_ = np.linalg.norm(b - a)
        e = w / 2 if ext is None else ext
        c = (a + b) / 2 + nn * (po - t / 2)
        self.obox(c, (u, s, nn), (L_ / 2 + e, w / 2, t / 2), uv)

    def mesh(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}


# ---------------------------------------------------------------- frames
def build_solids():
    b = Solids(UV_FRAME)
    nf = {name: pane_corners(name, 1)[1] for name in PANES}          # the left panes' outward normals (the right ones mirror x)
    pts = {name: [B(*p) for p in PANES[name][0]] for name in PANES}   # centre-line corners (blocks)

    def post(a, c, w, n, t=0.045, po=0.012, ext=None):
        for s in (1, -1):
            sx = (lambda p: np.array([s * p[0], p[1], p[2]]))
            b.bar(sx(a), sx(c), w, np.array([s * n[0], n[1], n[2]]), t=t, po=po, ext=ext)
    f, q, sa, sb = pts['front'], pts['quarter'], pts['side_a'], pts['side_b']
    # the centre post (one member across x = 0, 7 cm wide) between the two front panes
    b.bar(f[0] * np.array([0, 1, 1]), f[3] * np.array([0, 1, 1]), 0.07, nf['front'], t=0.055, po=0.014)
    post(f[1], f[2], 0.05, nf['front'])                       # the front post between the front and quarter panes
    post(q[1], q[2], 0.05, unit(nf['quarter'] + nf['side_a']))   # the corner post between the quarter and the first side pane
    post(sa[1], sa[2], 0.05, unit(nf['side_a'] + nf['side_b']))  # the side post between the side panes
    post(sb[1], sb[2], 0.05, nf['side_b'])                    # the post at the seat-back bulkhead
    # the roof rails along the roof's front edge: front pane top, quarter pane top, side panes' tops
    post(f[3], f[2], 0.045, nf['front'], ext=0.0)
    post(q[3], q[2], 0.045, nf['quarter'], ext=0.0)
    post(sa[3], sa[2], 0.045, nf['side_a'], ext=0.0)
    post(sb[3], sb[2], 0.045, nf['side_b'], ext=0.0)
    # the deck rails along the lower edge
    post(f[0], f[1], 0.05, nf['front'], ext=0.0)
    post(q[0], q[1], 0.05, nf['quarter'], ext=0.0)
    post(sa[0], sa[1], 0.05, nf['side_a'], ext=0.0)
    post(sb[0], sb[1], 0.05, nf['side_b'], ext=0.0)
    return b


# ---------------------------------------------------------------- the airframe

def cut_frame(bones, already):
    """removes the model's own frames, interiors and seats (CUT_BOXES) from the hull pieces: every face wholly inside a cut box and inside no kept
    box. The first run must find EXPECT_CUT faces; returns the faces removed"""
    gone = {}
    box = np.array(CUT_BOXES, float)
    lo, hi = box[:, :3] - CUT_TOL, box[:, 3:] + CUT_TOL
    kb = np.array(KEEP_BOXES, float)
    klo, khi = kb[:, :3] - CUT_TOL, kb[:, 3:] + CUT_TOL
    for name in HULLS:
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


def flat_config(tris_by_pane):
    """flat_panes/h_6k.json: every pane's box (|x|, mirrored), tight round its glass"""
    panes = []
    for name, side, g, n in glass_panes():
        if side < 0:
            continue
        P = np.array(g)
        lo, hi = P.min(0) - 0.004, P.max(0) + 0.004
        panes.append({'name': 'flat ' + name.replace('_', ' ') + ' pane', 'box': [[round(float(lo[0]), 4), round(float(lo[1]), 4), round(float(lo[2]), 4)],
                                                                           [round(float(hi[0]), 4), round(float(hi[1]), 4), round(float(hi[2]), 4)]],
                      'mirror': True})
    return {'why': ("H-6K flight deck: a solid radome nose and a flight deck set back under a stepped, raised windscreen of flat panes (the Tu-16 family "
                    "uses flat armoured glass: a centre post between two front panes either side, then side windows). The model's own window band is the "
                    "frame layout (canopy_h_6k.py): per side a front pane and a quarter pane either side of the centre / corner posts and two side panes "
                    "before and behind a side post, each one exact plane (the best fit through its frame corners). The pane count and sizes are the "
                    "model's line, UNVERIFIED against photographs of the real H-6K. The roof is metal and closed: no overhead panes."),
            'panes': panes}


def main(argv):
    global UV_FRAME
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    sw = L.Swatches(TEXTURE)
    UV_FRAME = [float(v) for v in sw.uv(GREY_FRAME)]
    bars_bone = next((b for b in bones if b['name'] == BARS_BONE), None)
    if bars_bone is None:
        at = next(i for i, b in enumerate(bones) if b['name'] == H0) + 1
        bars_bone = {'name': BARS_BONE, 'parent': 'hull', 'pivot': list(next(b for b in bones if b['name'] == 'hull')['pivot'])}
        bones.insert(at, bars_bone)
    already = bool(bars_bone.get('poly_mesh', {}).get('polys'))
    cut = cut_frame(bones, already)
    solids = build_solids()
    bars_bone['poly_mesh'] = solids.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f"h_6k canopy: the model's frames, interiors and seats cut out ({cut} faces), {len(solids.polys)} faces of frames; "
          f'{len(tris)} glass triangles (x2 windings): front, quarter and two side panes either side')
    if '--flat' in argv:
        with open(FLAT, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(flat_config(None), f, indent=2, ensure_ascii=False)
            f.write('\n')
        print('wrote', FLAT)
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
