package nuna.datagen.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import nuna.datagen.kit.Kit;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/** Every value the world sends fits a column of its table: the sink drops a field that has none, and rounds a decimal. */
class LakeSchemasTest {

    private static final Kit KIT = Kit.load();

    @Test
    void everyRowTheWorldSendsFitsItsTableAndEveryColumnIsUsed() {
        Map<Feed, Schema> schemas = new EnumMap<>(Feed.class);
        Map<Feed, Set<String>> used = new EnumMap<>(Feed.class);
        for (Feed f : Feed.values()) {
            if (f.path == Feed.Path.KAFKA) {
                schemas.put(f, LakeSchemas.schema(f, KIT));
                used.put(f, new HashSet<>());
            }
        }
        Schema withLocate = new Schema(Types.NestedField.optional(1000, LakeSchemas.LOCATE_ID, Types.StringType.get()));
        // A month, so margin debits have built up and interest is booked at its end, at enough accounts that every
        // template is used.
        World w = new World(World.Settings.of(5, LocalDate.of(2026, 10, 1), 600), KIT);
        Sink check = new Sink() {
            @Override
            public void accept(Event e) {
                if (e.feed().path != Feed.Path.KAFKA) {
                    return;
                }
                e.row().forEach((column, value) -> {
                    Types.NestedField field = schemas.get(e.feed()).findField(column);
                    if (field == null && e.feed() == Feed.FINANCIAL_TRANSACTIONS) {
                        field = withLocate.findField(column);
                    }
                    assertThat(field).as(e.feed() + "." + column).isNotNull();
                    if (value != null) {
                        fits(e.feed() + "." + column, value, field.type());
                        used.get(e.feed()).add(column);
                    }
                });
            }
        };
        for (int i = 0; i < 22; i++) {
            w.runDay(check);
        }
        assertThat(used.get(Feed.FINANCIAL_TRANSACTIONS)).contains(LakeSchemas.LOCATE_ID);
        schemas.forEach((f, schema) -> {
            Set<String> unused = new HashSet<>();
            schema.columns().forEach(c -> unused.add(c.name()));
            unused.removeAll(used.get(f));
            // A rejection's id columns are null only when the posting is not one of the world's; none is.
            assertThat(unused).as("columns of " + f.lakeTable() + " no row filled").isEmpty();
        });
    }

    private static void fits(String what, Object value, Type type) {
        switch (type.typeId()) {
            case LONG -> assertThat(value).as(what).isInstanceOf(Long.class);
            case INTEGER -> assertThat(value).as(what).isInstanceOf(Integer.class);
            case STRING -> assertThat(value).as(what).isInstanceOf(String.class);
            case BOOLEAN -> assertThat(value).as(what).isInstanceOf(Boolean.class);
            case DATE -> assertThat(value).as(what).isInstanceOf(LocalDate.class);
            case TIMESTAMP -> assertThat(value).as(what).isInstanceOf(Instant.class);
            case DECIMAL -> {
                assertThat(value).as(what).isInstanceOf(BigDecimal.class);
                Types.DecimalType d = (Types.DecimalType) type;
                BigDecimal v = ((BigDecimal) value).stripTrailingZeros();
                assertThat(Math.max(v.scale(), 0)).as(what + " = " + value + " keeps its digits").isLessThanOrEqualTo(d.scale());
                assertThat(v.precision() - v.scale()).as(what + " = " + value + " fits").isLessThanOrEqualTo(d.precision() - d.scale());
            }
            case LIST -> {
                assertThat(value).as(what).isInstanceOf(List.class);
                Types.StructType element = (Types.StructType) ((Types.ListType) type).elementType();
                for (Object item : (List<?>) value) {
                    ((Map<?, ?>) item).forEach((k, v) -> fits(what + "." + k, v, element.field((String) k).type()));
                }
            }
            default -> throw new AssertionError(what + ": no check for " + type);
        }
    }
}
