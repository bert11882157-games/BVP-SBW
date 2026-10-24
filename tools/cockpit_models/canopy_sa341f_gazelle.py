#!/usr/bin/env python3
"""SLIM-FRAME PASS (supersedes the layout text below where it differs): the real Gazelle is a clear bubble on few slim members, so the model's
thick cage members (belt rail, upper rail, mid post, A / B pillars, door posts, centre post and its braces) are cut and only these are rebuilt
slim (4 cm, along the model's own lines): centre post, A pillars, B pillars, one post splitting each door window; the belt, chin and keel bars
and the roof window's cross bar are gone. Glass: rear door (flat), front door (wraps the A pillar), shoulder strips, rear quarter windows,
the overhead window, and one wrap-round windscreen-and-chin pane each side of the centre post. Roof, sill and rear structure are body skin and stay.

SA 341F Gazelle glasshouse, hand-built for the Aerospatiale Gazelle.mtb airframe (commits aeff8294, b9b6bfc7): the model's own frame
cage, glazed.

The model's cabin is a cage of slim members (5 x 10 cm boxes: sill rails, B pillar, door post, a belt rail, an upper rail, the roof rail, the
leaning A pillars, a centre post that runs from the roof's front edge down to the nose keel) with open apertures between them and no glass
anywhere (frame rows 982 / 1003 of the source, read off checks/orig). The Gazelle's real glasshouse is the same layout: a light frame
of few members round big panes. This script keeps that cage as the outer mould line (every member stays where it is) and
  * cuts the old interior (both seat groups, the old instrument block, the floor controls, pedals and the overhead switch plate: CUT_BOXES,
    element boxes read off the source; faces that also lie in a kept element's box stay) so the cockpit script can build the new one;
  * glazes every opening of the model's frame:
      - door windows: per side a rear and a front door, each a lower and an upper window between the sill, the belt rail, the upper rail
        and the posts (B pillar, door post, a slim centre post of the front door, the A pillar); all FLAT, in two planes per side (the
        lower band leans out 5.6 degrees, the upper band in 8 degrees, the planes of the model's own members), listed in flat_panes;
      - a shoulder strip (the roof window band) between the upper rail and the roof rail, a ruled strip, and the small rear quarter
        window behind the B pillar (the glasshouse's rear edge, z 2.26 .. 2.72), a ruled pane;
      - the PILOT's overhead window: the roof over the right seat is open in the model (the left roof carries the sight), glazed with two
        flat panes in one plane with a slim cross bar (flat_panes);
      - the windscreen: two big curved panes (a gentle bubble) between the roof front edge, the A pillar, the centre post and a belt bar
        at the dash height; and the chin windows below them, down to a bottom bar over the sill front and the nose keel;
  * adds the slim members the cage lacks (belt bar, chin bar, keel post bar, the roof window's cross bar; 4 cm) in the airframe colours.
The glass stops at every member edge (the opening outlines are the members' own faces; the nose panes stop 2 cm short of their bars).

CANOPY (eye-relative sections, the right half from the sill up over the top, windscreen included) is what the checks in
work/elite_expansion_2/checks read for the canopy outline. The pilot's eye stands 0.465 off the glasshouse axis (side-by-side seats):
the checks measure from the eye, so they run through gazelle/check_gazelle.py, which sets the origin to the axis (x 0, the eye's y and z).

Run first (on the airframe's files); the cockpit script sa341f_gazelle.py follows.
Usage: python tools/cockpit_models/canopy_sa341f_gazelle.py [--dry-run]
Frames: vehicle blocks (x left, y up, z forward; 1 block = 1 m on this model); the mesh is written in model pixels (x, y, -z) * 16."""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

VID = 'sa341f_gazelle'
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, f'assets/berts_vehicle_pack/custom_geo/{VID}.geo.json')
ASSET = os.path.join(GEN, f'assets/berts_vehicle_pack/sbw/vehicles/{VID}.json')
DATA = os.path.join(GEN, f'data/berts_vehicle_pack/sbw/vehicles/{VID}.json')
TEXTURE = L.texture_path(VID)
HULL = 'hull'
BARS_BONE = 'hull__canopy'


def _eye():
    try:
        d = json.load(open(DATA, encoding='utf-8'))
        return np.array(d['Attachments']['pilot_eye']['Position'], float)
    except (OSError, KeyError):
        return np.array([-0.46519, 1.67468, 3.45515])


EYE = _eye()                                   # the pilot's eye (right seat, x < 0)
CENTRE = np.array([0.0, EYE[1], EYE[2]])       # the glasshouse axis at the eye's height: the origin of CANOPY and of the checks

