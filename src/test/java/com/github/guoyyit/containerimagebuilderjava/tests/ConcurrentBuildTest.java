package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentBuildTest extends AbstractBuildTest {

    @BeforeEach
    void init() throws Exception {
        setUp();
    }

    @AfterEach
    void clean() throws Exception {
        tearDown();
    }

    @Test
    void identicalConcurrentBuildsCreateEachLayerOnce() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "a/f.txt", "a");
        writeFile(context, "b/g.txt", "b");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY a /a\nCOPY b /b\n");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<BuildResult> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(service.build(new BuildRequest("shared-image", descriptor, context)));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(List.of(), List.copyOf(errors));
        Set<String> imageIds = results.stream()
                .map(r -> r.imageId().value()).collect(Collectors.toSet());
        assertEquals(1, imageIds.size());

        try (var stream = Files.list(cacheDir.resolve("layers"))) {
            long layerDirs = stream.count();
            assertEquals(2, layerDirs);
        }

        try (var stream = Files.walk(cacheDir.resolve("tmp"))) {
            long leftovers = stream.filter(Files::isRegularFile).count();
            assertEquals(0, leftovers);
        }
    }

    @Test
    void distinctConcurrentBuildsShareUnchangedLayers() throws Exception {
        Path commonContext = createContext("common");
        writeFile(commonContext, "common/c.txt", "shared-content");

        int imageCount = 6;
        ExecutorService pool = Executors.newFixedThreadPool(imageCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(imageCount);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < imageCount; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    Path ctx = createContext("ctx-" + index);
                    writeFile(ctx, "common/c.txt", "shared-content");
                    writeFile(ctx, "unique/u.txt", "unique-" + index);
                    Path desc = createDescriptor("image-" + index + ".desc",
                            "FROM scratch\nCOPY common /common\nCOPY unique /unique\n");
                    service.build(new BuildRequest("image-" + index, desc, ctx));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(List.of(), List.copyOf(errors));
        try (var stream = Files.list(cacheDir.resolve("layers"))) {
            assertEquals(1 + imageCount, stream.count());
        }
    }
}
