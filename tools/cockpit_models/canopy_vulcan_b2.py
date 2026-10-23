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

The real Vulcan B.2 (photographs and type descriptions; no drawing with dimensions was found, so every number is the model's own
line, UNVERIFIED against the real jet): the two pilots sit side by side under a small, heavily framed canopy; the windscreen is
a flat, upright, multi-pane screen (a centre post between two flat panes, a small flat pane round each corner), small
overhead 'eyebrow' windows above it, and a small side window each side; behind the pilots the dorsal fairing covers the
windowless rear crew compartment. Here:

  * windscreen: ONE flat plane for the centre pair (a centre post at x 0, outer posts at |x| 0.69), raked 41 degrees, between the
    arch (top, z 12.42) and the base bar on the nose deck (z 12.90); each corner pane is a flat parallelogram turned 50 degrees
    about the outer post (planar by construction);
  * eyebrow windows: two flat panes in the flat roof plane between the mid bow (z 12.04) and the arch, split by a centre rib;
  * side windows: one flat pane each side in the vertical side wall;
  * the arch IS the front bow of the canopy: one member from the left corner pane's top edge over the centre pair to the right
    one; the rear bow stands against the hump's front face (z 11.155), so the canopy rear seals on the spine fairing;
  * frames (bone wreck_fuselage_1__canopy, closed solids, 4 to 7 cm, airframe grey): sill rails, rear bow, mid bow, arch,
    centre post, outer posts, corner posts, window frames, base bar; the canopy skin between the windows is slim opaque panels in
    the hump's grey; glass stops at every frame edge.

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

# ---- the canopy (vehicle blocks)
Y_B = 4.965                                # sill / base height
XF = 0.86                                  # the canopy's foot: the side wall's plane
YS = 5.28                                  # the shoulder line's height (the wall's top)
XR = 0.69                                  # the roof's half width = the outer post line
Z_R = 11.20                                # the canopy's rear (a 5.5 cm rear bow stands here: 11.20 .. 11.255; the hump's front face is at 11.155)
Z_A = 12.42                                # the arch (front bow) on the roof plane
Z_B = 12.90                                # the base bar's line (the nose block's rear face)
Z_M = 12.04                                # the mid bow
Y_T = 5.505                                # the arch's height = the front pane's top edge
H_REAR = 5.70                              # the roof plane's height at the rear (the hump's crown stands at 5.72)
K_ROOF = (H_REAR - Y_T) / (Z_A - Z_R)      # roof slope (falls forward)
K_SCR = (Y_T - Y_B) / (Z_B - Z_A)          # the windscreen's rake: rises this much per block back from the base
THETA = math.radians(50.0)                 # the corner panes' turn about the outer post
S_C = (XF - XR) / math.cos(THETA)
DZ_C = S_C * math.sin(THETA)
Z_CT, Z_CB = Z_A - DZ_C, Z_B - DZ_C        # the corner pane's outer edge: top z, base z (x = XF)
Z_SH = Z_B - DZ_C - (YS - Y_B) / (Y_T - Y_B) * (Z_B - Z_A)   # the side wall's front edge at the shoulder height (on the rake line)
SW_Z = (11.55, 12.02)                      # the side window's outline (z)
SW_Y = (5.03, 5.24)                        # and (y)


def H(z):
    """the roof plane's height (flat across x; falls forward)"""
    return H_REAR - K_ROOF * (z - Z_R)


def y_scr(z):
    """the centre pair's plane: independent of x"""
    return Y_B + K_SCR * (Z_B - z)


def x_base(z):
    """where the corner pane's base line stands at z (z between Z_CB and Z_B)"""
    return XR + (Z_B - z) / DZ_C * (XF - XR)


def R(x, y, z):
    return np.array([x, y, z], float)


def mir(p):
    return np.array([-p[0], p[1], p[2]], float)


def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


# ---------------------------------------------------------------- the sections the checks read (eye-relative to CENTRE)

def section_abs(z):
    """the right half-section at z (absolute): from the sill foot up to the crown (x = 0)"""
    if z <= Z_A + 1e-9:
        return [(XF, Y_B), (XF, YS), (XR, H(z)), (0.0, H(z))]
    if z <= Z_CB:                          # the side wall up to the corner pane's outer edge (the rake line), then the corner pane
        y_rake = Y_T - (z - Z_CT) / (Z_CB - Z_CT) * (Y_T - Y_B)
        return [(XF, Y_B), (XF, y_rake), (XR, y_scr(z)), (0.0, y_scr(z))]
    return [(x_base(min(z, Z_B)), Y_B), (XR, y_scr(z)), (0.0, y_scr(z))]


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
    stations = [Z_R, 11.6, 12.0, 12.3, Z_A, Z_A + 0.02, 12.5, 12.6, 12.7, 12.8, Z_B]
    for z in stations:
        pts = section_abs(z)
        out.append((z - CENTRE[2], [(x - CENTRE[0], y - CENTRE[1]) for x, y in pts]))
    return out


CANOPY = outline_rel()                     # as handbuilt_canopy.Shape wants it (the checks read this)


