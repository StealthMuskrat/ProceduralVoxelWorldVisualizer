package io.voxelcreator.core;

import static io.voxelcreator.core.NoiseAlgorithms.Field;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/**
 * Canonical panel compiler. Mutable definitions are copied once; runtime uses immutable
 * instructions and primitive scratch.
 */
public final class NoiseCompiler {
  private final ProjectData project;
  private final long runtimeSeed;
  private final boolean tracing;
  private final ThreadLocal<NoiseTrace.Capture> capture = new ThreadLocal<>();
  private final Map<UUID, Noise> resources = new LinkedHashMap<>();
  private final Map<UUID, Field> compiled = new HashMap<>();
  private final Set<UUID> building = new LinkedHashSet<>();
  private final Map<UUID, Long> resourceWork = new HashMap<>();
  private final Map<UUID, Integer> resourceDepth = new HashMap<>();

  private record FieldPlan(Field field, long work, int depth) {}

  public record Statistics(
      int enabledItems,
      int constantItems,
      int sharedItems,
      long expressionInstructions,
      int scratchSlots,
      long sampleWork,
      int dependencyDepth) {}

  private final Map<UUID, Statistics> statistics = new HashMap<>();

  public Statistics statistics(UUID resource) {
    compile(resource);
    return statistics.get(resource);
  }

  private record InputKey(String type, List<Long> literal, Number evaluator) {}

  private record OperationKey(
      String kind,
      String type,
      Map<String, InputKey> inputs,
      com.fasterxml.jackson.databind.JsonNode parameters) {}

  private static OperationKey commonKey(Item item, Map<String, Input> bound) {
    if (ProcessingExtensions.SHAPES.contains(item.kind)
        || ProcessingRegistry.SAMPLED.contains(item.kind)
        || Set.of("EXPRESSION", "FALLOFF", "SPHERE", "BOX", "COORDINATE_HASH", "COORDINATE")
            .contains(item.kind)) return null;
    var inputs = new TreeMap<String, InputKey>();
    bound.forEach(
        (name, input) -> {
          var bits =
              input.literal == null
                  ? List.<Long>of()
                  : java.util.Arrays.stream(input.literal)
                      .mapToObj(Double::doubleToRawLongBits)
                      .toList();
          inputs.put(
              name, new InputKey(input.type, bits, input.literal == null ? input.number : null));
        });
    return new OperationKey(
        item.kind,
        item.resultType,
        Map.copyOf(inputs),
        ProjectIO.JSON.valueToTree(item.parameters));
  }

  public static final long MAX_SAMPLE_WORK = 1_000_000;

  public NoiseCompiler(ProjectData data, long seed) {
    this(data, seed, false);
  }

  private NoiseCompiler(ProjectData data, long seed, boolean tracing) {
    this.tracing = tracing;
    project = ProjectIO.copy(data);
    runtimeSeed = seed;
    for (var n : project.noise2D) resources.put(n.uuid, n);
    for (var n : project.noise3D) resources.put(n.uuid, n);
  }

  /** Explicit diagnostic replay. Ordinary compiled samplers contain no capture hooks. */
  public NoiseTrace explain(UUID uuid, double x, double y, double z) {
    return explain(uuid, x, y, z, NoiseTrace.DEFAULT_LIMIT);
  }

  public NoiseTrace explain(UUID uuid, double x, double y, double z, int limit) {
    if (limit < 0 || limit > NoiseTrace.MAX_LIMIT)
      throw new IllegalArgumentException("Trace event limit 0.." + NoiseTrace.MAX_LIMIT);
    var replay = new NoiseCompiler(project, runtimeSeed, true);
    var field = replay.compile(uuid);
    var observations = new NoiseTrace.Capture(limit);
    replay.capture.set(observations);
    try {
      double value = field.sample(x, y, z);
      return new NoiseTrace(uuid, x, y, z, value, observations.events, observations.omitted);
    } finally {
      replay.capture.remove();
    }
  }

  private void observe(
      UUID resource,
      UUID item,
      String id,
      String phase,
      String kind,
      String type,
      double x,
      double y,
      double z,
      double... values) {
    var current = capture.get();
    if (current != null) current.add(resource, item, id, phase, kind, type, x, y, z, values);
  }

  public Field compile(UUID uuid) {
    var old = compiled.get(uuid);
    if (old != null) return old;
    var n = resources.get(uuid);
    if (n == null) throw new IllegalArgumentException("Unknown noise UUID " + uuid);
    if (building.size() >= 64)
      throw new IllegalArgumentException("COMPLEXITY_LIMIT: noise reference depth exceeds 64");
    if (!building.add(uuid))
      throw new IllegalArgumentException("Noise dependency cycle " + building + " -> " + uuid);
    try {
      Field f = build(n);
      compiled.put(uuid, f);
      return f;
    } finally {
      building.remove(uuid);
    }
  }

  public Field compile(String id, int dimension) {
    return compile(
        resources.values().stream()
            .filter(n -> n.dimension == dimension && n.id.equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No " + dimension + "D noise " + id))
            .uuid);
  }

  public static List<Diagnostic> validate(ProjectData p) {
    var result = new ArrayList<>(Validator.validate(p));
    if (!result.isEmpty()) return result;
    var c = new NoiseCompiler(p, p.project.seed);
    for (var n : c.resources.values())
      try {
        c.compile(n.uuid);
      } catch (RuntimeException e) {
        if (e instanceof CompilationFailure failure) result.add(failure.diagnostic());
        else
          result.add(
              new Diagnostic(
                  "INVALID_NOISE",
                  Diagnostic.Severity.ERROR,
                  e.getMessage(),
                  "/noise/" + n.uuid,
                  n.uuid,
                  null,
                  null));
      }
    return List.copyOf(result);
  }

  @FunctionalInterface
  private interface Number {
    double get(Context c, int component);
  }

  private static final Number SOURCE_VALUE = (c, k) -> c.sourceValue();

  private record Input(String type, Number number, int depth, long work, double[] literal) {
    Input(String type, Number number) {
      this(type, number, 0, 0, null);
    }

    Input(String type, Number number, int depth, long work) {
      this(type, number, depth, work, null);
    }

    double scalar(Context c) {
      return number.get(c, 0);
    }

    int components() {
      return type.endsWith("2") || type.endsWith("2D")
          ? 2
          : type.endsWith("3") || type.endsWith("3D") ? 3 : 1;
    }
  }

  @FunctionalInterface
  private interface Step {
    void run(Context c);
  }

  private static final class Context {
    double x, y, z, worldX, worldY, worldZ, source;
    boolean sourceComputed;
    int epoch;
    final double[] values;
    final int[] stamps;
    final Field rawSource;
    final UUID resource;

    double sourceValue() {
      if (!sourceComputed) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
          throw new EvaluationFailure(
              resource, null, worldX, worldY, worldZ, "Nonfinite sampling coordinates");
        source = rawSource.sample(x, y, z);
        if (!Double.isFinite(source))
          throw new EvaluationFailure(resource, null, worldX, worldY, worldZ, "Nonfinite source");
        sourceComputed = true;
      }
      return source;
    }

    void begin(double x, double y, double z) {
      this.x = worldX = x;
      this.y = worldY = y;
      this.z = worldZ = z;
      sourceComputed = false;
      if (++epoch == 0) {
        Arrays.fill(stamps, 0);
        epoch = 1;
      }
    }

    Context(int size, Field source, UUID resource) {
      values = new double[size];
      stamps = new int[size / 3];
      rawSource = source;
      this.resource = resource;
    }
  }

