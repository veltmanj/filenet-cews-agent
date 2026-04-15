package nl.nextend.filenet.cews.agent;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Thread-local bridge between Byte Buddy advice callbacks and per-request capture
 * state.
 *
 * <p>The servlet request is processed on one request thread, so the agent can use
 * a {@link ThreadLocal} to associate body reads with the request that triggered
 * them. The context is created on servlet entry and removed on exit.</p>
 */
final class CaptureState {
    private static final String REDACTED_HEADER_VALUE = "<redacted>";

    private static final ThreadLocal<CaptureContext> CURRENT = new ThreadLocal<>();

    private CaptureState() {
    }

    /**
     * Starts request capture for the current thread if the request matches the
     * configured filters and sampling rules.
     */
    static void begin(HttpServletRequest request, RequestCaptureConfig config, AsyncEventWriter writer) {
        if (!canBeginCapture(request, config, writer)) {
            return;
        }
        String uri = request.getRequestURI();
        if (!config.matchesUri(uri) || !shouldCaptureSample(config)) {
            return;
        }
        CaptureContext context = createContext(request, config, uri);
        CURRENT.set(context);
        if (config.emitStartEvent()) {
            writer.enqueue(context.toStartEventJson());
        }
    }

    /**
     * Appends a block read to the current request context, if one is active.
     */
    static void onRead(byte[] bytes, int offset, int length) {
        CaptureContext context = CURRENT.get();
        if (context != null && bytes != null && length > 0) {
            context.onRead(bytes, offset, length);
        }
    }

    /**
     * Appends a single-byte read to the current request context, if one is active.
     */
    static void onRead(int value) {
        CaptureContext context = CURRENT.get();
        if (context != null) {
            context.onRead(value);
        }
    }

    /**
     * Completes capture for the current request and emits the closing event.
     */
    static void end(HttpServletResponse response, Throwable throwable, AsyncEventWriter writer) {
        CaptureContext context = CURRENT.get();
        if (context == null || writer == null) {
            CURRENT.remove();
            return;
        }
        int responseStatus = response == null ? -1 : response.getStatus();
        writer.enqueue(context.toEndEventJson(throwable, responseStatus, writer.droppedEvents()));
        CURRENT.remove();
    }

    private static boolean canBeginCapture(HttpServletRequest request,
                                           RequestCaptureConfig config,
                                           AsyncEventWriter writer) {
        return request != null && config != null && writer != null;
    }

    private static boolean shouldCaptureSample(RequestCaptureConfig config) {
        return config.sampleRate() <= 1 || ThreadLocalRandom.current().nextInt(config.sampleRate()) == 0;
    }

    private static CaptureContext createContext(HttpServletRequest request,
                                                RequestCaptureConfig config,
                                                String uri) {
        return new CaptureContext(
            System.nanoTime(),
            System.currentTimeMillis(),
            new CaptureContext.RequestMetadata(
                request.getMethod(),
                uri,
                config.metadataLight() ? null : request.getQueryString(),
                config.metadataLight() ? null : request.getRemoteAddr(),
                request.getContentType(),
                config.metadataLight() ? -1L : request.getContentLengthLong(),
                config.metadataLight() ? Collections.<String, String>emptyMap() : captureHeaders(request, config)
            ),
            config.maxBodyBytes(),
            config.shouldCaptureBodyContentType(request.getContentType()),
            config.metadataLight() ? RequestCaptureConfig.MetadataMode.LIGHT : RequestCaptureConfig.MetadataMode.FULL
        );
    }

    private static Map<String, String> captureHeaders(HttpServletRequest request, RequestCaptureConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (!config.shouldIncludeHeader(headerName)) {
                continue;
            }
            headers.put(headerName, resolveHeaderValue(request, config, headerName));
        }
        return headers;
    }

    private static String resolveHeaderValue(HttpServletRequest request,
                                             RequestCaptureConfig config,
                                             String headerName) {
        if (config.shouldRedactHeader(headerName)) {
            return REDACTED_HEADER_VALUE;
        }
        return request.getHeader(headerName);
    }
}
