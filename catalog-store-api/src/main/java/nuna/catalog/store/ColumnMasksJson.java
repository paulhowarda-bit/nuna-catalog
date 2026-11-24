package nuna.catalog.store;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A rule's column masks as the SQL and Oracle stores keep them: a JSON object from column name to expression, keys in
 * order, so the same masks are always the same text. The stores carry no JSON library; this reads only what
 * {@link #write} writes, a flat object of strings.
 */
public final class ColumnMasksJson {

    private ColumnMasksJson() {
    }

    public static String write(SortedMap<String, String> masks) {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> mask : masks.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            string(json, mask.getKey());
            json.append(':');
            string(json, mask.getValue());
        }
        return json.append('}').toString();
    }

    public static SortedMap<String, String> read(String json) {
        Reader reader = new Reader(json);
        SortedMap<String, String> masks = new TreeMap<>();
        reader.skip();
        reader.expect('{');
        reader.skip();
        if (reader.peek() != '}') {
            while (true) {
                reader.skip();
                String column = reader.string();
                reader.skip();
                reader.expect(':');
                reader.skip();
                masks.put(column, reader.string());
                reader.skip();
                if (reader.peek() == ',') {
                    reader.expect(',');
                    continue;
                }
                break;
            }
        }
        reader.expect('}');
        reader.skip();
        if (!reader.done()) {
            throw new IllegalArgumentException("column masks: text after the object");
        }
        return masks;
    }

    private static void string(StringBuilder json, String text) {
        json.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    private static final class Reader {

        private final String json;
        private int at;

        Reader(String json) {
            this.json = json;
        }

        boolean done() {
            return at >= json.length();
        }

        char peek() {
            if (done()) {
                throw new IllegalArgumentException("column masks: the text ends early");
            }
            return json.charAt(at);
        }

        void skip() {
            while (!done() && Character.isWhitespace(json.charAt(at))) {
                at++;
            }
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("column masks: expected " + c + " at " + at);
            }
            at++;
        }

        String string() {
            expect('"');
            StringBuilder text = new StringBuilder();
            while (true) {
                char c = peek();
                at++;
                if (c == '"') {
                    return text.toString();
                }
                if (c != '\\') {
                    text.append(c);
                    continue;
                }
                char escaped = peek();
                at++;
                switch (escaped) {
                    case '"', '\\', '/' -> text.append(escaped);
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'u' -> {
                        if (at + 4 > json.length()) {
                            throw new IllegalArgumentException("column masks: a short \\u escape");
                        }
                        text.append((char) Integer.parseInt(json.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("column masks: unknown escape \\" + escaped);
                }
            }
        }
    }
}
