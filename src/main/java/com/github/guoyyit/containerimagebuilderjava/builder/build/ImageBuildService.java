package com.github.guoyyit.containerimagebuilderjava.builder.build;

import com.github.guoyyit.containerimagebuilderjava.builder.config.BuilderProperties;
import com.github.guoyyit.containerimagebuilderjava.builder.context.BuildContext;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.Layer;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import com.github.guoyyit.containerimagebuilderjava.builder.parser.BuildDescription;
import com.github.guoyyit.containerimagebuilderjava.builder.parser.DockerfileParser;
import com.github.guoyyit.containerimagebuilderjava.builder.parser.Instruction;
import com.github.guoyyit.containerimagebuilderjava.builder.tar.TarWriter;
import com.github.guoyyit.containerimagebuilderjava.builder.util.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Orchestrates layered, content-addressed, reproducible image builds.
 *
 * <p>Each COPY instruction becomes one tar layer. A layer's content is fully
 * determined by its parent layer digest, the destination path and the hashes of
 * the context files it copies; changing a base image or any context file therefore
 * rebuilds that layer and every layer after it, while unchanged layers are reused
 * byte-for-byte. Builds hold the layer cache read lock from layer acquisition
 * through image package publication, so garbage collection can never remove a
 * layer a build is about to reference.
 */
public class ImageBuildService {

    private static final Logger log = LoggerFactory.getLogger(ImageBuildService.class);
    private static final String GENESIS = "sha256:0000000000000000000000000000000000000000000000000000000000000000";

    private final BuilderProperties properties;
    private final LayerCache layerCache;
    private final ImageStore outputStore;
    private final ImageStore baseStore;

    public ImageBuildService(BuilderProperties properties) throws IOException {
        this(properties, new LayerCache(properties.getCacheDir()),
                new ImageStore(properties.getOutputDir()), new ImageStore(properties.getBaseImagesDir()));
    }

    public ImageBuildService(BuilderProperties properties, LayerCache layerCache,
                             ImageStore outputStore, ImageStore baseStore) {
        this.properties = properties;
        this.layerCache = layerCache;
        this.outputStore = outputStore;
        this.baseStore = baseStore;
    }

