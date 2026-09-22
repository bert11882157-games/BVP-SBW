import fs from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { createHash } from 'node:crypto';
import { inflateRawSync } from 'node:zlib';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const vehicle = 'com/yourname/berts_vehicle_pack/entity/ArmoredVehicleEntity';
const sbwVehicle = 'com/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity';
const sbwGeo = 'com/atsuishio/superbwarfare/entity/vehicle/base/GeoVehicleEntity';
const tag = '(Lnet/minecraft/nbt/CompoundTag;)V';
export const ABI_WITNESSES = [
  { owner: vehicle, name: 'defineSynchedData', runtime: 'm_8097_', descriptor: '()V', mappingOwner: 'net/minecraft/world/entity/Entity', parent: sbwVehicle, superCall: true },
  { owner: vehicle, name: 'readAdditionalSaveData', runtime: 'm_7378_', descriptor: tag, mappingOwner: 'net/minecraft/world/entity/Entity', parent: sbwVehicle, superCall: true },
  { owner: vehicle, name: 'addAdditionalSaveData', runtime: 'm_7380_', descriptor: tag, mappingOwner: 'net/minecraft/world/entity/Entity', parent: sbwVehicle, superCall: true },
  { owner: 'com/yourname/berts_vehicle_pack/client/particle/BvpAnimatedParticle', name: 'tick', runtime: 'm_5989_', descriptor: '()V', mappingOwner: 'net/minecraft/client/particle/Particle', superCall: true },
  { owner: 'com/yourname/berts_vehicle_pack/client/renderer/BvpSpinningProjectileRenderer', name: 'render', runtime: 'm_7392_',
    descriptor: '(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V',
    mappingOwner: 'net/minecraft/client/renderer/entity/EntityRenderer', superCall: true },
  // These are real SBW API names, not missed Minecraft remaps.
  { owner: vehicle, name: 'afterVehicleTick', runtime: 'afterVehicleTick', descriptor: '()V', parent: sbwVehicle, superCall: true },
  { owner: 'com/yourname/berts_vehicle_pack/client/renderer/BaseVehicleRenderer', name: 'render', runtime: 'render',
    descriptor: '(Lcom/atsuishio/superbwarfare/client/renderer/vehicle/VehicleRenderBackendContext;)Z' }
];
const fail = message => { throw new Error(message); };
const need = (condition, message) => { if (!condition) fail(message); };

