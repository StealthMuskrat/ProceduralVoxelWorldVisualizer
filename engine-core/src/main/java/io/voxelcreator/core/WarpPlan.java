package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.Item;
import java.util.*;

/** Immutable bounded warp program. Caller owns primitive result scratch and stage context. */
public final class WarpPlan {
  public interface Displacement<C> {
    boolean present(int axis);

    default boolean resampled(int axis) {
      return true;
    }

    double sample(C context, int axis, double x, double y, double z);
  }

  private final int dimension, octaves;
  private final String mode, order;
  private final double frequency;
  private final double[] scales, gains;
  private final NoiseAlgorithms.Field[] algorithms;

  private WarpPlan(
      int dimension,
      int octaves,
      String mode,
      String order,
      double frequency,
      double[] scales,
      double[] gains,
      NoiseAlgorithms.Field[] algorithms) {
    this.dimension = dimension;
    this.octaves = octaves;
    this.mode = mode;
    this.order = order;
    this.frequency = frequency;
    this.scales = scales;
    this.gains = gains;
    this.algorithms = algorithms;
  }

  public int octaves() {
    return octaves;
  }

  public static WarpPlan compile(Item item, int dimension, long noiseSeed) {
    var controls = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
    for (var c : WarpRegistry.controls(dimension))
      if (!c.key().startsWith("source.")) controls.put(c.key(), c.value(item));
    var algorithm = WarpRegistry.algorithm(item, dimension);
    int octaves = controls.get("warpOctaves").intValue();
    double lac = controls.get("warpLacunarity").doubleValue(),
        gain = controls.get("warpGain").doubleValue();
    var scales = new double[octaves];
    var gains = new double[octaves];
    var fields = new NoiseAlgorithms.Field[octaves];
    long baseSeed =
        CoordinateHash.mix(
            noiseSeed + Double.doubleToLongBits(controls.get("seedOffset").doubleValue()));
    boolean increment = controls.get("octaveSeeds").asText().equals("INCREMENT");
    for (int o = 0; o < octaves; o++) {
      scales[o] = o == 0 ? 1 : scales[o - 1] * lac;
      gains[o] = o == 0 ? 1 : gains[o - 1] * gain;
      if (!Double.isFinite(scales[o]) || scales[o] == 0)
        throw new ProcessingExtensions.InvalidControl(
            "warpLacunarity", "Warp octave scale overflow/underflow");
      if (!Double.isFinite(gains[o]))
        throw new ProcessingExtensions.InvalidControl("warpGain", "Warp octave gain overflow");
      fields[o] =
          NoiseAlgorithms.compile(
              algorithm,
              dimension,
              increment && o > 0 ? CoordinateHash.mix(baseSeed + o) : baseSeed);
    }
    return new WarpPlan(
        dimension,
        octaves,
        controls.get("warpMode").asText(),
        controls.get("warpOrder").asText(),
        controls.get("frequency").doubleValue(),
        scales,
        gains,
        fields);
  }

  public <C> void apply(
      double x,
      double y,
      double z,
      double amplitude,
      C context,
      Displacement<C> displacement,
      double[] out) {
    if (out.length < 3) throw new IllegalArgumentException("Three result slots required");
    finite(x);
    finite(y);
    finite(z);
    finite(amplitude);
    double xx = x, yy = y, zz = z;
    if (amplitude != 0)
      for (int o = 0; o < octaves; o++) {
        double amount = amplitude * gains[o];
        finite(amount);
        if (amount == 0) continue;
        if (mode.equals("SEQUENTIAL")) {
          for (int j = 0; j < dimension; j++) {
            int axis = order.charAt(j) == 'X' ? 0 : order.charAt(j) == 'Y' ? 1 : 2;
            double delta = amount * component(context, displacement, axis, o, xx, yy, zz);
            finite(delta);
            if (axis == 0) xx += delta;
            else if (axis == 1) yy += delta;
            else zz += delta;
            finite(xx);
            finite(yy);
            finite(zz);
          }
        } else {
          double qx = mode.equals("INDEPENDENT") ? x : xx,
              qy = mode.equals("INDEPENDENT") ? y : yy,
              qz = mode.equals("INDEPENDENT") ? z : zz;
          double dx = component(context, displacement, 0, o, qx, qy, qz),
              dy = dimension == 2 ? 0 : component(context, displacement, 1, o, qx, qy, qz),
              dz = component(context, displacement, 2, o, qx, qy, qz);
          double mx = amount * dx, my = amount * dy, mz = amount * dz;
          finite(mx);
          finite(my);
          finite(mz);
          xx += mx;
          yy += my;
          zz += mz;
          finite(xx);
          finite(yy);
          finite(zz);
        }
      }
    out[0] = xx;
    out[1] = yy;
    out[2] = zz;
  }

  private <C> double component(
      C context, Displacement<C> displacement, int axis, int octave, double x, double y, double z) {
    double scale = scales[octave], value;
    if (displacement != null && displacement.present(axis)) {
      if (!displacement.resampled(axis))
        return checked(displacement.sample(context, axis, x, y, z));
      double xx = x * scale * frequency,
          yy = dimension == 2 ? 0 : y * scale * frequency,
          zz = z * scale * frequency;
      finite(xx);
      finite(yy);
      finite(zz);
      value = displacement.sample(context, axis, xx, dimension == 2 ? 0 : yy, zz);
    } else {
      double xx = (x + (axis == 1 ? 31.1 : axis == 2 ? -11.2 : 0)) * scale,
          yy = dimension == 2 ? 0 : (y + (axis == 1 ? -17.3 : axis == 2 ? 43.8 : 0)) * scale,
          zz = (z + (axis == 1 ? 7.7 : axis == 2 ? -19.4 : 0)) * scale;
      finite(xx);
      finite(yy);
      finite(zz);
      value = algorithms[octave].sample(xx, yy, zz);
    }
    finite(value);
    return value;
  }

  private static double checked(double value) {
    finite(value);
    return value;
  }

  private static void finite(double value) {
    if (!Double.isFinite(value)) throw new ArithmeticException("Nonfinite warp arithmetic");
  }
}
