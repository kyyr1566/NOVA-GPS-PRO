#!/usr/bin/env node
'use strict';
/** Operator tool:  node cli.js <keygen|import|revoke|show|list> …  (reads DATABASE_PATH like the server) */
const fs = require('node:fs');
const { LicenseStore } = require('./lib/store');
const { generateKeyPair } = require('./lib/keys');
const P = require('./lib/protocol');

const [cmd, ...args] = process.argv.slice(2);
const db = () => new LicenseStore(process.env.DATABASE_PATH || './data/licenses.db');
const usage = () => {
  console.log(`Usage:
  node cli.js keygen --out-dir <DIR outside the repo>   create the ACTIVATION key pair (prints the PUBLIC key for the app)
  node cli.js import <file.record.json> [...]            register licenses produced by the license generator
  node cli.js revoke <licenseId>                         block a license (activation + re-activation refused)
  node cli.js show <licenseId>
  node cli.js list`);
  process.exit(2);
};

try {
  switch (cmd) {
    case 'keygen': {
      const i = args.indexOf('--out-dir');
      if (i < 0 || !args[i + 1]) usage();
      const r = generateKeyPair(args[i + 1], 'nova-activation-private');
      console.log(`Private key written to ${r.file} (keep it OFF GitHub, back it up offline).`);
      console.log('Put this PUBLIC key in the Android build as ACTIVATION_PUBLIC_KEY:');
      console.log(r.publicKeyBase64);
      break;
    }
    case 'import': {
      if (!args.length) usage();
      const s = db();
      for (const f of args) {
        const rec = JSON.parse(fs.readFileSync(f, 'utf8'));
        if (!P.LICENSE_ID_RE.test(rec.licenseId) || rec.licenseType !== 'LIFETIME' || !Number.isInteger(rec.createdAt)) {
          throw new Error(`${f}: not a valid license record`);
        }
        console.log(`${rec.licenseId}: ${s.register(rec) ? 'registered' : 'already registered'}`);
      }
      break;
    }
    case 'revoke': {
      if (!args[0]) usage();
      console.log(db().revoke(args[0]) ? 'revoked' : 'license not found');
      break;
    }
    case 'show': {
      if (!args[0]) usage();
      console.log(JSON.stringify(db().get(args[0]) || 'license not found', null, 2));
      break;
    }
    case 'list': {
      for (const l of db().list()) console.log(`${l.licenseId}  ${l.status.padEnd(9)}  bound=${l.deviceHash ? 'yes' : 'no'}  first=${l.firstActivatedAt ?? '-'}`);
      break;
    }
    default: usage();
  }
} catch (e) {
  console.error(`Error: ${e.message}`);
  process.exit(1);
}
