import * as assert from 'assert';
import { TWO_SESSIONS } from './logParser.fixtures';
import { applyFilter } from './logFilter';
import { isError, isNetwork } from './logCategory';
import { parseLog } from './logParser';

describe('logFilter', () => {
  const doc = parseLog(TWO_SESSIONS);
  const tags = (sessions: { tag: string }[]) => sessions.map((s) => s.tag);

  it('filters by short session tag', () => {
    const result = applyFilter(doc, { sessionTag: '9qbhab' });
    assert.strictEqual(result.sessions.length, 1);
    assert.strictEqual(result.sessions[0].tag, '9qbhab');
  });

  it('filters by full session id', () => {
    const result = applyFilter(doc, { sessionTag: '2026-06-17T11-52-55-4lfu9t' });
    assert.strictEqual(result.sessions[0].tag, '4lfu9t');
  });

  it('text filter keeps only matching sessions and entries', () => {
    const result = applyFilter(doc, { text: 'user' });
    assert.strictEqual(result.sessions.length, 1);
    const session = result.sessions[0];
    assert.strictEqual(session.tag, '9qbhab');
    assert.ok(session.entries.length > 0);
    assert.ok(session.entries.every((e) => e.raw.toLowerCase().includes('user')));
  });

  it('newest-first orders sessions by started descending', () => {
    assert.deepStrictEqual(tags(applyFilter(doc, { sort: 'newest' }).sessions), ['9qbhab', '4lfu9t']);
  });

  it('oldest-first orders sessions by started ascending', () => {
    assert.deepStrictEqual(tags(applyFilter(doc, { sort: 'oldest' }).sessions), ['4lfu9t', '9qbhab']);
  });

  it('networking tab keeps only network entries and drops network-free sessions', () => {
    const result = applyFilter(doc, { category: 'networking' });
    assert.deepStrictEqual(tags(result.sessions), ['9qbhab']);
    assert.ok(result.sessions.flatMap((s) => s.entries).every((e) => isNetwork(e)));
  });

  it('console tab excludes network but keeps everything else', () => {
    const result = applyFilter(doc, { category: 'console' });
    assert.deepStrictEqual(tags(result.sessions).sort(), ['4lfu9t', '9qbhab']);
    assert.ok(result.sessions.flatMap((s) => s.entries).every((e) => !isNetwork(e)));
    assert.strictEqual(result.sessions.find((s) => s.tag === '9qbhab')!.entries.length, 1);
  });

  it('errors tab keeps error lines and network errors across sessions', () => {
    const result = applyFilter(doc, { category: 'errors' });
    assert.deepStrictEqual(tags(result.sessions).sort(), ['4lfu9t', '9qbhab']);
    const entries = result.sessions.flatMap((s) => s.entries);
    assert.ok(entries.length > 0);
    assert.ok(entries.every((e) => isError(e)));
  });

  it('all tab is identity', () => {
    const result = applyFilter(doc, { category: 'all' });
    assert.deepStrictEqual(new Set(tags(result.sessions)), new Set(tags(doc.sessions)));
    const count = (d: typeof doc) => d.sessions.reduce((sum, s) => sum + s.entries.length, 0);
    assert.strictEqual(count(result), count(doc));
  });

  it('category and text intersect', () => {
    const result = applyFilter(doc, { text: 'PUT', category: 'networking' });
    const entries = result.sessions[0].entries;
    assert.strictEqual(entries.length, 1);
    assert.ok(entries.every((e) => isError(e)));
    assert.ok(!entries.some((e) => e.raw.includes('[GET]')));
  });
});
