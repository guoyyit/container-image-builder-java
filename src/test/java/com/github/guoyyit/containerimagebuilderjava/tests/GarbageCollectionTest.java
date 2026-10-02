package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GarbageCollectionTest extends AbstractBuildTest {

    @BeforeEach
    void init() throws Exception {
        setUp();
    }

    @AfterEach
    void clean() throws Exception {
        tearDown();
    }

    @Test
    void unreferencedLayersAreCollected() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "a/f.txt", "v1");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY a /a\n");
        BuildResult first = service.build(new BuildRequest("demo", descriptor, context));

        writeFile(context, "a/f.txt", "v2");
        BuildResult second = service.build(new BuildRequest("demo", descriptor, context));

        assertEquals(2, countLayerDirs());
        int removed = service.garbageCollect();
        assertEquals(1, removed);
        assertEquals(1, countLayerDirs());
        assertTrue(Files.isDirectory(cacheDir.resolve("layers").resolve(second.layerChainIds().get(0).value())));
        assertTrue(Files.notExists(cacheDir.resolve("layers").resolve(first.layerChainIds().get(0).value())));
    }

    @Test
    void layersOfOtherImagesAreNotCollected() throws Exception {
        Path firstContext = createContext("ctx-a");
        writeFile(firstContext, "f.txt", "a");
        Path firstDescriptor = createDescriptor("a.desc",
                "FROM scratch\nCOPY f.txt /f.txt\n");
        BuildResult first = service.build(new BuildRequest("image-a", firstDescriptor, firstContext));

        Path secondContext = createContext("ctx-b");
        writeFile(secondContext, "f.txt", "b");
        Path secondDescriptor = createDescriptor("b.desc",
                "FROM scratch\nCOPY f.txt /f.txt\n");
        BuildResult second = service.build(new BuildRequest("image-b", secondDescriptor, secondContext));

        assertEquals(0, service.garbageCollect());
        assertEquals(2, countLayerDirs());

        assertTrue(service.deleteImage("image-a"));
        assertEquals(1, service.garbageCollect());
        assertTrue(Files.notExists(cacheDir.resolve("layers").resolve(first.layerChainIds().get(0).value())));
        assertTrue(Files.isDirectory(cacheDir.resolve("layers").resolve(second.layerChainIds().get(0).value())));
    }

    @Test
    void gcWaitsForActiveBuildsAndKeepsTheirLayers() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "f.txt", "single");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY f.txt /f.txt\n");
        service.build(new BuildRequest("keep", descriptor, context));

        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch releaseBuild = new CountDownLatch(1);
        Path otherContext = createContext("other");
        writeFile(otherContext, "g.txt", "other");
        writeFile(otherContext, "h.txt", "another");
        Path otherDescriptor = createDescriptor("other.desc",
                "FROM scratch\nCOPY g.txt /g.txt\nCOPY h.txt /h.txt\n");

        Thread buildThread = new Thread(() -> {
            try {
                com.github.guoyyit.containerimagebuilderjava.build.BuildCancellationToken token =
                        new com.github.guoyyit.containerimagebuilderjava.build.BuildCancellationToken() {
                            @Override
                            public void throwIfCancelled() {
                                try {
                                    if (countLayerDirs() >= 2 && buildStarted.getCount() > 0) {
                                        buildStarted.countDown();
                                    }
                                    if (buildStarted.getCount() == 0
                                            && countLayerDirs() >= 2
                                            && releaseBuild.getCount() > 0) {
                                        releaseBuild.await(30, TimeUnit.SECONDS);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                super.throwIfCancelled();
                            }
                        };
                service.build(new BuildRequest("other", otherDescriptor, otherContext), token);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        buildThread.start();
        assertTrue(buildStarted.await(10, TimeUnit.SECONDS));

        Thread gcThread = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(200);
                service.garbageCollect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        gcThread.start();

        TimeUnit.MILLISECONDS.sleep(500);
        assertEquals(2, countLayerDirs());

        releaseBuild.countDown();
        buildThread.join(10000);
        gcThread.join(10000);
        assertEquals(3, countLayerDirs());
    }

    private long countLayerDirs() throws Exception {
        try (var stream = Files.list(cacheDir.resolve("layers"))) {
            return stream.filter(Files::isDirectory).count();
        }
    }
}
