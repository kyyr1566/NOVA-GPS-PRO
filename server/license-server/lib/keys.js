'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const P = require('./protocol');

/** True if `file` lives inside a Git working tree (private keys must never be stored there). */
function insideGitRepo(file) {
  let dir = path.dirname(path.resolve(file));
  for (;;) {
    if (fs.existsSync(path.join(dir, '.git'))) return true;
    const up = path.dirname(dir);
    if (up === dir) return false;
    dir = up;
  }
}

/** Loads an Ed25519 private key (PKCS#8 PEM) from a file path or an inline PEM. Never logs key material. */
function loadPrivateKey({ file, pem }) {
  let text = pem;
  if (!text && file) {
    if (insideGitRepo(file)) console.warn('WARNING: the private key file is inside a Git working tree – move it out of the repository.');
    text = fs.readFileSync(file, 'utf8');
  }
  if (!text) throw new Error('no private key configured');
  const key = crypto.createPrivateKey(text);
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
