#!/usr/bin/env python3
"""Avro Vulcan B.2 flight-deck canopy, hand-built for the Avro Vulcan.mtb airframe (commits 0cf09ae6, af3e063b): the small
fighter-style canopy over the two pilots, side by side, on the model's own fuselage.

The model's cockpit zone (vehicle blocks, 1 block = 1 m on this model; x to the left, y up, z forward; read off the untouched
model, checks/orig, and the source elements in vulcan/cutlist.txt): the pilots' floor is a slab with its top at y 4.123 between
z 11.155 and 12.90; the solid nose block starts at z 12.90 (deck top y 4.947); the fuselage walls stand at |x| 0.82 .. 0.87 above
y 4.41 (the shoulder caps' top y 4.947) and 1.07 .. 1.16 below; behind z 11.155 the model's dorsal hump (rows 1171 / 1191,
a grey shell, crown y 5.72) covers the rear crew compartment: that hump is exterior skin and stays. The model's own frames over
the pilots (the arch lumps, rails, the old centre bars and sight), its interiors and all seat groups are cut by element boxes
(CUT_BOXES; faces that also lie in a kept element's box stay). The shoulders, the pilots' floor, the rear floor and the nose
block are exterior and stay.

The real Vulcan B.2 (descriptions: a five-panel windscreen with its framing and small round side windows; small thick heavily framed
panes; pilots complained about the view; no photograph of the pane layout was reachable, so every number is the model's own line,
UNVERIFIED against the real jet): a mostly metal fairing blended into the dorsal hump with a wrapping row of small panes. Here the
canopy takes its layout from the original model's own frame lumps (checks/orig):

  * mould line: the model's own crown line (5.72 at the hump falling to 5.43 at the arch, z 12.12) as a smooth superellipse skin in the
    hump's grey, cut by the windscreen row's facets, which follow the model's own windscreen bars (element 125 at x 0.19 .. 0.24, y 5.43
    at z 12.12 to 5.09 at 12.66; elements 124 / 122 at x 0.58 .. 0.73) and its rounded plan: the footprint narrows round the nose;
  * windscreen: FIVE flat panes in a wrapping row, each in its own plane (a parallelogram of a post line and a horizontal-ish
    direction, planar by construction): the centre opening between the two inner posts (x +-0.215), the pane in front of each pilot
    (the captain's eye stands in the middle of his), and a corner pane each side wrapping round; opaque cowl below on the same planes;
  * one small flat side opening each side: the slit the original leaves between its rail and its shoulder lumps (z 11.45 .. 11.80);
  * NO roof windows (the original has none);
  * frames (bone wreck_fuselage_1__canopy, closed solids, 6 cm, airframe grey): a slim bar round every pane (the posts, the arch along
    the tops, the lower edges), sill rails, the rear bow; glass stops half a frame inside each outline.

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read. The checks measure from the pilot's eye: here the captain's eye stands 0.4365 off the canopy
axis, so they are run through vulcan/check_vulcan.py, which sets the origin to the canopy axis (x 0, the eye's y and z).

Run first (on the airframe's files); the cockpit script vulcan_b2.py follows.
Usage: python tools/cockpit_models/canopy_vulcan_b2.py [--dry-run]"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo/vulcan_b2.geo.json')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles/vulcan_b2.json')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles/vulcan_b2.json')
TEXTURE = L.texture_path('vulcan_b2')


def _eyes():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        A = d['Attachments']
        return np.array(A['pilot_eye']['Position'], float), np.array(A['copilot_eye']['Position'], float)
    except (OSError, KeyError):
        return np.array([0.4365, 5.335, 11.5915]), np.array([-0.4365, 5.335, 11.5915])


EYE, EYE_C = _eyes()                       # the captain's and the co-pilot's eyes (vehicle blocks)
CENTRE = np.array([0.0, EYE[1], EYE[2]])   # the canopy axis at the eyes' height: the origin of CANOPY and of the checks
BARS_BONE = 'wreck_fuselage_1__canopy'     # (an empty bone of the airframe: the frames go with the forward fuselage section)
H0 = 'wreck_fuselage_1__hull'
HULLS = ('wreck_fuselage_1__hull', 'wreck_fuselage_0__hull', 'wreck_fuselage_2__hull', 'wreck_fuselage_3__hull')
GREY_PANEL = (99, 105, 105)                # the hump's own grey (the canopy skin between the windows)
GREY_FRAME = (62, 66, 66)                  # frames: a darker airframe grey
UV_FRAME = None
UV_PANEL = None

# ---- the model's frames, interiors and seat groups, cut out: every face of the hull pieces lying wholly inside one of CUT_BOXES
# and in none of KEEP_BOXES (absolute blocks, x0 y0 z0 x1 y1 z1; the source elements listed by vulcan/gen_cutboxes.py: the
# interior rows 1872 / 2018 / 1904 / 1935 / 1981 / 1835 (except the floor slabs 637 / 638 and the lower rear bulkhead blocks 719 / 724),
# the old sight bars 855-860 and the frame elements of rows 1171 / 1191 from z 11.155 forward that stand over the shoulder caps).
# EXPECT_CUT is the face count the first run must find (the airframe's face list must be the committed one); a second run finds none.
CUT_BOXES = [
    (0.825, 4.947, 11.155, 0.873, 5.093, 11.737), (0.825, 5.093, 11.446, 0.873, 5.141, 11.737),
    (0.825, 5.093, 11.155, 0.873, 5.141, 11.446), (0.776, 5.093, 11.155, 0.873, 5.238, 11.300),
    (0.582, 5.093, 11.155, 0.873, 5.626, 11.300), (0.582, 5.383, 11.155, 0.776, 5.626, 11.446),
    (0.582, 5.383, 11.446, 0.728, 5.578, 11.883), (0.582, 5.238, 11.591, 0.824, 5.432, 11.883),
    (0.679, 5.093, 11.591, 0.873, 5.238, 11.931), (0.582, 5.238, 11.640, 0.825, 5.432, 12.077),
    (0.340, 5.578, 11.155, 0.631, 5.723, 11.446), (0.242, 5.432, 11.446, 0.631, 5.675, 11.931),
    (0.194, 5.335, 11.883, 0.631, 5.529, 12.125), (0.000, 5.626, 11.155, 0.436, 5.723, 11.446),
    (0.000, 5.481, 11.446, 0.436, 5.675, 11.931), (0.000, 5.383, 11.931, 0.340, 5.529, 12.125),
    (0.679, 4.947, 11.737, 0.873, 5.093, 12.707), (0.388, 4.947, 12.707, 0.728, 4.995, 12.901),
    (0.340, 4.947, 12.659, 0.436, 5.141, 12.901), (0.631, 4.947, 12.465, 0.728, 5.141, 12.707),
    (0.388, 4.947, 12.659, 0.728, 5.141, 12.901), (0.582, 5.093, 12.028, 0.679, 5.335, 12.465),
    (0.194, 5.093, 12.125, 0.242, 5.432, 12.659), (0.000, 4.947, 12.659, 0.388, 5.141, 12.901),
    (0.000, 4.899, 12.901, 0.436, 4.995, 12.950), (-0.873, 4.947, 11.155, -0.825, 5.093, 11.737),
    (-0.873, 5.093, 11.446, -0.825, 5.141, 11.737), (-0.873, 5.093, 11.155, -0.825, 5.141, 11.446),
    (-0.873, 5.093, 11.155, -0.776, 5.238, 11.300), (-0.873, 5.093, 11.155, -0.582, 5.626, 11.300),
    (-0.776, 5.383, 11.155, -0.582, 5.626, 11.446), (-0.728, 5.383, 11.446, -0.582, 5.578, 11.883),
    (-0.824, 5.238, 11.591, -0.582, 5.432, 11.883), (-0.873, 5.093, 11.591, -0.679, 5.238, 11.931),
    (-0.825, 5.238, 11.640, -0.582, 5.432, 12.077), (-0.631, 5.578, 11.155, -0.340, 5.723, 11.446),
    (-0.436, 5.626, 11.155, 0.000, 5.723, 11.446), (-0.873, 4.947, 11.737, -0.679, 5.093, 12.707),
    (-0.728, 4.947, 12.707, -0.388, 4.995, 12.901), (-0.436, 4.947, 12.659, -0.340, 5.141, 12.901),
    (-0.728, 4.947, 12.465, -0.631, 5.141, 12.707), (-0.728, 4.947, 12.659, -0.388, 5.141, 12.901),
    (-0.679, 5.093, 12.028, -0.582, 5.335, 12.465), (-0.388, 4.947, 12.659, 0.000, 5.141, 12.901),
    (-0.436, 4.899, 12.901, 0.000, 4.995, 12.950), (0.097, 4.365, 11.349, 0.776, 5.044, 11.446),
    (0.097, 4.123, 11.349, 0.776, 4.365, 12.028), (0.728, 4.365, 11.446, 0.776, 5.044, 11.591),
    (0.097, 4.365, 11.446, 0.146, 5.044, 11.591), (0.242, 5.044, 11.349, 0.631, 5.432, 11.398),
    (0.146, 4.365, 11.446, 0.728, 4.413, 12.028), (0.146, 4.413, 11.446, 0.728, 5.044, 11.495),
    (0.533, 4.850, 11.446, 0.631, 5.044, 11.495), (0.533, 4.753, 11.446, 0.631, 4.850, 11.495),
    (0.533, 4.413, 11.446, 0.631, 4.753, 11.495), (0.242, 4.850, 11.446, 0.340, 5.044, 11.495),
    (0.242, 4.753, 11.446, 0.340, 4.850, 11.495), (0.242, 4.413, 11.446, 0.340, 4.753, 11.495),
    (0.242, 5.044, 11.300, 0.631, 5.432, 11.349), (0.582, 4.365, 11.689, 0.776, 4.462, 11.786),
    (0.533, 4.365, 11.689, 0.582, 4.462, 11.786), (0.097, 4.365, 11.689, 0.291, 4.462, 11.786),
    (0.291, 4.365, 11.689, 0.340, 4.462, 11.786), (0.728, 4.511, 11.543, 0.776, 4.607, 11.834),
    (0.097, 4.511, 11.543, 0.146, 4.607, 11.834), (-0.776, 4.365, 11.349, -0.097, 5.044, 11.446),
    (-0.776, 4.123, 11.349, -0.097, 4.365, 12.028), (-0.776, 4.365, 11.446, -0.728, 5.044, 11.591),
    (-0.146, 4.365, 11.446, -0.097, 5.044, 11.591), (-0.631, 5.044, 11.349, -0.242, 5.432, 11.398),
    (-0.728, 4.365, 11.446, -0.146, 4.413, 12.028), (-0.728, 4.413, 11.446, -0.146, 5.044, 11.495),
    (-0.631, 4.850, 11.446, -0.533, 5.044, 11.495), (-0.631, 4.753, 11.446, -0.533, 4.850, 11.495),
    (-0.631, 4.413, 11.446, -0.533, 4.753, 11.495), (-0.340, 4.850, 11.446, -0.242, 5.044, 11.495),
    (-0.340, 4.753, 11.446, -0.242, 4.850, 11.495), (-0.340, 4.413, 11.446, -0.242, 4.753, 11.495),
    (-0.631, 5.044, 11.300, -0.242, 5.432, 11.349), (-0.776, 4.365, 11.689, -0.582, 4.462, 11.786),
    (-0.582, 4.365, 11.689, -0.533, 4.462, 11.786), (-0.291, 4.365, 11.689, -0.097, 4.462, 11.786),
    (-0.340, 4.365, 11.689, -0.291, 4.462, 11.786), (-0.776, 4.511, 11.543, -0.728, 4.607, 11.834),
    (-0.146, 4.511, 11.543, -0.097, 4.607, 11.834), (-0.097, 4.123, 12.222, 0.097, 4.413, 12.901),
    (-0.097, 4.413, 12.222, 0.097, 4.559, 12.901), (0.000, 4.559, 12.222, 1.018, 4.899, 12.901),
    (0.388, 4.899, 12.222, 0.873, 4.947, 12.901), (0.388, 4.947, 12.222, 0.776, 5.189, 12.901),
    (0.000, 4.899, 12.222, 0.388, 5.189, 12.901), (0.776, 4.413, 12.222, 1.067, 4.559, 12.901),
    (0.776, 4.123, 12.222, 1.164, 4.413, 12.901), (-1.018, 4.559, 12.222, 0.000, 4.899, 12.901),
    (-0.873, 4.899, 12.222, -0.388, 4.947, 12.901), (-0.776, 4.947, 12.222, -0.388, 5.189, 12.901),
    (-0.388, 4.899, 12.222, 0.000, 5.189, 12.901), (-1.067, 4.413, 12.222, -0.776, 4.559, 12.901),
    (-1.164, 4.123, 12.222, -0.776, 4.413, 12.901), (0.776, 4.123, 11.349, 1.164, 4.413, 12.222),
    (0.776, 4.413, 11.349, 1.115, 4.705, 12.222), (-1.164, 4.123, 11.349, -0.776, 4.413, 12.222),
    (-1.115, 4.413, 11.349, -0.776, 4.705, 12.222), (-0.097, 4.123, 11.591, 0.097, 4.413, 12.222),
    (-0.097, 4.413, 12.077, 0.097, 4.559, 12.222), (-0.097, 4.413, 12.222, 0.097, 4.559, 12.901),
    (0.340, 4.123, 12.222, 0.533, 4.559, 12.901), (0.340, 4.123, 12.174, 0.533, 4.559, 12.222),
    (0.340, 4.123, 12.028, 0.533, 4.413, 12.174), (0.582, 4.123, 12.367, 0.728, 4.268, 12.707),
    (0.631, 4.171, 12.707, 0.679, 4.220, 12.901), (0.194, 4.171, 12.707, 0.242, 4.220, 12.901),
    (0.146, 4.123, 12.367, 0.291, 4.268, 12.707), (-0.533, 4.123, 12.222, -0.340, 4.559, 12.901),
    (-0.533, 4.123, 12.174, -0.340, 4.559, 12.222), (-0.533, 4.123, 12.028, -0.340, 4.413, 12.174),
    (-0.728, 4.123, 12.367, -0.582, 4.268, 12.707), (-0.679, 4.171, 12.707, -0.631, 4.220, 12.901),
    (-0.242, 4.171, 12.707, -0.194, 4.220, 12.901), (-0.291, 4.123, 12.367, -0.146, 4.268, 12.707),
    (0.000, 5.141, 12.125, 0.679, 5.189, 12.222), (0.631, 4.947, 12.125, 0.776, 5.189, 12.222),
    (-0.679, 5.141, 12.125, 0.000, 5.189, 12.222), (-0.776, 4.947, 12.125, -0.631, 5.189, 12.222),
    (-0.631, 5.432, 11.446, -0.242, 5.675, 11.931), (-0.436, 5.481, 11.446, 0.000, 5.675, 11.931),
    (-0.631, 5.335, 11.883, -0.194, 5.529, 12.125), (-0.340, 5.383, 11.931, 0.000, 5.529, 12.125),
    (-0.242, 5.093, 12.125, -0.194, 5.432, 12.659), (0.024, 4.519, 11.996, 0.073, 4.698, 12.222),
    (0.024, 4.422, 11.947, 0.073, 4.601, 12.174), (-0.073, 4.422, 11.947, -0.024, 4.601, 12.174),
    (-0.073, 4.519, 11.996, -0.024, 4.698, 12.222), (0.000, 5.626, 11.155, 0.582, 5.675, 11.204),
    (0.000, 5.093, 11.155, 0.825, 5.626, 11.204), (0.728, 4.947, 11.155, 0.825, 5.093, 11.204),
    (0.728, 4.899, 11.155, 0.873, 4.947, 11.204), (0.728, 4.413, 11.155, 1.115, 4.899, 11.204),
    (0.728, 4.123, 11.155, 1.164, 4.413, 11.204), (-0.582, 5.626, 11.155, 0.000, 5.675, 11.204),
    (-0.825, 5.093, 11.155, 0.000, 5.626, 11.204), (-0.825, 4.947, 11.155, -0.728, 5.093, 11.204),
    (-0.873, 4.899, 11.155, -0.728, 4.947, 11.204), (-1.115, 4.413, 11.155, -0.728, 4.899, 11.204),
    (-1.164, 4.123, 11.155, -0.728, 4.413, 11.204), (-0.291, 3.880, 11.107, 0.291, 3.929, 11.155),
    (-0.291, 3.686, 11.107, 0.291, 3.735, 11.155), (0.242, 3.153, 11.107, 0.291, 4.123, 11.155),
    (-0.291, 3.153, 11.107, -0.242, 4.123, 11.155), (-0.242, 5.044, 12.174, 0.242, 5.141, 12.222),
    (0.000, 4.995, 8.585, 0.776, 5.287, 8.973), (0.000, 4.899, 8.585, 0.873, 4.995, 8.973),
    (0.000, 4.413, 8.488, 1.164, 4.899, 8.779), (0.000, 3.929, 8.488, 1.164, 4.413, 8.779),
    (0.000, 3.492, 8.488, 1.164, 3.929, 8.779), (-0.873, 4.899, 8.585, 0.000, 4.995, 8.973),
    (-1.164, 4.413, 8.488, 0.000, 4.899, 8.779), (-1.164, 3.929, 8.488, 0.000, 4.413, 8.779),
    (-1.164, 3.492, 8.488, 0.000, 3.929, 8.779), (0.000, 3.880, 8.779, 1.164, 3.977, 9.264),
    (0.291, 3.589, 9.312, 0.679, 3.638, 9.894), (0.242, 3.589, 9.312, 0.291, 3.735, 9.797),
    (0.242, 3.589, 9.797, 0.291, 3.735, 9.991), (0.291, 3.589, 9.894, 0.679, 3.735, 9.991),
    (0.243, 3.735, 9.894, 0.291, 4.365, 9.991), (0.242, 3.832, 9.457, 0.291, 3.880, 9.894),
    (0.218, 3.880, 9.409, 0.315, 3.929, 9.700), (0.679, 3.589, 9.312, 0.728, 3.735, 9.797),
    (0.679, 3.832, 9.457, 0.728, 3.880, 9.894), (0.655, 3.880, 9.409, 0.752, 3.929, 9.700),
    (0.679, 3.589, 9.797, 0.728, 3.735, 9.991), (0.291, 3.638, 9.312, 0.679, 3.686, 9.894),
    (0.291, 3.662, 9.506, 0.388, 3.710, 9.652), (0.582, 3.662, 9.506, 0.679, 3.710, 9.652),
    (0.533, 3.662, 9.506, 0.582, 3.710, 9.555), (0.533, 3.662, 9.603, 0.582, 3.710, 9.652),
    (0.388, 3.662, 9.555, 0.436, 3.710, 9.603), (0.315, 3.880, 9.845, 0.412, 4.413, 9.894),
    (0.558, 3.880, 9.845, 0.655, 4.413, 9.894), (0.558, 3.783, 9.845, 0.655, 3.880, 9.894),
    (0.315, 3.783, 9.845, 0.412, 3.880, 9.894), (0.436, 3.444, 9.603, 0.533, 3.589, 9.700),
    (0.291, 4.317, 9.894, 0.679, 4.365, 9.991), (0.267, 3.735, 9.894, 0.703, 4.317, 9.991),
    (0.679, 3.735, 9.894, 0.728, 4.365, 9.991), (0.243, 4.365, 9.894, 0.728, 4.413, 9.991),
    (-0.873, 3.444, 8.779, -0.582, 3.735, 9.069), (-0.582, 3.589, 9.312, -0.194, 3.638, 9.894),
    (-0.631, 3.589, 9.312, -0.582, 3.735, 9.797), (-0.631, 3.589, 9.797, -0.582, 3.735, 9.991),
    (-0.582, 3.589, 9.894, -0.194, 3.735, 9.991), (-0.631, 3.735, 9.894, -0.582, 4.365, 9.991),
    (-0.631, 3.832, 9.457, -0.582, 3.880, 9.894), (-0.655, 3.880, 9.409, -0.558, 3.929, 9.700),
    (-0.194, 3.589, 9.312, -0.146, 3.735, 9.797), (-0.194, 3.832, 9.457, -0.145, 3.880, 9.894),
    (-0.218, 3.880, 9.409, -0.121, 3.929, 9.700), (-0.194, 3.589, 9.797, -0.145, 3.735, 9.991),
    (-0.582, 3.638, 9.312, -0.194, 3.686, 9.894), (-0.582, 3.662, 9.506, -0.485, 3.710, 9.652),
    (-0.291, 3.662, 9.506, -0.194, 3.710, 9.652), (-0.340, 3.662, 9.506, -0.291, 3.710, 9.555),
    (-0.340, 3.662, 9.603, -0.291, 3.710, 9.652), (-0.485, 3.662, 9.555, -0.436, 3.710, 9.603),
    (-0.558, 3.880, 9.845, -0.461, 4.413, 9.894), (-0.315, 3.880, 9.845, -0.218, 4.413, 9.894),
    (-0.315, 3.783, 9.845, -0.218, 3.880, 9.894), (-0.558, 3.783, 9.845, -0.461, 3.880, 9.894),
    (-0.436, 3.444, 9.603, -0.340, 3.589, 9.700), (-0.582, 4.317, 9.894, -0.194, 4.365, 9.991),
    (-0.606, 3.735, 9.894, -0.170, 4.317, 9.991), (-0.194, 3.735, 9.894, -0.145, 4.365, 9.991),
    (-0.631, 4.365, 9.894, -0.145, 4.413, 9.991), (0.582, 3.444, 8.779, 0.873, 3.735, 9.069),
    (-1.164, 3.880, 8.779, 0.000, 3.977, 9.264), (0.000, 3.735, 8.779, 1.164, 3.880, 9.264),
    (-1.164, 3.735, 8.779, 0.000, 3.880, 9.264), (-0.776, 4.995, 8.585, 0.000, 5.287, 8.973),
    (0.825, 4.413, 8.681, 1.164, 4.899, 8.973), (0.000, 4.413, 8.681, 0.825, 4.899, 8.973),
    (-1.164, 4.413, 8.681, -0.825, 4.899, 8.973), (-0.825, 4.413, 8.681, 0.000, 4.899, 8.973),
    (-0.582, 4.026, 8.779, -0.194, 4.413, 8.827), (-0.533, 4.171, 8.827, -0.242, 4.268, 8.876),
    (-0.533, 4.268, 8.827, -0.242, 4.365, 8.876), (-0.533, 4.074, 8.827, -0.242, 4.171, 8.876),
    (1.115, 3.880, 8.779, 1.164, 4.413, 9.748), (1.018, 4.413, 8.779, 1.164, 4.656, 9.748),
    (-1.164, 3.880, 8.779, -1.115, 4.413, 9.748), (-1.164, 4.413, 8.779, -1.018, 4.656, 9.748),
    (-0.242, 2.667, 10.088, 0.242, 3.541, 10.912), (-0.242, 2.667, 10.912, 0.242, 3.541, 11.010),
    (-0.242, 2.667, 9.991, 0.242, 3.541, 10.088), (0.291, 4.098, 8.827, 0.485, 4.389, 9.118),
    (0.291, 4.195, 8.827, 0.485, 4.438, 9.167), (0.291, 4.050, 8.827, 0.485, 4.292, 9.069),
    (0.242, 4.026, 8.779, 0.533, 4.268, 8.827), (-0.340, 4.850, 8.924, -0.291, 4.899, 9.021),
    (-0.340, 4.753, 8.973, -0.291, 4.899, 9.118), (-0.340, 4.656, 9.069, -0.291, 4.753, 9.118),
    (-0.364, 4.511, 9.045, -0.267, 4.656, 9.142), (-0.752, 4.511, 9.045, -0.655, 4.656, 9.142),
    (-0.728, 4.656, 9.069, -0.679, 4.753, 9.118), (-0.728, 4.753, 8.973, -0.679, 4.899, 9.118),
    (-0.728, 4.850, 8.924, -0.679, 4.899, 9.021), (0.291, 4.850, 8.924, 0.340, 4.899, 9.021),
    (0.291, 4.753, 8.973, 0.340, 4.899, 9.118), (0.291, 4.656, 9.069, 0.340, 4.753, 9.118),
    (0.267, 4.511, 9.045, 0.364, 4.656, 9.142), (0.655, 4.511, 9.045, 0.752, 4.656, 9.142),
    (0.679, 4.656, 9.069, 0.728, 4.753, 9.118), (0.679, 4.753, 8.973, 0.728, 4.899, 9.118),
    (0.679, 4.850, 8.924, 0.728, 4.899, 9.021), (-0.024, 5.189, 12.125, 0.024, 5.287, 12.174),
    (-0.073, 5.238, 12.125, 0.073, 5.287, 12.174), (0.267, 5.141, 12.416, 0.315, 5.287, 12.610),
    (0.267, 5.238, 12.222, 0.315, 5.383, 12.416), (0.267, 5.093, 12.416, 0.364, 5.287, 12.659),
    (-0.315, 5.141, 12.416, -0.267, 5.287, 12.610), (-0.315, 5.238, 12.222, -0.267, 5.383, 12.416),
    (-0.364, 5.093, 12.416, -0.267, 5.287, 12.659),
]
KEEP_BOXES = [
    (0.825, 4.413, 12.707, 1.115, 4.899, 12.901), (0.825, 4.413, 11.737, 1.164, 4.899, 12.707),
    (0.873, 4.413, 11.155, 1.164, 4.899, 11.737), (0.873, 4.413, 10.524, 1.164, 4.899, 11.155),
    (0.873, 4.413, 9.748, 1.213, 4.899, 10.524), (0.873, 4.413, 8.488, 1.213, 4.899, 9.748),
    (1.067, 3.929, 12.707, 1.213, 4.413, 12.901), (1.067, 3.929, 11.737, 1.213, 4.413, 12.707),
    (1.115, 3.929, 11.155, 1.213, 4.413, 11.737), (1.115, 3.929, 10.524, 1.213, 4.413, 11.155),
    (0.000, 3.492, 12.707, 1.213, 3.929, 12.901), (0.000, 3.492, 11.737, 1.213, 3.929, 12.707),
    (0.000, 3.492, 11.155, 1.213, 3.929, 11.737), (1.164, 3.492, 10.524, 1.213, 3.929, 11.155),
    (1.115, 3.929, 9.748, 1.213, 4.413, 10.524), (1.164, 3.929, 8.488, 1.213, 4.413, 9.748),
    (1.164, 3.492, 9.748, 1.213, 3.929, 10.524), (1.164, 3.492, 8.488, 1.213, 3.929, 9.748),
    (0.000, 3.153, 12.707, 1.213, 3.492, 12.901), (0.000, 3.153, 11.737, 1.213, 3.492, 12.707),
    (0.000, 3.153, 11.155, 1.213, 3.492, 11.737), (0.000, 3.153, 10.524, 1.213, 3.492, 11.155),
    (0.000, 3.153, 9.748, 1.213, 3.492, 10.524), (0.000, 3.153, 8.488, 1.213, 3.492, 9.748),
    (0.000, 4.413, 6.354, 1.213, 4.947, 8.488), (0.000, 3.492, 6.354, 1.213, 4.899, 8.488),
    (0.679, 4.899, 11.737, 0.921, 4.947, 12.707), (0.825, 4.899, 11.155, 0.921, 4.947, 11.737),
    (0.776, 4.899, 10.524, 0.921, 4.995, 11.155), (0.631, 4.899, 9.748, 0.921, 5.093, 10.524),
    (0.776, 4.899, 9.748, 0.921, 4.995, 10.524), (0.728, 4.899, 8.488, 0.921, 4.995, 9.748),
    (0.242, 4.995, 8.488, 0.825, 5.287, 9.748), (0.000, 4.899, 6.354, 0.921, 5.093, 8.488),
    (0.000, 4.995, 6.354, 0.776, 5.287, 8.488), (0.000, 3.055, 6.354, 1.213, 3.492, 8.488),
    (0.388, 4.899, 12.707, 0.873, 4.947, 12.901), (0.194, 4.995, 8.488, 0.776, 5.287, 8.779),
    (0.000, 5.238, 8.488, 0.291, 5.287, 8.779), (0.776, 4.947, 10.524, 0.873, 5.141, 11.155),
    (0.631, 4.995, 9.748, 0.825, 5.238, 10.524), (0.242, 5.093, 8.779, 0.679, 5.287, 9.748),
    (0.533, 5.093, 10.524, 0.873, 5.626, 11.155), (0.485, 5.141, 9.748, 0.825, 5.578, 10.524),
    (0.242, 5.238, 8.779, 0.679, 5.432, 9.748), (0.340, 5.578, 10.524, 0.631, 5.723, 11.155),
    (0.291, 5.432, 9.748, 0.582, 5.675, 10.524), (0.242, 5.287, 8.779, 0.533, 5.529, 9.748),
    (0.000, 5.626, 10.524, 0.436, 5.723, 11.155), (0.000, 5.481, 9.748, 0.436, 5.675, 10.524),
    (0.000, 5.238, 8.779, 0.388, 5.529, 9.748), (0.000, 4.317, 12.901, 1.115, 4.899, 14.453),
    (0.000, 3.929, 12.901, 1.213, 4.413, 14.453), (0.000, 3.492, 12.901, 1.213, 3.929, 14.453),
    (0.000, 3.153, 12.901, 1.213, 3.541, 14.453), (0.000, 4.656, 12.901, 0.873, 4.947, 14.453),
    (0.000, 2.910, 12.901, 0.970, 3.201, 14.453), (-1.115, 4.413, 12.707, -0.825, 4.899, 12.901),
    (-1.164, 4.413, 11.737, -0.825, 4.899, 12.707), (-1.164, 4.413, 11.155, -0.873, 4.899, 11.737),
    (-1.164, 4.413, 10.524, -0.873, 4.899, 11.155), (-1.213, 4.413, 9.748, -0.873, 4.899, 10.524),
    (-1.213, 4.413, 8.488, -0.873, 4.899, 9.748), (-1.213, 3.929, 12.707, -1.067, 4.413, 12.901),
    (-1.213, 3.929, 11.737, -1.067, 4.413, 12.707), (-1.213, 3.929, 11.155, -1.115, 4.413, 11.737),
    (-1.213, 3.929, 10.524, -1.115, 4.413, 11.155), (-1.213, 3.492, 12.707, 0.000, 3.929, 12.901),
    (-1.213, 3.492, 11.737, 0.000, 3.929, 12.707), (-1.213, 3.492, 11.155, 0.000, 3.929, 11.737),
    (-1.213, 3.492, 10.524, -1.164, 3.929, 11.155), (-1.213, 3.929, 9.748, -1.115, 4.413, 10.524),
    (-1.213, 3.929, 8.488, -1.164, 4.413, 9.748), (-1.213, 3.492, 9.748, -1.164, 3.929, 10.524),
    (-1.213, 3.492, 8.488, -1.164, 3.929, 9.748), (-1.213, 3.153, 12.707, 0.000, 3.492, 12.901),
    (-1.213, 3.153, 11.737, 0.000, 3.492, 12.707), (-1.213, 3.153, 11.155, 0.000, 3.492, 11.737),
    (-1.213, 4.413, 6.354, 0.000, 4.947, 8.488), (-1.213, 3.492, 6.354, 0.000, 4.899, 8.488),
    (-0.921, 4.899, 11.737, -0.679, 4.947, 12.707), (-0.921, 4.899, 11.155, -0.825, 4.947, 11.737),
    (-0.921, 4.899, 10.524, -0.776, 4.995, 11.155), (-0.921, 4.899, 9.748, -0.631, 5.093, 10.524),
    (-0.921, 4.899, 9.748, -0.776, 4.995, 10.524), (-0.921, 4.899, 8.488, -0.728, 4.995, 9.748),
    (-0.825, 4.995, 8.488, -0.242, 5.287, 9.748), (-0.921, 4.899, 6.354, 0.000, 5.093, 8.488),
    (-0.776, 4.995, 6.354, 0.000, 5.287, 8.488), (-1.213, 3.056, 6.354, 0.000, 3.492, 8.488),
    (-0.873, 4.899, 12.707, -0.388, 4.947, 12.901), (-0.776, 4.995, 8.488, -0.194, 5.287, 8.779),
    (-0.291, 5.238, 8.488, 0.000, 5.287, 8.779), (-0.873, 4.947, 10.524, -0.776, 5.141, 11.155),
    (-0.825, 4.995, 9.748, -0.631, 5.238, 10.524), (-0.679, 5.093, 8.779, -0.242, 5.287, 9.748),
    (-0.873, 5.093, 10.524, -0.533, 5.626, 11.155), (-0.825, 5.141, 9.748, -0.485, 5.578, 10.524),
    (-0.679, 5.238, 8.779, -0.242, 5.432, 9.748), (-0.631, 5.578, 10.524, -0.340, 5.723, 11.155),
    (-0.582, 5.432, 9.748, -0.291, 5.675, 10.524), (-0.533, 5.287, 8.779, -0.242, 5.529, 9.748),
    (-0.436, 5.626, 10.524, 0.000, 5.723, 11.155), (-0.436, 5.481, 9.748, 0.000, 5.675, 10.524),
    (-0.388, 5.238, 8.779, 0.000, 5.529, 9.748), (-1.115, 4.317, 12.901, 0.000, 4.899, 14.453),
    (-1.213, 3.929, 12.901, 0.000, 4.413, 14.453), (-1.213, 3.492, 12.901, 0.000, 3.929, 14.453),
    (-1.213, 3.153, 12.901, 0.000, 3.541, 14.453), (-0.873, 4.656, 12.901, 0.000, 4.947, 14.453),
    (-0.970, 2.910, 12.901, 0.000, 3.201, 14.453), (0.873, 2.910, 12.271, 1.164, 3.201, 12.659),
    (-1.164, 2.910, 12.271, -0.873, 3.201, 12.659), (0.000, 3.929, 11.155, 1.164, 4.123, 12.901),
    (-1.164, 3.929, 11.155, 0.000, 4.123, 12.901), (0.000, 3.153, 8.488, 1.164, 3.492, 8.779),
    (-1.164, 3.153, 8.488, 0.000, 3.492, 8.779), (-1.213, 3.153, 10.524, 0.000, 3.492, 11.155),
    (-1.213, 3.153, 9.748, 0.000, 3.492, 10.524), (-1.213, 3.153, 8.488, 0.000, 3.492, 9.748),
]
EXPECT_CUT = 1464
CUT_TOL = 0.004

# ---- the canopy (vehicle blocks): the model's own mould line, its windscreen's openings as flat panes
Y_B = 4.965                                # sill height
XF = 0.86                                  # the canopy's foot half width
Z_R = 11.20                                # the canopy's rear (a 5.5 cm rear bow stands here: 11.20 .. 11.255; the hump's front face is at 11.155)
Z_A = 12.12                                # the arch: the front of the crown line, the windscreen's top
Z_E = 12.85                                # the canopy's front end on the nose deck
Z_B = 12.90                                # the dash / nose block's rear face (the last section the checks read)
# the crown line over the pilots: the model's own top line there (its frame lumps, elements 104-118), read at x = 0
CROWN = [(11.20, 5.716), (11.30, 5.70), (11.60, 5.63), (11.90, 5.54), (Z_A, 5.43)]
NS = 20                                    # points per half section
ROWS = [11.20, 11.50, 11.80, 12.00, 12.12, 12.30, 12.50, 12.70, Z_E]
BAR_W = 0.06                               # frame width round the panes
INSET = 0.03                               # the glass stops half a frame inside each outline
CENTRE_POST = False                        # the model's centre opening has no post (True splits it in two by a slim post)

# The windscreen row, read off the model's frame lumps (checks/orig): the posts are the old windscreen bars (element 125 at x 0.19 .. 0.24,
# running from y 5.43 at z 12.12 to 5.09 at 12.66; element 124 / 122 at x 0.58 .. 0.73 from (z 12.03, y 5.34) down to the sill block at
# z 12.71), between them three openings on each side of a centre one: the centre opening between the two inner posts, the opening
# in front of each pilot, and the wrapping quarter opening at each corner. Each opening is one flat parallelogram: the inner post's
# line and a horizontal-ish direction D (the plane contains both: planar by construction), so the row wraps round the rounded plan.
POST_I = 0.215
TC = np.array([POST_I, 5.43, Z_A])         # the centre / pilot post's top ...
BC = np.array([POST_I, 5.12, 12.62])       # ... and the glass's lower edge on it
V_POST = BC - TC
D_M = np.array([0.405, -0.09, -0.09])      # pilot pane: from the inner post to the next post, falling and sweeping back a little
D_Q = np.array([0.22, -0.03, -0.14])       # quarter pane: wraps round the corner (turned about 33 degrees)
T_M, B_M = TC + D_M, BC + D_M              # the second post's top and bottom
T_Q, B_Q = T_M + D_Q, B_M + D_Q            # the quarter pane's outer edge
SW_Z = (11.45, 11.80)                      # the side opening (the original's slit between the rail and the shoulder lumps)
SW_Y = (5.13, 5.25)


def R(x, y, z):
    return np.array([x, y, z], float)


def mir(p):
    return np.array([-p[0], p[1], p[2]], float)


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def facet_y(x, z):
    """the windscreen row's surface: three flat facets (centre, pilot's, corner), the height at (|x|, z)"""
    x = abs(x)
    if x <= POST_I:
        P0, d = TC, np.array([1.0, 0.0, 0.0])
    elif x <= T_M[0]:
        P0, d = TC, D_M
    else:
        P0, d = T_M, D_Q
    b = (x - P0[0]) / d[0]
    a = (z - P0[2] - b * d[2]) / V_POST[2]
    return float(P0[1] + a * V_POST[1] + b * d[1])


def crown(z):
    if z <= Z_A + 1e-9:
        return float(np.interp(z, [c[0] for c in CROWN], [c[1] for c in CROWN]))
    return max(facet_y(0.0, z), Y_B)


def x_edge(z):
    """the half width of the canopy's footprint at z: the foot's 0.86, cut short where the facets fall to the sill"""
    X = XF if z <= 12.30 else max(XF - 0.13 * (z - 12.30) / 0.55, 0.73)
    lo, hi = 0.0, X
    if min(facet_y(X, z), crown(z)) > Y_B + 0.004:
        return X
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        if facet_y(mid, z) > Y_B + 0.004:
            lo = mid
        else:
            hi = mid
    return max(lo, 0.02)


def raw_section(z, k=NS + 1):
    """the right half-section at z (absolute): from the sill foot up the wall over the shoulder to the crown at x = 0: a superellipse
    (smooth fairing, fuller toward the arch) cut by the windscreen row's flat facets; the facet ridges (posts) are section points"""
    X = x_edge(z)
    h = max(crown(z) - Y_B, 0.0)
    e = float(np.interp(z, [11.2, 11.7, Z_A], [2.4, 2.4, 6.0]))
    phi = np.linspace(0, math.pi / 2, k)
    xs = np.array([X * math.cos(p) ** (2 / e) if p < math.pi / 2 - 1e-9 else 0.0 for p in phi])
    for xr in (POST_I, T_M[0]):                 # snap the nearest interior point onto each ridge
        if X > xr + 0.01:
            j = 1 + int(np.argmin(np.abs(xs[1:-1] - xr)))
            xs[j] = xr
    pts = []
    for x in xs:
        y_roof = Y_B + h * (max(1 - (x / X) ** e, 0.0)) ** (1 / e) if X > 1e-6 else Y_B
        y = max(min(y_roof, facet_y(x, z)), Y_B)
        pts.append((float(x), float(y)))
    pts[0] = (pts[0][0], Y_B)
    return np.array(pts)


