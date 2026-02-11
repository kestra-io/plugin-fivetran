package io.kestra.plugin.fivetran;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFivetranConnection extends Task {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    @Schema(
        title = "Fivetran API key",
        description = "Required; paired with `apiSecret` for HTTP Basic authentication."
    )
    @NotNull
    Property<String> apiKey;

    @Schema(
        title = "Fivetran API secret",
        description = "Required secret token used with `apiKey` for Basic authentication."
    )
    @NotNull
    Property<String> apiSecret;

    @Schema(
        title = "Fivetran API base URL",
        description = "Base endpoint for all requests. Defaults to `https://api.fivetran.com`; override for regional or private deployments."
    )
    @NotNull
    @Builder.Default
    Property<String> baseUrl = Property.ofValue("https://api.fivetran.com");

    @Schema(
        title = "HTTP client options",
        description = "Optional Kestra HTTP configuration (timeouts, proxy, retries) applied to Fivetran calls."
    )
    protected HttpConfiguration options;

    /**
     * @param requestBuilder The prepared HTTP request builder.
     * @param responseType   The expected response type.
     * @param <RES>          The response class.
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

        try (HttpClient client = new HttpClient(runContext, httpConfiguration)) {
            HttpResponse<String> response = client.request(request, String.class);

            RES parsedResponse = MAPPER.readValue(response.getBody(), responseType);
            return HttpResponse.<RES>builder()
                .request(request)
                .body(parsedResponse)
                .headers(response.getHeaders())
                .status(response.getStatus())
                .build();

        } catch (IOException e) {
            throw new RuntimeException("Error executing HTTP request", e);
        }
    }
}
