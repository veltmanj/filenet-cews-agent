package nl.nextend.filenet.cews.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice for {@code ServletInputStream.read(byte[], int, int)}.
 */
public final class ServletInputStreamArrayReadAdvice {
    private ServletInputStreamArrayReadAdvice() {
    }

    /**
     * Records a ranged block read from the servlet body stream.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) byte[] buffer,
                              @Advice.Argument(1) int offset,
                              @Advice.Return int read) {
        if (read > 0) {
            CaptureState.onRead(buffer, offset, read);
        }
    }
}