"""Ground counterpart of tools/cockpit_models/nozzle_check.py: are a ground vehicle's exhaust origins on its own grille?

    python exhaust_check.py ID [ID...]            report, exit 1 on any FAIL
    python exhaust_check.py ID --fix              move the EngineExhaust origins onto the grilles (client json only)
    python exhaust_check.py ID --sheet OUT.jpg    rear three-quarter render with the origins marked (needs Blender)

Frames (read from the renderers, not guessed):
  EngineExhaust.Origins   BvpEngineExhaustRenderer negates Z before the vehicle matrix, so the position is in the MODEL
                          frame: final model px / 16, +Z = REAR (hull about -3.96 .. +4.81 on the Abrams).
  Attachments (muzzles)   the matrix frame: data = geo_to_data(px) = (x, y, -z) / 16, nose = +Z, relative to the
                          parent frame's rest origin (TurretPos, BarrelPos, ...).
  Nothing else in the ground client json is positioned in the model frame: no smoke dischargers, muzzle flash points or
  sound emitters exist as separate positions (flash and fire sound use the muzzle attachments checked here).

Grilles: EXITS[id] (hand-measured, model px, box = x0, x1, y0, y1, z of the outer louvre face) wins; otherwise the
heuristic looks in the hull mesh's rear fifth for thin plates (<= 1.5 px deep, >= 6 x 4 px) whose face texture is
banded (std of the row means >= BAND), which is what louvres look like in these models.

FAIL: an origin more than MAX_M from the nearest grille box, or on the front half of the hull. A muzzle attachment is
FAILed when it is more than MAX_M from the tip of its barrel bone.
"""
import json
import os
import re
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack')
DATA = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack')
MAX_M = 0.2
# front-engined vehicles: the hull-front-half rule does not apply (their exhaust may sit forward, but still on a grille)
FRONT_ENGINE = {'m1128', 'puma_ifv'}
BAND = 6.0
OUT_PX = 0.25            # --fix: origin this far outside the louvre face

# hand-measured louvre boxes, model px: (x0, x1, y0, y1, z of the outer face; +Z = rear)
EXITS = {
    'm1a1_abrams': [(-17.629, -8.351, 20.539, 27.962, 76.547), (4.639, 17.629, 20.539, 27.962, 76.547)],
    'm1a2_abrams_sep_v2': [(-17.629, -8.351, 20.771, 28.193, 76.547), (4.639, 17.629, 20.771, 28.193, 76.547)],
    'm1a1_tusk': [(-17.632, -8.352, 20.524, 27.948, 76.560), (4.640, 17.632, 20.524, 27.948, 76.560)],
    # banded rear panel (texture std 56) 3.5 px inside the rear overhang, so the box spans z 33.8 .. 37.3 (hull end)
    'fv721_fox': [(-10.6, 10.6, 15.4, 22.9, 33.8, 37.3)],
    # the model's louvred grille on the rear plate (part 0 elements 156-159, 206-215): slats x -8.96 .. 12.37 px,
    # y 24.2 .. 28.4 px, outer face z 59.03 px
    't64bv': [(-8.96, 12.37, 24.2, 28.4, 59.03)],
    # Stryker: C7 engine front right, outlet on the right (-x) hull side forward. The model has no visible outlet there: this is the
    # right side face (x -28.5) at engine-deck height abreast the roof louvre panel (z -43.7). UNVERIFIED, a decision, not a measurement
    'm1128': [(-28.5, -28.5, 28.0, 36.0, -47.5, -39.9)],
    # Puma: MTU 10V 890 front right, outlet on the right (-x) hull side face forward (x -34.438, the side module face), at
    # the engine deck height. The model has no visible outlet: UNVERIFIED, a decision. Set in puma_build.py (EXHAUST)
    'puma_ifv': [(-34.438, -34.438, 18.1, 25.7, -42.75, -33.25)],
}


def load(path):
    with open(path, encoding='utf-8') as fh:
        return json.load(fh)


