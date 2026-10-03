'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { loadPrivateKey, insideGitRepo } = require('../lib/keys');

const tmp = (prefix) => fs.mkdtempSync(path.join(os.tmpdir(), prefix));
const newKey = () => crypto.generateKeyPairSync('ed25519');
const pemOf = (k) => k.privateKey.export({ type: 'pkcs8', format: 'pem' });
/** The base64 body lines of a PEM – any of them appearing in output would be a key leak. */
const bodyLines = (pem) => pem.split('\n').filter((l) => l && !l.startsWith('-----'));
const leaks = (text, pem) => /PRIVATE KEY/.test(text) || bodyLines(pem).some((l) => l.length >= 16 && text.includes(l));

function writeKey(dir, name = 'activation.pem') {
  fs.mkdirSync(dir, { recursive: true });
  const k = newKey(); const pem = pemOf(k); const file = path.join(dir, name);
  fs.writeFileSync(file, pem, { mode: 0o600 });
  return { file, pem, k };
}

/** Runs fn while capturing everything written through console.* and process.stdout/stderr. */
function capture(fn) {
  const out = [];
  const saved = {};
  for (const m of ['log', 'info', 'warn', 'error', 'debug']) { saved[m] = console[m]; console[m] = (...a) => out.push(a.join(' ')); }
  const so = process.stdout.write, se = process.stderr.write;
  process.stdout.write = (c) => { out.push(String(c)); return true; };
  process.stderr.write = (c) => { out.push(String(c)); return true; };
  let result, error;
  try { result = fn(); } catch (e) { error = e; }
  finally { Object.assign(console, saved); process.stdout.write = so; process.stderr.write = se; }
  return { out: out.join('\n'), result, error };
}

// ---- environment sanity: the "outside" location used below must really be outside any repository
test('test precondition: the OS temp dir is not inside a Git repository', () => {
  assert.strictEqual(insideGitRepo(path.join(os.tmpdir(), 'x', 'key.pem')), false);
});

// ---- 1. key inside a Git repository → rejected
test('private key inside a Git working tree is REJECTED (error, not a warning) and never read', () => {
  const repo = tmp('repo-'); fs.mkdirSync(path.join(repo, '.git'));
  const { file, pem } = writeKey(path.join(repo, 'secrets', 'deep'));
  const realRead = fs.readFileSync; let touched = false;
  fs.readFileSync = function (p, ...rest) { if (String(p) === file) touched = true; return realRead.call(this, p, ...rest); };
  let r;
  try { r = capture(() => loadPrivateKey({ file })); } finally { fs.readFileSync = realRead; }
  assert.ok(r.error, 'must throw');
  assert.strictEqual(r.error.code, 'KEY_IN_GIT_REPOSITORY');
  assert.match(r.error.message, /Git repository/);
  assert.strictEqual(touched, false, 'the file must not even be read');
  assert.strictEqual(r.result, undefined, 'no key object may be returned');
  assert.ok(!leaks(r.error.message + r.out, pem));
});

test('every kind of Git repository is recognised: .git dir, .git file (worktree/submodule), bare repo', () => {
  const dirRepo = tmp('r1-'); fs.mkdirSync(path.join(dirRepo, '.git'));
  const fileRepo = tmp('r2-'); fs.writeFileSync(path.join(fileRepo, '.git'), 'gitdir: /somewhere/.git/worktrees/x\n');
  const bare = tmp('r3-'); fs.writeFileSync(path.join(bare, 'HEAD'), 'ref: refs/heads/main\n');
  fs.mkdirSync(path.join(bare, 'objects')); fs.mkdirSync(path.join(bare, 'refs'));
  for (const repo of [dirRepo, fileRepo, bare]) {
    const { file } = writeKey(path.join(repo, 'k'));
    assert.throws(() => loadPrivateKey({ file }), (e) => e.code === 'KEY_IN_GIT_REPOSITORY', repo);
  }
  // a key stored inside the .git directory itself is also inside the repository
  const { file } = writeKey(path.join(dirRepo, '.git', 'hooks'));
  assert.throws(() => loadPrivateKey({ file }), (e) => e.code === 'KEY_IN_GIT_REPOSITORY');
});

test('a symlink cannot smuggle a repository-resident key past the check', () => {
  const repo = tmp('repo-'); fs.mkdirSync(path.join(repo, '.git'));
  const { file } = writeKey(path.join(repo, 'k'));
  const outside = tmp('out-'); const link = path.join(outside, 'innocent.pem');
  fs.symlinkSync(file, link);
  assert.throws(() => loadPrivateKey({ file: link }), (e) => e.code === 'KEY_IN_GIT_REPOSITORY');
});

