package io.voxelcreator.core;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class CurveWrappingTest {
  static Curve curve(double lo, double hi, String mode) {
    var c = new Curve();
    c.min = lo;
    c.max = hi;
    c.outsideDomain = mode;
    c.points.getFirst().x = lo;
    c.points.getFirst().y = 0;
    c.points.getLast().x = hi;
    c.points.getLast().y = 1;
    return c;
  }

  @Test
  void independentExactRationalCorpusAndHistoricalBits() throws Exception {
    var rows =
        ProjectIO.JSON
            .readTree(Path.of("../fixtures/curve-wrapping-vectors.json").toFile())
            .path("vectors");
    int fallback = 0, legacy = 0;
    for (var row : rows) {
      var f =
          new ScalarCurve(
              curve(
                  row.path("min").asDouble(),
                  row.path("max").asDouble(),
                  row.path("mode").asText()));
      double input = row.path("input").asDouble(),
          expected = Double.parseDouble(row.path("expectedHex").asText()),
          actual = f.sample(input);
      assertTrue(Double.isFinite(actual) && actual >= 0 && actual <= 1, row.toString());
      if (row.path("legacyValid").asBoolean()) {
        assertEquals(
            Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), row.toString());
        legacy++;
      } else {
        assertEquals(expected, actual, 2e-13, row.toString());
        fallback++;
      }
    }
    assertTrue(fallback > 100);
    assertTrue(legacy > 100);
  }

  @Test
  void exactKnotsAndHistoricalUnitSeamsRemainUnchanged() {
    var repeat = new ScalarCurve(curve(1, 2, "REPEAT"));
    assertEquals(0, repeat.sample(1));
    assertEquals(1, repeat.sample(2));
    assertEquals(0, repeat.sample(0));
    assertEquals(0, repeat.sample(3));
    assertEquals(0, repeat.sample(Double.MAX_VALUE));
    assertEquals(0, repeat.sample(-Double.MAX_VALUE));
    var mirror = new ScalarCurve(curve(1, 2, "MIRROR"));
    assertEquals(1, mirror.sample(0));
    assertEquals(0, mirror.sample(3));
  }

  @Test
  void smallestDomainsDoNotOverflowTheirWrappingQuotient() {
    for (String mode : new String[] {"REPEAT", "MIRROR"}) {
      var f = new ScalarCurve(curve(0, Double.MIN_VALUE, mode));
      assertEquals(1, f.sample(Double.MIN_VALUE));
      for (double input : new double[] {-Double.MAX_VALUE, -1, 1, Double.MAX_VALUE})
        assertEquals(0, f.sample(input));
      assertEquals(mode.equals("MIRROR") ? 1 : 0, f.sample(-Double.MIN_VALUE));
    }
  }

  @Test
  void mirrorPeriodAndRelativeOffsetMayOverflowWithoutBreakingTheMapping() {
    double lo = -8e307, hi = 8e307, length = hi - lo;
    var mirror = new ScalarCurve(curve(lo, hi, "MIRROR"));
    for (double value : new double[] {-Double.MAX_VALUE, Double.MAX_VALUE}) {
      double mapped = value > hi ? hi - (value - hi) : lo + (lo - value);
      assertEquals((mapped - lo) / length, mirror.sample(value), 2e-14);
    }
    var repeat = new ScalarCurve(curve(lo, hi, "REPEAT"));
    assertEquals((Double.MAX_VALUE - length - lo) / length, repeat.sample(Double.MAX_VALUE), 2e-14);
  }

  @Test
  void compiledSnapshotsRemainOwnedAndNonfiniteInputsStillReject() {
    var c = curve(1, 2, "REPEAT");
    var f = new ScalarCurve(c);
    double before = f.sample(Double.MAX_VALUE);
    c.outsideDomain = "CLAMP";
    c.points.getLast().y = 9;
    assertEquals(before, f.sample(Double.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> f.sample(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> f.sample(Double.POSITIVE_INFINITY));
  }
}
