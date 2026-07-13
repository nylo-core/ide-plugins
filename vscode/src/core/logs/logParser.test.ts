import * as assert from 'assert';
import { TWO_SESSIONS } from './logParser.fixtures';
import { parseLog } from './logParser';
import { NetworkEntry, RawLine, StandardLine } from './logModel';

describe('logParser', () => {
  it('parses session fields, log lines and console lines', () => {
    const doc = parseLog(TWO_SESSIONS);
    assert.strictEqual(doc.sessions.length, 2);

    const s0 = doc.sessions[0];
    assert.strictEqual(s0.tag, '4lfu9t');
    assert.strictEqual(s0.fullId, '2026-06-17T11-52-55-4lfu9t');
    assert.strictEqual(s0.started, Date.UTC(2026, 5, 17, 11, 52, 55));
    assert.strictEqual(s0.platform, 'ios Version 26.5 (Build 23F77)');
    assert.strictEqual(s0.app, 'Pretalk 1.0.0');
    assert.strictEqual(s0.version, 'nylo_framework v7.1.24');
    assert.strictEqual(s0.env, 'developing');
    assert.ok(s0.bannerLines.some((l) => l.includes('SESSION  2026-06-17T11-52-55-4lfu9t')));

    const stds = s0.entries.filter((e): e is StandardLine => e.entryType === 'standard');
    assert.strictEqual(stds.length, 3);
    assert.strictEqual(stds[0].level, 'debug');
    assert.strictEqual(stds[0].sessionTag, '4lfu9t');
    assert.strictEqual(stds[0].message, '[AppProvider] setup in 217ms');
    assert.strictEqual(stds[1].level, 'error');
    assert.strictEqual(stds[1].message, 'boom');
    assert.ok(stds[1].stack!.includes('#0      foo'));
    assert.ok(stds[1].raw.includes('\n#0      foo'));
    assert.strictEqual(stds[2].level, null);
    assert.strictEqual(stds[2].message, 'flutter: console output');
  });

  it('parses network records with kind and structured fields', () => {
    const s1 = parseLog(TWO_SESSIONS).sessions[1];
    assert.strictEqual(s1.tag, '9qbhab');

    const nets = s1.entries.filter((e): e is NetworkEntry => e.entryType === 'network');
    assert.strictEqual(nets.length, 3);
    assert.strictEqual(nets[0].netKind, 'request');
    assert.strictEqual(nets[0].method, 'GET');
    assert.strictEqual(nets[0].requestId, '096232f7');
    assert.ok(nets[0].summary.includes('GET'));
    assert.strictEqual(nets[1].netKind, 'response');
    assert.strictEqual(nets[1].statusCode, 200);
    assert.strictEqual(nets[2].netKind, 'error');
    assert.strictEqual(nets[2].statusCode, 500);
    assert.ok(nets[1].raw.includes('Ann'));
  });

  it('log record without session or level', () => {
    const doc = parseLog('{"t":"log","ts":"2026-06-17T11:52:55","msg":"hello world"}');
    const line = doc.preamble.filter((e): e is StandardLine => e.entryType === 'standard')[0];
    assert.strictEqual(line.sessionTag, null);
    assert.strictEqual(line.level, null);
    assert.strictEqual(line.message, 'hello world');
    assert.strictEqual(line.timestamp, Date.UTC(2026, 5, 17, 11, 52, 55));
  });

  it('records before the first session are preamble', () => {
    const doc = parseLog('{"t":"console","msg":"boot starting"}\n' + TWO_SESSIONS);
    assert.strictEqual(doc.sessions.length, 2);
    assert.ok(doc.preamble.some((e) => e.raw.includes('boot starting')));
  });

  it('non-json and unknown records become raw lines', () => {
    const doc = parseLog('not json at all\n' + '{"t":"mystery","msg":"x"}');
    const raws = doc.preamble.filter((e): e is RawLine => e.entryType === 'raw');
    assert.ok(raws.some((r) => r.raw === 'not json at all'));
    assert.ok(raws.some((r) => r.raw.includes('mystery')));
  });

  it('carriage returns are normalized out', () => {
    const one = '{"t":"console","msg":"line one"}';
    const two = '{"t":"console","msg":"line two"}';
    const doc = parseLog(one + '\r\n' + two + '\r\n');
    assert.deepStrictEqual(
      doc.preamble.filter((e): e is StandardLine => e.entryType === 'standard').map((e) => e.message),
      ['line one', 'line two'],
    );
    assert.ok(doc.preamble.every((e) => !e.raw.includes('\r')));
  });

  it('json-escaped carriage returns inside fields never reach the raw text', () => {
    // A \r inside a JSON string survives whole-file normalization (it exists in the file only
    // as the two-char escape), so the field accessor must normalize it out of `raw`.
    const record =
      '{"t":"log","ts":"2026-06-26 10:00:00","session":"s1","level":"error",' +
      '"msg":"line1\\r\\nline2","stack":"#0 a\\r#1 b"}';
    const line = parseLog(record).preamble[0] as StandardLine;
    assert.ok(!line.raw.includes('\r'));
    assert.ok(line.raw.includes('line1\nline2'));
    assert.ok(line.stack!.includes('#0 a\n#1 b'));
  });

  it('parses iso timestamps with negative utc offsets', () => {
    const doc = parseLog('{"t":"log","ts":"2026-06-26T10:32:59-05:00","msg":"hi"}');
    const line = doc.preamble[0] as StandardLine;
    assert.strictEqual(line.timestamp, Date.UTC(2026, 5, 26, 10, 32, 59));
  });
});
