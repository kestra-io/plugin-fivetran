package io.kestra.plugin.fivetran;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;

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

    @Schema(title = "API key")
    @NotNull
    Property<String> apiKey;

    @Schema(title = "API secret")
    @NotNull
    Property<String> apiSecret;

    @Schema(title = "The base URL of the Fivetran API.")
    @NotNull
    Property<String> baseUrl = Property.of("https://api.fivetran.com");

    @Schema(title = "The HTTP client configuration.")
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
            .addHeader("Authorization", "Basic " +
                runContext.render(this.apiKey).as(String.class).orElseThrow() + ":" +
                runContext.render(this.apiSecret).as(String.class).orElseThrow()
            )
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json;version=2")
            .build();

        try (HttpClient client = new HttpClient(runContext, options)) {
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
