#!/usr/bin/env python3
"""Vehicle audio import (tools/audio/lizard_manifest.json -> bvp/src/main/resources/assets/bvp_audio).

  python3 tools/audio/lizard_import.py SOURCE_ROOT [--air-only]

SOURCE_ROOT holds the staged archive folders (objects_vehicles_client/vehicles/..., objects_weapons_client/weapons/...).

engine  one start_idle_stop WAV is cut at its embedded loop region (RIFF 'cue ' point + LIST/adtl 'ltxt' length, the
        engine's own markers) into <set>_start (up to the loop), <set>_idle (the loop, seamless) and <set>_stop (after
        it). Without a region the file is reported and skipped.
loop    tracks / turret: the cue region when there is one, else the whole file.
air     aircraft (manifest air_engine / air_layer / aircraft): engine sets cut like 'engine' (long start-ups keep
        their last seconds), layer loops (boost / distant / rotor / interior) loudness-matched, and one complete
        profile per aircraft entity type with its kind's defaults (AIR_KINDS) and its own pitch.
All outputs are mono (Minecraft only positions mono sources) Ogg Vorbis, peak-normalized per source file (the same
gain for all cuts of one file), written with sounds.json entries (stream false: short, decoded once) and the shared
vehicle audio profiles assets/bvp_audio/sbw/vehicle_audio/shared/<engine>__<tracks>__<turret>.json, plus one profile
per vehicle in assets/berts_vehicle_pack/sbw/vehicle_audio/<vehicle>.json that extends its shared set.
"""
import json, os, struct, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT = os.path.join(REPO, 'bvp', 'src', 'main', 'resources', 'assets')
NS = 'bvp_audio'


def wav_regions(path):
    """(sample_rate, frames, [(start, length)]) from a WAV's cue points and adtl ltxt lengths; ([] if none)."""
    with open(path, 'rb') as f:
        data = f.read()
    if data[:4] != b'RIFF' or data[8:12] != b'WAVE':
        return None
    rate = channels = bits = None
    frames = 0
    cues, lengths = {}, {}
    pos = 12
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack('<I', data[pos + 4:pos + 8])[0]
        body = data[pos + 8:pos + 8 + size]
        if cid == b'fmt ':
            _, channels, rate, _, _, bits = struct.unpack('<HHIIHH', body[:16])
        elif cid == b'data':
            frames = size // max(1, channels * bits // 8) if channels and bits else 0
        elif cid == b'cue ':
            n = struct.unpack('<I', body[:4])[0]
            for i in range(n):
                cue_id, position, _, _, _, sample_offset = struct.unpack('<II4sIII', body[4 + i * 24:28 + i * 24])
                cues[cue_id] = sample_offset if sample_offset else position
        elif cid == b'LIST' and body[:4] == b'adtl':
            p = 4
            while p + 8 <= len(body):
                sid = body[p:p + 4]
                ssize = struct.unpack('<I', body[p + 4:p + 8])[0]
                if sid == b'ltxt':
                    cue_id, length = struct.unpack('<II', body[p + 8:p + 16])
                    lengths[cue_id] = length
                p += 8 + ssize + (ssize & 1)
        pos += 8 + size + (size & 1)
    regions = sorted((cues[c], lengths[c]) for c in cues if c in lengths and lengths[c] > 0)
    return rate, frames, regions


def ffmpeg(args):
    subprocess.run(['ffmpeg', '-v', 'error', '-y'] + args, check=True)


def peak_gain_db(path, target=-1.0):
    out = subprocess.run(['ffmpeg', '-v', 'info', '-i', path, '-af', 'volumedetect', '-f', 'null', '-'],
                         capture_output=True, text=True).stderr
    for line in out.splitlines():
        if 'max_volume:' in line:
            return target - float(line.split('max_volume:')[1].split('dB')[0])
    return 0.0


