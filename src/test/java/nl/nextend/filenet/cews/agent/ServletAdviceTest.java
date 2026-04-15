package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Byte Buddy advice entrypoints.
 *
 * <p>These tests call the advice methods directly, which gives coverage over the
 * integration between published agent runtime state, servlet entry/exit advice,
 * and input-stream read advice without requiring an actual application server.</p>
 */
class ServletAdviceTest {
    @TempDir
    Path tempDir;

    /**
     * Clears the published runtime between tests so the advice entrypoints always
     * observe a known baseline state.
     */
    @AfterEach
    void clearRuntime() throws Exception {
        runtimeReference().set(null);
    }

    /**
     * Verifies the happy path where servlet entry/exit advice and input-stream
     * advice cooperate to produce a complete start/end event pair.
     */
    @Test
    void serviceAndInputStreamAdvicesCaptureHttpRequestLifecycle() throws Exception {
        Path outputFile = tempDir.resolve("advice-capture.ndjson");
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile
                + ",includeUri=.*/FNCEWS.*"
                + ",captureBody=true,maxBodyBytes=5"
                + ",includeHeaders=SOAPAction|Authorization"
                + ",redactHeaders=Authorization"
        );
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        publishRuntime(config, writer);

        HttpServletRequest request = httpRequest(
            "/wsi/FNCEWS40MTOM/",
            "POST",
            "operation=Create",
            "10.0.0.4",
            "application/soap+xml;charset=UTF-8",
            6L,
            headers("SOAPAction", "Create", "Authorization", "secret")
        );
        HttpServletResponse response = httpResponse(207);

        ServletServiceAdvice.onEnter(request);
        ServletInputStreamAdvice.onExit((int) 'x');
        ServletInputStreamArrayReadAdvice.onExit("abcdef".getBytes(StandardCharsets.UTF_8), 1, 3);
        ServletInputStreamFullArrayReadAdvice.onExit("yz".getBytes(StandardCharsets.UTF_8), 2);
        ServletServiceAdvice.onExit(response, new RuntimeException("soap fault"));
        writer.close();

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());

        String startEvent = lines.get(0);
        String endEvent = lines.get(1);

        assertTrue(startEvent.contains("\"phase\":\"start\""));
        assertTrue(startEvent.contains("\"Authorization\":\"<redacted>\""));

        assertTrue(endEvent.contains("\"phase\":\"end\""));
        assertTrue(endEvent.contains("\"responseStatus\":207"));
        assertTrue(endEvent.contains("\"bodyPreview\":" + CaptureContext.jsonQuote("xbcdy")));
        assertTrue(endEvent.contains("\"bodyTruncated\":true"));
        assertTrue(endEvent.contains("\"totalBodyBytes\":6"));
        assertTrue(endEvent.contains("\"errorType\":\"java.lang.RuntimeException\""));
        assertTrue(endEvent.contains("\"errorMessage\":\"soap fault\""));
    }

    /**
     * Verifies that the service advice safely ignores generic non-HTTP servlet
     * arguments instead of trying to allocate capture state for unsupported traffic.
     */
    @Test
    void serviceAdviceIgnoresNonHttpServletArguments() throws Exception {
        Path outputFile = tempDir.resolve("advice-non-http.ndjson");
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile + ",includeUri=.*/FNCEWS.*"
        );
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        publishRuntime(config, writer);

        ServletRequest request = proxy(ServletRequest.class, (proxy, method, args) -> defaultValue(method));
        ServletResponse response = proxy(ServletResponse.class, (proxy, method, args) -> defaultValue(method));

        ServletServiceAdvice.onEnter(request);
        ServletInputStreamAdvice.onExit((int) 'x');
        ServletServiceAdvice.onExit(response, null);
        writer.close();

        assertTrue(!Files.exists(outputFile) || Files.size(outputFile) == 0L);
    }

    @Test
    void lowOverheadProfileEmitsOnlyEndEvent() throws Exception {
        Path outputFile = tempDir.resolve("advice-low-overhead.ndjson");
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + outputFile + ",profile=filenet-cews-low-overhead"
        );
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);
        publishRuntime(config, writer);

        HttpServletRequest request = httpRequest(
            "/wsi/FNCEWS40MTOM/",
            "POST",
            "operation=Create",
            "10.0.0.4",
            "application/soap+xml;charset=UTF-8",
            6L,
            headers("SOAPAction", "Create", "Authorization", "secret")
        );
        HttpServletResponse response = httpResponse(200);

        ServletServiceAdvice.onEnter(request);
        ServletInputStreamArrayReadAdvice.onExit("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6);
        ServletServiceAdvice.onExit(response, null);
        writer.close();

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"phase\":\"end\""));
        assertTrue(!lines.get(0).contains("\"phase\":\"start\""));
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> runtimeReference() throws Exception {
        Field runtimeField = RequestCaptureAgent.class.getDeclaredField("RUNTIME");
        runtimeField.setAccessible(true);
        AtomicReference<Object> runtime = (AtomicReference<Object>) runtimeField.get(null);
        assertNotNull(runtime);
        return runtime;
    }

    private static void publishRuntime(RequestCaptureConfig config, AsyncEventWriter writer) throws Exception {
        runtimeReference().set(newAgentRuntime(config, writer));
    }

    private static Object newAgentRuntime(RequestCaptureConfig config, AsyncEventWriter writer) throws Exception {
        Class<?> runtimeType = Class.forName("nl.nextend.filenet.cews.agent.RequestCaptureAgent$AgentRuntime");
        Constructor<?> constructor = runtimeType.getDeclaredConstructor(RequestCaptureConfig.class, AsyncEventWriter.class);
        constructor.setAccessible(true);
        return constructor.newInstance(config, writer);
    }

    private static HttpServletRequest httpRequest(String uri,
                                                  String method,
                                                  String query,
                                                  String remoteAddr,
                                                  String contentType,
                                                  long contentLength,
                                                  Map<String, String> headers) {
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
            return defaultValue(invokedMethod);
        });
    }

    private static HttpServletResponse httpResponse(int status) {
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
}