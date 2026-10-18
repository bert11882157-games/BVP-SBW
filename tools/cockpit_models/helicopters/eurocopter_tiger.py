"""Tiger HAP/HAD: the gunner's (rear seat's) Strix sight viewing head on the coaming, its binocular eyepiece facing
him."""
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['seat'] != 0:                      # the rear seat: the gunner's station of the real aircraft
            continue
        s, E = t.s, t.E
        lip = L.Facet(t, 0).at(0, t.top_c)       # top of the panel's centre facet
        # the sight head: a squat box on the coaming just ahead of the panel lip
        hc = np.array([E[0], t.top_c + 0.045 * s, lip[2] + 0.08 * s])
        mesh.box(hc, (X, Y, Z), (0.075 * s, 0.05 * s, 0.09 * s), c['dgrey'], faces={(1, 1): c['black']})
        # the neck and the binocular eyepiece pointing aft at the eye
        mesh.box(hc + [0, 0.02 * s, -0.11 * s], (X, Y, Z), (0.05 * s, 0.03 * s, 0.03 * s), c['black'])
        for sg in (1, -1):
            mesh.box(hc + [sg * 0.032 * s, 0.025 * s, -0.15 * s], (X, Y, Z), (0.018 * s, 0.018 * s, 0.018 * s),
                     c['black'], faces={(2, -1): c['bezel']})
        # rubber brow pad above the eyepieces
        mesh.box(hc + [0, 0.052 * s, -0.15 * s], (X, Y, Z), (0.055 * s, 0.008 * s, 0.02 * s), c['black'])
