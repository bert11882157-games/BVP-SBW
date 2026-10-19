#!/usr/bin/env python3
"""Cockpit-and-canopy rebuild, in three steps from the aircraft's original model (git ref, default origin/main):

1. Cut. A defined volume is cut out, and only faces lying wholly inside it go:
     * the cockpit well: between the rails (inside the canopy's width at the sill), from the rear bulkhead to the
       windscreen base, from below the floor up: the old interior (seat, panel, consoles, glareshield, sight);
     * the old canopy above the rails: parts standing wholly above the rail line inside the canopy's footprint (a
       hoop, frame bars, mirrors), and faces with a see-through texture there (the model's own glass);
     * all of the old CanopyGlass.
   Faces crossing the volume's boundary (fuselage skin, the rails, the deck under the windscreen) stay. The cut is
   checked: every removed face is reported with the rule that took it, and none may have a corner outside the volume.
2. Canopy (canopy.py): the new canopy is lofted from the old glass's footprint into the clean opening.
3. Cockpit (cockpit_models/cockpit_lib.py): a modelled cockpit is built under the new canopy.

Usage: python3 tools/canopy_models/rebuild.py [--ref=origin/main] [--dry-run] id...
"""
import json, os, subprocess, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import canopy as C  # noqa: E402
L, S = C.L, C.S
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
import fleet as F  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
REL = 'bvp/src/generated/resources/assets/berts_vehicle_pack/'


def git_json(ref, path):
    git = os.environ.get('BVP_GIT', 'git')           # git need not be on PATH (BVP_GIT names it)
    out = subprocess.run([git, '-C', ROOT, 'show', f'{ref}:{path}'], capture_output=True, check=True).stdout
    return json.loads(out.decode('utf-8'))


def load(vid, ref):
    ac = L.Aircraft(vid)
    ac.geo = git_json(ref, f'{REL}custom_geo/{vid}.geo.json')
    ac.asset = git_json(ref, f'{REL}sbw/vehicles/{vid}.json')
    ac.bones = ac.geo['minecraft:geometry'][0]['bones']
    cg = ac.asset.get('CanopyGlass')
    ac.glass = np.array(cg['Triangles'], float).reshape(-1, 3, 3) if cg else np.zeros((0, 3, 3))
    return ac


class Volume:
    """the cut: the cockpit well and the old canopy's footprint above the rails"""

    def __init__(self, tub, glass, spec):
        E = tub.E
        self.x0 = E[0]
        self.half = spec.get('well_half_width', tub.Wg - 0.01)
        self.z0 = E[2] + spec.get('well_rear', tub.back_z - E[2] - 0.08)
        self.z1 = E[2] + spec.get('well_front', tub.ws_z - E[2] + 0.02)
        self.floor = tub.floor_y - 0.3
        self.rail = tub.sill + spec.get('rail_lift', 0.02)
        G = glass.reshape(-1, 3)
        self.glo, self.ghi = G.min(0) - 0.08, G.max(0) + 0.08

    def in_well(self, V):
        return bool(np.all(np.abs(V[:, 0] - self.x0) < self.half) and np.all(V[:, 2] > self.z0) and
                    np.all(V[:, 2] < self.z1) and np.all(V[:, 1] > self.floor))

    def in_footprint(self, V):
        """per corner: inside the canopy's plan outline (any height)"""
        return ((V[:, 0] > self.glo[0]) & (V[:, 0] < self.ghi[0]) & (V[:, 2] > self.glo[2]) & (V[:, 2] < self.ghi[2]))

    def in_canopy(self, V):
        return bool(np.all(V[:, 1] > self.rail) and self.in_footprint(V).all())