# ---- the old interior and its seat groups, cut out: every face wholly inside one of CUT_BOXES and inside none of KEEP_BOXES
# (absolute blocks x0 y0 z0 x1 y1 z1, read off the source elements: rows 455 seats, 436 instrument block, 398 floor controls / pedals /
# consoles, from z 2.585 forward, minus the nose keel's base 537, the rear sill post 548 and the rear block 356 / 357 / 361). EXPECT_CUT is the face count
# the first run must find (the airframe must be the committed one); a second run finds none.
CUT_BOXES = [
    (0.827, 0.724, 2.721, 0.930, 1.240, 2.928), (0.827, 1.240, 2.670, 0.930, 1.602, 2.825),
    (0.724, 1.602, 2.618, 0.879, 1.809, 2.773), (0.724, 0.724, 3.807, 0.879, 1.240, 4.117),
    (0.724, 1.240, 3.703, 0.879, 1.602, 3.962), (0.672, 1.602, 3.652, 0.827, 1.706, 3.858),
    (0.817, 1.137, 3.341, 0.930, 1.240, 3.817), (0.879, 1.137, 2.825, 0.930, 1.240, 3.341),
    (0.827, 0.724, 3.186, 0.930, 1.137, 3.341), (0.749, 1.602, 3.290, 0.879, 1.654, 3.703),
    (0.801, 1.602, 2.757, 0.879, 1.654, 3.290), (0.827, 1.240, 3.186, 0.930, 1.602, 3.341),
    (0.000, 1.757, 3.755, 0.052, 1.912, 4.065), (0.000, 1.602, 4.065, 0.052, 1.809, 4.324),
    (0.000, 1.292, 4.272, 0.052, 1.654, 4.685), (0.791, 1.240, 3.548, 0.894, 1.602, 3.703),
    (-0.930, 0.724, 2.721, -0.827, 1.240, 2.928), (-0.930, 1.240, 2.670, -0.827, 1.602, 2.825),
    (-0.879, 1.602, 2.618, -0.724, 1.809, 2.773), (-0.879, 0.724, 3.807, -0.724, 1.240, 4.117),
    (-0.879, 1.240, 3.703, -0.724, 1.602, 3.962), (-0.827, 1.602, 3.652, -0.672, 1.706, 3.858),
    (-0.930, 1.137, 3.341, -0.817, 1.240, 3.817), (-0.930, 1.137, 2.825, -0.879, 1.240, 3.341),
    (-0.930, 0.724, 3.186, -0.827, 1.137, 3.341), (-0.879, 1.602, 3.290, -0.749, 1.654, 3.703),
    (-0.879, 1.602, 2.757, -0.801, 1.654, 3.290), (-0.930, 1.240, 3.186, -0.827, 1.602, 3.341),
    (-0.052, 1.757, 3.755, 0.000, 1.912, 4.065), (-0.052, 1.602, 4.065, 0.000, 1.809, 4.324),
    (-0.052, 1.292, 4.272, 0.000, 1.654, 4.685), (-0.894, 1.240, 3.548, -0.791, 1.602, 3.703),
    (-0.672, 0.569, 3.238, -0.620, 0.724, 3.548), (-0.724, 0.724, 3.186, -0.207, 0.775, 3.652),
    (-0.724, 0.724, 3.652, -0.207, 0.775, 3.703), (-0.724, 0.775, 3.135, -0.207, 1.085, 3.238),
    (-0.724, 1.085, 3.083, -0.207, 1.447, 3.186), (-0.724, 1.447, 3.083, -0.207, 1.499, 3.135),
    (-0.724, 0.775, 3.135, -0.672, 1.085, 3.445), (-0.310, 0.569, 3.238, -0.258, 0.724, 3.548),
    (-0.258, 0.775, 3.135, -0.207, 1.085, 3.445), (0.620, 0.569, 3.238, 0.672, 0.724, 3.548),
    (0.207, 0.724, 3.186, 0.724, 0.775, 3.652), (0.207, 0.724, 3.652, 0.724, 0.775, 3.703),
    (0.207, 0.775, 3.135, 0.724, 1.085, 3.238), (0.207, 1.085, 3.083, 0.724, 1.447, 3.186),
    (0.207, 1.447, 3.083, 0.724, 1.499, 3.135), (0.672, 0.775, 3.135, 0.724, 1.085, 3.445),
    (0.258, 0.569, 3.238, 0.310, 0.724, 3.548), (0.207, 0.775, 3.135, 0.258, 1.085, 3.445),
    (-0.155, 0.569, 3.186, 0.155, 0.724, 3.652), (-0.155, 0.569, 3.652, 0.155, 0.827, 4.013),
    (-0.155, 0.569, 4.013, 0.155, 0.827, 4.375), (-0.155, 0.827, 4.013, 0.155, 1.085, 4.375),
    (-0.465, 0.827, 4.013, -0.155, 1.085, 4.375), (-0.465, 1.085, 4.065, 0.155, 1.396, 4.375),
    (-0.465, 1.085, 4.375, 0.155, 1.396, 4.582), (-0.155, 0.672, 4.375, 0.155, 1.085, 4.789),
    (-0.155, 0.569, 4.375, 0.155, 0.672, 4.789), (-0.543, 0.594, 3.703, -0.388, 0.905, 3.858),
    (-0.491, 0.905, 3.755, -0.439, 1.060, 3.807), (-0.491, 1.008, 3.703, -0.439, 1.111, 3.807),
    (-0.491, 1.060, 3.703, -0.439, 1.266, 3.755), (-0.413, 0.536, 3.912, -0.362, 0.777, 4.102),
    (-0.413, 0.705, 4.030, -0.310, 0.777, 4.102), (-0.517, 0.536, 3.912, -0.465, 0.777, 4.102),
    (-0.620, 0.705, 4.030, -0.517, 0.777, 4.102), (0.388, 0.594, 3.703, 0.543, 0.905, 3.858),
    (0.439, 0.905, 3.755, 0.491, 1.060, 3.807), (0.439, 1.008, 3.703, 0.491, 1.111, 3.807),
    (0.439, 1.060, 3.703, 0.491, 1.266, 3.755), (0.362, 0.536, 3.912, 0.413, 0.777, 4.102),
    (0.465, 0.705, 4.030, 0.569, 0.777, 4.102), (0.465, 0.536, 3.912, 0.517, 0.777, 4.102),
    (0.310, 0.705, 4.030, 0.413, 0.777, 4.102), (-0.310, 0.672, 2.721, -0.052, 0.982, 2.876),
    (-0.620, 0.724, 2.721, -0.362, 0.879, 2.876), (-0.413, 1.318, 4.039, -0.207, 1.421, 4.246),
    (-0.258, 1.421, 4.039, -0.207, 1.628, 4.091), (-0.258, 1.421, 4.194, -0.207, 1.525, 4.246),
    (-0.258, 1.473, 4.091, -0.207, 1.628, 4.246), (-0.258, 1.473, 4.091, -0.207, 1.525, 4.246),
    (-0.413, 1.421, 4.039, -0.362, 1.628, 4.091), (-0.413, 1.421, 4.194, -0.362, 1.525, 4.246),
    (-0.413, 1.473, 4.091, -0.362, 1.628, 4.246), (-0.413, 1.473, 4.091, -0.362, 1.525, 4.246),
    (-0.388, 1.396, 4.065, -0.233, 1.654, 4.065), (-0.026, 0.712, 3.515, 0.026, 0.875, 3.600),
    (-0.078, 0.886, 3.474, 0.078, 0.946, 3.576), (-0.078, 0.843, 3.474, 0.078, 0.903, 3.576),
    (-0.005, 1.551, 4.396, 0.005, 1.706, 4.406), (0.052, 1.499, 4.143, 0.103, 1.809, 4.453),
    (0.052, 1.240, 4.324, 0.207, 1.654, 4.685), (-0.103, 1.499, 4.143, -0.052, 1.809, 4.453),
    (-0.207, 1.240, 4.324, -0.052, 1.654, 4.685), (0.905, 1.163, 2.773, 0.956, 1.215, 2.980),
    (-0.956, 1.163, 2.773, -0.905, 1.215, 2.980), (-0.155, 0.672, 4.789, 0.155, 1.085, 4.840),
    (-0.155, 1.396, 4.065, 0.103, 1.499, 4.168), (-0.517, 0.879, 2.773, -0.465, 0.930, 2.825),
    (-0.620, 0.879, 2.721, -0.569, 0.930, 2.773), (-0.620, 0.930, 2.618, -0.569, 0.982, 2.773),
    (-0.517, 0.930, 2.618, -0.465, 0.982, 2.825), (-0.155, 0.982, 2.566, -0.103, 1.034, 2.825),
    (-0.282, 0.982, 2.659, -0.194, 1.034, 2.821), (-0.775, 0.930, 2.566, -0.103, 0.982, 2.618),
    (-0.508, 0.930, 2.596, -0.194, 0.982, 2.721), (0.775, 0.672, 2.721, 0.827, 0.930, 2.825),
    (-0.775, 0.672, 2.721, -0.724, 0.930, 2.825), (-0.078, 1.861, 3.316, 0.078, 1.912, 3.367),
    (-0.078, 1.861, 3.264, 0.078, 1.912, 3.316), (-0.078, 1.861, 3.212, 0.078, 1.912, 3.264),
    (-0.155, 1.809, 3.445, 0.155, 1.861, 3.755), (0.052, 1.757, 3.600, 0.103, 1.809, 3.652),
    (-0.052, 1.706, 3.600, 0.000, 1.809, 3.652),
]
KEEP_BOXES = [
    (0.724, 0.569, 3.341, 0.879, 0.724, 3.858), (0.827, 0.569, 2.825, 0.879, 0.724, 3.341),
    (0.827, 0.569, 2.153, 0.879, 0.724, 2.825), (0.000, 0.413, 2.825, 0.879, 0.569, 3.341),
    (0.000, 1.861, 3.031, 0.672, 2.016, 3.445), (0.620, 1.757, 2.153, 0.775, 1.912, 2.618),
    (0.620, 1.809, 2.618, 0.775, 1.912, 3.031), (0.000, 0.413, 3.341, 0.879, 0.569, 3.858),
    (0.000, 0.465, 3.858, 0.775, 0.569, 4.220), (0.620, 0.569, 3.858, 0.827, 0.724, 4.220),
    (0.827, 0.724, 2.256, 0.930, 1.240, 2.825), (0.620, 1.706, 3.445, 0.775, 1.861, 3.652),
    (0.000, 1.809, 3.445, 0.672, 1.964, 3.652), (0.569, 1.706, 3.652, 0.775, 1.809, 3.807),
    (0.000, 1.861, 3.652, 0.517, 1.938, 3.755), (0.000, 1.085, 4.634, 0.207, 1.292, 4.840),
    (0.000, 0.879, 4.789, 0.207, 1.085, 4.892), (0.000, 0.724, 4.840, 0.207, 0.879, 4.892),
    (0.000, 0.569, 4.737, 0.207, 0.724, 4.892), (0.000, 0.413, 4.530, 0.207, 0.620, 4.789),
    (-0.879, 0.569, 3.341, -0.724, 0.724, 3.858), (-0.879, 0.569, 2.825, -0.827, 0.724, 3.341),
    (-0.879, 0.569, 2.153, -0.827, 0.724, 2.825), (-0.879, 0.413, 2.825, 0.000, 0.569, 3.341),
    (-0.207, 1.861, 3.031, 0.000, 2.016, 3.445), (-0.775, 1.757, 2.153, -0.620, 1.912, 2.618),
    (-0.775, 1.809, 2.618, -0.620, 1.912, 3.031), (-0.879, 0.413, 3.341, 0.000, 0.569, 3.858),
    (-0.775, 0.465, 3.858, 0.000, 0.569, 4.220), (-0.827, 0.569, 3.858, -0.620, 0.724, 4.220),
    (-0.930, 0.724, 2.256, -0.827, 1.240, 2.825), (-0.775, 1.706, 3.445, -0.620, 1.861, 3.652),
    (-0.207, 1.861, 3.445, 0.000, 1.964, 3.652), (-0.775, 1.706, 3.652, -0.569, 1.809, 3.807),
    (-0.517, 1.861, 3.652, 0.000, 1.938, 3.755), (-0.207, 1.085, 4.634, 0.000, 1.292, 4.840),
    (-0.207, 0.879, 4.789, 0.000, 1.085, 4.892), (-0.207, 0.724, 4.840, 0.000, 0.879, 4.892),
    (-0.207, 0.569, 4.737, 0.000, 0.724, 4.892), (-0.207, 0.413, 4.530, 0.000, 0.620, 4.789),
    (-0.775, 0.569, 2.153, 0.827, 0.672, 2.825), (-0.775, 0.672, 2.153, 0.827, 0.930, 2.721),
    (-0.672, 0.930, 2.153, 0.724, 1.292, 2.566), (-1.602, 0.724, 2.514, -0.879, 0.930, 2.773),
    (-0.026, 1.912, 3.497, 0.026, 2.119, 4.117), (-0.026, 1.912, 3.703, 0.181, 2.016, 3.910),
    (-0.181, 1.912, 3.703, 0.026, 2.016, 3.910), (-0.155, 0.413, 4.220, 0.155, 0.569, 4.737),
    (-0.827, 0.569, 2.566, -0.775, 0.982, 2.618),
]
EXPECT_CUT = 641
CUT_TOL = 0.004

