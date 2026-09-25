"""Generated rack adapters (small crossbar ejector racks) for twin/triple rack stores.

Only used where the aircraft geometry itself offers too few stations for the rack's copies. The adapter is a
post hanging from the pylon's bottom station and a crossbar under it; copies hang from the crossbar's
underside (triple racks) and against its two ends (their side anchors), like the crossbar racks modelled on
the F-100C and Su-27 pylons. Sizes are the smallest that keep every copy clear of the others, of the pylon and
of the airframe on every aircraft that uses the rack (checked with the same placement code as the game).
"""
import copy
import json
import os
from collections import OrderedDict, defaultdict

import numpy as np

import data
import geo
import layout
import scene
import verify

THICKNESS = 0.045
POST_MIN_WIDTH = 0.04
POST_MAX_WIDTH = 0.1
D_STEPS = np.round(np.arange(0.0, 0.61, 0.03), 3)
W_STEP = 0.02


def model_location(store_id):
    base = store_id.split(":", 1)[1].split("/")[-1]
    return "berts_vehicle_pack:custom_geo/aircraft_stores/rack_%s.geo.json" % base


def mount_with(plan, kind, mount, entries):
    import attach
    m = copy.deepcopy(mount)
    for k, v in attach.stations_json(kind, entries).items():
        m[k] = v
    return m


def exclude(entries, sid, count, allowed):
    """Stop every entry that serves `count` copies of `sid` from serving that store."""
    for e in entries:
        serves_count = e["copies"] is None or count in e["copies"]
        serves_store = (e["stores"] is None or sid in e["stores"]) and sid not in (e.get("except") or set())
        if serves_count and serves_store:
            if e["stores"] is not None:
                e["stores"] = set(e["stores"]) - {sid}
            else:
                e["except"] = set(e.get("except") or set()) | {sid}
    entries[:] = [e for e in entries if e["stores"] is None or e["stores"]]


def resolve(plans, stores):
    """Check every store on its mount's stations; move failing singles to an alternate station, send failing
    racks to an adapter, and size one adapter per rack store."""
    import attach
    mun = attach.MUN
    uses = defaultdict(list)
    for name, plan in plans.items():
        for kind, mount in data.mounts(plan.definition):
            r = plan.results.get(mount["Id"])
            if not r or "entries" not in r:
                continue
            allowed = r["allowed"]
            for sid in allowed:
                store = stores.get(sid)
                if not store or not store.get("Model"):
                    continue
                n = int(store.get("FixedRackCount", 1))
                test = mount_with(plan, kind, mount, r["entries"])
                stations = layout.mount_stations(test)
                if layout.stations_for(stations[0], n, sid) is not None:
                    m = scene.evaluate(scene.Scene(mun), plan.index, plan.S, test, store, n, sid)
                    if scene.ok(m):
                        continue
                    before = copy.deepcopy(r["entries"])
                    exclude(r["entries"], sid, n, allowed)
                    if n == 1:
                        placed = False
                        for alt in r.get("alternates", []):
                            r["entries"].append(attach.entry(alt, {1}, {sid}))
                            test = mount_with(plan, kind, mount, r["entries"])
                            m2 = scene.evaluate(scene.Scene(mun), plan.index, plan.S, test, store, 1, sid)
                            if scene.ok(m2):
                                r["notes"].append("%s hangs from the %s rail (bottom station: %s)" % (
                                    sid.split(":")[1], alt["face"], metrics_summary(m)))
                                placed = True
                                break
                            r["entries"].pop()
                        if not placed:
                            r["entries"][:] = before
                            r["notes"].append("%s: no clean station, keeps the bottom station (%s)" % (
                                sid.split(":")[1], metrics_summary(m)))
                        continue
                    r["notes"].append("native %d-copy stations rejected for %s (%s); rack adapter" % (
                        n, sid.split(":")[1], metrics_summary(m)))
                if n > 1:
                    uses[sid].append((plan, kind, mount))
    specs = OrderedDict()
    for sid, entries in uses.items():
        specs[sid] = size_adapter(mun, sid, stores[sid], entries)
    return specs


def metrics_summary(m):
    return "gap=%.3f depth=%.3f clash=%.3f adapter=%.3f" % (m["gap"], m["depth"], m["clash"], m["adapter_depth"])


