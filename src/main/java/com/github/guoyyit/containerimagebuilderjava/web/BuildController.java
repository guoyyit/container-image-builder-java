package com.github.guoyyit.containerimagebuilderjava.web;

import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;
import com.github.guoyyit.containerimagebuilderjava.model.BuildRequest;
import com.github.guoyyit.containerimagebuilderjava.model.BuildResult;
import com.github.guoyyit.containerimagebuilderjava.model.ImageManifest;
import com.github.guoyyit.containerimagebuilderjava.service.ImageBuildService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/images")
public class BuildController {

    private final ImageBuildService service;

    public BuildController(ImageBuildService service) {
        this.service = service;
    }

    @PostMapping("/build")
    public BuildResult build(@RequestBody BuildApiRequest request) {
        BuildRequest buildRequest = new BuildRequest(request.imageName(),
                Path.of(request.descriptorPath()), Path.of(request.contextDir()));
        return service.build(buildRequest);
    }

    @GetMapping("/{name}")
    public ImageManifest inspect(@PathVariable("name") String name) {
        return service.inspect(name);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable("name") String name) {
        boolean removed = service.deleteImage(name);
        return Map.of("name", name, "deleted", removed);
    }

    @PostMapping("/gc")
    public Map<String, Object> garbageCollect() {
        return Map.of("removedLayers", service.garbageCollect());
    }

    @ExceptionHandler(BuildException.class)
    public ResponseEntity<Map<String, String>> handleBuildError(BuildException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage()));
    }

    record BuildApiRequest(String imageName, String descriptorPath, String contextDir) {
    }
}
