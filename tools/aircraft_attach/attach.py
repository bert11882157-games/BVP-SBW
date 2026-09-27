#!/usr/bin/env python3
"""Derive physical attachment data for BVP aircraft stores and write it into the generated data.

  python3 tools/aircraft_attach/attach.py plan            # compute and report, write nothing
  python3 tools/aircraft_attach/attach.py apply           # write store anchors, pylon stations, rack adapters
  python3 tools/aircraft_attach/attach.py check [--render DIR] [aircraft ...]
                                                          # verify the written data (runtime placement port)

See README.md for the rules. Edits are surgical (touched keys only) and idempotent.
"""
import argparse
import json
import os
import sys
from collections import OrderedDict, defaultdict

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adapters  # noqa: E402
import data  # noqa: E402
import geo  # noqa: E402
import layout  # noqa: E402
import munitions  # noqa: E402
import pylons  # noqa: E402
import verify  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
NON_MISSILE = {"BOMB", "GUN_POD", "ROCKET_POD"}
EPS = 1.5e-4


def load_config():
    with open(os.path.join(HERE, "config.json")) as f:
        return json.load(f)


CONFIG = load_config()


# ----------------------------------------------------------------------------------------------- munitions
class Munitions:
    def __init__(self):
        self.cache = {}

    def geometry(self, model):
        if model not in self.cache:
            self.cache[model] = munitions.MunitionGeometry(model)
        return self.cache[model]


MUN = Munitions()


def store_anchor_values(store):
    """Anchor keys for a modelled store, in the MountAnchor frame (4 decimals)."""
    model = data.model_name(store)
    g = MUN.geometry(model)
    forward = store.get("ModelForward", "-Z")
    missile = store.get("Category") not in NON_MISSILE
    a = g.anchors(forward, missile=missile)
    top = munitions.to_anchor_frame(a["top"])
    left = munitions.to_anchor_frame(a["left"])
    right = munitions.to_anchor_frame(a["right"])
    axis = munitions.to_anchor_frame(a["axis"])
    scale = float(store.get("Scale", 1.0))
    launch = munitions.hull_offset(axis - top, forward, scale)
    return dict(top=data.vec4(top), left=data.vec4(left), right=data.vec4(right), axis=data.vec4(axis),
                launch=data.vec4(launch), rule=g.rule, lugs=len(g.lugs))


def differs(old, new):
    if old is None:
        return True
    return any(abs(float(a) - float(b)) > EPS for a, b in zip(old, new))


def plan_store(store_id, store, external):
    """Return (changes dict key->value, report line) for one store."""
    if not store.get("Model"):
        return {}, None
    v = store_anchor_values(store)
    if any(not np.isfinite(float(c)) for key in ("top", "left", "right", "axis", "launch") for c in v[key]):
        # geometry the rules cannot resolve (e.g. an open or non-circular body at the station): never write NaN,
        # keep the store's current anchors
        return {}, "%-48s %-16s UNRESOLVED (non-finite anchors) - kept as written" % (store_id, data.model_name(store))
    changes = OrderedDict()
    if differs(store.get("MountAnchor"), v["top"]):
        changes["MountAnchor"] = v["top"]
    sides = OrderedDict([("Left", v["left"]), ("Right", v["right"])])
    old = store.get("SideMountAnchors") or {}
    if differs(old.get("Left"), v["left"]) or differs(old.get("Right"), v["right"]):
        changes["SideMountAnchors"] = sides
    if differs(store.get("MountAxis"), v["axis"]):
        changes["MountAxis"] = v["axis"]
    # LaunchOffset stays the legacy/internal-bay value; for externally carried stores it is rewritten to the
    # derived bottom-station launch offset so readers that ignore MountAxis stay consistent.
    if external and differs(store.get("LaunchOffset"), v["launch"]):
        changes["LaunchOffset"] = v["launch"]
    line = "%-48s %-16s rule=%-16s top=%s L=%s R=%s axis=%s launch=%s" % (
        store_id, data.model_name(store), v["rule"], v["top"], v["left"], v["right"], v["axis"], v["launch"])
    return changes, line


def apply_store_changes(store, changes):
    for key in ("MountAnchor", "SideMountAnchors", "MountAxis"):
        if key in changes:
            after = {"MountAnchor": "ModelForward", "SideMountAnchors": "MountAnchor",
                     "MountAxis": "SideMountAnchors"}[key]
            if after not in store:
                after = "Model"
            data.set_after(store, key, changes[key], after)
    if "LaunchOffset" in changes:
        data.set_after(store, "LaunchOffset", changes["LaunchOffset"], "MountAxis")
    if "RackAdapter" in changes:
        data.set_after(store, "RackAdapter", changes["RackAdapter"], "FixedRackCount")


# ------------------------------------------------------------------------------------------------ stations
def rack_counts(allowed, stores):
    counts = {1}
    for sid in allowed:
        s = stores.get(sid)
        if s and s.get("FixedRackCount"):
            counts.add(int(s["FixedRackCount"]))
    return counts


