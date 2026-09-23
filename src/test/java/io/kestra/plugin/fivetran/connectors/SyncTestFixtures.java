package io.kestra.plugin.fivetran.connectors;

/**
 * Shared connector JSON fixtures for {@link SyncReattachTest} and {@link SyncRetryTest}, which both
 * stub the same Fivetran connector-status and sync-trigger endpoints.
 */
final class SyncTestFixtures {
    private SyncTestFixtures() {
    }

    static final String SYNC_TRIGGERED_BODY = """
        {
          "code": "Success",
          "message": "Sync has been successfully triggered"
        }
        """;

    static String connectorBody(String connectorId, String succeededAt, String failedAt, String syncState) {
        return """
            {
              "code": "Success",
              "data": {
                "id": "%s",
                "name": "%s",
                "paused": false,
                "version": 1,
                "status": {
                  "setup_state": "connected",
                  "sync_state": "%s",
                  "update_state": "on_schedule",
                  "is_historical_sync": false,
                  "schema_status": "ready",
                  "tasks": [],
                  "warnings": []
                },
                "daily_sync_time": "14:00",
                "succeeded_at": %s,
                "connector_type_id": "postgres",
                "sync_frequency": 360,
                "pause_after_trial": false,
                "group_id": "some_group",
                "connected_by": "some_user",
                "created_at": "2025-01-01T00:00:00.000Z",
                "failed_at": %s,
                "schedule_type": "auto"
              }
            }
            """.formatted(connectorId, connectorId, syncState, jsonValue(succeededAt), jsonValue(failedAt));
    }

    static String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
