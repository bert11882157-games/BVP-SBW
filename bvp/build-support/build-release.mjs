import fs from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { listFiles } from './remap-sources.mjs';
import { parseOptions } from './options.mjs';
import { remapWithVerifiedLibraries } from './remap-addon.mjs';

const support = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const options = parseOptions(args);
const option = (name, fallback) => options.get(name) ?? fallback;
const tree = path.resolve(option('--tree', path.join(support, '../..')));
const bvp = path.join(tree, 'bvp');
const sbw = path.join(tree, 'sbw');
const output = path.resolve(option('--output', path.join(bvp, 'build/source-build')));
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const environment = { ...process.env };
if (option('--gradle-home')) environment.GRADLE_USER_HOME = path.resolve(option('--gradle-home'));
const startedAt = new Date().toISOString();
await fs.mkdir(output, { recursive: true });

export async function identity(file, id, version = 'resolved') {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return { id, version, path: path.resolve(file), size: (await fs.stat(file)).size, sha256: hash.digest('hex') };
}
async function verify(row, base = output) {
  if (!row || !/^[0-9a-f]{64}$/i.test(row.sha256) || !Number.isSafeInteger(row.size)) throw new Error('Invalid dependency identity');
  const actual = await identity(path.resolve(base, row.path), row.id, row.version);
  if (actual.size !== row.size || actual.sha256.toLowerCase() !== row.sha256.toLowerCase()) throw new Error(`Input hash mismatch: ${row.id}`);
  return actual;
}
async function json(file, object) { await fs.writeFile(file, JSON.stringify(object, null, 2) + '\n'); }
async function run(id, command, commandArgs, cwd) {
  const log = path.join(output, `${id}.log`);
  const stream = createWriteStream(log);
  stream.write(`${new Date().toISOString()}\n${JSON.stringify({ command, args: commandArgs, cwd })}\n`);
  const status = await new Promise((resolve, reject) => {
    const child = spawn(command, commandArgs, { cwd, env: environment, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    child.stdout.on('data', data => { stream.write(data); process.stdout.write(data); });
    child.stderr.on('data', data => { stream.write(data); process.stderr.write(data); });
    child.on('error', reject);
    child.on('close', resolve);
  }).catch(async error => { stream.end(String(error)); throw error; });
  stream.write(`\nEXIT ${status}\n`);
  await new Promise(resolve => stream.end(resolve));
  if (status !== 0) throw new Error(`${id} failed (${status}); see ${log}`);
  return { id, status: 'passed', log: await identity(log, id) };
}
const gradle = async (id, project, taskArgs) => run(id, java, [
  '-classpath', path.join(sbw, 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain',
  '-p', project, '--no-daemon', '--console=plain', '--stacktrace', '--build-cache', '-Pkotlin.compiler.execution.strategy=in-process',
  ...(args.includes('--offline') ? ['--offline'] : []), ...taskArgs
], tree);
const bridgeScript = path.join(support, 'sbw-bridge.gradle');
const checks = [];
let snapshot;
try {
  snapshot = JSON.parse(await fs.readFile(path.join(tree, 'SOURCE_SNAPSHOT.json'), 'utf8'));
  if (!snapshot.treeSha256 || !Array.isArray(snapshot.files)) throw new Error('Invalid SOURCE_SNAPSHOT.json');
  // Checkpoint verification is explicit here, not inferred from timestamps or filenames.
  for (const row of snapshot.files) await verify({ ...row, id: row.path }, tree);
  const source = option('--meshloader-source');
  const externalManifest = option('--meshloader-manifest');
  let approvedMesh;
  if (externalManifest) {
    const file = path.resolve(externalManifest);
    const metadata = JSON.parse(await fs.readFile(file, 'utf8'));
    if (metadata.schema !== 1 || metadata.id !== 'sbwmeshloader' || metadata.version !== '0.1.1') {
      throw new Error('Unsupported meshloader dependency');
    }
    approvedMesh = await verify(metadata, path.dirname(file));
  } else {
    const root = path.resolve(source);
    for (const required of ['gradle.properties', 'src/main/java', 'src/main/resources']) {
      await fs.stat(path.join(root, required));
    }
  }
  checks.push(await run('tool-versions', java, ['-version'], tree));
  checks.push(await run('source-mapping-tests', process.execPath, ['--test',
    path.join(support, 'remap-sources.test.mjs'), path.join(support, 'options.test.mjs'),
    path.join(support, 'remap-addon.test.mjs'), path.join(support, 'runtime-abi.test.mjs')], tree));
  checks.push(await gradle('sbw-build', sbw, ['-I', bridgeScript, 'writeBvpBuildInputs']));
  const bridge = JSON.parse(await fs.readFile(path.join(sbw, 'build/bvp-bridge/inputs.json'), 'utf8'));
  const runtime = await verify(bridge.runtime);
  const mappedApi = await verify(bridge.mappedApi);
  const mappings = await verify(bridge.mappings);
  const reverseMappings = await verify(bridge.reverseMappings);
  const remappingTool = await verify(bridge.remapper);
  const publicDependencies = [];
  for (const row of bridge.classpath) publicDependencies.push(await verify(row));
  for (const row of bridge.buildTools) publicDependencies.push(await verify(row));
  const remap = async (id, file, direction, libraries) => {
    checks.push(await remapWithVerifiedLibraries({ id, file, direction, libraries, verify,
      manifest: path.join(output, `${id}-libraries.json`),
      execute: taskArgs => gradle(id, sbw, ['-I', bridgeScript, ...taskArgs]) }));
  };

  let mesh;
  let mappedMesh = path.join(output, 'meshloader-mapped.jar');
  if (source) {
    // Private dependency bootstrap only. These sources are never part of the production export.
    const root = path.resolve(source);
    const sourceRows = [];
    for (const name of await listFiles(path.join(root, 'src/main'))) {
      sourceRows.push(await identity(path.join(root, 'src/main', name), name));
    }
    sourceRows.push(await identity(path.join(root, 'gradle.properties'), 'gradle.properties'));
    const dependencyBuild = path.join(output, 'dependency-source');
    await fs.mkdir(dependencyBuild, { recursive: true });
    await fs.copyFile(path.join(support, 'dependency.gradle'), path.join(dependencyBuild, 'build.gradle'));
    await fs.writeFile(path.join(dependencyBuild, 'settings.gradle'), "rootProject.name = 'meshloader-dependency'\n");
    checks.push(await gradle('meshloader-source-build', dependencyBuild, [
      'jar', `-PdependencySourceRoot=${root}`, `-PsbwBuildInputs=${path.join(sbw, 'build/bvp-bridge/inputs.json')}`
    ]));
    for (const row of sourceRows) await verify(row);
    const jars = (await fs.readdir(path.join(dependencyBuild, 'build/libs'))).filter(x => x.endsWith('-mapped.jar'));
    if (jars.length !== 1) throw new Error('Expected one source-built meshloader JAR');
    await fs.copyFile(path.join(dependencyBuild, 'build/libs', jars[0]), mappedMesh);
    const meshRuntime = path.join(output, jars[0].replace('-mapped.jar', '.jar'));
    await fs.copyFile(mappedMesh, meshRuntime);
    await remap('meshloader-runtime-map', meshRuntime, 'runtime', [mappedApi]);
    const version = jars[0].slice('sbwmeshloader-'.length, -'-mapped.jar'.length);
    mesh = await identity(meshRuntime, 'sbwmeshloader', version);
    await json(path.join(output, 'meshloader-dependency.json'), { schema: 1, ...mesh, sourceBuild: { sources: sourceRows, check: checks.find(x => x.id === 'meshloader-source-build') } });
  } else {
    mesh = await verify(approvedMesh);
    await fs.copyFile(mesh.path, mappedMesh);
    await remap('meshloader-compiler-map', mappedMesh, 'named', [runtime]);
  }
  bridge.meshloader = await identity(mappedMesh, 'sbwmeshloader-mapped', mesh.version);
  const inputPath = path.join(output, 'inputs.json');
  await json(inputPath, bridge);
  checks.push(await gradle('bvp-build', bvp, ['jar', `-PsbwBuildInputs=${inputPath}`, `-PnodeExecutable=${process.execPath}`]));
  const jars = (await fs.readdir(path.join(bvp, 'build/libs'))).filter(x => x.endsWith('-mapped.jar'));
  if (jars.length !== 1) throw new Error('Expected exactly one BVP mapped candidate');
  const candidate = path.join(output, jars[0].replace('-mapped.jar', '.jar'));
  await fs.copyFile(path.join(bvp, 'build/libs', jars[0]), candidate);
  await remap('bvp-runtime-map', candidate, 'runtime', [mappedApi, bridge.meshloader]);
  checks.push(await run('bvp-runtime-abi', process.execPath, [path.join(support, 'runtime-abi.mjs'),
    '--named', path.join(bvp, 'build/libs', jars[0]), '--runtime', candidate,
    '--inputs', inputPath], tree));
  checks.push(await gradle('bvp-static-resources', bvp, ['verifyStaticResources',
    `-PstaticJar=${candidate}`, `-PsbwBuildInputs=${inputPath}`, `-PnodeExecutable=${process.execPath}`]));
  const version = jars[0].slice('berts_vehicle_pack-'.length, -'-mapped.jar'.length);
  for (const row of snapshot.files) await verify({ ...row, id: row.path }, tree);
  const sbwCandidate = path.join(output, path.basename(runtime.path));
  await fs.copyFile(runtime.path, sbwCandidate);
  const proof = {
    schema: 1, kind: 'bvp-sbw-source-build', sourceTree: { path: tree, sha256: snapshot.treeSha256, fileCount: snapshot.files.length },
    startedAt, completedAt: new Date().toISOString(),
    artifacts: [await identity(candidate, 'bvp', version), await identity(sbwCandidate, 'sbw', runtime.version)],
    dependencies: [...new Map([...publicDependencies, mappedApi, bridge.meshloader, mappings, reverseMappings, remappingTool, mesh]
      .map(row => [row.id + ':' + row.version + ':' + row.sha256, row])).values()]
      .map(row => ({ ...row, id: row.id + ':' + row.version + ':' + row.sha256.slice(0, 12) })), checks
  };
  await json(path.join(output, 'BUILD_PROOF.json'), proof);
  console.log(`DONE source-built development candidates; proof ${path.join(output, 'BUILD_PROOF.json')}`);
} catch (error) {
  await json(path.join(output, 'BUILD_BLOCKER.json'), { schema: 1, startedAt, stoppedAt: new Date().toISOString(), message: error.message, checks });
  throw error;
}
