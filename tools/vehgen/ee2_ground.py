#!/usr/bin/env python3
"""Shared helpers of the Elite Expansion 2 launcher and wheeled-vehicle builds (tos1a_build.py, himars_build.py,
scud_build.py, tigr_build.py). Everything here is what strela_build.py / m113a3_build.py / t64bv_build.py do inline:

  * the supplied .mtb read with FlexTrapezoid elements as their stored shapebox corners (the rule of
    tools/mtb_export/export_smp_mtb_obj.mjs) and Shape elements extruded to -z (strela_build.shape_polys);
  * the toolbox -> final model px frame, meshes, boxes, simple solids;
  * textures (the model's own PNG, optionally with a template's rows below it) and the x0.178 wreck texture;
  * hand-anchored registry insertions (ModEntities / ModEntityRenderers after a complete statement, lang after a key,
    creative tab, mobile / land containers, vehicle_scale applied.json), all idempotent and newline-preserving;
  * a rocket weapon in the pack's current schema (the Mi-24V S-8 / old BM-21 layout) and its projectile profile;
  * the picture sheet: Blender views (work/elite_expansion_2/tools/render_views.py) plus crew-eye renders
    (tools/cockpit_models/eyeview.py).
"""
import copy
import io
import json
import math
import os
import re
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402

mtb = mtbgeo.mtb
REPO = vehgen.REPO
NS = vehgen.NS
SOURCES = os.path.abspath(os.path.join(REPO, '..', 'source', 'mtb', 'elite_expansion'))
BLENDER = r'C:\Program Files\Blender Foundation\Blender 5.2\blender.exe'
EE2 = os.path.abspath(os.path.join(REPO, '..'))
NATIVE_RATE = 1.15            # VehicleEntity.turretTurnXSpeed / YSpeed multiply the data by 1.15


def shape_polys(f, tw, th):
    """strela_build.shape_polys: the Shape outline mirrored in x about the offset, its front plane at the NEGATED z offset,
    extruded by the depth toward -z (the rule of tools/mtb_export/export_smp_mtb_obj.mjs shapeFaces)."""
    ox, oy, oz = mtb.num(f[15]), mtb.num(f[16]), mtb.num(f[17])
    depth = mtb.num(f[11])
    sw, sh = mtb.uv_dim(mtb.num(f[9])), mtb.uv_dim(mtb.num(f[10]))
    tx, ty = mtb.num(f[18]), mtb.num(f[19])
    pts = mtb.shape_outline(f)
    n = len(pts)
    front = [mtb.place([ox - x, oy + y, -oz], f) for x, y in pts]
    back = [mtb.place([ox - x, oy + y, -oz - depth], f) for x, y in pts]
    uf = [(tx + x, ty + y) for x, y in pts]
    ub = [(tx + sw * 2 - x, ty + y) for x, y in pts]
    polys = [(front[::-1], uf[::-1]), (back, ub)]
    lengths = [math.dist(pts[i], pts[(i + 1) % n]) for i in range(n)]
    total = sum(lengths) or 1.0
    pos = 0.0
    v0, v1 = ty + sh, ty + sh + mtb.uv_dim(depth)
    for i in range(n):
        j = (i + 1) % n
        u0 = tx + pos
        pos += lengths[i]
        u1 = tx + pos
        polys.append(([front[i], front[j], back[j], back[i]], [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]))
    return polys


mtb.shape_polys = shape_polys


def load(path):
    with open(path, encoding='utf-8') as fh:
        return json.load(fh)


