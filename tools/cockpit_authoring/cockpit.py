#!/usr/bin/env python3
"""Cockpit authoring models for Blockbench, and the way back into the game.

export  Cuts each aircraft's cockpit section out of its visual model (custom_geo/<id>.geo.json) into a native
        Blockbench project (<id>.cockpit.bbmodel, Generic Model format, Mesh elements, the aircraft texture embedded,
        no plugin needed) in the same model-pixel frame as the full model, so the cockpit sits exactly where it sits
        on the aircraft (Blockbench shows it the way it shows the Bedrock model: x mirrored). Next to it:
        <id>.cockpit.manifest.json (which faces were cut, so the import can put the edited cockpit back in their
        place).

        Bones in the file:
          <original bone names>   the cockpit faces, still in the bones they came from (parents kept, empty)
          ref_context             the airframe around the cockpit, for orientation only (never imported)
          displays/display__s<seat>__<KIND>__<n>
                                  one flight display each: a square screen (4-corner face, facing the crew) with a
                                  small triangle marking the screen's top edge. Move/rotate/scale the bone or its
                                  vertices to place it. Screens stay square: the width is the mean edge length.
          gauges/gauge__s<seat>__<KIND>__<n>
                                  one round gauge each: a square as wide as the dial, with the same top marker
          unused_display__s<seat>__<KIND>
                                  a spare display parked beside the cockpit (inert until renamed display__...)

        Output folders: <out>/jets, <out>/props, <out>/helis.

import  Reads an edited <id>.cockpit.bbmodel (or an older <id>.cockpit.geo.json): the faces it holds replace the faces that were cut from the model
        (added and deleted faces included, new bones are added under their parent), the display__/gauge__ bones
        become the aircraft's FlightDisplays / CockpitGauges (kind, seat and count from the bone names; depth and
        style kept from the display they replace).

Frames: model pixels (x left, y up, z aft) <-> vehicle-local blocks (x left, y up, z forward) = (x, y, -z) / 16.
Usage:
  python3 tools/cockpit_authoring/cockpit.py export OUT_DIR [id...]
  python3 tools/cockpit_authoring/cockpit.py import FILE.cockpit.bbmodel [--dry-run]
"""
import base64, hashlib, json, math, os, re, shutil, sys, uuid
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources')
GEO = os.path.join(GEN, 'assets/berts_vehicle_pack/custom_geo')
ASSET = os.path.join(GEN, 'assets/berts_vehicle_pack/sbw/vehicles')
DATA = os.path.join(GEN, 'data/berts_vehicle_pack/sbw/vehicles')
TEX = os.path.join(GEN, 'assets/berts_vehicle_pack/textures/entity')

JETS_OVERRIDE = {'il_76m'}                 # turbofan airliner-type jets whose models carry blade-named bones
DEFAULT_DEPTH = 0.035
# moving or external parts that pass through the cockpit box but are not cockpit (rotors, sensor turrets, guns,
# control surfaces, gear): never cut
NOT_COCKPIT = re.compile(r'rotor|prop|blade|spinner|weapon|gun|barrel|barell|turret|missile|rocket|pylon|gear|wheel|'
                         r'flap|aileron|elevator|rudder|slat|airbrake|intake_ramp|refuel|probe', re.I)
LIFT = 0.002                               # markers float this far in front of the instrument
MARK = 0.18                                # top marker size, share of the screen width


# ---------------------------------------------------------------- frames

def to_px(p):
    return [round(float(p[0]) * 16, 4), round(float(p[1]) * 16, 4), round(-float(p[2]) * 16, 4)]


def to_vl(p):
    return np.array([p[0] / 16.0, p[1] / 16.0, -p[2] / 16.0])


def r5(v):
    return [round(float(x), 5) + 0.0 for x in v]


# ---------------------------------------------------------------- inputs

def load(vid):
    geo = json.load(open(os.path.join(GEO, vid + '.geo.json')))
    data = json.load(open(os.path.join(DATA, vid + '.json')))
    asset = json.load(open(os.path.join(ASSET, vid + '.json')))
    return geo, data, asset


def category(vid, data, geo):
    if data.get('Type') == 'Helicopter':
        return 'helis'
    if vid in JETS_OVERRIDE:
        return 'jets'
    names = [b['name'].lower() for b in geo['minecraft:geometry'][0]['bones']]
    return 'props' if any(re.search(r'prop|rotor|blade|spinner', n) for n in names) else 'jets'