def half_section(z_rel):
    """the canopy's right half-section at a z relative to CENTRE, eye-relative (the cockpit script reads this)"""
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


# ---------------------------------------------------------------- the glass: planes, insets

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
        (p0, u0), (p1, u1) = lines[i - 1], lines[i]       # the corner P[i] joins edge i-1 and edge i
        A_ = np.array([u0, -u1]).T                         # p0 + s u0 = p1 + t u1  (least squares in the plane)
        sol = np.linalg.lstsq(A_, p1 - p0, rcond=None)[0]
        out.append(p0 + sol[0] * u0)
    return out


def both_sides(name, o, e):
    """the pane on the left (x > 0) as given, and mirrored to the right (corner order reversed so the outline stays convex-wound)"""
    return [(name, o, e), (name, [mir(p) for p in o][::-1], [e[2], e[1], e[0], e[3]])]


def glass_panes():
    """the flat panes: name -> corners (outline on the frame centre lines) and the inset per edge (half the frame's width)"""
    panes = []
    d_arch, d_post, d_base = 0.025, 0.025, 0.030
    # the centre pair: one plane, split by the centre post (edges: arch, outer post, base, centre post)
    panes += both_sides('front', [R(0, Y_T, Z_A), R(XR, Y_T, Z_A), R(XR, Y_B, Z_B), R(0, Y_B, Z_B)], [d_arch, d_post, d_base, d_post])
    # the corner panes (edges: arch diagonal, corner post, base, outer post)
    panes += both_sides('corner', [R(XR, Y_T, Z_A), R(XF, Y_T, Z_CT), R(XF, Y_B, Z_CB), R(XR, Y_B, Z_B)], [d_arch, 0.0225, d_base, d_post])
    # the eyebrow windows: in the roof plane (edges: mid bow, outer rail, arch, centre rib)
    panes += both_sides('eyebrow', [R(0, H(Z_M), Z_M), R(XR, H(Z_M), Z_M), R(XR, H(Z_A), Z_A), R(0, H(Z_A), Z_A)], [0.0225, 0.0225, d_arch, 0.0225])
    # the side windows: in the vertical side wall
    panes += both_sides('side', [R(XF, SW_Y[0], SW_Z[0]), R(XF, SW_Y[0], SW_Z[1]), R(XF, SW_Y[1], SW_Z[1]), R(XF, SW_Y[1], SW_Z[0])], [0.018] * 4)
    return panes


def glass_triangles():
    tris = []
    for name, o, e in glass_panes():
        g = inset_poly(o, e)
        tris.append([g[0], g[1], g[2]])
        tris.append([g[0], g[2], g[3]])
    return tris


# ---------------------------------------------------------------- frames and skin