// Read only requested class entries; never extract or rewrite an archive.
export async function openArchive(file) {
  const handle = await fs.open(file, 'r');
  try {
    const size = (await handle.stat()).size;
    const read = async (offset, length) => {
      need(Number.isSafeInteger(offset) && Number.isSafeInteger(length) && offset >= 0 && length >= 0 && offset + length <= size, 'Invalid ZIP bounds');
      const buffer = Buffer.alloc(length);
      const result = await handle.read(buffer, 0, length, offset);
      need(result.bytesRead === length, 'Truncated ZIP');
      return buffer;
    };
    const tail = await read(Math.max(0, size - 65557), Math.min(size, 65557));
    let end = tail.length - 22;
    while (end >= 0 && (tail.readUInt32LE(end) !== 0x06054b50 || end + 22 + tail.readUInt16LE(end + 20) !== tail.length)) end--;
    need(end >= 0, 'Missing ZIP directory');
    const count = tail.readUInt16LE(end + 10), length = tail.readUInt32LE(end + 12), offset = tail.readUInt32LE(end + 16);
    need(tail.readUInt16LE(end + 4) === 0 && tail.readUInt16LE(end + 6) === 0 && tail.readUInt16LE(end + 8) === count, 'Split ZIP unsupported');
    need(count !== 65535 && length !== 0xffffffff && offset !== 0xffffffff, 'ZIP64 unsupported for runtime ABI check');
    need(length <= 64 * 1024 * 1024, 'Oversized ZIP directory');
    const directory = await read(offset, length), entries = new Map();
    let p = 0;
    for (let i = 0; i < count; i++) {
      need(p + 46 <= directory.length && directory.readUInt32LE(p) === 0x02014b50, 'Invalid ZIP entry');
      const nameLength = directory.readUInt16LE(p + 28), extra = directory.readUInt16LE(p + 30), comment = directory.readUInt16LE(p + 32);
      need(p + 46 + nameLength + extra + comment <= directory.length, 'Truncated ZIP entry');
      const name = directory.toString('utf8', p + 46, p + 46 + nameLength);
      need(!entries.has(name), `Duplicate ZIP entry: ${name}`);
      entries.set(name, { flags: directory.readUInt16LE(p + 8), method: directory.readUInt16LE(p + 10),
        compressed: directory.readUInt32LE(p + 20), size: directory.readUInt32LE(p + 24), offset: directory.readUInt32LE(p + 42) });
      p += 46 + nameLength + extra + comment;
    }
    need(p === directory.length, 'Unexpected ZIP directory tail');
    const entry = async name => {
      const row = entries.get(name);
      need(row, `Missing archive entry: ${name}`);
      need(!(row.flags & 1) && row.size <= 16 * 1024 * 1024 && row.compressed <= 16 * 1024 * 1024, 'Unsupported class entry');
      const local = await read(row.offset, 30);
      need(local.readUInt32LE(0) === 0x04034b50 && local.readUInt16LE(8) === row.method, 'Invalid ZIP local header');
      const bytes = await read(row.offset + 30 + local.readUInt16LE(26) + local.readUInt16LE(28), row.compressed);
      const result = row.method === 0 ? bytes : row.method === 8 ? inflateRawSync(bytes, { maxOutputLength: row.size }) : fail('Unsupported ZIP compression');
      need(result.length === row.size, 'ZIP entry size mismatch');
      return result;
    };
    const cache = new Map();
    return { entry, close: () => handle.close(), get: async name => {
      if (!cache.has(name)) cache.set(name, readClass(await entry(name + '.class')));
      const result = cache.get(name);
      need(result.name === name, 'Class/archive identity mismatch');
      return result;
    } };
  } catch (error) { await handle.close(); throw error; }
}

export function readClass(bytes) {
  let p = 0;
  const take = n => { need(p + n <= bytes.length, 'Truncated class'); const at = p; p += n; return at; };
  const u1 = () => bytes.readUInt8(take(1)), u2 = () => bytes.readUInt16BE(take(2)), u4 = () => bytes.readUInt32BE(take(4));
  need(u4() === 0xcafebabe, 'Invalid class header');
  u2(); u2();
  const pool = new Array(u2());
  for (let i = 1; i < pool.length; i++) {
    const type = u1();
    if (type === 1) { const n = u2(); pool[i] = bytes.toString('utf8', take(n), p); }
    else if ([3, 4].includes(type)) take(4);
    else if ([5, 6].includes(type)) { take(8); i++; }
    else if ([7, 8, 16, 19, 20].includes(type)) pool[i] = { type, a: u2() };
    else if ([9, 10, 11, 12, 17, 18].includes(type)) pool[i] = { type, a: u2(), b: u2() };
    else if (type === 15) { u1(); u2(); }
    else fail(`Unsupported class constant: ${type}`);
  }
  const text = index => { need(typeof pool[index] === 'string', 'Invalid class UTF8 reference'); return pool[index]; };
  const className = index => { need(pool[index]?.type === 7, 'Invalid class reference'); return text(pool[index].a); };
  const attributes = () => {
    const result = [];
    for (let n = u2(); n > 0; n--) { const name = text(u2()), length = u4(); result.push({ name, bytes: bytes.subarray(take(length), p) }); }
    return result;
  };
  const access = u2(), name = className(u2()), superIndex = u2(), superName = superIndex ? className(superIndex) : null;
  const interfaces = Array.from({ length: u2() }, () => className(u2()));
  for (let n = u2(); n > 0; n--) { u2(); u2(); u2(); attributes(); }
  const methods = [];
  for (let n = u2(); n > 0; n--) {
    const flags = u2(), method = text(u2()), descriptor = text(u2()), attrs = attributes();
    const code = attrs.find(x => x.name === 'Code')?.bytes;
    methods.push({ name: method, descriptor, access: flags, calls: code ? readCalls(code, pool, text, className) : [] });
  }
  attributes(); need(p === bytes.length, 'Unexpected class tail');
  return { name, superName, interfaces, access, methods };
}

