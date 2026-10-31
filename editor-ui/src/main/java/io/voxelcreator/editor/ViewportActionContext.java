package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.Coordinates;
import java.util.*;

/** Constant-size view command ownership; worker progress and camera position are not commands. */
record ViewportActionContext(
    Object document,
    ProjectData project,
    long revision,
    Object view,
    Object surface,
    int tab,
    long epoch,
    UUID noise,
    UUID stage,
    String representation,
    String stageMode,
    Object bookmarkOwner,
    boolean auto,
    boolean grid,
    Coordinates picked) {
  static ViewportActionContext capture(
      EditorState state,
      Object view,
      Object surface,
      int tab,
      long epoch,
      UUID noise,
      UUID stage,
      String representation,
      String stageMode,
      Object bookmarkOwner,
      Coordinates picked) {
    return new ViewportActionContext(
        state.document.sessionToken(),
        state.project(),
        state.revision,
        view,
        surface,
        tab,
        epoch,
        noise,
        stage,
        representation,
        stageMode,
        bookmarkOwner,
        state.user.editor.autoRegenerate,
        state.user.visualization.grid,
        picked);
  }

  boolean same(ViewportActionContext other) {
    return other != null
        && document == other.document
        && project == other.project
        && revision == other.revision
        && view == other.view
        && Objects.equals(surface, other.surface)
        && tab == other.tab
        && epoch == other.epoch
        && Objects.equals(noise, other.noise)
        && Objects.equals(stage, other.stage)
        && Objects.equals(representation, other.representation)
        && Objects.equals(stageMode, other.stageMode)
        && bookmarkOwner == other.bookmarkOwner
        && auto == other.auto
        && grid == other.grid
        && Objects.equals(picked, other.picked);
  }
}
