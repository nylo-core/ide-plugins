import * as assert from 'assert';
import { metroMakeEnvArgs, metroToolName } from './metroNaming';

describe('metroNaming', () => {
  it('tool name follows the existing manual pattern', () => {
    assert.strictEqual(metroToolName('Dev'), 'Metro make:env Dev');
    assert.strictEqual(metroToolName('Prod Staging'), 'Metro make:env Prod Staging');
    assert.strictEqual(metroToolName('Default'), 'Metro make:env Default');
  });

  it('builds the dart make:env args for a given env file', () => {
    assert.deepStrictEqual(metroMakeEnvArgs('.env.prod'), [
      'run',
      'nylo_framework:main',
      'make:env',
      '--file=.env.prod',
    ]);
  });
});
