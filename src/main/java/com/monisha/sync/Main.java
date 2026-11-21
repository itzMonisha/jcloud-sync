package com.monisha.sync;

import com.monisha.sync.ai.GeminiMetadataExtractor;
import com.monisha.sync.cloud.CloudStorageProvider;
import com.monisha.sync.cloud.LocalMockStorageProvider;
import com.monisha.sync.cloud.S3StorageProvider;
import com.monisha.sync.config.SyncConfig;
import com.monisha.sync.engine.ConflictResolutionStrategy;
import com.monisha.sync.engine.SyncEngine;
import com.monisha.sync.engine.SyncResult;
import com.monisha.sync.retry.DeadLetterQueue;
import com.monisha.sync.retry.RetryExecutor;
import com.monisha.sync.state.FileState;
import com.monisha.sync.state.SyncStateStore;
import com.monisha.sync.state.SyncStatus;
import com.monisha.sync.watcher.DirectoryWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(
    name = "jcloudsync",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "JCloudSync - High-performance local directory to enterprise cloud storage synchronization tool",
    subcommands = {
        Main.InitCommand.class,
        Main.SyncCommand.class,
        Main.WatchCommand.class,
        Main.StatusCommand.class,
        Main.SearchCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Override
    public Integer call() {
        System.out.println("JCloudSync ready");
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    // =========================================================================
    // Subcommand: init
    // =========================================================================
    @Command(name = "init", mixinStandardHelpOptions = true, description = "Generates a starter config.yaml configuration file")
    public static class InitCommand implements Callable<Integer> {

        @Option(names = {"-f", "--force"}, description = "Overwrite existing configuration file if present")
        private boolean force;

        @Option(names = {"-o", "--output"}, description = "Output path for the configuration file", defaultValue = "config.yaml")
        private Path outputPath;

        @Override
        public Integer call() {
            try {
                if (Files.exists(outputPath) && !force) {
                    System.err.printf("[WARNING] %s already exists. Use --force to overwrite.%n", outputPath);
                    return 1;
                }

                String starterYaml = """
                    watchDirectory: "./sync-folder"
                    s3Bucket: "jcloud-sync-dev"
                    s3Region: "us-east-1"
                    s3EndpointOverride: "http://localhost:4566"
                    debounceMs: 500
                    metricsPort: 9090
                    conflictStrategy: "KEEP_BOTH"
                    aiIndexingEnabled: false
                    ignorePatterns: ["*.tmp", "*.swp", ".git/**", ".DS_Store", ".jcloud/**"]
                    """;

                Files.writeString(outputPath, starterYaml);
                System.out.printf("[SUCCESS] Generated starter configuration at: %s%n", outputPath.toAbsolutePath().normalize());
                return 0;
            } catch (Exception e) {
                System.err.printf("[ERROR] Failed to generate configuration: %s%n", e.getMessage());
                return 1;
            }
        }
    }

    // =========================================================================
    // Subcommand: sync
    // =========================================================================
    @Command(name = "sync", mixinStandardHelpOptions = true, description = "Reconciles local files and cloud storage")
    public static class SyncCommand implements Callable<Integer> {

        @Option(names = {"--once"}, description = "Runs a single reconciliation pass and exits", defaultValue = "true")
        private boolean once;

        @Option(names = {"-c", "--config"}, description = "Path to config YAML", defaultValue = "config.yaml")
        private Path configPath;

        @Option(names = {"-m", "--mock"}, description = "Run against local mock cloud bucket (offline mode)")
        private boolean mockMode;

        @Option(names = {"-s", "--strategy"}, description = "Conflict resolution strategy: LOCAL_WINS, REMOTE_WINS, KEEP_BOTH")
        private ConflictResolutionStrategy strategyOverride;

        @Option(names = {"--ai-index"}, description = "Enable Gemini AI semantic indexing and metadata extraction")
        private boolean aiIndex;

        @Override
        public Integer call() {
            try {
                SyncConfig config = loadConfig(configPath);
                if (strategyOverride != null) {
                    config.setConflictStrategy(strategyOverride.name());
                }
                if (aiIndex) {
                    config.setAiIndexingEnabled(true);
                }

                Path watchDir = Path.of(config.getWatchDirectory());
                Path stateDbPath = watchDir.resolve(".jcloud").resolve("state.db");

                try (SyncStateStore stateStore = new SyncStateStore(stateDbPath);
                     CloudStorageProvider cloudStorage = createCloudStorage(config, mockMode)) {

                    GeminiMetadataExtractor extractor = config.isAiIndexingEnabled() ? new GeminiMetadataExtractor() : null;
                    SyncEngine engine = new SyncEngine(
                        watchDir,
                        cloudStorage,
                        stateStore,
                        config.createIgnoreMatcher(),
                        config.getConflictResolutionStrategy(),
                        8,
                        new RetryExecutor(),
                        new DeadLetterQueue(watchDir.resolve(".jcloud").resolve("dead_letter.log")),
                        extractor
                    );

                    SyncResult result = engine.reconcile();
                    printSyncSummaryTable(config, result, mockMode, config.isAiIndexingEnabled());
                    return result.isSuccessful() ? 0 : 1;
                }
            } catch (Exception e) {
                System.err.printf("[ERROR] Sync failed: %s%n", e.getMessage());
                log.error("Sync error", e);
                return 1;
            }
        }
    }

    // =========================================================================
    // Subcommand: watch
    // =========================================================================
    @Command(name = "watch", mixinStandardHelpOptions = true, description = "Runs the continuous WatchService daemon with graceful shutdown hook")
    public static class WatchCommand implements Callable<Integer> {

        @Option(names = {"-c", "--config"}, description = "Path to config YAML", defaultValue = "config.yaml")
        private Path configPath;

        @Option(names = {"-m", "--mock"}, description = "Run against local mock cloud bucket (offline mode)")
        private boolean mockMode;

        @Option(names = {"-s", "--strategy"}, description = "Conflict resolution strategy: LOCAL_WINS, REMOTE_WINS, KEEP_BOTH")
        private ConflictResolutionStrategy strategyOverride;

        @Option(names = {"--ai-index"}, description = "Enable Gemini AI semantic indexing and metadata extraction")
        private boolean aiIndex;

        @Override
        public Integer call() {
            try {
                SyncConfig config = loadConfig(configPath);
                if (strategyOverride != null) {
                    config.setConflictStrategy(strategyOverride.name());
                }
                if (aiIndex) {
                    config.setAiIndexingEnabled(true);
                }

                Path watchDir = Path.of(config.getWatchDirectory());
                if (!Files.exists(watchDir)) {
                    Files.createDirectories(watchDir);
                }

                Path stateDbPath = watchDir.resolve(".jcloud").resolve("state.db");
                SyncStateStore stateStore = new SyncStateStore(stateDbPath);
                CloudStorageProvider cloudStorage = createCloudStorage(config, mockMode);

                GeminiMetadataExtractor extractor = config.isAiIndexingEnabled() ? new GeminiMetadataExtractor() : null;
                SyncEngine engine = new SyncEngine(
                    watchDir,
                    cloudStorage,
                    stateStore,
                    config.createIgnoreMatcher(),
                    config.getConflictResolutionStrategy(),
                    8,
                    new RetryExecutor(),
                    new DeadLetterQueue(watchDir.resolve(".jcloud").resolve("dead_letter.log")),
                    extractor
                );

                System.out.println("[INFO] Performing initial bidirectional reconciliation...");
                SyncResult initialResult = engine.reconcile();
                System.out.printf("[INFO] Initial sync completed (Uploaded: %d, Downloaded: %d, Conflicts: %d)%n",
                    initialResult.uploadedCount(), initialResult.downloadedCount(), initialResult.conflictsResolvedCount());

                DirectoryWatcher watcher = new DirectoryWatcher(config, engine::handleLocalChange);
                watcher.start();

                System.out.println("================================================================================");
                System.out.printf("  JCloudSync Watcher Daemon Active%n");
                System.out.printf("  Watching Directory: %s%n", watchDir.toAbsolutePath().normalize());
                System.out.printf("  Storage Target:     %s%n", mockMode ? "LocalMockBucket (offline)" : "s3://" + config.getS3Bucket());
                System.out.printf("  AI Indexing:        %s%n", config.isAiIndexingEnabled() ? "ENABLED (Gemini)" : "DISABLED");
                System.out.printf("  Debounce Window:    %d ms%n", config.getDebounceMs());
                System.out.printf("  Conflict Strategy:  %s%n", config.getConflictStrategy());
                System.out.println("  Press Ctrl+C to terminate cleanly.");
                System.out.println("================================================================================");

                CountDownLatch shutdownLatch = new CountDownLatch(1);

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("\n[INFO] Graceful shutdown initiated...");
                    try {
                        watcher.close();
                        cloudStorage.close();
                        stateStore.close();
                        System.out.println("[INFO] JCloudSync Watcher stopped cleanly.");
                    } catch (Exception e) {
                        System.err.printf("[WARN] Error during shutdown: %s%n", e.getMessage());
                    } finally {
                        shutdownLatch.countDown();
                    }
                }, "jcloud-shutdown-hook"));

                shutdownLatch.await();
                return 0;
            } catch (Exception e) {
                System.err.printf("[ERROR] Watcher daemon failure: %s%n", e.getMessage());
                log.error("Watcher daemon failure", e);
                return 1;
            }
        }
    }

    // =========================================================================
    // Subcommand: status
    // =========================================================================
    @Command(name = "status", mixinStandardHelpOptions = true, description = "Displays tracked files, sync state, and metrics")
    public static class StatusCommand implements Callable<Integer> {

        @Option(names = {"-c", "--config"}, description = "Path to config YAML", defaultValue = "config.yaml")
        private Path configPath;

        @Option(names = {"-m", "--mock"}, description = "Run against local mock cloud bucket (offline mode)")
        private boolean mockMode;

        @Override
        public Integer call() {
            try {
                SyncConfig config = loadConfig(configPath);
                Path watchDir = Path.of(config.getWatchDirectory());
                Path stateDbPath = watchDir.resolve(".jcloud").resolve("state.db");

                if (!Files.exists(stateDbPath)) {
                    System.out.println("[INFO] No active sync state found. Run 'jcloudsync sync' to initialize tracking.");
                    return 0;
                }

                try (SyncStateStore stateStore = new SyncStateStore(stateDbPath)) {
                    List<FileState> allFiles = stateStore.getAll();
                    long syncedCount = allFiles.stream().filter(f -> f.syncStatus() == SyncStatus.SYNCED).count();
                    long pendingCount = allFiles.stream().filter(f -> f.syncStatus() == SyncStatus.PENDING).count();
                    long conflictCount = allFiles.stream().filter(f -> f.syncStatus() == SyncStatus.CONFLICT).count();

                    System.out.println("================================================================================");
                    System.out.println("                            JCLOUDSYNC STATUS                                   ");
                    System.out.println("================================================================================");
                    System.out.printf("  Configuration:%n");
                    System.out.printf("    Watch Directory:   %s%n", watchDir.toAbsolutePath().normalize());
                    System.out.printf("    Cloud Target:      %s%n", mockMode ? "LocalMockBucket (offline)" : "s3://" + config.getS3Bucket());
                    System.out.printf("    Region:            %s%n", config.getS3Region());
                    if (config.getS3EndpointOverride() != null && !config.getS3EndpointOverride().isBlank()) {
                        System.out.printf("    Endpoint Override: %s%n", config.getS3EndpointOverride());
                    }
                    System.out.printf("    Conflict Strategy: %s%n", config.getConflictStrategy());
                    System.out.printf("    Debounce Window:   %d ms%n", config.getDebounceMs());
                    System.out.printf("%n  Metrics:%n");
                    System.out.printf("    Total Tracked:     %d files%n", allFiles.size());
                    System.out.printf("    Synced:            %d files%n", syncedCount);
                    System.out.printf("    Pending:           %d files%n", pendingCount);
                    System.out.printf("    Conflicts:         %d files%n", conflictCount);

                    System.out.println("\n  Tracked Files:");
                    System.out.println("  ------------------------------------------------------------------------------");
                    System.out.printf("  %-10s %-10s %-30s %-16s%n", "STATUS", "SIZE", "RELATIVE PATH", "SHA-256 (PREFIX)");
                    System.out.println("  ------------------------------------------------------------------------------");

                    if (allFiles.isEmpty()) {
                        System.out.println("  (No files currently tracked)");
                    } else {
                        for (FileState fs : allFiles) {
                            String prefixHash = fs.sha256Hash().length() >= 12 ? fs.sha256Hash().substring(0, 12) + "..." : fs.sha256Hash();
                            String sizeStr = formatFileSize(fs.fileSize());
                            System.out.printf("  %-10s %-10s %-30s %-16s%n",
                                fs.syncStatus(), sizeStr, truncatePath(fs.relativePath(), 30), prefixHash);
                        }
                    }
                    System.out.println("================================================================================");
                    return 0;
                }
            } catch (Exception e) {
                System.err.printf("[ERROR] Failed to query status: %s%n", e.getMessage());
                return 1;
            }
        }
    }

    // =========================================================================
    // Subcommand: search
    // =========================================================================
    @Command(name = "search", mixinStandardHelpOptions = true, description = "Searches synced documents by semantic tags/keywords and prints matches")
    public static class SearchCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Search query or semantic keyword tag")
        private String query;

        @Option(names = {"-c", "--config"}, description = "Path to config YAML", defaultValue = "config.yaml")
        private Path configPath;

        @Option(names = {"-m", "--mock"}, description = "Run against local mock cloud bucket (offline mode)")
        private boolean mockMode;

        @Override
        public Integer call() {
            try {
                SyncConfig config = loadConfig(configPath);
                Path watchDir = Path.of(config.getWatchDirectory());
                Path stateDbPath = watchDir.resolve(".jcloud").resolve("state.db");

                if (!Files.exists(stateDbPath)) {
                    System.err.println("[ERROR] No sync database found. Run 'jcloudsync sync' first.");
                    return 1;
                }

                String bucket = config.getS3Bucket() != null ? config.getS3Bucket() : "jcloud-sync";
                String cloudPrefix = mockMode ? "mock://" + bucket + "/" : "s3://" + bucket + "/";

                try (SyncStateStore stateStore = new SyncStateStore(stateDbPath)) {
                    List<FileState> results = stateStore.search(query);

                    System.out.println("================================================================================");
                    System.out.println("                         JCLOUDSYNC SEMANTIC SEARCH                             ");
                    System.out.println("================================================================================");
                    System.out.printf("  Query: \"%s\" | Target: %s%n", query, cloudPrefix);
                    System.out.println("--------------------------------------------------------------------------------");

                    if (results.isEmpty()) {
                        System.out.println("  No matching documents found.");
                    } else {
                        int index = 1;
                        for (FileState fs : results) {
                            String cloudUri = cloudPrefix + fs.relativePath();
                            String tagsDisplay = (fs.tags() != null && !fs.tags().isEmpty())
                                ? "[" + String.join(", ", fs.tags()) + "]"
                                : "[]";
                            String summaryDisplay = (fs.summary() != null && !fs.summary().isBlank())
                                ? fs.summary()
                                : "(No AI summary generated)";

                            System.out.printf("  [%d] %s%n", index++, fs.relativePath());
                            System.out.printf("      Cloud URI: %s%n", cloudUri);
                            System.out.printf("      Status:    %s (%s)%n", fs.syncStatus(), formatFileSize(fs.fileSize()));
                            System.out.printf("      Tags:      %s%n", tagsDisplay);
                            System.out.printf("      Summary:   %s%n", summaryDisplay);
                            System.out.println("--------------------------------------------------------------------------------");
                        }
                    }
                    System.out.printf("  Total Matches: %d%n", results.size());
                    System.out.println("================================================================================");
                    return 0;
                }
            } catch (Exception e) {
                System.err.printf("[ERROR] Search failed: %s%n", e.getMessage());
                return 1;
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private static SyncConfig loadConfig(Path configPath) throws IOException {
        if (Files.exists(configPath)) {
            return SyncConfig.load(configPath);
        }
        return new SyncConfig();
    }

    private static CloudStorageProvider createCloudStorage(SyncConfig config, boolean mockMode) throws IOException {
        if (mockMode) {
            Path mockDir = Path.of("./mock-cloud-bucket");
            return new LocalMockStorageProvider(mockDir);
        }
        return new S3StorageProvider(config);
    }

    private static void printSyncSummaryTable(SyncConfig config, SyncResult result, boolean mockMode, boolean aiIndexed) {
        System.out.println("================================================================================");
        System.out.println("                     JCLOUDSYNC RECONCILIATION SUMMARY                          ");
        System.out.println("================================================================================");
        System.out.printf("  Target: %s | Strategy: %s | AI Indexing: %s%n",
            mockMode ? "LocalMockBucket (offline)" : "s3://" + config.getS3Bucket(),
            config.getConflictStrategy(),
            aiIndexed ? "ENABLED (Gemini)" : "DISABLED");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("  %-22s %-8s %-45s%n", "CATEGORY", "COUNT", "DETAILS / FILES");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("  %-22s %-8d %-45s%n", "Uploaded (Local)", result.uploadedCount(), formatFileList(result.uploaded()));
        System.out.printf("  %-22s %-8d %-45s%n", "Downloaded (Remote)", result.downloadedCount(), formatFileList(result.downloaded()));
        System.out.printf("  %-22s %-8d %-45s%n", "Skipped (In Sync)", result.skippedCount(), formatFileList(result.skipped()));
        System.out.printf("  %-22s %-8d %-45s%n", "Conflicted (Resolved)", result.conflictsResolvedCount(), formatFileList(result.conflicts()));
        System.out.printf("  %-22s %-8d %-45s%n", "Deleted (Local)", result.deletedLocalCount(), formatFileList(result.deletedLocal()));
        System.out.printf("  %-22s %-8d %-45s%n", "Deleted (Remote)", result.deletedRemoteCount(), formatFileList(result.deletedRemote()));
        System.out.printf("  %-22s %-8d %-45s%n", "Errors / DLQ", result.errors().size(), formatFileList(result.errors()));
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("  Reconciliation Status: %s (Total Processed: %d)%n",
            result.isSuccessful() ? "SUCCESS" : "FAILED_WITH_ERRORS", result.totalProcessed());
        System.out.println("================================================================================");
    }

    private static String formatFileList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "-";
        }
        if (list.size() <= 2) {
            return String.join(", ", list);
        }
        return list.get(0) + ", " + list.get(1) + " (+" + (list.size() - 2) + " more)";
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String truncatePath(String path, int maxLen) {
        if (path.length() <= maxLen) return path;
        return "..." + path.substring(path.length() - maxLen + 3);
    }
}
