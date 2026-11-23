package nuna.catalog.store.sql;

import static org.assertj.core.api.Assertions.assertThat;

import nuna.catalog.store.Ref;
import nuna.catalog.store.RefEntry;
import nuna.catalog.store.RefPointers;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A row of {@code SqlRefs.listPointers} read into a ref's pointers for one object (D33, item 105's store read for item
 * 108). The head, a branch's own pointer, is there exactly when its version is: JDBC's {@code wasNull} answers for the
 * last column read, so the store judges the head before it reads the ref's columns. The heavy build's store suites
 * found a branch with no pointer of its own read as having one (2026-11-23); this checks the same row without a
 * database.
 */
class SqlRefsRowsTest {

    private static final OffsetDateTime MADE = OffsetDateTime.of(2026, 11, 23, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void aBranchWithNoPointerOfItsOwnHasNoHead() throws Exception {
        RefPointers read = SqlRefs.pointers(row("BRANCH", MADE.plusDays(7), null));

        assertThat(read.ref().kind()).isEqualTo(Ref.Kind.BRANCH);
        assertThat(read.cut()).isEqualTo(new RefEntry.At(3, "s3://w/t/metadata/3.json", "c3"));
        assertThat(read.head()).isEmpty();
    }

    @Test
    void aBranchWithItsOwnPointerHasItAsItsHead() throws Exception {
        RefPointers read = SqlRefs.pointers(row("BRANCH", MADE.plusDays(7), 4L));

        assertThat(read.head()).contains(new RefEntry.At(4, "s3://w/t/metadata/4.branch.json", "h4"));
    }

    @Test
    void aTagHasNoHead() throws Exception {
        RefPointers read = SqlRefs.pointers(row("TAG", null, null));

        assertThat(read.ref().kind()).isEqualTo(Ref.Kind.TAG);
        assertThat(read.ref().expiresAt()).isEmpty();
        assertThat(read.head()).isEmpty();
    }

    /** One row of the listing's join: the ref, its cut's pointer, and its own pointer when {@code head} is given. */
    private static ResultSet row(String kind, OffsetDateTime expiresAt, Long head) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("id", UUID.randomUUID());
        columns.put("catalog_id", UUID.randomUUID());
        columns.put("name_norm", "dev");
        columns.put("name", "Dev");
        columns.put("kind", kind);
        columns.put("created_at", MADE);
        columns.put("created_by", "alice");
        columns.put("from_ref", null);
        columns.put("from_name", Ref.MAIN);
        columns.put("expires_at", expiresAt);
        columns.put("version", 3L);
        columns.put("metadata_location", "s3://w/t/metadata/3.json");
        columns.put("sha256", "c3");
        columns.put("head_version", head);
        columns.put("head_location", head == null ? null : "s3://w/t/metadata/" + head + ".branch.json");
        columns.put("head_sha256", head == null ? null : "h" + head);
        return fake(columns);
    }

    /** A result set on one row that answers {@code wasNull} for the column read last, as JDBC's does. */
    private static ResultSet fake(Map<String, Object> columns) {
        boolean[] lastNull = {false};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("wasNull")) {
                        return lastNull[0];
                    }
                    String column = (String) args[0];
                    if (!columns.containsKey(column)) {
                        throw new UnsupportedOperationException("no column " + column);
                    }
                    Object value = columns.get(column);
                    lastNull[0] = value == null;
                    return switch (method.getName()) {
                        case "getLong" -> value == null ? 0L : value;
                        case "getString", "getObject" -> value;
                        default -> throw new UnsupportedOperationException("ResultSet." + method.getName());
                    };
                });
    }
}
