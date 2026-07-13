import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { isEnvFileName, scanEnvFiles } from './envFileScanner';

describe('envFileScanner', () => {
  let projectDir: string;

  beforeEach(() => {
    projectDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nylo-scanner-test-'));
  });

  afterEach(() => {
    fs.rmSync(projectDir, { recursive: true, force: true });
  });

  const write = (name: string, body = 'X=1') => fs.writeFileSync(path.join(projectDir, name), body);

  it('returns empty when project dir does not exist', () => {
    assert.deepStrictEqual(scanEnvFiles(path.join(projectDir, 'does-not-exist')), []);
  });

  it('returns empty when no env files present', () => {
    write('pubspec.yaml', 'name: foo\n');
    write('.gitignore', 'build/\n');
    assert.deepStrictEqual(scanEnvFiles(projectDir), []);
  });

  it('excludes .env-example', () => {
    write('.env-example', 'FOO=BAR\n');
    write('.env', 'FOO=BAZ\n');
    const results = scanEnvFiles(projectDir);
    assert.strictEqual(results.length, 1);
    assert.strictEqual(results[0].fileName, '.env');
    assert.strictEqual(results[0].displayName, 'Default');
  });

  it('picks up dotted suffixes', () => {
    write('.env');
    write('.env.dev');
    write('.env.prod');
    write('.env.prod.staging');
    write('.env.valet');
    write('.env-example');

    const displayNames = new Set(scanEnvFiles(projectDir).map((e) => e.displayName));
    assert.deepStrictEqual(displayNames, new Set(['Default', 'Dev', 'Prod', 'Prod Staging', 'Valet']));
  });

  it('results are sorted by display name', () => {
    write('.env.valet');
    write('.env.dev');
    write('.env');
    write('.env.prod');
    assert.deepStrictEqual(
      scanEnvFiles(projectDir).map((e) => e.displayName),
      ['Default', 'Dev', 'Prod', 'Valet'],
    );
  });

  it('isEnvFileName accepts env files and rejects everything else', () => {
    assert.ok(isEnvFileName('.env'));
    assert.ok(isEnvFileName('.env.dev'));
    assert.ok(isEnvFileName('.env.prod.staging'));

    assert.ok(!isEnvFileName('.env-example'));
    assert.ok(!isEnvFileName('.envrc'));
    assert.ok(!isEnvFileName('env.dev'));
    assert.ok(!isEnvFileName('random.txt'));
  });

  it('ignores non-env files even if they share a prefix', () => {
    write('.env.dev');
    write('.envrc', 'export FOO=1\n');
    write('env.dev');

    const results = scanEnvFiles(projectDir);
    assert.strictEqual(results.length, 1);
    assert.strictEqual(results[0].fileName, '.env.dev');
  });
});
