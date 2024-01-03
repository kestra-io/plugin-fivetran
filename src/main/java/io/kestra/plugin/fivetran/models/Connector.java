package io.kestra.plugin.fivetran.models;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;

@Value
@Jacksonized
@SuperBuilder
public class Connector {
    @JsonProperty("id")
    String id;

    @JsonProperty("name")
    String name;

    @JsonProperty("paused")
    Boolean paused;

    @JsonProperty("version")
    Integer version;

    @JsonProperty("status")
    ConnectorStatusResponse status;

    @JsonProperty("daily_sync_time")
    String dailySyncTime;

    @JsonProperty("succeeded_at")
    ZonedDateTime succeededAt;

    @JsonProperty("connector_type_id")
    String connectorTypeId;

    @JsonProperty("sync_frequency")
    Integer syncFrequency;

    @JsonProperty("pause_after_trial")
    Boolean pauseAfterTrial;

    @JsonProperty("group_id")
    String groupId;

    @JsonProperty("connected_by")
    String connectedBy;

    @JsonProperty("setup_tests")
    @Valid
    List<SetupTestResultResponse> setupTests;

    @JsonProperty("source_sync_details")
    Object sourceSyncDetails;

    @JsonProperty("created_at")
    ZonedDateTime createdAt;

    @JsonProperty("failed_at")
    ZonedDateTime failedAt;

    @JsonProperty("schedule_type")
    String scheduleType;

    @Builder.Default
    Map<String, Object> properties = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getProperties(){
        return properties != null ? properties : new HashMap<>();
    }

    @JsonAnySetter
    public void addProperties(String property, Object value){
        properties.put(property, value);
    }

    public ZonedDateTime completedDate() {
        return this.getSucceededAt() != null && (
            this.getFailedAt() == null ||
            this.getSucceededAt().compareTo(this.getFailedAt()) > 0
        ) ?
            this.getSucceededAt() :
            this.getFailedAt();
    }
}
