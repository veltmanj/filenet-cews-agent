package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Manual performance harness for estimating the in-process cost of the request
 * capture path.
 *
 * <p>This harness is intentionally opt-in because it measures timing-sensitive
 * behavior and prints operational metrics instead of enforcing strict thresholds.
 * The goal is to compare capture modes before production rollout, not to make the
 * normal unit-test suite flaky.</p>
 *
 * <p>The simulation drives the same core classes used in production:
 * {@link CaptureContext} and {@link AsyncEventWriter}. It reports throughput,
 * latency percentiles, approximate heap delta, output size, and dropped-event
 * counts for three scenarios:</p>
 *
 * <ul>
 * <li>control: no capture, used as a local comparison point</li>
 * <li>headers-only capture</li>
 * <li>body-preview capture</li>
 * </ul>
 *
 * <p>The results are useful for sizing and comparing configurations, but they do
 * not replace a full staging load test on WebSphere with real CEWS traffic.</p>
 */
@Tag("performance")
class PerformanceSimulationTest {
    private static final DateTimeFormatter REPORT_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    /**
     * Runs the configured performance scenarios and prints a compact report.
     *
     * <p>Enable this test explicitly with:</p>
     *
     * <pre>
     * ./mvnw -q -DrunPerformanceTests=true -Dtest=PerformanceSimulationTest test
     * </pre>
     */
    @Test
    @EnabledIfSystemProperty(named = "runPerformanceTests", matches = "true")
    void reportsRelativeCostForConfiguredLoadProfile() throws Exception {
        SimulationSettings settings = SimulationSettings.fromSystemProperties();
        List<ScenarioResult> results = runAllScenarios(settings);
        Path reportDirectory = writeReports(settings, results);

        for (ScenarioResult result : results) {
            System.out.println(result.format());
        }
        System.out.println("[perf] detailed report directory=" + reportDirectory.toAbsolutePath());

        assertEquals(4, results.size());
        for (ScenarioResult result : results) {
            assertEquals(settings.totalRequests(), result.totalRequests);
            assertTrue(result.throughputPerSecond > 0.0D);
        }
        assertNotEquals(0L, controlSink);
    }

