"""the cockpit seen from above: a top-down height map of the model around the crew, the cockpit opening in it and its
rim (sill line), and the skin height across a station"""
import numpy as np
from scipy import ndimage


def heightmap(m, E, xr=1.4, zb=3.5, zf=4.0, step=0.01, top=3.0):
    """first opaque hit straight down from E.y + top over x in [-xr, xr], z in [E.z - zb, E.z + zf]"""
    xs = np.arange(-xr, xr + 1e-9, step); zs = np.arange(E[2] - zb, E[2] + zf + 1e-9, step)
    H = np.full((len(zs), len(xs)), np.nan)
    for j, z in enumerate(zs):
        for i, x in enumerate(xs):
            h = m.cast((x, E[1] + top, z), (0, -1, 0), maxd=top + 5.0)
            if h:
                H[j, i] = h[1][1]
    return xs, zs, H


def opening(xs, zs, H, E, t_lo=None, t_hi=None, step=0.005, bridge=0.3, bridge_x=0.12):
    """the opening grown from the lowest cell near the eye at the highest level that keeps it off the map's border
    (closed along z so seat bars do not split it, and across thin walls such as seat rails where that gives the larger
    opening, holes filled) -> (mask, rim level)"""
    dz = abs(zs[1] - zs[0])
    j0 = int(np.argmin(abs(zs - E[2]))); i0 = int(np.argmin(abs(xs - E[0])))
    Hf = np.where(np.isnan(H), -1e9, H)
    t_lo = E[1] - 1.5 if t_lo is None else t_lo
    t_hi = E[1] + 0.3 if t_hi is None else t_hi
    win = Hf[max(0, j0 - 25):j0 + 25, max(0, i0 - 15):i0 + 15]
    jj, ii = np.unravel_index(np.argmin(np.where(win < -1e8, 1e9, win)), win.shape)
    seed = (max(0, j0 - 25) + jj, max(0, i0 - 15) + ii)
    kz = np.ones((max(3, int(round(bridge / dz))), 1), bool)
    kx = np.ones((1, max(3, int(round(bridge_x / dz)))), bool)
    found = [b for b in (_grow(Hf, seed, t_lo, t_hi, step, ks) for ks in ((kz,), (kz, kx))) if b is not None]
    if not found:
        return None, None
    # compared as mirrored about the centre line, the way the canopy will take it (a centre bar halves an opening)
    reg, t = max(found, key=lambda b: (b[0] | b[0][:, ::-1]).sum())
    return ndimage.binary_fill_holes(reg), t


def _grow(Hf, seed, t_lo, t_hi, step, kernels):
    best = None
    for t in np.arange(t_lo, t_hi, step):
        low = Hf < t
        for k in kernels:
            low = low | ndimage.binary_closing(Hf < t, structure=k)
        lab, n = ndimage.label(low)
        k = lab[seed]
        if k == 0:
            continue
        reg = lab == k
        if reg[0].any() or reg[-1].any() or reg[:, 0].any() or reg[:, -1].any():
            break
        best = (reg, t)
    return best


def rim(xs, zs, H, reg, min_w=0.15):
    """per z row of the opening: (z, x left edge, sill y left, x right edge, sill y right); rows narrower than min_w
    or not spanning the centre line are left out"""
    out = []
    d = xs[1] - xs[0]
    for j, z in enumerate(zs):
        row = np.nonzero(reg[j])[0]
        if len(row) == 0:
            continue
        il, ir = row.max(), row.min()
        xl, xr = xs[il] + d / 2, xs[ir] - d / 2
        if xl - xr < min_w or xl < 0.03 or xr > -0.03:
            continue
        yl = np.nanmax(H[j, il + 1:il + 5]) if il + 1 < len(xs) else np.nan
        yr = np.nanmax(H[j, max(0, ir - 4):ir]) if ir > 0 else np.nan
        out.append((z, xl, yl, xr, yr))
    return np.array(out)


class Skin:
    """the skin around the opening: height at (x, z) from the map, none over the opening"""

    def __init__(self, xs, zs, H, reg):
        self.xs, self.zs = xs, zs
        self.H = np.where(reg, np.nan, H)
        self.d = xs[1] - xs[0]

    def row(self, z):
        j = int(np.clip(round((z - self.zs[0]) / self.d), 0, len(self.zs) - 1))
        return self.H[j]

    def at(self, x, z):
        """skin height, nan over the opening or off the map"""
        i = int(round((x - self.xs[0]) / self.d)); j = int(round((z - self.zs[0]) / self.d))
        if i < 0 or i >= len(self.xs) or j < 0 or j >= len(self.zs):
            return np.nan
        return self.H[j, i]