def entry(st, copies=None, stores=None):
    return dict(point=np.asarray(st["point"], float), face=st["face"],
                copies=None if copies is None else set(copies), stores=None if stores is None else set(stores),
                **{"except": None})


def entry_json(e, mirror=False):
    p = np.asarray(e["point"], float) * ([-1, 1, 1] if mirror else [1, 1, 1])
    out = OrderedDict([("Point", data.vec4(p)), ("Face", layout.mirror_face(e["face"]) if mirror else e["face"])])
    if e.get("copies") is not None:
        out["Copies"] = sorted(e["copies"])
    if e.get("stores") is not None:
        out["Stores"] = sorted(e["stores"])
    if e.get("except"):
        out["ExceptStores"] = sorted(e["except"])
    return out


def stations_json(kind, entries):
    left = [entry_json(e) for e in entries]
    if kind == "Pairs":
        return OrderedDict([("LeftStations", left), ("RightStations", [entry_json(e, True) for e in entries])])
    return OrderedDict([("Stations", left)])


class AircraftPlan:
    def __init__(self, name, definition, stores):
        self.name = name
        self.definition = definition
        self.stores = stores
        self.S = pylons.Structure(name)
        self.index = verify.StructureIndex(self.S)
        self.cfg = CONFIG.get("aircraft", {}).get(name, {})
        self.results = OrderedDict()      # mount id -> dict(stations per position json, report)

    def mount_cfg(self, mid):
        return self.cfg.get(mid, {})

    def find(self, P, mid):
        cfg = self.mount_cfg(mid)
        if "support_offset" in cfg:
            P = np.asarray(P, float) + cfg["support_offset"]
        sup = self.S.find_support(P)
        if sup is None:
            return None
        bottom = self.S.bottom_station(P, sup)
        if "bottom_override" in cfg:
            bottom = dict(bottom)
            bottom["point"] = np.array(cfg["bottom_override"], float)
        asm = self.S.assembly(sup["island"])
        sides = self.S.side_faces(asm, bottom) if bottom["kind"] == "pylon" else {}
        return dict(support=sup, bottom=bottom, assembly=asm, sides=sides)

    def plan_mount(self, kind, mount):
        mid = mount["Id"]
        cfg = self.mount_cfg(mid)
        allowed = [s for s in mount.get("AllowedStores", []) if s in self.stores]
        if mount.get("Internal"):
            return None
        if cfg.get("skip"):
            return dict(skip=cfg["skip"])
        if not any(self.stores[s].get("Model") for s in allowed):
            return dict(skip="no modelled store (item or baked presentation only)")
        counts = rack_counts(allowed, self.stores)
        P = np.array(mount["Left"] if kind == "Pairs" else mount["Position"], float)
        geom = self.find(P, mid)
        if geom is None:
            return dict(skip="no structure found above the mount position")
        bottom, sides = geom["bottom"], geom["sides"]
        lateral = {f: s for f, s in sides.items() if s["protrusion"] > pylons.SIDE_PROTRUSION}
        outboard = "left" if P[0] <= 0 else "right"
        inboard = "right" if outboard == "left" else "left"
        mode = cfg.get("layout")
        if mode is None:
            if bottom["kind"] == "skin":
                mode = "skin"
            elif len(lateral) == 2:
                mode = "crossbar"
            elif len(lateral) == 1:
                mode = "arm"
            else:
                mode = "blade"
        entries = []
        multi = sorted(c for c in counts if c > 1)
        if mode in ("blade", "skin"):
            entries.append(entry(bottom))
        elif mode == "arm":
            side = next(iter(lateral.values()))
            entries.append(entry(bottom))
            if 2 in counts:
                entries.append(entry(side, {2}))
        elif mode == "crossbar":
            if multi:
                entries.append(entry(bottom, {1} | ({3} if 3 in counts else set())))
                pair = {2} | ({3} if 3 in counts else set())
                entries.append(entry(lateral[outboard], pair))
                entries.append(entry(lateral[inboard], pair))
            else:
                entries.append(entry(bottom))
        elif mode == "shoulders":
            entries.append(entry(lateral.get(outboard) or sides[outboard]))
            entries.append(entry(lateral.get(inboard) or sides[inboard]))
        else:
            raise ValueError("unknown layout %s for %s.%s" % (mode, self.name, mid))
        alternates = [lateral[f] for f in (outboard, inboard) if f in lateral]
        result = dict(mode=mode, geometry=geom, counts=counts, P=P, kind=kind, entries=entries,
                      alternates=alternates, allowed=allowed, notes=[])
        if kind == "Pairs":
            # the right wing must carry the same structure
            PR = np.array(mount["Right"], float)
            geom_r = self.find(PR, mid)
            if geom_r is not None:
                mirror = np.array(geom_r["bottom"]["point"]) * [-1, 1, 1]
                result["mirror_error"] = float(np.linalg.norm(mirror - bottom["point"]))
            else:
                result["mirror_error"] = np.inf
        return result

    def plan(self):
        for kind, mount in data.mounts(self.definition):
            r = self.plan_mount(kind, mount)
            if r is not None:
                self.results[mount["Id"]] = r
        return self.results