def section_abs(z):
    return [tuple(p) for p in raw_section(z, k=18)]


def section_y(z, x):
    """the section's outer height at |x|"""
    s = raw_section(z, k=80)
    return float(np.interp(x, s[::-1, 0], s[::-1, 1]))


HUMP = {          # the dorsal hump over the rear crew compartment (the model's own outer skin, read off checks/orig): z -> (x of the skin at y 4.50, [(x, top y)])
    8.50: (1.1606, [(1.1, 4.601), (1.0, 4.768), (0.9, 4.913), (0.8, 4.980), (0.7, 5.038), (0.6, 5.093), (0.5, 5.147), (0.4, 5.202), (0.3, 5.256), (0.2, 5.287), (0.0, 5.287)]),
    9.00: (1.1606, [(1.1, 4.601), (1.0, 4.768), (0.9, 4.920), (0.8, 4.993), (0.7, 5.056), (0.6, 5.114), (0.5, 5.172), (0.4, 5.230), (0.3, 5.342), (0.2, 5.342), (0.0, 5.342)]),
    9.50: (1.1606, [(1.1, 4.601), (1.0, 4.768), (0.9, 4.920), (0.8, 5.005), (0.7, 5.072), (0.6, 5.130), (0.5, 5.357), (0.4, 5.430), (0.3, 5.467), (0.2, 5.467), (0.0, 5.467)]),
    10.00: (1.1535, [(1.1, 4.601), (1.0, 4.768), (0.9, 4.920), (0.8, 5.012), (0.7, 5.254), (0.6, 5.411), (0.5, 5.504), (0.4, 5.554), (0.3, 5.576), (0.2, 5.576), (0.0, 5.576)]),
    10.50: (1.1223, [(1.1, 4.545), (1.0, 4.745), (0.9, 4.920), (0.8, 5.180), (0.7, 5.360), (0.6, 5.540), (0.5, 5.613), (0.4, 5.663), (0.3, 5.670), (0.2, 5.670), (0.0, 5.670)]),
    11.00: (1.1208, [(1.1, 4.541), (1.0, 4.742), (0.9, 4.920), (0.8, 5.239), (0.7, 5.459), (0.6, 5.622), (0.5, 5.662), (0.4, 5.705), (0.3, 5.711), (0.2, 5.711), (0.0, 5.711)]),
    11.19: (1.1208, [(1.1, 4.541), (1.0, 4.742), (0.9, 4.920), (0.8, 5.272), (0.7, 5.492), (0.6, 5.632), (0.5, 5.672), (0.4, 5.712), (0.3, 5.717), (0.2, 5.717), (0.0, 5.717)]),
}


