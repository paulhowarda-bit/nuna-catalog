package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Trade reporting (item 153), read from the order management system's own rows as they are sent, so it draws nothing
 * at random: the CAT events (SEC Rule 613, the CAT NMS Plan), the confirms (SEC Rule 10b-10), and each month the Rule
 * 606(a)(1) routing report and the best-execution review (FINRA Rule 5310).
 *
 * <p>CAT. The firm, an industry member with the synthetic CAT reporter id {@code WRLD-1}, reports each new order it
 * accepts (MENO) and each route to a venue (MEOR); the venue, a plan participant, reports the trade. Each customer's
 * account has the synthetic FDID {@code FDID-<account>}. An order a day order's expiry ends is not reported again (a
 * choice made here). Tie 9 holds each accepted order, route and execution to exactly one CAT event.
 *
 * <p>Rule 606. Orders are held non-directed and routed by the firm: market orders and marketable limit orders (every
 * limit order the world places is marketable when routed), by security class: {@code large_cap_nms}, the hundred most
 * popular securities standing in for the index the rule names, and {@code other_nms}. The firm takes no payment for
 * order flow and pays each venue an access fee of $0.30 a hundred shares executed (a choice made here).
 *
 * <p>Best execution. Each month, per venue and order type: executions, shares against shares routed, the price
 * improvement a share against a synthetic quote (half-spread 2 to 8 basis points of the price, at least a cent, the
 * improvement a fixed share of it by execution), and the time from route to execution; venues are ranked by
 * improvement and those below the month's average are flagged for review.
 */
final class Reporting {

    static final String REPORTER = "WRLD-1";
    private static final BigDecimal ACCESS_FEE = new BigDecimal("0.30");

    private static final class Routing {
        long orders, shares, executed;
        BigDecimal paid = BigDecimal.ZERO;
    }

    private static final class Review {
        long executions, shares, routed, millis;
        BigDecimal improvement = BigDecimal.ZERO;
    }

    private record Route(String id, Instant sent, String category, String venue, String securityClass,
                         String orderType) {}

    private final Emitter emitter;
    private final Map<String, Route> routes = new HashMap<>();
    private final Map<List<String>, Routing> routing = new TreeMap<>(Comparator.comparing(Object::toString));
    private final Map<List<String>, Review> reviews = new TreeMap<>(Comparator.comparing(Object::toString));
    private final Map<Long, String> confirmed = new HashMap<>();
    private long nextCat = 1, nextConfirm = 1;

    Reporting(Emitter emitter) {
        this.emitter = emitter;
    }

    static String securityClass(long security) {
        return security - 100001 < 100 ? "large_cap_nms" : "other_nms";
    }

    /** A row the order management system sent: its CAT event, and what the month's reports count. */
    void observe(Instant at, Feed feed, Map<String, Object> row) {
        switch (feed) {
            case ORDER_EVENTS -> {
                if ("accepted".equals(row.get("event_type"))) {
                    cat(at, at, "MENO", "industry_member", REPORTER, row, null, null, row.get("limit_price"),
                        row.get("quantity"), null);
                }
            }
            case ROUTES -> {
                String category = row.get("limit_price") == null ? "market" : "marketable_limit";
                long security = (Long) row.get("security_id");
                Route r = new Route((String) row.get("route_id"), (Instant) row.get("sent_at"), category,
                    (String) row.get("venue"), securityClass(security), category);
                routes.put(r.id(), r);
                cat(at, (Instant) row.get("sent_at"), "MEOR", "industry_member", REPORTER, row, r.id(), null,
                    row.get("limit_price"), row.get("routed_quantity"), r.venue());
                long q = (Long) row.get("routed_quantity");
                Routing g = routing.computeIfAbsent(List.of(r.securityClass(), category, r.venue()), k -> new Routing());
                g.orders++;
                g.shares += q;
                reviews.computeIfAbsent(List.of(r.venue(), category), k -> new Review()).routed += q;
            }
            case EXECUTIONS -> {
                Route r = routes.get((String) row.get("route_id"));
                cat(at, at, "trade", "participant", r.venue(), row, r.id(), (String) row.get("exec_id"),
                    row.get("last_price"), row.get("last_quantity"), r.venue());
                long q = (Long) row.get("last_quantity");
                Routing g = routing.get(List.of(r.securityClass(), r.category(), r.venue()));
                g.executed += q;
                g.paid = g.paid.add(ACCESS_FEE.multiply(BigDecimal.valueOf(q)).movePointLeft(2));
                Review v = reviews.get(List.of(r.venue(), r.orderType()));
                v.executions++;
                v.shares += q;
                v.millis += Duration.between(r.sent(), at).toMillis();
                BigDecimal price = (BigDecimal) row.get("last_price");
                long security = (Long) row.get("security_id");
                BigDecimal half = price.multiply(BigDecimal.valueOf(2 + Math.floorMod(security, 7)))
                    .movePointLeft(4).max(new BigDecimal("0.01"));
                long execNumber = Long.parseLong(((String) row.get("exec_id")).substring(4));
                BigDecimal share = BigDecimal.valueOf(Math.floorMod(execNumber * 7919 + r.venue().hashCode(), 6), 1);
                v.improvement = v.improvement.add(half.multiply(share).multiply(BigDecimal.valueOf(q)));
            }
            default -> {
            }
        }
    }

