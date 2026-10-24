#!/usr/bin/env python3
"""The icons of the M142 HIMARS (m142_himars): tusk_icon.py's renderer on the M142's geometry and texture.

    python3 tools/vehgen/himars_icon.py        (delete tools/icon_flags/originals/.../m142_himars.png first when re-running,
                                                then tools/icon_flags/flags.py --write m142_himars)
"""
import os

import tusk_icon

VID = 'm142_himars'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
