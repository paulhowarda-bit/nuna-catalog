package nuna.datagen.kit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A financial transaction (schema 20): a template and its field values by field id. Field 1 is the
 * financialTransactionId, 5 the brokerage account, 6 the security, 7 the currency, 8 the quantity, 9 the
 * originatingFinancialTransactionId; field 18 of a sell or cover fill is the lots it names. A value is a Long, a
 * BigDecimal, a String or, for the lots, a list of {@link LotShares}.
 */
public record FinancialTransaction(int template, long brokerageAccount, Map<Integer, Object> fields) {

    public static final int ID = 1, EFFECTIVE_DATE = 2, ORIGINATOR = 4, BROKERAGE_ACCOUNT = 5, SECURITY = 6,
        CURRENCY = 7, QUANTITY = 8, ORIGINATING_ID = 9, LOTS = 18;

    /** A lot a sell or cover fill names, and the shares it takes from it. */
    public record LotShares(long lot, BigDecimal shares) {}

    public FinancialTransaction {
        fields = new TreeMap<>(fields);
    }

    public long id() {
        return longValue(ID);
    }

    public boolean has(int field) {
        return fields.get(field) != null;
    }

    public Object get(int field) {
        return fields.get(field);
    }

    public BigDecimal decimal(int field) {
        Object v = fields.get(field);
        if (v == null) {
            throw new IllegalArgumentException("financial transaction " + fields.get(ID) + " has no field " + field);
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    public long longValue(int field) {
        Object v = fields.get(field);
        if (v == null) {
            throw new IllegalArgumentException("financial transaction " + fields.get(ID) + " has no field " + field);
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
    }

    public Long longOrNull(int field) {
        return has(field) ? longValue(field) : null;
    }

    /** Field 18 as the lots it names; empty when absent. */
    @SuppressWarnings("unchecked")
    public List<LotShares> lots() {
        Object v = fields.get(LOTS);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<LotShares> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof LotShares ls) {
                out.add(ls);
            } else {
                List<Object> pair = (List<Object>) o;
                out.add(new LotShares(((Number) pair.get(0)).longValue(), new BigDecimal(pair.get(1).toString())));
            }
        }
        return out;
    }

    /** A copy with some fields changed. */
    public FinancialTransaction with(Map<Integer, Object> changes) {
        Map<Integer, Object> f = new TreeMap<>(fields);
        f.putAll(changes);
        Object ba = f.get(BROKERAGE_ACCOUNT);
        return new FinancialTransaction(template, ba == null ? brokerageAccount : ((Number) ba).longValue(), f);
    }
}
