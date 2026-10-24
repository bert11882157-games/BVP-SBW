#!/usr/bin/env python3
"""Shared helpers of the Elite Expansion 2 naval builds (dv15_build.py, lcac_build.py).

The pack has no boat of its own (Essex and Kiev are static carriers), so a boat is the SBW speedboat's data contract
(EngineType Ship, Type Boat, the Seats / Weapons / OBB / Turret fields) on the pack's source-fitted geometry:

  * data    = a copy of superbwarfare:speedboat (sbw/src/main/resources/data/superbwarfare/sbw/vehicles/speedboat.json) with
              this vessel's mass, speed, health, seats, boxes, turret frames and weapon;
  * client  = the minimal pack resource (Model + texture; no RunningGear: a boat has none), geometry bones hull, turret, barell;
  * entity  = FittedBoatEntity (Java, UnarmoredMountedWeaponEntity: native hull damage and collision, no tank armour / ground
              mobility systems), registered like the pack's other source-fitted vehicles and drawn by the standard vehicle backend.

The ground helpers (meshes, boxes, textures, registry text edits) are ee2_ground.py's.
"""
import copy
import json
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ee2_ground as E  # noqa: E402
import vehgen  # noqa: E402
import fit_existing  # noqa: E402

REPO = vehgen.REPO
SPEEDBOAT = os.path.join(REPO, 'sbw', 'src', 'main', 'resources', 'data', 'superbwarfare', 'sbw', 'vehicles', 'speedboat.json')
JAVA_ENTITY = os.path.join(vehgen.JAVA, 'entity', 'FittedBoatEntity.java')
NS = vehgen.NS

BOAT_JAVA = '''package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Source-fitted boats and craft (DV15, LCAC): the SBW ship engine and the data-driven seats and weapons, native hull damage
 * and collision (no tank armour resolution, ground mobility limits, track systems or ammo-rack warnings).
 */
public class FittedBoatEntity extends UnarmoredMountedWeaponEntity {
    public FittedBoatEntity(EntityType<?> type, Level level, String profileId) {
        super(type, level, profileId);
    }
}
'''


def _read(path):
    with open(path, encoding='utf-8', newline='') as fh:
        return fh.read()


def _write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as fh:
        fh.write(text)


def slice_boxes(model, ids, x_edges, axis=0):
    """One px box per slice of the elements (by their toolbox centre along `axis`), for a hull that is not box shaped."""
    out = []
    for lo_e, hi_e in zip(x_edges[:-1], x_edges[1:]):
        sel = []
        for i in ids:
            lo, hi = model.tb_box([i])
            c = (lo[axis] + hi[axis]) / 2
            if lo_e <= c < hi_e:
                sel.append(i)
        if sel:
            out.append(model.box(sel))
    return out


def register_boat(vid, hitbox, after_vid, entity='FittedBoatEntity'):
    """FittedBoatEntity (or a subclass: FittedDeckBoatEntity for a craft with a cargo deck) registration and standard-backend
    renderer, each right after the complete statement of `after_vid`; the entity imports go in after the Ags30Entity import.
    The registered type stays EntityType<FittedBoatEntity>; only the constructed class changes."""
    const, aconst = E.java_const(vid), E.java_const(after_vid)
    path = os.path.join(vehgen.JAVA, 'init', 'ModEntities.java')
    src = _read(path)
    nl = E._nl(src)
    for cls in sorted({'FittedBoatEntity', entity}):
        imp = f'import com.yourname.berts_vehicle_pack.entity.{cls};'
        if imp not in src:
            m = re.search(r'import com\.yourname\.berts_vehicle_pack\.entity\.Ags30Entity;(\r?\n)', src)
            assert m, 'Ags30Entity import anchor'
            src = src[:m.end()] + imp + m.group(1) + src[m.end():]
    if f'"{vid}"' not in src:
        m = re.search(rf'( *)public static final RegistryObject<EntityType<\w+>> {aconst} =\r?\n.*?;\r?\n', src, re.S)
        ind = m.group(1)
        line = (f'{ind}public static final RegistryObject<EntityType<FittedBoatEntity>> {const} ={nl}'
                f'{ind}        ENTITIES.register("{vid}", () -> vehicle({nl}'
                f'{ind}                (type, level) -> new {entity}(type, level, "{vid}"),{nl}'
                f'{ind}                {hitbox[0]}f, {hitbox[1]}f, "{vid}"));{nl}')
        src = src[:m.end()] + line + src[m.end():]
    else:                                         # already registered: only the constructed class may change
        src = re.sub(rf'(\(type, level\) -> new )\w+(\(type, level, "{vid}"\))', rf'\g<1>{entity}\g<2>', src)
    _write(path, src)
    path = os.path.join(vehgen.JAVA, 'init', 'ModEntityRenderers.java')
    src = _read(path)
    if f'"{vid}"' not in src:
        nl = E._nl(src)
        m = re.search(rf'( *)registerVehicle\(event, ModEntities\.{aconst}, "{after_vid}", .*?\)\);\r?\n', src, re.S)
        ind = m.group(1)
        name = ''.join(p.capitalize() for p in vid.split('_')) + 'Renderer'
        line = (f'{ind}registerVehicle(event, ModEntities.{const}, "{vid}", standardVehicle({nl}'
                f'{ind}        "custom_geo/{vid}.geo.json", "textures/entity/{vid}.png", "{name}"));{nl}')
        _write(path, src[:m.end()] + line + src[m.end():])
    if not os.path.exists(JAVA_ENTITY):
        _write(JAVA_ENTITY, BOAT_JAVA)


