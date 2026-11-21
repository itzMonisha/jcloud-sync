package com.monisha.sync.watcher;

import com.monisha.sync.config.IgnoreMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryWatcherTest {

    @Test
    void testDebounceCoalescing(@TempDir Path tempDir) throws Exception {
        List<FileChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        // 300ms debounce window
        long debounceMs = 300;
        IgnoreMatcher ignoreMatcher = new IgnoreMatcher(tempDir, List.of("*.tmp"));

        try (DirectoryWatcher watcher = new DirectoryWatcher(tempDir, debounceMs, ignoreMatcher, event -> {
            events.add(event);
            latch.countDown();
        })) {
            watcher.start();

            // Simulate rapid editor saves: create and write to the same file 4 times in quick succession (< 50ms intervals)
            Path targetFile = tempDir.resolve("rapid_edit.txt");
            Files.writeString(targetFile, "Version 1\n");
            Thread.sleep(40);
            Files.writeString(targetFile, "Version 2\n");
            Thread.sleep(40);
            Files.writeString(targetFile, "Version 3\n");
            Thread.sleep(40);
            Files.writeString(targetFile, "Version 4 Final\n");

            // Wait for debounce window (300ms) to elapse + margin
            boolean received = latch.await(2000, TimeUnit.MILLISECONDS);
            assertTrue(received, "Should receive a debounced event");

            // Additional quiet period to ensure no extra duplicate events are fired
            Thread.sleep(debounceMs + 200);

            // Because all writes occurred within the 300ms debounce sliding window,
            // they must coalesce into a single event!
            assertEquals(1, events.size(), "Rapid writes must coalesce into exactly 1 event");
            assertEquals("rapid_edit.txt", events.get(0).relativePath().toString().replace('\\', '/'));
        }
    }

    @Test
    void testIgnorePatternFiltering(@TempDir Path tempDir) throws Exception {
        List<FileChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch validFileLatch = new CountDownLatch(1);

        List<String> patterns = List.of("*.tmp", "*.swp", ".git/**", ".DS_Store");
        IgnoreMatcher ignoreMatcher = new IgnoreMatcher(tempDir, patterns);

        try (DirectoryWatcher watcher = new DirectoryWatcher(tempDir, 200, ignoreMatcher, event -> {
            events.add(event);
            if (event.relativePath().toString().equals("valid.txt")) {
                validFileLatch.countDown();
            }
        })) {
            watcher.start();

            // Create ignored files
            Files.writeString(tempDir.resolve("test.tmp"), "temp data");
            Files.writeString(tempDir.resolve("editor.swp"), "swap data");
            Files.writeString(tempDir.resolve(".DS_Store"), "apple junk");

            // Create .git subfolder and files inside
            Path gitDir = tempDir.resolve(".git");
            Files.createDirectories(gitDir);
            Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main");

            // Create one valid file
            Files.writeString(tempDir.resolve("valid.txt"), "important data");

            boolean received = validFileLatch.await(2000, TimeUnit.MILLISECONDS);
            assertTrue(received, "Valid file must trigger event");

            // Let debounce settle
            Thread.sleep(400);

            // Verify ONLY the valid file triggered an event
            for (FileChangeEvent event : events) {
                String rel = event.relativePath().toString().replace('\\', '/');
                assertFalse(rel.endsWith(".tmp"), "Ignored *.tmp must not produce events");
                assertFalse(rel.endsWith(".swp"), "Ignored *.swp must not produce events");
                assertFalse(rel.contains(".git"), "Ignored .git must not produce events");
                assertFalse(rel.endsWith(".DS_Store"), "Ignored .DS_Store must not produce events");
            }

            assertEquals(1, events.size());
            assertEquals("valid.txt", events.get(0).relativePath().toString().replace('\\', '/'));
        }
    }

    @Test
    void testEphemeralFileCreatedAndDeletedWithinDebounce(@TempDir Path tempDir) throws Exception {
        List<FileChangeEvent> events = new CopyOnWriteArrayList<>();
        IgnoreMatcher ignoreMatcher = new IgnoreMatcher(tempDir, Collections.emptyList());

        // 500ms debounce
        try (DirectoryWatcher watcher = new DirectoryWatcher(tempDir, 500, ignoreMatcher, events::add)) {
            watcher.start();

            // Create a temporary file and delete it immediately before debounce window expires
            Path ephemeralFile = tempDir.resolve("ephemeral.dat");
            Files.writeString(ephemeralFile, "ephemeral content");
            Thread.sleep(50);
            Files.delete(ephemeralFile);

            // Wait for debounce window to pass
            Thread.sleep(700);

            // Because file was created and deleted within debounce window, it should be canceled completely
            assertEquals(0, events.size(), "Ephemeral file created and deleted before debounce must produce 0 events");
        }
    }

    @Test
    void testRecursiveDirectoryWatcher(@TempDir Path tempDir) throws Exception {
        List<FileChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (DirectoryWatcher watcher = new DirectoryWatcher(tempDir, 200, null, event -> {
            if (event.relativePath().toString().replace('\\', '/').equals("sub/folder/nested.txt")) {
                events.add(event);
                latch.countDown();
            }
        })) {
            watcher.start();

            // Create nested directory dynamically with short pause for OS registration
            Path subDir = tempDir.resolve("sub");
            Files.createDirectories(subDir);
            Thread.sleep(150);
            Path nestedDir = subDir.resolve("folder");
            Files.createDirectories(nestedDir);
            Thread.sleep(200);

            // Create file in nested directory
            Files.writeString(nestedDir.resolve("nested.txt"), "nested file content");

            boolean received = latch.await(5000, TimeUnit.MILLISECONDS);
            assertTrue(received, "Should detect file created in dynamically registered subdirectory");
            assertEquals(1, events.size());
        }
    }
}
