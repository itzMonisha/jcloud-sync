package com.monisha.sync.retry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks failed file transfer operations that exhausted retry attempts.
 * Records failures in-memory and appends them to a persistent log file.
 */
public class DeadLetterQueue {

    public record DeadLetterEntry(
        String relativePath,
        String operation,
        String errorMessage,
        Instant failedAt,
        int attempts
    ) {
        public String toLogLine() {
            return String.format("[%s] OP=%s PATH=%s ATTEMPTS=%d ERROR=%s",
                failedAt, operation, relativePath, attempts, errorMessage);
        }
    }

    private final Path logFilePath;
    private final List<DeadLetterEntry> inMemoryEntries = new CopyOnWriteArrayList<>();

    public DeadLetterQueue() {
        this(null);
    }

    public DeadLetterQueue(Path logFilePath) {
        this.logFilePath = logFilePath;
    }

    public synchronized void recordFailure(String relativePath, String operation, Throwable error, int attempts) {
        String msg = error != null ? (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()) : "Unknown error";
        DeadLetterEntry entry = new DeadLetterEntry(
            Objects.requireNonNull(relativePath, "relativePath must not be null"),
            Objects.requireNonNull(operation, "operation must not be null"),
            msg,
            Instant.now(),
            attempts
        );

        inMemoryEntries.add(entry);

        if (logFilePath != null) {
            try {
                Path parent = logFilePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Files.writeString(logFilePath, entry.toLogLine() + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }
    }

    public List<DeadLetterEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(inMemoryEntries));
    }

    public int size() {
        return inMemoryEntries.size();
    }

    public synchronized void clear() {
        inMemoryEntries.clear();
        if (logFilePath != null && Files.exists(logFilePath)) {
            try {
                Files.deleteIfExists(logFilePath);
            } catch (IOException ignored) {}
        }
    }

    public Path getLogFilePath() {
        return logFilePath;
    }
}
