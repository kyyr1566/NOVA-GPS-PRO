'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const script = path.join(__dirname, '..', '..', 'check-no-secrets.sh');
const git = (cwd, ...args) => spawnSync('git', args, { cwd, encoding: 'utf8' });

function repo(files, { forceAdd = [] } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'guard-'));
  git(dir, 'init', '-q');
  fs.writeFileSync(path.join(dir, '.gitignore'), '*.pem\n*.jks\n.env\nissued/\n');
  for (const [name, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), text);
  }
  for (const f of forceAdd) git(dir, 'add', '-f', f);
  return dir;
}
const run = (dir) => spawnSync('bash', [script], { cwd: dir, encoding: 'utf8' });
const pem = () => crypto.generateKeyPairSync('ed25519').privateKey.export({ type: 'pkcs8', format: 'pem' });
const longCode = `NOVA1.${'A'.repeat(80)}.${'B'.repeat(86)}`;

test('secret guard passes on a clean tree (and allows .env.example)', () => {
  const r = run(repo({ 'a.txt': 'hello', '.env.example': 'LICENSE_PUBLIC_KEY=\n', 'README.md': 'code looks like NOVA1.<payload>.<signature>' }));
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
});

test('secret guard blocks force-added key/keystore/.env/database/issued files', () => {
  for (const f of ['k.pem', 'release.jks', '.env', 'data/x.db', 'issued/NL-A.license.txt', 'issued/NL-A.record.json', 'keystore.properties', 'node_modules/x/index.js']) {
    const dir = repo({ [f]: 'x' }, { forceAdd: [f] });
    const r = run(dir);
    assert.strictEqual(r.status, 1, f);
    assert.match(r.stdout, new RegExp(f.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')), f);
  }
});

test('secret guard blocks key material and customer licenses by content, without printing the content', () => {
  const p = pem();
  const dir = repo({ 'notes.txt': p, 'tokens.txt': `ghp_${'a'.repeat(36)}`, 'x/lic.txt': longCode,
    'build.gradle.kts': ['store', 'Password = "hunter2hunter2"'].join('') });
  const r = run(dir);
  assert.strictEqual(r.status, 1);
  for (const f of ['notes.txt', 'tokens.txt', 'x/lic.txt', 'build.gradle.kts']) assert.match(r.stdout, new RegExp(f));
  const out = r.stdout + r.stderr;
  assert.ok(!out.includes(p.split('\n')[1]) && !out.includes('hunter2') && !out.includes('A'.repeat(40)), 'content must not be echoed');
});

test('secret guard allow-lists only the throw-away test fixture path', () => {
  const fixture = 'app/src/test/java/com/nova/gpspro/license/NodeInteropFixture.kt';
  assert.strictEqual(run(repo({ [fixture]: `const val C = "${longCode}"` })).status, 0);
  assert.strictEqual(run(repo({ 'app/src/main/Other.kt': `const val C = "${longCode}"` })).status, 1);
});

test('the real repository passes the guard', () => {
  const root = path.join(__dirname, '..', '..', '..');
  const r = spawnSync('bash', [script], { cwd: root, encoding: 'utf8' });
  assert.strictEqual(r.status, 0, r.stdout + r.stderr);
});
