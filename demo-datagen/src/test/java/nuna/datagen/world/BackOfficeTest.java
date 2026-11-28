package nuna.datagen.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import nuna.datagen.kit.Kit;
import org.junit.jupiter.api.Test;

/**
 * The back office against the ledger (item 152; docs/design/brokerage-world.md, "It ties out"): every payment is the
 * ledger's, with its id, amount and template, every ACH return and ACATS transfer too, and the seeded structuring
 * deposits and sanctioned wire make exactly their own alerts and cases, the wire never reaching the ledger.
 */
class BackOfficeTest {

    private static final Kit KIT = Kit.load();
    /** The templates that start a payment, and the field each carries the payment's id (or the journal's) in. */
    private static final Map<Integer, Integer> STARTS = Map.of(30, 11, 31, 13, 33, 12, 70, 11, 71, 12, 76, 11);
    /** The templates that carry a payment on: settled, cancelled, returned. */
    private static final Set<Integer> LATER = Set.of(32, 34, 35, 72, 73, 74, 75);
    private static final Set<Integer> ACATS = Set.of(82, 83, 84, 85);

    /** A template field's column in financial_transactions. */
    private static String column(int template, int field) {
        return World.snake(KIT.templates().get(template).fields().get(field));
    }

    private static BigDecimal dec(Object v) {
        return v instanceof BigDecimal d ? d : BigDecimal.valueOf(((Number) v).longValue());
    }

    private static Map<Long, Map<String, Object>> transactions(Played p) {
        Map<Long, Map<String, Object>> fts = new HashMap<>();
        for (Map<String, Object> ft : p.of(Feed.FINANCIAL_TRANSACTIONS)) {
            fts.put((Long) ft.get("financial_transaction_id"), ft);
        }
        return fts;
    }

    /**
     * Tie 5: each payment row is the ledger's financial transaction of its id, template, account and amount; the first
     * carries the payment's id, each later one goes back to it; and every payment the ledger books has its row. A request
     * made while the payments systems were shut (item 321) is recorded first, with no financial transaction, as version
     * 1, and booked as version 2 or rejected.
     */
    @Test
    void everyPaymentIsTheLedgersWithItsIdTemplateAndAmount() {
        Played p = Played.seeded();
        Map<Long, Map<String, Object>> fts = transactions(p);
        Map<String, Long> started = new HashMap<>();
        Set<Long> paid = new HashSet<>();
        Set<String> requested = new HashSet<>();
        int rejected = 0;
        for (Map<String, Object> pay : p.of(Feed.PAYMENTS)) {
            String id = (String) pay.get("payment_id");
            if (pay.get("status").equals("blocked")) {
                continue;
            }
            if (pay.get("status").equals("requested")) {
                assertThat(pay.get("version")).as(id).isEqualTo(1);
                assertThat(pay.get("financial_transaction_id")).as(id).isNull();
                requested.add(id);
                continue;
            }
            if (pay.get("status").equals("rejected")) {
                assertThat(requested.remove(id)).as(id + " was requested").isTrue();
                assertThat(pay.get("financial_transaction_id")).as(id).isNull();
                rejected++;
                continue;
            }
            Long ftId = (Long) pay.get("financial_transaction_id");
            Map<String, Object> ft = fts.get(ftId);
            assertThat(ft).as(id + " version " + pay.get("version") + "'s financial transaction").isNotNull();
            int template = (Integer) pay.get("template_id");
            assertThat(ft.get("template_id")).as(id).isEqualTo(template);
            assertThat(ft.get("brokerage_account")).as(id).isEqualTo(pay.get("brokerage_account"));
            assertThat(dec(ft.get(column(template, 10)))).as(id).isEqualByComparingTo((BigDecimal) pay.get("amount"));
            if (STARTS.containsKey(template)) {
                assertThat(pay.get("version")).as(id).isEqualTo(requested.remove(id) ? 2 : 1);
                assertThat(ft.get(column(template, STARTS.get(template)))).as(id).isEqualTo(id);
                started.put(id, ftId);
            } else {
                assertThat(LATER).as(id).contains(template);
                assertThat(ft.get("originating_financial_transaction_id")).as(id).isEqualTo(started.get(id));
            }
            assertThat(paid.add(ftId)).as("financial transaction " + ftId + " is one payment's").isTrue();
        }
        for (Map<String, Object> ft : p.of(Feed.FINANCIAL_TRANSACTIONS)) {
            int template = (Integer) ft.get("template_id");
            if (STARTS.containsKey(template) || LATER.contains(template)) {
                assertThat(paid).as("template " + template + ", " + ft.get("financial_transaction_id"))
                    .contains((Long) ft.get("financial_transaction_id"));
            }
        }
        assertThat(started).hasSizeGreaterThan(100);
        assertThat(p.of(Feed.PAYMENTS)).extracting(r -> r.get("method")).contains("cash", "ach", "wire");
        assertThat(p.of(Feed.PAYMENTS)).extracting(r -> r.get("status")).contains("requested");
        // What is still requested was asked for after the last day's cutoff (a wire's, the earliest): the next business
        // day books it.
        Instant cutoff = p.days.getLast().atTime(World.WIRE_CUTOFF).atZone(World.NEW_YORK).toInstant();
        for (Map<String, Object> pay : p.of(Feed.PAYMENTS)) {
            if (requested.contains(pay.get("payment_id"))) {
                assertThat((Instant) pay.get("event_time")).as(pay.toString()).isAfterOrEqualTo(cutoff);
            }
        }
    }

