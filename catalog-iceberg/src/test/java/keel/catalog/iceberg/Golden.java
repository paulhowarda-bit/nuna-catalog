package keel.catalog.iceberg;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import keel.catalog.iceberg.Outcome.BadRequest;
import keel.catalog.iceberg.Outcome.NewMetadata;
import keel.catalog.iceberg.Outcome.NoChange;
import keel.catalog.iceberg.Outcome.NotFound;
import keel.catalog.iceberg.Outcome.RequirementFailed;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Golden files, and the normalization that makes two runs of the same request comparable.
 *
 * <p>Normalized, and why:
 * <ul>
 *   <li>Clock readings. iceberg-core stamps {@code last-updated-ms} with System.currentTimeMillis() in
 *   TableMetadata.Builder.build(), and the snapshot-log entry of a rollback in setRef(); a view's history
 *   entry for a re-activated version in ViewMetadata.Builder.setCurrentVersionId(); and the reference
 *   create-view handler stamps the new version with it. None takes an injected clock. Every JSON number
 *   whose value lies inside the wall-clock window of the call is replaced with {@link #CLOCK}.</li>
 *   <li>The reference's random UUIDs. The reference create handlers draw table-uuid and view-uuid from
 *   UUID.randomUUID(); the adapter is given the UUID. The reference value (checked to be a UUID) is
 *   replaced by the one the adapter was given.</li>
 * </ul>
 * Metadata file names need no normalization: a base is registered with the reference catalog at the
 * location the adapter is given, and a new file's own name never appears in its content.
 */
final class Golden {

    /** Stands in for every clock reading: 2026-01-01T00:00:00Z, after every fixture timestamp. */
    static final long CLOCK = 1767225600000L;
    static final String WAREHOUSE = "s3://warehouse";
    static final boolean RECORD = Boolean.getBoolean("golden.record");

    private static final Path DIR = Path.of(System.getProperty("basedir", "."), "src", "test", "resources", "golden");
    private static final Pattern NUMBER = Pattern.compile("(?<=[:\\[,])(\\d+)(?=[,}\\]])");
    private static final Pattern REFERENCE_UUID = Pattern.compile("\"(?:table|view)-uuid\":\"([^\"]+)\"");
    private static final JsonFactory JSON = new JsonFactory();

    private Golden() {
    }

    static String request(GoldenCase c) {
        return read(DIR.resolve(c.name() + ".request.json"));
    }

    /** The table or view name a create request asks for. */
    static String requestedName(GoldenCase c) {
        try {
            return new ObjectMapper().readTree(request(c)).get("name").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The exact metadata bytes the base case produced, or null for a case with no base. */
    static String baseJson(GoldenCase c) {
        return c.base() == null ? null : compact(read(file(GoldenCase.named(c.base()))));
    }

    static String baseLocation(GoldenCase c) {
        return c.base() == null ? null : WAREHOUSE + "/golden/" + c.base() + ".metadata.json";
    }

    static String expected(GoldenCase c) {
        return read(file(c));
    }

    static void record(GoldenCase c, String text) throws IOException {
        Files.writeString(file(c), text);
    }

    static Path file(GoldenCase c) {
        return DIR.resolve(c.name() + (c.expectsMetadata() ? ".metadata.json" : ".outcome.txt"));
    }

    /** Replaces every clock reading taken between {@code from} and {@code to} (inclusive). */
    static Outcome clock(Outcome outcome, long from, long to) {
        if (!(outcome instanceof NewMetadata m)) {
            return outcome;
        }
        Matcher matcher = NUMBER.matcher(m.json());
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            matcher.appendReplacement(out, value >= from && value <= to ? String.valueOf(CLOCK) : matcher.group(1));
        }
        matcher.appendTail(out);
        return new NewMetadata(out.toString(), m.before(), m.after());
    }

    /** Replaces the reference's random table or view UUID with the one the adapter was given. */
    static Outcome uuid(Outcome outcome, String given) {
        NewMetadata m = (NewMetadata) outcome;
        Matcher matcher = REFERENCE_UUID.matcher(m.json());
        if (!matcher.find()) {
            throw new AssertionError("no table-uuid or view-uuid in " + m.json());
        }
        String drawn = UUID.fromString(matcher.group(1)).toString();
        return new NewMetadata(m.json().replace('"' + drawn + '"', '"' + given + '"'), m.before(), m.after());
    }

    /** The golden text for an outcome: metadata laid out one value per line, anything else on one line. */
    static String render(Outcome outcome) {
        return switch (outcome) {
            case NewMetadata m -> layout(m.json());
            case NoChange n -> "no-change\n";
            case RequirementFailed f -> "requirement-failed: " + f.message() + "\n";
            case BadRequest b -> "bad-request: " + b.message() + "\n";
            case NotFound n -> "not-found\n";
        };
    }

    /** Inserts whitespace between tokens and nothing else; {@link #compact} undoes it exactly. */
    static String layout(String json) {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(
                Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);
        StringWriter out = new StringWriter();
        try (JsonParser parser = JSON.createParser(json); JsonGenerator generator = JSON.createGenerator(out)) {
            generator.setPrettyPrinter(printer);
            while (parser.nextToken() != null) {
                generator.copyCurrentEvent(parser);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out + "\n";
    }

    /** Removes the whitespace outside JSON strings. */
    static String compact(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (char ch : text.toCharArray()) {
            if (inString) {
                out.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '"') {
                inString = true;
                out.append(ch);
            } else if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** The lines that differ, for a failure message a reviewer can read without a diff tool. */
    static String differences(String expected, String actual) {
        String[] want = expected.split("\n", -1);
        String[] got = actual.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.max(want.length, got.length) && lines.size() < 40; i++) {
            String w = i < want.length ? want[i] : "<none>";
            String g = i < got.length ? got[i] : "<none>";
            if (!w.equals(g)) {
                lines.add("line " + (i + 1) + "\n  golden: " + w + "\n  actual: " + g);
            }
        }
        return String.join("\n", lines);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
