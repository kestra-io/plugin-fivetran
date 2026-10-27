package io.kestra.plugin.fivetran;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a database source keeps its tables, so the asset id of a source table matches the one the JDBC and
 * dbt plugins emit for it. Only the location key is read from the connector config, and never logged.
 */
final class SourceLocation {
    // Fivetran masks secrets with asterisks, which must never end up in an id.
    private static final Pattern MASKED = Pattern.compile("\\*+");

    // Services whose config names the database; Fivetran schemas are the source schemas.
    private static final Set<String> DATABASE_SERVICES = Set.of(
        "postgres", "postgres_rds", "aurora_postgres", "google_cloud_postgresql", "azure_postgres", "heroku_postgres",
        "sql_server", "sql_server_rds", "sql_server_hva", "azure_sql_db", "azure_sql_managed_db", "google_cloud_sqlserver",
        "oracle", "oracle_rds", "oracle_rac", "oracle_hva",
        "snowflake_db", "redshift_db"
    );
    private static final Set<String> PROJECT_SERVICES = Set.of("bigquery_db");
    // MySQL has no schema level: the Fivetran schema key is the MySQL database.
    private static final Set<String> SCHEMALESS_SERVICES = Set.of(
        "mysql", "mysql_rds", "aurora", "google_cloud_mysql", "mysql_azure", "magento_mysql", "magento_mysql_rds", "maria", "maria_rds", "maria_azure"
    );

    private SourceLocation() {
    }

    /**
     * @return {@code Optional.empty()} when the service is not a known database source; the database is
     *         {@code null} for MySQL variants, which need none.
     */
    static Optional<Resolved> resolve(String service, Map<String, Object> config) {
        if (service == null) {
            return Optional.empty();
        }
        if (SCHEMALESS_SERVICES.contains(service)) {
            return Optional.of(new Resolved(null));
        }

        var key = DATABASE_SERVICES.contains(service) ? "database" : PROJECT_SERVICES.contains(service) ? "project_id" : null;
        if (key == null || config == null || !(config.get(key) instanceof String value)) {
            return Optional.empty();
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() || MASKED.matcher(trimmed).matches() ? Optional.empty() : Optional.of(new Resolved(trimmed));
    }

    record Resolved(String database) {
    }
}
