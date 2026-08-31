package io.kestra.plugin.fivetran.models;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class ConnectorSchemasResponse {
    String code;
    String message;
    Data data;

    @Value
    @Jacksonized
    @SuperBuilder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("schemas")
        Map<String, ConnectorSchema> schemas;
    }
}
