package com.github.guoyyit.containerimagebuilderjava.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "container-builder.cache")
public class CacheConfig {

    private Path rootDir = Path.of(".image-cache");

    private long unreferencedGraceMillis = 0L;

    public Path getRootDir() {
        return rootDir;
    }

    public void setRootDir(Path rootDir) {
        this.rootDir = rootDir;
    }

    public long getUnreferencedGraceMillis() {
        return unreferencedGraceMillis;
    }

    public void setUnreferencedGraceMillis(long unreferencedGraceMillis) {
        this.unreferencedGraceMillis = unreferencedGraceMillis;
    }
}
