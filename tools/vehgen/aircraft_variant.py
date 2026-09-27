#!/usr/bin/env python3
"""Aircraft variant: a new aircraft from a provided SMP Toolbox model and an existing aircraft of the same family
(F-16B from F-16C, ...), written straight into the repo outputs.

  python3 tools/vehgen/aircraft_variant.py tools/vehgen/specs/<id>.json [--sources DIR] [--write]

The new airframe is aligned to the template (same length, nose, ground), so everything the template's later repo
passes authored for that airframe - flight data, OBBs, terrain contact, surface modules, pylon stations, afterburner
outlets, gear rig - stays valid. What the spec lists is rebuilt from the new model:
  airframe   the source elements (minus baked stores: texture rows below `storeTextureV`, outside `keepBoxes`)
             distributed over the template's wreck sections: wings by part, fuselage by the template's section cuts,
             control surfaces into their template bones
  kept       template bones that stay template geometry (gear, gear wheels, control stick, modeled store assemblies);
             they keep their texture through a combined atlas (template on top, source below)
  seats      extra crew seats and eye attachments
  client     removed keys (e.g. CanopyGlass when the source models its own canopy)
Everything else (armor, armaments, modeled stores, flight reference) is the template's under the new id.
"""
import copy, io, json, os, re, sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mtbgeo  # noqa: E402
import vehgen  # noqa: E402
from vehgen import ASSETS, DATA, MAIN, NS, Output, load, r5  # noqa: E402

num = mtbgeo.mtb.num


