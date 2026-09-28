"""Front, side and rear hull armor of the MBTs that keep box armor or a hand-measured mesh (owner, 2026-09-28).

    python3 tools/armor_mesh/mbt_front.py [--check]

* Upper front plate: strong composite, line of sight auto_mesh.UFP_LOS (horizontal shot). The T-72A, T-72B and T-90M
  box profiles had no upper-plate box at all (a shot there was an unboxed hit): the plate is added on the model's
  own glacis plane (the upper front face of the generated hull solid), as a thin box turned about X.
* Lower front plate: 110 mm. Hull sides 80 mm, the rear plate 40 mm (box profiles).
* T-90A (hand-measured mesh): the ufp_* plates get the line-of-sight value for their slope, the lfp 110 mm.
A second run changes nothing.
"""
import json
import math
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import auto_mesh as A  # noqa: E402
import build_mesh as B  # noqa: E402

DATA = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'armor')
MESH = os.path.join(HERE, '..', '..', 'bvp', 'src', 'main', 'resources', 'data', 'berts_vehicle_pack', 'armor_mesh')
BOX = ['t72a', 't72b', 't90m']
HAND_MESH = ['t90a']
THIN = 0.03  # half thickness of an added plate box, blocks


def rot(r):
    rx, ry, rz = [math.radians(a) for a in r]
    Rx = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Ry = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Rz = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def to_profile(p):
    p = np.asarray(p, float)
    return np.array([-p[..., 0], p[..., 1], p[..., 2]]).T / 16.0 if p.ndim > 1 else np.array([-p[0], p[1], p[2]]) / 16


def hull_solid(vid):
    """The generated hull solid (geo px) for the vehicle, whatever its armor source."""
    B.SKIPPED.clear()
    _, info = A.build(vid, verbose=False)
    return np.vstack([V for f, V in info.get('solids', []) if f == 'hull'])


def glacis_plates(vid, V):
    """Box plates on the upper front faces of the hull solid (faces turned only about X)."""
    mid_y = float((V[:, 1].max() + V[:, 1].min()) / 2)
    out = []
    groups = []
    for n, d, poly in B.hull_faces(V):
        if A.aspect(n, 'hull') != 'front' or A.front_part(n, poly.mean(0), mid_y) != 'ufp' or abs(n[0]) > 0.1:
            continue
        for g in groups:
            if g[0] @ n > 1 - 1e-4:
                g[1] = np.vstack([g[1], poly])
                break
        else:
            groups.append([n, poly])
    for n, poly in groups:
        P = to_profile(poly)
        npf = np.array([0.0, n[1], n[2]])
        npf /= np.linalg.norm(npf)
        theta = math.atan2(npf[2], npf[1])
        wz = np.array([0.0, -math.sin(theta), math.cos(theta)])
        u, w = P[:, 0], P @ wz
        centre_face = np.array([(u.max() + u.min()) / 2, 0.0, 0.0])
        wc = (w.max() + w.min()) / 2
        c = P.mean(0) @ npf
        centre = np.array([centre_face[0], 0.0, 0.0]) + wc * wz + c * npf - THIN * npf
        centre[0] = centre_face[0]
        cos = abs(float(n[2]))
        out.append({'name': f'ufp_composite_{len(out):02d}', 'center': [round(float(x), 5) for x in centre],
                    'half_size': [round(float((u.max() - u.min()) / 2), 5), THIN,
                                  round(float((w.max() - w.min()) / 2), 5)],
                    'rotation': [round(math.degrees(theta), 3), 0, 0], 'frame': 'hull',
                    'armor_mm': round(A.UFP_LOS[vid] * cos, 1)})
    return out


