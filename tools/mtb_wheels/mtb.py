"""SMP Toolbox .mtb reader: Box / Shapebox / Trapezoid elements (the same reconstruction as
tools/mtb_export/export_smp_mtb_obj.mjs) and Shape elements (ModelRendererTurbo.addShape3D: a 2D polygon extruded
by its depth), as textured polygons in the toolbox frame (+x forward, +y down, +z left)."""
import io, math, zipfile

CORNER_BITS = [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]]
STORED_CORNER_ORDER = [0, 1, 5, 4, 3, 2, 6, 7]
TRAPEZOID_FACES = {'MR_RIGHT': (0, 0), 'MR_LEFT': (0, 1), 'MR_FRONT': (2, 0), 'MR_BACK': (2, 1),
                   'MR_TOP': (1, 0), 'MR_BOTTOM': (1, 1)}
BOX_FACES = [[5, 1, 2, 6], [0, 4, 7, 3], [5, 4, 0, 1], [2, 3, 7, 6], [1, 0, 3, 2], [4, 5, 6, 7]]


def num(v):
    return float(str(v).replace(',', '.'))


def uv_dim(v):
    if v % 1 == 0:
        return v
    if v < 1:
        return 1
    return math.trunc(v) + (1 if v % 1 > 0.5 else 0)


