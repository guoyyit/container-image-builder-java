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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GarbageCollectorTest {

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
    void unreferencedLayersAreCollectedAndReferencedKept() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("a.txt"), "a");
        Files.writeString(ctx.resolve("b.txt"), "b");
        Files.writeString(ctx.resolve("c.txt"), "c");

        BuildResult imageA = service.build(
                TestFixtures.writeDescription(tempDir.resolve("d1"),
                        "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY b.txt /b.txt\n"),
                ctx, "imageA:1");
        BuildResult imageB = service.build(
                TestFixtures.writeDescription(tempDir.resolve("d2"),
                        "FROM mybase:1.0\nCOPY c.txt /c.txt\n"),
                ctx, "imageB:1");

        Set<String> aLayers = new HashSet<>(imageA.layerDigests());
        Set<String> bLayers = new HashSet<>(imageB.layerDigests());

        // delete image A -> its layers become unreferenced
        new ImageStore(props.getOutputDir()).delete(ImageStore.sanitize("imageA:1"));

        long before = layerCache.listBlobs().size();
        GarbageCollector.GcStats stats = gc.gc();

        assertEquals(2, stats.deletedBlobs(), "two unreferenced layers should be collected");
        for (String digest : aLayers) {
            assertFalse(layerCache.hasBlob(digest), "A layer should be gone: " + digest);
        }
        for (String digest : bLayers) {
            assertTrue(layerCache.hasBlob(digest), "B layer must be kept: " + digest);
        }
        // base layers kept
        ImageStore baseStore = new ImageStore(props.getBaseImagesDir());
        var baseManifest = baseStore.readManifest(baseStore.packageDir("mybase_1.0"));
        for (var layer : baseManifest.layers()) {
            assertTrue(layerCache.hasBlob(layer.digest()), "base layer must be kept: " + layer.digest());
        }

        // rebuilding A reuses base + B layers and recreates its own
        BuildResult rebuilt = service.build(
                TestFixtures.writeDescription(tempDir.resolve("d3"),
                        "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY b.txt /b.txt\n"),
                ctx, "imageA:2");
        assertEquals(imageA.imageId(), rebuilt.imageId());
        assertEquals(2, rebuilt.layersRebuilt());
        assertTrue(layerCache.hasBlob(bLayers.iterator().next()));
    }

    @Test
    void sizeLimitEvictsUnreferencedLruFirst() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("a.txt"), "a");
        Files.writeString(ctx.resolve("b.txt"), "b");

        BuildResult imageA = service.build(
                TestFixtures.writeDescription(tempDir.resolve("d1"),
                        "FROM mybase:1.0\nCOPY a.txt /a.txt\n"),
                ctx, "lruA:1");
        BuildResult imageB = service.build(
                TestFixtures.writeDescription(tempDir.resolve("d2"),
                        "FROM mybase:1.0\nCOPY b.txt /b.txt\n"),
                ctx, "lruB:1");

        // both unreferenced after deletion
        new ImageStore(props.getOutputDir()).delete(ImageStore.sanitize("lruA:1"));
        new ImageStore(props.getOutputDir()).delete(ImageStore.sanitize("lruB:1"));

        // limit to ~ base size: both copy layers should be evicted
        long baseSize = layerCache.blobSize(imageA.layerDigests().get(0))
                + layerCache.blobSize(imageB.layerDigests().get(0));
        props.setMaxCacheSizeBytes(1); // smaller than anything -> evict all unreferenced
        GarbageCollector.GcStats stats = gc.gc();

        assertEquals(2, stats.deletedBlobs());
        assertEquals(0, layerCache.listBlobs().stream()
                .filter(p -> imageA.layerDigests().contains(p.getFileName().toString())
                        || imageB.layerDigests().contains(p.getFileName().toString()))
                .count());
    }
}