    /**
     * Builds an image from a build description and context.
     *
     * @param descriptionFile path to the Dockerfile-like description
     * @param contextRoot     build context directory
     * @param tag             image tag (used as the output package directory name)
     */
    public BuildResult build(Path descriptionFile, Path contextRoot, String tag) throws IOException {
        try {
            return layerCache.withReadLock(() -> {
                try {
                    return doBuild(descriptionFile, contextRoot, tag);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private BuildResult doBuild(Path descriptionFile, Path contextRoot, String tag) throws IOException {
        if (!Files.isDirectory(contextRoot)) {
            throw new IOException("build context is not a directory: " + contextRoot);
        }
        BuildDescription desc = new DockerfileParser().parse(descriptionFile);
        BuildContext context = new BuildContext(contextRoot);

        ImageStore.LoadedImage base = loadBaseImage(desc.from());

        Layer config = buildConfigLayer();

        String parentDigest = base.manifest().layers().isEmpty()
                ? GENESIS
                : base.manifest().layers().get(base.manifest().layers().size() - 1).digest();

        List<ImageManifest.Descriptor> layerDescriptors = new ArrayList<>(base.manifest().layers());
        List<String> layerDigests = new ArrayList<>();
        int rebuilt = 0;
        int reused = 0;

        for (Instruction.Copy copy : desc.copies()) {
            String dest = normalizeDest(copy.destination());
            ResolvedCopy resolved = resolveEntries(copy, dest, context);
            String inputKey = layerInputKey(parentDigest, dest, resolved.contextFiles());

            LayerCache.ComputedLayer computed = layerCache.getOrCompute(inputKey, staging -> {
                try (OutputStream out = Files.newOutputStream(staging)) {
                    new TarWriter().write(resolved.entries(), out);
                }
                return Digests.sha256(staging);
            });

            if (computed.built()) {
                rebuilt++;
            } else {
                reused++;
            }
            Layer layer = computed.layer();
            layerDescriptors.add(new ImageManifest.Descriptor(
                    ImageManifest.MEDIA_TYPE_LAYER, layer.digest(), layer.size()));
            layerDigests.add(layer.digest());
            parentDigest = layer.digest();
            log.info("layer {} {} (key={})", computed.built() ? "built" : "reused",
                    layer.digest().substring(0, 12), inputKey.substring(0, 12));
        }

        ImageManifest manifest = new ImageManifest(2, ImageManifest.MEDIA_TYPE_MANIFEST,
                new ImageManifest.Descriptor(ImageManifest.MEDIA_TYPE_CONFIG, config.digest(), config.size()),
                List.copyOf(layerDescriptors));

        List<Path> blobSources = new ArrayList<>();
        blobSources.add(config.path());
        for (ImageManifest.Descriptor layer : manifest.layers()) {
            blobSources.add(layerCache.blobPath(layer.digest()));
        }
        outputStore.save(ImageStore.sanitize(tag), manifest, blobSources);

        String imageId = ImageStore.imageId(manifest);
        log.info("built image {} id={} layers={} (rebuilt={}, reused={})",
                tag, imageId.substring(0, 12), layerDigests.size(), rebuilt, reused);
        return new BuildResult(tag, imageId, config.digest(), List.copyOf(layerDigests), rebuilt, reused);
    }

    private ImageStore.LoadedImage loadBaseImage(Instruction.From from) throws IOException {
        String dirName = ImageStore.sanitize(from.name() + "_" + from.tag());
        ImageStore.LoadedImage base = baseStore.load(dirName);
        for (ImageManifest.Descriptor layer : base.manifest().layers()) {
            layerCache.importBlob(layer.digest(), base.blobPath(layer.digest()));
        }
        layerCache.importBlob(base.manifest().config().digest(),
                base.blobPath(base.manifest().config().digest()));
        return base;
    }

    private Layer buildConfigLayer() throws IOException {
        String arch = normalizeArch(System.getProperty("os.arch"));
        String json = "{\"architecture\":\"" + arch + "\",\"os\":\"linux\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String digest = Digests.sha256(bytes);
        return layerCache.importBytes(digest, bytes);
    }

    /** Resolves COPY sources into tar entries and the context files they came from. */
    private ResolvedCopy resolveEntries(Instruction.Copy copy, String dest, BuildContext context)
            throws IOException {
        boolean destIsDir = copy.destination().endsWith("/") || copy.sources().size() > 1;
        List<TarWriter.Entry> entries = new ArrayList<>();
        List<BuildContext.FileEntry> contextFiles = new ArrayList<>();
        Set<String> dirs = new TreeSet<>();
        Path root = context.root();

        for (String src : copy.sources()) {
            Path srcPath = root.resolve(src).normalize();
            if (!srcPath.startsWith(root)) {
                throw new IOException("COPY source escapes build context: " + src);
            }
            if (Files.isDirectory(srcPath)) {
                try (var walk = Files.walk(srcPath)) {
                    for (Path p : walk.toList()) {
                        if (Files.isDirectory(p)) {
                            if (!p.equals(srcPath)) {
                                String rel = srcPath.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                                dirs.add(joinPath(dest, rel));
                            }
                        } else if (Files.isRegularFile(p)) {
                            String rel = srcPath.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                            addFile(entries, dirs, contextFiles, joinPath(dest, rel), p);
                        }
                    }
                }
            } else if (Files.isRegularFile(srcPath)) {
                String layerPath = destIsDir
                        ? joinPath(dest, srcPath.getFileName().toString())
                        : dest;
                addFile(entries, dirs, contextFiles, layerPath, srcPath);
            } else {
                throw new IOException("COPY source not found: " + src);
            }
        }
        for (String dir : dirs) {
            entries.add(TarWriter.Entry.directory(dir, 0755));
        }
        contextFiles.sort((a, b) -> a.relativePath().compareTo(b.relativePath()));
        return new ResolvedCopy(entries, contextFiles);
    }

    private void addFile(List<TarWriter.Entry> entries, Set<String> dirs,
                         List<BuildContext.FileEntry> contextFiles, String layerPath, Path source)
            throws IOException {
        entries.add(TarWriter.Entry.file(layerPath, BuildContext.posixMode(source), source));
        contextFiles.add(new BuildContext.FileEntry(
                source.getFileName().toString(), Files.size(source),
                BuildContext.posixMode(source), Digests.sha256(source)));
        String parent = parentOf(layerPath);
        while (parent != null && !parent.isEmpty()) {
            dirs.add(parent);
            parent = parentOf(parent);
        }
    }

    private record ResolvedCopy(List<TarWriter.Entry> entries,
                                List<BuildContext.FileEntry> contextFiles) {
    }

    private static String layerInputKey(String parentDigest, String dest,
                                        List<BuildContext.FileEntry> files) {
        StringBuilder sb = new StringBuilder("layer-v1\n");
        sb.append("parent=").append(parentDigest).append('\n');
        sb.append("dest=").append(dest).append('\n');
        sb.append("files=");
        for (BuildContext.FileEntry f : files) {
            sb.append(f.relativePath()).append(':').append(f.sha256()).append(':')
                    .append(f.mode()).append(':').append(f.size()).append('\n');
        }
        return Digests.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeDest(String dest) throws IOException {
        String d = dest;
        while (d.startsWith("/")) {
            d = d.substring(1);
        }
        for (String segment : d.split("/")) {
            if ("..".equals(segment)) {
                throw new IOException("COPY destination must not contain '..': " + dest);
            }
        }
        return d;
    }

    private static String joinPath(String dir, String name) {
        if (dir.isEmpty()) {
            return name;
        }
        if (dir.endsWith("/")) {
            return dir + name;
        }
        return dir + "/" + name;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    private static String normalizeArch(String arch) {
        return switch (arch) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
    }
}