def cut(src, dst, start, end, rate, gain_db, fade_ms=0):
    """Samples [start, end) of src -> mono ogg; end None = to the end of the file."""
    filters = [f'volume={gain_db:.2f}dB']
    if fade_ms:
        filters.append(f'afade=t=out:st={max(0.0, (end - start) / rate - fade_ms / 1000):.4f}:d={fade_ms / 1000:.3f}'
                       if end is not None else f'areverse,afade=t=in:d={fade_ms / 1000:.3f},areverse')
    trim = f'atrim=start_sample={start}' + (f':end_sample={end}' if end is not None else '') + ',asetpts=PTS-STARTPTS'
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    ffmpeg(['-i', src, '-af', ','.join([trim] + filters), '-ac', '1', '-c:a', 'libvorbis', '-q:a', '5', dst])


def pcm_mono(path):
    """(rate, float mono samples) of any audio file."""
    import numpy as np
    rate = int(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries',
                                'stream=sample_rate', '-of', 'csv=p=0', path], capture_output=True, text=True).stdout.strip())
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 's16le', '-ac', '1', '-'], capture_output=True).stdout
    return rate, np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0


def make_loop(src, dst, start, end, gain_db, fade_seconds=0.1):
    """A seamless loop from samples [start, end): the region's tail is cross-faded (equal power) into the samples
    just before its head, so the last sample leads straight into the first one. BF2 region markers are loop
    *regions*, not sample-exact loop points; played raw they click at the seam."""
    import numpy as np, wave
    rate, a = pcm_mono(src)
    end = len(a) if end is None else min(end, len(a))
    a = a * 10 ** (gain_db / 20)
    region = a[start:end]
    fade = int(min(fade_seconds * rate, len(region) // 4))
    if fade > 16:
        t = np.linspace(0, np.pi / 2, fade)
        head = region[:fade]
        body = region[fade:].copy()
        body[-fade:] = body[-fade:] * np.cos(t) + head * np.sin(t)
        region = body
    pcm = np.clip(region * 32767, -32768, 32767).astype(np.int16)
    tmp = dst + '.tmp.wav'
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with wave.open(tmp, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm.tobytes())
    ffmpeg(['-i', tmp, '-c:a', 'libvorbis', '-q:a', '5', dst])
    os.remove(tmp)


def pcm_frames(path, channels):
    """(rate, samples[frames, channels]) of any audio file."""
    import numpy as np
    rate = int(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries',
                                'stream=sample_rate', '-of', 'csv=p=0', path], capture_output=True, text=True).stdout.strip())
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 's16le', '-ac', str(channels), '-'],
                         capture_output=True).stdout
    a = np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0
    return rate, a.reshape(-1, channels)


def loudness_gain_db(path, start, end, target_rms_db, ceiling_db=-0.5):
    """Gain that brings samples [start, end) to target_rms_db RMS without the whole file peaking above ceiling_db."""
    import numpy as np
    _, a = pcm_mono(path)
    region = a[start:len(a) if end is None else end]
    rms = 20 * np.log10(np.sqrt(np.mean(region ** 2)) + 1e-9)
    peak = 20 * np.log10(np.max(np.abs(a)) + 1e-9)
    return min(target_rms_db - rms, ceiling_db - peak)


