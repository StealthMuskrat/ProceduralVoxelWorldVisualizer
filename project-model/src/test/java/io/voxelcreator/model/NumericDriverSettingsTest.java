package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NumericDriverSettingsTest {
  @Test
  void arbitraryMappedEndpointsClampAndInterpolateInEitherDirection() {
    var d = new NumericDriver();
    d.inputMin = -2;
    d.inputMax = 2;
    d.outputMin = -10;
    d.outputMax = 30;
    assertEquals(-10, WorldSemantics.mapNumeric(d, -3));
    assertEquals(30, WorldSemantics.mapNumeric(d, 3));
    assertEquals(0, WorldSemantics.mapNumeric(d, -1));
    assertEquals(10, WorldSemantics.mapNumeric(d, 0));
    d.mapping = "SMOOTHSTEP";
    assertEquals(-3.75, WorldSemantics.mapNumeric(d, -1));
    d.outputMin = 30;
    d.outputMax = -10;
    assertEquals(23.75, WorldSemantics.mapNumeric(d, -1));
    d.outputMin = d.outputMax = -7;
    assertEquals(-7, WorldSemantics.mapNumeric(d, 0));
  }

  @Test
  void extremeFiniteEndpointsNeverProduceInfiniteInterpolation() {
    var d = new NumericDriver();
    d.inputMin = -Double.MAX_VALUE;
    d.inputMax = Double.MAX_VALUE;
    d.outputMin = -Double.MAX_VALUE;
    d.outputMax = Double.MAX_VALUE;
    assertEquals(0, WorldSemantics.mapNumeric(d, 0));
    assertEquals(-Double.MAX_VALUE, WorldSemantics.mapNumeric(d, -Double.MAX_VALUE));
    assertEquals(Double.MAX_VALUE, WorldSemantics.mapNumeric(d, Double.MAX_VALUE));
    for (double x : new double[] {-.75, -.1, .1, .75}) {
      double value = WorldSemantics.mapNumeric(d, x * Double.MAX_VALUE);
      assertTrue(Double.isFinite(value));
      assertEquals(x, value / Double.MAX_VALUE, 3e-16);
    }
    d.outputMin = d.outputMax = Double.MAX_VALUE;
    assertEquals(Double.MAX_VALUE, WorldSemantics.mapNumeric(d, 1));
    assertThrows(IllegalArgumentException.class, () -> WorldSemantics.mapNumeric(d, Double.NaN));
  }

  @Test
  void atomicListInheritanceDistinguishesAbsentEmptyAndExplicitZero() {
    var l = new Layer();
    var op = new Operation();
    var d = new NumericDriver();
    d.outputMin = d.outputMax = 0;
    l.defaults.parameterDrivers.add(d);
    assertEquals(1, SettingsResolver.resolve(l, op).parameterDrivers.size());
    l.defaults.parameterDrivers.getFirst().outputMax = 12;
    assertEquals(12, SettingsResolver.resolve(l, op).parameterDrivers.getFirst().outputMax);
    op.overrides.put("parameterDrivers", ProjectIO.JSON.createArrayNode());
    assertTrue(SettingsResolver.resolve(l, op).parameterDrivers.isEmpty());
    op.overrides.remove("parameterDrivers");
    assertEquals(1, SettingsResolver.resolve(l, op).parameterDrivers.size());
    var resolved = SettingsResolver.resolve(l, op);
    resolved.parameterDrivers.clear();
    assertEquals(1, l.defaults.parameterDrivers.size());
  }

  @Test
  void semanticValidationRejectsBadTargetsCombinationsRangesReferencesAndLimits() {
    var p = new ProjectData();
    var l = new Layer();
    l.id = "l";
    p.worldGenerator.layers.add(l);
    var d = new NumericDriver();
    l.defaults.parameterDrivers.add(d);
    d.destination = "seedOffset";
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_DRIVER_TARGET")));
    d.destination = "threshold";
    d.combination = "DIVIDE";
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_COMBINATION")));
    d.combination = "REPLACE";
    d.inputMax = d.inputMin;
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_DRIVER_RANGE")));
    d.inputMax = 1;
    d.noiseGeneratorUuid = UUID.randomUUID();
    assertTrue(Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_REFERENCE")));
    d.noiseGeneratorUuid = null;
    d.outputMin = -100;
    d.outputMax = 100;
    assertTrue(Validator.validate(p).isEmpty());
    d.outputMax = Double.POSITIVE_INFINITY;
    assertTrue(Validator.validate(p).stream().anyMatch(x -> x.code().equals("NONFINITE")));
    d.outputMax = 100;
    for (int i = 0; i < 32; i++) l.defaults.parameterDrivers.add(new NumericDriver());
    assertTrue(Validator.validate(p).stream().anyMatch(x -> x.code().equals("COMPLEXITY_LIMIT")));
  }

  @Test
  void numericRowsCannotMasqueradeAsUnitProbabilityDrivers() {
    var p = new ProjectData();
    var l = new Layer();
    l.id = "layer";
    p.worldGenerator.layers.add(l);
    l.defaults.probabilityDriver = new NumericDriver();
    assertTrue(
        Validator.validate(p).stream().anyMatch(d -> d.code().equals("INVALID_DRIVER_SHAPE")));
  }

  @Test
  void unitProbabilityDriverRangeRemainsRestricted() {
    var p = new ProjectData();
    var l = new Layer();
    l.id = "l";
    p.worldGenerator.layers.add(l);
    l.defaults.probabilityDriver.outputMax = 2;
    assertTrue(
        Validator.validate(p).stream().anyMatch(x -> x.code().equals("INVALID_PROBABILITY")));
    assertEquals(
        List.of(
            "threshold",
            "baseHeight",
            "heightScale",
            "strength",
            "probability",
            "scaleX",
            "scaleY",
            "scaleZ",
            "offsetX",
            "offsetY",
            "offsetZ",
            "transformX",
            "transformY",
            "transformZ",
            "transformPivotX",
            "transformPivotY",
            "transformPivotZ"),
        WorldSemantics.PARAMETER_TARGETS);
    assertFalse(WorldSemantics.parameterUsed("ALL", "threshold"));
    assertTrue(WorldSemantics.parameterUsed("HEIGHT_BOUNDARY", "heightScale"));
  }
}