def eyes(data):
    att = data.get('Attachments', {})
    out = {}
    for k, seat in enumerate(data.get('Seats', [])):
        name = (seat.get('CameraPos') or {}).get('EyeAttachment')
        if name and name in att:
            out[k] = np.array(att[name]['Position'], float)
        elif 'Position' in seat:
            out[k] = np.array(seat['Position'], float) + np.array([0, 1.5, 0])
    return out


# models whose seat eye attachments do not sit in the cockpit (the cockpit found by eye on the model): vehicle-local
# (lo, hi) boxes
BOX_OVERRIDE = {
    'ah1w_super_cobra': ([-1.0, 0.6, 3.0], [1.0, 4.0, 7.6]),
    'mi24_hind_a': ([-1.3, 0.6, 5.3], [1.3, 4.0, 10.2]),
}


def cockpit_box(data, asset, vid=None):
    if vid in BOX_OVERRIDE:
        lo, hi = BOX_OVERRIDE[vid]
        return np.array(lo, float), np.array(hi, float)
    """Vehicle-local box (lo, hi) around the crew stations: the canopy glass with a margin where there is one,
    else the eyes, and always every display and gauge."""
    E = eyes(data)
    # crew stations: seats with an eye attachment near the front half of the vehicle (passenger benches of a
    # transport are left out when they are far from the pilots)
    pts = np.array(list(E.values())) if E else np.zeros((1, 3))
    pilot = pts[0]
    pts = pts[np.linalg.norm(pts - pilot, axis=1) < 4.0]
    lo = pts.min(0) + np.array([-1.0, -1.6, -1.3])
    hi = pts.max(0) + np.array([1.0, 0.9, 1.6])
    glass = asset.get('CanopyGlass')
    if glass and glass.get('Triangles'):
        g = np.array(glass['Triangles'], float).reshape(-1, 3)
        lo = np.minimum(lo, g.min(0) + np.array([-0.5, -1.2, -0.6]))
        hi = np.maximum(hi, g.max(0) + np.array([0.5, 0.35, 0.9]))
        lo[2] = max(lo[2], g[:, 2].min() - 0.6)
        hi[2] = min(hi[2], g[:, 2].max() + 0.9) if g[:, 2].max() + 0.9 > pts[:, 2].max() + 0.4 else hi[2]
    lo[0] = min(lo[0], -0.9); hi[0] = max(hi[0], 0.9)
    for key, items in (('FlightDisplays', 'Displays'), ('CockpitGauges', 'Gauges')):
        for d in (asset.get(key) or {}).get(items, []):
            c = np.array(d['Center'], float)
            lo = np.minimum(lo, c - 0.3); hi = np.maximum(hi, c + 0.3)
    return lo, hi


# ---------------------------------------------------------------- export

def poly_centroid(pm, poly):
    pos = pm['positions']
    return to_vl(np.mean([pos[v[0]] for v in poly], axis=0))


def sub_mesh(pm, polys):
    """A poly_mesh holding only [polys], re-indexed."""
    pmap, nmap, umap = {}, {}, {}
    P, N, U, out = [], [], [], []
    for poly in polys:
        q = []
        for v in poly:
            a = pmap.setdefault(v[0], len(pmap))
            if a == len(P): P.append(pm['positions'][v[0]])
            b = nmap.setdefault(v[1], len(nmap)) if len(v) > 1 else 0
            if len(v) > 1 and b == len(N): N.append(pm['normals'][v[1]])
            c = umap.setdefault(v[2], len(umap)) if len(v) > 2 else 0
            if len(v) > 2 and c == len(U): U.append(pm['uvs'][v[2]])
            q.append([a, b, c])
        out.append(q)
    return {'normalized_uvs': pm.get('normalized_uvs', True), 'positions': P, 'normals': N or [[0, 1, 0]],
            'uvs': U or [[0, 0]], 'polys': out}


def frame(centre, normal, up):
    n = np.array(normal, float); n /= np.linalg.norm(n)
    u = np.array(up, float); u = u - n * u.dot(n); u /= np.linalg.norm(u)
    r = np.cross(u, n); r /= np.linalg.norm(r)
    return np.array(centre, float), n, u, r