  private Field build(Noise n) {
    long seed = CoordinateHash.seed(runtimeSeed, n.uuid, n.seedOffset);
    final int dimension = n.dimension;
    Field source;
    try {
      source = NoiseAlgorithms.compile(n.algorithm, n.dimension, seed);
    } catch (RuntimeException failure) {
      throw new CompilationFailure(
          n.uuid,
          null,
          failure instanceof NoiseAlgorithmRegistry.Problem problem
              ? problem.parameter().equals("key")
                  ? "algorithm"
                  : problem.parameter().equals("dimension")
                      ? "dimension"
                      : "algorithm/parameters/" + problem.parameter()
              : "algorithm",
          failure);
    }

    var inputs = new HashMap<UUID, Input>();
    var params = new HashMap<UUID, Input>();
    for (var p : n.sharedParameters) {
      try {
        params.put(p.uuid, constant(p.type, p.value));
      } catch (RuntimeException failure) {
        throw new CompilationFailure(n.uuid, p.uuid, "value", failure);
      }
    }
    if (tracing) {
      Field raw = source;
      source =
          (x, y, z) -> {
            double value = raw.sample(x, y, z);
            observe(n.uuid, null, n.id, "SOURCE", n.algorithm.key, "Scalar", x, y, z, value);
            return value;
          };
    }
    TransformPlan transforms = compileTransforms(n, source, params, seed);
    var prefixes = new HashMap<UUID, FieldPlan>();
    Input rawResult = new Input("Scalar", SOURCE_VALUE, 0, 16);
    FieldPlan sourceField = executable(n, source, transforms, rawResult, transforms.slots);
    int slot = transforms.slots;
    int enabledItems = 0, constantItems = 0, sharedItems = 0;
    long expressionInstructions = 0;
    var commonOperations = new HashMap<OperationKey, Integer>();
    for (var item : n.processing) {
      try {
        var spec = ProcessingRegistry.ITEMS.get(item.kind);
        if (spec == null)
          throw new IllegalArgumentException(
              "Unsupported processing kind " + item.kind + " in " + item.id);
        if (!item.enabled) {
          continue;
        }
        if (!ProcessingExtensions.supports(item.kind, dimension))
          throw new IllegalArgumentException(item.kind + ": requires 3D coordinates");
        enabledItems++;
        final int index = slot;
        slot += 3;
        var bound = new HashMap<String, Input>();
        for (var e : item.inputs.entrySet()) {
          if ((e.getKey().equals("field") && ProcessingRegistry.SAMPLED.contains(item.kind))
              || item.kind.equals("WARP_SAMPLE") && WarpRegistry.component(e.getKey())) {
            if (!ProcessingRegistry.isFieldBinding(e.getValue()))
              throw new IllegalArgumentException(
                  "A sampled-field input requires Source Field, Earlier Field Prefix or Noise"
                      + " Generator Field");
            continue;
          }
          bound.put(e.getKey(), binding(e.getValue(), inputs, params, n, seed));
        }
        for (String required : spec.inputs())
          if (!bound.containsKey(required))
            throw new IllegalArgumentException(item.id + ": missing input " + required);
        Input a = bound.get("a"), b = bound.get("b");
        if (!Set.of(
                    "EXPRESSION",
                    "SELECT",
                    "NORMALIZE",
                    "ADD",
                    "SUBTRACT",
                    "MULTIPLY",
                    "DIVIDE",
                    "CONSTANT",
                    "COORDINATES_TO_VECTOR",
                    "VECTOR_TO_COORDINATES",
                    "VECTOR_ROTATE",
                    "GRADIENT")
                .contains(item.kind)
            && !spec.result().equals(item.resultType))
          throw new IllegalArgumentException(item.id + ": result type must be " + spec.result());
        if (item.kind.equals("GRADIENT")
            && !item.resultType.equals(ProcessingExtensions.resultType(item.kind, dimension)))
          throw new IllegalArgumentException(
              "Gradient result must match the active field dimension");
        String kind = item.kind;
        Number eval;
        Input expressionConstant = null;
        long sampleWork = 0;
        int sampleDepth = 0;
        if (kind.equals("CONSTANT")) {
          if (!Set.of("Scalar", "Boolean", "Vector2", "Vector3").contains(item.resultType))
            throw new IllegalArgumentException(
                "Constant results are Scalar, Boolean or value Vector");
          var literal = item.parameters.get("value");
          if (literal == null)
            literal =
                ProjectIO.JSON.valueToTree(
                    item.resultType.equals("Boolean")
                        ? false
                        : item.resultType.equals("Scalar")
                            ? 0
                            : components(item.resultType) == 2
                                ? new double[] {0, 0}
                                : new double[] {0, 0, 0});
          Input value = constant(item.resultType, literal);
          eval = value.number;
        } else if (kind.equals("EXPRESSION")) {
          var types = new LinkedHashMap<String, Expression.Type>();
          for (var e : bound.entrySet()) {
            if (!Set.of("Scalar", "Boolean").contains(e.getValue().type))
              throw new IllegalArgumentException("Expression inputs must be Scalar or Boolean");
            types.put(e.getKey(), Expression.Type.valueOf(e.getValue().type));
          }
          for (String key : AMBIENT) types.putIfAbsent(key, Expression.Type.Scalar);
          types.putIfAbsent("source", Expression.Type.Scalar);
          var constants = new LinkedHashMap<String, Double>();
          for (var entry : bound.entrySet())
            if (entry.getValue().literal != null)
              constants.put(entry.getKey(), entry.getValue().literal[0]);
          for (String key :
              List.of("worldMinY", "worldMaxY", "worldHeight", "projectSeed", "noiseGeneratorSeed"))
            if (!bound.containsKey(key)) constants.put(key, ambient(key, null, seed));
          var program = Expression.compile(text(item, "expression", ""), types, constants);
          if (!program.type().name().equals(item.resultType))
            throw new IllegalArgumentException("Expression result type mismatch in " + item.id);
          var resolved = new LinkedHashMap<String, Input>();
          for (String name : program.inputs()) {
            Input input = bound.get(name);
            if (input == null)
              input =
                  name.equals("source")
                      ? rawResult
                      : new Input("Scalar", (c, k) -> ambient(name, c, seed));
            resolved.put(name, input);
          }
          // Only executed-expression dependencies contribute to work/depth; every authored binding
          // was still resolved and type checked before dead branches were removed.
          bound.clear();
          bound.putAll(resolved);
          Input[] arguments = resolved.values().toArray(Input[]::new);
          final class Access implements Expression.IndexedVariables {
            Context current;

            public double get(int index) {
              return arguments[index].scalar(current);
            }
          }
          ThreadLocal<Access> access = ThreadLocal.withInitial(Access::new);
          eval =
              (c, k) -> {
                var variables = access.get();
                var previous = variables.current;
                variables.current = c;
                try {
                  return program.type() == Expression.Type.Scalar
                      ? program.scalarIndexed(variables)
                      : program.boolIndexed(variables) ? 1 : 0;
                } finally {
                  variables.current = previous;
                }
              };
          sampleWork = program.statistics().instructions();
          expressionInstructions += program.statistics().instructions();
          if (program.constantValue().isPresent()) {
            double[] value = {program.constantValue().getAsDouble()};
            expressionConstant = new Input(item.resultType, (c, k) -> value[k], 0, 0, value);
          }
        } else if (ProcessingRegistry.SAMPLED.contains(kind)) {
          Binding selected = item.inputs.get("field");
          if (selected == null) {
            String legacy = text(item, "noiseGeneratorUuid", "");
            selected =
                legacy.isEmpty()
                    ? Binding.sourceField()
                    : Binding.noiseField(UUID.fromString(legacy));
          }
          FieldPlan sampled =
              selectedField(selected, n, source, transforms, sourceField, inputs, prefixes, slot);
          Field field = sampled.field;
          Input coordinates = bound.get("coordinates");
          if (coordinates != null
              && !coordinates.type.equals(dimension == 2 ? "Coordinates2D" : "Coordinates3D"))
            throw new IllegalArgumentException(
                "Explicit sample locations require matching Coordinates values");
          if (ProcessingExtensions.CALCULUS.contains(kind)) {
            if (bound.keySet().stream().anyMatch(key -> !key.equals("coordinates")))
              throw new IllegalArgumentException(
                  "Sampled calculus accepts field and optional coordinates inputs");
            var plan = FieldCalculus.compile(item, dimension, field);
            sampleWork = boundedWork(sampled.work, plan.samples());
            sampleDepth = sampled.depth;
            eval =
                (c, k) ->
                    plan.field()
                        .sample(
                            coordinates == null ? c.worldX : coordinates.number.get(c, 0),
                            coordinates == null
                                ? c.worldY
                                : dimension == 2 ? 0 : coordinates.number.get(c, 1),
                            coordinates == null
                                ? c.worldZ
                                : coordinates.number.get(c, dimension == 2 ? 1 : 2),
                            k);
          } else if (kind.equals("WARP_SAMPLE")) {
            var plan = WarpPlan.compile(item, dimension, seed);
            var components = new FieldPlan[3];
            for (int axis = 0; axis < 3; axis++) {
              String name = WarpRegistry.COMPONENTS.get(axis);
              var binding = item.inputs.get(name);
              if (binding == null) continue;
              if (dimension == 2 && axis == 1)
                throw new ProcessingExtensions.InvalidControl(
                    "inputs/" + name, "2D warp has no Y displacement");
              try {
                components[axis] =
                    selectedField(
                        binding, n, source, transforms, sourceField, inputs, prefixes, slot);
              } catch (CompilationFailure failure) {
                throw failure;
              } catch (RuntimeException failure) {
                throw new CompilationFailure(n.uuid, item.uuid, "inputs/" + name, failure);
              }
            }
            for (String name : bound.keySet())
              if (!Set.of("coordinates", "amplitude", "warpX", "warpY", "warpZ").contains(name))
                throw new ProcessingExtensions.InvalidControl(
                    "inputs/" + name, "Unsupported warp input " + name);
            var values = new Input[] {bound.get("warpX"), bound.get("warpY"), bound.get("warpZ")};
            for (int axis = 0; axis < 3; axis++) {
              if (values[axis] != null) require(values[axis], "Scalar", item);
              if (values[axis] != null && components[axis] != null)
                throw new ProcessingExtensions.InvalidControl(
                    "inputs/" + WarpRegistry.COMPONENTS.get(axis),
                    "Choose a numeric displacement or a sampled field, not both");
              if (dimension == 2 && axis == 1 && values[axis] != null)
                throw new ProcessingExtensions.InvalidControl(
                    "inputs/warpY", "2D warp has no Y displacement");
            }
            Input amount = control(bound, item, "amplitude", 8);
            require(amount, "Scalar", item);
            var displacement = warpDisplacement(values, components);
            long displacementWork = 0;
            for (int axis : dimension == 2 ? new int[] {0, 2} : new int[] {0, 1, 2}) {
              displacementWork =
                  Math.addExact(
                      displacementWork,
                      components[axis] != null
                          ? components[axis].work
                          : values[axis] != null ? 0 : 16);
              if (components[axis] != null)
                sampleDepth = Math.max(sampleDepth, components[axis].depth);
            }
            sampleWork = Math.addExact(sampled.work, boundedWork(displacementWork, plan.octaves()));
            boundedWork(sampleWork, 1);
            sampleDepth = Math.max(sampleDepth, sampled.depth);
            var scratch = ThreadLocal.withInitial(() -> new double[3]);
            eval =
                (c, k) -> {
                  double x = coordinates == null ? c.worldX : coordinates.number.get(c, 0),
                      y =
                          coordinates == null
                              ? c.worldY
                              : dimension == 2 ? 0 : coordinates.number.get(c, 1),
                      z =
                          coordinates == null
                              ? c.worldZ
                              : coordinates.number.get(c, dimension == 2 ? 1 : 2);
                  double[] location = scratch.get();
                  plan.apply(x, y, z, amount.scalar(c), c, displacement, location);
                  return field.sample(location[0], location[1], location[2]);
                };
          } else {
            int oct = integer(item, "octaves", 4, 1, 16), radius = integer(item, "radius", 1, 0, 4);
            double lac = param(item, "lacunarity", 2),
                gain = param(item, "gain", .5),
                spacing = param(item, "spacing", 1),
                dx = param(item, "x", 0),
                dy = param(item, "y", 0),
                dz = param(item, "z", 0),
                strength = param(item, "strength", 1);
            String axis = text(item, "axis", "x"), kernel = text(item, "kernel", "BOX");
            if (spacing <= 0
                || lac <= 0
                || !Set.of("x", "y", "z").contains(axis)
                || dimension == 2 && axis.equals("y")
                || !Set.of("BOX", "GAUSSIAN").contains(kernel)
                || strength < 0
                || strength > 1)
              throw new IllegalArgumentException("Invalid field sampling controls");
            int width = 2 * radius + 1,
                count = dimension == 2 ? width * width : width * width * width;
            long samples =
                kind.equals("DERIVATIVE")
                    ? 2
                    : kind.equals("SMOOTH_FILTER")
                        ? count + 1
                        : Set.of("FBM", "RIDGED", "BILLOW", "PING_PONG").contains(kind) ? oct : 1;
            sampleWork = boundedWork(sampled.work, samples);
            sampleDepth = sampled.depth;
            double baseFrequency = param(item, "frequency", 1);
            if (baseFrequency <= 0)
              throw new IllegalArgumentException("Sampling frequency must be positive");
            double[] weights = new double[count];
            double total = 0;
            int wi = 0;
            for (int y = dimension == 2 ? 0 : -radius; y <= (dimension == 2 ? 0 : radius); y++)
              for (int z = -radius; z <= radius; z++)
                for (int x = -radius; x <= radius; x++) {
                  double value =
                      kernel.equals("GAUSSIAN")
                          ? StrictMath.exp(
                              -(x * x + y * y + z * z) / StrictMath.max(.5, radius * radius / 2.))
                          : 1;
                  weights[wi++] = value;
                  total += value;
                }
            for (int i = 0; i < weights.length; i++) weights[i] /= total;
            eval =
                (c, k) -> {
                  double x = coordinates == null ? c.worldX : coordinates.number.get(c, 0),
                      y =
                          coordinates == null
                              ? c.worldY
                              : dimension == 2 ? 0 : coordinates.number.get(c, 1),
                      z =
                          coordinates == null
                              ? c.worldZ
                              : coordinates.number.get(c, dimension == 2 ? 1 : 2);
                  if (kind.equals("NOISE_REFERENCE")) return field.sample(x, y, z);
                  if (kind.equals("OFFSET_SAMPLE")) return field.sample(x + dx, y + dy, z + dz);
                  if (kind.equals("DERIVATIVE")) {
                    double ax = axis.equals("x") ? spacing : 0,
                        ay = axis.equals("y") ? spacing : 0,
                        az = axis.equals("z") ? spacing : 0;
                    return (field.sample(x + ax, y + ay, z + az)
                            - field.sample(x - ax, y - ay, z - az))
                        / (2 * spacing);
                  }
                  if (kind.equals("SMOOTH_FILTER")) {
                    double sum = 0;
                    int indexWeight = 0;
                    for (int yy = dimension == 2 ? 0 : -radius;
                        yy <= (dimension == 2 ? 0 : radius);
                        yy++)
                      for (int zz = -radius; zz <= radius; zz++)
                        for (int xx = -radius; xx <= radius; xx++)
                          sum +=
                              weights[indexWeight++]
                                  * field.sample(
                                      x + xx * spacing, y + yy * spacing, z + zz * spacing);
                    double original = field.sample(x, y, z);
                    return original + (sum - original) * strength;
                  }
                  double sum = 0, amp = 1, freq = baseFrequency;
                  for (int i = 0; i < oct; i++) {
                    double value = field.sample(x * freq, y * freq, z * freq);
                    value =
                        switch (kind) {
                          case "RIDGED" -> 1 - StrictMath.abs(value);
                          case "BILLOW" -> 2 * StrictMath.abs(value) - 1;
                          case "PING_PONG" -> {
                            double t = value + 1;
                            t -= 2 * StrictMath.floor(t / 2);
                            yield 1 - StrictMath.abs(t - 1);
                          }
                          default -> value;
                        };
                    sum += amp * value;
                    amp *= gain;
                    freq *= lac;
                  }
                  return sum;
                };
          }
        } else if (ProcessingExtensions.SHAPES.contains(kind)) {
          var shape = DistanceShapes.compile(item, dimension);
          var position = bound.get("position");
          if (position != null)
            require(position, dimension == 2 ? "Coordinates2D" : "Coordinates3D", item);
          if (bound.keySet().stream().anyMatch(key -> !key.equals("position")))
            throw new IllegalArgumentException(
                "Distance shapes accept an optional Coordinates position input");
          eval =
              (c, k) ->
                  shape.sample(
                      position == null ? c.x : position.number.get(c, 0),
                      dimension == 2 ? 0 : position == null ? c.y : position.number.get(c, 1),
                      position == null ? c.z : position.number.get(c, dimension == 2 ? 1 : 2));
        } else if (ProcessingExtensions.COMBINATIONS.contains(kind)) {
          validateSignature(item, bound);
          double smoothness =
              ProcessingExtensions.controls(item, dimension).get("smoothness").doubleValue();
          eval =
              (c, k) ->
                  DistanceShapes.smoothMaximum(
                      a.scalar(c),
                      kind.equals("SMOOTH_SUBTRACTION") ? -b.scalar(c) : b.scalar(c),
                      smoothness);
        } else if (ProcessingExtensions.REMAPPING.contains(kind)) {
          var plan = ScalarRemapping.compile(item, dimension);
          require(a, "Scalar", item);
          var weight = bound.get("weight");
          var position = bound.get("position");
          if (kind.equals("BLEND")) {
            require(b, "Scalar", item);
            if (weight != null) require(weight, "Scalar", item);
          } else if (kind.equals("FALLOFF") && position != null)
            require(position, dimension == 2 ? "Coordinates2D" : "Coordinates3D", item);
          for (String key : bound.keySet())
            if (!key.equals("a")
                && !(kind.equals("BLEND") && Set.of("b", "weight").contains(key))
                && !(kind.equals("FALLOFF") && key.equals("position")))
              throw new IllegalArgumentException(kind + ": unsupported input " + key);
          eval =
              kind.equals("BLEND")
                  ? (c, k) -> {
                    double w =
                        plan.effectiveWeight(weight == null ? plan.weight() : weight.scalar(c));
                    if (w == 0) return plan.output(a.scalar(c));
                    if (w == 1 && plan.mode().equals("LERP")) return plan.output(b.scalar(c));
                    return plan.combine(a.scalar(c), b.scalar(c), w);
                  }
                  : (c, k) -> {
                    double av = a.scalar(c);
                    if (!plan.usesPosition()) return plan.sample(av, 0, 0, 0);
                    return plan.sample(
                        av,
                        position == null ? c.x : position.number.get(c, 0),
                        dimension == 2 ? 0 : position == null ? c.y : position.number.get(c, 1),
                        position == null ? c.z : position.number.get(c, dimension == 2 ? 1 : 2));
                  };
        } else if (kind.equals("CURVE")) {
          require(a, "Scalar", item);
          var curve = ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class);
          var cc = new ScalarCurve(curve);
          eval = (c, k) -> cc.sample(a.scalar(c));
        } else {
          validateSignature(item, bound);
          Parameters parameters = Parameters.compile(item);
          eval = (c, k) -> evaluate(kind, parameters, bound, c, k, seed, dimension);
        }
        int components = components(item.resultType);
        Input folded = expressionConstant;
        if (expressionConstant != null) {
          // A valid complete expression constant has no runtime dependencies.
        } else if (kind.equals("SELECT") && bound.get("condition").literal != null)
          folded = bound.get(bound.get("condition").literal[0] != 0 ? "true" : "false");
        else if ((kind.equals("AND") || kind.equals("OR"))
            && a.literal != null
            && ((kind.equals("AND") && a.literal[0] == 0)
                || (kind.equals("OR") && a.literal[0] != 0)))
          folded = constant("Boolean", ProjectIO.JSON.valueToTree(kind.equals("OR")));
        else if (!ProcessingExtensions.SHAPES.contains(kind)
            && !Set.of("EXPRESSION", "FALLOFF", "SPHERE", "BOX", "COORDINATE_HASH", "COORDINATE")
                .contains(kind)
            && !ProcessingRegistry.SAMPLED.contains(kind)
            && bound.values().stream().allMatch(input -> input.literal != null)) {
          try {
            double[] values = new double[components];
            Context scratch = new Context(0, source, n.uuid);
            for (int k = 0; k < components; k++) {
              values[k] = eval.get(scratch, k);
              if (!Double.isFinite(values[k])) throw new ArithmeticException();
            }
            folded = new Input(item.resultType, (c, k) -> values[k], 0, 0, values);
          } catch (RuntimeException invalidConstant) {
            /* Keep the executed runtime boundary; an unselected invalid constant branch is legal. */
          }
        }
        if (folded != null) {
          if (folded.literal != null) constantItems++;
          if (tracing && folded.literal != null) {
            Input constant = folded;
            folded =
                new Input(
                    item.resultType,
                    (c, k) -> {
                      if (k == 0)
                        observe(
                            n.uuid,
                            item.uuid,
                            item.id,
                            "FOLDED",
                            kind,
                            item.resultType,
                            c.x,
                            c.y,
                            c.z,
                            constant.literal);
                      return constant.number.get(c, k);
                    },
                    constant.depth,
                    constant.work,
                    constant.literal);
          }
          inputs.put(item.uuid, folded);
          continue;
        }
        var key = commonKey(item, bound);
        Integer previousSlot = key == null ? null : commonOperations.putIfAbsent(key, index);
        final int sharedIndex = previousSlot == null ? index : previousSlot;
        if (previousSlot != null) sharedItems++;
        Number number = eval;
        Step instruction =
            c -> {
              try {
                for (int k = 0; k < components; k++) {
                  double value = number.get(c, k);
                  if (!Double.isFinite(value)) throw new ArithmeticException("Nonfinite result");
                  c.values[index + k] = value;
                }
              } catch (RuntimeException e) {
                if (e instanceof EvaluationFailure) throw e;
                throw new EvaluationFailure(n.uuid, item.uuid, c.x, c.y, c.z, e.getMessage(), e);
              }
            };
        Step normalInstruction = instruction;
        Step observedInstruction =
            tracing
                ? c -> {
                  normalInstruction.run(c);
                  double[] values = Arrays.copyOfRange(c.values, index, index + components);
                  observe(
                      n.uuid,
                      item.uuid,
                      item.id,
                      "PROCESSING",
                      kind,
                      item.resultType,
                      c.x,
                      c.y,
                      c.z,
                      values);
                }
                : normalInstruction;
        int depth =
            1
                + Math.max(
                    sampleDepth, bound.values().stream().mapToInt(Input::depth).max().orElse(0));
        if (depth > 128)
          throw new IllegalArgumentException(
              "COMPLEXITY_LIMIT: processing dependency depth exceeds 128");
        inputs.put(
            item.uuid,
            new Input(
                item.resultType,
                (c, k) -> {
                  int cell = sharedIndex / 3;
                  if (c.stamps[cell] != c.epoch) {
                    observedInstruction.run(c);
                    if (sharedIndex != index)
                      System.arraycopy(c.values, index, c.values, sharedIndex, components);
                    c.stamps[cell] = c.epoch;
                  }
                  return c.values[sharedIndex + k];
                },
                depth,
                Math.addExact(
                    1 + sampleWork, bound.values().stream().mapToLong(Input::work).sum())));
      } catch (CompilationFailure failure) {
        throw failure;
      } catch (RuntimeException failure) {
        throw new CompilationFailure(
            n.uuid,
            item.uuid,
            failure instanceof ProcessingExtensions.InvalidControl invalid
                ? invalid.key
                : "EXPRESSION".equals(item.kind) ? "expression" : null,
            failure);
      }
    }
    var output = binding(n.outputBinding, inputs, params, n, seed);
    if (!output.type.equals("Scalar")) throw new IllegalArgumentException("Output must be Scalar");
    FieldPlan result = executable(n, source, transforms, output, slot);
    resourceWork.put(n.uuid, result.work);
    resourceDepth.put(n.uuid, result.depth);
    statistics.put(
        n.uuid,
        new Statistics(
            enabledItems,
            constantItems,
            sharedItems,
            expressionInstructions,
            slot,
            result.work,
            result.depth));
    if (!tracing) return result.field;
    return (x, y, z) -> {
      double value = result.field.sample(x, y, z);
      observe(n.uuid, n.outputBinding.itemUuid, n.id, "OUTPUT", "OUTPUT", "Scalar", x, y, z, value);
      return value;
    };
  }

  private static long boundedWork(long work, long samples) {
    try {
      long value = Math.multiplyExact(work, samples);
      if (value > MAX_SAMPLE_WORK)
        throw new IllegalArgumentException(
            "COMPLEXITY_LIMIT: sampled-field work exceeds " + MAX_SAMPLE_WORK);
      return value;
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("COMPLEXITY_LIMIT: sampled-field work overflow");
    }
  }

  private static int integer(Item item, String name, int fallback, int min, int max) {
    double value = param(item, name, fallback);
    if (value != StrictMath.rint(value) || value < min || value > max)
      throw new IllegalArgumentException(
          item.id + ": " + name + " must be an integer in [" + min + "," + max + "]");
    return (int) value;
  }

  private record TransformPlan(List<Step> steps, int slots, long work, int depth) {}

  private TransformPlan compileTransforms(
      Noise n, Field source, Map<UUID, Input> parameters, long seed) {
    var steps = new ArrayList<Step>();
    var earlier = new HashMap<UUID, Input>();
    int slots = 0, depth = 0;
    long work = 0;
    for (Item item : n.coordinateTransforms) {
      try {
        if (!Set.of("TRANSLATE", "SCALE", "ROTATE", "DOMAIN_WARP").contains(item.kind))
          throw new IllegalArgumentException("Unsupported transform " + item.kind);
        String coordinatesType = n.dimension == 2 ? "Coordinates2D" : "Coordinates3D";
        if (!item.resultType.equals(coordinatesType))
          throw new IllegalArgumentException("Transform result must be " + coordinatesType);
        if (!item.enabled) continue;
        var bound = new HashMap<String, Input>();
        var allowed =
            Set.of(
                "coordinates",
                "x",
                "y",
                "z",
                "angle",
                "angleX",
                "angleY",
                "angleZ",
                "pivotX",
                "pivotY",
                "pivotZ",
                "amplitude",
                "warpX",
                "warpY",
                "warpZ");
        var displacementFields = new FieldPlan[3];
        for (var e : item.inputs.entrySet()) {
          if (kindOfWarp(item) && WarpRegistry.component(e.getKey())) {
            int axis = WarpRegistry.COMPONENTS.indexOf(e.getKey());
            if (n.dimension == 2 && axis == 1)
              throw new ProcessingExtensions.InvalidControl(
                  "inputs/" + e.getKey(), "2D warp has no Y displacement");
            var binding = e.getValue();
            if (binding == null
                || !binding.sourceKind.equals("Noise Generator Field")
                || !binding.type.equals("Scalar"))
              throw new ProcessingExtensions.InvalidControl(
                  "inputs/" + e.getKey(),
                  "Coordinate warp displacement requires a Noise Generator Field");
            Field ref;
            try {
              ref = projectedReference(binding, n.dimension);
            } catch (CompilationFailure failure) {
              throw failure;
            } catch (RuntimeException failure) {
              throw new CompilationFailure(n.uuid, item.uuid, "inputs/" + e.getKey(), failure);
            }
            displacementFields[axis] =
                new FieldPlan(
                    ref,
                    resourceWork.get(binding.noiseGeneratorUuid),
                    resourceDepth.get(binding.noiseGeneratorUuid));
            continue;
          }
          if (!allowed.contains(e.getKey()))
            throw new IllegalArgumentException("Unknown transform input " + e.getKey());
          if (Set.of(
                  "Source Result", "Source Field", "Earlier Field Prefix", "Noise Generator Field")
              .contains(e.getValue().sourceKind))
            throw new IllegalArgumentException(
                "Coordinate transforms cannot use source/processing results or field selectors");
          Input input = binding(e.getValue(), earlier, parameters, n, seed);
          String expected = e.getKey().equals("coordinates") ? coordinatesType : "Scalar";
          require(input, expected, item);
          bound.put(e.getKey(), input);
        }
        int index = slots;
        slots += 3;
        String kind = item.kind;
        final int dimension = n.dimension;
        UUID resource = n.uuid, id = item.uuid;
        Input coordinates = bound.get("coordinates");
        Input x = control(bound, item, "x", kind.equals("SCALE") ? 1 : 0),
            y = control(bound, item, "y", kind.equals("SCALE") ? 1 : 0),
            z = control(bound, item, "z", kind.equals("SCALE") ? 1 : 0);
        Input px = control(bound, item, "pivotX", 0),
            py = control(bound, item, "pivotY", 0),
            pz = control(bound, item, "pivotZ", 0),
            angle = control(bound, item, "angle", 0),
            ax = control(bound, item, "angleX", 0),
            ay =
                bound.containsKey("angleY") || item.parameters.containsKey("angleY")
                    ? control(bound, item, "angleY", 0)
                    : angle,
            az = control(bound, item, "angleZ", 0),
            amplitude = control(bound, item, "amplitude", 8);
        String order = text(item, "rotationOrder", "XYZ");
        if (!Set.of("XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX").contains(order))
          throw new IllegalArgumentException("Explicit rotation axis order required");
        WarpPlan warp = kind.equals("DOMAIN_WARP") ? WarpPlan.compile(item, dimension, seed) : null;
        var warpValues = new Input[] {bound.get("warpX"), bound.get("warpY"), bound.get("warpZ")};
        if (warp != null)
          for (int axis = 0; axis < 3; axis++)
            if (warpValues[axis] != null && displacementFields[axis] != null)
              throw new ProcessingExtensions.InvalidControl(
                  "inputs/" + WarpRegistry.COMPONENTS.get(axis),
                  "Choose numeric or sampled displacement, not both");
        var displacement = warpDisplacement(warpValues, displacementFields);
        var warpScratch = ThreadLocal.withInitial(() -> new double[3]);
        Step step =
            c -> {
              try {
                double xx = coordinates == null ? c.x : coordinates.number.get(c, 0),
                    yy =
                        coordinates == null
                            ? c.y
                            : dimension == 2 ? 0 : coordinates.number.get(c, 1),
                    zz =
                        coordinates == null
                            ? c.z
                            : coordinates.number.get(c, dimension == 2 ? 1 : 2);
                if (kind.equals("TRANSLATE")) {
                  xx += x.scalar(c);
                  yy += dimension == 2 ? 0 : y.scalar(c);
                  zz += z.scalar(c);
                } else if (kind.equals("SCALE")) {
                  double pivotX = px.scalar(c), pivotY = py.scalar(c), pivotZ = pz.scalar(c);
                  xx = pivotX + (xx - pivotX) * x.scalar(c);
                  yy = dimension == 2 ? 0 : pivotY + (yy - pivotY) * y.scalar(c);
                  zz = pivotZ + (zz - pivotZ) * z.scalar(c);
                } else if (kind.equals("ROTATE")) {
                  double pivotX = px.scalar(c), pivotY = py.scalar(c), pivotZ = pz.scalar(c);
                  xx -= pivotX;
                  yy -= pivotY;
                  zz -= pivotZ;
                  double rx = ax.scalar(c) * StrictMath.PI / 180,
                      ry = (dimension == 2 ? angle : ay).scalar(c) * StrictMath.PI / 180,
                      rz = az.scalar(c) * StrictMath.PI / 180;
                  for (int i = 0; i < (dimension == 2 ? 1 : 3); i++) {
                    char axis = dimension == 2 ? 'Y' : order.charAt(i);
                    double radians = axis == 'X' ? rx : axis == 'Y' ? ry : rz,
                        co = StrictMath.cos(radians),
                        si = StrictMath.sin(radians),
                        old;
                    if (axis == 'X') {
                      old = yy;
                      yy = old * co - zz * si;
                      zz = old * si + zz * co;
                    } else if (axis == 'Y') {
                      old = xx;
                      xx = old * co - zz * si;
                      zz = old * si + zz * co;
                    } else {
                      old = xx;
                      xx = old * co - yy * si;
                      yy = old * si + yy * co;
                    }
                  }
                  xx += pivotX;
                  yy = dimension == 2 ? 0 : yy + pivotY;
                  zz += pivotZ;
                } else {
                  double[] location = warpScratch.get();
                  warp.apply(xx, yy, zz, amplitude.scalar(c), c, displacement, location);
                  xx = location[0];
                  yy = location[1];
                  zz = location[2];
                }
                if (!Double.isFinite(xx) || !Double.isFinite(yy) || !Double.isFinite(zz))
                  throw new ArithmeticException("Nonfinite transformed coordinates");
                c.x = xx;
                c.y = dimension == 2 ? 0 : yy;
                c.z = zz;
                c.values[index] = xx;
                c.values[index + 1] = dimension == 2 ? zz : yy;
                c.values[index + 2] = dimension == 2 ? 0 : zz;
              } catch (RuntimeException failure) {
                if (failure instanceof EvaluationFailure) throw failure;
                throw new EvaluationFailure(
                    resource, id, c.worldX, c.worldY, c.worldZ, failure.getMessage(), failure);
              }
            };
        Step normalStep = step;
        steps.add(
            tracing
                ? c -> {
                  normalStep.run(c);
                  observe(
                      resource,
                      id,
                      item.id,
                      "TRANSFORM",
                      kind,
                      coordinatesType,
                      c.worldX,
                      c.worldY,
                      c.worldZ,
                      Arrays.copyOfRange(c.values, index, index + dimension));
                }
                : normalStep);
        long stepWork = 1;
        if (warp != null) {
          long oneOctave = 0;
          for (int axis : dimension == 2 ? new int[] {0, 2} : new int[] {0, 1, 2}) {
            oneOctave =
                Math.addExact(
                    oneOctave,
                    displacementFields[axis] != null
                        ? displacementFields[axis].work
                        : warpValues[axis] != null ? 0 : 16);
            if (displacementFields[axis] != null)
              depth = Math.max(depth, displacementFields[axis].depth);
          }
          stepWork = boundedWork(oneOctave, warp.octaves());
        }
        for (Input in : bound.values()) {
          stepWork = Math.addExact(stepWork, in.work);
          depth = Math.max(depth, in.depth);
        }
        work = Math.addExact(work, stepWork);
        boundedWork(work, 1);
        if (depth >= 128)
          throw new IllegalArgumentException(
              "COMPLEXITY_LIMIT: transform dependency depth exceeds 128");
        earlier.put(id, new Input(coordinatesType, (c, k) -> c.values[index + k], depth + 1, 0));
      } catch (CompilationFailure failure) {
        throw failure;
      } catch (RuntimeException failure) {
        throw new CompilationFailure(
            n.uuid,
            item.uuid,
            failure instanceof ProcessingExtensions.InvalidControl invalid ? invalid.key : null,
            failure);
      }
    }
    return new TransformPlan(List.copyOf(steps), slots, work, depth);
  }

  private static boolean kindOfWarp(Item item) {
    return item.kind.equals("DOMAIN_WARP");
  }

  private FieldPlan selectedField(
      Binding selected,
      Noise n,
      Field source,
      TransformPlan transforms,
      FieldPlan sourceField,
      Map<UUID, Input> earlier,
      Map<UUID, FieldPlan> prefixes,
      int slots) {
    if (selected == null || !selected.type.equals("Scalar"))
      throw new IllegalArgumentException("Sampled field requires Scalar output");
    return switch (selected.sourceKind) {
      case "Source Field" -> sourceField;
      case "Earlier Field Prefix" -> {
        Input target = earlier.get(selected.itemUuid);
        if (target == null || !target.type.equals("Scalar"))
          throw new IllegalArgumentException(
              "Earlier field prefix must select an enabled earlier Scalar result");
        FieldPlan cached = prefixes.get(selected.itemUuid);
        if (cached == null) {
          cached = executable(n, source, transforms, target, slots);
          prefixes.put(selected.itemUuid, cached);
        }
        yield cached;
      }
      case "Noise Generator Field" -> {
        Field ref = projectedReference(selected, n.dimension);
        yield new FieldPlan(
            ref,
            resourceWork.get(selected.noiseGeneratorUuid),
            resourceDepth.get(selected.noiseGeneratorUuid));
      }
      default -> throw new IllegalArgumentException("Unsupported sampled-field selector");
    };
  }

  private static WarpPlan.Displacement<Context> warpDisplacement(
      Input[] values, FieldPlan[] fields) {
    return new WarpPlan.Displacement<>() {
      public boolean present(int axis) {
        return values[axis] != null || fields[axis] != null;
      }

      public boolean resampled(int axis) {
        return fields[axis] != null;
      }

      public double sample(Context context, int axis, double x, double y, double z) {
        return fields[axis] != null
            ? fields[axis].field.sample(x, y, z)
            : values[axis].scalar(context);
      }
    };
  }

  private static Input control(Map<String, Input> inputs, Item item, String key, double fallback) {
    Input value = inputs.get(key);
    return value != null
        ? value
        : constant("Scalar", ProjectIO.JSON.valueToTree(param(item, key, fallback)));
  }

  private static FieldPlan executable(
      Noise n, Field source, TransformPlan transforms, Input output, int slots) {
    long work = Math.addExact(output.work, transforms.work);
    boundedWork(work, 1);
    UUID resource = n.uuid;
    ThreadLocal<Context> scratch =
        ThreadLocal.withInitial(() -> new Context(slots, source, resource));
    Field field =
        (x, y, z) -> {
          if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new EvaluationFailure(
                resource, null, x, y, z, "Finite sample coordinates required");
          Context c = scratch.get();
          c.begin(x, y, z);
          for (var step : transforms.steps) step.run(c);
          double value = output.scalar(c);
          if (!Double.isFinite(value))
            throw new EvaluationFailure(resource, null, x, y, z, "Nonfinite output");
          return value;
        };
    return new FieldPlan(field, work, Math.max(output.depth, transforms.depth));
  }

  private Field projectedReference(Binding binding, int fromDimension) {
    Noise target = resources.get(binding.noiseGeneratorUuid);
    if (target == null) throw new IllegalArgumentException("Unknown noise reference");
    Field field = compile(binding.noiseGeneratorUuid);
    String projection = binding.projection;
    double slice = binding.slice;
    if (!Set.of("XZ", "XY", "YZ").contains(projection) || !Double.isFinite(slice))
      throw new IllegalArgumentException("Explicit finite projection/slice required");
    if (target.dimension == fromDimension) return field;
    if (target.dimension == 2)
      return (x, y, z) ->
          switch (projection) {
            case "XY" -> field.sample(x, 0, y);
            case "YZ" -> field.sample(y, 0, z);
            default -> field.sample(x, 0, z);
          };
    return (x, y, z) ->
        switch (projection) {
          case "XY" -> field.sample(x, z, slice);
          case "YZ" -> field.sample(slice, x, z);
          default -> field.sample(x, slice, z);
        };
  }

  private static int components(String t) {
    return t.endsWith("2") || t.endsWith("2D") ? 2 : t.endsWith("3") || t.endsWith("3D") ? 3 : 1;
  }

  private static Input constant(String type, com.fasterxml.jackson.databind.JsonNode n) {
    if (n == null || n.isNull()) throw new IllegalArgumentException("Constant required");
    if (!Validator.TYPES.contains(type))
      throw new IllegalArgumentException("Unsupported constant type " + type);
    double[] values = new double[components(type)];
    if (type.equals("Boolean")) {
      if (!n.isBoolean()) throw new IllegalArgumentException("Boolean literal required");
      values[0] = n.booleanValue() ? 1 : 0;
    } else if (values.length == 1) {
      if (!n.isNumber() || !Double.isFinite(n.doubleValue()))
        throw new IllegalArgumentException("Finite Scalar literal required");
      values[0] = n.doubleValue();
    } else {
      if (!n.isArray() || n.size() != values.length)
        throw new IllegalArgumentException(type + " requires " + values.length + " components");
      for (int k = 0; k < values.length; k++) {
        if (!n.get(k).isNumber() || !Double.isFinite(n.get(k).doubleValue()))
          throw new IllegalArgumentException("Finite components required");
        values[k] = n.get(k).doubleValue();
      }
    }
    return new Input(type, (c, k) -> values[k], 0, 0, values);
  }

  private Input binding(
      Binding b, Map<UUID, Input> earlier, Map<UUID, Input> params, Noise n, long seed) {
    if (b == null) throw new IllegalArgumentException("Missing binding");
    Input in =
        switch (b.sourceKind) {
          case "Constant" -> constant(b.type, b.value);
          case "Source Result" -> new Input("Scalar", SOURCE_VALUE, 0, 16);
          case "Earlier Result" -> earlier.get(b.itemUuid);
          case "Shared Parameter" -> params.get(b.parameterUuid);
          case "Ambient Value" -> {
            if (!AMBIENT.contains(b.ambientKey))
              throw new IllegalArgumentException("Unknown ambient " + b.ambientKey);
            yield new Input("Scalar", (c, k) -> ambient(b.ambientKey, c, seed));
          }
          case "Noise Generator Reference" -> {
            Field field = projectedReference(b, n.dimension);
            yield new Input(
                "Scalar",
                (c, k) -> field.sample(c.x, c.y, c.z),
                resourceDepth.get(b.noiseGeneratorUuid),
                resourceWork.get(b.noiseGeneratorUuid));
          }
          default -> throw new IllegalArgumentException("Unknown binding " + b.sourceKind);
        };
    if (in == null) throw new IllegalArgumentException("Unresolved binding " + b.sourceKind);
    if (!in.type.equals(b.type))
      throw new IllegalArgumentException("Binding declares " + b.type + " but input is " + in.type);
    return in;
  }

  public static final Set<String> AMBIENT =
      Set.of(
          "x",
          "y",
          "z",
          "worldMinY",
          "worldMaxY",
          "worldHeight",
          "normalizedY",
          "projectSeed",
          "noiseGeneratorSeed");

  private double ambient(String key, Context c, long seed) {
    return switch (key) {
      case "x" -> c.x;
      case "y" -> c.y;
      case "z" -> c.z;
      case "worldMinY" -> project.project.minY;
      case "worldMaxY" -> project.project.maxY;
      case "worldHeight" -> (double) project.project.maxY - project.project.minY + 1;
      case "normalizedY" ->
          project.project.maxY == project.project.minY
              ? 0
              : (c.y - project.project.minY)
                  / ((double) project.project.maxY - project.project.minY);
      case "projectSeed" -> runtimeSeed;
      case "noiseGeneratorSeed" -> seed;
      default -> throw new IllegalArgumentException("Unknown ambient " + key);
    };
  }

  private static void require(Input a, String type, Item i) {
    if (a == null || !a.type.equals(type))
      throw new IllegalArgumentException(i.id + ": expected " + type + " input");
  }

  private static void validateSignature(Item i, Map<String, Input> in) {
    String k = i.kind;
    var a = in.get("a");
    var b = in.get("b");
    if (k.equals("EQUAL") || k.equals("NOT_EQUAL")) {
      if (a == null || b == null || !a.type.equals(b.type))
        throw new IllegalArgumentException("Equality requires matching computational types");
      return;
    }
    if (Set.of(
            "COORDINATES_TO_VECTOR",
            "VECTOR_TO_COORDINATES",
            "PROJECT_VECTOR",
            "EXPAND_VECTOR",
            "PROJECT_COORDINATES",
            "EXPAND_COORDINATES",
            "VECTOR_ROTATE")
        .contains(k)) {
      String expected =
          switch (k) {
            case "COORDINATES_TO_VECTOR" ->
                i.resultType.equals("Vector2") ? "Coordinates2D" : "Coordinates3D";
            case "VECTOR_TO_COORDINATES" ->
                i.resultType.equals("Coordinates2D") ? "Vector2" : "Vector3";
            case "PROJECT_VECTOR" -> "Vector3";
            case "EXPAND_VECTOR" -> "Vector2";
            case "PROJECT_COORDINATES" -> "Coordinates3D";
            case "EXPAND_COORDINATES" -> "Coordinates2D";
            default -> i.resultType;
          };
      require(a, expected, i);
      if (k.equals("COORDINATES_TO_VECTOR") && !Set.of("Vector2", "Vector3").contains(i.resultType)
          || k.equals("VECTOR_TO_COORDINATES")
              && !Set.of("Coordinates2D", "Coordinates3D").contains(i.resultType)
          || k.equals("VECTOR_ROTATE") && !Set.of("Vector2", "Vector3").contains(i.resultType))
        throw new IllegalArgumentException("Conversion result type mismatch");
      if (k.startsWith("EXPAND")) require(b, "Scalar", i);
      return;
    }
    if (k.equals("ADD") || k.equals("SUBTRACT") || k.equals("MULTIPLY") || k.equals("DIVIDE")) {
      if (a == null || b == null || !Set.of("Scalar", "Vector2", "Vector3").contains(a.type))
        throw new IllegalArgumentException(i.id + ": arithmetic requires Scalar or value Vector");
      if (k.equals("ADD") || k.equals("SUBTRACT")) {
        if (!a.type.equals(b.type))
          throw new IllegalArgumentException("Addition/subtraction require matching types");
      } else require(b, "Scalar", i);
      if (!i.resultType.equals(a.type))
        throw new IllegalArgumentException(
            "Arithmetic result type must preserve the first input type");
      return;
    }
    if (Set.of("AND", "OR", "NOT", "BOOLEAN_TO_SCALAR").contains(k)) {
      require(a, "Boolean", i);
      if (b != null) require(b, "Boolean", i);
    } else if (k.equals("SELECT")) {
      require(in.get("condition"), "Boolean", i);
      require(in.get("true"), i.resultType, i);
      require(in.get("false"), i.resultType, i);
    } else if (Set.of("DOT", "CROSS").contains(k)) {
      if (a == null
          || b == null
          || !a.type.equals(b.type)
          || !Set.of("Vector2", "Vector3").contains(a.type)
          || k.equals("CROSS") && !a.type.equals("Vector3"))
        throw new IllegalArgumentException(i.id + ": matching vectors required");
    } else if (Set.of("LENGTH", "NORMALIZE", "COMPONENT").contains(k)) {
      if (a == null || !Set.of("Vector2", "Vector3").contains(a.type))
        throw new IllegalArgumentException("Vector required");
      if (k.equals("NORMALIZE") && !i.resultType.equals(a.type))
        throw new IllegalArgumentException("Normalization preserves vector dimension");
    } else for (var e : in.entrySet()) require(e.getValue(), "Scalar", i);
    var spec = ProcessingRegistry.ITEMS.get(k);
    if (!Set.of("SELECT", "NORMALIZE").contains(k) && !spec.result().equals(i.resultType))
      throw new IllegalArgumentException(i.id + ": result type must be " + spec.result());
  }

  private static double evaluate(
      String k, Parameters p, Map<String, Input> in, Context c, int component, long seed, int dim) {
    Input aa = in.get("a"), bb = in.get("b");
    if (k.equals("SELECT"))
      return in.get(in.get("condition").scalar(c) != 0 ? "true" : "false").number.get(c, component);
    if (k.equals("ADD") || k.equals("SUBTRACT") || k.equals("MULTIPLY") || k.equals("DIVIDE")) {
      double av = aa.number.get(c, component),
          bv = bb.number.get(c, (k.equals("ADD") || k.equals("SUBTRACT")) ? component : 0);
      return switch (k) {
        case "ADD" -> av + bv;
        case "SUBTRACT" -> av - bv;
        case "MULTIPLY" -> av * bv;
        default -> av / bv;
      };
    }
    if (k.equals("EQUAL") || k.equals("NOT_EQUAL")) {
      boolean equal = true;
      for (int j = 0; j < aa.components(); j++)
        if (aa.number.get(c, j) != bb.number.get(c, j)) {
          equal = false;
          break;
        }
      return equal == k.equals("EQUAL") ? 1 : 0;
    }
    double a = aa == null ? 0 : aa.scalar(c);
    if (k.equals("AND")) return a != 0 && bb.scalar(c) != 0 ? 1 : 0;
    if (k.equals("OR")) return a != 0 || bb.scalar(c) != 0 ? 1 : 0;
    double b = bb == null ? 0 : bb.scalar(c);
    return switch (k) {
      case "ADD" -> a + b;
      case "SUBTRACT" -> a - b;
      case "MULTIPLY" -> a * b;
      case "DIVIDE" -> a / b;
      case "POWER" -> StrictMath.pow(a, b);
      case "MODULO" -> a % b;
      case "MIN", "UNION" -> StrictMath.min(a, b);
      case "MAX", "INTERSECTION" -> StrictMath.max(a, b);
      case "SUBTRACTION" -> StrictMath.max(a, -b);
      case "ABS" -> StrictMath.abs(a);
      case "FLOOR" -> StrictMath.floor(a);
      case "CEIL" -> StrictMath.ceil(a);
      case "ROUND" -> StrictMath.floor(a + .5);
      case "SIN" -> StrictMath.sin(a);
      case "COS" -> StrictMath.cos(a);
      case "TAN" -> StrictMath.tan(a);
      case "SQRT" -> StrictMath.sqrt(a);
      case "LOG" -> StrictMath.log(a);
      case "EXP" -> StrictMath.exp(a);
      case "SIGN" -> StrictMath.signum(a);
      case "LESS" -> a < b ? 1 : 0;
      case "LESS_EQUAL" -> a <= b ? 1 : 0;
      case "GREATER" -> a > b ? 1 : 0;
      case "GREATER_EQUAL" -> a >= b ? 1 : 0;
      case "EQUAL" -> a == b ? 1 : 0;
      case "NOT_EQUAL" -> a != b ? 1 : 0;
      case "NOT" -> a == 0 ? 1 : 0;
      case "COMPOSE3", "COORDINATES3" ->
          in.get(component == 0 ? "x" : component == 1 ? "y" : "z").scalar(c);
      case "COMPOSE2", "COORDINATES2" -> in.get(component == 0 ? "x" : "z").scalar(c);
      case "DOT" -> {
        double v = 0;
        for (int j = 0; j < aa.components(); j++) v += aa.number.get(c, j) * bb.number.get(c, j);
        yield v;
      }
      case "CROSS" -> {
        int u = (component + 1) % 3, v = (component + 2) % 3;
        yield aa.number.get(c, u) * bb.number.get(c, v) - aa.number.get(c, v) * bb.number.get(c, u);
      }
      case "LENGTH", "NORMALIZE" -> {
        double sum = 0;
        for (int j = 0; j < aa.components(); j++) {
          double v = aa.number.get(c, j);
          sum += v * v;
        }
        double len = StrictMath.sqrt(sum);
        yield k.equals("LENGTH") ? len : len == 0 ? 0 : aa.number.get(c, component) / len;
      }
      case "COMPONENT" -> {
        int j = (int) p.value(Parameter.COMPONENT);
        if (j < 0 || j >= aa.components()) throw new IllegalArgumentException("Component index");
        yield aa.number.get(c, j);
      }
      case "REPLICATE2", "REPLICATE3", "BOOLEAN_TO_SCALAR" -> a;
      case "COORDINATES_TO_VECTOR", "VECTOR_TO_COORDINATES" -> aa.number.get(c, component);
      case "PROJECT_VECTOR", "PROJECT_COORDINATES" -> aa.number.get(c, p.projectAxis(component));
      case "EXPAND_VECTOR", "EXPAND_COORDINATES" ->
          component == p.insertedAxis() ? b : aa.number.get(c, p.expandedComponent(component));
      case "VECTOR_ROTATE" -> {
        double angle = p.value(Parameter.ANGLE) * StrictMath.PI / 180,
            co = StrictMath.cos(angle),
            si = StrictMath.sin(angle);
        if (aa.components() == 2)
          yield component == 0
              ? aa.number.get(c, 0) * co - aa.number.get(c, 1) * si
              : aa.number.get(c, 0) * si + aa.number.get(c, 1) * co;
        int axis = p.axis.equals("x") ? 0 : p.axis.equals("y") ? 1 : 2,
            u = (axis + 1) % 3,
            v = (axis + 2) % 3;
        yield component == axis
            ? aa.number.get(c, axis)
            : component == u
                ? aa.number.get(c, u) * co - aa.number.get(c, v) * si
                : aa.number.get(c, u) * si + aa.number.get(c, v) * co;
      }

      case "SCALAR_TO_BOOLEAN" -> a != 0 ? 1 : 0;
      case "CONSTANT" -> p.value(Parameter.VALUE);
      case "COORDINATE_HASH" ->
          CoordinateHash.probability(
              seed,
              (int) StrictMath.floor(c.x),
              (int) StrictMath.floor(c.y),
              (int) StrictMath.floor(c.z));
      case "COORDINATE" ->
          switch (p.axis) {
            case "y" -> c.y;
            case "z" -> c.z;
            default -> c.x;
          };
      case "SPHERE" ->
          StrictMath.sqrt(
                  StrictMath.pow(c.x - p.value(Parameter.CENTER_X), 2)
                      + (dim == 2 ? 0 : StrictMath.pow(c.y - p.value(Parameter.CENTER_Y), 2))
                      + StrictMath.pow(c.z - p.value(Parameter.CENTER_Z), 2))
              - p.value(Parameter.RADIUS);
      case "BOX" -> {
        double x = StrictMath.abs(c.x - p.value(Parameter.CENTER_X)) - p.value(Parameter.EXTENT_X),
            y =
                dim == 2
                    ? Double.NEGATIVE_INFINITY
                    : StrictMath.abs(c.y - p.value(Parameter.CENTER_Y))
                        - p.value(Parameter.EXTENT_Y),
            z = StrictMath.abs(c.z - p.value(Parameter.CENTER_Z)) - p.value(Parameter.EXTENT_Z);
        yield StrictMath.sqrt(
                StrictMath.pow(StrictMath.max(x, 0), 2)
                    + StrictMath.pow(StrictMath.max(y, 0), 2)
                    + StrictMath.pow(StrictMath.max(z, 0), 2))
            + StrictMath.min(StrictMath.max(x, StrictMath.max(y, z)), 0);
      }
      case "SMOOTH_UNION" -> {
        double smooth = p.value(Parameter.SMOOTHNESS),
            h = Expression.clamp(.5 + .5 * (b - a) / smooth, 0, 1);
        yield b + (a - b) * h - smooth * h * (1 - h);
      }
      default -> throw new IllegalArgumentException("No evaluator for " + k);
    };
  }

  private enum Parameter {
    VALUE("value", 0),
    MIN("min", 0),
    MAX("max", 1),
    INPUT_MIN("inputMin", -1),
    INPUT_MAX("inputMax", 1),
    OUTPUT_MIN("outputMin", 0),
    OUTPUT_MAX("outputMax", 1),
    LEVELS("levels", 8),
    TRANSITION("transition", 0),
    EXTENT("extent", 64),
    COMPONENT("component", 0),
    RADIUS("radius", 16),
    EXTENT_X("extentX", 16),
    EXTENT_Y("extentY", 16),
    EXTENT_Z("extentZ", 16),
    SMOOTHNESS("smoothness", 1),
    CENTER_X("centerX", 0),
    CENTER_Y("centerY", 0),
    CENTER_Z("centerZ", 0),
    ANGLE("angle", 0);
    final String key;
    final double defaultValue;

    Parameter(String key, double value) {
      this.key = key;
      defaultValue = value;
    }
  }

  private static final class Parameters {
    final double[] values;
    final boolean clamp;
    final String axis, pair;

    private Parameters(double[] values, boolean clamp, String axis, String pair) {
      this.values = values;
      this.clamp = clamp;
      this.axis = axis;
      this.pair = pair;
    }

    double value(Parameter p) {
      return values[p.ordinal()];
    }

    int projectAxis(int k) {
      return pair.equals("XY") ? k : pair.equals("YZ") ? k + 1 : k == 0 ? 0 : 2;
    }

    int insertedAxis() {
      return pair.equals("XY") ? 2 : pair.equals("YZ") ? 0 : 1;
    }

    int expandedComponent(int k) {
      return k == projectAxis(0) ? 0 : 1;
    }

    static Parameters compile(Item item) {
      double[] values = new double[Parameter.values().length];
      for (Parameter p : Parameter.values())
        values[p.ordinal()] = param(item, p.key, p.defaultValue);
      var clamp = item.parameters.get("clamp");
      if (clamp != null && !clamp.isBoolean())
        throw new IllegalArgumentException("Clamp parameter must be Boolean");
      String axis = text(item, "axis", "x"), pair = text(item, "axes", "XZ");
      if (!Set.of("x", "y", "z").contains(axis) || !Set.of("XY", "XZ", "YZ").contains(pair))
        throw new IllegalArgumentException("Explicit valid axis/projection required");
      if (item.kind.equals("TERRACE")
          && (values[Parameter.LEVELS.ordinal()] < 1
              || values[Parameter.LEVELS.ordinal()]
                  != StrictMath.rint(values[Parameter.LEVELS.ordinal()])
              || values[Parameter.TRANSITION.ordinal()] < 0
              || values[Parameter.TRANSITION.ordinal()] > 1))
        throw new IllegalArgumentException(
            "Terrace requires integer levels >=1 and transition in [0,1]");
      if (item.kind.equals("REMAP")
          && values[Parameter.INPUT_MIN.ordinal()] == values[Parameter.INPUT_MAX.ordinal()])
        throw new IllegalArgumentException("Remap input endpoints must differ");
      if (item.kind.equals("FALLOFF") && values[Parameter.EXTENT.ordinal()] <= 0
          || item.kind.equals("SMOOTH_UNION") && values[Parameter.SMOOTHNESS.ordinal()] <= 0)
        throw new IllegalArgumentException("Extent/smoothness must be positive");
      return new Parameters(values, clamp != null && clamp.booleanValue(), axis, pair);
    }
  }

  private static double param(Item i, String name, double fallback) {
    var n = i.parameters.get(name);
    if (n == null) return fallback;
    if (!n.isNumber() || !Double.isFinite(n.doubleValue()))
      throw new IllegalArgumentException(i.id + ": finite numeric parameter required: " + name);
    return n.doubleValue();
  }

  private static String text(Item i, String name, String fallback) {
    var n = i.parameters.get(name);
    return n == null ? fallback : n.asText();
  }
}
