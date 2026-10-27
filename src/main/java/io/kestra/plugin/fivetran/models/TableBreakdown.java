package io.kestra.plugin.fivetran.models;

import java.net.URI;

import lombok.Value;

@Value
public class TableBreakdown {
    URI uri;
    int tableCount;
    int enabledTableCount;
}
