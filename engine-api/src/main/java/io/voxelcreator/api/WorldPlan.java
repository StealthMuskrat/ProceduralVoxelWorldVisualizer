package io.voxelcreator.api;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;

final class WorldPlan {
  private record Op(
      UUID layerUuid,
      UUID uuid,
      UUID noiseUuid,
      String layerId,
      String id,
      String interpretation,
      int dimension,
      Settings settings,
      int target,
      long probabilitySeed,
      NoiseAlgorithms.Field field,
      Map<UUID, NoiseAlgorithms.Field> gates,
      Map<String, String> sources) {}

  private final List<Op> operations;
  private final int minY, maxY;
  private final VoxelRegistry registry;
  private final Map<UUID, Set<Integer>> sets;

  WorldPlan(ProjectData p, VoxelRegistry registry, long seed) {
    this.registry = registry;
    minY = p.project.minY;
    maxY = p.project.maxY;
    var compiled = new ArrayList<Op>();
    var memberships = new HashMap<UUID, Set<Integer>>();
    for (var set : p.voxelSets) {
      var member = new HashSet<Integer>();
      for (UUID u : set.voxelTypeUuids) member.add(registry.runtimeId(u));
      memberships.put(set.uuid, Set.copyOf(member));
    }
    sets = Map.copyOf(memberships);
    for (var layer : p.worldGenerator.layers) {
      if (!layer.enabled) continue;
      for (var op : layer.operations) {
        if (!op.enabled) continue;
        Settings s = SettingsResolver.resolve(layer, op);
        long opSeed = s.seedOffset == 0 ? seed : CoordinateHash.mix(seed ^ s.seedOffset);
        var compiler = new NoiseCompiler(p, opSeed);
        var noise =
            java.util.stream.Stream.concat(p.noise2D.stream(), p.noise3D.stream())
                .filter(n -> n.uuid.equals(op.noiseGeneratorUuid))
                .findFirst()
                .orElseThrow();
        var gates = new HashMap<UUID, NoiseAlgorithms.Field>();
        collectGates(s.fieldGates, gates, compiler);
        var sources = new LinkedHashMap<String, String>();
        for (String key : SettingsResolver.PROPERTIES)
          sources.put(key, SettingsResolver.source(op, key));
        compiled.add(
            new Op(
                layer.uuid,
                op.uuid,
                noise.uuid,
                layer.id,
                op.id,
                op.interpretation,
                noise.dimension,
                s,
                s.action.equals("SET") ? registry.runtimeId(s.voxelTypeUuid) : 0,
                CoordinateHash.seed(
                    CoordinateHash.seed(opSeed, noise.uuid, noise.seedOffset), op.uuid, 0),
                compiler.compile(noise.uuid),
                Map.copyOf(gates),
                Map.copyOf(sources)));
      }
    }
    operations = List.copyOf(compiled);
  }

  private void collectGates(Gate g, Map<UUID, NoiseAlgorithms.Field> map, NoiseCompiler c) {
    if (g.noiseGeneratorUuid != null)
      map.put(g.noiseGeneratorUuid, c.compile(g.noiseGeneratorUuid));
    for (var child : g.children) collectGates(child, map, c);
  }

  long stagingEstimate(int size) {
    return operations.isEmpty()
        ? 256
        : 128L
            + 16L * size * size * size
            + 8L * size * size * operations.stream().filter(o -> o.dimension == 2).count();
  }

  boolean empty() {
    return operations.isEmpty();
  }

  int sample(int x, int y, int z) {
    return sample(x, y, z, null, Integer.MAX_VALUE, null, 0);
  }

  int stageSample(int x, int y, int z, int stage) {
    return sample(x, y, z, null, stage, null, 0);
  }

  GenerationTrace trace(BlockPos p) {
    var trace = new ArrayList<GenerationTrace.Step>();
    int result = sample(p.x(), p.y(), p.z(), trace, Integer.MAX_VALUE, null, 0);
    return new GenerationTrace(p, result, List.copyOf(trace));
  }

  VoxelChunk generate(ChunkPos pos, int size, CancellationToken token) {
    if (empty() || (long) pos.y() * size > maxY || (long) pos.y() * size + size - 1 < minY)
      return VoxelChunk.uniform(pos, size, 0);
    int ox = pos.x() * size, oy = pos.y() * size, oz = pos.z() * size;
    double[][] fields = new double[operations.size()][];
    for (int i = 0; i < fields.length; i++) {
      Op op = operations.get(i);
      if (op.dimension == 2) {
        fields[i] = new double[size * size];
        for (int z = 0; z < size; z++) {
          token.throwIfCancelled();
          for (int x = 0; x < size; x++) fields[i][z * size + x] = field(op, ox + x, 0, oz + z);
        }
      }
    }
    return VoxelChunk.generate(
        pos,
        size,
        (x, y, z) -> {
          token.throwIfCancelled();
          return sample(ox + x, oy + y, oz + z, null, Integer.MAX_VALUE, fields, z * size + x);
        });
  }

  private double field(Op op, int x, int y, int z) {
    var s = op.settings;
    return op.field.sample(
        x * s.scaleX + s.offsetX,
        op.dimension == 2 ? 0 : y * s.scaleY + s.offsetY,
        z * s.scaleZ + s.offsetZ);
  }

