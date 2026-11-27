package nuna.datagen.kit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The demo kit: the chart of accounts and the booking schemes the posting engine runs, as data. Forked once from
 * ledger kit v14 ({@code defs.py}, {@code schemes_v20.py}) and the demo's own from then on (D48 as amended: the ledger
 * kit serves the sequencer; this one only fills Iceberg). A booking scheme is selected by template alone.
 */
public final class Kit {

    public enum Side {
        Debit, Credit;

        public Side flip() {
            return this == Debit ? Credit : Debit;
        }
    }

    /**
     * The closed list of calculations (schema 12); 1, 4, 7 and 8 are retired and never reused. 11 to 13 are the demo
     * kit's own (item 155): lots received by transfer, and lots and short lots rescaled by a split.
     */
    public static final int IDENTIFY_LOTS_NAMED = 2, SETTLE_PENDING = 3, DIFFERENCE = 5, SUM = 6,
        IDENTIFY_SHORT_LOTS_NAMED = 9, SETTLE_SHORT_PENDING = 10, OPEN_LOTS_RECEIVED = 11, RESCALE_LOTS = 12,
        RESCALE_SHORT_LOTS = 13;

    /** Key dimensions (schema 11 LedgerDimension). */
    public static final int BROKERAGE_ACCOUNT = 1, CURRENCY = 2, SECURITY = 3, LOT = 4;

    /** The sub-ledger roots. */
    public static final int SL_CA = 2, SL_SHARE = 3, SL_BP = 4, SL_RCB = 5;

    public record Dimension(int id, String name, String description) {}

    public record Root(int id, Integer parent, String naturalClass, String measure, boolean postable, String code,
                       String name, String description) {}

    public record Summary(int id, int parent, String naturalClass, String measure, String code, String name) {}

    /** A ledger account definition (a leaf of the chart). */
    public record Definition(int id, int parent, String naturalClass, String measure, String code, String name,
                             String description, Side normalSide, List<Integer> keyDimensions, int root) {}

    public record Control(int control, int of) {}

    public record Mirror(int register, int of) {}

    public record Chart(String source, List<Dimension> dimensions, List<Root> roots, List<Summary> summaries,
                        List<Definition> definitions, List<Control> controls, List<Mirror> mirrors,
                        List<Integer> mayReverse, int unitExponent) {}

    /**
     * Where an amount or a key value comes from: a transaction field, a group of one (the lots of field 18), or a
     * calculation's output.
     */
    public record Source(Integer field, Integer group, Integer calculation, String output) {
        public static Source field(int f) {
            return new Source(f, null, null, null);
        }

        public static Source output(int calculation, String output) {
            return new Source(null, null, calculation, output);
        }
    }

    public record Binding(int dimension, Source source) {}

    public record Line(int definition, Side side, Source amount, List<Binding> dimensions, Integer repeat) {}

    public record Entry(int subLedger, List<Line> lines) {}

    public record Calculation(int id, int kind, Map<String, Source> inputs) {}

    public record BookingScheme(String name, int id, int template, List<Calculation> calculations,
                                List<Entry> entries, String note) {
        public List<Line> lines() {
            return entries.stream().flatMap(e -> e.lines().stream()).toList();
        }
    }

    public record Template(int id, String name, Map<Integer, String> fields, boolean startsLifecycle,
                           Integer originTemplate) {}

    public record CalculationKind(String name, List<String> inputs, List<String> outputs) {}

    public record Schemes(String source, Map<Integer, String> commonFields, List<Template> templates,
                          List<String> nonNegativeFields, Map<Integer, CalculationKind> calculations,
                          List<BookingScheme> bookingSchemes) {}

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final Chart chart;
    private final Schemes schemes;
    private final Map<Integer, Definition> definitions;
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, Template> templates;
    private final Map<Integer, BookingScheme> byTemplate;
    private final Set<Integer> mayReverse;
    private final Set<String> nonNegative;

    public Kit(Chart chart, Schemes schemes) {
        this.chart = chart;
        this.schemes = schemes;
        this.definitions = chart.definitions().stream()
            .collect(Collectors.toMap(Definition::id, d -> d, (a, b) -> a, LinkedHashMap::new));
        chart.roots().forEach(r -> names.put(r.id(), r.name()));
        chart.summaries().forEach(s -> names.put(s.id(), s.name()));
        chart.definitions().forEach(d -> names.put(d.id(), d.name()));
        this.templates = schemes.templates().stream()
            .collect(Collectors.toMap(Template::id, t -> t, (a, b) -> a, TreeMap::new));
        this.byTemplate = new HashMap<>();
        for (BookingScheme s : schemes.bookingSchemes()) {
            // B5: one booking scheme per template.
            if (byTemplate.put(s.template(), s) != null) {
                throw new IllegalArgumentException("two booking schemes for template " + s.template());
            }
        }
        this.mayReverse = Set.copyOf(chart.mayReverse());
        this.nonNegative = Set.copyOf(schemes.nonNegativeFields());
    }

    /** The demo kit as this build carries it. */
    public static Kit load() {
        return new Kit(read("chart.json", Chart.class), read("booking-schemes.json", Schemes.class));
    }

