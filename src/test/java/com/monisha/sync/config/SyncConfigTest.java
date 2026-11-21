package com.monisha.sync.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncConfigTest {

    @Test
    void testParseConfigYaml() throws IOException {
        Path configPath = Path.of("config.yaml");
        SyncConfig config = SyncConfig.load(configPath);

        assertNotNull(config);
        assertEquals("./sync-folder", config.getWatchDirectory());
        assertEquals("jcloud-sync-dev", config.getS3Bucket());
        assertEquals("us-east-1", config.getS3Region());
        assertEquals("http://localhost:4566", config.getS3EndpointOverride());
        assertEquals(500, config.getDebounceMs());
        assertEquals(9090, config.getMetricsPort());
        assertEquals("KEEP_BOTH", config.getConflictStrategy());
        assertEquals(com.monisha.sync.engine.ConflictResolutionStrategy.KEEP_BOTH, config.getConflictResolutionStrategy());

        List<String> ignorePatterns = config.getIgnorePatterns();
        assertEquals(5, ignorePatterns.size());
        assertTrue(ignorePatterns.contains("*.tmp"));
        assertTrue(ignorePatterns.contains("*.swp"));
        assertTrue(ignorePatterns.contains(".git/**"));
        assertTrue(ignorePatterns.contains(".DS_Store"));
        assertTrue(ignorePatterns.contains(".jcloud/**"));
    }

    @Test
    void testIgnoreMatcher() {
        List<String> patterns = List.of("*.tmp", "*.swp", ".git/**", ".DS_Store");
        IgnoreMatcher matcher = new IgnoreMatcher(Path.of("./sync-folder"), patterns);

        // Extensions matched at any directory depth
        assertTrue(matcher.matches("test.tmp"));
        assertTrue(matcher.matches("sub/nested/file.tmp"));
        assertTrue(matcher.matches("editor.swp"));
        assertTrue(matcher.matches("deeply/nested/editor.swp"));

        // Exact file pattern
        assertTrue(matcher.matches(".DS_Store"));
        assertTrue(matcher.matches("subfolder/.DS_Store"));

        // Directory glob pattern
        assertTrue(matcher.matches(".git"));
        assertTrue(matcher.matches(".git/config"));
        assertTrue(matcher.matches(".git/objects/01/abcdef"));

        // Valid files should NOT match
        assertFalse(matcher.matches("document.txt"));
        assertFalse(matcher.matches("subfolder/report.pdf"));
        assertFalse(matcher.matches("temp.txt"));
        assertFalse(matcher.matches("src/main/java/Main.java"));
    }
}
