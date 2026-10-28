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
import io.kestra.core.utils.IdUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            .id("sync_" + IdUtils.create())
            .type(Sync.class.getName())
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

    @Test
    @DisplayName("Should return on the first completion even if the next sync is already running, for a plain trigger")
    void returnsOnFirstCompletionWhenNotQueued(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubGet(Scenario.STARTED, "TRIGGER", T0, "scheduled");
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SCENARIO)
                .whenScenarioStateIs("TRIGGER")
                .willSetStateTo("DONE")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SyncTestFixtures.SYNC_TRIGGERED_BODY)
                )
        );
        stubGet("DONE", "DONE", T1, "syncing");

        var output = run(wmRuntimeInfo, false);

        assertEquals(ZonedDateTime.parse(T1).toInstant(), output.getSucceededAt().toInstant());
    }

    @Test
    @DisplayName("Should return on the first completion even if the next sync is already running, after a reattach")
    void returnsOnFirstCompletionWhenReattached(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubGet(Scenario.STARTED, "DONE", T0, "syncing");
        stubGet("DONE", "DONE", T1, "syncing");

        var output = run(wmRuntimeInfo, true);

        assertEquals(ZonedDateTime.parse(T1).toInstant(), output.getSucceededAt().toInstant());
        assertTrue(output.isReattached());
    }

    @Test
    @DisplayName("Should accept a later completion seen while still syncing once the queued trigger's first completion was recorded")
    void acceptsSecondCompletionWhileStillSyncingWhenQueued(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubGet(Scenario.STARTED, "TRIGGER", T0, "syncing");
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SCENARIO)
                .whenScenarioStateIs("TRIGGER")
                .willSetStateTo("FIRST")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SyncTestFixtures.SYNC_TRIGGERED_BODY)
                )
        );
        stubGet("FIRST", "SECOND", T1, "syncing");
        stubGet("SECOND", "SECOND", T2, "syncing");

        var output = run(wmRuntimeInfo, false);

        assertEquals(ZonedDateTime.parse(T2).toInstant(), output.getSucceededAt().toInstant());
    }

    private Sync.Output run(WireMockRuntimeInfo wmRuntimeInfo, boolean reattach) throws Exception {
        return Sync.builder()
            .id("sync_" + IdUtils.create())
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .reattach(Property.ofValue(reattach))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build()
            .run(runContextFactory.of(ImmutableMap.of()));
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
