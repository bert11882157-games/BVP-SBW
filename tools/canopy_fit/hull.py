"""The canopy as a stretched skin: the convex hull of the fitted bubble and of every frame, arch, coaming and post
around the opening (each raised by its clearance), so flat panes form between straight frames by themselves and the
bubble passes over or through the rest. The hull's underside is dropped, the rest is cut where it meets the model's
skin (points under the skin are set on it, faces lying on the skin are dropped) and any edge still standing above the
skin is carried straight down onto it."""
import math
import numpy as np
from scipy.spatial import ConvexHull


def hull_sheet(P, skin, max_edge=0.04, near_skin=0.12, down=-0.25):
    """-> indexed sheet (V, F) of the hull of points P, with its underside dropped and its foot on the skin"""
    h = ConvexHull(P)
    F = h.simplices.copy()
    n = h.equations[:, :3]
    # outward winding
    fn = np.cross(P[F[:, 1]] - P[F[:, 0]], P[F[:, 2]] - P[F[:, 0]])
    flip = np.sum(fn * n, 1) < 0
    F[flip] = F[flip][:, ::-1]
    F = F[n[:, 1] > down]
    V = P.copy()
    # finer only near the skin, where the foot is cut
    V, F = refine(V, F, skin, max_edge, near_skin)
    s = np.array([skin.at(v[0], v[2]) for v in V])
    under = ~np.isnan(s) & (V[:, 1] < s)
    V[under, 1] = s[under]
    on = under | (~np.isnan(s) & (np.abs(V[:, 1] - s) < 0.003))
    F = F[~on[F].all(1)]
    used = np.unique(F); remap = -np.ones(len(V), int); remap[used] = np.arange(len(used))
    V, F, on, s = V[used], remap[F], on[used], s[used]
    V, F = skirt(V, F, on, s, skin)
    return simplify(V, F)


def simplify(V, F, angle=0.6, boundary=False):
    """faces lying within angle degrees of their neighbours merged (keeping the open edges), then triangulated"""
    import bpy  # noqa: F401
    import bmesh
    bm = bmesh.new()
    vs = [bm.verts.new(tuple(v)) for v in V]
    for f in F:
        try:
            bm.faces.new([vs[i] for i in f])
        except ValueError:
            pass
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    bmesh.ops.dissolve_limit(bm, angle_limit=math.radians(angle), use_dissolve_boundaries=boundary,
                             verts=list(bm.verts), edges=list(bm.edges))
    bmesh.ops.triangulate(bm, faces=list(bm.faces))
    bm.verts.index_update()
    V2 = np.array([v.co[:] for v in bm.verts]).reshape(-1, 3)
    F2 = np.array([[v.index for v in f.verts] for f in bm.faces], int).reshape(-1, 3)
    bm.free()
    return V2, F2


def refine(V, F, skin, max_edge, near_skin):
    """split every edge longer than max_edge that comes within near_skin of the skin (both faces on an edge split it
    alike, so no cracks open)"""
    V = [np.asarray(v, float) for v in V]; F = [tuple(int(x) for x in f) for f in F]

    def low(i):
        sk = skin.at(V[i][0], V[i][2])
        return np.isnan(sk) or V[i][1] < sk + near_skin

    for _ in range(10):
        split = {}
        for f in F:
            for a, b in ((f[0], f[1]), (f[1], f[2]), (f[2], f[0])):
                k = (min(a, b), max(a, b))
                if k not in split and np.linalg.norm(V[a] - V[b]) > max_edge and (low(a) or low(b)):
                    V.append((V[a] + V[b]) / 2); split[k] = len(V) - 1
        if not split:
            break
        out = []
        for a, b, c in F:
            m = [split.get((min(x, y), max(x, y))) for x, y in ((a, b), (b, c), (c, a))]
            ab, bc, ca = m
            n = sum(x is not None for x in m)
            if n == 0:
                out.append((a, b, c))
            elif n == 3:
                out += [(a, ab, ca), (ab, b, bc), (ca, bc, c), (ab, bc, ca)]
            elif n == 1:
                if ab is not None:
                    out += [(a, ab, c), (ab, b, c)]
                elif bc is not None:
                    out += [(a, b, bc), (a, bc, c)]
                else:
                    out += [(a, b, ca), (ca, b, c)]
            else:
                if ab is None:
                    out += [(a, b, bc), (a, bc, ca), (ca, bc, c)]
                elif bc is None:
                    out += [(b, c, ca), (b, ca, ab), (ab, ca, a)]
                else:
                    out += [(c, a, ab), (c, ab, bc), (bc, ab, b)]
        F = out
    return np.array(V), np.array(F, int)


def skirt(V, F, on, s, skin):
    """walls straight down from every open edge that stands above the skin"""
    edges = {}
    for f in F:
        for a, b in ((f[0], f[1]), (f[1], f[2]), (f[2], f[0])):
            k = (min(a, b), max(a, b))
            edges[k] = None if k in edges else (a, b)
    V = list(V); F = [tuple(f) for f in F]
    foot = {}

    def down(i):
        if i not in foot:
            if on[i] or np.isnan(s[i]) or V[i][1] - s[i] > 0.4:
                foot[i] = None
            else:
                V.append(np.array([V[i][0], s[i], V[i][2]])); foot[i] = len(V) - 1
        return foot[i]

    for e in edges.values():
        if e is None:
            continue
        a, b = e
        if on[a] and on[b]:
            continue
        fa, fb = (a if on[a] else down(a)), (b if on[b] else down(b))
        if fa is None or fb is None:
            continue
        if fa != a and fb != b:
            F += [(b, a, fa), (b, fa, fb)]
        elif fa != a:
            F.append((b, a, fa))
        elif fb != b:
            F.append((b, a, fb))
    return np.array(V), np.array(F, int)


def decimate(V, F, faces):
    """collapse edges until about `faces` faces remain (Blender's decimate) -> (V, F)"""
    import bpy
    if len(F) <= faces:
        return V, F
    me = bpy.data.meshes.new('dec'); me.from_pydata([tuple(v) for v in V], [], [tuple(int(i) for i in f) for f in F])
    ob = bpy.data.objects.new('dec', me); bpy.context.scene.collection.objects.link(ob)
    md = ob.modifiers.new('d', 'DECIMATE'); md.decimate_type = 'COLLAPSE'; md.ratio = faces / len(F)
    md.use_collapse_triangulate = True
    dg = bpy.context.evaluated_depsgraph_get(); ev = ob.evaluated_get(dg); m2 = ev.to_mesh()
    V2 = np.array([v.co[:] for v in m2.vertices]).reshape(-1, 3)
    F2 = np.array([p.vertices[:] for p in m2.polygons if len(p.vertices) == 3], int).reshape(-1, 3)
    ev.to_mesh_clear(); bpy.data.objects.remove(ob); bpy.data.meshes.remove(me)
    return V2, F2
