package io.kestra.plugin.fivetran.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * One table inside a connector's schema config. Only the destination name and the enabled flag are
 * mapped: the endpoint also returns every column, which is never needed to compose an asset id.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaTable {
    @JsonProperty("name_in_destination")
    String nameInDestination;

    @JsonProperty("enabled")
    Boolean enabled;

    /**
     * The table name as written in the destination. Falls back to the source-side key when Fivetran
     * reports no rename, which is the common case for connectors that do not remap names.
     */
    public String destinationName(String sourceName) {
        return this.nameInDestination != null && !this.nameInDestination.isBlank()
            ? this.nameInDestination
            : sourceName;
    }

    // Fivetran omits `enabled` on some connector types; an omitted flag means the table is synced.
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(this.enabled);
    }
}
