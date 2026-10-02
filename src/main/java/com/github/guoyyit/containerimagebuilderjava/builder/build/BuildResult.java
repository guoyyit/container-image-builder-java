package com.github.guoyyit.containerimagebuilderjava.builder.build;

import java.util.List;

/**
 * Result of a successful image build.
 */
public record BuildResult(String tag, String imageId, String configDigest,
                          List<String> layerDigests, int layersRebuilt, int layersReused) {
}
