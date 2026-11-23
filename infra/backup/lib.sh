# Shared SQL for backup.sh and restore.sh (sourced, not run).

# Latest successful migration.
FLYWAY_VERSION_SQL="SELECT coalesce((SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1), 'none')"

# "<table>=<rows>" for every table in the public schema, sorted. Needs row security off: run as a
# superuser or a BYPASSRLS role, otherwise PostgreSQL refuses instead of silently counting less.
TABLE_COUNTS_SQL="SET row_security = off; SELECT table_name || '=' || (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name"

# Runs SQL inside an exported snapshot (same data as the dump).
in_snapshot() {
  local snapshot="$1" sql="$2"
  psql -X -q -At -v ON_ERROR_STOP=1 <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET TRANSACTION SNAPSHOT '$snapshot';
$sql;
COMMIT;
SQL
}

# Runs SQL against the current PGDATABASE.
run_sql() {
  psql -X -q -At -v ON_ERROR_STOP=1 -c "$1"
}