def box_profile(vid, check):
    path = os.path.join(DATA, f'{vid}.json')
    text = open(path).read()
    d = json.loads(text)
    before = json.dumps(d, sort_keys=True)
    V = hull_solid(vid)
    centre = to_profile(V.mean(0))
    plates = [p for p in d['plates'] if not p['name'].startswith('ufp_composite_')]
    mid_y = float((V[:, 1].max() + V[:, 1].min()) / 2)
    cut_geo = A.composite_depth(V, 'hull', mid_y, vid)
    cut, cut_y = (None, None) if cut_geo is None else (cut_geo[0] / 16.0, cut_geo[1] / 16.0)
    for p in plates:
        if p.get('frame', 'hull') != 'hull':
            continue
        h = np.array(p['half_size'])
        n = rot(p.get('rotation', [0, 0, 0]))[:, int(np.argmin(h))]
        if (np.array(p['center']) - centre) @ n < 0:
            n = -n
        if abs(p['center'][0] - centre[0]) > 1.0 and p['half_size'][0] < 0.25:
            n = np.array([np.sign(p['center'][0] - centre[0]), 0.0, 0.0])   # a chunky block on the hull side
        if abs(p['center'][0] - centre[0]) > 1.0 and p['half_size'][0] < 0.25:
            n = np.array([np.sign(p['center'][0] - centre[0]), 0.0, 0.0])   # a chunky block on the hull side
        if n[2] < -0.5 and n[1] < -0.12:          # profile front is -z: lower front plate
            p['armor_mm'] = A.LFP_MM
        elif abs(n[0]) > 0.8 and cut is not None and p['center'][2] < cut + 0.15 and p['center'][1] > cut_y:
            p['armor_mm'] = float(A.UFP_LOS[vid])   # the side of the frontal composite
        elif abs(n[0]) > 0.8:
            p['armor_mm'] = A.MBT_SIDE_MM
        elif n[2] > 0.8:
            p['armor_mm'] = A.MBT_REAR_MM
    d['plates'] = plates + glacis_plates(vid, V)
    # carousel autoloader: a short thick cylinder on the hull floor under the turret (two crossed square boxes make
    # its octagonal footprint), replacing the old ring of thin boxes
    bones = A.load_model(vid)
    piv = np.array(bones['turret'].get('pivot', [0, 0, 0]), float)
    lo, hi = V.min(0), V.max(0)
    r = 0.42 * (hi[0] - lo[0])
    y0, y1 = lo[1] + 1.0, lo[1] + 1.0 + max(4.0, 0.22 * (hi[1] - lo[1]))
    c = to_profile(np.array([piv[0], (y0 + y1) / 2, piv[2]]))
    half = [round(0.86 * r / 16, 5), round((y1 - y0) / 32, 5), round(0.86 * r / 16, 5)]
    d['ammo_racks'] = [{'name': f'carousel_{k}', 'center': [round(float(x), 5) for x in c], 'half_size': half,
                        'rotation': [0, ang, 0], 'frame': 'hull'} for k, ang in ((0, 0), (1, 45))]
    if json.dumps(d, sort_keys=True) != before:
        if not check:
            open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else ''))
        return True
    return False


def hand_mesh(vid, check):
    path = os.path.join(MESH, f'{vid}.geo.json')
    text = open(path).read()
    g = json.loads(text)
    changed = False
    for b in g['minecraft:geometry'][0]['bones']:
        m = re.match(r'plate__([0-9.]+)mm__(ufp|lfp)(.*)$', b['name'])
        if not m or not b.get('poly_mesh') or 'port' in m.group(3):
            continue
        P = np.array(b['poly_mesh']['positions'], float)
        best, normal = 0.0, None
        for poly in b['poly_mesh']['polys']:
            q = P[[v[0] for v in poly]]
            cr = np.cross(q[1] - q[0], q[2] - q[0])
            if np.linalg.norm(cr) > best:
                best, normal = np.linalg.norm(cr), cr / np.linalg.norm(cr)
        mm = A.LFP_MM if m.group(2) == 'lfp' else round(A.UFP_LOS[vid] * abs(float(normal[2])), 1)
        name = f"plate__{B.fmt_mm(mm)}__{m.group(2)}{m.group(3)}"
        if name != b['name']:
            b['name'] = name
            changed = True
    if changed and not check:
        open(path, 'w').write(json.dumps(g, separators=(',', ':')) + ('\n' if text.endswith('\n') else ''))
    return changed


def main(argv):
    check = '--check' in argv
    changed = [v for v in BOX if box_profile(v, check)] + [v for v in HAND_MESH if hand_mesh(v, check)]
    print(('would change: ' if check else 'changed: ') + (', '.join(changed) or 'nothing'))
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
