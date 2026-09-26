"""Identifies the modelled instruments on an aircraft's front instrument panel (see cleanup.py)."""
import math, os, sys
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'canopy_frames'))
import thin as T  # noqa: E402

CONE_AZ = 38.0          # degrees either side of the nose
CONE_EL = (-50.0, -4.0)  # below the eye line, above the pilot's knees
MAX_RANGE = 1.3          # blocks from the eye
MAX_PART = 0.075         # half-extent of a gauge, knob or switch box


def in_cone(eye, c):
    rel = c - eye
    r = float(np.linalg.norm(rel))
    if r < 0.1 or r > MAX_RANGE:
        return False
    d = rel / r
    el = math.degrees(math.asin(np.clip(d[1], -1, 1)))
    az = math.degrees(math.atan2(d[0], d[2]))
    return abs(az) < CONE_AZ and CONE_EL[0] < el < CONE_EL[1]


def instrument_boxes(geo, eye, exclude=frozenset()):
    """(bone name, poly ids) of small exported boxes (gauge bodies, bezels, knobs, switches) on the front panel."""
    out = []
    for bone in geo['minecraft:geometry'][0]['bones']:
        pm = bone.get('poly_mesh')
        if not pm or not pm.get('polys') or bone['name'].startswith('pilot_view_occluder'):
            continue
        for pis, ids, P in T.cuboids(pm):
            if any((bone['name'], pi) in exclude for pi in pis):
                continue
            c, axes, ext = T.box_frame(P)
            if ext.max() > MAX_PART or not in_cone(eye, c):
                continue
            out.append((bone['name'], pis))
    return out
