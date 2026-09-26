#!/usr/bin/env python3
"""Orthographic, alpha-tested, textured renders of a model (side, top, front, rear) around a window, for looking at
canopies by eye. Also returns the depth and hit-triangle index per pixel, for measurements."""
import os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
import thin as T  # noqa: E402

# view: (image right axis, image up axis, viewing direction); vehicle-local x left, y up, z forward
VIEWS = {
    'left':  (np.array([0, 0, -1.0]), np.array([0, 1.0, 0]), np.array([-1.0, 0, 0])),  # from +x, nose to the left
    'right': (np.array([0, 0, 1.0]), np.array([0, 1.0, 0]), np.array([1.0, 0, 0])),
    'top':   (np.array([-1.0, 0, 0]), np.array([0, 0, 1.0]), np.array([0, -1.0, 0])),  # nose up
    'front': (np.array([-1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, -1.0])),  # looking aft at the nose
    'rear':  (np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])),
}


def model(vid):
    _, geo = T.load_geo(vid)
    tris, uvs = T.triangles(geo)
    return tris, uvs, T.load_texture(vid)


def render(tris, uvs, tex, view, centre, half_w, half_h, step=0.01, alpha=True):
    """(colour HxWx3, depth HxW along the view, index HxW) for the window centre +- (half_w, half_h)."""
    R, U, D = VIEWS[view]
    rel = tris - centre
    a = rel @ R; b = rel @ U; d = rel @ D
    nx = int(2 * half_w / step); ny = int(2 * half_h / step)
    depth = np.full((ny, nx), np.inf); index = np.full((ny, nx), -1)
    H, W = tex.shape[:2]
    keep = (a.max(1) > -half_w) & (a.min(1) < half_w) & (b.max(1) > -half_h) & (b.min(1) < half_h)
    color = np.zeros((ny, nx, 3))
    for ti in np.nonzero(keep)[0]:
        A = np.stack([a[ti], b[ti]], 1)
        i0 = max(0, int(np.ceil((A[:, 0].min() + half_w) / step))); i1 = min(nx - 1, int(np.floor((A[:, 0].max() + half_w) / step)))
        j0 = max(0, int(np.ceil((half_h - A[:, 1].max()) / step))); j1 = min(ny - 1, int(np.floor((half_h - A[:, 1].min()) / step)))
        if i1 < i0 or j1 < j0:
            continue
        X, Y = np.meshgrid(-half_w + np.arange(i0, i1 + 1) * step, half_h - np.arange(j0, j1 + 1) * step)
        (x0, y0), (x1, y1), (x2, y2) = A
        den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(den) < 1e-12:
            continue
        w0 = ((y1 - y2) * (X - x2) + (x2 - x1) * (Y - y2)) / den
        w1 = ((y2 - y0) * (X - x2) + (x0 - x2) * (Y - y2)) / den
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not inside.any():
            continue
        z = w0 * d[ti, 0] + w1 * d[ti, 1] + w2 * d[ti, 2]
        uv = w0[..., None] * uvs[ti, 0] + w1[..., None] * uvs[ti, 1] + w2[..., None] * uvs[ti, 2]
        tx = np.clip((uv[..., 0] * W).astype(int), 0, W - 1); ty = np.clip(((1 - uv[..., 1]) * H).astype(int), 0, H - 1)
        texel = tex[ty, tx]
        if alpha:
            inside &= texel[..., 3] > 0.5
        blk = depth[j0:j1 + 1, i0:i1 + 1]
        nearer = inside & (z < blk)
        blk[nearer] = z[nearer]
        color[j0:j1 + 1, i0:i1 + 1][nearer] = texel[..., :3][nearer]
        index[j0:j1 + 1, i0:i1 + 1][nearer] = ti
    return color, depth, index


def to_pixel(p, view, centre, half_w, half_h, step):
    R, U, _ = VIEWS[view]
    rel = np.asarray(p) - centre
    return ((rel @ R + half_w) / step, (half_h - rel @ U) / step)


def image(color, depth, scale=2, sky=(150, 190, 235), shade=True):
    from PIL import Image
    img = color.copy()
    if shade:
        fin = np.isfinite(depth)
        if fin.any():
            lo, hi = depth[fin].min(), depth[fin].max()
            s = 1.0 - 0.45 * (depth - lo) / max(hi - lo, 1e-6)
            img = img * np.where(fin, s, 1.0)[..., None]
    img = np.where(np.isfinite(depth)[..., None], img * 255, np.array(sky))
    im = Image.fromarray(img.astype(np.uint8))
    return im.resize((im.width * scale, im.height * scale), Image.NEAREST)
