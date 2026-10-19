"""Ka-50: the shading hood of the IT-23 Shkval TV screen (the D:STORES item of the station's explicit panel, top centre) and
the K-37-800 ejection seat's yellow-and-black firing handle between the pilot's knees, and a plain bulkhead plate where
the old instrument panel painted on the nose bulkhead (z 4.106, ahead of the coaming, seen through the windscreen)
was removed, and plain blocks for the old side consoles below the new ones (their painted switch rows showed
through the windscreen ahead of the panel wings)."""
import math
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        f = L.Facet(t, 0)
        for kind, a, v, d in [it for it in st.get('panel', {}).get('centre', []) if it[0] == 'D:STORES']:
            # the IT-23 screen's place in the station's explicit panel (real metres, scaled like the driver does)
            a, d = a * t.s, d * t.s
            y = f.top(a) - v * t.s * math.cos(t.lean)
            p = f.at(a, y)
            _, n, u, r = L.frame(p, f.N, f.U)
            hw = d * (0.5 + L.DISPLAY_BEZEL) + 0.006        # just outside the display housing
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
    # the nose bulkhead in place of the old painted panel (the spec's remove box at z 4.09..4.12)
    mesh.box([0, (1.108 + 2.405) / 2, 4.11], (X, Y, Z), (0.72, (2.405 - 1.108) / 2, 0.006), c['coaming'])
    for sg in (1, -1):        # the old consoles' blocks, plain (the spec's remove boxes at x 0.43..0.80)
        mesh.box([sg * 0.612, (1.036 + 1.612) / 2, (2.665 + 4.106) / 2], (X, Y, Z), (0.18, (1.612 - 1.036) / 2,
                 (4.106 - 2.665) / 2), c['coaming'])
