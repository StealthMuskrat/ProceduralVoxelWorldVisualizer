package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.core.EvaluationFailure;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferred engine-JAR-only oracle for shared canonical fields and separate placement state. */
public final class WorldCompilationConsumer {
  private static final int[] OFFSETS = {0, 1, -2, 3};
  private record Fixture(ProjectData source, int dimension, boolean failing) {}
  private record Retained(VoxelChunk chunk, Fixture fixture, long seed, int prefix) {}
  private static UUID id(int value) { return new UUID(0, value); }

  private static Fixture fixture(int dimension, boolean failing) {
    var p = new ProjectData(); p.project.uuid = id(1); p.worldGenerator.uuid = id(2);
    p.project.seed = 17; p.project.minY = -16; p.project.maxY = 15;
    var base = new Noise(); base.uuid = id(100); base.id = "base"; base.dimension = dimension;
    base.seedOffset = 11; base.algorithm.key = "CONSTANT";
    base.algorithm.parameters.clear(); base.algorithm.parameters.put("value", .25);
    var expression = ProcessingRegistry.create("EXPRESSION", "coordinates", dimension); expression.uuid = id(110);
    expression.parameters.put("expression", ProjectIO.JSON.valueToTree(failing ? "1/(x-1)"
        : dimension == 2 ? "source+x+2*z+(projectSeed%97)/128"
        : "source+x+2*z+4*y+(projectSeed%97)/128"));
    base.processing.add(expression); base.outputBinding = Binding.earlier(expression.uuid);
    var dependent = new Noise(); dependent.uuid = id(101); dependent.id = "dependent";
    dependent.dimension = dimension; dependent.seedOffset = -7; dependent.algorithm.key = "CONSTANT";
    var twice = ProcessingRegistry.create("EXPRESSION", "twice", dimension); twice.uuid = id(111);
    twice.parameters.put("expression", ProjectIO.JSON.valueToTree("a*2"));
    var binding = new Binding(); binding.sourceKind = "Noise Generator Reference"; binding.noiseGeneratorUuid = base.uuid;
    twice.inputs.put("a", binding); dependent.processing.add(twice); dependent.outputBinding = Binding.earlier(twice.uuid);
    (dimension == 2 ? p.noise2D : p.noise3D).addAll(List.of(base, dependent));
    var layer = new Layer(); layer.uuid = id(200); layer.id = "layer";
    layer.defaults.seedOffset = 37; layer.defaults.threshold = .5;
    layer.defaults.probability = .5; layer.defaults.action = "SET"; layer.defaults.voxelTypeUuid = id(10);
    p.worldGenerator.layers.add(layer);
    for (int i = 0; i < 4; i++) {
      var type = new VoxelType(); type.uuid = id(10 + i); type.id = "type-" + i; p.voxelTypes.add(type);
      var op = new Operation(); op.uuid = id(300 + i); op.id = "placement-" + i;
      op.noiseGeneratorUuid = i % 2 == 0 ? base.uuid : dependent.uuid; op.interpretation = "SELECTION";
      if (i < 2 || i == 3) op.overrides.put("seedOffset", ProjectIO.JSON.valueToTree(i < 2 ? 0 : 37));
      if (i % 2 == 0) op.overrides.put("threshold", ProjectIO.JSON.valueToTree(0));
      if (i == 1) op.overrides.put("probability", ProjectIO.JSON.valueToTree(1));
      op.overrides.put("offsetX", ProjectIO.JSON.valueToTree(OFFSETS[i]));
      op.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(type.uuid)); layer.operations.add(op);
    }
    return new Fixture(p, dimension, failing);
  }

  // Independent seed/draw specification: no engine hash, compiler or resolver produces expectations.
  private static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }
  private static long seed(long parent, UUID uuid, long offset) {
    return mix(parent ^ mix(uuid.getMostSignificantBits())
        ^ Long.rotateLeft(mix(uuid.getLeastSignificantBits()), 17) ^ mix(offset));
  }
  private static long effective(long context, int placement) { return placement < 2 ? context : mix(context ^ 37); }
  private static double field(Fixture f, long context, int i, int x, int y, int z) {
    double sx = x + OFFSETS[i];
    double value;
    if (f.failing) value = 1 / (sx - 1);
    else {
      value = .25 + sx + 2 * z;
      if (f.dimension == 3) value += 4 * y;
      value += ((double) effective(context, i) % 97) / 128;
    }
    return i % 2 == 0 ? value : value * 2;
  }
  private static double draw(long context, int i, int x, int y, int z) {
    long resource = seed(effective(context, i), id(i % 2 == 0 ? 100 : 101), i % 2 == 0 ? 11 : -7);
    long probability = seed(resource, id(300 + i), 0);
    return (mix(probability ^ mix((long) x * 0x9e3779b97f4a7c15L)
        ^ Long.rotateLeft(mix((long) y * 0xc2b2ae3d27d4eb4fL), 21)
        ^ Long.rotateLeft(mix((long) z * 0x165667b19e3779f9L), 42)) >>> 11) * 0x1.0p-53;
  }
  private static int expected(Fixture f, long context, int x, int y, int z, int prefix) {
    int value = 0;
    for (int i = 0; i < prefix; i++)
      if (field(f, context, i, x, y, z) >= (i % 2 == 0 ? 0 : .5)
          && draw(context, i, x, y, z) < (i == 1 ? 1 : .5)) value = i + 1;
    return value;
  }
  private static long cells(Retained retained) {
    var chunk = retained.chunk; int root = chunk.size(); long count = 0;
    for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++) {
      require(chunk.getVoxelTypeId(x, y, z) == expected(retained.fixture, retained.seed,
          chunk.position().x() * root + x, chunk.position().y() * root + y,
          chunk.position().z() * root + z, retained.prefix), "Exact retained voxel differs"); count++;
    }
    return count;
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var finals = new ArrayList<Retained>(); var stages = new ArrayList<Retained>();
    long worlds = 0, traces = 0, fields = 0, cancelled = 0, failures = 0;
    for (int dimension : new int[] {2, 3}) {
      var f = fixture(dimension, false); String original = ProjectIO.JSON.writeValueAsString(f.source);
      var result = new ProjectCompiler().compileWorld(f.source);
      require(result.success() && original.equals(ProjectIO.JSON.writeValueAsString(f.source)), "Compilation changed caller project");
      var compiled = result.compiled();
      // Context construction must use the compiled definition despite later caller changes.
      f.source.project.seed = 999; f.source.worldGenerator.layers.clear();
      f.source.noise2D.clear(); f.source.noise3D.clear();
      for (int workers : new int[] {1, 3}) try (var engine = new NoiseGeneratorEngine(workers, 16, 16L << 20)) {
        for (int root : new int[] {2, 4}) {
          var policy = workers == 1 ? WorldOptions.CachePolicy.NO_CACHE : WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE;
          try (var first = engine.openWorld(compiled, new WorldOptions(null, root, policy, 1L << 20));
              var second = engine.openWorld(compiled, new WorldOptions(-31L, root, policy, 1L << 20))) {
            var pending = new ArrayList<CompletableFuture<VoxelChunk>>();
            for (var world : List.of(first, second)) for (int cx : new int[] {-1, 0})
              pending.add(world.generateChunkAsync(new ChunkPos(cx, 0, 0)));
            int task = 0;
            for (var world : List.of(first, second)) {
              long context = world == first ? 17 : -31;
              for (int cx : new int[] {-1, 0}) {
                var chunk = pending.get(task++).get(10, TimeUnit.SECONDS); finals.add(new Retained(chunk, f, context, 4));
                if (workers == 3) require(world.generateChunk(new ChunkPos(cx, 0, 0)) == chunk, "Cache did not retain completed chunk");
              }
              var token = new CancellationToken(); token.cancel();
              try { world.generateChunkAsync(new ChunkPos(0, 0, 0), token).join(); throw new AssertionError("Cancelled request completed"); }
              catch (CancellationException expected) { cancelled++; }
              for (int z : new int[] {-2, 0, 3}) for (int x : new int[] {-3, 0, 5}) {
                var trace = world.trace(new BlockPos(x, 1, z)); require(trace.steps().size() == 4, "Missing placement trace");
                for (int i = 0; i < 4; i++) {
                  var step = trace.steps().get(i); bits(field(f, context, i, x, 1, z), step.field());
                  bits(draw(context, i, x, 1, z), step.details().random());
                  require(step.operationUuid().equals(id(300 + i)) && step.result() == expected(f, context, x, 1, z, i + 1)
                      && step.details().settingTargets().get("threshold").override() == (i % 2 == 0), "Placement state/provenance leaked"); fields++;
                }
                require(trace.finalType() == expected(f, context, x, 1, z, 4), "Trace final state differs"); traces++;
              }
              var pair = world.generateStageChunk(new ChunkPos(0, 0, 0), id(303));
              stages.add(new Retained(pair.before(), f, context, 3)); stages.add(new Retained(pair.after(), f, context, 4)); worlds++;
            }
            first.close();
            require(second.getVoxelTypeId(new BlockPos(0, 0, 0)) == expected(f, -31, 0, 0, 0, 4), "Peer context closure leaked");
          }
          require(engine.metrics().stagingBytes() == 0, "Staging reservation leaked");
        }
      }
    }
    for (int dimension : new int[] {2, 3}) for (int workers : new int[] {1, 3}) {
      var f = fixture(dimension, true); var compiled = new CompiledProject(f.source);
      try (var engine = new NoiseGeneratorEngine(workers, 8, 8L << 20);
          var world = engine.openWorld(compiled, new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
        try { world.generateChunk(new ChunkPos(0, 0, 0)); throw new AssertionError("Invalid sample published"); }
        catch (EvaluationFailure failure) {
          require(failure.resourceUuid.equals(id(100)) && failure.itemUuid.equals(id(110)) && failure.x == 1,
              "Shared dependency lost innermost failure identity"); failures++;
        }
        require(world.cacheStats().chunks() == 0 && engine.metrics().stagingBytes() == 0, "Failed generation retained partial work");
        finals.add(new Retained(world.generateChunk(new ChunkPos(2, 0, 0)), f, 17, 4));
      }
    }
    long finalCells = 0, stageCells = 0;
    for (var retained : finals) finalCells += cells(retained);
    for (var retained : stages) stageCells += cells(retained);
    require(worlds == 16 && traces == 144 && fields == 576 && cancelled == 16 && failures == 4
        && finals.size() == 36 && finalCells == 1184 && stages.size() == 32 && stageCells == 1152, "Replay dimensions changed");
    Files.writeString(out.resolve("world-compilation-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "healthyWorlds", worlds, "traces", traces, "fieldAndDrawPairs", fields, "cancelledRequests", cancelled,
        "failedSamples", failures, "retainedFinalChunks", finals.size(), "retainedFinalCells", finalCells,
        "retainedStageSnapshots", stages.size(), "retainedStageCells", stageCells, "roots", List.of(2, 4))));
    System.out.println("WORLD_COMPILATION_CONSUMER_PASS");
  }
  private static void bits(double expected, double actual) {
    require(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual), "Field/draw raw bits differ");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
