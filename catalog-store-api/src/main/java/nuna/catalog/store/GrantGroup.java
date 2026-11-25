package nuna.catalog.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A group that holds at least one grant in a catalog, with every privilege it holds anywhere there, by wire name:
 * each once, sorted.
 */
public record GrantGroup(String group, List<String> privileges) {

    public GrantGroup {
        privileges = privileges.stream().distinct().sorted().toList();
    }

    /** One per group of {@code rows}, each a group and a privilege it holds, in the order the groups first come. */
    public static List<GrantGroup> byGroup(List<Map.Entry<String, String>> rows) {
        Map<String, List<String>> held = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows) {
            held.computeIfAbsent(row.getKey(), group -> new ArrayList<>()).add(row.getValue());
        }
        return held.entrySet().stream().map(entry -> new GrantGroup(entry.getKey(), entry.getValue())).toList();
    }
}
