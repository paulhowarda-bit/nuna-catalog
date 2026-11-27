package nuna.datagen.world;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import nuna.datagen.world.Market.Security;

/**
 * The digital channels (item 154): sessions on the web site and the two apps, and their click events, about 0.35
 * sessions a business day per brokerage account (350 at 1,000 accounts, the design's 200 to 500), active traders and
 * short sellers far more often than dormant accounts, one session in ten a visitor who never logs in. A session starts
 * between 07:00 and 16:30, most around the open, and its last event is before 17:00, when the world's day ends. Each
 * event goes out as it happens; the session's own row goes out as it ends, with what it held. It draws from a source of
 * chance of its own, so the rest of the world draws as before.
 */
final class Digital {

    static final LocalTime FIRST = LocalTime.of(7, 0), LAST_START = LocalTime.of(16, 30),
        CLOSE = LocalTime.of(16, 59, 59);
    static final double SESSIONS_PER_ACCOUNT = 0.35;

    private static final List<String> PAGES = List.of("home", "portfolio", "positions", "activity", "balances",
        "research", "news", "transfers", "statements", "tax_documents", "settings", "watchlist");
    private static final List<String> PUBLIC_PAGES = List.of("home", "pricing", "open_account", "research", "help",
        "login");
    private static final List<String> ELEMENTS = List.of("nav_portfolio", "nav_trade", "nav_research",
        "chart_range_1d", "chart_range_1y", "watchlist_add", "news_open", "alerts_bell", "dark_mode_toggle");

    private final List<World.Account> accounts;
    private final List<Security> securities;
    private final Rng rng;
    private final Emitter out;
    private final double[] accountWeights;
    private final double[] securityWeights;
    private long nextSession = 1, nextEvent = 1;

    Digital(List<World.Account> accounts, List<Security> securities, Rng rng, Emitter out) {
        this.accounts = accounts;
        this.securities = securities;
        this.rng = rng;
        this.out = out;
        accountWeights = new double[accounts.size()];
        double c = 0;
        for (int i = 0; i < accounts.size(); i++) {
            c += switch (accounts.get(i).persona()) {
                case "dormant" -> 0.1;
                case "buy_and_hold" -> 0.6;
                default -> 2.0;
            };
            accountWeights[i] = c;
        }
        securityWeights = new double[securities.size()];
        c = 0;
        for (int i = 0; i < securities.size(); i++) {
            c += 1 / Math.pow(i + 1, 0.9);
            securityWeights[i] = c;
        }
    }

    /** Plans the day's sessions, each a task at its start. */
    void plan(LocalDate day, Function<LocalTime, Instant> at, BiConsumer<Instant, Runnable> schedule) {
        double mean = accounts.size() * SESSIONS_PER_ACCOUNT * (0.8 + 0.4 * rng.uniform());
        int sessions = (int) Math.max(0, Math.round(mean + Math.sqrt(mean) * rng.normal()));
        for (int i = 0; i < sessions; i++) {
            LocalTime start = startTime();
            schedule.accept(at.apply(start), () -> session(day, start, at, schedule));
        }
    }

    /** Most sessions come around the open, more at lunch and before the close, the rest spread over the day. */
    private LocalTime startTime() {
        double u = rng.uniform();
        int second;
        if (u < 0.30) {
            second = 9 * 3600 + 30 * 60 + (int) Math.abs(rng.normal() * 45 * 60);
        } else if (u < 0.45) {
            second = 12 * 3600 + rng.below(3600);
        } else if (u < 0.60) {
            second = 15 * 3600 + rng.below(3600);
        } else {
            second = FIRST.toSecondOfDay() + rng.below(LAST_START.toSecondOfDay() - FIRST.toSecondOfDay());
        }
        return LocalTime.ofSecondOfDay(Math.min(second, LAST_START.toSecondOfDay()));
    }

