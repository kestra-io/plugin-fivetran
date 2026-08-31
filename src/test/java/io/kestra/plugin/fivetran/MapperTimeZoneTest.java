package io.kestra.plugin.fivetran;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kestra.plugin.fivetran.models.ConnectorResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Fivetran reports timestamps in UTC and they are surfaced verbatim in task output, so the shared mapper
 * must not rewrite them into the worker's local zone. Core's JSON mapper adjusts dates to the context time
 * zone by default, which would silently shift every succeededAt/failedAt a flow already reads.
 */
class MapperTimeZoneTest {
    @Test
    @DisplayName("Should keep a UTC timestamp in UTC rather than shifting it to the worker's zone")
    void deserializationPreservesTheReportedOffset() throws Exception {
        String body = """
            {
              "code": "Success",
              "data": {
                "id": "some_connector",
                "succeeded_at": "2026-07-27T13:13:08.389Z",
                "group_id": "some_group"
              }
            }
            """;

        ConnectorResponse response = AbstractFivetranConnection.mapper().readValue(body, ConnectorResponse.class);
        ZonedDateTime succeededAt = response.getData().getSucceededAt();

        assertThat(succeededAt.toInstant(), is(ZonedDateTime.parse("2026-07-27T13:13:08.389Z").toInstant()));
        assertThat(succeededAt.getOffset(), is(ZoneOffset.UTC));
    }
}
