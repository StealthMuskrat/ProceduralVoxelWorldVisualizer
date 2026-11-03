package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldControlMetadataTest {
  @TempDir Path directory;

  private static Path root() {
    for (var path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
      if (Files.isDirectory(path.resolve("fixtures"))) return path;
    throw new AssertionError("Repository fixtures unavailable");
  }

  @Test
  void everyFixedInheritablePropertyHasOneCanonicalPresentationTopic() {
    var metadata = AppMetadata.defaults();
    var ids = new HashSet<String>();
    for (var definition : WorldControlMetadata.DEFINITIONS) {
      assertTrue(ids.add(definition.id()));
      assertEquals(definition.tooltip(), metadata.control(definition.id()).tooltip);
      assertFalse(definition.tooltip().isBlank());
      assertTrue(definition.tooltip().length() <= 1024);
      assertTrue(definition.description().length() <= 8192);
    }
    assertEquals(32, ids.size());
    for (String property : SettingsResolver.PROPERTIES)
      assertEquals("world.setting." + property, WorldControlMetadata.topic("settings/" + property));
    metadata.validate();
  }

  @Test
  void nestedRowsAndRecursiveConditionsKeepTheirSettingFamilyAfterReorder() {
    for (String path :
        List.of(
            "samplingTransforms/0/x",
            "samplingTransforms/63/pivotZ",
            "parameterDrivers/0/inputMin",
            "parameterDrivers/31/transformIndex",
            "typeIntervals/255/max",
            "probabilityDriver/outputMax",
            "strengthDriver/enabled",
            "fieldGates/children/0/children/2/seedOffset",
            "voxelStatePredicate/children/3/children/1/reference")) {
      String family = path.substring(0, path.indexOf('/'));
      assertEquals("world.setting." + family, WorldControlMetadata.topic("settings/" + path));
    }
    assertEquals(
        WorldControlMetadata.setting("samplingTransforms/0", "x"),
        WorldControlMetadata.setting("samplingTransforms/12", "x"));
  }

  @Test
  void overrideResetAndPlacementControlsRouteToReadOnlySemanticGuidance() {
    for (String property : SettingsResolver.PROPERTIES) {
      assertEquals("world.inheritance", WorldControlMetadata.topic("override/" + property));
      assertEquals("world.inheritance", WorldControlMetadata.topic("reset/" + property));
    }
    assertEquals("world.enabled", WorldControlMetadata.topic("enabled"));
    assertEquals("world.source", WorldControlMetadata.topic("noiseGeneratorUuid"));
    assertEquals("world.interpretation", WorldControlMetadata.topic("interpretation"));
  }

  @Test
  void unknownScopesDoNotInventMetadataWhileFamilyGuidanceDoesNotPretendToNavigate() {
    assertNull(WorldControlMetadata.topic(null));
    assertNull(WorldControlMetadata.topic("settings/unknown/0/value"));
    assertNull(WorldControlMetadata.topic("override/unknown"));
    assertNull(WorldControlMetadata.topic("settings//threshold"));
    assertNull(WorldControlMetadata.topic("other/threshold"));
    assertNull(WorldControlMetadata.setting(null, "inputMin"));
    // This is a family guide, not a claim that this row/leaf exists in a live draft.
    assertEquals(
        "world.setting.parameterDrivers",
        WorldControlMetadata.topic("settings/parameterDrivers/999/unknown"));
  }

  @Test
  void legacyAndPartialProfilesMergeWorldDefaultsWithoutChangingCreationValues() throws Exception {
    var legacy = AppMetadata.read(root().resolve("fixtures/app-config-legacy.json"));
    assertNotNull(legacy.control("world.setting.threshold"));
    var partial = AppMetadata.read(root().resolve("fixtures/app-config-world-guidance.json"));
    assertEquals(
        "Configured World threshold 100% ### snow 雪",
        partial.control("world.setting.threshold").tooltip);
    assertEquals(
        "Configured live inheritance guidance", partial.control("world.inheritance").tooltip);
    assertEquals(AppMetadata.defaults().controls.keySet(), partial.controls.keySet());
    assertTrue(partial.creationDefaults.isEmpty());
  }

  @Test
  void privateMetadataPublicationChangesHelpWithoutApplyingAWorldDraft() throws Exception {
    var active = AppMetadata.defaults();
    var privateProfile = new MetadataEditSession(active);
    var project = new ProjectData();
    var layer = new ProjectData.Layer();
    layer.defaults.strength = .625;
    var operation = new ProjectData.Operation();
    operation.overrides.put("threshold", ProjectIO.JSON.valueToTree(0));
    layer.operations.add(operation);
    project.worldGenerator.layers.add(layer);
    var before = ProjectIO.JSON.valueToTree(project);
    privateProfile.draft().controls.get("world.setting.threshold").tooltip =
        "Private threshold guide";
    assertFalse(
        new HelpCatalog(active)
            .topic("world.setting.threshold")
            .tooltip()
            .equals("Private threshold guide"));
    var published = privateProfile.apply(active, directory.resolve("app.json"));
    assertEquals(
        "Private threshold guide",
        new HelpCatalog(published).topic("world.setting.threshold").tooltip());
    assertEquals(before, ProjectIO.JSON.valueToTree(project));
    assertEquals(.625, SettingsResolver.resolve(layer, operation).strength);
    assertEquals(0, SettingsResolver.resolve(layer, operation).threshold);
  }

  @Test
  void guidanceCannotAddCreationDefaultsOptionsOrComputationalAuthority() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("world.setting.action").options.put("SET", "Paint");
    assertThrows(IllegalArgumentException.class, metadata::validate);
    metadata.controls.get("world.setting.action").options.clear();
    metadata.creationDefaults.put("world.setting.strength", ProjectIO.JSON.valueToTree(.5));
    assertThrows(IllegalArgumentException.class, metadata::validate);
    assertFalse(CreationRegistry.CONTROLS.containsKey("world.setting.strength"));
  }

  @Test
  void exactSeedGuidanceKeepsRawDigitsAndRejectedSpellingWithoutDoubleConversion() {
    for (String raw :
        List.of(
            "9007199254740993",
            "+0009007199254740993",
            Long.toString(Long.MIN_VALUE),
            Long.toString(Long.MAX_VALUE),
            "-")) {
      var text = WorldControlMetadata.integerTooltip("Configured seed guide", raw, raw.equals("-"));
      assertTrue(text.contains("\nText: " + raw + "\n"));
      assertTrue(text.startsWith("Configured seed guide"));
      assertTrue(text.contains("Right-click for Help."));
      assertEquals(raw.equals("-"), text.contains("Correct the complete integer text"));
    }
  }

  @Test
  void contributedControlsAndHelpSnapshotsDoNotShareMutablePresentationObjects() {
    var left = new LinkedHashMap<String, AppMetadata.Control>();
    var right = new LinkedHashMap<String, AppMetadata.Control>();
    WorldControlMetadata.contribute(left);
    WorldControlMetadata.contribute(right);
    left.get("world.setting.threshold").tooltip = "Changed";
    assertNotEquals("Changed", right.get("world.setting.threshold").tooltip);
    assertThrows(UnsupportedOperationException.class, WorldControlMetadata.DEFINITIONS::clear);
    var catalog = new HelpCatalog(AppMetadata.defaults());
    for (var definition : WorldControlMetadata.DEFINITIONS) {
      var topic = catalog.topic(definition.id());
      assertTrue(topic.related().contains("help.world-guidance"));
      assertTrue(topic.copyText(catalog).contains(definition.description()));
    }
  }

  @Test
  void explicitSchemaAndFixtureKeysMatchThePresentationCatalogWithoutNewCreationKeys()
      throws Exception {
    var schema = ProjectIO.JSON.readTree(root().resolve("schema/app-config.schema.json").toFile());
    var controls = schema.at("/$defs/AppMetadata/properties/controls");
    var creation = schema.at("/$defs/AppMetadata/properties/creationDefaults/properties");
    assertEquals(AppMetadata.defaults().controls.size(), controls.path("maxProperties").intValue());
    for (var definition : WorldControlMetadata.DEFINITIONS) {
      var entry = controls.path("properties").path(definition.id());
      assertEquals("#/$defs/Control", entry.path("allOf").get(0).path("$ref").asText());
      assertFalse(
          entry
              .path("allOf")
              .get(1)
              .at("/properties/options/additionalProperties")
              .asBoolean(true));
      assertFalse(creation.has(definition.id()));
    }
  }
}
