package io.kestra.plugin.fivetran.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Value
@Jacksonized
@SuperBuilder
public class ConnectorStatusResponse {
    @JsonProperty("tasks")
    List<Alert> tasks;

    @JsonProperty("warnings")
    List<Alert> warnings;

    @JsonProperty("schema_status")
    String schemaStatus;

    @JsonProperty("update_state")
    String updateState;

    @JsonProperty("setup_state")
    String setupState;

    @JsonProperty("sync_state")
    String syncState;

    @JsonProperty("is_historical_sync")
    Boolean isHistoricalSync;
}