    /** Each ACH return is the ledger's return of its payment, with its code; each ACATS transfer the ledger's by control. */
    @Test
    void everyAchReturnAndAcatsTransferIsTheLedgers() {
        Played p = Played.seeded();
        Map<Long, Map<String, Object>> fts = transactions(p);
        Map<String, Long> started = new HashMap<>();
        for (Map<String, Object> pay : p.of(Feed.PAYMENTS)) {
            if (pay.get("template_id") != null && STARTS.containsKey(pay.get("template_id"))) {
                started.put((String) pay.get("payment_id"), (Long) pay.get("financial_transaction_id"));
            }
        }
        Set<Long> returned = new HashSet<>();
        for (Map<String, Object> r : p.of(Feed.ACH_RETURNS)) {
            Map<String, Object> ft = fts.get((Long) r.get("financial_transaction_id"));
            int template = (Boolean) r.get("after_settlement") ? 75 : 74;
            assertThat(ft.get("template_id")).isEqualTo(template);
            assertThat(ft.get(column(template, 13))).isEqualTo(r.get("return_code"));
            assertThat(dec(ft.get(column(template, 10)))).isEqualByComparingTo((BigDecimal) r.get("amount"));
            assertThat(ft.get("originating_financial_transaction_id")).isEqualTo(started.get((String) r.get("payment_id")));
            returned.add((Long) ft.get("financial_transaction_id"));
        }
        assertThat(returned).isNotEmpty();
        assertThat(p.of(Feed.FINANCIAL_TRANSACTIONS)).filteredOn(ft -> Set.of(74, 75).contains(ft.get("template_id")))
            .extracting(ft -> (Long) ft.get("financial_transaction_id")).containsExactlyInAnyOrderElementsOf(returned);

        Map<String, Object> accounts = new HashMap<>();
        for (Map<String, Object> ft : p.of(Feed.FINANCIAL_TRANSACTIONS)) {
            if (ACATS.contains((Integer) ft.get("template_id"))) {
                accounts.put((String) ft.get(column((Integer) ft.get("template_id"), 11)), ft.get("brokerage_account"));
            }
        }
        assertThat(p.of(Feed.ACATS_TRANSFERS)).isNotEmpty();
        Map<String, Object> transfers = p.of(Feed.ACATS_TRANSFERS).stream()
            .collect(Collectors.toMap(t -> (String) t.get("acats_control_number"), t -> t.get("brokerage_account")));
        assertThat(transfers).isEqualTo(accounts);
    }

