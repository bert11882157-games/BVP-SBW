#!/usr/bin/env python3
"""Fits canopy glass to every aircraft as one smooth, connected shell.

From the pilot's eye the tool samples the view sphere on a fine azimuth/elevation grid:
  * the canopy hull is the convex hull of the nearby surfaces with nothing solid just beyond them (frames, bows,
    rails and the fuselage skin around the cockpit, not seats or consoles);
  * a direction looks through glass when, where the ray leaves that hull, there is no opaque surface (the texture is
    see-through there). Seats, gunsights and headrests between the eye and the hull do not matter;
  * frame bars are thin gaps in that mask and are closed over, so the glass runs continuously across them.
The glazed directions form one region (holes filled, the largest piece kept). Spokes from its middle to its edge
are resampled to ROWS points along the hull, with the edge smoothed around the rim, and neighbouring spokes join into
one grid: a smooth rim, no holes or ragged triangles.

Output: "CanopyGlass" in the asset vehicle JSON (vehicle-local blocks, +X left, +Y up, +Z forward):
  {"Schema":2,"Frame":"VEHICLE_LOCAL_BLOCKS","Eye":[x,y,z],"Axis":[unit],"Ref":[unit],"Rows":n,
   "Spokes":[[phi, edge, r0 ... r(n-1)], ...]}
Spokes run from Axis (the middle of the glazed region) out to its edge, evenly spaced in phi (degrees, from Ref
toward Axis x Ref). Row k of a spoke is at angle edge * k / (n - 1) from Axis, r blocks from the eye; neighbouring
spokes (wrapping round) are joined, so the glass is one disc-like sheet with a smooth rim.
Usage: python3 tools/canopy_glass/glass.py [--write] [--sheet DIR] [id...]
"""
import json, math, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

SHELL_RANGE = 1.8      # blocks from the eye: surfaces for the hull
BEHIND = (0.1, 0.9)    # solid in this range beyond a hit marks interior structure, not the shell
MIN_EL = -60.0
SAMPLE = 1.5           # degrees between mask samples
AZ_STEP = 6.0          # degrees between glass columns
ROWS = 12
CLOSE = 5              # samples: frame bars up to about this many degrees wide are glazed over
EXIT_BEFORE, EXIT_AFTER = 0.12, 0.3   # an opaque surface this close to the hull exit means no glass there
INSET = 0.006          # glass sits this far inside the hull
MIN_RUN = 8.0          # degrees: shorter glazed runs are ignored
# Aircraft whose pilot view sits inside a closed hull (no canopy the tool can see).
SKIP = {'ho_229', 'me_163', 'me_262_50mm', 'me_262_elite'}


def aircraft():
    return sorted(f[:-5] for f in os.listdir(os.path.join(T.GEN, 'data/berts_vehicle_pack/flight_reference')))


def direction(az, el):
    a, e = np.radians(az), np.radians(el)
    return np.stack([np.cos(e) * np.sin(a), np.sin(e), np.cos(e) * np.cos(a)], -1)


def shell_hull(depth, caster, eye):
    from scipy.spatial import ConvexHull
    pts = []
    for el in np.arange(MIN_EL, 90.1, 3.0):
        n_az = max(8, int(round(120 * math.cos(math.radians(el)))))
        for az in np.linspace(-180, 180, n_az, endpoint=False):
            d = direction(az, el)
            t = float(T.lookup(depth, d[None])[0])
            if t < SHELL_RANGE and not caster.hits(d, t + BEHIND[0], t + BEHIND[1]):
                pts.append(eye + d * t)
    if len(pts) < 20:
        return None
    hull = ConvexHull(np.array(pts))
    if (hull.equations[:, :3] @ eye + hull.equations[:, 3]).max() > -0.05:
        return None                         # the eye is not inside the canopy shell
    return hull


def exit_distance(hull, eye, dirs):
    """Distance from the eye to where each ray leaves the hull."""
    n, off = hull.equations[:, :3], hull.equations[:, 3]
    nd = dirs @ n.T                                           # (..., planes)
    dist = -(n @ eye + off)                                   # > 0 inside
    with np.errstate(divide='ignore', invalid='ignore'):
        t = np.where(nd > 1e-9, dist / nd, np.inf)
    return t.min(-1)


