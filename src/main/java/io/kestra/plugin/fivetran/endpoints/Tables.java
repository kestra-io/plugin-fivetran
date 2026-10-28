package io.kestra.plugin.fivetran.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.plugins.endpoint.PluginEndpoint;
import io.kestra.core.plugins.endpoint.PluginEndpointContext;
import io.kestra.core.plugins.endpoint.PluginEndpointResponse;
import io.kestra.core.serializers.FileSerde;

/**
 * Returns the rows of the {@code tables} breakdown file of a {@code Sync} or {@code Status} task run, one page at a
 * time. On {@code Status} the {@code connectorId} parameter picks the connector, defaulting to the first one.
 * Optional filters: {@code q} keeps the rows whose source or destination schema or table name contains it, ignoring
 * case, and {@code enabled} keeps the tables that are synced ({@code true}: enabled table in an enabled schema) or
 * not ({@code false}).
 * <p>
 * The file URI is always read from the task run's own outputs, never from a parameter, and only the breakdown's
 * allow-listed fields are returned, whatever the file holds.
 */
@Plugin
public class Tables implements PluginEndpoint {
    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 500;

    static final List<String> FIELDS = List.of(
        "schema",
        "schemaInDestination",
        "schemaEnabled",
        "table",
        "tableInDestination",
        "enabled",
        "syncMode",
        "enabledPatchAllowed"
    );

    @Override
    public String name() {
        return "tables";
    }

    @Override
    public PluginEndpointResponse handle(PluginEndpointContext context) {
        var size = Math.min(positiveInt(context.param("size"), DEFAULT_SIZE), MAX_SIZE);
        var page = positiveInt(context.param("page"), 1);
        var needle = Optional.ofNullable(context.param("q")).map(q -> q.strip().toLowerCase(Locale.ROOT)).orElse("");
        var enabled = Optional.ofNullable(context.param("enabled")).map(String::strip).filter(value -> !value.isEmpty()).map(Boolean::parseBoolean).orElse(null);

        var skip = (long) (page - 1) * size;
        var results = new ArrayList<Map<String, Object>>();
        long[] counts = new long[2]; // [rows in the file, rows matching the filters]

        open(context).ifPresent(file ->
        {
            try (var input = file.input()) {
                if (needle.isEmpty() && enabled == null && file.tableCount() != null) {
                    // No filter: the page is a plain slice and the total is already known, so skip the rows before
                    // it without deserializing them and stop once the page is filled.
                    FileSerde.read(input, skip, size, row ->
                    {
                        if (row instanceof Map<?, ?> map) {
                            results.add(allowListed(map));
                        }
                    });
                    counts[0] = file.tableCount();
                    counts[1] = file.tableCount();
                    return;
                }

                FileSerde.read(input, row ->
                {
                    if (!(row instanceof Map<?, ?> map)) {
                        return;
                    }
                    counts[0]++;
                    if (!matches(map, needle, enabled)) {
                        return;
                    }
                    if (counts[1] >= skip && results.size() < size) {
                        results.add(allowListed(map));
                    }
                    counts[1]++;
                });
            } catch (IOException | RuntimeException e) {
                // A file that cannot be read is answered like a missing one: the view shows no rows.
                results.clear();
                counts[0] = 0;
                counts[1] = 0;
            }
        });

        return PluginEndpointResponse.of(new Page(page, size, counts[1], counts[0], List.copyOf(results)));
    }

    /**
     * The breakdown file to read and the row count its task run reported, if any.
     */
    record BreakdownFile(InputStream input, Long tableCount) {
    }

    /**
     * Opens the breakdown file of the requested connector, empty when the task run is not a Fivetran one, the
     * output is missing, or the file does not exist. The scoped storage rejects any URI outside the execution,
     * which is treated the same as a missing file.
     */
    static Optional<BreakdownFile> open(PluginEndpointContext context) {
        var outputs = context.outputs().get();
        if (outputs == null) {
            return Optional.empty();
        }

        var located = locate(outputs, context.param("connectorId"));
        if (located == null) {
            return Optional.empty();
        }

        try {
            var uri = URI.create(located.uri());
            if (!context.storage().exists(uri)) {
                return Optional.empty();
            }
            return Optional.of(new BreakdownFile(context.storage().getFile(uri), located.tableCount()));
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }

    private record Located(String uri, Long tableCount) {
    }

    /**
     * The {@code tables} and {@code tableCount} outputs of a Fivetran task run: on {@code Sync} the top-level one (its outputs carry
     * {@code connectorId} and the {@code freshness} summary), on {@code Status} the one of the requested connector
     * in {@code connectors} (each entry carries its {@code id}). Other task runs of the execution return null, so
     * the endpoint cannot be used to read their files.
     */
    private static Located locate(Map<String, Object> outputs, String connectorId) {
        if (outputs.get("connectorId") instanceof String && outputs.get("freshness") instanceof Map<?, ?>) {
            return located(outputs);
        }

        if (outputs.get("connectors") instanceof Map<?, ?> connectors && !connectors.isEmpty()) {
            var state = connectorId == null || connectorId.isBlank()
                ? connectors.values().iterator().next()
                : connectors.get(connectorId.strip());
            if (state instanceof Map<?, ?> connector && connector.get("id") instanceof String) {
                return located(connector);
            }
        }

        return null;
    }

    private static Located located(Map<?, ?> source) {
        var uri = text(source.get("tables"));
        if (uri == null) {
            return null;
        }
        var count = source.get("tableCount") instanceof Number number && number.longValue() >= 0 ? number.longValue() : null;
        return new Located(uri, count);
    }

    private static boolean matches(Map<?, ?> row, String needle, Boolean enabled) {
        if (enabled != null && synced(row) != enabled) {
            return false;
        }
        if (needle.isEmpty()) {
            return true;
        }
        for (var field : List.of("schema", "schemaInDestination", "table", "tableInDestination")) {
            if (row.get(field) instanceof String value && value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // Same rule as enabledTableCount: a table is synced when it is enabled in an enabled schema.
    private static boolean synced(Map<?, ?> row) {
        return Boolean.TRUE.equals(row.get("schemaEnabled")) && Boolean.TRUE.equals(row.get("enabled"));
    }

    private static Map<String, Object> allowListed(Map<?, ?> row) {
        var result = new LinkedHashMap<String, Object>();
        for (var field : FIELDS) {
            result.put(field, row.get(field));
        }
        return result;
    }

    // Outputs read back from the repository hold the URI as text, outputs still in memory as a URI.
    private static String text(Object value) {
        var string = value instanceof URI uri ? uri.toString() : value instanceof String text ? text : null;
        return string == null || string.isBlank() ? null : string;
    }

    private static int positiveInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            var parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * One page of rows. {@code page} is one-based, matching the Kestra API. {@code total} counts the rows matching
     * the filters, {@code tableCount} every row of the file.
     */
    record Page(int page, int size, long total, long tableCount, List<Map<String, Object>> results) {
    }
}
