package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred finite control vocabulary and field-local review ownership. */
class ViewportChoiceReviewTest {
  @Test
  void finiteControlsKeepSeparateExactVocabulariesAndImmutableOrder() {
    var option = ViewportChoicePanel.Option.class;
    assertEquals(
        List.of("Grayscale", "Heatmap", "Contour", "Threshold", "Histogram"),
        ViewportChoicePanel.Option.DISPLAY.values);
    assertEquals(
        List.of("Threshold Voxels", "Isosurface", "Volume", "Point Cloud", "Slices"),
        ViewportChoicePanel.Option.REPRESENTATION.values);
    assertEquals(List.of("X", "Y", "Z"), ViewportChoicePanel.Option.SLICE_NORMAL.values);
    assertEquals(
        List.of("Final", "Before", "After", "Only Changes"),
        ViewportChoicePanel.Option.STAGE_PREVIEW.values);
    for (var value : option.getEnumConstants()) {
      assertFalse(value.accepts(null));
      assertFalse(value.accepts(""));
      assertFalse(value.accepts("unknown"));
      assertThrows(UnsupportedOperationException.class, () -> value.values.clear());
    }
    assertTrue(ViewportChoicePanel.Option.DISPLAY.accepts("Threshold"));
    assertFalse(ViewportChoicePanel.Option.REPRESENTATION.accepts("Threshold"));
    assertTrue(ViewportChoicePanel.Option.REPRESENTATION.accepts("Threshold Voxels"));
    assertFalse(ViewportChoicePanel.Option.SLICE_NORMAL.accepts("x"));
    assertEquals(
        ViewportChoicePanel.Option.DISPLAY.values,
        ViewportCommands.menuCommands(0).stream().limit(5).map(command -> command.label).toList());
  }

  @Test
  void fieldValueChangesRetireReviewEvenWhenViewContextIsUnchanged() {
    try (var state = new EditorState(new UserConfig(), false)) {
      Object view = new Object();
      var source =
          ViewportActionContext.capture(
              state, view, "surface", 1, 7, null, null, "Slices", "Final", null, null);
      var review = new ViewportChoicePanel.Review(source, "Y");
      assertTrue(review.same(new ViewportChoicePanel.Review(source, new String("Y"))));
      assertFalse(review.same(new ViewportChoicePanel.Review(source, "Z")));
      state.draft = new ProjectData.Noise();
      state.selected = UUID.randomUUID();
      assertTrue(
          review.same(
              new ViewportChoicePanel.Review(
                  ViewportActionContext.capture(
                      state, view, "surface", 1, 7, null, null, "Slices", "Final", null, null),
                  "Y")));
      assertFalse(
          review.same(
              new ViewportChoicePanel.Review(
                  ViewportActionContext.capture(
                      state, view, "surface", 1, 8, null, null, "Slices", "Final", null, null),
                  "Y")));
    }
  }
}
