package com.github.guoyyit.containerimagebuilderjava.storage;

import com.github.guoyyit.containerimagebuilderjava.config.CacheConfig;
import com.github.guoyyit.containerimagebuilderjava.digest.Digests;
import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;
import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.model.Layer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class DefaultLayerStore implements LayerStore {

    private final Path layersDir;
    private final Path tmpDir;
    private final ConcurrentHashMap<String, Object> chainLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> pins = new ConcurrentHashMap<>();
    private final AtomicInteger activeBuilds = new AtomicInteger(0);
    private volatile boolean gcRunning = false;
    private final Object gcLock = new Object();

    public DefaultLayerStore(CacheConfig config) {
        Path root = config.getRootDir();
        this.layersDir = root.resolve("layers");
        this.tmpDir = root.resolve("tmp");
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(layersDir);
            Files.createDirectories(tmpDir);
            try (Stream<Path> entries = Files.list(tmpDir)) {
                entries.forEach(this::deleteQuietly);
            }
            try (Stream<Path> entries = Files.list(layersDir)) {
                entries.filter(Files::isDirectory).forEach(dir -> {
                    if (!Files.isRegularFile(dir.resolve("layer.tar"))
                            || !Files.isRegularFile(dir.resolve("diff-id"))) {
                        deleteQuietly(dir);
                    }
                });
            }
        } catch (IOException e) {
            throw new BuildException("failed to initialize layer store at " + layersDir, e);
        }
    }

    @Override
    public Layer get(Digest chainId) {
        Path dir = layerDir(chainId);
        Path archive = dir.resolve("layer.tar");
        Path diffFile = dir.resolve("diff-id");
        if (!Files.isRegularFile(archive) || !Files.isRegularFile(diffFile)) {
            return null;
        }
        try {
            Digest diffId = Digest.of(Files.readString(diffFile, StandardCharsets.UTF_8).trim());
            return new Layer(chainId, diffId, archive);
        } catch (IOException e) {
            throw new BuildException("failed to read layer metadata for " + chainId, e);
        }
    }

    @Override
    public Layer getOrCreate(Digest chainId, Digest expectedDiffId, LayerWriter writer) {
        Layer existing = get(chainId);
        if (existing != null) {
            if (expectedDiffId != null && !existing.diffId().equals(expectedDiffId)) {
                throw new BuildException("corrupted layer cache for chain id " + chainId
                        + ": expected diff id " + expectedDiffId + " but stored " + existing.diffId());
            }
            return existing;
        }
        Object lock = chainLocks.computeIfAbsent(chainId.value(), key -> new Object());
        synchronized (lock) {
            Layer secondLook = get(chainId);
            if (secondLook != null) {
                if (expectedDiffId != null && !secondLook.diffId().equals(expectedDiffId)) {
                    throw new BuildException("corrupted layer cache for chain id " + chainId);
                }
                return secondLook;
            }
            return create(chainId, expectedDiffId, writer);
        }
    }

    @Override
    public boolean exists(Digest chainId) {
        return get(chainId) != null;
    }

    private Layer create(Digest chainId, Digest expectedDiffId, LayerWriter writer) {
        Path staging;
        try {
            staging = Files.createTempFile(tmpDir, "layer-", ".tar");
        } catch (IOException e) {
            throw new BuildException("failed to create staging file for layer " + chainId, e);
        }
        try {
            writer.write(staging);
            Digest actual = Digests.sha256File(staging);
            if (expectedDiffId != null && !actual.equals(expectedDiffId)) {
                throw new BuildException("layer content mismatch for chain id " + chainId
                        + ": expected " + expectedDiffId + " but computed " + actual);
            }
            Path dir = layersDir.resolve(chainId.value());
            Files.createDirectories(dir);
            Path stagedTarget = dir.resolve("layer.tar.staging");
            Files.move(staging, stagedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(dir.resolve("diff-id"), actual.value(), StandardCharsets.UTF_8);
            try {
                Files.move(stagedTarget, dir.resolve("layer.tar"),
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException renameError) {
                deleteQuietly(dir);
                throw renameError;
            }
            Layer layer = new Layer(chainId, actual, dir.resolve("layer.tar"));
            if (get(chainId) == null) {
                throw new BuildException("layer " + chainId + " vanished immediately after publish");
            }
            return layer;
        } catch (BuildException e) {
            deleteQuietly(staging);
            throw e;
        } catch (Exception e) {
            deleteQuietly(staging);
            throw new BuildException("failed to build layer " + chainId, e);
        }
    }

    @Override
    public void pin(Digest chainId) {
        pins.compute(chainId.value(), (key, current) -> {
            AtomicInteger count = current == null ? new AtomicInteger(0) : current;
            count.incrementAndGet();
            return count;
        });
    }

    @Override
    public void unpin(Digest chainId) {
        pins.computeIfPresent(chainId.value(), (key, count) ->
                count.decrementAndGet() <= 0 ? null : count);
    }

    @Override
    public void acquireBuildLease() {
        synchronized (gcLock) {
            while (gcRunning) {
                try {
                    gcLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BuildException("interrupted while waiting for garbage collection to finish", e);
                }
            }
            activeBuilds.incrementAndGet();
        }
    }

    @Override
    public void releaseBuildLease() {
        synchronized (gcLock) {
            activeBuilds.decrementAndGet();
            gcLock.notifyAll();
        }
    }

    @Override
    public void runExclusiveWithBuilds(Runnable action) throws InterruptedException {
        synchronized (gcLock) {
            while (activeBuilds.get() > 0) {
                gcLock.wait();
            }
            gcRunning = true;
            try {
                action.run();
            } finally {
                gcRunning = false;
                gcLock.notifyAll();
            }
        }
    }

    @Override
    public List<Digest> listLayerChainIds() {
        List<Digest> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(layersDir)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                if (Files.isRegularFile(dir.resolve("layer.tar"))) {
                    result.add(Digest.of(dir.getFileName().toString()));
                }
            });
        } catch (IOException e) {
            throw new BuildException("failed to list layers", e);
        }
        return result;
    }

    public boolean isPinned(Digest chainId) {
        AtomicInteger count = pins.get(chainId.value());
        return count != null && count.get() > 0;
    }

    public void deleteLayer(Digest chainId) {
        deleteQuietly(layerDir(chainId));
    }

    private Path layerDir(Digest chainId) {
        return layersDir.resolve(chainId.value());
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
