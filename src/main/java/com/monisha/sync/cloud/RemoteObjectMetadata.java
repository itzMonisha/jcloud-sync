package com.monisha.sync.cloud;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata record for an object stored in a cloud storage provider.
 */
public record RemoteObjectMetadata(
    String key,
    String etag,
    String sha256,
    Instant lastModified,
    long sizeBytes,
    Map<String, String> userMetadata
) {
    public RemoteObjectMetadata {
        Objects.requireNonNull(key, "key must not be null");
        userMetadata = userMetadata != null ? Map.copyOf(userMetadata) : Collections.emptyMap();
    }

    public RemoteObjectMetadata(String key, String etag, String sha256, Instant lastModified, long sizeBytes) {
        this(key, etag, sha256, lastModified, sizeBytes, Collections.emptyMap());
    }
}
