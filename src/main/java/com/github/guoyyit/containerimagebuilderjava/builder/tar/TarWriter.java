package com.github.guoyyit.containerimagebuilderjava.builder.tar;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes deterministic, reproducible POSIX (ustar) tar archives.
 *
 * <p>All volatile metadata (mtime, uid, gid, names) is normalized so that the
 * same logical content always produces byte-identical archives:
 * <ul>
 *   <li>entries are sorted by name;</li>
 *   <li>mtime is always the epoch (0);</li>
 *   <li>uid/gid are always 0 (root), uname/gname are {@code root};</li>
 *   <li>file mode bits are preserved but nothing else.</li>
 * </ul>
 */
public class TarWriter {

    private static final int BLOCK = 512;
    private static final byte[] ZERO_BLOCK = new byte[BLOCK];
    private static final byte[] GNU_LONG_LINK = "././@LongLink".getBytes(StandardCharsets.UTF_8);

    /** A file or directory entry to be written into the tar. */
    public record Entry(String name, int mode, Path source, boolean directory) {

        public static Entry file(String name, int mode, Path source) {
            return new Entry(name, mode, source, false);
        }

        public static Entry directory(String name, int mode) {
            String n = name.endsWith("/") ? name : name + "/";
            return new Entry(n, mode, null, true);
        }
    }

    /**
     * Writes all entries (sorted by name) followed by two zero blocks.
     */
    public void write(List<Entry> entries, OutputStream out) throws IOException {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(e -> e.name));
        for (Entry entry : sorted) {
            writeEntry(entry, out);
        }
        out.write(ZERO_BLOCK);
        out.write(ZERO_BLOCK);
    }

    private void writeEntry(Entry entry, OutputStream out) throws IOException {
        byte[] nameBytes = entry.name.getBytes(StandardCharsets.UTF_8);
        long size = entry.directory ? 0 : Files.size(entry.source);
        int mode = entry.directory ? entry.mode : entry.mode & 07777;

        if (nameBytes.length <= 100) {
            writeHeader(out, nameBytes, mode, size, entry.directory ? (byte) '5' : (byte) '0');
        } else {
            String prefix = splitPrefix(entry.name);
            if (prefix != null) {
                String base = entry.name.substring(prefix.length() + 1);
                writeHeader(out, base.getBytes(StandardCharsets.UTF_8), prefix.getBytes(StandardCharsets.UTF_8),
                        mode, size, entry.directory ? (byte) '5' : (byte) '0');
            } else {
                writeGnuLongName(out, entry.name);
                writeHeader(out, GNU_LONG_LINK, mode, size, entry.directory ? (byte) '5' : (byte) '0');
            }
        }

        if (!entry.directory) {
            Files.copy(entry.source, out);
            long remainder = size % BLOCK;
            if (remainder != 0) {
                out.write(new byte[(int) (BLOCK - remainder)]);
            }
        }
    }

    private void writeGnuLongName(OutputStream out, String name) throws IOException {
        byte[] data = name.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[data.length + 1];
        System.arraycopy(data, 0, content, 0, data.length);
        writeHeader(out, GNU_LONG_LINK, 0, content.length, (byte) 'L');
        out.write(content);
        long remainder = content.length % BLOCK;
        if (remainder != 0) {
            out.write(new byte[(int) (BLOCK - remainder)]);
        }
    }

    private void writeHeader(OutputStream out, byte[] name, int mode, long size, byte typeflag)
            throws IOException {
        writeHeader(out, name, null, mode, size, typeflag);
    }

    private void writeHeader(OutputStream out, byte[] name, byte[] prefix, int mode, long size,
                             byte typeflag) throws IOException {
        byte[] header = new byte[BLOCK];

        putString(header, 0, 100, name);
        putOctal(header, 100, 8, mode);
        putOctal(header, 108, 8, 0); // uid
        putOctal(header, 116, 8, 0); // gid
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, 0); // mtime: epoch
        // checksum field (148..155) filled with spaces during calculation
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = typeflag;
        putString(header, 257, 6, "ustar".getBytes(StandardCharsets.UTF_8));
        header[263] = '0';
        header[264] = '0';
        putString(header, 265, 32, "root".getBytes(StandardCharsets.UTF_8));
        putString(header, 297, 32, "root".getBytes(StandardCharsets.UTF_8));
        if (prefix != null) {
            putString(header, 345, 155, prefix);
        }

        int checksum = 0;
        for (int i = 0; i < BLOCK; i++) {
            checksum += header[i] & 0xff;
        }
        putChecksum(header, checksum);
        out.write(header);
    }

    private static void putString(byte[] header, int offset, int maxLen, byte[] value) {
        int len = Math.min(value.length, maxLen);
        System.arraycopy(value, 0, header, offset, len);
    }

    private static void putOctal(byte[] header, int offset, int fieldLen, long value) {
        String s = Long.toOctalString(value);
        int digits = fieldLen - 1;
        if (s.length() > digits) {
            throw new IllegalArgumentException("value too large for octal field at " + offset);
        }
        int start = offset + digits - s.length();
        for (int i = offset; i < offset + digits; i++) {
            header[i] = '0';
        }
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, start, bytes.length);
        header[offset + fieldLen - 1] = 0;
    }

    private static void putChecksum(byte[] header, int checksum) {
        String s = String.format("%06o", checksum);
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, 148, 6);
        header[154] = 0;
        header[155] = ' ';
    }

    /**
     * Splits a long name into (prefix, base) at a slash so that prefix fits in
     * 155 bytes and base in 100 bytes. Returns null if impossible.
     */
    private static String splitPrefix(String name) {
        for (int i = name.length() - 1; i >= 0; i--) {
            if (name.charAt(i) == '/') {
                String prefix = name.substring(0, i);
                String base = name.substring(i + 1);
                if (prefix.getBytes(StandardCharsets.UTF_8).length <= 155
                        && base.getBytes(StandardCharsets.UTF_8).length <= 100) {
                    return prefix;
                }
            }
        }
        return null;
    }
}
