package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.common.collect.ImmutableMap;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@KestraTest
@WireMockTest
class SyncQueuedBehindRunningTest {
    private static final String CONNECTOR_ID = "arriving_atone";
    private static final String SCENARIO = "connector-sync-queued";
    private static final String T0 = "2026-07-27T13:00:00.000Z";
    private static final String T1 = "2026-07-27T14:27:31.462Z";
    private static final String T2 = "2026-07-27T14:27:33.512Z";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @DisplayName("Should not return on the completion of a running sync our queued trigger is waiting behind")
    void waitsPastCompletionWhileStillSyncing(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubGet(Scenario.STARTED, "TRIGGER", T0, "syncing");

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SCENARIO)
                .whenScenarioStateIs("TRIGGER")
                .willSetStateTo("RUNNING_DONE")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SyncTestFixtures.SYNC_TRIGGERED_BODY)
                )
        );

        // The running sync completes (T1) but the connector is still syncing: that is the queued one starting.
        stubGet("RUNNING_DONE", "QUEUED_DONE", T1, "syncing");
        stubGet("QUEUED_DONE", "QUEUED_DONE", T2, "scheduled");

        Sync.Output output = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build()
            .run(runContextFactory.of(ImmutableMap.of()));

        assertEquals(ZonedDateTime.parse(T2).toInstant(), output.getSucceededAt().toInstant());
        assertEquals("scheduled", output.getFreshness().getSyncState());
    }

    private void stubGet(String fromState, String toState, String succeededAt, String syncState) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SCENARIO)
                .whenScenarioStateIs(fromState)
                .willSetStateTo(toState)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SyncTestFixtures.connectorBody(CONNECTOR_ID, succeededAt, null, syncState))
                )
        );
    }
}
