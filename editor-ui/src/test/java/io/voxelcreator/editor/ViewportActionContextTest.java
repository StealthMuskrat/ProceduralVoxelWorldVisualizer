package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.Coordinates;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred view command ownership and failed preference publication boundaries. */
class ViewportActionContextTest {
  private final Object view = new Object();
  private final UUID noise = UUID.randomUUID(), stage = UUID.randomUUID();

  private ViewportActionContext capture(
      EditorState state,
      Object target,
      Object surface,
      int tab,
      long epoch,
      Object dialog,
      Coordinates picked) {
    return ViewportActionContext.capture(
        state, target, surface, tab, epoch, noise, stage, "Grayscale", "Final", dialog, picked);
  }

  @Test
  void exactViewTabSurfaceEpochAndDialogOwnCommands() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var owner = new String("dialog");
      var point = new Coordinates(2, null, 3);
      var review = capture(state, view, "surface", 0, 4, owner, point);
      assertTrue(review.same(capture(state, view, new String("surface"), 0, 4, owner, point)));
      assertFalse(review.same(capture(state, new Object(), "surface", 0, 4, owner, point)));
      assertFalse(review.same(capture(state, view, "other", 0, 4, owner, point)));
      assertFalse(review.same(capture(state, view, "surface", 1, 4, owner, point)));
      assertFalse(review.same(capture(state, view, "surface", 0, 5, owner, point)));
      assertFalse(review.same(capture(state, view, "surface", 0, 4, new String("dialog"), point)));
      assertFalse(
          review.same(capture(state, view, "surface", 0, 4, owner, new Coordinates(3, null, 3))));
    }
  }

  @Test
  void sourceModesAndOwnedPreferenceChangesRetireReviewWhilePropertiesDoesNot() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var review = capture(state, view, "surface", 0, 4, null, null);
      state.draft = new ProjectData.Noise();
      state.selected = UUID.randomUUID();
      assertTrue(review.same(capture(state, view, "surface", 0, 4, null, null)));
      for (var next :
          List.of(
              ViewportActionContext.capture(
                  state,
                  view,
                  "surface",
                  0,
                  4,
                  UUID.randomUUID(),
                  stage,
                  "Grayscale",
                  "Final",
                  null,
                  null),
              ViewportActionContext.capture(
                  state,
                  view,
                  "surface",
                  0,
                  4,
                  noise,
                  UUID.randomUUID(),
                  "Grayscale",
                  "Final",
                  null,
                  null),
              ViewportActionContext.capture(
                  state, view, "surface", 0, 4, noise, stage, "Heatmap", "Final", null, null),
              ViewportActionContext.capture(
                  state, view, "surface", 0, 4, noise, stage, "Grayscale", "Before", null, null)))
        assertFalse(review.same(next));
      state.user.editor.autoRegenerate = !review.auto();
      assertFalse(review.same(capture(state, view, "surface", 0, 4, null, null)));
      state.user.editor.autoRegenerate = review.auto();
      state.user.visualization.grid = !review.grid();
      assertFalse(review.same(capture(state, view, "surface", 0, 4, null, null)));
    }
  }

  @Test
  void sameUuidReplacementAndCanonicalRevisionRetireReview(@TempDir Path out) throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var file = out.resolve("project.json");
      state.document.save(file);
      var before = capture(state, view, "surface", 0, 4, null, null);
      assertTrue(state.document.open(file).isEmpty());
      assertEquals(before.project().project.uuid, state.project().project.uuid);
      assertFalse(before.same(capture(state, view, "surface", 0, 4, null, null)));
      var next = capture(state, view, "surface", 0, 4, null, null);
      state.revision++;
      assertFalse(next.same(capture(state, view, "surface", 0, 4, null, null)));
    }
  }

  @Test
  void failedAutoSavePreservesLiveValueAndDestinationThenRetryMergesOtherPreferences(
      @TempDir Path out) throws Exception {
    var user = new UserConfig();
    boolean initial = user.editor.autoRegenerate;
    var occupied = out.resolve("occupied");
    Files.createDirectory(occupied);
    Files.writeString(occupied.resolve("keep"), "original");
    assertThrows(IllegalStateException.class, () -> user.applyAutoRegenerate(!initial, occupied));
    assertEquals(initial, user.editor.autoRegenerate);
    assertEquals("original", Files.readString(occupied.resolve("keep")));
    user.workspace.width = 1777;
    var before = ProjectIO.JSON.valueToTree(user);
    var expected = before.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("editor"))
        .put("autoRegenerate", !initial);
    var file = out.resolve("user.json");
    user.applyAutoRegenerate(!initial, file);
    assertEquals(expected, ProjectIO.JSON.readTree(file.toFile()));
    assertEquals(expected, ProjectIO.JSON.valueToTree(user));
    assertEquals(1777, user.workspace.width);
    try (var files = Files.list(out)) {
      assertEquals(Set.of(file, occupied), files.collect(java.util.stream.Collectors.toSet()));
    }
  }
}
