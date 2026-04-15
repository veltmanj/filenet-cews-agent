package nl.nextend.filenet.cews.agent;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice attached to servlet {@code service(...)} methods.
 *
 * <p>This advice marks the beginning and end of the high-level servlet request so
 * the agent can create and later flush the per-request {@link CaptureContext}.
 * The hook is intentionally written against generic servlet method signatures so
 * container-specific wrapper implementations can still be observed.</p>
 */
public final class ServletServiceAdvice {
    private ServletServiceAdvice() {
    }

    /**
     * Starts capture when the servlet request enters the service method.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) ServletRequest request) {
        if (request instanceof HttpServletRequest) {
            CaptureState.begin((HttpServletRequest) request, RequestCaptureAgent.config(), RequestCaptureAgent.writer());
        }
    }

    /**
     * Completes capture when the servlet request leaves the service method.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(1) ServletResponse response,
                              @Advice.Thrown Throwable throwable) {
        HttpServletResponse httpServletResponse = response instanceof HttpServletResponse
            ? (HttpServletResponse) response
            : null;
        CaptureState.end(httpServletResponse, throwable, RequestCaptureAgent.writer());
    }
}
