package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CaptureContext} JSON serialization and body preview handling.
 *
 * <p>The production agent depends on this class to preserve a bounded body preview
 * while still reporting exact byte counts and valid JSON. The tests therefore aim
 * at truncation, escaping, and conditional field emission.</p>
 */
class CaptureContextTest {
    /**
     * Verifies that body preview content is truncated to the configured maximum,
     * escaped for JSON output, and accompanied by the correct byte counters.
     */
    @Test
    void endEventIncludesEscapedBodyPreviewAndTruncation() {
        CaptureContext context = new CaptureContext(
            System.nanoTime(),
            System.currentTimeMillis(),
            new CaptureContext.RequestMetadata(
                "POST",
                "/wsi/FNCEWS40MTOM/",
                "operation=Ping",
                "127.0.0.1",
                "application/soap+xml",
                12L,
                Collections.singletonMap("SOAPAction", "Ping")
            ),
            4,
            true,
            RequestCaptureConfig.MetadataMode.FULL
        );

        context.onRead("ab\r\ncd".getBytes(), 0, 6);

        String eventJson = context.toEndEventJson(null, 200, 0);

        assertTrue(eventJson.contains("\"bodyPreview\":" + CaptureContext.jsonQuote("ab\\r\\n")));
        assertTrue(eventJson.contains("\"bodyTruncated\":true"));
        assertTrue(eventJson.contains("\"totalBodyBytes\":6"));
    }

    /**
     * Verifies that body preview is omitted entirely when body capture is disabled,
     * while the raw byte count is still retained for observability.
     */
    @Test
    void endEventOmitsBodyPreviewWhenCaptureDisabled() {
        CaptureContext context = new CaptureContext(
            System.nanoTime(),
            System.currentTimeMillis(),
            new CaptureContext.RequestMetadata(
                "GET",
                "/health",
                null,
                "127.0.0.1",
                "text/plain",
                0L,
                Collections.emptyMap()
            ),
            16,
            false,
            RequestCaptureConfig.MetadataMode.FULL
        );

        context.onRead('x');

        String eventJson = context.toEndEventJson(null, 200, 0);

        assertFalse(eventJson.contains("bodyPreview"));
        assertTrue(eventJson.contains("\"totalBodyBytes\":1"));
    }

    @Test
    void lightMetadataModeOmitsHeadersAndRemoteDetails() {
        CaptureContext context = new CaptureContext(
            System.nanoTime(),
            System.currentTimeMillis(),
            new CaptureContext.RequestMetadata(
                "POST",
                "/wsi/FNCEWS40MTOM/",
                "operation=Ping",
                "127.0.0.1",
                "application/soap+xml",
                12L,
                Collections.singletonMap("SOAPAction", "Ping")
            ),
            4,
            false,
            RequestCaptureConfig.MetadataMode.LIGHT
        );

        String eventJson = context.toEndEventJson(null, 200, 0);

        assertFalse(eventJson.contains("headers"));
        assertFalse(eventJson.contains("query"));
        assertFalse(eventJson.contains("remoteAddr"));
        assertFalse(eventJson.contains("contentLength"));
        assertTrue(eventJson.contains("\"contentType\":\"application/soap+xml\""));
    }
}