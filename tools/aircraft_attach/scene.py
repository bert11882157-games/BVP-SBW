"""Place munitions and rack adapters exactly as the runtime does, and measure contact/penetration."""
import numpy as np

import data
import geo
import layout
import munitions
import verify


class Scene:
    def __init__(self, munition_cache, adapter_meshes=None):
        self.mun = munition_cache
        self.adapter_meshes = adapter_meshes if adapter_meshes is not None else {}

    def munition(self, store, placement):
        """PlacedMunition for a store placement (hull blocks)."""
        g = self.mun.geometry(data.model_name(store))
        a = layout.anchors(store)
        anchor = np.asarray(placement["anchor"], float)
        point = np.asarray(placement["point"], float)

        def fn(p):
            m = p * np.array([-1.0, 1.0, 1.0])
            return point + np.array([layout.hull_delta(a, q - anchor) for q in m]) if len(m) else m

        mesh = g.mesh.transformed(fn)
        origin = point + layout.hull_delta(a, np.array([-g.x0, g.y0, 0.0]) - anchor)
        direction = layout.hull_delta(a, np.array([0.0, 0.0, 1.0]))
        pm = verify.PlacedMunition(g, mesh, origin, direction / np.linalg.norm(direction))
        pm.scale = a["scale"]
        pm.placement = placement
        return pm

    def adapter_mesh(self, adapter_json, base, mirror=False):
        """Adapter triangles in hull blocks hung from base."""
        model = adapter_json["Model"]
        tris = self.adapter_meshes.get(model)
        if tris is None:
            path = geo.resource_path(model)
            tris = geo.model_mesh(path).tris / 16.0
            self.adapter_meshes[model] = tris
        anchor = np.array(adapter_json.get("MountAnchor", [0, 0, 0]), float)
        pts = tris.reshape(-1, 3) * np.array([-1.0, 1.0, 1.0])          # anchor frame
        rel = (pts - anchor) * np.array([-1.0, 1.0, -1.0])               # -Z model, scale 1
        if mirror:
            rel = rel * np.array([-1.0, 1.0, 1.0])
        return geo.Mesh((np.asarray(base) + rel).reshape(-1, 3, 3), np.zeros(len(tris), int))


def body_depth_scaled(pm, pts):
    """PlacedMunition.body_depth honouring Scale."""
    s = getattr(pm, "scale", 1.0)
    if s == 1.0:
        return pm.body_depth(pts)
    g = pm.geom
    rel = pts - pm.origin
    z = (rel @ pm.dir) / s
    radial = rel - np.outer(rel @ pm.dir, pm.dir)
    dist = np.linalg.norm(radial, axis=1)
    r = np.interp(z, g.profile_z, np.nan_to_num(g.profile_r), left=0, right=0) * s
    depth = r - dist
    depth[(z < g.profile_z[0]) | (z > g.profile_z[-1])] = 0
    return np.clip(depth, 0, None)


def open_bottom(structure, islands, point):
    """A virtual bottom face for an open-bottomed pylon blade (its walls end at the station height)."""
    out = []
    for i in islands:
        if not structure.is_small(i):
            continue
        t, _ = geo.first_hit(structure.island_mesh(i), point - [0, 0.002, 0], [0, 1, 0], 0.01)
        if t is not None:
            return np.zeros((0, 3, 3))
        y, xs = structure.section_low(i, float(point[2]))
        if y is None or abs(y - point[1]) > 0.005 or not (xs.min() - 1e-3 <= point[0] <= xs.max() + 1e-3):
            continue
        lo, hi = structure.box[i]
        x0, x1, z0, z1 = xs.min(), xs.max(), max(lo[2], point[2] - 0.6), min(hi[2], point[2] + 0.6)
        a, b, c, d = [x0, y, z0], [x1, y, z0], [x1, y, z1], [x0, y, z1]
        out += [[a, b, c], [a, c, d]]
    return np.array(out, float).reshape(-1, 3, 3)


