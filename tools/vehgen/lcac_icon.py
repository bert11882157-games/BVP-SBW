#!/usr/bin/env python3
"""The icons of the LCAC (lcac): tusk_icon.py's renderer on the LCAC's geometry and texture.

    python3 tools/vehgen/lcac_icon.py        (delete tools/icon_flags/originals/.../lcac.png first when re-running,
                                                then tools/icon_flags/flags.py --write lcac)
"""
import os

import tusk_icon

VID = 'lcac'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
