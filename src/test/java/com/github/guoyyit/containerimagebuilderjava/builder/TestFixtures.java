package com.github.guoyyit.containerimagebuilderjava.builder;

import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.Layer;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import com.github.guoyyit.containerimagebuilderjava.builder.tar.TarWriter;
import com.github.guoyyit.containerimagebuilderjava.builder.util.Digests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers shared by tests: temp configuration and a hand-built local base image.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static BuilderProperties properties(Path root) {
        BuilderProperties p = new BuilderProperties();
        p.setCacheDir(root.resolve("cache"));
        p.setBaseImagesDir(root.resolve("base-images"));
        p.setOutputDir(root.resolve("images"));
        p.setStagingGraceMs(0);
        return p;
    }

    /**
     * Creates a local base image package with two layers and a deterministic
     * config, returning its manifest.
     */
    public static ImageManifest createBaseImage(BuilderProperties props, String name, String tag)
            throws IOException {
        return createBaseImage(props, name, tag, "base data\n");
    }

    /**
     * Creates a local base image package with one layer containing {@code content}
     * in {@code opt/base.txt}.
     */
    public static ImageManifest createBaseImage(BuilderProperties props, String name, String tag,
                                               String content) throws IOException {
        LayerCache cache = new LayerCache(props.getCacheDir());
        ImageStore store = new ImageStore(props.getBaseImagesDir());

        Path src = Files.createTempDirectory("base-src");
        Path f1 = Files.writeString(src.resolve("hostname"), "base-host\n");
        Path f2 = Files.writeString(src.resolve("base.txt"), content);

        byte[] tar1 = tar(List.of(
                TarWriter.Entry.directory("etc", 0755),
                TarWriter.Entry.file("etc/hostname", 0644, f1)));
        byte[] tar2 = tar(List.of(
                TarWriter.Entry.directory("opt", 0755),
                TarWriter.Entry.file("opt/base.txt", 0644, f2)));

        Layer layer1 = importBytes(cache, tar1);
        Layer layer2 = importBytes(cache, tar2);

        String configJson = "{\"architecture\":\"arm64\",\"os\":\"linux\"}";
        Layer config = importBytes(cache, configJson.getBytes(StandardCharsets.UTF_8));

        ImageManifest manifest = new ImageManifest(2, ImageManifest.MEDIA_TYPE_MANIFEST,
                new ImageManifest.Descriptor(ImageManifest.MEDIA_TYPE_CONFIG, config.digest(), config.size()),
                List.of(
                        new ImageManifest.Descriptor(ImageManifest.MEDIA_TYPE_LAYER, layer1.digest(), layer1.size()),
                        new ImageManifest.Descriptor(ImageManifest.MEDIA_TYPE_LAYER, layer2.digest(), layer2.size())));

        store.save(ImageStore.sanitize(name + "_" + tag), manifest,
                List.of(config.path(), layer1.path(), layer2.path()));
        return manifest;
    }

    /** Creates a minimal build description file. */
    public static Path writeDescription(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("Dockerfile");
        Files.writeString(f, content);
        return f;
    }

    public static byte[] tar(List<TarWriter.Entry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new TarWriter().write(entries, out);
        return out.toByteArray();
    }

    public static Layer importBytes(LayerCache cache, byte[] data) throws IOException {
        String digest = Digests.sha256(data);
        return cache.importBytes(digest, data);
    }

    /** Reads all bytes of a file. */
    public static byte[] read(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    /** Lists the regular files in a directory, sorted. */
    public static List<Path> listFiles(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(result::add);
        }
        result.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }
}
