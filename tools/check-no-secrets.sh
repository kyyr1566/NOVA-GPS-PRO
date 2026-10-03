#!/usr/bin/env bash
# Fails if anything that must never be in Git is tracked (or about to be added): private keys, keystores, .env files,
# databases, issued customer licenses, credentials/tokens. Run locally before pushing; CI runs it on every push / PR.
#   tools/check-no-secrets.sh
set -u
cd "$(git rev-parse --show-toplevel)" || exit 2
fail=0
report() { echo "::error::$1"; echo "  $2"; fail=1; }

# files in the index plus files that would be added (untracked, not ignored)
files=$( { git ls-files; git ls-files --others --exclude-standard; } | sort -u )

# 1) forbidden file names / locations
bad=$(printf '%s\n' "$files" | grep -E '(^|/)\.env($|\.)|\.(pem|key|p8|p12|pfx|jks|keystore|db|db-wal|db-shm|sqlite|sqlite3)$|(^|/)(keystore|signing|local)\.properties$|(^|/)issued/|\.license\.txt$|\.record\.json$|(^|/)node_modules/' | grep -vE '(^|/)\.env\.example$' || true)
[ -n "$bad" ] && report "forbidden secret-like files are tracked" "$(printf '%s\n' "$bad" | sed 's/^/    /')"

# 2) forbidden content (file contents are never printed, only the file names)
scan() { # $1 = description, $2 = regex, $3.. = extra git-grep pathspecs
  local desc=$1 re=$2; shift 2
  local hits
  hits=$(printf '%s\n' "$files" | xargs -d '\n' -r git grep --untracked -IlE -e "$re" -- "$@" 2>/dev/null | grep -v '^tools/check-no-secrets.sh$' || true)
  [ -n "$hits" ] && report "$desc" "$(printf '%s\n' "$hits" | sed 's/^/    /')"
  return 0
}
scan "private key block found"        '-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----'
scan "access token / cloud key found" 'ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[abp]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}'
scan "literal signing password found" '(storePassword|keyPassword)[[:space:]]*=[[:space:]]*"[^"]+"'
# a real customer license / receipt must never be committed (the one throw-away test fixture is allow-listed)
scan "license code or receipt committed" 'NOVA(ACT)?1\.[A-Za-z0-9_-]{60,}\.[A-Za-z0-9_-]{60,}' ':!app/src/test/java/com/nova/gpspro/license/NodeInteropFixture.kt'

if [ $fail -ne 0 ]; then echo "Secret check FAILED – remove these from Git (git rm --cached) and rotate anything that was exposed."; exit 1; fi
echo "Secret check passed."