def rot_x(p, d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return [p[0], p[1] * c - p[2] * s, p[1] * s + p[2] * c]


def rot_y(p, d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return [p[0] * c + p[2] * s, p[1], -p[0] * s + p[2] * c]


def rot_z(p, d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return [p[0] * c - p[1] * s, p[0] * s + p[1] * c, p[2]]


def place(p, f):
    """Local element point -> model frame: X, then -Z, then Y rotation about the rotation point, then translate."""
    q = rot_y(rot_z(rot_x(p, num(f[12])), -num(f[14])), num(f[13]))
    return [q[0] + num(f[6]), q[1] + num(f[7]), q[2] + num(f[8])]


def read(path):
    """(texture size, list of element field lists, embedded texture bytes)."""
    z = zipfile.ZipFile(path)
    text = z.read('Model.txt').decode('utf-8', 'replace')
    png = z.read('Model.png') if 'Model.png' in z.namelist() else None
    tw = th = None
    elements = []
    for line in text.splitlines():
        if line.startswith('TexSizeX|'):
            tw = int(line.split('|')[1])
        elif line.startswith('TexSizeY|'):
            th = int(line.split('|')[1])
        elif line.startswith('Element|'):
            elements.append(line.split('|'))
    return (tw, th), elements, png


def box_corners(f):
    off = [num(f[15]), num(f[16]), num(f[17])]
    w, h, d = num(f[9]), num(f[10]), num(f[11])
    kind = f[5]
    if kind == 'Trapezoid':
        face = TRAPEZOID_FACES[f[44]]
        amount = num(f[45])
        local, ok = [], True
        for ci, bits in enumerate(CORNER_BITS):
            sel = bits[face[0]] == face[1]
            p = []
            for axis, bit in enumerate(bits):
                e = amount if sel and axis != face[0] else 0
                if abs(num(f[20 + axis * 8 + STORED_CORNER_ORDER[ci]]) - e) > 1e-7:
                    ok = False
                p.append(off[axis] + bit * (w, h, d)[axis] + (1 if bit else -1) * e)
            local.append(p)
        if ok:
            return [place(p, f) for p in local]
        kind = 'Shapebox'
    if kind == 'Shapebox':
        c = [[num(f[20 + i]), num(f[28 + i]), num(f[36 + i])] for i in range(8)]
        local = [
            [off[0] - c[0][0], off[1] - c[0][1], off[2] - c[0][2]],
            [off[0] + w + c[1][0], off[1] - c[1][1], off[2] - c[1][2]],
            [off[0] + w + c[5][0], off[1] + h + c[5][1], off[2] - c[5][2]],
            [off[0] - c[4][0], off[1] + h + c[4][1], off[2] - c[4][2]],
            [off[0] - c[3][0], off[1] - c[3][1], off[2] + d + c[3][2]],
            [off[0] + w + c[2][0], off[1] - c[2][1], off[2] + d + c[2][2]],
            [off[0] + w + c[6][0], off[1] + h + c[6][1], off[2] + d + c[6][2]],
            [off[0] - c[7][0], off[1] + h + c[7][1], off[2] + d + c[7][2]],
        ]
    else:  # Box
        x1 = off[0] + w + (0.01 if w == 0 else 0)
        y1 = off[1] + h + (0.01 if h == 0 else 0)
        z1 = off[2] + d + (0.01 if d == 0 else 0)
        local = [[off[0], off[1], off[2]], [x1, off[1], off[2]], [x1, y1, off[2]], [off[0], y1, off[2]],
                 [off[0], off[1], z1], [x1, off[1], z1], [x1, y1, z1], [off[0], y1, z1]]
    return [place(p, f) for p in local]


def box_polys(f, tw, th):
    """[(positions[4], uvs[4])] with uvs in texels (u right, v down)."""
    corners = box_corners(f)
    tx, ty = num(f[18]), num(f[19])
    w, h, d = uv_dim(num(f[9])), uv_dim(num(f[10])), uv_dim(num(f[11]))

    def rect(x, y, ex, ey):
        return [(tx + x + ex, ty + y), (tx + x, ty + y), (tx + x, ty + y + ey), (tx + x + ex, ty + y + ey)]
    uvs = [rect(d + w, d, d, h), rect(0, d, d, h), rect(d, 0, w, d), rect(d + w, 0, w, d),
           rect(d, d, w, h), rect(d + w + d, d, w, h)]
    return [([corners[i] for i in face], uvs[k]) for k, face in enumerate(BOX_FACES)]


def shape_outline(f):
    """The Shape element's 2D polygon: (x, y) vertex list (fields 58.. and 78..; count at 98)."""
    n = int(num(f[98]))
    return [(num(f[58 + i]), num(f[78 + i])) for i in range(n)]


def shape_polys(f, tw, th):
    """ModelRendererTurbo.addShape3D with the toolbox's default MR_FRONT facing: the outline lies in the element's
    x/y plane, mirrored in x about the offset, and is extruded by the depth along +z. Face textures: the outline at
    the texture offset (front) and mirrored beside it (back); the sides as one strip below, each edge taking its
    share of the outline's length."""
    ox, oy, oz = num(f[15]), num(f[16]), num(f[17])
    depth = num(f[11])
    sw, sh = uv_dim(num(f[9])), uv_dim(num(f[10]))
    tx, ty = num(f[18]), num(f[19])
    pts = shape_outline(f)
    n = len(pts)
    front = [place([ox - x, oy + y, oz], f) for x, y in pts]
    back = [place([ox - x, oy + y, oz + depth], f) for x, y in pts]
    uf = [(tx + x, ty + y) for x, y in pts]
    ub = [(tx + sw * 2 - x, ty + y) for x, y in pts]
    polys = [(front[::-1], uf[::-1]), (back, ub)]
    lengths = [math.dist(pts[i], pts[(i + 1) % n]) for i in range(n)]
    total = sum(lengths) or 1.0
    side_w = total
    pos = 0.0
    v0, v1 = ty + sh, ty + sh + uv_dim(depth)
    for i in range(n):
        j = (i + 1) % n
        u0 = tx + pos / total * side_w
        pos += lengths[i]
        u1 = tx + pos / total * side_w
        polys.append(([front[i], front[j], back[j], back[i]], [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]))
    return polys


def element_polys(f, tw, th):
    if f[5] == 'Shape':
        return shape_polys(f, tw, th)
    return box_polys(f, tw, th)
