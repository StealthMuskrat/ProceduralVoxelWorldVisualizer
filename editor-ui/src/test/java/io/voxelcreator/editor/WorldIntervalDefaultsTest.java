package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldIntervalDefaultsTest {
  private static final UUID TYPE = UUID.randomUUID();

  private static TypeInterval range(
      double min, double max, boolean includeMin, boolean includeMax) {
    var r = new TypeInterval();
    r.min = min;
    r.max = max;
    r.includeMin = includeMin;
    r.includeMax = includeMax;
    r.voxelTypeUuid = TYPE;
    return r;
  }

  private static TypeInterval validAddition(List<TypeInterval> original) {
    String before = ProjectIO.JSON.valueToTree(original).toString();
    var result = WorldIntervalDefaults.suggest(original, TYPE);
    assertTrue(result.available(), result.message());
    var p = new ProjectData();
    var type = new VoxelType();
    type.uuid = TYPE;
    type.id = "stone";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "layer";
    layer.defaults.typeIntervals.addAll(original);
    layer.defaults.typeIntervals.add(result.interval());
    p.worldGenerator.layers.add(layer);
    assertEquals(List.of(), Validator.validate(p));
    assertEquals(before, ProjectIO.JSON.valueToTree(original).toString());
    assertEquals(TYPE, result.interval().voxelTypeUuid);
    return result.interval();
  }

  private static boolean contains(TypeInterval r, double value) {
    return WorldSemantics.contains(value, r.min, r.max, r.includeMin, r.includeMax);
  }

  @Test
  void emptyListUsesTheExistingUnitDefaultAndRequiresAnExplicitExistingType() {
    assertFalse(WorldIntervalDefaults.suggest(List.of(), null).available());
    var r = validAddition(List.of());
    assertEquals(0, r.min);
    assertEquals(1, r.max);
    assertTrue(r.includeMin);
    assertFalse(r.includeMax);
  }

  @Test
  void reorderingNeverChangesTheNextDefaultOrMutatesExistingRows() {
    var a = range(-2, 0, true, false);
    var b = range(0, 1, true, false);
    var ascending = new ArrayList<>(List.of(a, b));
    var descending = new ArrayList<>(List.of(b, a));
    var first = validAddition(ascending);
    var second = validAddition(descending);
    assertEquals(ProjectIO.JSON.valueToTree(first), ProjectIO.JSON.valueToTree(second));
    assertEquals(1, first.min);
    assertEquals(2, first.max);
    assertEquals(List.of(a, b), ascending);
    assertEquals(List.of(b, a), descending);
    first.min = 99;
    assertEquals(1, b.max);
    assertEquals(1, second.min);
  }

  @Test
  void appendRespectsTheExistingUpperEndpointInclusivity() {
    for (boolean closed : new boolean[] {false, true}) {
      var existing = range(-1, 2, true, closed);
      var added = validAddition(List.of(existing));
      assertEquals(2, added.min);
      assertEquals(3, added.max);
      assertEquals(!closed, added.includeMin);
      assertFalse(added.includeMax);
      assertEquals(!closed, contains(added, 2));
      assertTrue(contains(added, 2.5));
      assertFalse(contains(added, 3));
    }
  }

  @Test
  void saturatedUnitArithmeticAdvancesByOneRepresentableValueAndIncludesIt() {
    for (double max : new double[] {1e20, -1e20, Math.nextDown(Double.MAX_VALUE)}) {
      var existing = range(-Double.MAX_VALUE, max, true, true);
      var added = validAddition(List.of(existing));
      assertEquals(max, added.min);
      assertEquals(Math.nextUp(max), added.max);
      assertTrue(Double.isFinite(added.max));
      assertFalse(added.includeMin);
      assertTrue(added.includeMax);
      assertFalse(contains(added, max));
      assertTrue(contains(added, added.max));
      assertFalse(contains(existing, added.max));
    }
  }

  @Test
  void finiteDomainEndpointsAndInternalSingletonGapsRemainUsable() {
    var upper = validAddition(List.of(range(-Double.MAX_VALUE, Double.MAX_VALUE, true, false)));
    assertEquals(Double.MAX_VALUE, upper.min);
    assertEquals(Double.MAX_VALUE, upper.max);
    assertTrue(contains(upper, Double.MAX_VALUE));
    var hole =
        validAddition(
            List.of(
                range(0, Double.MAX_VALUE, false, true), range(-Double.MAX_VALUE, 0, true, false)));
    assertEquals(0, hole.min);
    assertEquals(0, hole.max);
    assertTrue(contains(hole, 0));
    assertTrue(contains(hole, -0.0));
    var lower = validAddition(List.of(range(-Double.MAX_VALUE, Double.MAX_VALUE, false, true)));
    assertEquals(-Double.MAX_VALUE, lower.min);
    assertEquals(-Double.MAX_VALUE, lower.max);
    assertTrue(contains(lower, -Double.MAX_VALUE));
  }

  @Test
  void exhaustedUpperSpaceUsesTheFirstInternalGapThenALowerPrefix() {
    var hole =
        validAddition(
            List.of(
                range(8, Double.MAX_VALUE, true, true), range(-Double.MAX_VALUE, 4, true, true)));
    assertEquals(4, hole.min);
    assertEquals(5, hole.max);
    assertFalse(hole.includeMin);
    assertFalse(hole.includeMax);
    assertTrue(contains(hole, 4.5));
    var lower = validAddition(List.of(range(-1, Double.MAX_VALUE, true, true)));
    assertEquals(-2, lower.min);
    assertEquals(-1, lower.max);
    assertTrue(lower.includeMin);
    assertFalse(lower.includeMax);
  }

  @Test
  void fullFiniteCoverageAndGapsWithoutRepresentableValuesDoNotInventAnInterval() {
    assertFalse(
        WorldIntervalDefaults.suggest(
                List.of(range(-Double.MAX_VALUE, Double.MAX_VALUE, true, true)), TYPE)
            .available());
    assertFalse(
        WorldIntervalDefaults.suggest(
                List.of(
                    range(-Double.MAX_VALUE, 0, true, true),
                    range(Double.MIN_VALUE, Double.MAX_VALUE, true, true)),
                TYPE)
            .available());
  }

  @Test
  void invalidRangesAndCapsAreUnavailableWithoutRepairingThePrivateValues() {
    for (var rows :
        List.of(
            List.of(range(0, 0, true, false)),
            List.of(range(2, 1, true, true)),
            List.of(range(0, Double.POSITIVE_INFINITY, true, true)),
            List.of(range(Double.NaN, 1, true, true)),
            List.of(range(0, 1, true, true), range(1, 2, true, true)),
            List.of(range(0, 2, true, false), range(1, 3, true, false)))) {
      String before = ProjectIO.JSON.valueToTree(rows).toString();
      assertFalse(WorldIntervalDefaults.suggest(rows, TYPE).available());
      assertEquals(before, ProjectIO.JSON.valueToTree(rows).toString());
    }
    var capped = new ArrayList<TypeInterval>();
    for (int i = 0; i < 256; i++) capped.add(range(i, i + 1, true, false));
    assertFalse(WorldIntervalDefaults.suggest(capped, TYPE).available());
    assertFalse(
        WorldIntervalDefaults.suggest(Arrays.asList((TypeInterval) null), TYPE).available());
  }

  @Test
  void signedZeroSingletonAndOpenRangeSortNumericallyWithoutChangingStoredSigns() {
    var open = range(-0.0, 1, false, false);
    var point = range(0.0, 0.0, true, true);
    var added = validAddition(List.of(open, point));
    assertEquals(1, added.min);
    assertEquals(2, added.max);
    assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(open.min));
    assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(point.min));
  }
}
