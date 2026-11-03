package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsControlMetadataTest {
  @TempDir Path directory;

  private static Path root() {
    for (var path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
      if (Files.isDirectory(path.resolve("fixtures"))) return path;
    throw new AssertionError("Repository fixtures unavailable");
  }

  @Test
  void everyProjectPerformanceAndCaptureInputHasOnePresentationSubject() {
    var expected = new HashSet<String>();
    for (String field : List.of("seed", "minY", "maxY"))
      expected.add(SettingsControlMetadata.project(field));
    for (var field : PerformancePreferences.Field.values())
      expected.add(SettingsControlMetadata.performance(field));
    for (var field : ProfileCaptureInputs.Field.values())
      expected.add(SettingsControlMetadata.capture(field));
    expected.add("settings.capture.work");
    var actual = new HashSet<String>();
    var metadata = AppMetadata.defaults();
    for (var definition : SettingsControlMetadata.DEFINITIONS) {
      assertTrue(actual.add(definition.id()));
      assertFalse(definition.label().isBlank());
      assertFalse(definition.tooltip().isBlank());
      assertTrue(definition.tooltip().length() <= 1024);
      assertTrue(definition.description().length() <= 8192);
      assertEquals(definition.tooltip(), metadata.control(definition.id()).tooltip);
    }
    assertEquals(22, actual.size());
    assertEquals(expected, actual);
    metadata.validate();
  }

  @Test
  void topicsRejectUnknownScopesWithoutInventingNavigationOrCreationKeys() {
    assertNull(SettingsControlMetadata.project(null));
    assertNull(SettingsControlMetadata.project("seedOffset"));
    assertNull(SettingsControlMetadata.project("minY/0"));
    assertNull(SettingsControlMetadata.performance(null));
    assertNull(SettingsControlMetadata.capture(null));
    assertNull(SettingsControlMetadata.topic("settings.capture.unknown"));
    for (var definition : SettingsControlMetadata.DEFINITIONS)
      assertFalse(CreationRegistry.CONTROLS.containsKey(definition.id()));
  }

  @Test
  void exactSeedAndBudgetGuidancePreservesEveryRawDigitWithoutDoubleConversion() {
    var metadata = AppMetadata.defaults();
    for (String raw :
        List.of(
            "9007199254740993",
            "+0009007199254740993",
            Long.toString(Long.MIN_VALUE),
            Long.toString(Long.MAX_VALUE))) {
      String text =
          SettingsControlMetadata.integerTooltip(
              metadata, "settings.project.seed", raw, Long.MIN_VALUE, Long.MAX_VALUE, true);
      assertTrue(text.contains("\nText: " + raw + "\n"));
      assertFalse(text.contains("Correct or restore"));
      assertTrue(
          text.contains(Long.toString(Long.MIN_VALUE))
              && text.contains(Long.toString(Long.MAX_VALUE)));
    }
  }

  @Test
  void integerDomainAndRejectedOrStaleSpellingRemainExplicit() {
    var metadata = AppMetadata.defaults();
    for (String raw : List.of("-", "1.0", "9223372036854775808", " 12", "NaN")) {
      var text =
          SettingsControlMetadata.integerTooltip(
              metadata, "settings.project.seed", raw, Long.MIN_VALUE, Long.MAX_VALUE, true);
      assertTrue(text.contains("\nText: " + raw + "\n"));
      assertTrue(text.contains("Correct or restore"));
    }
    assertTrue(
        SettingsControlMetadata.integerTooltip(
                metadata,
                "settings.project.minY",
                "2147483648",
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                true)
            .contains("Correct or restore"));
    assertTrue(
        SettingsControlMetadata.integerTooltip(
                metadata, "settings.project.seed", "17", Long.MIN_VALUE, Long.MAX_VALUE, false)
            .contains("Correct or restore"));
    assertFalse(
        SettingsControlMetadata.integerTooltip(
                metadata, "settings.performance.cacheMiB", "0", 0, Integer.MAX_VALUE, true)
            .contains("Correct or restore"));
  }

  @Test
  void configuredGuidanceIsLiteralAndBothPartsUseIndependentNativeIdentities() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("settings.project.seed").tooltip = "Configured 100% ### snow 雪";
    String text =
        SettingsControlMetadata.integerTooltip(
            metadata, "settings.project.seed", "+0017", Long.MIN_VALUE, Long.MAX_VALUE, true);
    assertTrue(text.startsWith("Configured 100% ### snow 雪\n"));
    assertTrue(text.endsWith("Right-click for Help."));
    assertNotEquals(
        NumericGuidance.identity("settings.project.seed", 12),
        NumericGuidance.identity("settings.project.seed", 13));
    assertThrows(
        NullPointerException.class,
        () -> SettingsControlMetadata.tooltip(metadata, "settings.unknown", ""));
  }

  @Test
  void legacyAndPartialProfilesMergeSettingsGuidesWithoutNewDefaults() throws Exception {
    var legacy = AppMetadata.read(root().resolve("fixtures/app-config-legacy.json"));
    assertNotNull(legacy.control("settings.project.seed"));
    var partial = AppMetadata.read(root().resolve("fixtures/app-config-settings-guidance.json"));
    assertEquals(
        "Configured exact seed 100% ### snow 雪", partial.control("settings.project.seed").tooltip);
    assertEquals("Configured work totals guide", partial.control("settings.capture.work").tooltip);
    assertEquals(AppMetadata.defaults().controls.keySet(), partial.controls.keySet());
    assertTrue(partial.creationDefaults.isEmpty());
  }

  @Test
  void privateMetadataApplyCannotPublishProjectPreferencesOrCaptureInput() throws Exception {
    var active = AppMetadata.defaults();
    var session = new MetadataEditSession(active);
    var project = new ProjectData();
    project.project.seed = 9007199254740993L;
    var user = new UserConfig();
    var capture = new ProfileCaptureInputs();
    capture.edit(ProfileCaptureInputs.Field.DURATION, "-");
    var beforeProject = ProjectIO.JSON.valueToTree(project);
    var beforeUser = ProjectIO.JSON.valueToTree(user);
    var beforeCapture = capture.text();
    session.draft().controls.get("settings.project.seed").tooltip = "Private seed guide";
    assertNotEquals(
        "Private seed guide", new HelpCatalog(active).topic("settings.project.seed").tooltip());
    var applied = session.apply(active, directory.resolve("app.json"));
    assertEquals(
        "Private seed guide", new HelpCatalog(applied).topic("settings.project.seed").tooltip());
    assertEquals(beforeProject, ProjectIO.JSON.valueToTree(project));
    assertEquals(beforeUser, ProjectIO.JSON.valueToTree(user));
    assertEquals(beforeCapture, capture.text());
    assertFalse(capture.work());
    assertNull(capture.feedback().options());
  }

  @Test
  void subjectsCannotAcquireOptionsOrCreationDefaultAuthority() {
    var metadata = AppMetadata.defaults();
    for (var definition : SettingsControlMetadata.DEFINITIONS) {
      var control = metadata.controls.get(definition.id());
      control.options.put("true", "Enabled");
      assertThrows(IllegalArgumentException.class, metadata::validate);
      control.options.clear();
      metadata.creationDefaults.put(definition.id(), ProjectIO.JSON.valueToTree(17));
      assertThrows(IllegalArgumentException.class, metadata::validate);
      metadata.creationDefaults.clear();
    }
    metadata.validate();
  }

  @Test
  void contributedControlsAndHelpSnapshotsDoNotShareMutableGuidance() {
    var left = new LinkedHashMap<String, AppMetadata.Control>();
    var right = new LinkedHashMap<String, AppMetadata.Control>();
    SettingsControlMetadata.contribute(left);
    SettingsControlMetadata.contribute(right);
    left.get("settings.project.seed").tooltip = "Changed";
    assertNotEquals("Changed", right.get("settings.project.seed").tooltip);
    assertThrows(UnsupportedOperationException.class, SettingsControlMetadata.DEFINITIONS::clear);
    var catalog = new HelpCatalog(AppMetadata.defaults());
    for (var definition : SettingsControlMetadata.DEFINITIONS) {
      assertTrue(catalog.topic(definition.id()).related().contains("help.settings-guidance"));
      assertTrue(
          catalog.topic(definition.id()).copyText(catalog).contains(definition.description()));
    }
  }

  @Test
  void explicitSchemaSubjectsAndLimitsMatchTheCatalogWithoutNewCreationKeys() throws Exception {
    var schema = ProjectIO.JSON.readTree(root().resolve("schema/app-config.schema.json").toFile());
    var controls = schema.at("/$defs/AppMetadata/properties/controls");
    var creation = schema.at("/$defs/AppMetadata/properties/creationDefaults/properties");
    assertEquals(AppMetadata.defaults().controls.size(), controls.path("maxProperties").intValue());
    for (var definition : SettingsControlMetadata.DEFINITIONS) {
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