    private static <T> T read(String name, Class<T> type) {
        try (InputStream in = Kit.class.getResourceAsStream(name)) {
            return MAPPER.readValue(Objects.requireNonNull(in, name), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** This kit with one booking scheme replaced (by name), as a loader would see a changed set. */
    public Kit withScheme(BookingScheme replacement) {
        List<BookingScheme> list = new ArrayList<>();
        for (BookingScheme s : schemes.bookingSchemes()) {
            list.add(s.name().equals(replacement.name()) ? replacement : s);
        }
        return new Kit(chart, new Schemes(schemes.source(), schemes.commonFields(), schemes.templates(),
            schemes.nonNegativeFields(), schemes.calculations(), list));
    }

    public Chart chart() {
        return chart;
    }

    public Schemes schemes() {
        return schemes;
    }

    public Definition definition(int id) {
        Definition d = definitions.get(id);
        if (d == null) {
            throw new IllegalArgumentException("no definition " + id);
        }
        return d;
    }

    public boolean defines(int id) {
        return definitions.containsKey(id);
    }

    public String name(int id) {
        return names.get(id);
    }

    public Side normal(int definition) {
        return definition(definition).normalSide();
    }

    public boolean mayReverse(int definition) {
        return mayReverse.contains(definition);
    }

    public boolean nonNegative(String fieldName) {
        return nonNegative.contains(fieldName);
    }

    public Template template(int id) {
        return templates.get(id);
    }

    public Map<Integer, Template> templates() {
        return templates;
    }

    /** The booking scheme for a template, or null (schema 13 NoBookingScheme). */
    public BookingScheme schemeFor(int template) {
        return byTemplate.get(template);
    }

    /** Every field a template's financial transaction carries: the common ones, then its own. */
    public Map<Integer, String> fieldsOf(Template t) {
        Map<Integer, String> all = new TreeMap<>(schemes.commonFields());
        all.putAll(t.fields());
        return all;
    }

    /** The ControlOf pairs and the register mirrors, as (other, source). */
    public List<int[]> mirrored() {
        List<int[]> out = new ArrayList<>();
        chart.controls().forEach(c -> out.add(new int[] {c.control(), c.of()}));
        chart.mirrors().forEach(m -> out.add(new int[] {m.register(), m.of()}));
        return out;
    }

    /** The default bindings of a definition's key: BrokerageAccount from field 5, Currency 7, Security 6, Lot 1. */
    public List<Binding> defaultBindings(int definition) {
        return definition(definition).keyDimensions().stream()
            .map(k -> new Binding(k, Source.field(switch (k) {
                case BROKERAGE_ACCOUNT -> 5;
                case CURRENCY -> 7;
                case SECURITY -> 6;
                case LOT -> 1;
                default -> throw new IllegalArgumentException("dimension " + k);
            })))
            .toList();
    }

    /**
     * The loader's mirror proof (schema 12 B20): for every ControlOf pair and register mirror, each journal line a
     * booking scheme writes on one definition of the pair has its counterpart on the other, in the same booking
     * scheme, from the same amount source, repeating over the same calculation, on the side that keeps the two equal
     * in normal-balance terms. Returns the problems as "SCHEME other source" (empty: accepted).
     */
    public List<String> validateMirrors() {
        List<String> out = new ArrayList<>();
        for (BookingScheme s : schemes.bookingSchemes()) {
            List<Line> lines = s.lines();
            for (int[] pair : mirrored()) {
                int other = pair[0], src = pair[1];
                boolean same = normal(other) == normal(src);
                List<String> a = lines.stream().filter(l -> l.definition() == src)
                    .map(l -> l.side() + " " + l.amount() + " " + l.repeat()).sorted().toList();
                List<String> b = lines.stream().filter(l -> l.definition() == other)
                    .map(l -> (same ? l.side() : l.side().flip()) + " " + l.amount() + " " + l.repeat()).sorted().toList();
                if (!a.equals(b)) {
                    out.add(s.name() + " " + other + " " + src);
                }
            }
        }
        return out;
    }

    /**
     * The chart loader's rules: R4, each key dimension once and only known ones; R10, a definition under a customer
     * sub-ledger root declares BrokerageAccount first. Returns the problems as "R4 id" or "R10 id" (empty: accepted).
     */
    public static List<String> validateChart(List<Definition> definitions, Set<Integer> knownDimensions) {
        Set<Integer> customerRoots = Set.of(SL_CA, SL_SHARE, SL_BP, SL_RCB);
        List<String> out = new ArrayList<>();
        for (Definition d : definitions) {
            List<Integer> ks = d.keyDimensions();
            if (Set.copyOf(ks).size() != ks.size() || !knownDimensions.containsAll(ks)) {
                out.add("R4 " + d.id());
            }
            if (customerRoots.contains(d.root()) && (ks.isEmpty() || ks.get(0) != BROKERAGE_ACCOUNT)) {
                out.add("R10 " + d.id());
            }
        }
        return out;
    }

    public List<String> validateChart() {
        return validateChart(chart.definitions(),
            chart.dimensions().stream().map(Dimension::id).collect(Collectors.toSet()));
    }
}
