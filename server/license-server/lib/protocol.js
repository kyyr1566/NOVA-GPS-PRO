'use strict';
/**
 * NOVA GPS PRO license protocol (server side). Keep byte-for-byte compatible with
 * tools/license-generator/lib/protocol.js and the Android app (com.nova.gpspro.license).
 *
 *   license  = NOVA1.<base64url(payload)>.<base64url(Ed25519 signature)>      (signed OFFLINE by the issuer)
 *   receipt  = NOVAACT1.<base64url(payload)>.<base64url(Ed25519 signature)>   (signed by THIS server on activation)
 *
 * payload  = UTF-8 text, one `key=value` per line. The signature covers
 *            DOMAIN_TAG + payload bytes (domain separation: a license signature can never pass as a receipt).
 */
const crypto = require('node:crypto');

const PRODUCT = 'NOVA_GPS_PRO';
const LICENSE_PREFIX = 'NOVA1';
const RECEIPT_PREFIX = 'NOVAACT1';
const LICENSE_DOMAIN = Buffer.from('NOVA-LICENSE-V1\n', 'utf8');
const RECEIPT_DOMAIN = Buffer.from('NOVA-ACTIVATION-V1\n', 'utf8');
const SPKI_ED25519_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

const LICENSE_ID_RE = /^NL-[A-Z2-7]{26}$/;
const DEVICE_HASH_RE = /^[0-9a-f]{64}$/;
const NONCE_RE = /^[0-9a-f]{32}$/;

/** Removes whitespace / zero-width characters that pasting or QR apps tend to add. */
function normalize(text) {
  return String(text).replace(/[\s\u200B-\u200F\uFEFF]/g, '');
}

function b64uDecode(s) {
  if (!/^[A-Za-z0-9_-]+$/.test(s)) return null;
  return Buffer.from(s, 'base64url');
}

/** Raw 32-byte Ed25519 public key (base64 / base64url) -> KeyObject. Throws on bad input. */
function publicKeyFromBase64(b64) {
  const raw = Buffer.from(String(b64).trim().replace(/-/g, '+').replace(/_/g, '/'), 'base64');
  if (raw.length !== 32) throw new Error('Ed25519 public key must be 32 raw bytes (base64)');
  return crypto.createPublicKey({ key: Buffer.concat([SPKI_ED25519_PREFIX, raw]), format: 'der', type: 'spki' });
}

function rawPublicKey(publicKey) {
  return publicKey.export({ type: 'spki', format: 'der' }).subarray(-32);
}

/** Parses `PREFIX.payload.signature` syntactically. Returns null if malformed. Trust nothing yet. */
function parseSigned(prefix, text) {
  const parts = normalize(text).split('.');
  if (parts.length !== 3 || parts[0] !== prefix) return null;
  const payload = b64uDecode(parts[1]);
  const signature = b64uDecode(parts[2]);
  if (!payload || !signature || signature.length !== 64 || payload.length === 0 || payload.length > 4096) return null;
  const fields = new Map();
  for (const line of payload.toString('utf8').split('\n')) {
    const l = line.trim();
    if (!l) continue;
    const eq = l.indexOf('=');
    if (eq <= 0) return null;
    const k = l.slice(0, eq);
    if (fields.has(k)) return null; // duplicate key
    fields.set(k, l.slice(eq + 1));
  }
  return { payload, signature, fields };
}

function toInt(s) {
  return /^[0-9]{1,15}$/.test(s || '') ? Number(s) : null;
}

/** Parses + shape-validates a license. Returns {payload, signature, claims} or null. */
function parseLicense(code) {
  const p = parseSigned(LICENSE_PREFIX, code);
  if (!p) return null;
  const f = p.fields;
  const claims = {
    version: toInt(f.get('v')),
    product: f.get('product'),
    licenseId: f.get('licenseId'),
    licenseType: f.get('licenseType'),
    issuedAt: toInt(f.get('issuedAt')),
    deviceBinding: f.get('deviceBinding'),
    minVersion: toInt(f.get('minVersion')),
    nonce: f.get('nonce'),
  };
  if (claims.version === null || claims.issuedAt === null || claims.minVersion === null) return null;
  if (!claims.product || !LICENSE_ID_RE.test(claims.licenseId || '') || !claims.licenseType) return null;
  if (!claims.deviceBinding || !NONCE_RE.test(claims.nonce || '')) return null;
  return { payload: p.payload, signature: p.signature, claims };
}

function verifyWith(publicKey, domain, payload, signature) {
  try {
    return crypto.verify(null, Buffer.concat([domain, payload]), publicKey, signature);
  } catch {
    return false;
  }
}

const verifyLicenseSignature = (parsed, publicKey) =>
  verifyWith(publicKey, LICENSE_DOMAIN, parsed.payload, parsed.signature);

/** Signs an activation receipt with the server's activation private key. */
function signReceipt(privateKey, { licenseId, deviceHash, firstActivatedAt }) {
  const payload = Buffer.from(
    `v=1\nproduct=${PRODUCT}\nlicenseId=${licenseId}\ndeviceHash=${deviceHash}\nfirstActivatedAt=${firstActivatedAt}\n`, 'utf8');
  const sig = crypto.sign(null, Buffer.concat([RECEIPT_DOMAIN, payload]), privateKey);
  return `${RECEIPT_PREFIX}.${payload.toString('base64url')}.${sig.toString('base64url')}`;
}

module.exports = {
  PRODUCT, LICENSE_PREFIX, RECEIPT_PREFIX, LICENSE_DOMAIN, RECEIPT_DOMAIN,
  LICENSE_ID_RE, DEVICE_HASH_RE, NONCE_RE,
  normalize, publicKeyFromBase64, rawPublicKey, parseSigned, parseLicense,
  verifyLicenseSignature, verifyWith, signReceipt,
};
