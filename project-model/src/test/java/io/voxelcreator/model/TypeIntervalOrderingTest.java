package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TypeIntervalOrderingTest {
  private static ProjectData project(
      double rangeZero, double pointZero, boolean closed, boolean reverse) {
    var p = new ProjectData();
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "layer";
    p.worldGenerator.layers.add(layer);
    var range = new TypeInterval();
    range.min = rangeZero;
    range.max = 1;
    range.includeMin = closed;
    range.voxelTypeUuid = type.uuid;
    var point = new TypeInterval();
    point.min = point.max = pointZero;
    point.includeMin = point.includeMax = true;
    point.voxelTypeUuid = type.uuid;
    layer.defaults.typeIntervals.addAll(reverse ? List.of(point, range) : List.of(range, point));
    return p;
  }

  @Test
  void openZeroBoundaryAndSingletonAreValidInEitherOrderAndBothSignedRepresentations() {
    for (double rangeZero : new double[] {-0.0, 0.0})
      for (double pointZero : new double[] {-0.0, 0.0})
        for (boolean reverse : new boolean[] {false, true}) {
          var p = project(rangeZero, pointZero, false, reverse);
          String before = ProjectIO.JSON.valueToTree(p).toString();
          assertEquals(List.of(), Validator.validate(p));
          var rows = p.worldGenerator.layers.getFirst().defaults.typeIntervals;
          var point = rows.get(reverse ? 0 : 1);
          var range = rows.get(reverse ? 1 : 0);
          assertTrue(WorldSemantics.compareTypeIntervals(point, range) < 0);
          assertFalse(
              WorldSemantics.contains(
                  0.0, range.min, range.max, range.includeMin, range.includeMax));
          assertTrue(
              WorldSemantics.contains(
                  -0.0, point.min, point.max, point.includeMin, point.includeMax));
          assertEquals(before, ProjectIO.JSON.valueToTree(p).toString());
        }
  }

  @Test
  void closedZeroBoundaryStillRejectsOverlapWithoutNormalizingTheAuthoredBits() {
    for (double rangeZero : new double[] {-0.0, 0.0})
      for (double pointZero : new double[] {-0.0, 0.0})
        for (boolean reverse : new boolean[] {false, true}) {
          var p = project(rangeZero, pointZero, true, reverse);
          String before = ProjectIO.JSON.valueToTree(p).toString();
          assertTrue(
              Validator.validate(p).stream()
                  .anyMatch(d -> d.code().equals("OVERLAPPING_INTERVAL")));
          assertEquals(before, ProjectIO.JSON.valueToTree(p).toString());
        }
  }
}
