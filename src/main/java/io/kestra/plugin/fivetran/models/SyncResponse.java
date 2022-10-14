package io.kestra.plugin.fivetran.models;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class SyncResponse {
    String code;
    String message;
}
