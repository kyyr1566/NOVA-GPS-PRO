'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const P = require('../lib/protocol');
const { issueLicense } = require('../lib/issue');
const { generateKeyPair } = require('../lib/keys');

const GEN = path.join(__dirname, '..', 'generate.js');
const kp = () => crypto.generateKeyPairSync('ed25519');
const run = (args, env = {}) => spawnSync(process.execPath, [GEN, ...args], { encoding: 'utf8', env: { ...process.env, ...env } });

test('issued license: required claims, unguessable id, valid signature', () => {
  const { publicKey, privateKey } = kp();
  const lic = issueLicense(privateKey, { minVersion: 3, now: () => 1_700_000_000 });
  const parsed = P.parseLicense(lic.licenseCode);
  assert.ok(parsed);
  assert.match(lic.licenseCode, /^NOVA1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  assert.ok(lic.licenseCode.length > 250, 'long code');
  const c = parsed.claims;
  assert.strictEqual(c.product, 'NOVA_GPS_PRO');
  assert.strictEqual(c.licenseType, 'LIFETIME');
  assert.strictEqual(c.deviceBinding, 'FIRST_ACTIVATION');
  assert.strictEqual(c.issuedAt, 1_700_000_000);
  assert.strictEqual(c.minVersion, 3);
  assert.match(c.licenseId, /^NL-[A-Z2-7]{26}$/);
  assert.match(c.nonce, /^[0-9a-f]{32}$/);
  assert.ok(P.verifyLicenseSignature(parsed, publicKey));
  assert.deepStrictEqual(lic.record, { licenseId: c.licenseId, licenseType: 'LIFETIME', createdAt: 1_700_000_000 });
});

test('ids and nonces never repeat', () => {
  const { privateKey } = kp();
  const ids = new Set(Array.from({ length: 500 }, () => issueLicense(privateKey).licenseId));
  assert.strictEqual(ids.size, 500);
});

test('tampered payload / signature / other key all fail verification', () => {
  const { publicKey, privateKey } = kp();
  const { licenseCode } = issueLicense(privateKey);
  const [pre, pl, sig] = licenseCode.split('.');
  const ok = (code) => { const p = P.parseLicense(code); return !!p && P.verifyLicenseSignature(p, publicKey); };
  assert.ok(ok(licenseCode));
  const payload = Buffer.from(pl, 'base64url').toString().replace('LIFETIME', 'LIFETIMX');
  assert.ok(!ok(`${pre}.${Buffer.from(payload).toString('base64url')}.${sig}`), 'payload');
  const s = Buffer.from(sig, 'base64url'); s[5] ^= 1;
  assert.ok(!ok(`${pre}.${pl}.${s.toString('base64url')}`), 'signature');
  assert.ok(!P.verifyLicenseSignature(P.parseLicense(licenseCode), kp().publicKey), 'other key');
  // a receipt-domain signature can't be replayed as a license signature
  const receipt = P.signReceipt(privateKey, { licenseId: 'NL-AAAAAAAAAAAAAAAAAAAAAAAAAA', deviceHash: 'a'.repeat(64), firstActivatedAt: 1 });
  assert.strictEqual(P.parseLicense(receipt), null);
});

test('keygen refuses to write inside a Git repository and writes 0600 outside', () => {
  const fakeRepo = fs.mkdtempSync(path.join(os.tmpdir(), 'repo-'));
  fs.mkdirSync(path.join(fakeRepo, '.git'));
  assert.throws(() => generateKeyPair(path.join(fakeRepo, 'keys'), 'k'), /Git repository/);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'keys-'));
  const r = generateKeyPair(dir, 'k');
  assert.strictEqual(fs.statSync(r.file).mode & 0o777, 0o600);
  assert.strictEqual(Buffer.from(r.publicKeyBase64, 'base64').length, 32);
  assert.throws(() => generateKeyPair(dir, 'k'), /already exists/);
});

test('CLI end-to-end: keygen → issue → verify; private key never printed; refuses without a key', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gen-'));
  const kg = run(['keygen', '--out-dir', path.join(dir, 'keys')]);
  assert.strictEqual(kg.status, 0, kg.stderr);
  assert.ok(!/PRIVATE KEY/.test(kg.stdout + kg.stderr));
  const pub = kg.stdout.trim().split('\n').pop();
  const keyFile = path.join(dir, 'keys', 'nova-license-private.pem');

  const out = path.join(dir, 'out');
  const issue = run(['issue', '--count', '2', '--out', out, '--key-file', keyFile]);
  assert.strictEqual(issue.status, 0, issue.stderr);
  assert.ok(!/PRIVATE KEY/.test(issue.stdout + issue.stderr));
  const files = fs.readdirSync(out);
  assert.strictEqual(files.filter((f) => f.endsWith('.png')).length, 2);
  const txt = files.find((f) => f.endsWith('.license.txt'));
  const v = run(['verify', path.join(out, txt), '--public-key', pub]);
  assert.strictEqual(v.status, 0, v.stderr);
  assert.match(v.stdout, /signature: VALID/);
  const bad = run(['verify', path.join(out, txt), '--public-key', kp().publicKey.export({ type: 'spki', format: 'der' }).subarray(-32).toString('base64')]);
  assert.notStrictEqual(bad.status, 0);

  const nokey = run(['issue', '--out', out], { LICENSE_PRIVATE_KEY_FILE: '', LICENSE_PRIVATE_KEY_PEM: '' });
  assert.notStrictEqual(nokey.status, 0);
  assert.match(nokey.stderr, /no private key configured/);
});

test('source tree contains no private key material', () => {
  const root = path.join(__dirname, '..');
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) =>
    e.name === 'node_modules' ? [] : e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
  for (const f of walk(root)) assert.ok(!/BEGIN (ED25519 |EC |RSA )?PRIVATE KEY/.test(fs.readFileSync(f, 'utf8')) || f.endsWith('generator.test.js'), f);
});