def glass_mask(depth, caster, hull, eye):
    azs = np.arange(-180, 180, SAMPLE)
    els = np.arange(MIN_EL, 90.0 + 1e-6, SAMPLE)
    A, E = np.meshgrid(azs, els, indexing='ij')
    dirs = direction(A, E)
    rh = exit_distance(hull, eye, dirs)
    first = T.lookup(depth, dirs.reshape(-1, 3)).reshape(A.shape)
    mask = np.zeros(A.shape, bool)
    for i in range(A.shape[0]):
        for j in range(A.shape[1]):
            r = rh[i, j]
            if not np.isfinite(r):
                continue
            t = first[i, j]
            if t >= r + EXIT_AFTER:
                mask[i, j] = True                  # clear view through the hull boundary
            elif t >= r - EXIT_BEFORE:
                mask[i, j] = False                 # opaque skin/frame at the boundary
            else:                                  # something inside (seat, sight, panel): look past it
                mask[i, j] = not caster.hits(dirs[i, j], r - EXIT_BEFORE, r + EXIT_AFTER)
            if mask[i, j] and els[j] < 10.0:
                # Glass sits on the airframe: straight below it there must be fuselage, not open air beside it.
                mask[i, j] = below(caster, eye + dirs[i, j] * r)
    return azs, els, mask, rh


DOWN = np.array([0.0, -1.0, 0.0])
DOWN_RANGE = 0.9       # blocks: glass lower than this above the fuselage skin is air beside the nose


def down_distance(caster, point, reach):
    """Distance straight down from point to the first opaque surface within reach, or None."""
    lo, hi = 0.005, reach
    if not caster_hits_from(caster, point, DOWN, lo, hi):
        return None
    for _ in range(12):
        mid = (lo + hi) / 2
        if caster_hits_from(caster, point, DOWN, lo, mid):
            hi = mid
        else:
            lo = mid
    return hi


def caster_hits_from(caster, point, d, tmin, tmax):
    eye = caster.eye
    caster.eye = point
    try:
        return caster.hits(d, tmin, tmax)
    finally:
        caster.eye = eye


def below(caster, point):
    eye = caster.eye
    caster.eye = point
    try:
        return caster.hits(DOWN, 0.005, DOWN_RANGE)
    finally:
        caster.eye = eye


RASTER = 181   # zenith-centred azimuthal raster of the view sphere down to MIN_EL


