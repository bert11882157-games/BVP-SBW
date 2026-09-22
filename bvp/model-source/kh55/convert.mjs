import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const assets = resolve(here, '../../src/main/resources/assets/berts_vehicle_pack');
const sourcePath = resolve(here, 'kh55.bbmodel.json');
export const SOURCE_SHA256 = '30d9cc2ddbb4fbdd315a3540c62de5d762288e765bdde00aa67af26bf719b7ac';
export const NOZZLE_Y = 0.4;
export const CENTRE_Y = 48;

const subtract = (a, b) => a.map((n, i) => n - b[i]);
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const rounded = v => v.map(n => Math.abs(n) < 1e-10 ? 0 : Number(n.toFixed(10)));

/** Blockbench free-format uses local vertices and ZYX Euler rotations (Rx, then Ry, then Rz). */
export function worldVertex(vertex, element) {
  let [x, y, z] = vertex;
  const [a, b, c] = element.rotation.map(n => n * Math.PI / 180);
  [y, z] = [y * Math.cos(a) - z * Math.sin(a), y * Math.sin(a) + z * Math.cos(a)];
  [x, z] = [x * Math.cos(b) + z * Math.sin(b), -x * Math.sin(b) + z * Math.cos(b)];
  [x, y] = [x * Math.cos(c) - y * Math.sin(c), x * Math.sin(c) + y * Math.cos(c)];
  return [x, y, z].map((n, i) => n + element.origin[i]);
}

/** Rendered positions, before the loader's 16-units-per-block conversion. */
export function renderVertex([x, y, z], variant) {
  if (variant === 'projectiles') return [z, y - NOZZLE_Y, -x];
  assert.equal(variant, 'aircraft_stores');
  return [z, -x, CENTRE_Y - y];
}

export function convertModel(source, variant) {
  assert.equal(source.meta.model_format, 'free');
  assert.equal(source.elements.length, 12);
  assert.deepEqual(source.resolution, { width: 112, height: 112 });
  // This authored model has organizational groups only. Fail if later edits add bone transforms.
  for (const group of source.groups) {
    assert.deepEqual(group.rotation, [0, 0, 0]);
    assert.equal(group.visibility, true);
    assert.equal(group.export, true);
  }
  const mesh = { normalized_uvs: true, positions: [], normals: [], uvs: [], polys: [] };
  const stats = { elements: 0, vertices: 0, sourceFaces: 0, triangles: 0, zeroAreaTriangles: 0 };
  for (const element of source.elements) {
    assert.equal(element.type, 'mesh');
    assert.equal(element.visibility, true);
    assert.equal(element.export, true);
    assert.equal(element.shading, 'flat');
    stats.elements++;
    const indices = new Map();
    const rendered = new Map();
    for (const [key, vertex] of Object.entries(element.vertices)) {
      assert(vertex.length === 3 && vertex.every(Number.isFinite));
      const position = renderVertex(worldVertex(vertex, element), variant);
      rendered.set(key, position);
      indices.set(key, mesh.positions.length);
      // Meshloader mirrors raw X. Compensate here so its final frame is renderVertex's frame.
      mesh.positions.push(rounded([-position[0], position[1], position[2]]));
      stats.vertices++;
    }
    for (const face of Object.values(element.faces)) {
      stats.sourceFaces++;
      assert.equal(face.texture, 0);
      assert([3, 4].includes(face.vertices.length));
      // Saved Blockbench faces already have perimeter order. Keep its 0-2 quad diagonal.
      const triangles = face.vertices.length === 3 ? [face.vertices]
        : [face.vertices.slice(0, 3), [face.vertices[0], face.vertices[2], face.vertices[3]]];
      for (const triangle of triangles) {
        const points = triangle.map(key => rendered.get(key));
        assert(points.every(Boolean));
        const normal = cross(subtract(points[1], points[0]), subtract(points[2], points[0]));
        const length = Math.hypot(...normal);
        // The authored nose contains repeated-coordinate vertices. They have no visible area.
        if (length < 1e-9) { stats.zeroAreaTriangles++; continue; }
        const normalIndex = mesh.normals.length;
        mesh.normals.push(rounded([-normal[0] / length, normal[1] / length, normal[2] / length]));
        mesh.polys.push(triangle.map(key => {
          const uv = face.uv[key];
          assert(uv?.length === 2 && uv.every(n => Number.isFinite(n) && n >= 0 && n <= 112));
          const uvIndex = mesh.uvs.length;
          // The loader applies 1-v. These bottom-origin UVs reproduce Blockbench's image UVs.
          mesh.uvs.push(rounded([uv[0] / 112, 1 - uv[1] / 112]));
          return [indices.get(key), normalIndex, uvIndex];
        }));
        stats.triangles++;
      }
    }
  }
  const geometry = {
    format_version: '1.12.0',
    'minecraft:geometry': [{
      description: {
        identifier: `geometry.${variant}.kh55`, texture_width: 112, texture_height: 112,
        visible_bounds_width: 8, visible_bounds_height: 8,
        visible_bounds_offset: variant === 'projectiles' ? [0, 2.975, 0] : [0, 0, 0]
      },
      bones: [{ name: variant === 'projectiles' ? 'missile' : 'store', pivot: [0, 0, 0], poly_mesh: mesh }]
    }]
  };
  return { geometry, stats };
}

export async function outputs() {
  const bytes = await readFile(sourcePath);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), SOURCE_SHA256,
    'Review source provenance and transforms before accepting a changed authoring model');
  const source = JSON.parse(bytes);
  assert.equal(source.textures.length, 1);
  const texture = source.textures[0];
  assert.equal(texture.width, 112);
  assert.equal(texture.height, 112);
  assert(texture.source.startsWith('data:image/png;base64,'));
  const png = Buffer.from(texture.source.slice('data:image/png;base64,'.length), 'base64');
  assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.equal(png.readUInt32BE(16), 112);
  assert.equal(png.readUInt32BE(20), 112);
  const files = new Map([[resolve(assets, 'textures/aircraft_stores/kh55.png'), png]]);
  let stats;
  for (const variant of ['aircraft_stores', 'projectiles']) {
    const result = convertModel(source, variant);
    stats = result.stats;
    files.set(resolve(assets, `custom_geo/${variant}/kh55.geo.json`),
      Buffer.from(JSON.stringify(result.geometry, null, 2) + '\n'));
  }
  return { files, stats };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  assert(process.argv.slice(2).every(arg => arg === '--check'), 'Usage: node convert.mjs [--check]');
  const { files, stats } = await outputs();
  for (const [path, bytes] of files) {
    if (process.argv.includes('--check')) assert.deepEqual(await readFile(path), bytes, path);
    else { await mkdir(dirname(path), { recursive: true }); await writeFile(path, bytes); }
  }
  console.log(JSON.stringify({ mode: process.argv.includes('--check') ? 'check' : 'write', ...stats }));
}
