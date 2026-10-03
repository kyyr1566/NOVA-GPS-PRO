'use strict';
const P = require('./protocol');
const { loadPrivateKey } = require('./keys');

/** All configuration comes from the environment (see .env.example). Nothing secret lives in the source. */
function loadConfig(env = process.env) {
  const need = (name) => {
    if (!env[name]) throw new Error(`missing required setting ${name}`);
    return env[name];
  };
  return {
    host: env.HOST || '127.0.0.1',                          // bind locally; TLS is terminated by a reverse proxy
    port: Number(env.PORT || 8080),
    databasePath: env.DATABASE_PATH || './data/licenses.db',
    trustProxy: env.TRUST_PROXY === '1',
    rateLimitPerMinute: Number(env.RATE_LIMIT_PER_MINUTE || 20),
    licensePublicKey: P.publicKeyFromBase64(need('LICENSE_PUBLIC_KEY')),        // PUBLIC key of the license issuer
    activationPrivateKey: loadPrivateKey({                                       // server's receipt-signing key
      file: env.ACTIVATION_PRIVATE_KEY_FILE, pem: env.ACTIVATION_PRIVATE_KEY_PEM }),
  };
}

module.exports = { loadConfig };
