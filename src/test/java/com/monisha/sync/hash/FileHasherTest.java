package com.monisha.sync.hash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class FileHasherTest {

    @Test
    void testComputeSha256OfString() {
        // Standard SHA-256 of empty string is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String emptyHash = FileHasher.computeSha256("");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash);

        // SHA-256 of "hello world"
        String helloHash = FileHasher.computeSha256("hello world");
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", helloHash);
    }

    @Test
    void testComputeSha256OfFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "hello world");

        String hash = FileHasher.computeSha256(file);
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }

    @Test
    void testDetectContentChangeVsMetadataTouch(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("touch_test.txt");
        Files.writeString(file, "Initial content for test");

        FileFingerprint fp1 = FileHasher.computeFingerprint(file);
        assertNotNull(fp1);
        assertEquals(file.toFile().length(), fp1.sizeBytes());

        // 1. Metadata-only touch (change last modified time, keep content unchanged)
        // Sleep slightly to guarantee different timestamp resolution
        Thread.sleep(100);
        long newTimestamp = System.currentTimeMillis() + 60_000;
        Files.setLastModifiedTime(file, FileTime.fromMillis(newTimestamp));

        FileFingerprint fp2 = FileHasher.computeFingerprint(file);

        // Hashes should match, but timestamps differ
        assertEquals(fp1.sha256Hash(), fp2.sha256Hash());
        assertNotEquals(fp1.lastModifiedEpoch(), fp2.lastModifiedEpoch());
        assertTrue(FileHasher.isMetadataOnlyTouch(fp2, fp1), "Should detect metadata-only touch");
        assertFalse(FileHasher.hasContentChanged(fp2, fp1), "Content should be reported unchanged");
        assertFalse(FileHasher.hasContentChanged(file, fp1.sha256Hash()));

        // 2. Actual content change
        Files.writeString(file, "Completely different content now");
        FileFingerprint fp3 = FileHasher.computeFingerprint(file);

        assertNotEquals(fp1.sha256Hash(), fp3.sha256Hash());
        assertFalse(FileHasher.isMetadataOnlyTouch(fp3, fp2), "Content change is not metadata-only touch");
        assertTrue(FileHasher.hasContentChanged(fp3, fp2), "Should detect content change between fingerprints");
        assertTrue(FileHasher.hasContentChanged(file, fp2.sha256Hash()));
    }
}
