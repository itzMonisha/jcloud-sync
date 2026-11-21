package com.monisha.sync.engine;

import com.monisha.sync.cloud.LocalMockStorageProvider;
import com.monisha.sync.config.IgnoreMatcher;
import com.monisha.sync.hash.FileHasher;
import com.monisha.sync.state.FileState;
import com.monisha.sync.state.SyncStateStore;
import com.monisha.sync.state.SyncStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SyncEngineConflictTest {

    private SyncStateStore stateStore;

    @BeforeEach
    void setUp() throws SQLException {
        stateStore = new SyncStateStore("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void testBidirectionalNewFilesReconciled(@TempDir Path tempDir) throws IOException, SQLException {
        Path localDir = tempDir.resolve("local");
        Path remoteMockDir = tempDir.resolve("remote-bucket");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteMockDir);

        LocalMockStorageProvider cloudStorage = new LocalMockStorageProvider(remoteMockDir);
        IgnoreMatcher ignoreMatcher = new IgnoreMatcher(localDir, List.of("*.tmp"));

        SyncEngine engine = new SyncEngine(localDir, cloudStorage, stateStore, ignoreMatcher, ConflictResolutionStrategy.KEEP_BOTH);

        // 1. Create a new file locally
        Files.writeString(localDir.resolve("local_doc.txt"), "Local file content");

        // 2. Create a new file remotely
        Path remoteFile = tempDir.resolve("temp_remote.txt");
        Files.writeString(remoteFile, "Remote file content");
        cloudStorage.uploadFile(remoteFile, "remote_doc.txt");

        // 3. Reconcile
        SyncResult result = engine.reconcile();
        assertEquals(1, result.uploadedCount(), "local_doc.txt must be uploaded");
        assertEquals(1, result.downloadedCount(), "remote_doc.txt must be downloaded");
        assertEquals(0, result.conflictsResolvedCount());

        // Verify both sides now have both files
        assertTrue(cloudStorage.exists("local_doc.txt"));
        assertTrue(Files.exists(localDir.resolve("remote_doc.txt")));
        assertEquals("Remote file content", Files.readString(localDir.resolve("remote_doc.txt")));

        // Verify state store
        assertEquals(2, stateStore.count());
        assertEquals(SyncStatus.SYNCED, stateStore.get("local_doc.txt").orElseThrow().syncStatus());
        assertEquals(SyncStatus.SYNCED, stateStore.get("remote_doc.txt").orElseThrow().syncStatus());
    }

    @Test
    void testConflictResolutionLocalWins(@TempDir Path tempDir) throws IOException, SQLException {
        Path localDir = tempDir.resolve("local");
        Path remoteMockDir = tempDir.resolve("remote-bucket");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteMockDir);

        LocalMockStorageProvider cloudStorage = new LocalMockStorageProvider(remoteMockDir);
        SyncEngine engine = new SyncEngine(localDir, cloudStorage, stateStore, null, ConflictResolutionStrategy.LOCAL_WINS);

        // Establish an initial synced baseline
        String relPath = "notes.txt";
        Path localFile = localDir.resolve(relPath);
        Files.writeString(localFile, "Initial V1");
        cloudStorage.uploadFile(localFile, relPath);
        stateStore.upsert(new FileState(relPath, FileHasher.computeSha256(localFile), System.currentTimeMillis(), SyncStatus.SYNCED));

        // Induce concurrent modifications on both sides
        Files.writeString(localFile, "Local Version modified");
        Path tempRemote = tempDir.resolve("temp_remote_edit.txt");
        Files.writeString(tempRemote, "Remote Version modified");
        cloudStorage.uploadFile(tempRemote, relPath);

        // Reconcile with LOCAL_WINS
        SyncResult result = engine.reconcile();
        assertEquals(1, result.conflictsResolvedCount());
        assertTrue(result.conflicts().contains(relPath));

        // Remote must be overwritten with local copy
        Path downloaded = tempDir.resolve("check_remote.txt");
        cloudStorage.downloadFile(relPath, downloaded);
        assertEquals("Local Version modified", Files.readString(downloaded));
        assertEquals("Local Version modified", Files.readString(localFile));
    }

    @Test
    void testConflictResolutionRemoteWins(@TempDir Path tempDir) throws IOException, SQLException {
        Path localDir = tempDir.resolve("local");
        Path remoteMockDir = tempDir.resolve("remote-bucket");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteMockDir);

        LocalMockStorageProvider cloudStorage = new LocalMockStorageProvider(remoteMockDir);
        SyncEngine engine = new SyncEngine(localDir, cloudStorage, stateStore, null, ConflictResolutionStrategy.REMOTE_WINS);

        // Baseline synced state
        String relPath = "shared.txt";
        Path localFile = localDir.resolve(relPath);
        Files.writeString(localFile, "Initial V1");
        cloudStorage.uploadFile(localFile, relPath);
        stateStore.upsert(new FileState(relPath, FileHasher.computeSha256(localFile), System.currentTimeMillis(), SyncStatus.SYNCED));

        // Concurrent modifications
        Files.writeString(localFile, "Local Modified Text");
        Path tempRemote = tempDir.resolve("temp_remote_shared.txt");
        Files.writeString(tempRemote, "Remote Overriding Text");
        cloudStorage.uploadFile(tempRemote, relPath);

        // Reconcile with REMOTE_WINS
        SyncResult result = engine.reconcile();
        assertEquals(1, result.conflictsResolvedCount());

        // Local file must be replaced by remote version
        assertEquals("Remote Overriding Text", Files.readString(localFile));
    }

    @Test
    void testConflictResolutionKeepBoth(@TempDir Path tempDir) throws IOException, SQLException {
        Path localDir = tempDir.resolve("local");
        Path remoteMockDir = tempDir.resolve("remote-bucket");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteMockDir);

        LocalMockStorageProvider cloudStorage = new LocalMockStorageProvider(remoteMockDir);
        SyncEngine engine = new SyncEngine(localDir, cloudStorage, stateStore, null, ConflictResolutionStrategy.KEEP_BOTH);

        // Baseline synced state
        String relPath = "doc.txt";
        Path localFile = localDir.resolve(relPath);
        Files.writeString(localFile, "Original baseline");
        cloudStorage.uploadFile(localFile, relPath);
        stateStore.upsert(new FileState(relPath, FileHasher.computeSha256(localFile), System.currentTimeMillis(), SyncStatus.SYNCED));

        // Concurrent conflicting modifications
        Files.writeString(localFile, "Conflicting Local edits");
        Path tempRemote = tempDir.resolve("temp_remote_doc.txt");
        Files.writeString(tempRemote, "Conflicting Remote edits");
        cloudStorage.uploadFile(tempRemote, relPath);

        // Reconcile with KEEP_BOTH
        SyncResult result = engine.reconcile();
        assertEquals(1, result.conflictsResolvedCount());

        // Main file has the remote version
        assertEquals("Conflicting Remote edits", Files.readString(localFile));

        // A conflict file preserving the local version must exist!
        try (Stream<Path> files = Files.list(localDir)) {
            List<Path> conflictFiles = files.filter(p -> p.getFileName().toString().contains(".conflict.")).toList();
            assertEquals(1, conflictFiles.size(), "Exactly one .conflict file must be created");
            Path conflictFile = conflictFiles.get(0);
            assertEquals("Conflicting Local edits", Files.readString(conflictFile));
        }

        // Verify conflict state recorded
        List<FileState> conflictStates = stateStore.getByStatus(SyncStatus.CONFLICT);
        assertEquals(1, conflictStates.size());
    }

    @Test
    void testIgnoreFilteringDuringReconcile(@TempDir Path tempDir) throws IOException, SQLException {
        Path localDir = tempDir.resolve("local");
        Path remoteMockDir = tempDir.resolve("remote-bucket");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteMockDir);

        LocalMockStorageProvider cloudStorage = new LocalMockStorageProvider(remoteMockDir);
        IgnoreMatcher ignoreMatcher = new IgnoreMatcher(localDir, List.of("*.tmp", ".git/**"));
        SyncEngine engine = new SyncEngine(localDir, cloudStorage, stateStore, ignoreMatcher, ConflictResolutionStrategy.KEEP_BOTH);

        // Create ignored files
        Files.writeString(localDir.resolve("scratch.tmp"), "should not sync");
        Path gitDir = localDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), "git config");

        // Create normal file
        Files.writeString(localDir.resolve("keep.txt"), "valid file");

        SyncResult result = engine.reconcile();
        assertEquals(1, result.uploadedCount());
        assertTrue(cloudStorage.exists("keep.txt"));
        assertFalse(cloudStorage.exists("scratch.tmp"));
        assertFalse(cloudStorage.exists(".git/config"));
    }
}
