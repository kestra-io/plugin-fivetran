package io.kestra.plugin.fivetran.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Whether a table's {@code enabled} flag can be changed. The free-text {@code reason} is deliberately
 * not mapped, so it can never reach an output.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnabledPatchSettings {
    @JsonProperty("allowed")
    Boolean allowed;
}
