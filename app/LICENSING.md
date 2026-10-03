# Licensing – build configuration

The app needs three **public** settings. A *Release* build fails if any is missing or malformed; a Debug build without them
starts but **cannot activate anything** (fail closed – there is no bypass or mock verifier in any build type).

| Setting | Meaning |
|---|---|
| `LICENSE_SERVER_BASE_URL` | `https://` URL of the activation server (no trailing path, no default) |
| `LICENSE_PUBLIC_KEY` | Base64 Ed25519 **public** key printed by `tools/license-generator keygen` |
| `ACTIVATION_PUBLIC_KEY` | Base64 Ed25519 **public** key printed by `server/license-server/cli.js keygen` |

Resolution order: `-P<NAME>=…` Gradle property → environment variable → `local.properties` (git-ignored).
In GitHub Actions they are read from repository *Variables* (`vars.*`; they are not secrets). Private keys are never needed by the app build.

## Release build & signing
`./gradlew assembleRelease` is refused unless the three settings above are valid **and** `LICENSE_SERVER_BASE_URL`
is a real public HTTPS host (localhost, loopback/private addresses and placeholder domains such as `example.com` are rejected).
R8 (`isMinifyEnabled = true`) runs on every release build.

Signing material is supplied **from outside Git** – `-P<NAME>`, environment variable, or `keystore.properties` (git-ignored):

| Setting | Meaning |
|---|---|
| `NOVA_RELEASE_STORE_FILE` | path of the keystore (must be **outside** the project folder) |
| `NOVA_RELEASE_STORE_PASSWORD` / `NOVA_RELEASE_KEY_ALIAS` / `NOVA_RELEASE_KEY_PASSWORD` | its credentials |

With none of them set the release APK is produced **unsigned** (`app-release-unsigned.apk`) and must be signed
(`apksigner`) before installation; a partial configuration fails the build. `*.jks`, `*.keystore` and `keystore.properties` are git-ignored.

Note: the device hash is derived from ANDROID_ID, which Android scopes per app-signing key. A license activated by a
build signed with the *debug* key will therefore not match the same app signed with the *release* key on that phone;
test licenses with the final signing key.
