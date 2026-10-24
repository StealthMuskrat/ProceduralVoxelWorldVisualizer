package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.Algorithm;
import java.util.*;

/**
 * Stable adapter over vendored FastNoiseLite 1.1.1, double coordinates and float noise output
 * widened to double.
 */
public final class NoiseAlgorithms {
  @FunctionalInterface
  public interface Field {
    double sample(double x, double y, double z);
  }

  public static final List<String> KEYS =
      List.of(
          "OPEN_SIMPLEX_2",
          "PERLIN",
          "VALUE",
          "VALUE_CUBIC",
          "CELLULAR",
          "WHITE_HASH",
          "CONSTANT",
          "GRADIENT_Y",
          "SPHERE_SDF",
          "BOX_SDF");

  public static Field compile(Algorithm a, int dimension, long seed) {
    if (!KEYS.contains(a.key)) throw new IllegalArgumentException("Unknown algorithm " + a.key);
    for (String p : a.parameters.keySet())
      if (!Set.of(
              "frequency",
              "value",
              "radius",
              "extentX",
              "extentY",
              "extentZ",
              "cellularDistance",
              "cellularReturn",
              "jitter",
              "fractal",
              "octaves",
              "lacunarity",
              "gain",
              "pingPongStrength")
          .contains(p)) throw new IllegalArgumentException("Unsupported algorithm parameter " + p);
    double frequency = a.parameters.getOrDefault("frequency", .01);
    if (!Double.isFinite(frequency) || frequency <= 0 || frequency > 1000000)
      throw new IllegalArgumentException("Frequency must be in (0,1000000]");
    double val = a.parameters.getOrDefault("value", 0.),
        radius = a.parameters.getOrDefault("radius", 16.);
    if (a.key.equals("CONSTANT")) return (x, y, z) -> val;
    if (a.key.equals("GRADIENT_Y")) return (x, y, z) -> dimension == 2 ? z : y;
    if (a.key.equals("WHITE_HASH"))
      return (x, y, z) ->
          (CoordinateHash.mix(
                          seed
                              ^ CoordinateHash.mix(Double.doubleToLongBits(x * frequency))
                              ^ Long.rotateLeft(
                                  CoordinateHash.mix(
                                      Double.doubleToLongBits(
                                          (dimension == 2 ? 0 : y) * frequency)),
                                  21)
                              ^ Long.rotateLeft(
                                  CoordinateHash.mix(Double.doubleToLongBits(z * frequency)), 42))
                      >>> 11)
                  * 0x1.0p-52
              - 1;
    if (a.key.equals("SPHERE_SDF"))
      return (x, y, z) -> StrictMath.sqrt(x * x + (dimension == 2 ? 0 : y * y) + z * z) - radius;
    if (a.key.equals("BOX_SDF")) {
      double ex = a.parameters.getOrDefault("extentX", 16.),
          ey = a.parameters.getOrDefault("extentY", 16.),
          ez = a.parameters.getOrDefault("extentZ", 16.);
      return (x, y, z) -> {
        double qx = StrictMath.abs(x) - ex,
            qy = dimension == 2 ? Double.NEGATIVE_INFINITY : StrictMath.abs(y) - ey,
            qz = StrictMath.abs(z) - ez;
        double ax = StrictMath.max(qx, 0), ay = StrictMath.max(qy, 0), az = StrictMath.max(qz, 0);
        return StrictMath.sqrt(ax * ax + ay * ay + az * az)
            + StrictMath.min(StrictMath.max(qx, StrictMath.max(qy, qz)), 0);
      };
    }
    var f = new FastNoiseLite((int) (seed ^ (seed >>> 32)));
    f.SetFrequency((float) frequency);
    f.SetNoiseType(
        switch (a.key) {
          case "OPEN_SIMPLEX_2" -> FastNoiseLite.NoiseType.OpenSimplex2;
          case "PERLIN" -> FastNoiseLite.NoiseType.Perlin;
          case "VALUE" -> FastNoiseLite.NoiseType.Value;
          case "VALUE_CUBIC" -> FastNoiseLite.NoiseType.ValueCubic;
          case "CELLULAR" -> FastNoiseLite.NoiseType.Cellular;
          default -> throw new AssertionError();
        });
    int oct = a.parameters.getOrDefault("octaves", 3.).intValue();
    if (oct < 1 || oct > 16) throw new IllegalArgumentException("Octaves 1..16");
    f.SetFractalOctaves(oct);
    f.SetFractalLacunarity(a.parameters.getOrDefault("lacunarity", 2.).floatValue());
    f.SetFractalGain(a.parameters.getOrDefault("gain", .5).floatValue());
    int fractal = a.parameters.getOrDefault("fractal", 0.).intValue();
    if (fractal < 0 || fractal > 3) throw new IllegalArgumentException("Fractal index 0..3");
    f.SetFractalType(FastNoiseLite.FractalType.values()[fractal]);
    f.SetFractalPingPongStrength(a.parameters.getOrDefault("pingPongStrength", 2.).floatValue());
    int distance = a.parameters.getOrDefault("cellularDistance", 1.).intValue(),
        ret = a.parameters.getOrDefault("cellularReturn", 1.).intValue();
    if (distance < 0
        || distance >= FastNoiseLite.CellularDistanceFunction.values().length
        || ret < 0
        || ret >= FastNoiseLite.CellularReturnType.values().length)
      throw new IllegalArgumentException("Invalid cellular variant");
    f.SetCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.values()[distance]);
    f.SetCellularReturnType(FastNoiseLite.CellularReturnType.values()[ret]);
    f.SetCellularJitter(a.parameters.getOrDefault("jitter", 1.).floatValue());
    return dimension == 2 ? (x, y, z) -> f.GetNoise(x, z) : (x, y, z) -> f.GetNoise(x, y, z);
  }
}
