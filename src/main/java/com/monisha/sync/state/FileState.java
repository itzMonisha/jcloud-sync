package com.monisha.sync.state;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the persistent tracking state of a file in the sync directory,
 * including cryptographic fingerprints and optional AI semantic metadata.
 */
public record FileState(
    String relativePath,
    String sha256Hash,
    long lastModifiedEpoch,
    SyncStatus syncStatus,
    long fileSize,
    String summary,
    List<String> tags
) {
    public FileState {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        Objects.requireNonNull(sha256Hash, "sha256Hash must not be null");
        Objects.requireNonNull(syncStatus, "syncStatus must not be null");
        summary = summary != null ? summary : "";
        tags = tags != null ? List.copyOf(tags) : Collections.emptyList();
    }

    public FileState(String relativePath, String sha256Hash, long lastModifiedEpoch, SyncStatus syncStatus, long fileSize) {
        this(relativePath, sha256Hash, lastModifiedEpoch, syncStatus, fileSize, "", List.of());
    }

    public FileState(String relativePath, String sha256Hash, long lastModifiedEpoch, SyncStatus syncStatus) {
        this(relativePath, sha256Hash, lastModifiedEpoch, syncStatus, 0L, "", List.of());
    }
}
