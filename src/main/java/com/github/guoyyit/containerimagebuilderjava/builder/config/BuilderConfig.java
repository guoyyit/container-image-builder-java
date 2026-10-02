package com.github.guoyyit.containerimagebuilderjava.builder.config;

import com.github.guoyyit.containerimagebuilderjava.builder.build.ImageBuildService;
import com.github.guoyyit.containerimagebuilderjava.builder.gc.GarbageCollector;
import com.github.guoyyit.containerimagebuilderjava.builder.image.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.builder.layer.LayerCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Wires the builder components as Spring beans.
 */
@Configuration
public class BuilderConfig {

    @Bean
    public LayerCache layerCache(BuilderProperties properties) throws IOException {
        return new LayerCache(properties.getCacheDir());
    }

    @Bean
    public ImageStore outputStore(BuilderProperties properties) throws IOException {
        return new ImageStore(properties.getOutputDir());
    }

    @Bean
    public ImageStore baseStore(BuilderProperties properties) throws IOException {
        return new ImageStore(properties.getBaseImagesDir());
    }

    @Bean
    public ImageBuildService imageBuildService(BuilderProperties properties, LayerCache layerCache,
                                               @Qualifier("outputStore") ImageStore outputStore,
                                               @Qualifier("baseStore") ImageStore baseStore) {
        return new ImageBuildService(properties, layerCache, outputStore, baseStore);
    }

    @Bean
    public GarbageCollector garbageCollector(BuilderProperties properties, LayerCache layerCache,
                                             @Qualifier("outputStore") ImageStore outputStore,
                                             @Qualifier("baseStore") ImageStore baseStore) {
        return new GarbageCollector(properties, layerCache, outputStore, baseStore);
    }
}
