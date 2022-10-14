package io.kestra.plugin.fivetran.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class SetupTestResultResponse   {
  @JsonProperty("title")
  String title = null;

  @JsonProperty("status")
  String status = null;

  @JsonProperty("message")
  String message = null;

  @JsonProperty("details")
  Object details = null;
}
