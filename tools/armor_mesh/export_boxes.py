#!/usr/bin/env python3
"""Export BVP box armor profiles as Blockbench armor-mesh templates.

For every armor profile that has volumes, writes
``tools/armor_mesh/templates/<id>.armor.geo.json``: a Bedrock geometry file (``poly_mesh``
bones, the format the Blockbench "Meshy" plugin reads and writes) in the same geo units and
frame as the vehicle's visual model ``custom_geo/<id>.geo.json``. Each box becomes one bone
with a closed 6-quad mesh, named ``<kind>__<param>__<name>`` and parented to
``armor_hull``, ``armor_turret`` or ``armor_barrel``. See ``docs/ARMOR_MESH.md``.

Templates are a starting point for authoring; the game never loads them. To make the game use
one, finish it in Blockbench and save it as
``bvp/src/main/resources/data/berts_vehicle_pack/armor_mesh/<id>.geo.json``.

Usage:
    python3 tools/armor_mesh/export_boxes.py                 # all profiles with volumes
    python3 tools/armor_mesh/export_boxes.py t72b leo2a6     # selected profiles
    python3 tools/armor_mesh/export_boxes.py --check         # verify, write nothing
"""
import argparse
import json
import math
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ARMOR_DIRS = [
    os.path.join(REPO, 'bvp/src/main/resources/data/berts_vehicle_pack/armor'),       # source overlay wins
    os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/armor'),
]
GEO_DIRS = [
    os.path.join(REPO, 'bvp/src/main/resources/assets/berts_vehicle_pack/custom_geo'),
    os.path.join(REPO, 'bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo'),
]
OUT_DIR = os.path.join(REPO, 'tools/armor_mesh/templates')

# Profiles authored in hit-local space while the rendered model is mirrored on X
# (ArmorProfiles.mirrorsProfileX): armor-local = geo / 16 instead of (-x, y, z) / 16.
MIRRORED_PROFILES = {'t72a', 't72b'}

# Box list -> (bone kind, how to build the param segment)
LISTS = [
    ('plates', 'plate'),
    ('explosive_reactive_armor', 'era'),
    ('era', 'era'),
    ('engines', 'engine'),
    ('sensitive_internals', 'internal'),
    ('ammo_racks', 'ammo'),
    ('modules', 'module'),
    ('tracks', 'track'),
]
ROOTS = {'hull': 'armor_hull', 'turret': 'armor_turret', 'barrel': 'armor_barrel'}
VISUAL_FRAME_BONES = {'hull': ('hull',), 'turret': ('turret',), 'barrel': ('barell', 'barrel')}
BLOCKBENCH_NAME = re.compile(r'^[\w.-]+$')
# Box corner i: bit 0 -> +x, bit 1 -> +y, bit 2 -> +z (same order as ArmorBoxVolume)
FACES = [(0, 2, 6, 4), (1, 3, 7, 5), (0, 1, 5, 4), (2, 3, 7, 6), (0, 1, 3, 2), (4, 5, 7, 6)]
FACE_AXES = [(0, -1), (0, 1), (1, -1), (1, 1), (2, -1), (2, 1)]


def rotate_x(v, degrees):
    r = math.radians(degrees)
    c, s = math.cos(r), math.sin(r)
    return (v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c)


def rotate_y(v, degrees):
    r = math.radians(degrees)
    c, s = math.cos(r), math.sin(r)
    return (v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c)


def rotate_z(v, degrees):
    r = math.radians(degrees)
    c, s = math.cos(r), math.sin(r)
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2])


def rotate(v, rotation):
    """ArmorBox rotation: X, then Y, then Z (degrees)."""
    return rotate_z(rotate_y(rotate_x(v, rotation[0]), rotation[1]), rotation[2])


def num(value):
    """Short, exact-enough JSON number (1e-9 geo units)."""
    value = round(float(value), 9)
    if value == 0:
        return 0
    if value == int(value) and abs(value) < 1e15:
        return int(value)
    return value


def text(value):
    value = num(value)
    return str(value)


def armor_to_geo(point, mirrored):
    sign = 1.0 if mirrored else -1.0
    return (sign * point[0] * 16.0, point[1] * 16.0, point[2] * 16.0)


def box_corners(box):
    center, half = box['center'], box['half_size']
    rotation = box.get('rotation') or [0.0, 0.0, 0.0]
    corners = []
    for i in range(8):
        local = (half[0] if i & 1 else -half[0], half[1] if i & 2 else -half[1], half[2] if i & 4 else -half[2])
        r = rotate(local, rotation)
        corners.append((r[0] + center[0], r[1] + center[1], r[2] + center[2]))
    return corners


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def unit(v):
    length = math.sqrt(dot(v, v))
    return (v[0] / length, v[1] / length, v[2] / length) if length > 0 else (0.0, 1.0, 0.0)


