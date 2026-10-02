package com.github.guoyyit.containerimagebuilderjava.tests;

import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerArchiveTest extends AbstractBuildTest {

    @BeforeEach
    void init() throws Exception {
        setUp();
    }

    @AfterEach
    void clean() throws Exception {
        tearDown();
    }

    @Test
    void tarContainsSortedEntriesWithZeroMtimes() throws Exception {
        Path context = createContext("ctx");
        writeFile(context, "app/sub/a.txt", "aaa");
        writeFile(context, "app/b.txt", "bbbb");
        Path descriptor = createDescriptor("image.desc",
                "FROM scratch\nCOPY app /app\n");

        BuildResult result = service.build(new BuildRequest("demo", descriptor, context));
        Path tar = cacheDir.resolve("layers")
                .resolve(result.layerChainIds().get(0).value()).resolve("layer.tar");

        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(tar));
                TarArchiveInputStream tarIn = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            String previous = "";
            while ((entry = tarIn.getNextEntry()) != null) {
                assertTrue(entry.getName().compareTo(previous) >= 0,
                        "entries must be sorted: " + previous + " before " + entry.getName());
                previous = entry.getName();
                assertEquals(0L, entry.getModTime().getTime());
                assertEquals(0, entry.getLongUserId());
                assertEquals(0, entry.getLongGroupId());
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    tarIn.transferTo(content);
                    files.put(entry.getName(), content.toByteArray());
                }
            }
        }

        assertEquals(2, files.size());
        assertEquals("aaa", new String(files.get("app/sub/a.txt")));
        assertEquals("bbbb", new String(files.get("app/b.txt")));
    }
}
