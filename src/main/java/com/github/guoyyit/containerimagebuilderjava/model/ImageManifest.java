package com.github.guoyyit.containerimagebuilderjava.model;

import java.util.List;

public record ImageManifest(String name,
                            Digest baseChainId,
                            List<Digest> layerChainIds,
                            Digest imageId,
                            long createdAtEpoch) {
}
