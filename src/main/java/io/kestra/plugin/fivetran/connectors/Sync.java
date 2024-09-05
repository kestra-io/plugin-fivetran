package io.kestra.plugin.fivetran.connectors;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.fivetran.AbstractFivetranConnection;
import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorResponse;
import io.kestra.plugin.fivetran.models.SyncResponse;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.uri.UriTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

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
    title = "Run a sync on a connection."
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
    @PluginProperty(dynamic = true)
    @NotNull
    private String connectorId;

    @Schema(
        title = "Force with running sync.",
        description = "If `force` is true and the connector is currently syncing, it will stop the sync and re-run it. " +
            "If force is `false`, the connector will sync only if it isn't currently syncing."
    )
    @PluginProperty(dynamic = false)
    @Builder.Default
    Boolean force = false;

    @Schema(
        title = "Wait for the end of the job.",
        description = "Allowing to capture job status & logs."
    )
    @PluginProperty(dynamic = false)
    @Builder.Default
    Boolean wait = true;

    @Schema(
        title = "The max total wait duration."
    )
    @PluginProperty(dynamic = false)
    @Builder.Default
    Duration maxDuration = Duration.ofMinutes(60);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private transient Map<Integer, Integer> loggedLine = new HashMap<>();

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String connectorId = runContext.render(this.connectorId);

        // previous sync
        Connector previousConnector = fetchConnector(runContext);

        // create sync
        HttpResponse<SyncResponse> syncHttpResponse = this.request(
            runContext,
            HttpRequest
                .create(
                    HttpMethod.POST,
                    UriTemplate
                        .of("/v2/connectors/{connectorId}/sync")
                        .expand(Map.of("connectorId", connectorId))
                )
                .body(Map.of("force", this.force)),
            Argument.of(SyncResponse.class)
        );

        SyncResponse syncResponse = syncHttpResponse.getBody().orElseThrow(() -> new IllegalStateException("Missing body on trigger"));

        logger.info("Job status {} with response: {}", syncHttpResponse.getStatus(), syncResponse);

        if (!this.wait) {
            return null;
        }

        // wait for end
        Connector finalConnector = Await.until(
            throwSupplier(() -> {
                Connector current = fetchConnector(runContext);

                if (current.completedDate() != null && current.completedDate().compareTo(previousConnector.completedDate()) > 0) {
                    return current;
                }

                return null;
            }),
            Duration.ofSeconds(1),
            this.maxDuration
        );

        if (finalConnector.getFailedAt() != null) {
            throw new Exception("Connector '" + connectorId + "' failed: " + finalConnector);
        }

        return null;
    }

    private Connector fetchConnector(RunContext runContext) throws IllegalVariableEvaluationException {
        HttpResponse<ConnectorResponse> fetchConnector = this.request(
            runContext,
            HttpRequest
                .create(
                    HttpMethod.GET,
                    UriTemplate
                        .of("/v2/connectors/{connectorId}")
                        .expand(Map.of("connectorId", runContext.render(this.connectorId)))
                ),
            Argument.of(ConnectorResponse.class)
        );

        return fetchConnector.getBody().orElseThrow().getData();
    }
}
