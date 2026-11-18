package nuna.catalog.server.tracing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory span exporter: keeps every span it is given. */
final class SpanRecorder implements SpanExporter {

    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    List<SpanData> spans() {
        return List.copyOf(spans);
    }

    /** The one span of this name. */
    SpanData named(String name) {
        List<SpanData> named = spans.stream().filter(span -> span.getName().equals(name)).toList();
        if (named.size() != 1) {
            throw new AssertionError(named.size() + " spans named " + name + " among "
                    + spans.stream().map(SpanData::getName).toList());
        }
        return named.getFirst();
    }
}
