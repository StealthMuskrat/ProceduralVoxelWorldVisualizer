package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorldSettingNavigationTest {
  private final Layer layer = new Layer();
  private final Operation operation = new Operation();

  private EditorEvents.Target target(String parameter, String path) {
    return new EditorEvents.Target(UUID.randomUUID(), layer.uuid, null, parameter, path, false);
  }

  private WorldSettingNavigation.Route route(Object draft, String parameter, String path) {
    return WorldSettingNavigation.resolve(draft, layer, target(parameter, path));
  }

  @Test
  void everyTopLevelPropertyHasALayerDestinationAndAnExplicitInheritedOverrideDestination() {
    var before = ProjectIO.JSON.valueToTree(operation);
    for (var key : SettingsResolver.PROPERTIES) {
      assertEquals("settings/" + key, route(layer, "settings/" + key, "").control());
      var inherited = route(operation, "settings/" + key, "");
      assertEquals("override/" + key, inherited.control());
      assertEquals(key, inherited.property());
      assertFalse(inherited.explanation().isEmpty());
    }
    assertEquals(before, ProjectIO.JSON.valueToTree(operation));
  }

  @Test
  void explicitZeroAndFalseStayOverriddenAndDirectFieldsRemainSeparate() {
    operation.overrides.put("threshold", ProjectIO.JSON.valueToTree(0));
    assertEquals("settings/threshold", route(operation, "threshold", "").control());
    assertEquals("enabled", route(operation, "enabled", "").control());
    assertEquals("interpretation", route(operation, "interpretation", "").control());
    assertEquals("noiseGeneratorUuid", route(operation, "noiseGeneratorUuid", "").control());
    assertEquals("enabled", route(layer, "enabled", "").control());
    assertNull(route(layer, "interpretation", ""));
    operation.overrides.put("probabilityDriver", ProjectIO.JSON.valueToTree(new FieldDriver()));
    assertEquals(
        "settings/probabilityDriver/enabled",
        route(operation, "settings/probabilityDriver/enabled", "").control());
    assertFalse(operation.overrides.get("probabilityDriver").path("enabled").asBoolean());
  }

  @Test
  void currentOwnerUsesOnlyRecognizedStructuralSuffixesAndQualifiedKeysWin() {
    assertEquals(
        "settings/probabilityDriver/inputMax",
        route(layer, "inputMax", "/worldGenerator/layers/999/defaults/probabilityDriver/inputMax")
            .control());
    assertEquals(
        "settings/strengthDriver/enabled",
        route(layer, "enabled", "/worldGenerator/layers/999/defaults/strengthDriver/enabled")
            .control());
    assertEquals(
        "settings/strengthDriver/noiseGeneratorUuid",
        route(
                layer,
                "noiseGeneratorUuid",
                "/worldGenerator/layers/999/defaults/strengthDriver/noiseGeneratorUuid")
            .control());
    assertEquals(
        "settings/threshold",
        route(
                layer,
                "settings/threshold",
                "/worldGenerator/layers/999/defaults/probabilityDriver/inputMax")
            .control());
    operation.overrides.put("probabilityDriver", ProjectIO.JSON.valueToTree(new FieldDriver()));
    assertEquals(
        "settings/probabilityDriver/outputMax",
        route(
                operation,
                "outputMax",
                "/worldGenerator/layers/999/operations/777/overrides/probabilityDriver/outputMax")
            .control());
    assertNull(route(layer, "inputMax", "/noise2D/0/parameters/probabilityDriver/inputMax"));
  }

  @Test
  void positionalCollectionsAndNestedConditionsRevealTheirContainerWithoutChoosingARow() {
    for (var key :
        List.of(
            "samplingTransforms",
            "parameterDrivers",
            "typeIntervals",
            "fieldGates",
            "voxelStatePredicate")) {
      assertEquals(
          "settings/" + key,
          route(layer, null, "/worldGenerator/layers/2/defaults/" + key + "/children/999/threshold")
              .control());
      assertEquals("settings/" + key, route(layer, "settings/" + key + "/999/x", "").control());
    }
  }

  @Test
  void singletonDriverLeavesAndConditionalGateFieldsFocusOnlyVisibleControls() {
    for (var field :
        List.of(
            "enabled",
            "noiseGeneratorUuid",
            "mapping",
            "inputMin",
            "inputMax",
            "outputMin",
            "outputMax"))
      assertEquals(
          "settings/strengthDriver/" + field,
          route(layer, "settings/strengthDriver/" + field, "").control());
    assertEquals(
        "settings/fieldGates/kind", route(layer, "settings/fieldGates/threshold", "").control());
    layer.defaults.fieldGates.kind = "COMPARE";
    assertEquals(
        "settings/fieldGates/threshold",
        route(layer, "settings/fieldGates/threshold", "").control());
    assertEquals(
        "settings/fieldGates/kind", route(layer, "settings/fieldGates/probability", "").control());
    layer.defaults.fieldGates.kind = "INTERVAL";
    assertEquals(
        "settings/fieldGates/includeMax",
        route(layer, "settings/fieldGates/includeMax", "").control());
    layer.defaults.fieldGates.kind = "PROBABILITY";
    assertEquals(
        "settings/fieldGates/seedOffset",
        route(layer, "settings/fieldGates/seedOffset", "").control());
  }

  @Test
  void predicateAndIntervalTypeLinksRevealTheirEditableChoiceWithoutChangingIt() {
    assertEquals(
        "settings/voxelStatePredicate/kind",
        route(layer, "settings/voxelStatePredicate/reference", "").control());
    layer.defaults.voxelStatePredicate.kind = "IN_SET";
    assertEquals(
        "settings/voxelStatePredicate/reference",
        route(layer, "settings/voxelStatePredicate/reference", "").control());
    layer.defaults.typeSource = "INTERVALS";
    assertEquals("settings/typeSource", route(layer, "settings/voxelTypeUuid", "").control());
    operation.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(UUID.randomUUID()));
    assertEquals("override/typeSource", route(operation, "settings/voxelTypeUuid", "").control());
    assertFalse(operation.overrides.containsKey("typeSource"));
  }

  @Test
  void malformedOverrideRetainsItsExplicitDisableControl() {
    operation.overrides.put("threshold", ProjectIO.JSON.nullNode());
    assertEquals("override/threshold", route(operation, "settings/threshold", "").control());
    assertTrue(operation.overrides.get("threshold").isNull());
  }

  @Test
  void unrelatedDraftsControlsAndUnknownSettingsDoNotAcquireRoutes() {
    assertNull(route(new Noise(), "settings/threshold", ""));
    assertNull(route(layer, "settings/unknown", ""));
    assertNull(route(layer, "value", ""));
    assertNull(
        WorldSettingNavigation.resolve(
            layer,
            null,
            new EditorEvents.Target(
                UUID.randomUUID(),
                layer.uuid,
                UUID.randomUUID(),
                "settings/threshold",
                "",
                false)));
  }
}
