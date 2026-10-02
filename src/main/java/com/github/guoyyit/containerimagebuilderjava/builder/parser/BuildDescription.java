package com.github.guoyyit.containerimagebuilderjava.builder.parser;

import java.util.List;

/**
 * Parsed build description: a base image reference followed by copy instructions.
 */
public record BuildDescription(Instruction.From from, List<Instruction.Copy> copies) {
}
