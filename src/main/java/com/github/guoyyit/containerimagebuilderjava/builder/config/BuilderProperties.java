package com.github.guoyyit.containerimagebuilderjava.builder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Configuration for the local image builder.
 */
@ConfigurationProperties(prefix = "builder")
public class BuilderProperties {

    /** Root directory of the content-addressed layer cache. */
    private Path cacheDir = Path.of("data", "cache");

    /** Directory holding pre-built local base image packages. */
    private Path baseImagesDir = Path.of("data", "base-images");

    /** Directory where built image packages are written. */
    private Path outputDir = Path.of("data", "images");

    /** Maximum cache size in bytes; 0 means unlimited. */
    private long maxCacheSizeBytes = 0L;

    /** Minimum age of staging temp files before GC may remove them (ms). */
    private long stagingGraceMs = 3_600_000L;

    public Path getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public Path getBaseImagesDir() {
        return baseImagesDir;
    }

    public void setBaseImagesDir(Path baseImagesDir) {
        this.baseImagesDir = baseImagesDir;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }

    public long getMaxCacheSizeBytes() {
        return maxCacheSizeBytes;
    }

    public void setMaxCacheSizeBytes(long maxCacheSizeBytes) {
        this.maxCacheSizeBytes = maxCacheSizeBytes;
    }

    public long getStagingGraceMs() {
        return stagingGraceMs;
    }

    public void setStagingGraceMs(long stagingGraceMs) {
        this.stagingGraceMs = stagingGraceMs;
    }
}
