import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { convertModel, outputs, worldVertex } from './convert.mjs';

const source = JSON.parse(await readFile(new URL('kh55.bbmodel.json', import.meta.url)));
const close = (actual, expected, epsilon = 1e-8) => assert(Math.abs(actual - expected) <= epsilon,
  `${actual} differs from ${expected}`);
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const sub = (a, b) => a.map((n, i) => n - b[i]);

test('authored rotations preserve the nose, wing spread and asymmetric details', () => {
  // These landmarks are independent of the meshloader coordinate conversion.
  worldVertex([0, 14, 0], source.elements[0]).forEach((n, i) => close(n, [0, 95.6, 0][i]));
  worldVertex([0, 0, 0], source.elements[4]).forEach((n, i) => close(n, [0, .4, 0][i]));
  const all = source.elements.flatMap(e => Object.values(e.vertices).map(p => worldVertex(p, e)));
  const min = [0, 1, 2].map(i => Math.min(...all.map(p => p[i])));
  const max = [0, 1, 2].map(i => Math.max(...all.map(p => p[i])));
  min.forEach((n, i) => close(n, [-8.025952298742908, .4, -32.98][i]));
  max.forEach((n, i) => close(n, [8.025952298742915, 95.6, 32.98][i]));
});

test('both runtime variants preserve visible triangles, finite normals and exact texture placement', () => {
  for (const variant of ['projectiles', 'aircraft_stores']) {
    const { geometry, stats } = convertModel(source, variant);
    assert.deepEqual(stats, { elements: 12, vertices: 503, sourceFaces: 500,
      triangles: 872, zeroAreaTriangles: 16 });
    const mesh = geometry['minecraft:geometry'][0].bones[0].poly_mesh;
    const runtime = mesh.positions.map(([x, y, z]) => [-x / 16, y / 16, z / 16]);
    for (const poly of mesh.polys) {
      assert.equal(poly.length, 3);
      for (const [p, n, uv] of poly) {
        assert(mesh.positions[p] && mesh.normals[n] && mesh.uvs[uv]);
        mesh.uvs[uv].forEach(x => assert(Number.isFinite(x) && x >= 0 && x <= 1));
      }
      const [a, b, c] = poly.map(v => runtime[v[0]]);
      const actualNormal = cross(sub(b, a), sub(c, a));
      const length = Math.hypot(...actualNormal);
      assert(length > 1e-12, 'No zero-area faces reach meshloader normal calculation');
      const [nx, ny, nz] = mesh.normals[poly[0][1]];
      [-nx, ny, nz].forEach((n, i) => close(n, actualNormal[i] / length));
    }
    // Source first face starts joQ2 at UV(53,86.5229), with its original winding.
    const firstUv = mesh.uvs[mesh.polys[0][0][2]];
    close(firstUv[0] * 112, 53);
    close((1 - firstUv[1]) * 112, 86.5229);
    const bounds = [0, 1, 2].map(i => [Math.min(...runtime.map(p => p[i])),
      Math.max(...runtime.map(p => p[i]))]);
    close(bounds[0][0], -2.06125); close(bounds[0][1], 2.06125);
    const axis = variant === 'projectiles' ? 1 : 2;
    close(bounds[axis][0], variant === 'projectiles' ? 0 : -2.975);
    close(bounds[axis][1], variant === 'projectiles' ? 5.95 : 2.975);
  }
});

test('maintained resources reproduce byte-for-byte, including the supplied embedded PNG', async () => {
  const { files } = await outputs();
  for (const [path, bytes] of files) assert.deepEqual(await readFile(path), bytes);
  const png = [...files].find(([path]) => path.endsWith('.png'))[1];
  assert.deepEqual(png, Buffer.from(source.textures[0].source.split(',')[1], 'base64'));
});
