package io.voxelcreator.api;

import java.util.UUID;

/** Stable layer/operation identity and its boundaries in the enabled, committed pipeline. */
public record GenerationStage(
    UUID uuid, UUID layerUuid, String kind, String label, int before, int after, boolean enabled) {}
