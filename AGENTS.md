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

- `io.kestra.plugin.fivetran.connectors.Sync`: triggers a connector sync and optionally waits for completion.
- `io.kestra.plugin.fivetran.connectors.Status`: reads the current status of one or more connectors via a single GET per connector, without triggering a sync. Computes a `fresh` verdict per connector from `succeededAt`/`syncFrequency`/`freshnessBuffer`, and by default (`wait: true`) polls until every connector is fresh or fails fast on a paused/broken connector, up to `maxDuration`. Emits one lineage asset per connector destination schema when `assets.enableAuto` is set, with the asset id composed as `groupId.schema` (falling back to `connectorId.schema` when `groupId` is absent) so connectors sharing a schema never collide. Each segment is sanitized on its own before being joined with `.`, so a `.` inside a raw segment (e.g. schema `google_sheets.destination`) can never be mistaken for the delimiter.

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
