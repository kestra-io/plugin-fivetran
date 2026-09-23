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
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorStatusResponse;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
@WireMockTest
class SyncReattachTest {
    private static final String CONNECTOR_ID = "arriving_atone";
    private static final String SYNC_SCENARIO = "connector-sync-reattach";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @DisplayName("Should adopt a sync already in progress without triggering a duplicate")
    void syncingConnectorIsAdoptedWithoutPosting(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null, "syncing"))
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null, "scheduled"))
                )
        );

        Sync.Output output = syncTask(wmRuntimeInfo.getHttpBaseUrl(), true, null, false).run(runContext());

        assertTrue(output.isReattached());
        assertNotNull(output.getSucceededAt());
        verify(exactly(0), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should trigger a new sync when the connector is only scheduled, not syncing")
    void scheduledConnectorTriggersANewSync(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null, "scheduled"))
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

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null, "scheduled"))
                )
        );

        Sync.Output output = syncTask(wmRuntimeInfo.getHttpBaseUrl(), true, null, false).run(runContext());

        assertFalse(output.isReattached());
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should always trigger a new sync when reattach is left at its default (false)")
    void reattachDefaultsToFalseAndAlwaysPosts(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null, "syncing"))
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

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null, "scheduled"))
                )
        );

        Sync.Output output = syncTask(wmRuntimeInfo.getHttpBaseUrl(), null, null, false).run(runContext());

        assertFalse(output.isReattached());
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should trigger a new sync when the in-progress sync is older than reattachMaxAge")
    void staleInProgressSyncTriggersANewSync(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2020-01-01T00:00:00.000Z", null, "syncing"))
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

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null, "scheduled"))
                )
        );

        Sync.Output output = syncTask(wmRuntimeInfo.getHttpBaseUrl(), true, Duration.ofMinutes(5), false).run(runContext());

        assertFalse(output.isReattached());
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should force-restart a stale in-progress sync instead of sending a no-op trigger")
    void staleInProgressSyncIsForceRestarted(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2020-01-01T00:00:00.000Z", null, "syncing"))
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

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null, "scheduled"))
                )
        );

        Sync.Output output = syncTask(wmRuntimeInfo.getHttpBaseUrl(), true, Duration.ofMinutes(5), false).run(runContext());

        assertFalse(output.isReattached());
        verify(
            exactly(1),
            postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .withRequestBody(containing("\"force\":true"))
        );
    }

    @Test
    @DisplayName("Should let force win over reattach and trigger a new sync with force=true")
    void forceTakesPrecedenceOverReattach(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null, "syncing"))
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

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("SYNCED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null, "scheduled"))
                )
        );

        Sync.Output output = syncTask(wmRuntimeInfo.getHttpBaseUrl(), true, null, true).run(runContext());

        assertFalse(output.isReattached());
        verify(
            exactly(1),
            postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .withRequestBody(containing("\"force\":true"))
        );
    }

    @Test
    @DisplayName("Should reject a negative reattachMaxAge before any HTTP call")
    void negativeReattachMaxAgeIsRejected(WireMockRuntimeInfo wmRuntimeInfo) {
        Sync task = syncTask(wmRuntimeInfo.getHttpBaseUrl(), true, Duration.ofMinutes(-1), false);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertTrue(thrown.getMessage().contains("reattachMaxAge must not be negative"), "message was: " + thrown.getMessage());
        verify(exactly(0), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("isSyncing should be true only for sync_state=syncing, case-insensitively, and null-safe on a missing status")
    void isSyncingClassification() {
        assertTrue(Sync.isSyncing(connectorWithSyncState("syncing")));
        assertTrue(Sync.isSyncing(connectorWithSyncState("SYNCING")));
        assertFalse(Sync.isSyncing(connectorWithSyncState("scheduled")));
        assertFalse(Sync.isSyncing(connectorWithSyncState("rescheduled")));
        assertFalse(Sync.isSyncing(connectorWithSyncState("paused")));
        assertFalse(Sync.isSyncing(Connector.builder().id(CONNECTOR_ID).status(null).build()));
    }

    @Test
    @DisplayName("isStaleForReattach should bound the in-progress sync's age on completedDate, boundary inclusive")
    void isStaleForReattachClassification() {
        ZonedDateTime now = ZonedDateTime.parse("2026-01-01T00:00:00Z");
        Connector recentlyCompleted = Connector.builder().id(CONNECTOR_ID).succeededAt(now.minusMinutes(4)).build();
        Connector longCompleted = Connector.builder().id(CONNECTOR_ID).succeededAt(now.minusMinutes(6)).build();
        Connector neverCompleted = Connector.builder().id(CONNECTOR_ID).build();

        // No cap: never stale, whatever completedDate is, including null.
        assertFalse(Sync.isStaleForReattach(recentlyCompleted, null, now));
        assertFalse(Sync.isStaleForReattach(neverCompleted, null, now));

        // Within the cap: not stale. Boundary inclusive.
        assertFalse(Sync.isStaleForReattach(recentlyCompleted, Duration.ofMinutes(5), now));
        assertFalse(Sync.isStaleForReattach(Connector.builder().id(CONNECTOR_ID).succeededAt(now.minusMinutes(5)).build(), Duration.ofMinutes(5), now));

        // Beyond the cap: stale.
        assertTrue(Sync.isStaleForReattach(longCompleted, Duration.ofMinutes(5), now));

        // A cap is set but the connector never completed a sync: age is unbounded, so treat as stale (do not adopt).
        assertTrue(Sync.isStaleForReattach(neverCompleted, Duration.ofMinutes(5), now));
    }

    private Sync syncTask(String baseUrl, Boolean reattach, Duration reattachMaxAge, boolean force) {
        Sync.SyncBuilder<?, ?> builder = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(baseUrl))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .force(Property.ofValue(force));

        if (reattach != null) {
            builder.reattach(Property.ofValue(reattach));
        }
        if (reattachMaxAge != null) {
            builder.reattachMaxAge(Property.ofValue(reattachMaxAge));
        }

        return builder.build();
    }

    private static Connector connectorWithSyncState(String syncState) {
        return Connector.builder()
            .id(CONNECTOR_ID)
            .status(ConnectorStatusResponse.builder().syncState(syncState).build())
            .build();
    }

    private RunContext runContext() {
        return runContextFactory.of(ImmutableMap.of());
    }

    private static final String SYNC_TRIGGERED_BODY = SyncTestFixtures.SYNC_TRIGGERED_BODY;

    private static String connectorBody(String succeededAt, String failedAt, String syncState) {
        return SyncTestFixtures.connectorBody(CONNECTOR_ID, succeededAt, failedAt, syncState);
    }
}
