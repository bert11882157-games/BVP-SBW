#!/usr/bin/env python3
"""Lean vehicle generator: one SMP Toolbox model + one existing vehicle as template -> a complete BVP vehicle,
written straight into the repo outputs (never through the old 17k-line generator, whose outputs later repo passes
have changed).

  python3 tools/vehgen/vehgen.py <spec.json> [--sources DIR] [--write]

The spec (tools/vehgen/specs/*.json) names the source, its scale, which toolbox parts become which template bones,
and every functional point (seats, eyes, pivots, muzzle, exhaust) in GEO pixels measured on the converted model
(see README.md for the frames and how each point was chosen). Everything else is derived:
  custom_geo/<id>.geo.json          poly meshes per bone, template bone hierarchy and names (runtime names kept)
  custom_geo/<id>_turret_wreck      template copy (renamed)
  geo/native_fallback/<id>          one bounding cube per bone
  assets sbw/vehicles/<id>.json     template client model definition, ids and exhaust replaced
  data sbw/vehicles/<id>.json       template vehicle; seats, attachments, station, OBBs, terrain contacts, muzzle
  data armor/<id>.json              template plates mapped hull box -> hull box
  textures entity / entity_dead (x0.178, the pack's wreck darkening) / 32 px item icon
  lang, creative tab, containers, entity class, ModEntities, ModEntityRenderers registration (idempotent)
Without --write it only prints what it would do.
"""
import copy, io, json, math, os, re, sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
import mtbgeo  # noqa: E402

NS = 'berts_vehicle_pack'
GEN = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources')
MAIN = os.path.join(REPO, 'bvp', 'src', 'main')
ASSETS = os.path.join(GEN, 'assets', NS)
DATA = os.path.join(GEN, 'data', NS)
JAVA = os.path.join(MAIN, 'java', 'com', 'yourname', 'berts_vehicle_pack')
DEAD = 0.178


def r5(v):
    return [round(float(c), 5) for c in v]


def load(path):
    with open(path) as f:
        return json.load(f)


class Output:
    def __init__(self, write):
        self.write, self.files = write, []

    def json(self, path, data, compact=False, indent=2, newline=True):
        self.files.append(path)
        if self.write:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'w') as f:
                if compact:
                    json.dump(data, f, separators=(',', ':'))
                else:
                    json.dump(data, f, indent=indent)
                if newline:
                    f.write('\n')

    def image(self, path, img):
        self.files.append(path)
        if self.write:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            img.save(path)

    def text(self, path, before, after):
        if before == after:
            return
        self.files.append(path)
        if self.write:
            with open(path, 'w') as f:
                f.write(after)


class Build:
    def __init__(self, spec, sources):
        self.spec = spec
        self.id = spec['id']
        self.template = spec['template']
        self.src = mtbgeo.Source(os.path.join(sources, spec['source']))
        s = spec['scale']
        ground = self.src.corners()[:, 1].max()          # lowest toolbox point (+y down) = the ground
        self.frame = mtbgeo.Frame(s, ground * s)
        # longitudinal anchor: the named part's centre lands on the template's geo z
        anchor = spec['anchor']
        z = self.frame.geo(self.src.corners(anchor['parts']).mean(0))[2]
        self.z_shift = anchor['geoZ'] - z

    def geo(self, p):
        g = self.frame.geo(p)
        g[2] += self.z_shift
        return g

    def mesh(self, parts):
        els = [f for p in parts for f in self.src.parts.get(p, [])]
        m = mtbgeo.mesh(els, self.frame, self.src.tw, self.src.th)
        for p in m['positions']:
            p[2] = round(p[2] + self.z_shift, 5)
        return m

    def part_centre(self, parts):
        c = np.array([self.geo(p) for p in self.src.corners(parts)])
        return (c.min(0) + c.max(0)) / 2, c.min(0), c.max(0)

    def point(self, v):
        """A spec point: [x, y, z] in model px (the geo frame before the longitudinal shift), or
        {"part": n | "parts": [..], "at": "centre" | "rotation"} (bounding-box centre / element rotation point)."""
        if isinstance(v, dict):
            parts = v.get('parts') or [v['part']]
            if v.get('at') == 'rotation':
                return self.geo(mtbgeo.Source.rotation_point([f for p in parts for f in self.src.parts[p]]))
            return self.part_centre(parts)[0]
        g = np.array(v, float)
        g[2] += self.z_shift
        return g


