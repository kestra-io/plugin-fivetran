# How to use the Fivetran plugin

Trigger Fivetran connector syncs from Kestra flows and optionally wait for completion.

## Authentication

Set `apiKey` and `apiSecret` (both required) for HTTP Basic authentication against the Fivetran API. Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and apply connection properties globally with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

`connectors.Sync` triggers a sync for a `connectorId` (required) and waits for completion by default (`wait: true`). Set `force: true` to cancel any active sync before starting a new one. By default a running sync is left as-is and the task skips. Cap wait time with `maxDuration` (default 60 minutes).

While waiting, the connector status is polled every `pollFrequency` (default 5 seconds). Transient errors during polling (429, 5xx, read timeouts, and refused connections) are retried and do not fail the task, since the sync keeps running on Fivetran regardless. `maxAttempts` (default 3, total attempts including the first) and `initialRetryDelay` (default 1s) tune the per-request retry.