def outline_rel():
    """the sections the checks read: the dorsal hump's outer skin over the rear crew compartment (opaque, no glass) from the rear wall
    to the canopy, then the canopy and the windscreen. The whole crew space is one 'canopy' to the checks: nothing of the new
    cockpit may stand outside it"""
    out = []
    for z, (xo, pts) in sorted(HUMP.items()):
        out.append((z - CENTRE[2], [(xo - CENTRE[0], 4.50 - CENTRE[1])] + [(x - CENTRE[0], y - CENTRE[1]) for x, y in pts]))
    for z in [Z_R, 11.6, 12.0, Z_A, 12.3, 12.5, 12.7, Z_E, Z_B]:
        pts = section_abs(min(z, Z_E))
        out.append((z - CENTRE[2], [(x - CENTRE[0], y - CENTRE[1]) for x, y in pts]))
    return out


CANOPY = outline_rel()                     # as handbuilt_canopy.Shape wants it (the checks read this)


def half_section(z_rel):
    return [(x - CENTRE[0], y - CENTRE[1]) for x, y in section_abs(z_rel + CENTRE[2])]


# ---------------------------------------------------------------- mesher (closed solids)

class Solids:
    """faces of the frame members and skin panels as a poly mesh in model pixels; each face has one winding, outward from the
    solid. Points are vehicle blocks"""

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

    def bar(self, a, b, w, n, t=0.05, po=0.015, ext=None, uv=None):
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

    def slab(self, tri, out, t=0.012, uv=None):
        """a thin skin triangle: its outer face toward [out] (a direction), the slab t thick on the inside"""
        a, b, c = (np.array(p, float) for p in tri)
        n = np.cross(b - a, c - a)
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            return
        n /= ln
        if n @ np.array(out, float) < 0:
            n = -n
        P0 = [a, b, c]
        P1 = [p - n * t for p in P0]
        self.face(P0, n, uv)
        self.face(P1[::-1], -n, uv)
        cen = np.mean(P0 + P1, 0)
        for i in range(3):
            j = (i + 1) % 3
            self.quad([P0[i], P0[j], P1[j], P1[i]], cen, uv)

    def poly_slab(self, pts, out, t=0.012, uv=None):
        for k in range(1, len(pts) - 1):
            self.slab([pts[0], pts[k], pts[k + 1]], out, t, uv)

    def mesh(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}


