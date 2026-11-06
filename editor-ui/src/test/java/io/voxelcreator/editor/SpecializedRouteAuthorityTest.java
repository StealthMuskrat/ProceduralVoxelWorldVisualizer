package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred definitions for the specialized consumers of diagnostic pointer hints. */
class SpecializedRouteAuthorityTest {
  private final UUID project = UUID.randomUUID();
  private final Noise noise = new Noise();
  private final Item item = ProcessingRegistry.create("CURVE", "response");
  private final Curve curve = new Curve();
  private final Layer layer = new Layer();
  private final Operation operation = new Operation();

  SpecializedRouteAuthorityTest() {
    curve.points.add(1, new Point(.5, .5));
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    noise.processing.add(item);
    layer.operations.add(operation);
  }

  private EditorEvents.Target noiseTarget(UUID control, String key, String path) {
    return new EditorEvents.Target(project, noise.uuid, control, key, path, false);
  }

  private WorldSettingNavigation.Route world(Object draft, String key, String path) {
    return WorldSettingNavigation.resolve(
        draft,
        layer,
        new EditorEvents.Target(project, ((Identity) draft).uuid, null, key, path, false));
  }

  private List<String> badTokens() {
    return List.of(
        "+0", "00", "01", "-0", "-1", "2147483648", "1-1-1-1-1", "",
        UUID.randomUUID().toString());
  }

  @Test
  void curveHintsAcceptOnlyOwnedFullUuidsOrHistoricalCanonicalIndices() {
    var before = ProjectIO.JSON.valueToTree(noise);
    UUID point = curve.points.get(1).uuid;
    for (String root : List.of("noise2D", "noise3D", "noise"))
      for (String owner : List.of("0", "999", noise.uuid.toString().toUpperCase(Locale.ROOT)))
        for (String collection : List.of("processing", "control"))
          for (String itemToken :
              List.of("0", "999", item.uuid.toString().toUpperCase(Locale.ROOT))) {
            String base =
                "/" + root + "/" + owner + "/" + collection + "/" + itemToken + "/parameters/curve";
            assertEquals(
                "displayMax",
                CurveNavigation.resolve(
                        noise, noiseTarget(item.uuid, "curve", base + "/displayMax"))
                    .field());
            for (String pointToken :
                List.of("0", "999", point.toString().toUpperCase(Locale.ROOT))) {
              var route =
                  CurveNavigation.resolve(
                      noise, noiseTarget(point, "curve", base + "/points/" + pointToken + "/x"));
              assertSame(item, route.item());
              assertEquals(point, route.point());
              assertEquals("x", route.field());
            }
          }
    assertEquals(before, ProjectIO.JSON.valueToTree(noise));
  }

  @Test
  void foreignAndMalformedCurveResourceItemAndPointTokensContributeNoLeaf() {
    UUID point = curve.points.get(1).uuid;
    for (String token : badTokens()) {
      for (String path :
          List.of(
              "/noise2D/" + token + "/processing/0/parameters/curve/displayMax",
              "/noise2D/0/control/" + token + "/parameters/curve/displayMax")) {
        assertNull(CurveNavigation.resolve(noise, noiseTarget(item.uuid, null, path)), path);
        assertEquals(
            "min",
            CurveNavigation.resolve(noise, noiseTarget(item.uuid, "curve", path)).field(),
            path);
      }
      for (String path :
          List.of(
              "/noise/" + token + "/control/0/parameters/curve/points/0/x",
              "/noise/0/control/" + token + "/parameters/curve/points/0/x",
              "/noise/0/control/0/parameters/curve/points/" + token + "/x"))
        assertEquals(
            "y", CurveNavigation.resolve(noise, noiseTarget(point, "curve", path)).field(), path);
    }
  }

  @Test
  void explicitCurveParentsAndFieldsCannotBeRefinedByPointerText() {
    UUID point = curve.points.get(1).uuid;
    String path = "/noise2D/0/processing/0/parameters/curve";
    assertEquals(
        "min",
        CurveNavigation.resolve(noise, noiseTarget(item.uuid, "parameters/curve", path + "/max"))
            .field());
    assertEquals(
        "y",
        CurveNavigation.resolve(
                noise, noiseTarget(point, "parameters/curve", path + "/points/1/x"))
            .field());
    for (String key : CurveNavigation.CURVE_FIELDS)
      assertEquals(
          key,
          CurveNavigation.resolve(
                  noise, noiseTarget(item.uuid, "parameters/curve/" + key, path + "/max"))
              .field());
    for (String key : CurveNavigation.POINT_FIELDS)
      assertEquals(
          key,
          CurveNavigation.resolve(noise, noiseTarget(point, "curve/" + key, path + "/points/1/y"))
              .field());
    for (String unrelated :
        List.of(
            "noise2D/0/processing/0/parameters/curve/max",
            "/runtime/noise2D/0/processing/0/parameters/curve/max",
            "/noise2D/0/parameters/processing/0/parameters/curve/max"))
      assertNull(CurveNavigation.resolve(noise, noiseTarget(item.uuid, null, unrelated)));
  }

