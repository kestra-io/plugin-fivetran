package io.kestra.plugin.fivetran;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import org.apache.hc.core5.http.Method;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.RetryUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFivetranConnection extends Task {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR_MIN = 500;
    private static final int SERVER_ERROR_MAX = 599;
    // Upper bound on how far to walk the exception cause chain, so a cyclic chain cannot loop forever.
    private static final int MAX_CAUSE_CHAIN_DEPTH = 16;

    @Schema(
        title = "Fivetran API key",
        description = "Required; paired with `apiSecret` for HTTP Basic authentication."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    Property<String> apiKey;

    @Schema(
        title = "Fivetran API secret",
        description = "Required secret token used with `apiKey` for Basic authentication."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
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
        description = "Optional Kestra HTTP configuration (timeouts, proxy) applied to Fivetran calls. Retries are configured separately via `maxAttempts` and `initialRetryDelay`."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    @Schema(
        title = "Maximum number of attempts on transient errors",
        description = "Total attempts including the first call, not just retries. Default: 3."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<@Min(1) Integer> maxAttempts = Property.ofValue(3);

    @Schema(
        title = "Initial delay before the first retry",
        description = "Backoff grows from this delay, capped at 30 seconds. Default: 1 second."
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

        HttpConfiguration httpConfiguration = builder.build();

        var rMaxAttempts = runContext.render(this.maxAttempts).as(Integer.class).orElseThrow();
        Duration rInitialRetryDelay = runContext.render(this.initialRetryDelay).as(Duration.class).orElseThrow();
        if (rInitialRetryDelay.isNegative() || rInitialRetryDelay.isZero()) {
            throw new IllegalArgumentException("initialRetryDelay must be a positive duration, but was " + rInitialRetryDelay);
        }

        try (HttpClient client = new HttpClient(runContext, httpConfiguration)) {
            return RetryUtils.<HttpResponse<RES>, HttpClientException> of(
                Exponential.builder()
                    .delayFactor(2.0)
                    .interval(rInitialRetryDelay)
                    .maxInterval(Duration.ofSeconds(30))
                    .maxAttempts(rMaxAttempts)
                    .build(),
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
}