    /**
     * The seeded structuring deposits make one structuring alert, escalated to a case whose SAR is filed; the sanctioned
     * wire one confirmed match, one alert and a case reporting the blocked property; nothing else opens a case, and the
     * blocked wire never reaches the ledger.
     */
    @Test
    void theSeededDepositsAndTheSanctionedWireMakeExactlyTheirAlertsAndCases() {
        Played p = Played.seeded();
        World world = new World(Played.SETTINGS, KIT);
        long structurer = world.structuringAccount(), sanctioned = world.sanctionedAccount();
        assertThat(structurer).isNotEqualTo(sanctioned);

        Map<String, List<Map<String, Object>>> alerts = byId(p.of(Feed.AML_ALERTS), "alert_id");
        assertThat(alerts.values()).extracting(v -> v.getFirst().get("scenario")).containsOnly("structuring",
            "sanctions_match", "large_wire");
        List<List<Map<String, Object>>> structuring = alerts.values().stream()
            .filter(v -> v.getFirst().get("scenario").equals("structuring")).toList();
        assertThat(structuring).singleElement().satisfies(v -> {
            assertThat(v).extracting(r -> r.get("brokerage_account")).containsOnly(structurer);
            assertThat(v).extracting(r -> r.get("status")).containsExactly("open", "escalated");
            String detail = (String) v.getFirst().get("detail");
            List<Map<String, Object>> deposits = p.of(Feed.PAYMENTS).stream()
                .filter(r -> detail.contains((String) r.get("payment_id"))).toList();
            assertThat(deposits).hasSize(BackOffice.STRUCTURING_DEPOSITS).allSatisfy(d -> {
                assertThat(d.get("brokerage_account")).isEqualTo(structurer);
                assertThat(d.get("method")).isEqualTo("cash");
                assertThat((BigDecimal) d.get("amount")).isBetween(new BigDecimal("9000"), new BigDecimal("9900"));
            });
        });
        List<List<Map<String, Object>>> matches = alerts.values().stream()
            .filter(v -> v.getFirst().get("scenario").equals("sanctions_match")).toList();
        assertThat(matches).singleElement().satisfies(v -> {
            assertThat(v).extracting(r -> r.get("brokerage_account")).containsOnly(sanctioned);
            assertThat(v).extracting(r -> r.get("status")).containsExactly("open", "escalated");
        });

        Map<String, List<Map<String, Object>>> cases = byId(p.of(Feed.AML_CASES), "case_id");
        assertThat(cases.values()).hasSize(2).extracting(v -> v.getFirst().get("case_type") + " "
                + v.getFirst().get("brokerage_account") + " " + v.getLast().get("status"))
            .containsExactlyInAnyOrder("sar_review " + structurer + " sar_filed",
                "sanctions_block " + sanctioned + " report_filed");

        List<Map<String, Object>> confirmed = p.of(Feed.SANCTIONS_SCREENS).stream()
            .filter(s -> s.get("result").equals("confirmed_match")).toList();
        List<Map<String, Object>> blocked = p.of(Feed.PAYMENTS).stream()
            .filter(r -> r.get("status").equals("blocked")).toList();
        assertThat(blocked).singleElement().satisfies(b -> {
            assertThat(b.get("brokerage_account")).isEqualTo(sanctioned);
            assertThat(b.get("method")).isEqualTo("wire");
            assertThat(b.get("financial_transaction_id")).isNull();
            assertThat(b.get("counterparty_name")).isEqualTo(BackOffice.SEEDED_MATCH.name());
        });
        String wire = (String) blocked.getFirst().get("payment_id");
        assertThat(confirmed).singleElement().satisfies(s -> {
            assertThat(s.get("matched_ent_num")).isEqualTo(BackOffice.SEEDED_MATCH.entNum());
            assertThat(s.get("payment_id")).isEqualTo(wire);
            assertThat(s.get("disposition")).isEqualTo("blocked");
        });
        assertThat(p.of(Feed.PAYMENTS)).filteredOn(r -> r.get("payment_id").equals(wire)).hasSize(1);
        assertThat(p.of(Feed.FINANCIAL_TRANSACTIONS)).noneMatch(ft -> ft.containsValue(wire));
        assertThat(p.of(Feed.SANCTIONS_LIST)).extracting(r -> r.get("ent_num"))
            .contains(BackOffice.SEEDED_MATCH.entNum());
    }

    private static Map<String, List<Map<String, Object>>> byId(List<Map<String, Object>> rows, String key) {
        Map<String, List<Map<String, Object>>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            by.computeIfAbsent((String) r.get(key), k -> new java.util.ArrayList<>()).add(r);
        }
        return by;
    }
}
