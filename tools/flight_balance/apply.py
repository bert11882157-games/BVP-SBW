#!/usr/bin/env python3
"""Writes the owner's 2026-09-28 speed balance into flight_reference/<id>.json.

    python3 tools/flight_balance/apply.py [--check]

* engineering.firstSoftCapKmh from targets.FIRST_SOFT_CAP (HUD km/h, Mach 1 = 400 km/h);
* a transonic drag rise for every aircraft that had none (waveDragCoefficient 0): propeller aircraft 0.05 from
  Mach 0.62 (compressibility), subsonic jets 0.04, transonic jets 0.02, supersonic jets 0.009;
* AERO_FIXES (drag data far above the real type, a copied airframe);
* the MiG-21, F-104 and F-15: afterburner Mach thrust factor set so they pass the global soft cap on afterburner in
  level flight, slowly;
* airbrakes (airbrakeDragPerMetre 0.0012) on the types that have them in service.

Idempotent. Variant specs (tools/vehgen/aircraft_variant.py) copy their template's engineering block; re-running a
variant spec keeps its template's values, so run this afterwards.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sim  # noqa: E402
import targets  # noqa: E402

AERO_FIXES = {
    # delta canard with a Cd0 of 0.071 could not reach Mach 1 on afterburner: Typhoon-like values
    'rafale': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.009},
    'f_14a': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.008},
    'f_14d': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.008},
    # licence/derived Flankers: the Su-27's own values
    'j_11a': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.0058},
    'su_35': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.0058},
    'mirage_5': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.01},
    'mirage_f1': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.01},
    'f_8h': {'zeroLiftDragCoefficient': 0.022, 'waveDragCoefficient': 0.01},
}
REFERENCE_FIXES = {
    'su_35': {'maximumTrueAirspeedKmh': 2400},   # was 1400 (the Su-35S reaches Mach 2.25)
}
# afterburner Mach thrust factor for the three that pass the global soft cap (level AB top ~665-685 km/h)
FAST_AB_MACH = {'mig_21bis': 4.5, 'f_104g': 4.4, 'f_15c': 3.1, 'f_15e': 2.1}
AIRBRAKE = 0.0012
AIRBRAKES = {
    # speed brakes / dive brakes / split surfaces in service
    'a_10', 'a_7d', 'f2h_2', 'f3h', 'f9f_2', 'f_100c', 'f_111f', 'f_14a', 'f_14d', 'f_15c', 'f_15e', 'f_16b',
    'f_16c', 'f_4c', 'f_5a', 'f_8h', 'j_11a', 'j_2', 'j_5', 'md_450_ouragan', 'meteor_f_8', 'mirage_5',
    'mirage_f1', 'sabre_mk_6', 'su_24', 'su_39', 'su_57', 'su_9', 'super_mystere', 'ho_229', 'ju_87_b2',
    'rafale', 'b_47e',
}


def category(vid, e):
    if (e.get('propellerPowerReferenceSpeedMps') or 0) > 0:
        return 'prop'
    cap = targets.FIRST_SOFT_CAP.get(vid, 650)
    return 'subsonic' if cap <= 280 else 'transonic' if cap < 400 else 'supersonic'


def main(argv):
    check = '--check' in argv
    changed = 0
    for vid in sim.airplanes():
        path = os.path.join(sim.REF, vid + '.json')
        text = open(path).read()
        d = json.loads(text)
        e, r = d['engineering'], d['reference']
        before = json.dumps(d, sort_keys=True)
        if vid in targets.FIRST_SOFT_CAP:
            e['firstSoftCapKmh'] = targets.FIRST_SOFT_CAP[vid]
        for k, v in AERO_FIXES.get(vid, {}).items():
            e[k] = v
        for k, v in REFERENCE_FIXES.get(vid, {}).items():
            r[k] = v
        cat = category(vid, e)
        if not e.get('waveDragCoefficient'):
            e['waveDragCoefficient'] = {'prop': 0.05, 'subsonic': 0.04, 'transonic': 0.02, 'supersonic': 0.009}[cat]
        if cat == 'prop':
            e['waveDragOnsetMach'] = 0.62
        if vid in FAST_AB_MACH:
            e['afterburnerMachThrustFactor'] = FAST_AB_MACH[vid]
        if vid in AIRBRAKES and not e.get('airbrakeDragPerMetre'):
            e['airbrakeDragPerMetre'] = AIRBRAKE
        if json.dumps(d, sort_keys=True) != before:
            changed += 1
            if not check:
                open(path, 'w').write(json.dumps(d, indent=2)
                                      + ('\n' if text.endswith('\n') else ''))
    print(f"{changed} files {'would change' if check else 'changed'}")
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
