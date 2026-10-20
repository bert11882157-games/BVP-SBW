#!/usr/bin/env python3
"""Modelled cockpits for the fleet (cockpit_lib.py does the modelling).

For each aircraft listed in fleet.json: the pilot's station is measured from the eye and the canopy glass, the old
interior is removed, and a modelled cockpit of the aircraft's era is put in its place:

  * new faces go into the bone wreck_<piece>__cockpit (parent hull) of the wreck piece that held most of the old
    interior, so the cockpit breaks away with its fuselage section;
  * the stick goes into the existing animated stick bone (<piece>__control_stick_pitch), whose pivot (and the
    AircraftRig surfaces that turn it) move to the new stick's foot; aircraft without a stick rig get a fixed stick;
  * CockpitGauges and FlightDisplays for that seat are replaced by the new panel's instruments and annunciator
    panels (other seats keep theirs), HeadUpDisplay is written for HUD-equipped jets and the HUD combiner / gunsight
    glass is added to CanopyGlass.

Run it on the committed originals (git checkout the aircraft's geo and asset files first): the tub is measured from
the old interior, so a run on top of an earlier one fits slightly differently and removes a few more faces.
tools/cockpit_layout/layout.py leaves the aircraft listed here alone.

tools/canopy_build/run.py runs it in two stages around the canopy build: --stage=strip measures the tub and
removes the old interior (saving the tub to --state=FILE), the canopy is cut and rebuilt on the bare tub, then
--stage=build puts the cockpit in from the saved tub.

Usage: python3 tools/cockpit_models/fleet.py [--dry-run] [--sheet DIR] [--stage=strip|build --state=FILE] [id...]
       (no ids: all of fleet.json)
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import cockpit_lib as L  # noqa: E402
import structure as ST  # noqa: E402

FLEET = json.load(open(os.path.join(HERE, 'fleet.json')))
PRESETS = json.load(open(os.path.join(HERE, '..', 'cockpit_layout', 'presets.json')))['aircraft']
MARK = 'modelled_cockpit'          # glass triangles this tool added are tagged by position: see glass_drop()


FRAMES_DIR = os.path.join(HERE, '..', 'canopy_panels', 'frames')
PLATES_DIR = os.path.join(HERE, 'plates')     # the structures' plates, for the instrument layouts and the previews


def stick_bones(ac):
    return [b for b in ac.bones if b['name'].endswith('__control_stick_pitch')]


def piece_of(name):
    return name.split('__', 1)[0] if name.startswith('wreck_') else None


def measure(ac, spec, era):
    """the tub for this aircraft's seat, measured on the model as loaded (the originals, with the panel glass); ->
    (tub, old sight-glass triangles dropped)"""
    seat = spec.get('seat', 0)
    panelled = os.path.exists(os.path.join(FRAMES_DIR, ac.vid + '.json'))   # tools/canopy_panels glass: nothing old in it
    sight_glass = 0 if panelled else ac.drop_sight_glass(ac.eye(seat), spec.get('tub', {}).get('s', 1.0))
    tub_kw = dict(spec.get('tub', {}))
    tub_kw.setdefault('hinge_frac', L.ERAS[era].get('hinge_frac', 0.5))
    tub = L.Tub(ac, seat, **tub_kw)
    sight_glass += 0 if panelled else ac.drop_glass_inside(tub)
    return tub, sight_glass


def strip(ac, spec, tub):
    """removes the old interior around the tub (and the hand-listed leftovers), never a face that can be seen from
    outside the glazed aircraft; -> (faces removed, faces cut, removed per bone, exterior faces kept back)"""
    E = tub.E
    rm, clips = L.old_interior(ac, [tub])
    # hand-listed leftovers: boxes relative to the eye (x left, y up, z forward) whose faces go too
    for lo, hi in spec.get('remove_boxes', []):
        lo, hi = np.array(lo) + E, np.array(hi) + E
        for b in ac.bones:
            pm = b.get('poly_mesh')
            if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']):
                continue
            P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
            for i, poly in enumerate(pm['polys']):
                cc = P[[v[0] for v in poly]].mean(0)
                if np.all(cc >= lo) and np.all(cc <= hi):
                    rm.setdefault(b['name'], set()).add(i)
                    clips.get(b['name'], {}).pop(i, None)
    kept = L.keep_exterior(ac, rm, clips, [tub])   # nothing seen from outside the glazed aircraft goes
    removed, count = 0, {}
    for b in ac.bones:
        pm = b.get('poly_mesh')
        for i, (axis, c) in clips.get(b['name'], {}).items():
            L.clip_keep(pm, i, axis, c)
        if b['name'] in rm or b['name'] in clips:
            gone = rm.get(b['name'], set())
            pm['polys'] = [p for i, p in enumerate(pm['polys']) if i not in gone and p]
            removed += len(gone)
            count[b['name']] = len(gone)
    return removed, sum(map(len, clips.values())), count, kept


def empty_mesh():
    return {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}


def cockpit_bone(ac, source):
    """the bone the new cockpit faces go into (parent hull), made after the bone that held most of the old interior
    when it does not exist yet"""
    bone = next((b for b in ac.bones if b['name'].endswith('__cockpit')), None)
    if bone is None:
        piece = piece_of(source) or 'wreck_fuselage_1'
        hull = ac.bone('hull')
        bone = {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': empty_mesh()}
        at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
        ac.bones.insert(at, bone)
    return bone


def save_state(path, ac, tub, extra):
    """the tub and the glass it was measured against (the build stage places the HUD and the panel corners under
    that glass, not under the thicker glass tools/canopy_build writes)"""
    d = {k: (v.tolist() if isinstance(v, np.ndarray) else v) for k, v in tub.__dict__.items()}
    json.dump({'tub': d, 'glass': [round(float(v), 4) for v in ac.glass.reshape(-1)], **extra}, open(path, 'w'))


def load_state(path, ac):
    st = json.load(open(path))
    tub = L.Tub.__new__(L.Tub)
    tub.__dict__.update(st['tub'])
    tub.E = np.array(tub.E, float)
    ac.glass = np.array(st['glass'], float).reshape(-1, 3, 3)
    return tub, st


def apply(vid, dry=False, stage=None, state=None):
    """stage None: the old interior out and the new cockpit in, in one go, on the committed originals.
    'strip': the tub is measured and the old interior removed, an empty cockpit bone marks the piece, and the tub
    goes to the [state] file; 'build': the cockpit is built from that tub, after tools/canopy_build has cut the old
    canopy and built the new one (the tub is not measured again: the glass and skin have changed by then)."""
    spec = FLEET['aircraft'][vid]
    era = spec.get('era') or PRESETS.get(vid)
    era = era if isinstance(era, str) else era.get('preset')
    ac = L.Aircraft(vid)
    seat = spec.get('seat', 0)
    if stage == 'build':
        tub, st = load_state(state, ac)
        source, removed, cut, count, sight_glass = st['source'], st['removed'], st['cut'], st['count'], st['sight_glass']
    else:
        tub, sight_glass = measure(ac, spec, era)
        # drop what an earlier run added
        ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
        removed, cut, count, kept = strip(ac, spec, tub)
        source = max(count, key=count.get) if count else 'wreck_fuselage_1__hull'
    E, s = tub.E, tub.s
    bone = cockpit_bone(ac, source)
    a = ac.asset
    if stage == 'strip':
        if a.get('CanopyGlass'):                 # the old sight glass went with the old interior
            a['CanopyGlass']['Triangles'] = [round(float(v), 4) + 0.0 for v in ac.glass.reshape(-1)]
        if not dry:
            with open(ac.geo_path, 'w') as f:
                json.dump(ac.geo, f, separators=(',', ':'))
            L.write_asset(ac.asset_path, ac.asset)
            save_state(state, ac, tub, {'source': source, 'removed': removed, 'cut': cut, 'count': count,
                                    'sight_glass': sight_glass})
        return ac, tub, (f'{vid}: {era}, {tub.describe()}; removed {removed} old faces from {sorted(count)}, '
                         f'cut {cut}, {kept} exterior faces kept back, {sight_glass} old glass triangles dropped; '
                         f'cockpit bone {bone["name"]}')

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

    sw = L.Swatches(L.texture_path(vid))
    plates, st_rep = None, ''
    if ST.has(vid):
        # the aircraft's own structure (structures/<vid>.py): stage 1 of the hand-built cockpits
        mesh, stick, gauges, displays, glass, hud, st_foot, plates, st_rep = ST.build(vid, ac, tub, sw, spec,
                                                                                      write_textures=not dry)
        if st_foot is not None:
            foot = np.array(st_foot, float)
    else:
        mesh, stick, gauges, displays, glass, hud = L.build(ac, tub, era, spec.get('palette', 'nato'), sw,
                                                            hud=spec.get('hud'), gunsight=spec.get('gunsight', True),
                                                            stick_foot=foot, side_stick=bool(side))
    if rigged:
        px = L.r5(foot * np.array([16.0, 16.0, -16.0]))
        for b in ac.bones:
            if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
                b['pivot'] = px
        for b in sticks:
            b['poly_mesh'] = empty_mesh()
        sticks[0]['poly_mesh'] = stick.poly_mesh()
        for sf in rig['Surfaces']:
            if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
                sf['Pivot'] = px
                if side:
                    sf['MaxDeflectionDegrees'] = 6.0           # a side stick barely moves
    else:
        if sticks:                                            # an unrigged stick bone: empty it, the stick is fixed
            for b in sticks:
                b['poly_mesh'] = empty_mesh()
        mesh.extend(stick)

    pm = mesh.poly_mesh()
    bone['poly_mesh'] = pm

    # instruments: this seat's are replaced, other seats keep theirs
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
    # glass: the old HUD / gunsight glass went with the old interior (drop_sight_glass), the new combiner comes in;
    # at the build stage the canopy glass is tools/canopy_build's and stays as it is
    if stage is None and a.get('CanopyGlass'):
        a['CanopyGlass']['Triangles'] = [round(float(v), 4) + 0.0 for v in ac.glass.reshape(-1)]
    if glass:
        L.add_glass(a, glass)

    vol = L.loader_volume(pm)
    report = (f'{vid}: {era}, {tub.describe()}; removed {removed} old faces from {sorted(count)}, cut {cut}; '
              f'{len(pm["polys"])} new faces in {bone["name"]} (volume {vol:+.0f}; clipped to the skin: '
              f'{mesh.clipped[0]} vertices moved, {mesh.clipped[1]} faces dropped; {mesh.roofed} deck plates), {len(gauges)} gauges, '
              f'{len(displays)} displays, {sight_glass} old glass triangles dropped, HUD {"yes" if hud else "no"}, stick {"rigged" if rigged else "fixed"}'
              f'{" side" if side else ""}; colour error up to {sw.worst:.0f}' + (f'; structure: {st_rep}' if st_rep else ''))
    if not dry:
        with open(ac.geo_path, 'w') as f:
            json.dump(ac.geo, f, separators=(',', ':'))
        L.write_asset(ac.asset_path, ac.asset)
        if plates is not None:
            os.makedirs(PLATES_DIR, exist_ok=True)
            json.dump({'vehicle': vid, 'seat': seat, 'eye': L.r5(E), 'plates': plates},
                      open(os.path.join(PLATES_DIR, vid + '.json'), 'w'), indent=1)
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
    stage = next((a[8:] for a in argv if a.startswith('--stage=')), None)
    state = next((a[8:] for a in argv if a.startswith('--state=')), None)
    ids = [a for a in argv if not a.startswith('--') and a != out] or sorted(FLEET['aircraft'])
    for vid in ids:
        _, _, rep = apply(vid, dry, stage, state)
        print(rep)
    if out and not dry:
        sheet(ids, out)


if __name__ == '__main__':
    main(sys.argv[1:])
