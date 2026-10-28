# Kestra Fivetran Plugin

## What

- Provides plugin components under `io.kestra.plugin.fivetran`.
- Includes classes such as `Sync`, `Status`, `SyncResponse`, `ConnectorStatusResponse`, `ConnectorResponse`.

## Why

- What user problem does this solve? Teams need to orchestrate Fivetran connectors to automate data movement in Kestra workflows from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Fivetran steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Fivetran.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `fivetran`

### Key Plugin Classes

- `io.kestra.plugin.fivetran.connectors.Sync`: triggers a connector sync and optionally waits for completion. Fails right away on a paused connector (Fivetran answers the trigger with 200 Success and starts nothing). A completion seen while Fivetran still reports `syncing` waits up to 1 minute (`CompletionWatch`) for the syncing state to end, then is accepted anyway so back-to-back syncs never hang the task; at `maxDuration` that completion is returned rather than failing. Returns the connector id, the `succeededAt` of the sync it observed when it waited, a `freshness` summary and the table breakdown. Emits lineage when `assets.enableAuto` is set and `wait` is true.
- `io.kestra.plugin.fivetran.connectors.Status`: reads the current status of one or more connectors via a single GET per connector, without triggering a sync. Computes a `fresh` verdict per connector from `succeededAt`/`syncFrequency`/`freshnessBuffer`, and by default (`wait: true`) polls until every connector is fresh or fails fast on a paused/broken connector, up to `maxDuration`. Also reports `updateState`, `alertCount`, `alertCodes` and the table breakdown per connector. Emits lineage when `assets.enableAuto` is set.

### Schema read and table breakdown

Both tasks read `GET /v2/connectors/{id}/schemas` once per connector, on the final connector state (after polling for `Status`), whatever `assets.enableAuto` is set to. `AbstractFivetranConnection.readSchemas` never throws: a failed read warns with the connector id and HTTP status (never the response body) and leaves `tables`/`tableCount`/`enabledTableCount` null. The one read is shared by `writeTableBreakdown` and `emitAssets`.

`writeTableBreakdown` writes one Ion row per table to internal storage with the `FileSerde` `OutputStream` overloads. Rows hold only `schema`, `schemaInDestination`, `schemaEnabled`, `table`, `tableInDestination`, `enabled`, `syncMode` and `enabledPatchAllowed`. Never add `columns`, `row_filter`, `parent_table`, `enabled_patch_settings.reason`, connector `config` or destination data. Disabled schemas and tables are included with their flags. `tableCount` counts every row, `enabledTableCount` counts tables that are enabled in an enabled schema. `ConnectorFreshness` is the nested summary on `Sync.Output`; `Status.ConnectorState` carries the same fields flat. Alert messages are never output, only codes.

### Plugin endpoint

`io.kestra.plugin.fivetran.endpoints.Tables` is a `PluginEndpoint` named `tables`, served by core at `/api/v1/{tenant}/plugins/{cls}/endpoints/tables/{executionId}/{taskRunId}` and called by the UI through the slot `fetchEndpoint` prop. It pages through the rows of the task run's `tables` breakdown file (`page`/`size`, size capped at 500), filtered by `q` (source or destination schema/table name, ignoring case) and `enabled` (same rule as `enabledTableCount`). On `Status`, `connectorId` picks the connector, defaulting to the first one.

- The file URI is read only from the task run's own outputs, never from a parameter, and only for Fivetran task runs (`Sync`: `connectorId` plus `freshness`; `Status`: a `connectors` entry with its `id`). Another task's files are never readable through it.
- Rows are rebuilt from the breakdown allow-list, so a field that is not one of the 8 breakdown fields can never be returned, whatever the file holds.
- A missing or unreadable file answers like an empty one. The endpoint never throws.

### Asset lineage

`AbstractFivetranConnection.emitAssets` is the one helper both tasks call, because a connector on Fivetran's own schedule is never triggered through `Sync` and one driven from Kestra is often never read through `Status`. Either task alone leaves half the graph empty.

Per connector it emits:

- One **table-grain** asset per synced table, id `database.schema.name`, metadata `system`/`database`/`schema`/`name`/`connectorId`/`lastSyncedAt`/`syncState`/`service`/`sourceSchema`/`sourceTable`/`syncMode`, emitted as its own `AssetEmit` with the source table as input (one bundle per table) and `partOf` set to the connector-grain asset, so the dependencies graph groups a schema's tables under it. The connector asset is emitted first so the parent exists. dbt writes the same table ids without `partOf`, and core keeps the stored `partOf` when an update carries none. `SourceLocation` resolves database sources from the connector type (`connector_type_id` on v2, falling back to `service`) and `config` (location key only, masked values ignored) to a `Table` input `database.sourceSchema.sourceTable` (`sourceSchema.sourceTable` for MySQL variants, `project.dataset.table` for BigQuery), names being the schema-config keys; anything else is an `External` input `connectorId.sourceSchema.sourceTable`. Connector `config` is `@ToString.Exclude` and never logged. `system` names the warehouse (the destination's `service`), not this plugin, because plugin-dbt writes the same asset id and sets `system` to the warehouse adapter. The spellings agree for `snowflake`, `databricks` and `redshift`, but not for BigQuery, which Fivetran calls `big_query` and dbt calls `bigquery`. Metadata is descriptive only, the id is what forms the edge. The Fivetran origin stays visible through `connectorId`. This is the same id convention plugin-dbt emits (`ResultParser.assetIdFor`), so a Fivetran-loaded table and the dbt model reading it resolve to a single node and the edge forms with no manual mapping. `database` comes from `GET /v2/destinations/{group_id}` (`config.database`, falling back to `catalog` then `project_id`), never from the group id: a group id is a Fivetran-internal identifier, not a warehouse database, so an id built on it would be table-grain but still in the wrong namespace. Tables come from `GET /v2/connectors/{id}/schemas`, using `name_in_destination` and skipping disabled schemas and tables.
- One **connector-grain** asset (own emit, no input, metadata `system`/`connectorId`/`schema`/`lastSyncedAt`/`syncState`), id still `groupId.schema` (falling back to `connectorId.schema` when `groupId` is absent), so upgrading renames nothing and connector-level freshness keeps somewhere to live. Typed `Dataset`, not `Table`, because it covers a whole destination schema rather than one table. Both types have existed since EE 1.2.0.

