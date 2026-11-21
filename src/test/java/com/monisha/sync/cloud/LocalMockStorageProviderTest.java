package com.monisha.sync.cloud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LocalMockStorageProviderTest {

    @Test
    void testUploadDownloadDelete(@TempDir Path tempDir) throws IOException {
        Path mockRoot = tempDir.resolve("mock-bucket");
        LocalMockStorageProvider provider = new LocalMockStorageProvider(mockRoot);

        // Prepare local file
        Path localFile = tempDir.resolve("sample.txt");
        Files.writeString(localFile, "Mock cloud payload");

        // Upload
        RemoteObjectMetadata meta = provider.uploadFile(localFile, "data/sample.txt");
        assertNotNull(meta);
        assertEquals("data/sample.txt", meta.key());
        assertNotNull(meta.sha256());
        assertEquals(Files.size(localFile), meta.sizeBytes());

        // Exists check
        assertTrue(provider.exists("data/sample.txt"));

        // Get metadata
        Optional<RemoteObjectMetadata> fetched = provider.getObjectMetadata("data/sample.txt");
        assertTrue(fetched.isPresent());
        assertEquals(meta.sha256(), fetched.get().sha256());

        // Download
        Path downloadDest = tempDir.resolve("downloaded.txt");
        provider.downloadFile("data/sample.txt", downloadDest);
        assertTrue(Files.exists(downloadDest));
        assertEquals("Mock cloud payload", Files.readString(downloadDest));

        // List
        List<RemoteObjectMetadata> list = provider.listRemoteObjects();
        assertEquals(1, list.size());
        assertEquals("data/sample.txt", list.get(0).key());

        // Delete
        provider.deleteFile("data/sample.txt");
        assertFalse(provider.exists("data/sample.txt"));
        assertEquals(0, provider.listRemoteObjects().size());
        assertThrows(FileNotFoundException.class, () -> provider.downloadFile("data/sample.txt", downloadDest));
    }
}
