#!/usr/bin/env python3
"""The owner's belt standard (2026-09-28) for every belted weapon wt_belts.py does not rebuild from WT tables
(aircraft and helicopter guns, machine guns).

    python3 tools/ballistics/belt_standard.py [--check]

Autocannons (20 mm and up), aircraft and helicopter guns with both HE and AP rounds, and any gun whose rounds include
the "air game"/"ground game" pair get two belts:

    Air Belt    (HE-dominant): 3x HE, no tracer, then 1x AP with tracer
    Ground Belt (AP-dominant): 3x the best penetrator (APFSDS > APDS > HVAP > AP), then 1x HE with tracer

A gun with rounds of only one kind gets one belt: 3x its main round untraced, then 1x a tracer round (the same round
traced when it has no tracer variant). Machine guns keep one belt: 3 rounds without tracer (their non-tracer rounds in
turn), then 1 tracer. Tracer colours are set by nation afterwards (tracer_nations.py; run order: wt_belts,
belt_standard, tracer_nations). Idempotent.
"""
import copy
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tracer_nations  # noqa: E402
import wt_belts as W  # noqa: E402

TRACER = re.compile(r'(^|_)(t|api_t|ap_t|apit|apt|hvapt|bzt\d*|fit|hei_t|hefi_t|hef_t|he_i_t|hedp_t|t_ball|ap_t_ball)'
                    r'(_|\d|$)|bzt44|_hvapt$|_apit$|_apt$', re.I)
DROP = ('ProjectileBelt', 'NominalBallistics')


def is_tracer(rid):
    return bool(TRACER.search(rid))


def kind(entry, rid):
    st = str((entry.get('Override') or {}).get('ShellType') or '').upper()
    if st == 'HE' or re.search(r'air_game$', rid):
        return 'HE'
    return 'AP'


def ap_rank(rid):
    r = rid.lower()
    for i, pat in enumerate(('apfsds', 'apds', 'hvap', 'apcr', 'ground_game', 'aphe', 'sap', 'ap')):
        if pat in r:
            return i
    return 99


def calibre(name, entries):
    """Gun calibre in mm: the projectile profiles' CaliberMm, else the display name ("7.62 mm", "7_62mm")."""
    for e in entries:
        try:
            c = json.load(open(W.profile_path(e['Override']['Projectile']['Profile'])))['Combat']
            if c.get('CaliberMm'):
                return float(c['CaliberMm'])
        except Exception:
            pass
    m = re.search(r'(\d+(?:[._]\d+)?)\s*mm', str(name))
    return float(m.group(1).replace('_', '.')) if m else 0.0


def plan(w, lang, airborne=False):
    at = w.get('AmmoType') or []
    pb = w.get('ProjectileBeltAmmoType') or []
    old = [e for e in at if (e.get('Override') or {}).get('ProjectileBelt')]
    if not old:
        return None
    entries = at + pb
    if any(not e.get('Ammo') for e in entries):
        return None
    rounds = []   # (identity, round id, kind, tracer?, label, colour)
    labels, colours = {}, {}
    for e in old:
        for r in e['Override']['ProjectileBelt'].get('Rounds', []):
            if r.get('Ammo'):
                labels.setdefault(r['Ammo'], r.get('Round'))
                if r.get('Tracer') not in (None, 'NONE', 'INHERIT'):
                    colours.setdefault(r['Ammo'], r['Tracer'])
    seen = set()
    for e in entries:
        ident = e['Ammo']
        if ident in seen:
            return None
        seen.add(ident)
        rid = W.round_id_of(e) or labels.get(ident) or ident
        rounds.append((ident, rid, kind(e, rid), is_tracer(rid), labels.get(ident) or rid, colours.get(ident)))
    name = lang.get(w.get('Name', ''), w.get('Name', ''))
    cal = calibre(name, entries)
    he = [r for r in rounds if r[2] == 'HE']
    ap = [r for r in rounds if r[2] == 'AP']
    composite = any(r[1].endswith('_game') for r in rounds)

    def pick(pool, prefer_tracer):
        want = [r for r in pool if r[3] == prefer_tracer]
        return (want or pool)[0]

    belts = []
    if (cal >= 20 or composite or airborne) and he and ap:
        he_main = pick(he, False)
        ap_t = pick(sorted(ap, key=lambda r: ap_rank(r[1])), True)
        best = sorted(ap, key=lambda r: (ap_rank(r[1]), r[3]))[0]
        he_t = pick(he, True)
        belts = [('Air Belt', [(he_main, 3, 'NONE'), (ap_t, 1, 'T')]),
                 ('Ground Belt', [(best, 3, 'T' if best[3] else 'NONE'), (he_t, 1, 'T')])]
    elif cal >= 20:
        pool = he or ap
        main = pick(pool, False)
        tr = pick(pool, True)
        belts = [('Air Belt' if he else 'Ground Belt', [(main, 3, 'NONE'), (tr, 1, 'T')])]
    else:
        plain, rids = [], set()
        for r in [r for r in rounds if not r[3]] or rounds:
            if r[1] not in rids:
                rids.add(r[1])
                plain.append(r)
        tracers = [r for r in rounds if r[3]] or rounds
        seq = [plain[i % len(plain)] for i in range(3)] if len(plain) != 2 else [plain[0], plain[1], plain[0]]
        bname = old[0]['Override']['ProjectileBelt'].get('Name') or old[0]['Override'].get('Name') or 'Belt'
        belts = [(bname, [(r, 1, 'NONE') for r in seq] + [(tracers[0], 1, 'T')])]
    return belts, rounds, old


