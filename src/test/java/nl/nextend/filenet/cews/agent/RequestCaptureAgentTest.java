package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.bytebuddy.description.method.MethodDescription;

/**
 * Tests for the high-level agent runtime publication helpers in
 * {@link RequestCaptureAgent}.
 *
 * <p>These tests use reflection because the published runtime holder and installed
 * event builder are intentionally private implementation details. The goal is to
 * verify that the public static accessors expose exactly the runtime snapshot that
 * the advice layer depends on.</p>
 */
class RequestCaptureAgentTest {
    @TempDir
    File tempDir;

    /**
     * Clears the published runtime between tests so static state cannot leak from
     * one test case into the next.
     */
    @AfterEach
    void clearRuntime() throws Exception {
        runtimeReference().set(null);
    }

    /**
     * Verifies that once a runtime snapshot is published, the static accessors
     * expose the same config and writer instances that were stored.
     */
    @Test
    void configAndWriterReturnPublishedRuntimeState() throws Exception {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + new File(tempDir, "agent.ndjson").getAbsolutePath());
        AsyncEventWriter writer = new AsyncEventWriter(new File(tempDir, "agent.ndjson"), 8);

        runtimeReference().set(newAgentRuntime(config, writer));

        assertSame(config, RequestCaptureAgent.config());
        assertSame(writer, RequestCaptureAgent.writer());

