package io.kestra.plugin.fivetran;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.DynamicTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.NettyHttpClientFactory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import javax.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFivetranConnection extends Task implements DynamicTask {
    @Schema(
        title = "API key"
    )
    @PluginProperty(dynamic = true)
    @NotNull
    String apiKey;

    @Schema(
        title = "API secret"
    )
    @PluginProperty(dynamic = true)
    @NotNull
    String apiSecret;

    private static final NettyHttpClientFactory FACTORY = new NettyHttpClientFactory();

    protected HttpClient client() throws IllegalVariableEvaluationException, MalformedURLException, URISyntaxException {
        return FACTORY.createClient(URI.create("https://api.fivetran.com").toURL(), new DefaultHttpClientConfiguration());
    }

    protected <REQ, RES> HttpResponse<RES> request(RunContext runContext, MutableHttpRequest<REQ> request, Argument<RES> argument) throws HttpClientResponseException {
        try {
            request = request
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/json;version=2")
                .basicAuth(runContext.render(this.apiKey), runContext.render(this.apiSecret));

            try (HttpClient client = this.client()) {
                return client.toBlocking().exchange(request, argument);
            }
        } catch (HttpClientResponseException e) {
            throw new HttpClientResponseException(
                "Request failed '" + e.getStatus().getCode() + "' and body '" + e.getResponse().getBody(String.class).orElse("null") + "'",
                e,
                e.getResponse()
            );
        } catch (IllegalVariableEvaluationException | MalformedURLException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
