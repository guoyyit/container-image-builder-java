package com.github.guoyyit.containerimagebuilderjava.builder.tar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarWriterTest {

    @TempDir
    Path tempDir;

    private byte[] writeTar(Path root) throws Exception {
        List<TarWriter.Entry> entries = new ArrayList<>();
        entries.add(TarWriter.Entry.directory("etc", 0755));
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                entries.add(TarWriter.Entry.file(name, 0644, p));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new TarWriter().write(entries, out);
        return out.toByteArray();
    }

    @Test
    void tarIsDeterministicAndNormalized() throws Exception {
        Path ctx = tempDir.resolve("ctx");
        Files.createDirectories(ctx.resolve("etc"));
        Files.writeString(ctx.resolve("etc/a.txt"), "hello");
        Files.writeString(ctx.resolve("b.txt"), "world");

        byte[] first = writeTar(ctx);
        byte[] second = writeTar(ctx);
        assertArrayEquals(first, second, "tar bytes must be identical across writes");

        // parse headers
        int offset = 0;
        List<String> names = new ArrayList<>();
        while (offset < first.length) {
            byte[] header = new byte[512];
            System.arraycopy(first, offset, header, 0, 512);
            if (allZero(header)) {
                break;
            }
            String name = new String(header, 0, 100, StandardCharsets.UTF_8).trim();
            names.add(name);
            assertEquals("ustar", new String(header, 257, 5, StandardCharsets.UTF_8), "magic");
            assertEquals("00", new String(header, 263, 2, StandardCharsets.UTF_8), "version");
            assertEquals(0, octal(header, 136, 12), "mtime must be epoch");
            assertEquals(0, octal(header, 108, 8), "uid must be root");
            assertEquals(0, octal(header, 116, 8), "gid must be root");
            long size = octal(header, 124, 12);
            offset += 512 + ((size + 511) / 512) * 512;
        }
        assertEquals(List.of("b.txt", "etc/", "etc/a.txt"), names);
        // archive ends with two zero blocks
        assertEquals(0, first[first.length - 1]);
        assertEquals(0, first[first.length - 512]);
    }

    private static boolean allZero(byte[] b) {
        for (byte value : b) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static long octal(byte[] header, int offset, int len) {
        String s = new String(header, offset, len, StandardCharsets.US_ASCII).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }
}
