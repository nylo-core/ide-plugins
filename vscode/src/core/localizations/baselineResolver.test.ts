import * as assert from 'assert';
import { readEnvDefaultLocale, resolveBaseline } from './baselineResolver';

describe('baselineResolver', () => {
  const locales = ['de', 'en', 'es'];

  it('override wins when valid', () => {
    assert.strictEqual(resolveBaseline(locales, 'es', 'de'), 'es');
  });

  it('ignores invalid override and uses env default', () => {
    assert.strictEqual(resolveBaseline(locales, 'zz', 'de'), 'de');
  });

  it('falls back to en when no override or env default', () => {
    assert.strictEqual(resolveBaseline(locales, null, null), 'en');
  });

  it('falls back to the first locale when there is no en', () => {
    assert.strictEqual(resolveBaseline(['de', 'fr'], null, null), 'de');
  });

  it('returns null when there are no locales', () => {
    assert.strictEqual(resolveBaseline([], 'en', 'en'), null);
  });

  it('reads DEFAULT_LOCALE from env text', () => {
    assert.strictEqual(readEnvDefaultLocale('APP_NAME=foo\nDEFAULT_LOCALE="fr"\n'), 'fr');
    assert.strictEqual(readEnvDefaultLocale('DEFAULT_LOCALE=ja'), 'ja');
    assert.strictEqual(readEnvDefaultLocale('APP_NAME=foo\n'), null);
  });
});
