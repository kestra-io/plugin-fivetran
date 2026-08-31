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

- `io.kestra.plugin.fivetran.connectors.Sync`: triggers a connector sync and optionally waits for completion. Returns the connector id and, when it waited, the `succeededAt` of the sync it observed. Emits lineage when `assets.enableAuto` is set.
- `io.kestra.plugin.fivetran.connectors.Status`: reads the current status of one or more connectors via a single GET per connector, without triggering a sync. Computes a `fresh` verdict per connector from `succeededAt`/`syncFrequency`/`freshnessBuffer`, and by default (`wait: true`) polls until every connector is fresh or fails fast on a paused/broken connector, up to `maxDuration`. Emits lineage when `assets.enableAuto` is set.

### Asset lineage

`AbstractFivetranConnection.emitAssets` is the one helper both tasks call, because a connector on Fivetran's own schedule is never triggered through `Sync` and one driven from Kestra is often never read through `Status`. Either task alone leaves half the graph empty.

Per connector it emits:

- One **table-grain** asset per synced table, id `database.schema.name`, metadata `system`/`database`/`schema`/`name`/`connectorId`. `system` names the warehouse (the destination's `service`), not this plugin, because plugin-dbt writes the same asset id and sets `system` to the warehouse adapter. The spellings agree for `snowflake`, `databricks` and `redshift`, but not for BigQuery, which Fivetran calls `big_query` and dbt calls `bigquery`. Metadata is descriptive only, the id is what forms the edge. The Fivetran origin stays visible through `connectorId`. This is the same id convention plugin-dbt emits (`ResultParser.assetIdFor`), so a Fivetran-loaded table and the dbt model reading it resolve to a single node and the edge forms with no manual mapping. `database` comes from `GET /v2/destinations/{group_id}` (`config.database`, falling back to `catalog` then `project_id`), never from the group id: a group id is a Fivetran-internal identifier, not a warehouse database, so an id built on it would be table-grain but still in the wrong namespace. Tables come from `GET /v2/connectors/{id}/schemas`, using `name_in_destination` and skipping disabled schemas and tables.
- One **connector-grain** asset, id still `groupId.schema` (falling back to `connectorId.schema` when `groupId` is absent), so upgrading renames nothing and connector-level freshness keeps somewhere to live.

Both reads are skipped entirely when `assets.enableAuto` is off, and destinations are resolved once per group id per run. Nothing on the lineage path can fail the task: a failed destination or schema read, and an `enableAuto` expression that will not render, all warn and leave the connector asset in place. Lineage is metadata about the run, so it must never fail a sync that succeeded. The destination read is the one Fivetran response documented to carry raw credentials (Snowflake `password`/`private_key`, BigQuery `secret_key`), so its config is kept out of `toString` and its exception messages are never logged.

Ids over the core 150-character cap get a digest suffix rather than plain truncation. The table name is the last segment, so cutting the tail would land every table of one schema on the same id and silently collapse them into a single node.

Each id segment is sanitized on its own before being joined with `.`, so a `.` inside a raw segment (e.g. schema `google_sheets.destination`) can never be mistaken for the delimiter.

### Project Structure

```
plugin-fivetran/
├── src/main/java/io/kestra/plugin/fivetran/models/
├── src/test/java/io/kestra/plugin/fivetran/models/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