def dump(path, data, compact=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        if compact:
            json.dump(data, fh, separators=(',', ':'))
        else:
            fh.write(json.dumps(data, indent=2))
        fh.write('\n')


def r5(v):
    return [round(float(c), 5) for c in v]


class Model:
    """A supplied .mtb in the final model px frame: geo = [z*s, -y*s + yOff, -x*s + zShift]."""

    def __init__(self, source, scale, ground_y, wheel_bottom=0.0, z_shift=0.0, tex_h=None):
        self.src = mtbgeo.Source(os.path.join(SOURCES, source))
        for f in self.src.elements:                       # FlexTrapezoid: the toolbox's stored shapebox corners
            if f[5] == 'FlexTrapezoid':
                f[5] = 'Shapebox'
        self.E = self.src.elements
        self.s = scale
        self.frame = mtbgeo.Frame(scale, ground_y * scale + wheel_bottom)
        self.z_shift = z_shift
        self.tex_w, self.tex_h = self.src.tw, tex_h or self.src.th

    def geo(self, p):
        g = self.frame.geo(p)
        g[2] += self.z_shift
        return g

    def px(self, p):
        return [round(float(v), 4) for v in self.geo(p)]

    def els(self, ids):
        return [self.E[i] for i in ids]

    def part(self, n):
        return [i for i, f in enumerate(self.E) if int(f[4]) == n]

    def mesh(self, ids, tf=None):
        """poly_mesh of elements (indices); tf(point_px) -> point_px transforms the result (e.g. a door closed)."""
        if not ids:
            return {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        m = mtbgeo.mesh(self.els(ids), self.frame, self.src.tw, self.tex_h)
        for p in m['positions']:
            p[2] = round(p[2] + self.z_shift, 5)
        if tf is not None:
            P = np.array(m['positions'])
            Q = np.array([tf(p) for p in P])
            m['positions'] = [r5(q) for q in Q]
            # normals: recompute per polygon from the moved positions (rigid moves keep the winding)
            normals = []
            for poly in m['polys']:
                q = Q[[v[0] for v in poly]]
                n = np.cross(q[1] - q[0], q[2] - q[1])
                if np.linalg.norm(n) < 1e-9 and len(q) > 3:
                    n = np.cross(q[2] - q[0], q[3] - q[2])
                n = n / (np.linalg.norm(n) or 1.0)
                normals.append(r5(n))
                for v in poly:
                    v[1] = len(normals) - 1
            m['normals'] = normals
        return m

    def corners(self, ids):
        out = []
        for i in ids:
            f = self.E[i]
            if f[5] == 'Shape':
                out += [p for pos, _ in mtb.element_polys(f, self.src.tw, self.src.th) for p in pos]
            else:
                out += mtb.box_corners(f)
        return np.array(out)

    def box(self, ids):
        c = np.array([self.geo(p) for p in self.corners(ids)])
        return c.min(0), c.max(0)

    def tb_box(self, ids):
        c = self.corners(ids)
        return c.min(0), c.max(0)

    def uv_of(self, ids):
        """A texel (normalized u, v) of the first face of the elements: for hand-built solids in the model's colours."""
        m = self.mesh(ids[:1])
        return m['uvs'][0]


def merge(*meshes):
    out = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    for m in meshes:
        if not m or not m.get('polys'):
            continue
        p0, n0, u0 = len(out['positions']), len(out['normals']), len(out['uvs'])
        out['positions'] += [list(p) for p in m['positions']]
        out['normals'] += [list(n) for n in m['normals']]
        out['uvs'] += [list(u) for u in m['uvs']]
        out['polys'] += [[[a + p0, b + n0, c + u0] for a, b, c in poly] for poly in m['polys']]
    return out


def solid(faces, uv, centre):
    """Closed solid from planar faces (lists of px points), every vertex on texel uv, outward normals."""
    m = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    for face in faces:
        q = np.array(face, float)
        n = np.cross(q[1] - q[0], q[2] - q[1])
        if np.linalg.norm(n) < 1e-9:
            continue
        n /= np.linalg.norm(n)
        if np.dot(n, q.mean(0) - centre) < 0:
            q, n = q[::-1], -n
        m['normals'].append(r5(n))
        m['uvs'].append(list(uv))
        poly = []
        for p in q:
            m['positions'].append(r5(p))
            poly.append([len(m['positions']) - 1, len(m['normals']) - 1, len(m['uvs']) - 1])
        m['polys'].append(poly)
    return m


def box_px(lo, hi, uv):
    """An axis-aligned px box as a solid."""
    lo, hi = np.array(lo, float), np.array(hi, float)
    c = {(i, j, k): np.array([hi[0] if i else lo[0], hi[1] if j else lo[1], hi[2] if k else lo[2]])
         for i in (0, 1) for j in (0, 1) for k in (0, 1)}
    quads = [[(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)], [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],
             [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)], [(1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)],
             [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)], [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)]]
    return solid([[c[v] for v in q] for q in quads], uv, (lo + hi) / 2)


def cylinder_x(cx, cy, cz, r, x0, x1, uv, n=16):
    """A cylinder along x (an axle, a wheel hub) as a solid."""
    ring = [(cy + r * math.cos(2 * math.pi * k / n), cz + r * math.sin(2 * math.pi * k / n)) for k in range(n)]
    faces = [[(x0, y, z) for y, z in ring], [(x1, y, z) for y, z in ring]]
    for k in range(n):
        a, b = ring[k], ring[(k + 1) % n]
        faces.append([(x0, a[0], a[1]), (x0, b[0], b[1]), (x1, b[0], b[1]), (x1, a[0], a[1])])
    return solid(faces, uv, np.array([(x0 + x1) / 2, cy, cz]))


