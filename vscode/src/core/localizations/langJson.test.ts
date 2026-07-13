import * as assert from 'assert';
import { parseFlattened, withValue } from './langJson';

const BOM = '﻿';

describe('langJson.parseFlattened', () => {
  it('flattens nested objects to dot notation', () => {
    const map = parseFlattened('{"common":{"ok":"OK","nested":{"deep":"D"}},"buttons":{"login":"Login"}}');
    assert.strictEqual(map.get('common.ok'), 'OK');
    assert.strictEqual(map.get('common.nested.deep'), 'D');
    assert.strictEqual(map.get('buttons.login'), 'Login');
    assert.strictEqual(map.size, 3);
  });

  it('preserves insertion order', () => {
    const map = parseFlattened('{"b":{"z":"1","a":"2"},"a":"3"}');
    assert.deepStrictEqual([...map.keys()], ['b.z', 'b.a', 'a']);
  });

  it('reads raw utf8 characters', () => {
    const map = parseFlattened('{"a":"café","b":"💔"}');
    assert.strictEqual(map.get('a'), 'café');
    assert.strictEqual(map.get('b'), '💔');
  });

  it('decodes json unicode escapes', () => {
    assert.strictEqual(parseFlattened('{"a":"\\u0041\\u00e9"}').get('a'), 'Aé');
  });

  it('keeps escaped quotes verbatim', () => {
    assert.strictEqual(parseFlattened('{"a":"say \\"hi\\""}').get('a'), 'say "hi"');
  });

  it('keeps placeholder tokens verbatim', () => {
    const map = parseFlattened('{"a":"{{xp}} XP","b":"{{terms:terms and conditions}}"}');
    assert.strictEqual(map.get('a'), '{{xp}} XP');
    assert.strictEqual(map.get('b'), '{{terms:terms and conditions}}');
  });

  it('stringifies arrays and maps json null to empty', () => {
    const map = parseFlattened('{"arr":[1,2,"x"],"nul":null,"num":5,"bool":true}');
    assert.strictEqual(map.get('arr'), '[1,2,"x"]');
    assert.strictEqual(map.get('nul'), '');
    assert.strictEqual(map.get('num'), '5');
    assert.strictEqual(map.get('bool'), 'true');
  });

  it('strips a leading BOM', () => {
    assert.strictEqual(parseFlattened(BOM + '{"a":"1"}').get('a'), '1');
  });

  it('duplicate keys keep the last value', () => {
    const map = parseFlattened('{"a":"1","a":"2"}');
    assert.strictEqual(map.get('a'), '2');
    assert.strictEqual(map.size, 1);
  });

  it('throws on malformed json', () => {
    assert.throws(() => parseFlattened('{'));
  });

  it('throws when top level is not an object', () => {
    assert.throws(() => parseFlattened('[1,2,3]'));
  });
});

describe('langJson.withValue', () => {
  it('updates an existing key', () => {
    const updated = withValue('{"common":{"ok":"OK"}}', 'common.ok', 'Okay');
    assert.strictEqual(parseFlattened(updated).get('common.ok'), 'Okay');
  });

  it('inserts a missing nested key', () => {
    const updated = withValue('{"common":{"ok":"OK"}}', 'common.deep.new', 'Hi');
    const map = parseFlattened(updated);
    assert.strictEqual(map.get('common.deep.new'), 'Hi');
    assert.strictEqual(map.get('common.ok'), 'OK');
  });

  it('preserves a single trailing newline', () => {
    const updated = withValue('{}\n', 'a', '1');
    assert.ok(updated.endsWith('\n'));
    assert.ok(!updated.replace(/\n$/, '').endsWith('\n'));
  });

  it('omits a trailing newline when the source has none', () => {
    assert.ok(!withValue('{"a":"x"}', 'a', 'y').endsWith('\n'));
  });

  it('keeps placeholders and emoji unescaped', () => {
    assert.ok(withValue('{"a":"x"}', 'b', '{{xp}} 💔').includes('{{xp}} 💔'));
  });

  it('writes apostrophes and html chars literally', () => {
    const apos = withValue('{"a":"x"}', 'score', "Score're");
    assert.ok(apos.includes("Score're"));
    assert.ok(!apos.includes('\\u0027')); // literal backslash-u-0027 must be absent

    const html = withValue('{"a":"x"}', 'b', 'a < b & c > d = e');
    assert.ok(html.includes('a < b & c > d = e'));
  });

  it('updates a flat dotted key in place instead of nesting a duplicate', () => {
    const updated = withValue('{"login.email":"E-mail"}', 'login.email', 'Email');
    const map = parseFlattened(updated);
    assert.strictEqual(map.get('login.email'), 'Email');
    assert.strictEqual(map.size, 1);
    assert.ok(updated.includes('"login.email"')); // still stored flat, not exploded to nested
  });

  it('updates through an object name that itself contains a dot', () => {
    const updated = withValue('{"a.b":{"c":"old"}}', 'a.b.c', 'new');
    const map = parseFlattened(updated);
    assert.strictEqual(map.get('a.b.c'), 'new');
    assert.strictEqual(map.size, 1);
  });

  it('never replaces a scalar intermediate - the new key is written flat', () => {
    const updated = withValue('{"login":"Anmelden"}', 'login.email', 'E-Mail');
    const map = parseFlattened(updated);
    assert.strictEqual(map.get('login'), 'Anmelden'); // the existing translation survives
    assert.strictEqual(map.get('login.email'), 'E-Mail');
  });

  it('refuses to overwrite a nested object with a string', () => {
    assert.throws(() => withValue('{"login":{"email":"x"}}', 'login', 'y'));
  });
});
