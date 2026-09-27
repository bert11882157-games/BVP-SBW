"""SMP Toolbox .mtb -> runtime poly-mesh geometry, the geometric core of tools/vehgen.

Frames (see tools/vehgen/README.md):
  toolbox  +x forward, +y down, +z vehicle left (tools/mtb_wheels/mtb.py)
  geo      +X vehicle left, +Y up, nose -Z, 16 px per block      geo = [z*s, -y*s + yOff, -x*s]
  data     +X vehicle left, +Y up, nose +Z, blocks               data = [gx/16, gy/16, -gz/16]
The toolbox -> geo map is a reflection (det -1); faces are re-wound counter-clockwise seen from outside their
element instead of relying on the source winding. yaw180 turns sources modelled nose -x.
"""
import os, sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'mtb_wheels'))
import mtb  # noqa: E402


class Frame:
    def __init__(self, scale, y_offset, yaw180=False):
        self.s, self.y_off, self.yaw180 = scale, y_offset, yaw180

    def geo(self, p):
        x, y, z = (-p[0], p[1], -p[2]) if self.yaw180 else (p[0], p[1], p[2])
        return np.array([z * self.s, -y * self.s + self.y_off, -x * self.s])

    def data(self, p):
        g = self.geo(p)
        return np.array([g[0] / 16, g[1] / 16, -g[2] / 16])


def geo_to_data(g):
    return np.array([g[0] / 16, g[1] / 16, -g[2] / 16])


def data_to_geo(d):
    return np.array([d[0] * 16, d[1] * 16, -d[2] * 16])


class Source:
    """An .mtb file: elements by part number, texture, and the non-element records."""

    def __init__(self, path):
        import zipfile
        (self.tw, self.th), self.elements, self.png = mtb.read(path)
        text = zipfile.ZipFile(path).read('Model.txt').decode('utf-8', 'replace').splitlines()
        self.records = [line.split('|') for line in text if line.split('|')[0] in ('Seat', 'Gun', 'Wheel', 'CP')]
        self.model_scale = next((mtb.num(line.split('|')[1]) for line in text if line.startswith('ModelScale|')), 1.0)
        self.parts = {}
        for f in self.elements:
            self.parts.setdefault(int(f[4]), []).append(f)

    def corners(self, parts=None):
        els = self.elements if parts is None else [f for p in parts for f in self.parts.get(p, [])]
        return np.array([c for f in els for c in mtb.box_corners(f)]) if els else np.zeros((0, 3))

    @staticmethod
    def rotation_point(elements):
        """The most common element rotation point of a part (toolbox frame)."""
        from collections import Counter
        (key, _), = Counter((f[6], f[7], f[8]) for f in elements).most_common(1)
        return np.array([mtb.num(v) for v in key])


def mesh(elements, frame, tw, th, keep=None):
    """poly_mesh dict (normalized uvs, v flipped) of the elements in the geo frame; keep(face_centre_geo) filters."""
    positions, normals, uvs, polys = [], [], [], []
    for f in elements:
        faces = mtb.element_polys(f, tw, th)
        centre = np.mean([frame.geo(p) for pos, _ in faces for p in pos], axis=0)
        for pos, uv in faces:
            P = [frame.geo(p) for p in pos]
            q = np.array(P)
            if keep is not None and not keep(q.mean(0)):
                continue
            pieces = [list(range(len(P)))] if len(P) <= 4 else \
                [[0, k, k + 1, k + 2] if k + 2 < len(P) else [0, k, k + 1] for k in range(1, len(P) - 1, 2)]
            for idx in pieces:
                qq = q[idx]
                n = np.cross(qq[1] - qq[0], qq[2] - qq[1])
                if len(idx) == 4 and np.linalg.norm(n) < 1e-9:
                    n = np.cross(qq[2] - qq[0], qq[3] - qq[2])
                ln = np.linalg.norm(n)
                if ln < 1e-9:
                    continue
                n = n / ln
                order = idx
                if np.dot(n, qq.mean(0) - centre) < 0:
                    order, n = idx[::-1], -n
                normals.append([round(float(c), 5) for c in n])
                poly = []
                for i in order:
                    positions.append([round(float(c), 5) for c in P[i]])
                    uvs.append([round(uv[i][0] / tw, 6), round(1 - uv[i][1] / th, 6)])
                    poly.append([len(positions) - 1, len(normals) - 1, len(uvs) - 1])
                polys.append(poly)
    return {'normalized_uvs': True, 'positions': positions, 'normals': normals, 'uvs': uvs, 'polys': polys}


def bounds(meshes):
    pts = [p for m in meshes for p in m['positions']]
    if not pts:
        return None
    P = np.array(pts)
    return P.min(0), P.max(0)