def paths(vid):
    return {'geo': os.path.join(ASSETS, 'custom_geo', f'{vid}.geo.json'),
            'client': os.path.join(ASSETS, 'sbw', 'vehicles', f'{vid}.json'),
            'data': os.path.join(DATA, 'sbw', 'vehicles', f'{vid}.json'),
            'tex': os.path.join(ASSETS, 'textures', 'entity', f'{vid}.png')}


def bone_mesh(geo, name):
    for b in geo['bones']:
        if b['name'] == name and b.get('poly_mesh'):
            return b['poly_mesh']
    return None


def hull_name(geo):
    best, n = None, 0
    for b in geo['bones']:
        pm = b.get('poly_mesh')
        if pm and not re.match(r'(?i)(wheel|track|crude|broken)', b['name']) and len(pm['positions']) > n:
            best, n = b['name'], len(pm['positions'])
    return 'hull' if bone_mesh(geo, 'hull') else best


def components(pm):
    P = np.array(pm['positions'])
    polys = pm['polys']
    par = list(range(len(P)))

    def find(i):
        while par[i] != i:
            par[i] = par[par[i]]
            i = par[i]
        return i
    for p in polys:
        for t in p[1:]:
            par[find(t[0])] = find(p[0][0])
    comp = {}
    for pi, p in enumerate(polys):
        comp.setdefault(find(p[0][0]), []).append(pi)
    return P, comp


def heuristic_grilles(geo, tex_path):
    """Rear-plate louvre faces: z-facing quads of the hull mesh near its rear end, 6..24 px, whose texture patch is banded.\n    Only a hint: front-engined or roof-vented vehicles need an EXITS entry."""
    from PIL import Image
    pm = bone_mesh(geo, hull_name(geo))
    if not os.path.exists(tex_path):
        return []
    P = np.array(pm['positions'])
    tex = np.asarray(Image.open(tex_path).convert('L'), float)
    desc = geo.get('description', {})
    tw, th = desc.get('texture_width', 1), desc.get('texture_height', 1)
    uvs = np.array(pm['uvs'])
    norm = pm.get('normalized_uvs', False)
    found = []
    zmax = P[:, 2].max()
    for poly in pm['polys']:
        if len(poly) != 4:
            continue
        Q = P[[t[0] for t in poly]]
        lo, hi = Q.min(0), Q.max(0)
        ext = hi - lo
        flat = [a for a in range(3) if ext[a] < 0.05]
        # rear plate only: z-facing, within 4 px of the hull's rear end, no larger than a louvre panel (24 px)
        if flat != [2] or lo[2] < zmax - 4 or max(ext) > 24:
            continue
        o1, o2 = [i for i in range(3) if i != flat[0]]
        if min(ext[o1], ext[o2]) < 4 or max(ext[o1], ext[o2]) < 6:
            continue
        uv = uvs[[t[2] for t in poly]]
        if norm:                                       # normalized uvs have v up
            uv = np.column_stack([uv[:, 0], 1 - uv[:, 1]]) * [tw, th]
        u0, v0 = uv.min(0).astype(int)
        u1, v1 = np.ceil(uv.max(0)).astype(int)
        patch = tex[max(v0, 0):v1, max(u0, 0):u1]
        if patch.shape[0] >= 4 and patch.shape[1] >= 2 and patch.mean(1).std() >= BAND:
            found.append((float(lo[0]), float(hi[0]), float(lo[1]), float(hi[1]), float(lo[2]), float(hi[2])))
    return sorted(set(found))


def box(b):
    return tuple(b) if len(b) == 6 else (b[0], b[1], b[2], b[3], b[4], b[4])     # EXITS: z of the outer face


def box_dist_m(p_px, b):
    x0, x1, y0, y1, z0, z1 = box(b)
    d = np.array([max(x0 - p_px[0], 0, p_px[0] - x1), max(y0 - p_px[1], 0, p_px[1] - y1),
                  max(z0 - p_px[2], 0, p_px[2] - z1)])
    return float(np.linalg.norm(d)) / 16


def frame_origin(d, frame):
    z = np.zeros(3)
    tur = np.array(d.get('TurretPos', z), float)
    if frame in ('Vehicle', 'VehicleCustomPitch'):
        return z
    if frame == 'Turret':
        return tur
    if frame == 'Barrel':
        return tur + np.array(d.get('BarrelPos', z), float)
    ws = tur + np.array(d.get('PassengerWeaponStationPos', z), float)
    if frame == 'WeaponStation':
        return ws
    if frame == 'WeaponStationBarrel':
        return ws + np.array(d.get('PassengerWeaponStationBarrelPos', z), float)
    return None


