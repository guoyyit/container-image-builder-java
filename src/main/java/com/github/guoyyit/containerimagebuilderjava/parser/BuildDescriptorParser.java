package com.github.guoyyit.containerimagebuilderjava.parser;

import com.github.guoyyit.containerimagebuilderjava.model.BuildDescriptor;
import com.github.guoyyit.containerimagebuilderjava.model.CopyInstruction;
import com.github.guoyyit.containerimagebuilderjava.model.Instruction;
import com.github.guoyyit.containerimagebuilderjava.exception.BuildException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BuildDescriptorParser {

    public BuildDescriptor parse(Path descriptorPath) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(descriptorPath, StandardCharsets.UTF_8);
            return parseLines(lines, descriptorPath);
        } catch (IOException e) {
            throw new BuildException("failed to read build descriptor: " + descriptorPath, e);
        }
    }

    BuildDescriptor parseLines(List<String> lines, Path source) {
        String baseImage = null;
        List<Instruction> instructions = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).trim();
            int lineNo = i + 1;
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            String[] tokens = raw.split("\\s+");
            String keyword = tokens[0].toUpperCase(java.util.Locale.ROOT);
            switch (keyword) {
                case "FROM" -> {
                    if (baseImage != null) {
                        throw parseError(source, lineNo, "multiple FROM instructions are not supported");
                    }
                    if (tokens.length != 2) {
                        throw parseError(source, lineNo, "FROM requires exactly one base image reference");
                    }
                    baseImage = tokens[1];
                }
                case "COPY" -> {
                    if (baseImage == null) {
                        throw parseError(source, lineNo, "COPY requires a preceding FROM");
                    }
                    if (tokens.length < 3) {
                        throw parseError(source, lineNo, "COPY requires at least one source and a destination");
                    }
                    List<String> sources = new ArrayList<>();
                    for (int t = 1; t < tokens.length - 1; t++) {
                        sources.add(tokens[t]);
                    }
                    String destination = tokens[tokens.length - 1];
                    instructions.add(new CopyInstruction(List.copyOf(sources), destination));
                }
                default -> throw parseError(source, lineNo, "unsupported instruction: " + tokens[0]);
            }
        }
        if (baseImage == null) {
            throw new BuildException("build descriptor " + source + " must contain a FROM instruction");
        }
        return new BuildDescriptor(baseImage, List.copyOf(instructions));
    }

    private BuildException parseError(Path source, int lineNo, String message) {
        return new BuildException("invalid build descriptor " + source + " at line " + lineNo + ": " + message);
    }
}
