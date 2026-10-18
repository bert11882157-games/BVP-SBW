#!/usr/bin/env python3
"""Pulls every helicopter's suspended-armament slots from the War Thunder datamine (github.com/gszabi99/War-Thunder-
Datamine, aces.vromfs.bin_u/gamedata) and resolves each preset down to its launcher container and munition: count,
mass, and the munition's own flight and guidance numbers. Writes wt_slots.json next to this script; build.py reads it,
so the mod's pylons carry exactly the presets the game allows.

Usage: python3 tools/heli_armament/extract_wt.py [--ref COMMIT]   (network: raw.githubusercontent.com)
"""
import json, os, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = 'https://raw.githubusercontent.com/gszabi99/War-Thunder-Datamine/{ref}/aces.vromfs.bin_u/'
# mod vehicle id -> War Thunder unit (the nearest one where the game has no exact match; see README)
UNITS = {
    'ah_64d': 'ah_64d', 'ah_1f': 'ah_1f', 'ah_1g_cobra': 'ah_1g', 'ah_6j': 'ah_6m',
    'eurocopter_tiger': 'tiger_uht', 'ka50': 'ka_50', 'mi24v': 'mi_24v', 'mi28n': 'mi_28n',
    'mi_24a': 'mi_24a', 'mi_24d': 'mi_24d',
}
_cache = {}


def fetch(ref, path):
    path = path.replace('\\', '/')
    low = path.lower()
    if low.startswith('gamedata/'):
        low = 'gamedata/' + low[len('gamedata/'):]
    if low.endswith('.blk'):
        low += 'x'
    if low in _cache:
        return _cache[low]
    url = REPO.format(ref=ref) + low
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            data = json.loads(r.read().decode('utf-8'))
    except Exception as e:                       # noqa: BLE001 - recorded, the slot table still lists the preset
        data = {'_error': f'{e} ({url})'}
    _cache[low] = data
    return data


def as_list(x):
    return x if isinstance(x, list) else ([] if x is None else [x])


ROCKET_KEYS = ('caliber', 'mass', 'massEnd', 'maxSpeed', 'endSpeed', 'maxDistance', 'timeLife', 'explosiveMass',
               'explosiveType', 'armorpower', 'cumulativeDamage', 'guidanceType', 'operatedDist', 'distFromCmdLineToStartCtrl',
               'maxLateralAcceleration', 'thrustDeflection', 'length', 'bulletName', 'fuseDelayDist', 'hitPowerMult')


def munition(ref, blk):
    """the round a gun/rocket blk fires: a flat summary"""
    d = fetch(ref, blk)
    if '_error' in d:
        return {'blk': blk, 'error': d['_error']}
    r = d.get('rocket') or d.get('bomb') or d.get('bullet')
    kind = 'rocket' if 'rocket' in d else 'bomb' if 'bomb' in d else 'bullet' if 'bullet' in d else '?'
    if isinstance(r, list):
        r = r[0]
    out = {'blk': blk, 'kind': kind}
    if isinstance(r, dict):
        for k in ROCKET_KEYS:
            if k in r:
                out[k] = r[k]
        g = r.get('guidance')
        if isinstance(g, dict):
            out['guidance'] = {k: v for k, v in g.items() if not isinstance(v, (dict, list)) or k in ('irSeeker', 'laserBeam')}
            for k in ('irSeeker', 'opticalFlowSeeker', 'radarSeeker', 'laserBeam'):
                if k in g:
                    out['guidance'][k] = {kk: vv for kk, vv in g[k].items() if not isinstance(vv, (dict, list))}
            if 'guidanceAutopilot' in g:
                out['guidance']['autopilot'] = {kk: vv for kk, vv in g['guidanceAutopilot'].items() if not isinstance(vv, (dict, list))}
        if 'cumulativeDamage' in r and isinstance(r['cumulativeDamage'], dict):
            out['cumulativeDamage'] = r['cumulativeDamage']
    for k in ('bullets', 'shotFreq', 'mass', 'cannon', 'sound'):
        if k in d and not isinstance(d[k], (dict, list)):
            out['gun_' + k] = d[k]
    return out


def weapon(ref, w):
    blk = w.get('blk', '')
    entry = {'blk': blk.split('/')[-1].rsplit('.', 1)[0], 'trigger': w.get('trigger'), 'emitter': w.get('emitter'),
             'bullets': w.get('bullets')}
    d = fetch(ref, blk)
    if d.get('container'):
        entry['container'] = {k: d[k] for k in ('mesh', 'bullets', 'mass', 'emitter') if k in d}
        entry['munition'] = munition(ref, d.get('blk', ''))
    else:
        entry['munition'] = munition(ref, blk)
    return entry


def main(argv):
    ref = argv[argv.index('--ref') + 1] if '--ref' in argv else 'master'
    out = {'source': REPO.format(ref=ref), 'units': {}}
    for vid, unit in UNITS.items():
        fm = fetch(ref, f'gamedata/flightmodels/{unit}.blkx')
        ws = fm.get('WeaponSlots', {})
        slots = []
        for s in as_list(ws.get('WeaponSlot')):
            presets = []
            for p in as_list(s.get('WeaponPreset')):
                presets.append({'name': p.get('name'), 'icon': p.get('iconType'),
                                'weapons': [weapon(ref, w) for w in as_list(p.get('Weapon'))
                                            if 'dummy' not in w.get('blk', '')]})
            slots.append({'index': s.get('index'), 'order': s.get('order'), 'presets': presets})
        out['units'][vid] = {'wt_unit': unit, 'maxloadMass': ws.get('maxloadMass'),
                             'maxloadMassLeftConsoles': ws.get('maxloadMassLeftConsoles'),
                             'maxloadMassRightConsoles': ws.get('maxloadMassRightConsoles'),
                             'maxDisbalance': ws.get('maxDisbalance'), 'slots': slots}
        print(vid, unit, len(slots), 'slots', file=sys.stderr)
    with open(os.path.join(HERE, 'wt_slots.json'), 'w') as f:
        json.dump(out, f, indent=1)
        f.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
