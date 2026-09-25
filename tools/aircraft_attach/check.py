"""Verify written attachment data with the runtime placement port; optional orthographic renders.

For every external mount with stations and every modelled store it allows (at its fixed rack count):
  gap      <= 0.02  the munition touches its pylon/adapter
  depth    <= 0.03  no interpenetration with the airframe (landing gear excluded: it is retracted in flight)
  clash    <= 0.03  copies do not interpenetrate
  adapter  <= 0.03  a rack adapter neither enters the airframe nor a munition
Launch points are reported relative to the drawn munition (distance from the body axis).
"""
import os
import sys

import numpy as np

import attach
import data
import layout
import pylons
import scene
import verify


def launch_error(pm):
    """Distance of the launch point from the drawn munition's body axis."""
    p = np.asarray(pm.placement["launch"], float)
    rel = p - pm.origin
    radial = rel - np.outer(rel @ pm.dir, pm.dir)[0]
    return float(np.linalg.norm(radial))


def render(path, title_rows, structure, rows):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.collections import PolyCollection

    fig, axs = plt.subplots(len(rows), 2, figsize=(17, 4.4 * len(rows)), gridspec_kw=dict(width_ratios=[1, 2.3]))
    axs = np.atleast_2d(axs)
    for r, row in enumerate(rows):
        m = row["metrics"]
        npos = len(layout.mount_positions(row["mount"]))
        own = [pm for k, pm in enumerate(m.get("placed", [])) if k % npos == 0]
        pts = [pm.mesh.tris.reshape(-1, 3) for pm in own]
        pts += [am.tris.reshape(-1, 3) for am, ad in zip(m.get("adapter_meshes", []), m["adapters"]) if ad["position"] == 0]
        allp = np.concatenate(pts) if pts else np.array([row["P"]])
        lo = allp.min(0) - [0.25, 0.25, 0.3]
        hi = allp.max(0) + [0.25, 0.35, 0.3]
        tri_s = structure.mesh.tris
        mn, mx = tri_s.min(1), tri_s.max(1)
        sel = np.all(mx >= lo, 1) & np.all(mn <= hi, 1)
        groups = [(tri_s[sel], "#9fb3c8", "k")]
        for am, ad in zip(m.get("adapter_meshes", []), m["adapters"]):
            if ad["position"] == 0:
                groups.append((am.tris, "#555555", "k"))
        cols = ["#e8a33d", "#d9534f", "#5cb85c", "#8e6cc9"]
        for k, pm in enumerate(m.get("placed", [])):
            if k % npos == 0:
                groups.append((pm.mesh.tris, cols[(k // npos) % len(cols)], "#333"))
        for c, (u, v, d, lim_u, lim_v) in enumerate([(0, 1, 2, (lo[0], hi[0]), (lo[1], hi[1])),
                                                     (2, 1, 0, (lo[2], hi[2]), (lo[1], hi[1]))]):
            ax = axs[r, c]
            polys, faces, edges, depth = [], [], [], []
            sign = 1.0 if c == 0 else -1.0     # front: viewer at +z (nose); side: viewer at -x (left side)
            for tris, fc, ec in groups:
                if not len(tris):
                    continue
                if c == 0:
                    keep = np.ones(len(tris), bool)
                else:
                    keep = np.ones(len(tris), bool)
                t = tris[keep]
                polys.extend(t[:, :, [u, v]])
                faces.extend([fc] * len(t))
                edges.extend([ec] * len(t))
                depth.extend((t[:, :, d].mean(1) * sign).tolist())
            order = np.argsort(depth)
            ax.add_collection(PolyCollection([polys[i] for i in order], facecolors=[faces[i] for i in order],
                                             edgecolors=[edges[i] for i in order], linewidths=0.15))
            for p in m["placements"][::npos]:
                ax.plot(p["point"][u], p["point"][v], "o", color="blue", ms=4)
                ax.plot(p["launch"][u], p["launch"][v], "x", color="red", ms=6)
            ax.set_xlim(*lim_u)
            ax.set_ylim(*lim_v)
            ax.set_aspect("equal")
            ax.grid(True, lw=0.3)
        status = "OK" if scene.ok(m) else "FAIL"
        axs[r, 0].set_title("%s x%d  %s" % (row["store"].split(":")[1], m["copies"], status), fontsize=10)
        axs[r, 1].set_title("gap %.3f  depth %.3f  clash %.3f  adapter %.3f  %s  (front: nose toward you; side: nose right)"
                            % (m["gap"], m["depth"], m["clash"], m["adapter_depth"],
                               "native" if m["native"] else ("adapter" if m["adapters"] else "legacy")), fontsize=9)
    fig.suptitle(title_rows, fontsize=12)
    fig.tight_layout()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fig.savefig(path, dpi=62)
    plt.close(fig)


def main(only, render_dir):
    stores = data.all_stores()
    aircraft = data.all_aircraft()
    sc = scene.Scene(attach.MUN)
    failures = []
    total = 0
    for name, d in aircraft.items():
        if only and name not in only:
            continue
        ext = [(k, m) for k, m in data.mounts(d) if not m.get("Internal")]
        if not ext:
            continue
        S = pylons.Structure(name)
        index = verify.StructureIndex(S)
        for kind, mount in ext:
            has = any(k in mount for k in ("Stations", "LeftStations"))
            rows = []
            for sid in mount.get("AllowedStores", []):
                store = stores.get(sid)
                if not store or not store.get("Model"):
                    continue
                n = int(store.get("FixedRackCount", 1))
                m = scene.evaluate(sc, index, S, mount, store, n, sid)
                total += 1
                le = max((launch_error(pm) for pm in m.get("placed", [])), default=0.0)
                status = "OK" if scene.ok(m) else "FAIL"
                how = "native" if m["native"] else ("adapter" if m["adapters"] else "legacy")
                print("%-6s %-12s %-16s %-44s x%d %-7s gap=%.3f depth=%.3f%s clash=%.3f adapter=%.3f launch_axis=%.3f" % (
                    status, name, mount["Id"], sid, n, how, m["gap"], m["depth"],
                    "" if m["depth_island"] is None else "(%s)" % S.mesh.names.get(m["depth_island"], "?"),
                    m["clash"], m["adapter_depth"], le))
                if status != "OK":
                    failures.append((name, mount["Id"], sid))
                rows.append(dict(store=sid, metrics=m, mount=mount,
                                 P=np.array(mount.get("Left", mount.get("Position")), float)))
            if render_dir and rows and has:
                for start in range(0, len(rows), 4):
                    chunk = rows[start:start + 4]
                    suffix = "" if len(rows) <= 4 else "_%d" % (start // 4 + 1)
                    render(os.path.join(render_dir, name, mount["Id"] + suffix + ".png"),
                           "%s %s (%s)" % (name, mount["Id"], "left" if kind == "Pairs" else "single"), S, chunk)
    print("\nchecked %d store/mount combinations, %d outside limits" % (total, len(failures)))
    for f in failures:
        print("  FAIL", *f)
    return failures


if __name__ == "__main__":
    main(set(sys.argv[1:]) or None, None)