def bounds_of(geo):
    pts = [p for b in geo['bones'] if b.get('poly_mesh') for p in b['poly_mesh']['positions']]
    P = np.array(pts)
    return P.min(0), P.max(0)


def set_bounds(geo):
    lo, hi = bounds_of(geo)
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]


def shift_bone(b, dx=0.0, dy=0.0, dz=0.0):
    pm = b.get('poly_mesh')
    if pm:
        pm['positions'] = [[round(p[0] + dx, 5), round(p[1] + dy, 5), round(p[2] + dz, 5)] for p in pm['positions']]
    for c in b.get('cubes', []):
        c['origin'] = [round(c['origin'][0] + dx, 5), round(c['origin'][1] + dy, 5), round(c['origin'][2] + dz, 5)]
        if 'pivot' in c:
            c['pivot'] = [round(c['pivot'][0] + dx, 5), round(c['pivot'][1] + dy, 5), round(c['pivot'][2] + dz, 5)]
    b['pivot'] = [round(b['pivot'][0] + dx, 5), round(b['pivot'][1] + dy, 5), round(b['pivot'][2] + dz, 5)]


# ---------------------------------------------------------------------------------------------------- textures
def write_textures(vid, model, tpl_vid=None, tpl_rows=None, size=None):
    """Entity texture = the model's PNG at (0, 0) on a size canvas (default the model's), plus the template texture's rows
    tpl_rows=(y0, y1) copied in place (track-link texels); the wreck texture is the pack's x0.178 darkening."""
    own = Image.open(io.BytesIO(model.src.png)).convert('RGBA')
    w, h = size or own.size
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    if tpl_vid and tpl_rows:
        tpl = Image.open(os.path.join(vehgen.ASSETS, 'textures', 'entity', f'{tpl_vid}.png')).convert('RGBA')
        img.paste(tpl.crop((0, tpl_rows[0], min(w, tpl.width), tpl_rows[1])), (0, tpl_rows[0]))
    img.paste(own, (0, 0))
    img.save(os.path.join(vehgen.ASSETS, 'textures', 'entity', f'{vid}.png'))
    a = np.asarray(img).astype(float)
    dead = a.copy()
    dead[..., :3] = np.clip(np.round(a[..., :3] * vehgen.DEAD), 0, 255)
    Image.fromarray(dead.astype(np.uint8)).save(os.path.join(vehgen.ASSETS, 'textures', 'entity_dead', f'{vid}.png'))


# ---------------------------------------------------------------------------------------------- registry edits
def _read(path):
    with open(path, encoding='utf-8', newline='') as fh:
        return fh.read()


def _write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as fh:
        fh.write(text)


def _nl(text):
    return '\r\n' if '\r\n' in text else '\n'


def java_const(vid):
    c = vid.upper()
    return re.sub(r'^9([A-Z])(\d+)', r'NINE_\1_\2', c)


def register(vid, hitbox, after_vid):
    """FittedGroundVehicleEntity registration and renderer, each inserted right after the complete statement of
    `after_vid` (anchored, never inside a statement)."""
    const, aconst = java_const(vid), java_const(after_vid)
    path = os.path.join(vehgen.JAVA, 'init', 'ModEntities.java')
    src = _read(path)
    if f'"{vid}"' not in src:
        nl = _nl(src)
        m = re.search(rf'( *)public static final RegistryObject<EntityType<\w+>> {aconst} =\r?\n.*?;\r?\n', src, re.S)
        ind = m.group(1)
        line = (f'{ind}public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> {const} ={nl}'
                f'{ind}        ENTITIES.register("{vid}", () -> vehicle({nl}'
                f'{ind}                (type, level) -> new FittedGroundVehicleEntity(type, level, "{vid}"),{nl}'
                f'{ind}                {hitbox[0]}f, {hitbox[1]}f, "{vid}"));{nl}')
        _write(path, src[:m.end()] + line + src[m.end():])
    path = os.path.join(vehgen.JAVA, 'init', 'ModEntityRenderers.java')
    src = _read(path)
    if f'"{vid}"' not in src:
        nl = _nl(src)
        m = re.search(rf'( *)registerVehicle\(event, ModEntities\.{aconst}, "{after_vid}", .*?\)\);\r?\n', src, re.S)
        ind = m.group(1)
        line = (f'{ind}registerVehicle(event, ModEntities.{const}, "{vid}", context ->{nl}'
                f'{ind}        new FittedGroundVehicleRenderer(context, bvp("custom_geo/{vid}.geo.json"),{nl}'
                f'{ind}                bvp("textures/entity/{vid}.png"), "{vid}"));{nl}')
        _write(path, src[:m.end()] + line + src[m.end():])


