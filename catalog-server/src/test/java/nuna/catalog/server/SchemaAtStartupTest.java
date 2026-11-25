package nuna.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * The server migrates with the store's own steps for its backend (M8): on PostgreSQL every step, the shared ones and
 * its own, is applied, the journal is partitioned by month (V4) and its partitions reach ahead, as the store suite's
 * schema expects. The steps are read from the classpath, so a new one needs no change here (backlog 268).
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
        assertThat(Flyway.configure().dataSource(jdbc.getDataSource())
                .locations("classpath:nuna/store/sql/migration", "classpath:nuna/store/sql/postgresql").load().info()
                .pending()).as("PostgreSQL's steps not applied").isEmpty();
        assertThat(jdbc.queryForObject("SELECT relkind::text FROM pg_class WHERE relname = 'journal'", String.class))
                .isEqualTo("p");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent"
                + " WHERE c.relname = 'journal'", Integer.class)).isGreaterThan(12);
    }
}
