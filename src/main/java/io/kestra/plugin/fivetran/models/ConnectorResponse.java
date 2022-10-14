package io.kestra.plugin.fivetran.models;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class ConnectorResponse {
    String code;
    String message;
    Connector data;
}
