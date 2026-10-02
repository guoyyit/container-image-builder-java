package com.github.guoyyit.containerimagebuilderjava.builder.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.guoyyit.containerimagebuilderjava.builder.util.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stores and loads image packages.
 *
 * <p>An image package is a directory containing a deterministic {@code manifest.json}
 * and a {@code blobs/sha256/<digest>} file per config/layer. Blobs are hard-linked
 * from the layer cache when possible (copied otherwise), so the package is
 * self-contained while cache space is shared. Every load re-verifies each blob's
 * sha256 digest and the layer order.
 */
public class ImageStore {

    private static final Logger log = LoggerFactory.getLogger(ImageStore.class);
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String BLOBS_DIR = "blobs/sha256";

    private final Path rootDir;
    private final ObjectMapper mapper;

    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ImageStore(Path rootDir) throws IOException {
        this.rootDir = rootDir;
        Files.createDirectories(rootDir);
        this.mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** A loaded image package: its manifest plus the directory it lives in. */
    public record LoadedImage(ImageManifest manifest, Path packageDir) {

        public Path blobPath(String digest) {
            return packageDir.resolve(BLOBS_DIR).resolve(digest);
        }
    }

    /**
     * Writes an image package atomically.
     *
     * @param dirName      directory name (already sanitized) for the package
     * @param manifest     deterministic manifest
     * @param blobSources  cache blob paths for config and layers, in order
     */
    public void save(String dirName, ImageManifest manifest, List<Path> blobSources) throws IOException {
        Path target = packageDir(dirName);
        Path staging = rootDir.resolve(".tmp-" + UUID.randomUUID());
        Files.createDirectories(staging.resolve(BLOBS_DIR));
        try {
            Files.writeString(staging.resolve(MANIFEST_FILE), toJson(manifest));
            List<String> digests = new ArrayList<>();
            digests.add(manifest.config().digest());
            manifest.layers().forEach(d -> digests.add(d.digest()));
            for (int i = 0; i < digests.size(); i++) {
                linkOrCopy(blobSources.get(i), staging.resolve(BLOBS_DIR).resolve(digests.get(i)));
            }
            if (Files.exists(target)) {
                Path backup = rootDir.resolve(".old-" + UUID.randomUUID());
                Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                    throw e;
                }
                deleteRecursively(backup);
            } else {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (RuntimeException | IOException e) {
            deleteRecursively(staging);
            throw e;
        }
    }

    /** Loads and verifies an image package. */
    public LoadedImage load(String dirName) throws IOException {
        return load(packageDir(dirName));
    }

    /** Reads the manifest of a package without verifying its blobs. */
    public ImageManifest readManifest(Path packageDir) throws IOException {
        Path manifestFile = packageDir.resolve(MANIFEST_FILE);
        try {
            return mapper.readValue(manifestFile.toFile(), ImageManifest.class);
        } catch (IOException e) {
            throw new IOException("corrupt manifest in " + packageDir, e);
        }
    }

    /** Loads and verifies an image package at an arbitrary path. */
    public LoadedImage load(Path packageDir) throws IOException {
        Path manifestFile = packageDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("image package not found: " + packageDir);
        }
        ImageManifest manifest;
        try {
            manifest = mapper.readValue(manifestFile.toFile(), ImageManifest.class);
        } catch (IOException e) {
            throw new IOException("corrupt manifest in " + packageDir, e);
        }
        verify(manifest, packageDir);
        return new LoadedImage(manifest, packageDir);
    }

    /** Verifies every blob digest and the layer order. */
    public void verify(ImageManifest manifest, Path packageDir) throws IOException {
        Path blobs = packageDir.resolve(BLOBS_DIR);
        verifyBlob(manifest.config(), blobs);
        for (ImageManifest.Descriptor layer : manifest.layers()) {
            verifyBlob(layer, blobs);
        }
    }

    private void verifyBlob(ImageManifest.Descriptor descriptor, Path blobsDir) throws IOException {
        Path blob = blobsDir.resolve(descriptor.digest());
        if (!Files.isRegularFile(blob)) {
            throw new IOException("missing blob " + descriptor.digest());
        }
        String actual = Digests.sha256(blob);
        if (!actual.equals(descriptor.digest())) {
            throw new IOException("blob digest mismatch: expected " + descriptor.digest()
                    + " but was " + actual);
        }
        if (Files.size(blob) != descriptor.size()) {
            throw new IOException("blob size mismatch for " + descriptor.digest());
        }
    }

    public List<String> listDirs() throws IOException {
        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(rootDir)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.startsWith(".tmp-") && !name.startsWith(".old-"))
                    .forEach(result::add);
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    public void delete(String dirName) throws IOException {
        deleteRecursively(packageDir(dirName));
    }

    public Path packageDir(String dirName) {
        return rootDir.resolve(sanitize(dirName));
    }

    /** Sanitizes a tag/ref into a safe directory name. */
    public static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Deterministic JSON: sorted keys, no volatile fields. */
    public static String toJson(ImageManifest manifest) throws IOException {
        Map<String, Object> root = new TreeMap<>();
        root.put("schemaVersion", manifest.schemaVersion());
        root.put("mediaType", manifest.mediaType());
        root.put("config", descriptorMap(manifest.config()));
        List<Object> layers = new ArrayList<>();
        manifest.layers().forEach(d -> layers.add(descriptorMap(d)));
        root.put("layers", layers);
        return STATIC_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** Image identifier: sha256 of the deterministic manifest bytes. */
    public static String imageId(ImageManifest manifest) {
        try {
            byte[] bytes = toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> descriptorMap(ImageManifest.Descriptor d) {
        Map<String, Object> map = new TreeMap<>();
        map.put("mediaType", d.mediaType());
        map.put("digest", d.digest());
        map.put("size", d.size());
        return map;
    }

    private static void linkOrCopy(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
        } catch (IOException e) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("failed to delete {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("failed to walk {}", root, e);
        }
    }
}
