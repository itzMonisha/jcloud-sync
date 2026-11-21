package com.monisha.sync.integration;

import com.monisha.sync.cloud.RemoteObjectMetadata;
import com.monisha.sync.cloud.S3StorageProvider;
import com.monisha.sync.engine.ConflictResolutionStrategy;
import com.monisha.sync.engine.SyncEngine;
import com.monisha.sync.engine.SyncResult;
import com.monisha.sync.state.SyncStateStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test against a real MinIO S3-compatible container.
 * Automatically runs in Docker-enabled CI environments and skips when Docker daemon is not active.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3SyncIntegrationTest {

    private static final String BUCKET_NAME = "jcloud-integration-test";

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z")
        .withUserName("minioadmin")
        .withPassword("minioadmin");

    private static S3Client s3Client;
    private static S3StorageProvider s3Provider;

    @BeforeAll
    static void setUp() {
        if (!minio.isRunning()) {
            return;
        }

        URI endpointUri = URI.create(minio.getS3URL());
        s3Client = S3Client.builder()
            .endpointOverride(endpointUri)
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())
            ))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .build();

        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET_NAME).build());
        s3Provider = new S3StorageProvider(BUCKET_NAME, s3Client);
    }

    @AfterAll
    static void tearDown() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @Test
    void testEndToEndUploadDownloadDeleteAgainstMinIO(@TempDir Path tempDir) throws IOException {
        if (!minio.isRunning()) return;

        Path localFile = tempDir.resolve("sample.txt");
        Files.writeString(localFile, "Real MinIO container payload integration test");

        // 1. Upload
        RemoteObjectMetadata uploadMeta = s3Provider.uploadFile(localFile, "integration/sample.txt");
        assertNotNull(uploadMeta);
        assertEquals("integration/sample.txt", uploadMeta.key());
        assertTrue(s3Provider.exists("integration/sample.txt"));

        // 2. Get Metadata
        Optional<RemoteObjectMetadata> meta = s3Provider.getObjectMetadata("integration/sample.txt");
        assertTrue(meta.isPresent());
        assertEquals(uploadMeta.sha256(), meta.get().sha256());

        // 3. Download
        Path downloaded = tempDir.resolve("downloaded.txt");
        s3Provider.downloadFile("integration/sample.txt", downloaded);
        assertTrue(Files.exists(downloaded));
        assertEquals("Real MinIO container payload integration test", Files.readString(downloaded));

        // 4. List
        List<RemoteObjectMetadata> objects = s3Provider.listRemoteObjects("integration");
        assertEquals(1, objects.size());

        // 5. Delete
        s3Provider.deleteFile("integration/sample.txt");
        assertFalse(s3Provider.exists("integration/sample.txt"));
    }

    @Test
    void testSyncEngineReconciliationAndConflictAgainstMinIO(@TempDir Path tempDir) throws IOException, SQLException {
        if (!minio.isRunning()) return;

        Path localWatchDir = tempDir.resolve("watch");
        Files.createDirectories(localWatchDir);

        Path stateDbPath = tempDir.resolve("state.db");
        try (SyncStateStore stateStore = new SyncStateStore(stateDbPath)) {
            SyncEngine engine = new SyncEngine(localWatchDir, s3Provider, stateStore, null, ConflictResolutionStrategy.KEEP_BOTH);

            // Create initial synced file
            Path noteFile = localWatchDir.resolve("notes.txt");
            Files.writeString(noteFile, "Version 1.0 Initial");

            SyncResult r1 = engine.reconcile();
            assertEquals(1, r1.uploadedCount());
            assertTrue(s3Provider.exists("notes.txt"));

            // Induce bidirectional conflict
            Files.writeString(noteFile, "Local Modified Content");
            Path remoteEdit = tempDir.resolve("remote_edit.txt");
            Files.writeString(remoteEdit, "Remote MinIO Modified Content");
            s3Provider.uploadFile(remoteEdit, "notes.txt");

            // Reconcile with KEEP_BOTH
            SyncResult r2 = engine.reconcile();
            assertEquals(1, r2.conflictsResolvedCount());

            // Original local file should now match the remote version
            assertEquals("Remote MinIO Modified Content", Files.readString(noteFile));

            // .conflict file should exist locally with local edits
            try (var stream = Files.list(localWatchDir)) {
                List<Path> conflicts = stream.filter(p -> p.getFileName().toString().contains(".conflict.")).toList();
                assertEquals(1, conflicts.size());
                assertEquals("Local Modified Content", Files.readString(conflicts.get(0)));
            }
        }
    }
}