def barrel_tip_px(geo):
    for name in ('barell', 'barrel', 'Barrel', 'gun'):
        pm = bone_mesh(geo, name)
        if pm:
            P = np.array(pm['positions'])
            tip = P[P[:, 2] <= P[:, 2].min() + 1.0]
            return tip.mean(0)
    return None


def check(vid, fix=False):
    f = paths(vid)
    if not all(os.path.exists(f[k]) for k in ('geo', 'client', 'data')):
        print(f'{vid}\n  not built in this tree (no geo / client / data json): skipped')
        return 0
    geo = load(f['geo'])['minecraft:geometry'][0]
    client, data = load(f['client']), load(f['data'])
    grilles = EXITS.get(vid)
    src = 'EXITS'
    if not grilles:
        grilles, src = heuristic_grilles(geo, f['tex']), 'heuristic'
    hn = hull_name(geo)
    P = np.array(bone_mesh(geo, hn)['positions'])
    mid = (P[:, 2].min() + P[:, 2].max()) / 2
    rows, bad = [], 0
    origins = (client.get('EngineExhaust') or {}).get('Origins', [])
    if not origins:
        rows.append(('EngineExhaust', '-', '-', 'no origins'))
    for o in origins:
        px = np.array(o['Position'], float) * 16
        front = px[2] < mid and vid not in FRONT_ENGINE
        if grilles:
            dist = min(box_dist_m(px, b) for b in grilles)
            near = f'{dist:.2f} m from the nearest of {len(grilles)} grille(s) [{src}]'
        else:
            dist, near = None, 'no grille found (add an EXITS entry)'
        fail = front or dist is None or dist > MAX_M
        bad += fail
        rows.append((o['Id'], [round(v, 4) for v in o['Position']], 'FAIL' if fail else 'ok',
                     near + (', ON THE FRONT HALF' if front else '')))
    tip = barrel_tip_px(geo)
    for name, a in (data.get('Attachments') or {}).items():
        if not re.search(r'(?i)^main.?muzzle$', name) or a.get('Parent') != 'Barrel':
            continue
        fo = frame_origin(data, a['Parent'])
        if tip is None or fo is None:
            rows.append((name, '-', '-', 'barrel bone or frame not found'))
            continue
        p = fo + np.array(a['Position'], float)
        p_px = np.array([p[0], p[1], -p[2]]) * 16
        dist = float(np.linalg.norm(p_px - tip)) / 16
        fail = dist > MAX_M
        bad += fail
        rows.append((name, [round(v, 4) for v in a['Position']], 'FAIL' if fail else 'ok',
                     f'{dist:.2f} m from the barrel tip (model px {np.round(tip, 1).tolist()})'))
    print(vid)
    for r in rows:
        print(f'  {r[0]:<34} {str(r[1]):<30} {r[2]:<5} {r[3]}')
    if fix:
        if not grilles:
            print('  --fix: no grille known for this id, nothing changed')
        else:
            c = load(f['client'])
            c['EngineExhaust']['Origins'] = [
                {'Id': f'engine_exhaust_group_origin_{i:03d}',
                 'Position': [round(v / 16, 5) for v in outside(b)]}
                for i, b in enumerate(sorted(grilles))]
            with open(f['client'], 'w', encoding='utf-8', newline='\n') as fh:
                fh.write(json.dumps(c, indent=2) + '\n')
            print('  --fix: client json written; put the same points in the generating script')
    return bad


