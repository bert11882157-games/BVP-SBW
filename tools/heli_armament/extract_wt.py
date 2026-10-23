#!/usr/bin/env python3
"""Pulls every helicopter's suspended-armament slots from the War Thunder datamine (github.com/gszabi99/War-Thunder-
Datamine, aces.vromfs.bin_u/gamedata) and resolves each preset down to its launcher container and munition: count,
mass, and the munition's own flight and guidance numbers. Writes wt_slots.json next to this script; build.py reads it,
so the mod's pylons carry exactly the presets the game allows.

It also pulls every gun the helicopters carry (built in or in a pod) and the ammunition belts War Thunder offers on
that helicopter: gamedata/weapons/<gun>.blkx gives the default belt (its top-level `bullet` list) and the named belts
(`<gun>_universal`, `_ground_targets`, `_armor_targets`, `_air_targets`, `_stealth`, `_tracers` blocks, each a
`bullet` list in firing order); the unit's flightmodels/<unit>.blkx `modifications` list says which named belts the
helicopter has. Each round keeps its WT numbers (mass, calibre, speed, Cx, filler, tracer, de Marre factor or armour
power table). Belt and round names come from lang.vromfs.bin_u/lang/units_modifications.csv and units_weaponry.csv.
Writes wt_guns.json; build.py turns it into the guns' belts (belts.py).

Usage: python3 tools/heli_armament/extract_wt.py [--ref COMMIT] [--guns]   (network: raw.githubusercontent.com)
       --guns rewrites only wt_guns.json
"""
import csv, io, json, os, sys, urllib.request

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


# ------------------------------------------------------------------------------------------------ guns and belts
LANG = 'https://raw.githubusercontent.com/gszabi99/War-Thunder-Datamine/{ref}/lang.vromfs.bin_u/lang/{name}'
ROUND_KEYS = ('mass', 'damageMass', 'caliber', 'damageCaliber', 'ballisticCaliber', 'speed', 'Cx', 'explosiveMass',
              'explosiveType')
BELT_SUFFIX = {'universal': 'Universal', 'ground_targets': 'Ground targets', 'armor_targets': 'Armored targets',
               'air_targets': 'Air targets', 'stealth': 'Stealth', 'tracers': 'Tracers'}


def lang(ref, name):
    """{key: English text} of one WT localisation table"""
    with urllib.request.urlopen(LANG.format(ref=ref, name=name), timeout=120) as r:
        rows = csv.reader(io.StringIO(r.read().decode('utf-8-sig')), delimiter=';')
        return {row[0]: row[1] for row in rows if len(row) > 1}


def gun_blks(fm):
    """the gun blks (cannon*/gun*) a flight model mounts anywhere: built in or in a pod preset"""
    out = []

    def walk(o):
        if isinstance(o, dict):
            b = o.get('blk')
            if isinstance(b, str):
                name = b.replace('\\', '/').split('/')[-1]
                if b.lower().startswith('gamedata/weapons/') and b.count('/') == 2 and \
                        name.lower().startswith(('cannon', 'gun')) and b not in out:
                    out.append(b)
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(fm.get('WeaponSlots', {}))
    return out


def bullet(b, strength):
    r = {k: b[k] for k in ROUND_KEYS if k in b}
    r['bulletType'] = b.get('bulletType')
    if 'explosiveMass' in b and b.get('explosiveType') in strength:
        r['tntKg'] = round(b['explosiveMass'] * strength[b['explosiveType']], 5)
    tr = (b.get('visual') or {}).get('tracer')
    r['tracer'] = None if tr in (None, 'noColor') else tr
    kin = (b.get('damage') or {}).get('kinetic') or {}
    if 'demarrePenetrationK' in kin:
        r['demarre'] = {k: kin[k] for k in ('demarrePenetrationK', 'demarreSpeedPow', 'demarreMassPow',
                                             'demarreCaliberPow') if k in kin}
    ap = b.get('armorpower')
    if isinstance(ap, dict):
        r['armorpower'] = sorted([v[1], v[0]] for v in ap.values())       # [[metres, mm], ...]
    cum = b.get('cumulativeDamage')
    if isinstance(cum, dict) and 'armorPower' in cum:
        r['cumulativeMm'] = cum['armorPower']
    return r


def guns(ref):
    strength = {k: v['strengthEquivalent'] for k, v in fetch(ref, 'gamedata/damage_model/explosive.blkx')
                .get('explosiveTypes', {}).items() if isinstance(v, dict) and 'strengthEquivalent' in v}
    mods, weaponry = lang(ref, 'units_modifications.csv'), lang(ref, 'units_weaponry.csv')
    out = {'source': REPO.format(ref=ref), 'guns': {}, 'units': {}, 'belt_names': {}, 'round_names': {}}
    for vid, unit in UNITS.items():
        fm = fetch(ref, f'gamedata/flightmodels/{unit}.blkx')
        have = set(fm.get('modifications', {}))
        unit_guns = {}
        for blk in gun_blks(fm):
            key = blk.split('/')[-1].rsplit('.', 1)[0].lower()
            d = fetch(ref, blk)
            belts = {'default': as_list(d.get('bullet'))}
            belts.update({k: as_list(v['bullet']) for k, v in d.items() if isinstance(v, dict) and 'bullet' in v})
            g = out['guns'].setdefault(key, {'blk': 'gamedata/weapons/' + key + '.blkx',
                                             'name': weaponry.get('weapons/' + blk.split('/')[-1].rsplit('.', 1)[0]),
                                             'belts': {}, 'rounds': {}})
            for bk, bl in belts.items():
                seq = []
                for b in bl:
                    r = bullet(b, strength)
                    t = r['bulletType']
                    if t in g['rounds'] and g['rounds'][t] != r:
                        raise ValueError(f'{key}: two different {t} rounds')
                    g['rounds'][t] = r
                    seq.append(t)
                    out['round_names'][t] = weaponry.get(f'{t}/name/short', t)
                g['belts'][bk] = seq
                if bk != 'default':
                    suffix = next((s for s in BELT_SUFFIX if bk.lower().endswith('_' + s)), None)
                    out['belt_names'][bk] = mods.get(f'modification/{bk}/short') or BELT_SUFFIX.get(suffix, bk)
            unit_guns[key] = ['default'] + [bk for bk in belts if bk != 'default' and bk in have]
        out['units'][vid] = {'wt_unit': unit, 'guns': unit_guns}
        print(vid, unit, {k: len(v) for k, v in unit_guns.items()}, file=sys.stderr)
    out['belt_names']['default'] = 'Default'
    with open(os.path.join(HERE, 'wt_guns.json'), 'w') as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write('\n')


def main(argv):
    ref = argv[argv.index('--ref') + 1] if '--ref' in argv else 'master'
    guns(ref)
    if '--guns' in argv:
        return
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
