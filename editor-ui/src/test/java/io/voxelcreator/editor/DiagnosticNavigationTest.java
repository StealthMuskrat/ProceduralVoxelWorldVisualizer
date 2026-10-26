package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiagnosticNavigationTest {
  private static ProjectData project() {
    var p = new ProjectData();
    var n = new Noise();
    n.id = "n";
    var item = ProcessingRegistry.create("EXPRESSION", "formula");
    item.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
    n.processing.add(item);
    n.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(n);
    return p;
  }

  private static EditorEvents.Target target(ProjectData p) {
    var n = p.noise2D.getFirst();
    return new EditorEvents.Target(
        p.project.uuid,
        n.uuid,
        n.processing.getFirst().uuid,
        "expression",
        "/noise2D/0/processing/0/parameters/expression",
        false);
  }

  @Test
  void bindingPathsSelectExactDecodedInputRatherThanLeafOrPrefix() {
    var p = project();
    var n = p.noise2D.getFirst();
    var item = n.processing.getFirst();
    for (String collection : List.of("processing", "coordinateTransforms", "control")) {
      var diagnostic =
          new Diagnostic(
              "BAD",
              Diagnostic.Severity.ERROR,
              "bad",
              "/noise2D/0/" + collection + "/0/inputs/a~1b~0c/constant",
              n.uuid,
              item.uuid,
              "constant");
      var target = EditorEvents.Target.diagnostic(p.project.uuid, diagnostic);
      assertEquals("inputs/a/b~c", target.parameter());
      assertTrue(DiagnosticNavigation.inputTarget(target, item.uuid, "a/b~c"));
      assertFalse(DiagnosticNavigation.inputTarget(target, item.uuid, "a"));
      assertFalse(DiagnosticNavigation.inputTarget(target, UUID.randomUUID(), "a/b~c"));
      assertEquals(diagnostic.jsonPointer(), target.pointer());
    }
  }

  @Test
  void ordinaryAndParameterNamedInputsDoNotBecomeBindingRoutes() {
    var p = project();
    var n = p.noise2D.getFirst();
    var item = n.processing.getFirst();
    for (String pointer :
        List.of(
            "/noise2D/0/processing/0/parameters/inputs/value",
            "/noise2D/0/processing/0/parameters/expression",
            "/runtime/inputs/x")) {
      var diagnostic =
          new Diagnostic(
              "BAD", Diagnostic.Severity.ERROR, "bad", pointer, n.uuid, item.uuid, "expression");
      assertEquals("expression", DiagnosticNavigation.parameter(diagnostic));
    }
    assertFalse(DiagnosticNavigation.inputTarget(null, item.uuid, "a"));
  }

  @Test
  void historicalUuidTargetsSurviveReorderButNotDeletionOrNewProjects() {
    var p = project();
    var t = target(p);
    var n = p.noise2D.getFirst();
    var another = new Noise();
    another.id = "another";
    p.noise2D.addFirst(another);
    assertTrue(DiagnosticNavigation.available(p, null, null, t));
    n.processing.clear();
    assertFalse(DiagnosticNavigation.available(p, null, null, t));
    assertFalse(DiagnosticNavigation.available(project(), null, null, t));
  }

  @Test
  void currentDraftControlsAreUsedWithoutFallingBackToDifferentCommittedControls() {
    var p = project();
    var t = target(p);
    var draft =
        ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(p.noise2D.getFirst()), Noise.class);
    draft.processing.clear();
    assertFalse(DiagnosticNavigation.available(p, t.resource(), draft, t));
    assertTrue(DiagnosticNavigation.available(p, null, null, t));
    var added = ProcessingRegistry.create("EXPRESSION", "new");
    draft.processing.add(added);
    var addedTarget =
        new EditorEvents.Target(t.project(), t.resource(), added.uuid, "expression", "", false);
    assertTrue(DiagnosticNavigation.available(p, t.resource(), draft, addedTarget));
  }

  @Test
  void rejectedDraftNavigationPreservesTextDocumentRevisionAndUndoHistory() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var p = project();
      assertTrue(state.change(active -> active.noise2D.addAll(p.noise2D)));
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      var item = draft.processing.getFirst();
      item.parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
      String committed = ProjectIO.JSON.valueToTree(state.project()).toString();
      long revision = state.revision, history = state.document.historyBytes();
      assertFalse(state.applyDraft());
      assertEquals("Rejected edit", state.validationScope);
      var diagnostic = state.issues.getFirst();
      var t = EditorEvents.Target.diagnostic(state.project().project.uuid, diagnostic);
      state.multiSelection.add(UUID.randomUUID());
      assertTrue(state.navigate(t));
      assertSame(draft, state.draft);
      assertEquals(1, state.multiSelection.size());
      assertEquals("sin(", item.parameters.get("expression").asText());
      assertEquals(committed, ProjectIO.JSON.valueToTree(state.project()).toString());
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertTrue(state.events.snapshot().getFirst().actionRequired());
      state.refreshDraft();
      assertTrue(state.issues.isEmpty());
      assertEquals(
          "x", ((Noise) state.draft).processing.getFirst().parameters.get("expression").asText());
    }
  }

  @Test
  void validationAndEventReadStateNeverDirtyOrCreateHistory() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertFalse(state.document.dirty());
      assertTrue(state.validateProject().isEmpty());
      assertEquals("PROJECT_VALID", state.events.snapshot().getFirst().code());
      state.events.markAllRead();
      state.events.resolve(state.events.snapshot().getFirst().id());
      assertFalse(state.document.dirty());
      assertEquals(0, state.document.historyBytes());
      assertEquals(0, state.uiQueue.size());
    }
  }

  @Test
  void projectSettingsTargetsAreAvailableOnlyForTheirProject() {
    var p = project();
    var d = Diagnostic.error("INVALID_BOUNDS", "/project/minY", "bad");
    var t = EditorEvents.Target.diagnostic(p.project.uuid, d);
    assertTrue(t.settings());
    assertTrue(DiagnosticNavigation.available(p, null, null, t));
    assertFalse(DiagnosticNavigation.available(project(), null, null, t));
    assertFalse(
        DiagnosticNavigation.available(
            p,
            null,
            null,
            EditorEvents.Target.diagnostic(
                p.project.uuid, Diagnostic.error("RUNTIME", "/runtime", "bad"))));
  }
}