# ---------------------------------------------------------------- the panes: outlines, glass

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


def pane_outlines(s=1):
    """the flat panes on one side (s = +1 left, -1 right): name -> four corners (top-inner, top-outer, bottom-outer, bottom-inner)"""
    sx = lambda p: R(s * p[0], p[1], p[2])      # noqa: E731
    out = {'pilot': [sx(TC), sx(T_M), sx(B_M), sx(BC)], 'quarter': [sx(T_M), sx(T_Q), sx(B_Q), sx(B_M)]}
    return out


def centre_outline():
    return [R(-POST_I, TC[1], TC[2]), R(POST_I, TC[1], TC[2]), R(POST_I, BC[1], BC[2]), R(-POST_I, BC[1], BC[2])]


def side_plane():
    """the side opening's plane: x = x0 + sl (y - yc), tangent to the section there"""
    yc, zc = 0.5 * sum(SW_Y), 0.5 * sum(SW_Z)
    s = raw_section(zc, k=200)
    order = np.argsort(s[:, 1])
    ysort, xsort = s[order, 1], s[order, 0]
    x0 = float(np.interp(yc, ysort, xsort))
    x1, x2 = float(np.interp(yc - 0.05, ysort, xsort)), float(np.interp(yc + 0.05, ysort, xsort))
    return x0, (x2 - x1) / 0.10


