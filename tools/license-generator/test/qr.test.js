'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const qr = require('../lib/qr');
const { qrToPng } = require('../lib/image');
const { issueLicense } = require('../lib/issue');
const { decodeGray, matrixToGray } = require('./helpers');

const roundTrip = (text) => {
  const code = qr.encode(text);
  const g = matrixToGray(code);
  return decodeGray(g.lum, g.width, g.height);
};

test('byte capacity per version matches the QR standard (level M)', () => {
  const expected = [14, 26, 42, 62, 84, 106, 122, 152, 180, 213, 251, 287, 331, 362, 412, 450, 504, 560, 624, 666];
  expected.forEach((cap, i) => assert.strictEqual(qr.byteCapacity(i + 1), cap, `version ${i + 1}`));
  assert.strictEqual(qr.byteCapacity(40), 2331);
});

test('ZXing decodes our QR for many sizes (all versions up to 40 boundaries)', () => {
  for (const len of [1, 14, 15, 26, 27, 42, 43, 62, 84, 122, 152, 180, 213, 214, 251, 287, 331, 412, 500, 666, 667, 900, 1500, 2331]) {
    const text = crypto.randomBytes(len).toString('base64url').slice(0, len);
    assert.strictEqual(roundTrip(text), text, `len ${len}`);
  }
});

test('UTF-8 text round-trips', () => assert.strictEqual(roundTrip('NOVA ✓ تفعيل'), 'NOVA ✓ تفعيل'));

test('a real license code → QR → ZXing → the identical license code', () => {
  const { privateKey } = crypto.generateKeyPairSync('ed25519');
  const lic = issueLicense(privateKey);
  assert.strictEqual(roundTrip(lic.licenseCode), lic.licenseCode);
});

test('PNG output is a valid image that decodes (via ImageMagick → ZXing)', (t) => {
  if (spawnSync('convert', ['-version']).status !== 0) return t.skip('ImageMagick not installed');
  const { privateKey } = crypto.generateKeyPairSync('ed25519');
  const lic = issueLicense(privateKey);
  const png = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'qr-')), 'q.png');
  fs.writeFileSync(png, qrToPng(qr.encode(lic.licenseCode), 4));
  const head = execFileSync('convert', [png, '-format', '%w %h %[colorspace]', 'info:']).toString().trim();
  const [w, h] = head.split(' ').map(Number);
  const gray = execFileSync('convert', [png, '-depth', '8', 'gray:-'], { maxBuffer: 1 << 26 });
  assert.strictEqual(gray.length, w * h);
  assert.strictEqual(decodeGray(gray, w, h), lic.licenseCode);
});
