#!/usr/bin/env bash
# Runs the restore drill against the local Compose database (development only). The PostgreSQL
# container already has pg_dump, pg_restore, psql and gpg. Usage: infra/backup/drill-local.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
container="$(docker compose ps -q postgres)"
[[ -n "$container" ]] || { echo "start the database first: docker compose up -d postgres" >&2; exit 2; }
docker exec "$container" rm -rf /tmp/gcare-backup
docker cp infra/backup "$container:/tmp/gcare-backup"
docker exec -e PGUSER="${DB_USERNAME:-gcare}" -e PGPASSWORD="${DB_PASSWORD:-gcare}" -e PGHOST=localhost \
  -e PGDATABASE="${DB_NAME:-gcare_hms_dev}" "$container" bash /tmp/gcare-backup/drill.sh
