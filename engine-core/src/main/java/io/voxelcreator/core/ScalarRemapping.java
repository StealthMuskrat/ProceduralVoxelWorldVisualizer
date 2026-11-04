package io.voxelcreator.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.ProjectData.Item;
import java.util.Map;

/** Immutable scalar manipulation parameters shared by compiled evaluation and UI previews. */
public final class ScalarRemapping {
  private ScalarRemapping() {}

  public static final class Plan {
    private final String kind;
    private final int dimension;
    private final Map<String, JsonNode> controls;

    private Plan(String kind, int dimension, Map<String, JsonNode> controls) {
      this.kind = kind;
      this.dimension = dimension;
      var owned = new java.util.LinkedHashMap<String, JsonNode>();
      controls.forEach((key, value) -> owned.put(key, value.deepCopy()));
      this.controls = Map.copyOf(owned);
    }

    private double n(String key) {
      return controls.get(key).doubleValue();
    }

    private String s(String key) {
      return controls.get(key).textValue();
    }

    public boolean usesPosition() {
      return kind.equals("FALLOFF") && s("domain").equals("COORDINATES");
    }

    public double weight() {
      return n("weight");
    }

    public double effectiveWeight(double weight) {
      return s("weightPolicy").equals("CLAMP") ? Expression.clamp(weight, 0, 1) : weight;
    }

    public double output(double value) {
      if (!Double.isFinite(value)) throw new ArithmeticException("Nonfinite weighted blend result");
      return s("outputPolicy").equals("CLAMP_01") ? Expression.clamp(value, 0, 1) : value;
    }

    public String mode() {
      return s("mode");
    }

    public double combine(double a, double b, double weight) {
      double w = effectiveWeight(weight);
      if (w == 0) return output(a);
      double target =
          switch (mode()) {
            case "LERP" -> b;
            case "ADD" -> a + b;
            case "SUBTRACT" -> a - b;
            case "MULTIPLY" -> a * b;
            case "DIVIDE" -> a / b;
            case "MIN" -> StrictMath.min(a, b);
            case "MAX" -> StrictMath.max(a, b);
            default -> throw new AssertionError(mode());
          };
      if (!Double.isFinite(target)) throw new ArithmeticException("Nonfinite blend combination");
      return output(w == 1 ? target : FiniteInterpolation.between(a, target, w));
    }

    private static double normalizedDifference(double value, double center, double extent) {
      double difference = value - center;
      // Subtract first whenever representable, including nearby large coordinates. Only an
      // overflowing subtraction is distributed over the positive finite extent.
      return Double.isFinite(difference) ? difference / extent : value / extent - center / extent;
    }

    private double distance(double dx, double dy, double dz) {
      return switch (s("metric")) {
        case "EUCLIDEAN" -> {
          double squares = StrictMath.pow(dx, 2) + StrictMath.pow(dy, 2) + StrictMath.pow(dz, 2);
          if (Double.isNaN(squares)) yield squares;
          if (Double.isFinite(squares)
              && (squares >= Double.MIN_NORMAL || dx == 0 && dy == 0 && dz == 0))
            yield StrictMath.sqrt(squares);
          // Keep ordinary operation order, recovering underflowed/subnormal squares or overflow
          // with a scaled norm. The norm itself may overflow; normalization below can still fit.
          yield StrictMath.hypot(StrictMath.hypot(dx, dy), dz);
        }
        case "CHEBYSHEV" ->
            StrictMath.max(
                StrictMath.abs(dx), StrictMath.max(StrictMath.abs(dy), StrictMath.abs(dz)));
        case "MANHATTAN" -> StrictMath.abs(dx) + StrictMath.abs(dy) + StrictMath.abs(dz);
        case "AXIS" -> StrictMath.abs(s("axis").equals("x") ? dx : s("axis").equals("y") ? dy : dz);
        default -> throw new AssertionError(s("metric"));
      };
    }

    private double falloffRatio(double a, double x, double y, double z) {
      double extent = n("extent");
      if (s("domain").equals("INPUT")) {
        double distance = StrictMath.abs(a - n("centerX"));
        return Double.isFinite(distance)
            ? distance / extent
            : StrictMath.abs(normalizedDifference(a, n("centerX"), extent));
      }
      double dx = x - n("centerX"),
          dy = dimension == 2 ? 0 : y - n("centerY"),
          dz = z - n("centerZ");
      double distance = distance(dx, dy, dz);
      if (Double.isFinite(distance)) return distance / extent;
      return distance(
          normalizedDifference(x, n("centerX"), extent),
          dimension == 2 ? 0 : normalizedDifference(y, n("centerY"), extent),
          normalizedDifference(z, n("centerZ"), extent));
    }

    public double sample(double a, double x, double y, double z) {
      return switch (kind) {
        case "REMAP" -> {
          double t = (a - n("inputMin")) / (n("inputMax") - n("inputMin"));
          double v = n("outputMin") + t * (n("outputMax") - n("outputMin"));
          yield controls.get("clamp").booleanValue()
              ? Expression.clamp(
                  v,
                  StrictMath.min(n("outputMin"), n("outputMax")),
                  StrictMath.max(n("outputMin"), n("outputMax")))
              : v;
        }
        case "CLAMP" -> Expression.clamp(a, n("min"), n("max"));
        case "TERRACE" -> {
          double scaled = a * n("levels");
          double cell = StrictMath.floor(scaled),
              fraction = scaled - cell,
              transition = n("transition");
          double blend =
              transition == 0
                  ? 0
                  : Expression.clamp((fraction - 1 + transition) / transition, 0, 1);
          blend = blend * blend * (3 - 2 * blend);
          yield (cell + blend) / n("levels");
        }
        case "FALLOFF" -> {
          double t = falloffRatio(a, x, y, z);
          if (s("outside").equals("CLAMP")) t = Expression.clamp(t, 0, 1);
          double shaped =
              switch (s("shape")) {
                case "LINEAR" -> t;
                case "SMOOTHSTEP" -> t * t * (3 - 2 * t);
                case "SMOOTHERSTEP" -> t * t * t * (t * (6 * t - 15) + 10);
                case "COSINE" -> (1 - StrictMath.cos(StrictMath.PI * t)) * .5;
                default -> throw new AssertionError(s("shape"));
              };
          yield a * (1 - shaped);
        }
        default -> throw new IllegalStateException(kind);
      };
    }
  }

  public static Plan compile(Item item, int dimension) {
    var values = ProcessingExtensions.controls(item, dimension);
    if (item.kind.equals("REMAP")
        && values.get("inputMin").doubleValue() == values.get("inputMax").doubleValue())
      throw new ProcessingExtensions.InvalidControl(
          "inputMax", "Remap input endpoints must differ");
    if (item.kind.equals("CLAMP")
        && values.get("min").doubleValue() > values.get("max").doubleValue())
      throw new ProcessingExtensions.InvalidControl("max", "Clamp maximum must be >= minimum");
    return new Plan(item.kind, dimension, values);
  }
}
