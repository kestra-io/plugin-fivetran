package io.kestra.plugin.fivetran.connectors;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.assets.External;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.AssetFailureBehavior;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.fivetran.TestAssetManagerFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class SourceInputAssetsTest {
    private static final String CONNECTOR_ID = "sultry_glowing";
    private static final String GROUP_ID = "sepia_inhabit";
    private static final String TABLE_TYPE = "io.kestra.plugin.ee.assets.Table";
    private static final String DATASET_TYPE = "io.kestra.plugin.ee.assets.Dataset";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private TestAssetManagerFactory assetManagerFactory;

    @BeforeEach
    void setUp() {
        assetManagerFactory.clear();
    }

    private record Source(String service, String config, String schema, String table, String expectedId) {
    }

    @Test
    @DisplayName("Should use the source table as a Table input for database connectors")
    void databaseSourceInput(WireMockRuntimeInfo wm) throws Exception {
        var sources = List.of(
            new Source("postgres", "{\"database\": \"shop\", \"password\": \"******\"}", "public", "orders", "shop.public.orders"),
            new Source("mysql", "{\"database\": \"ignored\"}", "shop", "orders", "shop.orders"),
            new Source("sql_server", "{\"database\": \"ShopDb\"}", "dbo", "orders", "ShopDb.dbo.orders"),
            new Source("snowflake_db", "{\"database\": \"RAW\"}", "PUBLIC", "ORDERS", "RAW.PUBLIC.ORDERS"),
            new Source("bigquery_db", "{\"project_id\": \"acme-prod\"}", "sales", "orders", "acme-prod.sales.orders")
        );

        for (var source : sources) {
            assetManagerFactory.clear();
            stubAll(source.service(), source.config(), source.schema(), source.table(), "orders");

            var task = statusTask(wm, null);
            task.run(runContextFactory.of(task, Map.of()));

            var input = tableEmits().getFirst().inputs().getFirst();
            assertThat(source.service(), input.id(), is(source.expectedId()));
            assertThat(source.service(), input.type(), is(TABLE_TYPE));
        }
    }

    @Test
    @DisplayName("Should keep a SaaS source as an External input")
    void saasSourceInput(WireMockRuntimeInfo wm) throws Exception {
        stubAll("salesforce", "{\"instance_url\": \"https://acme.my.salesforce.com\"}", "salesforce", "account", "account");

        var task = statusTask(wm, null);
        task.run(runContextFactory.of(task, Map.of()));

        var input = tableEmits().getFirst().inputs().getFirst();
        assertThat(input.id(), is(CONNECTOR_ID + ".salesforce.account"));
        assertThat(input.type(), is(External.ASSET_TYPE));
    }

    @Test
    @DisplayName("Should fall back to External when a database connector's name is masked")
    void maskedConfigFallsBackToExternal(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"******\"}", "public", "orders", "orders");

        var task = statusTask(wm, null);
        task.run(runContextFactory.of(task, Map.of()));

        var input = tableEmits().getFirst().inputs().getFirst();
        assertThat(input.id(), is(CONNECTOR_ID + ".public.orders"));
        assertThat(input.type(), is(External.ASSET_TYPE));
    }

    @Test
    @DisplayName("Should fall back to External when the connector reports no service or config")
    void missingConfigFallsBackToExternal(WireMockRuntimeInfo wm) throws Exception {
        stubAll(null, null, "public", "orders", "orders");

        var task = statusTask(wm, null);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(tableEmits().getFirst().inputs().getFirst().type(), is(External.ASSET_TYPE));
    }

    @Test
    @DisplayName("Should emit the connector Dataset alone, then one bundle per table")
    void oneEmitPerTableAndADatasetEmit(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"shop\"}", "public", "orders", "orders");
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")).willReturn(
                json(schemasBody("""
                    {"public": {"name_in_destination": "public", "enabled": true, "tables": {
                      "orders": {"enabled": true, "sync_mode": "SOFT_DELETE"},
                      "customers": {"enabled": true},
                      "audit": {"enabled": false}
                    }}}
                    """))
            )
        );

        var task = statusTask(wm, null);
        task.run(runContextFactory.of(task, Map.of()));

        var emitted = assetManagerFactory.allEmitted();
        assertThat(emitted, hasSize(3));
        assertThat(emitted.getFirst().inputs(), hasSize(0));
        assertThat(emitted.getFirst().outputs().getFirst().getType(), is(DATASET_TYPE));
        assertThat(emitted.getFirst().outputs().getFirst().getId(), is(GROUP_ID + ".analytics_schema"));
        for (var emit : emitted.subList(1, 3)) {
            assertThat(emit.inputs(), hasSize(1));
            assertThat(emit.outputs(), hasSize(1));
        }
        assertThat(
            emitted.subList(1, 3).stream().map(e -> e.outputs().getFirst().getId()).toList(),
            contains("analytics.public.orders", "analytics.public.customers")
        );
    }

    @Test
    @DisplayName("Should add the sync and source metadata to the table asset")
    void tableMetadata(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"shop\"}", "public", "orders", "orders");
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")).willReturn(
                json(schemasBody("""
                    {"public": {"enabled": true, "tables": {"orders": {"enabled": true, "sync_mode": "HISTORY"}}}}
                    """))
            )
        );

        var task = statusTask(wm, null);
        task.run(runContextFactory.of(task, Map.of()));

        var metadata = tableEmits().getFirst().outputs().getFirst().getMetadata();
        assertThat(metadata.get("lastSyncedAt"), is(SUCCEEDED_AT));
        assertThat(metadata.get("syncState"), is("scheduled"));
        assertThat(metadata.get("service"), is("postgres"));
        assertThat(metadata.get("sourceSchema"), is("public"));
        assertThat(metadata.get("sourceTable"), is("orders"));
        assertThat(metadata.get("syncMode"), is("HISTORY"));
        // Existing keys stay.
        assertThat(metadata.get("connectorId"), is(CONNECTOR_ID));
        assertThat(metadata.get("database"), is("analytics"));
        assertThat(metadata.get("system"), is("snowflake"));
        assertThat(metadata.containsKey("config"), is(false));
    }

    @Test
    @DisplayName("Should fail the task when an emit fails and assetFailureBehavior is FAIL")
    void failBehaviorFailsTheTask(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"shop\"}", "public", "orders", "orders");
        assetManagerFactory.failOnEmit(new IllegalArgumentException("boom"));

        var task = statusTask(wm, Property.ofValue(AssetFailureBehavior.FAIL));
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(task, Map.of())));
        assertThat(ex.getMessage(), containsString("boom"));
    }

    @Test
    @DisplayName("Should end in WARNING when an emit fails and assetFailureBehavior is WARN")
    void warnBehaviorEndsInWarning(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"shop\"}", "public", "orders", "orders");
        assetManagerFactory.failOnEmit(new IllegalArgumentException("boom"));

        var task = statusTask(wm, Property.ofValue(AssetFailureBehavior.WARN));
        var output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output.finalState().orElse(null), is(State.Type.WARNING));
        assertThat(output.getConnectors().get(CONNECTOR_ID).getFresh(), is(true));
    }

    @Test
    @DisplayName("Should default to WARN when assetFailureBehavior is unset or cannot be rendered")
    void unsetOrUnrenderableBehaviorWarns(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"shop\"}", "public", "orders", "orders");
        assetManagerFactory.failOnEmit(new IllegalArgumentException("boom"));

        var unset = statusTask(wm, null);
        assertThat(unset.run(runContextFactory.of(unset, Map.of())).finalState().orElse(null), is(State.Type.WARNING));

        var unrenderable = statusTask(wm, Property.<AssetFailureBehavior> ofExpression("{{ missing.variable }}"));
        assertThat(unrenderable.run(runContextFactory.of(unrenderable, Map.of())).finalState().orElse(null), is(State.Type.WARNING));
    }

    @Test
    @DisplayName("Should only log when an emit fails and assetFailureBehavior is IGNORE")
    void ignoreBehaviorKeepsTheState(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"shop\"}", "public", "orders", "orders");
        assetManagerFactory.failOnEmit(new IllegalArgumentException("boom"));

        var task = statusTask(wm, Property.ofValue(AssetFailureBehavior.IGNORE));
        var output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output.finalState().isPresent(), is(false));
    }

    @Test
    @DisplayName("Should skip silently, even under FAIL, when the edition cannot emit assets")
    void unsupportedEmitterIsASilentSkip(WireMockRuntimeInfo wm) throws Exception {
        stubAll("postgres", "{\"database\": \"shop\"}", "public", "orders", "orders");
        assetManagerFactory.throwUnsupportedOperationOnEmit(true);

        var task = statusTask(wm, Property.ofValue(AssetFailureBehavior.FAIL));
        var output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output.finalState().isPresent(), is(false));
        assertThat(assetManagerFactory.allEmitted(), hasSize(0));
    }

    private Status statusTask(WireMockRuntimeInfo wm, Property<AssetFailureBehavior> behavior) {
        return Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(CONNECTOR_ID)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(Property.ofValue(true), null, null, behavior))
            .build();
    }

    private List<AssetEmit> tableEmits() {
        return assetManagerFactory.allEmitted().stream().filter(e -> !e.inputs().isEmpty()).toList();
    }

    private static final String SUCCEEDED_AT = ZonedDateTime.now().minusMinutes(10).toInstant().toString();

    private void stubAll(String service, String config, String schema, String table, String destinationTable) {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(service, config))));
        stubFor(
            get(urlEqualTo("/v2/destinations/" + GROUP_ID)).willReturn(
                json("""
                    {"code": "Success", "data": {"id": "%s", "group_id": "%s", "service": "snowflake", "config": {"database": "analytics"}}}
                    """.formatted(GROUP_ID, GROUP_ID))
            )
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")).willReturn(
                json(schemasBody("""
                    {"%s": {"name_in_destination": "analytics_schema", "enabled": true, "tables": {"%s": {"name_in_destination": "%s", "enabled": true}}}}
                    """.formatted(schema, table, destinationTable)))
            )
        );
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String schemasBody(String schemas) {
        return """
            {"code": "Success", "data": {"enable_new_by_default": true, "schemas": %s}}
            """.formatted(schemas);
    }

    private static String connectorBody(String service, String config) {
        return """
            {
              "code": "Success",
              "data": {
                "id": "%s",
                "name": "display name",
                "destination_schema": {"name": "analytics_schema"},
                %s
                %s
                "paused": false,
                "status": {"setup_state": "connected", "sync_state": "scheduled", "schema_status": "ready"},
                "succeeded_at": "%s",
                "failed_at": null,
                "sync_frequency": 360,
                "group_id": "%s",
                "schedule_type": "auto"
              }
            }
            """.formatted(
            CONNECTOR_ID,
            service != null ? "\"service\": \"" + service + "\"," : "",
            config != null ? "\"config\": " + config + "," : "",
            SUCCEEDED_AT,
            GROUP_ID
        );
    }
}
