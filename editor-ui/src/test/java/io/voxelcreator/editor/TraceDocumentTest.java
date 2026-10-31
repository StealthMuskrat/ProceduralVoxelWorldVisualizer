package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceDocumentTest {
  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(TraceWorkflowProbe::fixture));
    return state;
  }

  private static TraceSnapshot capture(EditorState state) {
    var source = ProjectIO.copy(state.project());
    try (var world = state.engine.openWorld(new CompiledProject(source))) {
      return new TraceSnapshot(
          state.document.sessionToken(),
          source,
          state.revision,
          world.trace(new BlockPos(2, 0, 3)));
    }
  }

  @Test
  void equalLookingTokensAndContextFreeQueriesCannotCertifyABoundTrace() {
    var source = new ProjectData();
    var token = new String("document");
    var trace =
        new TraceSnapshot(
            token, source, 7, new GenerationTrace(new BlockPos(1, 2, 3), 0, List.of()));
    assertEquals(TraceSnapshot.Freshness.CURRENT, trace.freshness(token, source.project.uuid, 7));
    assertEquals(
        TraceSnapshot.Freshness.PROJECT_CHANGED,
        trace.freshness(new String(token), source.project.uuid, 7));
    assertEquals(TraceSnapshot.Freshness.PROJECT_CHANGED, trace.freshness(source.project.uuid, 7));
    assertEquals(
        TraceSnapshot.Freshness.PROJECT_CHANGED, trace.freshness(token, source.project.uuid, 8));
    assertEquals(
        TraceSnapshot.Freshness.OTHER_PROJECT, trace.freshness(token, UUID.randomUUID(), 7));
  }

  @Test
  void identicalReopenAtTheSameRevisionRetainsHistoryButRetiresAllOldSourceLinks(@TempDir Path out)
      throws Exception {
    try (var state = state()) {
      var trace = capture(state);
      var details = trace.details();
      long revision = state.revision;
      var event = state.publishTrace(trace);
      var file = out.resolve("project.json");
      state.document.save(file);
      assertTrue(trace.available(state, trace.finalTypeTarget()));
      assertTrue(state.document.open(file).isEmpty());
      assertEquals(revision, state.revision);
      assertSame(trace, state.traces.get(event).orElseThrow());
      assertEquals(TraceSnapshot.Freshness.PROJECT_CHANGED, trace.freshness(state));
      assertTrue(trace.reopened(state));
      assertFalse(trace.sameDocument(state));
      assertFalse(trace.available(state, trace.finalTypeTarget()));
      for (int i = 0; i < trace.trace().steps().size(); i++)
        for (var row : trace.rows(i))
          if (row.target() != null) assertFalse(trace.available(state, row.target()));
      assertEquals(details, trace.details());
    }
  }

  @Test
  void saveAndFailedOpenDoNotExpireTheCurrentTraceOrItsLinks(@TempDir Path out) throws Exception {
    try (var state = state()) {
      var trace = capture(state);
      var owner = state.document.sessionToken();
      state.document.save(out.resolve("save-as.json"));
      var bad = out.resolve("bad.json");
      Files.writeString(bad, "{}");
      assertFalse(state.document.open(bad).isEmpty());
      assertThrows(
          java.io.IOException.class, () -> state.document.open(out.resolve("absent.json")));
      assertSame(owner, state.document.sessionToken());
      assertFalse(trace.reopened(state));
      assertTrue(trace.sameDocument(state));
      assertEquals(TraceSnapshot.Freshness.CURRENT, trace.freshness(state));
      assertTrue(trace.available(state, trace.finalTypeTarget()));
    }
  }

  @Test
  void sameDocumentEditsKeepUnchangedLinksAndUndoCannotRewriteSnapshotFreshness() {
    try (var state = state()) {
      var trace = capture(state);
      var owner = state.document.sessionToken();
      var target = trace.finalTypeTarget();
      var details = trace.details();
      assertTrue(state.change(p -> p.project.seed++));
      assertSame(owner, state.document.sessionToken());
      assertFalse(trace.reopened(state));
      assertTrue(trace.available(state, target));
      assertEquals(TraceSnapshot.Freshness.PROJECT_CHANGED, trace.freshness(state));
      assertTrue(state.change(p -> p.voxelTypes.getFirst().id = "renamed"));
      assertFalse(trace.available(state, target));
      state.document.undo();
      state.revision++;
      assertTrue(trace.available(state, target));
      assertEquals(TraceSnapshot.Freshness.PROJECT_CHANGED, trace.freshness(state));
      assertEquals(details, trace.details());
    }
  }

  @Test
  void anExplicitNewTraceRebindsTheCurrentDocumentWithoutMutatingTheHistoricalOne(@TempDir Path out)
      throws Exception {
    try (var state = state()) {
      var old = capture(state);
      var file = out.resolve("project.json");
      state.document.save(file);
      assertTrue(state.document.open(file).isEmpty());
      var next = capture(state);
      assertEquals(old.trace(), next.trace());
      assertEquals(old.details(), next.details());
      assertEquals(old.revision(), next.revision());
      assertEquals(TraceSnapshot.Freshness.PROJECT_CHANGED, old.freshness(state));
      assertFalse(old.available(state, old.finalTypeTarget()));
      assertEquals(TraceSnapshot.Freshness.CURRENT, next.freshness(state));
      assertTrue(next.available(state, next.finalTypeTarget()));
    }
  }

  @Test
  void legacyPresentationsRetainRevisionSemanticsAndBoundForeignIdentityCannotBeBorrowed() {
    var source = new ProjectData();
    var owner = new Object();
    var result = new GenerationTrace(new BlockPos(-1, 2, 3), 0, List.of());
    var legacy = new TraceSnapshot(source, 7, result);
    var bound = new TraceSnapshot(owner, source, 7, result);
    var project = source.project.uuid;
    var details = bound.details();
    assertEquals(TraceSnapshot.Freshness.CURRENT, legacy.freshness(new Object(), project, 7));
    source.project.uuid = UUID.randomUUID();
    assertEquals(TraceSnapshot.Freshness.CURRENT, bound.freshness(owner, project, 7));
    assertEquals(
        TraceSnapshot.Freshness.OTHER_PROJECT, bound.freshness(owner, source.project.uuid, 7));
    assertEquals(details, bound.details());
    assertEquals(project, bound.project());
  }
}
