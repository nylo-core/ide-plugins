import * as assert from 'assert';
import { envDisplayName } from './envFileNaming';

describe('envFileNaming', () => {
  it('null suffix becomes Default', () => {
    assert.strictEqual(envDisplayName(null), 'Default');
  });

  it('empty/blank suffix becomes Default', () => {
    assert.strictEqual(envDisplayName(''), 'Default');
    assert.strictEqual(envDisplayName('   '), 'Default');
  });

  it('single segment is title-cased', () => {
    assert.strictEqual(envDisplayName('dev'), 'Dev');
    assert.strictEqual(envDisplayName('prod'), 'Prod');
    assert.strictEqual(envDisplayName('valet'), 'Valet');
  });

  it('dotted segments are split, title-cased and space joined', () => {
    assert.strictEqual(envDisplayName('prod.staging'), 'Prod Staging');
    assert.strictEqual(envDisplayName('dev.local.mac'), 'Dev Local Mac');
  });

  it('existing capitalisation is preserved on non-leading characters', () => {
    assert.strictEqual(envDisplayName('prodQA'), 'ProdQA');
  });

  it('consecutive dots produce no empty segments', () => {
    assert.strictEqual(envDisplayName('dev..local'), 'Dev Local');
  });
});
