package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.google.common.collect.ImmutableMap;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
@WireMockTest
class SyncPausedConnectorTest {
    private static final String CONNECTOR_ID = "paused_connector";
    private static final String SUCCEEDED_AT = "2026-07-27T13:00:00.000Z";

    // What Fivetran answers to a sync request on a paused connector: 200 Success, and no sync starts.
    private static final String PAUSED_BODY = """
        {
          "code": "Success",
          "message": "Connection with id 'paused_connector' is paused"
        }
        """;

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @DisplayName("Should fail right away on a paused connector instead of triggering and waiting until maxDuration")
    void pausedConnectorFailsWithoutTriggering(WireMockRuntimeInfo wmRuntimeInfo) {
        stubConnector(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> run(wmRuntimeInfo, true));

        assertTrue(thrown.getMessage().contains("is paused"), "message was: " + thrown.getMessage());
        verify(exactly(0), postRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")));
    }

    @Test
    @DisplayName("Should fail when Fivetran answers that the connector is paused, even with wait: false")
    void pausedAnswerToTheTriggerFails(WireMockRuntimeInfo wmRuntimeInfo) {
        // Not paused when read, paused by the time the trigger arrives.
        stubConnector(false);
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(PAUSED_BODY))
        );

        for (boolean wait : new boolean[] { true, false }) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> run(wmRuntimeInfo, wait));
            assertTrue(thrown.getMessage().contains("is paused"), "message was: " + thrown.getMessage());
        }
        // One status read per run: no polling after the paused answer.
        verify(exactly(2), getRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)));
    }

    private void run(WireMockRuntimeInfo wmRuntimeInfo, boolean wait) throws Exception {
        Sync.builder()
            .id("sync_" + IdUtils.create())
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .wait(Property.ofValue(wait))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .build()
            .run(runContextFactory.of(ImmutableMap.of()));
    }

    private static void stubConnector(boolean paused) {
        var body = SyncTestFixtures.connectorBody(CONNECTOR_ID, SUCCEEDED_AT, null, paused ? "paused" : "scheduled")
            .replace("\"paused\": false", "\"paused\": " + paused);
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body))
        );
    }
}