# ---- the model's frame lines (blocks), read off the source elements' corners
GREEN = (74, 101, 62)                          # right-side frame colour of the airframe
TAN = (109, 91, 62)                            # left-side frame colour
BAR = 0.04                                     # the slim members this script adds: 4 cm square
GAP = 0.02                                     # the nose panes stop this far from a bar's centre line (half the bar)


def lerp_pl(pl, y):
    """piecewise-linear value at y of a polyline [(y, v), ...] sorted by y (clamped)"""
    ys = [p[0] for p in pl]
    return float(np.interp(y, ys, [p[1] for p in pl]))


# edges of the openings in the side wall as z over y
B_FRONT = [(0.724, 2.928), (1.240, 2.825), (1.602, 2.773), (1.809, 2.721)]     # B pillar's front edge
D_REAR_L = [(0.724, 3.290), (1.137, 3.186)]                                      # door post rear edge, lower band
D_FRONT_L = [(0.724, 3.341), (1.137, 3.238)]
D_REAR_U = [(1.240, 3.290), (1.602, 3.186)]                                      # upper band
D_FRONT_U = [(1.240, 3.341), (1.602, 3.238)]
M_REAR = [(1.240, 3.652), (1.602, 3.548)]                                        # the front door's slim post (upper band only)
M_FRONT = [(1.240, 3.703), (1.602, 3.600)]
A_REAR = [(0.724, 3.858), (1.240, 3.807), (1.602, 3.703), (1.706, 3.652)]        # A pillar's rear edge
# the A pillar's front edge (x, y, z) from the roof corner down to the sill front
A_FRONT = [(0.698, 1.706, 3.807), (0.750, 1.602, 3.858), (0.800, 1.240, 3.962), (0.750, 0.724, 4.117)]


