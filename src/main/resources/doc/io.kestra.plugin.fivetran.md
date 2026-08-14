# How to use the Fivetran plugin

Trigger Fivetran connector syncs from Kestra flows and optionally wait for completion.

## Authentication

Set `apiKey` and `apiSecret` (both required) for HTTP Basic authentication against the Fivetran API. Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and apply connection properties globally with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

`connectors.Sync` triggers a sync for a `connectorId` (required) and waits for completion by default (`wait: true`). Set `force: true` to cancel any active sync before starting a new one. By default a running sync is left as-is and the task skips. Cap wait time with `maxDuration` (default 60 minutes).

While waiting, the connector status is polled every `pollFrequency` (default 5 seconds). Transient errors during polling (429, 5xx, read timeouts, and refused connections) are retried and do not fail the task, since the sync keeps running on Fivetran regardless. `maxAttempts` (default 3, total attempts including the first) and `initialRetryDelay` (default 1s) tune the per-request retry.

`connectors.Status` reads the current status of one or more connectors given a `connectorIds` list (required), with a single GET per connector and no sync triggered. Output is a `connectors` map keyed by connector ID, each entry exposing sync/setup/schema state, `succeededAt`/`failedAt`, the derived `completedDate`/`hasFailed`, and a `fresh` verdict.

A connector is `fresh` when its last sync succeeded within `syncFrequency` (in minutes, as reported by Fivetran) plus an optional `slack` buffer (default `PT0S`) of now. `fresh` is `null` when Fivetran reports no `syncFrequency` for the connector, since freshness cannot then be computed.

By default (`wait: true`), `Status` acts as a freshness gate: it polls every `pollFrequency` (default 30 seconds) until all requested connectors are fresh, up to `maxDuration` (default 1 hour), and fails with a clear message naming the still-stale connectors if the deadline is reached. A paused connector, or one whose setup is not `connected` (`broken`, `incomplete`, bad auth), can never become fresh on its own, so the gate fails fast on it instead of polling forever; set `allowFailed: true` to instead report such a connector as not-fresh and let the gate wait out `maxDuration`. Set `wait: false` for a single read-only snapshot that never throws on stale, paused, or broken connectors.

When `assets.enableAuto` is set, `Status` emits one lineage asset per connector, keyed by its Fivetran destination schema (falling back to the connector name if no schema is reported).
