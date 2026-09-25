#!/usr/bin/env node

import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { rotateX, rotateY, rotateZ } from './bvp/vector_math.mjs';

const execFileAsync = promisify(execFile);

function usage() {
  console.error(
    'Usage: node tools/mtb_export/export_smp_mtb_obj.mjs --input <model.mtb> --output-dir <dir> [--name <base>] [--coordinate-space fmt|blender] [--force]'
  );
}

function option(args, name) {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : null;
}

function parseMtbNumber(value, field) {
  const parsed = Number.parseFloat(String(value).replace(',', '.').trim());
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid MTB number for ${field}: ${value}`);
  }
  return parsed;
}

function fmtNumber(value) {
  if (!Number.isFinite(value)) {
    throw new Error(`Refusing to write a non-finite OBJ value: ${value}`);
  }
  const rounded = Number(value.toFixed(9));
  return Object.is(rounded, -0) ? '0' : String(rounded);
}

function safeName(value) {
  return String(value).replace(/[^A-Za-z0-9_.-]+/g, '_');
}

function fmtUvDimension(value) {
  if (value % 1 === 0) {
    return value;
  }
  if (value < 1) {
    return 1;
  }
  return Math.trunc(value) + (value % 1 > 0.5 ? 1 : 0);
}

function rotateFmtLocal(point, fields) {
  // Exact SMP -> FMT convention:
  // import X/Y/-Z rotation, then export local geometry in X -> Z -> Y order.
  const rx = parseMtbNumber(fields[12], 'rotation X');
  const ry = parseMtbNumber(fields[13], 'rotation Y');
  const rz = -parseMtbNumber(fields[14], 'rotation Z');
  return rotateY(rotateZ(rotateX(point, rx), rz), ry);
}

function transformPoint(point, fields) {
  const rotated = rotateFmtLocal(point, fields);
  return [
    rotated[0] + parseMtbNumber(fields[6], 'position X'),
    rotated[1] + parseMtbNumber(fields[7], 'position Y'),
    rotated[2] + parseMtbNumber(fields[8], 'position Z'),
  ];
}

// Trapezoid (ModelRendererTurbo.addTrapezoid): one selected face grows by `amount` on its two in-plane axes.
// Same rule as tools/bvp/mtb_trapezoid.mjs (verified_native). SMP Toolbox also stores the element's shapebox corner
// offsets; when they disagree with the direction (seen on mirrored copies) the stored corners win, because they are
// what the toolbox displayed, and the element is counted in the report instead of being refused or dropped.
export const trapezoidConflicts = [];
const TRAPEZOID_FACES = Object.freeze({
  MR_RIGHT: [0, 0], MR_LEFT: [0, 1], MR_FRONT: [2, 0],
  MR_BACK: [2, 1], MR_TOP: [1, 0], MR_BOTTOM: [1, 1],
});
const CORNER_BITS = [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]];
const STORED_CORNER_ORDER = [0, 1, 5, 4, 3, 2, 6, 7];

function trapezoidLocalCorners(fields, off, dimensions) {
  const face = TRAPEZOID_FACES[fields[44]];
  if (!face) throw new Error(`Unknown Trapezoid direction ${fields[44]}`);
  const amount = parseMtbNumber(fields[45], 'Trapezoid amount');
  const [normalAxis, selectedSide] = face;
  return CORNER_BITS.map((bits, corner) => {
    const selected = bits[normalAxis] === selectedSide;
    return bits.map((bit, axis) => {
      const expansion = selected && axis !== normalAxis ? amount : 0;
      const stored = parseMtbNumber(fields[20 + axis * 8 + STORED_CORNER_ORDER[corner]], 'Trapezoid corner');
      if (Math.abs(stored - expansion) > 1e-7) throw new TrapezoidConflict();
      return off[axis] + bit * dimensions[axis] + (bit ? 1 : -1) * expansion;
    });
  });
}

class TrapezoidConflict extends Error {}

function makeCorners(fields) {
  const off = [
    parseMtbNumber(fields[15], 'offset X'),
    parseMtbNumber(fields[16], 'offset Y'),
    parseMtbNumber(fields[17], 'offset Z'),
  ];
  const width = parseMtbNumber(fields[9], 'width');
  const height = parseMtbNumber(fields[10], 'height');
  const depth = parseMtbNumber(fields[11], 'depth');

  let local;
  if (fields[5] === 'Trapezoid') {
    try {
      local = trapezoidLocalCorners(fields, off, [width, height, depth]);
    } catch (error) {
      if (!(error instanceof TrapezoidConflict)) throw error;
      trapezoidConflicts.push(`${fields[3]} (${fields[44]} ${fields[45]})`);
    }
  }
  if (local) {
    // Native Trapezoid taper, consistent with its stored corners.
  } else if (fields[5] === 'Shapebox' || fields[5] === 'Trapezoid') {
    const corner = (index) => [
      parseMtbNumber(fields[20 + index], `corner ${index} X`),
      parseMtbNumber(fields[28 + index], `corner ${index} Y`),
      parseMtbNumber(fields[36 + index], `corner ${index} Z`),
    ];
    const [c0, c1, c2, c3, c4, c5, c6, c7] = Array.from(
      { length: 8 },
      (_, index) => corner(index)
    );
    local = [
      [off[0] - c0[0], off[1] - c0[1], off[2] - c0[2]],
      [off[0] + width + c1[0], off[1] - c1[1], off[2] - c1[2]],
      [off[0] + width + c5[0], off[1] + height + c5[1], off[2] - c5[2]],
      [off[0] - c4[0], off[1] + height + c4[1], off[2] - c4[2]],
      [off[0] - c3[0], off[1] - c3[1], off[2] + depth + c3[2]],
      [off[0] + width + c2[0], off[1] - c2[1], off[2] + depth + c2[2]],
      [off[0] + width + c6[0], off[1] + height + c6[1], off[2] + depth + c6[2]],
      [off[0] - c7[0], off[1] + height + c7[1], off[2] + depth + c7[2]],
    ];
  } else if (fields[5] === 'Box') {
    const x1 = off[0] + width + (width === 0 ? 0.01 : 0);
    const y1 = off[1] + height + (height === 0 ? 0.01 : 0);
    const z1 = off[2] + depth + (depth === 0 ? 0.01 : 0);
    local = [
      [off[0], off[1], off[2]],
      [x1, off[1], off[2]],
      [x1, y1, off[2]],
      [off[0], y1, off[2]],
      [off[0], off[1], z1],
      [x1, off[1], z1],
      [x1, y1, z1],
      [off[0], y1, z1],
    ];
  } else {
    throw new Error(`Unsupported SMP/FMT element type: ${fields[5]}`);
  }
  return local.map((point) => transformPoint(point, fields));
}

function faceUvs(textureX, textureY, width, height, depth, textureWidth, textureHeight) {
  const uv = (x, y) => [x / textureWidth, 1 - y / textureHeight];
  const rect = (x, y, extentX, extentY) => [
    uv(textureX + x + extentX, textureY + y),
    uv(textureX + x, textureY + y),
    uv(textureX + x, textureY + y + extentY),
    uv(textureX + x + extentX, textureY + y + extentY),
  ];
  return [
    rect(depth + width, depth, depth, height),
    rect(0, depth, depth, height),
    rect(depth, 0, width, depth),
    rect(depth + width, 0, width, depth),
    rect(depth, depth, width, height),
    rect(depth + width + depth, depth, width, height),
  ];
}

function elementFaces(fields, textureWidth, textureHeight) {
  const corners = makeCorners(fields);
  const textureX = parseMtbNumber(fields[18], 'texture X');
  const textureY = parseMtbNumber(fields[19], 'texture Y');
  const width = fmtUvDimension(parseMtbNumber(fields[9], 'UV width'));
  const height = fmtUvDimension(parseMtbNumber(fields[10], 'UV height'));
  const depth = fmtUvDimension(parseMtbNumber(fields[11], 'UV depth'));
  const uvs = faceUvs(
    textureX,
    textureY,
    width,
    height,
    depth,
    textureWidth,
    textureHeight
  );
  const indices = [
    [5, 1, 2, 6],
    [0, 4, 7, 3],
    [5, 4, 0, 1],
    [2, 3, 7, 6],
    [1, 0, 3, 2],
    [4, 5, 6, 7],
  ];
  return indices.map((face, index) => ({
    positions: face.map((corner) => corners[corner]),
    uvs: uvs[index],
  }));
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex').toUpperCase();
}

function pointForCoordinateSpace(point, coordinateSpace) {
  if (coordinateSpace === 'fmt') {
    return point;
  }
  // Rigid right-handed root transform only. The SMP/FMT reconstruction itself
  // stays native; this maps +X forward/+Y down/+Z left to conventional OBJ
  // -Z forward/+Y up/+X right for Blender's default OBJ import axes.
  return [-point[2], -point[1], -point[0]];
}

async function assertWritableOutput(file, force) {
  try {
    await fs.access(file);
    if (!force) {
      throw new Error(`Output already exists (pass --force to replace it): ${file}`);
    }
  } catch (error) {
    if (error.code !== 'ENOENT') {
      throw error;
    }
  }
}

async function main() {
  const args = process.argv.slice(2);
  const input = option(args, '--input');
  const outputDir = option(args, '--output-dir');
  const force = args.includes('--force');
  const coordinateSpace = option(args, '--coordinate-space') ?? 'fmt';
  if (!input || !outputDir) {
    usage();
    process.exitCode = 2;
    return;
  }
  if (!['fmt', 'blender'].includes(coordinateSpace)) {
    throw new Error(`Unsupported --coordinate-space ${coordinateSpace}; expected fmt or blender`);
  }

  const resolvedInput = path.resolve(input);
  const resolvedOutputDir = path.resolve(outputDir);
  const baseName = safeName(
    option(args, '--name') ?? path.basename(resolvedInput, path.extname(resolvedInput))
  );
  const objPath = path.join(resolvedOutputDir, `${baseName}.obj`);
  const mtlPath = path.join(resolvedOutputDir, `${baseName}.mtl`);
  const texturePath = path.join(resolvedOutputDir, `${baseName}.png`);
  const reportPath = path.join(resolvedOutputDir, `${baseName}.conversion.json`);
  for (const output of [objPath, mtlPath, texturePath, reportPath]) {
    await assertWritableOutput(output, force);
  }

  const archiveBytes = await fs.readFile(resolvedInput);
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'smp-mtb-'));
  try {
    // MTBs are zip archives: Windows tar (bsdtar) reads them, GNU tar does not, so fall back to unzip.
    try {
      await execFileAsync('tar', ['-xf', resolvedInput, '-C', tempDir]);
    } catch (tarError) {
      try {
        await execFileAsync('unzip', ['-o', '-q', resolvedInput, '-d', tempDir]);
      } catch {
        throw tarError;
      }
    }
    const modelPath = path.join(tempDir, 'Model.txt');
    const embeddedTexturePath = path.join(tempDir, 'Model.png');
    const modelText = await fs.readFile(modelPath, 'utf8');
    const textureBytes = await fs.readFile(embeddedTexturePath);
    const widthMatch = modelText.match(/^TexSizeX\|(\d+)/m);
    const heightMatch = modelText.match(/^TexSizeY\|(\d+)/m);
    if (!widthMatch || !heightMatch) {
      throw new Error('MTB is missing TexSizeX/TexSizeY');
    }
    const textureWidth = Number(widthMatch[1]);
    const textureHeight = Number(heightMatch[1]);

    const elements = modelText
      .split(/\r?\n/)
      .filter((line) => line.startsWith('Element|'))
      .map((line, index) => {
        const fields = line.split('|');
        if (fields.length < 44) {
          throw new Error(`Element ${index} has only ${fields.length} fields`);
        }
        if (!['Box', 'Shapebox', 'Trapezoid'].includes(fields[5])) {
          throw new Error(`Element ${index} has unsupported type ${fields[5]}`);
        }
        if (fields[5] === 'Trapezoid' && fields.length < 46) {
          throw new Error(`Trapezoid element ${index} has only ${fields.length} fields`);
        }
        return { index, fields };
      });
    if (elements.length === 0) {
      throw new Error('MTB contains no supported elements');
    }

    const groups = new Map();
    for (const element of elements) {
      const groupId = element.fields[4];
      if (!groups.has(groupId)) {
        groups.set(groupId, []);
      }
      groups.get(groupId).push(element);
    }

    const obj = [
      '# FMT-Marker OBJ-3',
      '#',
      '# Reconstructed from SMP Toolbox V2 MTB with the FMT importer/exporter conventions.',
      `# Source: ${path.basename(resolvedInput)}`,
      `# Source SHA-256: ${sha256(archiveBytes)}`,
      `# TextureWidth: ${textureWidth}`,
      `# TextureHeight: ${textureHeight}`,
      `mtllib ${baseName}.mtl`,
      's off',
      '',
    ];
    let nextIndex = 1;
    let faceCount = 0;
    let vertexCount = 0;
    const orderedGroups = [...groups.entries()].sort((a, b) => Number(a[0]) - Number(b[0]));
    for (const [groupId, groupElements] of orderedGroups) {
      obj.push(`# Group Name: group${groupId}`);
      obj.push(`o group${safeName(groupId)}`);
      obj.push('usemtl fmt_material');
      const indices = new Map();
      for (const element of groupElements) {
        obj.push(`# ID: ${safeName(element.fields[3])}_${element.fields[2]}_${element.index}`);
        for (const face of elementFaces(element.fields, textureWidth, textureHeight)) {
          const faceIndices = [];
          for (let index = 0; index < face.positions.length; index += 1) {
            const position = pointForCoordinateSpace(face.positions[index], coordinateSpace);
            const uv = face.uvs[index];
            const key = [
              ...position.map(fmtNumber),
              ...uv.map(fmtNumber),
            ].join('|');
            let vertexIndex = indices.get(key);
            if (!vertexIndex) {
              vertexIndex = nextIndex;
              nextIndex += 1;
              vertexCount += 1;
              indices.set(key, vertexIndex);
              obj.push(`v ${position.map(fmtNumber).join(' ')}`);
              obj.push(`vt ${uv.map(fmtNumber).join(' ')}`);
            }
            faceIndices.push(`${vertexIndex}/${vertexIndex}`);
          }
          obj.push(`f ${faceIndices.join(' ')}`);
          faceCount += 1;
        }
      }
      obj.push('');
    }
    obj.push('# FMT-Marker OBJ-END', '');

    const mtl = [
      'newmtl fmt_material',
      'Ka 1.000000 1.000000 1.000000',
      'Kd 1.000000 1.000000 1.000000',
      'Ks 0.000000 0.000000 0.000000',
      'd 1.000000',
      `map_Kd ${baseName}.png`,
      `map_d ${baseName}.png`,
      '',
    ];

    await fs.mkdir(resolvedOutputDir, { recursive: true });
    await fs.writeFile(objPath, obj.join('\n'), 'utf8');
    await fs.writeFile(mtlPath, mtl.join('\n'), 'utf8');
    await fs.copyFile(embeddedTexturePath, texturePath);
    const report = {
      method: 'SMP Toolbox V2 MTB -> FMT-compatible OBJ (Box, Shapebox, native Trapezoid)',
      source: resolvedInput,
      sourceSha256: sha256(archiveBytes),
      embeddedTextureSha256: sha256(textureBytes),
      textureWidth,
      textureHeight,
      elements: elements.length,
      trapezoidStoredCornerFallbacks: trapezoidConflicts,
      elementTypes: Object.fromEntries([...elements.reduce((counts, element) =>
        counts.set(element.fields[5], (counts.get(element.fields[5]) ?? 0) + 1), new Map())]),
      groups: orderedGroups.map(([id, entries]) => ({ id, elements: entries.length })),
      vertices: vertexCount,
      textureCoordinates: vertexCount,
      quadFaces: faceCount,
      coordinateSpace: coordinateSpace === 'fmt'
        ? 'FMT native (+X forward, +Y down, +Z left)'
        : 'Blender-ready rigid root transform (-Z forward, +Y up, +X right)',
      uvPolicy: 'Flip U=false, Flip V=true',
      obj: objPath,
      mtl: mtlPath,
      texture: texturePath,
    };
    await fs.writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
    console.log(JSON.stringify(report, null, 2));
  } finally {
    await fs.rm(tempDir, { recursive: true, force: true });
  }
}

await main();
