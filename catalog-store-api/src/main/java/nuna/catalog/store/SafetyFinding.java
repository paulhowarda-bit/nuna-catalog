package nuna.catalog.store;

import java.util.Objects;

/**
 * A durability setting that is unsafe for the catalog (D8.2), e.g. {@code pg.sync-commit-off}.
 * Codes are listed in docs/storage-design.md section 7.
 */
public record SafetyFinding(String code, String detail) {

    public SafetyFinding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
    }
}
