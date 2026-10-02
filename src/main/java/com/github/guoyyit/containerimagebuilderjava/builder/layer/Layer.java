package com.github.guoyyit.containerimagebuilderjava.builder.layer;

import java.nio.file.Path;

/**
 * A content-addressed layer blob.
 *
 * @param digest sha256 digest of the layer tar bytes
 * @param size   size of the layer tar in bytes
 * @param path   location of the blob in the cache
 */
public record Layer(String digest, long size, Path path) {
}
