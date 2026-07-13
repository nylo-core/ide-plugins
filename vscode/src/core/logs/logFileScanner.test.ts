import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { defaultLogDate, scanLogDir } from './logFileScanner';

describe('logFileScanner', () => {
  let dir: string;

  beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nylo-logs-test-'));
  });

  afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('returns empty when dir does not exist', () => {
    assert.deepStrictEqual(scanLogDir(path.join(dir, 'missing')), []);
  });

  it('discovers log files newest-first and ignores others', () => {
    for (const name of ['2026-06-17.log', '2026-06-19.log', '2026-06-18.log', 'readme.md', '2026-99-99.log']) {
      fs.writeFileSync(path.join(dir, name), 'x');
    }
    assert.deepStrictEqual(
      scanLogDir(dir).map((r) => r.date),
      ['2026-06-19', '2026-06-18', '2026-06-17'],
    );
  });

  it('defaultLogDate prefers today when present', () => {
    assert.strictEqual(defaultLogDate(['2026-06-19', '2026-06-17'], '2026-06-17'), '2026-06-17');
  });

  it('defaultLogDate falls back to newest when today is missing', () => {
    assert.strictEqual(defaultLogDate(['2026-06-19', '2026-06-17'], '2026-06-20'), '2026-06-19');
  });

  it('defaultLogDate is null when there are no logs', () => {
    assert.strictEqual(defaultLogDate([], '2026-06-20'), null);
  });
});
