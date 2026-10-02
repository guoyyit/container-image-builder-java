package com.github.guoyyit.containerimagebuilderjava.builder.build;

import com.github.guoyyit.containerimagebuilderjava.builder.TestFixtures;
import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.Layer;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import com.github.guoyyit.containerimagebuilderjava.builder.tar.TarWriter;
import com.github.guoyyit.containerimagebuilderjava.builder.util.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReproducibilityTest {

    @TempDir
    Path tempDir;

    private BuilderProperties props;
    private Path ctx;
    private Path desc;

    @BeforeEach
    void setUp() throws Exception {
        props = TestFixtures.properties(tempDir);
        TestFixtures.createBaseImage(props, "mybase", "1.0");
        ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("a.txt"), "alpha");
        Files.writeString(ctx.resolve("b.txt"), "beta");
        desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY b.txt /b.txt\n");
    }

    @Test
    void sameInputProducesSameImageIdAndLayers() throws Exception {
        ImageBuildService service = new ImageBuildService(props);
        BuildResult first = service.build(desc, ctx, "repro:1");

        // rebuild with the same tag (replaces the package)
        BuildResult second = service.build(desc, ctx, "repro:1");

        assertEquals(first.imageId(), second.imageId());
        assertEquals(first.configDigest(), second.configDigest());
        assertEquals(first.layerDigests(), second.layerDigests());

        // manifest bytes are identical
        ImageStore store = new ImageStore(props.getOutputDir());
        byte[] manifest1 = Files.readAllBytes(store.packageDir(ImageStore.sanitize("repro:1")).resolve("manifest.json"));
        BuildResult third = service.build(desc, ctx, "repro:1");
        byte[] manifest2 = Files.readAllBytes(store.packageDir(ImageStore.sanitize("repro:1")).resolve("manifest.json"));
        assertArrayEquals(manifest1, manifest2);
    }

    @Test
    void freshCacheProducesSameImageId() throws Exception {
        ImageBuildService service = new ImageBuildService(props);
        BuildResult first = service.build(desc, ctx, "repro:1");

        // a second builder with a fresh cache but the same base + context
        BuilderProperties props2 = TestFixtures.properties(tempDir.resolve("fresh"));
        TestFixtures.createBaseImage(props2, "mybase", "1.0");
        ImageBuildService service2 = new ImageBuildService(props2);
        BuildResult second = service2.build(desc, ctx, "repro:2");

        assertEquals(first.imageId(), second.imageId());
        assertEquals(first.layerDigests(), second.layerDigests());
    }

    @Test
    void manifestAndConfigContainNoVolatileFields() throws Exception {
        ImageBuildService service = new ImageBuildService(props);
        BuildResult result = service.build(desc, ctx, "repro:1");

        ImageStore store = new ImageStore(props.getOutputDir());
        var loaded = store.load(ImageStore.sanitize("repro:1"));
        String manifestJson = Files.readString(loaded.packageDir().resolve("manifest.json"));

        // no timestamps or volatile metadata in the manifest
        assertFalse(manifestJson.contains("created"), "manifest must not contain 'created'");
        assertFalse(manifestJson.contains("timestamp"), "manifest must not contain timestamps");
        assertFalse(manifestJson.contains("Date"), "manifest must not contain dates");

        // config blob has no created field
        byte[] configBytes = Files.readAllBytes(loaded.blobPath(result.configDigest()));
        String configJson = new String(configBytes);
        assertFalse(configJson.contains("created"), "config must not contain 'created'");
        assertEquals(result.configDigest(), Digests.sha256(configBytes));

        // layer tars are deterministic: same content -> same bytes
        Path layerBlob = loaded.blobPath(result.layerDigests().get(0));
        byte[] tarBytes = Files.readAllBytes(layerBlob);
        // mtime is epoch (0) in every header
        assertTarMtimeIsEpoch(tarBytes);
    }

    @Test
    void tarBytesAreIdenticalAcrossWrites() throws Exception {
        Path src = tempDir.resolve("tarsrc");
        Files.createDirectories(src);
        Files.writeString(src.resolve("x.txt"), "x");

        byte[] t1 = TestFixtures.tar(List.of(
                TarWriter.Entry.directory("etc", 0755),
                TarWriter.Entry.file("etc/x.txt", 0644, src.resolve("x.txt"))));
        byte[] t2 = TestFixtures.tar(List.of(
                TarWriter.Entry.directory("etc", 0755),
                TarWriter.Entry.file("etc/x.txt", 0644, src.resolve("x.txt"))));
        assertArrayEquals(t1, t2);
    }

    private void assertTarMtimeIsEpoch(byte[] tar) {
        int offset = 0;
        while (offset < tar.length) {
            byte[] header = new byte[512];
            System.arraycopy(tar, offset, header, 0, 512);
            if (allZero(header)) {
                break;
            }
            // mtime field at 136..148 is octal; epoch = 0
            long mtime = Long.parseLong(new String(header, 136, 12).trim(), 8);
            assertEquals(0, mtime, "mtime must be epoch");
            long size = Long.parseLong(new String(header, 124, 12).trim(), 8);
            offset += 512 + ((size + 511) / 512) * 512;
        }
    }

    private static boolean allZero(byte[] b) {
        for (byte value : b) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
