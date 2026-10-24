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
      Expression.Statistics statistics) {
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

  private final LinkedHashMap<Key, Result> cache = new LinkedHashMap<>(32, .75f, true);

  public int cachedEntries() {
    return cache.size();
  }

  public Result inspect(ProjectData project, Noise noise, Item item) {
    try {
      var types = new LinkedHashMap<String, Expression.Type>();
      var constants = new LinkedHashMap<String, Double>();
      for (var entry : item.inputs.entrySet()) {
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
      String source =
          item.parameters.containsKey("expression")
              ? item.parameters.get("expression").asText()
              : "";
      var key = new Key(source, item.resultType, Map.copyOf(types), Map.copyOf(constants));
      Result result = cache.get(key);
      if (result != null) return result;
      try {
        var program = Expression.compile(source, types, constants);
        if (!program.type().name().equals(item.resultType))
          throw new Expression.Problem(
              "Expected " + item.resultType + ", received " + program.type(), 0, source.length());
        result = new Result(null, 0, 0, program.type(), program.inputs(), program.statistics());
      } catch (Expression.Problem problem) {
        result =
            new Result(problem.getMessage(), problem.start, problem.end, null, List.of(), null);
      }
      cache.put(key, result);
      while (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
      return result;
    } catch (RuntimeException problem) {
      return new Result(
          problem.getMessage() == null
              ? "Invalid expression input definition"
              : problem.getMessage(),
          0,
          0,
          null,
          List.of(),
          null);
    }
  }
}