def marker_mesh(centre, normal, up, half, outline):
    """poly_mesh (model px) of a flat instrument: [outline] = corners as (s, t) multiples of half along (right, up),
    wound to face along the normal, plus a small triangle standing on the top edge (the screen's up)."""
    c, n, u, r = frame(centre, normal, up)
    lift = n * LIFT
    pts = [c + r * (s * half) + u * (t * half) + lift for s, t in outline]
    m = MARK * 2 * half
    top = c + u * half + lift * 2
    tri = [top + r * (m * 0.5) + u * (m * 0.15), top - r * (m * 0.5) + u * (m * 0.15), top + u * (m * 1.0)]
    # winding: counter-clockwise seen from the crew side (along -n) keeps the normal on the crew side
    def wound(poly):
        a, b, cc = poly[0], poly[1], poly[2]
        return poly if np.cross(b - a, cc - a).dot(n) > 0 else poly[::-1]
    faces = [wound(pts), wound(tri)]
    P, polys = [], []
    nn = to_px(n); nn = [nn[0] / 16, nn[1] / 16, nn[2] / 16]
    for f in faces:
        idx = []
        for p in f:
            idx.append([len(P), 0, 0]); P.append(to_px(p))
        polys.append(idx)
    return {'normalized_uvs': True, 'positions': P, 'normals': [r5(nn)], 'uvs': [[0, 0]], 'polys': polys}


SQUARE = [(-1, -1), (1, -1), (1, 1), (-1, 1)]


def export(vid, out_root):
    geo, data, asset = load(vid)
    cat = category(vid, data, geo)
    out = os.path.join(out_root, cat); os.makedirs(out, exist_ok=True)
    g = geo['minecraft:geometry'][0]
    bones = g['bones']
    by_name = {b['name']: b for b in bones}
    lo, hi = cockpit_box(data, asset, vid)
    clo, chi = lo - 1.5, hi + 1.5
    inside = lambda p: bool(np.all(p >= lo) and np.all(p <= hi))
    near = lambda p: bool(np.all(p >= clo) and np.all(p <= chi))
    cut, keep_bones, context = {}, set(), []
    context_pm = {'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    for b in bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        editable = not NOT_COCKPIT.search(b['name'].split('__')[-1])
        sel, ctx = [], []
        for i, poly in enumerate(pm['polys']):
            c = poly_centroid(pm, poly)
            if editable and inside(c):
                sel.append(i)
            elif near(c):
                ctx.append(poly)
        if sel:
            cut[b['name']] = sel
            n = b['name']
            while n:
                keep_bones.add(n); n = by_name[n].get('parent')
        if ctx:
            context.append(sub_mesh(pm, ctx))
    out_bones = []
    for b in bones:                                  # original order keeps parents ahead of children
        if b['name'] not in keep_bones:
            continue
        nb = {k: b[k] for k in ('name', 'parent', 'pivot', 'rotation') if k in b}
        if b['name'] in cut:
            pm = b['poly_mesh']
            nb['poly_mesh'] = sub_mesh(pm, [pm['polys'][i] for i in cut[b['name']]])
        out_bones.append(nb)
    # context: one bone, all nearby airframe faces merged
    if context:
        P, N, U, polys = [], [], [], []
        for m in context:
            po, no, uo = len(P), len(N), len(U)
            P += m['positions']; N += m['normals']; U += m['uvs']
            polys += [[[v[0] + po, v[1] + no, v[2] + uo] for v in poly] for poly in m['polys']]
        out_bones.append({'name': 'ref_context', 'pivot': [0, 0, 0],
                          'poly_mesh': {'normalized_uvs': True, 'positions': P, 'normals': N, 'uvs': U, 'polys': polys}})
    # instruments
    displays = (asset.get('FlightDisplays') or {}).get('Displays', [])
    gauges = (asset.get('CockpitGauges') or {}).get('Gauges', [])
    out_bones.append({'name': 'displays', 'pivot': [0, 0, 0]})
    count = {}
    for d in displays:
        key = (d.get('Seat', 0), d.get('Kind', 'PFD'))
        count[key] = count.get(key, 0) + 1
        name = f"display__s{key[0]}__{key[1]}__{count[key]}"
        c = to_px(d['Center'])
        out_bones.append({'name': name, 'parent': 'displays', 'pivot': c,
                          'poly_mesh': px_mesh(d['Center'], d['Normal'], d['Up'], d['Width'] / 2, SQUARE)})
    out_bones.append({'name': 'gauges', 'pivot': [0, 0, 0]})
    count = {}
    for d in gauges:
        key = (d.get('Seat', 0), d.get('Kind', 'GAUGE'))
        count[key] = count.get(key, 0) + 1
        name = f"gauge__s{key[0]}__{key[1]}__{count[key]}"
        out_bones.append({'name': name, 'parent': 'gauges', 'pivot': to_px(d['Center']),
                          'poly_mesh': px_mesh(d['Center'], d['Normal'], d['Up'], d['Diameter'] / 2, SQUARE)})
    # a spare display per crew seat, parked outside the left side of the cockpit, facing left
    E = eyes(data)
    width = displays[0]['Width'] if displays else 0.15
    crew = [k for k, s in enumerate(data.get('Seats', [])) if (s.get('CameraPos') or {}).get('EyeAttachment')][:2] or [0]
    for k in crew:
        e = E.get(k, (lo + hi) / 2)
        if vid in BOX_OVERRIDE or not np.all((e >= lo) & (e <= hi)):
            e = (lo + hi) / 2 + np.array([0, 0.4, 0.4 * (1 - 2 * crew.index(k))])
        c = np.array([hi[0] + 0.4, e[1] - 0.3, e[2] + 0.5])
        out_bones.append({'name': f'unused_display__s{k}__PFD', 'pivot': to_px(c),
                          'poly_mesh': px_mesh(c, [1, 0, 0], [0, 1, 0], width / 2, SQUARE)})
    desc = dict(g['description']); desc['identifier'] = f'geometry.{vid}_cockpit'
    model = {'format_version': '1.12.0', 'minecraft:geometry': [{'description': desc, 'bones': out_bones}]}
    base = os.path.join(out, vid)
    tex = os.path.join(TEX, vid + '.png')
    with open(base + '.cockpit.bbmodel', 'w') as f:
        json.dump(to_bbmodel(model, vid, tex if os.path.exists(tex) else None), f, separators=(',', ':'))
    manifest = {'vehicle': vid, 'category': cat, 'box_vehicle_local': [r5(lo), r5(hi)], 'cut': cut,
                'displays': displays, 'gauges': gauges,
                'source_geo_sha': sha(os.path.join(GEO, vid + '.geo.json'))}
    with open(base + '.cockpit.manifest.json', 'w') as f:
        json.dump(manifest, f, indent=1)
    faces = sum(len(v) for v in cut.values())
    return cat, faces, len(displays), len(gauges)


def px_mesh(centre, normal, up, half, outline):
    """marker_mesh in vehicle-local blocks, converted to model pixels."""
    m = marker_mesh(centre, normal, up, half, outline)
    return m


def sha(path):
    import hashlib
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()



# ---------------------------------------------------------------- Blockbench project (.bbmodel)
#
# Blockbench shows a Bedrock model with x mirrored (its bedrock codec negates x of pivots and positions), so the
# project uses bb = (-x, y, z) of model pixels, and the mirror flips every face's winding. Faces are front-facing
# counter-clockwise in both frames (the model's normals agree with cross(v1-v0, v2-v0)); the reversal restores that.
# Mesh vertices are relative to the Mesh's origin (its rotation pivot), which is the bone pivot. UVs: poly_mesh
# normalized with v up from the bottom; Blockbench texture pixels with v down from the top.

BB_FORMAT = '4.10'


def _uid(*parts):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'bvp-cockpit/' + '/'.join(str(p) for p in parts)))


