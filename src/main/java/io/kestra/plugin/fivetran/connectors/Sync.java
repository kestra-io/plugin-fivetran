package io.kestra.plugin.fivetran.connectors;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
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
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import jakarta.validation.constraints.NotNull;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a sync on a Fivetran connection."
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
                    apiKey: "api_key"
                    apiSecret: "api_secret"
                    connectorId: "connector_id"
                """
        )
    }
)
public class Sync extends AbstractFivetranConnection implements RunnableTask<VoidOutput> {
    @Schema(
        title = "The connector id to sync."
    )
    @NotNull
    private Property<String> connectorId;

    @Schema(
        title = "Force with running sync.",
        description = "If `force` is true and the connector is currently syncing, it will stop the sync and re-run it. " +
            "If force is `false`, the connector will sync only if it isn't currently syncing."
    )
    @Builder.Default
    Property<Boolean> force = Property.of(false);

    @Schema(
        title = "Wait for the end of the job.",
        description = "Allowing to capture job status & logs."
    )
    @Builder.Default
    Property<Boolean> wait = Property.of(true);

    @Schema(
        title = "The max total wait duration."
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.of(Duration.ofMinutes(60));

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private transient Map<Integer, Integer> loggedLine = new HashMap<>();

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String connectorId = runContext.render(this.connectorId).as(String.class).orElseThrow();

        Connector previousConnector = fetchConnector(runContext);

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(URI.create(runContext.render(this.getBaseUrl()).as(String.class).orElseThrow() +
                "/v2/connectors/" + connectorId + "/sync"))
            .method("POST")
            .body(HttpRequest.JsonRequestBody.builder()
                .content(Map.of("force", runContext.render(this.force).as(Boolean.class).orElseThrow()))
                .build());

        HttpResponse<SyncResponse> syncHttpResponse = this.request(runContext, requestBuilder, SyncResponse.class);
        SyncResponse syncResponse = syncHttpResponse.getBody();
        if (syncResponse == null) {
            throw new IllegalStateException("Missing body on trigger");
        }

        logger.info("Job status {} with response: {}", syncHttpResponse.getStatus(), syncResponse);

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
            return null;
        }

        // Wait for sync completion
        Connector finalConnector = Await.until(
            throwSupplier(() -> {
                Connector current = fetchConnector(runContext);
                if (current.completedDate() != null && current.completedDate().compareTo(previousConnector.completedDate()) > 0) {
                    return current;
                }
                return null;
            }),
            Duration.ofSeconds(1),
            runContext.render(this.maxDuration).as(Duration.class).orElseThrow()
        );

        if (finalConnector.getFailedAt() != null) {
            throw new Exception("Connector '" + connectorId + "' failed: " + finalConnector);
        }

        return null;
    }


    private Connector fetchConnector(RunContext runContext) throws IllegalVariableEvaluationException, HttpClientException {
        String connectorId = runContext.render(this.connectorId).as(String.class).orElseThrow();

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(URI.create(runContext.render(this.getBaseUrl()).as(String.class).orElseThrow() +
                "/v2/connectors/" + connectorId))
            .method("GET");

        HttpResponse<ConnectorResponse> fetchConnector = this.request(runContext, requestBuilder, ConnectorResponse.class);

        return fetchConnector.getBody().getData();
    }

}
