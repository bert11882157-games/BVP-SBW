#!/usr/bin/env python3
"""Writes outlets.json into the asset vehicle JSONs.

dry: adds {"Schema":1, "Frame":"VEHICLE_LOCAL_BLOCKS", "Afterburning":false, "Outlets":[...]} (the dry-thrust heat
     haze; no flame particles) to jets that have no AfterburnerPresentation; an existing dry block is replaced.
fix: moves the listed afterburner outlets (matched by side and order) of afterburning jets; their TaP emitters are
     offsets from the outlet, so they follow.
Usage: python3 tools/engine_nozzles/write.py
"""
import json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '../../bvp/src/generated/resources/assets/berts_vehicle_pack/sbw/vehicles')


def expand(entries):
    out = []
    for e in entries:
        x, y, z, r = e[:4]
        out.append((x, y, z, r))
        if abs(x) > 1e-6:
            out.append((-x, y, z, r))
    return out


def block_span(text, key):
    m = re.search(r'\n( *)"%s": \{' % key, text)
    if not m:
        return None
    start = m.start() + 1
    depth = 0
    for i in range(m.end() - 1, len(text)):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                if text[end] == ',':
                    end += 1
                return start, end, m.group(1)
    raise ValueError(key)


def render(indent, key, value, comma):
    body = json.dumps(value, indent=2)
    body = body.replace('\n', '\n' + indent)
    return f'{indent}"{key}": {body}{"," if comma else ""}'


def main():
    table = json.load(open(os.path.join(HERE, 'outlets.json')))
    for vid, entries in sorted(table['dry'].items()):
        path = os.path.join(ASSETS, vid + '.json')
        text = open(path, encoding='utf-8').read()
        data = json.loads(text)
        old = data.get('AfterburnerPresentation')
        if old is not None and old.get('Afterburning', True):
            raise SystemExit(f'{vid} has a real afterburner block')
        outlets = [{'Id': f'engine_{i}', 'Position': [round(x, 4), round(y, 4), round(z, 4)], 'Direction': [0, 0, -1],
                    'NozzleRadiusBlocks': round(r, 3)} for i, (x, y, z, r) in enumerate(expand(entries))]
        block = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Afterburning': False, 'Outlets': outlets}
        span = block_span(text, 'AfterburnerPresentation')
        if span:
            start, end, indent = span
            text = text[:start] + render(indent, 'AfterburnerPresentation', block, text[end - 1] == ',') + text[end:]
        else:
            anchor = block_span(text, 'Model')
            start, end, indent = anchor
            text = text[:end] + '\n' + render(indent, 'AfterburnerPresentation', block, True) + text[end:]
        json.loads(text)
        open(path, 'w', encoding='utf-8').write(text)
    for vid, entries in sorted(table['fix'].items()):
        path = os.path.join(ASSETS, vid + '.json')
        text = open(path, encoding='utf-8').read()
        data = json.loads(text)
        ab = data['AfterburnerPresentation']
        targets = expand(entries)
        for outlet in ab['Outlets']:
            px = outlet['Position'][0]
            best = min(targets, key=lambda t: abs(t[0] - px))
            if abs(best[0] - px) > 0.7 or best[0] * px < 0:
                raise SystemExit(f'{vid}: no fix for outlet at x={px}')
            outlet['Position'] = [round(best[0], 4), round(best[1], 4), round(best[2], 4)]
            outlet['NozzleRadiusBlocks'] = round(best[3], 3)
        start, end, indent = block_span(text, 'AfterburnerPresentation')
        text = text[:start] + render(indent, 'AfterburnerPresentation', ab, text[end - 1] == ',') + text[end:]
        json.loads(text)
        open(path, 'w', encoding='utf-8').write(text)
    print(len(table['dry']), 'dry jets,', len(table['fix']), 'afterburner fixes')


if __name__ == '__main__':
    main()
