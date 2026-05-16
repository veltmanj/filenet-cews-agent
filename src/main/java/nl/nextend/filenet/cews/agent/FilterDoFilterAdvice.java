package nl.nextend.filenet.cews.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice attached to servlet filter {@code doFilter(...)} methods.
 *
 * <p>This provides an earlier request entrypoint for containers where the target
 * application traffic is routed through filters before any observable servlet
 * service method is reached.</p>
 */
public final class FilterDoFilterAdvice {
    private FilterDoFilterAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object request) {
        CaptureState.begin(request, RequestCaptureAgent.config(), RequestCaptureAgent.writer());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) Object request,
                              @Advice.Argument(1) Object response,
                              @Advice.Thrown Throwable throwable) {
        CaptureState.end(request, response, throwable, RequestCaptureAgent.writer());
    }
}