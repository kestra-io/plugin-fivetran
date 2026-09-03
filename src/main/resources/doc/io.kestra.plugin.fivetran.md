# How to use the Fivetran plugin

Trigger Fivetran connector syncs from Kestra flows and optionally wait for completion.

## Authentication

Set `apiKey` and `apiSecret` (both required) for HTTP Basic authentication against the Fivetran API. Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and set connection properties on each task.

## Tasks

`connectors.Sync` triggers a sync for a `connectorId` (required) and waits for completion by default (`wait: true`). Set `force: true` to cancel any active sync before starting a new one. By default a running sync is left as-is and the task skips. Cap wait time with `maxDuration` (default 60 minutes).

While waiting, the connector status is polled every `pollFrequency` (default 5 seconds). Transient errors during polling (429, 5xx, read timeouts, and refused connections) are retried and do not fail the task, since the sync keeps running on Fivetran regardless. `maxAttempts` (default 3, total attempts including the first) and `initialRetryDelay` (default 1s) tune the per-request retry.

`connectors.Status` reads the current status of one or more connectors given a `connectorIds` list (required), with a single GET per connector and no sync triggered. Output is a `connectors` map keyed by connector ID, each entry exposing sync/setup/schema state, `succeededAt`/`failedAt`, the derived `completedDate`/`hasFailed`, and a `fresh` verdict.

A connector is `fresh` when its last sync succeeded within `syncFrequency` (in minutes, as reported by Fivetran) plus an optional `freshnessBuffer` (default `PT0S`) of now. `fresh` is `null` when Fivetran reports no `syncFrequency` for the connector, since freshness cannot then be computed.

By default (`wait: true`), `Status` acts as a freshness gate: it polls every `pollFrequency` (default 30 seconds) until all requested connectors are fresh, up to `maxDuration` (default 1 hour), and fails with a clear message naming the still-stale connectors if the deadline is reached. A paused connector, or one whose setup is not `connected` (`broken`, `incomplete`, bad auth), can never become fresh on its own, so the gate fails fast on it instead of polling forever; set `allowTerminal: true` to instead report such a connector as not-fresh and let the gate wait out `maxDuration`. Set `wait: false` for a single read-only snapshot that never throws on stale, paused, or broken connectors.

`connectors.Sync` returns the connector ID and, when it waited, the `succeededAt` of the sync it observed. `succeededAt` is null with `wait: false`, since the sync is still running on Fivetran when the task returns.

When `assets.enableAuto` is set, both `Sync` and `Status` emit lineage. Emitting from both matters because a connector on Fivetran's own schedule is never triggered through `Sync`, and one driven from Kestra is often never read through `Status`.

Per connector they emit one asset per synced table, with the id `database.schema.name` and metadata `system`, `database`, `schema`, `name` and `connectorId`. This is the same id convention plugin-dbt uses, so a Fivetran-loaded table and the dbt model reading it resolve to a single asset and the lineage edge forms with no manual mapping. The table list comes from the connector's schema config, skipping disabled schemas and tables, and the database name from the connector's destination. `system` names the warehouse rather than this plugin, matching what dbt records for the same table.

The connector-level asset is still emitted alongside, keyed by the Fivetran destination schema, so existing asset IDs are unchanged and connector-level sync state keeps somewhere to live.

Some destinations report no database name (Fivetran-managed destinations are the known case). Those connectors emit the connector-level asset only, and log a warning saying so. Nothing on the lineage path can fail a task whose sync succeeded: a failed destination or schema read, or an `assets.enableAuto` expression that will not render, warns and moves on.