def bone_name(kind, box):
    name = box.get('name') or ('armor' if kind == 'plate' else 'internal')
    if kind == 'plate':
        param = text(box.get('armor_mm', 0.0)) + 'mm'
    elif kind == 'era':
        era_type = box.get('type', 'kontakt1')
        param = '%s_ke%s_ce%s' % (era_type, text(box.get('kinetic_protection_mm', 25.0)),
                                   text(box.get('chemical_protection_mm', 400.0)))
    elif kind == 'ammo':
        param = (text(box['health']) + 'hp') if 'health' in box else ''
    elif kind == 'module':
        param = box.get('module', '') + ('' if box.get('unified', True) else '-split')
    else:
        param = ''
    return '%s__%s__%s' % (kind, param, name)


def visual_pivots(profile_id):
    for directory in GEO_DIRS:
        path = os.path.join(directory, profile_id + '.geo.json')
        if not os.path.exists(path):
            continue
        with open(path) as handle:
            geo = json.load(handle)['minecraft:geometry'][0]
        by_name = {bone['name'].lower(): bone for bone in geo.get('bones', [])}
        pivots = {}
        for frame, names in VISUAL_FRAME_BONES.items():
            for name in names:
                if name in by_name:
                    pivots[frame] = [num(v) for v in by_name[name].get('pivot', [0, 0, 0])]
                    break
        return pivots, os.path.relpath(path, REPO)
    return {}, None


def read_profile(profile_id):
    for directory in ARMOR_DIRS:
        path = os.path.join(directory, profile_id + '.json')
        if os.path.exists(path):
            with open(path) as handle:
                return json.load(handle)
    raise FileNotFoundError(profile_id)


def profile_ids():
    ids = set()
    for directory in ARMOR_DIRS:
        if os.path.isdir(directory):
            ids.update(name[:-5] for name in os.listdir(directory) if name.endswith('.json'))
    return sorted(ids)


def volumes_of(profile):
    seen_era = False
    for key, kind in LISTS:
        if key == 'era' and seen_era:
            continue  # "explosive_reactive_armor" wins over "era", like ArmorProfiles
        boxes = profile.get(key) or []
        if key == 'explosive_reactive_armor' and 'explosive_reactive_armor' in profile:
            seen_era = True
        for box in boxes:
            half = box.get('half_size') or [0, 0, 0]
            if min(half) <= 0:
                continue
            yield kind, box


