"""One module per helicopter: SPEC = {wt, payload, weapon_seat, retire, slots: [{id, name, pos, stores}]}.
Slot ids follow War Thunder's WeaponSlot index (1 = the pilot's far left, +x); pos is the pylon point the store's
lugs touch (vehicle-local blocks: x left, y up, z forward), measured on the airframe model."""
import importlib, pkgutil

HELIS = {m.name: importlib.import_module(f'{__name__}.{m.name}').SPEC for m in pkgutil.iter_modules(__path__)}
