package com.github.guoyyit.containerimagebuilderjava.builder.api;

import com.github.guoyyit.containerimagebuilderjava.builder.build.BuildResult;
import com.github.guoyyit.containerimagebuilderjava.builder.build.ImageBuildService;
import com.github.guoyyit.containerimagebuilderjava.builder.gc.GarbageCollector;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST entry points for builds, image inspection and cache garbage collection.
 */
@RestController
@RequestMapping("/api")
public class BuildController {

    private final ImageBuildService buildService;
    private final ImageStore outputStore;
    private final LayerCache layerCache;
    private final GarbageCollector garbageCollector;

    public BuildController(ImageBuildService buildService,
                           @org.springframework.beans.factory.annotation.Qualifier("outputStore")
                           ImageStore outputStore,
                           LayerCache layerCache,
                           GarbageCollector garbageCollector) {
        this.buildService = buildService;
        this.outputStore = outputStore;
        this.layerCache = layerCache;
        this.garbageCollector = garbageCollector;
    }

    /** Request body for a build. */
    public record BuildRequest(String descriptionPath, String contextPath, String tag) {
    }

    @PostMapping("/build")
    public Map<String, Object> build(@RequestBody BuildRequest request) throws IOException {
        BuildResult result = buildService.build(
                Path.of(request.descriptionPath()), Path.of(request.contextPath()), request.tag());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tag", result.tag());
        body.put("imageId", result.imageId());
        body.put("configDigest", result.configDigest());
        body.put("layerDigests", result.layerDigests());
        body.put("layersRebuilt", result.layersRebuilt());
        body.put("layersReused", result.layersReused());
        return body;
    }

    @GetMapping("/images")
    public List<String> listImages() throws IOException {
        return outputStore.listDirs();
    }

    @GetMapping("/images/{tag}")
    public Map<String, Object> getImage(@PathVariable String tag) throws IOException {
        ImageStore.LoadedImage loaded = outputStore.load(ImageStore.sanitize(tag));
        ImageManifest manifest = loaded.manifest();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tag", tag);
        body.put("imageId", ImageStore.imageId(manifest));
        body.put("config", descriptor(manifest.config()));
        body.put("layers", manifest.layers().stream().map(this::descriptor).toList());
        return body;
    }

    @DeleteMapping("/images/{tag}")
    public Map<String, Object> deleteImage(@PathVariable String tag) throws IOException {
        outputStore.delete(ImageStore.sanitize(tag));
        return Map.of("deleted", tag);
    }

    @PostMapping("/gc")
    public Map<String, Object> gc() throws IOException {
        GarbageCollector.GcStats stats = garbageCollector.gc();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deletedBlobs", stats.deletedBlobs());
        body.put("freedBytes", stats.freedBytes());
        body.put("remainingBlobs", stats.remainingBlobs());
        body.put("remainingBytes", stats.remainingBytes());
        body.put("stagingRemoved", stats.stagingRemoved());
        return body;
    }

    @GetMapping("/cache")
    public Map<String, Object> cacheStats() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blobCount", layerCache.listBlobs().size());
        body.put("totalBytes", layerCache.totalBlobSize());
        body.put("indexSize", layerCache.indexKeys().size());
        return body;
    }

    private Map<String, Object> descriptor(ImageManifest.Descriptor d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mediaType", d.mediaType());
        map.put("digest", d.digest());
        map.put("size", d.size());
        return map;
    }
}
