package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

import nl.nextend.filenet.cews.agent.support.LibertyTestWar;
import nl.nextend.filenet.cews.agent.support.LibertyWithAgentContainer;

@Testcontainers
class LibertyInstrumentationIT {
    private static final String COMMON_AGENT_ARGS =
        "captureBody=true,maxBodyBytes=64,includeHeaders=SOAPAction|Content-Type,diagnosticTransforms=false";
    private static final String SYNC_AGENT_ARGS = "includeUri=.*/sync/.*," + COMMON_AGENT_ARGS;
    private static final String ASYNC_AGENT_ARGS = "includeUri=.*/async/.*," + COMMON_AGENT_ARGS;

    @TempDir
    Path tempDir;

    @Test
    void agentCapturesSyncAndAsyncRequestsAcrossTwoLibertyInstances() throws Exception {
        Path warFile = LibertyTestWar.build(tempDir.resolve("capture-test.war"));
        Path agentJar = agentJar();

        try (LibertyWithAgentContainer syncContainer = new LibertyWithAgentContainer(
                "sync",
                warFile,
                agentJar,
                SYNC_AGENT_ARGS);
             LibertyWithAgentContainer asyncContainer = new LibertyWithAgentContainer(
                 "async",
                 warFile,
                 agentJar,
                 ASYNC_AGENT_ARGS)) {
            syncContainer.start();
            asyncContainer.start();

            HttpResponse syncResponse = post(syncContainer.endpoint("/capture/sync/echo"), "hello-sync", "SyncAction");
            HttpResponse asyncResponse = post(asyncContainer.endpoint("/capture/async/echo"), "hello-async", "AsyncAction");

            assertEquals(200, syncResponse.statusCode);
            assertEquals("sync:hello-sync", syncResponse.body);
            assertEquals(202, asyncResponse.statusCode);
            assertEquals("async:hello-async", asyncResponse.body);

            List<String> syncEvents = awaitEvents(syncContainer, "\"uri\":\"/capture/sync/echo\"", "\"phase\":\"end\"");
            List<String> asyncEvents = awaitEvents(asyncContainer, "\"uri\":\"/capture/async/echo\"", "\"phase\":\"end\"");
            writeEventsArtifact("sync", syncEvents);
            writeEventsArtifact("async", asyncEvents);
            long syncElapsedMillis = endEventLongField(syncEvents, "elapsedMillis");
            long asyncElapsedMillis = endEventLongField(asyncEvents, "elapsedMillis");

            assertContainsPhase(syncEvents, "agent-installed");
            assertContains(syncEvents, "\"uri\":\"/capture/sync/echo\"");
            assertContains(syncEvents, "\"contentLength\":10");
            assertContains(syncEvents, "\"phase\":\"end\"");

            assertContainsPhase(asyncEvents, "agent-installed");
            assertContains(asyncEvents, "\"uri\":\"/capture/async/echo\"");
            assertContains(asyncEvents, "\"responseStatus\":202");
            assertContains(asyncEvents, "\"contentLength\":11");
            assertContains(asyncEvents, "\"phase\":\"end\"");
            assertTrue(asyncElapsedMillis >= 100L, "Expected async elapsedMillis >= 100 but was " + asyncElapsedMillis);
            assertTrue(asyncElapsedMillis > syncElapsedMillis,
                "Expected async elapsedMillis to exceed sync elapsedMillis but got async="
                    + asyncElapsedMillis
                    + " sync="
                    + syncElapsedMillis);
        }
    }

    private static Path agentJar() {
        String buildDirectory = System.getProperty("agent.build.directory");
        String projectVersion = System.getProperty("agent.project.version");
        if (buildDirectory == null || projectVersion == null) {
            throw new IllegalStateException("Missing build metadata for integration test");
        }
        return Paths.get(buildDirectory, "filenet-cews-agent-" + projectVersion + ".jar");
    }

    private static void writeEventsArtifact(String instanceName, List<String> events) throws IOException {
        Path artifactDirectory = eventsArtifactDirectory();
        Files.createDirectories(artifactDirectory);
        Path artifactFile = artifactDirectory.resolve(instanceName + ".ndjson");
        String contents = String.join(System.lineSeparator(), events) + System.lineSeparator();
        Files.write(artifactFile, contents.getBytes(StandardCharsets.UTF_8));
        System.out.println("Liberty E2E NDJSON (" + instanceName + "): " + artifactFile.toAbsolutePath());
        if (Boolean.getBoolean("liberty.e2e.printEvents")) {
            System.out.println(contents);
        }
    }

    private static Path eventsArtifactDirectory() {
        String configuredDirectory = System.getProperty("liberty.e2e.events.dir");
        if (configuredDirectory != null && !configuredDirectory.trim().isEmpty()) {
            return Paths.get(configuredDirectory);
        }
        String buildDirectory = System.getProperty("agent.build.directory");
        if (buildDirectory == null || buildDirectory.trim().isEmpty()) {
            throw new IllegalStateException("Missing build directory for Liberty E2E event artifacts");
        }
        return Paths.get(buildDirectory, "liberty-e2e-events");
    }

    private static HttpResponse post(String endpoint, String payload, String soapAction) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
        connection.setRequestProperty("SOAPAction", soapAction);
        byte[] requestBytes = payload.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(requestBytes.length);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(requestBytes);
        }
        int statusCode = connection.getResponseCode();
        try (InputStream inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            return new HttpResponse(statusCode, readBody(inputStream));
        }
    }

    private static String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertContainsPhase(List<String> events, String phase) {
        assertContains(events, "\"phase\":\"" + phase + "\"");
    }

    private static void assertContains(List<String> events, String fragment) {
        assertTrue(containsFragment(events, fragment), "Expected fragment not found: " + fragment + " in " + events);
    }

    private static long endEventLongField(List<String> events, String fieldName) {
        String endEvent = endEvent(events);
        String marker = "\"" + fieldName + "\":";
        int markerIndex = endEvent.indexOf(marker);
        if (markerIndex < 0) {
            throw new IllegalStateException("Missing field " + fieldName + " in " + endEvent);
        }
        int valueStart = markerIndex + marker.length();
        int valueEnd = valueStart;
        while (valueEnd < endEvent.length() && Character.isDigit(endEvent.charAt(valueEnd))) {
            valueEnd++;
        }
        return Long.parseLong(endEvent.substring(valueStart, valueEnd));
    }

    private static String endEvent(List<String> events) {
        for (String event : events) {
            if (event.contains("\"phase\":\"end\"")) {
                return event;
            }
        }
        throw new IllegalStateException("Missing end event in " + events);
    }

    private static boolean containsFragment(List<String> events, String fragment) {
        for (String event : events) {
            if (event.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> awaitEvents(LibertyWithAgentContainer container, String requiredFragment, String endFragment)
        throws IOException {
        List<String> lastEvents = container.readEventLines();
        for (int attempt = 0; attempt < 20; attempt++) {
            if (containsFragment(lastEvents, requiredFragment) && containsFragment(lastEvents, endFragment)) {
                return lastEvents;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250L));
            lastEvents = container.readEventLines();
        }
        return lastEvents;
    }

    private static final class HttpResponse {
        private final int statusCode;
        private final String body;

        private HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}