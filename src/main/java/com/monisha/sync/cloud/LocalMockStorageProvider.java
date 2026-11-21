package com.monisha.sync.cloud;

import com.monisha.sync.hash.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Simulates a remote cloud bucket in a local filesystem directory.
 * Enables running end-to-end sync workflows offline without cloud credentials or internet connectivity.
 */
public class LocalMockStorageProvider implements CloudStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalMockStorageProvider.class);

    private final Path mockBucketRoot;
    private final Map<String, RemoteObjectMetadata> metadataIndex = new ConcurrentHashMap<>();

    public LocalMockStorageProvider(Path mockBucketRoot) throws IOException {
        this.mockBucketRoot = Objects.requireNonNull(mockBucketRoot, "mockBucketRoot must not be null").toAbsolutePath().normalize();
        if (!Files.exists(this.mockBucketRoot)) {
            Files.createDirectories(this.mockBucketRoot);
        }
    }

    @Override
    public RemoteObjectMetadata uploadFile(Path localFile, String remoteKey) throws IOException {
        Objects.requireNonNull(localFile, "localFile must not be null");
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");

        String normalizedKey = normalizeKey(remoteKey);
        Path targetPath = mockBucketRoot.resolve(normalizedKey);

        Path parent = targetPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Files.copy(localFile, targetPath, StandardCopyOption.REPLACE_EXISTING);

        String sha256 = FileHasher.computeSha256(targetPath);
        long size = Files.size(targetPath);
        Instant lastModified = Files.getLastModifiedTime(targetPath).toInstant();
        String etag = "etag-" + sha256.substring(0, Math.min(16, sha256.length()));

        RemoteObjectMetadata meta = new RemoteObjectMetadata(
            normalizedKey,
            etag,
            sha256,
            lastModified,
            size,
            Map.of("sha256", sha256)
        );

        metadataIndex.put(normalizedKey, meta);
        log.debug("Mock uploaded [{}] to [{}]", localFile, normalizedKey);
        return meta;
    }

    @Override
    public void downloadFile(String remoteKey, Path localDestination) throws IOException {
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");
        Objects.requireNonNull(localDestination, "localDestination must not be null");

        String normalizedKey = normalizeKey(remoteKey);
        Path sourcePath = mockBucketRoot.resolve(normalizedKey);

        if (!Files.exists(sourcePath)) {
            throw new FileNotFoundException("Remote mock object not found: " + normalizedKey);
        }

        Path parent = localDestination.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Files.copy(sourcePath, localDestination, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Mock downloaded [{}] to [{}]", normalizedKey, localDestination);
    }

    @Override
    public void deleteFile(String remoteKey) throws IOException {
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");
        String normalizedKey = normalizeKey(remoteKey);
        Path targetPath = mockBucketRoot.resolve(normalizedKey);

        Files.deleteIfExists(targetPath);
        metadataIndex.remove(normalizedKey);
        log.debug("Mock deleted [{}]", normalizedKey);
    }

    @Override
    public List<RemoteObjectMetadata> listRemoteObjects() throws IOException {
        return listRemoteObjects(null);
    }

    @Override
    public List<RemoteObjectMetadata> listRemoteObjects(String prefix) throws IOException {
        List<RemoteObjectMetadata> results = new ArrayList<>();
        String normalizedPrefix = prefix != null ? normalizeKey(prefix) : "";

        try (Stream<Path> stream = Files.walk(mockBucketRoot)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String key = mockBucketRoot.relativize(file).toString().replace('\\', '/');
                if (normalizedPrefix.isEmpty() || key.startsWith(normalizedPrefix)) {
                    getObjectMetadata(key).ifPresent(results::add);
                }
            }
        }
        return results;
    }

    @Override
    public Optional<RemoteObjectMetadata> getObjectMetadata(String remoteKey) throws IOException {
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");
        String normalizedKey = normalizeKey(remoteKey);
        Path targetPath = mockBucketRoot.resolve(normalizedKey);

        if (!Files.exists(targetPath)) {
            metadataIndex.remove(normalizedKey);
            return Optional.empty();
        }

        RemoteObjectMetadata cached = metadataIndex.get(normalizedKey);
        Instant diskModified = Files.getLastModifiedTime(targetPath).toInstant();

        if (cached != null && cached.lastModified().equals(diskModified)) {
            return Optional.of(cached);
        }

        // Recompute if modified or not cached
        String sha256 = FileHasher.computeSha256(targetPath);
        long size = Files.size(targetPath);
        String etag = "etag-" + sha256.substring(0, Math.min(16, sha256.length()));
        RemoteObjectMetadata meta = new RemoteObjectMetadata(
            normalizedKey,
            etag,
            sha256,
            diskModified,
            size,
            Map.of("sha256", sha256)
        );
        metadataIndex.put(normalizedKey, meta);
        return Optional.of(meta);
    }

    public Path getMockBucketRoot() {
        return mockBucketRoot;
    }

    private String normalizeKey(String key) {
        return key.replace('\\', '/').replaceAll("^/+", "");
    }
}
