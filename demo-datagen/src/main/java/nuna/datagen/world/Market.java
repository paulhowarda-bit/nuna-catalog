package nuna.datagen.world;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The synthetic securities and their prices: about 300 stocks and ETFs, priced by a seeded random walk in five-minute
 * steps with a market factor, a sector factor and each security's own noise.
 */
public final class Market {

    public static final List<String> SECTORS = List.of("Information Technology", "Health Care", "Financials",
        "Consumer Discretionary", "Industrials", "Communication Services", "Consumer Staples", "Energy", "Utilities",
        "Real Estate", "Materials");

    private static final List<String> FIRST = List.of("Arbor", "Beacon", "Cinder", "Delta", "Ember", "Fable", "Granite",
        "Harbor", "Ion", "Juniper", "Kestrel", "Lumen", "Meridian", "Nimbus", "Onyx", "Pinnacle", "Quarry", "Rowan",
        "Summit", "Tidal", "Umber", "Vantage", "Willow", "Xenial", "Yarrow", "Zephyr", "Alder", "Basalt", "Corvid",
        "Dune", "Estuary", "Fjord", "Glacier", "Heron", "Inlet", "Jasper", "Kiln", "Larch", "Moraine", "Nunatak");
    private static final List<String> SECOND = List.of("Systems", "Health", "Capital", "Brands", "Industries", "Media",
        "Foods", "Energy", "Power", "Realty", "Materials", "Labs", "Networks", "Therapeutics", "Bancorp", "Motors",
        "Logistics", "Devices", "Software", "Resources");
    private static final List<String> SUFFIX = List.of("Inc.", "Corp.", "Holdings Inc.", "Group Inc.", "Co.");

    /** A security of the instrument master. Instrument ids start at 100001 (840 is USD's). */
    public record Security(long id, String symbol, String name, String cusip, String isin, String type, String sector,
                           double beta, double sigma) {}

    /** One five-minute bar. */
    public record Bar(Security security, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}

    private final List<Security> securities = new ArrayList<>();
    private final double[] price;
    private final double[] popularity;
    private final Rng rng;

    /** Bars per regular session: 09:30 to 16:00. */
    public static final int BARS = 78;
    /** Bars per trade date outside it: overnight 21:00 to 04:00, pre-market to 09:30, post-market 16:00 to 20:00. */
    public static final int THIN_BARS = 84 + 66 + 48;

    Market(Rng rng, int count) {
        this.rng = rng;
        Set<String> symbols = new HashSet<>();
        int etfs = Math.max(1, count / 10);
        for (int i = 0; i < count; i++) {
            boolean etf = i >= count - etfs;
            String sector = SECTORS.get(i % SECTORS.size());
            String name;
            String symbol;
            do {
                if (etf) {
                    name = "Nuna Demo " + sector + " Index ETF";
                    if (symbols.contains(symbolOf(name, 0))) {
                        name = "Nuna Demo " + rng.pick(FIRST) + " " + sector + " ETF";
                    }
                } else {
                    name = rng.pick(FIRST) + " " + rng.pick(SECOND) + " " + rng.pick(SUFFIX);
                }
                symbol = symbolOf(name, rng.below(3));
            } while (symbols.contains(symbol));
            symbols.add(symbol);
            String cusip = Identifiers.cusip(i);
            double beta = etf ? 1.0 : 0.6 + rng.uniform() * 0.9;
            double sigma = etf ? 0.008 : 0.012 + rng.uniform() * 0.02;
            securities.add(new Security(100001 + i, symbol, name, cusip, Identifiers.isin(cusip), etf ? "ETF" : "Common Stock",
                sector, beta, sigma));
        }
        price = new double[count];
        popularity = new double[count];
        double cum = 0;
        for (int i = 0; i < count; i++) {
            price[i] = Math.round(rng.lognormal(60, 0.9) * 100) / 100.0 + 2;
            cum += 1 / Math.pow(i + 1, 0.9);
            popularity[i] = cum;
        }
    }

    private static String symbolOf(String name, int variant) {
        String[] w = name.replace("Nuna Demo ", "").split(" ");
        String a = w[0].toUpperCase(), b = w.length > 1 ? w[1].toUpperCase() : "X";
        return switch (variant) {
            case 0 -> a.substring(0, Math.min(3, a.length())) + b.charAt(0);
            case 1 -> a.substring(0, Math.min(2, a.length())) + b.substring(0, Math.min(2, b.length()));
            default -> a.charAt(0) + b.substring(0, Math.min(3, b.length()));
        };
    }

    public List<Security> securities() {
        return securities;
    }

    public Security byIndex(int i) {
        return securities.get(i);
    }

    /** A security drawn by popularity. */
    Security popular() {
        return securities.get(rng.zipf(securities.size(), 0.9, popularity));
    }

    public int index(Security s) {
        return (int) (s.id() - 100001);
    }

    /** The current price, to the cent. */
    public BigDecimal price(Security s) {
        return BigDecimal.valueOf(price[index(s)]).setScale(2, RoundingMode.HALF_UP);
    }

    /** A split of ratioNew for ratioOld on its ex-date, before the open: the price is divided by the ratio. */
    void split(Security s, long ratioNew, long ratioOld) {
        int i = index(s);
        price[i] = Math.max(0.01, Math.round(price[i] * ratioOld / ratioNew * 100) / 100.0);
    }

    /**
     * The securities that trade outside the regular session (item 321): the most popular fifth, at least 20, as the
     * overnight market's liquidity sits in the names most traded by day.
     */
    public static int thinCount(int securities) {
        return Math.min(securities, Math.max(20, securities / 5));
    }

    /** Whether a security trades outside the regular session. */
    boolean tradesThin(Security s) {
        return index(s) < thinCount(securities.size());
    }

    /** A security that trades outside the regular session, drawn by popularity. */
    Security popularThin() {
        return securities.get(rng.zipf(thinCount(securities.size()), 0.9, popularity));
    }

    /** Moves every price one five-minute step of the regular session and returns the step's bars. */
    List<Bar> step() {
        return step(securities.size(), 1.0, 1.0);
    }

    /**
     * A five-minute step outside the regular session: only the securities that trade there move, by about a third as
     * much, on the session's share of the regular session's volume.
     */
    List<Bar> stepThin(double volume) {
        return step(thinCount(securities.size()), 0.35, volume);
    }

    private List<Bar> step(int count, double move, double volumeShare) {
        double market = rng.normal() * 0.0012 * move;
        double[] sector = new double[SECTORS.size()];
        for (int k = 0; k < sector.length; k++) {
            sector[k] = rng.normal() * 0.0008 * move;
        }
        List<Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Security s = securities.get(i);
            double open = price[i];
            double ret = s.beta() * market + sector[SECTORS.indexOf(s.sector())]
                + rng.normal() * s.sigma() / Math.sqrt(BARS) * move;
            double close = Math.max(0.5, open * Math.exp(ret));
            double high = Math.max(open, close) * (1 + Math.abs(rng.normal()) * 0.0008);
            double low = Math.min(open, close) * (1 - Math.abs(rng.normal()) * 0.0008);
            price[i] = Math.round(close * 100) / 100.0;
            long volume = Math.max(1, Math.round(rng.lognormal(20000 / Math.sqrt(i + 1.0), 0.6) * volumeShare)) * 10;
            bars.add(new Bar(s, cents4(open), cents4(high), cents4(low), cents4(price[i]), volume));
        }
        return bars;
    }

    private static BigDecimal cents4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