def cut(ac, vol, tex, tub, glass):
    """{bone: set(poly)} removed, and {rule: count}"""
    out, why = {}, {'well': 0, 'canopy part': 0, 'old glass face': 0}
    canopy_parts = set()
    for b in ac.bones:
        if not L.is_interior_bone(b['name']):
            continue
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float)
        polys = pm['polys']
        verts = [P[[v[0] for v in p]] for p in polys]
        sel = set()
        for i, V in enumerate(verts):
            if vol.in_well(V):
                sel.add(i); why['well'] += 1
            elif vol.in_canopy(V) and S._see_through(tex, U[[v[2] for v in polys[i]]]):
                sel.add(i); why['old glass face'] += 1
        # connected parts standing wholly above the rails in the canopy's footprint: hoops, frame bars, mirrors
        parent = {}
        def root(x):
            while parent.setdefault(x, x) != x:
                parent[x] = parent[parent[x]]; x = parent[x]
            return x
        key = lambda i: tuple(np.round(P[i], 4))
        for p in polys:
            ks = [key(v[0]) for v in p]
            for k in ks[1:]:
                parent[root(ks[0])] = root(k)
        parts = {}
        for i, p in enumerate(polys):
            parts.setdefault(root(key(p[0][0])), []).append(i)
        for faces in parts.values():
            if len(faces) > 400:
                continue
            V = np.concatenate([verts[i] for i in faces])
            above = vol.in_footprint(V) & (V[:, 1] > vol.rail)
            # a hoop or frame whose legs reach down into the fuselage still stands mostly above the rails
            if above.mean() >= 0.7 and V[:, 1].max() > vol.rail + 0.1 and vol.in_footprint(V).all():
                new = set(faces) - sel
                sel |= new; why['canopy part'] += len(new)
                canopy_parts.update((b['name'], i) for i in new)
        if sel:
            out[b['name']] = sel
    # old canopy frame members welded into the hull mesh: faces along the old glass (strip.py's test) that stand
    # wholly above the rails in the footprint; the rails themselves and the deck under the glass stay
    along, _ = S.find(ac, tub, canopy=glass)
    for name, faces in along.items():
        pm = next(b for b in ac.bones if b['name'] == name)['poly_mesh']
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        for i in faces:
            V = P[[v[0] for v in pm['polys'][i]]]
            if vol.in_canopy(V) and i not in out.get(name, set()):
                out.setdefault(name, set()).add(i); why['canopy part'] += 1
    vol.parts = canopy_parts
    return out, why


def check(ac, rm, vol):
    """every removed face lies wholly in the volume (the well, or above the rails in the canopy's footprint) or
    belongs to an old canopy part (inside the footprint): -> list of offending faces"""
    bad = []
    for b in ac.bones:
        for i in rm.get(b['name'], ()):
            P = np.array(b['poly_mesh']['positions'], float) * np.array([1, 1, -1]) / 16.0
            V = P[[v[0] for v in b['poly_mesh']['polys'][i]]]
            part = (b['name'], i) in vol.parts and vol.in_footprint(V).all()
            if not (vol.in_well(V) or vol.in_canopy(V) or part):
                bad.append((b['name'], i))
    return bad


def rebuild(vid, ref='origin/main', dry=False):
    ac = load(vid, ref)
    tex = np.asarray(Image.open(L.texture_path(vid)).convert('RGBA')).astype(np.int16)
    sw = L.Swatches(L.texture_path(vid))
    fspec = F.FLEET['aircraft'].get(vid, {})
    era = C.era_of(vid)
    seat = fspec.get('seat', 0)
    E0 = ac.eye(seat)
    ac.drop_sight_glass(E0, fspec.get('tub', {}).get('s', 1.0))     # the old gunsight / HUD plates: not canopy
    tub_kw = dict(fspec.get('tub', {}))
    tub_kw.setdefault('hinge_frac', L.ERAS[era].get('hinge_frac', 0.5))
    tub = L.Tub(ac, seat, **tub_kw)
    ac.drop_glass_inside(tub)
    old = ac.glass.copy()
    spec = C.spec_for(vid, ac, tub.E, [tub.E], None)
    vol = Volume(tub, old, spec)

    # 1. cut
    rm, why = cut(ac, vol, tex, tub, old)
    bad = check(ac, rm, vol)
    if bad:
        raise SystemExit(f'{vid}: cut check failed, {len(bad)} faces outside the volume: {bad[:5]}')
    paint = tuple(spec.get('paint_rgb') or C.frame_rgb(ac, rm, tub, tex) or C.paint_rgb(ac, tub, tex))
    for b in ac.bones:
        if b['name'] in rm:
            b['poly_mesh']['polys'] = [p for i, p in enumerate(b['poly_mesh']['polys']) if i not in rm[b['name']]]
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith(('__cockpit', '__canopy'))]

    # 2. canopy
    shape = C.Shape(old, spec, tub.E, sill_ref=(tub.back_z, tub.Z + 0.1, tub.sill))
    cmesh, cglass = C.build(ac, tub, shape, spec, sw, paint)
    ac.glass = np.array(cglass, float).reshape(-1, 3, 3)
    ac.__dict__.pop('_skin', None); ac.__dict__.pop('_all', None)

    # 3. cockpit, under the new canopy
    mesh, stick, gauges, displays, hud_glass, hud = cockpit(ac, tub, era, fspec, sw)
    source = max(rm, key=lambda k: len(rm[k])) if rm else 'wreck_fuselage_1__hull'
    piece = source.split('__', 1)[0] if source.startswith('wreck_') else 'wreck_fuselage_1'
    hull = ac.bone('hull')
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
    ac.bones.insert(at, {'name': piece + '__canopy', 'parent': 'hull', 'pivot': list(hull['pivot']),
                         'poly_mesh': cmesh.poly_mesh()})
    ac.bones.insert(at, {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']),
                         'poly_mesh': mesh.poly_mesh()})
    write_instruments(ac, seat, gauges, displays, hud, tub)
    tri = list(ac.glass.reshape(-1, 3)) + [np.array(p, float) for p in hud_glass]
    ac.asset['CanopyGlass'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS',
                               'Triangles': [round(float(v), 4) + 0.0 for p in tri for v in p]}
    rep = (f'{vid} ({era}): cut {sum(len(v) for v in rm.values())} faces {why} from {sorted(rm)}; canopy z '
           f'{shape.rear:.2f}..{shape.front:.2f} paint {paint}; cockpit {len(mesh.faces)} faces, {len(gauges)} gauges, '
           f'{len(displays)} displays, HUD {"yes" if hud else "no"}')
    if not dry:
        with open(ac.geo_path, 'w', newline='\n', encoding='utf-8') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        C.write_asset_lf(ac.asset_path, ac.asset)
    return ac, rm, vol, rep


