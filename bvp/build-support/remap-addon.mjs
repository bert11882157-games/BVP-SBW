import fs from 'node:fs/promises';
import path from 'node:path';

// Keep inheritance inputs in the input JAR's namespace and bind them to exact build hashes.
export async function remapWithVerifiedLibraries({ id, file, direction, libraries, verify, manifest, execute }) {
  if (!['runtime', 'named'].includes(direction)) throw new Error('Unknown addon mapping direction');
  if (!Array.isArray(libraries) || !libraries.length) throw new Error('Missing addon inheritance libraries');
  const expectedSbw = direction === 'runtime' ? 'sbw-mapped-api' : 'sbw';
  if (libraries[0]?.id !== expectedSbw) throw new Error('Wrong SBW inheritance namespace');
  if (id === 'bvp-runtime-map' && libraries[1]?.id !== 'sbwmeshloader-mapped') {
    throw new Error('BVP runtime remap requires the mapped mesh dependency');
  }
  const verified = [];
  for (const row of libraries) verified.push(await verify(row));
  if (verified.some(row => !path.isAbsolute(row.path) || row.path.includes(path.delimiter))) {
    throw new Error('Unsupported inheritance library path');
  }
  await fs.writeFile(manifest, JSON.stringify({ schema: 1, direction, libraries: verified }, null, 2) + '\n');
  const check = await execute(['remapSourceAddon', `-PsourceAddonJar=${file}`,
    `-PsourceAddonDirection=${direction}`, `-PsourceAddonLibraries=${verified.map(row => row.path).join(path.delimiter)}`,
    `-PsourceAddonLibraryManifest=${manifest}`]);
  for (const row of verified) await verify(row);
  return check;
}
