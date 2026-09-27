#!/usr/bin/env python3
"""Point vehicle weapons at the imported bvp_audio weapon families (tools/audio/lizard_manifest.json 'weapon_map').

  python3 tools/audio/apply_weapon_sounds.py [--dry-run]

For every weapon in bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles/*.json whose '<vehicle>.<weapon>'
or current Fire3P event is mapped: Fire1P / Fire3P / Fire3PFar / Fire3PVeryFar become the family's 1p / 3p / far /
very-far events (very far falls back to far), the four gains become 1 and SoundRadius the family's hearing range (in
units of 16 blocks: near band 0.4x, far band 0.7x, silence at 1x; see SpatialAudio.weaponCue). Other weapons are left
alone. Idempotent: a weapon already on a family keeps it (its Fire3P is then a bvp_audio event, matched back).
"""
import glob, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
DATA = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw', 'vehicles')
SOUNDS = os.path.join(REPO, 'bvp', 'src', 'main', 'resources', 'assets', 'bvp_audio', 'sounds', 'weapon')

RADIUS = {
    't90_125': 40, 't72_125': 40, 'nato_120': 40, 'cr2_120': 40, 'bmp3_100': 34, 'erc_90': 34,
    '2a42_30': 26, '2a72_30': 26, 'lav_25': 24, 'm242_25': 24, 'rh202_20': 22, 'zu23': 24,
    'kpvt_145': 20, 'kord': 18, 'dshk': 18, 'm2hb': 18,
    'pkt': 12, 'm240': 12, 'mg3': 12, 'china_mg': 12,
    'tow': 16, 'atgm_ru': 16, 'spg9': 18, 'mk19': 12, 's8': 16, 'hydra': 16,
}


def main(argv):
    dry = '--dry-run' in argv
    manifest = json.load(open(os.path.join(HERE, 'lizard_manifest.json')))
    mapping = manifest['weapon_map']
    changed, counts = 0, {}
    for path in sorted(glob.glob(os.path.join(DATA, '*.json'))):
        vid = os.path.basename(path)[:-5]
        data = json.load(open(path))
        if data.get('Type') == 'Airplane':
            continue
        touched = False
        for name, weapon in (data.get('Weapons') or {}).items():
            info = weapon.get('SoundInfo')
            if not isinstance(info, dict):
                continue
            current = info.get('Fire3P') or ''
            family = mapping.get(f'{vid}.{name}') or mapping.get(current)
            if family is None and current.startswith('bvp_audio:weapon/') and current.endswith('_3p'):
                family = current[len('bvp_audio:weapon/'):-3]
            if family is None:
                continue
            exists = lambda slot: os.path.exists(os.path.join(SOUNDS, f'{family}_{slot}.ogg'))
            if not exists('3p'):
                print(f'{vid}.{name}: family {family} not built; skipped')
                continue
            far = 'far' if exists('far') else '3p'
            very = 'veryfar' if exists('veryfar') else far
            info['Fire1P'] = f'bvp_audio:weapon/{family}_1p' if exists('1p') else f'bvp_audio:weapon/{family}_3p'
            info['Fire3P'] = f'bvp_audio:weapon/{family}_3p'
            info['Fire3PFar'] = f'bvp_audio:weapon/{family}_{far}'
            info['Fire3PVeryFar'] = f'bvp_audio:weapon/{family}_{very}'
            for g in ('Fire1PGain', 'Fire3PGain', 'Fire3PFarGain', 'Fire3PVeryFarGain'):
                info[g] = 1.0
            weapon['SoundRadius'] = RADIUS.get(family, weapon.get('SoundRadius', 16))
            counts[family] = counts.get(family, 0) + 1
            touched = True
        if touched:
            changed += 1
            if not dry:
                with open(path, 'w') as f:
                    json.dump(data, f, indent=2)
                    f.write('\n')
    print(f'{changed} vehicle files, {sum(counts.values())} weapons: ' +
          ', '.join(f'{k} {v}' for k, v in sorted(counts.items())))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
