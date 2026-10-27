package io.kestra.plugin.fivetran.models;

import java.net.URI;

import lombok.Value;

/**
 * Result of writing a connector's per-table breakdown to internal storage.
 */
@Value
public class TableBreakdown {
    URI uri;
    int tableCount;
    int enabledTableCount;
}
