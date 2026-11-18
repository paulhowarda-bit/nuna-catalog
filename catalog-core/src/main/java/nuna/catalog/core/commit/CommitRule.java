package nuna.catalog.core.commit;

import nuna.catalog.iceberg.Facts;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.GovernanceAttribute;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One enterprise policy check, run after the builder has applied a request and before the metadata
 * file is written (D11). A rejection is a 400 or 403, never a 409: retrying cannot change the answer.
 */
@FunctionalInterface
public interface CommitRule {

    Optional<Rejection> judge(Change change);

    /**
     * The change being judged.
     *
     * @param object     the object as the catalog records it, with its assigned location root
     * @param before     its metadata before the change; null when the change creates it
     * @param after      its metadata after the change
     * @param now        the catalog's clock
     * @param governance holds and the regulated flag on the object and everything above it, read on demand
     */
    record Change(CatalogObject object, Facts before, Facts after, Instant now, Governance governance) {
        public Change {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(after, "after");
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(governance, "governance");
        }
    }

    /** Governance records on the object, its namespaces and its catalog. */
    interface Governance {
        /** A legal hold is placed on the object or anything above it (D24). */
        boolean held();

        /** The object or anything above it carries the regulated flag (D11a, D26). */
        boolean regulated();

        /**
         * The attribute of {@code kind} on the nearest level that sets one: the object, then its
         * namespaces from the innermost out, then its catalog.
         */
        Optional<GovernanceAttribute> nearest(GovernanceAttribute.Kind kind);
    }

    /** @param type the Iceberg error class name */
    record Rejection(int status, String type, String message) {

        public static Rejection badRequest(String message) {
            return new Rejection(400, "BadRequestException", message);
        }

        public static Rejection forbidden(String message) {
            return new Rejection(403, "ForbiddenException", message);
        }
    }
}
