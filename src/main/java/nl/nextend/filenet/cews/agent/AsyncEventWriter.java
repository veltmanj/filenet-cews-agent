package nl.nextend.filenet.cews.agent;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous newline-delimited event writer.
 *
 * <p>Request threads should not block on file I/O, so events are queued in memory
 * and flushed by a dedicated daemon thread. A special shutdown sentinel is used to
 * tell the worker thread to finish draining the queue, flush outstanding lines and
 * terminate cleanly.</p>
 */
public final class AsyncEventWriter implements Closeable {
    private static final int MIN_QUEUE_CAPACITY = 256;
    private static final long SHUTDOWN_JOIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long SHUTDOWN_SIGNAL_RETRY_MILLIS = 100L;

    private static final String SHUTDOWN_SIGNAL = "__STOP__";
    private static final Logger LOGGER = Logger.getLogger(AsyncEventWriter.class.getName());

    private final BlockingQueue<String> queue;
    private final Thread writerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong droppedEvents = new AtomicLong();
    private final boolean logWriteFailures;

    /**
     * Creates and starts a new asynchronous writer.
     *
     * @param outputFile destination file for newline-delimited JSON events
     * @param capacity requested queue capacity; a minimum floor is enforced to keep
     *                 the writer useful under burst traffic
     */
    AsyncEventWriter(File outputFile, int capacity) {
        this(outputFile, capacity, true);
    }

    AsyncEventWriter(File outputFile, int capacity, boolean logWriteFailures) {
        this.queue = new ArrayBlockingQueue<>(Math.max(MIN_QUEUE_CAPACITY, capacity));
        this.logWriteFailures = logWriteFailures;
        this.writerThread = new Thread(new WriterTask(outputFile), "request-capture-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Attempts to enqueue one serialized event. If the queue is already full, the
     * event is dropped and the drop counter is incremented instead of blocking the
     * request thread.
     */
    public void enqueue(String event) {
        if (event == null || closed.get()) {
            return;
        }
        if (!queue.offer(event)) {
            droppedEvents.incrementAndGet();
        }
    }

    /**
     * Returns the number of events dropped because the writer queue was full.
     */
    long droppedEvents() {
        return droppedEvents.get();
    }

    /**
     * Stops accepting new events, waits for the worker thread to drain buffered
     * events, and then joins the writer thread for a bounded period.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        enqueueShutdownSignal();
        try {
            writerThread.join(SHUTDOWN_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Retries until the shutdown signal can be published or the worker has already
     * exited.
     *
     * <p>The queue may be full when shutdown begins, so a single non-blocking offer
     * is not reliable enough for the sentinel used to stop the writer thread.</p>
     */
    private void enqueueShutdownSignal() {
        while (writerThread.isAlive()) {
            try {
                if (queue.offer(SHUTDOWN_SIGNAL, SHUTDOWN_SIGNAL_RETRY_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Background task responsible for consuming queued events and writing them to
     * disk in FIFO order.
     */
    private final class WriterTask implements Runnable {
        private final File outputFile;

        private WriterTask(File outputFile) {
            this.outputFile = outputFile;
        }

        @Override
        public void run() {
            ensureOutputDirectoryExists();
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile, true), StandardCharsets.UTF_8))) {
                while (true) {
                    String event = queue.take();
                    if (SHUTDOWN_SIGNAL.equals(event)) {
                        flushRemainingEvents(writer);
                        return;
                    }
                    writeEventLine(writer, event);
                    if (drain(writer)) {
                        writer.flush();
                        return;
                    }
                    writer.flush();
                }
            } catch (IOException ignored) {
                if (logWriteFailures) {
                    LOGGER.log(Level.WARNING, ignored, () -> "Failed to write events to " + outputFile);
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Writes all currently queued events and returns whether a shutdown signal
         * was encountered while draining the queue.
         */
        private boolean drain(BufferedWriter writer) throws IOException {
            boolean shutdownRequested = false;
            while (true) {
                String next = queue.poll();
                if (next == null) {
                    return shutdownRequested;
                }
                if (SHUTDOWN_SIGNAL.equals(next)) {
                    shutdownRequested = true;
                    continue;
                }
                writeEventLine(writer, next);
            }
        }

        private void ensureOutputDirectoryExists() {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        }

        private void flushRemainingEvents(BufferedWriter writer) throws IOException {
            drain(writer);
            writer.flush();
        }

        private void writeEventLine(BufferedWriter writer, String event) throws IOException {
            writer.write(event);
            writer.newLine();
        }
    }
}