def slot(r, n, t):
    colour = 'NONE' if t == 'NONE' else (r[5] or 'RED')
    return {'Shots': n, 'Round': r[4], 'Ammo': r[0], 'Tracer': colour}


def merge(slots):
    out = []
    for s in slots:
        if out and out[-1]['Ammo'] == s['Ammo'] and out[-1]['Tracer'] == s['Tracer']:
            out[-1]['Shots'] += s['Shots']
        else:
            out.append(dict(s))
    return out


def apply(w, belts, rounds, old):
    family = old[0]['Override']['ProjectileBelt'].get('Family')
    life = w.get('ProjectileLife', 40)
    by_id = {e['Ammo']: e for e in (w.get('AmmoType') or []) + (w.get('ProjectileBeltAmmoType') or [])}
    starts = []
    at = []
    for bname, spec in belts:
        start = spec[0][0][0]
        if start in starts:
            return False  # two belts would share a selector identity
        starts.append(start)
        e = copy.deepcopy(by_id[start])
        o = e.setdefault('Override', {})
        for k in DROP:
            o.pop(k, None)
        o['Name'] = bname
        pbelt = {'Name': bname}
        if family:
            pbelt['Family'] = family
        pbelt['Rounds'] = merge([slot(r, n, t) for r, n, t in spec])
        o['ProjectileBelt'] = pbelt
        proj = o.get('Projectile') or {}
        o['NominalBallistics'] = {'Supported': True, 'ProjectileType': proj.get('Type'),
                                  'ProjectileProfile': proj.get('Profile'), 'ProjectileLife': life}
        at.append(e)
    pb = []
    for ident, e in by_id.items():
        if ident in starts:
            continue
        e = copy.deepcopy(e)
        for k in DROP:
            (e.get('Override') or {}).pop(k, None)
        pb.append(e)
    w['AmmoType'] = at
    w['ProjectileBeltAmmoType'] = pb
    return True


def main(argv):
    check = '--check' in argv
    lang = json.load(open(os.path.join(W.REPO, 'bvp/src/generated/resources/assets/berts_vehicle_pack/lang/en_us.json')))
    changed = 0
    for path in sorted(glob.glob(os.path.join(W.SBW, 'vehicles', '*.json'))):
        vid = os.path.basename(path)[:-5]
        text = open(path).read()
        d = json.loads(text)
        before = json.dumps(d, sort_keys=True)
        notes = []
        for wn, w in (d.get('Weapons') or {}).items():
            if (vid, wn) in W.WEAPONS or not isinstance(w, dict):
                continue
            p = plan(w, lang, d.get('Type') in ('Airplane', 'Helicopter'))
            if not p:
                continue
            belts, rounds, old = p
            if apply(w, belts, rounds, old):
                notes.append(f"{wn}: " + ' / '.join(
                    f"{b}=" + '+'.join(f"{n}x{r[1]}{'(T)' if t == 'T' else ''}" for r, n, t in s) for b, s in belts))
        tracer_nations.fix(d, tracer_nations.east_of(os.path.join('vehicles', vid + '.json')), [])
        if json.dumps(d, sort_keys=True) != before:
            changed += 1
            print(f'{vid}: ' + '; '.join(notes))
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2, ensure_ascii=False) + ('\n' if text.endswith('\n') else ''))
    print(f"{changed} files {'would change' if check else 'changed'}")
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
