"""Writes the helicopter store definitions (data/berts_vehicle_pack/sbw/aircraft_stores/heli/*.json) and the TOW launch
and projectile profiles. Usage: make_stores.py <tree>"""
import copy, json, os, sys

tree = sys.argv[1]
D = os.path.join(tree, r'bvp\src\generated\resources\data\berts_vehicle_pack\sbw')


def load(*p):
    return json.load(open(os.path.join(D, *p), encoding='utf-8-sig'))


def save(doc, *p):
    path = os.path.join(D, *p)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(json.dumps(doc, indent=2, ensure_ascii=False) + '\n')


def pod(name, capacity, mass, ammo='superbwarfare:small_unguided_rocket'):
    return {'Schema': 1, 'Name': name, 'Category': 'ROCKET_POD', 'Capacity': capacity, 'MassKg': mass, 'AmmoItem': ammo}


def launcher(name, capacity, mass):
    return {'Schema': 1, 'Name': name, 'Category': 'MISSILE_LAUNCHER', 'Capacity': capacity, 'MassKg': mass}


def gun(name, mass):
    return {'Schema': 1, 'Name': name, 'Category': 'GUN_POD', 'MassKg': mass}


def bare(store, name, capacity):
    """A guided store drawn by the helicopter's own pylon geometry: no separate model."""
    s = {k: v for k, v in copy.deepcopy(store).items() if k not in (
        'Model', 'ModelForward', 'MountAnchor', 'SideMountAnchors', 'MountAxis', 'Texture', 'Scale')}
    s['Name'] = name
    s['Capacity'] = capacity
    return s


stores = {
    # unguided rocket pods (fire the helicopter's own rocket channel); MassKg per round including the pod's share
    'b8v20': pod('B-8V20 · 20 × S-8 rockets', 20, 17.5),
    'b13l': pod('B-13L · 5 × S-13 rockets', 5, 100.0),
    'ub32': pod('UB-32 · 32 × S-5 rockets', 32, 8.0),
    'm260': pod('M260 · 7 × Hydra 70 rockets', 7, 16.0),
    'm261': pod('M261 · 19 × Hydra 70 rockets', 19, 14.0),
    'xm158': pod('XM158 · 7 × 2.75 in rockets', 7, 16.0),
    'snebe_22': pod('68 mm pod · 22 × SNEB rockets', 22, 10.0),
    # gun pods (fire the helicopter's own gun channel)
    'm134_pods': gun('M134 minigun pods', 110.0),
    'm195_pods': gun('M195 20 mm gun pods', 120.0),
    # auxiliary tanks: carried, never fired
    'tiger_tanks': {'Schema': 1, 'Name': 'Auxiliary fuel tanks', 'Category': 'VISUAL_ONLY', 'MassKg': 300.0},
    # launchers for the helicopter's own guided missiles (native channels, native seat and guidance)
    'ataka_launcher': launcher('9M120 Ataka · 8-round launcher', 8, 49.5),
    'vikhr_launcher': launcher('9K121 Vikhr · 8-round launcher', 8, 45.0),
    'shturm_rails': launcher('9M114 Shturm · 2-round rails', 2, 31.4),
}
stores['m299_hellfire'] = bare(load('aircraft_stores', 'ah_64d', 'agm114_proxy.json'),
                               'M299 · 4 × AGM-114K Hellfire II', 4)
stores['m299_hellfire']['LaunchOffset'] = [0.0, -0.45, 0.35]
stores['hot3_quad'] = bare(load('aircraft_stores', 'eurocopter_tiger', 'hot3_proxy.json'), 'HOT 3 · 4-round launcher', 4)
stores['hot3_quad']['LaunchOffset'] = [0.0, -0.3, 1.0]
stores['falanga_twin'] = bare(load('aircraft_stores', 'munition', 'falanga_9m17m.json'),
                              '9M17M Falanga · 2-round rails', 2)
stores['falanga_twin']['LaunchOffset'] = [0.0, 0.25, 0.3]

# TOW: a SACLOS command-guided launcher with its own launch and projectile profiles (Falanga airframe numbers, TOW
# name and sight guidance)
gun_profile = load('guns', 'aircraft_stores', 'falanga_9m17m.json')
gun_profile['Name'] = 'BGM-71 TOW · wire-guided, sight command'
gun_profile['Projectile']['Profile'] = 'berts_vehicle_pack:aircraft_stores/bgm71_tow'
save(gun_profile, 'guns', 'aircraft_stores', 'bgm71_tow.json')
projectile = load('projectile_profiles', 'aircraft_stores', 'falanga_9m17m.json')
save(projectile, 'projectile_profiles', 'aircraft_stores', 'bgm71_tow.json')
tow = bare(load('aircraft_stores', 'munition', 'falanga_9m17m.json'), 'M65 · 4 × BGM-71 TOW', 4)
tow['CommandGuidance'] = {'Mode': 'SACLOS'}
tow['ProjectileProfile'] = 'berts_vehicle_pack:aircraft_stores/bgm71_tow'
tow['LaunchGunProfile'] = 'berts_vehicle_pack:aircraft_stores/bgm71_tow'
tow['MassKg'] = 22.0
tow['LaunchOffset'] = [0.0, -0.2, 0.6]
stores['tow_quad'] = tow

for name, doc in stores.items():
    save(doc, 'aircraft_stores', 'heli', name + '.json')
    print('heli/' + name, doc['Category'])
# the old always-hidden equipment stores are replaced by per-pylon stores
for vid in ('mi24v', 'ka50', 'mi28n', 'ah_6j', 'ah_1g_cobra'):
    p = os.path.join(D, 'aircraft_stores', 'modeled_store', vid, 'suspended_equipment.json')
    if os.path.exists(p):
        os.remove(p)
        try:
            os.rmdir(os.path.dirname(p))
        except OSError:
            pass
        print('removed', p[len(D):])