def apply_stations(definition, results):
    changed = False
    for kind, mount in data.mounts(definition):
        r = results.get(mount["Id"])
        if not r or "entries" not in r:
            continue
        for key, value in stations_json(kind, r["entries"]).items():
            if mount.get(key) != value:
                anchor_key = "Position" if kind == "Singles" else ("Left" if key == "LeftStations" else "Right")
                data.set_after(mount, key, value, anchor_key)
                changed = True
    return changed


# ------------------------------------------------------------------------------------------------ pipeline
def external_store_ids(aircraft):
    ext = set()
    for name, d in aircraft.items():
        for kind, m in data.mounts(d):
            if not m.get("Internal"):
                ext.update(m.get("AllowedStores", []))
    return ext


def run_plan(write, only=None, verbose=True, shared=False):
    stores = data.all_stores()
    aircraft = data.all_aircraft()
    ext = external_store_ids(aircraft)
    report = []
    # 1) store anchors
    store_changes = OrderedDict()
    for sid, store in stores.items():
        changes, line = plan_store(sid, store, sid in ext)
        if line:
            report.append(("store", sid, line, sorted(changes)))
        if changes:
            store_changes[sid] = changes
    if write:
        for sid, changes in store_changes.items():
            apply_store_changes(stores[sid], changes)
    else:
        for sid, changes in store_changes.items():
            apply_store_changes(stores[sid], changes)   # in memory, so stations/adapters see the new anchors
    # 2) pylon stations
    plans = OrderedDict()
    for name, d in aircraft.items():
        if only and name not in only:
            continue
        if not any(not m.get("Internal") for _, m in data.mounts(d)):
            continue
        plan = AircraftPlan(name, d, stores)
        plan.plan()
        plans[name] = plan
    # 3) native rack checks and rack adapters
    adapter_uses = adapters.resolve(plans, stores)
    if only and not shared:
        # A rack adapter is shared by every carrier of its store and sized from the planned carriers. A partial run
        # would resize (or add) adapters that other aircraft draw, so it leaves them alone; its mounts fall back to
        # the store's existing adapter or the legacy RackSpacing. Pass --shared, or run without an aircraft list.
        if adapter_uses:
            print("partial run: rack adapters left unchanged for %s (use --shared to rewrite them)"
                  % ", ".join(s.split(":")[1] for s in adapter_uses))
        adapter_uses = {}
    for sid, spec in adapter_uses.items():
        store_changes.setdefault(sid, OrderedDict())["RackAdapter"] = spec["json"]
        apply_store_changes(stores[sid], {"RackAdapter": spec["json"]})
    if write:
        for sid in store_changes:
            data.write_json(data.store_path(sid), stores[sid])
        for name, plan in plans.items():
            if apply_stations(plan.definition, plan.results):
                data.write_json(data.aircraft_path(name), plan.definition)
        for sid, spec in adapter_uses.items():
            adapters.write_model(spec)
    return stores, aircraft, plans, store_changes, adapter_uses, report


def print_plan(plans, store_changes, adapter_uses, report):
    print("== store anchors")
    for kind, sid, line, keys in report:
        print(line, "CHANGES:" + ",".join(keys) if keys else "")
    print("\n== pylon stations")
    for name, plan in plans.items():
        for mid, r in plan.results.items():
            if "skip" in r:
                print("%-12s %-16s SKIP %s" % (name, mid, r["skip"]))
                continue
            st = [entry_json(e) for e in r["entries"]]
            desc = "; ".join("%s%s%s%s" % (e["Face"], e["Point"], ("x" + ",".join(map(str, e["Copies"]))) if "Copies" in e else "",
                                          ((" only " + ",".join(x.split(":")[1] for x in e["Stores"])) if "Stores" in e else "") +
                                          ((" except " + ",".join(x.split(":")[1] for x in e["ExceptStores"])) if "ExceptStores" in e else ""))
                             for e in st)
            print("%-12s %-16s %-9s counts=%s P=%s mirror_err=%.4f  %s" % (
                name, mid, r["mode"], sorted(r["counts"]), data.vec4(r["P"]), r.get("mirror_error", 0.0), desc))
            for note in r.get("notes", []):
                print("%-12s %-16s   note: %s" % ("", "", note))
    print("\n== rack adapters")
    for sid, spec in adapter_uses.items():
        print(sid, spec["summary"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["plan", "apply", "check"])
    ap.add_argument("aircraft", nargs="*")
    ap.add_argument("--render", default=None)
    ap.add_argument("--shared", action="store_true", help="partial apply may rewrite shared rack adapters")
    args = ap.parse_args()
    if args.command in ("plan", "apply"):
        stores, aircraft, plans, store_changes, adapter_uses, report = run_plan(args.command == "apply",
                                                                              set(args.aircraft) or None,
                                                                              shared=args.shared)
        print_plan(plans, store_changes, adapter_uses, report)
    else:
        import check
        check.main(args.aircraft, args.render)


if __name__ == "__main__":
    main()
