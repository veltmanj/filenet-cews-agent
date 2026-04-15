package nl.nextend.filenet.cews.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice for {@code ServletInputStream.read()} single-byte reads.
 */
public final class ServletInputStreamAdvice {
    private ServletInputStreamAdvice() {
    }

    /**
     * Records one byte read from the servlet body stream.
     */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return int value) {
        CaptureState.onRead(value);
    }
}
