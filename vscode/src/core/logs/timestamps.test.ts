import * as assert from 'assert';
import { displayTimestamp, parseTimestampMs } from './timestamps';

describe('timestamps', () => {
  it('parses iso timestamps to epoch millis', () => {
    assert.strictEqual(parseTimestampMs('2026-06-17T11:52:55'), Date.UTC(2026, 5, 17, 11, 52, 55));
    assert.strictEqual(parseTimestampMs('2026-06-17T11:52:55.100'), Date.UTC(2026, 5, 17, 11, 52, 55, 100));
  });

  it('parses the legacy space-separated form', () => {
    assert.strictEqual(parseTimestampMs('2026-06-26 10:00:00'), Date.UTC(2026, 5, 26, 10, 0, 0));
    assert.strictEqual(displayTimestamp('2026-06-26 10:00:00'), '2026-06-26 10:00:00');
  });

  it('keeps microsecond precision in the sort key', () => {
    const a = parseTimestampMs('2026-06-26T14:17:24.223296')!;
    const b = parseTimestampMs('2026-06-26T14:17:24.223297')!;
    assert.ok(a < b, 'microsecond fractions must stay distinguishable');
    const base = Date.UTC(2026, 5, 26, 14, 17, 24);
    assert.ok(a > base + 223 && a < base + 224);
    // Display still drops the fraction entirely.
    assert.strictEqual(displayTimestamp('2026-06-26T14:17:24.223296'), '2026-06-26 14:17:24');
  });

  it('ignores trailing zones and offsets, including negative ones', () => {
    const expected = Date.UTC(2026, 5, 26, 10, 32, 59);
    assert.strictEqual(parseTimestampMs('2026-06-26T10:32:59Z'), expected);
    assert.strictEqual(parseTimestampMs('2026-06-26T10:32:59+02:00'), expected);
    assert.strictEqual(parseTimestampMs('2026-06-26T10:32:59-05:00'), expected);
  });

  it('falls back to the raw string when unparseable', () => {
    assert.strictEqual(parseTimestampMs('yesterday-ish'), null);
    assert.strictEqual(displayTimestamp('yesterday-ish'), 'yesterday-ish');
    assert.strictEqual(displayTimestamp(null), '');
  });
});
