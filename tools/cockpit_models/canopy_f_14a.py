#!/usr/bin/env python3
"""F-14A canopy, made by hand: the shape from the model's old glass (glass_sections.py, on the be357476 asset),
cleaned up into smooth sections (metres from the pilot's eye; the RIO's eye is 1.45 m behind it), and the real frame
layout.

The F-14 canopy (side views and photos of the type): a windscreen of a flat armoured centre pane between two curved
side panes, its two posts down to the foot and the arch at the rear; one bubble over both crew, hinged at the back,
frameless but for its edge (the front bow against the windscreen arch, the sill rails, the tail frame on the spine).

Run after the cockpit (f_14a.py), on its output. Usage: python3 tools/cockpit_models/canopy_f_14a.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'f_14a'
SOURCE_BONE = 'wreck_fuselage_0__hull'

# the canopy's sections, metres from the eye: z, then the right half from the sill up over the top
CANOPY = [
    (-2.75, [(0.187, 0.065), (0.173, 0.131), (0.167, 0.187), (0.15, 0.243), (0.106, 0.281), (0.054, 0.303), (0.0, 0.321)]),
    (-2.50, [(0.213, -0.101), (0.241, -0.018), (0.227, 0.071), (0.221, 0.145), (0.215, 0.229), (0.164, 0.3), (0.088, 0.346), (0.0, 0.38)]),
    (-2.25, [(0.252, -0.155), (0.276, -0.052), (0.262, 0.049), (0.253, 0.134), (0.249, 0.233), (0.198, 0.324), (0.109, 0.386), (0.0, 0.427)]),
    (-2.00, [(0.278, -0.168), (0.287, 0.056), (0.262, 0.243), (0.213, 0.353), (0.121, 0.432), (0.0, 0.479)]),
    (-1.75, [(0.295, -0.157), (0.318, -0.03), (0.302, 0.083), (0.29, 0.178), (0.278, 0.288), (0.219, 0.387), (0.128, 0.479), (0.0, 0.537)]),
    (-1.50, [(0.307, -0.138), (0.325, -0.002), (0.31, 0.112), (0.298, 0.211), (0.274, 0.315), (0.223, 0.422), (0.132, 0.522), (0.0, 0.588)]),
    (-1.25, [(0.315, -0.122), (0.333, 0.016), (0.315, 0.134), (0.3, 0.234), (0.275, 0.338), (0.225, 0.447), (0.136, 0.555), (0.0, 0.623)]),
    (-1.00, [(0.317, -0.119), (0.338, 0.02), (0.318, 0.139), (0.302, 0.239), (0.277, 0.345), (0.229, 0.458), (0.138, 0.567), (0.0, 0.63)]),
    (-0.75, [(0.317, -0.134), (0.337, 0.004), (0.318, 0.123), (0.302, 0.224), (0.278, 0.33), (0.229, 0.443), (0.137, 0.549), (0.0, 0.61)]),
    (-0.50, [(0.315, -0.168), (0.334, -0.03), (0.316, 0.088), (0.302, 0.189), (0.275, 0.292), (0.224, 0.401), (0.137, 0.512), (0.0, 0.57)]),
    (-0.25, [(0.314, -0.219), (0.331, -0.08), (0.314, 0.036), (0.303, 0.138), (0.282, 0.245), (0.23, 0.356), (0.138, 0.464), (0.0, 0.518)]),
    (0.00, [(0.31, -0.285), (0.329, -0.15), (0.31, -0.034), (0.303, 0.068), (0.284, 0.176), (0.232, 0.289), (0.137, 0.39), (0.0, 0.44)]),
    (0.25, [(0.305, -0.358), (0.326, -0.228), (0.307, -0.111), (0.298, -0.011), (0.281, 0.097), (0.234, 0.214), (0.136, 0.31), (0.0, 0.355)]),
    (0.50, [(0.296, -0.428), (0.309, -0.25), (0.289, -0.09), (0.274, 0.015), (0.221, 0.121), (0.134, 0.228), (0.0, 0.267)]),
    (0.75, [(0.281, -0.483), (0.299, -0.361), (0.281, -0.255), (0.275, -0.163), (0.258, -0.064), (0.217, 0.045), (0.126, 0.134), (0.0, 0.168)]),
    (1.00, [(0.274, -0.47), (0.27, -0.33), (0.256, -0.224), (0.235, -0.136), (0.188, -0.047), (0.105, 0.017), (0.0, 0.048)]),
    (1.24, [(0.252, -0.44), (0.244, -0.344), (0.225, -0.268), (0.191, -0.2), (0.128, -0.161), (0.066, -0.134), (0.0, -0.114)]),
]
ARCH = 0.58                      # the windscreen's arch, the canopy's front bow against it
POST_X = 0.12                    # the windscreen's posts either side of the flat centre pane, out from the centre line

# an old fitting left floating high inside the canopy over the RIO's panel (eye-relative box)
GONE = [((-0.08, 0.45, -1.20), (0.08, 0.55, -1.00))]


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    can = C.Shape(CANOPY)
    glass = C.glass_triangles(can, E, -2.75, 1.24, nz=44)
    C.bow(m, can, E, -2.73, 0.04, 0.03, out, inner)             # the tail frame on the spine
    C.bow(m, can, E, ARCH, 0.05, 0.03, out, inner)                    # the windscreen arch and the canopy's front bow
    C.bow(m, can, E, 1.225, 0.03, 0.015, out, inner, s0=0.0, s1=1.0)   # the windscreen's foot on the nose
    C.member(m, can, E, 0.0, -2.75, 1.24, 0.05, 0.035, out, inner, steps=44)   # the sill rails
    C.member(m, can, E, lambda z: can.s_at_x(z, POST_X), ARCH + 0.025, 1.22, 0.025, 0.02, out, inner, steps=14)
    return m, glass


def main(argv):
    C.run(VID, SOURCE_BONE, build, argv, colour=(46, 48, 50), boxes=GONE)   # the frames black, as on the type (reference views)


if __name__ == '__main__':
    main(sys.argv[1:])