def side_outline(s=1):
    x0, sl = side_plane()
    yc = 0.5 * sum(SW_Y)
    pts = [(SW_Z[0], SW_Y[0]), (SW_Z[1], SW_Y[0]), (SW_Z[1], SW_Y[1]), (SW_Z[0], SW_Y[1])]
    return [R(s * (x0 + sl * (y - yc)), y, z) for z, y in pts]


def glass_triangles():
    tris = []
    quads = [centre_outline()]
    if CENTRE_POST:
        c = centre_outline()
        quads = [[c[0], R(0, c[0][1], c[0][2]), R(0, c[3][1], c[3][2]), c[3]], [R(0, c[0][1], c[0][2]), c[1], c[2], R(0, c[3][1], c[3][2])]]
    for s in (1, -1):
        for o in pane_outlines(s).values():
            quads.append(o[::-1] if s < 0 else o)
        o = side_outline(s)
        quads.append(o[::-1] if s < 0 else o)
    for o in quads:
        g = inset_poly(o, [INSET] * 4)
        tris.append([g[0], g[1], g[2]])
        tris.append([g[0], g[2], g[3]])
    return tris


# ---------------------------------------------------------------- skin (the smooth fairing with the panes cut out) and frames

def point_in_poly(p, poly):
    x, y = p
    inside = False
    for i in range(len(poly)):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % len(poly)]
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
            inside = not inside
    return inside


