package com.github.guoyyit.containerimagebuilderjava.builder.build;

import com.github.guoyyit.containerimagebuilderjava.builder.TestFixtures;
import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentBuildTest {

    @TempDir
    Path tempDir;

    private BuilderProperties props;
    private ImageBuildService service;
    private LayerCache layerCache;

    @BeforeEach
    void setUp() throws Exception {
        props = TestFixtures.properties(tempDir);
        TestFixtures.createBaseImage(props, "mybase", "1.0");
        layerCache = new LayerCache(props.getCacheDir());
        service = new ImageBuildService(props, layerCache,
                new com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore(props.getOutputDir()),
                new com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore(props.getBaseImagesDir()));
    }

    @Test
    void concurrentBuildsOfSameImageBuildEachLayerOnce() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("a.txt"), "a");
        Files.writeString(ctx.resolve("b.txt"), "b");
        Path desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY b.txt /b.txt\n");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<BuildResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> service.build(desc, ctx, "same:1")));
        }
        pool.shutdown();

        List<BuildResult> results = new ArrayList<>();
        for (Future<BuildResult> f : futures) {
            results.add(f.get());
        }

        // all builds succeeded with the same image id
        String imageId = results.get(0).imageId();
        for (BuildResult r : results) {
            assertEquals(imageId, r.imageId());
        }

        // base image: 2 layers + 1 config = 3 blobs; build adds 2 copy layers
        // (config is shared). No duplicate writes -> exactly 5 blobs.
        long blobCount = layerCache.listBlobs().size();
        assertEquals(5, blobCount, "each layer must be generated exactly once");

        // no staging leftovers
        try (var stream = Files.list(props.getCacheDir().resolve("staging"))) {
            assertEquals(0, stream.count());
        }
    }

    @Test
    void concurrentBuildsOfDifferentImagesAllSucceed() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<BuildResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                Path ctx = tempDir.resolve("ctx" + idx);
                Files.createDirectories(ctx);
                Files.writeString(ctx.resolve("f" + idx + ".txt"), "content-" + idx);
                Path desc = TestFixtures.writeDescription(tempDir.resolve("d" + idx),
                        "FROM mybase:1.0\nCOPY f" + idx + ".txt /f.txt\n");
                return service.build(desc, ctx, "diff:" + idx);
            }));
        }
        pool.shutdown();

        for (Future<BuildResult> f : futures) {
            BuildResult r = f.get();
            assertEquals(1, r.layerDigests().size());
        }

        // all image packages verify
        var outputStore = new com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore(
                props.getOutputDir());
        for (int i = 0; i < threads; i++) {
            var loaded = outputStore.load(com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore.sanitize("diff:" + i));
            assertEquals(3, loaded.manifest().layers().size()); // 2 base + 1 copy
        }
    }
}
