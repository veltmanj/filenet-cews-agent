package nl.nextend.filenet.cews.agent;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable configuration for a request capture session.
 *
 * <p>The configuration is built from agent arguments once during agent startup.
 * A small profile system is supported so operators can enable a CEWS-specific
 * baseline and then override selected fields with explicit arguments.</p>
 */
final class RequestCaptureConfig {
    private static final String DEFAULT_OUTPUT = "request-capture.ndjson";
    private static final String DEFAULT_INCLUDE_URI = ".*";
    private static final int DEFAULT_MAX_BODY_BYTES = 4096;
    private static final int DEFAULT_QUEUE_CAPACITY = 4096;
    private static final int DEFAULT_SAMPLE_RATE = 1;
    private static final int FILENET_CEWS_MAX_BODY_BYTES = 2048;
    private static final String ARGUMENT_SEPARATOR = ",";

    static final String DEFAULT_HEADERS = "Host|SOAPAction|Content-Type|Content-Length|User-Agent|X-Request-ID|traceparent";
    static final String DEFAULT_REDACT_HEADERS = "Authorization|Cookie|Set-Cookie";
    static final String DEFAULT_BODY_TYPES = "text/*|application/xml|application/soap+xml|text/xml|application/json|*/xml|*+xml";
    static final String FILENET_CEWS_PROFILE = "filenet-cews";
    static final String FILENET_CEWS_LOW_OVERHEAD_PROFILE = "filenet-cews-low-overhead";
    static final String FILENET_CEWS_URI = ".*/(?:wsi/)?FNCEWS(?:40(?:MTOM|DIME))?.*";
    static final String FILENET_CEWS_HEADERS = "Host|SOAPAction|Content-Type|Content-Length|User-Agent|X-Request-ID|traceparent|MIME-Version|Content-ID";
    static final String FILENET_CEWS_REDACT_HEADERS = "Authorization|Cookie|Set-Cookie|Proxy-Authorization";

    private final File outputFile;
    private final Pattern includeUriPattern;
    private final boolean captureBody;
    private final int maxBodyBytes;
    private final int queueCapacity;
    private final int sampleRate;
    private final boolean diagnosticTransforms;
    private final EventMode eventMode;
    private final MetadataMode metadataMode;
    private final Set<String> includeHeaders;
    private final Set<String> redactHeaders;
    private final Set<String> bodyContentTypes;

    /**
     * Mutable parser state used while walking the raw agent argument list.
     */
    private static final class Builder {
        private String output = DEFAULT_OUTPUT;
        private String includeUri = DEFAULT_INCLUDE_URI;
        private boolean captureBody;
        private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private int sampleRate = DEFAULT_SAMPLE_RATE;
        private boolean diagnosticTransforms = true;
        private EventMode eventMode = EventMode.START_AND_END;
        private MetadataMode metadataMode = MetadataMode.FULL;
        private String includeHeaders = DEFAULT_HEADERS;
        private String redactHeaders = DEFAULT_REDACT_HEADERS;
        private String bodyContentTypes = DEFAULT_BODY_TYPES;
        private String profile;

        private RequestCaptureConfig build() {
            return new RequestCaptureConfig(this);
        }

