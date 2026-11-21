package com.monisha.sync.watcher;

import com.monisha.sync.config.IgnoreMatcher;
import com.monisha.sync.config.SyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a root directory recursively using Java NIO WatchService with debounce coalescing
 * and glob ignore filtering.
 */
public class DirectoryWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DirectoryWatcher.class);

    private final Path rootDirectory;
    private final long debounceMs;
    private final IgnoreMatcher ignoreMatcher;
    private final Consumer<FileChangeEvent> listener;

    private final WatchService watchService;
    private final Map<WatchKey, Path> watchKeys = new ConcurrentHashMap<>();
    private final Map<Path, DebounceItem> pendingEvents = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceExecutor;

    private volatile boolean running = false;
    private Thread watchThread;

    private static class DebounceItem {
        final Path fullPath;
        final Path relativePath;
        ChangeType changeType;
        ScheduledFuture<?> scheduledFuture;
        long lastUpdated;

        DebounceItem(Path fullPath, Path relativePath, ChangeType changeType) {
            this.fullPath = fullPath;
            this.relativePath = relativePath;
            this.changeType = changeType;
            this.lastUpdated = System.currentTimeMillis();
        }
    }

    public DirectoryWatcher(Path rootDirectory,
                            long debounceMs,
                            IgnoreMatcher ignoreMatcher,
                            Consumer<FileChangeEvent> listener) throws IOException {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null").toAbsolutePath().normalize();
        this.debounceMs = debounceMs > 0 ? debounceMs : 500;
        this.ignoreMatcher = ignoreMatcher;
        this.listener = Objects.requireNonNull(listener, "listener must not be null");

        this.watchService = FileSystems.getDefault().newWatchService();
        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jcloud-debounce-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public DirectoryWatcher(SyncConfig config, Consumer<FileChangeEvent> listener) throws IOException {
        this(
            Path.of(config.getWatchDirectory()),
            config.getDebounceMs(),
            config.createIgnoreMatcher(),
            listener
        );
    }

    /**
     * Starts watching the root directory and all its subdirectories recursively.
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        if (!Files.exists(rootDirectory)) {
            Files.createDirectories(rootDirectory);
        }

        registerAll(rootDirectory);
        running = true;

        watchThread = new Thread(this::runWatchLoop, "jcloud-watchservice-loop");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("Started DirectoryWatcher on [{}] with debounce {}ms", rootDirectory, debounceMs);
    }

    private void runWatchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                log.debug("DirectoryWatcher loop interrupted or closed");
                break;
            }

            Path dir = watchKeys.get(key);
            if (dir == null) {
                key.cancel();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path name = ev.context();
                Path child = dir.resolve(name);
                Path relative = rootDirectory.relativize(child);

                // Ignore filter check
                if (ignoreMatcher != null && (ignoreMatcher.matches(rootDirectory, child) || ignoreMatcher.matches(relative))) {
                    log.debug("Ignored event on [{}] matching pattern", relative);
                    continue;
                }

                ChangeType type = mapKindToChangeType(kind);

                // If a new directory is created, register it and nested subdirs recursively
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                            registerAll(child);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to recursively register newly created directory: {}", child, e);
                    }
                }

                // Submit to debounce coalescing
                enqueueEvent(child, relative, type);
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeys.remove(key);
                if (watchKeys.isEmpty()) {
                    log.warn("All watched directories are inaccessible or removed");
                }
            }
        }
    }

    /**
     * Enqueues an event into the debounce coalescing buffer.
     */
    public void enqueueEvent(Path fullPath, Path relativePath, ChangeType newType) {
        synchronized (pendingEvents) {
            DebounceItem item = pendingEvents.get(relativePath);
            if (item != null) {
                if (item.scheduledFuture != null) {
                    item.scheduledFuture.cancel(false);
                }

                // Coalesce rapid changes:
                // 1. CREATED then DELETED before debounce finishes: ephemeral file -> discard completely
                if (item.changeType == ChangeType.CREATED && newType == ChangeType.DELETED) {
                    pendingEvents.remove(relativePath);
                    return;
                }
                // 2. CREATED then MODIFIED: still a CREATED event once debounce finishes
                else if (item.changeType == ChangeType.CREATED && newType == ChangeType.MODIFIED) {
                    item.changeType = ChangeType.CREATED;
                }
                // 3. MODIFIED then DELETED: becomes DELETED
                else if (newType == ChangeType.DELETED) {
                    item.changeType = ChangeType.DELETED;
                }
                // 4. Any other sequence: update to latest
                else {
                    item.changeType = newType;
                }

                item.lastUpdated = System.currentTimeMillis();
                item.scheduledFuture = debounceExecutor.schedule(() -> dispatch(relativePath), debounceMs, TimeUnit.MILLISECONDS);
            } else {
                DebounceItem newItem = new DebounceItem(fullPath, relativePath, newType);
                newItem.scheduledFuture = debounceExecutor.schedule(() -> dispatch(relativePath), debounceMs, TimeUnit.MILLISECONDS);
                pendingEvents.put(relativePath, newItem);
            }
        }
    }

    private void dispatch(Path relativePath) {
        DebounceItem item;
        synchronized (pendingEvents) {
            item = pendingEvents.remove(relativePath);
        }
        if (item != null) {
            try {
                listener.accept(new FileChangeEvent(item.fullPath, item.relativePath, item.changeType, System.currentTimeMillis()));
            } catch (Exception e) {
                log.error("Error dispatching FileChangeEvent for [{}]", relativePath, e);
            }
        }
    }

    /**
     * Flushes all currently debounced events immediately to the listener.
     */
    public void flushPending() {
        Map<Path, DebounceItem> toDispatch;
        synchronized (pendingEvents) {
            toDispatch = Map.copyOf(pendingEvents);
            for (DebounceItem item : toDispatch.values()) {
                if (item.scheduledFuture != null) {
                    item.scheduledFuture.cancel(false);
                }
            }
            pendingEvents.clear();
        }

        for (DebounceItem item : toDispatch.values()) {
            try {
                listener.accept(new FileChangeEvent(item.fullPath, item.relativePath, item.changeType, System.currentTimeMillis()));
            } catch (Exception e) {
                log.error("Error flushing FileChangeEvent for [{}]", item.relativePath, e);
            }
        }
    }

    public int getPendingCount() {
        synchronized (pendingEvents) {
            return pendingEvents.size();
        }
    }

    private void registerDirectory(Path dir) throws IOException {
        if (ignoreMatcher != null && ignoreMatcher.matches(rootDirectory, dir)) {
            return;
        }
        WatchKey key = dir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        );
        watchKeys.put(key, dir);
    }

    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (ignoreMatcher != null && ignoreMatcher.matches(rootDirectory, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private ChangeType mapKindToChangeType(WatchEvent.Kind<?> kind) {
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            return ChangeType.CREATED;
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            return ChangeType.DELETED;
        } else {
            return ChangeType.MODIFIED;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public long getDebounceMs() {
        return debounceMs;
    }

    @Override
    public synchronized void close() throws IOException {
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            log.warn("Error closing WatchService", e);
        }

        if (watchThread != null) {
            watchThread.interrupt();
        }

        debounceExecutor.shutdownNow();
        try {
            if (!debounceExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("Debounce executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        watchKeys.clear();
        pendingEvents.clear();
        log.info("DirectoryWatcher stopped");
    }
}
