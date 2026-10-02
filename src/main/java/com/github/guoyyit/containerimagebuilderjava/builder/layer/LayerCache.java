package com.github.guoyyit.containerimagebuilderjava.builder.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Content-addressed layer cache.
 *
 * <p>Layers are stored as {@code blobs/sha256/<digest>} and published atomically
 * (temp file + rename), so a half-written layer is never visible. An index maps
 * a stable <em>input key</em> (parent digest + instruction + content hashes) to
 * the layer digest; a layer is therefore built at most once per input key, and
 * identical layer content from different keys is still deduplicated by digest.
 *
 * <p>A global read/write lock coordinates builds (read lock) with garbage
 * collection (write lock): GC can never delete a blob a build is referencing.
 */
public class LayerCache {

    private static final Logger log = LoggerFactory.getLogger(LayerCache.class);
    private static final String BLOBS_DIR = "blobs";
    private static final String STAGING_DIR = "staging";
    private static final String INDEX_FILE = "layers-index.json";

    private final Path cacheDir;
    private final Path blobsDir;
    private final Path stagingDir;
    private final Path indexFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /** input key -> layer digest (tar content hash). */
    private final Map<String, String> index = new ConcurrentHashMap<>();

    /** Per-input-key locks: only one build computes a given layer. */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    /** Read lock held during builds; write lock held during GC. */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

    public LayerCache(Path cacheDir) throws IOException {
        this.cacheDir = cacheDir;
        this.blobsDir = cacheDir.resolve(BLOBS_DIR);
        this.stagingDir = cacheDir.resolve(STAGING_DIR);
        this.indexFile = cacheDir.resolve(INDEX_FILE);
        Files.createDirectories(blobsDir);
        Files.createDirectories(stagingDir);
        loadIndex();
    }

    /** Builds a layer tar into a staging file and returns its sha256 digest. */
    @FunctionalInterface
    public interface LayerBuilder {
        String build(Path stagingFile) throws IOException;
    }

    /** Result of a layer lookup: the layer and whether it was newly built. */
    public record ComputedLayer(Layer layer, boolean built) {
    }

    /**
     * Returns the layer for {@code inputKey}, computing it once if absent.
     *
     * @param inputKey stable hash of parent digest, instruction and file hashes
     * @param builder  writes the layer tar to the provided staging file
     */
    public ComputedLayer getOrCompute(String inputKey, LayerBuilder builder) throws IOException {
        Object keyLock = keyLocks.computeIfAbsent(inputKey, k -> new Object());
        synchronized (keyLock) {
            String digest = index.get(inputKey);
            if (digest != null) {
                Path blob = blobPath(digest);
                if (Files.isRegularFile(blob)) {
                    touch(blob);
                    return new ComputedLayer(new Layer(digest, Files.size(blob), blob), false);
                }
                index.remove(inputKey);
            }

            Path staging = stagingDir.resolve(UUID.randomUUID() + ".tmp");
            try {
                String builtDigest = builder.build(staging);
                Path blob = blobPath(builtDigest);
                if (Files.isRegularFile(blob)) {
                    // same content already present from another input key
                    Files.deleteIfExists(staging);
                } else {
                    Files.move(staging, blob, StandardCopyOption.ATOMIC_MOVE);
                    fsyncDir(blobsDir);
                }
                index.put(inputKey, builtDigest);
                persistIndex();
                touch(blob);
                return new ComputedLayer(new Layer(builtDigest, Files.size(blob), blob), true);
            } catch (RuntimeException | IOException e) {
                deleteQuietly(staging);
                throw e;
            }
        }
    }

    /**
     * Imports a blob from outside the cache (e.g. a base image layer), verifying
     * its digest. No-op if the blob is already present.
     */
    public Layer importBlob(String expectedDigest, Path source) throws IOException {
        Path blob = blobPath(expectedDigest);
        if (Files.isRegularFile(blob)) {
            String actual = sha256(blob);
            if (!actual.equals(expectedDigest)) {
                throw new IOException("corrupt blob in cache: " + expectedDigest);
            }
            touch(blob);
            return new Layer(expectedDigest, Files.size(blob), blob);
        }
        Path staging = stagingDir.resolve(UUID.randomUUID() + ".tmp");
        try {
            Files.copy(source, staging);
            String actual = sha256(staging);
            if (!actual.equals(expectedDigest)) {
                throw new IOException("digest mismatch while importing " + expectedDigest
                        + ", got " + actual);
            }
            Files.move(staging, blob, StandardCopyOption.ATOMIC_MOVE);
            fsyncDir(blobsDir);
            return new Layer(expectedDigest, Files.size(blob), blob);
        } catch (RuntimeException | IOException e) {
            deleteQuietly(staging);
            throw e;
        }
    }

