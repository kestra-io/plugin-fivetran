# Kestra Fivetran Plugin

## What

- Provides plugin components under `io.kestra.plugin.fivetran`.
- Includes classes such as `Sync`, `SyncResponse`, `ConnectorStatusResponse`, `ConnectorResponse`.

## Why

- What user problem does this solve? Teams need to orchestrate Fivetran connectors to automate data movement in Kestra workflows from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Fivetran steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Fivetran.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `fivetran`

### Key Plugin Classes

- `io.kestra.plugin.fivetran.connectors.Sync`

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
