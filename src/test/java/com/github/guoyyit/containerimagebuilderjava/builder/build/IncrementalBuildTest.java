package com.github.guoyyit.containerimagebuilderjava.builder.build;

import com.github.guoyyit.containerimagebuilderjava.builder.TestFixtures;
import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IncrementalBuildTest {

    @TempDir
    Path tempDir;

    private BuilderProperties props;
    private ImageBuildService service;
    private Path ctx;

    @BeforeEach
    void setUp() throws Exception {
        props = TestFixtures.properties(tempDir);
        TestFixtures.createBaseImage(props, "mybase", "1.0", "base-v1\n");
        service = new ImageBuildService(props);
        ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
    }

    @Test
    void unchangedLayersAreReused() throws Exception {
        Files.writeString(ctx.resolve("a.txt"), "a1");
        Files.writeString(ctx.resolve("b.txt"), "b1");
        Path desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY b.txt /b.txt\n");

        BuildResult first = service.build(desc, ctx, "inc:1");
        assertEquals(2, first.layersRebuilt());

        BuildResult second = service.build(desc, ctx, "inc:2");
        assertEquals(0, second.layersRebuilt());
        assertEquals(2, second.layersReused());
        assertEquals(first.imageId(), second.imageId());
    }

    @Test
    void changedFileRebuildsThatLayerAndSubsequentLayers() throws Exception {
        Files.writeString(ctx.resolve("a.txt"), "a1");
        Files.writeString(ctx.resolve("b.txt"), "b1");
        Path desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY b.txt /b.txt\n");

        BuildResult first = service.build(desc, ctx, "inc:1");

        // change a.txt -> layer 1 changes, layer 2 (parent changed) changes too
        Files.writeString(ctx.resolve("a.txt"), "a2");
        BuildResult second = service.build(desc, ctx, "inc:2");
        assertEquals(2, second.layersRebuilt(), "both layers must rebuild");
        assertNotEquals(first.layerDigests().get(0), second.layerDigests().get(0));
        // layer 2 content (b.txt) is unchanged, so its digest is the same,
        // but it was rebuilt because its parent changed
        assertEquals(first.layerDigests().get(1), second.layerDigests().get(1));
        assertNotEquals(first.imageId(), second.imageId());

        // change only b.txt -> layer 1 reused, layer 2 rebuilt
        Files.writeString(ctx.resolve("a.txt"), "a1");
        Files.writeString(ctx.resolve("b.txt"), "b2");
        BuildResult third = service.build(desc, ctx, "inc:3");
        assertEquals(1, third.layersRebuilt());
        assertEquals(1, third.layersReused());
        assertEquals(first.layerDigests().get(0), third.layerDigests().get(0),
                "unchanged first layer must be reused");
        assertNotEquals(first.layerDigests().get(1), third.layerDigests().get(1),
                "b.txt changed -> layer 2 content changes");
    }

    @Test
    void changedBaseImageRebuildsAllLayers() throws Exception {
        Files.writeString(ctx.resolve("a.txt"), "a1");
        Path desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nCOPY a.txt /a.txt\n");

        BuildResult first = service.build(desc, ctx, "inc:1");

        // new base image content under the same ref
        TestFixtures.createBaseImage(props, "mybase", "1.0", "base-v2-changed\n");
        BuildResult second = service.build(desc, ctx, "inc:2");

        assertEquals(1, second.layersRebuilt());
        // COPY layer content is unchanged, but the base layers in the manifest
        // changed, so the image id changes
        assertEquals(first.layerDigests().get(0), second.layerDigests().get(0));
        assertNotEquals(first.imageId(), second.imageId());
    }
}
