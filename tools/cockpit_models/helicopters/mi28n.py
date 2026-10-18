"""Mi-28N: the navigator-operator's (front seat's) sighting station: the optical sight's viewing head with its
rubber eyepiece hood on the coaming in front of him, over the centre display, with its two hand-controller grips
on the panel's lower edge either side of the cyclic."""
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['seat'] != 1:                      # the front seat
            continue
        s, E = t.s, t.E
        cen = L.Facet(t, 0)
        lip = cen.at(0, t.top_c)
        # the viewing head: a block on the coaming, the eyepiece tube coming aft at the operator's eye line
        hc = np.array([E[0], t.top_c + 0.05 * s, lip[2] + 0.07 * s])
        mesh.box(hc, (X, Y, Z), (0.09 * s, 0.06 * s, 0.10 * s), c['dgrey'], faces={(1, 1): c['black']})
        ep = np.array([E[0], t.top_c + 0.07 * s, lip[2] - 0.08 * s])
        mesh.box((hc + ep) / 2 + [0, 0.01 * s, 0], (X, Y, Z), (0.05 * s, 0.035 * s, abs(hc[2] - ep[2]) / 2 + 0.01),
                 c['black'])
        mesh.box(ep, (X, Y, Z), (0.06 * s, 0.04 * s, 0.025 * s), c['black'], faces={(2, -1): c['bezel']})
        # hand-controller grips at the panel's foot, left and right of the cyclic
        foot = cen.at(0, t.bot_c)
        for sg in (1, -1):
            g = np.array([E[0] + sg * 0.17 * s, t.bot_c + 0.05 * s, foot[2] - 0.08 * s])
            mesh.box(g - [0, 0.04 * s, 0], (X, Y, Z), (0.03 * s, 0.02 * s, 0.04 * s), c['black'])
            ax = (X, L.unit([0, 1, 0.25]), L.unit([0, -0.25, 1]))
            mesh.box(g + [0, 0.01 * s, 0], ax, (0.016 * s, 0.045 * s, 0.02 * s), c['dgrey'])
