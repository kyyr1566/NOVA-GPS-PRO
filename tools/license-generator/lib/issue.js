'use strict';
const P = require('./protocol');

/** Creates one new LIFETIME license. Returns the code to give the customer + the record to import on the server. */
function issueLicense(privateKey, { minVersion = 1, now = () => Math.floor(Date.now() / 1000) } = {}) {
  if (!Number.isInteger(minVersion) || minVersion < 1) throw new Error('minVersion must be a positive integer');
  const licenseId = P.newLicenseId();
  const t = now();
  const licenseCode = P.signLicense(privateKey, { licenseId, issuedAt: t, minVersion });
  return { licenseId, licenseCode, record: { licenseId, licenseType: 'LIFETIME', createdAt: t } };
}

module.exports = { issueLicense };
