#!/usr/bin/env bash
# GCare HMS database restore with verification (task 5.4; docs/23).
#
# Usage: restore.sh <backup .dump.gpg>   (its .manifest must sit beside it)
#
# 1. Checks the encrypted file against the SHA-256 in the manifest (refuses on mismatch).
# 2. Refuses unless the target database is empty (never restores over live data).
# 3. Decrypts with the operator's private key (GNUPGHOME) straight into pg_restore.
# 4. Verifies the migration version and the row count of every table against the manifest.
# Prints the elapsed time (for the drill record) and exits non-zero on any difference.
#
# Required environment: PGHOST, PGPORT, PGDATABASE (the empty target), PGUSER (+ password), and a
# GNUPGHOME holding the private key. The restoring user becomes the owner of restored objects.
# GCARE_APP_LOGIN: the login role the application connects as. Role memberships are cluster-wide and
# not in the dump, so on a new server the application could not run a single query without
# "GRANT gcare_app TO <login>". Set it for every real restore; drills that never start the
# application may leave it empty (a warning is printed).
set -euo pipefail

backup="${1:?usage: restore.sh <backup .dump.gpg>}"
manifest="${backup%.dump.gpg}.manifest"
[[ -f "$backup" && -f "$manifest" ]] || { echo "backup or manifest not found" >&2; exit 2; }
: "${PGDATABASE:?set PGDATABASE (the empty target database)}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
start="$(date +%s)"

expected_sha="$(grep '^sha256=' "$manifest" | cut -d= -f2)"
actual_sha="$(sha256sum "$backup" | cut -d' ' -f1)"
if [[ "$expected_sha" != "$actual_sha" ]]; then
  echo "checksum mismatch: the backup file is damaged or not the one in the manifest" >&2
  exit 3
fi

tables="$(run_sql "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
if [[ "$tables" != "0" ]]; then
  echo "target database $PGDATABASE is not empty ($tables tables); restore only into an empty database" >&2
  exit 4
fi

# The application role is cluster-wide, so it is not in the dump; grants in the dump need it.
run_sql "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gcare_app') THEN CREATE ROLE gcare_app NOLOGIN; END IF; END \$\$"
if [[ -n "${GCARE_APP_LOGIN:-}" ]]; then
  [[ "$GCARE_APP_LOGIN" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || { echo "GCARE_APP_LOGIN is not a plain role name" >&2; exit 2; }
  run_sql "GRANT gcare_app TO \"$GCARE_APP_LOGIN\""
else
  echo "warning: GCARE_APP_LOGIN not set; grant gcare_app to the application's login before starting it" >&2
fi

gpg --batch --quiet --decrypt "$backup" | pg_restore --no-owner --exit-on-error --dbname="$PGDATABASE"

failures=0
expected_version="$(grep '^flyway_version=' "$manifest" | cut -d= -f2)"
actual_version="$(run_sql "$FLYWAY_VERSION_SQL")"
if [[ "$expected_version" != "$actual_version" ]]; then
  echo "migration version differs: manifest $expected_version, restored $actual_version" >&2
  failures=$((failures + 1))
fi
diff <(grep '^table\.' "$manifest" | sed 's/^table\.//') <(run_sql "$TABLE_COUNTS_SQL") >&2 \
  || { echo "row counts differ from the manifest (lines above: < manifest, > restored)" >&2; failures=$((failures + 1)); }

elapsed=$(( $(date +%s) - start ))
echo "restored=$PGDATABASE"
echo "flyway_version=$actual_version"
echo "tables=$(grep -c '^table\.' "$manifest")"
echo "seconds=$elapsed"
if (( failures > 0 )); then
  echo "RESTORE VERIFICATION FAILED" >&2
  exit 5
fi
echo "verified=true"