def _bbv(p):
    return [-float(p[0]), float(p[1]), float(p[2])]


def to_bbmodel(model, vid, tex_path):
    g = model['minecraft:geometry'][0]
    desc = g['description']
    if tex_path:
        from PIL import Image
        tw, th = Image.open(tex_path).size
        src = 'data:image/png;base64,' + base64.b64encode(open(tex_path, 'rb').read()).decode()
    else:
        tw, th, src = desc.get('texture_width', 64), desc.get('texture_height', 64), None
    elements, groups = [], {}
    for b in g['bones']:
        name = b['name']
        locked = name.startswith('ref_')
        pivot = _bbv(b.get('pivot', [0, 0, 0]))
        rot = b.get('rotation', [0, 0, 0])
        grp = {'name': name, 'origin': pivot, 'color': 0, 'uuid': _uid(vid, 'g', name), 'export': True,
               'mirror_uv': False, 'isOpen': name in ('displays', 'gauges'), 'locked': locked, 'visibility': True,
               'autouv': 0, 'children': []}
        if any(abs(x) > 1e-9 for x in rot):
            grp['rotation'] = [-float(rot[0]), -float(rot[1]), float(rot[2])]
        groups[name] = grp
        pm = b.get('poly_mesh')
        if pm and pm.get('polys'):
            el = mesh_element(pm, pivot, vid, name, tw, th, locked,
                              'screen' if name.startswith(('display', 'unused_display')) else
                              'dial' if name.startswith('gauge') else 'context' if locked else 'faces')
            elements.append(el)
            grp['children'].append(el['uuid'])
    outliner = []
    for b in g['bones']:                      # parents precede children in the exported bone order
        grp, parent = groups[b['name']], b.get('parent')
        (groups[parent]['children'] if parent in groups else outliner).append(grp)
    tex = {'path': '', 'name': vid + '.png', 'folder': '', 'namespace': '', 'id': '0', 'width': tw, 'height': th,
           'uv_width': tw, 'uv_height': th, 'particle': False, 'use_as_default': True, 'layers_enabled': False,
           'sync_to_project': '', 'render_mode': 'default', 'render_sides': 'double', 'frame_time': 1,
           'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False, 'visible': True,
           'internal': True, 'saved': False, 'uuid': _uid(vid, 'texture')}
    if src:
        tex['source'] = src
    return {'meta': {'format_version': BB_FORMAT, 'model_format': 'free', 'box_uv': False},
            'name': vid + '_cockpit', 'model_identifier': '', 'visible_box': [1, 1, 0],
            'variable_placeholders': '', 'variable_placeholder_buttons': [], 'timeline_setups': [],
            'unhandled_root_fields': {}, 'resolution': {'width': tw, 'height': th},
            'elements': elements, 'outliner': outliner, 'textures': [tex] if src else []}