function readCalls(attribute, pool, text, className) {
  need(attribute.length >= 8, 'Truncated Code attribute');
  const length = attribute.readUInt32BE(4);
  need(length <= attribute.length - 8, 'Truncated bytecode');
  const code = attribute.subarray(8, 8 + length), calls = [];
  const one = new Set([16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188]);
  const two = new Set([17, 19, 20, 132, ...Array.from({ length: 16 }, (_, i) => 153 + i),
    ...Array.from({ length: 7 }, (_, i) => 178 + i), 187, 189, 192, 193, 198, 199]);
  let p = 0;
  while (p < code.length) {
    const at = p, op = code[p++];
    if (op >= 182 && op <= 185) {
      need(p + 2 <= code.length, 'Truncated invocation');
      const ref = pool[code.readUInt16BE(p)], pair = pool[ref?.b];
      need([10, 11].includes(ref?.type) && pair?.type === 12, 'Invalid invocation reference');
      calls.push({ opcode: op, owner: className(ref.a), name: text(pair.a), descriptor: text(pair.b) });
    }
    if (op === 170 || op === 171) {
      p = (p + 3) & ~3;
      need(p + (op === 170 ? 12 : 8) <= code.length, 'Truncated switch');
      const count = op === 170 ? code.readInt32BE(p + 8) - code.readInt32BE(p + 4) + 1 : code.readInt32BE(p + 4);
      need(count >= 0 && count <= code.length, 'Invalid switch count');
      p += (op === 170 ? 12 : 8) + count * (op === 170 ? 4 : 8);
    } else if (op === 196) {
      need(p < code.length && [21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 132, 169].includes(code[p]), 'Invalid wide opcode');
      p += code[p] === 132 ? 5 : 3;
    } else if ([185, 186, 200, 201].includes(op)) p += 4;
    else if (op === 197) p += 3;
    else if (two.has(op)) p += 2;
    else if (one.has(op)) p++;
    else need(op <= 201, `Unknown bytecode opcode at ${at}`);
    need(p <= code.length, 'Truncated instruction');
  }
  return calls;
}

export function readMappings(text) {
  need(text.startsWith('tsrg2 '), 'Expected named-to-SRG TSRG2 mappings');
  const classes = new Map(), methods = new Map();
  let owner;
  for (const line of text.split(/\r?\n/).slice(1)) {
    if (!line.trim()) continue;
    const parts = line.trim().split(/\s+/);
    if (!/^\s/.test(line)) { owner = parts[0]; classes.set(owner, parts[1]); }
    else if (/^\t[^\t]/.test(line) && parts[1]?.startsWith('(')) methods.set(`${owner}\0${parts[0]}\0${parts[1]}`, parts[2]);
  }
  return { classes, methods };
}
const mappedDescriptor = (descriptor, mapping) => descriptor.replace(/L([^;]+);/g, (_, name) => `L${mapping.classes.get(name) ?? name};`);
function declaration(type, name, descriptor) {
  const found = type.methods.filter(method => method.name === name && method.descriptor === descriptor);
  need(found.length === 1, `ABI declaration missing/duplicate: ${type.name}.${name}${descriptor}`);
  need(!(found[0].access & (0x0002 | 0x0008 | 0x0400)), `ABI override is private/static/abstract: ${type.name}.${name}`);
  return found[0];
}

