package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.BookmarkTransactions.Tab;
import java.util.*;

/** Displayed bookmark/tab and document ownership, independent of a moving camera. */
record BookmarkActionContext(
    Object lifetime,
    ProjectData project,
    long revision,
    Tab tab,
    UUID selected,
    BookmarkIndex.Row row,
    Object dialog,
    boolean pending) {
  static BookmarkActionContext capture(
      EditorState state,
      Tab tab,
      UUID selected,
      BookmarkIndex.Row row,
      Object dialog,
      boolean pending) {
    return new BookmarkActionContext(
        state.document.sessionToken(),
        state.project(),
        state.revision,
        tab,
        selected,
        row,
        dialog,
        pending);
  }

  boolean same(BookmarkActionContext other) {
    return other != null
        && lifetime == other.lifetime
        && project == other.project
        && revision == other.revision
        && tab == other.tab
        && Objects.equals(selected, other.selected)
        && row == other.row
        && dialog == other.dialog
        && pending == other.pending;
  }

  boolean current(
      EditorState state,
      Tab currentTab,
      UUID currentSelection,
      Object currentDialog,
      boolean currentPending) {
    return lifetime == state.document.sessionToken()
        && project == state.project()
        && revision == state.revision
        && tab == currentTab
        && Objects.equals(selected, currentSelection)
        && dialog == currentDialog
        && pending == currentPending
        && (row == null || rowCurrent(state, tab, row));
  }

  static boolean rowCurrent(EditorState state, Tab tab, BookmarkIndex.Row row) {
    var actual = BookmarkTransactions.find(state.project(), tab, row.uuid());
    return actual != null
        && Objects.equals(actual.name, row.name())
        && BookmarkTransactions.coordinates(actual).equals(row.coordinates());
  }
}
