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
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.fivetran.AbstractFivetranConnection;
import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorResponse;
import io.kestra.plugin.fivetran.models.SyncResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger and optionally watch connector sync",
    description = "Starts a Fivetran connector sync through the Fivetran API. Can force-cancel and restart an in-progress sync. Waits for completion by default (up to 60 minutes) and fails if the connector reports a failure."
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
        )
    }
)
public class Sync extends AbstractFivetranConnection implements RunnableTask<VoidOutput> {
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
        description = "When true (default), poll the connector until the sync finishes to capture status and logs. Set to false to return immediately after kickoff."
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

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private transient Map<Integer, Integer> loggedLine = new HashMap<>();

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String connectorId = runContext.render(this.connectorId).as(String.class).orElseThrow();

        Duration rPollFrequency = runContext.render(this.pollFrequency).as(Duration.class).orElseThrow();
        if (rPollFrequency.isNegative() || rPollFrequency.isZero()) {
            throw new IllegalArgumentException("pollFrequency must be a positive duration, but was " + rPollFrequency);
        }

        Connector previousConnector = fetchConnector(runContext);

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(
                URI.create(
                    runContext.render(this.getBaseUrl()).as(String.class).orElseThrow() +
                        "/v2/connectors/" + connectorId + "/sync"
                )
            )
            .method("POST")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(Map.of("force", runContext.render(this.force).as(Boolean.class).orElseThrow()))
                    .build()
            );

        HttpResponse<SyncResponse> syncHttpResponse = this.request(runContext, requestBuilder, SyncResponse.class);
        SyncResponse syncResponse = syncHttpResponse.getBody();
        if (syncResponse == null) {
            throw new IllegalStateException("Missing body on trigger");
        }

        logger.info("Job status {} with response: {}", syncHttpResponse.getStatus(), syncResponse);

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
            return null;
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
            throw new Exception("Connector '" + connectorId + "' failed: " + finalConnector);
        }

        return null;
    }

    /**
     * Whether a failed status read is transient and polling should continue. The sync keeps running
     * on Fivetran regardless, so a transient read failure is not a sync failure; other errors fail fast.
     */
    static boolean isTransientReadFailure(Throwable e) {
        return isRetriableTransientError(e, "GET");
    }

    private Connector fetchConnector(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        String connectorId = runContext.render(this.connectorId).as(String.class).orElseThrow();

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(
                URI.create(
                    runContext.render(this.getBaseUrl()).as(String.class).orElseThrow() +
                        "/v2/connectors/" + connectorId
                )
            )
            .method("GET");

        HttpResponse<ConnectorResponse> fetchConnector = this.request(runContext, requestBuilder, ConnectorResponse.class);

        return fetchConnector.getBody().getData();
    }

}
