package com.github.guoyyit.containerimagebuilderjava.builder.gc;

import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reclaims unreferenced layers.
 *
 * <p>Garbage collection runs under the layer cache write lock, so it can never
 * delete a blob a concurrent build is referencing. A blob is <em>referenced</em>
 * if it is the config or a layer of any image package in the output directory or
 * the base image directory. Unreferenced blobs are always removed; when a maximum
 * cache size is configured, unreferenced blobs are removed oldest-access-first
 * (LRU) until the size limit is met. Staging temp files older than the configured
 * grace period are also removed.
 */
public class GarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(GarbageCollector.class);

    private final BuilderProperties properties;
    private final LayerCache layerCache;
    private final ImageStore outputStore;
    private final ImageStore baseStore;

    public GarbageCollector(BuilderProperties properties, LayerCache layerCache,
                            ImageStore outputStore, ImageStore baseStore) {
        this.properties = properties;
        this.layerCache = layerCache;
        this.outputStore = outputStore;
        this.baseStore = baseStore;
    }

    /** Result of a garbage collection run. */
    public record GcStats(int deletedBlobs, long freedBytes, long remainingBlobs,
                          long remainingBytes, int stagingRemoved) {
    }

    /** Runs garbage collection. */
    public GcStats gc() throws IOException {
        return layerCache.withWriteLock(() -> {
            try {
                return doGc();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private GcStats doGc() throws IOException {
        Set<String> referenced = scanReferenced();

        List<Path> blobs = new ArrayList<>(layerCache.listBlobs());
        long total = 0;
        for (Path b : blobs) {
            total += Files.size(b);
        }

        // candidates: unreferenced blobs, oldest access first
        List<Path> candidates = new ArrayList<>();
        for (Path b : blobs) {
            String digest = b.getFileName().toString();
            if (!referenced.contains(digest)) {
                candidates.add(b);
            }
        }
        candidates.sort(Comparator.comparingLong(GarbageCollector::lastAccessTime));

        long maxSize = properties.getMaxCacheSizeBytes();
        int deleted = 0;
        long freed = 0;
        for (Path candidate : candidates) {
            if (maxSize > 0 && total <= maxSize) {
                break;
            }
            long size = Files.size(candidate);
            layerCache.deleteBlob(candidate.getFileName().toString());
            total -= size;
            freed += size;
            deleted++;
        }

        // drop index entries whose blobs are gone (keeps metadata self-consistent)
        for (String inputKey : layerCache.indexKeys()) {
            String digest = layerCache.digestForInputKey(inputKey).orElse(null);
            if (digest != null && !layerCache.hasBlob(digest)) {
                layerCache.removeIndexEntry(inputKey);
            }
        }

        int stagingRemoved = layerCache.cleanStaleStaging(properties.getStagingGraceMs());

        long remaining = 0;
        for (Path b : layerCache.listBlobs()) {
            remaining += Files.size(b);
        }
        log.info("gc: deleted {} blobs ({} bytes), remaining {} blobs ({} bytes), staging removed {}",
                deleted, freed, layerCache.listBlobs().size(), remaining, stagingRemoved);
        return new GcStats(deleted, freed, layerCache.listBlobs().size(), remaining, stagingRemoved);
    }

    /** Scans all image packages (output + base) for referenced config/layer digests. */
    private Set<String> scanReferenced() throws IOException {
        Set<String> referenced = new HashSet<>();
        scanStore(outputStore, referenced);
        scanStore(baseStore, referenced);
        return referenced;
    }

    private void scanStore(ImageStore store, Set<String> referenced) throws IOException {
        for (String dirName : store.listDirs()) {
            Path packageDir = store.packageDir(dirName);
            try {
                ImageManifest manifest = store.readManifest(packageDir);
                referenced.add(manifest.config().digest());
                for (ImageManifest.Descriptor layer : manifest.layers()) {
                    referenced.add(layer.digest());
                }
            } catch (IOException e) {
                log.warn("skipping unreadable image package {}: {}", packageDir, e.toString());
            }
        }
    }

    private static long lastAccessTime(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            return attrs.lastAccessTime().toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
