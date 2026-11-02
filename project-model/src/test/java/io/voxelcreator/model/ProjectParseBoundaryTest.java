package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred whole-document admission, exact versions and resolvable rejection pointers. */
class ProjectParseBoundaryTest {
  @TempDir Path directory;

  private ProjectData fixture() {
    var p = new ProjectData();
    var noise = new Noise();
    noise.id = "field";
    p.noise2D.add(noise);
    return p;
  }

  @Test
  void extraValuesAndMalformedTailsRejectTheWholeInput() throws Exception {
    var io = new ProjectIO();
    String valid = ProjectIO.JSON.writeValueAsString(fixture());
    for (String tail :
        List.of("{}", "[]", "2", "true", "null", "\"tail\"", "garbage", "{", "/*comment*/")) {
      var result = io.parse(valid + "\n" + tail);
      assertFalse(result.success(), tail);
      assertNull(result.project());
      assertEquals(1, result.diagnostics().size());
      assertEquals("INVALID_JSON", result.diagnostics().getFirst().code());
      assertEquals("/", result.diagnostics().getFirst().jsonPointer());
    }
  }

  @Test
  void legalLeadingAndTrailingWhitespaceRetainsExactModelValues() throws Exception {
    var p = fixture();
    p.project.seed = Long.MIN_VALUE;
    p.project.minY = Integer.MIN_VALUE;
    p.project.maxY = Integer.MAX_VALUE;
    var expected = ProjectIO.JSON.valueToTree(p);
    var result =
        new ProjectIO().parse(" \t\r\n" + ProjectIO.JSON.writeValueAsString(p) + "\r\n\t ");
    assertTrue(result.success(), result.diagnostics().toString());
    assertEquals(expected, ProjectIO.JSON.valueToTree(result.project()));
  }

  @Test
  void unsupportedVersionsRejectBeforeIntegerNarrowingOrModelBinding() throws Exception {
    for (String version :
        List.of(
            "3",
            "2.0",
            "\"2\"",
            "true",
            "[]",
            "{}",
            "4294967298",
            "-4294967294",
            "340282366920938463463374607431768211458")) {
      ObjectNode tree = ProjectIO.JSON.valueToTree(fixture());
      tree.set("formatVersion", ProjectIO.JSON.readTree(version));
      var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree));
      assertFalse(result.success(), version);
      assertEquals("UNSUPPORTED_VERSION", result.diagnostics().getFirst().code());
      assertEquals("/formatVersion", result.diagnostics().getFirst().jsonPointer());
    }
  }

  @Test
  void missingNullAndDuplicateVersionFieldsKeepTheirExistingRejectionContracts() throws Exception {
    ObjectNode tree = ProjectIO.JSON.valueToTree(fixture());
    tree.remove("formatVersion");
    for (boolean explicitNull : List.of(false, true)) {
      if (explicitNull) tree.putNull("formatVersion");
      var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree));
      assertEquals("MISSING_FIELD", result.diagnostics().getFirst().code());
      assertEquals("/formatVersion", result.diagnostics().getFirst().jsonPointer());
    }
    String duplicate =
        "{\"formatVersion\":2," + ProjectIO.JSON.writeValueAsString(fixture()).substring(1);
    assertEquals("INVALID_JSON", new ProjectIO().parse(duplicate).diagnostics().getFirst().code());
  }

  @Test
  void mappingErrorsEscapeSpecialObjectKeysWithoutLosingArrayIndices() throws Exception {
    ObjectNode tree = ProjectIO.JSON.valueToTree(fixture());
    var noise = (ObjectNode) tree.path("noise2D").get(0);
    noise.put("unknown~/field", "bad");
    var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree));
    var issue = result.diagnostics().getFirst();
    assertEquals("INVALID_JSON", issue.code());
    assertEquals("/noise2D/0/unknown~0~1field", issue.jsonPointer());
    assertEquals("bad", tree.at(issue.jsonPointer()).textValue());
  }

  @Test
  void uuidShapeErrorsEscapeEveryDynamicParentKey() throws Exception {
    var p = fixture();
    var item = new Item();
    item.id = "disabled";
    item.kind = "EXPRESSION";
    item.enabled = false;
    item.inputs.put("a~/b", Binding.noiseField(p.noise2D.getFirst().uuid));
    p.noise2D.getFirst().processing.add(item);
    ObjectNode tree = ProjectIO.JSON.valueToTree(p);
    ((ObjectNode) tree.path("noise2D").get(0).path("processing").get(0).path("inputs").path("a~/b"))
        .put("noiseGeneratorUuid", "malformed");
    var result = new ProjectIO().parse(ProjectIO.JSON.writeValueAsString(tree));
    var issue = result.diagnostics().getFirst();
    assertEquals("INVALID_STRUCTURE", issue.code());
    assertEquals("/noise2D/0/processing/0/inputs/a~0~1b/noiseGeneratorUuid", issue.jsonPointer());
    assertEquals("malformed", tree.at(issue.jsonPointer()).textValue());
  }

  @Test
  void rejectedOpenRetainsSessionPathLifetimeDirtyCheckpointAndHistory() throws Exception {
    var document = new DocumentSession();
    assertTrue(document.edit(p -> p.project.seed = 17).isEmpty());
    var saved = directory.resolve("saved.json");
    document.save(saved);
    byte[] bytes = Files.readAllBytes(saved);
    assertTrue(document.edit(p -> p.project.seed = 29).isEmpty());
    var active = document.data();
    var token = document.sessionToken();
    var before = ProjectIO.JSON.valueToTree(active);
    long history = document.historyBytes();
    var candidate = directory.resolve("candidate.json");
    Files.writeString(candidate, ProjectIO.JSON.writeValueAsString(fixture()) + "\n{}");
    var issues = document.open(candidate);
    assertEquals("INVALID_JSON", issues.getFirst().code());
    assertSame(active, document.data());
    assertSame(token, document.sessionToken());
    assertEquals(saved, document.path());
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    assertEquals(history, document.historyBytes());
    assertTrue(document.dirty());
    assertArrayEquals(bytes, Files.readAllBytes(saved));
    document.undo();
    assertEquals(17, document.data().project.seed);
    assertFalse(document.dirty());
    document.redo();
    assertEquals(29, document.data().project.seed);
    assertTrue(document.dirty());
    Files.writeString(candidate, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    assertTrue(document.open(candidate).isEmpty());
    assertNotSame(token, document.sessionToken());
    assertEquals(candidate, document.path());
    assertFalse(document.dirty());
    assertEquals(0, document.historyBytes());
  }
}
