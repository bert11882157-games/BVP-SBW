#!/usr/bin/env python3
"""Turns inside-out munition meshes the right way out (the owner's report, 2026-09-28: "all-dark shading on the
suspended armament").

The mesh loader (sbwmeshloader PolyMesh) mirrors X and takes each face's normal as (p1 - p0) x (p2 - p0) of the
mirrored points. Vehicle models are wound so that normal points outward; most aircraft-store models were wound the
other way, so every normal pointed into the body. Stores are drawn with nearly flat, viewer-facing lighting
(BvpSuspendedStoreRenderer MUNITION_FLAT_LIGHTING), so an inward normal faces away from the light on every visible
face and the whole store renders in the dark ambient term.

For every closed shell (faces joined through shared vertices) the signed volume by the loader's convention says
which way it is wound; a negative shell has the vertex order of each of its faces reversed (UVs travel with their
vertex, so texturing is unchanged). Shells too small or too flat to tell (single cards, open fins) follow the sign
of their whole mesh. A second run changes nothing; --check exits 1 when a file would change.

    python3 tools/munition_models/fix_winding.py [--check] [files...]
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CUSTOM_GEO = os.path.join(HERE, '..', '..', 'bvp', 'src', 'generated', 'resources', 'assets', 'berts_vehicle_pack',
                          'custom_geo')
# munition models: pylon stores and their flight models, ATGMs, flying projectiles (vehicle models are not touched)
MUNITION_DIRS = ('aircraft_stores', 'atgm', 'projectiles')


def meshes(node):
    if isinstance(node, dict):
        if isinstance(node.get('poly_mesh'), dict):
            yield node['poly_mesh']
        for value in node.values():
            yield from meshes(value)
    elif isinstance(node, list):
        for value in node:
            yield from meshes(value)


def loader_points(mesh):
    return [(-p[0], p[1], p[2]) for p in mesh['positions']]


def signed_volume(points, polys):
    """Six times the signed volume enclosed by these faces with the loader's normals (positive = outward)."""
    total = 0.0
    for poly in polys:
        idx = [v[0] for v in poly]
        if len(idx) < 3:
            continue
        a = points[idx[0]]
        for k in range(1, len(idx) - 1):
            b, c = points[idx[k]], points[idx[k + 1]]
            total += (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0])
                      + a[2] * (b[0] * c[1] - b[1] * c[0]))
    return total


def shells(polys):
    """Face indices grouped by connected vertices."""
    parent = {}

    def find(x):
        while parent.setdefault(x, x) != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for poly in polys:
        idx = [v[0] for v in poly]
        for other in idx[1:]:
            ra, rb = find(idx[0]), find(other)
            if ra != rb:
                parent[ra] = rb
    groups = {}
    for face, poly in enumerate(polys):
        if poly:
            groups.setdefault(find(poly[0][0]), []).append(face)
    return list(groups.values())


def extent(points, polys, faces):
    used = {v[0] for f in faces for v in polys[f]}
    xs = [points[i] for i in used]
    return max(max(p[k] for p in xs) - min(p[k] for p in xs) for k in range(3)) if xs else 0.0


def fix_mesh(mesh):
    """Reverses inside-out shells in place; returns the number of faces reversed."""
    polys = mesh.get('polys')
    if not mesh.get('positions') or not polys:
        return 0
    points = loader_points(mesh)
    whole = signed_volume(points, polys)
    flipped = 0
    for faces in shells(polys):
        sub = [polys[f] for f in faces]
        volume = signed_volume(points, sub)
        size = extent(points, polys, faces)
        # decisive only when the shell encloses a real volume for its size (not a card or an open fin)
        decisive = len(faces) >= 4 and size > 0 and abs(volume) > 1e-3 * size ** 3
        sign = volume if decisive else whole
        if sign < 0:
            for f in faces:
                polys[f] = list(reversed(polys[f]))
            flipped += len(faces)
    return flipped


def main(argv):
    check = '--check' in argv
    files = [a for a in argv if not a.startswith('--')] or sorted(
        f for d in MUNITION_DIRS for f in glob.glob(os.path.join(CUSTOM_GEO, d, '*.geo.json')))
    changed = 0
    for path in files:
        with open(path, encoding='utf-8') as f:
            text = f.read()
        data = json.loads(text)
        flipped = sum(fix_mesh(m) for m in meshes(data))
        if not flipped:
            continue
        changed += 1
        print(f'{os.path.relpath(path)}: {flipped} faces turned outward')
        if not check:
            with open(path, 'w', encoding='utf-8') as f:
                json.dump(data, f, separators=(',', ':') if '\n' not in text.strip() else None,
                          indent=None if '\n' not in text.strip() else 2)
                if text.endswith('\n'):
                    f.write('\n')
    print(f'{changed} file(s) {"would change" if check else "changed"}')
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
