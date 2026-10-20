"""a vehicle model for canopy glass work: alpha-tested ray casts in the vehicle frame (blocks; x left, y up, z forward)"""
import hashlib, io, json, os, re, subprocess
import numpy as np
from PIL import Image
import bpy  # noqa: F401  (mathutils comes with the bpy module)
from mathutils import Vector
from mathutils.bvhtree import BVHTree

REPO = '/home/claude/bvp-sbw'
GEN = REPO + '/bvp/src/generated/resources'
GEO = 'bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo/{}.geo.json'
ASSET = 'bvp/src/generated/resources/assets/berts_vehicle_pack/sbw/vehicles/{}.json'
DATA = 'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles/{}.json'
TEX = 'bvp/src/generated/resources/assets/berts_vehicle_pack/textures/entity/{}.png'


def git_bytes(ref, path):
    return subprocess.check_output(['git', '-C', REPO, 'show', f'{ref}:{path}'])


def texture_bytes(ref, vid):
    """the vehicle's texture at ref; a Git LFS pointer is resolved through git lfs, or else from the folder named by
    CANOPY_FIT_TEX holding a copy with the same hash"""
    b = git_bytes(ref, TEX.format(vid)) if ref else open(os.path.join(REPO, TEX.format(vid)), 'rb').read()
    if not b.startswith(b'version https://git-lfs'):
        return b
    oid = b.split(b'sha256:')[1].split()[0].decode()
    d = os.environ.get('CANOPY_FIT_TEX')
    if d and os.path.exists(os.path.join(d, vid + '.png')):
        c = open(os.path.join(d, vid + '.png'), 'rb').read()
        if hashlib.sha256(c).hexdigest() == oid:
            return c
    return subprocess.run(['git', '-C', REPO, 'lfs', 'smudge'], input=b, capture_output=True, check=True).stdout


def git_json(ref, path):
    return json.loads(git_bytes(ref, path))


class Model:
    def __init__(self, vid, ref=None, geo=None):
        self.vid = vid
        rd = (lambda p: git_json(ref, p)) if ref else (lambda p: json.load(open(os.path.join(REPO, p))))
        self.geo = geo if geo is not None else rd(GEO.format(vid))
        self.data = rd(DATA.format(vid))
        self.asset = rd(ASSET.format(vid))
        tex = Image.open(io.BytesIO(texture_bytes(ref, vid)))
        self.alpha = np.array(tex.convert('RGBA'))[..., 3]
        self.rgb = np.array(tex.convert('RGB'))
        g = self.geo['minecraft:geometry'][0]
        T, U, B = [], [], []
        self.bone_names = []
        for b in g['bones']:
            pm = b.get('poly_mesh')
            if not pm or not pm.get('polys'):
                continue
            bi = len(self.bone_names); self.bone_names.append(b['name'])
            P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
            UV = np.array(pm['uvs'], float)
            for poly in pm['polys']:
                for k in range(1, len(poly) - 1):
                    c = [poly[0], poly[k], poly[k + 1]]
                    T.append(P[[v[0] for v in c]]); U.append(UV[[v[2] for v in c]]); B.append(bi)
        self.T = np.array(T); self.U = np.array(U); self.B = np.array(B)
        # rotors and propellers spin: no frame of the canopy, nothing the glass must reach up to
        spin = np.array([bool(re.search(r'rotor|propeller', n, re.I)) for n in self.bone_names] + [False])
        self.spins = spin[self.B] if len(self.B) else np.zeros(0, bool)
        h, w = self.alpha.shape
        self.tw, self.th = w, h
        # per triangle: fully opaque (1), fully transparent (0) or mixed (-1), from a 7-point sample
        bc = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [1/3, 1/3, 1/3], [.6, .2, .2], [.2, .6, .2], [.2, .2, .6]])
        uvs = np.einsum('sk,nkd->nsd', bc, self.U)
        a = self.sample_alpha(uvs.reshape(-1, 2)).reshape(len(T), -1) >= 128
        self.op = np.where(a.all(1), 1, np.where(~a.any(1), 0, -1))
        verts = self.T.reshape(-1, 3)
        self.bvh = BVHTree.FromPolygons([Vector(v) for v in verts], [(3*i, 3*i+1, 3*i+2) for i in range(len(T))],
                                        all_triangles=True, epsilon=0.0)
        self.N = np.cross(self.T[:, 1] - self.T[:, 0], self.T[:, 2] - self.T[:, 0])
        n = np.linalg.norm(self.N, axis=1, keepdims=True); self.N = self.N / np.maximum(n, 1e-12)

    def sample_alpha(self, uv):
        uv = np.asarray(uv, float)
        px = np.clip((uv[..., 0] * self.tw).astype(int), 0, self.tw - 1)
        py = np.clip(((1 - uv[..., 1]) * self.th).astype(int), 0, self.th - 1)
        return self.alpha[py, px]

    def colour(self, i, p):
        uv = self.uv_at(i, p)
        px = int(np.clip(uv[0] * self.tw, 0, self.tw - 1)); py = int(np.clip((1 - uv[1]) * self.th, 0, self.th - 1))
        return self.rgb[py, px]

    def uv_at(self, i, p):
        a, b, c = self.T[i]
        v0, v1, v2 = b - a, c - a, np.asarray(p) - a
        d00, d01, d11 = v0 @ v0, v0 @ v1, v1 @ v1
        d20, d21 = v2 @ v0, v2 @ v1
        den = d00 * d11 - d01 * d01
        if abs(den) < 1e-18:
            return self.U[i].mean(0)
        v = (d11 * d20 - d01 * d21) / den; w = (d00 * d21 - d01 * d20) / den
        return (1 - v - w) * self.U[i][0] + v * self.U[i][1] + w * self.U[i][2]

    def cast(self, o, d, maxd=50.0, skip=None):
        """first opaque hit along o + t d: (t, point, tri) or None, rotors and propellers passed through. skip(tri)
        -> True passes through that triangle too"""
        o = Vector(o); d = Vector(d).normalized(); t0 = 0.0
        for _ in range(64):
            loc, nrm, idx, dist = self.bvh.ray_cast(o, d, maxd - t0)
            if loc is None:
                return None
            t0 += dist
            ok = self.op[idx]
            if ok == -1:
                ok = self.sample_alpha(self.uv_at(idx, np.array(loc)))[()] >= 128
            if ok and not self.spins[idx] and not (skip and skip(idx)):
                return t0, np.array(loc), idx
            o = loc + d * 1e-4; t0 += 1e-4
        return None

    def eyes(self):
        """crew eye positions (vehicle frame), one per seat"""
        att = self.data.get('Attachments', {})
        out = []
        for s in self.data.get('Seats', []):
            name = (s.get('CameraPos') or {}).get('EyeAttachment')
            if name and name in att:
                out.append(np.array(att[name]['Position'], float))
            else:
                p = np.array(s['Position'], float); out.append(p + np.array([0, 1.62, 0]))
        return out
