package nuna.datagen.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nuna.datagen.world.Event;
import nuna.datagen.world.Feed;
import nuna.datagen.world.Played;
import org.junit.jupiter.api.Test;

/**
 * The CDC leg's back office and treasury (item 152): the tables demo/cdc/backoffice.sql and oracle.sql make are those
 * {@link Sources} writes, and a load writes only what changed, run against H2 in PostgreSQL's and Oracle's modes.
 */
class SourcesTest {

    private static final Path CDC = Path.of("..", "demo", "cdc");

    @Test
    void theSqlFilesMakeTheTablesTheLoadWrites() throws Exception {
        assertThat(Files.readString(CDC.resolve("backoffice.sql"))).as("demo/cdc/backoffice.sql")
            .contains(Sources.ddl(Sources.Db.BACKOFFICE)).doesNotContain("settlement.instructions");
        assertThat(Files.readString(CDC.resolve("oracle.sql"))).as("demo/cdc/oracle.sql")
            .contains(Sources.ddl(Sources.Db.TREASURY)).doesNotContain("treasury.payments (");
    }

    /** The books of two weeks of the world: its events fed in feed by feed, each feed's in the order it sent them. */
    /** The books as the world played them, every event in its order, each day's end closing the General Ledger's day. */
    private static Sources.Book book() {
        return Played.seeded().book;
    }

