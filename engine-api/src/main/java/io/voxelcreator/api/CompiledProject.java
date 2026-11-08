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
    this(prepare(data));
  }

  /** Opaque owned candidate: compiler entry points cannot access or recopy its editable model. */
  static final class Prepared {
    private final ProjectData snapshot;
    private final List<Diagnostic> diagnostics;

    private Prepared(ProjectData snapshot, List<Diagnostic> diagnostics) {
      this.snapshot = snapshot;
      this.diagnostics = List.copyOf(diagnostics);
    }

    List<Diagnostic> diagnostics() {
      return diagnostics;
    }
  }

  static Prepared prepare(ProjectData data) {
    // Preserve validation-first malformed-input diagnostics and avoid copying a failed traversal.
    var issues = NoiseCompiler.validate(data);
    if (!issues.isEmpty()) return new Prepared(null, issues);
    var owned = ProjectIO.copy(data);
    // Caller validation does not prove that serialization captured the same valid definition.
    issues = NoiseCompiler.validate(owned);
    return new Prepared(issues.isEmpty() ? owned : null, issues);
  }

  static CompiledProject accept(Prepared prepared) {
    return new CompiledProject(prepared);
  }

  private CompiledProject(Prepared prepared) {
    if (!prepared.diagnostics.isEmpty())
      throw new IllegalArgumentException(prepared.diagnostics.toString());
    snapshot = Objects.requireNonNull(prepared.snapshot);
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

  /**
   * Compiles the 2D resource identified by its stable UUID for this seed.
   *
   * @throws IllegalArgumentException if uuid is null, unknown or identifies a 3D resource
   */
  public NoiseSampler2D noise2D(UUID uuid, long seed) {
    var n = find(uuid);
    if (n.dimension != 2) throw new IllegalArgumentException("Expected 2D resource");
    var c = new NoiseCompiler(snapshot, seed);
    var f = c.compile(uuid);
    return (x, z) -> f.sample(x, 0, z);
  }

  /**
   * Compiles the 3D resource identified by its stable UUID for this seed.
   *
   * @throws IllegalArgumentException if uuid is null, unknown or identifies a 2D resource
   */
  public NoiseSampler3D noise3D(UUID uuid, long seed) {
    var n = find(uuid);
    if (n.dimension != 3) throw new IllegalArgumentException("Expected 3D resource");
    var c = new NoiseCompiler(snapshot, seed);
    var f = c.compile(uuid);
    return f::sample;
  }

  /**
   * Resolves an exact, case-sensitive visible ID in the 2D namespace to its stable UUID.
   *
   * @throws IllegalArgumentException if id is null or no 2D resource has that exact ID
   */
  public NoiseSampler2D noise2D(String id, long seed) {
    return noise2D(
        snapshot.noise2D.stream()
            .filter(n -> n.id.equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown 2D noise " + id))
            .uuid,
        seed);
  }

  /**
   * Resolves an exact, case-sensitive visible ID in the 3D namespace to its stable UUID.
   *
   * @throws IllegalArgumentException if id is null or no 3D resource has that exact ID
   */
  public NoiseSampler3D noise3D(String id, long seed) {
    return noise3D(
        snapshot.noise3D.stream()
            .filter(n -> n.id.equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown 3D noise " + id))
            .uuid,
        seed);
  }

  /**
   * Low-volume diagnostic replay; does not populate a world/chunk cache or mutate this snapshot.
   */
  public io.voxelcreator.core.NoiseTrace explainNoise(
      UUID id, long seed, double x, double y, double z, int limit) {
    find(id);
    return new NoiseCompiler(snapshot, seed).explain(id, x, y, z, limit);
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
