package nuna.audittables;

import nuna.catalog.audit.AuditRow;
import nuna.catalog.core.Names;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StaticTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The read side of the audit table {@code events} (D23), for the catalog server's audit feed (D45, backlog 125): the
 * records of one catalog newest first, filtered and a page at a time, and the counts of a span of time. Rows reach
 * the table when the writer commits, about a minute after they happen, so this is that far behind.
 *
 * <p>A page is ordered by record time, then partition, then offset, newest first; its cursor names the last row it
 * gave, and the next page holds the rows after it in that order, so no row is given twice or skipped while the table
 * grows. Files are read newest first by the upper bound of their times, and reading stops once the page is full and
 * every file left is older than its last row. A filter that matches few rows could make a page read the whole table;
 * instead a page reads about {@link #DEFAULT_SCAN_BUDGET} rows, then stops at the newest time it has not finished and
 * returns what it found newer than that, with a cursor there: the caller asks again to search further back. Each page
 * reads further back than the cursor it was given, so asking again always ends.
 *
 * <p>Only the columns the filters need are read for every row; a row's whole record, its payload included, is read
 * only for the rows a page gives, from the row groups that hold them. Every file read lies under the table's
 * location: a manifest that names a file elsewhere is refused, so the table's writer cannot make the catalog read
 * another table's files.
 */
public final class EventsReader {

    /** About how many rows one page reads before it returns what it has found. */
    public static final long DEFAULT_SCAN_BUDGET = 500_000;
    /** The most rows one page gives. */
    public static final int MAX_LIMIT = 200;

    private static final Schema SCAN = EventsTable.SCHEMA.select("time", "type", "principal", "catalog",
            "object_path", "topic_partition", "topic_offset");
    private static final Schema COUNT = EventsTable.SCHEMA.select("time", "type", "principal", "catalog",
            "object_kind", "object_id", "object_path");
    private static final String READ = "nuna.catalog.read";
    private static final String COMMIT = "nuna.catalog.commit";

    /**
     * A place in the feed's order: the next page holds the rows after it, those with an older time, or the same time
     * and a lower partition, or the same partition and a lower offset.
     */
    public record Cursor(long timeMicros, int partition, long offset) {

        private static final String VERSION = "1";

        /** The cursor for "every row at or before {@code time}". */
        static Cursor atOrBefore(long timeMicros) {
            return new Cursor(timeMicros, Integer.MAX_VALUE, Long.MAX_VALUE);
        }

        /** An opaque page token. */
        public String token() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((VERSION + ":" + timeMicros + ":" + partition
                    + ":" + offset).getBytes(StandardCharsets.US_ASCII));
        }

        /** @throws IllegalArgumentException if {@code token} is not one {@link #token} made */
        public static Cursor parse(String token) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII).split(":");
                if (parts.length != 4 || !parts[0].equals(VERSION)) {
                    throw new IllegalArgumentException("not a page token: " + token);
                }
                return new Cursor(Long.parseLong(parts[1]), Integer.parseInt(parts[2]), Long.parseLong(parts[3]));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("not a page token: " + token, e);
            }
        }
    }

    /**
     * What a page asks for.
     *
     * @param catalog   the catalog's name as its records carry it; only its records are given
     * @param types     the event types to give, e.g. {@code nuna.catalog.read}; empty for every type
     * @param principal the principal whose records to give, or null for anyone
     * @param path      a namespace's levels, or a namespace's levels and a table or view name: only records about it
     *                  and, for a namespace, everything in it; empty for the whole catalog. Compared as the catalog
     *                  compares names.
     * @param from      the oldest time to give, inclusive, or null
     * @param to        the time to give records before, exclusive, or null for now
     * @param after     the cursor of the page before, or empty for the first page
     * @param limit     the most rows to give, 1 to {@link #MAX_LIMIT}
     */
    public record Query(String catalog, Set<String> types, String principal, List<String> path, Instant from,
                        Instant to, Optional<Cursor> after, int limit) {

        public Query {
            Objects.requireNonNull(catalog, "catalog");
            types = Set.copyOf(types);
            path = path.stream().map(Names::normalize).toList();
            Objects.requireNonNull(after, "after");
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT + ", not " + limit);
            }
        }
    }

    /**
     * One page.
     *
     * @param rows       the rows, newest first
     * @param next       the cursor of the next page; empty when no row is left
     * @param searchedTo when the page stopped before it was full: every row newer than this was searched, and the
     *                   next page searches from here back; empty otherwise
     */
    public record Page(List<AuditRow> rows, Optional<Cursor> next, Optional<Instant> searchedTo) {
    }

    /**
     * An object's records in a span.
     *
     * @param kind    {@code TABLE}, {@code VIEW}, {@code NAMESPACE} or {@code CATALOG}
     * @param id      its stable id
     * @param path    its namespace levels and name, as its newest record names it
     * @param records how many records are about it
     * @param reads   how many of them are reads ({@code nuna.catalog.read})
     * @param writer  the principal with the most commits to it, or null when nothing committed to it
     */
    public record Busy(String kind, String id, List<String> path, long records, long reads, String writer) {
    }

    /**
     * One catalog's records in a span.
     *
     * @param byType  how many records of each type
     * @param busiest the objects with the most records, most first
     */
    public record CatalogCounts(Map<String, Long> byType, List<Busy> busiest) {
    }

    /**
     * The records of a span of time.
     *
     * @param from     the span's start, inclusive
     * @param to       its end, exclusive
     * @param byType   how many records of each type in the whole deployment, every catalog's and those about none
     * @param catalogs each catalog's counts, by the catalog's name as the catalog compares names
     */
    public record Summary(Instant from, Instant to, Map<String, Long> byType, Map<String, CatalogCounts> catalogs) {
    }

    private final Table table;
    private final long scanBudget;
    private final int timeField;

    private EventsReader(Table table, long scanBudget) {
        this.table = table;
        this.scanBudget = scanBudget;
        this.timeField = table.schema().findField("time").fieldId();
    }

    /**
     * The table as {@code metadataLocation}, a metadata file the catalog wrote, describes it, read through {@code s3}.
     * Every other file it reads must lie under the table's location.
     */
    public static EventsReader open(String name, String metadataLocation, S3Client s3) {
        S3FileIO io = new S3FileIO(() -> s3);
        TableMetadata metadata = TableMetadataParser.read(io, metadataLocation);
        return of(new BaseTable(new StaticTableOperations(metadata, new Confined(io, metadata.location())), name),
                DEFAULT_SCAN_BUDGET);
    }

    /** For tests: a table at hand, and a budget of {@code scanBudget} rows a page. */
    static EventsReader of(Table table, long scanBudget) {
        return new EventsReader(table, scanBudget);
    }

    /** The table's name, as it was opened. */
    public String name() {
        return table.name();
    }

    /** The snapshot read, or empty for a table never written. */
    public Optional<Long> snapshotId() {
        return Optional.ofNullable(table.currentSnapshot()).map(Snapshot::snapshotId);
    }

    /** When the writer committed the snapshot read: the feed holds what had reached the table then. */
    public Optional<Instant> committedAt() {
        return Optional.ofNullable(table.currentSnapshot()).map(s -> Instant.ofEpochMilli(s.timestampMillis()));
    }

    // ---- the feed ----

    /** One page of the feed, as {@code query} asks. */
    public Page page(Query query) {
        if (table.currentSnapshot() == null) {
            return new Page(List.of(), Optional.empty(), Optional.empty());
        }
        long ceiling = query.after().map(Cursor::timeMicros).orElse(query.to() == null ? Long.MAX_VALUE
                : micros(query.to()) - 1);
        Expression filter = Expressions.lessThanOrEqual("time", ceiling);
        if (query.to() != null) {
            filter = Expressions.and(filter, Expressions.lessThan("time", micros(query.to())));
        }
        if (query.from() != null) {
            filter = Expressions.and(filter, Expressions.greaterThanOrEqual("time", micros(query.from())));
        }
        filter = Expressions.and(filter, Expressions.equal("catalog", query.catalog()));
        if (!query.types().isEmpty()) {
            filter = Expressions.and(filter, Expressions.in("type", query.types()));
        }
        if (query.principal() != null) {
            filter = Expressions.and(filter, Expressions.equal("principal", query.principal()));
        }

        // The rows kept, the oldest at the head, so a newer row can push it out.
        PriorityQueue<Candidate> kept = new PriorityQueue<>(NEWEST_FIRST.reversed());
        boolean overflow = false;
        long scanned = 0;
        Long boundary = null;
        List<Split> splits = splits(filter);
        int next = 0;
        for (; next < splits.size(); next++) {
            Split split = splits.get(next);
            if (kept.size() == query.limit() && kept.peek().time() > split.upper()) {
                break;
            }
            // Out of budget: stop at this split's newest time, if that is further back than the page began, so the
            // next page starts further back than this one did.
            if (scanned >= scanBudget && split.upper() < ceiling) {
                boundary = split.upper();
                break;
            }
            try (CloseableIterable<Record> records = read(split, SCAN, filter)) {
                for (Record record : records) {
                    scanned++;
                    Candidate row = candidate(record, split);
                    if (!matches(query, record, row)) {
                        continue;
                    }
                    if (kept.size() < query.limit()) {
                        kept.add(row);
                    } else {
                        overflow = true;
                        if (NEWEST_FIRST.compare(row, kept.peek()) < 0) {
                            kept.poll();
                            kept.add(row);
                        }
                    }
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        List<Candidate> rows = new ArrayList<>(kept);
        rows.sort(NEWEST_FIRST);
        if (boundary != null) {
            long below = boundary;
            rows.removeIf(row -> row.time() <= below);
        }
        Optional<Cursor> cursor = Optional.empty();
        Optional<Instant> searchedTo = Optional.empty();
        if (rows.size() == query.limit() && (overflow || next < splits.size())) {
            Candidate last = rows.getLast();
            cursor = Optional.of(new Cursor(last.time(), last.partition(), last.offset()));
        } else if (boundary != null) {
            cursor = Optional.of(Cursor.atOrBefore(boundary));
            searchedTo = Optional.of(instant(boundary));
        }
        return new Page(whole(rows), cursor, searchedTo);
    }

    /** A row's place in the feed's order and the row group it lies in. */
    private record Candidate(long time, int partition, long offset, Split split) {
    }

    /** Newest first: by time, then partition, then offset, each the highest first. */
    private static final Comparator<Candidate> NEWEST_FIRST = Comparator.comparingLong(Candidate::time)
            .thenComparingInt(Candidate::partition).thenComparingLong(Candidate::offset).reversed();

    private static boolean matches(Query query, Record record, Candidate row) {
        if (!query.catalog().equals(record.getField("catalog"))) {
            return false;
        }
        if (!query.types().isEmpty() && !query.types().contains((String) record.getField("type"))) {
            return false;
        }
        if (query.principal() != null && !query.principal().equals(record.getField("principal"))) {
            return false;
        }
        if (query.from() != null && row.time() < micros(query.from())) {
            return false;
        }
        if (query.to() != null && row.time() >= micros(query.to())) {
            return false;
        }
        if (!query.path().isEmpty()) {
            @SuppressWarnings("unchecked")
            List<String> path = (List<String>) record.getField("object_path");
            if (path == null || path.size() < query.path().size()) {
                return false;
            }
            for (int i = 0; i < query.path().size(); i++) {
                if (!query.path().get(i).equals(Names.normalize(path.get(i)))) {
                    return false;
                }
            }
        }
        return query.after().map(after -> NEWEST_FIRST.compare(row,
                new Candidate(after.timeMicros(), after.partition(), after.offset(), null)) > 0).orElse(true);
    }

    private Candidate candidate(Record record, Split split) {
        return new Candidate(micros((OffsetDateTime) record.getField("time")),
                (Integer) record.getField("topic_partition"), (Long) record.getField("topic_offset"), split);
    }

    /** The whole rows of the candidates, read from the row groups that hold them. */
    private List<AuditRow> whole(List<Candidate> rows) {
        Map<Split, List<Candidate>> bySplit = new LinkedHashMap<>();
        rows.forEach(row -> bySplit.computeIfAbsent(row.split(), s -> new ArrayList<>()).add(row));
        Map<Candidate, AuditRow> found = new HashMap<>();
        bySplit.forEach((split, wanted) -> {
            Map<String, Candidate> byPlace = new HashMap<>();
            wanted.forEach(row -> byPlace.put(row.partition() + ":" + row.offset(), row));
            Expression offsets = Expressions.in("topic_offset", wanted.stream().map(Candidate::offset).toList());
            try (CloseableIterable<Record> records = read(split, EventsTable.SCHEMA, offsets)) {
                for (Record record : records) {
                    Candidate row = byPlace.get(record.getField("topic_partition") + ":" + record.getField("topic_offset"));
                    if (row != null && row.time() == micros((OffsetDateTime) record.getField("time"))) {
                        found.put(row, row(record));
                    }
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return rows.stream().map(row -> Objects.requireNonNull(found.get(row),
                () -> "the audit table's row at " + row.partition() + ":" + row.offset() + " was not found again"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static AuditRow row(Record record) {
        return new AuditRow((String) record.getField("event_id"), ((OffsetDateTime) record.getField("time")).toInstant(),
                (String) record.getField("type"), (String) record.getField("source"),
                (String) record.getField("principal"), (String) record.getField("end_user"),
                (String) record.getField("engine"), (String) record.getField("request_id"),
                (String) record.getField("catalog"), (String) record.getField("object_kind"),
                (String) record.getField("object_id"), (List<String>) record.getField("object_path"),
                (String) record.getField("operation"), (String) record.getField("privilege"),
                (String) record.getField("reason"), (String) record.getField("data"),
                (Integer) record.getField("topic_partition"), (Long) record.getField("topic_offset"));
    }

    // ---- the counts ----

    /**
     * The records whose time is in {@code [from, to)}: in the deployment and in each catalog, by type, and each
     * catalog's {@code top} busiest objects. Reads every row of the span, the columns it counts only.
     */
    public Summary summary(Instant from, Instant to, int top) {
        Map<String, Long> deployment = new TreeMap<>();
        Map<String, Map<String, Long>> byCatalog = new TreeMap<>();
        Map<String, Map<String, Tally>> objects = new HashMap<>();
        if (table.currentSnapshot() != null) {
            Expression filter = Expressions.and(Expressions.greaterThanOrEqual("time", micros(from)),
                    Expressions.lessThan("time", micros(to)));
            for (Split split : splits(filter)) {
                each(split, COUNT, filter, record -> {
                    long time = micros((OffsetDateTime) record.getField("time"));
                    if (time < micros(from) || time >= micros(to)) {
                        return;
                    }
                    String type = (String) record.getField("type");
                    deployment.merge(type, 1L, Long::sum);
                    String catalog = (String) record.getField("catalog");
                    if (catalog == null) {
                        return;
                    }
                    String key = Names.normalize(catalog);
                    byCatalog.computeIfAbsent(key, k -> new TreeMap<>()).merge(type, 1L, Long::sum);
                    String id = (String) record.getField("object_id");
                    if (id != null) {
                        objects.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(id, k -> new Tally())
                                .add(record, time, type);
                    }
                });
            }
        }
        Map<String, CatalogCounts> catalogs = new TreeMap<>();
        byCatalog.forEach((catalog, types) -> catalogs.put(catalog, new CatalogCounts(types,
                objects.getOrDefault(catalog, Map.of()).entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, Tally> e) -> e.getValue().records)
                                .reversed().thenComparing(Map.Entry::getKey))
                        .limit(top)
                        .map(e -> e.getValue().busy(e.getKey()))
                        .toList())));
        return new Summary(from, to, deployment, catalogs);
    }

    /** One object's records in a span. */
    private static final class Tally {

        private long records;
        private long reads;
        private long newest = Long.MIN_VALUE;
        private String kind;
        private List<String> path;
        private final Map<String, Long> writers = new HashMap<>();

        @SuppressWarnings("unchecked")
        void add(Record record, long time, String type) {
            records++;
            if (READ.equals(type)) {
                reads++;
            }
            if (COMMIT.equals(type) && record.getField("principal") != null) {
                writers.merge((String) record.getField("principal"), 1L, Long::sum);
            }
            if (time >= newest) {
                newest = time;
                kind = (String) record.getField("object_kind");
                path = (List<String>) record.getField("object_path");
            }
        }

        Busy busy(String id) {
            // The most commits; of two with as many, the first by name.
            Comparator<Map.Entry<String, Long>> most = Comparator.comparingLong(Map.Entry::getValue);
            String writer = writers.entrySet().stream()
                    .max(most.thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .map(Map.Entry::getKey).orElse(null);
            return new Busy(kind, id, path == null ? List.of() : List.copyOf(path), records, reads, writer);
        }
    }

    // ---- reading files ----

    /**
     * One row group of a data file, or the whole file when its row groups are not recorded, with the newest time any
     * row of the file may hold.
     */
    private record Split(DataFile file, long start, long length, long upper) {
    }

    /** Every split of the files {@code filter} may match, newest first by the upper bound of their times. */
    private List<Split> splits(Expression filter) {
        List<Split> splits = new ArrayList<>();
        try (CloseableIterable<FileScanTask> tasks = table.newScan().useSnapshot(table.currentSnapshot().snapshotId())
                .filter(filter).includeColumnStats(List.of("time")).planFiles()) {
            for (FileScanTask task : tasks) {
                if (!task.deletes().isEmpty()) {
                    throw new IllegalStateException("rows were deleted from " + table.name() + " (" + task.file()
                            .location() + "); the audit table is never deleted from (D24), and the feed reads no deletes");
                }
                DataFile file = task.file();
                if (file.format() != FileFormat.PARQUET) {
                    throw new IllegalStateException(table.name() + " holds a " + file.format() + " file, "
                            + file.location() + "; its writer writes Parquet only");
                }
                long upper = Optional.ofNullable(file.upperBounds()).map(bounds -> bounds.get(timeField))
                        .map(bound -> (Long) Conversions.fromByteBuffer(Types.TimestampType.withZone(), bound))
                        .orElse(Long.MAX_VALUE);
                List<Long> offsets = file.splitOffsets();
                if (offsets == null || offsets.isEmpty()) {
                    splits.add(new Split(file, 0, file.fileSizeInBytes(), upper));
                } else {
                    for (int i = 0; i < offsets.size(); i++) {
                        long end = i + 1 < offsets.size() ? offsets.get(i + 1) : file.fileSizeInBytes();
                        splits.add(new Split(file, offsets.get(i), end - offsets.get(i), upper));
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        splits.sort(Comparator.comparingLong(Split::upper).reversed());
        return splits;
    }

    private CloseableIterable<Record> read(Split split, Schema projection, Expression filter) {
        InputFile input = table.io().newInputFile(split.file().location(), split.file().fileSizeInBytes());
        return Parquet.read(input).project(projection).split(split.start(), split.length()).filter(filter)
                .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(projection, fileSchema)).build();
    }

    private void each(Split split, Schema projection, Expression filter, Consumer<Record> each) {
        try (CloseableIterable<Record> records = read(split, projection, filter)) {
            records.forEach(each);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static long micros(OffsetDateTime time) {
        return micros(time.toInstant());
    }

    private static Instant instant(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    /** The table's files, read only, and only under its location. */
    static final class Confined implements FileIO {

        private final FileIO io;
        private final String under;

        Confined(FileIO io, String location) {
            this.io = io;
            this.under = location.endsWith("/") ? location : location + "/";
        }

        @Override
        public InputFile newInputFile(String path) {
            return io.newInputFile(approve(path));
        }

        @Override
        public InputFile newInputFile(String path, long length) {
            return io.newInputFile(approve(path), length);
        }

        @Override
        public OutputFile newOutputFile(String path) {
            throw new UnsupportedOperationException("the audit feed writes nothing: " + path);
        }

        @Override
        public void deleteFile(String path) {
            throw new UnsupportedOperationException("the audit feed deletes nothing: " + path);
        }

        @Override
        public Map<String, String> properties() {
            return io.properties();
        }

        @Override
        public void close() {
            io.close();
        }

        private String approve(String path) {
            if (!path.startsWith(under) || path.contains("/../")) {
                throw new IllegalStateException("the audit table names " + path + ", which is not under its location "
                        + under + "; the feed reads nothing else");
            }
            return path;
        }
    }
}
