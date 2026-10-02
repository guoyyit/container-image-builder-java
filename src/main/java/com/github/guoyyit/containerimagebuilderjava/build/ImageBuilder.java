package com.github.guoyyit.containerimagebuilderjava.build;

import com.github.guoyyit.containerimagebuilderjava.digest.Digests;
import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;
import com.github.guoyyit.containerimagebuilderjava.model.CopyInstruction;
import com.github.guoyyit.containerimagebuilderjava.model.BuildDescriptor;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;
import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.model.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.model.Instruction;
import com.github.guoyyit.containerimagebuilderjava.storage.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.storage.LayerStore;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;

public class ImageBuilder {

    private final LayerStore layerStore;
    private final ImageStore imageStore;
    private final LayerBuilder layerBuilder;

    public ImageBuilder(LayerStore layerStore, ImageStore imageStore) {
        this.layerStore = layerStore;
        this.imageStore = imageStore;
        this.layerBuilder = new LayerBuilder(layerStore);
    }

    public BuildResult build(String imageName, BuildDescriptor descriptor, Path contextDir,
                             BuildCancellationToken token) {
        if (imageName == null || imageName.isBlank()) {
            throw new BuildException("image name must not be blank");
        }
        layerStore.acquireBuildLease();
        boolean committed = false;
        List<Digest> pinned = new ArrayList<>();
        try {
            token.throwIfCancelled();
            Digest baseChainId = resolveBaseChainId(descriptor.baseImage());
            List<Digest> layerChainIds = new ArrayList<>();
            Digest parent = baseChainId;
            boolean allCacheHits = true;
            for (Instruction instruction : descriptor.instructions()) {
                Digest chainId;
                if (instruction instanceof CopyInstruction copy) {
                    chainId = layerBuilder.copyChainId(parent, copy, contextDir, token);
                    boolean existed = layerStore.exists(chainId);
                    allCacheHits = allCacheHits && existed;
                    if (!existed) {
                        chainId = layerBuilder.buildCopyLayer(parent, copy, contextDir, token);
                    }
                } else {
                    throw new BuildException("unsupported instruction type: " + instruction.getClass());
                }
                layerStore.pin(chainId);
                pinned.add(chainId);
                layerChainIds.add(chainId);
                parent = chainId;
            }
            token.throwIfCancelled();
            Digest imageId = computeImageId(imageName, baseChainId, layerChainIds);
            ImageManifest manifest = new ImageManifest(imageName, baseChainId,
                    List.copyOf(layerChainIds), imageId, 0L);
            imageStore.commit(manifest);
            committed = true;
            return new BuildResult(imageId, List.copyOf(layerChainIds), allCacheHits);
        } finally {
            for (Digest chainId : pinned) {
                layerStore.unpin(chainId);
            }
            layerStore.releaseBuildLease();
            if (!committed) {
                // Layers published before the failure are kept in the content-addressed cache.
                // No manifest references them, so they are eligible for garbage collection.
            }
        }
    }

    private Digest resolveBaseChainId(String baseImage) {
        if ("scratch".equals(baseImage)) {
            return null;
        }
        ImageManifest base = imageStore.load(baseImage);
        if (base == null) {
            throw new BuildException("base image not found in local image store: " + baseImage);
        }
        return base.layerChainIds().isEmpty()
                ? base.baseChainId()
                : base.layerChainIds().get(base.layerChainIds().size() - 1);
    }

    private Digest computeImageId(String imageName, Digest baseChainId, List<Digest> layerChainIds) {
        StringBuilder builder = new StringBuilder();
        builder.append("image|").append(imageName).append('|');
        builder.append(baseChainId == null ? "scratch" : baseChainId.value());
        for (Digest chainId : layerChainIds) {
            builder.append('|').append(chainId.value());
        }
        return Digests.sha256Hex(builder.toString());
    }
}
