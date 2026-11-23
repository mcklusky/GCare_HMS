#!/usr/bin/env bash
# GCare HMS database backup (task 5.4; docs/23).
#
# Writes <BACKUP_DIR>/gcare-<db>-<UTC stamp>.dump.gpg (pg_dump custom format, streamed straight into
# gpg public-key encryption: no plaintext dump touches the disk) and a manifest beside it with the
# migration version, the row count of every table (taken in the same snapshot as the dump) and the
# SHA-256 of the encrypted file. Restoring needs the private key, which is kept off this server.
#
# Required environment (no built-in values; set them from the approved backup policy):
#   PGHOST, PGPORT, PGDATABASE, PGUSER (+ PGPASSWORD or ~/.pgpass)  database to back up. The user
#       must be able to read every table with row security off (superuser or BYPASSRLS).
#   BACKUP_DIR              where backups are written
#   BACKUP_GPG_RECIPIENT    fingerprint of the operator's public key (in GNUPGHOME's keyring)
#   BACKUP_RETENTION_DAYS   backups older than this are deleted after a successful backup
# Optional:
#   BACKUP_METRICS_FILE     Prometheus textfile to write (node_exporter textfile collector)
#   BACKUP_MAX_AGE_SECONDS  approved maximum backup age (RPO); exported for the alert rule
# How often this runs is decided by the scheduler (cron, systemd timer) per the approved RPO.
set -euo pipefail

: "${PGDATABASE:?set PGDATABASE}"
: "${PGUSER:?set PGUSER}"
: "${BACKUP_DIR:?set BACKUP_DIR}"
: "${BACKUP_GPG_RECIPIENT:?set BACKUP_GPG_RECIPIENT (operator public key fingerprint)}"
: "${BACKUP_RETENTION_DAYS:?set BACKUP_RETENTION_DAYS from the approved retention policy}"
[[ "$BACKUP_RETENTION_DAYS" =~ ^[0-9]+$ ]] || { echo "BACKUP_RETENTION_DAYS must be a whole number" >&2; exit 2; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="$BACKUP_DIR/gcare-${PGDATABASE}-${stamp}"
start="$(date +%s)"

# A failed run leaves nothing that looks like a backup (a truncated but valid .dump.gpg would).
completed=0
cleanup() {
  if [[ "$completed" != 1 ]]; then
    rm -f "$base.dump.gpg" "$base.manifest.tmp" "$base.manifest"
  fi
}
trap cleanup EXIT

# Hold one read-only snapshot open so the counts and the dump see exactly the same data.
coproc SNAP { psql -X -q -At -v ON_ERROR_STOP=1; }
echo "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();" >&"${SNAP[1]}"
read -r snapshot <&"${SNAP[0]}"
[[ -n "$snapshot" ]] || { echo "could not export a snapshot" >&2; exit 1; }

# Assignments, not command substitutions inside echo: set -e stops the run if a query fails.
flyway_version="$(in_snapshot "$snapshot" "$FLYWAY_VERSION_SQL")"
table_counts="$(in_snapshot "$snapshot" "$TABLE_COUNTS_SQL")"
[[ -n "$flyway_version" && -n "$table_counts" ]] || { echo "could not read the manifest data" >&2; exit 1; }
{
  echo "format=gcare-backup-1"
  echo "created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "database=$PGDATABASE"
  echo "flyway_version=$flyway_version"
  echo "$table_counts" | sed 's/^/table./'
} > "$base.manifest.tmp"

pg_dump --format=custom --snapshot="$snapshot" --no-password \
  | gpg --batch --yes --quiet --trust-model always --recipient "$BACKUP_GPG_RECIPIENT" \
        --output "$base.dump.gpg" --encrypt
echo "COMMIT;" >&"${SNAP[1]}"
exec {SNAP[1]}>&-
wait "$SNAP_PID" 2>/dev/null || true

{
  cat "$base.manifest.tmp"
  echo "bytes=$(wc -c < "$base.dump.gpg" | tr -d ' ')"
  echo "sha256=$(sha256sum "$base.dump.gpg" | cut -d' ' -f1)"
} > "$base.manifest"
rm -f "$base.manifest.tmp"
completed=1
elapsed=$(( $(date +%s) - start ))

# Retention only after a successful backup, and never the one just written.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'gcare-*.dump.gpg' -o -name 'gcare-*.manifest' \) \
  -mtime "+$BACKUP_RETENTION_DAYS" ! -name "$(basename "$base").*" -print -delete | sed 's/^/deleted /'

if [[ -n "${BACKUP_METRICS_FILE:-}" ]]; then
  {
    echo "# HELP gcare_backup_last_success_timestamp_seconds Time of the last successful backup."
    echo "# TYPE gcare_backup_last_success_timestamp_seconds gauge"
    echo "gcare_backup_last_success_timestamp_seconds{database=\"$PGDATABASE\"} $(date +%s)"
    echo "gcare_backup_duration_seconds{database=\"$PGDATABASE\"} $elapsed"
    echo "gcare_backup_size_bytes{database=\"$PGDATABASE\"} $(wc -c < "$base.dump.gpg" | tr -d ' ')"
    if [[ -n "${BACKUP_MAX_AGE_SECONDS:-}" ]]; then
      echo "gcare_backup_max_age_seconds{database=\"$PGDATABASE\"} $BACKUP_MAX_AGE_SECONDS"
    fi
  } > "$BACKUP_METRICS_FILE.tmp"
  mv "$BACKUP_METRICS_FILE.tmp" "$BACKUP_METRICS_FILE"
fi

echo "backup=$base.dump.gpg"
echo "manifest=$base.manifest"
echo "seconds=$elapsed"
