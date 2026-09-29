// Unit test for the data side of bvp_armor_faces.js (no Blockbench needed): node tools/blockbench/test_bvp_armor_faces.js
'use strict';
const assert = require('assert');
const path = require('path');
const plugin = require(path.join(__dirname, 'bvp_armor_faces.js'));

const {KEY, cleanMm, compileInto, applyParsed, volumeInfo} = plugin;

function mesh(name, faceKeys, faceMm) {
    const faces = {};
    for (const k of faceKeys) faces[k] = {vertices: []};
    return {type: 'mesh', name, faces, [KEY]: faceMm || {}};
}

function cube(name, faceMm, exported = true) {
    return {type: 'cube', name, export: exported, [KEY]: faceMm || {}};
}

function group(name, children, parent) {
    const g = {type: 'group', name, children, parent, export: true};
    children.forEach((c) => { c.parent = g; });
    return g;
}

// cleanMm
assert.strictEqual(cleanMm(80), 80);
assert.strictEqual(cleanMm('12.345'), 12.35);
assert.strictEqual(cleanMm(0), 0);
assert.strictEqual(cleanMm(-1), null);
assert.strictEqual(cleanMm(''), null);
assert.strictEqual(cleanMm('abc'), null);
assert.strictEqual(cleanMm(null), null);

// compile: two meshes in one bone, faces in insertion order; stale keys dropped; cubes matched by exported order
const hull = group('armor_hull', []);
const m1 = mesh('a', ['k1', 'k2', 'k3'], {k2: 80, gone: 99});
const m2 = mesh('b', ['j1', 'j2'], {j1: '20'});
const c1 = cube('c1', {north: 45, east: null});
const hidden = cube('hidden', {north: 5}, false);
const c2 = cube('c2', {});
const plate = group('plate__30mm__ufp', [m1, c1, hidden, m2, c2], hull);
const bare = group('plate__10mm__bare', [mesh('x', ['q'])], hull);
const bones = [
    {name: 'armor_hull'},
    {name: 'plate__30mm__ufp', poly_mesh: {polys: [[], [], [], [], []]}, cubes: [{origin: [0, 0, 0]}, {origin: [1, 1, 1]}]},
    {name: 'plate__10mm__bare', poly_mesh: {polys: [[]]}},
];
const warnings = [];
compileInto(bones, [hull, plate, bare], (w) => warnings.push(w));
assert.deepStrictEqual(bones[1][KEY], [null, 80, null, 20, null]);
assert.deepStrictEqual(bones[1].cubes[0][KEY], {north: 45});
assert.strictEqual(bones[1].cubes[1][KEY], undefined);
assert.strictEqual(bones[2][KEY], undefined, 'no face armor: nothing written');
assert.deepStrictEqual(warnings, []);

// compile before Meshy (no poly_mesh yet) still writes; a polygon count mismatch does not
const early = [{name: 'plate__30mm__ufp', cubes: [{}, {}]}];
compileInto(early, [plate], () => {});
assert.deepStrictEqual(early[0][KEY], [null, 80, null, 20, null]);
const wrong = [{name: 'plate__30mm__ufp', poly_mesh: {polys: [[]]}, cubes: [{}]}];
const w2 = [];
compileInto(wrong, [plate], (w) => w2.push(w));
assert.strictEqual(wrong[0][KEY], undefined);
assert.strictEqual(w2.length, 2, 'polygon and cube mismatch both warned');

// parse: round trip onto freshly imported elements (Meshy: one mesh per bone, faces in polys order)
const im = mesh('mesh', ['n0', 'n1', 'n2', 'n3', 'n4']);
const ic1 = cube('c1');
const ic2 = cube('c2');
const imported = group('plate__30mm__ufp', [ic1, ic2, im], null);
const parsedBones = JSON.parse(JSON.stringify(bones));
const applied = applyParsed(parsedBones, (name) => (name === imported.name ? imported : null), (w) => warnings.push(w));
assert.strictEqual(applied, 2);
assert.deepStrictEqual(im[KEY], {n1: 80, n3: 20});
assert.deepStrictEqual(ic1[KEY], {north: 45});
assert.deepStrictEqual(ic2[KEY], {});
assert.deepStrictEqual(warnings, []);

// parse without Meshy (no mesh built): skipped with a warning
const lonely = group('plate__30mm__ufp', [cube('c1'), cube('c2')], null);
const w3 = [];
applyParsed(JSON.parse(JSON.stringify(bones)), () => lonely, (w) => w3.push(w));
assert.strictEqual(w3.length, 1);

// volume info from the nearest volume bone
assert.deepStrictEqual(volumeInfo(plate), {bone: 'plate__30mm__ufp', mm: 30, plate: true});
const nested = group('extra', [], plate);
assert.strictEqual(volumeInfo(nested).mm, 30);
assert.strictEqual(volumeInfo(group('armor__12p5__skirt', [])).mm, 12.5);
assert.strictEqual(volumeInfo(group('era__kontakt5__x', [])).plate, false);
assert.strictEqual(volumeInfo(group('wheel', [])), null);

console.log('PASS bvp_armor_faces: compile, parse round trip, mismatches, volume names');
