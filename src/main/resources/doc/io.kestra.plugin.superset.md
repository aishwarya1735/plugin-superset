# Apache Superset

Orchestrate Apache Superset business intelligence assets, dataset refreshes, chart queries, and dashboard exports.

## Authentication

All tasks authenticate against Apache Superset using JWT tokens obtained via `POST /api/v1/security/login`. 
Write operations automatically request and supply an `X-CSRFToken` header.

Supply the following connection properties:
- `url`: Superset base API URL (e.g. `https://superset.example.com/api/v1`).
- `username`: Account username.
- `password`: Account password (marked as a secret).
- `provider`: Authentication provider (defaults to `db`).

## Tasks

- `io.kestra.plugin.superset.datasets.Refresh`: Refreshes dataset metadata and columns.
- `io.kestra.plugin.superset.datasets.List`: Lists available datasets.
- `io.kestra.plugin.superset.dashboards.List`: Lists dashboards with pagination.
- `io.kestra.plugin.superset.dashboards.Export`: Exports dashboards as a `.zip` backup file to Kestra internal storage.
- `io.kestra.plugin.superset.charts.Data`: Queries chart data and saves result rows to internal storage.

## Triggers

- `io.kestra.plugin.superset.dashboards.Trigger`: Polls Superset dashboards and triggers execution when dashboards are created or modified, using namespace Key-Value store watermarks.
