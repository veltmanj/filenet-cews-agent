package nl.nextend.filenet.cews.agent;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thread-local bridge between Byte Buddy advice callbacks and per-request capture
 * state.
 *
 * <p>The servlet request is processed on one request thread, so the agent can use
 * a {@link ThreadLocal} to associate body reads with the request that triggered
 * them. The context is created on servlet entry and removed on exit.</p>
 */
public final class CaptureState {
    private static final String REDACTED_HEADER_VALUE = "<redacted>";
    private static final String ACTIVE_CAPTURE_ATTRIBUTE = CaptureState.class.getName() + ".active";

    private static final ThreadLocal<ActiveCapture> CURRENT = new ThreadLocal<>();

    private CaptureState() {
    }

    /**
     * Starts request capture for the current thread if the request matches the
     * configured filters and sampling rules.
     */
    public static void begin(Object request, RequestCaptureConfig config, AsyncEventWriter writer) {
        if (!canBeginCapture(request, config, writer)) {
            return;
        }
        ActiveCapture activeCapture = activeCapture(request);
        if (activeCapture != null) {
            CURRENT.set(activeCapture);
            return;
        }
        String uri = ServletApiBridge.requestUri(request);
        if (uri == null) {
            return;
        }
        if (!config.matchesUri(uri) || !shouldCaptureSample(config)) {
            return;
        }
        activeCapture = new ActiveCapture(request, createContext(request, config, uri));
        ServletApiBridge.setAttribute(request, ACTIVE_CAPTURE_ATTRIBUTE, activeCapture);
        CURRENT.set(activeCapture);
        if (config.emitStartEvent()) {
            writer.enqueue(activeCapture.context.toStartEventJson());
        }
    }

    /**
     * Appends a block read to the current request context, if one is active.
     */
    public static void onRead(byte[] bytes, int offset, int length) {
        ActiveCapture activeCapture = CURRENT.get();
        if (activeCapture != null && bytes != null && length > 0) {
            activeCapture.context.onRead(bytes, offset, length);
        }
    }

    /**
     * Appends a single-byte read to the current request context, if one is active.
     */
    public static void onRead(int value) {
        ActiveCapture activeCapture = CURRENT.get();
        if (activeCapture != null) {
            activeCapture.context.onRead(value);
        }
    }

    /**
     * Completes capture for the current request and emits the closing event.
     */
    public static void end(Object response, Throwable throwable, AsyncEventWriter writer) {
        end(null, response, throwable, writer);
    }

    public static void end(Object request,
                           Object response,
                           Throwable throwable,
                           AsyncEventWriter writer) {
        ActiveCapture activeCapture = currentCapture(request);
        if (activeCapture == null || writer == null) {
            CURRENT.remove();
            clearRequestAttribute(request, activeCapture);
            return;
        }
        if (request != null && ServletApiBridge.isAsyncStarted(request)) {
            activeCapture.rememberThrowable(throwable);
            registerAsyncListener(request, response, activeCapture, writer);
            CURRENT.remove();
            return;
        }
        finish(activeCapture, response, throwable, writer);
    }

    private static boolean canBeginCapture(Object request,
                                           RequestCaptureConfig config,
                                           AsyncEventWriter writer) {
        return request != null && config != null && writer != null;
    }

    private static boolean shouldCaptureSample(RequestCaptureConfig config) {
        return config.sampleRate() <= 1 || ThreadLocalRandom.current().nextInt(config.sampleRate()) == 0;
    }

    private static ActiveCapture currentCapture(Object request) {
        ActiveCapture activeCapture = CURRENT.get();
        if (activeCapture != null) {
            return activeCapture;
        }
        return activeCapture(request);
    }

    private static ActiveCapture activeCapture(Object request) {
        if (request == null) {
            return null;
        }
        Object attribute = ServletApiBridge.getAttribute(request, ACTIVE_CAPTURE_ATTRIBUTE);
        return attribute instanceof ActiveCapture ? (ActiveCapture) attribute : null;
    }

    private static void registerAsyncListener(Object request,
                                              Object response,
                                              ActiveCapture activeCapture,
                                              AsyncEventWriter writer) {
        if (!activeCapture.markAsyncListenerRegistered()) {
            return;
        }
        Object asyncContext = ServletApiBridge.asyncContext(request);
        ServletApiBridge.addAsyncListener(asyncContext, request, response, new AsyncCompletionCallbacks(activeCapture, writer));
    }