def build_template(profile_id, profile):
    mirrored = profile_id in MIRRORED_PROFILES
    pivots, visual_path = visual_pivots(profile_id)
    bones = [
        {'name': ROOTS['hull'], 'pivot': pivots.get('hull', [0, 0, 0])},
        {'name': ROOTS['turret'], 'parent': ROOTS['hull'], 'pivot': pivots.get('turret', [0, 0, 0])},
        {'name': ROOTS['barrel'], 'parent': ROOTS['turret'], 'pivot': pivots.get('barrel', [0, 0, 0])},
    ]
    names = set()
    geo_min = [math.inf] * 3
    geo_max = [-math.inf] * 3
    for kind, box in volumes_of(profile):
        name = bone_name(kind, box)
        if not BLOCKBENCH_NAME.match(name):
            raise ValueError('%s: bone name %r is not valid in Blockbench' % (profile_id, name))
        if name.lower() in names:
            raise ValueError('%s: duplicate bone name %r' % (profile_id, name))
        names.add(name.lower())
        frame = (box.get('frame') or 'hull').strip().lower()
        frame = frame if frame in ROOTS else 'hull'
        corners = [armor_to_geo(c, mirrored) for c in box_corners(box)]
        center = armor_to_geo(box['center'], mirrored)
        normals = []
        polys = []
        for face_index, face in enumerate(FACES):
            quad = list(face)
            face_center = tuple(sum(corners[i][axis] for i in quad) / 4.0 for axis in range(3))
            outward = unit(sub(face_center, center))
            n = cross(sub(corners[quad[1]], corners[quad[0]]), sub(corners[quad[2]], corners[quad[0]]))
            if dot(n, outward) < 0:
                quad[1], quad[3] = quad[3], quad[1]
            normals.append([num(v) for v in unit(cross(sub(corners[quad[1]], corners[quad[0]]),
                                                      sub(corners[quad[2]], corners[quad[0]])))])
            polys.append([[quad[k], face_index, k] for k in range(4)])
        for corner in corners:
            for axis in range(3):
                geo_min[axis] = min(geo_min[axis], corner[axis])
                geo_max[axis] = max(geo_max[axis], corner[axis])
        bones.append({
            'name': name,
            'parent': ROOTS[frame],
            'pivot': [num(v) for v in center],
            'poly_mesh': {
                'normalized_uvs': True,
                'positions': [[num(v) for v in corner] for corner in corners],
                'normals': normals,
                'uvs': [[0, 0], [1, 0], [1, 1], [0, 1]],
                'polys': polys,
            },
        })
    if len(bones) == 3:
        return None, 0
    width = max(geo_max[0] - geo_min[0], geo_max[2] - geo_min[2]) / 16.0
    height = (geo_max[1] - geo_min[1]) / 16.0
    description = {
        'identifier': 'geometry.%s_armor' % profile_id,
        'texture_width': 16,
        'texture_height': 16,
        'visible_bounds_width': num(math.ceil(width + 2)),
        'visible_bounds_height': num(math.ceil(height + 2)),
        'visible_bounds_offset': [0, num(round((geo_min[1] + geo_max[1]) / 32.0, 2)), 0],
    }
    header = {
        'format_version': '1.12.0',
        'bvp_armor_mesh': {
            'profile': profile_id,
            'generated_by': 'tools/armor_mesh/export_boxes.py',
            'x_mirrored_profile': mirrored,
            'visual_model': visual_path,
            'note': 'Template only: the game loads armor meshes from '
                    'bvp/src/main/resources/data/berts_vehicle_pack/armor_mesh/<id>.geo.json',
        },
    }
    return render(header, description, bones), len(bones) - 3


def render(header, description, bones):
    """Readable but compact: one line per bone."""
    lines = ['{']
    for key, value in header.items():
        lines.append('  %s: %s,' % (json.dumps(key), json.dumps(value)))
    lines.append('  "minecraft:geometry": [{')
    lines.append('    "description": %s,' % json.dumps(description))
    lines.append('    "bones": [')
    for index, bone in enumerate(bones):
        suffix = ',' if index + 1 < len(bones) else ''
        lines.append('      %s%s' % (json.dumps(bone, separators=(',', ':')), suffix))
    lines.append('    ]')
    lines.append('  }]')
    lines.append('}')
    return '\n'.join(lines) + '\n'


def verify(profile_id, profile, content):
    """Reload the written template and compare every corner with the source box (armor blocks)."""
    mirrored = profile_id in MIRRORED_PROFILES
    geo = json.loads(content)['minecraft:geometry'][0]
    by_name = {bone['name']: bone for bone in geo['bones']}
    worst = 0.0
    count = 0
    for kind, box in volumes_of(profile):
        bone = by_name[bone_name(kind, box)]
        positions = bone['poly_mesh']['positions']
        sign = 1.0 if mirrored else -1.0
        for source, written in zip(box_corners(box), positions):
            back = (sign * written[0] / 16.0, written[1] / 16.0, written[2] / 16.0)
            worst = max(worst, max(abs(a - b) for a, b in zip(source, back)))
        count += 1
    return count, worst


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('profiles', nargs='*', help='profile ids (default: every profile with volumes)')
    parser.add_argument('--out', default=OUT_DIR, help='output directory')
    parser.add_argument('--check', action='store_true', help='verify only, write nothing')
    args = parser.parse_args(argv)
    ids = args.profiles or profile_ids()
    os.makedirs(args.out, exist_ok=True)
    written = 0
    total_volumes = 0
    for profile_id in ids:
        profile = read_profile(profile_id)
        content, volumes = build_template(profile_id, profile)
        if content is None:
            continue
        count, worst = verify(profile_id, profile, content)
        if worst > 1e-8:
            raise SystemExit('%s: template corners differ from the boxes by %.3g blocks' % (profile_id, worst))
        path = os.path.join(args.out, profile_id + '.armor.geo.json')
        if not args.check:
            with open(path, 'w', newline='\n') as handle:
                handle.write(content)
        written += 1
        total_volumes += volumes
        print('%-22s %4d volumes  max corner error %.1e blocks%s' % (
            profile_id, volumes, worst, '' if args.check else '  -> ' + os.path.relpath(path, REPO)))
    print('%d templates, %d volumes%s' % (written, total_volumes, ' (check only)' if args.check else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