def cockpit(ac, tub, era, fspec, sw):
    """the modelled cockpit (fleet.py's build, without its own old-interior pass: the cut did that)"""
    E, s = tub.E, tub.s
    sticks = F.stick_bones(ac)
    rig = ac.asset.get('AircraftRig')
    rigged = bool(sticks) and rig and rig.get('Schema') == 2 and \
        any(sf.get('Bone') == 'control_stick_pitch' for sf in rig.get('Surfaces', []))
    side = fspec.get('side_stick')
    foot = (np.array([E[0] - side * 0.30 * s, tub.console_y + 0.02 * s, E[2] + 0.28 * s]) if side
            else np.array([E[0], E[1] - 0.92 * s, E[2] + 0.44 * s]))
    mesh, stick, gauges, displays, glass, hud = L.build(ac, tub, era, fspec.get('palette', 'nato'), sw,
                                                        hud=fspec.get('hud'), gunsight=fspec.get('gunsight', True),
                                                        stick_foot=foot, side_stick=bool(side))
    import detail as D
    D.add(mesh, tub, D.colours(sw, fspec.get('palette', 'nato')), era, displays)
    if rigged:
        px = L.r5(foot * np.array([16.0, 16.0, -16.0]))
        for b in ac.bones:
            if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
                b['pivot'] = px
        for b in sticks:
            b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        sticks[0]['poly_mesh'] = stick.poly_mesh()
        for sf in rig['Surfaces']:
            if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
                sf['Pivot'] = px
                if side:
                    sf['MaxDeflectionDegrees'] = 6.0
    else:
        for b in sticks:
            b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        mesh.extend(stick)
    return mesh, stick, gauges, displays, glass, hud


def write_instruments(ac, seat, gauges, displays, hud, tub):
    a = ac.asset
    old_g = [g for g in (a.get('CockpitGauges') or {}).get('Gauges', []) if g.get('Seat', 0) != seat]
    old_d = [d for d in (a.get('FlightDisplays') or {}).get('Displays', []) if d.get('Seat', 0) != seat]
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': old_g + gauges}
    fd = a.get('FlightDisplays') or {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS'}
    fd['Displays'] = old_d + displays
    fd.setdefault('Schema', 1); fd.setdefault('Frame', 'VEHICLE_LOCAL_BLOCKS')
    a['FlightDisplays'] = fd
    if any(d.get('Kind') == 'STORES' for d in fd['Displays']) and 'Planform' not in fd:
        import importlib.util
        sp = importlib.util.spec_from_file_location('cockpit_layout', os.path.join(HERE, '..', 'cockpit_layout',
                                                                                    'layout.py'))
        lay = importlib.util.module_from_spec(sp); sp.loader.exec_module(lay)
        fd['Planform'] = lay.planform(ac.vid)
    if not fd['Displays']:
        a.pop('FlightDisplays')
    if hud:
        a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat,
                              'Center': L.r5(hud['centre']), 'Normal': L.r5(hud['normal']), 'Up': L.r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': L.r5(tub.E)}
    else:
        a.pop('HeadUpDisplay', None)


def main(argv):
    ref = next((a.split('=', 1)[1] for a in argv if a.startswith('--ref=')), 'origin/main')
    dry = '--dry-run' in argv
    for vid in [a for a in argv if not a.startswith('--')]:
        print(rebuild(vid, ref, dry)[3], flush=True)


if __name__ == '__main__':
    main(sys.argv[1:])
