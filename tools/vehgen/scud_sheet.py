#!/usr/bin/env python3
"""Picture sheet of the Scud-B: travel views, the erector at 0 / 45 / 88 deg (the barrel and the missile bones turned about the
hinge in a temporary copy of the committed geo), the three crew eyes.

    python3 tools/vehgen/scud_sheet.py OUT.jpg
"""
import copy
import json
import math
import os
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ee2_ground as E  # noqa: E402
import vehgen  # noqa: E402

VID = 'scud_b'


def raised(geo, deg):
    """The geo with bones barell and ammo_Rocket_1 pitched `deg` nose-up about the barell pivot (geo: up +Y, nose -Z)."""
    g = copy.deepcopy(geo)
    bones = {b['name']: b for b in g['minecraft:geometry'][0]['bones']}
    piv = np.array(bones['barell']['pivot'])
    a = math.radians(deg)
    for name in ('barell', 'ammo_Rocket_1'):
        pm = bones[name]['poly_mesh']
        out = []
        for p in pm['positions']:
            dy, u = p[1] - piv[1], -(p[2] - piv[2])
            y2, u2 = dy * math.cos(a) + u * math.sin(a), u * math.cos(a) - dy * math.sin(a)
            out.append([p[0], round(piv[1] + y2, 5), round(piv[2] - u2, 5)])
        pm['positions'] = out
        pm['normals'] = [[n[0], n[1] * math.cos(a) + (-n[2]) * math.sin(a), -((-n[2]) * math.cos(a) - n[1] * math.sin(a))]
                         for n in pm['normals']]
    return g


def views_of(geo, tmp, tag, size=520):
    path = os.path.join(tmp, tag + '.geo.json')
    with open(path, 'w') as fh:
        json.dump(geo, fh)
    obj = os.path.join(tmp, tag + '.obj')
    subprocess.run([sys.executable, os.path.join(HERE, 'geo_to_obj.py'), path, obj,
                    os.path.join(vehgen.ASSETS, 'textures', 'entity', VID + '.png'), '--skip', '^(brokenTrack|crudeTrack)'],
                   check=True, stdout=subprocess.DEVNULL)
    out = os.path.join(tmp, tag)
    subprocess.run([E.BLENDER, '--background', '--factory-startup', '--python',
                    os.path.join(E.EE2, 'tools', 'render_views.py'), '--', obj, out, str(size)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def main(argv):
    out = argv[0]
    tmp = tempfile.mkdtemp(prefix='scud_sheet_')
    geo = E.load(os.path.join(vehgen.ASSETS, 'custom_geo', VID + '.geo.json'))
    base = views_of(geo, tmp, 'travel')
    tiles = [('three quarter', f'{base}/three_quarter.png'), ('left', f'{base}/left.png'), ('top', f'{base}/top.png'),
             ('front', f'{base}/front.png'), ('rear', f'{base}/rear.png')]
    for deg in (45, 88):
        d = views_of(raised(geo, deg), tmp, f'up{deg}')
        tiles.append((f'erector {deg} deg three quarter', f'{d}/three_quarter.png'))
        tiles.append((f'erector {deg} deg left', f'{d}/left.png'))
    eyes = [('driver (left cab)', (1.287, 2.552, 5.28), 0, -4, 75), ('commander (right cab)', (-1.287, 2.552, 5.28), 0, -4, 75),
            ('operator periscope', (0.0, 3.234, 4.62), 0, -3, 75)]
    for k, (label, eye, yaw, pitch, fov) in enumerate(eyes):
        png = os.path.join(tmp, f'eye_{k}.png')
        E.eye_render(VID, png, eye, yaw=yaw, pitch=pitch, fov=fov)
        tiles.append((label, png))
    E.sheet(tiles, out, 'Scud-B (scud_b): travel, erector 45 and 88 deg, crew eyes', width=1100, cols=3)
    for root, _, files in os.walk(tmp, topdown=False):
        for f in files:
            os.remove(os.path.join(root, f))
        os.rmdir(root)
    print(out)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