def mesh_element(pm, origin, vid, bone, tw, th, locked, label):
    P = pm['positions']; U = pm.get('uvs') or [[0, 0]]
    norm = pm.get('normalized_uvs', True)
    verts, faces, used = {}, {}, {}
    for fi, poly in enumerate(pm['polys']):
        keys, seen = [], set()
        for v in reversed(poly):              # mirror: reverse the winding
            if v[0] in seen:
                continue
            seen.add(v[0])
            k = used.get(v[0])
            if k is None:
                k = used[v[0]] = 'v%d' % len(used)
                p = _bbv(P[v[0]])
                verts[k] = [round(p[i] - origin[i], 6) + 0.0 for i in range(3)]
            uv = U[v[2]] if len(v) > 2 and v[2] < len(U) else [0, 0]
            keys.append((k, [round(uv[0] * tw if norm else uv[0], 5), round((1 - uv[1]) * th if norm else th - uv[1], 5)]))
        if len(keys) < 3:
            continue
        faces['f%d' % fi] = {'uv': {k: uv for k, uv in keys}, 'vertices': [k for k, _ in keys], 'texture': 0}
    return {'name': label, 'color': 0, 'origin': list(origin), 'rotation': [0, 0, 0], 'export': True,
            'visibility': True, 'locked': locked, 'render_order': 'default', 'allow_mirror_modeling': True,
            'vertices': verts, 'faces': faces, 'type': 'mesh', 'uuid': _uid(vid, 'm', bone)}


def _rot_zyx(deg):
    x, y, z = (math.radians(float(a)) for a in deg)
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rz @ ry @ rx                        # Blockbench / three.js Euler order 'ZYX'


def _face_key(pts):
    """Corners in winding order, starting at the smallest: a double-sided panel's two faces differ."""
    q = [tuple(round(float(c), 3) for c in p) for p in pts]
    q = [c for i, c in enumerate(q) if c != q[i - 1]] or q
    i = q.index(min(q))
    return tuple(q[i:] + q[:i])