def add_lang(entries, after_key):
    """lang keys inserted in order after the line of `after_key` (an anchored insertion between complete lines)."""
    path = os.path.join(vehgen.ASSETS, 'lang', 'en_us.json')
    src = _read(path)
    nl = _nl(src)
    anchor = f'  "{after_key}": '
    i = src.index(anchor)
    j = src.index('\n', i) + 1
    add = ''
    for k, v in entries.items():
        if f'"{k}"' in src:
            continue
        add += f'  "{k}": {json.dumps(v, ensure_ascii=False)},{nl}'
    if add:
        _write(path, src[:j] + add + src[j:])
    json.loads(_read(path))


def add_containers(vid, after_vid, land=True, mobile=True, tab=True):
    data = vehgen.DATA
    jobs = []
    if tab:
        jobs.append(('creative_tabs.json', f'"{after_vid}",', f'"{vid}",'))
    if mobile:
        jobs.append(('sbw/containers/mobile_vehicles.json', f'"{NS}:{after_vid}",', f'"{NS}:{vid}",'))
    for rel, pattern, insert in jobs:
        path = os.path.join(data, rel)
        src = _read(path)
        if insert in src:
            continue
        i = src.index(pattern)
        line_start = src.rfind('\n', 0, i) + 1
        indent = src[line_start:i]
        j = src.index('\n', i) + 1
        _write(path, src[:j] + indent + insert + _nl(src) + src[j:])
        json.loads(_read(path))
    if land:
        path = os.path.join(data, 'sbw', 'containers', 'land_vehicles.json')
        src = _read(path)
        if f'"{NS}:{vid}"' not in src:
            m = re.search(rf'( *)\{{\r?\n *"Type": "{NS}:{after_vid}",\r?\n *"Weight": (\d+)\r?\n *\}},\r?\n', src)
            block = m.group(0).replace(f'{NS}:{after_vid}', f'{NS}:{vid}')
            _write(path, src[:m.end()] + block + src[m.end():])
            json.loads(_read(path))
    path = os.path.join(REPO, 'tools', 'vehicle_scale', 'applied.json')
    src = _read(path)
    if f'"{vid}"' not in src:
        # sized at the pack's 1.1 x real (17.6 px per m), like vehgen's ground vehicles: one line inserted after the last
        # key that sorts before it (the file is not re-sorted: other entries keep their order)
        nl = _nl(src)
        lines = src.split(nl)
        at = 1
        for k, line in enumerate(lines):
            m = re.match(r'\s*"([^"]+)":', line)
            if m and m.group(1) < vid:
                at = k + 1
        lines.insert(at, f' "{vid}": 1.1,')
        if not lines[at + 1].strip().startswith('"'):         # inserted as the last entry: move the comma
            lines[at] = lines[at].rstrip(',')
            lines[at - 1] = lines[at - 1] + ','
        _write(path, nl.join(lines))
        json.loads(_read(path))


# ------------------------------------------------------------------------------------------------- weapons
def rocket_weapon(name, magazine, rpm, reload_ticks, projectile_type, profile_id, tnt, velocity, gravity, life,
                  muzzles, positions, sounds, spread=0.3, radius=8, icon='generic_small_rocket', zoom=2,
                  ammo='superbwarfare:small_rocket', consumption='superbwarfare:small_unguided_rocket'):
    """An unguided rocket salvo weapon in the pack's current schema (the Mi-24V S-8 / old BM-21 layout): every muzzle
    attachment a tube mouth on the launcher, absolute rest positions in the Vehicle frame (strela_10m2's convention)."""
    sp = {'Transform': 'Vehicle', 'ShootDirectionForHud': [0, 0, 1], 'Positions': [list(p) for p in positions],
          'Directions': [[0, 0, 1]] * len(positions), 'ViewPosition': list(positions[0]),
          'MuzzleAttachments': list(muzzles), 'MuzzleDirectionAttachments': list(muzzles),
          'EffectAttachments': list(muzzles), 'EffectDirectionAttachments': list(muzzles),
          'BoundUpWithAmmoAmount': True}
    w = {
        'Name': name,
        'AmmoType': [{'Ammo': ammo, 'ConsumptionItem': consumption, 'Override': {'TntEquivalentKg': tnt}}],
        'Icon': f'berts_vehicle_pack:textures/overlay/vehicle/weapon/icons/{icon}.png',
        'DefaultZoom': zoom, 'DefaultFireMode': 'Auto', 'AvailableFireModes': 'Auto',
        'Magazine': magazine, 'NormalReloadTime': reload_ticks, 'EmptyReloadTime': reload_ticks,
        'Projectile': {'Type': projectile_type, 'Profile': profile_id},
        'Spread': spread, 'RPM': rpm, 'Damage': 100, 'ExplosionDamage': 0, 'ExplosionRadius': radius,
        'TntEquivalentKg': tnt, 'Gravity': gravity, 'Velocity': velocity, 'ProjectileLife': life,
        'ShellType': 'HE', 'Crosshair': '@Empty', 'SoundRadius': 16,
        'SoundInfo': sounds,
        'NominalBallistics': {'Supported': True, 'ProjectileType': projectile_type, 'ProjectileProfile': profile_id,
                              'ProjectileLife': life},
        'ShootPos': sp,
        'ShootShake': [8, 10, 60],
    }
    return w


