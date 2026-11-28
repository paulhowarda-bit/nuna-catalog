package nuna.catalog.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One job of a scan that failed on a table, as the worker names it (backlog 327): the job's kind, as its scan counts
 * it in {@code failures}, the table it failed on, as the work list named it, and why: a {@code reason} the console
 * words, and the refusal or error the worker met, cut to {@value #MAX_MESSAGE} characters.
 *
 * @param kind      the failure's kind, as {@code failures} counts it ({@code table-load}, {@code compaction}, ...)
 * @param catalog   the table's catalog
 * @param namespace the table's namespace, its levels from the outermost in
 * @param name      the table's name
 * @param id        the table's id, as the work list gave it
 * @param reason    why, in a word: {@code closed} (closed until classified), {@code refused}, {@code not-found},
 *                  {@code changed} (another table has the name now) or {@code error}
 * @param message   the refusal or error the worker met, 1 to {@value #MAX_MESSAGE} characters, no control character
 */
public record FailedJob(String kind, String catalog, List<String> namespace, String name, UUID id, String reason,
                        String message) {

    /** The longest message kept: a refusal's words, never a stack. */
    public static final int MAX_MESSAGE = 300;
    private static final int MAX_LEVELS = 32;

    public FailedJob {
        Objects.requireNonNull(id, "id");
        namespace = List.copyOf(namespace);
        if (kind == null || !TableAssessment.LABEL.matcher(kind).matches()
                || reason == null || !TableAssessment.LABEL.matcher(reason).matches()) {
            throw new IllegalArgumentException("a failed job's kind and reason are labels such as table-load: " + kind
                    + ", " + reason);
        }
        if (namespace.isEmpty() || namespace.size() > MAX_LEVELS || !text(catalog) || !text(name)
                || !namespace.stream().allMatch(FailedJob::text)) {
            throw new IllegalArgumentException("a failed job names its table: a catalog, 1 to " + MAX_LEVELS
                    + " namespace levels and a name, each 1 to 1000 characters, none a control character");
        }
        if (message == null || message.isEmpty() || message.length() > MAX_MESSAGE || !text(message)) {
            throw new IllegalArgumentException("a failed job's message is 1 to " + MAX_MESSAGE + " characters, none a"
                    + " control character");
        }
    }

    private static boolean text(String value) {
        return value != null && WorkerStorage.TEXT.matcher(value).matches();
    }

    /**
     * The failed jobs as the SQL and Oracle stores keep them: one a line, its fields apart by tabs, which no field
     * holds: kind, reason, id, catalog, name, message, then each namespace level.
     */
    public static String write(List<FailedJob> jobs) {
        List<String> lines = new ArrayList<>();
        for (FailedJob job : jobs) {
            List<String> fields = new ArrayList<>(List.of(job.kind, job.reason, job.id.toString(), job.catalog,
                    job.name, job.message));
            fields.addAll(job.namespace);
            lines.add(String.join("\t", fields));
        }
        return String.join("\n", lines);
    }

    public static List<FailedJob> read(String text) {
        List<FailedJob> jobs = new ArrayList<>();
        if (text.isEmpty()) {
            return jobs;
        }
        for (String line : text.split("\n", -1)) {
            String[] fields = line.split("\t", -1);
            jobs.add(new FailedJob(fields[0], fields[3], Arrays.asList(fields).subList(6, fields.length), fields[4],
                    UUID.fromString(fields[2]), fields[1], fields[5]));
        }
        return jobs;
    }
}
