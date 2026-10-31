package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.render.RenderPressure;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred exact paused source/limit ownership without mutable progress in the review. */
class PreviewResumeContextTest {
  private final Object view = new Object(), source = new Object();

  private PreviewResumeContext capture(
      EditorState state,
      PreviewResumeContext.Kind kind,
      RenderPressure p,
      Object resource,
      String key,
      int extent,
      boolean rejected) {
    var context =
        ViewportActionContext.capture(
            state, view, "surface", 1, 7, null, null, "Volume", "Final", null, null);
    return PreviewResumeContext.capture(state, context, kind, p, resource, key, extent, rejected);
  }

  @Test
  void exactPauseSourceKindExtentAndRejectionOwnTheDecision() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var p = new RenderPressure();
      p.pause();
      var kind = PreviewResumeContext.Kind.VOLUME;
      var review = capture(state, kind, p, source, "source", 64, true);
      assertTrue(review.same(capture(state, kind, p, source, new String("source"), 64, true)));
      var other = new RenderPressure();
      other.pause();
      assertFalse(review.same(capture(state, kind, other, source, "source", 64, true)));
      assertFalse(
          review.same(
              capture(state, PreviewResumeContext.Kind.STREAMING, p, source, "source", 64, true)));
      assertFalse(review.same(capture(state, kind, p, new Object(), "source", 64, true)));
      assertFalse(review.same(capture(state, kind, p, source, "other", 64, true)));
      assertFalse(review.same(capture(state, kind, p, source, "source", 32, true)));
      assertFalse(review.same(capture(state, kind, p, source, "source", 64, false)));
      p.resume(p.pauseVersion(), 1);
      p.pause();
      assertFalse(review.same(capture(state, kind, p, source, "source", 64, true)));
      assertTrue(review.paused());
      assertEquals(1, review.pauseVersion());
    }
  }

  @Test
  void limitsAndLevelChangesRetireReviewWhileUnrelatedPrivateInputDoesNot() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var p = new RenderPressure();
      p.pause();
      var kind = PreviewResumeContext.Kind.VOLUME;
      var review = capture(state, kind, p, source, "source", 64, false);
      state.draft = new ProjectData.Noise();
      state.selected = UUID.randomUUID();
      assertTrue(review.same(capture(state, kind, p, source, "source", 64, false)));
      state.user.performance.sceneCpuMiB++;
      assertFalse(review.same(capture(state, kind, p, source, "source", 64, false)));
      assertEquals(
          state.user.performance.sceneCpuMiB - 1,
          review.limits().get(PerformancePreferences.Field.SCENE_CPU_MIB));
      state.user.performance.sceneCpuMiB--;
      p.observe(1_000_000_000L, 1, 1, 10, true);
      assertEquals(review.pauseVersion(), p.pauseVersion());
      assertFalse(review.same(capture(state, kind, p, source, "source", 64, false)));
    }
  }

  @Test
  void sameUuidDocumentReplacementRetiresOldPauseReview(@TempDir Path out) throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var file = out.resolve("project.json");
      state.document.save(file);
      var p = new RenderPressure();
      p.pause();
      var review =
          capture(state, PreviewResumeContext.Kind.STREAMING, p, source, "source", 64, false);
      long revision = state.revision;
      assertTrue(state.document.open(file).isEmpty());
      assertEquals(revision, state.revision);
      assertEquals(review.view().project().project.uuid, state.project().project.uuid);
      assertFalse(
          review.same(
              capture(state, PreviewResumeContext.Kind.STREAMING, p, source, "source", 64, false)));
      assertTrue(p.paused());
    }
  }
}
