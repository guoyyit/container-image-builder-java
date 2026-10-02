package com.github.guoyyit.containerimagebuilderjava.builder.context;

import com.github.guoyyit.containerimagebuilderjava.builder.util.Digests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A build context: the directory whose files are copied into image layers.
 */
public class BuildContext {

    public record FileEntry(String relativePath, long size, int mode, String sha256) {
    }

    private final Path root;

    public BuildContext(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * All regular files under the context root, sorted by relative path for
     * determinism.
     */
    public List<FileEntry> files() throws IOException {
        List<FileEntry> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                result.add(new FileEntry(rel, Files.size(p), posixMode(p), Digests.sha256(p)));
            }
        }
        result.sort(Comparator.comparing(FileEntry::relativePath));
        return result;
    }

    /** Unix permission bits (07777) of a file, normalized across platforms. */
    public static int posixMode(Path path) throws IOException {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
        int mode = 0;
        if (perms.contains(PosixFilePermission.OWNER_READ)) {
            mode |= 0400;
        }
        if (perms.contains(PosixFilePermission.OWNER_WRITE)) {
            mode |= 0200;
        }
        if (perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
            mode |= 0100;
        }
        if (perms.contains(PosixFilePermission.GROUP_READ)) {
            mode |= 0040;
        }
        if (perms.contains(PosixFilePermission.GROUP_WRITE)) {
            mode |= 0020;
        }
        if (perms.contains(PosixFilePermission.GROUP_EXECUTE)) {
            mode |= 0010;
        }
        if (perms.contains(PosixFilePermission.OTHERS_READ)) {
            mode |= 0004;
        }
        if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
            mode |= 0002;
        }
        if (perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
            mode |= 0001;
        }
        return mode;
    }
}
