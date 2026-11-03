package io.voxelcreator.api;

/** Immutable observation; RegionTask publishes state and all four counts together. */
public record TaskProgress(
    String state, long pending, long completed, long failed, long cancelled) {}
