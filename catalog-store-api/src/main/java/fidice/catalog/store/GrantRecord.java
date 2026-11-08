package fidice.catalog.store;

import java.time.Instant;

/** A stored grant: who granted it and when. */
public record GrantRecord(Grant grant, String grantedBy, Instant grantedAt) {
}