def build_geo(b, tpl_geo):
    spec = b.spec
    g = copy.deepcopy(tpl_geo)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{b.id}'
    geo['description']['texture_width'] = b.src.tw
    geo['description']['texture_height'] = b.src.th
    bones = {bone['name']: bone for bone in geo['bones']}
    for bone in geo['bones']:
        bone.pop('poly_mesh', None)
    for name, parts in spec['bones'].items():
        bones[name]['poly_mesh'] = b.mesh(parts)
    for name, v in spec.get('pivots', {}).items():
        bones[name]['pivot'] = r5(b.point(v))
    # bones the spec does not fill are dropped when they only carried template geometry
    keep = set(spec['bones']) | set(spec.get('pivots', {})) | set(spec.get('keepBones', []))
    geo['bones'] = [bone for bone in geo['bones'] if bone['name'] in keep]
    names = {bone['name'] for bone in geo['bones']}
    for bone in geo['bones']:
        if bone.get('parent') and bone['parent'] not in names:
            raise SystemExit(f'bone {bone["name"]}: parent {bone["parent"]} dropped')
    lo, hi = mtbgeo.bounds([bone['poly_mesh'] for bone in geo['bones'] if 'poly_mesh' in bone])
    size = hi - lo
    geo['description']['visible_bounds_width'] = round(float(max(size[0], size[2]) / 16 + 1), 2)
    geo['description']['visible_bounds_height'] = round(float(size[1] / 16 + 1), 2)
    geo['description']['visible_bounds_offset'] = [0, round(float((lo[1] + hi[1]) / 32), 2), 0]
    return g, lo, hi


def native_fallback(b, geo):
    g0 = geo['minecraft:geometry'][0]
    out = {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': dict(g0['description'], identifier=f'geometry.{b.id}_native_fallback'),
        'bones': [{'name': 'bvpNativeFallbackFrame', 'pivot': [0, 0, 0], 'rotation': [0, 180, 0]}]}]}
    for bone in g0['bones']:
        nb = {'name': bone['name'], 'parent': bone.get('parent') or 'bvpNativeFallbackFrame', 'pivot': bone['pivot']}
        if bone.get('poly_mesh') and bone['poly_mesh']['positions']:
            P = np.array(bone['poly_mesh']['positions'])
            nb['cubes'] = [{'origin': r5(P.min(0)), 'size': r5(P.max(0) - P.min(0)), 'uv': [0, 0]}]
        out['minecraft:geometry'][0]['bones'].append(nb)
    return out


def box_obb(lo, hi, transform='Vehicle', part=None, origin=np.zeros(3)):
    """OBB from a geo-px box (min/max), relative to a geo-px origin; data frame, half sizes in blocks."""
    c = mtbgeo.geo_to_data((lo + hi) / 2 - origin)
    half = (hi - lo) / 32
    o = {'Position': r5(c), 'Size': r5(half), 'Transform': transform, 'Rotation': transform}
    if part:
        o['Part'] = part
    return o


