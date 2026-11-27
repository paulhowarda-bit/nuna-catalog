package nuna.datagen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nuna.datagen.kit.Kit;
import nuna.datagen.live.Sources;
import nuna.datagen.world.Feed;
import nuna.datagen.world.LakeSchemas;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/**
 * demo.sh flags every lake table that holds a tax id, a birth date, an address or a bank account number, and only
 * those (D20, item 308): both routes' copies of the world's tables and the CDC leg's histories; and it registers every
 * writer of them as a trusted engine, since a flagged table opens its files only to trusted engines.
 */
class FlaggedTablesTest {

    private static final Path DEMO = Path.of("..", "demo");

    /** The columns that make a table flagged: tax id, birth date, address, bank account number. */
    private static final Set<String> SENSITIVE = Set.of("tax_id", "birth_date", "address_line", "bank_account_number",
        "counterparty_account_number");

    private static List<String> words(String sh, String name) {
        Matcher m = Pattern.compile("(?m)^" + name + "=\"([^\"]*)\"").matcher(sh);
        assertThat(m.find()).as(name + " in demo.sh").isTrue();
        return Arrays.asList(m.group(1).trim().split("\\s+"));
    }

    @Test
    void demoShFlagsEveryTableWithTaxIdsBirthDatesAddressesOrBankAccountNumbers() throws IOException {
        Kit kit = Kit.load();
        List<String> sensitive = new ArrayList<>();
        for (Feed f : Feed.numbered()) {
            boolean flagged = LakeSchemas.schema(f, kit).columns().stream().map(Types.NestedField::name)
                .anyMatch(SENSITIVE::contains);
            if (flagged) {
                sensitive.add(f.lakeTable(Feed.Path.KAFKA));
                sensitive.add(f.lakeTable(Feed.Path.SINGLESTORE));
            }
        }
        for (Sources.Table t : Sources.Table.values()) {
            if (t.columnNames().stream().anyMatch(SENSITIVE::contains)) {
                sensitive.add(t.history());
            }
        }
        assertThat(sensitive).as("the tables with sensitive columns").isNotEmpty();
        assertThat(words(Files.readString(DEMO.resolve("demo.sh")), "FLAGGED_TABLES"))
            .containsExactlyInAnyOrderElementsOf(sensitive);
    }

    /**
     * Every writer of a flagged table is a trusted engine: Kafka Connect's sinks, the world's and the CDC leg's (user
     * cdc, connect.yaml); SingleStore's change feeds (user singlestore); the generator, which tags each end of day and
     * counts each table's rows (user brokerage); and Trino (user trino), which reads them for its users. Each user's
     * client is its token's azp, which Mint.java gives.
     */
    @Test
    void demoShTrustsEveryWriterOfAFlaggedTable() throws IOException {
        String mint = Files.readString(DEMO.resolve("Mint.java"));
        List<String> clients = new ArrayList<>();
        for (String user : List.of("trino", "cdc", "singlestore", "brokerage")) {
            Matcher m = Pattern.compile("USERS\\.put\\(\"" + user + "\", new String\\[] \\{\"([^\"]+)\"").matcher(mint);
            assertThat(m.find()).as(user + " in Mint.java").isTrue();
            clients.add(m.group(1));
        }
        assertThat(words(Files.readString(DEMO.resolve("demo.sh")), "TRUSTED_CLIENTS"))
            .containsExactlyInAnyOrderElementsOf(clients);
    }
}
