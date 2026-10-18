#!/usr/bin/env python3
"""Modelled cockpits for the fleet (cockpit_lib.py does the modelling).

For each aircraft listed in fleet.json: the pilot's station is measured from the eye and the canopy glass, the old
interior is removed, and a modelled cockpit of the aircraft's era is put in its place:

  * new faces go into the bone wreck_<piece>__cockpit (parent hull) of the wreck piece that held most of the old
    interior, so the cockpit breaks away with its fuselage section;
  * the stick goes into the existing animated stick bone (<piece>__control_stick_pitch), whose pivot (and the
    AircraftRig surfaces that turn it) move to the new stick's foot; aircraft without a stick rig get a fixed stick;
  * CockpitGauges and FlightDisplays for that seat are replaced by the new panel's instruments (other seats keep
    theirs), HeadUpDisplay is written for HUD-equipped jets and the HUD combiner / gunsight glass is added to
    CanopyGlass.

Run it on the committed originals (git checkout the aircraft's geo and asset files first): the tub is measured from
the old interior, so a run on top of an earlier one fits slightly differently and removes a few more faces.
tools/cockpit_layout/layout.py leaves the aircraft listed here alone.

Usage: python3 tools/cockpit_models/fleet.py [--dry-run] [--sheet DIR] [id...]   (no ids: all of fleet.json)
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402

FLEET = json.load(open(os.path.join(HERE, 'fleet.json')))
PRESETS = json.load(open(os.path.join(HERE, '..', 'cockpit_layout', 'presets.json')))['aircraft']
MARK = 'modelled_cockpit'          # glass triangles this tool added are tagged by position: see glass_drop()


def stick_bones(ac):
    return [b for b in ac.bones if b['name'].endswith('__control_stick_pitch')]


def piece_of(name):
    return name.split('__', 1)[0] if name.startswith('wreck_') else None


def apply(vid, dry=False):
    spec = FLEET['aircraft'][vid]
    era = spec.get('era') or PRESETS.get(vid)
    era = era if isinstance(era, str) else era.get('preset')
    ac = L.Aircraft(vid)
    sw = L.Swatches(L.texture_path(vid))
    seat = spec.get('seat', 0)
    sight_glass = ac.drop_sight_glass(ac.eye(seat), spec.get('tub', {}).get('s', 1.0))
    tub = L.Tub(ac, seat, **spec.get('tub', {}))
    sight_glass += ac.drop_glass_inside(tub)
    E, s = tub.E, tub.s

    # drop what an earlier run added
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]

    # the old interior
    rm, clips = L.old_interior(ac, [tub])
    removed = 0
    count = {}
    for b in ac.bones:
        pm = b.get('poly_mesh')
        for i, (axis, c) in clips.get(b['name'], {}).items():
            L.clip_keep(pm, i, axis, c)
        if b['name'] in rm or b['name'] in clips:
            gone = rm.get(b['name'], set())
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone and p]
            removed += len(gone)
            count[b['name']] = len(gone)
    source = max(count, key=count.get) if count else 'wreck_fuselage_1__hull'
    piece = piece_of(source) or 'wreck_fuselage_1'

    # stick
    sticks = stick_bones(ac)
    rig = ac.asset.get('AircraftRig')
    rigged = bool(sticks) and rig and rig.get('Schema') == 2 and \
        any(sf.get('Bone') == 'control_stick_pitch' for sf in rig.get('Surfaces', []))
    side = spec.get('side_stick')
    if side:
        foot = np.array([E[0] - side * 0.30 * s, tub.console_y + 0.02 * s, E[2] + 0.28 * s])
    else:
        foot = np.array([E[0], E[1] - 0.92 * s, E[2] + 0.44 * s])

    mesh, stick, gauges, displays, glass, hud = L.build(ac, tub, era, spec.get('palette', 'nato'), sw,
                                                        hud=spec.get('hud'), gunsight=spec.get('gunsight', True),
                                                        stick_foot=foot, side_stick=bool(side))
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
                    sf['MaxDeflectionDegrees'] = 6.0           # a side stick barely moves
    else:
        if sticks:                                            # an unrigged stick bone: empty it, the stick is fixed
            for b in sticks:
                b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        mesh.extend(stick)

    pm = mesh.poly_mesh()
    hull = ac.bone('hull')
    bone = {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
    ac.bones.insert(at, bone)

    # instruments: this seat's are replaced, other seats keep theirs
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
        spec_ = importlib.util.spec_from_file_location('cockpit_layout', os.path.join(HERE, '..', 'cockpit_layout',
                                                                                      'layout.py'))
        lay = importlib.util.module_from_spec(spec_); spec_.loader.exec_module(lay)
        fd['Planform'] = lay.planform(vid)                    # the stores page draws the airframe from above
    if not fd['Displays']:
        a.pop('FlightDisplays')
    if hud:
        a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': seat,
                              'Center': L.r5(hud['centre']), 'Normal': L.r5(hud['normal']), 'Up': L.r5(hud['up']),
                              'Width': round(float(hud['width']), 5), 'Height': round(float(hud['height']), 5),
                              'Eye': L.r5(E)}
    else:
        a.pop('HeadUpDisplay', None)
    # glass: the old HUD / gunsight glass went with the old interior (drop_sight_glass), the new combiner comes in
    if a.get('CanopyGlass'):
        a['CanopyGlass']['Triangles'] = [round(float(v), 4) + 0.0 for v in ac.glass.reshape(-1)]
    if glass:
        L.add_glass(a, glass)

    vol = L.loader_volume(pm)
    report = (f'{vid}: {era}, {tub.describe()}; removed {removed} old faces from {sorted(count)}, cut {sum(map(len, clips.values()))}; '
              f'{len(pm["polys"])} new faces in {bone["name"]} (volume {vol:+.0f}), {len(gauges)} gauges, '
              f'{len(displays)} displays, {sight_glass} old glass triangles dropped, HUD {"yes" if hud else "no"}, stick {"rigged" if rigged else "fixed"}'
              f'{" side" if side else ""}; colour error up to {sw.worst:.0f}')
    if not dry:
        with open(ac.geo_path, 'w') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        L.write_asset(ac.asset_path, ac.asset)
    return ac, tub, report


def sheet(vids, out_dir, **kw):
    import eyeview as EV
    from PIL import Image
    os.makedirs(out_dir, exist_ok=True)
    for vid in vids:
        EV.render(vid, os.path.join(out_dir, vid + '.png'), size=(700, 480), **kw)


def main(argv):
    dry = '--dry-run' in argv
    out = None
    if '--sheet' in argv:
        out = argv[argv.index('--sheet') + 1]
    ids = [a for a in argv if not a.startswith('--') and a != out] or sorted(FLEET['aircraft'])
    for vid in ids:
        _, _, rep = apply(vid, dry)
        print(rep)
    if out and not dry:
        sheet(ids, out)


if __name__ == '__main__':
    main(sys.argv[1:])
