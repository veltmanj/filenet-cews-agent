package nl.nextend.filenet.cews.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AsyncEventWriter}.
 *
 * <p>These tests focus on the operational guarantees the rest of the agent relies
 * on: queued events must be flushed on shutdown, late writes must be ignored after
 * close, and the failure path must account for dropped events once the background
 * writer can no longer consume the queue.</p>
 */
class AsyncEventWriterTest {
    @TempDir
    Path tempDir;

    /**
     * Verifies that a normal shutdown drains the in-memory queue and persists the
     * queued events in FIFO order.
     */
    @Test
    void closeFlushesQueuedEvents() throws IOException {
        Path outputFile = tempDir.resolve("events.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);

        writer.enqueue("first");
        writer.enqueue("second");
        writer.close();

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("first", lines.get(0));
        assertEquals("second", lines.get(1));
    }

    /**
     * Verifies that once the writer has been closed it stops accepting new work,
     * which prevents request threads from enqueueing events that can never be
     * written.
     */
    @Test
    void closePreventsFurtherEventsFromBeingAccepted() throws IOException {
        Path outputFile = tempDir.resolve("events-after-close.ndjson");
        AsyncEventWriter writer = new AsyncEventWriter(outputFile.toFile(), 8);

        writer.enqueue("before-close");
        writer.close();
        writer.enqueue("after-close");

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals("before-close", lines.get(0));
        assertTrue(writer.droppedEvents() >= 0);
    }

    /**
     * Forces the background writer into a permanent failure by using a directory
     * as the output target. Once the worker has failed, the queue should fill up
     * and the drop counter must increase, proving the failure path is observable.
     */
    @Test
    void incrementsDroppedEventsWhenQueueFillsAfterWorkerFailure() throws IOException {
        Path directoryTarget = tempDir.resolve("writer-target-directory");
        Files.createDirectories(directoryTarget);

        AsyncEventWriter writer = new AsyncEventWriter(directoryTarget.toFile(), 8);

        for (int index = 0; index < 5_000; index++) {
            writer.enqueue("event-" + index);
        }

        writer.close();

        assertTrue(writer.droppedEvents() > 0);
        assertTrue(Files.isDirectory(directoryTarget));
        assertFalse(Files.isRegularFile(directoryTarget));
        assertNotEquals(0L, writer.droppedEvents());
    }
}