    private void cat(Instant at, Instant stamp, String type, String reporterType, String reporter, Map<String, Object> row,
                     String route, String exec, Object price, Object quantity, String destination) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("cat_event_id", nextCat++);
        c.put("event_type", type);
        c.put("reporter_type", reporterType);
        c.put("reporter_id", reporter);
        c.put("fdid", "FDID-" + row.get("brokerage_account"));
        c.put("order_id", row.get("order_id"));
        c.put("order_ref", row.get("order_ref"));
        c.put("route_id", route);
        c.put("exec_id", exec);
        c.put("security_id", row.get("security_id"));
        c.put("symbol", row.get("symbol"));
        c.put("side", row.get("side"));
        c.put("order_type", row.get("order_type") != null ? row.get("order_type")
            : row.get("limit_price") == null ? "market" : "limit");
        c.put("price", price);
        c.put("quantity", quantity);
        c.put("time_in_force", "DAY");
        c.put("destination", destination);
        c.put("trade_date", row.get("trade_date"));
        c.put("event_timestamp", stamp);
        c.put("business_date", row.get("trade_date"));
        emitter.emit(at, Feed.CAT_EVENTS, c);
    }

    /**
     * A confirm (Rule 10b-10) for an order done for the day: filled, or expired with shares filled. A bust sends a
     * corrected confirm for what is left, or a cancellation when nothing is.
     */
    void confirm(Instant at, LocalDate day, long order, String ref, long account, Market.Security s, String side,
                 LocalDate tradeDate, LocalDate settlementDate, long quantity, BigDecimal value, BigDecimal secFee,
                 BigDecimal taf, int executions, String venue, String status) {
        String id = "CNF-" + (20_000_000 + nextConfirm++);
        String corrects = confirmed.put(order, id);
        boolean buy = side.equals("buy") || side.equals("buy_to_cover");
        BigDecimal fees = secFee.add(taf);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("confirm_id", id);
        row.put("status", status);
        row.put("corrects_confirm_id", corrects);
        row.put("order_id", order);
        row.put("order_ref", ref);
        row.put("brokerage_account", account);
        row.put("security_id", s.id());
        row.put("symbol", s.symbol());
        row.put("cusip", s.cusip());
        row.put("side", side);
        row.put("capacity", "agency");
        row.put("trade_date", tradeDate);
        row.put("settlement_date", settlementDate);
        row.put("quantity", quantity);
        row.put("average_price", quantity == 0 ? null
            : value.divide(BigDecimal.valueOf(quantity), 6, RoundingMode.HALF_UP));
        row.put("principal", value.setScale(2, RoundingMode.HALF_UP));
        row.put("sec_fee", secFee);
        row.put("finra_taf", taf);
        row.put("net_amount", (buy ? value.add(fees) : value.subtract(fees)).setScale(2, RoundingMode.HALF_UP));
        row.put("executions", executions);
        row.put("venue", venue);
        row.put("business_date", day);
        row.put("event_time", at);
        emitter.emit(at, Feed.CONFIRMS, row);
    }

    /** The month's Rule 606 rows and best-execution review, for the close; the month's counts then start again. */
    void close(YearMonth period, LocalDate periodEnd, List<Map<String, Object>> rule606,
               List<Map<String, Object>> bestEx) {
        Map<List<String>, Long> perCategory = new HashMap<>();
        routing.forEach((k, g) -> perCategory.merge(List.of(k.get(0), k.get(1)), g.orders, Long::sum));
        routing.forEach((k, g) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period.toString());
            row.put("security_class", k.get(0));
            row.put("order_category", k.get(1));
            row.put("venue", k.get(2));
            row.put("orders_routed", g.orders);
            row.put("percent_of_orders", BigDecimal.valueOf(g.orders * 100)
                .divide(BigDecimal.valueOf(perCategory.get(List.of(k.get(0), k.get(1)))), 6, RoundingMode.HALF_UP));
            row.put("shares_routed", g.shares);
            row.put("net_payment_paid", g.paid.setScale(2, RoundingMode.HALF_UP));
            row.put("net_payment_paid_per_hundred", g.executed == 0 ? BigDecimal.ZERO.setScale(6)
                : g.paid.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(g.executed), 6,
                RoundingMode.HALF_UP));
            row.put("net_payment_received", BigDecimal.ZERO.setScale(2));
            rule606.add(row);
        });
        List<Map.Entry<List<String>, Review>> ranked = new ArrayList<>(reviews.entrySet());
        ranked.removeIf(e -> e.getValue().executions == 0);
        BigDecimal total = BigDecimal.ZERO;
        long shares = 0;
        for (var e : ranked) {
            total = total.add(e.getValue().improvement);
            shares += e.getValue().shares;
        }
        BigDecimal average = shares == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(shares), 6,
            RoundingMode.HALF_UP);
        ranked.sort(Comparator.comparing((Map.Entry<List<String>, Review> e) -> perShare(e.getValue())).reversed()
            .thenComparing(e -> e.getKey().toString()));
        int rank = 0;
        for (var e : ranked) {
            Review v = e.getValue();
            BigDecimal improvement = perShare(v);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period.toString());
            row.put("venue", e.getKey().get(0));
            row.put("order_type", e.getKey().get(1));
            row.put("executions", v.executions);
            row.put("shares", v.shares);
            row.put("routed_shares", v.routed);
            row.put("fill_rate", BigDecimal.valueOf(v.shares).divide(BigDecimal.valueOf(Math.max(1, v.routed)), 6,
                RoundingMode.HALF_UP));
            row.put("average_improvement_per_share", improvement);
            row.put("average_time_to_execution_ms", v.millis / v.executions);
            row.put("rank", ++rank);
            row.put("finding", improvement.compareTo(average) >= 0 ? "meets_benchmark" : "review_routing");
            row.put("reviewed_on", periodEnd);
            bestEx.add(row);
        }
        routing.clear();
        reviews.clear();
        routes.clear();
    }

    private static BigDecimal perShare(Review v) {
        return v.improvement.divide(BigDecimal.valueOf(v.shares), 6, RoundingMode.HALF_UP);
    }
}
