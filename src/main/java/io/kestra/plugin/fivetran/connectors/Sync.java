package io.kestra.plugin.fivetran.connectors;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.fivetran.AbstractFivetranConnection;
import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorStatusResponse;
import io.kestra.plugin.fivetran.models.SyncResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger and optionally watch connector sync",
    description = "Starts a Fivetran connector sync through the Fivetran API. Can force-cancel and restart an in-progress sync, or re-attach to one already in progress with `reattach`. Waits for completion by default (up to 60 minutes) and fails if the connector reports a failure. With `assets.enableAuto` set, emits one asset per table the connector writes, with the id `database.schema.name` so it joins the dbt model reading that table in the lineage graph."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: fivetran_sync
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.fivetran.connectors.Sync
                    apiKey: "{{ secret('FIVETRAN_API_KEY') }}"
                    apiSecret: "{{ secret('FIVETRAN_API_SECRET') }}"
                    connectorId: "connector_id"
                """
        ),
        @Example(
            full = true,
            title = "Sync a connector and emit one lineage asset per table it writes.",
            code = """
                id: fivetran_sync_with_assets
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.fivetran.connectors.Sync
                    apiKey: "{{ secret('FIVETRAN_API_KEY') }}"
                    apiSecret: "{{ secret('FIVETRAN_API_SECRET') }}"
                    connectorId: "connector_id"
                    assets:
                      enableAuto: true
                """
        ),
        @Example(
            full = true,
            title = "Re-attach to a sync already running on the connector instead of triggering a duplicate.",
            code = """
                id: fivetran_sync_reattach
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.fivetran.connectors.Sync
                    apiKey: "{{ secret('FIVETRAN_API_KEY') }}"
                    apiSecret: "{{ secret('FIVETRAN_API_SECRET') }}"
                    connectorId: "connector_id"
                    reattach: true
                    reattachMaxAge: PT30M
                """
        )
    },
    metrics = {
        @Metric(name = "reattached", type = Counter.TYPE, description = "Whether the task re-attached to an already in-progress sync (1) instead of triggering a new one (0).")
    }
)
public class Sync extends AbstractFivetranConnection implements RunnableTask<Sync.Output> {
    @Schema(
        title = "Connector ID",
        description = "Identifier of the Fivetran connector to sync."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> connectorId;

    @Schema(
        title = "Force restart if already syncing",
        description = "When true, cancels a running sync before starting a new one. Default is false to skip if a sync is already running."
    )
    @Builder.Default
    Property<Boolean> force = Property.ofValue(false);

    @Schema(
        title = "Wait for sync completion",
        description = "When true (default), poll the connector until the sync finishes to capture status and logs. Set to false to return once the sync has been triggered, without waiting for it. `succeededAt` is then null, since the sync is still running on Fivetran. With `assets.enableAuto` set, lineage is still emitted in that case, which costs two extra reads before the task returns."
    )
    @Builder.Default
    Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum wait duration",
        description = "Upper bound for waiting when `wait` is true. Default is 60 minutes."
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(
        title = "Poll frequency",
        description = "Interval between connector status checks while waiting for the sync to complete. Default is 5 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(5));

    @Schema(
        title = "Reattach to an already in-progress sync instead of starting a new one",
        description = """
            When true, adopts any sync already running on the connector -- including one Fivetran started itself on \
            its own schedule, not just a sync a previous run of this task triggered -- instead of triggering a new \
            one. Fivetran exposes no way to tell one sync run apart from another, so adoption matches on the \
            connector's own `sync_state: syncing` as reported by Fivetran, not on this execution's identity. This \
            covers a worker-loss resubmit, a retry, a replay, a manual re-run, or two executions driving the same \
            connector: whichever one observes the connector already syncing waits on it instead of triggering a \
            duplicate. Default is false. `force: true` always takes precedence over `reattach` and starts a new \
            sync regardless."""
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    Property<Boolean> reattach = Property.ofValue(false);

    @Schema(
        title = "Maximum age of an in-progress sync that can still be re-attached to",
        description = """
            Used only when `reattach` is true. Fivetran reports no sync start time, so the connector's last \
            completion timestamp is used as a conservative upper bound on how long the current sync has been \
            running: when that bound exceeds `reattachMaxAge`, the in-progress sync is treated as stale and a new \
            sync is triggered instead of adopting it. Default is unbounded: any in-progress sync is adopted, \
            however long it has been running."""
    )
    @PluginProperty(group = "reliability")
    Property<Duration> reattachMaxAge;

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private transient Map<Integer, Integer> loggedLine = new HashMap<>();

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String connectorId = runContext.render(this.connectorId).as(String.class).orElseThrow();

        Duration rPollFrequency = runContext.render(this.pollFrequency).as(Duration.class).orElseThrow();
        if (rPollFrequency.isNegative() || rPollFrequency.isZero()) {
            throw new IllegalArgumentException("pollFrequency must be a positive duration, but was " + rPollFrequency);
        }

        Duration rReattachMaxAge = runContext.render(this.reattachMaxAge).as(Duration.class).orElse(null);
        if (rReattachMaxAge != null && rReattachMaxAge.isNegative()) {
            throw new IllegalArgumentException("reattachMaxAge must not be negative, but was " + rReattachMaxAge);
        }

        Connector previousConnector = fetchConnector(runContext);

        boolean rForce = runContext.render(this.force).as(Boolean.class).orElseThrow();
        boolean rReattach = runContext.render(this.reattach).as(Boolean.class).orElseThrow();
        if (rForce && rReattach) {
            logger.warn("`force` takes precedence over `reattach`: a new sync will be triggered even though re-attach was requested");
        }

        boolean reattached = rReattach
            && !rForce
            && isSyncing(previousConnector)
            && !isStaleForReattach(previousConnector, rReattachMaxAge, ZonedDateTime.now());

        if (reattached) {
            logger.info("Reattached to the sync already running on connector '{}' instead of triggering a new one", connectorId);
        } else {
            HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
                .uri(
                    URI.create(
                        rBaseUrl(runContext) + "/v2/connectors/" + encodePathSegment(connectorId) + "/sync"
                    )
                )
                .method("POST")
                .body(
                    HttpRequest.JsonRequestBody.builder()
                        .content(Map.of("force", rForce))
                        .build()
                );

            HttpResponse<SyncResponse> syncHttpResponse = this.request(runContext, requestBuilder, SyncResponse.class);
            SyncResponse syncResponse = syncHttpResponse.getBody();
            if (syncResponse == null) {
                throw new IllegalStateException("Missing body on trigger");
            }

            logger.info("Job status {} with response: {}", syncHttpResponse.getStatus(), syncResponse);
        }

        runContext.metric(Counter.of("reattached", reattached ? 1 : 0));

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
            emitAssets(runContext, connectorId, previousConnector);
            return Output.builder().connectorId(connectorId).reattached(reattached).build();
        }

        ZonedDateTime previousCompletedDate = previousConnector.completedDate();
        Duration rMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElseThrow();
        AtomicReference<Exception> lastTransientError = new AtomicReference<>();
        Connector finalConnector;
        try {
            finalConnector = Await.until(
                throwSupplier(() ->
                {
                    Connector current;
                    try {
                        current = fetchConnector(runContext);
                    } catch (Exception e) {
                        // A transient read failure is not a sync failure, so keep polling; see isTransientReadFailure.
                        if (isTransientReadFailure(e)) {
                            lastTransientError.set(e);
                            logger.warn("Could not read connector '{}' status, retrying on next poll: {}", connectorId, e.getMessage());
                            return null;
                        }
                        throw e;
                    }

                    if (
                        current.completedDate() != null
                            && (previousCompletedDate == null || current.completedDate().isAfter(previousCompletedDate))
                    ) {
                        return current;
                    }
                    return null;
                }),
                rPollFrequency,
                rMaxDuration
            );
        } catch (TimeoutException e) {
            // If polling only ever saw transient errors, name the last one so the failure is diagnosable
            // instead of surfacing Await's generic "failed to terminate" message with no cause.
            Exception last = lastTransientError.get();
            if (last == null) {
                throw e;
            }
            throw new TimeoutException(
                "Connector '" + connectorId + "' did not complete within " + rMaxDuration
                    + ", last error while polling: " + last.getMessage()
            );
        }

        if (finalConnector.hasFailed()) {
            throw new Exception(
                "Connector '" + connectorId + "' failed"
                    + (reattached ? " (this task re-attached to a sync it did not trigger)" : "")
                    + ": " + finalConnector
            );
        }

        emitAssets(runContext, connectorId, finalConnector);

        return Output.builder()
            .connectorId(connectorId)
            .succeededAt(finalConnector.getSucceededAt())
            .reattached(reattached)
            .build();
    }

    // Single-connector view of the shared lineage helper.
    private void emitAssets(RunContext runContext, String connectorId, Connector connector) {
        ConnectorStatusResponse status = connector.getStatus();
        Map<String, String> syncStates = new HashMap<>();
        syncStates.put(connectorId, status != null ? status.getSyncState() : null);

        this.emitAssets(runContext, Map.of(connectorId, connector), syncStates);
    }

    /**
     * Whether a failed status read is transient and polling should continue. The sync keeps running
     * on Fivetran regardless, so a transient read failure is not a sync failure; other errors fail fast.
     */
    static boolean isTransientReadFailure(Throwable e) {
        return isRetriableTransientError(e, "GET");
    }

    private static final String SYNCING_STATE = "syncing";

    // rescheduled is a queued retry, not a running sync, so only syncing counts as in-flight.
    static boolean isSyncing(Connector connector) {
        ConnectorStatusResponse status = connector.getStatus();
        return status != null && SYNCING_STATE.equalsIgnoreCase(status.getSyncState());
    }

    /**
     * Fivetran reports no sync start time, so completedDate() is used as a conservative upper bound on how
     * long the current sync has been running: an in-flight sync necessarily started after the last
     * completion. False negatives (refusing to adopt a fresh sync) are the safe direction here; false
     * positives cannot occur. Parameterized on {@code now} so tests can assert the boundary deterministically.
     */
    static boolean isStaleForReattach(Connector connector, Duration maxAge, ZonedDateTime now) {
        if (maxAge == null) {
            return false;
        }

        ZonedDateTime completedDate = connector.completedDate();
        if (completedDate == null) {
            return true;
        }

        return completedDate.isBefore(now.minus(maxAge));
    }

    private Connector fetchConnector(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        String connectorId = runContext.render(this.connectorId).as(String.class).orElseThrow();
        return this.fetchConnector(runContext, connectorId);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Connector ID",
            description = "The connector this task synced."
        )
        String connectorId;

        @Schema(
            title = "Timestamp of the sync this task waited for",
            description = "Null when `wait` is false, since the sync is still running on Fivetran when the task returns."
        )
        ZonedDateTime succeededAt;

        @Schema(
            title = "Whether an already in-progress sync was re-attached to",
            description = "True when `reattach` adopted a sync already running on the connector instead of triggering a new one."
        )
        boolean reattached;
    }
}
