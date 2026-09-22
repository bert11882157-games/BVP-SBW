import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { deflateRawSync } from 'node:zlib';
import { ABI_WITNESSES, readClass, readMappings, verifyRuntimeAbi, openArchive } from './runtime-abi.mjs';

const u1 = n => Buffer.from([n]);
const u2 = n => { const b = Buffer.alloc(2); b.writeUInt16BE(n); return b; };
const u4 = n => { const b = Buffer.alloc(4); b.writeUInt32BE(n); return b; };
const join = (...parts) => Buffer.concat(parts.flat());

// Minimal byte fixtures exercise the parser and descriptors without invoking javac.
function classBytes(type) {
  const cp = [], utf = value => { cp.push(join(u1(1), u2(Buffer.byteLength(value)), Buffer.from(value))); return cp.length; };
  const clazz = name => { const text = utf(name); cp.push(join(u1(7), u2(text))); return cp.length; };
  const self = clazz(type.name), parent = type.superName ? clazz(type.superName) : 0;
  const methods = type.methods.map(method => {
    const name = utf(method.name), descriptor = utf(method.descriptor), codeName = utf('Code');
    const code = [];
    for (const call of method.calls ?? []) {
      const owner = clazz(call.owner), n = utf(call.name), d = utf(call.descriptor);
      cp.push(join(u1(12), u2(n), u2(d)));
      const pair = cp.length;
      cp.push(join(u1(10), u2(owner), u2(pair)));
      code.push(join(u1(call.opcode), u2(cp.length)));
    }
    code.push(method.code ?? u1(method.descriptor.endsWith('V') ? 177 : 172));
    const instructions = join(code), attribute = join(u2(8), u2(8), u4(instructions.length), instructions, u2(0), u2(0));
    return join(u2(method.access ?? 1), u2(name), u2(descriptor), u2(1), u2(codeName), u4(attribute.length), attribute);
  });
  return join(u4(0xcafebabe), u2(0), u2(61), u2(cp.length + 1), cp, u2(0x21), u2(self), u2(parent), u2(0), u2(0), u2(methods.length), methods, u2(0));
}
const vehicle = ABI_WITNESSES[0].owner;
const geo = 'com/atsuishio/superbwarfare/entity/vehicle/base/GeoVehicleEntity';
const sbw = 'com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity';
const parents = {
  [vehicle]: geo,
  [ABI_WITNESSES[3].owner]: 'net/minecraft/client/particle/TextureSheetParticle',
  [ABI_WITNESSES[4].owner]: 'net/minecraft/client/renderer/entity/EntityRenderer',
  [ABI_WITNESSES[6].owner]: 'java/lang/Object',
  [geo]: sbw, [sbw]: 'net/minecraft/world/entity/Entity'
};
function fixture() {
  const a = new Map(), b = new Map();
  for (const [name, superName] of Object.entries(parents)) {
    a.set(name, { name, superName, methods: [] }); b.set(name, { name, superName, methods: [] });
  }
  const map = new Map();
  for (const witness of ABI_WITNESSES) {
    for (const [types, methodName] of [[a, witness.name], [b, witness.runtime]]) {
      const method = { name: methodName, descriptor: witness.descriptor, access: 1, calls: witness.superCall ?
        [{ opcode: 183, owner: parents[witness.owner], name: methodName, descriptor: witness.descriptor }] : [] };
      types.get(witness.owner).methods.push(method);
      if (witness.parent) types.get(witness.parent).methods.push({ ...method, calls: [] });
    }
    if (witness.mappingOwner) {
      if (!map.has(witness.mappingOwner)) map.set(witness.mappingOwner, []);
      map.get(witness.mappingOwner).push(`\t${witness.name} ${witness.descriptor} ${witness.runtime}`);
    }
  }
  const mappings = readMappings('tsrg2 left right\n' + [...map].map(([owner, rows]) => `${owner} ${owner}\n${rows.join('\n')}`).join('\n'));
  const reader = types => ({ get: async name => {
    assert.ok(types.has(name), `Missing superclass: ${name}`);
    return readClass(classBytes(types.get(name)));
  } });
  return { a, b, inputs: { named: reader(a), runtime: reader(b), namedApi: reader(a), runtimeApi: reader(b), mappings } };
}

