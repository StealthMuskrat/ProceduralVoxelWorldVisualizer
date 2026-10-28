package io.voxelcreator.api;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.WorldSemantics.Parameter;
import io.voxelcreator.model.WorldSemantics.Phase;
import io.voxelcreator.storage.*;
import java.util.*;

final class WorldPlan {
  private record NumericUse(
      int row,
      Parameter target,
      NumericDriver mapping,
      NoiseAlgorithms.Field field,
      int dimension) {}

  private final ThreadLocal<double[]> numericScratch =
      ThreadLocal.withInitial(() -> new double[Parameter.TRANSFORM_X.ordinal() + 64 * 6]);

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
      Map<UUID, NoiseAlgorithms.Field> inputs,
      Map<String, String> sources,
      WorldSampling sampling,
      List<Integer> intervalTargets,
      List<NumericUse> numeric,
      long fieldSeed,
      Map<String, GenerationTrace.SettingSource> settingTargets,
      Profiler.WorkSource workSource,
      boolean drivenSampling) {
    boolean columnCached() {
      return !drivenSampling && dimension == 2 && sampling.columnInvariant();
    }
  }

  private final ProjectData source;
  private final List<Op> operations;
  private final List<GenerationStage> stages;
  private final int minY, maxY;
  private final VoxelRegistry registry;
  private final Map<UUID, Set<Integer>> sets;
  private final Map<UUID, Integer> fieldDimensions;

  WorldPlan(ProjectData p, VoxelRegistry registry, long seed) {
    // CompiledProject supplies its private immutable source snapshot. Keep one source, not one
    // compiler/project copy per operation.
    source = p;
    this.registry = registry;
    var dimensions = new HashMap<UUID, Integer>();
    java.util.stream.Stream.concat(p.noise2D.stream(), p.noise3D.stream())
        .forEach(n -> dimensions.put(n.uuid, n.dimension));
    fieldDimensions = Map.copyOf(dimensions);
    minY = p.project.minY;
    maxY = p.project.maxY;
    var compiled = new ArrayList<Op>();
    var stageList = new ArrayList<GenerationStage>();
    var memberships = new HashMap<UUID, Set<Integer>>();
    for (var set : p.voxelSets) {
      var member = new HashSet<Integer>();
      for (UUID u : set.voxelTypeUuids) member.add(registry.runtimeId(u));
      memberships.put(set.uuid, Set.copyOf(member));
    }
    sets = Map.copyOf(memberships);
    for (var layer : p.worldGenerator.layers) {
      var layerWork = new Profiler.WorkSource(layer.uuid, "Layer " + layer.id, null);
      int layerStart = compiled.size();
      for (var op : layer.operations) {
        int opStart = compiled.size();
        stageList.add(
            new GenerationStage(
                op.uuid,
                layer.uuid,
                "operation",
                layer.id + " / " + op.id,
                opStart,
                opStart + (layer.enabled && op.enabled ? 1 : 0),
                layer.enabled && op.enabled));
        if (!layer.enabled || !op.enabled) continue;
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
        for (var driver : List.of(s.probabilityDriver, s.strengthDriver))
          if (driver.enabled && driver.noiseGeneratorUuid != null)
            gates.computeIfAbsent(driver.noiseGeneratorUuid, id -> compileField(id, compiler));
        var sources = new LinkedHashMap<String, String>();
        for (String key : SettingsResolver.PROPERTIES)
          sources.put(key, SettingsResolver.source(op, key));
        var targets = new LinkedHashMap<String, GenerationTrace.SettingSource>();
        for (String key : SettingsResolver.PROPERTIES) {
          boolean override = op.overrides.containsKey(key);
          targets.put(
              key,
              new GenerationTrace.SettingSource(override ? op.uuid : layer.uuid, key, override));
        }
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
                s.action.equals("SET") && s.typeSource.equals("FIXED")
                    ? registry.runtimeId(s.voxelTypeUuid)
                    : 0,
                CoordinateHash.seed(
                    CoordinateHash.seed(opSeed, noise.uuid, noise.seedOffset), op.uuid, 0),
                compileField(noise.uuid, compiler),
                Map.copyOf(gates),
                Map.copyOf(sources),
                new WorldSampling(s),
                s.typeIntervals.stream().map(r -> registry.runtimeId(r.voxelTypeUuid)).toList(),
                numericUses(s, op.interpretation, compiler),
                opSeed,
                Map.copyOf(targets),
                new Profiler.WorkSource(op.uuid, layer.id + " / " + op.id, layerWork),
                s.parameterDrivers.stream()
                    .anyMatch(
                        d ->
                            d.enabled
                                && WorldSemantics.parameter(d.destination).phase
                                    == Phase.SAMPLING)));
      }
      stageList.add(
          new GenerationStage(
              layer.uuid,
              layer.uuid,
              "layer",
              layer.id,
              layerStart,
              compiled.size(),
              layer.enabled));
    }
    operations = List.copyOf(compiled);
    stages = List.copyOf(stageList);
  }

  List<GenerationStage> stages() {
    return stages;
  }

  GenerationStage stage(UUID id) {
    return stages.stream()
        .filter(s -> s.uuid().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown stage " + id));
  }

  private NoiseAlgorithms.Field compileField(UUID id, NoiseCompiler compiler) {
    var field = compiler.compile(id);
    return fieldDimensions.get(id) == 2 ? (x, y, z) -> field.sample(x, 0, z) : field;
  }

  private List<NumericUse> numericUses(
      Settings settings, String interpretation, NoiseCompiler compiler) {
    var uses = new ArrayList<NumericUse>();
    for (int row = 0; row < settings.parameterDrivers.size(); row++) {
      var d = settings.parameterDrivers.get(row);
      if (!d.enabled || !WorldSemantics.parameterUsed(interpretation, d.destination)) continue;
      if (WorldSemantics.parameter(d.destination).transform()
          && !settings.samplingTransforms.get(d.transformIndex).enabled) continue;
      uses.add(
          new NumericUse(
              row,
              WorldSemantics.parameter(d.destination),
              d,
              d.noiseGeneratorUuid == null ? null : compileField(d.noiseGeneratorUuid, compiler),
              d.noiseGeneratorUuid == null ? 0 : fieldDimensions.get(d.noiseGeneratorUuid)));
    }
    return List.copyOf(uses);
  }

  private double[] initialValues(Op op) {
    var values = numericScratch.get();
    for (int slot = 0; slot < (op.drivenSampling ? Parameter.TRANSFORM_X.ordinal() : 5); slot++)
      values[slot] = WorldSemantics.PARAMETERS.get(slot).value(op.settings);
    for (var use : op.numeric)
      if (use.target.transform())
        values[use.target.slot(use.mapping.transformIndex)] =
            use.target.value(op.settings, use.mapping.transformIndex);
    return values;
  }

  private void numericValues(
      Op op,
      Phase phase,
      double primary,
      int x,
      int y,
      int z,
      double[] values,
      List<GenerationTrace.ParameterEvaluation> trace) {
    for (var use : op.numeric) {
      var d = use.mapping;
      var target = use.target;
      if (target.phase != phase) continue;
      boolean baseline = phase == Phase.SAMPLING;
      double sample =
          use.field == null
              ? primary
              : sampleField(op, use.field, use.dimension, x, y, z, baseline ? null : values);
      int slot = target.slot(d.transformIndex);
      double mapped = WorldSemantics.mapNumeric(d, sample), previous = values[slot];
      double next = WorldSemantics.combine(d.combination, previous, mapped);
      if (!Double.isFinite(next) || target.unitRange && (next < 0 || next > 1))
        throw new EvaluationFailure(
            op.noiseUuid,
            op.uuid,
            x,
            y,
            z,
            "parameterDrivers["
                + use.row
                + "] "
                + d.destination
                + " "
                + d.combination
                + " produced "
                + (Double.isFinite(next) ? "a value outside [0,1]" : "a nonfinite value"));
      values[slot] = next;
      if (trace != null) {
        var location =
            op.sampling.coordinates(
                x,
                y,
                z,
                use.field == null ? op.dimension : use.dimension,
                baseline ? null : op.drivenSampling ? values : null);
        trace.add(
            new GenerationTrace.ParameterEvaluation(
                use.row,
                d.destination,
                d.combination,
                d.noiseGeneratorUuid,
                sample,
                mapped,
                previous,
                next,
                phase.name(),
                location,
                target.transform() ? d.transformIndex : null));
      }
    }
  }

  private void collectGates(Gate g, Map<UUID, NoiseAlgorithms.Field> map, NoiseCompiler c) {
    if ((g.kind.equals("COMPARE") || g.kind.equals("INTERVAL")) && g.noiseGeneratorUuid != null)
      map.computeIfAbsent(g.noiseGeneratorUuid, id -> compileField(id, c));
    if (Set.of("AND", "OR", "NOT").contains(g.kind))
      for (var child : g.children) collectGates(child, map, c);
  }

  long stagingEstimate(int size) {
    return operations.isEmpty()
        ? 256
        : 128L
            + 16L * size * size * size
            + 32L * Math.min(registry.size(), (long) size * size * size)
            + 8L * size * size * operations.stream().filter(Op::columnCached).count();
  }

  boolean empty() {
    return operations.isEmpty();
  }

  int sample(int x, int y, int z) {
    return sample(x, y, z, null, Integer.MAX_VALUE, null, 0, null, null);
  }

  int stageSample(int x, int y, int z, int stage) {
    return sample(x, y, z, null, stage, null, 0, null, null);
  }

  GenerationTrace trace(BlockPos p) {
    var trace = new ArrayList<GenerationTrace.Step>();
    int result = sample(p.x(), p.y(), p.z(), trace, Integer.MAX_VALUE, null, 0, null, null);
    return new GenerationTrace(p, result, List.copyOf(trace));
  }

  VoxelChunk generate(ChunkPos pos, int size, CancellationToken token) {
    return generate(pos, size, token, null);
  }

  VoxelChunk generate(ChunkPos pos, int size, CancellationToken token, Profiler.Scope profile) {
    var work = profile == null ? null : profile.work();
    var outcome = Profiler.Outcome.FAILED;
    try {
      var result = generate(pos, size, token, Integer.MAX_VALUE, null, profile, work);
      outcome = Profiler.Outcome.SUCCESS;
      return result;
    } catch (java.util.concurrent.CancellationException cancelled) {
      outcome = Profiler.Outcome.CANCELLED;
      throw cancelled;
    } finally {
      if (work != null) work.flush(outcome);
    }
  }

  StageChunk generateStage(ChunkPos pos, int size, CancellationToken token, UUID id) {
    return generateStage(pos, size, token, id, null);
  }

  StageChunk generateStage(
      ChunkPos pos, int size, CancellationToken token, UUID id, Profiler.Scope profile) {
    GenerationStage stage = stage(id);
    if (stage.after() == 0
        || (long) pos.y() * size > maxY
        || (long) pos.y() * size + size - 1 < minY) {
      var empty = VoxelChunk.uniform(pos, size, 0);
      return new StageChunk(stage, empty, empty);
    }
    var work = profile == null ? null : profile.work();
    var outcome = Profiler.Outcome.FAILED;
    try {
      double[][] fields = fields(pos, size, token, stage.after(), profile, work);
      VoxelChunk before = generate(pos, size, token, stage.before(), fields, profile, work),
          after =
              stage.before() == stage.after()
                  ? before
                  : generate(pos, size, token, stage.after(), fields, profile, work);
      token.throwIfCancelled();
      outcome = Profiler.Outcome.SUCCESS;
      return new StageChunk(stage, before, after);
    } catch (java.util.concurrent.CancellationException cancelled) {
      outcome = Profiler.Outcome.CANCELLED;
      throw cancelled;
    } finally {
      if (work != null) work.flush(outcome);
    }
  }

  private double[][] fields(
      ChunkPos pos,
      int size,
      CancellationToken token,
      int limit,
      Profiler.Scope profile,
      Profiler.WorkBatch work) {
    int ox = pos.x() * size, oz = pos.z() * size;
    double[][] fields = new double[Math.min(limit, operations.size())][];
    for (int i = 0; i < fields.length; i++) {
      Op op = operations.get(i);
      if (op.columnCached()) {
        long stamp = profile == null ? 0 : profile.begin();
        long workStamp = work == null ? 0 : work.begin();
        var outcome = Profiler.Outcome.FAILED;
        try {
          fields[i] = new double[size * size];
          for (int z = 0; z < size; z++) {
            token.throwIfCancelled();
            for (int x = 0; x < size; x++) fields[i][z * size + x] = field(op, ox + x, 0, oz + z);
          }
          outcome = Profiler.Outcome.SUCCESS;
        } catch (java.util.concurrent.CancellationException cancelled) {
          outcome = Profiler.Outcome.CANCELLED;
          throw cancelled;
        } finally {
          if (workStamp != 0) work.finish(workStamp, op.workSource);
          if (stamp != 0)
            profile.finish(
                stamp, Profiler.Kind.FIELD_PREFETCH, op.uuid, op.layerId + " / " + op.id, outcome);
        }
      }
    }
    return fields;
  }

  private VoxelChunk generate(
      ChunkPos pos,
      int size,
      CancellationToken token,
      int limit,
      double[][] values,
      Profiler.Scope profile,
      Profiler.WorkBatch work) {
    if (empty()
        || limit == 0
        || (long) pos.y() * size > maxY
        || (long) pos.y() * size + size - 1 < minY) return VoxelChunk.uniform(pos, size, 0);
    int ox = pos.x() * size, oy = pos.y() * size, oz = pos.z() * size;
    double[][] fields = values == null ? fields(pos, size, token, limit, profile, work) : values;
    return VoxelChunk.generate(
        pos,
        size,
        (x, y, z) -> {
          token.throwIfCancelled();
          return sample(ox + x, oy + y, oz + z, null, limit, fields, z * size + x, profile, work);
        });
  }

  private double field(Op op, int x, int y, int z) {
    return sampleField(op, op.field, op.dimension, x, y, z);
  }

  private double sampleField(
      Op op, NoiseAlgorithms.Field field, int dimension, int x, int y, int z) {
    try {
      return op.sampling.sample(field, dimension, x, y, z);
    } catch (ArithmeticException failure) {
      throw new EvaluationFailure(op.noiseUuid, op.uuid, x, y, z, failure.getMessage());
    }
  }

  private double sampleField(
      Op op, NoiseAlgorithms.Field field, int dimension, int x, int y, int z, double[] values) {
    if (values == null || !op.drivenSampling) return sampleField(op, field, dimension, x, y, z);
    try {
      return op.sampling.sample(field, dimension, x, y, z, values);
    } catch (ArithmeticException failure) {
      throw new EvaluationFailure(op.noiseUuid, op.uuid, x, y, z, failure.getMessage());
    }
  }

  private double driver(
      Op op,
      FieldDriver driver,
      String property,
      double original,
      int x,
      int y,
      int z,
      double constant,
      double[] values,
      List<GenerationTrace.DriverEvaluation> report) {
    if (!driver.enabled) return 1;
    double value =
        driver.noiseGeneratorUuid == null
            ? original
            : sampleField(
                op,
                op.inputs.get(driver.noiseGeneratorUuid),
                fieldDimensions.get(driver.noiseGeneratorUuid),
                x,
                y,
                z,
                values);
    double mapped = WorldSemantics.map(driver, value);
    if (report != null) {
      report.add(
          new GenerationTrace.DriverEvaluation(
              property, driver.noiseGeneratorUuid, value, mapped, constant, constant * mapped));
    }
    return mapped;
  }

  private int sample(
      int x,
      int y,
      int z,
      List<GenerationTrace.Step> trace,
      int limit,
      double[][] cached,
      int at,
      Profiler.Scope profile,
      Profiler.WorkBatch work) {
    if (y < minY || y > maxY) return 0;
    int state = 0;
    int index = 0;
    int traceRemaining = 1024;
    for (Op op : operations) {
      if (index >= limit) break;
      long profileStamp = profile == null ? 0 : profile.beginOperation();
      long workStamp = work == null ? 0 : work.begin();
      var profileOutcome = Profiler.Outcome.FAILED;
      try {
        var s = op.settings;
        double f = Double.NaN;
        int previous = state;
        String reason = "Selected";
        boolean gatePassed = true, predicatePassed = true;
        boolean gateReached = false, predicateReached = false;
        var gateTrace = trace == null ? null : new ArrayList<GenerationTrace.GateEvaluation>();
        var driverTrace = trace == null ? null : new ArrayList<GenerationTrace.DriverEvaluation>();
        var parameterTrace =
            trace == null ? null : new ArrayList<GenerationTrace.ParameterEvaluation>();
        NoiseTrace primaryTrace = null;
        var resolved = initialValues(op);
        GenerationTrace.SampleCoordinates actualCoordinates = null;
        var comparisons = trace == null ? null : new ArrayList<GenerationTrace.Comparison>();
        if (comparisons != null) {
          comparisons.add(
              new GenerationTrace.Comparison(
                  "minY", y, ">=", Math.max(minY, s.minY), y >= Math.max(minY, s.minY)));
          comparisons.add(
              new GenerationTrace.Comparison(
                  "maxY", y, "<=", Math.min(maxY, s.maxY), y <= Math.min(maxY, s.maxY)));
        }
        int target = op.target, matchedInterval = -1;
        boolean choose = true;
        if (y < Math.max(minY, s.minY) || y > Math.min(maxY, s.maxY)) {
          choose = false;
          reason = "Outside effective Y interval";
        }
        if (choose) {
          numericValues(op, Phase.SAMPLING, Double.NaN, x, y, z, resolved, parameterTrace);
          if (trace != null) {
            var location =
                op.sampling.coordinates(x, y, z, op.dimension, op.drivenSampling ? resolved : null);
            actualCoordinates = location;
            primaryTrace =
                new NoiseCompiler(source, op.fieldSeed)
                    .explain(
                        op.noiseUuid, location.x(), location.y(), location.z(), traceRemaining);
            traceRemaining -= primaryTrace.events().size();
            f = primaryTrace.value();
          } else
            f =
                cached != null && cached[index] != null
                    ? cached[index][at]
                    : sampleField(op, op.field, op.dimension, x, y, z, resolved);
          numericValues(op, Phase.INTERPRETATION, f, x, y, z, resolved, parameterTrace);
          double threshold = resolved[Parameter.THRESHOLD.ordinal()],
              baseHeight = resolved[Parameter.BASE_HEIGHT.ordinal()],
              heightScale = resolved[Parameter.HEIGHT_SCALE.ordinal()];
          double height = baseHeight + f * heightScale;
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
                case "SELECTION", "DENSITY" -> f >= threshold;
                case "TYPE_SELECTION", "ALL" -> true;
                default -> throw new AssertionError();
              };
          if (comparisons != null) {
            if (op.interpretation.startsWith("HEIGHT"))
              comparisons.add(
                  new GenerationTrace.Comparison(
                      "baseHeight",
                      y,
                      op.interpretation.equals("HEIGHT_BELOW")
                          ? "<="
                          : op.interpretation.equals("HEIGHT_ABOVE") ? ">=" : "==",
                      rounded,
                      choose));
            else if (Set.of("SELECTION", "DENSITY").contains(op.interpretation))
              comparisons.add(
                  new GenerationTrace.Comparison("threshold", f, ">=", threshold, choose));
          }
          if (!choose) reason = "Field interpretation rejected sample";
          if (choose && s.typeSource.equals("INTERVALS")) {
            matchedInterval = WorldSemantics.interval(s.typeIntervals, f);
            if (matchedInterval < 0) {
              choose = false;
              reason = "No type interval matched; NO_CHANGE";
            } else target = op.intervalTargets.get(matchedInterval);
          }
        }
        if (choose) numericValues(op, Phase.ACCEPTANCE, f, x, y, z, resolved, parameterTrace);
        double probability =
            resolved[Parameter.PROBABILITY.ordinal()] * resolved[Parameter.STRENGTH.ordinal()];
        if (choose) {
          probability *=
              driver(
                  op,
                  s.probabilityDriver,
                  "probability",
                  f,
                  x,
                  y,
                  z,
                  resolved[Parameter.PROBABILITY.ordinal()],
                  resolved,
                  driverTrace);
          probability *=
              driver(
                  op,
                  s.strengthDriver,
                  "strength",
                  f,
                  x,
                  y,
                  z,
                  resolved[Parameter.STRENGTH.ordinal()],
                  resolved,
                  driverTrace);
        }
        if (choose) {
          gateReached = true;
          gatePassed = gate(s.fieldGates, op, f, x, y, z, resolved, gateTrace, "fieldGates");
        }
        if (choose && !gatePassed) {
          choose = false;
          reason = "Field gate rejected sample";
        }
        if (choose) {
          predicateReached = true;
          predicatePassed = predicate(s.voxelStatePredicate, previous);
        }
        if (choose && !predicatePassed) {
          choose = false;
          reason =
              "Previous committed voxel predicate rejected state " + registry.semanticId(previous);
        }
        double random = CoordinateHash.probability(op.probabilitySeed, x, y, z);
        if (choose && (probability <= 0 || (probability < 1 && random >= probability))) {
          choose = false;
          reason = "Probability rejected: " + random + " >= " + probability;
        }
        if (choose) {
          state =
              switch (s.action) {
                case "SET" -> target;
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
                  op.sources,
                  new GenerationTrace.Details(
                      settingsValues(s),
                      List.copyOf(gateTrace),
                      gateReached ? gatePassed : null,
                      predicateReached ? predicatePassed : null,
                      probability,
                      random,
                      choose,
                      actualCoordinates,
                      matchedInterval < 0 ? null : matchedInterval,
                      target,
                      List.copyOf(driverTrace),
                      List.copyOf(parameterTrace),
                      op.settingTargets,
                      List.copyOf(comparisons),
                      primaryTrace)));
        index++;
        profileOutcome = Profiler.Outcome.SUCCESS;
      } finally {
        if (workStamp != 0) work.finish(workStamp, op.workSource);
        if (profileStamp != 0)
          profile.finish(
              profileStamp,
              Profiler.Kind.OPERATION,
              op.uuid,
              op.layerId + " / " + op.id,
              profileOutcome);
      }
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

  private static Map<String, String> settingsValues(Settings settings) {
    var values = new LinkedHashMap<String, String>();
    ProjectIO.JSON
        .valueToTree(settings)
        .fields()
        .forEachRemaining(e -> values.put(e.getKey(), e.getValue().toString()));
    return Collections.unmodifiableMap(values);
  }

  private boolean gate(
      Gate g,
      Op op,
      double original,
      int x,
      int y,
      int z,
      double[] values,
      List<GenerationTrace.GateEvaluation> report,
      String path) {
    double value = Double.NaN;
    boolean result;
    Double gateRandom = null;
    if (g.kind.equals("ALWAYS")) result = true;
    else if (g.kind.equals("NOT"))
      result = !gate(g.children.getFirst(), op, original, x, y, z, values, report, path + "/0");
    else if (g.kind.equals("PROBABILITY")) {
      value = g.probability;
      gateRandom =
          CoordinateHash.probability(
              CoordinateHash.mix(
                  op.probabilitySeed
                      ^ 0x6a09e667f3bcc909L
                      ^ CoordinateHash.mix(g.seedOffset)
                      ^ CoordinateHash.mix(path.hashCode())),
              x,
              y,
              z);
      result = g.probability >= 1 || g.probability > 0 && gateRandom < g.probability;
    } else if (g.kind.equals("AND") || g.kind.equals("OR")) {
      result = g.kind.equals("AND");
      for (int i = 0; i < g.children.size(); i++) {
        boolean child =
            gate(g.children.get(i), op, original, x, y, z, values, report, path + "/" + i);
        if (g.kind.equals("AND") && !child) {
          result = false;
          break;
        }
        if (g.kind.equals("OR") && child) {
          result = true;
          break;
        }
      }
    } else {
      value =
          g.noiseGeneratorUuid == null
              ? original
              : sampleField(
                  op,
                  op.inputs.get(g.noiseGeneratorUuid),
                  fieldDimensions.get(g.noiseGeneratorUuid),
                  x,
                  y,
                  z,
                  values);
      result =
          g.kind.equals("INTERVAL")
              ? WorldSemantics.contains(value, g.threshold, g.max, g.includeMin, g.includeMax)
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
    if (report != null)
      report.add(
          new GenerationTrace.GateEvaluation(
              path,
              g.kind,
              g.noiseGeneratorUuid,
              Double.isNaN(value) ? null : value,
              result,
              gateRandom));
    return result;
  }
}
