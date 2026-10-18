"""Mi-24D cockpit details: the operator's sight station (KPS-53AV gun sight with the Raduga-F missile guidance sight)
in the front cockpit, the same unit as the Mi-24V's (mi24v.py)."""
import importlib.util, os

_sp = importlib.util.spec_from_file_location('heli_mi24v_parts', os.path.join(os.path.dirname(__file__), 'mi24v.py'))
_m = importlib.util.module_from_spec(_sp); _sp.loader.exec_module(_m)


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['era'].startswith('HELO_GUNNER'):
            _m.operator_sight(mesh, ac, t, c)
