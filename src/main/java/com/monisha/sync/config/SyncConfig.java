package com.monisha.sync.config;

import com.monisha.sync.engine.ConflictResolutionStrategy;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration representation parsed from config.yaml.
 */
public class SyncConfig {

    private String watchDirectory;
    private String s3Bucket;
    private String s3Region;
    private String s3EndpointOverride;
    private long debounceMs;
    private int metricsPort;
    private String conflictStrategy = "KEEP_BOTH";
    private boolean aiIndexingEnabled = false;
    private List<String> ignorePatterns = new ArrayList<>();

    public SyncConfig() {
    }

    public SyncConfig(String watchDirectory,
                      String s3Bucket,
                      String s3Region,
                      String s3EndpointOverride,
                      long debounceMs,
                      int metricsPort,
                      String conflictStrategy,
                      boolean aiIndexingEnabled,
                      List<String> ignorePatterns) {
        this.watchDirectory = watchDirectory;
        this.s3Bucket = s3Bucket;
        this.s3Region = s3Region;
        this.s3EndpointOverride = s3EndpointOverride;
        this.debounceMs = debounceMs;
        this.metricsPort = metricsPort;
        this.conflictStrategy = conflictStrategy != null ? conflictStrategy : "KEEP_BOTH";
        this.aiIndexingEnabled = aiIndexingEnabled;
        this.ignorePatterns = ignorePatterns != null ? new ArrayList<>(ignorePatterns) : new ArrayList<>();
    }

    public static SyncConfig load(Path configPath) throws IOException {
        Objects.requireNonNull(configPath, "configPath must not be null");
        try (InputStream in = Files.newInputStream(configPath)) {
            return load(in);
        }
    }

    public static SyncConfig load(InputStream inputStream) {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(SyncConfig.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);
        return yaml.load(inputStream);
    }

    public static SyncConfig load(String yamlContent) {
        Objects.requireNonNull(yamlContent, "yamlContent must not be null");
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(SyncConfig.class, loaderOptions);
        Yaml yaml = new Yaml(constructor);
        return yaml.load(yamlContent);
    }

    public IgnoreMatcher createIgnoreMatcher() {
        Path root = watchDirectory != null ? Path.of(watchDirectory) : null;
        return new IgnoreMatcher(root, ignorePatterns);
    }

    public String getWatchDirectory() {
        return watchDirectory;
    }

    public void setWatchDirectory(String watchDirectory) {
        this.watchDirectory = watchDirectory;
    }

    public String getS3Bucket() {
        return s3Bucket;
    }

    public void setS3Bucket(String s3Bucket) {
        this.s3Bucket = s3Bucket;
    }

    public String getS3Region() {
        return s3Region;
    }

    public void setS3Region(String s3Region) {
        this.s3Region = s3Region;
    }

    public String getS3EndpointOverride() {
        return s3EndpointOverride;
    }

    public void setS3EndpointOverride(String s3EndpointOverride) {
        this.s3EndpointOverride = s3EndpointOverride;
    }

    public long getDebounceMs() {
        return debounceMs;
    }

    public void setDebounceMs(long debounceMs) {
        this.debounceMs = debounceMs;
    }

    public int getMetricsPort() {
        return metricsPort;
    }

    public void setMetricsPort(int metricsPort) {
        this.metricsPort = metricsPort;
    }

    public String getConflictStrategy() {
        return conflictStrategy != null ? conflictStrategy : "KEEP_BOTH";
    }

    public void setConflictStrategy(String conflictStrategy) {
        this.conflictStrategy = conflictStrategy;
    }

    public ConflictResolutionStrategy getConflictResolutionStrategy() {
        if (conflictStrategy == null) {
            return ConflictResolutionStrategy.KEEP_BOTH;
        }
        try {
            return ConflictResolutionStrategy.valueOf(conflictStrategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ConflictResolutionStrategy.KEEP_BOTH;
        }
    }

    public boolean isAiIndexingEnabled() {
        return aiIndexingEnabled;
    }

    public void setAiIndexingEnabled(boolean aiIndexingEnabled) {
        this.aiIndexingEnabled = aiIndexingEnabled;
    }

    public List<String> getIgnorePatterns() {
        return ignorePatterns != null ? Collections.unmodifiableList(ignorePatterns) : Collections.emptyList();
    }

    public void setIgnorePatterns(List<String> ignorePatterns) {
        this.ignorePatterns = ignorePatterns != null ? new ArrayList<>(ignorePatterns) : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncConfig that = (SyncConfig) o;
        return debounceMs == that.debounceMs &&
                metricsPort == that.metricsPort &&
                aiIndexingEnabled == that.aiIndexingEnabled &&
                Objects.equals(watchDirectory, that.watchDirectory) &&
                Objects.equals(s3Bucket, that.s3Bucket) &&
                Objects.equals(s3Region, that.s3Region) &&
                Objects.equals(s3EndpointOverride, that.s3EndpointOverride) &&
                Objects.equals(conflictStrategy, that.conflictStrategy) &&
                Objects.equals(ignorePatterns, that.ignorePatterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(watchDirectory, s3Bucket, s3Region, s3EndpointOverride, debounceMs, metricsPort, conflictStrategy, aiIndexingEnabled, ignorePatterns);
    }

    @Override
    public String toString() {
        return "SyncConfig{" +
                "watchDirectory='" + watchDirectory + '\'' +
                ", s3Bucket='" + s3Bucket + '\'' +
                ", s3Region='" + s3Region + '\'' +
                ", s3EndpointOverride='" + s3EndpointOverride + '\'' +
                ", debounceMs=" + debounceMs +
                ", metricsPort=" + metricsPort +
                ", conflictStrategy='" + conflictStrategy + '\'' +
                ", aiIndexingEnabled=" + aiIndexingEnabled +
                ", ignorePatterns=" + ignorePatterns +
                '}';
    }
}
