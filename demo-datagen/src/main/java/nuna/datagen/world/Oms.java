package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import nuna.datagen.world.Market.Security;

/**
 * The order management system's records of the world's orders (item 151): an event for each change of an order, a
 * route for each venue it is sent to, and an execution for each fill the ledger books (kit templates 41, 44, 61 and 64),
 * carrying that fill's id, price and quantity. It draws nothing at random: a route follows its fill's venue, which the
 * world drew, so the world's other feeds are the same with or without it.
 */
final class Oms {

    /** An order as the world holds it after a change. */
    record OrderState(long id, String ref, long account, Security security, String side, String type,
                      BigDecimal limitPrice, long quantity, long filled, BigDecimal value, String status, String locate,
                      LocalDate tradeDate) {
        BigDecimal averagePrice() {
            return filled == 0 ? null : value.divide(BigDecimal.valueOf(filled), 6, RoundingMode.HALF_UP);
        }
    }

    /** How long before its first fill an order reaches a venue. */
    private static final long ROUTE_LEAD_MILLIS = 250;

    private final Emitter emitter;
    private long nextEvent = 1, nextRoute = 1, nextExecution = 1;
    /** Per open order, the route to each venue it was sent to, and the time of its last event. */
    private final Map<Long, Map<String, String>> routes = new HashMap<>();
    private final Map<Long, Instant> lastEvent = new HashMap<>();

    Oms(Emitter emitter) {
        this.emitter = emitter;
    }

    void accepted(Instant at, OrderState o) {
        event(at, o, "accepted", null, null);
    }

    /**
     * A fill the ledger booked: the route to its venue if the order has none there yet, the execution, and the order's
     * event (partially filled or filled).
     */
    void executed(Instant at, OrderState o, String venue, long quantity, BigDecimal price, String fillId,
                  long financialTransaction, LocalDate settlementDate) {
        Map<String, String> byVenue = routes.computeIfAbsent(o.id(), k -> new HashMap<>());
        String route = byVenue.get(venue);
        long leavesBefore = o.quantity() - (o.filled() - quantity);
        if (route == null) {
            route = "RTE-" + (60_000_000 + nextRoute++);
            byVenue.put(venue, route);
            Instant sent = at.minusMillis(ROUTE_LEAD_MILLIS);
            Instant previous = lastEvent.get(o.id());
            if (previous != null && sent.isBefore(previous)) {
                sent = previous;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("route_id", route);
            row.put("order_id", o.id());
            row.put("order_ref", o.ref());
            row.put("brokerage_account", o.account());
            row.put("security_id", o.security().id());
            row.put("symbol", o.security().symbol());
            row.put("side", o.side());
            row.put("venue", venue);
            row.put("routed_quantity", leavesBefore);
            row.put("limit_price", o.limitPrice());
            row.put("trade_date", o.tradeDate());
            row.put("sent_at", sent);
            emitter.emit(at, Feed.ROUTES, row);
            // The order as it stood when it was routed, before this fill.
            long filledBefore = o.filled() - quantity;
            event(at, new OrderState(o.id(), o.ref(), o.account(), o.security(), o.side(), o.type(), o.limitPrice(),
                o.quantity(), filledBefore, o.value().subtract(price.multiply(BigDecimal.valueOf(quantity))),
                filledBefore == 0 ? "accepted" : "partially_filled", o.locate(), o.tradeDate()), "routed", route, null);
        }
        String exec = "EXE-" + (80_000_000 + nextExecution++);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("exec_id", exec);
        row.put("order_id", o.id());
        row.put("order_ref", o.ref());
        row.put("route_id", route);
        row.put("fill_id", fillId);
        row.put("financial_transaction_id", financialTransaction);
        row.put("brokerage_account", o.account());
        row.put("security_id", o.security().id());
        row.put("symbol", o.security().symbol());
        row.put("side", o.side());
        row.put("venue", venue);
        row.put("last_quantity", quantity);
        row.put("last_price", price);
        row.put("gross_amount", price.multiply(BigDecimal.valueOf(quantity)));
        row.put("cum_quantity", o.filled());
        row.put("leaves_quantity", o.quantity() - o.filled());
        row.put("trade_date", o.tradeDate());
        row.put("settlement_date", settlementDate);
        row.put("executed_at", at);
        emitter.emit(at, Feed.EXECUTIONS, row);
        event(at, o, o.filled() == o.quantity() ? "filled" : "partially_filled", route, exec);
        if (o.filled() == o.quantity()) {
            forget(o.id());
        }
    }

    /** An order the close expired with shares left unfilled. */
    void expired(Instant at, OrderState o) {
        event(at, o, "expired", null, null);
        forget(o.id());
    }

    /** An execution the venue broke after the day (item 153): the order as it stands without it. */
    void busted(Instant at, OrderState o) {
        event(at, o, "trade_busted", null, null);
    }

    private void forget(long order) {
        routes.remove(order);
        lastEvent.remove(order);
    }

    private void event(Instant at, OrderState o, String type, String route, String exec) {
        lastEvent.put(o.id(), at);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", nextEvent++);
        row.put("order_id", o.id());
        row.put("order_ref", o.ref());
        row.put("event_type", type);
        row.put("brokerage_account", o.account());
        row.put("security_id", o.security().id());
        row.put("symbol", o.security().symbol());
        row.put("side", o.side());
        row.put("order_type", o.type());
        row.put("limit_price", o.limitPrice());
        row.put("quantity", o.quantity());
        row.put("filled_quantity", o.filled());
        row.put("leaves_quantity", type.equals("expired") || type.equals("trade_busted") ? 0L : o.quantity() - o.filled());
        row.put("average_price", o.averagePrice());
        row.put("status", o.status());
        row.put("locate_id", o.locate());
        row.put("route_id", route);
        row.put("exec_id", exec);
        row.put("trade_date", o.tradeDate());
        row.put("event_time", at);
        emitter.emit(at, Feed.ORDER_EVENTS, row);
    }
}
