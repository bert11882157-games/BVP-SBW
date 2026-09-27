"""Flight reference consistency, mirroring BvpAircraftFlightProfiles.decode (a failure there throws when the aircraft
entity is constructed, so the aircraft cannot be spawned at all).

    python3 tools/vehgen/flightref.py [ids...]      # validate (all flight_reference files when no ids are given)

`derive(d)` recomputes the dependent fields after reference overrides: full-fuel mass, wing area from the full-fuel
wing loading, installed static thrust and the afterburner ratio.
"""
import glob
import json
import math
import os
import sys

GRAVITY = 9.80665
HERE = os.path.dirname(os.path.abspath(__file__))
DIR = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'data', 'berts_vehicle_pack',
                   'flight_reference')


def derive(d):
    r, e = d['reference'], d['engineering']
    r['fullFuelMassKg'] = r['baseMassKg'] + r['mainFuelMassKg']
    r['wingAreaSquareMetres'] = r['fullFuelMassKg'] / r['fullFuelWingLoadingKgPerSquareMetre']
    if e.get('propellerPowerReferenceSpeedMps', 0) == 0 and r.get('dryThrustKgfPerEngine') is not None:
        e['staticOrEquivalentThrustNewtons'] = r['engineCount'] * r['dryThrustKgfPerEngine'] * GRAVITY
        if e.get('afterburnerEnabled'):
            e['afterburnerMultiplier'] = r['afterburnerThrustKgfPerEngine'] / r['dryThrustKgfPerEngine']
    return d


def close(value, expected):
    return math.isfinite(expected) and abs(value - expected) <= 1e-6 * max(1, abs(expected))


def problems(d, vid):
    out = []
    r, e = d['reference'], d['engineering']
    if d.get('referenceProfileId') != f'berts_vehicle_pack:flight_reference/{vid}' or \
            d.get('handlingProfileId') != f'berts_vehicle_pack:flight_handling/{vid}':
        out.append('profile pair mismatch')
    if d.get('lengthScale') != 0.25:
        out.append('lengthScale')
    mass, area = r['fullFuelMassKg'], r['wingAreaSquareMetres']
    if not close(mass, r['baseMassKg'] + r['mainFuelMassKg']):
        out.append('full-fuel mass %s != %s' % (mass, r['baseMassKg'] + r['mainFuelMassKg']))
    if not close(area, mass / r['fullFuelWingLoadingKgPerSquareMetre']):
        out.append('wing area %s != %s' % (area, mass / r['fullFuelWingLoadingKgPerSquareMetre']))
    thrust = e['staticOrEquivalentThrustNewtons']
    prop = e.get('propellerPowerReferenceSpeedMps', 0)
    if prop > 0:
        eff = e['effectivePropulsiveEfficiency']
        if not close(thrust, r['engineCount'] * r['maximumPowerHpPerEngine'] * e['hpToWatts'] * eff / prop):
            out.append('propeller thrust')
    else:
        if not close(thrust, r['engineCount'] * r['dryThrustKgfPerEngine'] * GRAVITY):
            out.append('installed jet thrust %s != %s' % (thrust, r['engineCount'] * r['dryThrustKgfPerEngine'] * GRAVITY))
    if e['afterburnerEnabled'] and r.get('afterburnerThrustKgfPerEngine') is None:
        out.append('afterburner enabled without afterburner thrust')
    elif e['afterburnerEnabled']:
        if not close(e['afterburnerMultiplier'], r['afterburnerThrustKgfPerEngine'] / r['dryThrustKgfPerEngine']):
            out.append('afterburner ratio')
    elif e['afterburnerMultiplier'] != 1 or r.get('afterburnerThrustKgfPerEngine') is not None:
        out.append('disabled afterburner adds thrust')
    lift = math.sqrt(GRAVITY / (0.5 * 1.225 * area / mass * e['maximumLiftCoefficient']))
    vmax = r['maximumTrueAirspeedKmh'] / 3.6
    if not lift < e['controlReferenceSpeedMps'] < vmax:
        out.append('control speed %.1f not in (%.1f, %.1f)' % (e['controlReferenceSpeedMps'], lift, vmax))
    cas = e.get('pitotCasCalibrationFactor', 1)
    if not 1 <= cas <= 1.1:
        out.append('pitot CAS calibration')
    if ('takeoffPitchReferenceSpeedMps' in e) != ('lowSpeedThrustMultiplier' in e):
        out.append('takeoff calibration pair')
    return out


def main(argv):
    ids = argv[1:] or sorted(os.path.basename(f)[:-5] for f in glob.glob(os.path.join(DIR, '*.json')))
    bad = 0
    for vid in ids:
        d = json.load(open(os.path.join(DIR, f'{vid}.json')))
        p = problems(d, vid)
        if p:
            bad += 1
            print(vid, '; '.join(p))
    print(f'{len(ids)} flight references, {bad} with problems')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
