package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.common.collect.ImmutableMap;

import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.fivetran.TestAssetManagerFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThan;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class StatusTest {
    private static final String SUCCEEDED_CONNECTOR_ID = "succeeded_connector";
    private static final String STALE_FAILED_CONNECTOR_ID = "stale_failed_connector";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private TestAssetManagerFactory assetManagerFactory;

    @BeforeEach
    void setUp() {
        assetManagerFactory.clear();
    }

    @Test
    @DisplayName("Should read the status of several connectors without triggering a sync")
    void readsStatusOfMultipleConnectorsWithoutSyncing(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        // succeeded_at more recent than failed_at: the last completion is a success.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + SUCCEEDED_CONNECTOR_ID))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(SUCCEEDED_CONNECTOR_ID, "2026-07-27T13:13:08.389Z", "2026-04-28T03:06:48.382Z"))
                )
        );

        // failed_at more recent than succeeded_at: the last completion is a genuine failure.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + STALE_FAILED_CONNECTOR_ID))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(STALE_FAILED_CONNECTOR_ID, "2026-01-01T00:00:00.000Z", "2026-07-27T13:13:08.389Z"))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(SUCCEEDED_CONNECTOR_ID, STALE_FAILED_CONNECTOR_ID)))
            .wait(Property.ofValue(false))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors(), aMapWithSize(2));

        Status.ConnectorState succeeded = output.getConnectors().get(SUCCEEDED_CONNECTOR_ID);
        assertThat(succeeded.getId(), is(SUCCEEDED_CONNECTOR_ID));
        assertThat(succeeded.getName(), is(SUCCEEDED_CONNECTOR_ID));
        assertThat(succeeded.getPaused(), is(false));
        assertThat(succeeded.getSyncFrequency(), is(360));
        assertThat(succeeded.getScheduleType(), is("auto"));
        assertThat(succeeded.getSyncState(), is("scheduled"));
        assertThat(succeeded.getSetupState(), is("connected"));
        assertThat(succeeded.getSchemaStatus(), is("ready"));
        assertThat(succeeded.getSucceededAt(), is(ZonedDateTime.parse("2026-07-27T13:13:08.389Z")));
        assertThat(succeeded.getFailedAt(), is(ZonedDateTime.parse("2026-04-28T03:06:48.382Z")));
        assertThat(succeeded.getCompletedDate(), is(ZonedDateTime.parse("2026-07-27T13:13:08.389Z")));
        assertThat(succeeded.isHasFailed(), is(false));

        Status.ConnectorState staleFailed = output.getConnectors().get(STALE_FAILED_CONNECTOR_ID);
        assertThat(staleFailed.getSucceededAt(), is(ZonedDateTime.parse("2026-01-01T00:00:00.000Z")));
        assertThat(staleFailed.getFailedAt(), is(ZonedDateTime.parse("2026-07-27T13:13:08.389Z")));
        assertThat(staleFailed.getCompletedDate(), is(ZonedDateTime.parse("2026-07-27T13:13:08.389Z")));
        assertThat(staleFailed.isHasFailed(), is(true));
        // failedAt was the last completion, so it can never be fresh regardless of the clock.
        assertThat(staleFailed.getFresh(), is(false));

        verify(exactly(1), getRequestedFor(urlEqualTo("/v2/connectors/" + SUCCEEDED_CONNECTOR_ID)));
        verify(exactly(1), getRequestedFor(urlEqualTo("/v2/connectors/" + STALE_FAILED_CONNECTOR_ID)));
        verify(exactly(0), postRequestedFor(urlMatching(".*/sync")));
    }

    @Test
    @DisplayName("Should mark a connector fresh only when its last success is within syncFrequency plus freshnessBuffer")
    void computesFreshnessFromSucceededAtAndSyncFrequency(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String freshConnectorId = "fresh_connector";
        String staleConnectorId = "stale_connector";

        // 10 minutes ago, well within the 360-minute sync_frequency.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + freshConnectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(freshConnectorId, isoNow(-10), null, false, "connected", 360, null))
                )
        );

        // 10 hours ago, well beyond the 360-minute sync_frequency.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + staleConnectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(staleConnectorId, isoNow(-600), null, false, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(freshConnectorId, staleConnectorId)))
            .wait(Property.ofValue(false))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors().get(freshConnectorId).getFresh(), is(true));
        assertThat(output.getConnectors().get(staleConnectorId).getFresh(), is(false));
    }

    @Test
    @DisplayName("Should let freshnessBuffer widen the freshness window beyond syncFrequency")
    void freshnessBufferWidensTheFreshnessWindow(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "buffer_connector";

        // 400 minutes ago: stale against a 360-minute sync_frequency alone, fresh once a 1-hour buffer is added.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-400), null, false, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .freshnessBuffer(Property.ofValue(Duration.ofHours(1)))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors().get(connectorId).getFresh(), is(true));
    }

    @Test
    @DisplayName("Should report fresh as null without throwing when wait is false and sync_frequency is missing")
    void reportsNullFreshWhenSyncFrequencyMissingAndNotWaiting(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "no_frequency_connector";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, null, null, false, "connected", null, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors().get(connectorId).getFresh(), is(nullValue()));
    }

    @Test
    @DisplayName("Should fail fast instead of polling forever when sync_frequency is missing while waiting")
    void failsFastWhenSyncFrequencyMissingInWaitMode(WireMockRuntimeInfo wmRuntimeInfo) {
        String connectorId = "no_frequency_connector_waiting";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, null, null, false, "connected", null, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString(connectorId));
        assertThat(thrown.getMessage(), containsString("sync_frequency"));
    }

    @Test
    @DisplayName("Should fail fast on a paused connector when waiting for freshness")
    void failsFastOnPausedConnectorInWaitMode(WireMockRuntimeInfo wmRuntimeInfo) {
        String connectorId = "paused_connector";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, null, null, true, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString(connectorId));
        assertThat(thrown.getMessage(), containsString("cannot become fresh"));
        assertThat(thrown.getMessage(), containsString("paused=true"));
    }

    @Test
    @DisplayName("Should succeed in wait mode when a connector is already fresh even though it is now paused")
    void succeedsOnAlreadyFreshPausedConnectorInWaitMode(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "fresh_then_paused_connector";

        // Fivetran commonly pauses a connector right after it syncs to control cost; it is still fresh,
        // so the fail-fast gate must not fire on it.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-10), null, true, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(true))
            .allowTerminal(Property.ofValue(false))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors().get(connectorId).getFresh(), is(true));
        assertThat(output.getConnectors().get(connectorId).getPaused(), is(true));
    }

    @Test
    @DisplayName("Should fail fast on a connector whose setup is not connected when waiting for freshness")
    void failsFastOnNonConnectedSetupStateInWaitMode(WireMockRuntimeInfo wmRuntimeInfo) {
        String connectorId = "broken_connector";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, null, null, false, "broken", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString(connectorId));
        assertThat(thrown.getMessage(), containsString("setupState=broken"));
    }

    @Test
    @DisplayName("Should poll until the connector becomes fresh and then return success")
    void pollsUntilConnectorBecomesFreshThenSucceeds(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "poll_until_fresh_connector";
        String scenario = "status-poll-until-fresh";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("FRESH")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-600), null, false, "connected", 360, null))
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .inScenario(scenario)
                .whenScenarioStateIs("FRESH")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-5), null, false, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .pollFrequency(Property.ofValue(Duration.ofSeconds(1)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors().get(connectorId).getFresh(), is(true));
    }

    @Test
    @DisplayName("Should throw naming the stale connector when it never becomes fresh before maxDuration")
    void throwsTimeoutNamingStaleConnectorWhenDeadlineIsReached(WireMockRuntimeInfo wmRuntimeInfo) {
        String connectorId = "always_stale_connector";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-600), null, false, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .pollFrequency(Property.ofValue(Duration.ofMillis(200)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        TimeoutException thrown = assertThrows(TimeoutException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString(connectorId));
        assertThat(thrown.getMessage(), containsString("did not become fresh"));
    }

    @Test
    @DisplayName("Should emit one asset per connector with a groupId-scoped id when assets.enableAuto is true")
    void emitsOneAssetPerConnectorWhenAssetsEnabled(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "asset_connector";
        String schema = "asset_connector_dest_schema";
        // connectorBody's fixture hardcodes group_id to "some_group", so the composed id is "some_group.<schema>".
        String expectedAssetId = "some_group." + schema;

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-10), null, false, "connected", 360, schema))
                )
        );

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        Status.Output output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output.getConnectors().get(connectorId).getFresh(), is(true));

        List<AssetEmit> emitted = assetManagerFactory.allEmitted();
        assertThat(emitted, hasSize(1));

        Asset asset = emitted.get(0).outputs().get(0);
        assertThat(asset.getId(), is(expectedAssetId));
        assertThat(asset.getType(), is("io.kestra.plugin.ee.assets.Table"));
        assertThat(asset.getMetadata().get("connectorId"), is(connectorId));
        assertThat(asset.getMetadata().get("schema"), is(schema));
    }

    @Test
    @DisplayName("Should emit no asset when assets.enableAuto is unset (default)")
    void emitsNoAssetWhenAssetsNotEnabled(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "asset_disabled_connector";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-10), null, false, "connected", 360, "some_schema"))
                )
        );

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .build();

        task.run(runContextFactory.of(task, Map.of()));

        assertThat(assetManagerFactory.allEmitted(), is(empty()));
    }

    @Test
    @DisplayName("Should emit distinct asset ids for two connectors sharing the same schema")
    void emitsDistinctAssetIdsForConnectorsSharingSchema(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorIdA = "collision_connector_a";
        String connectorIdB = "collision_connector_b";
        String sharedSchema = "shared_schema";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdA))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdA, isoNow(-10), null, false, "connected", 360, sharedSchema, "group_alpha"))
                )
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdB))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdB, isoNow(-10), null, false, "connected", 360, sharedSchema, "group_beta"))
                )
        );

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorIdA, connectorIdB)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        task.run(runContextFactory.of(task, Map.of()));

        List<AssetEmit> emitted = assetManagerFactory.allEmitted();
        assertThat(emitted, hasSize(2));

        String idA = emitted.get(0).outputs().get(0).getId();
        String idB = emitted.get(1).outputs().get(0).getId();
        assertThat(idA, is("group_alpha." + sharedSchema));
        assertThat(idB, is("group_beta." + sharedSchema));
        assertThat(idA, is(not(idB)));
    }

    @Test
    @DisplayName("Should sanitize a dot inside a schema so it is never mistaken for the group/schema delimiter")
    void sanitizesDotInsideSchemaSoItCannotCollideWithDelimiter(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "dotted_schema_connector";
        // Fivetran can report a schema containing a dot, e.g. "google_sheets.destination".
        String schema = "google_sheets.destination";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-10), null, false, "connected", 360, schema))
                )
        );

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        task.run(runContextFactory.of(task, Map.of()));

        List<AssetEmit> emitted = assetManagerFactory.allEmitted();
        assertThat(emitted, hasSize(1));
        // connectorBody's fixture hardcodes group_id to "some_group"; the schema's dot is replaced with "_".
        assertThat(emitted.get(0).outputs().get(0).getId(), is("some_group.google_sheets_destination"));
    }

    @Test
    @DisplayName("Should compose distinct asset ids for a dotted groupId/schema pair that would otherwise collide")
    void composesDistinctAssetIdsForDottedSegmentsThatWouldOtherwiseCollide(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorIdA = "collision_dot_connector_a";
        String connectorIdB = "collision_dot_connector_b";

        // Without per-segment sanitization, groupId="g"/schema="a.b" and groupId="g.a"/schema="b" would both
        // naively join to "g.a.b". Sanitizing each segment before joining keeps their composed ids distinct.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdA))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdA, isoNow(-10), null, false, "connected", 360, "a.b", "g"))
                )
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdB))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdB, isoNow(-10), null, false, "connected", 360, "b", "g.a"))
                )
        );

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorIdA, connectorIdB)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        task.run(runContextFactory.of(task, Map.of()));

        List<AssetEmit> emitted = assetManagerFactory.allEmitted();
        assertThat(emitted, hasSize(2));

        String idA = emitted.get(0).outputs().get(0).getId();
        String idB = emitted.get(1).outputs().get(0).getId();
        assertThat(idA, is("g.a_b"));
        assertThat(idB, is("g_a.b"));
        assertThat(idA, is(not(idB)));
    }

    @Test
    @DisplayName("Should fall back to a non-empty placeholder asset id when groupId and schema sanitize down to nothing")
    void fallsBackToNonEmptyAssetIdWhenComposedIdSanitizesToEmpty(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "underscore_connector";

        // groupId="_" and schema="_" compose to "_._", which is entirely non-alphanumeric and would
        // otherwise sanitize down to "", violating Asset.id's @NotBlank/@Size(min=1) constraint.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-10), null, false, "connected", 360, "_", "_"))
                )
        );

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        task.run(runContextFactory.of(task, Map.of()));

        List<AssetEmit> emitted = assetManagerFactory.allEmitted();
        assertThat(emitted, hasSize(1));
        assertThat(emitted.get(0).outputs().get(0).getId(), is("connector"));
    }

    @Test
    @DisplayName("Should treat a connector whose last success is just inside the freshness threshold as fresh")
    void treatsJustInsideThresholdAsFreshInclusiveBoundary(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "boundary_connector";
        int syncFrequencyMinutes = 5;
        Duration buffer = Duration.ofSeconds(30);

        // The freshness threshold is `now - (syncFrequency + freshnessBuffer)`, evaluated at task run time. Exact
        // equality can't be asserted from a black-box test since "now" is not injectable, so succeededAt is
        // pinned 1 second newer than the threshold computed here, right before the stub is set up. Task
        // execution (a couple of WireMock round trips) always finishes well under that 1s margin, so the
        // connector remains inside the window at evaluation time. Under the old strict `isAfter` check this
        // margin would occasionally get eaten by test-execution jitter and flip the result to stale; the
        // inclusive `!isBefore` check keeps it deterministically fresh.
        ZonedDateTime threshold = ZonedDateTime.now().minusMinutes(syncFrequencyMinutes).minus(buffer);
        String succeededAt = threshold.plusSeconds(1).toInstant().toString();

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, succeededAt, null, false, "connected", syncFrequencyMinutes, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .freshnessBuffer(Property.ofValue(buffer))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors().get(connectorId).getFresh(), is(true));
    }

    @Test
    @DisplayName("Should treat succeededAt exactly at the syncFrequency+freshnessBuffer threshold as fresh, and one nanosecond later as stale")
    void computeFreshIsInclusiveOfTheExactBoundary() {
        ZonedDateTime succeededAt = ZonedDateTime.parse("2026-01-01T00:00:00Z");
        int syncFrequencyMinutes = 60;
        Duration buffer = Duration.ofMinutes(5);
        // The threshold is `now - syncFrequency - buffer`; equivalently, succeededAt is fresh as long as
        // `now` has not yet passed `succeededAt + syncFrequency + buffer`.
        ZonedDateTime boundaryNow = succeededAt.plusMinutes(syncFrequencyMinutes).plus(buffer);

        assertThat(
            Status.computeFresh(succeededAt, false, syncFrequencyMinutes, buffer, boundaryNow),
            is(true)
        );
        assertThat(
            Status.computeFresh(succeededAt, false, syncFrequencyMinutes, buffer, boundaryNow.plusNanos(1)),
            is(false)
        );
        assertThat(
            Status.computeFresh(succeededAt, false, null, buffer, boundaryNow),
            is(nullValue())
        );
        assertThat(
            Status.computeFresh(succeededAt, true, syncFrequencyMinutes, buffer, succeededAt),
            is(false)
        );
    }

    @Test
    @DisplayName("Should fail clearly and without retrying when a connector is not found")
    void failsClearlyOn404WithoutRetrying(WireMockRuntimeInfo wmRuntimeInfo) {
        String connectorId = "missing_connector";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(aResponse().withStatus(404))
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .build();

        assertThrows(HttpClientResponseException.class, () -> task.run(runContext()));

        // 404 is not a retriable transient error, so it must fail on the very first call.
        verify(exactly(1), getRequestedFor(urlEqualTo("/v2/connectors/" + connectorId)));
    }

    @Test
    @DisplayName("Should fail clearly when connectorIds is empty instead of vacuously succeeding")
    void failsWhenConnectorIdsIsEmpty(WireMockRuntimeInfo wmRuntimeInfo) {
        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of()))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString("connectorIds must not be empty"));
    }

    @Test
    @DisplayName("Should fail clearly naming the index when connectorIds contains a blank element")
    void failsWhenConnectorIdsContainsBlankElement(WireMockRuntimeInfo wmRuntimeInfo) {
        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of("valid_connector", "   ")))
            .wait(Property.ofValue(false))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString("connectorIds[1]"));
        assertThat(thrown.getMessage(), containsString("null or blank"));

        verify(exactly(0), getRequestedFor(urlMatching("/v2/connectors/.*")));
    }

    @Test
    @DisplayName("Should fail clearly naming the index when connectorIds contains a null element")
    void failsWhenConnectorIdsContainsNullElement(WireMockRuntimeInfo wmRuntimeInfo) {
        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(Arrays.asList("valid_connector", null)))
            .wait(Property.ofValue(false))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString("connectorIds[1]"));
        assertThat(thrown.getMessage(), containsString("null or blank"));

        verify(exactly(0), getRequestedFor(urlMatching("/v2/connectors/.*")));
    }

    @Test
    @DisplayName("Should reject pollFrequency greater than maxDuration before polling")
    void failsWhenPollFrequencyExceedsMaxDuration(WireMockRuntimeInfo wmRuntimeInfo) {
        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of("any_connector")))
            .pollFrequency(Property.ofValue(Duration.ofMinutes(10)))
            .maxDuration(Property.ofValue(Duration.ofMinutes(2)))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString("pollFrequency"));
        assertThat(thrown.getMessage(), containsString("maxDuration"));
    }

    @Test
    @DisplayName("Should reject a negative freshnessBuffer before making any HTTP call")
    void failsWhenFreshnessBufferIsNegative(WireMockRuntimeInfo wmRuntimeInfo) {
        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of("any_connector")))
            .wait(Property.ofValue(false))
            .freshnessBuffer(Property.ofValue(Duration.ofMinutes(-5)))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString("freshnessBuffer must not be negative"));

        verify(exactly(0), getRequestedFor(urlMatching("/v2/connectors/.*")));
    }

    @Test
    @DisplayName("Should not fail fast but time out when allowTerminal is true and a stale connector is paused")
    void allowTerminalLetsStalePausedConnectorTimeOutInsteadOfFailingFast(WireMockRuntimeInfo wmRuntimeInfo) {
        String connectorId = "allow_terminal_stale_paused_connector";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, null, null, true, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .allowTerminal(Property.ofValue(true))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(1)))
            .build();

        TimeoutException thrown = assertThrows(TimeoutException.class, () -> task.run(runContext()));
        assertThat(thrown.getMessage(), containsString(connectorId));
        assertThat(thrown.getMessage(), containsString("did not become fresh"));
    }

    @Test
    @DisplayName("Should succeed with a normal output when the asset emitter is unavailable (OSS edition)")
    void succeedsWithNormalOutputWhenAssetEmitterThrowsUnsupportedOperationException(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "oss_asset_connector";
        String schema = "oss_asset_connector_schema";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-10), null, false, "connected", 360, schema))
                )
        );

        // Mirrors AssetManagerFactory#of(boolean)'s real OSS default, where emit() throws because the
        // EE emitter isn't available.
        assetManagerFactory.throwUnsupportedOperationOnEmit(true);

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        Status.Output output = assertDoesNotThrow(() -> task.run(runContextFactory.of(task, Map.of())));

        assertThat(output.getConnectors(), aMapWithSize(1));
        assertThat(output.getConnectors().get(connectorId).getFresh(), is(true));
        assertThat(assetManagerFactory.allEmitted(), is(empty()));
    }

    @Test
    @DisplayName("Should wait for the slowest connector when multiple connectors become fresh at different times")
    void waitsForTheSlowestConnectorAmongMultiple(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorIdA = "staggered_connector_a";
        String connectorIdB = "staggered_connector_b";
        String scenario = "status-staggered-multi-connector";

        // A is fresh from the very first poll.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdA))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdA, isoNow(-5), null, false, "connected", 360, null))
                )
        );

        // B stays stale for the first two poll cycles and only becomes fresh on the third.
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdB))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("STALE_ONCE_MORE")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdB, isoNow(-600), null, false, "connected", 360, null))
                )
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdB))
                .inScenario(scenario)
                .whenScenarioStateIs("STALE_ONCE_MORE")
                .willSetStateTo("FRESH")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdB, isoNow(-600), null, false, "connected", 360, null))
                )
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorIdB))
                .inScenario(scenario)
                .whenScenarioStateIs("FRESH")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorIdB, isoNow(-5), null, false, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorIdA, connectorIdB)))
            .pollFrequency(Property.ofValue(Duration.ofMillis(200)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        Status.Output output = task.run(runContext());

        assertThat(output.getConnectors(), aMapWithSize(2));
        assertThat(output.getConnectors().get(connectorIdA).getFresh(), is(true));
        assertThat(output.getConnectors().get(connectorIdB).getFresh(), is(true));

        // B was stale on the first two polls, so it must have been polled more than once before succeeding.
        verify(moreThan(1), getRequestedFor(urlEqualTo("/v2/connectors/" + connectorIdB)));
    }

    @Test
    @DisplayName("Should recover from a transient error mid-poll and eventually succeed")
    void recoversFromTransientErrorMidPollInWaitMode(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String connectorId = "transient_error_connector";
        String scenario = "status-transient-error-recovery";

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("RECOVERED")
                .willReturn(aResponse().withStatus(503))
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + connectorId))
                .inScenario(scenario)
                .whenScenarioStateIs("RECOVERED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(connectorId, isoNow(-5), null, false, "connected", 360, null))
                )
        );

        Status task = Status.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(connectorId)))
            // A single attempt so the 503 bubbles up to the poll loop's own transient-error handling
            // instead of being absorbed by the low-level request() retry.
            .maxAttempts(Property.ofValue(1))
            .pollFrequency(Property.ofValue(Duration.ofMillis(200)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        Status.Output output = assertDoesNotThrow(() -> task.run(runContext()));

        assertThat(output.getConnectors().get(connectorId).getFresh(), is(true));
    }

    private RunContext runContext() {
        return runContextFactory.of(ImmutableMap.of());
    }

    private static String isoNow(long minutesOffset) {
        return ZonedDateTime.now().plusMinutes(minutesOffset).toInstant().toString();
    }

    private static String connectorBody(String connectorId, String succeededAt, String failedAt) {
        return connectorBody(connectorId, succeededAt, failedAt, false, "connected", 360, null);
    }

    private static String connectorBody(
        String connectorId,
        String succeededAt,
        String failedAt,
        boolean paused,
        String setupState,
        Integer syncFrequency,
        String schema) {
        return connectorBody(connectorId, succeededAt, failedAt, paused, setupState, syncFrequency, schema, "some_group");
    }

    private static String connectorBody(
        String connectorId,
        String succeededAt,
        String failedAt,
        boolean paused,
        String setupState,
        Integer syncFrequency,
        String schema,
        String groupId) {
        return """
            {
              "code": "Success",
              "data": {
                "id": "%s",
                "name": "%s",
                %s
                "paused": %s,
                "version": 1,
                "status": {
                  "setup_state": "%s",
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
                "sync_frequency": %s,
                "pause_after_trial": false,
                "group_id": "%s",
                "connected_by": "some_user",
                "created_at": "2025-01-01T00:00:00.000Z",
                "failed_at": %s,
                "schedule_type": "auto"
              }
            }
            """.formatted(
            connectorId,
            connectorId,
            schemaField(schema),
            paused,
            setupState,
            jsonValue(succeededAt),
            syncFrequency == null ? "null" : syncFrequency.toString(),
            groupId,
            jsonValue(failedAt)
        );
    }

    private static String schemaField(String schema) {
        return schema == null ? "" : "\"schema\": \"" + schema + "\",";
    }

    private static String jsonValue(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
