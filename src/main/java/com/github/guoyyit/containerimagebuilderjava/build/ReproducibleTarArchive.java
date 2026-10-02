package com.github.guoyyit.containerimagebuilderjava.build;

import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal USTAR tar writer producing reproducible archives: fixed user/group,
 * zero modification times and entries written in caller-provided order.
 */
final class ReproducibleTarArchive {

    private static final int BLOCK_SIZE = 512;
    private static final int NAME_LENGTH = 100;
    private final OutputStream out;
    private boolean finished;

    ReproducibleTarArchive(OutputStream out) {
        this.out = out;
    }

    void writeDirectory(String entryPath) {
        String path = entryPath.endsWith("/") ? entryPath : entryPath + "/";
        if (path.length() > NAME_LENGTH) {
            throw new BuildException("tar entry path too long: " + path);
        }
        byte[] header = new byte[BLOCK_SIZE];
        putString(header, 0, NAME_LENGTH, path);
        putOctal(header, 100, 8, 0);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, 0L);
        putOctal(header, 136, 12, 0L);
        header[156] = '5';
        fillHeaderMagic(header);
        try {
            out.write(header);
        } catch (IOException e) {
            throw new BuildException("failed to write tar directory header for " + path, e);
        }
    }

    void writeFile(String entryPath, Path source) {
        long size;
        try {
            size = Files.size(source);
        } catch (IOException e) {
            throw new BuildException("failed to stat tar entry " + entryPath, e);
        }
        if (entryPath.length() > NAME_LENGTH) {
            throw new BuildException("tar entry path too long: " + entryPath);
        }
        byte[] header = new byte[BLOCK_SIZE];
        putString(header, 0, NAME_LENGTH, entryPath);
        putOctal(header, 100, 8, 0);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, 0L);
        header[156] = '0';
        fillHeaderMagic(header);
        try {
            out.write(header);
            long remaining = size;
            try (InputStream in = Files.newInputStream(source)) {
                byte[] buffer = new byte[8192];
                int read;
                while (remaining > 0 && (read = in.read(buffer, 0,
                        (int) Math.min(buffer.length, remaining))) != -1) {
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            if (remaining != 0) {
                throw new BuildException("size changed while writing tar entry " + entryPath);
            }
            int padding = (int) ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE);
            if (padding > 0) {
                out.write(new byte[padding]);
            }
        } catch (IOException e) {
            throw new BuildException("failed to write tar entry " + entryPath, e);
        }
    }

    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            out.write(new byte[BLOCK_SIZE * 2]);
            out.flush();
        } catch (IOException e) {
            throw new BuildException("failed to finalize tar archive", e);
        }
    }

    private void fillHeaderMagic(byte[] header) {
        putString(header, 257, 6, "ustar");
        header[263] = '0';
        header[264] = '0';
        putString(header, 265, 32, "");
        putString(header, 297, 32, "");
        putString(header, 329, 16, "");
        putString(header, 345, 16, "");
        setChecksum(header);
    }

    private void setChecksum(byte[] header) {
        long sum = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (i >= 148 && i < 156) {
                sum += ' ';
            } else {
                sum += header[i] & 0xFF;
            }
        }
        String value = String.format(java.util.Locale.ROOT, "%06o", sum) + '\0' + ' ';
        putString(header, 148, 8, value);
    }

    private void putString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > length) {
            throw new BuildException("tar field overflow at offset " + offset);
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private void putOctal(byte[] header, int offset, int length, long value) {
        String formatted = String.format(java.util.Locale.ROOT, "%0" + (length - 1) + "o", value);
        byte[] bytes = formatted.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length + 1 > length) {
            throw new BuildException("tar numeric field overflow at offset " + offset);
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + bytes.length] = 0;
    }
}
