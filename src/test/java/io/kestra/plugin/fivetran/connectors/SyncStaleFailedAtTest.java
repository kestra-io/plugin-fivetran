package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.common.collect.ImmutableMap;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class SyncStaleFailedAtTest {
    private static final String CONNECTOR_ID = "arriving_atone";
    private static final String SYNC_SCENARIO = "connector-sync";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @DisplayName("Should not fail a sync whose succeeded_at is more recent than a stale failed_at")
    void staleFailedAtDoesNotFailASucceededSync(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        // previousConnector fetch: no succeeded_at yet, only a stale failure from months ago.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, "2026-04-28T03:06:48.382Z"))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        // Post-sync fetch: this run succeeded in July, the April failure is stale and must be ignored.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", "2026-04-28T03:06:48.382Z"))
                )
        );

        assertDoesNotThrow(() -> syncTask(wmRuntimeInfo.getHttpBaseUrl()).run(runContext()));
    }

    @Test
    @DisplayName("Should fail a sync whose failed_at is more recent than succeeded_at")
    void mostRecentFailureFailsTheSync(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, "2026-01-01T00:00:00.000Z"))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        // Post-sync fetch: the July failure happened after the April success, so this run genuinely failed.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-04-28T03:06:48.382Z", "2026-07-27T13:13:08.389Z"))
                )
        );

        Exception exception = assertThrows(Exception.class, () -> syncTask(wmRuntimeInfo.getHttpBaseUrl()).run(runContext()));
        assertThat(exception.getMessage(), containsString("Connector '" + CONNECTOR_ID + "' failed"));
    }

    private Sync syncTask(String baseUrl) {
        return Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(baseUrl))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .build();
    }

    private RunContext runContext() {
        return runContextFactory.of(ImmutableMap.of());
    }

    private static final String SYNC_TRIGGERED_BODY = """
        {
          "code": "Success",
          "message": "Sync has been successfully triggered"
        }
        """;

    private static String connectorBody(String succeededAt, String failedAt) {
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
                  "sync_state": "scheduled",
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
            """.formatted(CONNECTOR_ID, CONNECTOR_ID, jsonValue(succeededAt), jsonValue(failedAt));
    }

    private static String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
