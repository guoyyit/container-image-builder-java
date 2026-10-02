package com.github.guoyyit.containerimagebuilderjava.digest;

import com.github.guoyyit.containerimagebuilderjava.model.Digest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Digests {
    private Digests() {
    }

    public static Digest sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    public static Digest sha256Hex(byte[] content) {
        return new Digest(toHex(newMessageDigest().digest(content)));
    }

    public static MessageDigest newMessageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static Digest toDigest(MessageDigest digest) {
        return new Digest(toHex(digest.digest()));
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static Digest sha256File(Path file) {
        MessageDigest digest = newMessageDigest();
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new com.github.guoyyit.containerimagebuilderjava.exception.BuildException(
                    "failed to hash file: " + file, e);
        }
        return toDigest(digest);
    }
}