def boat_geo(model, template_geo, bones):
    """geo json: hull, turret, barell. bones: {'hull': ids, 'turret': (ids, pivot_tb) or None, 'barell': (ids, pivot_tb) or None}."""
    g = copy.deepcopy(template_geo)
    geo = g['minecraft:geometry'][0]
    geo['description']['identifier'] = f'geometry.{bones["id"]}'
    geo['description']['texture_width'] = model.tex_w
    geo['description']['texture_height'] = model.tex_h
    geo['bones'] = [{'name': 'hull', 'pivot': [0, 0, 0], 'poly_mesh': model.mesh(bones['hull'])}]
    if bones.get('turret'):
        ids, piv = bones['turret']
        geo['bones'].append({'name': 'turret', 'parent': 'hull', 'pivot': E.r5(model.geo(piv)), 'poly_mesh': model.mesh(ids)})
    if bones.get('barell'):
        ids, piv = bones['barell']
        geo['bones'].append({'name': 'barell', 'parent': 'turret', 'pivot': E.r5(model.geo(piv)), 'poly_mesh': model.mesh(ids)})
    if not bones.get('turret'):                  # no station: the empty turret / barell bones stay at the origin (the pack's rig)
        geo['bones'] += [{'name': 'turret', 'parent': 'hull', 'pivot': [0, 0, 0]},
                         {'name': 'barell', 'parent': 'turret', 'pivot': [0, 0, 0]}]
    E.set_bounds(geo)
    return g


def speedboat_data():
    return E.load(SPEEDBOAT)


def write_vehicle(vid, geo, data, client_hint_vid, wreck_from='m142_himars', armor_from='m142_himars'):
    """Writes geo, wreck, native fallback, client resource, data and armour of the boat; returns the paths."""
    paths = fit_existing.files_of(vid)
    out = vehgen.Output(True)
    out.json(paths['geo'], geo, compact=True)
    src = fit_existing.files_of(wreck_from)
    wreck = fit_existing.build_wreck(geo, E.load(src['wreck'])) if any(
        b['name'] == 'turret' and b.get('poly_mesh') for b in geo['minecraft:geometry'][0]['bones']) else None
    if wreck is not None:
        out.json(paths['wreck'], wreck, compact=True)

    class B:
        pass
    b = B()
    b.id = vid
    out.json(paths['fallback'], vehgen.native_fallback(b, geo), compact=True)
    client = {'ID': f'{NS}:{vid}', 'Model': {'GeometryBackend': f'{NS}:vehicle/{vid}',
                                              'Model': f'{NS}:geo/native_fallback/{vid}.geo.json',
                                              'Texture': f'{NS}:textures/entity/{vid}.png',
                                              'WreckTexture': f'{NS}:textures/entity_dead/{vid}.png'},
              'MouseSpeed': [0.2, 0.2]}
    out.json(paths['client'], client)
    out.json(paths['data'], data)
    armor = E.load(fit_existing.files_of(armor_from)['armor'])
    armor['id'] = vid
    out.json(paths['armor'], armor)
    return paths, out


def import_weapon(vid, wname, src_vid, src_w, data):
    """The weapon of another vehicle (profile ids renamed to this one, profiles copied): data['Weapons'][wname]."""
    spec = {'id': vid, 'template': src_vid, 'weaponFrom': {wname: (src_vid, src_w)}}
    data.setdefault('Weapons', {})
    sources = fit_existing.import_weapons(spec, {'Weapons': data['Weapons']})
    out = vehgen.Output(True)
    fit_existing.copy_profiles(spec, data, out, sources)
    return data['Weapons'][wname]
