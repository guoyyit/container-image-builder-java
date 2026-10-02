package com.github.guoyyit.containerimagebuilderjava.model;

public record Layer(Digest chainId, Digest diffId, java.nio.file.Path archivePath) {
}
