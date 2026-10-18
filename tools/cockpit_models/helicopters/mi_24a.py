"""Mi-24A cockpit details: the navigator-operator's sight station in the nose (the sight for the flexible A-12.7
machine gun and the Raduga-F guidance sight of the Falanga missiles), a slightly smaller unit than the Mi-24V/D's
(mi24v.py)."""
import importlib.util, os

_sp = importlib.util.spec_from_file_location('heli_mi24v_parts', os.path.join(os.path.dirname(__file__), 'mi24v.py'))
_m = importlib.util.module_from_spec(_sp); _sp.loader.exec_module(_m)


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['era'].startswith('HELO_GUNNER'):
            _m.operator_sight(mesh, ac, t, c, scale=0.9)
