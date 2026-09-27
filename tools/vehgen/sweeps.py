"""Rig part pivots and axes (swing wings, and also control surfaces and rotors), kept consistent between the geo bones
and the client AircraftRig.

The rig animator rejects a Sweeps entry whose Pivot differs from the bone pivot in the model, so both change together:
the sweep bone and every descendant that shares its pivot (e.g. wreck_wing_left__sweep_left), in each geo given.

    python3 tools/vehgen/sweeps.py <vehicle id> '<overrides json>'

patches custom_geo/<id>.geo.json, geo/native_fallback/<id>.geo.json and the client sbw/vehicles/<id>.json in place.
Overrides: {"sweep_left": {"pivot": [x, null, z], "axis": [0, 1, 0], "maxDegrees": 48}, ...}; a null pivot component keeps the old
value. Geo px, frame of the geo (+X left, +Y up, nose -Z). A sweep turns about +Y (axis [0, 1, 0]); AngleSign -1 on
the left wing sweeps the tip aft.
"""
import json
import os
import re
import sys

import numpy as np


def apply(geos, client, overrides):
    pivots = apply_geo(geos, overrides)
    apply_client(client, overrides, pivots)


def apply_geo(geos, overrides):
    """Sets the geo pivots; returns {bone: new pivot}."""
    pivots = {}
    for bone, o in overrides.items():
        new = None
        for geo in geos:
            bones = geo['minecraft:geometry'][0]['bones']
            by = {b['name']: b for b in bones}
            if bone not in by:
                raise SystemExit(f'no bone {bone}')
            old = list(by[bone]['pivot'])
            new = [round(float(v), 5) if v is not None else old[i] for i, v in enumerate(o['pivot'])]
            names = {bone}
            grew = True
            while grew:
                grew = False
                for b in bones:
                    if b.get('parent') in names and b['name'] not in names:
                        names.add(b['name'])
                        grew = True
            for b in bones:
                if b['name'] in names and np.allclose(b['pivot'], old, atol=1e-4):
                    b['pivot'] = list(new)
        pivots[bone] = new
    return pivots


def apply_client(client, overrides, pivots):
    for bone, o in overrides.items():
        new = pivots[bone]
        hit = False
        rig = client['AircraftRig']
        for s in [s for key in ('Sweeps', 'Surfaces', 'Rotors') for s in (rig.get(key) or [])]:
            if s['Bone'] == bone:
                s['Pivot'] = list(new)
                if 'axis' in o:
                    s['Axis'] = list(o['axis'])
                if 'maxDegrees' in o:
                    s['MaxDeflectionDegrees'] = o['maxDegrees']
                hit = True
        if not hit:
            raise SystemExit(f'no rig Sweeps/Surfaces/Rotors entry for {bone}')


def _set_array(block, key, values):
    m = re.search(r'"%s":\s*\[([^\]]*)\]' % key, block)
    parts = m.group(1).split(',')
    assert len(parts) == len(values)
    new = ','.join(re.sub(r'[-0-9.eE]+', json.dumps(v), p, count=1) for p, v in zip(parts, values))
    return block[:m.start(1)] + new + block[m.end(1):]


def main(argv):
    vid, overrides = argv[1], json.loads(argv[2])
    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.join(here, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack')
    paths = [os.path.join(assets, 'custom_geo', f'{vid}.geo.json'),
             os.path.join(assets, 'geo', 'native_fallback', f'{vid}.geo.json')]
    geos = [json.load(open(p)) for p in paths]
    cpath = os.path.join(assets, 'sbw', 'vehicles', f'{vid}.json')
    client = json.load(open(cpath))
    apply(geos, client, overrides)
    for p, g in zip(paths, geos):
        with open(p, 'w') as f:
            json.dump(g, f, separators=(',', ':'))
            f.write('\n')
    # edit only the changed values in the text: the client files are partly hand-formatted
    text = open(cpath).read()
    rig = client['AircraftRig']
    for s in [s for key in ('Sweeps', 'Surfaces', 'Rotors') for s in (rig.get(key) or [])]:
        if s['Bone'] not in overrides:
            continue
        start = text.index('"AircraftRig"')
        a = text.index(f'"Bone": "{s["Bone"]}"', start)
        nxt = text.find('"Bone":', a + 8)
        b = len(text) if nxt < 0 else nxt
        block = text[a:b]
        for key, value in (('Pivot', s['Pivot']), ('Axis', s['Axis'])):
            if key not in block:
                continue
            block = _set_array(block, key, value)
        block = re.sub(r'("MaxDeflectionDegrees":\s*)[-0-9.eE]+', lambda m: m.group(1) + json.dumps(
            s['MaxDeflectionDegrees']), block, count=1)
        text = text[:a] + block + text[b:]
    assert json.loads(text) == client
    with open(cpath, 'w') as f:
        f.write(text)
    for s in [s for key in ('Sweeps', 'Surfaces', 'Rotors') for s in (rig.get(key) or [])]:
        if s['Bone'] in overrides:
            print(vid, s['Bone'], s['Pivot'], s['Axis'])


if __name__ == '__main__':
    main(sys.argv)
