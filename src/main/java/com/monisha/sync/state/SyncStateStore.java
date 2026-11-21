package com.monisha.sync.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Local state store backed by SQLite to track file fingerprints, last modified timestamps,
 * sync statuses, and AI semantic metadata.
 */
public class SyncStateStore implements AutoCloseable {

    private final String jdbcUrl;
    private final Connection connection;

    public SyncStateStore(Path dbPath) throws SQLException {
        this("jdbc:sqlite:" + ensureParentExists(dbPath).toAbsolutePath().normalize());
    }

    public SyncStateStore(String jdbcUrl) throws SQLException {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.connection = DriverManager.getConnection(jdbcUrl);
        initSchema();
    }

    private static Path ensureParentExists(Path dbPath) {
        Objects.requireNonNull(dbPath, "dbPath must not be null");
        Path parent = dbPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create parent directories for SQLite DB: " + parent, e);
            }
        }
        return dbPath;
    }

    private synchronized void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            if (!jdbcUrl.contains(":memory:")) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
            }
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_states (
                    relative_path TEXT PRIMARY KEY,
                    sha256_hash TEXT NOT NULL,
                    last_modified_epoch INTEGER NOT NULL,
                    sync_status TEXT NOT NULL,
                    file_size INTEGER DEFAULT 0,
                    summary TEXT,
                    tags TEXT,
                    updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                );
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_states_status ON file_states(sync_status);");

            // Safe schema migrations for existing DB instances
            try {
                stmt.execute("ALTER TABLE file_states ADD COLUMN summary TEXT;");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE file_states ADD COLUMN tags TEXT;");
            } catch (SQLException ignored) {}
        }
    }

    /**
     * Inserts or updates the file state and AI metadata.
     *
     * @param state file state record
     */
    public synchronized void upsert(FileState state) throws SQLException {
        Objects.requireNonNull(state, "state must not be null");
        String sql = """
            INSERT INTO file_states (relative_path, sha256_hash, last_modified_epoch, sync_status, file_size, summary, tags, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))
            ON CONFLICT(relative_path) DO UPDATE SET
                sha256_hash = excluded.sha256_hash,
                last_modified_epoch = excluded.last_modified_epoch,
                sync_status = excluded.sync_status,
                file_size = excluded.file_size,
                summary = excluded.summary,
                tags = excluded.tags,
                updated_at = strftime('%s', 'now');
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, state.relativePath());
            ps.setString(2, state.sha256Hash());
            ps.setLong(3, state.lastModifiedEpoch());
            ps.setString(4, state.syncStatus().name());
            ps.setLong(5, state.fileSize());
            ps.setString(6, state.summary());
            ps.setString(7, state.tags() != null ? String.join(",", state.tags()) : "");
            ps.executeUpdate();
        }
    }

    /**
     * Retrieves the file state for a given relative path.
     *
     * @param relativePath relative file path
     * @return Optional containing the state if present
     */
    public synchronized Optional<FileState> get(String relativePath) throws SQLException {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        String sql = "SELECT relative_path, sha256_hash, last_modified_epoch, sync_status, file_size, summary, tags FROM file_states WHERE relative_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Retrieves all recorded file states.
     */
    public synchronized List<FileState> getAll() throws SQLException {
        String sql = "SELECT relative_path, sha256_hash, last_modified_epoch, sync_status, file_size, summary, tags FROM file_states ORDER BY relative_path ASC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<FileState> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        }
    }

    /**
     * Retrieves all file states matching a specific sync status.
     *
     * @param status status to filter by
     */
    public synchronized List<FileState> getByStatus(SyncStatus status) throws SQLException {
        Objects.requireNonNull(status, "status must not be null");
        String sql = "SELECT relative_path, sha256_hash, last_modified_epoch, sync_status, file_size, summary, tags FROM file_states WHERE sync_status = ? ORDER BY relative_path ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<FileState> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        }
    }

    /**
     * Searches indexed files by relative path, AI summary, or semantic keyword tags.
     *
     * @param query search query
     * @return list of matching FileState records
     */
    public synchronized List<FileState> search(String query) throws SQLException {
        if (query == null || query.isBlank()) {
            return getAll();
        }
        String pattern = "%" + query.trim().toLowerCase() + "%";
        String sql = """
            SELECT relative_path, sha256_hash, last_modified_epoch, sync_status, file_size, summary, tags
            FROM file_states
            WHERE lower(relative_path) LIKE ? OR lower(summary) LIKE ? OR lower(tags) LIKE ?
            ORDER BY relative_path ASC
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                List<FileState> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        }
    }

    /**
     * Updates the sync status of a recorded file.
     *
     * @param relativePath relative path
     * @param newStatus new sync status
     * @return true if updated, false if not found
     */
    public synchronized boolean updateStatus(String relativePath, SyncStatus newStatus) throws SQLException {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        String sql = "UPDATE file_states SET sync_status = ?, updated_at = strftime('%s', 'now') WHERE relative_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, newStatus.name());
            ps.setString(2, relativePath);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Deletes a file record by relative path.
     *
     * @param relativePath relative path
     * @return true if deleted, false if not present
     */
    public synchronized boolean delete(String relativePath) throws SQLException {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        String sql = "DELETE FROM file_states WHERE relative_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, relativePath);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized int count() throws SQLException {
        String sql = "SELECT COUNT(*) FROM file_states";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized void clear() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM file_states");
        }
    }

    private FileState mapRow(ResultSet rs) throws SQLException {
        String tagsRaw = rs.getString("tags");
        List<String> tagsList = tagsRaw != null && !tagsRaw.isBlank()
            ? Arrays.stream(tagsRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
            : List.of();

        return new FileState(
            rs.getString("relative_path"),
            rs.getString("sha256_hash"),
            rs.getLong("last_modified_epoch"),
            SyncStatus.valueOf(rs.getString("sync_status")),
            rs.getLong("file_size"),
            rs.getString("summary"),
            tagsList
        );
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }
}
