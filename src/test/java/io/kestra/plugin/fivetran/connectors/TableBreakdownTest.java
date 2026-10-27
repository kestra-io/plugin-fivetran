package io.kestra.plugin.fivetran.connectors;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.models.property.Property;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.Await;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.fivetran.TestAssetManagerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The per-table breakdown and freshness outputs of {@code Sync} and {@code Status}.
 */
@KestraTest
@WireMockTest
class TableBreakdownTest {
    private static final String CONNECTOR_ID = "sultry_glowing";
    private static final String SECOND_CONNECTOR_ID = "second_connector";
    private static final String GROUP_ID = "sepia_inhabit";
    private static final String SCENARIO = "table-breakdown-sync";
    private static final String SCHEMAS = """
        {
          "salesforce": {
            "name_in_destination": "sf",
            "enabled": true,
            "tables": {
              "account": {
                "name_in_destination": "account_dest",
                "enabled": true,
                "sync_mode": "SOFT_DELETE",
                "enabled_patch_settings": {"allowed": true, "reason": "free text secret", "reason_code": "X"},
                "row_filter": {"secret": "filter"},
                "parent_table": "parent_secret",
                "columns": {"ssn": {"enabled": false, "hashed": true}}
              },
              "archived_lead": {"enabled": false, "enabled_patch_settings": {"allowed": false}}
            }
          },
          "unused": {
            "enabled": false,
            "tables": {"whatever": {"enabled": true}}
          }
        }
        """;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private TestAssetManagerFactory assetManagerFactory;

    @Inject
    @Named(QueueFactoryInterface.WORKERTASKLOG_NAMED)
    private QueueInterface<LogEntry> logQueue;

    @BeforeEach
    void setUp() {
        assetManagerFactory.clear();
    }

    @Test
    @DisplayName("Should output freshness and an allow-listed Ion breakdown from Sync when it waits")
    void syncOutputsFreshnessAndBreakdown(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubSyncScenario();
        stubSchemas(CONNECTOR_ID, SCHEMAS);

        var task = syncTask(wmRuntimeInfo, true);
        var runContext = runContextFactory.of(task, Map.of());
        var output = task.run(runContext);

        assertThat(output.getFreshness().getFresh(), is(true));
        assertThat(output.getFreshness().getSyncState(), is("scheduled"));
        assertThat(output.getFreshness().getUpdateState(), is("on_schedule"));
        assertThat(output.getFreshness().getAlertCount(), is(3));
        assertThat(output.getFreshness().getAlertCodes(), containsInAnyOrder("resync_table_warning", "setup_failed"));
        assertThat(output.getTableCount(), is(3));
        assertThat(output.getEnabledTableCount(), is(1));

        var rows = readRows(runContext, output.getTables());
        assertThat(rows, hasSize(3));
        assertThat(
            rows.getFirst(),
            is(
                Map.of(
                    "schema", "salesforce",
                    "schemaInDestination", "sf",
                    "schemaEnabled", true,
                    "table", "account",
                    "tableInDestination", "account_dest",
                    "enabled", true,
                    "syncMode", "SOFT_DELETE",
                    "enabledPatchAllowed", true
                )
            )
        );
        var all = rows.toString();
        assertThat(all, not(containsString("secret")));
        assertThat(all, not(containsString("ssn")));
        assertThat(all, not(containsString("hashed")));
    }

