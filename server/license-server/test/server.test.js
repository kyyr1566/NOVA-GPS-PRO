'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const P = require('../lib/protocol');
const { LicenseStore } = require('../lib/store');
const { createServer } = require('../server');
// the real issuer, so server and generator are proven wire-compatible
const { issueLicense } = require('../../../tools/license-generator/lib/issue');

const dev = () => crypto.randomBytes(32).toString('hex');   // stands for SHA-256(product + ANDROID_ID + constant)
const pair = () => crypto.generateKeyPairSync('ed25519');

async function setup(opts = {}) {
  const issuer = pair(), activation = pair();
  const store = new LicenseStore(':memory:');
  let t = 1_800_000_000;
  const server = createServer({
    store, licensePublicKey: issuer.publicKey, activationPrivateKey: activation.privateKey,
    now: () => t++, log: () => {}, ...opts,
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = async (body, headers = { 'Content-Type': 'application/json' }) => {
    const res = await fetch(`${base}/v1/activate`, { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) });
    return { http: res.status, body: await res.json() };
  };
  const issue = (register = true) => {
    const lic = issueLicense(issuer.privateKey, { now: () => 1_799_000_000 });
    if (register) store.register(lic.record);
    return lic;
  };
  const req = (lic, deviceHash) => ({ product: 'NOVA_GPS_PRO', licenseCode: lic.licenseCode, deviceHash, appVersion: 1 });
  return { issuer, activation, store, server, post, issue, req, close: () => new Promise((r) => { server.close(r); server.closeAllConnections(); }) };
}

const receiptClaims = (activationPub, receipt) => {
  const p = P.parseSigned(P.RECEIPT_PREFIX, receipt);
  assert.ok(p, 'receipt parses');
  assert.ok(P.verifyWith(activationPub, P.RECEIPT_DOMAIN, p.payload, p.signature), 'receipt signature valid');
  return Object.fromEntries(p.fields);
};

test('1. valid license + new device → ACTIVATED with a signed receipt; device gets bound', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(), d = dev();
  const r = await s.post(s.req(lic, d));
  assert.strictEqual(r.http, 200);
  assert.strictEqual(r.body.status, 'ACTIVATED');
  const c = receiptClaims(s.activation.publicKey, r.body.receipt);
  assert.strictEqual(c.product, 'NOVA_GPS_PRO');
  assert.strictEqual(c.licenseId, lic.licenseId);
  assert.strictEqual(c.deviceHash, d);
  const row = s.store.get(lic.licenseId);
  assert.strictEqual(row.status, 'ACTIVATED');
  assert.strictEqual(row.deviceHash, d);
  assert.strictEqual(row.firstActivatedAt, Number(c.firstActivatedAt));
  assert.strictEqual(row.lastActivationAt, row.firstActivatedAt);
});

test('4. same device again (reinstall / data cleared) → allowed, firstActivatedAt kept, lastActivationAt moves', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(), d = dev();
  const a = await s.post(s.req(lic, d));
  const b = await s.post(s.req(lic, d));
  assert.strictEqual(b.http, 200);
  assert.strictEqual(receiptClaims(s.activation.publicKey, b.body.receipt).firstActivatedAt,
                     receiptClaims(s.activation.publicKey, a.body.receipt).firstActivatedAt);
  const row = s.store.get(lic.licenseId);
  assert.ok(row.lastActivationAt > row.firstActivatedAt);
});

test('5. same license on another device → 409 LICENSE_ALREADY_BOUND; binding never changes', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(), x = dev(), y = dev();
  assert.strictEqual((await s.post(s.req(lic, x))).http, 200);
  const r = await s.post(s.req(lic, y));
  assert.deepStrictEqual(r, { http: 409, body: { status: 'LICENSE_ALREADY_BOUND' } });   // no details leaked, no receipt
  assert.strictEqual(s.store.get(lic.licenseId).deviceHash, x);
  assert.strictEqual((await s.post(s.req(lic, x))).http, 200, 'original device still works');
  assert.strictEqual((await s.post(s.req(lic, y))).http, 409, 'and the other one stays refused');
});

