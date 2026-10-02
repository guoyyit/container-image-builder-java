package com.github.guoyyit.containerimagebuilderjava.builder.build;

import com.github.guoyyit.containerimagebuilderjava.builder.TestFixtures;
import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageBuildServiceTest {

    @TempDir
    Path tempDir;

    private BuilderProperties props;
    private ImageBuildService service;

    @BeforeEach
    void setUp() throws Exception {
        props = TestFixtures.properties(tempDir);
        TestFixtures.createBaseImage(props, "mybase", "1.0");
        service = new ImageBuildService(props);
    }

    @Test
    void buildsImageWithCopyLayers() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx.resolve("etc"));
        Files.writeString(ctx.resolve("app.jar"), "jar-bytes");
        Files.writeString(ctx.resolve("etc/app.conf"), "conf");

        Path desc = TestFixtures.writeDescription(tempDir,
                "FROM mybase:1.0\nCOPY app.jar /app/app.jar\nCOPY etc/app.conf /etc/app.conf\n");

        BuildResult result = service.build(desc, ctx, "myapp:1.0");

        assertNotNull(result.imageId());
        assertEquals(2, result.layerDigests().size());
        assertEquals(2, result.layersRebuilt());
        assertEquals(0, result.layersReused());

        // package exists and verifies
        ImageStore store = new ImageStore(props.getOutputDir());
        ImageStore.LoadedImage loaded = store.load(ImageStore.sanitize("myapp:1.0"));
        ImageManifest manifest = loaded.manifest();
        assertEquals(4, manifest.layers().size()); // 2 base + 2 copy
        assertEquals(manifest.layers().get(2).digest(), result.layerDigests().get(0));
        assertEquals(manifest.layers().get(3).digest(), result.layerDigests().get(1));

        // layer order is the copy order
        List<String> layerNames = manifest.layers().stream().map(ImageManifest.Descriptor::digest).toList();
        assertEquals(result.layerDigests(), layerNames.subList(2, 4));

        // image id is the manifest digest
        assertEquals(ImageStore.imageId(manifest), result.imageId());
    }

    @Test
    void layerContentIsVerifiable() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("f.txt"), "content");
        Path desc = TestFixtures.writeDescription(tempDir, "FROM mybase:1.0\nCOPY f.txt /f.txt\n");

        BuildResult result = service.build(desc, ctx, "verify:1");

        // the published layer blob in the package matches its digest byte-for-byte
        ImageStore store = new ImageStore(props.getOutputDir());
        ImageStore.LoadedImage loaded = store.load(ImageStore.sanitize("verify:1"));
        String layerDigest = result.layerDigests().get(0);
        byte[] blobBytes = Files.readAllBytes(loaded.blobPath(layerDigest));
        assertEquals(layerDigest, com.github.guoyyit.containerimagebuilderjava.builder.util.Digests.sha256(blobBytes));

        // the layer contains the copied file
        String tar = new String(blobBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(tar.contains("f.txt"));
    }

    @Test
    void reusedLayersAreNotRebuilt() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("a.txt"), "a");
        Files.writeString(ctx.resolve("b.txt"), "b");
        Path desc = TestFixtures.writeDescription(tempDir,
                "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY b.txt /b.txt\n");

        BuildResult first = service.build(desc, ctx, "reuse:1");
        assertEquals(2, first.layersRebuilt());

        BuildResult second = service.build(desc, ctx, "reuse:2");
        assertEquals(0, second.layersRebuilt());
        assertEquals(2, second.layersReused());
        assertArrayEquals(first.layerDigests().toArray(), second.layerDigests().toArray());
        assertEquals(first.imageId(), second.imageId());
    }
}
