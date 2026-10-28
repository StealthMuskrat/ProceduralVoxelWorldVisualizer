package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred contracts for explicit preview review, reload and separate private Properties state. */
class ImportEditSessionTest {
  @Test
  void reusedValidatorDiagnosticsStayVisibleAcrossRepeatedAttemptsAndThrownFailureCanRetry() {
    try (var state = state()) {
      var session = new ImportEditSession(state, incoming());
      var preview = session.preview();
      session.markShown(preview);
      var rejection = List.of(Diagnostic.error("SAME_LIST", "/fixture", "Rejected"));
      state.document.setValidator(p -> rejection);
      for (int attempt = 0; attempt < 2; attempt++) {
        assertEquals(ImportEditSession.Result.REJECTED, session.apply());
        assertEquals(1, session.issueCount());
        assertEquals(rejection, session.applyIssues());
      }
      state.document.setValidator(
          p -> {
            throw new IllegalStateException();
          });
      assertEquals(ImportEditSession.Result.REJECTED, session.apply());
      assertEquals(0, session.issueCount());
      assertTrue(session.applyIssues().isEmpty());
      assertSame(preview, session.preview());
      assertTrue(session.canApply());
      state.document.setValidator(NoiseCompiler::validate);
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
    }
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              p.project.seed = 41;
              p.noise2D.add(noise("editing", .125));
              p.noise2D.add(noise("shared", .25));
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private ProjectData incoming() {
    var p = new ProjectData();
    p.noise2D.add(noise("shared", .25));
    p.noise2D.add(noise("added", .75));
    return p;
  }

  private ResourceImport.Row row(ResourceImport.Plan p, String id) {
    return p.rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void applyRequiresTheExactDisplayedPreviewAndPublishesOneOrdinaryUndoTransaction() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var session = new ImportEditSession(state, incoming());
      var preview = session.preview();
      assertEquals(ImportEditSession.Result.NOT_REVIEWED, session.apply());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      session.markShown(preview);
      assertTrue(session.canApply());
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertTrue(session.closed());
      assertEquals(4, state.project().noise2D.size());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void choiceChangesRequireAnotherDisplayedPlanAndKeepCopyIdentitiesStable() {
    try (var state = state()) {
      var session = new ImportEditSession(state, incoming());
      var original = session.preview();
      UUID shared = row(original, "shared").uuid(), added = row(original, "added").targetUuid();
      session.markShown(original);
      session.choose(shared, ResourceImport.Choice.REUSE);
      session.markShown(original);
      assertFalse(session.canApply());
      assertEquals(ImportEditSession.Result.NOT_REVIEWED, session.apply());
      var next = session.preview();
      assertEquals(added, row(next, "added").targetUuid());
      assertEquals(state.project().noise2D.getLast().uuid, row(next, "shared").targetUuid());
      session.markShown(next);
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertEquals(3, state.project().noise2D.size());
    }
  }

  @Test
  void unchangedChoicesKeepTheReviewedPlanAndChoiceMapsCannotMutateIt() {
    try (var state = state()) {
      var session = new ImportEditSession(state, incoming());
      var preview = session.preview();
      session.markShown(preview);
      session.choose(row(preview, "shared").uuid(), ResourceImport.Choice.COPY);
      assertSame(preview, session.preview());
      assertTrue(session.canApply());
      assertThrows(
          UnsupportedOperationException.class,
          () -> session.choices().put(UUID.randomUUID(), ResourceImport.Choice.SKIP));
      assertThrows(
          IllegalArgumentException.class,
          () -> session.choose(UUID.randomUUID(), ResourceImport.Choice.COPY));
      assertThrows(
          IllegalArgumentException.class, () -> session.choose(row(preview, "added").uuid(), null));
    }
  }

  @Test
  void incomingAndReturnedCandidatesAreOwnedAndDoNotFollowExternalMutation() {
    try (var state = state()) {
      var incoming = incoming();
      var session = new ImportEditSession(state, incoming);
      var preview = session.preview();
      UUID target = row(preview, "added").targetUuid();
      incoming.noise2D.clear();
      preview.candidate().noise2D.clear();
      session.markShown(preview);
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertEquals(
          .75,
          ((Noise) EditorState.find(state.project(), target)).algorithm.parameters.get("value"));
    }
  }

  @Test
  void changedDestinationRetainsTheOldPreviewUntilExplicitReloadPreservesChoices() {
    try (var state = state()) {
      var session = new ImportEditSession(state, incoming());
      var old = session.preview();
      UUID shared = row(old, "shared").uuid();
      session.choose(shared, ResourceImport.Choice.REUSE);
      old = session.preview();
      session.markShown(old);
      UUID oldCopy = row(old, "added").targetUuid();
      state.change(p -> p.project.seed = 42);
      assertFalse(session.current());
      assertSame(old, session.preview());
      assertEquals(ImportEditSession.Result.STALE, session.apply());
      assertThrows(
          IllegalStateException.class, () -> session.choose(shared, ResourceImport.Choice.SKIP));
      session.reload();
      var next = session.preview();
      assertEquals(ResourceImport.Choice.REUSE, session.choice(shared));
      assertNotEquals(oldCopy, row(next, "added").targetUuid());
      assertFalse(session.canApply());
      session.markShown(next);
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertEquals(42, state.project().project.seed);
    }
  }

  @Test
  void aReplacedProjectCannotReuseTheOldWorkspaceAndCancelCannotPublish() {
    try (var state = state()) {
      var session = new ImportEditSession(state, incoming());
      session.markShown(session.preview());
      state.newSession();
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertFalse(session.canReload());
      assertThrows(IllegalStateException.class, session::reload);
      assertEquals(ImportEditSession.Result.STALE, session.apply());
      session.cancel();
      assertEquals(ImportEditSession.Result.CLOSED, session.apply());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void failedConfiguredValidationRetainsPreviewIdentityPrivateInputHistoryAndBoundedDetails() {
    try (var state = state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var draft = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, bytes = state.document.historyBytes();
      var session = new ImportEditSession(state, incoming());
      var preview = session.preview();
      UUID target = row(preview, "added").targetUuid();
      session.markShown(preview);
      state.document.setValidator(
          p ->
              java.util.stream.IntStream.range(0, 300)
                  .mapToObj(
                      i -> Diagnostic.error("IMPORT_FIXTURE", "/fixture/" + i, "Rejected fixture"))
                  .toList());
      assertEquals(ImportEditSession.Result.REJECTED, session.apply());
      assertEquals(300, session.issueCount());
      assertEquals(256, session.applyIssues().size());
      assertSame(preview, session.preview());
      assertTrue(session.current());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      state.document.setValidator(NoiseCompiler::validate);
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertNotNull(EditorState.find(state.project(), target));
      assertSame(draft, state.draft);
    }
  }

  @Test
  void enabledCompilerFailureIsRejectedWithoutClosingAndSkipCanResolveIt() {
    try (var state = state()) {
      var incoming = new ProjectData();
      var n = noise("bad", .5);
      var expression = ProcessingRegistry.create("EXPRESSION", "formula");
      expression.parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
      n.processing.add(expression);
      n.outputBinding = Binding.earlier(expression.uuid);
      incoming.noise2D.add(n);
      var session = new ImportEditSession(state, incoming);
      var preview = session.preview();
      assertTrue(preview.valid());
      session.markShown(preview);
      assertEquals(ImportEditSession.Result.REJECTED, session.apply());
      assertFalse(session.closed());
      session.choose(n.uuid, ResourceImport.Choice.SKIP);
      session.markShown(session.preview());
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertEquals(2, state.project().noise2D.size());
    }
  }

  @Test
  void reusedCompatibilityIsRecheckedOnReloadAndCopyCannotReplaceTheChangedExistingSource() {
    try (var state = state()) {
      var session = new ImportEditSession(state, incoming());
      UUID shared = row(session.preview(), "shared").uuid();
      session.choose(shared, ResourceImport.Choice.REUSE);
      state.change(p -> p.noise2D.getLast().algorithm.parameters.put("value", .5));
      session.reload();
      var invalid = session.preview();
      assertEquals(ResourceImport.Choice.REUSE, session.choice(shared));
      assertFalse(invalid.valid());
      session.markShown(invalid);
      assertFalse(session.canApply());
      session.choose(shared, ResourceImport.Choice.COPY);
      var valid = session.preview();
      session.markShown(valid);
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertEquals(.5, state.project().noise2D.get(1).algorithm.parameters.get("value"));
      assertEquals(
          .25,
          ((Noise) EditorState.find(state.project(), row(valid, "shared").targetUuid()))
              .algorithm.parameters.get("value"));
    }
  }

  @Test
  void skippedNoiseExcludesItsPlacementButKeepsTheUnrelatedLayerAndLiveDefaults() {
    try (var state = state()) {
      var incoming = new ProjectData();
      var n = noise("field", 1);
      incoming.noise2D.add(n);
      var layer = new Layer();
      layer.id = "layer";
      var op = new Operation();
      op.id = "operation";
      op.noiseGeneratorUuid = n.uuid;
      layer.operations.add(op);
      incoming.worldGenerator.layers.add(layer);
      var session = new ImportEditSession(state, incoming);
      session.choose(n.uuid, ResourceImport.Choice.SKIP);
      var preview = session.preview();
      assertTrue(preview.valid());
      assertTrue(preview.candidate().worldGenerator.layers.getFirst().operations.isEmpty());
      session.markShown(preview);
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertEquals(2, state.project().noise2D.size());
      assertEquals("NO_CHANGE", state.project().worldGenerator.layers.getFirst().defaults.action);
    }
  }

  @Test
  void separatePropertiesEditsAndSelectionRemainExactAcrossSuccessfulImport() {
    try (var state = state()) {
      var session = new ImportEditSession(state, incoming());
      state.select("noise2D", state.project().noise2D.getLast().uuid);
      var draft = (Noise) state.draft;
      draft.seedOffset = 43;
      state.seedInputs.edit(state, "seedOffset", "Seed", "+");
      UUID selected = state.selected;
      var user = ProjectIO.JSON.valueToTree(state.user);
      session.markShown(session.preview());
      assertEquals(ImportEditSession.Result.APPLIED, session.apply());
      assertSame(draft, state.draft);
      assertEquals(selected, state.selected);
      assertEquals(Set.of(selected), state.multiSelection);
      assertEquals("+", state.seedInputs.text("seedOffset"));
      assertEquals(0, ((Noise) EditorState.find(state.project(), selected)).seedOffset);
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
    }
  }

  @Test
  void revisionOnlyChangesRequireReloadAndInPlaceChangesStillFailTheModelPublicationCheck() {
    for (boolean tracked : new boolean[] {false, true})
      try (var state = state()) {
        var session = new ImportEditSession(state, incoming());
        var preview = session.preview();
        session.markShown(preview);
        if (tracked) state.change(p -> {});
        else
          state.project().project.seed =
              42; // Deliberate unsupported mutation exercises the model boundary.
        assertEquals(ImportEditSession.Result.STALE, session.apply());
        assertSame(preview, session.preview());
        assertEquals(2, state.project().noise2D.size());
      }
  }
}
