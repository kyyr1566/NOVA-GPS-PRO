'use strict';
/**
 * License database (SQLite via node:sqlite). Holds ONLY:
 *   licenseId, licenseType, status, deviceHash (salted SHA-256 – never the raw ANDROID_ID),
 *   firstActivatedAt, lastActivationAt, createdAt.
 * No private keys, no license codes, no IPs, no location / usage data.
 */
const fs = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const STATUS = Object.freeze({ AVAILABLE: 'AVAILABLE', ACTIVATED: 'ACTIVATED', REVOKED: 'REVOKED' });

const SCHEMA = `
CREATE TABLE IF NOT EXISTS licenses (
  license_id          TEXT PRIMARY KEY,
  license_type        TEXT NOT NULL,
  status              TEXT NOT NULL CHECK (status IN ('AVAILABLE','ACTIVATED','REVOKED')),
  device_hash         TEXT,
  first_activated_at  INTEGER,
  last_activation_at  INTEGER,
  created_at          INTEGER NOT NULL
) STRICT;`;

const toModel = (r) => r && ({
  licenseId: r.license_id, licenseType: r.license_type, status: r.status, deviceHash: r.device_hash,
  firstActivatedAt: r.first_activated_at, lastActivationAt: r.last_activation_at, createdAt: r.created_at,
});

class LicenseStore {
  constructor(file) {
    if (file !== ':memory:') fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
    this.db = new DatabaseSync(file);
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000; PRAGMA foreign_keys = ON;');
    this.db.exec(SCHEMA);
    this.q = {
      get: this.db.prepare('SELECT * FROM licenses WHERE license_id = ?'),
      insert: this.db.prepare('INSERT OR IGNORE INTO licenses (license_id, license_type, status, created_at) VALUES (?, ?, ?, ?)'),
      // atomic first binding: succeeds for exactly one caller, whatever the concurrency
      bind: this.db.prepare(`UPDATE licenses SET device_hash = ?, status = 'ACTIVATED', first_activated_at = ?, last_activation_at = ?
                             WHERE license_id = ? AND status = 'AVAILABLE' AND device_hash IS NULL`),
      touch: this.db.prepare('UPDATE licenses SET last_activation_at = ? WHERE license_id = ?'),
      revoke: this.db.prepare(`UPDATE licenses SET status = 'REVOKED' WHERE license_id = ?`),
      list: this.db.prepare('SELECT * FROM licenses ORDER BY created_at DESC, license_id'),
    };
  }

  get(licenseId) { return toModel(this.q.get.get(licenseId)); }

  /** Registers a license issued by the generator. Idempotent. Returns true if newly inserted. */
  register({ licenseId, licenseType, createdAt }) {
    return this.q.insert.run(licenseId, licenseType, STATUS.AVAILABLE, createdAt).changes === 1;
  }

  /** @returns true iff THIS call performed the first binding. */
  bind(licenseId, deviceHash, now) { return this.q.bind.run(deviceHash, now, now, licenseId).changes === 1; }

  touch(licenseId, now) { this.q.touch.run(now, licenseId); }

  revoke(licenseId) { return this.q.revoke.run(licenseId).changes === 1; }

  list() { return this.q.list.all().map(toModel); }

  close() { this.db.close(); }
}

module.exports = { LicenseStore, STATUS };
