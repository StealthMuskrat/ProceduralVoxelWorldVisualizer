package io.voxelcreator.api;

public record TaskProgress(
    String state, long pending, long completed, long failed, long cancelled) {}
