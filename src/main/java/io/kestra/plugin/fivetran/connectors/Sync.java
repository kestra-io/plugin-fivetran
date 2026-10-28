package io.kestra.plugin.fivetran.connectors;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
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
    description = """
        Starts a Fivetran connector sync through the Fivetran API.
        Can force-cancel and restart an in-progress sync, or re-attach to one already in progress with `reattach`.
        Waits for completion by default (up to 60 minutes) and fails if the connector reports a failure.
        Fails right away on a paused connector, since Fivetran accepts the sync request but starts nothing.
        A completion Fivetran reports while the connector is still syncing waits up to 1 minute for the syncing state to end before it is accepted, so the task does not return before the sync has finished, and a busy connector whose next sync starts right away still returns within that minute.
        With `assets.enableAuto` set and `wait: true`, emits one asset per table the connector writes, with the id `database.schema.name` so it joins the dbt model reading that table in the lineage graph, and the source table as its input: a `Table` asset for database sources (e.g. PostgreSQL, MySQL, SQL Server, Snowflake, BigQuery) or an `External` asset `connectorId.schema.table` otherwise. With `wait: false` no asset is emitted, since the sync has not finished when the task returns.
        If building or emitting an asset fails, `assets.assetFailureBehavior` applies: `FAIL` fails the task, `WARN` (the default) ends it in WARNING, `IGNORE` only logs at debug level.
        Whatever `assets.enableAuto` is set to, reads the connector's schema config once (one extra GET) to output a per-table breakdown file (`tables`, `tableCount`, `enabledTableCount`) and a `freshness` summary with the connector's alert codes; a failed schema read logs a warning and never fails the task.
        """
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
            title = "Sync a connector, then read its freshness and per-table breakdown.",
            code = """
                id: fivetran_sync_with_breakdown
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.fivetran.connectors.Sync
                    apiKey: "{{ secret('FIVETRAN_API_KEY') }}"
                    apiSecret: "{{ secret('FIVETRAN_API_SECRET') }}"
                    connectorId: "connector_id"
                    wait: true

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: |
                      Alerts: {{ outputs.sync.freshness.alertCount }},
                      enabled tables: {{ outputs.sync.enabledTableCount }} of {{ outputs.sync.tableCount }},
                      breakdown file: {{ outputs.sync.tables }}
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
        @Metric(name = "reattached", type = Counter.TYPE, description = "Whether the task re-attached to an already in-progress sync (1) instead of triggering a new one (0)."),
        @Metric(
            name = "resumed", type = Counter.TYPE,
            description = "Whether the task resumed polling a sync this task run triggered before it was interrupted (1) instead of triggering a new one (0)."
        )
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
        description = "When true (default), poll the connector until the sync finishes to capture status and logs. Set to false to return once the sync has been triggered, without waiting for it. `succeededAt` is then null, since the sync is still running on Fivetran. No assets are emitted in that case, since the sync has not finished. To record lineage after the sync, add a downstream `io.kestra.plugin.fivetran.connectors.Status` task with `assets.enableAuto`. If the task run is interrupted while waiting (the worker is lost, or `maxDuration` is reached and the task is retried), the next attempt resumes polling the sync it triggered instead of triggering another one. Only a sync triggered less than twice `maxDuration` ago is resumed: a restart of the execution long after triggers a new sync. A paused connector fails right away, unless the resumed sync already completed."
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
            running: when that bound exceeds `reattachMaxAge`, the in-progress sync is treated as stale: it is \
            cancelled and a new sync is started (the trigger is sent with `force: true`) instead of adopting it. \
            A connector that has never completed a sync has no such upper bound to check, so once \
            `reattachMaxAge` is set it is always treated as stale and never adopted -- also taking the forced, \
            cancel-and-restart path. Default is unbounded: any in-progress sync is adopted, however long it has \
            been running."""
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

        var rReattachMaxAge = runContext.render(this.reattachMaxAge).as(Duration.class).orElse(null);
        if (rReattachMaxAge != null && rReattachMaxAge.isNegative()) {
            throw new IllegalArgumentException("reattachMaxAge must not be negative, but was " + rReattachMaxAge);
        }

        Connector previousConnector = fetchConnector(runContext);

        var rForce = runContext.render(this.force).as(Boolean.class).orElseThrow();
        var rReattach = runContext.render(this.reattach).as(Boolean.class).orElse(false);
        if (rForce && rReattach) {
            logger.warn("`force` takes precedence over `reattach`: a new sync will be triggered even though re-attach was requested");
        }

        var rWait = runContext.render(this.wait).as(Boolean.class).orElseThrow();
        Duration rMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElseThrow();
        // A sync this task run triggered before it was interrupted (worker lost, maxDuration hit): resume polling it
        // instead of triggering a second one. With `wait: false` there is nothing to resume.
        var resumed = rWait ? readCheckpoint(runContext, rMaxDuration, Instant.now()) : Optional.<SyncCheckpoint> empty();

        // A resumed sync may have finished before the connector was paused, so polling decides there.
        if (resumed.isEmpty() && Boolean.TRUE.equals(previousConnector.getPaused())) {
            throw pausedConnector(connectorId);
        }

        boolean reattached;
        boolean queuedBehindRunning;
        ZonedDateTime previousCompletedDate;
        if (resumed.isPresent()) {
            var checkpoint = resumed.get();
            reattached = checkpoint.reattached();
            queuedBehindRunning = checkpoint.queuedBehindRunning();
            previousCompletedDate = checkpoint.baselineDate();
            logger.info(
                "Resuming the sync this task run {} on connector '{}' before it was interrupted, instead of triggering a new one",
                reattached ? "re-attached to" : "triggered",
                connectorId
            );
        } else {
            var wasSyncing = isSyncing(previousConnector);
            var staleSync = rReattach
                && !rForce
                && wasSyncing
                && isStaleForReattach(previousConnector, rReattachMaxAge, ZonedDateTime.now());
            reattached = rReattach && !rForce && wasSyncing && !staleSync;
            queuedBehindRunning = wasSyncing && !reattached && !rForce && !staleSync;
            previousCompletedDate = previousConnector.completedDate();

            if (reattached) {
                logger.info("Reattached to the sync already running on connector '{}' instead of triggering a new one", connectorId);
            } else {
                // A stale in-progress sync ignores a non-forced trigger on Fivetran's side, so force it here to
                // cancel that stale sync and start a fresh one instead of adopting it.
                HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
                    .uri(
                        URI.create(
                            rBaseUrl(runContext) + "/v2/connectors/" + encodePathSegment(connectorId) + "/sync"
                        )
                    )
                    .method("POST")
                    .body(
                        HttpRequest.JsonRequestBody.builder()
                            .content(Map.of("force", rForce || staleSync))
                            .build()
                    );

                HttpResponse<SyncResponse> syncHttpResponse = this.request(runContext, requestBuilder, SyncResponse.class);
                SyncResponse syncResponse = syncHttpResponse.getBody();
                if (syncResponse == null) {
                    throw new IllegalStateException("Missing body on trigger");
                }
                // Paused between the status read and the trigger: Fivetran still answers 200 Success.
                if (syncResponse.getMessage() != null && syncResponse.getMessage().toLowerCase(Locale.ROOT).contains("is paused")) {
                    throw pausedConnector(connectorId);
                }

                logger.info("Job status {} with response: {}", syncHttpResponse.getStatus(), syncResponse);
            }

            if (rWait) {
                writeCheckpoint(runContext, SyncCheckpoint.triggered(previousCompletedDate, queuedBehindRunning, reattached, Instant.now()));
            }
        }

        runContext.metric(Counter.of("reattached", reattached ? 1 : 0));
        runContext.metric(Counter.of("resumed", resumed.isPresent() ? 1 : 0));

        if (!rWait) {
            var outputBuilder = Output.builder().connectorId(connectorId).reattached(reattached);
            return withBreakdown(runContext, connectorId, previousConnector, ConnectorFreshness.from(previousConnector, null), outputBuilder, false);
        }

        var completion = new CompletionWatch(connectorId, previousCompletedDate, queuedBehindRunning, COMPLETION_GRACE, Instant::now, logger);
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

                    var accepted = completion.accept(current);
                    // A paused connector starts no sync, so a resumed run that saw no completion would only time out.
                    if (accepted == null && resumed.isPresent() && Boolean.TRUE.equals(current.getPaused())) {
                        throw pausedConnector(connectorId);
                    }
                    return accepted;
                }),
                rPollFrequency,
                rMaxDuration
            );
        } catch (TimeoutException e) {
            // The sync completed but Fivetran still reported syncing when maxDuration ran out: the completion is real,
            // so return it rather than failing a sync that finished.
            var candidate = completion.candidate();
            if (candidate.isPresent()) {
                logger.warn(
                    "Connector '{}' completed at {} but Fivetran still reported it syncing when maxDuration ({}) ran out, returning that completion",
                    connectorId,
                    candidate.get().completedDate(),
                    rMaxDuration
                );
                finalConnector = candidate.get();
            } else {
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
        }

        // The sync this task run was waiting on has ended, failed or not: a retry must trigger a new one.
        writeCheckpoint(runContext, SyncCheckpoint.done());

        if (finalConnector.hasFailed()) {
            throw new Exception(
                "Connector '" + connectorId + "' failed"
                    + (reattached ? " (this task re-attached to a sync it did not trigger)" : "")
                    + ": " + finalConnector
            );
        }

        var outputBuilder = Output.builder()
            .connectorId(connectorId)
            .succeededAt(finalConnector.getSucceededAt())
            .reattached(reattached);
        return withBreakdown(runContext, connectorId, finalConnector, ConnectorFreshness.from(finalConnector, true), outputBuilder, true);
    }

    static final String CHECKPOINT_KEY = "fivetran.sync";

    /**
     * What a task run needs to resume polling the sync it started, saved once the sync is triggered and marked
     * {@code done} once polling sees it end. Checkpoints are scoped to the task run, which keeps its id when a lost
     * worker's task is resubmitted, across {@code retry} attempts and across a manual restart of the execution.
     *
     * @param baseline the connector's completion date before the trigger, as ISO-8601, null if it never completed
     * @param triggeredAt when the sync was triggered, as ISO-8601; null on checkpoints saved before it was recorded
     */
    record SyncCheckpoint(String state, String baseline, boolean queuedBehindRunning, boolean reattached, String triggeredAt) {

        static final String TRIGGERED = "triggered";
        static final String DONE = "done";

        static SyncCheckpoint triggered(ZonedDateTime baseline, boolean queuedBehindRunning, boolean reattached, Instant triggeredAt) {
            return new SyncCheckpoint(TRIGGERED, baseline != null ? baseline.toString() : null, queuedBehindRunning, reattached, triggeredAt.toString());
        }

        static SyncCheckpoint done() {
            return new SyncCheckpoint(DONE, null, false, false, null);
        }

        ZonedDateTime baselineDate() {
            return baseline != null ? ZonedDateTime.parse(baseline) : null;
        }

        Instant triggeredInstant() {
            return triggeredAt != null ? Instant.parse(triggeredAt) : null;
        }
    }

    /**
     * The sync to resume, empty when this task run has none in flight. Checkpoints only make resuming possible, so
     * a store that cannot be read, or a value that cannot be parsed, is logged and the task triggers a sync as usual.
     * A checkpoint older than twice {@code maxDuration} (or with no trigger time) is stale: a {@code retry} right
     * after a timeout is well within that and keeps waiting on the running sync, whereas a restart long after must
     * not judge an unrelated later sync against an old baseline.
     */
    static Optional<SyncCheckpoint> readCheckpoint(RunContext runContext, Duration maxDuration, Instant now) {
        try {
            var checkpoints = runContext.checkpoints();
            if (checkpoints == null) {
                return Optional.empty();
            }
            var value = checkpoints.get(CHECKPOINT_KEY);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            var checkpoint = JacksonMapper.ofJson().readValue(value.get(), SyncCheckpoint.class);
            if (!SyncCheckpoint.TRIGGERED.equals(checkpoint.state())) {
                return Optional.empty();
            }

            var triggeredAt = checkpoint.triggeredInstant();
            if (triggeredAt == null) {
                runContext.logger().warn("The saved sync checkpoint has no trigger time, so it is ignored and a new sync is triggered");
                return Optional.empty();
            }
            var maxAge = maxDuration.multipliedBy(2);
            if (triggeredAt.isBefore(now.minus(maxAge))) {
                runContext.logger().warn(
                    "The saved sync checkpoint was taken at {}, more than twice maxDuration ({}) ago, so it is ignored and a new sync is triggered",
                    triggeredAt,
                    maxDuration
                );
                return Optional.empty();
            }
            return Optional.of(checkpoint);
        } catch (Exception e) {
            runContext.logger().warn("Could not read the sync checkpoint, a new sync is triggered: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // Never fails the task: a missing checkpoint only means a resubmitted task triggers a new sync, as before.
    static void writeCheckpoint(RunContext runContext, SyncCheckpoint checkpoint) {
        try {
            var checkpoints = runContext.checkpoints();
            if (checkpoints != null) {
                checkpoints.set(CHECKPOINT_KEY, JacksonMapper.ofJson().writeValueAsString(checkpoint));
            }
        } catch (Exception e) {
            runContext.logger().warn("Could not save the sync checkpoint, an interrupted run will trigger a new sync: {}", e.getMessage());
        }
    }

    // The schema config is read once and shared by lineage and the table breakdown.
    private Output withBreakdown(
        RunContext runContext,
        String connectorId,
        Connector connector,
        ConnectorFreshness freshness,
        Output.OutputBuilder outputBuilder,
        boolean emitAssets) throws IOException {
        var schemas = readSchemas(runContext, connectorId);

        // With `wait: false` the connector is the pre-trigger snapshot, which describes no finished sync.
        if (emitAssets) {
            var syncStates = new HashMap<String, String>();
            syncStates.put(connectorId, freshness.getSyncState());
            this.emitAssets(
                runContext,
                Map.of(connectorId, connector),
                syncStates,
                schemas != null ? Map.of(connectorId, schemas) : Map.of()
            ).ifPresent(outputBuilder::state);
        }

        outputBuilder.freshness(freshness);
        var breakdown = writeTableBreakdown(runContext, schemas);
        if (breakdown != null) {
            outputBuilder
                .tables(breakdown.getUri())
                .tableCount(breakdown.getTableCount())
                .enabledTableCount(breakdown.getEnabledTableCount());
        }
        return outputBuilder.build();
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

    // How long a completion seen while Fivetran still reports syncing waits for the syncing state to end.
    static final Duration COMPLETION_GRACE = Duration.ofMinutes(1);

    /**
     * Decides, poll after poll, whether the sync this task waits for has completed.
     * <p>
     * A completion newer than the one before the trigger is accepted as soon as Fivetran stops reporting
     * {@code syncing}. Seen while it still reports {@code syncing}, the completion is ambiguous: a real Fivetran
     * connector can report a completion and keep syncing for a few more seconds, and a busy connector starts its next
     * sync right after ours. So the completion waits for the syncing state to end, for at most {@code grace}, and is
     * then accepted anyway: a back-to-back sync costs that much and never hangs the task until maxDuration.
     * <p>
     * When the trigger was queued behind a sync already running, the first completion seen while syncing is that
     * earlier sync's and is skipped.
     */
    static final class CompletionWatch {
        private final ZonedDateTime baseline;
        private final boolean queuedBehindRunning;
        private final Duration grace;
        private final Supplier<Instant> clock;
        private final String connectorId;
        private final Logger logger;
        private ZonedDateTime earlierSyncCompletion;
        private Connector candidate;
        private Instant candidateSince;

        CompletionWatch(String connectorId, ZonedDateTime baseline, boolean queuedBehindRunning, Duration grace, Supplier<Instant> clock, Logger logger) {
            this.connectorId = connectorId;
            this.logger = logger;
            this.baseline = baseline;
            this.queuedBehindRunning = queuedBehindRunning;
            this.grace = grace;
            this.clock = clock;
        }

        /** The connector once its sync is accepted as complete, null to keep polling. */
        Connector accept(Connector current) {
            var completedDate = current.completedDate();
            if (completedDate == null || (baseline != null && !completedDate.isAfter(baseline))) {
                return null;
            }

            if (!isSyncing(current)) {
                return current;
            }

            if (queuedBehindRunning) {
                if (earlierSyncCompletion == null) {
                    earlierSyncCompletion = completedDate;
                    return null;
                }
                if (!completedDate.isAfter(earlierSyncCompletion)) {
                    return null;
                }
            }

            candidate = current;
            if (candidateSince == null) {
                candidateSince = clock.get();
                // Logged once per hold, so the case stays visible without one line per poll.
                logger.debug(
                    "Connector '{}' reports a completion at {} but is still syncing, waiting up to {} for the sync to end",
                    connectorId,
                    completedDate,
                    grace
                );
            }
            if (Duration.between(candidateSince, clock.get()).compareTo(grace) < 0) {
                return null;
            }
            logger.debug(
                "Connector '{}' is still syncing {} after reporting a completion at {}, accepting that completion",
                connectorId,
                grace,
                completedDate
            );
            return current;
        }

        /** The latest completion seen while Fivetran still reported syncing, if any. */
        Optional<Connector> candidate() {
            return Optional.ofNullable(candidate);
        }
    }

    /**
     * Fivetran answers a sync request on a paused connector with 200 Success and "Connection with id '...' is
     * paused", and starts nothing, so waiting on it could only end in a maxDuration timeout.
     */
    private static IllegalStateException pausedConnector(String connectorId) {
        return new IllegalStateException(
            "Connector '" + connectorId + "' is paused in Fivetran, which ignores sync requests on a paused connector. Resume it in Fivetran, then run the task again."
        );
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
            description = "The completion of the sync this task observed, as reported by Fivetran. Null when `wait` is false, since the sync is still running on Fivetran when the task returns. See `freshness.succeededAt` for the connector's last known success."
        )
        ZonedDateTime succeededAt;

        @Schema(
            title = "Whether an already in-progress sync was re-attached to",
            description = "True when `reattach` adopted a sync already running on the connector instead of triggering a new one."
        )
        boolean reattached;

        @Schema(
            title = "Connector freshness",
            description = """
                State of the connector when the task returned.
                With `wait: true` it is read after the sync completed.
                With `wait: false` it is the snapshot taken before the sync was triggered, so `fresh` is null and `freshness.succeededAt` is the connector's last known success, which predates the triggered sync.
                The top-level `succeededAt` is the completion of the sync this task observed.
                """
        )
        ConnectorFreshness freshness;

        @Schema(
            title = "Per-table breakdown file",
            description = """
                URI of an Ion file in internal storage with one row per table of the connector: `schema`, `schemaInDestination`, `schemaEnabled`, `table`, `tableInDestination`, `enabled`, `syncMode` and `enabledPatchAllowed`.
                Null when the schema config could not be read.
                """
        )
        URI tables;

        @Schema(
            title = "Number of tables",
            description = "Every table the connector reports, enabled or not. Null when the schema config could not be read."
        )
        Integer tableCount;

        @Schema(
            title = "Number of enabled tables",
            description = "Tables that are enabled and belong to an enabled schema, i.e. the ones written to the destination. Null when the schema config could not be read."
        )
        Integer enabledTableCount;

        @JsonIgnore
        @Getter(AccessLevel.NONE)
        State.Type state;

        @Override
        public Optional<State.Type> finalState() {
            return Optional.ofNullable(state);
        }
    }
}
