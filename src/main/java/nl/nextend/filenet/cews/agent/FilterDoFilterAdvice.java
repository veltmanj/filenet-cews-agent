package nl.nextend.filenet.cews.agent;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
    public static void onEnter(@Advice.Argument(0) ServletRequest request) {
        if (request instanceof HttpServletRequest) {
            CaptureState.begin((HttpServletRequest) request, RequestCaptureAgent.config(), RequestCaptureAgent.writer());
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(1) ServletResponse response,
                              @Advice.Thrown Throwable throwable) {
        HttpServletResponse httpServletResponse = response instanceof HttpServletResponse
            ? (HttpServletResponse) response
            : null;
        CaptureState.end(httpServletResponse, throwable, RequestCaptureAgent.writer());
    }
}