    /**
     * Imports in-memory blob data (e.g. the image config), verifying its digest.
     * No-op if the blob is already present.
     */
    public Layer importBytes(String expectedDigest, byte[] data) throws IOException {
        Path blob = blobPath(expectedDigest);
        if (Files.isRegularFile(blob)) {
            return new Layer(expectedDigest, Files.size(blob), blob);
        }
        Path staging = stagingDir.resolve(UUID.randomUUID() + ".tmp");
        try {
            Files.write(staging, data);
            String actual = sha256(staging);
            if (!actual.equals(expectedDigest)) {
                throw new IOException("digest mismatch while importing " + expectedDigest
                        + ", got " + actual);
            }
            Files.move(staging, blob, StandardCopyOption.ATOMIC_MOVE);
            fsyncDir(blobsDir);
            return new Layer(expectedDigest, Files.size(blob), blob);
        } catch (RuntimeException | IOException e) {
            deleteQuietly(staging);
            throw e;
        }
    }

    public Path blobPath(String digest) {
        return blobsDir.resolve(digest);
    }

    public boolean hasBlob(String digest) {
        return Files.isRegularFile(blobPath(digest));
    }

    public long blobSize(String digest) throws IOException {
        return Files.size(blobPath(digest));
    }

    public List<Path> listBlobs() throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(blobsDir)) {
            stream.filter(Files::isRegularFile).forEach(result::add);
        }
        return result;
    }

    public void deleteBlob(String digest) throws IOException {
        Files.deleteIfExists(blobPath(digest));
    }

    public Optional<String> digestForInputKey(String inputKey) {
        return Optional.ofNullable(index.get(inputKey));
    }

    /** All input keys currently recorded in the index. */
    public java.util.Set<String> indexKeys() {
        return java.util.Set.copyOf(index.keySet());
    }

    public void removeIndexEntry(String inputKey) {
        if (index.remove(inputKey) != null) {
            try {
                persistIndex();
            } catch (IOException e) {
                log.warn("failed to persist layer index after removal of {}", inputKey, e);
            }
        }
    }

    public void withReadLock(Runnable action) {
        rwLock.readLock().lock();
        try {
            action.run();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public <T> T withReadLock(Supplier<T> action) {
        rwLock.readLock().lock();
        try {
            return action.get();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void withWriteLock(Runnable action) {
        rwLock.writeLock().lock();
        try {
            action.run();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public <T> T withWriteLock(Supplier<T> action) {
        rwLock.writeLock().lock();
        try {
            return action.get();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Deletes staging temp files older than the given grace period. */
    public int cleanStaleStaging(long graceMs) throws IOException {
        long cutoff = System.currentTimeMillis() - graceMs;
        int removed = 0;
        try (var stream = Files.list(stagingDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                FileTime mtime = Files.getLastModifiedTime(p);
                if (mtime.toMillis() < cutoff) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Updates the last-access time used for LRU eviction. */
    public void touch(Path blob) {
        try {
            Files.getFileAttributeView(blob, BasicFileAttributeView.class)
                    .setTimes(null, FileTime.fromMillis(System.currentTimeMillis()), null);
        } catch (IOException e) {
            log.debug("failed to touch {}", blob, e);
        }
    }

    /** Total size of all blobs in bytes. */
    public long totalBlobSize() throws IOException {
        long total = 0;
        for (Path p : listBlobs()) {
            total += Files.size(p);
        }
        return total;
    }

    private void loadIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        try {
            var root = mapper.readTree(indexFile.toFile());
            var layers = root.get("layers");
            if (layers != null) {
                layers.fields().forEachRemaining(e -> index.put(e.getKey(), e.getValue().asText()));
            }
        } catch (IOException e) {
            log.warn("failed to load layer index, starting empty: {}", e.toString());
            index.clear();
        }
    }

    private synchronized void persistIndex() throws IOException {
        Map<String, Object> root = Map.of("version", 1, "layers", Map.copyOf(index));
        Path tmp = indexFile.resolveSibling(indexFile.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(tmp, mapper.writeValueAsString(root));
        Files.move(tmp, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (var in = Files.newInputStream(path)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fsyncDir(Path dir) throws IOException {
        try (var ch = java.nio.channels.FileChannel.open(dir)) {
            ch.force(true);
        } catch (IOException e) {
            // directory fsync is best-effort on some platforms
            log.debug("fsync of {} failed", dir, e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("failed to delete {}", p, e);
        }
    }
}
