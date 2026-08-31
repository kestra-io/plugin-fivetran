package io.kestra.plugin.fivetran.models;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * One schema inside a connector's schema config. A single-schema source (Salesforce) reports one entry,
 * a multi-schema source (Postgres) reports one per source schema.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectorSchema {
    @JsonProperty("name_in_destination")
    String nameInDestination;

    @JsonProperty("enabled")
    Boolean enabled;

    @JsonProperty("tables")
    Map<String, SchemaTable> tables;

    /**
     * The schema name as written in the destination. Falls back to the source-side key when Fivetran
     * reports no rename.
     */
    public String destinationName(String sourceName) {
        return this.nameInDestination != null && !this.nameInDestination.isBlank()
            ? this.nameInDestination
            : sourceName;
    }

    // Fivetran omits `enabled` on some connector types; an omitted flag means the schema is synced.
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(this.enabled);
    }
}
