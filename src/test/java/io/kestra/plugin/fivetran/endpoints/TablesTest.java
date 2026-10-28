package io.kestra.plugin.fivetran.endpoints;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.serializers.FileSerde;

import reactor.core.publisher.Flux;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class TablesTest {
    private static final String SYNC_URI = "kestra:///ns/flow/executions/exec/tasks/sync/taskrun/tables.ion";
    private static final String OTHER_URI = "kestra:///ns/flow/executions/exec/tasks/status/taskrun/other.ion";

    private static byte[] ion(List<Map<String, Object>> rows) throws IOException {
        var output = new ByteArrayOutputStream();
        FileSerde.writeAll(output, Flux.fromIterable(rows)).block();
        return output.toByteArray();
    }

    private static Map<String, Object> row(String schema, boolean schemaEnabled, String table, boolean enabled) {
        var row = new LinkedHashMap<String, Object>();
        row.put("schema", schema);
        row.put("schemaInDestination", schema + "_dest");
        row.put("schemaEnabled", schemaEnabled);
        row.put("table", table);
        row.put("tableInDestination", table);
        row.put("enabled", enabled);
        row.put("syncMode", "SOFT_DELETE");
        row.put("enabledPatchAllowed", true);
        return row;
    }

    private static byte[] breakdown() throws IOException {
        return ion(
            List.of(
                row("salesforce", true, "Account", true),
                row("salesforce", true, "Opportunity", false),
                row("hubspot", false, "contact", true)
            )
        );
    }

    private static StubEndpointContext syncContext() throws IOException {
        return new StubEndpointContext()
            .output("connectorId", "connector_a")
            .output("freshness", Map.of("fresh", true))
            .output("tables", SYNC_URI)
            .file(SYNC_URI, breakdown());
    }

    private static List<String> tables(JsonNode json) {
        var names = new ArrayList<String>();
        json.get("results").forEach(row -> names.add(row.get("table").asText()));
        return names;
    }

    @Test
    void returns_every_row_of_a_sync_breakdown_with_the_allow_listed_fields() throws Exception {
        var json = StubEndpointContext.json(new Tables().handle(syncContext()));

        assertThat(json.get("total").asLong(), is(3L));
        assertThat(json.get("tableCount").asLong(), is(3L));
        assertThat(tables(json), contains("Account", "Opportunity", "contact"));

        var account = json.get("results").get(0);
        var fields = new ArrayList<String>();
        account.fieldNames().forEachRemaining(fields::add);
        assertThat(fields, is(Tables.FIELDS));
        assertThat(account.get("schemaInDestination").asText(), is("salesforce_dest"));
    }

    @Test
    void never_returns_fields_outside_the_allow_list() throws Exception {
        var leaky = row("salesforce", true, "Account", true);
        leaky.put("columns", Map.of("ssn", Map.of("enabled", true)));
        var context = syncContext().file(SYNC_URI, ion(List.of(leaky)));

        var response = new Tables().handle(context);

        assertThat(new String(response.body()), not(containsString("ssn")));
        assertThat(new String(response.body()), not(containsString("columns")));
    }

    @Test
    void filters_by_schema_or_table_name_ignoring_case() throws Exception {
        assertThat(tables(StubEndpointContext.json(new Tables().handle(syncContext().param("q", "OPPORT")))), contains("Opportunity"));
        // The destination name matches too.
        assertThat(tables(StubEndpointContext.json(new Tables().handle(syncContext().param("q", "hubspot_DEST")))), contains("contact"));
    }

    @Test
    void enabled_filter_follows_the_enabled_table_count_rule() throws Exception {
        var synced = StubEndpointContext.json(new Tables().handle(syncContext().param("enabled", "true")));
        // An enabled table in a disabled schema is not synced.
        assertThat(tables(synced), contains("Account"));
        assertThat(synced.get("total").asLong(), is(1L));
        assertThat(synced.get("tableCount").asLong(), is(3L));

        assertThat(tables(StubEndpointContext.json(new Tables().handle(syncContext().param("enabled", "false")))), contains("Opportunity", "contact"));
    }

    @Test
    void pages_through_the_matching_rows() throws Exception {
        var second = StubEndpointContext.json(new Tables().handle(syncContext().param("size", "2").param("page", "2")));

        assertThat(tables(second), contains("contact"));
        assertThat(second.get("total").asLong(), is(3L));
        assertThat(second.get("page").asInt(), is(2));
    }

    @Test
    void caps_the_page_size_and_ignores_invalid_paging() throws Exception {
        var json = StubEndpointContext.json(new Tables().handle(syncContext().param("size", "100000").param("page", "-3")));

        assertThat(json.get("size").asInt(), is(Tables.MAX_SIZE));
        assertThat(json.get("page").asInt(), is(1));
    }

    @Test
    void reads_the_requested_connector_of_a_status_task_run() throws Exception {
        var connectors = new LinkedHashMap<String, Object>();
        connectors.put("connector_a", Map.of("id", "connector_a", "tables", SYNC_URI));
        connectors.put("connector_b", Map.of("id", "connector_b", "tables", OTHER_URI));
        var context = new StubEndpointContext()
            .output("connectors", connectors)
            .file(SYNC_URI, breakdown())
            .file(OTHER_URI, ion(List.of(row("netsuite", true, "invoice", true))));

        assertThat(tables(StubEndpointContext.json(new Tables().handle(context))), contains("Account", "Opportunity", "contact"));
        assertThat(tables(StubEndpointContext.json(new Tables().handle(context.param("connectorId", "connector_b")))), contains("invoice"));
        assertThat(StubEndpointContext.json(new Tables().handle(context.param("connectorId", "unknown"))).get("total").asLong(), is(0L));
    }

    @Test
    void accepts_the_tables_output_as_a_uri() throws Exception {
        var context = syncContext().output("tables", URI.create(SYNC_URI));

        assertThat(StubEndpointContext.json(new Tables().handle(context)).get("total").asLong(), is(3L));
    }

    @Test
    void ignores_task_runs_that_are_not_fivetran_ones() throws Exception {
        // Another task of the execution with a `tables` output: its file must not be readable through this endpoint.
        var context = new StubEndpointContext().output("tables", SYNC_URI).file(SYNC_URI, breakdown());

        assertThat(StubEndpointContext.json(new Tables().handle(context)).get("total").asLong(), is(0L));
    }

    @Test
    void missing_output_file_or_unreadable_file_returns_no_rows() throws Exception {
        var noFile = new StubEndpointContext().output("connectorId", "connector_a").output("freshness", Map.of()).output("tables", SYNC_URI);
        // A failed schema read leaves `tables` null.
        var noOutput = new StubEndpointContext().output("connectorId", "connector_a").output("freshness", Map.of());
        var notIon = syncContext().file(SYNC_URI, "not ion {".getBytes());

        for (var context : List.of(new StubEndpointContext(), noOutput, noFile, notIon)) {
            var json = StubEndpointContext.json(new Tables().handle(context));
            assertThat(json.get("total").asLong(), is(0L));
            assertThat(json.get("results").size(), is(0));
        }
    }

    @Test
    void with_a_known_count_and_no_filter_pages_without_reading_the_rest_of_the_file() throws Exception {
        var rows = breakdown();
        var trailing = "not ion {".getBytes();
        var file = new byte[rows.length + trailing.length];
        System.arraycopy(rows, 0, file, 0, rows.length);
        System.arraycopy(trailing, 0, file, rows.length, trailing.length);
        var context = syncContext().output("tableCount", 3).file(SYNC_URI, file).param("size", "1").param("page", "2");

        var json = StubEndpointContext.json(new Tables().handle(context));

        // Only the page is deserialized: the unreadable tail, past it, is never reached.
        assertThat(tables(json), contains("Opportunity"));
        assertThat(json.get("total").asLong(), is(3L));
        assertThat(json.get("tableCount").asLong(), is(3L));
    }

    @Test
    void with_a_known_count_a_filter_still_counts_the_matching_rows() throws Exception {
        var json = StubEndpointContext.json(new Tables().handle(syncContext().output("tableCount", 3).param("enabled", "false")));

        assertThat(tables(json), contains("Opportunity", "contact"));
        assertThat(json.get("total").asLong(), is(2L));
        assertThat(json.get("tableCount").asLong(), is(3L));
    }
}
