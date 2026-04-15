package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequestCaptureConfigTest {
    @Test
    void matchesWildcardContentTypes() {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "captureBody=true,bodyContentTypes=text/*|application/json|*/xml|*+xml");

        assertTrue(config.shouldCaptureBodyContentType("text/xml;charset=UTF-8"));
        assertTrue(config.shouldCaptureBodyContentType("application/json"));
        assertTrue(config.shouldCaptureBodyContentType("application/soap+xml"));
        assertTrue(config.shouldCaptureBodyContentType("application/custom+xml"));
        assertFalse(config.shouldCaptureBodyContentType("application/octet-stream"));
    }

    @Test
    void appliesFilenetCewsProfileDefaults() {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs("profile=filenet-cews");

        assertTrue(config.matchesUri("/wsi/FNCEWS40MTOM/"));
        assertTrue(config.matchesUri("/FileNet/FNCEWS"));
        assertFalse(config.matchesUri("/health"));
        assertFalse(config.captureBody());
        assertTrue(config.shouldIncludeHeader("SOAPAction"));
        assertTrue(config.shouldRedactHeader("Proxy-Authorization"));
    }

    @Test
    void explicitArgsOverrideFilenetCewsProfileDefaults() {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(
            "profile=filenet-cews,captureBody=true,maxBodyBytes=512,includeUri=.*/CustomService/.*");

        assertTrue(config.captureBody());
        assertTrue(config.matchesUri("/CustomService/endpoint"));
        assertFalse(config.matchesUri("/wsi/FNCEWS40MTOM/"));
        assertEquals(512, config.maxBodyBytes());
    }

    @Test
    void appliesLowOverheadProfileDefaults() {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs("profile=filenet-cews-low-overhead");

        assertTrue(config.matchesUri("/wsi/FNCEWS40MTOM/"));
        assertFalse(config.captureBody());
        assertFalse(config.emitStartEvent());
        assertTrue(config.metadataLight());
        assertEquals(512, config.maxBodyBytes());
    }

    @Test
    void enablesTransformDiagnosticsWhenRequested() {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs("diagnosticTransforms=true");

        assertTrue(config.diagnosticTransforms());
    }

    @Test
    void enablesTransformDiagnosticsByDefault() {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs(null);

        assertTrue(config.diagnosticTransforms());
    }

    @Test
    void allowsTransformDiagnosticsToBeDisabledExplicitly() {
        RequestCaptureConfig config = RequestCaptureConfig.fromAgentArgs("diagnosticTransforms=false");

        assertFalse(config.diagnosticTransforms());
    }
}
