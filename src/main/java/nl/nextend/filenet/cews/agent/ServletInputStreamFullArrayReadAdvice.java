package nl.nextend.filenet.cews.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice for {@code ServletInputStream.read(byte[])}.
 */
public final class ServletInputStreamFullArrayReadAdvice {
    private ServletInputStreamFullArrayReadAdvice() {
    }

    /**
     * Records a full-array read from the servlet body stream.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) byte[] buffer,
                              @Advice.Return int read) {
        if (read > 0) {
            CaptureState.onRead(buffer, 0, read);
        }
    }
}