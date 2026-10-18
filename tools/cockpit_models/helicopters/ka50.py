"""Ka-50: the shading hood of the IT-23 Shkval TV screen (the STORES display at the top of the centre panel) and
the K-37-800 ejection seat's yellow-and-black firing handle between the pilot's knees."""
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        facets = [L.Facet(t, 0), L.Facet(t, 1), L.Facet(t, -1)]
        for f, a, y, kind, d in L.layout(t, L.ERAS[st['era']], facets):
            if kind != 'D:STORES':
                continue
            p = f.at(a, y)
            _, n, u, r = L.frame(p, f.N, f.U)
            hw = d / 2 + 0.012
            # side cheeks, deep at the top and shallow at the foot, and a top plate
            for sg in (-1, 1):
                p0, p1 = p + r * sg * hw - u * hw, p + r * sg * hw + u * hw
                q1, q0 = p1 + n * 0.06, p0 + n * 0.012
                mesh.flat([p0, p1, q1, q0], c['black'], normal=r * sg)
                mesh.flat([p0, p1, q1, q0], c['black'], normal=-r * sg)
            mesh.box(p + u * (hw + 0.003) + n * 0.03, (r, u, n), (hw + 0.006, 0.003, 0.03), c['black'])
        # seat firing handle (the K-37 is pulled from the handles at the front of the seat pan)
        s, E = t.s, t.E
        hy = E[1] - 0.76 * s
        mesh.box([E[0], hy, E[2] + 0.20 * s], (X, Y, Z), (0.06 * s, 0.008 * s, 0.008 * s), c['yellow'])
        for sg in (1, -1):
            mesh.box([E[0] + sg * 0.06 * s, hy - 0.02 * s, E[2] + 0.19 * s], (X, Y, Z),
                     (0.006 * s, 0.022 * s, 0.006 * s), c['black'])
