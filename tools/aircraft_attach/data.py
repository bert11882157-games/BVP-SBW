"""Loading and surgical writing of the BVP aircraft store / armament JSON (formatting preserved)."""
import glob
import json
import os
from collections import OrderedDict

import geo

NS = "berts_vehicle_pack"


def store_path(store_id):
    ns, path = store_id.split(":", 1)
    assert ns == NS, store_id
    return os.path.join(geo.DATA, "aircraft_stores", path + ".json")


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f, object_pairs_hook=OrderedDict)


def dump_json(data):
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def write_json(path, data):
    text = dump_json(data)
    with open(path, encoding="utf-8") as f:
        old = f.read()
    if old == text:
        return False
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return True


def all_stores():
    out = OrderedDict()
    root = os.path.join(geo.DATA, "aircraft_stores")
    for path in sorted(glob.glob(os.path.join(root, "**", "*.json"), recursive=True)):
        rel = os.path.relpath(path, root)[:-5].replace(os.sep, "/")
        out[NS + ":" + rel] = load_json(path)
    return out


def all_aircraft():
    out = OrderedDict()
    for path in sorted(glob.glob(os.path.join(geo.DATA, "aircraft_armaments", "*.json"))):
        out[os.path.basename(path)[:-5]] = load_json(path)
    return out


def aircraft_path(name):
    return os.path.join(geo.DATA, "aircraft_armaments", name + ".json")


def mounts(definition):
    for key in ("Pairs", "Singles"):
        for m in definition.get(key, []):
            yield key, m


def model_name(store):
    model = store.get("Model")
    if not model:
        return None
    base = model.split("/")[-1]
    return base[:-len(".geo.json")] if base.endswith(".geo.json") else None


def set_after(obj, key, value, after):
    """Insert or replace key; a new key goes right after `after` (or at the end) to keep diffs local."""
    if key in obj:
        obj[key] = value
        return obj
    out = OrderedDict()
    placed = False
    for k, v in obj.items():
        out[k] = v
        if k == after and not placed:
            out[key] = value
            placed = True
    if not placed:
        out[key] = value
    obj.clear()
    obj.update(out)
    return obj


def r4(v):
    """Round to 4 decimals and drop negative zero."""
    x = round(float(v), 4)
    return 0.0 if x == 0 else x


def vec4(v):
    return [r4(x) for x in v]
