package keel.catalog.server.metrics;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passes a response through unchanged, keeping a copy of the start of an error body (status 400 or more), from which
 * {@link #errorType} reads the Iceberg error type the client was sent: {@code error.type} of an Iceberg error, or
 * {@code error} of an OAuth one. Wherever the error was written, a route, an error handler or a filter, it is the type
 * the client saw.
 */
final class ErrorCapture extends HttpServletResponseWrapper {

    /** An error body is a few hundred bytes; its type follows its message. */
    private static final int KEPT = 16 * 1024;
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final StringBuilder chars = new StringBuilder();
    private ServletOutputStream stream;
    private PrintWriter writer;

    ErrorCapture(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) {
            ServletOutputStream out = super.getOutputStream();
            stream = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    out.write(b);
                    if (keeping() && bytes.size() < KEPT) {
                        bytes.write(b);
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                    if (keeping()) {
                        bytes.write(b, off, Math.min(len, KEPT - bytes.size()));
                    }
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }

                @Override
                public void close() throws IOException {
                    out.close();
                }

                @Override
                public boolean isReady() {
                    return out.isReady();
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    out.setWriteListener(listener);
                }
            };
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            PrintWriter out = super.getWriter();
            writer = new PrintWriter(new Writer() {
                @Override
                public void write(char[] buffer, int off, int len) {
                    out.write(buffer, off, len);
                    if (keeping()) {
                        chars.append(buffer, off, Math.min(len, KEPT - chars.length()));
                    }
                }

                @Override
                public void flush() {
                    out.flush();
                }

                @Override
                public void close() {
                    out.close();
                }
            });
        }
        return writer;
    }

    /** The error type the body names, or {@code none} when it names none a label may hold. */
    String errorType() {
        String body = chars.isEmpty() ? bytes.toString(StandardCharsets.UTF_8) : chars.toString();
        if (body.isEmpty()) {
            return CatalogMetrics.NONE;
        }
        try {
            JsonNode error = JSON.readTree(body).path("error");
            String type = error.isString() ? error.asString() : error.path("type").asString("");
            return TYPE.matcher(type).matches() ? type : CatalogMetrics.NONE;
        } catch (JacksonException e) {
            return CatalogMetrics.NONE;
        }
    }

    private boolean keeping() {
        return getStatus() >= 400;
    }
}
