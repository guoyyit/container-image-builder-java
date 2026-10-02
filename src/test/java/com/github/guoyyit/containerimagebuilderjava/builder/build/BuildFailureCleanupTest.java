package com.github.guoyyit.containerimagebuilderjava.builder.build;

import com.github.guoyyit.containerimagebuilderjava.builder.TestFixtures;
import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildFailureCleanupTest {

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
    void failedBuildLeavesNoStagingOrPackage() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("good.txt"), "good");
        Path desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nCOPY good.txt /good.txt\nCOPY missing.txt /missing.txt\n");

        assertThrows(Exception.class, () -> service.build(desc, ctx, "fail:1"));

        // no staging leftovers
        Path staging = props.getCacheDir().resolve("staging");
        try (Stream<Path> stream = Files.list(staging)) {
            assertEquals(0, stream.count(), "staging must be empty after failure");
        }

        // no image package
        Path pkg = props.getOutputDir().resolve(ImageStore.sanitize("fail:1"));
        assertFalse(Files.exists(pkg), "no image package may remain after failure");

        // completed layer (good.txt) is still in cache and reusable
        Path fixed = TestFixtures.writeDescription(tempDir.resolve("d2"),
                "FROM mybase:1.0\nCOPY good.txt /good.txt\n");
        BuildResult result = service.build(fixed, ctx, "fixed:1");
        assertEquals(1, result.layersReused(), "good.txt layer was completed in the failed run and is reused");
        assertEquals(0, result.layersRebuilt());
    }

    @Test
    void invalidInstructionFailsCleanly() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Path desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nRUN echo hi\n");

        assertThrows(IllegalArgumentException.class, () -> service.build(desc, ctx, "bad:1"));

        Path staging = props.getCacheDir().resolve("staging");
        try (Stream<Path> stream = Files.list(staging)) {
            assertEquals(0, stream.count());
        }
        assertFalse(Files.exists(props.getOutputDir().resolve(ImageStore.sanitize("bad:1"))));
    }

    @Test
    void secondBuildAfterFailureReusesCompletedLayers() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("a.txt"), "a");
        Files.writeString(ctx.resolve("b.txt"), "b");
        Path desc = TestFixtures.writeDescription(tempDir.resolve("d"),
                "FROM mybase:1.0\nCOPY a.txt /a.txt\nCOPY missing.txt /missing.txt\n");

        assertThrows(Exception.class, () -> service.build(desc, ctx, "fail:1"));

        // fix the context and rebuild
        Files.writeString(ctx.resolve("missing.txt"), "found");
        BuildResult result = service.build(desc, ctx, "fixed:1");

        // a.txt layer was completed in the failed run and is reused
        assertEquals(1, result.layersReused());
        assertEquals(1, result.layersRebuilt());
        assertTrue(Files.exists(props.getOutputDir().resolve(ImageStore.sanitize("fixed:1"))));
    }
}
