package io.kestra.plugin.fivetran;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.hc.core5.http.Method;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import io.kestra.core.models.assets.AssetIdentifier;
import io.kestra.core.models.assets.AssetRelationRef;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.models.assets.External;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.AssetFailureBehavior;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.AbstractRetry;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.RetryUtils;
import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorResponse;
import io.kestra.plugin.fivetran.models.ConnectorSchema;
import io.kestra.plugin.fivetran.models.ConnectorSchemasResponse;
import io.kestra.plugin.fivetran.models.Destination;
import io.kestra.plugin.fivetran.models.DestinationResponse;
import io.kestra.plugin.fivetran.models.SchemaTable;
import io.kestra.plugin.fivetran.models.TableBreakdown;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFivetranConnection extends Task {
    // Core's non-strict JSON mapper, so the plugin does not maintain a second Jackson configuration that
    // can drift from the platform's. Zone adjustment is turned back off: Fivetran reports timestamps in UTC
    // and they are surfaced verbatim in task output, so rewriting them into the worker's local zone would
    // silently change every succeededAt/failedAt a flow already reads.
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson(false)
        .copy()
        .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

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
    // The connector-grain asset covers a whole destination schema, not one table. Both types have existed
    // since EE 1.2.0, so naming it honestly costs nothing.
    protected static final String DATASET_ASSET_TYPE = "io.kestra.plugin.ee.assets.Dataset";
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
        description = "Optional Kestra HTTP configuration (timeouts, proxy) applied to Fivetran calls. Its `retry` policy, when set, replaces `maxAttempts` and `initialRetryDelay` as the plugin's retry policy; it is applied once by the plugin, never stacked with the HTTP client's own retry. Its `retryOnStatusCodes`, `retryOnStatusCodesByMethod` and `retryableTransportFailureMethods` fields are ignored: only read calls are retried, on a 429, a 5xx, a read timeout or a refused connection."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    @Schema(
        title = "Maximum number of attempts on transient errors",
        description = "Total attempts including the first call, not just retries. Default: 3. Ignored when `options.retry` is set, which then defines the retry policy."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<@Min(1) Integer> maxAttempts = Property.ofValue(3);

    @Schema(
        title = "Initial delay before the first retry",
        description = "Backoff grows from this delay, capped at 30 seconds. Default: 1 second. Ignored when `options.retry` is set, which then defines the retry policy."
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
        // Retries are owned by the loop below. Core's own HTTP retry is turned off so a user-set `options.retry`
        // cannot run inside it and multiply the attempts; that policy drives the loop instead.
        var userRetry = this.options != null ? this.options.getRetry() : null;
        builder.retry(Exponential.builder().interval(Duration.ofSeconds(1)).maxInterval(Duration.ofSeconds(30)).maxAttempts(1).build());

        HttpConfiguration httpConfiguration = builder.build();

        AbstractRetry retryPolicy = userRetry != null ? userRetry : defaultRetry(runContext);

        try (HttpClient client = new HttpClient(runContext, httpConfiguration)) {
            return RetryUtils.<HttpResponse<RES>, HttpClientException> of(
                retryPolicy,
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

    private AbstractRetry defaultRetry(RunContext runContext) throws IllegalVariableEvaluationException {
        var rMaxAttempts = runContext.render(this.maxAttempts).as(Integer.class).orElseThrow();
        // @Min(1) only catches static values at flow-validation time; a dynamic expression can still
        // render to 0 or negative, which Failsafe rejects with an opaque error naming no property.
        if (rMaxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, but was " + rMaxAttempts);
        }
        var rInitialRetryDelay = runContext.render(this.initialRetryDelay).as(Duration.class).orElseThrow();
        if (rInitialRetryDelay.isNegative() || rInitialRetryDelay.isZero()) {
            throw new IllegalArgumentException("initialRetryDelay must be a positive duration, but was " + rInitialRetryDelay);
        }
        return Exponential.builder()
            .delayFactor(2.0)
            .interval(rInitialRetryDelay)
            .maxInterval(Duration.ofSeconds(30))
            .maxAttempts(rMaxAttempts)
            .build();
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

    // Exposed for the mapper's timezone regression test.
    static ObjectMapper mapper() {
        return MAPPER;
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

        var body = response.getBody();
        if (body == null || body.getData() == null || body.getData().getSchemas() == null) {
            return Map.of();
        }
        return body.getData().getSchemas();
    }

    /**
     * Reads a connector's schema config for the table breakdown and lineage. Never throws: both are
     * metadata about the run, so a failed read must not fail a task whose sync succeeded.
     *
     * @return The schemas, or null when the read failed.
     */
    protected Map<String, ConnectorSchema> readSchemas(RunContext runContext, String connectorId) {
        try {
            return fetchConnectorSchemas(runContext, connectorId);
        } catch (Exception e) {
            // Status and exception type only: the response body is never logged.
            var status = e instanceof HttpClientResponseException hcre && hcre.getResponse() != null
                ? hcre.getResponse().getStatus().getCode()
                : null;
            var reason = status != null ? "HTTP " + status : e.getClass().getSimpleName();
            var consequence = lineageEnabledQuietly(runContext)
                ? "skipping the table breakdown and table-level assets"
                : "skipping the table breakdown";
            var hint = status != null && (status == 401 || status == 403)
                ? " Check that the API key may read connector schemas."
                : "";
            runContext.logger().warn(
                "Could not read the schema config of connector '{}' ({}), {}.{}",
                connectorId,
                reason,
                consequence,
                hint
            );
            return null;
        }
    }

    protected Map<String, Map<String, ConnectorSchema>> readAllSchemas(RunContext runContext, Collection<String> connectorIds) {
        var schemasByConnector = new LinkedHashMap<String, Map<String, ConnectorSchema>>();
        for (var connectorId : connectorIds) {
            var schemas = readSchemas(runContext, connectorId);
            if (schemas != null) {
                schemasByConnector.put(connectorId, schemas);
            }
        }
        return schemasByConnector;
    }

    /**
     * Writes one Ion row per table to internal storage. Rows carry an explicit allow-list of fields, so
     * nothing else from the schema config (columns, row filters, free-text reasons) reaches storage.
     * Disabled schemas and tables are included with their flags; only {@code enabledTableCount} excludes them.
     *
     * @return The storage URI and counts, or null when the schemas could not be read.
     */
    protected TableBreakdown writeTableBreakdown(RunContext runContext, Map<String, ConnectorSchema> schemas) throws IOException {
        if (schemas == null) {
            return null;
        }

        var rows = new ArrayList<Map<String, Object>>();
        var enabledTableCount = 0;
        for (var schemaEntry : schemas.entrySet()) {
            var schema = schemaEntry.getValue();
            if (schema == null || schema.getTables() == null) {
                continue;
            }
            var schemaInDestination = schema.destinationName(schemaEntry.getKey());

            for (var tableEntry : schema.getTables().entrySet()) {
                var table = tableEntry.getValue();
                if (table == null) {
                    continue;
                }

                if (schema.isEnabled() && table.isEnabled()) {
                    enabledTableCount++;
                }

                var row = new LinkedHashMap<String, Object>();
                row.put("schema", schemaEntry.getKey());
                row.put("schemaInDestination", schemaInDestination);
                row.put("schemaEnabled", schema.isEnabled());
                row.put("table", tableEntry.getKey());
                row.put("tableInDestination", table.destinationName(tableEntry.getKey()));
                row.put("enabled", table.isEnabled());
                row.put("syncMode", table.getSyncMode());
                row.put("enabledPatchAllowed", table.getEnabledPatchSettings() != null ? table.getEnabledPatchSettings().getAllowed() : null);
                rows.add(row);
            }
        }

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var output = Files.newOutputStream(tempFile.toPath())) {
            FileSerde.writeAll(output, Flux.fromIterable(rows)).block();
        }

        return new TableBreakdown(runContext.storage().putFile(tempFile), rows.size(), enabledTableCount);
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
     * with assets off never pays for the destination call.
     */
    protected boolean assetsEnabled(RunContext runContext) throws IllegalVariableEvaluationException {
        AssetsDeclaration declaration = this.getAssets();
        return declaration != null
            && runContext.render(declaration.getEnableAuto()).as(Boolean.class).orElse(false);
    }

    // The warning path must not throw, so an expression that fails to render counts as lineage off.
    private boolean lineageEnabledQuietly(RunContext runContext) {
        try {
            return assetsEnabled(runContext);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Emits lineage for the given connectors: the connector-grain asset carrying the sync state, then one
     * bundle per synced table with the source table as its input. Shared by {@code Sync} and {@code Status},
     * because a connector on Fivetran's own schedule is never triggered through {@code Sync} and one driven
     * from Kestra is often never read through {@code Status}, so either task alone leaves half the graph empty.
     * <p>
     * Table ids are composed as {@code database.schema.name}, the same convention plugin-dbt emits, so a
     * Fivetran-loaded table and the dbt model reading it resolve to one node and the edge forms with no
     * manual mapping. {@code database} comes from the destination rather than the group ID: a group ID is
     * a Fivetran-internal identifier, not a warehouse database, so an id built on it would be table-grain
     * but still in the wrong namespace.
     * <p>
     * Core never routes an exception thrown by {@code run()} through {@code assets.assetFailureBehavior},
     * so it is applied here.
     *
     * @param runContext The run context used to render properties and emit assets.
     * @param connectors Connectors keyed by the ID they were requested with.
     * @param syncStates Optional sync state per connector ID, recorded on the connector-grain asset.
     * @param schemasByConnector Schema config already read per connector ID, so lineage and the table
     *        breakdown share one GET. A connector absent from it emits no table assets.
     * @return {@code WARNING} when an asset failed under {@code WARN}, so the task can end in that state
     * @throws IllegalStateException when an asset failed under {@code FAIL}
     */
    protected Optional<State.Type> emitAssets(
        RunContext runContext,
        Map<String, Connector> connectors,
        Map<String, String> syncStates,
        Map<String, Map<String, ConnectorSchema>> schemasByConnector) {
        if (connectors == null || connectors.isEmpty()) {
            return Optional.empty();
        }

        try {
            if (!assetsEnabled(runContext)) {
                return Optional.empty();
            }
        } catch (Exception e) {
            // `enableAuto` is a Property, so it can be an expression that fails to render. Rendering happens
            // after the sync has already succeeded, and lineage is metadata about the run rather than the run
            // itself, so a gate that cannot be read skips lineage instead of failing the task.
            runContext.logger().warn("Could not read assets.enableAuto, skipping lineage.", e);
            return Optional.empty();
        }

        // Several connectors usually share one destination, so it is resolved once per group ID. Misses are
        // cached too, so a destination that cannot be read is not re-requested per connector.
        var destinationByGroup = new HashMap<String, Destination>();
        var failures = new AssetFailures(runContext, this.getAssets());

        for (Map.Entry<String, Connector> entry : connectors.entrySet()) {
            String connectorId = entry.getKey();
            Connector connector = entry.getValue();
            if (connector == null) {
                continue;
            }

            var syncState = syncStates == null ? null : syncStates.get(connectorId);
            List<AssetEmit> emits;
            try {
                emits = new ArrayList<>();
                var connectorAsset = connectorAsset(connectorId, connector, syncState);
                // The connector asset goes first, so the parent its tables declare `partOf` already exists.
                emits.add(new AssetEmit(List.of(), List.of(connectorAsset)));
                emits.addAll(
                    tableEmits(
                        runContext,
                        connectorId,
                        connector,
                        syncState,
                        connectorAsset.getId(),
                        destinationByGroup,
                        schemasByConnector.get(connectorId)
                    )
                );
            } catch (Exception e) {
                failures.record(connectorId, "build the assets", e);
                continue;
            }

            for (int i = 0; i < emits.size(); i++) {
                var emit = emits.get(i);
                try {
                    runContext.assets().emit(emit);
                } catch (UnsupportedOperationException e) {
                    // OSS edition or tests where EE assets are not available. Only emit() reveals this, so one
                    // connector's lineage reads are already spent by the time we find out; returning here keeps
                    // it to that one rather than repeating it per connector.
                    runContext.logger().debug("Asset emission is not supported in this edition, skipping.");
                    return failures.warned() ? Optional.of(State.Type.WARNING) : Optional.empty();
                } catch (Exception e) {
                    var assetId = emit.outputs().getFirst().getId();
                    failures.record(connectorId, "emit asset '" + assetId + "'", e);
                    // Emit failures are usually a queue outage, so skip the rest instead of logging each one.
                    failures.skipped(connectorId, emits.size() - i - 1);
                    break;
                }
            }
        }

        return failures.warned() ? Optional.of(State.Type.WARNING) : Optional.empty();
    }

    /** Applies {@code assets.assetFailureBehavior} to asset build and emit failures. */
    private static final class AssetFailures {
        private final RunContext runContext;
        private final AssetsDeclaration declaration;
        private AssetFailureBehavior behavior;
        private boolean warned;

        private AssetFailures(RunContext runContext, AssetsDeclaration declaration) {
            this.runContext = runContext;
            this.declaration = declaration;
        }

        boolean warned() {
            return warned;
        }

        void record(String connectorId, String what, Exception cause) {
            var message = "Failed to " + what + " for connector '" + connectorId + "': " + cause.getMessage();
            switch (behavior()) {
                case FAIL -> throw new IllegalStateException(
                    message + ". Set assets.assetFailureBehavior to WARN or IGNORE to not fail the task.", cause
                );
                case WARN -> {
                    runContext.logger().warn(message);
                    warned = true;
                }
                case IGNORE -> runContext.logger().debug(message);
            }
        }

        // Only reached under WARN or IGNORE: record() has already thrown under FAIL.
        void skipped(String connectorId, int count) {
            if (count == 0) {
                return;
            }
            var message = "Skipped the remaining " + count + " asset(s) of connector '" + connectorId + "' after an emit failure.";
            if (behavior() == AssetFailureBehavior.WARN) {
                runContext.logger().warn(message);
            } else {
                runContext.logger().debug(message);
            }
        }

        // Rendered once per run, not per failure. Core falls back to WARN when the property cannot be rendered.
        private AssetFailureBehavior behavior() {
            if (behavior == null) {
                try {
                    behavior = declaration.getAssetFailureBehavior() == null
                        ? AssetFailureBehavior.WARN
                        : runContext.render(declaration.getAssetFailureBehavior()).as(AssetFailureBehavior.class).orElse(AssetFailureBehavior.WARN);
                } catch (Exception e) {
                    behavior = AssetFailureBehavior.WARN;
                }
            }
            return behavior;
        }
    }

    // GET /v2/connectors/{id} reports the type as connector_type_id; only the v1 payload carries service.
    private static String connectorType(Connector connector) {
        return connector.getConnectorTypeId() != null ? connector.getConnectorTypeId() : connector.getService();
    }

    /**
     * The connector-grain asset, kept alongside the table-grain ones because a connector's freshness and
     * sync state have no table-level equivalent. Its id stays {@code groupId.schema} so upgrading the
     * plugin does not rename assets that already exist.
     */
    private static Asset connectorAsset(String connectorId, Connector connector, String syncState) {
        String schema = connector.destinationSchemaName();

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("system", ASSET_SYSTEM);
        metadata.put("connectorId", connectorId);
        metadata.put("schema", schema);
        putIfNotNull(metadata, "lastSyncedAt", connector.getSucceededAt() != null ? connector.getSucceededAt().toString() : null);
        putIfNotNull(metadata, "syncState", syncState);

        return Custom.builder()
            .id(sanitizeAssetId(composeConnectorAssetId(connectorId, connector, schema)))
            .type(DATASET_ASSET_TYPE)
            .metadata(metadata)
            .build();
    }

    /**
     * One emit per table the connector writes, id {@code database.schema.name}, with the source table as
     * input and the connector asset as {@code partOf}, so the dependencies graph groups a schema's tables. Returns empty rather than throwing when the database or the schema config is
     * unavailable:
     * lineage is metadata about the run, never the run itself, so a lineage read failure must not fail a
     * sync that actually succeeded.
     */
    private List<AssetEmit> tableEmits(
        RunContext runContext,
        String connectorId,
        Connector connector,
        String syncState,
        String connectorAssetId,
        Map<String, Destination> destinationByGroup,
        Map<String, ConnectorSchema> schemas) {
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

        // A failed schema read was already warned about by readSchemas.
        if (schemas == null) {
            return List.of();
        }

        var source = SourceLocation.resolve(connectorType(connector), connector.getConfig());
        if (source.isEmpty()) {
            runContext.logger().debug(
                "Connector '{}' has no resolvable database source location, its source tables are emitted as External assets.",
                connectorId
            );
        }

        var emits = new ArrayList<AssetEmit>();
        var enabledSchemas = 0;
        for (var schemaEntry : schemas.entrySet()) {
            ConnectorSchema schema = schemaEntry.getValue();
            // A disabled schema or table is not written to the destination, so it is not an asset.
            if (schema == null || !schema.isEnabled() || schema.getTables() == null) {
                continue;
            }
            var sourceSchema = schemaEntry.getKey();
            String schemaName = schema.destinationName(sourceSchema);
            enabledSchemas++;

            for (var tableEntry : schema.getTables().entrySet()) {
                SchemaTable table = tableEntry.getValue();
                if (table == null || !table.isEnabled()) {
                    continue;
                }
                var sourceTable = tableEntry.getKey();
                String tableName = table.destinationName(sourceTable);

                var metadata = new LinkedHashMap<String, Object>();
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
                putIfNotNull(metadata, "lastSyncedAt", connector.getSucceededAt() != null ? connector.getSucceededAt().toString() : null);
                putIfNotNull(metadata, "syncState", syncState);
                putIfNotNull(metadata, "service", connectorType(connector));
                metadata.put("sourceSchema", sourceSchema);
                metadata.put("sourceTable", sourceTable);
                putIfNotNull(metadata, "syncMode", table.getSyncMode());

                Asset output = Custom.builder()
                    .id(sanitizeAssetId(joinSegments(database, schemaName, tableName)))
                    .type(TABLE_ASSET_TYPE)
                    .metadata(metadata)
                    .build()
                    .withPartOf(new AssetRelationRef(connectorAssetId, null, null));
                emits.add(new AssetEmit(List.of(sourceInput(connectorId, source, sourceSchema, sourceTable)), List.<Asset> of(output)));
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
            emits.size()
        );

        return emits;
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    // An untied source stays External because a guessed id could collide with one another plugin emits.
    private static AssetIdentifier sourceInput(
        String connectorId,
        Optional<SourceLocation.Resolved> source,
        String sourceSchema,
        String sourceTable) {
        if (source.isEmpty()) {
            return new AssetIdentifier(null, null, sanitizeAssetId(joinSegments(connectorId, sourceSchema, sourceTable)), External.ASSET_TYPE);
        }
        var database = source.get().database();
        var id = database == null ? joinSegments(sourceSchema, sourceTable) : joinSegments(database, sourceSchema, sourceTable);
        return new AssetIdentifier(null, null, sanitizeAssetId(id), TABLE_ASSET_TYPE);
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
        var id = new StringBuilder();
        for (String segment : segments) {
            if (!id.isEmpty()) {
                id.append('.');
            }
            id.append(sanitizeSegment(segment));
        }
        return id.toString();
    }

    // Allows ^[a-zA-Z0-9][a-zA-Z0-9._-]*, the set this plugin has always built ids from. Newer cores also
    // accept ':', but it is still replaced so ids already emitted stay stable across upgrades. Anything
    // outside that set becomes '_'.
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
        var suffix = "_" + String.format("%08x", sanitized.hashCode());
        return sanitized.substring(0, MAX_ASSET_ID_LENGTH - suffix.length()) + suffix;
    }
}