def _clip_half(poly, n, d):
    """Sutherland-Hodgman: the part of the 2-D polygon with n . p - d >= 0"""
    out = []
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        da, db = n @ a - d, n @ b - d
        if da >= 0:
            out.append(a)
        if (da >= 0) != (db >= 0):
            out.append(a + (b - a) * (da / (da - db)))
    return out


def window_polys():
    """the openings as convex polygons in the projection each lies best in: ('xz', pts) for the windscreen row, ('zy', pts) for the side slit"""
    w = [('xz', [np.array([p[0], p[2]]) for p in centre_outline()])]
    for o in pane_outlines(1).values():
        w.append(('xz', [np.array([p[0], p[2]]) for p in o]))
    w.append(('zy', [np.array([p[2], p[1]]) for p in side_outline(1)]))
    return w


def cut_tris(tri, windows):
    """a skin triangle (right half or mirrored: |x| is used) minus the openings: exact, by clipping against each opening's edges"""
    pieces = [list(tri)]
    for kind, poly in windows:
        nxt = []
        P = poly if (poly[1] - poly[0])[0] * (poly[2] - poly[1])[1] - (poly[1] - poly[0])[1] * (poly[2] - poly[1])[0] > 0 else poly[::-1]
        lo, hi = np.min(P, axis=0), np.max(P, axis=0)
        for pc in pieces:
            pts = np.array([[abs(q[0]), q[2]] if kind == 'xz' else [q[2], q[1]] for q in pc])
            if len(pc) < 3 or (kind == 'zy' and min(abs(q[0]) for q in pc) < 0.5):
                nxt.append(pc); continue
            if np.any(pts.max(0) < lo - 1e-6) or np.any(pts.min(0) > hi + 1e-6):
                nxt.append(pc); continue
            sgn = 1 if np.mean([q[0] for q in pc]) >= 0 else -1
            cur = [np.array(p) for p in pts]
            lift = pc
            # barycentric lift from the 2-D projection of the three corners (pc is a triangle or a convex polygon: lift by its first three corners)
            A_ = np.array([[pts[0][0], pts[1][0], pts[2][0]], [pts[0][1], pts[1][1], pts[2][1]], [1, 1, 1]])
            if abs(np.linalg.det(A_)) < 1e-12:
                nxt.append(pc); continue
            Ainv = np.linalg.inv(A_)
            def up(q):
                w_ = Ainv @ np.array([q[0], q[1], 1.0])
                return w_[0] * lift[0] + w_[1] * lift[1] + w_[2] * lift[2]
            rest = cur
            for i in range(len(P)):
                a, b = P[i], P[(i + 1) % len(P)]
                e = b - a
                nrm = np.array([-e[1], e[0]]) / max(np.linalg.norm(e), 1e-12)     # inward (left of a CCW edge)
                dd = nrm @ a
                outside = _clip_half(rest, -nrm, -dd)
                if len(outside) >= 3:
                    nxt.append([up(q) for q in outside])
                rest = _clip_half(rest, nrm, dd)
                if len(rest) < 3:
                    break
        pieces = nxt
    out = []
    for pc in pieces:
        for k in range(1, len(pc) - 1):
            ta, tb, tc = pc[0], pc[k], pc[k + 1]
            if np.linalg.norm(np.cross(tb - ta, tc - ta)) > 1e-9:
                out.append((ta, tb, tc))
    return out


