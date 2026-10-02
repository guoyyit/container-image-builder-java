package com.github.guoyyit.containerimagebuilderjava.build;

import com.github.guoyyit.containerimagebuilderjava.model.CopyInstruction;
import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.digest.Digests;
import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;
import com.github.guoyyit.containerimagebuilderjava.storage.LayerStore;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class LayerBuilder {

    private final LayerStore layerStore;

    public LayerBuilder(LayerStore layerStore) {
        this.layerStore = layerStore;
    }

    public Digest buildCopyLayer(Digest parentChainId, CopyInstruction instruction, Path contextDir,
                                 BuildCancellationToken token) {
        List<Path> sources = resolveSources(instruction, contextDir);
        String destination = normalizeDestination(instruction.destination());
        List<LayerEntry> entries = collectEntries(instruction, contextDir, sources, destination, token);
        Digest chainId = copyChainId(parentChainId, instruction, contextDir, token, entries);
        layerStore.pin(chainId);
        try {
            token.throwIfCancelled();
            layerStore.getOrCreate(chainId, null, stagingFile -> {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(stagingFile))) {
                    writeTar(entries, out, token);
                }
            });
            token.throwIfCancelled();
            return chainId;
        } finally {
            layerStore.unpin(chainId);
        }
    }

    public Digest copyChainId(Digest parentChainId, CopyInstruction instruction, Path contextDir,
                              BuildCancellationToken token) {
        List<Path> sources = resolveSources(instruction, contextDir);
        String destination = normalizeDestination(instruction.destination());
        List<LayerEntry> entries = collectEntries(instruction, contextDir, sources, destination, token);
        return copyChainId(parentChainId, instruction, contextDir, token, entries);
    }

    private Digest copyChainId(Digest parentChainId, CopyInstruction instruction, Path contextDir,
                               BuildCancellationToken token, List<LayerEntry> entries) {
        Digest diffId = computeDiffId(entries);
        String chainInput = parentChainId == null ? "" : parentChainId.value();
        chainInput += "|copy|" + diffId.value();
        return Digests.sha256Hex(chainInput);
    }

    private List<Path> resolveSources(CopyInstruction instruction, Path contextDir) {
        List<Path> sources = new ArrayList<>();
        for (String source : instruction.sources()) {
            if (source.isBlank()) {
                throw new BuildException("COPY source path must not be blank");
            }
            if (source.startsWith("/")) {
                throw new BuildException("COPY source must be relative to context: " + source);
            }
            Path resolved = contextDir.resolve(source).normalize();
            if (!resolved.startsWith(contextDir.toAbsolutePath().normalize())) {
                throw new BuildException("COPY source escapes the build context: " + source);
            }
            if (!Files.exists(resolved)) {
                throw new BuildException("COPY source not found in build context: " + source);
            }
            sources.add(resolved);
        }
        return sources;
    }

    private String normalizeDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw new BuildException("COPY destination must not be blank");
        }
        String normalized = destination.startsWith("/") ? destination.substring(1) : destination;
        normalized = normalized.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private List<LayerEntry> collectEntries(CopyInstruction instruction, Path contextDir, List<Path> sources,
                                            String destination, BuildCancellationToken token) {
        List<LayerEntry> entries = new ArrayList<>();
        boolean multipleSources = instruction.sources().size() > 1;
        if (multipleSources) {
            String destDir = withTrailingSlash(destination);
            for (int index = 0; index < sources.size(); index++) {
                Path source = sources.get(index);
                String baseName = Path.of(instruction.sources().get(index)).getFileName().toString();
                collectOne(source, stripTrailingSlash(joinPath(destDir, baseName)), entries, token);
            }
            Collections.sort(entries);
            return entries;
        }

        Path source = sources.get(0);
        if (Files.isDirectory(source)) {
            collectOne(source, destination, entries, token);
        } else {
            String baseName = Path.of(instruction.sources().get(0)).getFileName().toString();
            String target;
            if (instruction.destination().endsWith("/") || destination.isEmpty()) {
                target = stripTrailingSlash(joinPath(withTrailingSlash(destination), baseName));
            } else {
                target = destination;
            }
            collectOne(source, target, entries, token);
        }
        Collections.sort(entries);
        return entries;
    }

    private String stripTrailingSlash(String path) {
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void collectOne(Path source, String targetPath, List<LayerEntry> entries,
                            BuildCancellationToken token) {
        token.throwIfCancelled();
        if (Files.isRegularFile(source)) {
            entries.add(new LayerEntry(source, targetPath, false));
            return;
        }
        if (Files.isDirectory(source)) {
            try (Stream<Path> walk = Files.walk(source)) {
                List<Path> all = walk.sorted().toList();
                for (Path path : all) {
                    token.throwIfCancelled();
                    String relative = source.relativize(path).toString().replace('\\', '/');
                    String entryPath = relative.isEmpty() ? targetPath : joinPath(targetPath, relative);
                    if (Files.isDirectory(path)) {
                        entries.add(new LayerEntry(path, withTrailingSlash(entryPath), true));
                    } else if (Files.isRegularFile(path)) {
                        entries.add(new LayerEntry(path, entryPath, false));
                    }
                }
            } catch (IOException e) {
                throw new BuildException("failed to scan COPY source " + source, e);
            }
        }
    }

    private String withTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private String joinPath(String parent, String child) {
        if (parent.endsWith("/")) {
            return parent + child;
        }
        return parent + "/" + child;
    }

    private Digest computeDiffId(List<LayerEntry> entries) {
        MessageDigest digest = Digests.newMessageDigest();
        for (LayerEntry entry : entries) {
            digest.update(entry.tarPath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (entry.directory()) {
                digest.update(new byte[] {'d', 0});
            } else {
                digest.update(new byte[] {'f', 0});
                digest.update(Digests.sha256File(entry.sourcePath()).value()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(longBytes(sizeOf(entry.sourcePath())));
                digest.update((byte) 0);
            }
        }
        return Digests.toDigest(digest);
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new BuildException("failed to stat file " + file, e);
        }
    }

    private byte[] longBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }

    private void writeTar(List<LayerEntry> entries, OutputStream out, BuildCancellationToken token)
            throws IOException {
        ReproducibleTarArchive archive = new ReproducibleTarArchive(out);
        for (LayerEntry entry : entries) {
            token.throwIfCancelled();
            if (entry.directory()) {
                archive.writeDirectory(entry.tarPath());
            } else {
                archive.writeFile(entry.tarPath(), entry.sourcePath());
            }
        }
        archive.finish();
    }

    private record LayerEntry(Path sourcePath, String tarPath, boolean directory)
            implements Comparable<LayerEntry> {
        @Override
        public int compareTo(LayerEntry other) {
            return this.tarPath.compareTo(other.tarPath);
        }
    }
}