def main(argv):
    write = '--write' in argv
    args = [a for a in argv if not a.startswith('--')]
    sources = '/tmp/claude-0/exp/elite additions'
    if '--sources' in argv:
        sources = argv[argv.index('--sources') + 1]
        args.remove(sources)
    spec = load(args[0])
    vid, tid = spec['id'], spec['template']
    out = Output(write)
    src = mtbgeo.Source(os.path.join(sources, spec['source']))
    tpl_geo = load(os.path.join(ASSETS, 'custom_geo', f'{tid}.geo.json'))
    tg = tpl_geo['minecraft:geometry'][0]
    tbones = {b['name']: b for b in tg['bones']}

    # ---- alignment: source airframe length / nose / ground onto the template airframe
    air_parts = [p for p in src.parts if p not in spec.get('excludeParts', [])]
    frame1 = mtbgeo.Frame(1.0, 0.0, yaw180=spec.get('yaw180', False))
    c1 = np.array([frame1.geo(q) for p in spec['lengthParts'] for f in src.parts[p] for q in mtbgeo.mtb.box_corners(f)])
    T = np.vstack([np.array(tbones[n]['poly_mesh']['positions']) for n in spec['templateLengthBones']])
    s = (T[:, 2].max() - T[:, 2].min()) / (c1[:, 2].max() - c1[:, 2].min())
    frame = mtbgeo.Frame(s, 0.0, yaw180=spec.get('yaw180', False))
    cs = c1 * s
    off = np.array([0.0, 0.0, T[:, 2].min() - cs[:, 2].min()])
    ground = np.array([frame.geo(q) for p in spec.get('groundParts', spec['lengthParts']) for f in src.parts[p]
                       for q in mtbgeo.mtb.box_corners(f)])     # the source's own gear sets the ground
    off[1] = spec.get('groundY', 0.0) - ground[:, 1].min()
    if spec.get('alignY') == 'templateBottom':
        # the template's gear is kept: the fuselage bottom goes where the template's is, not the source's own gear
        off[1] = T[:, 1].min() - cs[:, 1].min()

    def g(p):
        return frame.geo(p) + off

    # ---- element selection and section assignment
    boxes = [(np.array(a, float), np.array(b, float)) for a, b in spec.get('keepBoxes', [])]

    def centre(f):
        c = np.array([g(q) for q in mtbgeo.mtb.box_corners(f)])
        return (c.min(0) + c.max(0)) / 2

    drop_rules = spec.get('dropRules', [])     # [{"absXMin": .., "yMax": .., "absXMax": .., "zMin": ..}] (all given hold)

    def is_store(f):
        c = centre(f)
        for r in drop_rules:
            if (abs(c[0]) >= r.get('absXMin', -1e9) and abs(c[0]) <= r.get('absXMax', 1e9)
                    and r.get('yMin', -1e9) <= c[1] <= r.get('yMax', 1e9)
                    and r.get('zMin', -1e9) <= c[2] <= r.get('zMax', 1e9)):
                return True
        if num(f[19]) >= spec.get('storeTextureV', -1):
            return False
        return not any(np.all(c >= lo) and np.all(c <= hi) for lo, hi in boxes)

    assign = {}
    dropped = 0
    cuts = spec['fuselageCuts']            # geo z cuts between fuselage sections 0|1|2|3
    for p in air_parts:
        rule = spec['parts'].get(str(p), 'fuselage')
        for f in src.parts[p]:
            if is_store(f):
                dropped += 1
                continue
            c = centre(f)
            bone = None
            if isinstance(rule, dict):     # {"bone": ..., "if": {"zMin": ...}} else fuselage
                cond = rule.get('if', {})
                if c[2] >= cond.get('zMin', -1e9) and c[2] <= cond.get('zMax', 1e9):
                    bone = rule['bone']
                rule_name = rule.get('else', 'fuselage') if bone is None else None
            else:
                rule_name = rule
            if bone is None:
                if rule_name == 'wing':
                    bone = 'wreck_wing_left__hull' if c[0] > 0 else 'wreck_wing_right__hull'
                elif rule_name == 'fuselage':
                    k = sum(c[2] > z for z in cuts)
                    bone = f'wreck_fuselage_{k}__hull'
                elif rule_name.startswith('side:'):       # side:left_bone,right_bone
                    lb, rb = rule_name[5:].split(',')
                    bone = lb if c[0] > 0 else rb
                else:
                    bone = rule_name
            if bone == 'drop':
                continue
            assign.setdefault(bone, []).append(f)

    # ---- combined texture: template atlas on top, source below (normalized v, flipped: 1 = top)
    t_img = Image.open(os.path.join(ASSETS, 'textures', 'entity', f'{tid}.png')).convert('RGBA')
    s_img = Image.open(io.BytesIO(src.png)).convert('RGBA')
    W = max(t_img.width, s_img.width)
    atlas = Image.new('RGBA', (W, t_img.height + s_img.height), (0, 0, 0, 0))
    atlas.paste(t_img, (0, 0))
    atlas.paste(s_img, (0, t_img.height))
    H = atlas.height
    t_frac, s_frac = t_img.height / H, s_img.height / H

    def remap_template(m):
        m = copy.deepcopy(m)
        m['uvs'] = [[round(u * t_img.width / W, 6), round(1 - (1 - v) * t_frac, 6)] for u, v in m['uvs']]
        return m

    def source_mesh(els):
        m = mtbgeo.mesh(els, frame, src.tw, src.th)
        for q in m['positions']:
            q[0] = round(q[0] + off[0], 5); q[1] = round(q[1] + off[1], 5); q[2] = round(q[2] + off[2], 5)
        m['uvs'] = [[round(u * s_img.width / W, 6), round(t_frac * 0 + (1 - t_frac) - (1 - v) * s_frac + 0, 6)]
                    for u, v in m['uvs']]
        return m

    # ---- geometry
    geo = copy.deepcopy(tpl_geo)
    gg = geo['minecraft:geometry'][0]
    gg['description']['identifier'] = f'geometry.{vid}'
    gg['description']['texture_width'] = W
    gg['description']['texture_height'] = H
    keep = set(spec['keepTemplateBones'])
    unknown = set(assign) - {b['name'] for b in gg['bones']}
    if unknown:
        raise SystemExit(f'bones not in the template: {sorted(unknown)}')
    for b in gg['bones']:
        if b['name'] in assign:
            b['poly_mesh'] = source_mesh(assign[b['name']])
        elif 'poly_mesh' in b:
            if b['name'] in keep or any(b['name'].endswith('__' + k) for k in keep):
                b['poly_mesh'] = remap_template(b['poly_mesh'])
            else:
                b.pop('poly_mesh')
    out.json(os.path.join(ASSETS, 'custom_geo', f'{vid}.geo.json'), geo, compact=True)
    wreck_path = os.path.join(ASSETS, 'custom_geo', f'{tid}_turret_wreck.geo.json')
    if os.path.exists(wreck_path):
        w = load(wreck_path)
        w['minecraft:geometry'][0]['description']['identifier'] = f'geometry.{vid}_turret_wreck'
        out.json(os.path.join(ASSETS, 'custom_geo', f'{vid}_turret_wreck.geo.json'), w, compact=True)
    b = type('B', (), {'id': vid})()
    out.json(os.path.join(ASSETS, 'geo', 'native_fallback', f'{vid}.geo.json'), vehgen.native_fallback(b, geo),
             compact=True)

    # ---- textures and icon
    out.image(os.path.join(ASSETS, 'textures', 'entity', f'{vid}.png'), atlas)
    a = np.asarray(atlas).astype(float)
    dead = a.copy()
    dead[..., :3] = np.clip(np.round(a[..., :3] * vehgen.DEAD), 0, 255)
    out.image(os.path.join(ASSETS, 'textures', 'entity_dead', f'{vid}.png'), Image.fromarray(dead.astype(np.uint8)))
    meshes = [bn['poly_mesh'] for bn in gg['bones'] if 'poly_mesh' in bn and not bn['name'].startswith('suspended')
              and '__suspended' not in bn['name']]
    icon = vehgen.icon(b, meshes, a / 255)
    for rel, base in (('textures/vehicle_icon', ASSETS), ('textures/item/vehicle_icons', ASSETS),
                      ('textures/item/vehicle_icons', os.path.join(MAIN, 'resources', 'assets', NS))):
        tpl_icon = os.path.join(base, rel, f'{tid}.png')
        if os.path.exists(tpl_icon):
            size = Image.open(tpl_icon).size
            out.image(os.path.join(base, rel, f'{vid}.png'), icon.resize(size, Image.NEAREST))

    # ---- data: template under the new id, extra seats / attachments
    def renamed(obj):
        text = json.dumps(obj)
        # exact ids and asset paths only: sound events such as <ns>:<tid>_engine_loop stay the template's
        for a_, b_ in ((f'"{NS}:{tid}"', f'"{NS}:{vid}"'), (f'/{tid}"', f'/{vid}"'), (f'/{tid}.png"', f'/{vid}.png"'),
                       (f'/{tid}.geo.json"', f'/{vid}.geo.json"'), (f'modeled_store/{tid}/', f'modeled_store/{vid}/')):
            text = text.replace(a_, b_)
        return json.loads(text)

    data = renamed(load(os.path.join(DATA, 'sbw', 'vehicles', f'{tid}.json')))
    for name, at in spec.get('attachments', {}).items():
        data['Attachments'][name] = {'Parent': 'Vehicle', 'Position': r5(mtbgeo.geo_to_data(np.array(at))),
                                     'Direction': [0, 0, 1]}
    for i, at in spec.get('seatPositions', {}).items():
        data['Seats'][int(i)]['Position'] = r5(mtbgeo.geo_to_data(np.array(at)))
    for seat in spec.get('addSeats', []):
        base_seat = copy.deepcopy(data['Seats'][seat['copyOf']])
        for k in seat.get('remove', []):
            base_seat.pop(k, None)
        base_seat['Position'] = r5(mtbgeo.geo_to_data(np.array(seat['at'])))
        cam = base_seat.get('CameraPos', {})
        for k in ('EyeAttachment', 'ZoomEyeAttachment', 'DirectionAttachment', 'ZoomDirectionAttachment'):
            if k in cam:
                cam[k] = seat['eye']
        data['Seats'].append(base_seat)
    data.update(spec.get('dataOverrides', {}))
    out.json(os.path.join(DATA, 'sbw', 'vehicles', f'{vid}.json'), data)
    client = renamed(load(os.path.join(ASSETS, 'sbw', 'vehicles', f'{tid}.json')))
    for k in spec.get('removeClientKeys', []):
        client.pop(k, None)
    out.json(os.path.join(ASSETS, 'sbw', 'vehicles', f'{vid}.json'), client)
    for rel in ('armor', 'flight_reference'):
        path = os.path.join(DATA, rel, f'{tid}.json')
        if os.path.exists(path):
            d = renamed(load(path))
            if 'id' in d:
                d['id'] = vid
            if rel == 'flight_reference':
                d['handlingProfileId'] = load(path)['handlingProfileId']   # handling is shared with the template
                for k, v in spec.get('flightReference', {}).items():
                    d['reference'][k] = v
            out.json(os.path.join(DATA, rel, f'{vid}.json'), d)
    arm = renamed(load(os.path.join(DATA, 'sbw', 'aircraft_armaments', f'{tid}.json')))
    arm['Name'] = spec['name']
    out.json(os.path.join(DATA, 'sbw', 'aircraft_armaments', f'{vid}.json'), arm)
    ms = os.path.join(DATA, 'sbw', 'aircraft_stores', 'modeled_store', tid)
    if os.path.isdir(ms):
        for fn in sorted(os.listdir(ms)):
            d = load(os.path.join(ms, fn))
            d['Name'] = d['Name'].replace(load(os.path.join(DATA, 'sbw', 'aircraft_armaments', f'{tid}.json'))['Name'],
                                          spec['name'])
            out.json(os.path.join(DATA, 'sbw', 'aircraft_stores', 'modeled_store', vid, fn), d)

    # ---- sounds: the template's events are reused by id (data keeps the template's sound ids)
    # ---- registration (entity class, entities, renderers, lang, tab, containers)
    reg = type('R', (), {'id': vid, 'template': tid, 'spec': spec})()
    register_aircraft(reg, out)

    print(f'{vid}: scale {s:.4f} px/unit, offset {r5(off)}, {dropped} baked store elements dropped')
    for bone, els in sorted(assign.items()):
        print(f'  {bone:<40} {len(els)} elements')
    print(('wrote ' if write else 'would write ') + f'{len(out.files)} files')
    for f in out.files:
        print('  ' + os.path.relpath(f, vehgen.REPO))


