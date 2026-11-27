package nuna.catalog.security;

import io.trino.sql.tree.DataType;
import io.trino.sql.tree.DereferenceExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.LambdaExpression;
import io.trino.sql.tree.Node;
import io.trino.sql.tree.Query;
import io.trino.sql.tree.SubqueryExpression;
import nuna.catalog.store.RowColumnRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The columns a table's row filters and column masks name, against its schema (D20, backlog 240). A mask is served
 * by its column's name (OpaAnswers), so a mask whose column the schema lacks masks nothing: a typo leaves the column
 * it meant unmasked, and so would a rename, Trino then asking for the new name. Two checks keep a rule and its table's
 * schema together:
 * <ul>
 *     <li>setting a rule: every mask names a column of the table's current schema, and is kept under the schema's
 *     spelling; every column the filter names is one of the schema's ({@link #check});</li>
 *     <li>a schema change: one that renames or drops a column a rule names is refused, naming the rule
 *     ({@link #taken}).</li>
 * </ul>
 * Names compare ignoring case, as Trino's do. Which names a filter means as columns is read from its tree, parsed by
 * Trino's parser ({@link RuleExpressions}, backlog 244): each identifier that is no part of a qualified name, a type,
 * a lambda's parameter or a subquery ({@link #columnsReferenced}). The schema-change check reads every identifier in
 * the filter's words, subqueries included ({@link #identifiers}), so it refuses at least every change the filter needs,
 * and reads a rule stored before rules were parsed as well as one stored after.
 */
public final class RuleColumns {

    /** The most names a message lists. */
    private static final int LISTED = 10;

    private RuleColumns() {
    }

    /** What {@link #check} found. */
    public sealed interface Checked {

        /** Every name is the schema's: the masks, keyed by the column's name as the schema spells it. */
        record Fits(SortedMap<String, String> masks) implements Checked {
        }

        /** What the rule names that the schema lacks, or names twice, in words. */
        record Unfit(String message) implements Checked {
        }
    }

    /**
     * A rule's filter and masks against the table's current schema.
     *
     * @param columns the current schema's top-level columns
     */
    public static Checked check(List<String> columns, Optional<String> filter, Map<String, String> masks) {
        SortedMap<String, String> kept = new TreeMap<>();
        List<String> lacking = new ArrayList<>();
        for (Map.Entry<String, String> mask : masks.entrySet()) {
            Optional<String> column = column(columns, mask.getKey());
            if (column.isEmpty()) {
                lacking.add(mask.getKey());
            } else if (kept.put(column.get(), mask.getValue()) != null) {
                return new Checked.Unfit("column-masks masks column " + column.get() + " twice");
            }
        }
        if (!lacking.isEmpty()) {
            return new Checked.Unfit("column-masks names " + listed(lacking) + ", which the table's current schema"
                    // The rule behind this message: D20.
                    + " does not have; a mask names a column of the table as it is now");
        }
        Set<String> referenced = new LinkedHashSet<>();
        if (filter.isPresent()) {
            switch (RuleExpressions.parse(filter.get(), "row-filter")) {
                case RuleExpressions.Parsed.Parses parses -> referenced.addAll(columnsReferenced(parses.expression()));
                case RuleExpressions.Parsed.Fails fails -> {
                    return new Checked.Unfit(fails.message());
                }
            }
        }
        Set<String> known = lowered(columns);
        List<String> unknown = referenced.stream().filter(name -> !known.contains(name)).toList();
        if (!unknown.isEmpty()) {
            return new Checked.Unfit("row-filter names " + listed(unknown) + ", which the table's current schema"
                    // The rule behind this message: D20.
                    + " does not have; a filter's columns are the table's as it is now");
        }
        return new Checked.Fits(kept);
    }

    /**
     * Whether a schema change takes away a column a rule names: a rename or a drop, as the current schema's columns
     * before and after it show.
     *
     * @return the refusal's words, naming each rule's group and the columns it names; empty when no rule names a
     *         column the change takes away
     */
    public static Optional<String> taken(List<String> before, List<String> after, Collection<RowColumnRule> rules) {
        Set<String> gone = lowered(before);
        gone.removeAll(lowered(after));
        if (gone.isEmpty() || rules.isEmpty()) {
            return Optional.empty();
        }
        List<String> each = new ArrayList<>();
        rules.stream().sorted(Comparator.comparing(RowColumnRule::group)).forEach(rule -> {
            Set<String> named = new LinkedHashSet<>(lowered(rule.columnMasks().keySet()));
            rule.rowFilter().ifPresent(filter -> named.addAll(identifiers(filter)));
            List<String> hit = named.stream().filter(gone::contains).sorted().toList();
            if (!hit.isEmpty()) {
                each.add("group " + rule.group() + "'s rule names " + listed(hit));
            }
        });
        if (each.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("The change renames or drops a column a row filter or column mask names: "
                + String.join("; ", each) + ". A rename would leave the column unmasked; the grant admin changes or"
                // The rule behind this message: D20, backlog 240.
                + " removes the rule first");
    }

    /** The schema's name for {@code name}: the column spelled so, else the one column that matches ignoring case. */
    private static Optional<String> column(List<String> columns, String name) {
        if (columns.contains(name)) {
            return Optional.of(name);
        }
        List<String> matching = columns.stream().filter(column -> column.equalsIgnoreCase(name)).toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }

    /**
     * Every identifier in {@code sql} outside string literals and comments, quoted or not, lowercased: every name it
     * could mean as a column, and more.
     */
    static Set<String> identifiers(String sql) {
        return tokens(sql).stream().filter(Token::identifier).map(Token::lowered)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The names {@code filter} means as the table's columns, lowercased, read from its tree: each identifier that is
     * not part of a qualified name ({@code e.desk}, or a row's field), a type, a lambda's parameter, or inside a
     * subquery, whose names are its own tables'. A function's name is no identifier in Trino's tree.
     */
    static Set<String> columnsReferenced(Expression filter) {
        Set<String> names = new LinkedHashSet<>();
        columns(filter, Set.of(), names);
        return names;
    }

    private static void columns(Node node, Set<String> parameters, Set<String> names) {
        switch (node) {
            case Identifier identifier -> {
                String name = identifier.getValue().toLowerCase(Locale.ROOT);
                if (!parameters.contains(name)) {
                    names.add(name);
                }
            }
            case DereferenceExpression dereference -> {
                if (!(dereference.getBase() instanceof Identifier)
                        && !(dereference.getBase() instanceof DereferenceExpression)) {
                    columns(dereference.getBase(), parameters, names);
                }
            }
            case LambdaExpression lambda -> {
                Set<String> inner = new HashSet<>(parameters);
                lambda.getArguments().forEach(argument -> inner.add(argument.getName().getValue()
                        .toLowerCase(Locale.ROOT)));
                columns(lambda.getBody(), inner, names);
            }
            case SubqueryExpression subquery -> {
            }
            case Query query -> {
            }
            case DataType type -> {
            }
            default -> node.getChildren().forEach(child -> columns(child, parameters, names));
        }
    }

    /** A word of an SQL expression: an identifier, quoted or bare; a string literal; or any other symbol. */
    private record Token(Kind kind, String text) {

        enum Kind { BARE, QUOTED, LITERAL, SYMBOL }

        boolean identifier() {
            return kind == Kind.BARE || kind == Kind.QUOTED;
        }

        String lowered() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    private static List<Token> tokens(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'' || c == '"') {
                StringBuilder text = new StringBuilder();
                i++;
                while (i < n) {
                    if (sql.charAt(i) == c) {
                        if (i + 1 < n && sql.charAt(i + 1) == c) {
                            text.append(c);
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    text.append(sql.charAt(i++));
                }
                tokens.add(new Token(c == '"' ? Token.Kind.QUOTED : Token.Kind.LITERAL, text.toString()));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'
                        || sql.charAt(i) == '$' || sql.charAt(i) == '@')) {
                    i++;
                }
                tokens.add(new Token(Token.Kind.BARE, sql.substring(start, i)));
            } else if (Character.isDigit(c)) {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '>') {
                tokens.add(new Token(Token.Kind.SYMBOL, "->"));
                i += 2;
            } else {
                tokens.add(new Token(Token.Kind.SYMBOL, String.valueOf(c)));
                i++;
            }
        }
        return tokens;
    }

    private static Set<String> lowered(Collection<String> names) {
        return names.stream().map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static String listed(List<String> names) {
        List<String> shown = names.stream().limit(LISTED).toList();
        String list = String.join(", ", shown);
        return names.size() > LISTED ? list + " and " + (names.size() - LISTED) + " more" : list;
    }
}
