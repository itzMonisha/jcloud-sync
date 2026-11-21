package com.monisha.sync.hash;

/**
 * Snapshot of a file's fingerprint including its SHA-256 hash, last modified epoch, and size.
 */
public record FileFingerprint(
    String sha256Hash,
    long lastModifiedEpoch,
    long sizeBytes
) {
    public FileFingerprint {
        if (sha256Hash == null || sha256Hash.isBlank()) {
            throw new IllegalArgumentException("sha256Hash must not be null or blank");
        }
    }
}