def register_aircraft(b, out):
    spec = b.spec
    cls = spec['entityClass']
    const, tpl_const = b.id.upper(), b.template.upper()
    J = vehgen.JAVA
    path = os.path.join(J, 'init', 'ModEntities.java')
    src = open(path).read()
    if f'"{b.id}"' not in src:
        m = re.search(rf'( *)public static final RegistryObject<EntityType<(\w+)>> {tpl_const} =\n.*?"{b.template}"\)\);\n',
                      src, re.S)
        if m.group(2) != cls:
            raise SystemExit('aircraft variants reuse the template entity class')
        line = m.group(0).replace(f'> {tpl_const} =', f'> {const} =').replace(f'"{b.template}"', f'"{b.id}"')
        out.text(path, src, src[:m.end()] + line + src[m.end():])
    path = os.path.join(J, 'init', 'ModEntityRenderers.java')
    src = open(path).read()
    if f'"{b.id}"' not in src:
        m = re.search(rf'( *)registerVehicle\(event, ModEntities\.{tpl_const}, "{b.template}", .*?;\n', src, re.S)
        block = m.group(0).replace(f'ModEntities.{tpl_const}', f'ModEntities.{const}').replace(
            f'"{b.template}"', f'"{b.id}"').replace(f'custom_geo/{b.template}.geo.json', f'custom_geo/{b.id}.geo.json') \
            .replace(f'textures/entity/{b.template}.png', f'textures/entity/{b.id}.png')
        out.text(path, src, src[:m.end()] + block + src[m.end():])
    path = os.path.join(ASSETS, 'lang', 'en_us.json')
    src = open(path).read()
    key = f'entity.{NS}.{b.id}'
    if key not in src:
        i = src.index(f'  "entity.{NS}.{b.template}": ')
        j = src.index('\n', i) + 1
        out.text(path, src, src[:j] + f'  "{key}": {json.dumps(spec["name"])},\n' + src[j:])
    for rel, pattern, insert in [
        ('creative_tabs.json', f'"{b.template}",\n', f'"{b.id}",\n'),
        ('sbw/containers/mobile_vehicles.json', f'"{NS}:{b.template}",\n', f'"{NS}:{b.id}",\n'),
    ]:
        path = os.path.join(DATA, rel)
        src = open(path).read()
        if insert.strip() not in src and pattern in src:
            i = src.index(pattern)
            indent = src[src.rfind('\n', 0, i) + 1:i]
            j = i + len(pattern)
            out.text(path, src, src[:j] + indent + insert + src[j:])
    for fn in os.listdir(os.path.join(DATA, 'sbw', 'containers')):
        path = os.path.join(DATA, 'sbw', 'containers', fn)
        src = open(path).read()
        if f'"{NS}:{b.id}"' in src:
            continue
        m = re.search(rf'( *)\{{\n *"Type": "{NS}:{b.template}",\n *"Weight": (\d+)\n *\}},?\n', src)
        if m:
            block = m.group(0).replace(f'{NS}:{b.template}', f'{NS}:{b.id}')
            if not block.rstrip().endswith(','):
                block = block.rstrip('\n') + ',\n'
            out.text(path, src, src[:m.start()] + block + src[m.start():])


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
