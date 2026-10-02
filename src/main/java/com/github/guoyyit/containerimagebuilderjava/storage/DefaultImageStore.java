package com.github.guoyyit.containerimagebuilderjava.storage;

import com.github.guoyyit.containerimagebuilderjava.config.CacheConfig;
import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;
import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.model.ImageManifest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class DefaultImageStore implements ImageStore {

    private static final String REGISTRY_FILE = "registry.json";

    private final Path imagesDir;
    private final Object lock = new Object();
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultImageStore(CacheConfig config) {
        this.imagesDir = config.getRootDir().resolve("images");
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(imagesDir);
            if (!Files.isRegularFile(imagesDir.resolve(REGISTRY_FILE))) {
                Files.writeString(imagesDir.resolve(REGISTRY_FILE), "{}", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new BuildException("failed to initialize image store at " + imagesDir, e);
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, String> readRegistry() {
        try {
            String json = Files.readString(imagesDir.resolve(REGISTRY_FILE), StandardCharsets.UTF_8);
            return mapper.readValue(json, java.util.Map.class);
        } catch (IOException e) {
            throw new BuildException("failed to read image registry", e);
        }
    }

    private void writeRegistry(java.util.Map<String, String> registry) {
        try {
            Path staging = Files.createTempFile(imagesDir, "registry-", ".json");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry);
            Files.writeString(staging, json, StandardCharsets.UTF_8);
            Files.move(staging, imagesDir.resolve(REGISTRY_FILE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BuildException("failed to write image registry", e);
        }
    }

    @Override
    public void commit(ImageManifest manifest) {
        synchronized (lock) {
            try {
                Path file = manifestFile(manifest.imageId());
                if (!Files.isRegularFile(file)) {
                    Path staging = Files.createTempFile(imagesDir, "manifest-", ".json");
                    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
                    Files.writeString(staging, json, StandardCharsets.UTF_8);
                    Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
                }
                java.util.Map<String, String> registry = readRegistry();
                registry.put(manifest.name(), manifest.imageId().value());
                writeRegistry(registry);
            } catch (IOException e) {
                throw new BuildException("failed to commit image manifest for " + manifest.name(), e);
            }
        }
    }

    @Override
    public ImageManifest load(String imageName) {
        synchronized (lock) {
            String imageId = readRegistry().get(imageName);
            if (imageId == null) {
                return null;
            }
            Path file = manifestFile(Digest.of(imageId));
            if (!Files.isRegularFile(file)) {
                throw new BuildException("dangling image reference in registry: " + imageName);
            }
            try {
                return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), ImageManifest.class);
            } catch (IOException e) {
                throw new BuildException("failed to read manifest for image " + imageName, e);
            }
        }
    }

    @Override
    public boolean exists(String imageName) {
        return readRegistry().containsKey(imageName);
    }

    @Override
    public List<ImageManifest> listImages() {
        synchronized (lock) {
            List<ImageManifest> result = new ArrayList<>();
            for (String imageId : new LinkedHashSet<>(readRegistry().values())) {
                try {
                    result.add(mapper.readValue(
                            Files.readString(manifestFile(Digest.of(imageId)), StandardCharsets.UTF_8),
                            ImageManifest.class));
                } catch (IOException e) {
                    throw new BuildException("failed to read manifest " + imageId, e);
                }
            }
            return result;
        }
    }

    @Override
    public List<Digest> allReferencedChainIds() {
        synchronized (lock) {
            Set<Digest> result = new LinkedHashSet<>();
            for (ImageManifest manifest : listImages()) {
                result.addAll(manifest.layerChainIds());
            }
            return List.copyOf(result);
        }
    }

    @Override
    public boolean delete(String imageName) {
        synchronized (lock) {
            java.util.Map<String, String> registry = readRegistry();
            if (registry.remove(imageName) == null) {
                return false;
            }
            writeRegistry(registry);
            return true;
        }
    }

    private Path manifestFile(Digest imageId) {
        return imagesDir.resolve(imageId.value() + ".json");
    }

    public void pruneUnreferencedManifestFiles() {
        synchronized (lock) {
            Set<String> referenced = new LinkedHashSet<>(readRegistry().values());
            try (Stream<Path> files = Files.list(imagesDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")
                                && !p.getFileName().toString().equals(REGISTRY_FILE))
                        .filter(p -> !referenced.contains(p.getFileName().toString().replace(".json", "")))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException e) {
                throw new BuildException("failed to prune manifest files", e);
            }
        }
    }
}
