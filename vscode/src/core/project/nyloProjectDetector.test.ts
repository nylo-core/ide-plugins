import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { isNyloProjectDir } from './nyloProjectDetector';

describe('nyloProjectDetector', () => {
  let projectDir: string;

  beforeEach(() => {
    projectDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nylo-detector-test-'));
  });

  afterEach(() => {
    fs.rmSync(projectDir, { recursive: true, force: true });
  });

  it('false when pubspec missing', () => {
    assert.strictEqual(isNyloProjectDir(projectDir), false);
  });

  it('false when pubspec lacks nylo_framework', () => {
    fs.writeFileSync(
      path.join(projectDir, 'pubspec.yaml'),
      ['name: foo', 'dependencies:', '  flutter:', '    sdk: flutter', '  http: ^1.0.0'].join('\n'),
    );
    assert.strictEqual(isNyloProjectDir(projectDir), false);
  });

  it('true when pubspec lists nylo_framework as direct dependency', () => {
    fs.writeFileSync(
      path.join(projectDir, 'pubspec.yaml'),
      ['name: foo', 'dependencies:', '  flutter:', '    sdk: flutter', '  nylo_framework: ^7.0.0'].join('\n'),
    );
    assert.strictEqual(isNyloProjectDir(projectDir), true);
  });

  it('true with leading whitespace and a git ref block', () => {
    fs.writeFileSync(
      path.join(projectDir, 'pubspec.yaml'),
      ['dependencies:', '  nylo_framework:', '    git:', '      url: https://github.com/nylo-core/nylo', '      ref: main'].join('\n'),
    );
    assert.strictEqual(isNyloProjectDir(projectDir), true);
  });

  it('false when nylo_framework appears only inside a comment', () => {
    fs.writeFileSync(
      path.join(projectDir, 'pubspec.yaml'),
      ['# we use to depend on nylo_framework: but moved off it', 'name: foo', 'dependencies:', '  flutter:', '    sdk: flutter'].join('\n'),
    );
    assert.strictEqual(isNyloProjectDir(projectDir), false);
  });
});
