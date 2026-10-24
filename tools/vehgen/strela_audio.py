#!/usr/bin/env python3
"""Sounds of the 9K35 Strela-10M2 (strela_10m2).

    python3 tools/vehgen/strela_audio.py

  engine   TaP (tyrants-and-plebeians-mk1, Flan assets MTLBEngine / MTLBIdle: the MT-LB recording the TaP Strela-10 itself
           uses) copied to sounds/tap_fleet_shared/mtlbengine.ogg and mtlbidle.ogg, events tap_mtlbengine / tap_mtlbidle in
           the berts_vehicle_pack sounds.json, selected by CustomSoundProfileId tap_engine_mtlbengine__mtlbidle (the T-64BV
           route, BvpTankEngineSounds); the vehicle_audio profile (tracks and turret layers) extends bmp1__bmp1__bmp like
           the 9P149 (tools/audio/lizard_manifest.json vehicles.strela_10m2);
  launch   Lizardpyle stationary/igla_djigit igla_fire as the new weapon family `igla` (manifest weapons.igla, weapon_map
           strela_10m2.Missile), built here the way tools/audio/lizard_import.py builds a family (its helpers, one family only:
           a --only run would re-cut the shared BMP-1 engine set, and Ogg output is not byte-reproducible).
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(REPO, 'tools', 'audio'))
import lizard_import as L  # noqa: E402

VID = 'strela_10m2'
FAMILY = 'igla'
LIZARD = r'F:\Users\a\Downloads\Lizardpyle'
TAP = (r'F:\Users\a\AppData\Roaming\.technic\modpacks\tyrants-and-plebeians-mk1\Flan'
       r'\Tyrants and Plebeians Hero Shooter Update Final\assets\flansmod\sounds')
GEN = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources')
ASSETS = os.path.join(GEN, 'assets', 'berts_vehicle_pack')
DATA = os.path.join(GEN, 'data', 'berts_vehicle_pack', 'sbw', 'vehicles', VID + '.json')


def source(rel):
    base = 'objects_weapons_client/weapons/' + rel[8:] if rel.startswith('weapons:') \
        else 'objects_vehicles_client/vehicles/' + rel
    return os.path.join(LIZARD, base)


def build_family(manifest):
    spec = manifest['weapons'][FAMILY]
    sounds = {}
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        for slot, rel in zip(('1p', '3p', 'far', 'veryfar'), spec['src']):
            if not rel:
                continue
            src = source(rel)
            wav = src
            if not src.lower().endswith('.wav'):
                wav = os.path.join(tmp, f'w_{FAMILY}_{slot}.wav')
                L.ffmpeg(['-i', src, wav])
            gain = L.peak_gain_db(wav)
            dst = os.path.join(L.OUT, L.NS, 'sounds', 'weapon', f'{FAMILY}_{slot}.ogg')
            channels = ['-ac', '1'] if slot != '1p' else []
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            L.ffmpeg(['-i', wav, '-af', f'volume={gain:.2f}dB'] + channels + ['-c:a', 'libvorbis', '-q:a', '5', dst])
            sounds[f'weapon/{FAMILY}_{slot}'] = {'category': 'neutral',
                                                 'sounds': [{'name': f'{L.NS}:weapon/{FAMILY}_{slot}', 'stream': False}]}
            print('weapon', FAMILY, slot, dst)
    path = os.path.join(L.OUT, L.NS, 'sounds.json')
    existing = json.load(open(path, encoding='utf-8'))
    existing.update(sounds)
    with open(path, 'w') as f:                       # the lizard_import writer (sorted, indent 2)
        json.dump(dict(sorted(existing.items())), f, indent=2)
        f.write('\n')


def tap_engine():
    out = os.path.join(ASSETS, 'sounds', 'tap_fleet_shared')
    for src, dst in (('MTLBEngine.ogg', 'mtlbengine.ogg'), ('MTLBIdle.ogg', 'mtlbidle.ogg')):
        shutil.copyfile(os.path.join(TAP, src), os.path.join(out, dst))
        print('tap', dst)
    path = os.path.join(ASSETS, 'sounds.json')
    text = open(path, encoding='utf-8', newline='').read()
    if '"tap_mtlbengine"' in text:
        return
    nl = '\r\n' if '\r\n' in text else '\n'
    anchor = '  "tap_t72engine": {'
    i = text.index(anchor)
    entries = ''
    for ev, f in (('tap_mtlbengine', 'mtlbengine'), ('tap_mtlbidle', 'mtlbidle')):
        entries += (f'  "{ev}": {{\n    "sounds": [\n      {{\n        "name": "berts_vehicle_pack:tap_fleet_shared/{f}",\n'
                    f'        "stream": true,\n        "attenuation_distance": 64\n      }}\n    ]\n  }},\n').replace('\n', nl)
    open(path, 'w', encoding='utf-8', newline='').write(text[:i] + entries + text[i:])
    json.loads(open(path, encoding='utf-8').read())


def profile_and_data():
    # vehicle_audio profile (what lizard_import writes for a manifest vehicle: the shared set already exists)
    shared = os.path.join(L.OUT, L.NS, 'sbw', 'vehicle_audio', 'shared', 'bmp1__bmp1__bmp.json')
    assert os.path.exists(shared), shared
    vp = os.path.join(L.OUT, 'berts_vehicle_pack', 'sbw', 'vehicle_audio', VID + '.json')
    with open(vp, 'w') as f:
        json.dump({'extends': f'{L.NS}:shared/bmp1__bmp1__bmp'}, f, indent=2)
        f.write('\n')
    d = json.load(open(DATA, encoding='utf-8'))
    d['EngineSoundMode'] = 'CUSTOM'
    d['TrackSoundMode'] = 'NATIVE'
    d['CustomSoundProfileId'] = 'berts_vehicle_pack:tap_engine_mtlbengine__mtlbidle'
    d['EngineSound'] = 'berts_vehicle_pack:tank_engine_silent'
    w = d['Weapons']['Missile']
    info = w['SoundInfo']
    for slot, key in (('1p', 'Fire1P'), ('3p', 'Fire3P'), ('far', 'Fire3PFar'), ('veryfar', 'Fire3PVeryFar')):
        info[key] = f'{L.NS}:weapon/{FAMILY}_{slot}'
    for g in ('Fire1PGain', 'Fire3PGain', 'Fire3PFarGain', 'Fire3PVeryFarGain'):
        info[g] = 1.0
    w['SoundRadius'] = 16
    with open(DATA, 'w') as f:
        f.write(json.dumps(d, indent=2) + '\n')


def main():
    manifest = json.load(open(os.path.join(REPO, 'tools', 'audio', 'lizard_manifest.json'), encoding='utf-8'))
    build_family(manifest)
    tap_engine()
    profile_and_data()
    return 0


if __name__ == '__main__':
    sys.exit(main())