  private int sample(
      int x, int y, int z, List<GenerationTrace.Step> trace, int limit, double[][] cached, int at) {
    if (y < minY || y > maxY) return 0;
    int state = 0;
    int index = 0;
    for (Op op : operations) {
      if (index >= limit) break;
      var s = op.settings;
      double f = Double.NaN;
      int previous = state;
      String reason = "Selected";
      boolean choose = true;
      if (y < Math.max(minY, s.minY) || y > Math.min(maxY, s.maxY)) {
        choose = false;
        reason = "Outside effective Y interval";
      }
      if (choose) {
        f = cached != null && cached[index] != null ? cached[index][at] : field(op, x, y, z);
        double height = s.baseHeight + f * s.heightScale;
        if (op.interpretation.startsWith("HEIGHT") && !Double.isFinite(height))
          throw new EvaluationFailure(
              op.noiseUuid, op.uuid, x, y, z, "Nonfinite height interpretation");
        double rounded =
            switch (s.rounding) {
              case "FLOOR" -> StrictMath.floor(height);
              case "CEIL" -> StrictMath.ceil(height);
              case "ROUND" -> StrictMath.floor(height + .5);
              default -> throw new AssertionError();
            };
        choose =
            switch (op.interpretation) {
              case "HEIGHT_BELOW" -> y <= rounded;
              case "HEIGHT_ABOVE" -> y >= rounded;
              case "HEIGHT_BOUNDARY" -> y == rounded;
              case "SELECTION", "DENSITY" -> f >= s.threshold;
              default -> throw new AssertionError();
            };
        if (!choose) reason = "Field interpretation rejected sample";
      }
      if (choose && !gate(s.fieldGates, op, f, x, y, z)) {
        choose = false;
        reason = "Field gate rejected sample";
      }
      if (choose && !predicate(s.voxelStatePredicate, previous)) {
        choose = false;
        reason =
            "Previous committed voxel predicate rejected state " + registry.semanticId(previous);
      }
      double probability = s.probability * s.strength;
      double random = CoordinateHash.probability(op.probabilitySeed, x, y, z);
      if (choose && (probability <= 0 || (probability < 1 && random >= probability))) {
        choose = false;
        reason = "Probability rejected: " + random + " >= " + probability;
      }
      if (choose) {
        state =
            switch (s.action) {
              case "SET" -> op.target;
              case "CLEAR" -> 0;
              case "NO_CHANGE" -> state;
              default -> throw new AssertionError();
            };
        if (s.action.equals("NO_CHANGE")) reason = "Selected; NO_CHANGE";
      }
      if (trace != null)
        trace.add(
            new GenerationTrace.Step(
                op.layerUuid,
                op.uuid,
                op.noiseUuid,
                op.layerId,
                op.id,
                f,
                previous,
                state,
                choose ? s.action : "NO_CHANGE",
                reason,
                op.sources));
      index++;
    }
    return state;
  }

  private boolean predicate(Predicate p, int state) {
    return switch (p.kind) {
      case "ANY" -> true;
      case "EMPTY" -> state == 0;
      case "OCCUPIED" -> state > 0;
      case "TYPE_IS" -> state > 0 && state == registry.runtimeId(p.reference);
      case "TYPE_IS_NOT" -> state > 0 && state != registry.runtimeId(p.reference);
      case "IN_SET" -> state > 0 && sets.get(p.reference).contains(state);
      case "NOT_IN_SET" -> state > 0 && !sets.get(p.reference).contains(state);
      case "AND" -> {
        boolean match = true;
        for (var c : p.children)
          if (!predicate(c, state)) {
            match = false;
            break;
          }
        yield match;
      }
      case "OR" -> {
        boolean match = false;
        for (var c : p.children)
          if (predicate(c, state)) {
            match = true;
            break;
          }
        yield match;
      }
      default -> throw new AssertionError();
    };
  }

  private boolean gate(Gate g, Op op, double original, int x, int y, int z) {
    return switch (g.kind) {
      case "ALWAYS" -> true;
      case "AND" -> {
        boolean match = true;
        for (var c : g.children)
          if (!gate(c, op, original, x, y, z)) {
            match = false;
            break;
          }
        yield match;
      }
      case "OR" -> {
        boolean match = false;
        for (var c : g.children)
          if (gate(c, op, original, x, y, z)) {
            match = true;
            break;
          }
        yield match;
      }
      default -> {
        double value =
            g.noiseGeneratorUuid == null
                ? original
                : op.gates
                    .get(g.noiseGeneratorUuid)
                    .sample(
                        x * op.settings.scaleX + op.settings.offsetX,
                        y * op.settings.scaleY + op.settings.offsetY,
                        z * op.settings.scaleZ + op.settings.offsetZ);
        yield g.kind.equals("INTERVAL")
            ? value >= g.threshold && value <= g.max
            : switch (g.comparison) {
              case "GE" -> value >= g.threshold;
              case "GT" -> value > g.threshold;
              case "LE" -> value <= g.threshold;
              case "LT" -> value < g.threshold;
              case "EQ" -> value == g.threshold;
              case "NE" -> value != g.threshold;
              default -> throw new AssertionError();
            };
      }
    };
  }
}
