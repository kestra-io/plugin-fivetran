package io.kestra.plugin.fivetran.models;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ConnectorTest {
    // Fivetran keeps failed_at as the timestamp of the most recent failure ever, it is never cleared on a later success.
    @Test
    @DisplayName("Should not be considered failed when succeeded_at is more recent than a stale failed_at")
    void staleFailedAtDoesNotFailASucceededSync() {
        Connector connector = Connector.builder()
            .succeededAt(ZonedDateTime.parse("2026-07-27T13:13:08.389Z"))
            .failedAt(ZonedDateTime.parse("2026-04-28T03:06:48.382Z"))
            .build();

        assertThat(connector.hasFailed(), is(false));
    }

    @Test
    @DisplayName("Should be considered failed when failed_at is more recent than succeeded_at")
    void mostRecentFailureFailsTheSync() {
        Connector connector = Connector.builder()
            .succeededAt(ZonedDateTime.parse("2026-04-28T03:06:48.382Z"))
            .failedAt(ZonedDateTime.parse("2026-07-27T13:13:08.389Z"))
            .build();

        assertThat(connector.hasFailed(), is(true));
    }

    @Test
    @DisplayName("Should be considered failed when the connector never succeeded")
    void neverSucceededFailsTheSync() {
        Connector connector = Connector.builder()
            .succeededAt(null)
            .failedAt(ZonedDateTime.parse("2026-04-28T03:06:48.382Z"))
            .build();

        assertThat(connector.hasFailed(), is(true));
    }

    @Test
    @DisplayName("Should be considered failed when succeeded_at and failed_at are equal")
    void equalTimestampsFailTheSync() {
        ZonedDateTime sameInstant = ZonedDateTime.parse("2026-07-27T13:13:08.389Z");
        Connector connector = Connector.builder()
            .succeededAt(sameInstant)
            .failedAt(sameInstant)
            .build();

        assertThat(connector.hasFailed(), is(true));
    }
}