test('ABI gate accepts all seven exact mapped declarations and super-calls', async () => {
  assert.equal((await verifyRuntimeAbi(fixture().inputs)).length, 7);
});
for (const index of [0, 1, 2, 3, 4]) {
  test(`ABI gate rejects stranded named ${ABI_WITNESSES[index].name} override`, async () => {
    const f = fixture(), w = ABI_WITNESSES[index];
    f.b.get(w.owner).methods.find(x => x.name === w.runtime).name = w.name;
    await assert.rejects(verifyRuntimeAbi(f.inputs), /ABI declaration/);
  });
}
test('ABI gate rejects wrong descriptors, unmapped super-calls, access changes and residual aliases', async () => {
  for (const mutate of [
    method => { method.descriptor = '(I)V'; },
    method => { method.calls[0].name = 'defineSynchedData'; },
    method => { method.access = 9; },
    (method, type) => { type.methods.push({ ...method, name: 'defineSynchedData' }); }
  ]) {
    const f = fixture(), type = f.b.get(vehicle);
    mutate(type.methods[0], type);
    await assert.rejects(verifyRuntimeAbi(f.inputs), /ABI declaration|super-call|static|override remains/);
  }
});
test('custom SBW render and afterVehicleTick must not receive Minecraft names', async () => {
  for (const index of [5, 6]) {
    const f = fixture(), w = ABI_WITNESSES[index];
    f.b.get(w.owner).methods.find(x => x.name === w.name).name = 'm_7392_';
    await assert.rejects(verifyRuntimeAbi(f.inputs), /ABI declaration/);
  }
});
test('missing superclass, wrong runtime parent and mapping drift are rejected', async () => {
  const missing = fixture(); missing.a.delete(geo);
  await assert.rejects(verifyRuntimeAbi(missing.inputs), /Missing superclass/);
  const parent = fixture(); parent.b.get(geo).superName = 'java/lang/Object';
  await assert.rejects(verifyRuntimeAbi(parent.inputs), /superclass chain/);
  const mapping = fixture();
  mapping.inputs.mappings.methods.set('net/minecraft/world/entity/Entity\0defineSynchedData\0()V', 'm_9999_');
  await assert.rejects(verifyRuntimeAbi(mapping.inputs), /mapping contract/);
});
test('class reader skips operand bytes and rejects truncation/unknown constants', () => {
  const value = classBytes({ name: 'Example', superName: 'java/lang/Object', methods: [{ name: 'tick', descriptor: '()V', code: Buffer.from([16, 183, 87, 177]) }] });
  assert.deepEqual(readClass(value).methods[0].calls, []);
  assert.throws(() => readClass(value.subarray(0, value.length - 1)), /Truncated/);
  const broken = Buffer.from(value); broken[10] = 99;
  assert.throws(() => readClass(broken), /constant/);
});

function zipBytes(rows) {
  const locals = [], directory = [];
  let offset = 0;
  for (const [name, value, method = 8] of rows) {
    const encoded = Buffer.from(name), data = method === 8 ? deflateRawSync(value) : value;
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(method, 8);
    local.writeUInt32LE(data.length, 18); local.writeUInt32LE(value.length, 22); local.writeUInt16LE(encoded.length, 26);
    const entry = Buffer.alloc(46); entry.writeUInt32LE(0x02014b50); entry.writeUInt16LE(method, 10);
    entry.writeUInt32LE(data.length, 20); entry.writeUInt32LE(value.length, 24); entry.writeUInt16LE(encoded.length, 28); entry.writeUInt32LE(offset, 42);
    locals.push(join(local, encoded, data)); directory.push(join(entry, encoded)); offset += local.length + encoded.length + data.length;
  }
  const central = join(directory), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(rows.length, 8); end.writeUInt16LE(rows.length, 10); end.writeUInt32LE(central.length, 12); end.writeUInt32LE(offset, 16);
  return join(locals, central, end);
}
test('archive reader handles stored/deflated classes and fails on missing/duplicate/corrupt entries', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'runtime-abi-'));
  t.after(async () => { assert.equal(path.dirname(root), os.tmpdir()); await fs.rm(root, { recursive: true }); });
  const file = path.join(root, 'test.jar'), value = classBytes({ name: 'Example', superName: 'java/lang/Object', methods: [] });
  for (const method of [0, 8]) {
    await fs.writeFile(file, zipBytes([['Example.class', value, method]]));
    const jar = await openArchive(file);
    try { assert.equal((await jar.get('Example')).superName, 'java/lang/Object'); await assert.rejects(jar.get('Absent'), /Missing archive/); }
    finally { await jar.close(); }
  }
  await fs.writeFile(file, zipBytes([['Example.class', value], ['Example.class', value]]));
  await assert.rejects(openArchive(file), /Duplicate/);
  await fs.writeFile(file, Buffer.from('not a zip'));
  await assert.rejects(openArchive(file), /Missing ZIP/);
});
