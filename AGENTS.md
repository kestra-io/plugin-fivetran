# Kestra Fivetran Plugin

## What

- Provides plugin components under `io.kestra.plugin.fivetran`.
- Includes classes such as `Sync`, `SyncResponse`, `ConnectorStatusResponse`, `ConnectorResponse`.

## Why

- This plugin integrates Kestra with Connectors.
- It provides tasks that trigger and manage Fivetran connector syncs.

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
