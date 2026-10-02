package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.config.CacheConfig;
import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;
import com.github.guoyyit.containerimagebuilderjava.service.ImageBuildService;
import com.github.guoyyit.containerimagebuilderjava.storage.DefaultImageStore;
import com.github.guoyyit.containerimagebuilderjava.storage.DefaultLayerStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReproducibilityTest extends AbstractBuildTest {

    private Path secondCacheDir;
    private ImageBuildService secondService;

    @BeforeEach
    void init() throws Exception {
        setUp();
        secondCacheDir = tempDir.resolve("cache2");
        CacheConfig config = new CacheConfig();
        config.setRootDir(secondCacheDir);
        secondService = new ImageBuildService(config,
                new DefaultLayerStore(config), new DefaultImageStore(config));
    }

    @AfterEach
    void clean() throws Exception {
        tearDown();
    }

    @Test
    void independentCachesAndBuildTimesProduceIdenticalArtifacts() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "dir/sub/file.txt", "hello\n");
        writeFile(context, "root.txt", "world");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY dir /dir\nCOPY root.txt /root.txt\n");
        BuildRequest request = new BuildRequest("repro", descriptor, context);

        BuildResult first = service.build(request);
        Thread.sleep(1100);
        BuildResult second = secondService.build(request);

        assertEquals(first.imageId(), second.imageId());
        assertEquals(first.layerChainIds(), second.layerChainIds());

        for (int i = 0; i < first.layerChainIds().size(); i++) {
            Path firstTar = cacheDir.resolve("layers")
                    .resolve(first.layerChainIds().get(i).value()).resolve("layer.tar");
            Path secondTar = secondCacheDir.resolve("layers")
                    .resolve(second.layerChainIds().get(i).value()).resolve("layer.tar");
            assertArrayEquals(Files.readAllBytes(firstTar), Files.readAllBytes(secondTar));
        }

        Path manifestA = cacheDir.resolve("images").resolve(first.imageId().value() + ".json");
        Path manifestB = secondCacheDir.resolve("images").resolve(second.imageId().value() + ".json");
        assertEquals(Files.readString(manifestA), Files.readString(manifestB));
    }

    @Test
    void mtimeDoesNotChangeLayerIdentity() throws Exception {
        Path context = createContext("ctx");
        Path file = writeFile(context, "f.txt", "same-bytes");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY f.txt /f.txt\n");

        BuildResult first = service.build(new BuildRequest("img", descriptor, context));
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(0));
        BuildResult second = service.build(new BuildRequest("img", descriptor, context));

        assertEquals(first.imageId(), second.imageId());
        assertEquals(first.layerChainIds(), second.layerChainIds());
    }
}
