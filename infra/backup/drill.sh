#!/usr/bin/env bash
# Restore drill (task 5.4; docs/23): back up the database, restore it into a new scratch database on
# the same server, verify it, and drop the scratch database. Uses a throwaway key pair made for the
# drill only, so it proves the procedure without touching the operator's real keys or backups.
#
# Needs PGHOST/PGPORT/PGUSER/PGPASSWORD of a user that may create databases and read with row
# security off, and PGDATABASE (the database to back up). Prints the drill record on stdout.
set -euo pipefail
: "${PGDATABASE:?set PGDATABASE (the database to back up)}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

work="$(mktemp -d)"
scratch="gcare_restore_drill_$(date -u +%Y%m%d%H%M%S)"
cleanup() {
  PGDATABASE=postgres psql -X -q -c "DROP DATABASE IF EXISTS $scratch" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --passphrase '' --quick-gen-key "GCare restore drill <drill@example.invalid>" default default never
fingerprint="$(gpg --batch --list-keys --with-colons | awk -F: '/^fpr:/ {print $10; exit}')"

echo "drill_started=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "source=$PGDATABASE"
BACKUP_DIR="$work/backups" BACKUP_GPG_RECIPIENT="$fingerprint" BACKUP_RETENTION_DAYS=1 \
  bash "$HERE/backup.sh" | sed 's/^/backup_/'
file="$(ls "$work"/backups/*.dump.gpg)"

PGDATABASE=postgres psql -X -q -v ON_ERROR_STOP=1 -c "CREATE DATABASE $scratch"
PGDATABASE="$scratch" bash "$HERE/restore.sh" "$file" | sed 's/^/restore_/'
echo "drill_result=PASSED"
