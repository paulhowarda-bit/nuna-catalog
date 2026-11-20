package nuna.changefeed.engine;

import nuna.changefeed.engine.ObserveReader.Event;
import nuna.changefeed.engine.ObserveReader.Type;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;

/** A SingleStore orders table as the feed sees it, and helpers to feed it events and read its Iceberg copy. */
final class Fixtures {

    static final TableIdentifier ORDERS = TableIdentifier.of(Namespace.of("markets", "orders"), "orders");

    private Fixtures() {}

    static SourceTable orders(int partitions, SourceTable.Column... extra) {
        List<SourceTable.Column> columns = new ArrayList<>(List.of(
                SourceTable.column("ops.orders.order_id", "order_id", "bigint", "bigint(20)", 19, 0, false),
                SourceTable.column("ops.orders.symbol", "symbol", "varchar", "varchar(12)", 0, 0, false),
                SourceTable.column("ops.orders.qty", "qty", "int", "int(11)", 10, 0, false),
                SourceTable.column("ops.orders.price", "price", "decimal", "decimal(18,6)", 18, 6, true),
                SourceTable.column("ops.orders.status", "status", "varchar", "varchar(16)", 0, 0, false)));
        columns.addAll(List.of(extra));
        return new SourceTable("ops", "orders", List.copyOf(columns), List.of("order_id"), partitions);
    }

    static FeedConfig config(String table, String partitionBy) {
        return new FeedConfig("jdbc:mariadb://unused", "root", "", "ops", table, Map.of(), ORDERS, 2, partitionBy, 0, false);
    }

    static Object[] row(long id, String symbol, int qty, String price, String status) {
        return new Object[] {id, symbol, qty, price == null ? null : new BigDecimal(price), status};
    }

    /** A delete carries only the key. */
    static Object[] key(long id) {
        return new Object[] {id, null, null, null, null};
    }

    static Event insert(int partition, Object[] row) {
        return new Event(Type.INSERT, partition, "x", row);
    }

    static Event update(int partition, Object[] row) {
        return new Event(Type.UPDATE, partition, "x", row);
    }

    static Event delete(int partition, long id) {
        return new Event(Type.DELETE, partition, "x", key(id));
    }

    /** One committed transaction on a partition, ending at the given offset. */
    static void transaction(ChangeBatch batch, int partition, String offset, Event... rows) {
        batch.accept(new Event(Type.BEGIN_TRANSACTION, partition, offset, null));
        for (Event row : rows) {
            batch.accept(row);
        }
        batch.accept(new Event(Type.COMMIT_TRANSACTION, partition, offset, null));
    }

    /** The copy's live rows, by order id, as {symbol, qty, price, status}. */
    static Map<Long, List<Object>> rows(Table table) {
        table.refresh();
        Map<Long, List<Object>> rows = new TreeMap<>();
        try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
            for (Record r : records) {
                List<Object> values = new ArrayList<>();
                for (int i = 1; i < r.size(); i++) {
                    values.add(r.get(i));
                }
                if (rows.put((Long) r.get(0), values) != null) {
                    throw new AssertionError("order " + r.get(0) + " appears twice in " + table);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    static List<Object> values(String symbol, int qty, String price, String status) {
        List<Object> values = new ArrayList<>();
        values.add(symbol);
        values.add(qty);
        values.add(price == null ? null : new BigDecimal(price));
        values.add(status);
        return values;
    }
}
