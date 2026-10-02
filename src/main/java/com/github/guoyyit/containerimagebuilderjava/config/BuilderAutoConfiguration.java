package com.github.guoyyit.containerimagebuilderjava.config;

import com.github.guoyyit.containerimagebuilderjava.service.ImageBuildService;
import com.github.guoyyit.containerimagebuilderjava.storage.DefaultImageStore;
import com.github.guoyyit.containerimagebuilderjava.storage.DefaultLayerStore;
import com.github.guoyyit.containerimagebuilderjava.storage.ImageStore;
import com.github.guoyyit.containerimagebuilderjava.storage.LayerStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
@EnableConfigurationProperties(CacheConfig.class)
public class BuilderAutoConfiguration {

    @Bean
    public LayerStore layerStore(CacheConfig cacheConfig) {
        return new DefaultLayerStore(cacheConfig);
    }

    @Bean
    public ImageStore imageStore(CacheConfig cacheConfig) {
        return new DefaultImageStore(cacheConfig);
    }

    @Bean
    public ImageBuildService imageBuildService(CacheConfig cacheConfig, LayerStore layerStore,
                                               ImageStore imageStore) {
        return new ImageBuildService(cacheConfig, layerStore, imageStore);
    }
}
