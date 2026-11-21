package com.monisha.sync.engine;

import com.monisha.sync.ai.DocumentAiMetadata;
import com.monisha.sync.ai.GeminiMetadataExtractor;
import com.monisha.sync.cloud.CloudStorageProvider;
import com.monisha.sync.cloud.RemoteObjectMetadata;
import com.monisha.sync.config.IgnoreMatcher;
import com.monisha.sync.config.SyncConfig;
import com.monisha.sync.hash.FileFingerprint;
import com.monisha.sync.hash.FileHasher;
import com.monisha.sync.retry.DeadLetterQueue;
import com.monisha.sync.retry.RetryExecutor;
import com.monisha.sync.state.FileState;
import com.monisha.sync.state.SyncStateStore;
import com.monisha.sync.state.SyncStatus;
import com.monisha.sync.watcher.ChangeType;
import com.monisha.sync.watcher.FileChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Orchestrator reconciling local file system state with remote cloud storage.
 * Uses Java 21 Virtual Threads with bounded concurrency, exponential backoff retries,
 * dead-letter logging, and an optional AI Metadata & Semantic Indexing hook.
 */
public class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);
    private static final DateTimeFormatter CONFLICT_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path localWatchDirectory;
    private final CloudStorageProvider cloudStorage;
    private final SyncStateStore stateStore;
    private final IgnoreMatcher ignoreMatcher;
    private final ConflictResolutionStrategy conflictStrategy;
    private final Semaphore transferSemaphore;
    private final RetryExecutor retryExecutor;
    private final DeadLetterQueue deadLetterQueue;
    private final GeminiMetadataExtractor aiExtractor;

    public SyncEngine(Path localWatchDirectory,
                      CloudStorageProvider cloudStorage,
                      SyncStateStore stateStore,
                      IgnoreMatcher ignoreMatcher,
                      ConflictResolutionStrategy conflictStrategy) {
        this(localWatchDirectory, cloudStorage, stateStore, ignoreMatcher, conflictStrategy, 8,
            new RetryExecutor(3, 150, 3000, 2.0, 0.25),
            new DeadLetterQueue(localWatchDirectory.resolve(".jcloud").resolve("dead_letter.log")),
            null);
    }

    public SyncEngine(Path localWatchDirectory,
                      CloudStorageProvider cloudStorage,
                      SyncStateStore stateStore,
                      IgnoreMatcher ignoreMatcher,
                      ConflictResolutionStrategy conflictStrategy,
                      int maxConcurrentTransfers,
                      RetryExecutor retryExecutor,
                      DeadLetterQueue deadLetterQueue,
                      GeminiMetadataExtractor aiExtractor) {
        this.localWatchDirectory = Objects.requireNonNull(localWatchDirectory, "localWatchDirectory must not be null").toAbsolutePath().normalize();
        this.cloudStorage = Objects.requireNonNull(cloudStorage, "cloudStorage must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.ignoreMatcher = ignoreMatcher;
        this.conflictStrategy = conflictStrategy != null ? conflictStrategy : ConflictResolutionStrategy.KEEP_BOTH;
        this.transferSemaphore = new Semaphore(Math.max(1, maxConcurrentTransfers));
        this.retryExecutor = retryExecutor != null ? retryExecutor : new RetryExecutor();
        this.deadLetterQueue = deadLetterQueue != null ? deadLetterQueue : new DeadLetterQueue();
        this.aiExtractor = aiExtractor;
    }

    public SyncEngine(SyncConfig config, CloudStorageProvider cloudStorage, SyncStateStore stateStore) {
        this(
            Path.of(config.getWatchDirectory()),
            cloudStorage,
            stateStore,
            config.createIgnoreMatcher(),
            config.getConflictResolutionStrategy(),
            8,
            new RetryExecutor(3, 150, 3000, 2.0, 0.25),
            new DeadLetterQueue(Path.of(config.getWatchDirectory()).resolve(".jcloud").resolve("dead_letter.log")),
            config.isAiIndexingEnabled() ? new GeminiMetadataExtractor() : null
        );
    }

    /**
     * Executes a complete bidirectional reconciliation pass using Java 21 Virtual Threads.
     *
     * @return SyncResult summarizing operations performed
     */
    public synchronized SyncResult reconcile() {
        log.info("Starting bidirectional reconciliation on [{}] with strategy [{}] (AI Indexing: {})",
            localWatchDirectory, conflictStrategy, (aiExtractor != null ? "ENABLED" : "DISABLED"));

        List<String> uploaded = new CopyOnWriteArrayList<>();
        List<String> downloaded = new CopyOnWriteArrayList<>();
        List<String> skipped = new CopyOnWriteArrayList<>();
        List<String> conflicts = new CopyOnWriteArrayList<>();
        List<String> deletedLocal = new CopyOnWriteArrayList<>();
        List<String> deletedRemote = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();

        try {
            if (!Files.exists(localWatchDirectory)) {
                Files.createDirectories(localWatchDirectory);
            }

            Map<String, Path> localFiles = scanLocalFiles();
            Map<String, RemoteObjectMetadata> remoteObjects = scanRemoteObjects();

            Set<String> allPaths = new TreeSet<>();
            allPaths.addAll(localFiles.keySet());
            allPaths.addAll(remoteObjects.keySet());
            for (FileState fs : stateStore.getAll()) {
                allPaths.add(fs.relativePath());
            }

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (String relPath : allPaths) {
                    if (isIgnored(relPath)) {
                        continue;
                    }

                    Path localPath = localWatchDirectory.resolve(relPath);
                    boolean localExists = Files.exists(localPath) && Files.isRegularFile(localPath);
                    RemoteObjectMetadata remoteMeta = remoteObjects.get(relPath);
                    boolean remoteExists = remoteMeta != null;
                    Optional<FileState> stateOpt = stateStore.get(relPath);

                    // Scenario A: Only local exists
                    if (localExists && !remoteExists) {
                        if (stateOpt.isEmpty()) {
                            executor.submit(() -> executeTransfer(relPath, "UPLOAD", () -> {
                                uploadLocalFile(localPath, relPath);
                                uploaded.add(relPath);
                            }, errors));
                        } else {
                            String localHash = FileHasher.computeSha256(localPath);
                            if (!localHash.equalsIgnoreCase(stateOpt.get().sha256Hash())) {
                                executor.submit(() -> executeTransfer(relPath, "UPLOAD", () -> {
                                    uploadLocalFile(localPath, relPath);
                                    uploaded.add(relPath);
                                }, errors));
                            } else {
                                executor.submit(() -> executeTransfer(relPath, "DELETE_LOCAL", () -> {
                                    Files.deleteIfExists(localPath);
                                    stateStore.delete(relPath);
                                    deletedLocal.add(relPath);
                                    log.info("Propagated remote deletion to local: [{}]", relPath);
                                }, errors));
                            }
                        }
                    }
                    // Scenario B: Only remote exists
                    else if (!localExists && remoteExists) {
                        if (stateOpt.isEmpty()) {
                            executor.submit(() -> executeTransfer(relPath, "DOWNLOAD", () -> {
                                downloadRemoteFile(relPath, localPath);
                                downloaded.add(relPath);
                            }, errors));
                        } else {
                            executor.submit(() -> executeTransfer(relPath, "DELETE_REMOTE", () -> {
                                cloudStorage.deleteFile(relPath);
                                stateStore.delete(relPath);
                                deletedRemote.add(relPath);
                                log.info("Propagated local deletion to remote: [{}]", relPath);
                            }, errors));
                        }
                    }
                    // Scenario C: Both exist
                    else if (localExists && remoteExists) {
                        FileFingerprint localFp = FileHasher.computeFingerprint(localPath);
                        String remoteHash = resolveRemoteHash(relPath, remoteMeta);

                        if (remoteHash != null && localFp.sha256Hash().equalsIgnoreCase(remoteHash)) {
                            // Ensure metadata is saved if available
                            DocumentAiMetadata aiMeta = DocumentAiMetadata.empty();
                            if (aiExtractor != null && isAiIndexable(localPath)) {
                                aiMeta = aiExtractor.extractMetadata(localPath);
                            }
                            stateStore.upsert(new FileState(
                                relPath,
                                localFp.sha256Hash(),
                                localFp.lastModifiedEpoch(),
                                SyncStatus.SYNCED,
                                localFp.sizeBytes(),
                                aiMeta.summary(),
                                aiMeta.tags()
                            ));
                            skipped.add(relPath);
                            continue;
                        }

                        boolean localChanged = stateOpt.isEmpty() || !localFp.sha256Hash().equalsIgnoreCase(stateOpt.get().sha256Hash());
                        boolean remoteChanged = stateOpt.isEmpty() || remoteHash == null || !remoteHash.equalsIgnoreCase(stateOpt.get().sha256Hash());

                        if (localChanged && !remoteChanged) {
                            executor.submit(() -> executeTransfer(relPath, "UPLOAD", () -> {
                                uploadLocalFile(localPath, relPath);
                                uploaded.add(relPath);
                            }, errors));
                        } else if (!localChanged && remoteChanged) {
                            executor.submit(() -> executeTransfer(relPath, "DOWNLOAD", () -> {
                                downloadRemoteFile(relPath, localPath);
                                downloaded.add(relPath);
                            }, errors));
                        } else {
                            conflicts.add(relPath);
                            executor.submit(() -> executeTransfer(relPath, "CONFLICT", () -> {
                                resolveConflict(relPath, localPath, localFp, remoteMeta);
                            }, errors));
                        }
                    }
                    // Scenario D: Neither exists
                    else {
                        stateStore.delete(relPath);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Reconciliation cycle encountered unexpected failure", e);
            errors.add("FATAL: " + e.getMessage());
        }

        SyncResult result = new SyncResult(uploaded, downloaded, skipped, conflicts, deletedLocal, deletedRemote, errors);
        log.info("Reconciliation finished: uploaded={}, downloaded={}, skipped={}, conflictsResolved={}, errors={}",
            result.uploadedCount(), result.downloadedCount(), result.skippedCount(), result.conflictsResolvedCount(), errors.size());
        return result;
    }

    private void executeTransfer(String relPath, String operation, TaskAction action, List<String> errors) {
        try {
            transferSemaphore.acquire();
            try {
                retryExecutor.executeVoid(action::execute, operation + ":" + relPath);
            } finally {
                transferSemaphore.release();
            }
        } catch (Exception e) {
            log.error("Transfer failed for [{}] after retries: {}", relPath, e.getMessage(), e);
            deadLetterQueue.recordFailure(relPath, operation, e, retryExecutor.getMaxAttempts());
            errors.add(relPath + " [" + operation + "]: " + e.getMessage());
            try {
                stateStore.updateStatus(relPath, SyncStatus.CONFLICT);
            } catch (Exception ignored) {}
        }
    }

    @FunctionalInterface
    private interface TaskAction {
        void execute() throws Exception;
    }

    /**
     * Handles an asynchronous event reported by DirectoryWatcher.
     *
     * @param event file change event
     */
    public synchronized void handleLocalChange(FileChangeEvent event) {
        String relPath = event.relativePath().toString().replace('\\', '/');
        if (isIgnored(relPath)) {
            return;
        }

        try {
            if (event.changeType() == ChangeType.DELETED) {
                retryExecutor.executeVoid(() -> {
                    cloudStorage.deleteFile(relPath);
                    stateStore.delete(relPath);
                }, "DELETE:" + relPath);
                log.info("Deleted [{}] from cloud and state store", relPath);
            } else {
                Path localFile = event.fullPath();
                if (Files.exists(localFile) && Files.isRegularFile(localFile)) {
                    retryExecutor.executeVoid(() -> uploadLocalFile(localFile, relPath), "UPLOAD:" + relPath);
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle local change event for [{}]", relPath, e);
            deadLetterQueue.recordFailure(relPath, event.changeType().name(), e, retryExecutor.getMaxAttempts());
            try {
                stateStore.updateStatus(relPath, SyncStatus.CONFLICT);
            } catch (Exception ignored) {}
        }
    }

    private void uploadLocalFile(Path localFile, String relPath) throws IOException, java.sql.SQLException {
        cloudStorage.uploadFile(localFile, relPath);
        FileFingerprint fp = FileHasher.computeFingerprint(localFile);

        DocumentAiMetadata aiMeta = DocumentAiMetadata.empty();
        if (aiExtractor != null && isAiIndexable(localFile)) {
            try {
                aiMeta = aiExtractor.extractMetadata(localFile);
            } catch (Exception e) {
                log.debug("AI extraction skipped for [{}]: {}", relPath, e.getMessage());
            }
        }

        stateStore.upsert(new FileState(
            relPath,
            fp.sha256Hash(),
            fp.lastModifiedEpoch(),
            SyncStatus.SYNCED,
            fp.sizeBytes(),
            aiMeta.summary(),
            aiMeta.tags()
        ));
        log.info("Uploaded [{}] (SHA-256: {})", relPath, fp.sha256Hash());
    }

    private void downloadRemoteFile(String relPath, Path localDestination) throws IOException, java.sql.SQLException {
        cloudStorage.downloadFile(relPath, localDestination);
        FileFingerprint fp = FileHasher.computeFingerprint(localDestination);

        DocumentAiMetadata aiMeta = DocumentAiMetadata.empty();
        if (aiExtractor != null && isAiIndexable(localDestination)) {
            try {
                aiMeta = aiExtractor.extractMetadata(localDestination);
            } catch (Exception e) {
                log.debug("AI extraction skipped for [{}]: {}", relPath, e.getMessage());
            }
        }

        stateStore.upsert(new FileState(
            relPath,
            fp.sha256Hash(),
            fp.lastModifiedEpoch(),
            SyncStatus.SYNCED,
            fp.sizeBytes(),
            aiMeta.summary(),
            aiMeta.tags()
        ));
        log.info("Downloaded [{}] (SHA-256: {})", relPath, fp.sha256Hash());
    }

    private void resolveConflict(String relPath, Path localPath, FileFingerprint localFp, RemoteObjectMetadata remoteMeta)
            throws IOException, java.sql.SQLException {
        log.warn("Conflict detected on [{}]! Applying resolution strategy: {}", relPath, conflictStrategy);

        switch (conflictStrategy) {
            case LOCAL_WINS -> {
                uploadLocalFile(localPath, relPath);
                log.info("Conflict resolved via LOCAL_WINS: uploaded local version of [{}]", relPath);
            }
            case REMOTE_WINS -> {
                downloadRemoteFile(relPath, localPath);
                log.info("Conflict resolved via REMOTE_WINS: overwritten local with remote version of [{}]", relPath);
            }
            case KEEP_BOTH -> {
                String conflictFileName = generateConflictFileName(localPath.getFileName().toString());
                Path conflictFilePath = localPath.resolveSibling(conflictFileName);

                Files.copy(localPath, conflictFilePath, StandardCopyOption.REPLACE_EXISTING);
                String conflictRelPath = localWatchDirectory.relativize(conflictFilePath).toString().replace('\\', '/');
                FileFingerprint conflictFp = FileHasher.computeFingerprint(conflictFilePath);
                stateStore.upsert(new FileState(
                    conflictRelPath,
                    conflictFp.sha256Hash(),
                    conflictFp.lastModifiedEpoch(),
                    SyncStatus.CONFLICT,
                    conflictFp.sizeBytes(),
                    "Conflicted local version preserved",
                    List.of("conflict")
                ));

                downloadRemoteFile(relPath, localPath);
                log.info("Conflict resolved via KEEP_BOTH: local saved to [{}] and remote saved to [{}]", conflictRelPath, relPath);
            }
        }
    }

    private boolean isAiIndexable(Path path) {
        if (path == null || path.getFileName() == null) return false;
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".pdf");
    }

    private String generateConflictFileName(String originalFileName) {
        String timestamp = LocalDateTime.now().format(CONFLICT_TIMESTAMP_FMT);
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String base = originalFileName.substring(0, dotIndex);
            String ext = originalFileName.substring(dotIndex);
            return base + ".conflict." + timestamp + ext;
        } else {
            return originalFileName + ".conflict." + timestamp;
        }
    }

    private String resolveRemoteHash(String relPath, RemoteObjectMetadata meta) throws IOException {
        if (meta.sha256() != null && !meta.sha256().isBlank()) {
            return meta.sha256();
        }
        Optional<RemoteObjectMetadata> fullMeta = cloudStorage.getObjectMetadata(relPath);
        if (fullMeta.isPresent() && fullMeta.get().sha256() != null) {
            return fullMeta.get().sha256();
        }
        return meta.etag();
    }

    private Map<String, Path> scanLocalFiles() throws IOException {
        Map<String, Path> map = new HashMap<>();
        Files.walkFileTree(localWatchDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (ignoreMatcher != null && ignoreMatcher.matches(localWatchDirectory, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    Path rel = localWatchDirectory.relativize(file);
                    String key = rel.toString().replace('\\', '/');
                    if (!isIgnored(key)) {
                        map.put(key, file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return map;
    }

    private Map<String, RemoteObjectMetadata> scanRemoteObjects() throws IOException {
        Map<String, RemoteObjectMetadata> map = new HashMap<>();
        for (RemoteObjectMetadata meta : cloudStorage.listRemoteObjects()) {
            String key = meta.key().replace('\\', '/');
            if (!isIgnored(key)) {
                map.put(key, meta);
            }
        }
        return map;
    }

    private boolean isIgnored(String relPath) {
        if (relPath.startsWith(".jcloud/") || relPath.equals(".jcloud") || relPath.startsWith(".jcloud\\")) {
            return true;
        }
        return ignoreMatcher != null && (ignoreMatcher.matches(relPath) || ignoreMatcher.matches(Path.of(relPath)));
    }

    public Path getLocalWatchDirectory() {
        return localWatchDirectory;
    }

    public CloudStorageProvider getCloudStorage() {
        return cloudStorage;
    }

    public SyncStateStore getStateStore() {
        return stateStore;
    }

    public ConflictResolutionStrategy getConflictStrategy() {
        return conflictStrategy;
    }

    public DeadLetterQueue getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public RetryExecutor getRetryExecutor() {
        return retryExecutor;
    }

    public GeminiMetadataExtractor getAiExtractor() {
        return aiExtractor;
    }
}
