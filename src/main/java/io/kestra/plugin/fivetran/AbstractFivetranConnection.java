package io.kestra.plugin.fivetran;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.hc.core5.http.Method;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientRequestException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.RetryUtils;
import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorResponse;
import io.kestra.plugin.fivetran.models.ConnectorSchema;
import io.kestra.plugin.fivetran.models.ConnectorSchemasResponse;
import io.kestra.plugin.fivetran.models.Destination;
import io.kestra.plugin.fivetran.models.DestinationResponse;
import io.kestra.plugin.fivetran.models.SchemaTable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFivetranConnection extends Task {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR_MIN = 500;
    private static final int SERVER_ERROR_MAX = 599;
    // Upper bound on how far to walk the exception cause chain, so a cyclic chain cannot loop forever.
    private static final int MAX_CAUSE_CHAIN_DEPTH = 16;

    private static final String DEFAULT_BASE_URL = "https://api.fivetran.com";

    // Compiled once: sanitizeSegment runs three times per emitted table, so a connector with hundreds of
    // tables would otherwise recompile these on every segment of every id.
    private static final Pattern DISALLOWED_ID_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern LEADING_NON_ALPHANUMERIC = Pattern.compile("^[^a-zA-Z0-9]+");

    protected static final String TABLE_ASSET_TYPE = "io.kestra.plugin.ee.assets.Table";
    private static final String ASSET_SYSTEM = "fivetran";
    // Asset ids are constrained to 150 characters by the core Asset contract.
    private static final int MAX_ASSET_ID_LENGTH = 150;

    @Schema(
        title = "Fivetran API key",
        description = "Required; paired with `apiSecret` for HTTP Basic authentication."
    )
    @NotNull
    @ToString.Exclude
    @PluginProperty(secret = true, group = "connection")
    Property<String> apiKey;

    @Schema(
        title = "Fivetran API secret",
        description = "Required secret token used with `apiKey` for Basic authentication."
    )
    @NotNull
    @ToString.Exclude
    @PluginProperty(secret = true, group = "connection")
    Property<String> apiSecret;

    @Schema(
        title = "Fivetran API base URL",
        description = "Base endpoint for all requests. Defaults to `https://api.fivetran.com`; override for regional or private deployments."
    )
    @NotNull
    @Builder.Default
    Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "HTTP client options",
        description = "Optional Kestra HTTP configuration (timeouts, proxy) applied to Fivetran calls. Retries are configured separately via `maxAttempts` and `initialRetryDelay`."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    @Schema(
        title = "Maximum number of attempts on transient errors",
        description = "Total attempts including the first call, not just retries. Default: 3."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<@Min(1) Integer> maxAttempts = Property.ofValue(3);

    @Schema(
        title = "Initial delay before the first retry",
        description = "Backoff grows from this delay, capped at 30 seconds. Default: 1 second."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Duration> initialRetryDelay = Property.ofValue(Duration.ofSeconds(1));

    /**
     * @param runContext The run context used to render properties and build the HTTP client.
     * @param requestBuilder The prepared HTTP request builder.
     * @param responseType The expected response type.
     * @param <RES> The response class.
     * @return HttpResponse of type RES.
     */
    protected <RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws HttpClientException, IllegalVariableEvaluationException {

        var request = requestBuilder
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json;version=2")
            .build();

        HttpConfiguration.HttpConfigurationBuilder builder = this.options != null ? this.options.toBuilder() : HttpConfiguration.builder();

        builder.auth(BasicAuthConfiguration.builder().username(apiKey).password(apiSecret).build());

        HttpConfiguration httpConfiguration = builder.build();

        var rMaxAttempts = runContext.render(this.maxAttempts).as(Integer.class).orElseThrow();
        // @Min(1) only catches static values at flow-validation time; a dynamic expression can still
        // render to 0 or negative, which Failsafe rejects with an opaque error naming no property.
        if (rMaxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, but was " + rMaxAttempts);
        }
        Duration rInitialRetryDelay = runContext.render(this.initialRetryDelay).as(Duration.class).orElseThrow();
        if (rInitialRetryDelay.isNegative() || rInitialRetryDelay.isZero()) {
            throw new IllegalArgumentException("initialRetryDelay must be a positive duration, but was " + rInitialRetryDelay);
        }

        try (HttpClient client = new HttpClient(runContext, httpConfiguration)) {
            return RetryUtils.<HttpResponse<RES>, HttpClientException> of(
                Exponential.builder()
                    .delayFactor(2.0)
                    .interval(rInitialRetryDelay)
                    .maxInterval(Duration.ofSeconds(30))
                    .maxAttempts(rMaxAttempts)
                    .build(),
                // On exhausted retries, surface the original error instead of RetryUtils' RetryFailed,
                // so the task keeps the real HTTP status/cause and request()'s declared type stays honest.
                failed -> failed.getCause() instanceof HttpClientException hce
                    ? hce
                    : new HttpClientRequestException(failed.getMessage(), request, failed.getCause())
            ).run(
                (res, throwable) -> isRetriableTransientError(throwable, request.getMethod()),
                () ->
                {
                    HttpResponse<String> response = client.request(request, String.class);
                    RES parsedResponse = MAPPER.readValue(response.getBody(), responseType);
                    return HttpResponse.<RES> builder()
                        .request(request)
                        .body(parsedResponse)
                        .headers(response.getHeaders())
                        .status(response.getStatus())
                        .build();
                }
            );
        } catch (IOException e) {
            throw new RuntimeException("Error executing HTTP request", e);
        }
    }

    /**
     * Reads a connector's current status via GET, without triggering a sync.
     *
     * @param runContext The run context used to render properties and build the HTTP client.
     * @param connectorId The already-rendered Fivetran connector ID.
     * @return The connector as returned by the Fivetran API.
     */
    protected Connector fetchConnector(RunContext runContext, String connectorId)
        throws IllegalVariableEvaluationException, HttpClientException {
        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(
                URI.create(
                    rBaseUrl(runContext) + "/v2/connectors/" + encodePathSegment(connectorId)
                )
            )
            .method("GET");

        HttpResponse<ConnectorResponse> response = this.request(runContext, requestBuilder, ConnectorResponse.class);

        return response.getBody().getData();
    }

    /**
     * URL-encodes a connector or destination ID before it is interpolated into a Fivetran API path, so
     * reserved URL characters cannot alter the request path. Shared by the GET status read, the POST sync
     * trigger, and the two lineage reads. {@link URLEncoder} uses form-encoding, which emits {@code +} for
     * a space, so it is corrected to the path-segment {@code %20}.
     */
    // baseUrl carries a @Builder.Default, so it renders with that default rather than throwing.
    protected String rBaseUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.getBaseUrl()).as(String.class).orElse(DEFAULT_BASE_URL);
    }

    protected static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Whether an error calling the Fivetran API is transient and worth retrying. Only read-only
     * methods (GET/HEAD) retry, since the poll loop drives them repeatedly. Write methods, i.e. the
     * non-idempotent sync trigger, never retry, so a retry cannot start the sync twice.
     */
    protected static boolean isRetriableTransientError(Throwable throwable, String method) {
        if (throwable == null || !isReadOnlyMethod(method)) {
            return false;
        }

        if (throwable instanceof HttpClientResponseException ex) {
            int code = ex.getResponse().getStatus().getCode();
            return code == TOO_MANY_REQUESTS || (code >= SERVER_ERROR_MIN && code <= SERVER_ERROR_MAX);
        }

        // Core wraps transport failures inconsistently (HttpClientRequestException for a SocketException
        // or TLS failure, RuntimeException for other IOExceptions), so match the cause chain rather than
        // the wrapper type. Only a read timeout or a refused connection is worth retrying. A bad host,
        // TLS failure or parse error never resolves by waiting, so it fails fast with its real cause.
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_CHAIN_DEPTH; cause = cause.getCause(), depth++) {
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectException) {
                return true;
            }
        }

        return false;
    }

    // GET/HEAD only. Named for retry-safety, not RFC idempotency: PUT/DELETE are idempotent but are
    // not safe to retry blindly here, so they are treated as write methods.
    private static boolean isReadOnlyMethod(String method) {
        return Method.GET.isSame(method) || Method.HEAD.isSame(method);
    }

    /**
     * Reads a connector's schema config, i.e. which schemas and tables it writes into the destination.
     *
     * @param runContext The run context used to render properties and build the HTTP client.
     * @param connectorId The already-rendered Fivetran connector ID.
     * @return Schemas keyed by their source-side name, empty when Fivetran reports none.
     */
    protected Map<String, ConnectorSchema> fetchConnectorSchemas(RunContext runContext, String connectorId)
        throws IllegalVariableEvaluationException, HttpClientException {
        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(
                URI.create(
                    rBaseUrl(runContext) + "/v2/connectors/" + encodePathSegment(connectorId) + "/schemas"
                )
            )
            .method("GET");

        HttpResponse<ConnectorSchemasResponse> response = this.request(runContext, requestBuilder, ConnectorSchemasResponse.class);

        ConnectorSchemasResponse body = response.getBody();
        if (body == null || body.getData() == null || body.getData().getSchemas() == null) {
            return Map.of();
        }
        return body.getData().getSchemas();
    }

    /**
     * Reads a destination, whose config carries the warehouse database name behind a Fivetran group ID.
     *
     * @param runContext The run context used to render properties and build the HTTP client.
     * @param groupId The Fivetran group (destination) ID taken from the connector.
     * @return The destination as returned by the Fivetran API, null when the body carries no data.
     */
    protected Destination fetchDestination(RunContext runContext, String groupId)
        throws IllegalVariableEvaluationException, HttpClientException {
        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(
                URI.create(
                    rBaseUrl(runContext) + "/v2/destinations/" + encodePathSegment(groupId)
                )
            )
            .method("GET");

        HttpResponse<DestinationResponse> response = this.request(runContext, requestBuilder, DestinationResponse.class);

        return response.getBody() != null ? response.getBody().getData() : null;
    }

    /**
     * Whether automatic asset emission is enabled on this task. Checked before any lineage read so a task
     * with assets off never pays for the extra destination and schema calls.
     */
    protected boolean assetsEnabled(RunContext runContext) throws IllegalVariableEvaluationException {
        AssetsDeclaration declaration = this.getAssets();
        return declaration != null
            && runContext.render(declaration.getEnableAuto()).as(Boolean.class).orElse(false);
    }

    /**
     * Emits lineage for the given connectors: one table-grain asset per synced table, plus the
     * connector-grain asset carrying the sync state. Shared by {@code Sync} and {@code Status}, because a
     * connector on Fivetran's own schedule is never triggered through {@code Sync} and one driven from
     * Kestra is often never read through {@code Status}, so either task alone leaves half the graph empty.
     * <p>
     * Table ids are composed as {@code database.schema.name}, the same convention plugin-dbt emits, so a
     * Fivetran-loaded table and the dbt model reading it resolve to one node and the edge forms with no
     * manual mapping. {@code database} comes from the destination rather than the group ID: a group ID is
     * a Fivetran-internal identifier, not a warehouse database, so an id built on it would be table-grain
     * but still in the wrong namespace.
     *
     * @param runContext The run context used to render properties and emit assets.
     * @param connectors Connectors keyed by the ID they were requested with.
     * @param syncStates Optional sync state per connector ID, recorded on the connector-grain asset.
     */
    protected void emitAssets(RunContext runContext, Map<String, Connector> connectors, Map<String, String> syncStates) {
        if (connectors == null || connectors.isEmpty()) {
            return;
        }

        try {
            if (!assetsEnabled(runContext)) {
                return;
            }
        } catch (Exception e) {
            // `enableAuto` is a Property, so it can be an expression that fails to render. Rendering happens
            // after the sync has already succeeded, and lineage is metadata about the run rather than the run
            // itself, so a gate that cannot be read skips lineage instead of failing the task.
            runContext.logger().warn("Could not read assets.enableAuto, skipping lineage.", e);
            return;
        }

        // Several connectors usually share one destination, so it is resolved once per group ID. Misses are
        // cached too, so a destination that cannot be read is not re-requested per connector.
        Map<String, Destination> destinationByGroup = new HashMap<>();

        for (Map.Entry<String, Connector> entry : connectors.entrySet()) {
            String connectorId = entry.getKey();
            Connector connector = entry.getValue();
            if (connector == null) {
                continue;
            }

            List<Asset> assets = new ArrayList<>();
            assets.add(connectorAsset(connectorId, connector, syncStates == null ? null : syncStates.get(connectorId)));
            assets.addAll(tableAssets(runContext, connectorId, connector, destinationByGroup));

            try {
                runContext.assets().emit(new AssetEmit(List.of(), assets));
            } catch (UnsupportedOperationException e) {
                // OSS edition or tests where EE assets are not available. Only emit() reveals this, so one
                // connector's lineage reads are already spent by the time we find out; returning here keeps
                // it to that one rather than repeating it per connector.
                runContext.logger().debug("Asset emission is not supported in this edition, skipping.");
                return;
            } catch (QueueException | IllegalVariableEvaluationException e) {
                runContext.logger().warn("Unable to emit fivetran asset for connector '{}'", connectorId, e);
            }
        }
    }

    /**
     * The connector-grain asset, kept alongside the table-grain ones because a connector's freshness and
     * sync state have no table-level equivalent. Its id stays {@code groupId.schema} so upgrading the
     * plugin does not rename assets that already exist.
     */
    private static Asset connectorAsset(String connectorId, Connector connector, String syncState) {
        String schema = connector.destinationSchemaName();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("system", ASSET_SYSTEM);
        metadata.put("connectorId", connectorId);
        metadata.put("schema", schema);
        if (syncState != null) {
            metadata.put("syncState", syncState);
        }

        return Custom.builder()
            .id(sanitizeAssetId(composeConnectorAssetId(connectorId, connector, schema)))
            .type(TABLE_ASSET_TYPE)
            .metadata(metadata)
            .build();
    }

    /**
     * One asset per table the connector writes, id {@code database.schema.name}. Returns empty rather than
     * throwing when the database or the schema config cannot be read: lineage is metadata about the run,
     * never the run itself, so a lineage read failure must not fail a sync that actually succeeded.
     */
    private List<Asset> tableAssets(RunContext runContext, String connectorId, Connector connector, Map<String, Destination> destinationByGroup) {
        Destination destination = resolveDestination(runContext, connector, destinationByGroup);
        String database = destination != null ? destination.databaseName() : null;
        if (database == null) {
            // Warn, not debug: this is the one line that explains why lineage produced nothing but a
            // connector node, and an operator cannot diagnose that from a quiet log. Fivetran-managed
            // destinations are the known case: Managed BigQuery reports only `data_set_location`, `bucket`
            // and `support_json_type`, so no warehouse database name exists to read.
            runContext.logger().warn(
                "Destination '{}' reports no database name, so connector '{}' emits no table-level assets.",
                connector.getGroupId(),
                connectorId
            );
            return List.of();
        }

        Map<String, ConnectorSchema> schemas;
        try {
            schemas = fetchConnectorSchemas(runContext, connectorId);
        } catch (Exception e) {
            runContext.logger().warn(
                "Could not read the schema config of connector '{}', emitting the connector asset only.",
                connectorId,
                e
            );
            return List.of();
        }

        List<Asset> assets = new ArrayList<>();
        int enabledSchemas = 0;
        for (Map.Entry<String, ConnectorSchema> schemaEntry : schemas.entrySet()) {
            ConnectorSchema schema = schemaEntry.getValue();
            // A disabled schema or table is not written to the destination, so it is not an asset.
            if (schema == null || !schema.isEnabled() || schema.getTables() == null) {
                continue;
            }
            String schemaName = schema.destinationName(schemaEntry.getKey());
            enabledSchemas++;

            for (Map.Entry<String, SchemaTable> tableEntry : schema.getTables().entrySet()) {
                SchemaTable table = tableEntry.getValue();
                if (table == null || !table.isEnabled()) {
                    continue;
                }
                String tableName = table.destinationName(tableEntry.getKey());

                Map<String, Object> metadata = new LinkedHashMap<>();
                // plugin-dbt sets `system` to the warehouse adapter, not to itself. Both plugins write this
                // same asset id, so naming the warehouse agrees with dbt for snowflake, databricks and
                // redshift, where the two spellings are identical. They still differ on BigQuery, which
                // Fivetran calls `big_query` and dbt calls `bigquery`, so the field can flip there. It is
                // descriptive only, the id is what forms the edge. The Fivetran origin stays in `connectorId`.
                metadata.put("system", destination.getService() != null ? destination.getService() : ASSET_SYSTEM);
                metadata.put("database", database);
                metadata.put("schema", schemaName);
                metadata.put("name", tableName);
                metadata.put("connectorId", connectorId);

                assets.add(
                    Custom.builder()
                        .id(sanitizeAssetId(joinSegments(database, schemaName, tableName)))
                        .type(TABLE_ASSET_TYPE)
                        .metadata(metadata)
                        .build()
                );
            }
        }

        // One line stating what lineage resolved, so an operator can tell a working run from a degraded one
        // without turning on debug logging or reading the plugin source.
        runContext.logger().info(
            "Connector '{}': resolved database '{}', {} enabled schema(s) of {} reported, emitting {} table asset(s).",
            connectorId,
            database,
            enabledSchemas,
            schemas.size(),
            assets.size()
        );

        return assets;
    }

    /**
     * The destination behind a connector's group ID, or null when it cannot be read. Cached per group ID by
     * the caller, misses included, so N connectors on one destination cost one lookup.
     */
    private Destination resolveDestination(RunContext runContext, Connector connector, Map<String, Destination> destinationByGroup) {
        String groupId = connector.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        if (destinationByGroup.containsKey(groupId)) {
            return destinationByGroup.get(groupId);
        }

        Destination destination = null;
        try {
            destination = fetchDestination(runContext, groupId);
        } catch (Exception e) {
            // Deliberately not logging the exception message: this is the one Fivetran response that carries
            // raw credentials (Snowflake `password`/`private_key`, BigQuery `secret_key`), and a parser error
            // can quote the source it choked on.
            runContext.logger().warn("Could not read destination '{}' ({})", groupId, e.getClass().getSimpleName());
        }

        destinationByGroup.put(groupId, destination);
        return destination;
    }

    // groupId scopes the schema to the connector's destination so two connectors landing in the same schema
    // (e.g. shared across groups) still get distinct asset ids; connectorId is the fallback when groupId is absent.
    private static String composeConnectorAssetId(String connectorId, Connector connector, String schema) {
        String prefix = connector.getGroupId() != null ? connector.getGroupId() : connectorId;
        return schema != null
            ? joinSegments(prefix, schema)
            : joinSegments(connectorId);
    }

    // Sanitize each segment before joining so "." only ever appears as the delimiter: a raw segment can
    // contain "." (e.g. schema "google_sheets.destination"), which would otherwise make the id ambiguous.
    private static String joinSegments(String... segments) {
        StringBuilder id = new StringBuilder();
        for (String segment : segments) {
            if (!id.isEmpty()) {
                id.append('.');
            }
            id.append(sanitizeSegment(segment));
        }
        return id.toString();
    }

    // Kept in step with the narrowest Asset.id contract this plugin compiles against: core 1.3.13 allows
    // ^[a-zA-Z0-9][a-zA-Z0-9._-]* and rejects ':', which a later core added. Anything outside that set
    // becomes '_' rather than emit an id the framework declares invalid.
    static String sanitizeSegment(String segment) {
        return DISALLOWED_ID_CHARS.matcher(segment).replaceAll("_");
    }

    // Enforce the rest of Asset's id contract (^[a-zA-Z0-9]..., size 1-150) that per-segment sanitization
    // leaves: trim leading non-alphanumerics, fall back to a placeholder if that empties the id (e.g. "___"),
    // and cap the length.
    static String sanitizeAssetId(String rawId) {
        String sanitized = LEADING_NON_ALPHANUMERIC.matcher(rawId).replaceFirst("");
        if (sanitized.isEmpty()) {
            sanitized = "connector";
        }
        if (sanitized.length() <= MAX_ASSET_ID_LENGTH) {
            return sanitized;
        }

        // The table name is the last segment, so plain truncation cuts the only part that differs between the
        // tables of one schema: under a `database.schema.` prefix at the length cap every table would land on
        // the same id and all but the last would silently vanish from the graph. A digest of the full id keeps
        // them distinct. Not security-sensitive, it only has to separate ids sharing a 150-character prefix.
        String suffix = "_" + String.format("%08x", sanitized.hashCode());
        return sanitized.substring(0, MAX_ASSET_ID_LENGTH - suffix.length()) + suffix;
    }
}
