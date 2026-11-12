package keel.catalog.server.rest;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the REST surface ({@code keel.rest.*}).
 *
 * @param defaultPageSize         listing page size when the client names none
 * @param maxPageSize             the largest page served; larger requests get this many
 * @param alertMetadataChars      a loaded metadata file larger than this is alerted, once per file (D14)
 * @param alertSnapshots          a loaded table with more snapshots than this is alerted, once per file (D14)
 * @param metadataCacheChars      budget of each in-memory metadata cache, in characters of JSON (D14)
 * @param dropRetention           how long a dropped table or view stays undroppable before purge (D25)
 * @param credentialRefreshMargin no 304 when vended credentials expire within this (D14); the Java
 *                                client keeps a loaded table for 5 minutes
 * @param identityProvider        named in the {@code /v1/oauth/tokens} error: where tokens come from (D13)
 * @param auditSource             the CloudEvents {@code source} of this deployment's audit events
 * @param maxTransactionTables    the most tables one {@code /transactions/commit} may change; its store
 *                                transaction locks that many pointers (D15, P10)
 */
@ConfigurationProperties("keel.rest")
public record RestSettings(
        @DefaultValue("100") int defaultPageSize,
        @DefaultValue("1000") int maxPageSize,
        @DefaultValue("1048576") long alertMetadataChars,
        @DefaultValue("1000") int alertSnapshots,
        @DefaultValue("268435456") long metadataCacheChars,
        @DefaultValue("30d") Duration dropRetention,
        @DefaultValue("5m") Duration credentialRefreshMargin,
        @DefaultValue("the organisation's identity provider") String identityProvider,
        @DefaultValue("urn:keel:deployment:keel-catalog") URI auditSource,
        @DefaultValue("100") int maxTransactionTables) {

    public RestSettings {
        if (defaultPageSize < 1 || maxPageSize < defaultPageSize) {
            throw new IllegalArgumentException("need 1 <= default-page-size <= max-page-size, got "
                    + defaultPageSize + " and " + maxPageSize);
        }
        if (maxTransactionTables < 1) {
            throw new IllegalArgumentException("max-transaction-tables must be at least 1, got " + maxTransactionTables);
        }
    }
}
