"""Autocannon and machine-gun belts, tracers, velocities and HE filler as in War Thunder.

    python3 tools/ballistics/wt_belts.py [--check]

User rules (2026-09-27): War Thunder is the source for autocannon/HMG belts; tracers must have the right colour and be
in the right order; HE filler (grams of TNT) from WT; 30 mm HE flies at the gun's APDS velocity.

Source: the War Thunder datamine (gszabi99/War-Thunder-Datamine, gamedata/weapons/groundmodels_weapons/*.blkx: belt
bullet order, speed, explosiveMass/explosiveType, visual.tracer; config/gameparams.blkx tracerColors), TNT equivalent
= mass x strengthEquivalent from gamedata/damage_model/explosive.blkx (A-IX-2 1.54, Hexal 1.70, Torpex 1.60,
Octol 1.59, PETN 1.70, JHL-3 1.54), and wiki.warthunder.com unit pages for belt names. Research notes: docs/WT_BELTS.md.

For each listed weapon the belts are rebuilt from the WT belt list: one AmmoType entry per belt (its Ammo is the
belt's selector identity, so belts start at distinct rounds; a belt may start at any phase of its cycle), and one
ProjectileBeltAmmoType entry per round the belts use. Round entries keep their projectile profile and gameplay
fields; a round the weapon lacks is copied from another weapon that has it (profile file included). Tracer-only
fixes (12.7 mm and 7.62 mm families) edit the rounds in place. A second run changes nothing.

Tracer colours are then set by nation (tracer_nations.py, owner's rule 2026-09-28: Eastern tracers green, Western
red), so the WT colours above only decide which rounds are traced and, for the West, the shade of red.
"""
import copy
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tracer_nations  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
SBW = os.path.join(REPO, 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack', 'sbw')
POOL = ['superbwarfare:small_shell_ap', 'superbwarfare:small_shell_aa', 'superbwarfare:small_shell_gs',
        'superbwarfare:small_shell_he', 'superbwarfare:large_shell_ap']

# round id -> (tracer, WT muzzle velocity m/s, TNT equivalent kg or None when inert)
ROUNDS = {
    # 30 mm 2A42 / 2A72 / 2A38
    '3ubr6_ap_t': ('RED', 970, None), '3uof8_hef_i': ('NONE', 960, 0.0755), '3uor6_hef_t': ('BRIGHT_RED', 960, 0.0179),
    '3ubr8_apds': ('DARK_RED', 1120, None), '3ubr11_apfsds': ('GREEN', 1260, None),
    # 23 mm 2A7 / 2A14: BZT, OFZ, OFZT (the pack's second HE round is the OFZT)
    '23mm_apit': ('RED', 970, None), '23mm_hei': ('NONE', 980, 0.0285), '23mm_hei_2': ('LIGHT_RED', 970, 0.0200),
    # 25 mm M242 / M811
    'm791_apds': ('WHITE', 1345, None), 'm792_hei_t_1': ('RED', 1100, 0.0544), 'm919_apfsds': ('WHITE', 1385, None),
    'm811_m791_apds': ('WHITE', 1345, None), 'm811_m792_hei_t': ('RED', 1100, 0.0544),
    'm811_pmb090_apfsds': ('WHITE', 1385, None),
    # 20 mm Rh202
    'dm63_apds': ('LIGHT_RED', 1150, None), 'dm43_hvap_t': ('LIGHT_RED', 1100, None), 'dm51a1_hefi_t_1': ('LIGHT_RED', 1100, 0.01105),
    'dm51a1_hefi_t_2': ('LIGHT_RED', 1100, 0.01105),
    # 35 mm KDA
    'kda_35_api_t': ('LIGHT_RED', 1175, 0.0374), 'kda_35_hei_t': ('LIGHT_RED', 1175, 0.204),
    'kda_35_dm23_apds': ('LIGHT_RED', 1400, None),
    # 40 mm Bofors L/70 (Strf 90)
    'slpprj_m01_apfsds': ('WHITE', 1495, None), 'slsgr_m90_he': ('BRIGHT_RED', 988, 0.1744),
    # 30 mm ZPT-99
    'dtc04_30_apds': ('DARK_RED', 1180, None), 'dty02_30_hefi': ('NONE', 960, 0.0755),
    'dtc10_30_apfsds': ('RED', 1310, None),
}
# HE rounds of these guns fly at the gun's APDS velocity (user rule), not WT's
# weapons whose gun has the APDS round but no belt of it here (the BMP-2M fires APFSDS instead): same HE velocity
HE_AT_APDS_NO_BELT = {('bmp2m', 'Cannon')}
HE_AT_APDS = {'3uof8_hef_i': '3ubr8_apds', '3uor6_hef_t': '3ubr8_apds', 'dty02_30_hefi': 'dtc04_30_apds'}

# Owner's standard (2026-09-28): every autocannon has two belts.
#   Air Belt    (HE-dominant): 3x HE, no tracer, then 1x AP with tracer
#   Ground Belt (AP-dominant): 3x the gun's best penetrator (APFSDS > APDS > AP), then 1x HE with tracer
# The first belt is the default (SBW selects AmmoType[0]). Owner 2026-10-04: ground-vehicle autocannons default to
# the Ground Belt; the AA guns (AA_FIRST) keep the Air Belt first.
# A slot's third element forces its tracer: 'NONE' = untraced, 'T' = traced (the round's WT colour, red if WT has
# none; tracer_nations.py then sets green/red by nation). Without it the round's WT tracer is used.
def std(he, ap_t, best_ap, he_t):
    return [('Ground Belt', [(best_ap, 3), (he_t, 1, 'T')]),
            ('Air Belt', [(he, 3, 'NONE'), (ap_t, 1, 'T')])]


def air_first(belts):
    return [b for b in belts if b[0] == 'Air Belt'] + [b for b in belts if b[0] != 'Air Belt']


A42 = std('3uof8_hef_i', '3ubr6_ap_t', '3ubr8_apds', '3uor6_hef_t')
# the BMP-2M (Berezhok) Ground Belt is WT's all-APFSDS belt (owner: uniformly APFSDS)
A42M = [('Ground Belt', [('3ubr11_apfsds', 1)]), A42[1]]
# the BTR-90 also carries the BMP-2M's APFSDS belt (owner 2026-10-04), as its default
BTR90 = [('APFSDS Belt', [('3ubr11_apfsds', 1)])] + A42
A72 = A42
BMP3 = A42
A42_HELI = air_first(A42)
A38 = air_first(std('3uof8_hef_i', '3ubr6_ap_t', '3ubr6_ap_t', '3uor6_hef_t'))      # 2A38: no APDS
ZU23 = air_first(std('23mm_hei', '23mm_apit', '23mm_apit', '23mm_hei_2'))
M242 = std('m792_hei_t_1', 'm791_apds', 'm791_apds', 'm792_hei_t_1')
M242_LAV = std('m792_hei_t_1', 'm791_apds', 'm919_apfsds', 'm792_hei_t_1')
M811 = std('m811_m792_hei_t', 'm811_m791_apds', 'm811_pmb090_apfsds', 'm811_m792_hei_t')
RH202 = std('dm51a1_hefi_t_1', 'dm43_hvap_t', 'dm63_apds', 'dm51a1_hefi_t_1')
KDA = air_first(std('kda_35_hei_t', 'kda_35_api_t', 'kda_35_dm23_apds', 'kda_35_hei_t'))
BOFORS = std('slsgr_m90_he', 'slpprj_m01_apfsds', 'slpprj_m01_apfsds', 'slsgr_m90_he')
ZPT99 = std('dty02_30_hefi', 'dtc04_30_apds', 'dtc10_30_apfsds', 'dty02_30_hefi')

WEAPONS = {
    ('bmp2', 'Cannon'): ('TWO_A42', A42), ('bmp2m', 'Cannon'): ('TWO_A42', A42M),
    ('bmpt', 'Cannon'): ('TWO_A42', A42), ('bmpt', 'DualCannon'): ('TWO_A42', A42),
    ('btr_90', 'Cannon'): ('TWO_A42', BTR90),
    ('btr80a', 'Cannon'): ('TWO_A42', A72), ('bmp3m_elite', 'DualCannon'): ('TWO_A42', BMP3),
    ('9k22_tunguska', 'Cannon'): ('TWO_A42', A38),
    ('ka50', 'Cannon'): ('TWO_A42', A42_HELI), ('mi28n', 'Cannon'): ('TWO_A42', A42_HELI),
    ('zu23_2', 'Cannon'): ('ZU23', ZU23), ('zsu23_4', 'Cannon'): ('ZU23', ZU23),
    ('m2_bradley', 'Cannon'): ('M242', M242), ('lav25', 'Cannon'): ('M242', M242_LAV),
    ('vbci', 'Cannon'): ('M242', M811),
    ('marder_1a1', 'Cannon'): ('RH202', RH202), ('marder_1a2', 'Cannon'): ('RH202', RH202),
    ('marder_1a5', 'Cannon'): ('RH202', RH202),
    ('gepard', 'Cannon'): ('GENERIC', KDA), ('cv9040_no_net', 'Cannon'): ('GENERIC', BOFORS),
    ('qn_506model', 'Cannon'): ('GENERIC', ZPT99), ('zbd_09', 'Cannon'): ('GENERIC', ZPT99),
}
# tracer-only fixes, by round id: 12.7 mm BZT-44 is red in WT (the pack had green); PKT/Type 86 API-T red,
# PKTM (BMP-2M) pink; 14.5 mm KPVT BZT the same Soviet red composition as the 12.7 mm BZT-44
TRACER_ONLY = {
    'kpvt_api_t_1': 'RED', 'kpvt_api_t_2': 'RED',
    'russian_127_bzt44_api_t_1': 'RED', 'russian_127_bzt44_api_t_2': 'RED', 'qjc88a_bzt44_api_t': 'RED',
    'russian_762_ap_t': 'RED', 'type86_api_t': 'RED',
}
TRACER_ONLY_VEHICLE = {('bmp2m', 'MainMachineGun', 'russian_762_ap_t'): 'PINK'}
ENTRY_KEYS_DROP = ('ProjectileBelt', 'NominalBallistics')
# rounds WT has that no pack weapon had: built from a sibling round of the same gun with WT's combat values
SYNTH = {
    'dm43_hvap_t': ('dm63_apds', {'HullDamageClass': 'APCR', 'PenetrationMm': 57, 'PenetrationCurve': {
        'DistancesMetres': [10, 100, 500, 1000, 1500, 2000], 'PenetrationMm': [57, 52, 37, 24, 15, 10]}},
        {'Name': 'DM43'}),
    # WT BMP-2M "APFSDS": 1260 m/s, 0.1 kg, 102/99/94/88/81/75 mm, ricochet 78/80/81 deg
    '3ubr11_apfsds': ('3ubr8_apds', {'HullDamageClass': 'APFSDS', 'PenetrationMm': 102, 'PenetrationCurve': {
        'DistancesMetres': [10, 100, 500, 1000, 1500, 2000], 'PenetrationMm': [102, 99, 94, 88, 81, 75]},
        'RicochetCurve': {'IncidenceAnglesDegrees': [0, 78, 80, 81, 90], 'Probability': [0, 0, 0.5, 1, 1]}},
        {'Name': '3UBR11 APFSDS'}),
}


def profile_path(pid):
    return os.path.join(SBW, 'projectile_profiles', pid.split(':', 1)[1] + '.json')


def round_id_of(entry):
    pid = ((entry.get('Override') or {}).get('Projectile') or {}).get('Profile')
    if not pid or not os.path.exists(profile_path(pid)):
        return None
    return json.load(open(profile_path(pid))).get('Combat', {}).get('RoundId', '').split(':')[-1] or None


def catalogue():
    """round id -> (vehicle, weapon, entry) for every round entry of every weapon (a donor for missing rounds)."""
    out = {}
    for path in sorted(glob.glob(os.path.join(SBW, 'vehicles', '*.json'))):
        vid = os.path.basename(path)[:-5]
        d = json.load(open(path))
        for wn, w in (d.get('Weapons') or {}).items():
            for e in (w.get('ProjectileBeltAmmoType') or []) + (w.get('AmmoType') or []):
                rid = round_id_of(e)
                if rid and rid not in out:
                    out[rid] = (vid, wn, e)
    return out


def adopt(vid, wn, rid, donor, writes):
    """A round entry for this weapon copied from a donor weapon, with its profile file copied into this weapon."""
    dvid, dwn, dentry = donor
    e = copy.deepcopy(dentry)
    for k in ENTRY_KEYS_DROP:
        e.get('Override', {}).pop(k, None)
    src = e['Override']['Projectile']['Profile']
    name = f'belt_ammo_wt_{rid}'
    dst = f'berts_vehicle_pack:{vid}/{wn.lower()}/{name}'
    prof = json.load(open(profile_path(src)))
    prof['Combat']['WeaponId'] = f'berts_vehicle_pack:{vid}/{wn.lower()}'
    writes[profile_path(dst)] = prof
    e['Override']['Projectile']['Profile'] = dst
    return e


def match(order):
    """A distinct start round per belt (bipartite matching, preferring each belt's own first round); None where
    no distinct start exists."""
    best = [None] * len(order)
    best_n = [-1]

    def go(i, used, cur, n):
        if n + (len(order) - i) <= best_n[0]:
            return
        if i == len(order):
            best_n[0] = n
            best[:] = cur
            return
        for r in order[i]:
            if r not in used:
                go(i + 1, used | {r}, cur + [r], n + 1)
        go(i + 1, used, cur + [None], n)
    go(0, frozenset(), [], 0)
    return best


def rotate_for_start(rounds, start):
    """The belt's cycle rotated so that it begins at round `start` (same repeating order); slots are
    (round, shots, tracer override)."""
    seq = [(r, t) for r, n, t in rounds for _ in range(n)]
    i = next(k for k, (r, _) in enumerate(seq) if r == start)
    seq = seq[i:] + seq[:i]
    out = []
    for r, t in seq:
        if out and out[-1][0] == r and out[-1][2] == t:
            out[-1][1] += 1
        else:
            out.append([r, 1, t])
    return [tuple(x) for x in out]


def slot_tracer(r, t):
    """Tracer of one belt slot: the round's WT tracer, or the slot's forced one ('NONE' / 'T')."""
    own = ROUNDS[r][0]
    if t == 'NONE':
        return 'NONE'
    if t == 'T':
        return own if own != 'NONE' else 'RED'
    return own


def rebuild(vid, wn, w, family, belts, cat, writes, notes):
    entries = {}
    for e in (w.get('AmmoType') or []) + (w.get('ProjectileBeltAmmoType') or []):
        rid = round_id_of(e)
        if rid and rid not in entries:
            base = copy.deepcopy(e)
            for k in ENTRY_KEYS_DROP:
                base.get('Override', {}).pop(k, None)
            entries[rid] = base
    needed = []
    belts = [(b, [tuple(x) + (None,) * (3 - len(x)) for x in rounds]) for b, rounds in belts]
    for _, rounds in belts:
        for r, _, _ in rounds:
            if r not in needed:
                needed.append(r)
    for r, (base, combat, over) in SYNTH.items():
        if any(r in [x[0] for x in rs] for _, rs in belts) and r not in entries and base in entries:
            e = copy.deepcopy(entries[base])
            src = e['Override']['Projectile']['Profile']
            dst = f'berts_vehicle_pack:{vid}/{wn.lower()}/belt_ammo_wt_{r}'
            prof = copy.deepcopy(json.load(open(profile_path(src))))
            prof['Combat'].update(copy.deepcopy(combat))
            prof['Combat']['RoundId'] = f'berts_vehicle_pack:{r}'
            writes[profile_path(dst)] = prof
            e['Override']['Projectile']['Profile'] = dst
            e['Override'].update(over)
            e['Override']['ApDurability'] = combat.get('PenetrationMm', e['Override'].get('ApDurability'))
            e['Ammo'] = None
            entries[r] = e
            notes.append(f'{vid}.{wn}: built WT round {r} from {base}')
    usable = []
    for bname, rounds in belts:
        missing = [r for r, _, _ in rounds if r not in entries and r not in cat]
        if missing:
            notes.append(f'{vid}.{wn}: belt {bname} skipped, no profile anywhere for {missing}')
            continue
        usable.append((bname, rounds))
    for r in needed:
        if r not in entries and r in cat and any(r in [x[0] for x in rs] for _, rs in usable):
            entries[r] = adopt(vid, wn, r, cat[r], writes)
            notes.append(f'{vid}.{wn}: added round {r} (from {cat[r][0]}.{cat[r][1]})')
    # ammo identities: keep each round's current one when unique, else take a free one from the pool
    ammo, taken = {}, set()
    for r in needed:
        if r in entries:
            a = entries[r].get('Ammo')
            if a and a not in taken:
                ammo[r] = a
                taken.add(a)
    for r in needed:
        if r in entries and r not in ammo:
            free = [p for p in POOL if p not in taken]
            if not free:
                notes.append(f'{vid}.{wn}: no free ammo identity for {r}')
                continue
            ammo[r] = free[0]
            taken.add(free[0])
    # WT velocity, filler and the HE-at-APDS rule
    for r, e in entries.items():
        if r not in ROUNDS:
            continue
        _, ms, tnt = ROUNDS[r]
        apds = HE_AT_APDS.get(r)
        if apds and (apds in entries or (vid, wn) in HE_AT_APDS_NO_BELT):
            ms = ROUNDS[apds][1]
        o = e.setdefault('Override', {})
        o['Velocity'] = round(ms / 20.0, 3)
        if tnt is not None:
            o['TntEquivalentKg'] = tnt
        if r in ammo:
            e['Ammo'] = ammo[r]
    # belts: each starts at a round no other belt starts at (a belt may begin at any phase of its cycle); a
    # belt left without one gets an alias of its first round under a free ammo identity
    order = [[rounds[0][0]] + [x[0] for x in rounds if x[0] != rounds[0][0]] for _, rounds in usable]
    choice = match(order)
    at, starts, pb_extra = [], set(), []
    for i, (bname, rounds) in enumerate(usable):
        start = choice[i]
        if start is None:
            free = [p for p in POOL if p not in taken]
            if not free:
                notes.append(f'{vid}.{wn}: belt {bname} skipped, no ammo identity left for its start')
                continue
            alias = rounds[0][0] + '#' + bname
            entries[alias] = copy.deepcopy(entries[rounds[0][0]])
            ammo[alias] = free[0]
            taken.add(free[0])
            entries[alias]['Ammo'] = free[0]
            rounds = [(alias if r == rounds[0][0] else r, n, t) for r, n, t in rounds]
            start = alias
        starts.add(ammo[start])
        rot = rotate_for_start(rounds, start)
        e = copy.deepcopy(entries[start])
        o = e['Override']
        o['Name'] = bname
        o['ProjectileBelt'] = {'Name': bname, 'Family': family, 'Rounds': [
            {'Shots': n, 'Round': r.split('#')[0], 'Ammo': ammo[r], 'Tracer': slot_tracer(r.split('#')[0], t)}
            for r, n, t in rot]}
        o['NominalBallistics'] = {'Supported': True, 'ProjectileType': o['Projectile']['Type'],
                                  'ProjectileProfile': o['Projectile']['Profile'],
                                  'ProjectileLife': w.get('ProjectileLife', 40)}
        at.append(e)
    pb = [entries[r] for r in needed if r in entries and ammo.get(r) not in starts]
    w['AmmoType'] = at
    w['ProjectileBeltAmmoType'] = pb
    if at:
        # the weapon-level fallback velocity follows the first belt's first round
        w['Velocity'] = at[0]['Override']['Velocity']


def tracer_only(vid, wn, w):
    changed = False
    for e in (w.get('AmmoType') or []):
        b = (e.get('Override') or {}).get('ProjectileBelt')
        if not b:
            continue
        for r in b['Rounds']:
            ent = next((x for x in (w.get('AmmoType') or []) + (w.get('ProjectileBeltAmmoType') or [])
                        if x.get('Ammo') == r.get('Ammo')), None)
            rid = round_id_of(ent) if ent else None
            want = TRACER_ONLY_VEHICLE.get((vid, wn, rid), TRACER_ONLY.get(rid))
            if want and r.get('Tracer') != want:
                r['Tracer'] = want
                changed = True
    return changed


def main(argv):
    check = '--check' in argv
    cat = catalogue()
    report, writes = [], {}
    for path in sorted(glob.glob(os.path.join(SBW, 'vehicles', '*.json'))):
        vid = os.path.basename(path)[:-5]
        text = open(path).read()
        d = json.loads(text)
        before = json.dumps(d, sort_keys=True)
        notes = []
        for wn, w in (d.get('Weapons') or {}).items():
            if (vid, wn) in WEAPONS:
                family, belts = WEAPONS[(vid, wn)]
                rebuild(vid, wn, w, family, belts, cat, writes, notes)
            else:
                tracer_only(vid, wn, w)
        tracer_nations.fix(d, tracer_nations.east_of(os.path.join('vehicles', vid + '.json')), [])
        if json.dumps(d, sort_keys=True) != before:
            report.append(f'{vid}: ' + ('; '.join(notes) if notes else 'belts/tracers updated'))
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else ''))
        elif notes:
            report.append(f'{vid}: ' + '; '.join(notes))
    for p, prof in writes.items():
        east = tracer_nations.east_of(os.path.relpath(p, SBW))
        if east is not None:
            tracer_nations.fix(prof, east, [])
        if not os.path.exists(p) or json.load(open(p)) != prof:
            report.append(f'profile {os.path.relpath(p, SBW)}')
            if not check:
                os.makedirs(os.path.dirname(p), exist_ok=True)
                open(p, 'w').write(json.dumps(prof, indent=2) + '\n')
    print('\n'.join(report) or 'nothing to change')
    return 1 if (check and report) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
