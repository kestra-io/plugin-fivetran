package io.kestra.plugin.fivetran.models;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class Connector {
    @JsonProperty("id")
    String id;

    @JsonProperty("name")
    String name;

    @JsonProperty("schema")
    String schema;

    @JsonProperty("destination_schema")
    DestinationSchema destinationSchema;

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
    public Map<String, Object> getProperties() {
        return properties != null ? properties : new HashMap<>();
    }

    @JsonAnySetter
    public void addProperties(String property, Object value) {
        properties.put(property, value);
    }

    public ZonedDateTime completedDate() {
        return this.getSucceededAt() != null && (this.getFailedAt() == null ||
            this.getSucceededAt().compareTo(this.getFailedAt()) > 0) ? this.getSucceededAt() : this.getFailedAt();
    }

    // Fivetran never clears failed_at after a later success, so a non-null failed_at alone doesn't mean the
    // last sync failed: only treat it as a failure when no later success happened after it.
    public boolean hasFailed() {
        return this.getFailedAt() != null &&
            (this.getSucceededAt() == null || !this.getSucceededAt().isAfter(this.getFailedAt()));
    }

    // Prefer the structured, stable destination_schema.name. Fall back to the legacy top-level `schema`
    // (older API), and only then to the editable display name as a last resort.
    public String destinationSchemaName() {
        if (this.destinationSchema != null && this.destinationSchema.getName() != null) {
            return this.destinationSchema.getName();
        }
        if (this.schema != null) {
            return this.schema;
        }
        return this.name;
    }
}
