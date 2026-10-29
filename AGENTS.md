# Kestra Apache Superset Plugin

Integration plugin (`io.kestra.plugin.superset`) connecting Kestra flows to Apache Superset via its `/api/v1` REST API.

## Subpackages, Tasks & Triggers

- `io.kestra.plugin.superset.charts`
  - `Data`: Runs `POST /chart/data` and streams or returns result rows via `fetchType`.
- `io.kestra.plugin.superset.dashboards`
  - `List`: Pages through `GET /dashboard/` and outputs typed `DashboardItem` records.
  - `Export`: Exports dashboards via `GET /dashboard/export/` as a ZIP file in internal storage.
  - `Trigger`: Polls `GET /dashboard/` ordered by `changed_on` and persists watermarks in the namespace KV Store.
- `io.kestra.plugin.superset.datasets`
  - `List`: Pages through `GET /dataset/` and outputs typed `DatasetItem` records.
  - `Refresh`: Refreshes dataset column metadata via `PUT /dataset/{id}/refresh` using JWT + CSRF authentication.
