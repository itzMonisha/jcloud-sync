package com.monisha.sync.config;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Matches file and directory paths against glob ignore patterns using java.nio.file.PathMatcher.
 */
public class IgnoreMatcher {

    private final Path rootDir;
    private final List<String> patterns;
    private final List<CompiledPattern> matchers;

    private static class CompiledPattern {
        final String rawPattern;
        final PathMatcher pathMatcher;
        final boolean hasDirectorySeparator;
        final String prefixBeforeDoubleStar;

        CompiledPattern(String rawPattern, PathMatcher pathMatcher, boolean hasDirectorySeparator, String prefixBeforeDoubleStar) {
            this.rawPattern = rawPattern;
            this.pathMatcher = pathMatcher;
            this.hasDirectorySeparator = hasDirectorySeparator;
            this.prefixBeforeDoubleStar = prefixBeforeDoubleStar;
        }
    }

    public IgnoreMatcher(List<String> patterns) {
        this(null, patterns);
    }

    public IgnoreMatcher(Path rootDir, List<String> patterns) {
        this.rootDir = rootDir != null ? rootDir.toAbsolutePath().normalize() : null;
        this.patterns = patterns != null ? List.copyOf(patterns) : Collections.emptyList();
        this.matchers = new ArrayList<>();

        FileSystem fs = FileSystems.getDefault();
        for (String raw : this.patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim();
            boolean hasDirSep = trimmed.contains("/") || trimmed.contains("\\");
            PathMatcher pm = fs.getPathMatcher("glob:" + trimmed);

            String prefix = null;
            if (trimmed.endsWith("/**")) {
                prefix = trimmed.substring(0, trimmed.length() - 3);
            }

            this.matchers.add(new CompiledPattern(trimmed, pm, hasDirSep, prefix));
        }
    }

    /**
     * Checks if the given path matches any configured ignore glob pattern.
     *
     * @param path target path to test (can be absolute or relative)
     * @return true if ignored, false otherwise
     */
    public boolean matches(Path path) {
        if (path == null || matchers.isEmpty()) {
            return false;
        }

        Path relativePath = path;
        if (path.isAbsolute()) {
            if (rootDir != null && path.startsWith(rootDir)) {
                relativePath = rootDir.relativize(path);
            } else {
                relativePath = path.normalize();
            }
        }

        Path fileName = relativePath.getFileName();
        String normalizedPathStr = relativePath.toString().replace('\\', '/');

        for (CompiledPattern cp : matchers) {
            // 1. Direct match on relative path
            if (cp.pathMatcher.matches(relativePath)) {
                return true;
            }

            // 2. If pattern does not contain a directory separator (e.g. *.tmp, .DS_Store),
            // match against the file name component so nested files match as expected
            if (!cp.hasDirectorySeparator && fileName != null && cp.pathMatcher.matches(fileName)) {
                return true;
            }

            // 3. For directory prefix patterns like ".git/**", match the directory itself or subpaths
            if (cp.prefixBeforeDoubleStar != null) {
                String normPrefix = cp.prefixBeforeDoubleStar.replace('\\', '/');
                if (normalizedPathStr.equals(normPrefix) || normalizedPathStr.startsWith(normPrefix + "/")) {
                    return true;
                }
            }

            // 4. Match against path constructed from normalized string with forward slashes
            Path normalizedPath = Path.of(normalizedPathStr);
            if (cp.pathMatcher.matches(normalizedPath)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the target path matches ignore patterns relative to the specified base directory.
     *
     * @param baseDir base directory to relativize against
     * @param target target path to check
     * @return true if ignored, false otherwise
     */
    public boolean matches(Path baseDir, Path target) {
        if (baseDir != null && target != null && target.isAbsolute()) {
            Path rel = baseDir.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize());
            return matches(rel);
        }
        return matches(target);
    }

    /**
     * Checks if a relative string path matches any ignore pattern.
     *
     * @param relativePath string representation of relative path
     * @return true if ignored, false otherwise
     */
    public boolean matches(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        return matches(Path.of(relativePath));
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public Path getRootDir() {
        return rootDir;
    }
}
