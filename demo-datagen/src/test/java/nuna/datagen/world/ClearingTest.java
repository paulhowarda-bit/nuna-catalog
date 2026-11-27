package nuna.datagen.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The order management system and clearing against the ledger (item 151; docs/design/brokerage-world.md, "It ties
 * out"): each execution is one ledger fill, CNS is the net of the fills, the stock record ties to the share positions
 * and open lots and balances by owner and location, and the seeded fail to deliver makes exactly its own rows.
 */
class ClearingTest {

    private static final Set<Integer> FILLS = Set.of(41, 44, 61, 64);

    private static long longOf(Object v) {
        return ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        return v instanceof BigDecimal d ? d : BigDecimal.valueOf(longOf(v));
    }

    @Test
    void theWorldsBothWaysPostWithoutAnUnexpectedRejectionAndOnlyTheSeededOneFails() {
        assertThat(Played.seeded().unexpectedRejections).isZero();
        assertThat(Played.clean().unexpectedRejections).isZero();
        assertThat(Played.seeded().breakSeeded).isTrue();
        assertThat(Played.clean().breakSeeded).isFalse();
        // The fail changes clearing alone: every other feed is the same with it or without it.
        for (Feed f : Feed.values()) {
            if (f.namespace.equals("brokerage.clearing")) {
                continue;
            }
            assertThat(Played.seeded().of(f)).as(f.name()).isEqualTo(Played.clean().of(f));
        }
    }

    /** Tie 3: every execution is one ledger fill with the same fill id, quantity, price and trade date. */
    @Test
    void everyExecutionIsOneLedgerFillWithItsIdPriceAndQuantity() {
        Played p = Played.seeded();
        Map<String, Map<String, Object>> fills = new HashMap<>();
        for (Map<String, Object> ft : p.of(Feed.FINANCIAL_TRANSACTIONS)) {
            if (FILLS.contains((Integer) ft.get("template_id"))) {
                assertThat(fills.put((String) ft.get("fill_id"), ft)).as("fill id " + ft.get("fill_id")).isNull();
            }
        }
        List<Map<String, Object>> executions = p.of(Feed.EXECUTIONS);
        assertThat(executions).hasSize(fills.size()).hasSizeGreaterThan(500);
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> e : executions) {
            String fillId = (String) e.get("fill_id");
            assertThat(seen.add(fillId)).as("one execution per fill " + fillId).isTrue();
            Map<String, Object> ft = fills.get(fillId);
            assertThat(ft).as(fillId).isNotNull();
            assertThat(dec(e.get("last_quantity"))).as(fillId).isEqualByComparingTo((BigDecimal) ft.get("quantity"));
            assertThat((BigDecimal) e.get("last_price")).as(fillId).isEqualByComparingTo((BigDecimal) ft.get("fill_price"));
            assertThat(e.get("trade_date")).as(fillId).isEqualTo(ft.get("trade_date"));
            assertThat(e.get("financial_transaction_id")).isEqualTo(ft.get("financial_transaction_id"));
            assertThat(e.get("security_id")).isEqualTo(ft.get("security_id"));
        }
    }

    /** Routes and order events: each execution came through a route to its venue; each order's last event is its state. */
    @Test
    void eachExecutionCameThroughARouteToItsVenueAndEachOrdersLastEventIsItsState() {
        Played p = Played.seeded();
        Map<String, Map<String, Object>> routes = new HashMap<>();
        for (Map<String, Object> r : p.of(Feed.ROUTES)) {
            routes.put((String) r.get("route_id"), r);
        }
        for (Map<String, Object> e : p.of(Feed.EXECUTIONS)) {
            Map<String, Object> r = routes.get((String) e.get("route_id"));
            assertThat(r).as((String) e.get("exec_id")).isNotNull();
            assertThat(r.get("venue")).isEqualTo(e.get("venue"));
            assertThat(r.get("order_id")).isEqualTo(e.get("order_id"));
            assertThat((Instant) r.get("sent_at")).isBeforeOrEqualTo((Instant) e.get("executed_at"));
        }
        Map<Long, String> lastOrder = new HashMap<>(), lastEvent = new HashMap<>();
        Set<Long> accepted = new HashSet<>();
        Map<String, Integer> types = new TreeMap<>();
        for (Map<String, Object> o : p.of(Feed.ORDERS)) {
            lastOrder.put((Long) o.get("order_id"), (String) o.get("status"));
        }
        for (Map<String, Object> ev : p.of(Feed.ORDER_EVENTS)) {
            lastEvent.put((Long) ev.get("order_id"), (String) ev.get("status"));
            types.merge((String) ev.get("event_type"), 1, Integer::sum);
            if (ev.get("event_type").equals("accepted")) {
                accepted.add((Long) ev.get("order_id"));
            }
        }
        assertThat(lastEvent).isEqualTo(lastOrder);
        assertThat(accepted).isEqualTo(lastOrder.keySet());
        assertThat(types.get("filled") + types.get("partially_filled")).isEqualTo(p.of(Feed.EXECUTIONS).size());
        assertThat(types.get("routed")).isEqualTo(p.of(Feed.ROUTES).size());
        assertThat(types).containsKeys("expired");
    }

    /** Every short sale's acceptance (template 60) names a locate the clearing books found, and only those. */
    @Test
    void eachShortSaleWasAcceptedUnderALocate() {
        Played p = Played.seeded();
        Set<String> accepted = new HashSet<>(), located = new HashSet<>();
        for (Map<String, Object> ft : p.of(Feed.FINANCIAL_TRANSACTIONS)) {
            if ((Integer) ft.get("template_id") == 60) {
                accepted.add((String) ft.get("locate_id"));
            }
        }
        for (Map<String, Object> l : p.of(Feed.LOCATES)) {
            assertThat(located.add((String) l.get("locate_id"))).isTrue();
        }
        assertThat(accepted).isNotEmpty().isEqualTo(located);
        assertThat(p.of(Feed.LOCATES)).extracting(l -> l.get("source")).contains("easy_to_borrow_list");
    }

    private record Key(LocalDate date, long security) {}

    /** CNS net per trade date and security, less the customers' fills net: what CNS holds that no customer fill does. */
    private static Map<Key, BigDecimal[]> cnsLessFills(Played p) {
        Map<Key, BigDecimal[]> net = new TreeMap<>((a, b) -> a.date().equals(b.date())
            ? Long.compare(a.security(), b.security()) : a.date().compareTo(b.date()));
        for (Map<String, Object> c : p.of(Feed.CNS_OBLIGATIONS)) {
            BigDecimal[] v = net.computeIfAbsent(new Key((LocalDate) c.get("trade_date"), longOf(c.get("security_id"))),
                k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            v[0] = v[0].add(dec(c.get("net_quantity")));
            v[1] = v[1].add((BigDecimal) c.get("net_amount"));
        }
        for (Map<String, Object> ft : p.of(Feed.FINANCIAL_TRANSACTIONS)) {
            int t = (Integer) ft.get("template_id");
            if (!FILLS.contains(t)) {
                continue;
            }
            boolean buy = t == 41 || t == 64;
            BigDecimal[] v = net.computeIfAbsent(new Key((LocalDate) ft.get("trade_date"), longOf(ft.get("security_id"))),
                k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal q = (BigDecimal) ft.get("quantity");
            v[0] = v[0].subtract(buy ? q : q.negate());
            v[1] = v[1].subtract(buy ? ((BigDecimal) ft.get("fill_amount")).negate() : (BigDecimal) ft.get("gross_amount"));
        }
        net.values().removeIf(v -> v[0].signum() == 0 && v[1].signum() == 0);
        return net;
    }

    /** Tie 4 on a clean world: CNS is exactly the net of the customers' fills, per security and trade date. */
    @Test
    void onACleanWorldCnsIsTheNetOfTheCustomersFills() {
        Played p = Played.clean();
        assertThat(p.of(Feed.CNS_OBLIGATIONS)).hasSizeGreaterThan(100);
        assertThat(cnsLessFills(p)).isEmpty();
        assertThat(p.of(Feed.FAILS)).isEmpty();
        assertThat(p.of(Feed.SETTLEMENTS)).allSatisfy(s -> assertThat(s.get("quantity_failed")).isEqualTo(0L));
        assertThat(p.of(Feed.STOCK_RECORD)).noneMatch(r -> r.get("category").equals("fail_to_deliver")
            || r.get("category").equals("firm"));
    }

    /**
     * The seeded break: one net delivery holding a customer short sale fails for the short sale's shares, ages, is
     * closed out at 09:30 on the next settlement day (Reg SHO 204(a)) by a purchase for the firm's own account, and is
     * delivered when that settles. Exactly these rows show it: one partly failed settlement, four fail events, and one
     * CNS obligation that differs from the customers' fills, by the close-out, on the close-out's trade date.
     */
    @Test
    void theSeededFailToDeliverIsClosedOutUnderRegSho204AndShowsExactlyItsRows() {
        Played p = Played.seeded();
        MarketCalendar cal = new MarketCalendar();
        List<Map<String, Object>> failed = p.of(Feed.SETTLEMENTS).stream()
            .filter(s -> longOf(s.get("quantity_failed")) > 0).toList();
        assertThat(failed).hasSize(1);
        Map<String, Object> settlement = failed.getFirst();
        LocalDate s = (LocalDate) settlement.get("settlement_date");
        assertThat(s).isAfterOrEqualTo(p.days.get(2));
        assertThat(settlement.get("direction")).isEqualTo("deliver");
        assertThat(settlement.get("status")).isEqualTo("partially_settled");
        long q = longOf(settlement.get("quantity_failed"));
        long security = longOf(settlement.get("security_id"));

        List<Map<String, Object>> fails = p.of(Feed.FAILS);
        assertThat(fails).extracting(f -> f.get("event_type"))
            .containsExactly("opened", "aged", "closed_out", "delivered");
        assertThat(fails).extracting(f -> f.get("fail_id")).containsOnly(fails.getFirst().get("fail_id"));
        assertThat(fails).allSatisfy(f -> {
            assertThat(f.get("quantity")).isEqualTo(q);
            assertThat(f.get("reg_sho_rule")).isEqualTo("204(a)");
            assertThat(f.get("obligation_id")).isEqualTo(settlement.get("obligation_id"));
        });
        LocalDate closeOutDay = cal.next(s);
        assertThat(fails).extracting(f -> f.get("business_date"))
            .containsExactly(s, closeOutDay, closeOutDay, cal.settlement(closeOutDay));
        Instant deadline = closeOutDay.atTime(LocalTime.of(9, 30)).atZone(World.NEW_YORK).toInstant();
        assertThat(fails.get(2).get("event_time")).isEqualTo(deadline);
        assertThat(fails.getFirst().get("close_out_deadline")).isEqualTo(deadline);
        BigDecimal cost = (BigDecimal) fails.get(2).get("close_out_amount");
        assertThat(cost).isEqualByComparingTo(((BigDecimal) fails.get(2).get("close_out_price")).multiply(BigDecimal.valueOf(q)));

        // The obligation that failed held a customer short sale of at least the failed shares.
        Map<String, Object> obligation = p.of(Feed.CNS_OBLIGATIONS).stream()
            .filter(c -> c.get("obligation_id").equals(settlement.get("obligation_id"))).findFirst().orElseThrow();
        assertThat(longOf(obligation.get("short_sale_quantity"))).isGreaterThanOrEqualTo(q);

        // Tie 4 shows the close-out alone: shares bought, money paid, on its trade date.
        Map<Key, BigDecimal[]> breaks = cnsLessFills(p);
        assertThat(breaks).containsOnlyKeys(new Key(closeOutDay, security));
        BigDecimal[] diff = breaks.get(new Key(closeOutDay, security));
        assertThat(diff[0]).isEqualByComparingTo(BigDecimal.valueOf(q));
        assertThat(diff[1]).isEqualByComparingTo(cost.negate());

        // The stock record holds the fail at the ends of the settlement date and the close-out day, and the firm's
        // shares from the close-out on.
        for (Map<String, Object> r : p.of(Feed.STOCK_RECORD)) {
            LocalDate d = (LocalDate) r.get("business_date");
            if (r.get("category").equals("fail_to_deliver")) {
                assertThat(d).isIn(s, closeOutDay);
                assertThat((BigDecimal) r.get("quantity")).isEqualByComparingTo(BigDecimal.valueOf(-q));
            }
            if (r.get("category").equals("firm")) {
                assertThat(d).isAfterOrEqualTo(closeOutDay);
                assertThat((BigDecimal) r.get("quantity")).isEqualByComparingTo(BigDecimal.valueOf(q));
            }
        }
        assertThat(p.of(Feed.STOCK_RECORD)).filteredOn(r -> r.get("category").equals("fail_to_deliver")).hasSize(2);
    }

    /**
     * Tie 1, and the stock record's own balance: each customer's position in it is the ledger's Share Position less
     * Short Position and the open lots' shares, and per security and day its owners add up to its locations.
     */
    @Test
    void theStockRecordTiesToTheLedgersPositionsAndLotsAndItsOwnersToItsLocations() {
        for (Played p : List.of(Played.seeded(), Played.clean())) {
            Map<String, BigDecimal> ledger = new TreeMap<>(), lots = new TreeMap<>(), record = new TreeMap<>();
            for (Map<String, Object> b : p.of(Feed.BALANCES_EOD)) {
                int def = (Integer) b.get("definition_id");
                BigDecimal v = (BigDecimal) b.get("balance");
                String k = b.get("business_date") + "/" + b.get("brokerage_account") + "/" + b.get("security_id");
                switch (def) {
                    case 3002 -> ledger.merge(k, v, BigDecimal::add);
                    case 3102 -> ledger.merge(k, v.negate(), BigDecimal::add);
                    case 3001, 3103 -> lots.merge(k, v, BigDecimal::add);
                    case 3003, 3101 -> lots.merge(k, v.negate(), BigDecimal::add);
                    default -> {
                    }
                }
            }
            Map<String, BigDecimal> balance = new TreeMap<>();
            Set<String> days = new HashSet<>();
            for (Map<String, Object> r : p.of(Feed.STOCK_RECORD)) {
                BigDecimal q = (BigDecimal) r.get("quantity");
                days.add(r.get("business_date").toString());
                if (r.get("category").equals("customer")) {
                    record.put(r.get("business_date") + "/" + r.get("brokerage_account") + "/" + r.get("security_id"), q);
                }
                balance.merge(r.get("business_date") + "/" + r.get("security_id"),
                    r.get("side").equals("owner") ? q : q.negate(), BigDecimal::add);
                assertThat((BigDecimal) r.get("market_value")).isEqualByComparingTo(
                    ((BigDecimal) r.get("close_price")).multiply(q).setScale(2, RoundingMode.HALF_UP));
            }
            ledger.values().removeIf(v -> v.signum() == 0);
            lots.values().removeIf(v -> v.signum() == 0);
            assertThat(record).hasSizeGreaterThan(1000);
            assertThat(days).hasSize(Played.DAYS);
            assertThat(differences(ledger, record)).as("Share Position less Short Position against the stock record")
                .isEmpty();
            assertThat(differences(lots, record)).as("the open lots against the stock record").isEmpty();
            assertThat(balance).allSatisfy((k, v) -> assertThat(v).as("owners less locations of " + k).isZero());
        }
        // The positions moved by more than fills: ACATS transfers in and out, and a split.
        assertThat(Played.seeded().of(Feed.FINANCIAL_TRANSACTIONS)).extracting(ft -> ft.get("template_id"))
            .contains(83, 84, 86);
    }

    /** Each key whose value differs between the two, with both values (a missing one as 0). */
    private static Map<String, String> differences(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        Map<String, String> out = new TreeMap<>();
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            BigDecimal x = a.getOrDefault(k, BigDecimal.ZERO), y = b.getOrDefault(k, BigDecimal.ZERO);
            if (x.compareTo(y) != 0) {
                out.put(k, x.toPlainString() + " against " + y.toPlainString());
            }
        }
        return out;
    }
}