def from_bbmodel(bb, original_normals=None):
    """A Bedrock geometry dict (bones with poly_mesh in model pixels) from a Blockbench project. Groups are bones;
    every Mesh directly in a group becomes that bone's faces (a Mesh's own rotation is baked in, a group's rotation
    is kept as the bone rotation). Meshes outside any group go to 'hull'. Faces whose corners match a face of the
    exported model keep its normals; others get a flat normal."""
    res = bb.get('resolution') or {}
    texs = bb.get('textures') or []
    tw = float((texs[0].get('uv_width') or texs[0].get('width')) if texs else res.get('width', 64))
    th = float((texs[0].get('uv_height') or texs[0].get('height')) if texs else res.get('height', 64))
    els = {e['uuid']: e for e in bb.get('elements', [])}
    bones = []

    def add_mesh(pm, el):
        if el.get('type') != 'mesh':
            return
        R = _rot_zyx(el.get('rotation', [0, 0, 0]))
        o = np.array(el.get('origin', [0, 0, 0]), float)
        vmap = {}
        for k, p in el.get('vertices', {}).items():
            w = o + R @ np.array(p, float)
            vmap[k] = [round(-float(w[0]), 6) + 0.0, round(float(w[1]), 6) + 0.0, round(float(w[2]), 6) + 0.0]
        for f in el.get('faces', {}).values():
            ks = [k for k in f.get('vertices', []) if k in vmap]
            if len(ks) < 3:
                continue
            ks = ks[::-1]                      # un-mirror the winding
            if len(ks) == 4:                   # Blockbench keeps quads in any order; restore a non-twisted ring
                ks = _ring(ks, vmap)
            pts = [np.array(vmap[k]) for k in ks]
            key = _face_key(pts)
            nrm = (original_normals or {}).get(key)
            if nrm is None:
                n = np.cross(pts[1] - pts[0], pts[2] - pts[0])
                if len(pts) == 4:
                    n = n + np.cross(pts[2] - pts[0], pts[3] - pts[0])
                ln = np.linalg.norm(n)
                n = n / ln if ln > 1e-12 else np.array([0, 1.0, 0])
                nrm = {tuple(round(float(c), 3) for c in p): r5(n) for p in pts}
            poly = []
            for k, p in zip(ks, pts):
                uv = (f.get('uv') or {}).get(k, [0, 0])
                pi = len(pm['positions']); pm['positions'].append(vmap[k])
                ni = len(pm['normals']); pm['normals'].append(nrm.get(tuple(round(float(c), 3) for c in p), r5([0, 1, 0])))
                ui = len(pm['uvs']); pm['uvs'].append([round(uv[0] / tw, 6), round(1 - uv[1] / th, 6)])
                poly.append([pi, ni, ui])
            pm['polys'].append(poly)

    def walk(node, parent):
        pm = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        o = node.get('origin', [0, 0, 0])
        b = {'name': node['name'], 'pivot': [-float(o[0]) + 0.0, float(o[1]), float(o[2])]}
        if parent:
            b['parent'] = parent
        rot = node.get('rotation') or [0, 0, 0]
        if any(abs(x) > 1e-9 for x in rot):
            b['rotation'] = [-float(rot[0]) + 0.0, -float(rot[1]) + 0.0, float(rot[2])]
        bones.append(b)
        for c in node.get('children', []):
            if isinstance(c, dict):
                walk(c, node['name'])
            elif c in els:
                add_mesh(pm, els[c])
        if pm['polys']:
            b['poly_mesh'] = pm

    loose = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
    for node in bb.get('outliner', []):
        if isinstance(node, dict):
            walk(node, None)
        elif node in els:
            add_mesh(loose, els[node])
    if loose['polys']:
        hull = next((b for b in bones if b['name'] == 'hull'), None)
        if hull is None:
            hull = {'name': 'hull', 'pivot': [0, 0, 0]}; bones.insert(0, hull)
        pm = hull.setdefault('poly_mesh', {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []})
        po, no, uo = len(pm['positions']), len(pm['normals']), len(pm['uvs'])
        pm['positions'] += loose['positions']; pm['normals'] += loose['normals']; pm['uvs'] += loose['uvs']
        pm['polys'] += [[[v[0] + po, v[1] + no, v[2] + uo] for v in p] for p in loose['polys']]
    return {'format_version': '1.12.0', 'minecraft:geometry': [{'description': {}, 'bones': bones}]}


def _ring(ks, vmap):
    """The order of a quad's corners that goes around its edge (the diagonal pair never adjacent), keeping ks[0],
    ks[1] and the winding direction when that order already is a ring."""
    P = {k: np.array(vmap[k]) for k in ks}
    def twisted(o):
        a, b, c, d = (P[k] for k in o)
        n1 = np.cross(b - a, c - a); n2 = np.cross(c - a, d - a)
        return n1 @ n2 < 0
    if not twisted(ks):
        return ks
    for o in ([ks[0], ks[1], ks[3], ks[2]], [ks[0], ks[2], ks[1], ks[3]]):
        if not twisted(o):
            return o
    return ks


