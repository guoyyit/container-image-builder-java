package com.github.guoyyit.containerimagebuilderjava.model;

import java.util.List;

public record CopyInstruction(List<String> sources, String destination) implements Instruction {
}
