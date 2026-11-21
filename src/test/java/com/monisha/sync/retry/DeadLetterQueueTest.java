package com.monisha.sync.retry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeadLetterQueueTest {

    @Test
    void testRecordFailureAndPersist(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("dead_letter.log");
        DeadLetterQueue dlq = new DeadLetterQueue(logFile);

        assertEquals(0, dlq.size());

        dlq.recordFailure("docs/critical.pdf", "UPLOAD", new IOException("Connection reset"), 3);
        dlq.recordFailure("images/banner.png", "DOWNLOAD", new RuntimeException("404 Not Found"), 3);

        assertEquals(2, dlq.size());
        List<DeadLetterQueue.DeadLetterEntry> entries = dlq.getEntries();
        assertEquals(2, entries.size());

        assertEquals("docs/critical.pdf", entries.get(0).relativePath());
        assertEquals("UPLOAD", entries.get(0).operation());
        assertEquals("Connection reset", entries.get(0).errorMessage());
        assertEquals(3, entries.get(0).attempts());

        // Check file persistence
        assertTrue(Files.exists(logFile));
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("docs/critical.pdf"));
        assertTrue(lines.get(1).contains("images/banner.png"));

        // Clear
        dlq.clear();
        assertEquals(0, dlq.size());
        assertFalse(Files.exists(logFile));
    }
}