    @Test
    @DisplayName("Should build Sync freshness from the pre-trigger snapshot when it does not wait")
    void syncWithoutWaitUsesTheSnapshot(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-1440)))));
        stubFor(post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync")).willReturn(json("""
            {"code": "Success", "message": "Sync has been successfully triggered"}
            """)));
        stubSchemas(CONNECTOR_ID, SCHEMAS);

        var task = syncTask(wmRuntimeInfo, false);
        var output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output.getFreshness().getFresh(), nullValue());
        assertThat(output.getFreshness().getSucceededAt(), notNullValue());
        assertThat(output.getTableCount(), is(3));
    }

    @Test
    @DisplayName("Should write one Ion file per connector from Status")
    void statusWritesOneFilePerConnector(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-10)))));
        stubFor(get(urlEqualTo("/v2/connectors/" + SECOND_CONNECTOR_ID)).willReturn(json(connectorBody(SECOND_CONNECTOR_ID, isoNow(-10)))));
        stubSchemas(CONNECTOR_ID, SCHEMAS);
        stubSchemas(SECOND_CONNECTOR_ID, """
            {"public": {"enabled": true, "tables": {"lead": {"enabled": true}}}}
            """);

        var task = statusTask(wmRuntimeInfo, List.of(CONNECTOR_ID, SECOND_CONNECTOR_ID), false, false);
        var runContext = runContextFactory.of(task, Map.of());
        var output = task.run(runContext);

        var first = output.getConnectors().get(CONNECTOR_ID);
        var second = output.getConnectors().get(SECOND_CONNECTOR_ID);
        assertThat(first.getTables(), not(second.getTables()));
        assertThat(first.getTableCount(), is(3));
        assertThat(first.getEnabledTableCount(), is(1));
        assertThat(first.getUpdateState(), is("on_schedule"));
        assertThat(first.getAlertCount(), is(3));
        assertThat(first.getAlertCodes(), containsInAnyOrder("resync_table_warning", "setup_failed"));
        assertThat(second.getTableCount(), is(1));
        assertThat(readRows(runContext, second.getTables()).getFirst().get("tableInDestination"), is("lead"));
    }

    @Test
    @DisplayName("Should write an empty but valid Ion file when the connector has no schemas")
    void emptySchemasYieldAnEmptyFile(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-10)))));
        stubSchemas(CONNECTOR_ID, "{}");

        var task = statusTask(wmRuntimeInfo, List.of(CONNECTOR_ID), false, false);
        var runContext = runContextFactory.of(task, Map.of());
        var state = task.run(runContext).getConnectors().get(CONNECTOR_ID);

        assertThat(state.getTableCount(), is(0));
        assertThat(state.getEnabledTableCount(), is(0));
        assertThat(readRows(runContext, state.getTables()), is(empty()));
    }

    @Test
    @DisplayName("Should still produce the breakdown for a paused connector")
    void pausedConnectorStillGetsABreakdown(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-10)).replace("\"paused\": false", "\"paused\": true")))
        );
        stubSchemas(CONNECTOR_ID, SCHEMAS);

        var task = statusTask(wmRuntimeInfo, List.of(CONNECTOR_ID), false, false);
        var state = task.run(runContextFactory.of(task, Map.of())).getConnectors().get(CONNECTOR_ID);

        assertThat(state.getPaused(), is(true));
        assertThat(state.getTableCount(), is(3));
        assertThat(state.getTables(), notNullValue());
    }

    @Test
    @DisplayName("Should succeed with null breakdown fields and a warning when the schema read fails")
    void failedSchemaReadNeverFailsTheTask(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-10)))));
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas"))
                .willReturn(aResponse().withStatus(500).withBody("{\"code\": \"InternalError\", \"message\": \"leaky-body\"}"))
        );

        var logs = new CopyOnWriteArrayList<LogEntry>();
        var receive = TestsUtils.receive(logQueue, l -> logs.add(l.getLeft()));

        var task = statusTask(wmRuntimeInfo, List.of(CONNECTOR_ID), false, false);
        var state = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of())).getConnectors().get(CONNECTOR_ID);

        assertThat(state.getTables(), nullValue());
        assertThat(state.getTableCount(), nullValue());
        assertThat(state.getEnabledTableCount(), nullValue());
        assertThat(state.getFresh(), is(true));

        Await.until(
            () -> logs.stream().anyMatch(l -> l.getMessage().contains("Could not read the schema config of connector '" + CONNECTOR_ID + "' (HTTP 500)")),
            Duration.ofMillis(50),
            Duration.ofSeconds(10)
        );
        assertThat(logs.stream().noneMatch(l -> l.getMessage().contains("leaky-body")), is(true));
        receive.blockLast(Duration.ofMillis(10));
    }

    @Test
    @DisplayName("Should request /schemas exactly once per connector when lineage is on, and still emit table assets")
    void lineageSharesTheSingleSchemaRead(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-10)))));
        stubFor(get(urlEqualTo("/v2/destinations/" + GROUP_ID)).willReturn(json("""
            {"code": "Success", "data": {"id": "%s", "group_id": "%s", "service": "snowflake", "config": {"database": "analytics"}}}
            """.formatted(GROUP_ID, GROUP_ID))));
        stubSchemas(CONNECTOR_ID, SCHEMAS);

        var task = statusTask(wmRuntimeInfo, List.of(CONNECTOR_ID), false, true);
        var state = task.run(runContextFactory.of(task, Map.of())).getConnectors().get(CONNECTOR_ID);

        verify(exactly(1), getRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")));
        assertThat(state.getTableCount(), is(3));
        var ids = assetManagerFactory.allEmitted().stream().flatMap(e -> e.outputs().stream()).map(a -> a.getId()).toList();
        assertThat(ids, contains(GROUP_ID + ".connector_schema", "analytics.sf.account_dest"));
    }

    @Test
    @DisplayName("Should never request the destination when lineage is off")
    void noDestinationReadWithoutLineage(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-10)))));
        stubSchemas(CONNECTOR_ID, SCHEMAS);

        var task = statusTask(wmRuntimeInfo, List.of(CONNECTOR_ID), false, false);
        task.run(runContextFactory.of(task, Map.of()));

        verify(exactly(0), getRequestedFor(urlEqualTo("/v2/destinations/" + GROUP_ID)));
        verify(exactly(1), getRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")));
    }

    @Test
    @DisplayName("Should read the schema config once, after polling, when Status waits for freshness")
    void waitingStatusReadsSchemasOnlyOnTheFinalState(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario("status-poll").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("FRESH")
                .willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-1440))))
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario("status-poll").whenScenarioStateIs("FRESH")
                .willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-10))))
        );
        stubSchemas(CONNECTOR_ID, SCHEMAS);

        var task = statusTask(wmRuntimeInfo, List.of(CONNECTOR_ID), true, false);
        var state = task.run(runContextFactory.of(task, Map.of())).getConnectors().get(CONNECTOR_ID);

        assertThat(state.getFresh(), is(true));
        verify(exactly(1), getRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")));
    }

    private void stubSyncScenario() {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SCENARIO).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-1440))))
        );
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SCENARIO).whenScenarioStateIs(Scenario.STARTED).willSetStateTo("SYNCED")
                .willReturn(json("""
                    {"code": "Success", "message": "Sync has been successfully triggered"}
                    """))
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SCENARIO).whenScenarioStateIs("SYNCED")
                .willReturn(json(connectorBody(CONNECTOR_ID, isoNow(-1))))
        );
    }

    private Sync syncTask(WireMockRuntimeInfo wmRuntimeInfo, boolean wait) {
        return Sync.builder()
            .id("sync")
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .wait(Property.ofValue(wait))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();
    }

    private Status statusTask(WireMockRuntimeInfo wmRuntimeInfo, List<String> connectorIds, boolean wait, boolean assets) {
        var builder = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(connectorIds))
            .wait(Property.ofValue(wait))
            .pollFrequency(Property.ofValue(Duration.ofMillis(100)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .maxAttempts(Property.ofValue(1));
        if (assets) {
            builder.assets(new AssetsDeclaration(true, List.of(), List.of()));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readRows(RunContext runContext, URI uri) throws Exception {
        try (InputStream in = runContext.storage().getFile(uri)) {
            return FileSerde.readAll(in).map(row -> (Map<String, Object>) row).collectList().block();
        }
    }

    private static void stubSchemas(String connectorId, String schemas) {
        stubFor(get(urlEqualTo("/v2/connectors/" + connectorId + "/schemas")).willReturn(json("""
            {"code": "Success", "data": {"enable_new_by_default": true, "schemas": %s}}
            """.formatted(schemas))));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String isoNow(long minutesOffset) {
        return ZonedDateTime.now().plusMinutes(minutesOffset).toInstant().toString();
    }

    private static String connectorBody(String connectorId, String succeededAt) {
        return """
            {
              "code": "Success",
              "data": {
                "id": "%s",
                "name": "display name",
                "destination_schema": {"name": "connector_schema"},
                "paused": false,
                "status": {
                  "setup_state": "connected",
                  "sync_state": "scheduled",
                  "update_state": "on_schedule",
                  "schema_status": "ready",
                  "tasks": [{"code": "setup_failed", "message": "free text", "details": {"a": 1}}],
                  "warnings": [
                    {"code": "resync_table_warning", "message": "Table needs a resync"},
                    {"code": "resync_table_warning", "message": "Another table"}
                  ]
                },
                "succeeded_at": "%s",
                "failed_at": null,
                "sync_frequency": 360,
                "group_id": "%s",
                "schedule_type": "auto"
              }
            }
            """.formatted(connectorId, succeededAt, GROUP_ID);
    }
}
