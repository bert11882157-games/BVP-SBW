"""Python port of the runtime placement contract (sbw AircraftStoreAttachment / AircraftPylonRacks).

Keep this file and the Kotlin implementation in step: the offline checks and renders use it to show exactly
what the game draws and where it launches from.

Store keys (all optional)
  MountAnchor           [x,y,z]  top anchor, MountAnchor frame (model-file blocks, x negated, before
                                 ModelForward turn and Scale)
  SideMountAnchors      {"Left": [x,y,z], "Right": [x,y,z]}  the store's own port/starboard side (hung nose
                                 forward), same frame
  MountAxis             [x,y,z]  body axis at the attachment station, same frame; the derived launch point
  LaunchOffsetOverride  true     use LaunchOffset (hull blocks from the attachment point) instead of MountAxis
  RackAdapter           {"Model","Texture","MountAnchor":[x,y,z],"Stations":[{"Point","Face"}...]}
                                 a generated ejector rack drawn with the rack store; its top anchor hangs from
                                 the mount's primary bottom station and its stations (hull offsets from that
                                 point, left-wing orientation) carry the copies

Aircraft mount keys (all optional)
  Stations (Singles), LeftStations / RightStations (Pairs): [{"Point":[x,y,z],"Face":"bottom|left|right",
                                 "Copies":[n...], "Stores":[id...], "ExceptStores":[id...]}]  hull-local
                                 points on the structure surface; a station serves every copy count unless
                                 Copies lists the counts it serves, and every store unless Stores lists the
                                 stores it serves; it never serves the stores in ExceptStores.

Copy placement for a rack of n copies at one physical mount position
  1. the stations serving n copies of this store, in order, when there are at least n of them;
  2. else the store's RackAdapter (when it has exactly n stations), hung from the primary station: the first
     unrestricted (no Stores) bottom station serving 1 copy, or the mount position;
  3. else the legacy RackSpacing/RackColumns offsets around the primary station (top anchor).
  A station's face picks the store anchor: bottom -> top anchor, left -> the store's Right side anchor,
  right -> its Left side anchor. The Right position of a Pair mirrors adapter stations (x and faces).
"""
import numpy as np

FACES = ("bottom", "left", "right")


def vec(v, default=None):
    if v is None:
        return default
    return np.array(v, float)


def anchors(store):
    top = vec(store.get("MountAnchor"), np.zeros(3))
    sides = store.get("SideMountAnchors") or {}
    return dict(
        top=top,
        left=vec(sides.get("Left")),
        right=vec(sides.get("Right")),
        axis=vec(store.get("MountAxis")),
        forward=store.get("ModelForward", "-Z"),
        scale=float(store.get("Scale", 1.0)),
        launch_offset=vec(store.get("LaunchOffset"), np.zeros(3)),
        override=bool(store.get("LaunchOffsetOverride", False)),
        model=store.get("Model"),
    )


def anchor_for(a, face):
    if face == "left":
        return a["right"] if a["right"] is not None else a["top"]
    if face == "right":
        return a["left"] if a["left"] is not None else a["top"]
    return a["top"]


def hull_delta(a, model_delta):
    d = np.asarray(model_delta, float) * a["scale"]
    if a["forward"] == "+Z":
        return d
    return d * np.array([-1.0, 1.0, -1.0])


def mount_positions(mount):
    if "Position" in mount:
        return [np.array(mount["Position"], float)]
    return [np.array(mount["Left"], float), np.array(mount["Right"], float)]


def mount_stations(mount):
    """Station lists per physical position (empty lists when the mount has none)."""
    if "Position" in mount:
        keys = ["Stations"]
    else:
        keys = ["LeftStations", "RightStations"]
    out = []
    for k in keys:
        out.append([dict(point=np.array(s["Point"], float), face=s.get("Face", "bottom"),
                         copies=None if "Copies" not in s else list(s["Copies"]),
                         stores=None if "Stores" not in s else list(s["Stores"]),
                         excluded=list(s.get("ExceptStores", []))) for s in mount.get(k, [])])
    return out


def stations_for(stations, copies, store_id=None):
    usable = [s for s in stations if (s["copies"] is None or copies in s["copies"]) and
              (s["stores"] is None or store_id in s["stores"]) and store_id not in s["excluded"]]
    return usable[:copies] if len(usable) >= copies else None


def primary(stations, position):
    """Where an adapter or a legacy rack hangs: the first unrestricted single-store bottom station."""
    for s in stations:
        if (s["copies"] is None or 1 in s["copies"]) and s["stores"] is None and s["face"] == "bottom":
            return s["point"]
    return position


def mirror_face(face):
    return {"left": "right", "right": "left"}.get(face, face)


def legacy_offset(copy, copies, spacing, columns=None):
    if copies == 3 and columns is None:
        return [np.array([-spacing[0] * 0.5, 0, 0]), np.array([spacing[0] * 0.5, 0, 0]),
                np.array([0, -spacing[1], 0])][copy]
    cols = min(copies, columns or 3)
    row = copy // cols
    size = min(cols, copies - row * cols)
    return np.array([(copy % cols - (size - 1) * 0.5) * spacing[0], -row * spacing[1], -row * spacing[2]])


def placement(a, point, face):
    anchor = anchor_for(a, face)
    if a["axis"] is not None and not a["override"]:
        launch = point + hull_delta(a, a["axis"] - anchor)
    else:
        launch = point + a["launch_offset"]
    return dict(point=np.asarray(point, float), face=face, anchor=anchor, launch=launch)


def layout(mount, store, copies, store_id=None):
    """Placements in fired order (copy-major, then position) and adapter placements."""
    a = anchors(store)
    positions = mount_positions(mount)
    if mount.get("Internal"):
        return [dict(point=p, face="bottom", anchor=a["top"], launch=p + a["launch_offset"])
                for _ in range(copies) for p in positions], []
    stations = mount_stations(mount)
    spacing = store.get("RackSpacing", [0.6, 0.4, 0.0])
    adapter = store.get("RackAdapter")
    per_position = []
    adapters = []
    for index, position in enumerate(positions):
        chosen = stations_for(stations[index], copies, store_id)
        if chosen is not None:
            per_position.append([placement(a, s["point"], s["face"]) for s in chosen])
            continue
        if adapter is not None and len(adapter["Stations"]) == copies:
            base = primary(stations[index], position)
            mirror = "Left" in mount and index == 1
            adapters.append(dict(point=base, adapter=adapter, position=index))
            row = []
            for s in adapter["Stations"]:
                off = np.array(s["Point"], float)
                face = s.get("Face", "bottom")
                if mirror:
                    off = off * [-1, 1, 1]
                    face = mirror_face(face)
                row.append(placement(a, base + off, face))
            per_position.append(row)
            continue
        base = primary(stations[index], position)
        per_position.append([placement(a, base + legacy_offset(c, copies, spacing, store.get("RackColumns")), "bottom")
                             for c in range(copies)])
    out = []
    for c in range(copies):
        for index in range(len(positions)):
            out.append(per_position[index][c])
    return out, adapters


def placed_points(a, anchor, point, pts_anchor_frame):
    """Map points given in the MountAnchor frame to hull blocks for a store placed with `anchor` on `point`."""
    return point + np.array([hull_delta(a, p - anchor) for p in pts_anchor_frame])
