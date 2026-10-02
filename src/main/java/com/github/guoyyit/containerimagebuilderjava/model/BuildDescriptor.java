package com.github.guoyyit.containerimagebuilderjava.model;

import java.util.List;

public record BuildDescriptor(String baseImage, List<Instruction> instructions) {
}
