import * as assert from 'assert';
import * as path from 'path';
import { captureTargetPath, markerValue, resolveShotIndex } from './markers';

describe('screenshots/markers', () => {
  const ready = '__NYLO_SHOT_READY__ route=/home locale=en slug=home index=3';

  describe('markerValue', () => {
    it('reads a value that runs to the next whitespace', () => {
      assert.strictEqual(markerValue(ready, 'locale'), 'en');
      assert.strictEqual(markerValue(ready, 'slug'), 'home');
      assert.strictEqual(markerValue(ready, 'index'), '3');
    });

    it('returns null when the key is absent', () => {
      assert.strictEqual(markerValue(ready, 'reason'), null);
    });

    it('respects the word boundary and does not match a key suffix', () => {
      assert.strictEqual(markerValue('foo xindex=5', 'index'), null);
    });

    it('reads the count from a BEGIN marker', () => {
      assert.strictEqual(markerValue('__NYLO_SHOTS_BEGIN__ count=12', 'count'), '12');
    });
  });

  describe('resolveShotIndex', () => {
    it('uses the parsed index when present', () => {
      assert.strictEqual(resolveShotIndex('3', 7), 3);
    });

    it('falls back when the index is missing or non-numeric', () => {
      assert.strictEqual(resolveShotIndex(null, 7), 7);
      assert.strictEqual(resolveShotIndex('', 7), 7);
      assert.strictEqual(resolveShotIndex('abc', 7), 7);
    });
  });

  describe('captureTargetPath', () => {
    it('builds <outDir>/<deviceSlug>/<locale>/NN-slug.png with a zero-padded index', () => {
      assert.strictEqual(
        captureTargetPath('/out', 'iphone-15', 'en', 'home', 3),
        path.join('/out', 'iphone-15', 'en', '03-home.png'),
      );
    });

    it('does not truncate an index of three or more digits', () => {
      assert.strictEqual(
        captureTargetPath('/out', 'pixel-7', 'fr', 'profile', 100),
        path.join('/out', 'pixel-7', 'fr', '100-profile.png'),
      );
    });
  });
});
