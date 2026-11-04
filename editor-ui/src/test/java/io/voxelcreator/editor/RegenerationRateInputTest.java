package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred strict live-rate edits, raw retention and pane/source ownership. */
class RegenerationRateInputTest {
  @TempDir Path directory;

  @Test
  void everyRateAndSignedLeadingZeroSpellingKeepsItsExactTextWithoutRedrawChanges() {
    var user = new UserConfig();
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    long version = input.version();
    for (int value = 1; value <= 20; value++) {
      String raw = "+000" + value;
      assertTrue(input.edit(user.editor, raw));
      assertEquals(value, user.editor.opportunitiesPerSecond);
      for (int redraw = 0; redraw < 3; redraw++) input.synchronize(user.editor);
      assertEquals(raw, input.text());
      assertEquals("", input.problem());
      assertEquals(version, input.version());
    }
  }

  @Test
  void rejectedSyntaxAndOutOfRangeIntegersRetainRawTextAndTheLastValidActiveValue() {
    for (String raw :
        List.of(
            "", "-", "+", "0", "-1", "21", "1.0", "1e1", " 7", "7 ", "NaN", "2147483648", "七")) {
      var user = new UserConfig();
      var input = new RegenerationRateInput();
      input.synchronize(user.editor);
      assertFalse(input.edit(user.editor, raw));
      assertEquals(10, user.editor.opportunitiesPerSecond);
      assertEquals(raw, input.text());
      assertFalse(input.problem().isBlank());
      input.synchronize(user.editor);
      assertEquals(raw, input.text());
    }
  }

  @Test
  void correctionChangesOnlyTheRateAndPreservesOtherPreferenceDomains() {
    var user = new UserConfig();
    var before = ProjectIO.JSON.valueToTree(user);
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    assertFalse(input.edit(user.editor, "--"));
    assertTrue(input.edit(user.editor, "+0007"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) before.get("editor"))
        .put("opportunitiesPerSecond", 7);
    assertEquals(before, ProjectIO.JSON.valueToTree(user));
    assertEquals("+0007", input.text());
  }

  @Test
  void suspendRetiresOldEditsButRetainsRejectedTextUntilTheSamePaneReopens() {
    var user = new UserConfig();
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    assertFalse(input.edit(user.editor, "-"));
    long version = input.version();
    input.suspend();
    input.suspend();
    assertFalse(input.current(user.editor));
    assertThrows(IllegalStateException.class, () -> input.edit(user.editor, "20"));
    input.synchronize(user.editor);
    assertTrue(input.version() > version);
    assertEquals("-", input.text());
    assertEquals(10, user.editor.opportunitiesPerSecond);
  }

  @Test
  void replacementEditorWithIdenticalValuesHasADifferentInputOwner() {
    var user = new UserConfig();
    var old = user.editor;
    var input = new RegenerationRateInput();
    input.synchronize(old);
    input.edit(old, "-");
    long version = input.version();
    user.editor = new UserConfig.Editor();
    assertFalse(input.current(user.editor));
    assertThrows(IllegalStateException.class, () -> input.edit(user.editor, "20"));
    input.synchronize(user.editor);
    assertTrue(input.version() > version);
    assertEquals("10", input.text());
    assertThrows(IllegalStateException.class, () -> input.edit(old, "20"));
    assertEquals(10, old.opportunitiesPerSecond);
    assertEquals(10, user.editor.opportunitiesPerSecond);
  }

  @Test
  void externallyChangedRateRetiresTheOldTextAndRequiresFreshAuthority() {
    var user = new UserConfig();
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    input.edit(user.editor, "-");
    long version = input.version();
    user.editor.opportunitiesPerSecond = 17;
    assertFalse(input.current(user.editor));
    assertThrows(IllegalStateException.class, () -> input.edit(user.editor, "20"));
    input.synchronize(user.editor);
    assertTrue(input.version() > version);
    assertEquals("17", input.text());
    assertEquals(17, input.value());
  }

  @Test
  void restoringActiveRateDiscardsRejectedTextWithoutChangingAnyPreference() {
    var user = new UserConfig();
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    input.edit(user.editor, "21");
    long version = input.version();
    var before = ProjectIO.JSON.valueToTree(user);
    input.restore(user.editor);
    assertTrue(input.version() > version);
    assertEquals("10", input.text());
    assertEquals("", input.problem());
    assertEquals(before, ProjectIO.JSON.valueToTree(user));
  }

  @Test
  void overlongOrNullEditsCannotReplaceTheRetainedBufferOrActiveValue() {
    var user = new UserConfig();
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    input.edit(user.editor, "-");
    assertThrows(IllegalArgumentException.class, () -> input.edit(user.editor, "0".repeat(33)));
    assertThrows(NullPointerException.class, () -> input.edit(user.editor, null));
    assertEquals("-", input.text());
    assertEquals(10, user.editor.opportunitiesPerSecond);
  }

  @Test
  void savingAndRestartReadUseOnlyTheLastValidActiveRateWhileRejectedTextRemainsPrivate()
      throws Exception {
    var user = new UserConfig();
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    input.edit(user.editor, "+0007");
    input.edit(user.editor, "-");
    var file = directory.resolve("user-config.json");
    user.save(file);
    assertEquals(7, UserConfig.read(file).editor.opportunitiesPerSecond);
    assertEquals("-", input.text());
    assertEquals(7, input.value());
    assertFalse(
        ProjectIO.JSON.readTree(file.toFile()).toString().contains("RegenerationRateInput"));
  }

  @Test
  void invalidExternalRatesRemainVisibleAndCanBeRepairedOnlyByACompleteValidEdit() {
    var user = new UserConfig();
    user.editor.opportunitiesPerSecond = 0;
    var input = new RegenerationRateInput();
    input.synchronize(user.editor);
    assertEquals("0", input.text());
    assertFalse(input.problem().isEmpty());
    assertTrue(input.edit(user.editor, "1"));
    assertEquals(1, user.editor.opportunitiesPerSecond);
    user.editor.opportunitiesPerSecond = 21;
    input.synchronize(user.editor);
    assertEquals("21", input.text());
    assertTrue(input.edit(user.editor, "20"));
    user.validate();
  }
}