def rocket_profile(weapon_id, round_id, calibre, hull, rack, pen, explosion='large', scale=1.0, boost_ticks=40):
    """A rocket projectile profile (berts_vehicle_pack:rocket) in the Mi-24V B-13L / S-13 layout. DamageNormalizationDataTest
    rules: module = min(100, 0.6 hull), rack in 1..1000, rocket penetration > 0."""
    p = load(os.path.join(vehgen.DATA, 'sbw', 'projectile_profiles', 'mi24v', 'b13lrocket', 'default.json'))
    c = p['Combat']
    c.update({'WeaponId': weapon_id, 'RoundId': round_id, 'CaliberMm': calibre, 'DiameterMm': calibre,
              'HullDamageClass': 'HE', 'HullDamage': hull, 'ModuleDamage': int(round(min(100.0, 0.6 * hull))),
              'AmmoRackDamage': rack, 'PenetrationMm': float(pen)})
    p['RenderScale'] = scale
    trail = p['Extensions']['berts_vehicle_pack:projectile_effect_v1']
    trail['Trail']['Phases'][0]['EndTickExclusive'] = boost_ticks
    trail['Trail']['Phases'][1]['StartTick'] = boost_ticks
    trail['Trail']['WidthBlocks'] = round(0.1 * scale, 3)
    trail['Impact']['Explosion'] = explosion
    return p


# ---------------------------------------------------------------------------------------------- pictures
def render_views(vid, out_dir, size=520, skip='^(brokenTrack|crudeTrack)'):
    """Blender views (top, below, three_quarter, left, right, front, rear) of the committed geo + texture."""
    os.makedirs(out_dir, exist_ok=True)
    obj = os.path.join(out_dir, 'model.obj')
    a = vehgen.ASSETS
    subprocess.run([sys.executable, os.path.join(HERE, 'geo_to_obj.py'), os.path.join(a, 'custom_geo', vid + '.geo.json'),
                    obj, os.path.join(a, 'textures', 'entity', vid + '.png'), '--skip', skip], check=True,
                   stdout=subprocess.DEVNULL)
    subprocess.run([BLENDER, '--background', '--factory-startup', '--python',
                    os.path.join(EE2, 'tools', 'render_views.py'), '--', obj, out_dir, str(size)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return {n: os.path.join(out_dir, n + '.png') for n in ('three_quarter', 'left', 'right', 'front', 'rear', 'top',
                                                             'below')}


def eye_render(vid, out, eye, yaw=0.0, pitch=-5.0, fov=75.0, size=(520, 390), geo_path=None):
    sys.path.insert(0, os.path.join(REPO, 'tools', 'cockpit_models'))
    import eyeview
    eyeview.render(vid, out, eye=list(eye), yaw=yaw, pitch=pitch, fov=fov, size=size, geo_path=geo_path)
    return out


def sheet(tiles, out, title, width=1100, cols=3):
    """[(label, png)] -> a labelled contact sheet `width` px wide."""
    ims = [(lab, Image.open(p).convert('RGB')) for lab, p in tiles]
    tw = width // cols
    th = int(tw * 0.75)
    rows = (len(ims) + cols - 1) // cols
    sh = Image.new('RGB', (tw * cols, rows * th + 34), (30, 30, 34))
    d = ImageDraw.Draw(sh)
    d.text((10, 10), title, fill=(240, 240, 240))
    for i, (lab, im) in enumerate(ims):
        im = im.resize((tw, th), Image.LANCZOS)
        x, y = (i % cols) * tw, 34 + (i // cols) * th
        sh.paste(im, (x, y))
        d.rectangle([x + 4, y + 4, x + 8 + 7 * len(lab), y + 20], fill=(250, 250, 250))
        d.text((x + 7, y + 6), lab, fill=(10, 10, 10))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    sh.save(out, quality=86)
    return out