The destination read is skipped entirely when `assets.enableAuto` is off (the schema read is not, see above), and destinations are resolved once per group id per run. Asset build/emit failures follow `assets.assetFailureBehavior` (`FAIL` throws, `WARN` ends the task in WARNING through `Output.finalState()`, `IGNORE` logs at debug). After the first emit failure for a connector, its remaining emits are skipped with one summary line, so a queue outage does not cost one failing call and one log line per table, and the behavior is rendered once per run. An unsupported emitter (OSS) is a silent skip, and `Sync` with `wait: false` emits nothing. Otherwise nothing on the lineage path can fail the task: a failed destination or schema read, and an `enableAuto` expression that will not render, all warn and leave the connector asset in place. Lineage is metadata about the run, so it must never fail a sync that succeeded. The destination read is the one Fivetran response documented to carry raw credentials (Snowflake `password`/`private_key`, BigQuery `secret_key`), so its config is kept out of `toString` and its exception messages are never logged.

Ids over the core 150-character cap get a digest suffix rather than plain truncation. The table name is the last segment, so cutting the tail would land every table of one schema on the same id and silently collapse them into a single node.

Each id segment is sanitized on its own before being joined with `.`, so a `.` inside a raw segment (e.g. schema `google_sheets.destination`) can never be mistaken for the delimiter. The allowed set is `^[a-zA-Z0-9][a-zA-Z0-9._-]*`, the set the plugin has always built ids from. Newer cores also accept `:`, but it is still replaced rather than passed through so ids already emitted stay stable across upgrades.

### Plugin UI

`ui/` is a Vue 3 + Vite micro-frontend (same structure and dependency set as `plugin-airbyte`) that adds to the topology graph, for `connectors.Sync` and `connectors.Status` only:

- A card under the node (`topology-details` slot, `ConnectorTopologyDetails.vue`). Before a run it shows the task settings as written (`connectorId`/`connectorIds`, `wait`, `maxDuration`, plus `freshnessBuffer` on `Status`); after a run, `Sync` shows sync state, last success, fresh, alerts and tables, and `Status` shows connectors, fresh, paused, with alerts and tables.
- A "Show Details" action. It has no `taskProp`, so the host opens a dialog with the `topology-task-modal` slot (`ConnectorDetails.vue`): task settings, then every connector with its states, dates, fresh verdict, alert codes and table counts (`ConnectorSections.vue`, shared with the Connectors view). On `Sync` with `wait: false` it notes that the values are the snapshot taken before the trigger.
- Views in the execution Artifacts tab (`execution-artifact/<id>` slots, listed per task in `ui/vite.config.ts`): **Tables** on both tasks (`TablesArtifact.vue`, rows from the `tables` endpoint with a connector picker on `Status`, a synced/not synced filter, a name search and paging) and **Connectors** on `Status` (`ConnectorsArtifact.vue`). Each exports a `getSubtitle` (`composables/subtitles.ts`) computed from the outputs only.

Rules:

- The components read only the task run's outputs, through the host's `fetchOutputs` prop falling back to `taskRun.outputs`, and the plugin's own `tables` endpoint through the host's `fetchEndpoint` prop. They reload once the task run is terminal. They make no HTTP call of their own and never call Fivetran. A host without `fetchEndpoint` shows an upgrade note in the Tables view.
- Every output field is optional: executions from before the outputs existed, null table counts after a failed schema read and `wait: false` all render a placeholder.
- Table rows are only rendered from the `tables` endpoint, which returns the 8 breakdown fields and nothing else. Never render alert messages, connector `config` or destination data. None of it is in the outputs, and the settings reader never touches `apiKey`/`apiSecret`.
- `height`/`heightWithExecution` in `ui/vite.config.ts` must match the rendered row counts (about 56px plus 31.5px per row). Update them when a row is added or removed.
- `ui/vite.config.ts` caps Module Federation chunk names: with the `io.kestra.plugin.fivetran` remote name the longest virtual chunk name is 256 characters, one over the 255-byte file name limit on Linux, and the build fails without the cap.
- Gradle runs `npm install` and `npm run build -- --outDir ../src/main/resources/plugin-ui` (tasks `npmInstallUI` and `buildUI`) before packaging, so building the plugin needs Node.js 18+. `src/main/resources/plugin-ui/` is build output and is git-ignored. The plugin targets Kestra 2.1.x (Java 25) and no longer supports 2.0.x: plugin endpoints, the Artifacts tab, the asset card footer, asset `partOf` and task checkpoints are 2.1 APIs.
- Checks: `npm test`, `npm run lint` and `npm run format` in `ui/`.

### Project Structure

```
plugin-fivetran/
├── src/main/java/io/kestra/plugin/fivetran/models/
├── src/test/java/io/kestra/plugin/fivetran/models/
├── ui/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
