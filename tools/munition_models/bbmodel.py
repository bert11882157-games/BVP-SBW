"""Blockbench (.bbmodel, free/mesh format) reader: mesh and cube elements as textured polygons in Blockbench
model space (px; +Y up; element rotation XYZ about its origin, applied Z then Y then X as Blockbench does).

polys(model) -> [(positions Nx3, uvs Nx2 in texels, texture index, element index)]
"""
import base64, io, json, math

import numpy as np


def _rot(deg):
    rx, ry, rz = (math.radians(v) for v in deg)
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return X @ Y @ Z


def _groups(outliner, parent=None, out=None):
    """element uuid -> list of enclosing groups (outermost first)."""
    out = {} if out is None else out
    chain = [] if parent is None else parent
    for node in outliner:
        if isinstance(node, str):
            out[node] = chain
        else:
            _groups(node.get('children', []), chain + [node], out)
    return out


def load(path):
    with open(path) as f:
        return json.load(f)


def textures(model):
    """[(PIL.Image or None, width, height)] per texture (uv_width/height from the model resolution)."""
    from PIL import Image
    out = []
    for t in model.get('textures', []):
        src = t.get('source') or ''
        img = None
        if src.startswith('data:image'):
            img = Image.open(io.BytesIO(base64.b64decode(src.split(',', 1)[1]))).convert('RGBA')
        w = t.get('uv_width') or model.get('resolution', {}).get('width') or (img.width if img else 16)
        h = t.get('uv_height') or model.get('resolution', {}).get('height') or (img.height if img else 16)
        out.append((img, w, h))
    return out


def polys(model):
    groups = _groups(model.get('outliner', []))
    result = []
    for ei, e in enumerate(model.get('elements', [])):
        if e.get('visibility') is False or e.get('export') is False:
            continue
        R = _rot(e.get('rotation', [0, 0, 0]))
        o = np.array(e.get('origin', [0, 0, 0]), float)

        def place(p):
            q = R @ (np.asarray(p, float) - (0 if e.get('type') == 'mesh' else o)) + o
            for g in reversed(groups.get(e.get('uuid'), [])):
                if any(g.get('rotation', [0, 0, 0])):
                    go = np.array(g.get('origin', [0, 0, 0]), float)
                    q = _rot(g['rotation']) @ (q - go) + go
            return q

        if e.get('type') == 'mesh':
            verts = e['vertices']
            for face in e['faces'].values():
                ids = face.get('vertices', [])
                if len(ids) < 3:
                    continue
                if len(ids) == 4:
                    ids = _quad_order(verts, ids)
                P = np.array([place(verts[i]) for i in ids])
                U = np.array([face['uv'][i] for i in ids], float)
                result.append((P, U, face.get('texture'), ei))
        else:
            f, t = np.array(e['from'], float), np.array(e['to'], float)
            inf = e.get('inflate', 0)
            f, t = f - inf, t + inf
            x0, y0, z0 = f
            x1, y1, z1 = t
            corners = {
                'north': [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
                'south': [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
                'east': [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
                'west': [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
                'up': [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
                'down': [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
            }
            for name, face in (e.get('faces') or {}).items():
                if name not in corners or 'uv' not in face:
                    continue
                u0, v0, u1, v1 = face['uv']
                P = np.array([place(c) for c in corners[name]])
                U = np.array([(u0, v0), (u1, v0), (u1, v1), (u0, v1)], float)
                result.append((P, U, face.get('texture'), ei))
    return result


def _quad_order(verts, ids):
    """Blockbench stores quad vertices unordered: sort them around their centroid in the face plane."""
    P = np.array([verts[i] for i in ids], float)
    c = P.mean(0)
    n = np.cross(P[1] - P[0], P[2] - P[0])
    if np.linalg.norm(n) < 1e-9:
        n = np.cross(P[2] - P[0], P[3] - P[0])
    n = n / (np.linalg.norm(n) or 1)
    a = P[0] - c
    a = a / (np.linalg.norm(a) or 1)
    b = np.cross(n, a)
    ang = [math.atan2((p - c) @ b, (p - c) @ a) for p in P]
    return [ids[i] for i in np.argsort(ang)]
