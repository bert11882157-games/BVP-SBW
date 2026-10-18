"""War Thunder belts for every helicopter gun: the built-in guns and the gun pods (build.py applies them).

wt_guns.json (extract_wt.py) holds, per gun, War Thunder's belts in firing order (gamedata/weapons/<gun>.blkx: the
top-level `bullet` list is the default belt, each `<gun>_<purpose>` block a named belt), which of them the helicopter
has (the unit's flightmodels/<unit>.blkx `modifications`) and every round's numbers. For each gun this module writes

  * one AmmoType entry per WT belt, in WT's order (Default first), named as in the game (units_modifications.csv
    short names, else the generic purpose name), with the belt's exact round sequence (ProjectileBelt);
  * one ProjectileBeltAmmoType entry per round no belt is selected by;
  * one projectile profile per round: WT calibre, muzzle velocity, TNT equivalent (filler x WT strength equivalent,
    20 mm and up), penetration at 10/100/500/1000/1500/2000 m (WT's de Marre formula with drag for kinetic rounds:
    P = K * 100 * V^1.43 * M^0.71 / (1900^1.43 * (d / 100 mm)^1.07), the round slowing by its Cx; WT's armour power
    table for the rifle-calibre rounds that have one; HEDP: its cumulative jet), and the damage numbers of
    tools/damage/balance.py, so balance.py leaves them as they are.

Belt selectors: each belt's AmmoType `Ammo` is one of its own rounds' identities, distinct per belt; a belt with no
round of its own left is selected by an alias identity no round uses. A round's identity is unique on the weapon, so a
belt slot always resolves to that round's profile. Tracers: WT's traced rounds are traced, the others are not; the
colour follows the owner's nation rule (tools/ballistics/tracer_nations.py: Eastern green, Western red shades).
Owner's rule kept from tools/ballistics/autocannon_he_velocity.py: 30 mm HE flies at the gun's APDS velocity when the
helicopter has the APDS round. Gameplay fields the WT data has no counterpart for (Damage, ExplosionRadius, HE
ApDurability, ShootShake, the inventory item) keep the pack's values for that gun.
"""
import copy
import importlib.util
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'ballistics'))
import tracer_nations  # noqa: E402

NS = 'berts_vehicle_pack'
WT = json.load(open(os.path.join(HERE, 'wt_guns.json')))
DISTANCES = [10, 100, 500, 1000, 1500, 2000]
RHO = 1.225
POOL = ['superbwarfare:small_shell_ap', 'superbwarfare:small_shell_he', 'superbwarfare:small_shell_aa',
        'superbwarfare:small_shell_gs', 'superbwarfare:large_shell_ap', 'superbwarfare:large_shell_he',
        'superbwarfare:large_shell_gs', 'superbwarfare:large_shell_cm', 'superbwarfare:large_shell_wp']
WT_TRACER = {'Red': 'RED', 'DarkRed': 'DARK_RED', 'BrightRed': 'BRIGHT_RED', 'LightRed': 'LIGHT_RED',
             'White': 'WHITE', 'Green': 'GREEN', 'Pink': 'PINK'}