def A_at(y):
    """the A pillar's front edge at height y: (x, y, z)"""
    ys = [p[1] for p in A_FRONT][::-1]
    xs = [p[0] for p in A_FRONT][::-1]
    zs = [p[2] for p in A_FRONT][::-1]
    return np.array([np.interp(y, ys, xs), y, np.interp(y, ys, zs)])


# ---- the slim members that stay (the real Gazelle's layout: the centre post, the A pillars, the B pillars, one post splitting each
# door window; no belt rail, no upper rail, no windscreen bars): centre lines, 4 cm square, rebuilt along the model's own member lines
HW = BAR / 2
XS = 0.880                                      # the door glass plane (vertical), the model's sill / door-frame mid-plane
B_MID = [(0.724, 2.8765), (1.240, 2.773), (1.602, 2.721), (1.809, 2.6695)]      # B pillar: z over y
D_MID = [(0.724, 3.315), (1.654, 3.212)]                                         # the door post splitting the door window
A_MID = [(0.698, 1.706, 3.7295), (0.800, 1.240, 3.8845), (0.750, 0.724, 3.9875)]   # A pillar centre line (x, y, z), top to foot
Y_TOP = 1.654                                    # the door glass's upper edge (under the shoulder strip)


def bx(y):
    """the B pillar's x at height y (vertical in the door plane, then in to the roof rail)"""
    return XS if y <= 1.602 else float(np.interp(y, [1.602, 1.809], [XS, 0.76]))


def A_x_at(y):
    ys = [p[1] for p in A_MID][::-1]
    return float(np.interp(y, ys, [p[0] for p in A_MID][::-1])), float(np.interp(y, ys, [p[2] for p in A_MID][::-1]))


def ruled(left, right):
    """triangles of a strip between two polylines of 3D points with the same count (rows)"""
    out = []
    for i in range(len(left) - 1):
        out.append((left[i], right[i], right[i + 1]))
        out.append((left[i], right[i + 1], left[i + 1]))
    return out


def rear_door():
    """flat: between the B pillar and the door post, sill to the shoulder strip, in the plane x = XS"""
    ys = [0.724, Y_TOP]
    L_ = [np.array([XS, y, lerp_pl(B_MID, y) + HW]) for y in ys]
    R_ = [np.array([XS, y, lerp_pl(D_MID, y) - HW]) for y in ys]
    return ruled(L_, R_)


