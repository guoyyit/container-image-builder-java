package com.github.guoyyit.containerimagebuilderjava.service;

import com.github.guoyyit.containerimagebuilderjava.build.ImageBuilder;
import com.github.guoyyit.containerimagebuilderjava.build.BuildCancellationToken;
import com.github.guoyyit.containerimagebuilderjava.config.CacheConfig;
import com.github.guoyyit.containerimagebuilderjava.gc.GarbageCollector;
import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;
import com.github.guoyyit.containerimagebuilderjava.model.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.parser.BuildDescriptorParser;
import com.github.guoyyit.containerimagebuilderjava.storage.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.storage.LayerStore;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ImageBuildService {

    private final BuildDescriptorParser parser = new BuildDescriptorParser();
    private final ImageBuilder imageBuilder;
    private final ImageStore imageStore;
    private final GarbageCollector garbageCollector;
    private final CacheConfig config;

    public ImageBuildService(CacheConfig config, LayerStore layerStore, ImageStore imageStore) {
        this.config = config;
        this.imageStore = imageStore;
        this.imageBuilder = new ImageBuilder(layerStore, imageStore);
        this.garbageCollector = new GarbageCollector(layerStore, imageStore);
    }

    public BuildResult build(BuildRequest request) {
        return build(request, BuildCancellationToken.never());
    }

    public BuildResult build(BuildRequest request, BuildCancellationToken token) {
        Path contextDir = normalize(request.contextDir());
        Path descriptorPath = normalize(request.descriptorPath());
        if (!java.nio.file.Files.isDirectory(contextDir)) {
            throw new com.github.guoyyit.containerimagebuilderjava.exception.BuildException(
                    "build context is not a directory: " + contextDir);
        }
        if (!java.nio.file.Files.isRegularFile(descriptorPath)) {
            throw new com.github.guoyyit.containerimagebuilderjava.exception.BuildException(
                    "build descriptor not found: " + descriptorPath);
        }
        var descriptor = parser.parse(descriptorPath);
        return imageBuilder.build(request.imageName(), descriptor, contextDir, token);
    }

    public ImageManifest inspect(String imageName) {
        ImageManifest manifest = imageStore.load(imageName);
        if (manifest == null) {
            throw new com.github.guoyyit.containerimagebuilderjava.exception.BuildException(
                    "image not found: " + imageName);
        }
        return manifest;
    }

    public boolean deleteImage(String imageName) {
        return imageStore.delete(imageName);
    }

    public int garbageCollect() {
        return garbageCollector.collect();
    }

    public Path cacheRoot() {
        return config.getRootDir();
    }

    private Path normalize(Path path) {
        if (path == null) {
            return Paths.get("");
        }
        try {
            return path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return path;
        }
    }
}
