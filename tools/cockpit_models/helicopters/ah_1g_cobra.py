"""AH-1G cockpit details: the gunner's XM28 pantograph sight.

The AH-1G gunner aimed the chin turret with a reflex sight on a pantograph arm in front of him, swung by the two
handgrips under it; ah_1f.gunner_sight() without the TSU's eyepiece hood draws it. The pilot's M73 rocket sight is
the driver's reflector sight on the coaming (`gunsight: true`).
"""
import importlib.util, os

_sp = importlib.util.spec_from_file_location('heli_ah_1f_parts', os.path.join(os.path.dirname(__file__), 'ah_1f.py'))
_f = importlib.util.module_from_spec(_sp); _sp.loader.exec_module(_f)


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['era'].startswith('HELO_GUNNER'):
            _f.gunner_sight(mesh, ac, t, c, scale=0.8, hood=False)