def sheet(ids, out):
    """Rear three-quarter renders with the origins marked, through the tools/vehgen OBJ and Blender helpers."""
    import tempfile
    from PIL import Image
    blender = r'C:\Program Files\Blender Foundation\Blender 5.2\blender.exe'
    tmp = tempfile.mkdtemp()
    tiles = []
    for vid in ids:
        f = paths(vid)
        if not all(os.path.exists(f[k]) for k in ('geo', 'client', 'tex')):
            continue
        obj = os.path.join(tmp, vid + '.obj')
        subprocess.run([sys.executable, os.path.join(HERE, 'geo_to_obj.py'), f['geo'], obj, f['tex'], '--skip',
                        '^(Wheel|wheel|Track|track|crudeTrack|brokenTrack)'], check=True, capture_output=True)
        marks = [[*o['Position'], [0, 0.8, 0.1]] for o in load(f['client']).get('EngineExhaust', {}).get('Origins', [])]
        png = os.path.join(tmp, vid + '.png')
        script = os.path.join(tmp, 'mark_view.py')
        with open(script, 'w') as fh:
            fh.write(MARK_VIEW)
        H = np.array(bone_mesh(load(f['geo'])['minecraft:geometry'][0], hull_name(load(f['geo'])['minecraft:geometry'][0]))
                     ['positions'])
        cy, cz = -(H[:, 2].min() + H[:, 2].max()) / 32, (H[:, 1].min() + H[:, 1].max()) / 32
        scale = (H[:, 2].max() - H[:, 2].min()) / 16 * 1.05 + 2.0       # the whole hull in the tile
        az = '140' if '--front' not in sys.argv else '40'
        subprocess.run([blender, '-b', '--factory-startup', '-P', script, '--', obj, png, az, '30', f'{scale:.2f}', '0',
                        f'{cy:.2f}', f'{cz:.2f}', '800', json.dumps(marks)], check=True, capture_output=True)
        tiles.append(Image.open(png).convert('RGB').resize((550, 412)))
    sh = Image.new('RGB', (550 * min(2, len(tiles)), 412 * ((len(tiles) + 1) // 2)))
    for i, t in enumerate(tiles):
        sh.paste(t, ((i % 2) * 550, (i // 2) * 412))
    sh.save(out, quality=88)


MARK_VIEW = '''import math, sys, json
import bpy
from mathutils import Vector, Matrix
a = sys.argv[sys.argv.index('--') + 1:]
obj, out = a[0], a[1]
az, el, sc = map(float, a[2:5]); c = Vector(map(float, a[5:8]))
size = int(a[8]); marks = json.loads(a[9])
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.wm.obj_import(filepath=obj)
for dx, dy, dz, col in marks:
    bpy.ops.mesh.primitive_uv_sphere_add(radius=0.11, location=(-dx, -dz, dy), segments=24, ring_count=12)
    o = bpy.context.active_object
    m = bpy.data.materials.new('mk'); m.diffuse_color = (*col, 1.0)
    o.data.materials.append(m); o.show_in_front = True
s = bpy.context.scene
s.render.engine = 'BLENDER_WORKBENCH'
s.display.shading.light = 'STUDIO'; s.display.shading.color_type = 'TEXTURE'
s.render.resolution_x = size; s.render.resolution_y = int(size * 0.75)
w = bpy.data.worlds.new('w'); s.world = w; w.color = (0.82, 0.84, 0.87)
s.view_settings.view_transform = 'Standard'
cd = bpy.data.cameras.new('c'); cam = bpy.data.objects.new('c', cd); s.collection.objects.link(cam); s.camera = cam
cd.type = 'ORTHO'; cd.ortho_scale = sc; cd.clip_end = 1000; cd.clip_start = 0.01
d = Vector((math.sin(math.radians(az)) * math.cos(math.radians(el)), math.cos(math.radians(az)) * math.cos(math.radians(el)), math.sin(math.radians(el))))
cam.location = c + d * 60
f = -d; r = f.cross(Vector((0, 0, 1))).normalized(); u = r.cross(f)
cam.rotation_euler = Matrix((r, u, -f)).transposed().to_euler()
s.render.filepath = out
bpy.ops.render.render(write_still=True)
'''


def main(argv):
    ids = [a for a in argv if not a.startswith('--') and not a.endswith('.jpg')]
    fix = '--fix' in argv
    if not ids or (fix and len(ids) != 1):
        print(__doc__)
        return 2
    bad = sum(check(v, fix) for v in ids)
    if '--sheet' in argv:
        sheet(ids, argv[argv.index('--sheet') + 1])
    print(f'{bad} failures')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))