export async function verifyRuntimeAbi({ named, runtime, namedApi, runtimeApi, mappings }) {
  const findings = [], errors = [];
  const mappedName = name => mappings.classes.get(name) ?? name;
  for (const [owner, parent] of [[vehicle, sbwGeo], [sbwGeo, sbwVehicle], [sbwVehicle, 'net/minecraft/world/entity/Entity']]) {
    const a = await (owner === vehicle ? named : namedApi).get(owner);
    const b = await (owner === vehicle ? runtime : runtimeApi).get(mappedName(owner));
    need(a.superName === parent && b.superName === mappedName(parent), `Broken SBW superclass chain: ${owner}`);
  }
  for (const witness of ABI_WITNESSES) {
    try {
      const a = await named.get(witness.owner), b = await runtime.get(mappedName(witness.owner));
      need(b.superName === mappedName(a.superName), `Changed superclass: ${witness.owner}`);
      const descriptor = mappedDescriptor(witness.descriptor, mappings);
      if (witness.mappingOwner) need(mappings.methods.get(`${witness.mappingOwner}\0${witness.name}\0${witness.descriptor}`) === witness.runtime, `Changed mapping contract: ${witness.name}`);
      const source = declaration(a, witness.name, witness.descriptor), target = declaration(b, witness.runtime, descriptor);
      need(source.access === target.access, `Changed override access: ${witness.owner}.${witness.name}`);
      if (witness.name !== witness.runtime) need(!b.methods.some(x => x.name === witness.name && x.descriptor === descriptor), `Unrenamed override remains: ${witness.owner}.${witness.name}`);
      if (witness.parent) {
        declaration(await namedApi.get(witness.parent), witness.name, witness.descriptor);
        declaration(await runtimeApi.get(mappedName(witness.parent)), witness.runtime, descriptor);
      }
      if (witness.superCall) {
        const matching = (call, owner, name, desc) => call.opcode === 183 && call.owner === owner && call.name === name && call.descriptor === desc;
        need(source.calls.some(call => matching(call, a.superName, witness.name, witness.descriptor)), `Missing named super-call: ${witness.name}`);
        need(target.calls.some(call => matching(call, b.superName, witness.runtime, descriptor)), `Unmapped/missing runtime super-call: ${witness.name}`);
        if (witness.name !== witness.runtime) need(!target.calls.some(call => matching(call, b.superName, witness.name, descriptor)), `Named runtime super-call remains: ${witness.name}`);
      }
      findings.push(`${witness.owner}.${witness.runtime}${descriptor}`);
    } catch (error) { errors.push(error.message); }
  }
  need(!errors.length, `BVP runtime ABI failed (${errors.length}):\n${errors.join('\n')}`);
  return findings;
}

async function fileIdentity(file) {
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(file)) hash.update(bytes);
  return { path: path.resolve(file), size: (await fs.stat(file)).size, sha256: hash.digest('hex') };
}
async function checked(row) {
  need(row && Number.isSafeInteger(row.size) && /^[a-f0-9]{64}$/i.test(row.sha256), 'Invalid ABI dependency identity');
  const actual = await fileIdentity(row.path);
  need(actual.size === row.size && actual.sha256 === row.sha256.toLowerCase(), `ABI input hash mismatch: ${row.id}`);
  return actual;
}
export async function checkArtifacts({ namedFile, runtimeFile, inputsFile }) {
  const input = JSON.parse(await fs.readFile(inputsFile, 'utf8'));
  need(input.schema === 1 && input.kind === 'sbw-source-compiler-inputs', 'Invalid ABI bridge input');
  const inputs = [input.mappedApi, input.meshloader, input.runtime, input.mappings];
  for (const row of inputs) await checked(row);
  const artifacts = [await fileIdentity(namedFile), await fileIdentity(runtimeFile)];
  const opened = [];
  try {
    for (const file of [namedFile, runtimeFile, input.mappedApi.path, input.runtime.path]) opened.push(await openArchive(file));
    const [named, runtime, namedApi, runtimeApi] = opened;
    const findings = await verifyRuntimeAbi({ named, runtime, namedApi, runtimeApi, mappings: readMappings(await fs.readFile(input.mappings.path, 'utf8')) });
    for (const row of [...inputs, ...artifacts]) await checked(row);
    return { artifacts, findings };
  } finally { for (const archive of opened) await archive.close(); }
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  const options = new Map();
  for (let i = 2; i < process.argv.length; i += 2) {
    const key = process.argv[i], value = process.argv[i + 1];
    need(['--named', '--runtime', '--inputs'].includes(key) && !options.has(key) && value && !value.startsWith('--'), 'Usage: runtime-abi.mjs --named <jar> --runtime <jar> --inputs <inputs.json>');
    options.set(key, value);
  }
  need(options.size === 3, 'Missing runtime ABI inputs');
  const result = await checkArtifacts({ namedFile: options.get('--named'), runtimeFile: options.get('--runtime'), inputsFile: options.get('--inputs') });
  console.log(JSON.stringify(result, null, 2));
  console.log(`PASS BVP runtime ABI: ${result.findings.length} exact declarations, descriptors and required super-calls`);
}
