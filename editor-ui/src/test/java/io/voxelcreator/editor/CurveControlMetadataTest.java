package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurveControlMetadataTest {
  @TempDir Path directory;

  private static Path root() {
    for (var path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
      if (Files.isDirectory(path.resolve("fixtures"))) return path;
    throw new AssertionError("Repository fixtures unavailable");
  }

  @Test
  void everyNumericModeLocalControlAndActionHasExactlyOneSubject() {
    var expected =
        Set.of(
            "min",
            "max",
            "displayMin",
            "displayMax",
            "stepX",
            "stepY",
            "x",
            "y",
            "inTangent",
            "outTangent",
            "outsideDomain",
            "segmentInterpolation",
            "handleMode",
            "grid",
            "snapX",
            "snapY",
            "undo",
            "redo",
            "fit",
            "delete",
            "apply",
            "cancel",
            "plot");
    var found = new HashSet<String>();
    var metadata = AppMetadata.defaults();
    int order = 0;
    for (var definition : CurveControlMetadata.DEFINITIONS) {
      assertTrue(found.add(definition.key()));
      var control = metadata.control(definition.id());
      assertEquals(definition.id(), CurveControlMetadata.topic(definition.key()));
      assertEquals(definition.tooltip(), control.tooltip);
      assertEquals(order++, control.order);
      assertFalse(control.label.isBlank());
      assertTrue(control.tooltip.length() <= 1024);
      assertTrue(control.description.length() <= 8192);
    }
    assertEquals(expected, found);
    metadata.validate();
  }

  @Test
  void guidanceSubjectsAreDistinctFromExistingCreationControls() {
    for (String field : List.of("min", "max", "displayMin", "displayMax", "outsideDomain")) {
      assertTrue(CreationRegistry.CONTROLS.containsKey("curve." + field));
      assertFalse(CreationRegistry.CONTROLS.containsKey(CurveControlMetadata.topic(field)));
      assertNotEquals("curve." + field, CurveControlMetadata.topic(field));
    }
    assertNull(CurveControlMetadata.topic(null));
    assertNull(CurveControlMetadata.topic("points/0/y"));
    assertNull(CurveControlMetadata.topic("curve.min"));
    assertNull(CurveControlMetadata.topic("unknown"));
  }

  @Test
  void configuredTooltipAndModeDetailsRemainLiteralAndIndependent() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("curve.editor.outsideDomain").tooltip = "100% ### snow 雪\nConfigured";
    for (String mode : CurveModes.OUTSIDE) {
      String detail = CurveModes.help(mode);
      String text = CurveControlMetadata.tooltip(metadata, "outsideDomain", detail);
      assertTrue(text.startsWith("100% ### snow 雪\nConfigured\n"));
      assertTrue(text.contains(detail));
      assertTrue(text.endsWith("Right-click for Help."));
    }
    metadata.controls.get("curve.editor.outsideDomain").tooltip = "";
    assertFalse(CurveControlMetadata.tooltip(metadata, "outsideDomain", null).startsWith("\n"));
    assertThrows(
        NullPointerException.class, () -> CurveControlMetadata.tooltip(metadata, "unknown", ""));
  }

  @Test
  void numericGuidancePreservesExactValuesAndPerPartNativeIdentity() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("curve.editor.y").tooltip = "Configured curve output";
    for (double value : new double[] {Math.nextUp(1.0), -2.5, Double.MIN_VALUE}) {
      var guide = NumericGuidance.from(metadata, CurveControlMetadata.topic("y"), value, -.2, 1.2);
      assertTrue(guide.text().startsWith("Configured curve output\n"));
      assertTrue(guide.text().contains(ScalarDisplay.exact(value)));
      assertEquals("curve.editor.y", guide.topic());
      assertNotEquals(guide.identity(17), guide.identity(19));
    }
  }

  @Test
  void legacyAndPartialProfilesMergeNewGuidesWithoutCreationOverrides() throws Exception {
    var legacy = AppMetadata.read(root().resolve("fixtures/app-config-legacy.json"));
    assertNotNull(legacy.control("curve.editor.y"));
    var partial = AppMetadata.read(root().resolve("fixtures/app-config-curve-guidance.json"));
    assertEquals("Configured curve 100% ### snow 雪", partial.control("curve.editor.y").tooltip);
    assertEquals(AppMetadata.defaults().controls.keySet(), partial.controls.keySet());
    assertTrue(partial.creationDefaults.isEmpty());
    partial.validate();
  }

  @Test
  void privateMetadataApplyChangesHelpWithoutChangingCurveOrPreferences() throws Exception {
    var active = AppMetadata.defaults();
    var session = new MetadataEditSession(active);
    var curve = new ProjectData.Curve();
    var preferences = new UserConfig();
    var beforeCurve = ProjectIO.JSON.valueToTree(curve);
    var beforeUser = ProjectIO.JSON.valueToTree(preferences);
    session.draft().controls.get("curve.editor.y").tooltip = "Private output guide";
    assertNotEquals(
        "Private output guide", new HelpCatalog(active).topic("curve.editor.y").tooltip());
    var published = session.apply(active, directory.resolve("app.json"));
    assertEquals(
        "Private output guide", new HelpCatalog(published).topic("curve.editor.y").tooltip());
    assertEquals(beforeCurve, ProjectIO.JSON.valueToTree(curve));
    assertEquals(beforeUser, ProjectIO.JSON.valueToTree(preferences));
    assertTrue(published.creationDefaults.isEmpty());
  }

  @Test
  void guideChangesCannotModifyNewCurveDefaultsOrEvaluation() {
    var metadata = AppMetadata.defaults();
    for (var definition : CurveControlMetadata.DEFINITIONS) {
      var control = metadata.controls.get(definition.id());
      control.label = "Configured " + definition.key();
      control.tooltip = "Configured guide";
      control.description = "Configured description";
    }
    var curve = EditorCreations.curve(metadata);
    assertEquals(0, curve.min);
    assertEquals(1, curve.max);
    assertEquals(-.2, curve.displayMin);
    assertEquals(1.2, curve.displayMax);
    assertEquals("CLAMP", curve.outsideDomain);
    assertEquals(2, curve.points.size());
    var compiled = new ScalarCurve(curve);
    for (double x : new double[] {-2, 0, .125, .5, 1, 2})
      assertEquals(Math.max(0, Math.min(1, x)), compiled.sample(x));
  }

  @Test
  void newSubjectsCannotAcquireOptionsOrCreationAuthority() {
    var metadata = AppMetadata.defaults();
    for (var definition : CurveControlMetadata.DEFINITIONS) {
      var control = metadata.controls.get(definition.id());
      control.options.put("CLAMP", "Stop");
      assertThrows(IllegalArgumentException.class, metadata::validate);
      control.options.clear();
      metadata.creationDefaults.put(definition.id(), ProjectIO.JSON.valueToTree(.5));
      assertThrows(IllegalArgumentException.class, metadata::validate);
      metadata.creationDefaults.clear();
    }
    metadata.validate();
  }

  @Test
  void freshContributionsAndHelpSnapshotsDoNotShareMutableGuidance() {
    var left = new LinkedHashMap<String, AppMetadata.Control>();
    var right = new LinkedHashMap<String, AppMetadata.Control>();
    CurveControlMetadata.contribute(left);
    CurveControlMetadata.contribute(right);
    left.get("curve.editor.y").tooltip = "Changed";
    assertNotEquals("Changed", right.get("curve.editor.y").tooltip);
    assertThrows(UnsupportedOperationException.class, CurveControlMetadata.DEFINITIONS::clear);
    var metadata = AppMetadata.defaults();
    var catalog = new HelpCatalog(metadata);
    metadata.controls.get("curve.editor.y").description = "Later";
    for (var definition : CurveControlMetadata.DEFINITIONS) {
      var topic = catalog.topic(definition.id());
      assertTrue(topic.related().contains("help.curve-guidance"));
      assertTrue(topic.copyText(catalog).contains(definition.description()));
    }
  }

  @Test
  void explicitSchemaContainsExactlyTheNewClosedPresentationSubjects() throws Exception {
    var schema = ProjectIO.JSON.readTree(root().resolve("schema/app-config.schema.json").toFile());
    var controls = schema.at("/$defs/AppMetadata/properties/controls");
    var creation = schema.at("/$defs/AppMetadata/properties/creationDefaults/properties");
    assertEquals(AppMetadata.defaults().controls.size(), controls.path("maxProperties").intValue());
    var found = new HashSet<String>();
    controls
        .path("properties")
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (key.startsWith("curve.editor.")) found.add(key);
            });
    assertEquals(23, found.size());
    for (var definition : CurveControlMetadata.DEFINITIONS) {
      assertTrue(found.contains(definition.id()));
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
