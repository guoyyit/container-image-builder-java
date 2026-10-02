package com.github.guoyyit.containerimagebuilderjava.builder.parser;

import java.util.List;

/**
 * A parsed build instruction.
 */
public sealed interface Instruction permits Instruction.From, Instruction.Copy {

    /** {@code FROM name[:tag]} — reference to a local base image package. */
    public record From(String imageRef, String name, String tag) implements Instruction {
    }

    /**
     * {@code COPY [--chown=...] src... dest} — copy context files into a layer.
     * {@code ADD} is treated as COPY (no remote fetch, no archive extraction).
     */
    public record Copy(List<String> sources, String destination) implements Instruction {
    }
}
