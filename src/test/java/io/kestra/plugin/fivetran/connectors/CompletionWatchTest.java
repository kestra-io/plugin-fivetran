package io.kestra.plugin.fivetran.connectors;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.kestra.plugin.fivetran.models.Connector;
import io.kestra.plugin.fivetran.models.ConnectorStatusResponse;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionWatchTest {
    private static final ZonedDateTime BEFORE = ZonedDateTime.parse("2026-10-28T09:00:00Z");
    private static final ZonedDateTime FIRST = ZonedDateTime.parse("2026-10-28T09:14:01Z");
    private static final ZonedDateTime FINAL = ZonedDateTime.parse("2026-10-28T09:14:12Z");
    private static final Duration GRACE = Duration.ofMinutes(1);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T09:14:05Z"));

    private Sync.CompletionWatch watch(boolean queued) {
        return new Sync.CompletionWatch(BEFORE, queued, GRACE, now::get);
    }

    private static Connector connector(ZonedDateTime succeededAt, String syncState) {
        return Connector.builder()
            .succeededAt(succeededAt)
            .status(ConnectorStatusResponse.builder().syncState(syncState).build())
            .build();
    }

    @Test
    void accepts_a_new_completion_once_syncing_has_ended() {
        var done = connector(FINAL, "scheduled");

        assertSame(done, watch(false).accept(done));
    }

    @Test
    void ignores_the_completion_from_before_the_trigger() {
        assertNull(watch(false).accept(connector(BEFORE, "scheduled")));
    }

    @Test
    void waits_while_syncing_then_accepts_when_syncing_ends() {
        var watch = watch(false);

        assertNull(watch.accept(connector(FIRST, "syncing")));
        now.set(now.get().plusSeconds(11));
        var done = connector(FINAL, "scheduled");
        assertSame(done, watch.accept(done));
    }

    @Test
    void accepts_a_completion_still_syncing_once_the_grace_period_is_over() {
        var watch = watch(false);
        var syncing = connector(FIRST, "syncing");

        assertNull(watch.accept(syncing));
        now.set(now.get().plus(GRACE).minusSeconds(1));
        assertNull(watch.accept(syncing));
        now.set(now.get().plusSeconds(1));
        // The next sync keeps the connector syncing: the task returns after the grace period, not at maxDuration.
        assertSame(syncing, watch.accept(syncing));
    }

    @Test
    void keeps_the_completion_seen_while_syncing_for_a_timeout() {
        var watch = watch(false);
        var syncing = connector(FIRST, "syncing");

        watch.accept(syncing);

        assertSame(syncing, watch.candidate().orElseThrow());
    }

    @Test
    void skips_the_earlier_sync_completion_when_the_trigger_was_queued() {
        var watch = watch(true);

        // The running sync completes and our queued one starts: not ours, and no grace period starts for it.
        assertNull(watch.accept(connector(FIRST, "syncing")));
        assertTrue(watch.candidate().isEmpty());
        now.set(now.get().plus(GRACE).plusSeconds(1));
        assertNull(watch.accept(connector(FIRST, "syncing")));

        var done = connector(FINAL, "scheduled");
        assertSame(done, watch.accept(done));
    }
}
