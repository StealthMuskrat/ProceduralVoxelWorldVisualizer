package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DraftSeedInputsTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (String id : List.of("a", "b")) {
                var n = new Noise();
                n.id = id;
                p.noise2D.add(n);
              }
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private void invalid(EditorState state, String key, String raw) {
    assertTrue(state.seedInputs.edit(state, key, "Seed offset", raw).isEmpty());
  }

  @Test
  void incompleteOverflowAndNonDecimalTextNeverApplyThePreviousSeed() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      long revision = state.revision, history = state.document.historyBytes();
      for (var raw : List.of("-", "+", "1e3", "0x20", " 3", "3 ", "١٢", "9223372036854775808")) {
        invalid(state, "seedOffset", raw);
        assertEquals(raw, state.seedInputs.text("seedOffset"));
        assertFalse(state.applyDraft());
        assertEquals("INVALID_SEED_TEXT", state.issues.getFirst().code());
        assertEquals(state.selected, state.issues.getFirst().sourceUuid());
        assertTrue(
            state.canNavigate(
                EditorEvents.Target.diagnostic(
                    state.project().project.uuid, state.issues.getFirst())));
        assertSame(draft, state.draft);
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      }
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void exactSignedLongValuesClearOnlyTheirOwnProblemAndCommitThroughTheUsualTransaction() {
    for (long value : new long[] {Long.MIN_VALUE, Long.MAX_VALUE})
      try (var state = state()) {
        invalid(state, "seedOffset", "-");
        invalid(state, "other/seedOffset", "+");
        var parsed =
            state.seedInputs.edit(state, "seedOffset", "Seed offset", Long.toString(value));
        assertEquals(value, parsed.orElseThrow());
        assertNull(state.seedInputs.text("seedOffset"));
        assertEquals(1, state.seedInputs.problems(state).size());
        state.seedInputs.discard(state, "other/seedOffset");
        ((Noise) state.draft).seedOffset = value;
        assertTrue(state.applyDraft());
        assertEquals(value, state.project().noise2D.getFirst().seedOffset);
        state.document.undo();
        assertEquals(0, state.project().noise2D.getFirst().seedOffset);
        state.document.redo();
        assertEquals(value, state.project().noise2D.getFirst().seedOffset);
      }
  }

  @Test
  void changedGateTreeDetachesOldTextFromAnIndexAndStillBlocksApplyUntilExplicitRestore() {
    try (var state = state()) {
      String path = "settings/fieldGates/children/0/seedOffset";
      invalid(state, path, "-");
      state.seedInputs.orphan(state, "settings/fieldGates");
      var orphan = state.seedInputs.problems(state).getFirst();
      assertTrue(orphan.orphan());
      assertEquals("-", orphan.text());
      assertNull(state.seedInputs.text(path));
      assertEquals(123, state.seedInputs.edit(state, path, "Seed", "123").orElseThrow());
      assertFalse(state.applyDraft());
      assertEquals("settings/fieldGates", state.issues.getFirst().parameterKey());
      state.seedInputs.discard(state, orphan.id());
      assertFalse(state.seedInputs.pending(state));
    }
  }

  @Test
  void prefixRestorationUsesPathBoundariesAndIncludesDetachedGateInputs() {
    try (var state = state()) {
      invalid(state, "settings/fieldGates/seedOffset", "-");
      invalid(state, "settings/fieldGates/children/0/seedOffset", "+");
      invalid(state, "settings/fieldGatesExtra/seedOffset", "bad");
      state.seedInputs.orphan(state, "settings/fieldGates");
      state.seedInputs.discardPrefix(state, "settings/fieldGates");
      assertEquals(1, state.seedInputs.problems(state).size());
      assertEquals("bad", state.seedInputs.text("settings/fieldGatesExtra/seedOffset"));
      state.seedInputs.clear(state);
      assertFalse(state.seedInputs.pending(state));
    }
  }

  @Test
  void replacementDraftSameUuidSelectionAndNewProjectClearOldOwnership() {
    try (var state = state()) {
      invalid(state, "seedOffset", "-");
      var owner = state.seedInputs.owner();
      state.refreshDraft();
      assertFalse(state.seedInputs.pending(state));
      assertNotSame(owner, state.seedInputs.owner());
      invalid(state, "seedOffset", "-");
      state.select("noise2D", state.selected);
      assertFalse(state.seedInputs.pending(state));
      invalid(state, "seedOffset", "-");
      state.newSession();
      assertFalse(state.seedInputs.pending(state));
    }
  }

  @Test
  void unrelatedProjectEditsPreserveTextOwnedByTheSamePrivateResourceDraft() {
    try (var state = state()) {
      invalid(state, "seedOffset", "-");
      var draft = state.draft;
      var owner = state.seedInputs.owner();
      state.change(p -> p.project.seed++);
      assertSame(draft, state.draft);
      assertSame(owner, state.seedInputs.owner());
      assertTrue(state.seedInputs.pending(state));
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void invalidInputStorageAndTextLengthsAreBoundedWithoutEvictingACommitBlocker() {
    try (var state = state()) {
      for (int i = 0; i < DraftSeedInputs.MAX_FIELDS; i++) invalid(state, "field/" + i, "-");
      assertThrows(
          IllegalStateException.class, () -> state.seedInputs.edit(state, "overflow", "Seed", "-"));
      assertEquals(DraftSeedInputs.MAX_FIELDS, state.seedInputs.problems(state).size());
      assertThrows(
          IllegalArgumentException.class,
          () -> state.seedInputs.edit(state, "field/0", "Seed", "1".repeat(129)));
      assertEquals("-", state.seedInputs.text("field/0"));
      assertFalse(state.applyDraft());
      state.seedInputs.discard(state, "field/0");
      invalid(state, "new", "-");
      assertEquals(DraftSeedInputs.MAX_FIELDS, state.seedInputs.problems(state).size());
    }
  }

  @Test
  void guardedNavigationSeesTextEvenWhenTheModelEqualsCommittedAndCancelPreservesIt() {
    try (var state = state()) {
      invalid(state, "seedOffset", "-");
      var source = state.draft;
      var navigation = new SearchNavigation(state);
      var target =
          new EditorEvents.Target(
              state.project().project.uuid,
              state.project().noise2D.getLast().uuid,
              null,
              null,
              "",
              false);
      assertEquals(SearchNavigation.Result.NEEDS_DECISION, navigation.request(target));
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          navigation.decide(SearchNavigation.Decision.APPLY));
      assertSame(source, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(
          SearchNavigation.Result.CANCELLED, navigation.decide(SearchNavigation.Decision.CANCEL));
      assertTrue(state.seedInputs.pending(state));
      navigation.request(target);
      assertEquals(
          SearchNavigation.Result.NAVIGATED, navigation.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(target.resource(), state.selected);
      assertFalse(state.seedInputs.pending(state));
    }
  }

  @Test
  void changedRawTextInvalidatesAnOutstandingDiscardDecisionEvenWithoutAModelEdit() {
    try (var state = state()) {
      invalid(state, "seedOffset", "-");
      var source = state.draft;
      var navigation = new SearchNavigation(state);
      var target =
          new EditorEvents.Target(
              state.project().project.uuid,
              state.project().noise2D.getLast().uuid,
              null,
              null,
              "",
              false);
      navigation.request(target);
      invalid(state, "seedOffset", "--");
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          navigation.decide(SearchNavigation.Decision.DISCARD));
      assertSame(source, state.draft);
      assertEquals("--", state.seedInputs.text("seedOffset"));
    }
  }
}
