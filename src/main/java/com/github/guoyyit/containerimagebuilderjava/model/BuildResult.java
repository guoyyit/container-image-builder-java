package com.github.guoyyit.containerimagebuilderjava.model;

import java.util.List;

public record BuildResult(Digest imageId, List<Digest> layerChainIds, boolean cacheHit) {
}