test('an inline PEM does not bypass the file check when a file inside a repository is also configured', () => {
  const repo = tmp('repo-'); fs.mkdirSync(path.join(repo, '.git'));
  const { file } = writeKey(path.join(repo, 'k'));
  assert.throws(() => loadPrivateKey({ file, pem: pemOf(newKey()) }), (e) => e.code === 'KEY_IN_GIT_REPOSITORY');
});

// ---- 2. valid key outside a repository → accepted
test('valid private key outside any Git repository is ACCEPTED and usable', () => {
  const { file, k } = writeKey(tmp('keys-'));
  const r = capture(() => loadPrivateKey({ file }));
  assert.strictEqual(r.error, undefined);
  assert.strictEqual(r.result.asymmetricKeyType, 'ed25519');
  const sig = crypto.sign(null, Buffer.from('msg'), r.result);
  assert.ok(crypto.verify(null, Buffer.from('msg'), k.publicKey, sig));
  assert.strictEqual(r.out, '', 'no warning / output of any kind on the success path');
});

test('wrong or unreadable keys fail without echoing their content', () => {
  const dir = tmp('keys-');
  const rsa = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' });
  const rsaFile = path.join(dir, 'rsa.pem'); fs.writeFileSync(rsaFile, rsa);
  const junk = path.join(dir, 'junk.pem'); fs.writeFileSync(junk, 'not a key at all, top-secret-looking text 123456\n');
  for (const [file, pem] of [[rsaFile, rsa], [junk, 'top-secret-looking']]) {
    const r = capture(() => loadPrivateKey({ file }));
    assert.ok(r.error);
    assert.ok(!leaks(r.error.message + (r.error.stack || '') + r.out, pem), file);
  }
  assert.throws(() => loadPrivateKey({}), /no private key configured/);
});

// ---- 3. nothing secret is printed or logged
test('loading a key (success and every failure path) prints/logs no key material', () => {
  const okDir = tmp('keys-'); const ok = writeKey(okDir);
  const repo = tmp('repo-'); fs.mkdirSync(path.join(repo, '.git')); const bad = writeKey(repo);
  const all = [
    capture(() => loadPrivateKey({ file: ok.file })),
    capture(() => loadPrivateKey({ file: bad.file })),
    capture(() => loadPrivateKey({ pem: ok.pem })),
    capture(() => loadPrivateKey({ pem: 'garbage' })),
  ];
  for (const r of all) {
    assert.ok(!leaks(r.out, ok.pem) && !leaks(r.out, bad.pem));
    if (r.error) assert.ok(!leaks(r.error.message + (r.error.stack || ''), ok.pem + bad.pem));
  }
});

test('inline PEM (environment variable) is accepted', () => {
  assert.strictEqual(loadPrivateKey({ pem: pemOf(newKey()) }).asymmetricKeyType, 'ed25519');
});

test('generator CLI: --key-file inside a Git repository → exit 1, clear message, nothing issued, no key material printed', () => {
  const gen = path.join(__dirname, '..', 'generate.js');
  const repo = tmp('repo-'); fs.mkdirSync(path.join(repo, '.git')); const bad = writeKey(repo);
  const out = path.join(tmp('out-'), 'issued');
  const env = { PATH: process.env.PATH };
  const rej = spawnSync(process.execPath, [gen, 'issue', '--out', out, '--key-file', bad.file], { encoding: 'utf8', env });
  assert.strictEqual(rej.status, 1);
  assert.match(rej.stderr, /refusing to load a private key from inside a Git repository/);
  assert.ok(!leaks(rej.stdout + rej.stderr, bad.pem));
  assert.ok(!fs.existsSync(out) || fs.readdirSync(out).length === 0, 'no license may be issued');
  // same through the environment variable
  const viaEnv = spawnSync(process.execPath, [gen, 'issue', '--out', out], { encoding: 'utf8', env: { ...env, LICENSE_PRIVATE_KEY_FILE: bad.file } });
  assert.strictEqual(viaEnv.status, 1);
  assert.match(viaEnv.stderr, /Git repository/);

  const good = writeKey(tmp('keys-'));
  const ok = spawnSync(process.execPath, [gen, 'issue', '--out', out, '--key-file', good.file], { encoding: 'utf8', env });
  assert.strictEqual(ok.status, 0, ok.stderr);
  assert.ok(!leaks(ok.stdout + ok.stderr, good.pem));
  assert.ok(!/WARNING/.test(ok.stdout + ok.stderr));
});
