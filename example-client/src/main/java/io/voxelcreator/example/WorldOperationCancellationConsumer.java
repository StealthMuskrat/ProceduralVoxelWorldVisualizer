package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred engine-JAR-only operation checkpoints with public profiler phase observations. */
public final class WorldOperationCancellationConsumer {
  private static final ChunkPos GOOD = new ChunkPos(0, 0, 0), BAD = new ChunkPos(-1, 0, 0);
  private record Fixture(ProjectData project, UUID first, UUID second, UUID stage, UUID badExpression, Profiler.Kind boundary) {}
  @FunctionalInterface private interface Action { void run() throws Exception; }
  private static Fixture fixture(int mode, boolean split) {
    var p = new ProjectData(); p.project.minY = -8; p.project.maxY = 7;
    int dimension = mode == 2 ? 3 : 2;
    var a = new VoxelType(); a.id = "first"; var b = new VoxelType(); b.id = "second"; p.voxelTypes.addAll(List.of(a, b));
    var good = new Noise(); good.id = "constant"; good.dimension = dimension; good.algorithm.key = "CONSTANT";
    good.algorithm.parameters.clear(); good.algorithm.parameters.put("value", .25);
    var bad = new Noise(); bad.id = "domain"; bad.dimension = dimension; bad.algorithm.key = "CONSTANT";
    var expression = ProcessingRegistry.create("EXPRESSION", "domain", dimension);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree("sqrt(x)"));
    bad.processing.add(expression); bad.outputBinding = Binding.earlier(expression.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).addAll(List.of(good, bad));
    var firstLayer = new Layer(); firstLayer.id = "first-layer"; firstLayer.defaults.action = "SET"; firstLayer.defaults.voxelTypeUuid = a.uuid;
    if (mode == 1) firstLayer.defaults.samplingTransforms.add(new SamplingTransform());
    p.worldGenerator.layers.add(firstLayer);
    var first = new Operation(); first.id = "first"; first.interpretation = "ALL"; first.noiseGeneratorUuid = good.uuid; firstLayer.operations.add(first);
    var secondLayer = firstLayer;
    if (split) {
      secondLayer = new Layer(); secondLayer.id = "second-layer"; secondLayer.defaults.action = "SET"; secondLayer.defaults.voxelTypeUuid = b.uuid;
      if (mode == 1) secondLayer.defaults.samplingTransforms.add(new SamplingTransform());
      p.worldGenerator.layers.add(secondLayer);
    }
    var second = new Operation(); second.id = "second"; second.interpretation = "ALL"; second.noiseGeneratorUuid = bad.uuid;
    if (!split) second.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(b.uuid));
    secondLayer.operations.add(second);
    return new Fixture(p, first.uuid, second.uuid, secondLayer.uuid, expression.uuid,
        mode == 0 ? Profiler.Kind.FIELD_PREFETCH : Profiler.Kind.OPERATION);
  }
  private static WorldOptions options(int root) { return new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0); }
  private static Profiler.Capture capture(NoiseGeneratorEngine e) { return e.profiler().start(new Profiler.Options(60000, 4096, 128, 1, true)); }
  private static boolean firstFinished(Profiler.Capture c, Fixture f) {
    return c.snapshot().samples().stream().anyMatch(s -> s.kind() == f.boundary && f.first.equals(s.sourceUuid()) && s.outcome() == Profiler.Outcome.SUCCESS);
  }
  private static void stopped(Profiler.Capture c, Fixture f, NoiseGeneratorEngine e, WorldContext w) {
    require(firstFinished(c, f), "First operation did not finish");
    require(c.snapshot().samples().stream().noneMatch(s -> s.kind() == f.boundary && f.second.equals(s.sourceUuid())), "Second operation began after boundary rejection");
    require(w.inFlightChunks() == 0 && w.cacheStats().chunks() == 0 && e.metrics().stagingBytes() == 0, "Boundary failure retained admission/storage");
  }
  private static void cancelled(Action action) throws Exception {
    try { action.run(); throw new AssertionError("Boundary cancellation was ignored"); }
    catch (CancellationException expected) { }
  }
  private static void cancellation(int mode, int root, boolean split, int route, List<VoxelChunk> retained) throws Exception {
    var f = fixture(mode, split); String authored = ProjectIO.JSON.writeValueAsString(f.project);
    try (var e = new NoiseGeneratorEngine(1, 8, 16L << 20); var w = e.openWorld(new CompiledProject(f.project), options(root))) {
      retained.add(w.generateChunk(GOOD)); var c = capture(e);
      var token = new CancellationToken(() -> firstFinished(c, f));
      if (route == 2) cancelled(() -> w.generateStageChunk(BAD, f.stage, token));
      else {
        var future = route == 0 ? w.generateChunkAsync(BAD, token) : w.generateChunkAsync(BAD, token, GenerationPriority.INTERACTIVE);
        cancelled(() -> future.get(5, TimeUnit.SECONDS));
      }
      stopped(c, f, e, w); require(e.metrics().completed() == 1, "Cancelled generation published a chunk");
    }
    require(authored.equals(ProjectIO.JSON.writeValueAsString(f.project)), "Cancellation changed caller JSON");
  }
  private static void faults(int mode, List<VoxelChunk> retained) throws Exception {
    var f = fixture(mode, false); String authored = ProjectIO.JSON.writeValueAsString(f.project); var fault = new IllegalStateException("parent-boundary");
    try (var e = new NoiseGeneratorEngine(1, 8, 16L << 20); var w = e.openWorld(new CompiledProject(f.project), options(4))) {
      retained.add(w.generateChunk(GOOD)); var c = capture(e);
      var token = new CancellationToken(() -> { if (firstFinished(c, f)) throw fault; return false; });
      try { w.generateStageChunk(BAD, f.stage, token); throw new AssertionError("Parent fault was ignored"); }
      catch (RuntimeException actual) { require(actual == fault, "Parent fault identity changed"); }
      stopped(c, f, e, w);
      try { w.generateChunk(BAD); throw new AssertionError("Uncancelled field fault was ignored"); }
      catch (EvaluationFailure actual) { require(f.badExpression.equals(actual.itemUuid), "Field fault lost source identity"); }
      retained.add(w.generateChunk(GOOD));
      require(e.metrics().completed() == 2 && e.metrics().stagingBytes() == 0 && w.cacheStats().chunks() == 0, "Retry retained failed work");
    }
    require(authored.equals(ProjectIO.JSON.writeValueAsString(f.project)), "Fault/retry changed caller JSON");
  }
  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var retained = new ArrayList<VoxelChunk>(); long cancellations = 0;
    for (int mode = 0; mode < 3; mode++) for (int root : new int[] {2, 4, 8}) {
      for (boolean split : new boolean[] {false, true}) for (int route = 0; route < 2; route++) {
        cancellation(mode, root, split, route, retained); cancellations++;
      }
      cancellation(mode, root, false, 2, retained); cancellations++;
    }
    for (int mode = 0; mode < 3; mode++) faults(mode, retained);
    long cells = 0, occupied = 0;
    for (var chunk : retained) {
      int size = chunk.size(), volume = size * size * size; var visited = new BitSet(volume);
      for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
        require(chunk.getVoxelTypeId(x, y, z) == 2, "Retained complete chunk changed"); cells++;
      }
      chunk.forEachOccupied((x, y, z, type) -> {
        require(x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size && type == 2, "Occupied coordinate/type changed");
        int index = (z * size + y) * size + x; require(!visited.get(index), "Duplicate occupied cell"); visited.set(index);
      });
      require(visited.cardinality() == volume && chunk.storageStatistics().occupiedVoxels() == volume
          && Arrays.equals(new int[] {0, 2}, chunk.paletteRuntimeIds()), "Retained occupied/palette metadata changed");
      occupied += visited.cardinality();
    }
    require(cancellations == 45 && retained.size() == 51 && cells == 9144 && occupied == cells, "Operation replay dimensions changed");
    Files.writeString(out.resolve("operation-cancellation-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "cancellations", cancellations, "asyncRequests", 36, "stageRequests", 9,
        "parentFaults", 3, "fieldFaults", 3, "healthyRetries", 3, "retainedChunks", retained.size(),
        "retainedCells", cells, "occupiedCells", occupied, "roots", List.of(2, 4, 8))));
    System.out.println("WORLD_OPERATION_CANCELLATION_CONSUMER_PASS");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
