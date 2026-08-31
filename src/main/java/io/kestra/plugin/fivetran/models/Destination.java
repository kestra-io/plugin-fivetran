package io.kestra.plugin.fivetran.models;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class Destination {
    @JsonProperty("id")
    String id;

    @JsonProperty("group_id")
    String groupId;

    @JsonProperty("service")
    String service;

    // Destination config carries credentials (password, private_key, secret_key) that Fivetran does not
    // always mask, so it is kept out of toString and never logged. Only databaseName() reads it.
    @JsonProperty("config")
    @ToString.Exclude
    Map<String, Object> config;

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

    /**
     * The warehouse-side database an asset id must be prefixed with, so a Fivetran-loaded table and the
     * dbt model reading it resolve to the same {@code database.schema.name}. Fivetran names this field
     * per destination service, so all three spellings are checked: `database` on Snowflake, Redshift and
     * the SQL destinations, `catalog` on Databricks, `project_id` on BigQuery. Null when the destination
     * reports none of them, in which case no table-grain id can be composed.
     */
    public String databaseName() {
        if (this.config == null) {
            return null;
        }

        for (String key : new String[] { "database", "catalog", "project_id" }) {
            Object value = this.config.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }

        return null;
    }
}
