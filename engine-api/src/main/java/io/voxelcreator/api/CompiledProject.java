package io.voxelcreator.api;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;

/** Immutable generation snapshot. No editor/render dependency. */
public final class CompiledProject {
  public static final String ENGINE_VERSION = "0.1.0";
  private final ProjectData snapshot;
  private final VoxelRegistry registry;

  public CompiledProject(ProjectData data) {
    var issues = NoiseCompiler.validate(data);
    if (!issues.isEmpty()) throw new IllegalArgumentException(issues.toString());
    snapshot = ProjectIO.copy(data);
    registry = new VoxelRegistry(snapshot.voxelTypes);
  }

  public UUID projectUuid() {
    return snapshot.project.uuid;
  }

  public int minY() {
    return snapshot.project.minY;
  }

  public int maxY() {
    return snapshot.project.maxY;
  }

  public long defaultSeed() {
    return snapshot.project.seed;
  }

  public VoxelRegistry registry() {
    return registry;
  }

  public NoiseSampler2D noise2D(UUID uuid, long seed) {
    var c = new NoiseCompiler(snapshot, seed);
    var n = find(uuid);
    if (n.dimension != 2) throw new IllegalArgumentException("Expected 2D resource");
    var f = c.compile(uuid);
    return (x, z) -> f.sample(x, 0, z);
  }

  public NoiseSampler3D noise3D(UUID uuid, long seed) {
    var c = new NoiseCompiler(snapshot, seed);
    var n = find(uuid);
    if (n.dimension != 3) throw new IllegalArgumentException("Expected 3D resource");
    var f = c.compile(uuid);
    return f::sample;
  }

  public NoiseSampler2D noise2D(String id, long seed) {
    return noise2D(
        snapshot.noise2D.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow().uuid,
        seed);
  }

  public NoiseSampler3D noise3D(String id, long seed) {
    return noise3D(
        snapshot.noise3D.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow().uuid,
        seed);
  }

  private Noise find(UUID uuid) {
    return java.util.stream.Stream.concat(snapshot.noise2D.stream(), snapshot.noise3D.stream())
        .filter(n -> n.uuid.equals(uuid))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown noise " + uuid));
  }

  WorldPlan plan(long seed) {
    return new WorldPlan(snapshot, registry, seed);
  }
}
