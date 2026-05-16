package nl.nextend.filenet.cews.agent;

import java.time.Instant;

import net.bytebuddy.asm.Advice;

final class ProbeServletHandlerAdvice {
    private ProbeServletHandlerAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(@Advice.Origin("#t") String typeName,
                        @Advice.Origin("#m") String methodName,
                        @Advice.Argument(0) Object request,
                        @Advice.Argument(1) Object response) {
        RequestCaptureConfig config = RequestCaptureAgent.config();
        AsyncEventWriter writer = RequestCaptureAgent.writer();
        ServletApiBridge.setResponseHeader(response, "X-Probe-Handler", typeName + "#" + methodName);
        if (config == null || writer == null || !config.diagnosticTransforms()) {
            return;
        }
        writer.enqueue("{\"timestamp\":\""
            + Instant.now().toString()
            + "\",\"phase\":\"probe-handler-enter\",\"type\":"
            + CaptureContext.jsonQuote(typeName)
            + ",\"method\":"
            + CaptureContext.jsonQuote(methodName)
            + ",\"requestType\":"
            + CaptureContext.jsonQuote(request == null ? "null" : request.getClass().getName())
            + "}");
    }
}