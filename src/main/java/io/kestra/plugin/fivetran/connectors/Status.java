package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.fivetran.AbstractFivetranConnection;
import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorStatusResponse;

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
    title = "Read the status of one or more Fivetran connectors, optionally gating on freshness",
    description = "Reads each connector's current status through a single GET call per connector. Never triggers a sync. By default (`wait: true`), polls until every connector is fresh so downstream tasks only run once the underlying data is up to date; set `wait: false` for a single read-only snapshot."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: fivetran_status_gate
                namespace: company.team

                tasks:
                  - id: wait_until_fresh
                    type: io.kestra.plugin.fivetran.connectors.Status
                    apiKey: "{{ secret('FIVETRAN_API_KEY') }}"
                    apiSecret: "{{ secret('FIVETRAN_API_SECRET') }}"
                    connectorIds:
                      - connector_id_1
                      - connector_id_2
                    slack: PT10M

                  - id: run_after_fresh_data
                    type: io.kestra.plugin.core.log.Log
                    message: "Connectors are fresh: {{ outputs.wait_until_fresh.connectors }}"
                """
        ),
        @Example(
            full = true,
            code = """
                id: fivetran_status_snapshot
                namespace: company.team

                tasks:
                  - id: status
                    type: io.kestra.plugin.fivetran.connectors.Status
                    apiKey: "{{ secret('FIVETRAN_API_KEY') }}"
                    apiSecret: "{{ secret('FIVETRAN_API_SECRET') }}"
                    connectorIds:
                      - connector_id_1
                      - connector_id_2
                    wait: false
                """
        )
    }
)
public class Status extends AbstractFivetranConnection implements RunnableTask<Status.Output> {
    private static final String CONNECTED_SETUP_STATE = "connected";
    private static final String TABLE_ASSET_TYPE = "io.kestra.plugin.ee.assets.Table";

    @Schema(
        title = "Connector IDs",
        description = "Identifiers of the Fivetran connectors whose status should be read."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> connectorIds;

    @Schema(
        title = "Freshness slack",
        description = "Buffer added on top of each connector's `syncFrequency` when deciding whether its last sync is fresh. Default is no buffer (`PT0S`)."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    Property<Duration> slack = Property.ofValue(Duration.ZERO);

    @Schema(
        title = "Wait until every connector is fresh",
        description = "When true (default), poll all connectors until each one is fresh, capped by `maxDuration`. Set to false to read each connector once and report its status without gating."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum wait duration",
        description = "Upper bound for waiting when `wait` is true. Default is 1 hour."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    @Schema(
        title = "Poll frequency",
        description = "Interval between connector status checks while waiting for freshness. Default is 30 seconds. Must not be greater than `maxDuration`."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(30));

    @Schema(
        title = "Allow terminal-state connectors instead of failing fast",
        description = "When `wait` is true, a paused connector or one whose setup is not `connected` can never become fresh, so the task fails fast by default instead of polling forever. Set to true to report such connectors as not-fresh instead, letting the gate wait out `maxDuration`."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    Property<Boolean> allowFailed = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        List<String> rConnectorIds = runContext.render(this.connectorIds).asList(String.class);
        if (rConnectorIds.isEmpty()) {
            throw new IllegalArgumentException("connectorIds must not be empty");
        }
        for (int i = 0; i < rConnectorIds.size(); i++) {
            String connectorId = rConnectorIds.get(i);
            if (connectorId == null || connectorId.isBlank()) {
                throw new IllegalArgumentException("connectorIds[" + i + "] must not be null or blank");
            }
        }
        Duration rSlack = runContext.render(this.slack).as(Duration.class).orElse(Duration.ZERO);
        if (rSlack.isNegative()) {
            throw new IllegalArgumentException("slack must not be negative, but was " + rSlack);
        }
        boolean rWait = runContext.render(this.wait).as(Boolean.class).orElse(true);
        boolean rAllowFailed = runContext.render(this.allowFailed).as(Boolean.class).orElse(false);

        if (!rWait) {
            Map<String, Connector> connectors = fetchAll(runContext, rConnectorIds);
            Map<String, ConnectorState> states = toStates(connectors, rSlack);
            logStates(logger, states);
            emitAssets(runContext, connectors, states);
            return Output.builder().connectors(states).build();
        }

        Duration rPollFrequency = runContext.render(this.pollFrequency).as(Duration.class).orElse(Duration.ofSeconds(30));
        if (rPollFrequency.isNegative() || rPollFrequency.isZero()) {
            throw new IllegalArgumentException("pollFrequency must be a positive duration, but was " + rPollFrequency);
        }
        Duration rMaxDuration = runContext.render(this.maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        if (rPollFrequency.compareTo(rMaxDuration) > 0) {
            throw new IllegalArgumentException(
                "pollFrequency (" + rPollFrequency + ") must not be greater than maxDuration (" + rMaxDuration + ")"
            );
        }

        AtomicReference<Exception> lastTransientError = new AtomicReference<>();
        AtomicReference<Map<String, ConnectorState>> lastSeenStates = new AtomicReference<>();
        AtomicReference<Map<String, Connector>> lastSeenConnectors = new AtomicReference<>();

        Map<String, ConnectorState> finalStates;
        try {
            finalStates = Await.until(
                throwSupplier(() ->
                {
                    Map<String, Connector> current;
                    try {
                        current = fetchAll(runContext, rConnectorIds);
                    } catch (Exception e) {
                        // A transient read failure does not mean the connector is stale, so keep polling.
                        if (isRetriableTransientError(e, "GET")) {
                            lastTransientError.set(e);
                            logger.warn("Could not read connector status, retrying on next poll: {}", e.getMessage());
                            return null;
                        }
                        throw e;
                    }

                    Map<String, ConnectorState> states = toStates(current, rSlack);
                    lastSeenStates.set(states);
                    lastSeenConnectors.set(current);

                    for (Map.Entry<String, Connector> entry : current.entrySet()) {
                        String connectorId = entry.getKey();
                        Connector connector = entry.getValue();
                        ConnectorState state = states.get(connectorId);

                        // Freshness can never be determined without a sync_frequency, so waiting for it would poll forever.
                        if (state.getFresh() == null) {
                            throw new IllegalStateException(
                                "Connector '" + connectorId + "' has no sync_frequency reported by Fivetran, so freshness cannot be computed"
                            );
                        }

                        // A connector already fresh has satisfied the gate's promise regardless of its
                        // current paused/setup state (e.g. paused-after-sync-cost), so only fail fast when
                        // it is NOT fresh and can never become fresh on its own.
                        if (!rAllowFailed && !Boolean.TRUE.equals(state.getFresh()) && isTerminal(connector)) {
                            throw new IllegalStateException(
                                "Connector '" + connectorId + "' cannot become fresh: " + terminalReason(connector)
                            );
                        }
                    }

                    boolean allFresh = states.values().stream().allMatch(state -> Boolean.TRUE.equals(state.getFresh()));
                    return allFresh ? states : null;
                }),
                rPollFrequency,
                rMaxDuration
            );
        } catch (TimeoutException e) {
            throw new TimeoutException(timeoutMessage(rMaxDuration, lastSeenStates.get(), lastTransientError.get()));
        }

        logStates(logger, finalStates);
        emitAssets(runContext, lastSeenConnectors.get(), finalStates);
        return Output.builder().connectors(finalStates).build();
    }

    private Map<String, Connector> fetchAll(RunContext runContext, List<String> connectorIds)
        throws IllegalVariableEvaluationException, HttpClientException {
        Map<String, Connector> connectors = new LinkedHashMap<>();
        for (String connectorId : connectorIds) {
            connectors.put(connectorId, this.fetchConnector(runContext, connectorId));
        }
        return connectors;
    }

    private static Map<String, ConnectorState> toStates(Map<String, Connector> connectors, Duration slack) {
        ZonedDateTime now = ZonedDateTime.now();
        Map<String, ConnectorState> states = new LinkedHashMap<>();
        connectors.forEach((id, connector) -> states.put(id, toConnectorState(connector, now, slack)));
        return states;
    }

    private static ConnectorState toConnectorState(Connector connector, ZonedDateTime now, Duration slack) {
        ConnectorStatusResponse status = connector.getStatus();

        return ConnectorState.builder()
            .id(connector.getId())
            .name(connector.getName())
            .paused(connector.getPaused())
            .syncFrequency(connector.getSyncFrequency())
            .scheduleType(connector.getScheduleType())
            .succeededAt(connector.getSucceededAt())
            .failedAt(connector.getFailedAt())
            .completedDate(connector.completedDate())
            .hasFailed(connector.hasFailed())
            .syncState(status != null ? status.getSyncState() : null)
            .setupState(status != null ? status.getSetupState() : null)
            .schemaStatus(status != null ? status.getSchemaStatus() : null)
            .fresh(computeFresh(connector.getSucceededAt(), connector.hasFailed(), connector.getSyncFrequency(), slack, now))
            .build();
    }

    /**
     * Null when Fivetran reports no sync_frequency, since freshness cannot be related to a frequency that
     * doesn't exist. Otherwise, fresh only if the last completion was a success within syncFrequency + slack
     * of {@code now}, boundary inclusive: a success landing exactly on the threshold still counts as fresh.
     * Package-private and parameterized on {@code now} so the boundary can be asserted deterministically in
     * tests instead of racing the real clock.
     */
    static Boolean computeFresh(ZonedDateTime succeededAt, boolean hasFailed, Integer syncFrequency, Duration slack, ZonedDateTime now) {
        if (syncFrequency == null) {
            return null;
        }

        return succeededAt != null
            && !hasFailed
            && !succeededAt.isBefore(now.minusMinutes(syncFrequency).minus(slack));
    }

    // A paused connector, or one whose setup is not connected (broken, incomplete, bad-auth), can never
    // sync again on its own, so it can never become fresh.
    private static boolean isTerminal(Connector connector) {
        return Boolean.TRUE.equals(connector.getPaused()) || !CONNECTED_SETUP_STATE.equals(setupState(connector));
    }

    private static String terminalReason(Connector connector) {
        return "paused=" + connector.getPaused() + ", setupState=" + setupState(connector);
    }

    private static String setupState(Connector connector) {
        ConnectorStatusResponse status = connector.getStatus();
        return status != null ? status.getSetupState() : null;
    }

    private static String timeoutMessage(Duration maxDuration, Map<String, ConnectorState> lastSeenStates, Exception lastTransientError) {
        String stale = lastSeenStates == null
            ? ""
            : lastSeenStates.entrySet().stream()
                .filter(entry -> !Boolean.TRUE.equals(entry.getValue().getFresh()))
                .map(entry -> entry.getKey() + " (last succeeded at " + entry.getValue().getSucceededAt() + ")")
                .collect(Collectors.joining(", "));

        StringBuilder message = new StringBuilder("Connector(s) did not become fresh within ").append(maxDuration);
        if (!stale.isEmpty()) {
            message.append(", still stale: ").append(stale);
        }
        if (lastTransientError != null) {
            message.append("; last error while polling: ").append(lastTransientError.getMessage());
        }
        return message.toString();
    }

    private static void logStates(Logger logger, Map<String, ConnectorState> states) {
        states.forEach(
            (connectorId, state) -> logger.info(
                "Connector '{}' syncState={} succeededAt={} fresh={}",
                connectorId,
                state.getSyncState(),
                state.getSucceededAt(),
                state.getFresh()
            )
        );
    }

    private void emitAssets(RunContext runContext, Map<String, Connector> connectors, Map<String, ConnectorState> states)
        throws IllegalVariableEvaluationException {
        for (Map.Entry<String, Connector> entry : connectors.entrySet()) {
            String connectorId = entry.getKey();
            Connector connector = entry.getValue();
            // Fivetran's connector destination schema; name is the only sane fallback when it's absent.
            String schema = connector.getSchema() != null ? connector.getSchema() : connector.getName();
            String assetId = sanitizeAssetId(composeAssetId(connectorId, connector, schema));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("system", "fivetran");
            metadata.put("connectorId", connectorId);
            metadata.put("schema", schema);
            ConnectorState state = states.get(connectorId);
            if (state != null && state.getSyncState() != null) {
                metadata.put("syncState", state.getSyncState());
            }

            Asset asset = Custom.builder()
                .id(assetId)
                .type(TABLE_ASSET_TYPE)
                .metadata(metadata)
                .build();

            try {
                runContext.assets().emit(new AssetEmit(List.of(), List.of(asset)));
            } catch (UnsupportedOperationException e) {
                // OSS edition or tests where EE assets are not available — silently skip.
                runContext.logger().debug("Asset emission is not supported in this edition, skipping.");
                return;
            } catch (QueueException e) {
                runContext.logger().warn("Unable to emit fivetran asset for connector '{}'", connectorId, e);
            }
        }
    }

    // groupId scopes the schema to the connector's destination so two connectors landing in the same schema
    // (e.g. shared across groups) still get distinct asset ids; connectorId is the fallback when groupId is absent.
    private static String composeAssetId(String connectorId, Connector connector, String schema) {
        String prefix = connector.getGroupId() != null ? connector.getGroupId() : connectorId;
        return schema != null
            ? sanitizeSegment(prefix) + "." + sanitizeSegment(schema)
            : sanitizeSegment(connectorId);
    }

    // Segments must be sanitized individually, before "." is added as the delimiter: a raw segment can
    // itself contain a "." (e.g. Fivetran schema "google_sheets.destination"), so joining unsanitized
    // segments is ambiguous, letting ("g", "a.b") and ("g.a", "b") both compose to the same id "g.a.b".
    // Replacing "." (and anything else outside the allowed set) with "_" within each segment first means
    // "." in the composed id can only ever be the delimiter added here.
    private static String sanitizeSegment(String segment) {
        return segment.replaceAll("[^a-zA-Z0-9_:-]", "_");
    }

    // Fivetran ids (group_id, schema, connector id) are not guaranteed to satisfy Asset's id pattern
    // (^[a-zA-Z0-9][a-zA-Z0-9._:-]*, size 1-150). composeAssetId already restricts every segment to that
    // character set via sanitizeSegment, so only the leading-alnum trim and size bound remain here.
    // An id made only of characters stripped by the leading-alnum trim (e.g. "___") would otherwise
    // sanitize down to "", violating Asset.id's @NotBlank/@Size(min=1); fall back to a fixed placeholder.
    private static String sanitizeAssetId(String rawId) {
        String sanitized = rawId.replaceFirst("^[^a-zA-Z0-9]+", "");
        if (sanitized.isEmpty()) {
            sanitized = "connector";
        }
        return sanitized.length() > 150 ? sanitized.substring(0, 150) : sanitized;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Connector statuses",
            description = "Map of connector ID to its current status, in the order the IDs were requested."
        )
        Map<String, ConnectorState> connectors;
    }

    @Builder
    @Getter
    public static class ConnectorState {
        @Schema(
            title = "Connector ID"
        )
        String id;

        @Schema(
            title = "Connector name"
        )
        String name;

        @Schema(
            title = "Whether the connector is paused"
        )
        Boolean paused;

        @Schema(
            title = "Sync frequency in minutes"
        )
        Integer syncFrequency;

        @Schema(
            title = "Schedule type",
            description = "Either `auto` or `manual`."
        )
        String scheduleType;

        @Schema(
            title = "Timestamp of the last successful sync"
        )
        ZonedDateTime succeededAt;

        @Schema(
            title = "Timestamp of the last failed sync"
        )
        ZonedDateTime failedAt;

        @Schema(
            title = "Timestamp of the most recent sync completion",
            description = "Whichever of `succeededAt` or `failedAt` is more recent."
        )
        ZonedDateTime completedDate;

        @Schema(
            title = "Whether the last sync completion was a failure",
            description = "True only when the most recent `failedAt` is not followed by a later `succeededAt`."
        )
        boolean hasFailed;

        @Schema(
            title = "Current sync state",
            description = "As reported by Fivetran, e.g. `scheduled`, `syncing`, `paused`, `rescheduled`."
        )
        String syncState;

        @Schema(
            title = "Current setup state",
            description = "As reported by Fivetran, e.g. `connected`, `broken`, `incomplete`."
        )
        String setupState;

        @Schema(
            title = "Current schema status",
            description = "As reported by Fivetran, e.g. `ready`, `blocked`."
        )
        String schemaStatus;

        @Schema(
            title = "Whether the connector's last sync is fresh",
            description = "True when the last sync succeeded within `syncFrequency` plus `slack` of now. False when stale, or the last completion was a failure. Null when Fivetran reported no `syncFrequency` for this connector, so freshness cannot be computed."
        )
        Boolean fresh;
    }
}
