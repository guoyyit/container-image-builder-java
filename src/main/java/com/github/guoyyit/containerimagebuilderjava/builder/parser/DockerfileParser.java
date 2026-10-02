package com.github.guoyyit.containerimagebuilderjava.builder.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Dockerfile-like build description.
 *
 * <p>Supported instructions:
 * <ul>
 *   <li>{@code FROM name[:tag]} — reference to a local base image package
 *       (tag defaults to {@code latest});</li>
 *   <li>{@code COPY [--chown=...] src... dest} — copy context files into a
 *       layer;</li>
 *   <li>{@code ADD} — treated as {@code COPY} (no remote fetch, no archive
 *       extraction).</li>
 * </ul>
 * Comments ({@code #}), blank lines and backslash line continuations are
 * supported. Any other instruction fails the build.
 */
public class DockerfileParser {

    public BuildDescription parse(Path descriptionFile) throws IOException {
        List<String> rawLines = Files.readAllLines(descriptionFile);
        List<String> instructions = joinContinuations(rawLines);

        Instruction.From from = null;
        List<Instruction.Copy> copies = new ArrayList<>();

        for (String instruction : instructions) {
            String[] tokens = instruction.trim().split("\\s+");
            String keyword = tokens[0].toUpperCase();
            switch (keyword) {
                case "FROM" -> {
                    if (from != null) {
                        throw new IllegalArgumentException("only one FROM is supported");
                    }
                    if (tokens.length < 2) {
                        throw new IllegalArgumentException("FROM requires an image reference");
                    }
                    from = parseFrom(tokens[1]);
                }
                case "COPY", "ADD" -> {
                    if (from == null) {
                        throw new IllegalArgumentException(keyword + " must come after FROM");
                    }
                    copies.add(parseCopy(keyword, tokens));
                }
                default -> throw new IllegalArgumentException(
                        "unsupported instruction: " + keyword + " (only FROM, COPY, ADD are supported)");
            }
        }

        if (from == null) {
            throw new IllegalArgumentException("build description must start with FROM");
        }
        return new BuildDescription(from, List.copyOf(copies));
    }

    private Instruction.From parseFrom(String ref) {
        int colon = ref.lastIndexOf(':');
        if (colon < 0) {
            return new Instruction.From(ref, ref, "latest");
        }
        String name = ref.substring(0, colon);
        String tag = ref.substring(colon + 1);
        if (name.isEmpty() || tag.isEmpty()) {
            throw new IllegalArgumentException("invalid image reference: " + ref);
        }
        return new Instruction.From(ref, name, tag);
    }

    private Instruction.Copy parseCopy(String keyword, String[] tokens) {
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.startsWith("--")) {
                if (token.startsWith("--chown=")) {
                    continue; // ownership is normalized to root in the layer tar
                }
                throw new IllegalArgumentException(
                        keyword + " option " + token + " is not supported");
            }
            args.add(token);
        }
        if (args.size() < 2) {
            throw new IllegalArgumentException(keyword + " requires at least a source and a destination");
        }
        String destination = args.get(args.size() - 1);
        List<String> sources = List.copyOf(args.subList(0, args.size() - 1));
        return new Instruction.Copy(sources, destination);
    }

    /** Joins lines ending with a backslash continuation. */
    private static List<String> joinContinuations(List<String> lines) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.endsWith("\\")) {
                current.append(trimmed, 0, trimmed.length() - 1).append(' ');
            } else {
                current.append(trimmed);
                result.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }
}
