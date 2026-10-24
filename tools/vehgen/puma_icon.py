#!/usr/bin/env python3
"""The icons of the SPz Puma (puma_ifv): tusk_icon.py's renderer on the Puma's geometry and texture.

    python3 tools/vehgen/puma_icon.py        (delete tools/icon_flags/originals/.../puma_ifv.png first when re-running,
                                              then tools/icon_flags/flags.py --write puma_ifv)
"""
import os

import tusk_icon

VID = 'puma_ifv'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
