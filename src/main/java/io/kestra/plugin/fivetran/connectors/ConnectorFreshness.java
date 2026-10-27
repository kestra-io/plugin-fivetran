package io.kestra.plugin.fivetran.connectors;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.kestra.plugin.fivetran.models.Alert;
import io.kestra.plugin.fivetran.models.Connector;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ConnectorFreshness {
    @Schema(
        title = "Current sync state",
        description = "As reported by Fivetran, e.g. `scheduled`, `syncing`, `paused`, `rescheduled`."
    )
    String syncState;

    @Schema(
        title = "Current setup state",
        description = "As reported by Fivetran, e.g. `connected`, `broken`, `incomplete`."
    )
    String setupState;

    @Schema(
        title = "Current update state",
        description = "As reported by Fivetran, e.g. `on_schedule`, `delayed`."
    )
    String updateState;

    @Schema(
        title = "Whether the connector is paused"
    )
    Boolean paused;

    @Schema(
        title = "Timestamp of the last successful sync",
        description = """
            The connector's last known success, taken from the connector snapshot. With `wait: false` it predates the sync this task triggered; use the top-level `succeededAt` for the sync the task itself observed.
            """
    )
    ZonedDateTime succeededAt;

    @Schema(
        title = "Timestamp of the last failed sync"
    )
    ZonedDateTime failedAt;

    @Schema(
        title = "Sync frequency in minutes"
    )
    Integer syncFrequency;

    @Schema(
        title = "Whether the connector's data is fresh",
        description = """
            True when `wait` is true, since a sync that just succeeded is fresh by definition.
            Null when `wait` is false, because the sync is still running.
            """
    )
    Boolean fresh;

    @Schema(
        title = "Number of alerts",
        description = "Size of the connector's `status.warnings` plus `status.tasks`."
    )
    int alertCount;

    @Schema(
        title = "Alert codes",
        description = """
            De-duplicated codes of the connector's warnings and tasks.
            Alert messages are left out to keep the output bounded.
            """
    )
    List<String> alertCodes;

    static ConnectorFreshness from(Connector connector, Boolean fresh) {
        var status = connector.getStatus();

        return ConnectorFreshness.builder()
            .syncState(status != null ? status.getSyncState() : null)
            .setupState(status != null ? status.getSetupState() : null)
            .updateState(updateState(connector))
            .paused(connector.getPaused())
            .succeededAt(connector.getSucceededAt())
            .failedAt(connector.getFailedAt())
            .syncFrequency(connector.getSyncFrequency())
            .fresh(fresh)
            .alertCount(alertCount(connector))
            .alertCodes(alertCodes(connector))
            .build();
    }

    static String updateState(Connector connector) {
        var status = connector.getStatus();
        return status != null ? status.getUpdateState() : null;
    }

    static int alertCount(Connector connector) {
        return alerts(connector).size();
    }

    static List<String> alertCodes(Connector connector) {
        return alerts(connector).stream().map(Alert::getCode).filter(Objects::nonNull).distinct().toList();
    }

    private static List<Alert> alerts(Connector connector) {
        var alerts = new ArrayList<Alert>();
        var status = connector.getStatus();
        if (status != null) {
            if (status.getWarnings() != null) {
                alerts.addAll(status.getWarnings());
            }
            if (status.getTasks() != null) {
                alerts.addAll(status.getTasks());
            }
        }
        return alerts;
    }
}
