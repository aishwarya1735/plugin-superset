# How to use the Apache Superset plugin

Orchestrate Apache Superset chart queries, dataset metadata refreshes, dashboard backups, and modification triggers from Kestra flows.

## Authentication

Every task and trigger authenticates against the Apache Superset REST API via `POST /api/v1/security/login` to obtain a short-lived JWT access token, and automatically fetches an `X-CSRFToken` from `GET /api/v1/security/csrf_token/` for write requests.

Store credentials using [Kestra secrets](https://kestra.io/docs/concepts/secret) and share common connection properties across tasks with [plugin defaults](https://kestra.io/docs/concepts/plugin-defaults):

- `url`: Superset API base URL including `/api/v1` (e.g. `https://superset.example.com/api/v1`).
- `username`: Superset username.
- `password`: Superset password.
- `provider`: Authentication provider (`db`, `ldap`, `oauth`, or `oid`; defaults to `db`).

## Tasks

- `io.kestra.plugin.superset.charts.Data`: Executes a chart query (`chartPayload` required) via `POST /chart/data` and outputs or stores the resulting rows (`fetchType`).
- `io.kestra.plugin.superset.dashboards.List`: Pages through all dashboards via `GET /dashboard/` (`fetchType` optional).
- `io.kestra.plugin.superset.dashboards.Export`: Exports the specified dashboards (`dashboardIds` required) as a `.zip` archive to internal storage via `GET /dashboard/export/`.
- `io.kestra.plugin.superset.datasets.List`: Pages through all datasets via `GET /dataset/` (`fetchType` optional).
- `io.kestra.plugin.superset.datasets.Refresh`: Refreshes column metadata for a dataset (`datasetId` required) via `PUT /dataset/{id}/refresh`.

## Triggers

- `io.kestra.plugin.superset.dashboards.Trigger`: Polls `GET /dashboard/` at a configurable `interval` and fires when dashboards are created or modified since the watermark persisted in the namespace KV Store.
