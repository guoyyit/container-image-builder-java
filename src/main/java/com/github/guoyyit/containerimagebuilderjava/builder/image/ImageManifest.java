package com.github.guoyyit.containerimagebuilderjava.builder.image;

import java.util.List;

/**
 * Deterministic image manifest.
 */
public record ImageManifest(int schemaVersion, String mediaType, Descriptor config,
                            List<Descriptor> layers) {

    public record Descriptor(String mediaType, String digest, long size) {
    }

    public static final String MEDIA_TYPE_CONFIG = "application/vnd.oci.image.config.v1+json";
    public static final String MEDIA_TYPE_LAYER = "application/vnd.oci.image.layer.v1.tar";
    public static final String MEDIA_TYPE_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
}
