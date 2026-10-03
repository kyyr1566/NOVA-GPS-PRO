'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const P = require('./protocol');

const isDir = (p) => { try { return fs.statSync(p).isDirectory(); } catch { return false; } };

/** A directory is a Git repository root if it has `.git` (directory, or file for worktrees/submodules) or is a bare repo. */
function isGitRoot(dir) {
  if (fs.existsSync(path.join(dir, '.git'))) return true;
  return fs.existsSync(path.join(dir, 'HEAD')) && isDir(path.join(dir, 'objects')) && isDir(path.join(dir, 'refs'));
}

/** Resolves symlinks of the longest existing prefix of `p` (so it also works for files that do not exist yet). */
function realish(p) {
  const rest = [];
  let cur = path.resolve(p);
  for (;;) {
    try { return path.join(fs.realpathSync(cur), ...rest.reverse()); } catch { /* not there yet */ }
    const up = path.dirname(cur);
    if (up === cur) return path.resolve(p);
    rest.push(path.basename(cur));
    cur = up;
  }
}

function hasGitAncestor(file) {
  let dir = path.dirname(file);
  for (;;) {
    if (isGitRoot(dir)) return true;
    const up = path.dirname(dir);
    if (up === dir) return false;
    dir = up;
  }
}

/**
 * True if `file` is inside ANY Git repository / working tree – judged by its path as given AND by where it really
 * lives after resolving symlinks (a symlink can't be used to smuggle a repo-resident key past the check).
 */
function insideGitRepo(file) {
  const given = path.resolve(file);
  return hasGitAncestor(given) || hasGitAncestor(realish(given));
}

/**
 * Loads an Ed25519 private key (PKCS#8 PEM) from a file path or an inline PEM.
 *  - A key FILE located inside a Git repository / working tree is refused outright (the file is not even read).
 *  - Error messages and logs never contain key material.
 */
function loadPrivateKey({ file, pem }) {
  if (file && insideGitRepo(file)) {
    const err = new Error(`refusing to load a private key from inside a Git repository (${path.resolve(file)}); ` +
      'keep private keys outside any repository / working tree');
    err.code = 'KEY_IN_GIT_REPOSITORY';
    throw err;
  }
  let text = pem;
  if (!text && file) text = fs.readFileSync(file, 'utf8');
  if (!text) throw new Error('no private key configured');
  let key;
  try {
    key = crypto.createPrivateKey(text);
  } catch {
    throw new Error('the private key could not be parsed (expected an unencrypted PKCS#8 Ed25519 PEM)');
  }
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('the private key is not an Ed25519 key');
  return key;
}

/** Creates a NEW key pair, writes the private key (mode 600) into outDir and returns the public key. */
function generateKeyPair(outDir, name) {
  if (insideGitRepo(path.join(outDir, name))) {
    throw new Error(`refusing to write a private key inside a Git repository (${path.resolve(outDir)}); choose a folder outside the project`);
  }
  fs.mkdirSync(outDir, { recursive: true, mode: 0o700 });
  const file = path.join(outDir, `${name}.pem`);
  if (fs.existsSync(file)) throw new Error(`${file} already exists – refusing to overwrite a key`);
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  fs.writeFileSync(file, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600, flag: 'wx' });
  return { file, publicKeyBase64: Buffer.from(P.rawPublicKey(publicKey)).toString('base64') };
}

module.exports = { loadPrivateKey, generateKeyPair, insideGitRepo };
