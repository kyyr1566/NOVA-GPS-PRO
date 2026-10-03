# NOVA GPS PRO – license generator (developer / seller tool)

Not part of the Android app. Creates LIFETIME licenses, signs them with an **offline Ed25519 private key** and renders
the QR code (it contains the license code and nothing else). Node ≥ 20, no runtime dependencies.

## Keys
```bash
node generate.js keygen --out-dir ~/secure/nova      # refuses to write inside a Git repo
```
* Prints the **public** key → `LICENSE_PUBLIC_KEY` in the Android build *and* in the activation server.
* The **private** key (`nova-license-private.pem`) never goes to GitHub, the app or the server. There is intentionally **no key in this repository**.
* The generator reads the key from `--key-file`, `LICENSE_PRIVATE_KEY_FILE` or `LICENSE_PRIVATE_KEY_PEM` only.

## Issue licenses
```bash
export LICENSE_PRIVATE_KEY_FILE=~/secure/nova/nova-license-private.pem
node generate.js issue --count 10 --out issued         # add --ascii to preview the QR in the terminal
```
Per license (in `issued/`, git-ignored): `NL-….license.txt` (the code `NOVA1.<payload>.<signature>`), `NL-….png` (QR of that code),
`NL-….record.json` (database row for the server: no code, no secrets). Then register on the server:
`node ../../server/license-server/cli.js import issued/*.record.json`.

Payload (signed as a whole): `v, product=NOVA_GPS_PRO, licenseId (NL-+26 base32), licenseType=LIFETIME, issuedAt, deviceBinding=FIRST_ACTIVATION, minVersion, nonce (128-bit random)`.

## Check a code
`node generate.js verify <code|file> --public-key <BASE64>`

## Tests
`npm install && npm test` (11 tests incl. signing round-trips and QR decode with ZXing as a dev dependency).
