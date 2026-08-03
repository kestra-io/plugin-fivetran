package io.kestra.plugin.fivetran.connectors;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.common.collect.ImmutableMap;

import io.kestra.core.http.client.HttpClientRequestException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
@WireMockTest
class SyncRetryTest {
    private static final String CONNECTOR_ID = "arriving_atone";
    private static final String SYNC_SCENARIO = "connector-sync-retry";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @DisplayName("Should retry a transient 429 while polling and eventually succeed")
    void transient429DuringPollIsRetried(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLLING")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING")
                .willSetStateTo("POLLING_RECOVERED")
                .willReturn(aResponse().withStatus(429))
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING_RECOVERED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null))
                )
        );

        assertDoesNotThrow(() -> syncTask(wmRuntimeInfo.getHttpBaseUrl()).run(runContext()));
    }

    @Test
    @DisplayName("Should retry a transient 503 while polling and eventually succeed")
    void transient503DuringPollIsRetried(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLLING")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING")
                .willSetStateTo("POLLING_RECOVERED")
                .willReturn(aResponse().withStatus(503))
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING_RECOVERED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null))
                )
        );

        assertDoesNotThrow(() -> syncTask(wmRuntimeInfo.getHttpBaseUrl()).run(runContext()));
    }

    @Test
    @DisplayName("Should fail without retrying when triggering the sync returns a persistent 500")
    void persistentErrorTriggeringSyncFailsWithoutRetry(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
        );

        assertThrows(HttpClientResponseException.class, () -> syncTask(wmRuntimeInfo.getHttpBaseUrl()).run(runContext()));

        verify(exactly(1), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should wait pollFrequency between status checks")
    void customPollFrequencyIsHonored(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLL_1")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLL_1")
                .willSetStateTo("POLL_2")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLL_2")
                .willSetStateTo("POLL_DONE")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLL_DONE")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null))
                )
        );

        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .pollFrequency(Property.ofValue(Duration.ofSeconds(1)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        Instant start = Instant.now();
        assertDoesNotThrow(() -> task.run(runContext()));
        long elapsedMs = Duration.between(start, Instant.now()).toMillis();

        // Two 1s intervals must elapse before the third poll sees completion. Warm-up only adds time,
        // so a lower bound is not flaky, and it would fail if pollFrequency were ignored (sub-second).
        assertTrue(elapsedMs >= 1800, "expected at least two 1s poll intervals, elapsed " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("Should keep polling when a transient failure exhausts the low-level retry")
    void pollLoopAbsorbsExhaustedTransientRetry(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLLING")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING")
                .willSetStateTo("POLLING_RECOVERED")
                .willReturn(aResponse().withStatus(503))
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING_RECOVERED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null))
                )
        );

        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .maxAttempts(Property.ofValue(1))
            .build();

        assertDoesNotThrow(() -> task.run(runContext()));
    }

    @Test
    @DisplayName("Should fail fast on a non-transient read error while polling")
    void nonTransientReadFailureDuringPollFailsFast(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLLING")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING")
                .willReturn(aResponse().withStatus(404))
        );

        assertThrows(HttpClientResponseException.class, () -> syncTask(wmRuntimeInfo.getHttpBaseUrl()).run(runContext()));
    }

    @Test
    @DisplayName("Should fail fast on a connection-level failure while polling instead of hanging")
    void connectionFailureDuringPollFailsFast(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLLING")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
        );

        assertThrows(HttpClientRequestException.class, () -> syncTask(wmRuntimeInfo.getHttpBaseUrl()).run(runContext()));
    }

    @Test
    @DisplayName("Request-level retry should recover within a single poll")
    void requestLevelRetryRecoversWithinOnePoll(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLLING")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING")
                .willSetStateTo("POLLING_RECOVERED")
                .willReturn(aResponse().withStatus(503))
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING_RECOVERED")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody("2026-07-27T13:13:08.389Z", null))
                )
        );

        // pollFrequency > maxDuration means a second poll iteration can never run, so completion here
        // proves the recovery happened inside a single request() via the retry wrapper.
        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .pollFrequency(Property.ofValue(Duration.ofSeconds(10)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(3)))
            .maxAttempts(Property.ofValue(3))
            .initialRetryDelay(Property.ofValue(Duration.ofMillis(50)))
            .build();

        assertDoesNotThrow(() -> task.run(runContext()));

        // 1 pre-trigger fetch + 2 in-request attempts (503 then 200) on the single poll.
        verify(exactly(3), getRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)));
    }

    @Test
    @DisplayName("Should name the last transient error when polling times out")
    void timeoutNamesLastTransientError(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(connectorBody(null, null))
                )
        );

        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("POLLING")
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SYNC_TRIGGERED_BODY)
                )
        );

        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO)
                .whenScenarioStateIs("POLLING")
                .willReturn(aResponse().withStatus(503))
        );

        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .pollFrequency(Property.ofValue(Duration.ofMillis(200)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(2)))
            .maxAttempts(Property.ofValue(1))
            .build();

        TimeoutException thrown = assertThrows(TimeoutException.class, () -> task.run(runContext()));
        assertTrue(thrown.getMessage().contains("last error while polling"), "message was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("A persistent transport failure exhausts the retry and surfaces the real cause, not RetryFailed")
    void transportFailureExhaustsRetryAndSurfacesRealCause() {
        // Port 1 is never bound, so every attempt gets a connection-refused (a ConnectException, classified
        // transient and retried). After the retry is exhausted, request()'s failureFunction must surface the
        // real HttpClientException with the connection-refused cause, not leak RetryUtils' RetryFailed.
        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue("http://127.0.0.1:1"))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .maxAttempts(Property.ofValue(3))
            .initialRetryDelay(Property.ofValue(Duration.ofMillis(20)))
            .build();

        HttpClientRequestException thrown = assertThrows(HttpClientRequestException.class, () -> task.run(runContext()));
        assertTrue(thrown.getMessage().contains("Connection refused"), "message was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("Should reject maxAttempts below 1 with a clear message instead of an opaque Failsafe error")
    void maxAttemptsBelowOneIsRejected(WireMockRuntimeInfo wmRuntimeInfo) {
        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .maxAttempts(Property.ofValue(0))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertTrue(thrown.getMessage().contains("maxAttempts must be at least 1"), "message was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("Should reject a non-positive pollFrequency before triggering the sync")
    void pollFrequencyNotPositiveIsRejected(WireMockRuntimeInfo wmRuntimeInfo) {
        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .pollFrequency(Property.ofValue(Duration.ZERO))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertTrue(thrown.getMessage().contains("pollFrequency must be a positive duration"), "message was: " + thrown.getMessage());
        // Rejected before any HTTP call, so the sync trigger never fired.
        verify(exactly(0), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should reject a non-positive initialRetryDelay")
    void initialRetryDelayNotPositiveIsRejected(WireMockRuntimeInfo wmRuntimeInfo) {
        Sync task = Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .initialRetryDelay(Property.ofValue(Duration.ZERO))
            .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext()));
        assertTrue(thrown.getMessage().contains("initialRetryDelay must be a positive duration"), "message was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("A read timeout or refused connection is transient; a bad host or parse error is not")
    void transientReadFailureClassification() {
        assertTrue(Sync.isTransientReadFailure(new RuntimeException("wrapped", new SocketTimeoutException("read timed out"))));
        assertTrue(Sync.isTransientReadFailure(new RuntimeException("wrapped", new ConnectException("connection refused"))));
        assertFalse(Sync.isTransientReadFailure(new RuntimeException("wrapped", new UnknownHostException("bad-host"))));
        assertFalse(Sync.isTransientReadFailure(new RuntimeException("parse", new IOException("no content"))));
    }

    private Sync syncTask(String baseUrl) {
        return Sync.builder()
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(baseUrl))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .maxAttempts(Property.ofValue(2))
            .initialRetryDelay(Property.ofValue(Duration.ofMillis(50)))
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
