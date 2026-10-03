'use strict';
/**
 * NOVA GPS PRO license activation server.
 *
 *   POST /v1/activate   {"product":"NOVA_GPS_PRO","licenseCode":"NOVA1…","deviceHash":"<64 hex>","appVersion":1}
 *   GET  /healthz
 *
 * It only ever sees: license code, salted device hash, product, app version. It never receives GPS data,
 * the raw ANDROID_ID, or anything about how the app is used. Run it behind an HTTPS reverse proxy
 * (the Android app refuses non-HTTPS servers).
 */
const http = require('node:http');
const { activate } = require('./lib/activate');

const MAX_BODY = 8 * 1024;

function createServer({ store, licensePublicKey, activationPrivateKey, trustProxy = false, rateLimitPerMinute = 20,
                        now = () => Math.floor(Date.now() / 1000), log = defaultLog }) {
  const hits = new Map(); // ip -> { n, reset }

  function limited(ip) {
    const t = Date.now();
    let h = hits.get(ip);
    if (!h || h.reset <= t) { h = { n: 0, reset: t + 60_000 }; hits.set(ip, h); }
    h.n += 1;
    if (hits.size > 10_000) for (const [k, v] of hits) if (v.reset <= t) hits.delete(k);
    return h.n > rateLimitPerMinute;
  }

  const send = (res, code, body) => {
    const data = JSON.stringify(body);
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(data), 'Cache-Control': 'no-store' });
    res.end(data);
  };

  return http.createServer((req, res) => {
    const url = (req.url || '').split('?')[0];
    if (req.method === 'GET' && url === '/healthz') return send(res, 200, { status: 'OK' });
    if (url !== '/v1/activate') return send(res, 404, { status: 'NOT_FOUND' });
    if (req.method !== 'POST') return send(res, 405, { status: 'METHOD_NOT_ALLOWED' });

    // Behind a trusted reverse proxy the client address is the LAST X-Forwarded-For entry (the one the proxy appended);
    // earlier entries are client-supplied and must not be trusted, or the rate limit could be dodged by spoofing them.
    const ip = (trustProxy && String(req.headers['x-forwarded-for'] || '').split(',').pop().trim()) || req.socket.remoteAddress || '?';
    if (limited(ip)) return send(res, 429, { status: 'RATE_LIMITED' });
    if (!/^application\/json/i.test(req.headers['content-type'] || '')) return send(res, 415, { status: 'INVALID_REQUEST' });

    const chunks = []; let size = 0; let aborted = false;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { aborted = true; send(res, 413, { status: 'INVALID_REQUEST' }); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (aborted) return;
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return send(res, 400, { status: 'INVALID_REQUEST' }); }
      try {
        const r = activate({ store, licensePublicKey, activationPrivateKey, now }, body);
        log(r.body.status, r.licenseId);
        send(res, r.http, r.body);
      } catch (e) {
        log('SERVER_ERROR');
        send(res, 500, { status: 'SERVER_ERROR' });
      }
    });
    req.on('error', () => {});
  });
}

// Only a (verified) license id – not secret, no personal data – is logged; never codes, device hashes, IPs or keys.
function defaultLog(event, licenseId) {
  console.log(JSON.stringify({ t: new Date().toISOString(), event, licenseId }));
}

if (require.main === module) {
  const { loadConfig } = require('./lib/config');
  const { LicenseStore } = require('./lib/store');
  let cfg;
  try { cfg = loadConfig(); } catch (e) { console.error(`Configuration error: ${e.message}`); process.exit(1); }
  const store = new LicenseStore(cfg.databasePath);
  createServer({ store, ...cfg }).listen(cfg.port, cfg.host, () =>
    console.log(`NOVA license server listening on ${cfg.host}:${cfg.port}`));
}

module.exports = { createServer };
