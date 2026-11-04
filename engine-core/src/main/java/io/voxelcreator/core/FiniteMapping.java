package io.voxelcreator.core;

import java.math.BigInteger;

/** Preserve ordinary arithmetic; recover exceptional finite affine mappings as exact ratios. */
final class FiniteMapping {
  private static final long FRACTION = 0x000fffffffffffffL, SIGN = Long.MIN_VALUE;

  private FiniteMapping() {}

  static double inverse(double minimum, double maximum, double value) {
    double difference = value - minimum, span = maximum - minimum;
    double result = difference / span;
    if (Double.isFinite(difference) && Double.isFinite(span)
        || !Double.isFinite(minimum)
        || !Double.isFinite(maximum)
        || !Double.isFinite(value)
        || minimum == maximum) return result;
    var start = units(minimum);
    return ratio(units(value).subtract(start), units(maximum).subtract(start));
  }

  static double remap(
      double value, double inputMin, double inputMax, double outputMin, double outputMax) {
    double difference = value - inputMin, span = inputMax - inputMin;
    double weight = difference / span, outputSpan = outputMax - outputMin;
    double offset = weight * outputSpan, result = outputMin + offset;
    if (Double.isFinite(difference)
            && Double.isFinite(span)
            && Double.isFinite(weight)
            && Double.isFinite(outputSpan)
            && Double.isFinite(offset)
            && Double.isFinite(result)
        || !Double.isFinite(value)
        || !Double.isFinite(inputMin)
        || !Double.isFinite(inputMax)
        || !Double.isFinite(outputMin)
        || !Double.isFinite(outputMax)
        || inputMin == inputMax) return result;
    return remapExact(value, inputMin, inputMax, outputMin, outputMax);
  }

  /**
   * Validated finite endpoints and a distinct input span, for an already exceptional curve path.
   */
  static double remapExact(
      double value, double inputMin, double inputMax, double outputMin, double outputMax) {
    var start = units(inputMin);
    var denominator = units(inputMax).subtract(start);
    var output = units(outputMin);
    var numerator =
        output
            .multiply(denominator)
            .add(units(value).subtract(start).multiply(units(outputMax).subtract(output)));
    return ratio(numerator, denominator.shiftLeft(1074));
  }

  /** Preserve endpoint + offset*slope order; recover only failed finite tangent arithmetic. */
  static double tangent(double endpoint, double origin, double slope, double value) {
    double distance = value - origin, product = distance * slope;
    double result = endpoint + product;
    if (Double.isFinite(distance) && Double.isFinite(product) && Double.isFinite(result)
        || !Double.isFinite(endpoint)
        || !Double.isFinite(origin)
        || !Double.isFinite(slope)
        || !Double.isFinite(value)) return result;
    if (slope == 0) return endpoint;
    var numerator =
        units(endpoint)
            .shiftLeft(1074)
            .add(units(value).subtract(units(origin)).multiply(units(slope)));
    return ratio(numerator, BigInteger.ONE.shiftLeft(2148));
  }

  /** Every finite double is an integer multiple of 2^-1074, including signed subnormals. */
  private static BigInteger units(double value) {
    long bits = Double.doubleToRawLongBits(value);
    int exponent = (int) ((bits >>> 52) & 0x7ff);
    long mantissa = bits & FRACTION;
    var result =
        exponent == 0
            ? BigInteger.valueOf(mantissa)
            : BigInteger.valueOf(mantissa | (1L << 52)).shiftLeft(exponent - 1);
    return bits < 0 ? result.negate() : result;
  }

  /** Exactly round a nonzero-denominator binary rational to nearest, ties to even. */
  private static double ratio(BigInteger numerator, BigInteger denominator) {
    if (numerator.signum() == 0) return 0.0;
    long sign = numerator.signum() != denominator.signum() ? SIGN : 0;
    var n = numerator.abs();
    var d = denominator.abs();
    int exponent = n.bitLength() - d.bitLength();
    int comparison =
        exponent >= 0 ? n.compareTo(d.shiftLeft(exponent)) : n.shiftLeft(-exponent).compareTo(d);
    if (comparison < 0) exponent--;
    if (exponent > 1023) return Double.longBitsToDouble(sign | 0x7ff0000000000000L);
    if (exponent < -1022) {
      // Rounding can produce zero or carry into the smallest normal double (bit 52).
      long fraction = rounded(n.shiftLeft(1074), d).longValueExact();
      return Double.longBitsToDouble(sign | fraction);
    }
    int shift = 52 - exponent;
    var significand = shift >= 0 ? rounded(n.shiftLeft(shift), d) : rounded(n, d.shiftLeft(-shift));
    if (significand.bitLength() > 53) {
      significand = significand.shiftRight(1);
      exponent++;
    }
    if (exponent > 1023) return Double.longBitsToDouble(sign | 0x7ff0000000000000L);
    long fraction = significand.longValueExact() & FRACTION;
    return Double.longBitsToDouble(sign | ((long) (exponent + 1023) << 52) | fraction);
  }

  private static BigInteger rounded(BigInteger numerator, BigInteger denominator) {
    var division = numerator.divideAndRemainder(denominator);
    int half = division[1].shiftLeft(1).compareTo(denominator);
    return half > 0 || half == 0 && division[0].testBit(0)
        ? division[0].add(BigInteger.ONE)
        : division[0];
  }
}