test('concurrent first activations from several devices: exactly one wins', async (t) => {
  const s = await setup({ rateLimitPerMinute: 100000 }); t.after(s.close);
  for (let i = 0; i < 20; i++) {
    const lic = s.issue();
    const rs = await Promise.all([s.post(s.req(lic, dev())), s.post(s.req(lic, dev())), s.post(s.req(lic, dev()))]);
    assert.deepStrictEqual(rs.map((r) => r.http).sort(), [200, 409, 409]);
  }
});

test('6/7/8. modified license, modified payload, modified signature, wrong signer → 400 INVALID_LICENSE', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(), d = dev();
  const [pre, pl, sig] = lic.licenseCode.split('.');
  const flip = (b64) => { const b = Buffer.from(b64, 'base64url'); b[3] ^= 1; return b.toString('base64url'); };
  const stranger = issueLicense(pair().privateKey);
  s.store.register(stranger.record); // even if registered, a foreign signature must not pass
  const evil = Buffer.from(pl, 'base64url').toString().replace('NOVA_GPS_PRO', 'NOVA_GPS_PR0');
  const cases = {
    'modified code (truncated)': lic.licenseCode.slice(0, -4),
    'modified payload': `${pre}.${flip(pl)}.${sig}`,
    'modified payload (product)': `${pre}.${Buffer.from(evil).toString('base64url')}.${sig}`,
    'modified signature': `${pre}.${pl}.${flip(sig)}`,
    'wrong signer': stranger.licenseCode,
    'garbage': 'not-a-license',
    'wrong prefix': lic.licenseCode.replace('NOVA1', 'NOVA2'),
  };
  for (const [name, code] of Object.entries(cases)) {
    const r = await s.post({ product: 'NOVA_GPS_PRO', licenseCode: code, deviceHash: d });
    assert.deepStrictEqual(r, { http: 400, body: { status: 'INVALID_LICENSE' } }, name);
  }
  assert.strictEqual(s.store.get(lic.licenseId).deviceHash, null, 'nothing got bound');
});

test('9. correctly signed but unknown license → 404 LICENSE_NOT_FOUND', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(false);
  assert.deepStrictEqual(await s.post(s.req(lic, dev())), { http: 404, body: { status: 'LICENSE_NOT_FOUND' } });
});

test('revoked license is refused (even on its own device)', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(), d = dev();
  assert.strictEqual((await s.post(s.req(lic, d))).http, 200);
  assert.ok(s.store.revoke(lic.licenseId));
  assert.deepStrictEqual(await s.post(s.req(lic, d)), { http: 403, body: { status: 'LICENSE_REVOKED' } });
});

test('malformed requests are rejected without touching the database', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(), d = dev();
  const bad = [
    { ...s.req(lic, d), product: 'OTHER' },
    { ...s.req(lic, d), deviceHash: 'ABC' },
    { ...s.req(lic, d), deviceHash: d.toUpperCase() },
    { ...s.req(lic, d), licenseCode: '' },
    { ...s.req(lic, d), licenseCode: 'x'.repeat(5000) },
    { product: 'NOVA_GPS_PRO' },
    [],
    'null',
  ];
  for (const b of bad) assert.strictEqual((await s.post(b)).http, 400, JSON.stringify(b).slice(0, 60));
  assert.strictEqual((await s.post('{oops')).http, 400);
  assert.strictEqual((await s.post('{}', { 'Content-Type': 'text/plain' })).http, 415);
  assert.strictEqual((await s.post('x'.repeat(20000))).http, 413);
  assert.strictEqual(s.store.get(lic.licenseId).status, 'AVAILABLE');
});

