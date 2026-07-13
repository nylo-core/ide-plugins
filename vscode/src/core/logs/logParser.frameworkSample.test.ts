import * as assert from 'assert';
import { isError, isNetwork } from './logCategory';
import { FRAMEWORK_SAMPLE } from './logParser.fixtures';
import { parseLog } from './logParser';
import { NetworkEntry, StandardLine } from './logModel';

/**
 * Contract guard: parses the *exact* NDJSON lines emitted by `nylo_support`'s `NyFileLogger`
 * (captured from its Phase A test run) so a future change on either side that breaks the shared
 * schema fails here. Verifies microsecond timestamps and superset network fields are tolerated.
 * Mirror of the Kotlin `LogParserFrameworkSampleTest` — keep the two in sync.
 */
describe('logParser framework sample', () => {
  it('parses the real framework NDJSON output', () => {
    const doc = parseLog(FRAMEWORK_SAMPLE);
    assert.strictEqual(doc.sessions.length, 1);
    const s = doc.sessions[0];

    assert.strictEqual(s.tag, 'idrxkz');
    assert.strictEqual(s.fullId, '2026-06-26T14-17-24-idrxkz');
    assert.strictEqual(s.version, 'nylo_framework v7.1.24');
    // Microsecond fraction (.223296) survives into the sort key; display drops it.
    const base = Date.UTC(2026, 5, 26, 14, 17, 24);
    assert.ok(s.started! > base + 223 && s.started! < base + 224);
    assert.strictEqual(s.startedRaw, '2026-06-26 14:17:24');

    const stds = s.entries.filter((e): e is StandardLine => e.entryType === 'standard');
    assert.strictEqual(stds.length, 3); // debug, error(+context+stack), console
    const errorLine = stds.filter((e) => e.level === 'error')[0];
    assert.ok(errorLine.raw.includes('Failed to load user'));
    assert.ok(errorLine.raw.includes('"retry":true')); // context rendered inline
    assert.ok(errorLine.raw.includes('\n#0 main')); // stack appended
    assert.strictEqual(stds.filter((e) => e.message.includes('raw console line'))[0].level, null);

    const nets = s.entries.filter((e): e is NetworkEntry => e.entryType === 'network');
    assert.strictEqual(nets.length, 3);
    assert.strictEqual(nets[0].netKind, 'request');
    assert.strictEqual(nets[0].requestId, '236f0833');
    assert.ok(nets[0].summary.includes('[PUT]'));
    assert.ok(nets[0].raw.includes('Ada')); // body folded in; empty headers{} skipped
    assert.ok(!nets[0].raw.includes('headers'));
    assert.strictEqual(nets[1].statusCode, 200);
    assert.ok(nets[1].raw.includes('"name": "Ada"')); // response body pretty-printed
    assert.strictEqual(nets[2].netKind, 'error');
    assert.strictEqual(nets[2].statusCode, 500);

    // Categorization: the error log line and the net error both land in ERRORS.
    assert.ok(isError(errorLine));
    assert.ok(isError(nets[2]));
    assert.ok(isNetwork(nets[0]));
  });
});
