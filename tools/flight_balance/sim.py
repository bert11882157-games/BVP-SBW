#!/usr/bin/env python3
"""1-D energy model of the fixed-wing solver for speed balancing (world units, HUD km/h = m/s x 3.6).

Replicates FixedWingFlightModel's thrust and drag terms along the flight path at sea level (density 1):

    thrust = gameDry(T/m * 0.25) * machTerm * propPowerFraction * (AB ? abMultiplier * surge : 1)
    drag   = parasite v^2 (1 - 0.4 d) + induced (liftRef/v)^2 cos^2(g) + wave (1 - 0.4 d)
             + overspeed(v - cap) (1 - 0.85 d)
    dv/dt  = thrust - drag + g sin(dive angle)

It is a calibration aid, not the solver; tools/flight_balance/verify runs the real solver for spot checks.

    python3 tools/flight_balance/sim.py [ids...]      # table of level / dive speeds and acceleration times
"""
import json
import math
import os
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
REF = os.path.join(REPO, 'bvp/src/generated/resources/data/berts_vehicle_pack/flight_reference')
S = 0.25
G = 9.80665 * S


class Params:
    mach_one_kmh = 400.0
    global_soft_kmh = 650.0
    hard_kmh = 750.0
    jet_dry_gain = 1.0
    prop_dry_gain = 1.6
    cap_linear = 0.22      # m/s^2 per m/s above the first soft cap (the global soft cap uses the same curve)
    cap_quadratic = 0.005  # m/s^2 per (m/s)^2
    dive_relief = 0.95     # share of the soft-cap resistance a vertical dive sheds
    wave_onset = 0.82
    wave_width = 0.25
    surge = 0.15           # afterburner light-up push, fraction of AB thrust, fading over surge_ticks
    surge_ticks = 30
    # engine surplus (thrust above drag) kept from transonic_start to transonic_full Mach; gravity is not scaled
    transonic_start = 0.85
    transonic_full = 1.1
    transonic_excess = 0.22


def load(vid):
    return json.load(open(os.path.join(REF, vid + '.json')))


def model(vid, P=Params, cap_kmh=None):
    d = load(vid)
    r, e = d['reference'], d['engineering']
    mass = r['fullFuelMassKg']
    area = r['wingAreaSquareMetres']
    F = 0.5 * 1.225 * area / mass
    cap = cap_kmh if cap_kmh is not None else e.get('firstSoftCapKmh')
    m = {
        'vid': vid,
        'dry': e['staticOrEquivalentThrustNewtons'] / mass * S,
        'prop': (e.get('propellerPowerReferenceSpeedMps') or 0) * S,
        'kp': F * e['zeroLiftDragCoefficient'] / S,
        'kw': F * (e.get('waveDragCoefficient') or 0) / S,
        'kind': G / S * e['inducedDragFactor'] * e['maximumLiftCoefficient'] * S,
        'liftref': math.sqrt(9.80665 / (F * e['maximumLiftCoefficient'])) * S,
        'ab': e['afterburnerMultiplier'] if e.get('afterburnerEnabled') else None,
        'mf_dry': e.get('dryMachThrustFactor') or 1.0,
        'mf_ab': e.get('afterburnerMachThrustFactor') or 1.0,
        'cap': (cap or P.global_soft_kmh) / 3.6,
        'onset': e.get('waveDragOnsetMach') or P.wave_onset,
    }
    m['gain'] = P.prop_dry_gain if m['prop'] > 0 else P.jet_dry_gain
    return m


def accel(m, v, ab=False, dive_deg=0.0, surge=1.0, P=Params):
    a_snd = P.mach_one_kmh / 3.6
    mach = v / a_snd
    blend = mach * mach / (1 + mach * mach)
    mf = m['mf_ab'] if ab else m['mf_dry']
    thrust = m['dry'] * m['gain'] * (1 + (mf - 1) * blend)
    if m['prop'] > 0:
        thrust *= m['prop'] / max(m['prop'], v)
    if ab and m['ab']:
        thrust *= m['ab'] * surge
    d = max(0.0, math.sin(math.radians(dive_deg)))
    cosg = math.cos(math.radians(dive_deg))
    wave = 0.0
    if m['kw'] > 0:
        x = max(0.0, mach - m['onset']) / P.wave_width
        wave = m['kw'] * v * v * x * x / (1 + x * x)
    induced = m['kind'] * (m['liftref'] / max(v, 1.0)) ** 2 * cosg * cosg
    cap = min(m['cap'], P.global_soft_kmh / 3.6)
    e1 = max(0.0, v - cap)
    over = (P.cap_linear * e1 + P.cap_quadratic * e1 * e1)
    drag = (m['kp'] * v * v + wave) * (1 - 0.4 * d) + induced + over * (1 - P.dive_relief * d)
    t0, t1 = P.transonic_start, P.transonic_full
    if mach > t0 and thrust > drag:
        f = min(1.0, (mach - t0) / (t1 - t0))
        thrust = drag + (thrust - drag) * (1 - (1 - P.transonic_excess) * f)
    return thrust - drag + G * d


def run(m, v0_kmh, seconds, ab=False, dive_deg=0.0, P=Params):
    v = v0_kmh / 3.6
    t = 0.0
    dt = 0.05
    marks = {}
    ticks = 0
    while t < seconds:
        surge = 1.0
        if ab and ticks < P.surge_ticks:
            surge = 1 + P.surge * (1 - ticks / P.surge_ticks)
        v = min(P.hard_kmh / 3.6, max(1.0, v + accel(m, v, ab, dive_deg, surge, P) * dt))
        t += dt
        ticks += 1
        for k in (300, 350, 400, 450, 500, 550, 600, 650, 700):
            if v * 3.6 >= k and k not in marks:
                marks[k] = t
    return v * 3.6, marks


def top(m, ab=False, dive_deg=0.0, P=Params):
    lo, hi = 20 / 3.6, P.hard_kmh / 3.6
    if accel(m, hi, ab, dive_deg, 1.0, P) > 0:
        return P.hard_kmh
    for _ in range(60):
        mid = (lo + hi) / 2
        if accel(m, mid, ab, dive_deg, 1.0, P) > 0:
            lo = mid
        else:
            hi = mid
    return lo * 3.6


def airplanes():
    return sorted(f[:-5] for f in os.listdir(REF) if f.endswith('.json'))


def main(argv):
    ids = argv or airplanes()
    print(f"{'id':34s} {'cap':>5s} {'dry':>5s} {'AB':>5s} {'d30':>5s} {'d60':>5s} {'d90':>5s}  t(250->M1 AB/dry)")
    for vid in ids:
        m = model(vid)
        dry = top(m)
        ab = top(m, True) if m['ab'] else None
        d30 = top(m, bool(m['ab']), 30)
        d60 = top(m, bool(m['ab']), 60)
        d90 = top(m, bool(m['ab']), 90)
        _, marks = run(m, 250, 240, bool(m['ab']))
        print(f"{vid:34s} {m['cap'] * 3.6:5.0f} {dry:5.0f} {ab or 0:5.0f} {d30:5.0f} {d60:5.0f} {d90:5.0f}  "
              f"{marks.get(400, float('nan')):6.1f}s")


if __name__ == '__main__':
    main(sys.argv[1:])
