package nuna.catalog.security;

import io.trino.sql.parser.ParsingException;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.Expression;

/**
 * A row filter or column mask parsed as Trino parses it when it applies the rule (D20, backlog 244): with Trino's own
 * parser, {@code io.trino:trino-parser}, and its rule for a standalone expression, the one Trino's analyzer uses for a
 * row filter and a column mask. One that does not parse is refused when it is set, saying where, rather than stored to
 * fail every query of its group. Subqueries and references to other tables parse, and are allowed (D20, amended
 * 2026-11-24): Trino runs them as the querying user, so every table they read goes through nuna's authorization for
 * that user. Only the syntax is checked here; names, types and functions are Trino's to resolve.
 */
public final class RuleExpressions {

    /** Thread-safe: each call makes its own lexer and parser. */
    private static final SqlParser PARSER = new SqlParser();

    private RuleExpressions() {
    }

    /** What {@link #parse} found. */
    public sealed interface Parsed {

        /** The expression's tree. */
        record Parses(Expression expression) implements Parsed {
        }

        /** Where and why it does not parse, in words. */
        record Fails(String message) implements Parsed {
        }
    }

    /**
     * Parses {@code sql} as one Trino SQL expression.
     *
     * @param what what the expression is, to open the message with: {@code row-filter}, or {@code The mask of x}
     */
    public static Parsed parse(String sql, String what) {
        try {
            return new Parsed.Parses(PARSER.createExpression(sql));
        } catch (ParsingException e) {
            return new Parsed.Fails(what + " is not a Trino SQL expression: at line " + e.getLineNumber() + ", column "
                    + e.getColumnNumber() + ", " + e.getErrorMessage());
        }
    }
}