def skin_rows():
    return [(z, raw_section(z)) for z in ROWS]


def facet_normal(d):
    n = unit(np.cross(V_POST, d))
    return n if n[1] > 0 else -n


def build_solids():
    b = Solids(UV_FRAME)
    sk = UV_PANEL
    rows = skin_rows()
    WINDOWS = window_polys()
    for s in (1, -1):
        for (z0, S0), (z1, S1) in zip(rows[:-1], rows[1:]):
            for k in range(len(S0) - 1):
                p00 = R(s * S0[k][0], S0[k][1], z0); p01 = R(s * S0[k + 1][0], S0[k + 1][1], z0)
                p10 = R(s * S1[k][0], S1[k][1], z1); p11 = R(s * S1[k + 1][0], S1[k + 1][1], z1)
                for tri in ((p00, p10, p11), (p00, p11, p01)):
                    a, bb, c = tri
                    if np.linalg.norm(np.cross(bb - a, c - a)) < 1e-9:
                        continue
                    n0 = unit(np.cross(bb - a, c - a))
                    for ta, tb, tc in cut_tris((a, bb, c), WINDOWS):
                        out = (ta + tb + tc) / 3 - R(0.0, Y_B + 0.12, (ta[2] + tb[2] + tc[2]) / 3)
                        n = n0 if n0 @ out >= 0 else -n0
                        b.face([ta, tb, tc], n, sk)
                        b.face([ta - n * 0.012, tb - n * 0.012, tc - n * 0.012][::-1], -n, sk)
    # -- frames on the panes' outlines: posts, the arch along the tops, the lower edges
    nC = facet_normal(np.array([1.0, 0.0, 0.0]))
    nM = facet_normal(D_M)
    nQ = facet_normal(D_Q)
    c = centre_outline()
    b.bar(c[0], c[1], BAR_W, nC, t=0.05, po=0.018, ext=BAR_W / 2)           # the centre opening's top (the arch) and bottom
    b.bar(c[3], c[2], BAR_W, nC, t=0.05, po=0.018, ext=BAR_W / 2)
    if CENTRE_POST:
        b.bar(R(0, TC[1], TC[2]), R(0, BC[1], BC[2]), 0.045, nC, t=0.05, po=0.018)
    for s in (1, -1):
        po_, qo_ = pane_outlines(s)['pilot'], pane_outlines(s)['quarter']
        nm = np.array([s * nM[0], nM[1], nM[2]])
        nq = np.array([s * nQ[0], nQ[1], nQ[2]])
        for i in range(4):                       # the pilot's pane: top (arch), outer post, bottom, inner post
            b.bar(po_[i], po_[(i + 1) % 4], BAR_W, nm, t=0.05, po=0.018, ext=BAR_W / 2)
        for i in (0, 1, 2):                      # the quarter pane: top (arch), outer edge, bottom (its inner edge is the pilot pane's outer post)
            b.bar(qo_[i], qo_[(i + 1) % 4], BAR_W, nq, t=0.05, po=0.018, ext=BAR_W / 2)
    # -- sill rails along the foot (the footprint's edge) and the nose end
    foot = [(x_edge(z), z) for z in ROWS]
    for s in (1, -1):
        for (xa, za), (xb, zb) in zip(foot[:-1], foot[1:]):
            b.bar(R(s * xa, Y_B, za), R(s * xb, Y_B, zb), 0.07, unit([s * 0.7, 0.7, 0.0]), t=0.06, po=0.02, ext=0.01)
    xe = foot[-1][0]
    b.bar(R(-xe, Y_B, Z_E), R(xe, Y_B, Z_E), 0.06, unit([0, 1.0, 0.3]), t=0.065, po=0.014, ext=0.0)
    # -- the rear bow against the hump's front face: along the section at Z_R (5.5 cm deep in z, 6 cm radially, 1.2 cm proud)
    zr0, zr1 = Z_R, Z_R + 0.055
    zc = 0.5 * (zr0 + zr1)
    sec = [tuple(p) for p in raw_section(zc, k=9)]
    for k in range(len(sec) - 1):
        (x0, y0), (x1, y1) = sec[k], sec[k + 1]
        u2 = unit([x1 - x0, y1 - y0, 0.0])
        out2 = np.array([u2[1], -u2[0], 0.0])
        if out2[1] < -1e-9 or (abs(out2[1]) < 1e-9 and out2[0] < 0):
            out2 = -out2
        for s in (1, -1):
            q0, q1 = R(s * x0, y0, zc), R(s * x1, y1, zc)
            o = R(s * out2[0], out2[1], 0.0)
            u = unit(q1 - q0)
            sdir = np.cross(u, o)
            cc = (q0 + q1) / 2 + o * (0.012 - 0.03)
            b.obox(cc, (u, sdir, o), (np.linalg.norm(q1 - q0) / 2 + 0.02, (zr1 - zr0) / 2, 0.03))
    # -- the side opening's frame
    for s in (1, -1):
        o = side_outline(s)
        nn = unit([s * 1.0, -side_plane()[1], 0.0])
        for i in range(4):
            b.bar(o[i], o[(i + 1) % 4], 0.045, nn, t=0.045, po=0.014, ext=0.02)
    return b


# ---------------------------------------------------------------- the airframe

def cut_frame(bones, already):
    """removes the model's own frames, interiors and seats (CUT_BOXES) from the hull pieces: every face wholly inside a cut box
    and inside no kept box. The first run must find EXPECT_CUT faces; returns the faces removed"""
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


def main(argv):
    global UV_FRAME, UV_PANEL
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    sw = L.Swatches(TEXTURE)
    UV_FRAME = [float(v) for v in sw.uv(GREY_FRAME)]
    UV_PANEL = [float(v) for v in sw.uv(GREY_PANEL)]
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
    print(f"vulcan_b2 canopy: the model's frames, interiors and seats cut out ({cut} faces), {len(solids.polys)} faces of frames and skin; "
          f'{len(tris)} glass triangles (x2 windings): five flat windscreen panes (centre, a pilot pane and a corner pane each side) and two small flat side windows in the smooth fairing')
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



