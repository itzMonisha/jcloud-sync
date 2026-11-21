package com.monisha.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainCliTest {

    @Test
    void testRootCommandPrintsReady() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            int exitCode = new CommandLine(new Main()).execute();
            assertEquals(0, exitCode);
            assertTrue(out.toString().contains("JCloudSync ready"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void testInitCommandGeneratesConfigFile(@TempDir Path tempDir) {
        Path targetConfig = tempDir.resolve("starter-config.yaml");
        int exitCode = new CommandLine(new Main()).execute("init", "-o", targetConfig.toString());
        assertEquals(0, exitCode);
        assertTrue(Files.exists(targetConfig));
    }

    @Test
    void testSyncOnceAndStatusCommandMockMode(@TempDir Path tempDir) throws Exception {
        Path watchDir = tempDir.resolve("local-watch");
        Files.createDirectories(watchDir);
        Files.writeString(watchDir.resolve("test_document.txt"), "Hello from CLI test");

        Path configPath = tempDir.resolve("test-config.yaml");
        String configYaml = String.format("""
            watchDirectory: "%s"
            s3Bucket: "test-cli-bucket"
            s3Region: "us-east-1"
            debounceMs: 100
            conflictStrategy: "KEEP_BOTH"
            ignorePatterns: [".jcloud/**"]
            """, watchDir.toString().replace('\\', '/'));
        Files.writeString(configPath, configYaml);

        // 1. Run sync --once --mock
        int syncExit = new CommandLine(new Main()).execute("sync", "--once", "--mock", "-c", configPath.toString());
        assertEquals(0, syncExit);

        // 2. Run status --mock
        ByteArrayOutputStream statusOut = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(statusOut));
            int statusExit = new CommandLine(new Main()).execute("status", "--mock", "-c", configPath.toString());
            assertEquals(0, statusExit);
            String output = statusOut.toString();
            assertTrue(output.contains("JCLOUDSYNC STATUS"));
            assertTrue(output.contains("test_document.txt"));
            assertTrue(output.contains("SYNCED"));
        } finally {
            System.setOut(originalOut);
        }

        // 3. Run search command
        ByteArrayOutputStream searchOut = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(searchOut));
            int searchExit = new CommandLine(new Main()).execute("search", "test_document", "--mock", "-c", configPath.toString());
            assertEquals(0, searchExit);
            String searchOutput = searchOut.toString();
            assertTrue(searchOutput.contains("JCLOUDSYNC SEMANTIC SEARCH"));
            assertTrue(searchOutput.contains("test_document.txt"));
            assertTrue(searchOutput.contains("mock://test-cli-bucket/test_document.txt"));
        } finally {
            System.setOut(originalOut);
        }
    }
}
