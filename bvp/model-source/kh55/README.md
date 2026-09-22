# KH-55 model source

`kh55.bbmodel.json` is the exact, unmodified `Kh-55.bbmodel` supplied by the repository
maintainer for this integration. Its extra `.json` suffix allows the source inventory to
recognize the authoring file; rename a copy to `.bbmodel` to open it in Blockbench.
The file contains the supplied artwork and embedded texture. No separate artist or license
attribution is present in its metadata; this directory records the supplied provenance
without assigning authorship or changing the repository's existing asset notices.

Source SHA-256: `30d9cc2ddbb4fbdd315a3540c62de5d762288e765bdde00aa67af26bf719b7ac`.

Run from the repository root with Node.js:

```text
node bvp/model-source/kh55/convert.mjs
node bvp/model-source/kh55/convert.mjs --check
node --test bvp/model-source/kh55/convert.test.mjs
```

The converter writes only these maintained resource overlays:

- `bvp/src/main/resources/assets/berts_vehicle_pack/custom_geo/aircraft_stores/kh55.geo.json`
- `bvp/src/main/resources/assets/berts_vehicle_pack/custom_geo/projectiles/kh55.geo.json`
- `bvp/src/main/resources/assets/berts_vehicle_pack/textures/aircraft_stores/kh55.png`

It retains all 12 exported, visible, flat-shaded meshes and their 503 vertices. The supplied
group named `Wings (Hidden?)` is explicitly visible and exported; its name does not hide it.
Its 500 faces become 872 nondegenerate triangles, preserving the saved quad diagonal and
per-corner texture coordinates. Sixteen zero-area triangles at repeated nose vertices have
no visible surface and are omitted so the renderer always has a finite face normal.
The 112-by-112 PNG is extracted directly from the embedded base64 bytes, without re-encoding.

Blockbench's free format uses local mesh vertices, element origins and ZYX Euler rotations.
The converter follows the upstream [node transform contract](https://github.com/JannisX11/blockbench/blob/v5.0.0/js/outliner/outliner.js)
and [format rotation default](https://github.com/JannisX11/blockbench/blob/v5.0.0/js/io/format.ts).
It bakes the static transforms. The supplied group has zero rotation; a future nonzero group
rotation is rejected for explicit review instead of being silently discarded.

Coordinates remain at the authored scale: 16 model units per block. The source nose points
along +Y, from nozzle Y=0.4 to nose Y=95.6. Both runtime variants preserve the 5.95-block
length and 4.1225-block deployed wing span. Meshloader reflects raw X and inverts UV V; the
export compensates for those operations and preserves face winding in the rendered frame.

| Variant | Rendered frame after meshloader | Bounds in blocks |
| --- | --- | --- |
| Flight | +Y nose, nozzle at origin, wings span X | X ±2.06125; Y 0..5.95; Z approximately ±0.501622 |
| Store | −Z nose, origin at axial centre | X ±2.06125; Y approximately ±0.501622; Z ±2.975 |

The store's nozzle is 2.975 blocks aft of its centre. BVP rotates its model frame to native
hull coordinates, so the native nozzle offset is `[0, 0, -2.975]`. The flight renderer uses
the supplied geometry directly in FFA's nozzle-origin frame; it needs no KH-29 offset or
additional scale. TU-95 definitions conceal internally loaded stores using their existing
empty store-group mapping and position launch origins separately. No wing or bay-door
animation is introduced by this conversion.

The checks verify source identity, deterministic output, finite mesh indices/normals, UV
bounds and orientation, source landmarks, runtime extents, and exact embedded texture bytes.
These checks and a software geometry preview are not in-game rendering acceptance.
