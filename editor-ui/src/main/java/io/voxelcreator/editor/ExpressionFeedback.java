package io.voxelcreator.editor;

import io.voxelcreator.core.*;
import io.voxelcreator.model.ProjectData;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/**
 * Bounded, display-only parser feedback; complete binding/resource validation still occurs on
 * Apply.
 */
public final class ExpressionFeedback {
  public record Result(
      String error,
      int start,
      int end,
      Expression.Type type,
      List<String> inputs,
      Expression.Statistics statistics,
      String input) {
    public Result(
        String error,
        int start,
        int end,
        Expression.Type type,
        List<String> inputs,
        Expression.Statistics statistics) {
      this(error, start, end, type, inputs, statistics, null);
    }

    public Result {
      inputs = List.copyOf(inputs);
    }

    public boolean valid() {
      return error == null;
    }
  }

  private record Key(
      String source,
      String result,
      Map<String, Expression.Type> types,
      Map<String, Double> constants) {}

  /** Immutable semantic input: worker compilation never reads live model objects. */
  static final class Request {
    private final Key key;
    private final Result definitionError;

    private Request(Key key, Result definitionError) {
      this.key = key;
      this.definitionError = definitionError;
    }

    String source() {
      return key.source();
    }

    long bytes() {
      long size = 512L + 6L * key.source().length();
      for (String name : key.types().keySet()) size += 192L + 4L * name.length();
      for (String name : key.constants().keySet()) size += 192L + 4L * name.length();
      if (definitionError != null) {
        if (definitionError.error() != null) size += 2L * definitionError.error().length();
        if (definitionError.input() != null) size += 2L * definitionError.input().length();
      }
      return size;
    }

    Result inspect() {
      if (definitionError != null) return definitionError;
      try {
        var program = Expression.compile(key.source(), key.types(), key.constants());
        if (!program.type().name().equals(key.result()))
          throw new Expression.Problem(
              "Expected " + key.result() + ", received " + program.type(),
              0,
              key.source().length());
        return new Result(null, 0, 0, program.type(), program.inputs(), program.statistics());
      } catch (Expression.Problem problem) {
        return new Result(problem.getMessage(), problem.start, problem.end, null, List.of(), null);
      }
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request r
          && key.equals(r.key)
          && Objects.equals(definitionError, r.definitionError);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, definitionError);
    }
  }

  private final LinkedHashMap<Key, Result> cache = new LinkedHashMap<>(32, .75f, true);

  public int cachedEntries() {
    return cache.size();
  }

  public Result inspect(ProjectData project, Noise noise, Item item) {
    var request = capture(project, noise, item, null);
    if (request.definitionError != null) return request.definitionError;
    var result = cache.get(request.key);
    if (result != null) return result;
    try {
      result = request.inspect();
      cache.put(request.key, result);
      while (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
      return result;
    } catch (RuntimeException failure) {
      return failure(failure, null);
    }
  }

  static String source(Item item) {
    return item.parameters.containsKey("expression")
        ? item.parameters.get("expression").asText()
        : "";
  }

  static Result inspectCaptured(Request request) {
    try {
      return request.inspect();
    } catch (RuntimeException failure) {
      return failure(failure, null);
    }
  }

  static Request capture(ProjectData project, Noise noise, Item item, String sourceOverride) {
    String currentInput = null;
    try {
      var types = new LinkedHashMap<String, Expression.Type>();
      var constants = new LinkedHashMap<String, Double>();
      for (var entry : item.inputs.entrySet()) {
        currentInput = entry.getKey();
        var binding = entry.getValue();
        String type = binding.type;
        if (!Set.of("Scalar", "Boolean").contains(type))
          throw new IllegalArgumentException(
              "Input " + entry.getKey() + " must be Scalar or Boolean");
        types.put(entry.getKey(), Expression.Type.valueOf(type));
        var literal = binding.sourceKind.equals("Constant") ? binding.value : null;
        if (binding.sourceKind.equals("Shared Parameter")) {
          var shared =
              noise.sharedParameters.stream()
                  .filter(p -> p.uuid.equals(binding.parameterUuid))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Unresolved shared parameter for " + entry.getKey()));
          if (!shared.type.equals(type))
            throw new IllegalArgumentException(
                "Shared parameter type mismatch for " + entry.getKey());
          literal = shared.value;
        }
        if ((binding.sourceKind.equals("Constant") || binding.sourceKind.equals("Shared Parameter"))
            && literal == null)
          throw new IllegalArgumentException("Constant input required: " + entry.getKey());
        if (literal != null) {
          if (type.equals("Boolean")) {
            if (!literal.isBoolean())
              throw new IllegalArgumentException("Boolean input required: " + entry.getKey());
            constants.put(entry.getKey(), literal.booleanValue() ? 1.0 : 0.0);
          } else {
            if (!literal.isNumber() || !Double.isFinite(literal.doubleValue()))
              throw new IllegalArgumentException("Finite input required: " + entry.getKey());
            constants.put(entry.getKey(), literal.doubleValue());
          }
        }
      }
      currentInput = null;
      for (String name : NoiseCompiler.AMBIENT) types.putIfAbsent(name, Expression.Type.Scalar);
      types.putIfAbsent("source", Expression.Type.Scalar);
      var ambient = new LinkedHashMap<String, Double>();
      ambient.put("worldMinY", (double) project.project.minY);
      ambient.put("worldMaxY", (double) project.project.maxY);
      ambient.put("worldHeight", (double) project.project.maxY - project.project.minY + 1);
      ambient.put("projectSeed", (double) project.project.seed);
      ambient.put(
          "noiseGeneratorSeed",
          (double) CoordinateHash.seed(project.project.seed, noise.uuid, noise.seedOffset));
      ambient.forEach(
          (name, value) -> {
            if (!item.inputs.containsKey(name)) constants.put(name, value);
          });
      String source = sourceOverride != null ? sourceOverride : source(item);
      var key = new Key(source, item.resultType, Map.copyOf(types), Map.copyOf(constants));
      return new Request(key, null);
    } catch (RuntimeException problem) {
      String text = sourceOverride;
      if (text == null)
        try {
          text = source(item);
        } catch (RuntimeException ignored) {
          text = "";
        }
      return new Request(
          new Key(text, item.resultType, Map.of(), Map.of()), failure(problem, currentInput));
    }
  }

  private static Result failure(RuntimeException problem, String currentInput) {
    return new Result(
        problem.getMessage() == null ? "Invalid expression input definition" : problem.getMessage(),
        0,
        0,
        null,
        List.of(),
        null,
        currentInput);
  }
}
