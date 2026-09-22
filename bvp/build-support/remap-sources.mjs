import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const manifestName = 'source-map-proof.json';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function readMemberMap(text) {
  if (!text.startsWith('tsrg2 ')) throw new Error('Expected named-to-SRG TSRG2 mappings');
  const result = new Map();
  for (const line of text.split(/\r?\n/)) {
    if (!/^\t[^\t]/.test(line)) continue;
    const parts = line.trim().split(/\s+/);
    const runtime = parts.at(-1);
    if (!/^[mf]_\d+_$/.test(runtime)) continue;
    const named = parts[0];
    if (result.has(runtime) && result.get(runtime) !== named) throw new Error('Ambiguous mapping: ' + runtime);
    result.set(runtime, named);
  }
  if (!result.size) throw new Error('Empty member mapping');
  return result;
}

export function remapJava(text, mapping) {
  const tokens = /\/\/[^\r\n]*|\/\*[\s\S]*?\*\/|"""[\s\S]*?"""|"(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*'|[A-Za-z_$][\w$]*/g;
  let replacements = 0;
  const output = text.replace(tokens, token => {
    if (!/^[mf]_\d+_$/.test(token)) return token;
    if (!mapping.has(token)) throw new Error('Unknown SRG member ' + token);
    replacements++;
    return mapping.get(token);
  });
  return { output, replacements };
}

const canonicalKey = (name, insensitive = process.platform === 'win32') => {
  const normalized = name.replaceAll('\\', '/').replace(/\/+$/, '');
  return insensitive ? normalized.toLowerCase() : normalized;
};
export function rejectOverlappingPaths(source, output, insensitive = process.platform === 'win32') {
  const a = canonicalKey(source, insensitive), b = canonicalKey(output, insensitive);
  if (a === b || a.startsWith(b + '/') || b.startsWith(a + '/')) {
    throw new Error('Source/output overlap or case alias');
  }
}
export async function checkedPath(name) {
  const absolute = path.resolve(name);
  const root = path.parse(absolute).root;
  let current = root;
  let lastExisting = root;
  const missingParts = [];
  let missing = false;
  for (const part of absolute.slice(root.length).split(path.sep).filter(Boolean)) {
    current = path.join(current, part);
    if (!missing) {
      const stat = await fs.lstat(current).catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
      });
      if (!stat) missing = true;
      else {
        if (stat.isSymbolicLink()) throw new Error('Symlink/junction path refused: ' + current);
        if (stat.isFile() && stat.nlink > 1) throw new Error('Hard-link alias refused: ' + current);
        lastExisting = current;
      }
    }
    if (missing) missingParts.push(part);
  }
  // Canonicalize the relevant existing endpoint, not every unrelated parent directory.
  return path.join(await fs.realpath(lastExisting), ...missingParts);
}

async function inventory(root) {
  const files = [], directories = [];
  async function visit(relative) {
    for (const entry of await fs.readdir(path.join(root, relative), { withFileTypes: true })) {
      const name = path.join(relative, entry.name);
      const stat = await fs.lstat(path.join(root, name));
      if (stat.isSymbolicLink()) throw new Error('Symlink/junction refused: ' + name);
      if (stat.isFile() && stat.nlink > 1) throw new Error('Hard-link alias refused: ' + name);
      if (stat.isDirectory()) { directories.push(name.replaceAll('\\', '/')); await visit(name); }
      else if (stat.isFile()) files.push(name.replaceAll('\\', '/'));
      else throw new Error('Non-regular output/source entry: ' + name);
    }
  }
  await visit('');
  return { files: files.sort(), directories: directories.sort() };
}
export async function listFiles(root) { return (await inventory(root)).files; }

