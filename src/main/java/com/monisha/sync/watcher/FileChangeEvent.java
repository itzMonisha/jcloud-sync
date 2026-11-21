package com.monisha.sync.watcher;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Event representing a debounced and filtered file change.
 */
public record FileChangeEvent(
    Path fullPath,
    Path relativePath,
    ChangeType changeType,
    long timestamp
) {
    public FileChangeEvent {
        Objects.requireNonNull(fullPath, "fullPath must not be null");
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        Objects.requireNonNull(changeType, "changeType must not be null");
    }
}