def evaluate(scene, index, structure, mount, store, copies, store_id=None):
    """Metrics for one store rack on one mount (both positions for a pair)."""
    placements, adapters = layout.layout(mount, store, copies, store_id)
    out = dict(copies=copies, placements=placements, adapters=adapters, gap=0.0, depth=0.0, depth_island=None,
               clash=0.0, adapter_depth=0.0, legacy=False, native=False)
    if not store.get("Model"):
        return out
    stations = layout.mount_stations(mount)
    positions = layout.mount_positions(mount)
    npos = len(positions)
    placed = [scene.munition(store, p) for p in placements]
    adapter_meshes = []
    for ad in adapters:
        mirror = "Left" in mount and ad["position"] == 1
        adapter_meshes.append(scene.adapter_mesh(ad["adapter"], ad["point"], mirror))
    native = [layout.stations_for(stations[i], copies, store_id) is not None for i in range(npos)]
    out["native"] = all(native)
    out["legacy"] = not out["native"] and not adapters
    gaps, depth, clash, adepth = [], (0.0, None), 0.0, 0.0
    for k, pm in enumerate(placed):
        pos = k % npos
        point = np.asarray(pm.placement["point"])
        # support: adapter mesh for adapter-borne copies, else the structure near the station
        ad = next((m for m, a in zip(adapter_meshes, adapters) if a["position"] == pos), None)
        if ad is not None:
            support_tris = ad.tris
        else:
            near = index.islands_near(point - 0.05, point + 0.05)
            support_tris = np.concatenate([structure.island_mesh(i).tris for i in near]) if near else np.zeros((0, 3, 3))
            if pm.placement["face"] == "bottom":
                support_tris = np.concatenate([support_tris, open_bottom(structure, near, point)])
        gaps.append(verify.min_distance(pm.samples, support_tris, point, 0.4))
        # munition inside structure
        islands = index.islands_near(pm.lo, pm.hi)
        d = index.inside_depth(pm.samples, islands)
        if d[0] > depth[0]:
            depth = d
        # structure inside munition body
        for i in islands:
            tris = structure.island_mesh(i).tris
            lo, hi = structure.box[i]
            if np.any(hi < pm.lo) or np.any(lo > pm.hi):
                continue
            pts = verify.surface_samples(tris, per_tri=3)
            sel = np.all((pts >= pm.lo - 1e-6) & (pts <= pm.hi + 1e-6), axis=1)
            if np.any(sel):
                dd = float(np.max(body_depth_scaled(pm, pts[sel]), initial=0.0))
                if dd > depth[0]:
                    depth = (dd, i)
        # adapter vs munition
        for am in adapter_meshes:
            pts = verify.surface_samples(am.tris, per_tri=4)
            adepth = max(adepth, float(np.max(body_depth_scaled(pm, pts), initial=0.0)))
        for j in range(k + 1, len(placed)):
            other = placed[j]
            if np.any(other.hi < pm.lo) or np.any(other.lo > pm.hi):
                continue
            clash = max(clash, float(np.max(body_depth_scaled(other, pm.samples), initial=0.0)),
                        float(np.max(body_depth_scaled(pm, other.samples), initial=0.0)))
    # adapters inside structure
    for am in adapter_meshes:
        lo, hi = am.bounds()
        pts = verify.surface_samples(am.tris, per_tri=4)
        d = index.inside_depth(pts, index.islands_near(lo, hi))
        adepth = max(adepth, d[0])
    out.update(gap=float(max(gaps)) if gaps else 0.0, gaps=gaps, depth=depth[0], depth_island=depth[1],
               clash=clash, adapter_depth=adepth, placed=placed, adapter_meshes=adapter_meshes)
    return out


def ok(metrics):
    if metrics.get("legacy"):
        return False
    return (metrics["gap"] <= verify.GAP_LIMIT and metrics["depth"] <= verify.DEPTH_LIMIT and
            metrics["clash"] <= verify.DEPTH_LIMIT and metrics["adapter_depth"] <= verify.DEPTH_LIMIT)