def write_loop(src, dst, start, end, gain_db, channels=1, fade_seconds=0.12, max_seconds=None):
    """make_loop for mono or stereo: region [start, end) (capped at max_seconds), tail cross-faded into the head."""
    import numpy as np, wave
    rate, a = pcm_frames(src, channels)
    end = len(a) if end is None else min(end, len(a))
    if max_seconds and end - start > max_seconds * rate:
        end = start + int(max_seconds * rate)
    region = a[start:end] * 10 ** (gain_db / 20)
    fade = int(min(fade_seconds * rate, len(region) // 4))
    if fade > 16:
        t = np.linspace(0, np.pi / 2, fade)[:, None]
        head = region[:fade]
        body = region[fade:].copy()
        body[-fade:] = body[-fade:] * np.cos(t) + head * np.sin(t)
        region = body
    pcm = np.clip(region * 32767, -32768, 32767).astype(np.int16)
    tmp = dst + '.tmp.wav'
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with wave.open(tmp, 'wb') as w:
        w.setnchannels(channels); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm.tobytes())
    ffmpeg(['-i', tmp, '-c:a', 'libvorbis', '-q:a', '5', dst])
    os.remove(tmp)


# ---------------------------------------------------------------------------------------------------------- aircraft
# Per-kind defaults of the generated aircraft profiles. Pitch pairs are [RPM 0, RPM 1] and scaled by the aircraft's
# "pitch"; volume pairs are [RPM 0, RPM 1].
AIR_KINDS = {
    'jet': {'startMax': 7.0, 'engine': {'range': 384, 'volume': 1.0, 'interiorVolume': 0.4, 'idlePitch': [0.72, 1.1],
                                        'idleVolume': [0.45, 1.0], 'spoolUp': 4.5, 'spoolDown': 3.0},
            'boost': {'range': 640, 'volume': [0.0, 1.0], 'pitch': [0.85, 1.05], 'from': 0.6},
            'distant': {'range': 1600, 'near': 110, 'volume': [0.6, 1.0], 'pitch': [0.88, 1.06]},
            'interior': {'volume': [0.5, 0.7], 'pitch': [0.95, 1.05]}},
    'prop': {'startMax': 5.0, 'engine': {'range': 320, 'volume': 1.0, 'interiorVolume': 0.55, 'idlePitch': [0.6, 1.12],
                                         'idleVolume': [0.55, 1.0], 'spoolUp': 1.2, 'spoolDown': 1.0},
             'distant': {'range': 900, 'near': 90, 'volume': [0.5, 1.0], 'pitch': [0.8, 1.08]},
             'interior': {'volume': [0.45, 0.6], 'pitch': [0.9, 1.08]}},
    'heli': {'startMax': 12.0, 'engine': {'range': 256, 'volume': 1.0, 'interiorVolume': 0.45, 'idlePitch': [0.93, 1.04],
                                          'idleVolume': [0.8, 1.0], 'spoolUp': 1.5, 'spoolDown': 1.5},
             'rotor': {'range': 512, 'volume': [0.75, 1.0], 'pitch': [0.97, 1.03]},
             'distant': {'range': 1100, 'near': 120, 'volume': [0.8, 1.0], 'pitch': [0.96, 1.03]},
             'interior': {'volume': [0.55, 0.65], 'pitch': [0.98, 1.02]}},
}
# RMS targets (dBFS) of the loop regions, so every aircraft sits at the same level before the profile volumes
AIR_LEVELS = {'engine': -14.0, 'boost': -16.0, 'distant': -14.0, 'rotor': -14.0, 'interior': -18.0}


def build_air(manifest, source, tmp, register, problems):
    """Cuts the air engine sets and layer loops, returns the per-aircraft profiles {entity id: profile}."""
    built = {}
    for key, spec in manifest.get('air_engine', {}).items():
        src = source(spec['src'])
        if not os.path.exists(src):
            problems.append(f'air engine {key}: missing {spec["src"]}')
            continue
        wav = src
        if not src.lower().endswith('.wav'):
            wav = os.path.join(tmp, 'air_' + key + '.wav')
            ffmpeg(['-i', src, wav])
        rate, frames, regions = wav_regions(wav)
        if not regions:
            problems.append(f'air engine {key}: no loop region in {spec["src"]}')
            continue
        loop_start, loop_len = max(regions, key=lambda r: r[1])
        loop_end = min(loop_start + loop_len, frames)
        gain = loudness_gain_db(wav, loop_start, loop_end, AIR_LEVELS['engine'])
        base = os.path.join(OUT, NS, 'sounds', 'air', key)
        # long start-ups (helicopter rotors run up for half a minute) keep only their last startMax seconds
        start_max = spec.get('startMax', AIR_KINDS[spec['kind']]['startMax'])
        start_from = max(0, loop_start - int(start_max * rate))
        filters = [f'atrim=start_sample={start_from}:end_sample={loop_start}', 'asetpts=PTS-STARTPTS',
                   f'volume={gain:.2f}dB']
        if start_from > 0:
            filters.append('afade=t=in:d=0.6')
        # the loops fade in over the last 40% of the start clip (VehicleAudioController); the clip hands over
        start_len = (loop_start - start_from) / rate
        tail = min(2.0, 0.3 * start_len)
        filters.append(f'afade=t=out:st={max(0.0, start_len - tail):.3f}:d={tail:.3f}')
        os.makedirs(os.path.dirname(base), exist_ok=True)
        ffmpeg(['-i', wav, '-af', ','.join(filters), '-ac', '1', '-c:a', 'libvorbis', '-q:a', '5', base + '_start.ogg'])
        write_loop(wav, base + '_idle.ogg', loop_start, loop_end, gain)
        has_stop = frames - loop_end > rate * 0.15
        if has_stop:
            cut(wav, base + '_stop.ogg', loop_end, None, rate, gain, fade_ms=120)
        for part in ('start', 'idle') + (('stop',) if has_stop else ()):
            register(f'air/{key}_{part}', f'air/{key}_{part}')
        built[key] = {'startSeconds': round((loop_start - start_from) / rate, 3), 'hasStop': has_stop}
        print(f'air engine {key:10s} start {(loop_start - start_from) / rate:5.2f}s loop {(loop_end - loop_start) / rate:5.2f}s '
              f'stop {max(0, frames - loop_end) / rate:5.2f}s gain {gain:+.1f} dB')

    layers = {}
    for key, spec in manifest.get('air_layer', {}).items():
        src = source(spec['src'])
        if not os.path.exists(src):
            problems.append(f'air layer {key}: missing {spec["src"]}')
            continue
        wav = src
        if not src.lower().endswith('.wav'):
            wav = os.path.join(tmp, 'airl_' + key + '.wav')
            ffmpeg(['-i', src, wav])
        rate, frames, regions = wav_regions(wav)
        start, end = 0, None
        if regions:
            s, l = max(regions, key=lambda r: r[1])
            if l > rate * 0.5:
                start, end = s, min(s + l, frames)
        role = spec['role']
        gain = loudness_gain_db(wav, start, end, AIR_LEVELS[role])
        channels = 2 if role == 'interior' else 1  # cockpit ambience stays stereo (heard at the listener)
        dst = os.path.join(OUT, NS, 'sounds', 'air', 'layer', key + '.ogg')
        write_loop(wav, dst, start, end, gain, channels, max_seconds=spec.get('maxSeconds', 30))
        register(f'air/layer/{key}', f'air/layer/{key}')
        layers[key] = role
        print(f'air layer  {key:18s} {role:8s} {"region" if end else "whole"} {duration(dst):5.2f}s gain {gain:+.1f} dB')

    def scaled(pair, factor):
        return [round(pair[0] * factor, 3), round(pair[1] * factor, 3)]

    profiles = {}
    for vehicle, spec in manifest.get('aircraft', {}).items():
        engine = spec['engine']
        if engine not in built:
            problems.append(f'aircraft {vehicle}: engine set {engine} was not built')
            continue
        kind = AIR_KINDS[spec['kind']]
        p = spec.get('pitch', 1.0)
        e = dict(kind['engine'])
        e.update({'start': f'{NS}:air/{engine}_start', 'idle': f'{NS}:air/{engine}_idle',
                  'startSeconds': built[engine]['startSeconds'], 'idlePitch': scaled(e['idlePitch'], p),
                  'drivePitch': [1.0, 1.0], 'driveVolume': [0.0, 0.0]})
        if built[engine]['hasStop']:
            e['stop'] = f'{NS}:air/{engine}_stop'
        e['volume'] = round(e['volume'] * spec.get('volume', 1.0), 3)
        profile = {'engine': e}
        for role in ('boost', 'distant', 'rotor', 'interior'):
            key = spec.get(role)
            if not key:
                continue
            if layers.get(key) != role:
                problems.append(f'aircraft {vehicle}: {role} layer {key} was not built')
                continue
            layer = dict(kind.get(role) or AIR_KINDS['jet' if role == 'boost' else 'heli'][role])
            layer['loop'] = f'{NS}:air/layer/{key}'
            if 'pitch' in layer and role != 'interior':
                layer['pitch'] = scaled(layer['pitch'], spec.get(role + 'Pitch', p))
            profile[role] = layer
        profiles[vehicle] = profile
    return profiles


def duration(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path],
                         capture_output=True, text=True).stdout.strip()
    return float(out or 0)


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    root = argv[0]
    air_only = '--air-only' in argv  # rebuild just the aircraft sets (Ogg output is not byte-reproducible)
    manifest = json.load(open(os.path.join(HERE, 'lizard_manifest.json')))
    if air_only:
        manifest = {k: v for k, v in manifest.items() if k.startswith('air') or k == 'aircraft'}
        manifest.update({'engine': {}, 'tracks': {}, 'turret': {}, 'vehicles': {}})
    sounds, meta, problems = {}, {}, []

    def source(rel):
        base = 'objects_weapons_client/weapons/' + rel[8:] if rel.startswith('weapons:') \
            else 'objects_vehicles_client/vehicles/' + rel
        return os.path.join(root, base)

    def register(name, sub):
        sounds[name] = {'category': 'neutral', 'sounds': [{'name': f'{NS}:{sub}', 'stream': False}]}

    with tempfile.TemporaryDirectory() as tmp:
        for key, rel in manifest['engine'].items():
            src = source(rel)
            if not os.path.exists(src):
                problems.append(f'engine {key}: missing {rel}')
                continue
            wav = src
            if not src.lower().endswith('.wav'):
                wav = os.path.join(tmp, key + '.wav')
                ffmpeg(['-i', src, wav])
            rate, frames, regions = wav_regions(wav)
            if not regions:
                problems.append(f'engine {key}: no loop region in {rel}')
                continue
            loop_start, loop_len = max(regions, key=lambda r: r[1])
            loop_end = min(loop_start + loop_len, frames)
            gain = peak_gain_db(wav)
            base = os.path.join(OUT, NS, 'sounds', 'engine', key)
            cut(wav, base + '_start.ogg', 0, loop_start, rate, gain)
            make_loop(wav, base + '_idle.ogg', loop_start, loop_end, gain)
            has_stop = frames - loop_end > rate * 0.15
            if has_stop:
                cut(wav, base + '_stop.ogg', loop_end, None, rate, gain, fade_ms=60)
            for part in ('start', 'idle') + (('stop',) if has_stop else ()):
                register(f'engine/{key}_{part}', f'engine/{key}_{part}')
            meta[key] = {'startSeconds': round(loop_start / rate, 3), 'idleSeconds': round((loop_end - loop_start) / rate, 3),
                         'hasStop': has_stop, 'gainDb': round(gain, 2)}
            print(f'engine {key:9s} start {loop_start / rate:5.2f}s idle {(loop_end - loop_start) / rate:5.2f}s '
                  f'stop {max(0, frames - loop_end) / rate:5.2f}s gain {gain:+.1f} dB')
        for section in ('tracks', 'turret'):
            for key, rel in manifest[section].items():
                src = source(rel)
                if not os.path.exists(src):
                    problems.append(f'{section} {key}: missing {rel}')
                    continue
                wav = src
                if not src.lower().endswith('.wav'):
                    wav = os.path.join(tmp, f'{section}_{key}.wav')
                    ffmpeg(['-i', src, wav])
                rate, frames, regions = wav_regions(wav)
                start, end = (0, None)
                if regions:
                    s, l = max(regions, key=lambda r: r[1])
                    if l > rate * 0.2:
                        start, end = s, min(s + l, frames)
                gain = peak_gain_db(wav)
                dst = os.path.join(OUT, NS, 'sounds', section, key + '.ogg')
                make_loop(wav, dst, start, end, gain, 0.08)
                register(f'{section}/{key}', f'{section}/{key}')
                print(f'{section:6s} {key:9s} {"region" if end else "whole"} {duration(dst):5.2f}s')

        # weapon families: [1p, 3p, far, veryfar]; automatic weapons keep one shot cycle plus a short faded tail
        for fam, spec in manifest.get('weapons', {}).items():
            for slot, rel in zip(('1p', '3p', 'far', 'veryfar'), spec['src']):
                if not rel:
                    continue
                src = source(rel)
                if not os.path.exists(src):
                    problems.append(f'weapon {fam} {slot}: missing {rel}')
                    continue
                wav = src
                if not src.lower().endswith('.wav'):
                    wav = os.path.join(tmp, f'w_{fam}_{slot}.wav')
                    ffmpeg(['-i', src, wav])
                rate, frames, regions = wav_regions(wav)
                gain = peak_gain_db(wav)
                dst = os.path.join(OUT, NS, 'sounds', 'weapon', f'{fam}_{slot}.ogg')
                end = None
                if spec.get('auto') and regions and slot in ('1p', '3p'):
                    cycle_end = max(s + l for s, l in regions)
                    end = min(frames, cycle_end + int(spec.get('tailSeconds', 0.55) * rate))
                channels = ['-ac', '1'] if slot != '1p' else []
                filters = [f'volume={gain:.2f}dB']
                if end is not None:
                    filters.insert(0, f'atrim=end_sample={end},asetpts=PTS-STARTPTS')
                    tail = (end - frames) if False else 0
                    filters.append(f'afade=t=out:st={max(0.0, end / rate - 0.35):.3f}:d=0.35')
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                ffmpeg(['-i', wav, '-af', ','.join(filters)] + channels + ['-c:a', 'libvorbis', '-q:a', '5', dst])
                register(f'weapon/{fam}_{slot}', f'weapon/{fam}_{slot}')
            print(f'weapon {fam:9s} ' + ' '.join(f'{sl}' for sl, r in zip(('1p', '3p', 'far', 'veryfar'), spec['src']) if r))

        air_profiles = build_air(manifest, source, tmp, register, problems)

    # sounds.json (this namespace is BVP-source-owned; the generator never writes it)
    path = os.path.join(OUT, NS, 'sounds.json')
    existing = json.load(open(path)) if os.path.exists(path) else {}
    existing.update(sounds)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(dict(sorted(existing.items())), f, indent=2)
        f.write('\n')

    # profiles: one shared set per (engine, tracks, turret) combination, one small file per vehicle
    tracked_range, written = 48, 0
    for vehicle, (engine, tracks, turret) in manifest['vehicles'].items():
        if engine not in meta:
            problems.append(f'vehicle {vehicle}: engine set {engine} was not built')
            continue
        m = meta[engine]
        shared = f'{engine}__{tracks or "none"}__{turret or "none"}'
        profile = {'engine': {
            'start': f'{NS}:engine/{engine}_start', 'idle': f'{NS}:engine/{engine}_idle',
            'startSeconds': m['startSeconds'], 'range': 112, 'volume': 0.9, 'interiorVolume': 0.55,
            'idlePitch': [1.0, 1.28], 'drivePitch': [1.0, 1.28], 'driveVolume': [0.0, 0.0]}}
        if m['hasStop']:
            profile['engine']['stop'] = f'{NS}:engine/{engine}_stop'
        if tracks:
            profile['tracks'] = {'loop': f'{NS}:tracks/{tracks}', 'range': tracked_range, 'volume': 0.75,
                                 'fullSpeed': 0.45}
        if turret:
            profile['turret'] = {'loop': f'{NS}:turret/{turret}', 'range': 28, 'volume': 0.6, 'fullRate': 24.0}
        shared_path = os.path.join(OUT, NS, 'sbw', 'vehicle_audio', 'shared', shared + '.json')
        os.makedirs(os.path.dirname(shared_path), exist_ok=True)
        with open(shared_path, 'w') as f:
            json.dump(profile, f, indent=2)
            f.write('\n')
        vehicle_path = os.path.join(OUT, 'berts_vehicle_pack', 'sbw', 'vehicle_audio', vehicle + '.json')
        os.makedirs(os.path.dirname(vehicle_path), exist_ok=True)
        with open(vehicle_path, 'w') as f:
            json.dump({'extends': f'{NS}:shared/{shared}'}, f, indent=2)
            f.write('\n')
        written += 1
    # aircraft: one complete profile per entity type (<namespace>:<path> -> assets/<namespace>/sbw/vehicle_audio/)
    for vehicle, profile in air_profiles.items():
        namespace, path = vehicle.split(':', 1)
        vehicle_path = os.path.join(OUT, namespace, 'sbw', 'vehicle_audio', path + '.json')
        os.makedirs(os.path.dirname(vehicle_path), exist_ok=True)
        with open(vehicle_path, 'w') as f:
            json.dump(profile, f, indent=2)
            f.write('\n')
        written += 1
    print(f'{len(sounds)} sound events, {written} vehicle profiles ({len(air_profiles)} aircraft)')
    for p in problems:
        print('PROBLEM', p)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
