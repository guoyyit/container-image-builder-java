package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.config.CacheConfig;
import com.github.guoyyit.containerimagebuilderjava.service.ImageBuildService;
import com.github.guoyyit.containerimagebuilderjava.storage.DefaultImageStore;
import com.github.guoyyit.containerimagebuilderjava.storage.DefaultLayerStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

abstract class AbstractBuildTest {

    protected Path tempDir;
    protected Path cacheDir;
    protected ImageBuildService service;

    protected void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cib-test-");
        cacheDir = tempDir.resolve("cache");
        CacheConfig config = new CacheConfig();
        config.setRootDir(cacheDir);
        DefaultLayerStore layerStore = new DefaultLayerStore(config);
        DefaultImageStore imageStore = new DefaultImageStore(config);
        service = new ImageBuildService(config, layerStore, imageStore);
    }

    protected void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    protected Path createContext(String name) throws IOException {
        Path context = tempDir.resolve(name);
        Files.createDirectories(context);
        return context;
    }

    protected Path createDescriptor(String name, String content) throws IOException {
        Path descriptor = tempDir.resolve(name);
        Files.writeString(descriptor, content, StandardCharsets.UTF_8);
        return descriptor;
    }

    protected Path writeFile(Path context, String relativePath, String content) throws IOException {
        Path file = context.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
