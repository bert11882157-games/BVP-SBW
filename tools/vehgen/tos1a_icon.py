#!/usr/bin/env python3
"""The icons of the TOS-1A Solntsepyok (tos_1a): tusk_icon.py's renderer on the TOS-1A's geometry and texture.

    python3 tools/vehgen/tos1a_icon.py        (delete tools/icon_flags/originals/.../tos_1a.png first when re-running,
                                                then tools/icon_flags/flags.py --write tos_1a)
"""
import os

import tusk_icon

VID = 'tos_1a'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
