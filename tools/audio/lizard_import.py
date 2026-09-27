#!/usr/bin/env python3
"""Vehicle audio import (tools/audio/lizard_manifest.json -> bvp/src/main/resources/assets/bvp_audio).

  python3 tools/audio/lizard_import.py SOURCE_ROOT [--report]

SOURCE_ROOT holds the staged archive folders (objects_vehicles_client/vehicles/..., objects_weapons_client/weapons/...).

engine  one start_idle_stop WAV is cut at its embedded loop region (RIFF 'cue ' point + LIST/adtl 'ltxt' length, the
        engine's own markers) into <set>_start (up to the loop), <set>_idle (the loop, seamless) and <set>_stop (after
        it). Without a region the file is reported and skipped.
loop    tracks / turret: the cue region when there is one, else the whole file.
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


def duration(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path],
                         capture_output=True, text=True).stdout.strip()
    return float(out or 0)


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    root = argv[0]
    manifest = json.load(open(os.path.join(HERE, 'lizard_manifest.json')))
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
    print(f'{len(sounds)} sound events, {written} vehicle profiles')
    for p in problems:
        print('PROBLEM', p)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
