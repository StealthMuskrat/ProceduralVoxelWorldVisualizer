package io.voxelcreator.api;

import io.voxelcreator.storage.VoxelChunk;
import java.util.*;

public record RegionResult(List<VoxelChunk> chunks, RegionSummary summary) {}
