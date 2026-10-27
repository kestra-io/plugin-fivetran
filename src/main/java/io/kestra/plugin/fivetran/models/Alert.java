package io.kestra.plugin.fivetran.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class Alert {
    String code;
    String message;
}