def original_face_normals(manifest):
    """{face key: {corner: normal}} for every face cut from the game model, so unchanged faces keep their normals."""
    geo = json.load(open(os.path.join(GEO, manifest['vehicle'] + '.geo.json')))
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    out = {}
    for name, idx in manifest.get('cut', {}).items():
        pm = (bones.get(name) or {}).get('poly_mesh')
        if not pm:
            continue
        for i in idx:
            if i >= len(pm['polys']):
                continue
            poly = pm['polys'][i]
            pts = [pm['positions'][v[0]] for v in poly]
            out[_face_key(pts)] = {tuple(round(float(c), 3) for c in pm['positions'][v[0]]):
                                   pm['normals'][v[1]] if len(v) > 1 and v[1] < len(pm['normals']) else [0, 1, 0]
                                   for v in poly}
    return out


def load_edited(path, manifest=None):
    data = json.load(open(path))
    if path.endswith('.bbmodel') or 'meta' in data:
        try:
            normals = original_face_normals(manifest) if manifest else None
        except (OSError, KeyError, ValueError):
            normals = None
        return from_bbmodel(data, normals)
    return data


# ---------------------------------------------------------------- import

NAME = re.compile(r'^(display|gauge)__s(\d+)__([A-Za-z0-9_]+?)__(\d+)$')


def read_marker(pm):
    """(centre, normal, up, half) in vehicle-local blocks from an instrument bone's faces: the face with the most
    corners is the screen/dial, the triangle marks its top edge."""
    P = [np.array(p, float) for p in pm['positions']]
    polys = sorted(pm['polys'], key=len, reverse=True)
    face = [to_vl(P[v[0]]) for v in polys[0]]
    c = np.mean(face, axis=0)
    n = np.cross(face[1] - face[0], face[2] - face[0])
    for k in range(2, len(face) - 1):
        n = n + np.cross(face[k] - face[0], face[k + 1] - face[0])
    n /= np.linalg.norm(n)
    c = c - n * LIFT                       # the marker floats LIFT off the instrument's true plane
    edges = [np.linalg.norm(face[(k + 1) % len(face)] - face[k]) for k in range(len(face))]
    half = float(np.mean(edges)) / 2
    up = None
    if len(polys) > 1:
        t = np.mean([to_vl(P[v[0]]) for v in polys[1]], axis=0)
        up = t - c; up = up - n * up.dot(n)
    if up is None or np.linalg.norm(up) < 1e-6:
        up = np.array([0, 1.0, 0]) - n * n[1]
    up /= np.linalg.norm(up)
    return c, n, up, half


