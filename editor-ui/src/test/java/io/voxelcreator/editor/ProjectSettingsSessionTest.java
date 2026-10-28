package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSettingsSessionTest {
  private EditorState state() {
    return new EditorState(new UserConfig(), false);
  }

  @Test
  void rawInvalidTextIsRetainedAndCannotApplyAnEarlierParsedSeed() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      for (var value :
          new String[] {"-", "+", "1e3", "0x10", " 3", "3 ", "--1", "١٢", "9223372036854775808"}) {
        session.edit("seed", value);
        assertEquals(value, session.text("seed"));
        assertFalse(session.apply(state));
        assertEquals("/project/seed", state.issues.getFirst().jsonPointer());
        assertTrue(
            EditorEvents.Target.diagnostic(state.project().project.uuid, state.issues.getFirst())
                .settings());
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      }
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
      assertTrue(session.current(state));
    }
  }

  @Test
  void exactSignedExtremesAndInclusiveIntBoundsRemainExact() {
    for (long seed : new long[] {Long.MIN_VALUE, Long.MAX_VALUE})
      try (var state = state()) {
        var session = new ProjectSettingsSession(state);
        session.edit("seed", Long.toString(seed));
        session.edit("minY", Integer.toString(Integer.MIN_VALUE));
        session.edit("maxY", Integer.toString(Integer.MAX_VALUE));
        assertTrue(session.validate().isEmpty());
        assertTrue(session.apply(state));
        assertEquals(seed, state.project().project.seed);
        assertEquals(Integer.MIN_VALUE, state.project().project.minY);
        assertEquals(Integer.MAX_VALUE, state.project().project.maxY);
        assertTrue(session.closed());
      }
  }

  @Test
  void invalidBoundsAndIntegerOverflowArePrivateAndPreciselyRouted() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("minY", "2147483648");
      assertEquals("minY", session.validate().getFirst().parameterKey());
      session.edit("minY", "4");
      session.edit("maxY", "3");
      assertEquals("INVALID_BOUNDS", session.validate().getFirst().code());
      assertEquals("maxY", session.validate().getFirst().parameterKey());
      assertFalse(session.apply(state));
      assertEquals(-64, state.project().project.minY);
      session.edit("maxY", "4");
      assertTrue(session.apply(state));
      assertEquals(4, state.project().project.minY);
      assertEquals(4, state.project().project.maxY);
    }
  }

  @Test
  void applyChangesOnlyThreeValuesAndPreservesAnUnappliedResourceDraft() {
    try (var state = state()) {
      assertTrue(
          state.change(
              p -> {
                var noise = new Noise();
                noise.id = "noise";
                p.noise2D.add(noise);
              }));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (Noise) state.draft;
      draft.id = "private";
      var before = ProjectIO.JSON.valueToTree(state.project());
      var preferences = ProjectIO.JSON.valueToTree(state.user);
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "+17");
      session.edit("minY", "-2");
      session.edit("maxY", "3");
      assertTrue(session.apply(state));
      var after = ProjectIO.JSON.valueToTree(state.project());
      var expected = before.deepCopy();
      var project = (com.fasterxml.jackson.databind.node.ObjectNode) expected.get("project");
      project.put("seed", 17);
      project.put("minY", -2);
      project.put("maxY", 3);
      assertEquals(expected, after);
      assertSame(draft, state.draft);
      assertEquals("private", draft.id);
      assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void configuredProjectValidationCanRejectOtherwiseValidSettingsWithoutClosingTheirSession() {
    try (var state = state()) {
      state.document.setValidator(
          p ->
              p.project.minY == p.project.maxY
                  ? java.util.List.of(
                      Diagnostic.error(
                          "REJECT_SINGLE_ROW",
                          "/project/maxY",
                          "Configured project validation rejected this range."))
                  : NoiseCompiler.validate(p));
      var before = ProjectIO.JSON.valueToTree(state.project());
      var session = new ProjectSettingsSession(state);
      session.edit("minY", "0");
      session.edit("maxY", "0");
      assertTrue(session.validate().isEmpty());
      assertFalse(session.apply(state));
      assertFalse(session.applyIssues().isEmpty());
      assertTrue(session.current(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      session.edit("maxY", "1");
      assertTrue(session.applyIssues().isEmpty());
      assertTrue(session.apply(state));
    }
  }

  @Test
  void unrelatedDocumentEditRevisionChangeAndUndoInvalidateTheOriginalSession() {
    for (int mode = 0; mode < 4; mode++)
      try (var state = state()) {
        var session = new ProjectSettingsSession(state);
        session.edit("seed", "88");
        if (mode == 0) state.change(p -> p.project.seed = 2);
        else if (mode == 1) state.revision++;
        else if (mode == 2) {
          state.change(p -> p.project.seed = 2);
          state.document.undo();
        } else state.project().project.minY = -63;
        var before = ProjectIO.JSON.valueToTree(state.project());
        assertFalse(session.current(state));
        assertThrows(IllegalStateException.class, () -> session.apply(state));
        assertEquals("88", session.text("seed"));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      }
  }

  @Test
  void reopenedSameUuidAndNewProjectCannotReceiveAnOldBuffer(@TempDir Path directory)
      throws Exception {
    try (var state = state()) {
      Path file = directory.resolve("same.json");
      state.document.save(file);
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "88");
      var uuid = state.project().project.uuid;
      assertTrue(state.document.open(file).isEmpty());
      assertEquals(uuid, state.project().project.uuid);
      assertThrows(IllegalStateException.class, () -> session.apply(state));
      var next = new ProjectSettingsSession(state);
      next.edit("seed", "99");
      state.newSession();
      assertThrows(IllegalStateException.class, () -> next.apply(state));
      assertEquals(0, state.project().project.seed);
    }
  }

  @Test
  void saveAndPreferencesDoNotInvalidateSettingsAndNoOpApplyAddsNoHistory(@TempDir Path directory)
      throws Exception {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      state.document.save(directory.resolve("project.json"));
      state.user.visualization.volumeShadowSteps = 8;
      assertTrue(session.current(state));
      long history = state.document.historyBytes();
      assertTrue(session.apply(state));
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void cancellationTextBoundsAndFeedbackCacheHaveExplicitLifetimes() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "-");
      assertSame(session.validate(), session.validate());
      assertThrows(IllegalArgumentException.class, () -> session.edit("seed", "1".repeat(129)));
      assertEquals("-", session.text("seed"));
      assertThrows(IllegalArgumentException.class, () -> session.edit("unknown", "1"));
      session.cancel();
      assertThrows(IllegalStateException.class, () -> session.edit("seed", "1"));
      assertThrows(IllegalStateException.class, () -> session.apply(state));
      assertFalse(state.document.dirty());
    }
  }
}