def munition_dims(mun, store):
    g = mun.geometry(data.model_name(store))
    a = layout.anchors(store)
    top = a["top"]
    axis = a["axis"]
    s = a["scale"]
    r_side = abs(a["right"][0] - axis[0]) * s if a["right"] is not None else g.radius * s
    return dict(length=g.length * s, radius=g.radius * s, r_side=r_side, top=(top[1] - axis[1]) * s,
                cylinder=(g.cylinder[1] - g.cylinder[0]) * s)


def box(x0, x1, y0, y1, z0, z1):
    """Six outward quads of an axis-aligned box in hull-offset blocks."""
    return [
        ([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], [-1, 0, 0]),
        ([[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]], [1, 0, 0]),
        ([[x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0]], [0, 1, 0]),
        ([[x0, y0, z1], [x0, y0, z0], [x1, y0, z0], [x1, y0, z1]], [0, -1, 0]),
        ([[x1, y0, z1], [x1, y1, z1], [x0, y1, z1], [x0, y0, z1]], [0, 0, 1]),
        ([[x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [x1, y0, z0]], [0, 0, -1]),
    ]


def geometry(n, W, D, Lb, Wp, Lp):
    """Quads (hull offsets from the top anchor, left-wing orientation) and stations."""
    quads = []
    if D > 1e-6:
        quads += box(-Wp / 2, Wp / 2, -D, 0.0, -Lp / 2, Lp / 2)
    quads += box(-W / 2, W / 2, -D - THICKNESS, -D, -Lb / 2, Lb / 2)
    ymid = -D - THICKNESS / 2
    left = OrderedDict([("Point", data.vec4([-W / 2, ymid, 0.0])), ("Face", "left")])
    right = OrderedDict([("Point", data.vec4([W / 2, ymid, 0.0])), ("Face", "right")])
    bottom = OrderedDict([("Point", data.vec4([0.0, -D - THICKNESS, 0.0])), ("Face", "bottom")])
    stations = [left, right] if n == 2 else [bottom, left, right]
    return quads, stations


def quads_to_tris(quads):
    tris = []
    for q, _ in quads:
        q = np.array(q, float)
        tris.append([q[0], q[1], q[2]])
        tris.append([q[0], q[2], q[3]])
    return np.array(tris)


def spec_json(sid, store, stations):
    return OrderedDict([("Model", model_location(sid)), ("Texture", store["Texture"]),
                        ("MountAnchor", [0.0, 0.0, 0.0]), ("Stations", stations)])


def size_adapter(mun, sid, store, entries):
    n = int(store["FixedRackCount"])
    dims = munition_dims(mun, store)
    blades = []
    for plan, kind, mount in entries:
        r = plan.results[mount["Id"]]
        xr = r["geometry"]["bottom"].get("x_range")
        if xr is not None:
            blades.append(xr[1] - xr[0])
    blade = max(blades) if blades else 0.1
    Wp = float(np.clip(min(blades) if blades else 0.06, POST_MIN_WIDTH, POST_MAX_WIDTH))
    Lb = float(np.clip(0.45 * dims["length"], 0.35, 1.0))
    Lp = 0.7 * Lb
    W0 = max(0.1, blade + 0.03)
    chosen = None
    tried = []
    for D in D_STEPS:
        W = W0
        while W <= 1.2:
            quads, stations = geometry(n, W, D, Lb, Wp, Lp)
            tris = quads_to_tris(quads)
            spec = spec_json(sid, store, stations)
            test_store = copy.deepcopy(store)
            test_store["RackAdapter"] = spec
            sc = scene.Scene(mun, {spec["Model"]: tris * np.array([1.0, 1.0, -1.0])})
            # clash-free width first (aircraft independent)
            placements = [layout.placement(layout.anchors(test_store), np.array(s["Point"], float), s["Face"])
                          for s in stations]
            placed = [sc.munition(test_store, p) for p in placements]
            clash = 0.0
            for i in range(len(placed)):
                for j in range(i + 1, len(placed)):
                    clash = max(clash, float(np.max(scene.body_depth_scaled(placed[j], placed[i].samples), initial=0)),
                                float(np.max(scene.body_depth_scaled(placed[i], placed[j].samples), initial=0)))
            if clash > verify.DEPTH_LIMIT:
                W = round(W + W_STEP, 3)
                continue
            worst = None
            good = True
            for plan, kind, mount in entries:
                r = plan.results[mount["Id"]]
                test_mount = mount_with(plan, kind, mount, r["entries"])
                m = scene.evaluate(sc, plan.index, plan.S, test_mount, test_store, n, sid)
                if not scene.ok(m):
                    good = False
                    worst = (plan.name, mount["Id"], metrics_summary(m))
                    break
            tried.append((D, W, worst))
            if good:
                chosen = (D, W, quads, stations, spec)
                break
            # structure trouble is solved by a lower crossbar, not a wider one
            break
        if chosen:
            break
    if chosen is None:
        D, W = D_STEPS[-1], W0
        quads, stations = geometry(n, W, D, Lb, Wp, Lp)
        spec = spec_json(sid, store, stations)
        chosen = (D, W, quads, stations, spec)
        summary = "NO CLEAR SIZE; last failure %s" % (tried[-1:],)
    else:
        summary = "post D=%.2f crossbar W=%.2f L=%.2f post W=%.2f uses=%s" % (
            chosen[0], chosen[1], Lb, Wp, ["%s.%s" % (p.name, m["Id"]) for p, k, m in entries])
    D, W, quads, stations, spec = chosen
    return dict(json=spec, quads=quads, store=store, sid=sid, summary=summary, uses=entries, D=D, W=W, Lb=Lb)


def texture_uv(store):
    """A UV inside the munition's largest upward face: the rack takes the munition's body colour."""
    path = geo.resource_path(store["Model"])
    bone = geo.load_bones(path)[0]
    pm = bone["poly_mesh"]
    pos = np.array(pm["positions"], float)
    uvs = np.array(pm["uvs"], float)
    best, uv = -1, [0.5, 0.5]
    for poly in pm["polys"]:
        idx = [v[0] for v in poly]
        p = pos[idx]
        n = np.cross(p[1] - p[0], p[2] - p[0])
        area = np.linalg.norm(n)
        if area > 0 and n[1] / area > 0.5 and area > best:
            best = area
            uv = uvs[[v[2] for v in poly]].mean(0).tolist()
    return uv, pm.get("normalized_uvs", True)


def write_model(spec):
    """Write the adapter geo model (geo pixels; the model frame is the MountAnchor frame with x negated)."""
    store = spec["store"]
    uv, normalized = texture_uv(store)
    positions, normals, uvs, polys = [], [], [], []
    for quad, normal in spec["quads"]:
        poly = []
        # hull offset -> MountAnchor frame of a -Z model (x and z negated) -> geo (x negated again) = z negated
        g = [[p[0] * 16.0, p[1] * 16.0, -p[2] * 16.0] for p in quad]
        gn = [normal[0], normal[1], -normal[2]]
        # z negation mirrors the quad: reverse the winding to keep it outward
        g = g[::-1]
        for p in g:
            positions.append([data.r4(c) for c in p])
            normals.append([float(c) for c in gn])
            uvs.append([data.r4(c) for c in uv])
            k = len(positions) - 1
            poly.append([k, k, k])
        polys.append(poly)
    model = OrderedDict([
        ("format_version", "1.12.0"),
        ("minecraft:geometry", [OrderedDict([
            ("description", OrderedDict([
                ("identifier", "geometry.aircraft_store.rack_%s" % spec["sid"].split("/")[-1].split(":")[-1]),
                ("texture_width", texture_size(store)[0]), ("texture_height", texture_size(store)[1]),
                ("visible_bounds_width", 4), ("visible_bounds_height", 4), ("visible_bounds_offset", [0, 0, 0])])),
            ("bones", [OrderedDict([
                ("name", "store"), ("pivot", [0, 0, 0]),
                ("poly_mesh", OrderedDict([("normalized_uvs", normalized), ("positions", positions),
                                           ("normals", normals), ("uvs", uvs), ("polys", polys)]))])])])])])
    path = geo.resource_path(spec["json"]["Model"])
    text = json.dumps(model, indent=2) + "\n"
    old = open(path).read() if os.path.exists(path) else None
    if old != text:
        with open(path, "w") as f:
            f.write(text)
    return path


def texture_size(store):
    path = geo.resource_path(store["Model"])
    with open(path) as f:
        d = json.load(f)
    desc = d["minecraft:geometry"][0]["description"]
    return desc.get("texture_width", 16), desc.get("texture_height", 16)
