package nuna.catalog.store.suite;

import nuna.catalog.store.SafetyFinding;
import java.util.List;
import java.util.function.Supplier;

/**
 * An unsafe durability setting a backend can stage. {@code selfCheck} applies the setting, runs
 * the self-check of a store that sees it, and reverts the setting.
 */
public record UnsafeSetting(String description, String findingCode, Supplier<List<SafetyFinding>> selfCheck) {
}
