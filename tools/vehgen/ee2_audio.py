#!/usr/bin/env python3
"""Sound helpers of the Elite Expansion 2 wheeled builds (himars_build.py, scud_build.py, tigr_build.py).

One Lizardpyle engine set is cut the way tools/audio/lizard_import.py cuts every set (the start_idle_stop WAV at its own
cue loop region into <set>_start / _idle / _stop, mono Ogg, peak-normalized), for that set only: a full or --only run of
lizard_import re-cuts shared sets and Ogg output is not byte-reproducible (the strela_audio.py precedent). The shared
vehicle_audio profile and the per-vehicle profile are written as lizard_import writes them. TaP one-shots are copied
into berts_vehicle_pack:tap_fleet_shared with a sounds.json event inserted at an anchor (strela_audio.tap_engine).
"""
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(REPO, 'tools', 'audio'))
import lizard_import as L  # noqa: E402

LIZARD = r'F:\Users\a\Downloads\Lizardpyle'
TAP = (r'C:\Users\udstu\AppData\Roaming\.technic\modpacks\tyrants-and-plebeians-mk1\Flan'
       r'\Tyrants and Plebeians Redemption\assets\flansmod\sounds')
BVP_ASSETS = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack')


def source(rel):
    base = 'objects_weapons_client/weapons/' + rel[8:] if rel.startswith('weapons:') \
        else 'objects_vehicles_client/vehicles/' + rel
    return os.path.join(LIZARD, base)


def _sounds_json_update(entries):
    path = os.path.join(L.OUT, L.NS, 'sounds.json')
    existing = json.load(open(path, encoding='utf-8'))
    if all(k in existing for k in entries):
        return
    existing.update(entries)
    with open(path, 'w') as f:                       # the lizard_import writer (sorted, indent 2)
        json.dump(dict(sorted(existing.items())), f, indent=2)
        f.write('\n')


def engine_set(key, rel, rebuild=False):
    """Cuts one Lizardpyle start_idle_stop WAV into bvp_audio engine/<key>_start|_idle|_stop. Returns its meta."""
    base = os.path.join(L.OUT, L.NS, 'sounds', 'engine', key)
    src = source(rel)
    rate, frames, regions = L.wav_regions(src)
    if not regions:
        raise SystemExit(f'engine {key}: no loop region in {rel}')
    loop_start, loop_len = max(regions, key=lambda r: r[1])
    loop_end = min(loop_start + loop_len, frames)
    has_stop = frames - loop_end > rate * 0.15
    meta = {'startSeconds': round(loop_start / rate, 3), 'hasStop': has_stop}
    if rebuild or not os.path.exists(base + '_idle.ogg'):
        gain = L.peak_gain_db(src)
        L.cut(src, base + '_start.ogg', 0, loop_start, rate, gain)
        L.make_loop(src, base + '_idle.ogg', loop_start, loop_end, gain)
        if has_stop:
            L.cut(src, base + '_stop.ogg', loop_end, None, rate, gain, fade_ms=60)
        print(f'engine {key}: start {loop_start / rate:.2f} s, idle {(loop_end - loop_start) / rate:.2f} s, '
              f'stop {"yes" if has_stop else "no"}, gain {gain:+.1f} dB')
    entries = {}
    for part in ('start', 'idle') + (('stop',) if has_stop else ()):
        entries[f'engine/{key}_{part}'] = {'category': 'neutral',
                                           'sounds': [{'name': f'{L.NS}:engine/{key}_{part}', 'stream': False}]}
    _sounds_json_update(entries)
    return meta


def profiles(vid, engine, meta, tracks=None, turret=None):
    """The shared <engine>__<tracks>__<turret> profile (lizard_import's layout) and the vehicle's own profile."""
    shared = f'{engine}__{tracks or "none"}__{turret or "none"}'
    profile = {'engine': {
        'start': f'{L.NS}:engine/{engine}_start', 'idle': f'{L.NS}:engine/{engine}_idle',
        'startSeconds': meta['startSeconds'], 'range': 112, 'volume': 0.9, 'interiorVolume': 0.55,
        'idlePitch': [1.0, 1.28], 'drivePitch': [1.0, 1.28], 'driveVolume': [0.0, 0.0]}}
    if meta['hasStop']:
        profile['engine']['stop'] = f'{L.NS}:engine/{engine}_stop'
    if tracks:
        profile['tracks'] = {'loop': f'{L.NS}:tracks/{tracks}', 'range': 48, 'volume': 0.75, 'fullSpeed': 0.45}
    if turret:
        profile['turret'] = {'loop': f'{L.NS}:turret/{turret}', 'range': 28, 'volume': 0.6, 'fullRate': 24.0}
    shared_path = os.path.join(L.OUT, L.NS, 'sbw', 'vehicle_audio', 'shared', shared + '.json')
    if not os.path.exists(shared_path):
        with open(shared_path, 'w') as f:
            json.dump(profile, f, indent=2)
            f.write('\n')
    vp = os.path.join(L.OUT, 'berts_vehicle_pack', 'sbw', 'vehicle_audio', vid + '.json')
    with open(vp, 'w') as f:
        json.dump({'extends': f'{L.NS}:shared/{shared}'}, f, indent=2)
        f.write('\n')
    return shared


def tap_event(src_name, event, anchor='  "tap_t72engine": {'):
    """TaP <src_name> -> berts_vehicle_pack:tap_fleet_shared/<event without tap_>.ogg, event `event` in sounds.json."""
    fname = event[4:] + '.ogg'
    dst = os.path.join(BVP_ASSETS, 'sounds', 'tap_fleet_shared', fname)
    if not os.path.exists(dst):
        shutil.copyfile(os.path.join(TAP, src_name), dst)
    path = os.path.join(BVP_ASSETS, 'sounds.json')
    text = open(path, encoding='utf-8', newline='').read()
    if f'"{event}"' in text:
        return f'berts_vehicle_pack:{event}'
    nl = '\r\n' if '\r\n' in text else '\n'
    i = text.index(anchor)
    entry = (f'  "{event}": {{\n    "sounds": [\n      {{\n        "name": "berts_vehicle_pack:tap_fleet_shared/{event[4:]}",\n'
             f'        "stream": false,\n        "attenuation_distance": 96\n      }}\n    ]\n  }},\n').replace('\n', nl)
    open(path, 'w', encoding='utf-8', newline='').write(text[:i] + entry + text[i:])
    json.loads(open(path, encoding='utf-8').read())
    return f'berts_vehicle_pack:{event}'
