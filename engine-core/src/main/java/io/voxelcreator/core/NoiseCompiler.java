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
  private final Map<UUID, Noise> resources = new LinkedHashMap<>();
  private final Map<UUID, Field> compiled = new HashMap<>();
  private final Set<UUID> building = new LinkedHashSet<>();

  public NoiseCompiler(ProjectData data, long seed) {
    project = ProjectIO.copy(data);
    runtimeSeed = seed;
    for (var n : project.noise2D) resources.put(n.uuid, n);
    for (var n : project.noise3D) resources.put(n.uuid, n);
  }

  public Field compile(UUID uuid) {
    var old = compiled.get(uuid);
    if (old != null) return old;
    var n = resources.get(uuid);
    if (n == null) throw new IllegalArgumentException("Unknown noise UUID " + uuid);
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

  private record Input(String type, Number number) {
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
    double x, y, z, source;
    final double[] values;

    Context(int size) {
      values = new double[size];
    }
  }

  private Field build(Noise n) {
    long seed = CoordinateHash.seed(runtimeSeed, n.uuid, n.seedOffset);
    Field source = NoiseAlgorithms.compile(n.algorithm, n.dimension, seed);
    var steps = new ArrayList<Step>();
    var transforms = new ArrayList<Step>();
    var inputs = new HashMap<UUID, Input>();
    var params = new HashMap<UUID, Input>();
    for (var p : n.sharedParameters) params.put(p.uuid, constant(p.type, p.value));
    for (var t : n.coordinateTransforms) {
      if (!Set.of("TRANSLATE", "SCALE", "ROTATE", "DOMAIN_WARP").contains(t.kind))
        throw new IllegalArgumentException("Unsupported transform " + t.kind);
      if (!t.resultType.equals(n.dimension == 2 ? "Coordinates2D" : "Coordinates3D"))
        throw new IllegalArgumentException(
            "Transform result must be dimension-matched Coordinates");
      if (!t.enabled) continue;
      double a = param(t, "x", t.kind.equals("SCALE") ? 1 : 0),
          b = param(t, "y", t.kind.equals("SCALE") ? 1 : 0),
          d = param(t, "z", t.kind.equals("SCALE") ? 1 : 0),
          angle = param(t, "angle", 0) * StrictMath.PI / 180;
      if (t.kind.equals("TRANSLATE"))
        transforms.add(
            c -> {
              c.x += a;
              c.y += b;
              c.z += d;
            });
      else if (t.kind.equals("SCALE"))
        transforms.add(
            c -> {
              c.x *= a;
              c.y *= b;
              c.z *= d;
            });
      else if (t.kind.equals("ROTATE")) {
        double co = StrictMath.cos(angle), si = StrictMath.sin(angle);
        transforms.add(
            c -> {
              double x = c.x;
              c.x = x * co - c.z * si;
              c.z = x * si + c.z * co;
            });
      } else {
        double amplitude = param(t, "amplitude", 8), freq = param(t, "frequency", .01);
        var alg = new Algorithm();
        alg.parameters.put("frequency", freq);
        Field warp =
            NoiseAlgorithms.compile(
                alg,
                n.dimension,
                CoordinateHash.mix(seed + Double.doubleToLongBits(param(t, "seedOffset", 0))));
        transforms.add(
            c -> {
              double x = c.x, y = c.y, z = c.z;
              c.x += amplitude * warp.sample(x, y, z);
              c.y += n.dimension == 2 ? 0 : amplitude * warp.sample(x + 31.1, y - 17.3, z + 7.7);
              c.z += amplitude * warp.sample(x - 11.2, y + 43.8, z - 19.4);
            });
      }
    }
    int slot = 0;
    for (var item : n.processing) {
      var spec = ProcessingRegistry.ITEMS.get(item.kind);
      if (spec == null)
        throw new IllegalArgumentException(
            "Unsupported processing kind " + item.kind + " in " + item.id);
      if (!item.enabled) {
        continue;
      }
      final int index = slot;
      slot += 3;
      var bound = new HashMap<String, Input>();
      for (var e : item.inputs.entrySet())
        bound.put(e.getKey(), binding(e.getValue(), inputs, params, n, seed));
      for (String required : spec.inputs())
        if (!bound.containsKey(required))
          throw new IllegalArgumentException(item.id + ": missing input " + required);
      Input a = bound.get("a"), b = bound.get("b");
      String kind = item.kind;
      Number eval;
      if (kind.equals("EXPRESSION")) {
        var types = new LinkedHashMap<String, Expression.Type>();
        for (var e : bound.entrySet()) {
          if (!Set.of("Scalar", "Boolean").contains(e.getValue().type))
            throw new IllegalArgumentException("Expression inputs must be Scalar or Boolean");
          types.put(e.getKey(), Expression.Type.valueOf(e.getValue().type));
        }
        for (String key : AMBIENT) types.putIfAbsent(key, Expression.Type.Scalar);
        types.putIfAbsent("source", Expression.Type.Scalar);
        var program = Expression.compile(text(item, "expression", ""), types);
        if (!program.type().name().equals(item.resultType))
          throw new IllegalArgumentException("Expression result type mismatch in " + item.id);
        ThreadLocal<Expression.Variables> vars = new ThreadLocal<>();
        ThreadLocal<Context> current = new ThreadLocal<>();
        eval =
            (c, k) -> {
              current.set(c);
              Expression.Variables v = vars.get();
              if (v == null) {
                v =
                    name -> {
                      Context ctx = current.get();
                      var in = bound.get(name);
                      return in != null
                          ? in.scalar(ctx)
                          : name.equals("source") ? ctx.source : ambient(name, ctx, seed);
                    };
                vars.set(v);
              }
              return program.type() == Expression.Type.Scalar
                  ? program.scalar(v)
                  : program.bool(v) ? 1 : 0;
            };
      } else if (Set.of(
              "NOISE_REFERENCE",
              "FBM",
              "RIDGED",
              "BILLOW",
              "PING_PONG",
              "OFFSET_SAMPLE",
              "DERIVATIVE",
              "SMOOTH_FILTER")
          .contains(kind)) {
        Field field = source;
        String ref = text(item, "noiseGeneratorUuid", "");
        if (!ref.isEmpty()) field = compile(UUID.fromString(ref));
        final Field sampled = field;
        int oct = (int) param(item, "octaves", 4), radius = (int) param(item, "radius", 1);
        if (oct < 1 || oct > 16 || radius < 0 || radius > 4)
          throw new IllegalArgumentException("Sampling budget exceeded");
        double lac = param(item, "lacunarity", 2),
            gain = param(item, "gain", .5),
            spacing = param(item, "spacing", 1),
            dx = param(item, "x", 0),
            dy = param(item, "y", 0),
            dz = param(item, "z", 0);
        if (spacing <= 0) throw new IllegalArgumentException("Positive stencil spacing required");
        eval =
            (c, k) -> {
              if (kind.equals("NOISE_REFERENCE")) return sampled.sample(c.x, c.y, c.z);
              if (kind.equals("OFFSET_SAMPLE")) return sampled.sample(c.x + dx, c.y + dy, c.z + dz);
              if (kind.equals("DERIVATIVE"))
                return (sampled.sample(c.x + spacing, c.y, c.z)
                        - sampled.sample(c.x - spacing, c.y, c.z))
                    / (2 * spacing);
              if (kind.equals("SMOOTH_FILTER")) {
                double sum = 0;
                int count = 0;
                for (int y = n.dimension == 2 ? 0 : -radius;
                    y <= (n.dimension == 2 ? 0 : radius);
                    y++)
                  for (int z = -radius; z <= radius; z++)
                    for (int x = -radius; x <= radius; x++) {
                      sum +=
                          sampled.sample(c.x + x * spacing, c.y + y * spacing, c.z + z * spacing);
                      count++;
                    }
                return sum / count;
              }
              double sum = 0, amp = 1, freq = 1;
              for (int i = 0; i < oct; i++) {
                double v = sampled.sample(c.x * freq, c.y * freq, c.z * freq);
                v =
                    switch (kind) {
                      case "RIDGED" -> 1 - StrictMath.abs(v);
                      case "BILLOW" -> 2 * StrictMath.abs(v) - 1;
                      case "PING_PONG" -> {
                        double t = (v + 1) % 2;
                        yield 1 - StrictMath.abs(t - 1);
                      }
                      default -> v;
                    };
                sum += amp * v;
                amp *= gain;
                freq *= lac;
              }
              return sum;
            };
      } else if (kind.equals("CURVE")) {
        require(a, "Scalar", item);
        var curve = ProjectIO.JSON.convertValue(item.parameters.get("curve"), Curve.class);
        var cc = new ScalarCurve(curve);
        eval = (c, k) -> cc.sample(a.scalar(c));
      } else {
        validateSignature(item, bound);
        eval = (c, k) -> evaluate(kind, item, bound, c, k, seed, n.dimension);
      }
      int components = components(item.resultType);
      Number number = eval;
      steps.add(
          c -> {
            try {
              for (int k = 0; k < components; k++) {
                double value = number.get(c, k);
                if (!Double.isFinite(value)) throw new ArithmeticException("Nonfinite result");
                c.values[index + k] = value;
              }
            } catch (RuntimeException e) {
              if (e instanceof EvaluationFailure) throw e;
              throw new EvaluationFailure(n.uuid, item.uuid, c.x, c.y, c.z, e.getMessage());
            }
          });
      inputs.put(item.uuid, new Input(item.resultType, (c, k) -> c.values[index + k]));
    }
    var output = binding(n.outputBinding, inputs, params, n, seed);
    if (!output.type.equals("Scalar")) throw new IllegalArgumentException("Output must be Scalar");
    final int slots = slot;
    ThreadLocal<Context> scratch = ThreadLocal.withInitial(() -> new Context(slots));
    var frozen = List.copyOf(steps);
    var frozenTransforms = List.copyOf(transforms);
    return (x, y, z) -> {
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
        throw new EvaluationFailure(n.uuid, null, x, y, z, "Finite sample coordinates required");
      Context c = scratch.get();
      c.x = x;
      c.y = y;
      c.z = z;
      for (var t : frozenTransforms) t.run(c);
      c.source = source.sample(c.x, c.y, c.z);
      if (!Double.isFinite(c.source))
        throw new EvaluationFailure(n.uuid, null, x, y, z, "Nonfinite source");
      for (var s : frozen) s.run(c);
      double result = output.scalar(c);
      if (!Double.isFinite(result))
        throw new EvaluationFailure(n.uuid, null, x, y, z, "Nonfinite output");
      return result;
    };
  }

  private static int components(String t) {
    return t.endsWith("2") || t.endsWith("2D") ? 2 : t.endsWith("3") || t.endsWith("3D") ? 3 : 1;
  }

  private static Input constant(String type, com.fasterxml.jackson.databind.JsonNode n) {
    if (n == null) throw new IllegalArgumentException("Constant required");
    double[] values = new double[components(type)];
    if (type.equals("Boolean")) values[0] = n.booleanValue() ? 1 : 0;
    else if (values.length == 1) values[0] = n.doubleValue();
    else for (int k = 0; k < values.length; k++) values[k] = n.get(k).doubleValue();
    return new Input(type, (c, k) -> values[k]);
  }

  private Input binding(
      Binding b, Map<UUID, Input> earlier, Map<UUID, Input> params, Noise n, long seed) {
    if (b == null) throw new IllegalArgumentException("Missing binding");
    Input in =
        switch (b.sourceKind) {
          case "Constant" -> constant(b.type, b.value);
          case "Source Result" -> new Input("Scalar", (c, k) -> c.source);
          case "Earlier Result" -> earlier.get(b.itemUuid);
          case "Shared Parameter" -> params.get(b.parameterUuid);
          case "Ambient Value" -> {
            if (!AMBIENT.contains(b.ambientKey))
              throw new IllegalArgumentException("Unknown ambient " + b.ambientKey);
            yield new Input("Scalar", (c, k) -> ambient(b.ambientKey, c, seed));
          }
          case "Noise Generator Reference" -> {
            var target = resources.get(b.noiseGeneratorUuid);
            var f = compile(b.noiseGeneratorUuid);
            String projection = b.projection;
            double slice = b.slice;
            if (target.dimension != n.dimension && !Set.of("XZ", "XY", "YZ").contains(projection))
              throw new IllegalArgumentException("Explicit projection required");
            yield new Input(
                "Scalar",
                (c, k) ->
                    target.dimension == 2
                        ? switch (projection) {
                          case "XY" -> f.sample(c.x, 0, c.y);
                          case "YZ" -> f.sample(c.y, 0, c.z);
                          default -> f.sample(c.x, 0, c.z);
                        }
                        : n.dimension == 2 ? f.sample(c.x, slice, c.z) : f.sample(c.x, c.y, c.z));
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
      String k, Item i, Map<String, Input> in, Context c, int component, long seed, int dim) {
    Input aa = in.get("a"), bb = in.get("b");
    if (k.equals("SELECT"))
      return in.get(in.get("condition").scalar(c) != 0 ? "true" : "false").number.get(c, component);
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
      case "CLAMP" -> Expression.clamp(a, param(i, "min", 0), param(i, "max", 1));
      case "REMAP" -> {
        double t =
            (a - param(i, "inputMin", -1)) / (param(i, "inputMax", 1) - param(i, "inputMin", -1));
        double v =
            param(i, "outputMin", 0) + t * (param(i, "outputMax", 1) - param(i, "outputMin", 0));
        yield i.parameters.containsKey("clamp") && i.parameters.get("clamp").asBoolean()
            ? Expression.clamp(v, param(i, "outputMin", 0), param(i, "outputMax", 1))
            : v;
      }
      case "TERRACE" -> {
        double levels = param(i, "levels", 8);
        yield StrictMath.floor(a * levels) / levels;
      }
      case "FALLOFF" -> {
        double extent = param(i, "extent", 64),
            d = StrictMath.sqrt(c.x * c.x + (dim == 2 ? 0 : c.y * c.y) + c.z * c.z);
        yield a * (1 - Expression.clamp(d / extent, 0, 1));
      }
      case "COMPOSE3" -> in.get(component == 0 ? "x" : component == 1 ? "y" : "z").scalar(c);
      case "COMPOSE2" -> in.get(component == 0 ? "x" : "z").scalar(c);
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
        int j = (int) param(i, "component", 0);
        if (j < 0 || j >= aa.components()) throw new IllegalArgumentException("Component index");
        yield aa.number.get(c, j);
      }
      case "REPLICATE3", "BOOLEAN_TO_SCALAR" -> a;
      case "SCALAR_TO_BOOLEAN" -> a != 0 ? 1 : 0;
      case "CONSTANT" -> param(i, "value", 0);
      case "COORDINATE_HASH" ->
          CoordinateHash.probability(
              seed,
              (int) StrictMath.floor(c.x),
              (int) StrictMath.floor(c.y),
              (int) StrictMath.floor(c.z));
      case "COORDINATE" ->
          switch (text(i, "axis", "x")) {
            case "y" -> c.y;
            case "z" -> c.z;
            default -> c.x;
          };
      case "SPHERE" ->
          StrictMath.sqrt(c.x * c.x + (dim == 2 ? 0 : c.y * c.y) + c.z * c.z)
              - param(i, "radius", 16);
      case "BOX" -> {
        double x = StrictMath.abs(c.x) - param(i, "extentX", 16),
            y = dim == 2 ? Double.NEGATIVE_INFINITY : StrictMath.abs(c.y) - param(i, "extentY", 16),
            z = StrictMath.abs(c.z) - param(i, "extentZ", 16);
        yield StrictMath.sqrt(
                StrictMath.pow(StrictMath.max(x, 0), 2)
                    + StrictMath.pow(StrictMath.max(y, 0), 2)
                    + StrictMath.pow(StrictMath.max(z, 0), 2))
            + StrictMath.min(StrictMath.max(x, StrictMath.max(y, z)), 0);
      }
      case "SMOOTH_UNION" -> {
        double smooth = param(i, "smoothness", 1),
            h = Expression.clamp(.5 + .5 * (b - a) / smooth, 0, 1);
        yield b + (a - b) * h - smooth * h * (1 - h);
      }
      default -> throw new IllegalArgumentException("No evaluator for " + k);
    };
  }

  private static double param(Item i, String name, double fallback) {
    var n = i.parameters.get(name);
    return n == null ? fallback : n.asDouble();
  }

  private static String text(Item i, String name, String fallback) {
    var n = i.parameters.get(name);
    return n == null ? fallback : n.asText();
  }
}
