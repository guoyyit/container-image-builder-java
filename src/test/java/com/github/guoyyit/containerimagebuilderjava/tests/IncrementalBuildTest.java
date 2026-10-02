package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;
import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.model.ImageManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalBuildTest extends AbstractBuildTest {

    @BeforeEach
    void init() throws Exception {
        setUp();
    }

    @AfterEach
    void clean() throws Exception {
        tearDown();
    }

    @Test
    void rebuildProducesIdenticalImageAndReusesLayers() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "app/a.txt", "alpha");
        writeFile(context, "app/b.txt", "beta");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY app /app\n");

        BuildRequest request = new BuildRequest("demo", descriptor, context);
        BuildResult first = service.build(request);
        assertEquals(1, first.layerChainIds().size());
        assertFalse(first.cacheHit());

        BuildResult second = service.build(request);
        assertEquals(first.imageId(), second.imageId());
        assertEquals(first.layerChainIds(), second.layerChainIds());
        assertTrue(second.cacheHit());

        ImageManifest manifest = service.inspect("demo");
        assertEquals(first.imageId(), manifest.imageId());
        assertEquals(first.layerChainIds(), manifest.layerChainIds());
        assertEquals(0L, manifest.createdAtEpoch());

        Path layerTar = cacheDir.resolve("layers")
                .resolve(first.layerChainIds().get(0).value()).resolve("layer.tar");
        byte[] firstBytes = Files.readAllBytes(layerTar);
        service.build(request);
        byte[] secondBytes = Files.readAllBytes(layerTar);
        org.junit.jupiter.api.Assertions.assertArrayEquals(firstBytes, secondBytes);

        long layerCount;
        try (var stream = Files.list(cacheDir.resolve("layers"))) {
            layerCount = stream.count();
        }
        assertEquals(1, layerCount);
    }

    @Test
    void changedContextRebuildsAffectedAndFollowingLayers() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "a/one.txt", "one");
        writeFile(context, "b/two.txt", "two");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY a /a\nCOPY b /b\n");

        BuildRequest request = new BuildRequest("demo", descriptor, context);
        BuildResult first = service.build(request);
        assertEquals(2, first.layerChainIds().size());

        writeFile(context, "a/one.txt", "one-changed");
        BuildResult second = service.build(request);

        assertNotEquals(first.layerChainIds().get(0), second.layerChainIds().get(0));
        assertNotEquals(first.layerChainIds().get(1), second.layerChainIds().get(1));
        assertNotEquals(first.imageId(), second.imageId());
        assertEquals(4, countLayers());

        int removed = service.garbageCollect();
        assertEquals(2, removed);
        assertEquals(2, countLayers());
    }

    private long countLayers() throws Exception {
        try (var stream = Files.list(cacheDir.resolve("layers"))) {
            return stream.count();
        }
    }

    @Test
    void unchangedUnrelatedLayerIsReusedWhenEarlierLayerChanges() throws Exception {
        // The second layer's content stays the same, but because the chain id embeds
        // the parent chain id, it must still be rebuilt as a new chain node.
        Path context = createContext("ctx");
        writeFile(context, "a/one.txt", "one");
        writeFile(context, "b/two.txt", "two");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY a /a\nCOPY b /b\n");
        BuildResult first = service.build(new BuildRequest("demo", descriptor, context));

        writeFile(context, "a/one.txt", "new-one");
        BuildResult second = service.build(new BuildRequest("demo", descriptor, context));
        Digest firstSecondLayer = first.layerChainIds().get(1);
        Digest secondSecondLayer = second.layerChainIds().get(1);
        assertNotEquals(firstSecondLayer, secondSecondLayer);

        String firstDiff = Files.readString(cacheDir.resolve("layers")
                .resolve(firstSecondLayer.value()).resolve("diff-id"));
        String secondDiff = Files.readString(cacheDir.resolve("layers")
                .resolve(secondSecondLayer.value()).resolve("diff-id"));
        assertEquals(firstDiff, secondDiff);
    }
}
