package com.github.guoyyit.containerimagebuilderjava.builder.gc;

import com.github.guoyyit.containerimagebuilderjava.builder.TestFixtures;
import com.github.guoyyit.containerimagebuilderjava.builder.build.BuildResult;
import com.github.guoyyit.containerimagebuilderjava.builder.build.ImageBuildService;
import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcConcurrentBuildTest {

    @TempDir
    Path tempDir;

    private BuilderProperties props;
    private LayerCache layerCache;
    private ImageBuildService service;
    private GarbageCollector gc;

    @BeforeEach
    void setUp() throws Exception {
        props = TestFixtures.properties(tempDir);
        TestFixtures.createBaseImage(props, "mybase", "1.0");
        layerCache = new LayerCache(props.getCacheDir());
        ImageStore outputStore = new ImageStore(props.getOutputDir());
        ImageStore baseStore = new ImageStore(props.getBaseImagesDir());
        service = new ImageBuildService(props, layerCache, outputStore, baseStore);
        gc = new GarbageCollector(props, layerCache, outputStore, baseStore);
    }

    @Test
    void readLockBlocksGcWriteLock() throws Exception {
        CountDownLatch buildHolding = new CountDownLatch(1);
        CountDownLatch gcFinished = new CountDownLatch(1);

        Thread buildThread = new Thread(() -> layerCache.withReadLock(() -> {
            buildHolding.countDown();
            assertDoesNotThrow(() -> Thread.sleep(200));
        }));
        buildThread.start();
        buildHolding.await();

        Thread gcThread = new Thread(() -> {
            layerCache.withWriteLock(() -> {
            });
            gcFinished.countDown();
        });
        gcThread.start();

        // GC must not finish while the build holds the read lock
        assertFalse(gcFinished.await(100, TimeUnit.MILLISECONDS),
                "GC must wait for the build's read lock");
        buildThread.join();
        assertTrue(gcFinished.await(2, TimeUnit.SECONDS), "GC proceeds after build finishes");
    }

    @Test
    void buildsAndGcRunConcurrentlyWithoutCorruption() throws Exception {
        int buildThreads = 4;
        int buildsPerThread = 4;
        ExecutorService pool = Executors.newFixedThreadPool(buildThreads + 1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < buildThreads; t++) {
            int threadIdx = t;
        futures.add(pool.submit(() -> {
                try {
                    for (int b = 0; b < buildsPerThread; b++) {
                        String tag = "img-" + threadIdx + "-" + b;
                        Path ctx = tempDir.resolve("ctx-" + threadIdx + "-" + b);
                        Files.createDirectories(ctx);
                        Files.writeString(ctx.resolve("f.txt"), "content-" + threadIdx + "-" + b);
                        Path desc = TestFixtures.writeDescription(
                                tempDir.resolve("d-" + threadIdx + "-" + b),
                                "FROM mybase:1.0\nCOPY f.txt /f.txt\n");
                        BuildResult result = service.build(desc, ctx, tag);
                        assertEquals(1, result.layerDigests().size());
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));
        }

        futures.add(pool.submit(() -> {
            for (int i = 0; i < 10; i++) {
                assertDoesNotThrow(gc::gc);
                assertDoesNotThrow(() -> Thread.sleep(30));
            }
        }));

        pool.shutdown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }

        // every image package verifies
        ImageStore outputStore = new ImageStore(props.getOutputDir());
        for (int t = 0; t < buildThreads; t++) {
            for (int b = 0; b < buildsPerThread; b++) {
                String tag = "img-" + t + "-" + b;
                var loaded = outputStore.load(ImageStore.sanitize(tag));
                assertEquals(3, loaded.manifest().layers().size());
            }
        }
    }
}
