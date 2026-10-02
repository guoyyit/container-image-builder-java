package com.github.guoyyit.containerimagebuilderjava.storage;

import com.github.guoyyit.containerimagebuilderjava.model.Digest;
import com.github.guoyyit.containerimagebuilderjava.model.ImageManifest;
import java.util.List;

public interface ImageStore {
    void commit(ImageManifest manifest);

    ImageManifest load(String imageName);

    boolean exists(String imageName);

    List<ImageManifest> listImages();

    List<Digest> allReferencedChainIds();

    boolean delete(String imageName);
}