def front_door():
    """between the door post and the A pillar (which leans in toward the nose, so the pane is a ruled quad, not flat)"""
    ys = [0.724, 1.24, Y_TOP]
    L_ = [np.array([XS, y, lerp_pl(D_MID, y) + HW]) for y in ys]
    R_ = []
    for y in ys:
        x, z = A_x_at(y)
        R_.append(np.array([x + 0.03, y, z - HW]))
    return ruled(L_, R_)


# the shoulder strip (the roof window band): from the door glass's top edge to the roof rail's lower edge (x 0.7495)
SH_Z = [2.74, 3.031, 3.445, 3.70]
SH_TOPY = [1.809, 1.809, 1.757, 1.708]


def shoulder_strip():
    b = [np.array([XS, Y_TOP, z]) for z in SH_Z]
    t = [np.array([0.7495, y, z - (0.04 if i in (0, 3) else 0.0) * (1 if i == 0 else -1)]) for i, (z, y) in enumerate(zip(SH_Z, SH_TOPY))]
    return ruled(b, t)


# the rear quarter window behind the B pillar (z 2.256 .. 2.75): a small ruled pane
RQ_BOT = [(2.256, 1.240), (2.750, 1.240)]
RQ_TOP = [(2.256, 1.757), (2.650, 1.809)]


def rear_quarter():
    b = [np.array([0.9045, y, z]) for z, y in RQ_BOT]
    t = [np.array([0.7495, y, z]) for z, y in RQ_TOP]
    return [(b[0], b[1], t[1]), (b[0], t[1], t[0])]


# the pilot's overhead window (right roof, x < 0): one pane in one roof plane y = ROOF_Y0 + ROOF_K (x + 0.31), no bar
ROOF_Y0, ROOF_K = 1.99, 0.27
RW_X_OUT = -0.62


def roof_y(x):
    return ROOF_Y0 + ROOF_K * (x + 0.31)


def roof_window():
    def quad(x0, x1, z0, z1):
        p = [np.array([x, roof_y(x), z]) for x, z in ((x0, z0), (x1, z0), (x1, z1), (x0, z1))]
        return [(p[0], p[1], p[2]), (p[0], p[2], p[3])]
    return quad(-0.31, RW_X_OUT, 2.618, 3.031) + quad(-0.31, RW_X_OUT, 3.031, 3.652) + quad(-0.207, -0.31, 3.031, 3.652)


# ---- the nose: the windscreen (one pane each side of the centre post, running down into the chin window; no bars)
POST_X = HW                                              # the centre post's half width: the glass stops here
POST_PTS = [(0.052, 1.861, 3.755), (0.052, 1.757, 4.065), (0.052, 1.602, 4.324), (0.052, 1.292, 4.634)]   # the model's post (outline)
POST_MID = [(3.755, 1.886), (4.065, 1.783), (4.298, 1.628), (4.660, 1.292)]          # the slim post's centre line (z, y) at x 0
POST_FOOT = np.array([POST_X, 1.292, 4.640])
WEDGE = np.array([0.207, 1.292, 4.664])                  # the keel wedge's top corner
KEEL_BOT = np.array([0.207, 0.724, 4.790])               # the keel's bottom corner
KEEP_X = 0.207
ROOF_FRONT = [(POST_X, 1.861, 3.755), (0.517, 1.861, 3.755), (0.620, 1.809, 3.755), (0.698, 1.706, 3.807)]
BULGE = 0.05
OUT_N = np.array([0.779, 0.0, 0.627])                    # plan direction out and forward from the nose chord


def poly_len_param(pl, n):
    """n+1 points along a polyline at equal chord-length fractions"""
    P = [np.array(p, float) for p in pl]
    d = np.r_[0, np.cumsum([np.linalg.norm(P[i + 1] - P[i]) for i in range(len(P) - 1)])]
    t = np.linspace(0, d[-1], n + 1)
    return np.array([[np.interp(tt, d, [p[k] for p in P]) for k in range(3)] for tt in t])


def coons(B, T, Lc, Rc, nu, nv, bulge, amp):
    """a Coons patch from four boundary polylines (bottom, top: left to right; left, right: bottom to top) with a bulge toward OUT_N"""
    Bp, Tp = poly_len_param(B, nu), poly_len_param(T, nu)
    Lp, Rp = poly_len_param(Lc, nv), poly_len_param(Rc, nv)
    P00, P10, P01, P11 = Bp[0], Bp[-1], Tp[0], Tp[-1]
    G = np.zeros((nv + 1, nu + 1, 3))
    for j in range(nv + 1):
        v = j / nv
        for i in range(nu + 1):
            u = i / nu
            s = ((1 - v) * Bp[i] + v * Tp[i] + (1 - u) * Lp[j] + u * Rp[j]
                 - ((1 - u) * (1 - v) * P00 + u * (1 - v) * P10 + (1 - u) * v * P01 + u * v * P11))
            s = s + OUT_N * (amp * math.sin(math.pi * u) * math.sin(math.pi * v) ** 0.8 * (1.0 if bulge else 0.0))
            G[j, i] = s
    tris = []
    for j in range(nv):
        for i in range(nu):
            a, b, c, d = G[j, i], G[j, i + 1], G[j + 1, i + 1], G[j + 1, i]
            tris.append((a, b, c))
            tris.append((a, c, d))
    return tris


