#!/usr/bin/env python3
"""Windscreen with a truly flat centre pane (owner rule: a pane that is flat on the real aircraft is flat in the model; the
frame is built around it, the glass is never bent to fit the frame). Shared by canopy_fa_18e.py and canopy_fa_18c.py.

Hornet / Super Hornet: the front windscreen is a flat centre panel (the plate behind the HUD combiner) with a slim post on
each side, and a small curved quarter pane beyond each post that falls away to the nose deck. Here, in vehicle-local blocks
(x sideways, y up, z forward):

  * centre pane: every vertex on ONE plane y = YT(z) (a line in z, no dependence on x), from the arch's front face to the
    front cap, between the two posts' inner edges;
  * posts: a slim bar each side, half width HW, centred at x = XP(z) = XP_FRAC * XB(z); the glass on both sides stops at
    the post's edges (bars lie on the surface, the flat side on the plane, the other on the quarter pane);
  * quarter pane: from the post's outer edge, a quarter ellipse (tangent to the plane at the post, vertical at the foot)
    down to the foot XB(z) on the nose deck; a foot strip (opaque sill frame) covers the last stretch to the deck;
  * front cap: an opaque strip across the whole windscreen at its front edge (the base frame) with a wall below it down to
    the nose deck, so nothing is open under the glass's front edge.

Every glass edge stops where a frame member begins: at the arch's front face zr(y) (found at the row's own height), at the
posts, at the foot strip, at the cap.
"""
import numpy as np


def _fix(f, z, n=8):
    for _ in range(n):
        z = f(z)
    return z


class FlatWS:
    def __init__(self, za, zb, plane, xb, skin_y, zr, xp_frac=0.60, cap=0.03, hw=0.022, th_foot=0.22):
        """za, zb: where the foot half width xb = (at za, at zb) is given (held outside); plane = ((z0, y0), (z1, y1)): two
        points of the centre pane's plane; skin_y(x, z): the nose deck's height; zr(y): the arch's front face at height y"""
        self.za, self.zb = za, zb
        (self.z0, self.y0), (self.z1, self.y1) = plane
        self.xb0, self.xb1 = xb
        self.skin_y, self.zr = skin_y, zr
        self.xp_frac, self.cap, self.hw, self.th_foot = xp_frac, cap, hw, th_foot
        self.zf = zb - cap                      # the glass ends here, the cap takes over

    # ---- the surface
    def XB(self, z):
        t = min(1.0, max(0.0, (z - self.za) / (self.zb - self.za)))
        return self.xb0 + (self.xb1 - self.xb0) * t

    def XP(self, z):
        return self.xp_frac * self.XB(z)

    def YT(self, z):
        return self.y0 + (self.y1 - self.y0) * (z - self.z0) / (self.z1 - self.z0)

    def YB(self, z):
        return self.skin_y(self.XB(z), z)

    def th0(self, z):
        return float(np.arcsin(min(1.0, self.hw / (self.XB(z) - self.XP(z)))))

    def arc(self, th, z):
        xb, xp, yt, yb = self.XB(z), self.XP(z), self.YT(z), self.YB(z)
        return xp + (xb - xp) * np.sin(th), yt - (yt - yb) * (1 - np.cos(th))

    def section(self, z, n_arc=14):
        """right half-section, foot up to the crown (x = 0): the quarter ellipse, then the flat"""
        pts = [self.arc(th, z) for th in np.linspace(np.pi / 2, 0, n_arc + 1)]
        pts = [(float(x), float(y)) for x, y in pts] + [(0.0, float(self.YT(z)))]
        return pts

    # ---- the glass
    def glass(self, nz=14, nx=10, nj=6):
        tris = []
        zf = self.zf
        # centre pane: one plane
        zc = _fix(lambda z: self.zr(self.YT(z)), self.z0)
        grid = []
        for i in range(nz + 1):
            z = zc + (zf - zc) * i / nz
            xc = self.XP(z) - self.hw
            grid.append([np.array([xc * (2.0 * k / nx - 1.0), self.YT(z), z]) for k in range(nx + 1)])
        tris += self._quads(grid)
        # quarter panes
        ths = lambda z, f: self.th0(z) + (self.th_foot_angle() - self.th0(z)) * f
        for side in (1, -1):
            g = []
            for i in range(nz + 1):
                g.append([])
            for j in range(nj + 1):
                f = j / nj

                def rear(z, f=f):
                    x, y = self.arc(ths(z, f), z)
                    return self.zr(y)
                zj = _fix(rear, self.za)
                for i in range(nz + 1):
                    z = zj + (zf - zj) * i / nz
                    x, y = self.arc(ths(z, f), z)
                    g[i].append(np.array([side * x, y, z]))
            tris += self._quads(g)
        return tris

    def th_foot_angle(self):
        return np.pi / 2 - self.th_foot

    @staticmethod
    def _quads(grid):
        out = []
        for i in range(len(grid) - 1):
            for j in range(len(grid[i]) - 1):
                a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
                for tri in ((a, b, c), (a, c, d)):
                    if np.linalg.norm(np.cross(tri[1] - tri[0], tri[2] - tri[0])) > 1e-10:
                        out.append([np.array(p) for p in tri])
        return out

    # ---- the frame members, written into a Bars (canopy script's class) b
    def add_bars(self, b, post=(0.008, 0.018), n=14):
        t_out, t_in = post
        zf = self.zf
        for side in (1, -1):
            # post: flat side on the plane, the other on the quarter pane
            za = _fix(lambda z: self.zr(self.YT(z)), self.z0)
            zb_ = _fix(lambda z: self.zr(self.arc(self.th0(z), z)[1]), self.za)
            A = [np.array([side * (self.XP(z) - self.hw), self.YT(z), z]) for z in (za + (zf - za) * i / n for i in range(n + 1))]
            B = []
            for i in range(n + 1):
                z = zb_ + (zf - zb_) * i / n
                x, y = self.arc(self.th0(z), z)
                B.append(np.array([side * x, y, z]))
            b.bar(A, B, t_out, t_in)
            # foot strip: from the quarter pane's lower edge to the deck
            thf = self.th_foot_angle()
            zs0 = _fix(lambda z: self.zr(self.arc(thf, z)[1]), self.za)
            zs1 = _fix(lambda z: self.zr(self.arc(np.pi / 2, z)[1]), self.za)
            A, B = [], []
            for i in range(n + 1):
                z = zs0 + (zf - zs0) * i / n
                x, y = self.arc(thf, z)
                A.append(np.array([side * x, y, z]))
                z = zs1 + (zf - zs1) * i / n
                x, y = self.arc(np.pi / 2, z)
                B.append(np.array([side * x, y, z]))
            b.bar(A, B, t_out, t_in)
        # front cap across the whole width, and the wall under it
        def full(z):
            R = self.section(z, 10)
            return [np.array([-x, y, z]) for x, y in R[:-1]] + [np.array([x, y, z]) for x, y in R[::-1]]
        A, B = full(zf), full(self.zb)
        b.bar(A, B, t_out, t_in)
        for i in range(len(B) - 1):
            top = [B[i], B[i + 1]]
            bot = [np.array([p[0], min(self.skin_y(abs(p[0]), self.zb) - 0.02, p[1] - 0.01), p[2]]) for p in top]
            b.face([top[0], bot[0], bot[1], top[1]], np.array([0.0, 0.0, 1.0]))
