package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the request-scoped capture lifecycle managed by {@link CaptureState}.
 *
 * <p>These tests simulate servlet requests with lightweight dynamic proxies so the
 * capture flow can be exercised without a servlet container. The focus is on
 * start/end event emission, body accumulation, redaction, filtering, and error
 * reporting because those are the most important RCA-facing outputs.</p>
 */
class CaptureStateTest {
    @TempDir
    Path tempDir;

    /**
     * Verifies the normal request lifecycle from start event to end event,
     * including header redaction, body truncation, and response status capture.
     */
    @Test
    void beginAndEndWriteStartAndEndEventsWithRedactionAndBodyPreview() throws IOException {
        Path outputFile = tempDir.resolve("capture.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile
                + ",includeUri=.*/FNCEWS.*"
                + ",captureBody=true,maxBodyBytes=4"
                + ",includeHeaders=SOAPAction|Authorization"
                + ",redactHeaders=Authorization"
        );

        HttpServletRequest request = request(
            "/wsi/FNCEWS40MTOM/",
            "POST",
            "operation=Create",
            "10.0.0.1",
            "application/soap+xml;charset=UTF-8",
            6L,
            headers("SOAPAction", "Create", "Authorization", "secret")
        );
        HttpServletResponse response = response(202);

        CaptureState.begin(request, config, writer);
        CaptureState.onRead("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6);
        CaptureState.end(response, null, writer);
        writer.close();

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"phase\":\"start\""));
        assertTrue(lines.get(0).contains("\"Authorization\":\"<redacted>\""));
        assertTrue(lines.get(1).contains("\"phase\":\"end\""));
        assertTrue(lines.get(1).contains("\"responseStatus\":202"));
        assertTrue(lines.get(1).contains("\"bodyPreview\":\"abcd\""));
        assertTrue(lines.get(1).contains("\"bodyTruncated\":true"));
        assertTrue(lines.get(1).contains("\"totalBodyBytes\":6"));
    }

    /**
     * Verifies that requests outside the configured URI filter do not allocate a
     * capture context and do not emit any events.
     */
    @Test
    void beginSkipsRequestsThatDoNotMatchConfiguredUri() throws IOException {
        Path outputFile = tempDir.resolve("capture-skip.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile + ",includeUri=.*/FNCEWS.*"
        );

        CaptureState.begin(
            request("/health", "GET", null, "127.0.0.1", "text/plain", 0L, Collections.emptyMap()),
            config,
            writer
        );
        CaptureState.end(response(200), null, writer);
        writer.close();

        assertFalse(Files.exists(outputFile) && Files.size(outputFile) > 0);
    }

    /**
     * Verifies that exception details are surfaced in the end event so operator
     * tooling can distinguish transport failures from successful completions.
     */
    @Test
    void endEventIncludesErrorTypeAndMessageWhenThrowableIsPresent() throws IOException {
        Path outputFile = tempDir.resolve("capture-error.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile + ",includeUri=.*/FNCEWS.*"
        );
        IllegalStateException throwable = new IllegalStateException("transport timeout");

        CaptureState.begin(
            request(
                "/wsi/FNCEWS40DIME/",
                "POST",
                null,
                "10.0.0.2",
                "text/xml",
                0L,
                headers("SOAPAction", "Ping")
            ),
            config,
            writer
        );
        CaptureState.end(response(500), throwable, writer);
        writer.close();

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"errorType\":\"java.lang.IllegalStateException\""));
        assertTrue(lines.get(1).contains("\"errorMessage\":\"transport timeout\""));
        assertTrue(lines.get(1).contains("\"responseStatus\":500"));
    }

    /**
     * Verifies that header allowlisting and redaction work case-insensitively,
     * which matches how HTTP header names are treated in practice.
     */
    @Test
    void headerSelectionAndRedactionAreCaseInsensitive() throws IOException {
        Path outputFile = tempDir.resolve("capture-headers.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile
                + ",includeUri=.*/FNCEWS.*"
                + ",includeHeaders=soapaction|authorization"
                + ",redactHeaders=authorization"
        );

        CaptureState.begin(
            request(
                "/wsi/FNCEWS/",
                "POST",
                null,
                "10.0.0.3",
                "text/xml",
                0L,
                headers("SOAPAction", "Create", "Authorization", "secret", "X-Ignore-Me", "skip")
            ),
            config,
            writer
        );
        CaptureState.end(response(200), null, writer);
        writer.close();

        String startEvent = Files.readAllLines(outputFile, StandardCharsets.UTF_8).get(0);
        assertTrue(startEvent.contains("\"SOAPAction\":\"Create\""));
        assertTrue(startEvent.contains("\"Authorization\":\"<redacted>\""));
        assertFalse(startEvent.contains("X-Ignore-Me"));
    }

    @Test
    void lowOverheadModeWritesOnlyEndEventWithLightMetadata() throws IOException {
        Path outputFile = tempDir.resolve("capture-low-overhead.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile
                + ",profile=filenet-cews-low-overhead"
        );

        CaptureState.begin(
            request(
                "/wsi/FNCEWS40MTOM/",
                "POST",
                "operation=Create",
                "10.0.0.5",
                "application/soap+xml;charset=UTF-8",
                6L,
                headers("SOAPAction", "Create", "Authorization", "secret")
            ),
            config,
            writer
        );
        CaptureState.onRead("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6);
        CaptureState.end(response(200), null, writer);
        writer.close();

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"phase\":\"end\""));
        assertFalse(lines.get(0).contains("headers"));
        assertFalse(lines.get(0).contains("remoteAddr"));
    }

    @Test
    void nestedServletEntryDoesNotDuplicateCaptureLifecycle() throws IOException {
        Path outputFile = tempDir.resolve("capture-nested.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile + ",includeUri=.*/FNCEWS.*"
        );

        HttpServletRequest request = request(
            "/wsi/FNCEWS40MTOM/",
            "POST",
            null,
            "10.0.0.6",
            "text/xml",
            0L,
            headers("SOAPAction", "Create")
        );

        CaptureState.begin(request, config, writer);
        CaptureState.begin(request, config, writer);
        CaptureState.end(response(200), null, writer);
        CaptureState.end(response(200), null, writer);
        writer.close();

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"phase\":\"start\""));
        assertTrue(lines.get(1).contains("\"phase\":\"end\""));
    }

    @Test
    void asyncCompletionDefersEndEventUntilAsyncListenerCompletes() throws Exception {
        Path outputFile = tempDir.resolve("capture-async.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile + ",includeUri=.*/FNCEWS.*"
        );

        AsyncRequest asyncRequest = asyncRequest(
            "/wsi/FNCEWS40MTOM/",
            "POST",
            null,
            "10.0.0.7",
            "text/xml",
            6L,
            headers("SOAPAction", "Create")
        );
        HttpServletResponse response = response(202);

        CaptureState.begin(asyncRequest.request, config, writer);
        CaptureState.onRead("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6);
        CaptureState.end(asyncRequest.request, response, null, writer);

        List<String> beforeComplete = awaitLineCount(outputFile, 1);
        assertEquals(1, beforeComplete.size());
        assertTrue(beforeComplete.get(0).contains("\"phase\":\"start\""));

        asyncRequest.complete(response, null);
        writer.close();

        List<String> lines = awaitLineCount(outputFile, 2);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"phase\":\"end\""));
        assertTrue(lines.get(1).contains("\"responseStatus\":202"));
    }

    private static HttpServletRequest request(String uri,
                                              String method,
                                              String query,
                                              String remoteAddr,
                                              String contentType,
                                              long contentLength,
                                              Map<String, String> headers) {
        return request(uri, method, query, remoteAddr, contentType, contentLength, headers, null);
    }

    private static HttpServletRequest request(String uri,
                                              String method,
                                              String query,
                                              String remoteAddr,
                                              String contentType,
                                              long contentLength,
                                              Map<String, String> headers,
                                              AsyncController asyncController) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        return proxy(HttpServletRequest.class, (proxy, invokedMethod, args) -> {
            String name = invokedMethod.getName();
            if ("getRequestURI".equals(name)) {
                return uri;
            }
            if ("getMethod".equals(name)) {
                return method;
            }
            if ("getQueryString".equals(name)) {
                return query;
            }
            if ("getRemoteAddr".equals(name)) {
                return remoteAddr;
            }
            if ("getContentType".equals(name)) {
                return contentType;
            }
            if ("getContentLengthLong".equals(name)) {
                return contentLength;
            }
            if ("getHeaderNames".equals(name)) {
                return enumeration(headers.keySet());
            }
            if ("getHeader".equals(name)) {
                return headers.get(args[0]);
            }
            if ("getAttribute".equals(name)) {
                return attributes.get(args[0]);
            }
            if ("setAttribute".equals(name)) {
                attributes.put((String) args[0], args[1]);
                return null;
            }
            if ("removeAttribute".equals(name)) {
                attributes.remove(args[0]);
                return null;
            }
            if ("isAsyncStarted".equals(name)) {
                return asyncController != null && asyncController.started;
            }
            if ("getAsyncContext".equals(name)) {
                return asyncController == null ? null : asyncController.asyncContext;
            }
            return defaultValue(invokedMethod);
        });
    }

    private static AsyncRequest asyncRequest(String uri,
                                             String method,
                                             String query,
                                             String remoteAddr,
                                             String contentType,
                                             long contentLength,
                                             Map<String, String> headers) {
        AsyncController asyncController = new AsyncController();
        HttpServletRequest request = request(uri, method, query, remoteAddr, contentType, contentLength, headers, asyncController);
        asyncController.request = request;
        return new AsyncRequest(request, asyncController);
    }

    private static HttpServletResponse response(int status) {
        return proxy(HttpServletResponse.class, (proxy, invokedMethod, args) -> {
            if ("getStatus".equals(invokedMethod.getName())) {
                return status;
            }
            return defaultValue(invokedMethod);
        });
    }

    private static Map<String, String> headers(String... entries) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            headers.put(entries[index], entries[index + 1]);
        }
        return headers;
    }

    private static Enumeration<String> enumeration(Iterable<String> values) {
        List<String> entries = new ArrayList<>();
        for (String value : values) {
            entries.add(value);
        }
        return Collections.enumeration(entries);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (byte.class.equals(returnType)) {
            return (byte) 0;
        }
        if (short.class.equals(returnType)) {
            return (short) 0;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (float.class.equals(returnType)) {
            return 0F;
        }
        if (double.class.equals(returnType)) {
            return 0D;
        }
        if (char.class.equals(returnType)) {
            return '\0';
        }
        throw new IllegalStateException("Unsupported primitive return type: " + returnType);
    }

    private static List<String> readLinesIfPresent(Path outputFile) throws IOException {
        return Files.exists(outputFile)
            ? Files.readAllLines(outputFile, StandardCharsets.UTF_8)
            : Collections.<String>emptyList();
    }

    private static List<String> awaitLineCount(Path outputFile, int expectedLineCount) throws IOException {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<String> lines = readLinesIfPresent(outputFile);
            if (lines.size() == expectedLineCount) {
                return lines;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25L));
        }
        return readLinesIfPresent(outputFile);
    }

    private static final class AsyncRequest {
        private final HttpServletRequest request;
        private final AsyncController controller;

        private AsyncRequest(HttpServletRequest request, AsyncController controller) {
            this.request = request;
            this.controller = controller;
        }

        private void complete(HttpServletResponse response, Throwable throwable) throws Exception {
            controller.complete(response, throwable);
        }
    }

    private static final class AsyncController {
        private final AsyncContext asyncContext;

        private HttpServletRequest request;
        private AsyncListener listener;
        private boolean started = true;

        private AsyncController() {
            this.asyncContext = proxy(AsyncContext.class, (proxy, invokedMethod, args) -> {
                if ("addListener".equals(invokedMethod.getName())) {
                    listener = (AsyncListener) args[0];
                    return null;
                }
                return defaultValue(invokedMethod);
            });
        }

        private void complete(HttpServletResponse response, Throwable throwable) throws Exception {
            started = false;
            if (listener != null) {
                listener.onComplete(new AsyncEvent(asyncContext, request, response, throwable));
            }
        }
    }
}