package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

        String event = (String) buildInstalledEventMethod().invoke(null, config);

        assertTrue(event.contains("\"phase\":\"agent-installed\""));
        assertTrue(event.contains(CaptureContext.jsonQuote(config.outputFile().getAbsolutePath())));
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
        Method method = RequestCaptureAgent.class.getDeclaredMethod("buildInstalledEvent", RequestCaptureConfig.class);
        method.setAccessible(true);
        return method;
    }

    private static Method buildServletServiceMatcherMethod() throws Exception {
        Method method = RequestCaptureAgent.class.getDeclaredMethod("buildServletServiceMatcher");
        method.setAccessible(true);
        return method;
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
}