def nose_panes():
    """the windscreen and chin pane on the left (one patch from the roof's front edge down to the chin floor)"""
    kb = np.array([KEEP_X, 0.760, KEEL_BOT[2] - 0.001])
    foot = A_at(0.760)
    a_pts = [foot] + [A_at(y) + np.array([0.0, 0.0, HW]) for y in np.linspace(0.80, 1.706, 10)]
    a_pts[-1] = np.array(ROOF_FRONT[-1])
    Lc = [kb, np.array([KEEP_X, 1.0, 4.74]), WEDGE, POST_FOOT] + [np.array([POST_X, y, z]) for z, y in POST_MID[::-1][1:]]
    Lc[-1] = np.array(ROOF_FRONT[0])
    T = [np.array(p) for p in ROOF_FRONT]
    return coons([kb, foot], T, Lc, a_pts, 12, 16, True, BULGE)


def mir(p):
    return np.array([-p[0], p[1], p[2]])


def glass_polys():
    """name -> (triangles on the left, flat on the real aircraft, 'both' sides or the 'right' only)"""
    out = {}
    out['door rear'] = (rear_door(), True, 'both')
    out['door front'] = (front_door(), False, 'both')
    out['shoulder strip'] = (shoulder_strip(), False, 'both')
    out['rear quarter window'] = (rear_quarter(), False, 'both')
    out['windscreen and chin window'] = (nose_panes(), False, 'both')
    out['overhead window'] = (roof_window(), True, 'right')
    return out


def glass_triangles():
    """every glass triangle, both sides, one winding"""
    tris = []
    for name, (ts, flat, side) in glass_polys().items():
        for t in ts:
            tris.append(t)
            if side == 'both':
                tris.append(tuple(mir(p) for p in t))
    return tris


# ---------------------------------------------------------------- the new slim members
def unit(v):
    v = np.array(v, float)
    return v / np.linalg.norm(v)


