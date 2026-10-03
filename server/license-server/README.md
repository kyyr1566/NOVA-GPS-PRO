# NOVA GPS PRO – license activation server

Tiny dependency-free Node.js (≥ 22.5, uses built-in `node:sqlite`) service. It exists **only** for first activation and
device-binding checks. It has no accounts, no payments, no location/GPS/usage data and stores no IP addresses.

## What it does
`POST /v1/activate` `{ "product":"NOVA_GPS_PRO", "licenseCode":"NOVA1.…", "deviceHash":"<64 hex>", "appVersion":1 }`

1. Verifies the Ed25519 signature of the license code (public key `LICENSE_PUBLIC_KEY`).
2. Looks the `licenseId` up in its database (licenses must have been **imported** after being issued → otherwise `LICENSE_NOT_FOUND`).
3. First activation → binds the license to `deviceHash`, stores `firstActivatedAt`. Same hash again → allowed (reinstall / cleared data).
   Another hash → `409 LICENSE_ALREADY_BOUND`. The bound hash is never changed. Revoked → `LICENSE_REVOKED`.
4. Answers `ACTIVATED` + a signed receipt `NOVAACT1.…` (signature by the server's *activation* key over licenseId + deviceHash + firstActivatedAt).
   The app verifies the receipt offline, so a fake server cannot activate anything.

Statuses: `ACTIVATED, INVALID_REQUEST, INVALID_LICENSE, LICENSE_NOT_FOUND, LICENSE_REVOKED, LICENSE_ALREADY_BOUND, RATE_LIMITED, SERVER_ERROR`.
Responses never contain device hashes, other license data or internal error text. `GET /healthz` is also available.

Database (`licenses`): `license_id, license_type, status, device_hash, first_activated_at, last_activation_at, created_at`.

## Setup
```bash
cd server/license-server
node cli.js keygen --out-dir /etc/nova-license     # creates the ACTIVATION key pair; prints the PUBLIC key (→ ACTIVATION_PUBLIC_KEY in the app build)
cp .env.example .env                                # fill LICENSE_PUBLIC_KEY, ACTIVATION_PRIVATE_KEY_FILE, DATABASE_PATH
set -a; . ./.env; set +a; node server.js
```
* Put it behind a TLS reverse proxy (nginx / Caddy) – the app refuses non-HTTPS URLs. Set `TRUST_PROXY=1` behind it.
* Back up the SQLite file (it is the record of which device owns which license) and the activation private key (offline).
* Losing the activation private key means every already-activated device keeps working only until it reinstalls; rotate by shipping a new app with a new `ACTIVATION_PUBLIC_KEY`.

## Operator CLI
```bash
node cli.js import ../../tools/license-generator/issued/*.record.json   # register freshly issued licenses (no license codes are stored)
node cli.js revoke NL-XXXXXXXX…      node cli.js show NL-…      node cli.js list
```

## Tests
`npm test` (12 tests: binding, re-activation, other device, unknown/revoked license, tampered code, atomic concurrent binding, rate limit, receipts, privacy of the DB).
