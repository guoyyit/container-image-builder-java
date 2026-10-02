package com.github.guoyyit.containerimagebuilderjava.storage;

import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.model.Layer;
import java.nio.file.Path;

public interface LayerStore {
    Layer get(Digest chainId);

    Layer getOrCreate(Digest chainId, Digest expectedDiffId, LayerWriter writer);

    boolean exists(Digest chainId);

    void pin(Digest chainId);

    void unpin(Digest chainId);

    void acquireBuildLease();

    void releaseBuildLease();

    void runExclusiveWithBuilds(java.lang.Runnable action) throws InterruptedException;

    java.util.List<Digest> listLayerChainIds();

    @FunctionalInterface
    interface LayerWriter {
        void write(Path stagingFile) throws Exception;
    }
}
