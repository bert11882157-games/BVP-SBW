export function parseOptions(args) {
  const valued = new Set(['--tree', '--output', '--gradle-home', '--meshloader-source',
    '--meshloader-manifest', '--compiler-extras-manifest']);
  const flags = new Set(['--offline']);
  const result = new Map();
  for (let i = 0; i < args.length; i++) {
    const key = args[i];
    if ((!valued.has(key) && !flags.has(key)) || result.has(key)) throw new Error(`Unknown or duplicate option: ${key}`);
    if (flags.has(key)) result.set(key, true);
    else {
      const value = args[++i];
      if (!value || value.startsWith('--')) throw new Error(`Missing value for ${key}`);
      result.set(key, value);
    }
  }
  if (result.has('--meshloader-source') === result.has('--meshloader-manifest')) {
    throw new Error('Specify exactly one --meshloader-manifest or --meshloader-source');
  }
  return result;
}