def ear_clip(P):
    """triangles (index triples) of a simple plan polygon (x, z points), by ear clipping"""
    P = [np.array(p, float) for p in P]
    n = len(P)
    area = sum(P[i][0] * P[(i + 1) % n][1] - P[(i + 1) % n][0] * P[i][1] for i in range(n))
    idx = list(range(n)) if area > 0 else list(range(n))[::-1]
    out = []
    cr = lambda a, b, c: (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
    guard = 0
    while len(idx) > 3 and guard < 1000:
        guard += 1
        for k in range(len(idx)):
            i0, i1, i2 = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            a, b, c = P[i0], P[i1], P[i2]
            if cr(a, b, c) <= 1e-12:
                continue
            if any(cr(a, b, P[j]) >= -1e-12 and cr(b, c, P[j]) >= -1e-12 and cr(c, a, P[j]) >= -1e-12
                   for j in idx if j not in (i0, i1, i2)):
                continue
            out.append((i0, i1, i2))
            idx.pop(k)
            break
    out.append(tuple(idx))
    return out


class Solids:
    """closed boxes as a poly mesh in model pixels; every face has one winding, outward from the solid"""

    def __init__(self):
        self.P, self.N, self.U, self.polys = [], [], [], []

    def face(self, pts, n, uv):
        px = [[float(q[0]) * 16, float(q[1]) * 16, -float(q[2]) * 16] for q in pts]
        npx = np.array([n[0], n[1], -n[2]])
        a, b, c = (np.array(q) for q in px[:3])
        if np.cross(b - a, c - a) @ npx > 0:       # the mesh loader mirrors X: in file pixels a face winds clockwise seen from its front
            px = px[::-1]
        ni = len(self.N)
        self.N.append([round(float(x), 5) + 0.0 for x in -npx])
        ui = len(self.U)
        self.U.append(list(uv))
        poly = []
        for p in px:
            poly.append([len(self.P), ni, ui])
            self.P.append([round(x, 4) + 0.0 for x in p])
        self.polys.append(poly)

    def obox(self, c, axes, half, uv):
        c = np.array(c, float)
        A = [unit(a) for a in axes]
        for k in range(3):
            for s in (-1, 1):
                i, j = [m for m in range(3) if m != k]
                fc = c + A[k] * half[k] * s
                q = [fc + A[i] * half[i] * a + A[j] * half[j] * b for a, b in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
                n = np.cross(q[1] - q[0], q[2] - q[0])
                n = n / np.linalg.norm(n)
                if n @ (fc - c) < 0:
                    n = -n
                self.face(q, n, uv)

    def beam(self, a, b, w, t, uv, up=(0, 1, 0), ext=0.0):
        """a straight member from a to b, w wide (across, near vertical) and t deep, its ends run on by ext"""
        a, b = np.array(a, float), np.array(b, float)
        u = unit(b - a)
        v = np.array(up, float)
        v = v - (v @ u) * u
        if np.linalg.norm(v) < 1e-6:
            v = np.array([1.0, 0, 0]) - u[0] * u
        v = unit(v)
        s = unit(np.cross(u, v))
        self.obox((a + b) / 2, (u, v, s), (np.linalg.norm(b - a) / 2 + ext, w / 2, t / 2), uv)

    def prism(self, poly, y0, y1, uv_top, uv_bot, uv_side, open_x=None):
        """a convex plan polygon [(x, z), ...] extruded between y0 and y1; the edges lying on x = open_x get no wall"""
        P = [np.array(p, float) for p in poly]
        cen = np.mean(P, 0)
        for i0, i1, i2 in ear_clip(P):
            self.face([(P[k][0], y1, P[k][1]) for k in (i0, i1, i2)], (0, 1, 0), uv_top)
            self.face([(P[k][0], y0, P[k][1]) for k in (i0, i1, i2)], (0, -1, 0), uv_bot)
        for i in range(len(P)):
            a, b = P[i], P[(i + 1) % len(P)]
            if open_x is not None and abs(a[0] - open_x) < 1e-9 and abs(b[0] - open_x) < 1e-9:
                continue
            e = b - a
            n = np.array([e[1], 0.0, -e[0]])
            if n @ np.array([(a[0] + b[0]) / 2 - cen[0], 0.0, (a[1] + b[1]) / 2 - cen[1]]) < 0:
                n = -n
            self.face([(a[0], y0, a[1]), (b[0], y0, b[1]), (b[0], y1, b[1]), (a[0], y1, a[1])], n, uv_side)

    def mesh(self):
        return {'normalized_uvs': True, 'positions': self.P, 'normals': self.N, 'uvs': self.U, 'polys': self.polys}


# the nose floor ahead of the old cabin floor (z 4.22): the chin windows' floor, closed below in the airframe colours, and the skirt
# between its edge and the chin bar (the chord from the A pillar's foot to the keel)
FLOOR_TOP, FLOOR_BOT, SKIRT_TOP = 0.575, 0.50, 0.72
GREY_FLOOR = (52, 54, 52)


def chord_x(z):
    a, k = A_at(0.760), np.array([KEEP_X, 0.760, KEEL_BOT[2] - 0.001])
    return float(a[0] + (z - a[2]) / (k[2] - a[2]) * (k[0] - a[0]))


def nose_floor_poly():
    return [(0.0, 4.20), (0.62, 4.20), (0.62, 4.22), (chord_x(4.22), 4.22), (KEEP_X, KEEL_BOT[2] - 0.001), (KEEP_X, 4.53), (0.0, 4.53)]


def build_solids(uv_l, uv_r, uv_floor):
    s = Solids()
    poly = nose_floor_poly()
    for side, uv in ((1, uv_l), (-1, uv_r)):
        pl = [(side * x, z) for x, z in poly]
        s.prism(pl if side > 0 else pl[::-1], FLOOR_BOT, FLOOR_TOP, uv_floor, uv, uv, open_x=0.0)
        # the skirt: a thin plate along the chord, from the floor slab's top up to the chin bar's underside
        p0 = np.array([side * poly[3][0], 0.0, poly[3][1]])
        p1 = np.array([side * poly[4][0], 0.0, poly[4][1]])
        u = unit(p1 - p0)
        nrm = unit(np.array([u[2], 0.0, -u[0]]) * (1 if side > 0 else -1))
        if nrm[0] * side < 0:
            nrm = -nrm
        c = (p0 + p1) / 2 + np.array([0.0, (FLOOR_TOP + SKIRT_TOP) / 2, 0.0])
        s.obox(c, (u, (0, 1, 0), nrm), (np.linalg.norm(p1 - p0) / 2, (SKIRT_TOP - FLOOR_TOP) / 2, 0.006), uv)
    for side, uv in ((1, uv_l), (-1, uv_r)):
        M = (lambda p: np.array(p, float)) if side > 0 else mir
        # the A pillar: along its centre line, foot to roof
        ap = [np.array(p) for p in [(x, y, z) for (x, y, z) in A_MID][::-1]]
        for a_, b_ in zip(ap[:-1], ap[1:]):
            s.beam(M(a_), M(b_), BAR, BAR, uv, ext=0.01)
        # the B pillar: sill to roof rail
        bp = [np.array([bx(y), y, lerp_pl(B_MID, y)]) for y in (0.724, 1.24, 1.602, 1.809)]
        for a_, b_ in zip(bp[:-1], bp[1:]):
            s.beam(M(a_), M(b_), BAR, BAR, uv, up=(1, 0, 0), ext=0.01)
        # the door post that splits each door window
        s.beam(M(np.array([XS, 0.724, D_MID[0][1]])), M(np.array([XS, 1.654, D_MID[1][1]])), BAR, BAR, uv, up=(1, 0, 0), ext=0.01)
    # the centre post: one slim member on the centre line from the roof's front edge to the keel
    pp = [np.array([0.0, y, z]) for z, y in POST_MID]
    for a_, b_ in zip(pp[:-1], pp[1:]):
        s.beam(a_, b_, BAR, BAR, uv_l, up=(1, 0, 0), ext=0.01)
    return s


# ---------------------------------------------------------------- the canopy outline for the checks
def _slice(tri, z):
    """points where triangle (3 x 3) crosses the plane z"""
    d = tri[:, 2] - z
    pts = []
    for i in range(3):
        a, b = tri[i], tri[(i + 1) % 3]
        if d[i] * d[(i + 1) % 3] < 0:
            s = d[i] / (d[i] - d[(i + 1) % 3])
            pts.append(a + (b - a) * s)
        elif d[i] == 0:
            pts.append(a)
    return pts


BODY = [(0.853, 0.724), (0.9045, 1.240), (0.853, 1.602), (0.7495, 1.780), (0.620, 1.870), (0.300, 1.880), (0.0, 1.880)]
BODY_Z = [2.62, 2.9, 3.2, 3.5, 3.75]
NOSE_Z = [3.80, 3.86, 3.93, 4.00, 4.06, 4.12, 4.20, 4.30, 4.40, 4.50, 4.60, 4.70, 4.78]


def a_band_points(z):
    """points (x, y) of the A pillar's body at station z: where the band between its rear and front edges crosses the plane"""
    pts = []
    rear_y = [p[0] for p in A_REAR]
    for y in np.arange(0.724, 1.7061, 0.02):
        zr = lerp_pl(A_REAR, y)
        zf = float(np.interp(y, [p[1] for p in A_FRONT][::-1], [p[2] for p in A_FRONT][::-1]))
        if zr <= z <= zf:
            x = float(np.interp(y, [p[1] for p in A_FRONT][::-1], [p[0] for p in A_FRONT][::-1]))
            pts.append((x, float(y)))
    return pts


def nose_outline(z, tris):
    """the half section of the glasshouse at station z (x >= 0): the envelope of the glass, the A pillar's body and the centre post, from
    the lowest outer point up round to the centre line"""
    pts = []
    for t in tris:
        T = np.array(t)
        if T[:, 2].min() <= z <= T[:, 2].max():
            for p in _slice(T, z):
                pts.append((abs(p[0]), p[1]))
    pts += a_band_points(z)
    for (x, y, zz), (x2, y2, z2) in zip(A_FRONT[:-1], A_FRONT[1:]):       # the A pillar's front edge
        if min(zz, z2) <= z <= max(zz, z2) and zz != z2:
            f = (z - zz) / (z2 - zz)
            pts.append((x + (x2 - x) * f, y + (y2 - y) * f))
    for (x, y, zz), (x2, y2, z2) in zip(POST_PTS[:-1], POST_PTS[1:]):    # the centre post's lower edge
        if min(zz, z2) <= z <= max(zz, z2) and zz != z2:
            f = (z - zz) / (z2 - zz)
            pts.append((x, y + (y2 - y) * f))
    pts = [p for p in pts if p[1] >= 0.70]
    yc = 1.20
    best = {}
    for x, y in pts:
        ang = math.degrees(math.atan2(y - yc, x))
        k = int(round(ang / 4.0))
        r = math.hypot(x, y - yc)
        if k not in best or r > best[k][0]:
            best[k] = (r, x, y)
    env = [(v[1], v[2]) for k, v in sorted(best.items())]
    top = [p for p in pts if p[0] < 0.07]
    ytop = min(p[1] for p in top) if top else env[-1][1]
    env = [p for p in env if p[0] > 0.02]
    return env + [(0.0, ytop)]


def outline_rel():
    out = []
    for z in BODY_Z:
        out.append((z - CENTRE[2], [(x, y - CENTRE[1]) for x, y in BODY]))
    tris = [t for name, (ts, flat, side) in glass_polys().items() if side == 'both' for t in ts]      # (the left side's glass)
    for z in NOSE_Z:
        env = nose_outline(z, tris)
        out.append((z - CENTRE[2], [(x, y - CENTRE[1]) for x, y in env]))
    return out


CANOPY = outline_rel()                         # (the checks read this)


# ---------------------------------------------------------------- the airframe
def cut_frame(bones, already):
    """removes the old interior (CUT_BOXES) from the hull piece: every face wholly inside a cut box and inside no kept box. The first
    run must find EXPECT_CUT faces; returns the faces removed"""
    pm = next(b for b in bones if b['name'] == HULL)['poly_mesh']
    P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
    box = np.array(CUT_BOXES, float)
    lo, hi = box[:, :3] - CUT_TOL, box[:, 3:] + CUT_TOL
    kb = np.array(KEEP_BOXES, float)
    klo, khi = kb[:, :3] - CUT_TOL, kb[:, 3:] + CUT_TOL
    idx = set()
    for k, poly in enumerate(pm['polys']):
        if not poly:
            continue
        V = P[[v[0] for v in poly]]
        vl, vh = V.min(0), V.max(0)
        if np.any(np.all(vl >= lo, axis=1) & np.all(vh <= hi, axis=1)) and \
                not np.any(np.all(vl >= klo, axis=1) & np.all(vh <= khi, axis=1)):
            idx.add(k)
    n = len(idx)
    if n != EXPECT_CUT and not already and n:
        raise SystemExit(f'the interior cut found {n} faces, expected {EXPECT_CUT}: the airframe changed; re-read CUT_BOXES (nothing was written)')
    pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in idx]
    return n


def main(argv):
    dry = '--dry-run' in argv
    geo = json.load(open(GEO, encoding='utf-8'))
    asset = json.load(open(ASSET, encoding='utf-8'))
    bones = geo['minecraft:geometry'][0]['bones']
    sw = L.Swatches(TEXTURE)
    uv_l, uv_r = [float(v) for v in sw.uv(TAN)], [float(v) for v in sw.uv(GREEN)]
    bars = next((b for b in bones if b['name'] == BARS_BONE), None)
    if bars is None:
        hull = next(b for b in bones if b['name'] == HULL)
        bars = {'name': BARS_BONE, 'parent': 'hull', 'pivot': list(hull['pivot'])}
        bones.insert(bones.index(hull) + 1, bars)
    already = bool(bars.get('poly_mesh', {}).get('polys'))
    cut = cut_frame(bones, already)
    solids = build_solids(uv_l, uv_r, [float(v) for v in sw.uv(GREY_FLOOR)])
    bars['poly_mesh'] = solids.mesh()
    tris = glass_triangles()
    both = [list(map(float, p)) for t in tris for p in t] + [list(map(float, t[k])) for t in tris for k in (0, 2, 1)]
    asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Triangles': [round(v, 4) + 0.0 for p in both for v in p]}
    print(f'{VID} canopy: old interior cut ({cut} faces), {len(solids.polys)} faces of added slim members; {len(tris)} glass triangles '
          f'(x2 windings): door windows, shoulder strips, the pilot\'s overhead window, windscreen and chin windows')
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
