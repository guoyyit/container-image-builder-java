package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.build.BuildCancellationToken;
import com.github.guoyyit.containerimagebuilderjava.exception.BuildCancelledException;
import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;
import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailureAndCancellationTest extends AbstractBuildTest {

    @BeforeEach
    void init() throws Exception {
        setUp();
    }

    @AfterEach
    void clean() throws Exception {
        tearDown();
    }

    @Test
    void missingSourceFailsWithoutCommittingAnyImage() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "a/f.txt", "first");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY a /a\nCOPY missing /m\n");

        BuildException error = assertThrows(BuildException.class, () ->
                service.build(new BuildRequest("demo", descriptor, context)));
        assertFalse(error instanceof BuildCancelledException);
        assertFalse(service.deleteImage("demo"));

        try (var stream = Files.list(cacheDir.resolve("tmp"))) {
            assertEquals(0, stream.filter(Files::isRegularFile).count());
        }
        try (var stream = Files.list(cacheDir.resolve("layers"))) {
            assertEquals(1, stream.count());
        }

        Path fixedDescriptor = createDescriptor("fixed.desc",
                "FROM scratch\nCOPY a /a\n");
        BuildResult result = service.build(new BuildRequest("demo", fixedDescriptor, context));
        assertEquals(1, result.layerChainIds().size());
        assertEquals(0, service.garbageCollect());
    }

    @Test
    void preCancelledBuildDoesNotCreateLayersOrImage() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "a/f.txt", "first");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY a /a\n");

        BuildCancellationToken token = new BuildCancellationToken();
        token.cancel();

        assertThrows(BuildCancelledException.class, () ->
                service.build(new BuildRequest("cancelled", descriptor, context), token));

        assertFalse(service.deleteImage("cancelled"));
        try (var stream = Files.list(cacheDir.resolve("layers"))) {
            assertEquals(0, stream.count());
        }
    }

    @Test
    void cancellationBetweenLayersKeepsCacheSelfConsistent() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "a/f.txt", "first");
        writeFile(context, "b/g.txt", "second");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY a /a\nCOPY b /b\n");

        BuildCancellationToken token = new BuildCancellationToken();
        token.cancel();
        assertThrows(BuildCancelledException.class, () ->
                service.build(new BuildRequest("cancelled", descriptor, context), token));

        BuildResult successful = service.build(new BuildRequest("ok", descriptor, context));
        assertEquals(2, successful.layerChainIds().size());
        assertEquals(0, service.garbageCollect());
    }

    @Test
    void contextTraversalIsRejected() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "a/f.txt", "first");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY ../outside /x\n");
        assertThrows(BuildException.class, () ->
                service.build(new BuildRequest("evil", descriptor, context)));
    }
}
