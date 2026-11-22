package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * The server migrates with the store's own steps for its backend (M8): on PostgreSQL the journal is
 * partitioned by month (V4) and its partitions reach ahead, as the store suite's schema expects.
 */
@Tag("heavy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = IssuerForTests.class)
@Import({PostgresForTests.class, S3ForTests.class})
class SchemaAtStartupTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theJournalIsPartitionedWithMonthsAhead() {
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",
                Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT relkind::text FROM pg_class WHERE relname = 'journal'", String.class))
                .isEqualTo("p");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent"
                + " WHERE c.relname = 'journal'", Integer.class)).isGreaterThan(12);
    }
}
