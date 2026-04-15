package nl.nextend.filenet.cews.agent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable per-request capture state.
 *
 * <p>An instance of this class is created when a matching servlet request enters
 * the instrumented service method. It accumulates request metadata, body preview
 * bytes and timing information until the request completes, after which it can be
 * rendered as start and end JSON events.</p>
 */
final class CaptureContext {
    private static final int DEFAULT_PREVIEW_BUFFER_SIZE = 1024;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final long startNanos;
    private final long startMillis;
    private final String method;
    private final String uri;
    private final String query;
    private final String remoteAddr;
    private final String contentType;
    private final long contentLength;
    private final Map<String, String> headers;
    private final ByteArrayOutputStream bodyPreview;
    private final int maxBodyBytes;
    private final boolean bodyCaptureEnabled;
    private final RequestCaptureConfig.MetadataMode metadataMode;

    private long totalBytesRead;
    private boolean bodyTruncated;

    /**
     * Immutable request metadata captured at servlet entry time.
     */
    static final class RequestMetadata {
        private final String method;
        private final String uri;
        private final String query;
        private final String remoteAddr;
        private final String contentType;
        private final long contentLength;
        private final Map<String, String> headers;

        RequestMetadata(String method,
                        String uri,
                        String query,
                        String remoteAddr,
                        String contentType,
                        long contentLength,
                        Map<String, String> headers) {
            this.method = method;
            this.uri = uri;
            this.query = query;
            this.remoteAddr = remoteAddr;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.headers = new LinkedHashMap<>(headers);
        }
    }

    /**
     * Creates a new request capture context for one servlet invocation.
     */
    CaptureContext(long startNanos,
                   long startMillis,
                   RequestMetadata metadata,
                   int maxBodyBytes,
                   boolean bodyCaptureEnabled,
                   RequestCaptureConfig.MetadataMode metadataMode) {
        this.startNanos = startNanos;
        this.startMillis = startMillis;
        this.method = metadata.method;
        this.uri = metadata.uri;
        this.query = metadata.query;
        this.remoteAddr = metadata.remoteAddr;
        this.contentType = metadata.contentType;
        this.contentLength = metadata.contentLength;
        this.headers = metadata.headers;
        this.maxBodyBytes = maxBodyBytes;
        this.bodyCaptureEnabled = bodyCaptureEnabled;
        this.metadataMode = metadataMode;
        this.bodyPreview = new ByteArrayOutputStream(Math.min(maxBodyBytes, DEFAULT_PREVIEW_BUFFER_SIZE));
    }

    /**
     * Serializes the request-start event emitted as soon as the matching request
     * enters the servlet service method.
     */
    String toStartEventJson() {
        StringBuilder builder = new StringBuilder(512);
        appendCommonFields(builder, "start", 0L, null, -1);
        builder.append('}');
        return builder.toString();
    }

    /**
     * Serializes the request-end event emitted when servlet processing returns.
     */
    String toEndEventJson(Throwable throwable, int responseStatus, long droppedEvents) {
        long elapsedMillis = elapsedMillis();
        StringBuilder builder = new StringBuilder(1024);
        appendCommonFields(builder, "end", elapsedMillis, throwable, responseStatus);
        builder.append(',').append("\"totalBodyBytes\":").append(totalBytesRead);
        builder.append(',').append("\"bodyTruncated\":").append(bodyTruncated);
        builder.append(',').append("\"writerDroppedEvents\":").append(droppedEvents);
        if (bodyCaptureEnabled) {
            builder.append(',').append("\"bodyPreview\":").append(jsonQuote(previewAsString()));
        }
        builder.append('}');
        return builder.toString();
    }

    /**
     * Records a block read from the servlet input stream.
     */
    void onRead(byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return;
        }
        totalBytesRead += length;
        appendBodyPreview(bytes, offset, length);
    }

    /**
     * Records a single-byte read from the servlet input stream.
     */
    void onRead(int value) {
        if (value < 0) {
            return;
        }
        totalBytesRead += 1;
        if (cannotCaptureMoreBody()) {
            markTruncatedWhenPreviewFull();
            return;
        }
        bodyPreview.write(value);
    }

    private long elapsedMillis() {
        return (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND;
    }

    private void appendBodyPreview(byte[] bytes, int offset, int length) {
        if (cannotCaptureMoreBody()) {
            markTruncatedWhenPreviewFull();
            return;
        }
        int remaining = maxBodyBytes - bodyPreview.size();
        int copyLength = Math.min(remaining, length);
        bodyPreview.write(bytes, offset, copyLength);
        if (copyLength < length) {
            bodyTruncated = true;
        }
    }

    private boolean cannotCaptureMoreBody() {
        return !bodyCaptureEnabled || previewIsFull();
    }

    private boolean previewIsFull() {
        return bodyPreview.size() >= maxBodyBytes;
    }

    private void markTruncatedWhenPreviewFull() {
        if (bodyCaptureEnabled && previewIsFull()) {
            bodyTruncated = true;
        }
    }

    private void appendCommonFields(StringBuilder builder,
                                    String phase,
                                    long elapsedMillis,
                                    Throwable throwable,
                                    int responseStatus) {
        builder.append('{');
        builder.append("\"timestamp\":").append(jsonQuote(Instant.ofEpochMilli(startMillis).toString()));
        builder.append(',').append("\"phase\":").append(jsonQuote(phase));
        builder.append(',').append("\"method\":").append(jsonQuote(method));
        builder.append(',').append("\"uri\":").append(jsonQuote(uri));
        builder.append(',').append("\"contentType\":").append(jsonQuote(contentType));
        if (RequestCaptureConfig.MetadataMode.FULL.equals(metadataMode)) {
            builder.append(',').append("\"query\":").append(jsonQuote(query));
            builder.append(',').append("\"remoteAddr\":").append(jsonQuote(remoteAddr));
            builder.append(',').append("\"contentLength\":").append(contentLength);
            builder.append(',').append("\"headers\":").append(headersJson());
        }
        builder.append(',').append("\"elapsedMillis\":").append(elapsedMillis);
        if (responseStatus >= 0) {
            builder.append(',').append("\"responseStatus\":").append(responseStatus);
        }
        if (throwable != null) {
            builder.append(',').append("\"errorType\":").append(jsonQuote(throwable.getClass().getName()));
            builder.append(',').append("\"errorMessage\":").append(jsonQuote(throwable.getMessage()));
        }
    }

    private String headersJson() {
        StringBuilder builder = new StringBuilder(256);
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(jsonQuote(entry.getKey())).append(':').append(jsonQuote(entry.getValue()));
        }
        builder.append('}');
        return builder.toString();
    }

    private String previewAsString() {
        String preview = new String(bodyPreview.toByteArray(), StandardCharsets.UTF_8);
        return preview.replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * Quotes a string as a JSON string literal.
     *
     * <p>This utility is intentionally local to the agent so no external JSON
     * library is required in the shaded runtime.</p>
     */
    static String jsonQuote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 16);
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"' || current == '\\') {
                builder.append('\\').append(current);
            } else if (current == '\b') {
                builder.append("\\b");
            } else if (current == '\f') {
                builder.append("\\f");
            } else if (current == '\n') {
                builder.append("\\n");
            } else if (current == '\r') {
                builder.append("\\r");
            } else if (current == '\t') {
                builder.append("\\t");
            } else if (current < 0x20) {
                builder.append(String.format("\\u%04x", (int) current));
            } else {
                builder.append(current);
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
