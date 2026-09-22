import test from 'node:test';
import assert from 'node:assert/strict';
import { parseOptions } from './options.mjs';

test('explicit dependency input and supported cache options', () => {
  const result = parseOptions(['--tree', '.', '--meshloader-manifest', 'dependency.json', '--offline']);
  assert.equal(result.get('--meshloader-manifest'), 'dependency.json');
  assert.equal(result.get('--offline'), true);
  assert.equal(parseOptions(['--meshloader-source', 'source']).get('--meshloader-source'), 'source');
});
test('reject missing, conflicting, duplicate and unknown options before a build', () => {
  for (const args of [[], ['--meshloader-source'], ['--meshloader-manifest', '--offline'],
    ['--meshloader-source', 'src', '--meshloader-manifest', 'input'],
    ['--meshloader-source', 'src', '--offline', '--offline'], ['--guess', 'file']]) {
    assert.throws(() => parseOptions(args));
  }
});
