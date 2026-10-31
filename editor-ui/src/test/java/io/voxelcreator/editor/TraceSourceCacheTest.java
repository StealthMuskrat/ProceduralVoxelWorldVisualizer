package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.storage.BlockPos;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceSourceCacheTest {
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

  private static EditorEvents.Target expression(TraceSnapshot trace) {
    return trace.rows(0).stream()
        .filter(
            row ->
                row.label().startsWith("Noise PROCESSING") && row.value().startsWith("EXPRESSION"))
        .findFirst()
        .orElseThrow()
        .target();
  }

  @Test
  void unchangedFramesReuseCommittedDefinitionsAndTheCacheRemainsBounded() {
    try (var state = state()) {
      var trace = capture(state);
      var cache = new TraceSourceCache();
      var target = expression(trace);
      for (int i = 0; i < 50; i++) {
        assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, target));
        assertEquals(
            TraceSourceCache.State.AVAILABLE,
            cache.evaluate(state, trace, trace.finalTypeTarget()));
      }
      assertEquals(2, cache.checks());
      for (int i = 1; i <= 64; i++)
        assertEquals(
            TraceSourceCache.State.MISSING_SOURCE,
            cache.evaluate(
                state,
                trace,
                new EditorEvents.Target(
                    state.project().project.uuid, new UUID(0, i), null, null, "", false)));
      assertEquals(TraceSourceCache.CAPACITY, cache.retained());
      assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, target));
      assertEquals(TraceSourceCache.CAPACITY, cache.retained());
      cache.clear();
      assertEquals(0, cache.retained());
    }
  }

  @Test
  void changedTypesDisableOnlyTheirSourceAndUndoRestoresTheExactDefinition() {
    try (var state = state()) {
      var trace = capture(state);
      var cache = new TraceSourceCache();
      var voxel = trace.finalTypeTarget();
      var control = expression(trace);
      assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, voxel));
      assertTrue(state.change(p -> p.voxelTypes.getFirst().id = "renamed"));
      assertEquals(TraceSourceCache.State.SOURCE_CHANGED, cache.evaluate(state, trace, voxel));
      assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, control));
      assertFalse(trace.available(state, voxel));
      state.document.undo();
      state.revision++;
      assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, voxel));
      assertTrue(trace.available(state, voxel));
    }
  }

  @Test
  void privateControlRemovalAndRestorationStayLiveWithoutRehashingTheCommittedResource() {
    try (var state = state()) {
      var trace = capture(state);
      var cache = new TraceSourceCache();
      var target = expression(trace);
      state.select("noise2D", target.resource());
      var draft = (Noise) state.draft;
      assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, target));
      long checks = cache.checks();
      var removed = draft.processing.removeFirst();
      assertEquals(target.control(), removed.uuid);
      assertEquals(
          TraceSourceCache.State.CONTROL_UNAVAILABLE, cache.evaluate(state, trace, target));
      assertEquals(checks, cache.checks());
      draft.processing.addFirst(removed);
      assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, target));
      assertEquals(checks, cache.checks());
    }
  }

  @Test
  void sameUuidOpenExpiresOldLinksButSaveKeepsTheSameCachedDefinition(@TempDir Path out)
      throws Exception {
    try (var state = state()) {
      var trace = capture(state);
      var cache = new TraceSourceCache();
      var target = trace.finalTypeTarget();
      cache.evaluate(state, trace, target);
      long checks = cache.checks();
      var file = out.resolve("saved.json");
      state.document.save(file);
      assertEquals(TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, target));
      assertEquals(checks, cache.checks());
      assertTrue(state.document.open(file).isEmpty());
      assertEquals(TraceSourceCache.State.OTHER_DOCUMENT, cache.evaluate(state, trace, target));
      assertEquals(0, cache.retained());
      assertEquals(checks, cache.checks());
      var current = capture(state);
      assertEquals(
          TraceSourceCache.State.AVAILABLE,
          cache.evaluate(state, current, current.finalTypeTarget()));
      assertEquals(checks + 1, cache.checks());
    }
  }

  @Test
  void directCanonicalReplacementAtEqualRevisionCannotReuseAPositiveCacheEntry() {
    try (var state = state()) {
      var trace = capture(state);
      var cache = new TraceSourceCache();
      var target = trace.finalTypeTarget();
      cache.evaluate(state, trace, target);
      long revision = state.revision;
      assertTrue(state.document.edit(p -> p.voxelTypes.getFirst().id = "renamed").isEmpty());
      assertEquals(revision, state.revision);
      assertEquals(TraceSourceCache.State.SOURCE_CHANGED, cache.evaluate(state, trace, target));
      assertEquals(2, cache.checks());
      var refreshed = capture(state);
      assertEquals(
          TraceSourceCache.State.AVAILABLE,
          cache.evaluate(state, refreshed, refreshed.finalTypeTarget()));
    }
  }

  @Test
  void missingAndTargetlessLinksHaveSeparateReadOnlyOutcomes() {
    try (var state = state()) {
      var trace = capture(state);
      var cache = new TraceSourceCache();
      var target = trace.finalTypeTarget();
      assertEquals(TraceSourceCache.State.NO_TARGET, cache.evaluate(state, trace, null));
      assertEquals(0, cache.checks());
      assertTrue(
          state.change(
              p -> {
                p.worldGenerator.layers.clear();
                p.voxelTypes.clear();
              }));
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var text = trace.details();
      assertEquals(TraceSourceCache.State.MISSING_SOURCE, cache.evaluate(state, trace, target));
      assertEquals(
          TraceSourceCache.State.AVAILABLE, cache.evaluate(state, trace, expression(trace)));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(text, trace.details());
    }
  }
}