    /** A session from its start: draws its events, each sent at its time, and its own row, sent at its end. */
    private void session(LocalDate day, LocalTime start, Function<LocalTime, Instant> at,
                         BiConsumer<Instant, Runnable> schedule) {
        long id = nextSession++;
        boolean visitor = rng.chance(0.1);
        World.Account account = visitor ? null : accounts.get(rng.zipf(accounts.size(), 0, accountWeights));
        double c = rng.uniform();
        String channel = c < 0.45 ? "web" : c < 0.80 ? "ios" : "android";
        String os = switch (channel) {
            case "web" -> rng.pick(List.of("Windows", "Windows", "macOS", "macOS", "Linux", "ChromeOS"));
            case "ios" -> "iOS";
            default -> "Android";
        };
        String client = channel.equals("web")
            ? rng.pick(List.of("Chrome 142", "Chrome 142", "Safari 26", "Edge 142", "Firefox 145"))
            : "app " + rng.pick(List.of("7.14.2", "7.14.2", "7.14.1", "7.13.0"));
        int n = visitor ? 1 + (int) Math.round(rng.lognormal(3, 0.6))
            : 2 + (int) Math.min(78, Math.round(rng.lognormal(10, 0.8)));
        List<Map<String, Object>> events = new ArrayList<>();
        int second = start.toSecondOfDay();
        int pageViews = 0;
        for (int i = 0; i < n && second <= CLOSE.toSecondOfDay(); i++) {
            Map<String, Object> e = event(id, account, visitor, i, n);
            if ("page_view".equals(e.get("event_type")) || "quote_view".equals(e.get("event_type"))) {
                pageViews++;
            }
            e.put("business_date", day);
            e.put("event_time", at.apply(LocalTime.ofSecondOfDay(second)));
            events.add(e);
            second += 1 + (int) Math.round(rng.lognormal(18, 0.9));
        }
        Map<String, Object> last = events.getLast();
        Instant end = (Instant) last.get("event_time");
        for (Map<String, Object> e : events) {
            Instant when = (Instant) e.get("event_time");
            schedule.accept(when, () -> {
                e.put("event_id", nextEvent++);
                out.emit(when, Feed.CLICK_EVENTS, reorder(e));
            });
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", id);
        row.put("brokerage_account", account == null ? null : account.id());
        row.put("logged_in", account != null);
        row.put("channel", channel);
        row.put("device_os", os);
        row.put("client", client);
        row.put("started_at", at.apply(start));
        row.put("ended_at", end);
        row.put("ended_by", "logout".equals(last.get("event_type")) ? "logout" : "timeout");
        row.put("page_views", pageViews);
        row.put("events", events.size());
        row.put("business_date", day);
        schedule.accept(end, () -> out.emit(end, Feed.SESSIONS, row));
    }

    /** The i-th of a session's n events: a login first and, half the time, a logout last; a visitor only browses. */
    private Map<String, Object> event(long session, World.Account account, boolean visitor, int i, int n) {
        String type, page = null, element = null;
        Security security = null;
        if (visitor) {
            type = "page_view";
            page = rng.pick(PUBLIC_PAGES);
        } else if (i == 0) {
            type = "login";
            page = "login";
        } else if (i == n - 1 && rng.chance(0.5)) {
            type = "logout";
            element = "sign_out";
        } else {
            double u = rng.uniform();
            if (u < 0.40) {
                type = "page_view";
                page = rng.pick(PAGES);
            } else if (u < 0.65) {
                type = "quote_view";
                page = "quote";
                security = popular();
            } else if (u < 0.75) {
                type = "search";
                page = "research";
                element = "search_box";
            } else if (u < 0.85) {
                type = "order_ticket_open";
                page = "trade";
                element = rng.chance(0.5) ? "buy_button" : "sell_button";
                security = popular();
            } else {
                type = "click";
                page = rng.pick(PAGES);
                element = rng.pick(ELEMENTS);
            }
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("session_id", session);
        e.put("brokerage_account", account == null ? null : account.id());
        e.put("event_type", type);
        e.put("page", page);
        e.put("element", element);
        e.put("security_id", security == null ? null : security.id());
        e.put("symbol", security == null ? null : security.symbol());
        return e;
    }

    private Security popular() {
        return securities.get(rng.zipf(securities.size(), 0.9, securityWeights));
    }

    /** The event's row in its table's column order, its id first. */
    private static Map<String, Object> reorder(Map<String, Object> e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", e.get("event_id"));
        e.forEach((k, v) -> {
            if (!k.equals("event_id")) {
                row.put(k, v);
            }
        });
        return row;
    }
}
