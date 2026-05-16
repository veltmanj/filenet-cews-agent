package nl.nextend.filenet.cews.agent;

import java.time.Instant;

import net.bytebuddy.asm.Advice;

final class ProbeServletConstructorAdvice {
    private ProbeServletConstructorAdvice() {
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(@Advice.Origin("#t") String typeName,
                       @Advice.This Object instance) {
        RequestCaptureConfig config = RequestCaptureAgent.config();
        AsyncEventWriter writer = RequestCaptureAgent.writer();
        if (config == null || writer == null || !config.diagnosticTransforms()) {
            return;
        }
        writer.enqueue("{\"timestamp\":\""
            + Instant.now().toString()
            + "\",\"phase\":\"probe-constructor-exit\",\"type\":"
            + CaptureContext.jsonQuote(typeName)
            + ",\"instanceType\":"
            + CaptureContext.jsonQuote(instance == null ? "null" : instance.getClass().getName())
            + "}");
    }
}