#!/usr/bin/env node
'use strict';
/**
 * NOVA GPS PRO license generator (developer / seller tool – NOT part of the app).
 *
 *   node generate.js keygen --out-dir <DIR outside the repository>
 *   node generate.js issue  [--count N] [--out DIR] [--min-version N] [--key-file FILE] [--ascii]
 *   node generate.js verify <license code | file> [--public-key BASE64]
 *
 * The private key is read from a file (--key-file / LICENSE_PRIVATE_KEY_FILE) or from the
 * LICENSE_PRIVATE_KEY_PEM environment variable. It is never part of this source tree.
 */
const fs = require('node:fs');
const path = require('node:path');
const P = require('./lib/protocol');
const { loadPrivateKey, generateKeyPair } = require('./lib/keys');
const { issueLicense } = require('./lib/issue');
const qr = require('./lib/qr');
const { qrToPng, qrToAscii } = require('./lib/image');

function parseArgs(argv) {
  const flags = {}, rest = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const k = argv[i].slice(2);
      if (k === 'ascii') flags[k] = true; else flags[k] = argv[++i];
    } else rest.push(argv[i]);
  }
  return { flags, rest };
}

function usage() {
  console.log(`Usage:
  node generate.js keygen --out-dir <DIR outside the repo>      create the LICENSE signing key pair (prints the PUBLIC key)
  node generate.js issue [--count N] [--out DIR] [--min-version N] [--key-file FILE] [--ascii]
  node generate.js verify <license code | file> [--public-key BASE64]

Private key: --key-file FILE, or env LICENSE_PRIVATE_KEY_FILE, or env LICENSE_PRIVATE_KEY_PEM.`);
  process.exit(2);
}

function main(argv) {
  const [cmd, ...tail] = argv;
  const { flags, rest } = parseArgs(tail);
  switch (cmd) {
    case 'keygen': {
      if (!flags['out-dir']) usage();
      const r = generateKeyPair(flags['out-dir'], 'nova-license-private');
      console.log(`Private key written to ${r.file}\n  → keep it OFF GitHub and OFF any server; back it up offline.`);
      console.log('Put this PUBLIC key in the Android build (LICENSE_PUBLIC_KEY) and on the server (LICENSE_PUBLIC_KEY):');
      console.log(r.publicKeyBase64);
      return;
    }
    case 'issue': {
      const count = Number(flags.count || 1), outDir = flags.out || 'issued';
      if (!Number.isInteger(count) || count < 1 || count > 1000) throw new Error('--count must be 1..1000');
      const key = loadPrivateKey({
        file: flags['key-file'] || process.env.LICENSE_PRIVATE_KEY_FILE, pem: process.env.LICENSE_PRIVATE_KEY_PEM });
      fs.mkdirSync(outDir, { recursive: true });
      for (let i = 0; i < count; i++) {
        const lic = issueLicense(key, { minVersion: flags['min-version'] ? Number(flags['min-version']) : 1 });
        const base = path.join(outDir, lic.licenseId);
        fs.writeFileSync(`${base}.license.txt`, `${lic.licenseCode}\n`);
        fs.writeFileSync(`${base}.record.json`, `${JSON.stringify(lic.record, null, 2)}\n`);
        const code = qr.encode(lic.licenseCode);
        fs.writeFileSync(`${base}.png`, qrToPng(code));
        console.log(`${lic.licenseId}\n  code : ${base}.license.txt\n  QR   : ${base}.png (QR v${code.version}, contains the license code only)\n  db   : ${base}.record.json`);
        if (flags.ascii) console.log(`\n${qrToAscii(code)}\n`);
      }
      console.log(`\nNext: register the licenses on the server →  node server/license-server/cli.js import ${path.join(outDir, '*.record.json')}`);
      return;
    }
    case 'verify': {
      if (!rest[0]) usage();
      const text = fs.existsSync(rest[0]) ? fs.readFileSync(rest[0], 'utf8') : rest[0];
      const parsed = P.parseLicense(text);
      if (!parsed) throw new Error('not a well-formed license code');
      console.log(JSON.stringify(parsed.claims, null, 2));
      const pub = flags['public-key'] || process.env.LICENSE_PUBLIC_KEY;
      if (!pub) { console.log('(signature not checked: pass --public-key or set LICENSE_PUBLIC_KEY)'); return; }
      const ok = P.verifyLicenseSignature(parsed, P.publicKeyFromBase64(pub));
      console.log(ok ? 'signature: VALID' : 'signature: INVALID');
      if (!ok) process.exit(1);
      return;
    }
    default: usage();
  }
}

try { main(process.argv.slice(2)); } catch (e) { console.error(`Error: ${e.message}`); process.exit(1); }