        writer.close();
    }

    /**
     * Verifies that the synthetic installed event emitted at agent startup carries
     * the expected phase marker and output path for operational diagnostics.
     */
    @Test
    void buildInstalledEventIncludesInstalledPhaseAndOutputPath() throws Exception {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "output=" + new File(tempDir, "agent-installed.ndjson").getAbsolutePath());

        String event = (String) buildInstalledEventMethod().invoke(null, config, instrumentation(true));

        assertTrue(event.contains("\"phase\":\"agent-installed\""));
        assertTrue(event.contains(CaptureContext.jsonQuote(config.outputFile().getAbsolutePath())));
        assertTrue(event.contains("\"retransformSupported\":true"));
    }

    @Test
    void buildTransformationEventIncludesPhaseAndType() throws Exception {
        String event = (String) buildTransformationEventMethod().invoke(null, "javax.servlet.http.HttpServlet");

        assertTrue(event.contains("\"phase\":\"agent-transform\""));
        assertTrue(event.contains("\"type\":\"javax.servlet.http.HttpServlet\""));
    }

    /**
     * Verifies that the static accessors remain null-safe before the runtime has
     * been published.
     */
    @Test
    void configAndWriterReturnNullWhenRuntimeNotPublished() throws Exception {
        runtimeReference().set(null);

        assertNull(RequestCaptureAgent.config());
        assertNull(RequestCaptureAgent.writer());
    }

    @Test
    void servletServiceMatcherCoversGenericAndHttpOverloads() throws Exception {
        @SuppressWarnings("unchecked")
        net.bytebuddy.matcher.ElementMatcher<MethodDescription> matcher =
            (net.bytebuddy.matcher.ElementMatcher<MethodDescription>) buildServletServiceMatcherMethod().invoke(null);

        assertTrue(matcher.matches(new MethodDescription.ForLoadedMethod(
            TestServlet.class.getDeclaredMethod("service", ServletRequest.class, ServletResponse.class))));
        assertTrue(matcher.matches(new MethodDescription.ForLoadedMethod(
            TestServlet.class.getDeclaredMethod("service", HttpServletRequest.class, HttpServletResponse.class))));
        assertTrue(!matcher.matches(new MethodDescription.ForLoadedMethod(
            TestServlet.class.getDeclaredMethod("notService", HttpServletRequest.class, HttpServletResponse.class))));
    }

    @Test
    void filterDoFilterMatcherCoversStandardFilterSignature() throws Exception {
        @SuppressWarnings("unchecked")
        net.bytebuddy.matcher.ElementMatcher<MethodDescription> matcher =
            (net.bytebuddy.matcher.ElementMatcher<MethodDescription>) buildFilterDoFilterMatcherMethod().invoke(null);

        assertTrue(matcher.matches(new MethodDescription.ForLoadedMethod(
            TestFilter.class.getDeclaredMethod("doFilter", ServletRequest.class, ServletResponse.class, javax.servlet.FilterChain.class))));
        assertTrue(!matcher.matches(new MethodDescription.ForLoadedMethod(
            TestFilter.class.getDeclaredMethod("notDoFilter", ServletRequest.class, ServletResponse.class, javax.servlet.FilterChain.class))));
    }

    @Test
    void retransformationIsEnabledWhenInstrumentationSupportsIt() throws Exception {
        assertTrue((Boolean) shouldUseRetransformationMethod().invoke(null, instrumentation(true)));
        assertTrue(!(Boolean) shouldUseRetransformationMethod().invoke(null, instrumentation(false)));
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Object> runtimeReference() throws Exception {
        Field runtimeField = RequestCaptureAgent.class.getDeclaredField("RUNTIME");
        runtimeField.setAccessible(true);
        AtomicReference<Object> runtime = (AtomicReference<Object>) runtimeField.get(null);
        assertNotNull(runtime);
        return runtime;
    }

    private static Object newAgentRuntime(RequestCaptureConfig config, AsyncEventWriter writer) throws Exception {
        Class<?> runtimeType = Class.forName("nl.nextend.filenet.cews.agent.RequestCaptureAgent$AgentRuntime");
        Constructor<?> constructor = runtimeType.getDeclaredConstructor(RequestCaptureConfig.class, AsyncEventWriter.class);
        constructor.setAccessible(true);
        return constructor.newInstance(config, writer);
    }

    private static Method buildInstalledEventMethod() throws Exception {
        Method method = RequestCaptureAgent.class.getDeclaredMethod("buildInstalledEvent", RequestCaptureConfig.class,
            Instrumentation.class);
        method.setAccessible(true);
        return method;
    }

    private static Method shouldUseRetransformationMethod() throws Exception {
        Method method = RequestCaptureAgent.class.getDeclaredMethod("shouldUseRetransformation", Instrumentation.class);
        method.setAccessible(true);
        return method;
    }

    private static Method buildTransformationEventMethod() throws Exception {
        Class<?> listenerType = Class.forName("nl.nextend.filenet.cews.agent.RequestCaptureAgent$TransformationLoggingListener");
        Method method = listenerType.getDeclaredMethod("buildTransformationEvent", String.class);
        method.setAccessible(true);
        return method;
    }

    private static Method buildServletServiceMatcherMethod() throws Exception {
        Method method = RequestCaptureAgent.class.getDeclaredMethod("buildServletServiceMatcher");
        method.setAccessible(true);
        return method;
    }

    private static Method buildFilterDoFilterMatcherMethod() throws Exception {
        Method method = RequestCaptureAgent.class.getDeclaredMethod("buildFilterDoFilterMatcher");
        method.setAccessible(true);
        return method;
    }

    private static Instrumentation instrumentation(boolean retransformSupported) {
        return (Instrumentation) Proxy.newProxyInstance(
            RequestCaptureAgentTest.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                if ("isRetransformClassesSupported".equals(method.getName())) {
                    return retransformSupported;
                }
                Class<?> returnType = method.getReturnType();
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
                    return (char) 0;
                }
                return null;
            });
    }

    @SuppressWarnings("unused")
    private static final class TestServlet {
        public void service(ServletRequest request, ServletResponse response) {
            // Signature-only helper used to validate Byte Buddy matcher coverage.
            if (request == response) {
                throw new IllegalStateException();
            }
        }

        protected void service(HttpServletRequest request, HttpServletResponse response) {
            // Signature-only helper used to validate Byte Buddy matcher coverage.
            if (request == response) {
                throw new IllegalStateException();
            }
        }

        public void notService(HttpServletRequest request, HttpServletResponse response) {
            // Signature-only helper used to validate Byte Buddy matcher coverage.
            if (request == response) {
                throw new IllegalStateException();
            }
        }
    }

    @SuppressWarnings("unused")
    private static final class TestFilter {
        public void doFilter(ServletRequest request, ServletResponse response, javax.servlet.FilterChain chain) {
            if (request == response || chain == null) {
                throw new IllegalStateException();
            }
        }

        public void notDoFilter(ServletRequest request, ServletResponse response, javax.servlet.FilterChain chain) {
            if (request == response || chain == null) {
                throw new IllegalStateException();
            }
        }
    }
}