function safeRelative(name) {
  if (typeof name !== 'string' || !name || name.includes('\\') || name.includes(':')
      || name.startsWith('/') || name.split('/').some(x => !x || x === '.' || x === '..')
      || path.posix.normalize(name) !== name) throw new Error('Unsafe mapping manifest path');
  return name;
}
export async function inspectOutput(sourcePath, outputPath) {
  const source = await checkedPath(sourcePath), output = await checkedPath(outputPath);
  rejectOverlappingPaths(source, output);
  if (!(await fs.stat(source)).isDirectory()) throw new Error('Source is not a directory');
  const exists = await fs.lstat(output).catch(error => {
    if (error.code === 'ENOENT') return null;
    throw error;
  });
  if (!exists) return { source, output, previous: null };
  if (!exists.isDirectory()) throw new Error('Output is not a directory');
  const found = await inventory(output);
  if (found.files.length === 0 && found.directories.length === 0) return { source, output, previous: null };
  if (!found.files.includes(manifestName)) throw new Error('Nonempty unowned output refused');
  const previous = JSON.parse(await fs.readFile(path.join(output, manifestName), 'utf8'));
  if (previous.schema !== 1 || previous.kind !== 'bvp-srg-source-map'
      || canonicalKey(previous.sourceRoot ?? '') !== canonicalKey(source)
      || !Array.isArray(previous.files) || !Array.isArray(previous.directories)) {
    throw new Error('Invalid output ownership manifest');
  }
  const owned = new Map();
  for (const row of previous.files) {
    safeRelative(row.path);
    if (!row.path.endsWith('.java') || !/^[a-f0-9]{64}$/.test(row.mappedSha256)
        || owned.has(canonicalKey(row.path))) throw new Error('Invalid/duplicate owned file');
    owned.set(canonicalKey(row.path), row);
  }
  const dirs = new Set(previous.directories.map(x => canonicalKey(safeRelative(x))));
  for (const name of found.directories) if (!dirs.has(canonicalKey(name))) throw new Error('Unowned output directory: ' + name);
  for (const name of found.files) {
    if (name === manifestName) continue;
    const row = owned.get(canonicalKey(name));
    if (!row) throw new Error('Unowned output file: ' + name);
    if (digest(await fs.readFile(path.join(output, name))) !== row.mappedSha256) {
      throw new Error('Modified owned output refused: ' + name);
    }
  }
  return { source, output, previous };
}

export async function translateSources(sourcePath, outputPath, mapping, mappings = {}) {
  const state = await inspectOutput(sourcePath, outputPath);
  const generated = new Map(), proof = [];
  const directories = new Set(state.previous?.directories ?? []);
  let replacements = 0;
  // Validate and translate every source before any output write.
  for (const relative of await listFiles(state.source)) {
    if (!relative.endsWith('.java')) continue;
    safeRelative(relative);
    const bytes = await fs.readFile(path.join(state.source, relative));
    const result = remapJava(bytes.toString('utf8'), mapping);
    if (generated.has(canonicalKey(relative))) throw new Error('Case-aliased source file');
    generated.set(canonicalKey(relative), { relative, text: result.output });
    replacements += result.replacements;
    proof.push({ path: relative, sourceSha256: digest(bytes), mappedSha256: digest(result.output) });
    for (let dir = path.posix.dirname(relative); dir !== '.'; dir = path.posix.dirname(dir)) directories.add(dir);
  }
  if (!proof.length) throw new Error('No canonical Java sources');
  await fs.mkdir(state.output, { recursive: true });
  const previousFiles = new Map((state.previous?.files ?? []).map(row => [canonicalKey(row.path), row]));
  for (const { relative, text } of generated.values()) {
    const destination = path.join(state.output, relative);
    await checkedPath(destination);
    await fs.mkdir(path.dirname(destination), { recursive: true });
    const existing = await fs.readFile(destination).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    if (existing !== null) {
      const previous = previousFiles.get(canonicalKey(relative));
      if (!previous || digest(existing) !== previous.mappedSha256) throw new Error('Output changed before write: ' + relative);
    }
    await fs.writeFile(destination, text);
  }
  // Only previously recorded, still-byte-identical task-owned files may be removed.
  for (const row of state.previous?.files ?? []) {
    if (generated.has(canonicalKey(row.path))) continue;
    const stale = path.join(state.output, row.path);
    await checkedPath(stale);
    const bytes = await fs.readFile(stale).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    if (bytes === null) continue;
    if (digest(bytes) !== row.mappedSha256) throw new Error('Stale owned output changed before removal');
    await fs.unlink(stale);
  }
  const manifest = { schema: 1, kind: 'bvp-srg-source-map', sourceRoot: state.source,
    mappings, replacements, files: proof, directories: [...directories].sort() };
  await checkedPath(path.join(state.output, manifestName));
  await fs.writeFile(path.join(state.output, manifestName), JSON.stringify(manifest, null, 2) + '\n');
  return manifest;
}

async function main() {
  const args = process.argv.slice(2);
  const value = name => {
    const i = args.indexOf(name);
    if (i < 0 || !args[i + 1]) throw new Error('Missing ' + name);
    return args[i + 1];
  };
  const inputs = JSON.parse(await fs.readFile(value('--inputs'), 'utf8'));
  const mappingBytes = await fs.readFile(inputs.mappings.path);
  if (digest(mappingBytes) !== inputs.mappings.sha256) throw new Error('Mapping input hash changed');
  const result = await translateSources(value('--source'), value('--output'),
    readMemberMap(mappingBytes.toString('utf8')), inputs.mappings);
  console.log('PASS deterministic SRG source translation: ' + result.files.length + ' files, '
    + result.replacements + ' identifiers; canonical source unchanged');
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) await main();
