package io.kestra.plugin.fivetran.connectors;

import com.google.common.collect.ImmutableMap;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@Tag("unit")
class SyncInvalidApiKeyTest {
    @Inject
    private RunContextFactory runContextFactory;

    private static final String INVALID_API_KEY = "invalid_api_key";
    private static final String INVALID_API_SECRET = "invalid_api_secret";
    private static final String INVALID_CONNECTOR_ID = "invalid_connector_id";
    private static final String BASE_URL = "https://api.fivetran.com";

    @Test
    @DisplayName("Should fail when using an invalid API key")
    void runInvalidApiKey() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
            .apiKey(Property.ofValue(INVALID_API_KEY))
            .apiSecret(Property.ofValue(INVALID_API_SECRET))
            .connectorId(Property.ofValue(INVALID_CONNECTOR_ID))
            .baseUrl(Property.ofValue(BASE_URL))
            .build();

        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> task.run(runContext),
            "Expected Sync to fail with HttpClientResponseException"
        );

        assertEquals(401, Objects.requireNonNull(exception.getResponse()).getStatus().getCode());
    }
}
