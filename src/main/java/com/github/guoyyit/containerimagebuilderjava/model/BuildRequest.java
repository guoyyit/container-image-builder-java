package com.github.guoyyit.containerimagebuilderjava.model;

import java.nio.file.Path;

public record BuildRequest(String imageName, Path descriptorPath, Path contextDir) {
}