def build_data(b, tpl, geo, lo, hi):
    spec = b.spec
    d = copy.deepcopy(tpl)
    d['ID'] = f'{NS}:{b.id}'
    bones = {bone['name']: bone for bone in geo['minecraft:geometry'][0]['bones']}
    pts = {k: b.point(v) for k, v in spec['points'].items()}
    station = np.array(bones['passengerWeaponStation']['pivot'])
    pitch = np.array(bones['passengerWeaponStationPitch']['pivot'])
    d['PassengerWeaponStationPos'] = r5(mtbgeo.geo_to_data(station))
    d['PassengerWeaponStationBarrelPos'] = r5(mtbgeo.geo_to_data(pitch - station))

    # seats: [geo px point, frame]; frames: Vehicle (absolute) or WeaponStation (relative to the station pivot)
    for i, seat in enumerate(spec['seats']):
        p = b.point(seat['at'])
        if seat.get('frame', 'Vehicle') == 'WeaponStation':
            p = p - station
        d['Seats'][i]['Position'] = r5(mtbgeo.geo_to_data(p))
    if len(spec['seats']) != len(d['Seats']):
        d['Seats'] = d['Seats'][:len(spec['seats'])]

    at = d['Attachments']
    for name, v in spec['attachments'].items():
        p = b.point(v['at'])
        parent = at[name]['Parent']
        if parent == 'WeaponStationBarrel':
            p = p - pitch
        elif parent != 'VehicleCustomPitch':
            raise SystemExit(f'attachment {name}: unsupported parent {parent}')
        at[name]['Position'] = r5(mtbgeo.geo_to_data(p))
    muzzle = at['passenger_station_muzzle']['Position']
    for w in d['Weapons'].values():
        sp = w.get('ShootPos') or {}
        if sp.get('Transform') == 'WeaponStationBarrel':
            sp['Positions'] = [muzzle for _ in sp['Positions']]
            sp['ViewPosition'] = muzzle

    # OBBs: hull sections along the length (spec 'obbSections', geo z cuts), station, barrel, wheels
    hull = np.array(bones['hull']['poly_mesh']['positions'])
    obbs = []
    for z0, z1, y_min in spec['obbSections']:          # model px (before the longitudinal shift)
        z0, z1 = z0 + b.z_shift, z1 + b.z_shift
        sel = hull[(hull[:, 2] >= z0) & (hull[:, 2] <= z1) & (hull[:, 1] >= y_min)]
        slo, shi = sel.min(0), sel.max(0)
        slo[2], shi[2] = max(slo[2], z0), min(shi[2], z1)
        obbs.append(box_obb(slo, shi))
    yaw = np.array(bones['passengerWeaponStationYaw']['poly_mesh']['positions'])
    obbs.append(box_obb(yaw.min(0), yaw.max(0), 'WeaponStation', origin=station))
    tube = np.array(bones['passengerWeaponStationPitch']['poly_mesh']['positions'])
    obbs.append(box_obb(tube.min(0), tube.max(0), 'WeaponStationBarrel', origin=pitch))
    contacts = []
    for name in ('wheelL0', 'wheelL1', 'wheelR0', 'wheelR1'):
        P = np.array(bones[name]['poly_mesh']['positions'])
        wlo, whi = P.min(0), P.max(0)
        obbs.append(box_obb(wlo, whi, part='WheelLeft' if 'L' in name else 'WheelRight'))
        c = mtbgeo.geo_to_data((wlo + whi) / 2)
        contacts.append((name, [c[0], 0.03013, c[2]]))
    d['OBB'] = obbs
    order = {'wheelL0': 0, 'wheelL1': 1, 'wheelR0': 2, 'wheelR1': 3}
    d['TerrainCompat'] = [r5(c) for _, c in sorted(contacts, key=lambda x: order[x[0]])]

    # third person camera distance follows the model length (template ratio); RotateOffsetHeight stays the template's
    t_len = spec['_templateHull'][1][2] - spec['_templateHull'][0][2]
    ratio = (hi[2] - lo[2]) / t_len
    d['ThirdPersonCameraPos'] = r5(np.array(tpl['ThirdPersonCameraPos']) * ratio)
    d.update(spec.get('dataOverrides', {}))
    return d, pts