def build_solids():
    b = Solids(UV_FRAME)
    sk = UV_PANEL
    n_scr = unit([0, 1.0, K_SCR])                       # the centre pair's outward normal
    n_roof = unit([0, 1.0, K_ROOF])                     # (the roof falls forward: its normal leans forward)
    # -- the arch = the front bow: one member from the left corner pane's top edge over the centre pair to the right one
    n_ridge = unit(n_scr + n_roof)
    b.bar(R(-XR, Y_T, Z_A), R(XR, Y_T, Z_A), 0.05, n_ridge, t=0.055, po=0.016, ext=0.025)
    for s in (1, -1):
        xs = lambda p: np.array([s * p[0], p[1], p[2]])
        n_c = unit(np.cross(R(XF, Y_T, Z_CT) - R(XR, Y_T, Z_A), R(XR, Y_B, Z_B) - R(XR, Y_T, Z_A)))   # the corner pane's normal
        if n_c[1] < 0:
            n_c = -n_c
        n_c = np.array([s * n_c[0], n_c[1], n_c[2]])
        b.bar(xs(R(XR, Y_T, Z_A)), xs(R(XF, Y_T, Z_CT)), 0.05, unit(n_c + n_roof), t=0.055, po=0.016, ext=0.025)
    # -- windscreen posts: the centre post and the outer posts (members along the pane edges)
    b.bar(R(0, Y_T, Z_A), R(0, Y_B, Z_B), 0.05, n_scr, t=0.05, po=0.015)
    for s in (1, -1):
        n_p = unit([s * math.sin(THETA) * 0.5, 1.0, K_SCR + 0.2])
        b.bar(R(s * XR, Y_T, Z_A), R(s * XR, Y_B, Z_B), 0.05, n_p, t=0.05, po=0.015)
        # the corner post on the pane's outer edge
        n_o = unit([s * 1.0, 0.25, 0.35])
        b.bar(R(s * XF, Y_T, Z_CT), R(s * XF, Y_B, Z_CB), 0.045, n_o, t=0.045, po=0.013)
    # -- the base bar on the deck: across the centre pair and out along the corner panes' feet
    b.bar(R(-XR, Y_B, Z_B), R(XR, Y_B, Z_B), 0.06, n_scr, t=0.065, po=0.014, ext=0.0)
    for s in (1, -1):
        b.bar(R(s * XR, Y_B, Z_B), R(s * XF, Y_B, Z_CB), 0.06, unit([s * math.sin(THETA), 0.9, math.cos(THETA) * 0.7]), t=0.065, po=0.014, ext=0.0)
    # -- sill rails along the side walls' feet (from the rear bow to the corner posts' feet), wall-top rails on the shoulder line
    for s in (1, -1):
        b.bar(R(s * XF, Y_B, Z_R), R(s * XF, Y_B, Z_CB), 0.075, unit([s * 0.7, 0.7, 0]), t=0.06, po=0.02, ext=0.0)
        b.bar(R(s * XF, YS, Z_R), R(s * XF, YS, Z_SH), 0.045, unit([s * 0.8, 0.6, 0]), t=0.05, po=0.014, ext=0.0)
    # -- the roof: mid bow, centre rib, outer rails
    b.bar(R(-XR, H(Z_M), Z_M), R(XR, H(Z_M), Z_M), 0.045, n_roof, t=0.05, po=0.014)
    b.bar(R(0, H(Z_M), Z_M), R(0, H(Z_A), Z_A), 0.045, n_roof, t=0.05, po=0.014)
    for s in (1, -1):
        b.bar(R(s * XR, H(Z_R), Z_R), R(s * XR, H(Z_A), Z_A), 0.045, unit([s * 0.4, 1.0, K_ROOF]), t=0.05, po=0.014)
    # -- the rear bow against the hump's front face: along the section at Z_R (5.5 cm deep in z, 6 cm radially, 1.2 cm proud)
    zr0, zr1 = Z_R, Z_R + 0.055
    zc = 0.5 * (zr0 + zr1)
    sec = [(XF, Y_B), (XF, YS), (XR, H_REAR), (0.0, H_REAR)]
    for k in range(3):
        (x0, y0), (x1, y1) = sec[k], sec[k + 1]
        u2 = unit([x1 - x0, y1 - y0, 0.0])
        out2 = np.array([u2[1], -u2[0], 0.0])                 # outward (up and out) for the right half
        for s in (1, -1):
            q0, q1 = R(s * x0, y0, zc), R(s * x1, y1, zc)
            o = R(s * out2[0], out2[1], 0.0)
            u = unit(q1 - q0)
            sdir = np.cross(u, o)
            cc = (q0 + q1) / 2 + o * (0.012 - 0.03)
            b.obox(cc, (u, sdir, o), (np.linalg.norm(q1 - q0) / 2 + 0.02, (zr1 - zr0) / 2, 0.03))    # -- the side windows' frames
    for s in (1, -1):
        y0, y1 = SW_Y
        z0, z1 = SW_Z
        nn = R(s * 1.0, 0, 0)
        for (p, q) in (((z0, y0), (z1, y0)), ((z1, y0), (z1, y1)), ((z1, y1), (z0, y1)), ((z0, y1), (z0, y0))):
            b.bar(R(s * XF, p[1], p[0]), R(s * XF, q[1], q[0]), 0.036, nn, t=0.04, po=0.012, ext=0.018)
    # -- the skin between the windows (slim opaque panels in the hump's grey; outer faces toward the outside)
    for s in (1, -1):
        sx = lambda p: np.array([s * p[0], p[1], p[2]])
        # the rear roof, one rectangle of the roof plane per half
        zp = Z_R + 0.012                       # (the skin's rear edge stands inside the rear bow: no face of it lies in the bow's rear plane)
        rz = [R(0, H(zp), zp), R(XR, H(zp), zp), R(XR, H(Z_M), Z_M), R(0, H(Z_M), Z_M)]
        b.poly_slab([sx(p) for p in rz], n_roof, 0.012, sk)
        # the shoulder chamfer: from the roof edge down to the wall top
        e0, e1 = R(XR, H(zp), zp), R(XR, H(Z_A), Z_A)
        w0, w1 = R(XF, YS, zp), R(XF, YS, Z_SH)
        out = unit([s * 1.0, 0.7, 0.2])
        b.poly_slab([sx(e0), sx(e1), sx(w1), sx(w0)], out, 0.012, sk)
        # the wall: x = XF; around the window
        def wq(pa, pb, pc, pd):
            b.poly_slab([sx(R(XF, y, z)) for z, y in (pa, pb, pc, pd)], [s * 1.0, 0, 0], 0.012, sk)
        z0, z1 = SW_Z
        y0, y1 = SW_Y
        zf = lambda y: Z_CB - (y - Y_B) / (YS - Y_B) * (Z_CB - Z_SH)      # the wall's front edge (the rake) at height y
        zp = Z_R + 0.012
        wq((zp, Y_B), (zf(Y_B), Y_B), (zf(y0), y0), (zp, y0))                # under the window
        wq((zp, y1), (zf(y1), y1), (zf(YS), YS), (zp, YS))                   # over the window
        wq((zp, y0), (z0, y0), (z0, y1), (zp, y1))                            # behind it
        wq((z1, y0), (zf(y0), y0), (zf(y1), y1), (z1, y1))                   # ahead of it
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
          f'{len(tris)} glass triangles (x2 windings): two flat front panes, two flat corner panes, two eyebrow and two side windows')
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

