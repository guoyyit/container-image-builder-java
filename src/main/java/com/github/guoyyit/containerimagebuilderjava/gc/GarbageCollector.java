package com.github.guoyyit.containerimagebuilderjava.gc;

import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.storage.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.storage.LayerStore;
import com.github.guoyyit.containerimagebuilderjava.storage.DefaultLayerStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GarbageCollector {

    private final LayerStore layerStore;
    private final ImageStore imageStore;

    public GarbageCollector(LayerStore layerStore, ImageStore imageStore) {
        this.layerStore = layerStore;
        this.imageStore = imageStore;
    }

    public int collect() {
        if (!(layerStore instanceof DefaultLayerStore store)) {
            return 0;
        }
        int[] removedHolder = new int[] {0};
        try {
            layerStore.runExclusiveWithBuilds(() -> {
                Set<Digest> referenced = new HashSet<>(imageStore.allReferencedChainIds());
                List<Digest> all = store.listLayerChainIds();
                int removed = 0;
                for (Digest chainId : all) {
                    if (referenced.contains(chainId) || store.isPinned(chainId)) {
                        continue;
                    }
                    store.deleteLayer(chainId);
                    removed++;
                }
                if (imageStore instanceof
                        com.github.guoyyit.containerimagebuilderjava.storage.DefaultImageStore image) {
                    image.pruneUnreferencedManifestFiles();
                }
                removedHolder[0] = removed;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new com.github.guoyyit.containerimagebuilderjava.exception.BuildException(
                    "garbage collection interrupted", e);
        }
        return removedHolder[0];
    }
}
