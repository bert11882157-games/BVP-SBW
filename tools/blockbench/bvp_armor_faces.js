/*
 * BVP Armor Faces: a Blockbench plugin for per-face armor thickness in BVP armor meshes.
 *
 * Select faces (mesh faces in Face mode, or cube faces in the UV panel / viewport face selection) and give them a
 * thickness in mm. A face without a thickness keeps its volume's thickness from the bone name
 * (plate__80mm__ufp -> 80 mm). The values are saved in the .bbmodel and exported into the Bedrock .geo.json as
 * "bvp_face_mm", which the BVP armor loader reads (ArmorMeshLoader, docs/ARMOR_MESH.md):
 *
 *   bone:  "bvp_face_mm": [mm | null, ...]         one entry per poly_mesh polygon, in Meshy's export order
 *   cube:  "bvp_face_mm": {"north": mm, ...}        Blockbench face names; missing = volume thickness
 *
 * Needs the Meshy plugin for Mesh elements in Bedrock models (cube faces work without it).
 * Install: File > Plugins > Load Plugin from File > this file.
 */
(function () {
    'use strict';

    const KEY = 'bvp_face_mm';
    const CUBE_FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
    const VOLUME_NAME = /^(plate|armor)__(\d+(?:[.p]\d+)?)(?:mm)?__/i;
    const OTHER_VOLUME = /^(era|engine|ammo|module|track|internal)__/i;

    // ------------------------------------------------------------------ data (no Blockbench globals; unit-tested)

    /** A thickness entry: a non-negative number (rounded to 0.01 mm), else null (= the volume's thickness). */
    function cleanMm(value) {
        if (value === null || value === undefined || value === '') return null;
        const mm = Number(value);
        return Number.isFinite(mm) && mm >= 0 ? Math.round(mm * 100) / 100 : null;
    }

    function isMesh(element) {
        return !!element && element.type === 'mesh';
    }

    function isCube(element) {
        return !!element && element.type === 'cube';
    }

    /** Face keys of a mesh in Meshy's export order (Object.values(mesh.faces)). */
    function meshFaceKeys(mesh) {
        return Object.keys(mesh.faces || {});
    }

    /** The bone array for the meshes of one group (in child order), or null when no face has a thickness. */
    function meshFaceArray(meshes) {
        const out = [];
        let any = false;
        for (const mesh of meshes) {
            const map = mesh[KEY] || {};
            for (const key of meshFaceKeys(mesh)) {
                const mm = cleanMm(map[key]);
                if (mm !== null) any = true;
                out.push(mm);
            }
        }
        return any ? out : null;
    }

    /** The cube object ({face name: mm}) of one cube, or null when no face has a thickness. */
    function cubeFaceObject(cube) {
        const map = cube[KEY] || {};
        const out = {};
        let any = false;
        for (const face of CUBE_FACES) {
            const mm = cleanMm(map[face]);
            if (mm !== null) {
                out[face] = mm;
                any = true;
            }
        }
        return any ? out : null;
    }

    function exportedCubes(group) {
        return (group.children || []).filter((c) => isCube(c) && c.export !== false);
    }

    function groupMeshes(group) {
        // Meshy exports every Mesh child of the group, exported or not
        return (group.children || []).filter(isMesh);
    }

    /**
     * Writes bvp_face_mm into the compiled bones (codec 'compile' event). Independent of Meshy's own compile
     * handler order: the array follows the same traversal Meshy uses (Mesh children in order, faces in order).
     */
    function compileInto(bones, groups, warn) {
        for (const group of groups) {
            if (!group || group.type !== 'group' || group.export === false) continue;
            const bone = bones.find((b) => b.name === group.name);
            if (!bone) continue;
            const faces = meshFaceArray(groupMeshes(group));
            if (faces) {
                const polys = bone.poly_mesh && Array.isArray(bone.poly_mesh.polys) ? bone.poly_mesh.polys.length : null;
                if (polys !== null && polys !== faces.length) {
                    warn(`Bone "${group.name}": ${faces.length} mesh faces but ${polys} exported polygons; `
                        + 'per-face armor not exported for this bone.');
                } else {
                    bone[KEY] = faces;
                }
            }
            const cubes = exportedCubes(group);
            const withArmor = cubes.map(cubeFaceObject);
            if (!withArmor.some((o) => o)) continue;
            if (!Array.isArray(bone.cubes) || bone.cubes.length !== cubes.length) {
                warn(`Bone "${group.name}": exported cubes do not line up; per-face cube armor not exported.`);
                continue;
            }
            withArmor.forEach((o, i) => {
                if (o) bone.cubes[i][KEY] = o;
            });
        }
    }

    /** Reads bvp_face_mm from parsed bones back onto the elements (after Meshy has built the meshes). */
    function applyParsed(bones, findGroup, warn) {
        let applied = 0;
        for (const bone of bones || []) {
            const group = findGroup(bone.name);
            if (!group) continue;
            if (Array.isArray(bone[KEY])) {
                const meshes = groupMeshes(group);
                const total = meshes.reduce((n, m) => n + meshFaceKeys(m).length, 0);
                if (total !== bone[KEY].length) {
                    warn(`Bone "${bone.name}": ${bone[KEY].length} face thicknesses for ${total} mesh faces `
                        + '(is Meshy installed?); skipped.');
                } else {
                    let i = 0;
                    for (const mesh of meshes) {
                        const map = {};
                        for (const key of meshFaceKeys(mesh)) {
                            const mm = cleanMm(bone[KEY][i++]);
                            if (mm !== null) map[key] = mm;
                        }
                        mesh[KEY] = map;
                        applied++;
                    }
                }
            }
            if (Array.isArray(bone.cubes) && bone.cubes.some((c) => c && c[KEY])) {
                const cubes = group.children.filter(isCube);
                if (cubes.length !== bone.cubes.length) {
                    warn(`Bone "${bone.name}": cubes do not line up; per-face cube armor skipped.`);
                    continue;
                }
                bone.cubes.forEach((c, i) => {
                    const o = c && c[KEY] ? cubeFaceObject({[KEY]: c[KEY]}) : null;
                    cubes[i][KEY] = o || {};
                    if (o) applied++;
                });
            }
        }
        return applied;
    }

    /** Thickness a face gets in game: its own, else its volume's (from the nearest plate/armor bone name). */
    function volumeInfo(group) {
        for (let g = group; g && g.type === 'group'; g = g.parent) {
            const m = VOLUME_NAME.exec(g.name || '');
            if (m) return {bone: g.name, mm: Number(m[2].replace(/p/i, '.')), plate: true};
            if (OTHER_VOLUME.test(g.name || '')) return {bone: g.name, mm: null, plate: false};
        }
        return null;
    }

    /** Colour for a thickness: blue (thin) to red (>= 250 mm). */
    function colourFor(mm) {
        const t = Math.max(0, Math.min(1, mm / 250));
        const hue = 240 - 240 * t;
        return `hsl(${Math.round(hue)}, 90%, 50%)`;
    }

    const api = {KEY, CUBE_FACES, cleanMm, meshFaceArray, cubeFaceObject, compileInto, applyParsed, volumeInfo,
        colourFor};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (typeof BBPlugin === 'undefined') return;

    // ------------------------------------------------------------------ Blockbench side

    const disposers = [];
    let overlayOn = false;

    function warn(message) {
        console.warn('[BVP Armor Faces] ' + message);
        Blockbench.showQuickMessage(message, 4000);
    }

    function selectedCubeFaces(cube) {
        try {
            if (typeof UVEditor !== 'undefined') {
                if (typeof UVEditor.getSelectedFaces === 'function') {
                    const faces = UVEditor.getSelectedFaces(cube);
                    if (Array.isArray(faces) && faces.length) return faces.filter((f) => CUBE_FACES.includes(f));
                }
                const vueFaces = UVEditor.vue && UVEditor.vue.selected_faces;
                if (Array.isArray(vueFaces) && vueFaces.length) return vueFaces.filter((f) => CUBE_FACES.includes(f));
            }
        } catch (e) {
            console.warn(e);
        }
        return [];
    }

    /** [{element, faces: [key...], all: bool}] for the current selection; all = no face selection, whole element. */
    function selectionTargets() {
        const targets = [];
        for (const mesh of (Mesh.selected || [])) {
            const picked = typeof mesh.getSelectedFaces === 'function' ? mesh.getSelectedFaces() : [];
            const all = !picked || picked.length === 0;
            targets.push({element: mesh, faces: all ? meshFaceKeys(mesh) : picked.slice(), all});
        }
        for (const cube of (Cube.selected || [])) {
            const picked = selectedCubeFaces(cube);
            const all = picked.length === 0;
            targets.push({element: cube, faces: all ? CUBE_FACES.slice() : picked, all});
        }
        return targets.filter((t) => t.faces.length);
    }

    function currentValues(targets) {
        const counts = new Map();
        for (const t of targets) {
            const map = t.element[KEY] || {};
            for (const f of t.faces) {
                const mm = cleanMm(map[f]);
                const label = mm === null ? 'volume default' : mm + ' mm';
                counts.set(label, (counts.get(label) || 0) + 1);
            }
        }
        return counts;
    }

    function describeTargets(targets) {
        const faces = targets.reduce((n, t) => n + t.faces.length, 0);
        const whole = targets.filter((t) => t.all).length;
        const counts = [...currentValues(targets)].map(([k, v]) => `${k}: ${v}`).join(', ');
        const volumes = new Set();
        let nonPlate = false;
        for (const t of targets) {
            const info = volumeInfo(t.element.parent);
            if (!info) volumes.add('no armor volume bone');
            else if (!info.plate) nonPlate = true;
            else volumes.add(`${info.bone} (${info.mm} mm)`);
        }
        let text = `${faces} face(s) on ${targets.length} element(s)`;
        if (whole) text += ` (${whole} element(s) with no face selection: all their faces)`;
        text += `.\n\nNow: ${counts || '-'}\n\nVolume: ${[...volumes].join(', ') || '-'}`;
        if (nonPlate) text += '\n\nNote: per-face thickness only applies to plate__/armor__ volumes.';
        return text;
    }

    function applyToTargets(targets, mm, message) {
        const elements = [...new Set(targets.map((t) => t.element))];
        Undo.initEdit({elements});
        for (const t of targets) {
            const map = Object.assign({}, t.element[KEY] || {});
            for (const f of t.faces) {
                if (mm === null) delete map[f];
                else map[f] = mm;
            }
            t.element[KEY] = map;
        }
        Undo.finishEdit(message);
        refreshOverlay();
    }

    function setThickness() {
        const targets = selectionTargets();
        if (!targets.length) {
            Blockbench.showQuickMessage('Select mesh faces or cubes first', 2500);
            return;
        }
        const values = [...currentValues(targets).keys()];
        const uniform = values.length === 1 && values[0] !== 'volume default' ? parseFloat(values[0]) : undefined;
        const info = volumeInfo(targets[0].element.parent);
        new Dialog({
            id: 'bvp_armor_set_face_mm',
            title: 'Set Face Armor Thickness',
            width: 460,
            form: {
                info: {type: 'info', text: describeTargets(targets)},
                mm: {label: 'Thickness (mm)', type: 'number', value: uniform ?? (info && info.mm) ?? 50, min: 0,
                    step: 1},
                reset: {label: 'Use the volume thickness instead', type: 'checkbox', value: false},
            },
            onConfirm(result) {
                const mm = result.reset ? null : cleanMm(result.mm);
                if (!result.reset && mm === null) {
                    Blockbench.showQuickMessage('Enter a thickness of 0 mm or more', 2500);
                    return false;
                }
                applyToTargets(targets, mm, mm === null ? 'Clear face armor thickness' : `Set face armor ${mm} mm`);
            },
        }).show();
    }

    function clearThickness() {
        const targets = selectionTargets();
        if (!targets.length) {
            Blockbench.showQuickMessage('Select mesh faces or cubes first', 2500);
            return;
        }
        applyToTargets(targets, null, 'Clear face armor thickness');
        Blockbench.showQuickMessage('Face armor cleared: faces use their volume thickness', 2000);
    }

    function editCubeFaces() {
        const cubes = Cube.selected || [];
        if (!cubes.length) {
            Blockbench.showQuickMessage('Select one or more cubes', 2500);
            return;
        }
        const first = cubes[0][KEY] || {};
        const form = {
            info: {type: 'info', text: `${cubes.length} cube(s). Leave a field empty for the volume thickness`
                    + (volumeInfo(cubes[0].parent) && volumeInfo(cubes[0].parent).mm !== null
                        ? ` (${volumeInfo(cubes[0].parent).mm} mm).` : '.')},
        };
        for (const face of CUBE_FACES) {
            form[face] = {label: face.charAt(0).toUpperCase() + face.slice(1) + ' (mm)', type: 'text',
                value: cleanMm(first[face]) === null ? '' : String(cleanMm(first[face]))};
        }
        new Dialog({
            id: 'bvp_armor_cube_faces',
            title: 'Cube Face Armor',
            width: 380,
            form,
            onConfirm(result) {
                const map = {};
                for (const face of CUBE_FACES) {
                    const text = String(result[face] ?? '').trim();
                    if (!text) continue;
                    const mm = cleanMm(text);
                    if (mm === null) {
                        Blockbench.showQuickMessage(`${face}: "${text}" is not a thickness`, 3000);
                        return false;
                    }
                    map[face] = mm;
                }
                Undo.initEdit({elements: cubes});
                for (const cube of cubes) cube[KEY] = Object.assign({}, map);
                Undo.finishEdit('Set cube face armor');
                refreshOverlay();
            },
        }).show();
    }

    function summary() {
        const rows = [];
        const elements = [...(Mesh.all || []), ...(Cube.all || [])];
        for (const element of elements) {
            const map = element[KEY] || {};
            const keys = isMesh(element) ? meshFaceKeys(element) : CUBE_FACES;
            const counts = new Map();
            for (const k of keys) {
                const mm = cleanMm(map[k]);
                if (mm !== null) counts.set(mm, (counts.get(mm) || 0) + 1);
            }
            if (!counts.size) continue;
            const info = volumeInfo(element.parent);
            const volume = info ? `${info.bone}${info.mm !== null ? ` (${info.mm} mm)` : ''}` : '(no volume bone)';
            const values = [...counts].sort((a, b) => a[0] - b[0])
                .map(([mm, n]) => `<span style="color:${colourFor(mm)}">&#9632;</span> ${mm} mm &times; ${n}`)
                .join(', ');
            rows.push(`<tr><td>${escapeHtml(volume)}</td><td>${escapeHtml(element.name)}</td>`
                + `<td>${values}</td><td>${keys.length - [...counts.values()].reduce((a, b) => a + b, 0)}</td></tr>`);
        }
        const table = rows.length
            ? '<table style="width:100%;border-collapse:collapse" cellpadding="4"><tr><th align="left">Volume</th>'
                + '<th align="left">Element</th><th align="left">Faces with own thickness</th><th>Default</th></tr>'
                + rows.join('') + '</table>'
            : '<p>No face has its own armor thickness yet.</p>';
        new Dialog({
            id: 'bvp_armor_summary',
            title: 'Face Armor Summary',
            width: 720,
            lines: [`<div style="max-height:60vh;overflow:auto">${table}</div>`],
            singleButton: true,
        }).show();
    }

    function escapeHtml(text) {
        return String(text).replace(/[&<>"]/g, (c) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
    }

    // ---------------------------------------------------------- viewport overlay (tint faces by thickness)

    const overlayName = 'bvp_face_armor_overlay';

    function clearOverlay() {
        for (const element of [...(Mesh.all || []), ...(Cube.all || [])]) {
            const object = element.mesh;
            if (!object) continue;
            const old = object.getObjectByName(overlayName);
            if (old) {
                object.remove(old);
                old.traverse((o) => {
                    if (o.geometry) o.geometry.dispose();
                    if (o.material) o.material.dispose();
                });
            }
        }
    }

    function cubeCorners(cube) {
        const inflate = cube.inflate || 0;
        const from = cube.from.map((v, i) => v - inflate - cube.origin[i]);
        const to = cube.to.map((v, i) => v + inflate - cube.origin[i]);
        const p = (x, y, z) => [x ? to[0] : from[0], y ? to[1] : from[1], z ? to[2] : from[2]];
        return {
            north: [p(1, 0, 0), p(0, 0, 0), p(0, 1, 0), p(1, 1, 0)],
            south: [p(0, 0, 1), p(1, 0, 1), p(1, 1, 1), p(0, 1, 1)],
            east: [p(1, 0, 1), p(1, 0, 0), p(1, 1, 0), p(1, 1, 1)],
            west: [p(0, 0, 0), p(0, 0, 1), p(0, 1, 1), p(0, 1, 0)],
            up: [p(0, 1, 1), p(1, 1, 1), p(1, 1, 0), p(0, 1, 0)],
            down: [p(0, 0, 0), p(1, 0, 0), p(1, 0, 1), p(0, 0, 1)],
        };
    }

    function refreshOverlay() {
        try {
            clearOverlay();
            if (!overlayOn || typeof THREE === 'undefined') return;
            for (const element of [...(Mesh.all || []), ...(Cube.all || [])]) {
                const map = element[KEY] || {};
                if (!element.mesh || !Object.keys(map).length) continue;
                const polygons = [];
                if (isMesh(element)) {
                    for (const key of meshFaceKeys(element)) {
                        const mm = cleanMm(map[key]);
                        const face = element.faces[key];
                        if (mm === null || !face) continue;
                        const keys = typeof face.getSortedVertices === 'function' ? face.getSortedVertices() : face.vertices;
                        polygons.push({mm, points: keys.map((k) => element.vertices[k]).filter(Boolean)});
                    }
                } else if (isCube(element)) {
                    const corners = cubeCorners(element);
                    for (const face of CUBE_FACES) {
                        const mm = cleanMm(map[face]);
                        if (mm !== null) polygons.push({mm, points: corners[face]});
                    }
                }
                if (!polygons.length) continue;
                const group = new THREE.Group();
                group.name = overlayName;
                for (const polygon of polygons) {
                    if (polygon.points.length < 3) continue;
                    const positions = [];
                    for (let j = 1; j + 1 < polygon.points.length; j++) {
                        positions.push(...polygon.points[0], ...polygon.points[j], ...polygon.points[j + 1]);
                    }
                    const geometry = new THREE.BufferGeometry();
                    geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
                    const material = new THREE.MeshBasicMaterial({
                        color: new THREE.Color(colourFor(polygon.mm)), transparent: true, opacity: 0.55,
                        side: THREE.DoubleSide, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -2,
                        polygonOffsetUnits: -2,
                    });
                    group.add(new THREE.Mesh(geometry, material));
                }
                element.mesh.add(group);
            }
        } catch (e) {
            console.warn('[BVP Armor Faces] overlay failed', e);
        }
    }

    // ------------------------------------------------------------------ codec hooks

    function geometryOf(model) {
        return model && model['minecraft:geometry'] ? model['minecraft:geometry'][0] : model;
    }

    function allGroups() {
        if (typeof getAllGroups === 'function') return getAllGroups();
        return (typeof Group !== 'undefined' && Group.all) || [];
    }

    function bvpArmorFacesOnCompile({model}) {
        try {
            const geometry = geometryOf(model);
            if (geometry && Array.isArray(geometry.bones)) compileInto(geometry.bones, allGroups(), warn);
        } catch (e) {
            console.error('[BVP Armor Faces] export failed', e);
        }
    }

    function bvpArmorFacesOnParsed({model}) {
        const geometry = geometryOf(model);
        if (!geometry || !Array.isArray(geometry.bones)) return;
        if (!geometry.bones.some((b) => b[KEY] || (Array.isArray(b.cubes) && b.cubes.some((c) => c && c[KEY])))) return;
        const project = typeof Project !== 'undefined' ? Project : null;
        // after Meshy's own 'parsed' handler has turned the poly_mesh bones into Mesh elements
        setTimeout(() => {
            try {
                const groups = (project && project.groups) || allGroups();
                const applied = applyParsed(geometry.bones, (name) => groups.find((g) => g.name === name), warn);
                if (applied) {
                    Blockbench.showQuickMessage(`Face armor loaded on ${applied} element(s)`, 2000);
                    refreshOverlay();
                }
            } catch (e) {
                console.error('[BVP Armor Faces] import failed', e);
            }
        }, 0);
    }

    function hookCodec(id) {
        const codec = Codecs[id];
        if (!codec) return;
        codec.on('compile', bvpArmorFacesOnCompile);
        codec.on('parsed', bvpArmorFacesOnParsed);
        disposers.push(() => {
            if (typeof codec.removeListener === 'function') {
                codec.removeListener('compile', bvpArmorFacesOnCompile);
                codec.removeListener('parsed', bvpArmorFacesOnParsed);
            } else {
                for (const event of ['compile', 'parsed']) {
                    const list = codec.events && codec.events[event];
                    if (!list) continue;
                    for (let i = list.length - 1; i >= 0; i--) {
                        if (list[i] === bvpArmorFacesOnCompile || list[i] === bvpArmorFacesOnParsed) list.splice(i, 1);
                    }
                }
            }
        });
    }

    BBPlugin.register('bvp_armor_faces', {
        title: 'BVP Armor Faces',
        author: 'BVP',
        description: 'Per-face armor thickness for BVP armor meshes, exported as bvp_face_mm.',
        icon: 'shield',
        version: '1.0.0',
        variant: 'both',
        min_version: '4.8.0',
        tags: ['Minecraft: Bedrock Edition'],
        onload() {
            new Property(Mesh, 'object', KEY, {default: {}});
            new Property(Cube, 'object', KEY, {default: {}});
            disposers.push(() => {
                if (Mesh.properties[KEY]) Mesh.properties[KEY].delete();
                if (Cube.properties[KEY]) Cube.properties[KEY].delete();
            });

            const actions = [
                new Action('bvp_armor_set_face_mm', {
                    name: 'Set Face Armor Thickness...',
                    description: 'Give the selected faces their own armor thickness (mm)',
                    icon: 'shield',
                    category: 'edit',
                    condition: () => (Mesh.selected || []).length || (Cube.selected || []).length,
                    click: setThickness,
                }),
                new Action('bvp_armor_clear_face_mm', {
                    name: 'Clear Face Armor Thickness',
                    description: 'The selected faces use their volume thickness again',
                    icon: 'remove_moderator',
                    category: 'edit',
                    condition: () => (Mesh.selected || []).length || (Cube.selected || []).length,
                    click: clearThickness,
                }),
                new Action('bvp_armor_cube_faces', {
                    name: 'Cube Face Armor...',
                    description: 'Edit the armor thickness of all six faces of the selected cubes',
                    icon: 'view_in_ar',
                    category: 'edit',
                    condition: () => (Cube.selected || []).length,
                    click: editCubeFaces,
                }),
                new Action('bvp_armor_summary', {
                    name: 'Face Armor Summary',
                    description: 'List every face with its own armor thickness',
                    icon: 'table_view',
                    category: 'view',
                    click: summary,
                }),
                new Toggle('bvp_armor_overlay', {
                    name: 'Show Face Armor',
                    description: 'Tint faces with their own thickness: blue thin, red 250 mm or more',
                    icon: 'palette',
                    category: 'view',
                    default: false,
                    onChange(value) {
                        overlayOn = value;
                        refreshOverlay();
                    },
                }),
            ];
            const menu = MenuBar.menus && MenuBar.menus.filter ? 'filter' : 'tools';
            for (const action of actions) MenuBar.addAction(action, menu);
            for (const action of actions.slice(0, 2)) {
                Mesh.prototype.menu && Mesh.prototype.menu.addAction(action);
                Cube.prototype.menu && Cube.prototype.menu.addAction(action);
            }
            Cube.prototype.menu && Cube.prototype.menu.addAction(actions[2]);
            disposers.push(() => {
                for (const action of actions) {
                    Mesh.prototype.menu && Mesh.prototype.menu.removeAction(action);
                    Cube.prototype.menu && Cube.prototype.menu.removeAction(action);
                    action.delete();
                }
            });

            hookCodec('bedrock');
            hookCodec('bedrock_old');

            for (const event of ['finish_edit', 'undo', 'redo', 'select_project', 'load_project']) {
                const listener = () => refreshOverlay();
                Blockbench.on(event, listener);
                disposers.push(() => Blockbench.removeListener(event, listener));
            }
        },
        onunload() {
            overlayOn = false;
            try {
                clearOverlay();
            } catch (e) {
                console.warn(e);
            }
            while (disposers.length) {
                try {
                    disposers.pop()();
                } catch (e) {
                    console.warn(e);
                }
            }
        },
    });
})();