def import_file(path, dry=False):
    if path.endswith('.bbmodel'):
        base = path[:-len('.cockpit.bbmodel')]
    else:
        base = path[:-len('.cockpit.geo.json')]
    manifest_path = base + '.cockpit.manifest.json'
    edited = load_edited(path, json.load(open(manifest_path)) if os.path.exists(manifest_path) else None)
    manifest = json.load(open(manifest_path))
    vid = manifest['vehicle']
    geo_path = os.path.join(GEO, vid + '.geo.json')
    if sha(geo_path) != manifest['source_geo_sha']:
        print(f'{vid}: the game model changed since this cockpit file was exported; importing onto the current model '
              f'(faces are matched by bone and index, re-export if bones were renamed)')
    geo = json.load(open(geo_path))
    g = geo['minecraft:geometry'][0]
    bones = {b['name']: b for b in g['bones']}
    ebones = edited['minecraft:geometry'][0]['bones']
    # 1. remove the faces that were cut
    for name, idx in manifest['cut'].items():
        b = bones.get(name)
        if not b:
            continue
        drop = set(idx)
        b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if i not in drop]
    # 2. put the edited faces back into their bones (new bones added)
    added_faces = 0
    for eb in ebones:
        name = eb['name']
        if name.startswith(('ref_', 'display', 'gauge', 'unused_')) or name in ('displays', 'gauges'):
            continue
        pm = eb.get('poly_mesh')
        if name not in bones:
            nb = {k: eb[k] for k in ('name', 'parent', 'pivot', 'rotation') if k in eb}
            if nb.get('parent') not in bones:
                nb['parent'] = 'hull' if 'hull' in bones else None
                if nb['parent'] is None: nb.pop('parent')
            g['bones'].append(nb); bones[name] = nb
        if not pm or not pm.get('polys'):
            continue
        b = bones[name]
        tm = b.setdefault('poly_mesh', {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []})
        po, no, uo = len(tm['positions']), len(tm['normals']), len(tm['uvs'])
        tm['positions'] += pm['positions']; tm['normals'] += pm.get('normals', [[0, 1, 0]])
        tm['uvs'] += pm.get('uvs', [[0, 0]])
        for poly in pm['polys']:
            tm['polys'].append([[v[0] + po, (v[1] if len(v) > 1 else 0) + no, (v[2] if len(v) > 2 else 0) + uo]
                                for v in poly])
            added_faces += 1
    # bone rotations are not baked into poly_mesh positions: rotate the Mesh element, not its group
    orig_rot = {b['name']: b.get('rotation', [0, 0, 0]) for b in g['bones']}
    for eb in ebones:
        rot = eb.get('rotation', [0, 0, 0])
        if any(abs(x) > 1e-6 for x in rot) and rot != orig_rot.get(eb['name'], [0, 0, 0]):
            print(f"{vid}: WARNING bone {eb['name']} was rotated as a group ({rot}); the rotation is ignored. "
                  f"Rotate the Mesh element inside it instead.")
    # 3. instruments
    old_d = {}
    for d in manifest['displays']:
        old_d.setdefault((d.get('Seat', 0), d.get('Kind', 'PFD')), []).append(d)
    old_g = {}
    for d in manifest['gauges']:
        old_g.setdefault((d.get('Seat', 0), d.get('Kind', 'GAUGE')), []).append(d)
    displays, gauges = [], []
    for eb in ebones:
        m = NAME.match(eb['name'])
        if not m or not eb.get('poly_mesh') or not eb['poly_mesh'].get('polys'):
            continue
        what, seat, kind, n = m.group(1), int(m.group(2)), m.group(3).upper(), int(m.group(4))
        c, nrm, up, half = read_marker(eb['poly_mesh'])
        if what == 'display':
            prev = (old_d.get((seat, kind)) or [{}])[min(n, len(old_d.get((seat, kind)) or [{}])) - 1]
            d = {'Seat': seat, 'Center': r5(c), 'Normal': r5(nrm), 'Up': r5(up), 'Width': round(2 * half, 4),
                 'Depth': prev.get('Depth', DEFAULT_DEPTH), 'Kind': kind}
            for k, v in prev.items():
                if k not in d: d[k] = v
            d.setdefault('Style', next((x.get('Style') for x in manifest['displays'] if x.get('Style')), 'LCD'))
            displays.append(d)
        else:
            prev = (old_g.get((seat, kind)) or [{}])[min(n, len(old_g.get((seat, kind)) or [{}])) - 1]
            d = {'Seat': seat, 'Kind': kind, 'Center': r5(c), 'Normal': r5(nrm), 'Up': r5(up),
                 'Diameter': round(2 * half, 4), 'Depth': prev.get('Depth', 0.03)}
            gauges.append(d)
    asset_path = os.path.join(ASSET, vid + '.json')
    asset = json.load(open(asset_path))
    if displays or manifest['displays']:
        fd = asset.setdefault('FlightDisplays', {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS'})
        fd['Displays'] = displays
        if not displays:
            asset.pop('FlightDisplays')
    if gauges or manifest['gauges']:
        cg = asset.setdefault('CockpitGauges', {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS'})
        cg['Gauges'] = gauges
        if not gauges:
            asset.pop('CockpitGauges')
    print(f'{vid}: {sum(len(v) for v in manifest["cut"].values())} cockpit faces replaced by {added_faces}; '
          f'{len(displays)} displays, {len(gauges)} gauges')
    if dry:
        return
    with open(geo_path, 'w') as f:
        json.dump(geo, f, separators=(',', ':'))
    with open(asset_path, 'w') as f:
        json.dump(asset, f, indent=2)
    # the manifest now describes the model as imported: re-export before editing again
    print(f'{vid}: written. Re-export before the next round of edits (face indices changed).')


def aircraft():
    out = []
    for f in sorted(os.listdir(DATA)):
        d = json.load(open(os.path.join(DATA, f)))
        if d.get('Type') in ('Airplane', 'Helicopter') and os.path.exists(os.path.join(GEO, f[:-5] + '.geo.json')):
            out.append(f[:-5])
    return out


def main(argv):
    if len(argv) >= 2 and argv[0] == 'export':
        out = argv[1]; ids = argv[2:] or aircraft()
        for vid in ids:
            cat, faces, nd, ng = export(vid, out)
            print(f'{vid:34s} {cat:6s} {faces:6d} cockpit faces  {nd} displays  {ng} gauges', flush=True)
    elif len(argv) >= 2 and argv[0] == 'import':
        import_file(argv[1], '--dry-run' in argv)
    else:
        print(__doc__)


if __name__ == '__main__':
    main(sys.argv[1:])