test('routing: health, 404, 405; rate limiting', async (t) => {
  const s = await setup({ rateLimitPerMinute: 5 }); t.after(s.close);
  const base = `http://127.0.0.1:${s.server.address().port}`;
  assert.strictEqual((await fetch(`${base}/healthz`)).status, 200);
  assert.strictEqual((await fetch(`${base}/nope`)).status, 404);
  assert.strictEqual((await fetch(`${base}/v1/activate`)).status, 405);
  const codes = [];
  for (let i = 0; i < 8; i++) codes.push((await s.post({})).http);
  assert.deepStrictEqual(codes, [400, 400, 400, 400, 400, 429, 429, 429]);
});

test('data model: only the agreed columns; no raw device id, no keys, no codes stored', async (t) => {
  const s = await setup(); t.after(s.close);
  const lic = s.issue(), d = dev();
  await s.post(s.req(lic, d));
  const cols = s.store.db.prepare('PRAGMA table_info(licenses)').all().map((c) => c.name);
  assert.deepStrictEqual(cols, ['license_id', 'license_type', 'status', 'device_hash', 'first_activated_at', 'last_activation_at', 'created_at']);
  const dump = JSON.stringify(s.store.db.prepare('SELECT * FROM licenses').all());
  assert.ok(!dump.includes('PRIVATE') && !dump.includes('NOVA1.'));
});

test('log lines carry no codes, device hashes or keys', async (t) => {
  const lines = [];
  const s = await setup({ log: (...a) => lines.push(a.join(' ')) }); t.after(s.close);
  const lic = s.issue(), d = dev();
  await s.post(s.req(lic, d)); await s.post(s.req(lic, dev()));
  const text = lines.join('\n');
  assert.match(text, /ACTIVATED/);
  assert.ok(!text.includes(d) && !text.includes('NOVA1') && !text.includes('PRIVATE') && !text.includes('NOVAACT1'));
});

test('startup refuses to run without keys; CLI imports and revokes', () => {
  const run = (args, env) => spawnSync(process.execPath, args, { encoding: 'utf8', env: { PATH: process.env.PATH, ...env } });
  const r = run([path.join(__dirname, '..', 'server.js')], {});
  assert.notStrictEqual(r.status, 0);
  assert.match(r.stderr, /missing required setting LICENSE_PUBLIC_KEY/);

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'srv-'));
  const env = { DATABASE_PATH: path.join(dir, 'db', 'l.db') };
  const lic = issueLicense(pair().privateKey);
  const rec = path.join(dir, 'x.record.json');
  fs.writeFileSync(rec, JSON.stringify(lic.record));
  const cli = path.join(__dirname, '..', 'cli.js');
  assert.match(run([cli, 'import', rec], env).stdout, /registered/);
  assert.match(run([cli, 'import', rec], env).stdout, /already registered/);
  assert.match(run([cli, 'revoke', lic.licenseId], env).stdout, /revoked/);
  assert.match(run([cli, 'show', lic.licenseId], env).stdout, /REVOKED/);
  const bad = path.join(dir, 'bad.json'); fs.writeFileSync(bad, '{"licenseId":"x"}');
  assert.notStrictEqual(run([cli, 'import', bad], env).status, 0);
  const kg = run([cli, 'keygen', '--out-dir', path.join(dir, 'keys')], env);
  assert.strictEqual(kg.status, 0, kg.stderr);
  assert.ok(!/PRIVATE KEY/.test(kg.stdout));
});

test('behind a trusted proxy the rate limit keys on the proxy-appended (last) X-Forwarded-For entry, not a spoofable one', async (t) => {
  const s = await setup({ rateLimitPerMinute: 3, trustProxy: true }); t.after(s.close);
  const base = `http://127.0.0.1:${s.server.address().port}/v1/activate`;
  const hit = async (xff) => (await fetch(base, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': xff }, body: '{}' })).status;
  const codes = [];
  // the attacker rotates a forged first entry; the proxy appends the real peer (203.0.113.9) last
  for (let i = 0; i < 6; i++) codes.push(await hit(`10.0.0.${i}, 203.0.113.9`));
  assert.deepStrictEqual(codes, [400, 400, 400, 429, 429, 429]);
  assert.strictEqual(await hit('198.51.100.7'), 400, 'a different real client is unaffected');
});
