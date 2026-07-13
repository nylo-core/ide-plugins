import * as assert from 'assert';
import { compareLocales } from './localeComparator';
import { LocaleIssue, LocaleSummary } from './model';

function m(entries: Record<string, string>): Map<string, string> {
  return new Map(Object.entries(entries));
}
const base = () => m({ a: 'A', b: 'B', c: 'C' });
const summaryFor = (summaries: LocaleSummary[], locale: string) => summaries.find((s) => s.locale === locale)!;
const issuesFor = (issues: LocaleIssue[], locale: string) => issues.filter((i) => i.locale === locale);

describe('localeComparator', () => {
  it('classifies missing empty extra and translated', () => {
    const values = new Map([
      ['en', base()],
      ['es', m({ a: 'Ä', b: '  ', d: 'D' })],
    ]);
    const report = compareLocales('en', values);
    const es = summaryFor(report.summaries, 'es');
    assert.strictEqual(es.translated, 1);
    assert.strictEqual(es.missing, 1);
    assert.strictEqual(es.empty, 1);
    assert.strictEqual(es.extra, 1);

    const byStatus = (status: string) => issuesFor(report.issues, 'es').filter((i) => i.status === status);
    assert.strictEqual(byStatus('missing')[0].key, 'c');
    assert.strictEqual(byStatus('empty')[0].key, 'b');
    assert.strictEqual(byStatus('extra')[0].key, 'd');
  });

  it('same as base flagged only when enabled and still counts as translated', () => {
    const values = new Map([
      ['en', m({ a: 'A' })],
      ['es', m({ a: 'A' })],
    ]);
    assert.strictEqual(compareLocales('en', values, new Map(), false).issues.length, 0);

    const flagged = compareLocales('en', values, new Map(), true);
    assert.strictEqual(flagged.issues[0].status, 'same_as_base');
    assert.strictEqual(summaryFor(flagged.summaries, 'es').percentComplete, 100);
  });

  it('percent complete reflects translated over baseline keys', () => {
    const values = new Map([
      ['en', base()],
      ['es', m({ a: 'A' })],
    ]);
    const es = summaryFor(compareLocales('en', values).summaries, 'es');
    assert.strictEqual(es.percentComplete, 33);
    assert.strictEqual(es.missing, 2);
  });

  it('zero key locale is all missing', () => {
    const values = new Map([
      ['en', base()],
      ['es', m({})],
    ]);
    const es = summaryFor(compareLocales('en', values).summaries, 'es');
    assert.strictEqual(es.percentComplete, 0);
    assert.strictEqual(es.missing, 3);
  });

  it('empty baseline avoids division error and reports extras only', () => {
    const values = new Map([
      ['en', m({})],
      ['es', m({ a: 'A' })],
    ]);
    const es = summaryFor(compareLocales('en', values).summaries, 'es');
    assert.strictEqual(es.percentComplete, 100);
    assert.strictEqual(es.extra, 1);
  });

  it('baseline switch changes results', () => {
    const values = new Map([
      ['en', m({ a: 'A' })],
      ['es', m({ a: 'A', b: 'B' })],
    ]);
    assert.strictEqual(compareLocales('en', values).issues.filter((i) => i.status === 'extra').length, 1);
    assert.strictEqual(compareLocales('es', values).issues.filter((i) => i.status === 'missing').length, 1);
  });

  it('parse error produces an error summary and no phantom issues', () => {
    const report = compareLocales('en', new Map([['en', base()]]), new Map([['es', 'bad json']]));
    const es = summaryFor(report.summaries, 'es');
    assert.strictEqual(es.parseError, 'bad json');
    assert.strictEqual(es.missing, 0);
    assert.strictEqual(issuesFor(report.issues, 'es').length, 0);
  });

  it('issues follow baseline key order', () => {
    const values = new Map([
      ['en', m({ z: 'Z', y: 'Y', x: 'X' })],
      ['es', m({})],
    ]);
    assert.deepStrictEqual(
      issuesFor(compareLocales('en', values).issues, 'es').map((i) => i.key),
      ['z', 'y', 'x'],
    );
  });

  it('baseline summary comes first and is marked', () => {
    const report = compareLocales('en', new Map([['en', base()], ['es', base()]]));
    assert.ok(report.summaries[0].isBaseline);
    assert.strictEqual(report.summaries[0].locale, 'en');
  });

  it('broken baseline produces no issues instead of flagging every key extra', () => {
    // An unparseable baseline must not degrade to an empty map: that would misclassify every key of
    // every locale as EXTRA (and "Remove extra keys" would then ask to delete them all).
    const report = compareLocales(
      'en',
      new Map([['es', m({ a: 'A', b: 'B' })]]),
      new Map([['en', 'Invalid JSON']]),
    );
    assert.strictEqual(report.issues.length, 0);
    assert.strictEqual(summaryFor(report.summaries, 'en').parseError, 'Invalid JSON');
    assert.strictEqual(summaryFor(report.summaries, 'es').extra, 0);
  });

  it('absent baseline produces no issues', () => {
    const report = compareLocales('en', new Map([['es', m({ a: 'A' })]]));
    assert.strictEqual(report.issues.length, 0);
  });
});
