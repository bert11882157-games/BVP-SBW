#!/usr/bin/env python3
"""Picture sheet of an Elite Expansion 2 ground vehicle: Blender views of the committed model plus crew-eye renders.

    python3 tools/vehgen/ee2_sheet.py VID OUT.jpg "TITLE" [label:x,y,z,yaw,pitch[,fov] ...]

Eyes are data-frame blocks (x left, y up, z forward; yaw positive to the left, pitch positive up), as eyeview.py.
"""
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ee2_ground as E  # noqa: E402


def main(argv):
    vid, out, title = argv[0], argv[1], argv[2]
    tmp = tempfile.mkdtemp(prefix=f'{vid}_sheet_')
    views = E.render_views(vid, tmp, 520)
    tiles = [(n.replace('_', ' '), views[n]) for n in ('three_quarter', 'left', 'right', 'front', 'rear', 'top')]
    for k, spec in enumerate(argv[3:]):
        label, nums = spec.split(':', 1)
        v = [float(x) for x in nums.split(',')]
        fov = v[5] if len(v) > 5 else 75.0
        png = os.path.join(tmp, f'eye_{k}.png')
        E.eye_render(vid, png, v[:3], yaw=v[3], pitch=v[4], fov=fov)
        tiles.append((label, png))
    E.sheet(tiles, out, title, width=1100, cols=3)
    for f in os.listdir(tmp):
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    print(out)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
