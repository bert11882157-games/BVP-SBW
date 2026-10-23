"""Game custom_geo (poly_mesh bones) -> OBJ, one object per bone, blocks (px / 16).

python geo_to_obj.py GEO.json OUT.obj [TEXTURE.png] [--skip REGEX] [--only REGEX]
OBJ axes: x = -geo x (vehicle right), y = geo y (up), z = geo z (nose -z): Blender's default OBJ import then shows
the nose at +Y, the right wing at +X, up +Z. With a texture, writes OUT.mtl and copies nothing (absolute path).
Bones are posed at rest (bone rotations ignored: the generated models carry none on poly_mesh bones).
"""
import json
import os
import re
import sys

args = [a for a in sys.argv[1:] if not a.startswith('--')]
skip = re.compile(sys.argv[sys.argv.index('--skip') + 1]) if '--skip' in sys.argv else None
only = re.compile(sys.argv[sys.argv.index('--only') + 1]) if '--only' in sys.argv else None
if skip:
    args.remove(skip.pattern)
if only:
    args.remove(only.pattern)
geo = json.load(open(args[0], encoding='utf-8'))['minecraft:geometry'][0]
out = args[1]
tex = args[2] if len(args) > 2 else None
lines = []
if tex:
    mtl = os.path.splitext(out)[0] + '.mtl'
    open(mtl, 'w').write(f'newmtl skin\nKd 1 1 1\nmap_Kd {os.path.abspath(tex)}\nmap_d {os.path.abspath(tex)}\n')
    lines.append(f'mtllib {os.path.basename(mtl)}')
vbase = tbase = 0
nb = 0
for bone in geo['bones']:
    pm = bone.get('poly_mesh')
    if not pm or not pm.get('polys'):
        continue
    if skip and skip.search(bone['name']) or only and not only.search(bone['name']):
        continue
    nb += 1
    lines.append(f"o {bone['name']}")
    if tex:
        lines.append('usemtl skin')
    for p in pm['positions']:
        lines.append(f'v {-p[0] / 16:.5f} {p[1] / 16:.5f} {p[2] / 16:.5f}')
    uvs = pm.get('uvs') or []
    for u in uvs:
        lines.append(f'vt {u[0]:.6f} {u[1]:.6f}')
    for poly in pm['polys']:
        if tex and uvs:
            lines.append('f ' + ' '.join(f'{vbase + v[0] + 1}/{tbase + v[2] + 1}' for v in poly))
        else:
            lines.append('f ' + ' '.join(str(vbase + v[0] + 1) for v in poly))
    vbase += len(pm['positions'])
    tbase += len(uvs)
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('bones', nb, 'verts', vbase)
