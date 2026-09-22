import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { remapWithVerifiedLibraries } from './remap-addon.mjs';

async function fixture(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'remap-inputs-'));
  t.after(async () => { assert.equal(path.dirname(root), os.tmpdir()); await fs.rm(root, { recursive: true }); });
  const row = async (id, bytes) => {
    const file = path.join(root, `${id}.jar`);
    await fs.writeFile(file, bytes);
    return { id, version: 'test', path: file, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
  };
  const libraries = [await row('sbw-mapped-api', 'api'), await row('sbwmeshloader-mapped', 'mesh')];
  const verify = async expected => {
    const bytes = await fs.readFile(expected.path);
    assert.equal(bytes.length, expected.size, 'Input size mismatch');
    assert.equal(createHash('sha256').update(bytes).digest('hex'), expected.sha256, 'Input hash mismatch');
    return expected;
  };
  return { id: 'bvp-runtime-map', file: path.join(root, 'bvp.jar'), direction: 'runtime',
    libraries, verify, manifest: path.join(root, 'libraries.json') };
}

test('runtime remap receives exact mapped API and mesh paths plus verified manifest', async t => {
  const args = await fixture(t);
  let invocations = 0;
  const result = await remapWithVerifiedLibraries({ ...args, execute: async command => {
    invocations++;
    assert.ok(command.includes(`-PsourceAddonLibraries=${args.libraries.map(x => x.path).join(path.delimiter)}`));
    assert.ok(command.includes(`-PsourceAddonLibraryManifest=${args.manifest}`));
    assert.deepEqual(JSON.parse(await fs.readFile(args.manifest, 'utf8')), { schema: 1, direction: 'runtime', libraries: args.libraries });
    return { id: args.id, status: 'passed' };
  } });
  assert.equal(invocations, 1);
  assert.equal(result.status, 'passed');
});

test('missing/wrong-namespace libraries fail before invoking the remapper', async t => {
  const args = await fixture(t);
  const execute = async () => assert.fail('Remapper must not run');
  for (const libraries of [[], args.libraries.slice(1), args.libraries.slice(0, 1)]) {
    await assert.rejects(remapWithVerifiedLibraries({ ...args, libraries, execute }), /libraries|namespace|mesh dependency/);
  }
  await assert.rejects(remapWithVerifiedLibraries({ ...args, direction: 'named', execute }), /namespace/);
});

test('library hash mismatch and in-flight mutation cannot yield a passing remap check', async t => {
  const args = await fixture(t);
  const wrong = structuredClone(args.libraries);
  wrong[1].sha256 = '0'.repeat(64);
  await assert.rejects(remapWithVerifiedLibraries({ ...args, libraries: wrong, execute: async () => assert.fail('must not run') }), /hash mismatch/);
  await assert.rejects(remapWithVerifiedLibraries({ ...args, execute: async () => {
    await fs.writeFile(args.libraries[1].path, 'evil');
    return { status: 'passed' };
  } }), /hash mismatch/);
});

test('reverse remap requires the runtime SBW namespace', async t => {
  const args = await fixture(t);
  const row = { ...args.libraries[0], id: 'sbw' };
  await remapWithVerifiedLibraries({ ...args, id: 'meshloader-compiler-map', direction: 'named', libraries: [row],
    execute: async command => { assert.ok(command.includes('-PsourceAddonDirection=named')); return { status: 'passed' }; } });
});

test('bridge pins archive identity, excludes ambient timestamps, and checks inheritance hashes', async () => {
  const bridge = await fs.readFile(new URL('./sbw-bridge.gradle', import.meta.url), 'utf8');
  for (const expected of ['archiveVersion.set(pinnedVersion)', "manifest.attributes(['Implementation-Version': pinnedVersion])",
    "manifest.attributes.remove('Implementation-Timestamp')", 'preserveFileTimestamps = false', 'reproducibleFileOrder = true',
    'identity(f, row.id, row.version).sha256 != row.sha256', 'libraries.from(extraLibraries)']) assert.ok(bridge.includes(expected), expected);
});
