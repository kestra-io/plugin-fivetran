package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.fivetran.TestAssetManagerFactory;

import jakarta.inject.Inject;

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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Table-grain lineage. The ids these tests assert are the contract with plugin-dbt: a Fivetran-loaded
 * table and the dbt model reading it only form an edge when both resolve to the same
 * {@code database.schema.name}, so an id change here silently disconnects the graph.
 */
@KestraTest
@WireMockTest
class TableAssetsTest {
    private static final String CONNECTOR_ID = "sultry_glowing";
    private static final String GROUP_ID = "sepia_inhabit";
    private static final String SCHEMA = "salesforce";
    private static final String SYNC_SCENARIO = "table-assets-sync";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private TestAssetManagerFactory assetManagerFactory;

    @BeforeEach
    void setUp() {
        assetManagerFactory.clear();
    }

    @Test
    @DisplayName("Should emit one database.schema.name asset per synced table, alongside the connector asset")
    void emitsTableGrainAssetsMatchingTheDbtIdConvention(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"database": "analytics", "host": "acme.snowflakecomputing.com"}
            """);
        stubSchemas("""
            {
              "salesforce": {
                "name_in_destination": "salesforce",
                "enabled": true,
                "tables": {
                  "account": {"name_in_destination": "account", "enabled": true},
                  "opportunity": {"name_in_destination": "opportunity", "enabled": true}
                }
              }
            }
            """);

        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        List<Asset> assets = emittedAssets();
        assertThat(assets, hasSize(3));

        // The connector-grain asset keeps its existing groupId.schema id so upgrading renames nothing.
        assertThat(ids(assets), contains(GROUP_ID + "." + SCHEMA, "analytics.salesforce.account", "analytics.salesforce.opportunity"));

        Asset account = assets.get(1);
        assertThat(account.getType(), is("io.kestra.plugin.ee.assets.Table"));
        assertThat(account.getMetadata().get("system"), is("snowflake"));
        assertThat(account.getMetadata().get("database"), is("analytics"));
        assertThat(account.getMetadata().get("schema"), is("salesforce"));
        assertThat(account.getMetadata().get("name"), is("account"));
        assertThat(account.getMetadata().get("connectorId"), is(CONNECTOR_ID));
    }

    @Test
    @DisplayName("Should skip disabled schemas and tables, which are never written to the destination")
    void skipsDisabledSchemasAndTables(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"database": "analytics"}
            """);
        stubSchemas("""
            {
              "salesforce": {
                "name_in_destination": "salesforce",
                "enabled": true,
                "tables": {
                  "account": {"name_in_destination": "account", "enabled": true},
                  "archived_lead": {"name_in_destination": "archived_lead", "enabled": false}
                }
              },
              "unused": {
                "name_in_destination": "unused",
                "enabled": false,
                "tables": {"whatever": {"name_in_destination": "whatever", "enabled": true}}
              }
            }
            """);

        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA, "analytics.salesforce.account"));
    }

    @Test
    @DisplayName("Should use name_in_destination rather than the source-side name when Fivetran renames")
    void prefersTheDestinationNameOverTheSourceName(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"database": "analytics"}
            """);
        stubSchemas("""
            {
              "public": {
                "name_in_destination": "salesforce",
                "enabled": true,
                "tables": {
                  "Account": {"name_in_destination": "account", "enabled": true},
                  "no_rename": {"enabled": true}
                }
              }
            }
            """);

        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(
            ids(emittedAssets()),
            containsInAnyOrder(GROUP_ID + "." + SCHEMA, "analytics.salesforce.account", "analytics.salesforce.no_rename")
        );
    }

    @Test
    @DisplayName("Should read the destination once for two connectors sharing a group")
    void resolvesTheDestinationOncePerGroup(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        String secondConnectorId = "second_connector";
        stubConnector(wmRuntimeInfo);
        stubFor(
            get(urlEqualTo("/v2/connectors/" + secondConnectorId))
                .willReturn(json(connectorBody(secondConnectorId, "other_schema")))
        );
        stubDestination("""
            {"database": "analytics"}
            """);
        stubSchemas("""
            {"salesforce": {"name_in_destination": "salesforce", "enabled": true, "tables": {"account": {"enabled": true}}}}
            """);
        stubFor(
            get(urlEqualTo("/v2/connectors/" + secondConnectorId + "/schemas"))
                .willReturn(json(schemasBody("""
                    {"other": {"name_in_destination": "other_schema", "enabled": true, "tables": {"lead": {"enabled": true}}}}
                    """)))
        );

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(CONNECTOR_ID, secondConnectorId)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        task.run(runContextFactory.of(task, Map.of()));

        // Both connectors land in the same destination, so the database lookup must not repeat per connector.
        verify(exactly(1), getRequestedFor(urlEqualTo("/v2/destinations/" + GROUP_ID)));
        assertThat(
            ids(emittedAssets()),
            containsInAnyOrder(
                GROUP_ID + "." + SCHEMA,
                "analytics.salesforce.account",
                GROUP_ID + ".other_schema",
                "analytics.other_schema.lead"
            )
        );
    }

    @Test
    @DisplayName("Should fall back to project_id on a BigQuery destination, which reports no database")
    void resolvesBigQueryProjectIdAsTheDatabase(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"project_id": "acme-warehouse", "data_set_location": "US"}
            """);
        stubSchemas("""
            {"salesforce": {"name_in_destination": "salesforce", "enabled": true, "tables": {"account": {"enabled": true}}}}
            """);

        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA, "acme-warehouse.salesforce.account"));
    }

    @Test
    @DisplayName("Should keep the connector asset and not fail the task when the schema config cannot be read")
    void degradesToTheConnectorAssetWhenSchemasCannotBeRead(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"database": "analytics"}
            """);
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas"))
                .willReturn(
                    aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\": \"NotFound_SchemaConfig\"}")
                )
        );

        // Lineage is metadata about the run, so a failed schema read must not fail a task that succeeded.
        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA));
    }

    @Test
    @DisplayName("Should keep the connector asset when the destination reports no database name")
    void degradesToTheConnectorAssetWhenNoDatabaseIsReported(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"host": "acme.example.com"}
            """);

        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA));
        // Without a database no table-grain id can be composed, so the schema read is not worth paying for.
        verify(exactly(0), getRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")));
    }

    @Test
    @DisplayName("Should make no lineage call at all when assets.enableAuto is unset")
    void makesNoLineageCallWhenAssetsAreDisabled(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"database": "analytics"}
            """);
        stubSchemas("""
            {"salesforce": {"name_in_destination": "salesforce", "enabled": true, "tables": {"account": {"enabled": true}}}}
            """);

        Status task = statusTask(wmRuntimeInfo, false);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(assetManagerFactory.allEmitted(), hasSize(0));
        verify(exactly(0), getRequestedFor(urlEqualTo("/v2/destinations/" + GROUP_ID)));
        verify(exactly(0), getRequestedFor(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")));
    }

    @Test
    @DisplayName("Should emit the same table assets from Sync, which is the only task a Fivetran-scheduled account runs")
    void syncEmitsTheSameTableAssetsAndReturnsAnOutput(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(json(connectorBody(CONNECTOR_ID, SCHEMA, "2026-08-30T10:00:00.000Z")))
        );
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .inScenario(SYNC_SCENARIO).whenScenarioStateIs(Scenario.STARTED).willSetStateTo("SYNCED")
                .willReturn(json("""
                    {"code": "Success", "message": "Sync has been successfully triggered"}
                    """))
        );
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .inScenario(SYNC_SCENARIO).whenScenarioStateIs("SYNCED")
                .willReturn(json(connectorBody(CONNECTOR_ID, SCHEMA, "2026-08-31T10:00:00.000Z")))
        );
        stubDestination("""
            {"database": "analytics"}
            """);
        stubSchemas("""
            {"salesforce": {"name_in_destination": "salesforce", "enabled": true, "tables": {"account": {"enabled": true}}}}
            """);

        Sync task = Sync.builder()
            .id("sync")
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .maxDuration(Property.ofValue(Duration.ofSeconds(5)))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        Sync.Output output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output.getConnectorId(), is(CONNECTOR_ID));
        assertThat(output.getSucceededAt(), is(ZonedDateTime.parse("2026-08-31T10:00:00.000Z", DateTimeFormatter.ISO_ZONED_DATE_TIME)));
        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA, "analytics.salesforce.account"));
    }

    @Test
    @DisplayName("Should return a connector id but no succeededAt when Sync does not wait")
    void syncWithoutWaitReportsNoCompletionTime(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .willReturn(json(connectorBody(CONNECTOR_ID, SCHEMA, "2026-08-30T10:00:00.000Z")))
        );
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .willReturn(json("""
                    {"code": "Success", "message": "Sync has been successfully triggered"}
                    """))
        );

        Sync task = Sync.builder()
            .id("sync")
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .wait(Property.ofValue(false))
            .build();

        Sync.Output output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output, notNullValue());
        assertThat(output.getConnectorId(), is(CONNECTOR_ID));
        // The sync is still running on Fivetran, so there is no completion time to report.
        assertThat(output.getSucceededAt(), nullValue());
    }

    @Test
    @DisplayName("Should not fail an otherwise-successful run when assets.enableAuto cannot be rendered")
    void anUnreadableAssetGateSkipsLineageRatherThanFailingTheTask(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);

        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(CONNECTOR_ID)))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(Property.<Boolean> ofExpression("{{ missing.variable }}"), null, null))
            .build();

        // The status read itself succeeded, so an unreadable lineage gate must not turn the task red.
        Status.Output output = task.run(runContextFactory.of(task, Map.of()));

        assertThat(output.getConnectors().get(CONNECTOR_ID).getFresh(), is(true));
        assertThat(assetManagerFactory.allEmitted(), hasSize(0));
    }

    @Test
    @DisplayName("Should keep the connector asset when the destination read fails outright")
    void degradesToTheConnectorAssetWhenTheDestinationReadFails(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubFor(
            get(urlEqualTo("/v2/destinations/" + GROUP_ID))
                .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody("{}"))
        );

        // A 5xx on a GET is retriable, and this test only cares about the give-up branch, so the default
        // 3 attempts with exponential backoff would just add seconds of sleep to the suite.
        Status task = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(CONNECTOR_ID)))
            .wait(Property.ofValue(false))
            .maxAttempts(Property.ofValue(1))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA));
    }

    @Test
    @DisplayName("Should emit table assets from Sync even when it does not wait for the sync to finish")
    void syncWithoutWaitStillEmitsTableAssets(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubFor(
            get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID))
                .willReturn(json(connectorBody(CONNECTOR_ID, SCHEMA, "2026-08-30T10:00:00.000Z")))
        );
        stubFor(
            post(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/sync"))
                .willReturn(json("""
                    {"code": "Success", "message": "Sync has been successfully triggered"}
                    """))
        );
        stubDestination("""
            {"database": "analytics"}
            """);
        stubSchemas("""
            {"salesforce": {"name_in_destination": "salesforce", "enabled": true, "tables": {"account": {"enabled": true}}}}
            """);

        Sync task = Sync.builder()
            .id("sync")
            .type(Sync.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorId(Property.ofValue(CONNECTOR_ID))
            .wait(Property.ofValue(false))
            .assets(new AssetsDeclaration(true, List.of(), List.of()))
            .build();

        task.run(runContextFactory.of(task, Map.of()));

        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA, "analytics.salesforce.account"));
    }

    @Test
    @DisplayName("Should fall back to naming this plugin when the destination reports no service")
    void fallsBackToFivetranAsSystemWhenTheDestinationHasNoService(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubFor(
            get(urlEqualTo("/v2/destinations/" + GROUP_ID))
                .willReturn(json("""
                    {"code": "Success", "data": {"id": "%s", "group_id": "%s", "config": {"database": "analytics"}}}
                    """.formatted(GROUP_ID, GROUP_ID)))
        );
        stubSchemas("""
            {"salesforce": {"name_in_destination": "salesforce", "enabled": true, "tables": {"account": {"enabled": true}}}}
            """);

        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        List<Asset> assets = emittedAssets();
        assertThat(ids(assets), contains(GROUP_ID + "." + SCHEMA, "analytics.salesforce.account"));
        assertThat(assets.get(1).getMetadata().get("system"), is("fivetran"));
    }

    @Test
    @DisplayName("Should fall back to catalog on a Databricks destination, which reports no database")
    void resolvesDatabricksCatalogAsTheDatabase(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        stubConnector(wmRuntimeInfo);
        stubDestination("""
            {"catalog": "acme_catalog", "server_hostname": "acme.cloud.databricks.com"}
            """);
        stubSchemas("""
            {"salesforce": {"name_in_destination": "salesforce", "enabled": true, "tables": {"account": {"enabled": true}}}}
            """);

        Status task = statusTask(wmRuntimeInfo, true);
        task.run(runContextFactory.of(task, Map.of()));

        assertThat(ids(emittedAssets()), contains(GROUP_ID + "." + SCHEMA, "acme_catalog.salesforce.account"));
    }

    private Status statusTask(WireMockRuntimeInfo wmRuntimeInfo, boolean assetsEnabled) {
        Status.StatusBuilder<?, ?> builder = Status.builder()
            .id("status")
            .type(Status.class.getName())
            .apiKey(Property.ofValue("dummy-api-key"))
            .apiSecret(Property.ofValue("dummy-api-secret"))
            .baseUrl(Property.ofValue(wmRuntimeInfo.getHttpBaseUrl()))
            .connectorIds(Property.ofValue(List.of(CONNECTOR_ID)))
            .wait(Property.ofValue(false));

        if (assetsEnabled) {
            builder.assets(new AssetsDeclaration(true, List.of(), List.of()));
        }
        return builder.build();
    }

    private List<Asset> emittedAssets() {
        return assetManagerFactory.allEmitted().stream().map(AssetEmit::outputs).flatMap(List::stream).toList();
    }

    private static List<String> ids(List<Asset> assets) {
        return assets.stream().map(Asset::getId).toList();
    }

    private void stubConnector(WireMockRuntimeInfo wmRuntimeInfo) {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID)).willReturn(json(connectorBody(CONNECTOR_ID, SCHEMA))));
    }

    private void stubDestination(String config) {
        stubFor(get(urlEqualTo("/v2/destinations/" + GROUP_ID)).willReturn(json(destinationBody(config))));
    }

    private void stubSchemas(String schemas) {
        stubFor(get(urlEqualTo("/v2/connectors/" + CONNECTOR_ID + "/schemas")).willReturn(json(schemasBody(schemas))));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String destinationBody(String config) {
        return """
            {
              "code": "Success",
              "data": {
                "id": "%s",
                "group_id": "%s",
                "service": "snowflake",
                "region": "GCP_US_EAST4",
                "setup_status": "connected",
                "config": %s
              }
            }
            """.formatted(GROUP_ID, GROUP_ID, config);
    }

    private static String schemasBody(String schemas) {
        return """
            {
              "code": "Success",
              "data": {
                "enable_new_by_default": true,
                "schema_change_handling": "ALLOW_ALL",
                "schemas": %s
              }
            }
            """.formatted(schemas);
    }

    private static String connectorBody(String connectorId, String schema) {
        return connectorBody(connectorId, schema, "2026-08-31T10:00:00.000Z");
    }

    private static String connectorBody(String connectorId, String schema, String succeededAt) {
        return """
            {
              "code": "Success",
              "data": {
                "id": "%s",
                "name": "display name",
                "schema": null,
                "destination_schema": {"name": "%s"},
                "paused": false,
                "status": {
                  "setup_state": "connected",
                  "sync_state": "scheduled",
                  "schema_status": "ready"
                },
                "succeeded_at": "%s",
                "failed_at": null,
                "sync_frequency": 360,
                "group_id": "%s",
                "schedule_type": "auto"
              }
            }
            """.formatted(connectorId, schema, succeededAt, GROUP_ID);
    }
}