  @Test
  void curveInsertionAndRemovalUseExactPointIdentityWithoutMutatingTheDraft() {
    UUID point = curve.points.get(1).uuid;
    var target =
        noiseTarget(point, "curve", "/noise2D/0/processing/0/parameters/curve/points/1/x");
    noise.processing.addFirst(ProcessingRegistry.create("ADD", "preceding"));
    curve.points.add(1, new Point(.25, .25));
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    var before = ProjectIO.JSON.valueToTree(noise);
    var route = CurveNavigation.resolve(noise, target);
    assertSame(item, route.item());
    assertEquals(point, route.point());
    var focus = new CurveControlFocus();
    focus.request(route.point(), route.field());
    assertEquals("x", focus.destination(curve).key());
    assertEquals(before, ProjectIO.JSON.valueToTree(noise));
    curve.points.removeIf(p -> p.uuid.equals(point));
    item.parameters.put("curve", ProjectIO.JSON.valueToTree(curve));
    assertNull(CurveNavigation.resolve(noise, target));
    assertNull(focus.destination(curve));
  }

  @Test
  void qualifiedWorldParentsAndLeavesRemainExactAcrossAllAliases() {
    operation.overrides.put("probabilityDriver", ProjectIO.JSON.valueToTree(new FieldDriver()));
    for (Object draft : List.of(layer, operation))
      for (String prefix : List.of("settings/", "defaults/", "overrides/")) {
        String path =
            draft == layer
                ? "/worldGenerator/layers/0/defaults/probabilityDriver/outputMax"
                : "/worldGenerator/layers/0/operations/0/overrides/probabilityDriver/outputMax";
        assertEquals(
            "settings/probabilityDriver",
            world(draft, prefix + "probabilityDriver", path).control());
        assertEquals(
            "settings/probabilityDriver/inputMin",
            world(draft, prefix + "probabilityDriver/inputMin", path).control());
      }
  }

  @Test
  void worldHintsAcceptMatchingLayerAndPlacementUuidsWithoutReplayingIndices() {
    operation.overrides.put("probabilityDriver", ProjectIO.JSON.valueToTree(new FieldDriver()));
    for (String owner : List.of("0", "999", layer.uuid.toString().toUpperCase(Locale.ROOT))) {
      String base = "/worldGenerator/layers/" + owner;
      assertEquals(
          "settings/probabilityDriver/outputMax",
          world(layer, null, base + "/defaults/probabilityDriver/outputMax").control());
      for (String placement :
          List.of("0", "999", operation.uuid.toString().toUpperCase(Locale.ROOT)))
        assertEquals(
            "settings/probabilityDriver/outputMax",
            world(
                    operation,
                    "outputMax",
                    base + "/operations/" + placement + "/overrides/probabilityDriver/outputMax")
                .control());
    }
  }

  @Test
  void foreignAndMalformedWorldLayerAndPlacementTokensCannotRefineRawKeys() {
    for (String token : badTokens()) {
      assertNull(
          world(
              layer,
              "outputMax",
              "/worldGenerator/layers/" + token + "/defaults/probabilityDriver/outputMax"));
      for (String path :
          List.of(
              "/worldGenerator/layers/" + token + "/operations/0/overrides/strengthDriver/enabled",
              "/worldGenerator/layers/0/operations/"
                  + token
                  + "/overrides/strengthDriver/enabled")) {
        assertNull(world(operation, null, path), path);
        assertEquals("enabled", world(operation, "enabled", path).control(), path);
        assertTrue(operation.overrides.isEmpty());
      }
    }
  }

  @Test
  void worldRoutesRequireTheExactDraftResourceAndAnOrdinaryResourceTarget() {
    for (Object draft : List.of(layer, operation)) {
      UUID id = ((Identity) draft).uuid;
      for (var target :
          List.of(
              new EditorEvents.Target(
                  project, UUID.randomUUID(), null, "settings/threshold", "", false),
              new EditorEvents.Target(project, null, null, "settings/threshold", "", false),
              new EditorEvents.Target(
                  project, id, UUID.randomUUID(), "settings/threshold", "", false),
              new EditorEvents.Target(project, id, null, "settings/threshold", "", true)))
        assertNull(WorldSettingNavigation.resolve(draft, layer, target));
    }
    assertNull(
        WorldSettingNavigation.resolve(
            operation,
            null,
            new EditorEvents.Target(
                project,
                operation.uuid,
                null,
                null,
                "/worldGenerator/layers/0/operations/0/overrides/threshold",
                false)));
  }

