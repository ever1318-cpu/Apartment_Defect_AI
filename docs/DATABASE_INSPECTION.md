# PostgreSQL Database Inspection

The database inspection commands provide a read-only view of an existing
PostgreSQL database. They do not accept arbitrary SQL, modify schema objects, or
run `COUNT(*)` across application tables.

## Installation

Install the optional PostgreSQL driver in the project environment:

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[database]"
```

`psql` is not required. The driver is imported only when a database command is
executed, so core, inference, training, and serving imports remain independent.

## Configuration

Set credentials only in process environment variables or an approved secret
manager:

```powershell
$env:APARTMENT_DB_HOST = "database.example.com"
$env:APARTMENT_DB_PORT = "5432"
$env:APARTMENT_DB_NAME = "apartments"
$env:APARTMENT_DB_USER = "read_only_inspector"
$env:APARTMENT_DB_PASSWORD = "<from-secret-manager>"
$env:APARTMENT_DB_SSLMODE = "require"
```

Port `5432`, SSL mode `require`, and a ten-second connection timeout are the
defaults. Host, database, user, and password are required. Passwords and complete
connection strings are never printed or included in JSON reports.

Use a PostgreSQL role restricted to `CONNECT` and catalog/application metadata
reads. Both commands explicitly set their transaction to read-only.

## Connection test

```powershell
apartment-data vision-db-test
apartment-data vision-db-test --json
```

The command queries `current_database()`, `current_user`, and `version()`.
Exit codes are:

- `0`: success
- `2`: missing or invalid configuration
- `3`: connection or authentication failure
- `4`: query failure after connection

Errors are intentionally sanitized. Consult secured server and network logs for
details rather than adding credentials or raw driver exceptions to application
logs.

## Catalog inspection

```powershell
apartment-data vision-db-inspect
apartment-data vision-db-inspect --schema public --table defect_images
apartment-data vision-db-inspect --include-views --top-candidates 30
apartment-data vision-db-inspect --json `
  --output workspace/db-inspection/backupdb-schema.json
```

The report contains user schemas, tables and views, columns, nullability,
defaults, primary keys, foreign keys, and PostgreSQL planner row estimates.
`pg_catalog`, `information_schema`, temporary schemas, and TOAST schemas are
always excluded. Views are included only with `--include-views`. `--schema` and
`--table` are passed as query values, not interpolated SQL. There is no
arbitrary SQL option and no application-table `COUNT(*)` query.

Image and label candidates are detected from column names using English and
Korean keywords such as `image`, `file`, `path`, `label`, `defect`, `이미지`,
`경로`, `라벨`, and `하자`. Candidate detection is an inventory aid, not a
semantic or privacy classification.

Candidates are scored from both table and column names, with matched categories,
columns, and reasons recorded in the report.

`--output` writes formatted JSON atomically and writes `backupdb-summary.txt`
beside it. The default directory is `workspace/db-inspection`. Reports contain
the configured host, database, user, and SSL mode, but never the password or a
full DSN.
