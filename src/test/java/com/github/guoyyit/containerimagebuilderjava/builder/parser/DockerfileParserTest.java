package com.github.guoyyit.containerimagebuilderjava.builder.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerfileParserTest {

    @TempDir
    Path tempDir;

    private BuildDescription parse(String content) throws Exception {
        Path f = tempDir.resolve("Dockerfile");
        Files.writeString(f, content);
        return new DockerfileParser().parse(f);
    }

    @Test
    void parsesFromAndCopies() throws Exception {
        BuildDescription desc = parse("""
                # comment
                FROM mybase:1.0
                COPY app.jar /app/app.jar
                COPY config/ extra.txt \\
                     /opt/
                ADD data.tar /opt/data/
                """);
        assertEquals("mybase:1.0", desc.from().imageRef());
        assertEquals("mybase", desc.from().name());
        assertEquals("1.0", desc.from().tag());
        assertEquals(3, desc.copies().size());
        assertEquals("app.jar", desc.copies().get(0).sources().get(0));
        assertEquals("/app/app.jar", desc.copies().get(0).destination());
        assertEquals(2, desc.copies().get(1).sources().size());
        assertEquals("/opt/", desc.copies().get(1).destination());
        assertEquals("data.tar", desc.copies().get(2).sources().get(0));
    }

    @Test
    void defaultTagIsLatest() throws Exception {
        BuildDescription desc = parse("FROM mybase\nCOPY f /f\n");
        assertEquals("latest", desc.from().tag());
    }

    @Test
    void rejectsUnknownInstruction() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> parse("FROM base:1\nRUN echo hi\n"));
        assertTrue(ex.getMessage().contains("RUN"));
    }

    @Test
    void rejectsCopyBeforeFrom() {
        assertThrows(IllegalArgumentException.class, () -> parse("COPY f /f\nFROM base:1\n"));
    }

    @Test
    void rejectsMissingFrom() {
        assertThrows(IllegalArgumentException.class, () -> parse("COPY f /f\n"));
    }
}
