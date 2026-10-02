package com.github.guoyyit.containerimagebuilderjava.model;

public record Digest(String value) {
    public static Digest of(String value) {
        return new Digest(value);
    }
}