    private List<ScenarioResult> runAllScenarios(SimulationSettings settings) throws Exception {
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : Scenario.values()) {
            results.add(runScenario(settings, scenario));
        }
        return results;
    }

    private Path writeReports(SimulationSettings settings, List<ScenarioResult> results) throws Exception {
        Path reportDirectory = settings.reportDirectory();
        Files.createDirectories(reportDirectory);
        String reportTimestamp = REPORT_TIMESTAMP_FORMAT.format(Instant.now());
        String markdownName = "performance-report-" + reportTimestamp + ".md";
        String htmlName = "performance-report-" + reportTimestamp + ".html";
        List<RunSummary> recentRuns = recentRuns(reportDirectory, settings, results, reportTimestamp);
        byte[] markdown = buildMarkdownReport(settings, results, recentRuns).getBytes(StandardCharsets.UTF_8);
        byte[] html = buildHtmlReport(settings, results, recentRuns).getBytes(StandardCharsets.UTF_8);
        Files.write(reportDirectory.resolve(markdownName), markdown);
        Files.write(reportDirectory.resolve(htmlName), html);
        Files.write(reportDirectory.resolve("performance-report-latest.md"), markdown);
        Files.write(reportDirectory.resolve("performance-report-latest.html"), html);
        return reportDirectory;
    }

    private String buildMarkdownReport(SimulationSettings settings, List<ScenarioResult> results, List<RunSummary> recentRuns) {
        ScenarioResult baseline = baseline(results);
        StringBuilder builder = new StringBuilder(4096);
        builder.append("# Performance Report\n\n");
        builder.append("## Load Profile\n\n");
        builder.append("- Users: ").append(settings.users).append('\n');
        builder.append("- Requests per user: ").append(settings.requestsPerUser).append('\n');
        builder.append("- Total requests: ").append(settings.totalRequests()).append('\n');
        builder.append("- Worker threads: ").append(settings.workerThreads).append('\n');
        builder.append("- Body bytes: ").append(settings.bodyBytes).append('\n');
        builder.append("- Queue capacity: ").append(settings.queueCapacity).append("\n\n");

        builder.append("## Scenario Summary\n\n");
        builder.append("| Scenario | Throughput req/s | Avg us | P95 us | P99 us | Heap delta MB | Output KB | Dropped | Throughput vs control | Avg latency vs control |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ScenarioResult result : results) {
            builder.append("| ").append(result.scenario.label)
                .append(" | ").append(formatDouble(result.throughputPerSecond))
                .append(" | ").append(formatDouble(result.averageLatencyMicros))
                .append(" | ").append(result.p95LatencyMicros)
                .append(" | ").append(result.p99LatencyMicros)
                .append(" | ").append(formatDouble(toMegabytes(result.heapDeltaBytes)))
                .append(" | ").append(formatDouble(toKilobytes(result.outputBytes)))
                .append(" | ").append(result.droppedEvents)
                .append(" | ").append(formatSignedPercent(result.throughputDeltaPercent(baseline)))
                .append(" | ").append(formatSignedPercent(result.averageLatencyDeltaPercent(baseline)))
                .append(" |\n");
        }

        builder.append("\n## Throughput\n\n");
        for (ScenarioResult result : results) {
            builder.append("- ").append(result.scenario.label).append(": ")
                .append(asciiBar(result.throughputPerSecond, maxThroughput(results)))
                .append(' ').append(formatDouble(result.throughputPerSecond)).append(" req/s\n");
        }

        builder.append("\n## Average Latency\n\n");
        for (ScenarioResult result : results) {
            builder.append("- ").append(result.scenario.label).append(": ")
                .append(asciiBar(result.averageLatencyMicros, maxAverageLatency(results)))
                .append(' ').append(formatDouble(result.averageLatencyMicros)).append(" us\n");
        }

        builder.append("\n## Estimated End-to-End Overhead\n\n");
        builder.append("These percentages use the incremental average latency above the local control scenario and map it onto example request baselines.\n\n");
        builder.append("| Scenario | Delta vs control us |");
        for (Integer baselineMillis : settings.baselineMillis) {
            builder.append(" Baseline ").append(baselineMillis).append(" ms |");
        }
        builder.append("\n| --- | ---: |");
        for (int index = 0; index < settings.baselineMillis.size(); index++) {
            builder.append(" ---: |");
        }
        builder.append("\n");
        for (ScenarioResult result : results) {
            if (Scenario.CONTROL.equals(result.scenario)) {
                continue;
            }
            builder.append("| ").append(result.scenario.label)
                .append(" | ").append(formatDouble(incrementalLatencyMicros(result, baseline)));
            for (Integer baselineMillis : settings.baselineMillis) {
                builder.append(" | ").append(formatDouble(estimatedOverheadPercent(result, baseline, baselineMillis.intValue()))).append('%');
            }
            builder.append(" |\n");
        }

        builder.append("\n## Extra Time Per 1000 Requests\n\n");
        builder.append("This view converts the incremental average latency into added wall-clock time per 1000 requests and shows what share of total request time that represents at each example baseline.\n\n");
        builder.append("| Scenario | Extra ms / 1000 requests |");
        for (Integer baselineMillis : settings.baselineMillis) {
            builder.append(" Share at ").append(baselineMillis).append(" ms baseline |");
        }
        builder.append("\n| --- | ---: |");
        for (int index = 0; index < settings.baselineMillis.size(); index++) {
            builder.append(" ---: |");
        }
        builder.append("\n");
        for (ScenarioResult result : results) {
            if (Scenario.CONTROL.equals(result.scenario)) {
                continue;
            }
            builder.append("| ").append(result.scenario.label)
                .append(" | ").append(formatDouble(extraMillisPerThousandRequests(result, baseline)));
            for (Integer baselineMillis : settings.baselineMillis) {
                builder.append(" | ").append(formatDouble(shareOfThousandRequestTimePercent(result, baseline, baselineMillis.intValue()))).append('%');
            }
            builder.append(" |\n");
        }

        builder.append("\n## Recent Trend\n\n");
        builder.append("| Run | Queue | Body bytes | Control req/s | Low-overhead avg us | Low-overhead dropped | Headers avg us | Headers dropped | Body avg us | Body dropped |\n");
        builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (RunSummary run : recentRuns) {
            builder.append("| ").append(run.timestamp)
                .append(" | ").append(run.queueCapacity)
                .append(" | ").append(run.bodyBytes)
                .append(" | ").append(formatDouble(run.controlThroughput))
                .append(" | ").append(formatOptionalDouble(run.lowOverheadAverageLatencyMicros))
                .append(" | ").append(formatOptionalLong(run.lowOverheadDroppedEvents))
                .append(" | ").append(formatDouble(run.headersAverageLatencyMicros))
                .append(" | ").append(run.headersDroppedEvents)
                .append(" | ").append(formatDouble(run.bodyAverageLatencyMicros))
                .append(" | ").append(run.bodyDroppedEvents)
                .append(" |\n");
        }

        RunSummary bestRun = bestRun(recentRuns);
        builder.append("\n## Best Observed Configuration\n\n");
        builder.append("- Run: `").append(bestRun.timestamp).append("`\n");
        builder.append("- Queue capacity: `").append(bestRun.queueCapacity).append("`\n");
        builder.append("- Body bytes: `").append(bestRun.bodyBytes).append("`\n");
        builder.append("- Why it stands out: ")
            .append(bestRunSummary(bestRun))
            .append("\n");

        builder.append("\n## Interpretation\n\n");
        builder.append("- `control` is the local baseline with no capture logic enabled.\n");
        builder.append("- `low-overhead` is the lighter end-event-only, metadata-light production candidate when you need the smallest footprint.\n");
        builder.append("- `headers-only` estimates the incremental cost of event creation and asynchronous logging without body preview accumulation.\n");
        builder.append("- `body-preview` estimates the extra cost of bounded request-body accumulation on top of the headers-only path.\n");
        builder.append("- Use the delta columns as a local comparison point before running a staging load test on WebSphere with real CEWS traffic.\n");
        builder.append("\n## Scenario Guidance\n\n");
        for (ScenarioResult result : results) {
            builder.append("### ").append(result.scenario.label).append("\n\n");
            builder.append(scenarioRecommendation(result, baseline)).append("\n\n");
        }

        builder.append("## Recommended Next Run\n\n");
        builder.append(recommendedNextRunMarkdown(settings, results, recentRuns));
        return builder.toString();
    }

    private String buildHtmlReport(SimulationSettings settings, List<ScenarioResult> results, List<RunSummary> recentRuns) {
        ScenarioResult baseline = baseline(results);
        double maxThroughput = maxThroughput(results);
        double maxAverageLatency = maxAverageLatency(results);
        StringBuilder builder = new StringBuilder(8192);
        builder.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            .append("<title>Performance Report</title><style>")
            .append("body{font-family:Georgia,serif;background:#f5f1e8;color:#1f252b;margin:0;padding:32px;}")
            .append("h1,h2{margin:0 0 16px;} .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:16px;margin:24px 0;}")
            .append(".card{background:#fffdf8;border:1px solid #d8cfbf;border-radius:14px;padding:16px;box-shadow:0 8px 24px rgba(31,37,43,0.06);}")
            .append(".metric{font-size:1.8rem;font-weight:700;margin-top:8px;} table{width:100%;border-collapse:collapse;background:#fffdf8;border-radius:14px;overflow:hidden;}")
            .append("th,td{padding:12px 10px;border-bottom:1px solid #ece4d7;text-align:left;} th{background:#efe6d7;} .bar-wrap{background:#ece4d7;border-radius:999px;height:12px;overflow:hidden;} ")
            .append(".bar{height:12px;background:linear-gradient(90deg,#146c78,#3f9d7d);} .bar-latency{background:linear-gradient(90deg,#b86a27,#db9b3b);} .delta-good{color:#1c7c54;} .delta-bad{color:#a33a2b;} code{background:#efe6d7;padding:2px 6px;border-radius:6px;}")
            .append("</style></head><body>");

        builder.append("<h1>Performance Report</h1>");
        builder.append("<p>Manual CEWS capture-path simulation for pre-production sizing and comparison.</p>");
        builder.append("<div class=\"grid\">")
            .append(metricCard("Users", Integer.toString(settings.users)))
            .append(metricCard("Requests / User", Integer.toString(settings.requestsPerUser)))
            .append(metricCard("Total Requests", Integer.toString(settings.totalRequests())))
            .append(metricCard("Worker Threads", Integer.toString(settings.workerThreads)))
            .append(metricCard("Body Bytes", Integer.toString(settings.bodyBytes)))
            .append(metricCard("Queue Capacity", Integer.toString(settings.queueCapacity)))
            .append("</div>");

        builder.append("<h2>Scenario Comparison</h2>");
        builder.append("<table><thead><tr><th>Scenario</th><th>Throughput</th><th>Avg</th><th>P95</th><th>P99</th><th>Heap Delta</th><th>Output</th><th>Dropped</th><th>Throughput Delta</th><th>Latency Delta</th></tr></thead><tbody>");
        for (ScenarioResult result : results) {
            builder.append("<tr><td>").append(escapeHtml(result.scenario.label)).append("</td>")
                .append("<td>").append(formatDouble(result.throughputPerSecond)).append(" req/s</td>")
                .append("<td>").append(formatDouble(result.averageLatencyMicros)).append(" us</td>")
                .append("<td>").append(result.p95LatencyMicros).append(" us</td>")
                .append("<td>").append(result.p99LatencyMicros).append(" us</td>")
                .append("<td>").append(formatDouble(toMegabytes(result.heapDeltaBytes))).append(" MB</td>")
                .append("<td>").append(formatDouble(toKilobytes(result.outputBytes))).append(" KB</td>")
                .append("<td>").append(result.droppedEvents).append("</td>")
                .append("<td class=\"").append(deltaClass(result.throughputDeltaPercent(baseline), true)).append("\">")
                .append(formatSignedPercent(result.throughputDeltaPercent(baseline))).append("</td>")
                .append("<td class=\"").append(deltaClass(result.averageLatencyDeltaPercent(baseline), false)).append("\">")
                .append(formatSignedPercent(result.averageLatencyDeltaPercent(baseline))).append("</td></tr>");
        }
        builder.append("</tbody></table>");

        builder.append("<h2>Visual Comparison</h2>");
        builder.append("<div class=\"grid\">");
        for (ScenarioResult result : results) {
            builder.append("<div class=\"card\"><h3>").append(escapeHtml(result.scenario.label)).append("</h3>")
                .append("<p>Throughput</p><div class=\"bar-wrap\"><div class=\"bar\" style=\"width:")
                .append(formatDouble(percent(result.throughputPerSecond, maxThroughput))).append("%\"></div></div>")
                .append("<div class=\"metric\">" + formatDouble(result.throughputPerSecond) + " req/s</div>")
                .append("<p>Average latency</p><div class=\"bar-wrap\"><div class=\"bar bar-latency\" style=\"width:")
                .append(formatDouble(percent(result.averageLatencyMicros, maxAverageLatency))).append("%\"></div></div>")
                .append("<div class=\"metric\">" + formatDouble(result.averageLatencyMicros) + " us</div>")
                .append("<p><code>p95</code> ").append(result.p95LatencyMicros).append(" us | <code>p99</code> ").append(result.p99LatencyMicros).append(" us</p>")
                .append("</div>");
        }
        builder.append("</div>");

        builder.append("<h2>Estimated End-to-End Overhead</h2><table><thead><tr><th>Scenario</th><th>Delta vs Control</th>");
        for (Integer baselineMillis : settings.baselineMillis) {
            builder.append("<th>Baseline ").append(baselineMillis).append(" ms</th>");
        }
        builder.append("</tr></thead><tbody>");
        for (ScenarioResult result : results) {
            if (Scenario.CONTROL.equals(result.scenario)) {
                continue;
            }
            builder.append("<tr><td>").append(escapeHtml(result.scenario.label)).append("</td>")
                .append("<td>").append(formatDouble(incrementalLatencyMicros(result, baseline))).append(" us</td>");
            for (Integer baselineMillis : settings.baselineMillis) {
                builder.append("<td>").append(formatDouble(estimatedOverheadPercent(result, baseline, baselineMillis.intValue()))).append("%</td>");
            }
            builder.append("</tr>");
        }
        builder.append("</tbody></table>");

        builder.append("<h2>Extra Time Per 1000 Requests</h2><table><thead><tr><th>Scenario</th><th>Extra ms / 1000 requests</th>");
        for (Integer baselineMillis : settings.baselineMillis) {
            builder.append("<th>Share at ").append(baselineMillis).append(" ms baseline</th>");
        }
        builder.append("</tr></thead><tbody>");
        for (ScenarioResult result : results) {
            if (Scenario.CONTROL.equals(result.scenario)) {
                continue;
            }
            builder.append("<tr><td>").append(escapeHtml(result.scenario.label)).append("</td>")
                .append("<td>").append(formatDouble(extraMillisPerThousandRequests(result, baseline))).append(" ms</td>");
            for (Integer baselineMillis : settings.baselineMillis) {
                builder.append("<td>").append(formatDouble(shareOfThousandRequestTimePercent(result, baseline, baselineMillis.intValue()))).append("%</td>");
            }
            builder.append("</tr>");
        }
        builder.append("</tbody></table>");

        builder.append("<h2>Recent Trend</h2><table><thead><tr><th>Run</th><th>Queue</th><th>Body Bytes</th><th>Control</th><th>Low-overhead Avg</th><th>Low-overhead Dropped</th><th>Headers Avg</th><th>Headers Dropped</th><th>Body Avg</th><th>Body Dropped</th></tr></thead><tbody>");
        for (RunSummary run : recentRuns) {
            builder.append("<tr><td>").append(escapeHtml(run.timestamp)).append("</td>")
                .append("<td>").append(run.queueCapacity).append("</td>")
                .append("<td>").append(run.bodyBytes).append("</td>")
                .append("<td>").append(formatDouble(run.controlThroughput)).append(" req/s</td>")
                .append("<td>").append(escapeHtml(formatOptionalDouble(run.lowOverheadAverageLatencyMicros))).append("</td>")
                .append("<td>").append(escapeHtml(formatOptionalLong(run.lowOverheadDroppedEvents))).append("</td>")
                .append("<td>").append(formatDouble(run.headersAverageLatencyMicros)).append(" us</td>")
                .append("<td>").append(run.headersDroppedEvents).append("</td>")
                .append("<td>").append(formatDouble(run.bodyAverageLatencyMicros)).append(" us</td>")
                .append("<td>").append(run.bodyDroppedEvents).append("</td></tr>");
        }
        builder.append("</tbody></table>");

        RunSummary bestRun = bestRun(recentRuns);
        builder.append("<h2>Best Observed Configuration</h2><div class=\"card\"><p><strong>Run:</strong> ")
            .append(escapeHtml(bestRun.timestamp))
            .append("</p><p><strong>Queue capacity:</strong> ")
            .append(bestRun.queueCapacity)
            .append("</p><p><strong>Body bytes:</strong> ")
            .append(bestRun.bodyBytes)
            .append("</p><p><strong>Why it stands out:</strong> ")
            .append(escapeHtml(bestRunSummary(bestRun)))
            .append("</p></div>");

        builder.append("<h2>Interpretation</h2><ul>")
            .append("<li><strong>control</strong> is the local baseline with no capture logic enabled.</li>")
            .append("<li><strong>low-overhead</strong> estimates the lighter end-event-only and metadata-light capture path recommended for stricter production budgets.</li>")
            .append("<li><strong>headers-only</strong> estimates the incremental cost of event creation and asynchronous logging without body preview accumulation.</li>")
            .append("<li><strong>body-preview</strong> estimates the additional cost of bounded request-body accumulation on top of the headers-only path.</li>")
            .append("<li>Use these numbers as a comparison aid before running a real staging load test on WebSphere with CEWS traffic.</li>")
            .append("</ul>");

        builder.append("<h2>Scenario Guidance</h2><div class=\"grid\">");
        for (ScenarioResult result : results) {
            builder.append("<div class=\"card\"><h3>").append(escapeHtml(result.scenario.label)).append("</h3><p>")
                .append(escapeHtml(scenarioRecommendation(result, baseline)))
                .append("</p></div>");
        }
        builder.append("</div>");

        builder.append("<h2>Recommended Next Run</h2>")
            .append(recommendedNextRunHtml(settings, results, recentRuns));

        builder.append("</body></html>");
        return builder.toString();
    }

    private ScenarioResult runScenario(SimulationSettings settings, Scenario scenario) throws Exception {
        Path outputFile = tempDir.resolve(scenario.fileName());
        byte[] requestBody = createBody(settings.bodyBytes);
        CaptureContext.RequestMetadata metadata = createMetadata(requestBody.length);
        long[] latenciesNanos = new long[settings.totalRequests()];
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(settings.totalRequests());
        ExecutorService executor = Executors.newFixedThreadPool(settings.workerThreads);

        AsyncEventWriter writer = scenario.requiresWriter()
            ? new AsyncEventWriter(outputFile.toFile(), settings.queueCapacity)
            : null;

        long heapBefore = usedHeapBytes();
        long startedAt = System.nanoTime();

        for (int index = 0; index < settings.totalRequests(); index++) {
            final int requestIndex = index;
            executor.submit(() -> {
                await(startGate);
                long requestStartedAt = System.nanoTime();
                runSingleRequest(scenario, metadata, requestBody, writer);
                latenciesNanos[requestIndex] = System.nanoTime() - requestStartedAt;
                doneGate.countDown();
            });
        }

        startGate.countDown();
        doneGate.await();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        if (writer != null) {
            writer.close();
        }

        long elapsedNanos = System.nanoTime() - startedAt;
        long heapAfter = usedHeapBytes();
        long outputBytes = Files.exists(outputFile) ? Files.size(outputFile) : 0L;
        long droppedEvents = writer == null ? 0L : writer.droppedEvents();

        return new ScenarioResult(scenario, settings, latenciesNanos, elapsedNanos, heapAfter - heapBefore, outputBytes, droppedEvents);
    }

    private void runSingleRequest(Scenario scenario,
                                  CaptureContext.RequestMetadata metadata,
                                  byte[] requestBody,
                                  AsyncEventWriter writer) {
        if (Scenario.CONTROL.equals(scenario)) {
            consumeWithoutCapture(requestBody);
            return;
        }

        CaptureContext context = new CaptureContext(
            System.nanoTime(),
            System.currentTimeMillis(),
            metadata,
            scenario.maxBodyBytes,
            scenario.captureBody,
            scenario.metadataMode
        );

        if (scenario.emitStartEvent()) {
            writer.enqueue(context.toStartEventJson());
        }
        if (requestBody.length > 0) {
            context.onRead(requestBody[0]);
        }
        if (requestBody.length > 2) {
            context.onRead(requestBody, 1, requestBody.length - 2);
        }
        if (requestBody.length > 1) {
            context.onRead(new byte[] { requestBody[requestBody.length - 1] }, 0, 1);
        }
        writer.enqueue(context.toEndEventJson(null, 200, writer.droppedEvents()));
    }

    private static volatile long controlSink;

    private void consumeWithoutCapture(byte[] requestBody) {
        long checksum = requestBody.length;
        checksum += requestBody.length;
        checksum += "POST".length();
        checksum += "/wsi/FNCEWS40MTOM/".length();
        checksum += "application/soap+xml;charset=UTF-8".length();
        for (byte current : requestBody) {
            checksum += current;
        }
        controlSink = checksum;
    }

    private CaptureContext.RequestMetadata createMetadata(int contentLength) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("SOAPAction", "Create");
        headers.put("Content-Type", "application/soap+xml");
        headers.put("User-Agent", "perf-harness");
        return new CaptureContext.RequestMetadata(
            "POST",
            "/wsi/FNCEWS40MTOM/",
            "operation=Create",
            "10.0.0.10",
            "application/soap+xml;charset=UTF-8",
            contentLength,
            headers
        );
    }

    private byte[] createBody(int bodyBytes) {
        StringBuilder builder = new StringBuilder(bodyBytes);
        while (builder.length() < bodyBytes) {
            builder.append("<soap>payload</soap>");
        }
        return builder.substring(0, bodyBytes).getBytes(StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Performance worker interrupted", interruptedException);
        }
    }

    private static long usedHeapBytes() {
        MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();
        return memoryMxBean.getHeapMemoryUsage().getUsed();
    }

    private static ScenarioResult baseline(List<ScenarioResult> results) {
        return results.get(0);
    }

    private static double maxThroughput(List<ScenarioResult> results) {
        double max = 0.0D;
        for (ScenarioResult result : results) {
            max = Math.max(max, result.throughputPerSecond);
        }
        return max;
    }

    private static double maxAverageLatency(List<ScenarioResult> results) {
        double max = 0.0D;
        for (ScenarioResult result : results) {
            max = Math.max(max, result.averageLatencyMicros);
        }
        return max;
    }

    private static String asciiBar(double value, double max) {
        int width = (int) Math.round(percent(value, max) / 5.0D);
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < 20; index++) {
            builder.append(index < width ? '#' : ' ');
        }
        builder.append(']');
        return builder.toString();
    }

    private static double percent(double value, double max) {
        if (max <= 0.0D) {
            return 0.0D;
        }
        return (value / max) * 100.0D;
    }

    private static double toMegabytes(long bytes) {
        return bytes / (1024.0D * 1024.0D);
    }

    private static double toKilobytes(long bytes) {
        return bytes / 1024.0D;
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatSignedPercent(double value) {
        return String.format(Locale.ROOT, "%+.2f%%", value);
    }
    
    private static String formatOptionalDouble(Double value) {
        return value == null ? "n/a" : formatDouble(value.doubleValue());
    }
    
    private static String formatOptionalLong(Long value) {
        return value == null ? "n/a" : Long.toString(value.longValue());
    }
    
    private static double incrementalLatencyMicros(ScenarioResult result, ScenarioResult baseline) {
        return Math.max(0.0D, result.averageLatencyMicros - baseline.averageLatencyMicros);
    }
    
    private static double estimatedOverheadPercent(ScenarioResult result, ScenarioResult baseline, int baselineMillis) {
        if (baselineMillis <= 0) {
            return 0.0D;
        }
        return (incrementalLatencyMicros(result, baseline) / (baselineMillis * 1_000.0D)) * 100.0D;
    }

    private static double extraMillisPerThousandRequests(ScenarioResult result, ScenarioResult baseline) {
        return incrementalLatencyMicros(result, baseline);
    }

    private static double shareOfThousandRequestTimePercent(ScenarioResult result, ScenarioResult baseline, int baselineMillis) {
        return estimatedOverheadPercent(result, baseline, baselineMillis);
    }

    private static String recommendedNextRunMarkdown(SimulationSettings settings,
                                                     List<ScenarioResult> results,
                                                     List<RunSummary> recentRuns) {
        Recommendation recommendation = buildRecommendation(settings, results, recentRuns);
        StringBuilder builder = new StringBuilder(512);
        builder.append("- Suggested queue capacity: `").append(recommendation.queueCapacity).append("`\n");
        builder.append("- Suggested body bytes: `").append(recommendation.bodyBytes).append("`\n");
        builder.append("- Rationale: ").append(recommendation.rationale).append("\n\n");
        builder.append("```bash\n");
        builder.append("./mvnw -q -DrunPerformanceTests=true -Dtest=PerformanceSimulationTest test \\\n");
        builder.append("  -DperfQueueCapacity=").append(recommendation.queueCapacity).append(" \\\n");
        builder.append("  -DperfBodyBytes=").append(recommendation.bodyBytes).append("\n");
        builder.append("```\n");
        return builder.toString();
    }

    private static String recommendedNextRunHtml(SimulationSettings settings,
                                                 List<ScenarioResult> results,
                                                 List<RunSummary> recentRuns) {
        Recommendation recommendation = buildRecommendation(settings, results, recentRuns);
        return new StringBuilder(768)
            .append("<div class=\"card\"><p><strong>Suggested queue capacity:</strong> ")
            .append(recommendation.queueCapacity)
            .append("</p><p><strong>Suggested body bytes:</strong> ")
            .append(recommendation.bodyBytes)
            .append("</p><p><strong>Rationale:</strong> ")
            .append(escapeHtml(recommendation.rationale))
            .append("</p><p><code>./mvnw -q -DrunPerformanceTests=true -Dtest=PerformanceSimulationTest test -DperfQueueCapacity=")
            .append(recommendation.queueCapacity)
            .append(" -DperfBodyBytes=")
            .append(recommendation.bodyBytes)
            .append("</code></p></div>")
            .toString();
    }

    private List<RunSummary> recentRuns(Path reportDirectory,
                                        SimulationSettings settings,
                                        List<ScenarioResult> results,
                                        String currentTimestamp) throws Exception {
        List<RunSummary> runs = new ArrayList<>();
        for (Path path : Files.newDirectoryStream(reportDirectory, "performance-report-*.md")) {
            String fileName = path.getFileName().toString();
            if (fileName.contains("latest")) {
                continue;
            }
            RunSummary summary = parseRunSummary(path);
            if (summary != null) {
                runs.add(summary);
            }
        }
        runs.add(currentRunSummary(settings, results, currentTimestamp));
        Collections.sort(runs, Comparator.comparing((RunSummary run) -> run.timestamp).reversed());
        if (runs.size() > 5) {
            return new ArrayList<>(runs.subList(0, 5));
        }
        return runs;
    }

    private RunSummary currentRunSummary(SimulationSettings settings, List<ScenarioResult> results, String timestamp) {
        return new RunSummary(
            timestamp,
            settings.queueCapacity,
            settings.bodyBytes,
            scenarioResult(results, Scenario.CONTROL).throughputPerSecond,
            Double.valueOf(scenarioResult(results, Scenario.LOW_OVERHEAD).averageLatencyMicros),
            Long.valueOf(scenarioResult(results, Scenario.LOW_OVERHEAD).droppedEvents),
            scenarioResult(results, Scenario.HEADERS_ONLY).averageLatencyMicros,
            scenarioResult(results, Scenario.HEADERS_ONLY).droppedEvents,
            scenarioResult(results, Scenario.BODY_PREVIEW).averageLatencyMicros,
            scenarioResult(results, Scenario.BODY_PREVIEW).droppedEvents
        );
    }

    private RunSummary parseRunSummary(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String timestamp = parseTimestamp(path.getFileName().toString());
        int queueCapacity = 0;
        int bodyBytes = 0;
        Double controlThroughput = null;
        Double lowOverheadAverage = null;
        Long lowOverheadDropped = null;
        Double headersAverage = null;
        Long headersDropped = null;
        Double bodyAverage = null;
        Long bodyDropped = null;
        boolean inScenarioSummary = false;

        for (String line : lines) {
            if (line.startsWith("## Scenario Summary")) {
                inScenarioSummary = true;
                continue;
            }
            if (line.startsWith("## ") && !line.startsWith("## Scenario Summary")) {
                inScenarioSummary = false;
            }
            if (line.startsWith("- Body bytes: ")) {
                bodyBytes = Integer.parseInt(normalizeNumericMarkdownValue(line.substring("- Body bytes: ".length())));
            } else if (line.startsWith("- Queue capacity: ")) {
                queueCapacity = Integer.parseInt(normalizeNumericMarkdownValue(line.substring("- Queue capacity: ".length())));
            } else if (inScenarioSummary && line.startsWith("| control |")) {
                String[] columns = splitMarkdownRow(line);
                controlThroughput = Double.valueOf(columns[1]);
            } else if (inScenarioSummary && line.startsWith("| low-overhead |")) {
                String[] columns = splitMarkdownRow(line);
                lowOverheadAverage = Double.valueOf(columns[2]);
                lowOverheadDropped = Long.valueOf(columns[7]);
            } else if (inScenarioSummary && line.startsWith("| headers-only |")) {
                String[] columns = splitMarkdownRow(line);
                headersAverage = Double.valueOf(columns[2]);
                headersDropped = Long.valueOf(columns[7]);
            } else if (inScenarioSummary && line.startsWith("| body-preview |")) {
                String[] columns = splitMarkdownRow(line);
                bodyAverage = Double.valueOf(columns[2]);
                bodyDropped = Long.valueOf(columns[7]);
            }
        }

        if (controlThroughput == null || headersAverage == null || headersDropped == null || bodyAverage == null || bodyDropped == null) {
            return null;
        }

        return new RunSummary(
            timestamp,
            queueCapacity,
            bodyBytes,
            controlThroughput.doubleValue(),
            lowOverheadAverage,
            lowOverheadDropped,
            headersAverage.doubleValue(),
            headersDropped.longValue(),
            bodyAverage.doubleValue(),
            bodyDropped.longValue()
        );
    }

    private String[] splitMarkdownRow(String line) {
        String trimmed = line.substring(1, line.length() - 1);
        String[] raw = trimmed.split("\\|");
        String[] columns = new String[raw.length];
        for (int index = 0; index < raw.length; index++) {
            columns[index] = raw[index].trim();
        }
        return columns;
    }

    private String parseTimestamp(String fileName) {
        return fileName
            .replace("performance-report-", "")
            .replace(".md", "");
    }

    private String normalizeNumericMarkdownValue(String value) {
        return value.trim().replace("`", "");
    }

    private static Recommendation buildRecommendation(SimulationSettings settings,
                                                      List<ScenarioResult> results,
                                                      List<RunSummary> recentRuns) {
        ScenarioResult headersOnly = scenarioResult(results, Scenario.HEADERS_ONLY);
        ScenarioResult bodyPreview = scenarioResult(results, Scenario.BODY_PREVIEW);
        double maxDroppedRatio = Math.max(droppedRatio(headersOnly), droppedRatio(bodyPreview));

        int queueCapacity = settings.queueCapacity;
        if (maxDroppedRatio >= 0.50D) {
            queueCapacity *= 4;
        } else if (maxDroppedRatio > 0.0D) {
            queueCapacity *= 2;
        }

        int bodyBytes = settings.bodyBytes;
        double recentBodyImprovement = recentBodyLatencyImprovement(recentRuns, settings);
        boolean shouldReduceBodyBytes = bodyPreview.droppedEvents > 0L
            || (bodyPreview.averageLatencyDeltaPercent(baseline(results)) > 100.0D && recentBodyImprovement >= 10.0D);
        if (shouldReduceBodyBytes) {
            bodyBytes = Math.max(512, settings.bodyBytes / 2);
        }

        String rationale;
        if (maxDroppedRatio > 0.0D) {
            rationale = "The current run shows queue pressure in the writer path, so the next run should widen the queue. The body-preview scenario also merits a smaller simulated payload to separate queue saturation from preview overhead.";
        } else if (bodyBytes < settings.bodyBytes) {
            rationale = "Writer drops are gone, and the recent trend shows body-preview latency is still improving materially as payload size falls. Keep the wider queue and reduce the simulated body size once more to isolate preview overhead more cleanly.";
        } else {
            rationale = "No writer drops were observed, and the recent trend no longer shows a strong latency win from shrinking the payload further. Keep the current queue and body size for a repeatable confirmation run or move to staging validation.";
        }

        return new Recommendation(queueCapacity, bodyBytes, rationale);
    }

    private static ScenarioResult scenarioResult(List<ScenarioResult> results, Scenario scenario) {
        for (ScenarioResult result : results) {
            if (scenario.equals(result.scenario)) {
                return result;
            }
        }
        throw new IllegalStateException("Missing scenario result: " + scenario.label);
    }

    private static double droppedRatio(ScenarioResult result) {
        if (result.totalRequests == 0) {
            return 0.0D;
        }
        return result.droppedEvents / (double) result.totalRequests;
    }

    private static double recentBodyLatencyImprovement(List<RunSummary> recentRuns, SimulationSettings settings) {
        RunSummary currentRun = null;
        RunSummary previousComparableRun = null;
        for (RunSummary run : recentRuns) {
            if (run.queueCapacity != settings.queueCapacity) {
                continue;
            }
            if (run.bodyBytes == settings.bodyBytes && currentRun == null) {
                currentRun = run;
                continue;
            }
            if (currentRun != null && run.bodyBytes > settings.bodyBytes) {
                previousComparableRun = run;
                break;
            }
        }

        if (currentRun == null || previousComparableRun == null || previousComparableRun.bodyAverageLatencyMicros <= 0.0D) {
            return 100.0D;
        }
        return ((previousComparableRun.bodyAverageLatencyMicros - currentRun.bodyAverageLatencyMicros)
            / previousComparableRun.bodyAverageLatencyMicros) * 100.0D;
    }

    private static RunSummary bestRun(List<RunSummary> recentRuns) {
        RunSummary best = null;
        for (RunSummary run : recentRuns) {
            if (run.headersDroppedEvents > 0L || run.bodyDroppedEvents > 0L) {
                continue;
            }
            if (run.lowOverheadDroppedEvents != null && run.lowOverheadDroppedEvents.longValue() > 0L) {
                continue;
            }
            if (best == null || lowOverheadCandidateLatency(run) < lowOverheadCandidateLatency(best)) {
                best = run;
            }
        }
        return best != null ? best : recentRuns.get(0);
    }

    private static String bestRunSummary(RunSummary bestRun) {
        return "zero dropped events, low-overhead average latency "
            + formatDouble(lowOverheadCandidateLatency(bestRun))
            + " us, body-preview average latency "
            + formatDouble(bestRun.bodyAverageLatencyMicros)
            + " us, headers-only average latency "
            + formatDouble(bestRun.headersAverageLatencyMicros)
            + " us, and control throughput "
            + formatDouble(bestRun.controlThroughput)
            + " req/s.";
    }

    private static double lowOverheadCandidateLatency(RunSummary run) {
        return run.lowOverheadAverageLatencyMicros == null ? run.bodyAverageLatencyMicros : run.lowOverheadAverageLatencyMicros.doubleValue();
    }

    private static String scenarioRecommendation(ScenarioResult result, ScenarioResult baseline) {
        if (Scenario.CONTROL.equals(result.scenario)) {
            return "Use this as the local baseline only. If control shifts materially between runs, treat the environment as noisy before comparing capture scenarios.";
        }

        double throughputDelta = result.throughputDeltaPercent(baseline);
        double latencyDelta = result.averageLatencyDeltaPercent(baseline);
        boolean hasDroppedEvents = result.droppedEvents > 0L;

        if (Scenario.HEADERS_ONLY.equals(result.scenario)) {
            if (hasDroppedEvents) {
                return "Headers-only capture is dropping events in this run. Increase queue capacity, reduce event volume, or shorten the collection window before using it for RCA.";
            }
            if (throughputDelta >= -10.0D && latencyDelta <= 20.0D) {
                return "Headers-only capture stays close to the control baseline here. This is the safest first production profile for a short RCA window.";
            }
            return "Headers-only capture shows a visible overhead relative to control. Keep it as the initial rollout mode, but validate it under a staging load before enabling broad collection.";
        }

        if (Scenario.LOW_OVERHEAD.equals(result.scenario)) {
            if (hasDroppedEvents) {
                return "Low-overhead capture is still dropping events in this run. Increase queue capacity before treating it as the production-safe candidate mode.";
            }
            if (estimatedOverheadPercent(result, baseline, 10) <= 10.0D) {
                return "Low-overhead capture stays within a single-digit to low-double-digit overhead budget for a 10 ms request baseline. This is the best production candidate to validate in staging.";
            }
            return "Low-overhead capture is materially cheaper than the fuller scenarios, but it still needs staging validation against real CEWS request times before production rollout.";
        }

        if (hasDroppedEvents) {
            return "Body-preview capture is dropping events in this run. Reduce max body bytes or tighten URI filters before using body previews in production.";
        }
        if (throughputDelta >= -15.0D && latencyDelta <= 30.0D) {
            return "Body-preview capture remains within a moderate local overhead range. Restrict it to the failing CEWS endpoint and keep the capture window short.";
        }
        return "Body-preview capture adds a clear overhead relative to control. Reserve it for targeted troubleshooting only, with narrow filters and a small body-preview limit.";
    }

    private static String deltaClass(double value, boolean higherIsBetter) {
        boolean good = higherIsBetter ? value >= 0.0D : value <= 0.0D;
        return good ? "delta-good" : "delta-bad";
    }

    private static String metricCard(String label, String value) {
        return "<div class=\"card\"><div>" + escapeHtml(label) + "</div><div class=\"metric\">" + escapeHtml(value) + "</div></div>";
    }

    private static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    /**
     * Supported measurement scenarios.
     */
    private enum Scenario {
        CONTROL("control", false, false, 0, RequestCaptureConfig.EventMode.END_ONLY, RequestCaptureConfig.MetadataMode.FULL),
        LOW_OVERHEAD("low-overhead", true, false, 0, RequestCaptureConfig.EventMode.END_ONLY, RequestCaptureConfig.MetadataMode.LIGHT),
        HEADERS_ONLY("headers-only", true, false, 0, RequestCaptureConfig.EventMode.START_AND_END, RequestCaptureConfig.MetadataMode.FULL),
        BODY_PREVIEW("body-preview", true, true, 2048, RequestCaptureConfig.EventMode.START_AND_END, RequestCaptureConfig.MetadataMode.FULL);

        private final String label;
        private final boolean requiresWriter;
        private final boolean captureBody;
        private final int maxBodyBytes;
        private final RequestCaptureConfig.EventMode eventMode;
        private final RequestCaptureConfig.MetadataMode metadataMode;

        Scenario(String label,
                 boolean requiresWriter,
                 boolean captureBody,
                 int maxBodyBytes,
                 RequestCaptureConfig.EventMode eventMode,
                 RequestCaptureConfig.MetadataMode metadataMode) {
            this.label = label;
            this.requiresWriter = requiresWriter;
            this.captureBody = captureBody;
            this.maxBodyBytes = maxBodyBytes;
            this.eventMode = eventMode;
            this.metadataMode = metadataMode;
        }

        private boolean requiresWriter() {
            return requiresWriter;
        }

        private boolean emitStartEvent() {
            return eventMode.emitsStartEvent();
        }

        private String fileName() {
            return label.replace(' ', '-').replace('/', '-') + ".ndjson";
        }
    }

    /**
     * Configurable load profile for the performance harness.
     */
    private static final class SimulationSettings {
        private static final String DEFAULT_REPORT_DIRECTORY = "target/performance-reports";

        private final int users;
        private final int requestsPerUser;
        private final int workerThreads;
        private final int bodyBytes;
        private final int queueCapacity;
        private final List<Integer> baselineMillis;
        private final Path reportDirectory;

        private SimulationSettings(int users,
                                   int requestsPerUser,
                                   int workerThreads,
                                   int bodyBytes,
                                   int queueCapacity,
                                   List<Integer> baselineMillis,
                                   Path reportDirectory) {
            this.users = users;
            this.requestsPerUser = requestsPerUser;
            this.workerThreads = workerThreads;
            this.bodyBytes = bodyBytes;
            this.queueCapacity = queueCapacity;
            this.baselineMillis = baselineMillis;
            this.reportDirectory = reportDirectory;
        }

        private static SimulationSettings fromSystemProperties() {
            int users = intProperty("perfUsers", 800);
            int requestsPerUser = intProperty("perfRequestsPerUser", 5);
            int defaultWorkers = Math.min(users, Math.max(16, Runtime.getRuntime().availableProcessors() * 4));
            int workerThreads = intProperty("perfWorkerThreads", defaultWorkers);
            int bodyBytes = intProperty("perfBodyBytes", 4096);
            int queueCapacity = intProperty("perfQueueCapacity", 4096);
            List<Integer> baselineMillis = intListProperty("perfBaselineMillis", "5|10|50");
            Path reportDirectory = Paths.get(System.getProperty("perfReportDir", DEFAULT_REPORT_DIRECTORY));
            return new SimulationSettings(users, requestsPerUser, workerThreads, bodyBytes, queueCapacity, baselineMillis, reportDirectory);
        }

        private int totalRequests() {
            return users * requestsPerUser;
        }

        private Path reportDirectory() {
            return reportDirectory;
        }

        private static int intProperty(String name, int defaultValue) {
            String value = System.getProperty(name);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(value.trim());
        }

        private static List<Integer> intListProperty(String name, String defaultValue) {
            String raw = System.getProperty(name, defaultValue);
            List<Integer> values = new ArrayList<>();
            for (String part : raw.split("\\|")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    values.add(Integer.valueOf(Integer.parseInt(trimmed)));
                }
            }
            return values;
        }
    }

    /**
     * Report for one measured scenario.
     */
    private static final class ScenarioResult {
        private final Scenario scenario;
        private final int totalRequests;
        private final double throughputPerSecond;
        private final double averageLatencyMicros;
        private final long p95LatencyMicros;
        private final long p99LatencyMicros;
        private final long heapDeltaBytes;
        private final long outputBytes;
        private final long droppedEvents;

        private ScenarioResult(Scenario scenario,
                               SimulationSettings settings,
                               long[] latenciesNanos,
                               long elapsedNanos,
                               long heapDeltaBytes,
                               long outputBytes,
                               long droppedEvents) {
            this.scenario = scenario;
            this.totalRequests = settings.totalRequests();
            this.throughputPerSecond = totalRequests * 1_000_000_000.0D / elapsedNanos;
            this.averageLatencyMicros = averageMicros(latenciesNanos);
            this.p95LatencyMicros = percentileMicros(latenciesNanos, 95);
            this.p99LatencyMicros = percentileMicros(latenciesNanos, 99);
            this.heapDeltaBytes = heapDeltaBytes;
            this.outputBytes = outputBytes;
            this.droppedEvents = droppedEvents;
        }

        private String format() {
            return String.format(
                Locale.ROOT,
                "[perf] scenario=%s requests=%d throughput=%.2f req/s avg=%.2f us p95=%d us p99=%d us heapDelta=%d bytes output=%d bytes dropped=%d",
                scenario.label,
                totalRequests,
                throughputPerSecond,
                averageLatencyMicros,
                p95LatencyMicros,
                p99LatencyMicros,
                heapDeltaBytes,
                outputBytes,
                droppedEvents
            );
        }

        private double throughputDeltaPercent(ScenarioResult baseline) {
            return deltaPercent(throughputPerSecond, baseline.throughputPerSecond);
        }

        private double averageLatencyDeltaPercent(ScenarioResult baseline) {
            return deltaPercent(averageLatencyMicros, baseline.averageLatencyMicros);
        }

        private static double deltaPercent(double value, double baselineValue) {
            if (baselineValue == 0.0D) {
                return 0.0D;
            }
            return ((value - baselineValue) / baselineValue) * 100.0D;
        }

        private static double averageMicros(long[] latenciesNanos) {
            long total = 0L;
            for (long latency : latenciesNanos) {
                total += latency;
            }
            return (total / (double) latenciesNanos.length) / 1_000.0D;
        }

        private static long percentileMicros(long[] latenciesNanos, int percentile) {
            List<Long> sorted = new ArrayList<>(latenciesNanos.length);
            for (long latency : latenciesNanos) {
                sorted.add(latency);
            }
            Collections.sort(sorted);
            int index = (int) Math.ceil((percentile / 100.0D) * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return TimeUnit.NANOSECONDS.toMicros(sorted.get(index));
        }
    }

    private static final class Recommendation {
        private final int queueCapacity;
        private final int bodyBytes;
        private final String rationale;

        private Recommendation(int queueCapacity, int bodyBytes, String rationale) {
            this.queueCapacity = queueCapacity;
            this.bodyBytes = bodyBytes;
            this.rationale = rationale;
        }
    }

    private static final class RunSummary {
        private final String timestamp;
        private final int queueCapacity;
        private final int bodyBytes;
        private final double controlThroughput;
        private final Double lowOverheadAverageLatencyMicros;
        private final Long lowOverheadDroppedEvents;
        private final double headersAverageLatencyMicros;
        private final long headersDroppedEvents;
        private final double bodyAverageLatencyMicros;
        private final long bodyDroppedEvents;

        private RunSummary(String timestamp,
                           int queueCapacity,
                           int bodyBytes,
                           double controlThroughput,
                           Double lowOverheadAverageLatencyMicros,
                           Long lowOverheadDroppedEvents,
                           double headersAverageLatencyMicros,
                           long headersDroppedEvents,
                           double bodyAverageLatencyMicros,
                           long bodyDroppedEvents) {
            this.timestamp = timestamp;
            this.queueCapacity = queueCapacity;
            this.bodyBytes = bodyBytes;
            this.controlThroughput = controlThroughput;
            this.lowOverheadAverageLatencyMicros = lowOverheadAverageLatencyMicros;
            this.lowOverheadDroppedEvents = lowOverheadDroppedEvents;
            this.headersAverageLatencyMicros = headersAverageLatencyMicros;
            this.headersDroppedEvents = headersDroppedEvents;
            this.bodyAverageLatencyMicros = bodyAverageLatencyMicros;
            this.bodyDroppedEvents = bodyDroppedEvents;
        }
    }
}