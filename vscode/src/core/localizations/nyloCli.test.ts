import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { parseFindings, resolveNyloCli } from './nyloCli';

describe('nyloCli.parseFindings', () => {
  it('parses the findings array', () => {
    const json = JSON.stringify({
      project: 'app',
      generated_at: 'now',
      count: 2,
      findings: [
        { file: 'lib/resources/pages/home.dart', line: 15, column: 10, value: 'Welcome', context: 'Text(...)' },
        { file: 'lib/resources/widgets/badge.dart', line: 8, column: 4, value: 'New', context: 'Text(...)' },
      ],
    });
    const findings = parseFindings(json);
    assert.strictEqual(findings.length, 2);
    assert.strictEqual(findings[0].file, 'lib/resources/pages/home.dart');
    assert.strictEqual(findings[0].line, 15);
    assert.strictEqual(findings[0].value, 'Welcome');
    assert.strictEqual(findings[0].context, 'Text(...)');
  });

  it('tolerates missing optional fields', () => {
    const findings = parseFindings('{"findings":[{"file":"a.dart"}]}');
    assert.strictEqual(findings.length, 1);
    assert.strictEqual(findings[0].line, 0);
    assert.strictEqual(findings[0].value, '');
  });

  it('empty, absent, or non-object input yields an empty list', () => {
    assert.strictEqual(parseFindings('{"findings":[]}').length, 0);
    assert.strictEqual(parseFindings('{"count":0}').length, 0);
    assert.strictEqual(parseFindings('').length, 0);
    assert.strictEqual(parseFindings('[1,2]').length, 0);
  });

  it('throws on malformed JSON so the caller reports a scan failure', () => {
    assert.throws(() => parseFindings('{ this is not json'));
  });
});

describe('nyloCli.resolveNyloCli', () => {
  let projectDir: string;

  beforeEach(() => {
    projectDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nylo-cli-test-'));
  });

  afterEach(() => {
    fs.rmSync(projectDir, { recursive: true, force: true });
  });

  it('uses dart run when pubspec depends on nylo_installer', () => {
    fs.writeFileSync(path.join(projectDir, 'pubspec.yaml'), 'dependencies:\n  nylo_installer: ^1.0.0\n');
    assert.deepStrictEqual(resolveNyloCli(projectDir), ['dart', 'run', 'nylo_installer:nylo']);
  });

  it('uses dart run when only the lockfile mentions nylo_installer', () => {
    fs.writeFileSync(path.join(projectDir, 'pubspec.yaml'), 'name: app\n');
    fs.writeFileSync(path.join(projectDir, 'pubspec.lock'), 'packages:\n  nylo_installer:\n    version: "1.0.0"\n');
    assert.deepStrictEqual(resolveNyloCli(projectDir), ['dart', 'run', 'nylo_installer:nylo']);
  });

  it('falls back to the global nylo CLI', () => {
    fs.writeFileSync(path.join(projectDir, 'pubspec.yaml'), 'name: app\ndependencies:\n  nylo_framework: ^7.0.0\n');
    assert.deepStrictEqual(resolveNyloCli(projectDir), ['nylo']);
  });
});
