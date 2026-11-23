package nuna.datagen.kit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The kit's golden run, exported from ledger kit v14 ({@code schemes_v20.py}): the four v114 lifecycles as financial
 * transactions, the lot of each business fill id, the workbook's General Ledger control per block, what the Python
 * engine posted for each, and the workbook's own journal lines ({@code events_v8.json}).
 */
final class Golden {

    record Run(String block, long brokerageAccount, int template, Map<String, Object> fields) {
        FinancialTransaction transaction() {
            Map<Integer, Object> f = new TreeMap<>();
            fields.forEach((k, v) -> f.put(Integer.valueOf(k), v instanceof Integer i ? Long.valueOf(i) : v));
            return new FinancialTransaction(template, brokerageAccount, f);
        }
    }

    record Posted(String block, List<List<Object>> lines) {}

    record LedgerAccount(int definition, List<List<Long>> key, long id) {}

    record PythonCheck(boolean allMatch, List<String> report) {}

    record PythonKitCheck(String name, boolean passed) {}

    record File(Map<String, Long> constants, List<Run> runs, Map<String, Long> lotOfFill,
                Map<String, BigDecimal> glControlByBlock, List<Posted> posted, List<LedgerAccount> ledgerAccounts,
                PythonCheck pythonCheck, List<PythonKitCheck> pythonKitChecks) {}

    record WorkbookLine(int def, String side, BigDecimal amount, String lot) {}

    record WorkbookEvent(String id, List<WorkbookLine> lines) {}

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    static final File RUN = read("golden-run.json", File.class);
    static final List<WorkbookEvent> WORKBOOK = List.of(read("events_v8.json", WorkbookEvent[].class));

    static final long USD = RUN.constants().get("USD"), XYZ = RUN.constants().get("XYZ"), ABC = RUN.constants().get("ABC");
    static final long BA_CASH = RUN.constants().get("BA_CASH"), BA_MARGIN = RUN.constants().get("BA_MARGIN"),
        BA_SHORT = RUN.constants().get("BA_SHORT");
    static final long D = RUN.constants().get("D"), T1 = RUN.constants().get("T1"), T2 = RUN.constants().get("T2"),
        T3 = RUN.constants().get("T3");

    private static <T> T read(String name, Class<T> type) {
        try (InputStream in = Golden.class.getResourceAsStream(name)) {
            return MAPPER.readValue(Objects.requireNonNull(in, name), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The golden run's financial transactions, with their blocks. */
    static List<Run> runs() {
        return RUN.runs();
    }

    static List<FinancialTransaction> transactions() {
        List<FinancialTransaction> out = new ArrayList<>();
        RUN.runs().forEach(r -> out.add(r.transaction()));
        return out;
    }

    static long lot(String businessFillId) {
        return RUN.lotOfFill().get(businessFillId);
    }

    /** An engine that has posted the first n financial transactions of the golden run. */
    static Engine fresh(Kit kit, int n) {
        Engine e = new Engine(kit);
        for (FinancialTransaction t : transactions().subList(0, n)) {
            try {
                e.post(t);
            } catch (Rejected r) {
                throw new AssertionError(r);
            }
        }
        return e;
    }

    static int index(String block) {
        List<Run> runs = RUN.runs();
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).block().equals(block)) {
                return i;
            }
        }
        throw new IllegalArgumentException(block);
    }

    private Golden() {}
}