def _balance():
    spec = importlib.util.spec_from_file_location('bvp_damage_balance',
                                                  os.path.join(HERE, '..', 'damage', 'balance.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


BALANCE = _balance()

P = f'{NS}:'
YAKB_AP = 'mi24v/cannon/belt_ammo_00_yakb_ap'
YAKB_T = 'aircraft_rounds/yakb/yakb_api_t'
YAKB_HE = 'aircraft_rounds/yakb/yakb_iai'
# gun -> per WT bulletType: (RoundId, profile the visuals are copied from); the pack's gameplay fields per kind;
# the inventory item a shot uses; the belt family (presentation only)
GUNS = {
    'cannon2a42': {
        'rounds': {'ap_t': ('3ubr6_ap_t', 'ka50/cannon/belt_ammo_00_3ubr6_ap_t'),
                   'he_frag_i': ('3uof8_hef_i', 'ka50/cannon/belt_ammo_01_3uof8_hef_i'),
                   'apds_autocannon': ('3ubr8_apds', 'ka50/cannon/ammo_00_3ubr8_apds'),
                   'he_frag_t': ('3uor6_hef_t', 'ka50/cannon/ammo_01_3uor6_hef_t')},
        'ap': {'Damage': 27}, 'he': {'Damage': 250, 'ExplosionRadius': 5, 'ApDurability': 8},
        'item': 'superbwarfare:medium_autocannon_shell', 'family': 'TWO_A42'},
    'cannonm230e1': {
        'rounds': {'he_dp': ('m230e1_hedp', 'aircraft_rounds/m230e1/m230e1_hedp')},
        'he': {'Damage': 78.33457777777778, 'ExplosionRadius': 1.5666915555555554, 'ApDurability': 51},
        'item': 'superbwarfare:medium_autocannon_shell'},
    'cannonm197': {
        'rounds': {'he_i_t': ('m197_hei_t', 'aircraft_rounds/m197/m197_hei_t'),
                   'he_frag_i': ('m197_hef_i', 'aircraft_rounds/m197/m197_hef_i'),
                   'ap_i': ('m197_ap_i', 'aircraft_rounds/m197/m197_ap_i')},
        'ap': {'Damage': 45}, 'he': {'Damage': 166.66666666666666, 'ExplosionRadius': 3.333333333333333,
                                     'ApDurability': 2},
        'item': 'superbwarfare:small_autocannon_shell'},
    'cannonm195': {
        'rounds': {'ap_i_t': ('m195_api_t', 'ah_1g_cobra/cannon/belt_ammo_00_m195_api_t'),
                   'he_frag_i': ('m195_hef_i', 'ah_1g_cobra/cannon/belt_ammo_01_m195_hef_i')},
        'ap': {'Damage': 22}, 'he': {'Damage': 250, 'ExplosionRadius': 5, 'ApDurability': 5},
        'shake': [8, 10, 12], 'item': 'superbwarfare:large_machine_gun_ammunition'},
    'cannongsh_23l': {
        'rounds': {'he_frag_i_t': ('gsh23_hefi_t', 'aircraft_rounds/gsh23l/gsh23_hefi'),
                   'ap_i': ('gsh23_api', 'aircraft_rounds/gsh23l/gsh23_api'),
                   'he_frag_i': ('gsh23_hefi', 'aircraft_rounds/gsh23l/gsh23_hefi')},
        'ap': {'Damage': 45}, 'he': {'Damage': 191.66666666666669, 'ExplosionRadius': 3.8333333333333335,
                                     'ApDurability': 4},
        'item': 'superbwarfare:small_autocannon_shell'},
    'gunyak_b': {
        'rounds': {'ap_i_ball': ('yakb_api', YAKB_AP), 'ap_i_t_ball': ('yakb_api_t', YAKB_T),
                   'he_i_ball': ('yakb_iai', YAKB_HE), 'ap_ball': ('yakb_ap', YAKB_AP)},
        'ap': {'Damage': 22}, 'he': {'Damage': 83.33333333333333, 'ExplosionRadius': 0, 'ApDurability': 3},
        'shake': [8, 10, 6], 'item': 'superbwarfare:medium_machine_gun_ammunition',
        # the aircraft-style guns (mi_24d's YaK-B, mi_24a's A-12.7) keep the fixed-wing guns' AP damage, no shake
        'aircraft': {'ap': {'Damage': 8}, 'shake': None}},
    'guna_12_7': {
        'rounds': {'ap_i_ball': ('a127_api', YAKB_AP), 'ap_i_t_ball': ('a127_api_t', YAKB_T),
                   'he_i_ball': ('a127_iai', YAKB_HE), 'ap_ball': ('a127_ap', YAKB_AP)},
        'ap': {'Damage': 8}, 'he': {'Damage': 83.33333333333333, 'ExplosionRadius': 0, 'ApDurability': 3},
        'item': 'superbwarfare:medium_machine_gun_ammunition'},
    'gunyak_b_guv': {
        'rounds': {'ap_ball': ('yakb_guv_ap', YAKB_AP), 'ap_i_t_ball': ('yakb_api_t', YAKB_T)},
        'ap': {'Damage': 22}, 'shake': [8, 10, 6], 'item': 'superbwarfare:medium_machine_gun_ammunition'},
    'gunfn_m3p': {
        'rounds': {'ap_i_t_ball_M20': ('m3p_m20_api_t', YAKB_T), 'ap_ball_M2': ('m3p_m2_ap', YAKB_AP),
                   'i_ball_M1': ('m3p_m1_i', YAKB_AP), 'ap_i_ball_M8': ('m3p_m8_api', YAKB_AP),
                   'i_ball_M23': ('m3p_m23_i', YAKB_AP)},
        'ap': {'Damage': 22}, 'shake': [8, 10, 6], 'item': 'superbwarfare:medium_machine_gun_ammunition'},
    'gungau_19': {
        'rounds': {'slap': ('gau19_slap', YAKB_AP), 'slap_t': ('gau19_slap_t', YAKB_T)},
        'ap': {'Damage': 22}, 'shake': [8, 10, 6], 'item': 'superbwarfare:medium_machine_gun_ammunition'},
    'gunm134_minigun': {
        'rounds': {'ap_ball': ('m134_ap', 'ah_6j/cannon/ammo_00_m134_ap'),
                   't_ball': ('m134_t', 'ah_6j/cannon/belt_ammo_00_m134_t')},
        'ap': {'Damage': 22}, 'shake': [0, 0, 0], 'item': 'superbwarfare:small_machine_gun_ammunition'},
}
GUNS['cannongsh_23l_upk_23_250'] = GUNS['cannongsh_23l']
GUNS['gunfn_m3p_cwp'] = GUNS['gunfn_m3p']

# built-in guns: (vehicle, weapon) -> WT gun
BUILTIN = {('ah_64d', 'M230'): 'cannonm230e1', ('ah_1f', 'PassengerGun1'): 'cannonm197',
           ('ka50', 'Cannon'): 'cannon2a42', ('mi28n', 'Cannon'): 'cannon2a42', ('mi24v', 'Cannon'): 'gunyak_b',
           ('mi_24d', 'PassengerGun1'): 'gunyak_b', ('mi_24a', 'PassengerGun1'): 'guna_12_7'}
# gun pods: build.py family -> (WT gun candidates, shared profile folder under aircraft_rounds/)
PODS = {'m134': (['gunm134_minigun'], 'm134'), 'm195': (['cannonm195'], 'm195'), 'gau19': (['gungau_19'], 'gau19'),
        'm3p': (['gunfn_m3p'], 'fn_m3p'), 'rmp': (['gunfn_m3p_cwp'], 'fn_m3p'),
        'gsh23': (['cannongsh_23l_upk_23_250', 'cannongsh_23l'], 'gsh23l'), 'guv': (['gunyak_b_guv'], 'yakb')}


def is_he(r):
    return 'explosiveMass' in r and r['bulletType'].startswith('he')


def calibre_mm(r):
    return round(r['caliber'] * 1000, 2)


def demarre_mm(r, v):
    k = r['demarre']
    d = (r.get('damageCaliber') or r['caliber']) * 1000 / 100.0
    m = r.get('damageMass') or r['mass']
    return (k['demarrePenetrationK'] * 100 * v ** k.get('demarreSpeedPow', 1.43) * m ** k.get('demarreMassPow', 0.71)
            / (1900 ** k.get('demarreSpeedPow', 1.43) * d ** k.get('demarreCaliberPow', 1.07)))


def table_mm(r, x):
    t = r['armorpower']
    if x <= t[0][0]:
        return t[0][1]
    for (x0, p0), (x1, p1) in zip(t, t[1:]):
        if x <= x1:
            return p0 + (p1 - p0) * (x - x0) / (x1 - x0)
    return t[-1][1]


def penetration(r):
    """WT penetration (mm) at DISTANCES, or None for an HE round without a jet (balance.py sets those)"""
    if 'cumulativeMm' in r:
        return [float(r['cumulativeMm'])] * len(DISTANCES)
    if 'demarre' in r and not is_he(r):
        bc = r.get('ballisticCaliber') or r['caliber']
        k = 0.5 * RHO * r['Cx'] * math.pi * bc * bc / 4 / r['mass']
        return [int(round(demarre_mm(r, r['speed'] * math.exp(-k * x)))) for x in DISTANCES]
    if 'armorpower' in r:
        return [round(table_mm(r, x), 2) for x in DISTANCES]
    return None


def gun_rounds(gun, belts):
    """WT round data of the rounds the given belts use, in first-use order"""
    g = WT['guns'][gun]
    out = []
    for b in belts:
        for t in g['belts'][b]:
            if t not in out:
                out.append(t)
    return [(t, g['rounds'][t]) for t in out]


class Gun:
    """one helicopter gun's WT belts, resolved for the weapon it is written into"""

    def __init__(self, vid, gun, folder, weapon_id, weapon):
        self.vid, self.gun, self.folder, self.weapon_id = vid, gun, folder, weapon_id
        self.cfg = GUNS[gun]
        self.belts = WT['units'][vid]['guns'][gun]
        # the fixed-wing gun physics (Gravity 0.05, 400-tick life) fly at m/s / 16, the ground-gun physics at m/s / 20
        self.aircraft = weapon.get('Gravity') == 0.05 and weapon.get('ProjectileLife') == 400
        self.scale = 16.0 if self.aircraft else 20.0
        self.life = weapon.get('ProjectileLife', 40)
        self.east = tracer_nations.east_of(os.path.join('projectile_profiles', folder, 'x.json'))
        self.rounds = gun_rounds(gun, self.belts)
        apds = [r['speed'] for _, r in self.rounds if r['bulletType'] == 'apds_autocannon']
        self.he_speed = apds[0] if apds and calibre_mm(self.rounds[0][1]) == 30 else None

    def game(self, kind):
        over = self.cfg.get('aircraft', {}) if self.aircraft else {}
        return dict(over.get(kind, self.cfg.get(kind, {})))

    def shake(self):
        over = self.cfg.get('aircraft', {}) if self.aircraft else {}
        return over['shake'] if 'shake' in over else self.cfg.get('shake')

    def profile_id(self, t):
        return f'{self.folder}/{self.prefix()}{self.cfg["rounds"][t][0]}'

    def prefix(self):
        return 'wt_' if self.folder.startswith('aircraft_rounds/') else 'belt_ammo_wt_'

    def profile(self, t, r, data_root):
        rid, base = self.cfg['rounds'][t]
        prof = copy.deepcopy(json.load(open(os.path.join(data_root, 'projectile_profiles', base + '.json'))))
        c = prof['Combat']
        he = is_he(r)
        cal = calibre_mm(r)
        pen = penetration(r)
        c['WeaponId'] = P + self.weapon_id
        c['RoundId'] = P + rid
        c['CaliberMm'] = c['DiameterMm'] = int(cal) if cal == int(cal) else cal
        c['DamageType'] = P + ('chemical' if he else 'kinetic')
        c['HullDamageClass'] = 'HE' if he else ('APDS' if t in ('apds_autocannon', 'slap', 'slap_t') else 'DEFAULT')
        c.pop('HeAspect', None)
        p = pen if pen is not None else [0.0] * len(DISTANCES)
        c['PenetrationMm'] = p[0]
        c['PenetrationCurve'] = {'DistancesMetres': list(DISTANCES), 'PenetrationMm': list(p)}
        rule = BALANCE.rules(c, rid, self.profile_id(t))
        if rule:
            hull, module, rack, new_pen, tandem, _ = rule
            c['HullDamage'], c['ModuleDamage'], c['AmmoRackDamage'] = hull, module, rack
            if new_pen is not None:
                c['PenetrationMm'] = new_pen
                c['PenetrationCurve']['PenetrationMm'] = [new_pen] * len(DISTANCES)
        trail = (prof.get('Extensions', {}).get(f'{NS}:projectile_effect_v1') or {}).get('Trail') or {}
        if not r.get('tracer'):
            trail.pop('Tracer', None)
        tracer_nations.fix(prof, self.east, [])
        return prof

    def tracer(self, r):
        tr = r.get('tracer')
        if not tr:
            return 'NONE'
        colour = next((v for k, v in sorted(WT_TRACER.items(), key=lambda kv: -len(kv[0])) if tr.endswith(k)), 'RED')
        if self.east:
            return 'GREEN'
        return colour if colour in tracer_nations.REDS else 'RED'

    def entry(self, t, r):
        he = is_he(r)
        cal = calibre_mm(r)
        speed = self.he_speed if he and self.he_speed else r['speed']
        o = {'Projectile': {'Type': 'superbwarfare:small_cannon_shell', 'Profile': P + self.profile_id(t)},
             'ShellType': 'HE' if he else 'AP', 'Velocity': round(speed / self.scale, 4)}
        g = self.game('he' if he else 'ap')
        pen = penetration(r)
        o['ApDurability'] = g.pop('ApDurability') if he else int(round(pen[0])) if pen else 0
        o['Damage'] = g.pop('Damage', 22)
        o['ExplosionDamage'] = 0
        o['ExplosionRadius'] = g.pop('ExplosionRadius', 0) if he and cal >= 20 else 0
        if he and cal >= 20 and 'tntKg' in r:
            o['TntEquivalentKg'] = round(r['tntKg'], 4)
        o['Name'] = WT['round_names'].get(t, t)
        if self.shake() is not None:
            o['ShootShake'] = list(self.shake())
        return {'Ammo': None, 'ConsumptionItem': self.cfg['item'], 'Override': o}

    def apply(self, w, data_root, writes):
        """rewrites w's AmmoType / ProjectileBeltAmmoType with the WT belts; profiles go into `writes`"""
        rounds = dict(self.rounds)
        order = [t for t, _ in self.rounds]
        ident = {t: POOL[i] for i, t in enumerate(order)}
        entries = {}
        for t in order:
            e = self.entry(t, rounds[t])
            e['Ammo'] = ident[t]
            entries[t] = e
            writes[self.profile_id(t)] = self.profile(t, rounds[t], data_root)
        g = WT['guns'][self.gun]
        seqs = [(WT['belt_names'].get(b, b), g['belts'][b]) for b in self.belts]
        # a distinct selector per belt: one of its own rounds (WT's first round preferred), else an alias
        choice = match([[s[0]] + [x for x in dict.fromkeys(s) if x != s[0]] for _, s in seqs])
        spare = iter(POOL[len(order):])
        at, used = [], set()
        for (name, seq), start in zip(seqs, choice):
            if start is None:
                e = copy.deepcopy(entries[seq[0]])
                e['Ammo'] = next(spare)
            else:
                e = copy.deepcopy(entries[start])
                used.add(start)
            o = e['Override']
            o['Name'] = name
            slots = []
            for t in seq:
                tr = self.tracer(rounds[t])
                if slots and slots[-1]['Ammo'] == ident[t] and slots[-1]['Tracer'] == tr:
                    slots[-1]['Shots'] += 1
                else:
                    slots.append({'Shots': 1, 'Round': self.cfg['rounds'][t][0], 'Ammo': ident[t], 'Tracer': tr})
            o['ProjectileBelt'] = {'Name': name, 'Family': self.cfg.get('family', 'GENERIC'), 'Rounds': slots}
            o['NominalBallistics'] = {'Supported': True, 'ProjectileType': o['Projectile']['Type'],
                                      'ProjectileProfile': o['Projectile']['Profile'], 'ProjectileLife': self.life}
            at.append(e)
        w['AmmoType'] = at
        w['ProjectileBeltAmmoType'] = [entries[t] for t in order if t not in used]
        w['Velocity'] = at[0]['Override']['Velocity']
        w['BeltFed'] = True
        tracer_nations.fix(w, self.east, [])


def match(order):
    """a distinct start round per belt, as many belts as possible, each preferring its own first round (the same
    bipartite search as tools/ballistics/wt_belts.py); None where a belt has none left"""
    best, best_n = [None] * len(order), [-1]

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


def builtin_gun(vid, wn, weapon):
    gun = BUILTIN.get((vid, wn))
    if not gun or gun not in WT['units'].get(vid, {}).get('guns', {}):
        return None
    folder = f'{vid}/{wn.lower()}'
    return Gun(vid, gun, folder, folder, weapon)


def pod_gun(vid, family, weapon):
    if family not in PODS:
        return None
    cands, folder = PODS[family]
    have = WT['units'].get(vid, {}).get('guns', {})
    gun = next((g for g in cands if g in have), None)
    if gun is None:
        return None
    return Gun(vid, gun, f'aircraft_rounds/{folder}', f'aircraft_guns/{folder}', weapon)


def write_profiles(writes, data_root, write_json):
    for pid, prof in sorted(writes.items()):
        write_json(os.path.join(data_root, 'projectile_profiles', pid + '.json'), prof)


def describe(vid, wn, w):
    """'belt: n x round, ...' lines of a weapon (for the build log and the README table)"""
    out = []
    for e in w.get('AmmoType', []):
        b = e['Override'].get('ProjectileBelt') or {}
        out.append(f"  {wn} {b.get('Name')}: " + ', '.join(
            f"{s['Shots']}x{s['Round']}{'' if s['Tracer'] == 'NONE' else '(' + s['Tracer'] + ')'}"
            for s in b.get('Rounds', [])))
    return out
