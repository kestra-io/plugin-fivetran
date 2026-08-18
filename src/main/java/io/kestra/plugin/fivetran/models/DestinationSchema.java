package io.kestra.plugin.fivetran.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class DestinationSchema {
    @JsonProperty("name")
    String name;

    @JsonProperty("table")
    String table;

    @JsonProperty("table_group_name")
    String tableGroupName;
}
