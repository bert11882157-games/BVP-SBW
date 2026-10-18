"""Mi-26: the centre panel between the two pilots' panels, where the old textured shared panel was removed, with its
glareshield and the pedestal under it, so nothing opens between the stations."""
import math
import numpy as np

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def extra(ac, tubs, stations, mesh, c):
    t = tubs[0]
    hw = min(abs(u.E[0]) - u.W for u in tubs) + 0.03       # reaches just under both inner wing tips
    z = t.Z + t.wing_len * math.sin(t.wing) - 0.01          # level with the inner wings' outer edges
    y0, y1 = t.bot_w, t.top_w
    yc = (y0 + y1) / 2
    mesh.box([0, yc, z + 0.015], (X, Y, Z), (hw, (y1 - y0) / 2, 0.015), c['panel'], faces={(2, -1): c['panel']})
    mesh.box([0, y1 + 0.01, z + 0.02], (X, Y, Z), (hw + 0.01, 0.012, 0.06), c['coaming'])
    # the pedestal from the panel foot down to the floor and back between the seats
    mesh.box([0, (t.floor_y + y0) / 2, z - 0.10], (X, Y, Z), (hw * 0.8, (y0 - t.floor_y) / 2, 0.11), c['console'])
    # engine and transmission dials (static), two columns
    for k in range(3):
        y = y1 - 0.06 - k * (y1 - y0 - 0.10) / 2
        for xx in (-hw * 0.45, hw * 0.45):
            p = np.array([xx, y, z - 0.001])
            mesh.ring(p, [0, 0, -1], [0, 1, 0], 0.030, 0.037, 0.006, c['mgrey'])
            mesh.disc(p + [0, 0, -0.002], [0, 0, -1], [0, 1, 0], 0.030, c['black'])
            mesh.box(p + [0, 0.010, -0.004], (X, Y, Z), (0.003, 0.013, 0.001), c['white'])
