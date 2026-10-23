#!/usr/bin/env python3
"""Ho 229 canopy, made by hand: the shape from the model's old glass (glass_sections.py), cleaned up into smooth
sections, and the real frame layout.

The Ho 229 canopy (the V3 airframe and drawings of the type): one blown bubble from the nose back over the pilot,
with no windscreen arch or bow across it, held only by the frame round its edge (the sill rails along both sides, the
tail frame where it meets the centre-section fairing, the slim edge at the nose).

Run after the cockpit (ho_229.py), on its output. Usage: python3 tools/cockpit_models/canopy_ho_229.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'ho_229'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the bubble's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.50, [(0.25, -0.42), (0.24, -0.33), (0.215, -0.24), (0.18, -0.16), (0.13, -0.09), (0.07, -0.04), (0.0, -0.02)]),
    (-0.40, [(0.297, -0.42), (0.263, -0.298), (0.246, -0.215), (0.211, -0.14), (0.156, -0.078), (0.091, -0.014), (0.0, 0.033)]),
    (-0.20, [(0.339, -0.40), (0.32, -0.244), (0.302, -0.141), (0.268, -0.043), (0.207, 0.047), (0.12, 0.131), (0.0, 0.2)]),
    (0.00, [(0.353, -0.40), (0.335, -0.244), (0.316, -0.138), (0.286, -0.031), (0.226, 0.072), (0.129, 0.158), (0.0, 0.2)]),
    (0.20, [(0.36, -0.40), (0.334, -0.25), (0.314, -0.138), (0.276, -0.038), (0.223, 0.068), (0.127, 0.153), (0.0, 0.2)]),
    (0.40, [(0.36, -0.42), (0.326, -0.268), (0.307, -0.164), (0.267, -0.068), (0.202, 0.016), (0.115, 0.092), (0.0, 0.132)]),
    (0.60, [(0.35, -0.44), (0.327, -0.305), (0.297, -0.204), (0.247, -0.119), (0.176, -0.054), (0.096, 0.0), (0.0, 0.026)]),
    (0.80, [(0.326, -0.45), (0.313, -0.337), (0.277, -0.243), (0.216, -0.172), (0.142, -0.131), (0.076, -0.093), (0.0, -0.069)]),
    (1.00, [(0.285, -0.45), (0.285, -0.376), (0.235, -0.294), (0.162, -0.249), (0.102, -0.224), (0.052, -0.205), (0.0, -0.184)]),
    (1.20, [(0.224, -0.42), (0.19, -0.39), (0.151, -0.368), (0.087, -0.35), (0.0, -0.331)]),
]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.50, 1.20, nz=24)
    C.bow(m, sh, E, -0.48, 0.035, 0.025, out, inner)                       # the tail frame
    C.bow(m, sh, E, 1.185, 0.03, 0.015, out, inner)                        # the slim edge at the nose
    C.member(m, sh, E, 0.0, -0.50, 1.20, 0.045, 0.03, out, inner, steps=24)  # the sill rails
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:])
