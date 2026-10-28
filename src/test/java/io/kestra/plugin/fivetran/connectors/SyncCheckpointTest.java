package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static io.kestra.plugin.fivetran.connectors.SyncTestFixtures.connectorBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
@WireMockTest
class SyncCheckpointTest {
    private static final String CONNECTOR_ID = "checkpointed_connector";
    private static final String BEFORE = "2026-07-27T10:00:00.000Z";
    private static final String AFTER = "2026-07-27T13:13:08.389Z";
    private static final String SCENARIO = "checkpointed-sync";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @DisplayName("Should resume polling the sync it triggered after an interruption instead of triggering another")
    void interruptedRunResumesWithoutPostingAgain(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        // The sync never completes within maxDuration: the task is interrupted while it runs on Fivetran.
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "syncing"));

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        assertThrows(TimeoutException.class, () -> task.run(runContext));
        assertEquals(Sync.SyncCheckpoint.TRIGGERED, checkpoint(runContext).state());

        // The retry finds the sync this task run triggered, now completed after the saved baseline.
        stubConnector(connectorBody(CONNECTOR_ID, AFTER, null, "scheduled"));
        Sync.Output output = task.run(runContext);

        assertNotNull(output.getSucceededAt());
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
        assertEquals(Sync.SyncCheckpoint.DONE, checkpoint(runContext).state());
    }

    @Test
    @DisplayName("Should keep the pre-trigger baseline when resuming, so the earlier completion is not accepted")
    void resumedRunWaitsForACompletionAfterTheSavedBaseline(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "syncing"));

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        assertThrows(TimeoutException.class, () -> task.run(runContext));

        // Still the completion from before the trigger: without the saved baseline the resumed run would read it as
        // the new sync's and succeed.
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "scheduled"));
        assertThrows(TimeoutException.class, () -> task.run(runContext));
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should trigger a new sync on a retry after the previous one completed")
    void completedSyncIsNotResumed(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        // The connector reads as before the trigger until the POST, then as failed by the triggered sync.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(json(connectorBody(CONNECTOR_ID, BEFORE, null, "scheduled")))
        );
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SCENARIO)
                .willSetStateTo("TRIGGERED")
                .willReturn(json(SyncTestFixtures.SYNC_TRIGGERED_BODY))
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SCENARIO)
                .whenScenarioStateIs("TRIGGERED")
                .willReturn(json(connectorBody(CONNECTOR_ID, BEFORE, AFTER, "scheduled")))
        );

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        Exception failure = assertThrows(Exception.class, () -> task.run(runContext));
        assertTrue(failure.getMessage().contains("failed"), "message was: " + failure.getMessage());
        // The sync ended, so a retry must not resume onto the failed sync but trigger a new one.
        assertEquals(Sync.SyncCheckpoint.DONE, checkpoint(runContext).state());

        assertThrows(Exception.class, () -> task.run(runContext));
        verify(exactly(2), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should trigger a sync as usual when the saved checkpoint cannot be parsed")
    void unreadableCheckpointFallsBackToTriggering(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "scheduled"));

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        runContext.checkpoints().set(Sync.CHECKPOINT_KEY, "not json");

        stubConnector(connectorBody(CONNECTOR_ID, AFTER, null, "scheduled"));
        // The first GET reads AFTER as the baseline, so the sync never completes: only the trigger matters here.
        assertThrows(TimeoutException.class, () -> task.run(runContext));
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should save no checkpoint with wait: false, since there is nothing to resume")
    void noWaitSavesNoCheckpoint(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "scheduled"));

        Sync task = syncTask(wmRuntimeInfo, false);
        RunContext runContext = runContext(task);
        task.run(runContext);

        assertFalse(runContext.checkpoints().get(Sync.CHECKPOINT_KEY).isPresent());
    }

    @Test
    @DisplayName("Should never fail the task when the checkpoint store is unavailable")
    void unavailableStoreIsIgnored(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "scheduled"));

        // A run context with no task run: the store has nothing to key the checkpoint on.
        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContextFactory.of(Map.of());
        assertTrue(Sync.readCheckpoint(runContext, Duration.ofMinutes(1), Instant.now()).isEmpty());
        Sync.writeCheckpoint(runContext, Sync.SyncCheckpoint.done());

        stubConnector(connectorBody(CONNECTOR_ID, AFTER, null, "scheduled"));
        assertThrows(TimeoutException.class, () -> task.run(runContext));
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should trigger a new sync when the checkpoint is older than twice maxDuration")
    void staleCheckpointIsDiscarded(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "scheduled"));

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        // maxDuration is 400ms: a sync triggered an hour ago is far past twice that.
        Sync.writeCheckpoint(runContext, Sync.SyncCheckpoint.triggered(ZonedDateTime.parse(BEFORE), false, false, Instant.now().minus(Duration.ofHours(1))));

        assertThrows(TimeoutException.class, () -> task.run(runContext));
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should resume a checkpoint taken within twice maxDuration")
    void recentCheckpointIsResumed(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(connectorBody(CONNECTOR_ID, AFTER, null, "scheduled"));

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        // 600ms old with a 400ms maxDuration: past maxDuration, inside the 800ms window.
        Sync.writeCheckpoint(runContext, Sync.SyncCheckpoint.triggered(ZonedDateTime.parse(BEFORE), false, false, Instant.now().minusMillis(600)));

        assertNotNull(task.run(runContext).getSucceededAt());
        verify(exactly(0), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should ignore a triggered checkpoint saved without a trigger time")
    void checkpointWithoutTriggerTimeIsDiscarded(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(connectorBody(CONNECTOR_ID, BEFORE, null, "scheduled"));

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        runContext.checkpoints().set(Sync.CHECKPOINT_KEY, "{\"state\":\"triggered\",\"baseline\":\"" + BEFORE + "\",\"queuedBehindRunning\":false,\"reattached\":false}");

        assertThrows(TimeoutException.class, () -> task.run(runContext));
        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should accept a resumed sync that completed before the connector was paused")
    void resumedSyncCompletedBeforePauseSucceeds(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(pausedBody(AFTER));

        Sync task = syncTask(wmRuntimeInfo, true);
        RunContext runContext = runContext(task);
        Sync.writeCheckpoint(runContext, Sync.SyncCheckpoint.triggered(ZonedDateTime.parse(BEFORE), false, false, Instant.now()));

        assertNotNull(task.run(runContext).getSucceededAt());
        verify(exactly(0), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should fail fast with the paused error when a resumed sync has no completion on a paused connector")
    void resumedPausedConnectorWithoutCompletionFailsFast(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubTrigger();
        stubConnector(pausedBody(BEFORE));

        Sync task = Sync.builder()
            .id("sync")
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .pollFrequency(Property.ofValue(Duration.ofMillis(50)))
            .maxAttempts(Property.ofValue(1))
            .build();
        RunContext runContext = runContext(task);
        Sync.writeCheckpoint(runContext, Sync.SyncCheckpoint.triggered(ZonedDateTime.parse(BEFORE), false, false, Instant.now()));

        var start = Instant.now();
        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertTrue(thrown.getMessage().contains("is paused"), "message was: " + thrown.getMessage());
        assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(10)) < 0, "must not wait until maxDuration");
        verify(exactly(0), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    private static String pausedBody(String succeededAt) {
        return connectorBody(CONNECTOR_ID, succeededAt, null, "paused").replace("\"paused\": false", "\"paused\": true");
    }

    private static Sync.SyncCheckpoint checkpoint(RunContext runContext) throws Exception {
        return JacksonMapper.ofJson().readValue(runContext.checkpoints().get(Sync.CHECKPOINT_KEY).orElseThrow(), Sync.SyncCheckpoint.class);
    }

    private RunContext runContext(Sync task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    private static Sync syncTask(WireMockRuntimeInfo wmRuntimeInfo, boolean wait) {
        return Sync.builder()
            .id("sync")
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .wait(Property.ofValue(wait))
            .maxDuration(Property.ofValue(Duration.ofMillis(400)))
            .pollFrequency(Property.ofValue(Duration.ofMillis(50)))
            .maxAttempts(Property.ofValue(1))
            .build();
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static void stubTrigger() {
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(SyncTestFixtures.SYNC_TRIGGERED_BODY))
        );
    }

    private static void stubConnector(String body) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body))
        );
    }
}