def build_armor(b, tpl, lo, hi, tlo, thi):
    a = copy.deepcopy(tpl)
    a['id'] = b.id
    k = (hi - lo) / (thi - tlo)
    for plate in a['plates']:
        if plate['frame'] != 'hull':
            raise SystemExit('armor: only hull plates are mapped')
        c = np.array(plate['center']) * 16
        plate['center'] = r5(((c - tlo) * k + lo) / 16)
        plate['half_size'] = r5(np.array(plate['half_size']) * k)
        plate['name'] = plate['name'].replace(b.template, b.id)
    return a


def icon(b, geo_meshes, img):
    """32 px item icon in the pack's style: right-side view, nose right, flat colours, 1 px dark outline."""
    from PIL import ImageDraw
    items = []
    for m in geo_meshes:
        P = np.array(m['positions']); U = np.array(m['uvs']); N = np.array(m['normals'])
        for poly in m['polys']:
            n = N[poly[0][1]]
            if n[0] >= 0:   # only faces turned toward the viewer on the vehicle's right (-X)
                continue
            q = P[[v[0] for v in poly]]
            uv = U[[v[2] for v in poly]].mean(0)
            th, tw = img.shape[:2]
            c = img[int(np.clip((1 - uv[1]) * th, 0, th - 1)), int(np.clip(uv[0] * tw, 0, tw - 1))]
            if c[3] < 0.1:
                continue
            items.append((q[:, 0].mean(), np.c_[-q[:, 2], q[:, 1]], c[:3] * (0.75 + 0.25 * abs(n[0]))))
    items.sort(key=lambda i: -i[0])            # far (left side, +X) first
    allp = np.vstack([i[1] for i in items])
    lo, hi = allp.min(0), allp.max(0)
    size, inner = 30, 28
    k = inner / max(hi - lo)
    off = (size - (hi - lo) * k) / 2
    big = 8
    canvas = Image.new('RGBA', (size * big, size * big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    for _, q, c in items:
        xy = [(float(((p[0] - lo[0]) * k + off[0]) * big), float((size - ((p[1] - lo[1]) * k + off[1])) * big))
              for p in q]
        draw.polygon(xy, fill=tuple(int(v * 255) for v in c) + (255,))
    small = canvas.resize((size, size), Image.BOX)
    a = np.asarray(small).astype(float)
    solid = a[..., 3] > 110
    rgb = np.where(solid[..., None], a[..., :3] / np.maximum(a[..., 3:4], 1) * 255, 0)
    out = np.zeros((32, 32, 4), np.uint8)
    out[1:31, 1:31, :3] = np.clip(rgb, 0, 255)
    out[1:31, 1:31, 3] = np.where(solid, 255, 0)
    mask = out[..., 3] > 0
    edge = np.zeros_like(mask)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        edge |= np.roll(np.roll(mask, dy, 0), dx, 1)
    edge &= ~mask
    out[edge] = (12, 12, 12, 255)
    return Image.fromarray(out)


def register(b, out):
    spec = b.spec
    cls = spec['entityClass']
    const = b.id.upper()
    tpl_const = b.template.upper()
    # entity class
    path = os.path.join(JAVA, 'entity', cls + '.java')
    body = (f'package com.yourname.berts_vehicle_pack.entity;\n\nimport net.minecraft.world.entity.EntityType;\n'
            f'import net.minecraft.world.level.Level;\n\npublic class {cls} extends ArmoredVehicleEntity {{\n'
            f'    public {cls}(EntityType<{cls}> type, Level world) {{\n        super(type, world, "{b.id}");\n'
            f'    }}\n}}\n')
    out.text(path, open(path).read() if os.path.exists(path) else '', body)
    # ModEntities: after the template's registration
    path = os.path.join(JAVA, 'init', 'ModEntities.java')
    src = open(path).read()
    if f'"{b.id}"' not in src:
        m = re.search(rf'( *)public static final RegistryObject<EntityType<\w+>> {tpl_const} =\n.*?\n', src)
        w, h = spec['hitbox']
        line = (f'{m.group(1)}public static final RegistryObject<EntityType<{cls}>> {const} =\n'
                f'{m.group(1)}        ENTITIES.register("{b.id}", () -> vehicle({cls}::new, {w}f, {h}f, "{b.id}"));\n')
        new = src[:m.end()] + line + src[m.end():]
        tpl_import = re.search(rf'import com\.yourname\.berts_vehicle_pack\.entity\.\w+;\n', src)
        own = f'import com.yourname.berts_vehicle_pack.entity.{cls};\n'
        if own not in new:
            new = new[:tpl_import.end()] + own + new[tpl_import.end():]
        out.text(path, src, new)
    # ModEntityRenderers: after the template's registration
    path = os.path.join(JAVA, 'init', 'ModEntityRenderers.java')
    src = open(path).read()
    if f'"{b.id}"' not in src:
        m = re.search(rf'( *)registerVehicle\(event, ModEntities\.{tpl_const}, "{b.template}", .*?;\n', src, re.S)
        renderer = ''.join(p.capitalize() for p in b.id.split('_')) + 'Renderer'
        line = (f'{m.group(1)}registerVehicle(event, ModEntities.{const}, "{b.id}", standardVehicle(\n'
                f'{m.group(1)}        "custom_geo/{b.id}.geo.json", "textures/entity/{b.id}.png",\n'
                f'{m.group(1)}        "{renderer}"));\n')
        out.text(path, src, src[:m.end()] + line + src[m.end():])
    # lang
    path = os.path.join(ASSETS, 'lang', 'en_us.json')
    src = open(path).read()
    key = f'entity.{NS}.{b.id}'
    if key not in src:
        tkey = f'  "entity.{NS}.{b.template}": '
        i = src.index(tkey)
        j = src.index('\n', i) + 1
        out.text(path, src, src[:j] + f'  "{key}": {json.dumps(spec["name"])},\n' + src[j:])
    # creative tab, containers: after the template
    for rel, pattern, insert in [
        ('creative_tabs.json', f'"{b.template}",\n', f'"{b.id}",\n'),
        ('sbw/containers/mobile_vehicles.json', f'"{NS}:{b.template}",\n', f'"{NS}:{b.id}",\n'),
    ]:
        path = os.path.join(DATA, rel)
        src = open(path).read()
        if insert.strip() not in src:
            i = src.index(pattern)
            indent = src[src.rfind('\n', 0, i) + 1:i]
            j = i + len(pattern)
            out.text(path, src, src[:j] + indent + insert + src[j:])
    path = os.path.join(DATA, 'sbw', 'containers', 'land_vehicles.json')
    src = open(path).read()
    if f'"{NS}:{b.id}"' not in src:
        m = re.search(rf'( *)\{{\n *"Type": "{NS}:{b.template}",\n *"Weight": (\d+)\n *\}},\n', src)
        block = m.group(0).replace(f'{NS}:{b.template}', f'{NS}:{b.id}')
        out.text(path, src, src[:m.end()] + block + src[m.end():])


def main(argv):
    write = '--write' in argv
    args = [a for a in argv if not a.startswith('--')]
    sources = '/tmp/claude-0/exp/elite additions'
    if '--sources' in argv:
        sources = argv[argv.index('--sources') + 1]
        args.remove(sources)
    spec = load(args[0])
    b = Build(spec, sources)
    out = Output(write)
    tid = b.template
    tpl_geo = load(os.path.join(ASSETS, 'custom_geo', f'{tid}.geo.json'))
    t_hull = np.array([bn for bn in tpl_geo['minecraft:geometry'][0]['bones'] if bn['name'] == 'hull'][0]
                      ['poly_mesh']['positions'])
    spec['_templateHull'] = (t_hull.min(0), t_hull.max(0))

    geo, lo, hi = build_geo(b, tpl_geo)
    out.json(os.path.join(ASSETS, 'custom_geo', f'{b.id}.geo.json'), geo, compact=True)
    wreck = load(os.path.join(ASSETS, 'custom_geo', f'{tid}_turret_wreck.geo.json'))
    wreck['minecraft:geometry'][0]['description']['identifier'] = f'geometry.{b.id}_turret_wreck'
    out.json(os.path.join(ASSETS, 'custom_geo', f'{b.id}_turret_wreck.geo.json'), wreck, compact=True)
    out.json(os.path.join(ASSETS, 'geo', 'native_fallback', f'{b.id}.geo.json'), native_fallback(b, geo), compact=True)

    client = load(os.path.join(ASSETS, 'sbw', 'vehicles', f'{tid}.json'))
    client = json.loads(json.dumps(client).replace(f'/{tid}', f'/{b.id}').replace(f':{tid}', f':{b.id}'))
    exhaust = mtbgeo.geo_to_data(b.point(spec['points']['exhaust']))
    client['EngineExhaust']['Origins'][0]['Position'] = r5(exhaust)
    out.json(os.path.join(ASSETS, 'sbw', 'vehicles', f'{b.id}.json'), client)

    data, pts = build_data(b, load(os.path.join(DATA, 'sbw', 'vehicles', f'{tid}.json')), geo, lo, hi)
    out.json(os.path.join(DATA, 'sbw', 'vehicles', f'{b.id}.json'), data)
    hull = np.array([bn for bn in geo['minecraft:geometry'][0]['bones'] if bn['name'] == 'hull'][0]
                    ['poly_mesh']['positions'])
    out.json(os.path.join(DATA, 'armor', f'{b.id}.json'),
             build_armor(b, load(os.path.join(DATA, 'armor', f'{tid}.json')), hull.min(0), hull.max(0),
                         *spec['_templateHull']))

    img = Image.open(io.BytesIO(b.src.png)).convert('RGBA')
    out.image(os.path.join(ASSETS, 'textures', 'entity', f'{b.id}.png'), img)
    a = np.asarray(img).astype(float)
    dead = a.copy()
    dead[..., :3] = np.clip(np.round(a[..., :3] * DEAD), 0, 255)
    out.image(os.path.join(ASSETS, 'textures', 'entity_dead', f'{b.id}.png'), Image.fromarray(dead.astype(np.uint8)))
    meshes = [bn['poly_mesh'] for bn in geo['minecraft:geometry'][0]['bones'] if 'poly_mesh' in bn]
    out.image(os.path.join(MAIN, 'resources', 'assets', NS, 'textures', 'item', 'vehicle_icons', f'{b.id}.png'),
              icon(b, meshes, np.asarray(img).astype(float) / 255))
    register(b, out)
    # vehgen sizes ground vehicles against their (already x1.1) template: record it so vehicle_scale never rescales
    path = os.path.join(REPO, 'tools', 'vehicle_scale', 'applied.json')
    applied = load(path)
    if b.id not in applied:
        applied[b.id] = 1.1
        out.json(path, dict(sorted(applied.items())), indent=1)

    print(f'{b.id}: scale {spec["scale"]} px/unit, z shift {b.z_shift:.2f} px, hull '
          f'{(hi - lo) / 16} blocks (template hull {(spec["_templateHull"][1] - spec["_templateHull"][0]) / 16})')
    for i, s in enumerate(data['Seats']):
        print(f'  seat {i} {s["Transform"]:<14} {s["Position"]}')
    for k, v in data['Attachments'].items():
        print(f'  attachment {k:<28} {v["Parent"]:<20} {v["Position"]}')
    print(('wrote ' if write else 'would write ') + f'{len(out.files)} files')
    for f in out.files:
        print('  ' + os.path.relpath(f, REPO))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