    private static void finish(ActiveCapture activeCapture,
                               Object response,
                               Throwable throwable,
                               AsyncEventWriter writer) {
        if (!activeCapture.markCompleted()) {
            CURRENT.remove();
            clearRequestAttribute(activeCapture.request, activeCapture);
            return;
        }
        int responseStatus = ServletApiBridge.responseStatus(response);
        writer.enqueue(activeCapture.context.toEndEventJson(activeCapture.resolveThrowable(throwable),
            responseStatus,
            writer.droppedEvents()));
        CURRENT.remove();
        clearRequestAttribute(activeCapture.request, activeCapture);
    }

    private static void clearRequestAttribute(Object request, ActiveCapture activeCapture) {
        if (request == null) {
            return;
        }
        if (activeCapture == null || ServletApiBridge.getAttribute(request, ACTIVE_CAPTURE_ATTRIBUTE) == activeCapture) {
            ServletApiBridge.removeAttribute(request, ACTIVE_CAPTURE_ATTRIBUTE);
        }
    }

    private static CaptureContext createContext(Object request,
                                                RequestCaptureConfig config,
                                                String uri) {
        String contentType = ServletApiBridge.contentType(request);
        return new CaptureContext(
            System.nanoTime(),
            System.currentTimeMillis(),
            new CaptureContext.RequestMetadata(
                ServletApiBridge.requestMethod(request),
                uri,
                config.metadataLight() ? null : ServletApiBridge.queryString(request),
                config.metadataLight() ? null : ServletApiBridge.remoteAddress(request),
                contentType,
                config.metadataLight() ? -1L : ServletApiBridge.contentLength(request),
                config.metadataLight() ? Collections.<String, String>emptyMap() : captureHeaders(request, config)
            ),
            config.maxBodyBytes(),
            config.shouldCaptureBodyContentType(contentType),
            config.metadataLight() ? RequestCaptureConfig.MetadataMode.LIGHT : RequestCaptureConfig.MetadataMode.FULL
        );
    }

    private static Map<String, String> captureHeaders(Object request, RequestCaptureConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = ServletApiBridge.headerNames(request);
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (!config.shouldIncludeHeader(headerName)) {
                continue;
            }
            headers.put(headerName, resolveHeaderValue(request, config, headerName));
        }
        return headers;
    }

    private static String resolveHeaderValue(Object request,
                                             RequestCaptureConfig config,
                                             String headerName) {
        if (config.shouldRedactHeader(headerName)) {
            return REDACTED_HEADER_VALUE;
        }
        return ServletApiBridge.header(request, headerName);
    }

    private static final class ActiveCapture {
        private final Object request;
        private final CaptureContext context;

        private boolean asyncListenerRegistered;
        private boolean completed;
        private Throwable deferredThrowable;

        private ActiveCapture(Object request, CaptureContext context) {
            this.request = request;
            this.context = context;
        }

        private synchronized boolean markAsyncListenerRegistered() {
            if (asyncListenerRegistered || completed) {
                return false;
            }
            asyncListenerRegistered = true;
            return true;
        }

        private synchronized void rememberThrowable(Throwable throwable) {
            if (throwable != null && deferredThrowable == null) {
                deferredThrowable = throwable;
            }
        }

        private synchronized Throwable resolveThrowable(Throwable throwable) {
            return throwable != null ? throwable : deferredThrowable;
        }

        private synchronized boolean markCompleted() {
            if (completed) {
                return false;
            }
            completed = true;
            return true;
        }
    }

    private static final class AsyncCompletionCallbacks implements ServletApiBridge.AsyncCallbacks {
        private final ActiveCapture activeCapture;
        private final AsyncEventWriter writer;

        private AsyncCompletionCallbacks(ActiveCapture activeCapture, AsyncEventWriter writer) {
            this.activeCapture = activeCapture;
            this.writer = writer;
        }

        @Override
        public void onComplete(Object asyncEvent) {
            finish(activeCapture, ServletApiBridge.suppliedResponse(asyncEvent), null, writer);
        }

        @Override
        public void onTimeout(Object asyncEvent) {
            finish(activeCapture, ServletApiBridge.suppliedResponse(asyncEvent), new IllegalStateException("Async request timed out"), writer);
        }

        @Override
        public void onError(Object asyncEvent) {
            finish(activeCapture, ServletApiBridge.suppliedResponse(asyncEvent), ServletApiBridge.asyncThrowable(asyncEvent), writer);
        }
    }
}
