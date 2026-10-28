package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import org.junit.jupiter.api.Test;

class CurveNavigationTest {
  private final Noise noise = new Noise();
  private final Item item = ProcessingRegistry.create("CURVE", "response");
  private final Curve curve = new Curve();

  CurveNavigationTest() {
    curve.points.add(1, new Point(.5, .5));
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    noise.processing.add(item);
  }

  private EditorEvents.Target target(UUID control, String parameter, String path) {
    return new EditorEvents.Target(UUID.randomUUID(), noise.uuid, control, parameter, path, false);
  }

  @Test
  void historicalPointIndicesNeverChooseTheCurrentPoint() {
    var point = curve.points.get(1).uuid;
    var route =
        CurveNavigation.resolve(
            noise,
            target(point, "curve", "/noise2D/99/processing/888/parameters/curve/points/999/x"));
    assertSame(item, route.item());
    assertEquals(point, route.point());
    assertEquals("x", route.field());
    // With only the item identity, the historical point index has no authority.
    route =
        CurveNavigation.resolve(
            noise,
            target(item.uuid, "curve", "/noise2D/99/processing/888/parameters/curve/points/1/y"));
    assertNull(route.point());
    assertEquals("min", route.field());
  }

  @Test
  void explicitKeysBeatContradictoryPathsAndRecognizeAllControls() {
    var point = curve.points.get(1).uuid;
    for (var key : CurveNavigation.POINT_FIELDS)
      assertEquals(
          key,
          CurveNavigation.resolve(
                  noise,
                  target(
                      point,
                      "parameters/curve/" + key,
                      "/noise2D/0/processing/0/parameters/curve/points/1/y"))
              .field());
    for (var key : CurveNavigation.CURVE_FIELDS)
      assertEquals(
          key, CurveNavigation.resolve(noise, target(item.uuid, "curve/" + key, "")).field());
    assertEquals(
        "displayMax",
        CurveNavigation.resolve(
                noise,
                target(item.uuid, "curve", "/noise3D/77/control/91/parameters/curve/displayMax"))
            .field());
  }

  @Test
  void ordinaryItemInputAndUnrelatedPathsDoNotInventPointDestinations() {
    assertNull(CurveNavigation.resolve(noise, target(item.uuid, null, "")));
    assertNull(
        CurveNavigation.resolve(
            noise, target(item.uuid, null, "/voxelTypes/0/parameters/curve/min")));
    assertNull(
        CurveNavigation.resolve(
            noise, target(item.uuid, null, "/noise2D/0/processing/0/parameters/curve-other/min")));
    assertNull(CurveNavigation.resolve(noise, target(item.uuid, "inputs/a", "")));
    assertNull(CurveNavigation.resolve(noise, target(item.uuid, "value", "")));
    assertEquals(
        "y",
        CurveNavigation.resolve(
                noise,
                target(
                    curve.points.getLast().uuid,
                    "curve",
                    "/voxelTypes/0/parameters/curve/points/9/x"))
            .field());
  }

  @Test
  void missingForeignAndRemovedControlsAreUnavailable() {
    assertNull(CurveNavigation.resolve(noise, target(UUID.randomUUID(), "curve", "")));
    assertNull(
        CurveNavigation.resolve(
            noise,
            new EditorEvents.Target(
                UUID.randomUUID(), UUID.randomUUID(), item.uuid, "curve", "", false)));
    var removed = curve.points.remove(1).uuid;
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    assertNull(CurveNavigation.resolve(noise, target(removed, "curve", "")));
    item.kind = "EXPRESSION";
    assertNull(CurveNavigation.resolve(noise, target(item.uuid, "curve", "")));
  }
}