    private static Connection database(Sources.Db db) throws SQLException {
        Connection c = DriverManager.getConnection(db == Sources.Db.BACKOFFICE
            ? "jdbc:h2:mem:backoffice;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            : "jdbc:h2:mem:treasury;MODE=Oracle;DATABASE_TO_LOWER=TRUE");
        try (Statement s = c.createStatement()) {
            if (db == Sources.Db.TREASURY) {
                s.execute("CREATE SCHEMA treasury");
            }
            for (String statement : Sources.ddl(db).split(";\n")) {
                // H2 has neither PostgreSQL's replica identity nor Oracle's supplemental logging, which only CDC reads.
                if (!statement.isBlank() && !statement.startsWith("ALTER TABLE")) {
                    s.execute(statement);
                }
            }
        }
        return c;
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void theBackOfficeLoadWritesEveryRowOnceThenOnlyWhatChanged() throws SQLException {
        Sources.Book book = book();
        try (Connection c = database(Sources.Db.BACKOFFICE)) {
            Sources sources = new Sources(Sources.Db.BACKOFFICE, c);
            Map<Sources.Table, Sources.Counts> first = sources.load(book);
            assertThat(first.keySet()).containsExactlyElementsOf(
                java.util.Arrays.stream(Sources.Table.values()).filter(t -> t.db == Sources.Db.BACKOFFICE).toList());
            for (Map.Entry<Sources.Table, Sources.Counts> e : first.entrySet()) {
                int size = book.size(e.getKey());
                assertThat(e.getValue()).as(e.getKey().name()).isEqualTo(new Sources.Counts(size, 0, size));
                assertThat(count(c, e.getKey().qualified())).as(e.getKey().name()).isEqualTo(size);
            }
            for (Sources.Table t : List.of(Sources.Table.PARTIES, Sources.Table.ACCOUNTS, Sources.Table.PAYMENTS,
                    Sources.Table.AML_ALERTS, Sources.Table.AML_CASES, Sources.Table.BENEFICIAL_OWNERS)) {
                assertThat(book.size(t)).as(t.name()).isPositive();
            }
            // Each payment once, at its latest version.
            assertThat(book.size(Sources.Table.PAYMENTS)).isEqualTo(Played.seeded().of(Feed.PAYMENTS).stream()
                .map(r -> r.get("payment_id")).distinct().count());

            // Read back, every value is what the books hold: a second load writes nothing.
            assertThat(sources.load(book).values()).allSatisfy(n -> assertThat(n.written() + n.deleted()).isZero());

            // A row someone changed is put back, and a row the books do not hold goes.
            long party;
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT min(party_id) FROM party.parties")) {
                r.next();
                party = r.getLong(1);
            }
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE party.parties SET email = 'changed@example.com' WHERE party_id = " + party);
                s.execute("INSERT INTO party.parties (party_id, version, legal_name) VALUES (1, 1, 'Stray Party')");
            }
            Map<Sources.Table, Sources.Counts> third = sources.load(book);
            assertThat(third.get(Sources.Table.PARTIES)).isEqualTo(
                new Sources.Counts(1, 1, book.size(Sources.Table.PARTIES)));
            assertThat(third).allSatisfy((t, n) -> {
                if (t != Sources.Table.PARTIES) {
                    assertThat(n.written() + n.deleted()).as(t.name()).isZero();
                }
            });
            assertThat(count(c, "party.parties")).isEqualTo(book.size(Sources.Table.PARTIES));
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT email FROM party.parties WHERE party_id = " + party)) {
                r.next();
                assertThat(r.getString(1)).isNotEqualTo("changed@example.com");
            }
        }
    }

    /**
     * The firm's bank sees the ACH and wire payments alone, each at its latest status, the blocked wire as blocked; the
     * General Ledger (item 153) has a journal entry per control that moved each day and every control's balance.
     */
    @Test
    void treasuryHoldsEachAchAndWirePaymentAsTheBankSeesIt() throws SQLException {
        Sources.Book book = book();
        Set<Object> bankable = new HashSet<>();
        for (Map<String, Object> r : Played.seeded().of(Feed.PAYMENTS)) {
            if (r.get("method").equals("ach") || r.get("method").equals("wire")) {
                bankable.add(r.get("payment_id"));
            }
        }
        try (Connection c = database(Sources.Db.TREASURY)) {
            Sources sources = new Sources(Sources.Db.TREASURY, c);
            int journal = book.size(Sources.Table.GL_JOURNAL), balances = book.size(Sources.Table.GL_BALANCES);
            assertThat(sources.load(book)).containsExactly(
                Map.entry(Sources.Table.BANK_PAYMENTS, new Sources.Counts(bankable.size(), 0, bankable.size())),
                Map.entry(Sources.Table.GL_JOURNAL, new Sources.Counts(journal, 0, journal)),
                Map.entry(Sources.Table.GL_BALANCES, new Sources.Counts(balances, 0, balances)));
            assertThat(sources.load(book)).allSatisfy((t, n) -> assertThat(n.written()).as(t.name()).isZero());
            // A control's balance appears from the day its definition first moves, then every day.
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT count(DISTINCT business_date), count(DISTINCT gl_account),"
                        + " count(CASE WHEN business_date = (SELECT max(business_date) FROM treasury.gl_balances)"
                        + " THEN 1 END) FROM treasury.gl_balances")) {
                r.next();
                assertThat(r.getInt(1)).isEqualTo(Played.DAYS);
                assertThat(r.getInt(3)).isEqualTo(r.getInt(2)).isGreaterThan(1);
            }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT gl_account_name, normal_side FROM treasury.gl_balances WHERE gl_account = 'GL-1001'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("Customer Cash Balances [Control]");
                assertThat(r.getString(2)).isEqualTo("Debit");
            }
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT rail, firm_bank_account, status, count(*) FROM treasury.bank_payments"
                        + " GROUP BY rail, firm_bank_account, status")) {
                Set<String> seen = new HashSet<>();
                while (r.next()) {
                    seen.add(r.getString(1) + " " + r.getString(2) + " " + r.getString(3));
                }
                assertThat(seen).allSatisfy(x -> assertThat(x).matches(
                    "(ACH DEMO-ACH-SETTLEMENT|FEDWIRE DEMO-WIRE-SETTLEMENT) (pending|settled|cancelled|returned|blocked)"));
                assertThat(seen).contains("FEDWIRE DEMO-WIRE-SETTLEMENT blocked", "ACH DEMO-ACH-SETTLEMENT settled");
            }
        }
    }
}
