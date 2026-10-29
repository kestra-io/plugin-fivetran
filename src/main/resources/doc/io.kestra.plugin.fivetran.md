# How to use the Fivetran plugin

Trigger Fivetran connector syncs from Kestra flows and optionally wait for completion.

## Authentication

Set `apiKey` and `apiSecret` (both required) for HTTP Basic authentication against the Fivetran API. Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and set connection properties on each task.

## Tasks

`connectors.Sync` triggers a sync for a `connectorId` (required) and waits for completion by default (`wait: true`). Set `force: true` to cancel any active sync before starting a new one. By default a running sync is left as-is and the task skips. Cap wait time with `maxDuration` (default 60 minutes).

While waiting, the connector status is polled every `pollFrequency` (default 5 seconds). Transient errors during polling (429, 5xx, read timeouts, and refused connections) are retried and do not fail the task, since the sync keeps running on Fivetran regardless. `maxAttempts` (default 3, total attempts including the first) and `initialRetryDelay` (default 1s) tune the per-request retry.

The per-request retry is the plugin's own: `maxAttempts` (default 3, total attempts including the first) and `initialRetryDelay` (default 1s) shape it, unless `options.retry` is set, in which case that policy replaces both. It is applied once by the plugin and never stacked with the HTTP client's own retry. Only read calls are retried, whatever the `retryOnStatusCodes`, `retryOnStatusCodesByMethod` and `retryableTransportFailureMethods` fields of `options` say, so the sync trigger itself is never sent twice.

With `wait: true`, `Sync` resumes the sync it triggered instead of triggering another when its task run is retried with the default `retry.behavior: RETRY_FAILED_TASK`, or resubmitted after a lost worker: it saves a task-run checkpoint once the sync is triggered and, on the next attempt, skips the trigger and keeps polling that sync. This holds however many attempts time out, as long as each one starts within twice `maxDuration` of the previous attempt's start or timeout; past that the checkpoint is stale and a new sync is triggered. Once a poll sees the sync end, successful or failed, a retry triggers a new one. `retry.behavior: CREATE_NEW_EXECUTION` starts a new task run, so it always triggers a new sync. Resuming needs Kestra 2.1 or later, which this plugin requires.

Set `reattach: true` to adopt a sync already in progress on the connector instead of triggering a duplicate. Adoption matches on the connector's own `sync_state: syncing`, not on this execution's identity, so it adopts *any* sync currently running on the connector, including one Fivetran started itself on its own schedule. This makes retries, replays, manual re-runs, and two executions driving the same connector all converge on the one sync actually running, instead of leaving it to timing whether they wait on it or start a second one. `reattachMaxAge` optionally caps how old that in-progress sync can be before it is treated as stale: it is cancelled and a new sync is started (the trigger is sent with `force: true`) instead of adopting it -- the same forced path a connector that has never completed a sync always takes once `reattachMaxAge` is set; default is unbounded. `force: true` always takes precedence over `reattach` and starts a new sync regardless. The output's `reattached` flag reports which happened.

`connectors.Status` reads the current status of one or more connectors given a `connectorIds` list (required), with a single GET per connector and no sync triggered. Output is a `connectors` map keyed by connector ID, each entry exposing sync/setup/schema state, `succeededAt`/`failedAt`, the derived `completedDate`/`hasFailed`, and a `fresh` verdict.

A connector is `fresh` when its last sync succeeded within `syncFrequency` (in minutes, as reported by Fivetran) plus an optional `freshnessBuffer` (default `PT0S`) of now. `fresh` is `null` when Fivetran reports no `syncFrequency` for the connector, since freshness cannot then be computed.

By default (`wait: true`), `Status` acts as a freshness gate: it polls every `pollFrequency` (default 30 seconds) until all requested connectors are fresh, up to `maxDuration` (default 1 hour), and fails with a clear message naming the still-stale connectors if the deadline is reached. A paused connector, or one whose setup is not `connected` (`broken`, `incomplete`, bad auth), can never become fresh on its own, so the gate fails fast on it instead of polling forever; set `allowTerminal: true` to instead report such a connector as not-fresh and let the gate wait out `maxDuration`. Set `wait: false` for a single read-only snapshot that never throws on stale, paused, or broken connectors.

