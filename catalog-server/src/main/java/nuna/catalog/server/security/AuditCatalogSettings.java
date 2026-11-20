package nuna.catalog.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The audit catalog's two groups ({@code nuna.audit.tables.*}, D23, amended 2026-11-20): the catalog the queryable
 * audit tables live in, the group that reads them and the audit table writer's group. At startup the server grants
 * the readers {@code select} on the catalog and the writer's group {@code create} and {@code write} there
 * ({@link AuditCatalogGrants}). Unset, it grants nothing; a group left unset gets nothing.
 *
 * @param catalog the audit catalog, declared in {@code nuna.storage.catalogs}, e.g. {@code nuna_audit}
 * @param readers the group that reads the audit tables; rows are not filtered, so it sees every object and person in
 *                the deployment's records and must be narrow
 * @param writers the audit table writer's group ({@code catalog-audit-tables}), which should hold nothing elsewhere
 */
@ConfigurationProperties("nuna.audit.tables")
public record AuditCatalogSettings(String catalog, String readers, String writers) {
}