def to_raster(dirs):
    el = np.degrees(np.arcsin(np.clip(dirs[..., 1], -1, 1)))
    az = np.degrees(np.arctan2(dirs[..., 0], dirs[..., 2]))
    rho = (90.0 - el) / (90.0 - MIN_EL) * (RASTER // 2)
    c = RASTER // 2
    x = c + rho * np.sin(np.radians(az)); y = c - rho * np.cos(np.radians(az))
    return np.round(x).astype(int), np.round(y).astype(int)


def region(azs, els, mask):
    """The glazed region as a zenith-centred raster: frame bars closed over, holes filled, largest piece kept."""
    from scipy.ndimage import binary_closing, binary_fill_holes, label
    c = RASTER // 2
    yy, xx = np.mgrid[0:RASTER, 0:RASTER]
    rho = np.hypot(xx - c, yy - c) / (RASTER // 2) * (90.0 - MIN_EL)
    az = np.degrees(np.arctan2(xx - c, -(yy - c)))
    el = 90.0 - rho
    inside = el >= MIN_EL
    i = np.clip(np.round((az + 180) / SAMPLE).astype(int) % len(azs), 0, len(azs) - 1)
    j = np.clip(np.round((el - MIN_EL) / SAMPLE).astype(int), 0, len(els) - 1)
    img = mask[i, j] & inside
    k = int(round(CLOSE * SAMPLE / ((90.0 - MIN_EL) / (RASTER // 2))))
    disk = np.hypot(*np.mgrid[-k:k + 1, -k:k + 1]) <= k
    img = binary_closing(np.pad(img, k + 1), disk)[k + 1:-(k + 1), k + 1:-(k + 1)] & inside
    img = binary_fill_holes(img)
    lab, n = label(img)
    if n == 0:
        return None
    sizes = np.bincount(lab.ravel())[1:]
    return lab == (1 + int(sizes.argmax()))


def raster_dirs():
    c = RASTER // 2
    yy, xx = np.mgrid[0:RASTER, 0:RASTER]
    rho = np.hypot(xx - c, yy - c) / (RASTER // 2) * (90.0 - MIN_EL)
    az = np.degrees(np.arctan2(xx - c, -(yy - c)))
    return direction(az, 90.0 - rho)


def spoke_dirs(axis, ref, phi, rho):
    side = np.cross(axis, ref)
    p, r = np.radians(phi), np.radians(rho)
    return (np.cos(r)[..., None] * axis + np.sin(r)[..., None]
            * (np.cos(p)[..., None] * ref + np.sin(p)[..., None] * side))


def circular_smooth(values, width):
    n = len(values)
    idx = (np.arange(n)[:, None] + np.arange(-width, width + 1)[None]) % n
    med = np.median(values[idx], axis=1)
    return med[idx].mean(axis=1)


RIM_PROBE = 2.0        # degrees past the glazed edge where the rail is measured
RIM_DROP = 0.9         # blocks: a rail lower than this under the eye is the airframe beyond the canopy
RIM_RANGE = 1.6        # blocks: farther surfaces past the edge are not the rail
FRAME_RANGE = 1.3      # blocks: frame bars crossing the glazed region
APEX = 0.3             # blocks above the eye when nothing shows the canopy height (frameless bubbles)
CLEARANCE = 0.15       # the glass clears the pilot's eye by at least this much


def rail_width(depth, eye):
    """Half-width of the cockpit at its rails: the outermost cockpit wall the pilot sees straight to either side."""
    best = 0.0
    for az in (90.0, -90.0):
        prev = None
        for el in np.arange(-70.0, 1.0, 2.0):          # up the cockpit wall until the view jumps past the rail
            d = direction(az, el)
            t = float(T.lookup(depth, d[None])[0])
            if t >= 1.5 or (prev is not None and t > prev * 1.25 + 0.03):
                break
            best = max(best, abs(d[0] * t))
            prev = t
    return max(best, 0.25) + 0.04


def bubble(eye, rim):
    """Frameless canopy: half an ellipsoid standing on the canopy rails, as wide as the rails, as long as the
    glazed rim and APEX above the eye. Returns the eye-ray exit distance function, or None."""
    rel = rim - eye
    band = rim[(rel[:, 1] > -0.9) & (rel[:, 1] < 0.2)]
    if len(band) < 6:
        return None
    a = float(np.clip(np.percentile(np.abs(band[:, 0]), 90), 0.2, 1.2))
    rails = band[np.abs(band[:, 0]) > 0.6 * a]
    yc = float(np.median(rails[:, 1])) if len(rails) else float(np.median(band[:, 1]))
    zc = float((rim[:, 2].min() + rim[:, 2].max()) / 2)
    c = float((rim[:, 2].max() - rim[:, 2].min()) / 2 * 1.05)
    b = eye[1] + APEX - yc
    if c < 0.3 or b < 0.15:
        return None
    ex, ey, ez = eye[0] / a, (eye[1] - yc) / b, (eye[2] - zc) / c
    if ex * ex + ey * ey + ez * ez >= 1:
        # The eye must sit inside: raise the dome until it clears the pilot.
        need = (eye[1] + CLEARANCE - yc) / math.sqrt(max(1e-6, 1 - ex * ex - min(ez * ez, 0.95)))
        b = max(b, need)

    def exit_(dirs):
        o = np.array([eye[0] / a, (eye[1] - yc) / b, (eye[2] - zc) / c])
        d = dirs / np.array([a, b, c])
        A = (d * d).sum(-1); B = 2 * (d @ o); C = o @ o - 1
        return (-B + np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A)
    return exit_


def frame_points(depth, caster, eye, img):
    """Canopy frames (bows, bars) crossing the glazed region: near surfaces with nothing solid just beyond."""
    d = raster_dirs()[img]
    t = T.lookup(depth, d).astype(float)
    out = []
    for dd, tt in zip(d, t):
        if tt < FRAME_RANGE and not caster.hits(dd, tt + 0.1, tt + 0.9):
            out.append(eye + dd * tt)
    return np.array(out).reshape(-1, 3)


def glass(vid):
    _, geo = T.load_geo(vid)
    tex = T.load_texture(vid)
    eye = T.eyes(vid)[0]
    if vid in SKIP:
        return eye, None
    tris, uvs = T.triangles(geo)
    depth = T.render_depth(tris, uvs, tex, eye)
    caster = T.Caster(tris, uvs, tex, eye)
    hull = shell_hull(depth, caster, eye)
    if hull is None:
        return eye, None
    azs, els, mask, _ = glass_mask(depth, caster, hull, eye)
    img = region(azs, els, mask)
    if img is None or img.sum() < 40:
        return eye, None
    # Spokes from the middle of the glazed region out to its edge.
    # Spoke centre: the point of the region deepest inside it (on the centre line; the region is symmetric).
    from scipy.ndimage import distance_transform_edt
    depth_in = distance_transform_edt(img)
    c = RASTER // 2
    xs = np.arange(RASTER)
    col = depth_in[:, c]
    if col.max() <= 0:
        return eye, None
    j = int(col.argmax())
    axis = raster_dirs()[j, c].copy(); axis[0] = 0.0; axis /= np.linalg.norm(axis)
    ref = np.array([0.0, 0.0, 1.0]) - axis[2] * axis
    if np.linalg.norm(ref) < 1e-3:
        ref = np.array([1.0, 0.0, 0.0]) - axis[0] * axis
    ref /= np.linalg.norm(ref)
    phis = np.arange(0, 360, AZ_STEP)
    rhos = np.arange(0, 125, 0.5)
    P, R = np.meshgrid(phis, rhos, indexing='ij')
    x, y = to_raster(spoke_dirs(axis, ref, P, R))
    ok = (x >= 0) & (x < RASTER) & (y >= 0) & (y < RASTER)
    member = np.zeros(P.shape, bool)
    member[ok] = img[y[ok], x[ok]]
    if not member[:, 0].all():
        return eye, None
    ends = np.append(rhos, rhos[-1] + 0.5)
    edge = np.array([ends[np.argmin(np.append(row, False))] - 0.5 for row in member])
    edge = circular_smooth(edge, 2)
    if edge.min() < 3:
        return eye, None
    # Where each spoke meets the canopy rail: the first surface just past the glazed edge.
    rim_dirs = spoke_dirs(axis, ref, phis, edge + RIM_PROBE)
    rim_t = T.lookup(depth, rim_dirs).astype(float)
    for extra in np.arange(2.0, 6.1, 2.0):              # rail lower than the glazed edge: look further out
        miss = rim_t >= RIM_RANGE
        if not miss.any():
            break
        d2 = spoke_dirs(axis, ref, phis[miss], edge[miss] + RIM_PROBE + extra)
        rim_dirs[miss] = d2
        rim_t[miss] = T.lookup(depth, d2)
    ok = rim_t < RIM_RANGE
    # Otherwise the rail is straight below the glazed edge (canopies wider than the pilot can see past).
    edge_dirs = spoke_dirs(axis, ref, phis, edge)
    edge_r = exit_distance(hull, eye, edge_dirs)
    for i in np.where(~ok)[0]:
        if not np.isfinite(edge_r[i]):
            continue
        p0 = eye + edge_dirs[i] * edge_r[i]
        h = down_distance(caster, p0, 1.0)
        if h is not None:
            q = p0 + DOWN * h - eye
            rim_t[i] = np.linalg.norm(q); rim_dirs[i] = q / rim_t[i]; ok[i] = True
    if ok.sum() < len(phis) // 2:
        return eye, None
    rim = rim_dirs * np.where(ok, rim_t, 0)[:, None]
    idx = np.arange(len(phis))
    for c in range(3):                                   # fill spokes without a rail from their neighbours
        rim[:, c] = np.interp(idx, idx[ok], rim[ok, c], period=len(phis))
    # Left/right symmetric (spoke phi mirrors 360 - phi), then smoothed round the rim.
    mirror = (-idx) % len(phis)
    rim = (rim + rim[mirror] * np.array([-1.0, 1, 1])) / 2
    for c in range(3):
        rim[:, c] = circular_smooth(rim[:, c], 2)
    rim = rim + eye
    frames = frame_points(depth, caster, eye, img)
    tallest = frames[:, 1].max() if len(frames) else -np.inf
    if tallest < eye[1] + CLEARANCE:
        shell_exit = bubble(eye, rim)                    # frameless bubble: nothing modelled above the pilot
        if shell_exit is None:
            return eye, None
    else:
        points = [rim, frames, eye + np.array([[0.0, -0.8, 0.0]])]   # the last closes the shell under the pilot
        P = np.vstack(points)
        P = np.vstack([P, P * np.array([-1.0, 1, 1])])
        from scipy.spatial import ConvexHull
        try:
            shell = ConvexHull(P)
        except Exception:
            return eye, None
        if (shell.equations[:, :3] @ eye + shell.equations[:, 3]).max() > -0.05:
            return eye, None
        shell_exit = lambda dirs: exit_distance(shell, eye, dirs)
    width = rail_width(depth, eye)
    s_k = np.sin(np.arange(0, ROWS) / (ROWS - 1) * math.pi / 2)
    spokes = []
    apex = None
    for phi, e in zip(phis, edge):
        dirs = spoke_dirs(axis, ref, np.full(ROWS, phi), e * s_k)
        r = shell_exit(dirs)
        r = np.minimum(r, width / np.maximum(np.abs(dirs[:, 0]), 1e-6)) - INSET   # never wider than the rails
        if not np.isfinite(r).all():
            return eye, None
        ring = eye + dirs * r[:, None]
        apex = ring[0]
        spokes.append([round(float(v), 4) for v in ring[1:].ravel()])
    return eye, {'Schema': 2, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Apex': [round(float(v), 4) for v in apex],
                 'Rings': ROWS - 1, 'Spokes': spokes}


def triangulate(block):
    """The runtime's triangulation of a Schema 2 dome (CanopyGlass.kt): list of (3, 3) vertex arrays."""
    apex = np.array(block['Apex']); n = block['Rings']
    pts = [np.array(sp).reshape(n, 3) for sp in block['Spokes']]
    tris = []
    for i in range(len(pts)):
        a, b = pts[i], pts[(i + 1) % len(pts)]
        tris.append(np.array([apex, a[0], b[0]]))
        for k in range(n - 1):
            tris.append(np.array([a[k], b[k], b[k + 1]]))
            tris.append(np.array([a[k], b[k + 1], a[k + 1]]))
    return tris


def render_check(vid, eye, block, out):
    """Pilot fisheye with the glass grid outlined, plus side and top views of the glass over the airframe."""
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from PIL import Image
    _, geo = T.load_geo(vid)
    fish = out + '.fish.png'
    T.view(vid, geo, eye, fish)
    im = np.asarray(Image.open(fish).convert('RGB'))
    tris = triangulate(block) if block else []
    size, radius = 420, 115.0
    c = np.array([0.0, math.sin(math.radians(40)), math.cos(math.radians(40))])
    right = -np.cross(np.array([0, 1.0, 0]), c); right /= np.linalg.norm(right)
    up = np.cross(c, -right); up /= np.linalg.norm(up)
    if up[1] < 0:
        up = -up

    def proj(p):
        d = (p - eye) / np.linalg.norm(p - eye)
        rho = math.acos(np.clip(d @ c, -1, 1))
        t = d - (d @ c) * c
        phi = math.atan2(t @ up, t @ right)
        x = rho / math.radians(radius) * math.cos(phi); y = rho / math.radians(radius) * math.sin(phi)
        return ((x + 1) / 2 * size, (1 - y) / 2 * size)
    fig, axes = plt.subplots(1, 3, figsize=(15, 5))
    axes[0].imshow(im)
    from matplotlib.patches import Polygon
    for tri in tris:
        axes[0].add_patch(Polygon([proj(p) for p in tri], closed=True, fc=(0.4, 0.8, 1, 0.25), ec=(1, 1, 0, 0.5), lw=0.3))
    axes[0].set_title(vid + ' (pilot view)'); axes[0].axis('off')
    atris, _ = T.triangles(geo)
    near = np.linalg.norm(atris.mean(1) - eye, axis=1) < 4
    for ax, (u, v, name) in zip(axes[1:], ((2, 1, 'side'), (0, 2, 'top'))):
        for tri in atris[near]:
            ax.add_patch(Polygon(tri[:, [u, v]], closed=True, fc=(0.6, 0.6, 0.6, 0.15), ec='none'))
        for tri in tris:
            ax.add_patch(Polygon(tri[:, [u, v]], closed=True, fc=(0.3, 0.7, 1, 0.35), ec=(0, 0.2, 0.6, 0.6), lw=0.3))
        ax.set_xlim(eye[u] - 3, eye[u] + 3); ax.set_ylim(eye[v] - 2.2, eye[v] + 2.2)
        ax.set_aspect('equal'); ax.set_title(name)
    fig.tight_layout(); fig.savefig(out, dpi=70); plt.close(fig)
    os.remove(fish)


def main(argv):
    write = '--write' in argv
    sheet = argv[argv.index('--sheet') + 1] if '--sheet' in argv else None
    ids = [a for a in argv if not a.startswith('--') and a != sheet] or aircraft()
    for vid in ids:
        eye, block = glass(vid)
        print(f'{vid:34s} ' + (f"{len(block['Spokes'])} spokes" if block else 'no glass'), flush=True)
        if sheet:
            os.makedirs(sheet, exist_ok=True)
            render_check(vid, eye, block, os.path.join(sheet, vid + '.png'))
        if write:
            path = os.path.join(T.GEN, 'assets/berts_vehicle_pack/sbw/vehicles', vid + '.json')
            raw = open(path, encoding='utf-8').read()
            doc = json.loads(raw)
            if block:
                doc['CanopyGlass'] = block
            else:
                doc.pop('CanopyGlass', None)
            indent = 2 if raw.startswith('{\n') else None
            open(path, 'w', encoding='utf-8').write(json.dumps(doc, indent=indent, ensure_ascii=False)
                                                    + ('\n' if raw.endswith('\n') else ''))


if __name__ == '__main__':
    main(sys.argv[1:])
