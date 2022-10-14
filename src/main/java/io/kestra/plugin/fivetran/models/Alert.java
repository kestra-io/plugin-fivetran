package io.kestra.plugin.fivetran.models;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class Alert {
    String code = null;
    String message = null;
}