`connectors.Sync` returns the connector ID and, when it waited, the `succeededAt` of the sync it observed. `succeededAt` is null with `wait: false`, since the sync is still running on Fivetran when the task returns. It also returns a `freshness` object (`syncState`, `setupState`, `updateState`, `paused`, `succeededAt`, `failedAt`, `syncFrequency`, `fresh`, `alertCount`, `alertCodes`). With `wait: true` it is read after the sync completed and `fresh` is `true`. With `wait: false` it is the snapshot taken before the sync was triggered and `fresh` is `null`.

Both tasks also output a per-table breakdown of each connector: `tables` (a `kestra://` URI of an Ion file with one row per table), `tableCount` and `enabledTableCount`. Each row holds `schema`, `schemaInDestination`, `schemaEnabled`, `table`, `tableInDestination`, `enabled`, `syncMode` and `enabledPatchAllowed`. Disabled schemas and tables are included with their flags; `enabledTableCount` only counts tables that are enabled in an enabled schema. On `Status` these sit on each entry of `connectors`, alongside `updateState`, `alertCount` and `alertCodes`. Pass `{{ outputs.<taskId>.tables }}` to any downstream task that reads an Ion file, such as the Serdes conversion tasks.

For example, sync a connector, then log its alert count and table counts and hand the breakdown file to a downstream task:

```yaml
tasks:
  - id: sync
    type: io.kestra.plugin.fivetran.connectors.Sync
    apiKey: "{{ secret('FIVETRAN_API_KEY') }}"
    apiSecret: "{{ secret('FIVETRAN_API_SECRET') }}"
    connectorId: "connector_id"

  - id: report
    type: io.kestra.plugin.core.log.Log
    message: "{{ outputs.sync.freshness.alertCount }} alerts, {{ outputs.sync.enabledTableCount }} enabled tables, breakdown at {{ outputs.sync.tables }}"
```

On `Sync`, the top-level `succeededAt` is the completion of the sync the task observed (null with `wait: false`), while `freshness.succeededAt` is the connector's last known success from the snapshot, which with `wait: false` predates the triggered sync.

The breakdown costs one extra `GET /v2/connectors/{id}/schemas` per connector per run, even when `assets.enableAuto` is off. The call is shared with lineage, so enabling lineage adds no second one. If the read fails (for example an API key without permission to read schemas), the task logs a warning naming the connector and HTTP status and still succeeds, with `tables`, `tableCount` and `enabledTableCount` null. There is no property to turn the read off.

When `assets.enableAuto` is set, both `Sync` and `Status` emit lineage. Emitting from both matters because a connector on Fivetran's own schedule is never triggered through `Sync`, and one driven from Kestra is often never read through `Status`.

Per connector they emit one asset per synced table, with the id `database.schema.name` and metadata `system`, `database`, `schema`, `name`, `connectorId`, `lastSyncedAt`, `syncState`, `service`, `sourceSchema`, `sourceTable` and `syncMode` (`lastSyncedAt`, `syncState`, `service` and `syncMode` omitted when Fivetran reports none). This is the same id convention plugin-dbt uses, so a Fivetran-loaded table and the dbt model reading it resolve to a single asset and the lineage edge forms with no manual mapping. The table list comes from the connector's schema config, skipping disabled schemas and tables, and the database name from the connector's destination. `system` names the warehouse rather than this plugin, matching what dbt records for the same table.

Each table asset has the source table as its input, emitted as one bundle per table. For database sources (PostgreSQL, MySQL, SQL Server, Oracle, Snowflake, Redshift and BigQuery services) the input is a `Table` asset `database.sourceSchema.sourceTable` (`project.dataset.table` for BigQuery, `sourceSchema.sourceTable` for MySQL, where the Fivetran schema key is the database), with names taken from the schema config keys rather than `name_in_destination`. The database is read from the connector `config`; only that location key is read, never logged. Any other connector, or one whose config has no usable (or only masked) database name, gets an `External` input `connectorId.sourceSchema.sourceTable`.

The connector-level asset is still emitted alongside, with no input, keyed by the Fivetran destination schema, so existing asset IDs are unchanged and connector-level sync state keeps somewhere to live.

Some destinations report no database name (Fivetran-managed destinations are the known case). Those connectors emit the connector-level asset only, and log a warning saying so. Nothing on the lineage path can fail a task whose sync succeeded: a failed destination or schema read, or an `assets.enableAuto` expression that will not render, warns and moves on. A failure while building or emitting an asset follows `assets.assetFailureBehavior`: `FAIL` fails the task, `WARN` (the default) ends it in WARNING, `IGNORE` logs at debug level. `Sync` with `wait: false` emits no assets, since the sync has not finished when it returns.