  @Test
  void inheritedAndConditionalFallbacksRemainExplicitAndNeverMaterializeValues() {
    var before = ProjectIO.JSON.valueToTree(layer);
    String pointer = "/worldGenerator/layers/0/operations/0/overrides/strengthDriver/enabled";
    assertEquals(
        "override/strengthDriver", world(operation, "settings/strengthDriver", pointer).control());
    assertEquals("override/threshold", world(operation, "settings/threshold", pointer).control());
    assertEquals(
        "settings/voxelStatePredicate/kind",
        world(layer, "settings/voxelStatePredicate/reference", pointer).control());
    assertEquals(
        "settings/fieldGates/kind",
        world(layer, "settings/fieldGates/threshold", pointer).control());
    assertEquals(before, ProjectIO.JSON.valueToTree(layer));
    operation.overrides.put("threshold", ProjectIO.JSON.valueToTree(0));
    operation.overrides.put("probability", ProjectIO.JSON.valueToTree(0));
    var overridden = ProjectIO.JSON.valueToTree(layer);
    assertEquals("settings/threshold", world(operation, "settings/threshold", pointer).control());
    assertEquals(
        "settings/probability", world(operation, "settings/probability", pointer).control());
    assertEquals(overridden, ProjectIO.JSON.valueToTree(layer));
  }

  @Test
  void worldLegacyLeavesAndCollectionHeadersRemainDistinctFromNestedRows() {
    String base = "/worldGenerator/layers/999/defaults/";
    assertEquals(
        "settings/strengthDriver/enabled",
        world(layer, "enabled", base + "strengthDriver/enabled").control());
    assertEquals(
        "settings/probabilityDriver/outputMax",
        world(layer, "probabilityDriver", base + "probabilityDriver/outputMax").control());
    assertEquals(
        "settings/fieldGates",
        world(layer, null, base + "fieldGates/children/999/threshold").control());
    for (String path :
        List.of(
            "worldGenerator/layers/0/defaults/probabilityDriver/outputMax",
            "/runtime/worldGenerator/layers/0/defaults/probabilityDriver/outputMax",
            "/worldGenerator/layers/0/defaults/children/0/probabilityDriver/outputMax"))
      assertNull(world(layer, "outputMax", path), path);
  }

  @Test
  void pinPointerHintsRequireTheirResourceWhileLeavingRowIdentityUnresolved() {
    var before = ProjectIO.JSON.valueToTree(noise);
    for (String root : List.of("noise2D", "noise3D", "noise"))
      for (String owner : List.of("999", noise.uuid.toString().toUpperCase(Locale.ROOT)))
        assertEquals(
            "pins",
            PinBrowser.navigation(
                noiseTarget(
                    null, "label", "/" + root + "/" + owner + "/pinnedControls/777/label")));
    for (String token : badTokens())
      assertNull(
          PinBrowser.navigation(
              noiseTarget(null, "label", "/noise2D/" + token + "/pinnedControls/0/label")));
    assertEquals(before, ProjectIO.JSON.valueToTree(noise));
  }

  @Test
  void explicitPinKeysWinAndUnrelatedKeysCannotAcquirePinRoutes() {
    String foreign = "/noise/" + UUID.randomUUID() + "/pinnedControls/0/label";
    assertEquals("pins", PinBrowser.navigation(noiseTarget(null, "pinnedControls", foreign)));
    assertEquals("pin/group", PinBrowser.navigation(noiseTarget(item.uuid, "pin/group", foreign)));
    assertEquals("pin/label", PinBrowser.navigation(noiseTarget(item.uuid, "pin/label", foreign)));
    assertNull(PinBrowser.navigation(noiseTarget(null, "parameters/label", foreign)));
    assertNull(PinBrowser.navigation(noiseTarget(item.uuid, "label", foreign)));
    assertNull(
        PinBrowser.navigation(
            new EditorEvents.Target(
                project, null, null, "label", "/noise/0/pinnedControls/0/label", false)));
    assertNull(
        PinBrowser.navigation(noiseTarget(null, "label", "noise2D/0/pinnedControls/0/label")));
  }
}
