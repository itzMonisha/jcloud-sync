package com.monisha.sync.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SyncStateStoreTest {

    private SyncStateStore store;

    @BeforeEach
    void setUp() throws SQLException {
        // Fast, isolated in-memory SQLite store for tests
        store = new SyncStateStore("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testUpsertAndRetrieve() throws SQLException {
        FileState state = new FileState(
            "documents/report.pdf",
            "abc123hash",
            1700000000000L,
            SyncStatus.PENDING,
            1024L
        );

        store.upsert(state);

        Optional<FileState> retrieved = store.get("documents/report.pdf");
        assertTrue(retrieved.isPresent());
        FileState found = retrieved.get();
        assertEquals("documents/report.pdf", found.relativePath());
        assertEquals("abc123hash", found.sha256Hash());
        assertEquals(1700000000000L, found.lastModifiedEpoch());
        assertEquals(SyncStatus.PENDING, found.syncStatus());
        assertEquals(1024L, found.fileSize());
    }

    @Test
    void testUpsertUpdatesExistingRecord() throws SQLException {
        FileState state1 = new FileState("app.log", "hash1", 1000L, SyncStatus.PENDING, 50L);
        store.upsert(state1);

        // Update same relative path with new hash and status
        FileState state2 = new FileState("app.log", "hash2", 2000L, SyncStatus.SYNCED, 120L);
        store.upsert(state2);

        assertEquals(1, store.count());
        Optional<FileState> retrieved = store.get("app.log");
        assertTrue(retrieved.isPresent());
        assertEquals("hash2", retrieved.get().sha256Hash());
        assertEquals(2000L, retrieved.get().lastModifiedEpoch());
        assertEquals(SyncStatus.SYNCED, retrieved.get().syncStatus());
        assertEquals(120L, retrieved.get().fileSize());
    }

    @Test
    void testGetByStatus() throws SQLException {
        store.upsert(new FileState("file1.txt", "h1", 100L, SyncStatus.SYNCED));
        store.upsert(new FileState("file2.txt", "h2", 100L, SyncStatus.PENDING));
        store.upsert(new FileState("file3.txt", "h3", 100L, SyncStatus.PENDING));
        store.upsert(new FileState("file4.txt", "h4", 100L, SyncStatus.CONFLICT));

        List<FileState> pending = store.getByStatus(SyncStatus.PENDING);
        assertEquals(2, pending.size());

        List<FileState> synced = store.getByStatus(SyncStatus.SYNCED);
        assertEquals(1, synced.size());
        assertEquals("file1.txt", synced.get(0).relativePath());

        List<FileState> conflicts = store.getByStatus(SyncStatus.CONFLICT);
        assertEquals(1, conflicts.size());
        assertEquals("file4.txt", conflicts.get(0).relativePath());
    }

    @Test
    void testUpdateStatusAndDelete() throws SQLException {
        store.upsert(new FileState("file.txt", "hash", 100L, SyncStatus.PENDING));

        boolean updated = store.updateStatus("file.txt", SyncStatus.SYNCED);
        assertTrue(updated);
        assertEquals(SyncStatus.SYNCED, store.get("file.txt").orElseThrow().syncStatus());

        boolean deleted = store.delete("file.txt");
        assertTrue(deleted);
        assertEquals(0, store.count());
        assertTrue(store.get("file.txt").isEmpty());
    }

    @Test
    void testFileBasedDatabase(@TempDir Path tempDir) throws SQLException {
        Path dbFile = tempDir.resolve(".jcloud").resolve("state.db");
        try (SyncStateStore fileStore = new SyncStateStore(dbFile)) {
            fileStore.upsert(new FileState("persisted.txt", "hash999", 500L, SyncStatus.SYNCED));
            assertEquals(1, fileStore.count());
        }

        // Re-open existing database file
        try (SyncStateStore reOpenedStore = new SyncStateStore(dbFile)) {
            assertEquals(1, reOpenedStore.count());
            assertTrue(reOpenedStore.get("persisted.txt").isPresent());
        }
    }

    @Test
    void testSearchByPathSummaryAndTags() throws SQLException {
        store.upsert(new FileState(
            "docs/architecture.md", "hash1", 100L, SyncStatus.SYNCED, 2048L,
            "Design document outlining Java 21 virtual threads and sync engine.",
            List.of("architecture", "virtual-threads", "java21")
        ));

        store.upsert(new FileState(
            "images/diagram.png", "hash2", 100L, SyncStatus.SYNCED, 1024L,
            "Architecture sequence diagram for conflict resolution.",
            List.of("diagram", "uml")
        ));

        store.upsert(new FileState(
            "notes.txt", "hash3", 100L, SyncStatus.PENDING, 512L,
            "Meeting notes from team sprint planning.",
            List.of("meeting", "planning")
        ));

        // Search by semantic tag
        List<FileState> tagMatches = store.search("virtual-threads");
        assertEquals(1, tagMatches.size());
        assertEquals("docs/architecture.md", tagMatches.get(0).relativePath());

        // Search by summary keyword
        List<FileState> summaryMatches = store.search("sequence diagram");
        assertEquals(1, summaryMatches.size());
        assertEquals("images/diagram.png", summaryMatches.get(0).relativePath());

        // Search by relative path component
        List<FileState> pathMatches = store.search("notes");
        assertEquals(1, pathMatches.size());
        assertEquals("notes.txt", pathMatches.get(0).relativePath());

        // Search with no results
        List<FileState> emptyMatches = store.search("nonexistent-query-xyz");
        assertTrue(emptyMatches.isEmpty());
    }
}
