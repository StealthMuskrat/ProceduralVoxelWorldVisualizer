package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Deferred exact displayed-input, source-value and failed-attempt recovery definitions. */
class ProjectSettingsOwnershipTest {
  private EditorState state() {
    return new EditorState(new UserConfig(), false);
  }

  private Map<String, String> displayed(ProjectSettingsSession session) {
    var fields = new LinkedHashMap<String, String>();
    ProjectSettingsSession.FIELDS.forEach(f -> fields.put(f, session.text(f)));
    return fields;
  }

  @Test
  void everyDisplayedFieldMustMatchBeforeValidationOrPublication() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "17");
      var before = ProjectIO.JSON.valueToTree(state.project());
      var calls = new AtomicInteger();
      state.document.setValidator(
          p -> {
            calls.incrementAndGet();
            return NoiseCompiler.validate(p);
          });
      for (var field : ProjectSettingsSession.FIELDS) {
        var shown = displayed(session);
        shown.put(field, "-");
        assertThrows(IllegalStateException.class, () -> session.apply(state, shown));
        var raw = session.text(field);
        shown.put(field, raw.startsWith("-") ? "-0" + raw.substring(1) : "+0" + raw);
        // Equal numbers do not authorize different displayed strings.
        assertThrows(IllegalStateException.class, () -> session.apply(state, shown));
      }
      var missing = displayed(session);
      missing.remove("maxY");
      assertThrows(IllegalStateException.class, () -> session.apply(state, missing));
      var extra = displayed(session);
      extra.put("other", "1");
      assertThrows(IllegalStateException.class, () -> session.apply(state, extra));
      assertEquals(0, calls.get());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals("17", session.text("seed"));
      assertFalse(session.closed());
      assertTrue(session.apply(state, displayed(session)));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void displayedAgreementWithInvalidTextStillCannotUseAnEarlierParsedSeed() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "17");
      assertTrue(session.validate().isEmpty());
      session.edit("seed", "+");
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertFalse(session.apply(state, displayed(session)));
      assertEquals("+", session.text("seed"));
      assertEquals(1, session.issueCount());
      assertEquals("/project/seed", session.applyIssues().getFirst().jsonPointer());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      session.edit("seed", Long.toString(Long.MIN_VALUE));
      assertTrue(session.apply(state, displayed(session)));
      assertEquals(Long.MIN_VALUE, state.project().project.seed);
    }
  }

  @Test
  void logicalProjectIdentityChangeInvalidatesTheOpeningWithoutARevision() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "17");
      state.project().project.uuid = UUID.randomUUID();
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertFalse(session.current(state));
      assertThrows(IllegalStateException.class, () -> session.apply(state, displayed(session)));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals("17", session.text("seed"));
    }
  }

  @Test
  void unrevisionedResourceChangeForcesExplicitReloadEvenIfLaterRestored() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "17");
      var noise = new Noise();
      noise.id = "added-elsewhere";
      state.project().noise2D.add(noise);
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertTrue(session.current(state)); // The inexpensive draw guard does not copy resources.
      assertThrows(IllegalStateException.class, () -> session.apply(state, displayed(session)));
      assertFalse(session.current(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.project().noise2D.clear();
      assertFalse(session.current(state));
      assertThrows(IllegalStateException.class, () -> session.apply(state, displayed(session)));
      assertEquals("17", session.text("seed"));
      assertEquals(0, state.revision);
      assertEquals(0, state.document.historyBytes());
    }
  }

  @Test
  void settingsBelongToTheirEditorButRemainIndependentOfPrivateResourceSelection() {
    try (var state = state();
        var foreign = state()) {
      assertTrue(
          state.change(
              p -> {
                var noise = new Noise();
                noise.id = "committed";
                p.noise2D.add(noise);
              }));
      var session = new ProjectSettingsSession(state);
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (Noise) state.draft;
      draft.id = "private-id";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      assertFalse(session.current(foreign));
      assertThrows(IllegalStateException.class, () -> session.apply(foreign, displayed(session)));
      assertTrue(session.current(state));
      session.edit("seed", "+17");
      assertTrue(session.apply(state, displayed(session)));
      assertSame(draft, state.draft);
      assertEquals("private-id", draft.id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals("committed", state.project().noise2D.getFirst().id);
      assertEquals(17, state.project().project.seed);
      assertFalse(state.applyDraft());
    }
  }

  @Test
  void repeatedRejectionThenThrowRetainsRawInputAndOwnsOnlyCurrentAttemptDetails() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "+17");
      var issues =
          IntStream.range(0, 300)
              .mapToObj(i -> Diagnostic.error("REJECT", "/fixture/" + i, "x".repeat(4000)))
              .toList();
      state.document.setValidator(p -> issues);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      for (int attempt = 0; attempt < 2; attempt++) {
        assertFalse(session.apply(state, displayed(session)));
        assertEquals(300, session.issueCount());
        assertEquals(256, session.applyIssues().size());
        assertThrows(UnsupportedOperationException.class, () -> session.applyIssues().clear());
      }
      state.document.setValidator(
          p -> {
            throw new IllegalStateException();
          });
      assertFalse(session.apply(state, displayed(session)));
      assertEquals(0, session.issueCount());
      assertTrue(session.applyIssues().isEmpty());
      assertTrue(session.applyFailure().contains("IllegalStateException"));
      assertEquals("+17", session.text("seed"));
      assertTrue(session.current(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      state.document.setValidator(NoiseCompiler::validate);
      assertTrue(session.apply(state, displayed(session)));
      assertEquals("", session.applyFailure());
      assertEquals(0, session.issueCount());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(17, state.project().project.seed);
    }
  }

  @Test
  void unchangedTextKeepsFeedbackButARealEditAndCancelClearIt() {
    try (var state = state()) {
      var session = new ProjectSettingsSession(state);
      session.edit("seed", "17");
      var feedback = session.validate();
      state.document.setValidator(p -> List.of(Diagnostic.error("REJECT", "", "Rejected")));
      assertFalse(session.apply(state));
      var rejection = session.applyIssues();
      session.edit("seed", "17");
      assertSame(feedback, session.validate());
      assertSame(rejection, session.applyIssues());
      session.edit("seed", "18");
      assertTrue(session.applyIssues().isEmpty());
      assertEquals(0, session.issueCount());
      session.cancel();
      assertEquals("", session.applyFailure());
      assertThrows(IllegalStateException.class, () -> session.apply(state));
    }
  }

  @Test
  void consumedPanelsDoNotReopenFromAnotherDrawAndNoOpApplyCannotRepeat() {
    try (var state = state()) {
      var panel = new ProjectSettingsPanel(state);
      assertTrue(panel.draw()); // No session: must not touch ImGui or silently open a buffer.
      assertTrue(panel.open(null));
      panel.cancel();
      assertTrue(panel.draw());
      assertFalse(panel.pendingChanges());
      assertTrue(panel.open(null));
      assertEquals("0", panel.text("seed"));
      panel.cancel();
      var session = new ProjectSettingsSession(state);
      assertTrue(session.apply(state, displayed(session)));
      assertTrue(session.closed());
      assertThrows(IllegalStateException.class, () -> session.apply(state, displayed(session)));
      assertEquals(0, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }
}
