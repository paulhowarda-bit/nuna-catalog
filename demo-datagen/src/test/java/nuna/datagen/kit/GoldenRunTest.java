package nuna.datagen.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Java port posts what the kit's Python engine posted, and both match the workbook: the golden run of ledger kit
 * v14 (44 financial transactions as the workbook numbers its blocks, 289 journal lines).
 */
class GoldenRunTest {

    private final Kit kit = Kit.load();

    record Key(int definition, Long lot, String side) {}

    @Test
    void theKitThisWasExportedFromPassedItsOwnChecks() {
        assertThat(Golden.RUN.pythonCheck().allMatch()).isTrue();
        assertThat(Golden.RUN.pythonKitChecks()).hasSize(46).allMatch(Golden.PythonKitCheck::passed);
    }

    @Test
    void postsExactlyWhatThePythonEnginePosted() throws Rejected {
        Engine engine = new Engine(kit);
        List<Golden.Run> runs = Golden.runs();
        assertThat(Golden.RUN.posted()).hasSize(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            List<JournalLine> got = engine.post(runs.get(i).transaction());
            List<List<Object>> want = Golden.RUN.posted().get(i).lines();
            assertThat(got).as(runs.get(i).block()).hasSize(want.size());
            for (int j = 0; j < want.size(); j++) {
                List<Object> w = want.get(j);
                JournalLine g = got.get(j);
                String at = runs.get(i).block() + " line " + j;
                assertThat(g.subLedger()).as(at).isEqualTo(((Number) w.get(0)).intValue());
                assertThat(g.definition()).as(at).isEqualTo(((Number) w.get(1)).intValue());
                assertThat(g.side().name()).as(at).isEqualTo(w.get(2));
                assertThat(g.amount()).as(at).isEqualByComparingTo(new BigDecimal((String) w.get(3)));
                assertThat(g.security()).as(at).isEqualTo(w.get(4) == null ? null : ((Number) w.get(4)).longValue());
                assertThat(g.lot()).as(at).isEqualTo(w.get(5) == null ? null : ((Number) w.get(5)).longValue());
            }
        }
        List<String> want = new ArrayList<>();
        Golden.RUN.ledgerAccounts().forEach(a -> want.add(a.definition() + " "
            + a.key().stream().map(kv -> kv.get(1)).toList() + " " + a.id()));
        List<String> got = new ArrayList<>();
        engine.ledgerAccounts().forEach((k, id) -> got.add(k.definition() + " " + k.values() + " " + id));
        assertThat(got).containsExactlyElementsOf(want);
    }

    @Test
    void matchesTheWorkbookPerFinancialTransactionAndAtTheEnd() throws Rejected {
        Map<String, Map<Key, BigDecimal>> wb = new LinkedHashMap<>();
        for (Golden.WorkbookEvent e : Golden.WORKBOOK) {
            for (Golden.WorkbookLine l : e.lines()) {
                Long lot = kit.definition(l.def()).root() == Kit.SL_SHARE && l.lot() != null ? Golden.lot(l.lot()) : null;
                wb.computeIfAbsent(e.id(), k -> new HashMap<>())
                    .merge(new Key(l.def(), lot, l.side()), l.amount(), BigDecimal::add);
            }
        }
        assertThat(Golden.WORKBOOK).hasSize(44);
        assertThat(Golden.WORKBOOK.stream().mapToInt(e -> e.lines().size()).sum()).isEqualTo(289);
        Engine engine = new Engine(kit);
        Map<String, Map<Key, BigDecimal>> got = new LinkedHashMap<>();
        for (Golden.Run r : Golden.runs()) {
            for (JournalLine l : engine.post(r.transaction())) {
                Long lot = kit.definition(l.definition()).root() == Kit.SL_SHARE ? l.lot() : null;
                got.computeIfAbsent(r.block(), k -> new HashMap<>())
                    .merge(new Key(l.definition(), lot, l.side().name()), l.amount(), BigDecimal::add);
            }
        }
        assertThat(got.keySet()).isEqualTo(wb.keySet());
        List<String> mismatches = new ArrayList<>();
        for (String block : wb.keySet()) {
            Set<Key> keys = new LinkedHashSet<>(wb.get(block).keySet());
            keys.addAll(got.get(block).keySet());
            for (Key k : keys) {
                BigDecimal w = wb.get(block).getOrDefault(k, BigDecimal.ZERO), g = got.get(block).getOrDefault(k, BigDecimal.ZERO);
                if (w.subtract(g).abs().compareTo(new BigDecimal("0.005")) > 0) {
                    mismatches.add(block + " " + k + ": workbook " + w + ", engine " + g);
                }
            }
        }
        assertThat(mismatches).isEmpty();

        assertThat(openLots(engine, Golden.BA_CASH, Golden.XYZ))
            .isEqualTo(Map.of(Golden.lot("F-002"), "30", Golden.lot("F-003"), "20"));
        assertThat(openLots(engine, Golden.BA_MARGIN, Golden.XYZ))
            .isEqualTo(Map.of(Golden.lot("M-001"), "100", Golden.lot("M-003"), "200"));
        assertThat(openLots(engine, Golden.BA_MARGIN, Golden.ABC)).isEmpty();
        assertThat(engine.shortLots().keySet())
            .allMatch(l -> engine.balance(Golden.BA_SHORT, 3101, Golden.ABC, l).signum() == 0);
        Map<String, String> ends = Map.of(
            Golden.BA_CASH + " 1001", "11245.20", Golden.BA_MARGIN + " 1001", "-62620", Golden.BA_MARGIN + " 1405", "0",
            Golden.BA_MARGIN + " 5104", "0", Golden.BA_MARGIN + " 4105", "0", Golden.BA_SHORT + " 1001", "4000",
            Golden.BA_SHORT + " 1202", "6000", Golden.BA_SHORT + " 5106", "0", Golden.BA_SHORT + " 4108", "0");
        ends.forEach((k, v) -> {
            String[] p = k.split(" ");
            assertThat(engine.balance(Long.parseLong(p[0]), Integer.parseInt(p[1]), null, null)).as(k)
                .isEqualByComparingTo(v);
        });
    }

    private static Map<Long, String> openLots(Engine engine, long ba, long sec) {
        Map<Long, String> out = new HashMap<>();
        engine.lots().forEach((lot, ref) -> {
            BigDecimal b = engine.balance(ba, 3001, sec, lot);
            if (ref.brokerageAccount() == ba && ref.security() == sec && b.signum() != 0) {
                out.put(lot, b.stripTrailingZeros().toPlainString());
            }
        });
        return out;
    }
}
