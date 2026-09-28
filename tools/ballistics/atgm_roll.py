#!/usr/bin/env python3
"""Real in-flight roll of every ATGM in the pack, written to its projectile profiles.

  python3 tools/ballistics/atgm_roll.py [--check]

Each profile whose RoundId names a missile below gets the extension superbwarfare:missile_roll_v1
{Schema, RollHz, RollAccelerationHzPerSecond, Direction} (SBW MissileRoll). RollHz is the missile's real steady rate;
the game draws at most 6 Hz (MissileRoll.MAX_HZ), reached after min(RollHz, 6) / acceleration seconds. Sources and
estimates: docs/ATGM_ROLL.md. Idempotent.
"""
import json, os, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PROFILES = os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/projectile_profiles')
EXT = 'superbwarfare:missile_roll_v1'

# RoundId path -> (steady roll Hz, spin-up acceleration Hz/s). Clockwise seen from behind (the only sourced direction,
# KBP Fagot/Metis) unless stated.
NOZZLE_SPUN = 20.0   # spun by canted booster nozzles / motors in the launcher: full rate within ~0.5 s of launch
WING_SPUN = 15.0     # spun by canted wings once they open: design rate within 0.5 s (9M112 Kobra, sourced)
ROLL = {
    '9m14': (8.5, 17.0),               # sourced 8.5 rev/s, reached during the booster (launch) phase
    'hj73e_tandem_atgm': (8.5, 17.0),  # Malyutka derivative
    '9m113_konkurs': (6.0, 12.0),      # sourced 5-7 Hz (spin kept clear of the 2-3 Hz airframe mode)
    '9m133_kornet': (10.0, 20.0),      # estimate: KBP single-channel family (Fagot 10 rev/s, Metis 7-12)
    '9m114_shturm': (10.0, NOZZLE_SPUN),  # estimate: spun by canted booster nozzles in the tube
    '9m120_ataka': (10.0, NOZZLE_SPUN),   # estimate: Shturm scheme
    '9k127_vikhr': (10.0, NOZZLE_SPUN),   # estimate: two canted motors impart the spin
    '9m117_bastion': (7.5, WING_SPUN),    # estimate 5-10 Hz: canted wings open after launch
    '9m119m1_tandem': (7.5, WING_SPUN),   # estimate: canted tail blades
    '9m112_kobra': (7.5, WING_SPUN),      # rate estimate; spin-up to design rate within 0.5 s sourced
    'gp105_tandem_atgm': (7.5, WING_SPUN),  # estimate: gun-launched, Refleks/Bastion scheme
    'gp125_tandem_atgm': (7.5, WING_SPUN),
    'milan_atgm': (12.0, 24.0),        # sourced 12 rev/s ("rotation lente"); spin-up estimate 0.5 s
    'milan_3_atgm': (12.0, 24.0),
    'qn502cdd_atgm': (8.0, 16.0),      # nothing published: estimate from the single-channel family
    'qn201dd_atgm': (8.0, 16.0),
    'bgm71a_tow': (0.0, 0.0),          # sourced: roll-stabilised by a gyro, does not spin
    'bgm71e_tow': (0.0, 0.0),
}


def main():
    check = '--check' in sys.argv
    changed = 0
    for root, _, files in os.walk(PROFILES):
        for name in files:
            path = os.path.join(root, name)
            text = open(path).read()
            d = json.loads(text)
            rid = (d.get('RoundId') or d.get('Combat', {}).get('RoundId') or '').split(':')[-1]
            if rid not in ROLL:
                continue
            hz, acc = ROLL[rid]
            want = {'Schema': 1, 'RollHz': hz, 'RollAccelerationHzPerSecond': acc, 'Direction': 'CLOCKWISE'}
            if d.get('Extensions', {}).get(EXT) == want:
                continue
            d.setdefault('Extensions', {})[EXT] = want
            changed += 1
            print(f'{os.path.relpath(path, PROFILES)}: {rid} {hz} Hz, {acc} Hz/s')
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2) + ('\n' if text.endswith('\n') else ''))
    print(f'{changed} profiles' + (' (check only)' if check else ''))


if __name__ == '__main__':
    main()
