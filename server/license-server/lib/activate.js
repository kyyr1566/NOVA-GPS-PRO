'use strict';
const P = require('./protocol');
const { STATUS } = require('./store');

/**
 * First-activation / re-activation decision. Pure of HTTP; returns { http, body }.
 *
 *   signature invalid / malformed ........ 400 INVALID_LICENSE
 *   valid signature, not registered ...... 404 LICENSE_NOT_FOUND
 *   revoked .............................. 403 LICENSE_REVOKED
 *   not bound yet → bind to this device .. 200 ACTIVATED   (first activation)
 *   bound to the SAME device ............. 200 ACTIVATED   (reinstall / re-entry)
 *   bound to ANOTHER device .............. 409 LICENSE_ALREADY_BOUND   (never transferable)
 */
function activate({ store, licensePublicKey, activationPrivateKey, now = () => Math.floor(Date.now() / 1000) }, req) {
  let licenseId; // only known (and logged) once the signature has been verified
  const fail = (http, status) => ({ http, body: { status }, licenseId });
  if (!req || typeof req !== 'object') return fail(400, 'INVALID_REQUEST');
  const { product, licenseCode, deviceHash } = req;
  if (product !== P.PRODUCT) return fail(400, 'INVALID_REQUEST');
  if (typeof deviceHash !== 'string' || !P.DEVICE_HASH_RE.test(deviceHash)) return fail(400, 'INVALID_REQUEST');
  if (typeof licenseCode !== 'string' || licenseCode.length === 0 || licenseCode.length > 2048) return fail(400, 'INVALID_REQUEST');

  const parsed = P.parseLicense(licenseCode);
  if (!parsed || !P.verifyLicenseSignature(parsed, licensePublicKey)) return fail(400, 'INVALID_LICENSE');
  const c = parsed.claims;
  if (c.version !== 1 || c.product !== P.PRODUCT || c.licenseType !== 'LIFETIME' || c.deviceBinding !== 'FIRST_ACTIVATION') {
    return fail(400, 'INVALID_LICENSE');
  }

  licenseId = c.licenseId;
  const row = store.get(c.licenseId);
  if (!row) return fail(404, 'LICENSE_NOT_FOUND');
  if (row.status === STATUS.REVOKED) return fail(403, 'LICENSE_REVOKED');

  const t = now();
  let current = row;
  if (row.deviceHash === null) {
    if (store.bind(c.licenseId, deviceHash, t)) current = store.get(c.licenseId);
    else current = store.get(c.licenseId); // lost a race: whoever won is now the bound device
  }
  if (current.status === STATUS.REVOKED) return fail(403, 'LICENSE_REVOKED');
  if (current.deviceHash !== deviceHash) return fail(409, 'LICENSE_ALREADY_BOUND');

  if (row.deviceHash !== null) store.touch(c.licenseId, t);
  const receipt = P.signReceipt(activationPrivateKey, {
    licenseId: c.licenseId, deviceHash, firstActivatedAt: current.firstActivatedAt,
  });
  return { http: 200, body: { status: 'ACTIVATED', receipt }, licenseId };
}

module.exports = { activate };
