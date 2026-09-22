import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { readMemberMap, remapJava, rejectOverlappingPaths, inspectOutput, translateSources } from './remap-sources.mjs';

const sample = 'tsrg2 left right\na/a a/a\n\tmove ()V m_12_\n\tvalue f_13_\n\t\t0 p_1_ p_1_\n';
test('map only member tokens, preserving literal and comment bytes', () => {
  const input = 'a.m_12_(); int x = a.f_13_; // m_12_\n/* f_13_ */ "m_12_\\\"f_13_"; \'x\'; """\nf_13_\n"""; my_m_12_;';
  const result = remapJava(input, readMemberMap(sample));
  assert.equal(result.output, input.replace('a.m_12_', 'a.move').replace('a.f_13_', 'a.value'));
  assert.equal(result.replacements, 2);
});
test('reject ambiguity, unknown members and reversed/empty mapping', () => {
  assert.throws(() => readMemberMap(sample + '\tother ()V m_12_\n'), /Ambiguous/);
  assert.throws(() => remapJava('m_99_();', readMemberMap(sample)), /Unknown/);
  assert.throws(() => readMemberMap('tsrg2 a b\n\tm_12_ ()V move'), /Empty/);
  assert.throws(() => readMemberMap('not mapping'), /TSRG2/);
});

async function fixture(t) {
  const base = await fs.realpath(os.tmpdir());
  const root = await fs.mkdtemp(path.join(base, 'source-map-test-'));
  t.after(async () => {
    const resolved = await fs.realpath(root);
    assert.equal(path.dirname(resolved).toLowerCase(), base.toLowerCase());
    assert.ok(path.basename(resolved).startsWith('source-map-test-'));
    await fs.rm(resolved, { recursive: true });
  });
  const source = path.join(root, 'src'), output = path.join(root, 'build', 'named');
  await fs.mkdir(source);
  await fs.writeFile(path.join(source, 'A.java'), 'class A { void f() { m_12_(); } }');
  return { root, source, output };
}
test('symmetric overlap and Windows case aliases fail before writes', async t => {
  const f = await fixture(t);
  await assert.rejects(inspectOutput(f.source, f.root), /overlap/);
  await assert.rejects(inspectOutput(f.source, path.join(f.source, 'child')), /overlap/);
  await assert.rejects(inspectOutput(f.source, f.source), /overlap/);
  assert.throws(() => rejectOverlappingPaths('C:/Project/src', 'c:/PROJECT', true), /overlap/);
  assert.throws(() => rejectOverlappingPaths('C:/Project/src', 'c:/project/SRC', true), /overlap/);
  if (process.platform === 'win32') await assert.rejects(inspectOutput(f.source, f.source.toUpperCase()), /overlap/);
  assert.equal(await fs.readFile(path.join(f.source, 'A.java'), 'utf8'), 'class A { void f() { m_12_(); } }');
});
test('output and ancestor junctions/symlinks are refused', async t => {
  const f = await fixture(t);
  const target = path.join(f.root, 'target');
  await fs.mkdir(target);
  const alias = path.join(f.root, 'alias');
  await fs.symlink(target, alias, process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(inspectOutput(f.source, alias), /Symlink|junction/);
  await assert.rejects(inspectOutput(f.source, path.join(alias, 'nested')), /Symlink|junction/);
});
test('unowned files and directories are preserved and refused', async t => {
  const f = await fixture(t);
  await fs.mkdir(f.output, { recursive: true });
  const note = path.join(f.output, 'keep.txt');
  await fs.writeFile(note, 'user data');
  await assert.rejects(translateSources(f.source, f.output, readMemberMap(sample)), /unowned/);
  assert.equal(await fs.readFile(note, 'utf8'), 'user data');
  await fs.unlink(note);
  await fs.mkdir(path.join(f.output, 'keep-directory'));
  await assert.rejects(translateSources(f.source, f.output, readMemberMap(sample)), /unowned/);
});
test('only prior hash-validated manifest entries can be removed', async t => {
  const f = await fixture(t);
  const map = readMemberMap(sample);
  await fs.writeFile(path.join(f.source, 'B.java'), 'class B {}');
  await translateSources(f.source, f.output, map);
  await fs.unlink(path.join(f.source, 'B.java'));
  await translateSources(f.source, f.output, map);
  await assert.rejects(fs.stat(path.join(f.output, 'B.java')), { code: 'ENOENT' });
  const original = await fs.readFile(path.join(f.output, 'A.java'), 'utf8');
  await fs.writeFile(path.join(f.output, 'unrelated.txt'), 'keep');
  await assert.rejects(translateSources(f.source, f.output, map), /Unowned/);
  assert.equal(await fs.readFile(path.join(f.output, 'A.java'), 'utf8'), original);
  assert.equal(await fs.readFile(path.join(f.output, 'unrelated.txt'), 'utf8'), 'keep');
});
test('modified owned output and hard-link aliases fail closed', async t => {
  const f = await fixture(t);
  const map = readMemberMap(sample);
  await translateSources(f.source, f.output, map);
  await fs.writeFile(path.join(f.output, 'A.java'), 'user edit');
  await assert.rejects(translateSources(f.source, f.output, map), /Modified/);
  assert.equal(await fs.readFile(path.join(f.output, 'A.java'), 'utf8'), 'user edit');
  await fs.link(path.join(f.source, 'A.java'), path.join(f.root, 'linked.java'));
  await assert.rejects(translateSources(f.source, path.join(f.root, 'new-output'), map), /Hard-link/);
});