        private Set<String> parseSet(String value) {
            if (value == null || value.trim().isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> values = new LinkedHashSet<>();
            Arrays.stream(value.split("\\|"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(RequestCaptureConfig::normalize)
                .forEach(values::add);
            return values;
        }
    }

    private RequestCaptureConfig(Builder builder) {
        this.outputFile = new File(builder.output);
        this.includeUriPattern = Pattern.compile(builder.includeUri);
        this.captureBody = builder.captureBody;
        this.maxBodyBytes = builder.maxBodyBytes;
        this.queueCapacity = builder.queueCapacity;
        this.sampleRate = builder.sampleRate;
        this.diagnosticTransforms = builder.diagnosticTransforms;
        this.eventMode = builder.eventMode;
        this.metadataMode = builder.metadataMode;
        this.includeHeaders = builder.parseSet(builder.includeHeaders);
        this.redactHeaders = builder.parseSet(builder.redactHeaders);
        this.bodyContentTypes = builder.parseSet(builder.bodyContentTypes);
    }

    /**
     * Parses the raw {@code -javaagent} argument string into a concrete immutable
     * configuration object.
     *
     * @param agentArgs raw comma-separated agent argument list, or {@code null}
     * @return resolved request capture configuration
     */
    static RequestCaptureConfig fromAgentArgs(String agentArgs) {
        Builder builder = new Builder();
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return builder.build();
        }

        String[] arguments = splitArguments(agentArgs);
        applyProfileSelection(arguments, builder);
        applyProfileDefaults(builder);
        applyExplicitOverrides(arguments, builder);
        return builder.build();
    }

    /**
     * Returns the destination file used for newline-delimited event output.
     */
    File outputFile() {
        return outputFile;
    }

    /**
     * Tests whether a request URI matches the configured inclusion filter.
     */
    boolean matchesUri(String uri) {
        return includeUriPattern.matcher(uri == null ? "" : uri).matches();
    }

    /**
     * Indicates whether body preview capture is globally enabled.
     */
    boolean captureBody() {
        return captureBody;
    }

    /**
     * Returns the maximum number of request bytes retained in the body preview.
     */
    int maxBodyBytes() {
        return maxBodyBytes;
    }

    /**
     * Returns the writer queue capacity requested by the configuration.
     */
    int queueCapacity() {
        return queueCapacity;
    }

    /**
     * Returns the configured sampling rate, where {@code 1} means capture every
     * matching request.
     */
    int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns {@code true} if instrumentation-time class transformation events
     * should be written to the agent output for diagnostics.
     */
    boolean diagnosticTransforms() {
        return diagnosticTransforms;
    }

    /**
     * Returns {@code true} if a request-start event should be emitted before the
     * request completes.
     */
    boolean emitStartEvent() {
        return eventMode.emitsStartEvent();
    }

    /**
     * Returns {@code true} if the lighter metadata shape should be used.
     */
    boolean metadataLight() {
        return MetadataMode.LIGHT.equals(metadataMode);
    }

    /**
     * Determines whether a header should be included in the captured metadata.
     */
    boolean shouldIncludeHeader(String headerName) {
        return includeHeaders.isEmpty() || includeHeaders.contains(normalize(headerName));
    }

    /**
     * Determines whether a captured header value should be replaced with the
     * standard redaction marker.
     */
    boolean shouldRedactHeader(String headerName) {
        return redactHeaders.contains(normalize(headerName));
    }

    /**
     * Returns {@code true} if the provided content type is allowed for body
     * preview capture under the current configuration.
     */
    boolean shouldCaptureBodyContentType(String contentType) {
        if (!captureBody || contentType == null || contentType.trim().isEmpty()) {
            return false;
        }
        String normalized = normalizeContentType(contentType);
        for (String candidate : bodyContentTypes) {
            if (matchesContentTypePattern(normalized, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesContentTypePattern(String normalized, String candidate) {
        if (candidate.endsWith("/*")) {
            return normalized.startsWith(candidate.substring(0, candidate.length() - 1));
        }
        if (candidate.startsWith("*/") || candidate.startsWith("*+")) {
            return normalized.endsWith(candidate.substring(1));
        }
        return normalized.equals(candidate);
    }

    private static void applyProfileSelection(String[] arguments, Builder builder) {
        for (String argument : arguments) {
            KeyValue keyValue = parseKeyValue(argument);
            if (keyValue != null && "profile".equalsIgnoreCase(keyValue.key)) {
                builder.profile = normalize(keyValue.value);
            }
        }
    }

    private static void applyProfileDefaults(Builder builder) {
        if (FILENET_CEWS_PROFILE.equals(builder.profile)) {
            builder.includeUri = FILENET_CEWS_URI;
            builder.maxBodyBytes = FILENET_CEWS_MAX_BODY_BYTES;
            builder.includeHeaders = FILENET_CEWS_HEADERS;
            builder.redactHeaders = FILENET_CEWS_REDACT_HEADERS;
            return;
        }
        if (FILENET_CEWS_LOW_OVERHEAD_PROFILE.equals(builder.profile)) {
            builder.includeUri = FILENET_CEWS_URI;
            builder.maxBodyBytes = 512;
            builder.includeHeaders = FILENET_CEWS_HEADERS;
            builder.redactHeaders = FILENET_CEWS_REDACT_HEADERS;
            builder.eventMode = EventMode.END_ONLY;
            builder.metadataMode = MetadataMode.LIGHT;
        }
    }

    private static void applyExplicitOverrides(String[] arguments, Builder builder) {
        for (String argument : arguments) {
            KeyValue keyValue = parseKeyValue(argument);
            if (keyValue == null || "profile".equalsIgnoreCase(keyValue.key)) {
                continue;
            }
            applyOverride(keyValue, builder);
        }
    }

    private static void applyOverride(KeyValue keyValue, Builder builder) {
        if ("output".equalsIgnoreCase(keyValue.key)) {
            builder.output = keyValue.value;
        } else if ("includeUri".equalsIgnoreCase(keyValue.key)) {
            builder.includeUri = keyValue.value;
        } else if ("captureBody".equalsIgnoreCase(keyValue.key)) {
            builder.captureBody = Boolean.parseBoolean(keyValue.value);
        } else if ("maxBodyBytes".equalsIgnoreCase(keyValue.key)) {
            builder.maxBodyBytes = parsePositiveInt(keyValue.value, builder.maxBodyBytes);
        } else if ("queueCapacity".equalsIgnoreCase(keyValue.key)) {
            builder.queueCapacity = parsePositiveInt(keyValue.value, builder.queueCapacity);
        } else if ("sampleRate".equalsIgnoreCase(keyValue.key)) {
            builder.sampleRate = Math.max(1, parsePositiveInt(keyValue.value, builder.sampleRate));
        } else if ("diagnosticTransforms".equalsIgnoreCase(keyValue.key)) {
            builder.diagnosticTransforms = Boolean.parseBoolean(keyValue.value);
        } else if ("eventMode".equalsIgnoreCase(keyValue.key)) {
            builder.eventMode = EventMode.parse(keyValue.value, builder.eventMode);
        } else if ("metadataMode".equalsIgnoreCase(keyValue.key)) {
            builder.metadataMode = MetadataMode.parse(keyValue.value, builder.metadataMode);
        } else if ("includeHeaders".equalsIgnoreCase(keyValue.key)) {
            builder.includeHeaders = keyValue.value;
        } else if ("redactHeaders".equalsIgnoreCase(keyValue.key)) {
            builder.redactHeaders = keyValue.value;
        } else if ("bodyContentTypes".equalsIgnoreCase(keyValue.key)) {
            builder.bodyContentTypes = keyValue.value;
        }
    }

    private static String[] splitArguments(String agentArgs) {
        return agentArgs.split(ARGUMENT_SEPARATOR);
    }

    private static KeyValue parseKeyValue(String rawArgument) {
        String argument = rawArgument.trim();
        if (argument.isEmpty()) {
            return null;
        }
        int separator = argument.indexOf('=');
        if (separator < 0) {
            return null;
        }
        return new KeyValue(
            argument.substring(0, separator).trim(),
            argument.substring(separator + 1).trim()
        );
    }

    private static String normalizeContentType(String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        return semicolon >= 0 ? normalized.substring(0, semicolon).trim() : normalized.trim();
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Capture event emission policy.
     */
    enum EventMode {
        START_AND_END,
        END_ONLY;

        boolean emitsStartEvent() {
            return START_AND_END.equals(this);
        }

        private static EventMode parse(String value, EventMode fallback) {
            String normalized = normalize(value);
            if ("end-only".equals(normalized) || "end_only".equals(normalized) || "endonly".equals(normalized)) {
                return END_ONLY;
            }
            if ("start-and-end".equals(normalized) || "start_and_end".equals(normalized) || "startend".equals(normalized)) {
                return START_AND_END;
            }
            return fallback;
        }
    }

    /**
     * Metadata detail level used for serialized request events.
     */
    enum MetadataMode {
        FULL,
        LIGHT;

        private static MetadataMode parse(String value, MetadataMode fallback) {
            String normalized = normalize(value);
            if ("light".equals(normalized)) {
                return LIGHT;
            }
            if ("full".equals(normalized)) {
                return FULL;
            }
            return fallback;
        }
    }

    /**
     * Parsed representation of a single {@code key=value} agent argument.
     */
    private static final class KeyValue {
        private final String key;
        private final String value;

        private KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
